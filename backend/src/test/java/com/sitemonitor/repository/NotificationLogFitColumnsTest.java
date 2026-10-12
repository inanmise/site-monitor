package com.sitemonitor.repository;

import com.sitemonitor.model.NotificationLog;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.test.context.TestPropertySource;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code notification_logs} VARCHAR(255) kolonları (2026-10-12): uzun alıcı listesi / durum metni satırı DÜŞÜRMEZ —
 * varlık yazmadan önce sığdırır (alıcı listesi virgül sınırında "… +N"). H2 uzunluğu zorlar: kırpma olmasa kayıt atardı.
 */
@DataJpaTest
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
class NotificationLogFitColumnsTest {

    @Autowired NotificationLogRepository repo;

    private static String addresses(int n) {
        List<String> out = new ArrayList<>();
        for (int i = 1; i <= n; i++) out.add("kullanici.adi.soyadi" + i + "@ornek-kurum.example.com");
        return String.join(", ", out);
    }

    @Test
    @DisplayName("uzun alıcı listesi + uzun durum metni → satır yazılır; liste tam adreslerle '… +N', durum '…' ile biter")
    void longValuesAreFittedNotDropped() {
        NotificationLog n = new NotificationLog();
        n.setAlertEventId(0L);
        n.setSentAt("2026-10-12T09:00:00");
        n.setRecipientName("Kendi kendini izleme");
        n.setRecipientEmail(addresses(30));
        n.setEmailStatus("FAILED: " + "x".repeat(600));
        n.setWebhookStatus("SENT");
        n.setTrigger("WATCHDOG");
        n.setCc(addresses(12));
        NotificationLog saved = repo.saveAndFlush(n);

        NotificationLog back = repo.findById(saved.getId()).orElseThrow();
        assertThat(back.getRecipientEmail()).hasSizeLessThanOrEqualTo(NotificationLog.VARCHAR_MAX)
                .startsWith("kullanici.adi.soyadi1@ornek-kurum.example.com, ").containsPattern(" … \\+\\d+$");
        // yarım adres yok: "… +N" öncesi her öğe tam bir adres
        String kept = back.getRecipientEmail().replaceAll(" … \\+\\d+$", "");
        for (String a : kept.split(", ")) assertThat(a).matches("kullanici\\.adi\\.soyadi\\d+@ornek-kurum\\.example\\.com");
        int keptCount = kept.split(", ").length;
        assertThat(back.getRecipientEmail()).endsWith("+" + (30 - keptCount));
        assertThat(back.getEmailStatus()).hasSize(NotificationLog.VARCHAR_MAX).endsWith("…");
        assertThat(back.getCc()).hasSizeLessThanOrEqualTo(NotificationLog.VARCHAR_MAX);
    }

    @Test
    @DisplayName("sığan değerlere dokunulmaz; tek dev öğe düz kırpılır")
    void shortValuesUntouched() {
        assertThat(NotificationLog.fitList("a@x.com, b@x.com", 255)).isEqualTo("a@x.com, b@x.com");
        assertThat(NotificationLog.fit("SENT", 255)).isEqualTo("SENT");
        assertThat(NotificationLog.fit(null, 255)).isNull();
        String huge = "y".repeat(400) + "@x.com";
        assertThat(NotificationLog.fitList(huge, 255)).hasSize(255).endsWith("…");
        assertThat(NotificationLog.fitList(huge + ", b@x.com", 255)).hasSize(255).endsWith("…");
        // kısaltılmış mı (yeniden gönderim kapısı)
        assertThat(NotificationLog.fitted(NotificationLog.fitList(addresses(30), 255))).isTrue();
        assertThat(NotificationLog.fitted(NotificationLog.fit("x".repeat(300), 255))).isTrue();
        assertThat(NotificationLog.fitted("a@x.com, b@x.com")).isFalse();
        assertThat(NotificationLog.fitted("a … +b")).isFalse();
        assertThat(NotificationLog.fitted(null)).isFalse();
    }
}
