package com.sitemonitor.service;

import com.sitemonitor.repository.AlertEventRepository;
import com.sitemonitor.repository.DnsMonitorRepository;
import com.sitemonitor.repository.DnsRecordRepository;
import com.sitemonitor.repository.NetworkOutageEventRepository;
import com.sitemonitor.service.evalstate.EvalLoadGate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Çok pod Sürüm 3-3 P0 — {@link MonitoringOutageService#evalBatchTicket()} (dağıtık kontrol sonuçlarının boşaltıcısı, paket
 * C): değerlendirme kapısının girişi — kapı NONE iken hemen döner ve SAYILIR ({@code awaitEvalQuiescent} bilet açıkken
 * false), LOADING iken yükleme bitene dek BEKLER (yarım belleğe karar yok), aynı iş parçacığında YENİDEN GİRİLEBİLİR (bilet
 * açıkken {@code handleDnsSweep} / iç bilet beklemez).
 */
class MonitoringOutageEvalBatchTicketTest {

    final AtomicLong clock = new AtomicLong(1_000_000L);
    MonitoringOutageService mos;
    EvalLoadGate gate;
    Thread worker;

    @BeforeEach
    void setUp() {
        AppSettingsService settings = mock(AppSettingsService.class);
        when(settings.getBoolean(anyString(), anyBoolean())).thenAnswer(i -> i.getArgument(1));
        when(settings.getInt(anyString(), anyInt())).thenAnswer(i -> i.getArgument(1));
        AlertEventRepository alertRepo = mock(AlertEventRepository.class);
        when(alertRepo.findOpenByDomainIn(anyCollection())).thenReturn(List.of());
        mos = new MonitoringOutageService(alertRepo, mock(EscalationService.class), mock(JdbcTemplate.class),
                mock(DnsRecordRepository.class), mock(DnsMonitorRepository.class), settings,
                mock(NetworkOutageEventRepository.class));
        ReflectionTestUtils.setField(mos, "nowMs", (LongSupplier) clock::get);
        ReflectionTestUtils.setField(mos, "suppressionClock", (LongSupplier) clock::get);
        ReflectionTestUtils.setField(mos, "confirmExecutor", MonitoringMultiPodKit.immediate());
        ReflectionTestUtils.setField(mos, "recoveryExecutor", MonitoringMultiPodKit.immediate());
        gate = mos.evalLoadGate();
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        gate.disarm();
        if (worker != null) worker.join(5_000);
    }

    @Test
    @DisplayName("kapı NONE: bilet hemen döner ve sayılır — açıkken durulma false, kapanınca true; close iki kez çağrılsa da güvenli")
    void countedByQuiescence() {
        assertThat(gate.state()).isEqualTo(EvalLoadGate.State.NONE);
        assertThat(mos.awaitEvalQuiescent(10)).isTrue();
        EvalLoadGate.Ticket t = mos.evalBatchTicket();
        try {
            assertThat(gate.inFlight()).isEqualTo(1);
            assertThat(mos.awaitEvalQuiescent(100)).as("bilet açıkken devir durulması beklemeli").isFalse();
        } finally {
            t.close();
        }
        assertThat(gate.inFlight()).isZero();
        assertThat(mos.awaitEvalQuiescent(100)).isTrue();
    }

    @Test
    @DisplayName("kapı LOADING (3-2 devir yüklemesi): bilet yükleme bitene dek BEKLER, sökülünce girer")
    void waitsWhileLoading() throws InterruptedException {
        gate.arm(42L, clock.get() + EvalLoadGate.LOAD_TIMEOUT_MS);
        assertThat(gate.state()).isEqualTo(EvalLoadGate.State.LOADING);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        worker = new Thread(() -> {
            try (EvalLoadGate.Ticket t = mos.evalBatchTicket()) {
                entered.countDown();
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "r33-p0-drain");
        worker.start();
        assertThat(entered.await(300, TimeUnit.MILLISECONDS)).as("yükleme sürerken girmemeli").isFalse();
        gate.disarm();
        assertThat(entered.await(5, TimeUnit.SECONDS)).as("sökülünce girer").isTrue();
        assertThat(gate.inFlight()).isEqualTo(1);
        release.countDown();
        worker.join(5_000);
        assertThat(gate.inFlight()).isZero();
    }

    @Test
    @DisplayName("yeniden girilebilir: bilet açıkken kapı LOADING'e geçse de aynı iş parçacığının iç bileti ve handleDnsSweep beklemez")
    void reentrantWhileLoading() throws InterruptedException {
        CountDownLatch holding = new CountDownLatch(1);
        CountDownLatch armed = new CountDownLatch(1);
        AtomicReference<Long> nestedMs = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        worker = new Thread(() -> {
            try (EvalLoadGate.Ticket outer = mos.evalBatchTicket()) {
                holding.countDown();
                armed.await(5, TimeUnit.SECONDS);
                long t0 = System.nanoTime();
                try (EvalLoadGate.Ticket inner = mos.evalBatchTicket()) {
                    mos.handleDnsSweep(List.of(), List.of(), List.of(), List.of(), List.of());
                }
                nestedMs.set(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0));
            } catch (Throwable e) {
                failure.set(e);
            }
        }, "r33-p0-reentrant");
        worker.start();
        assertThat(holding.await(5, TimeUnit.SECONDS)).isTrue();
        gate.arm(43L, clock.get() + EvalLoadGate.LOAD_TIMEOUT_MS);
        armed.countDown();
        worker.join(5_000);
        assertThat(failure.get()).isNull();
        assertThat(nestedMs.get()).as("iç giriş beklemedi (yükleme son tarihi 20 sn)").isNotNull().isLessThan(2_000L);
        assertThat(gate.inFlight()).isZero();
        assertThat(gate.state()).as("kapı hâlâ yüklemede — iç giriş sökmedi").isEqualTo(EvalLoadGate.State.LOADING);
    }
}
