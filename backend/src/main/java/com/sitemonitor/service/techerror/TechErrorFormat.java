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

    public static String count(Number n, String lang) {
        return n == null ? EMPTY : number(n.doubleValue(), "en".equals(lang));
    }

    public static String number(double d, boolean en) {
        DecimalFormatSymbols sym = DecimalFormatSymbols.getInstance(en ? Locale.US : Locale.forLanguageTag("tr-TR"));
        if (!en) {
            sym.setGroupingSeparator('.');
            sym.setDecimalSeparator(',');
        }
        DecimalFormat df = new DecimalFormat("#,##0.####", sym);
        df.setRoundingMode(RoundingMode.HALF_UP);
        return df.format(BigDecimal.valueOf(d));
    }

    public static String percent(double d, boolean en) {
        String n = number(d, en);
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
