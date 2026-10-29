package com.sitemonitor.service.checks;

/**
 * Veritabanı-çapalı saat (2026-10-29, çok pod Sürüm 3-3 P0 sözleşmesi, tasarım R3-3 §3.3; paket A uygular). İş tablosunun
 * tüm zaman alanları ({@code created_ms}, {@code claimed_ms}, {@code lease_until_ms}, {@code finished_ms}) ve kira bitiş
 * karşılaştırması bu saatle yazılır — kiralar pod saatlerine dayanmaz (pod saatleri arasında 5–30 sn fark R2b'den beri
 * izlenir, kira ise 150 sn).
 *
 * <p>Uygulama: çapa = {@code SELECT CURRENT_TIMESTAMP} (örnekleyicinin deyimi, {@code SystemMetricSampler.SQL_DB_NOW})
 * {@link #ANCHOR_MS}'de bir; {@code nowMs() = çapaDb + (nanoTime − çapaNano) / 1e6} (ek sorgu yok). Çapa okunamazsa
 * {@link #MAX_EXTRAPOLATE_MS}'e dek eski çapadan ilerler, sonra pod saatine düşer ve {@link #stale()} true döner (Küme kartı
 * {@code db_clock_stale}). Hiç okunmadıysa pod saati + stale. Asla fırlatmaz, istek / karar iş parçacığında sorgu yapmaz
 * (çapa yenilemesi kendi iş parçacığında ya da tembel, eşik geçince).
 *
 * <p>Zamanlama tablosu ({@code monitor_check_schedule}) bugünkü pod saatiyle kalır — bu saat yalnız iş tablosu içindir.
 */
public interface DbClock {

    /** Çapa yenileme aralığı (ms). */
    long ANCHOR_MS = 30_000L;
    /** Çapa okunamadan en çok bu kadar ileri tahmin edilir (ms); sonra pod saati. */
    long MAX_EXTRAPOLATE_MS = 300_000L;

    /** Şimdi (epoch ms, veritabanı saatine çapalı). */
    long nowMs();

    /** Değer şu an pod saatinden mi geliyor (çapa hiç alınamadı ya da {@link #MAX_EXTRAPOLATE_MS}'i aştı). */
    boolean stale();

    /** Pod saati (çapasız) — yalnız testler ve {@link #stale()} yedeği için. */
    DbClock SYSTEM = new DbClock() {
        @Override
        public long nowMs() {
            return System.currentTimeMillis();
        }

        @Override
        public boolean stale() {
            return true;
        }
    };
}
