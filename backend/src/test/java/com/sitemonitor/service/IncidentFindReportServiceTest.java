package com.sitemonitor.service;

import com.sitemonitor.config.GlobalExceptionHandler.FieldValidationException;
import com.sitemonitor.model.IncidentOption;
import com.sitemonitor.model.IncidentPostmortem;
import com.sitemonitor.model.IncidentRecord;
import com.sitemonitor.repository.IncidentOptionRepository;
import com.sitemonitor.repository.IncidentPostmortemRepository;
import com.sitemonitor.repository.IncidentRecordQueries;
import com.sitemonitor.repository.IncidentRecordRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.TestPropertySource;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Olay ve Hata Geçmişi — "Bul ve Raporla" (2026-11-26, Paket 3) servis + sorgu sözleşmesi, gerçek H2 üzerinde: her arama alanı,
 * kimlik araması, yeni süzgeçler (CSV alanında TAM öğe, tam hata kodu / kaydeden, postmortem durumu), sıralamalar, eğilimin
 * süzgeçsiz yolunun bugünkü sayılarla AYNI olması, MTTD / ortanca MTTR / takım kırılımı / müşteri toplamı ve seçenek
 * yeniden adlandırma / birleştirme. Her test kendi işleminde koşar ve geri alınır (kalıcı satır yok).
 */
@DataJpaTest
@Import(IncidentService.class)
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
@org.springframework.test.context.event.RecordApplicationEvents
class IncidentFindReportServiceTest {

    @Autowired IncidentService service;
    @Autowired IncidentRecordRepository repo;
    @Autowired IncidentPostmortemRepository postmortems;
    @Autowired IncidentOptionRepository options;

    private IncidentRecord save(String title, Consumer<IncidentRecord> c) {
        IncidentRecord e = new IncidentRecord();
        e.setTitle(title);
        e.setOccurredAt("2026-11-10T10:00:00");
        e.setSeverity("HIGH");
        e.setStatus("OPEN");
        e.setCategory("APPLICATION");
        e.setSlaBreached(false);
        e.setTeamId(1L);
        e.setTeamName("Takım A");
        e.setCreatedBy("sre1");
        e.setCreatedAt("2026-11-10T10:05:00");
        e.setUpdatedAt("2026-11-10T10:05:00");
        if (c != null) c.accept(e);
        return repo.saveAndFlush(e);
    }

    private static IncidentService.ListFilter q(String q) {
        return IncidentService.ListFilter.legacy(q, null, null, null, null, null, null, null, null, null, null);
    }

    private static IncidentService.ListFilter ext(String serviceExact, String channelExact, String problemType, String errorCode,
                                                  String tag, String createdBy, String postmortem) {
        return new IncidentService.ListFilter(null, null, null, null, null, null, null, null, null, null, null,
                serviceExact, channelExact, problemType, errorCode, tag, createdBy, postmortem);
    }

    private List<Long> ids(IncidentService.ListFilter f, List<Long> scope, String sort) {
        return service.list(f, scope, PageRequest.of(0, 100), sort).getContent().stream().map(IncidentRecord::getId).toList();
    }

    // ── Arama ─────────────────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("arama her alana bakar (başlık, alan, kök neden, açıklama, çözüm, iş etkisi, etiket, üç kod, etkilenen uygulama / sistem / servis, sorun türü); eşleşen alan ipucu doğru")
    void searchEveryField() {
        record Case(String key, Consumer<IncidentRecord> set) { }
        List<Case> cases = List.of(
                new Case("title", e -> e.setTitle("baslik zkqtitle")),
                new Case("service", e -> e.setService("zkqservice.example.com")),
                new Case("rca_summary", e -> e.setRcaSummary("Kök neden: zkqrca havuzu")),
                new Case("description", e -> e.setDescription("Açıklama zkqdesc")),
                new Case("resolution_steps", e -> e.setResolutionSteps("1. zkqres yeniden başlat")),
                new Case("business_impact", e -> e.setBusinessImpact("zkqimpact müşteri")),
                new Case("tags", e -> e.setTags("prod,zkqtag")),
                new Case("error_code", e -> e.setErrorCode("ZKQERR-1")),
                new Case("function_code", e -> e.setFunctionCode("ZKQFN-2")),
                new Case("channel_code", e -> e.setChannelCode("ZKQCH-3")),
                new Case("affected_app", e -> e.setAffectedApp("zkqapp")),
                new Case("affected_systems", e -> e.setAffectedSystems("zkqsys")),
                new Case("affected_services", e -> e.setAffectedServices("zkqsvcs")),
                new Case("problem_types", e -> e.setProblemTypes("zkqptype")));
        List<Long> created = new ArrayList<>();
        for (Case c : cases) created.add(save("kayıt " + c.key(), c.set()).getId());
        String[] needles = {"zkqtitle", "zkqservice", "zkqrca", "zkqdesc", "zkqres", "zkqimpact", "zkqtag", "zkqerr", "zkqfn",
                "zkqch", "zkqapp", "zkqsys", "zkqsvcs", "zkqptype"};
        for (int k = 0; k < cases.size(); k++) {
            Long id = created.get(k);
            assertThat(ids(q(needles[k].toUpperCase()), null, null)).as(cases.get(k).key()).containsExactly(id);
            IncidentRecord e = repo.findById(id).orElseThrow();
            assertThat(IncidentService.matchField(e, needles[k])).isEqualTo(cases.get(k).key());
        }
        // Kanal BİLEREK aranmaz (kanal süzgeci ayrıdır)
        save("kanal", e -> e.setChannel("zkqchannelonly"));
        assertThat(ids(q("zkqchannelonly"), null, null)).isEmpty();
    }

