package com.sitemonitor.service;

import com.sitemonitor.it.MultiPodTestSupport;
import com.sitemonitor.it.MultiPodTestSupport.Pod;
import com.sitemonitor.service.MonitoringOutageService.ConfirmState;
import com.sitemonitor.service.MonitoringOutageService.SweepItem;
import com.sitemonitor.service.evalstate.EvalStateSink;
import com.sitemonitor.service.evalstate.EvalStateSource;
import com.sitemonitor.service.evalstate.HandoverResult;
import com.sitemonitor.service.schedule.SweepLeadershipChangedEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Çok pod Sürüm 3-2 (2026-10-28, paket C; tasarım R3-2 §5.1): {@code SchedulerService.onContextClosed} devir sırası — gerçek
 * kira (iki "pod", ortak H2, {@link MultiPodTestSupport}).
 *
 * <ul>
 *   <li><b>J-1:</b> kaydedici yok / hazır değil / hazır sorusu fırlatır → devir adımlarının HİÇBİRİ çağrılmaz (ne kaydedici
 *       ne MOS kapısı); kira bugünkü gibi hemen bırakılır, olaylar GAINED + RELEASED.</li>
 *   <li><b>Hazır:</b> aç (kaydedici) → başlat (MOS) → {@code shutdownStarted} → durulma (≤ 300 ms) → boşaltma (700 ms,
 *       durulma sonucu) → kira bırakıldı (RELEASED) → devri kapat (MOS) — boşaltma sırasında kira HÂLÂ bu pod'da.</li>
 *   <li>Boşaltma fırlatsa da kira bırakılır ve devir kapanır; durulmadıysa boşaltma {@code quiet = false} alır.</li>
 * </ul>
 */
class SchedulerHandoverOrderTest {

    final MultiPodTestSupport mp = MultiPodTestSupport.create();
    final List<String> events = new CopyOnWriteArrayList<>();
    MonitoringOutageService mos;

    @BeforeEach
    void setUp() {
        mos = mp.mock(MonitoringOutageService.class);
    }

    @AfterEach
    void close() {
        mp.close();
    }

    /** Lider pod (gerçek kira) — kira olayları {@link #events}'e de yazılır. */
    private Pod leader() {
        Pod a = mp.pod(1);
        a.events().listen(SweepLeadershipChangedEvent.class, e -> events.add("event:" + e.reason()));
        a.scheduler().renewSweepLeadership();
        assertThat(mp.leaseOwner("sweep-leader")).isEqualTo(MultiPodTestSupport.owner(1));
        return a;
    }

    private boolean shutdownStarted(Pod p) {
        return (Boolean) ReflectionTestUtils.getField(p.scheduler(), "shutdownStarted");
    }

    /** Sırayı kaydeden kaydedici; her çağrıda verilen gözlemi de kaydeder. */
    final class RecordingSink implements EvalStateSink {
        final Supplier<Boolean> ready;
        Pod pod;
        RuntimeException flushThrows;
        Error flushError;
        Error openError;

        RecordingSink(Supplier<Boolean> ready) {
            this.ready = ready;
        }

        @Override public boolean handoverReady() {
            events.add("ready");
            return ready.get();
        }

        @Override public void openHandover() {
            events.add("open:shutdownStarted=" + shutdownStarted(pod));
            if (openError != null) throw openError;
        }

        @Override public HandoverResult flushForHandover(long budgetMs, boolean quiet) {
            events.add("flush:" + budgetMs + ":" + quiet + ":shutdownStarted=" + shutdownStarted(pod)
                    + ":leaseHeld=" + MultiPodTestSupport.owner(1).equals(mp.leaseOwner("sweep-leader")));
            if (flushThrows != null) throw flushThrows;
            if (flushError != null) throw flushError;
            return HandoverResult.WRITTEN;
        }

