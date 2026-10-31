package com.sitemonitor.service.cache;

import java.util.List;

/**
 * Önbellek veri yolu ({@link CacheBus}) adları — Spring önbellekleri dışındaki bellek-içi görüntüler (2026-10-22, çok pod
 * R2-A, MP-30). Spring önbellekleri ({@code cert-latest}, {@code card-extras}, {@code today-monitors} …) kendi adlarıyla
 * yolda yer alır ({@code BusAwareCacheManager}); burada yalnız elle tutulan bellekler var.
 *
 * <p><b>Adlar KALICIDIR</b> (çok pod planı G6): {@code cache_bus} satırının birincil anahtarıdır; yeniden adlandırmak
 * yuvarlanan dağıtımda eski ve yeni pod'un birbirini duymamasına yol açar. Yeni bir bellek yeni bir ad alır.
 */
public final class CacheBusNames {

    private CacheBusNames() { }

    /** Zayıf algoritma raporu (istisna ekle / kaldır) — {@code WeakAlgorithmReportService}. */
    public static final String WEAK_ALGO = "weak-algo";
    /** Kanal teknik hata analizi — {@code TechErrorAnalyticsService.invalidate}. */
    public static final String TECH_ERROR_ANALYTICS = "te-analytics";
    /** Nöbet çizelgesi / yer değiştirme — {@code OnCallService}. */
    public static final String ONCALL = "oncall";
    /** Takım sessiz saatleri — {@code TeamQuietHoursService}. */
    public static final String QUIET_HOURS = "quiet-hours";
    /** Pasif alıcı süzgeci — {@code InactiveRecipientGuard}. */
    public static final String INACTIVE_RECIPIENTS = "inactive-recipients";
    /** 7/24 operatör takımları — {@code NocOperatorService}. */
    public static final String NOC_OPERATORS = "noc-operators";
    /** Takım erişim paylaşımları — {@code TeamAccessGrantService}. */
    public static final String TEAM_GRANTS = "team-grants";
    /** Bakım pencereleri (alarm bastırma) — {@code MaintenanceService}. */
    public static final String MAINTENANCE_WINDOWS = "maintenance-windows";
    /** Sistem bakımı — {@code SystemMaintenanceService}. */
    public static final String SYSTEM_MAINTENANCE = "system-maintenance";
    /** Pasif / silinmiş hesap kapısı + tek-oturum (süpersede) önbelleği — {@code UserService}. */
    public static final String USER_GATE = "user-gate";
    /** Tatil / özel gün takvimi (veritabanı sorgu izlemesi "tatillerde çalışma", 2026-10-31) — {@code HolidayCalendarService}. */
    public static final String HOLIDAYS = "holidays";

    /** Hepsi (kapı testi: biçim + tekillik). */
    public static final List<String> ALL = List.of(WEAK_ALGO, TECH_ERROR_ANALYTICS, ONCALL, QUIET_HOURS, INACTIVE_RECIPIENTS,
            NOC_OPERATORS, TEAM_GRANTS, MAINTENANCE_WINDOWS, SYSTEM_MAINTENANCE, USER_GATE, HOLIDAYS);
}
