package com.sitemonitor.service.techerror;

import com.sitemonitor.service.techerror.TechErrorTemplate.IssueOptions;
import com.sitemonitor.service.techerror.TechErrorTemplate.Section;
import com.sitemonitor.service.techerror.TechErrorValues.Issue;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;

/**
 * Anomali konusunun GİRİŞ METNİ (2026-10-18, kullanıcı isteği: "üretilen rapor metinleri bu cevaplara göre olsun"): konunun
 * cevaplarından kurulan kısa paragraf — açıklama bunun ardından gelir. Ekran önizlemesi, PDF ve e-posta AYNI metni basar;
 * dil ŞABLONUN dilidir (raporun geri kalanı gibi). Örnek (TR):
 * <pre>12 Eylül 2026, 14:00–15:30 arasında **1.250** teknik hata alındı; bu, dönemdeki tüm teknik hataların **%3,4** kadarıdır.
 * Kesinti kaynaklıdır (Kesinti ID: **INC-2026-0915**). Müşteri etkisi: **320** müşteri.</pre>
 * Kurallar (özet cümlelerininkiyle aynı ruh — YARIM CÜMLE YOK): sorulmayan ya da cevaplanmayan bilgi cümleden düşer; zaman
 * yoksa adet cümlesi "Bu konu nedeniyle …" diye başlar; pay yalnız hesaplandıysa eklenir; kesinti cevabı Evet / Hayır; müşteri
 * etkisi Evet'te sayıyla (yoksa "Müşteri etkisi var."), Hayır'da "Müşteri etkisi yok."; müşteri etkisi sorulmayan bölümde
 * girilmiş müşteri sayısı "{etiket}: **n**." Rakamlar ve Kesinti ID kalın; etiket / ID içindeki yıldız dizileri teke iner
 * ({@link TechErrorSummary#neutral}); yıldız taşıyan ID kalın yazılmaz (kalın işareti bozulmasın). Hiçbir şey kalmazsa boş metin.
 * İstemci aynası: {@code techErrorModel.issueLeadText} — doğruluk tablosu {@code TechErrorIssueTextTest.LEAD_TABLE}.
 */
public final class TechErrorIssueText {

    private TechErrorIssueText() {}

    /**
     * Giriş metni. {@code share} hesaplanan pay (yoksa {@code null}); {@code resolve} şablon etiketlerinin yer tutucularını
     * çözer ({@code {kanal}} …). Eski düzendeki bölüm ({@link IssueOptions#legacy}) için de çalışır ama PDF / e-posta onu
     * çağırmaz (eski sayaç satırı bayt bayt kalır).
     */
    public static String lead(Section s, Issue i, Double share, String lang, UnaryOperator<String> resolve) {
        return lead(s, i, share, lang, resolve, null, null);
    }

