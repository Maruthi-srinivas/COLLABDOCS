package com.collabdocs.common;

import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.DoubleAdder;

public final class AppMetrics {
    public static final AppMetrics INSTANCE = new AppMetrics();
    private static final double[] BUCKETS = {0.05, 0.1, 0.2, 0.5, 1, 2, 5};
    private final Map<String, AtomicLong> counters = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> gauges = new ConcurrentHashMap<>();
    private final Map<String, Histogram> histograms = new ConcurrentHashMap<>();

    private AppMetrics() {}

    public void increment(String name, String... labels) {
        counters.computeIfAbsent(name + label(labels), key -> new AtomicLong()).incrementAndGet();
    }

    public void gauge(String name, long value, String... labels) {
        gauges.computeIfAbsent(name + label(labels), key -> new AtomicLong()).set(value);
    }

    public void observe(String name, double value, String... labels) {
        histograms.computeIfAbsent(name + label(labels), key -> new Histogram()).observe(value);
    }

    public String render() {
        StringBuilder out = new StringBuilder();
        counters.forEach((key, value) -> out.append(key).append(' ').append(value.get()).append('\n'));
        gauges.forEach((key, value) -> out.append(key).append(' ').append(value.get()).append('\n'));
        histograms.forEach((key, histogram) -> histogram.render(key, out));
        return out.toString();
    }

    private static String label(String... labels) {
        if (labels.length == 0) {
            return "";
        }
        StringBuilder out = new StringBuilder("{");
        for (int i = 0; i < labels.length; i += 2) {
            if (i > 0) out.append(',');
            out.append(labels[i]).append("=\"").append(labels[i + 1].replace("\\", "").replace("\"", "")).append('"');
        }
        return out.append('}').toString();
    }

    private static final class Histogram {
        private final long[] counts = new long[BUCKETS.length];
        private final DoubleAdder sum = new DoubleAdder();
        private final AtomicLong total = new AtomicLong();

        void observe(double value) {
            synchronized (counts) {
                for (int i = 0; i < BUCKETS.length; i++) {
                    if (value <= BUCKETS[i]) counts[i]++;
                }
            }
            sum.add(value);
            total.incrementAndGet();
        }

        void render(String key, StringBuilder out) {
            int brace = key.indexOf('{');
            String name = brace < 0 ? key : key.substring(0, brace);
            String labels = brace < 0 ? "" : key.substring(brace + 1, key.length() - 1);
            long[] snapshot;
            synchronized (counts) {
                snapshot = counts.clone();
            }
            for (int i = 0; i < BUCKETS.length; i++) {
                out.append(name).append("_bucket{");
                if (!labels.isEmpty()) out.append(labels).append(',');
                out.append("le=\"").append(BUCKETS[i]).append("\"} ").append(snapshot[i]).append('\n');
            }
            out.append(name).append("_bucket{");
            if (!labels.isEmpty()) out.append(labels).append(',');
            out.append("le=\"+Inf\"} ").append(total.get()).append('\n');
            out.append(name).append("_sum");
            if (brace >= 0) out.append(key.substring(brace));
            out.append(' ').append(sum.sum()).append('\n');
            out.append(name).append("_count");
            if (brace >= 0) out.append(key.substring(brace));
            out.append(' ').append(total.get()).append('\n');
        }
    }

    public static java.util.List<String> names(String rendered) {
        ArrayList<String> names = new ArrayList<>();
        for (String line : rendered.split("\n")) {
            if (!line.isBlank()) names.add(line);
        }
        return names;
    }
}
