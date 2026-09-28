package com.sitemonitor.service.mail;

/**
 * E-posta tasarım belirteçleri — uygulamanın shadcn (zinc) dilinin e-posta karşılığı
 * (e-posta yeniden tasarımı 2026-09-26; kaynak: frontend {@code styles/globals.css}).
 *
 * <p>Tüm renkler DÜZ HEX'tir: Outlook (Word motoru) {@code rgba()} ve 8 haneli hex'i düşürür.
 * Renk yalnız bu sınıftan okunur; şablonlarda serbest hex yazmak yeni bir "palet" doğurur
 * ve e-postalar yeniden birbirinden kopar.
 */
public final class MailTokens {

    private MailTokens() { }

    /** Dış zemin — zinc-100. */
    public static final String BG = "#f4f4f5";
    /** Kart zemini. */
    public static final String CARD = "#ffffff";
    /** Kenarlık — zinc-200 (shadcn {@code --border}). */
    public static final String BORDER = "#e4e4e7";
    /** Tablo satır ayracı — kenarlıktan bir ton açık. */
    public static final String DIVIDER = "#f4f4f5";
    /** Ana metin — zinc-950 (shadcn {@code --foreground}). */
    public static final String FG = "#09090b";
    /** İkincil metin — zinc-500 (shadcn {@code --muted-foreground}). */
    public static final String MUTED = "#71717a";
    /** Tablo başlığı / footer zemini — zinc-50. */
    public static final String SUBTLE = "#fafafa";
    /** İkincil rozet/buton zemini — zinc-100 (shadcn {@code --secondary}). */
    public static final String SECONDARY = "#f4f4f5";
    /** Birincil — uygulamanın {@code --primary}'si. */
    public static final String PRIMARY = "#2563eb";
    public static final String DESTRUCTIVE = "#dc2626";
    public static final String SUCCESS = "#16a34a";
    public static final String WARNING = "#d97706";
    /** İlerleme çubuğu boş kısmı. */
    public static final String TRACK = "#e4e4e7";
    /**
     * Karanlık tuval — zinc-950 (shadcn koyu {@code --background}). YALNIZ {@code MailDoc.darkCanvas()} açan
     * e-postada ve {@code prefers-color-scheme:dark} altında dış zemine uygulanır; kart AÇIK kalır
     * ({@code LIGHT_SCHEME_META} kilidi — 2026-09-28, Haftalık Erişilebilirlik yeniden tasarımı).
     */
    public static final String DARK_CANVAS = "#09090b";

    /** Yazı ailesi — web font YÜKLENMEZ; Inter kuruluysa o, değilse sistem fontu. Outlook için
     *  head'deki MSO bloğu Segoe UI'ı zorlar (ilk font kurulu değilse Word Times New Roman'a düşer). */
    public static final String FONT = "Inter,'Segoe UI',-apple-system,BlinkMacSystemFont,Roboto,'Helvetica Neue',Arial,sans-serif";
    public static final String MONO = "ui-monospace,SFMono-Regular,Menlo,Consolas,'Liberation Mono','Courier New',monospace";

    /** Kart köşesi (shadcn {@code --radius} 0.625rem). */
    public static final int RADIUS_CARD = 10;
    /** Buton / rozet / iç kart köşesi. */
    public static final int RADIUS_CONTROL = 6;
    public static final int RADIUS_INNER = 8;

    /** Kart azami genişliği: bildirimler 600, veri-yoğun raporlar 640 (eski 850 ailesi kalktı). */
    public static final int WIDTH = 600;
    public static final int WIDTH_WIDE = 640;
    /** Kartın yatay iç boşluğu (masaüstü) — içerik genişliği = kart − 2×GUTTER. */
    public static final int GUTTER = 24;

    /** Yazı ölçeği (px): gövde 15 (mobilde asla 14'ün altı), etiket 13, meta 12, başlık 22 (mobil 20). */
    public static final int TEXT = 15;
    public static final int TEXT_SM = 14;
    public static final int LABEL = 13;
    public static final int META = 12;
    public static final int H1 = 22;
    public static final int H1_MOBILE = 20;

    /**
     * Anlam tonu — uyarı kutusu, rozet ve vurgu renkleri. {@code strong} ikon/çubuk/rakam rengi;
     * {@code bg}/{@code border}/{@code text} tonlu kutunun zemini, kenarı ve yazısı.
     */
    public enum Tone {
        DESTRUCTIVE("#dc2626", "#fef2f2", "#fecaca", "#991b1b", "!"),
        SUCCESS("#16a34a", "#f0fdf4", "#bbf7d0", "#166534", "&#10003;"),
        WARNING("#d97706", "#fffbeb", "#fde68a", "#92400e", "!"),
        INFO("#2563eb", "#eff6ff", "#bfdbfe", "#1e40af", "i"),
        NEUTRAL("#71717a", "#fafafa", "#e4e4e7", "#09090b", "i");

        public final String strong;
        public final String bg;
        public final String border;
        public final String text;
        /** İkon dairesindeki glif (HTML). */
        public final String glyph;

        Tone(String strong, String bg, String border, String text, String glyph) {
            this.strong = strong;
            this.bg = bg;
            this.border = border;
            this.text = text;
            this.glyph = glyph;
        }
    }
}
