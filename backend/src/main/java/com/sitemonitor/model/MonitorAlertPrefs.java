package com.sitemonitor.model;

/**
 * İzleme alarm tercihleri (2026-09-19): dokuz izleme türünün ortak kesiti — bildirim kanalları ve
 * ALARM SEVİYESİ. Sweep bağlamı ({@code SchedulerService.chanCtx}) buradan doldurulur.
 *
 * <p>Alarm seviyesi ürün kararı: süre-bitişi (sertifika / alan adı kaydı) alarmları GÜN eşiğiyle
 * kademelenir; onun dışındaki HER izleme alarmı (erişilemiyor, yavaş, değişti, anahtar kelime, sayfa,
 * sentetik …) varsayılan <b>WARNING</b> ile açılır. Kullanıcı izlemeyi düzenleyip HIGH/CRITICAL
 * seçerse alarm o seviyede açılır ve eskalasyon kontakları (seviye eşiğine göre) alıcıya eklenir.
 * {@code null} = WARNING.
 */
public interface MonitorAlertPrefs {
    String LEVEL_WARNING = "WARNING", LEVEL_HIGH = "HIGH", LEVEL_CRITICAL = "CRITICAL";
    java.util.List<String> LEVELS = java.util.List.of(LEVEL_WARNING, LEVEL_HIGH, LEVEL_CRITICAL);

    Boolean getNotifyEmail();
    Boolean getNotifyWebhook();
    String getAlertLevel();

    /** Geçerli seviye ya da WARNING (null / bilinmeyen değer). */
    static String effectiveLevel(String level) {
        return level != null && LEVELS.contains(level) ? level : LEVEL_WARNING;
    }

    /** Gövdeden gelen değeri normalize eder: boş → null (WARNING), bilinmeyen → null. */
    static String normalize(Object raw) {
        if (raw == null) return null;
        String v = String.valueOf(raw).trim().toUpperCase(java.util.Locale.ROOT);
        return LEVELS.contains(v) ? (LEVEL_WARNING.equals(v) ? null : v) : null;
    }
}
