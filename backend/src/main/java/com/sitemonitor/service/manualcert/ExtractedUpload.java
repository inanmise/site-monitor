package com.sitemonitor.service.manualcert;

import com.sitemonitor.service.manualcert.CertificateFileParser.CsrInfo;
import com.sitemonitor.service.manualcert.CertificateFileParser.ParsedCert;
import com.sitemonitor.util.Msg;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Tarayıcıda ayıklanmış yükleme ({@code extracted}) — SIKI doğrulama (2026-10-08, kullanıcı isteği: özel anahtar ve
 * parola sunucuya hiç gelmez; arayüz dosyayı tarayıcıda açar, yalnız açık sertifikaları gönderir).
 *
 * <p>Sözleşme (JSON): {@code {format, file_name, size_bytes, entries:[{alias, key_entry, certs:[Base64 DER, yaprak önce]}],
 * csr_pem:[…], private_keys_removed}}. Kurallar: bilinmeyen alan yok; {@code format} beyaz listede; en çok
 * {@value #MAX_CERTS} sertifika, her biri katı Base64 → tam bir DER X.509 (artık bayt yok); çözülmüş toplam ≤ 5 MB;
 * {@code alias} ≤ 255 karakter (denetim karakterleri atılır); {@code csr_pem} yalnız TEK bir CERTIFICATE REQUEST PEM
 * bloğu, her biri ≤ 16 KB ve ayrıştırılabilir; gövdenin hiçbir yerinde {@code PRIVATE KEY} geçemez. Hata →
 * {@link Invalid} (çağıran 400 döner). Parola alanı sözleşmede YOKTUR — gelirse bilinmeyen alan olarak reddedilir.
 *
 * <p>Saf: ağ / veritabanı yok; kopyalanan ya da loglanan içerik yok.
 */
public final class ExtractedUpload {

    private ExtractedUpload() { }

    /** Arayüzün gönderdiği biçim adları (tarayıcıdaki ayıklayıcıyla aynı). */
    public static final Set<String> FORMATS = Set.of("PEM", "DER", "PKCS7", "PKCS12", "JKS", "JCEKS", "ZIP", "TEXT");
    public static final int MAX_CERTS = 200;
    public static final int MAX_ENTRIES = 200;
    public static final int MAX_CSR = 5;
    public static final int CSR_PEM_MAX = 16 * 1024;
    public static final int ALIAS_MAX = 255;
    /** Ham JSON üst sınırı: 5 MB DER'in Base64'ü (~6,7 MB) + yapı. */
    public static final int MAX_JSON_BYTES = 8 * 1024 * 1024;
    public static final int MAX_PRIVATE_KEYS = 100_000;

    private static final Set<String> TOP_FIELDS = Set.of("format", "file_name", "size_bytes", "entries", "csr_pem",
            "private_keys_removed");
    private static final Set<String> ENTRY_FIELDS = Set.of("alias", "key_entry", "certs");
    private static final Pattern CSR_PEM = Pattern.compile(
            "\\A\\s*-----BEGIN CERTIFICATE REQUEST-----([A-Za-z0-9+/=\\s]+)-----END CERTIFICATE REQUEST-----\\s*\\z");

    /** Doğrulama hatası — ileti kullanıcı diline göre ({@link Msg#t}); içerik (sertifika / metin) taşımaz. */
    public static final class Invalid extends RuntimeException {
        public Invalid(String message) {
            super(message, null, false, false);
        }
    }

    /**
     * @param json multipart {@code extracted} parçasının baytları (UTF-8 JSON)
     * @throws Invalid sözleşmeye uymayan gövde
     */
    public static CertificateFileParser.Result parse(byte[] json, ObjectMapper mapper) {
        if (json == null || json.length == 0) throw invalid("boş", "empty");
        if (json.length > MAX_JSON_BYTES) throw invalid("çok büyük", "too large");
        // Savunma: tarayıcı özel anahtarı hiçbir koşulda göndermez — gövdede iz varsa reddedilir
        if (new String(json, StandardCharsets.UTF_8).contains("PRIVATE KEY")) {
            throw new Invalid(Msg.t("Gönderilen sertifika bilgisinde özel anahtar var; özel anahtar sunucuya gönderilemez.",
                    "The submitted certificate data contains a private key; private keys can't be sent to the server."));
        }
        JsonNode root;
        try {
            root = mapper.readTree(json);
        } catch (Exception e) {
            throw invalid("JSON okunamadı", "unreadable JSON");
        }
        if (root == null || !root.isObject()) throw invalid("nesne bekleniyordu", "an object was expected");
        for (String f : root.propertyNames()) {
            if (!TOP_FIELDS.contains(f)) throw invalid("bilinmeyen alan", "unknown field");
        }

        String format = text(root, "format");
        if (format == null || !FORMATS.contains(format)) throw invalid("biçim desteklenmiyor", "unsupported format");
        String fileName = optText(root, "file_name");
        if (fileName != null && fileName.length() > 1024) throw invalid("dosya adı çok uzun", "file name too long");
        long size = optLong(root, "size_bytes", 0);
        if (size < 0 || size > CertificateFileParser.MAX_BYTES) throw invalid("dosya boyutu geçersiz", "invalid file size");
        long keys = optLong(root, "private_keys_removed", 0);
        if (keys < 0 || keys > MAX_PRIVATE_KEYS) throw invalid("anahtar sayısı geçersiz", "invalid key count");

        List<ParsedCert> certs = new ArrayList<>();
        JsonNode entries = root.get("entries");
        if (entries != null && !entries.isNull()) {
            if (!entries.isArray()) throw invalid("entries dizi olmalı", "entries must be an array");
            if (entries.size() > MAX_ENTRIES) throw invalid("çok fazla girdi", "too many entries");
            long total = 0;
            int count = 0;
            for (JsonNode e : entries.values()) {
                if (!e.isObject()) throw invalid("girdi nesne olmalı", "an entry must be an object");
                for (String f : e.propertyNames()) {
                    if (!ENTRY_FIELDS.contains(f)) throw invalid("bilinmeyen girdi alanı", "unknown entry field");
                }
                String alias = alias(e.get("alias"));
                JsonNode ke = e.get("key_entry");
                if (ke != null && !ke.isNull() && !ke.isBoolean()) throw invalid("key_entry mantıksal olmalı", "key_entry must be a boolean");
                boolean keyEntry = ke != null && ke.isBoolean() && ke.booleanValue();
                JsonNode list = e.get("certs");
                if (list == null || !list.isArray() || list.isEmpty()) throw invalid("girdide sertifika yok", "an entry has no certificate");
                int i = 0;
                for (JsonNode c : list.values()) {
                    if (++count > MAX_CERTS) {
                        throw new Invalid(Msg.t("Tek seferde en çok " + MAX_CERTS + " sertifika gönderilebilir.",
                                "At most " + MAX_CERTS + " certificates can be sent at once."));
                    }
                    if (!c.isString()) throw invalid("sertifika metin olmalı", "a certificate must be a string");
                    byte[] der;
                    try {
                        der = Base64.getDecoder().decode(c.stringValue());
                    } catch (IllegalArgumentException ex) {
                        throw invalid("sertifika Base64 değil", "a certificate is not Base64");
                    }
                    total += der.length;
                    if (total > CertificateFileParser.MAX_BYTES) throw invalid("sertifikalar çok büyük", "certificates too large");
                    X509Certificate x = CertificateFileParser.toX509(der);
                    if (x == null || !encodedEquals(x, der)) throw invalid("geçerli bir X.509 sertifikası değil", "not a valid X.509 certificate");
                    certs.add(new ParsedCert(x, alias, keyEntry && i == 0, null));
                    i++;
                }
            }
        }

        CsrInfo csr = null;
        JsonNode csrs = root.get("csr_pem");
        if (csrs != null && !csrs.isNull()) {
            if (!csrs.isArray()) throw invalid("csr_pem dizi olmalı", "csr_pem must be an array");
            if (csrs.size() > MAX_CSR) throw invalid("çok fazla CSR", "too many CSRs");
            for (JsonNode c : csrs.values()) {
                if (!c.isString() || c.stringValue().length() > CSR_PEM_MAX) throw invalid("CSR geçersiz", "invalid CSR");
                Matcher m = CSR_PEM.matcher(c.stringValue());
                if (!m.matches()) throw invalid("CSR yalnız CERTIFICATE REQUEST PEM olabilir", "a CSR must be a CERTIFICATE REQUEST PEM");
                byte[] der;
                try {
                    der = Base64.getMimeDecoder().decode(m.group(1).replaceAll("\\s+", ""));
                } catch (IllegalArgumentException ex) {
                    throw invalid("CSR Base64 değil", "the CSR is not Base64");
                }
                CsrInfo info = CertificateFileParser.parseCsr(der);
                if (info == null) throw invalid("CSR okunamadı", "the CSR couldn't be read");
                if (csr == null) csr = info;
            }
        }
        return CertificateFileParser.fromExtracted(format, fileName, size, certs, csr, (int) keys);
    }

    private static boolean encodedEquals(X509Certificate x, byte[] der) {
        try {
            return java.util.Arrays.equals(x.getEncoded(), der);
        } catch (Exception e) {
            return false;
        }
    }

    private static String alias(JsonNode n) {
        if (n == null || n.isNull()) return null;
        if (!n.isString()) throw invalid("takma ad metin olmalı", "alias must be a string");
        String s = n.stringValue();
        if (s.length() > ALIAS_MAX) throw invalid("takma ad çok uzun", "alias too long");
        StringBuilder sb = new StringBuilder(s.length());
        for (char ch : s.toCharArray()) if (!Character.isISOControl(ch)) sb.append(ch);
        String out = sb.toString().strip();
        return out.isEmpty() ? null : out;
    }

    private static String text(JsonNode root, String field) {
        JsonNode n = root.get(field);
        return n != null && n.isString() ? n.stringValue() : null;
    }

    private static String optText(JsonNode root, String field) {
        JsonNode n = root.get(field);
        if (n == null || n.isNull()) return null;
        if (!n.isString()) throw invalid(field + " metin olmalı", field + " must be a string");
        return n.stringValue();
    }

    private static long optLong(JsonNode root, String field, long def) {
        JsonNode n = root.get(field);
        if (n == null || n.isNull()) return def;
        if (!n.isIntegralNumber() || !n.canConvertToLong()) throw invalid(field + " tamsayı olmalı", field + " must be an integer");
        return n.longValue();
    }

    private static Invalid invalid(String tr, String en) {
        return new Invalid(Msg.t("Gönderilen sertifika bilgisi geçersiz (" + tr + "). Dosyayı yeniden seçip analiz edin.",
                "The submitted certificate data is invalid (" + en + "). Choose the file again and analyse it."));
    }
}
