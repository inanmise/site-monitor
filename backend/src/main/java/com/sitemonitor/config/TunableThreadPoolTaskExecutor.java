package com.sitemonitor.config;

import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * Havuz VE kuyruk boyutu çalışma anında değiştirilebilen {@link ThreadPoolTaskExecutor}.
 *
 * <p>Spring'in kendi {@code setCorePoolSize/setMaxPoolSize}'ı zaten canlı (initialize sonrası
 * alttaki {@link ThreadPoolExecutor}'a yazar) ama iki tuzağı var: (1) {@code setQueueCapacity}
 * yalnız alanı günceller, kuyruk sabit kapasiteli {@code LinkedBlockingQueue} olarak çoktan
 * kurulmuştur; (2) core'u max'ın üstüne çıkaran ara adım {@code IllegalArgumentException} fırlatır
 * (JDK, {@code max < core} durumunu reddeder). Bu sınıf kuyruğu {@link ResizableCapacityQueue}
 * ile kurar ve boyutları sıra-güvenli uygular.
 */
public class TunableThreadPoolTaskExecutor extends ThreadPoolTaskExecutor {

    private volatile ResizableCapacityQueue<Runnable> liveQueue;

    @Override
    protected BlockingQueue<Runnable> createQueue(int queueCapacity) {
        // Spring: 0/negatif kapasite = SynchronousQueue. Biz her zaman sınırlı kuyruk isteriz
        // (CallerRuns geri-basıncı ona dayanır); 1 altı istek 1'e çekilir.
        ResizableCapacityQueue<Runnable> q = new ResizableCapacityQueue<>(Math.max(1, queueCapacity));
        this.liveQueue = q;
        return q;
    }

    /**
     * Havuz boyutlarını canlı uygular. Ön-koşul: {@code 1 <= core <= max}.
     * Sıra: yeni max mevcut core'dan küçük değilse önce max sonra core; aksi halde önce core
     * (küçültme) sonra max. Her iki yolda JDK'nın {@code max < core} kuralı hiç ihlal edilmez.
     */
    public synchronized void applyPoolSizes(int core, int max) {
        if (core < 1 || max < core)
            throw new IllegalArgumentException("invalid pool sizes core=" + core + " max=" + max);
        int currentCore = getCorePoolSize();
        if (max >= currentCore) {
            setMaxPoolSize(max);
            setCorePoolSize(core);
        } else {
            setCorePoolSize(core);
            setMaxPoolSize(max);
        }
    }

    /** Kuyruk tavanını canlı uygular; {@link #getQueueCapacity()} de yeni değeri döner. */
    public synchronized void applyQueueCapacity(int capacity) {
        if (capacity < 1) throw new IllegalArgumentException("invalid queue capacity " + capacity);
        setQueueCapacity(capacity);                     // Spring alanı (health kartı bunu okur)
        ResizableCapacityQueue<Runnable> q = liveQueue;
        if (q != null) q.setCapacity(capacity);         // canlı kuyruk (initialize öncesi null olabilir)
    }

    /** Test/teşhis: canlı kuyruğun gerçek tavanı (Spring alanından bağımsız). */
    public int getLiveQueueCapacity() {
        ResizableCapacityQueue<Runnable> q = liveQueue;
        return q != null ? q.getCapacity() : getQueueCapacity();
    }
}