    /**
     * {@link #lead(Section, Issue, Double, String, UnaryOperator)} — kısaltmayla girilmiş adetlerin üssüyle (2026-10-18:
     * "1,25M" yazılan hata / müşteri adedi metinde de "1,25M"; üs yoksa tam sayı, bayt bayt eskisi). Pay hesabı ve tekil /
     * çoğul seçimi SAYININ kendisiyle yapılır — üs yalnız gösterimdir.
     */
    public static String lead(Section s, Issue i, Double share, String lang, UnaryOperator<String> resolve,
                              Integer errorsUnit, Integer customersUnit) {
        if (s == null || i == null) return "";
        boolean en = "en".equals(lang);
        UnaryOperator<String> res = resolve == null ? UnaryOperator.identity() : resolve;
        IssueOptions o = s.opts();
        List<String> out = new ArrayList<>(3);

        String when = o.askTime() ? when(i, en) : null;
        Number errors = i.errors();
        String n = errors == null ? null : bold(TechErrorFormat.count(errors, lang, errorsUnit));
        String p = share == null || errors == null ? null : bold(TechErrorFormat.percent(share, en));
        String first = null;
        if (en) {
            String noun = errors != null && errors.doubleValue() == 1 ? "technical error" : "technical errors";
            if (when != null && n != null) first = cap(when) + ", " + n + " " + noun + " occurred";
            else if (when != null) first = "It happened " + when;
            else if (n != null) first = "This topic caused " + n + " " + noun;
            if (first != null && p != null) first += "; this is " + p + " of all technical errors in the period";
        } else {
            if (when != null && n != null) first = when + " " + n + " teknik hata alındı";
            else if (when != null) first = when + " yaşandı";
            else if (n != null) first = "Bu konu nedeniyle " + n + " teknik hata alındı";
            if (first != null && p != null) first += "; bu, dönemdeki tüm teknik hataların " + p + " kadarıdır";
        }
        if (first != null) out.add(first + ".");

        if (o.askOutage() && i.outage() != null) {
            if (i.outage()) {
                String id = i.outageId() == null || i.outageId().isBlank() ? null : TechErrorSummary.neutral(i.outageId().trim());
                String label = label(res.apply(o.outageIdLabel()), en ? "Outage ID" : "Kesinti ID");
                String ref = id == null ? "" : " (" + label + ": " + (id.indexOf('*') >= 0 ? id : bold(id)) + ")";
                out.add((en ? "It was caused by an outage" : "Kesinti kaynaklıdır") + ref + ".");
            } else {
                out.add(en ? "It was not caused by an outage." : "Kesinti kaynaklı değildir.");
            }
        }

        if (o.askImpact()) {
            if (Boolean.TRUE.equals(i.impact())) {
                Number c = i.customers();
                if (c != null) {
                    String count = bold(TechErrorFormat.count(c, lang, customersUnit));
                    out.add(en ? "Customer impact: " + count + (c.doubleValue() == 1 ? " customer." : " customers.")
                            : "Müşteri etkisi: " + count + " müşteri.");
                } else {
                    out.add(en ? "There was customer impact." : "Müşteri etkisi var.");
                }
            } else if (Boolean.FALSE.equals(i.impact())) {
                out.add(en ? "No customer impact." : "Müşteri etkisi yok.");
            }
        } else if (i.customers() != null) {
            String label = label(res.apply(s.customersLabel()), en ? "Affected customers" : "Etkilenen müşteri sayısı");
            String count = bold(TechErrorFormat.count(i.customers(), lang, customersUnit));
            out.add((label.endsWith("?") ? label + " " : label + ": ") + count + ".");
        }
        return String.join(" ", out);
    }

    /**
     * Zaman ifadesi (cümle ortası): TR "12 Eylül 2026, 14:00–15:30 arasında" · "12 Eylül 2026, 23:30 – ertesi gün 01:15
     * arasında" · "12 Eylül 2026 tarihinde"; EN "on 12 September 2026, 14:00–15:30" · "on 12 September 2026, 23:30 – 01:15
     * the next day" · "on 12 September 2026". Tarih yoksa {@code null}.
     */
    static String when(Issue i, boolean en) {
        String day = day(i.date(), en);
        if (day == null) return null;
        boolean range = i.start() != null && i.end() != null;
        if (en) {
            if (!range) return "on " + day;
            return "on " + day + ", " + (i.endNextDay() ? i.start() + " – " + i.end() + " the next day" : i.start() + "–" + i.end());
        }
        if (!range) return day + " tarihinde";
        return day + ", " + (i.endNextDay() ? i.start() + " – ertesi gün " + i.end() : i.start() + "–" + i.end()) + " arasında";
    }

    /** Gün: "12 Eylül 2026" / "12 September 2026" (gün baştaki sıfırsız); okunamazsa {@code null}. */
    public static String day(String iso, boolean en) {
        if (!TechErrorValues.validDate(iso)) return null;
        LocalDate d = LocalDate.parse(iso);
        return d.getDayOfMonth() + " " + TechErrorTemplate.monthName(d.getMonthValue(), en ? "en" : "tr") + " " + d.getYear();
    }

    /** Saat aralığı kısa gösterimi (geçmiş satırı, rozet): "14:00–15:30" / "23:30 – ertesi gün 01:15" (EN "… 01:15 next day"). */
    public static String range(Issue i, boolean en) {
        if (i.start() == null || i.end() == null) return null;
        if (!i.endNextDay()) return i.start() + "–" + i.end();
        return en ? i.start() + " – " + i.end() + " next day" : i.start() + " – ertesi gün " + i.end();
    }

    private static String bold(String s) {
        return "**" + s + "**";
    }

    private static String cap(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static String label(String raw, String fallback) {
        String v = raw == null ? "" : TechErrorSummary.neutral(raw).trim();
        return v.isEmpty() ? fallback : v;
    }
}
