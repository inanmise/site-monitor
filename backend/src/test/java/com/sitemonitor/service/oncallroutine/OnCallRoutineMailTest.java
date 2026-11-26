package com.sitemonitor.service.oncallroutine;

import com.sitemonitor.service.PushText;
import com.sitemonitor.service.mail.MailDoc;
import com.sitemonitor.service.mail.OnCallRoutineMail;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Nöbet rutini e-postaları ve push metinleri (2026-11-26): konu bayrakları, TR / EN, tablo sınırları ("+N"), görev
 * açıklaması / bağlantısı e-postaya girmez, push metinleri kanal güvenli ve en düşük sınıra (80) 66 karakterlik takım adıyla
 * sığar.
 */
class OnCallRoutineMailTest {

    static final String TEAM66 = "Dijital Kanallar Mobil Bankacılık Uygulama Geliştirme Ekibi (Ödem)";
    static final LocalDate DAY = LocalDate.parse("2026-11-26");

    static OnCallRoutineMail.Completion completion(int seq, boolean late, boolean partial, int skippedRows) {
        List<OnCallRoutineMail.Skipped> skipped = new ArrayList<>();
        for (int i = 0; i < skippedRows; i++) skipped.add(new OnCallRoutineMail.Skipped("Sabah", "Görev " + i, "neden " + i));
        return new OnCallRoutineMail.Completion("Ödeme Sistemleri", 7L, DAY, "Kişi A", Instant.parse("2026-11-26T07:42:00Z"),
                "Kişi B", 12, 11, skippedRows > 0 ? 1 : 0, 1, 2, seq, late, partial, skipped,
                List.of(new OnCallRoutineMail.Problem("HIGH", "Ağ geçidi", "2026-11-26", "zaman aşımı")), List.of(),
                "Gün notu", 3, 5, "https://sitemonitor.example.com/");
    }

    @Test
    @DisplayName("konu: [Site Monitor] öneki, tarih, güncellendi / geç / eksiklerle bayrakları; EN eşdeğeri")
    void subjects() {
        assertThat(OnCallRoutineMail.completionSubject(completion(1, false, false, 0), false))
                .isEqualTo("[Site Monitor] Nöbet rutini tamamlandı: Ödeme Sistemleri · 26.11.2026");
        assertThat(OnCallRoutineMail.completionSubject(completion(2, true, true, 1), false))
                .endsWith("(güncellendi, geç, eksiklerle)");
        assertThat(OnCallRoutineMail.completionSubject(completion(2, true, true, 1), true))
                .startsWith("[Site Monitor] On-call routine done: ").endsWith("26 Nov 2026 (updated, late, with skipped tasks)");
        assertThat(OnCallRoutineMail.handoverSubject("Ödeme", LocalDate.parse("2026-11-16"), LocalDate.parse("2026-11-22"), false))
                .isEqualTo("[Site Monitor] Nöbet devri: Ödeme · 16.11 – 22.11.2026");
        assertThat(OnCallRoutineMail.completionSubject(completion(1, false, false, 0), false)).doesNotContain("SiteMonitor");
    }

    @Test
    @DisplayName("tamamlama e-postası: TR + EN, atlanan tablo 50 satırda kesilir + '+N', uygulama bağlantısı takım + gün taşır")
    void completionMail() {
        MailDoc.Mail tr = OnCallRoutineMail.completion(completion(1, false, true, 51), false);
        assertThat(tr.html()).contains("Atlanan görevler").contains("+1 daha").contains("Gün notu")
                .contains("tab=oncallroutines&amp;or_team=7&amp;or_day=2026-11-26");
        assertThat(tr.text()).contains("Bu haftanın görevleri: 3/5 tamamlandı");
        MailDoc.Mail en = OnCallRoutineMail.completion(completion(1, false, true, 2), true);
        assertThat(en.html()).contains("Skipped tasks").contains("Problems recorded today")
                .doesNotContain("more — see Site Monitor");
        assertThat(en.text()).contains("This week's tasks: 3/5 done");
    }

