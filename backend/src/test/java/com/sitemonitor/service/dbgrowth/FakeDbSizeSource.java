package com.sitemonitor.service.dbgrowth;

import com.sitemonitor.service.retention.RetentionPolicy;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/** Testler için sahte veri kaynağı — PostgreSQL'e özgü okumalar burada sabit / ayarlanabilir. */
class FakeDbSizeSource implements DbSizeSource {

    Boolean supported = true;
    long dbBytes = 10L << 30;
    List<TableSize> tables = new ArrayList<>();
    Map<String, String> oldest = new HashMap<>();
    /** Tablo → en eski kayıt okumasında fırlatılacak hata (zaman aşımı benzetimi). */
    Map<String, RuntimeException> oldestFails = new HashMap<>();
    RuntimeException dbSizeFails;
    Map<String, List<String>> histograms = new HashMap<>();
    Long count;
    RuntimeException countFails;
    List<VacuumStat> vacuum = new ArrayList<>();
    RuntimeException vacuumFails;
    List<IndexInfo> unused = new ArrayList<>();
    List<BloatInfo> bloat = new ArrayList<>();
    Map<String, List<long[]>> hot = new HashMap<>();
    RuntimeException hotFails;
    final AtomicInteger dbSizeCalls = new AtomicInteger();
    final AtomicInteger oldestCalls = new AtomicInteger();

    @Override public Boolean supported() { return supported; }

    @Override public long databaseBytes() {
        dbSizeCalls.incrementAndGet();
        if (dbSizeFails != null) throw dbSizeFails;
        return dbBytes;
    }

    @Override public List<TableSize> tables() { return tables; }

    @Override public String oldest(RetentionPolicy p) {
        oldestCalls.incrementAndGet();
        if (oldestFails.containsKey(p.table())) throw oldestFails.get(p.table());
        return oldest.get(p.table());
    }

    @Override public Map<String, List<String>> histograms(List<String[]> tableColumns) {
        Map<String, List<String>> out = new HashMap<>();
        for (String[] tc : tableColumns) {
            List<String> b = histograms.get(tc[0] + "." + tc[1]);
            if (b != null) out.put(tc[0] + "." + tc[1], b);
        }
        return out;
    }

    @Override public Long countOlder(RetentionPolicy p, String cutoff) {
        if (countFails != null) throw countFails;
        return count;
    }

    @Override public List<VacuumStat> vacuumStats() {
        if (vacuumFails != null) throw vacuumFails;
        return vacuum;
    }

    @Override public List<IndexInfo> unusedIndexes(long minBytes) { return unused; }

    @Override public List<BloatInfo> bloat(List<String> tables) { return bloat; }

    @Override public List<long[]> hotMonitors(String table, String sinceIso, int limit) {
        if (hotFails != null) throw hotFails;
        return hot.getOrDefault(table, List.of());
    }
}