        @Override public void recovery(String rkey, String alertType, String domain) {}
        @Override public void observation(String alertType, SweepItem item, boolean manual, long atMs) {}
        @Override public void typeState(String alertType) {}
        @Override public void chainStart(String confirmKey, long chainId, SweepItem item, ConfirmState state,
                                         String firstFailureAt, long delayMs) {}
        @Override public void chainProgress(String confirmKey, long chainId, ConfirmState state,
                                            List<Map<String, Object>> attempts) {}
        @Override public void chainFinishing(String confirmKey, long chainId, int lockTry) {}
        @Override public void chainEnd(String confirmKey, long chainId) {}
        @Override public void sweepEnd(String alertType) {}
        @Override public void bindSource(EvalStateSource source) {}
        @Override public Status status() { return null; }
    }

    private void recordMos(boolean quiet) {
        doAnswer(inv -> { events.add("begin"); return null; }).when(mos).beginEvalHandover();
        doAnswer(inv -> { events.add("quiesce:" + inv.getArgument(0)); return quiet; }).when(mos).awaitEvalQuiescent(anyLong());
        doAnswer(inv -> {
            events.add("end:leaseReleased=" + (mp.lease("sweep-leader") == null));
            return null;
        }).when(mos).endEvalHandover();
    }

    private void verifyNoHandoverOnMos() {
        verify(mos, never()).beginEvalHandover();
        verify(mos, never()).awaitEvalQuiescent(anyLong());
        verify(mos, never()).endEvalHandover();
    }

    @Test
    @DisplayName("J-1: kaydedici yok → devir adımı yok (MOS kapısı çağrılmaz), kira hemen bırakılır — GAINED + RELEASED")
    void noRecorder_releaseUnchanged() {
        Pod a = leader();
        a.scheduler().onContextClosed(null);
        assertThat(mp.lease("sweep-leader")).as("kira bırakıldı").isNull();
        assertThat(events).containsExactly("event:" + SweepLeadershipChangedEvent.GAINED, "event:" + SweepLeadershipChangedEvent.RELEASED);
        assertThat(shutdownStarted(a)).isTrue();
        verifyNoHandoverOnMos();
    }

    @Test
    @DisplayName("J-1: ayar kapalı (kaydedici hazır değil) → yalnız bellek sorusu; açma / boşaltma / MOS kapısı yok, sıra bugünkü gibi")
    void settingOff_notReady_noHandoverCalls() {
        Pod a = leader();
        RecordingSink sink = new RecordingSink(() -> false);
        sink.pod = a;
        a.scheduler().setEvalStateSinkForHandover(sink);
        a.scheduler().onContextClosed(null);
        assertThat(events).containsExactly("event:" + SweepLeadershipChangedEvent.GAINED, "ready",
                "event:" + SweepLeadershipChangedEvent.RELEASED);
        assertThat(mp.lease("sweep-leader")).isNull();
        verifyNoHandoverOnMos();
    }

    @Test
    @DisplayName("J-1: varsayılan (NOOP) kaydedici — handoverReady false, flushForHandover hiç çağrılmaz")
    void noopRecorder_noHandoverCalls() {
        Pod a = leader();
        EvalStateSink noop = mock(EvalStateSink.class, org.mockito.Mockito.CALLS_REAL_METHODS);
        a.scheduler().setEvalStateSinkForHandover(noop);
        a.scheduler().onContextClosed(null);
        verify(noop).handoverReady();
        verify(noop, never()).openHandover();
        verify(noop, never()).flushForHandover(anyLong(), org.mockito.ArgumentMatchers.anyBoolean());
        verifyNoHandoverOnMos();
        assertThat(mp.lease("sweep-leader")).isNull();
    }

    @Test
    @DisplayName("hazır sorusu fırlatırsa devir atlanır, kapanış bugünkü gibi sürer")
    void readyThrows_closesAsToday() {
        Pod a = leader();
        RecordingSink sink = new RecordingSink(() -> { throw new IllegalStateException("x"); });
        sink.pod = a;
        a.scheduler().setEvalStateSinkForHandover(sink);
        a.scheduler().onContextClosed(null);
        assertThat(mp.lease("sweep-leader")).isNull();
        assertThat(events).doesNotContain("begin").noneMatch(e -> e.startsWith("open") || e.startsWith("flush"));
        verifyNoHandoverOnMos();
    }

