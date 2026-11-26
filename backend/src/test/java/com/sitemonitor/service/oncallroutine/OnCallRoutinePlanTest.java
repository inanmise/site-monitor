package com.sitemonitor.service.oncallroutine;

import com.sitemonitor.service.oncallroutine.OnCallRoutinePlan.Item;
import com.sitemonitor.service.oncallroutine.OnCallRoutinePlan.Progress;
import com.sitemonitor.service.oncallroutine.OnCallRoutinePlan.Row;
import com.sitemonitor.service.oncallroutine.OnCallRoutinePlan.Sheet;
import com.sitemonitor.service.oncallroutine.OnCallRoutineSettings.Settings;
import com.sitemonitor.service.oncallroutine.OnCallRoutineTemplate.Template;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Gün sayfası planı (2026-11-26): zorunlu küme R(D), sanal ∪ maddeleşmiş birleşim, kaldırılan / düzenlenen görev kuralları,
 * gecikmiş / henüz zamanı değil (sabit saatle), donmuş görünüm.
 */
class OnCallRoutinePlanTest {

    static final Settings S = OnCallRoutineSettings.defaults();
    static final LocalDate WED = LocalDate.parse("2026-11-25");
    static final LocalDate FRI = LocalDate.parse("2026-11-27");

    static Template template() {
        return OnCallRoutineTemplate.parseStrict(Map.of("sections", List.of(
                Map.of("id", "s1", "title", "Sabah", "window_to", "10:30", "tasks", List.of(
                        Map.of("id", "d1", "title", "Kuyruklar"),
                        Map.of("id", "d2", "title", "İsteğe bağlı", "required", false),
                        Map.of("id", "off", "title", "Pasif", "active", false))),
                Map.of("id", "s2", "title", "Haftalık", "tasks", List.of(
                        Map.of("id", "w1", "title", "Yedek denemesi", "frequency", "WEEKLY", "weekday", 5),
                        Map.of("id", "m1", "title", "Aylık rapor", "frequency", "MONTHLY", "monthly_rule", "LAST_WORKDAY"))))));
    }

    static Row row(long id, String task, String kind, String key, String due, String title, String state) {
        return new Row(id, task, "s1", kind, key, due, title, "Sabah", null, null, null, true, "DAILY", null, null, state,
                null, null, null, null, null, 0, null, null, 1, 0);
    }

    @Test
    @DisplayName("açık sayfa: günlük + haftalık + aylık görülür; pasif görev yok; haftalık son gün gelmeden zorunlu değil")
    void openSheet() {
        Sheet s = OnCallRoutinePlan.sheet(template(), S, Set.of(), WED, List.of(), false);
        assertThat(s.items()).extracting(Item::taskId).contains("d1", "d2", "w1").doesNotContain("off");
        Item w1 = s.find("WEEK", "2026-11-23", "w1");
        assertThat(w1.dueDay()).isEqualTo(FRI);
        assertThat(w1.required()).isFalse();              // Çarşamba < Cuma
        assertThat(w1.requiredFlag()).isTrue();
        assertThat(s.find("DAY", "2026-11-25", "d2").required()).isFalse();
        // sıra: önce DAY, sonra WEEK
        assertThat(s.items().get(0).periodKind()).isEqualTo("DAY");
        // Cuma: haftalık zorunlu
        Sheet fri = OnCallRoutinePlan.sheet(template(), S, Set.of(), FRI, List.of(), false);
        assertThat(fri.find("WEEK", "2026-11-23", "w1").required()).isTrue();
        assertThat(fri.required()).extracting(Item::taskId).contains("d1", "w1");
        // ayın son iş günü (30 Kasım Pazartesi) haftası: aylık görev 30 Kasım haftasından görülür; Çarşamba 25'te görülmez
        assertThat(s.find("MONTH", "2026-11", "m1")).isNull();
        Sheet mon30 = OnCallRoutinePlan.sheet(template(), S, Set.of(), LocalDate.parse("2026-11-30"), List.of(), false);
        assertThat(mon30.find("MONTH", "2026-11", "m1").required()).isTrue();
    }

    @Test
    @DisplayName("iş günü değil: açık sayfa boş; zorunlu küme yok")
    void nonWorkday() {
        Sheet sat = OnCallRoutinePlan.sheet(template(), S, Set.of(), LocalDate.parse("2026-11-28"), List.of(), false);
        assertThat(sat.items()).isEmpty();
        Sheet holiday = OnCallRoutinePlan.sheet(template(), S, Set.of(WED), WED, List.of(), false);
        assertThat(holiday.items()).isEmpty();
    }

    @Test
    @DisplayName("maddeleşmiş satır sanalın yerini alır; düzenlenen görev anlık görüntüsünü korur (snapshotDiffers)")
    void materialisedRow() {
        Row r = row(10, "d1", "DAY", "2026-11-25", "2026-11-25", "Eski başlık", "DONE");
        Sheet s = OnCallRoutinePlan.sheet(template(), S, Set.of(), WED, List.of(r), false);
        Item i = s.find("DAY", "2026-11-25", "d1");
        assertThat(i.row()).isNotNull();
        assertThat(i.title()).isEqualTo("Eski başlık");
        assertThat(i.snapshotDiffers()).isTrue();
        assertThat(i.resolved()).isTrue();
        assertThat(s.items().stream().filter(x -> x.taskId().equals("d1")).count()).isEqualTo(1);
    }

