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
 *
 * <p>2026-10-18 (kullanıcı isteği: "4.4G gibi bir giriş yapıldığında raporda da aynı şekilde gözüksün"): kısaltmayla
 * girilmiş sayı ({@code values.units}, {@link TechErrorValues}) kısaltmasıyla basılır — {@link #abbreviated}; tam yazılmış
 * sayı eskisi gibi tam. Harfler Qlik'inkiler (k / M / G / T) iki dilde de; ondalık ayracı raporun dilinden.
 */
public final class TechErrorFormat {

    private TechErrorFormat() {}

    public static final String EMPTY = "—";

    public static String value(Field f, Object v, String lang) {
        return value(f, v, lang, null);
    }

    /**
     * {@link #value(Field, Object, String)} + kısaltma üssü ({@code unit}: 3 / 6 / 9 / 12; null = tam sayı). Kısaltma
     * yalnız SAYI alanında geçer (yüzde / evet-hayır / metin üssü yok sayar).
     */
    public static String value(Field f, Object v, String lang, Integer unit) {
        if (v == null) return EMPTY;
        boolean en = "en".equals(lang);
        return switch (f.type()) {
            case "number" -> v instanceof Number n ? numberOrAbbrev(n.doubleValue(), unit, en) : String.valueOf(v);
            case "percent" -> v instanceof Number n ? percent(n.doubleValue(), en) : String.valueOf(v);
            case "yesno" -> v instanceof Boolean b ? yesNo(b, en) : String.valueOf(v);
            default -> String.valueOf(v);
        };
    }

    /** Tablo hücresi: sütun türüne göre (sayı / yüzde / metin); boş "—". */
    public static String cell(TechErrorTemplate.Column c, Object v, String lang) {
        return cell(c, v, lang, null);
    }

    /** {@link #cell(TechErrorTemplate.Column, Object, String)} + kısaltma üssü (yalnız sayı sütunu). */
    public static String cell(TechErrorTemplate.Column c, Object v, String lang, Integer unit) {
        if (v == null) return EMPTY;
        boolean en = "en".equals(lang);
        return switch (c.type()) {
            case "number" -> v instanceof Number n ? numberOrAbbrev(n.doubleValue(), unit, en) : String.valueOf(v);
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
        return delta(f, prev, cur, lang, null);
    }

    /**
     * {@link #delta(Field, Object, Object, String)} + bu ayın değerinin kısaltma üssü (2026-10-18): bu ayın değeri
     * kısaltmayla girildiyse SAYI farkı da kısa yazılır — birim büyüklüğe göre, en çok 2 ondalık ("+50M (+%1,15)"); yüzde
     * kısmı ve puan farkı değişmez. Üs yoksa çıktı eskisiyle bayt bayt aynı.
     */
    public static Delta delta(Field f, Object prev, Object cur, String lang, Integer unit) {
        Double p = TechErrorValues.number(prev);
        Double c = TechErrorValues.number(cur);
        if (p == null || c == null) return null;
        boolean en = "en".equals(lang);
        if ("percent".equals(f.type())) return points(c - p, en);
        double diff = c - p;
        if (diff == 0) return new Delta(en ? "no change" : "değişim yok", "flat");
        String sign = diff > 0 ? "+" : "−";
        String text = sign + (isUnit(unit) ? compactQlik(Math.abs(diff), en) : number(Math.abs(diff), en));
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
        return count(n, lang, null);
    }

    /** Anomali konusunun adedi (müşteri / hata) + kısaltma üssü (2026-10-18). */
    public static String count(Number n, String lang, Integer unit) {
        return n == null ? EMPTY : numberOrAbbrev(n.doubleValue(), unit, "en".equals(lang));
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

    // ── Kısaltmalı sayı (2026-10-18) ─────────────────────────────────────────────────────────────────

    /** Kısaltma harfi (Qlik, iki dilde aynı): 3 → k, 6 → M, 9 → G, 12 → T; başka üs → null. */
    public static String letter(int exp) {
        return switch (exp) {
            case 3 -> "k";
            case 6 -> "M";
            case 9 -> "G";
            case 12 -> "T";
            default -> null;
        };
    }

    /** Geçerli kısaltma üssü mü (3 / 6 / 9 / 12)? */
    public static boolean isUnit(Integer exp) {
        return exp != null && letter(exp) != null;
    }

    /**
     * Kısaltmayla girilmiş sayının gösterimi: değer 10^{@code exp} ile KAYDIRILIR (ondalık gösterimde — kayan nokta bölmesi
     * yok), en çok 6 ondalık yarım yukarı, sondaki sıfırlar düşer, binlik gruplaması {@link #number} gibi, harf eklenir,
     * boşluk yok: 4.400.000.000 / 9 → TR "4,4G", EN "4.4G"; 1.250.000.000.000 / 9 → "1.250G". Yazılan rakam böylece aynen
     * geri gelir ("4.40G" → "4,4G"). Geçersiz üs → tam sayı. İstemci aynası: {@code techErrorModel.formatAbbreviated} (ortak
     * doğruluk tablosu {@code TechErrorNumberDisplayTest.ABBREV_TABLE}).
     */
    public static String abbreviated(double value, int exp, boolean en) {
        String l = letter(exp);
        if (l == null || !Double.isFinite(value)) return number(value, en);
        BigDecimal s = BigDecimal.valueOf(value).movePointLeft(exp).setScale(6, RoundingMode.HALF_UP).stripTrailingZeros();
        return decimal(en, "#,##0.######").format(s) + l;
    }

    private static String numberOrAbbrev(double d, Integer unit, boolean en) {
        return isUnit(unit) ? abbreviated(d, unit, en) : number(d, en);
    }

    private static final BigDecimal THOUSAND = BigDecimal.valueOf(1000);

    /**
     * Farkın kısa yazımı (kısaltmalı değerin {@code {d:}} farkı ve kutucuk farkı): 1000'in altı tam (en çok 2 ondalık),
     * üstü büyüklüğe göre k / M / G / T, en çok 2 ondalık yarım yukarı; yuvarlanınca 1000 olan bir üst birime geçer
     * ("999.999" → "1M", "1.000k" değil). {@code a} ≥ 0. İstemci aynası: {@code techErrorModel.compactQlik}.
     */
    public static String compactQlik(double a, boolean en) {
        BigDecimal v = BigDecimal.valueOf(Math.abs(a));
        if (v.setScale(2, RoundingMode.HALF_UP).compareTo(THOUSAND) < 0) return number(Math.abs(a), en, 2);
        double m = Math.abs(a);
        int exp = m < 1e6 ? 3 : m < 1e9 ? 6 : m < 1e12 ? 9 : 12;
        BigDecimal s = v.movePointLeft(exp).setScale(2, RoundingMode.HALF_UP);
        if (s.compareTo(THOUSAND) >= 0 && exp < 12) {
            exp += 3;
            s = v.movePointLeft(exp).setScale(2, RoundingMode.HALF_UP);
        }
        return decimal(en, "#,##0.##").format(s) + letter(exp);
    }

    // ── Sıfır işareti (2026-10-18) ───────────────────────────────────────────────────────────────────

    /**
     * Sıfır adedin yanına yeşil yuvarlak tik konur mu (kullanıcı isteği: "0 adet gibi olursa 0 yanına yeşil yuvarlak tik")?
     * Yalnız SAYI (adet) alanında, değer tam 0 (bilinmeyen / boş değer 0 DEĞİLDİR) ve alanın "daha iyi" yönü {@code higher}
     * değilse (ör. "toplam işlem 0" iyi değildir). Yüzde, evet/hayır, metin ve otomatik hesaplanan alanda hiç. Muaf alan
     * çağıranın işi (muaf alan zaten çizilmez). İstemci aynası: {@code techErrorModel.zeroOk} (ortak doğruluk tablosu
     * {@code TechErrorNumberDisplayTest.ZERO_TABLE}).
     */
    public static boolean zeroOk(Field f, Object v) {
        if (f == null || !"number".equals(f.type()) || f.calc() != null) return false;
        if ("higher".equals(TechErrorTemplate.effectiveBetter(f))) return false;
        return zeroCount(v);
    }

    /** Anomali konusunun adedi (müşteri / hata) tam 0 mı — yalnız sayı değer; boş / metin hayır. */
    public static boolean zeroCount(Object v) {
        return v instanceof Number n && Double.isFinite(n.doubleValue()) && n.doubleValue() == 0;
    }

    /**
     * Grafik çubuğu etiketi (2026-10-15, otomatik trend): sayı binlik / milyon / milyar / trilyon kısaltmasıyla — 2026-10-18
     * kullanıcı kararıyla iki dilde de Qlik harfleri k / M / G / T (önce TR "B" / "Mn" / "Mr", EN "K" / "M" / "B"); 100'ün
     * altında bir ondalık ("12,5M"), üstünde tam ("125M"); yuvarlanınca 1000 olacak değer bir üst birime geçer ("1.000k"
     * değil "1M" — inceleme 2026-10-15). Yüzde en çok iki ondalık, 0,1'in altında iki anlamlı basamak kalacak kadar (en çok
     * altı): %0,42 · %0,042 · %0,0042 · %0,00004. Tam değer göstergede / araç ipucunda görünür; bu yalnız dar çubuk başlığı
     * içindir. İstemci aynası: techErrorModel.compactNumber.
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
        String[] units = { "k", "M", "G", "T" };
        int u = a < 1_000_000d ? 0 : a < 1_000_000_000d ? 1 : a < 1_000_000_000_000d ? 2 : 3;
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
