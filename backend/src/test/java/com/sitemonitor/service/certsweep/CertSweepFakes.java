package com.sitemonitor.service.certsweep;

import com.sitemonitor.service.checks.CheckJobRunner;
import org.springframework.dao.DataAccessResourceFailureException;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.LongConsumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Çok pod Sürüm 3-4 paket B — koordinatör testlerinin sahteleri: bellek içi {@link CertSweepStore} (paket A'nın SQL
 * sözleşmesinin AYNI kuralları: sahiplenme = PENDING ya da kirası dolmuş CLAIMED ∧ deneme &lt; 2 ∧ kaçınılan işçi değil ∧
 * koşu RUNNING ve nabzı taze; tamamlama = belirteç CAS'ı ∧ koşu RUNNING), işçiyi taklit eden {@link Worker} (paket A'nın
 * davranışı: kapanışta sonucu atar, ölçücü fırlatırsa her öğeyi {@code failed} işaretiyle tamamlar) ve test kurucusu.
 */
public final class CertSweepFakes {

    private CertSweepFakes() {
    }

    /** Test / gidiş-dönüş kurucusu (paket dışından — {@code CertChunkRoundTripTest}). Uyku = {@code sleep} geri çağrısı. */
    public static CertRunCoordinator coordinator(CertChunking chunking, CertSweepStore store, CertChunkRunner runner,
                                                 java.util.function.BooleanSupplier anotherLivePod,
                                                 Function<List<String>, Map<String, String>> issuers, String instanceId,
                                                 LongSupplier monoMs, LongConsumer sleep) {
        return new CertRunCoordinator(chunking, store, runner, anotherLivePod, issuers, () -> instanceId, monoMs,
                ms -> sleep.accept(ms));
    }

    /** Ayarlar sahte: anahtar açık / kapalı, pod ayarları verilen. */
    public static CertChunking chunking(boolean enabled, CertChunking.Tuning tuning) {
        com.sitemonitor.service.AppSettingsService s = org.mockito.Mockito.mock(com.sitemonitor.service.AppSettingsService.class);
        org.mockito.Mockito.lenient().when(s.getBoolean(org.mockito.ArgumentMatchers.eq(CertChunking.KEY_ENABLED),
                org.mockito.ArgumentMatchers.anyBoolean())).thenReturn(enabled);
        return new CertChunking(s, tuning);
    }

    /** Ayarlar sahte: anahtar her okumada {@code enabled}'dan (tur sürerken kapatma — SW-L2). */
    public static CertChunking chunking(java.util.function.BooleanSupplier enabled, CertChunking.Tuning tuning) {
        com.sitemonitor.service.AppSettingsService s = org.mockito.Mockito.mock(com.sitemonitor.service.AppSettingsService.class);
        org.mockito.Mockito.lenient().when(s.getBoolean(org.mockito.ArgumentMatchers.eq(CertChunking.KEY_ENABLED),
                org.mockito.ArgumentMatchers.anyBoolean())).thenAnswer(i -> enabled.getAsBoolean());
        return new CertChunking(s, tuning);
    }

    /** Testlerin pod ayarları: parça 5, en az 1 alan adı (tur hep parçalı olabilsin). */
    public static CertChunking.Tuning tuning() {
        return new CertChunking.Tuning(true, 5, 1, 4, 0, 60_000L, 10_000L, 50, 250L, 5_000L);
    }

    // ═════════════════════════════════════════════════════════════════════════════════════════════════════════════
    // Bellek içi depo
    // ═════════════════════════════════════════════════════════════════════════════════════════════════════════════

    public static final class Store implements CertSweepStore {

