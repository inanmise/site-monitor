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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Kanallar arası analiz (2026-10-18, R22) — H2'de GERÇEK SQL. Değer yalnız raporun KENDİ şablonunda aynı kimlik ve aynı
 * türdeyse alınır; tür farkı / muaf / rapor yok → null (asla 0); hesaplanan alan rapor ekranı / PDF gibi CANLI (geçen ay
 * pencerenin bir ay öncesinden de okunur); referans tür çoğunluk; pencere önceki takvim ayıyla biter; hücre sınırında en
 * yeni aylar kalır; sorgu sayısı kanal sayısından bağımsız (kanal + dilimler); sonuç 60 sn hafızada (Yenile 5 sn'de bir).
 */
@DataJpaTest
@Import({TechErrorAnalyticsService.class, TechErrorReportService.class, TechErrorHistoryService.class})
@TestPropertySource(properties = {"spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.generate_statistics=true"})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class TechErrorAnalyticsServiceTest {

    private static final YearMonth END = YearMonth.of(2026, 9);
    private static final long A = 1L, B = 2L, C = 3L;
    private static final Map<Long, String> NAMES = Map.of(A, "Bireysel Kanallar", B, "Kurumsal Kanallar", C, "Ödeme Sistemleri");

    @Autowired TechErrorAnalyticsService analytics;
    @Autowired TechErrorReportService reportService;
    @Autowired TechErrorChannelRepository channels;
    @Autowired TechErrorReportRepository reports;
    @Autowired EntityManagerFactory emf;

    @BeforeEach
    void clean() {
        reports.deleteAll();
        channels.deleteAll();
        analytics.useClock(System::currentTimeMillis);
        analytics.chunkSize = TechErrorAnalyticsService.CHUNK;
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

    /** Raporun değerlerini düzeltir (geçen ayın rakamı sonradan düzeltildi). */
    private TechErrorReport correct(TechErrorReport r, Map<String, Object> fields) {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("fields", fields);
        r.setValuesJson(TechErrorTemplate.JSON.writeValueAsString(raw));
        return reports.save(r);
    }

    /** Şablonda bir alanın etiketini değiştirir (hesap / bağlar korunur). */
    private static Template withLabel(Template t, String id, String label) {
        List<Section> out = new ArrayList<>();
        for (Section s : t.sections()) {
            if (!"metrics".equals(s.kind())) { out.add(s); continue; }
            List<Field> fs = new ArrayList<>();
            for (Field f : s.fields()) {
                fs.add(f.id().equals(id) ? new Field(f.id(), label, f.type(), f.hint(), f.skipIfNo(), f.calc(), f.target(), f.better()) : f);
            }
            out.add(s.withFields(List.copyOf(fs)));
        }
        return new Template(t.lang(), t.title(), List.copyOf(out));
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
        return analytics.analytics(teams, team, months, fields, END, NAMES, false);
    }

    private static ChannelSeries series(Result r, String name) {
        return r.channels().stream().filter(c -> c.name().equals(name)).findFirst().orElseThrow();
    }

    private static int idx(Result r, YearMonth ym) {
        return r.months().indexOf(ym);
    }

    // ── Testler ────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("pencere önceki takvim ayıyla biter (12 ay, eskiden yeniye); penceresinde raporu olmayan pasif kanal listelenmez; değer + rapor kimliği + gönderildi")
    void windowValuesAndReports() {
        TechErrorChannel mobil = channel(A, "Mobil");
        TechErrorChannel pos = channel(A, "POS");
        channel(A, "Eski Kanal", TechErrorTemplate.defaults("tr"), false);
        TechErrorReport sep = report(mobil, END, TechErrorTemplate.defaults("tr"),
                vals("total-tx", 1_250_000, "error-count", 5250, "error-rate", 0.42), Instant.parse("2026-10-05T08:00:00Z"));
        report(mobil, END.minusMonths(1), vals("total-tx", 1_100_000, "error-count", 5500, "error-rate", 0.5));
        report(pos, END.minusMonths(11), vals("total-tx", 900));
        report(pos, END.minusMonths(12), vals("total-tx", 800));        // pencere dışında (yalnız geçen ay olarak okunur)

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
        assertThat(m.values().get("error-rate").get(10)).isEqualTo(0.5);
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
        assertThat(r.dropped()).isEmpty();
        assertThat(r.fieldsFallback()).isFalse();
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
    @DisplayName("referans tür ÇOĞUNLUK: iki kanal yüzde, bir kanal sayı → yüzde; sayı tanımlayan kanal tür farkıyla işaretli, değeri null")
    void referenceTypeByMajority() {
        Template pct = withType(TechErrorTemplate.defaults("tr"), "total-tx", "percent");
        TechErrorChannel a = channel(A, "A Kanalı");                                            // total-tx sayı (ilk kanal)
        TechErrorChannel b = channel(A, "B Kanalı", pct, true);                                 // total-tx yüzde
        TechErrorChannel c = channel(A, "C Kanalı", pct, true);                                 // total-tx yüzde
        report(a, END, vals("total-tx", 10));
        report(b, END, pct, vals("total-tx", 20), null);
        report(c, END, pct, vals("total-tx", 30), null);
        Result r = run(Set.of(A), null, 12, List.of("total-tx"));
        assertThat(r.fields().get(0).type()).isEqualTo("percent");
        assertThat(r.fields().get(0).typeMismatch()).containsExactly(a.getId());
        assertThat(series(r, "A Kanalı").values().get("total-tx").get(11)).isNull();
        assertThat(series(r, "B Kanalı").values().get("total-tx").get(11)).isEqualTo(20d);
        assertThat(series(r, "C Kanalı").values().get("total-tx").get(11)).isEqualTo(30d);
        var opt = r.available().stream().filter(o -> o.id().equals("total-tx")).findFirst().orElseThrow();
        assertThat(opt.type()).isEqualTo("percent");
        assertThat(opt.channels()).isEqualTo(2);
        var rate = r.available().stream().filter(o -> o.id().equals("error-rate")).findFirst().orElseThrow();
        assertThat(rate.channels()).isEqualTo(3);
    }

    @Test
    @DisplayName("inceleme 2026-10-18 (2. tur): tür farkıyla işaretli kanalın ESKİ raporları — kopyalanmış şablonunda alan referans türdeyse — değer verir (aynı tür); güncel türdeki ayları null (not: 'yalnız aynı türdeki aylar gösterilir')")
    void flaggedChannelOldReportsWithReferenceTypeStillCount() {
        Template num = TechErrorTemplate.defaults("tr");                                         // total-tx sayı
        Template pct = withType(num, "total-tx", "percent");
        TechErrorChannel a = channel(A, "A Kanalı");                                            // güncel: sayı
        TechErrorChannel c = channel(A, "C Kanalı");                                            // güncel: sayı
        TechErrorChannel b = channel(A, "B Kanalı", pct, true);                                 // güncel: yüzde (azınlık)
        report(a, END, vals("total-tx", 10));
        report(c, END, vals("total-tx", 30));
        report(b, END.minusMonths(1), num, vals("total-tx", 700), null);                        // eski rapor: alan SAYI iken
        report(b, END, pct, vals("total-tx", 20), null);                                        // tür değişti
        Result r = run(Set.of(A), null, 12, List.of("total-tx"));
        assertThat(r.fields().get(0).type()).isEqualTo("number");
        assertThat(r.fields().get(0).typeMismatch()).containsExactly(b.getId());
        List<Double> tx = series(r, "B Kanalı").values().get("total-tx");
        assertThat(tx.get(10)).as("eski ay, aynı tür").isEqualTo(700d);
        assertThat(tx.get(11)).as("güncel ay, farklı tür").isNull();
    }

    @Test
    @DisplayName("inceleme 2026-10-18 (2. tur): rapor servisiyle yazım (değer kaydı, rapor açma / silme) bu pod'un analiz belleğini işlem işlendikten sonra boşaltır — düzeltilen değer ve silinen rapor 60 sn beklemeden görünür; değişmeyen kayıt belleği boşaltmaz")
    void reportWritesClearTheMemo() {
        TechErrorChannel mobil = channel(A, "Mobil");
        TechErrorReport sep = report(mobil, END, vals("total-tx", 10));
        Result first = run(Set.of(A), null, 12, List.of("total-tx"));
        assertThat(series(first, "Mobil").values().get("total-tx").get(11)).isEqualTo(10d);
        assertThat(run(Set.of(A), null, 12, List.of("total-tx"))).isSameAs(first);             // bellekte

        long v = reports.findById(sep.getId()).orElseThrow().getVersion();
        reportService.saveValues(sep.getId(), v, Map.of("fields", Map.of("total-tx", 20)), 7L);
        Result after = run(Set.of(A), null, 12, List.of("total-tx"));
        assertThat(after).isNotSameAs(first);
        assertThat(series(after, "Mobil").values().get("total-tx").get(11)).isEqualTo(20d);

        // Aynı değerle kayıt: yazım yok → bellek kalır
        long v2 = reports.findById(sep.getId()).orElseThrow().getVersion();
        reportService.saveValues(sep.getId(), v2, Map.of("fields", Map.of("total-tx", 20)), 7L);
        assertThat(run(Set.of(A), null, 12, List.of("total-tx"))).isSameAs(after);

        // Yeni rapor açılınca görünür (değeri boş, kimliği dolu)
        TechErrorReport aug = reportService.createReport(channels.findById(mobil.getId()).orElseThrow(), END.minusMonths(1).getYear(),
                END.minusMonths(1).getMonthValue(), 7L);
        Result opened = run(Set.of(A), null, 12, List.of("total-tx"));
        assertThat(series(opened, "Mobil").reports().get(10)).isNotNull();
        assertThat(series(opened, "Mobil").reports().get(10).id()).isEqualTo(aug.getId());

        reportService.deleteReport(sep.getId(), false);
        Result deleted = run(Set.of(A), null, 12, List.of("total-tx"));
        assertThat(series(deleted, "Mobil").values().get("total-tx").get(11)).isNull();
        assertThat(series(deleted, "Mobil").reports().get(11)).isNull();

        // 3. tur: yeni kanal ve değişen kanal sırası da belleği boşaltır (sıra grafikteki ilk 8 kanalı belirler)
        assertThat(run(Set.of(A), null, 12, List.of("total-tx"))).isSameAs(deleted);
        TechErrorChannel yeni = reportService.createChannel(A, "Yeni Kanal", null, TechErrorTemplate.defaults("tr"), 7L);
        Result withNew = run(Set.of(A), null, 12, List.of("total-tx"));
        assertThat(withNew).isNotSameAs(deleted);
        assertThat(withNew.channels()).anyMatch(c -> c.name().equals("Yeni Kanal"));
        reportService.reorderChannels(A, List.of(yeni.getId(), mobil.getId()), 7L);
        assertThat(run(Set.of(A), null, 12, List.of("total-tx"))).isNotSameAs(withNew);
    }

    @Test
    @DisplayName("referans tür eşitlikte kanal sırasındaki İLK tanımlayan: bir sayı + bir yüzde → sayı; yüzde kanalı işaretli")
    void referenceTypeTieTakesFirstChannel() {
        TechErrorChannel a = channel(A, "A Kanalı");                                            // total-tx sayı
        Template pct = withType(TechErrorTemplate.defaults("tr"), "total-tx", "percent");
        TechErrorChannel b = channel(A, "B Kanalı", pct, true);                                 // total-tx yüzde
        report(a, END, vals("total-tx", 10));
        report(b, END, pct, vals("total-tx", 20), null);
        Result r = run(Set.of(A), null, 12, List.of("total-tx"));
        assertThat(r.fields().get(0).type()).isEqualTo("number");
        assertThat(r.fields().get(0).typeMismatch()).containsExactly(b.getId());
        assertThat(series(r, "A Kanalı").values().get("total-tx").get(11)).isEqualTo(10d);
        assertThat(series(r, "B Kanalı").values().get("total-tx").get(11)).isNull();
        var opt = r.available().stream().filter(o -> o.id().equals("total-tx")).findFirst().orElseThrow();
        assertThat(opt.type()).isEqualTo("number");
        assertThat(opt.channels()).isEqualTo(1);
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
    @DisplayName("hesaplanan oran CANLI (rapor ekranı / PDF gibi): saklı değer değil girilen adetlerden; formülsüz alan saklı değeri okur")
    void computedRatioIsLive() {
        TechErrorChannel mobil = channel(A, "Mobil");
        // error-rate = error-count ÷ total-tx = %10 — bayat saklı 0,5 okunmaz
        report(mobil, END, vals("error-count", 100, "total-tx", 1000, "error-rate", 0.5));
        // hesap girdisi var, saklı oran yok → hesaplanır (%5)
        report(mobil, END.minusMonths(1), vals("error-count", 50, "total-tx", 1000));
        // girdi eksik → değer yok (rapor ekranı da boş gösterir); saklı 0,9 okunmaz
        report(mobil, END.minusMonths(2), vals("total-tx", 1000, "error-rate", 0.9));
        // formülü olmayan (elle girilen) oran: saklı değer
        TechErrorChannel pos = channel(A, "POS");
        Template manual = withType(TechErrorTemplate.defaults("tr"), "error-rate", "percent");   // hesap bağı düşer
        report(pos, END, manual, vals("error-count", 100, "total-tx", 1000, "error-rate", 0.5), null);
        Result r = run(Set.of(A), null, 12, List.of("error-rate"));
        List<Double> v = series(r, "Mobil").values().get("error-rate");
        assertThat(v.get(11)).isEqualTo(10d);
        assertThat(v.get(10)).isEqualTo(5d);
        assertThat(v.get(9)).isNull();
        assertThat(series(r, "POS").values().get("error-rate").get(11)).isEqualTo(0.5);
    }

    @Test
    @DisplayName("değişim oranı CANLI: geçen ay pencerenin dışındaysa da okunur; geçen ay düzeltilince değer değişir; sorgu sayısı aynı")
    void changeRateUsesPreviousMonthLive() {
        Statistics stats = emf.unwrap(SessionFactory.class).getStatistics();
        TechErrorChannel mobil = channel(A, "Mobil");
        // Eylül 2025: 12 aylık pencerenin (Ekim 2025 – Eylül 2026) DIŞINDA — yalnız Ekim'in "geçen ay"ı
        TechErrorReport sep25 = report(mobil, END.minusMonths(12), vals("total-tx", 1000));
        report(mobil, END.minusMonths(11), vals("total-tx", 1100, "tx-increase-pct", 99));      // bayat saklı 99 → %10
        report(mobil, END, vals("total-tx", 500, "tx-increase-pct", 7));                          // Ağustos raporu yok → elle: 7
        stats.clear();
        Result r = run(Set.of(A), null, 12, List.of("tx-increase-pct"));
        long statements = stats.getPrepareStatementCount();
        List<Double> v = series(r, "Mobil").values().get("tx-increase-pct");
        assertThat(r.months().get(0)).isEqualTo(END.minusMonths(11));
        assertThat(v.get(0)).isEqualTo(10d);
        assertThat(v.get(11)).isEqualTo(7d);
        assertThat(series(r, "Mobil").reports()).hasSize(12);
        assertThat(statements).isEqualTo(2);
        // Geçen ay düzeltildi: 1000 → 880 → (1100 − 880) ÷ 880 = %25
        correct(sep25, vals("total-tx", 880));
        analytics.invalidate();
        stats.clear();
        Result again = run(Set.of(A), null, 12, List.of("tx-increase-pct"));
        assertThat(series(again, "Mobil").values().get("tx-increase-pct").get(0)).isEqualTo(25d);
        assertThat(stats.getPrepareStatementCount()).isEqualTo(statements);
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
        assertThat(pick.dropped()).containsExactly("yok-boyle-alan");
        assertThat(pick.fieldsFallback()).isFalse();
        // İstenenlerin HİÇBİRİ yok (takım değişti / eski bağlantı): varsayılan alanlar + düşenler, "alan yok" denmez
        Result gone = run(Set.of(A), null, 12, List.of("yok-boyle-alan", "baska-alan"));
        assertThat(gone.fields()).extracting(TechErrorAnalyticsService.FieldRef::id).containsExactly("error-rate", "total-tx");
        assertThat(gone.dropped()).containsExactly("yok-boyle-alan", "baska-alan");
        assertThat(gone.fieldsFallback()).isTrue();
        assertThat(def.dropped()).isEmpty();
        assertThat(def.fieldsFallback()).isFalse();
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
    @DisplayName("DİLİMLİ okuma: dilim 3 satırken 41 rapor 14 dilimde okunur (kanal + 14 = 15 cümle) ve sonuç tek dilimle aynı")
    void chunkedReadSameResult() {
        Statistics stats = emf.unwrap(SessionFactory.class).getStatistics();
        TechErrorChannel firstChannel = null;
        for (int i = 0; i < 8; i++) {
            TechErrorChannel ch = channel(A, "K" + i);
            if (firstChannel == null) firstChannel = ch;
            for (int k = 0; k < 5; k++) report(ch, END.minusMonths(k), vals("total-tx", 100 * i + k + 1, "error-count", k, "error-rate", 9));
        }
        report(firstChannel, END.minusMonths(12), vals("total-tx", 1));                     // pencere öncesi (geçen ay)
        Result whole = run(Set.of(A), null, 12, List.of("total-tx", "error-rate"));
        analytics.invalidate();
        analytics.chunkSize = 3;
        stats.clear();
        Result chunked = run(Set.of(A), null, 12, List.of("total-tx", "error-rate"));
        assertThat(stats.getPrepareStatementCount()).isEqualTo(1 + 14);
        assertThat(chunked.channels()).hasSize(8);
        for (int i = 0; i < 8; i++) {
            assertThat(series(chunked, "K" + i).values()).isEqualTo(series(whole, "K" + i).values());
            assertThat(series(chunked, "K" + i).reports()).isEqualTo(series(whole, "K" + i).reports());
        }
        assertThat(series(chunked, "K3").values().get("total-tx").get(11)).isEqualTo(301d);
        assertThat(series(chunked, "K3").values().get("error-rate").get(10)).isEqualTo(0.33);  // 1 ÷ 302 → %0,33
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
    @DisplayName("Yenile (fresh): kayıt 5 sn'den gençse aynı sonuç; eskiyse yeniden hesaplar ve belleği tazeler")
    void freshBypassesAtMostEveryFiveSeconds() {
        long[] now = {1_000_000L};
        analytics.useClock(() -> now[0]);
        TechErrorChannel mobil = channel(A, "Mobil");
        report(mobil, END, vals("total-tx", 10));
        Result first = run(Set.of(A), null, 12, List.of("total-tx"));
        report(mobil, END.minusMonths(1), vals("total-tx", 20));
        now[0] += 3_000;
        Result early = analytics.analytics(Set.of(A), null, 12, List.of("total-tx"), END, NAMES, true);
        assertThat(early).isSameAs(first);
        now[0] += 3_000;                                                                   // kayıt 6 sn'lik
        Result fresh = analytics.analytics(Set.of(A), null, 12, List.of("total-tx"), END, NAMES, true);
        assertThat(fresh).isNotSameAs(first);
        assertThat(series(fresh, "Mobil").values().get("total-tx").get(10)).isEqualTo(20d);
        assertThat(run(Set.of(A), null, 12, List.of("total-tx"))).isSameAs(fresh);         // bellek tazelendi
        // Takım kümesi anahtarın parçası: aynı takım kümesi (tekrar / sıra farkı) aynı kayıt
        assertThat(analytics.analytics(List.of(A, A), null, 12, List.of("total-tx"), END, NAMES, false)).isSameAs(fresh);
    }

    @Test
    @DisplayName("okuma hatası belleğe alınmaz: ilk çağrı hatayı görür, ikinci çağrı yeniden okur")
    void failureIsNotMemoized() {
        TechErrorChannelRepository ch = mock(TechErrorChannelRepository.class);
        TechErrorReportRepository rp = mock(TechErrorReportRepository.class);
        when(ch.findByTeamIdIn(any())).thenThrow(new IllegalStateException("db yok")).thenReturn(List.of());
        TechErrorAnalyticsService svc = new TechErrorAnalyticsService(ch, rp);
        assertThatThrownBy(() -> svc.analytics(Set.of(A), null, 12, List.of(), END, NAMES, false)).hasMessage("db yok");
        assertThat(svc.analytics(Set.of(A), null, 12, List.of(), END, NAMES, false).channels()).isEmpty();
        verify(ch, times(2)).findByTeamIdIn(any());
    }

    @Test
    @DisplayName("gönderim kuyrukta (hiç gönderilmemiş, QUEUED): queued — 'gönderilmedi' değil; önceden gönderilmiş rapor sent")
    void queuedReportIsMarked() {
        TechErrorChannel mobil = channel(A, "Mobil");
        TechErrorReport q = new TechErrorReport();
        q.setChannelId(mobil.getId());
        q.setTeamId(A);
        q.setReportYear(END.getYear());
        q.setReportMonth(END.getMonthValue());
        q.setTemplateJson(TechErrorTemplate.toJson(TechErrorTemplate.defaults("tr")));
        q.setValuesJson("{\"fields\":{\"total-tx\":10}}");
        q.setSendState("QUEUED");
        q.setCreatedAt(Instant.parse("2026-01-01T00:00:00Z"));
        reports.save(q);
        TechErrorReport resent = report(mobil, END.minusMonths(1), TechErrorTemplate.defaults("tr"), vals("total-tx", 9),
                Instant.parse("2026-09-03T08:00:00Z"));
        Result r = run(Set.of(A), null, 12, List.of("total-tx"));
        var reps = series(r, "Mobil").reports();
        assertThat(reps.get(11).queued()).isTrue();
        assertThat(reps.get(11).sent()).isFalse();
        assertThat(reps.get(10).id()).isEqualTo(resent.getId());
        assertThat(reps.get(10).sent()).isTrue();
        assertThat(reps.get(10).queued()).isFalse();
    }

    @Test
    @DisplayName("etiket yer tutucuları: tek kanallı kapsamda kanal adı, ay / yıl en yeni raporun ayı; çok kanalda ya da raporsuzken düşer")
    void labelPlaceholders() {
        Template t = withLabel(TechErrorTemplate.defaults("tr"), "total-tx", "{kanal} toplam işlem ({ay} {yil})");
        TechErrorChannel mobil = channel(A, "Mobil", t, true);
        report(mobil, END.minusMonths(2), t, vals("total-tx", 5), null);
        Result one = run(Set.of(A), null, 12, List.of("total-tx"));
        assertThat(one.fields().get(0).label()).isEqualTo("Mobil toplam işlem (Temmuz 2026)");
        assertThat(one.available().stream().filter(o -> o.id().equals("total-tx")).findFirst().orElseThrow().label())
                .isEqualTo("Mobil toplam işlem (Temmuz 2026)");
        channel(A, "POS", t, true);
        analytics.invalidate();
        assertThat(run(Set.of(A), null, 12, List.of("total-tx")).fields().get(0).label()).isEqualTo("toplam işlem (Temmuz 2026)");
        reports.deleteAll();
        analytics.invalidate();
        assertThat(run(Set.of(A), null, 12, List.of("total-tx")).fields().get(0).label()).isEqualTo("toplam işlem");
        // Saf yardımcı: EN yer tutucuları, boş kalan etiket → kimlik, uçtaki ayıraç temizlenir
        assertThat(TechErrorAnalyticsService.label("{channel} — Total ({month})", "total-tx", null, null, "en")).isEqualTo("Total");
        assertThat(TechErrorAnalyticsService.label("{kanal}", "total-tx", null, null, "tr")).isEqualTo("total-tx");
        assertThat(TechErrorAnalyticsService.label("Toplam {ay} {yil}", "x", "Mobil", YearMonth.of(2026, 3), "tr")).isEqualTo("Toplam Mart 2026");
        assertThat(TechErrorAnalyticsService.label("Total {month} {year}", "x", null, YearMonth.of(2026, 3), "en")).isEqualTo("Total March 2026");
        assertThat(TechErrorAnalyticsService.label("Sade etiket", "x", null, null, "tr")).isEqualTo("Sade etiket");
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
        var page = org.springframework.data.domain.PageRequest.of(0, 200);
        var rows = reports.findAnalyticsRows(List.of(mobil.getId()), from, to, 0L, page);
        assertThat(rows).extracting(TechErrorReportRepository.AnalyticsRow::getReportMonth).containsExactly(1, 9);   // kimlik sırası
        assertThat(rows.get(0).getTemplateJson()).isNotBlank();
        assertThat(rows.get(0).getValuesJson()).isNotBlank();
        assertThat(reports.findAnalyticsRows(List.of(mobil.getId(), pos.getId()), from, to, 0L, page)).hasSize(3);
        // Anahtar kümesi: id > after; dilim boyu sınırlı
        assertThat(reports.findAnalyticsRows(List.of(mobil.getId(), pos.getId()), from, to, rows.get(0).getId(), page)).hasSize(2);
        assertThat(reports.findAnalyticsRows(List.of(mobil.getId(), pos.getId()), from, to, 0L,
                org.springframework.data.domain.PageRequest.of(0, 1))).hasSize(1);
    }

    // ── Pasif kanallar (2026-10-18, kullanıcı kararı: "pasife alınmış kanalları geçmiş aylarıyla birlikte gösterelim") ─

    /** Şablonun ilk metrik bölümüne bir alan ekler. */
    private static Template withExtra(Template t, String id, String label, String type) {
        List<Section> out = new ArrayList<>();
        boolean added = false;
        for (Section s : t.sections()) {
            if (added || !"metrics".equals(s.kind())) { out.add(s); continue; }
            List<Field> fs = new ArrayList<>(s.fields());
            fs.add(new Field(id, label, type, null));
            out.add(s.withFields(List.copyOf(fs)));
            added = true;
        }
        return new Template(t.lang(), t.title(), List.copyOf(out));
    }

    private TechErrorChannel inactive(long team, String name, Template t) {
        return channel(team, name, t, false);
    }

    private TechErrorChannel inactive(long team, String name) {
        return inactive(team, name, TechErrorTemplate.defaults("tr"));
    }

    @Test
    @DisplayName("pasif kanal penceresinde raporu varsa listelenir (active=false), aktiflerin ARDINDAN aynı sırayla; raporu yalnız pencere dışında (geçen ay dilimi dahil) olan pasif listelenmez")
    void inactiveWithReportsListedAfterActive() {
        TechErrorChannel mobil = channel(A, "Mobil");
        TechErrorChannel arsiv = inactive(A, "Arşiv Kanalı");                    // adı aktiflerden önce gelir — sıra yine sonda
        TechErrorChannel pos = channel(B, "POS");
        TechErrorChannel eskiPos = inactive(B, "Eski POS");
        TechErrorChannel kapali = inactive(A, "Kapalı Kanal");
        TechErrorChannel bos = inactive(A, "Boş Kanal");                         // hiç raporu yok
        report(mobil, END, vals("total-tx", 10));
        report(arsiv, END.minusMonths(11), vals("total-tx", 700));               // pencerenin en eski ayı
        report(arsiv, END.minusMonths(3), vals("total-tx", 750));
        report(eskiPos, END.minusMonths(5), vals("total-tx", 40));
        report(kapali, END.minusMonths(12), vals("total-tx", 5));                // yalnız "geçen ay" dilimi (pencere dışı)
        report(kapali, END.minusMonths(20), vals("total-tx", 6));

        Result r = run(Set.of(A, B), null, 12, List.of("total-tx"));
        assertThat(r.channels()).extracting(ChannelSeries::name).containsExactly("Mobil", "POS", "Arşiv Kanalı", "Eski POS");
        assertThat(r.channels()).extracting(ChannelSeries::active).containsExactly(true, true, false, false);
        assertThat(r.channelCount()).isEqualTo(4);
        assertThat(r.truncated()).isFalse();
        ChannelSeries a = series(r, "Arşiv Kanalı");
        assertThat(a.values().get("total-tx").get(0)).isEqualTo(700d);
        assertThat(a.values().get("total-tx").get(8)).isEqualTo(750d);
        assertThat(a.values().get("total-tx").get(11)).isNull();                // pasife alındıktan sonraki ay boş (0 değil)
        assertThat(a.reports().get(0)).isNotNull();
        assertThat(r.channels()).noneMatch(c -> c.id() == kapali.getId() || c.id() == bos.getId());
        // Aktif kanal raporsuz da listelenir (bugünkü davranış)
        Result onlyB = run(Set.of(A, B), B, 12, List.of("total-tx"));
        assertThat(onlyB.channels()).extracting(ChannelSeries::name).containsExactly("POS", "Eski POS");
        // 6 aylık pencere: Arşiv'in Haziran'ı (−3) ve Eski POS'un Nisan'ı (−5) içeride
        Result six0 = run(Set.of(A, B), null, 6, List.of("total-tx"));
        assertThat(six0.channels()).extracting(ChannelSeries::name).containsExactly("Mobil", "POS", "Arşiv Kanalı", "Eski POS");
        // Raporu yalnız 6 aylık pencerenin hemen öncesinde (−6, geçen ay dilimi) olan pasif 6 ayda yok, 12 ayda var
        reports.deleteAll();
        analytics.invalidate();
        report(arsiv, END.minusMonths(6), vals("total-tx", 1));
        Result six = run(Set.of(A, B), null, 6, List.of("total-tx"));
        assertThat(six.channels()).extracting(ChannelSeries::name).containsExactly("Mobil", "POS");
        Result twelve = run(Set.of(A, B), null, 12, List.of("total-tx"));
        assertThat(twelve.channels()).extracting(ChannelSeries::name).containsExactly("Mobil", "POS", "Arşiv Kanalı");
    }

    @Test
    @DisplayName("referans tür AKTİF kanallardan: iki pasif yüzde, bir aktif sayı → sayı (pasifler tür farkıyla işaretli); aktiflerde olmayan alan listelenen pasiflerin çoğunluğundan; raporsuz pasifin alanı seçilemez")
    void referenceTypePrefersActiveThenListedInactive() {
        Template pct = withType(TechErrorTemplate.defaults("tr"), "total-tx", "percent");
        Template noSla = without(TechErrorTemplate.defaults("tr"), "sla-rate");
        TechErrorChannel mobil = channel(A, "Mobil", noSla, true);                                  // total-tx sayı, sla-rate yok
        TechErrorChannel p1 = inactive(A, "Pasif 1", pct);                                         // total-tx yüzde, sla-rate yüzde
        TechErrorChannel p2 = inactive(A, "Pasif 2", pct);
        TechErrorChannel p3 = inactive(A, "Pasif 3", withExtra(withType(TechErrorTemplate.defaults("tr"), "sla-rate", "number"),
                "archive-count", "Arşiv adedi", "number"));                                        // sla-rate SAYI (azınlık)
        TechErrorChannel kapali = inactive(A, "Kapalı", withExtra(TechErrorTemplate.defaults("tr"), "legacy-count", "Eski adet", "number"));
        report(mobil, END, noSla, vals("total-tx", 10), null);
        report(p1, END.minusMonths(2), pct, vals("total-tx", 20, "sla-rate", 99.9), null);
        report(p2, END.minusMonths(2), pct, vals("total-tx", 30, "sla-rate", 99.5), null);
        report(p3, END.minusMonths(2), vals("archive-count", 3));
        report(kapali, END.minusMonths(14), vals("legacy-count", 1));                               // pencere dışında → listelenmez

        Result r = run(Set.of(A), null, 12, List.of("total-tx", "sla-rate"));
        assertThat(r.channels()).extracting(ChannelSeries::name).containsExactly("Mobil", "Pasif 1", "Pasif 2", "Pasif 3");
        var tx = r.fields().get(0);
        assertThat(tx.type()).as("aktiflerin çoğunluğu (pasifler sayıca fazla olsa da)").isEqualTo("number");
        assertThat(tx.typeMismatch()).containsExactly(p1.getId(), p2.getId());
        assertThat(series(r, "Mobil").values().get("total-tx").get(11)).isEqualTo(10d);
        assertThat(series(r, "Pasif 1").values().get("total-tx").get(9)).as("raporun şablonunda tür farklı").isNull();
        var sla = r.fields().get(1);
        assertThat(sla.type()).as("hiçbir aktif kanal tanımlamıyor → listelenen pasiflerin çoğunluğu").isEqualTo("percent");
        assertThat(sla.typeMismatch()).containsExactly(p3.getId());
        assertThat(series(r, "Pasif 1").values().get("sla-rate").get(9)).isEqualTo(99.9);
        var opts = r.available();
        // Kanal sayısı: referans türle tanımlayan LİSTELENEN kanallar (aktif Mobil + sayı tanımlı Pasif 3)
        assertThat(opts.stream().filter(o -> o.id().equals("total-tx")).findFirst().orElseThrow().channels()).isEqualTo(2);
        assertThat(opts.stream().filter(o -> o.id().equals("sla-rate")).findFirst().orElseThrow().channels()).isEqualTo(2);
        var archive = opts.stream().filter(o -> o.id().equals("archive-count")).findFirst().orElseThrow();
        assertThat(archive.type()).isEqualTo("number");
        assertThat(archive.channels()).isEqualTo(1);
        assertThat(opts).extracting(TechErrorAnalyticsService.FieldOption::id).doesNotContain("legacy-count");
        // Seçilebilir alan sırası: önce aktiflerin alanları, pasiflere özgü alan sonda
        List<String> ids = opts.stream().map(TechErrorAnalyticsService.FieldOption::id).toList();
        assertThat(ids.indexOf("archive-count")).isGreaterThan(ids.indexOf("total-tx"));
        assertThat(ids.indexOf("sla-rate")).isGreaterThan(ids.indexOf("total-tx"));
    }

    @Test
    @DisplayName("SABİT SORGU pasiflerle de: kanal + TEK hafif pasif sorgusu + pencere = 3 cümle — 4 pasifle de 44 pasifle de; takım seçiliyken de; yalnız pasif kanallı kapsam da 3; pasif kanalı olmayan takımlarda 2")
    void inactiveChannelsKeepStatementCount() {
        Statistics stats = emf.unwrap(SessionFactory.class).getStatistics();
        TechErrorChannel mobil = channel(A, "Mobil");
        TechErrorChannel arsiv = inactive(A, "Arşiv");
        inactive(A, "Boş");
        TechErrorChannel odeme = inactive(C, "Ödeme Eski");
        report(mobil, END, vals("total-tx", 1));
        for (int k = 0; k < 6; k++) report(arsiv, END.minusMonths(k + 2), vals("total-tx", k));
        report(odeme, END.minusMonths(1), vals("total-tx", 9));
        stats.clear();
        Result r = run(Set.of(A, C), null, 12, List.of("total-tx", "error-rate"));
        assertThat(stats.getPrepareStatementCount()).isEqualTo(3);
        assertThat(r.channels()).extracting(ChannelSeries::name).containsExactly("Mobil", "Arşiv", "Ödeme Eski");
        // 40 pasif daha (10'unun penceresinde raporu var): cümle sayısı aynı — pasif kanal başına sorgu yok
        for (int i = 0; i < 40; i++) {
            TechErrorChannel p = inactive(i % 2 == 0 ? A : C, String.format("Eski %02d", i));
            if (i % 4 == 0) report(p, END.minusMonths(i % 12), vals("total-tx", i));
        }
        analytics.invalidate();
        stats.clear();
        Result many = run(Set.of(A, C), null, 12, List.of("total-tx", "error-rate"));
        assertThat(stats.getPrepareStatementCount()).isEqualTo(3);
        assertThat(many.channels()).hasSize(13);
        assertThat(many.channels().get(12).name()).isEqualTo("Ödeme Eski");
        analytics.invalidate();
        stats.clear();
        Result filtered = run(Set.of(A, C), A, 12, List.of("total-tx"));
        assertThat(stats.getPrepareStatementCount()).isEqualTo(3);
        assertThat(filtered.channels()).hasSize(12);
        assertThat(filtered.channels()).extracting(ChannelSeries::name).startsWith("Mobil", "Arşiv").doesNotContain("Ödeme Eski");
        analytics.invalidate();
        stats.clear();
        Result onlyInactive = run(Set.of(C), null, 12, List.of());
        assertThat(stats.getPrepareStatementCount()).isEqualTo(3);
        assertThat(onlyInactive.channels()).extracting(ChannelSeries::name).containsExactly("Ödeme Eski");
        assertThat(onlyInactive.fields()).extracting(TechErrorAnalyticsService.FieldRef::id).containsExactly("error-rate", "total-tx");
        assertThat(series(onlyInactive, "Ödeme Eski").values().get("total-tx").get(10)).isEqualTo(9d);
        // Okunabilen takımlarda pasif kanal yoksa hafif sorgu da yok (eski davranış: 2 cümle)
        TechErrorChannel pos = channel(B, "POS");
        report(pos, END, vals("total-tx", 3));
        analytics.invalidate();
        stats.clear();
        run(Set.of(B), null, 12, List.of("total-tx"));
        assertThat(stats.getPrepareStatementCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("tam satırlar yalnız LİSTELENEN kanallar için okunur: takım seçiliyken öteki takımın raporlu pasifi ve kapsamın penceresi dışında raporu olan pasifi pencere okumasına girmez (dilim 1 satır → 3 satır = 4 dilim)")
    void fullRowsReadOnlyForListedChannels() {
        Statistics stats = emf.unwrap(SessionFactory.class).getStatistics();
        TechErrorChannel mobil = channel(A, "Mobil");
        TechErrorChannel arsiv = inactive(A, "Arşiv");
        TechErrorChannel kapali = inactive(A, "Kapalı");
        TechErrorChannel odeme = inactive(C, "Ödeme Eski");
        report(mobil, END, vals("total-tx", 1));
        report(arsiv, END.minusMonths(2), vals("total-tx", 2));
        report(arsiv, END.minusMonths(3), vals("total-tx", 3));
        report(kapali, END.minusMonths(12), vals("total-tx", 4));                // yalnız "geçen ay" dilimi — listelenmez
        for (int k = 0; k < 5; k++) report(odeme, END.minusMonths(k), vals("total-tx", 10 + k));
        analytics.chunkSize = 1;
        stats.clear();
        Result r = run(Set.of(A, C), A, 12, List.of("total-tx"));
        // kanal + hafif pasif sorgusu + 3 dolu dilim (Mobil 1, Arşiv 2) + 1 boş son dilim
        assertThat(stats.getPrepareStatementCount()).isEqualTo(1 + 1 + 4);
        assertThat(r.channels()).extracting(ChannelSeries::name).containsExactly("Mobil", "Arşiv");
        assertThat(r.teamsWithChannels()).containsExactly(A, C);
        assertThat(series(r, "Arşiv").values().get("total-tx").get(9)).isEqualTo(2d);
        assertThat(r.channels()).noneMatch(c -> c.id() == kapali.getId() || c.id() == odeme.getId());
    }

    @Test
    @DisplayName("depo: pasif kanalların istenen dönemdeki EN YENİ rapor dönemi — takım IN listesi, aktif kanal / başka takım / pencere dışı satır vermez, raporsuz pasif yok")
    void repositoryNewestPeriodOfInactiveChannels() {
        TechErrorChannel mobil = channel(A, "Mobil");
        TechErrorChannel arsiv = inactive(A, "Arşiv");
        inactive(A, "Boş");
        TechErrorChannel eski = inactive(B, "Eski");
        report(mobil, YearMonth.of(2026, 9), vals());
        report(arsiv, YearMonth.of(2026, 1), vals());
        report(arsiv, YearMonth.of(2026, 5), vals());
        report(arsiv, YearMonth.of(2026, 10), vals());                          // pencere sonrası
        report(eski, YearMonth.of(2026, 3), vals());
        int from = TechErrorAnalyticsService.period(YearMonth.of(2026, 1));
        int to = TechErrorAnalyticsService.period(YearMonth.of(2026, 9));
        List<Object[]> onlyA = reports.newestPeriodOfInactiveChannels(List.of(A), from, to);
        assertThat(onlyA).hasSize(1);
        assertThat(((Number) onlyA.get(0)[0]).longValue()).isEqualTo(arsiv.getId());
        assertThat(((Number) onlyA.get(0)[1]).intValue()).isEqualTo(TechErrorAnalyticsService.period(YearMonth.of(2026, 5)));
        Map<Long, Integer> both = new HashMap<>();
        for (Object[] row : reports.newestPeriodOfInactiveChannels(List.of(A, B), from, to)) {
            both.put(((Number) row[0]).longValue(), ((Number) row[1]).intValue());
        }
        assertThat(both).containsOnlyKeys(arsiv.getId(), eski.getId());
        assertThat(both.get(eski.getId())).isEqualTo(TechErrorAnalyticsService.period(YearMonth.of(2026, 3)));
        assertThat(reports.newestPeriodOfInactiveChannels(List.of(A),
                TechErrorAnalyticsService.period(YearMonth.of(2026, 6)), to)).isEmpty();
    }

    @Test
    @DisplayName("takım seçici: yalnız raporlu pasif kanalı olan takım listede (takım seçiliyken de); raporu pencerede olmayan pasif kanallı takım listede değil")
    void teamPickerIncludesTeamsWithListedInactiveOnly() {
        channel(A, "Mobil");
        TechErrorChannel eski = inactive(B, "Kurumsal Eski");                    // B: yalnız pasif, raporu pencere dışında
        TechErrorChannel odeme = inactive(C, "Sanal POS Eski");                  // C: yalnız pasif, raporu pencerede
        report(eski, END.minusMonths(15), vals("total-tx", 1));
        report(odeme, END.minusMonths(4), vals("total-tx", 2));
        Result all = run(Set.of(A, B, C), null, 12, List.of("total-tx"));
        assertThat(all.teamsWithChannels()).containsExactly(A, C);
        assertThat(all.channels()).extracting(ChannelSeries::name).containsExactly("Mobil", "Sanal POS Eski");
        Result onlyA = run(Set.of(A, B, C), A, 12, List.of("total-tx"));
        assertThat(onlyA.teamsWithChannels()).as("seçim değişince seçici değişmez").containsExactly(A, C);
        assertThat(onlyA.channels()).extracting(ChannelSeries::name).containsExactly("Mobil");
        Result onlyC = run(Set.of(A, B, C), C, 12, List.of("total-tx"));
        assertThat(onlyC.channels()).extracting(ChannelSeries::name).containsExactly("Sanal POS Eski");
        assertThat(onlyC.channels().get(0).active()).isFalse();
        assertThat(onlyC.teamsWithChannels()).containsExactly(A, C);
        assertThat(run(Set.of(A, B, C), B, 12, List.of("total-tx")).channels()).isEmpty();
    }

    @Test
    @DisplayName("pasifler aktiflerin ay penceresini DARALTMAZ: 50 aktif × 24 ay = 1200 sığar; yalnız en eski ayda raporu olan pasif sığmaz → 24 ay kalır, pasif düşer (channels_truncated; truncated DEĞİL); 49 aktifle aynı pasif listelenir; kısmen sığınca sıranın SONUNDAKİ pasif düşer")
    void inactiveNeverShrinksActiveWindow() {
        List<TechErrorChannel> list = new ArrayList<>();
        for (int i = 0; i < 50; i++) list.add(channel(i < 25 ? A : B, String.format("Kanal %02d", i)));
        report(list.get(0), END.minusMonths(23), vals("total-tx", 1));            // 24 aylık pencerenin en eski ayı
        TechErrorChannel old = inactive(B, "Pasif Eski");
        report(old, END.minusMonths(23), vals("total-tx", 13));                   // yalnız en eski ayda raporu var
        Result r = run(Set.of(A, B), null, 24, List.of("total-tx"));
        assertThat(r.months()).hasSize(24);
        assertThat(r.months().get(0)).isEqualTo(END.minusMonths(23));
        assertThat(r.truncated()).as("aylar kesilmedi").isFalse();
        assertThat(r.channelsTruncated()).isTrue();
        assertThat(r.inactiveOmitted()).isEqualTo(1);
        assertThat(r.channels()).hasSize(50).allMatch(ChannelSeries::active);
        assertThat(r.channelCount()).as("gösterilen 50 + listeye alınmayan 1").isEqualTo(51);
        assertThat(series(r, "Kanal 00").values().get("total-tx").get(0)).isEqualTo(1d);
        assertThat(r.teamsWithChannels()).containsExactly(A, B);

        // 49 aktif: aynı pasif kalan 24 hücreye sığar → listelenir, 24 ay, işaret yok
        channels.delete(list.get(49));
        analytics.invalidate();
        Result fits = run(Set.of(A, B), null, 24, List.of("total-tx"));
        assertThat(fits.months()).hasSize(24);
        assertThat(fits.truncated()).isFalse();
        assertThat(fits.channelsTruncated()).isFalse();
        assertThat(fits.inactiveOmitted()).isZero();
        assertThat(fits.channels()).hasSize(50);
        assertThat(fits.channelCount()).isEqualTo(50);
        ChannelSeries last = fits.channels().get(49);
        assertThat(last.name()).isEqualTo("Pasif Eski");
        assertThat(last.active()).isFalse();
        assertThat(last.values().get("total-tx").get(0)).isEqualTo(13d);

        // 48 aktif: 1200 − 48 × 24 = 48 hücre → 2 pasif; 3 raporlu pasif → sıranın SONUNDAKİ (Pasif Eski) düşer
        channels.delete(list.get(48));
        TechErrorChannel p1 = inactive(A, "Pasif 1");
        TechErrorChannel p2 = inactive(B, "Pasif 2");
        report(p1, END, vals("total-tx", 11));
        report(p2, END.minusMonths(1), vals("total-tx", 12));
        analytics.invalidate();
        Result partial = run(Set.of(A, B), null, 24, List.of("total-tx"));
        assertThat(partial.months()).hasSize(24);
        assertThat(partial.truncated()).isFalse();
        assertThat(partial.channelsTruncated()).isTrue();
        assertThat(partial.inactiveOmitted()).isEqualTo(1);
        assertThat(partial.channels()).hasSize(50);
        assertThat(partial.channels().subList(48, 50)).extracting(ChannelSeries::name).containsExactly("Pasif 1", "Pasif 2");
        assertThat(partial.channels()).noneMatch(c -> c.id() == old.getId());
        assertThat(partial.channelCount()).isEqualTo(51);
        assertThat(series(partial, "Pasif 1").values().get("total-tx").get(23)).isEqualTo(11d);
        // Daha kısa dönem: hepsi sığar
        Result twelve = run(Set.of(A, B), null, 12, List.of("total-tx"));
        assertThat(twelve.channelsTruncated()).isFalse();
        assertThat(twelve.channels()).extracting(ChannelSeries::name).contains("Pasif 1", "Pasif 2").doesNotContain("Pasif Eski");
    }

    @Test
    @DisplayName("aktifler ayları kestiğinde (51 × 24 > 1200 → 23 ay; truncated) pasif kalan hücreye sığarsa listelenir; sığmayan pasif ayrıca işaretlenir; raporu kesilen ayda kalan pasif sayılmaz ama takımı seçicide")
    void activeTruncationThenInactiveFill() {
        for (int i = 0; i < 51; i++) channel(i < 26 ? A : B, String.format("Kanal %02d", i));
        TechErrorChannel p1 = inactive(A, "Pasif 1");
        TechErrorChannel p2 = inactive(B, "Pasif 2");
        TechErrorChannel cut = inactive(C, "Ödeme Eski");
        report(p1, END, vals("total-tx", 11));
        report(p2, END.minusMonths(1), vals("total-tx", 12));
        report(cut, END.minusMonths(23), vals("total-tx", 13));                   // 24 ayın en eski ayı — 23 ayda dışarıda
        Result r = run(Set.of(A, B, C), null, 24, List.of("total-tx"));
        assertThat(r.months()).hasSize(23);                                       // 1200 / 51
        assertThat(r.truncated()).isTrue();
        // 1200 − 51 × 23 = 27 hücre → 1 pasif
        assertThat(r.channels()).hasSize(52);
        assertThat(r.channels().get(51).name()).isEqualTo("Pasif 1");
        assertThat(r.channelsTruncated()).isTrue();
        assertThat(r.inactiveOmitted()).isEqualTo(1);
        assertThat(r.channelCount()).isEqualTo(53);
        assertThat(r.teamsWithChannels()).containsExactly(A, B, C);
    }

    @Test
    @DisplayName("takım seçici seçimle DEĞİŞMEZ, aktif pencere kesildiğinde de: team=all / A / B / C aynı liste (İSTENEN pencereye bakar); seçilen pasif-yalnız takım kanalını tam pencereyle gösterir")
    void teamPickerIdenticalForEverySelection() {
        for (int i = 0; i < 51; i++) channel(A, String.format("Kanal %02d", i));
        channel(B, "POS");
        TechErrorChannel sanal = inactive(C, "Sanal POS Eski");
        report(sanal, END.minusMonths(23), vals("total-tx", 7));                  // yalnız 24 aylık pencerenin en eski ayı
        TechErrorChannel eski = inactive(B, "Kurumsal Eski");
        report(eski, END.minusMonths(30), vals("total-tx", 1));                   // istenen pencerenin dışında
        Result all = run(Set.of(A, B, C), null, 24, List.of("total-tx"));
        assertThat(all.truncated()).isTrue();                                     // 52 aktif → 23 ay
        assertThat(all.channels()).noneMatch(c -> c.id() == sanal.getId());       // raporu gösterilen aylarda yok
        assertThat(all.teamsWithChannels()).containsExactly(A, B, C);
        for (Long team : new Long[] {A, B, C}) {
            assertThat(run(Set.of(A, B, C), team, 24, List.of("total-tx")).teamsWithChannels())
                    .as("takım %s seçiliyken", team).isEqualTo(all.teamsWithChannels());
        }
        Result onlyC = run(Set.of(A, B, C), C, 24, List.of("total-tx"));
        assertThat(onlyC.months()).hasSize(24);
        assertThat(onlyC.channels()).extracting(ChannelSeries::name).containsExactly("Sanal POS Eski");
        assertThat(series(onlyC, "Sanal POS Eski").values().get("total-tx").get(0)).isEqualTo(7d);
        // 12 ay: C'nin raporu istenen pencerenin dışında → hiçbir seçimde C yok
        List<Long> twelve = run(Set.of(A, B, C), null, 12, List.of("total-tx")).teamsWithChannels();
        assertThat(twelve).containsExactly(A, B);
        for (Long team : new Long[] {A, B}) {
            assertThat(run(Set.of(A, B, C), team, 12, List.of("total-tx")).teamsWithChannels()).isEqualTo(twelve);
        }
    }

    @Test
    @DisplayName("kanal pasife alınınca / yeniden açılınca (updateChannel) analiz belleği boşalır: pasif kanal raporlarıyla rozetli, raporsuz pasif listeden düşer")
    void deactivationClearsTheMemo() {
        TechErrorChannel mobil = reportService.createChannel(A, "Mobil", null, TechErrorTemplate.defaults("tr"), 7L);
        TechErrorChannel pos = reportService.createChannel(A, "POS", null, TechErrorTemplate.defaults("tr"), 7L);
        report(mobil, END, vals("total-tx", 10));
        Result first = run(Set.of(A), null, 12, List.of("total-tx"));
        assertThat(first.channels()).extracting(ChannelSeries::active).containsExactly(true, true);
        assertThat(run(Set.of(A), null, 12, List.of("total-tx"))).isSameAs(first);
        reportService.updateChannel(mobil.getId(), Map.of("active", false), 7L);
        Result afterMobil = run(Set.of(A), null, 12, List.of("total-tx"));
        assertThat(afterMobil).isNotSameAs(first);
        assertThat(afterMobil.channels()).extracting(ChannelSeries::name).containsExactly("POS", "Mobil");
        assertThat(afterMobil.channels()).extracting(ChannelSeries::active).containsExactly(true, false);
        reportService.updateChannel(pos.getId(), Map.of("active", false), 7L);
        Result afterPos = run(Set.of(A), null, 12, List.of("total-tx"));
        assertThat(afterPos.channels()).extracting(ChannelSeries::name).containsExactly("Mobil");   // POS'un raporu yok
        reportService.updateChannel(mobil.getId(), Map.of("active", true), 7L);
        assertThat(run(Set.of(A), null, 12, List.of("total-tx")).channels()).extracting(ChannelSeries::active).containsExactly(true);
    }
}
