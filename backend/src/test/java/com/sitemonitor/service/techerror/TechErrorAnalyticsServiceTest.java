package com.sitemonitor.service.techerror;

import com.sitemonitor.model.TechErrorChannel;
import com.sitemonitor.model.TechErrorReport;
import com.sitemonitor.repository.TechErrorChannelRepository;
import com.sitemonitor.repository.TechErrorReportRepository;
import com.sitemonitor.service.techerror.TechErrorAnalyticsService.ChannelSeries;
import com.sitemonitor.service.techerror.TechErrorAnalyticsService.Result;
import com.sitemonitor.service.techerror.TechErrorTemplate.Field;
import com.sitemonitor.service.techerror.TechErrorTemplate.Section;
import com.sitemonitor.service.techerror.TechErrorTemplate.Template;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Kanallar arası analiz (2026-10-18, R22) — H2'de GERÇEK SQL. Değer yalnız raporun KENDİ şablonunda aynı kimlik ve aynı
 * türdeyse alınır; tür farkı / muaf / rapor yok → null (asla 0); hesaplanan alan saklandığı gibi; pencere önceki takvim
 * ayıyla biter; hücre sınırında en yeni aylar kalır; sorgu sayısı kanal sayısından bağımsız (2); sonuç 60 sn hafızada.
 */
