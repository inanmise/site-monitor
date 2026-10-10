package com.sitemonitor.service.mail;

import com.sitemonitor.service.mail.MailKit.Badge;
import com.sitemonitor.service.mail.MailKit.Btn;
import com.sitemonitor.service.mail.MailKit.Kpi;
import com.sitemonitor.service.mail.MailKit.ListItem;
import com.sitemonitor.service.mail.MailKit.Variant;
import com.sitemonitor.service.mail.MailTokens.Tone;
import com.sitemonitor.service.report.executive.ExecFormat;
import com.sitemonitor.service.report.executive.ExecutiveSummary;
import com.sitemonitor.service.report.executive.SectionResult;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * AYLIK YÖNETİCİ ÖZETİ e-postası (2026-10-10) — YALNIZ {@link MailDoc}/{@link MailKit} ile (Outlook-güvenli, mobil duyarlı,
 * sol şerit YOK; {@code EmailResponsiveContractTest}). Saf: veri {@link ExecutiveSummary}, taban adres ve damga çağırandan.
 *
 * <p><b>İçerik:</b> durum rozeti + tek satırlık hüküm → üst şerit göstergeleri (bölüm başına bir kutu) → bölüm kartları
 * (başlık + durum rozeti + bölümün hükümleri; bölümleri TANIMAZ, {@link SectionResult}'ı genel çizer — yeni bir bölüm
 * sağlayıcısı kendiliğinden görünür) → ek dosya kartı → "Uygulamada aç" → şeffaflık alt bilgisi. Tablolar PDF'tedir;
 * gövde kısa kalır (Gmail 102 KB sınırının çok altında).
 */
public final class ExecutiveSummaryMail {

    private ExecutiveSummaryMail() { }

    public static final String KICKER = "Aylık Yönetici Özeti";

    /** Konu: {@code [Site Monitor] Aylık Yönetici Özeti · Eylül 2026 · AKSİYON GEREKLİ} (marka iki sözcük). */
    public static String subject(ExecutiveSummary s) {
        return "[Site Monitor] Aylık Yönetici Özeti · " + nz(s.monthLabel(), s.month()) + " · "
                + ExecFormat.sectionStatusLabel(s.status());
    }

