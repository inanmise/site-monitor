package com.sitemonitor.service;

import com.sitemonitor.model.LatestCheck;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Otomatik parmak izi pini (TOFU — ilk görüşte güven).
 *
 * <p>Kullanıcı kararı (2026-08-23): "manuel bir şekilde pinleme yapmak istemiyorum… sertifika ilk
 * değiştiğini fark ettiğinde hemen yeni pini pinle, sonra aynı pin üzerinden devam et."
 *
 * <p>Bu mantık HER kontrolün yazma yolunda (süpürme + manuel) koşuyor; bir hata burada sessizdir:
 * pin yanlış güncellenirse ya gerçek bir sertifika değişimi hiç görünmez, ya da her kontrolde
 * sahte "değişti" uyarısı çıkar.
 */
class CertificateAutoPinTest {

    private static final String NOW = "2026-08-23T12:00:00";

    private static final String HOST = "a.example.com";
    /** Kusursuz gözlem: sertifika bu adı kapsıyor ve zinciri güven köküne bağlanıyor. */
    private static final List<String> SAN = List.of(HOST);

    private static LatestCheck fresh() {
        LatestCheck lc = new LatestCheck();
        lc.setDomain(HOST);
        lc.setTrustStatus("TRUSTED");
        return lc;
    }

    private static LatestCheck pinned(String fp, String at) {
        LatestCheck lc = fresh();
        lc.setPinnedFingerprint(fp);
        lc.setPinnedAt(at);
        return lc;
    }

    @Test
    @DisplayName("İLK görüşte sessizce sabitlenir — değişim olarak raporlanmaz")
    void firstSightPinsSilently() {
        LatestCheck lc = fresh();

        CertificateService.applyAutoPin(lc, "AA:BB", NOW, SAN);

        assertThat(lc.getPinnedFingerprint()).isEqualTo("AA:BB");
        assertThat(lc.getPinnedAt()).isEqualTo(NOW);
        // İlk kez görmek bir DEĞİŞİM değildir: kullanıcı yeni eklediği her domain için uyarı almasın.
        assertThat(lc.getFingerprintChangedAt()).isNull();
        assertThat(lc.getPreviousFingerprint()).isNull();
    }

    @Test
    @DisplayName("Aynı sertifika sürerken hiçbir alan DEĞİŞMEZ (gereksiz yazma yok)")
    void unchangedCertificateIsLeftAlone() {
        LatestCheck lc = pinned("AA:BB", "2026-06-01T00:00:00");

        CertificateService.applyAutoPin(lc, "AA:BB", NOW, SAN);

        assertThat(lc.getPinnedAt()).isEqualTo("2026-06-01T00:00:00");
        assertThat(lc.getFingerprintChangedAt()).isNull();
    }

    @Test
    @DisplayName("Parmak izi büyük/küçük harf farkıyla gelirse DEĞİŞİM sayılmaz")
    void caseDifferenceIsNotAChange() {
        LatestCheck lc = pinned("aa:bb:cc", "2026-06-01T00:00:00");

        CertificateService.applyAutoPin(lc, "AA:BB:CC", NOW, SAN);

        assertThat(lc.getFingerprintChangedAt()).isNull();
    }

