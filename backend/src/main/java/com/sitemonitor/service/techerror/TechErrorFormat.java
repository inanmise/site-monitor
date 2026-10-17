package com.sitemonitor.service.techerror;

import com.sitemonitor.service.techerror.TechErrorTemplate.Field;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;

/**
 * Teknik hata raporunun DEĞER biçimi (PDF + e-posta ortak; 2026-10-13). Dil şablondan gelir: TR "1.234.567" ve
 * "%0,42", EN "1,234,567" ve "0.42%". Boş değer "—". Ondalık en çok 4 hane, sondaki sıfırlar düşer.
 */
public final class TechErrorFormat {

    private TechErrorFormat() {}

    public static final String EMPTY = "—";

    public static String value(Field f, Object v, String lang) {
        if (v == null) return EMPTY;
        boolean en = "en".equals(lang);
        return switch (f.type()) {
            case "number" -> v instanceof Number n ? number(n.doubleValue(), en) : String.valueOf(v);
            case "percent" -> v instanceof Number n ? percent(n.doubleValue(), en) : String.valueOf(v);
            case "yesno" -> v instanceof Boolean b ? yesNo(b, en) : String.valueOf(v);
            default -> String.valueOf(v);
        };
    }

    /** Tablo hücresi: sütun türüne göre (sayı / yüzde / metin); boş "—". */
    public static String cell(TechErrorTemplate.Column c, Object v, String lang) {
        if (v == null) return EMPTY;
        boolean en = "en".equals(lang);
        return switch (c.type()) {
            case "number" -> v instanceof Number n ? number(n.doubleValue(), en) : String.valueOf(v);
            case "percent" -> v instanceof Number n ? percent(n.doubleValue(), en) : String.valueOf(v);
            default -> String.valueOf(v);
        };
    }

    public static String yesNo(boolean b, boolean en) {
        return en ? (b ? "Yes" : "No") : (b ? "Evet" : "Hayır");
    }

    /**
     * Önceki aya göre fark (2026-10-17, 4b — kullanıcı kararı: adetlerde fark adet + yüzde "▲ 1.250 · +%3,2", oranlarda puan
     * "▼ 0,05 puan"): {@code text} işaretli metin, {@code dir} {@code up} / {@code down} / {@code flat}. Ok işaretini çizen
     * (PDF üçgeni, e-posta ▲▼, ekran simgesi) yön alanından okur; metin yalnız işaret + rakam taşır. İstemci aynası:
     * {@code techErrorModel.deltaOf}.
     */
    public record Delta(String text, String dir) { }

    /**
     * Sayı alanı: fark adet (+ önceki 0 değilse yüzde); yüzde alanı: puan farkı. Değişim yoksa "değişim yok" / "no change"
     * (yüzdede "0 puan"). İkisinden biri yoksa {@code null} (cümle düşer, kutucukta fark satırı yoktur).
     */
    public static Delta delta(Field f, Object prev, Object cur, String lang) {
        Double p = TechErrorValues.number(prev);
        Double c = TechErrorValues.number(cur);
        if (p == null || c == null) return null;
        boolean en = "en".equals(lang);
        if ("percent".equals(f.type())) return points(c - p, en);
        double diff = c - p;
        if (diff == 0) return new Delta(en ? "no change" : "değişim yok", "flat");
        String sign = diff > 0 ? "+" : "−";
        String text = sign + number(Math.abs(diff), en);
        if (p != 0) text += " (" + sign + percent(TechErrorCalc.roundRate(Math.abs(diff) / Math.abs(p) * 100), en) + ")";
        return new Delta(text, diff > 0 ? "up" : "down");
    }

    /** Puan farkı (yüzde alanının değişimi ya da hedefe uzaklık): "+0,04 puan" / "−0,02 puan" / "0 puan" (EN "pts"); 2 hane, küçük fark 2 anlamlı hane. */
    public static Delta points(double diff, boolean en) {
        double r = TechErrorCalc.roundRate(Math.abs(diff));
        String unit = en ? " pts" : " puan";
        if (r == 0) return new Delta("0" + unit, "flat");
        return new Delta((diff > 0 ? "+" : "−") + number(r, en, 6) + unit, diff > 0 ? "up" : "down");
    }

