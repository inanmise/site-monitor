package com.sitemonitor.service;

import com.sitemonitor.model.LatestCheck;
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

    private static LatestCheck pinned(String fp, String at) {
        LatestCheck lc = new LatestCheck();
        lc.setPinnedFingerprint(fp);
        lc.setPinnedAt(at);
        return lc;
    }

    @Test
    @DisplayName("İLK görüşte sessizce sabitlenir — değişim olarak raporlanmaz")
    void firstSightPinsSilently() {
        LatestCheck lc = new LatestCheck();

        CertificateService.applyAutoPin(lc, "AA:BB", NOW);

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

        CertificateService.applyAutoPin(lc, "AA:BB", NOW);

        assertThat(lc.getPinnedAt()).isEqualTo("2026-06-01T00:00:00");
        assertThat(lc.getFingerprintChangedAt()).isNull();
    }

    @Test
    @DisplayName("Parmak izi büyük/küçük harf farkıyla gelirse DEĞİŞİM sayılmaz")
    void caseDifferenceIsNotAChange() {
        LatestCheck lc = pinned("aa:bb:cc", "2026-06-01T00:00:00");

        CertificateService.applyAutoPin(lc, "AA:BB:CC", NOW);

        assertThat(lc.getFingerprintChangedAt()).isNull();
    }

    @Test
    @DisplayName("Sertifika değişince ESKİSİ saklanır, YENİSİ hemen sabitlenir ve an kaydedilir")
    void changeRepinsAndRecords() {
        LatestCheck lc = pinned("ESKI", "2026-06-01T00:00:00");

        CertificateService.applyAutoPin(lc, "YENI", NOW);

        assertThat(lc.getPreviousFingerprint()).isEqualTo("ESKI");
        assertThat(lc.getPinnedFingerprint()).isEqualTo("YENI");
        assertThat(lc.getPinnedAt()).isEqualTo(NOW);
        assertThat(lc.getFingerprintChangedAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("Yeniden sabitlemeden SONRA aynı sertifika sürerse ikinci uyarı üretilmez")
    void afterRepinTheNewCertIsTheBaseline() {
        LatestCheck lc = pinned("ESKI", "2026-06-01T00:00:00");
        CertificateService.applyAutoPin(lc, "YENI", NOW);

        CertificateService.applyAutoPin(lc, "YENI", "2026-08-23T13:00:00");

        // Değişim anı İLK fark edildiği anda kalır; her kontrolde tazelenirse satır hiç yeşile dönmez.
        assertThat(lc.getFingerprintChangedAt()).isEqualTo(NOW);
        assertThat(lc.getPinnedAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("Parmak izi okunamadıysa pin KORUNUR — erişim sorunu değişim gibi gösterilmez")
    void missingFingerprintKeepsThePin() {
        LatestCheck lc = pinned("AA:BB", "2026-06-01T00:00:00");

        CertificateService.applyAutoPin(lc, null, NOW);
        CertificateService.applyAutoPin(lc, "   ", NOW);

        assertThat(lc.getPinnedFingerprint()).isEqualTo("AA:BB");
        assertThat(lc.getFingerprintChangedAt()).isNull();
        assertThat(lc.getPreviousFingerprint()).isNull();
    }
}
