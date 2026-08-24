package com.sitemonitor.service;

import com.sitemonitor.model.AppUser;
import com.sitemonitor.model.AuditLog;
import com.sitemonitor.model.RememberMeToken;
import com.sitemonitor.repository.AuditLogRepository;
import com.sitemonitor.repository.RememberMeTokenRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * "Cihaz Geçmişi / Oturum Güvenliği" ekranının okuma tarafı.
 *
 * <p><b>Neden ayrı servis:</b> aynı DTO'yu iki uç üretiyor — kullanıcının kendi ekranı
 * ({@code /api/me/devices}, self-scope) ve adminin salt-okunur görünümü. Mantık tek yerde
 * durmazsa iki uç zamanla birbirinden sapar ve "admin ne görüyor" sorusu cevapsız kalır.
 *
 * <p><b>Gizlilik sözleşmesi:</b> ürettiği hiçbir DTO oturum kimliği, token değeri ya da token
 * hash'i TAŞIMAZ — yalnız opak satır id'leri ve cihaz meta'sı. Entity'leri doğrudan
 * serileştirmemenin asıl sebebi budur.
 *
 * <p><b>Tek-aktif-oturum gerçeği:</b> SiteMonitor'de kullanıcının aynı anda TEK canlı oturumu
 * olur ve her login eski remember-me token'larını iptal eder. Dolayısıyla "hatırlanan cihaz"
 * listesi en fazla BİR satırdır; ekranın asıl değeri giriş GEÇMİŞİ'ndedir.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DeviceHistoryService {

    private final RememberMeTokenRepository rememberRepo;
    private final AuditLogRepository auditLogRepo;
    private final GeoIpService geoIpService;
    private final AppSettingsService appSettings;

    /** Kurumsal ağdan gelen istekte şehir/ülke çözülemez; satır bozuk görünmesin diye açık etiket. */
    public static final String CORPORATE_NETWORK = "CORPORATE_NETWORK";

    // ── Bu cihaz + hatırlanan cihazlar ───────────────────────────────────────

    /**
     * @param user             kaydı okunacak kullanıcı (self ya da admin görünümünde hedef)
     * @param currentTokenHash isteği yapan tarayıcının remember-me token HASH'i (yoksa null) —
     *                         yalnız "bu cihaz mı" işaretlemesi için; yanıta ASLA yazılmaz
     */
    public Map<String, Object> devicesFor(AppUser user, String currentTokenHash) {
        String actor = user.getUsername() == null ? "" : user.getUsername().toLowerCase();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("current", currentDevice(user, actor, currentTokenHash != null));
        out.put("remembered", rememberedDevices(user.getUsername(), currentTokenHash));
        out.put("retention_days", auditRetentionDays());
        return out;
    }

    private Map<String, Object> currentDevice(AppUser user, String actor, boolean remembered) {
        Map<String, Object> m = new LinkedHashMap<>();
        // Grandfathered kullanıcıda (activeSessionId = null) bu alanlar boş kalır; ekran çökmez,
        // "bilgi yok" gösterir. Kicked/terminated ayrımı BURADA yapılmaz — bu ekran tek-oturum
        // sözleşmesinin ÜZERİNE okuma koyar, onu yeniden yorumlamaz.
        m.put("last_seen_at", user.getLastSeenAt());
        m.put("remembered_on_this_device", remembered);

        Optional<AuditLog> latest = auditLogRepo.findLatestOwnLogin(actor);
        if (latest.isEmpty()) {
            m.put("known", false);
            return m;
        }
        AuditLog a = latest.get();
        m.put("known", true);
        m.put("login_at", a.getEventTime());
        m.put("ip", a.getIpAddress());
        m.put("ua_summary", UserAgentSummary.labelOf(a.getUserAgent()));
        m.put("device", UserAgentSummary.of(a.getUserAgent()).device());
        putLocation(m, a.getIpAddress(), a.getIpCity(), a.getIpCountry());
        return m;
    }

    private List<Map<String, Object>> rememberedDevices(String canonicalUsername, String currentTokenHash) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (RememberMeToken t : rememberRepo.findByUsername(canonicalUsername)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", t.getId());                         // OPAK satır id'si — token DEĞİL
            m.put("ua_summary", t.getUaSummary());
            m.put("ip", t.getIpAddress());
            m.put("created_at", t.getCreatedAt());
            m.put("last_used_at", t.getLastUsedAt());
            m.put("expires_at", t.getExpiresAt());
            // Karşılaştırma HASH üzerinden; hiçbir token değeri/hash'i yanıta girmez.
            m.put("is_this_device", currentTokenHash != null && currentTokenHash.equals(t.getToken()));
            putLocation(m, t.getIpAddress(), t.getIpCity(), t.getIpCountry());
            rows.add(m);
        }
        return rows;
    }

    // ── Giriş geçmişi ────────────────────────────────────────────────────────

    /** @param failed true → yalnız başarısız denemeler (LOGIN_FAILED), false → başarılı girişler */
    public Map<String, Object> loginsFor(AppUser user, boolean failed, int page, int size) {
        String actor = user.getUsername() == null ? "" : user.getUsername().toLowerCase();
        int sz = Math.max(1, Math.min(size, 200));
        Page<AuditLog> result = auditLogRepo.findOwnLogins(
                actor, failed ? "LOGIN_FAILED" : "LOGIN", PageRequest.of(Math.max(0, page), sz));

        List<Map<String, Object>> rows = new ArrayList<>(result.getNumberOfElements());
        for (AuditLog a : result.getContent()) rows.add(loginRow(a));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("rows", rows);
        out.put("total", result.getTotalElements());
        out.put("page", result.getNumber());
        out.put("total_pages", result.getTotalPages());
        out.put("retention_days", auditRetentionDays());
        return out;
    }

    private Map<String, Object> loginRow(AuditLog a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", a.getId());
        m.put("at", a.getEventTime());
        m.put("ua_summary", UserAgentSummary.labelOf(a.getUserAgent()));
        m.put("device", UserAgentSummary.of(a.getUserAgent()).device());
        // Ham UA yalnız satır GENİŞLETMESİ için; listede asla dökülmez.
        m.put("ua_raw", a.getUserAgent());
        m.put("ip", a.getIpAddress());
        m.put("org", a.getIpOrg());
        m.put("outcome", a.getOutcome());
        m.put("failure_reason", a.getFailureReason());
        m.put("anomaly_flags", splitFlags(a.getAnomalyFlags()));
        putLocation(m, a.getIpAddress(), a.getIpCity(), a.getIpCountry());
        return m;
    }

    // ── Yardımcılar ──────────────────────────────────────────────────────────

    /**
     * Konum alanları. Private/RFC1918 IP'de geo çözülemez — satırı boş bırakmak yerine açık bir
     * etiket veriyoruz, yoksa kurum ağından giren herkes "konum bilinmiyor" görürdü.
     */
    private void putLocation(Map<String, Object> m, String ip, String city, String country) {
        boolean priv = ip != null && !ip.isBlank() && geoIpService.isPrivateIp(ip);
        m.put("city", priv ? null : city);
        m.put("country", priv ? null : country);
        m.put("location_kind", priv ? CORPORATE_NETWORK : null);
    }

    private static List<String> splitFlags(String csv) {
        if (csv == null || csv.isBlank()) return List.of();
        List<String> out = new ArrayList<>();
        for (String s : csv.split(",")) {
            String v = s.trim();
            if (!v.isEmpty()) out.add(v);
        }
        return out;
    }

    /** Giriş geçmişinin ufku audit retention'ıdır — arayüz bunu kullanıcıya söyler. */
    private int auditRetentionDays() {
        return appSettings.getInt("site.monitor.audit.retention-days", 365);
    }
}
