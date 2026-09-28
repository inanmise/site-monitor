package com.sitemonitor.service.mail;

import com.sitemonitor.service.mail.MailKit.Badge;
import com.sitemonitor.service.mail.MailKit.Btn;
import com.sitemonitor.service.mail.MailKit.Cell;
import com.sitemonitor.service.mail.MailKit.Col;
import com.sitemonitor.service.mail.MailKit.Item;
import com.sitemonitor.service.mail.MailKit.Kpi;
import com.sitemonitor.service.mail.MailKit.ListItem;
import com.sitemonitor.service.mail.MailKit.Segment;
import com.sitemonitor.service.mail.MailKit.Variant;
import com.sitemonitor.service.mail.MailTokens.Tone;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * AYLIK SERTİFİKA ENVANTERİ RAPORU e-postası (yeniden tasarım 2026-09-28) — YALNIZ {@link MailDoc}/{@link MailKit}
 * ile kurulur (Outlook-güvenli, mobil duyarlı, sol şerit YOK, serbest renk YOK). Saf fonksiyon: Spring bağımlılığı
 * yok; veri {@link Report} olarak dışarıdan gelir ({@code service.report.CertInventorySummary} üretir), CANLI taban
 * adres ve damga çağırandan ({@code EmailNotificationService}). Galeri ve sözleşme testi aynı kurucuyu çağırır.
 *
 * <p><b>Okuma sırası</b> — "şimdi ne yapmalıyım?" sorusundan "neden, ne kadar?" sorusuna:
 * <ol>
 *   <li>durum rozeti + tek satırlık hüküm ("412 aktif sertifika · 9 tanesi 30 gün içinde bitiyor · 3 hijyen bulgusu");</li>
 *   <li>KPI kutuları (toplam, 30/14/7 gün içinde, süresi dolmuş, hata, veri yok, hijyen; geçen aya göre değişim çipi)
 *       — paylaşılan {@code MailKit.kpis} ızgarası, telefonda 2×4;</li>
 *   <li>kalan süreye göre parçalı durum çubuğu (td hücreleri; görsel/gradyan yok) + adet/yüzde lejantı;</li>
 *   <li>"Önümüzdeki 30 gün": süresi dolmuş + 30 gün içinde bitenler, aciliyet haplarıyla, sertifika penceresine derin
 *       bağlantı ({@code ?tab=dashboard&domain=…&open=cert}) — telefonda 2 sütunlu istif satır;</li>
 *   <li>envanter hijyeni: grup başına açıklama + önerilen aksiyon + ilk kayıtlar (grup sayısına göre 5–10);</li>
 *   <li>sıradaki yenilemeler, takıma ve sağlayıcıya (CA) göre kırılım, ekler, CTA ve şeffaflık alt bilgisi.</li>
 * </ol>
 *
 * <p>Uzun listeler kırpılır ve "+N daha" ile söylenir; tam liste EKTEDİR (CSV tam liste + PDF özet). Gövde boyutu bu yüzden
 * envanter büyüklüğünden bağımsız kalır (Gmail 102 KB'ta kırpar). Her dinamik değer kaçırılır.
 */
public final class CertInventoryMail {

    private CertInventoryMail() { }

    public static final String KICKER = "Aylık Sertifika Envanteri";
    /**
     * Gövdedeki sertifika satırı bütçesi: "Önümüzdeki 30 gün" en fazla bu kadar satır gösterir (gerisi "+N daha", tamamı
     * ekte); "Sıradaki yenilemeler" yalnız KALAN bütçeyle çizilir — acil satırlar kırpılırken 30+ günlük satır gösterilmez.
     * Bütçe gövdeyi Gmail'in 102 KB kırpma sınırının altında tutar (kapı: CertInventoryMailTest.sizeBudget).
     */
    public static final int UPCOMING_CAP = 20;
    /** "Sıradaki yenilemeler" (30 günden sonra) tavanı — kalan bütçe bundan küçükse o kadar. */
    public static final int LATER_CAP = 8;
    /** Hijyen grubu başına listelenen en fazla kayıt (InventoryHygieneService.MAX_PER_GROUP ile aynı). */
    public static final int SAMPLE_CAP = 10;
    /**
     * Hijyen örneklerinin TOPLAM bütçesi: grup başına tavan = max(5, min(10, bütçe / grup sayısı)) — 1–3 grupta 10'ar
     * (önceki rapor gibi), 5 grupta 6'şar. Tam liste PDF ekinde; gövde Gmail 102 KB sınırının altında kalır.
     */
    public static final int HYGIENE_BUDGET = 30;
    /** Hijyen ayrıntısı gövdede en fazla bu kadar karakter (uzun hata metni; tamamı PDF'te). */
    public static final int DETAIL_MAX = 120;
    public static final int TEAM_CAP = 8;
    public static final int ISSUER_CAP = 6;

    /**
     * Gövde bayt tavanı. Gmail ~102 KB'ı aşan HTML'i KIRPAR ("[Mesaj kırpıldı]") — alt bilgi, CTA ve ekler kartı
     * kaybolur. Tavanlar tipik envanteri rahat sığdırır; ama alan adı/takım/hata metni uzunluğu sınırsız olduğundan
     * kurucu çıktıyı ÖLÇER ve gerekirse daha sıkı tavanlarla yeniden çizer ({@link #LEVELS}). "+N daha" notları her
     * düzeyde doğrudur (tavan akar); tam liste PDF ekindedir.
     */
    public static final int MAX_BYTES = 95_000;

    /** Liste tavanları (bir sıkılık düzeyi). {@code hygieneFloor} = grup başına en az örnek. */
    record Caps(int upcoming, int later, int hygieneBudget, int hygieneFloor, int detailMax, int teams, int issuers) { }

    /** Sırayla denenen düzeyler: tam → sıkı → asgari. */
    static final List<Caps> LEVELS = List.of(
            new Caps(UPCOMING_CAP, LATER_CAP, HYGIENE_BUDGET, 5, DETAIL_MAX, TEAM_CAP, ISSUER_CAP),
            new Caps(12, 5, 15, 3, 100, 6, 4),
            new Caps(8, 3, 10, 2, 80, 5, 3));

    // ── Veri modeli (gövde VE PDF eki aynı özetten beslenir → sayılar birbirini tutar) ──

    /**
     * Tek sertifika satırı. {@code expiry} kurum saatinde (Europe/Istanbul) "gg.aa.yyyy"; {@code status} kısa Türkçe
     * durum ("geçerli", "hata", "zincir kırık" …); {@code team} sorumlu takımın adı (null = atanmamış).
     */
    public record Cert(String domain, String team, Integer tier, Integer daysLeft, String expiry, String issuer, String status) { }

    /** Aktif envanterin kalan süre kovaları (ayrık; toplamları aktif kayıt sayısıdır). {@code unknown} = tarih yok. */
    public record Buckets(int expired, int days0to7, int days8to14, int days15to30, int days31to90, int over90, int unknown) {
        public static Buckets empty() { return new Buckets(0, 0, 0, 0, 0, 0, 0); }
        public int within7() { return days0to7; }
        public int within14() { return days0to7 + days8to14; }
        public int within30() { return days0to7 + days8to14 + days15to30; }
        public int total() { return expired + days0to7 + days8to14 + days15to30 + days31to90 + over90 + unknown; }
    }

    /** Hijyen bulgusu (alan adı + Türkçe ayrıntı). */
    public record Finding(String domain, String detail) { }

    /** Hijyen grubu — {@code findings} TAM liste olabilir; gövde ilk {@value #SAMPLE_CAP}'u gösterir, PDF hepsini. */
    public record FindingGroup(String key, String title, int total, List<Finding> findings) {
        public FindingGroup {
            findings = findings == null ? List.of() : List.copyOf(findings);
        }
    }

    /** Kırılım satırı (takım/sağlayıcı): aktif sertifika, acil (süresi dolmuş + ≤30 gün), okunamayan. */
    public record Breakdown(String label, int total, int urgent, int errors) { }

    /** Ek dosya: ad + içerik açıklaması. */
    public record Attachment(String fileName, String description) { }

    /**
     * Raporun tamamı. Listeler TAM (kırpılmamış) gelir; kırpma sunum katmanındadır. {@code prevTotal}/{@code prevFindings}
     * geçen ayın rapor kaydından ({@code cert_inventory_report_log}); kayıt yoksa null → değişim çipi çizilmez.
     */
    public record Report(String monthLabel, String scope, int ownerTeams,
                         int active, int passive, int total,
                         Buckets buckets, int errors,
                         List<Cert> upcoming, List<Cert> later,
                         List<FindingGroup> findings, int findingTotal,
                         List<Breakdown> byTeam, List<Breakdown> byIssuer,
                         List<Attachment> attachments,
                         Integer prevTotal, Integer prevFindings) {
        public Report {
            buckets = buckets == null ? Buckets.empty() : buckets;
            upcoming = upcoming == null ? List.of() : List.copyOf(upcoming);
            later = later == null ? List.of() : List.copyOf(later);
            findings = findings == null ? List.of() : List.copyOf(findings);
            byTeam = byTeam == null ? List.of() : List.copyOf(byTeam);
            byIssuer = byIssuer == null ? List.of() : List.copyOf(byIssuer);
            attachments = attachments == null ? List.of() : List.copyOf(attachments);
        }

        public Report withAttachments(List<Attachment> a) {
            return new Report(monthLabel, scope, ownerTeams, active, passive, total, buckets, errors, upcoming, later,
                    findings, findingTotal, byTeam, byIssuer, a, prevTotal, prevFindings);
        }

        public Report withPrevious(Integer lastMonthTotal, Integer lastMonthFindings) {
            return new Report(monthLabel, scope, ownerTeams, active, passive, total, buckets, errors, upcoming, later,
                    findings, findingTotal, byTeam, byIssuer, attachments, lastMonthTotal, lastMonthFindings);
        }
    }

    // ── Kurucu ───────────────────────────────────────────────────────────────

    /**
     * @param baseUrl     CANLI uygulama taban adresi ({@code site.monitor.app.base-url}); http(s) değilse bağlantı ÜRETİLMEZ
     *                    (şemasız göreli bağlantı e-postada kırık açılır)
     * @param generatedAt alt bilgi damgası (kurum saati), null olabilir
     */
    public static MailDoc.Mail build(Report r, String baseUrl, String generatedAt) {
        MailDoc.Mail m = null;
        for (Caps c : LEVELS) {
            m = render(r, baseUrl, generatedAt, c);
            if (m.html().getBytes(StandardCharsets.UTF_8).length <= MAX_BYTES) return m;
        }
        return m;      // asgari düzey de aşıyorsa (pratikte olmaz) en küçüğü
    }

    static MailDoc.Mail render(Report r, String baseUrl, String generatedAt, Caps caps) {
        String base = baseUrl == null ? "" : baseUrl.trim().replaceAll("/+$", "");
        boolean links = base.startsWith("http://") || base.startsWith("https://");
        String month = nz(r.monthLabel(), "—");
        String verdict = verdict(r);

        // darkCanvas: sistem koyu temadaysa DIŞ zemin koyulaşır, kart açık kalır (haftalık raporla aynı aile kararı).
        MailDoc d = MailDoc.create("[Site Monitor] Aylık Sertifika Envanteri — " + month).wide().darkCanvas()
                .preheader(month + " · " + verdict)
                .kicker(KICKER)
                .badges(statusBadge(r), Badge.outline(nz(r.scope(), "Tüm envanter")))
                .title(month + " · Sertifika Envanteri", verdict);
        d.note("Kapsam: " + nz(r.scope(), "tüm envanter") + " · " + r.total() + " kayıt (" + r.active() + " aktif, "
                + r.passive() + " pasif; silinmişler hariç) · " + r.ownerTeams() + " sahip takım.");
        d.paragraphHtml("<strong>Sayın Sertifika Ekibi,</strong>", "Sayın Sertifika Ekibi,");
        d.paragraphHtml("Aşağıda sertifika envanterinin <strong>" + esc(month) + "</strong> dönemi özeti yer almaktadır. "
                        + "Eksik, hatalı veya güncel olmayan kayıtlar varsa lütfen envanterden güncelleyiniz.",
                "Aşağıda sertifika envanterinin " + month + " dönemi özeti yer almaktadır. "
                        + "Eksik, hatalı veya güncel olmayan kayıtlar varsa lütfen envanterden güncelleyiniz.");

        kpis(d, r);
        if (r.active() == 0) {
            d.alert(Tone.INFO, "Envanterde aktif sertifika kaydı yok.",
                    "Pasif kayıtlar sayılır ama izlenmez; kalan süre ve hijyen bölümleri yalnız aktif kayıtlar için hesaplanır.");
        } else {
            distribution(d, r);
            upcoming(d, r, base, links, caps);
        }
        hygiene(d, r, base, links, caps);
        if (r.active() > 0) later(d, r, base, links, caps);
        breakdowns(d, r, caps);
        attachments(d, r);
        if (links) {
            d.buttons(null, List.of(
                    new Btn(base + "/?tab=domains", "Envanteri uygulamada aç", Variant.PRIMARY),
                    new Btn(base + "/?tab=forecast", "Vade Takvimi'ni aç", Variant.OUTLINE)));
        }
        footer(d, base, links, generatedAt);
        return d.build();
    }

    // ── Başlık ───────────────────────────────────────────────────────────────

    /** Tek satırlık hüküm — başlığın alt satırı ve gelen kutusu önizlemesi. Uydurma sayı yok: hepsi özetten. */
    public static String verdict(Report r) {
        if (r.active() == 0) return "Envanterde aktif sertifika yok";
        Buckets b = r.buckets();
        List<String> p = new ArrayList<>();
        p.add(r.active() + " aktif sertifika");
        if (b.expired() > 0) p.add(b.expired() + " tanesinin süresi dolmuş");
        p.add(b.within30() > 0 ? b.within30() + " tanesi 30 gün içinde bitiyor" : "30 gün içinde biten yok");
        p.add(r.findingTotal() > 0 ? r.findingTotal() + " hijyen bulgusu" : "hijyen bulgusu yok");
        return String.join(" · ", p);
    }

    private static Badge statusBadge(Report r) {
        Buckets b = r.buckets();
        if (b.expired() > 0 || b.within7() > 0) return Badge.solid("AKSİYON GEREKLİ", Tone.DESTRUCTIVE);
        if (b.within30() > 0 || r.findingTotal() > 0 || r.errors() > 0) return Badge.tint("TAKİP GEREKLİ", Tone.WARNING);
        return Badge.tint("SORUNSUZ", Tone.SUCCESS);
    }

    // ── KPI kutuları ─────────────────────────────────────────────────────────

    private static void kpis(MailDoc d, Report r) {
        Buckets b = r.buckets();
        // Paylaşılan KPI ızgarası (MailKit.kpis — haftalık raporla aynı kutu). Her kutuda ipucu satırı var → 4'lü satırda
        // yükseklikler eşit kalır. Değişim çipi YALNIZ geçen ayın kaydı varsa (uydurma karşılaştırma yok).
        Integer pt = r.prevTotal(), pf = r.prevFindings();
        d.kpis(List.of(
                new Kpi("Toplam kayıt", String.valueOf(r.total()), null,
                        delta(r.total(), pt), Tone.NEUTRAL, r.active() + " aktif · " + r.passive() + " pasif"),
                new Kpi("30 gün içinde", String.valueOf(b.within30()), toned(b.within30(), Tone.WARNING.strong), null, null, "bitecek sertifika"),
                new Kpi("14 gün içinde", String.valueOf(b.within14()), toned(b.within14(), Tone.WARNING.strong), null, null, "bitecek sertifika"),
                new Kpi("7 gün içinde", String.valueOf(b.within7()), toned(b.within7(), Tone.DESTRUCTIVE.strong), null, null, "bitecek sertifika"),
                new Kpi("Süresi dolmuş", String.valueOf(b.expired()), toned(b.expired(), Tone.DESTRUCTIVE.text), null, null, "yenileme gecikmiş"),
                new Kpi("Hata / erişilemez", String.valueOf(r.errors()), toned(r.errors(), Tone.DESTRUCTIVE.strong), null, null, "sertifika okunamadı"),
                new Kpi("Veri yok", String.valueOf(b.unknown()), toned(b.unknown(), MailTokens.FG), null, null, "bitiş tarihi bilinmiyor"),
                new Kpi("Hijyen bulgusu", String.valueOf(r.findingTotal()), toned(r.findingTotal(), Tone.WARNING.strong),
                        delta(r.findingTotal(), pf), pf == null || r.findingTotal() == pf ? Tone.NEUTRAL
                                : r.findingTotal() < pf ? Tone.SUCCESS : Tone.WARNING,
                        r.findingTotal() == 0 ? "envanter temiz" : r.findings().size() + " grupta")));
    }

    /** Geçen aya göre değişim çipi: "▲ 4 · geçen ay 222" / "değişmedi"; geçen ay bilinmiyorsa null (çip yok). */
    public static String delta(int now, Integer prev) {
        if (prev == null) return null;
        int diff = now - prev;
        if (diff == 0) return "değişmedi · geçen ay " + prev;
        return (diff > 0 ? "▲ " : "▼ ") + Math.abs(diff) + " · geçen ay " + prev;
    }

    /** Sıfır değer ikincil renkte (göz dolu kutuya gitsin); dolu değer anlam renginde. */
    private static String toned(int n, String color) {
        return n > 0 ? color : MailTokens.MUTED;
    }

    // ── Dağılım çubuğu ───────────────────────────────────────────────────────

    private static void distribution(MailDoc d, Report r) {
        Buckets b = r.buckets();
        List<Segment> segs = List.of(
                new Segment("Süresi dolmuş", b.expired(), Tone.DESTRUCTIVE.text),
                new Segment("0–7 gün", b.days0to7(), Tone.DESTRUCTIVE.strong),
                new Segment("8–30 gün", b.days8to14() + b.days15to30(), Tone.WARNING.strong),
                new Segment("31–90 gün", b.days31to90(), Tone.INFO.strong),
                new Segment("90 gün üstü", b.over90(), Tone.SUCCESS.strong),
                new Segment("Tarih yok", b.unknown(), Tone.NEUTRAL.strong));
        String bar = MailKit.segmentBar(segs);
        if (bar.isEmpty()) return;
        int total = b.total();
        StringBuilder t = new StringBuilder("Dağılım: ");
        boolean first = true;
        for (Segment s : segs) {
            if (s.count() <= 0) continue;
            t.append(first ? "" : " · ").append(s.label()).append(' ').append(s.count())
             .append(" (").append(MailKit.segmentPct(s.count(), total)).append(')');
            first = false;
        }
        d.heading("Kalan süreye göre", total + " aktif sertifika");
        d.raw(bar, t.toString());
    }

    // ── Önümüzdeki 30 gün / sıradaki yenilemeler ─────────────────────────────

    private static void upcoming(MailDoc d, Report r, String base, boolean links, Caps caps) {
        List<Cert> all = r.upcoming();
        if (all.isEmpty()) {
            d.heading("Önümüzdeki 30 gün");
            Cert next = r.later().isEmpty() ? null : r.later().get(0);
            d.alert(Tone.SUCCESS, "Önümüzdeki 30 gün içinde süresi dolacak sertifika yok.",
                    next == null ? null : "En yakın bitiş: " + next.domain() + " — " + daysText(next.daysLeft())
                            + (blank(next.expiry()) ? "" : " (" + next.expiry() + ")") + ".");
            return;
        }
        d.heading("Önümüzdeki 30 gün", all.size() + " sertifika · süresi dolmuşlar ve en yakın bitişler üstte · tarihler Türkiye saatiyle");
        List<Cert> shown = all.subList(0, Math.min(caps.upcoming(), all.size()));
        certTable(d, shown, base, links);
        int hidden = all.size() - shown.size();
        if (hidden > 0) {
            String where = hasPdf(r) ? "tamamı ekteki PDF raporunda" : "tamamı ekteki dosyalarda";
            if (links) {
                d.noteHtml("+" + hidden + " sertifika daha bu pencerede — " + where + " ve "
                                + MailKit.link(base + "/?tab=forecast", "Vade Takvimi") + "'nde.",
                        "+" + hidden + " sertifika daha bu pencerede — " + where + " ve Vade Takvimi'nde: " + base + "/?tab=forecast");
            } else {
                d.note("+" + hidden + " sertifika daha bu pencerede — " + where + ".");
            }
        }
    }

    private static void later(MailDoc d, Report r, String base, boolean links, Caps caps) {
        List<Cert> all = r.later();
        int budget = Math.min(caps.later(), caps.upcoming() - r.upcoming().size());
        if (all.isEmpty() || budget <= 0) return;       // acil liste kırpıldıysa 30+ gün satırı gösterilmez
        List<Cert> shown = all.subList(0, Math.min(budget, all.size()));
        d.heading("Sıradaki yenilemeler", "30 günden sonra bitecek en yakın " + shown.size() + " sertifika");
        certTable(d, shown, base, links);
        int hidden = all.size() - shown.size();
        if (hidden > 0) d.note("+" + hidden + " sertifika daha — tamamı ekteki dosyalarda.");
    }

    private static void certTable(MailDoc d, List<Cert> certs, String base, boolean links) {
        List<Item> items = new ArrayList<>(certs.size());
        StringBuilder t = new StringBuilder();
        for (Cert c : certs) {
            String url = links && !blank(c.domain()) ? certUrl(base, c.domain()) : null;
            String main = url == null ? esc(nz(c.domain(), "—")) : MailKit.quietLink(url, c.domain());
            String sub = subline(c);
            String team = nz(c.team(), "Takım atanmamış");
            items.add(new Item(main, sub == null ? null : esc(sub), esc(team), esc(nz(c.expiry(), "—")), urgencyPill(c.daysLeft())));
            t.append("- ").append(nz(c.domain(), "—")).append(" — ").append(daysText(c.daysLeft()));
            if (!blank(c.expiry())) t.append(" · Bitiş: ").append(c.expiry());
            t.append(" · Takım: ").append(team);
            if (sub != null) t.append(" · ").append(sub);
            t.append('\n');
            if (url != null) t.append("  ").append(url).append('\n');
        }
        d.raw(MailKit.itemTable(List.of("Sertifika", "Takım", "Bitiş", "Kalan"), items), t.toString());
    }

    /** Alan adının SERTİFİKA PENCERESİ (Pano, tek seferlik {@code open=cert}; frontend {@code useCertDeepLink}). */
    public static String certUrl(String base, String domain) {
        return base + "/?tab=dashboard&domain=" + URLEncoder.encode(domain, StandardCharsets.UTF_8) + "&open=cert";
    }

    /** İkincil satır: sağlayıcı · T1 · kayda değer durum ("geçerli" ve hapla zaten söylenen "süresi dolmuş" yazılmaz). */
    private static String subline(Cert c) {
        List<String> p = new ArrayList<>();
        if (!blank(c.issuer())) p.add(c.issuer());
        if (c.tier() != null) p.add("T" + c.tier());
        String s = c.status();
        if (!blank(s) && !"geçerli".equalsIgnoreCase(s) && !"süresi dolmuş".equalsIgnoreCase(s)) p.add(s);
        return p.isEmpty() ? null : String.join(" · ", p);
    }

    /** Kalan süre hapı: dolmuş = dolu kırmızı, 0–7 kırmızı, 8–30 amber, 31–90 mavi, üstü yeşil, bilinmiyor = nötr. */
    public static String urgencyPill(Integer days) {
        if (days == null) return MailKit.pill("veri yok", Tone.NEUTRAL, false);
        if (days < 0) return MailKit.pill(Math.abs(days) + " gün önce doldu", Tone.DESTRUCTIVE, true);
        if (days == 0) return MailKit.pill("bugün bitiyor", Tone.DESTRUCTIVE, true);
        if (days <= 7) return MailKit.pill(days + " gün", Tone.DESTRUCTIVE, false);
        if (days <= 30) return MailKit.pill(days + " gün", Tone.WARNING, false);
        if (days <= 90) return MailKit.pill(days + " gün", Tone.INFO, false);
        return MailKit.pill(days + " gün", Tone.SUCCESS, false);
    }

    public static String daysText(Integer days) {
        if (days == null) return "veri yok";
        if (days < 0) return Math.abs(days) + " gün önce doldu";
        if (days == 0) return "bugün bitiyor";
        return days + " gün kaldı";
    }

    // ── Hijyen ───────────────────────────────────────────────────────────────

    private static void hygiene(MailDoc d, Report r, String base, boolean links, Caps caps) {
        if (r.findings().isEmpty()) {
            d.alert(Tone.SUCCESS, "Envanterde eksik, hatalı veya güncel olmayan kayıt bulunmadı.", null);
            return;
        }
        int cap = sampleCap(r.findings().size(), caps.hygieneBudget(), caps.hygieneFloor());
        d.heading("Envanter hijyeni", r.findingTotal() + " bulgu · " + r.findings().size()
                + " grup · her grupta en fazla " + cap + " kayıt listelenir");
        String where = hasPdf(r) ? "tamamı ekteki PDF raporunda." : "tamamı uygulamadaki Envanter ekranında.";
        for (FindingGroup g : r.findings()) {
            Tone tone = "health".equals(g.key()) || "error".equals(g.key()) ? Tone.DESTRUCTIVE : Tone.WARNING;
            boolean certLink = links && ("health".equals(g.key()) || "error".equals(g.key()));
            List<Finding> shown = g.findings().subList(0, Math.min(cap, g.findings().size()));
            int hidden = Math.max(0, g.total() - shown.size());
            List<ListItem> items = new ArrayList<>(shown.size());
            StringBuilder t = new StringBuilder(nz(g.title(), "Bulgu") + " (" + g.total() + ")");
            String explain = explain(g.key());
            String action = action(g.key());
            if (explain != null) t.append('\n').append(explain);
            if (action != null) t.append("\nÖnerilen: ").append(action);
            for (Finding f : shown) {
                String dom = nz(f.domain(), "—");
                String title = certLink && !blank(f.domain()) ? MailKit.quietLink(certUrl(base, f.domain()), f.domain()) : esc(dom);
                items.add(new ListItem(title, blank(f.detail()) ? null : esc(clip(f.detail(), caps.detailMax()))));
                t.append("\n- ").append(dom).append(blank(f.detail()) ? "" : ": " + f.detail());
            }
            String foot = hidden > 0 ? "+" + hidden + " kayıt daha — " + where : null;
            if (foot != null) t.append('\n').append(foot);
            d.raw(MailKit.listCard(esc(nz(g.title(), "Bulgu")), Badge.tint(g.total() + " kayıt", tone),
                    explain == null ? null : esc(explain),
                    action == null ? null : "<strong style=\"font-weight:600\">Önerilen:</strong> " + esc(action),
                    items, foot == null ? null : esc(foot)), t.toString());
        }
    }

    /** Grup sayısına göre grup başına örnek tavanı (bkz. {@link #HYGIENE_BUDGET}). */
    public static int sampleCap(int groups) {
        return sampleCap(groups, HYGIENE_BUDGET, 5);
    }

    static int sampleCap(int groups, int budget, int floor) {
        return groups <= 0 ? SAMPLE_CAP : Math.max(floor, Math.min(SAMPLE_CAP, budget / groups));
    }

    /** Grup anahtarı → "neden önemli" (InventoryHygieneService grupları). */
    public static String explain(String key) {
        return switch (key == null ? "" : key) {
            case "missing" -> "Takımı olmayan kayıt alarmları doğru ekibe yönlendiremez; kritiklik seviyesi (tier) eksikse önceliklendirme yapılamaz.";
            case "contacts" -> "Dört sorumlu ekip alanı da (Servis Yönetimi, Uygulama Geliştirme, IISAdmin, WAFAdmin) boş; yenileme günü kimin aksiyon alacağı belli değil.";
            case "stale" -> "Envanterde olup izleme sonucu olmayan ya da son kontrolü eşiği aşan kayıtlar: izleniyor sanılır ama izlenmez.";
            case "error" -> "Sertifika okunamadı (bağlantı, DNS ya da TLS hatası); bitiş tarihi bilinmiyor.";
            case "health" -> "Süresi dolmuş, iptal edilmiş, zinciri kırık, dağıtımı eksik ya da zayıf algoritmalı sertifikalar.";
            default -> null;
        };
    }

    /** Grup anahtarı → önerilen aksiyon. */
    public static String action(String key) {
        return switch (key == null ? "" : key) {
            case "missing" -> "Envanterde kaydı açıp sorumlu takımı ve kritiklik seviyesini atayın.";
            case "contacts" -> "Kayda en az bir sorumlu ekip bilgisi girin.";
            case "stale" -> "Alan adına erişimi doğrulayıp yeniden kontrol başlatın; kayıt artık kullanılmıyorsa pasife alın.";
            case "error" -> "Hata metnine göre erişimi (vekil sunucu, güvenlik duvarı, port) kontrol edin; adres değiştiyse kaydı güncelleyin.";
            case "health" -> "Sertifikayı yenileyin; zincir ya da dağıtım sorununda ara sertifikaları ve tüm sunuculara dağıtımı tamamlayın.";
            default -> null;
        };
    }

    // ── Kırılımlar ───────────────────────────────────────────────────────────

    private static void breakdowns(MailDoc d, Report r, Caps caps) {
        if (r.byTeam().size() >= 2) {
            List<Breakdown> shown = r.byTeam().subList(0, Math.min(caps.teams(), r.byTeam().size()));
            List<List<Cell>> rows = new ArrayList<>();
            for (Breakdown b : shown) {
                rows.add(List.of(Cell.of(b.label()), Cell.of(String.valueOf(b.total()), MailTokens.MUTED, false),
                        Cell.of(String.valueOf(b.urgent()), toned(b.urgent(), Tone.WARNING.strong), b.urgent() > 0),
                        Cell.of(String.valueOf(b.errors()), toned(b.errors(), Tone.DESTRUCTIVE.strong), b.errors() > 0)));
            }
            d.heading("Takıma göre", "Sorumlu takım · acil = süresi dolmuş ya da 30 gün içinde bitecek"
                    + (r.byTeam().size() > shown.size() ? " · en acil " + shown.size() + " takım" : ""));
            List<Col> cols = List.of(Col.of("Takım"), Col.num("Aktif"), Col.num("Acil"), Col.num("Hata"));
            d.raw(MailKit.compactTable(cols, rows), plainRows(cols, rows));
            int hidden = r.byTeam().size() - shown.size();
            if (hidden > 0) d.note("+" + hidden + " takım daha — tamamı ekteki dosyalarda.");
        }
        if (r.byIssuer().size() >= 2) {
            List<Breakdown> shown = r.byIssuer().subList(0, Math.min(caps.issuers(), r.byIssuer().size()));
            List<List<Cell>> rows = new ArrayList<>();
            for (Breakdown b : shown) {
                rows.add(List.of(Cell.of(b.label()), Cell.of(String.valueOf(b.total()), MailTokens.MUTED, false),
                        Cell.of(String.valueOf(b.urgent()), toned(b.urgent(), Tone.WARNING.strong), b.urgent() > 0)));
            }
            d.heading("Sağlayıcıya göre", "Sertifikayı veren kuruluş (CA) · son kontrol sonucuna göre");
            List<Col> cols = List.of(Col.of("Sağlayıcı"), Col.num("Sertifika"), Col.num("Acil"));
            d.raw(MailKit.compactTable(cols, rows), plainRows(cols, rows));
            int hidden = r.byIssuer().size() - shown.size();
            if (hidden > 0) d.note("+" + hidden + " sağlayıcı daha.");
        }
    }

    // ── Ekler / alt bilgi ────────────────────────────────────────────────────

    private static void attachments(MailDoc d, Report r) {
        if (r.attachments().isEmpty()) return;
        List<ListItem> items = new ArrayList<>();
        StringBuilder t = new StringBuilder("Ekteki dosyalar — Envanterin tamamı ektedir; gövdedeki listeler kısaltılmıştır.");
        for (Attachment a : r.attachments()) {
            items.add(new ListItem(MailKit.mono(nz(a.fileName(), "—")), blank(a.description()) ? null : esc(a.description())));
            t.append("\n- ").append(nz(a.fileName(), "—")).append(blank(a.description()) ? "" : ": " + a.description());
        }
        d.raw(MailKit.listCard("Ekteki dosyalar", Badge.outline(r.attachments().size() + " dosya"),
                esc("Envanterin tamamı ektedir; gövdedeki listeler kısaltılmıştır."), null, items, null), t.toString());
    }

    private static void footer(MailDoc d, String base, boolean links, String generatedAt) {
        String why = "Bu rapor, sertifika envanterinde sahip (sorumlu ya da uygulama geliştirme) takımı olarak kayıtlı her takıma "
                + "ve rapor ayarlarındaki ek alıcılara her ay otomatik gönderilir. Eksik ya da hatalı kayıtlar sahiplerince "
                + "fark edilsin diye envanterin tamamını içerir.";
        String settings = links ? base + "/?tab=settings&sec=certinvreport" : null;
        String html = esc(why) + (settings == null ? ""
                : " Gönderim zamanı ve ek alıcılar: " + MailKit.link(settings, "Rapor ayarları") + " (yönetici).");
        String text = why + (settings == null ? "" : " Gönderim zamanı ve ek alıcılar: Rapor ayarları (yönetici) — " + settings);
        d.footerWhyCustom(html, text)
         .footerMeta("Site Monitor — Otomatik Aylık Envanter Raporu", blank(generatedAt) ? null : "Oluşturuldu: " + generatedAt);
    }

    // ── küçük yardımcılar ────────────────────────────────────────────────────

    /** Tablo satırlarının düz metni: "- Takım A · Aktif: 40 · Acil: 5 · Hata: 1". */
    private static String plainRows(List<Col> cols, List<List<Cell>> rows) {
        StringBuilder t = new StringBuilder();
        for (List<Cell> row : rows) {
            t.append("- ");
            for (int c = 0; c < cols.size() && c < row.size(); c++) {
                if (c == 0) t.append(row.get(c).text());
                else t.append(" · ").append(cols.get(c).label()).append(": ").append(row.get(c).text());
            }
            t.append('\n');
        }
        return t.toString();
    }

    private static String clip(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 1).stripTrailing() + "…";
    }

    private static boolean hasPdf(Report r) {
        for (Attachment a : r.attachments()) {
            if (a.fileName() != null && a.fileName().toLowerCase(java.util.Locale.ROOT).endsWith(".pdf")) return true;
        }
        return false;
    }

    private static String esc(String s) { return MailKit.esc(s); }
    private static boolean blank(String s) { return s == null || s.isBlank(); }
    private static String nz(String s, String dflt) { return blank(s) ? dflt : s; }
}
