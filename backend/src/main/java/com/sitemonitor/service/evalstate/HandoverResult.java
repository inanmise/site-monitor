package com.sitemonitor.service.evalstate;

import java.util.Locale;

/**
 * Zarif kapanışta "bırakmadan önce boşalt" adımının sonucu (2026-10-28, çok pod Sürüm 3-2 P0; tasarım R3-2 §5). Üreten:
 * {@link EvalStateSink#flushForHandover(long, boolean)} (kaydedici, paket A); çağıran: {@code SchedulerService.onContextClosed}
 * (paket C) — yalnız ayar AÇIK ve {@link EvalStateSink#handoverReady()} true iken. Saklanan:
 * {@link EvalStateSink.Status#lastHandover()} (yalnız kapanan pod yanıt verirken görünür). Sonuç kararı değiştirmez; yalnız
 * halefin hangi katmanı yükleyebileceğini belirler ({@link #WRITTEN} → tam devir mümkün, diğerleri → en çok gözlemler).
 *
 * <p>Adlar ve {@link #key()} değerleri KALICIDIR (Sistem Sağlığı yanıtı, günlük).
 */
public enum HandoverResult {

    /** Bekleyen her işlem yazıldı, kendi zincir satırları bırakıldı, devir işareti ({@code handover_ms}) SON deyim olarak yazıldı. */
    WRITTEN,
    /** Değerlendirmeler 300 ms'de durulmadı — veri yazıldı, işaret YAZILMADI (halef en çok gözlemler). */
    NOT_QUIESCENT,
    /** Kuşak eksik (eşitleme boşaltılmadı / düşüş / bekleyen kaldı) — işaret YAZILMADI. */
    INCOMPLETE,
    /** Tutulan kuşak yok — hiçbir şey yazılmadı. */
    NO_GENERATION,
    /** Kalp atışı 0 satır — daha yeni bir kuşak alınmış (çit dışı); hiçbir şey yazılmadı. */
    FENCED,
    /** Boşaltma deyimi başarısız — işaret YAZILMADI. */
    FLUSH_FAILED,
    /** 700 ms bütçesi aşıldı (kilit alınamadı ya da yardımcı iş parçacığı bitmedi) — işaret YAZILMADI. */
    BUDGET_EXCEEDED,
    /** Adım koşmadı (kaydedici yok / NOOP / devir hazır değil). */
    SKIPPED;

    /** Kalıcı küçük harfli kod ({@code written}, {@code not_quiescent}, …) — yanıt ve ekran sözlüğü bununla. */
    public String key() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** Devir işareti yazıldı mı (halef tam katmanı yükleyebilir). */
    public boolean markerWritten() {
        return this == WRITTEN;
    }
}
