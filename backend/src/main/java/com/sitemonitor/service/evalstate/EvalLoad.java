package com.sitemonitor.service.evalstate;

import com.sitemonitor.service.MonitoringOutageService.SweepItem;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Devirde okunan kalıcı değerlendirme durumu — yeni tarama liderinin belleğine BİR KEZ uygulanacak, DEĞİŞMEZ yük
 * (2026-10-28, çok pod Sürüm 3-2 P0; tasarım R3-2 §1.1 / §3.5). Kuran: {@code EvalStateLoader} (paket A, kaydedicinin
 * yazıcı iş parçacığında, kuşak CAS'ından SONRA, yalnız {@code gen = prior.gen} satırlarından). Uygulayan:
 * {@code MonitoringOutageService.applyEvalLoad} (paket B, {@link EvalStateSource#applyEvalLoad}) — kapı ({@code EvalLoadGate})
 * altında, uçuşta değerlendirme yokken ve yalnız BOŞ belleğe; aksi hâlde yük atılır (asla birleştirilmez, J-3).
 *
 * <p><b>İki katman (J-4):</b>
 * <ul>
 *   <li>{@link #TIER_FULL} — temiz devir (zarif kapanış) kanıtlandı: kurtarma sayaçları + son sayım, DOWN kardeşler,
 *       bastırma (yalnız TRUE), zamanlanmış tur görüldü (D-6 rahatlaması), pencere tohumu + tur aralığı.</li>
 *   <li>{@link #TIER_OBSERVATIONS} — temiz devir yok ama önceki sahip kanıtla taze ve eksiksiz yazıyordu: YALNIZ DOWN
 *       kardeşler + pencere tohumu + tur aralığı. Karar durumu (sayaç, bastırma histerezisi, D-6) bu katmanda ASLA
 *       taşınmaz — kurucu {@link #recovery}, {@link #suppressionActive} ve {@link #roundSeen}'i bu katmanda BOŞALTIR
 *       (yükleyici hatası bile J-4'ü delemez).</li>
 * </ul>
 * Hiçbir şey yüklenmeyecekse yük KURULMAZ; {@link EvalStateSource#evalLoadSkipped} bir {@link EvalLoadReport} ile çağrılır.
 *
 * <p>Kurucu her koleksiyonu kopyalar (null anahtar / değer atılır), sonuç değiştirilemez. Bilinmeyen katman bir programlama
 * hatasıdır → {@link IllegalArgumentException} (yalnız {@link #TIER_FULL} / {@link #TIER_OBSERVATIONS} sabitleri kullanılır).
 *
 * @param token             yükün ait olduğu liderlik dönemi belirteci ({@link EvalStateSource#evalLoadToken()} — MOS'un
 *                          GAINED dinleyicisinde artırdığı değer); uygulamada belirteç değişmişse yük atılır
 * @param tier              {@link #TIER_FULL} | {@link #TIER_OBSERVATIONS}
 * @param recovery          {@code "tür:alan adı"} ({@link EvalStateKeys#recoveryKey}) → sayaç + son sayım — yalnız FULL
 * @param siblings          {@code "tür|monitor_id"} ({@link #siblingKey}) → DOWN kardeş gözlemi (TTL içindekiler)
 * @param suppressionActive bastırması AÇIK türler (FALSE satırlar konmaz; yokluk ≡ false) — yalnız FULL
 * @param roundSeen         zamanlanmış turu görülmüş türler (D-6) — yalnız FULL
 * @param window            tür → alan adı → pencere işareti (pencere tohumu, her kuşaktan, son 60 dk)
 * @param intervals         tür → tur aralığı (ms, &gt; 0)
 * @param meta              nereden / ne kadar eski / ne kadar sürdü ({@code null} → {@link Meta#EMPTY})
 */
public record EvalLoad(long token, String tier,
                       Map<String, Recovery> recovery,
                       Map<String, Sibling> siblings,
                       Set<String> suppressionActive,
                       Set<String> roundSeen,
                       Map<String, Map<String, SeedMark>> window,
                       Map<String, Long> intervals,
                       Meta meta) {

    /** Temiz devir katmanı — {@link EvalLoadReport#OUTCOME_FULL} ile aynı dize (kalıcı). */
    public static final String TIER_FULL = EvalLoadReport.OUTCOME_FULL;
    /** Yalnız gözlemler katmanı — {@link EvalLoadReport#OUTCOME_OBSERVATIONS} ile aynı dize (kalıcı). */
    public static final String TIER_OBSERVATIONS = EvalLoadReport.OUTCOME_OBSERVATIONS;

    /**
     * Bir kurtarma anahtarının yüklenen değeri ({@code monitor_eval_state}: {@code recovery_up_count > 0}).
     *
     * @param upCount       ardışık sağlıklı sonuç sayısı (&gt; 0)
     * @param lastCountedMs son SAYILMIŞ sağlıklı sonucun anı (null = bilinmiyor)
     */
    public record Recovery(int upCount, Long lastCountedMs) {}

    /**
     * Bir DOWN kardeş gözlemi ({@code monitor_last_status}: {@code up = FALSE}, {@code monitor_id} dolu, TTL içinde). Ham hata
     * metni tabloda YOKTUR; sınıfı {@code errorClass} olarak gelir (MOS bunu {@code DownObservation.loadedClass}'a koyar —
     * yeniden oynatma ve doğrulama {@code classifyError(null)}'a düşmesin, tasarım §1.5).
     *
     * @param alertType  alarm türü (bellek anahtarının ilk parçası)
     * @param domain     alan adı (null değil — tablo satırı alan adsız yazılmaz)
     * @param detail     kalem detayı (port, kayıt türü …; null olabilir)
     * @param monitorId  izleme kimliği
     * @param teamId     sahip takım (null = yok)
     * @param standalone bağımsız izleme mi (null = bilinmiyor)
     * @param errorClass {@link EvalStateSource#ERROR_NETWORK} | {@code DNS} | {@code CONFIG} | {@code OTHER} | null
     * @param observedMs gözlem anı (kaydedicinin saati; TTL ve pencere budaması bununla)
     */
    public record Sibling(String alertType, String domain, String detail, long monitorId, Long teamId, Boolean standalone,
                          String errorClass, long observedMs) {

        /** Bellek anahtarı — {@code "tür|monitor_id"} ({@link #siblingKey}). */
        public String key() {
            return siblingKey(alertType, monitorId);
        }

        /**
         * MOS {@code slim()} biçiminde ince kalem: tür, alan adı, detay, {@code up = false}, hata YOK (metin tabloda yok),
         * bağlam yalnız dolu {@code monitor_id} / {@code team_id} / {@code standalone}; recheck yok.
         */
        public SweepItem slimItem() {
            Map<String, Object> ctx = new HashMap<>();
            ctx.put("monitor_id", monitorId);
            if (teamId != null) ctx.put("team_id", teamId);
            if (standalone != null) ctx.put("standalone", standalone);
            return new SweepItem(alertType, domain, detail, false, null, ctx, null);
        }
    }

    /**
     * Pencere tohumunun bir işareti (VERIFY'ın {@code window_seed} kuralı: alan adının pencere içindeki son gözlemi).
     *
     * @param networkDown ağ-sınıfı DOWN mı ({@code !up && network_down})
     * @param atMs        en yeni gözlem anı
     */
    public record SeedMark(boolean networkDown, long atMs) {}

    /**
     * Yükün kaynağı ve ölçüleri (rapora ve günlüğe gider; karara GİRMEZ).
     *
     * @param fromGeneration önceki sahibin kuşağı ({@code prior.gen}; satırlar yalnız bu kuşaktan okundu)
     * @param fromWriter     önceki sahibin örnek kimliği ({@code prior.writer} = {@code SchedulerService.instanceId()})
     * @param handoverAgeMs  devir işaretinin yaşı (yalnız FULL; null = devir yok)
     * @param durableAgeMs   son "tam ve kalıcı" yazımın yaşı ({@code now − prior.flushed_ms})
     * @param partialReason  yalnız {@link #TIER_OBSERVATIONS}: neden tam değil ({@link EvalLoadReport#OBSERVATIONS_REASONS});
     *                       FULL'da null
     * @param readMs         okumaların toplam süresi
     */
    public record Meta(long fromGeneration, String fromWriter, Long handoverAgeMs, long durableAgeMs, String partialReason,
                       long readMs) {
        /** Bilinmeyen kaynak (testler / null meta). */
        public static final Meta EMPTY = new Meta(0L, null, null, 0L, null, 0L);
    }

    public EvalLoad {
        if (!TIER_FULL.equals(tier) && !TIER_OBSERVATIONS.equals(tier)) {
            throw new IllegalArgumentException("EvalLoad: bilinmeyen katman '" + tier + "'");
        }
        boolean full = TIER_FULL.equals(tier);
        recovery = full ? copy(recovery) : Map.of();
        siblings = copy(siblings);
        suppressionActive = full ? copy(suppressionActive) : Set.of();
        roundSeen = full ? copy(roundSeen) : Set.of();
        window = copyWindow(window);
        intervals = copy(intervals);
        meta = meta == null ? Meta.EMPTY : meta;
    }

    /** Bellekteki {@code downObserved} anahtarı: {@code "tür|monitor_id"} (MOS {@code recordObservations} ile aynı). */
    public static String siblingKey(String alertType, long monitorId) {
        return alertType + "|" + monitorId;
    }

    /** Temiz devir katmanı mı. */
    public boolean full() {
        return TIER_FULL.equals(tier);
    }

    /** Pencere tohumundaki toplam (tür × alan adı) işaret sayısı. */
    public int windowDomains() {
        int n = 0;
        for (Map<String, SeedMark> m : window.values()) n += m.size();
        return n;
    }

    private static <K, V> Map<K, V> copy(Map<K, V> in) {
        if (in == null || in.isEmpty()) return Map.of();
        Map<K, V> out = new LinkedHashMap<>();
        in.forEach((k, v) -> {
            if (k != null && v != null) out.put(k, v);
        });
        return out.isEmpty() ? Map.of() : Collections.unmodifiableMap(out);
    }

    private static <T> Set<T> copy(Set<T> in) {
        if (in == null || in.isEmpty()) return Set.of();
        Set<T> out = new LinkedHashSet<>();
        for (T t : in) {
            if (t != null) out.add(t);
        }
        return out.isEmpty() ? Set.of() : Collections.unmodifiableSet(out);
    }

    private static Map<String, Map<String, SeedMark>> copyWindow(Map<String, Map<String, SeedMark>> in) {
        if (in == null || in.isEmpty()) return Map.of();
        Map<String, Map<String, SeedMark>> out = new LinkedHashMap<>();
        in.forEach((type, marks) -> {
            if (type == null) return;
            Map<String, SeedMark> m = copy(marks);
            if (!m.isEmpty()) out.put(type, m);
        });
        return out.isEmpty() ? Map.of() : Collections.unmodifiableMap(out);
    }
}