    @Test
    @DisplayName("Sertifika değişince ESKİSİ saklanır, YENİSİ hemen sabitlenir ve an kaydedilir")
    void changeRepinsAndRecords() {
        LatestCheck lc = pinned("ESKI", "2026-06-01T00:00:00");

        CertificateService.applyAutoPin(lc, "YENI", NOW, SAN);

        assertThat(lc.getPreviousFingerprint()).isEqualTo("ESKI");
        assertThat(lc.getPinnedFingerprint()).isEqualTo("YENI");
        assertThat(lc.getPinnedAt()).isEqualTo(NOW);
        assertThat(lc.getFingerprintChangedAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("Yeniden sabitlemeden SONRA aynı sertifika sürerse ikinci uyarı üretilmez")
    void afterRepinTheNewCertIsTheBaseline() {
        LatestCheck lc = pinned("ESKI", "2026-06-01T00:00:00");
        CertificateService.applyAutoPin(lc, "YENI", NOW, SAN);

        CertificateService.applyAutoPin(lc, "YENI", "2026-08-23T13:00:00", SAN);

        // Değişim anı İLK fark edildiği anda kalır; her kontrolde tazelenirse satır hiç yeşile dönmez.
        assertThat(lc.getFingerprintChangedAt()).isEqualTo(NOW);
        assertThat(lc.getPinnedAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("Parmak izi okunamadıysa pin KORUNUR — erişim sorunu değişim gibi gösterilmez")
    void missingFingerprintKeepsThePin() {
        LatestCheck lc = pinned("AA:BB", "2026-06-01T00:00:00");

        CertificateService.applyAutoPin(lc, null, NOW, SAN);
        CertificateService.applyAutoPin(lc, "   ", NOW, SAN);

        assertThat(lc.getPinnedFingerprint()).isEqualTo("AA:BB");
        assertThat(lc.getFingerprintChangedAt()).isNull();
        assertThat(lc.getPreviousFingerprint()).isNull();
    }

    // ── Güven kapısı: araya giren sertifika pini EZEMEZ ────────────────────────

    @Test
    @DisplayName("Sunulan sertifika bu adı KAPSAMIYORSA pin korunur, damga basılmaz")
    void hostnameMismatchKeepsThePin() {
        LatestCheck lc = pinned("GERCEK", "2026-06-01T00:00:00");

        // NXDOMAIN-hijack imzası: alan adı yerel modeme çözülmüş, sertifika CN=192.168.1.1.
        CertificateService.applyAutoPin(lc, "MODEM", NOW, List.of("192.168.1.1"));

        assertThat(lc.getPinnedFingerprint()).isEqualTo("GERCEK");
        assertThat(lc.getPreviousFingerprint()).isNull();
        assertThat(lc.getFingerprintChangedAt()).isNull();
    }

    @Test
    @DisplayName("Sunulan sertifika GÜVENİLMİYORSA pin korunur, damga basılmaz")
    void untrustedCertificateKeepsThePin() {
        LatestCheck lc = pinned("GERCEK", "2026-06-01T00:00:00");
        lc.setTrustStatus("UNTRUSTED");

        CertificateService.applyAutoPin(lc, "ARAYA_GIREN", NOW, SAN);

        assertThat(lc.getPinnedFingerprint()).isEqualTo("GERCEK");
        assertThat(lc.getFingerprintChangedAt()).isNull();
    }

    @Test
    @DisplayName("Kusurlu gözlem İLK sabitlemeyi de yapmaz — pin baştan kirletilemez")
    void defectiveObservationDoesNotSeedThePin() {
        LatestCheck lc = fresh();
        lc.setTrustStatus("UNTRUSTED");

        CertificateService.applyAutoPin(lc, "MODEM", NOW, List.of("192.168.1.1"));

        assertThat(lc.getPinnedFingerprint()).isNull();
        assertThat(lc.getPinnedAt()).isNull();
    }

    @Test
    @DisplayName("Araya girme geçtikten sonra GERÇEK sertifika ikinci bir sahte damga üretmez")
    void realCertificateAfterInterceptionIsNotAChange() {
        LatestCheck lc = pinned("GERCEK", "2026-06-01T00:00:00");
        lc.setTrustStatus("UNTRUSTED");
        CertificateService.applyAutoPin(lc, "MODEM", NOW, List.of("192.168.1.1"));

        // Ağ düzeldi: aynı gerçek sertifika geri döndü. Pin hiç kirlenmediği için bu bir DEĞİŞİM değil.
        lc.setTrustStatus("TRUSTED");
        CertificateService.applyAutoPin(lc, "GERCEK", "2026-08-23T13:00:00", SAN);

        assertThat(lc.getPinnedFingerprint()).isEqualTo("GERCEK");
        assertThat(lc.getFingerprintChangedAt()).isNull();
        assertThat(lc.getPreviousFingerprint()).isNull();
    }

    @Test
    @DisplayName("KANIT YOKLUĞU kapıyı kapatmaz: SAN boş / güven hesaplanmamışsa TOFU çalışır")
    void unknownEvidenceStillPins() {
        LatestCheck lc = fresh();
        lc.setTrustStatus(null);

        CertificateService.applyAutoPin(lc, "AA:BB", NOW, List.of());

        // Hafif kontroller SAN/trust üretmeyebilir; bilinmeyeni kusur sayarsak hiçbir pin kurulmaz.
        assertThat(lc.getPinnedFingerprint()).isEqualTo("AA:BB");
    }
}