        public final LongSupplier clock;
        public volatile boolean tablesReady = true;
        /** Doluyken her depo çağrısı (saat / hazır / finish hariç) bunu fırlatır. */
        public volatile RuntimeException failAll;
        /** {@code createRun} fırlatsın mı. */
        public volatile RuntimeException failCreate;
        /** {@code settleOrphans} fırlatsın mı. */
        public volatile RuntimeException failSettle;
        /** Bu parça kimliklerinden birini içeren {@code readResults} çağrısı fırlatır (CR-L2). */
        public final java.util.Set<Long> failReadFor = java.util.concurrent.ConcurrentHashMap.newKeySet();
        /** Bu parça kimliğinin {@code cancel}'ı fırlatır (CR-L2). */
        public final java.util.Set<Long> failCancelFor = java.util.concurrent.ConcurrentHashMap.newKeySet();
        /** Her {@code readResults} çağrısının kimlik sayısı. */
        public final List<Integer> readBatches = Collections.synchronizedList(new ArrayList<>());
        public final List<String> calls = Collections.synchronizedList(new ArrayList<>());
        public final Map<String, Run> runs = new LinkedHashMap<>();
        public final List<Chunk> chunks = new ArrayList<>();
        public final List<String> finishes = Collections.synchronizedList(new ArrayList<>());
        public CertRunSpec lastSpec;
        private long nextId = 1;

        public Store(LongSupplier clock) {
            this.clock = clock;
        }

        public static final class Run {
            public String runId, kind, coordinator, state, outcome, workersJson;
            public boolean evict;
            public int domainsTotal, chunksTotal, chunkSize;
            public long createdMs, heartbeatMs;
            public Long finishedMs;
            public Integer takeovers, reclaims, secondOpinions;

            CertSweepStatus.Run view() {
                return new CertSweepStatus.Run(runId, kind, coordinator, state, evict, domainsTotal, chunksTotal, chunkSize,
                        createdMs, heartbeatMs, finishedMs, outcome, takeovers, reclaims, secondOpinions, workersJson);
            }
        }

        public static final class Chunk {
            public long id;
            public String runId, state, avoid, claimedBy, token, worker, resultsJson;
            public int no, attempts;
            public List<CertChunkSpec.Entry> entries;
            public long createdMs;
            public Long claimedMs, leaseUntil, finishedMs;
            public Integer checked, networkErrors, errors, warnings;

            ChunkState view() {
                return new ChunkState(id, no, state, attempts, entries.size(), createdMs, claimedBy, claimedMs, leaseUntil,
                        worker, finishedMs, checked, networkErrors, avoid);
            }
        }

        private void touch(String call) {
            calls.add(call);
            RuntimeException f = failAll;
            if (f != null) throw f;
        }

        public static RuntimeException dbDown() {
            return new DataAccessResourceFailureException("veritabanı yok (test)");
        }

        @Override
        public long nowMs() {
            return clock.getAsLong();
        }

        @Override
        public boolean clockStale() {
            return false;
        }

        @Override
        public boolean tablesReady() {
            return tablesReady;
        }

        @Override
        public synchronized List<CertSweepStatus.Run> settleOrphans() {
            touch("settleOrphans");
            if (failSettle != null) throw failSettle;
            List<CertSweepStatus.Run> out = new ArrayList<>();
            for (Run r : runs.values()) {
                if (RUN_RUNNING.equals(r.state) || RUN_DECIDING.equals(r.state)) {
                    out.add(r.view());
                    r.state = RUN_ABANDONED;
                    r.outcome = OUTCOME_ORPHANED;
                    r.finishedMs = clock.getAsLong();
                }
            }
            chunks.removeIf(c -> {
                Run r = runs.get(c.runId);
                return r == null || !(RUN_RUNNING.equals(r.state) || RUN_DECIDING.equals(r.state));
            });
            return out;
        }

        @Override
        public synchronized int pruneRuns(long olderThanMs, int limit) {
            touch("pruneRuns");
            int k = 0;
            for (var it = runs.values().iterator(); it.hasNext() && k < limit; ) {
                Run r = it.next();
                if (r.createdMs < olderThanMs && !(RUN_RUNNING.equals(r.state) || RUN_DECIDING.equals(r.state))) {
                    it.remove();
                    k++;
                }
            }
            return k;
        }

        @Override
        public synchronized void createRun(CertRunSpec spec) {
            touch("createRun");
            if (failCreate != null) throw failCreate;
            lastSpec = spec;
            long now = clock.getAsLong();
            for (CertChunkSpec c : spec.chunks()) add(spec.runId(), c, now);
            Run r = new Run();
            r.runId = spec.runId();
            r.kind = spec.kind();
            r.coordinator = spec.coordinator();
            r.state = RUN_RUNNING;
            r.evict = spec.evictCaches();
            r.domainsTotal = spec.domainsTotal();
            r.chunksTotal = spec.chunksTotal();
            r.chunkSize = spec.chunkSize();
            r.createdMs = now;
            r.heartbeatMs = now;
            runs.put(r.runId, r);
        }

