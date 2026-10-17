package com.sitemonitor.service.techerror;

import com.sitemonitor.model.TechErrorChannel;
import com.sitemonitor.model.TechErrorReport;
import com.sitemonitor.repository.TechErrorChannelRepository;
import com.sitemonitor.repository.TechErrorReportExportRepository;
import com.sitemonitor.repository.TechErrorReportRepository;
import com.sitemonitor.service.techerror.TechErrorTemplate.Calc;
import com.sitemonitor.service.techerror.TechErrorTemplate.Field;
import com.sitemonitor.service.techerror.TechErrorTemplate.Section;
import com.sitemonitor.service.techerror.TechErrorTemplate.Template;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Limit;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Teknik hata raporu DEĞER dışa aktarımı (E23, 2026-10-18) — H2'de GERÇEK SQL ({@code NOT_SUPPORTED}: uygulamadaki gibi
 * işlemsiz çağıran). Her rapor KENDİ kopyalanmış şablonuyla (aynı kimlik + tür), muaf / hesaplanan bayrakları, durum
 * süzgeci ve "gönderimden sonra değişti", onay durumu yalnız onay akışlı kanalda, kişi verisi yok, tek sorgu + tavan.
 */
@DataJpaTest
@Import(TechErrorExportService.class)
@TestPropertySource(properties = {"spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.generate_statistics=true"})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class TechErrorExportServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-18T09:00:00Z");
    private static final int COL_STATUS = TechErrorExportService.COLUMNS.indexOf("report_status");
    private static final int COL_FIELD = TechErrorExportService.COLUMNS.indexOf("field_id");
    private static final int COL_LABEL = TechErrorExportService.COLUMNS.indexOf("field_label");
    private static final int COL_TYPE = TechErrorExportService.COLUMNS.indexOf("field_type");
    private static final int COL_VALUE = TechErrorExportService.COLUMNS.indexOf("value");
    private static final int COL_EXEMPT = TechErrorExportService.COLUMNS.indexOf("exempt");
    private static final int COL_COMPUTED = TechErrorExportService.COLUMNS.indexOf("computed");
    private static final int COL_CHANGED = TechErrorExportService.COLUMNS.indexOf("changed_after_send");
    private static final int COL_APPROVAL = TechErrorExportService.COLUMNS.indexOf("approval_state");
    private static final int COL_MONTH = TechErrorExportService.COLUMNS.indexOf("month");
    private static final int COL_SENT_AT = TechErrorExportService.COLUMNS.indexOf("sent_at");

    @Autowired TechErrorExportService exporter;
    @Autowired TechErrorChannelRepository channels;
    @Autowired TechErrorReportRepository reports;
    @Autowired jakarta.persistence.EntityManagerFactory emf;

    @BeforeEach
    void clean() {
        reports.deleteAll();
        channels.deleteAll();
    }

    /** {kanal} / {ay} çözülsün diye etiketlerde yer tutucu; uzun metin ve görsel alanı dışa aktarılmaz. */
    private static Template template(List<Field> fields) {
        List<Field> all = new ArrayList<>(fields);
        all.add(new Field("note", "Açıklama", "textarea", null));
        return new Template("tr", "{kanal} — {ay} {yil}", List.of(
                new Section("figures", "metrics", "Veriler", all, List.of(), null, null, null, null, null),
                new Section("shots", "images", "Görseller", List.of(),
                        List.of(new TechErrorTemplate.Slot("shot-1", "SLA", "full")), null, null, null, null, null)));
    }

    private static final List<Field> DEFAULT_FIELDS = List.of(
            new Field("total-tx", "{kanal} toplam işlem ({ay})", "number", null),
            new Field("error-count", "Teknik hata adedi", "number", null),
            new Field("had-outage", "Kesinti oldu mu?", "yesno", null),
            new Field("outage-errors", "Kesinti kaynaklı hata", "number", null, "had-outage"),
            new Field("error-rate", "Teknik hata oranı", "percent", null, null, Calc.ratio("error-count", null, "total-tx")),
            new Field("comment", "Kısa not", "text", null));

    private TechErrorChannel channel(long team, String name, String executives) {
        TechErrorChannel c = new TechErrorChannel();
        c.setTeamId(team);
        c.setName(name);
        c.setNameKey(name.toLowerCase(java.util.Locale.forLanguageTag("tr")));
        c.setExecutiveEmails(executives);
        c.setTemplateJson(TechErrorTemplate.toJson(template(DEFAULT_FIELDS)));
        return channels.save(c);
    }

    private TechErrorReport report(TechErrorChannel ch, int year, int month, Template t, Map<String, Object> fields) {
        TechErrorReport r = new TechErrorReport();
        r.setChannelId(ch.getId());
        r.setTeamId(ch.getTeamId());
        r.setReportYear(year);
        r.setReportMonth(month);
        r.setTemplateJson(TechErrorTemplate.toJson(t));
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("fields", fields);
        r.setValuesJson(TechErrorTemplate.JSON.writeValueAsString(raw));
        r.setUpdatedById(7L);
        r.setCreatedById(7L);
        return reports.save(r);
    }

    private void markSent(TechErrorReport r, Instant at, String to) {
        assertThat(reports.claimSend(r.getId(), at, at.minusSeconds(600))).isEqualTo(1);
        assertThat(reports.finishSent(r.getId(), at, "SENT", at, 7L, to, 0L)).isEqualTo(1);
    }

    private static Map<String, Object> values(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    private List<List<Object>> rowsOf(TechErrorExportService.Result res, long reportId) {
        int col = TechErrorExportService.COLUMNS.indexOf("report_id");
        return res.rows().stream().filter(r -> Long.valueOf(reportId).equals(r.get(col))).toList();
    }

    private static List<Object> field(List<List<Object>> rows, String id) {
        return rows.stream().filter(r -> id.equals(r.get(COL_FIELD))).findFirst().orElse(null);
    }

    @Test
    @DisplayName("her rapor KENDİ kopyalanmış şablonuyla: aynı kimlik farklı türde o ayın türüyle; kanalın bugünkü şablonunda olan ama raporda olmayan alan satır üretmez; etiket o ayla çözülür; uzun metin / görsel yok")
    void ownTemplateSameIdAndType() {
        TechErrorChannel ch = channel(1, "Mobil", null);
        // Ağustos: "error-rate" o ay SAYI alanıydı (sonradan yüzdeye çevrildi), "comment" yoktu
        Template aug = template(List.of(new Field("total-tx", "{kanal} toplam işlem ({ay})", "number", null),
                new Field("error-rate", "Hata oranı (eski)", "number", null)));
        TechErrorReport a = report(ch, 2026, 8, aug, values("total-tx", 1000, "error-rate", 12, "note", "uzun açıklama"));
        TechErrorReport s = report(ch, 2026, 9, template(DEFAULT_FIELDS),
                values("total-tx", 2000, "error-count", 30, "had-outage", true, "outage-errors", 4, "error-rate", 1.5,
                        "comment", "=HYPERLINK(\"x\")", "note", "uzun"));

        TechErrorExportService.Result res = exporter.export(List.of(ch), Map.of(1L, "Takım A"),
                new TechErrorExportService.Filter(2026, null, false), NOW);

        List<List<Object>> augRows = rowsOf(res, a.getId());
        assertThat(augRows).extracting(r -> r.get(COL_FIELD)).containsExactly("total-tx", "error-rate");
        assertThat(field(augRows, "error-rate").get(COL_TYPE)).isEqualTo("number");
        assertThat(field(augRows, "error-rate").get(COL_LABEL)).isEqualTo("Hata oranı (eski)");
        assertThat(field(augRows, "error-rate").get(COL_VALUE)).isEqualTo(new BigDecimal("12"));
        assertThat(field(augRows, "error-rate").get(COL_COMPUTED)).isEqualTo(false);
        assertThat(field(augRows, "total-tx").get(COL_LABEL)).isEqualTo("Mobil toplam işlem (Ağustos)");

        List<List<Object>> sepRows = rowsOf(res, s.getId());
        assertThat(sepRows).extracting(r -> r.get(COL_FIELD))
                .containsExactly("total-tx", "error-count", "had-outage", "outage-errors", "error-rate", "comment");
        assertThat(field(sepRows, "error-rate").get(COL_TYPE)).isEqualTo("percent");
        assertThat(field(sepRows, "error-rate").get(COL_VALUE)).isEqualTo(new BigDecimal("1.5"));
        assertThat(field(sepRows, "total-tx").get(COL_LABEL)).isEqualTo("Mobil toplam işlem (Eylül)");
        assertThat(field(sepRows, "had-outage").get(COL_VALUE)).isEqualTo(true);
        assertThat(field(sepRows, "comment").get(COL_VALUE)).isEqualTo("=HYPERLINK(\"x\")");     // ham; CSV nötrler
        assertThat(res.rows()).noneMatch(r -> "note".equals(r.get(COL_FIELD)));
        assertThat(res.rows()).allSatisfy(r -> assertThat(r).hasSize(TechErrorExportService.COLUMNS.size()));
        assertThat(res.reports()).extracting(TechErrorExportService.ReportMeta::fieldCount).containsExactly(2, 6);
        // Sıra: dönem (eskiden yeniye)
        assertThat(res.reports()).extracting(TechErrorExportService.ReportMeta::month).containsExactly(8, 9);
    }

    @Test
    @DisplayName("muaf alan (bağlı soru Hayır): değer boş + exempt (saklı eski değer sızmaz); hesaplanan oran PDF gibi CANLI hesaplanır (saklı 1,475 değil 59 / 4000 → 1,47) + computed; boş değer null")
    void exemptAndComputedFlags() {
        TechErrorChannel ch = channel(1, "POS", null);
        TechErrorReport r = report(ch, 2026, 9, template(DEFAULT_FIELDS),
                values("total-tx", 4000, "error-count", 59, "had-outage", false, "outage-errors", 12, "error-rate", 1.475));
        List<List<Object>> rows = exporter.export(List.of(ch), Map.of(1L, "Takım A"),
                new TechErrorExportService.Filter(2026, 9, false), NOW).rows();
        List<Object> outage = field(rows, "outage-errors");
        assertThat(outage.get(COL_EXEMPT)).isEqualTo(true);
        assertThat(outage.get(COL_VALUE)).isNull();
        List<Object> rate = field(rows, "error-rate");
        assertThat(rate.get(COL_COMPUTED)).isEqualTo(true);
        assertThat(rate.get(COL_EXEMPT)).isEqualTo(false);
        assertThat(rate.get(COL_VALUE)).isEqualTo(new BigDecimal("1.47"));           // canlı, 2 hane (PDF ile aynı)
        assertThat(field(rows, "total-tx").get(COL_COMPUTED)).isEqualTo(false);
        assertThat(field(rows, "comment").get(COL_VALUE)).isNull();
        assertThat(field(rows, "comment").get(COL_EXEMPT)).isEqualTo(false);
        assertThat(r.getId()).isNotNull();
    }

    @Test
    @DisplayName("durum süzgeci: sentOnly yalnız gönderilmişler; gönderimden sonra değişen 'changed' + changed_after_send; taslak yalnız all'da; dönem aralığı (yıl / ay) uygulanır")
    void statusFilterAndChangedAfterSend() {
        TechErrorChannel ch = channel(1, "Mobil", null);
        TechErrorReport draft = report(ch, 2026, 7, template(DEFAULT_FIELDS), values("total-tx", 1));
        TechErrorReport sent = report(ch, 2026, 8, template(DEFAULT_FIELDS), values("total-tx", 2));
        TechErrorReport changed = report(ch, 2026, 9, template(DEFAULT_FIELDS), values("total-tx", 3));
        TechErrorReport lastYear = report(ch, 2025, 12, template(DEFAULT_FIELDS), values("total-tx", 4));
        Instant at = Instant.parse("2026-10-02T08:00:00Z");
        markSent(sent, at, "takim@example.com");
        markSent(changed, at, "takim@example.com");
        markSent(lastYear, at, "takim@example.com");
        reports.touchContent(changed.getId(), at.plusSeconds(60));

        TechErrorExportService.Result onlySent = exporter.export(List.of(ch), Map.of(1L, "Takım A"),
                new TechErrorExportService.Filter(2026, null, true), NOW);
        assertThat(onlySent.reports()).extracting(TechErrorExportService.ReportMeta::reportId)
                .containsExactly(sent.getId(), changed.getId());
        assertThat(onlySent.reports()).extracting(TechErrorExportService.ReportMeta::status).containsExactly("sent", "changed");
        assertThat(onlySent.reports()).extracting(TechErrorExportService.ReportMeta::changedAfterSend).containsExactly(false, true);
        assertThat(field(rowsOf(onlySent, changed.getId()), "total-tx").get(COL_CHANGED)).isEqualTo(true);
        assertThat(field(rowsOf(onlySent, sent.getId()), "total-tx").get(COL_SENT_AT)).isEqualTo(at.toString());

        TechErrorExportService.Result all = exporter.export(List.of(ch), Map.of(1L, "Takım A"),
                new TechErrorExportService.Filter(2026, null, false), NOW);
        assertThat(all.reports()).extracting(TechErrorExportService.ReportMeta::reportId)
                .containsExactly(draft.getId(), sent.getId(), changed.getId());
        assertThat(all.reports().get(0).status()).isEqualTo("draft");
        assertThat(all.reports().get(0).sentAt()).isNull();

        TechErrorExportService.Result sept = exporter.export(List.of(ch), Map.of(1L, "Takım A"),
                new TechErrorExportService.Filter(2026, 9, false), NOW);
        assertThat(sept.reports()).extracting(TechErrorExportService.ReportMeta::reportId).containsExactly(changed.getId());
        assertThat(sept.rows()).allSatisfy(r -> assertThat(r.get(COL_MONTH)).isEqualTo(9));

        // Tek kanalın tüm yılları (yıl yok)
        TechErrorExportService.Result years = exporter.export(List.of(ch), Map.of(1L, "Takım A"),
                new TechErrorExportService.Filter(null, null, true), NOW);
        assertThat(years.reports()).extracting(TechErrorExportService.ReportMeta::reportId)
                .containsExactly(lastYear.getId(), sent.getId(), changed.getId());
    }

    @Test
    @DisplayName("onay durumu yalnız onay akışlı kanalda (yönetici alıcısı tanımlı): none / pending / approved / stale — liste bloğuyla aynı kural; akışsız kanalda null")
    void approvalStateOnlyWithFlow() {
        TechErrorChannel flow = channel(1, "Mobil", "ceo@example.com");
        TechErrorChannel plain = channel(1, "POS", null);
        TechErrorReport none = report(flow, 2026, 6, template(DEFAULT_FIELDS), values("total-tx", 1));
        TechErrorReport pending = report(flow, 2026, 7, template(DEFAULT_FIELDS), values("total-tx", 1));
        TechErrorReport approved = report(flow, 2026, 8, template(DEFAULT_FIELDS), values("total-tx", 1));
        TechErrorReport stale = report(flow, 2026, 9, template(DEFAULT_FIELDS), values("total-tx", 1));
        TechErrorReport noFlow = report(plain, 2026, 9, template(DEFAULT_FIELDS), values("total-tx", 1));
        Instant at = Instant.parse("2026-10-03T08:00:00Z");
        for (TechErrorReport r : List.of(pending, approved, stale)) {
            assertThat(reports.requestApproval(r.getId(), 0L, at, 7L, at.minusSeconds(600))).isEqualTo(1);
        }
        assertThat(reports.approve(approved.getId(), 0L, at.plusSeconds(60), 8L, "gizli onay notu")).isEqualTo(1);
        assertThat(reports.approve(stale.getId(), 0L, at.plusSeconds(60), 8L, "gizli onay notu")).isEqualTo(1);
        reports.touchContent(stale.getId(), at.plusSeconds(120));

        TechErrorExportService.Result res = exporter.export(List.of(flow, plain), Map.of(1L, "Takım A"),
                new TechErrorExportService.Filter(2026, null, false), NOW);
        Map<Long, String> byReport = new LinkedHashMap<>();
        for (TechErrorExportService.ReportMeta m : res.reports()) byReport.put(m.reportId(), m.approvalState());
        assertThat(byReport.get(none.getId())).isEqualTo("none");
        assertThat(byReport.get(pending.getId())).isEqualTo("pending");
        assertThat(byReport.get(approved.getId())).isEqualTo("approved");
        assertThat(byReport.get(stale.getId())).isEqualTo("stale");
        assertThat(byReport).containsEntry(noFlow.getId(), null);
        assertThat(field(rowsOf(res, approved.getId()), "total-tx").get(COL_APPROVAL)).isEqualTo("approved");
        // Kanal sırası verilen listeyle (akışlı kanal önce)
        assertThat(res.reports().get(res.reports().size() - 1).reportId()).isEqualTo(noFlow.getId());
    }

    @Test
    @DisplayName("onay durumu kuralı rapor listesinin onay bloğuyla AYNI (reddedilen eskimez; sayaçsız satır 0)")
    void approvalRuleMatchesListBlock() {
        assertThat(TechErrorExportService.approvalState(null, null, 3L)).isEqualTo("none");
        assertThat(TechErrorExportService.approvalState("PENDING", 2L, 2L)).isEqualTo("pending");
        assertThat(TechErrorExportService.approvalState("PENDING", 2L, 3L)).isEqualTo("stale");
        assertThat(TechErrorExportService.approvalState("APPROVED", 0L, null)).isEqualTo("approved");
        assertThat(TechErrorExportService.approvalState("REJECTED", 1L, 5L)).isEqualTo("rejected");
    }

    @Test
    @DisplayName("kişi verisi YOK: alıcı adresi, onay notu, kişi kimlikleri ne izdüşümde ne satırda; izdüşüm yalnız durum / şablon / değer sütunları")
    void noPeopleData() {
        TechErrorChannel ch = channel(1, "Mobil", "ceo@example.com");
        TechErrorReport r = report(ch, 2026, 9, template(DEFAULT_FIELDS), values("total-tx", 10));
        Instant at = Instant.parse("2026-10-02T08:00:00Z");
        markSent(r, at, "gizli.alici@example.com");
        reports.requestApproval(r.getId(), 0L, at, 7L, at.minusSeconds(600));
        reports.approve(r.getId(), 0L, at, 8L, "gizli onay notu");
        TechErrorExportService.Result res = exporter.export(List.of(ch), Map.of(1L, "Takım A"),
                new TechErrorExportService.Filter(2026, 9, true), NOW);
        String dump = res.rows().toString() + res.reports().toString();
        assertThat(dump).doesNotContain("gizli.alici@example.com", "gizli onay notu", "ceo@example.com");
        for (Method m : TechErrorReportExportRepository.ExportRow.class.getMethods()) {
            assertThat(m.getName()).as("izdüşümde kişi sütunu").doesNotContain("By", "SentTo", "Note", "Queued");
        }
        assertThat(TechErrorExportService.COLUMNS).noneMatch(c -> c.contains("_by") || c.contains("recipient") || c.contains("_to"));
    }

    @Test
    @DisplayName("sabit iki sorgu + tavan: önce SAYIM (JSON okunmaz) — tavanı aşan süzgeç yükleme yapmadan TooLargeException; sayım 0 ise yükleme yok;"
            + " yükleme bir ay geriden (dönem = yıl × 12 + ay) ve en çok iki kat; sayım ile yükleme arasında aşan tavan da reddedilir; kanal yoksa sorgu yok")
    void countFirstThenSingleLoad() {
        TechErrorReportExportRepository repo = mock(TechErrorReportExportRepository.class);
        TechErrorExportService svc = new TechErrorExportService(repo);
        assertThat(svc.export(List.of(), Map.of(), new TechErrorExportService.Filter(2026, null, true), NOW).rows()).isEmpty();
        verify(repo, never()).countForExport(any(), anyInt(), anyInt(), anyBoolean());
        verify(repo, never()).findForExport(any(), anyInt(), anyInt(), anyInt(), anyBoolean(), any());

        TechErrorChannel ch = new TechErrorChannel();
        ch.setId(10L);
        ch.setTeamId(1L);
        ch.setName("Mobil");
        int sep2026 = 2026 * 12 + 9;
        // Tavanı aşan sayım → yükleme YOK
        when(repo.countForExport(any(), anyInt(), anyInt(), anyBoolean())).thenReturn((long) TechErrorExportService.MAX_REPORTS + 1);
        assertThatThrownBy(() -> svc.export(List.of(ch), Map.of(1L, "Takım A"), new TechErrorExportService.Filter(2026, 9, false), NOW))
                .isInstanceOf(TechErrorExportService.TooLargeException.class);
        verify(repo).countForExport(eq(java.util.Set.of(10L)), eq(sep2026), eq(sep2026), eq(false));
        verify(repo, never()).findForExport(any(), anyInt(), anyInt(), anyInt(), anyBoolean(), any());
        // Sayım 0 → yükleme yok
        when(repo.countForExport(any(), anyInt(), anyInt(), anyBoolean())).thenReturn(0L);
        assertThat(svc.export(List.of(ch), Map.of(), new TechErrorExportService.Filter(2026, 9, true), NOW).reports()).isEmpty();
        verify(repo, never()).findForExport(any(), anyInt(), anyInt(), anyInt(), anyBoolean(), any());
        // Yükleme güvenlik tavanı (2 × MAX) aşılırsa reddedilir
        when(repo.countForExport(any(), anyInt(), anyInt(), anyBoolean())).thenReturn(3L);
        List<TechErrorReportExportRepository.ExportRow> many = new ArrayList<>();
        for (int i = 0; i <= 2 * TechErrorExportService.MAX_REPORTS; i++) many.add(mock(TechErrorReportExportRepository.ExportRow.class));
        when(repo.findForExport(any(), anyInt(), anyInt(), anyInt(), anyBoolean(), any())).thenReturn(many);
        assertThatThrownBy(() -> svc.export(List.of(ch), Map.of(1L, "Takım A"), new TechErrorExportService.Filter(2026, 9, false), NOW))
                .isInstanceOf(TechErrorExportService.TooLargeException.class);
        verify(repo, times(1)).findForExport(eq(java.util.Set.of(10L)), eq(sep2026 - 1), eq(sep2026), eq(sep2026), eq(false),
                eq(Limit.of(2 * TechErrorExportService.MAX_REPORTS + 1)));
        // Sayımdan sonra açılan raporlar dışa aktarılanları tavanın üstüne çıkarırsa da reddedilir
        List<TechErrorReportExportRepository.ExportRow> grown = new ArrayList<>();
        for (int i = 0; i <= TechErrorExportService.MAX_REPORTS; i++) {
            TechErrorReportExportRepository.ExportRow r = mock(TechErrorReportExportRepository.ExportRow.class);
            when(r.getId()).thenReturn((long) i + 1);
            when(r.getChannelId()).thenReturn(10L);
            when(r.getReportYear()).thenReturn(2026);
            when(r.getReportMonth()).thenReturn(9);
            grown.add(r);
        }
        when(repo.findForExport(any(), anyInt(), anyInt(), anyInt(), anyBoolean(), any())).thenReturn(grown);
        assertThatThrownBy(() -> svc.export(List.of(ch), Map.of(1L, "Takım A"), new TechErrorExportService.Filter(2026, 9, false), NOW))
                .isInstanceOf(TechErrorExportService.TooLargeException.class);
        // Yıl / ay yok → geniş aralık (boş-olabilir parametre yok); yılın Ocak'ı önceki yılın Aralık'ından bir sonra
        when(repo.findForExport(any(), anyInt(), anyInt(), anyInt(), anyBoolean(), any())).thenReturn(List.of());
        svc.export(List.of(ch), Map.of(), new TechErrorExportService.Filter(null, null, true), NOW);
        verify(repo).countForExport(any(), eq(1), eq(9999 * 12 + 12), eq(true));
        verify(repo).findForExport(any(), eq(0), eq(1), eq(9999 * 12 + 12), eq(true), any());
        svc.export(List.of(ch), Map.of(), new TechErrorExportService.Filter(2026, null, true), NOW);
        verify(repo).findForExport(any(), eq(2025 * 12 + 12), eq(2026 * 12 + 1), eq(2026 * 12 + 12), eq(true), any());
    }

    @Test
    @DisplayName("sabit sorgu sayısı (H2, Hibernate istatistiği): kanal ve rapor sayısından bağımsız İKİ deyim (sayım + yükleme) — önceki ay okuması ayrı sorgu değil")
    void fixedStatementCount() {
        List<TechErrorChannel> chs = new ArrayList<>();
        for (int c = 0; c < 3; c++) {
            TechErrorChannel ch = channel(1, "Kanal " + c, c == 0 ? "ceo@example.com" : null);
            chs.add(ch);
            for (int m = 6; m <= 9; m++) report(ch, 2026, m, template(CHANGE_FIELDS), values("total-tx", 1000 + m * 10, "error-count", m));
        }
        org.hibernate.stat.Statistics stats = emf.unwrap(org.hibernate.SessionFactory.class).getStatistics();
        stats.clear();
        TechErrorExportService.Result res = exporter.export(chs, Map.of(1L, "Takım A"),
                new TechErrorExportService.Filter(2026, null, false), NOW);
        assertThat(res.reports()).hasSize(12);
        assertThat(stats.getPrepareStatementCount()).isEqualTo(2);
        stats.clear();
        exporter.export(chs.subList(0, 1), Map.of(1L, "Takım A"), new TechErrorExportService.Filter(2026, 9, true), NOW);
        assertThat(stats.getPrepareStatementCount()).isEqualTo(1);              // sayım 0 (gönderilmiş yok) → yükleme yok
    }

    /** Önceki aya göre değişim / artış: yüzde ve evet/hayır alanları geçen ayın "total-tx"ine göre. */
    private static final List<Field> CHANGE_FIELDS = List.of(
            new Field("total-tx", "Toplam işlem", "number", null),
            new Field("error-count", "Teknik hata adedi", "number", null),
            new Field("error-rate", "Teknik hata oranı", "percent", null, null, Calc.ratio("error-count", null, "total-tx")),
            new Field("tx-increase-pct", "İşlem değişimi", "percent", null, null, Calc.change("total-tx")),
            new Field("tx-increase", "İşlem arttı mı?", "yesno", null, null, Calc.increase("total-tx")));

    @Test
    @DisplayName("canlı hesap PDF gibi: kanalın İLK raporunda değişim / artış formülü kullanılamaz (MANUAL) → girilen değer kalır, computed = false;"
            + " ertesi ay geçen ayın adedinden hesaplanır (computed = true, saklı değer değil)")
    void firstReportKeepsTypedChangeAndNextMonthComputes() {
        TechErrorChannel ch = channel(1, "Mobil", null);
        TechErrorReport aug = report(ch, 2026, 8, template(CHANGE_FIELDS),
                values("total-tx", 1000, "error-count", 10, "tx-increase-pct", 5.5, "tx-increase", false));
        TechErrorReport sep = report(ch, 2026, 9, template(CHANGE_FIELDS),
                values("total-tx", 1200, "error-count", 12, "tx-increase-pct", 99, "tx-increase", false, "error-rate", 7));

        TechErrorExportService.Result res = exporter.export(List.of(ch), Map.of(1L, "Takım A"),
                new TechErrorExportService.Filter(2026, null, false), NOW);
        List<List<Object>> a = rowsOf(res, aug.getId());
        assertThat(field(a, "tx-increase-pct").get(COL_VALUE)).isEqualTo(new BigDecimal("5.5"));
        assertThat(field(a, "tx-increase-pct").get(COL_COMPUTED)).isEqualTo(false);
        assertThat(field(a, "tx-increase").get(COL_VALUE)).isEqualTo(false);
        assertThat(field(a, "tx-increase").get(COL_COMPUTED)).isEqualTo(false);
        assertThat(field(a, "error-rate").get(COL_VALUE)).isEqualTo(new BigDecimal("1"));
        assertThat(field(a, "error-rate").get(COL_COMPUTED)).isEqualTo(true);
        List<List<Object>> s = rowsOf(res, sep.getId());
        assertThat(field(s, "tx-increase-pct").get(COL_VALUE)).isEqualTo(new BigDecimal("20"));   // |1200 − 1000| / 1000
        assertThat(field(s, "tx-increase-pct").get(COL_COMPUTED)).isEqualTo(true);
        assertThat(field(s, "tx-increase").get(COL_VALUE)).isEqualTo(true);
        assertThat(field(s, "tx-increase").get(COL_COMPUTED)).isEqualTo(true);
        assertThat(field(s, "error-rate").get(COL_VALUE)).isEqualTo(new BigDecimal("1"));         // saklı 7 değil
    }

    @Test
    @DisplayName("geçen ay sonradan DÜZELTİLİRSE dışa aktarılan değişim oranı da değişir (PDF gibi canlı); önceki ay taslak olsa da"
            + " ve süzgeç dışında kalsa da (yalnız gönderilmiş / tek ay / yıl sınırı) okunur ama satır üretmez")
    void correctedPreviousMonthChangesTheRate() {
        TechErrorChannel ch = channel(1, "Mobil", null);
        TechErrorReport dec = report(ch, 2025, 12, template(CHANGE_FIELDS), values("total-tx", 400));
        TechErrorReport jan = report(ch, 2026, 1, template(CHANGE_FIELDS), values("total-tx", 500));
        TechErrorReport aug = report(ch, 2026, 8, template(CHANGE_FIELDS), values("total-tx", 1000));
        TechErrorReport sep = report(ch, 2026, 9, template(CHANGE_FIELDS), values("total-tx", 1200));
        Instant at = Instant.parse("2026-10-02T08:00:00Z");
        markSent(sep, at, "takim@example.com");
        markSent(jan, at, "takim@example.com");

        TechErrorExportService.Result one = exporter.export(List.of(ch), Map.of(1L, "Takım A"),
                new TechErrorExportService.Filter(2026, 9, true), NOW);
        assertThat(one.reports()).extracting(TechErrorExportService.ReportMeta::reportId).containsExactly(sep.getId());
        assertThat(field(one.rows(), "tx-increase-pct").get(COL_VALUE)).isEqualTo(new BigDecimal("20"));

        // Ağustos (taslak) düzeltildi: 1000 → 800 → Eylül'ün değişimi |1200 − 800| / 800 = %50 (Eylül yeniden kaydedilmeden)
        TechErrorReport fix = reports.findById(aug.getId()).orElseThrow();
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("fields", values("total-tx", 800));
        fix.setValuesJson(TechErrorTemplate.JSON.writeValueAsString(raw));
        reports.save(fix);
        TechErrorExportService.Result two = exporter.export(List.of(ch), Map.of(1L, "Takım A"),
                new TechErrorExportService.Filter(2026, 9, true), NOW);
        assertThat(field(two.rows(), "tx-increase-pct").get(COL_VALUE)).isEqualTo(new BigDecimal("50"));
        assertThat(field(two.rows(), "tx-increase-pct").get(COL_COMPUTED)).isEqualTo(true);

        // Yıl sınırı: 2026 yılı süzgecinde Ocak, 2025 Aralık'ına göre (|500 − 400| / 400 = %25); Aralık satır üretmez
        TechErrorExportService.Result year = exporter.export(List.of(ch), Map.of(1L, "Takım A"),
                new TechErrorExportService.Filter(2026, null, true), NOW);
        assertThat(year.reports()).extracting(TechErrorExportService.ReportMeta::reportId).containsExactly(jan.getId(), sep.getId());
        assertThat(field(rowsOf(year, jan.getId()), "tx-increase-pct").get(COL_VALUE)).isEqualTo(new BigDecimal("25"));
        assertThat(rowsOf(year, dec.getId())).isEmpty();
        assertThat(rowsOf(year, aug.getId())).isEmpty();
    }

    @Test
    @DisplayName("formül var ama sonuç yok (girdi boş): saklı eski oran dışa aktarılmaz (PDF'teki gibi boş), computed = false;"
            + " geçen ayın aynı kimlikli alanı başka türdeyse kullanılmaz (sameTypeFields) → değişim elle girilen değer")
    void missingInputDropsStoredRateAndTypeMismatchIsManual() {
        TechErrorChannel ch = channel(1, "POS", null);
        Template augT = template(List.of(new Field("total-tx", "Toplam işlem (metin)", "text", null)));
        report(ch, 2026, 8, augT, values("total-tx", "1000"));
        TechErrorReport sep = report(ch, 2026, 9, template(CHANGE_FIELDS),
                values("total-tx", 1200, "error-rate", 3.3, "tx-increase-pct", 12));
        List<List<Object>> rows = rowsOf(exporter.export(List.of(ch), Map.of(1L, "Takım A"),
                new TechErrorExportService.Filter(2026, 9, false), NOW), sep.getId());
        assertThat(field(rows, "error-rate").get(COL_VALUE)).isNull();                  // hata adedi boş → oran yok
        assertThat(field(rows, "error-rate").get(COL_COMPUTED)).isEqualTo(false);
        assertThat(field(rows, "tx-increase-pct").get(COL_VALUE)).isEqualTo(new BigDecimal("12"));
        assertThat(field(rows, "tx-increase-pct").get(COL_COMPUTED)).isEqualTo(false);
    }

    @Test
    @DisplayName("changed_after_send durumdan BAĞIMSIZ: gönderilmiş + sonra değişmiş rapor taze bir gönderim talebi sürerken (durum 'sending')"
            + " de changed_after_send = true; değişmemiş olan false; hiç gönderilmemiş taslak false")
    void changedAfterSendWhileSending() {
        TechErrorChannel ch = channel(1, "Mobil", null);
        TechErrorReport changed = report(ch, 2026, 8, template(DEFAULT_FIELDS), values("total-tx", 1));
        TechErrorReport same = report(ch, 2026, 9, template(DEFAULT_FIELDS), values("total-tx", 2));
        TechErrorReport draft = report(ch, 2026, 7, template(DEFAULT_FIELDS), values("total-tx", 3));
        Instant at = Instant.parse("2026-10-02T08:00:00Z");
        markSent(changed, at, "takim@example.com");
        markSent(same, at, "takim@example.com");
        reports.touchContent(changed.getId(), at.plusSeconds(60));
        Instant claim = NOW.minusSeconds(60);
        assertThat(reports.claimSend(changed.getId(), claim, claim.minusSeconds(600))).isEqualTo(1);
        assertThat(reports.claimSend(same.getId(), claim, claim.minusSeconds(600))).isEqualTo(1);
        assertThat(reports.claimSend(draft.getId(), claim, claim.minusSeconds(600))).isEqualTo(1);

        TechErrorExportService.Result res = exporter.export(List.of(ch), Map.of(1L, "Takım A"),
                new TechErrorExportService.Filter(2026, null, false), NOW);
        Map<Long, TechErrorExportService.ReportMeta> by = new LinkedHashMap<>();
        for (TechErrorExportService.ReportMeta m : res.reports()) by.put(m.reportId(), m);
        assertThat(by.get(changed.getId()).status()).isEqualTo("sending");
        assertThat(by.get(changed.getId()).changedAfterSend()).isTrue();
        assertThat(field(rowsOf(res, changed.getId()), "total-tx").get(COL_CHANGED)).isEqualTo(true);
        assertThat(by.get(same.getId()).status()).isEqualTo("sending");
        assertThat(by.get(same.getId()).changedAfterSend()).isFalse();
        assertThat(by.get(draft.getId()).changedAfterSend()).isFalse();
    }

    @Test
    @DisplayName("düz ondalık: bilimsel gösterim yok, en çok 6 ondalık, tam sayı kesirsiz; tür uyuşmazlığı null")
    void plainDecimals() {
        assertThat(TechErrorExportService.plainDecimal(12000L).toString()).isEqualTo("12000");
        assertThat(TechErrorExportService.plainDecimal(12000.0).toString()).isEqualTo("12000");
        assertThat(TechErrorExportService.plainDecimal(0.000001).toString()).isEqualTo("0.000001");
        assertThat(TechErrorExportService.plainDecimal(0.1 + 0.2).toString()).isEqualTo("0.3");
        assertThat(TechErrorExportService.plainDecimal(-1.5).toString()).isEqualTo("-1.5");
        assertThat(TechErrorExportService.plainDecimal(Double.NaN)).isNull();
        Field num = new Field("n", "N", "number", null);
        Field yes = new Field("y", "Y", "yesno", null);
        assertThat(TechErrorExportService.exportValue(num, "metin")).isNull();
        assertThat(TechErrorExportService.exportValue(yes, 1)).isNull();
        assertThat(TechErrorExportService.exportValue(yes, false)).isEqualTo(false);
    }
}
