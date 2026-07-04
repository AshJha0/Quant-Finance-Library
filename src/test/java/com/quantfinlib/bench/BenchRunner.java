package com.quantfinlib.bench;

import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;

/**
 * Entry point for running JMH under {@code mvn exec:java}: rebuilds
 * {@code java.class.path} from the plugin's isolated classloader so JMH's
 * forked measurement JVMs can find the benchmarks (the classworlds launcher
 * otherwise masks the real classpath).
 *
 * <pre>
 * mvn test-compile exec:java -Dexec.mainClass=com.quantfinlib.bench.BenchRunner \
 *     -Dexec.classpathScope=test -Dexec.args=CoreBenchmarks
 * </pre>
 */
public final class BenchRunner {

    private BenchRunner() {
    }

    public static void main(String[] args) throws Exception {
        if (BenchRunner.class.getClassLoader() instanceof URLClassLoader loader) {
            StringBuilder classpath = new StringBuilder();
            for (URL url : loader.getURLs()) {
                if (!classpath.isEmpty()) {
                    classpath.append(File.pathSeparatorChar);
                }
                classpath.append(new File(url.toURI()).getAbsolutePath());
            }
            System.setProperty("java.class.path", classpath.toString());
        }
        org.openjdk.jmh.Main.main(args);
    }
}
