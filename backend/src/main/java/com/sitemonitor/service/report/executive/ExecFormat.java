package com.sitemonitor.service.report.executive;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Yönetici özetinin TÜRKÇE biçimleyicileri — hüküm metinleri, e-posta ve PDF aynı yazımı kullanır (ekran kendi dilinde
 * biçimler). Saf, durumsuz.
 */
public final class ExecFormat {

    private ExecFormat() { }

    static final String[] MONTHS_TR = {"Ocak", "Şubat", "Mart", "Nisan", "Mayıs", "Haziran",
            "Temmuz", "Ağustos", "Eylül", "Ekim", "Kasım", "Aralık"};
    /**
     * Kodlu sütun değerlerinin Türkçesi ({@code <tür>.<kod>}); arayüz karşılığı {@code exec.enum.<tür>.<kod>}
     * (kapı: {@code ExecutiveSummaryI18nGateTest}).
     */
    public static final java.util.Map<String, String> ENUM_TR = java.util.Map.of(
            "renewal_class.ON_TIME", "Zamanında",
            "renewal_class.LATE", "Geç",
            "renewal_class.LAST_MINUTE", "Son dakika",
            "renewal_class.AFTER_EXPIRY", "Süresi dolduktan sonra",
            "overdue_reason.EXPIRED", "Süresi dolmuş",
            "overdue_reason.NO_PLAN", "Plan yok",
            "overdue_reason.PLAN_PASSED", "Plan tarihi geçti");

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd.MM.yyyy");
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");

    /** "Eylül 2026". */
    public static String monthLabel(YearMonth m) {
        return MONTHS_TR[m.getMonthValue() - 1] + " " + m.getYear();
    }

    /** Yüzde, en fazla {@code digits} ondalık, Türkçe ondalık virgül: 99.951 → "%99,95". null → "—". */
    public static String pct(Double v, int digits) {
        if (v == null || v.isNaN()) return "—";
        return "%" + num(v, digits);
    }

    public static String pct(Double v) {
        return pct(v, 2);
    }

    /** Sayı, sondaki sıfırlar atılmış: 99.90 → "99,9", 100.0 → "100". */
    public static String num(double v, int digits) {
        String s = String.format(Locale.ROOT, "%." + digits + "f", v);
        if (s.contains(".")) s = s.replaceAll("0+$", "").replaceAll("\\.$", "");
        return s.replace('.', ',');
    }

    /** Yüzde puanı farkı: +0,12 / −0,30 / 0. */
    public static String pp(Double v) {
        if (v == null || v.isNaN()) return "—";
        if (Math.abs(v) < 0.005) return "0 puan";
        return (v > 0 ? "+" : "−") + num(Math.abs(v), 2) + " puan";
    }

    /** Dakika → "45 dk" / "2 sa 5 dk" / "3 gün 4 sa". null → "—". */
    public static String minutes(Double m) {
        if (m == null || m.isNaN()) return "—";
        long total = Math.round(m);
        if (total < 60) return total + " dk";
        long h = total / 60, mm = total % 60;
        if (h < 24) return mm == 0 ? h + " sa" : h + " sa " + mm + " dk";
        long d = h / 24, hh = h % 24;
        return hh == 0 ? d + " gün" : d + " gün " + hh + " sa";
    }

    /** Gün → "12 gün" / "süresi 3 gün önce doldu". */
    public static String days(Integer d) {
        if (d == null) return "—";
        if (d < 0) return Math.abs(d) + " gün önce doldu";
        if (d == 0) return "bugün";
        return d + " gün";
    }

    /** ISO gün ("2026-09-30") → "30.09.2026"; ayrıştırılamazsa olduğu gibi. */
    public static String date(String isoDay) {
        if (isoDay == null || isoDay.isBlank()) return "—";
        try {
            return LocalDate.parse(isoDay.length() >= 10 ? isoDay.substring(0, 10) : isoDay).format(DAY);
        } catch (Exception e) {
            return isoDay;
        }
    }