        private void add(String runId, CertChunkSpec c, long now) {
            Chunk k = new Chunk();
            k.id = nextId++;
            k.runId = runId;
            k.no = c.chunkNo();
            k.state = CHUNK_PENDING;
            k.entries = c.entries();
            k.avoid = c.avoidWorker();
            k.attempts = 0;
            k.createdMs = now;
            chunks.add(k);
        }

        @Override
        public synchronized int addChunks(String runId, List<CertChunkSpec> list) {
            touch("addChunks");
            long now = clock.getAsLong();
            for (CertChunkSpec c : list) add(runId, c, now);
            return list.size();
        }

        @Override
        public synchronized boolean heartbeat(String runId) {
            touch("heartbeat");
            Run r = runs.get(runId);
            if (r == null || !RUN_RUNNING.equals(r.state)) return false;
            r.heartbeatMs = clock.getAsLong();
            return true;
        }

        @Override
        public synchronized List<ChunkState> chunkStates(String runId) {
            touch("chunkStates");
            List<ChunkState> out = new ArrayList<>();
            chunks.stream().filter(c -> c.runId.equals(runId)).sorted(Comparator.comparingInt(c -> c.no))
                    .forEach(c -> out.add(c.view()));
            return out;
        }

        @Override
        public synchronized Map<Long, String> readResults(String runId, Collection<Long> chunkIds) {
            touch("readResults");
            readBatches.add(chunkIds.size());
            for (Long id : chunkIds) if (failReadFor.contains(id)) throw dbDown();
            Map<Long, String> out = new LinkedHashMap<>();
            for (Chunk c : chunks) {
                if (c.runId.equals(runId) && CHUNK_DONE.equals(c.state) && chunkIds.contains(c.id)) out.put(c.id, c.resultsJson);
            }
            return out;
        }

        @Override
        public synchronized boolean cancel(String runId, long chunkId) {
            touch("cancel");
            if (failCancelFor.contains(chunkId)) throw dbDown();
            Chunk c = byId(chunkId);
            if (c == null || !c.runId.equals(runId)) return false;
            if (!(CHUNK_PENDING.equals(c.state) || CHUNK_CLAIMED.equals(c.state))) return false;
            c.state = CHUNK_CANCELLED;
            return true;
        }

        @Override
        public synchronized boolean markDeciding(String runId, String workersJson, int takeovers, int reclaims,
                                                 int secondOpinions) {
            touch("markDeciding");
            Run r = runs.get(runId);
            if (r == null || !RUN_RUNNING.equals(r.state)) return false;
            r.state = RUN_DECIDING;
            r.workersJson = workersJson;
            r.takeovers = takeovers;
            r.reclaims = reclaims;
            r.secondOpinions = secondOpinions;
            return true;
        }

        @Override
        public synchronized boolean finish(String runId, String state, String outcome, boolean atShutdown) {
            finishes.add(runId + ":" + state + ":" + outcome + ":" + atShutdown);
            calls.add("finish");
            if (failAll != null) return false;
            Run r = runs.get(runId);
            boolean updated = false;
            if (r != null && (RUN_RUNNING.equals(r.state) || RUN_DECIDING.equals(r.state))) {
                r.state = state;
                r.outcome = outcome;
                r.finishedMs = clock.getAsLong();
                updated = true;
            }
            if (!atShutdown) chunks.removeIf(c -> c.runId.equals(runId));
            return updated;
        }

