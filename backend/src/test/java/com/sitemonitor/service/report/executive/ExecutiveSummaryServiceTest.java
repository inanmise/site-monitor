package com.sitemonitor.service.report.executive;

import com.sitemonitor.config.GlobalExceptionHandler.FieldValidationException;
import com.sitemonitor.model.ExecutiveSummaryReport;
import com.sitemonitor.repository.ExecutiveSummaryReportRepository;
import com.sitemonitor.repository.TeamRepository;
import com.sitemonitor.service.CertificateService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static com.sitemonitor.service.report.executive.ExecTestSupport.NOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Özet servisi — bölüm sırası, hata yalıtımı, üst şerit, ay doğrulaması, bellek (sabit hesap sayısı), gönderilmiş ayın
 * kaydından okuma ve genişleme noktası (yeni bir sağlayıcı kendiliğinden girer).
 */
class ExecutiveSummaryServiceTest {

    private final ExecutiveSummarySettings settings = mock(ExecutiveSummarySettings.class);
    private final CertificateService certs = mock(CertificateService.class);
    private final TeamRepository teams = mock(TeamRepository.class);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final ExecutiveSummaryReportRepository repo = mock(ExecutiveSummaryReportRepository.class);

    @BeforeEach
    void setUp() {
        when(settings.availabilityTarget()).thenReturn(99.9);
        when(settings.renewalTargetDays()).thenReturn(30);
        when(repo.findByReportYearAndReportMonth(anyInt(), anyInt())).thenReturn(Optional.empty());
    }

    /** Sayaçlı sahte bölüm — genişleme noktasının örneği. */
    static final class Fake implements ExecutiveSummarySection {
        final String key; final int order; final String status; final AtomicInteger calls = new AtomicInteger();
        final boolean boom;
        Fake(String key, int order, String status, boolean boom) { this.key = key; this.order = order; this.status = status; this.boom = boom; }
        @Override public String key() { return key; }
        @Override public int order() { return order; }
        @Override public String title() { return "Bölüm " + key; }
        @Override public SectionResult compute(ExecutiveSummaryContext ctx) {
            calls.incrementAndGet();
            if (boom) throw new IllegalStateException("patladı");
            // bağlamın paylaşılan verisini iki kez ister — tek okuma beklenir
            ctx.inventory();
            ctx.inventory();
            return SectionResult.builder(key, order, title()).status(status).headlineKpi("k")
                    .verdict("V_" + key, SectionResult.T_OK, "hüküm " + key)
                    .kpi(new SectionResult.Kpi("k", "Gösterge", 1, "int", SectionResult.T_OK, null, null, null, null, null))
                    .build();
        }
    }

    private ExecutiveSummaryService svc(List<ExecutiveSummarySection> sections) {
        ExecutiveSummaryService s = new ExecutiveSummaryService(sections, settings, certs, teams, jdbc, repo);
        s.setClock(() -> NOW);
        return s;
    }

    @Test
    @DisplayName("bölümler order'a göre; hata fırlatan bölüm 'hesaplanamadı' olur, diğerleri etkilenmez; genel durum en kötüsü")
    void orderingIsolationHeadline() {
        Fake late = new Fake("tls-grade", 50, SectionResult.OK, false);   // ayrılmış sıra: yeni sağlayıcı kendiliğinden girer
        Fake first = new Fake("availability", 10, SectionResult.ATTENTION, false);
        Fake broken = new Fake("noise", 20, SectionResult.OK, true);
        ExecutiveSummary s = svc(List.of(late, first, broken)).compute(YearMonth.of(2026, 9));
        assertThat(s.sections()).extracting(SectionResult::key).containsExactly("availability", "noise", "tls-grade");
        assertThat(s.section("noise").status()).isEqualTo(SectionResult.ERROR);
        assertThat(s.status()).isEqualTo(SectionResult.ERROR);           // hata ATTENTION'dan önemli
        assertThat(s.headline()).extracting(SectionResult.Verdict::code).containsExactly("V_availability", "V_tls-grade");
        assertThat(s.headlineKpis()).extracting(ExecutiveSummary.HeadlineKpi::section).containsExactly("availability", "tls-grade");
        assertThat(s.month()).isEqualTo("2026-09");
        assertThat(s.monthLabel()).isEqualTo("Eylül 2026");
        assertThat(s.from()).isEqualTo("2026-08-31T21:00:00");
        assertThat(s.complete()).isTrue();
        assertThat(s.settings()).containsEntry("availability_target", 99.9);
        // paylaşılan envanter tek okuma (iki bölüm × iki çağrı)
        verify(jdbc, times(1)).queryForList(ExecutiveSummaryService.INVENTORY_SQL);
    }

