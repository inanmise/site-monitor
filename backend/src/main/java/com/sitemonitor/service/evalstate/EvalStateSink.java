package com.sitemonitor.service.evalstate;

import com.sitemonitor.service.MonitoringOutageService.ConfirmState;
import com.sitemonitor.service.MonitoringOutageService.SweepItem;

import java.util.List;
import java.util.Map;

/**
 * Kalıcı değerlendirme durumu kaydedicisinin yüzü (2026-10-23, çok pod Sürüm 3-1 P0; tasarım R3-1 §2.4). Uygulayan:
 * {@code EvalStateRecorder} (paket A1, pod başına bir tane). Çağıran: {@code MonitoringOutageService} (paket A2) — alan
 * {@code @Autowired(required = false)}; null / {@link #NOOP} = bugünkü davranış.
 *
 * <p><b>Kuyruğa alma API'si — sözleşme</b>
 * <ul>
 *   <li><b>Kararı ASLA değiştirmez (I-1):</b> hiçbir çağrı alarm / kurtarma / bastırma / teyit kararını etkileyemez.
 *       Uygulama asla fırlatmaz; çağıran yine de her çağrıyı {@code RuntimeException} yutan küçük bir yardımcıyla sarar.</li>
 *   <li><b>Veritabanı YOK (I-2):</b> çağrı tarama / istek / teyit iş parçacığında koşar; yalnız bellekteki birleştirici
 *       haritaya ({@code anahtar → son değer, seq}) yazar, beklemez, kilit tutmaz. Tek bir daemon yazıcı boşaltır.</li>
 *   <li><b>Kapı:</b> kip {@link EvalStateMode#OFF} değil VE {@code SchedulerService.sweepLeaderHint()} true VE kapanış
 *       başlamadı — aksi hâlde çağrı hiçbir şey yapmaz (takipçinin yerel elle değerlendirmesi yazmaz).</li>
 *   <li><b>Sıra numarası:</b> her işlem pod'a özgü artan bir {@code seq} alır; {@link #recovery} ve {@link #typeState}
 *       değeri {@link EvalStateSource}'tan seq ALINDIKTAN SONRA okur; birleştiricide anahtar başına en büyük seq kalır.</li>
 *   <li><b>Anahtarlar:</b> {@link EvalStateKeys} (tablo başına birleştirme anahtarı; doğrulayıcı aynı anahtarla sorar).</li>
 * </ul>
 */
public interface EvalStateSink {

    // ── Kuyruğa alma (MonitoringOutageService kancaları, tasarım §2.5) ──────────────────────────────────────────────

    /**
     * Kurtarma durumu değişti ({@code recoveryUpCount} / {@code recoveryLastCountedAt} / {@code recoveryGeneration} /
     * {@code recoveryStartedAt}) — güncel değer {@link EvalStateSource#recoveryValues(String)}'tan okunur.
     *
     * @param rkey      {@code "tür:alan adı"} (MOS'un kurtarma anahtarı, {@link EvalStateKeys#recoveryKey})
     * @param alertType alarm türü (satır PK'sının ilk parçası)
     * @param domain    alan adı (satır PK'sının ikinci parçası; 500 karakterden uzunsa kaydedilmez)
     */
    void recovery(String rkey, String alertType, String domain);

    /**
     * Bir kalemin son gözlenen sonucu ({@code monitor_last_status}) — hem zamanlanmış hem elle sonuçlar (bellek de ikisini
     * kaydeder). Değişince ya da son YAZILAN gözlem {@code last-status-refresh-ms}'den eskiyse yazılır.
     *
     * @param manual elle kontrol mü ({@code source = 'M'}), değilse zamanlanmış ({@code 'S'})
     * @param atMs   gözlem anı (MOS saati)
     */
    void observation(String alertType, SweepItem item, boolean manual, long atMs);

    /**
     * Türün bayrakları değişti (bastırma girişi / çıkışı, ilk zamanlanmış tur, tur aralığı) — güncel değer
     * {@link EvalStateSource#typeValues(String)}'tan okunur. Uygulama ilk-tur damgasını tür başına 60 sn'de bire indirir.
     */
    void typeState(String alertType);

