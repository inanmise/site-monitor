package com.sitemonitor.service;

import java.util.List;
import java.util.Set;

/**
 * Denetim olay türlerinin KANONİK kataloğu — "hangi olay türleri var" sorusunun tek cevabı.
 *
 * <p><b>Neden gerekti.</b> Tür adları koda dağılmıştı ve hiçbir yerde toplanmıyordu. Üç somut
 * sonucu vardı:
 * <ul>
 *   <li>Arayüzdeki filtre listesi elle tutuluyordu ve <b>sürüklenmişti</b>: 158 türün yalnız
 *       32'si seçilebiliyordu — bütün {@code MAINTENANCE_*}, {@code SQL_EXECUTE},
 *       {@code MONITOR_TRIGGER} ve 12 {@code WEEKLY_REPORT_*} türü filtrede hiç yoktu.</li>
 *   <li>Yeni bir tür eklendiğinde TR/EN etiketi eklemeyi hatırlatan hiçbir şey yoktu; ekranda
 *       ham {@code SNAKE_CASE} beliriyordu.</li>
 *   <li>Aynı olayın iki farklı adla yazılması (ya da bir yazım hatası) hiçbir yerde
 *       yakalanmıyordu.</li>
 * </ul>
 *
 * <p><b>Kategori kuralla türetilir, elle yazılmaz.</b> 158 satırlık ikinci bir eşleme tablosu
 * tutmak, ilk yazım hatasında sessizce yanlış gruplayan bir tabloya dönüşürdü. Kural sırası
 * ÖNEMLİDİR: daha özel önek daha genel olandan ÖNCE gelir ({@code USER_PUSH_*} entegrasyondur,
 * {@code USER_*} kullanıcı yönetimi).
 *
 * <p><b>Kapı:</b> {@code AuditCoverageTest} kodda geçen her olay türü literalinin burada olmasını
 * şart koşar — liste kendiliğinden eskimez.
 */
public final class AuditEventCatalog {

    private AuditEventCatalog() {}

    /** Bir olay türü ve ait olduğu kategori (arayüzde gruplu filtre için). */
    public record Event(String type, String category) {}

    public static final String AUTH = "AUTH";
    public static final String SECURITY = "SECURITY";
    public static final String USER = "USER";
    public static final String TEAM = "TEAM";
    public static final String PERMISSION = "PERMISSION";
    public static final String MONITOR = "MONITOR";
    public static final String CERTIFICATE = "CERTIFICATE";
    public static final String INCIDENT = "INCIDENT";
    public static final String MAINTENANCE = "MAINTENANCE";
    public static final String REPORT = "REPORT";
    public static final String SETTINGS = "SETTINGS";
    public static final String INTEGRATION = "INTEGRATION";
    public static final String DATA = "DATA";
    public static final String SYSTEM = "SYSTEM";

    /** Arayüzdeki grup sırası — güvenlikle ilgili olanlar üstte. */
    public static final List<String> CATEGORY_ORDER = List.of(
            AUTH, SECURITY, PERMISSION, USER, TEAM, MONITOR, CERTIFICATE, INCIDENT,
            MAINTENANCE, REPORT, SETTINGS, INTEGRATION, DATA, SYSTEM);

