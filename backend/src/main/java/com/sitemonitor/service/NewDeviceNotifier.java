package com.sitemonitor.service;

import com.sitemonitor.model.AppUser;
import com.sitemonitor.repository.AppUserRepository;
import com.sitemonitor.repository.AuditLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * "Hesabına yeni bir cihazdan giriş yapıldı" bilgi e-postası (E1).
 *
 * <p>Amacı proaktif olmak: kullanıcı Cihaz Geçmişi ekranına bakmayı akıl etmeden, şüpheli girişi
 * ona biz söyleriz. Ekranın pasif değerini aktif hâle getiren parça budur.
 *
 * <p><b>Neden AYRI servis:</b> {@link AuditService} e-posta katmanına bağlı değil ve öyle kalmalı —
 * denetim kaydı yazmak, posta göndermeye bağımlı olmamalı. Buradaki her şey best-effort'tur:
 * hata YUTULUR, çünkü bir bildirim hatası GİRİŞİ engellememeli.
 *
 * <p><b>Yanlış alarm tuzağı:</b> "yeni cihaz" ham User-Agent eşitliğiyle belirlenmez. Tarayıcı her
 * güncellendiğinde ham dize değişir ({@code Chrome/120} → {@code Chrome/121}); ham karşılaştırma
 * her güncellemede "yeni cihaz" der ve kullanıcıyı yanlış alarma boğardı. Karşılaştırma
 * {@link UserAgentSummary} özeti üzerinden yapılır ("Windows · Chrome") — sürümden bağımsız.
 *
 * <p><b>Opt-in:</b> {@code site.monitor.security.new-device-email} varsayılan KAPALI. Kurumsal
 * kurulumda posta hacmi bir karardır; yönetici açar.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NewDeviceNotifier {

    private final AuditLogRepository auditLogRepo;
    private final AppUserRepository appUserRepo;
    private final EmailNotificationService emailService;
    private final AppSettingsService appSettings;
    private final GeoIpService geoIpService;

    /**
     * Bu giriş, kullanıcı için DAHA ÖNCE görülmemiş bir cihazdan mı — öyleyse bilgilendir.
     *
     * @param auditId bu girişin denetim satırı (geçmiş taramasında kendisi HARİÇ tutulur)
     */
    @Async("certCheckExecutor")
    public void notifyIfNewDevice(Long auditId, String actor, String userAgent,
                                  String ipAddress, String whenIso) {
        try {
            if (!appSettings.getBoolean("site.monitor.security.new-device-email", false)) return;
            if (auditId == null || actor == null || actor.isBlank()) return;

            String summary = UserAgentSummary.labelOf(userAgent);
            // Tanınamayan cihaz için bildirim GÖNDERİLMEZ: "Oturum" diye bir uyarı kullanıcıya
            // hiçbir şey anlatmaz ve her tanınmayan istemci (curl, bot, sağlık probu) posta üretirdi.
            if (summary == null) return;

            List<String> past = auditLogRepo.findDistinctLoginUserAgents(actor.toLowerCase(), auditId);
            Set<String> knownSummaries = new HashSet<>();
            for (String ua : past) {
                String s = UserAgentSummary.labelOf(ua);
                if (s != null) knownSummaries.add(s);
            }
            if (knownSummaries.contains(summary)) return;          // tanıdık cihaz → sessiz

            // İLK giriş de bildirilmez: kullanıcının geçmişi henüz yokken "yeni cihaz" demek,
            // hesabı ilk kez kuran herkese anlamsız bir uyarı göndermek olurdu.
            if (knownSummaries.isEmpty()) return;

            AppUser user = appUserRepo.findByUsername(actor).orElse(null);
            if (user == null || user.getEmail() == null || user.getEmail().isBlank()) return;

            String location = describeLocation(ipAddress);
            emailService.sendNewDeviceEmail(user.getEmail(),
                    user.getDisplayName() != null && !user.getDisplayName().isBlank()
                            ? user.getDisplayName() : user.getUsername(),
                    summary, ipAddress, location, whenIso);
            log.info("Yeni cihaz bildirimi gönderildi: user={} cihaz={}", actor, summary);
        } catch (Exception e) {
            // Bildirim GİRİŞİ engellemez — sessizce vazgeç.
            log.debug("Yeni cihaz bildirimi atlandı: {}", e.getMessage());
        }
    }

    /** Kurum ağından gelen IP'de şehir/ülke yoktur; e-postada boş satır yerine açık etiket. */
    private String describeLocation(String ip) {
        if (ip == null || ip.isBlank()) return null;
        if (geoIpService.isPrivateIp(ip)) return "Kurum ağı";
        try {
            GeoIpService.GeoInfo geo = geoIpService.lookup(ip);
            if (geo == null) return null;
            if (geo.city() != null && geo.country() != null) return geo.city() + ", " + geo.country();
            return geo.country() != null ? geo.country() : null;
        } catch (Exception e) {
            return null;
        }
    }
}