    /**
     * Teyit zinciri başladı ({@code inFlight.putIfAbsent} + {@code chainOwner.put} sonrası).
     *
     * @param confirmKey     MOS'un teyit anahtarı ({@code confirmKey(item)})
     * @param chainId        zincir kimliği ({@code chainOwner} değeri, pod'a özgü)
     * @param state          başlangıç durumu (deneme 0, toplam deneme, sonraki deneme anı)
     * @param firstFailureAt ilk hata anı (UTC {@code yyyy-MM-ddTHH:mm:ss}, = {@code state.startedAt()})
     * @param delayMs        denemeler arası bekleme ({@code effDelayMs(item)}) — tasarımdaki {@code delay_ms} sütunu
     */
    void chainStart(String confirmKey, long chainId, SweepItem item, ConfirmState state, String firstFailureAt,
                    long delayMs);

    /**
     * Zincir ilerledi ({@code inFlight.computeIfPresent} sonrası ve deneme listesine ekleme sonrası).
     *
     * @param attempts denemelerin DEĞİŞMEZ kopyası ({@code List.copyOf}) — çağıran kopyalar, uygulama saklayabilir
     */
    void chainProgress(String confirmKey, long chainId, ConfirmState state, List<Map<String, Object>> attempts);

    /** Son adım alarm kilidi için yeniden denemeye planlandı ({@code finishConfirmed} kilit çakışması; durum FINISHING). */
    void chainFinishing(String confirmKey, long chainId, int lockTry);

    /** Zincir bitti / bırakıldı — yalnız {@code releaseChain} kaydı GERÇEKTEN sildiyse (ya da eski çağrı) çağrılır. */
    void chainEnd(String confirmKey, long chainId);

    /** Tarama çağrısı bitti — bekleyen işlem varsa yazıcıya hemen boşaltma ipucu (beklemez). */
    void sweepEnd(String alertType);

    /**
     * Bellek bir kalemin DOWN gözlemini YENİ bir sonuç olmadan bıraktı (D-5 budaması: TTL doldu ya da hedef artık
     * izlenmiyor — {@code pruneObservations} / {@code downSibling}) → tablodaki satırı da silinir (2026-10-23, R3-1 FX-STATE).
     * Aksi hâlde duraklatılıp yeniden açılan bir izleme, yeniden gözlenene dek bellekte olmayan bir DOWN kardeş satırı
     * bırakırdı. Varsayılan: hiçbir şey (P0 uygulayıcıları derlenir).
     *
     * @param alertType bellek anahtarının türü
     * @param item      budanan kalemin ince kopyası ({@code monitor_id} taşır)
     */
    default void observationPruned(String alertType, SweepItem item) {}

    // ── Devirde okuma ve bırakmadan önce boşaltma (2026-10-28, çok pod Sürüm 3-2 P0; tasarım R3-2 §3.5 / §5) ─────────
    // Varsayılanlar = bugünkü davranış (NOOP ve eski uygulayıcılar değişmeden derlenir).

    /** Devirde değerlendirmelerin durulmasını bekleme üst sınırı (ms; {@code MOS.awaitEvalQuiescent}). */
    long HANDOVER_QUIESCE_MS = 300L;
    /** Devir boşaltmasının üst sınırı (ms; {@link #flushForHandover} yardımcı iş parçacığı birleştirmesi). */
    long HANDOVER_FLUSH_MS = 700L;
    /**
     * Devir adımının lider kirasının bırakılmasını EN ÇOK geciktirebileceği süre (ms) = {@link #HANDOVER_QUIESCE_MS} +
     * {@link #HANDOVER_FLUSH_MS}. Kapanış bütçesi kapısı ({@code ShutdownBudgetGateTest}) bunu saniyeye yuvarlayıp ekler.
     */
    long HANDOVER_BUDGET_MS = 1_000L;

    /**
     * Bu pod'da yükleme yapılabilir mi — GAINED anında MOS sorar (yalnız bellek, veritabanı YOK). null = hazır (kip yazıyor,
     * kapanmıyor, tablolar bilinen eksik değil); aksi hâlde {@link EvalLoadReport} neden kodu
     * ({@link EvalLoadReport#REASON_MODE_OFF}, {@link EvalLoadReport#REASON_RECORDER_CLOSING},
     * {@link EvalLoadReport#REASON_TABLES_MISSING} …). Varsayılan {@link EvalLoadReport#REASON_NO_RECORDER} (kaydedici yok).
     */
    default String loadBlocker() {
        return EvalLoadReport.REASON_NO_RECORDER;
    }

    /**
     * Zarif kapanışta tam devir yapılabilir mi — ayar AÇIK ∧ kuşak tutuluyor ∧ kuşak EKSİKSİZ ∧ çit dışı değil ∧ işaret
     * sütunları var. Yalnız bellek; asla fırlatmaz. Varsayılan false (devir adımı koşmaz — bugünkü kapanış).
     */
    default boolean handoverReady() {
        return false;
    }

