package com.sitemonitor.service.schedule;

/**
 * Bu pod'un tarama liderliği (kira {@code lease:sweep-leader}) değişti (2026-10-21, çok pod temeli P0).
 *
 * <p>{@code SchedulerService} yayınlar — yalnız GEÇİŞTE, pod başına her geçişte TAM BİR KEZ; kararlı durumda (lider kirayı
 * yeniler, takipçi yine alamaz) hiçbir şey yayınlanmaz:
 * <ul>
 *   <li>{@link #GAINED} — kira bu pod'a geçti ({@code leader = true}): ilk alış ya da süresi dolmuş kiranın devralınması.</li>
 *   <li>{@link #LOST} — kira başka pod'a geçti ({@code leader = false}): bu pod yenileyemeden süresi doldu ve başka
 *       sahip aldı; bu pod bunu bir sonraki kira yoklamasında fark eder.</li>
 *   <li>{@link #RELEASED} — bu pod kirayı zarif kapanışta bıraktı ({@code leader = false}); bu pod artık yeniden
 *       ALMAZ. Yalnız kira bu pod'daydıysa yayınlanır.</li>
 * </ul>
 * Aynı pod'un olayları sırayla gelir: GAINED, ardından LOST ya da RELEASED, sonra yine GAINED … RELEASED'dan sonra olay
 * gelmez. Kira TABLOSU yoksa (eski tek pod geri düşüşü, pod taramayı YEREL koşturur) olay yayınlanmaz. 2026-10-21 (çok pod
 * R1-A): kira sorgusu GEÇİCİ hata verirken lider, son yazdığı kira bitişinin güvenli penceresi dolunca taramayı bırakır ve
 * {@link #LOST} yayınlar (pencere içindeki hatalar olay üretmez); tıkanmış lider (10 dk hiç tarama tıkı yok) kirayı
 * bıraktığında da {@link #LOST} yayınlanır — her iki durumda pod sonra yeniden {@link #GAINED} alabilir.
 *
 * <p><b>Dinleyiciler için:</b> olay, geçişi gören iş parçacığında (bir tarama iş parçacığı, {@code sweep-leader-lease}
 * yenileyicisi ya da kapanış iş parçacığı) {@code SchedulerService}'in liderlik kilidi tutulurken EŞZAMANLI dağıtılır —
 * bu sıralamayı garanti eder. Dinleyici kısa sürmeli (bellek durumunu sıfırlamak gibi), ağ / uzun DB işi yapmamalı;
 * uzun işi kendi iş parçacığına bırakmalı. {@code isSweepLeader()} çağırmak güvenlidir (kilit yeniden girilir).
 * Dinleyicinin fırlattığı istisna yakalanır ve WARN yazılır — liderlik kararı değişmez.
 *
 * @param leader bu olaydan sonra bu pod lider mi (yalnız {@link #GAINED} için true)
 * @param owner  bu pod'un kira sahip kimliği ({@code ClusterScheduleService.owner()})
 * @param atMs   geçişin görüldüğü an (epoch ms; {@code SchedulerService}'in zamanlama saati)
 * @param reason {@link #GAINED}, {@link #LOST} ya da {@link #RELEASED}
 */
public record SweepLeadershipChangedEvent(boolean leader, String owner, long atMs, String reason) {

    /** Kira bu pod'a geçti. */
    public static final String GAINED = "GAINED";
    /** Kira başka pod'a geçti (bu pod yenileyemedi). */
    public static final String LOST = "LOST";
    /** Bu pod kirayı kapanışta bıraktı. */
    public static final String RELEASED = "RELEASED";
}
