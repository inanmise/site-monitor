package com.certmonitor.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.lang.management.*;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Slf4j
@Service
public class MetricsService {

    private static final int    MAX_SAMPLES = 1440; // 1440 × 60 s = 24 h
    private static final DateTimeFormatter ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);

    private final MemoryMXBean   memMx    = ManagementFactory.getMemoryMXBean();
    private final ThreadMXBean   threadMx = ManagementFactory.getThreadMXBean();
    private final com.sun.management.OperatingSystemMXBean osMx;

    private final Deque<Map<String, Object>> history = new ArrayDeque<>(MAX_SAMPLES + 1);
    private long lastGcMs = 0;

    public MetricsService() {
        com.sun.management.OperatingSystemMXBean os = null;
        try {
            os = (com.sun.management.OperatingSystemMXBean)
                    ManagementFactory.getOperatingSystemMXBean();
        } catch (ClassCastException e) {
            log.warn("com.sun.management.OperatingSystemMXBean not available — CPU metrics disabled");
        }
        this.osMx = os;
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 5_000)
    public synchronized void sample() {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("ts", ISO.format(Instant.now()));

        // Process & system CPU %
        if (osMx != null) {
            double cpuProc = osMx.getProcessCpuLoad() * 100;
            double cpuSys  = osMx.getCpuLoad()        * 100;
            p.put("cpu_process", cpuProc >= 0 ? round1(cpuProc) : null);
            p.put("cpu_system",  cpuSys  >= 0 ? round1(cpuSys)  : null);
        } else {
            p.put("cpu_process", null);
            p.put("cpu_system",  null);
        }

        // JVM heap
        MemoryUsage heap = memMx.getHeapMemoryUsage();
        long heapUsedMb  = heap.getUsed() / (1024 * 1024);
        long heapMaxMb   = heap.getMax()  / (1024 * 1024);
        p.put("heap_used_mb", heapUsedMb);
        p.put("heap_max_mb",  heapMaxMb);
        p.put("heap_pct", heapMaxMb > 0 ? (int)(heap.getUsed() * 100L / heap.getMax()) : 0);

        // Non-heap (Metaspace + Code Cache)
        MemoryUsage nonHeap = memMx.getNonHeapMemoryUsage();
        p.put("non_heap_mb", nonHeap.getUsed() / (1024 * 1024));

        // Live thread count
        p.put("threads", threadMx.getThreadCount());

        // GC pause delta (ms) since last sample
        long gcNow = ManagementFactory.getGarbageCollectorMXBeans()
                .stream().mapToLong(GarbageCollectorMXBean::getCollectionTime).sum();
        p.put("gc_delta_ms", Math.max(0, gcNow - lastGcMs));
        lastGcMs = gcNow;

        history.addLast(p);
        while (history.size() > MAX_SAMPLES) history.pollFirst();
    }

    public synchronized List<Map<String, Object>> getHistory() {
        return new ArrayList<>(history);
    }

    private static double round1(double v) {
        return Math.round(v * 10) / 10.0;
    }
}
