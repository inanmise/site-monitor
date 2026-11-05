package com.sitemonitor.service.mail;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Alarm iletimi bilgi postası (2026-11-05): sahip takım, alıcı, kapsam özeti (en çok 20 + "ve N tane daha"), ekleyen, not,
 * bitiş; uygulama adresi varsa "Alarm iletimi" bağlantısı; düz metin eşi; kullanıcı girdisi kaçırılır.
 */
class AlertForwardInfoMailTest {

    @Test
    @DisplayName("içerik: sahip, alıcı, üyeler, kapsam (+N), not, bitiş, bağlantı; HTML kaçırılır; düz metin dolu")
    void content() {
        List<String> monitors = new ArrayList<>();
        for (int i = 1; i <= 23; i++) monitors.add("HTTP/Website: s" + i);
        MailDoc.Mail m = AlertForwardInfoMail.build(new AlertForwardInfoMail.Info("Sipariş <b>", "Ağ Operasyon", false,
                List.of("Ayşe Yılmaz (Ağ / Uzman)"), "MONITORS", monitors, "Yönetici", "Not & açıklama", "2026-12-31",
                "https://sm.example.com/"));
        assertThat(m.html()).contains("Sipariş &lt;b&gt;").doesNotContain("Sipariş <b>")
                .contains("Ayşe Yılmaz (Ağ / Uzman)").contains("23 seçilen izleme").contains("HTTP/Website: s20")
                .doesNotContain("HTTP/Website: s21").contains("ve 3 tane daha").contains("Not &amp; açıklama")
                .contains("2026-12-31").contains("https://sm.example.com/?tab=admin&amp;g_tab=forwards");
        assertThat(m.text()).contains("Ağ Operasyon").contains("ve 3 tane daha").contains("Yönetici");
    }

    @Test
    @DisplayName("tüm takım + tüm izlemeler; adres yoksa bağlantı yok; konu sahip takımı söyler")
    void wholeTeamNoLink() {
        MailDoc.Mail m = AlertForwardInfoMail.build(new AlertForwardInfoMail.Info("Ödeme", "Güvenlik", true, List.of(), "ALL",
                List.of(), null, null, null, ""));
        assertThat(m.html()).contains("Tüm izlemeler").contains("Güvenlik (takımın tamamı)").contains("Süresiz")
                .doesNotContain("g_tab=forwards");
        assertThat(AlertForwardInfoMail.subject("Ödeme")).isEqualTo("[Site Monitor] Alarm iletimi: Ödeme takımının alarmları size de gelecek");
        assertThat(AlertForwardInfoMail.scopeSummary("TYPES", 2)).isEqualTo("2 izleme türünün tüm izlemeleri");
    }
}
