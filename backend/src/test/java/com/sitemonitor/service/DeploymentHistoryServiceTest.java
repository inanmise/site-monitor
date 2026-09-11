package com.sitemonitor.service;

import com.sitemonitor.model.DeploymentHistory;
import com.sitemonitor.repository.DeploymentHistoryRepository;
import com.sitemonitor.service.DeploymentHistoryService.Kind;
import com.sitemonitor.service.DeploymentHistoryService.ManualRequest;
import com.sitemonitor.service.DeploymentHistoryService.TransitionEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Dağıtım geçmişi servisi — kayıt yolu BEST-EFFORT (hiçbir hata açılışı/kapanışı düşürmez),
 * geri doldurma idempotent, elle kayıt sıkı doğrulanır, yalnız MANUAL satır silinir, sürüm geçişi
 * olay yayımlar (E3 mail/push dinler).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DeploymentHistoryServiceTest {

    @Mock DeploymentHistoryRepository repo;
    @Mock BuildInfo buildInfo;
    @Mock ReleaseIndexService releaseIndex;
    @Mock JdbcTemplate jdbc;
    @Mock ApplicationEventPublisher events;

    private static final String SHA = "0123456789abcdef0123456789abcdef01234567";

    private static BuildInfo.Snapshot snapshot(String version) {
        return new BuildInfo.Snapshot(version, "env", SHA, "2026-09-10T18:00:00Z", version,
                "ghcr.io/example/site-monitor:v" + version, "prod", "sitemonitor", 42, version, "cfg",
                "inst-1", "host-1", "pod-1", "node-1", "25", "2026-09-11T08:00:00Z",
                Instant.parse("2026-09-11T08:00:00Z").toEpochMilli());
    }

    private static DeploymentHistory row(long id, String startedAt, String version, String source) {
        DeploymentHistory d = new DeploymentHistory();
        d.setId(id); d.setStartedAt(startedAt); d.setRecordedAt(startedAt);
        d.setEnvironment("prod"); d.setVersion(version); d.setSource(source);
        return d;
    }

    private DeploymentHistoryService service;

    @BeforeEach
    void setUp() {
        when(buildInfo.get()).thenReturn(snapshot("1.1.0"));
        when(releaseIndex.all()).thenReturn(List.of());
        when(releaseIndex.find(any())).thenReturn(Optional.empty());
        when(repo.findByEnvironmentOrderByStartedAtAscIdAsc(anyString())).thenReturn(List.of());
        service = new DeploymentHistoryService(repo, buildInfo, releaseIndex, jdbc, events, new MockEnvironment());
    }

    // ── recordStartup ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("recordStartup: satır BuildInfo'dan doldurulur, kaynak STARTUP, createdBy SYSTEM; currentId saklanır")
    void recordStartup_persistsSnapshot() {
        when(repo.save(any())).thenAnswer(inv -> { DeploymentHistory d = inv.getArgument(0); d.setId(7L); return d; });
        service.recordStartup();
        ArgumentCaptor<DeploymentHistory> cap = ArgumentCaptor.forClass(DeploymentHistory.class);
        verify(repo).save(cap.capture());
        DeploymentHistory d = cap.getValue();
        assertThat(d.getVersion()).isEqualTo("1.1.0");
        assertThat(d.getEnvironment()).isEqualTo("prod");
        assertThat(d.getGitCommit()).isEqualTo(SHA);
        assertThat(d.getHelmRevision()).isEqualTo(42);
        assertThat(d.getPodName()).isEqualTo("pod-1");
        assertThat(d.getStartedAt()).isEqualTo("2026-09-11T08:00:00Z");
        assertThat(d.getSource()).isEqualTo(DeploymentHistory.SOURCE_STARTUP);
        assertThat(d.getCreatedBy()).isEqualTo("SYSTEM");
        assertThat(d.getLastSeenAt()).isEqualTo(d.getRecordedAt());
        assertThat(service.currentId()).isEqualTo(7L);
    }

    @Test
    @DisplayName("recordStartup: repo patlarsa istisna YUTULUR (açılış düşmez), currentId boş kalır")
    void recordStartup_swallowsRepoException() {
        when(repo.save(any())).thenThrow(new RuntimeException("db down"));
        assertThatCode(service::recordStartup).doesNotThrowAnyException();
        assertThat(service.currentId()).isNull();
        verify(events, never()).publishEvent(any(Object.class));
    }

    @Test
    @DisplayName("recordStartup: BuildInfo bile patlasa açılış düşmez")
    void recordStartup_buildInfoThrows() {
        when(buildInfo.get()).thenThrow(new IllegalStateException("boom"));
        assertThatCode(service::recordStartup).doesNotThrowAnyException();
        verify(repo, never()).save(any());
    }

    @Test
    @DisplayName("site.monitor.deploy.history.enabled=false → hiç kayıt yok")
    void recordStartup_disabledFlag() {
        DeploymentHistoryService off = new DeploymentHistoryService(repo, buildInfo, releaseIndex, jdbc, events,
                new MockEnvironment().withProperty("site.monitor.deploy.history.enabled", "false"));
        off.recordStartup();
        verify(repo, never()).save(any());
        assertThat(off.currentId()).isNull();
    }

    @Test
    @DisplayName("yükseltme (1.0.0 → 1.1.0) → TransitionEvent(UPGRADE, prod, 1.0.0→1.1.0) yayımlanır")
    void recordStartup_publishesUpgradeEvent() {
        DeploymentHistory saved = row(2, "2026-09-11T08:00:00Z", "1.1.0", DeploymentHistory.SOURCE_STARTUP);
        when(repo.save(any())).thenReturn(saved);
        when(repo.findByEnvironmentOrderByStartedAtAscIdAsc("prod")).thenReturn(List.of(
                row(1, "2026-09-01T08:00:00Z", "1.0.0", DeploymentHistory.SOURCE_STARTUP), saved));
        service.recordStartup();
        ArgumentCaptor<Object> cap = ArgumentCaptor.forClass(Object.class);
        verify(events).publishEvent(cap.capture());
        assertThat(cap.getValue()).isInstanceOf(TransitionEvent.class);
        TransitionEvent ev = (TransitionEvent) cap.getValue();
        assertThat(ev.kind()).isEqualTo(Kind.UPGRADE);
        assertThat(ev.environment()).isEqualTo("prod");
        assertThat(ev.fromVersion()).isEqualTo("1.0.0");
        assertThat(ev.toVersion()).isEqualTo("1.1.0");
        assertThat(ev.row()).isSameAs(saved);
    }

    @Test
    @DisplayName("aynı sürümle yeniden başlatma → olay YOK; dinleyici patlarsa açılış yine düşmez")
    void recordStartup_restartNoEvent_listenerFailureSwallowed() {
        DeploymentHistory saved = row(2, "2026-09-11T08:00:00Z", "1.1.0", DeploymentHistory.SOURCE_STARTUP);
        when(repo.save(any())).thenReturn(saved);
        when(repo.findByEnvironmentOrderByStartedAtAscIdAsc("prod")).thenReturn(List.of(
                row(1, "2026-09-01T08:00:00Z", "1.1.0", DeploymentHistory.SOURCE_STARTUP), saved));
        service.recordStartup();
        verify(events, never()).publishEvent(any(Object.class));

        // Geri alma + dinleyici hatası
        when(repo.findByEnvironmentOrderByStartedAtAscIdAsc("prod")).thenReturn(List.of(
                row(1, "2026-09-01T08:00:00Z", "2.0.0", DeploymentHistory.SOURCE_STARTUP), saved));
        org.mockito.Mockito.doThrow(new RuntimeException("listener")).when(events).publishEvent(any(Object.class));
        assertThatCode(service::recordStartup).doesNotThrowAnyException();
        verify(events).publishEvent(any(Object.class));
    }

    // ── markReady / touch / recordShutdown ────────────────────────────────────────

    @Test
    @DisplayName("markReady/touch/recordShutdown: currentId yokken repo'ya dokunmaz; varken ISO-UTC anla çağırır")
    void lifecycleHooks() {
        service.markReady(); service.touch(); service.recordShutdown("graceful");
        verify(repo, never()).markReady(anyLong(), anyString());
        verify(repo, never()).touch(anyLong(), anyString());
        verify(repo, never()).markEnded(anyLong(), anyString(), anyString());

        when(repo.save(any())).thenAnswer(inv -> { DeploymentHistory d = inv.getArgument(0); d.setId(9L); return d; });
        service.recordStartup();
        service.markReady();
        service.touch();
        service.recordShutdown(null);
        String iso = "\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}Z";
        verify(repo).markReady(eq(9L), org.mockito.ArgumentMatchers.matches(iso));
        verify(repo).touch(eq(9L), org.mockito.ArgumentMatchers.matches(iso));
        verify(repo).markEnded(eq(9L), org.mockito.ArgumentMatchers.matches(iso), eq("unknown"));
    }

    @Test
    @DisplayName("kapanışta repo patlarsa istisna yutulur (kapanış engellenmez)")
    void recordShutdown_swallows() {
        when(repo.save(any())).thenAnswer(inv -> { DeploymentHistory d = inv.getArgument(0); d.setId(9L); return d; });
        service.recordStartup();
        when(repo.markEnded(anyLong(), anyString(), anyString())).thenThrow(new RuntimeException("closed"));
        when(repo.touch(anyLong(), anyString())).thenThrow(new RuntimeException("closed"));
        when(repo.markReady(anyLong(), anyString())).thenThrow(new RuntimeException("closed"));
        assertThatCode(() -> { service.recordShutdown("crash"); service.touch(); service.markReady(); })
                .doesNotThrowAnyException();
    }

    // ── backfill ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("backfill: SCHEMA_PATCH denetim satırları BACKFILL olur; audit_ref zaten varsa ATLANIR (idempotent)")
    void backfill_idempotent() {
        when(jdbc.queryForList(anyString())).thenReturn(List.of(
                Map.of("id", 10L, "event_time", "2026-08-01T03:00:00"),
                Map.of("id", 11, "event_time", "2026-08-02T03:00:00")));
        when(repo.existsByAuditRef(10L)).thenReturn(true);
        when(repo.existsByAuditRef(11L)).thenReturn(false);
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Map<String, Object> out = service.backfill("admin", null);   // ortam boş → koşan ortam (prod)
        assertThat(out).containsEntry("environment", "prod").containsEntry("candidates", 2)
                .containsEntry("inserted", 1).containsEntry("skippedExisting", 1);

        ArgumentCaptor<DeploymentHistory> cap = ArgumentCaptor.forClass(DeploymentHistory.class);
        verify(repo).save(cap.capture());
        DeploymentHistory d = cap.getValue();
        assertThat(d.getAuditRef()).isEqualTo(11L);
        assertThat(d.getVersion()).isNull();                       // tahmin YOK
        assertThat(d.getSource()).isEqualTo(DeploymentHistory.SOURCE_BACKFILL);
        assertThat(d.getStartedAt()).isEqualTo("2026-08-02T03:00:00");   // audit event_time dönüşümsüz
        assertThat(d.getCreatedBy()).isEqualTo("admin");
        assertThat(d.getNote()).contains("#11");

        // İkinci koşum: hepsi mevcut → sıfır ekleme
        when(repo.existsByAuditRef(anyLong())).thenReturn(true);
        assertThat(service.backfill("admin", "Staging ")).containsEntry("inserted", 0).containsEntry("environment", "staging");
    }

    @Test
    @DisplayName("backfill: geçersiz ortam adı reddedilir; backfillPreview null sayımı 0 sayar")
    void backfill_invalidEnvAndPreview() {
        assertThatThrownBy(() -> service.backfill("admin", "Prod Ortamı!")).isInstanceOf(IllegalArgumentException.class);
        when(jdbc.queryForObject(anyString(), eq(Integer.class))).thenReturn(null);
        assertThat(service.backfillPreview()).isZero();
        when(jdbc.queryForObject(anyString(), eq(Integer.class))).thenReturn(3);
        assertThat(service.backfillPreview()).isEqualTo(3);
    }

    // ── createManual ──────────────────────────────────────────────────────────────

    private static ManualRequest req(String env, String version, String startedAt, String note) {
        return new ManualRequest(env, version, startedAt, note, "abc12345", 3);
    }

    @Test
    @DisplayName("createManual: geçerli istek → MANUAL satır (v öneki soyulur, ortam küçük harf, tarih normalize)")
    void createManual_ok() {
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        DeploymentHistory d = service.createManual("ops", req(" PROD ", "v20.53.0", "2026-09-01T10:00:00.250Z", "  CR-1234 "));
        assertThat(d.getEnvironment()).isEqualTo("prod");
        assertThat(d.getVersion()).isEqualTo("20.53.0");
        assertThat(d.getStartedAt()).isEqualTo("2026-09-01T10:00:00Z");
        assertThat(d.getNote()).isEqualTo("CR-1234");
        assertThat(d.getSource()).isEqualTo(DeploymentHistory.SOURCE_MANUAL);
        assertThat(d.getCreatedBy()).isEqualTo("ops");
        assertThat(d.getGitCommit()).isEqualTo("abc12345");
        assertThat(d.getHelmRevision()).isEqualTo(3);
    }

    @Test
    @DisplayName("createManual: ortam regex (a-z0-9-, ≤40)")
    void createManual_envValidation() {
        assertThatThrownBy(() -> service.createManual("ops", req("prod ortam", "1.0.0", "2026-09-01T10:00:00Z", "n")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.createManual("ops", req("", "1.0.0", "2026-09-01T10:00:00Z", "n")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.createManual("ops", req("a".repeat(41), "1.0.0", "2026-09-01T10:00:00Z", "n")))
                .isInstanceOf(IllegalArgumentException.class);
        verify(repo, never()).save(any());
    }

    @Test
    @DisplayName("createManual: sürüm X.Y.Z olmalı")
    void createManual_semverValidation() {
        assertThatThrownBy(() -> service.createManual("ops", req("prod", "20.53", "2026-09-01T10:00:00Z", "n")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("20.53");
        assertThatThrownBy(() -> service.createManual("ops", req("prod", null, "2026-09-01T10:00:00Z", "n")))
                .isInstanceOf(IllegalArgumentException.class);
        verify(repo, never()).save(any());
    }

    @Test
    @DisplayName("createManual: tarih ISO-8601 UTC olmalı; gelecek tarih reddedilir")
    void createManual_dateValidation() {
        assertThatThrownBy(() -> service.createManual("ops", req("prod", "1.0.0", "2026-09-01 10:00", "n")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.createManual("ops", req("prod", "1.0.0", null, "n")))
                .isInstanceOf(IllegalArgumentException.class);
        String future = Instant.now().plusSeconds(3600).toString();
        assertThatThrownBy(() -> service.createManual("ops", req("prod", "1.0.0", future, "n")))
                .isInstanceOf(IllegalArgumentException.class);
        verify(repo, never()).save(any());
    }

    @Test
    @DisplayName("createManual: not zorunlu ve ≤500 karakter")
    void createManual_noteValidation() {
        String past = Instant.now().minusSeconds(3600).toString();
        assertThatThrownBy(() -> service.createManual("ops", req("prod", "1.0.0", past, "   ")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.createManual("ops", req("prod", "1.0.0", past, null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.createManual("ops", req("prod", "1.0.0", past, "x".repeat(501))))
                .isInstanceOf(IllegalArgumentException.class);
        verify(repo, never()).save(any());
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        assertThat(service.createManual("ops", req("prod", "1.0.0", past, "x".repeat(500))).getNote()).hasSize(500);
    }

    // ── deleteManual ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("deleteManual: MANUAL satır silinir; STARTUP/BACKFILL → IllegalStateException (409); yok → NoSuchElement (404)")
    void deleteManual() {
        when(repo.findById(1L)).thenReturn(Optional.of(row(1, "2026-09-01T10:00:00Z", "1.0.0", DeploymentHistory.SOURCE_MANUAL)));
        when(repo.findById(2L)).thenReturn(Optional.of(row(2, "2026-09-01T10:00:00Z", "1.0.0", DeploymentHistory.SOURCE_STARTUP)));
        when(repo.findById(3L)).thenReturn(Optional.of(row(3, "2026-09-01T10:00:00Z", null, DeploymentHistory.SOURCE_BACKFILL)));
        when(repo.findById(4L)).thenReturn(Optional.empty());

        assertThat(service.deleteManual(1L).getId()).isEqualTo(1L);
        verify(repo).deleteManual(1L);

        assertThatThrownBy(() -> service.deleteManual(2L)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> service.deleteManual(3L)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> service.deleteManual(4L)).isInstanceOf(NoSuchElementException.class);
        verify(repo, never()).deleteManual(2L);
        verify(repo, never()).deleteManual(3L);
    }

    // ── özet / yayın birleşimi ────────────────────────────────────────────────────

    @Test
    @DisplayName("leadTimeSeconds: indekste yoksa null; varsa dağıtım − yayın (sn)")
    void leadTime() {
        assertThat(service.leadTimeSeconds("1.0.0", "2026-09-01T10:00:00Z")).isNull();
        when(releaseIndex.find("1.0.0")).thenReturn(Optional.of(new ReleaseIndexService.Release(
                "1.0.0", "v1.0.0", "2026-09-01T09:00:00Z", SHA, null, "patch", false, Map.of(), List.of(), false, 0)));
        assertThat(service.leadTimeSeconds("1.0.0", "2026-09-01T10:00:00Z")).isEqualTo(3600L);
        assertThat(service.leadTimeSeconds("1.0.0", "bozuk")).isNull();
        assertThat(service.leadTimeSeconds(null, "2026-09-01T10:00:00Z")).isNull();
    }

    @Test
    @DisplayName("environments(): koşan ortam listede yoksa başa eklenir")
    void environmentsIncludeCurrent() {
        when(repo.findDistinctEnvironments()).thenReturn(List.of("staging"));
        assertThat(service.environments()).containsExactly("prod", "staging");
        when(repo.findDistinctEnvironments()).thenReturn(List.of("prod", "staging"));
        assertThat(service.environments()).containsExactly("prod", "staging");
    }
}
