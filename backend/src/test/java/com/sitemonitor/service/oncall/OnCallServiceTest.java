package com.sitemonitor.service.oncall;

import com.sitemonitor.model.AppUser;
import com.sitemonitor.model.OnCallOverride;
import com.sitemonitor.model.OnCallSchedule;
import com.sitemonitor.repository.AppUserRepository;
import com.sitemonitor.repository.OnCallOverrideRepository;
import com.sitemonitor.repository.OnCallScheduleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Nöbet çizelgesi servisi (2026-10-11): kayıt doğrulaması, bildirim hedefi (yalnız seçenek açık + etkin hesap), yer değiştirme, bellek. */
class OnCallServiceTest {

    OnCallScheduleRepository schedules;
    OnCallOverrideRepository overrides;
    AppUserRepository users;
    OnCallService svc;
    final List<OnCallSchedule> rows = new ArrayList<>();

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        schedules = mock(OnCallScheduleRepository.class);
        overrides = mock(OnCallOverrideRepository.class);
        users = mock(AppUserRepository.class);
        svc = new OnCallService(schedules, overrides, users);
        when(schedules.findByTeamIdIn(anyCollection())).thenAnswer(i -> rows.stream()
                .filter(s -> ((Collection<Long>) i.getArgument(0)).contains(s.getTeamId())).toList());
        when(schedules.findByTeamId(any())).thenAnswer(i -> rows.stream().filter(s -> s.getTeamId().equals(i.getArgument(0))).findFirst());
        when(schedules.save(any())).thenAnswer(i -> {
            OnCallSchedule s = i.getArgument(0);
            rows.removeIf(r -> r.getTeamId().equals(s.getTeamId()));
            rows.add(s);
            return s;
        });
        when(overrides.findByTeamIdInAndEndsAtGreaterThan(anyCollection(), anyString())).thenReturn(List.of());
        when(overrides.findByTeamIdAndEndsAtGreaterThanOrderByStartsAtAscIdAsc(any(), anyString())).thenReturn(List.of());
        when(users.findAllById(any())).thenAnswer(i -> {
            List<AppUser> out = new ArrayList<>();
            for (Object id : (Iterable<?>) i.getArgument(0)) out.add(user((Long) id, (Long) id != 99L));
            return out;
        });
        when(users.findById(anyLong())).thenAnswer(i -> Optional.of(user(i.getArgument(0), true)));
    }

    private static AppUser user(Long id, boolean active) {
        AppUser u = new AppUser();
        u.setId(id);
        u.setUsername("N" + id);
        u.setDisplayName("Kişi " + id);
        u.setEmail("kisi" + id + "@example.com");
        u.setActive(active);
        return u;
    }

    private void schedule(long team, boolean notify, List<Long> members) {
        String anchor = LocalDateTime.now(OnCallRotation.TZ).minusDays(1).withSecond(0).withNano(0).toString().substring(0, 16);
        svc.save(team, true, "WEEKLY", anchor, members, notify, "admin");
    }

    @Test
    @DisplayName("bildirim hedefi: yalnız 'nöbetçiye de bildir' açık çizelgeler; ekran için tüm etkin çizelgeler")
    void notifyTargetsOnlyWhenOptedIn() {
        schedule(1L, true, List.of(10L, 11L));
        schedule(2L, false, List.of(20L));
        Map<Long, OnCallService.OnCallPerson> t = svc.notifyTargets(List.of(1L, 2L));
        assertThat(t).containsOnlyKeys(1L);
        assertThat(t.get(1L).email()).isEqualTo("kisi10@example.com");
        assertThat(svc.currentFor(List.of(1L, 2L))).containsOnlyKeys(1L, 2L);
    }

    @Test
    @DisplayName("pasif nöbetçiye bildirim gitmez ama ekranda görünür (uyarıyla)")
    void passiveSkippedForNotify() {
        schedule(1L, true, List.of(99L));
        assertThat(svc.notifyTargets(List.of(1L))).isEmpty();
        assertThat(svc.currentFor(List.of(1L)).get(1L).active()).isFalse();
    }

    @Test
    @DisplayName("bellek: aynı takım kümesi 30 sn içinde yeniden sorgulanmaz; kayıt belleği temizler")
    void cached() {
        schedule(1L, true, List.of(10L));
        svc.notifyTargets(List.of(1L));
        svc.notifyTargets(List.of(1L));
        verify(schedules, times(1)).findByTeamIdIn(anyCollection());
        schedule(1L, true, List.of(11L));
        assertThat(svc.notifyTargets(List.of(1L)).get(1L).userId()).isEqualTo(11L);
    }

    @Test
    @DisplayName("kayıt doğrulaması: rotasyon, devir anı, açıkken en az bir kişi, bilinmeyen kişi, üst sınır")
    void validation() {
        assertThatThrownBy(() -> svc.save(1L, true, "MONTHLY", "2026-10-05T09:00", List.of(10L), false, "a"))
                .hasMessageContaining("Rotasyon");
        assertThatThrownBy(() -> svc.save(1L, true, "WEEKLY", "05.10.2026", List.of(10L), false, "a"))
                .hasMessageContaining("devir");
        assertThatThrownBy(() -> svc.save(1L, true, "WEEKLY", "2026-13-05T09:00", List.of(10L), false, "a"))
                .hasMessageContaining("geçerli");
        assertThatThrownBy(() -> svc.save(1L, true, "WEEKLY", "2026-10-05T09:00", List.of(), false, "a"))
                .hasMessageContaining("en az bir");
        org.mockito.Mockito.doReturn(List.of()).when(users).findAllById(any());
        assertThatThrownBy(() -> svc.save(1L, false, "WEEKLY", "2026-10-05T09:00", List.of(10L), false, "a"))
                .hasMessageContaining("bulunamadı");
        List<Long> many = new ArrayList<>();
        for (long i = 0; i < 51; i++) many.add(i);
        assertThatThrownBy(() -> svc.save(1L, false, "WEEKLY", "2026-10-05T09:00", many, false, "a"))
                .hasMessageContaining("en çok");
        // tekrarlanan kişi tek kez saklanır; kapalı çizelge boş üyeyle kaydedilebilir
        org.mockito.Mockito.doAnswer(i -> {
            List<AppUser> out = new ArrayList<>();
            for (Object id : (Iterable<?>) i.getArgument(0)) out.add(user((Long) id, true));
            return out;
        }).when(users).findAllById(any());
        assertThat(svc.save(1L, true, "daily", "2026-10-05T09:00", List.of(10L, 10L, 11L), true, "a").getMemberIds()).isEqualTo("10,11");
        assertThat(svc.save(2L, false, "WEEKLY", "2026-10-05T09:00", List.of(), false, "a").getMemberIds()).isNull();
    }

    @Test
    @DisplayName("yer değiştirme: bitiş başlangıçtan sonra, en çok 90 gün, kişi var; eski satırlar budanır")
    void overrideRules() {
        when(overrides.save(any())).thenAnswer(i -> i.getArgument(0));
        assertThatThrownBy(() -> svc.addOverride(1L, 10L, "2026-10-10T10:00:00Z", "2026-10-10T09:00:00Z", null, "a"))
                .hasMessageContaining("Bitiş");
        assertThatThrownBy(() -> svc.addOverride(1L, 10L, "2026-10-10T10:00:00Z", "2027-02-10T09:00:00Z", null, "a"))
                .hasMessageContaining("90");
        assertThatThrownBy(() -> svc.addOverride(1L, null, "2026-10-10T10:00:00Z", "2026-10-11T09:00:00Z", null, "a"))
                .hasMessageContaining("Nöbeti devralacak");
        OnCallOverride o = svc.addOverride(1L, 10L, "2026-10-10T10:00:00Z", "2026-10-11T09:00:00", "izin", "a");
        assertThat(o.getStartsAt()).isEqualTo("2026-10-10T10:00:00");
        assertThat(o.getEndsAt()).isEqualTo("2026-10-11T09:00:00");
        verify(overrides).deleteEndedBefore(anyString());
    }

    @Test
    @DisplayName("görünüm: ayarlar, sıralı üyeler (user_id), şu anki nöbetçi, yaklaşan 8 dilim")
    void view() {
        schedule(1L, true, List.of(10L, 11L));
        Map<String, Object> v = svc.view(1L, Instant.now());
        assertThat(v).containsEntry("enabled", true).containsEntry("rotation", "WEEKLY").containsEntry("notify_oncall", true);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> members = (List<Map<String, Object>>) v.get("members");
        assertThat(members).extracting(m -> m.get("user_id")).containsExactly(10L, 11L);
        assertThat(v.get("current")).isNotNull();
        assertThat((List<?>) v.get("upcoming")).hasSize(8);
        assertThat(svc.view(9L, Instant.now())).containsEntry("configured", false).containsEntry("current", null);
        svc.deleteForTeam(1L);
        verify(schedules).delete(any());
        verify(overrides).deleteByTeam(1L);
        verify(users, never()).deleteAll();
        assertThat(Set.of()).isEmpty();
    }
}
