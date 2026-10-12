package com.sitemonitor.service.access;

/**
 * Takım erişim paylaşımı düzeyi (2026-10-12) — KÜMÜLATİF: üst düzey alttakilerin hepsini içerir.
 * <ul>
 *   <li>{@link #VIEW} — paylaşan takımın izleme verisini okur (takım üyesi gibi);</li>
 *   <li>{@link #EXECUTE} — + şimdi kontrol et, tanıla, TLS yeniden tara, sağlık yenile, alarm onayla / çöz / yeniden bildir;</li>
 *   <li>{@link #EDIT} — + o takıma izleme / envanter ekle, düzenle;</li>
 *   <li>{@link #DELETE} — + o takımın izleme / envanter kayıtlarını sil.</li>
 * </ul>
 * Rol izin matrisi ayrıca uygulanır: paylaşım takım KAPSAMIDIR, eylem izni rolden gelir.
 */
public enum TeamAccessLevel {
    VIEW, EXECUTE, EDIT, DELETE;

    public boolean atLeast(TeamAccessLevel other) {
        return ordinal() >= other.ordinal();
    }

    /** Bilinmeyen / boş değer → null (çağıran reddeder ya da yok sayar). */
    public static TeamAccessLevel parse(String raw) {
        if (raw == null) return null;
        try {
            return valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public static TeamAccessLevel max(TeamAccessLevel a, TeamAccessLevel b) {
        if (a == null) return b;
        if (b == null) return a;
        return a.ordinal() >= b.ordinal() ? a : b;
    }
}