        @Override
        public synchronized Optional<ClaimedChunk> claim(String owner, long leaseMs, long createdBeforeMs) {
            touch("claim");
            long now = clock.getAsLong();
            for (Chunk c : chunks.stream().sorted(Comparator.comparingLong(k -> k.id)).toList()) {
                boolean free = CHUNK_PENDING.equals(c.state)
                        || (CHUNK_CLAIMED.equals(c.state) && c.leaseUntil != null && c.leaseUntil < now);
                if (!free || c.attempts >= CertChunking.MAX_ATTEMPTS || c.createdMs >= createdBeforeMs) continue;
                if (c.avoid != null && c.avoid.equals(owner)) continue;
                Run r = runs.get(c.runId);
                if (r == null || !RUN_RUNNING.equals(r.state) || r.heartbeatMs < now - CertChunking.COORDINATOR_ALIVE_MS) continue;
                c.state = CHUNK_CLAIMED;
                c.claimedBy = owner;
                c.token = UUID.randomUUID().toString().substring(0, 12);
                c.claimedMs = now;
                c.leaseUntil = now + leaseMs;
                c.attempts++;
                return Optional.of(new ClaimedChunk(c.id, c.runId, c.no, c.entries, c.attempts, c.token, now, c.leaseUntil));
            }
            return Optional.empty();
        }

        @Override
        public synchronized int renew(String owner, Collection<Long> chunkIds, long leaseMs) {
            touch("renew");
            int k = 0;
            for (Chunk c : chunks) {
                if (owner.equals(c.claimedBy) && CHUNK_CLAIMED.equals(c.state) && chunkIds.contains(c.id)) {
                    c.leaseUntil = clock.getAsLong() + leaseMs;
                    k++;
                }
            }
            return k;
        }

        @Override
        public synchronized boolean complete(ChunkCompletion comp) {
            touch("complete");
            Chunk c = byId(comp.id());
            if (c == null || !CHUNK_CLAIMED.equals(c.state) || !comp.claimToken().equals(c.token)) return false;
            Run r = runs.get(c.runId);
            if (r == null || !RUN_RUNNING.equals(r.state)) return false;
            c.state = CHUNK_DONE;
            c.resultsJson = comp.resultsJson();
            c.checked = comp.checked();
            c.networkErrors = comp.networkErrors();
            c.errors = comp.errors();
            c.warnings = comp.warnings();
            c.finishedMs = clock.getAsLong();
            c.worker = comp.worker();
            c.leaseUntil = null;
            return true;
        }

        @Override
        public synchronized int release(String owner, Collection<Long> chunkIds) {
            touch("release");
            int k = 0;
            for (Chunk c : chunks) {
                if (owner.equals(c.claimedBy) && CHUNK_CLAIMED.equals(c.state) && chunkIds.contains(c.id)) {
                    c.state = CHUNK_PENDING;
                    c.claimedBy = null;
                    c.token = null;
                    c.leaseUntil = null;
                    c.attempts--;
                    k++;
                }
            }
            return k;
        }

        @Override
        public CertSweepStatus status(int lastRuns) {
            throw new UnsupportedOperationException("koordinatör durum okumaz");
        }

        public synchronized Chunk byId(long id) {
            for (Chunk c : chunks) if (c.id == id) return c;
            return null;
        }

        public synchronized Run run(String runId) {
            return runs.get(runId);
        }

        public synchronized List<Chunk> chunksOf(String runId) {
            return chunks.stream().filter(c -> c.runId.equals(runId)).toList();
        }
    }

    // ═════════════════════════════════════════════════════════════════════════════════════════════════════════════
    // İşçi taklidi (paket A'nın davranışı)
    // ═════════════════════════════════════════════════════════════════════════════════════════════════════════════

    public static final class Worker {
        public final Store store;
        public final CertChunkRunner runner;
        public final String owner;
        public long leaseMs = 60_000L;
        public int completed, late, discarded, failedChunks;

        public Worker(Store store, CertChunkRunner runner, String owner) {
            this.store = store;
            this.runner = runner;
            this.owner = owner;
        }

        /** Bir parça sahiplenip ölçer; parça yoksa false. */
        public boolean tick() {
            Optional<ClaimedChunk> c = store.claim(owner, leaseMs, Long.MAX_VALUE);
            if (c.isEmpty()) return false;
            run(c.get());
            return true;
        }

        /** Alınabilecek her parçayı ölçer. */
        public int drain() {
            int k = 0;
            while (k < 10_000 && tick()) k++;
            return k;
        }

