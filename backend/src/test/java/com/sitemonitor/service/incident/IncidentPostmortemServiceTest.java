package com.sitemonitor.service.incident;

import com.sitemonitor.config.GlobalExceptionHandler.FieldValidationException;
import com.sitemonitor.model.AlertComment;
import com.sitemonitor.model.AlertEvent;
import com.sitemonitor.model.AppUser;
import com.sitemonitor.model.IncidentActionItem;
import com.sitemonitor.model.IncidentPostmortem;
import com.sitemonitor.model.IncidentRecord;
import com.sitemonitor.model.NotificationLog;
import com.sitemonitor.repository.AlertCommentRepository;
import com.sitemonitor.repository.AlertEventRepository;
import com.sitemonitor.repository.AppUserRepository;
import com.sitemonitor.repository.IncidentActionItemRepository;
import com.sitemonitor.repository.IncidentPostmortemRepository;
import com.sitemonitor.repository.NotificationLogRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.OptimisticLockingFailureException;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

/**
 * Postmortem servisi (2026-10-11): şablon ön doldurma, sürüm denetimi, bölüm sınırı, durum geçişinde yayımlama damgası,
 * aksiyon doğrulamaları (başlık, tavan, pasif sahip, tarih, yalnız-durum kipi), tamamlanma damgası, hatırlatma sıfırlama
 * ve otomatik zaman çizelgesi (sıra, alarm görünürlüğü, ilk bildirim, yorumlar). Adlar sahte.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class IncidentPostmortemServiceTest {

    @Mock IncidentPostmortemRepository pmRepo;
    @Mock IncidentActionItemRepository actionRepo;
    @Mock AppUserRepository userRepo;
    @Mock AlertEventRepository alertRepo;
    @Mock AlertCommentRepository commentRepo;
    @Mock NotificationLogRepository logRepo;

    IncidentPostmortemService svc;
    IncidentRecord inc;

    @BeforeEach
    void setUp() {
        svc = new IncidentPostmortemService(pmRepo, actionRepo, userRepo);
        svc.setAlertSources(alertRepo, commentRepo, logRepo);
        inc = new IncidentRecord();
        inc.setId(10L);
        inc.setTitle("Ödeme kesintisi");
        inc.setOccurredAt("2026-10-10T08:00:00");
        inc.setDetectedAt("2026-10-10T08:05");
        inc.setResolvedAt("2026-10-10T09:00:00Z");
        inc.setCreatedAt("2026-10-10T09:30:00");
        inc.setCreatedBy("kullanici.a");
        inc.setBusinessImpact("Ödemeler 55 dk alınamadı");
        inc.setRcaSummary("Sertifika süresi doldu");
        inc.setResolutionSteps("Sertifika yenilendi");
        inc.setDescription("Ayrıntı");
        when(pmRepo.saveAndFlush(any())).thenAnswer(i -> {
            IncidentPostmortem p = i.getArgument(0);
            p.setVersion(p.getVersion() == null ? 0L : p.getVersion() + 1);
            return p;
        });
        when(actionRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        when(actionRepo.findByIncidentIdOrderByIdAsc(anyLong())).thenReturn(List.of());
    }

    private static AppUser user(long id, boolean active) {
        AppUser u = new AppUser();
        u.setId(id);
        u.setUsername("u" + id);
        u.setDisplayName("Ayşegül Nur Karaosmanoğlu-Yıldırımtekin (Teknoloji Servis Yönetimi)");
        u.setActive(active);
        return u;
    }

    @Test
    @DisplayName("rapor yokken şablon olay kaydından ön doldurulur, hiçbir şey yazılmaz")
    void prefilledTemplate() {
        when(pmRepo.findByIncidentId(10L)).thenReturn(Optional.empty());
        Map<String, Object> v = svc.postmortemView(inc);
        assertThat(v).containsEntry("exists", false).containsEntry("status", "DRAFT")
                .containsEntry("impact", "Ödemeler 55 dk alınamadı").containsEntry("root_cause", "Sertifika süresi doldu")
                .containsEntry("resolution", "Sertifika yenilendi").containsEntry("summary", "Ayrıntı").containsEntry("lessons", "");
        verify(pmRepo, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("ilk kayıt: oluşturulur, gönderilmeyen bölüm boş kalır; yayımlanınca damga, geri alınınca silinir")
    void saveCreateAndPublish() {
        when(pmRepo.findByIncidentId(10L)).thenReturn(Optional.empty());
        IncidentPostmortem p = svc.save(inc, Map.of("summary", "Özet", "lessons", "  ", "status", "published"), "kullanici.a");
        assertThat(p.getIncidentId()).isEqualTo(10L);
        assertThat(p.getSummary()).isEqualTo("Özet");
        assertThat(p.getLessons()).isNull();
        assertThat(p.getStatus()).isEqualTo("PUBLISHED");
        assertThat(p.getPublishedBy()).isEqualTo("kullanici.a");
        assertThat(p.getPublishedAt()).isNotNull();
        assertThat(p.getCreatedBy()).isEqualTo("kullanici.a");

        when(pmRepo.findByIncidentId(10L)).thenReturn(Optional.of(p));
        IncidentPostmortem p2 = svc.save(inc, Map.of("version", p.getVersion(), "status", "IN_REVIEW", "trigger", "Dağıtım"), "kullanici.b");
        assertThat(p2.getStatus()).isEqualTo("IN_REVIEW");
        assertThat(p2.getPublishedAt()).isNull();
        assertThat(p2.getTriggerText()).isEqualTo("Dağıtım");
        assertThat(p2.getSummary()).isEqualTo("Özet");   // gönderilmeyen bölüm değişmez
        assertThat(p2.getUpdatedBy()).isEqualTo("kullanici.b");
    }

    @Test
    @DisplayName("eski sürümle kayıt 409 (eşzamanlı düzenleme); geçersiz durum ve uzun bölüm alan hatası")
    void saveGuards() {
        IncidentPostmortem p = new IncidentPostmortem();
        p.setIncidentId(10L);
        p.setStatus("DRAFT");
        p.setVersion(3L);
        when(pmRepo.findByIncidentId(10L)).thenReturn(Optional.of(p));
        assertThatThrownBy(() -> svc.save(inc, Map.of("version", 2, "summary", "x"), "a")).isInstanceOf(OptimisticLockingFailureException.class);
        assertThatThrownBy(() -> svc.save(inc, Map.of("summary", "x"), "a")).isInstanceOf(OptimisticLockingFailureException.class);
        assertThatThrownBy(() -> svc.save(inc, Map.of("version", 3, "status", "ARCHIVED"), "a"))
                .isInstanceOf(FieldValidationException.class).hasMessageContaining("Taslak");
        String big = "x".repeat(IncidentPostmortemService.MAX_SECTION_CHARS + 1);
        assertThatThrownBy(() -> svc.save(inc, Map.of("version", 3, "lessons", big), "a"))
                .isInstanceOfSatisfying(FieldValidationException.class, e -> assertThat(e.getFields()).containsKey("lessons"));
        verify(pmRepo, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("aksiyon ekle: varsayılanlar, sahip adı anlık görüntü; boş başlık / pasif sahip / bozuk tarih / tavan reddedilir")
    void createAction() {
        when(userRepo.findById(7L)).thenReturn(Optional.of(user(7L, true)));
        IncidentActionItem a = svc.createAction(inc, Map.of("title", "  Alarm eşiğini düşür  ", "due_date", "2026-10-20"), 7L, "kullanici.a");
        assertThat(a.getTitle()).isEqualTo("Alarm eşiğini düşür");
        assertThat(a.getStatus()).isEqualTo("OPEN");
        assertThat(a.getKind()).isEqualTo("PREVENT");
        assertThat(a.getPriority()).isEqualTo("MEDIUM");
        assertThat(a.getOwnerUserId()).isEqualTo(7L);
        assertThat(a.getOwnerName()).startsWith("Ayşegül Nur");
        assertThat(a.getDueDate()).isEqualTo("2026-10-20");

        assertThatThrownBy(() -> svc.createAction(inc, Map.of("title", " "), null, "a"))
                .isInstanceOfSatisfying(FieldValidationException.class, e -> assertThat(e.getFields()).containsKey("title"));
        when(userRepo.findById(8L)).thenReturn(Optional.of(user(8L, false)));
        assertThatThrownBy(() -> svc.createAction(inc, Map.of("title", "x"), 8L, "a"))
                .isInstanceOfSatisfying(FieldValidationException.class, e -> assertThat(e.getFields()).containsKey("owner_user_id"));
        assertThatThrownBy(() -> svc.createAction(inc, Map.of("title", "x", "due_date", "2026-13-40"), null, "a"))
                .isInstanceOfSatisfying(FieldValidationException.class, e -> assertThat(e.getFields()).containsKey("due_date"));
        assertThatThrownBy(() -> svc.createAction(inc, Map.of("title", "x", "kind", "BLAME"), null, "a"))
                .isInstanceOfSatisfying(FieldValidationException.class, e -> assertThat(e.getFields()).containsKey("kind"));
        when(actionRepo.countByIncidentId(10L)).thenReturn((long) IncidentPostmortemService.MAX_ACTIONS_PER_INCIDENT);
        assertThatThrownBy(() -> svc.createAction(inc, Map.of("title", "x"), null, "a")).isInstanceOf(FieldValidationException.class);
    }

    @Test
    @DisplayName("aksiyon güncelle: tamamlanma damgası, yeniden açılınca silinir; tarih/sahip değişince hatırlatma sıfırlanır")
    void updateAction() {
        when(userRepo.findById(9L)).thenReturn(Optional.of(user(9L, true)));
        IncidentActionItem a = new IncidentActionItem();
        a.setIncidentId(10L);
        a.setTitle("İş");
        a.setStatus("OPEN");
        a.setKind("PREVENT");
        a.setPriority("LOW");
        a.setDueDate("2026-10-12");
        a.setOwnerUserId(7L);
        a.setRemindedOn("2026-10-11");

        svc.updateAction(a, Map.of("status", "DONE"), null, false, false, "b");
        assertThat(a.getCompletedAt()).isNotNull();
        svc.updateAction(a, Map.of("status", "IN_PROGRESS"), null, false, false, "b");
        assertThat(a.getCompletedAt()).isNull();
        assertThat(a.getRemindedOn()).isEqualTo("2026-10-11");

        svc.updateAction(a, Map.of("due_date", "2026-10-30"), null, false, false, "b");
        assertThat(a.getRemindedOn()).isNull();
        a.setRemindedOn("2026-10-11");
        svc.updateAction(a, Map.of("owner_user_id", "x"), 9L, true, false, "b");
        assertThat(a.getOwnerUserId()).isEqualTo(9L);
        assertThat(a.getRemindedOn()).isNull();
        svc.updateAction(a, new HashMap<>(Map.of("due_date", "")), null, false, false, "b");
        assertThat(a.getDueDate()).isNull();
        svc.updateAction(a, Map.of(), null, true, false, "b");   // sahip açıkça kaldırıldı
        assertThat(a.getOwnerUserId()).isNull();
    }

    @Test
    @DisplayName("sahip yalnız durumu değiştirebilir — başka alan sessizce değil, açıkça reddedilir")
    void statusOnly() {
        IncidentActionItem a = new IncidentActionItem();
        a.setTitle("İş");
        a.setStatus("OPEN");
        a.setKind("PREVENT");
        a.setPriority("LOW");
        svc.updateAction(a, Map.of("status", "DONE"), null, false, true, "sahip");
        assertThat(a.getStatus()).isEqualTo("DONE");
        assertThatThrownBy(() -> svc.updateAction(a, Map.of("status", "OPEN", "title", "Yeni"), null, false, true, "sahip"))
                .isInstanceOf(SecurityException.class);
        assertThat(a.getTitle()).isEqualTo("İş");
    }

    @Test
    @DisplayName("zaman çizelgesi: damgalar normalize, eskiden yeniye; alarm yaşam döngüsü + ilk başarılı bildirim + yorum + aksiyon + yayım")
    void timeline() {
        AlertEvent ev = new AlertEvent();
        ev.setId(55L);
        ev.setDomain("odeme.example.com");
        ev.setAlertType("HTTP_DOWN");
        ev.setAlertLevel("CRITICAL");
        ev.setTeamId(5L);
        ev.setCreatedAt("2026-10-10T08:01:00");
        ev.setAcknowledgedAt("2026-10-10T08:10:00");
        ev.setAcknowledgedBy("kullanici.b");
        ev.setResolvedAt("2026-10-10T09:01:00");
        inc.setAlertEventId(55L);
        when(alertRepo.findById(55L)).thenReturn(Optional.of(ev));
        NotificationLog failed = new NotificationLog();
        failed.setSentAt("2026-10-10T08:02:00");
        failed.setEmailStatus("FAILED: smtp");
        NotificationLog ok1 = new NotificationLog();
        ok1.setSentAt("2026-10-10T08:03:00");
        ok1.setEmailStatus("SENT");
        NotificationLog ok2 = new NotificationLog();
        ok2.setSentAt("2026-10-10T08:30:00");
        ok2.setWebhookStatus("SENT");
        when(logRepo.findByAlertEventIdOrderBySentAtDesc(55L)).thenReturn(List.of(ok2, ok1, failed));
        AlertComment c = new AlertComment();
        c.setAuthorName("Kullanıcı B");
        c.setBody("Sertifika   süresi\n doldu");
        c.setCreatedAt("2026-10-10T08:20:00");
        when(commentRepo.findByAlertEventIdAndDeletedAtIsNullOrderByCreatedAtAsc(55L)).thenReturn(List.of(c));
        IncidentActionItem a = new IncidentActionItem();
        a.setId(3L);
        a.setTitle("Sertifika takibi");
        a.setStatus("DONE");
        a.setCreatedAt("2026-10-10T10:00:00");
        a.setCompletedAt("2026-10-11T07:00:00");
        when(actionRepo.findByIncidentIdOrderByIdAsc(10L)).thenReturn(List.of(a));
        IncidentPostmortem pm = new IncidentPostmortem();
        pm.setPublishedAt("2026-10-11T08:00:00");
        pm.setPublishedBy("kullanici.a");
        when(pmRepo.findByIncidentId(10L)).thenReturn(Optional.of(pm));

        List<Map<String, Object>> tl = svc.timeline(inc, true, team -> team == 5L);
        assertThat(tl).extracting(m -> m.get("kind")).containsExactly(
                "occurred", "alert_opened", "alert_notified", "detected", "alert_acknowledged", "alert_comment",
                "resolved", "alert_resolved", "recorded", "action_added", "action_done", "postmortem_published");
        assertThat(tl.get(2)).containsEntry("at", "2026-10-10T08:03:00").containsEntry("count", 2);
        assertThat(tl.get(3)).containsEntry("at", "2026-10-10T08:05:00");
        assertThat(tl.get(5)).containsEntry("text", "Sertifika süresi doldu").containsEntry("actor", "Kullanıcı B");
        assertThat(tl.get(1)).containsEntry("domain", "odeme.example.com").containsEntry("alert_level", "CRITICAL");

        // Alarm izni yok ya da alarmın takımı görülemiyor → alarm satırları hiç yok
        assertThat(svc.timeline(inc, false, team -> true)).extracting(m -> m.get("kind")).doesNotContain("alert_opened", "alert_comment");
        assertThat(svc.timeline(inc, true, team -> false)).extracting(m -> m.get("kind")).doesNotContain("alert_opened", "alert_notified");
    }

    @Test
    @DisplayName("damga normalizasyonu: bölgesiz / Z / ofset / saniyesiz; bozuk ve aşırı uzun → yok sayılır")
    void normalize() {
        assertThat(IncidentPostmortemService.norm("2026-10-10T08:00")).isEqualTo("2026-10-10T08:00:00");
        assertThat(IncidentPostmortemService.norm("2026-10-10T08:00:00Z")).isEqualTo("2026-10-10T08:00:00");
        assertThat(IncidentPostmortemService.norm("2026-10-10T11:00:00+03:00")).isEqualTo("2026-10-10T08:00:00");
        assertThat(IncidentPostmortemService.norm("2026-10-10T08:00:00.123")).isEqualTo("2026-10-10T08:00:00");
        assertThat(IncidentPostmortemService.norm("dün")).isNull();
        assertThat(IncidentPostmortemService.norm("2".repeat(100))).isNull();
        assertThat(IncidentPostmortemService.norm(null)).isNull();
    }

    @Test
    @DisplayName("liste rozetleri iki sorguyla eşlenir")
    void summaries() {
        when(pmRepo.statusFor(any())).thenReturn(List.<Object[]>of(new Object[]{10L, "IN_REVIEW"}));
        when(actionRepo.countsFor(any(), any())).thenReturn(List.<Object[]>of(new Object[]{10L, 2L, 1L, 3L, 6L}, new Object[]{11L, 0L, 0L, 1L, 1L}));
        Map<Long, Map<String, Object>> s = svc.summaries(List.of(10L, 11L, 12L));
        assertThat(s.get(10L)).containsEntry("postmortem_status", "IN_REVIEW").containsEntry("actions_open", 2L)
                .containsEntry("actions_overdue", 1L).containsEntry("actions_done", 3L).containsEntry("actions_total", 6L);
        assertThat(s.get(11L)).doesNotContainKey("postmortem_status").containsEntry("actions_done", 1L);
        assertThat(s).doesNotContainKey(12L);
        assertThat(svc.summaries(List.of())).isEmpty();
    }
}
