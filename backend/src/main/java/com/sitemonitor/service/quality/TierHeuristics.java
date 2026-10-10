package com.sitemonitor.service.quality;

import com.sitemonitor.model.CertificateInventory;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * "Katman (tier) sinyallerle çelişiyor" kuralının SEZGİLERİ — bilinçli olarak MUHAFAZAKÂR ve AÇIKLANABİLİR.
 *
 * <p>Katmanın doğrusunu yalnız kaydın sahibi bilir; sistem yalnız GÜÇLÜ bir çelişkiyi işaretler ve nedenini söyler
 * (arayüz {@code dq.tier.<NEDEN>} ile yazar). Zayıf sinyal (ör. WAF açık, dış tedarikçi) tek başına işaret ETMEZ:
 * WAF iç uygulamaların da önünde durur. Üç kural:
 * <ol>
 *   <li>{@link #EV_ON_LOW_TIER} — katman 3–4 (UAT / geliştirme) ama EV sertifikası işaretli VE adında test ortamı
 *       belirteci yok. EV sertifikası müşteriye açık üretim siteleri için alınır.</li>
 *   <li>{@link #NONPROD_NAME_ON_PROD_TIER} — katman 1–2 (üretim) ama alan adının BÜTÜN bir parçası test ortamı
 *       belirteci ({@code test}, {@code uat}, {@code dev}, {@code preprod}, {@code staging}, {@code qa}…). Parça
 *       eşleşmesi tam sözcüktür: {@code developer.example.com} ya da {@code contest.example.com} eşleşmez;
 *       {@code uat-api.example.com}, {@code api.test.example.com}, {@code app-dev01.example.com} eşleşir.</li>
 *   <li>{@link #INTERNAL_CERT_ON_CUSTOMER_TIER} — katman 1 (müşteriye açık üretim) ama "iç sertifika" işaretli:
 *       kurum içi CA'nın sertifikasına müşteri tarayıcısı güvenmez — ya katman ya bayrak yanlıştır.</li>
 * </ol>
 * Katmanı boş kayıt bu kurala girmez (o {@link DataQualityRule#INV_NO_TIER}'ın işi).
 */
public final class TierHeuristics {

    private TierHeuristics() {}

    public static final String EV_ON_LOW_TIER = "EV_ON_LOW_TIER";
    public static final String NONPROD_NAME_ON_PROD_TIER = "NONPROD_NAME_ON_PROD_TIER";
    public static final String INTERNAL_CERT_ON_CUSTOMER_TIER = "INTERNAL_CERT_ON_CUSTOMER_TIER";

    /** Neden kodları (arayüz kopyası ve i18n kapısı bununla karşılaştırır). */
    public static final List<String> REASONS = List.of(EV_ON_LOW_TIER, NONPROD_NAME_ON_PROD_TIER,
            INTERNAL_CERT_ON_CUSTOMER_TIER);

    /** Test / üretim-dışı ortam belirteçleri — sondaki rakamlar atılarak TAM sözcük eşleşir. */
    static final Set<String> NONPROD_TOKENS = Set.of(
            "test", "tst", "uat", "dev", "devel", "preprod", "staging", "stage", "stg", "sandbox", "sbx", "qa");

    /** Ad uzunluğu üst sınırı — envanter anahtarı en çok 253 karakter; fazlası taranmaz. */
    static final int MAX_NAME = 300;

    /** Tek bulgu: neden kodu + (varsa) eşleşen belirteç. */
    public record Finding(String reason, String token) {}

    /** Kayıt için ilk çelişki ya da yoksa {@code null}. */
    public static Finding evaluate(CertificateInventory r) {
        if (r == null || r.getTier() == null) return null;
        int tier = r.getTier();
        String token = nonProdToken(r.getDomain());
        if (tier >= 3 && Boolean.TRUE.equals(r.getEvCertificate()) && token == null) {
            return new Finding(EV_ON_LOW_TIER, null);
        }
        if (tier <= 2 && token != null) return new Finding(NONPROD_NAME_ON_PROD_TIER, token);
        if (tier == 1 && Boolean.TRUE.equals(r.getInternalCert())) return new Finding(INTERNAL_CERT_ON_CUSTOMER_TIER, null);
        return null;
    }

    /**
     * Alan adındaki ilk test ortamı belirteci ya da {@code null}. Doğrusal tarama (regex yok): ad noktalara, her
     * parça {@code -} / {@code _} ile sözcüklere bölünür; sondaki rakamlar atılır; ardışık "pre" + "prod" da
     * {@code preprod} sayılır.
     */
    static String nonProdToken(String name) {
        if (name == null || name.isBlank() || name.length() > MAX_NAME) return null;
        String s = name.trim().toLowerCase(Locale.ROOT);
        int colon = s.indexOf(':');
        if (colon >= 0) s = s.substring(0, colon);   // host:port → host
        List<String> words = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '.' || ch == '-' || ch == '_' || ch == '*') {
                if (!cur.isEmpty()) { words.add(cur.toString()); cur.setLength(0); }
            } else {
                cur.append(ch);
            }
        }
        if (!cur.isEmpty()) words.add(cur.toString());
        String prev = null;
        for (String w : words) {
            String bare = stripDigits(w);
            if (NONPROD_TOKENS.contains(bare)) return bare;
            if ("prod".equals(bare) && "pre".equals(prev)) return "preprod";
            prev = bare;
        }
        return null;
    }

    private static String stripDigits(String w) {
        int end = w.length();
        while (end > 0 && Character.isDigit(w.charAt(end - 1))) end--;
        return w.substring(0, end);
    }
}
