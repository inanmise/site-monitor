package com.sitemonitor.service.oncallroutine;

import com.sitemonitor.service.oncallroutine.OnCallRoutineTemplate.Section;
import com.sitemonitor.service.oncallroutine.OnCallRoutineTemplate.Task;
import com.sitemonitor.service.oncallroutine.OnCallRoutineTemplate.Template;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Nöbet rutini örnek şablonu (2026-11-26) — genel bir operasyon ekibi listesi, TR ve EN. Hiç bağlantı YOK (sunucu adı,
 * örnek adres bile). Her çağrı TAZE kimlik üretir (silinmiş bir görevin kimliği yeniden kullanılmasın).
 */
public final class OnCallRoutineSampleTemplate {

    private OnCallRoutineSampleTemplate() {}

    public static Template build(String lang) {
        boolean en = "en".equalsIgnoreCase(lang);
        Ids ids = new Ids();
        List<Section> sections = new ArrayList<>();
        sections.add(new Section(ids.next("s"), en ? "Start of day" : "Gün başlangıcı", null, "10:30", List.of(
                daily(ids, en ? "Check the infrastructure dashboards" : "Altyapı panolarını kontrol et"),
                daily(ids, en ? "Review error increases and anomalies" : "Hata artışlarını ve anomalileri incele"),
                daily(ids, en ? "Check open incident and problem records" : "Açık olay ve problem kayıtlarını kontrol et",
                        "open_incidents"),
                daily(ids, en ? "Answer monitoring calls and e-mails" : "İzleme çağrı ve e-postalarına dön"),
                daily(ids, en ? "Check postmortem records and actions" : "Postmortem kayıtlarını ve aksiyonları kontrol et",
                        "open_postmortems", "overdue_actions"),
                daily(ids, en ? "Check SiteMonitor alarms and monitors" : "SiteMonitor alarmlarını ve izlemelerini kontrol et",
                        "open_alerts", "down_monitors", "stale_monitors"))));
        sections.add(new Section(ids.next("s"), en ? "During the day" : "Gün içi", null, null, List.of(
                daily(ids, en ? "Check long-running operations" : "Uzun süren işlemleri kontrol et"),
                daily(ids, en ? "Check queue backlogs" : "Kuyruk birikimlerini kontrol et"))));
        sections.add(new Section(ids.next("s"), en ? "Regular e-mails" : "Düzenli gelen e-postalar", null, null, List.of(
                daily(ids, en ? "Review the daily version check e-mail" : "Günlük sürüm kontrol e-postasını incele"),
                daily(ids, en ? "Review the JVM report" : "JVM raporunu incele"),
                daily(ids, en ? "Review the restarted pods report" : "Yeniden başlayan pod raporunu incele"))));
        sections.add(new Section(ids.next("s"), en ? "Weekly" : "Haftalık", null, null, List.of(
                weekly(ids, en ? "Send the weekly report" : "Haftalık raporu gönder", 5),
                weekly(ids, en ? "Check proactive improvement records" : "Proaktif iyileştirme kayıtlarını kontrol et", 5),
                weekly(ids, en ? "Follow the weekly outage meeting" : "Haftalık kesinti toplantısını takip et", 1))));
        sections.add(new Section(ids.next("s"), en ? "Monthly" : "Aylık", null, null, List.of(
                monthly(ids, en ? "Monthly transition check" : "Aylık geçiş kontrolü"),
                monthly(ids, en ? "Review certificate expirations" : "Sertifika bitişlerini gözden geçir", "expiring_certs"))));
        return new Template(sections);
    }

    private static Task daily(Ids ids, String title, String... signals) {
        return new Task(ids.next("t"), title, null, List.of(), List.of(signals), true, true, "DAILY",
                null, null, null, null, null, null);
    }

    private static Task weekly(Ids ids, String title, int weekday) {
        return new Task(ids.next("t"), title, null, List.of(), List.of(), true, true, "WEEKLY",
                weekday, null, null, null, null, null);
    }

    private static Task monthly(Ids ids, String title, String... signals) {
        return new Task(ids.next("t"), title, null, List.of(), List.of(signals), true, true, "MONTHLY",
                null, "FIRST_WORKDAY", null, null, null, null);
    }

    /** {@code <önek>-<zaman36><rastgele4><sıra>} — kimlik kalıbına uyar ({@code [a-z0-9][a-z0-9_-]{0,39}}). */
    private static final class Ids {
        private final String stamp = Long.toString(System.currentTimeMillis(), 36);
        private int n;

        String next(String prefix) {
            String rand = Integer.toString(ThreadLocalRandom.current().nextInt(36 * 36 * 36 * 36), 36);
            return prefix + "-" + stamp + rand + Integer.toString(n++, 36);
        }
    }
}