    @Test
    @DisplayName("L-5: hazır sorusu Error (AssertionError) fırlatsa da onContextClosed fırlatmaz — devir atlanır, shutdownStarted, kira "
            + "bırakılır, GAINED + RELEASED (bugünkü gibi)")
    void readyThrowsError_closesAsToday() {
        Pod a = leader();
        RecordingSink sink = new RecordingSink(() -> { throw new AssertionError("x"); });
        sink.pod = a;
        a.scheduler().setEvalStateSinkForHandover(sink);
        a.scheduler().onContextClosed(null);
        assertThat(shutdownStarted(a)).isTrue();
        assertThat(mp.lease("sweep-leader")).isNull();
        assertThat(events).containsExactly("event:" + SweepLeadershipChangedEvent.GAINED, "ready",
                "event:" + SweepLeadershipChangedEvent.RELEASED);
        verifyNoHandoverOnMos();
    }

    @Test
    @DisplayName("L-5: açma / MOS kapısı / durulma / boşaltma / kapatma Error fırlatsa da kapanış sürer — kira boşaltmadan sonra bırakılır")
    void handoverStepsThrowError_releaseStillHappens() {
        Pod a = leader();
        doAnswer(inv -> { throw new AssertionError("b"); }).when(mos).beginEvalHandover();
        when(mos.awaitEvalQuiescent(anyLong())).thenThrow(new AssertionError("q"));
        doAnswer(inv -> { throw new AssertionError("e"); }).when(mos).endEvalHandover();
        RecordingSink sink = new RecordingSink(() -> true);
        sink.pod = a;
        sink.openError = new AssertionError("o");
        sink.flushError = new AssertionError("f");
        a.scheduler().setEvalStateSinkForHandover(sink);
        a.scheduler().onContextClosed(null);
        assertThat(shutdownStarted(a)).isTrue();
        assertThat(events).containsExactly("event:" + SweepLeadershipChangedEvent.GAINED, "ready", "open:shutdownStarted=false",
                "flush:" + EvalStateSink.HANDOVER_FLUSH_MS + ":false:shutdownStarted=true:leaseHeld=true",
                "event:" + SweepLeadershipChangedEvent.RELEASED);
        assertThat(mp.lease("sweep-leader")).isNull();
        verify(mos).endEvalHandover();
    }

    @Test
    @DisplayName("L-5: kaydedici sağlayıcısı Error fırlatsa da (getIfUnique) kapanış bugünkü gibi")
    @SuppressWarnings("unchecked")
    void providerThrowsError_closesAsToday() {
        Pod a = leader();
        ObjectProvider<EvalStateSink> broken = mock(ObjectProvider.class);
        when(broken.getIfUnique()).thenThrow(new AssertionError("x"));
        a.scheduler().setEvalStateSinkProvider(broken);
        a.scheduler().onContextClosed(null);
        assertThat(shutdownStarted(a)).isTrue();
        assertThat(mp.lease("sweep-leader")).isNull();
        verifyNoHandoverOnMos();
    }

    @Test
    @DisplayName("hazır: aç → başlat → shutdownStarted → durulma (300) → boşaltma (700, kira hâlâ bende) → RELEASED → devri kapat")
    void ready_fullOrder() {
        Pod a = leader();
        recordMos(true);
        RecordingSink sink = new RecordingSink(() -> true);
        sink.pod = a;
        a.scheduler().setEvalStateSinkForHandover(sink);

        a.scheduler().onContextClosed(null);

        assertThat(events).containsExactly(
                "event:" + SweepLeadershipChangedEvent.GAINED,
                "ready",
                "open:shutdownStarted=false",
                "begin",
                "quiesce:" + EvalStateSink.HANDOVER_QUIESCE_MS,
                "flush:" + EvalStateSink.HANDOVER_FLUSH_MS + ":true:shutdownStarted=true:leaseHeld=true",
                "event:" + SweepLeadershipChangedEvent.RELEASED,
                "end:leaseReleased=true");
        assertThat(mp.lease("sweep-leader")).isNull();
        // @PreDestroy yedeği idempotent: ikinci devir / olay yok
        a.scheduler().onShutdown();
        assertThat(events).hasSize(8);
    }

