package com.certmonitor.service;

import com.certmonitor.model.PinnedCa;
import com.certmonitor.repository.PinnedCaRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import javax.net.ssl.X509TrustManager;
import java.net.IDN;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * CA otomatik sabitleme (auto-pin / TOFU): HTTP monitörünün strict TLS kontrolü PKIX güven hatası
 * verdiğinde sunucunun zinciri trust-all soketle çekilir, CA'ları host:port başına DB'ye pinlenir
 * ve kontrol bir kez tekrarlanır; pin süresi dolunca / sunucu yeni CA'ya geçince otomatik yeniden
 * pinlenir ({@code SchedulerService.runCaPinRefresh} + hata anında lazy re-pin). Admin onayı yoktur —
 * her pin/rotasyon audit-log'a yazılır (CA_PINNED / CA_ROTATED). Bilinçli TOFU: hostname doğrulaması
 * ve geçerlilik kontrolleri delege TM'lerde aynen çalışır; pinler YALNIZ HTTP uptime strict yolunu
 * etkiler (sertifika trust_status raporu ve RDAP pinlere bakmaz).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CaAutoPinService {

    /** Genel Ayarlar key'i — özellik anahtarı (varsayılan açık). */
    public static final String ENABLED_KEY = "cert.monitor.trust.auto-pin.enabled";

    private static final DateTimeFormatter ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);

    /** TM cache TTL — pin'i olmayan hostlar dahil (negatif cache; handshake başına DB'ye gitme). */
    private static final long TM_CACHE_TTL_MS   = 60_000;
    /** Aynı host için art arda pin denemesi alt sınırı (bozuk endpoint'i her 30 sn'lik kontrolde dövme). */
    private static final long PIN_RATE_LIMIT_MS = 5 * 60_000;
    /** recordTrustFailure → drain penceresi (redirect hedefinin pinlenebilmesi için). */
    private static final long FAILURE_WINDOW_MS = 10_000;
    private static final int  MAX_MAP_ENTRIES   = 10_000;
    private static final int  FETCH_TIMEOUT_SEC = 8;

    private final CertificateCheckerService certificateCheckerService;
    private final PinnedCaRepository pinnedCaRepository;
    private final AppSettingsService appSettings;
    private final AuditService auditService;

    /** tm=null → "pin yok" negatif girişi. */
    private record TmEntry(X509TrustManager tm, long loadedAt) {}

    private final ConcurrentHashMap<String, TmEntry> tmCache             = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long>    lastPinAttempt      = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Object>  hostLocks           = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long>    recentTrustFailures = new ConcurrentHashMap<>();

    public boolean isEnabled() {
        return appSettings.getBoolean(ENABLED_KEY, true);
    }

    /**
     * Handshake anında pin lookup ({@code TrustEvaluator.pinAwareOutboundSslContext} delege zincirinin
     * son halkası). Pin yoksa / özellik kapalıysa null — çağıran cacerts+bundle ile kalır.
     */
    public X509TrustManager trustManagerForHost(String host, int port) {
        if (host == null || host.isBlank() || !isEnabled()) return null;
        String key = key(normalizeHost(host), port);
        long now = System.currentTimeMillis();
        TmEntry e = tmCache.get(key);
        if (e != null && now - e.loadedAt() < TM_CACHE_TTL_MS) return e.tm();
        if (tmCache.size() > MAX_MAP_ENTRIES) tmCache.clear();
        X509TrustManager tm = null;
        try {
            Optional<PinnedCa> pin = pinnedCaRepository.findByHostAndPort(normalizeHost(host), port);
            if (pin.isPresent()) tm = TrustEvaluator.buildTmFromPem(pin.get().getPem());
        } catch (Exception ex) {
            log.warn("Pinlenmiş CA yüklenemedi {}: {}", key, ex.getMessage());
        }
        tmCache.put(key, new TmEntry(tm, now));
        return tm;
    }

    /**
     * Sunucudan zinciri çekip CA'ları pinler. Yeni pin ya da fingerprint değişimi → true (caller bir
     * kez retry eder); aynı pin / rate-limit / hata → false. Thread-safe (host başına lock) ve
     * çok-pod yarışına dayanıklı (unique constraint → yeniden oku-güncelle).
     */
    public boolean pinFromServer(String rawHost, int port, String reason) {
        if (!isEnabled()) return false;
        String host = normalizeHost(rawHost);
        if (host.isBlank()) return false;
        String key = key(host, port);
        long now = System.currentTimeMillis();
        Long last = lastPinAttempt.get(key);
        if (last != null && now - last < PIN_RATE_LIMIT_MS) return false;
        if (lastPinAttempt.size() > MAX_MAP_ENTRIES) lastPinAttempt.clear();
        lastPinAttempt.put(key, now);
        if (hostLocks.size() > MAX_MAP_ENTRIES) hostLocks.clear();
        Object lock = hostLocks.computeIfAbsent(key, k -> new Object());
        synchronized (lock) {
            try {
                X509Certificate[] chain = certificateCheckerService.captureDirectChain(host, port, FETCH_TIMEOUT_SEC);
                if (chain == null || chain.length == 0) return false;
                // CA'lar = leaf hariç tümü; sunucu yalnız leaf sunuyorsa (self-signed/eksik zincir) leaf'in kendisi.
                X509Certificate[] toPin = chain.length > 1 ? Arrays.copyOfRange(chain, 1, chain.length) : chain;
                String fingerprint = sha256Hex(toPin);

                Optional<PinnedCa> existing = pinnedCaRepository.findByHostAndPort(host, port);
                if (existing.isPresent() && fingerprint.equals(existing.get().getFingerprintSha256())) {
                    return false; // sunucu hâlâ aynı CA'yı sunuyor — pin sorunu değil
                }
                boolean rotated = existing.isPresent();
                String oldFp = rotated ? existing.get().getFingerprintSha256() : null;

                PinnedCa p = existing.orElseGet(PinnedCa::new);
                p.setHost(host);
                p.setPort(port);
                p.setPem(toPem(toPin));
                p.setFingerprintSha256(fingerprint);
                p.setSubject(abbreviate(toPin[toPin.length - 1].getSubjectX500Principal().getName(), 500));
                p.setNotAfter(minNotAfter(toPin));
                p.setPinnedAt(ISO.format(Instant.now()));
                p.setLastReason(reason);
                try {
                    pinnedCaRepository.save(p);
                } catch (DataIntegrityViolationException dup) { // çok-pod yarışı: diğeri önce insert etti
                    PinnedCa cur = pinnedCaRepository.findByHostAndPort(host, port).orElseThrow(() -> dup);
                    if (fingerprint.equals(cur.getFingerprintSha256())) { tmCache.remove(key); return true; }
                    cur.setPem(p.getPem());
                    cur.setFingerprintSha256(fingerprint);
                    cur.setSubject(p.getSubject());
                    cur.setNotAfter(p.getNotAfter());
                    cur.setPinnedAt(p.getPinnedAt());
                    cur.setLastReason(reason);
                    pinnedCaRepository.save(cur);
                }
                tmCache.remove(key);
                String detail = "host=" + key + " reason=" + reason
                        + " subject=" + p.getSubject() + " notAfter=" + p.getNotAfter()
                        + " fingerprint=" + fingerprint + (oldFp != null ? " oldFingerprint=" + oldFp : "");
                auditService.recordAction(rotated ? "CA_ROTATED" : "CA_PINNED",
                        "system", null, null, null, "pinned_ca", key, detail, null, null, null);
                log.info("CA auto-pin {}: {} — {}", rotated ? "rotated" : "pinned", key, p.getSubject());
                return true;
            } catch (Exception e) {
                log.warn("CA auto-pin başarısız {} — {}", key, e.getMessage());
                return false;
            }
        }
    }

    /** Handshake reddi anında TM tarafından çağrılır — retry yolunun redirect hedefini de pinleyebilmesi için. */
    public void recordTrustFailure(String host, int port) {
        if (host == null || host.isBlank()) return;
        if (recentTrustFailures.size() > 1_000) recentTrustFailures.clear();
        recentTrustFailures.put(key(normalizeHost(host), port), System.currentTimeMillis());
    }

    /** Son {@value #FAILURE_WINDOW_MS} ms içindeki güven hatası hedeflerini ("host:port") boşaltarak döner. */
    public Set<String> drainRecentTrustFailures() {
        long now = System.currentTimeMillis();
        Set<String> out = new HashSet<>();
        for (var it = recentTrustFailures.entrySet().iterator(); it.hasNext(); ) {
            var en = it.next();
            it.remove();
            if (now - en.getValue() <= FAILURE_WINDOW_MS) out.add(en.getKey());
        }
        return out;
    }

    /**
     * Bitişine ≤7 gün kalan (veya geçmiş) pinleri sunucudan yeniden çekip gerekiyorsa döndürür.
     * PKIX trust anchor'ın geçerliliğini kontrol etmediğinden süresi dolan pin handshake'i düşürmeyebilir —
     * bu proaktif yol asıl yenileme mekanizmasıdır; hata-anı re-pin yedektir. Döndürülen sayı: rotasyon adedi.
     */
    public int refreshExpiringPins() {
        if (!isEnabled()) return 0;
        String threshold = ISO.format(Instant.now().plus(7, ChronoUnit.DAYS));
        int rotated = 0;
        for (PinnedCa p : pinnedCaRepository.findByNotAfterLessThanEqual(threshold)) {
            lastPinAttempt.remove(key(p.getHost(), p.getPort())); // proaktif yenileme rate-limit'e takılmasın
            if (pinFromServer(p.getHost(), p.getPort(), "scheduled-refresh")) rotated++;
        }
        return rotated;
    }

    /** Pin yazımı (URL host) ve lookup ({@code SSLEngine.getPeerHost()}) AYNI normalize'ı kullanmalı. */
    static String normalizeHost(String h) {
        if (h == null) return "";
        String s = h.trim().toLowerCase(Locale.ROOT);
        if (s.startsWith("[") && s.endsWith("]")) s = s.substring(1, s.length() - 1);
        try { s = IDN.toASCII(s); } catch (Exception ignore) { /* best-effort */ }
        return s;
    }

    private static String key(String normalizedHost, int port) {
        return normalizedHost + ":" + port;
    }

    private static String sha256Hex(X509Certificate[] certs) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        for (X509Certificate c : certs) md.update(c.getEncoded());
        StringBuilder sb = new StringBuilder();
        for (byte b : md.digest()) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    private static String toPem(X509Certificate[] certs) throws Exception {
        StringBuilder sb = new StringBuilder();
        Base64.Encoder enc = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII));
        for (X509Certificate c : certs) {
            sb.append("-----BEGIN CERTIFICATE-----\n")
              .append(enc.encodeToString(c.getEncoded()))
              .append("\n-----END CERTIFICATE-----\n");
        }
        return sb.toString();
    }

    private static String minNotAfter(X509Certificate[] certs) {
        Instant min = null;
        for (X509Certificate c : certs) {
            Instant na = c.getNotAfter().toInstant();
            if (min == null || na.isBefore(min)) min = na;
        }
        return ISO.format(min);
    }

    private static String abbreviate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }
}
