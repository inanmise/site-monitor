package com.sitemonitor.service;

import com.sitemonitor.model.Team;
import com.sitemonitor.model.UserPushDelivery;
import com.sitemonitor.repository.TeamRepository;
import com.sitemonitor.repository.UserPushDeliveryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Webhook Push Gönderim Logu sorgu servisi (2026-09-19): sınıflandırma, kapsam (takım + kendi satırı), özet, detay (batch), yeniden kuyruk. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@SuppressWarnings("unchecked")
class PushLogQueryServiceTest {

    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);
    private static final Instant NOW = Instant.parse("2026-09-19T12:00:00Z");

    @Mock UserPushDeliveryRepository repo;
    @Mock TeamRepository teamRepo;
    @Mock UserPushService userPushService;
    PushLogQueryService svc;

    private static String at(long minutesAgo) { return ISO.format(NOW.minus(Duration.ofMinutes(minutesAgo))); }

    /** id, alertEventId, trigger, monitorType, monitorId, monitorName, teamId, alertLevel, username, displayName, title, status, httpStatus, error, attempts, createdAt, sentAt, batchId, notificationId */
    private static Object[] row(long id, Long team, String user, String monitor, String level, String status, Integer http, String error, String created, String sent, String batch) {
        return new Object[]{id, 10L, "INITIAL", "HTTP", 1L, monitor, team, level, user, user + " Adı", "Site Monitor", status, http, error, http == null ? 0 : 1, created, sent, batch, sent == null ? null : "n-" + id};
    }

    @BeforeEach
    void setUp() {
        svc = new PushLogQueryService(repo, teamRepo, userPushService, null);
        Team a = new Team(); a.setId(1L); a.setName("Takım A");
        Team b = new Team(); b.setId(2L); b.setName("Takım B");
        when(teamRepo.findAll()).thenReturn(List.of(a, b));
        when(repo.findWindowRows(anyString(), anyString())).thenReturn(List.of(
                row(1, 1L, "u1", "api", "CRITICAL", "SENT", 200, null, at(6), at(5), "b1"),
                row(2, 1L, "u2", "api", "CRITICAL", "FAILED", 500, "Internal error", at(6), null, "b1"),
                row(3, 2L, "u3", "gw", "HIGH", "FAILED", null, "connect timed out", at(4), null, "b2"),
                row(4, 2L, "u3", "gw", "HIGH", "PENDING", null, null, at(3), null, "b3"),
                row(5, null, "u9", "test", "WARNING", "SKIPPED_USER_OPT_OUT", null, null, at(2), null, null),
                row(6, 2L, "u1", "gw", "HIGH", "RATE_LIMITED", null, null, at(1), null, "b4")));
        when(userPushService.enabled()).thenReturn(true);
    }

    private PushLogQueryService.Filter f(String status, Long team, String user, String q) {
        return new PushLogQueryService.Filter(at(60 * 48), null, status, null, team, user, null, null, null, q, null);
    }

    @Test
    @DisplayName("kind: SENT/FAILED/PENDING/BLOCKED(devre/hız)/SKIPPED; hata sınıfı HTTP koduna göre, kodsuz metne göre")
    void classify() {
        assertThat(PushLogQueryService.kindOf("CIRCUIT_OPEN")).isEqualTo("BLOCKED");
        assertThat(PushLogQueryService.kindOf("RATE_LIMITED")).isEqualTo("BLOCKED");
        assertThat(PushLogQueryService.kindOf("SKIPPED_USER_OPT_OUT")).isEqualTo("SKIPPED");
        assertThat(PushLogQueryService.kindOf("PENDING")).isEqualTo("PENDING");
        assertThat(PushLogQueryService.kindOf("weird")).isEqualTo("UNKNOWN");
        assertThat(PushLogQueryService.classifyError("FAILED", 401, null)).isEqualTo("AUTH");
        assertThat(PushLogQueryService.classifyError("FAILED", 404, null)).isEqualTo("NOT_FOUND");
        assertThat(PushLogQueryService.classifyError("FAILED", 429, null)).isEqualTo("RATE");
        assertThat(PushLogQueryService.classifyError("FAILED", 503, null)).isEqualTo("SERVER");
        assertThat(PushLogQueryService.classifyError("FAILED", 422, null)).isEqualTo("CLIENT");
        assertThat(PushLogQueryService.classifyError("FAILED", null, "Read timed out")).isEqualTo("TIMEOUT");
        assertThat(PushLogQueryService.classifyError("FAILED", null, "Connection refused")).isEqualTo("CONNECT");
        assertThat(PushLogQueryService.classifyError("FAILED", null, "URL ayarlanmamış")).isEqualTo("CONFIG");
        assertThat(PushLogQueryService.classifyError("FAILED", null, "gövde kurulamadı: x")).isEqualTo("CONFIG");
        assertThat(PushLogQueryService.classifyError("FAILED", null, "something odd")).isEqualTo("OTHER");
        assertThat(PushLogQueryService.classifyError("SENT", 200, null)).isNull();
    }

    @Test
    @DisplayName("arama + kapsam: global 6 satır yeni üstte (at = sentAt ?? createdAt); takım 1 kullanıcısı u3 → takım 1 satırları + kendi satırları")
    void searchAndScope() {
        Map<String, Object> r = svc.search(f(null, null, null, null), PushLogQueryService.Scope.all(), 0, 25, NOW);
        assertThat(r).containsEntry("total", 6);
        List<Map<String, Object>> items = (List<Map<String, Object>>) r.get("items");
        assertThat(items).extracting(m -> m.get("id")).containsExactly(6L, 5L, 4L, 3L, 1L, 2L);   // 1: sentAt(5dk) > 2: createdAt(6dk)
        assertThat(items.get(0)).containsEntry("kind", "BLOCKED").containsEntry("team_name", "Takım B");
        assertThat(items.get(5)).containsEntry("error_class", "SERVER").containsEntry("http_status", 500);

        PushLogQueryService.Scope scoped = new PushLogQueryService.Scope(false, Set.of(1L), "U3");
        List<Map<String, Object>> s = (List<Map<String, Object>>) svc.search(f(null, null, null, null), scoped, 0, 25, NOW).get("items");
        assertThat(s).extracting(m -> m.get("id")).containsExactly(4L, 3L, 1L, 2L);
        assertThat(svc.search(f("FAILED", null, null, null), PushLogQueryService.Scope.all(), 0, 25, NOW)).containsEntry("total", 2);
        assertThat(svc.search(f(null, 2L, null, null), PushLogQueryService.Scope.all(), 0, 25, NOW)).containsEntry("total", 3);
        assertThat(svc.search(f(null, null, "u1", null), PushLogQueryService.Scope.all(), 0, 25, NOW)).containsEntry("total", 2);
        assertThat(svc.search(f(null, null, null, "timed out"), PushLogQueryService.Scope.all(), 0, 25, NOW)).containsEntry("total", 1);
    }

    @Test
    @DisplayName("özet: KPI (1 sent / 2 failed / 1 pending / 1 blocked / 1 skipped, oran %33,3), saatlik kova, takım/alıcı/izleme/seviye kırılımı, hata sınıfları")
    void summary() {
        Map<String, Object> s = svc.summary(f(null, null, null, null), PushLogQueryService.Scope.all(), NOW);
        Map<String, Object> kpi = (Map<String, Object>) s.get("kpi");
        assertThat(kpi).containsEntry("total", 6).containsEntry("sent", 1).containsEntry("failed", 2).containsEntry("pending", 1)
                .containsEntry("blocked", 1).containsEntry("skipped", 1).containsEntry("success_rate", 33.3).containsEntry("failed_users", 2);
        assertThat(s).containsEntry("granularity", "hour");
        List<Map<String, Object>> tl = (List<Map<String, Object>>) s.get("timeline");
        assertThat(tl.get(tl.size() - 2)).containsEntry("bucket", "2026-09-19T11:00:00").containsEntry("sent", 1).containsEntry("failed", 2).containsEntry("pending", 2).containsEntry("skipped", 1);
        List<Map<String, Object>> teams = (List<Map<String, Object>>) s.get("teams");
        assertThat(teams.get(0)).containsEntry("team_name", "Takım B").containsEntry("failed", 1).containsEntry("total", 3);   // eşit failed → toplam büyük üstte
        assertThat(teams.get(1)).containsEntry("team_name", "Takım A").containsEntry("failed", 1).containsEntry("total", 2).containsEntry("success_rate", 50.0);
        assertThat(((List<Map<String, Object>>) s.get("top_users")).get(0)).containsEntry("username", "u3").containsEntry("failed", 1);
        assertThat(((List<Map<String, Object>>) s.get("top_monitors"))).extracting(m -> m.get("monitor_name")).containsExactly("gw", "api", "test");
        assertThat(((List<Map<String, Object>>) s.get("levels"))).extracting(m -> m.get("level")).contains("CRITICAL", "HIGH", "WARNING");
        assertThat(((List<Map<String, Object>>) s.get("error_classes"))).extracting(m -> m.get("error_class")).containsExactlyInAnyOrder("SERVER", "TIMEOUT");
    }

    @Test
    @DisplayName("detay: gövde + ham yanıt + aynı batch'in satırları (kapsam süzgeçli); kapsam dışı null")
    void detail() {
        UserPushDelivery d = new UserPushDelivery(); d.setId(2L); d.setTeamId(1L); d.setUsername("u2"); d.setStatus("FAILED"); d.setHttpStatus(500);
        d.setMessage("msg"); d.setRawResponse("{\"error\":1}"); d.setBatchId("b1"); d.setCreatedAt(at(6));
        UserPushDelivery peer = new UserPushDelivery(); peer.setId(1L); peer.setTeamId(1L); peer.setUsername("u1"); peer.setStatus("SENT"); peer.setBatchId("b1"); peer.setCreatedAt(at(6)); peer.setSentAt(at(5));
        when(repo.findById(2L)).thenReturn(Optional.of(d));
        when(repo.findByBatchIdOrderByIdAsc("b1")).thenReturn(List.of(peer, d));

        Map<String, Object> out = svc.detail(2L, PushLogQueryService.Scope.all());
        assertThat(out).containsEntry("message", "msg").containsEntry("raw_response", "{\"error\":1}").containsEntry("error_class", "SERVER");
        assertThat((List<?>) out.get("batch")).hasSize(2);
        assertThat(svc.detail(2L, new PushLogQueryService.Scope(false, Set.of(2L), "u9"))).isNull();
    }

    @Test
    @DisplayName("yeniden kuyruk: yalnız FAILED/BLOCKED; kanal kapalıysa CHANNEL_DISABLED; kapsam dışı NOT_FOUND; başarıda UserPushService.requeue")
    void requeue() {
        UserPushDelivery failed = new UserPushDelivery(); failed.setId(2L); failed.setTeamId(1L); failed.setUsername("u2"); failed.setStatus("FAILED");
        UserPushDelivery sent = new UserPushDelivery(); sent.setId(1L); sent.setTeamId(1L); sent.setUsername("u1"); sent.setStatus("SENT");
        UserPushDelivery blocked = new UserPushDelivery(); blocked.setId(6L); blocked.setTeamId(2L); blocked.setUsername("u1"); blocked.setStatus("RATE_LIMITED");
        when(repo.findById(2L)).thenReturn(Optional.of(failed));
        when(repo.findById(1L)).thenReturn(Optional.of(sent));
        when(repo.findById(6L)).thenReturn(Optional.of(blocked));

        assertThat(svc.requeue(1L, PushLogQueryService.Scope.all(), "admin").reason()).isEqualTo("NOT_RETRYABLE");
        assertThat(svc.requeue(99L, PushLogQueryService.Scope.all(), "admin").reason()).isEqualTo("NOT_FOUND");
        assertThat(svc.requeue(2L, new PushLogQueryService.Scope(false, Set.of(2L), "zz"), "admin").reason()).isEqualTo("NOT_FOUND");
        when(userPushService.enabled()).thenReturn(false);
        assertThat(svc.requeue(2L, PushLogQueryService.Scope.all(), "admin").reason()).isEqualTo("CHANNEL_DISABLED");
        verify(userPushService, never()).requeue(any());
        when(userPushService.enabled()).thenReturn(true);
        assertThat(svc.requeue(2L, PushLogQueryService.Scope.all(), "admin").ok()).isTrue();
        assertThat(svc.requeue(6L, PushLogQueryService.Scope.all(), "admin").ok()).isTrue();
        verify(userPushService).requeue(failed);
        verify(userPushService).requeue(blocked);
    }
}
