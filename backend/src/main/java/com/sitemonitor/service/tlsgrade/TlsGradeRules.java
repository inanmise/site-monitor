package com.sitemonitor.service.tlsgrade;

import com.sitemonitor.model.LatestCheck;
import com.sitemonitor.model.TlsProfile;
import com.sitemonitor.service.CertificateHealthRules;
import com.sitemonitor.service.CertificateHealthRules.CipherTier;
import com.sitemonitor.service.CertificateHealthRules.Status;
import com.sitemonitor.service.CertificateHealthService;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * TLS YAPILANDIRMA NOTU (A+ … F) — saf, durumsuz, tablo-güdümlü TEK kural kaynağı (2026-10-10, kullanıcı isteği:
 * "protokol sürümü, şifre takımları, OCSP stapling, HSTS ve anahtar boyu tek bir notta toplansın; mevcut sağlık
 * kurallarının üstüne kurulsun").
 *
 * <p><b>Puan UYDURULMAZ, TAVAN uygulanır.</b> Not A+'tan başlar; her bulgu notun çıkabileceği en yüksek değeri (tavan)
 * belirler, sonuç tavanların en düşüğüdür. Yüzde puanı yoktur: elimizde tam şifre takımı taraması yok ve
 * {@link CertificateHealthRules} sınıfının ilkesi ("yüzde skoru UYDURMAK yerine savunulabilir kademe") burada da geçerli.
 * Her not, kendisini sınırlayan nedenleri ({@link Finding}: kod + tavan + parametre) taşır — arayüz "Neden B: TLS 1.0
 * açık" der, kural arayüzde TEKRAR yazılmaz.
 *
 * <p><b>Tavan tablosu</b> (SSL Labs derecelendirme kılavuzundan esinlenilmiş, kurum için sadeleştirilmiş):
 * <ul>
 *   <li><b>F</b> — süresi dolmuş, alan adı uyuşmuyor, güvenilmeyen CA, iptal edilmiş, kırık zincir, zayıf anahtar
 *       (RSA/DSA &lt; 2048, EC &lt; 256), SHA-1/MD5 imza, NULL/anonim/EXPORT takımı anlaşılan ya da kabul edilen.</li>
 *   <li><b>D</b> — modern istemciyle RC4/3DES/DES anlaşılıyor; ya da iki ve daha fazla C düzeyi sorun birlikte
 *       ({@code MULTIPLE_SERIOUS}).</li>
 *   <li><b>C</b> — TLS 1.2 yok; yalnız zayıf takım önerildiğinde sunucu kabul ediyor.</li>
 *   <li><b>B</b> — TLS 1.0 ya da 1.1 açık; ileriye dönük gizlilik (PFS) yok; modern istemciye CBC seçiliyor.</li>
 *   <li><b>A</b> (A+ olamaz) — TLS 1.3 yok; HSTS yok / kısa / hiç bakılmadı; OCSP zımbalama yok; TLS profili henüz
 *       yoklanmadı / yoklanamadı / eksik kaldı.</li>
 *   <li><b>A+</b> — yukarıdakilerin hiçbiri yok: TLS 1.3 açık, ≥ 180 gün HSTS, protokol profili eksiksiz.</li>
 * </ul>
 * A+ isteğe bağlı bir üst basamaktır (kullanıcı A–F dedi; A+ yalnız KANITLANMIŞ en iyi yapılandırmayı ayırır).
 *
 * <p><b>UNKNOWN, FAIL DEĞİLDİR.</b> Bilinmeyen bir şey not düşürmez; ama A+ bir kanıt ister: TLS 1.3 desteği ve HSTS
 * doğrulanmadan A+ verilmez. Zımbalamanın bilinmemesi (ör. yalnız TLS 1.3 konuşan sunucu, şifreli Certificate) hiçbir
 * şey düşürmez. Elle yüklenen (ağ ucu olmayan) sertifika NOTLANMAZ ({@link #NOT_APPLICABLE}) — ağ notu uydurulmaz.
 *
 * <p>Hükümler {@link CertificateHealthRules}'tan alınır (protokol, şifre kademesi, PFS, imza, anahtar boyu, güvenlik
 * bayrakları) ve HSTS eşiği {@link CertificateHealthService#HSTS_MIN_MAX_AGE_SECONDS} — sağlık listesi ile not aynı
 * sertifika için çelişemez.
 */
public final class TlsGradeRules {

    private TlsGradeRules() { }

    /** Kural sürümü — rubrik değişirse artar (arayüz ve günlük hangi kuralla notlandığını bilsin). */
    public static final int RUBRIC_VERSION = 1;

    /** İyiden kötüye. */
    public static final List<String> GRADES = List.of("A+", "A", "B", "C", "D", "F");

    /** Durum: notlandı. */
    public static final String GRADED = "graded";
    /** Durum: uygulanamaz (elle yüklenen sertifika — ağ ucu yok). */
    public static final String NOT_APPLICABLE = "not_applicable";
    /** Durum: veri yok (hiç kontrol edilmedi / son kontrol başarısız). */
    public static final String NO_DATA = "no_data";

    /** Durum gerekçeleri (i18n {@code tlsg.state.<KOD>}). */
    public static final List<String> STATE_CODES = List.of("MANUAL", "NO_CHECK", "CHECK_FAILED");

    /** HSTS'in "uzun" sayıldığı en kısa süre (gün) — sağlık listesinin eşiğiyle aynı. */
    public static final long HSTS_MIN_DAYS = CertificateHealthService.HSTS_MIN_MAX_AGE_SECONDS / 86_400L;

    /** NIST SP 800-57: 2030 sonrası RSA/DSA için asgari bit (bilgi notu; tavan değil). */
    static final int RSA_2030_MIN_BITS = 3072;

    /**
     * Neden kataloğu — SIRA arayüz sırasıdır (aynı tavanda). {@code cap} null = bilgi notu (notu etkilemez). Yeni kod
     * eklenirse TR + EN {@code tlsg.reason.<KOD>.title|why|fix} ve {@code tlsgrade/tlsGradeCodes.js} aynı değişiklikte
     * eklenir ({@code TlsGradeI18nGateTest}).
     */
    public enum Reason {
        CERT_EXPIRED("F"), HOSTNAME_MISMATCH("F"), CERT_UNTRUSTED("F"), CERT_REVOKED("F"), CHAIN_BROKEN("F"),
        KEY_WEAK("F"), SIG_WEAK("F"), CIPHER_INSECURE("F"), INSECURE_CIPHER_ACCEPTED("F"),
        CIPHER_WEAK("D"), MULTIPLE_SERIOUS("D"),
        NO_TLS12("C"), WEAK_CIPHER_ACCEPTED("C"),
        TLS10_ENABLED("B"), TLS11_ENABLED("B"), NO_PFS("B"), CIPHER_CBC("B"),
        NO_TLS13("A"), HSTS_MISSING("A"), HSTS_SHORT("A"), HSTS_NOT_CHECKED("A"), OCSP_STAPLING_MISSING("A"),
        PROFILE_PENDING("A"), PROFILE_FAILED("A"), PROFILE_PARTIAL("A"),
        KEY_2030(null);

        private final String cap;

        Reason(String cap) { this.cap = cap; }

        /** Notun bu nedenle çıkabileceği en yüksek değer; null = bilgi notu. */
        public String cap() { return cap; }
    }

    /** Kodlar, katalog sırasıyla (gate testi ve arayüz kopyası bununla karşılaştırılır). */
    public static final List<String> REASON_CODES = Arrays.stream(Reason.values()).map(Enum::name).toList();

    /** Tek bulgu: kod + tavan (null = bilgi) + i18n parametreleri ({@code {0}}, {@code {1}}). */
    public record Finding(String code, String cap, List<Object> params) {
        public Map<String, Object> toJson() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("code", code);
            m.put("cap", cap);
            if (params != null && !params.isEmpty()) m.put("params", params);
            return m;
        }
    }

    /**
     * Değerlendirme sonucu.
     *
     * @param state       {@link #GRADED} / {@link #NOT_APPLICABLE} / {@link #NO_DATA}
     * @param stateReason notlanmadıysa gerekçe ({@link #STATE_CODES}); notlandıysa null
     * @param grade       "A+" … "F"; notlanmadıysa null
     * @param reasons     notu sınırlayan bulgular, EN KÖTÜ tavan önce
     * @param notes       bilgi notları (tavansız)
     */
    public record Grade(String state, String stateReason, String grade, List<Finding> reasons, List<Finding> notes) {
        public boolean graded() { return GRADED.equals(state) && grade != null; }

        /** Neden kodları, sıralı — durum tablosu / günlük için (virgülle birleştirilir). */
        public List<String> codes() { return reasons.stream().map(Finding::code).toList(); }

        /** Notu TAM OLARAK bu değere indiren nedenler ("Neden B?"). */
        public List<Finding> decisive() {
            return reasons.stream().filter(f -> grade != null && grade.equals(f.cap())).toList();
        }
    }

    // ── Not yardımcıları ──────────────────────────────────────────────────────────────────────

    /** A+ → 6 … F → 1; tanınmayan → 0. Büyük = iyi. */
    public static int rank(String grade) {
        int i = grade == null ? -1 : GRADES.indexOf(grade);
        return i < 0 ? 0 : GRADES.size() - i;
    }

    public static boolean isGrade(String grade) {
        return grade != null && GRADES.contains(grade);
    }

    /** İki nottan kötüsü; null tavan yok sayılır. */
    static String worse(String current, String cap) {
        if (cap == null) return current;
        if (current == null) return cap;
        return rank(cap) < rank(current) ? cap : current;
    }

    // ── Değerlendirme ─────────────────────────────────────────────────────────────────────────

    /** Güvenlik bayrakları son kontrolden hesaplanır (SAN JSON'u ayrıştırılır). */
    public static Grade evaluate(LatestCheck lc, TlsProfile profile, boolean manual) {
        return evaluate(lc, profile, manual, null);
    }

    /**
     * @param lc            son kontrol satırı (null = hiç kontrol edilmedi)
     * @param profile       TLS profili (null = henüz yoklanmadı)
     * @param manual        elle yüklenen sertifika mı
     * @param securityFlags çağıran zaten hesapladıysa ({@code CertificateDto.securityFlags}); null → burada hesaplanır
     */
    public static Grade evaluate(LatestCheck lc, TlsProfile profile, boolean manual, List<String> securityFlags) {
        if (manual) return new Grade(NOT_APPLICABLE, "MANUAL", null, List.of(), List.of());
        if (lc == null) return new Grade(NO_DATA, "NO_CHECK", null, List.of(), List.of());
        if ("error".equalsIgnoreCase(trim(lc.getStatus()))) return new Grade(NO_DATA, "CHECK_FAILED", null, List.of(), List.of());

        List<Finding> r = new ArrayList<>();

        // ── Sertifika ──
        Integer days = lc.getDaysRemaining();
        if (days != null && days < 0) add(r, Reason.CERT_EXPIRED, -days);
        List<String> flags = securityFlags != null ? securityFlags
                : CertificateHealthRules.securityFlags(lc.getDomain(), parseSan(lc.getSan()), lc.getTrustStatus(), false);
        if (flags.contains(CertificateHealthRules.FLAG_HOSTNAME_MISMATCH)) add(r, Reason.HOSTNAME_MISMATCH);
        if (flags.contains(CertificateHealthRules.FLAG_UNTRUSTED_CA)) add(r, Reason.CERT_UNTRUSTED);
        if ("REVOKED".equals(up(lc.getRevocationStatus())) || "REVOKED".equals(up(lc.getChainStatus()))) {
            add(r, Reason.CERT_REVOKED);
        }
        if (CertificateHealthRules.fromStatusLabel(lc.getChainStatus(), "VALID", "BROKEN") == Status.FAIL) {
            add(r, Reason.CHAIN_BROKEN);
        }
        if (CertificateHealthRules.keySizeStatus(lc.getPublicKeyAlgorithm(), lc.getPublicKeySize()) == Status.FAIL) {
            add(r, Reason.KEY_WEAK, trim(lc.getPublicKeyAlgorithm()), lc.getPublicKeySize());
        }
        if (CertificateHealthRules.signatureStatus(lc.getSignatureAlgorithm()) == Status.FAIL) {
            add(r, Reason.SIG_WEAK, trim(lc.getSignatureAlgorithm()));
        }

        // ── Anlaşılan el sıkışması (tarayıcı kipli saatlik kontrol); şifre adı yoksa profilin seçtiği takım ──
        String version = trim(lc.getTlsVersion());
        String cipher = trim(lc.getCipherSuite());
        if (cipher.isEmpty() && profile != null && !isBlank(profile.getPreferredCipher())) {
            cipher = profile.getPreferredCipher().trim();
            if (version.isEmpty()) version = "TLSv1.2";   // profilin tercih takımı TLS 1.2 yoklamasından gelir
        }
        CipherTier tier = CertificateHealthRules.cipherTier(cipher);
        if (tier == CipherTier.WEAK) add(r, isInsecureCipher(cipher) ? Reason.CIPHER_INSECURE : Reason.CIPHER_WEAK, cipher);
        else if (tier == CipherTier.ACCEPTABLE) add(r, Reason.CIPHER_CBC, cipher);
        if (CertificateHealthRules.pfsStatus(version, cipher) == Status.FAIL) add(r, Reason.NO_PFS, cipher);
        boolean legacyNegotiated = CertificateHealthRules.protocolStatus(lc.getTlsVersion()) == Status.FAIL;
        if (legacyNegotiated) add(r, Reason.NO_TLS12, trim(lc.getTlsVersion()));

        // ── TLS profili (günlük yoklama) ──
        if (profile == null) {
            add(r, Reason.PROFILE_PENDING);
        } else if (TlsProfile.STATUS_FAILED.equals(profile.getStatus()) || TlsProfile.STATUS_BLOCKED.equals(profile.getStatus())) {
            add(r, Reason.PROFILE_FAILED, profile.getStatus());
        } else {
            if (TlsProfile.YES.equals(profile.getTls10())) add(r, Reason.TLS10_ENABLED);
            if (TlsProfile.YES.equals(profile.getTls11())) add(r, Reason.TLS11_ENABLED);
            if (!legacyNegotiated && TlsProfile.NO.equals(profile.getTls12())) add(r, Reason.NO_TLS12, "");
            if (TlsProfile.NO.equals(profile.getTls13())) add(r, Reason.NO_TLS13);
            if (TlsProfile.YES.equals(profile.getWeakCipher())) {
                String suite = trim(profile.getWeakCipherSuite());
                add(r, isInsecureCipher(suite) ? Reason.INSECURE_CIPHER_ACCEPTED : Reason.WEAK_CIPHER_ACCEPTED, suite);
            }
            if (TlsProfile.NO.equals(profile.getOcspStapling())) add(r, Reason.OCSP_STAPLING_MISSING);
            if (!known(profile.getTls10()) || !known(profile.getTls11())
                    || !known(profile.getTls12()) || !known(profile.getTls13())) {
                add(r, Reason.PROFILE_PARTIAL);
            }
        }

        // ── HSTS (sağlık satırıyla aynı ayrım) ──
        hsts(lc, r);

        // ── Yığılma: iki ve daha fazla C düzeyi sorun → D ──
        long serious = r.stream().filter(f -> "C".equals(f.cap())).count();
        if (serious >= 2) add(r, Reason.MULTIPLE_SERIOUS, serious);

        String grade = "A+";
        for (Finding f : r) grade = worse(grade, f.cap());

        r.sort(Comparator.comparingInt((Finding f) -> rank(f.cap()))
                .thenComparingInt(f -> Reason.valueOf(f.code()).ordinal()));

        List<Finding> notes = new ArrayList<>();
        Integer size = lc.getPublicKeySize();
        String keyAlg = up(lc.getPublicKeyAlgorithm());
        if ((keyAlg.contains("RSA") || keyAlg.contains("DSA")) && size != null && size >= 2048 && size < RSA_2030_MIN_BITS) {
            notes.add(new Finding(Reason.KEY_2030.name(), null, List.of(trim(lc.getPublicKeyAlgorithm()), size)));
        }
        return new Grade(GRADED, null, grade, List.copyOf(r), List.copyOf(notes));
    }

    /**
     * HSTS satırı: {@code ENABLED} + max-age ≥ 180 gün → sorun yok; kısa → {@code HSTS_SHORT}; {@code MISSING} (başlık yok,
     * max-age=0 ya da max-age'siz geçersiz başlık) → {@code HSTS_MISSING}; hiç bakılmadı / belirlenemedi →
     * {@code HSTS_NOT_CHECKED}. HSTS yalnız kullanıcı "Şimdi kontrol et" dediğinde ölçülür (K4) — not otomatik başlık çekmez.
     */
    private static void hsts(LatestCheck lc, List<Finding> r) {
        String status = up(lc.getHstsStatus());
        if ("ENABLED".equals(status)) {
            Map<String, Object> policy = CertificateHealthService.parseJsonMap(lc.getHstsPolicy());
            Long maxAge = policy.get("max_age") instanceof Number n ? Long.valueOf(n.longValue()) : null;
            if (maxAge != null && maxAge > 0 && maxAge < CertificateHealthService.HSTS_MIN_MAX_AGE_SECONDS) {
                add(r, Reason.HSTS_SHORT, maxAge / 86_400L);
            }
            return;
        }
        if ("MISSING".equals(status)) {
            add(r, Reason.HSTS_MISSING);
            return;
        }
        add(r, Reason.HSTS_NOT_CHECKED);
    }

    /** NULL / anonim / EXPORT takımı — şifreleme ya da kimlik doğrulama fiilen yok (F). RC4/3DES/DES ise "zayıf" (D/C). */
    static boolean isInsecureCipher(String cipher) {
        String c = up(cipher);
        return c.contains("NULL") || c.contains("ANON") || c.contains("EXPORT");
    }

    private static boolean known(String v) {
        return TlsProfile.YES.equals(v) || TlsProfile.NO.equals(v);
    }

    private static void add(List<Finding> r, Reason reason, Object... params) {
        for (Finding f : r) if (f.code().equals(reason.name())) return;   // aynı neden bir kez
        List<Object> p = new ArrayList<>(params.length);
        for (Object o : params) p.add(o == null ? "" : o);
        r.add(new Finding(reason.name(), reason.cap(), List.copyOf(p)));
    }

    /** SAN JSON dizisi → liste; bozuk/boş → boş liste (bayrak UNKNOWN'a düşer, çökmez). */
    static List<String> parseSan(String json) {
        if (isBlank(json)) return List.of();
        try {
            Object v = SAN_JSON.readValue(json, List.class);
            List<String> out = new ArrayList<>();
            if (v instanceof List<?> l) for (Object o : l) if (o != null) out.add(o.toString());
            return out;
        } catch (Exception e) {
            return List.of();
        }
    }

    private static final com.fasterxml.jackson.databind.ObjectMapper SAN_JSON = new com.fasterxml.jackson.databind.ObjectMapper();

    private static String up(String s) { return s == null ? "" : s.trim().toUpperCase(Locale.ROOT); }
    private static String trim(String s) { return s == null ? "" : s.trim(); }
    private static boolean isBlank(String s) { return s == null || s.trim().isEmpty(); }
}