    public static String count(Number n, String lang) {
        return n == null ? EMPTY : number(n.doubleValue(), "en".equals(lang));
    }

    public static String number(double d, boolean en) {
        return number(d, en, 4);
    }

    private static String number(double d, boolean en, int maxDecimals) {
        DecimalFormatSymbols sym = DecimalFormatSymbols.getInstance(en ? Locale.US : Locale.forLanguageTag("tr-TR"));
        if (!en) {
            sym.setGroupingSeparator('.');
            sym.setDecimalSeparator(',');
        }
        DecimalFormat df = new DecimalFormat("#,##0." + "#".repeat(maxDecimals), sym);
        df.setRoundingMode(RoundingMode.HALF_UP);
        return df.format(BigDecimal.valueOf(d));
    }

    /**
     * Yüzde: en çok 4 ondalık; sıfırdan büyük ama 0,01'in altındaki yüzde 2 anlamlı haneyle (en çok 6 ondalık) — %0,0028,
     * %0,00004 (2026-10-16: 4 ondalık sınırı küçük oranı sahte "%0" gösteriyordu). İstemci aynası: techErrorModel.formatPercent.
     */
    public static String percent(double d, boolean en) {
        String n = d != 0 && Math.abs(d) < 0.01
                ? number(d, en, TechErrorCalc.smallScale(BigDecimal.valueOf(d)))
                : number(d, en);
        return en ? n + "%" : "%" + n;
    }

    /**
     * Grafik çubuğu etiketi (2026-10-15, otomatik trend): sayı binlik / milyon / milyar kısaltmasıyla (TR "B" / "Mn" /
     * "Mr", EN "K" / "M" / "B"), 100'ün altında bir ondalık ("12,5Mn"), üstünde tam ("125Mn"); yuvarlanınca 1000 olacak
     * değer bir üst birime geçer ("1.000B" değil "1Mn" — inceleme 2026-10-15). Yüzde en çok iki ondalık, 0,1'in altında
     * iki anlamlı basamak kalacak kadar (en çok altı): %0,42 · %0,042 · %0,0042 · %0,00004. Tam değer göstergede / araç ipucunda
     * görünür; bu yalnız dar çubuk başlığı içindir. İstemci aynası: techErrorModel.compactNumber.
     */
    public static String compact(double d, String type, boolean en) {
        if ("percent".equals(type)) {
            double a = Math.abs(d);
            // 0,1 ve üstü 2 ondalık; altında iki anlamlı basamak (değerler 6 ondalığa kadar saklanır — "%0" basılmaz)
            int digits = a == 0 || a >= 0.1 ? 2 : a >= 0.01 ? 3 : a >= 0.001 ? 4 : a >= 0.0001 ? 5 : 6;
            String n = decimal(en, "#,##0." + "#".repeat(digits)).format(BigDecimal.valueOf(d));
            return en ? n + "%" : "%" + n;
        }
        double a = Math.abs(d);
        if (a < 1000) return number(d, en);
        String[] units = en ? new String[] { "K", "M", "B" } : new String[] { "B", "Mn", "Mr" };
        int u = a < 1_000_000d ? 0 : a < 1_000_000_000d ? 1 : 2;
        double scaled = d / Math.pow(1000, u + 1);
        if (Math.abs(scaled) >= 999.5 && u < units.length - 1) {          // 999,5 → 1000: bir üst birim
            u++;
            scaled = d / Math.pow(1000, u + 1);
        }
        return decimal(en, Math.abs(scaled) < 100 ? "#,##0.#" : "#,##0").format(BigDecimal.valueOf(scaled)) + units[u];
    }

    private static DecimalFormat decimal(boolean en, String pattern) {
        DecimalFormatSymbols sym = DecimalFormatSymbols.getInstance(en ? Locale.US : Locale.forLanguageTag("tr-TR"));
        if (!en) {
            sym.setGroupingSeparator('.');
            sym.setDecimalSeparator(',');
        }
        DecimalFormat df = new DecimalFormat(pattern, sym);
        df.setRoundingMode(RoundingMode.HALF_UP);
        return df;
    }
}
