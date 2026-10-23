package com.sitemonitor.service.evalstate;

import com.sitemonitor.service.MonitoringOutageService;
import com.sitemonitor.service.MonitoringOutageService.ConfirmState;
import com.sitemonitor.service.MonitoringOutageService.SweepItem;
import com.sitemonitor.service.health.ClusterHealthStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * Kalıcı değerlendirme durumu DOĞRULAYICISI (2026-10-23, çok pod Sürüm 3-1 A2; tasarım R3-1 §2.6) — 3-2'nin "okumayı
 * tablolara çevir" kararı için KANIT. Kaydedici ({@code EvalStateRecorder}, A1) kendi yazıcı iş parçacığında, yalnız bu pod
 * tarama lideri + kuşak sahibi + kip {@link EvalStateMode#VERIFY} iken, {@code verify-ms}'de bir çağırır. Bellek
 * ({@link MonitoringOutageService#verifySnapshot()}) ile bu kuşağın satırlarını karşılaştırır, raporu küme gözlemi
 * {@link EvalVerifyReport#OBSERVATION} olarak yayımlar (her pod'un Sistem Sağlığı görür).
 *
 * <p><b>Karar yolu bunu OKUMAZ (I-1).</b> Asla fırlatmaz: okuma hatası / boşaltma gecikmesi / kuşak yok →
 * {@link EvalVerifyReport#STATUS_UNKNOWN} (asla "0 uyuşmazlık").
 *
 * <p><b>Yarışsız algoritma:</b> {@code s0 = seq} → anlık görüntü → {@code s1 = seq} → {@code s1}'e kadar her işlem
 * yazılana dek bekle → yalnız {@code gen = kuşağım} satırları → karşılaştır; {@code s0}'dan sonra kuyruğa alınan ya da kirli
 * anahtarlar ATLANIR. MOS kancaları mutasyondan SONRA çağrıldığı için {@code seq ≤ s0} olan bir anahtarın değeri görüntüde
 * de tabloda da aynıdır.
 *
 * <p><b>Eşitleme turu ({@link #REASON_RESYNC}):</b> bu kuşağı ilk kez gören ya da önceki çağrıdan bu yana
 * {@code 3 × verify-ms}'den uzun boşluk görülen turda karşılaştırma YAPILMAZ: kip kapalıyken kaydedici toplu düşüş yapar ve
 * o ana kadarki anahtarları atlar (bağlam {@code changedSince}); bellekteki durum kaydediciye yeniden kuyruğa alınır
 * ({@link MonitoringOutageService#replayEvalState}) ki anahtarlar yeniden karşılaştırılabilsin. Tur "bilinmiyor: resync"
 * raporlanır, sonraki tur karşılaştırır.
 *
 * <p>Türler ({@link EvalVerifyReport#KINDS}): {@code recovery}, {@code sibling}, {@code suppression}, {@code round_seen},
 * {@code confirmation} (geçti / kaldı), {@code descriptor} ve {@code window_seed} (yalnız ölçülür). Rapor açık anahtar /
 * alan adı / bağlam taşımaz — yalnız {@link EvalStateKeys#shortHash}.
 *
 * <p><b>Dönem bekçisi (R3-1 FX-STATE F1):</b> boşaltma beklemesinden sonra ve karşılaştırmalardan sonra
 * {@link Context#stillCurrent()} sorulur — liderlik değiştiyse, kuşak bırakıldıysa ya da pod çitlendiyse tur
 * {@link EvalVerifyReport#REASON_LEADERSHIP_CHANGED}; ve yalnız hâlâ kuşak sahibi olan pod raporunu YAYIMLAR (eski lider
 * küme gözlemini sahte "uyuşmazlık" / boş "ok" ile ezemez). Hiçbir geçti / kaldı türünde karşılaştırma yoksa ama atlanan
 * varsa tur {@link EvalVerifyReport#REASON_NOTHING_COMPARED} (bilinmeyen ≠ yeşil). "Hâlâ izleniyor mu" sorgusu hata
 * verirse anahtar ATLANIR (uyuşmazlık sayılmaz, F5). Okumaların kendi sorgu zaman aşımı vardır.
 */
@Slf4j
@Component
public class EvalStateVerifier implements EvalStateVerification {

    /** Eşitleme turu nedeni (kısa kod — {@link EvalVerifyReport#reason}). Kalıcı; Sistem Sağlığı çevirir. */
    public static final String REASON_RESYNC = "resync";

    /** {@code s1}'e kadar işlemlerin yazılması için azami bekleme. */
    static final long FLUSH_WAIT_MS = 5_000L;
    /** Bellekte olmayan DOWN satırı için tur başına en çok "hâlâ izleniyor mu" sorgusu (tasarım: ≤ 200). */
    static final int SIBLING_LOOKUPS_MAX = 200;
    /** Tur başına en çok betimleyici çözümleme sorgusu (tasarım: ≤ 50). */
    static final int DESCRIPTOR_LOOKUPS_MAX = 50;
    /** Kardeş gözlem anı toleransına eklenen pay (tazeleme aralığının üstüne; saat / boşaltma gecikmesi). */
    static final long SIBLING_SLACK_MS = 5_000L;
    /** Kaydedilemeyen alan adı uzunluğu sınırı ({@code monitor_eval_state.domain VARCHAR(500)}). */
    static final int MAX_DOMAIN = 500;
    /** Boşluk eşiği = bu kat × {@code verify-ms}. */
    static final int GAP_FACTOR = 3;

    static final String STATE_RUNNING = "RUNNING";
    static final String STATE_ABANDONED = "ABANDONED";

    private static final DateTimeFormatter ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);

    /** Gözlem yükü — uygulamanın genel kuralıyla aynı ad biçimi (SNAKE_CASE). */
    private static final ObjectMapper JSON = JsonMapper.builder()
            .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE).build();

    private final MonitoringOutageService mos;
    private final JdbcTemplate jdbc;

    @Autowired(required = false)
    private ClusterHealthStore clusterHealthStore;

    @Value("${site.monitor.eval-state.verify-ms:300000}")
    private long verifyMs = 300_000L;

    /** Son karşılaştırma tabanının kuşağı (eşitleme turu yapıldı); -1 = yok. */
    private long basisGen = -1L;
    /** Önceki çağrının anı (kaydedici saati); -1 = hiç. */
    private long lastCallMs = -1L;
    private volatile EvalVerifyReport lastReport;

    @Autowired
    public EvalStateVerifier(MonitoringOutageService mos, JdbcTemplate jdbc) {
        this.mos = mos;
        this.jdbc = EvalStateSql.withQueryTimeout(jdbc, EvalStateSql.QUERY_TIMEOUT_SECONDS);
    }

    void setClusterHealthStore(ClusterHealthStore store) {
        this.clusterHealthStore = store;
    }

    void setVerifyMs(long verifyMs) {
        this.verifyMs = verifyMs;
    }

    /** Bu pod'un son raporu (null = henüz tur yok). */
    public EvalVerifyReport lastReport() {
        return lastReport;
    }

    @Override
    public synchronized EvalVerifyReport verify(Context ctx) {
        long at = safeNow(ctx);
        String pod = safeWriter(ctx);
        long gen = safeGeneration(ctx);
        EvalVerifyReport report;
        try {
            report = run(ctx, at, pod, gen);
        } catch (Exception e) {
            log.debug("Kalıcı değerlendirme doğrulaması tamamlanamadı (bilinmiyor): {}", e.toString());
            report = EvalVerifyReport.unknown(at, pod, gen, EvalVerifyReport.REASON_READ_FAILED);
        }
        lastReport = report;
        if (stillCurrent(ctx)) {
            publish(report);
        } else {
            log.debug("Kalıcı değerlendirme doğrulaması: bu pod artık kuşak sahibi değil — rapor yayımlanmadı (kuşak {})", gen);
        }
        if (EvalVerifyReport.STATUS_MISMATCH.equals(report.status())) {
            log.warn("Kalıcı değerlendirme doğrulaması: {} uyuşmazlık — {}: {} (kuşak {}; karar etkilenmez, bellek kaynak)",
                    report.passFailMismatches(), mismatchKinds(report), report.firstMismatches(), gen);
        }
        return report;
    }

    private EvalVerifyReport run(Context ctx, long at, String pod, long gen) {
        if (gen <= 0) return EvalVerifyReport.unknown(at, pod, gen, EvalVerifyReport.REASON_NO_GENERATION);

        // Eşitleme turu — tablo bu kuşakta bellekten geride olabilir: yeniden kuyruğa al, karşılaştırma bir sonraki turda.
        boolean gap = lastCallMs >= 0 && verifyMs > 0 && at - lastCallMs > GAP_FACTOR * verifyMs;
        if (gen != basisGen || lastCallMs < 0 || gap) {
            int n = mos.replayEvalState();
            log.info("Kalıcı değerlendirme durumu eşitleme turu (kuşak {}, {}): {} işlem yeniden kuyruğa alındı — karşılaştırma "
                    + "sonraki turda", gen, gen != basisGen ? "yeni kuşak" : "uzun boşluk", n);
            basisGen = gen;
            lastCallMs = at;
            return EvalVerifyReport.unknown(at, pod, gen, REASON_RESYNC);
        }
        lastCallMs = at;

        long s0 = ctx.seq();
        EvalSnapshot snap;
        try {
            snap = mos.verifySnapshot();
        } catch (Exception e) {
            log.debug("Kalıcı değerlendirme doğrulaması: bellek görüntüsü alınamadı: {}", e.toString());
            return EvalVerifyReport.unknown(at, pod, gen, EvalVerifyReport.REASON_SNAPSHOT_FAILED);
        }
        long s1 = ctx.seq();
        boolean flushed = ctx.awaitFlushed(s1, FLUSH_WAIT_MS);
        // Boşaltma beklemesi sırasında liderlik olayı / toplu düşüş / çit: karşılaştırma penceresi geçersiz (F1).
        if (!stillCurrent(ctx)) return EvalVerifyReport.unknown(at, pod, gen, EvalVerifyReport.REASON_LEADERSHIP_CHANGED);
        if (!flushed) {
            return EvalVerifyReport.unknown(at, pod, gen, EvalVerifyReport.REASON_FLUSH_LAG);
        }

        Rows rows;
        try {
            rows = new Rows(readRecovery(pod, gen), readTypes(pod, gen),
                    readLastStatus(pod, gen, snap.atMs() - maxWindowMs(snap)), readConfirmations(pod, gen));
        } catch (Exception e) {
            log.debug("Kalıcı değerlendirme doğrulaması: tablolar okunamadı: {}", e.toString());
            return EvalVerifyReport.unknown(at, pod, gen, EvalVerifyReport.REASON_READ_FAILED);
        }

        long refreshMs = Math.max(0L, ctx.lastStatusRefreshMs());
        Map<String, EvalVerifyReport.KindResult> kinds = new LinkedHashMap<>();
        List<String> first = new ArrayList<>();
        kinds.put(EvalVerifyReport.KIND_RECOVERY, compareRecovery(ctx, s0, snap, rows.recovery, first));
        kinds.put(EvalVerifyReport.KIND_SIBLING, compareSiblings(ctx, s0, snap, rows.lastStatus, gen, pod, refreshMs, first));
        kinds.put(EvalVerifyReport.KIND_SUPPRESSION, compareSuppression(ctx, s0, snap, rows.types, first));
        kinds.put(EvalVerifyReport.KIND_ROUND_SEEN, compareRoundSeen(ctx, s0, snap, rows.types, first));
        kinds.put(EvalVerifyReport.KIND_CONFIRMATION, compareConfirmations(ctx, s0, snap, rows.confirmations, first));
        kinds.put(EvalVerifyReport.KIND_DESCRIPTOR, checkDescriptors(ctx, s0, rows.confirmations, first));
        kinds.put(EvalVerifyReport.KIND_WINDOW_SEED, measureWindowSeed(snap, rows.lastStatus, first));
        // Karşılaştırma sürerken olay / düşüş / çit → sonuç bu kuşağın değil (F1).
        if (!stillCurrent(ctx)) return EvalVerifyReport.unknown(at, pod, gen, EvalVerifyReport.REASON_LEADERSHIP_CHANGED);
        if (nothingCompared(kinds)) {
            return new EvalVerifyReport(at, pod, gen, EvalVerifyReport.STATUS_UNKNOWN,
                    EvalVerifyReport.REASON_NOTHING_COMPARED, kinds, List.of());
        }
        return new EvalVerifyReport(at, pod, gen, EvalVerifyReport.statusOf(kinds), null, kinds, first);
    }

    /** Geçti / kaldı türlerinin hiçbirinde karşılaştırma yok ama atlanan var → "0 uyuşmazlık" denemez. */
    static boolean nothingCompared(Map<String, EvalVerifyReport.KindResult> kinds) {
        int compared = 0, skipped = 0;
        for (String k : EvalVerifyReport.PASS_FAIL_KINDS) {
            EvalVerifyReport.KindResult r = kinds.get(k);
            if (r == null) continue;
            compared += Math.max(0, r.compared());
            skipped += Math.max(0, r.skipped());
        }
        return compared == 0 && skipped > 0;
    }

    private static boolean stillCurrent(Context ctx) {
        try {
            return ctx.stillCurrent();
        } catch (Exception e) {
            return false;   // bilinmiyor → güvenli taraf: bu turun sonucu kuşağın değil, yayımlanmaz
        }
    }

    // ── Karşılaştırmalar ──────────────────────────────────────────────────────────────────────────────────────────────

    /** {@code recovery}: yokluk ≡ (0, null, null); sayaç, son sayım anı ve zincir kuşağı birebir. */
    private static EvalVerifyReport.KindResult compareRecovery(Context ctx, long s0, EvalSnapshot snap,
                                                              Map<String, RecoveryRow> db, List<String> first) {
        Set<String> keys = new TreeSet<>();
        keys.addAll(snap.recoveryUpCount().keySet());
        keys.addAll(snap.recoveryLastCountedAt().keySet());
        keys.addAll(snap.recoveryGeneration().keySet());
        keys.addAll(db.keySet());
        int compared = 0, mismatches = 0, skipped = 0;
        for (String key : keys) {
            int sep = key.indexOf(':');
            String domain = sep < 0 ? key : key.substring(sep + 1);
            if (domain.length() > MAX_DOMAIN || ctx.changedSince(EvalStateKeys.Table.EVAL_STATE, key, s0)) {
                skipped++;
                continue;
            }
            compared++;
            RecoveryRow r = db.get(key);
            int mUp = snap.recoveryUpCount().getOrDefault(key, 0);
            Long mLast = snap.recoveryLastCountedAt().get(key);
            Long mGen = snap.recoveryGeneration().get(key);
            boolean equal = r == null
                    ? mUp == 0 && mLast == null && mGen == null
                    : mUp == r.upCount() && Objects.equals(mLast, r.lastCountedMs()) && Objects.equals(mGen, r.chainGen());
            if (!equal) {
                mismatches++;
                mismatch(EvalVerifyReport.KIND_RECOVERY, key, first);
            }
        }
        return new EvalVerifyReport.KindResult(compared, mismatches, skipped, null);
    }

    /**
     * {@code sibling}: bellekteki DOWN gözlemi ↔ bu kuşağın {@code up = false} satırı (alan adı, sahiplik, gözlem anı tazeleme
     * payı içinde). Bellekte olmayan, TTL içindeki DOWN satırı yalnız hedef artık izlenmiyorsa tutarlıdır (bellek onu budar).
     */
    private EvalVerifyReport.KindResult compareSiblings(Context ctx, long s0, EvalSnapshot snap,
                                                       Map<String, LastStatusRow> all, long gen, String pod,
                                                       long refreshMs, List<String> first) {
        long now = snap.atMs();
        long ttl = MonitoringOutageService.siblingTtlMs();
        int compared = 0, mismatches = 0, skipped = 0;
        Set<String> memoryKeys = new LinkedHashSet<>();
        for (Map.Entry<String, EvalSnapshot.Down> e : snap.downObserved().entrySet()) {
            EvalSnapshot.Down d = e.getValue();
            if (d.monitorId() == null || d.alertType() == null) continue;
            String lk = EvalStateKeys.lastStatusKey(d.alertType(), "m:" + d.monitorId());
            memoryKeys.add(lk);
            if (now - d.atMs() > ttl) continue;   // bellek bunu zaten yok sayar (kardeş kuralı TTL'i aşanı atlar)
            if (ctx.changedSince(EvalStateKeys.Table.LAST_STATUS, lk, s0)) {
                skipped++;
                continue;
            }
            compared++;
            LastStatusRow r = all.get(lk);
            boolean equal = r != null && r.mine(gen, pod) && !r.up()
                    && Objects.equals(d.domain(), r.domain())
                    && Objects.equals(d.monitorId(), r.monitorId())
                    && Objects.equals(d.teamId(), r.teamId())
                    && Boolean.TRUE.equals(d.standalone()) == Boolean.TRUE.equals(r.standalone())
                    && Math.abs(d.atMs() - r.observedMs()) <= refreshMs + SIBLING_SLACK_MS;
            if (!equal) {
                mismatches++;
                mismatch(EvalVerifyReport.KIND_SIBLING, lk, first);
            }
        }
        int lookups = 0;
        for (LastStatusRow r : all.values()) {
            if (!r.mine(gen, pod) || r.up() || r.monitorId() == null) continue;
            String lk = EvalStateKeys.lastStatusKey(r.alertType(), r.itemKey());
            if (memoryKeys.contains(lk)) continue;
            if (now - r.observedMs() > ttl) continue;   // TTL dışı: bellekte de yok sayılır → tutarlı
            if (ctx.changedSince(EvalStateKeys.Table.LAST_STATUS, lk, s0)) {
                skipped++;
                continue;
            }
            if (lookups >= SIBLING_LOOKUPS_MAX) {
                skipped++;
                continue;
            }
            lookups++;
            Boolean monitored;
            try {
                monitored = mos.stillMonitoredOrNull(new EvalSnapshot.Down(r.alertType(), r.domain(), null, r.monitorId(),
                        r.teamId(), r.standalone(), null, r.observedMs()).slimItem());
            } catch (Exception ex) {
                monitored = null;
            }
            if (monitored == null) {   // "izleniyor mu" okunamadı (DB hatası) → bilinmiyor: atla, uyuşmazlık sayma (F5)
                skipped++;
                continue;
            }
            compared++;
            if (monitored) {
                mismatches++;
                mismatch(EvalVerifyReport.KIND_SIBLING, lk, first);
            }
        }
        return new EvalVerifyReport.KindResult(compared, mismatches, skipped, null);
    }

    /** {@code suppression}: bastırma bayrağı birebir (null ≡ false). */
    private static EvalVerifyReport.KindResult compareSuppression(Context ctx, long s0, EvalSnapshot snap,
                                                                 Map<String, TypeRow> db, List<String> first) {
        Set<String> types = new TreeSet<>(snap.suppressionActive().keySet());
        types.addAll(db.keySet());
        int compared = 0, mismatches = 0, skipped = 0;
        for (String t : types) {
            if (ctx.changedSince(EvalStateKeys.Table.TYPE_STATE, t, s0)) {
                skipped++;
                continue;
            }
            compared++;
            boolean m = Boolean.TRUE.equals(snap.suppressionActive().get(t));
            TypeRow r = db.get(t);
            boolean d = r != null && Boolean.TRUE.equals(r.suppressionActive());
            if (m != d) {
                mismatches++;
                mismatch(EvalVerifyReport.KIND_SUPPRESSION, t, first);
            }
        }
        return new EvalVerifyReport.KindResult(compared, mismatches, skipped, null);
    }

    /** {@code round_seen}: "bu kuşakta zamanlanmış turu görüldü" ⇔ {@code last_scheduled_round_ms ≥ kuşak başlangıcı}. */
    private static EvalVerifyReport.KindResult compareRoundSeen(Context ctx, long s0, EvalSnapshot snap,
                                                               Map<String, TypeRow> db, List<String> first) {
        long genStart = ctx.generationStartMs();
        Set<String> types = new TreeSet<>(snap.scheduledRoundSeen());
        types.addAll(db.keySet());
        int compared = 0, mismatches = 0, skipped = 0;
        for (String t : types) {
            if (ctx.changedSince(EvalStateKeys.Table.TYPE_STATE, t, s0)) {
                skipped++;
                continue;
            }
            compared++;
            boolean m = snap.scheduledRoundSeen().contains(t);
            TypeRow r = db.get(t);
            boolean d = r != null && r.lastScheduledRoundMs() != null && r.lastScheduledRoundMs() >= genStart;
            if (m != d) {
                mismatches++;
                mismatch(EvalVerifyReport.KIND_ROUND_SEEN, t, first);
            }
        }
        return new EvalVerifyReport.KindResult(compared, mismatches, skipped, null);
    }

    /** {@code confirmation}: süren zincirlerin anahtar kümesi + zincir kimliği birebir, deneme ±1. */
    private static EvalVerifyReport.KindResult compareConfirmations(Context ctx, long s0, EvalSnapshot snap,
                                                                   Map<String, ConfirmationRow> db, List<String> first) {
        Map<String, ConfirmationRow> live = new LinkedHashMap<>();
        db.forEach((k, r) -> {
            if (!STATE_ABANDONED.equals(r.state())) live.put(k, r);
        });
        Set<String> keys = new TreeSet<>(snap.inFlight().keySet());
        keys.addAll(live.keySet());
        int compared = 0, mismatches = 0, skipped = 0;
        for (String key : keys) {
            if (ctx.changedSince(EvalStateKeys.Table.CONFIRMATIONS, key, s0)) {
                skipped++;
                continue;
            }
            compared++;
            ConfirmState st = snap.inFlight().get(key);
            Long chain = snap.chainOwner().get(key);
            ConfirmationRow r = live.get(key);
            boolean equal = st != null && r != null
                    && (chain == null || chain == r.chainId())
                    && Math.abs(st.attempt() - r.attempt()) <= 1;
            if (!equal) {
                mismatches++;
                mismatch(EvalVerifyReport.KIND_CONFIRMATION, key, first);
            }
        }
        return new EvalVerifyReport.KindResult(compared, mismatches, skipped, null);
    }

    /**
     * {@code descriptor} (yalnız ölçülür): bu kuşağın RUNNING satırı 3-2'de sürdürülebilir mi — izleme türü çözülmüş
     * ({@code monitor_type}, kaydedicinin {@code RecheckRefs} eşlemesi) VE hedef hâlâ izleniyor. Tur başına ≤ 50 sorgu.
     */
    private EvalVerifyReport.KindResult checkDescriptors(Context ctx, long s0, Map<String, ConfirmationRow> db,
                                                        List<String> first) {
        int compared = 0, mismatches = 0, skipped = 0, lookups = 0;
        for (Map.Entry<String, ConfirmationRow> e : db.entrySet()) {
            ConfirmationRow r = e.getValue();
            if (!STATE_RUNNING.equals(r.state())) continue;
            if (ctx.changedSince(EvalStateKeys.Table.CONFIRMATIONS, e.getKey(), s0) || lookups >= DESCRIPTOR_LOOKUPS_MAX) {
                skipped++;
                continue;
            }
            boolean resolvable = r.monitorType() != null && !r.monitorType().isBlank();
            if (resolvable) {
                lookups++;
                Map<String, Object> ctxMap = new HashMap<>();
                if (r.monitorId() != null) ctxMap.put("monitor_id", r.monitorId());
                Boolean monitored;
                try {
                    monitored = mos.stillMonitoredOrNull(new SweepItem(r.alertType(), r.domain(), r.detail(), false, null,
                            ctxMap, null));
                } catch (Exception ex) {
                    monitored = null;
                }
                if (monitored == null) {   // okunamadı → bilinmiyor: atla (F5)
                    skipped++;
                    continue;
                }
                resolvable = monitored;
            }
            compared++;
            if (!resolvable) {
                mismatches++;
                mismatch(EvalVerifyReport.KIND_DESCRIPTOR, e.getKey(), first);
            }
        }
        return new EvalVerifyReport.KindResult(compared, mismatches, skipped, null);
    }

    /**
     * {@code window_seed} (yalnız ölçülür): 3-2'nin GAINED'de tablodan kuracağı pencere örneği (türün pencere içindeki her
     * kaleminin SON sonucu; alan adı, kalemlerinden biri ağ-sınıfı DOWN ise DOWN) ile bellekteki pencere (son turun alan
     * adı işaretleri). Uyuşmazlık = bastırma kararı farklı çıkan tür; {@code maxDelta} = en büyük {@code |Δoran|}.
     */
    private EvalVerifyReport.KindResult measureWindowSeed(EvalSnapshot snap, Map<String, LastStatusRow> all,
                                                         List<String> first) {
        int compared = 0, mismatches = 0;
        double maxDelta = 0;
        for (Map.Entry<String, EvalSnapshot.Window> e : snap.windows().entrySet()) {
            String type = e.getKey();
            EvalSnapshot.Window w = e.getValue();
            long cutoff = snap.atMs() - w.windowMs();
            Map<String, Boolean> byDomain = new HashMap<>();
            for (LastStatusRow r : all.values()) {
                if (!type.equals(r.alertType()) || r.domain() == null || r.observedMs() < cutoff) continue;
                byDomain.merge(r.domain(), !r.up() && r.networkDown(), Boolean::logicalOr);
            }
            int seedDown = (int) byDomain.values().stream().filter(Boolean::booleanValue).count();
            int seedTotal = byDomain.size();
            compared++;
            double seedRate = seedTotal == 0 ? 0 : (double) seedDown / seedTotal;
            maxDelta = Math.max(maxDelta, Math.abs(w.rate() - seedRate));
            boolean memDecision = mos.wouldSuppress(type, w.networkDown(), w.total());
            boolean seedDecision = mos.wouldSuppress(type, seedDown, seedTotal);
            if (memDecision != seedDecision) {
                mismatches++;
                mismatch(EvalVerifyReport.KIND_WINDOW_SEED, type, first);
            }
        }
        return new EvalVerifyReport.KindResult(compared, mismatches, 0, compared == 0 ? null : maxDelta);
    }

    private static void mismatch(String kind, String key, List<String> first) {
        if (first.size() < EvalVerifyReport.MAX_KEYS) first.add(kind + ":" + EvalStateKeys.shortHash(key));
    }

    private static String mismatchKinds(EvalVerifyReport r) {
        List<String> out = new ArrayList<>();
        for (String k : EvalVerifyReport.PASS_FAIL_KINDS) {
            EvalVerifyReport.KindResult kr = r.kinds().get(k);
            if (kr != null && kr.mismatches() > 0) out.add(k + "=" + kr.mismatches());
        }
        return String.join(", ", out);
    }

    // ── Okumalar (yalnız bu kuşak; pencere tohumu için son pencere içindeki tüm kuşaklar) ─────────────────────────────

    record RecoveryRow(String alertType, String domain, int upCount, Long lastCountedMs, Long chainGen) {}

    record TypeRow(String alertType, Boolean suppressionActive, Long lastScheduledRoundMs) {}

    record LastStatusRow(String alertType, String itemKey, String domain, Long monitorId, Long teamId, Boolean standalone,
                         boolean up, boolean networkDown, long observedMs, long gen, String writer) {
        boolean mine(long myGen, String me) {
            return gen == myGen && Objects.equals(writer, me);
        }
    }

    record ConfirmationRow(String confirmKey, long chainId, String alertType, String domain, String detail,
                           String monitorType, Long monitorId, int attempt, String state) {}

    private record Rows(Map<String, RecoveryRow> recovery, Map<String, TypeRow> types,
                        Map<String, LastStatusRow> lastStatus, Map<String, ConfirmationRow> confirmations) {}

    static final String SQL_RECOVERY = "SELECT alert_type, domain, recovery_up_count, recovery_last_counted_ms, "
            + "recovery_chain_gen FROM " + EvalStateKeys.Table.EVAL_STATE.tableName() + " WHERE gen = ? AND writer = ?";
    static final String SQL_TYPES = "SELECT alert_type, suppression_active, last_scheduled_round_ms FROM "
            + EvalStateKeys.Table.TYPE_STATE.tableName() + " WHERE gen = ? AND writer = ? AND alert_type <> ?";
    static final String SQL_LAST_STATUS = "SELECT alert_type, item_key, domain, monitor_id, team_id, standalone, up, "
            + "network_down, observed_ms, gen, writer FROM " + EvalStateKeys.Table.LAST_STATUS.tableName()
            + " WHERE (gen = ? AND writer = ?) OR observed_ms >= ?";
    static final String SQL_CONFIRMATIONS = "SELECT confirm_key, chain_id, alert_type, domain, detail, monitor_type, "
            + "monitor_id, attempt, state FROM " + EvalStateKeys.Table.CONFIRMATIONS.tableName()
            + " WHERE gen = ? AND writer = ?";

    private Map<String, RecoveryRow> readRecovery(String pod, long gen) {
        Map<String, RecoveryRow> out = new LinkedHashMap<>();
        jdbc.query(SQL_RECOVERY, rs -> {
            RecoveryRow r = new RecoveryRow(rs.getString(1), rs.getString(2), intOf(rs, 3), longOf(rs, 4), longOf(rs, 5));
            if (r.alertType() != null && r.domain() != null) out.put(EvalStateKeys.recoveryKey(r.alertType(), r.domain()), r);
        }, gen, pod);
        return out;
    }

    private Map<String, TypeRow> readTypes(String pod, long gen) {
        Map<String, TypeRow> out = new LinkedHashMap<>();
        jdbc.query(SQL_TYPES, rs -> {
            TypeRow r = new TypeRow(rs.getString(1), boolOf(rs, 2), longOf(rs, 3));
            if (r.alertType() != null) out.put(r.alertType(), r);
        }, gen, pod, EvalStateKeys.GENERATION_ROW);
        return out;
    }

    private Map<String, LastStatusRow> readLastStatus(String pod, long gen, long windowCutoff) {
        Map<String, LastStatusRow> out = new LinkedHashMap<>();
        jdbc.query(SQL_LAST_STATUS, rs -> {
            Long observed = longOf(rs, 9);
            Long g = longOf(rs, 10);
            LastStatusRow r = new LastStatusRow(rs.getString(1), rs.getString(2), rs.getString(3), longOf(rs, 4),
                    longOf(rs, 5), boolOf(rs, 6), Boolean.TRUE.equals(boolOf(rs, 7)), Boolean.TRUE.equals(boolOf(rs, 8)),
                    observed == null ? 0L : observed, g == null ? 0L : g, rs.getString(11));
            if (r.alertType() != null && r.itemKey() != null) {
                out.put(EvalStateKeys.lastStatusKey(r.alertType(), r.itemKey()), r);
            }
        }, gen, pod, windowCutoff);
        return out;
    }

    private Map<String, ConfirmationRow> readConfirmations(String pod, long gen) {
        Map<String, ConfirmationRow> out = new LinkedHashMap<>();
        jdbc.query(SQL_CONFIRMATIONS, rs -> {
            Long chain = longOf(rs, 2);
            ConfirmationRow r = new ConfirmationRow(rs.getString(1), chain == null ? 0L : chain, rs.getString(3),
                    rs.getString(4), rs.getString(5), rs.getString(6), longOf(rs, 7), intOf(rs, 8), rs.getString(9));
            if (r.confirmKey() != null) out.put(r.confirmKey(), r);
        }, gen, pod);
        return out;
    }

    private static Long longOf(ResultSet rs, int i) throws SQLException {
        Object v = rs.getObject(i);
        return v instanceof Number n ? n.longValue() : null;
    }

    private static int intOf(ResultSet rs, int i) throws SQLException {
        Object v = rs.getObject(i);
        return v instanceof Number n ? n.intValue() : 0;
    }

    private static Boolean boolOf(ResultSet rs, int i) throws SQLException {
        Object v = rs.getObject(i);
        if (v instanceof Boolean b) return b;
        if (v instanceof Number n) return n.intValue() != 0;
        return null;
    }

    /** Pencere tohumu için en uzun pencere (görüntüdeki türler; yoksa taban). */
    private static long maxWindowMs(EvalSnapshot snap) {
        long max = 0L;
        for (EvalSnapshot.Window w : snap.windows().values()) max = Math.max(max, w.windowMs());
        return max;
    }

    // ── Yayın ─────────────────────────────────────────────────────────────────────────────────────────────────────────

    /** Raporu küme gözlemi olarak yayımlar (en yeni kazanır). Hiç fırlatmaz. */
    private void publish(EvalVerifyReport r) {
        ClusterHealthStore store = clusterHealthStore;
        if (store == null || r == null) return;
        try {
            store.publish(EvalVerifyReport.OBSERVATION, r.pod(), r.atMs(), payloadOf(r),
                    ISO.format(Instant.ofEpochMilli(r.atMs())));
        } catch (Exception e) {
            log.debug("Kalıcı değerlendirme doğrulama raporu yayımlanamadı: {}", e.toString());
        }
    }

    /**
     * Gözlem yükü sınırı — {@code ClusterHealthStore.PAYLOAD_MAX}; daha uzun metin "…" ile kırpılır ve JSON bozulur (ekran
     * "okunamadı" der). Rapor bu sınırın çok altındadır (7 tür, ≤ 5 özet); yine de aşarsa kademeli küçültülür.
     */
    static final int PAYLOAD_MAX = 4000;

    /** Gözlem yükü: raporun SNAKE_CASE JSON'u ({@code at_ms, pod, generation, status, reason, kinds, first_mismatches}). */
    public static String toJson(EvalVerifyReport r) {
        return JSON.writeValueAsString(r);
    }

    /**
     * Yayımlanacak yük — HER ZAMAN geçerli JSON ve ≤ {@link #PAYLOAD_MAX}: önce tam rapor; sığmazsa ilk uyuşmazlık
     * özetleri atılır; yine sığmazsa (beklenmez — örn. aşırı uzun pod adı) türler ve pod adı atılır, durum korunur.
     */
    static String payloadOf(EvalVerifyReport r) {
        String json = toJson(r);
        if (json.length() <= PAYLOAD_MAX) return json;
        json = toJson(new EvalVerifyReport(r.atMs(), r.pod(), r.generation(), r.status(), r.reason(), r.kinds(), List.of()));
        if (json.length() <= PAYLOAD_MAX) return json;
        return toJson(new EvalVerifyReport(r.atMs(), null, r.generation(), r.status(), "payload_too_large", Map.of(), List.of()));
    }

    /** Gözlem yükünü okur (Sistem Sağlığı); bozuk / boş → null. */
    public static EvalVerifyReport fromJson(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return JSON.readValue(json, EvalVerifyReport.class);
        } catch (Exception e) {
            return null;
        }
    }

    // ── Bağlam güvenli okumaları (kaydedici hata verse de rapor üretilir) ─────────────────────────────────────────────

    private static long safeNow(Context ctx) {
        try {
            return ctx.nowMs();
        } catch (Exception e) {
            return System.currentTimeMillis();
        }
    }

    private static String safeWriter(Context ctx) {
        try {
            return ctx.writer();
        } catch (Exception e) {
            return null;
        }
    }

    private static long safeGeneration(Context ctx) {
        try {
            return ctx.generation();
        } catch (Exception e) {
            return 0L;
        }
    }
}
