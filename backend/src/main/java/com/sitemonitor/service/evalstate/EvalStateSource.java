package com.sitemonitor.service.evalstate;

/**
 * Bellekteki değerlendirme durumunun DAR okuma yüzü (2026-10-23, çok pod Sürüm 3-1 P0) — {@code MonitoringOutageService}
 * uygular (paket A2), kaydedici ({@code EvalStateRecorder}, paket A1) {@link EvalStateSink#recovery} /
 * {@link EvalStateSink#typeState} çağrısında GÜNCEL değeri buradan okur.
 *
 * <p><b>Neden değer argümanla değil de buradan okunur:</b> kaydedici işlemin sıra numarasını ({@code seq}) ÖNCE alır,
 * SONRA bu yüzden okur. Böylece aynı anahtarın en büyük {@code seq}'li işlemi, daha küçük {@code seq}'li her işlemin
 * mutasyonundan SONRA okunmuş olur — iki iş parçacığı (tarama + kurtarma zinciri) aynı anahtarı yarışarak değiştirse de
 * tabloya en son bellek değeri yazılır. Değer çağıranda okunup argümanla geçseydi okuma ile sıra numarası arasındaki
 * pencerede eski değer yeni numarayla yazılabilirdi.
 *
 * <p><b>Kurallar (uygulayan için):</b> yalnız bellek haritası okuması — veritabanı, kilit beklemesi, günlük yazımı
 * YOK; asla fırlatmaz; her çağrıda yeni (değişmez) bir değer döner. Bağlama: {@link EvalStateSink#bindSource}
 * (Spring döngüsü kurmadan; elle kurulan testlerde de aynı yol).
 */
public interface EvalStateSource {

    /** Hata sınıfları ({@code monitor_last_status.error_class}) — kalıcı değerler; ham hata metni ASLA yazılmaz. */
    String ERROR_NETWORK = "NETWORK";
    String ERROR_DNS = "DNS";
    String ERROR_CONFIG = "CONFIG";
    String ERROR_OTHER = "OTHER";

    /**
     * {@code "tür:alan adı"} kurtarma anahtarının güncel değerleri ({@code recoveryUpCount},
     * {@code recoveryLastCountedAt}, {@code recoveryGeneration}, {@code recoveryStartedAt}). Anahtar yoksa
     * {@link RecoveryValues#NONE}.
     */
    RecoveryValues recoveryValues(String rkey);

    /** Türün bayrakları ({@code suppressionActive}, {@code scheduledRoundSeen}, {@code suppressionTick} aralığı). */
    TypeValues typeValues(String alertType);

    /**
     * Hata metninin sınıfı: {@link #ERROR_CONFIG} (yapılandırma / politika — ağ kanıtı DEĞİL), {@link #ERROR_DNS}
     * (DNS işaretleri ya da hata metni = çıplak hedef), {@link #ERROR_NETWORK} (taşıma katmanı), {@link #ERROR_OTHER}
     * (tanınmayan, boş olmayan), {@code null} (boş / null hata). SÖZLEŞME: her girdi için
     * {@code MonitoringOutageService.isOutageClass(error, domain) == (sınıf ∈ {NETWORK, DNS})} — kaydedicinin yazdığı
     * {@code network_down} ve 3-2'nin pencere tohumu bastırma kararıyla aynı sınıflandırmayı kullansın.
     */
    String errorClass(String error, String domain);

    /**
     * Kimlikli bir kalemin bellekteki GÜNCEL DOWN gözlemi ({@code downObserved["tür|kimlik"]}) — kaydedici gözlem işleminin
     * seq'ini ALDIKTAN SONRA okur (R3-1 FX-STATE, F4): iki iş parçacığının mutasyon → kanca aralığı iç içe geçse de satır
     * belleği izler (eski DOWN yeni UP'ı ezemez). Dönüş: {@link DownValues#ABSENT} = bellekte DOWN kaydı yok (kalem UP ya da
     * budanmış); null = kaynak bu sorguyu desteklemiyor (çağıranın değerleri kullanılır — eski sözleşme).
     */
    default DownValues downObservation(String alertType, long monitorId) {
        return null;
    }

    /** Kanca istisnası sayısı (birikimli; R3-1 FX-STATE, F7) — kaydedicinin durumunda görünür. Varsayılan 0. */
    default long hookFailures() {
        return 0L;
    }

    // ── Devirde okuma (2026-10-28, çok pod Sürüm 3-2 P0; tasarım R3-2 §3.5) — varsayılanlar = bugünkü davranış ────────

    /**
     * Bekleyen yüklemenin belirteci — MOS GAINED dinleyicisinde ({@code evalEpoch}) yükleme kapısını arıyorsa {@code > 0};
     * 0 = bekleyen yükleme yok (ayar kapalı, kaydedici hazır değil, yükleme bitti / atıldı). Kaydedici CAS'tan sonra bunu
     * sorar; 0 ise hiçbir okuma yapmaz. Okumalar arasında değer değişirse okumayı keser. Yalnız bellek; asla fırlatmaz.
     * Varsayılan 0 (yükleme yok).
     */
    default long evalLoadToken() {
        return 0L;
    }

