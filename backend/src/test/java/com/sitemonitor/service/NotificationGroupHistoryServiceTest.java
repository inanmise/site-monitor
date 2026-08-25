package com.sitemonitor.service;

import com.sitemonitor.model.AuditLog;
import com.sitemonitor.model.NotificationGroup;
import com.sitemonitor.repository.AuditLogRepository;
import com.sitemonitor.repository.NotificationGroupRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.Pageable;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Grup geçmişinin TAKIM KAPSAMI — bir takımın sorumlusu başka takımın grup geçmişini görmemeli.
 *
 * <p>Buradaki asıl zorluk silinmiş gruptur: denetim satırı yalnız grup kimliğini taşır, grup
 * satırı ise artık yoktur — "bu kayıt hangi takımın?" sorusu canlı tablodan cevaplanamaz. Oysa
 * kullanıcının en çok aradığı kayıt tam da silme kaydıdır. Eşleme bu yüzden denetim anlık
 * görüntüsünden kurulur; kurulamıyorsa satır KAPALI tarafa düşer (gösterilmez) ve sayılır.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class NotificationGroupHistoryServiceTest {

    private static final long TEAM_A = 1L;
    private static final long TEAM_B = 2L;

    @Mock AuditLogRepository auditRepo;
    @Mock NotificationGroupRepository groupRepo;

    private NotificationGroupHistoryService service() {
        return new NotificationGroupHistoryService(auditRepo, groupRepo);
    }

    private static AuditLog row(long id, String type, String groupId, String changes) {
        AuditLog a = new AuditLog();
        a.setId(id);
        a.setEventType(type);
        a.setEventTime("2026-08-2" + (id % 10) + "T10:00:00");
        a.setActor("ayse");
        a.setResourceType(NotificationGroupHistoryService.RESOURCE);
        a.setResourceId(groupId);
        a.setChanges(changes);
        return a;
    }

    private static NotificationGroup group(long id, long teamId) {
        NotificationGroup g = new NotificationGroup();
        g.setId(id);
        g.setTeamId(teamId);
        g.setName("Nöbet");
        return g;
    }

    private void audit(AuditLog... rows) {
        when(auditRepo.findByResourceTypeOrderByEventTimeDesc(any(), any(Pageable.class)))
                .thenReturn(List.of(rows));
        when(auditRepo.findByResourceTypeAndResourceIdOrderByEventTimeDesc(any(), any(), any(Pageable.class)))
                .thenReturn(List.of(rows));
    }

    @Test
    @DisplayName("CANLI grup: satır grubun takımına göre süzülür")
    void liveGroup_scopedByTeam() {
        when(groupRepo.findAll()).thenReturn(List.of(group(10L, TEAM_A), group(20L, TEAM_B)));
        audit(row(1, "NOTIFICATION_GROUP_UPDATE", "10", null),
              row(2, "NOTIFICATION_GROUP_UPDATE", "20", null));

        var h = service().history(List.of(TEAM_A), null, 50);

        assertThat(h.items()).extracting(e -> e.row().getId()).containsExactly(1L);
    }

    /**
     * Kullanıcının en çok görmek istediği kayıt budur: "bu grubu kim sildi". Grup satırı
     * gittiği için takım YALNIZ silme kaydındaki anlık görüntüden çözülebilir.
     */
    @Test
    @DisplayName("SİLİNMİŞ grup: takım, silme anlık görüntüsünden çözülür ve satır GÖRÜNÜR")
    void deletedGroup_teamResolvedFromSnapshot() {
        when(groupRepo.findAll()).thenReturn(List.of());
        audit(row(1, "NOTIFICATION_GROUP_DELETE", "10",
                  "{\"name\":\"Nöbet\",\"emails\":\"a@example.com\",\"teamId\":1}"),
              row(2, "NOTIFICATION_GROUP_UPDATE", "10", "{\"name\":{\"from\":\"Eski\",\"to\":\"Nöbet\"}}"));

        var h = service().history(List.of(TEAM_A), null, 50);

        // Silme kaydı takımı çözer; AYNI grubun daha eski güncelleme kaydı da onunla görünür olur.
        assertThat(h.items()).extracting(e -> e.row().getId()).containsExactly(1L, 2L);
        assertThat(h.items()).allSatisfy(e -> assertThat(e.teamId()).isEqualTo(TEAM_A));
    }

    @Test
    @DisplayName("SİLİNMİŞ grup BAŞKA takımınsa gösterilmez")
    void deletedGroup_foreignTeam_hidden() {
        when(groupRepo.findAll()).thenReturn(List.of());
        audit(row(1, "NOTIFICATION_GROUP_DELETE", "20", "{\"name\":\"X\",\"teamId\":2}"));

        var h = service().history(List.of(TEAM_A), null, 50);

        assertThat(h.items()).isEmpty();
        // Takımı ÇÖZÜLDÜ ama kapsam dışı: gizlenen sayısına girmez, bu bir eksiklik değil.
        assertThat(h.hidden()).isZero();
    }

    @Test
    @DisplayName("Takımı ÇÖZÜLEMEYEN satır gizlenir ve SAYILIR (eksik geçmiş tam sanılmasın)")
    void unresolvableTeam_hiddenAndCounted() {
        when(groupRepo.findAll()).thenReturn(List.of());
        // Eski kayıt: Java Map.toString — JSON değil, ayrıştırılamaz.
        audit(row(1, "NOTIFICATION_GROUP_DELETE", "77", "{name=Eski, teamId=1}"));

        var h = service().history(List.of(TEAM_A), null, 50);

        assertThat(h.items()).isEmpty();
        assertThat(h.hidden()).isEqualTo(1);
    }

    @Test
    @DisplayName("Global görücü (kapsam null) her satırı görür — çözülemeyen dahil")
    void globalViewer_seesEverything() {
        when(groupRepo.findAll()).thenReturn(List.of());
        audit(row(1, "NOTIFICATION_GROUP_DELETE", "77", "bozuk"),
              row(2, "NOTIFICATION_GROUP_UPDATE", "20", null));

        var h = service().history(null, null, 50);

        assertThat(h.items()).hasSize(2);
        assertThat(h.hidden()).isZero();
    }

    @Test
    @DisplayName("Kapsamı boş kullanıcı hiçbir şey görmez")
    void emptyScope_seesNothing() {
        when(groupRepo.findAll()).thenReturn(List.of(group(10L, TEAM_A)));
        audit(row(1, "NOTIFICATION_GROUP_UPDATE", "10", null));

        assertThat(service().history(List.of(), null, 50).items()).isEmpty();
    }

    @Test
    @DisplayName("limit aşılırsa kesildiği AÇIKÇA bildirilir")
    void limitExceeded_reportsTruncated() {
        when(groupRepo.findAll()).thenReturn(List.of(group(10L, TEAM_A)));
        audit(row(1, "NOTIFICATION_GROUP_UPDATE", "10", null),
              row(2, "NOTIFICATION_GROUP_UPDATE", "10", null));

        var h = service().history(List.of(TEAM_A), null, 1);

        assertThat(h.items()).hasSize(1);
        assertThat(h.truncated()).isTrue();
    }

    @Test
    @DisplayName("groupId verilirse tek grubun sorgusu kullanılır")
    void groupFilter_usesResourceIdQuery() {
        when(groupRepo.findAll()).thenReturn(List.of(group(10L, TEAM_A)));
        when(auditRepo.findByResourceTypeAndResourceIdOrderByEventTimeDesc(
                eq(NotificationGroupHistoryService.RESOURCE), eq("10"), any(Pageable.class)))
                .thenReturn(List.of(row(1, "NOTIFICATION_GROUP_UPDATE", "10", null)));

        var h = service().history(List.of(TEAM_A), 10L, 50);

        assertThat(h.items()).hasSize(1);
    }

    @Test
    @DisplayName("Takım, FARK biçiminden de okunur (ileride taşıma eklenirse okunur kalsın)")
    void teamFromDiffShape() {
        assertThat(NotificationGroupHistoryService.teamFromChanges(
                "{\"teamId\":{\"from\":1,\"to\":2}}")).isEqualTo(2L);
        assertThat(NotificationGroupHistoryService.teamFromChanges("{\"name\":\"X\"}")).isNull();
        assertThat(NotificationGroupHistoryService.teamFromChanges(null)).isNull();
    }
}