    @Test
    @DisplayName("kaldırılan görev: sanal örnek kaybolur, maddeleşmiş satır 'kaldırıldı' ve zorunlu değil")
    void removedTask() {
        Row r = row(11, "gone", "DAY", "2026-11-25", "2026-11-25", "Silinen", "PENDING");
        Sheet s = OnCallRoutinePlan.sheet(template(), S, Set.of(), WED, List.of(r), false);
        Item i = s.find("DAY", "2026-11-25", "gone");
        assertThat(i.removed()).isTrue();
        assertThat(i.required()).isFalse();
        Progress p = OnCallRoutinePlan.progress(s, WED, LocalTime.of(9, 0));
        assertThat(p.required()).isEqualTo(1);   // yalnız d1 (w1 Cuma'ya kadar zorunlu değil)
    }

    @Test
    @DisplayName("donmuş görünüm: yalnız satırlar, şablondan yeni görev eklenmez, satır 'kaldırıldı' sayılmaz")
    void frozen() {
        Row r = row(12, "gone", "DAY", "2026-11-25", "2026-11-25", "Silinen", "MISSED");
        Sheet s = OnCallRoutinePlan.sheet(template(), S, Set.of(), WED, List.of(r), true);
        assertThat(s.items()).hasSize(1);
        assertThat(s.items().get(0).removed()).isFalse();
        assertThat(s.items().get(0).required()).isTrue();
    }

    @Test
    @DisplayName("gecikmiş / henüz zamanı değil: sabit saatle pencere sonu ve son gün")
    void overdueAndNotBefore() {
        Sheet s = OnCallRoutinePlan.sheet(template(), S, Set.of(), WED, List.of(), false);
        Item d1 = s.find("DAY", "2026-11-25", "d1");
        assertThat(d1.windowTo()).isEqualTo("10:30");          // bölüm penceresi
        assertThat(d1.overdue(WED, LocalTime.of(10, 0))).isFalse();
        assertThat(d1.overdue(WED, LocalTime.of(10, 31))).isTrue();
        assertThat(d1.overdue(WED.plusDays(1), LocalTime.of(8, 0))).isTrue();   // dün kalmış
        Item w1 = s.find("WEEK", "2026-11-23", "w1");
        assertThat(w1.notBefore(WED, WED, LocalTime.of(9, 0))).isTrue();          // Cuma'ya kadar
        Progress p = OnCallRoutinePlan.progress(s, WED, LocalTime.of(11, 0));
        assertThat(p.overdue()).isEqualTo(1);
        assertThat(p.optionalPending()).isGreaterThanOrEqualTo(2);              // d2 + w1
    }

    @Test
    @DisplayName("satır görünürlüğü: DAY o gün, WEEK o hafta, MONTH son gün haftasının Pazartesi'sinden ay sonuna")
    void rowVisibility() {
        assertThat(OnCallRoutinePlan.rowVisibleOn(row(1, "x", "DAY", "2026-11-25", "2026-11-25", "x", "PENDING"), WED)).isTrue();
        assertThat(OnCallRoutinePlan.rowVisibleOn(row(1, "x", "DAY", "2026-11-24", "2026-11-24", "x", "PENDING"), WED)).isFalse();
        assertThat(OnCallRoutinePlan.rowVisibleOn(row(1, "x", "WEEK", "2026-11-23", "2026-11-27", "x", "PENDING"), WED)).isTrue();
        Row m = row(1, "x", "MONTH", "2026-11", "2026-11-30", "x", "PENDING");
        assertThat(OnCallRoutinePlan.rowVisibleOn(m, LocalDate.parse("2026-11-30"))).isTrue();
        assertThat(OnCallRoutinePlan.rowVisibleOn(m, WED)).isFalse();
        assertThat(OnCallRoutinePlan.rowVisibleOn(null, WED)).isFalse();
        assertThat(OnCallRoutinePlan.periodsSeenFrom(WED)).hasSize(4);
    }

    @Test
    @DisplayName("dönem görevleri (kapanış): türün etkin görevleri ve son günleri")
    void periodItems() {
        List<Item> week = OnCallRoutinePlan.periodItems(template(), S, Set.of(), "WEEK", "2026-11-23");
        assertThat(week).extracting(Item::taskId).containsExactly("w1");
        List<Item> day = OnCallRoutinePlan.periodItems(template(), S, Set.of(), "DAY", "2026-11-28");
        assertThat(day).isEmpty();
        assertThat(OnCallRoutinePlan.splitCsv("open_alerts,nope,open_alerts")).containsExactly("open_alerts");
        assertThat(OnCallRoutinePlan.csv(List.of())).isNull();
    }
}
