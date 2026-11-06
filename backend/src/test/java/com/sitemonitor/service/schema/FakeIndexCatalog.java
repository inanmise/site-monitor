package com.sitemonitor.service.schema;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;

/** İndeks bakım işlerinin birim testleri için bellek içi katalog (havuz dışı oturumun sahtesi). */
class FakeIndexCatalog implements IndexCatalog {

    /** Başka bir oturumun tuttuğu kilitler (tryLock false döner). */
    final Set<Long> heldElsewhere = new HashSet<>();
    final Set<Long> held = new HashSet<>();
    final Map<String, Integer> markers = new LinkedHashMap<>();
    final Map<String, List<IndexFacts>> tables = new LinkedHashMap<>();
    final Set<String> buildingTables = new HashSet<>();
    final List<IndexStats> stats = new ArrayList<>();
    final List<Leftover> leftovers = new ArrayList<>();
    /** Yeniden kurulan indeksin yeni boyutu (nitelikli ad → bayt). */
    final Map<String, Long> bytesAfter = new HashMap<>();
    /** Koşulan DDL'ler — "ddl | lock=… | stmt=…". */
    final List<String> executed = new ArrayList<>();
    final List<List<String>> leftoverQueries = new ArrayList<>();
    /** DDL → atılacak SQLSTATE (null = başarılı). */
    Function<String, String> failState = ddl -> null;
    /** DDL koşmadan hemen önce çağrılır (ör. kapanışı tetiklemek). */
    Consumer<String> beforeExecute = ddl -> { };
    final AtomicInteger cancels = new AtomicInteger();
    boolean closed;
    boolean markerReadFails;

    static IndexFacts idx(String table, String name, List<String> cols) {
        return idx(table, name, cols, cols.size());
    }

    static IndexFacts idx(String table, String name, List<String> cols, int keyCount) {
        List<String> ops = new ArrayList<>();
        List<String> coll = new ArrayList<>();
        List<String> opt = new ArrayList<>();
        for (int i = 0; i < keyCount; i++) {
            String c = cols.get(i);
            ops.add(c.equals("monitor_id") || c.equals("port") || c.equals("check_id") ? "3124" : "3126");
            coll.add(c.equals("monitor_id") || c.equals("port") || c.equals("check_id") ? "0" : "100");
            opt.add("0");
        }
        return new IndexFacts(name, "public." + name, table, "btree", false, false, false, false, true, true, true, false,
                false, keyCount, cols, ops, coll, opt, 1_000_000L);
    }

    FakeIndexCatalog put(IndexFacts f) {
        tables.computeIfAbsent(f.table(), k -> new ArrayList<>()).add(f);
        return this;
    }

    @Override public boolean tryLock(long key) {
        if (heldElsewhere.contains(key)) return false;
        held.add(key);
        return true;
    }

    @Override public void unlock(long key) { held.remove(key); }

    @Override public boolean markerExists(String key) throws SQLException {
        if (markerReadFails) throw new SQLException("nişan okunamadı", "08006");
        return markers.containsKey(key);
    }

    @Override public void writeMarker(String key, int rows) { markers.putIfAbsent(key, rows); }

    @Override public List<IndexFacts> indexes(String table) {
        return List.copyOf(tables.getOrDefault(table, List.of()));
    }

    @Override public boolean building(String table) { return buildingTables.contains(table); }

    @Override public List<IndexStats> bloatInputs(List<String> t) {
        return stats.stream().filter(s -> t.contains(s.table())).toList();
    }

    @Override public List<Leftover> leftovers(List<String> t) {
        leftoverQueries.add(List.copyOf(t));
        return leftovers.stream().filter(l -> t.contains(l.table())).toList();
    }

    @Override public long indexBytes(String q) { return bytesAfter.getOrDefault(q, -1L); }

    @Override public void executeBounded(String ddl, long lockMs, long stmtMs) throws SQLException {
        beforeExecute.accept(ddl);
        executed.add(ddl + " | lock=" + lockMs + " | stmt=" + stmtMs);
        String state = failState.apply(ddl);
        if (state != null) throw new SQLException("simulated " + state, state);
        if (ddl.startsWith("DROP INDEX CONCURRENTLY IF EXISTS ")) {
            String q = ddl.substring("DROP INDEX CONCURRENTLY IF EXISTS ".length());
            tables.values().forEach(l -> l.removeIf(f -> f.qualifiedName().equals(q)));
            leftovers.removeIf(l -> l.qualifiedName().equals(q));
        }
    }

    @Override public void cancel() { cancels.incrementAndGet(); }

    @Override public void close() { closed = true; }

    /** Yalnız DDL metinleri (sınır parametreleri olmadan). */
    List<String> ddls() {
        return executed.stream().map(s -> s.substring(0, s.indexOf(" | "))).toList();
    }
}
