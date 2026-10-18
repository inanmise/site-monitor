package com.sitemonitor.service.techerror;

import com.sitemonitor.service.techerror.TechErrorTemplate.IssueOptions;
import com.sitemonitor.service.techerror.TechErrorTemplate.Section;
import com.sitemonitor.service.techerror.TechErrorValues.Issue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Anomali konusunun giriş metni (2026-10-18). {@link #LEAD_TABLE} istemcideki {@code techErrorIssueText.test.js} tarafından bu
 * KAYNAKTAN okunur — ekran önizlemesi, PDF ve e-posta aynı metni basar.
 */
class TechErrorIssueTextTest {

    /**
     * Varsayılan anomali bölümü (şablon dili) + satırın soruları: "all" = varsayılanlar; yoksa virgüllü {@code time},
     * {@code outage}, {@code impact} (ve isteğe bağlı {@code idlabel:<ad>} — Kesinti ID etiketi).
     */
    static Section section(String lang, String opts) {
        Section base = TechErrorTemplate.defaults(lang).section("issues");
        IssueOptions d = IssueOptions.defaults("en".equals(lang));
        if ("all".equals(opts)) return base;
        boolean time = false, outage = false, impact = false;
        String idLabel = d.outageIdLabel();
        for (String tok : opts.split(",")) {
            String x = tok.trim();
            if (x.equals("time")) time = true;
            else if (x.equals("outage")) outage = true;
            else if (x.equals("impact")) impact = true;
            else if (x.startsWith("idlabel:")) idLabel = x.substring("idlabel:".length());
        }
        return base.withOptions(new IssueOptions(time, d.timeLabel(), outage, d.outageLabel(), idLabel, impact, d.impactLabel(),
                d.noImpactDisplay(), true, d.ratioOf()));
    }

    /** "date=2026-09-12;start=14:00;…" → konu (sayılar Long, true / false Boolean). */
    static Issue issue(String spec) {
        Map<String, String> m = new HashMap<>();
        if (!spec.isEmpty()) for (String part : spec.split(";")) {
            int eq = part.indexOf('=');
            m.put(part.substring(0, eq), part.substring(eq + 1));
        }
        java.util.function.Function<String, Long> num = k -> m.containsKey(k) ? Long.parseLong(m.get(k)) : null;
        java.util.function.Function<String, Boolean> bool = k -> m.containsKey(k) ? Boolean.parseBoolean(m.get(k)) : null;
        return new Issue("k", "Konu", "", true, "", num.apply("customers"), num.apply("errors"), m.get("date"), m.get("start"),
                m.get("end"), Boolean.TRUE.equals(bool.apply("next")), bool.apply("outage"), m.get("id"), bool.apply("impact"), Map.of(), null);
    }

    /** Konu tanımındaki kısaltma üssü ({@code eu} = hata adedi, {@code cu} = müşteri adedi); yoksa null. */
    static Integer unit(String spec, String key) {
        if (spec.isEmpty()) return null;
        for (String part : spec.split(";")) {
            int eq = part.indexOf('=');
            if (part.substring(0, eq).equals(key)) return Integer.parseInt(part.substring(eq + 1));
        }
        return null;
    }

    /**
     * Giriş metni doğruluk tablosu — JS testi AYNI tabloyu bu kaynaktan okur. Satır: ad; dil; sorular; konu ("anahtar=değer;…":
     * date, start, end, next, errors, outage, id, impact, customers; kısaltma üssü {@code eu} hata adedinin, {@code cu} müşteri
     * adedinin — 2026-10-18: "1,25M" yazılan adet metinde de "1,25M", pay ve tekil / çoğul SAYIYLA); pay ("-" = yok); beklenen metin.
     */
    static final String[][] LEAD_TABLE = {
            { "TR tam: zaman + adet + pay + kesinti ID + müşteri sayısı", "tr", "all",
              "date=2026-09-12;start=14:00;end=15:30;errors=1250;outage=true;id=INC-2026-0915;impact=true;customers=320", "3.4",
              "12 Eylül 2026, 14:00–15:30 arasında **1.250** teknik hata alındı; bu, dönemdeki tüm teknik hataların **%3,4** kadarıdır. "
                      + "Kesinti kaynaklıdır (Kesinti ID: **INC-2026-0915**). Müşteri etkisi: **320** müşteri." },
            { "TR gece yarısını geçen aralık, kesinti Hayır, etki Hayır", "tr", "all",
              "date=2026-09-30;start=23:30;end=01:15;next=true;errors=12;outage=false;impact=false", "-",
              "30 Eylül 2026, 23:30 – ertesi gün 01:15 arasında **12** teknik hata alındı. Kesinti kaynaklı değildir. Müşteri etkisi yok." },
            { "TR yalnız tarih + adet (pay yok)", "tr", "all", "date=2026-09-12;errors=5", "-",
              "12 Eylül 2026 tarihinde **5** teknik hata alındı." },
            { "TR zaman var adet yok", "tr", "all", "date=2026-09-12;start=14:00;end=15:30", "-",
              "12 Eylül 2026, 14:00–15:30 arasında yaşandı." },
            { "TR zaman yok: adet + küçük pay", "tr", "all", "errors=1250", "0.0028",
              "Bu konu nedeniyle **1.250** teknik hata alındı; bu, dönemdeki tüm teknik hataların **%0,0028** kadarıdır." },
            { "TR etki Evet, sayı yok", "tr", "all", "impact=true", "-", "Müşteri etkisi var." },
            { "TR hiçbir cevap yok → boş", "tr", "all", "", "-", "" },
            { "TR kesinti Evet, ID yok (eski kayıt)", "tr", "all", "outage=true", "-", "Kesinti kaynaklıdır." },
            { "TR yıldızlı ID kalın yazılmaz", "tr", "all", "outage=true;id=INC**1", "-", "Kesinti kaynaklıdır (Kesinti ID: INC*1)." },
            { "TR müşteri etkisi sorulmuyor: girilen sayı etiketle", "tr", "time,outage", "customers=320", "-",
              "Etkilenen müşteri sayısı: **320**." },
            { "TR zaman sorulmuyor: tarih yok sayılır", "tr", "outage,impact", "date=2026-09-12;errors=7", "-",
              "Bu konu nedeniyle **7** teknik hata alındı." },
            { "TR kendi Kesinti ID etiketi", "tr", "time,outage,impact,idlabel:Olay no", "outage=true;id=OLY-7", "-",
              "Kesinti kaynaklıdır (Olay no: **OLY-7**)." },
            { "EN full", "en", "all",
              "date=2026-09-12;start=14:00;end=15:30;errors=1250;outage=true;id=INC-2026-0915;impact=true;customers=320", "3.4",
              "On 12 September 2026, 14:00–15:30, **1,250** technical errors occurred; this is **3.4%** of all technical errors in the period. "
                      + "It was caused by an outage (Outage ID: **INC-2026-0915**). Customer impact: **320** customers." },
            { "EN overnight, one error, no outage, no impact", "en", "all",
              "date=2026-09-30;start=23:30;end=01:15;next=true;errors=1;outage=false;impact=false", "-",
              "On 30 September 2026, 23:30 – 01:15 the next day, **1** technical error occurred. It was not caused by an outage. No customer impact." },
            { "EN date only, no errors", "en", "all", "date=2026-09-12", "-", "It happened on 12 September 2026." },
            { "EN errors only", "en", "all", "errors=2000", "-", "This topic caused **2,000** technical errors." },
            { "EN one customer", "en", "all", "impact=true;customers=1", "-", "Customer impact: **1** customer." },
            { "EN impact not asked", "en", "time", "customers=12", "-", "Affected customers: **12**." },
            { "TR kısaltmalı hata ve müşteri adedi (üs yalnız gösterim)", "tr", "all",
              "date=2026-09-12;errors=1250000;eu=6;impact=true;customers=4400;cu=3", "3.4",
              "12 Eylül 2026 tarihinde **1,25M** teknik hata alındı; bu, dönemdeki tüm teknik hataların **%3,4** kadarıdır. "
                      + "Müşteri etkisi: **4,4k** müşteri." },
            { "TR müşteri etkisi sorulmuyor: kısaltmalı sayı etiketle", "tr", "time,outage", "customers=2500;cu=3", "-",
              "Etkilenen müşteri sayısı: **2,5k**." },
            { "TR sıfır kısaltılmaz", "tr", "all", "errors=0;eu=3", "-", "Bu konu nedeniyle **0** teknik hata alındı." },
            { "TR 6 ondalığa sığmayan kısaltma tam sayı", "tr", "all", "errors=4123456789;eu=9", "-",
              "Bu konu nedeniyle **4.123.456.789** teknik hata alındı." },
            { "EN abbreviated counts", "en", "all", "errors=1250000;eu=6;impact=true;customers=1000;cu=3", "-",
              "This topic caused **1.25M** technical errors. Customer impact: **1k** customers." },
    };

    @Test
    @DisplayName("LEAD_TABLE: sorulmayan / cevaplanmayan bilgi düşer (yarım cümle yok), rakamlar ve ID kalın, şablon dili; aynı metin ekranda, PDF'te, e-postada")
    void table() {
        assertThat(LEAD_TABLE.length).isGreaterThanOrEqualTo(15);
        for (String[] row : LEAD_TABLE) {
            Section s = section(row[1], row[2]);
            Double share = "-".equals(row[4]) ? null : Double.parseDouble(row[4]);
            assertThat(TechErrorIssueText.lead(s, issue(row[3]), share, row[1], x -> x, unit(row[3], "eu"), unit(row[3], "cu")))
                    .as(row[0]).isEqualTo(row[5]);
            // Kısaltmasız satır: üssüz imza bayt bayt aynı metni üretir
            if (unit(row[3], "eu") == null && unit(row[3], "cu") == null) {
                assertThat(TechErrorIssueText.lead(s, issue(row[3]), share, row[1], x -> x)).as(row[0]).isEqualTo(row[5]);
            }
        }
    }

    @Test
    @DisplayName("giriş metni kalın yazısı zengin metin olarak çözülür (PDF / e-posta): rakamlar, pay ve ID kalın; tarih düz")
    void richTextBold() {
        String lead = TechErrorIssueText.lead(section("tr", "all"), issue("date=2026-09-12;start=14:00;end=15:30;errors=1250;outage=true;id=#12-A"),
                3.4, "tr", x -> x);
        List<TechErrorRichText.Block> blocks = TechErrorRichText.parse(lead);
        assertThat(blocks).hasSize(1);
        List<String> bold = blocks.get(0).runs().stream().filter(TechErrorRichText.Run::bold).map(TechErrorRichText.Run::text).toList();
        assertThat(bold).containsExactly("1.250", "%3,4", "#12-A");
        assertThat(TechErrorRichText.plain(lead)).startsWith("12 Eylül 2026, 14:00–15:30 arasında 1.250 teknik hata alındı");
        // Gün ve aralık yardımcıları (geçmiş satırları)
        assertThat(TechErrorIssueText.day("2026-09-05", false)).isEqualTo("5 Eylül 2026");
        assertThat(TechErrorIssueText.day("2026-02-30", true)).isNull();
        assertThat(TechErrorIssueText.range(issue("start=23:30;end=01:15;next=true"), true)).isEqualTo("23:30 – 01:15 next day");
        assertThat(TechErrorIssueText.range(issue("start=14:00"), false)).isNull();
    }
}
