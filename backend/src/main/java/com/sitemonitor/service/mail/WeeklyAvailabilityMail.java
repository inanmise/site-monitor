package com.sitemonitor.service.mail;

import com.sitemonitor.service.EmailNotificationService.AttachmentInfo;
import com.sitemonitor.service.EmailNotificationService.AvailabilityRow;
import com.sitemonitor.service.EmailNotificationService.AvailabilitySummary;
import com.sitemonitor.service.EmailNotificationService.DeploymentWeekly;
import com.sitemonitor.service.EmailNotificationService.DomainExpiryWeekly;
import com.sitemonitor.service.EmailNotificationService.DomainExpiryWeeklyRow;
import com.sitemonitor.service.EmailNotificationService.PageSpeedWeekly;
import com.sitemonitor.service.EmailNotificationService.PageSpeedWeeklyRow;
import com.sitemonitor.service.EmailNotificationService.WeakAlgoWeekly;
import com.sitemonitor.service.mail.MailKit.Badge;
import com.sitemonitor.service.mail.MailKit.Bar;
import com.sitemonitor.service.mail.MailKit.Btn;
import com.sitemonitor.service.mail.MailKit.Kpi;
import com.sitemonitor.service.mail.MailKit.RankRow;
import com.sitemonitor.service.mail.MailKit.Variant;
import com.sitemonitor.service.mail.MailTokens.Tone;
import com.sitemonitor.service.report.WeeklyOutageReportService;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Haftalık Erişilebilirlik e-postası (yeniden tasarım 2026-09-28) — YALNIZ {@link MailDoc}/{@link MailKit} ile:
 * Outlook-güvenli, mobil duyarlı (360–390px'te tek sütun, 600–700px'te akışkan), sol şerit YOK, serbest renk YOK.
 * Saf fonksiyon: Spring bağımlılığı yok; veri {@link Input} ile HAZIR gelir (toplama
 * {@code WeeklyAvailabilityReportService}'te), bağlantılar CANLI taban adresten ({@code site.monitor.app.base-url}).
 *
 * <p><b>Okuma sırası</b> (yöneticinin 10 saniyede cevap aradığı soru → ayrıntı): hüküm rozeti + tek cümlelik hüküm →
 * erişilebilirlik KPI'ları (geçen haftaya göre değişim) → alarm KPI'ları (tüm izleme türleri) → ek duyurusu → izleme
 * türü başına çubuklar → en kötü domainler → haftanın en uzun alarmları → yaklaşan sertifika bitişleri → sayfa hızı /
 * dağıtım / zayıf algoritma / alan adı bantları → eylem düğmeleri → "neden bu e-postayı aldınız?".
 *
 * <p><b>Rakamlar kesin, liste tavanlı.</b> Hiçbir sayı özetle değiştirilmez; uzun listeler (domain, alarm, sertifika)
 * {@link #TOP_DOMAINS}/{@link #TOP_INCIDENTS}/{@link #TOP_CERTS} satırda kesilir ve kesilen kısım "+M daha" satırında
 * TÜRÜNE göre sayılıp tam listenin yerine (ekteki PDF / uygulama) bağlanır — büyük takımda gövde Gmail'in 102 KB
 * kırpma sınırının altında kalsın.
 */
public final class WeeklyAvailabilityMail {

    private WeeklyAvailabilityMail() { }

    public static final String KICKER = "Haftalık Erişilebilirlik";
    /** Gövdedeki domain listesinin tavanı — tamamı ekteki PDF'te. */
    public static final int TOP_DOMAINS = 10;
    /** Haftanın alarmları listesinin tavanı — tamamı Alarm Geçmişi'nde. */
    public static final int TOP_INCIDENTS = 8;
    /** Yaklaşan sertifika listesinin tavanı. */
    public static final int TOP_CERTS = 8;
    /** Yaklaşan sertifika penceresi (gün) — ekteki PDF'in "Süresi Yaklaşan Sertifikalar (≤ 60 gün)" bölümüyle aynı. */
    public static final int CERT_WINDOW_DAYS = 60;
    /** Alarm notunun gövdedeki en uzun hâli (tamamı alarm detayında). */
    static final int NOTE_MAX = 160;

    // ── Girdi ───────────────────────────────────────────────────────────────

    /**
     * Toplayıcının (WeeklyAvailabilityReportService) e-postaya ek verdiği bağlam. {@code weekStartDay}/{@code weekEndDay}
     * kurum saatiyle {@code yyyy-MM-dd} (Alarm Geçmişi süzgeci); {@code prevAvgAvailabilityPct} geçen haftanın ortalaması
     * (null = veri yok); {@code alarms} null = kesinti verisi toplanamadı (alarm bölümleri çizilmez).
     */
    public record Insights(Long teamId, int isoYear, int isoWeek, String weekStartDay, String weekEndDay,
                           Double prevAvgAvailabilityPct, Alarms alarms) { }

    /**
     * Haftanın alarm özeti (ekteki PDF ile AYNI kaynak). {@code total} hafta içinde açık olan TÜM alarmlar (önceki
     * haftadan devredenler dahil); değişim {@code openedThisWeek} ile {@code openedPrevWeek} arasındadır (iki taraf da
     * "o hafta açılan"). {@code mttrMinutes} çözülmüş alarmların ortalama süresi (null = çözülen yok).
     * {@code incidents} haftaya düşen süreye göre SIRALI (en uzun önce).
     */
    public record Alarms(int total, int openedThisWeek, int openedPrevWeek, int stillOpen, int carriedOver,
                         int affectedTargets, Long mttrMinutes, int resolved,
                         List<TypeRow> types, List<IncidentRow> incidents) { }

    /** İzleme türü satırı: kontrol başarı oranı (null = kontrol yok), geçen haftaya göre puan farkı, alarmlar, alarm süresi. */
    public record TypeRow(String label, int monitors, long checks, Double successRate, Double successRateDelta,
                          int alarmsOpened, int alarmsOpenNow, long alarmMinutes) { }

    /**
     * Haftanın bir alarmı — etiketler toplayıcıda Türkçeleştirilmiş gelir. {@code startedAtLocal} kurum saatiyle kısa
     * damga; {@code note} çözüm/onay notu (null = yok).
     */
    public record IncidentRow(Long alertId, String typeLabel, String problem, String target, String level, String levelLabel,
                              String startedAtLocal, boolean stillOpen, boolean carriedOver, long durationMin,
                              long weekDurationMin, String note) { }

    /** E-postanın tüm girdisi. {@code baseUrl} CANLI taban adres (boş = bağlantı/düğme çizilmez). */
    public record Input(String teamName, String weekLabel, List<AvailabilityRow> rows, AvailabilitySummary summary,
                        AttachmentInfo attachment, PageSpeedWeekly pageSpeed, DeploymentWeekly deployments,
                        WeakAlgoWeekly weakAlgo, DomainExpiryWeekly domainExpiry, Insights insights,
                        String baseUrl, String generatedAt) { }

    // ── Kurulum ─────────────────────────────────────────────────────────────

    public static MailDoc.Mail build(Input in) {
        String team = nz(in.teamName());
        String week = nz(in.weekLabel());
        List<AvailabilityRow> rows = in.rows() == null ? List.of() : in.rows();
        AvailabilitySummary s = in.summary() != null ? in.summary()
                : new AvailabilitySummary(rows.size(), 0, null, null, null, null, null, 0, null);
        Insights ins = in.insights();
        Alarms alarms = ins == null ? null : ins.alarms();
        String base = trimBase(in.baseUrl());

        long downMin = 0;
        int outages = 0;
        AvailabilityRow longest = null;
        for (AvailabilityRow r : rows) {
            downMin += r.downtimeMinutes();
            outages += r.outageCount();
            if (r.longestOutageMinutes() > 0 && (longest == null || r.longestOutageMinutes() > longest.longestOutageMinutes())) longest = r;
        }
        Verdict v = verdict(s, outages, downMin, longest);

        MailDoc d = MailDoc.create("[Site Monitor] " + team + " — Haftalık Erişilebilirlik (" + week + ")").wide().darkCanvas()
                .preheader(preheader(s, downMin, alarms))
                .kicker(KICKER);

        // ── Başlık: hüküm rozeti + hafta, başlık, selamlama, tek cümlelik hüküm
        d.badges(v.tone() == Tone.DESTRUCTIVE ? Badge.solid(v.badge(), v.tone()) : Badge.tint(v.badge(), v.tone()),
                ins == null ? null : Badge.outline(ins.isoYear() + " · " + ins.isoWeek() + ". hafta"));
        d.title("Haftalık erişilebilirlik özeti", team + " · " + week);
        d.paragraphHtml("<strong>Sayın " + MailKit.esc(team) + " ekibi,</strong> sahip olduğunuz domainlerin <strong>" + MailKit.esc(week)
                        + "</strong> haftasına ait erişilebilirlik özeti aşağıdadır. Hafta Pazartesi 00:00 – Pazar 23:59 aralığıdır (Türkiye saati).",
                "Sayın " + team + " ekibi, sahip olduğunuz domainlerin " + week
                        + " haftasına ait erişilebilirlik özeti aşağıdadır. Hafta Pazartesi 00:00 – Pazar 23:59 aralığıdır (Türkiye saati).");
        d.alert(v.tone(), v.title(), v.body());

        // ── Erişilebilirlik KPI'ları (HTTP · envanter domainleri)
        d.heading("Erişilebilirlik", "HTTP kontrolleri · sertifika envanterindeki " + s.domainCount() + " domain");
        d.kpis(List.of(avgKpi(s, ins), downtimeKpi(downMin, outages), affectedKpi(s, longest), certKpi(s, rows)));
        d.note("Renk eşiği: 99.90% ve üzeri yeşil · 99.00–99.89% sarı · altı kırmızı. Bakım penceresindeki kontroller hesaba katılmaz.");

        // ── Alarm KPI'ları (tüm izleme türleri)
        if (alarms != null) {
            d.heading("Alarmlar", "Tüm izleme türleri · ekteki kesinti raporuyla aynı kaynak");
            d.kpis(alarmKpis(alarms));
        }

        // ── Ek duyurusu — ekin ADI ve İÇERİĞİ gövdede yazılı olmazsa çoğu okuyucu eki kaçırır.
        attachment(d, in.attachment(), alarms != null);

        // ── İzleme türlerine göre
        if (alarms != null && alarms.types() != null && !alarms.types().isEmpty()) typeBars(d, alarms.types());

        // ── En kötü domainler
        domains(d, rows, s, base, in.attachment() != null);

        // ── Haftanın alarmları
        if (alarms != null) incidents(d, alarms, ins, base);

        // ── Yaklaşan sertifika bitişleri (≤ 60 gün)
        certExpiries(d, rows, base);

        // ── Mevcut yan bölümler (içerik ve metin değişmedi)
        pageSpeed(d, in.pageSpeed());
        deployments(d, in.deployments());
        weakAlgo(d, in.weakAlgo());
        domainExpiry(d, in.domainExpiry());

        // ── Eylemler
        if (!base.isEmpty()) {
            List<Btn> btns = new ArrayList<>();
            btns.add(new Btn(reportUrl(base, ins), "Raporu uygulamada aç", Variant.PRIMARY));
            if (alarms != null && alarms.total() > 0 && ins != null) btns.add(new Btn(alarmsUrl(base, ins), "Haftanın alarmlarını aç", Variant.OUTLINE));
            d.buttons(null, btns);
        }

        // ── Alt bilgi: neden bu e-posta + ayar bağlantısı
        String settings = base.isEmpty() ? "" : base + "/?tab=admin&g_tab=teams";
        String whyCore = "Bu rapor, %s için haftalık erişilebilirlik raporu açık olduğundan gönderildi. Alıcılar takımın e-posta adresi ile "
                + "takımın PO, teknik sorumlu ve müdür kontaklarıdır.";
        String whyHtml = String.format(whyCore, "<strong style=\"color:" + MailTokens.FG + "\">" + MailKit.esc(team) + "</strong> ekibi")
                + (settings.isEmpty() ? "" : " Raporu kapatmak ya da alıcıları değiştirmek için " + MailKit.link(settings, "Takım ayarları") + ".");
        String whyText = String.format(whyCore, team + " ekibi")
                + (settings.isEmpty() ? "" : " Raporu kapatmak ya da alıcıları değiştirmek için Takım ayarları: " + settings);
        d.footerWhyCustom(whyHtml, whyText);
        d.footerMeta("Site Monitor — Otomatik Haftalık Rapor", in.generatedAt() == null ? null : "Oluşturuldu: " + in.generatedAt());
        return d.build();
    }

    // ── Hüküm ───────────────────────────────────────────────────────────────

    record Verdict(Tone tone, String badge, String title, String body) { }

    /**
     * Haftanın tek cümlelik hükmü — yalnız veriden: ölçüm yoksa "veri yok", kesinti yoksa "kesintisiz", aksi hâlde
     * ortalamanın renk bandı (≥99.90 sağlıklı · ≥99.00 dikkat · altı kritik — tablo renkleriyle aynı eşik).
     */
    static Verdict verdict(AvailabilitySummary s, int outages, long downMin, AvailabilityRow longest) {
        int noData = Math.max(0, s.domainCount() - s.withDataCount());
        String noDataNote = noData > 0 && s.withDataCount() > 0 ? " " + noData + " domainde bu hafta ölçüm yok." : "";
        if (s.withDataCount() == 0) {
            return new Verdict(Tone.NEUTRAL, "VERİ YOK", "Bu hafta ölçüm verisi yok",
                    s.domainCount() == 0 ? "Takımın izlenen domaini yok."
                            : "İzlenen " + s.domainCount() + " domain için bu pencerede HTTP kontrol kaydı bulunamadı; erişilebilirlik hesaplanamadı.");
        }
        if (s.downDomainCount() == 0) {
            return new Verdict(Tone.SUCCESS, "KESİNTİSİZ", "Kesintisiz hafta · " + pctText(s.avgAvailabilityPct()) + " erişilebilirlik",
                    "Bu hafta hiçbir domain kesinti yaşamadı (" + s.withDataCount() + " domain ölçüldü)." + noDataNote);
        }
        Tone t = band(s.avgAvailabilityPct());
        String badge = t == Tone.SUCCESS ? "SAĞLIKLI" : t == Tone.WARNING ? "DİKKAT" : "KRİTİK";
        String body = "Kesinti yaşayan domain: " + s.downDomainCount() + " / " + s.domainCount() + "."
                + (longest == null ? "" : " En uzun kesinti " + dur(longest.longestOutageMinutes()) + " — " + longest.domain() + ".")
                + noDataNote;
        return new Verdict(t, badge, pctText(s.avgAvailabilityPct()) + " erişilebilirlik · " + outages + " kesinti, toplam " + dur(downMin), body);
    }

    private static String preheader(AvailabilitySummary s, long downMin, Alarms a) {
        String head = s.avgAvailabilityPct() == null ? "Bu hafta ölçüm verisi yok"
                : "Ortalama erişilebilirlik " + pctText(s.avgAvailabilityPct()) + " · " + s.downDomainCount() + " domain kesinti yaşadı"
                  + (downMin > 0 ? " · toplam " + dur(downMin) : "");
        return head + (a == null ? "" : " · " + a.total() + " alarm");
    }

    // ── KPI'lar ─────────────────────────────────────────────────────────────

    static Kpi avgKpi(AvailabilitySummary s, Insights ins) {
        Double avg = s.avgAvailabilityPct();
        Double prev = ins == null ? null : ins.prevAvgAvailabilityPct();
        String delta = null, hint = null;
        Tone tone = null;
        if (ins != null) {
            hint = prev == null ? "geçen hafta: veri yok" : "geçen hafta " + pctText(prev);
            if (avg != null && prev != null) {
                double diff = Math.round((avg - prev) * 100.0) / 100.0;
                if (diff > 0) { delta = "▲ " + fmt2(diff) + " puan"; tone = Tone.SUCCESS; }
                else if (diff < 0) { delta = "▼ " + fmt2(-diff) + " puan"; tone = Tone.DESTRUCTIVE; }
                else { delta = "değişmedi"; tone = Tone.NEUTRAL; }
            }
        }
        return new Kpi("Ort. erişilebilirlik", pctText(avg), pctColor(avg), delta, tone, hint);
    }

    static Kpi downtimeKpi(long downMin, int outages) {
        return new Kpi("Toplam kesinti süresi", dur(downMin), downMin > 0 ? Tone.DESTRUCTIVE.strong : Tone.SUCCESS.strong, null, null,
                outages > 0 ? outages + " kesinti" : "kesinti yok");
    }

    static Kpi affectedKpi(AvailabilitySummary s, AvailabilityRow longest) {
        int noData = Math.max(0, s.domainCount() - s.withDataCount());
        String hint = longest != null ? "en uzun kesinti " + dur(longest.longestOutageMinutes())
                : noData > 0 ? noData + " domainde veri yok" : "tümü sorunsuz";
        return new Kpi("Kesinti yaşayan domain", s.downDomainCount() + " / " + s.domainCount(),
                s.downDomainCount() > 0 ? Tone.DESTRUCTIVE.strong : Tone.SUCCESS.strong, null, null, hint);
    }

    static Kpi certKpi(AvailabilitySummary s, List<AvailabilityRow> rows) {
        Integer days = s.nearestCertDays();
        String domain = null;
        if (days != null) {
            for (AvailabilityRow r : rows) if (days.equals(r.certDaysRemaining())) { domain = r.domain(); break; }
        }
        String value = days == null ? "—" : days < 0 ? "Doldu" : days + " gün";
        String hint = days != null && days < 0 ? Math.abs(days) + " gün önce doldu" + (domain == null ? "" : " · " + domain) : domain;
        return new Kpi("En yakın sertifika", value, daysColor(days), null, null, hint);
    }

    static List<Kpi> alarmKpis(Alarms a) {
        int diff = a.openedThisWeek() - a.openedPrevWeek();
        String delta = diff > 0 ? "▲ " + diff : diff < 0 ? "▼ " + Math.abs(diff) : "değişmedi";
        Tone dt = diff > 0 ? Tone.DESTRUCTIVE : diff < 0 ? Tone.SUCCESS : Tone.NEUTRAL;
        long typesWithAlarms = a.types() == null ? 0 : a.types().stream().filter(t -> t.alarmsOpened() > 0 || t.alarmsOpenNow() > 0).count();
        return List.of(
                new Kpi("Alarm", String.valueOf(a.total()), a.total() > 0 ? null : Tone.SUCCESS.strong, delta, dt,
                        "bu hafta açılan " + a.openedThisWeek() + " · geçen hafta " + a.openedPrevWeek()),
                new Kpi("Hâlâ açık", String.valueOf(a.stillOpen()), a.stillOpen() > 0 ? Tone.DESTRUCTIVE.strong : Tone.SUCCESS.strong, null, null,
                        a.carriedOver() > 0 ? a.carriedOver() + " alarm önceki haftadan devretti" : null),
                new Kpi("Ort. çözüm süresi", a.mttrMinutes() == null ? "—" : dur(a.mttrMinutes()), null, null, null,
                        a.resolved() > 0 ? "MTTR · " + a.resolved() + " alarm çözüldü" : "çözülen alarm yok"),
                new Kpi("Etkilenen hedef", String.valueOf(a.affectedTargets()), null, null, null,
                        typesWithAlarms > 0 ? typesWithAlarms + " izleme türünde" : null));
    }

    // ── Ek duyurusu ─────────────────────────────────────────────────────────

    private static void attachment(MailDoc d, AttachmentInfo att, boolean alarmsInBody) {
        if (att == null) return;
        String what = att.alarmCount() == 0
                ? "Bu hafta kesinti yaşanmadı; ek, kapsam ve yöntem notunu içerir."
                : att.alarmCount() + " alarmın tamamı — izleme türü bazında gruplanmış detay, "
                  + "kesinti zaman çizelgesi, gün/saat yoğunluğu ve erişilebilirlik tabloları."
                  + (att.stillOpenCount() > 0 ? " " + att.stillOpenCount() + " alarm hâlâ açık." : "");
        String scope = alarmsInBody
                ? "Ek ve yukarıdaki Alarmlar bölümü " + att.monitorTypeCount() + " izleme türünün alarmlarını kapsar. "
                  + "Erişilebilirlik bölümü ise yalnız sertifika envanterindeki domainlerin HTTP kontrollerini gösterir — iki bölümdeki sayılar bu yüzden birbirini tutmaz."
                : "Ek, bu e-postadan DAHA GENİŞ bir kapsamı raporlar: " + att.monitorTypeCount() + " izleme türünün alarmları. "
                  + "Bu gövde ise yalnız sertifika envanterindeki domainlerin HTTP erişilebilirliğini gösterir — iki yerdeki sayılar bu yüzden birbirini tutmaz.";
        d.alertHtml(Tone.INFO, "Ek: ayrıntılı kesinti raporu (PDF)",
                MailKit.mono(att.fileName()) + "<br>" + MailKit.esc(what) + "<br><span style=\"font-size:12px\">" + MailKit.esc(scope) + "</span>",
                "Ek: ayrıntılı kesinti raporu (PDF) — " + att.fileName() + "\n" + what + "\n" + scope);
    }

    // ── İzleme türlerine göre ───────────────────────────────────────────────

    private static void typeBars(MailDoc d, List<TypeRow> types) {
        List<Bar> bars = new ArrayList<>();
        for (TypeRow t : types) {
            List<String> cap = new ArrayList<>();
            cap.add(t.monitors() + " izleme");
            cap.add(String.format(Locale.forLanguageTag("tr"), "%,d", t.checks()) + " kontrol");
            if (t.alarmsOpened() > 0) cap.add(t.alarmsOpened() + " alarm açıldı");
            if (t.alarmsOpenNow() > 0) cap.add(t.alarmsOpenNow() + " şu an açık");
            if (t.alarmsOpened() == 0 && t.alarmsOpenNow() == 0) cap.add("alarm yok");
            if (t.alarmMinutes() > 0) cap.add("alarm süresi " + dur(t.alarmMinutes()));
            if (t.successRateDelta() != null && t.successRateDelta() != 0.0) {
                cap.add("geçen haftaya göre " + (t.successRateDelta() > 0 ? "▲ " : "▼ ") + fmt1(Math.abs(t.successRateDelta())) + " puan");
            }
            Double rate = t.successRate();
            bars.add(new Bar(t.label(), rate == null ? "veri yok" : fmt1(rate) + "%", rate == null ? MailTokens.MUTED : pctColor(rate),
                    rate, pctColor(rate), String.join(" · ", cap)));
        }
        d.heading("İzleme türlerine göre", "Kontrol başarı oranı ve alarmlar · alarm süresi, haftaya düşen alarm dakikalarının toplamıdır");
        d.bars(bars);
    }

    // ── En kötü domainler ───────────────────────────────────────────────────

    /** Görünüm sırası: kesinti yaşayanlar (erişilebilirlik artan) → ölçümü olmayanlar → sorunsuzlar. */
    static List<AvailabilityRow> domainOrder(List<AvailabilityRow> rows) {
        List<AvailabilityRow> out = new ArrayList<>(rows);
        out.sort(Comparator.comparingInt(WeeklyAvailabilityMail::rank)
                .thenComparing(r -> r.availabilityPct() == null ? Double.MAX_VALUE : r.availabilityPct()));
        return out;
    }

    private static int rank(AvailabilityRow r) {
        if (r.availabilityPct() == null) return 1;
        return r.outageCount() > 0 || r.availabilityPct() < 100.0 ? 0 : 2;
    }

    private static void domains(MailDoc d, List<AvailabilityRow> rows, AvailabilitySummary s, String base, boolean hasPdf) {
        if (rows.isEmpty()) return;
        List<AvailabilityRow> ordered = domainOrder(rows);
        List<AvailabilityRow> shown = ordered.subList(0, Math.min(TOP_DOMAINS, ordered.size()));
        // En düşük / en yüksek yalnız FARKLIYSA: hepsi aynı yüzdedeyken "en düşük X · en yüksek X" bilgi taşımaz.
        String desc = s.bestDomain() != null && s.worstDomain() != null && s.withDataCount() > 0
                && s.worstPct() != null && s.bestPct() != null && s.worstPct() < s.bestPct()
                ? "En düşük: " + s.worstDomain() + " (" + pctText(s.worstPct()) + ") · En yüksek: " + s.bestDomain() + " (" + pctText(s.bestPct()) + ")"
                : null;
        List<RankRow> rr = new ArrayList<>();
        StringBuilder t = new StringBuilder();
        for (AvailabilityRow r : shown) {
            String url = base.isEmpty() || r.domain() == null ? "" : domainUrl(base, r.domain());
            String outageLine = r.outageCount() > 0
                    ? r.outageCount() + " kesinti · toplam " + dur(r.downtimeMinutes()) + " · en uzun " + dur(r.longestOutageMinutes())
                    : r.availabilityPct() == null ? "Bu hafta ölçüm yok" : "Kesinti yok";
            String resp = r.avgMs() == null ? null : "Yanıt ort " + r.avgMs() + " ms · p95 " + (r.p95Ms() == null ? "—" : r.p95Ms() + " ms");
            String cert = r.certDaysRemaining() == null ? null : certText(r.certDaysRemaining());
            String line2Html = joinDot(resp == null ? null : MailKit.esc(resp),
                    cert == null ? null : "Sertifika " + MailKit.strong(cert, daysColor(r.certDaysRemaining())));
            String line2Text = joinDot(resp, cert == null ? null : "Sertifika " + cert);
            Badge badge = domainBadge(r);
            rr.add(new RankRow(url.isEmpty() ? MailKit.esc(r.domain()) : MailKit.link(url, r.domain()),
                    MailDoc.list(MailKit.esc(outageLine), line2Html.isEmpty() ? null : line2Html),
                    "<span style=\"color:" + pctColor(r.availabilityPct()) + "\">" + MailKit.esc(pctText(r.availabilityPct())) + "</span>", badge));
            t.append("- ").append(r.domain()).append(" — ").append(pctText(r.availabilityPct())).append(" [").append(badge.label()).append("] · ")
             .append(outageLine).append(line2Text.isEmpty() ? "" : " · " + line2Text).append(url.isEmpty() ? "" : " · Ayrıntı: " + url).append('\n');
        }
        d.heading("Domainler — en düşük erişilebilirlik üstte", desc);
        d.rankList(rr, t.toString());
        int hidden = ordered.size() - shown.size();
        if (hidden > 0) {
            int hOut = 0, hNo = 0, hOk = 0;
            for (AvailabilityRow r : ordered.subList(shown.size(), ordered.size())) {
                switch (rank(r)) { case 0 -> hOut++; case 1 -> hNo++; default -> hOk++; }
            }
            String breakdown = joinDot(hOut > 0 ? hOut + " kesintili" : null, hNo > 0 ? hNo + " ölçümsüz" : null, hOk > 0 ? hOk + " sorunsuz" : null);
            String where = hasPdf ? "Tam liste ekteki PDF'te." : "Tüm domainler Pano'da.";
            String dash = base.isEmpty() ? "" : base + "/?tab=dashboard";
            d.noteHtml("+" + hidden + " domain daha (" + MailKit.esc(breakdown) + "). " + MailKit.esc(where)
                            + (dash.isEmpty() ? "" : " " + MailKit.link(dash, "Pano'yu aç")),
                    "+" + hidden + " domain daha (" + breakdown + "). " + where + (dash.isEmpty() ? "" : " Pano: " + dash));
        }
    }

    private static Badge domainBadge(AvailabilityRow r) {
        if (r.availabilityPct() == null) return Badge.tint("VERİ YOK", Tone.NEUTRAL);
        if (r.outageCount() > 0 || r.availabilityPct() < 100.0) {
            return Badge.tint("KESİNTİ", r.availabilityPct() < 99.0 ? Tone.DESTRUCTIVE : Tone.WARNING);
        }
        return Badge.tint("SORUNSUZ", Tone.SUCCESS);
    }

    // ── Haftanın alarmları ──────────────────────────────────────────────────

    private static void incidents(MailDoc d, Alarms a, Insights ins, String base) {
        if (a.total() == 0) {
            d.alert(Tone.SUCCESS, "Bu hafta alarm yok", "Takımın izlemelerinde bu hafta açık olan alarm olmadı.");
            return;
        }
        List<IncidentRow> all = a.incidents() == null ? List.of() : a.incidents();
        if (all.isEmpty()) return;
        List<IncidentRow> shown = all.subList(0, Math.min(TOP_INCIDENTS, all.size()));
        List<RankRow> rr = new ArrayList<>();
        StringBuilder t = new StringBuilder();
        for (IncidentRow r : shown) {
            String url = base.isEmpty() || r.alertId() == null || r.alertId() <= 0 ? "" : base + "/?tab=incidents&incident=" + r.alertId();
            String what = joinDot(r.problem(), r.typeLabel());
            String when = "Başlangıç " + nz(r.startedAtLocal()) + (r.carriedOver() ? " (önceki haftadan devreden)" : "")
                    + " · Seviye " + nz(r.levelLabel());
            String note = r.note() == null || r.note().isBlank() ? null : "Not: " + clip(r.note().strip());
            Badge badge = r.stillOpen() ? Badge.tint("AÇIK", Tone.DESTRUCTIVE) : Badge.tint("ÇÖZÜLDÜ", Tone.SUCCESS);
            String target = r.target() == null || r.target().isBlank() ? "—" : r.target();
            rr.add(new RankRow(url.isEmpty() ? MailKit.esc(target) : MailKit.link(url, target),
                    MailDoc.list(MailKit.esc(what), MailKit.esc(when), note == null ? null : MailKit.esc(note)),
                    MailKit.esc(dur(r.durationMin())), badge));
            t.append("- ").append(target).append(" — ").append(what).append(" · ").append(when).append(" · Süre ").append(dur(r.durationMin()))
             .append(" [").append(badge.label()).append(']').append(note == null ? "" : " · " + note)
             .append(url.isEmpty() ? "" : " · Olay: " + url).append('\n');
        }
        d.heading("Haftanın alarmları — en uzun " + shown.size(), "Haftaya düşen süreye göre sıralı · saatler Türkiye saati");
        d.rankList(rr, t.toString());
        int hidden = a.total() - shown.size();
        if (hidden > 0) {
            String url = base.isEmpty() || ins == null ? "" : alarmsUrl(base, ins);
            d.noteHtml("+" + hidden + " alarm daha — tamamı ekteki PDF'te ve Alarm Geçmişi'nde."
                            + (url.isEmpty() ? "" : " " + MailKit.link(url, "Alarm Geçmişi'ni aç")),
                    "+" + hidden + " alarm daha — tamamı ekteki PDF'te ve Alarm Geçmişi'nde." + (url.isEmpty() ? "" : " Alarm Geçmişi: " + url));
        }
    }

    // ── Yaklaşan sertifika bitişleri ────────────────────────────────────────

    private static void certExpiries(MailDoc d, List<AvailabilityRow> rows, String base) {
        List<AvailabilityRow> soon = new ArrayList<>();
        for (AvailabilityRow r : rows) if (r.certDaysRemaining() != null && r.certDaysRemaining() <= CERT_WINDOW_DAYS) soon.add(r);
        if (soon.isEmpty()) return;
        soon.sort(Comparator.comparingInt(AvailabilityRow::certDaysRemaining));
        List<AvailabilityRow> shown = soon.subList(0, Math.min(TOP_CERTS, soon.size()));
        List<RankRow> rr = new ArrayList<>();
        StringBuilder t = new StringBuilder();
        for (AvailabilityRow r : shown) {
            int days = r.certDaysRemaining();
            String url = base.isEmpty() ? "" : domainUrl(base, r.domain());
            String state = days < 0 ? "süresi doldu" : days <= 15 ? "kritik" : days <= 30 ? "yakın" : "izlemede";
            Tone tone = days <= 15 ? Tone.DESTRUCTIVE : days <= 30 ? Tone.WARNING : Tone.NEUTRAL;
            String value = days < 0 ? "Doldu" : days + " gün";
            String meta = days < 0 ? Math.abs(days) + " gün önce doldu" : "Sertifikanın bitmesine " + days + " gün var";
            rr.add(new RankRow(url.isEmpty() ? MailKit.esc(r.domain()) : MailKit.link(url, r.domain()), MailDoc.list(MailKit.esc(meta)),
                    "<span style=\"color:" + daysColor(days) + "\">" + MailKit.esc(value) + "</span>",
                    Badge.tint(state.toUpperCase(Locale.forLanguageTag("tr")), tone)));
            t.append("- ").append(r.domain()).append(" — ").append(value).append(" (").append(state).append(')')
             .append(url.isEmpty() ? "" : " · " + url).append('\n');
        }
        d.heading("Yaklaşan sertifika bitişleri", "Önümüzdeki " + CERT_WINDOW_DAYS + " gün · " + soon.size() + " domain");
        d.rankList(rr, t.toString());
        int hidden = soon.size() - shown.size();
        if (hidden > 0) {
            String url = base.isEmpty() ? "" : base + "/?tab=warnings";
            d.noteHtml("+" + hidden + " sertifika daha." + (url.isEmpty() ? "" : " " + MailKit.link(url, "Dikkat gerektiren sertifikalar")),
                    "+" + hidden + " sertifika daha." + (url.isEmpty() ? "" : " Dikkat gerektiren sertifikalar: " + url));
        }
    }

    // ── Yan bölümler (2026-09 öncesi içerik; metinler testlerle kilitli) ────

    private static void pageSpeed(MailDoc d, PageSpeedWeekly ps) {
        if (ps == null || ps.slowest() == null || ps.slowest().isEmpty()) return;
        List<RankRow> rr = new ArrayList<>();
        StringBuilder t = new StringBuilder();
        for (PageSpeedWeeklyRow r : ps.slowest()) {
            String load = r.avgLoadMs() != null ? r.avgLoadMs() + " ms" : "—";
            String trend = "—";
            String trendColor = MailTokens.MUTED;
            if (r.avgLoadMs() != null && r.prevAvgLoadMs() != null && r.prevAvgLoadMs() > 0) {
                long diff = r.avgLoadMs() - r.prevAvgLoadMs();
                long pct = Math.round(100.0 * diff / r.prevAvgLoadMs());
                if (pct > 0) { trend = "▲ %" + pct + " yavaşladı"; trendColor = Tone.DESTRUCTIVE.strong; }
                else if (pct < 0) { trend = "▼ %" + Math.abs(pct) + " hızlandı"; trendColor = Tone.SUCCESS.strong; }
                else { trend = "değişmedi"; }
            }
            String breach = r.breachedChecks() > 0 ? r.breachedChecks() + " ölçüm" : "—";
            rr.add(new RankRow(MailKit.esc(r.name()),
                    MailDoc.list("Geçen haftaya göre " + MailKit.strong(trend, trendColor),
                            "Eşik aşımı: " + (r.breachedChecks() > 0 ? MailKit.strong(breach, Tone.DESTRUCTIVE.strong) : "—")),
                    MailKit.esc(load), r.breachedChecks() > 0 ? Badge.tint("EŞİK AŞILDI", Tone.DESTRUCTIVE) : null));
            t.append("- ").append(r.name()).append(" · Ort. yükleme: ").append(load).append(" · Geçen haftaya göre: ").append(trend)
             .append(" · Eşik aşımı: ").append(breach).append('\n');
        }
        d.heading("Sayfa Hızı — en yavaş " + ps.slowest().size() + " sayfa"
                + (ps.breachedMonitorCount() > 0 ? " · " + ps.breachedMonitorCount() + " izlemede eşik aşıldı" : ""));
        d.rankList(rr, t.toString());
        d.note("Bu bölüm PERFORMANSI anlatır, kesintiyi değil: yavaş bir sayfa yukarıdaki erişilebilirlik yüzdesini düşürmez. "
                + "Ölçüm sunucudan çekilen HTML ve alt kaynaklarla yapılır; tarayıcı çalıştırılmadığı için JavaScript ile sonradan "
                + "yüklenen kaynaklar sayıma girmez.");
    }

    /** Sürüm &amp; Dağıtım (E2) — geri alma varsa ton kırmızı. TR ana satır + EN alt satır. */
    private static void deployments(MailDoc d, DeploymentWeekly dep) {
        if (dep == null) return;
        String range = (dep.fromVersion() != null && dep.toVersion() != null && !dep.fromVersion().equals(dep.toVersion()))
                ? " (v" + dep.fromVersion() + " → v" + dep.toVersion() + ")"
                : (dep.toVersion() != null ? " (v" + dep.toVersion() + ")" : "");
        String tr = dep.deployments() == 0
                ? "Bu hafta dağıtım yapılmadı; " + dep.restarts() + " yeniden başlatma, " + dep.rollbacks() + " geri alma"
                : "Bu hafta " + dep.deployments() + " dağıtım" + range + ", " + dep.restarts()
                  + " yeniden başlatma, " + dep.rollbacks() + " geri alma";
        String en = dep.deployments() == 0
                ? "No deployments this week; " + dep.restarts() + " restart(s), " + dep.rollbacks() + " rollback(s)"
                : dep.deployments() + " deployment(s)" + range + ", " + dep.restarts()
                  + " restart(s), " + dep.rollbacks() + " rollback(s) this week";
        bilingual(d, dep.rollbacks() > 0 ? Tone.DESTRUCTIVE : Tone.NEUTRAL, "Sürüm & Dağıtım", tr, en);
    }

    /** Zayıf algoritma (2026-09-12): "temiz" raporun da kanıtı olsun — "0 (tarandı: 212)" satırı görünür. */
    private static void weakAlgo(MailDoc d, WeakAlgoWeekly weak) {
        if (weak == null) return;
        String tr = weak.weak() == 0
                ? "Zayıf algoritmalı sertifika yok (tarandı: " + weak.scanned() + " alan)"
                : weak.weak() + " sertifika zayıf imza/anahtar kullanıyor (tarandı: " + weak.scanned() + " alan) — yenileme planı gerekli";
        String en = weak.weak() == 0
                ? "No weak-algorithm certificates (" + weak.scanned() + " domains scanned)"
                : weak.weak() + " certificate(s) use a weak signature/key (" + weak.scanned() + " domains scanned) — renewal plan needed";
        bilingual(d, weak.weak() > 0 ? Tone.DESTRUCTIVE : Tone.SUCCESS, "Zayıf Algoritma", tr, en);
    }

    /**
     * Alan adı (registrar) bitişleri (2026-09-22, madde G): sertifikadan AYRI vade sınıfı — kayıt dolarsa site kaybolur.
     * Boş pencere de raporlanır ("90 günde biten yok, izlenen N"): sessizlik "bakılmadı" ile karışmasın.
     */
    private static void domainExpiry(MailDoc d, DomainExpiryWeekly dom) {
        if (dom == null) return;
        boolean any = dom.rows() != null && !dom.rows().isEmpty();
        Tone tone = any && dom.rows().stream().anyMatch(r -> r.daysRemaining() != null && r.daysRemaining() <= 30) ? Tone.DESTRUCTIVE
                : any ? Tone.WARNING : Tone.SUCCESS;
        String head = any
                ? dom.rows().size() + " alan adı önümüzdeki " + dom.windowDays() + " günde doluyor (izlenen: " + dom.monitorCount() + ")"
                : "Önümüzdeki " + dom.windowDays() + " günde biten alan adı yok (izlenen: " + dom.monitorCount() + ")";
        String unlocked = dom.unlockedCount() > 0 ? " · " + dom.unlockedCount() + " alan adında transfer kilidi YOK" : "";
        // İngilizce alt satır — kardeş bantlarla (Sürüm & Dağıtım, Zayıf Algoritma) aynı iki dilli düzen
        String headEn = any
                ? dom.rows().size() + (dom.rows().size() == 1 ? " domain expires" : " domains expire") + " within the next " + dom.windowDays() + " days (" + dom.monitorCount() + " monitored)"
                : "No domain expires within the next " + dom.windowDays() + " days (" + dom.monitorCount() + " monitored)";
        String unlockedEn = dom.unlockedCount() > 0 ? " · " + dom.unlockedCount() + (dom.unlockedCount() == 1 ? " domain has" : " domains have") + " no transfer lock" : "";
        d.heading("Alan Adı Bitişleri");
        bilingual(d, tone, null, head + unlocked, headEn + unlockedEn);
        if (!any) return;
        List<RankRow> rr = new ArrayList<>();
        StringBuilder t = new StringBuilder();
        for (DomainExpiryWeeklyRow r : dom.rows()) {
            Integer dd = r.daysRemaining();
            String dc = dd == null ? MailTokens.MUTED : dd < 0 ? Tone.DESTRUCTIVE.text : dd <= 7 ? Tone.DESTRUCTIVE.strong
                    : dd <= 30 ? Tone.WARNING.strong : MailTokens.FG;
            String dTxt = dd == null ? "—" : dd < 0 ? Math.abs(dd) + " gün önce doldu" : dd + " gün";
            String lock = r.transferLock() == null ? "—" : switch (r.transferLock()) {
                case "BOTH" -> "registrar+registry"; case "SERVER" -> "registry"; case "CLIENT" -> "registrar";
                case "NONE" -> "YOK"; default -> "doğrulanamadı"; };
            String plan = r.plannedAt() == null ? "—" : (r.planOverdue() ? "GECİKMİŞ " : "") + r.plannedAt();
            String expiry = r.expiryDate() == null ? "—" : r.expiryDate().length() >= 10 ? r.expiryDate().substring(0, 10) : r.expiryDate();
            String registrar = r.registrar() == null ? "—" : r.registrar();
            boolean noLock = "NONE".equals(r.transferLock());
            Badge badge = dd == null ? null : dd < 0 ? Badge.solid("DOLDU", Tone.DESTRUCTIVE) : dd <= 7 ? Badge.tint("KRİTİK", Tone.DESTRUCTIVE)
                    : dd <= 30 ? Badge.tint("YAKIN", Tone.WARNING) : null;
            rr.add(new RankRow(MailKit.esc(r.domain()),
                    MailDoc.list(dd != null && dd < 0 ? MailKit.strong(dTxt, dc) : null,
                            "Bitiş " + MailKit.esc(expiry) + " · Registrar " + MailKit.esc(registrar),
                            "Transfer kilidi: " + (noLock ? MailKit.strong(lock, Tone.DESTRUCTIVE.strong) : MailKit.esc(lock))
                                    + " · Plan: " + (r.planOverdue() ? MailKit.strong(plan, Tone.DESTRUCTIVE.strong) : MailKit.esc(plan))),
                    "<span style=\"color:" + dc + "\">" + MailKit.esc(dd != null && dd < 0 ? "Doldu" : dTxt) + "</span>", badge));
            t.append("- ").append(r.domain()).append(" · Kalan: ").append(dTxt).append(" · Bitiş: ").append(expiry).append(" · Registrar: ")
             .append(registrar).append(" · Kilit: ").append(lock).append(" · Plan: ").append(plan).append('\n');
        }
        d.rankList(rr, t.toString());
    }

    /** İki dilli tonlu bant (TR ana satır + EN alt satır). */
    private static void bilingual(MailDoc d, Tone tone, String title, String tr, String en) {
        d.alertHtml(tone, title == null ? null : MailKit.esc(title),
                MailKit.esc(tr) + "<br><span style=\"font-size:12px;line-height:18px\">" + MailKit.esc(en) + "</span>",
                (title == null ? "" : title + ": ") + tr + "\n" + en);
    }

    // ── Bağlantılar (CANLI taban adres; boşsa bağlantı üretilmez) ───────────

    /** Domainin sertifika penceresi — alarm e-postalarının SSL derin bağlantısıyla aynı biçim ({@code open=cert}). */
    static String domainUrl(String base, String domain) {
        return base + "/?tab=dashboard&domain=" + URLEncoder.encode(domain, StandardCharsets.UTF_8) + "&open=cert";
    }

    /** Haftalık Raporlar ekranında bu hafta (+ takım) — uygulamanın w_ önekli derin bağlantısı. */
    static String reportUrl(String base, Insights ins) {
        if (ins == null) return base + "/?tab=weeklyreports";
        return base + "/?tab=weeklyreports&w_year=" + ins.isoYear() + "&w_week=" + ins.isoWeek()
                + (ins.teamId() == null ? "" : "&w_team=" + ins.teamId());
    }

    /** Alarm Geçmişi "tümü" görünümü, haftanın günleriyle (açılış anına göre) + takım süzgeci. */
    static String alarmsUrl(String base, Insights ins) {
        return base + "/?tab=alerthistory&view=all&from=" + nz(ins.weekStartDay()) + "&to=" + nz(ins.weekEndDay())
                + (ins.teamId() == null ? "" : "&team=" + ins.teamId());
    }

    private static String trimBase(String base) {
        return base == null || base.isBlank() ? "" : base.strip().replaceAll("/+$", "");
    }

    // ── Biçim yardımcıları ──────────────────────────────────────────────────

    /** Erişilebilirlik bandı: ≥99.9 yeşil, ≥99 sarı, altı kırmızı (tablo renkleriyle aynı eşik). */
    static Tone band(Double pct) {
        if (pct == null) return Tone.NEUTRAL;
        if (pct >= 99.9) return Tone.SUCCESS;
        if (pct >= 99.0) return Tone.WARNING;
        return Tone.DESTRUCTIVE;
    }

    /** Availability %'sine göre renk: ≥99.9 yeşil, ≥99 amber, <99 kırmızı, null gri. */
    static String pctColor(Double pct) {
        return pct == null ? MailTokens.MUTED : band(pct).strong;
    }

    static String pctText(Double pct) {
        if (pct == null) return "veri yok";
        return String.format(Locale.US, "%.2f%%", pct);
    }

    /** Kalan güne göre renk: doldu/≤30 kırmızı, ≤60 sarı, üstü ikincil. */
    static String daysColor(Integer days) {
        if (days == null) return MailTokens.MUTED;
        if (days <= 30) return Tone.DESTRUCTIVE.strong;
        if (days <= 60) return Tone.WARNING.strong;
        return MailTokens.MUTED;
    }

    private static String certText(int days) {
        return days < 0 ? Math.abs(days) + " gün önce doldu" : days + " gün";
    }

    /** Süre — sıfır "0 dk"; aksi hâlde ekteki PDF'in biçimi ("2g 4sa 12dk"). */
    static String dur(long minutes) {
        return minutes <= 0 ? "0 dk" : WeeklyOutageReportService.humanDuration(minutes);
    }

    private static String fmt2(double v) {
        return String.format(Locale.US, "%.2f", v);
    }

    private static String fmt1(double v) {
        return String.format(Locale.US, "%.1f", v);
    }

    private static String clip(String s) {
        return s.length() <= NOTE_MAX ? s : s.substring(0, NOTE_MAX - 1).stripTrailing() + "…";
    }

    private static String joinDot(String... parts) {
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p == null || p.isBlank()) continue;
            if (sb.length() > 0) sb.append(" · ");
            sb.append(p);
        }
        return sb.toString();
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
