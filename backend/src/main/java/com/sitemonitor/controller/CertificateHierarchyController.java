package com.sitemonitor.controller;

import com.sitemonitor.config.CorrelationIdFilter;
import com.sitemonitor.model.CertificateInventory;
import com.sitemonitor.repository.CertificateInventoryRepository;
import com.sitemonitor.service.CertificateCheckerService;
import com.sitemonitor.service.CertificateFacts;
import com.sitemonitor.service.InventoryVisibility;
import com.sitemonitor.service.PermissionService;
import com.sitemonitor.service.SsrfGuard;
import com.sitemonitor.service.TrustEvaluator;
import com.sitemonitor.service.certhierarchy.NetworkCertificateHierarchy;
import com.sitemonitor.util.Msg;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;

/**
 * Ağdan kontrol edilen sertifikanın tarayıcı gibi HİYERARŞİSİ (2026-10-12, kullanıcı isteği).
 *
 * <p>{@code GET /api/certificates/{domain}/hierarchy} — sunucuya kontrolle AYNI yoldan bir el sıkışma yapar, sunulan
 * zinciri alır ve kökü güven deposundan tamamlar ({@link NetworkCertificateHierarchy}). Düğüm biçimi elle yüklenen
 * sertifikanın {@code /manual-certs/{id}/versions/{vid}/chain} yanıtıyla aynıdır. Salt okuma: kayıt, önbellek, alarm,
 * denetim yok — SSL sekmesinin canlı önizlemesinin ({@code /check-preview}) kardeşi.
 *
 * <p><b>Kapı</b>: {@code inventory.list/view} + kayıt ENVANTERDE olmalı (ad-hoc host yok) + önizlemeyle AYNI okuma kuralı
 * (kendi kapsamı, 7/24 operatörü, org geneli okuma). Elle yüklenen kayıtta 409 {@code MANUAL_CERT} (kendi görünümü var).
 * Kullanıcı × alan adı başına dakikada {@value #MAX_PER_MINUTE}, aynı anda en çok {@value #MAX_CONCURRENT} el sıkışma;
 * aşımda 429 (eşzamanlılık reddi dakikalık hakkı YEMEZ). Bağlantı / el sıkışma hatası 502 {@code HIERARCHY_UNREACHABLE},
 * ad çözülemezse 502 {@code DNS_UNRESOLVABLE}, SSRF kuralı 400 {@code BLOCKED}, beklenmeyen hata 500 (istisna metni hiçbir
 * yanıtta istemciye gitmez).
 *
 * <p><b>Yol ipucu</b>: {@code tls_mode} (browser|default) ve {@code via} (proxy|direct) — SSL sekmesinin AZ ÖNCE başarılı
 * önizlemesinin kullandığı yol ({@code tls_mode_used} / {@code via}); kontrolün geri düşüşüyle (TLS kipi / proxy↔doğrudan)
 * başaran WAF'lı hostlar hiyerarşide de aynı yoldan okunur. Yalnız bu iki değer kabul edilir; {@code via=proxy} yalnız kayıt
 * proxy'liyse ya da otomatik proxy geri düşüşü açıksa geçerlidir, proxy yine yalnız yapılandırılmışsa ve NO_PROXY dışındaysa
 * kullanılır (karar kontrolle aynı yerde). İpucu yoksa kaydın ayarları.
 */
