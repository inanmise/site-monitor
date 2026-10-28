package com.sitemonitor.service.evalstate;

import com.sitemonitor.it.MultiPodTestSupport;
import com.sitemonitor.model.AlertEvent;
import com.sitemonitor.repository.AlertEventRepository;
import com.sitemonitor.repository.DnsMonitorRepository;
import com.sitemonitor.repository.DnsRecordRepository;
import com.sitemonitor.repository.NetworkOutageEventRepository;
import com.sitemonitor.service.AppSettingsService;
import com.sitemonitor.service.EscalationService;
import com.sitemonitor.service.MonitoringOutageService;
import com.sitemonitor.service.health.ClusterHealthStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Çok pod Sürüm 3-1 A2 — kalıcı değerlendirme durumu DOĞRULAYICISI (tasarım R3-1 §2.6). Gerçek {@link MonitoringOutageService}
 * + sözleşmeye sadık eşzamanlı ayna ({@link MirrorRecorder}) + H2 tabloları: gerçekçi senaryodan sonra 0 uyuşmazlık
 * (kancalar eksiksiz), her tür için kurcalanan satır yakalanır, yarışan / kirli anahtar atlanır, budanmış kardeş tutarlı,
 * okunamayan veritabanı / boşaltma gecikmesi / kuşak yok = "bilinmiyor" (asla 0), eşitleme turu, pencere tohumu farkı
 * ölçülür, rapor küme gözlemi olarak SNAKE_CASE yayımlanır.
 */
class EvalStateVerifierTest {

    static final String PORT = EscalationService.TYPE_PORT_DOWN;
    static final String KW = EscalationService.TYPE_KEYWORD;
    static final String DB = "db.example.com";
    static final String OTHER = "other.example.com";

    MultiPodTestSupport mp;
    JdbcTemplate h2;
    MonitoringOutageService mos;
    MirrorRecorder rec;
    EvalStateVerifier verifier;
    ClusterHealthStore store;
    PendingExecutor confirm;
    final Map<Long, Boolean> monitored = new HashMap<>();

    /** schedule() görevlerini bekleten yürütücü (teyit zinciri doğrulama sırasında "sürüyor" kalsın). */
    static final class PendingExecutor extends ScheduledThreadPoolExecutor {
        final List<Runnable> queued = new ArrayList<>();

        PendingExecutor() {
            super(1);
        }