@DataJpaTest
@Import(TechErrorAnalyticsService.class)
@TestPropertySource(properties = {"spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.generate_statistics=true"})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class TechErrorAnalyticsServiceTest {

    private static final YearMonth END = YearMonth.of(2026, 9);
    private static final long A = 1L, B = 2L, C = 3L;
    private static final Map<Long, String> NAMES = Map.of(A, "Bireysel Kanallar", B, "Kurumsal Kanallar", C, "Ödeme Sistemleri");

    @Autowired TechErrorAnalyticsService analytics;
    @Autowired TechErrorChannelRepository channels;
    @Autowired TechErrorReportRepository reports;
    @Autowired EntityManagerFactory emf;

    @BeforeEach
    void clean() {
        reports.deleteAll();
        channels.deleteAll();
        analytics.invalidate();
    }

    // ── Yardımcılar ────────────────────────────────────────────────────────────────────────────────

    private TechErrorChannel channel(long team, String name, Template t, boolean active) {
        TechErrorChannel ch = new TechErrorChannel();
        ch.setTeamId(team);
        ch.setName(name);
        ch.setNameKey(name.toLowerCase(java.util.Locale.forLanguageTag("tr")));
        ch.setActive(active);
        ch.setTemplateJson(TechErrorTemplate.toJson(t));
        ch.setTemplateVersion(1);
        ch.setCreatedAt(Instant.parse("2025-01-01T00:00:00Z"));
        return channels.save(ch);
    }

    private TechErrorChannel channel(long team, String name) {
        return channel(team, name, TechErrorTemplate.defaults("tr"), true);
    }

    private TechErrorReport report(TechErrorChannel ch, YearMonth ym, Template t, Map<String, Object> fields, Instant sentAt) {
        TechErrorReport r = new TechErrorReport();
        r.setChannelId(ch.getId());
        r.setTeamId(ch.getTeamId());
        r.setReportYear(ym.getYear());
        r.setReportMonth(ym.getMonthValue());
        r.setTemplateJson(TechErrorTemplate.toJson(t));
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("fields", fields);
        r.setValuesJson(TechErrorTemplate.JSON.writeValueAsString(raw));
        r.setLastSentAt(sentAt);
        r.setCreatedAt(Instant.parse("2026-01-01T00:00:00Z"));
        return reports.save(r);
    }

    private TechErrorReport report(TechErrorChannel ch, YearMonth ym, Map<String, Object> fields) {
        return report(ch, ym, TechErrorTemplate.defaults("tr"), fields, null);
    }

    /** Şablonda bir alanın türünü değiştirir (hesap / muafiyet bağı düşer). */
    private static Template withType(Template t, String id, String type) {
        List<Section> out = new ArrayList<>();
        for (Section s : t.sections()) {
            if (!"metrics".equals(s.kind())) { out.add(s); continue; }
            List<Field> fs = new ArrayList<>();
            for (Field f : s.fields()) fs.add(f.id().equals(id) ? new Field(f.id(), f.label(), type, f.hint()) : f);
            out.add(s.withFields(List.copyOf(fs)));
        }
        return new Template(t.lang(), t.title(), List.copyOf(out));
    }

    /** Şablondan bir alanı çıkarır. */
    private static Template without(Template t, String id) {
        List<Section> out = new ArrayList<>();
        for (Section s : t.sections()) {
            if (!"metrics".equals(s.kind())) { out.add(s); continue; }
            List<Field> fs = new ArrayList<>();
            for (Field f : s.fields()) if (!f.id().equals(id)) fs.add(f);
            out.add(s.withFields(List.copyOf(fs)));
        }
        return new Template(t.lang(), t.title(), List.copyOf(out));
    }

    private static Map<String, Object> vals(Object... kv) {
        Map<String, Object> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    private Result run(Set<Long> teams, Long team, int months, List<String> fields) {
        return analytics.analytics(teams, team, months, fields, END, NAMES);
    }

    private static ChannelSeries series(Result r, String name) {
        return r.channels().stream().filter(c -> c.name().equals(name)).findFirst().orElseThrow();
    }

    private static int idx(Result r, YearMonth ym) {
        return r.months().indexOf(ym);
    }

    // ── Testler ────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("pencere önceki takvim ayıyla biter (12 ay, eskiden yeniye); yalnız AKTİF kanallar; değer + rapor kimliği + gönderildi")
    void windowValuesAndReports() {
        TechErrorChannel mobil = channel(A, "Mobil");
        TechErrorChannel pos = channel(A, "POS");
        channel(A, "Eski Kanal", TechErrorTemplate.defaults("tr"), false);
        TechErrorReport sep = report(mobil, END, TechErrorTemplate.defaults("tr"),
                vals("total-tx", 1_250_000, "error-rate", 0.42), Instant.parse("2026-10-05T08:00:00Z"));
        report(mobil, END.minusMonths(1), vals("total-tx", 1_100_000, "error-rate", 0.5));
        report(pos, END.minusMonths(11), vals("total-tx", 900));
        report(pos, END.minusMonths(12), vals("total-tx", 800));        // pencere dışında

        Result r = run(Set.of(A), null, 12, List.of("error-rate", "total-tx"));
        assertThat(r.months()).hasSize(12);
        assertThat(r.months().get(0)).isEqualTo(YearMonth.of(2025, 10));
        assertThat(r.months().get(11)).isEqualTo(END);
        assertThat(r.channels()).extracting(ChannelSeries::name).containsExactly("Mobil", "POS");
        assertThat(r.channelCount()).isEqualTo(2);
        assertThat(r.truncated()).isFalse();
        ChannelSeries m = series(r, "Mobil");
        assertThat(m.values().get("total-tx").get(11)).isEqualTo(1_250_000d);
        assertThat(m.values().get("total-tx").get(10)).isEqualTo(1_100_000d);
        assertThat(m.values().get("error-rate").get(11)).isEqualTo(0.42);
        assertThat(m.reports().get(11).id()).isEqualTo(sep.getId());
        assertThat(m.reports().get(11).sent()).isTrue();
        assertThat(m.reports().get(10).sent()).isFalse();
        // Raporu olmayan ay null — 0 değil
        assertThat(m.values().get("total-tx").get(9)).isNull();
        assertThat(m.reports().get(9)).isNull();
        ChannelSeries p = series(r, "POS");
        assertThat(p.values().get("total-tx").get(0)).isEqualTo(900d);
        assertThat(p.values().get("error-rate").get(0)).isNull();       // değeri girilmemiş alan
        assertThat(r.fields()).extracting(TechErrorAnalyticsService.FieldRef::type).containsExactly("percent", "number");
    }

    @Test
    @DisplayName("yalnız aynı kimlik VE aynı tür: raporun kendi şablonunda tür farklıysa ya da alan yoksa o ay null")
    void sameIdAndTypeOnly() {
        TechErrorChannel mobil = channel(A, "Mobil");
        // Ağustos raporu toplam işlemi YÜZDE olarak tanımlayan eski bir şablonla açılmış
        report(mobil, END.minusMonths(1), withType(TechErrorTemplate.defaults("tr"), "total-tx", "percent"),
                vals("total-tx", 55), null);
        // Temmuz raporunun şablonunda alan hiç yok (değer JSON'da dursa da okunmaz)
        report(mobil, END.minusMonths(2), without(TechErrorTemplate.defaults("tr"), "total-tx"), vals("total-tx", 77), null);
        report(mobil, END, vals("total-tx", 1000));

        Result r = run(Set.of(A), null, 12, List.of("total-tx"));
        List<Double> tx = series(r, "Mobil").values().get("total-tx");
        assertThat(tx.get(11)).isEqualTo(1000d);
        assertThat(tx.get(10)).as("tür farkı").isNull();
        assertThat(tx.get(9)).as("şablonda yok").isNull();
        // Ay için rapor VAR (kimlik döner) ama değer yok
        assertThat(series(r, "Mobil").reports().get(10)).isNotNull();
    }

    @Test
    @DisplayName("referans tür: alanı tanımlayan İLK kanalın güncel şablonundan; ikinci kanalda farklı türse o kanalın değeri null")
    void referenceTypeFromFirstChannel() {
        TechErrorChannel a = channel(A, "A Kanalı");                                            // total-tx sayı
        Template pct = withType(TechErrorTemplate.defaults("tr"), "total-tx", "percent");
        TechErrorChannel b = channel(A, "B Kanalı", pct, true);                                 // total-tx yüzde
        report(a, END, vals("total-tx", 10));
        report(b, END, pct, vals("total-tx", 20), null);
        Result r = run(Set.of(A), null, 12, List.of("total-tx"));
        assertThat(r.fields().get(0).type()).isEqualTo("number");
        assertThat(series(r, "A Kanalı").values().get("total-tx").get(11)).isEqualTo(10d);
        assertThat(series(r, "B Kanalı").values().get("total-tx").get(11)).isNull();
        // Seçilebilir alanlar: aynı türle tanımlayan kanal sayısı
        var opt = r.available().stream().filter(o -> o.id().equals("total-tx")).findFirst().orElseThrow();
        assertThat(opt.type()).isEqualTo("number");
        assertThat(opt.channels()).isEqualTo(1);
        var rate = r.available().stream().filter(o -> o.id().equals("error-rate")).findFirst().orElseThrow();
        assertThat(rate.channels()).isEqualTo(2);
    }

    @Test
    @DisplayName("muaf alan (bağlı soru \"Hayır\") null; soru \"Evet\" ise değer okunur")
    void exemptIsNull() {
        TechErrorChannel mobil = channel(A, "Mobil");
        report(mobil, END, vals("had-outage", false, "outage-errors", 5));
        report(mobil, END.minusMonths(1), vals("had-outage", true, "outage-errors", 7));
        Result r = run(Set.of(A), null, 12, List.of("outage-errors"));
        List<Double> v = series(r, "Mobil").values().get("outage-errors");
        assertThat(v.get(11)).isNull();
        assertThat(v.get(10)).isEqualTo(7d);
    }

    @Test
    @DisplayName("hesaplanan alan SAKLANDIĞI hâliyle — eski ay yeniden hesaplanmaz")
    void computedStoredValue() {
        TechErrorChannel mobil = channel(A, "Mobil");
        // error-rate = error-count ÷ total-tx olurdu (%10) — saklı değer 0,5 okunur
        report(mobil, END, vals("error-count", 100, "total-tx", 1000, "error-rate", 0.5));
        // hesap girdisi var ama saklı oran yok → null (yeniden hesaplanmaz)
        report(mobil, END.minusMonths(1), vals("error-count", 50, "total-tx", 1000));
        Result r = run(Set.of(A), null, 12, List.of("error-rate"));
        List<Double> v = series(r, "Mobil").values().get("error-rate");
        assertThat(v.get(11)).isEqualTo(0.5);
        assertThat(v.get(10)).isNull();
    }

    @Test
    @DisplayName("takım süzgeci; okunabilen takım kümesi dışındaki kanal yok; takım seçici yalnız aktif kanalı olan takımlar (ad sırası)")
    void scopeAndTeamFilter() {
        channel(A, "Mobil");
        channel(B, "Kurumsal İnternet");
        channel(C, "Sanal POS");
        channel(C, "Kapalı", TechErrorTemplate.defaults("tr"), false);
        Result all = run(Set.of(A, B), null, 12, List.of());
        assertThat(all.channels()).extracting(ChannelSeries::name).containsExactly("Mobil", "Kurumsal İnternet");
        assertThat(all.teamsWithChannels()).containsExactly(A, B);
        Result onlyB = run(Set.of(A, B), B, 12, List.of());
        assertThat(onlyB.channels()).extracting(ChannelSeries::name).containsExactly("Kurumsal İnternet");
        assertThat(onlyB.teamsWithChannels()).containsExactly(A, B);
        assertThat(run(Set.of(), null, 12, List.of()).channels()).isEmpty();
    }

    @Test
    @DisplayName("alan seçimi: boşsa varsayılan error-rate + total-tx; bilinmeyen kimlik düşer; kanallarda olmayan varsayılan alan varsayılan türle")
    void fieldResolution() {
        Template noSla = without(TechErrorTemplate.defaults("tr"), "sla-rate");
        channel(A, "Mobil", noSla, true);
        Result def = run(Set.of(A), null, 12, List.of());
        assertThat(def.fields()).extracting(TechErrorAnalyticsService.FieldRef::id).containsExactly("error-rate", "total-tx");
        assertThat(def.available()).extracting(TechErrorAnalyticsService.FieldOption::id).doesNotContain("sla-rate", "had-outage");
        Result pick = run(Set.of(A), null, 12, List.of("sla-rate", "yok-boyle-alan", "total-tx"));
        assertThat(pick.fields()).extracting(TechErrorAnalyticsService.FieldRef::id).containsExactly("sla-rate", "total-tx");
        assertThat(pick.fields().get(0).type()).isEqualTo("percent");
        assertThat(series(pick, "Mobil").values().get("sla-rate")).containsOnlyNulls();
    }

    @Test
    @DisplayName("hücre sınırı: 51 kanal × 24 ay > 1200 → en yeni 23 ay kalır, truncated; en eski ayın raporu düşer")
    void truncationKeepsNewestMonths() {
        List<TechErrorChannel> list = new ArrayList<>();
        for (int i = 0; i < 51; i++) list.add(channel(i < 26 ? A : B, String.format("Kanal %02d", i)));
        report(list.get(0), END.minusMonths(23), vals("total-tx", 1));   // 24 aylık pencerenin en eski ayı
        report(list.get(0), END.minusMonths(22), vals("total-tx", 2));
        Result r = run(Set.of(A, B), null, 24, List.of("total-tx"));
        assertThat(r.truncated()).isTrue();
        assertThat(r.monthsRequested()).isEqualTo(24);
        assertThat(r.months()).hasSize(23);
        assertThat(r.months().get(22)).isEqualTo(END);
        assertThat(r.months().get(0)).isEqualTo(END.minusMonths(22));
        assertThat(r.channels()).hasSize(51);
        ChannelSeries first = series(r, "Kanal 00");
        assertThat(first.values().get("total-tx")).hasSize(23);
        assertThat(first.values().get("total-tx").get(0)).isEqualTo(2d);
        // Sınırın altında kesilmez
        assertThat(run(Set.of(A, B), null, 12, List.of("total-tx")).truncated()).isFalse();
    }

    @Test
    @DisplayName("SABİT SORGU: 1 kanal / 1 rapor ile 8 kanal / 40 rapor aynı sayıda cümle (kanal + rapor = 2)")
    void fixedQueryCount() {
        Statistics stats = emf.unwrap(SessionFactory.class).getStatistics();
        TechErrorChannel one = channel(A, "Tek");
        report(one, END, vals("total-tx", 1));
        stats.clear();
        run(Set.of(A), null, 12, List.of("total-tx", "error-rate"));
        long small = stats.getPrepareStatementCount();
        for (int i = 0; i < 7; i++) {
            TechErrorChannel ch = channel(A, "K" + i);
            for (int k = 0; k < 5; k++) report(ch, END.minusMonths(k), vals("total-tx", k, "error-rate", 0.1 * k));
        }
        analytics.invalidate();
        stats.clear();
        Result r = run(Set.of(A), null, 12, List.of("total-tx", "error-rate"));
        assertThat(r.channels()).hasSize(8);
        assertThat(stats.getPrepareStatementCount()).isEqualTo(small).isEqualTo(2);
    }

    @Test
    @DisplayName("60 sn hafıza: aynı anahtarla ikinci okuma sorgusuz ve aynı sonuç; boşaltınca yeni rapor görünür")
    void memoized() {
        Statistics stats = emf.unwrap(SessionFactory.class).getStatistics();
        TechErrorChannel mobil = channel(A, "Mobil");
        report(mobil, END, vals("total-tx", 10));
        Result first = run(Set.of(A), null, 12, List.of("total-tx"));
        report(mobil, END.minusMonths(1), vals("total-tx", 20));
        stats.clear();
        Result again = run(Set.of(A), null, 12, List.of("total-tx"));
        assertThat(stats.getPrepareStatementCount()).isZero();
        assertThat(again).isSameAs(first);
        assertThat(series(again, "Mobil").values().get("total-tx").get(10)).isNull();
        // Farklı anahtar (6 ay) ayrı hesaplanır
        assertThat(run(Set.of(A), null, 6, List.of("total-tx")).months()).hasSize(6);
        analytics.invalidate();
        assertThat(series(run(Set.of(A), null, 12, List.of("total-tx")), "Mobil").values().get("total-tx").get(10)).isEqualTo(20d);
    }

    @Test
    @DisplayName("depo sorgusu: kanal kümesi ve dönem aralığı (yıl × 12 + ay) iki uç dahil")
    void repositoryWindowQuery() {
        TechErrorChannel mobil = channel(A, "Mobil");
        TechErrorChannel pos = channel(B, "POS");
        report(mobil, YearMonth.of(2025, 12), vals());
        report(mobil, YearMonth.of(2026, 1), vals());
        report(mobil, YearMonth.of(2026, 9), vals());
        report(mobil, YearMonth.of(2026, 10), vals());
        report(pos, YearMonth.of(2026, 3), vals());
        int from = TechErrorAnalyticsService.period(YearMonth.of(2026, 1));
        int to = TechErrorAnalyticsService.period(YearMonth.of(2026, 9));
        var rows = reports.findAnalyticsRows(List.of(mobil.getId()), from, to);
        assertThat(rows).extracting(TechErrorReportRepository.AnalyticsRow::getReportMonth).containsExactlyInAnyOrder(1, 9);
        assertThat(rows.get(0).getTemplateJson()).isNotBlank();
        assertThat(rows.get(0).getValuesJson()).isNotBlank();
        assertThat(reports.findAnalyticsRows(List.of(mobil.getId(), pos.getId()), from, to)).hasSize(3);
    }
}
