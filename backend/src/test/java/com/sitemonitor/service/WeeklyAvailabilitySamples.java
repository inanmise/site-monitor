package com.sitemonitor.service;

import com.sitemonitor.model.Team;
import com.sitemonitor.service.EmailNotificationService.AttachmentInfo;
import com.sitemonitor.service.EmailNotificationService.AvailabilityRow;
import com.sitemonitor.service.EmailNotificationService.AvailabilitySummary;
import com.sitemonitor.service.EmailNotificationService.DeploymentWeekly;
import com.sitemonitor.service.EmailNotificationService.DomainExpiryWeekly;
import com.sitemonitor.service.EmailNotificationService.DomainExpiryWeeklyRow;
import com.sitemonitor.service.EmailNotificationService.PageSpeedWeekly;
import com.sitemonitor.service.EmailNotificationService.PageSpeedWeeklyRow;
import com.sitemonitor.service.EmailNotificationService.WeakAlgoWeekly;
import com.sitemonitor.service.MonitoringWeeklyStatsService.TypeStats;
import com.sitemonitor.service.WeeklyAvailabilityReportService.Window;
import com.sitemonitor.service.mail.WeeklyAvailabilityMail;
import com.sitemonitor.service.report.WeeklyOutageReportService.OutageRow;
import com.sitemonitor.service.report.WeeklyOutageReportService.TypeGroup;
import com.sitemonitor.service.report.WeeklyOutageReportService.WeeklyOutageData;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Haftalık Erişilebilirlik e-postasının GERÇEKÇİ örnekleri (yeniden tasarım 2026-09-28) — galeri, sözleşme ve içerik
 * testleri aynı veriyi kullanır. Alarm tarafı gerçek eşleyiciden ({@link WeeklyAvailabilityReportService#alarmsOf},
 * {@link WeeklyAvailabilityReportService#insightsFor}) geçer; sabit tarihler yalnız GÖSTERİMDİR (kayan bir pencereyle
 * karşılaştırılmaz). Yer tutucu veri: example.com, Takım A.
 */
final class WeeklyAvailabilitySamples {

    private WeeklyAvailabilitySamples() { }

    static final String LONG_HOST = EmailSamples.LONG_HOST;
    static final String WEEK = "21–27 Eylül 2026";
    /** 2026 ISO 39. hafta (Pzt 21 Eylül 00:00 – Paz 27 Eylül 23:59:59, Türkiye saati) — UTC sınırlar. */
    static final Window W39 = new Window("2026-09-20T21:00:00", "2026-09-27T20:59:59",
            Instant.parse("2026-09-27T20:59:59Z"), 2026, 39, WEEK);
    static final AttachmentInfo ATT = new AttachmentInfo("haftalik-kesinti-raporu_takim-a_2026-W39.pdf", 14, 2, 9, 10);

    static Team team() {
        Team t = new Team();
        t.setId(7L);
        t.setName("Takım A");
        return t;
    }

    /** Tek örneğin girdileri — {@code EmailNotificationService.buildWeeklyAvailabilityHtml} 10'lu imzasıyla aynı sıra. */
    record Case(String slug, List<AvailabilityRow> rows, AvailabilitySummary summary, AttachmentInfo att, PageSpeedWeekly ps,
                DeploymentWeekly dep, WeakAlgoWeekly weak, DomainExpiryWeekly dom, WeeklyAvailabilityMail.Insights insights) {
        String html(EmailNotificationService svc) {
            return svc.buildWeeklyAvailabilityHtml("Takım A", WEEK, rows, summary, att, ps, dep, weak, dom, insights);
        }
    }

    static List<Case> all() {
        return List.of(typical(), clean(), bad(), large(), noData());
    }

    // ── Örnekler ──────────────────────────────────────────────────────────────

    /** Olağan hafta: 2 domainde kesinti, 14 alarm (2'si açık), geçen haftadan hafif iyileşme. */
    static Case typical() {
        List<AvailabilityRow> rows = new ArrayList<>();
        rows.add(new AvailabilityRow(LONG_HOST, 97.42, 3, 260, 180, 812L, 2410L, 5));
        rows.add(new AvailabilityRow("www.example.com", 99.87, 1, 12, 12, 210L, 480L, 44));
        for (int i = 1; i <= 10; i++) rows.add(new AvailabilityRow("svc" + i + ".example.com", 100.0, 0, 0, 0, 90L + i * 7, 180L + i * 11, 30 * i));
        rows.add(new AvailabilityRow("nodata.example.com", null, 0, 0, 0, null, null, null));
        List<OutageRow> alarms = new ArrayList<>();
        alarms.add(alarm(4101, "http", "HTTP_DOWN", LONG_HOST, "CRITICAL", "2026-09-23T06:10:00", "2026-09-23T09:10:00", false, false, 180, 180, "Yük dengeleyicide sertifika zinciri eksikti; yeniden dağıtıldı."));
        alarms.add(alarm(4102, "port", "PORT_DOWN", "db.example.com:5432", "CRITICAL", "2026-09-24T01:00:00", null, true, false, 6720, 6720, null));
        alarms.add(alarm(4103, "http", "HTTP_DOWN", "www.example.com", "HIGH", "2026-09-25T11:02:00", "2026-09-25T11:14:00", false, false, 12, 12, null));
        alarms.add(alarm(4104, "dns", "DNS_CHANGED", "example.com (A)", "HIGH", "2026-09-22T08:00:00", "2026-09-22T09:30:00", false, false, 90, 90, "Planlı IP değişikliği — Kişi A onayladı."));
        alarms.add(alarm(4105, "cert", "EXPIRY", LONG_HOST, "CRITICAL", "2026-09-10T06:00:00", null, true, true, 26000, 10079, null));
        for (int i = 0; i < 9; i++) {
            alarms.add(alarm(4110 + i, "ping", "PING_SLOW", "gw" + (i % 3 + 1) + ".example.com", "WARNING",
                    "2026-09-2" + (2 + i % 6) + "T1" + i + ":00:00", "2026-09-2" + (2 + i % 6) + "T1" + i + ":0" + (i + 1) + ":00", false, false, i + 1, i + 1, null));
        }
        List<TypeStats> types = List.of(
                ts("cert", 13, 91, 100.0, 0.0, 0, 1), ts("http", 40, 26880, 99.6, -0.2, 2, 0), ts("ping", 6, 60480, 99.9, 0.1, 9, 0),
                ts("port", 4, 2688, 96.4, -3.5, 1, 1), ts("dns", 8, 5376, 100.0, 0.0, 1, 0), ts("keyword", 0, 0, null, null, 0, 0));
        WeeklyOutageData o = outage(alarms, types, rows, 3);
        return new Case("report-availability", rows, summary(rows), ATT,
                new PageSpeedWeekly(List.of(
                        new PageSpeedWeeklyRow("Ana sayfa — " + EmailSamples.TR, EmailSamples.LONG_URL, 3120L, 2400L, 14),
                        new PageSpeedWeeklyRow("Giriş", "https://www.example.com/login", 980L, 1200L, 0)), 6, 1),
                new DeploymentWeekly(2, "20.86.0", "20.87.0", 1, 1), new WeakAlgoWeekly(2, 212),
                new DomainExpiryWeekly(List.of(
                        new DomainExpiryWeeklyRow("example.com", 5, "2026-10-01T00:00:00Z", "Örnek Registrar", "NONE", null, false),
                        new DomainExpiryWeeklyRow(LONG_HOST, 40, "2026-11-05", null, "BOTH", "2026-09-20", true)), 18, 90, 1),
                WeeklyAvailabilityReportService.insightsFor(team(), W39, 99.65, o));
    }

    /** %100 hafta: kesinti yok, alarm yok, geçen haftayla aynı. */
    static Case clean() {
        List<AvailabilityRow> rows = List.of(
                new AvailabilityRow("www.example.com", 100.0, 0, 0, 0, 120L, 240L, 120),
                new AvailabilityRow("api.example.com", 100.0, 0, 0, 0, 95L, 180L, 210));
        List<TypeStats> types = List.of(ts("cert", 2, 14, 100.0, 0.0, 0, 0), ts("http", 3, 2016, 100.0, 0.0, 0, 0));
        WeeklyOutageData o = outage(List.of(), types, rows, 0);
        return new Case("report-availability-clean", rows, summary(rows),
                new AttachmentInfo("haftalik-kesinti-raporu_takim-a_2026-W39.pdf", 0, 0, 0, 10), null,
                new DeploymentWeekly(0, null, "20.87.0", 0, 0), new WeakAlgoWeekly(0, 212), new DomainExpiryWeekly(List.of(), 4, 90, 0),
                WeeklyAvailabilityReportService.insightsFor(team(), W39, 100.0, o));
    }

    /** Kötü hafta: ortalama %99'un altında, uzun ve açık kesintiler, notlarda özel karakterler. */
    static Case bad() {
        List<AvailabilityRow> rows = new ArrayList<>();
        rows.add(new AvailabilityRow("odeme.example.com", 91.20, 6, 887, 420, 1320L, 4800L, 12));
        rows.add(new AvailabilityRow("api.example.com", 96.85, 4, 318, 190, 640L, 2100L, 3));
        rows.add(new AvailabilityRow("mobil.example.com", 98.90, 2, 111, 95, 410L, 900L, -2));
        rows.add(new AvailabilityRow("www.example.com", 99.95, 1, 5, 5, 220L, 510L, 75));
        for (int i = 1; i <= 4; i++) rows.add(new AvailabilityRow("svc" + i + ".example.com", 100.0, 0, 0, 0, 100L + i, 200L + i, 90 + i));
        List<OutageRow> alarms = new ArrayList<>();
        alarms.add(alarm(5201, "http", "HTTP_DOWN", "odeme.example.com", "CRITICAL", "2026-09-22T03:00:00", null, true, false, 9959, 9959,
                "Kök neden: <b>veritabanı</b> bağlantı havuzu tükendi & yeniden başlatma \"geçici\" çözüm oldu."));
        alarms.add(alarm(5202, "http", "HTTP_DOWN", "api.example.com", "CRITICAL", "2026-09-24T12:00:00", "2026-09-24T15:10:00", false, false, 190, 190, null));
        alarms.add(alarm(5203, "port", "PORT_DOWN", "mq.example.com:5672", "HIGH", "2026-09-25T20:00:00", "2026-09-26T02:00:00", false, false, 360, 360, null));
        alarms.add(alarm(5204, "keyword", "KEYWORD", "https://www.example.com/giris", "HIGH", "2026-09-26T09:00:00", "2026-09-26T09:45:00", false, false, 45, 45, null));
        alarms.add(alarm(5205, "scripted", "SCRIPTED_FAIL", "Ödeme akışı", "CRITICAL", "2026-09-27T18:00:00", null, true, false, 1799, 1799, null));
        alarms.add(alarm(5206, "cert", "EXPIRY", "mobil.example.com", "CRITICAL", "2026-09-15T06:00:00", null, true, true, 19000, 10079, null));
        for (int i = 0; i < 6; i++) {
            alarms.add(alarm(5210 + i, "dns", "DNS_SLOW", "ns" + (i + 1) + ".example.com", "WARNING", "2026-09-23T0" + i + ":00:00",
                    "2026-09-23T0" + i + ":20:00", false, false, 20, 20, null));
        }
        List<TypeStats> types = List.of(
                ts("cert", 8, 56, 100.0, 0.0, 0, 1), ts("http", 12, 8064, 95.3, -4.4, 2, 1), ts("port", 5, 3360, 97.8, -2.1, 1, 0),
                ts("dns", 6, 4032, 99.1, -0.8, 6, 0), ts("keyword", 3, 2016, 98.7, -1.2, 1, 0), ts("scripted", 2, 672, 91.4, -8.1, 1, 1));
        WeeklyOutageData o = outage(alarms, types, rows, 4);
        return new Case("report-availability-bad", rows, summary(rows),
                new AttachmentInfo("haftalik-kesinti-raporu_takim-a_2026-W39.pdf", alarms.size(), 3, 11, 10), null,
                new DeploymentWeekly(3, "20.86.0", "20.87.2", 2, 1), new WeakAlgoWeekly(1, 40),
                new DomainExpiryWeekly(List.of(new DomainExpiryWeeklyRow("example.com", 9, "2026-10-07", "Örnek Registrar", "CLIENT", null, false)), 5, 90, 0),
                WeeklyAvailabilityReportService.insightsFor(team(), W39, 99.52, o));
    }

    /** Büyük takım: 60 domain, 40 alarm, 14 yaklaşan sertifika — tavanlar ve "+M daha" satırları. */
    static Case large() {
        List<AvailabilityRow> rows = new ArrayList<>();
        for (int i = 1; i <= 60; i++) {
            String d = "app" + i + ".example.com";
            if (i <= 14) rows.add(new AvailabilityRow(d, 100.0 - i * 0.13, 1 + i % 3, 12L * i, 6L * i, 150L + i, 400L + i, i <= 7 ? i * 4 : 200 + i));
            else if (i <= 17) rows.add(new AvailabilityRow(d, null, 0, 0, 0, null, null, null));
            else rows.add(new AvailabilityRow(d, 100.0, 0, 0, 0, 120L + i, 300L + i, i % 5 == 0 ? 30 + i : 300 + i));
        }
        List<OutageRow> alarms = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            boolean open = i % 9 == 0;
            alarms.add(alarm(6000 + i, i % 2 == 0 ? "http" : "port", i % 2 == 0 ? "HTTP_DOWN" : "PORT_DOWN", "app" + (i % 14 + 1) + ".example.com",
                    i % 4 == 0 ? "CRITICAL" : "HIGH", String.format("2026-09-%02dT%02d:15:00", 22 + i % 6, i % 24),
                    open ? null : String.format("2026-09-%02dT%02d:45:00", 22 + i % 6, i % 24), open, false, 30 + i * 7L, 30 + i * 7L, null));
        }
        List<TypeStats> types = List.of(ts("cert", 60, 420, 100.0, 0.0, 0, 0), ts("http", 60, 40320, 99.2, -0.3, 20, 3),
                ts("port", 30, 20160, 99.5, 0.2, 20, 2));
        WeeklyOutageData o = outage(alarms, types, rows, 31);
        return new Case("report-availability-large", rows, summary(rows),
                new AttachmentInfo("haftalik-kesinti-raporu_takim-a_2026-W39.pdf", 40, 5, 14, 10), null,
                new DeploymentWeekly(1, "20.86.0", "20.87.0", 0, 0), new WeakAlgoWeekly(0, 60), new DomainExpiryWeekly(List.of(), 12, 90, 0),
                WeeklyAvailabilityReportService.insightsFor(team(), W39, 99.61, o));
    }

    /** Veri yok: ölçüm kaydı yok, kesinti verisi toplanamadı (ek yok, alarm bölümleri çizilmez). */
    static Case noData() {
        List<AvailabilityRow> rows = List.of(
                new AvailabilityRow("yeni1.example.com", null, 0, 0, 0, null, null, null),
                new AvailabilityRow("yeni2.example.com", null, 0, 0, 0, null, null, 350));
        return new Case("report-availability-nodata", rows, summary(rows), null, null, null, null, null,
                WeeklyAvailabilityReportService.insightsFor(team(), W39, null, null));
    }

    // ── Kurucular ────────────────────────────────────────────────────────────

    static OutageRow alarm(long id, String type, String alertType, String target, String level, String startedAt, String endedAt,
                           boolean open, boolean carried, long durMin, long weekDurMin, String note) {
        return new OutageRow(id, type, alertType, target, level, startedAt, endedAt, open, carried, durMin, weekDurMin, 0L,
                null, null, open ? null : "Sistem (otomatik)", null, note, 2, 0, false, null, null);
    }

    static TypeStats ts(String type, int active, long checks, Double rate, Double delta, int opened, int openNow) {
        return new TypeStats(type, active, checks, rate, opened, opened, openNow, delta, 0, null, List.of());
    }

    /** Kesinti verisi — toplamlar {@code WeeklyOutageReportService.assemble} ile AYNI kurallarla türetilir. */
    static WeeklyOutageData outage(List<OutageRow> rows, List<TypeStats> types, List<AvailabilityRow> availability, int prevOpened) {
        Map<String, List<OutageRow>> byType = new LinkedHashMap<>();
        for (String t : MonitorTypeCatalog.ORDER) byType.put(t, new ArrayList<>());
        for (OutageRow r : rows) byType.get(r.monitorType()).add(r);
        List<TypeGroup> groups = new ArrayList<>();
        byType.forEach((k, v) -> { if (!v.isEmpty()) groups.add(new TypeGroup(k, MonitorTypeCatalog.label(k), List.copyOf(v))); });
        List<OutageRow> longest = rows.stream()
                .sorted(Comparator.comparingLong(OutageRow::weekDurationMin).reversed()
                        .thenComparing(Comparator.comparingLong(OutageRow::durationMin).reversed()))
                .limit(10).toList();
        int opened = (int) rows.stream().filter(r -> !r.carriedOver()).count();
        return new WeeklyOutageData("Takım A", WEEK, "28.09.2026 10:00",
                rows.size(), (int) rows.stream().filter(OutageRow::stillOpen).count(),
                (int) rows.stream().filter(OutageRow::carriedOver).count(),
                (int) rows.stream().map(OutageRow::target).filter(Objects::nonNull).distinct().count(),
                rows.stream().mapToLong(OutageRow::weekDurationMin).sum(), null,
                (int) availability.stream().filter(r -> r.outageCount() > 0).count(),
                opened, prevOpened, opened - prevOpened, types, groups, longest,
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), 10080L,
                rows.stream().filter(OutageRow::stillOpen).toList(), List.of(), availability, List.of(), 0, 0);
    }

    /** {@code WeeklyAvailabilityReportService.summarize} ile aynı kural (ortalama, %100 kıskacı, en iyi/en kötü). */
    static AvailabilitySummary summary(List<AvailabilityRow> rows) {
        List<AvailabilityRow> withData = rows.stream().filter(r -> r.availabilityPct() != null).toList();
        Double avg = withData.isEmpty() ? null
                : Math.round(withData.stream().mapToDouble(AvailabilityRow::availabilityPct).average().orElse(0) * 100.0) / 100.0;
        if (avg != null && avg >= 100.0 && withData.stream().anyMatch(r -> r.availabilityPct() < 100.0)) avg = 99.99;
        AvailabilityRow best = withData.stream().max(Comparator.comparingDouble(AvailabilityRow::availabilityPct)).orElse(null);
        AvailabilityRow worst = withData.stream().min(Comparator.comparingDouble(AvailabilityRow::availabilityPct)).orElse(null);
        Integer nearest = rows.stream().map(AvailabilityRow::certDaysRemaining).filter(Objects::nonNull).min(Integer::compareTo).orElse(null);
        return new AvailabilitySummary(rows.size(), withData.size(), avg,
                best == null ? null : best.domain(), best == null ? null : best.availabilityPct(),
                worst == null ? null : worst.domain(), worst == null ? null : worst.availabilityPct(),
                (int) rows.stream().filter(r -> r.outageCount() > 0).count(), nearest);
    }
}
