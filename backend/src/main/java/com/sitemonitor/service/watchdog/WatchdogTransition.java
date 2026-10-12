package com.sitemonitor.service.watchdog;

import com.sitemonitor.model.WatchdogState;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * Kontrol durum geçişi — SAF (2026-10-11). Tek bir kötü ölçüm alarm yapmaz: {@code confirm} ardışık sorunlu ölçüm
 * PROBLEM'e, aynı sayıda sağlıklı ölçüm OK'e geçirir. PROBLEM sürerken {@code reminderHours} (0 = hiç) dolunca bir
 * hatırlatma. UNKNOWN (ölçülemedi) ve NA (uygulanmaz) sayaçları değiştirmez — ölçülemeyen kontrol ne alarm ne iyileşme
 * üretir. Önem ölçümden gelir (örn. veritabanı DOWN → CRITICAL).
 *
 * <p><b>Teslim edilemeyen bildirim kaybolmaz:</b> hiçbir kanaldan gidemeyen açılış / hatırlatma ({@code *_FAILED})
 * {@link #RETRY_MINUTES} dakikada bir yeniden denenir (dakikalık değerlendirmede her tur değil — günlük şişmesin).
 */
public final class WatchdogTransition {

    private WatchdogTransition() {}

    public static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);

    /** Yapılacak bildirim. */
    public enum Action { NONE, ALERT, REMIND, RECOVER }

    /** Ölçüm sonucu. */
    public enum Result { OK, PROBLEM, UNKNOWN, NA }

    /** Teslim edilemeyen bildirimin yeniden deneme aralığı (dk). */
    public static final int RETRY_MINUTES = 15;

    /**
     * {@code state} yerinde güncellenir; dönen değer gönderilecek bildirimdir (bildirim anı ÇAĞIRAN tarafından
     * gönderildikten sonra {@link #markNotified} ile yazılır — gönderilemeyen bildirim "gitti" sayılmaz).
     */
    public static Action apply(WatchdogState state, Result result, String severity, String factsJson,
                               Instant now, int confirm, int reminderHours) {
        int need = Math.max(1, confirm);
        String nowIso = ISO.format(now);
        state.setLastResult(result.name());
        state.setEvaluatedAt(nowIso);
        if (factsJson != null) state.setFacts(factsJson);
        if (state.getStatus() == null) state.setStatus("OK");
        if (state.getSince() == null) state.setSince(nowIso);
        int ps = state.getProblemStreak() == null ? 0 : state.getProblemStreak();
        int os = state.getOkStreak() == null ? 0 : state.getOkStreak();
        switch (result) {
            case PROBLEM -> {
                ps++;
                state.setProblemStreak(ps);
                state.setOkStreak(0);
                if (severity != null) state.setSeverity(severity);
                if (!"PROBLEM".equals(state.getStatus())) {
                    if (ps >= need) {
                        state.setStatus("PROBLEM");
                        state.setSince(nowIso);
                        return Action.ALERT;
                    }
                    return Action.NONE;
                }
                String kind = state.getNotifiedKind();
                Instant notified = state.getNotifiedAt() == null ? null : parse(state.getNotifiedAt());
                // Bu sorun dönemi için hiç bildirim denenmedi (örn. bildirim kapalıyken açıldı, sonra açıldı)
                if (notified == null || kind == null || notified.isBefore(parse(state.getSince()))) return Action.ALERT;
                if (kind.endsWith("_FAILED")) {
                    if (Duration.between(notified, now).toMinutes() < RETRY_MINUTES) return Action.NONE;
                    return kind.startsWith("REMIND") ? Action.REMIND : Action.ALERT;
                }
                if (reminderHours > 0 && Duration.between(notified, now).toHours() >= reminderHours) return Action.REMIND;
                return Action.NONE;
            }
            case OK -> {
                os++;
                state.setOkStreak(os);
                state.setProblemStreak(0);
                if ("PROBLEM".equals(state.getStatus()) && os >= need) {
                    state.setStatus("OK");
                    state.setSince(nowIso);
                    return Action.RECOVER;
                }
                if (!"PROBLEM".equals(state.getStatus())) state.setSeverity(null);
                return Action.NONE;
            }
            default -> {
                return Action.NONE;   // UNKNOWN / NA: sayaçlar değişmez
            }
        }
    }

    /**
     * Bildirim denendi: en az bir kanal teslim ettiyse {@code ALERT|REMIND|RECOVER}, hiçbiri edemediyse {@code *_FAILED}
     * (yeniden denenir; iyileşme bildirimi yeniden denenmez — sorun zaten bitti).
     */
    public static void markNotified(WatchdogState state, Action action, boolean delivered, Instant now) {
        state.setNotifiedAt(ISO.format(now));
        state.setNotifiedKind(action.name() + (delivered ? "" : "_FAILED"));
        if (action == Action.RECOVER) state.setSeverity(null);
    }

    static Instant parse(String iso) {
        try {
            return LocalDateTime.parse(iso.length() > 19 ? iso.substring(0, 19) : iso).toInstant(ZoneOffset.UTC);
        } catch (Exception e) {
            return Instant.EPOCH;
        }
    }
}
