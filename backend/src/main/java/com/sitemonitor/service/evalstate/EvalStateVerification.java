package com.sitemonitor.service.evalstate;

/**
 * Doğrulama turu (VERIFY) sözleşmesi (2026-10-23, çok pod Sürüm 3-1 P0; tasarım R3-1 §2.6). Uygulayan:
 * {@code EvalStateVerifier} (paket A2 — bellek anlık görüntüsü {@code MonitoringOutageService.verifySnapshot()} ile
 * tabloları karşılaştırır, sonucu {@link EvalVerifyReport#OBSERVATION} adıyla yayımlar). Çağıran: kaydedici (paket A1),
 * KENDİ yazıcı iş parçacığında (zamanlayıcı havuzunda asla), her {@code site.monitor.eval-state.verify-ms}'de bir, yalnız
 * bu pod lider + kuşak sahibi + kip {@link EvalStateMode#VERIFY} iken. Kaydedici doğrulayıcıyı tembel çözer
 * ({@code ObjectProvider}) — doğrulayıcı → MOS → kaydedici bağımlılığı döngü kurmaz.
 *
 * <p>Asla fırlatmaz: okuma hatası → {@link EvalVerifyReport#unknown}. Karar yolu bu sonucu OKUMAZ (I-1).
 */
public interface EvalStateVerification {

    /** Bir doğrulama turu koşar; sonuç kaydedicinin durumunda saklanır. */
    EvalVerifyReport verify(Context ctx);

    /**
     * Kaydedicinin doğrulayıcıya açtığı yüz (tur başına; yarışsız algoritma — tasarım §2.6):
     * {@code s0 = seq()} → anlık görüntü → {@code s1 = seq()} → {@code awaitFlushed(s1, 5 sn)} (false → tur
     * {@link EvalVerifyReport#REASON_FLUSH_LAG}) → yalnız {@code gen = generation()} satırlarını oku → karşılaştır,
     * {@code changedSince(tablo, anahtar, s0)} true olan anahtarları ATLA.
     */
    interface Context {

        /** Bu pod'un örnek kimliği (satırların {@code writer} değeri). */
        String writer();

        /** Bu pod'un tuttuğu kuşak (> 0). */
        long generation();

        /** Kuşağın alındığı an (kaydedici saati, ms) — {@code round_seen} karşılaştırmasının alt sınırı. */
        long generationStartMs();

        /** Kaydedicinin saati (ms) — TTL / tazeleme payı hesapları. */
        long nowMs();

        /** Son AYRILAN işlem sıra numarası. */
        long seq();

        /**
         * {@code seq}'e kadar (dahil) her işlem tablolara yazılana (ya da düşüp anahtarı kirli işaretlenene) dek en çok
         * {@code timeoutMs} bekler; zamanında olduysa true. Kesintide false döner (kesinti bayrağı korunur).
         */
        boolean awaitFlushed(long seq, long timeoutMs);

        /** Anahtar {@code seq}'ten SONRA kuyruğa alındı mı ya da kirli mi (üst sınırda düştü) — true ise karşılaştırılmaz. */
        boolean changedSince(EvalStateKeys.Table table, String key, long seq);

        /** {@code monitor_last_status} tazeleme aralığı (ms) — kardeş gözleminin {@code atMs} toleransı. */
        long lastStatusRefreshMs();

        /**
         * Bu bağlam hâlâ geçerli mi: bağlam kurulduğundan beri liderlik dönemi değişmedi, kuşak bırakılmadı ve pod çitlenmedi
         * (2026-10-23, R3-1 FX-STATE). false → tur {@link EvalVerifyReport#REASON_LEADERSHIP_CHANGED} ve rapor
         * YAYIMLANMAZ (artık kuşak sahibi olmayan pod küme gözlemini ezmesin). Varsayılan true (sabit test bağlamları).
         */
        default boolean stillCurrent() {
            return true;
        }
    }
}
