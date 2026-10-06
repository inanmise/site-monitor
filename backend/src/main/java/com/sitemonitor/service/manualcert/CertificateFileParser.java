package com.sitemonitor.service.manualcert;

import com.sitemonitor.service.CertificateFacts;
import org.bouncycastle.asn1.ASN1Encodable;
import org.bouncycastle.asn1.ASN1InputStream;
import org.bouncycastle.asn1.ASN1Integer;
import org.bouncycastle.asn1.ASN1ObjectIdentifier;
import org.bouncycastle.asn1.ASN1Primitive;
import org.bouncycastle.asn1.ASN1Sequence;
import org.bouncycastle.asn1.pkcs.Attribute;
import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.Extensions;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.operator.DefaultAlgorithmNameFinder;
import org.bouncycastle.pkcs.PKCS10CertificationRequest;
import org.bouncycastle.pkcs.jcajce.JcaPKCS10CertificationRequest;

import javax.security.auth.x500.X500Principal;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.PublicKey;
import java.security.UnrecoverableKeyException;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.Collections;
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
 * <p>Desteklenen: PEM (çoklu CERTIFICATE / TRUSTED CERTIFICATE / PKCS7 blokları; her türden PRIVATE KEY bloğu
 * sayılır ve YOK SAYILIR; CERTIFICATE REQUEST tanınır), zırhsız Base64 DER, DER X.509, PKCS#7 (PEM/DER),
 * PKCS#12 (.pfx/.p12, parola), JKS / JCEKS (parola verilirse bütünlük denetlenir; verilmezse sertifikalar parolasız
 * okunur — bütünlük denetimi atlanır), BKS (BouncyCastle) ve ZIP (sınırlarla, iç içe arşiv açılmaz).
 *
 * <p><b>Gizli malzeme:</b> özel anahtar hiçbir zaman okunmaz (anahtar deposunda yalnız sertifika zinciri istenir),
 * parola yalnız {@link KeyStore#load} çağrısına verilir; bu sınıf onu kopyalamaz, saklamaz, loglamaz. Parolayı
 * kullanımdan sonra sıfırlamak ÇAĞIRANIN işidir.
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
     */
    public static final List<String> WARNING_CODES = List.of(
            "PRIVATE_KEY_IGNORED", "CSR_NOT_CERTIFICATE", "NO_CERTIFICATE", "PASSWORD_REQUIRED", "PASSWORD_WRONG",
            "UNSUPPORTED_FORMAT", "FILE_TOO_LARGE", "ZIP_LIMIT", "ZIP_SKIPPED_ENTRY", "EXPIRED", "NOT_YET_VALID",
            "EXPIRES_SOON", "SELF_SIGNED", "CHAIN_INCOMPLETE", "CHAIN_EXPIRED_INTERMEDIATE", "WEAK_SIGNATURE", "WEAK_KEY",
            "CA_CERTIFICATE", "MULTIPLE_LEAVES", "DUPLICATE_IN_FILE", "ALREADY_TRACKED", "SAME_SUBJECT_TRACKED",
            "NETWORK_MONITORED", "KEY_CHANGED", "KEY_SAME", "SUBJECT_CHANGED", "OLDER_THAN_CURRENT", "SAN_CHANGED");

    public static final String SEV_INFO = "info";
    public static final String SEV_WARN = "warn";
    public static final String SEV_ERROR = "error";

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
        boolean needsPassword;
        boolean passwordError;
        CsrInfo csr;
        int privateKeyCount;
        boolean rejected;
        final List<Warning> warnings = new ArrayList<>();
        final List<ParsedCert> certs = new ArrayList<>();

        public String format() { return format; }
        public String fileName() { return fileName; }
        public long sizeBytes() { return sizeBytes; }
        public boolean needsPassword() { return needsPassword; }
        public boolean passwordError() { return passwordError; }
        public CsrInfo csr() { return csr; }
        public int privateKeyCount() { return privateKeyCount; }
        public List<Warning> warnings() { return Collections.unmodifiableList(warnings); }
        public List<ParsedCert> certs() { return Collections.unmodifiableList(certs); }
    }

    // ── Giriş ────────────────────────────────────────────────────────────────

    /**
     * @param data     dosya baytları ya da yapıştırılan metnin UTF-8 baytları
     * @param fileName istemcinin bildirdiği dosya adı (yalnız gösterim + uyarı; biçim İÇERİKTEN tanınır)
     * @param password anahtar deposu parolası (null olabilir; KOPYALANMAZ)
     * @param pasted   girdi yapıştırılan metin mi (biçim {@code TEXT} raporlanır)
     */
    public static Result parse(byte[] data, String fileName, char[] password, boolean pasted) {
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
            parseZip(data, r, password);
        } else {
            String fmt = parseBlob(data, r, password, null, true);
            r.format = pasted ? "TEXT" : fmt;
            if (fmt == null && !r.rejected) {
                r.rejected = true;
                r.warnings.add(new Warning("UNSUPPORTED_FORMAT", SEV_ERROR,
                        Map.of("name", r.fileName == null ? "" : r.fileName)));
            }
        }
        finish(r);
        return r;
    }

    private static void finish(Result r) {
        if (r.rejected) {
            r.certs.clear();
            return;
        }
        if (r.privateKeyCount > 0) {
            r.warnings.add(new Warning("PRIVATE_KEY_IGNORED", SEV_INFO, Map.of("count", r.privateKeyCount)));
        }
        if (r.certs.isEmpty()) {
            if (r.csr != null) {
                r.warnings.add(new Warning("CSR_NOT_CERTIFICATE", SEV_ERROR,
                        Map.of("cn", r.csr.cn() == null ? "" : r.csr.cn())));
            } else if (!r.needsPassword && !r.passwordError) {
                r.warnings.add(new Warning("NO_CERTIFICATE", SEV_ERROR, Map.of()));
            }
        }
    }

    // ── Biçim tanıma ─────────────────────────────────────────────────────────

    /**
     * Tek bir blob (dosya ya da ZIP girdisi) — tanınan biçimi döner; tanınmazsa null.
     *
     * @param allowBase64 zırhsız Base64 denemesine izin (özyinelemede bir kez)
     */
    static String parseBlob(byte[] data, Result r, char[] password, String source, boolean allowBase64) {
        String text = asciiText(data);
        if (text != null && text.contains("-----BEGIN ")) {
            parsePem(text, r, source);
            return "PEM";
        }
        if (startsWith(data, 0xFE, 0xED, 0xFE, 0xED)) {
            readKeystore("JKS", data, r, password, source);
            return "JKS";
        }
        if (startsWith(data, 0xCE, 0xCE, 0xCE, 0xCE)) {
            readKeystore("JCEKS", data, r, password, source);
            return "JCEKS";
        }
        if ((data[0] & 0xFF) == 0x30) {
            String der = parseDer(data, r, password, source);
            if (der != null) return der;
        }
        if (data.length > 4 && data[0] == 0 && data[1] == 0 && data[2] == 0 && (data[3] == 1 || data[3] == 2)) {
            if (readKeystore("BKS", data, r, password, source)) return "BKS";
        }
        if (allowBase64 && text != null) {
            byte[] decoded = decodeLooseBase64(text);
            if (decoded != null && decoded.length > 0) {
                String inner = parseBlob(decoded, r, password, source, false);
                if (inner != null) return inner;
            }
        }
        return null;
    }

    /** ASN.1 DER: X.509, PKCS#7 SignedData, PKCS#12 PFX ya da PKCS#10 CSR. */
    private static String parseDer(byte[] data, Result r, char[] password, String source) {
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
        // PKCS#12 PFX: SEQUENCE { version INTEGER (3), authSafe ContentInfo, macData? }
        if (first instanceof ASN1Integer v && seq.size() >= 2 && v.getValue().intValue() == 3
                && seq.getObjectAt(1) instanceof ASN1Sequence) {
            readKeystore("PKCS12", data, r, password, source);
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
        return null;
    }

    // ── PEM ──────────────────────────────────────────────────────────────────

    private static final Pattern PEM_BLOCK = Pattern.compile(
            "-----BEGIN ([A-Z0-9 .]+)-----(.*?)-----END \\1-----", Pattern.DOTALL);

    static void parsePem(String text, Result r, String source) {
        Matcher m = PEM_BLOCK.matcher(text);
        while (m.find()) {
            String label = m.group(1).trim().toUpperCase(Locale.ROOT);
            if (label.contains("PRIVATE KEY")) {          // RSA/EC/DSA/ENCRYPTED/OPENSSH/… — sayılır, okunmaz
                r.privateKeyCount++;
                continue;
            }
            byte[] der = pemBody(m.group(2));
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

    // ── Anahtar depoları ─────────────────────────────────────────────────────

    private static final class BcHolder {
        static final BouncyCastleProvider BC = new BouncyCastleProvider();
    }

    private static KeyStore newKeyStore(String type) throws Exception {
        return "BKS".equals(type) ? KeyStore.getInstance("BKS", BcHolder.BC) : KeyStore.getInstance(type);
    }

    /**
     * Anahtar deposundaki sertifikaları okur. Özel anahtar İSTENMEZ — anahtar girdisinin yalnız zinciri okunur.
     *
     * @return depo açılabildi mi
     */
    static boolean readKeystore(String type, byte[] data, Result r, char[] password, String source) {
        boolean hasPassword = password != null && password.length > 0;
        KeyStore ks = null;
        if ("PKCS12".equals(type)) {
            if (hasPassword) {
                try {
                    ks = load(type, data, password);
                } catch (Exception e) {
                    if (isPasswordError(e)) {
                        r.passwordError = true;
                        r.warnings.add(new Warning("PASSWORD_WRONG", SEV_ERROR, Map.of("format", type)));
                    }
                    return false;
                }
            } else {
                // Parolasız PFX yaygın (boş parola) — önce boş, sonra null (bütünlük atlanır, şifresiz torbalar okunur).
                try { ks = load(type, data, new char[0]); } catch (Exception ignore) { ks = null; }
                if (ks == null || !hasCertificates(ks)) {
                    try { ks = load(type, data, null); } catch (Exception ignore) { ks = null; }
                }
                if (ks == null || !hasCertificates(ks)) {
                    r.needsPassword = true;
                    r.warnings.add(new Warning("PASSWORD_REQUIRED", SEV_ERROR, Map.of("format", type)));
                    return ks != null;
                }
            }
        } else {
            // JKS / JCEKS / BKS: sertifikalar parolasız okunabilir (bütünlük denetimi atlanır). Parola verildiyse
            // önce onunla denenir; yanlışsa uyarı + parolasız okuma (açık sertifikaları okumak için parola gerekmez).
            if (hasPassword) {
                try {
                    ks = load(type, data, password);
                } catch (Exception e) {
                    if (isPasswordError(e)) {
                        r.warnings.add(new Warning("PASSWORD_WRONG", SEV_WARN, Map.of("format", type)));
                    }
                    ks = null;
                }
            }
            if (ks == null) {
                try {
                    ks = load(type, data, null);
                } catch (Exception e) {
                    return false;
                }
            }
        }
        collect(ks, r, source);
        return true;
    }

    private static KeyStore load(String type, byte[] data, char[] password) throws Exception {
        KeyStore ks = newKeyStore(type);
        try (InputStream in = new ByteArrayInputStream(data)) {
            ks.load(in, password);
        }
        return ks;
    }

    private static boolean hasCertificates(KeyStore ks) {
        try {
            for (String alias : Collections.list(ks.aliases())) {
                if (ks.isCertificateEntry(alias)) return true;
                Certificate[] chain = ks.getCertificateChain(alias);
                if (chain != null && chain.length > 0) return true;
            }
        } catch (Exception ignore) { /* boş say */ }
        return false;
    }

    private static void collect(KeyStore ks, Result r, String source) {
        try {
            for (String alias : Collections.list(ks.aliases())) {
                if (ks.isKeyEntry(alias)) {
                    Certificate[] chain = ks.getCertificateChain(alias);
                    if (chain == null || chain.length == 0) continue;   // gizli anahtar girdisi (JCEKS) — sertifika yok
                    r.privateKeyCount++;                                // anahtar OKUNMAZ; yalnız "yok sayıldı" bilgisi
                    for (int i = 0; i < chain.length; i++) {
                        if (chain[i] instanceof X509Certificate x)
                            r.certs.add(new ParsedCert(x, alias, i == 0, source));
                    }
                } else if (ks.isCertificateEntry(alias)) {
                    Certificate c = ks.getCertificate(alias);
                    if (c instanceof X509Certificate x) r.certs.add(new ParsedCert(x, alias, false, source));
                }
            }
        } catch (Exception ignore) {
            // Okunabilen kadarı kalır.
        }
    }

    /** Parola yanlış mı — JDK/BC iletisi sürüme göre değişir; neden zinciri taranır. */
    static boolean isPasswordError(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof UnrecoverableKeyException) return true;
            if (t instanceof javax.crypto.BadPaddingException) return true;
            String m = t.getMessage();
            if (m != null) {
                String l = m.toLowerCase(Locale.ROOT);
                if (l.contains("password") || l.contains("mac invalid") || l.contains("integrity check")) return true;
            }
            if (t.getCause() == t) break;
        }
        return false;
    }

    // ── ZIP ──────────────────────────────────────────────────────────────────

    private static void parseZip(byte[] data, Result r, char[] password) {
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
                boolean hadCsr = r.csr != null, needPw = r.needsPassword, pwErr = r.passwordError;
                String fmt = null;
                try {
                    fmt = parseBlob(buf, r, password, name, true);
                } catch (Exception ignore) { /* bozuk girdi atlanır */ }
                boolean contributed = r.certs.size() > certs || r.privateKeyCount > keys
                        || (r.csr != null && !hadCsr) || r.needsPassword != needPw || r.passwordError != pwErr;
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
