package com.sitemonitor.service;

import com.sitemonitor.model.LatestCheck;
import com.sitemonitor.model.PageMonitor;
import com.sitemonitor.repository.LatestCheckRepository;
import com.sitemonitor.repository.PageCheckRepository;
import com.sitemonitor.repository.PageMonitorRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * Sağlık listesinin UYGULAMA KATMANI satırlarını (HSTS, karışık içerik) İSTEMLİ olarak doldurur.
 *
 * <p><b>Otomatik ASLA koşmaz.</b> Saatlik süpürmeye eklenseydi tüm envanter için her saat HTML
 * çekilirdi: izlenen sistemlere gereksiz yük ve bizde ciddi maliyet. Bu yüzden yalnız kullanıcı
 * "Şimdi kontrol et" dediğinde çalışır, sonuç tarihiyle saklanır ve sonraki açılışlarda o tarihle
 * gösterilir.
 *
 * <p><b>Yeni ağ yolu AÇILMAZ.</b> HSTS için {@link HstsDiagnosticsService}, karışık içerik için
 * {@link PageCheckerService} çekirdeği kullanılır — proxy, timeout, SSRF koruması ve HTML
 * ayrıştırma zaten oralarda çözülmüş. İkinci bir istemci yazmak, o korumaların birini atlamak
 * demek olurdu.
 *
 * <p><b>Sayfa izlemesi varsa ONA bakılır (K3).</b> Aynı adres zaten dakikalar içinde taranıyorsa
 * yeniden HTML çekmenin anlamı yok; sonuç oradan okunur ve satır bedavaya dolar.
 *
 * <p><b>Hata UNKNOWN'dır, FAIL değil.</b> Sayfa çekilemedi/başlık okunamadı → "Doğrulanamadı".
 * Erişilemeyen bir sayfayı "karışık içerik var" diye raporlamak yanlış alarm üretirdi.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CertificateAppLayerProbe {

    /** Saklanan durum etiketleri — {@code CertificateHealthService} bunları okur. */
    public static final String HSTS_ENABLED = "ENABLED";
    public static final String HSTS_MISSING = "MISSING";
    public static final String MIXED_CLEAN = "CLEAN";
    public static final String MIXED_FOUND = "MIXED";
    public static final String UNKNOWN = "UNKNOWN";

    /** Tek sayfa taraması için üst sınır — kullanıcı düğmeye basıp beklerken makul kalmalı. */
    private static final int PAGE_TIMEOUT_MS = 8_000;

    private static final DateTimeFormatter ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);

    private final HstsDiagnosticsService hstsService;
    private final PageCheckerService pageChecker;
    private final PageMonitorRepository pageMonitorRepo;
    private final PageCheckRepository pageCheckRepo;
    private final LatestCheckRepository latestCheckRepo;

    /**
     * İki satırı da tazeler ve {@code latest_checks}'e yazar.
     *
     * <p>Best-effort: bir probe patlarsa diğeri yine koşar ve sertifika tazelemesi düşmez.
     * Sağlık listesi eksik bir satırla açılır, kullanıcının kaydı bozulmaz.
     */
    public void refresh(String domain, int port) {
        String now = ISO.format(Instant.now());
        String hsts = safe(() -> checkHsts(domain, port), "HSTS");
        String mixed = safe(() -> checkMixedContent(domain, port), "karışık içerik");

        try {
            LatestCheck lc = latestCheckRepo.findById(domain).orElse(null);
            if (lc == null) return;                    // hiç kontrol edilmemiş domain — yazacak satır yok
            lc.setHstsStatus(hsts);
            lc.setHstsAt(now);
            lc.setMixedContentStatus(mixed);
            lc.setMixedContentAt(now);
            latestCheckRepo.save(lc);
        } catch (Exception e) {
            log.warn("Uygulama katmanı sonuçları yazılamadı ({}): {}", domain, e.toString());
        }
    }

    /**
     * HSTS başlığı — {@code HstsDiagnosticsService} çekirdeği paylaşılır, admin kapısı DELİNMEZ
     * (o uç yerinde kalır; burada yalnız servis çağrılır).
     *
     * <p>{@code max-age=0} politikayı bilerek SİLER, yani başlık var diye "açık" saymak yanlış
     * olur — {@code NOT_ENFORCED} de eksik sayılır.
     */
    public String checkHsts(String domain, int port) {
        Map<String, Object> r = hstsService.diagnose(domain, port);
        String verdict = String.valueOf(r.get("verdict"));
        if ("ENFORCED".equals(verdict)) return HSTS_ENABLED;
        if ("ABSENT".equals(verdict) || "NOT_ENFORCED".equals(verdict)) return HSTS_MISSING;
        return UNKNOWN;                                 // CONNECT_FAILED ve beklenmeyen değerler
    }

    /**
     * Karışık içerik — önce mevcut Sayfa İzleme sonucu, yoksa tek seferlik tarama (K3).
     *
     * <p>Sayfa izlemesinin son kontrolü kullanılabiliyorsa yeni HTML çekilmez: aynı iş dakikalar
     * içinde zaten yapılıyor.
     */
    public String checkMixedContent(String domain, int port) {
        String fromMonitor = fromPageMonitor(domain);
        if (fromMonitor != null) return fromMonitor;

        String url = "https://" + domain + (port == 443 ? "" : ":" + port) + "/";
        var result = pageChecker.test(url, PAGE_TIMEOUT_MS);
        if (result == null || !result.mainReachable()) return UNKNOWN;
        return result.mixedContentCount() > 0 ? MIXED_FOUND : MIXED_CLEAN;
    }

    /** Aktif sayfa izlemelerinin EN YENİ kontrolünden karışık içerik durumu; yoksa null. */
    private String fromPageMonitor(String domain) {
        try {
            List<PageMonitor> monitors = pageMonitorRepo.findByUrlContainingIgnoreCaseAndActiveTrue(domain);
            boolean sawResult = false;
            for (PageMonitor m : monitors) {
                var last = pageCheckRepo.findTopByMonitorIdOrderByCheckedAtDesc(m.getId()).orElse(null);
                if (last == null) continue;
                sawResult = true;
                // Herhangi bir izlemede karışık içerik varsa satır KİRLİ: tek temiz sayfa
                // diğerini aklamaz.
                if (last.getMixedContentCount() != null && last.getMixedContentCount() > 0) return MIXED_FOUND;
            }
            return sawResult ? MIXED_CLEAN : null;
        } catch (Exception e) {
            log.debug("Sayfa izleme sonucu okunamadı ({}): {}", domain, e.toString());
            return null;
        }
    }

    private String safe(java.util.function.Supplier<String> probe, String label) {
        try {
            String v = probe.get();
            return v == null ? UNKNOWN : v;
        } catch (Exception e) {
            log.debug("{} kontrolü başarısız: {}", label, e.toString());
            return UNKNOWN;
        }
    }
}
