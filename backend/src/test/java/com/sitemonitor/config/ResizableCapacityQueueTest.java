package com.sitemonitor.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Kuyruk tavanı canlı değişir: büyütme anında yeni offer'ları kabul eder, küçültme mevcut
 * öğeleri korur ama yenileri reddeder. LinkedBlockingQueue'nun final kapasitesi bunu YAPAMAZDI.
 */
class ResizableCapacityQueueTest {

    @Test
    @DisplayName("tavan dolunca offer false döner; büyütünce aynı offer kabul edilir")
    void offer_respectsLiveCapacity() {
        ResizableCapacityQueue<Integer> q = new ResizableCapacityQueue<>(2);
        assertThat(q.offer(1)).isTrue();
        assertThat(q.offer(2)).isTrue();
        assertThat(q.offer(3)).as("3. öğe tavanı aşar").isFalse();
        assertThat(q.remainingCapacity()).isZero();

        q.setCapacity(3);
        assertThat(q.offer(3)).as("tavan büyüdü — restart yok").isTrue();
        assertThat(q.getCapacity()).isEqualTo(3);
        assertThat(q.size()).isEqualTo(3);
    }

    @Test
    @DisplayName("küçültme mevcut öğeleri ATMAZ, yalnız yeni gelenleri reddeder")
    void shrink_keepsExistingRejectsNew() {
        ResizableCapacityQueue<Integer> q = new ResizableCapacityQueue<>(5);
        for (int i = 0; i < 5; i++) q.offer(i);
        q.setCapacity(2);
        assertThat(q.size()).as("kuyruktaki 5 görev korunur").isEqualTo(5);
        assertThat(q.offer(99)).isFalse();
        assertThat(q.remainingCapacity()).isZero();
        // Boşalınca yeni tavan geçerli
        q.clear();
        assertThat(q.offer(1)).isTrue();
        assertThat(q.offer(2)).isTrue();
        assertThat(q.offer(3)).isFalse();
    }

    @Test
    @DisplayName("tavan < 1 reddedilir (0 = SynchronousQueue semantiğine sessiz kayma OLMAZ)")
    void capacityBelowOne_rejected() {
        assertThatThrownBy(() -> new ResizableCapacityQueue<Integer>(0))
                .isInstanceOf(IllegalArgumentException.class);
        ResizableCapacityQueue<Integer> q = new ResizableCapacityQueue<>(1);
        assertThatThrownBy(() -> q.setCapacity(-5)).isInstanceOf(IllegalArgumentException.class);
        assertThat(q.getCapacity()).isEqualTo(1);
    }

    @Test
    @DisplayName("add/put tavanda IllegalStateException — sınırsız alt kuyruğa sızıntı yok")
    void addAndPut_honourCapacity() throws InterruptedException {
        ResizableCapacityQueue<Integer> q = new ResizableCapacityQueue<>(1);
        q.put(1);
        assertThatThrownBy(() -> q.add(2)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> q.put(2)).isInstanceOf(IllegalStateException.class);
        assertThat(q.offer(2, 10, java.util.concurrent.TimeUnit.MILLISECONDS)).isFalse();
        assertThat(q.size()).isEqualTo(1);
    }
}