    @Test
    @DisplayName("kimlik araması: #id ve id kaydı bulur; metinde rakam geçen kayıtlar da eşleşir; eşleşen alan 'id'")
    void searchById() {
        IncidentRecord a = save("İlk kayıt", null);
        IncidentRecord b = save("başka", null);
        assertThat(ids(q("#" + a.getId()), null, null)).contains(a.getId()).doesNotContain(b.getId());
        assertThat(IncidentService.matchField(a, "#" + a.getId())).isEqualTo("id");
        assertThat(IncidentService.matchField(b, "#" + a.getId())).isNull();
        IncidentRecord digits = save("Hata " + b.getId() + " kodu", null);
        assertThat(ids(q(String.valueOf(b.getId())), null, null)).contains(b.getId(), digits.getId());
        assertThat(IncidentService.idQuery("#12")).isEqualTo(12L);
        assertThat(IncidentService.idQuery("12a")).isNull();
        assertThat(IncidentService.idQuery("1234567890123456789")).isNull();
    }

    @Test
    @DisplayName("arama joker karakterleri kaçırılır: % yalnız % geçen kaydı bulur, _ tek karakter değildir")
    void searchEscapesWildcards() {
        IncidentRecord pct = save("yüzde %50 düşüş", null);
        save("yüzde 50 düşüş", null);
        IncidentRecord und = save("ab_cd", null);
        save("abxcd", null);
        assertThat(ids(q("%"), null, null)).containsExactly(pct.getId());
        assertThat(ids(q("b_c"), null, null)).containsExactly(und.getId());
    }

    @Test
    @DisplayName("200 karakteri aşan arama ya da süzgeç → alan adlı 400; geçersiz postmortem durumu → alan adlı 400")
    void longFilterText400() {
        String longText = "x".repeat(IncidentService.MAX_FILTER_TEXT + 1);
        assertThatThrownBy(() -> service.list(q(longText), null, PageRequest.of(0, 10), null))
                .isInstanceOfSatisfying(FieldValidationException.class, ex -> assertThat(ex.getFields()).containsOnlyKeys("q"));
        assertThatThrownBy(() -> service.list(ext(null, null, null, null, longText, null, null), null, PageRequest.of(0, 10), null))
                .isInstanceOfSatisfying(FieldValidationException.class, ex -> assertThat(ex.getFields()).containsOnlyKeys("tag"));
        assertThatThrownBy(() -> service.list(ext(null, null, null, null, null, null, "yayında"), null, PageRequest.of(0, 10), null))
                .isInstanceOfSatisfying(FieldValidationException.class, ex -> assertThat(ex.getFields()).containsOnlyKeys("postmortem"));
    }

    // ── Yeni süzgeçler ────────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("alan / kanal / sorun türü / etiket süzgeci CSV alanında TAM öğe (büyük-küçük harf duyarsız, boşluklu virgül); alt dize eşleşmez")
    void tokenFilters() {
        IncidentRecord a = save("a", e -> { e.setService("mobil.example.com, web.example.com"); e.setChannel("Mobil Şube,Public Web");
            e.setProblemTypes("Yavaşlık , Kesinti"); e.setTags("prod,odeme"); });
        IncidentRecord b = save("b", e -> { e.setService("mobil.example.com.tr"); e.setChannel("Mobil Şube Plus");
            e.setProblemTypes("Kesinti Değil"); e.setTags("prod-x"); });
        assertThat(ids(ext("MOBIL.example.com", null, null, null, null, null, null), null, null)).containsExactly(a.getId());
        assertThat(ids(ext("web.example.com", null, null, null, null, null, null), null, null)).containsExactly(a.getId());
        assertThat(ids(ext(null, "mobil şube", null, null, null, null, null), null, null)).containsExactly(a.getId());
        assertThat(ids(ext(null, null, "kesinti", null, null, null, null), null, null)).containsExactly(a.getId());
        assertThat(ids(ext(null, null, null, null, "prod", null, null), null, null)).containsExactly(a.getId());
        assertThat(ids(ext(null, null, null, null, "prod-x", null, null), null, null)).containsExactly(b.getId());
        // eski "service" süzgeci alt dize kuralını korur (ikisi de)
        IncidentService.ListFilter legacy = IncidentService.ListFilter.legacy(null, null, null, null, "mobil.example.com", null,
                null, null, null, null, null);
        assertThat(ids(legacy, null, null)).containsExactlyInAnyOrder(a.getId(), b.getId());
    }

    @Test
    @DisplayName("hata kodu ve kaydeden TAM (büyük-küçük harf duyarsız); postmortem none / draft / published")
    void exactAndPostmortemFilters() {
        IncidentRecord a = save("a", e -> { e.setErrorCode("E-100"); e.setCreatedBy("Ayse"); });
        IncidentRecord b = save("b", e -> { e.setErrorCode("E-1000"); e.setCreatedBy("ayse2"); });
        IncidentRecord c = save("c", null);
        assertThat(ids(ext(null, null, null, "e-100", null, null, null), null, null)).containsExactly(a.getId());
        assertThat(ids(ext(null, null, null, null, null, "AYSE", null), null, null)).containsExactly(a.getId());
        pm(a.getId(), "DRAFT");
        pm(b.getId(), "PUBLISHED");
        assertThat(ids(ext(null, null, null, null, null, null, "draft"), null, null)).containsExactly(a.getId());
        assertThat(ids(ext(null, null, null, null, null, null, "published"), null, null)).containsExactly(b.getId());
        assertThat(ids(ext(null, null, null, null, null, null, "none"), null, null)).containsExactly(c.getId());
        assertThat(ids(ext(null, null, null, null, null, null, "in-review"), null, null)).isEmpty();
    }

    private void pm(Long incidentId, String status) {
        IncidentPostmortem p = new IncidentPostmortem();
        p.setIncidentId(incidentId);
        p.setStatus(status);
        p.setCreatedBy("sre1");
        p.setCreatedAt("2026-11-10T11:00:00");
        p.setUpdatedBy("sre1");
        p.setUpdatedAt("2026-11-10T11:00:00");
        postmortems.saveAndFlush(p);
    }

