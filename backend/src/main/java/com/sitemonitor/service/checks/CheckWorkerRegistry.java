package com.sitemonitor.service.checks;

import java.util.List;
import java.util.Set;

/**
 * Canlı kontrol işçileri {@code monitor_check_workers} (2026-10-29, çok pod Sürüm 3-3 P0 sözleşmesi, tasarım R3-3 §3.1 /
 * §5.3 / §6; paket A uygular: {@code CheckWorkerRegistryImpl}). Her işçi anahtar açıkken {@link #HEARTBEAT_MS}'de bir kendi
 * satırını yazar; lider kip kararı ({@code SchedulerService.distributedMode(T)}, paket B) ve Küme kartı (paket D) okur.
 *
 * <p><b>Canlı</b> = son kalp atışı {@link #LIVE_MS} içinde (DB saati, {@link DbClock}) VE {@code stopping} değil. Okumalar
 * önbellekli ({@link #READ_CACHE_MS}); istek / karar iş parçacığında sorgu yapmaz. Anahtar kapalıyken hiç satır yazılmaz,
 * hiç okunmaz (K-1).
 */
public interface CheckWorkerRegistry {

    /** Kalp atışı aralığı (ms). */
    long HEARTBEAT_MS = 10_000L;
    /** Bu kadar sessiz işçi canlı sayılmaz (ms). */
    long LIVE_MS = 30_000L;
    /** Lider tablo okumasını bu sıklıkla tazeler (ms). */
    long READ_CACHE_MS = 10_000L;
    /** Bu kadar eski kalp atışı satırı lider tarafından saatlik silinir (ms). */
    long PRUNE_AFTER_MS = 86_400_000L;
    /** {@link #liveOtherWorkers} sonucu: tablo okunamadı (→ çağıran KLASİK kipe düşer, asla "bilinmeyen işçilerle dağıtık"). */
    int UNKNOWN = -1;

    /**
     * BU POD DIŞINDAKİ canlı işçilerden kaç tanesi {@code type} türünü sahiplenebiliyor ({@code caps} içeriyor); okunamadıysa
     * {@link #UNKNOWN}. Bellek önbelleğinden (≤ {@link #READ_CACHE_MS} eski) döner.
     */
    int liveOtherWorkers(String type);

    /** Bu pod şu an hangi türleri sahiplenebilir: işçi açık ∧ (ping → ICMP var) ∧ (scripted → k6 var) — yerel yoklama. */
    Set<String> localCapabilities();

    /** Tüm bilinen işçiler (son okuma; Küme kartı). Okunamadıysa boş liste. */
    List<WorkerView> workers();

    /**
     * Bir işçinin satırı.
     *
     * @param instanceId     örnek kimliği ({@code SchedulerService.instanceId()})
     * @param pod            pod adı ({@code ClusterHealthStore.podOf(instanceId)})
     * @param caps           sahiplenebildiği türler
     * @param slots          yuva sayısı
     * @param busy           şu an koşan kontrol
     * @param completed      açılıştan beri tamamlanan
     * @param late           açılıştan beri kirası kaybedilip düşen sonuç
     * @param claimLagP95Ms  son pencerenin sahiplenme gecikmesi p95 (ms; bilinmiyorsa null)
     * @param heartbeatMs    son kalp atışı (DB saati, ms)
     * @param version        uygulama sürümü
     * @param stopping       kapanıyor (yeni iş almaz)
     * @param live           {@link #LIVE_MS} kuralına göre canlı
     */
    record WorkerView(String instanceId, String pod, Set<String> caps, int slots, int busy, long completed, long late,
                      Long claimLagP95Ms, long heartbeatMs, String version, boolean stopping, boolean live) {
        public WorkerView {
            caps = caps == null ? Set.of() : CheckJobTypes.canonical(caps);
        }
    }
}
