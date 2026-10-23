package com.sitemonitor.service.evalstate;

import com.sitemonitor.service.MonitoringOutageService.ConfirmState;
import com.sitemonitor.service.MonitoringOutageService.SweepItem;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * {@code MonitoringOutageService} bellek durumunun DEĞİŞMEZ anlık görüntüsü — yalnız doğrulama (VERIFY) için
 * (2026-10-23, çok pod Sürüm 3-1 P0; tasarım R3-1 §2.5). Dolduran: {@code MonitoringOutageService.verifySnapshot()}
 * (paket A2; haritaların kopyası + tür başına pencere örneği, pencere işaretlerini ve tur saatini YAZMADAN). Okuyan:
 * {@code EvalStateVerifier} (A2). Karar yolu bunu OKUMAZ (I-1). Kurucu her koleksiyonu kopyalar (null anahtar / değer
 * atılır) — sonradan değiştirilemez.
 *
 * @param atMs                  görüntü anı (MOS saati, ms)
 * @param recoveryUpCount       {@code "tür:alan adı"} → ardışık sağlıklı sayısı
 * @param recoveryLastCountedAt {@code "tür:alan adı"} → son SAYILMIŞ sağlıklı sonucun anı
 * @param recoveryGeneration    {@code "tür:alan adı"} → etkin kurtarma zincirinin kuşağı (pod'a özgü)
 * @param recoveryStartedAt     {@code "tür:alan adı"} → etkin kurtarma zincirinin başlama anı
 * @param downObserved          {@code "tür|monitor_id"} → son gözlemi DOWN olan izleme (ince kopya)
 * @param suppressionActive     tür → ağ kesintisi bastırması açık mı
 * @param scheduledRoundSeen    son liderlik değişiminden beri zamanlanmış turu işlenmiş türler (D-6)
 * @param inFlight              teyit anahtarı → canlı teyit durumu
 * @param chainOwner            teyit anahtarı → zincir kimliği
 * @param windows               tür → bastırma penceresi örneği ({@code peekWindowSample}) ve pencere uzunluğu
 */
public record EvalSnapshot(long atMs,
                           Map<String, Integer> recoveryUpCount,
                           Map<String, Long> recoveryLastCountedAt,
                           Map<String, Long> recoveryGeneration,
                           Map<String, Long> recoveryStartedAt,
                           Map<String, Down> downObserved,
                           Map<String, Boolean> suppressionActive,
                           Set<String> scheduledRoundSeen,
                           Map<String, ConfirmState> inFlight,
                           Map<String, Long> chainOwner,
                           Map<String, Window> windows) {

    /** Boş görüntü (kaydedici / MOS yok). */
    public static final EvalSnapshot EMPTY = new EvalSnapshot(0L, null, null, null, null, null, null, null, null, null, null);

    /**
     * {@code downObserved} kaydının ince kopyası (MOS {@code slim()}: tür, alan adı, detay + sahiplik bağlamı).
     *
     * @param errorClass {@link EvalStateSource#errorClass} sonucu (ham hata metni görüntüye GİRMEZ)
     * @param atMs       gözlem anı
     */
    public record Down(String alertType, String domain, String detail, Long monitorId, Long teamId,
                       Boolean standalone, String errorClass, long atMs) {

        /**
         * {@code MonitoringOutageService.stillMonitored} sorusu için ince kalem (recheck yok, {@code up = false}, hata yok;
         * bağlam yalnız dolu {@code monitor_id} / {@code team_id} / {@code standalone}).
         */
        public SweepItem slimItem() {
            Map<String, Object> ctx = new HashMap<>();
            if (monitorId != null) ctx.put("monitor_id", monitorId);
            if (teamId != null) ctx.put("team_id", teamId);
            if (standalone != null) ctx.put("standalone", standalone);
            return new SweepItem(alertType, domain, detail, false, null, ctx, null);
        }
    }

    /**
     * Türün bastırma penceresi örneği (yazmadan okunmuş).
     *
     * @param networkDown penceredeki ağ-sınıfı DOWN alan adı sayısı
     * @param total       penceredeki gözlenmiş alan adı sayısı
     * @param windowMs    pencere uzunluğu ({@code max(3 × tur aralığı, taban)}, en çok 60 dk)
     */
    public record Window(int networkDown, int total, long windowMs) {
        public double rate() {
            return total == 0 ? 0 : (double) networkDown / total;
        }
    }

    public EvalSnapshot {
        recoveryUpCount = copy(recoveryUpCount);
        recoveryLastCountedAt = copy(recoveryLastCountedAt);
        recoveryGeneration = copy(recoveryGeneration);
        recoveryStartedAt = copy(recoveryStartedAt);
        downObserved = copy(downObserved);
        suppressionActive = copy(suppressionActive);
        scheduledRoundSeen = copy(scheduledRoundSeen);
        inFlight = copy(inFlight);
        chainOwner = copy(chainOwner);
        windows = copy(windows);
    }

    private static <K, V> Map<K, V> copy(Map<K, V> in) {
        if (in == null || in.isEmpty()) return Map.of();
        Map<K, V> out = new LinkedHashMap<>();
        in.forEach((k, v) -> {
            if (k != null && v != null) out.put(k, v);
        });
        return Collections.unmodifiableMap(out);
    }

    private static <T> Set<T> copy(Set<T> in) {
        if (in == null || in.isEmpty()) return Set.of();
        Set<T> out = new HashSet<>();
        for (T t : in) {
            if (t != null) out.add(t);
        }
        return Collections.unmodifiableSet(out);
    }
}
