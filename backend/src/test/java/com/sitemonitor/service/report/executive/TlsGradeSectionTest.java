package com.sitemonitor.service.report.executive;

import com.sitemonitor.dto.CertificateDto;
import com.sitemonitor.model.TlsGradeChange;
import com.sitemonitor.service.tlsgrade.TlsGradeService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import static com.sitemonitor.service.report.executive.ExecTestSupport.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * TLS notu bölümü — durum eşikleri (doğruluk tablosu), dağılım / neden sayımı, ay penceresindeki düşüşler (uç nokta başına
 * en ağırı, toparlanma rapor anına göre), bağlamdan okuma (elle yüklenen = uygulanamaz) ve ay sınırları.
 */
class TlsGradeSectionTest {

    private final TlsGradeService service = mock(TlsGradeService.class);
    private final TlsGradeSection section = new TlsGradeSection(service);

    private static TlsGradeSection.Endpoint ep(String domain, Integer tier, String grade, String... reasons) {
        return new TlsGradeSection.Endpoint(domain, 1L, "Takım A", tier, grade, List.of(reasons));
    }

    private static TlsGradeChange drop(Long inv, String domain, String from, String to, String at) {
        TlsGradeChange c = new TlsGradeChange();
        c.setInventoryId(inv);
        c.setDomain(domain);
        c.setTeamId(1L);
        c.setFromGrade(from);
        c.setToGrade(to);
        c.setDirection(TlsGradeChange.DROP);
        c.setChangedAt(at);
        return c;
    }

    private SectionResult eval(List<TlsGradeSection.Endpoint> graded, List<TlsGradeChange> drops) {
        return section.evaluate(ctx(), new TlsGradeSection.Inputs(graded, 0, 0,
                Map.of("endpoints", graded.size(), "ok", graded.size(), "partial", 0, "failed", 0),
                new TlsGradeService.DropWindow(drops.size(), drops)));
    }

    /** {@code n} adet A notlu Seviye 3 uç nokta + verilenler. */
    private static List<TlsGradeSection.Endpoint> fleet(int goodCount, TlsGradeSection.Endpoint... extra) {
        List<TlsGradeSection.Endpoint> out = new ArrayList<>();
        for (int i = 0; i < goodCount; i++) out.add(ep("ok" + i + ".example.com", 3, i % 2 == 0 ? "A+" : "A", "NO_TLS13"));
        out.addAll(List.of(extra));
        return out;
    }

    @ParameterizedTest(name = "{0} → {1}")
    @CsvSource({
            "all-good,             ok",
            "f-tier2,              attention",
            "f-tier1,              critical",
            "c-10pct,              attention",
            "c-9pct,               ok",
            "drop-unrecovered,     attention",
            "drop-recovered,       ok",
            "empty,                no_data",
    })
    @DisplayName("DURUM EŞİKLERİ: Seviye 1'de F → kritik; başka F / C ve altı ≥ %10 / toparlanmamış düşüş → takip")
    void statusTruthTable(String scenario, String expected) {
        List<TlsGradeSection.Endpoint> graded;
        List<TlsGradeChange> drops = List.of();
        switch (scenario) {
            case "all-good" -> graded = fleet(10);
            case "f-tier2" -> graded = fleet(30, ep("bad.example.com", 2, "F", "CERT_EXPIRED"));
            case "f-tier1" -> graded = fleet(30, ep("bad.example.com", 1, "F", "CERT_EXPIRED"));
            case "c-10pct" -> graded = fleet(9, ep("c.example.com", 3, "C", "NO_TLS12"));     // 1 / 10 = %10
            case "c-9pct" -> graded = fleet(10, ep("c.example.com", 3, "C", "NO_TLS12"));     // 1 / 11 = %9,1
            case "drop-unrecovered" -> {
                graded = fleet(10, ep("dropped.example.com", 2, "B", "TLS10_ENABLED"));
                drops = List.of(drop(7L, "dropped.example.com", "A", "B", "2026-09-12T10:00:00"));
            }
            case "drop-recovered" -> {
                graded = fleet(10, ep("dropped.example.com", 2, "A", "NO_TLS13"));
                drops = List.of(drop(7L, "dropped.example.com", "A", "B", "2026-09-12T10:00:00"));
            }
            default -> graded = List.of();
        }
        assertThat(eval(graded, drops).status()).isEqualTo(expected);
    }