    /**
     * Devri aç: kapanış başladıktan sonra biten değerlendirmelerin işlemleri de kuyruğa alınsın (kuyruğa alma kapısı lider
     * ipucu yerine "ipucu ∨ devir açık" der); RELEASED olayı kapatır. Yalnız bellek. Varsayılan: hiçbir şey.
     */
    default void openHandover() {
    }

    /**
     * Bırakmadan önce boşalt: bekleyen her işlemi yazar, kendi zincir satırlarını bırakır ve — {@code quiet} ∧ kuşak eksiksiz
     * ∧ bekleyen yok ∧ düşüş yok ise — devir işaretini SON deyim olarak yazar. Yardımcı bir iş parçacığında, en çok
     * {@code budgetMs} beklenerek; asla fırlatmaz.
     *
     * @param budgetMs bekleme üst sınırı (çağıran {@link #HANDOVER_FLUSH_MS} geçer)
     * @param quiet    değerlendirmeler durulduysa true ({@code MOS.awaitEvalQuiescent})
     * @return sonuç; varsayılan {@link HandoverResult#SKIPPED}
     */
    default HandoverResult flushForHandover(long budgetMs, boolean quiet) {
        return HandoverResult.SKIPPED;
    }

    // ── Bağlama ve durum ────────────────────────────────────────────────────────────────────────────────────────────

    /**
     * Güncel bellek değerlerinin kaynağını bağlar — {@code MonitoringOutageService} kaydediciyi aldığı ayarlayıcıda
     * {@code sink.bindSource(this)} çağırır (Spring bağımlılık döngüsü kurmadan; elle kurulan çok pod testlerinde de aynı
     * yol). Kaynak bağlanmadan gelen {@link #recovery} / {@link #typeState} çağrıları yok sayılır.
     */
    void bindSource(EvalStateSource source);

    /**
     * Bu pod'un kaydedici durumu (Sistem Sağlığı {@code data.eval_state}, POD ölçüleri). Asla fırlatmaz, veritabanı
     * okumaz; {@link #NOOP} için null (= kaydedici yok, ekran "Bilinmiyor" der — asla yeşil değil).
     */
    Status status();

    /**
     * Bu pod'un kaydedici durumu (tasarım §2.6 {@code EvalStateRecorder.Status} — kaydedici bu arabirimi uyguladığı için
     * {@code EvalStateRecorder.Status} adıyla da erişilir). Zaman alanları epoch ms, null = hiç.
     *
     * @param mode          {@link EvalStateMode#key()} — {@code off | write | verify}
     * @param active        bu pod şu an yazıyor mu (kip yazıyor, lider ipucu true, kuşak alındı, tablo var)
     * @param writer        bu pod'un örnek kimliği ({@code SchedulerService.instanceId()})
     * @param generation    tutulan kuşak (çit belirteci); null = yok
     * @param pending       boşaltılmayı bekleyen (birleştirilmiş) işlem sayısı
     * @param dropped       üst sınır yüzünden düşen işlem sayısı (birikimli; etkilenen anahtarlar "kirli")
     * @param flushes       başarılı boşaltma sayısı (birikimli)
     * @param writeFailures başarısız boşaltma sayısı (birikimli)
     * @param lastFlushMs   son başarılı boşaltma
     * @param lastFailureMs son başarısız boşaltma
     * @param lastFailure   son hatanın KISA özeti (istisna sınıfı; SQL / bağlam / değer metni ASLA)
     * @param tableMissing  tablolar henüz yok (ilk açılışın ilk saniyeleri) — sessizce yeniden denenir
     * @param inherited     son GAINED'de devralınan önceki kuşak satırları; null = hesaplanmadı / okunamadı
     * @param lastVerify    bu pod'un son doğrulama turu; null = henüz tur yok
     * @param fenced        bu pod çit dışı (daha yeni bir kuşak alındı; GAINED ya da kuşak sahibi sessiz kalınca kalkar) —
     *                      lider ipucu true iken uzun süre true kalması = bu pod yazmıyor (R3-1 FX-STATE, SONA eklendi)
     * @param hookFailures  kanca / kuyruğa alma istisnaları (birikimli; MOS kancaları + kaydedicinin kendi yakaladıkları) —
     *                      karar etkilenmez ama ayna eksik kalabilir (R3-1 FX-STATE, SONA eklendi)
     * @param readEnabled   bu pod devirde okuma ayarını AÇIK görüyor mu ({@link EvalStateReadSwitch}; çok pod Sürüm 3-2,
     *                      SONA eklendi)
     * @param handoverReady {@link #handoverReady()} — şu an zarif kapanışta tam devir mümkün mü (3-2)
     * @param lastLoad      bu pod'un son yükleme raporu ({@link EvalStateSource#lastEvalLoad()}; null = açılıştan beri hiç
     *                      liderlik kazanılmadı) (3-2)
     * @param lastHandover  bu pod'un son devir boşaltması sonucu (null = hiç; yalnız kapanan pod yanıt verirken görünür) (3-2)
     */
    record Status(String mode, boolean active, String writer, Long generation,
                  long pending, long dropped, long flushes, long writeFailures,
                  Long lastFlushMs, Long lastFailureMs, String lastFailure, boolean tableMissing,
                  Inherited inherited, EvalVerifyReport lastVerify, boolean fenced, long hookFailures,
                  boolean readEnabled, boolean handoverReady, EvalLoadReport lastLoad, HandoverResult lastHandover) {

        /**
         * 3-1 (16 bileşenli) biçim — mevcut çağıranlar derlensin; {@code readEnabled = false}, {@code handoverReady = false},
         * {@code lastLoad = null}, {@code lastHandover = null}.
         */
        public Status(String mode, boolean active, String writer, Long generation,
                      long pending, long dropped, long flushes, long writeFailures,
                      Long lastFlushMs, Long lastFailureMs, String lastFailure, boolean tableMissing,
                      Inherited inherited, EvalVerifyReport lastVerify, boolean fenced, long hookFailures) {
            this(mode, active, writer, generation, pending, dropped, flushes, writeFailures, lastFlushMs, lastFailureMs,
                    lastFailure, tableMissing, inherited, lastVerify, fenced, hookFailures, false, false, null, null);
        }

        /** Eski (14 bileşenli) biçim — mevcut çağıranlar derlensin; {@code fenced = false}, {@code hookFailures = 0}. */
        public Status(String mode, boolean active, String writer, Long generation,
                      long pending, long dropped, long flushes, long writeFailures,
                      Long lastFlushMs, Long lastFailureMs, String lastFailure, boolean tableMissing,
                      Inherited inherited, EvalVerifyReport lastVerify) {
            this(mode, active, writer, generation, pending, dropped, flushes, writeFailures, lastFlushMs, lastFailureMs,
                    lastFailure, tableMissing, inherited, lastVerify, false, 0L);
        }
    }