    /**
     * Okunan yükü belleğe uygular (yazıcı iş parçacığında çağrılır) — yalnız kapı hâlâ bu belirteçle LOADING iken, uçuşta
     * değerlendirme yokken ve bellek BOŞken; aksi hâlde yükü atar (asla birleştirmez, J-3) ve raporunu
     * {@link #lastEvalLoad()}'a yazar. Veritabanı yok; asla fırlatmaz.
     *
     * @return true = uygulandı; false = atıldı (neden {@link #lastEvalLoad()}'da). Varsayılan false (uygulanmaz).
     */
    default boolean applyEvalLoad(EvalLoad load) {
        return false;
    }

    /**
     * Kaydedici / yükleyici hiçbir şey yüklemeyecek (süreklilik reddetti, okuma düştü, sınır aşıldı …) — kapıyı belirteç
     * eşleşirse açar ve raporu {@link #lastEvalLoad()}'a yazar. Belirteç eskiyse yok sayılır. Varsayılan: hiçbir şey.
     */
    default void evalLoadSkipped(long token, EvalLoadReport report) {
    }

    /** Bu pod'un son yükleme raporu (son GAINED'den beri; null = açılıştan beri hiç liderlik kazanılmadı). Varsayılan null. */
    default EvalLoadReport lastEvalLoad() {
        return null;
    }

    /**
     * Bellekteki tüm durumu kaydediciye YENİDEN kuyruğa alır (MOS {@code replayEvalState()}) — kuşağın EKSİKSİZ sayılması
     * için (tasarım §2.2: yeni kuşak ancak bir tam oynatma boşaltıldıktan sonra işaret taşır). Zincirler oynatılmaz.
     *
     * @return kuyruğa alınan işlem sayısı; varsayılan 0 (oynatma yok)
     */
    default int replay() {
        return 0;
    }

    /**
     * Temiz devir başlıyor ({@code SchedulerService.onContextClosed}, yalnız {@code EvalStateSink.handoverReady()} iken):
     * yeni değerlendirme girişleri en çok {@code HANDOVER_WAIT_MS} bekler (MOS kapısı HANDOVER). Yalnız bellek, veritabanı
     * yok, asla fırlatmaz. Varsayılan: hiçbir şey (paket B MOS'ta uygular; tasarım §5.1).
     */
    default void beginEvalHandover() {
    }

    /**
     * Uçuştaki değerlendirmelerin bitmesini en çok {@code maxMs} bekler (yeni girişler HANDOVER'da bekliyor).
     *
     * @return true = uçuşta değerlendirme kalmadı. Varsayılan true (bekleyecek bir şey yok).
     */
    default boolean awaitEvalQuiescent(long maxMs) {
        return true;
    }

    /** Devir bitti (lider bırakıldı ya da bırakma denendi): kapı HANDOVER'dan çıkar; tekrar çağrılabilir. Varsayılan: hiçbir şey. */
    default void endEvalHandover() {
    }

    /**
     * Bir türün bastırma penceresi uzunluğu, kaydedilmiş tur aralığından (yükleyicinin pencere tohumu için; inceleme
     * 2026-10-28, kaydedici M-2: tohum VERIFY'ın ölçtüğü gibi TÜR BAŞINA pencereyle kurulur). MOS kendi formülüyle geçersiz
     * kılar ({@code max(3 × aralık, taban)}, en çok 60 dk). Varsayılan: aynı formül, taban 5 dk.
     *
     * @param roundIntervalMs kaydedilmiş tur aralığı (ms; ≤ 0 = bilinmiyor → taban)
     */
    default long windowLengthMsFor(String alertType, long roundIntervalMs) {
        long floor = 300_000L;
        long cap = 60L * 60_000L;
        return Math.min(cap, Math.max(floor, 3L * Math.max(0L, roundIntervalMs)));
    }

    /**
     * Bellekteki DOWN gözleminin dar kopyası — ham hata metni YOK, yalnız sınıfı.
     *
     * @param present    bellekte DOWN kaydı var mı ({@link #ABSENT} = yok)
     * @param errorClass {@link #errorClass} sonucu
     * @param atMs       gözlem anı (MOS saati)
     */
    record DownValues(boolean present, String domain, String detail, Long teamId, Boolean standalone, String errorClass,
                      long atMs) {
        public static final DownValues ABSENT = new DownValues(false, null, null, null, null, null, 0L);
    }

    /**
     * Bir kurtarma anahtarının değerleri. {@code upCount} 0 = sayaç yok (bellekte anahtar yok); diğerleri null = yok.
     * {@code chainGen} pod'a özgü zincir kuşağıdır (bilgi amaçlı), {@code chainStartedMs} zincirin başlama anı.
     */
    record RecoveryValues(int upCount, Long lastCountedMs, Long chainGen, Long chainStartedMs) {
        public static final RecoveryValues NONE = new RecoveryValues(0, null, null, null);

        /** Sayaç, son sayım ve zincir yok mu (satır "sıfır" olarak yazılır, silinmez). */
        public boolean isNone() {
            return upCount == 0 && lastCountedMs == null && chainGen == null && chainStartedMs == null;
        }
    }

    /**
     * Türün bayrakları. {@code suppressionActive} null = bellekte kayıt yok (≡ false); {@code scheduledRoundSeen} =
     * son liderlik değişiminden beri türün en az bir ZAMANLANMIŞ turu işlendi mi (D-6); {@code roundIntervalMs} = son
     * iki zamanlanmış çağrı arasındaki aralık (bilinmiyorsa null).
     */
    record TypeValues(Boolean suppressionActive, boolean scheduledRoundSeen, Long roundIntervalMs) {
        public static final TypeValues NONE = new TypeValues(null, false, null);
    }
}
