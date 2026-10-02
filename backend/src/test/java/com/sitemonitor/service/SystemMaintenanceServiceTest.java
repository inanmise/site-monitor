package com.sitemonitor.service;

import com.sitemonitor.model.AppUser;
import com.sitemonitor.model.SystemMaintenanceSuppression;
import com.sitemonitor.model.SystemMaintenanceWindow;
import com.sitemonitor.repository.AppUserRepository;
import com.sitemonitor.repository.SystemMaintenanceSuppressionRepository;
import com.sitemonitor.repository.SystemMaintenanceWindowRepository;
import com.sitemonitor.repository.TeamRepository;
import com.sitemonitor.service.SystemMaintenanceService.Phase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.mock.web.MockHttpSession;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Sistem Bakım Modu — durum makinesi, doğrulama, yönetim eylemleri, istemci blokları ve önbellek (2026-10-02).
 * Durum ZAMANDAN türetilir; testler sabit saatle (Clock.fixed) koşar.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SystemMaintenanceServiceTest {

    static final Instant NOW = Instant.parse("2026-10-02T10:00:00Z");

    @Mock SystemMaintenanceWindowRepository repo;
    @Mock SystemMaintenanceSuppressionRepository suppressionRepo;
    @Mock AuditService auditService;
    @Mock AppUserRepository userRepo;
    @Mock UserService userService;
    @Mock TeamRepository teamRepo;

    SystemMaintenanceService svc;
    MockHttpSession admin;
    List<SystemMaintenanceWindow> stored;

    @BeforeEach
    void setUp() {
        svc = new SystemMaintenanceService(repo, suppressionRepo, auditService, userRepo, userService, teamRepo);
        svc.clock = Clock.fixed(NOW, ZoneOffset.UTC);
        svc.cacheMs = 5000;
        admin = new MockHttpSession();
        admin.setAttribute("username", "admin");
        admin.setAttribute("userId", 1L);
        admin.setAttribute("systemRole", "ADMIN");
        stored = new ArrayList<>();
        when(repo.save(any(SystemMaintenanceWindow.class))).thenAnswer(inv -> {
            SystemMaintenanceWindow w = inv.getArgument(0);
            if (w.getId() == null) w.setId(100L + stored.size());
            stored.removeIf(x -> x.getId().equals(w.getId()));
            stored.add(w);
            return w;
        });
        when(repo.findById(anyLong())).thenAnswer(inv -> stored.stream()
                .filter(w -> w.getId().equals(inv.getArgument(0))).findFirst());
        when(repo.findByCancelledAtIsNullAndEndAtGreaterThanOrderByStartAtAsc(anyString())).thenAnswer(inv -> {
            String now = inv.getArgument(0);
            return stored.stream().filter(w -> w.getCancelledAt() == null && w.getEndAt().compareTo(now) > 0)
                    .sorted((a, b) -> a.getStartAt().compareTo(b.getStartAt())).toList();
        });
    }

    static String iso(Instant i) { return SystemMaintenanceService.iso(i); }

    /** Pencere: başlangıç/bitiş şimdiden dakika ofsetiyle. */
    SystemMaintenanceWindow win(long id, long startMin, long endMin, int warn, int announceH, boolean mute) {
        SystemMaintenanceWindow w = new SystemMaintenanceWindow();
        w.setId(id);
        w.setStartAt(iso(NOW.plus(Duration.ofMinutes(startMin))));
        w.setEndAt(iso(NOW.plus(Duration.ofMinutes(endMin))));
        w.setPlannedStartAt(w.getStartAt());
        w.setPlannedEndAt(w.getEndAt());
        w.setWarnMinutes(warn);
        w.setAnnounceHours(announceH);
        w.setMuteNotifications(mute);
        w.setRevision(1);
        w.setMessageTr("Veritabanı sürüm yükseltmesi");
        w.setContact("BT Destek 1234");
        w.setCreatedBy("admin");
        w.setSessionsEnded(3);
        w.setLoginsBlocked(2);
        w.setNotificationsSuppressed(7);
        stored.add(w);
        return w;
    }

    static Map<String, Object> body(Object... kv) {
        Map<String, Object> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    /** İstanbul yerel "yyyy-MM-ddTHH:mm" — şimdiden dakika ofsetiyle. */
    static String local(long minutes) {
        return SystemMaintenanceService.LOCAL_IN.format(NOW.plus(Duration.ofMinutes(minutes)).atZone(SystemMaintenanceService.ZONE));
    }

    // ── Durum makinesi ─────────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("phaseOf — durum ZAMANDAN türetilir")
    class Phases {
        @Test
        void planned_announced_warning_active_ended_cancelled() {
            // başlangıç +120 dk, uyarı 10 dk, duyuru 1 sa
            SystemMaintenanceWindow w = win(1, 120, 180, 10, 1, false);
            assertThat(SystemMaintenanceService.phaseOf(w, NOW)).isEqualTo(Phase.PLANNED);
            assertThat(SystemMaintenanceService.phaseOf(w, NOW.plus(Duration.ofMinutes(60)))).isEqualTo(Phase.ANNOUNCED);
            assertThat(SystemMaintenanceService.phaseOf(w, NOW.plus(Duration.ofMinutes(110)))).isEqualTo(Phase.WARNING);
            assertThat(SystemMaintenanceService.phaseOf(w, NOW.plus(Duration.ofMinutes(120)))).isEqualTo(Phase.ACTIVE);
            assertThat(SystemMaintenanceService.phaseOf(w, NOW.plus(Duration.ofMinutes(179)))).isEqualTo(Phase.ACTIVE);
            assertThat(SystemMaintenanceService.phaseOf(w, NOW.plus(Duration.ofMinutes(180)))).isEqualTo(Phase.ENDED);
            w.setCancelledAt(iso(NOW));
            assertThat(SystemMaintenanceService.phaseOf(w, NOW.plus(Duration.ofMinutes(130)))).isEqualTo(Phase.CANCELLED);
        }

        @Test
        @DisplayName("duyuru kapalı (0) → uyarıdan önce PLANNED kalır")
        void announceOff_staysPlanned() {
            SystemMaintenanceWindow w = win(1, 120, 180, 10, 0, false);
            assertThat(SystemMaintenanceService.phaseOf(w, NOW.plus(Duration.ofMinutes(100)))).isEqualTo(Phase.PLANNED);
        }
    }

    // ── Doğrulama ────────────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("planla — alan yanında doğrulama")
    class Schedule {
        @Test
        @DisplayName("geçmiş zamanda başlangıç → start_local alan hatası")
        void pastStart_rejected() {
            assertThatThrownBy(() -> svc.schedule(body("start_local", local(-5), "end_local", local(60)), admin))
                    .isInstanceOf(SystemMaintenanceService.FieldException.class)
                    .extracting(e -> ((SystemMaintenanceService.FieldException) e).field()).isEqualTo("start_local");
            verify(repo, never()).save(any());
        }

        @Test
        @DisplayName("bitiş başlangıçtan önce → end_local; 5 dk'dan kısa → end_local")
        void endBeforeStart_andTooShort_rejected() {
            assertThatThrownBy(() -> svc.schedule(body("start_local", local(60), "end_local", local(30)), admin))
                    .extracting(e -> ((SystemMaintenanceService.FieldException) e).field()).isEqualTo("end_local");
            assertThatThrownBy(() -> svc.schedule(body("start_local", local(60), "end_local", local(63)), admin))
                    .extracting(e -> ((SystemMaintenanceService.FieldException) e).field()).isEqualTo("end_local");
        }

        @Test
        @DisplayName("çakışan pencere reddedilir (start_local + #id)")
        void overlap_rejected() {
            win(5, 100, 200, 10, 24, false);
            assertThatThrownBy(() -> svc.schedule(body("start_local", local(150), "end_local", local(260)), admin))
                    .isInstanceOf(SystemMaintenanceService.FieldException.class)
                    .hasMessageContaining("#5")
                    .extracting(e -> ((SystemMaintenanceService.FieldException) e).field()).isEqualTo("start_local");
        }

        @Test
        @DisplayName("zorunlu alan boş → alan hatası; geçersiz uyarı süresi → warn_minutes")
        void required_and_invalidWarn() {
            assertThatThrownBy(() -> svc.schedule(body("end_local", local(60)), admin))
                    .extracting(e -> ((SystemMaintenanceService.FieldException) e).field()).isEqualTo("start_local");
            assertThatThrownBy(() -> svc.schedule(body("start_local", local(60), "end_local", local(120), "warn_minutes", 7), admin))
                    .extracting(e -> ((SystemMaintenanceService.FieldException) e).field()).isEqualTo("warn_minutes");
        }

        @Test
        @DisplayName("geçerli plan → kaydedilir (İstanbul → UTC), sürüm 1, denetim SYSTEM_MAINTENANCE_SCHEDULED")
        void valid_saved_andAudited() {
            SystemMaintenanceWindow w = svc.schedule(body("start_local", local(120), "end_local", local(180),
                    "warn_minutes", 15, "announce_hours", 6, "mute_notifications", true,
                    "message_tr", "Sürüm", "email_all_users", true), admin);
            assertThat(w.getStartAt()).isEqualTo(iso(NOW.plus(Duration.ofMinutes(120))));
            assertThat(w.getEndAt()).isEqualTo(iso(NOW.plus(Duration.ofMinutes(180))));
            assertThat(w.getWarnMinutes()).isEqualTo(15);
            assertThat(w.getAnnounceHours()).isEqualTo(6);
            assertThat(w.getMuteNotifications()).isTrue();
            assertThat(w.getRevision()).isEqualTo(1);
            assertThat(w.getImmediate()).isFalse();
            assertThat(w.getCreatedBy()).isEqualTo("admin");
            assertThat(w.getEmailCorrections()).as("düzeltme e-postası varsayılan açık").isTrue();
            verify(auditService).recordAction(eq("SYSTEM_MAINTENANCE_SCHEDULED"), eq(admin), eq("SYSTEM_MAINTENANCE"),
                    eq(String.valueOf(w.getId())), anyString(), isNull());
        }

        @Test
        @DisplayName("bildirim susturma varsayılanı KAPALI")
        void muteDefaultOff() {
            SystemMaintenanceWindow w = svc.schedule(body("start_local", local(120), "end_local", local(180)), admin);
            assertThat(w.getMuteNotifications()).isFalse();
            assertThat(w.getWarnMinutes()).isEqualTo(10);
            assertThat(w.getAnnounceHours()).isEqualTo(24);
        }
    }

    // ── Hemen bakıma al ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("hemen bakıma al: geri sayım 0 → 10 sn'lik pencere, uyarı hemen (1 dk), duyuru yok, denetim START_NOW")
    void startNow_zeroCountdown() {
        SystemMaintenanceWindow w = svc.startNow(body("countdown_minutes", 0, "duration_minutes", 30), admin);
        assertThat(w.getStartAt()).isEqualTo(iso(NOW.plusSeconds(10)));
        assertThat(w.getEndAt()).isEqualTo(iso(NOW.plusSeconds(10).plus(Duration.ofMinutes(30))));
        assertThat(w.getImmediate()).isTrue();
        assertThat(w.getWarnMinutes()).isEqualTo(1);
        assertThat(w.getAnnounceHours()).isZero();
        assertThat(w.getStartedBy()).isEqualTo("admin");
        assertThat(SystemMaintenanceService.phaseOf(w, NOW)).isEqualTo(Phase.WARNING);
        verify(auditService).recordAction(eq("SYSTEM_MAINTENANCE_START_NOW"), eq(admin), eq("SYSTEM_MAINTENANCE"),
                anyString(), anyString(), isNull());
    }

    @Test
    @DisplayName("hemen bakıma al: geçersiz geri sayım → countdown_minutes alan hatası")
    void startNow_invalidCountdown() {
        assertThatThrownBy(() -> svc.startNow(body("countdown_minutes", 3), admin))
                .extracting(e -> ((SystemMaintenanceService.FieldException) e).field()).isEqualTo("countdown_minutes");
    }

    // ── Uzat / bitir / iptal / düzenle ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("uzat +30: bitiş ileri, sürüm artar (düzeltme e-postası), denetim EXTENDED")
    void extend_active() {
        SystemMaintenanceWindow w = win(7, -10, 20, 10, 0, false);
        SystemMaintenanceWindow out = svc.extend(7L, body("minutes", 30), admin);
        assertThat(out.getEndAt()).isEqualTo(iso(NOW.plus(Duration.ofMinutes(50))));
        assertThat(out.getRevision()).isEqualTo(2);
        assertThat(out.getExtendedCount()).isEqualTo(1);
        verify(auditService).recordAction(eq("SYSTEM_MAINTENANCE_EXTENDED"), eq(admin), eq("SYSTEM_MAINTENANCE"),
                eq("7"), anyString(), isNull());
        assertThat(w.getExtendedBy()).isEqualTo("admin");
    }

    @Test
    @DisplayName("uzat: yeni bitiş mevcuttan önce → alan hatası; planlı (uyarı öncesi) bakım → 409")
    void extend_rules() {
        win(7, -10, 20, 10, 0, false);
        assertThatThrownBy(() -> svc.extend(7L, body("end_local", local(10)), admin))
                .isInstanceOf(SystemMaintenanceService.FieldException.class);
        win(8, 300, 400, 10, 0, false);
        assertThatThrownBy(() -> svc.extend(8L, body("minutes", 15), admin)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("hemen bitir: bitiş = şimdi → ENDED; başlamamış bakım → 409 (iptal edin)")
    void endNow() {
        win(9, -10, 50, 10, 0, true);
        SystemMaintenanceWindow out = svc.endNow(9L, admin);
        assertThat(out.getEndAt()).isEqualTo(iso(NOW));
        assertThat(out.getEndedBy()).isEqualTo("admin");
        assertThat(SystemMaintenanceService.phaseOf(out, NOW)).isEqualTo(Phase.ENDED);
        verify(auditService).recordAction(eq("SYSTEM_MAINTENANCE_END_NOW"), eq(admin), eq("SYSTEM_MAINTENANCE"),
                eq("9"), anyString(), isNull());
        win(10, 60, 120, 10, 0, false);
        assertThatThrownBy(() -> svc.endNow(10L, admin)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("iptal: başlamadan önce → CANCELLED + sürüm artar; süren bakım → 409")
    void cancel() {
        win(11, 60, 120, 10, 24, false);
        SystemMaintenanceWindow out = svc.cancel(11L, admin);
        assertThat(out.getCancelledAt()).isEqualTo(iso(NOW));
        assertThat(out.getRevision()).isEqualTo(2);
        assertThat(SystemMaintenanceService.phaseOf(out, NOW)).isEqualTo(Phase.CANCELLED);
        verify(auditService).recordAction(eq("SYSTEM_MAINTENANCE_CANCELLED"), eq(admin), eq("SYSTEM_MAINTENANCE"),
                eq("11"), anyString(), isNull());
        win(12, -5, 30, 10, 0, false);
        assertThatThrownBy(() -> svc.cancel(12L, admin)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("düzenle: saat değişirse sürüm artar; yalnız ayar değişirse sürüm aynı; başlamış bakım → 409")
    void update() {
        win(13, 120, 180, 10, 24, false);
        SystemMaintenanceWindow a = svc.update(13L, body("message_tr", "Yeni neden"), admin);
        assertThat(a.getRevision()).isEqualTo(1);
        assertThat(a.getMessageTr()).isEqualTo("Yeni neden");
        SystemMaintenanceWindow b = svc.update(13L, body("start_local", local(130), "end_local", local(190)), admin);
        assertThat(b.getRevision()).isEqualTo(2);
        assertThat(b.getPlannedStartAt()).isEqualTo(iso(NOW.plus(Duration.ofMinutes(130))));
        verify(auditService, times(2)).recordAction(eq("SYSTEM_MAINTENANCE_UPDATED"), eq(admin), eq("SYSTEM_MAINTENANCE"),
                eq("13"), anyString(), isNull());
        win(14, -5, 30, 10, 0, false);
        assertThatThrownBy(() -> svc.update(14L, body("message_tr", "x"), admin)).isInstanceOf(IllegalStateException.class);
    }

    // ── Kapılar ve istemci blokları ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("bakım yokken: isActive=false, muted=false, blok {state:none}")
    void noMaintenance() {
        assertThat(svc.isActive()).isFalse();
        assertThat(svc.notificationsMuted()).isFalse();
        assertThat(svc.clientBlock()).containsEntry("state", "none");
        assertThat(svc.publicStatus()).containsEntry("state", "none").containsKey("server_now");
    }

    @Test
    @DisplayName("duyuru penceresi dışında planlı bakım kullanıcıya gösterilmez; içinde 'announced'")
    void clientBlock_announceWindow() {
        win(20, 25 * 60, 26 * 60, 10, 24, false);   // 25 saat sonra, duyuru 24 sa
        assertThat(svc.clientBlock()).containsEntry("state", "none");
        stored.clear();
        svc.invalidate();
        win(21, 23 * 60, 24 * 60, 10, 24, false);   // 23 saat sonra
        Map<String, Object> b = svc.clientBlock();
        assertThat(b).containsEntry("state", "announced").containsEntry("id", 21L).containsEntry("warn_minutes", 10);
        assertThat(b.get("start_at")).asString().endsWith("Z");
    }

    @Test
    @DisplayName("aktif + susturma açık → muted; aktif + susturma kapalı → muted değil; uyarı aşamasında → aktif değil")
    void activeAndMuted() {
        win(30, -5, 60, 10, 0, true);
        assertThat(svc.isActive()).isTrue();
        assertThat(svc.notificationsMuted()).isTrue();
        assertThat(svc.clientBlock()).containsEntry("state", "active");
        stored.clear();
        svc.invalidate();
        win(31, -5, 60, 10, 0, false);
        assertThat(svc.notificationsMuted()).isFalse();
        stored.clear();
        svc.invalidate();
        win(32, 5, 60, 10, 0, true);
        assertThat(svc.isActive()).isFalse();
        assertThat(svc.notificationsMuted()).isFalse();
        assertThat(svc.clientBlock()).containsEntry("state", "warning");
    }

    @Test
    @DisplayName("public durum: yalnız durum/saatler/mesaj/iletişim — kimlik, kişi, sayaç YOK")
    void publicStatus_noLeak() {
        win(40, -5, 60, 10, 0, true);
        Map<String, Object> p = svc.publicStatus();
        assertThat(p.keySet()).containsExactlyInAnyOrder("state", "start_at", "end_at", "start_local", "end_local",
                "warn_minutes", "announce_hours", "message_tr", "message_en", "contact", "server_now");
        assertThat(p.toString()).doesNotContain("admin").doesNotContain("sessions").doesNotContain("logins_blocked");
    }

    @Test
    @DisplayName("401/403 sinyali: code=MAINTENANCE + pencere + arayüz dilinde mesaj")
    void signalBody() {
        win(41, -5, 60, 10, 0, false);
        Map<String, Object> b = svc.signalBody();
        assertThat(b).containsEntry("success", false).containsEntry("code", "MAINTENANCE").containsEntry("error_code", "MAINTENANCE");
        assertThat(b.get("maintenance")).isInstanceOf(Map.class);
        assertThat(b.get("error")).asString().contains("İstanbul");
    }

    @Test
    @DisplayName("önbellek: TTL içinde DB'ye tekrar gidilmez; invalidate sonrası gidilir; DB hatası → fail-open")
    void cache() {
        svc.isActive();
        svc.isActive();
        svc.notificationsMuted();
        verify(repo, times(1)).findByCancelledAtIsNullAndEndAtGreaterThanOrderByStartAtAsc(anyString());
        svc.invalidate();
        svc.isActive();
        verify(repo, times(2)).findByCancelledAtIsNullAndEndAtGreaterThanOrderByStartAtAsc(anyString());
        svc.invalidate();
        when(repo.findByCancelledAtIsNullAndEndAtGreaterThanOrderByStartAtAsc(anyString())).thenThrow(new RuntimeException("db down"));
        assertThat(svc.isActive()).isFalse();
    }

    // ── Sayaçlar ve susturma kaydı ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("susturma: sayaç artar; ilk kez → satır (INITIAL = açılış); tekrar → touch")
    void noteSuppressed() {
        win(50, -5, 60, 10, 0, true);
        when(suppressionRepo.touch(eq(50L), eq(900L), anyString(), anyBoolean())).thenReturn(0);
        svc.noteSuppressed(900L, "INITIAL");
        verify(repo).incrementNotificationsSuppressed(50L);
        ArgumentCaptor<SystemMaintenanceSuppression> cap = ArgumentCaptor.forClass(SystemMaintenanceSuppression.class);
        verify(suppressionRepo).save(cap.capture());
        assertThat(cap.getValue().getOpening()).isTrue();
        assertThat(cap.getValue().getWindowId()).isEqualTo(50L);

        when(suppressionRepo.touch(eq(50L), eq(901L), anyString(), eq(false))).thenReturn(1);
        svc.noteSuppressed(901L, "DAILY_REALERT");
        verify(suppressionRepo, times(1)).save(any());   // ikinci çağrı satır açmadı

        svc.noteSuppressed(null, "INITIAL");   // olaysız → yalnız sayaç
        verify(repo, times(3)).incrementNotificationsSuppressed(50L);
    }

    @Test
    @DisplayName("bakım yokken sayaçlar hiç yazılmaz")
    void counters_noMaintenance() {
        svc.recordBlockedLogin();
        svc.recordSessionEnded();
        svc.noteSuppressed(1L, "INITIAL");
        verify(repo, never()).incrementLoginsBlocked(anyLong());
        verify(repo, never()).incrementSessionsEnded(anyLong());
        verify(repo, never()).incrementNotificationsSuppressed(anyLong());
    }

    @Test
    @DisplayName("etki özeti: canlı oturumlar ve global yönetici olmayanlar")
    void impact() {
        AppUser a = user("ADMIN", "LOCAL"), b = user("USER", "LDAP"), c = user("ADMIN", "LDAP"), d = user("USER", "LOCAL");
        d.setActive(false);
        when(userRepo.findAllWithActiveSession()).thenReturn(List.of(a, b, c, d));
        when(userService.hasLiveSession(any())).thenReturn(true);
        when(userService.computeViewTeamIds(a)).thenReturn(null);
        when(userService.computeViewTeamIds(c)).thenReturn(List.of(4L));
        Map<String, Object> m = svc.impact();
        assertThat(m).containsEntry("live_sessions", 3).containsEntry("affected_sessions", 2).containsEntry("admin_sessions", 1);
    }

    @Test
    @DisplayName("global yönetici: yerel ADMIN evet; AD müdürü (kapsamlı) ve USER hayır")
    void globalAdminAccount() {
        AppUser a = user("ADMIN", "LOCAL"), m = user("ADMIN", "LDAP"), u = user("USER", "LOCAL");
        when(userService.computeViewTeamIds(a)).thenReturn(null);
        when(userService.computeViewTeamIds(m)).thenReturn(List.of(1L));
        when(userService.computeViewTeamIds(u)).thenReturn(List.of(1L));
        assertThat(svc.isGlobalAdminAccount(a)).isTrue();
        assertThat(svc.isGlobalAdminAccount(m)).isFalse();
        assertThat(svc.isGlobalAdminAccount(u)).isFalse();
    }

    @Test
    @DisplayName("windowText: aynı gün tek tarih, İstanbul saati")
    void windowText() {
        assertThat(SystemMaintenanceService.windowText("2026-10-02T19:00:00", "2026-10-02T20:30:00"))
                .isEqualTo("02.10.2026 22:00 – 23:30");
        assertThat(SystemMaintenanceService.windowText("2026-10-02T20:00:00", "2026-10-02T22:00:00"))
                .isEqualTo("02.10.2026 23:00 – 03.10.2026 01:00");
    }

    @Test
    @DisplayName("geçmiş DTO: plan/gerçek zamanlar ve sayaçlar")
    void dto_history() {
        SystemMaintenanceWindow w = win(60, -90, -30, 10, 24, true);
        w.setEndedBy("admin");
        Map<String, Object> d = svc.toDto(w);
        assertThat(d).containsEntry("phase", "ended").containsEntry("sessions_ended", 3).containsEntry("logins_blocked", 2)
                .containsEntry("notifications_suppressed", 7).containsEntry("mute_notifications", true)
                .containsEntry("ended_by", "admin");
        assertThat(d.get("actual_start_at")).isNotNull();
        assertThat(d.get("actual_end_at")).isNotNull();
        SystemMaintenanceWindow p = win(61, 100, 200, 10, 0, false);
        Map<String, Object> d2 = svc.toDto(p);
        assertThat(d2.get("actual_start_at")).isNull();
        assertThat(d2).containsEntry("phase", "planned");
    }

    private static AppUser user(String role, String source) {
        AppUser u = new AppUser();
        u.setUsername(role + "-" + source + "-" + System.nanoTime());
        u.setSystemRole(role);
        u.setAuthSource(source);
        u.setActive(true);
        return u;
    }

    @Test
    @DisplayName("notificationsHeld: susturulmuş bitmiş bakımın telafisi koşana dek true")
    void held_untilCatchUp() {
        SystemMaintenanceWindow w = win(70, -60, -1, 10, 0, true);
        when(repo.findPendingJobs()).thenReturn(List.of(w));
        assertThat(svc.notificationsMuted()).isFalse();
        assertThat(svc.notificationsHeld()).isTrue();
        w.setCatchUpAt(iso(NOW));
        assertThat(svc.notificationsHeld()).isFalse();
    }

    // ── "Bakım tamamlandı" notu (2026-10-02, kullanıcı isteği) ──────────────────────────────────────

    @Nested
    @DisplayName("bakım tamamlandı — state 'ended' (notice dakikası içinde)")
    class EndedNotice {

        @Test
        @DisplayName("bitişten 20 dk sonra: istemci bloğu 'ended' (id, sürüm, gerçek bitiş, plan bitişi, mesaj); public blokta kimlik YOK")
        void withinNotice() {
            SystemMaintenanceWindow w = win(80, -90, -20, 10, 24, true);
            w.setPlannedEndAt(iso(NOW.minus(Duration.ofMinutes(10))));   // erken bitirildi (plan −10 dk, gerçek −20 dk)
            w.setMessageEn("DB upgrade");
            w.setRevision(3);
            Map<String, Object> b = svc.clientBlock();
            assertThat(b).containsEntry("state", "ended").containsEntry("id", 80L).containsEntry("revision", 3)
                    .containsEntry("end_at", iso(NOW.minus(Duration.ofMinutes(20))) + "Z")
                    .containsEntry("planned_end_at", iso(NOW.minus(Duration.ofMinutes(10))) + "Z")
                    .containsEntry("start_at", iso(NOW.minus(Duration.ofMinutes(90))) + "Z")
                    .containsEntry("message_tr", "Veritabanı sürüm yükseltmesi").containsEntry("message_en", "DB upgrade")
                    .containsEntry("contact", "BT Destek 1234");
            assertThat(b).containsKeys("start_local", "end_local");
            assertThat(svc.isActive()).as("bitmiş pencere giriş kapısını tetiklemez").isFalse();
            assertThat(svc.notificationsMuted()).isFalse();

            Map<String, Object> p = svc.publicStatus();
            assertThat(p.keySet()).containsExactlyInAnyOrder("state", "start_at", "end_at", "planned_end_at", "start_local",
                    "end_local", "message_tr", "message_en", "contact", "server_now");
            assertThat(p).containsEntry("state", "ended");
            assertThat(p.toString()).doesNotContain("admin").doesNotContain("sessions").doesNotContain("revision");
        }

        @Test
        @DisplayName("bildirim süresi dolunca (61 dk) → none; 0 = kapalı → none ve sorgu eski sınırla (şimdi)")
        void afterNotice_andOff() {
            win(81, -120, -61, 10, 0, false);
            assertThat(svc.clientBlock()).containsEntry("state", "none");
            assertThat(svc.publicStatus()).containsEntry("state", "none");

            stored.clear();
            svc.invalidate();
            win(82, -60, -5, 10, 0, false);
            svc.endedNoticeMinutes = 0;
            assertThat(svc.clientBlock()).containsEntry("state", "none");
            assertThat(svc.publicStatus()).containsEntry("state", "none");
            ArgumentCaptor<String> since = ArgumentCaptor.forClass(String.class);
            verify(repo, org.mockito.Mockito.atLeastOnce()).findByCancelledAtIsNullAndEndAtGreaterThanOrderByStartAtAsc(since.capture());
            assertThat(since.getValue()).isEqualTo(iso(NOW));
        }

        @Test
        @DisplayName("önbellek sorgusu son 60 dk'da bitenleri de getirir (tek sorgu, ek DB turu yok)")
        void cacheQueryLooksBack() {
            win(83, -30, -10, 10, 0, false);
            svc.clientBlock();
            svc.publicStatus();
            svc.isActive();
            verify(repo, times(1)).findByCancelledAtIsNullAndEndAtGreaterThanOrderByStartAtAsc(iso(NOW.minus(Duration.ofMinutes(60))));
        }

        @Test
        @DisplayName("iptal edilen bakım hiçbir zaman 'ended' üretmez")
        void cancelled_never() {
            SystemMaintenanceWindow w = win(84, -40, -10, 10, 0, false);
            w.setCancelledAt(iso(NOW.minus(Duration.ofMinutes(50))));
            assertThat(svc.clientBlock()).containsEntry("state", "none");
            assertThat(svc.publicStatus()).containsEntry("state", "none");
            assertThat(svc.recentlyEnded()).isEmpty();
        }

        @Test
        @DisplayName("duyuru / uyarı / aktif pencere her zaman önce gelir; planlı (duyurusuz) pencere 'ended'i bastırmaz")
        void shownWindowWins() {
            win(85, -70, -10, 10, 0, false);              // 10 dk önce bitti
            win(86, -5, 60, 10, 0, false);                // AKTİF
            assertThat(svc.clientBlock()).containsEntry("state", "active").containsEntry("id", 86L);
            assertThat(svc.publicStatus()).containsEntry("state", "active");

            stored.removeIf(x -> x.getId() == 86L);
            svc.invalidate();
            win(87, 5 * 60, 6 * 60, 10, 24, false);       // 5 sa sonra, duyuru 24 sa → ANNOUNCED
            assertThat(svc.clientBlock()).containsEntry("state", "announced").containsEntry("id", 87L);

            stored.removeIf(x -> x.getId() == 87L);
            svc.invalidate();
            win(88, 3 * 24 * 60, 3 * 24 * 60 + 60, 10, 24, false);   // 3 gün sonra → PLANNED (kullanıcıya görünmez)
            assertThat(svc.clientBlock()).containsEntry("state", "ended").containsEntry("id", 85L);
        }

        @Test
        @DisplayName("birden çok bitmiş pencere → en son biten; 'Hemen bitir' sonrası end_at = şimdi, plan bitişi korunur")
        void latestEnded_andEndNow() {
            win(89, -200, -150, 10, 0, false);   // bildirim süresi dışında
            win(90, -50, -40, 10, 0, false);
            win(91, -30, 40, 10, 0, true);       // AKTİF → hemen bitir
            svc.endNow(91L, admin);
            Map<String, Object> b = svc.clientBlock();
            assertThat(b).containsEntry("state", "ended").containsEntry("id", 91L)
                    .containsEntry("end_at", iso(NOW) + "Z")
                    .containsEntry("planned_end_at", iso(NOW.plus(Duration.ofMinutes(40))) + "Z");
        }

        @Test
        @DisplayName("sınır: 59 dk önce biten görünür, tam 60 dk önce biten görünmez (sorgu end_at > şimdi − 60 dk)")
        void boundary() {
            win(92, -120, -59, 10, 0, false);
            assertThat(svc.recentlyEnded().map(SystemMaintenanceWindow::getId)).contains(92L);
            stored.clear();
            svc.invalidate();
            win(93, -120, -60, 10, 0, false);
            assertThat(svc.recentlyEnded()).isEmpty();
        }
    }

    // ── "Bakım bitince de e-posta gönder" (email_on_end, 2026-10-02) ─────────────────────────────

    @Nested
    @DisplayName("email_on_end — istek ↔ varlık ↔ DTO")
    class EmailOnEnd {

        @Test
        @DisplayName("planla: gövdede yoksa varsayılan AÇIK; false gönderilirse kapalı; DTO'da görünür")
        void schedule_defaultOn_andOff() {
            SystemMaintenanceWindow a = svc.schedule(body("start_local", local(120), "end_local", local(180),
                    "email_all_users", true), admin);
            assertThat(a.getEmailOnEnd()).isTrue();
            assertThat(svc.toDto(a)).containsEntry("email_on_end", true).containsEntry("end_mail_count", 0)
                    .containsEntry("end_mail_status", null).containsEntry("end_mail_at", null);
            SystemMaintenanceWindow b = svc.schedule(body("start_local", local(300), "end_local", local(360),
                    "email_on_end", false), admin);
            assertThat(b.getEmailOnEnd()).isFalse();
            assertThat(svc.toDto(b)).containsEntry("email_on_end", false);
        }

        @Test
        @DisplayName("hemen bakıma al: email_on_end + alıcılar kaydedilir")
        void startNow_roundTrip() {
            when(teamRepo.existsById(3L)).thenReturn(true);
            SystemMaintenanceWindow w = svc.startNow(body("countdown_minutes", 0, "duration_minutes", 30,
                    "email_team_ids", List.of(3), "email_on_end", "false"), admin);
            assertThat(w.getEmailOnEnd()).isFalse();
            assertThat(w.getEmailTeamIds()).isEqualTo("3");
            assertThat(svc.toDto(w)).containsEntry("email_on_end", false).containsEntry("email_team_ids", List.of(3L));
            stored.clear();   // aynı aralıkta ikinci "hemen" bakım çakışırdı
            SystemMaintenanceWindow w2 = svc.startNow(body("countdown_minutes", 0, "duration_minutes", 30), admin);
            assertThat(w2.getEmailOnEnd()).as("varsayılan açık").isTrue();
        }

        @Test
        @DisplayName("düzenle: gövdede yoksa mevcut değer korunur; verilirse değişir")
        void update_keepsOrChanges() {
            SystemMaintenanceWindow w = win(95, 120, 180, 10, 24, false);
            w.setEmailOnEnd(false);
            assertThat(svc.update(95L, body("message_tr", "x"), admin).getEmailOnEnd()).isFalse();
            assertThat(svc.update(95L, body("email_on_end", true), admin).getEmailOnEnd()).isTrue();
        }

        @Test
        @DisplayName("yama öncesi satır (null) açık sayılır")
        void nullIsOn() {
            SystemMaintenanceWindow w = new SystemMaintenanceWindow();
            assertThat(SystemMaintenanceService.emailOnEnd(w)).isTrue();
            w.setEmailOnEnd(false);
            assertThat(SystemMaintenanceService.emailOnEnd(w)).isFalse();
        }
    }
}