    @Test
    @DisplayName("kapsam: takım YA DA giren takım; boş kapsam hiçbir şey (sorgu yok); null kapsam hepsi")
    void scope() {
        IncidentRecord own = save("kendi", e -> e.setTeamId(5L));
        IncidentRecord entered = save("girdiğim", e -> { e.setTeamId(9L); e.setCreatedByTeamId(5L); });
        IncidentRecord foreign = save("yabancı", e -> e.setTeamId(9L));
        assertThat(ids(q(null), List.of(5L), null)).containsExactlyInAnyOrder(own.getId(), entered.getId());
        assertThat(ids(q(null), List.of(), null)).isEmpty();
        assertThat(ids(q(null), null, null)).contains(own.getId(), entered.getId(), foreign.getId());
        assertThat(service.trends(IncidentService.ListFilter.dates(null, null), List.of()).get("summary"))
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP).containsEntry("total", 0L);
    }

    // ── Sıralama ──────────────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("sıralamalar: oluş (yeni / eski), önem, durum, süre (süresiz sonda), güncelleme; bilinmeyen anahtar = varsayılan")
    void sorts() {
        IncidentRecord low = save("düşük", e -> { e.setSeverity("LOW"); e.setStatus("RESOLVED"); e.setOccurredAt("2026-11-01T10:00:00");
            e.setDurationMinutes(5); e.setUpdatedAt("2026-11-12T10:00:00"); });
        IncidentRecord crit = save("kritik", e -> { e.setSeverity("CRITICAL"); e.setStatus("MITIGATED"); e.setOccurredAt("2026-11-03T10:00:00");
            e.setDurationMinutes(null); e.setUpdatedAt(null); });
        IncidentRecord high = save("yüksek", e -> { e.setSeverity("HIGH"); e.setStatus("OPEN"); e.setOccurredAt("2026-11-02T10:00:00");
            e.setDurationMinutes(90); e.setUpdatedAt("2026-11-14T10:00:00"); });
        assertThat(ids(q(null), null, "occurred_desc")).containsExactly(crit.getId(), high.getId(), low.getId());
        assertThat(ids(q(null), null, "occurred_asc")).containsExactly(low.getId(), high.getId(), crit.getId());
        assertThat(ids(q(null), null, "severity")).containsExactly(crit.getId(), high.getId(), low.getId());
        assertThat(ids(q(null), null, "status")).containsExactly(high.getId(), crit.getId(), low.getId());
        assertThat(ids(q(null), null, "duration")).containsExactly(high.getId(), low.getId(), crit.getId());
        assertThat(ids(q(null), null, "updated")).containsExactly(high.getId(), low.getId(), crit.getId());
        assertThat(ids(q(null), null, "DROP TABLE")).containsExactly(crit.getId(), high.getId(), low.getId());
        assertThat(IncidentService.sortKey(" Severity ")).isEqualTo("severity");
        assertThat(IncidentService.sortKey(null)).isEqualTo(IncidentService.DEFAULT_SORT);
    }

    @Test
    @DisplayName("dışa aktarım kimlikleri: listeyle aynı süzgeç + sıralama, tavan + toplam (tavan aşılınca sayım), kapsam boş = hiç; kimlik sırasıyla yükleme silineni atlar")
    void exportIdsAndLoadOrdered() {
        IncidentRecord low = save("dışa düşük", e -> { e.setSeverity("LOW"); e.setOccurredAt("2026-11-12T10:00:00"); });
        IncidentRecord crit = save("dışa kritik", e -> { e.setSeverity("CRITICAL"); e.setOccurredAt("2026-11-10T10:00:00"); });
        IncidentRecord high = save("dışa yüksek", e -> { e.setSeverity("HIGH"); e.setOccurredAt("2026-11-11T10:00:00"); });
        IncidentRecord med = save("dışa orta", e -> { e.setSeverity("MEDIUM"); e.setOccurredAt("2026-11-09T10:00:00"); });
        IncidentService.ListFilter dis = q("dışa");
        IncidentService.ExportIds all = service.exportIds(dis, null, "severity", 10);
        assertThat(all.ids()).containsExactly(crit.getId(), high.getId(), med.getId(), low.getId());
        assertThat(all.total()).isEqualTo(4L);
        // tavanın İKİ üstü: sorgu yalnız max + 1 satır okur — toplam o satır sayısı değil, ayrı sayımdır
        IncidentService.ExportIds capped = service.exportIds(dis, null, "occurred_desc", 2);
        assertThat(capped.ids()).containsExactly(low.getId(), high.getId());
        assertThat(capped.total()).isEqualTo(4L);
        assertThat(service.exportIds(dis, List.of(), null, 10)).satisfies(x -> {
            assertThat(x.ids()).isEmpty();
            assertThat(x.total()).isZero();
        });
        assertThat(service.exportIds(dis, List.of(99L), null, 10).ids()).isEmpty();
        repo.deleteById(high.getId());
        assertThat(service.loadOrdered(all.ids())).extracting(IncidentRecord::getId).containsExactly(crit.getId(), med.getId(), low.getId());
        assertThat(service.loadOrdered(List.of())).isEmpty();
    }

    // ── Eğilim ────────────────────────────────────────────────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private static Map<String, Object> summary(Map<String, Object> t) {
        return (Map<String, Object>) t.get("summary");
    }

    private void seedTrend() {
        save("t1", e -> { e.setSeverity("CRITICAL"); e.setStatus("RESOLVED"); e.setCategory("DATABASE"); e.setChannel("Mobil Şube, ATM");
            e.setDurationMinutes(30); e.setDetectedAt("2026-11-10T10:10:00"); e.setAffectedCustomers(100); e.setSlaBreached(true); });
        save("t2", e -> { e.setSeverity("HIGH"); e.setStatus("RESOLVED"); e.setCategory("NETWORK"); e.setChannel("ATM");
            e.setDurationMinutes(60); e.setDetectedAt("2026-11-10T10:30:00"); e.setAffectedCustomers(50); e.setTeamId(2L);
            e.setTeamName("Takım B"); });
        save("t3", e -> { e.setSeverity("LOW"); e.setStatus("OPEN"); e.setCategory("NETWORK");
            e.setDetectedAt("2026-11-10T10:50:00"); e.setTeamId(null); e.setTeamName(null); });
        save("t4", e -> { e.setSeverity("MEDIUM"); e.setStatus("RESOLVED"); e.setDurationMinutes(240); e.setTeamId(2L);
            e.setTeamName("Takım B"); e.setDetectedAt("2026-11-10T09:00:00"); });   // tespit oluştan önce → MTTD dışı
    }

    @Test
    @DisplayName("ek süzgeç yokken sayılar bugünkü yolla; nüfusu daraltmayan süzgeçle (süzgeçli yol) AYNI sayılar; daraltan süzgeç değiştirir")
    @SuppressWarnings("unchecked")
    void trendsIdenticalWithoutFilter() {
        seedTrend();
        Map<String, Object> legacy = service.trends("2026-11-01T00:00:00", "2026-11-30T23:59:59", null);
        Map<String, Object> viaFilter = service.trends(new IncidentService.ListFilter(null, null, null, null, null, null,
                "2026-11-01T00:00:00", "2026-11-30T23:59:59", null, null, null, null, null, null, null, null, "sre1", null), null);
        assertThat(legacy.get("filtered")).isEqualTo(false);
        assertThat(viaFilter.get("filtered")).isEqualTo(true);
        for (String k : List.of("daily", "by_severity", "by_status", "by_category", "by_channel", "by_team")) {
            assertThat(viaFilter.get(k)).as(k).isEqualTo(legacy.get(k));
        }
        Map<String, Object> a = summary(legacy), b = summary(viaFilter);
        for (String k : List.of("total", "critical", "sla_breached", "open", "resolved", "resolved_within_sla", "mttr_minutes",
                "mttr_sample", "mttr_median_minutes", "mttd_minutes", "mttd_median_minutes", "mttd_sample", "customers_sum",
                "customers_sample")) {
            assertThat(b.get(k)).as(k).isEqualTo(a.get(k));
        }
        assertThat(a.get("total")).isEqualTo(4L);
        assertThat((Map<String, Long>) legacy.get("by_channel")).containsEntry("ATM", 2L).containsEntry("Mobil Şube", 1L);

        Map<String, Object> narrowed = service.trends(new IncidentService.ListFilter(null, null, null, null, null, null,
                "2026-11-01T00:00:00", "2026-11-30T23:59:59", 2L, null, null, null, null, null, null, null, null, null), null);
        assertThat(summary(narrowed)).containsEntry("total", 2L).containsEntry("critical", 0L);
    }

    @Test
    @DisplayName("süzgeçli günlük seri veritabanında UTC saatine göre gruplanır ama İstanbul gününe düşer — UTC gece yarısı çevresi eski yolla aynı")
    @SuppressWarnings("unchecked")
    void dailyHourGroupsFollowIstanbulDay() {
        save("a", e -> { e.setOccurredAt("2026-11-09T20:59:59"); e.setSeverity("CRITICAL"); });   // İstanbul 9 Kasım 23:59
        save("b", e -> e.setOccurredAt("2026-11-09T21:00:00"));                                   // İstanbul 10 Kasım 00:00
        save("c", e -> { e.setOccurredAt("2026-11-09T21:45:00"); e.setSeverity("LOW"); });        // İstanbul 10 Kasım 00:45
        save("d", e -> e.setOccurredAt("2026-11-10T20:30:00"));                                   // İstanbul 10 Kasım 23:30
        save("e", e -> e.setOccurredAt("2026-11-10T21:10:00"));                                   // İstanbul 11 Kasım 00:10
        Map<String, Object> legacy = service.trends("2026-11-01T00:00:00", "2026-11-30T23:59:59", null);
        Map<String, Object> viaFilter = service.trends(new IncidentService.ListFilter(null, null, null, null, null, null,
                "2026-11-01T00:00:00", "2026-11-30T23:59:59", null, null, null, null, null, null, null, null, "sre1", null), null);
        assertThat(viaFilter.get("daily")).isEqualTo(legacy.get("daily"));
        List<Map<String, Object>> daily = (List<Map<String, Object>>) viaFilter.get("daily");
        assertThat(daily).extracting(m -> m.get("day")).containsExactly("2026-11-09", "2026-11-10", "2026-11-11");
        assertThat(daily).extracting(m -> m.get("count")).containsExactly(1L, 3L, 1L);
        assertThat(daily.get(0)).containsEntry("critical", 1L);
        assertThat(daily.get(1)).containsEntry("high", 2L).containsEntry("low", 1L);
    }

    @Test
    @DisplayName("saat kovasından gün = satır satır gün (aynı veri, iki yol): ayrıştırılamayan değer ham UTC gününe, kısa değer sayılmaz")
    void dailyOfHoursEqualsRowWise() {
        List<Object[]> hours = List.of(
                new Object[]{"2026-11-09T20", "HIGH", 2L},
                new Object[]{"2026-11-09T21", "CRITICAL", 3L},
                new Object[]{"2026-11-09", "LOW", 1L},          // saat yok (yalnız tarih) → ham gün
                new Object[]{"2026-11", "LOW", 9L},             // < 10 karakter → sayılmaz
                new Object[]{null, "LOW", 9L},
                new Object[]{"2026-11-10T03", null, 4L});       // önemsiz → yalnız toplam
        List<Map<String, Object>> d = IncidentService.dailyOfHours(hours);
        assertThat(d).extracting(m -> m.get("day")).containsExactly("2026-11-09", "2026-11-10");
        assertThat(d.get(0)).containsEntry("count", 3L).containsEntry("high", 2L).containsEntry("low", 1L);
        assertThat(d.get(1)).containsEntry("count", 7L).containsEntry("critical", 3L);
    }

    @Test
    @DisplayName("MTTD (tespit − oluş; oluştan önceki tespit dışarıda), ortanca MTTR, müşteri toplamı, takım kırılımı (takımsız sonda), capped yok")
    @SuppressWarnings("unchecked")
    void trendExtras() {
        seedTrend();
        Map<String, Object> t = service.trends("2026-11-01T00:00:00", null, null);
        Map<String, Object> s = summary(t);
        // MTTD: 10, 30, 50 → ortalama 30, ortanca 30, örneklem 3
        assertThat(s).containsEntry("mttd_minutes", 30L).containsEntry("mttd_median_minutes", 30L).containsEntry("mttd_sample", 3L);
        // MTTR: çözülmüş-süreli 30, 60, 240 → ortanca 60, ortalama round(110) = 110
        assertThat(s).containsEntry("mttr_median_minutes", 60L).containsEntry("mttr_minutes", 110L).containsEntry("mttr_sample", 3L);
        assertThat(s).containsEntry("customers_sum", 150L).containsEntry("customers_sample", 2L);
        List<Map<String, Object>> byTeam = (List<Map<String, Object>>) t.get("by_team");
        assertThat(byTeam).extracting(m -> m.get("team_id")).containsExactly(2L, 1L, null);
        assertThat(byTeam.get(0)).containsEntry("team_name", "Takım B").containsEntry("count", 2L);
        assertThat(t.get("capped")).isEqualTo(false);
    }

    @Test
    @DisplayName("müşteri sayısı girilmiş kayıt yoksa toplam null (uydurma 0 yok); MTTD örneklemi 0 → null")
    void trendUnknownIsNull() {
        save("tek", null);
        Map<String, Object> s = summary(service.trends(null, null, null));
        assertThat(s.get("customers_sum")).isNull();
        assertThat(s).containsEntry("customers_sample", 0L);
        assertThat(s.get("mttd_minutes")).isNull();
        assertThat(s.get("mttd_median_minutes")).isNull();
        assertThat(s.get("mttr_median_minutes")).isNull();
    }

    @Test
    @DisplayName("Bugün / Son 7 / Son 30 gün pencereleri İstanbul takvim gününe hizalı (bugün dahil 1 / 7 / 30 gün)")
    void windowStarts() {
        String[] w = IncidentService.windowStarts(LocalDate.of(2026, 9, 28));
        assertThat(w).containsExactly("2026-09-27T21:00:00", "2026-09-21T21:00:00", "2026-08-29T21:00:00");
    }

    @Test
    @DisplayName("dakika farkı: saniyesiz eski kayıt okunur, ters sıra ve bozuk değer null; ortanca çift sayıda ortalama")
    void timingHelpers() {
        assertThat(IncidentService.minutesBetweenStamps("2026-11-10T10:00:00", "2026-11-10T10:30:30")).isEqualTo(30.5);
        assertThat(IncidentService.minutesBetweenStamps("2026-11-10T10:00", "2026-11-10T10:15")).isEqualTo(15.0);
        assertThat(IncidentService.minutesBetweenStamps("2026-11-10T10:30:00", "2026-11-10T10:00:00")).isNull();
        assertThat(IncidentService.minutesBetweenStamps("dün", "2026-11-10T10:00:00")).isNull();
        assertThat(IncidentService.median(List.of(10d, 40d))).isEqualTo(25L);
        assertThat(IncidentService.median(List.of(50d, 10d, 30d))).isEqualTo(30L);
        assertThat(IncidentService.median(List.of())).isNull();
    }

    // ── Seçenek yeniden adlandırma / birleştirme ──────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("kullanım: takımın kayıtları (takımsız kayıtta giren takım) + takım / genel seçenek satırları; CSV öğe başına sayılır")
    void optionUsage() {
        save("a", e -> e.setChannel("Mobil Şube, ATM"));
        save("b", e -> e.setChannel("mobil şube"));
        save("c", e -> { e.setTeamId(null); e.setCreatedByTeamId(1L); e.setChannel("ATM"); });
        save("d", e -> { e.setTeamId(2L); e.setChannel("Gişe"); });
        options.saveAndFlush(new IncidentOption("CHANNEL", "IVR", "sre1", "2026-11-01T00:00:00", null));
        options.saveAndFlush(new IncidentOption("CHANNEL", "ATM", "sre1", "2026-11-01T00:00:00", 1L));
        IncidentService.UsageReport report = service.optionUsage("channel", 1L);
        assertThat(report.truncated()).isFalse();
        assertThat(report.rowsRead()).isEqualTo(3);
        List<IncidentService.OptionUsage> u = report.values();
        assertThat(u).extracting(IncidentService.OptionUsage::value).containsExactly("ATM", "IVR", "Mobil Şube");
        IncidentService.OptionUsage atm = u.get(0);
        assertThat(atm.records()).isEqualTo(2L);
        assertThat(atm.teamOption()).isTrue();
        assertThat(atm.globalOption()).isFalse();
        assertThat(u.get(1).records()).isZero();
        assertThat(u.get(1).globalOption()).isTrue();
        assertThat(u.get(2).records()).isEqualTo(2L);
    }

    @Test
    @DisplayName("yeniden adlandırma TAM öğe: 'Mobil Şube' değişir, 'Mobil Şube Plus' kalır; başka takımın kaydına dokunulmaz; sürüm artar")
    void renameWholeToken() {
        IncidentRecord a = save("a", e -> e.setChannel("Mobil Şube, Mobil Şube Plus"));
        IncidentRecord b = save("b", e -> e.setChannel("mobil şube"));
        IncidentRecord other = save("c", e -> { e.setTeamId(2L); e.setChannel("Mobil Şube"); });
        long va = a.getVersion();
        IncidentService.RenameResult r = service.renameOptions("CHANNEL", 1L, List.of("Mobil Şube"), "Mobil", "admin");
        repo.flush();
        assertThat(r.merge()).isFalse();
        assertThat(r.records()).isEqualTo(2);
        assertThat(r.recordIds()).containsExactly(a.getId(), b.getId());
        assertThat(r.perValue()).containsEntry("Mobil Şube", 2);
        IncidentRecord a2 = repo.findById(a.getId()).orElseThrow();
        assertThat(a2.getChannel()).isEqualTo("Mobil, Mobil Şube Plus");
        assertThat(a2.getVersion()).isEqualTo(va + 1);
        assertThat(a2.getUpdatedBy()).isEqualTo("admin");
        assertThat(repo.findById(b.getId()).orElseThrow().getChannel()).isEqualTo("Mobil");
        assertThat(repo.findById(other.getId()).orElseThrow().getChannel()).isEqualTo("Mobil Şube");
    }

    @Test
    @DisplayName("birleştirme: iki değer tek değerde, aynı kayıtta tekilleşir; takım seçenekleri silinir, hedef eklenir, genel seçenek korunur")
    void mergeDedupesAndOptions() {
        IncidentRecord a = save("a", e -> e.setChannel("IVR, Çağrı Merkezi"));
        IncidentRecord b = save("b", e -> e.setChannel("ivr"));
        options.saveAndFlush(new IncidentOption("CHANNEL", "IVR", "sre1", "2026-11-01T00:00:00", 1L));
        options.saveAndFlush(new IncidentOption("CHANNEL", "Çağrı Merkezi", "sre1", "2026-11-01T00:00:00", null));
        IncidentService.RenameResult r = service.renameOptions("CHANNEL", 1L, List.of("IVR", "Çağrı Merkezi"), "Sesli Kanal", "admin");
        repo.flush();
        assertThat(r.merge()).isTrue();
        assertThat(repo.findById(a.getId()).orElseThrow().getChannel()).isEqualTo("Sesli Kanal");
        assertThat(repo.findById(b.getId()).orElseThrow().getChannel()).isEqualTo("Sesli Kanal");
        assertThat(r.optionsRemoved()).isEqualTo(1);
        assertThat(r.globalKept()).containsExactly("Çağrı Merkezi");
        List<String> after = options.findByTypeForTeam("CHANNEL", 1L).stream().map(IncidentOption::getValue).toList();
        assertThat(after).contains("Sesli Kanal", "Çağrı Merkezi").doesNotContain("IVR");
    }

    @Autowired com.sitemonitor.repository.TechErrorReportIncidentRepository links;
    @Autowired jakarta.persistence.EntityManager em;
    @Autowired org.springframework.test.context.event.ApplicationEvents appEvents;

    private com.sitemonitor.model.TechErrorReportIncident link(long reportId, String issueId, Long incidentId, String incidentUpdatedAt) {
        com.sitemonitor.model.TechErrorReportIncident l = new com.sitemonitor.model.TechErrorReportIncident();
        l.setReportId(reportId);
        l.setChannelId(7L);
        l.setSectionId("issues");
        l.setIssueId(issueId);
        l.setIncidentId(incidentId);
        l.setIncidentUpdatedAt(incidentUpdatedAt);
        return links.saveAndFlush(l);
    }

    @Test
    @DisplayName("birleştirme: olay değişikliği YAYIMLANMAZ; eşleme kaynağı değişmez, olayla eşit (ok) bağ yeni updated_at'e taşınır, zaten bayat bağ bayat kalır")
    void renameKeepsTechErrorLinksOk() {
        IncidentRecord a = save("a", e -> e.setChannel("IVR, ATM"));
        IncidentRecord b = save("b", e -> { e.setChannel("ivr"); e.setUpdatedAt("2026-11-10T11:00:00"); });
        IncidentRecord legacy = save("c", e -> { e.setChannel("IVR"); e.setUpdatedAt(null); });
        String srcBefore = com.sitemonitor.service.techerror.TechErrorIncidentLinks.sourceFp(
                com.sitemonitor.service.techerror.TechErrorIncidentMapper.Source.of(a));
        Long la = link(100L, "i-a", a.getId(), a.getUpdatedAt()).getId();
        Long lb = link(101L, "i-b", b.getId(), "2026-11-10T10:05:00").getId();   // olay bağdan sonra düzenlenmiş: bayat
        Long lc = link(102L, "i-c", legacy.getId(), null).getId();               // eski satır: iki taraf da null (eşit)
        long eventsBefore = appEvents.stream(IncidentRecordChangedEvent.class).count();

        service.renameOptions("CHANNEL", 1L, List.of("IVR"), "Sesli Kanal", "admin");
        em.flush();
        em.clear();

        IncidentRecord a2 = repo.findById(a.getId()).orElseThrow();
        assertThat(a2.getChannel()).isEqualTo("Sesli Kanal, ATM");
        assertThat(a2.getUpdatedAt()).isNotEqualTo("2026-11-10T10:05:00");
        assertThat(com.sitemonitor.service.techerror.TechErrorIncidentLinks.sourceFp(
                com.sitemonitor.service.techerror.TechErrorIncidentMapper.Source.of(a2)))
                .as("seçenek alanları eşleme kaynağında yok").isEqualTo(srcBefore);
        assertThat(links.findById(la).orElseThrow().getIncidentUpdatedAt()).as("ok bağ ok kalır").isEqualTo(a2.getUpdatedAt());
        assertThat(links.findById(lb).orElseThrow().getIncidentUpdatedAt()).as("bayat bağa dokunulmaz").isEqualTo("2026-11-10T10:05:00");
        assertThat(links.findById(lc).orElseThrow().getIncidentUpdatedAt())
                .isEqualTo(repo.findById(legacy.getId()).orElseThrow().getUpdatedAt());
        assertThat(appEvents.stream(IncidentRecordChangedEvent.class).count()).as("senkron kuyruğu doldurulmaz").isEqualTo(eventsBefore);
    }

    @Test
    @DisplayName("inceleme 2026-11-26: tarama ile kilit arasında değeri değişen (sonucu aynı kalan) kayıt atlanır — onun bağı TAŞINMAZ, "
            + "değer başına sayı yalnız değişen kayıtları sayar")
    void renameCarriesOnlyChangedRecordsLinks() {
        IncidentRecord a = save("a", e -> e.setChannel("IVR"));
        IncidentRecord b = save("b", e -> e.setChannel("IVR, ATM"));   // taramada değişecek: "IVR, ATM" → "ATM"
        Long la = link(110L, "i-a", a.getId(), a.getUpdatedAt()).getId();
        Long lb = link(111L, "i-b", b.getId(), b.getUpdatedAt()).getId();
        IncidentRecordRepository real = repo;
        IncidentRecordRepository hooked = org.mockito.Mockito.mock(IncidentRecordRepository.class,
                org.mockito.AdditionalAnswers.delegatesTo(real));
        // Aday taramasından SONRA, kilitten ÖNCE: b'nin kanalı başka bir yoldan zaten "ATM" olmuş (updated_at'e dokunmayan
        // eski pod / elle düzeltme) — "atm" kaynağı hâlâ eşleşir ama sonuç aynı ("ATM" → "ATM"): b atlanır.
        org.mockito.Mockito.doAnswer(inv -> {
            em.flush();
            em.createNativeQuery("UPDATE incident_records SET channel = 'ATM' WHERE id = " + b.getId()).executeUpdate();
            em.clear();
            return real.lockRows(inv.<java.util.Collection<Long>>getArgument(0));
        }).when(hooked).lockRows(org.mockito.ArgumentMatchers.anyCollection());
        org.springframework.test.util.ReflectionTestUtils.setField(service, "repo", hooked);
        IncidentService.RenameResult r;
        try {
            r = service.renameOptions("CHANNEL", 1L, List.of("IVR", "atm"), "ATM", "admin");
        } finally {
            org.springframework.test.util.ReflectionTestUtils.setField(service, "repo", real);
        }
        em.flush();
        em.clear();
        assertThat(r.records()).isEqualTo(1);
        assertThat(r.recordIds()).containsExactly(a.getId());
        assertThat(r.perValue()).as("sonucu aynı kalan b sayılmaz").containsExactly(Map.entry("IVR", 1), Map.entry("atm", 0));
        IncidentRecord a2 = repo.findById(a.getId()).orElseThrow();
        IncidentRecord b2 = repo.findById(b.getId()).orElseThrow();
        assertThat(a2.getChannel()).isEqualTo("ATM");
        assertThat(b2.getChannel()).isEqualTo("ATM");
        assertThat(b2.getUpdatedAt()).as("atlanan kayıt yazılmadı").isEqualTo("2026-11-10T10:05:00");
        assertThat(links.findById(la).orElseThrow().getIncidentUpdatedAt()).isEqualTo(a2.getUpdatedAt());
        assertThat(links.findById(lb).orElseThrow().getIncidentUpdatedAt())
                .as("atlanan kaydın bağı olayla eşit kalır (yersiz 'bayat' yok)").isEqualTo(b2.getUpdatedAt());
    }

    @Test
    @DisplayName("inceleme 2026-11-26: kullanım okuması sınırlı — tavanı aşınca truncated + okunan satır; yeniden adlandırma taraması "
            + "tavanı aşınca alan adlı 400, hiçbir kayıt değişmez")
    void usageAndRenameScanAreBounded() {
        IncidentRecord a = save("a", e -> e.setChannel("ATM"));
        save("b", e -> e.setChannel("ATM, IVR"));
        save("c", e -> e.setChannel("Gişe"));
        // service bir işlem vekili (CGLIB) — alan vekilde değil HEDEFTE değişmeli (ReflectionTestUtils vekili açar)
        Object usageCap = org.springframework.test.util.ReflectionTestUtils.getField(service, "optionUsageMaxRows");
        Object scanCap = org.springframework.test.util.ReflectionTestUtils.getField(service, "renameScanMaxRows");
        try {
            org.springframework.test.util.ReflectionTestUtils.setField(service, "optionUsageMaxRows", 2);
            IncidentService.UsageReport u = service.optionUsage("CHANNEL", 1L);
            assertThat(u.truncated()).isTrue();
            assertThat(u.rowsRead()).isEqualTo(2);
            assertThat(u.values()).extracting(IncidentService.OptionUsage::value).containsExactly("ATM", "IVR");
            org.springframework.test.util.ReflectionTestUtils.setField(service, "renameScanMaxRows", 2);
            assertField(() -> service.renameOptions("CHANNEL", 1L, List.of("ATM"), "Bankamatik", "admin"), "from");
        } finally {
            org.springframework.test.util.ReflectionTestUtils.setField(service, "optionUsageMaxRows", usageCap);
            org.springframework.test.util.ReflectionTestUtils.setField(service, "renameScanMaxRows", scanCap);
        }
        em.flush();
        em.clear();
        assertThat(repo.findById(a.getId()).orElseThrow().getChannel()).isEqualTo("ATM");
    }

    @Test
    @DisplayName("inceleme 2026-11-26: bilinmeyen seçenek türü alan adlı 400 (type), metin girdiyi yansıtmaz; küçük harf tür Türkçe "
            + "yerel ayarda da tanınır")
    void unknownOptionTypeIsFieldError() {
        assertField(() -> service.optionUsage("<b>bogus</b>", 1L), "type");
        assertThatThrownBy(() -> service.optionUsage("<b>bogus</b>", 1L)).hasMessageNotContaining("bogus");
        assertField(() -> service.renameOptions("nope", 1L, List.of("a"), "b", "admin"), "type");
        java.util.Locale before = java.util.Locale.getDefault();
        try {
            java.util.Locale.setDefault(java.util.Locale.forLanguageTag("tr-TR"));
            assertThat(service.optionUsage("domain", 1L).values()).isEmpty();
        } finally {
            java.util.Locale.setDefault(before);
        }
    }

    @Test
    @DisplayName("inceleme 2026-11-26: günlük görünüm yalnız daily döndürür (özet / kırılım / MTTD hesaplanmaz); süzgeçli ve süzgeçsiz "
            + "yolda tam çağrının daily'siyle aynı")
    void dailyOnlyTrend() {
        save("t1", e -> { e.setOccurredAt("2026-11-10T21:30:00"); e.setSeverity("CRITICAL"); e.setTags("prod"); });
        save("t2", e -> { e.setOccurredAt("2026-11-11T08:00:00"); e.setTags("prod"); });
        save("t3", e -> e.setOccurredAt("2026-11-11T09:00:00"));
        IncidentService.ListFilter none = IncidentService.ListFilter.dates("2026-11-01T00:00:00", "2026-11-30T23:59:59");
        IncidentService.ListFilter tag = new IncidentService.ListFilter(null, null, null, null, null, null,
                "2026-11-01T00:00:00", "2026-11-30T23:59:59", null, null, null, null, null, null, null, "prod", null, null);
        for (IncidentService.ListFilter f : List.of(none, tag)) {
            Map<String, Object> full = service.trends(f, null);
            Map<String, Object> daily = service.trends(f, null, true);
            assertThat(daily).containsOnlyKeys("daily", "filtered", "daily_only");
            assertThat(daily.get("daily")).isEqualTo(full.get("daily"));
            assertThat(daily.get("filtered")).isEqualTo(full.get("filtered"));
            assertThat(daily.get("daily_only")).isEqualTo(true);
        }
    }

    @Test
    @DisplayName("kod alanı: değerin tamamı eşleşir (E-1 → E-01 değişir, E-10 kalmaz değişmez); mevcut hedefe yeniden adlandırma birleştirme sayılır")
    void codeFieldExact() {
        IncidentRecord a = save("a", e -> e.setErrorCode("E-1"));
        IncidentRecord b = save("b", e -> e.setErrorCode("E-10"));
        save("c", e -> e.setErrorCode("E-01"));
        IncidentService.RenameResult r = service.renameOptions("ERROR_CODE", 1L, List.of("e-1"), "E-01", "admin");
        repo.flush();
        assertThat(r.merge()).as("hedef değer takımın kayıtlarında zaten var").isTrue();
        assertThat(repo.findById(a.getId()).orElseThrow().getErrorCode()).isEqualTo("E-01");
        assertThat(repo.findById(b.getId()).orElseThrow().getErrorCode()).isEqualTo("E-10");
    }

    @Test
    @DisplayName("doğrulama: takım yok / hedef boş / CSV hedefte virgül / aynı değer / kaynak yok / alan sınırı → alan adlı 400 ve hiçbir kayıt değişmez")
    void renameValidation() {
        IncidentRecord a = save("a", e -> { e.setChannel("ATM"); e.setErrorCode("E-1"); });
        assertField(() -> service.renameOptions("CHANNEL", null, List.of("ATM"), "x", "admin"), "team_id");
        assertField(() -> service.renameOptions("CHANNEL", 1L, List.of("ATM"), "  ", "admin"), "to");
        assertField(() -> service.renameOptions("CHANNEL", 1L, List.of("ATM"), "A,B", "admin"), "to");
        assertField(() -> service.renameOptions("CHANNEL", 1L, List.of("ATM"), "ATM", "admin"), "to");
        assertField(() -> service.renameOptions("CHANNEL", 1L, List.of(" "), "Yeni", "admin"), "from");
        assertField(() -> service.renameOptions("ERROR_CODE", 1L, List.of("E-1"), "x".repeat(101), "admin"), "to");
        assertThat(repo.findById(a.getId()).orElseThrow().getChannel()).isEqualTo("ATM");
        // Büyük-küçük harf düzeltmesi yeniden adlandırmadır (aynı değer sayılmaz)
        service.renameOptions("CHANNEL", 1L, List.of("ATM"), "Atm", "admin");
        repo.flush();
        assertThat(repo.findById(a.getId()).orElseThrow().getChannel()).isEqualTo("Atm");
    }

    private static void assertField(Runnable r, String field) {
        assertThatThrownBy(r::run).isInstanceOfSatisfying(FieldValidationException.class,
                ex -> assertThat(ex.getFields()).containsOnlyKeys(field));
    }

    @Test
    @DisplayName("sorgu süzgeci: tarihler UTC'ye, arama küçük harf + kaçış, kimlik; süzgeçsiz istekte yalnız tarih")
    void toQueryShape() {
        IncidentRecordQueries.Filter f = IncidentService.toQuery(q("#42"), List.of(1L));
        assertThat(f.qid()).isEqualTo(42L);
        assertThat(f.q()).isEqualTo("%#42%");
        assertThat(IncidentService.toQuery(q("A%b_"), null).q()).isEqualTo("%a!%b!_%");
        assertThat(IncidentService.tokenPattern(" Mobil Şube ")).isEqualTo("%,Mobil Şube,%");
        assertThat(IncidentService.ListFilter.dates("2026-11-01", null).onlyDates()).isTrue();
        assertThat(ext(null, null, null, null, "prod", null, null).onlyDates()).isFalse();
        assertThat(ext(null, null, null, null, "prod", null, null).hasExtended()).isTrue();
    }
}
