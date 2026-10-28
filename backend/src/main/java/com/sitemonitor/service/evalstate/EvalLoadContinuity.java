package com.sitemonitor.service.evalstate;

import com.sitemonitor.service.evalstate.EvalStateSql.PriorGeneration;

import java.util.Objects;

/**
 * Devirde yükleme SÜREKLİLİK kararı — saf doğruluk tablosu (2026-10-28, çok pod Sürüm 3-2; tasarım R3-2 §2.4). Girdi: yeni
 * liderin kiraya bağlı kuşak CAS'ının yerini aldığı {@code '*'} satırı ({@link PriorGeneration}, kazanan CAS'ın okuduğu
 * satır). Sırayla denenir; ilk düşen denetim nedeni verir ({@link EvalLoadReport} {@code REASON_*}, kalıcı kodlar):
 *
 * <ol>
 *   <li>işaret sütunları okunabiliyor — değilse NONE / {@code columns_missing}</li>
 *   <li>{@code prior.gen > 0} — NONE / {@code no_previous_generation}</li>
 *   <li>{@code prior.writer ≠ ben} (son kayıtlı kuşak bizimse arada kaydetmeyen bir lider olabilir) — NONE /
 *       {@code writer_is_self}</li>
 *   <li>{@code prior.state_gen == prior.gen} (bu kuşak için 3-2 işareti yazılmış) — NONE / {@code previous_unmarked}</li>
 *   <li>{@code prior.flushed_ms} dolu (kuşak EKSİKSİZ) — NONE / {@code previous_incomplete}</li>
 *   <li>{@code flushed_ms − şimdi ≤ 30 sn} — NONE / {@code clock_skew}</li>
 *   <li>{@code şimdi − flushed_ms ≤ 5 dk} — NONE / {@code previous_stale}</li>
 *   <li>→ OBSERVATIONS mümkün. FULL için ayrıca: {@code handover_ms} dolu ve {@code = updated_ms} — değilse OBSERVATIONS /
 *       {@code no_handover}</li>
 *   <li>{@code şimdi − handover_ms ≤ 5 dk} (değilse {@code handover_too_old}) ve {@code handover_ms − şimdi ≤ 30 sn}
 *       (değilse {@code clock_skew}) — OBSERVATIONS</li>
 *   <li>(yükleyici) ara lider yok — {@link EvalStateSql#SQL_INTERMEDIATE} sayısı 0, değilse OBSERVATIONS /
 *       {@code intermediate_leader} ({@link #afterIntermediate})</li>
 * </ol>
 * Sınırlar kapsayıcıdır ({@code ≤}). Saatler pod saatleridir (önceki sahip ↔ halef); yaşlar halefin saatiyle ölçülür.
 */
public final class EvalLoadContinuity {

    /** Pod saatleri arası kabul edilen fark (R2b izleyicisi 5 / 30 sn'de alarm verir — dakika ölçeğinin çok altında). */
    public static final long CLOCK_SLACK_MS = 30_000L;
    /** Son "eksiksiz ve kalıcı" yazımın azami yaşı — OBSERVATIONS için. */
    public static final long OBSERVATIONS_MAX_AGE_MS = 5L * 60 * 1000;
    /** Devir işaretinin azami yaşı — FULL için. */
    public static final long FULL_MAX_AGE_MS = 5L * 60 * 1000;
    /** Ara lider sorgusunun devir anından geriye payı ({@code handover_ms − 30 sn}). */
    public static final long INTERMEDIATE_SLACK_MS = 30_000L;

    /** Karar katmanı. {@link #FULL} = tam aday (ara lider sorgusu yükleyicide). */
    public enum Tier { NONE, OBSERVATIONS, FULL }

    /**
     * Karar.
     *
     * @param tier          katman
     * @param reason        NONE'da neden yüklenmediği, OBSERVATIONS'da neden tam olmadığı; FULL'da null
     * @param prior         karar verilen satır (null olabilir)
     * @param durableAgeMs  {@code şimdi − flushed_ms} (≥ 0; bilinmiyorsa null)
     * @param handoverAgeMs {@code şimdi − handover_ms} (≥ 0; yalnız geçerli devir işareti varsa, yoksa null)
     */
    public record Decision(Tier tier, String reason, PriorGeneration prior, Long durableAgeMs, Long handoverAgeMs) {

        public Decision {
            Objects.requireNonNull(tier, "tier");
        }

        /** Önceki kuşak (yoksa ya da 0 ise null — rapor alanı). */
        public Long fromGeneration() {
            return prior != null && prior.gen() > 0 ? prior.gen() : null;
        }

