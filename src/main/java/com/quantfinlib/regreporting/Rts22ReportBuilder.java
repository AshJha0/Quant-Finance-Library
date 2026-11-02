package com.quantfinlib.regreporting;

import com.quantfinlib.fix.ExecutionReport;
import com.quantfinlib.microstructure.Execution;
import com.quantfinlib.orderbook.Side;
import com.quantfinlib.regreporting.ReportingIdentifiers.Granularity;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/**
 * Builds {@link Rts22TransactionReport}s from the library's fill objects plus the
 * firm's reference data.
 *
 * <p>Plain idea: a fill says what traded, how much, at what price and when. The
 * report also needs who the firm is, in what capacity it traded, who was on the
 * other side and on which venue. The builder holds the firm-level data once and
 * stamps it on each fill.
 *
 * <p>Fields the builder populates (see {@link Rts22Field.Source#LIBRARY}):
 * 1 report status (NEWT, or CANC via {@link #cancellation}); 2 transaction
 * reference number (the fill's execId, optionally prefixed); 3 venue transaction
 * id (when supplied); 4 executing entity LEI; 5 investment-firm flag; 6
 * submitting entity LEI (defaults to field 4); 7 and 16 buyer and seller, from the
 * fill side and the trading capacity: when dealing on own account (DEAL) the firm
 * is its own side, otherwise the client id given to {@link #client}; the other
 * side is the counterparty; 25 transmission flag (false unless set); 28 trading
 * date time in UTC at the chosen granularity; 29 trading capacity; 30 quantity;
 * 33 price; 34 price currency; 36 venue MIC (XOFF when unset); 41 instrument ISIN
 * from {@link #isin} or the fill symbol; 65 SFT flag (false).
 *
 * <p>Everything else, including all natural-person fields, decision-maker and
 * execution-within-firm codes, short-selling flag, waivers, net amount and
 * instrument reference data for non-ToTV instruments, is left blank for the
 * firm to set with {@link #set}. Blank means "not populated by the library", not
 * "not applicable"; the firm must decide which apply.
 *
 * <p>Worked example ({@code Rts22ReportBuilderTest}, own test): a BUY fill of 100
 * at 101.25 on XLON in ISIN GB0002634946, dealing on own account, gives buyer =
 * executing LEI, seller = counterparty LEI, quantity "100", price "101.25".
 */
public final class Rts22ReportBuilder {

    /** RTS 22 field 29 values. */
    public enum TradingCapacity { DEAL, MTCH, AOTC }

    private final String executingEntityLei;
    private String submittingEntityLei;
    private boolean investmentFirm = true;
    private TradingCapacity capacity = TradingCapacity.DEAL;
    private String client = "";
    private String counterparty = "";
    private String venue = "XOFF";
    private String venueTransactionId = "";
    private String isin = "";
    private String priceCurrency = "";
    private String referencePrefix = "";
    private Granularity granularity = Granularity.MICROS;
    private boolean cancellation;
    private boolean transmission;
    private final Map<Rts22Field, String> overrides = new EnumMap<>(Rts22Field.class);

    /**
     * @param executingEntityLei the firm's LEI (field 4); must pass the ISO 17442 check
     */
    public Rts22ReportBuilder(String executingEntityLei) {
        if (!ReportingIdentifiers.isValidLei(executingEntityLei)) {
            throw new IllegalArgumentException("executing entity LEI fails ISO 17442 check: " + executingEntityLei);
        }
        this.executingEntityLei = executingEntityLei;
        this.submittingEntityLei = executingEntityLei;
    }

    /** Field 6 when someone else (an ARM, a parent) submits. */
    public Rts22ReportBuilder submittingEntity(String lei) {
        this.submittingEntityLei = Objects.requireNonNull(lei);
        return this;
    }

    /** Field 5. */
    public Rts22ReportBuilder investmentFirm(boolean v) {
        this.investmentFirm = v;
        return this;
    }

    /** Field 29. */
    public Rts22ReportBuilder capacity(TradingCapacity c) {
        this.capacity = Objects.requireNonNull(c);
        return this;
    }

    /** Client identifier (LEI or national id) used as the firm's side for MTCH/AOTC. */
    public Rts22ReportBuilder client(String id) {
        this.client = Objects.requireNonNull(id);
        return this;
    }

    /** The other side of the trade (LEI, or a venue/CCP LEI for anonymous venue trades). */
    public Rts22ReportBuilder counterparty(String id) {
        this.counterparty = Objects.requireNonNull(id);
        return this;
    }

    /** Field 36: segment MIC, or XOFF / XXXX for off-venue trades. */
    public Rts22ReportBuilder venue(String mic) {
        this.venue = Objects.requireNonNull(mic);
        return this;
    }

    /** Field 3. */
    public Rts22ReportBuilder venueTransactionId(String id) {
        this.venueTransactionId = Objects.requireNonNull(id);
        return this;
    }