    /**
     * Kodda YAZILAN bütün olay türleri (alfabetik).
     *
     * <p>Anahtar sözcükle üretilen aileler de ({@code DOMAIN_BULK_*}, {@code ALERT_BULK_*})
     * burada AÇIKÇA sayılır: onlar {@code switch} kollarında doğuyor, bir literal taraması
     * tarafından bulunamaz ama kullanıcı filtrede görmek zorundadır.
     */
    public static final List<String> TYPES = List.of(
            "ACCESS_DENIED",
            "ACCOUNT_LOCKED",
            "ALERT_ACKNOWLEDGE",
            "ALERT_BULK_ACKNOWLEDGE",
            "ALERT_BULK_RENOTIFY",
            "ALERT_BULK_RESOLVE",
            "ALERT_DELETE",
            "ALERT_RENOTIFY",
            "ALERT_RESOLVE",
            "AUDIT_EXPORT",
            "AUDIT_RETENTION_PURGE",
            "AUTH_REQUIRED",
            "BRANDING_SAVE",
            "CA_PINNED",
            "CA_ROTATED",
            "CERT_HEALTH_DENIED",
            "CERT_HEALTH_REFRESH",
            "CERT_INVENTORY_REPORT_RUN",
            "CERT_INVENTORY_REPORT_SETTINGS",
            "CERT_INVENTORY_REPORT_TEST",
            "CERT_LIST_EXPORT",
            "CERT_NOTE_ADD",
            "CERT_NOTE_DELETE",
            "CERT_NOTE_EDIT",
            "CERT_NOTE_RESTORE",
            "CERT_RENEWAL_CONFIRMED",
            "CERT_RENEWAL_PLANNED",
            "CERT_RENEWAL_PLAN_CLEARED",
            "CHANGE_LOG_DENIED",
            "CLIENT_ERROR_REPORT",
            "CONTACT_CREATE",
            "CONTACT_DELETE",
            "CONTACT_UPDATE",
            "DIAGNOSTICS_DOMAIN_EXPIRY",
            "DIAGNOSTICS_HSTS",
            "DIAGNOSTICS_NETWORK",
            "DIAGNOSTICS_OPENSSL",
            "DIAGNOSTICS_PROXY_CA_CHAIN",
            "DIAGNOSTICS_RUN",
            "DOMAIN_ADD",
            "DOMAIN_AUTO_PURGE",
            "DOMAIN_BULK_ACTIVATE",
            "DOMAIN_BULK_DEACTIVATE",
            "DOMAIN_BULK_DELETE",
            "DOMAIN_BULK_PURGE",
            "DOMAIN_BULK_SET_CONTACTS",
            "DOMAIN_BULK_SET_TEAM",
            "DOMAIN_BULK_SET_TIER",
            "DOMAIN_DELETE_CHECK",
            "DOMAIN_EDIT",
            "DOMAIN_IMPORT",
            "DOMAIN_PURGE",
            "DOMAIN_RESTORE",
            "DOMAIN_SOFT_DELETE",
            "DOMAIN_TRANSFER_SY",
            "DOMAIN_TRANSFER_UG",
            "GENERAL_SETTINGS_SAVE",
            "GUIDE_LINK_CREATE",
            "GUIDE_LINK_DELETE",
            "GUIDE_LINK_UPDATE",
            "INCIDENT_COMMENT_ADD",
            "INCIDENT_COMMENT_DELETE",
            "INCIDENT_CREATE",
            "INCIDENT_DELETE",
            "INCIDENT_IMAGE_ADD",
            "INCIDENT_OPTION_ADD",
            "INCIDENT_OPTION_DELETE",
            "INCIDENT_TRANSFER",
            "INCIDENT_UPDATE",
            "ISSUE_REPORT",
            "LDAP_QUERY_USER",
            "LDAP_SETTINGS_SAVE",
            "LDAP_TEST",
            "LOGIN",
            "LOGIN_ANOMALY_ACK",
            "LOGIN_ANOMALY_ALERT",
            "LOGIN_ANOMALY_RESOLVED",
            "LOGIN_ANOMALY_SETTINGS_SAVE",
            "LOGIN_ANOMALY_TEST_EMAIL",
            "LOGIN_DISPUTED",
            "LOGIN_FAILED",
            "LOGIN_HELP_REPORT",
            "LOGIN_ISSUE_PURGE",
            "LOGIN_ISSUE_STATUS_CHANGE",
            "LOGOUT",
            "MAINTENANCE_CREATE",
            "MAINTENANCE_DELETE",
            "MAINTENANCE_PAUSE",
            "MAINTENANCE_QUICK",
            "MAINTENANCE_RESUME",
            "MAINTENANCE_UPDATE",
            "MONITOR_CREATE",
            "MONITOR_DELETE",
            "MONITOR_DIAGNOSE",
            "MONITOR_GROUP_RENAME",
            "MONITOR_GUIDE_SAVE",
            "MONITOR_NOTE_ADD",
            "MONITOR_NOTE_DELETE",
            "MONITOR_NOTE_EDIT",
            "MONITOR_RESTORE",
            "MONITOR_TEST",
            "MONITOR_TRIGGER",
            "MONITOR_UPDATE",
            "NOTIFICATION_GROUP_CREATE",
            "NOTIFICATION_GROUP_DEFAULT",
            "NOTIFICATION_GROUP_DELETE",
            "NOTIFICATION_GROUP_REASSIGN",
            "NOTIFICATION_GROUP_UPDATE",
            "PERMISSION_RESET",
            "PERMISSION_UPDATE",
            "REMEMBER_TOKEN_REVOKE",
            "RETENTION_APPROVAL_SAVE",
            "RETENTION_DRY_RUN",
            "RETENTION_HOLD_ACTIVE",
            "RETENTION_POLICY_CHANGE",
            "RETENTION_ROLLUP_BACKFILL",
            "RETENTION_RUN_MANUAL",
            "RETENTION_SETTINGS_SAVE",
            "RETENTION_SETTINGS_SHORTENED",
            "SCHEDULER_LOCK_RELEASE",
            "SCHEDULER_RUN",
            "SCHEMA_PATCH",
            "SCRIPTED_DRAFT_DELETE",
            "SCRIPTED_DRAFT_SAVE",
            "SECRET_DECRYPT",
            "SELF_PASSWORD_CHANGE",
            "SESSION_REVOKE_ALL",
            "SESSION_TERMINATE",
            "SMTP_RESEND",
            "SMTP_SETTINGS_SAVE",
            "SMTP_TEST",
            "SMTP_TEST_EMAIL",
            "SQL_EXECUTE",
            "STORM_SETTINGS_SAVE",
            "SYSTEM_DEPLOYMENT_BACKFILL",
            "SYSTEM_DEPLOYMENT_DELETE",
            "SYSTEM_DEPLOYMENT_EXPORT",
            "SYSTEM_DEPLOYMENT_MANUAL",
            "SYSTEM_SHUTDOWN",
            "SYSTEM_STARTUP",
            "TEAM_CREATE",
            "TEAM_DELETE",
            "TEAM_UPDATE",
            "TEAM_WEEKLY_NOTIFICATIONS",
            "TEMPLATE_CREATE",
            "TEMPLATE_DELETE",
            "TEMPLATE_DEMOTE",
            "TEMPLATE_PROMOTE",
            "TEMPLATE_UPDATE",
            "THRESHOLD_CREATE",
            "THRESHOLD_UPDATE",
            "TOUR_COMPLETED",
            "TOUR_DISMISSED",
            "USER_CREATE",
            "USER_DELETE",
            "USER_ORG_ROLE_UNLOCK",
            "USER_PASSWORD_AUTO_RESET",
            "USER_PUSH_EXPORT",
            "USER_PUSH_OPT_OUT",
            "USER_PUSH_SCOPES",
            "USER_PUSH_SETTINGS",
            "USER_PUSH_TEST",
            "USER_ROLE_UNLOCK",
            "USER_TEAM_UNLOCK",
            "USER_TOUR_RESET",
            "USER_UNLOCK",
            "USER_UPDATE",
            "WEAK_ALGO_EXCEPTION_CLEAR",
            "WEAK_ALGO_EXCEPTION_SET",
            "WEAK_ALGO_EXPORT",
            "WEAK_ALGO_NOTIFY",
            "WEEKLY_AVAILABILITY_RUN",
            "WEEKLY_AVAILABILITY_TEST",
            "WEEKLY_AVAILABILITY_TOGGLE",
            "WEEKLY_REPORT_APPROVE",
            "WEEKLY_REPORT_ACCESS",
            "WEEKLY_REPORT_COMMENT",
            "WEEKLY_REPORT_CREATE",
            "WEEKLY_REPORT_DELETE",
            "WEEKLY_REPORT_IMAGE_ADD",
            "WEEKLY_REPORT_IMAGE_DELETE",
            "WEEKLY_REPORT_REJECT",
            "WEEKLY_REPORT_REMINDER_TRIGGER",
            "WEEKLY_REPORT_REOPEN",
            "WEEKLY_REPORT_RESEND",
            "WEEKLY_REPORT_SAVE",
            "WEEKLY_REPORT_SUBMIT",
            "WEEKLY_REPORT_TRANSFER"
    );

