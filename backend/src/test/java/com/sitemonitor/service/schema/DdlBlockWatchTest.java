package com.sitemonitor.service.schema;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * İndeks DDL bekçisi (2026-11-06, inceleme ORTA-2) — sözleşme. Pinlenen: art arda 3 yoklamada bizi bekleyen oturum varsa
 * deyim BİR KEZ iptal edilir ve gerekçe yazılır; aralıklı bekleme (art arda değil) iptal etmez; art arda 3 yoklama hatası
 * da iptal eder (kör bekçi); iptal gönderilemezse gerekçe yazılmaz, yoklama sürer; durdurulan bekçi iptal etmez; ömür
 * DDL süresi + 1 dk ile sınırlı; bağlantı her yolda kapanır.
 */
class DdlBlockWatchTest {

    private static final int PID = 4242;

    private static final class FakeProbe implements DdlBlockWatch.Probe {
        volatile IntSupplier waiting = () -> 0;
        volatile boolean pollFails;
        volatile boolean cancelFails;
        final AtomicInteger polls = new AtomicInteger();
        final List<Integer> cancels = new CopyOnWriteArrayList<>();
        final AtomicInteger cancelAttempts = new AtomicInteger();
        final AtomicBoolean closed = new AtomicBoolean();

        @Override public int waitingFor(int pid) throws SQLException {
            polls.incrementAndGet();
            assertThat(pid).isEqualTo(PID);
            if (pollFails) throw new SQLException("bağlantı koptu", "08006");
            return waiting.getAsInt();
        }

        @Override public void cancel(int pid) throws SQLException {
            cancelAttempts.incrementAndGet();
            if (cancelFails) throw new SQLException("iptal gönderilemedi", "08006");
            cancels.add(pid);
        }

        @Override public void close() { closed.set(true); }
    }

    private static void await(BooleanSupplier cond) throws InterruptedException {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!cond.getAsBoolean() && System.nanoTime() < until) Thread.sleep(5);
        assertThat(cond.getAsBoolean()).as("10 sn içinde").isTrue();
    }

    @Test
    @DisplayName("Art arda 3 yoklamada bizi bekleyen oturum → deyim bir kez iptal edilir, gerekçe yazılır, bağlantı kapanır")
    void threeStrikesCancelOnce() throws Exception {
        FakeProbe p = new FakeProbe();
        p.waiting = () -> 2;
        DdlBlockWatch w = DdlBlockWatch.start(p, PID, 60_000, 5, System::currentTimeMillis);
        await(w::finished);
        assertThat(p.cancels).containsExactly(PID);
        assertThat(p.polls.get()).isEqualTo(DdlBlockWatch.STRIKES);
        assertThat(w.firedReason()).contains("2 oturum");
        assertThat(p.closed).isTrue();
        w.stop();
        assertThat(p.cancels).hasSize(1);
    }

    @Test
    @DisplayName("Aralıklı bekleme (art arda değil) iptal etmez; durdurulunca bağlantı kapanır, gerekçe yok")
    void intermittentNeverFires() throws Exception {
        FakeProbe p = new FakeProbe();
        AtomicInteger n = new AtomicInteger();
        p.waiting = () -> n.getAndIncrement() % 3 == 2 ? 0 : 1;   // 1, 1, 0, 1, 1, 0 …
        DdlBlockWatch w = DdlBlockWatch.start(p, PID, 60_000, 2, System::currentTimeMillis);
        await(() -> p.polls.get() >= 30);
        w.stop();
        await(w::finished);
        assertThat(p.cancels).isEmpty();
        assertThat(w.firedReason()).isNull();
        assertThat(p.closed).isTrue();
    }

    @Test
    @DisplayName("Art arda 3 yoklama hatası → kör bekçiyle sürmez, iptal eder (gerekçe: yoklayamadı)")
    void threePollErrorsCancel() throws Exception {
        FakeProbe p = new FakeProbe();
        p.pollFails = true;
        DdlBlockWatch w = DdlBlockWatch.start(p, PID, 60_000, 5, System::currentTimeMillis);
        await(w::finished);
        assertThat(p.cancels).containsExactly(PID);
        assertThat(w.firedReason()).contains("yoklayamadı");
    }

    @Test
    @DisplayName("İptal gönderilemezse gerekçe yazılmaz, yoklama sürer; sonraki denemede gönderilince gerekçe yazılır")
    void failedCancelRetries() throws Exception {
        FakeProbe p = new FakeProbe();
        p.waiting = () -> 1;
        p.cancelFails = true;
        DdlBlockWatch w = DdlBlockWatch.start(p, PID, 60_000, 5, System::currentTimeMillis);
        await(() -> p.cancelAttempts.get() >= 2);
        assertThat(w.firedReason()).as("iptal gönderilemedi → gerekçe yok").isNull();
        assertThat(w.finished()).isFalse();
        p.cancelFails = false;
        await(w::finished);
        assertThat(p.cancels).containsExactly(PID);
        assertThat(w.firedReason()).isNotNull();
    }

    @Test
    @DisplayName("Yoklamadan önce durdurulan bekçi hiç yoklamaz / iptal etmez; stop hemen döner")
    void stopBeforePoll() throws Exception {
        FakeProbe p = new FakeProbe();
        p.waiting = () -> 5;
        DdlBlockWatch w = DdlBlockWatch.start(p, PID, 60_000, 60_000, System::currentTimeMillis);
        long t0 = System.nanoTime();
        w.stop();
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0)).isLessThan(DdlBlockWatch.JOIN_MS);
        await(w::finished);
        assertThat(p.polls.get()).isZero();
        assertThat(p.cancels).isEmpty();
        assertThat(p.closed).isTrue();
    }

    @Test
    @DisplayName("Ömür sınırlı: saat DDL süresi + 1 dk'yı geçince bekçi kendiliğinden biter (iptal yok)")
    void lifetimeIsBounded() throws Exception {
        FakeProbe p = new FakeProbe();
        AtomicLong now = new AtomicLong(1_000_000L);
        DdlBlockWatch w = DdlBlockWatch.start(p, PID, 30_000, 5, now::get);
        await(() -> p.polls.get() >= 2);
        now.addAndGet(30_000 + DdlBlockWatch.MAX_EXTRA_MS);
        await(w::finished);
        assertThat(p.cancels).isEmpty();
        assertThat(p.closed).isTrue();
    }
}
