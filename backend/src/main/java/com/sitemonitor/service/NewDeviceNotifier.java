package com.sitemonitor.service;

import com.sitemonitor.model.AppUser;
import com.sitemonitor.repository.AppUserRepository;
import com.sitemonitor.repository.AuditLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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

    public static final String SETTING_KEY = "site.monitor.security.new-device-email";
    /** Yeni cihaz postası tetikleyicisi — yalnız yapılamayan (başlamayan) denetimin izinde yazılır. */
    public static final String TRIGGER_NEW_DEVICE = "NEW_DEVICE";
    static final String STATUS_DROPPED =
            "SKIPPED: yeni cihaz denetimi yapılamadı (pod kapanıyordu ya da kuyruk doluydu) — bildirim gönderilmedi";

    /**
     * Kendi küçük kuyruğu (2026-10-23, çok pod R2a · D2): eskiden {@code @Async("certCheckExecutor")} ile sertifika tarama
     * havuzuna gidiyordu; o havuz kapanışta BEKLEMİYOR, kuyruktaki denetim (ve postası) iz bırakmadan düşüyordu. Artık kapanışta
     * ortak kapanış süresi içinde boşaltılır; başlayamayan her denetim için (özellik açıksa) {@code notification_logs} satırı.
     */
    final java.util.concurrent.ExecutorService exec = new java.util.concurrent.ThreadPoolExecutor(1, 1, 0L,
            java.util.concurrent.TimeUnit.MILLISECONDS, new java.util.concurrent.LinkedBlockingQueue<>(1_000), r -> {
                Thread t = new Thread(r, "new-device-mail");
                t.setDaemon(true);
                return t;
            });

    /** Kapanışta kuyruğun en çok bekleneceği süre (ortak kapanış süresiyle sınırlı). Paket-özel: testte kısaltılır. */
    long drainCapMs = 5_000L;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.sitemonitor.repository.NotificationLogRepository notificationLogRepo;

    void setNotificationLogRepo(com.sitemonitor.repository.NotificationLogRepository r) { this.notificationLogRepo = r; }

    @jakarta.annotation.PreDestroy
    void shutdownExecutor() {
        DroppableTask.drain(exec, drainCapMs, DroppableTask.MAX_TRACES, this::recordDroppedMore);
    }

    /**
     * Bu giriş, kullanıcı için DAHA ÖNCE görülmemiş bir cihazdan mı — öyleyse bilgilendir. Kuyruğa alır ve hemen döner (giriş
     * yanıtını bloklamaz); asıl denetim {@link #check}.
     *
     * @param auditId bu girişin denetim satırı (geçmiş taramasında kendisi HARİÇ tutulur)
     */
    public void notifyIfNewDevice(Long auditId, String actor, String userAgent,
                                  String ipAddress, String whenIso) {
        DroppableTask.submit(exec, () -> check(auditId, actor, userAgent, ipAddress, whenIso), () -> recordDropped(actor));
    }

    /** Başlayamayan denetimin izi — yalnız özellik AÇIKKEN (kapalıyken posta zaten gitmezdi). */
    void recordDropped(String actor) {
        try {
            if (!appSettings.getBoolean(SETTING_KEY, false)) return;
        } catch (Exception e) {
            return;
        }
        log.warn("Yeni cihaz denetimi başlayamadı (pod kapanıyor / kuyruk dolu): kullanıcı {} — bildirim gönderilmedi", actor);
        saveTrace(actor == null ? "-" : actor, STATUS_DROPPED);
    }

    /** Görev başı iz sınırını aşan başlamamış denetimler için TEK özet satırı (özellik açıkken). */
    void recordDroppedMore(int more) {
        try {
            if (!appSettings.getBoolean(SETTING_KEY, false)) return;
        } catch (Exception e) {
            return;
        }
        saveTrace("-", "SKIPPED: " + more + " yeni cihaz denetimi daha yapılamadı (pod kapanıyordu) — bildirim gönderilmedi");
    }

    private void saveTrace(String name, String status) {
        if (notificationLogRepo == null) return;
        com.sitemonitor.model.NotificationLog n = new com.sitemonitor.model.NotificationLog();
        n.setAlertEventId(0L);
        n.setSentAt(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(java.time.ZoneOffset.UTC)
                .format(java.time.Instant.now()));
        n.setRecipientName(name);
        n.setRecipientEmail("");
        n.setRecipientRole("USER");
        n.setSubject("[Site Monitor] Hesabınıza yeni bir cihazdan giriş yapıldı");
        n.setEmailStatus(status);
        n.setWebhookStatus("SKIPPED");
        n.setTrigger(TRIGGER_NEW_DEVICE);
        notificationLogRepo.save(n);
    }

    /** Asıl denetim (kuyruk iş parçacığında). Paket-özel: testler doğrudan çağırır. */
    void check(Long auditId, String actor, String userAgent, String ipAddress, String whenIso) {
        try {
            if (!appSettings.getBoolean(SETTING_KEY, false)) return;
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
