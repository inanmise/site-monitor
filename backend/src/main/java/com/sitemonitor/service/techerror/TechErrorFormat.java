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
}
