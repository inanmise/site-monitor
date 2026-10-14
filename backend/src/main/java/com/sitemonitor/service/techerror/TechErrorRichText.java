package com.sitemonitor.service.techerror;

import java.util.ArrayList;
import java.util.List;

/**
 * Teknik hata raporundaki açıklamaların BİÇİMİ (2026-10-14, kullanıcı isteği: "madde, alt madde, kalın yazı"). Düz
 * metin üzerinde küçük, öngörülebilir bir işaretleme — uygulama ({@code techErrorModel.parseRichText}), PDF ve e-posta
 * AYNI kuralla okur:
 * <ul>
 *   <li>{@code - }, {@code * } ya da {@code • } ile başlayan satır madde; başındaki her 2 boşluk bir alt düzey (en çok
 *       3 düzey);</li>
 *   <li>{@code 1. } / {@code 1) } ile başlayan satır numaralı madde (numara yazıldığı gibi kalır);</li>
 *   <li>{@code **kalın**} satır içinde kalın yazı. İşaret TAM iki yıldızdır; açılıştan sonra ve kapanıştan önce boşluk
 *       olmaz. Daha uzun yıldız dizisi ("4543 **** 1234" gibi maskeli kart numarası) ve eşi olmayan işaret olduğu gibi
 *       görünür (inceleme 2026-10-14: eski kural her {@code **}'ı işaret sayıp önceki sürümde yazılmış açıklamalardaki
 *       yıldızları siliyordu);</li>
 *   <li>diğer satırlar paragraf satırıdır (satır sonu korunur, girinti düzeyi korunur); boş satır paragraf aralığı.</li>
 * </ul>
 * HTML yoktur, kaçış gerekmez. Ayrıştırma doğrusaldır (satır ve karakter başına sabit iş), blok sayısı sınırlıdır.
 * "Boşluk" iki tarafta da {@link #ws(char)}'dır (Java {@code isWhitespace} + bölünmez boşluklar): Word / Outlook'tan
 * yapıştırılan bölünmez boşluklu boş satır ekranda ve PDF'te aynı şekilde paragraf aralığıdır.
 */
public final class TechErrorRichText {

    private TechErrorRichText() {}

    public static final int MAX_LEVEL = 2;
    public static final int MAX_BLOCKS = 2_000;

    /**
     * Ayrıştırıcının boşluk tanımı — uygulamadaki {@code techErrorModel.isRichSpace} ile AYNI küme: Java
     * {@code Character.isWhitespace} artı bölünmez boşluklar (U+00A0, U+2007, U+202F) ve U+FEFF.
     */
    public static boolean ws(char c) {
        return Character.isWhitespace(c) || c == ' ' || c == ' ' || c == ' ' || c == '﻿';
    }

    /** {@link #ws(char)} kümesine göre iki uçtan kırpar. */
    public static String strip(String s) {
        if (s == null) return "";
        int a = 0, b = s.length();
        while (a < b && ws(s.charAt(a))) a++;
        while (b > a && ws(s.charAt(b - 1))) b--;
        return s.substring(a, b);
    }

    /** {@link #ws(char)} kümesine göre boş mu? */
    public static boolean blank(String s) {
        if (s == null) return true;
        for (int i = 0; i < s.length(); i++) if (!ws(s.charAt(i))) return false;
        return true;
    }

    public record Run(String text, boolean bold) { }

    /**
     * Bir satır. {@code kind}: {@code p} (paragraf satırı), {@code li} (madde), {@code gap} (paragraf aralığı).
     * {@code marker}: madde işareti — {@code null} (numarasız madde, düzeye göre çizilir) ya da "3." gibi numara.
     */
    public record Block(String kind, int level, String marker, List<Run> runs) {
        public boolean gap() { return "gap".equals(kind); }
        public boolean item() { return "li".equals(kind); }
        public String plain() {
            StringBuilder b = new StringBuilder();
            for (Run r : runs) b.append(r.text());
            return b.toString();
        }
    }

    public static List<Block> parse(String text) {
        List<Block> out = new ArrayList<>();
        if (text == null || text.isEmpty()) return out;
        String[] lines = text.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        boolean pendingGap = false;
        for (String line : lines) {
            if (out.size() >= MAX_BLOCKS) break;
            String raw = tabs(line);
            if (blank(raw)) {
                if (!out.isEmpty()) pendingGap = true;
                continue;
            }
            if (pendingGap) {
                out.add(new Block("gap", 0, null, List.of()));
                pendingGap = false;
            }
            int i = 0, spaces = 0;
            while (i < raw.length() && raw.charAt(i) == ' ') {
                spaces++;
                i++;
            }
            int level = Math.min(MAX_LEVEL, spaces / 2);
            String rest = raw.substring(i);
            if (rest.length() >= 2 && (rest.startsWith("- ") || rest.startsWith("• ") || (rest.charAt(0) == '*' && rest.charAt(1) == ' '))) {
                out.add(new Block("li", level, null, inline(strip(rest.substring(2)))));
                continue;
            }
            int d = 0;
            // Yalnız ASCII rakam (uygulama da 0–9 okur; Character.isDigit başka alfabelerin rakamlarını da kabul ederdi)
            while (d < rest.length() && d < 3 && rest.charAt(d) >= '0' && rest.charAt(d) <= '9') d++;
            if (d > 0 && d + 1 < rest.length() && (rest.charAt(d) == '.' || rest.charAt(d) == ')') && rest.charAt(d + 1) == ' ') {
                out.add(new Block("li", level, rest.substring(0, d) + ".", inline(strip(rest.substring(d + 2)))));
                continue;
            }
            out.add(new Block("p", level, null, inline(strip(rest))));
        }
        return out;
    }