    /**
     * Liderliği kazanınca bir kez sayılan, ÖNCEKİ kuşaklardan kalan satırlar (3-2'nin devirde taşıyacağının doğrudan kanıtı;
     * 3-1'de karara GİRMEZ).
     *
     * @param atMs               sayım anı
     * @param previousGeneration bu pod'un CAS'ının yerini aldığı kuşak (= kuşak − 1; ilk kuşakta null) — sayılar YALNIZ bu
     *                           kuşağın satırlarıdır (daha eski kuşakların bayat satırları sayılmaz, R3-1 FX-STATE)
     * @param recoveryCounters   {@code recovery_up_count > 0} satırları
     * @param downSiblings       TTL içindeki {@code up = false} ve {@code monitor_id} dolu satırlar
     * @param activeSuppressions {@code suppression_active = true} türler
     * @param abandonedChains    bu GAINED'de ABANDONED işaretlenen zincirler
     */
    record Inherited(long atMs, Long previousGeneration, int recoveryCounters, int downSiblings,
                     int activeSuppressions, int abandonedChains) {}

    // ── Boş uygulama ────────────────────────────────────────────────────────────────────────────────────────────────

    /** Hiçbir şey yapmayan kaydedici — bugünkü davranış (testler, kaydedicisiz kurulum). {@link #status()} null döner. */
    EvalStateSink NOOP = new EvalStateSink() {
        @Override public void recovery(String rkey, String alertType, String domain) {}
        @Override public void observation(String alertType, SweepItem item, boolean manual, long atMs) {}
        @Override public void typeState(String alertType) {}
        @Override public void chainStart(String confirmKey, long chainId, SweepItem item, ConfirmState state,
                                         String firstFailureAt, long delayMs) {}
        @Override public void chainProgress(String confirmKey, long chainId, ConfirmState state,
                                            List<Map<String, Object>> attempts) {}
        @Override public void chainFinishing(String confirmKey, long chainId, int lockTry) {}
        @Override public void chainEnd(String confirmKey, long chainId) {}
        @Override public void sweepEnd(String alertType) {}
        @Override public void bindSource(EvalStateSource source) {}
        @Override public Status status() { return null; }
        @Override public String toString() { return "EvalStateSink.NOOP"; }
    };
}