    @Test
    @DisplayName("hazır ama değerlendirmeler durulmadı → boşaltma quiet=false alır (işaretsiz), kira yine bırakılır")
    void notQuiet_flushWithoutMarker() {
        Pod a = leader();
        recordMos(false);
        RecordingSink sink = new RecordingSink(() -> true);
        sink.pod = a;
        a.scheduler().setEvalStateSinkForHandover(sink);
        a.scheduler().onContextClosed(null);
        assertThat(events).contains("flush:" + EvalStateSink.HANDOVER_FLUSH_MS + ":false:shutdownStarted=true:leaseHeld=true");
        assertThat(mp.lease("sweep-leader")).isNull();
    }

    @Test
    @DisplayName("boşaltma fırlatsa da kira bırakılır ve devir kapanır (finally)")
    void flushThrows_releaseStillHappens() {
        Pod a = leader();
        recordMos(true);
        RecordingSink sink = new RecordingSink(() -> true);
        sink.pod = a;
        sink.flushThrows = new IllegalStateException("boom");
        a.scheduler().setEvalStateSinkForHandover(sink);
        a.scheduler().onContextClosed(null);
        assertThat(mp.lease("sweep-leader")).as("kira bırakıldı").isNull();
        assertThat(events).endsWith("event:" + SweepLeadershipChangedEvent.RELEASED, "end:leaseReleased=true");
    }

    @Test
    @DisplayName("MOS kapısı fırlatsa da (başlat / durulma / kapat) kapanış sürer, kira bırakılır")
    void mosGateThrows_releaseStillHappens() {
        Pod a = leader();
        doAnswer(inv -> { throw new IllegalStateException("b"); }).when(mos).beginEvalHandover();
        when(mos.awaitEvalQuiescent(anyLong())).thenThrow(new IllegalStateException("q"));
        doAnswer(inv -> { throw new IllegalStateException("e"); }).when(mos).endEvalHandover();
        RecordingSink sink = new RecordingSink(() -> true);
        sink.pod = a;
        a.scheduler().setEvalStateSinkForHandover(sink);
        a.scheduler().onContextClosed(null);
        assertThat(events).contains("flush:" + EvalStateSink.HANDOVER_FLUSH_MS + ":false:shutdownStarted=true:leaseHeld=true");
        assertThat(mp.lease("sweep-leader")).isNull();
        verify(mos).endEvalHandover();
    }

    @Test
    @DisplayName("Spring sağlayıcısı: kaydedici ilk kapanışta çözülür; sağlayıcı fırlatırsa / boşsa bugünkü kapanış")
    @SuppressWarnings("unchecked")
    void providerResolution() {
        Pod a = leader();
        recordMos(true);
        RecordingSink sink = new RecordingSink(() -> true);
        sink.pod = a;
        ObjectProvider<EvalStateSink> provider = mock(ObjectProvider.class);
        when(provider.getIfUnique()).thenReturn(sink);
        a.scheduler().setEvalStateSinkProvider(provider);
        a.scheduler().onContextClosed(null);
        assertThat(events).contains("open:shutdownStarted=false", "begin");

        MultiPodTestSupport other = MultiPodTestSupport.create();
        try {
            Pod b = other.pod(1);
            b.scheduler().renewSweepLeadership();
            ObjectProvider<EvalStateSink> broken = mock(ObjectProvider.class);
            when(broken.getIfUnique()).thenThrow(new IllegalStateException("not unique"));
            b.scheduler().setEvalStateSinkProvider(broken);
            b.scheduler().onContextClosed(null);
            assertThat(other.lease("sweep-leader")).isNull();
            ObjectProvider<EvalStateSink> empty = mock(ObjectProvider.class);
            Pod c = other.pod(2);
            c.scheduler().renewSweepLeadership();
            c.scheduler().setEvalStateSinkProvider(empty);
            c.scheduler().onContextClosed(null);
            assertThat(other.lease("sweep-leader")).isNull();
        } finally {
            other.close();
        }
    }
}