    @Test
    @DisplayName("dağılım: paylar, F sayısı, not tablosu; nedenler yalnız A'nın altındaki uç noktalarda ve B–F tavanlılar")
    void distributionAndReasons() {
        List<TlsGradeSection.Endpoint> graded = List.of(
                ep("a1.com", 1, "A+"),
                ep("a2.com", 2, "A", "NO_TLS13", "HSTS_MISSING"),
                ep("b1.com", 2, "B", "TLS10_ENABLED", "NO_TLS13"),
                ep("b2.com", 3, "B", "TLS10_ENABLED", "TLS11_ENABLED"),
                ep("c1.com", 3, "C", "NO_TLS12", "TLS10_ENABLED"),
                ep("f1.com", 1, "F", "CERT_EXPIRED", "TLS10_ENABLED", "NO_TLS13"));
        SectionResult r = eval(graded, List.of());

        assertThat(r.snapshot()).isTrue();
        assertThat(r.asOf()).isEqualTo("2026-10-10T09:00:00");
        assertThat(r.status()).isEqualTo(SectionResult.CRITICAL);
        assertThat(verdictCodes(r)).containsExactly("DISTRIBUTION", "F_GRADES", "NO_DROPS", "TOP_REASON");
        assertThat(kpi(r, "top_share").value()).isEqualTo(33.3);
        assertThat(kpi(r, "low_share").value()).isEqualTo(33.3);
        assertThat(kpi(r, "f_count").value()).isEqualTo(1);
        assertThat(kpi(r, "f_count").hintParams()).containsExactly(1);

        SectionResult.Table grades = table(r, "grades");
        assertThat(grades.rows()).extracting(m -> m.get("grade")).containsExactly("A+", "A", "B", "C", "D", "F");
        assertThat(grades.rows()).extracting(m -> m.get("endpoints")).containsExactly(1, 1, 2, 1, 0, 1);
        assertThat(grades.rows().get(5)).containsEntry("tier1", 1).containsEntry("state", SectionResult.T_BAD);

        // TLS10_ENABLED 4 uç noktada (b1, b2, c1, f1); A tavanlı NO_TLS13 / HSTS_MISSING sayılmaz
        SectionResult.Table reasons = table(r, "reasons");
        assertThat(reasons.rows()).extracting(m -> m.get("reason"))
                .containsExactly("TLS10_ENABLED", "CERT_EXPIRED", "NO_TLS12", "TLS11_ENABLED");
        assertThat(reasons.rows().get(0)).containsEntry("endpoints", 4).containsEntry("share", 100.0).containsEntry("cap", "B");
        assertThat(r.verdicts().get(3).params().get(0)).isEqualTo(new SectionResult.Param("TLS10_ENABLED", "tls_reason"));

        // En düşük: F önce, sonra C; notu belirleyen neden (tavanı nota eşit)
        SectionResult.Table lowest = table(r, "lowest");
        assertThat(lowest.rows()).extracting(m -> m.get("domain")).containsExactly("f1.com", "c1.com");
        assertThat(lowest.rows()).extracting(m -> m.get("reason")).containsExactly("CERT_EXPIRED", "NO_TLS12");
    }

    @Test
    @DisplayName("düşüşler: uç nokta başına EN AĞIR düşüş, toparlanmayanlar önce, gün İstanbul takvimiyle; toplam tam sayım")
    void dropsTable() {
        List<TlsGradeSection.Endpoint> graded = List.of(
                ep("x.com", 1, "C", "NO_TLS12"),
                ep("y.com", 2, "A", "NO_TLS13"),
                ep("z.com", 3, "B", "TLS10_ENABLED"));
        List<TlsGradeChange> rows = List.of(
                drop(1L, "x.com", "B", "C", "2026-09-20T10:00:00"),
                drop(1L, "x.com", "A", "C", "2026-09-10T10:00:00"),          // x'in en ağır düşüşü (A → C)
                drop(2L, "y.com", "A", "D", "2026-09-30T20:30:00"),          // toparlandı (bugün A); 23:30 TR = 30 Eylül
                drop(3L, "z.com", "A", "B", "2026-09-01T00:30:00"));
        SectionResult r = section.evaluate(ctx(), new TlsGradeSection.Inputs(graded, 0, 0, null,
                new TlsGradeService.DropWindow(9, rows)));

        assertThat(r.verdicts()).filteredOn(v -> v.code().equals("DROPS")).singleElement()
                .satisfies(v -> assertThat(v.params()).containsExactly(9L, 3, 2));
        assertThat(kpi(r, "drops").value()).isEqualTo(9L);
        assertThat(kpi(r, "probe_coverage").value()).isNull();
        assertThat(kpi(r, "probe_coverage").hint()).isNull();
        SectionResult.Table t = table(r, "drops");
        assertThat(t.total()).isEqualTo(3);
        assertThat(t.rows()).extracting(m -> m.get("domain")).containsExactly("x.com", "z.com", "y.com");
        assertThat(t.rows().get(0)).containsEntry("from_grade", "A").containsEntry("to_grade", "C")
                .containsEntry("current_grade", "C").containsEntry("state", SectionResult.T_WARN)
                .containsEntry("dropped_at", "2026-09-10");
        assertThat(t.rows().get(2)).containsEntry("state", SectionResult.T_OK).containsEntry("dropped_at", "2026-09-30");
        assertThat(r.notes()).extracting(SectionResult.Note::code).contains("DROPS_SAMPLED");
    }

