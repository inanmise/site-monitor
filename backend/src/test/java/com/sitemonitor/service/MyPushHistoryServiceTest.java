package com.sitemonitor.service;

import com.sitemonitor.model.Team;
import com.sitemonitor.model.UserPushDelivery;
import com.sitemonitor.repository.TeamRepository;
import com.sitemonitor.repository.UserPushDeliveryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.PageImpl;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * "Push geçmişim" satır biçimi ve SIZINTI kapısı (2026-10-04, onaylı öneri 3): kişi yalnız kendi push'unu ve üyesi olduğu
 * takımın kararlarını görür; görüş kapsamından çıkmış alarmın hedefi / kimliği / metni gizlenir ("başka takımın alarmı").
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MyPushHistoryServiceTest {

    @Mock UserPushDeliveryRepository repo;
    @Mock TeamRepository teamRepo;
    MyPushHistoryService service;
    final List<UserPushDelivery> page = new ArrayList<>();
    static final Instant NOW = Instant.parse("2026-10-04T12:00:00Z");

    @BeforeEach
    void setUp() {
        service = new MyPushHistoryService(repo, teamRepo);
        when(repo.myHistory(anyString(), any(), anyString(), anyString(), any())).thenAnswer(i -> new PageImpl<>(page));
        when(repo.myStatusCounts(anyString(), anyString())).thenReturn(List.of());
        when(repo.teamDecisionCounts(any(), anyString())).thenReturn(List.of());
        Team t3 = new Team(); t3.setId(3L); t3.setName("Takım Üç");
        Team t9 = new Team(); t9.setId(9L); t9.setName("Takım Dokuz");
        when(teamRepo.findAllById(any())).thenReturn(List.of(t3, t9));
    }

    private UserPushDelivery row(long id, String user, String status, String trigger, Long team, String name, String msg) {
        UserPushDelivery d = new UserPushDelivery();
        d.setId(id); d.setUsername(user); d.setStatus(status); d.setTrigger(trigger); d.setTeamId(team);
        d.setMonitorName(name); d.setMessage(msg); d.setAlertEventId(100 + id); d.setAlertLevel("HIGH");
        d.setCreatedAt("2026-10-04T10:00:00");
        page.add(d);
        return d;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> rows(Map<String, Object> resp) {
        return (List<Map<String, Object>>) resp.get("data");
    }

    @Test
    @DisplayName("kendi push'u (görünen takım): hedef, alarm, takım adı ve GERÇEKTEN gönderilen metin; neden yalnız gönderilmeyende")
    void ownVisibleRow() {
        row(1, "N00001", "SENT", "OPEN", 3L, "shop.example.com", "KRİTİK: shop.example.com yanıt vermiyor");
        row(2, "N00001", "RATE_LIMITED", "OPEN", 3L, "api.example.com", "YÜKSEK: api").setOverflowSummaryId(50L);
        Map<String, Object> r = service.history("N00001", List.of(3L), t -> false, 7, "all", 0, 25, NOW);
        Map<String, Object> sent = rows(r).get(0), limited = rows(r).get(1);
        assertThat(sent).containsEntry("target", "shop.example.com").containsEntry("alert_event_id", 101L)
                .containsEntry("team_name", "Takım Üç").containsEntry("message", "KRİTİK: shop.example.com yanıt vermiyor")
                .containsEntry("kind", "sent").containsEntry("scope", "own");
        assertThat(sent.get("reason")).isNull();
        assertThat(limited).containsEntry("reason", "RATE_LIMITED").containsEntry("kind", "not_sent").containsEntry("summarized_into", 50L);
        assertThat(r).containsEntry("login_code_note", true);
    }

    @Test
    @DisplayName("SIZINTI KAPISI: kendi satırındaki alarm artık görüş kapsamında değilse hedef / alarm kimliği / takım / metin GİZLİ, 'other_team' etiketi")
    void ownRow_outOfScope_isRedacted() {
        row(1, "N00001", "SENT", "OPEN", 9L, "secret-target.example.com", "KRİTİK: secret-target.example.com yanıt vermiyor");
        Map<String, Object> m = rows(service.history("N00001", List.of(3L), t -> t == 3L, 7, "all", 0, 25, NOW)).get(0);
        assertThat(m).containsEntry("visible", false).containsEntry("scope", "other_team").containsEntry("message_hidden", true);
        assertThat(m).doesNotContainKeys("target", "alert_event_id", "team_id", "team_name");
        assertThat(m.get("message")).isNull();
        assertThat(m.values()).noneMatch(v -> String.valueOf(v).contains("secret-target"));
    }

    @Test
    @DisplayName("görüş kapsamı (müdür) üyelik olmasa da kendi satırını görünür kılar; karar satırı yalnız ÜYELİKLE görünür")
    void viewScope_vs_membership() {
        row(1, "N00001", "SENT", "OPEN", 9L, "a.example.com", "m");
        row(2, "-", "SKIPPED_STORM", "OPEN", 3L, "b.example.com", null);
        List<Map<String, Object>> out = rows(service.history("N00001", List.of(3L), t -> t == 9L, 7, "all", 0, 25, NOW));
        assertThat(out.get(0)).containsEntry("visible", true).containsEntry("target", "a.example.com");
        assertThat(out.get(1)).containsEntry("team_decision", true).containsEntry("scope", "team")
                .containsEntry("target", "b.example.com").containsEntry("reason", "SKIPPED_STORM");
        assertThat(out.get(1).get("message")).as("karar satırında metin yok").isNull();
    }

    @Test
    @DisplayName("sorgu: kanonik kullanıcı adı + YALNIZ üyelik takımları; takımsız kişi -1 nöbetçisi (başka takımın kararı gelemez)")
    @SuppressWarnings("unchecked")
    void query_usesMembershipOnly() {
        service.history("N00001", List.of(), t -> true, 30, "not_sent", 2, 10, NOW);
        ArgumentCaptor<Collection<Long>> teams = ArgumentCaptor.forClass(Collection.class);
        verify(repo).myHistory(eq("N00001"), teams.capture(), eq("2026-09-04T12:00:00"), eq("not_sent"), any());
        assertThat(teams.getValue()).containsExactly(MyPushHistoryService.NO_TEAM);
    }

    @Test
    @DisplayName("KPI: gönderilen / kuyrukta / gönderilmeyen (nedene göre, çoktan aza) / özetlenen / takım kararları")
    void kpis() {
        List<Object[]> own = new ArrayList<>();
        own.add(new Object[]{"SENT", 5L});
        own.add(new Object[]{"PENDING", 1L});
        own.add(new Object[]{"RATE_LIMITED", 4L});
        own.add(new Object[]{"SKIPPED_USER_SNOOZE", 7L});
        when(repo.myStatusCounts(anyString(), anyString())).thenReturn(own);
        List<Object[]> team = new ArrayList<>();
        team.add(new Object[]{"SKIPPED_STORM", 2L});
        when(repo.teamDecisionCounts(any(), anyString())).thenReturn(team);
        when(repo.mySummarizedCount(anyString(), anyString())).thenReturn(3L);
        @SuppressWarnings("unchecked")
        Map<String, Object> k = (Map<String, Object>) service.history("N00001", List.of(3L), t -> true, 7, "all", 0, 25, NOW).get("kpis");
        assertThat(k).containsEntry("sent", 5L).containsEntry("pending", 1L).containsEntry("not_sent", 11L)
                .containsEntry("summarized", 3L).containsEntry("team_decisions", 2L);
        @SuppressWarnings("unchecked")
        Map<String, Long> by = (Map<String, Long>) k.get("not_sent_by_reason");
        assertThat(by.keySet()).containsExactly("SKIPPED_USER_SNOOZE", "RATE_LIMITED");
    }

    @Test
    @DisplayName("özet satırı kapsadığı satır sayısını taşır")
    void summaryRow_count() {
        row(60, "N00001", "SENT", UserPushOverflowService.TRIGGER, null, "Saat tavanı özeti · 4 bildirim", "SiteMonitor: ...");
        when(repo.countByOverflowSummaryId(60L)).thenReturn(4L);
        Map<String, Object> m = rows(service.history("N00001", Set.of(3L), t -> true, 7, "all", 0, 25, NOW)).get(0);
        assertThat(m).containsEntry("summarized_count", 4L).containsEntry("visible", true);
    }
}
