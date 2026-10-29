package com.sitemonitor.service.checks;

import java.lang.reflect.Field;
import java.util.function.Supplier;

/**
 * Test kancası (R3-3 FX, entegrasyon): bir gövdeyi işçinin "bu iş bırakıldı" durumunda koşturur — kesme bayrağı gövdedeki bir
 * {@code catch} tarafından temizlenmiş gibi (bayrak YOK, yalnız {@link CheckWorker#currentJobAbandoned()} true). Üretim
 * kodunda karşılığı yok; {@code CheckWorker.runTask} aynı ThreadLocal'ı iş süresince kurar.
 */
public final class CheckWorkerTestHooks {

    private CheckWorkerTestHooks() {
    }

    @SuppressWarnings("unchecked")
    public static <T> T runAsAbandonedJob(Supplier<T> body) {
        try {
            Field f = CheckWorker.class.getDeclaredField("CURRENT");
            f.setAccessible(true);
            ThreadLocal<CheckWorker.JobTask> current = (ThreadLocal<CheckWorker.JobTask>) f.get(null);
            CheckWorker.JobTask t = new CheckWorker.JobTask(null, System.nanoTime());
            t.abandoned = true;
            current.set(t);
            try {
                return body.get();
            } finally {
                current.remove();
            }
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
