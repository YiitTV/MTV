package com.yiittv.mtv;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Measures CPU time per thread group over a short window, for /mtv cpu. */
final class CpuSampler {

    private CpuSampler() {
    }

    static boolean supported() {
        ThreadMXBean bean = ManagementFactory.getThreadMXBean();
        return bean.isThreadCpuTimeSupported();
    }

    /** Blocks for {@code millis}; returns group -> CPU percent of one core, sorted. */
    static Map<String, Double> sample(long millis) throws InterruptedException {
        ThreadMXBean bean = ManagementFactory.getThreadMXBean();
        if (!bean.isThreadCpuTimeEnabled()) {
            bean.setThreadCpuTimeEnabled(true);
        }
        Map<Long, Long> before = cpuTimes(bean);
        long started = System.nanoTime();
        Thread.sleep(millis);
        long elapsed = Math.max(1, System.nanoTime() - started);
        Map<Long, Long> after = cpuTimes(bean);
        Map<String, Double> groups = new HashMap<>();
        for (ThreadInfo info : bean.getThreadInfo(after.keySet().stream()
                .mapToLong(Long::longValue).toArray())) {
            if (info == null) {
                continue;
            }
            long delta = after.getOrDefault(info.getThreadId(), 0L)
                    - before.getOrDefault(info.getThreadId(), 0L);
            if (delta <= 0) {
                continue;
            }
            groups.merge(group(info.getThreadName()), delta * 100.0 / elapsed, Double::sum);
        }
        Map<String, Double> sorted = new LinkedHashMap<>();
        groups.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .forEach(entry -> sorted.put(entry.getKey(), entry.getValue()));
        return sorted;
    }

    static String group(String name) {
        String value = name == null ? "" : name;
        String lower = value.toLowerCase(Locale.ROOT);
        if (value.startsWith("MTV-live-audio")) return "MTV live audio decoder";
        if (value.startsWith("MTV-MapEngine")) return "MTV render (map conversion)";
        if (value.startsWith("MTV-image")) return "MTV image loader";
        if (value.startsWith("MTV-Web")) return "MTV Web Studio";
        if (value.startsWith("MTV-YouTube") || value.startsWith("MTV-MediaLibrary")) {
            return "MTV downloads/media scan";
        }
        if (value.startsWith("MTV-")) return "MTV video decoder (FFmpeg)";
        if (lower.contains("mapengine")) return "MapEngine";
        if (value.equals("Server thread")) return "Minecraft main thread";
        if (lower.contains("netty") || lower.contains("epoll")) return "Network (Netty)";
        if (lower.contains("plasmo") || lower.contains("defaultdispatcher")) {
            return "Plasmo Voice / coroutines";
        }
        if (lower.contains("worldgen") || lower.contains("chunk") || lower.contains("region")) {
            return "Chunks / world";
        }
        if (lower.contains("gc ") || lower.startsWith("g1 ") || lower.contains("gc thread")
                || lower.contains("zgc")) {
            return "Garbage collector";
        }
        if (lower.contains("spark")) return "spark profiler";
        return "Other";
    }

    private static Map<Long, Long> cpuTimes(ThreadMXBean bean) {
        Map<Long, Long> result = new HashMap<>();
        for (long id : bean.getAllThreadIds()) {
            long time = bean.getThreadCpuTime(id);
            if (time >= 0) {
                result.put(id, time);
            }
        }
        return result;
    }

    static List<String> format(Map<String, Double> groups, int processors) {
        double total = groups.values().stream().mapToDouble(Double::doubleValue).sum();
        java.util.ArrayList<String> lines = new java.util.ArrayList<>();
        lines.add(String.format(Locale.ROOT,
                "Total: %.0f%% of one core (%d cores available, %.0f%% of the machine)",
                total, processors, total / Math.max(1, processors)));
        groups.forEach((name, value) -> {
            if (value >= 0.5) {
                lines.add(String.format(Locale.ROOT, "%5.1f%%  %s", value, name));
            }
        });
        return lines;
    }
}
