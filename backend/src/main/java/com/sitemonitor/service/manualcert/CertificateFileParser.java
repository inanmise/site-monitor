package com.sitemonitor.service.manualcert;

import com.sitemonitor.service.CertificateFacts;
import org.bouncycastle.asn1.ASN1Encodable;
import org.bouncycastle.asn1.ASN1InputStream;
import org.bouncycastle.asn1.ASN1Integer;
import org.bouncycastle.asn1.ASN1ObjectIdentifier;
import org.bouncycastle.asn1.ASN1OctetString;
import org.bouncycastle.asn1.ASN1Primitive;
import org.bouncycastle.asn1.ASN1Sequence;
import org.bouncycastle.asn1.pkcs.Attribute;
import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.Extensions;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.operator.DefaultAlgorithmNameFinder;
import org.bouncycastle.pkcs.PKCS10CertificationRequest;
import org.bouncycastle.pkcs.jcajce.JcaPKCS10CertificationRequest;

import javax.security.auth.x500.X500Principal;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.PublicKey;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Yüklenen sertifika dosyasını OKUR — biçim uzantıdan değil İÇERİKTEN tanınır (2026-10-06, manuel sertifika takibi).
 *
 * <p><b>Özel anahtar sunucuya GELMEZ (2026-10-08, kullanıcı isteği: "keystore yüklemesi yapılmaya çalışıldığında kesinlikle
 * private key için bir yükleme vs yapmayalım"):</b> arayüz dosyayı tarayıcıda açar ve yalnız açık sertifikaları
 * ({@code extracted}) gönderir — bu yol {@link #fromExtracted}. Ham dosya yolu ({@code file} / {@code text}, API
 * istemcileri için) yalnız ANAHTARSIZ içeriği kabul eder: PEM sertifika / PKCS7 / CSR blokları, zırhsız Base64 DER, DER
 * X.509, PKCS#7 (PEM/DER) ve bunları taşıyan ZIP. PKCS#12, JKS / JCEKS / BKS anahtar deposu ya da HERHANGİ bir özel
 * anahtar (her türden {@code PRIVATE KEY} PEM bloğu, PKCS#8 / PKCS#1 / SEC1 DER) — ZIP içinde de — yalnız TANINIR
 * ({@link Result#privateMaterial()}), hiçbir şekilde açılmaz, çözülmez; çağıran isteği reddeder
 * ({@code PRIVATE_KEY_NOT_ACCEPTED}). Sunucu artık parola almaz, anahtar deposu açmaz.
 *
 * <p>Saf ve durumsuz: ağ yok, veritabanı yok. Süre sınırı (zaman kutusu) çağıran serviste uygulanır.
 */
public final class CertificateFileParser {

    private CertificateFileParser() { }

    /** Tek dosya / yapıştırılan metin üst sınırı (sözleşme: 5 MB). */
    public static final int MAX_BYTES = 5 * 1024 * 1024;
    public static final int MAX_MB = 5;
    /** ZIP sınırları (sözleşme): en çok 50 girdi, toplam 5 MB açılmış veri. */
    public static final int ZIP_MAX_ENTRIES = 50;
    public static final long ZIP_MAX_TOTAL_BYTES = 5L * 1024 * 1024;
    /** Sıkıştırma oranı bundan büyük girdi "zip bombası" sayılır (sertifikalar ~1-3 kat sıkışır). */
    static final int ZIP_MAX_RATIO = 200;

    /**
     * Uyarı kodu KATALOĞU (sözleşme) — arayüz {@code mcert.warn.<KOD>} anahtarını dinamik çevirir; her kodun TR + EN
     * metni olmalı ({@code ManualCertWarningI18nGateTest}). Yeni kod buraya ve iki sözlüğe AYNI değişiklikte eklenir.
     * {@code PASSWORD_REQUIRED} / {@code PASSWORD_WRONG}: sunucu artık üretmez (parola tarayıcıda kalır); arayüzün
     * tarayıcı notları aynı sözlüğü kullanır (JKS bütünlük uyumsuzluğu).
     */
    public static final List<String> WARNING_CODES = List.of(
            "PRIVATE_KEY_KEPT_LOCAL", "CSR_NOT_CERTIFICATE", "NO_CERTIFICATE", "PASSWORD_REQUIRED", "PASSWORD_WRONG",
            "UNSUPPORTED_FORMAT", "FILE_TOO_LARGE", "ZIP_LIMIT", "ZIP_SKIPPED_ENTRY", "EXPIRED", "NOT_YET_VALID",
            "EXPIRES_SOON", "SELF_SIGNED", "CHAIN_INCOMPLETE", "CHAIN_EXPIRED_INTERMEDIATE", "WEAK_SIGNATURE", "WEAK_KEY",
            "CA_CERTIFICATE", "MULTIPLE_LEAVES", "DUPLICATE_IN_FILE", "ALREADY_TRACKED", "SAME_SUBJECT_TRACKED",
            "NETWORK_MONITORED", "KEY_CHANGED", "KEY_SAME", "SUBJECT_CHANGED", "OLDER_THAN_CURRENT", "SAN_CHANGED");

    public static final String SEV_INFO = "info";
    public static final String SEV_WARN = "warn";
    public static final String SEV_ERROR = "error";

    /** Ham yolda tanınan (ve reddedilen) gizli malzeme türleri. */
    public static final String SECRET_PKCS12 = "PKCS12";
    public static final String SECRET_JKS = "JKS";
    public static final String SECRET_JCEKS = "JCEKS";
    public static final String SECRET_BKS = "BKS";
    public static final String SECRET_PRIVATE_KEY = "PRIVATE_KEY";

    /** Dosya/girdi uyarısı — arayüz {@code mcert.warn.<code>} anahtarını parametrelerle çizer. */
    public record Warning(String code, String severity, Map<String, Object> params) {
        public Map<String, Object> toJson() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("code", code);
            m.put("severity", severity);
            m.put("params", params == null ? Map.of() : params);
            return m;
        }
    }

    /** Dosyada bulunan bir sertifika (henüz tekilleştirilmemiş). */
    public record ParsedCert(X509Certificate cert, String alias, boolean keyEntry, String source) { }

    /** Sertifika yerine yüklenen CSR'nin özeti. */
    public record CsrInfo(String cn, String subjectDn, List<String> san, String keyAlg, Integer keySize,
                          String signatureAlgorithm) {
        public Map<String, Object> toJson() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("cn", cn);
            m.put("subject_dn", subjectDn);
            m.put("san", san == null ? List.of() : san);
            m.put("key_alg", keyAlg);
            m.put("key_size", keySize);
            m.put("signature_algorithm", signatureAlgorithm);
            return m;
        }
    }

    /** Ayrıştırma sonucu. */
    public static final class Result {
        String format;
        String fileName;
        long sizeBytes;
        CsrInfo csr;
        int privateKeyCount;
        /** Ham yolda görülen ilk gizli malzemenin türü ({@code SECRET_*}); yoksa null. */
        String privateMaterial;
        boolean rejected;
        final List<Warning> warnings = new ArrayList<>();
        final List<ParsedCert> certs = new ArrayList<>();

        public String format() { return format; }
        public String fileName() { return fileName; }
        public long sizeBytes() { return sizeBytes; }
        public CsrInfo csr() { return csr; }
        public int privateKeyCount() { return privateKeyCount; }
        /** Ham yükleme özel anahtar / anahtar deposu taşıyor mu (taşıyorsa istek REDDEDİLİR) — tür ya da null. */
        public String privateMaterial() { return privateMaterial; }
        public List<Warning> warnings() { return Collections.unmodifiableList(warnings); }
        public List<ParsedCert> certs() { return Collections.unmodifiableList(certs); }

        void markSecret(String kind) {
            if (privateMaterial == null) privateMaterial = kind;
        }
    }

    // ── Tarayıcıda ayıklanmış yükleme ────────────────────────────────────────

    /**
     * Tarayıcının ayıkladığı AÇIK sertifikalar → sonuç (2026-10-08). Doğrulama çağıranda ({@link ExtractedUpload});
     * burada yalnız sonuç kurulur: özel anahtar sayısı bilgi uyarısı {@code PRIVATE_KEY_KEPT_LOCAL {count}} olur.
     */
    public static Result fromExtracted(String format, String fileName, long sizeBytes, List<ParsedCert> certs, CsrInfo csr,
                                       int privateKeysRemoved) {
        Result r = new Result();
        r.format = format;
        r.fileName = sanitizeFileName(fileName);
        r.sizeBytes = Math.max(0, sizeBytes);
        r.csr = csr;
        if (certs != null) r.certs.addAll(certs);
        if (privateKeysRemoved > 0) {
            r.warnings.add(new Warning("PRIVATE_KEY_KEPT_LOCAL", SEV_INFO, Map.of("count", privateKeysRemoved)));
        }
        finish(r);
        return r;
    }

    // ── Ham yükleme (API istemcileri) ────────────────────────────────────────

    /**
     * @param data     dosya baytları ya da yapıştırılan metnin UTF-8 baytları
     * @param fileName istemcinin bildirdiği dosya adı (yalnız gösterim + uyarı; biçim İÇERİKTEN tanınır)
     * @param pasted   girdi yapıştırılan metin mi (biçim {@code TEXT} raporlanır)
     */
    public static Result parse(byte[] data, String fileName, boolean pasted) {
        Result r = new Result();
        r.fileName = pasted ? null : sanitizeFileName(fileName);
        r.sizeBytes = data == null ? 0 : data.length;
        if (data == null || data.length == 0) {
            r.format = pasted ? "TEXT" : null;
            r.warnings.add(new Warning("NO_CERTIFICATE", SEV_ERROR, Map.of()));
            return r;
        }
        if (data.length > MAX_BYTES) {
            r.format = pasted ? "TEXT" : null;
            r.rejected = true;
            r.warnings.add(new Warning("FILE_TOO_LARGE", SEV_ERROR, Map.of("max_mb", MAX_MB)));
            return r;
        }
        if (isZip(data)) {
            r.format = "ZIP";
            parseZip(data, r);
        } else {
            String fmt = parseBlob(data, r, null, true);
            r.format = pasted ? "TEXT" : fmt;
            if (fmt == null && !r.rejected && r.privateMaterial == null) {
                r.rejected = true;
                r.warnings.add(new Warning("UNSUPPORTED_FORMAT", SEV_ERROR,
                        Map.of("name", r.fileName == null ? "" : r.fileName)));
            }
        }
        finish(r);
        return r;
    }

    private static void finish(Result r) {
        if (r.privateMaterial != null) r.rejected = true;   // gizli malzeme: hiçbir sertifika kullanılmaz, istek reddedilir
        if (r.rejected) {
            r.certs.clear();
            return;
        }
        if (r.certs.isEmpty()) {
            if (r.csr != null) {
                r.warnings.add(new Warning("CSR_NOT_CERTIFICATE", SEV_ERROR,
                        Map.of("cn", r.csr.cn() == null ? "" : r.csr.cn())));
            } else {
                r.warnings.add(new Warning("NO_CERTIFICATE", SEV_ERROR, Map.of()));
            }
        }
    }

    // ── Biçim tanıma ─────────────────────────────────────────────────────────

    /**
     * Tek bir blob (dosya ya da ZIP girdisi) — tanınan biçimi döner; tanınmazsa null. Anahtar deposu / PKCS#12 / özel
     * anahtar yalnız İMZASINDAN tanınır ve işaretlenir ({@link Result#markSecret}); içeriği okunmaz.
     *
     * @param allowBase64 zırhsız Base64 denemesine izin (özyinelemede bir kez)
     */
    static String parseBlob(byte[] data, Result r, String source, boolean allowBase64) {
        String text = asciiText(data);
        if (text != null && text.contains("-----BEGIN ")) {
            parsePem(text, r, source);
            return "PEM";
        }
        if (startsWith(data, 0xFE, 0xED, 0xFE, 0xED)) {
            r.markSecret(SECRET_JKS);
            return "JKS";
        }
        if (startsWith(data, 0xCE, 0xCE, 0xCE, 0xCE)) {
            r.markSecret(SECRET_JCEKS);
            return "JCEKS";
        }
        if ((data[0] & 0xFF) == 0x30) {
            String der = parseDer(data, r, source);
            if (der != null) return der;
        }
        if (looksLikeBks(data)) {
            r.markSecret(SECRET_BKS);
            return "BKS";
        }
        if (allowBase64 && text != null) {
            byte[] decoded = decodeLooseBase64(text);
            if (decoded != null && decoded.length > 0) {
                String inner = parseBlob(decoded, r, source, false);
                if (inner != null) return inner;
            }
        }
        return null;
    }

    /** ASN.1 DER: X.509, PKCS#7 SignedData, PKCS#12 PFX (yalnız tanınır), PKCS#10 CSR ya da özel anahtar (yalnız tanınır). */
    private static String parseDer(byte[] data, Result r, String source) {
        ASN1Sequence seq;
        try (ASN1InputStream in = new ASN1InputStream(data)) {
            ASN1Primitive p = in.readObject();
            if (!(p instanceof ASN1Sequence s)) return null;
            seq = s;
        } catch (Exception e) {
            return null;
        }
        if (seq.size() == 0) return null;
        ASN1Encodable first = seq.getObjectAt(0);
        // PKCS#7 ContentInfo: SEQUENCE { contentType OID, [0] content }
        if (first instanceof ASN1ObjectIdentifier oid) {
            if (PKCSObjectIdentifiers.signedData.equals(oid)) {
                parsePkcs7(data, r, source);
                return "PKCS7";
            }
            return null;
        }
        // PKCS#12 PFX: SEQUENCE { version INTEGER (3), authSafe ContentInfo, macData? } — AÇILMAZ, yalnız tanınır
        if (first instanceof ASN1Integer v && seq.size() >= 2 && seq.size() <= 3 && v.getValue().intValue() == 3
                && seq.getObjectAt(1) instanceof ASN1Sequence ci && ci.size() >= 1
                && ci.getObjectAt(0) instanceof ASN1ObjectIdentifier) {
            r.markSecret(SECRET_PKCS12);
            return "PKCS12";
        }
        // X.509 sertifikası ya da PKCS#10 CSR — ikisi de SEQUENCE { SEQUENCE, AlgorithmIdentifier, BIT STRING }
        X509Certificate cert = toX509(data);
        if (cert != null) {
            r.certs.add(new ParsedCert(cert, null, false, source));
            return "DER";
        }
        CsrInfo csr = parseCsr(data);
        if (csr != null) {
            if (r.csr == null) r.csr = csr;
            return "DER";
        }
        if (looksLikePrivateKey(seq)) {
            r.privateKeyCount++;
            r.markSecret(SECRET_PRIVATE_KEY);
            return "DER";
        }
        return null;
    }

    /** PKCS#8 (şifreli / şifresiz), PKCS#1 RSA, SEC1 EC, DSA özel anahtar ŞEKLİ — içerik okunmaz. */
    static boolean looksLikePrivateKey(ASN1Sequence seq) {
        try {
            int n = seq.size();
            ASN1Encodable a = seq.getObjectAt(0);
            // EncryptedPrivateKeyInfo: SEQUENCE { AlgorithmIdentifier, OCTET STRING }
            if (n == 2 && a instanceof ASN1Sequence alg && alg.size() >= 1 && alg.getObjectAt(0) instanceof ASN1ObjectIdentifier
                    && seq.getObjectAt(1) instanceof ASN1OctetString) return true;
            if (!(a instanceof ASN1Integer v)) return false;
            int ver = v.getValue().bitLength() > 8 ? -1 : v.getValue().intValue();
            // PKCS#8 PrivateKeyInfo / OneAsymmetricKey: SEQUENCE { INTEGER 0|1, AlgorithmIdentifier, OCTET STRING, … }
            if ((ver == 0 || ver == 1) && n >= 3 && seq.getObjectAt(1) instanceof ASN1Sequence
                    && seq.getObjectAt(2) instanceof ASN1OctetString) return true;
            // SEC1 ECPrivateKey: SEQUENCE { INTEGER 1, OCTET STRING, [0]?, [1]? }
            if (ver == 1 && n >= 2 && seq.getObjectAt(1) instanceof ASN1OctetString) return true;
            // PKCS#1 RSAPrivateKey (9 tamsayı) / DSA (6 tamsayı)
            if (ver == 0 && (n == 9 || n == 6)) {
                for (int i = 0; i < n; i++) if (!(seq.getObjectAt(i) instanceof ASN1Integer)) return false;
                return true;
            }
        } catch (Exception ignore) { /* tanınmadı */ }
        return false;
    }

    /** BouncyCastle BKS / UBER başlığı: sürüm (1|2) + tuz uzunluğu (1..1024) + tuz + yineleme sayısı. */
    static boolean looksLikeBks(byte[] b) {
        if (b.length < 16 || b[0] != 0 || b[1] != 0 || b[2] != 0 || (b[3] != 1 && b[3] != 2)) return false;
        int saltLen = ((b[4] & 0xFF) << 24) | ((b[5] & 0xFF) << 16) | ((b[6] & 0xFF) << 8) | (b[7] & 0xFF);
        if (saltLen < 1 || saltLen > 1024 || 8L + saltLen + 4 > b.length) return false;
        int o = 8 + saltLen;
        long iter = (((long) (b[o] & 0xFF)) << 24) | ((b[o + 1] & 0xFF) << 16) | ((b[o + 2] & 0xFF) << 8) | (b[o + 3] & 0xFF);
        return iter > 0 && iter < 10_000_000L;
    }

    // ── PEM ──────────────────────────────────────────────────────────────────

    // Sonsuz bekleme savunması (2026-10-09, ReDoS): eski iki kalıp (tembel DOTALL gövde + geri başvuru; etiketin iki yanında
    // yıldız) eşi olmayan HER BEGIN'de metnin sonuna dek tarıyordu — 5 MB'lık "-----BEGIN X-----" yığını dakikalarca CPU
    // yakıyordu ve java.util.regex kesmeye (Future.cancel) bakmadığı için 10 sn'lik süre kutusu bile onu durduramıyordu.
    // Şimdi işaretçiler TEK geçişte toplanır (etiket ≤ 64 karakter — gerçek etiketlerin en uzunu ~25) ve her BEGIN aynı
    // etiketli İLK END ile eşleşir: eski kalıbın anlamı birebir — eşi olmayan BEGIN atlanır, eşleşen bloktan sonra tarama
    // END'in ardından sürer; özel anahtar başlıkları (kapanışı bozuk olsa da) yine sayılır.
    private static final Pattern PEM_MARKER = Pattern.compile("-----(BEGIN|END) ([A-Z0-9 .]{1,64})-----");

    /** PEM bloğu: ham etiket + gövde metni (çözülmemiş). */
    record PemBlock(String label, String body) {}

    /** {@link #scanPem} sonucu: bloklar (sırasıyla) + özel anahtar BAŞLIĞI sayısı. */
    record PemScan(List<PemBlock> blocks, int privateKeyHeaders) {}

    /** Doğrusal PEM taraması — eski {@code PEM_BLOCK} / {@code PRIVATE_BEGIN} kalıplarının çıktısıyla aynı. */
    static PemScan scanPem(String text) {
        List<int[]> spans = new ArrayList<>();              // [başlangıç, bitiş, BEGIN mi (1/0)]
        List<String> labels = new ArrayList<>();
        Map<String, List<int[]>> endsByLabel = new HashMap<>();
        int privateHeaders = 0;
        int privateEnd = -1;
        Matcher m = PEM_MARKER.matcher(text);
        int from = 0;
        while (from < text.length() && m.find(from)) {
            boolean begin = "BEGIN".equals(m.group(1));
            String label = m.group(2);
            spans.add(new int[]{m.start(), m.end(), begin ? 1 : 0});
            labels.add(label);
            if (!begin) endsByLabel.computeIfAbsent(label, k -> new ArrayList<>()).add(new int[]{m.start(), m.end()});
            // Eski PRIVATE_BEGIN find() döngüsü örtüşmeyen eşleşmeleri sayıyordu.
            if (begin && m.start() >= privateEnd && label.contains("PRIVATE KEY")) {
                privateHeaders++;
                privateEnd = m.end();
            }
            from = m.start() + 1;                            // işaretçiler tire dizisini paylaşabilir (eski tarama gibi)
        }
        List<PemBlock> blocks = new ArrayList<>();
        Map<String, Integer> next = new HashMap<>();         // etiket başına END göstergesi — tekdüze ilerler (doğrusal)
        int pos = 0;
        for (int i = 0; i < spans.size(); i++) {
            int[] sp = spans.get(i);
            if (sp[2] == 0 || sp[0] < pos) continue;
            String label = labels.get(i);
            List<int[]> ends = endsByLabel.get(label);
            if (ends == null) continue;
            int p = next.getOrDefault(label, 0);
            while (p < ends.size() && ends.get(p)[0] < sp[1]) p++;
            next.put(label, p);
            if (p == ends.size()) continue;                  // eşi yok → bu BEGIN atlanır
            int[] end = ends.get(p);
            blocks.add(new PemBlock(label, text.substring(sp[1], end[0])));
            pos = end[1];
        }
        return new PemScan(blocks, privateHeaders);
    }

    static void parsePem(String text, Result r, String source) {
        PemScan scan = scanPem(text);
        for (int i = 0; i < scan.privateKeyHeaders(); i++) {  // RSA/EC/DSA/ENCRYPTED/OPENSSH/… — sayılır, OKUNMAZ
            r.privateKeyCount++;
            r.markSecret(SECRET_PRIVATE_KEY);
        }
        for (PemBlock block : scan.blocks()) {
            String label = block.label().trim().toUpperCase(Locale.ROOT);
            if (label.contains("PRIVATE KEY")) continue;
            byte[] der = pemBody(block.body());
            if (der == null) continue;
            switch (label) {
                case "CERTIFICATE", "X509 CERTIFICATE", "X.509 CERTIFICATE" -> {
                    X509Certificate c = toX509(der);
                    if (c != null) r.certs.add(new ParsedCert(c, null, false, source));
                }
                case "TRUSTED CERTIFICATE" -> {
                    // OpenSSL "trusted certificate": DER sertifika + ardından güven ek bilgisi (CertAux) — ilk nesne alınır.
                    X509Certificate c = toX509(firstAsn1Object(der));
                    if (c != null) r.certs.add(new ParsedCert(c, null, false, source));
                }
                case "PKCS7", "CMS" -> parsePkcs7(der, r, source);
                case "CERTIFICATE REQUEST", "NEW CERTIFICATE REQUEST" -> {
                    CsrInfo csr = parseCsr(der);
                    if (csr != null && r.csr == null) r.csr = csr;
                }
                default -> { /* PUBLIC KEY, X509 CRL, DH PARAMETERS … — sertifika değil, yok sayılır */ }
            }
        }
    }

    /** PEM gövdesi → DER (RFC 1421 başlık satırları atlanır). Bozuk Base64 → null. */
    private static byte[] pemBody(String body) {
        StringBuilder b64 = new StringBuilder(body.length());
        for (String line : body.split("\\R")) {
            String t = line.trim();
            if (t.isEmpty() || t.contains(":")) continue;   // Proc-Type: / DEK-Info: başlıkları
            b64.append(t);
        }
        try {
            return Base64.getMimeDecoder().decode(b64.toString());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static byte[] firstAsn1Object(byte[] der) {
        try (ASN1InputStream in = new ASN1InputStream(der)) {
            ASN1Primitive p = in.readObject();
            return p == null ? null : p.getEncoded();
        } catch (Exception e) {
            return null;
        }
    }

    // ── PKCS#7 ───────────────────────────────────────────────────────────────

    private static void parsePkcs7(byte[] der, Result r, String source) {
        try {
            Collection<? extends Certificate> certs = CertificateFactory.getInstance("X.509")
                    .generateCertificates(new ByteArrayInputStream(der));
            for (Certificate c : certs) {
                if (c instanceof X509Certificate x) r.certs.add(new ParsedCert(x, null, false, source));
            }
        } catch (Exception e) {
            // Bozuk PKCS#7: sertifika yok → üst katman NO_CERTIFICATE der.
        }
    }

    // ── ZIP ──────────────────────────────────────────────────────────────────

    private static void parseZip(byte[] data, Result r) {
        int entries = 0;
        long total = 0;
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(data))) {
            ZipEntry e;
            while ((e = zis.getNextEntry()) != null) {
                if (e.isDirectory()) continue;
                entries++;
                if (entries > ZIP_MAX_ENTRIES) {
                    zipLimit(r);
                    return;
                }
                String name = sanitizeFileName(e.getName());
                if (isArchiveName(name)) {
                    r.warnings.add(new Warning("ZIP_SKIPPED_ENTRY", SEV_INFO, Map.of("name", name == null ? "" : name)));
                    continue;
                }
                byte[] buf = readLimited(zis, ZIP_MAX_TOTAL_BYTES - total);
                if (buf == null) {
                    zipLimit(r);
                    return;
                }
                total += buf.length;
                long compressed = e.getCompressedSize();
                if (compressed > 0 && buf.length / compressed > ZIP_MAX_RATIO) {
                    zipLimit(r);
                    return;
                }
                if (buf.length == 0 || isZip(buf) || isGzip(buf)) {
                    r.warnings.add(new Warning("ZIP_SKIPPED_ENTRY", SEV_INFO, Map.of("name", name == null ? "" : name)));
                    continue;
                }
                int certs = r.certs.size(), keys = r.privateKeyCount;
                boolean hadCsr = r.csr != null;
                String hadSecret = r.privateMaterial;
                String fmt = null;
                try {
                    fmt = parseBlob(buf, r, name, true);
                } catch (Exception ignore) { /* bozuk girdi atlanır */ }
                boolean contributed = r.certs.size() > certs || r.privateKeyCount > keys
                        || (r.csr != null && !hadCsr) || !java.util.Objects.equals(hadSecret, r.privateMaterial);
                if (fmt == null || !contributed) {
                    r.warnings.add(new Warning("ZIP_SKIPPED_ENTRY", SEV_INFO, Map.of("name", name == null ? "" : name)));
                }
            }
        } catch (IOException ex) {
            if (r.certs.isEmpty()) {
                r.rejected = true;
                r.warnings.add(new Warning("UNSUPPORTED_FORMAT", SEV_ERROR,
                        Map.of("name", r.fileName == null ? "" : r.fileName)));
            }
        }
    }

    /**
     * Sınır aşıldı: okuma DURUR (kalan girdiler açılmaz — sıkıştırma bombası belleği büyütemez); sınıra kadar okunan
     * sertifikalar kalır (arayüz metni: "yalnız sınır içindeki dosyalar okundu").
     */
    private static void zipLimit(Result r) {
        r.warnings.add(new Warning("ZIP_LIMIT", SEV_WARN,
                Map.of("max_entries", ZIP_MAX_ENTRIES, "max_mb", MAX_MB)));
    }

    /** En çok {@code remaining} bayt okur; aşılırsa null (girdi sınırı). Okuma açılmış (inflate edilmiş) bayttır. */
    private static byte[] readLimited(InputStream in, long remaining) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        long read = 0;
        int n;
        while ((n = in.read(buf)) > 0) {
            read += n;
            if (read > remaining) return null;
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    private static boolean isArchiveName(String name) {
        if (name == null) return false;
        String l = name.toLowerCase(Locale.ROOT);
        return l.endsWith(".zip") || l.endsWith(".jar") || l.endsWith(".war") || l.endsWith(".gz")
                || l.endsWith(".tgz") || l.endsWith(".tar") || l.endsWith(".7z") || l.endsWith(".rar")
                || l.endsWith(".bz2") || l.endsWith(".xz");
    }

    // ── CSR ──────────────────────────────────────────────────────────────────

    static CsrInfo parseCsr(byte[] der) {
        try {
            PKCS10CertificationRequest req = new PKCS10CertificationRequest(der);
            String subjectDn = new X500Principal(req.getSubject().getEncoded()).getName();
            String cn = CertificateFacts.extractCn(subjectDn);
            List<String> san = new ArrayList<>();
            for (Attribute a : req.getAttributes(PKCSObjectIdentifiers.pkcs_9_at_extensionRequest)) {
                if (a.getAttrValues() == null || a.getAttrValues().size() == 0) continue;
                Extensions exts = Extensions.getInstance(a.getAttrValues().getObjectAt(0));
                GeneralNames gns = GeneralNames.fromExtensions(exts, Extension.subjectAlternativeName);
                if (gns == null) continue;
                for (GeneralName gn : gns.getNames()) {
                    if (gn.getTagNo() == GeneralName.dNSName) san.add(gn.getName().toString());
                }
            }
            String keyAlg = null;
            Integer keySize = null;
            try {
                PublicKey pk = new JcaPKCS10CertificationRequest(req).getPublicKey();
                keyAlg = pk.getAlgorithm();
                int s = CertificateFacts.publicKeySize(pk);
                keySize = s > 0 ? s : null;
            } catch (Exception ignore) { /* anahtar okunamadı — alan boş kalır */ }
            String sig;
            try {
                sig = new DefaultAlgorithmNameFinder().getAlgorithmName(req.getSignatureAlgorithm());
            } catch (Exception e) {
                sig = req.getSignatureAlgorithm().getAlgorithm().getId();
            }
            return new CsrInfo("Unknown".equals(cn) ? null : cn, subjectDn, san, keyAlg, keySize, sig);
        } catch (Exception e) {
            return null;
        }
    }

    // ── Yardımcılar ──────────────────────────────────────────────────────────

    static X509Certificate toX509(byte[] der) {
        if (der == null || der.length == 0) return null;
        try {
            Certificate c = CertificateFactory.getInstance("X.509").generateCertificate(new ByteArrayInputStream(der));
            return c instanceof X509Certificate x ? x : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** Sertifika listesi → PEM (yalnız AÇIK sertifikalar). */
    public static String toPem(List<X509Certificate> certs) {
        StringBuilder sb = new StringBuilder();
        Base64.Encoder enc = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII));
        for (X509Certificate c : certs) {
            try {
                sb.append("-----BEGIN CERTIFICATE-----\n")
                  .append(enc.encodeToString(c.getEncoded()))
                  .append("\n-----END CERTIFICATE-----\n");
            } catch (Exception ignore) { /* kodlanamayan sertifika yazılmaz */ }
        }
        return sb.toString();
    }

    /** Saklanan PEM zinciri → sertifikalar (sıra korunur). */
    public static List<X509Certificate> readPemChain(String pem) {
        List<X509Certificate> out = new ArrayList<>();
        if (pem == null || pem.isBlank()) return out;
        Result r = new Result();
        parsePem(pem, r, null);
        for (ParsedCert pc : r.certs) out.add(pc.cert());
        return out;
    }

    /** Yol parçalarını ve denetim karakterlerini atar; ≤ 255; boşsa null. */
    public static String sanitizeFileName(String name) {
        if (name == null) return null;
        String n = name.replace('\\', '/');
        int slash = n.lastIndexOf('/');
        if (slash >= 0) n = n.substring(slash + 1);
        StringBuilder sb = new StringBuilder(n.length());
        for (char c : n.toCharArray()) {
            if (Character.isISOControl(c) || "<>:\"|?*".indexOf(c) >= 0) continue;
            sb.append(c);
        }
        String out = sb.toString().trim();
        if (out.length() > 255) out = out.substring(out.length() - 255);
        return out.isEmpty() ? null : out;
    }

    private static boolean isZip(byte[] d) {
        return startsWith(d, 0x50, 0x4B, 0x03, 0x04) || startsWith(d, 0x50, 0x4B, 0x05, 0x06);
    }

    private static boolean isGzip(byte[] d) {
        return d.length >= 2 && (d[0] & 0xFF) == 0x1F && (d[1] & 0xFF) == 0x8B;
    }

    private static boolean startsWith(byte[] d, int... prefix) {
        if (d.length < prefix.length) return false;
        for (int i = 0; i < prefix.length; i++) if ((d[i] & 0xFF) != prefix[i]) return false;
        return true;
    }

    /** Metin gibi görünüyorsa (NUL yok, çoğu yazdırılabilir) ISO-8859-1 metin; değilse null. */
    private static String asciiText(byte[] d) {
        int printable = 0;
        int limit = Math.min(d.length, 4096);
        for (int i = 0; i < limit; i++) {
            int b = d[i] & 0xFF;
            if (b == 0) return null;
            if (b == '\n' || b == '\r' || b == '\t' || (b >= 0x20 && b < 0x7F)) printable++;
        }
        if (limit > 0 && printable * 100 / limit < 95) return null;
        return new String(d, StandardCharsets.ISO_8859_1);
    }

    private static final Pattern LOOSE_BASE64 = Pattern.compile("^[A-Za-z0-9+/=\\s]+$");

    private static byte[] decodeLooseBase64(String text) {
        String t = text.trim();
        if (t.length() < 16 || !LOOSE_BASE64.matcher(t).matches()) return null;
        try {
            return Base64.getMimeDecoder().decode(t);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
