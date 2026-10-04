package com.sitemonitor.controller;

import com.sitemonitor.repository.AlertEventRepository;
import com.sitemonitor.repository.AppUserRepository;
import com.sitemonitor.repository.AuditLogRepository;
import com.sitemonitor.repository.TeamRepository;
import com.sitemonitor.service.AppSettingsService;
import com.sitemonitor.service.MonitoringOverviewService;
import com.sitemonitor.service.PresenceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * Giriş sayfası istatistikleri — PUBLIC (auth YOK; AuthInterceptor.PUBLIC). HTTP yanıtı diğer /api uçları gibi
 * no-store (2026-09-10: eski "public, max-age=60" NetScaler'da bayat kopya bırakıyordu); yük hafifletmesi aşağıdaki
 * sunucu-içi bellektir, pod yeniden başlayınca sıfırdan dolar.
 *
 * <p><b>Kullanım istatistikleri (2026-10-04, kullanıcı bildirimi).</b> İzleme Panosu 688 sağlıklı izleme gösterirken
 * giriş sayfası 621 "izleme" gösteriyordu: buradaki sayı fırtına paydasıydı ({@code StormService.totalActiveMonitors}
 * — sertifika envanteri + bağımsız izlemeler, envanter-türevi Port/DNS hariç), pano ise dokuz izleme türünü sayıyor.
 * İki farklı tanım. Artık izleme rakamları panonun KENDİ hesabından gelir ({@link MonitoringOverviewService#orgSummary},
 * global görüntüleyicinin pano belleğiyle aynı anahtar); fırtına paydası DEĞİŞMEDİ (eşikler ona bağlı). Alanlar
 * (hepsi kuruluş geneli TOPLAM sayı — ad, alan adı, takım adı, kullanıcı ya da ayrıntı YOK):
 * <ul>
 *   <li>{@code healthy_monitors} = aktif − düşük − gecikmiş − hiç kontrol edilmemiş (pano "Sağlıklı" kutusu);
 *       {@code active_monitors}, {@code total_monitors} = pano "aktif" / "Toplam".</li>
 *   <li>{@code checks_24h} / {@code failed_checks_24h} = panonun 24 sa {@code checks_window}/{@code failed_window}'u:
 *       dokuz izleme türünün koşumları — sertifika taramaları DAHİL DEĞİL (pano "Koşum (24 sa)" kutusuyla aynı sayı).</li>
 *   <li>{@code alerts_24h} = son 24 sa'te açılan alarm (tüm türler, sertifika dahil) — tek COUNT.</li>
 *   <li>{@code teams} = aktif takım sayısı; {@code active_users} = aktif (pasif olmayan) kullanıcı hesabı sayısı.</li>
 *   <li>{@code online_users} = şu an SiteMonitor'ü açık tutan kullanıcı ({@link PresenceService}, çevrimiçi
 *       göstergesiyle aynı tanım); {@code logins_24h} = son 24 sa'te başarılı giriş yapan FARKLI kullanıcı.</li>
 *   <li>{@code monitored_targets} (eski alan, geriye uyum) = {@code active_monitors}; {@code availability_pct}
 *       değişmedi (7 gün uptime, bakım hariç).</li>
 * </ul>
 * Her rakam AYRI hesaplanır; biri hata verirse yalnız o {@code null} olur (arayüz "—"). Ayar
 * {@value #USAGE_KEY} (GLOBAL_ONLY, Marka sayfası) kapalıyken uç yalnız eski iki rakamı döner, arayüz şeridi gizler.
 *
 * <p><b>Bellek.</b> {@code site.monitor.public-stats.cache-ms} (60 sn — çevrimiçi sayısı değişir). Oturumsuz uç
 * olduğu için soğuk bellekte TEK hesap: aynı anda gelen istekler ya bayat kaydı alır ya da hesabı bekler (pano hesabı
 * ağırdır; açılış seli DB'ye N kez binmesin).
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class PublicStatsController {

    private static final DateTimeFormatter ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);

    /** Kullanım şeridi anahtarı (GLOBAL_ONLY, Marka sayfası) — kapalıyken yalnız {@link #LEGACY_FIELDS}. */
    public static final String USAGE_KEY = "site.monitor.public-stats.usage-enabled";
    /** Ayar kapalıyken dönen alanlar — 2026-10-04 öncesi yanıtın tamamı. */
    static final List<String> LEGACY_FIELDS = List.of("monitored_targets", "availability_pct");
    /** Kullanım şeridi alanları (sıra yanıt sırasıdır). */
    static final List<String> USAGE_FIELDS = List.of("healthy_monitors", "active_monitors", "total_monitors",
            "checks_24h", "failed_checks_24h", "alerts_24h", "teams", "active_users", "online_users", "logins_24h");

    /** Sunucu tarafı bellek penceresi (login açılışları DB'ye binmesin); testte 0'lanır. */
    @org.springframework.beans.factory.annotation.Value("${site.monitor.public-stats.cache-ms:60000}")
    long cacheMs = 60_000;

    private final JdbcTemplate jdbcTemplate;
    private final MonitoringOverviewService overviewService;
    private final AlertEventRepository alertEventRepo;
    private final TeamRepository teamRepo;
    private final AppUserRepository userRepo;
    private final AuditLogRepository auditLogRepo;
    private final PresenceService presenceService;
    private final AppSettingsService appSettings;

    /** Bellek kaydı: hesap anı + o hesapta kullanım alanlarının üretilip üretilmediği. */
    private record Snapshot(Map<String, Object> data, long at, boolean usage) {}

    private volatile Snapshot cached;
    private final ReentrantLock computeLock = new ReentrantLock();

    @GetMapping("/api/public-stats")
    public ResponseEntity<Map<String, Object>> stats() {
        boolean usage = usageEnabled();
        Map<String, Object> data = snapshot(usage);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("data", usage ? data : legacyOnly(data));
        response.put("success", true);
        response.put("timestamp", ISO.format(Instant.now()));
        return ResponseEntity.ok(response);
    }

    /** Ayar okunamazsa AÇIK (varsayılan) — anahtar Marka sayfasından kapatılır. */
    private boolean usageEnabled() {
        try {
            return appSettings.getBoolean("site.monitor.public-stats.usage-enabled", true);
        } catch (Exception e) {
            log.debug("Public stats kullanım ayarı okunamadı: {}", e.getMessage());
            return true;
        }
    }

    /**
     * Bellekli veri. Kayıt tazeyse ve gereken alanları taşıyorsa döner. Değilse TEK iş parçacığı hesaplar: kilidi
     * alamayan istek, uygun bir (bayat) kayıt varsa onu alır, yoksa (soğuk bellek / şerit yeni açıldı) hesabı bekler.
     */
    private Map<String, Object> snapshot(boolean usage) {
        Snapshot s = cached;
        if (usable(s, usage) && fresh(s)) return s.data();
        if (!computeLock.tryLock()) {
            if (usable(s, usage)) return s.data();
            computeLock.lock();
        }
        try {
            s = cached;
            if (usable(s, usage) && fresh(s)) return s.data();
            Snapshot next = new Snapshot(compute(usage), System.currentTimeMillis(), usage);
            cached = next;
            return next.data();
        } finally {
            computeLock.unlock();
        }
    }

    private static boolean usable(Snapshot s, boolean usage) {
        return s != null && (s.usage() || !usage);
    }

    private boolean fresh(Snapshot s) {
        return cacheMs > 0 && System.currentTimeMillis() - s.at() < cacheMs;
    }

    private static Map<String, Object> legacyOnly(Map<String, Object> data) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (String k : LEGACY_FIELDS) out.put(k, data.get(k));
        return out;
    }

    Map<String, Object> compute(boolean usage) {
        Instant now = Instant.now();
        String since24h = ISO.format(now.minus(24, ChronoUnit.HOURS));
        Map<String, Object> summary = figure("izleme özeti", overviewService::orgSummary);

        Map<String, Object> data = new LinkedHashMap<>();
        // Eski alan — artık panonun "aktif" sayısı (tutarlı tanım; eskiden fırtına paydasıydı).
        data.put("monitored_targets", summary != null ? summary.get("active") : null);
        data.put("availability_pct", figure("erişilebilirlik", this::availabilityPct));
        if (!usage) return data;

        data.put("healthy_monitors", summary != null ? summary.get("healthy") : null);
        data.put("active_monitors", summary != null ? summary.get("active") : null);
        data.put("total_monitors", summary != null ? summary.get("total") : null);
        data.put("checks_24h", summary != null ? summary.get("checks_window") : null);
        data.put("failed_checks_24h", summary != null ? summary.get("failed_window") : null);
        data.put("alerts_24h", figure("24 sa alarm", () -> alertEventRepo.countCreatedSince(since24h)));
        data.put("teams", figure("takım", teamRepo::countByActiveTrue));
        data.put("active_users", figure("aktif kullanıcı", userRepo::countByActiveTrue));
        data.put("online_users", figure("çevrimiçi", this::onlineUsers));
        data.put("logins_24h", figure("24 sa giriş", () -> auditLogRepo.countDistinctLoginActorsSince(since24h)));
        return data;
    }

    /** Tek rakam — hata verirse yalnız o rakam {@code null} (diğerleri etkilenmez). */
    private <T> T figure(String what, Supplier<T> source) {
        try {
            return source.get();
        } catch (Exception e) {
            log.debug("Public stats '{}' hesaplanamadı: {}", what, e.getMessage());
            return null;
        }
    }

    /** Çevrimiçi kullanıcı TOPLAMI — takım kırılımı public uca taşınmaz. */
    private Long onlineUsers() {
        Map<String, Object> online = presenceService.online();
        return online != null && online.get("total") instanceof Number n ? Long.valueOf(n.longValue()) : null;
    }

    /** 7 günlük erişilebilirlik: uptime_checks up oranı (bakım pencereleri hariç). Veri yoksa null → UI '—'. */
    private Double availabilityPct() {
        String cutoff = ISO.format(Instant.now().minus(7, ChronoUnit.DAYS));
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT COUNT(*) FILTER (WHERE status = 'up') AS ups, COUNT(*) AS total "
                        + "FROM uptime_checks WHERE checked_at >= ? AND maintenance IS NOT TRUE", cutoff);
        long total = row.get("total") instanceof Number n ? n.longValue() : 0;
        long ups   = row.get("ups")   instanceof Number n ? n.longValue() : 0;
        return com.sitemonitor.util.AvailabilityMath.pct(total, ups, 1);   // 1 ondalık; olay varken %100,0 DEĞİL (O-5)
    }
}
