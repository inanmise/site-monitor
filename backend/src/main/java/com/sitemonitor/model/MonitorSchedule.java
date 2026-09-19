package com.sitemonitor.model;

/**
 * Zamanlanmış izlemelerin (9 tür) ortak çizelge görünümü — "Sizin için — bugün" panelinin
 * "sessiz / bayat izleme" kartı (2026-09-19) her türü ayrı ayrı tanımak zorunda kalmadan
 * aktif/aralık/son-kontrol karşılaştırması yapar. Getter'ları Lombok üretir; türe özgü olan
 * yalnız {@link #scheduleType()} (activity_log tür kodu) ve {@link #scheduleTarget()}.
 */
public interface MonitorSchedule {
    Long getId();
    String getName();
    Long getTeamId();
    Boolean getActive();
    Integer getIntervalSeconds();
    /** UTC ISO ({@code yyyy-MM-dd'T'HH:mm:ss}) — yeni yaratılan izleme ilk kontrolünü beklerken "bayat" sayılmasın. */
    String getCreatedAt();

    /** {@code ActivityLogService} tür kodu (HTTP, PORT, PING, DNS, KEYWORD, PAGE, PAGESPEED, SCRIPTED, DOMAIN). */
    String scheduleType();

    /** Kart satırında ad yanında gösterilen hedef (URL / host / alan adı); Sentetik'te yok → null. */
    String scheduleTarget();

    /**
     * Çift kaynaklı DNS/Port izlemeleri için: {@code standalone != true} satır ENVANTER-türevidir — alanı
     * aktif envanterde değilse süpürme onu BİLEREK atlar (öksüz) ve liste uçları göstermez; bayat sayılmaz.
     * Diğer yedi tür envanterden bağımsız → her zaman true.
     */
    default boolean scheduleStandalone() { return true; }
}
