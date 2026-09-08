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
    public void refresh(String domain, int port, boolean forceProxy) {
        String now = ISO.format(Instant.now());
        ProbeOutcome hsts = safeHsts(domain, port, forceProxy);
        ProbeOutcome mixed = safeMixed(domain, port);

        try {
            LatestCheck lc = latestCheckRepo.findById(domain).orElse(null);
            if (lc == null) return;                    // hiç kontrol edilmemiş domain — yazacak satır yok
            lc.setHstsStatus(hsts.status());
            lc.setHstsAt(now);
            // UNKNOWN'un SEBEBİ saklanır: sağlık satırında "Doğrulanamadı" yazıp nedenini
            // söylememek, kullanıcıyı tam olarak buraya bakmaya zorlayan şeydi.
            lc.setHstsNote(hsts.note());
            lc.setMixedContentStatus(mixed.status());
            lc.setMixedContentAt(now);
            // HSTS ile AYNI sözleşme: UNKNOWN'un sebebi saklanır. Sebep yazılmazsa satır
            // "Kontrol edilmedi" görünür ve kullanıcı az önce yaptığı şeyi tekrar denemeye
            // yönlendirilir.
            lc.setMixedContentNote(mixed.note());
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
    public String checkHsts(String domain, int port, boolean forceProxy) {
        return probeHsts(domain, port, forceProxy).status();
    }

    /**
     * Durum + (yalnız UNKNOWN'da) gerekçe. Gerekçe DÖNÜŞ DEĞERİNDE taşınır, bir alanda değil:
     * bu servis tekil (singleton) ve iki kullanıcı farklı domainler için aynı anda kontrol
     * tetikleyebilir — paylaşılan alan, birinin gerekçesini diğerinin satırına yazardı.
     */
    private record ProbeOutcome(String status, String note) {}

    private ProbeOutcome probeHsts(String domain, int port, boolean forceProxy) {
        // forceProxy YUKARIDAN iner: izlemenin "vekilsiz" tercihi burada yeniden türetilirse
        // sertifika kontrolüyle ayrışır ve aynı domain için iki farklı cevap çıkar.
        Map<String, Object> r = hstsService.diagnose(domain, port, forceProxy);
        String verdict = String.valueOf(r.get("verdict"));
        if ("ENFORCED".equals(verdict)) return new ProbeOutcome(HSTS_ENABLED, null);
        if ("ABSENT".equals(verdict) || "NOT_ENFORCED".equals(verdict)) {
            return new ProbeOutcome(HSTS_MISSING, null);
        }
        return new ProbeOutcome(UNKNOWN, note(verdict, r));   // CONNECT_FAILED ve beklenmeyenler
    }

    /** UNKNOWN'un okunur gerekçesi — sağlık satırının kanıtında gösterilir. */
    private static String note(String verdict, Map<String, Object> r) {
        Object err = r.get("error");
        Object why = r.get("proxy_reason");
        String base = err != null ? String.valueOf(err) : verdict;
        return why != null ? base + " (" + why + ")" : base;
    }

    /** {@link #safe} ile aynı best-effort sözleşmesi; gerekçeyi de taşır. */
    private ProbeOutcome safeHsts(String domain, int port, boolean forceProxy) {
        try {
            ProbeOutcome o = probeHsts(domain, port, forceProxy);
            return o == null ? new ProbeOutcome(UNKNOWN, null) : o;
        } catch (Exception e) {
            log.debug("HSTS kontrolü başarısız: {}", e.toString());
            return new ProbeOutcome(UNKNOWN, e.toString());
        }
    }

    /** {@link #safeHsts} ile aynı best-effort sözleşmesi; gerekçeyi de taşır. */
    private ProbeOutcome safeMixed(String domain, int port) {
        try {
            ProbeOutcome o = probeMixedContent(domain, port);
            return o == null ? new ProbeOutcome(UNKNOWN, null) : o;
        } catch (Exception e) {
            log.debug("Karışık içerik kontrolü başarısız: {}", e.toString());
            return new ProbeOutcome(UNKNOWN, e.toString());
        }
    }

    /**
     * Karışık içerik — önce mevcut Sayfa İzleme sonucu, yoksa tek seferlik tarama (K3).
     *
     * <p>Sayfa izlemesinin son kontrolü kullanılabiliyorsa yeni HTML çekilmez: aynı iş dakikalar
     * içinde zaten yapılıyor.
     */
    public String checkMixedContent(String domain, int port) {
        return probeMixedContent(domain, port).status();
    }

    /**
     * Karışık içerik sonucu + (yalnız UNKNOWN'da) GEREKÇE — HSTS ile aynı sözleşme.
     *
     * <p><b>Neden gerekçe eklendi.</b> Bu satır yalnız {@code UNKNOWN} dönebiliyor ve sebebini
     * hiçbir yere yazmıyordu. Kullanıcı "Şimdi kontrol et"e basıyor, kontrol GERÇEKTEN koşuyor
     * (zaman damgası ve kaynak kaydediliyor), ama satır "Kontrol edilmedi" olarak kalıyor ve
     * önerilen eylem yine "kontrol edin" oluyordu — yani ekran, az önce yapılan şeyi öneren
     * kapalı bir döngüye giriyordu. En sık sebebi API uçları: {@code GET /} 401/403/404 dönünce
     * taranacak HTML yoktur ({@code mainReachable == false}), bu bir hata değil ama kullanıcının
     * bunu BİLMESİ gerekir — "Sayfa İzleme ekleyin" önerisi ancak o zaman anlam kazanır.
     */
    private ProbeOutcome probeMixedContent(String domain, int port) {
        String fromMonitor = fromPageMonitor(domain);
        if (fromMonitor != null) return new ProbeOutcome(fromMonitor, null);

        String url = "https://" + domain + (port == 443 ? "" : ":" + port) + "/";
        var result = pageChecker.test(url, PAGE_TIMEOUT_MS);
        if (result == null) return new ProbeOutcome(UNKNOWN, "sayfa kontrolü sonuç döndürmedi");
        if (!result.mainReachable()) {
            String why = result.error() != null ? result.error()
                    : (result.httpStatus() != null ? "ana sayfa HTTP " + result.httpStatus()
                                                   : "ana sayfa alınamadı");
            return new ProbeOutcome(UNKNOWN, why);
        }
        return new ProbeOutcome(result.mixedContentCount() > 0 ? MIXED_FOUND : MIXED_CLEAN, null);
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
