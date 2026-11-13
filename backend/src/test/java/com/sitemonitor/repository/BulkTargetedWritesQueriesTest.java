package com.sitemonitor.repository;

import com.sitemonitor.model.AlertEvent;
import com.sitemonitor.model.AuditLog;
import com.sitemonitor.model.EscalationContact;
import com.sitemonitor.model.NotificationGroup;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Toplu yönetim işlemlerinin HEDEFLİ yazımları (2026-11-13, bağımsız inceleme #7) — gerçek SQL (H2), işlem dışında.
 *
 * <p>Toplu uç satırı okuduktan sonra yazana dek aynı kayıt tekil uçtan değişebilir (adres listesi, webhook). Eski yol
 * bayat okunan varlığın TAMAMINI kaydediyor, bu değişikliği geri alıyordu. Hedefli UPDATE yalnız kendi kolonunu yazar ve
 * okunan değere koşulludur: başka alanın eşzamanlı değişikliği KORUNUR; bu arada başka takıma taşınan kayda yazılmaz.
 */
@DataJpaTest
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class BulkTargetedWritesQueriesTest {

    @Autowired EscalationContactRepository contactRepo;
    @Autowired NotificationGroupRepository groupRepo;
    @Autowired AlertEventRepository alertRepo;
    @Autowired AuditLogRepository auditRepo;

    @Test
    @DisplayName("kişi: pasifleştir / seviye / taşı yalnız kendi kolonunu yazar — okuma ile yazma arasındaki e-posta / webhook değişikliği korunur")
    void contactWritesKeepConcurrentEdits() {
        EscalationContact c = new EscalationContact();
        c.setName("Ayşe"); c.setEmail("ayse@example.com"); c.setRole("TECH"); c.setMinAlertLevel("WARNING");
        c.setTeamId(1L); c.setActive(true);
        c = contactRepo.save(c);
        Long id = c.getId();
        try {
            EscalationContact stale = contactRepo.findById(id).orElseThrow();      // toplu ucun okuduğu satır
            EscalationContact other = contactRepo.findById(id).orElseThrow();      // tekil düzenleme (aynı anda)
            other.setEmail("yeni@example.com");
            other.setWebhookUrl("https://hooks.example.com/x");
            contactRepo.save(other);

            assertThat(contactRepo.bulkSetActive(id, stale.getTeamId(), false)).isEqualTo(1);
            assertThat(contactRepo.bulkSetLevel(id, stale.getTeamId(), "CRITICAL")).isEqualTo(1);
            EscalationContact now = contactRepo.findById(id).orElseThrow();
            assertThat(now.getEmail()).isEqualTo("yeni@example.com");
            assertThat(now.getWebhookUrl()).isEqualTo("https://hooks.example.com/x");
            assertThat(now.getActive()).isFalse();
            assertThat(now.getMinAlertLevel()).isEqualTo("CRITICAL");

            // Değişmeyen değer yazılmaz (0 satır) — çağıran "bu arada değişti" der, denetim yazmaz
            assertThat(contactRepo.bulkSetActive(id, 1L, false)).isZero();
            // Bu arada başka takıma taşınmış kayda eski takım anahtarıyla yazılmaz
            assertThat(contactRepo.bulkMoveTeam(id, 1L, 2L)).isEqualTo(1);
            assertThat(contactRepo.bulkSetActive(id, 1L, true)).isZero();
            assertThat(contactRepo.bulkMoveTeam(id, 1L, 3L)).isZero();
            assertThat(contactRepo.findById(id).orElseThrow().getTeamId()).isEqualTo(2L);
        } finally {
            contactRepo.deleteById(id);
        }
    }

    @Test
    @DisplayName("kişi: takımsız eski kayıt -1 anahtarıyla taşınabilir / pasifleştirilebilir")
    void teamlessContactKey() {
        EscalationContact c = new EscalationContact();
        c.setName("Eski"); c.setEmail("eski@example.com"); c.setRole("TECH"); c.setMinAlertLevel("WARNING"); c.setActive(true);
        c = contactRepo.save(c);
        Long id = c.getId();
        try {
            assertThat(contactRepo.bulkSetActive(id, -1L, false)).isEqualTo(1);
            assertThat(contactRepo.bulkMoveTeam(id, -1L, 5L)).isEqualTo(1);
            assertThat(contactRepo.findById(id).orElseThrow().getTeamId()).isEqualTo(5L);
        } finally {
            contactRepo.deleteById(id);
        }
    }

    @Test
    @DisplayName("grup: taşıma ve varsayılan yalnız takım / bayrak yazar — eşzamanlı adres değişikliği korunur; koşul tutmazsa 0 satır")
    void groupWritesKeepConcurrentEdits() {
        NotificationGroup g = group(7L, "Nöbet", "a@example.com", false);
        NotificationGroup d = group(7L, "Ana", "d@example.com", true);
        g = groupRepo.save(g);
        d = groupRepo.save(d);
        try {
            NotificationGroup other = groupRepo.findById(g.getId()).orElseThrow();
            other.setEmails("a@example.com,b@example.com");
            groupRepo.save(other);

            assertThat(groupRepo.bulkSetDefault(g.getId(), 7L)).isEqualTo(1);
            assertThat(groupRepo.clearOtherDefaults(7L, g.getId())).isEqualTo(1);
            assertThat(groupRepo.findById(d.getId()).orElseThrow().getIsDefault()).isFalse();
            NotificationGroup now = groupRepo.findById(g.getId()).orElseThrow();
            assertThat(now.getIsDefault()).isTrue();
            assertThat(now.getEmails()).isEqualTo("a@example.com,b@example.com");

            // Varsayılan grup taşınmaz (koşul) — 0 satır; varsayılan olmayan taşınır, adres korunur
            assertThat(groupRepo.bulkMoveTeam(g.getId(), 7L, 8L, "2026-11-13T10:00:00", "admin", "Admin")).isZero();
            assertThat(groupRepo.bulkMoveTeam(d.getId(), 7L, 8L, "2026-11-13T10:00:00", "admin", "Admin")).isEqualTo(1);
            NotificationGroup moved = groupRepo.findById(d.getId()).orElseThrow();
            assertThat(moved.getTeamId()).isEqualTo(8L);
            assertThat(moved.getEmails()).isEqualTo("d@example.com");
            assertThat(moved.getUpdatedBy()).isEqualTo("admin");
            // Okunan takım artık geçerli değil → 0 satır
            assertThat(groupRepo.bulkMoveTeam(d.getId(), 7L, 9L, "2026-11-13T10:00:00", "admin", "Admin")).isZero();
        } finally {
            groupRepo.deleteById(g.getId());
            groupRepo.deleteById(d.getId());
        }
    }

    @Test
    @DisplayName("damgalı açık alarm sayısı: yalnız açık + damgalı alarmlar, grup başına")
    void openAlarmStampCount() {
        AlertEvent a = alarm(41L, false);
        AlertEvent b = alarm(41L, false);
        AlertEvent c = alarm(41L, true);    // kapalı → sayılmaz
        AlertEvent d = alarm(null, false);  // damgasız → sayılmaz
        List<AlertEvent> saved = alertRepo.saveAll(List.of(a, b, c, d));
        try {
            Map<Long, Long> m = new HashMap<>();
            for (Object[] r : alertRepo.countOpenByNotificationGroup()) m.put(((Number) r[0]).longValue(), ((Number) r[1]).longValue());
            assertThat(m).containsExactly(Map.entry(41L, 2L));
        } finally {
            alertRepo.deleteAll(saved);
        }
    }

    @Test
    @Transactional   // sınıfın NOT_SUPPORTED'ını ezer: denetim deposunda silme yok — satırlar test sonunda GERİ ALINIR (ortak H2 bağlamında
                     // kalırsa AuditLogRepositoryTest'in sayımlarını bozar; 2026-11-13 PR #111 CI'ı)
    @DisplayName("grup güncelleme izi: kaynak + olay türü + kimlik listesiyle okunur (taşıma satırlarının zamana göre çözümü)")
    void groupUpdateTrail() {
        AuditLog u = new AuditLog();
        u.setEventType("NOTIFICATION_GROUP_UPDATE");
        u.setResourceType("NOTIFICATION_GROUP");
        u.setResourceId("55");
        u.setEventTime("2026-11-13T10:00:00");
        u.setChanges("{\"teamId\":{\"from\":1,\"to\":2}}");
        AuditLog other = new AuditLog();
        other.setEventType("NOTIFICATION_GROUP_DELETE");
        other.setResourceType("NOTIFICATION_GROUP");
        other.setResourceId("55");
        other.setEventTime("2026-11-13T11:00:00");
        // Denetim deposu yalnız ekleme yapar (silme yüzeyi yok) — bu test işlemli, satırlar sonunda geri alınır.
        auditRepo.save(u);
        auditRepo.save(other);
        List<AuditLog> trail = auditRepo.findByResourceTypeAndEventTypeAndResourceIdIn(
                "NOTIFICATION_GROUP", "NOTIFICATION_GROUP_UPDATE", List.of("55", "56"), PageRequest.of(0, 10));
        assertThat(trail).extracting(AuditLog::getResourceId).containsExactly("55");
        // Taşıma izi sorgusu (son inceleme): yalnız teamId içeren güncelleme satırları, en yeni önce
        AuditLog edit = new AuditLog();
        edit.setEventType("NOTIFICATION_GROUP_UPDATE");
        edit.setResourceType("NOTIFICATION_GROUP");
        edit.setResourceId("55");
        edit.setEventTime("2026-11-13T12:00:00");
        edit.setChanges("{\"name\":{\"from\":\"a\",\"to\":\"b\"}}");
        auditRepo.save(edit);
        AuditLog move2 = new AuditLog();
        move2.setEventType("NOTIFICATION_GROUP_UPDATE");
        move2.setResourceType("NOTIFICATION_GROUP");
        move2.setResourceId("55");
        move2.setEventTime("2026-11-13T13:00:00");
        move2.setChanges("{\"teamId\":{\"from\":2,\"to\":3}}");
        auditRepo.save(move2);
        List<AuditLog> moves = auditRepo.findByResourceTypeAndEventTypeAndResourceIdInAndChangesContaining(
                "NOTIFICATION_GROUP", "NOTIFICATION_GROUP_UPDATE", List.of("55"), "\"teamId\"",
                PageRequest.of(0, 10, org.springframework.data.domain.Sort.by(org.springframework.data.domain.Sort.Direction.DESC, "id")));
        assertThat(moves).extracting(AuditLog::getId).containsExactly(move2.getId(), u.getId());
    }

    private static NotificationGroup group(long teamId, String name, String emails, boolean def) {
        NotificationGroup g = new NotificationGroup();
        g.setTeamId(teamId); g.setName(name); g.setEmails(emails); g.setIsDefault(def); g.setActive(true);
        return g;
    }

    private static AlertEvent alarm(Long groupId, boolean resolved) {
        AlertEvent e = new AlertEvent();
        e.setDomain("db.example.com");
        e.setAlertType("HTTP_DOWN");
        e.setAlertLevel("HIGH");
        e.setResolved(resolved);
        e.setAcknowledged(false);
        e.setCreatedAt("2026-11-13T10:00:00");
        e.setNotificationGroupId(groupId);
        return e;
    }
}