    /** UTC Instant → İstanbul "dd.MM.yyyy HH:mm". */
    public static String stamp(Instant t) {
        return t == null ? "—" : t.atZone(ExecutiveSummaryContext.IST).format(STAMP);
    }

    /** Değeri sütun/gösterge biçimiyle Türkçe yazar (posta ve PDF'in TEK biçimleyicisi). */
    public static String value(Object v, String format) {
        String f = format == null ? "text" : format;
        // Boş değerin anlamı türe göre: takımsız / gruplanmamış / seviyesiz — "—" değil.
        if (v == null) {
            return switch (f) {
                case "team" -> "Takımsız";
                case "service" -> "Gruplanmamış";
                case "tier" -> "Atanmamış";
                default -> "—";
            };
        }
        try {
            return switch (f) {
                case "tier" -> v instanceof Number n && n.intValue() >= 1 && n.intValue() <= 4
                        ? "Seviye " + n.intValue() : "Atanmamış";
                case "monitor_type" -> com.sitemonitor.service.MonitorTypeCatalog.label(String.valueOf(v));
                case "renewal_class", "overdue_reason" -> ENUM_TR.getOrDefault(f + "." + v, String.valueOf(v));
                case "pct_change" -> v instanceof Number n ? pctChange(n.doubleValue()) : String.valueOf(v);
                case "int" -> v instanceof Number n ? String.valueOf(Math.round(n.doubleValue())) : String.valueOf(v);
                case "pct" -> v instanceof Number n ? pct(n.doubleValue()) : String.valueOf(v);
                case "pp" -> v instanceof Number n ? pp(n.doubleValue()) : String.valueOf(v);
                case "minutes" -> v instanceof Number n ? minutes(n.doubleValue()) : String.valueOf(v);
                case "days" -> v instanceof Number n ? days(n.intValue()) : String.valueOf(v);
                case "date" -> date(String.valueOf(v));
                case "bool" -> Boolean.TRUE.equals(v) ? "Evet" : "Hayır";
                case "status" -> statusWord(String.valueOf(v));
                default -> String.valueOf(v);
            };
        } catch (Exception e) {
            return String.valueOf(v);
        }
    }

    /** Yüzde değişim: +%12,5 / −%3 / değişmedi. */
    public static String pctChange(Double v) {
        if (v == null || v.isNaN()) return "—";
        if (Math.abs(v) < 0.05) return "değişmedi";
        return (v > 0 ? "▲ %" : "▼ %") + num(Math.abs(v), 1);
    }

    /** Gösterge farkı ({@code Kpi.delta}) — biçimine göre ("pp" | "pct_change" | sayı). */
    public static String delta(Double v, String format) {
        if (v == null) return null;
        if ("pp".equals(format)) {
            if (Math.abs(v) < 0.005) return "geçen ayla aynı";
            return (v > 0 ? "▲ " : "▼ ") + num(Math.abs(v), 2) + " puan · geçen aya göre";
        }
        if ("pct_change".equals(format)) return pctChange(v) + " · geçen aya göre";
        return (v > 0 ? "▲ " : v < 0 ? "▼ " : "") + num(Math.abs(v), 1);
    }

    /** Ton → kısa Türkçe sözcük (renk tek başına bilgi taşımasın). */
    public static String statusWord(String tone) {
        if (tone == null) return "—";
        return switch (tone) {
            case SectionResult.T_OK -> "Uygun";
            case SectionResult.T_WARN -> "Takip";
            case SectionResult.T_BAD -> "Kritik";
            case SectionResult.T_INFO -> "Bilgi";
            default -> "—";
        };
    }

    /** Bölüm durumu → rozet metni. */
    public static String sectionStatusLabel(String status) {
        if (status == null) return "VERİ YOK";
        return switch (status) {
            case SectionResult.OK -> "SORUNSUZ";
            case SectionResult.ATTENTION -> "TAKİP GEREKLİ";
            case SectionResult.CRITICAL -> "AKSİYON GEREKLİ";
            case SectionResult.ERROR -> "HESAPLANAMADI";
            default -> "VERİ YOK";
        };
    }
}
