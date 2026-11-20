package com.sitemonitor.service.techerror;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Görselli olay içe almalarının pod başına yük sınırı (inceleme 2026-11-20): görselli içe alma raporun satır kilidi altında
 * olay görsellerinin baytlarını okur (içe alma başına ≤ {@link TechErrorIncidentImportService#MAX_IMAGE_READ_BYTES}).
 * Kişi başına aynı anda TEK, pod başına en çok {@link #PER_POD} görselli içe alma; dolu ise beklemeden reddedilir (çağıran
 * 429 {@code IMPORT_BUSY} + {@code Retry-After} döner). Reddedilen istek hiçbir şey saymaz / tutmaz. Pod belleği — güvenlik
 * sınırı değil, yük sınırı (CLAUDE.md "Security limits are cluster-wide": yük sınırları pod başına kalabilir).
 */
public final class TechErrorIncidentImportGate {

    /** Pod başına aynı anda en çok görselli içe alma. */
    public static final int PER_POD = 2;
    /** Reddedilen isteğe önerilen bekleme (sn). */
    public static final long RETRY_AFTER_SECONDS = 5L;

    /** Ret nedeni: kişinin süren içe alması var / pod dolu. */
    public enum Refusal { USER, POD }

    private final Set<String> users = ConcurrentHashMap.newKeySet();
    private final Semaphore pod = new Semaphore(PER_POD);

    /** Alınan yer; {@link #close()} bir kez bırakır (fazla çağrı etkisiz). */
    public final class Slot implements AutoCloseable {
        private final String key;
        private final AtomicBoolean open = new AtomicBoolean(true);

        private Slot(String key) {
            this.key = key;
        }

        @Override
        public void close() {
            if (!open.compareAndSet(true, false)) return;
            users.remove(key);
            pod.release();
        }
    }

    /** Sonuç: yer ({@code slot}) ya da ret nedeni ({@code refusal}). */
    public record Result(Slot slot, Refusal refusal) { }

    /** Beklemeden yer ister. {@code key} = kişi (kullanıcı kimliği / adı). */
    public Result tryEnter(String key) {
        String k = key == null ? "?" : key;
        if (!users.add(k)) return new Result(null, Refusal.USER);
        if (!pod.tryAcquire()) {
            users.remove(k);
            return new Result(null, Refusal.POD);
        }
        return new Result(new Slot(k), null);
    }

    /** Boş pod yeri (testler). */
    int available() {
        return pod.availablePermits();
    }
}
