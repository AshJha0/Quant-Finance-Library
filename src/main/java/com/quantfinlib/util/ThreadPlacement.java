package com.quantfinlib.util;

import java.util.concurrent.ThreadFactory;
import java.util.function.Consumer;

/**
 * Thread creation hook for the library's polling threads.
 *
 * <p>Every component that owns a thread (market-data bus consumer, order
 * gateway venue thread, capture writer, binary market-data reader, global
 * risk monitor) creates it through a {@link ThreadFactory} that the caller
 * can replace before {@code start()}. The default is {@link #daemon(String)}.
 * The library has no native dependencies, so it does not pin threads itself;
 * a caller that wants pinning supplies a factory whose threads pin themselves
 * as their first action, for example with an affinity library:
 * <pre>{@code
 * ThreadFactory pinned = ThreadPlacement.daemon("md-consumer",
 *         t -> Affinity.setAffinity(3));   // runs on the new thread, before the loop
 * bus.threadFactory(pinned);
 * }</pre>
 *
 * <p>Without a library, pin at the OS level instead: start the JVM under
 * {@code taskset -c 2-5 java ...}, reserve the cores from the scheduler with
 * the kernel parameters {@code isolcpus=2-5 nohz_full=2-5 rcu_nocbs=2-5},
 * and move interrupts off them ({@code /proc/irq/*}{@code /smp_affinity}).
 * A busy-spinning thread ({@link IdleStrategy#busySpin()}) only pays off on
 * a core that nothing else is scheduled on.
 */
public final class ThreadPlacement {

    private ThreadPlacement() {
    }

    /**
     * Daemon threads with a fixed name and no placement.
     *
     * @param name thread name
     * @return a factory
     */
    public static ThreadFactory daemon(String name) {
        return daemon(name, null);
    }

    /**
     * Daemon threads with a fixed name; {@code onStart} runs on the new thread
     * before its task, which is where a CPU-affinity call belongs.
     *
     * @param name    thread name
     * @param onStart placement hook run on the new thread, or null
     * @return a factory
     */
    public static ThreadFactory daemon(String name, Consumer<Thread> onStart) {
        return task -> {
            Runnable body = onStart == null ? task : () -> {
                onStart.accept(Thread.currentThread());
                task.run();
            };
            Thread t = new Thread(body, name);
            t.setDaemon(true);
            return t;
        };
    }
}
