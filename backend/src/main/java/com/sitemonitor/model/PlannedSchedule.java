package com.sitemonitor.model;

/**
 * Zamanlama penceresi / cron kolonları olan izleme (2026-11-15): sentetik izleme (k6r D2) ve Sayfa Hızı (2. sürüm madde 14).
 * Kolon adları ve anlamları {@code DbQuerySchedule}'ündür; hesap ve gövde / yanıt çevirisi {@code ScriptedSchedule}'dadır
 * (iki tür AYNI kodu kullanır). Lombok {@code @Data} erişimcileri bu arayüzü karşılar.
 */
public interface PlannedSchedule {
    String getScheduleMode();
    void setScheduleMode(String v);
    Integer getIntervalSeconds();
    void setIntervalSeconds(Integer v);
    String getCronExpression();
    void setCronExpression(String v);
    String getWindowsJson();
    void setWindowsJson(String v);
    String getBlackoutJson();
    void setBlackoutJson(String v);
    Boolean getSkipHolidays();
    void setSkipHolidays(Boolean v);
    String getSkipDatesJson();
    void setSkipDatesJson(String v);
    /** Zamanlayıcının planı (yalnız hedefli UPDATE yazar). */
    String getNextRunAt();
    /** Oluşturulma anı (UTC ISO) — "Bugün" panelinin gecikme kuralı. */
    String getCreatedAt();
}
