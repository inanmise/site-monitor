package com.sitemonitor.service.manualcert;

import com.sitemonitor.service.manualcert.CertificateFileParser.Result;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static com.sitemonitor.service.manualcert.TestCerts.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tarayıcıda ayıklanmış yükleme ({@code extracted}) — SIKI doğrulama (2026-10-08, kullanıcı isteği: özel anahtar ve parola
 * sunucuya hiç gelmez). Mutlu yol + her kural için ret: biçim beyaz listesi, bilinmeyen alan (ör. {@code password}),
 * katı Base64, tam DER X.509 (artık bayt yok), ≤ 200 sertifika, boyut, takma ad, CSR PEM, gövdede "PRIVATE KEY".
 */
class ExtractedUploadTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static Chain chain;

    @BeforeAll
    static void certs() {
        chain = TestCerts.chain("api.example.test", days(200));
    }

    private static String b64(X509Certificate c) {
        return Base64.getEncoder().encodeToString(der(c));
    }

    private static Map<String, Object> body(Consumer<Map<String, Object>> edit) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("format", "PKCS12");
        m.put("file_name", "sunucu.pfx");
        m.put("size_bytes", 4096);
        List<Object> entries = new ArrayList<>();
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("alias", "sunucu");
        e.put("key_entry", true);
        e.put("certs", List.of(b64(chain.leaf()), b64(chain.inter()), b64(chain.root())));
        entries.add(e);
        m.put("entries", entries);
        m.put("csr_pem", List.of());
        m.put("private_keys_removed", 1);
        if (edit != null) edit.accept(m);
        return m;
    }

    private static Result parse(Map<String, Object> m) {
        return ExtractedUpload.parse(JSON.writeValueAsBytes(m), JSON);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> firstEntry(Map<String, Object> m) {
        return (Map<String, Object>) ((List<Object>) m.get("entries")).get(0);
    }

    @Test
    @DisplayName("mutlu yol: sertifikalar sırasıyla, ilki anahtar girdisi + takma ad; PRIVATE_KEY_KEPT_LOCAL {count}; biçim / ad / boyut korunur")
    void happyPath() {
        Result r = parse(body(null));
        assertThat(r.format()).isEqualTo("PKCS12");
        assertThat(r.fileName()).isEqualTo("sunucu.pfx");
        assertThat(r.sizeBytes()).isEqualTo(4096);
        assertThat(r.certs()).extracting(CertificateFileParser.ParsedCert::cert).containsExactly(chain.leaf(), chain.inter(), chain.root());
        assertThat(r.certs()).extracting(CertificateFileParser.ParsedCert::keyEntry).containsExactly(true, false, false);
        assertThat(r.certs()).extracting(CertificateFileParser.ParsedCert::alias).containsOnly("sunucu");
        assertThat(r.privateMaterial()).isNull();
        assertThat(r.warnings()).anySatisfy(w -> {
            assertThat(w.code()).isEqualTo("PRIVATE_KEY_KEPT_LOCAL");
            assertThat(w.params()).containsEntry("count", 1);
        });
    }

    @Test
    @DisplayName("CSR PEM kabul edilir (sertifikasız dosyada CSR kartı); takma addaki denetim karakterleri atılır")
    void csrAndAliasSanitised() {
        String csrPem = pemBlock("CERTIFICATE REQUEST", csr("csr.example.test", List.of("csr.example.test"), rsa()));
        Result r = parse(body(m -> {
            m.put("entries", List.of());
            m.put("csr_pem", List.of(csrPem));
            m.put("private_keys_removed", 0);
        }));
        assertThat(r.csr()).isNotNull();
        assertThat(r.csr().cn()).isEqualTo("csr.example.test");
        assertThat(r.warnings()).extracting(CertificateFileParser.Warning::code).containsExactly("CSR_NOT_CERTIFICATE");

        Result a = parse(body(m -> firstEntry(m).put("alias", " ad\u0007\u0000 ")));
        assertThat(a.certs().get(0).alias()).isEqualTo("ad");
    }

    @Test
    @DisplayName("ret: bilinmeyen biçim, eksik biçim, bilinmeyen üst alan (password!), bilinmeyen girdi alanı")
    void rejectsFormatAndUnknownFields() {
        assertThatThrownBy(() -> parse(body(m -> m.put("format", "BKS")))).isInstanceOf(ExtractedUpload.Invalid.class);
        assertThatThrownBy(() -> parse(body(m -> m.remove("format")))).isInstanceOf(ExtractedUpload.Invalid.class);
        assertThatThrownBy(() -> parse(body(m -> m.put("password", "Test1234")))).isInstanceOf(ExtractedUpload.Invalid.class);
        assertThatThrownBy(() -> parse(body(m -> firstEntry(m).put("key", "AAAA")))).isInstanceOf(ExtractedUpload.Invalid.class);
        assertThatThrownBy(() -> parse(body(m -> firstEntry(m).put("key_entry", "evet")))).isInstanceOf(ExtractedUpload.Invalid.class);
    }

    @Test
    @DisplayName("ret: bozuk Base64, X.509 olmayan DER (CSR), sonunda artık bayt olan sertifika, boş sertifika listesi")
    void rejectsBadCertificates() {
        assertThatThrownBy(() -> parse(body(m -> firstEntry(m).put("certs", List.of("bu-base64-degil!!")))))
                .isInstanceOf(ExtractedUpload.Invalid.class);
        String csrB64 = Base64.getEncoder().encodeToString(csr("x.example.test", List.of(), rsa()));
        assertThatThrownBy(() -> parse(body(m -> firstEntry(m).put("certs", List.of(csrB64)))))
                .isInstanceOf(ExtractedUpload.Invalid.class);
        byte[] der = der(chain.leaf());
        byte[] trailing = java.util.Arrays.copyOf(der, der.length + 3);
        assertThatThrownBy(() -> parse(body(m -> firstEntry(m).put("certs", List.of(Base64.getEncoder().encodeToString(trailing))))))
                .isInstanceOf(ExtractedUpload.Invalid.class);
        assertThatThrownBy(() -> parse(body(m -> firstEntry(m).put("certs", List.of())))).isInstanceOf(ExtractedUpload.Invalid.class);
    }

    @Test
    @DisplayName("ret: 200'den fazla sertifika; ham JSON 8 MB üstü; çözülmüş sertifikalar toplamı 5 MB üstü")
    void rejectsSizeLimits() {
        List<String> many = new ArrayList<>();
        for (int i = 0; i < ExtractedUpload.MAX_CERTS + 1; i++) many.add(b64(chain.root()));
        assertThatThrownBy(() -> parse(body(m -> firstEntry(m).put("certs", many)))).isInstanceOf(ExtractedUpload.Invalid.class)
                .hasMessageContaining(String.valueOf(ExtractedUpload.MAX_CERTS));
        // Tam 200 kabul
        assertThat(parse(body(m -> firstEntry(m).put("certs", many.subList(0, ExtractedUpload.MAX_CERTS)))).certs())
                .hasSize(ExtractedUpload.MAX_CERTS);

        assertThatThrownBy(() -> ExtractedUpload.parse(new byte[ExtractedUpload.MAX_JSON_BYTES + 1], JSON))
                .isInstanceOf(ExtractedUpload.Invalid.class);

        // ~30 KB'lık sertifika (uzun SAN listesi) × n: DER toplamı 5 MB'ı aşar ama JSON 8 MB'ın ALTINDA kalır → DER toplam sınırı
        List<String> sans = new ArrayList<>();
        for (int i = 0; i < 650; i++) sans.add("alt-ad-" + i + ".cok-uzun-bir-alan-adi.example.test");
        X509Certificate big = leaf("big.example.test", sans, freshRsa(), chain.inter(), chain.interKey().getPrivate(), days(-1), days(90));
        int len = der(big).length;
        int n = CertificateFileParser.MAX_BYTES / len + 1;
        assertThat(n).isLessThanOrEqualTo(ExtractedUpload.MAX_CERTS);
        assertThat((long) n * ((len + 2) / 3 * 4 + 3)).isLessThan(ExtractedUpload.MAX_JSON_BYTES - 64_000L);
        List<String> heavy = new ArrayList<>();
        for (int i = 0; i < n; i++) heavy.add(b64(big));
        assertThatThrownBy(() -> parse(body(m -> firstEntry(m).put("certs", heavy)))).isInstanceOf(ExtractedUpload.Invalid.class)
                .hasMessageContaining("çok büyük");
    }

    @Test
    @DisplayName("ret: takma ad 255 karakter üstü; dosya boyutu negatif / 5 MB üstü; anahtar sayısı negatif")
    void rejectsFieldRanges() {
        assertThatThrownBy(() -> parse(body(m -> firstEntry(m).put("alias", "a".repeat(256))))).isInstanceOf(ExtractedUpload.Invalid.class);
        assertThatThrownBy(() -> parse(body(m -> m.put("size_bytes", -1)))).isInstanceOf(ExtractedUpload.Invalid.class);
        assertThatThrownBy(() -> parse(body(m -> m.put("size_bytes", CertificateFileParser.MAX_BYTES + 1L)))).isInstanceOf(ExtractedUpload.Invalid.class);
        assertThatThrownBy(() -> parse(body(m -> m.put("private_keys_removed", -2)))).isInstanceOf(ExtractedUpload.Invalid.class);
        assertThatThrownBy(() -> parse(body(m -> m.put("private_keys_removed", "1")))).isInstanceOf(ExtractedUpload.Invalid.class);
    }

    @Test
    @DisplayName("ret: csr_pem yalnız TEK CERTIFICATE REQUEST bloğu (sertifika PEM'i değil), ≤ 16 KB; gövdede 'PRIVATE KEY' hiç geçemez")
    void rejectsCsrAndPrivateKey() {
        assertThatThrownBy(() -> parse(body(m -> m.put("csr_pem", List.of(pem(chain.leaf()))))))
                .isInstanceOf(ExtractedUpload.Invalid.class);
        String csrPem = pemBlock("CERTIFICATE REQUEST", csr("csr.example.test", List.of(), rsa()));
        assertThatThrownBy(() -> parse(body(m -> m.put("csr_pem", List.of(csrPem + csrPem)))))
                .isInstanceOf(ExtractedUpload.Invalid.class);
        assertThatThrownBy(() -> parse(body(m -> m.put("csr_pem", List.of(csrPem + " ".repeat(ExtractedUpload.CSR_PEM_MAX))))))
                .isInstanceOf(ExtractedUpload.Invalid.class);
        assertThatThrownBy(() -> parse(body(m -> m.put("csr_pem", List.of(privateKeyPem(rsa()))))))
                .isInstanceOf(ExtractedUpload.Invalid.class).hasMessageContaining("özel anahtar");
        assertThatThrownBy(() -> parse(body(m -> m.put("file_name", "-----BEGIN PRIVATE KEY-----"))))
                .isInstanceOf(ExtractedUpload.Invalid.class);
        assertThatThrownBy(() -> ExtractedUpload.parse("[1,2]".getBytes(), JSON)).isInstanceOf(ExtractedUpload.Invalid.class);
        assertThatThrownBy(() -> ExtractedUpload.parse("{bozuk".getBytes(), JSON)).isInstanceOf(ExtractedUpload.Invalid.class);
    }
}