@Slf4j
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class CertificateHierarchyController {

    static final int MAX_PER_MINUTE = 6;
    static final long WINDOW_MS = 60_000L;
    static final int MAX_CONCURRENT = 4;

    private final CertificateInventoryRepository inventoryRepo;
    private final CertificateCheckerService checkerService;
    private final TrustEvaluator trustEvaluator;
    private final PermissionService permissionService;

    /** Org geneli okuma (isteğe bağlı — dilimli test bağlamında yok). */
    @Autowired(required = false)
    private InventoryVisibility inventoryVisibility;

    private final ConcurrentHashMap<String, Deque<Long>> rate = new ConcurrentHashMap<>();
    /** Paket-özel: testler her yolda iznin geri verildiğini doğrular. */
    final Semaphore inFlight = new Semaphore(MAX_CONCURRENT);
    /**
     * Kullanıcı başına eşzamanlı istek (2026-10-12): bir kişi farklı yavaş alan adlarıyla pod'un tüm {@value #MAX_CONCURRENT}
     * yuvasını tutup herkesi "meşgul"e düşürmesin. Paket-özel: testler her yolda sayacın geri verildiğini doğrular.
     */
    static final int MAX_PER_USER_CONCURRENT = 2;
    final ConcurrentHashMap<String, Integer> userInFlight = new ConcurrentHashMap<>();

    @GetMapping("/certificates/{domain}/hierarchy")
    public ResponseEntity<Map<String, Object>> hierarchy(@PathVariable String domain,
                                                         @RequestParam(name = "tls_mode", required = false) String tlsModeHint,
                                                         @RequestParam(name = "via", required = false) String viaHint,
                                                         HttpSession session) {
        permissionService.require(session, "inventory.list", "view");
        CertificateInventory inv = inventoryRepo.findByDomain(domain).orElseThrow(() -> new NoSuchElementException(Msg.t(
                "Bu alan adı envanterde yok; hiyerarşi yalnız izlenen sertifikalar için gösterilir.",
                "This domain isn't in the inventory; the hierarchy is shown only for monitored certificates.")));
        requireReadable(session, inv);
        if (inv.isManual()) throw CertificateController.manualCertConflict();

        // Önce eşzamanlılık: "meşgul" reddi kullanıcının dakikalık hakkını harcamasın. Kullanıcı başına tavan → pod geneli tavan.
        String user = userKey(session);
        if (!tryAcquireUser(user)) {
            return error(429, "RATE_LIMITED", Msg.t(
                    "Aynı anda en çok " + MAX_PER_USER_CONCURRENT + " hiyerarşi isteğiniz çalışabilir; önceki bitince yeniden deneyin.",
                    "At most " + MAX_PER_USER_CONCURRENT + " of your hierarchy requests can run at once; try again when the previous one finishes."));
        }
        if (!inFlight.tryAcquire()) {
            releaseUser(user);
            return error(429, "RATE_LIMITED", Msg.t(
                    "Şu an başka hiyerarşi istekleri çalışıyor; birkaç saniye sonra yeniden deneyin.",
                    "Other hierarchy requests are running right now; try again in a few seconds."));
        }
        try {
            if (!tryAcquireRate(user + "|" + inv.getDomain().toLowerCase(Locale.ROOT))) {
                return error(429, "RATE_LIMITED", Msg.t(
                        "Çok sık hiyerarşi isteği — kullanıcı ve alan adı başına dakikada en fazla " + MAX_PER_MINUTE
                                + " kez. Biraz bekleyip yeniden deneyin.",
                        "Too many hierarchy requests — at most " + MAX_PER_MINUTE
                                + " per minute per user and domain. Wait a moment and try again."));
            }
            // Yol ipucu YALNIZ kontrolün de gidebileceği yere: "proxy" → kayıt proxy'li ya da (geri düşüş açık VE otomatik proxy
            // geri düşüşü açık); "direct" → kayıt proxy'siz ya da geri düşüş açık (kontrolün chooseFallback kuralı: proxy
            // aşaması başarısızsa doğrudan dener). Operatörün proxy'ye yönlendirdiği kayıt, geri düşüş kapalıyken doğrudan
            // DENENMEZ (2026-10-12).
            boolean recordProxy = Boolean.TRUE.equals(inv.getUseProxy());
            boolean fallback = checkerService.retryFallbackEnabled();
            boolean forceProxy = "direct".equals(viaHint) ? (recordProxy && !fallback)
                    : "proxy".equals(viaHint) ? (recordProxy || (fallback && checkerService.autoProxyFallbackEnabled()))
                    : recordProxy;
            String tlsMode = "browser".equals(tlsModeHint) || "default".equals(tlsModeHint) ? tlsModeHint : inv.getTlsMode();
            int port = inv.getPort() != null && inv.getPort() > 0 ? inv.getPort() : 443;
            CertificateCheckerService.PresentedChain presented = checkerService.capturePresentedChain(
                    inv.getDomain(), port, forceProxy, tlsMode, inv.getTimeoutSeconds());
            TrustEvaluator.Anchors anchors = trustEvaluator.trustAnchors();
            Instant now = Instant.now();
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("domain", inv.getDomain());
            data.put("port", port);
            data.put("via", presented.viaProxy() ? "proxy" : "direct");
            data.put("checked_at", CertificateFacts.iso(now));
            // Sistem güven deposu okunamadıysa kök kararı "bilinmiyor" (arayüz yeşil / kırmızı demez, 2026-10-12)
            data.put("trust_store_readable", anchors.systemReadable());
            data.putAll(NetworkCertificateHierarchy.build(presented.certificates(), anchors.system(),
                    anchors.corporate(), now));
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("success", true);
            body.put("data", data);
            return ResponseEntity.ok(body);
        } catch (SsrfGuard.UnresolvableHostException e) {
            // Ad çözülemedi (NXDOMAIN / pod DNS) — güvenlik kuralı DEĞİL; kullanıcı ayarlara yönlendirilmez
            return error(502, "DNS_UNRESOLVABLE", Msg.t(
                    "Alan adı çözülemedi (DNS yanıt vermedi ya da kayıt yok); hiyerarşi alınamadı. Alan adının DNS kaydını "
                            + "ve sunucunun DNS erişimini kontrol edip yeniden deneyin.",
                    "The domain name couldn't be resolved (DNS didn't answer or there is no record), so the hierarchy can't "
                            + "be read. Check the domain's DNS record and the server's DNS access, then try again."));
        } catch (SsrfGuard.BlockedException e) {
            return error(400, "BLOCKED", Msg.t(
                    "Bu adrese güvenlik kuralı gereği bağlanılmıyor; hiyerarşi alınamadı. Adres kuralları Ayarlar → Güvenlik'te.",
                    "Connections to this address are blocked by a security rule, so the hierarchy can't be read. The address rules are under Settings → Security."));
        } catch (IOException e) {
            log.warn("Sertifika hiyerarşisi alınamadı: domain={} hata={}: {}", inv.getDomain(),
                    e.getClass().getSimpleName(), e.getMessage());
            return error(502, "HIERARCHY_UNREACHABLE", Msg.t(
                    "Sunucuya bağlanılamadı ya da el sıkışma tamamlanmadı; hiyerarşi şu an alınamıyor. Zincir görünümü son kontrolün "
                            + "sonucunu göstermeye devam eder — biraz sonra yeniden deneyin.",
                    "Couldn't connect to the server or finish the handshake, so the hierarchy can't be read right now. The chain view "
                            + "still shows the last check's result — try again in a little while."));
        } catch (RuntimeException e) {
            // Kod hatası bağlantı sorunu gibi gösterilmez: günlüğe tam iz, istemciye genel metin
            log.error("Sertifika hiyerarşisi kurulamadı: domain={}", inv.getDomain(), e);
            return error(500, "HIERARCHY_FAILED", Msg.t(
                    "Hiyerarşi hazırlanırken beklenmeyen bir hata oluştu. Birkaç saniye sonra yeniden deneyin; sürerse istek "
                            + "kimliğiyle sistem yöneticisine bildirin.",
                    "An unexpected error occurred while preparing the hierarchy. Try again in a few seconds; if it persists, "
                            + "report it to the system administrator with the request id."));
        } finally {
            inFlight.release();
            releaseUser(user);
        }
    }

    /** Kullanıcının çalışan istek sayısını atomik artırır; tavandaysa artırmaz ve false döner. */
    boolean tryAcquireUser(String user) {
        boolean[] ok = {false};
        userInFlight.compute(user, (k, n) -> {
            int cur = n == null ? 0 : n;
            if (cur >= MAX_PER_USER_CONCURRENT) return n;
            ok[0] = true;
            return cur + 1;
        });
        return ok[0];
    }

    /** Atomik azaltır; sıfıra inen kayıt silinir (harita yalnız çalışan isteği olan kullanıcıları tutar). */
    void releaseUser(String user) {
        userInFlight.computeIfPresent(user, (k, n) -> n <= 1 ? null : n - 1);
    }

    /**
     * Okuma kuralı — {@code CertificateController.requireReadableDomain} ile AYNI (SSL sekmesinin canlı önizlemesi):
     * izleme okuması (kendi kapsamı ya da 7/24 operatörü, SY veya UG takımı) ya da org geneli okuma.
     */
    private void requireReadable(HttpSession session, CertificateInventory inv) {
        if (SessionScope.canViewMonitoring(session, inv.getTeamId())) return;
        if (inv.getUgTeamId() != null && SessionScope.canViewMonitoring(session, inv.getUgTeamId())) return;
        if (inventoryVisibility != null && inventoryVisibility.readableOrgWide(session, inv)) return;
        throw new SecurityException(Msg.t("Bu alan adını görüntüleme yetkiniz yok.",
                "You are not allowed to view this domain."));
    }

    boolean tryAcquireRate(String key) {
        long now = System.currentTimeMillis();
        synchronized (rate) {
            if (rate.size() > 1_000) {
                rate.entrySet().removeIf(e -> {
                    Deque<Long> dq = e.getValue();
                    while (!dq.isEmpty() && now - dq.peekFirst() > WINDOW_MS) dq.pollFirst();
                    return dq.isEmpty();
                });
                if (rate.size() > 10_000) rate.clear();
            }
            Deque<Long> dq = rate.computeIfAbsent(key, x -> new ArrayDeque<>());
            while (!dq.isEmpty() && now - dq.peekFirst() > WINDOW_MS) dq.pollFirst();
            if (dq.size() >= MAX_PER_MINUTE) return false;
            dq.addLast(now);
            return true;
        }
    }

    private static String userKey(HttpSession session) {
        Object uid = session != null ? session.getAttribute("userId") : null;
        if (uid != null) return "u:" + uid;
        Object u = session != null ? session.getAttribute("username") : null;
        return u != null ? "n:" + u : "s:" + (session != null ? session.getId() : "anon");
    }

    private static ResponseEntity<Map<String, Object>> error(int status, String code, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", false);
        body.put("code", code);
        body.put("error", message);
        String rid = CorrelationIdFilter.current();
        if (rid != null) body.put("request_id", rid);
        return ResponseEntity.status(status).body(body);
    }
}