    /**
     * @param baseUrl     uygulama taban adresi (http(s) değilse bağlantı ÜRETİLMEZ)
     * @param generatedAt alt bilgi damgası (Türkiye saati)
     * @param pdfFileName ek dosya adı (null = ek yok, kart çizilmez)
     */
    public static MailDoc.Mail build(ExecutiveSummary s, String baseUrl, String generatedAt, String pdfFileName) {
        String base = baseUrl == null ? "" : baseUrl.trim().replaceAll("/+$", "");
        boolean links = base.startsWith("http://") || base.startsWith("https://");
        String month = nz(s.monthLabel(), s.month());
        String verdict = s.headline().isEmpty() ? "Özet hazır." : s.headline().get(0).text();

        MailDoc d = MailDoc.create("[Site Monitor] Aylık Yönetici Özeti — " + month).wide().darkCanvas()
                .preheader(month + " · " + verdict)
                .kicker(KICKER)
                .badges(statusBadge(s.status()), Badge.outline("Kurum geneli"))
                .title(month + " · Yönetici Özeti", verdict);
        d.note("Dönem: " + month + " (Türkiye saati)" + (s.complete() ? "" : " · ay devam ediyor, şimdiye kadarki veri")
                + " · Erişilebilirlik hedefi " + ExecFormat.pct(asDouble(s.settings().get("availability_target")), 3) + ".");

        // ── Üst şerit: bölüm başına bir gösterge ──
        List<Kpi> tiles = new ArrayList<>();
        for (ExecutiveSummary.HeadlineKpi h : s.headlineKpis()) {
            SectionResult.Kpi k = h.kpi();
            if (k == null) continue;
            tiles.add(new Kpi(k.label(), ExecFormat.value(k.value(), k.format()), toneColor(k.tone()),
                    ExecFormat.delta(k.delta(), k.deltaFormat()), deltaTone(k.deltaTone()), k.hint()));
        }
        d.kpis(tiles);

        // ── Bölüm kartları: hükümler ──
        d.heading("Bölümler", s.sections().size() + " bölüm · ayrıntılı tablolar ekteki PDF'te");
        for (SectionResult sec : s.sections()) {
            List<ListItem> items = new ArrayList<>();
            StringBuilder t = new StringBuilder(nz(sec.title(), sec.key()) + " — " + ExecFormat.sectionStatusLabel(sec.status()));
            for (SectionResult.Verdict v : sec.verdicts()) {
                items.add(new ListItem(MailKit.esc(v.text()), null));
                t.append("\n- ").append(v.text());
            }
            String desc = sec.snapshot() && sec.asOf() != null ? "Rapor anı: " + stampOf(sec.asOf()) : null;
            if (desc != null) t.append("\n").append(desc);
            d.raw(MailKit.listCard(MailKit.esc(nz(sec.title(), sec.key())), statusBadge(sec.status()),
                    desc == null ? null : MailKit.esc(desc), null, items, null), t.toString());
        }

        if (pdfFileName != null && !pdfFileName.isBlank()) {
            d.raw(MailKit.listCard("Ekteki dosya", Badge.outline("PDF"),
                    MailKit.esc("Özetin tamamı: takım ve hizmet tabloları, en gürültülü hedefler, yaklaşan bitişler, "
                            + "yenileme sınıfları ve yöntem notları."),
                    null, List.of(new ListItem(MailKit.mono(pdfFileName), null)), null),
                    "Ekteki dosya: " + pdfFileName + " — özetin tamamı (tablolar ve yöntem notları).");
        }
        if (links) {
            d.buttons(null, List.of(new Btn(base + "/?tab=executive&ex_m=" + enc(s.month()), "Özeti uygulamada aç",
                    Variant.PRIMARY)));
        }
        String why = "Bu özet, yönetici özeti ayarlarında tanımlı alıcılara ve (seçildiyse) global yöneticilere her ay "
                + "otomatik gönderilir. Sayılar uygulamanın kendi kayıtlarından üretilir; erişilebilirlik resmî bir SLO değil, "
                + "kurum hedefine göre ölçülen değerdir.";
        String settings = links ? base + "/?tab=executive&ex_cfg=1" : null;
        d.footerWhyCustom(MailKit.esc(why) + (settings == null ? ""
                        : " Alıcılar ve zamanlama: " + MailKit.link(settings, "Yönetici özeti ayarları") + " (global yönetici)."),
                why + (settings == null ? "" : " Alıcılar ve zamanlama: " + settings))
         .footerMeta("Site Monitor — Otomatik Aylık Yönetici Özeti",
                generatedAt == null || generatedAt.isBlank() ? null : "Oluşturuldu: " + generatedAt);
        return d.build();
    }

    static Badge statusBadge(String status) {
        if (SectionResult.CRITICAL.equals(status)) return Badge.solid(ExecFormat.sectionStatusLabel(status), Tone.DESTRUCTIVE);
        if (SectionResult.ATTENTION.equals(status) || SectionResult.ERROR.equals(status)) {
            return Badge.tint(ExecFormat.sectionStatusLabel(status), Tone.WARNING);
        }
        if (SectionResult.OK.equals(status)) return Badge.tint(ExecFormat.sectionStatusLabel(status), Tone.SUCCESS);
        return Badge.outline(ExecFormat.sectionStatusLabel(status));
    }

    static String toneColor(String tone) {
        if (tone == null) return null;
        return switch (tone) {
            case SectionResult.T_OK -> Tone.SUCCESS.strong;
            case SectionResult.T_WARN -> Tone.WARNING.strong;
            case SectionResult.T_BAD -> Tone.DESTRUCTIVE.strong;
            default -> null;
        };
    }

    static Tone deltaTone(String tone) {
        if (tone == null) return Tone.NEUTRAL;
        return switch (tone) {
            case SectionResult.T_OK -> Tone.SUCCESS;
            case SectionResult.T_WARN -> Tone.WARNING;
            case SectionResult.T_BAD -> Tone.DESTRUCTIVE;
            default -> Tone.NEUTRAL;
        };
    }

    private static String stampOf(String utcIso) {
        try {
            return ExecFormat.stamp(java.time.LocalDateTime.parse(utcIso).toInstant(java.time.ZoneOffset.UTC));
        } catch (Exception e) {
            return utcIso;
        }
    }

    private static Double asDouble(Object v) {
        return v instanceof Number n ? n.doubleValue() : null;
    }

    private static String enc(String s) {
        return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8);
    }

    private static String nz(String s, String dflt) {
        return s == null || s.isBlank() ? (dflt == null ? "—" : dflt) : s;
    }
}