    private static final Set<String> TYPE_SET = Set.copyOf(TYPES);

    /** Katalogda mı (kapı testi ve arayüz yedeği için). */
    public static boolean contains(String type) {
        return type != null && TYPE_SET.contains(type);
    }

    /**
     * Tür → kategori. Bilinmeyen tür {@link #SYSTEM}'e değil {@code "OTHER"}'a düşer: bilinmeyeni
     * bilinen bir kovaya koymak, katalogdan düşmüş bir türü fark etmeyi imkânsızlaştırırdı.
     */
    public static String categoryOf(String type) {
        if (type == null || type.isBlank()) return "OTHER";
        String t = type.toUpperCase(java.util.Locale.ROOT);

        // Güvenlik reddi her şeyden önce gelir: "*_DENIED" bir izleme olayı değil, bir yetki olayıdır.
        if (t.equals("ACCESS_DENIED") || t.equals("AUTH_REQUIRED") || t.endsWith("_DENIED")) return SECURITY;
        if (t.equals("SECRET_DECRYPT")) return SECURITY;

        if (t.startsWith("LOGIN") || t.equals("LOGOUT") || t.equals("ACCOUNT_LOCKED")
                || t.startsWith("SESSION_") || t.startsWith("REMEMBER_TOKEN")
                || t.equals("SELF_PASSWORD_CHANGE")) return AUTH;

        if (t.startsWith("PERMISSION_")) return PERMISSION;
        if (t.startsWith("TOUR_")) return USER;                     // ürün turu: kişinin kendi tercihi
        if (t.startsWith("USER_PUSH_")) return INTEGRATION;          // USER_* ten ÖNCE
        if (t.startsWith("USER_")) return USER;
        if (t.startsWith("TEAM_")) return TEAM;

        if (t.startsWith("CERT_INVENTORY_REPORT")) return REPORT;    // CERT_* ten ÖNCE
        if (t.startsWith("WEEKLY_")) return REPORT;
        if (t.startsWith("WEAK_ALGO_")) return CERTIFICATE;         // _EXPORT sonekinden ÖNCE: sertifika raporu

        if (t.startsWith("MAINTENANCE_")) return MAINTENANCE;
        if (t.startsWith("MONITOR_") || t.startsWith("THRESHOLD_")
                || t.startsWith("SCRIPTED_DRAFT")) return MONITOR;
        if (t.startsWith("CERT_") || t.startsWith("DOMAIN_") || t.startsWith("DIAGNOSTICS_")) return CERTIFICATE;
        if (t.startsWith("INCIDENT_") || t.startsWith("ALERT_")) return INCIDENT;

        if (t.startsWith("CA_")) return INTEGRATION;
        if (t.startsWith("SMTP_") || t.startsWith("LDAP_") || t.startsWith("NOTIFICATION_GROUP_")
                || t.startsWith("CONTACT_")) return INTEGRATION;

        if (t.startsWith("AUDIT_") || t.equals("SQL_EXECUTE") || t.endsWith("_EXPORT")
                || t.startsWith("RETENTION_")) return DATA;

        if (t.startsWith("SYSTEM_") || t.startsWith("SCHEDULER_") || t.equals("SCHEMA_PATCH")
                || t.equals("CLIENT_ERROR_REPORT") || t.equals("ISSUE_REPORT")) return SYSTEM;

        // Kalan her şey bir AYAR yüzeyidir (settings/branding/storm/guide-link/template…).
        if (t.startsWith("GENERAL_") || t.startsWith("BRANDING_") || t.startsWith("STORM_")
                || t.startsWith("GUIDE_LINK_") || t.startsWith("TEMPLATE_")
                || t.contains("SETTINGS")) return SETTINGS;

        return "OTHER";
    }

    /** Katalogun tamamı, kategori bilgisiyle (arayüz filtresi bunu okur). */
    public static List<Event> all() {
        return TYPES.stream().map(t -> new Event(t, categoryOf(t))).toList();
    }
}
