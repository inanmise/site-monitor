package com.sitemonitor.service;

import com.sitemonitor.model.PinnedCa;
import com.sitemonitor.repository.PinnedCaRepository;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Security;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CaAutoPinService — pin/rotasyon/no-op/rate-limit mantığı, mock zincir çekici + Map-destekli repo
 * ile. Zincirden CA seçimi (leaf hariç; tek elemanlı zincirde leaf'in kendisi), fingerprint bazlı
 * rotasyon tespiti, audit olayları (CA_PINNED/CA_ROTATED) ve host normalizasyonu doğrulanır.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CaAutoPinServiceTest {

    @Mock CertificateCheckerService certChecker;
    @Mock AppSettingsService appSettings;
    @Mock AuditService auditService;
    @Mock PinnedCaRepository repo;

    private static X509Certificate leaf;
    private static X509Certificate ca;

    private final Map<String, PinnedCa> store = new HashMap<>();

    @BeforeAll
    static void generateCerts() throws Exception {
        Security.addProvider(new BouncyCastleProvider());
        leaf = selfSigned("CN=leaf.example.test");
        ca   = selfSigned("CN=Test Internal CA");
    }

    @BeforeEach
    void wireMocks() {
        when(appSettings.getBoolean(eq(CaAutoPinService.ENABLED_KEY), anyBoolean())).thenReturn(true);
        when(repo.findByHostAndPort(anyString(), anyInt())).thenAnswer(inv ->
                Optional.ofNullable(store.get(inv.getArgument(0) + ":" + inv.getArgument(1))));
        when(repo.save(any(PinnedCa.class))).thenAnswer(inv -> {
            PinnedCa p = inv.getArgument(0);
            store.put(p.getHost() + ":" + p.getPort(), p);
            return p;
        });
    }

    private CaAutoPinService newService() {
        return new CaAutoPinService(certChecker, repo, appSettings, auditService);
    }

    @Test
    @DisplayName("yeni pin: zincirden yalnız CA'lar pinlenir (leaf hariç), CA_PINNED audit'lenir")
    void pinNew_pinsCaOnly_audited() throws Exception {
        when(certChecker.captureDirectChain(anyString(), anyInt(), anyInt()))
                .thenReturn(new X509Certificate[]{ leaf, ca });

        boolean changed = newService().pinFromServer("host.example.test", 443, "http-check");

        assertThat(changed).isTrue();
        PinnedCa pin = store.get("host.example.test:443");
        assertThat(pin).isNotNull();
        assertThat(pin.getSubject()).contains("Test Internal CA");
        // PEM tek sertifika içermeli (yalnız CA)
        assertThat(pin.getPem().split("BEGIN CERTIFICATE")).hasSize(2);
        assertThat(pin.getNotAfter()).isNotBlank();
        verify(auditService).recordAction(eq("CA_PINNED"), eq("system"), any(), any(), any(),
                eq("pinned_ca"), eq("host.example.test:443"), anyString(), any(), any(), any());
    }

    @Test
    @DisplayName("tek elemanlı zincir (sunucu yalnız leaf sunuyor) → leaf'in kendisi pinlenir")
    void leafOnlyChain_pinsLeaf() throws Exception {
        when(certChecker.captureDirectChain(anyString(), anyInt(), anyInt()))
                .thenReturn(new X509Certificate[]{ leaf });

        assertThat(newService().pinFromServer("solo.example.test", 443, "http-check")).isTrue();
        assertThat(store.get("solo.example.test:443").getSubject()).contains("leaf.example.test");
    }

    @Test
    @DisplayName("aynı fingerprint → no-op (false), ikinci save yok")
    void sameFingerprint_noop() throws Exception {
        when(certChecker.captureDirectChain(anyString(), anyInt(), anyInt()))
                .thenReturn(new X509Certificate[]{ leaf, ca });

        assertThat(newService().pinFromServer("host.example.test", 443, "a")).isTrue();
        // Yeni instance → taze rate-limit; repo store paylaşılıyor
        assertThat(newService().pinFromServer("host.example.test", 443, "b")).isFalse();
        verify(repo, times(1)).save(any());
    }

    @Test
    @DisplayName("rotasyon: fingerprint değişti → pin güncellenir, CA_ROTATED audit'lenir")
    void rotation_updatesPin_audited() throws Exception {
        when(certChecker.captureDirectChain(anyString(), anyInt(), anyInt()))
                .thenReturn(new X509Certificate[]{ leaf, ca });
        PinnedCa old = new PinnedCa();
        old.setHost("host.example.test");
        old.setPort(443);
        old.setPem("-----BEGIN CERTIFICATE-----\nold\n-----END CERTIFICATE-----\n");
        old.setFingerprintSha256("stale");
        old.setNotAfter("2020-01-01T00:00:00");
        old.setPinnedAt("2020-01-01T00:00:00");
        store.put("host.example.test:443", old);

        assertThat(newService().pinFromServer("host.example.test", 443, "http-check")).isTrue();
        assertThat(store.get("host.example.test:443").getFingerprintSha256()).isNotEqualTo("stale");
        verify(auditService).recordAction(eq("CA_ROTATED"), eq("system"), any(), any(), any(),
                eq("pinned_ca"), eq("host.example.test:443"), anyString(), any(), any(), any());
    }

    @Test
    @DisplayName("rate-limit: aynı instance'ta art arda ikinci deneme fetch bile yapmaz")
    void rateLimit_suppressesSecondFetch() throws Exception {
        when(certChecker.captureDirectChain(anyString(), anyInt(), anyInt()))
                .thenReturn(new X509Certificate[]{ leaf, ca });
        CaAutoPinService svc = newService();

        assertThat(svc.pinFromServer("host.example.test", 443, "a")).isTrue();
        assertThat(svc.pinFromServer("host.example.test", 443, "b")).isFalse();
        verify(certChecker, times(1)).captureDirectChain(anyString(), anyInt(), anyInt());
    }

    @Test
    @DisplayName("özellik kapalı → hiçbir şey yapılmaz")
    void disabled_noop() throws Exception {
        when(appSettings.getBoolean(eq(CaAutoPinService.ENABLED_KEY), anyBoolean())).thenReturn(false);

        assertThat(newService().pinFromServer("host.example.test", 443, "a")).isFalse();
        verify(certChecker, times(0)).captureDirectChain(anyString(), anyInt(), anyInt());
        assertThat(newService().trustManagerForHost("host.example.test", 443)).isNull();
    }

    @Test
    @DisplayName("direct zincir çekimi başarısız → proxy üzerinden yakalanıp pinlenir (RDAP/egress-kapalı ortam)")
    void directFails_proxyFallback_pins() throws Exception {
        when(certChecker.captureDirectChain(anyString(), anyInt(), anyInt()))
                .thenThrow(new java.io.IOException("connect timed out"));
        when(certChecker.captureProxyChain(anyString(), anyInt()))
                .thenReturn(new X509Certificate[]{ leaf, ca });

        assertThat(newService().pinFromServer("data.iana.org", 443, "rdap")).isTrue();
        assertThat(store.get("data.iana.org:443")).isNotNull();
        verify(certChecker).captureProxyChain("data.iana.org", 443);
    }

    @Test
    @DisplayName("direct VE proxy başarısız → pin edilmez, hata yutulur (false)")
    void bothCaptureFail_returnsFalse() throws Exception {
        when(certChecker.captureDirectChain(anyString(), anyInt(), anyInt()))
                .thenThrow(new java.io.IOException("connect timed out"));
        when(certChecker.captureProxyChain(anyString(), anyInt()))
                .thenThrow(new java.io.IOException("Proxy yapılandırılmamış"));

        assertThat(newService().pinFromServer("data.iana.org", 443, "rdap")).isFalse();
        assertThat(store).isEmpty();
    }

    @Test
    @DisplayName("isTrustFailure: PKIX/CertPath → true; hostname mismatch ve sıradan IO → false")
    void isTrustFailure_classification() {
        assertThat(CaAutoPinService.isTrustFailure(new javax.net.ssl.SSLHandshakeException(
                "PKIX path building failed: unable to find valid certification path to requested target"))).isTrue();
        assertThat(CaAutoPinService.isTrustFailure(new RuntimeException(
                new java.security.cert.CertPathBuilderException("no path")))).isTrue();
        assertThat(CaAutoPinService.isTrustFailure(new javax.net.ssl.SSLHandshakeException(
                "No subject alternative names matching IP address 127.0.0.1 found"))).isFalse();
        assertThat(CaAutoPinService.isTrustFailure(new java.net.ConnectException("connect timed out"))).isFalse();
        assertThat(CaAutoPinService.isTrustFailure(null)).isFalse();
    }

    @Test
    @DisplayName("normalizeHost: büyük harf, boşluk, IPv6 köşeli parantez")
    void normalizeHost_cases() {
        assertThat(CaAutoPinService.normalizeHost(" LocalHost ")).isEqualTo("localhost");
        assertThat(CaAutoPinService.normalizeHost("[::1]")).isEqualTo("::1");
        assertThat(CaAutoPinService.normalizeHost("Host.Example.TEST")).isEqualTo("host.example.test");
        assertThat(CaAutoPinService.normalizeHost(null)).isEqualTo("");
    }

    @Test
    @DisplayName("refreshExpiringPins: bitişi yaklaşan pin sunucudan yeniden çekilir ve döndürülür")
    void refreshExpiringPins_rotatesNearExpiry() throws Exception {
        when(certChecker.captureDirectChain(anyString(), anyInt(), anyInt()))
                .thenReturn(new X509Certificate[]{ leaf, ca });
        PinnedCa nearExpiry = new PinnedCa();
        nearExpiry.setHost("host.example.test");
        nearExpiry.setPort(443);
        nearExpiry.setPem("-----BEGIN CERTIFICATE-----\nold\n-----END CERTIFICATE-----\n");
        nearExpiry.setFingerprintSha256("stale");
        nearExpiry.setNotAfter("2020-01-01T00:00:00");
        nearExpiry.setPinnedAt("2020-01-01T00:00:00");
        store.put("host.example.test:443", nearExpiry);
        when(repo.findByNotAfterLessThanEqual(anyString())).thenReturn(List.of(nearExpiry));

        assertThat(newService().refreshExpiringPins()).isEqualTo(1);
        assertThat(store.get("host.example.test:443").getFingerprintSha256()).isNotEqualTo("stale");
        assertThat(store.get("host.example.test:443").getLastReason()).isEqualTo("scheduled-refresh");
    }

    // ── Y5: güven hatası kaydı KAYNAĞINA bağlı ve okuma YIKICI DEĞİL ─────────────

    @Test
    @DisplayName("bir alt sistemin güven hatası BAŞKA alt sistem adına pinlenemez")
    void trustFailuresAreScopedToTheirSource() {
        CaAutoPinService svc = newService();
        long wm = System.currentTimeMillis();
        svc.recordTrustFailure("webhook", "webhook-hedefi.example.com", 443);
        svc.recordTrustFailure("http-check", "izlenen.example.com", 443);

        // HTTP kontrolü yalnız KENDİ kaydını görür; webhook çıkışındaki PKIX hatası onun
        // adına "http-check" gerekçesiyle TOFU pinlenemez.
        assertThat(svc.recentTrustFailuresSince("http-check", wm))
                .containsExactly("izlenen.example.com:443");
        assertThat(svc.recentTrustFailuresSince("webhook", wm))
                .containsExactly("webhook-hedefi.example.com:443");
        assertThat(svc.recentTrustFailuresSince("rdap", wm)).isEmpty();
    }

    @Test
    @DisplayName("okuma YIKICI DEĞİL — paralel sweep'te bir monitör diğerinin kaydını çalamaz")
    void readingDoesNotConsumeOtherChecksRecords() {
        CaAutoPinService svc = newService();
        long wm = System.currentTimeMillis();
        svc.recordTrustFailure("http-check", "a.example.com", 443);
        svc.recordTrustFailure("http-check", "b.example.com", 443);

        // Eski drain iterator ile HER girdiyi siliyordu: önce koşan thread ikisini birden alıyor,
        // ikincisi BOŞ dönüyor ve pinned=false ile o tur HTTP_DOWN yazıyordu.
        assertThat(svc.recentTrustFailuresSince("http-check", wm))
                .containsExactlyInAnyOrder("a.example.com:443", "b.example.com:443");
        assertThat(svc.recentTrustFailuresSince("http-check", wm))
                .as("ikinci okuyucu da aynı kayıtları görmeli — okuma tüketmez")
                .containsExactlyInAnyOrder("a.example.com:443", "b.example.com:443");
    }

    @Test
    @DisplayName("su damgasından ÖNCEKİ kayıt bu kontrole ait değildir — dönmez")
    void recordsOlderThanTheWatermarkAreNotReturned() {
        CaAutoPinService svc = newService();
        svc.recordTrustFailure("http-check", "onceki-tur.example.com", 443);
        long wm = System.currentTimeMillis() + 1;   // kontrol BUNDAN sonra başladı
        assertThat(svc.recentTrustFailuresSince("http-check", wm)).isEmpty();
    }

    @Test
    @DisplayName("bileşik anahtar dizeye paketlenmez — ayırıcı taşıyan host çakışma üretmez")
    void compositeKeyIsARecordNotAPackedString() {
        CaAutoPinService svc = newService();
        long wm = System.currentTimeMillis();
        // "a:443" adlı bir kaynak ile "a" host/443 portu, dizeye paketlense aynı anahtara düşerdi.
        svc.recordTrustFailure("http-check", "a.example.com", 443);
        svc.recordTrustFailure("http-check:a.example.com", "x.example.com", 443);
        assertThat(svc.recentTrustFailuresSince("http-check", wm))
                .containsExactly("a.example.com:443");
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static X509Certificate selfSigned(String dn) throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        KeyPair kp = kpg.generateKeyPair();
        X500Name name = new X500Name(dn);
        Instant now = Instant.now();
        JcaX509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
                name, BigInteger.valueOf(System.nanoTime()),
                java.util.Date.from(now.minus(1, ChronoUnit.HOURS)),
                java.util.Date.from(now.plus(2, ChronoUnit.DAYS)),
                name, kp.getPublic());
        builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(true));
        ContentSigner signer = new JcaContentSignerBuilder("SHA256withRSA")
                .setProvider("BC").build(kp.getPrivate());
        return new JcaX509CertificateConverter().setProvider("BC")
                .getCertificate(builder.build(signer));
    }
}
