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
    private static ThreadLocal<CheckWorker.JobTask> current() throws ReflectiveOperationException {
        Field f = CheckWorker.class.getDeclaredField("CURRENT");
        f.setAccessible(true);
        return (ThreadLocal<CheckWorker.JobTask>) f.get(null);
    }

    /**
     * Gövdeyi BIRAKILMAMIŞ bir işçi işi olarak koşturur; gövdenin ortasında {@link #abandonCurrentJob()} çağrılırsa (ör. sahte
     * bir depo cevabının içinde) iş o andan sonra "bırakıldı" olur — gövde ortasında dolan zaman aşımı.
     */
    public static <T> T runAsJob(Supplier<T> body) {
        try {
            ThreadLocal<CheckWorker.JobTask> current = current();
            current.set(new CheckWorker.JobTask(null, System.nanoTime()));
            try {
                return body.get();
            } finally {
                current.remove();
            }
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    /** {@link #runAsJob} içindeki işi bu iş parçacığında "bırakıldı" yapar (işçi zaman aşımı ile aynı bayrak). */
    public static void abandonCurrentJob() {
        try {
            CheckWorker.JobTask t = current().get();
            if (t == null) throw new IllegalStateException("işçi işi yok");
            t.abandoned = true;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
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