    /** Field 41; when unset the fill symbol is used. */
    public Rts22ReportBuilder isin(String isin) {
        this.isin = Objects.requireNonNull(isin);
        return this;
    }

    /** Field 34. */
    public Rts22ReportBuilder priceCurrency(String ccy) {
        this.priceCurrency = Objects.requireNonNull(ccy);
        return this;
    }

    /** Prefix for field 2 so references are unique across sessions or venues. */
    public Rts22ReportBuilder referencePrefix(String prefix) {
        this.referencePrefix = Objects.requireNonNull(prefix);
        return this;
    }

    /** Field 28 precision; the firm chooses per RTS 25 for its activity. Default microseconds. */
    public Rts22ReportBuilder granularity(Granularity g) {
        this.granularity = Objects.requireNonNull(g);
        return this;
    }

    /** Field 1 = CANC instead of NEWT. */
    public Rts22ReportBuilder cancellation(boolean v) {
        this.cancellation = v;
        return this;
    }

    /** Field 25. */
    public Rts22ReportBuilder transmission(boolean v) {
        this.transmission = v;
        return this;
    }

    /** Sets any field explicitly; wins over what the builder derives. Empty clears. */
    public Rts22ReportBuilder set(Rts22Field field, String value) {
        overrides.put(Objects.requireNonNull(field), Objects.requireNonNull(value));
        return this;
    }

    /** Builds a report from a FIX execution report (must be a fill, execType F). */
    public Rts22TransactionReport fromExecution(ExecutionReport er, Instant tradeTime) {
        if (!er.isFill()) {
            throw new IllegalArgumentException("execution report " + er.execId() + " is not a fill");
        }
        return build(er.execId(), er.symbol(), er.side(), er.lastQty(), er.lastPrice(), tradeTime);
    }

    /**
     * Builds a report from a microstructure execution. Its {@code timestampNanos}
     * is read as nanoseconds since the Unix epoch, UTC; the builder's venue is
     * used if set to something other than XOFF, else the execution's venue.
     */
    public Rts22TransactionReport fromExecution(Execution ex, String reference) {
        Instant t = Instant.ofEpochSecond(Math.floorDiv(ex.timestampNanos(), 1_000_000_000L),
                Math.floorMod(ex.timestampNanos(), 1_000_000_000L));
        String savedVenue = venue;
        if ("XOFF".equals(venue) && ex.venue() != null && !ex.venue().isBlank()) {
            venue = ex.venue();
        }
        try {
            return build(reference, ex.symbol(), ex.side(), (double) ex.quantity(), ex.price(), t);
        } finally {
            venue = savedVenue;
        }
    }

    private Rts22TransactionReport build(String ref, String symbol, Side side, double qty, double px,
                                         Instant t) {
        if (!(qty > 0) || !Double.isFinite(px)) {
            throw new IllegalArgumentException("fill needs positive quantity and finite price");
        }
        String ownSide = capacity == TradingCapacity.DEAL ? executingEntityLei : client;
        Map<Rts22Field, String> v = new EnumMap<>(Rts22Field.class);
        v.put(Rts22Field.REPORT_STATUS, cancellation ? "CANC" : "NEWT");
        v.put(Rts22Field.TRANSACTION_REFERENCE_NUMBER, referencePrefix + ref);
        v.put(Rts22Field.TRADING_VENUE_TRANSACTION_ID, venueTransactionId);
        v.put(Rts22Field.EXECUTING_ENTITY_ID, executingEntityLei);
        v.put(Rts22Field.INVESTMENT_FIRM_INDICATOR, Boolean.toString(investmentFirm));
        v.put(Rts22Field.SUBMITTING_ENTITY_ID, submittingEntityLei);
        v.put(Rts22Field.BUYER_ID, side == Side.BUY ? ownSide : counterparty);
        v.put(Rts22Field.SELLER_ID, side == Side.BUY ? counterparty : ownSide);
        v.put(Rts22Field.TRANSMISSION_OF_ORDER_INDICATOR, Boolean.toString(transmission));
        v.put(Rts22Field.TRADING_DATE_TIME, ReportingIdentifiers.formatTimestamp(t, granularity));
        v.put(Rts22Field.TRADING_CAPACITY, capacity.name());
        v.put(Rts22Field.QUANTITY, decimal(qty));
        v.put(Rts22Field.PRICE, decimal(px));
        v.put(Rts22Field.PRICE_CURRENCY, priceCurrency);
        v.put(Rts22Field.VENUE, venue);
        v.put(Rts22Field.INSTRUMENT_ID, isin.isEmpty() ? symbol : isin);
        v.put(Rts22Field.SECURITIES_FINANCING_TRANSACTION_INDICATOR, "false");
        v.putAll(overrides);
        return new Rts22TransactionReport(v);
    }

    private static String decimal(double d) {
        return BigDecimal.valueOf(d).stripTrailingZeros().toPlainString();
    }
}
