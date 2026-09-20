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

import java.util.Collection;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Yönetim Paneli değişiklik geçmişi (2026-09-20): olay türü beyaz listesi (giriş kayıtları GİRMEZ), sayfalama,
 * takım kapsamı kapalı tarafa düşer; silinmiş kişinin takımı anlık görüntüden çözülür.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AdminHistoryServiceTest {

    @Mock AuditLogRepository auditRepo;
    @Mock EscalationContactRepository contactRepo;
    @InjectMocks AdminHistoryService service;

    private static AuditLog row(long id, String type, String resource, String rid, String changes) {
        AuditLog r = new AuditLog();
        r.setId(id); r.setEventType(type); r.setResourceType(resource); r.setResourceId(rid); r.setChanges(changes);
        r.setEventTime("2026-09-20T10:0" + (id % 10) + ":00");
        return r;
    }

    @Test
    @DisplayName("USER: yalnız yönetimsel olay türleri sorulur (LOGIN_*/LOGOUT beyaz listede DEĞİL); sayfa/toplam DB'den")
    @SuppressWarnings("unchecked")
    void userWhitelistAndPaging() {
        when(auditRepo.findByResourceTypeAndEventTypeIn(eq("USER"), anyCollection(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(row(1, "USER_UPDATE", "USER", "42", null)), Pageable.ofSize(25), 60));
        var h = service.history("USER", null, null, null, 2, 25);
        assertThat(h.items()).hasSize(1);
        assertThat(h.total()).isEqualTo(60);
        assertThat(h.page()).isEqualTo(2);
        assertThat(h.totalPages()).isEqualTo(3);
        ArgumentCaptor<Collection<String>> types = ArgumentCaptor.forClass(Collection.class);
        verify(auditRepo).findByResourceTypeAndEventTypeIn(eq("USER"), types.capture(), any(Pageable.class));
        assertThat(types.getValue()).contains("USER_UPDATE", "USER_PASSWORD_AUTO_RESET", "ACCOUNT_LOCKED")
                .doesNotContain("LOGIN_SUCCESS", "LOGIN_FAILED", "LOGOUT", "TOUR_COMPLETED", "USER_PUSH_OPT_OUT");
    }

    @Test
    @DisplayName("types süzgeci: beyaz liste içindekiler geçer, dışındakiler yok sayılır; hepsi dışıysa tüm liste")
    @SuppressWarnings("unchecked")
    void typesFilter() {
        when(auditRepo.findByResourceTypeAndEventTypeIn(eq("TEAM"), anyCollection(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));
        service.history("TEAM", null, List.of("TEAM_UPDATE", "LOGIN_SUCCESS"), null, 0, 25);
        ArgumentCaptor<Collection<String>> types = ArgumentCaptor.forClass(Collection.class);
        verify(auditRepo).findByResourceTypeAndEventTypeIn(eq("TEAM"), types.capture(), any(Pageable.class));
        assertThat(types.getValue()).containsExactly("TEAM_UPDATE");
    }

    @Test
    @DisplayName("TEAM kapsamlı: pencere okunur, yalnız kendi takımı, bellekte sayfalanır; sayfa boyutu tavanlı")
    void teamScope() {
        when(auditRepo.findByResourceTypeAndEventTypeIn(eq("TEAM"), anyCollection(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(row(1, "TEAM_UPDATE", "TEAM", "7", null), row(2, "TEAM_UPDATE", "TEAM", "9", null),
                        row(3, "TEAM_UPDATE", "TEAM", "7", null))));
        var h = service.history("TEAM", null, null, Set.of(7L), 0, 1);
        assertThat(h.items()).hasSize(1);
        assertThat(h.total()).isEqualTo(2);
        assertThat(h.totalPages()).isEqualTo(2);
        assertThat(h.items().get(0).teamId()).isEqualTo(7L);
        var page2 = service.history("TEAM", null, null, Set.of(7L), 1, 1);
        assertThat(page2.items()).extracting(e -> e.row().getId()).containsExactly(3L);
    }

    @Test
    @DisplayName("ESCALATION_CONTACT kapsamlı: canlı satırın takımı, silinmişte anlık görüntüdeki teamId; çözülemeyen gizlenir ve sayılır")
    void contactScopeResolvesDeletedFromSnapshot() {
        EscalationContact live = new EscalationContact(); live.setId(5L); live.setTeamId(7L);
        when(contactRepo.findAll()).thenReturn(List.of(live));
        when(auditRepo.findByResourceTypeAndEventTypeIn(eq("ESCALATION_CONTACT"), anyCollection(), any(Pageable.class))).thenReturn(new PageImpl<>(List.of(
                row(1, "CONTACT_UPDATE", "ESCALATION_CONTACT", "5", "{\"role\":{\"from\":\"PO\",\"to\":\"TECH\"}}"),
                row(2, "CONTACT_DELETE", "ESCALATION_CONTACT", "6", "{\"name\":\"x\",\"teamId\":9,\"active\":true}"),
                row(3, "CONTACT_DELETE", "ESCALATION_CONTACT", "8", "{\"name\":\"y\"}"))));
        var h = service.history("ESCALATION_CONTACT", null, null, Set.of(7L, 9L), 0, 50);
        assertThat(h.items()).extracting(e -> e.row().getResourceId()).containsExactly("5", "6");
        assertThat(h.items().get(1).teamId()).isEqualTo(9L);
        assertThat(h.hidden()).isEqualTo(1);
    }

    @Test
    @DisplayName("resourceId verilince tek kaydın geçmişi; bilinmeyen kaynak reddedilir")
    void singleResourceAndUnknown() {
        when(auditRepo.findByResourceTypeAndResourceIdAndEventTypeIn(eq("USER"), eq("42"), anyCollection(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(row(1, "USER_UPDATE", "USER", "42", null))));
        assertThat(service.history("USER", "42", null, null, 0, 25).items()).hasSize(1);
        assertThatThrownBy(() -> service.history("MONITOR", null, null, null, 0, 25)).isInstanceOf(IllegalArgumentException.class);
    }
}
