package com.sitemonitor.service;

import com.sitemonitor.model.AlertThreshold;
import com.sitemonitor.model.CertificateInventory;
import com.sitemonitor.model.LatestCheck;
import com.sitemonitor.repository.AlertThresholdRepository;
import com.sitemonitor.service.CertificateHealthRules.Status;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Sağlık kontrol listesinin TEK değerlendirme çekirdeği.
 *
 * <p>Burada test edilen şey "rozet ne renk" değil, HÜKÜM: hangi girdi hangi satırı hangi duruma
 * ve hangi AKSİYONA götürüyor. Aksiyon metni eşiklerle konuşmak zorunda — "17 gün kaldı" tek
 * başına bir karar değildir, uyarı eşiğinin altındaysa yenileme planlanmalıdır.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CertificateHealthServiceTest {

    @Mock AlertThresholdRepository thresholdRepo;
    @InjectMocks CertificateHealthService service;

    @BeforeEach
    void defaults() {
        AlertThreshold th = new AlertThreshold();
        th.setWarningDays(30);
        th.setCriticalDays(7);
        when(thresholdRepo.findFirstByActiveTrue()).thenReturn(Optional.of(th));
    }

    /** Her satırı OK yapan sağlıklı taban — testler yalnız ilgilendikleri alanı bozar. */
    private static LatestCheck healthy() {
        LatestCheck lc = new LatestCheck();
        lc.setDomain("a.example.com");
        lc.setNotBefore("2026-01-01T00:00:00");
        lc.setNotAfter("2027-01-01T00:00:00");
        lc.setDaysRemaining(120);
        lc.setRevocationStatus("VALID");
        lc.setChainStatus("VALID");
        lc.setTrustStatus("TRUSTED");
        lc.setDeploymentStatus("OK");
        lc.setPinnedFingerprint("AA:BB:CC");
        lc.setPinnedAt("2026-06-01T00:00:00");
        lc.setSan("[\"a.example.com\"]");
        lc.setSignatureAlgorithm("SHA256withRSA");
        lc.setPublicKeyAlgorithm("RSA");
        lc.setPublicKeySize(2048);
        lc.setIntermediateDaysRemaining(200);
        lc.setTlsVersion("TLSv1.3");
        lc.setCipherSuite("TLS_AES_256_GCM_SHA384");
        lc.setHstsStatus("ENABLED");
        lc.setMixedContentStatus("CLEAN");
        lc.setCheckedAt("2026-08-23T10:00:00");
        return lc;
    }

    private static CertificateInventory inv() {
        CertificateInventory i = new CertificateInventory();
        i.setDomain("a.example.com");
        i.setPort(443);
        i.setTeamId(5L);
        return i;
    }

    private CertificateHealthService.HealthRow row(LatestCheck lc, String key) {
        return service.evaluate(lc, inv(), true).rows().stream()
                .filter(r -> r.key().equals(key)).findFirst().orElseThrow();
    }

    // ── Satır kümesi ────────────────────────────────────────────────────────

    @Test
    @DisplayName("Kanonik satırların TAMAMI ve doğru SIRAYLA üretilir")
    void allRowsInCanonicalOrder() {
        var result = service.evaluate(healthy(), inv(), true);
        assertThat(result.rows()).extracting("key")
                .containsExactlyElementsOf(CertificateHealthService.ROW_KEYS);
    }

    @Test
    @DisplayName("Sağlıklı sertifikada her satır temiz ve hiçbirinde aksiyon yok")
    void healthyCertificateIsAllClear() {
        var result = service.evaluate(healthy(), inv(), true);
        assertThat(result.rows()).allMatch(r -> r.status() == Status.OK);
        assertThat(result.rows()).allMatch(r -> r.actionKey().equals("none"));
        assertThat(result.allClear()).isTrue();
        assertThat(result.okCount()).isEqualTo(result.evaluatedCount());
    }

    @Test
    @DisplayName("Hiç kontrol edilmemiş domain: uydurma OK üretilmez, tek UNKNOWN satır döner")
    void neverCheckedDomain() {
        var result = service.evaluate(null, inv(), false);
        assertThat(result.rows()).hasSize(1);
        assertThat(result.rows().get(0).status()).isEqualTo(Status.UNKNOWN);
        assertThat(result.evaluatedCount()).isZero();
        assertThat(result.allClear()).isFalse();
    }

    // ── Süre: aksiyon EŞİKLERLE konuşur ─────────────────────────────────────

    @Test
    @DisplayName("17 gün kalınca 'işlem gerekmez' DEĞİL, eşiğiyle birlikte yenileme planı")
    void expiryWarnSpeaksWithThresholds() {
        LatestCheck lc = healthy();
        lc.setDaysRemaining(17);
        var r = row(lc, "expiry");

        assertThat(r.status()).isEqualTo(Status.WARN);
        assertThat(r.actionKey()).isEqualTo("renewPlan");
        assertThat(r.actionArgs()).containsExactly(17, 30);   // kalan gün + uyarı eşiği
        assertThat(r.valueArgs()).containsExactly(17);
    }

    @Test
    @DisplayName("Kritik eşiğin altında aksiyon ACİL, süresi dolmuşta ayrı metin")
    void expiryCriticalAndExpired() {
        LatestCheck lc = healthy();
        lc.setDaysRemaining(5);
        var crit = row(lc, "expiry");
        assertThat(crit.status()).isEqualTo(Status.FAIL);
        assertThat(crit.actionKey()).isEqualTo("renewUrgent");
        assertThat(crit.actionArgs()).containsExactly(5, 7);

        lc.setDaysRemaining(-3);
        var expired = row(lc, "expiry");
        assertThat(expired.status()).isEqualTo(Status.FAIL);
        assertThat(expired.valueKey()).isEqualTo("expired");
        assertThat(expired.valueArgs()).containsExactly(3);   // mutlak değer: "3 gün önce doldu"
        assertThat(expired.actionKey()).isEqualTo("renewNow");
    }

    @Test
    @DisplayName("Eşik okunamazsa varsayılana düşer, değerlendirme DÜŞMEZ")
    void thresholdFailureFallsBack() {
        when(thresholdRepo.findFirstByActiveTrue()).thenThrow(new RuntimeException("db yok"));
        LatestCheck lc = healthy();
        lc.setDaysRemaining(17);
        assertThat(row(lc, "expiry").status()).isEqualTo(Status.WARN);   // varsayılan 30/7
    }

    // ── UNKNOWN ≠ FAIL ──────────────────────────────────────────────────────

    @Test
    @DisplayName("İptal durumu doğrulanamadıysa UNKNOWN — proxy gerçeği kırmızıya boyanmaz")
    void unknownRevocationIsNotFailure() {
        LatestCheck lc = healthy();
        lc.setRevocationStatus("UNKNOWN");
        lc.setOcspUrl("http://ocsp.example.com");
        var r = row(lc, "revocation");

        assertThat(r.status()).isEqualTo(Status.UNKNOWN);
        assertThat(r.valueKey()).isEqualTo("unverified");
        assertThat(r.actionKey()).isEqualTo("checkNetworkAccess");
        assertThat(r.evidence()).containsEntry("ocsp_url", "http://ocsp.example.com");
    }

    @Test
    @DisplayName("REVOKED gerçek bir hatadır ve derhal değiştirme aksiyonu verir")
    void revokedIsFailure() {
        LatestCheck lc = healthy();
        lc.setRevocationStatus("REVOKED");
        var r = row(lc, "revocation");
        assertThat(r.status()).isEqualTo(Status.FAIL);
        assertThat(r.actionKey()).isEqualTo("replaceNow");
    }

    @Test
    @DisplayName("Eski kayıtta protokol/cipher boş: UNKNOWN sayılır, ÖZETİN paydasına girmez")
    void missingTlsDataIsExcludedFromSummary() {
        LatestCheck lc = healthy();
        lc.setTlsVersion(null);
        lc.setCipherSuite(null);
        var result = service.evaluate(lc, inv(), true);

        assertThat(row(lc, "protocol").status()).isEqualTo(Status.UNKNOWN);
        assertThat(row(lc, "cipher").status()).isEqualTo(Status.UNKNOWN);
        assertThat(row(lc, "pfs").status()).isEqualTo(Status.UNKNOWN);
        // 14 satırın 3'ü doğrulanamadı → payda 11, hepsi temiz.
        assertThat(result.evaluatedCount()).isEqualTo(11);
        assertThat(result.okCount()).isEqualTo(11);
        assertThat(result.allClear()).isTrue();
    }


    // ── SAN kapsaması ve parmak izi pini ────────────────────────────────────
    //
    // Kullanıcı bildirimi (2026-08-23): "Domain is covered by the certificate" satırı sağlıklı
    // sertifikalarda bile "Doğrulanamadı" gösteriyordu. İki hata birdeydi ve ikisi de burada
    // pinleniyor.

    @Test
    @DisplayName("SAN listesi domaini kapsıyorsa satır TEMİZ (eskiden hep UNKNOWN'a düşüyordu)")
    void sanRowPassesWhenCovered() {
        LatestCheck lc = healthy();
        lc.setSan("[\"a.example.com\",\"www.example.com\"]");
        var r = row(lc, "sanMatch");

        assertThat(r.status()).isEqualTo(Status.OK);
        assertThat(r.valueKey()).isEqualTo("complete");
        assertThat(r.actionKey()).isEqualTo("none");
    }

    @Test
    @DisplayName("Joker sertifika alt alan adını kapsar")
    void sanRowAcceptsWildcard() {
        LatestCheck lc = healthy();
        lc.setSan("[\"*.example.com\"]");
        assertThat(row(lc, "sanMatch").status()).isEqualTo(Status.OK);
    }

    @Test
    @DisplayName("Kapsamayan sertifikada satır HATA verir ve SAN listesi kanıtta durur")
    void sanRowFailsWhenNotCovered() {
        LatestCheck lc = healthy();
        lc.setSan("[\"baska.example.com\"]");
        var r = row(lc, "sanMatch");

        assertThat(r.status()).isEqualTo(Status.FAIL);
        assertThat(r.actionKey()).isEqualTo("fixDeployment");
        assertThat(r.evidence()).containsEntry("san", "baska.example.com");
    }

    @Test
    @DisplayName("SAN yoksa ya da BOZUK JSON ise UNKNOWN — değerlendirme çökmez")
    void sanRowUnknownOnMissingOrBrokenData() {
        LatestCheck lc = healthy();
        lc.setSan(null);
        assertThat(row(lc, "sanMatch").status()).isEqualTo(Status.UNKNOWN);

        lc.setSan("{bozuk json");
        assertThat(row(lc, "sanMatch").status()).isEqualTo(Status.UNKNOWN);
    }

    // ── Sertifika değişimi (TOFU otomatik pin) ──────────────────────────────
    //
    // Kullanıcı kararı (2026-08-23): "manuel bir şekilde pinleme yapmak istemiyorum... sertifika
    // ilk değiştiğini fark ettiğinde hemen yeni pini pinle." Satır artık elle sabitlenmiş bir pinle
    // uyumu DEĞİL, sertifikanın sessizce değişip değişmediğini anlatıyor.

    @Test
    @DisplayName("Parmak izi kendiliğinden sabitlenmiş ve değişmemişse satır TEMİZ")
    void certChangeRowIsCleanWhenStable() {
        var r = row(healthy(), "pinnedFingerprint");

        assertThat(r.status()).isEqualTo(Status.OK);
        assertThat(r.valueKey()).isEqualTo("certStable");
        assertThat(r.actionKey()).isEqualTo("none");
        assertThat(r.evidence()).containsEntry("pinned_at", "2026-06-01T00:00:00");
    }

    @Test
    @DisplayName("YENİ değişim UYARI verir — HATA değil: yenileme normal ve beklenen bir olaydır")
    void recentChangeIsWarningNotFailure() {
        LatestCheck lc = healthy();
        lc.setPinnedFingerprint("YENI");
        lc.setPreviousFingerprint("ESKI");
        lc.setFingerprintChangedAt(java.time.LocalDateTime.now(java.time.ZoneOffset.UTC)
                .minusDays(1).withNano(0).toString());
        var r = row(lc, "pinnedFingerprint");

        assertThat(r.status()).isEqualTo(Status.WARN);
        assertThat(r.valueKey()).isEqualTo("certChanged");
        assertThat(r.actionKey()).isEqualTo("confirmRenewal");
        // "Neyden neye" sorusu kanıtta cevaplanır.
        assertThat(r.evidence()).containsEntry("previous", "ESKI").containsEntry("pinned", "YENI");
    }

    @Test
    @DisplayName("ESKİ değişim kendiliğinden yeşile döner — satır kalıcı sarı kalmaz")
    void oldChangeReturnsToClean() {
        LatestCheck lc = healthy();
        lc.setFingerprintChangedAt(java.time.LocalDateTime.now(java.time.ZoneOffset.UTC)
                .minusDays(30).withNano(0).toString());

        assertThat(row(lc, "pinnedFingerprint").status()).isEqualTo(Status.OK);
    }

    @Test
    @DisplayName("Sunulan sertifika pinden FARKLIYSA satır KIRMIZI — araya girme imzası")
    void servedDifferingFromPinIsFailure() {
        LatestCheck lc = healthy();
        // Güven kapısı yeniden sabitlemeyi engelledi (CertificateService.applyAutoPin): pin duruyor,
        // sunulan başka. Kapı olmasaydı bu satır sessizce yeşile döner, araya giren taraf pini
        // kendi lehine yazdırırdı.
        lc.setFingerprint("MODEM");

        var r = row(lc, "pinnedFingerprint");

        assertThat(r.status()).isEqualTo(Status.FAIL);
        assertThat(r.valueKey()).isEqualTo("certPinMismatch");
        assertThat(r.actionKey()).isEqualTo("investigateInterception");
        assertThat(r.evidence()).containsEntry("served", "MODEM").containsEntry("pinned", "AA:BB:CC");
    }

    @Test
    @DisplayName("Sunulan pinle aynıysa (harf farkı dahil) satır TEMİZ kalır")
    void servedMatchingThePinStaysClean() {
        LatestCheck lc = healthy();
        lc.setFingerprint("aa:bb:cc");

        assertThat(row(lc, "pinnedFingerprint").status()).isEqualTo(Status.OK);
    }

    @Test
    @DisplayName("Hiç parmak izi görülmemişse UNKNOWN (erişilemeyen domain)")
    void noFingerprintSeenIsUnknown() {
        LatestCheck lc = healthy();
        lc.setPinnedFingerprint(null);
        var r = row(lc, "pinnedFingerprint");

        assertThat(r.status()).isEqualTo(Status.UNKNOWN);
        assertThat(r.actionKey()).isEqualTo("runCheck");
    }

    @Test
    @DisplayName("Bozuk değişim tarihi UYARI ÜRETMEZ — biçim hatası yanlış alarma dönüşmemeli")
    void malformedChangeDateDoesNotWarn() {
        LatestCheck lc = healthy();
        lc.setFingerprintChangedAt("tarih-degil");

        assertThat(row(lc, "pinnedFingerprint").status()).isEqualTo(Status.OK);
    }

    // ── Protokol ve şifreleme ───────────────────────────────────────────────

    @Test
    @DisplayName("TLS 1.2 kabul edilir ama '1.3'ü değerlendirin' aksiyonu taşır")
    void tls12IsAcceptedWithNudge() {
        LatestCheck lc = healthy();
        lc.setTlsVersion("TLSv1.2");
        lc.setCipherSuite("TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256");
        var r = row(lc, "protocol");

        assertThat(r.status()).isEqualTo(Status.OK);
        assertThat(r.valueKey()).isEqualTo("acceptedProtocol");
        assertThat(r.actionKey()).isEqualTo("considerTls13");
    }

    @Test
    @DisplayName("TLS 1.0 hatadır; kanıtta kullanılan TLS modu taşınır (browser notu için)")
    void oldProtocolFailsAndCarriesMode() {
        LatestCheck lc = healthy();
        lc.setTlsVersion("TLSv1");
        lc.setTlsModeUsed("browser");
        var r = row(lc, "protocol");

        assertThat(r.status()).isEqualTo(Status.FAIL);
        assertThat(r.actionKey()).isEqualTo("disableOldProtocols");
        assertThat(r.evidence()).containsEntry("tls_mode_used", "browser");
    }

    @Test
    @DisplayName("CBC süiti UYARI verir ve AEAD önerir; cipher adı kanıtta durur")
    void cbcCipherWarns() {
        LatestCheck lc = healthy();
        lc.setTlsVersion("TLSv1.2");
        lc.setCipherSuite("TLS_ECDHE_RSA_WITH_AES_256_CBC_SHA384");
        var r = row(lc, "cipher");

        assertThat(r.status()).isEqualTo(Status.WARN);
        assertThat(r.valueKey()).isEqualTo("cipherAcceptable");
        assertThat(r.actionKey()).isEqualTo("preferAead");
        assertThat(r.evidence()).containsEntry("cipher_suite", "TLS_ECDHE_RSA_WITH_AES_256_CBC_SHA384");
    }

    @Test
    @DisplayName("Statik RSA anahtar değişiminde PFS KAPALI olarak raporlanır")
    void staticRsaHasNoPfs() {
        LatestCheck lc = healthy();
        lc.setTlsVersion("TLSv1.2");
        lc.setCipherSuite("TLS_RSA_WITH_AES_128_GCM_SHA256");
        var r = row(lc, "pfs");

        assertThat(r.status()).isEqualTo(Status.FAIL);
        assertThat(r.actionKey()).isEqualTo("enablePfs");
    }

    // ── Uygulama katmanı ────────────────────────────────────────────────────

    @Test
    @DisplayName("Hiç bakılmamış HSTS: UNKNOWN + istemli kontrol aksiyonu (otomatik koşmaz)")
    void hstsNotCheckedYet() {
        LatestCheck lc = healthy();
        lc.setHstsStatus(null);
        var r = row(lc, "hsts");

        assertThat(r.status()).isEqualTo(Status.UNKNOWN);
        assertThat(r.valueKey()).isEqualTo("notChecked");
        assertThat(r.actionKey()).isEqualTo("checkOnDemand");
    }

    @Test
    @DisplayName("HSTS yoksa UYARI — sertifika sorunu değil ama eksik bir sertleştirme")
    void hstsMissingIsWarning() {
        LatestCheck lc = healthy();
        lc.setHstsStatus("MISSING");
        var r = row(lc, "hsts");
        assertThat(r.status()).isEqualTo(Status.WARN);
        assertThat(r.actionKey()).isEqualTo("enableHsts");
    }

    @Test
    @DisplayName("HSTS doğrulanamadıysa SEBEBİ kanıtta taşınır (sebepsiz 'Doğrulanamadı' kör nokta)")
    void hstsUnverifiedCarriesReason() {
        LatestCheck lc = healthy();
        lc.setHstsStatus("UNKNOWN");
        lc.setHstsNote("connect timed out (monitor_prefers_direct)");
        var r = row(lc, "hsts");

        assertThat(r.status()).isEqualTo(Status.UNKNOWN);
        assertThat(r.valueKey()).isEqualTo("unverified");
        // Kullanıcı "Doğrulanamadı" görüp neden olduğunu ekranda bulamadığı için bu iş açıldı.
        assertThat(r.evidence()).containsEntry("note", "connect timed out (monitor_prefers_direct)");
    }

    @Test
    @DisplayName("HSTS açıkken kanıtta asılı kalmış bir sebep BULUNMAZ")
    void hstsEnabledHasNoStaleReason() {
        LatestCheck lc = healthy();
        lc.setHstsStatus("ENABLED");
        lc.setHstsNote(null);
        var r = row(lc, "hsts");

        assertThat(r.status()).isEqualTo(Status.OK);
        assertThat(r.evidence().get("note")).isNull();
    }

    @Test
    @DisplayName("Sayfa izlemesi YOKSA karışık içerik aksiyonu iki yol sunar")
    void mixedContentWithoutPageMonitor() {
        LatestCheck lc = healthy();
        lc.setMixedContentStatus(null);
        var r = service.evaluate(lc, inv(), false).rows().stream()
                .filter(x -> x.key().equals("mixedContent")).findFirst().orElseThrow();

        assertThat(r.status()).isEqualTo(Status.UNKNOWN);
        assertThat(r.actionKey()).isEqualTo("checkOnDemandOrAddPageMonitor");
        assertThat(r.evidence()).containsEntry("source", "onDemand");
    }

    @Test
    @DisplayName("Sayfa izlemesi VARSA karışık içerik aksiyonu oraya yönlendirir")
    void mixedContentWithPageMonitor() {
        LatestCheck lc = healthy();
        lc.setMixedContentStatus("MIXED");
        var r = row(lc, "mixedContent");

        assertThat(r.status()).isEqualTo(Status.FAIL);
        assertThat(r.actionKey()).isEqualTo("fixMixedSeePageMonitor");
        assertThat(r.evidence()).containsEntry("source", "pageMonitor");
    }

    // ── Kanıt ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Boş kanıt alanları ELENİR — arayüzde boş satır çizilmez")
    void blankEvidenceIsDropped() {
        LatestCheck lc = healthy();
        lc.setOcspUrl(null);
        lc.setCrlUrl("   ");
        var r = row(lc, "revocation");

        assertThat(r.evidence()).doesNotContainKey("ocsp_url").doesNotContainKey("crl_url");
        assertThat(r.evidence()).containsEntry("raw", "VALID");
    }

    @Test
    @DisplayName("Satır metinleri ANAHTAR döner — backend cümle kurmaz (i18n arayüzde)")
    void rowsCarryKeysNotSentences() {
        for (var r : service.evaluate(healthy(), inv(), true).rows()) {
            assertThat(r.valueKey()).doesNotContain(" ");
            assertThat(r.actionKey()).doesNotContain(" ");
        }
    }
}
