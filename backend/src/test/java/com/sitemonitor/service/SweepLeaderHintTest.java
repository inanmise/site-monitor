package com.sitemonitor.service;

import com.sitemonitor.it.MultiPodTestSupport;
import com.sitemonitor.it.MultiPodTestSupport.Pod;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Çok pod Sürüm 3-1 P0 sözleşmesi — {@link SchedulerService#sweepLeaderHint()} ve
 * {@link SchedulerService#sweepLeaderSinceMs()}: son bilinen liderlik kararının VERİTABANINA SORMADAN okunması.
 * Kaydedici (enqueue kapısı), yan iş yerleşimi ve ölçüm aktarıcısı bu ipucuna dayanır; ipucu kira kararıyla aynı yönde
 * olmalı, kapanışta ve kira bırakılınca false olmalı, veritabanı kapalıyken de fırlatmamalı.
 */
class SweepLeaderHintTest {

    static final long TTL = 180_000L;

    MultiPodTestSupport mp;

    @BeforeEach
    void setUp() {
        mp = MultiPodTestSupport.create();
    }

    @AfterEach
    void tearDown() {
        mp.close();
    }

    @Test
    @DisplayName("küme kaydı yok (tek pod / elle kurulum): ipucu true, liderlik anı -1; kapanış başlayınca false")
    void legacyPod_hintTrueUntilShutdown() {
        SchedulerService s = mp.legacyPod().scheduler();
        assertThat(s.sweepLeaderHint()).isTrue();
        assertThat(s.sweepLeaderSinceMs()).isEqualTo(-1L);

        ReflectionTestUtils.setField(s, "shutdownStarted", true);
        assertThat(s.sweepLeaderHint()).isFalse();
    }

    @Test
    @DisplayName("iki pod: ipucu kira kararını izler (lider true + liderlik anı, takipçi false); kira sorusu öncesi false")
    void twoPods_hintFollowsLeaseDecision() {
        Pod a = mp.pod(1);
        Pod b = mp.pod(2);
        assertThat(a.scheduler().sweepLeaderHint()).as("henüz kira sorulmadı").isFalse();
        assertThat(a.scheduler().sweepLeaderSinceMs()).isEqualTo(-1L);

        assertThat(a.scheduler().isSweepLeader()).isTrue();
        assertThat(b.scheduler().isSweepLeader()).isFalse();
        assertThat(a.scheduler().sweepLeaderHint()).isTrue();
        assertThat(a.scheduler().sweepLeaderSinceMs()).isEqualTo(MultiPodTestSupport.T0);
        assertThat(b.scheduler().sweepLeaderHint()).isFalse();
        assertThat(b.scheduler().sweepLeaderSinceMs()).isEqualTo(-1L);

        // A yenileyemedi, B devraldı: A'nın ipucu ancak kendi kira yoklamasında (LOST) düşer — ipucu karar değildir.
        mp.advance(TTL + 1_000L);
        b.scheduler().renewSweepLeadership();
        assertThat(b.scheduler().sweepLeaderHint()).isTrue();
        assertThat(b.scheduler().sweepLeaderSinceMs()).isEqualTo(mp.now());
        a.scheduler().renewSweepLeadership();
        assertThat(a.scheduler().sweepLeaderHint()).isFalse();
    }

    @Test
    @DisplayName("kira bırakılınca (zarif kapanış) ipucu false; kapanış bayrağı da false yapar")
    void releaseAndShutdown_hintFalse() {
        Pod a = mp.pod(1);
        assertThat(a.scheduler().isSweepLeader()).isTrue();
        a.scheduler().releaseSweepLeadership();
        assertThat(a.scheduler().sweepLeaderHint()).isFalse();

        Pod b = mp.pod(2);
        assertThat(b.scheduler().isSweepLeader()).isTrue();
        ReflectionTestUtils.setField(b.scheduler(), "shutdownStarted", true);
        assertThat(b.scheduler().sweepLeaderHint()).isFalse();
    }

    @Test
    @DisplayName("veritabanı YOK: ipucu ve liderlik anı fırlatmadan son bilinen değeri döner (sorgu yapmaz)")
    void noDatabaseAccess() {
        Pod a = mp.pod(1);
        assertThat(a.scheduler().isSweepLeader()).isTrue();
        mp.close();   // sonraki her DB erişimi hata verir
        assertThatCode(() -> {
            assertThat(a.scheduler().sweepLeaderHint()).isTrue();
            assertThat(a.scheduler().sweepLeaderSinceMs()).isEqualTo(MultiPodTestSupport.T0);
        }).doesNotThrowAnyException();
    }
}
