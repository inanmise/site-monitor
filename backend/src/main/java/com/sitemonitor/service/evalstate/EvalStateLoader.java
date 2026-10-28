package com.sitemonitor.service.evalstate;

import com.sitemonitor.service.MonitoringOutageService;
import com.sitemonitor.service.evalstate.EvalLoadContinuity.Decision;
import com.sitemonitor.service.evalstate.EvalLoadContinuity.Tier;
import com.sitemonitor.service.evalstate.EvalStateSql.LoadRecoveryRow;
import com.sitemonitor.service.evalstate.EvalStateSql.LoadSiblingRow;
import com.sitemonitor.service.evalstate.EvalStateSql.LoadTypeRow;
import com.sitemonitor.service.evalstate.EvalStateSql.PriorGeneration;
import org.springframework.dao.DataAccessException;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * Devirde kalıcı durumu OKUYAN taraf (2026-10-28, çok pod Sürüm 3-2; tasarım R3-2 §3.4) — durumsuz; kaydedicinin yazıcı iş
 * parçacığında, kiraya bağlı kuşak CAS'ından HEMEN SONRA (önceki sahip o anda çitlenmiştir) ve kaydedicinin 15 sn sorgu zaman
 * aşımlı şablonuyla koşar. YALNIZ {@code gen = prior.gen} satırları (pencere satırları hariç: her kuşak), en çok 5 ifade:
 *
 * <ol>
 *   <li>(FULL aday) ara lider sayısı — {@link EvalStateSql#SQL_INTERMEDIATE}; &gt; 0 → OBSERVATIONS / {@code intermediate_leader}</li>
 *   <li>(FULL) kurtarma sayaçları — {@code recovery_up_count > 0}</li>
 *   <li>DOWN kardeşler — {@code up = FALSE}, kimlikli, TTL ({@link MonitoringOutageService#siblingTtlMs()}) içinde</li>
 *   <li>tür satırları — FULL: bastırma TRUE + tur görüldü; her iki katman: tur aralığı</li>
 *   <li>pencere satırları — son {@link EvalWindowSeed#WINDOW_CAP_MS}, {@link EvalWindowSeed#marks}</li>
 * </ol>
 *
 * <p><b>Ya hep ya hiç (J-3):</b> her okuma {@code limit + 1} satır ister; sınırı aşan okuma → NONE / {@code too_large} (kısmi
 * yük ASLA); herhangi bir {@code DataAccessException} → NONE / {@code read_failed}. İfadeler arasında yükleme belirteci
 * ({@link EvalStateSource#evalLoadToken()}) sorulur: değiştiyse (zaman aşımı / liderlik değişti) okuma bırakılır, hiçbir şey
 * uygulanmaz (MOS kapısı zaten raporunu yazdı). Değer / alan adı günlüğe yazılmaz.
 */
public final class EvalStateLoader {

    /** Okuma başına satır üst sınırı (tasarım: 200 000). Aşan okuma = {@code too_large}. */
    public static final int MAX_LOAD_ROWS = 200_000;

    /**
     * Okuma sonucu: ya bir yük ({@code load}), ya bir "yüklenmedi" raporu ({@code skipped}) ya da belirteç eskidiği için
     * bırakıldı ({@code aborted}: ikisi de null — MOS'a hiçbir şey söylenmez).
     *
     * @param failure okuma hatasıysa kısa teknik özet (istisna sınıfı + kök neden; değer yok) — günlük için
     */
    public record Outcome(EvalLoad load, EvalLoadReport skipped, boolean aborted, String failure) {

        static Outcome loaded(EvalLoad load) {
            return new Outcome(load, null, false, null);
        }

        static Outcome skipped(EvalLoadReport report, String failure) {
            return new Outcome(null, report, false, failure);
        }

        static Outcome abort() {
            return new Outcome(null, null, true, null);
        }
    }

    private EvalStateLoader() {
    }

    /**
     * Süreklilik kararına göre okur.
     *
     * @param sql          kaydedicinin SQL katmanı (sorgu zaman aşımlı şablon)
     * @param decision     {@link EvalLoadContinuity#decide} sonucu (NONE verilirse onun raporu döner)
     * @param token        yükleme belirteci (CAS'tan hemen önce okundu)
     * @param currentToken her ifadeden sonra sorulan güncel belirteç
     * @param me           bu pod'un yazan kimliği (rapor {@code pod}, ara lider sorgusunda hariç tutulan)
     * @param nowMs        halefin saati (TTL / pencere kesimi, yaşlar)
     * @param maxRows      okuma başına üst sınır ({@link #MAX_LOAD_ROWS}; testler küçültür)
     */
    public static Outcome read(EvalStateSql sql, Decision decision, long token, LongSupplier currentToken, String me,
                               long nowMs, int maxRows) {
        if (decision == null || decision.tier() == Tier.NONE) {
            return Outcome.skipped(decision == null ? EvalLoadReport.none(nowMs, me, EvalLoadReport.REASON_NO_PREVIOUS_GENERATION)
                    : decision.noneReport(nowMs, me), null);
        }
        int cap = Math.max(1, maxRows);
        long t0 = System.nanoTime();
        Decision d = decision;
        PriorGeneration prior = d.prior();
        long gen = prior.gen();
        try {
            if (d.tier() == Tier.FULL) {
                int n = sql.countIntermediate(d.intermediateSinceMs(), prior.writer(), me);
                d = EvalLoadContinuity.afterIntermediate(d, n);
                if (!same(token, currentToken)) return Outcome.abort();
            }
            boolean full = d.tier() == Tier.FULL;
            Map<String, EvalLoad.Recovery> recovery = new LinkedHashMap<>();
            if (full) {
                List<LoadRecoveryRow> rows = sql.loadRecovery(gen, cap + 1);
                if (rows.size() > cap) return tooLarge(d, me, nowMs, t0);
                for (LoadRecoveryRow r : rows) {
                    if (r.alertType() == null || r.domain() == null || r.upCount() <= 0) continue;
                    recovery.put(EvalStateKeys.recoveryKey(r.alertType(), r.domain()),
                            new EvalLoad.Recovery(r.upCount(), r.lastCountedMs()));
                }
                if (!same(token, currentToken)) return Outcome.abort();
            }
            Map<String, EvalLoad.Sibling> siblings = new LinkedHashMap<>();
            List<LoadSiblingRow> sib = sql.loadSiblings(gen, nowMs - MonitoringOutageService.siblingTtlMs(), cap + 1);
            if (sib.size() > cap) return tooLarge(d, me, nowMs, t0);
            for (LoadSiblingRow r : sib) {
                if (r.alertType() == null || r.domain() == null) continue;
                EvalLoad.Sibling s = new EvalLoad.Sibling(r.alertType(), r.domain(), r.detail(), r.monitorId(), r.teamId(),
                        r.standalone(), r.errorClass(), r.observedMs());
                siblings.merge(s.key(), s, (a, b) -> b.observedMs() > a.observedMs() ? b : a);
            }
            if (!same(token, currentToken)) return Outcome.abort();
            Set<String> suppression = new LinkedHashSet<>();
            Set<String> roundSeen = new LinkedHashSet<>();
            Map<String, Long> intervals = new LinkedHashMap<>();
            List<LoadTypeRow> types = sql.loadTypes(gen, cap + 1);
            if (types.size() > cap) return tooLarge(d, me, nowMs, t0);
            for (LoadTypeRow r : types) {
                if (r.alertType() == null || EvalStateKeys.GENERATION_ROW.equals(r.alertType())) continue;
                if (full && Boolean.TRUE.equals(r.suppressionActive())) suppression.add(r.alertType());
                if (full && r.lastScheduledRoundMs() != null) roundSeen.add(r.alertType());
                if (r.roundIntervalMs() != null && r.roundIntervalMs() > 0) intervals.put(r.alertType(), r.roundIntervalMs());
            }
            if (!same(token, currentToken)) return Outcome.abort();
            List<EvalWindowSeed.Row> windowRows = sql.loadWindow(nowMs - EvalWindowSeed.WINDOW_CAP_MS, cap + 1);
            if (windowRows.size() > cap) return tooLarge(d, me, nowMs, t0);
            Map<String, Map<String, EvalLoad.SeedMark>> window = EvalWindowSeed.marks(windowRows,
                    nowMs - EvalWindowSeed.WINDOW_CAP_MS);
            if (!same(token, currentToken)) return Outcome.abort();
            long readMs = elapsedMs(t0);
            EvalLoad.Meta meta = new EvalLoad.Meta(gen, prior.writer(), d.handoverAgeMs(),
                    d.durableAgeMs() == null ? 0L : d.durableAgeMs(), full ? null : d.reason(), readMs);
            return Outcome.loaded(new EvalLoad(token, d.loadTier(), recovery, siblings, suppression, roundSeen, window,
                    intervals, meta));
        } catch (DataAccessException e) {
            return Outcome.skipped(EvalLoadReport.none(nowMs, me, EvalLoadReport.REASON_READ_FAILED, d.loadTier(),
                    d.fromGeneration(), d.fromWriter(), d.handoverAgeMs(), d.durableAgeMs(), elapsedMs(t0)), shortText(e));
        }
    }

    private static Outcome tooLarge(Decision d, String me, long nowMs, long t0) {
        return Outcome.skipped(EvalLoadReport.none(nowMs, me, EvalLoadReport.REASON_TOO_LARGE, d.loadTier(),
                d.fromGeneration(), d.fromWriter(), d.handoverAgeMs(), d.durableAgeMs(), elapsedMs(t0)), null);
    }

    private static boolean same(long token, LongSupplier current) {
        if (current == null) return true;
        try {
            return current.getAsLong() == token;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static long elapsedMs(long t0) {
        return TimeUnit.NANOSECONDS.toMillis(Math.max(0L, System.nanoTime() - t0));
    }

    /** İstisna sınıfı + kök nedenin sınıfı (ileti YOK — SQL metni değer taşıyabilir). */
    static String shortText(Throwable t) {
        Throwable root = t;
        int causeDepth = 0;
        while (causeDepth++ < com.sitemonitor.util.CauseChain.MAX_DEPTH && root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return t.getClass().getSimpleName() + (root != t ? " / " + root.getClass().getSimpleName() : "");
    }
}
