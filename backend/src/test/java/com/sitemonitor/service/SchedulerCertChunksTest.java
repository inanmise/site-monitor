package com.sitemonitor.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Çok pod Sürüm 3-4 — {@link SchedulerCertChunks} (inceleme SW-L3, FX-COORD 2026-10-29): tarama lideri bir parçanın satırlarını
 * en çok {@code max(1, çekirdek / 2)}'lik alt gruplarla ölçer (aynı klasik toplama, sıra korunur) — liderin bir parçası
 * {@code certCheckExecutor}'ın bütün çekirdeğini tutmaz. Takipçi, turun koordinatörü (kendi iş parçacığı) ve okunamayan çekirdek
 * bölmez; kapanışta kalan alt gruplar gönderilmez (liste kısa); istisna aynen fırlar.
 */
class SchedulerCertChunksTest {

    final List<Integer> batches = Collections.synchronizedList(new ArrayList<>());

    static List<Map<String, Object>> rows(int n) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("domain", String.format("d%03d.example.com", i));
            r.put("port", 443);
            out.add(r);
        }
        return out;
    }

    /** Sahte zamanlayıcı: {@code gatherCertResults} her çağrının boyutunu kaydeder, her satıra bir sonuç döner. */
    SchedulerService scheduler(boolean leader, boolean coordinating, int core) {
        SchedulerService s = mock(SchedulerService.class);
        when(s.sweepLeaderHint()).thenReturn(leader);
        when(s.isCoordinatingCertRun()).thenReturn(coordinating);
        when(s.certCheckCoreSize()).thenReturn(core);
        when(s.gatherCertResults(anyList(), anyString())).thenAnswer(i -> {
            List<Map<String, Object>> rows = i.getArgument(0);
            batches.add(rows.size());
            List<Map<String, Object>> out = new ArrayList<>();
            for (Map<String, Object> r : rows) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("domain", r.get("domain"));
                m.put("run_id", i.getArgument(1));
                out.add(m);
            }
            return out;
        });
        return s;
    }

    static List<Object> domains(List<Map<String, Object>> results) {
        return results.stream().map(m -> m.get("domain")).toList();
    }

    @Test
    @DisplayName("lider: 20 satırlık parça çekirdek 20 → 10 + 10 (yarım çekirdek); sıra ve sonuçlar klasik toplamayla aynı")
    void leaderMeasuresInHalfCoreSubBatches() {
        SchedulerCertChunks r = new SchedulerCertChunks(scheduler(true, false, 20));
        List<Map<String, Object>> in = rows(20);
        List<Map<String, Object>> out = r.checkDomains(in, "run-1");
        assertThat(batches).containsExactly(10, 10);
        assertThat(domains(out)).isEqualTo(domains(in));
        assertThat(out).allSatisfy(m -> assertThat(m).containsEntry("run_id", "run-1"));

        batches.clear();
        new SchedulerCertChunks(scheduler(true, false, 7)).checkDomains(rows(20), "run-2");
        assertThat(batches).as("çekirdek 7 → alt grup 3").containsExactly(3, 3, 3, 3, 3, 3, 2);
        batches.clear();
        new SchedulerCertChunks(scheduler(true, false, 1)).checkDomains(rows(3), "run-3");
        assertThat(batches).as("çekirdek 1 → en az 1").containsExactly(1, 1, 1);
    }

    @Test
    @DisplayName("takipçi / turun koordinatörü / çekirdek okunamadı / parça alt gruptan küçük → tek klasik toplama (bölünmez)")
    void noSubBatchingOutsideLeaderChunks() {
        new SchedulerCertChunks(scheduler(false, false, 20)).checkDomains(rows(20), "r");
        assertThat(batches).as("takipçi").containsExactly(20);
        batches.clear();
        new SchedulerCertChunks(scheduler(true, true, 20)).checkDomains(rows(20), "r");
        assertThat(batches).as("koordinatör kendi devraldığı alan adlarını bütün çekirdekle ölçer").containsExactly(20);
        batches.clear();
        new SchedulerCertChunks(scheduler(true, false, 0)).checkDomains(rows(20), "r");
        assertThat(batches).as("çekirdek okunamadı").containsExactly(20);
        batches.clear();
        new SchedulerCertChunks(scheduler(true, false, 20)).checkDomains(rows(8), "r");
        assertThat(batches).as("alt gruptan küçük").containsExactly(8);
        batches.clear();
        SchedulerService throwing = scheduler(true, false, 20);
        when(throwing.isCoordinatingCertRun()).thenThrow(new IllegalStateException("okunamadı"));
        new SchedulerCertChunks(throwing).checkDomains(rows(20), "r");
        assertThat(batches).as("okuma hatası → bölme yok").containsExactly(20);
        assertThat(SchedulerCertChunks.leaderSubBatch(scheduler(true, false, 20))).isEqualTo(10);
    }

    @Test
    @DisplayName("kapanış: alt grup kısa dönerse ya da alt gruplar arasında kapanış başlarsa kalanlar gönderilmez, liste KISA döner")
    void closingStopsBetweenSubBatches() {
        SchedulerService s = scheduler(true, false, 20);
        doAnswer(i -> {
            List<Map<String, Object>> rows = i.getArgument(0);
            batches.add(rows.size());
            List<Map<String, Object>> out = new ArrayList<>();
            for (int k = 0; k < rows.size() - 4; k++) out.add(new LinkedHashMap<>(rows.get(k)));   // kapanış: kısa
            return out;
        }).when(s).gatherCertResults(anyList(), anyString());
        List<Map<String, Object>> out = new SchedulerCertChunks(s).checkDomains(rows(20), "r");
        assertThat(batches).containsExactly(10);
        assertThat(out).hasSize(6);

        batches.clear();
        SchedulerService closing = scheduler(true, false, 20);
        when(closing.certRunClosing()).thenReturn(true);
        List<Map<String, Object>> out2 = new SchedulerCertChunks(closing).checkDomains(rows(20), "r");
        assertThat(batches).as("ilk alt gruptan sonra kapanış görüldü").containsExactly(10);
        assertThat(out2).hasSize(10);
    }

    @Test
    @DisplayName("bir kontrolün istisnası klasikteki gibi AYNEN fırlar; boş satır listesi zamanlayıcıya gitmez")
    void exceptionPropagatesAndEmptyRowsSkip() {
        SchedulerService s = scheduler(true, false, 20);
        IllegalStateException boom = new IllegalStateException("denetleyici çöktü");
        doThrow(boom).when(s).gatherCertResults(anyList(), anyString());
        assertThatThrownBy(() -> new SchedulerCertChunks(s).checkDomains(rows(20), "r")).isSameAs(boom);

        SchedulerService idle = scheduler(true, false, 20);
        assertThat(new SchedulerCertChunks(idle).checkDomains(List.of(), "r")).isEmpty();
        verify(idle, never()).gatherCertResults(anyList(), anyString());
    }
}