        /** Önceki sahibin örnek kimliği (yoksa null). */
        public String fromWriter() {
            return prior == null ? null : prior.writer();
        }

        /** Ara lider sorgusunun alt sınırı ({@code handover_ms − 30 sn}); devir işareti yoksa {@code Long.MIN_VALUE}. */
        public long intermediateSinceMs() {
            return prior == null || prior.handoverMs() == null ? Long.MIN_VALUE
                    : prior.handoverMs() - INTERMEDIATE_SLACK_MS;
        }

        /** Yük katmanı adı ({@link EvalLoad#TIER_FULL} | {@link EvalLoad#TIER_OBSERVATIONS}); NONE'da null. */
        public String loadTier() {
            return switch (tier) {
                case FULL -> EvalLoad.TIER_FULL;
                case OBSERVATIONS -> EvalLoad.TIER_OBSERVATIONS;
                case NONE -> null;
            };
        }

        /** NONE kararının raporu ({@code attempted_tier} null — süreklilik reddetti). */
        public EvalLoadReport noneReport(long atMs, String pod) {
            return EvalLoadReport.none(atMs, pod, reason, null, fromGeneration(), fromWriter(), handoverAgeMs, durableAgeMs,
                    0L);
        }
    }

    private EvalLoadContinuity() {
    }

    /**
     * Süreklilik kararı (denetim 1–9; 10 = {@link #afterIntermediate}).
     *
     * @param prior          yerine geçilen satır (null = okunamadı / yok)
     * @param me             bu pod'un yazan kimliği ({@code SchedulerService.instanceId()})
     * @param nowMs          halefin saati
     * @param columnsMissing işaret sütunları okunamadı (şema yaması bitmedi)
     */
    public static Decision decide(PriorGeneration prior, String me, long nowMs, boolean columnsMissing) {
        if (columnsMissing) return none(EvalLoadReport.REASON_COLUMNS_MISSING, prior, null);
        if (prior == null || prior.gen() <= 0) return none(EvalLoadReport.REASON_NO_PREVIOUS_GENERATION, prior, null);
        if (prior.writer() == null || prior.writer().equals(me)) {
            return none(EvalLoadReport.REASON_WRITER_IS_SELF, prior, null);
        }
        if (prior.stateGen() == null || prior.stateGen() != prior.gen()) {
            return none(EvalLoadReport.REASON_PREVIOUS_UNMARKED, prior, null);
        }
        Long flushed = prior.flushedMs();
        if (flushed == null) return none(EvalLoadReport.REASON_PREVIOUS_INCOMPLETE, prior, null);
        Long durableAge = Math.max(0L, nowMs - flushed);
        if (flushed - nowMs > CLOCK_SLACK_MS) return none(EvalLoadReport.REASON_CLOCK_SKEW, prior, durableAge);
        if (nowMs - flushed > OBSERVATIONS_MAX_AGE_MS) return none(EvalLoadReport.REASON_PREVIOUS_STALE, prior, durableAge);
        // ── OBSERVATIONS mümkün; FULL için temiz devir kanıtı ──
        Long handover = prior.handoverMs();
        if (handover == null || handover != prior.updatedMs()) {
            return new Decision(Tier.OBSERVATIONS, EvalLoadReport.REASON_NO_HANDOVER, prior, durableAge, null);
        }
        Long handoverAge = Math.max(0L, nowMs - handover);
        if (handover - nowMs > CLOCK_SLACK_MS) {
            return new Decision(Tier.OBSERVATIONS, EvalLoadReport.REASON_CLOCK_SKEW, prior, durableAge, handoverAge);
        }
        if (nowMs - handover > FULL_MAX_AGE_MS) {
            return new Decision(Tier.OBSERVATIONS, EvalLoadReport.REASON_HANDOVER_TOO_OLD, prior, durableAge, handoverAge);
        }
        return new Decision(Tier.FULL, null, prior, durableAge, handoverAge);
    }

    /** Denetim 10: FULL aday + ara lider sayısı &gt; 0 → OBSERVATIONS / {@code intermediate_leader}; aksi hâlde aynı karar. */
    public static Decision afterIntermediate(Decision d, long intermediateCount) {
        if (d == null || d.tier() != Tier.FULL || intermediateCount <= 0) return d;
        return new Decision(Tier.OBSERVATIONS, EvalLoadReport.REASON_INTERMEDIATE_LEADER, d.prior(), d.durableAgeMs(),
                d.handoverAgeMs());
    }

    private static Decision none(String reason, PriorGeneration prior, Long durableAge) {
        return new Decision(Tier.NONE, reason, prior, durableAge, null);
    }
}
