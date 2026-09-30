package com.sitemonitor.service;

import com.sitemonitor.model.AlertEvent;
import com.sitemonitor.model.Team;
import com.sitemonitor.repository.AlertEventRepository;
import com.sitemonitor.repository.TeamRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * İzleme menüsü rozetleri (2026-09-30): türe göre açık alarm özeti — kapsam Alarm Geçmişi ile aynı, sayı gruplu sorgudan,
 * kırılım/örnek en yeni sayfadan; kapsamsız kullanıcı hiçbir şey görmez.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OpenAlertsSummaryServiceTest {

    @Mock AlertEventRepository alertEventRepo;
    @Mock TeamRepository teamRepo;

    private static AlertEvent ev(long id, String type, String level, Long team, boolean acked) {
        AlertEvent e = new AlertEvent();
        e.setId(id); e.setDomain("t" + id); e.setAlertType(type); e.setAlertLevel(level); e.setTeamId(team);
        e.setAcknowledged(acked); e.setResolved(false); e.setCreatedAt("2026-09-30T13:4" + (id % 10) + ":00");
        return e;
    }

    @Test
    @SuppressWarnings("unchecked")
    @DisplayName("Kapsamlı kullanıcı: sayılar sekmeye toplanır, seviye kırılımı + sahiplenilmemiş + en yeni 5 örnek, takım adı çözülür")
    void scopedUser_summaryPerTab() {
        OpenAlertsSummaryService svc = new OpenAlertsSummaryService(alertEventRepo, teamRepo);
        when(alertEventRepo.countFilteredByType(eq(false), isNull(), isNull(), isNull(), isNull(), isNull(), eq(true), eq(List.of(14L))))
                .thenReturn(List.<Object[]>of(new Object[]{"SCRIPTED_FAIL", 3L}, new Object[]{"SCRIPTED_SLOW", 1L},
                        new Object[]{"HTTP_DOWN", 2L}, new Object[]{"ACCESSIBILITY", 1L}, new Object[]{"BOGUS", 9L}));
        when(alertEventRepo.findFiltered(eq(false), isNull(), isNull(), isNull(), isNull(), isNull(), isNull(), eq(true), eq(List.of(14L)), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(
                        ev(414, "SCRIPTED_FAIL", "WARNING", 14L, false), ev(413, "SCRIPTED_FAIL", "CRITICAL", 14L, true),
                        ev(412, "SCRIPTED_FAIL", "WARNING", 14L, false), ev(411, "SCRIPTED_SLOW", "HIGH", 14L, false),
                        ev(300, "HTTP_DOWN", "CRITICAL", 14L, false), ev(299, "HTTP_DOWN", "WARNING", null, false),
                        ev(298, "ACCESSIBILITY", "WARNING", null, false))));
        Team t = new Team(); t.setId(14L); t.setName("SY-Kurumsal Mimari");
        when(teamRepo.findAllById(any())).thenReturn(List.of(t));

        Map<String, Object> out = svc.build(false, List.of(14L));

        assertThat(out.get("total")).isEqualTo(7L);   // BOGUS (bilinmeyen tür) sayılmaz
        assertThat(out.get("sampled")).isEqualTo(false);
        Map<String, Map<String, Object>> tabs = (Map<String, Map<String, Object>>) out.get("tabs");
        assertThat(tabs.keySet()).containsAll(List.of("http", "ping", "port", "dns", "domain", "keyword", "page", "pagespeed", "scripted"));
        Map<String, Object> scripted = tabs.get("scripted");
        assertThat(scripted.get("count")).isEqualTo(4L);
        assertThat(scripted.get("unacked")).isEqualTo(3L);
        Map<String, Long> levels = (Map<String, Long>) scripted.get("levels");
        assertThat(levels).containsEntry("critical", 1L).containsEntry("high", 1L).containsEntry("warning", 2L);
        List<Map<String, Object>> items = (List<Map<String, Object>>) scripted.get("items");
        assertThat(items).hasSize(4);
        assertThat(items.get(0)).containsEntry("id", 414L).containsEntry("team_name", "SY-Kurumsal Mimari").containsEntry("acknowledged", false);
        assertThat(tabs.get("http").get("count")).isEqualTo(3L);   // HTTP_DOWN + ACCESSIBILITY aynı sekme
        assertThat(tabs.get("ping").get("count")).isEqualTo(0L);
        assertThat(((List<?>) tabs.get("ping").get("items"))).isEmpty();
    }

    @Test
    @DisplayName("Kapsamsız kullanıcı (görüş takımı yok): sorgu atılmaz, her sekme 0")
    void noScope_nothing() {
        OpenAlertsSummaryService svc = new OpenAlertsSummaryService(alertEventRepo, teamRepo);
        Map<String, Object> out = svc.build(false, List.of());
        assertThat(out.get("total")).isEqualTo(0L);
        verifyNoInteractions(alertEventRepo);
    }

    @Test
    @DisplayName("Global görüntüleyici: kapsam süzgeci kapalı (scoped=false); açık alarm yoksa örnek sayfası hiç çekilmez")
    void globalViewer_unscoped_noSampleWhenEmpty() {
        OpenAlertsSummaryService svc = new OpenAlertsSummaryService(alertEventRepo, teamRepo);
        when(alertEventRepo.countFilteredByType(eq(false), isNull(), isNull(), isNull(), isNull(), isNull(), eq(false), anyList()))
                .thenReturn(List.of());
        Map<String, Object> out = svc.build(true, null);
        assertThat(out.get("total")).isEqualTo(0L);
        verify(alertEventRepo, never()).findFiltered(any(), any(), any(), any(), any(), any(), any(), anyBoolean(), anyList(), any(Pageable.class));
    }

    @Test
    @DisplayName("Örneklem: toplam sayfa boyutunu aşarsa sampled=true (kırılım örneklemdir), sayı yine kesin")
    void sampledFlag() {
        OpenAlertsSummaryService svc = new OpenAlertsSummaryService(alertEventRepo, teamRepo);
        when(alertEventRepo.countFilteredByType(eq(false), isNull(), isNull(), isNull(), isNull(), isNull(), eq(false), anyList()))
                .thenReturn(List.<Object[]>of(new Object[]{"PING_DOWN", 500L}));
        when(alertEventRepo.findFiltered(eq(false), isNull(), isNull(), isNull(), isNull(), isNull(), isNull(), eq(false), anyList(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(ev(1, "PING_DOWN", "CRITICAL", 3L, false))));
        Map<String, Object> out = svc.build(true, null);
        assertThat(out.get("total")).isEqualTo(500L);
        assertThat(out.get("sampled")).isEqualTo(true);
    }
}