    @Test
    @DisplayName("devir e-postası: gün tablosu, haftalık / aylık görevler, devir notu")
    void handoverMail() {
        LocalDate from = LocalDate.parse("2026-11-16");
        var info = new OnCallRoutineMail.Handover("Ödeme", 7L, from, from.plusDays(6), List.of("Kişi A"), "Kişi C", 5, 3, 1, 4,
                2, List.of(new OnCallRoutineMail.HandoverDay(from, "COMPLETED", 12, 12, 0, 0, "Kişi A"),
                new OnCallRoutineMail.HandoverDay(from.plusDays(2), "MISSED", null, null, null, null, null)),
                List.of(new OnCallRoutineMail.PeriodItem("Yedek denemesi", "DONE")), List.of(), "Devir notu", List.of(), null);
        MailDoc.Mail m = OnCallRoutineMail.handover(info, false);
        assertThat(m.html()).contains("Tamamlandı").contains("Yapılmadı").contains("Yedek denemesi").contains("Devir notu")
                .doesNotContain("or_view=week");   // adres yoksa düğme yok
    }

    @Test
    @DisplayName("push metinleri: kanal güvenli, 66 karakterlik takım adıyla en düşük sınıra (80) sığar, gövde kesilmez")
    void pushTexts() {
        int limit = 80;
        List<String> all = List.of(
                OnCallRoutineMail.pushDone(TEAM66, 11, 12, 1, 3, false, limit),
                OnCallRoutineMail.pushDone(TEAM66, 11, 12, 1, 3, true, limit),
                OnCallRoutineMail.pushReminder(TEAM66, 4, true, false, limit),
                OnCallRoutineMail.pushReminder(TEAM66, 4, false, false, limit),
                OnCallRoutineMail.pushReminder(TEAM66, 4, false, true, limit),
                OnCallRoutineMail.pushSection(TEAM66, "10:30", 2, false, limit),
                OnCallRoutineMail.pushSection(TEAM66, "10:30", 2, true, limit),
                OnCallRoutineMail.pushDue(TEAM66, 1, false, limit),
                OnCallRoutineMail.pushDue(TEAM66, 1, true, limit),
                OnCallRoutineMail.pushHandover(TEAM66, 4, 5, 2, false, limit),
                OnCallRoutineMail.pushHandover(TEAM66, 4, 5, 2, true, limit));
        for (String s : all) {
            assertThat(s.length()).as(s).isLessThanOrEqualTo(limit);
            assertThat(PushText.isChannelSafe(s)).as(s).isTrue();
            assertThat(s).doesNotContain("http");
        }
        assertThat(all.get(0)).contains("11/12").contains("1 atlandı").contains("3 açık sorun");
        assertThat(all.get(3)).startsWith("Hatırlatma: ");
        assertThat(all.get(4)).startsWith("Reminder: ");
        // geniş sınırda takım adı tam
        assertThat(OnCallRoutineMail.pushDone("Ödeme", 5, 5, 0, 0, false, 320)).isEqualTo("Ödeme - nöbet rutini tamamlandı: 5/5");
        // takım adı yok / sığmıyor → yalnız gövde, başta "- " kalmaz
        assertThat(OnCallRoutineMail.pushDone(null, 5, 5, 0, 0, false, 320)).isEqualTo("nöbet rutini tamamlandı: 5/5");
        assertThat(OnCallRoutineMail.pushDone(TEAM66, 5, 5, 0, 0, false, 30)).isEqualTo("nöbet rutini tamamlandı: 5/5");
        // kısaltılan takım adı kanal güvenli "..." ile biter
        String cut = OnCallRoutineMail.pushHandover(TEAM66, 4, 5, 2, false, limit);
        assertThat(cut).contains("...").contains("nöbet devri: 4/5 gün tamamlandı, 2 açık sorun");
    }
}