        @Override
        public synchronized ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
            queued.add(command);
            return null;
        }
    }

    @BeforeEach
    void setUp() {
        mp = MultiPodTestSupport.create();
        h2 = mp.jdbc();
        h2.execute(ClusterHealthStore.OBS_DDL);
        AlertEventRepository alertRepo = mock(AlertEventRepository.class);
        AppSettingsService settings = mock(AppSettingsService.class);
        when(settings.getBoolean(anyString(), anyBoolean())).thenAnswer(i -> i.getArgument(1));
        when(settings.getInt(anyString(), anyInt())).thenAnswer(i -> i.getArgument(1));
        mos = new MonitoringOutageService(alertRepo, mock(EscalationService.class), mock(JdbcTemplate.class),
                mock(DnsRecordRepository.class), mock(DnsMonitorRepository.class), settings,
                mock(NetworkOutageEventRepository.class));
        for (String f : List.of("uptimeAlertEnabled", "portAlertEnabled", "dnsAlertEnabled", "keywordAlertEnabled",
                "pingAlertEnabled")) {
            ReflectionTestUtils.setField(mos, f, true);
        }
        ReflectionTestUtils.setField(mos, "confirmAttempts", 3);
        ReflectionTestUtils.setField(mos, "confirmDelayMs", 1L);
        ReflectionTestUtils.setField(mos, "bulkRateThreshold", 0.50);
        ReflectionTestUtils.setField(mos, "bulkMinErrors", 3);
        confirm = new PendingExecutor();
        ReflectionTestUtils.setField(mos, "confirmExecutor", confirm);
        ReflectionTestUtils.setField(mos, "nowMs", mp.clockSupplier());
        ReflectionTestUtils.setField(mos, "suppressionClock", mp.clockSupplier());
        mos.setStillMonitored(it -> {
            Object id = it.ctxExtra() == null ? null : it.ctxExtra().get("monitor_id");
            return !(id instanceof Number n) || monitored.getOrDefault(n.longValue(), true);
        });
        AlertEvent open = new AlertEvent();
        open.setId(42L);
        open.setDomain(DB);
        open.setAlertType(PORT);
        open.setAlertLevel("WARNING");
        open.setAcknowledged(false);
        open.setResolved(false);
        when(alertRepo.findOpenByDomainIn(anyCollection())).thenReturn(List.of(open));

        rec = new MirrorRecorder(h2, "pod-a-12345678", 1L, mp.clockSupplier());
        mos.setEvalStateSink(rec);
        verifier = new EvalStateVerifier(mos, h2);
        store = new ClusterHealthStore(h2);
        verifier.setClusterHealthStore(store);
    }

    @AfterEach
    void tearDown() {
        mp.close();
    }

    static Map<String, Object> status(String s, String error) {
        Map<String, Object> m = new HashMap<>();
        m.put("status", s);
        m.put("error", error);
        return m;
    }

    static MonitoringOutageService.SweepItem item(String type, String domain, String detail, boolean up, Long monitorId,
                                                  Map<String, Object> extra, Supplier<Map<String, Object>> recheck) {
        Map<String, Object> ctx = new LinkedHashMap<>(extra == null ? Map.of() : extra);
        if (monitorId != null) ctx.put("monitor_id", monitorId);
        return new MonitoringOutageService.SweepItem(type, domain, detail, up, up ? null : "connect timed out", ctx, recheck);
    }

    /** Gerçekçi lider turu: kurtarma sayacı, DOWN kardeş + süren teyit zinciri, bastırma açık tür, iki türün turu. */
    void realisticRounds() {
        mos.handleSweepResults(PORT, List.of(
                item(PORT, DB, "5432/TCP", true, 7L, Map.of("monitor_recovery_checks", 3), null),
                item(PORT, OTHER, "5433/TCP", false, 9L, Map.of("team_id", 4L, "standalone", true),
                        () -> status("down", "connect timed out"))));
        mos.handleSweepResults(KW, List.of(
                item(KW, "k1.example.com", "kw", false, 21L, null, null),
                item(KW, "k2.example.com", "kw", false, 22L, null, null),
                item(KW, "k3.example.com", "kw", false, 23L, null, null)));
    }

    /** İlk tur eşitler (bilinmiyor: resync), ikinci tur karşılaştırır. */
    EvalVerifyReport settledRound() {
        EvalVerifyReport first = verifier.verify(rec);
        assertThat(first.status()).isEqualTo(EvalVerifyReport.STATUS_UNKNOWN);
        assertThat(first.reason()).isEqualTo(EvalStateVerifier.REASON_RESYNC);
        mp.advance(1_000);   // gözlem yalnız daha YENİ an ile ezilir (ClusterHealthStore) — üretimde turlar dakikalarca arayla
        return verifier.verify(rec);
    }

    static EvalVerifyReport.KindResult kind(EvalVerifyReport r, String k) {
        return r.kinds().get(k);
    }

    // ── Tutarlı ayna ────────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Gerçekçi senaryo + sadık ayna → tüm geçti/kaldı türlerinde 0 uyuşmazlık, her tür gerçekten karşılaştırıldı")
    void realisticScenario_zeroMismatches() {
        realisticRounds();
        EvalVerifyReport r = settledRound();

        assertThat(r.status()).isEqualTo(EvalVerifyReport.STATUS_OK);
        assertThat(r.reason()).isNull();
        assertThat(r.generation()).isEqualTo(1L);
        assertThat(r.pod()).isEqualTo("pod-a-12345678");
        assertThat(r.kinds().keySet()).containsExactlyElementsOf(EvalVerifyReport.KINDS);
        for (String k : EvalVerifyReport.PASS_FAIL_KINDS) {
            assertThat(kind(r, k).mismatches()).as(k).isZero();
            assertThat(kind(r, k).compared()).as(k + " karşılaştırıldı").isPositive();
        }
        assertThat(kind(r, EvalVerifyReport.KIND_SUPPRESSION).compared()).isEqualTo(2);
        assertThat(kind(r, EvalVerifyReport.KIND_DESCRIPTOR)).isEqualTo(new EvalVerifyReport.KindResult(1, 0, 0, null));
        assertThat(kind(r, EvalVerifyReport.KIND_WINDOW_SEED).compared()).isEqualTo(2);
        assertThat(kind(r, EvalVerifyReport.KIND_WINDOW_SEED).mismatches()).isZero();
        assertThat(kind(r, EvalVerifyReport.KIND_WINDOW_SEED).maxDelta()).isEqualTo(0.0);
        assertThat(r.firstMismatches()).isEmpty();
        assertThat(verifier.lastReport()).isSameAs(r);
    }

    @Test
    @DisplayName("Zincir ilerler / biter, kurtarma kapanır, liderlik değişir → sonraki turlar yine 0 uyuşmazlık")
    void lifecycleKeepsMirrorConsistent() {
        realisticRounds();
        settledRound();
        mp.advance(30_000);
        List<Runnable> steps = new ArrayList<>(confirm.queued);
        confirm.queued.clear();
        steps.forEach(Runnable::run);   // zincir 1. deneme: ilerleme
        assertThat(verifier.verify(rec).status()).isEqualTo(EvalVerifyReport.STATUS_OK);
        for (int i = 0; i < 3; i++) {
            List<Runnable> more = new ArrayList<>(confirm.queued);
            confirm.queued.clear();
            more.forEach(Runnable::run);
        }
        mos.handleSweepResults(PORT, List.of(item(PORT, DB, "5432/TCP", true, 7L, Map.of("monitor_recovery_checks", 3), null),
                item(PORT, OTHER, "5433/TCP", true, 9L, Map.of("team_id", 4L, "standalone", true), null)));
        EvalVerifyReport r = verifier.verify(rec);
        assertThat(r.status()).isEqualTo(EvalVerifyReport.STATUS_OK);
        assertThat(kind(r, EvalVerifyReport.KIND_CONFIRMATION).compared()).as("zincir bitti, satır silindi").isZero();

        // Liderlik değişimi: bellek sıfırlanır, kaydedici yeni kuşak alır → doğrulayıcı yeni kuşağı eşitler, sonra 0.
        mos.onSweepLeadershipChanged(new com.sitemonitor.service.schedule.SweepLeadershipChangedEvent(false, "p", 0L,
                com.sitemonitor.service.schedule.SweepLeadershipChangedEvent.LOST));
        rec.newGeneration(2L);
        realisticRounds();
        EvalVerifyReport resync = verifier.verify(rec);
        assertThat(resync.reason()).isEqualTo(EvalStateVerifier.REASON_RESYNC);
        EvalVerifyReport after = verifier.verify(rec);
        assertThat(after.status()).isEqualTo(EvalVerifyReport.STATUS_OK);
        assertThat(after.generation()).isEqualTo(2L);
    }

    // ── Her uyuşmazlık türü yakalanır ───────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("recovery: tablodaki sayaç bellekten farklı → uyuşmazlık, ilk anahtar özeti raporda (açık anahtar YOK)")
    void recoveryMismatch() {
        realisticRounds();
        settledRound();
        h2.update("UPDATE monitor_eval_state SET recovery_up_count = 5 WHERE alert_type = ? AND domain = ?", PORT, DB);
        EvalVerifyReport r = verifier.verify(rec);
        assertThat(r.status()).isEqualTo(EvalVerifyReport.STATUS_MISMATCH);
        assertThat(kind(r, EvalVerifyReport.KIND_RECOVERY).mismatches()).isEqualTo(1);
        assertThat(r.firstMismatches()).containsExactly("recovery:" + EvalStateKeys.shortHash(PORT + ":" + DB));
        assertThat(String.join(",", r.firstMismatches())).doesNotContain(DB);
        assertThat(r.passFailMismatches()).isEqualTo(1);

        // Bellekte olmayan sayaç satırı da uyuşmazlık (satır sıfıra çekilmemiş).
        h2.update("UPDATE monitor_eval_state SET recovery_up_count = 1 WHERE alert_type = ? AND domain = ?", PORT, DB);
        h2.update("INSERT INTO monitor_eval_state (alert_type, domain, recovery_up_count, writer, gen, seq, updated_ms, updated_at) "
                + "VALUES (?, 'ghost.example.com', 2, 'pod-a-12345678', 1, 1, 0, 'x')", PORT);
        assertThat(kind(verifier.verify(rec), EvalVerifyReport.KIND_RECOVERY).mismatches()).isEqualTo(1);

        // Bellekteki sayacın satırı hiç yazılmamış (kayıp yazım) → uyuşmazlık.
        h2.update("DELETE FROM monitor_eval_state");
        mp.advance(1_000);
        EvalVerifyReport lost = verifier.verify(rec);
        assertThat(kind(lost, EvalVerifyReport.KIND_RECOVERY)).isEqualTo(new EvalVerifyReport.KindResult(1, 1, 0, null));
        assertThat(lost.firstMismatches()).containsExactly("recovery:" + EvalStateKeys.shortHash(PORT + ":" + DB));
    }

    @Test
    @DisplayName("sibling: bellekte DOWN ama satır yok / UP → uyuşmazlık; bellekte yok ama izlenen DOWN satır → uyuşmazlık")
    void siblingMismatch() {
        realisticRounds();
        settledRound();
        h2.update("UPDATE monitor_last_status SET up = TRUE WHERE alert_type = ? AND item_key = 'm:9'", PORT);
        EvalVerifyReport r = verifier.verify(rec);
        assertThat(kind(r, EvalVerifyReport.KIND_SIBLING).mismatches()).isEqualTo(1);

        h2.update("UPDATE monitor_last_status SET up = FALSE WHERE alert_type = ? AND item_key = 'm:9'", PORT);
        insertDownRow(PORT, 77L, "gone.example.com", mp.now());
        monitored.put(77L, true);
        assertThat(kind(verifier.verify(rec), EvalVerifyReport.KIND_SIBLING).mismatches())
                .as("izlenen ama bellekte olmayan DOWN satır").isEqualTo(1);
    }

    @Test
    @DisplayName("sibling: bellekte budanmış (artık izlenmiyor) ya da TTL dışı DOWN satır TUTARLI; gözlem anı payı aşılırsa uyuşmazlık")
    void prunedSiblingConsistent() {
        realisticRounds();
        settledRound();
        insertDownRow(PORT, 77L, "gone.example.com", mp.now());
        monitored.put(77L, false);
        insertDownRow(PORT, 78L, "old.example.com", mp.now() - MonitoringOutageService.siblingTtlMs() - 1);
        EvalVerifyReport r = verifier.verify(rec);
        assertThat(kind(r, EvalVerifyReport.KIND_SIBLING).mismatches()).isZero();
        assertThat(r.status()).isEqualTo(EvalVerifyReport.STATUS_OK);

        h2.update("UPDATE monitor_last_status SET observed_ms = observed_ms - 200000 WHERE item_key = 'm:9'");
        assertThat(kind(verifier.verify(rec), EvalVerifyReport.KIND_SIBLING).mismatches())
                .as("gözlem anı tazeleme payından (120 sn + 5 sn) fazla geride").isEqualTo(1);
    }

    @Test
    @DisplayName("suppression ve round_seen: bayrak / tur damgası farklı → uyuşmazlık")
    void suppressionAndRoundSeenMismatch() {
        realisticRounds();
        settledRound();
        h2.update("UPDATE monitor_eval_type_state SET suppression_active = FALSE WHERE alert_type = ?", KW);
        h2.update("UPDATE monitor_eval_type_state SET last_scheduled_round_ms = NULL WHERE alert_type = ?", PORT);
        EvalVerifyReport r = verifier.verify(rec);
        assertThat(kind(r, EvalVerifyReport.KIND_SUPPRESSION).mismatches()).isEqualTo(1);
        assertThat(kind(r, EvalVerifyReport.KIND_ROUND_SEEN).mismatches()).isEqualTo(1);
        assertThat(r.firstMismatches()).contains("suppression:" + EvalStateKeys.shortHash(KW),
                "round_seen:" + EvalStateKeys.shortHash(PORT));

        // Kuşak başlangıcından ÖNCEKİ damga "bu kuşakta görüldü" sayılmaz.
        h2.update("UPDATE monitor_eval_type_state SET last_scheduled_round_ms = ? WHERE alert_type = ?",
                rec.generationStartMs() - 1, PORT);
        assertThat(kind(verifier.verify(rec), EvalVerifyReport.KIND_ROUND_SEEN).mismatches()).isEqualTo(1);
    }

    @Test
    @DisplayName("confirmation: zincir kimliği farklı / satır yok / deneme ±1'den fazla → uyuşmazlık; ABANDONED satır sayılmaz")
    void confirmationMismatch() {
        realisticRounds();
        settledRound();
        String key = PORT + ":" + OTHER + ":5433/TCP";
        h2.update("UPDATE monitor_confirmations SET chain_id = 999 WHERE key_hash = ?", EvalStateKeys.keyHash(key));
        assertThat(kind(verifier.verify(rec), EvalVerifyReport.KIND_CONFIRMATION).mismatches()).isEqualTo(1);

        long chainId = mos.verifySnapshot().chainOwner().get(key);
        h2.update("UPDATE monitor_confirmations SET chain_id = ? WHERE key_hash = ?", chainId, EvalStateKeys.keyHash(key));
        assertThat(kind(verifier.verify(rec), EvalVerifyReport.KIND_CONFIRMATION).mismatches()).isZero();
        h2.update("UPDATE monitor_confirmations SET attempt = 3 WHERE key_hash = ?", EvalStateKeys.keyHash(key));
        assertThat(kind(verifier.verify(rec), EvalVerifyReport.KIND_CONFIRMATION).mismatches()).as("deneme 0 ↔ 3").isEqualTo(1);

        h2.update("UPDATE monitor_confirmations SET attempt = 1, state = 'ABANDONED' WHERE key_hash = ?",
                EvalStateKeys.keyHash(key));
        assertThat(kind(verifier.verify(rec), EvalVerifyReport.KIND_CONFIRMATION).mismatches())
                .as("bellekteki zincirin canlı satırı yok").isEqualTo(1);
    }

    @Test
    @DisplayName("confirmation: deneme farkı ±1 içinde → tolerans (adım arası yazım gecikmesi)")
    void confirmationAttemptTolerance() {
        realisticRounds();
        settledRound();
        String key = PORT + ":" + OTHER + ":5433/TCP";
        h2.update("UPDATE monitor_confirmations SET attempt = 1 WHERE key_hash = ?", EvalStateKeys.keyHash(key));
        EvalVerifyReport r = verifier.verify(rec);
        assertThat(kind(r, EvalVerifyReport.KIND_CONFIRMATION).mismatches()).as("0 ↔ 1 tolerans içinde").isZero();
    }

    @Test
    @DisplayName("descriptor (yalnız ölçülür): izleme türü çözülmemiş ya da hedef izlenmiyor → sayılır, durum OK kalır")
    void descriptorMeasuredOnly() {
        realisticRounds();
        settledRound();
        h2.update("UPDATE monitor_confirmations SET monitor_type = NULL");
        EvalVerifyReport r = verifier.verify(rec);
        assertThat(kind(r, EvalVerifyReport.KIND_DESCRIPTOR)).isEqualTo(new EvalVerifyReport.KindResult(1, 1, 0, null));
        assertThat(r.status()).as("ölçülen tür durumu değiştirmez").isEqualTo(EvalVerifyReport.STATUS_OK);

        h2.update("UPDATE monitor_confirmations SET monitor_type = 'port'");
        monitored.put(9L, false);
        assertThat(kind(verifier.verify(rec), EvalVerifyReport.KIND_DESCRIPTOR).mismatches()).isEqualTo(1);
    }

    @Test
    @DisplayName("window_seed (yalnız ölçülür): tablodan kurulan örnek bastırma kararını değiştiriyorsa sayılır + en büyük |Δoran|")
    void windowSeedDivergenceMeasured() {
        realisticRounds();
        settledRound();
        for (int i = 0; i < 5; i++) {
            h2.update("INSERT INTO monitor_last_status (alert_type, item_key, domain, up, network_down, source, observed_ms, "
                            + "changed_ms, writer, gen, seq, updated_ms, updated_at) VALUES (?,?,?,TRUE,FALSE,'M',?,?,'old-pod',0,1,0,'x')",
                    KW, "m:" + (900 + i), "seed" + i + ".example.com", mp.now(), mp.now());
        }
        EvalVerifyReport r = verifier.verify(rec);
        EvalVerifyReport.KindResult w = kind(r, EvalVerifyReport.KIND_WINDOW_SEED);
        assertThat(w.mismatches()).isEqualTo(1);
        assertThat(w.maxDelta()).isCloseTo(1.0 - 3.0 / 8.0, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(r.status()).isEqualTo(EvalVerifyReport.STATUS_OK);
        assertThat(r.firstMismatches()).contains("window_seed:" + EvalStateKeys.shortHash(KW));
    }

    @Test
    @DisplayName("3-2: window_seed ölçümü EvalWindowSeed'e taşındı — çıktı 3-1 döngüsüyle (kâhin) birebir: sayılar, karar farkı, en büyük |Δoran|")
    void windowSeedUnchanged() {
        realisticRounds();
        settledRound();
        // Karışık satırlar: başka kuşaklar, elle kaynak, ağ / ağ dışı DOWN, pencere dışı, aynı alan adında iki kalem
        long now = mp.now();
        String ins = "INSERT INTO monitor_last_status (alert_type, item_key, domain, up, network_down, source, observed_ms, "
                + "changed_ms, writer, gen, seq, updated_ms, updated_at) VALUES (?,?,?,?,?,'M',?,?,'old-pod',0,1,0,'x')";
        h2.update(ins, KW, "m:801", "k1.example.com", false, true, now, now);
        h2.update(ins, KW, "m:802", "k1.example.com", true, false, now, now);
        h2.update(ins, KW, "m:803", "kx.example.com", false, false, now, now);
        h2.update(ins, KW, "m:804", "ko.example.com", false, true, now - 10L * 60 * 60 * 1000, now);
        h2.update(ins, PORT, "m:805", "p1.example.com", false, true, now, now);
        EvalVerifyReport r = verifier.verify(rec);
        EvalVerifyReport.KindResult w = kind(r, EvalVerifyReport.KIND_WINDOW_SEED);

        // Kâhin: 3-1 measureWindowSeed (doğrulayıcının okuduğu satırlar üzerinde — bu kuşak ∪ pencere içi her kuşak)
        EvalSnapshot snap = mos.verifySnapshot();
        long maxWindow = 0;
        for (EvalSnapshot.Window x : snap.windows().values()) maxWindow = Math.max(maxWindow, x.windowMs());
        List<Map<String, Object>> rows = h2.queryForList("SELECT alert_type, domain, up, network_down, observed_ms FROM "
                + "monitor_last_status WHERE (gen = ? AND writer = ?) OR observed_ms >= ?", rec.generation(), rec.writer(),
                snap.atMs() - maxWindow);
        int compared = 0, mismatches = 0;
        double maxDelta = 0;
        for (Map.Entry<String, EvalSnapshot.Window> e : snap.windows().entrySet()) {
            String type = e.getKey();
            long cutoff = snap.atMs() - e.getValue().windowMs();
            Map<String, Boolean> byDomain = new HashMap<>();
            for (Map<String, Object> row : rows) {
                String d = (String) row.get("domain");
                if (!type.equals(row.get("alert_type")) || d == null || ((Number) row.get("observed_ms")).longValue() < cutoff) {
                    continue;
                }
                byDomain.merge(d, !(Boolean) row.get("up") && (Boolean) row.get("network_down"), Boolean::logicalOr);
            }
            int seedDown = (int) byDomain.values().stream().filter(Boolean::booleanValue).count();
            int seedTotal = byDomain.size();
            compared++;
            double seedRate = seedTotal == 0 ? 0 : (double) seedDown / seedTotal;
            maxDelta = Math.max(maxDelta, Math.abs(e.getValue().rate() - seedRate));
            if (mos.wouldSuppress(type, e.getValue().networkDown(), e.getValue().total())
                    != mos.wouldSuppress(type, seedDown, seedTotal)) {
                mismatches++;
            }
        }
        assertThat(w.compared()).isEqualTo(compared).isPositive();
        assertThat(w.mismatches()).isEqualTo(mismatches);
        assertThat(w.maxDelta()).isCloseTo(maxDelta, org.assertj.core.data.Offset.offset(1e-12));
    }

    // ── Atlananlar ve bilinmeyenler ─────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Görüntü sırasında kuyruğa alınan ya da kirli anahtar karşılaştırılmaz (atlandı), uyuşmazlık sayılmaz")
    void changedOrDirtyKeysSkipped() {
        realisticRounds();
        settledRound();
        h2.update("UPDATE monitor_eval_state SET recovery_up_count = 5 WHERE alert_type = ? AND domain = ?", PORT, DB);
        rec.markDirty(EvalStateKeys.Table.EVAL_STATE, PORT + ":" + DB);
        EvalVerifyReport r = verifier.verify(rec);
        assertThat(kind(r, EvalVerifyReport.KIND_RECOVERY).mismatches()).isZero();
        assertThat(kind(r, EvalVerifyReport.KIND_RECOVERY).skipped()).isEqualTo(1);

        // Yarışan anahtar: bağlam "s0'dan sonra kuyruğa alındı" der.
        EvalStateVerification.Context racing = new DelegatingContext(rec) {
            @Override
            public boolean changedSince(EvalStateKeys.Table table, String key, long seq) {
                return table == EvalStateKeys.Table.TYPE_STATE || super.changedSince(table, key, seq);
            }
        };
        h2.update("UPDATE monitor_eval_type_state SET suppression_active = FALSE WHERE alert_type = ?", KW);
        EvalVerifyReport r2 = verifier.verify(racing);
        assertThat(kind(r2, EvalVerifyReport.KIND_SUPPRESSION).mismatches()).isZero();
        assertThat(kind(r2, EvalVerifyReport.KIND_SUPPRESSION).skipped()).isEqualTo(2);
    }

    @Test
    @DisplayName("Okunamayan tablo → bilinmiyor (read_failed), türler boş — asla '0 uyuşmazlık'; boşaltma gecikmesi / kuşak yok da bilinmiyor")
    void unknownNeverZero() {
        realisticRounds();
        settledRound();
        rec.setFlushed(false);
        EvalVerifyReport lag = verifier.verify(rec);
        assertThat(lag.unknown()).isTrue();
        assertThat(lag.reason()).isEqualTo(EvalVerifyReport.REASON_FLUSH_LAG);
        assertThat(lag.kinds()).isEmpty();
        rec.setFlushed(true);

        h2.execute("DROP TABLE monitor_eval_state");
        mp.advance(1_000);
        EvalVerifyReport failed = verifier.verify(rec);
        assertThat(failed.status()).isEqualTo(EvalVerifyReport.STATUS_UNKNOWN);
        assertThat(failed.reason()).isEqualTo(EvalVerifyReport.REASON_READ_FAILED);
        assertThat(failed.kinds()).isEmpty();
        assertThat(failed.passFailMismatches()).isZero();
        assertThat(EvalStateVerifier.fromJson(store.read(EvalVerifyReport.OBSERVATION).orElseThrow().payload()).status())
                .as("yayımlanan da bilinmiyor").isEqualTo(EvalVerifyReport.STATUS_UNKNOWN);

        rec.newGeneration(0L);
        assertThat(verifier.verify(rec).reason()).isEqualTo(EvalVerifyReport.REASON_NO_GENERATION);
    }

    @Test
    @DisplayName("Bellek görüntüsü alınamazsa → bilinmiyor (snapshot_failed); bağlam fırlatsa da rapor üretilir")
    void snapshotFailureAndBrokenContext() {
        MonitoringOutageService broken = mock(MonitoringOutageService.class);
        when(broken.verifySnapshot()).thenThrow(new IllegalStateException("x"));
        EvalStateVerifier v = new EvalStateVerifier(broken, h2);
        assertThat(v.verify(rec).reason()).isEqualTo(EvalStateVerifier.REASON_RESYNC);
        assertThat(v.verify(rec).reason()).isEqualTo(EvalVerifyReport.REASON_SNAPSHOT_FAILED);

        EvalStateVerification.Context bad = new DelegatingContext(rec) {
            @Override
            public long seq() {
                throw new IllegalStateException("kaydedici bozuk");
            }
        };
        EvalVerifyReport r = v.verify(bad);
        assertThat(r.unknown()).isTrue();
        assertThat(r.reason()).isEqualTo(EvalVerifyReport.REASON_READ_FAILED);
    }

    // ── Eşitleme turu ───────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Kip kapalıyken geçen süre / uzun boşluk: eşitleme turu belleği yeniden kuyruğa alır, sonraki tur 0 uyuşmazlık")
    void gapResyncReplaysMemory() {
        realisticRounds();
        settledRound();
        verifier.setVerifyMs(300_000L);
        rec.setRecording(false);   // kip kapalı: mutasyonlar yazılmaz
        mp.advance(60_000);
        mos.handleSweepResults(PORT, List.of(item(PORT, DB, "5432/TCP", true, 7L, Map.of("monitor_recovery_checks", 3), null),
                item(PORT, DB, "5434/TCP", false, 13L, Map.of("monitor_recovery_checks", 3),
                        () -> status("down", "connect timed out"))));   // sahibin DOWN'u: sayaç silinir, kardeş DOWN eklenir
        assertThat(mos.verifySnapshot().recoveryUpCount()).isEmpty();
        assertThat(mos.verifySnapshot().downObserved()).containsKey(PORT + "|13");
        rec.setRecording(true);
        mp.advance(16 * 60_000L);   // > 3 × verify-ms
        EvalVerifyReport resync = verifier.verify(rec);
        assertThat(resync.reason()).isEqualTo(EvalStateVerifier.REASON_RESYNC);
        mp.advance(1_000);
        EvalVerifyReport r = verifier.verify(rec);
        assertThat(r.status()).as(String.valueOf(r)).isEqualTo(EvalVerifyReport.STATUS_OK);
        assertThat(kind(r, EvalVerifyReport.KIND_SIBLING).compared()).as("oynatılan DOWN gözlemleri karşılaştırıldı (port 9, 13 + 3 keyword)")
                .isEqualTo(5);
        assertThat(h2.queryForObject("SELECT up FROM monitor_last_status WHERE item_key = 'm:13'", Boolean.class))
                .as("kip kapalıyken görülen DOWN eşitlemeyle yazıldı").isFalse();
        assertThat(h2.queryForObject("SELECT recovery_up_count FROM monitor_eval_state WHERE domain = ?", Integer.class, DB))
                .as("bellekte olmayan bayat sayaç OYNATILMAZ — toplu düşüş kuralıyla atlanır").isEqualTo(1);
        assertThat(kind(r, EvalVerifyReport.KIND_RECOVERY).skipped()).isEqualTo(1);
    }

    @Test
    @DisplayName("Kısa kip kapalı aralık (boşluk eşiği altında): kapalıyken değişen anahtar toplu düşüşle ATLANIR, sahte uyuşmazlık yok")
    void shortOffPeriod_keysSkippedNotMismatched() {
        realisticRounds();
        settledRound();
        rec.setRecording(false);
        mp.advance(60_000);
        mos.handleSweepResults(PORT, List.of(item(PORT, DB, "5432/TCP", true, 7L, Map.of("monitor_recovery_checks", 3), null)));
        rec.setRecording(true);
        EvalVerifyReport r = verifier.verify(rec);   // boşluk eşiği altında → karşılaştırır (eşitleme yok)
        // FX-STATE: düşüşten hemen sonra HER anahtar atlanır → "0 uyuşmazlık / ok" değil, bilinmiyor (nothing_compared).
        assertThat(r.status()).isEqualTo(EvalVerifyReport.STATUS_UNKNOWN);
        assertThat(r.reason()).isEqualTo(EvalVerifyReport.REASON_NOTHING_COMPARED);
        assertThat(kind(r, EvalVerifyReport.KIND_RECOVERY).mismatches()).isZero();
        assertThat(kind(r, EvalVerifyReport.KIND_RECOVERY).skipped()).isEqualTo(1);

        // Anahtar yeniden kuyruğa girince yine karşılaştırılır.
        mp.advance(60_000);
        mos.handleSweepResults(PORT, List.of(item(PORT, DB, "5432/TCP", true, 7L, Map.of("monitor_recovery_checks", 5), null)));
        EvalVerifyReport again = verifier.verify(rec);
        assertThat(kind(again, EvalVerifyReport.KIND_RECOVERY)).isEqualTo(new EvalVerifyReport.KindResult(1, 0, 0, null));
        assertThat(again.status()).as("bir anahtar karşılaştırıldı").isEqualTo(EvalVerifyReport.STATUS_OK);
    }

    // ── Yayın ──────────────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Rapor küme gözlemi 'eval-state-verify' olarak yayımlanır: SNAKE_CASE JSON, yazan pod, gidiş-dönüş")
    void publishesObservation() {
        realisticRounds();
        EvalVerifyReport r = settledRound();
        ClusterHealthStore.Observation obs = store.read(EvalVerifyReport.OBSERVATION).orElseThrow();
        assertThat(obs.instanceId()).isEqualTo("pod-a-12345678");
        assertThat(obs.observedMs()).isEqualTo(r.atMs());
        assertThat(obs.payload()).contains("\"at_ms\"", "\"first_mismatches\"", "\"max_delta\"", "\"window_seed\"")
                .doesNotContain(DB).doesNotContain("passFail");
        assertThat(obs.payload().length()).isLessThan(4000);
        assertThat(EvalStateVerifier.fromJson(obs.payload())).isEqualTo(r);
        assertThat(EvalStateVerifier.fromJson("{bozuk")).isNull();
        assertThat(EvalStateVerifier.fromJson(null)).isNull();
    }

    @Test
    @DisplayName("Gerçek kaydedici (A1) ile uçtan uca: boşalt → doğrula (eşitleme) → boşalt → doğrula: tüm geçti/kaldı türlerinde 0")
    void realRecorder_zeroMismatches() {
        new EvalStateSchemaPatch(h2).apply();
        EvalStateVerifier v = new EvalStateVerifier(mos, h2);
        v.setClusterHealthStore(store);
        EvalStateRecorder real = EvalStateRecorder.builder(h2).writer("pod-r-00000001").leaderHint(() -> true)
                .mode(() -> EvalStateMode.VERIFY).clock(mp.clockSupplier()).verifier(() -> v).flushMs(50).build();
        try {
            mos.setEvalStateSink(real);
            realisticRounds();
            assertThat(real.flushNow()).isTrue();
            assertThat(real.generation()).isEqualTo(1L);
            assertThat(real.verifyNow().reason()).isEqualTo(EvalStateVerifier.REASON_RESYNC);
            mp.advance(1_000);
            assertThat(real.flushNow()).isTrue();
            EvalVerifyReport r = real.verifyNow();
            assertThat(r.status()).as(String.valueOf(r)).isEqualTo(EvalVerifyReport.STATUS_OK);
            for (String k : EvalVerifyReport.PASS_FAIL_KINDS) {
                assertThat(kind(r, k).mismatches()).as(k).isZero();
                assertThat(kind(r, k).compared()).as(k + " karşılaştırıldı").isPositive();
            }
            assertThat(kind(r, EvalVerifyReport.KIND_DESCRIPTOR)).as("RecheckRefs: port")
                    .isEqualTo(new EvalVerifyReport.KindResult(1, 0, 0, null));
            assertThat(kind(r, EvalVerifyReport.KIND_WINDOW_SEED).mismatches()).isZero();
            assertThat(real.status().lastVerify()).isEqualTo(r);
            assertThat(EvalStateVerifier.fromJson(store.read(EvalVerifyReport.OBSERVATION).orElseThrow().payload()))
                    .isEqualTo(r);

            // Gerçek kaydedicinin yazdığı satır kurcalanınca yakalanır.
            h2.update("UPDATE monitor_eval_state SET recovery_up_count = 9 WHERE domain = ?", DB);
            mp.advance(1_000);
            assertThat(kind(real.verifyNow(), EvalVerifyReport.KIND_RECOVERY).mismatches()).isEqualTo(1);
        } finally {
            real.stop();
        }
    }

    @Test
    @DisplayName("En kötü yük (7 türün hepsi azami sayılarla uyuşmaz, 5 özet, 200 karakter pod) < 4000 karakter, geçerli JSON, durum var")
    void worstCasePayloadFits() {
        Map<String, EvalVerifyReport.KindResult> kinds = new LinkedHashMap<>();
        for (String k : EvalVerifyReport.KINDS) {
            kinds.put(k, new EvalVerifyReport.KindResult(Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE,
                    0.12345678901234567));
        }
        List<String> first = new ArrayList<>();
        for (int i = 0; i < EvalVerifyReport.MAX_KEYS; i++) {
            first.add(EvalVerifyReport.KIND_CONFIRMATION + ":" + EvalStateKeys.shortHash("anahtar-" + i));
        }
        EvalVerifyReport worst = new EvalVerifyReport(Long.MAX_VALUE, "p".repeat(200), Long.MAX_VALUE,
                EvalVerifyReport.STATUS_MISMATCH, EvalVerifyReport.REASON_SNAPSHOT_FAILED, kinds, first);
        String json = EvalStateVerifier.payloadOf(worst);
        assertThat(json).isEqualTo(EvalStateVerifier.toJson(worst));
        assertThat(json.length()).isLessThan(4000);
        assertThat(json).contains("\"status\":\"mismatch\"");
        assertThat(EvalStateVerifier.fromJson(json)).isEqualTo(worst);

        // Beklenmeyen aşırı uzun alan: yük yine geçerli JSON, ≤ 4000, durum korunur (ClusterHealthStore kırpmaz).
        EvalVerifyReport huge = new EvalVerifyReport(1L, "p".repeat(5000), 1L, EvalVerifyReport.STATUS_MISMATCH, null,
                kinds, first);
        String fallback = EvalStateVerifier.payloadOf(huge);
        assertThat(fallback.length()).isLessThanOrEqualTo(EvalStateVerifier.PAYLOAD_MAX);
        EvalVerifyReport parsed = EvalStateVerifier.fromJson(fallback);
        assertThat(parsed).isNotNull();
        assertThat(parsed.status()).isEqualTo(EvalVerifyReport.STATUS_MISMATCH);
        assertThat(parsed.reason()).isEqualTo("payload_too_large");
    }

    // ── R3-1 FX-STATE ───────────────────────────────────────────────────────────────────────────────────────────────

    EvalStateRecorder realRecorder(EvalStateVerifier v, Supplier<EvalStateMode> mode) {
        new EvalStateSchemaPatch(h2).apply();
        return EvalStateRecorder.builder(h2).writer("pod-r-00000001").leaderHint(() -> true).mode(mode)
                .clock(mp.clockSupplier()).verifier(() -> v).flushMs(50).build();
    }

    /** Gerçek kaydediciyle eşitlenmiş başlangıç: gerçekçi turlar → boşalt → eşitleme turu → boşalt. */
    EvalStateVerifier settledReal(EvalStateRecorder real, EvalStateVerifier v) {
        mos.setEvalStateSink(real);
        realisticRounds();
        assertThat(real.flushNow()).isTrue();
        assertThat(real.verifyNow().reason()).isEqualTo(EvalStateVerifier.REASON_RESYNC);
        mp.advance(1_000);
        assertThat(real.flushNow()).isTrue();
        mp.advance(1_000);
        return v;
    }

    String publishedReason() {
        return EvalStateVerifier.fromJson(store.read(EvalVerifyReport.OBSERVATION).orElseThrow().payload()).reason();
    }

    @Test
    @DisplayName("FX F1: tur sürerken liderlik kaybedilir (LOST) → bilinmiyor 'leadership_changed', rapor YAYIMLANMAZ")
    void leadershipLostDuringVerifyRound() {
        EvalStateVerifier v = new EvalStateVerifier(mos, h2);
        v.setClusterHealthStore(store);
        EvalStateRecorder real = realRecorder(v, () -> EvalStateMode.VERIFY);
        try {
            settledReal(real, v);
            EvalStateVerification.Context base = real.verificationContext();
            EvalVerifyReport r = v.verify(new DelegatingContext(base) {
                @Override
                public boolean awaitFlushed(long seq, long timeoutMs) {
                    com.sitemonitor.service.schedule.SweepLeadershipChangedEvent lost =
                            new com.sitemonitor.service.schedule.SweepLeadershipChangedEvent(false, "pod-b", 0L,
                                    com.sitemonitor.service.schedule.SweepLeadershipChangedEvent.LOST);
                    mos.onSweepLeadershipChanged(lost);
                    real.onSweepLeadershipChanged(lost);
                    return super.awaitFlushed(seq, timeoutMs);
                }
            });
            assertThat(r.status()).as(String.valueOf(r)).isEqualTo(EvalVerifyReport.STATUS_UNKNOWN);
            assertThat(r.reason()).isEqualTo(EvalVerifyReport.REASON_LEADERSHIP_CHANGED);
            assertThat(r.kinds()).isEmpty();
            assertThat(publishedReason()).as("eski liderin turu küme gözlemini ezmedi").isEqualTo(EvalStateVerifier.REASON_RESYNC);
        } finally {
            real.stop();
        }
    }

    @Test
    @DisplayName("FX F1: tur sürerken başka pod daha yeni kuşak alır (çit) → bilinmiyor, asla boş 'ok'; yayımlanmaz")
    void fencedDuringVerifyRound() {
        EvalStateVerifier v = new EvalStateVerifier(mos, h2);
        v.setClusterHealthStore(store);
        EvalStateRecorder real = realRecorder(v, () -> EvalStateMode.VERIFY);
        try {
            settledReal(real, v);
            mos.handleSweepResults(PORT, List.of(item(PORT, DB, "5432/TCP", true, 7L, Map.of("monitor_recovery_checks", 3),
                    null)));
            EvalStateVerification.Context base = real.verificationContext();
            EvalVerifyReport r = v.verify(new DelegatingContext(base) {
                @Override
                public boolean awaitFlushed(long seq, long timeoutMs) {
                    assertThat(real.sql().acquireGeneration("pod-b-00000002", mp.now())).isEqualTo(2);
                    return super.awaitFlushed(seq, timeoutMs);
                }
            });
            assertThat(real.generation()).as("çitlendi").isZero();
            assertThat(r.status()).as(String.valueOf(r)).isEqualTo(EvalVerifyReport.STATUS_UNKNOWN);
            assertThat(r.reason()).isEqualTo(EvalVerifyReport.REASON_LEADERSHIP_CHANGED);
            assertThat(publishedReason()).isEqualTo(EvalStateVerifier.REASON_RESYNC);
        } finally {
            real.stop();
        }
    }

    @Test
    @DisplayName("FX F2: DOWN izleme duraklatılır (bellek budar) sonra yeniden açılır — satır da silindiği için uyuşmazlık yok")
    void pausedThenResumedDownMonitor() {
        EvalStateVerifier v = new EvalStateVerifier(mos, h2);
        v.setClusterHealthStore(store);
        EvalStateRecorder real = realRecorder(v, () -> EvalStateMode.VERIFY);
        try {
            mos.setEvalStateSink(real);
            realisticRounds();   // izleme 9 DOWN (bellek + satır)
            real.flushNow();
            real.verifyNow();    // eşitleme
            monitored.put(9L, false);   // kullanıcı duraklattı
            mp.advance(11 * 60_000L);   // > gözlem budama aralığı
            mos.handleSweepResults(KW, List.of(item(KW, "k1.example.com", "kw", true, 21L, null, null)));
            real.flushNow();
            mp.advance(1_000);
            EvalVerifyReport paused = real.verifyNow();
            assertThat(paused.status()).as(String.valueOf(paused)).isEqualTo(EvalVerifyReport.STATUS_OK);
            assertThat(h2.queryForObject("SELECT COUNT(*) FROM monitor_last_status WHERE item_key = 'm:9'", Integer.class))
                    .as("budanan kalemin satırı silindi").isZero();
            monitored.put(9L, true);    // yeniden açıldı, henüz gözlenmedi
            mp.advance(60_000);
            EvalVerifyReport resumed = real.verifyNow();
            assertThat(resumed.status()).as(String.valueOf(resumed)).isEqualTo(EvalVerifyReport.STATUS_OK);
            assertThat(kind(resumed, EvalVerifyReport.KIND_SIBLING).mismatches()).isZero();
        } finally {
            real.stop();
        }
    }

    @Test
    @DisplayName("FX F3: DOWN kalan izlemenin hedefi değişir (tazeleme süresi içinde) — satır hemen yazılır, uyuşmazlık yok")
    void downMonitorHostRenamedWithinRefresh() {
        EvalStateVerifier v = new EvalStateVerifier(mos, h2);
        v.setClusterHealthStore(store);
        EvalStateRecorder real = realRecorder(v, () -> EvalStateMode.VERIFY);
        try {
            settledReal(real, v);
            mp.advance(30_000);
            mos.handleSweepResults(PORT, List.of(item(PORT, "renamed.example.com", "5433/TCP", false, 9L,
                    Map.of("team_id", 4L, "standalone", true), () -> status("down", "connect timed out"))));
            real.flushNow();
            mp.advance(1_000);
            EvalVerifyReport r = real.verifyNow();
            assertThat(r.status()).as(String.valueOf(r)).isEqualTo(EvalVerifyReport.STATUS_OK);
            assertThat(kind(r, EvalVerifyReport.KIND_SIBLING).mismatches()).isZero();
            assertThat(h2.queryForObject("SELECT domain FROM monitor_last_status WHERE alert_type = ? AND item_key = 'm:9'",
                    String.class, PORT)).isEqualTo("renamed.example.com");
        } finally {
            real.stop();
        }
    }

    @Test
    @DisplayName("FX #2: kip off → on aynı liderlikte → yeni kuşak + eşitleme; sonra sıfırlanan sayaç sahte uyuşmazlık vermez")
    void modeToggleThenReset_verifyStaysClean() {
        java.util.concurrent.atomic.AtomicReference<EvalStateMode> mode =
                new java.util.concurrent.atomic.AtomicReference<>(EvalStateMode.VERIFY);
        EvalStateVerifier v = new EvalStateVerifier(mos, h2);
        v.setClusterHealthStore(store);
        EvalStateRecorder real = realRecorder(v, mode::get);
        try {
            settledReal(real, v);
            assertThat(real.verifyNow().status()).isEqualTo(EvalVerifyReport.STATUS_OK);
            long gen = real.generation();
            mode.set(EvalStateMode.OFF);
            mos.handleSweepResults(KW, List.of(item(KW, "k1.example.com", "kw", false, 21L, null, null)));
            real.flushNow();
            mode.set(EvalStateMode.VERIFY);
            mp.advance(60_000);
            // sahibin DOWN'u: kurtarma sayacı sıfırlanır (kaydedicinin temizlenmiş tabanına karşı no-op)
            mos.handleSweepResults(PORT, List.of(item(PORT, DB, "5432/TCP", false, 7L, Map.of("monitor_recovery_checks", 3),
                    () -> status("down", "connect timed out"))));
            assertThat(real.flushNow()).isTrue();
            assertThat(real.generation()).as("geri dönüşte yeni kuşak").isGreaterThan(gen);
            mp.advance(1_000);
            EvalVerifyReport first = real.verifyNow();
            assertThat(first.reason()).as(String.valueOf(first)).isEqualTo(EvalStateVerifier.REASON_RESYNC);
            mp.advance(1_000);
            real.flushNow();
            EvalVerifyReport after = real.verifyNow();
            assertThat(after.status()).as(String.valueOf(after)).isEqualTo(EvalVerifyReport.STATUS_OK);
            assertThat(kind(after, EvalVerifyReport.KIND_RECOVERY).mismatches()).isZero();
        } finally {
            real.stop();
        }
    }

    @Test
    @DisplayName("FX F5: 'hâlâ izleniyor mu' okunamazsa anahtar atlanır — bilinmeyen uyuşmazlığa dönmez (kardeş + betimleyici)")
    void stillMonitoredLookupFails_skippedNotMismatch() {
        realisticRounds();
        settledRound();
        insertDownRow(PORT, 77L, "gone.example.com", mp.now());
        mos.setStillMonitored(it -> {
            Object id = it.ctxExtra() == null ? null : it.ctxExtra().get("monitor_id");
            if (id instanceof Number n && (n.longValue() == 77L || n.longValue() == 9L)) {
                throw new IllegalStateException("havuz tükendi (test)");
            }
            return true;
        });
        EvalVerifyReport r = verifier.verify(rec);
        assertThat(kind(r, EvalVerifyReport.KIND_SIBLING).mismatches()).isZero();
        assertThat(kind(r, EvalVerifyReport.KIND_SIBLING).skipped()).isEqualTo(1);
        assertThat(kind(r, EvalVerifyReport.KIND_DESCRIPTOR)).isEqualTo(new EvalVerifyReport.KindResult(0, 0, 1, null));
        assertThat(r.status()).isEqualTo(EvalVerifyReport.STATUS_OK);
        MonitoringOutageService.SweepItem gone = new EvalSnapshot.Down(PORT, "x", null, 77L, null, null, null, 0L).slimItem();
        assertThat(mos.stillMonitored(gone)).as("karar yolu yine fail-open").isTrue();
        assertThat(mos.stillMonitoredOrNull(gone)).as("doğrulayıcı yolu: bilinmiyor").isNull();
    }

    @Test
    @DisplayName("Kesişim: hiçbir geçti/kaldı türünde karşılaştırma yok ama atlanan var → bilinmiyor 'nothing_compared' (asla yeşil)")
    void nothingCompared_isUnknown() {
        realisticRounds();
        settledRound();
        EvalStateVerification.Context allSkipped = new DelegatingContext(rec) {
            @Override
            public boolean changedSince(EvalStateKeys.Table table, String key, long seq) {
                return true;
            }
        };
        EvalVerifyReport r = verifier.verify(allSkipped);
        assertThat(r.status()).isEqualTo(EvalVerifyReport.STATUS_UNKNOWN);
        assertThat(r.reason()).isEqualTo(EvalVerifyReport.REASON_NOTHING_COMPARED);
        assertThat(kind(r, EvalVerifyReport.KIND_RECOVERY).skipped()).isPositive();
        assertThat(r.firstMismatches()).isEmpty();
        assertThat(EvalStateVerifier.nothingCompared(Map.of(EvalVerifyReport.KIND_RECOVERY,
                new EvalVerifyReport.KindResult(0, 0, 0, null)))).as("hiç anahtar yok = tutarlı, bilinmiyor değil").isFalse();
    }

    @Test
    @DisplayName("FX #6: doğrulayıcının okumaları sorgu zaman aşımlı kendi şablonunda")
    void verifierReadsUseQueryTimeout() {
        JdbcTemplate t = (JdbcTemplate) ReflectionTestUtils.getField(new EvalStateVerifier(mos, h2), "jdbc");
        assertThat(t.getQueryTimeout()).isEqualTo(EvalStateSql.QUERY_TIMEOUT_SECONDS);
        assertThat(t.getDataSource()).isSameAs(h2.getDataSource());
    }

    void insertDownRow(String type, long monitorId, String domain, long observedMs) {
        h2.update("INSERT INTO monitor_last_status (alert_type, item_key, domain, monitor_id, up, network_down, source, "
                        + "observed_ms, changed_ms, writer, gen, seq, updated_ms, updated_at) "
                        + "VALUES (?,?,?,?,FALSE,TRUE,'S',?,?,'pod-a-12345678',1,1,0,'x')",
                type, "m:" + monitorId, domain, monitorId, observedMs, observedMs);
    }

    /** Bağlamı sarmalar (testte tek yöntemi değiştirmek için). */
    static class DelegatingContext implements EvalStateVerification.Context {
        final EvalStateVerification.Context d;

        DelegatingContext(EvalStateVerification.Context d) {
            this.d = d;
        }

        @Override public String writer() { return d.writer(); }
        @Override public long generation() { return d.generation(); }
        @Override public long generationStartMs() { return d.generationStartMs(); }
        @Override public long nowMs() { return d.nowMs(); }
        @Override public long seq() { return d.seq(); }
        @Override public boolean awaitFlushed(long seq, long timeoutMs) { return d.awaitFlushed(seq, timeoutMs); }
        @Override public boolean changedSince(EvalStateKeys.Table t, String k, long s) { return d.changedSince(t, k, s); }
        @Override public long lastStatusRefreshMs() { return d.lastStatusRefreshMs(); }
        @Override public boolean stillCurrent() { return d.stillCurrent(); }
    }
}
