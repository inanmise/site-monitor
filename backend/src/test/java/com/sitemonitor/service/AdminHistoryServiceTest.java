package com.sitemonitor.service;

import com.sitemonitor.model.AuditLog;
import com.sitemonitor.model.EscalationContact;
import com.sitemonitor.repository.AuditLogRepository;
import com.sitemonitor.repository.EscalationContactRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Yönetim Paneli değişiklik geçmişi (2026-09-20): takım kapsamı kapalı tarafa düşer; silinmiş kişinin
 * takımı anlık görüntüden çözülür; bilinmeyen kaynak reddedilir.
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
    @DisplayName("TEAM: kapsamlı kullanıcı yalnız kendi takımlarının satırlarını görür")
    void teamScope() {
        when(auditRepo.findByResourceTypeOrderByEventTimeDesc(eq("TEAM"), any()))
                .thenReturn(List.of(row(1, "TEAM_UPDATE", "TEAM", "7", null), row(2, "TEAM_UPDATE", "TEAM", "9", null)));
        var h = service.history("TEAM", null, Set.of(7L), 50);
        assertThat(h.items()).hasSize(1);
        assertThat(h.items().get(0).row().getResourceId()).isEqualTo("7");
        assertThat(h.items().get(0).teamId()).isEqualTo(7L);
        assertThat(h.hidden()).isZero();
        // global: hepsi
        assertThat(service.history("TEAM", null, null, 50).items()).hasSize(2);
    }

    @Test
    @DisplayName("ESCALATION_CONTACT: canlı satırın takımı, silinmiş satırda anlık görüntüdeki teamId; çözülemeyen gizlenir")
    void contactScopeResolvesDeletedFromSnapshot() {
        EscalationContact live = new EscalationContact(); live.setId(5L); live.setTeamId(7L);
        when(contactRepo.findAll()).thenReturn(List.of(live));
        when(auditRepo.findByResourceTypeOrderByEventTimeDesc(eq("ESCALATION_CONTACT"), any())).thenReturn(List.of(
                row(1, "CONTACT_UPDATE", "ESCALATION_CONTACT", "5", "{\"role\":{\"from\":\"PO\",\"to\":\"TECH\"}}"),
                row(2, "CONTACT_DELETE", "ESCALATION_CONTACT", "6", "{\"name\":\"x\",\"teamId\":9,\"active\":true}"),
                row(3, "CONTACT_DELETE", "ESCALATION_CONTACT", "8", "{\"name\":\"y\"}")));
        var h = service.history("ESCALATION_CONTACT", null, Set.of(7L, 9L), 50);
        assertThat(h.items()).extracting(e -> e.row().getResourceId()).containsExactly("5", "6");
        assertThat(h.items().get(1).teamId()).isEqualTo(9L);
        assertThat(h.hidden()).isEqualTo(1);   // takımı çözülemeyen satır sessizce değil, SAYILARAK gizlendi
    }

    @Test
    @DisplayName("resourceId verilince tek kaydın geçmişi; bilinmeyen kaynak reddedilir")
    void singleResourceAndUnknown() {
        when(auditRepo.findByResourceTypeAndResourceIdOrderByEventTimeDesc(eq("USER"), eq("42"), any()))
                .thenReturn(List.of(row(1, "USER_UPDATE", "USER", "42", null)));
        assertThat(service.history("USER", "42", null, 50).items()).hasSize(1);
        assertThatThrownBy(() -> service.history("MONITOR", null, null, 50)).isInstanceOf(IllegalArgumentException.class);
    }
}
