package com.sitemonitor.service;

import com.sitemonitor.model.AuditLog;
import com.sitemonitor.model.EscalationContact;
import com.sitemonitor.repository.AuditLogRepository;
import com.sitemonitor.repository.EscalationContactRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Çapraz yönetim değişiklik akışı (2026-11-13): alanların olay türleri ayrık, sınırsız görüntüleyicide veritabanı
 * sayfası, kapsamlı görüntüleyicide pencere + alan başına takım süzgeci (takımı çözülemeyen satır kapalı tarafa düşer).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AdminActivityFeedServiceTest {

    @Mock AuditLogRepository auditRepo;
    @Mock EscalationContactRepository contactRepo;
    @Mock NotificationGroupHistoryService groupHistory;
    @InjectMocks AdminActivityFeedService service;

    private static AuditLog row(long id, String type, String resource, String rid, String changes) {
        AuditLog r = new AuditLog();
        r.setId(id); r.setEventType(type); r.setResourceType(resource); r.setResourceId(rid); r.setChanges(changes);
        r.setEventTime(String.format("2026-11-1%dT10:00:00", id % 10));
        return r;
    }

    private static AdminActivityFeedService.Access acc(String area, Collection<Long> teams) {
        return new AdminActivityFeedService.Access(area, teams);
    }

    @Test
    @DisplayName("Her alanın olay türleri ayrık ve tam; sekme geçmişinin beyaz listeleri aynen kullanılır")
    void areasAreDisjointAndComplete() {
        assertThat(AdminActivityFeedService.DEFS.keySet()).containsExactlyElementsOf(AdminActivityFeedService.AREAS);
        Set<String> seen = new HashSet<>();
        for (var def : AdminActivityFeedService.DEFS.values()) {
            for (String t : def.types()) {
                assertThat(seen.add(t)).as("olay türü iki alanda: " + t).isTrue();
                assertThat(AdminActivityFeedService.areaOf(t)).isEqualTo(def.key());
            }
        }
        assertThat(AdminActivityFeedService.DEFS.get("users").types()).isEqualTo(AdminHistoryService.EVENT_TYPES.get("USER"));
        assertThat(AdminActivityFeedService.areaOf("LOGIN")).isNull();
        assertThat(AdminActivityFeedService.areaOf(null)).isNull();
    }

    @Test
    @DisplayName("İzinli alan yoksa boş akış — sorgu YOK")
    void noAccessNoQuery() {
        var f = service.feed(List.of(), null, "", 0, 25);
        assertThat(f.items()).isEmpty();
        assertThat(f.total()).isZero();
        verifyNoInteractions(auditRepo, contactRepo, groupHistory);
    }

    @Test
    @DisplayName("Sınırsız (global görücü): seçili alanların tür birleşimi + zaman sınırı, sayfa/toplam veritabanından")
    @SuppressWarnings("unchecked")
    void unrestrictedUsesDatabasePage() {
        when(auditRepo.findAdminFeed(anyCollection(), anyString(), any(Pageable.class))).thenReturn(new PageImpl<>(List.of(
                row(1, "TEAM_UPDATE", "TEAM", "5", null),
                row(2, "ALERT_FORWARD_CREATE", "TEAM", "7", null),
                row(3, "THRESHOLD_UPDATE", "ALERT_THRESHOLD", "1", null)), Pageable.ofSize(3), 9));
        var f = service.feed(List.of(acc("teams", null), acc("forwards", null), acc("thresholds", null)),
                null, "2026-10-14T00:00:00", 1, 3);
        assertThat(f.total()).isEqualTo(9);
        assertThat(f.totalPages()).isEqualTo(3);
        assertThat(f.items()).extracting(AdminActivityFeedService.Entry::area).containsExactly("teams", "forwards", "thresholds");
        assertThat(f.items()).extracting(AdminActivityFeedService.Entry::teamId).containsExactly(5L, 7L, null);

        ArgumentCaptor<Collection<String>> types = ArgumentCaptor.forClass(Collection.class);
        ArgumentCaptor<Pageable> pg = ArgumentCaptor.forClass(Pageable.class);
        verify(auditRepo).findAdminFeed(types.capture(), eq("2026-10-14T00:00:00"), pg.capture());
        assertThat(types.getValue()).contains("TEAM_UPDATE", "ALERT_FORWARD_DELETE", "THRESHOLD_CREATE")
                .doesNotContain("USER_UPDATE", "CONTACT_CREATE", "LOGIN");
        assertThat(pg.getValue().getPageNumber()).isEqualTo(1);
        assertThat(pg.getValue().getPageSize()).isEqualTo(3);
        verify(auditRepo, never()).findAdminFeedByActor(anyCollection(), anyString(), anyString(), any(Pageable.class));
    }

    @Test
    @DisplayName("Kişi süzgeci küçük harfe çevrilip kişi sorgusuna gider")
    void actorFilterLowercased() {
        when(auditRepo.findAdminFeedByActor(anyCollection(), anyString(), anyString(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));
        service.feed(List.of(acc("teams", null)), "  Ayse.Yilmaz ", "", 0, 25);
        verify(auditRepo).findAdminFeedByActor(anyCollection(), eq(""), eq("ayse.yilmaz"), any(Pageable.class));
    }

    @Test
    @DisplayName("Kapsamlı: alan başına takım süzgeci; takımı çözülemeyen satır gizlenir (sayılır); izinsiz alan atlanır")
    void scopedFiltersPerArea() {
        EscalationContact c = new EscalationContact();
        c.setId(30L); c.setTeamId(1L);
        when(contactRepo.findAll()).thenReturn(List.of(c));
        when(groupHistory.rowTeams(anyList())).thenReturn(Map.of(
                5L, new NotificationGroupHistoryService.RowTeams(1L, java.util.Set.of(1L)),
                6L, new NotificationGroupHistoryService.RowTeams(2L, java.util.Set.of(2L))));
        when(auditRepo.findAdminFeed(anyCollection(), anyString(), any(Pageable.class))).thenReturn(new PageImpl<>(List.of(
                row(1, "TEAM_UPDATE", "TEAM", "1", null),                          // takım 1 → görünür
                row(2, "TEAM_UPDATE", "TEAM", "2", null),                          // takım 2 → kapsam dışı
                row(3, "CONTACT_UPDATE", "ESCALATION_CONTACT", "30", null),        // canlı kişi → takım 1
                row(4, "CONTACT_DELETE", "ESCALATION_CONTACT", "99", null),        // silinmiş, izi yok → gizli
                row(5, "NOTIFICATION_GROUP_UPDATE", "NOTIFICATION_GROUP", "40", null),   // grup → takım 1
                row(6, "NOTIFICATION_GROUP_DELETE", "NOTIFICATION_GROUP", "41", null),   // grup → takım 2
                row(7, "TEAM_ACCESS_GRANT_CREATE", "TEAM", "1", null),             // paylaşım: yönetim kapsamı 3
                row(8, "TEAM_UPDATE", "USER", "1", null))));                       // kaynak türü uymuyor → gizli
        var f = service.feed(List.of(
                        acc("teams", List.of(1L)), acc("contacts", List.of(1L)), acc("groups", List.of(1L)),
                        acc("sharing", List.of(3L))),
                null, "", 0, 25);
        assertThat(f.items()).extracting(e -> e.row().getId()).containsExactly(1L, 3L, 5L);
        assertThat(f.items()).extracting(AdminActivityFeedService.Entry::teamId).containsExactly(1L, 1L, 1L);
        assertThat(f.total()).isEqualTo(3);
        assertThat(f.hidden()).isEqualTo(2);           // silinmiş kişi + kaynak türü uymayan
        assertThat(f.truncated()).isFalse();
    }

    @Test
    @DisplayName("Kapsamlı: toplu taşınan grubun taşıma satırı İKİ takıma görünür (gösterim yeni takım); eski satır eski takımda")
    void groupMoveRowVisibleToBothTeams() {
        when(groupHistory.rowTeams(anyList())).thenReturn(Map.of(
                1L, new NotificationGroupHistoryService.RowTeams(2L, java.util.Set.of(1L, 2L)),   // taşıma A → B
                2L, new NotificationGroupHistoryService.RowTeams(1L, java.util.Set.of(1L))));     // taşımadan önceki düzenleme
        when(auditRepo.findAdminFeed(anyCollection(), anyString(), any(Pageable.class))).thenReturn(new PageImpl<>(List.of(
                row(1, "NOTIFICATION_GROUP_UPDATE", "NOTIFICATION_GROUP", "40", null),
                row(2, "NOTIFICATION_GROUP_UPDATE", "NOTIFICATION_GROUP", "40", null))));
        var a = service.feed(List.of(acc("groups", List.of(1L))), null, "", 0, 25);
        assertThat(a.items()).extracting(e -> e.row().getId()).containsExactly(1L, 2L);
        var b = service.feed(List.of(acc("groups", List.of(2L))), null, "", 0, 25);
        assertThat(b.items()).extracting(e -> e.row().getId()).containsExactly(1L);
        assertThat(b.items().get(0).teamId()).isEqualTo(2L);
    }

    @Test
    @DisplayName("Kapsamlı: istenmeyen alanın satırı akışa girmez; bellekte sayfalama + pencere dolunca truncated")
    void scopedPagingAndTruncation() {
        List<AuditLog> rows = new ArrayList<>();
        for (int i = 0; i < AdminActivityFeedService.WINDOW_MAX; i++) {
            rows.add(row(i + 1, i % 2 == 0 ? "TEAM_UPDATE" : "ONCALL_SCHEDULE_SAVE", "TEAM", "1", null));
        }
        when(auditRepo.findAdminFeed(anyCollection(), anyString(), any(Pageable.class))).thenReturn(new PageImpl<>(rows));
        var f = service.feed(List.of(acc("teams", List.of(1L))), null, "", 1, 10);
        assertThat(f.total()).isEqualTo(AdminActivityFeedService.WINDOW_MAX / 2);
        assertThat(f.items()).hasSize(10).allMatch(e -> "teams".equals(e.area()));
        assertThat(f.items().get(0).row().getId()).isEqualTo(21L);   // 2. sayfa: TEAM_UPDATE'lerin 11.'si
        assertThat(f.truncated()).isTrue();
        verifyNoInteractions(contactRepo, groupHistory);              // gereksiz tablo okuması yok
    }
    @Test
    @DisplayName("2026-11-13 hata taraması: taşınan kişinin taşıma satırı İKİ takıma görünür; taşımadan önceki satır eski takımda kalır")
    void contactMoveRowVisibleToBothTeams() {
        EscalationContact c = new EscalationContact();
        c.setId(30L); c.setTeamId(2L);                                            // kişi ŞİMDİ B'de
        when(contactRepo.findAll()).thenReturn(List.of(c));
        AuditLog move = row(2, "CONTACT_UPDATE", "ESCALATION_CONTACT", "30", "{\"teamId\":{\"from\":1,\"to\":2}}");
        AuditLog before = row(1, "CONTACT_UPDATE", "ESCALATION_CONTACT", "30", "{\"role\":{\"from\":\"PO\",\"to\":\"TECH\"}}");
        when(auditRepo.findAdminFeed(anyCollection(), anyString(), any(Pageable.class))).thenReturn(new PageImpl<>(List.of(move, before)));
        when(auditRepo.findByResourceTypeAndEventTypeAndResourceIdIn(eq("ESCALATION_CONTACT"), eq("CONTACT_UPDATE"), anyCollection(), any(Pageable.class)))
                .thenReturn(List.of(move, before));
        var a = service.feed(List.of(acc("contacts", List.of(1L))), null, "", 0, 25);
        assertThat(a.items()).extracting(e -> e.row().getId()).containsExactly(2L, 1L);
        assertThat(a.items()).extracting(AdminActivityFeedService.Entry::teamId).containsExactly(2L, 1L);
        var b = service.feed(List.of(acc("contacts", List.of(2L))), null, "", 0, 25);
        assertThat(b.items()).extracting(e -> e.row().getId()).containsExactly(2L);
    }
}