        public void run(ClaimedChunk c) {
            List<Map<String, Object>> res;
            try {
                res = runner.checkDomains(c.rows(), c.runId());
            } catch (RuntimeException e) {
                List<Map<String, Object>> failed = new ArrayList<>();
                for (int k = 0; k < c.domainCount(); k++) failed.add(CheckJobRunner.failed(e));
                failedChunks++;
                if (store.complete(ChunkCompletion.of(c, owner, failed, 0))) completed++;
                else late++;
                return;
            }
            if (runner.closing() || res == null || res.size() != c.domainCount()) {
                discarded++;
                return;
            }
            if (store.complete(ChunkCompletion.of(c, owner, res, runner.networkErrorCount(res)))) completed++;
            else late++;
        }
    }

    // ═════════════════════════════════════════════════════════════════════════════════════════════════════════════
    // Ölçücü sahtesi (pod'a özgü çıktı)
    // ═════════════════════════════════════════════════════════════════════════════════════════════════════════════

    /** Alan adı → sonuç (her çağrıda yeni harita + {@code run_id}); çağrılar kaydedilir. */
    public static final class Runner implements CertChunkRunner {
        public final String pod;
        public final Function<String, Map<String, Object>> outcome;
        public final List<String> checked = Collections.synchronizedList(new ArrayList<>());
        public volatile boolean closing;
        public volatile Supplier<RuntimeException> throwFor;
        public volatile String throwDomain;
        public volatile RuntimeException countThrows;

        public Runner(String pod, Function<String, Map<String, Object>> outcome) {
            this.pod = pod;
            this.outcome = outcome;
        }

        @Override
        public List<Map<String, Object>> checkDomains(List<Map<String, Object>> rows, String runId) {
            List<Map<String, Object>> out = new ArrayList<>();
            for (Map<String, Object> row : rows) {
                String d = (String) row.get("domain");
                checked.add(d);
                if (d.equals(throwDomain) && throwFor != null) throw throwFor.get();
                Map<String, Object> m = new LinkedHashMap<>(outcome.apply(d));
                m.put("run_id", runId);
                out.add(m);
            }
            return out;
        }

        @Override
        public boolean closing() {
            return closing;
        }

        @Override
        public long networkErrorCount(List<Map<String, Object>> results) {
            RuntimeException t = countThrows;
            if (t != null) throw t;
            return count(results);
        }

        @Override
        public int executorCoreSize() {
            return 20;
        }

        /** {@code SchedulerService.networkErrorCount} ile aynı kural. */
        public static long count(List<Map<String, Object>> results) {
            if (results == null) return 0;
            return results.stream()
                    .filter(r -> r != null && "error".equals(r.get("status")))
                    .filter(r -> !Boolean.TRUE.equals(r.get("unresolvable_host")))
                    .map(r -> r.get("error_class"))
                    .filter(c -> "DNS".equals(c) || "NETWORK".equals(c))
                    .count();
        }
    }

    /** Geçerli bir sonuç. */
    public static Map<String, Object> ok(String domain) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("domain", domain);
        m.put("status", "valid");
        m.put("days_remaining", 90);
        m.put("elapsed_ms", 412L);
        m.put("chain", List.of(Map.of("subject", "CN=" + domain)));
        m.put("hsts", null);
        return m;
    }

    /** Ağ sınıfı hata. */
    public static Map<String, Object> netErr(String domain) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("domain", domain);
        m.put("status", "error");
        m.put("error", "Connection timeout after 6s");
        m.put("error_class", "NETWORK");
        return m;
    }

    /** Çözülemeyen ad (CR-M-1): {@code status=error ∧ error_class=DNS ∧ unresolvable_host} — karar oranına girmez. */
    public static Map<String, Object> unresolvable(String domain) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("domain", domain);
        m.put("status", "error");
        m.put("error", "Domain resolution failed");
        m.put("error_class", "DNS");
        m.put("unresolvable_host", true);
        return m;
    }

    /** Klasik vade satırı. */
    public static Map<String, Object> row(String domain) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("domain", domain);
        r.put("port", 443);
        r.put("use_proxy", false);
        r.put("tls_mode", "");
        return r;
    }

    public static List<Map<String, Object>> rows(int n, String prefix) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (int i = 0; i < n; i++) out.add(row(String.format("%s%03d.example.com", prefix, i)));
        return out;
    }
}
