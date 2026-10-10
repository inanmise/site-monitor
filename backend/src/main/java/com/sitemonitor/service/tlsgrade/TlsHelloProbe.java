package com.sitemonitor.service.tlsgrade;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Ham TLS ClientHello yoklaması (2026-10-10) — "sunucu bu protokol sürümünü KABUL EDİYOR mu?" sorusunu, el sıkışmasını
 * TAMAMLAMADAN cevaplar.
 *
 * <p><b>Neden JSSE değil.</b> JDK TLS 1.0/1.1'i (ve RC4/3DES/NULL takımlarını) {@code jdk.tls.disabledAlgorithms} ile
 * kapatır; onları denemek için JVM genelindeki güvenlik özelliğini değiştirmek gerekirdi — bu, uygulamanın TÜM giden
 * bağlantılarını (LDAP, SMTP, sertifika kontrolü) zayıflatırdı. Bu sınıf JVM'e dokunmaz: ClientHello baytlarını kendisi
 * kurar, sunucunun ilk cevabını (ServerHello ya da uyarı) okur ve bağlantıyı kapatır. Anahtar değişimi yapılmaz, gizli
 * bir şey üretilmez ya da saklanmaz (TLS 1.3 anahtar payı yalnız rastgele 32 bayttır).
 *
 * <p><b>Sınırlar (sonsuz döngü / bellek yok):</b> bağlantı + okuma için tek bir son tarih ({@code timeoutMs}); kayıt
 * başına en çok {@value #MAX_RECORD} bayt, toplam {@value #MAX_TOTAL_BYTES} bayt, en çok {@value #MAX_RECORDS} kayıt.
 * Her döngü adımı en az bir kayıt başlığı tüketir ya da biter. Hiçbir sınırı aşan cevap {@link Outcome#PROTOCOL_ERROR}.
 *
 * <p><b>Sonuç anlamı.</b> {@link Outcome#ACCEPTED}: sunucu ServerHello ile İSTENEN sürümü seçti. {@link Outcome#REJECTED}:
 * ölümcül uyarı (handshake_failure, protocol_version…) ya da sunucu başka bir sürüm seçti. {@link Outcome#CLOSED}: cevap
 * gelmeden bağlantı kapandı/sıfırlandı (çağıran, aynı sunucu başka bir sürümü kabul ettiyse bunu "kapalı" sayar).
 * {@link Outcome#TIMEOUT} / {@link Outcome#CONNECT_FAILED} / {@link Outcome#PROTOCOL_ERROR}: bilinmiyor — tahmin edilmez.
 *
 * <p>OCSP zımbalama (yalnız TLS 1.2): ClientHello {@code status_request} taşır; sunucu ServerHello'da yankılarsa
 * Certificate'tan sonra gelen CertificateStatus (tip 22) beklenir: görülürse {@code YES}, ServerHelloDone (14) önce
 * gelirse ya da yankı yoksa {@code NO}, sınırlar dolarsa {@code UNKNOWN}. TLS 1.3'te bu iletiler şifrelidir → ölçülmez.
 */
public final class TlsHelloProbe {

    private TlsHelloProbe() { }

    public static final int TLS10 = 0x0301;
    public static final int TLS11 = 0x0302;
    public static final int TLS12 = 0x0303;
    public static final int TLS13 = 0x0304;

    static final int MAX_RECORD = 16_384 + 2_048;
    static final int MAX_TOTAL_BYTES = 64 * 1024;
    static final int MAX_RECORDS = 64;

    private static final int CT_CCS = 20, CT_ALERT = 21, CT_HANDSHAKE = 22, CT_APPDATA = 23;
    private static final int HS_SERVER_HELLO = 2, HS_CERTIFICATE_STATUS = 22, HS_SERVER_HELLO_DONE = 14;

    /** TLS 1.3 HelloRetryRequest'in sabit "random" değeri (RFC 8446 §4.1.3) — sürüm desteğini kanıtlar. */
    private static final byte[] HRR_RANDOM = hex("CF21AD74E59A6111BE1D8C021E65B891C2A211167ABB8C5E079E09E2C8A8339C");

    public enum Outcome { ACCEPTED, REJECTED, CLOSED, TIMEOUT, CONNECT_FAILED, PROTOCOL_ERROR }

    /** Ne soruluyor: bir protokol sürümü ya da (TLS 1.2 başlığıyla) yalnız zayıf şifre takımları. */
    public enum Kind { VERSION, WEAK_CIPHERS }

    /**
     * @param outcome   sonuç
     * @param version   sunucunun seçtiği sürüm (0 = ServerHello yok)
     * @param cipher    sunucunun seçtiği takım kodu (0 = yok)
     * @param stapling  "YES" / "NO" / "UNKNOWN" (yalnız zımbalama sorulduysa anlamlı)
     * @param alert     ölümcül uyarı açıklama kodu (RFC 8446 §6), yoksa null
     * @param error     kısa açıklama (yığın izi değil), yoksa null
     * @param elapsedMs geçen süre
     */
    public record HelloResult(Outcome outcome, int version, int cipher, String stapling, Integer alert,
                              String error, long elapsedMs) {
        /** Seçilen takımın IANA adı; ServerHello yoksa null. */
        public String cipherSuiteName() { return cipher == 0 ? null : TlsHelloProbe.cipherName(cipher); }
    }

    /** Bağlantı kurucu — doğrudan soket ya da vekil CONNECT tüneli (çağıran karar verir; test sahte sunucuya bağlar). */
    @FunctionalInterface
    public interface Connector {
        Socket open(int timeoutMs) throws IOException;
    }

    // ── Şifre takımları ───────────────────────────────────────────────────────────────────────

    /** TLS 1.3 takımları. */
    static final int[] TLS13_SUITES = {0x1301, 0x1302, 0x1303};

    /** TLS 1.2 için geniş öneri — modern (AEAD) önce, sonra CBC, statik RSA ve 3DES; sunucu tercihi okunur. */
    static final int[] TLS12_SUITES = {
            0xc02b, 0xc02f, 0xc02c, 0xc030, 0xcca9, 0xcca8, 0x009e, 0x009f,
            0xc023, 0xc027, 0xc024, 0xc028, 0xc009, 0xc013, 0xc00a, 0xc014,
            0x0067, 0x006b, 0x0033, 0x0039,
            0x009c, 0x009d, 0x003c, 0x003d, 0x002f, 0x0035,
            0xc012, 0xc008, 0x0016, 0x000a};

    /** TLS 1.0 / 1.1 için öneri — bu sürümlerde yalnız CBC ve akış takımları vardır (zayıflar dâhil: sürüm sorusu). */
    static final int[] LEGACY_SUITES = {
            0xc009, 0xc013, 0xc00a, 0xc014, 0x0033, 0x0039, 0x002f, 0x0035,
            0xc012, 0xc008, 0x0016, 0x000a, 0xc011, 0xc007, 0x0005, 0x0004};

    /** Yalnız zayıf takımlar: RC4, 3DES, DES, EXPORT, NULL, anonim. */
    static final int[] WEAK_SUITES = {
            0x0005, 0x0004, 0xc011, 0xc007,
            0x000a, 0xc012, 0xc008, 0x0016,
            0x0009, 0x0015,
            0x0003, 0x0006, 0x0008, 0x0014,
            0x0001, 0x0002, 0x003b, 0xc010, 0xc006,
            0x0018, 0x001b, 0x0034, 0xc018};

    private static final Map<Integer, String> CIPHER_NAMES = new LinkedHashMap<>();
    static {
        String[][] n = {
                {"1301", "TLS_AES_128_GCM_SHA256"}, {"1302", "TLS_AES_256_GCM_SHA384"},
                {"1303", "TLS_CHACHA20_POLY1305_SHA256"},
                {"c02b", "TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256"}, {"c02f", "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256"},
                {"c02c", "TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384"}, {"c030", "TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384"},
                {"cca9", "TLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305_SHA256"}, {"cca8", "TLS_ECDHE_RSA_WITH_CHACHA20_POLY1305_SHA256"},
                {"009e", "TLS_DHE_RSA_WITH_AES_128_GCM_SHA256"}, {"009f", "TLS_DHE_RSA_WITH_AES_256_GCM_SHA384"},
                {"c023", "TLS_ECDHE_ECDSA_WITH_AES_128_CBC_SHA256"}, {"c027", "TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA256"},
                {"c024", "TLS_ECDHE_ECDSA_WITH_AES_256_CBC_SHA384"}, {"c028", "TLS_ECDHE_RSA_WITH_AES_256_CBC_SHA384"},
                {"c009", "TLS_ECDHE_ECDSA_WITH_AES_128_CBC_SHA"}, {"c013", "TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA"},
                {"c00a", "TLS_ECDHE_ECDSA_WITH_AES_256_CBC_SHA"}, {"c014", "TLS_ECDHE_RSA_WITH_AES_256_CBC_SHA"},
                {"0067", "TLS_DHE_RSA_WITH_AES_128_CBC_SHA256"}, {"006b", "TLS_DHE_RSA_WITH_AES_256_CBC_SHA256"},
                {"0033", "TLS_DHE_RSA_WITH_AES_128_CBC_SHA"}, {"0039", "TLS_DHE_RSA_WITH_AES_256_CBC_SHA"},
                {"009c", "TLS_RSA_WITH_AES_128_GCM_SHA256"}, {"009d", "TLS_RSA_WITH_AES_256_GCM_SHA384"},
                {"003c", "TLS_RSA_WITH_AES_128_CBC_SHA256"}, {"003d", "TLS_RSA_WITH_AES_256_CBC_SHA256"},
                {"002f", "TLS_RSA_WITH_AES_128_CBC_SHA"}, {"0035", "TLS_RSA_WITH_AES_256_CBC_SHA"},
                {"c012", "TLS_ECDHE_RSA_WITH_3DES_EDE_CBC_SHA"}, {"c008", "TLS_ECDHE_ECDSA_WITH_3DES_EDE_CBC_SHA"},
                {"0016", "TLS_DHE_RSA_WITH_3DES_EDE_CBC_SHA"}, {"000a", "TLS_RSA_WITH_3DES_EDE_CBC_SHA"},
                {"c011", "TLS_ECDHE_RSA_WITH_RC4_128_SHA"}, {"c007", "TLS_ECDHE_ECDSA_WITH_RC4_128_SHA"},
                {"0005", "TLS_RSA_WITH_RC4_128_SHA"}, {"0004", "TLS_RSA_WITH_RC4_128_MD5"},
                {"0009", "TLS_RSA_WITH_DES_CBC_SHA"}, {"0015", "TLS_DHE_RSA_WITH_DES_CBC_SHA"},
                {"0003", "TLS_RSA_EXPORT_WITH_RC4_40_MD5"}, {"0006", "TLS_RSA_EXPORT_WITH_RC2_CBC_40_MD5"},
                {"0008", "TLS_RSA_EXPORT_WITH_DES40_CBC_SHA"}, {"0014", "TLS_DHE_RSA_EXPORT_WITH_DES40_CBC_SHA"},
                {"0001", "TLS_RSA_WITH_NULL_MD5"}, {"0002", "TLS_RSA_WITH_NULL_SHA"}, {"003b", "TLS_RSA_WITH_NULL_SHA256"},
                {"c010", "TLS_ECDHE_RSA_WITH_NULL_SHA"}, {"c006", "TLS_ECDHE_ECDSA_WITH_NULL_SHA"},
                {"0018", "TLS_DH_anon_WITH_RC4_128_MD5"}, {"001b", "TLS_DH_anon_WITH_3DES_EDE_CBC_SHA"},
                {"0034", "TLS_DH_anon_WITH_AES_128_CBC_SHA"}, {"c018", "TLS_ECDH_anon_WITH_AES_128_CBC_SHA"},
        };
        for (String[] e : n) CIPHER_NAMES.put(Integer.parseInt(e[0], 16), e[1]);
    }

    /** Takım kodu → IANA adı (tanınmayan → "0x...."). */
    public static String cipherName(int code) {
        String name = CIPHER_NAMES.get(code);
        return name != null ? name : String.format("0x%04X", code);
    }

    /** Sürüm kodu → okunur ad. */
    public static String versionName(int v) {
        return switch (v) {
            case 0x0300 -> "SSLv3";
            case TLS10 -> "TLSv1";
            case TLS11 -> "TLSv1.1";
            case TLS12 -> "TLSv1.2";
            case TLS13 -> "TLSv1.3";
            default -> v == 0 ? "" : String.format("0x%04X", v);
        };
    }

    // ── Yoklama ───────────────────────────────────────────────────────────────────────────────

    /**
     * Tek yoklama: bağlan, ClientHello gönder, ilk cevabı oku, kapat.
     *
     * @param connector     bağlantı kurucu
     * @param sniHost       SNI adı (IP adresi ise SNI gönderilmez)
     * @param version       {@link #TLS10} … {@link #TLS13} (WEAK_CIPHERS'ta yok sayılır, TLS 1.2 başlığı kullanılır)
     * @param kind          sürüm sorusu ya da zayıf takım sorusu
     * @param checkStapling yalnız TLS 1.2 sürüm sorusunda anlamlı
     * @param timeoutMs     bağlantı + okuma için toplam süre
     */
    public static HelloResult probe(Connector connector, String sniHost, int version, Kind kind,
                                    boolean checkStapling, int timeoutMs) {
        long start = System.currentTimeMillis();
        long deadline = start + Math.max(500, timeoutMs);
        int asked = kind == Kind.WEAK_CIPHERS ? TLS12 : version;
        boolean stapling = checkStapling && kind == Kind.VERSION && version == TLS12;
        Socket socket;
        try {
            socket = connector.open((int) Math.max(1, deadline - System.currentTimeMillis()));
        } catch (SocketTimeoutException e) {
            return result(Outcome.CONNECT_FAILED, 0, 0, null, null, "connect timeout", start);
        } catch (IOException | RuntimeException e) {
            return result(Outcome.CONNECT_FAILED, 0, 0, null, null, "connect: " + shortMsg(e), start);
        }
        try (socket) {
            byte[] hello = clientHello(sniHost, asked, kind, stapling, new SecureRandom());
            setTimeout(socket, deadline);
            OutputStream out = socket.getOutputStream();
            out.write(hello);
            out.flush();
            return readResponse(socket, socket.getInputStream(), deadline, asked, kind, stapling, start);
        } catch (SocketTimeoutException e) {
            return result(Outcome.TIMEOUT, 0, 0, null, null, "timeout", start);
        } catch (IOException e) {
            return result(Outcome.CLOSED, 0, 0, null, null, "closed: " + shortMsg(e), start);
        } catch (RuntimeException e) {
            return result(Outcome.PROTOCOL_ERROR, 0, 0, null, null, "parse: " + shortMsg(e), start);
        }
    }

    /** Sunucu cevabını okur. Paket-özel: test doğrudan akışla çağırır. */
    static HelloResult readResponse(Socket socket, InputStream in, long deadline, int asked, Kind kind,
                                    boolean stapling, long start) throws IOException {
        Counter counter = new Counter();
        ByteArrayOutputStream hs = new ByteArrayOutputStream();
        int records = 0;
        int negotiated = 0, cipher = 0;
        boolean sawHello = false;
        while (true) {
            if (++records > MAX_RECORDS) {
                return sawHello ? accepted(negotiated, cipher, "UNKNOWN", start)
                        : result(Outcome.PROTOCOL_ERROR, 0, 0, null, null, "too many records", start);
            }
            byte[] header;
            try {
                setTimeout(socket, deadline);
                header = readFully(in, 5, counter);
            } catch (EOFException e) {
                if (sawHello) return accepted(negotiated, cipher, "UNKNOWN", start);
                return counter.total == 0
                        ? result(Outcome.CLOSED, 0, 0, null, null, "closed without response", start)
                        : result(Outcome.PROTOCOL_ERROR, 0, 0, null, null, "truncated response", start);
            } catch (SocketTimeoutException e) {
                if (sawHello) return accepted(negotiated, cipher, "UNKNOWN", start);
                return result(Outcome.TIMEOUT, 0, 0, null, null, "timeout", start);
            } catch (LimitException e) {
                if (sawHello) return accepted(negotiated, cipher, "UNKNOWN", start);
                return result(Outcome.PROTOCOL_ERROR, 0, 0, null, null, "response too large", start);
            } catch (IOException e) {
                if (sawHello) return accepted(negotiated, cipher, "UNKNOWN", start);
                return counter.total == 0
                        ? result(Outcome.CLOSED, 0, 0, null, null, "closed: " + shortMsg(e), start)
                        : result(Outcome.PROTOCOL_ERROR, 0, 0, null, null, "read: " + shortMsg(e), start);
            }
            int type = header[0] & 0xff;
            int len = ((header[3] & 0xff) << 8) | (header[4] & 0xff);
            if (type != CT_CCS && type != CT_ALERT && type != CT_HANDSHAKE && type != CT_APPDATA) {
                if (sawHello) return accepted(negotiated, cipher, "UNKNOWN", start);
                return result(Outcome.PROTOCOL_ERROR, 0, 0, null, null, "not a TLS response", start);
            }
            if (len <= 0 || len > MAX_RECORD) {
                if (sawHello) return accepted(negotiated, cipher, "UNKNOWN", start);
                return result(Outcome.PROTOCOL_ERROR, 0, 0, null, null, "bad record length", start);
            }
            byte[] body;
            try {
                setTimeout(socket, deadline);
                body = readFully(in, len, counter);
            } catch (SocketTimeoutException e) {
                if (sawHello) return accepted(negotiated, cipher, "UNKNOWN", start);
                return result(Outcome.TIMEOUT, 0, 0, null, null, "timeout", start);
            } catch (IOException e) {   // EOF, sınır, sıfırlama
                if (sawHello) return accepted(negotiated, cipher, "UNKNOWN", start);
                return result(Outcome.PROTOCOL_ERROR, 0, 0, null, null, "truncated record", start);
            }

            if (type == CT_ALERT) {
                int level = body.length > 0 ? body[0] & 0xff : 2;
                int desc = body.length > 1 ? body[1] & 0xff : -1;
                if (level == 1 && !sawHello) continue;   // uyarı düzeyi (ör. unrecognized_name) — el sıkışması sürebilir
                if (sawHello) return accepted(negotiated, cipher, "UNKNOWN", start);
                return result(Outcome.REJECTED, 0, 0, null, desc < 0 ? null : desc, "alert " + desc, start);
            }
            if (type == CT_CCS || type == CT_APPDATA) {
                if (sawHello) return accepted(negotiated, cipher, "UNKNOWN", start);
                return result(Outcome.PROTOCOL_ERROR, 0, 0, null, null, "unexpected record before ServerHello", start);
            }

            // El sıkışması iletileri kayıtlara bölünebilir — tamponda biriktir, tam iletileri ayrıştır.
            hs.write(body, 0, body.length);
            byte[] buf = hs.toByteArray();
            int pos = 0;
            while (buf.length - pos >= 4) {
                int mtype = buf[pos] & 0xff;
                int mlen = ((buf[pos + 1] & 0xff) << 16) | ((buf[pos + 2] & 0xff) << 8) | (buf[pos + 3] & 0xff);
                if (mlen > MAX_TOTAL_BYTES) {
                    return sawHello ? accepted(negotiated, cipher, "UNKNOWN", start)
                            : result(Outcome.PROTOCOL_ERROR, 0, 0, null, null, "handshake message too large", start);
                }
                if (buf.length - pos - 4 < mlen) break;   // eksik ileti — sıradaki kaydı bekle
                int msgStart = pos + 4;
                pos = msgStart + mlen;
                if (!sawHello) {
                    if (mtype != HS_SERVER_HELLO) {
                        return result(Outcome.PROTOCOL_ERROR, 0, 0, null, null, "expected ServerHello", start);
                    }
                    ServerHello sh = parseServerHello(buf, msgStart, mlen);
                    if (sh == null) return result(Outcome.PROTOCOL_ERROR, 0, 0, null, null, "malformed ServerHello", start);
                    sawHello = true;
                    negotiated = sh.version();
                    cipher = sh.cipher();
                    if (kind == Kind.WEAK_CIPHERS) {
                        boolean offered = contains(WEAK_SUITES, cipher);
                        return offered ? accepted(negotiated, cipher, "UNKNOWN", start)
                                : result(Outcome.PROTOCOL_ERROR, negotiated, cipher, null, null, "server chose an unoffered suite", start);
                    }
                    if (negotiated != asked) {
                        return result(Outcome.REJECTED, negotiated, cipher, null, null, "server chose " + versionName(negotiated), start);
                    }
                    if (!stapling) return accepted(negotiated, cipher, "UNKNOWN", start);
                    if (!sh.statusEcho()) return accepted(negotiated, cipher, "NO", start);
                    continue;   // CertificateStatus / ServerHelloDone beklenir
                }
                if (mtype == HS_CERTIFICATE_STATUS) return accepted(negotiated, cipher, "YES", start);
                if (mtype == HS_SERVER_HELLO_DONE) return accepted(negotiated, cipher, "NO", start);
                // Certificate (11), ServerKeyExchange (12), CertificateRequest (13): oku, geç
            }
            hs.reset();
            if (pos < buf.length) hs.write(buf, pos, buf.length - pos);
        }
    }

    record ServerHello(int version, int cipher, boolean statusEcho) { }

    /** ServerHello gövdesi; sınır dışı erişim → null (asla istisna). */
    static ServerHello parseServerHello(byte[] b, int off, int len) {
        int end = off + len;
        int p = off;
        if (end > b.length || len < 38) return null;
        int legacyVersion = u16(b, p); p += 2;
        boolean hrr = Arrays.equals(Arrays.copyOfRange(b, p, p + 32), HRR_RANDOM);
        p += 32;
        int sidLen = b[p] & 0xff; p += 1;
        if (sidLen > 32 || p + sidLen + 3 > end) return null;
        p += sidLen;
        int cipher = u16(b, p); p += 2;
        p += 1;   // sıkıştırma
        int selected = 0;
        boolean statusEcho = false;
        if (p + 2 <= end) {
            int extTotal = u16(b, p); p += 2;
            int extEnd = Math.min(end, p + extTotal);
            while (p + 4 <= extEnd) {
                int type = u16(b, p);
                int elen = u16(b, p + 2);
                p += 4;
                if (p + elen > extEnd) return null;
                if (type == 0x002b && elen == 2) selected = u16(b, p);
                if (type == 0x0005) statusEcho = true;
                p += elen;
            }
        }
        int version = selected != 0 ? selected : legacyVersion;
        if (hrr && selected == 0) return null;   // HRR yalnız TLS 1.3'te ve supported_versions ile gelir
        return new ServerHello(version, cipher, statusEcho);
    }

    // ── ClientHello ───────────────────────────────────────────────────────────────────────────

    /** Kayıt katmanına sarılmış ClientHello. Paket-özel: test baytları doğrular. */
    static byte[] clientHello(String sniHost, int version, Kind kind, boolean statusRequest, SecureRandom rnd) {
        int legacy = version == TLS13 ? TLS12 : version;
        int[] suites = kind == Kind.WEAK_CIPHERS ? WEAK_SUITES
                : version == TLS13 ? TLS13_SUITES
                : version == TLS12 ? TLS12_SUITES : LEGACY_SUITES;

        Buf ext = new Buf();
        String sni = sniName(sniHost);
        if (sni != null) {
            byte[] name = sni.getBytes(StandardCharsets.US_ASCII);
            ext.u16(0x0000).u16(name.length + 5).u16(name.length + 3).u8(0).u16(name.length).bytes(name);
        }
        ext.u16(0x000a).u16(10).u16(8).u16(0x001d).u16(0x0017).u16(0x0018).u16(0x0019);   // supported_groups
        ext.u16(0x000b).u16(2).u8(1).u8(0);                                               // ec_point_formats
        if (legacy >= TLS12) {
            int[] sigs = {0x0403, 0x0804, 0x0401, 0x0503, 0x0805, 0x0501, 0x0806, 0x0601, 0x0201, 0x0203};
            ext.u16(0x000d).u16(2 + sigs.length * 2).u16(sigs.length * 2);
            for (int s : sigs) ext.u16(s);
        }
        if (statusRequest) ext.u16(0x0005).u16(5).u8(1).u16(0).u16(0);                     // status_request (OCSP)
        byte[] h2 = "h2".getBytes(StandardCharsets.US_ASCII), h11 = "http/1.1".getBytes(StandardCharsets.US_ASCII);
        ext.u16(0x0010).u16(2 + 2 + h2.length + h11.length).u16(2 + h2.length + h11.length)
                .u8(h2.length).bytes(h2).u8(h11.length).bytes(h11);                         // ALPN (tarayıcı biçimi)
        if (version != TLS13 || kind == Kind.WEAK_CIPHERS) {
            ext.u16(0x0017).u16(0);                                                       // extended_master_secret
            ext.u16(0x0023).u16(0);                                                       // session_ticket
            ext.u16(0xff01).u16(1).u8(0);                                                 // renegotiation_info
        } else {
            ext.u16(0x002b).u16(3).u8(2).u16(TLS13);                                      // supported_versions
            byte[] key = new byte[32];
            rnd.nextBytes(key);
            ext.u16(0x0033).u16(2 + 4 + 32).u16(4 + 32).u16(0x001d).u16(32).bytes(key);   // key_share (x25519)
            ext.u16(0x002d).u16(2).u8(1).u8(1);                                           // psk_key_exchange_modes
        }

        byte[] random = new byte[32];
        rnd.nextBytes(random);
        byte[] session = new byte[32];
        rnd.nextBytes(session);
        Buf body = new Buf();
        body.u16(legacy).bytes(random).u8(session.length).bytes(session);
        body.u16(suites.length * 2);
        for (int s : suites) body.u16(s);
        body.u8(1).u8(0);   // sıkıştırma: yok
        byte[] extBytes = ext.toBytes();
        body.u16(extBytes.length).bytes(extBytes);
        byte[] bodyBytes = body.toBytes();

        Buf hsMsg = new Buf();
        hsMsg.u8(1).u24(bodyBytes.length).bytes(bodyBytes);
        byte[] hsBytes = hsMsg.toBytes();
        Buf record = new Buf();
        record.u8(CT_HANDSHAKE).u16(TLS10).u16(hsBytes.length).bytes(hsBytes);
        return record.toBytes();
    }

    /** IP adresine SNI gönderilmez (RFC 6066); boş/IP → null. */
    static String sniName(String host) {
        if (host == null) return null;
        String h = host.trim();
        if (h.isEmpty() || h.length() > 253) return null;
        if (h.matches("[0-9.]+") || h.contains(":")) return null;
        for (int i = 0; i < h.length(); i++) if (h.charAt(i) > 0x7e || h.charAt(i) <= 0x20) return null;
        return h;
    }

    // ── Yardımcılar ───────────────────────────────────────────────────────────────────────────

    /** Toplam okuma sayacı ve sınırı. */
    private static final class Counter { int total; }

    private static final class LimitException extends IOException {
        LimitException() { super("response limit"); }
    }

    private static byte[] readFully(InputStream in, int n, Counter c) throws IOException {
        if (c.total + n > MAX_TOTAL_BYTES) throw new LimitException();
        byte[] b = new byte[n];
        int off = 0;
        while (off < n) {   // her tur en az 1 bayt okur ya da istisna/EOF ile biter (zaman aşımı soket düzeyinde)
            int r = in.read(b, off, n - off);
            if (r < 0) throw new EOFException();
            off += r;
            c.total += r;
        }
        return b;
    }

    private static void setTimeout(Socket s, long deadline) throws IOException {
        long left = deadline - System.currentTimeMillis();
        if (left <= 0) throw new SocketTimeoutException("deadline");
        if (s != null) s.setSoTimeout((int) Math.min(Integer.MAX_VALUE, left));
    }

    private static HelloResult accepted(int version, int cipher, String stapling, long start) {
        return result(Outcome.ACCEPTED, version, cipher, stapling, null, null, start);
    }

    private static HelloResult result(Outcome o, int version, int cipher, String stapling, Integer alert, String err, long start) {
        return new HelloResult(o, version, cipher, stapling == null ? "UNKNOWN" : stapling, alert,
                err, System.currentTimeMillis() - start);
    }

    private static boolean contains(int[] arr, int v) {
        for (int a : arr) if (a == v) return true;
        return false;
    }

    private static int u16(byte[] b, int p) {
        return ((b[p] & 0xff) << 8) | (b[p + 1] & 0xff);
    }

    static String shortMsg(Throwable e) {
        String m = e.getMessage();
        String s = e.getClass().getSimpleName() + (m == null ? "" : ": " + m);
        return s.length() > 120 ? s.substring(0, 120) : s;
    }

    private static byte[] hex(String s) {
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) out[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        return out;
    }

    /** Küçük bayt kurucu. */
    private static final class Buf {
        private final ByteArrayOutputStream o = new ByteArrayOutputStream();
        Buf u8(int v) { o.write(v & 0xff); return this; }
        Buf u16(int v) { o.write((v >>> 8) & 0xff); o.write(v & 0xff); return this; }
        Buf u24(int v) { o.write((v >>> 16) & 0xff); o.write((v >>> 8) & 0xff); o.write(v & 0xff); return this; }
        Buf bytes(byte[] b) { o.write(b, 0, b.length); return this; }
        byte[] toBytes() { return o.toByteArray(); }
    }
}
