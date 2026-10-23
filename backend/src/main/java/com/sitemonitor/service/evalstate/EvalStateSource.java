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
