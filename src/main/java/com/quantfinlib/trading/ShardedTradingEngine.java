package com.quantfinlib.trading;

import com.quantfinlib.marketdata.HftMarketDataBus;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntFunction;

/**
 * Horizontal scaling as shipped machinery, not a recipe: N independent
 * {@code bus → risk gate → order gateway} stacks (one consumer core and one
 * venue core per shard) behind a single symbol-routing facade. The scaling
 * model is <b>shared-nothing</b>: shards never touch each other's state, so
 * aggregate capacity is per-shard throughput × shard count — measured at
 * ~2.3M ticks/s per shard on the 300-symbol probe.
 *
 * <p>Symbol routing rules:</p>
 * <ul>
 *   <li>{@link #registerSymbol(String, int...)} assigns a symbol to one or
 *       MORE shards — multi-shard registration is the cross co-location
 *       tool: a synthetic cross must live where both its legs tick, and
 *       duplicating a leg's feed into a second shard costs one extra ring
 *       publish (~40 ns), which is the whole point of shared-nothing;</li>
 *   <li>{@link #publish} fans a tick to every shard hosting the symbol —
 *       primitive arrays only, zero allocation, single producer thread
 *       (or one producer per disjoint symbol set);</li>
 *   <li>strategies/quoters attach per shard via {@link #bus(int)} exactly
 *       as they would to a standalone bus — the shard is invisible to
 *       them.</li>
 * </ul>
 *
 * <p>What sharding deliberately does NOT solve: firm-wide risk. Each
 * shard's {@link HftRiskGate} sees only its own symbols; put a
 * {@link GlobalRiskAggregator} over {@link #gates()} for cross-shard
 * exposure caps.</p>
 */
public final class ShardedTradingEngine implements AutoCloseable {

    private final HftMarketDataBus[] buses;
    private final HftRiskGate[] gates;
    private final HftOrderGateway[] gateways;

    // Routing tables, indexed by the GLOBAL symbol handle returned from
    // registerSymbol: which shards host it, and its dense id in each.
    private final List<int[]> shardsOf = new ArrayList<>();
    private final List<int[]> localIdOf = new ArrayList<>();
    private final List<String> names = new ArrayList<>();
    // Frozen into arrays at start() so the publish hot path is array-only.
    private int[][] routeShards;
    private int[][] routeIds;
    private boolean started;

    /**
     * @param shardCount        independent stacks (2 threads each: consumer + venue)
     * @param busRingCapacity   tick ring per shard (power of two)
     * @param orderRingCapacity order ring per shard (power of two)
     * @param maxSymbolsPerShard dense-id capacity per shard
     * @param busySpin          spin-wait consumers (latency) vs park (cores)
     * @param gateFactory       builds each shard's risk gate (limits per shard)
     */
    public ShardedTradingEngine(int shardCount, int busRingCapacity, int orderRingCapacity,
                                int maxSymbolsPerShard, boolean busySpin,
                                IntFunction<HftRiskGate> gateFactory) {
        if (shardCount <= 0) {
            throw new IllegalArgumentException("shardCount must be > 0");
        }
        this.buses = new HftMarketDataBus[shardCount];
        this.gates = new HftRiskGate[shardCount];
        this.gateways = new HftOrderGateway[shardCount];
        for (int s = 0; s < shardCount; s++) {
            buses[s] = new HftMarketDataBus(busRingCapacity, maxSymbolsPerShard, busySpin);
            gates[s] = gateFactory.apply(s);
            gateways[s] = new HftOrderGateway(orderRingCapacity, gates[s], busySpin);
        }
    }

    /**
     * Registers a symbol on the given shard(s); returns the global handle
     * used with {@link #publish}. Setup path — call before {@link #start}.
     */
    public int registerSymbol(String symbol, int... shards) {
        if (started) {
            throw new IllegalStateException("register symbols before start()");
        }
        if (shards.length == 0) {
            throw new IllegalArgumentException("symbol needs at least one shard");
        }
        int[] ids = new int[shards.length];
        for (int i = 0; i < shards.length; i++) {
            ids[i] = buses[shards[i]].registerSymbol(symbol);
        }
        int handle = names.size();
        names.add(symbol);
        shardsOf.add(shards.clone());
        localIdOf.add(ids);
        return handle;
    }

    /** The symbol's dense id within one of its shards (for subscriptions). */
    public int localId(int handle, int shard) {
        int[] shards = shardsOf.get(handle);
        for (int i = 0; i < shards.length; i++) {
            if (shards[i] == shard) {
                return localIdOf.get(handle)[i];
            }
        }
        throw new IllegalArgumentException(
                names.get(handle) + " is not hosted on shard " + shard);
    }

    /** Starts every shard (subscribe strategies via {@link #bus} first). */
    public synchronized void start() {
        if (started) {
            return;
        }
        // Freeze routing into plain 2D arrays: the publish path after this
        // point is array indexing only — no List, no bounds surprises.
        routeShards = shardsOf.toArray(new int[0][]);
        routeIds = localIdOf.toArray(new int[0][]);
        for (int s = 0; s < buses.length; s++) {
            gateways[s].start();
            buses[s].start();
        }
        started = true;
    }

    /**
     * The producer hot path: fans one tick to every shard hosting the
     * symbol. Zero allocation. Returns false when ANY hosting shard's ring
     * was full (that shard missed the tick; counted on its bus).
     */
    public boolean publish(int handle, double price, double size, long timestampNanos) {
        int[] shards = routeShards[handle];
        int[] ids = routeIds[handle];
        boolean all = true;
        for (int i = 0; i < shards.length; i++) {
            all &= buses[shards[i]].publish(ids[i], price, size, timestampNanos);
        }
        return all;
    }

    public int shardCount() {
        return buses.length;
    }

    /** Shard components, for wiring listeners/quoters and observability. */
    public HftMarketDataBus bus(int shard) {
        return buses[shard];
    }

    public HftRiskGate gate(int shard) {
        return gates[shard];
    }

    public HftOrderGateway gateway(int shard) {
        return gateways[shard];
    }

    /** All gates — the input to a {@link GlobalRiskAggregator}. */
    public List<HftRiskGate> gates() {
        return List.of(gates);
    }

    /** Ticks processed across all shards. */
    public long processedCount() {
        long total = 0;
        for (HftMarketDataBus bus : buses) {
            total += bus.processedCount();
        }
        return total;
    }

    /** Orders delivered to venue listeners across all shards. */
    public long deliveredCount() {
        long total = 0;
        for (HftOrderGateway gateway : gateways) {
            total += gateway.deliveredCount();
        }
        return total;
    }

    @Override
    public void close() {
        for (int s = 0; s < buses.length; s++) {
            buses[s].close();
            gateways[s].close();
        }
    }
}
