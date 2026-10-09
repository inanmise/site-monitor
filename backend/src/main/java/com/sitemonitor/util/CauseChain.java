package com.sitemonitor.util;

/**
 * İstisna neden zinciri yürüyüşlerinin ortak derinlik tavanı (2026-10-09, sonsuz döngü savunması).
 *
 * <p>{@code getCause()} kendini döndürmez ama İKİ düğümlü bir döngü (A → B → A) kurulabilir: {@code A.initCause(B)} ardından
 * {@code B.initCause(A)} geçerlidir ve bazı kütüphaneler sarmalarken bunu yapar. Kodda yalnız "kendine işaret" denetimi vardı;
 * böyle bir zincir bir istek, e-posta ya da kontrol iş parçacığını sonsuza dek döndürürdü. Her yürüyüş bu tavanla sınırlanır;
 * gerçek zincirler birkaç halkadır, davranış değişmez. {@code CauseChainGuardTest} yeni bir yürüyüşün tavansız eklenmesini engeller.
 */
public final class CauseChain {

    /** Bir neden zincirinde izlenecek en çok halka. */
    public static final int MAX_DEPTH = 32;

    private CauseChain() {}
}
