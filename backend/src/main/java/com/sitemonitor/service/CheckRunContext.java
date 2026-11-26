package com.sitemonitor.service;

import java.util.function.Supplier;

/**
 * Bu iş parçacığında koşan kontrolün kökeni (H paketi 2026-11-26, rapor B2): teyit / kurtarma zincirinin yeniden kontrolü
 * mü? {@code MonitoringOutageService} zincir adımında kalemin yeniden kontrol tedarikçisini {@link #asRecheck} ile sarar;
 * işaretsiz türlerin yazıcıları ({@code SchedulerService.recheckHttp|Keyword|Port|Ping|Page|UptimeRun}) satıra
 * {@code recheck = TRUE} yazar ({@link #recheckFlag()}). Planlı tarama, dağıtık iş gövdesi ve elle kontrol bu bayrağı
 * görmez (NULL yazar) — işaretleme yalnız zincir adımının kendi iş parçacığında, tedarikçi çağrısı süresince geçerlidir.
 *
 * <p>Yeni bir iş parçacığına geçen tedarikçi bayrağı taşımaz (yazıcı NULL yazar — satır "planlı" sayılır; erişilebilirlik
 * bugünkü gibi). Bugünkü tüm yeniden kontrol tedarikçileri senkron koşar.
 */
public final class CheckRunContext {

    private CheckRunContext() {}

    private static final ThreadLocal<Boolean> RECHECK = new ThreadLocal<>();

    /** Tedarikçiyi "yeniden kontrol" bağlamında çağırır; önceki değer geri yüklenir (iç içe çağrı güvenli). */
    public static <T> T asRecheck(Supplier<T> body) {
        Boolean previous = RECHECK.get();
        RECHECK.set(Boolean.TRUE);
        try {
            return body.get();
        } finally {
            if (previous == null) RECHECK.remove(); else RECHECK.set(previous);
        }
    }

    /** Bu iş parçacığındaki kontrol bir teyit / kurtarma yeniden kontrolü mü. */
    public static boolean recheck() {
        return Boolean.TRUE.equals(RECHECK.get());
    }

    /** Satıra yazılacak değer: yeniden kontrolde {@code TRUE}, değilse {@code null} (yalnız TRUE yazılır). */
    public static Boolean recheckFlag() {
        return recheck() ? Boolean.TRUE : null;
    }
}
