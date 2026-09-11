package com.sitemonitor.util;

/**
 * CSV hücresi kaçışı — dışa aktarımların TEK kuralı.
 *
 * <p><b>İki ayrı sorunu birden çözer.</b>
 *
 * <p>1) <b>Alan ayrımı.</b> İçinde virgül, noktalı virgül, tırnak veya satır sonu geçen değer
 * tırnaklanır; tırnaklar ikilenir. {@code \r} de sayılır: satırlar {@code \r\n} ile birleştiği
 * için tek başına bir CR, satırı ortasından bölerdi.
 *
 * <p>2) <b>Formül enjeksiyonu (CWE-1236).</b> {@code =}, {@code +}, {@code -}, {@code @} (ve
 * sekme/CR) ile BAŞLAYAN bir hücreyi Excel/LibreOffice FORMÜL sayar. {@code =cmd|'/c calc'!A1}
 * biçiminde bir domain adı, alarm mesajı ya da not, dosyayı açan kişinin makinesinde komut
 * çalıştırma denemesine dönüşebilir. Başa tek tırnak konarak hücrenin METİN olduğu sabitlenir.
 *
 * <p><b>Neden ortak sınıf.</b> Bu koruma projede vardı ama yalnız {@code AdminController}'da:
 * denetim kaydı dışa aktarımı, kontrol geçmişi CSV'si ve arayüzdeki dört dışa aktarım aynı
 * kuralı uygulamıyordu (2026-08-23 denetimi). Oysa besledikleri veri daha da dışarıdan geliyor —
 * kontrol geçmişindeki hata metnini UZAK SUNUCU üretiyor. Kural tek yerde durmazsa bir sonraki
 * dışa aktarım yine korumasız yazılır.
 */
public final class Csv {

    private Csv() { }

    /** Formül nötrleme + tırnaklama uygulanmış hücre. null/boş → boş dize. */
    public static String cell(Object value) {
        if (value == null) return "";
        String s = String.valueOf(value);
        if (s.isEmpty()) return "";

        char c0 = s.charAt(0);
        if (c0 == '=' || c0 == '+' || c0 == '-' || c0 == '@' || c0 == '\t' || c0 == '\r') {
            s = "'" + s;
        }
        boolean needQuote = s.indexOf(',') >= 0 || s.indexOf('"') >= 0
                || s.indexOf('\n') >= 0 || s.indexOf('\r') >= 0 || s.indexOf(';') >= 0;
        String v = s.replace("\"", "\"\"");
        return needQuote ? "\"" + v + "\"" : v;
    }

    /**
     * Tam satır: her hücre {@link #cell(Object)} ile kaçırılır, virgülle birleşir, CRLF ile biter.
     * Dışa aktarımlar kendi {@code csvRow} yardımcısını yazmasın diye (2026-09-11: iki controller aynı
     * korumasız kopyayı taşıyordu — {@code CsvExportGuardTest}).
     */
    public static String row(Object... cells) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cells.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(cell(cells[i]));
        }
        return sb.append("\r\n").toString();
    }
}
