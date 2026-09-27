package com.sitemonitor.service.noc;

import com.sitemonitor.service.ActivityLogService;
import com.sitemonitor.service.EscalationService;
import com.sitemonitor.service.MonitorHistoryService;

import java.util.Locale;
import java.util.Set;

/**
 * 7/24 bildirimi taşıyan on izleme türü — sözleşmedeki tür anahtarları ({@code SSL}, {@code PING}, …).
 *
 * <p>Her tür, NOC akışının ihtiyaç duyduğu bütün eşlemeleri TEK yerde taşır: tablo, arayüz sekmesi (derin
 * bağlantı), etkinlik günlüğü türü, değişiklik geçmişi türü, denetim kaynağı ve yazma izni. Bir eşlemenin
 * türlerden birinde unutulması (projede tekrar eden "9'un 8'i bağlandı" kusuru) böylece derleme hatasıdır.
 */
public enum NocType {

    SSL("certificate_inventory", "dashboard", ActivityLogService.CERT, MonitorHistoryService.INVENTORY,
            "CERTIFICATE", "inventory.crud", "SSL / Sertifika"),
    PING("ping_monitors", "ping", ActivityLogService.PING, MonitorHistoryService.PING,
            "PING_MONITOR", "monitoring.crud", "Ping"),
    HTTP("http_monitors", "http", ActivityLogService.HTTP, MonitorHistoryService.HTTP,
            "HTTP_MONITOR", "monitoring.crud", "HTTP / Web"),
    KEYWORD("keyword_monitors", "keyword", ActivityLogService.KEYWORD, MonitorHistoryService.KEYWORD,
            "KEYWORD_MONITOR", "monitoring.crud", "Anahtar Kelime"),
    PAGE("page_monitors", "page", ActivityLogService.PAGE, MonitorHistoryService.PAGE,
            "PAGE_MONITOR", "monitoring.crud", "Sayfa Bütünlüğü"),
    PAGESPEED("pagespeed_monitors", "pagespeed", ActivityLogService.PAGESPEED, MonitorHistoryService.PAGESPEED,
            "PAGESPEED_MONITOR", "monitoring.crud", "Sayfa Hızı"),
    SCRIPTED("scripted_monitors", "scripted", ActivityLogService.SCRIPTED, MonitorHistoryService.SCRIPTED,
            // Sentetik izlemenin KENDİ güncelleme ucu monitoring.scripted/edit ister (k6 = keyfi kod; ADMIN + TEAM_ADMIN).
            "SCRIPTED_MONITOR", "monitoring.scripted", "Sentetik Senaryo"),
    DNS("dns_monitors", "dns", ActivityLogService.DNS, MonitorHistoryService.DNS,
            "DNS_MONITOR", "monitoring.crud", "DNS"),
    PORT("port_monitors", "port", ActivityLogService.PORT, MonitorHistoryService.PORT,
            "PORT_MONITOR", "monitoring.crud", "Port"),
    DOMAIN("domain_monitors", "domain", ActivityLogService.DOMAIN, MonitorHistoryService.DOMAIN,
            "DOMAIN_MONITOR", "monitoring.crud", "Alan Adı");

    public final String table;
    /** Arayüz sekmesi — {@code ?tab=<sekme>&monitor=<id>} (SSL: {@code &domain=}). */
    public final String tab;
    public final String activityType;
    public final String historyKind;
    public final String auditResource;
    /** Yazma izni — izlemenin KENDİ güncelleme ucunun istediği matris anahtarı ({@code edit}). */
    public final String permission;
    public final String labelTr;

    NocType(String table, String tab, String activityType, String historyKind, String auditResource,
            String permission, String labelTr) {
        this.table = table;
        this.tab = tab;
        this.activityType = activityType;
        this.historyKind = historyKind;
        this.auditResource = auditResource;
        this.permission = permission;
        this.labelTr = labelTr;
    }

    /** "ping" / "PING" → PING; bilinmeyen → null. */
    public static NocType parse(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static final Set<String> CERT_TYPES = Set.of("EXPIRY", "CHAIN_BROKEN", "REVOKED", "MISMATCH",
            EscalationService.TYPE_HOSTNAME_MISMATCH, EscalationService.TYPE_UNTRUSTED_CA);

    /**
     * Alarm türü → sahibi izleme türü. Sıra önemli: {@code PAGESPEED_} {@code PAGE_}'ten, {@code DOMAINMON_}
     * genel {@code DOMAIN_EXPIRY}'den (HTTP izlemesinin alan adı alarmı) önce gelir. Bilinmeyen → null
     * (NOC'a hiçbir şey gitmez — tahminle yanlış takım/izleme adına e-posta atmaktansa sessiz kalmak).
     */
    public static NocType forAlertType(String t) {
        if (t == null) return null;
        if (t.startsWith("PAGESPEED_")) return PAGESPEED;
        if (t.startsWith("PAGE_")) return PAGE;
        if (t.startsWith("SCRIPTED_")) return SCRIPTED;
        if (t.startsWith("PORT_")) return PORT;
        if (t.startsWith("DNS_")) return DNS;
        if (t.equals(EscalationService.TYPE_KEYWORD) || t.startsWith("KEYWORD_")) return KEYWORD;
        if (t.startsWith("PING_")) return PING;
        if (t.startsWith("DOMAINMON_")) return DOMAIN;
        if (t.equals(EscalationService.TYPE_HTTP_DOWN) || t.equals(EscalationService.TYPE_HTTP_SSL)
                || t.equals(EscalationService.TYPE_DOMAIN_EXPIRY)) return HTTP;
        if (t.equals(EscalationService.TYPE_ACCESSIBILITY) || CERT_TYPES.contains(t)) return SSL;
        return null;
    }
}