    /**
     * Sekme, kaydedilen metindeki gibi ({@link TechErrorTemplate#multiline}): satır başındaki her sekme iki boşluk (bir
     * alt düzey), satır içindeki tek boşluk. Önizleme ile PDF aynı satırı aynı okusun ("1.\tMadde" iki tarafta da madde).
     */
    static String tabs(String line) {
        if (line.indexOf('\t') < 0) return line;
        StringBuilder b = new StringBuilder(line.length() + 8);
        boolean lead = true;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '\t') b.append(lead ? "  " : " ");
            else {
                b.append(c);
                if (c != ' ') lead = false;
            }
        }
        return b.toString();
    }

    /** Unicode noktalama ya da simge (CommonMark'ın "punctuation" tanımı: P* ve S* kategorileri). */
    static boolean punct(char c) {
        switch (Character.getType(c)) {
            case Character.CONNECTOR_PUNCTUATION, Character.DASH_PUNCTUATION, Character.START_PUNCTUATION,
                 Character.END_PUNCTUATION, Character.INITIAL_QUOTE_PUNCTUATION, Character.FINAL_QUOTE_PUNCTUATION,
                 Character.OTHER_PUNCTUATION, Character.MATH_SYMBOL, Character.CURRENCY_SYMBOL,
                 Character.MODIFIER_SYMBOL, Character.OTHER_SYMBOL -> { return true; }
            default -> { return false; }
        }
    }

    /**
     * Satır içi kalın. Aday işaret = TAM iki yıldızlık dizi. Açabilir / kapatabilir CommonMark'ın bitişik (flanking)
     * kuralıyla: açılıştan sonra boşluk olmaz ve sonraki karakter noktalamaysa öncesi boşluk / noktalama / satır başı
     * olmalı; kapanış bunun aynası (inceleme 2026-10-14: "ab**@x.com ve cd**@y.com" gibi kelime içi maskeler eşleşip
     * yıldızlar siliniyordu). Soldan sağa: açık bir işaret varken kapatabilen aday onu kapatır, yoksa açabilen aday yeni
     * açılış olur. Eşlenmeyen her şey düz metindir — yıldız SİLİNMEZ. Doğrusal (karakter başına sabit iş).
     */
    public static List<Run> inline(String s) {
        List<Run> out = new ArrayList<>();
        if (s == null || s.isEmpty()) return out;
        int n = s.length();
        List<int[]> pairs = new ArrayList<>();
        int open = -1;
        for (int i = 0; i < n; ) {
            if (s.charAt(i) != '*') { i++; continue; }
            int j = i;
            while (j < n && s.charAt(j) == '*') j++;
            if (j - i == 2) {
                char before = i > 0 ? s.charAt(i - 1) : ' ', after = j < n ? s.charAt(j) : ' ';
                boolean beforeWs = i == 0 || ws(before), afterWs = j >= n || ws(after);
                boolean canOpen = !afterWs && (!punct(after) || beforeWs || punct(before));
                boolean canClose = !beforeWs && (!punct(before) || afterWs || punct(after));
                if (open >= 0 && canClose) {
                    pairs.add(new int[]{ open, i });
                    open = -1;
                } else if (canOpen) {
                    open = i;
                }
            }
            i = j;
        }
        int pos = 0;
        for (int[] p : pairs) {
            if (p[0] > pos) out.add(new Run(s.substring(pos, p[0]), false));
            out.add(new Run(s.substring(p[0] + 2, p[1]), true));
            pos = p[1] + 2;
        }
        if (pos < n) out.add(new Run(s.substring(pos), false));
        return out;
    }

    /** İşaretsiz düz metin (e-postanın metin eşi, karşılaştırmalar): kalın işaretleri düşer, maddeler "- " olur. */
    public static String plain(String text) {
        StringBuilder b = new StringBuilder();
        for (Block bl : parse(text)) {
            if (bl.gap()) {
                b.append('\n');
                continue;
            }
            b.append("  ".repeat(bl.level()));
            if (bl.item()) b.append(bl.marker() == null ? "- " : bl.marker() + " ");
            b.append(bl.plain()).append('\n');
        }
        return b.toString().strip();
    }
}
