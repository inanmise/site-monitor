package com.sitemonitor.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * İptal (OCSP/CRL) sorgusunun NEDEN kodu ve deneme ayrıntısı (2026-10-08, kullanıcı isteği: "Ham durum UNKNOWN — adres
 * göremedim; boş da olsa alanları gösterelim, yanıltıcı uyarıları kaldıralım, kullanıcıya düzgün bilgi verelim").
 *
 * <p>{@code revocation_status} yalnız VALID / REVOKED / UNKNOWN diyordu; UNKNOWN'un sebebi (sertifikada adres yok mu,
 * yalnız LDAP mı, adrese ulaşılamadı mı) hiçbir yerde yoktu ve Sağlık satırı HER durumda "OCSP/CRL erişimini kontrol
 * edin (proxy)" öneriyordu — adresi olmayan sertifikada (iç PKI'larda olağan) düzeltilecek bir şey yokken.
 *
 * <p>Tek kaynak: {@link ChainValidationService#checkRevocationDetailed} üretir; {@code latest_checks} /
 * {@code certificate_checks} {@code revocation_reason} (kod) + {@code revocation_detail} (denemeler, kısa JSON)
 * kolonlarında saklanır; Sağlık satırı ({@code CertificateHealthService}) ve arayüz ({@code utils/revocationInfo.js}) okur.
 * Durum hükmü DEĞİŞMEZ — bu sınıf yalnız açıklama üretir.
 */
public final class RevocationReason {

    /** Durum OCSP yanıtından geldi (VALID / REVOKED). */
    public static final String OCSP = "OCSP";
    /** Durum indirilen CRL'den geldi (VALID / REVOKED). */
    public static final String CRL = "CRL";
    /** Sertifikada OCSP de CRL adresi de yok — iptal durumu HİÇBİR istemcice denetlenemez (iç PKI'da olağan). */
    public static final String NO_ENDPOINTS = "NO_ENDPOINTS";
    /** Adres var ama hiçbiri http/https değil (çoğunlukla {@code ldap://}) — bu istemci sorgulayamaz. */
    public static final String UNSUPPORTED_SCHEME = "UNSUPPORTED_SCHEME";
    /** http/https adres var ama hiçbirinden yanıt alınamadı — denemeler {@code revocation_detail}'de. */
    public static final String UNREACHABLE = "UNREACHABLE";
    /** Veren (issuer) sertifika yok — iptal sorgusu kurulamaz. */
    public static final String NO_ISSUER = "NO_ISSUER";
    /** Hafif kontrol: iptal sorgusu bilinçli olarak yapılmadı. */
    public static final String NOT_CHECKED = "NOT_CHECKED";
    /** Elle yüklenen sertifika: sorgu henüz yapılmadı (arka planda ya da saatlik değerlendirmede yapılır). */
    public static final String PENDING = "PENDING";

    // ── Deneme (attempt) hata kodları — arayüz {@code hlth.revFail.<KOD>} ile çevirir ──
    public static final String FAIL_SCHEME = "SCHEME";
    public static final String FAIL_BLOCKED = "BLOCKED";
    public static final String FAIL_DNS = "DNS";
    public static final String FAIL_TIMEOUT = "TIMEOUT";
    public static final String FAIL_REFUSED = "REFUSED";
    public static final String FAIL_PROXY = "PROXY";
    public static final String FAIL_TLS = "TLS";
    public static final String FAIL_HTTP = "HTTP";
    public static final String FAIL_BAD_RESPONSE = "BAD_RESPONSE";
    public static final String FAIL_UNKNOWN_CERT = "UNKNOWN_CERT";
    public static final String FAIL_NETWORK = "NETWORK";
    public static final String FAIL_OTHER = "OTHER";

    /** Saklanan deneme sayısı ve adres uzunluğu tavanı (kolon şişmesin; adresi sertifika yazar). */
    static final int MAX_ATTEMPTS = 6;
    static final int MAX_URL = 300;

    private static final ObjectMapper JSON = new ObjectMapper();

    private RevocationReason() { }

    /**
     * Tek bir OCSP/CRL denemesi: kaynak ({@code OCSP}|{@code CRL}), adres, hata kodu ({@code FAIL_*}) ve HTTP hata
     * durumunda kod / OCSP yanıtlayıcı durumunda numara.
     */
    public record Attempt(String via, String url, String code, Integer status) {
        Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("via", via);
            m.put("url", clip(url));
            m.put("code", code);
            if (status != null) m.put("status", status);
            return m;
        }
    }

    /** Denemeleri kısa JSON'a çevirir; boşsa null. */
    public static String toJson(List<Attempt> attempts) {
        if (attempts == null || attempts.isEmpty()) return null;
        List<Map<String, Object>> out = new ArrayList<>();
        for (Attempt a : attempts) {
            if (out.size() >= MAX_ATTEMPTS) break;
            out.add(a.toMap());
        }
        try {
            return JSON.writeValueAsString(out);
        } catch (Exception e) {
            return null;
        }
    }

    /** Saklanan JSON'u listeye çevirir; bozuk / boş → boş liste (gösterim asla düşmez). */
    public static List<Map<String, Object>> parse(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            List<Map<String, Object>> l = JSON.readValue(json, new TypeReference<List<Map<String, Object>>>() { });
            return l == null ? List.of() : l;
        } catch (Exception e) {
            return List.of();
        }
    }

    /**
     * Gösterilecek neden: saklanan kod; yoksa (bu değişiklikten önceki kayıt) sertifika GERÇEKTEN okunmuş, durum
     * UNKNOWN ve iki adres de boşsa {@link #NO_ENDPOINTS} türetilir — sertifikada adres olmadığı sonradan da bellidir.
     * Okunamamış kayıtta (hata satırı) türetilmez: orada adresler sertifika okunmadığı için boştur.
     */
    public static String effective(String stored, String status, String ocspUrl, String crlUrl, boolean certificateRead) {
        if (stored != null && !stored.isBlank()) return stored;
        if (!certificateRead || !"UNKNOWN".equalsIgnoreCase(status == null ? "" : status.trim())) return null;
        return isBlank(ocspUrl) && isBlank(crlUrl) ? NO_ENDPOINTS : null;
    }

    static boolean isHttpUrl(String url) {
        if (url == null) return false;
        String s = url.trim().toLowerCase(java.util.Locale.ROOT);
        return s.startsWith("http://") || s.startsWith("https://");
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static String clip(String s) {
        if (s == null) return null;
        return s.length() > MAX_URL ? s.substring(0, MAX_URL) + "…" : s;
    }
}
