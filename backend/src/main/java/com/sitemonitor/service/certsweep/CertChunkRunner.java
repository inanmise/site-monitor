package com.sitemonitor.service.certsweep;

import java.util.List;
import java.util.Map;

/**
 * Bir parçanın ÖLÇÜMÜ (2026-10-29, çok pod Sürüm 3-4 P0 sözleşmesi, tasarım R3-4 §4.4 / §4.6) — paket B sağlar
 * ({@code service/SchedulerCertChunks}, {@code SchedulerService}'in KLASİK toplamasına bağlanır), paket A'nın işçisi
 * ({@code CertChunkWorker}) bir {@code ObjectProvider} üzerinden ve koordinatör ({@code CertRunCoordinator}, B) yerel ölçüm /
 * devralma / ikinci görüş için çağırır. TEK ölçme yolu: klasik tur ile parçalı tur aynı kodu koşar (L-3).
 *
 * <p><b>L-2:</b> ölçüm HİÇBİR satır yazmaz (denetleyici {@code CertificateCheckerService.check} saftır — yalnız günlük
 * satırı); kayıt, önbellek boşaltma, toplu kesinti kuralı ve alarm işleme yalnız koordinatörde, toplamadan SONRA, değişmemiş
 * klasik kodla.
 */
public interface CertChunkRunner {

    /**
     * Satırları klasik toplamayla ölçer — {@code SchedulerService.runCheckForDomains}'in toplama bölümünün AYNISI
     * ({@code gatherCertResults}): her satır için {@code checkerService.checkAsync(domain, port, use_proxy,
     * blankToNull(tls_mode), timeout_seconds)} bu pod'un {@code certCheckExecutor}'ına verilir, sonuçlar İLERLEMEYE bağlı
     * tavanla ({@code awaitCertCheck}, {@code sweep-max-wait-seconds}) SIRAYLA beklenir; her harita kopyalanır ve
     * {@code run_id = runId} eklenir.
     *
     * <ul>
     *   <li><b>Sıra ve uzunluk:</b> dönen liste {@code rows} ile aynı sırada. Kapanış başladıysa ({@link #closing()}) bekleme
     *       bırakılır ve liste DAHA KISA döner — çağıran onu kullanmaz (işçi atar, koordinatör SHUTDOWN ile bırakır).</li>
     *   <li><b>İstisna:</b> klasik toplamadaki gibi bir kontrolün istisnası FIRLAR ({@code CompletableFuture.join}). İşçi
     *       parçayı her öğesi {@code failed} işaretli ({@code CheckJobRunner.failed(t)}) olarak tamamlar; koordinatör onları
     *       kendisi yeniden ölçer — kendi ölçümü de fırlatırsa tur klasikteki gibi düşer (FAILED).</li>
     *   <li>Satır biçimi: {@link CertChunkSpec.Entry#toRow()} (ya da klasik vade listesinin satırı).</li>
     * </ul>
     *
     * @param rows  ölçülecek klasik satırlar (boş → boş liste)
     * @param runId sonuç haritalarına {@code run_id} olarak konacak koşu kimliği
     * @return sonuç haritaları (değiştirilebilir {@code LinkedHashMap}'ler)
     */
    List<Map<String, Object>> checkDomains(List<Map<String, Object>> rows, String runId);

    /**
     * Bu pod kapanıyor mu (bellek): {@code startupCheckStopped || stopping()} — {@code ContextClosedEvent} görüldü ya da kilit
     * servisi yeni iş almayı durdurdu. true iken işçi yeni parça almaz, ölçtüğünü atar; koordinatör SHUTDOWN ile bırakır.
     */
    boolean closing();

    /**
     * Toplu ağ kesintisi oranına giren hata sayısı — {@code SchedulerService.networkErrorCount} (TEK kural:
     * {@code status = error}, sınıf NETWORK / DNS, çözülemeyen ad hariç). Parça satırının {@code network_errors} sayımı ve
     * aykırı pod kuralı (§2.6) bunu kullanır; tur kararı ise klasik kodun kendi sayımıyla verilir.
     */
    long networkErrorCount(List<Map<String, Object>> results);

    /**
     * {@code certCheckExecutor}'ın GÜNCEL çekirdek sayısı (canlı ayar; Ayarlar → Genel → Görev Havuzu) — işçinin bütçesi
     * {@link CertChunking.Tuning#budget(int)} bununla hesaplanır. Okunamazsa ≤ 0 dönebilir (bütçe en az 1 olur).
     */
    int executorCoreSize();
}