    @Test
    @DisplayName("compute: elle yüklenen uygulanamaz, notsuz + hiç kontrol edilmemiş notsuz, duraklatılmış atlanır; "
            + "kapsama ağ alan adları, düşüşler AY SINIRLARIYLA (İstanbul → UTC)")
    @SuppressWarnings("unchecked")
    void computeReadsContextAndMonthWindow() {
        CertificateDto graded = cert("a.com", 1L, 1, null, null);
        graded.setTlsGrade("B");
        graded.setTlsGradeReasons(List.of("TLS10_ENABLED"));
        CertificateDto manual = cert("m.com", 1L, 2, null, null);
        manual.setCertSource("MANUAL");
        CertificateDto noGrade = cert("n.com", 2L, 2, null, null);
        CertificateDto paused = cert("p.com", 2L, 2, null, null);
        paused.setPaused(true);
        paused.setTlsGrade("F");
        ExecutiveSummaryContext c = ctx(SEP, NOW, 99.9, 30, List.of(graded, manual, noGrade, paused),
                invMap(inv("a.com", 1L, 1, null), inv("never.com", 2L, 3, null)), Map.of(1L, "Takım A"));
        when(service.coverageForDomains(any())).thenReturn(Map.of("endpoints", 3, "ok", 1, "partial", 1, "failed", 1));
        when(service.dropsBetween(anyString(), anyString(), anyInt())).thenReturn(new TlsGradeService.DropWindow(0, List.of()));

        SectionResult r = section.compute(c);

        verify(service).dropsBetween(eq("2026-08-31T21:00:00"), eq("2026-09-30T21:00:00"), eq(TlsGradeSection.DROP_SCAN));
        org.mockito.ArgumentCaptor<Collection<String>> cap = org.mockito.ArgumentCaptor.forClass(Collection.class);
        verify(service).coverageForDomains(cap.capture());
        assertThat(cap.getValue()).containsExactlyInAnyOrder("a.com", "n.com", "never.com");   // m.com elle, p.com duraklatılmış
        assertThat(kpi(r, "graded").value()).isEqualTo(1);
        assertThat(kpi(r, "graded").hintParams()).containsExactly(2, 1);           // n.com + never.com notsuz; m.com dosyadan
        assertThat(kpi(r, "probe_coverage").value()).isEqualTo(66.7);
        assertThat(table(r, "grades").rows().get(2)).containsEntry("endpoints", 1).containsEntry("tier1", 1);
        assertThat(r.status()).isEqualTo(SectionResult.OK);
        assertThat(verdictCodes(r)).contains("NO_DROPS");
    }

    @Test
    @DisplayName("bağımlılık hatası özeti düşürmez: kapsama / günlük okunamazsa bölüm yine üretilir (not + tablo yok)")
    void dependencyFailureIsIsolated() {
        CertificateDto graded = cert("a.com", 1L, 3, null, null);
        graded.setTlsGrade("A");
        when(service.coverageForDomains(any())).thenThrow(new IllegalStateException("tablo yok"));
        when(service.dropsBetween(anyString(), anyString(), anyInt())).thenThrow(new IllegalStateException("tablo yok"));
        SectionResult r = section.compute(ctx(SEP, NOW, 99.9, 30, List.of(graded), Map.of(), Map.of()));
        assertThat(r.status()).isEqualTo(SectionResult.OK);
        assertThat(r.notes()).extracting(SectionResult.Note::code).contains("DROPS_UNAVAILABLE");
        assertThat(r.tables()).extracting(SectionResult.Table::code).doesNotContain("drops");
        assertThat(kpi(r, "drops").value()).isNull();
        assertThat(verdictCodes(r)).doesNotContain("DROPS", "NO_DROPS");
    }

    @Test
    @DisplayName("veri yok: notlanan uç nokta yoksa VERİ YOK + hüküm; profil taranmamışsa NO_PROFILES notu")
    void noDataAndNoProfiles() {
        SectionResult empty = section.evaluate(ctx(), new TlsGradeSection.Inputs(List.of(), 3, 0,
                Map.of("endpoints", 3, "ok", 0, "partial", 0, "failed", 0), new TlsGradeService.DropWindow(0, List.of())));
        assertThat(empty.status()).isEqualTo(SectionResult.NO_DATA);
        assertThat(verdictCodes(empty)).containsExactly("NO_DATA");
        assertThat(table(empty, "grades").rows()).isEmpty();
        assertThat(empty.notes()).extracting(SectionResult.Note::code).contains("NO_PROFILES");
    }

    @Test
    @DisplayName("tablolar posta / PDF için sınırlı (≤ 10 satır), toplam kırpılmadan önceki sayı")
    void tableLimits() {
        List<TlsGradeSection.Endpoint> graded = new ArrayList<>();
        for (int i = 0; i < 25; i++) graded.add(ep("c" + i + ".com", 3, "C", "NO_TLS12"));
        SectionResult r = eval(graded, List.of());
        assertThat(table(r, "lowest").rows()).hasSize(TlsGradeSection.TABLE_LIMIT);
        assertThat(table(r, "lowest").total()).isEqualTo(25);
        assertThat(TlsGradeSection.REASON_LIMIT).isLessThanOrEqualTo(10);
    }
}
