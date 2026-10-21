package com.sitemonitor.service.monitoring;

/**
 * Kenar tetikli değişiklik alarmlarının (DNS_CHANGED, DOMAINMON_CHANGED) KALICI karar durumu (2026-10-21, çok pod R1-B,
 * MP-13). Kolon {@value #COLUMN} — {@code dns_records} ve {@code domain_checks} üzerinde, NULL'lanabilir.
 *
 * <p><b>Neden.</b> Değişiklik yalnız görüldüğü turda vardır: değişmiş satır kaydedildiği anda sonraki turun TABANI olur ve
 * aynı değişiklik bir daha algılanmaz. Karar ise bellekte koşan bir teyit zincirine (DNS: 3 × 30 sn) ya da tur sonundaki
 * değerlendirmeye kalıyordu — liderlik devri, kapı (yalnız lider) düşüşü ya da kapanış zinciri yarıda keserse değişiklik
 * SONSUZA DEK kayboluyordu. Artık satır karar verilene dek {@link #PENDING} kalır; tarama lideri her turda karar
 * verilmemiş satırları yeniden kuyruğa alır.
 *
 * <p>Geçişler yalnız KOŞULLU UPDATE ile ({@code WHERE change_state='PENDING'}): iki pod aynı satıra iki karar yazamaz.
 * NULL = bu özellikten önceki (ya da eski pod'un yazdığı) satır — yok sayılır, davranış eskisi gibi.
 */
public final class ChangeStates {

    private ChangeStates() {
    }

    /** Kolon adı (iki tabloda da aynı). */
    public static final String COLUMN = "change_state";

    /** Değişiklik görüldü, alarm hattına verildi, karar henüz yok (yeniden kuyruğa alınır). */
    public static final String PENDING = "PENDING";
    /** Teyit edildi, alarm hattı işledi (olay açıldı ya da açık olaya eklendi). */
    public static final String ALARMED = "ALARMED";
    /** Teyit sırasında değer tabana döndü — geçici dalgalanma, alarm yok. */
    public static final String TRANSIENT = "TRANSIENT";
    /** Bilerek alarmlanmadı (izlemede değişiklik alarmı kapalı, beklenen-değer içinde, izleme silindi / duraklatıldı,
     *  tür bildirimleri kapalı ya da açık olay başka sahibin). */
    public static final String SUPPRESSED = "SUPPRESSED";
    /** Karar süresi içinde verilemedi (uzun süre lider tarama koşmadı) — iz bırakılarak bırakıldı. */
    public static final String EXPIRED = "EXPIRED";

    /** DNS: en çok bu kadar eski bekleyen satır yeniden kuyruğa alınır (tur 5 dk); daha eskisi {@link #EXPIRED}. */
    public static final long DNS_REQUEUE_MAX_AGE_MS = 2L * 60 * 60 * 1000;
    /** Alan adı: tik saatlik olduğu için pay daha geniş; daha eskisi {@link #EXPIRED}. */
    public static final long DOMAIN_REQUEUE_MAX_AGE_MS = 6L * 60 * 60 * 1000;
    /**
     * DNS yeniden kuyruk sorgusunun geriye bakışı = en uzun işlenebilir yaş + pay (inceleme 2026-10-22, CORE-6): yalnız
     * 2 saatten genç satır teyide alınır; 2–3 saat arası EXPIRED olur (iz bırakır). Daha eskisi hiç okunmaz — her 5 dk'lık
     * turda 24 saatlik {@code dns_records} okunmasın. Sorgu bekleyen satırların kısmi dizinini kullanır
     * ({@code DeferredIndexBuilder.CATALOG}; kurulana dek {@code checked_at} dizininde bu aralık).
     */
    public static final long DNS_REQUEUE_LOOKBACK_MS = DNS_REQUEUE_MAX_AGE_MS + 60L * 60 * 1000;
    /** Alan adı: tik saatlik — en uzun işlenebilir yaş (6 saat) + 2 saat pay. */
    public static final long DOMAIN_REQUEUE_LOOKBACK_MS = DOMAIN_REQUEUE_MAX_AGE_MS + 2L * 60 * 60 * 1000;
    /** Bir turda en çok okunan bekleyen satır. */
    public static final int REQUEUE_BATCH = 200;
}
