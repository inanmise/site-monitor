package com.sitemonitor.config;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Kapasitesi ÇALIŞMA ANINDA değiştirilebilen sınırlı kuyruk.
 *
 * <p>{@link LinkedBlockingQueue}'nun kapasitesi {@code final}: bir kez kurulunca büyütülemez.
 * Bu yüzden executor kuyruğu eskiden yalnız {@code EXECUTOR_QUEUE_CAPACITY} + yeniden başlatma
 * ile ayarlanabiliyordu. Burada alt kuyruk SINIRSIZ tutulur, sınır {@link #offer(Object)}
 * içinde {@code volatile} bir tavanla uygulanır; {@link #setCapacity(int)} anında etki eder.
 *
 * <p>{@link java.util.concurrent.ThreadPoolExecutor} görevleri yalnız {@code offer(e)} ile
 * kuyruğa koyar; {@code false} dönünce yeni thread açar ya da reddetme politikasını
 * (CallerRuns) çalıştırır — sınırlı kuyruk semantiği birebir korunur. Tavan küçültüldüğünde
 * kuyruktaki mevcut görevler ATILMAZ; yalnız yeni gelenler taşma yoluna düşer.
 *
 * <p>Boyut denetimi ile ekleme arasında kilit yok: eşzamanlı ekleyiciler tavanı en fazla
 * thread sayısı kadar aşabilir. Executor için bu kabul edilebilir (kesin sayı değil geri-basınç
 * amaçlı); kritik bir sayaç olarak kullanılmamalı.
 */
public class ResizableCapacityQueue<E> extends LinkedBlockingQueue<E> {

    private volatile int capacity;

    public ResizableCapacityQueue(int capacity) {
        super();
        setCapacity(capacity);
    }

    public int getCapacity() {
        return capacity;
    }

    /** Yeni tavan (≥1). Küçültme mevcut öğeleri korur; büyütme anında yeni offer'ları kabul eder. */
    public void setCapacity(int capacity) {
        if (capacity < 1) throw new IllegalArgumentException("capacity must be >= 1, got " + capacity);
        this.capacity = capacity;
    }

    @Override
    public boolean offer(E e) {
        if (size() >= capacity) return false;
        return super.offer(e);
    }

    @Override
    public boolean offer(E e, long timeout, TimeUnit unit) throws InterruptedException {
        // Alt kuyruk sınırsız olduğundan super.offer(timeout) hiç beklemez; tavanı uygula.
        return offer(e);
    }

    @Override
    public void put(E e) throws InterruptedException {
        // put() bloklayan ekleme; sınırsız alt kuyrukta bloklamaz. Tavanı aşmamak için
        // reddedip çağırana bildirmek yerine executor semantiğiyle tutarlı olsun diye
        // kapasite dolunca IllegalStateException fırlatılır (ThreadPoolExecutor put kullanmaz).
        if (!offer(e)) throw new IllegalStateException("Queue full (capacity " + capacity + ")");
    }

    @Override
    public boolean add(E e) {
        if (!offer(e)) throw new IllegalStateException("Queue full (capacity " + capacity + ")");
        return true;
    }

    @Override
    public int remainingCapacity() {
        return Math.max(0, capacity - size());
    }
}