    @Test
    @DisplayName("bellek: aynı ay ikinci istekte HESAPLANMAZ; fresh 30 sn'de bir; devam eden ay da belleklenir")
    void memo() {
        Fake f = new Fake("availability", 10, SectionResult.OK, false);
        ExecutiveSummaryService s = svc(List.of(f));
        YearMonth sep = YearMonth.of(2026, 9);
        s.get(sep, false, false);
        s.get(sep, false, false);
        assertThat(f.calls.get()).isEqualTo(1);
        s.get(sep, false, true);
        assertThat(f.calls.get()).isEqualTo(2);
        s.get(sep, false, true);                 // 30 sn dolmadı → bellekten
        assertThat(f.calls.get()).isEqualTo(2);
        s.get(YearMonth.of(2026, 10), false, false);
        s.get(YearMonth.of(2026, 10), false, false);
        assertThat(f.calls.get()).isEqualTo(3);
    }

    @Test
    @DisplayName("gönderilmiş ay (SENT) kayıttan okunur (source=snapshot); live=true canlı hesaplar; FAILED kaydı kullanılmaz")
    void snapshot() {
        Fake f = new Fake("availability", 10, SectionResult.OK, false);
        ExecutiveSummaryService s = svc(List.of(f));
        ExecutiveSummary computed = s.compute(YearMonth.of(2026, 9));
        ExecutiveSummaryReport row = new ExecutiveSummaryReport();
        row.setStatus(ExecutiveSummaryReport.SENT);
        row.setSummaryJson(ExecutiveSummaryService.toJson(computed));
        when(repo.findByReportYearAndReportMonth(2026, 9)).thenReturn(Optional.of(row));

        ExecutiveSummary snap = s.get(YearMonth.of(2026, 9), false, false);
        assertThat(snap.source()).isEqualTo(ExecutiveSummary.SOURCE_SNAPSHOT);
        assertThat(snap.sections()).hasSize(1);
        assertThat(snap.sections().get(0).kpis().get(0).value()).isEqualTo(1);
        assertThat(snap.headline().get(0).text()).isEqualTo("hüküm availability");

        assertThat(s.get(YearMonth.of(2026, 9), true, false).source()).isEqualTo(ExecutiveSummary.SOURCE_LIVE);

        row.setStatus(ExecutiveSummaryReport.FAILED);
        assertThat(s.snapshot(YearMonth.of(2026, 9))).isNull();
    }

    @Test
    @DisplayName("ay doğrulaması: boş → son tamamlanan ay; gelecek ve 24 aydan eski → 400 (field=month)")
    void parseMonth() {
        ExecutiveSummaryService s = svc(List.of());
        assertThat(s.currentMonth()).isEqualTo(YearMonth.of(2026, 10));
        assertThat(s.parseMonth(null)).isEqualTo(YearMonth.of(2026, 9));
        assertThat(s.parseMonth("2026-10")).isEqualTo(YearMonth.of(2026, 10));
        assertThatThrownBy(() -> s.parseMonth("2026-11")).isInstanceOf(FieldValidationException.class);
        assertThatThrownBy(() -> s.parseMonth("2024-09")).isInstanceOf(FieldValidationException.class);
        assertThatThrownBy(() -> s.parseMonth("eylül")).isInstanceOf(FieldValidationException.class)
                .satisfies(e -> assertThat(((FieldValidationException) e).getFields()).containsKey("month"));
    }

    @Test
    @DisplayName("ay sınırında saat dilimi: 30 Eylül 21:30 UTC = 1 Ekim İstanbul → bu ay Ekim, varsayılan Eylül")
    void currentMonthIstanbul() {
        ExecutiveSummaryService s = svc(List.of());
        s.setClock(() -> Instant.parse("2026-09-30T21:30:00Z"));
        assertThat(s.currentMonth()).isEqualTo(YearMonth.of(2026, 10));
        assertThat(s.defaultMonth()).isEqualTo(YearMonth.of(2026, 9));
        s.setClock(() -> Instant.parse("2026-09-30T20:30:00Z"));
        assertThat(s.currentMonth()).isEqualTo(YearMonth.of(2026, 9));
    }

    @Test
    @DisplayName("ay listesi: bu ay + 12 ay, gönderim durumuyla")
    void months() {
        ExecutiveSummaryReport sent = new ExecutiveSummaryReport();
        sent.setReportYear(2026);
        sent.setReportMonth(9);
        sent.setStatus(ExecutiveSummaryReport.SENT);
        when(repo.findAllByOrderByReportYearDescReportMonthDesc(any())).thenReturn(List.of(sent));
        var months = svc(List.of()).months();
        assertThat(months).hasSize(13);
        assertThat(months.get(0)).containsEntry("month", "2026-10").containsEntry("current", true);
        assertThat(months.get(1)).containsEntry("month", "2026-09").containsEntry("status", "SENT");
    }
}
