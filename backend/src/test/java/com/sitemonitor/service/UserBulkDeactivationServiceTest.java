package com.sitemonitor.service;

import com.sitemonitor.model.AppUser;
import com.sitemonitor.model.UserBulkOperation;
import com.sitemonitor.repository.AppUserRepository;
import com.sitemonitor.repository.CertificateInventoryRepository;
import com.sitemonitor.repository.EscalationContactRepository;
import com.sitemonitor.repository.PasswordHistoryRepository;
import com.sitemonitor.repository.TeamRepository;
import com.sitemonitor.repository.UserBulkOperationRepository;
import com.sitemonitor.service.UserBulkDeactivationService.Criteria;
import com.sitemonitor.service.retention.RetentionCatalog;
import com.sitemonitor.service.retention.RetentionPolicy;
import jakarta.persistence.Column;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.mock.web.MockHttpSession;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Sistem geneli toplu pasife alma (2026-10-02, kullanıcı kararı). Hedef: ölçüt birleşimi, sunucu tarafı dışlamalar
 * (global + kapsamlı ADMIN, kendisi, zaten pasif), uygulamanın GERÇEK {@link UserService#updateUser} yolundan geçmesi
 * (TERMINATED sentinel + remember-me silme — UserService gerçek, depolar sahte), 409 liste değişti, uygulama anında
 * ADMIN olmuş kullanıcının atlanması, işlem kaydı ve geri alma kuralları (yalnız bu işlemin hâlâ pasif ve işareti
 * değişmemiş kullanıcıları; ikinci geri alma 409).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class UserBulkDeactivationServiceTest {

    @Mock AppUserRepository userRepo;
    @Mock TeamRepository teamRepo;
    @Mock CertificateInventoryRepository inventoryRepo;
    @Mock EscalationContactRepository contactRepo;
    @Mock PasswordHistoryRepository passwordHistoryRepo;
    @Mock RememberMeService rememberMeService;
    @Mock InactiveRecipientGuard guard;
    @Mock UserBulkOperationRepository opRepo;
    @Mock AuditService auditService;

    private static final Instant NOW = Instant.parse("2026-10-02T12:00:00Z");
    private static final long OPERATOR = 1L;

    private final Map<Long, AppUser> db = new LinkedHashMap<>();
    private UserService userService;
    private UserBulkDeactivationService service;
    private MockHttpSession session;
    private final List<UserBulkOperation> savedOps = new ArrayList<>();

    @BeforeEach
    void setUp() {
        userService = new UserService(userRepo, teamRepo, inventoryRepo, contactRepo, passwordHistoryRepo);
        userService.setRememberMeService(rememberMeService);
        userService.setInactiveRecipientGuard(guard);
        service = new UserBulkDeactivationService(userRepo, opRepo, userService, auditService);
        service.setClock(Clock.fixed(NOW, ZoneOffset.UTC));

        when(userRepo.findBulkCandidateRows()).thenAnswer(i -> rows());
        when(userRepo.findAllTeamMembershipPairs()).thenAnswer(i -> pairs());
        when(userRepo.findById(anyLong())).thenAnswer(i -> Optional.ofNullable(db.get((Long) i.getArgument(0))));
        when(userRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        when(opRepo.save(any())).thenAnswer(i -> {
            UserBulkOperation op = i.getArgument(0);
            if (op.getId() == null) op.setId(77L);
            savedOps.add(op);
            return op;
        });
        when(opRepo.findByKindAndIdGreaterThanAndUndoneAtIsNull(anyString(), anyLong())).thenReturn(List.of());

        session = new MockHttpSession();
        session.setAttribute("userId", OPERATOR);
        session.setAttribute("username", "ADMIN1");
        session.setAttribute("systemRole", "ADMIN");

        // Operatör (global ADMIN), kapsamlı müdür (AD ADMIN — DB'de de systemRole=ADMIN), pasif biri, hedefler.
        add(1, "ADMIN1", "ADMIN", true, "LOCAL", "2026-10-02T10:00:00", "2025-01-01T00:00:00", 1L);
        add(2, "MUDUR", "ADMIN", true, "LDAP", "2026-01-01T00:00:00", "2025-01-01T00:00:00", 5L);
        add(3, "ALICE", "USER", true, "LDAP", "2026-01-01T00:00:00", "2025-01-01T00:00:00", 5L);
        add(4, "BOB", "TEAM_ADMIN", true, "LOCAL", "2026-09-30T00:00:00", "2025-01-01T00:00:00", 6L, 5L);
        add(5, "CAROL", "AUDIT", true, "LOCAL", null, "2025-01-01T00:00:00", 7L);
        add(6, "DAVE", "USER", false, "LDAP", "2025-01-01T00:00:00", "2024-01-01T00:00:00", 5L);
        add(7, "ERIN", "USER", true, "LOCAL", null, "2026-09-30T00:00:00", 7L);
    }

    private AppUser add(long id, String username, String role, boolean active, String source, String lastLogin,
                        String created, Long team, Long... extra) {
        AppUser u = new AppUser();
        u.setId(id);
        u.setUsername(username);
        u.setDisplayName(username.charAt(0) + username.substring(1).toLowerCase());
        u.setSystemRole(role);
        u.setActive(active);
        u.setAuthSource(source);
        u.setLastLoginAt(lastLogin);
        u.setCreatedAt(created);
        u.setTeamId(team);
        LinkedHashSet<Long> teams = new LinkedHashSet<>();
        if (team != null) teams.add(team);
        teams.addAll(List.of(extra));
        u.setTeamIds(teams);
        u.setActiveSessionId("LIVE-" + id);
        db.put(id, u);
        return u;
    }

    private List<Object[]> rows() {
        List<Object[]> out = new ArrayList<>();
        for (AppUser u : db.values()) {
            out.add(new Object[]{u.getId(), u.getUsername(), u.getDisplayName(), u.getEmail(), u.getSystemRole(),
                    u.getAuthSource(), u.getLastLoginAt(), u.getCreatedAt(), u.getActive(), u.getTeamId(), u.getEmployeeId()});
        }
        return out;
    }

    private List<Object[]> pairs() {
        List<Object[]> out = new ArrayList<>();
        for (AppUser u : db.values()) for (Long t : u.getTeamIds()) out.add(new Object[]{u.getId(), t});
        return out;
    }

    private static Map<String, Object> crit(Object... kv) {
        Map<String, Object> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return Map.of("criteria", m);
    }

    @SuppressWarnings("unchecked")
    private static List<Long> targetIds(Map<String, Object> preview) {
        return ((List<Map<String, Object>>) preview.get("targets")).stream().map(r -> (Long) r.get("id")).toList();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> excluded(Map<String, Object> preview) {
        return (Map<String, Object>) preview.get("excluded");
    }

    // ── Önizleme: dışlamalar ────────────────────────────────────────────────

    @Test
    @DisplayName("Önizleme: global ve kapsamlı ADMIN, işlemi yapan kişi ve zaten pasif olan her zaman HARİÇ; sayıları raporlanır")
    void preview_alwaysExcluded() {
        Map<String, Object> p = service.preview(Criteria.from(crit()), OPERATOR);

        assertThat(targetIds(p)).containsExactlyInAnyOrder(3L, 4L, 5L, 7L);
        assertThat(excluded(p)).containsEntry("admins", 1).containsEntry("self", 1).containsEntry("already_inactive", 1);
        assertThat(p.get("total")).isEqualTo(4);
        assertThat(p.get("over_limit")).isEqualTo(false);
    }

    @Test
    @DisplayName("Önizleme satırı: ad, kullanıcı adı, takımlar (birincil + üyelik), kaynak, son giriş, rol")
    @SuppressWarnings("unchecked")
    void preview_rowShape() {
        Map<String, Object> p = service.preview(Criteria.from(crit("scope", "teams", "team_ids", List.of(5))), OPERATOR);
        Map<String, Object> bob = ((List<Map<String, Object>>) p.get("targets")).stream()
                .filter(r -> r.get("id").equals(4L)).findFirst().orElseThrow();
        assertThat(bob).containsEntry("username", "BOB").containsEntry("system_role", "TEAM_ADMIN")
                .containsEntry("auth_source", "LOCAL").containsEntry("last_login_at", "2026-09-30T00:00:00");
        assertThat((List<Long>) bob.get("team_ids")).containsExactly(6L, 5L);
    }

    // ── Önizleme: ölçüt birleşimleri ────────────────────────────────────────

    @Test
    @DisplayName("Takım kapsamı: birincil takım YA DA çoklu üyelik eşleşir; diğer takımlar dışarıda")
    void criteria_teams() {
        Map<String, Object> p = service.preview(Criteria.from(crit("scope", "teams", "team_ids", List.of(5))), OPERATOR);
        assertThat(targetIds(p)).containsExactlyInAnyOrder(3L, 4L);   // ALICE birincil 5, BOB ek üyelik 5
        assertThat(excluded(p)).containsEntry("admins", 1).containsEntry("self", 0).containsEntry("already_inactive", 1);
    }

    @Test
    @DisplayName("N gündür girmeyen: eski girişliler + hiç girmemiş ESKİ hesap dâhil; yeni açılmış hiç-girmemiş ve yakın girişli hariç")
    void criteria_inactiveDays_includesNeverLoggedInOldAccounts() {
        Map<String, Object> p = service.preview(Criteria.from(crit("inactive_days", 30)), OPERATOR);
        assertThat(targetIds(p)).containsExactly(3L, 5L);   // ALICE (Ocak), CAROL (hiç, 2025'te açıldı); ERIN 2 gün önce açıldı
        assertThat(p.get("inactive_cutoff")).isEqualTo("2026-09-02T12:00:00Z");
    }

    @Test
    @DisplayName("'Hiç giriş yapmamış olanlar dâhil' kapalıysa hiç girmemiş hesaplar eşleşmez")
    void criteria_inactiveDays_excludeNever() {
        Map<String, Object> p = service.preview(Criteria.from(crit("inactive_days", 30, "include_never_logged_in", false)), OPERATOR);
        assertThat(targetIds(p)).containsExactly(3L);
    }

    @Test
    @DisplayName("Okunamayan son giriş damgası eşleşmez (yanlış veriyle hesap kapanmaz)")
    void criteria_badStamp_notMatched() {
        db.get(3L).setLastLoginAt("dün");
        Map<String, Object> p = service.preview(Criteria.from(crit("inactive_days", 30)), OPERATOR);
        assertThat(targetIds(p)).doesNotContain(3L);
    }

    @Test
    @DisplayName("Hesap kaynağı: LDAP / LOCAL; rol süzgeci; hepsi VE ile birleşir")
    void criteria_sourceAndRole() {
        assertThat(targetIds(service.preview(Criteria.from(crit("auth_source", "LDAP")), OPERATOR))).containsExactly(3L);
        assertThat(targetIds(service.preview(Criteria.from(crit("auth_source", "local")), OPERATOR)))
                .containsExactlyInAnyOrder(4L, 5L, 7L);
        assertThat(targetIds(service.preview(Criteria.from(crit("system_role", "AUDIT")), OPERATOR))).containsExactly(5L);
        // Takım 5|7 VE yerel VE 3 gündür girmeyen: BOB 2 gün önce girdi, ERIN 2 gün önce açıldı → yalnız CAROL
        assertThat(targetIds(service.preview(Criteria.from(
                crit("scope", "teams", "team_ids", List.of(5, 7), "auth_source", "LOCAL", "inactive_days", 3)), OPERATOR)))
                .containsExactly(5L);
        // 1 gün: hepsi eski → BOB, CAROL, ERIN (ALICE LDAP olduğu için dışarıda)
        assertThat(targetIds(service.preview(Criteria.from(
                crit("scope", "teams", "team_ids", List.of(5, 7), "auth_source", "LOCAL", "inactive_days", 1)), OPERATOR)))
                .containsExactlyInAnyOrder(4L, 5L, 7L);
    }

    @Test
    @DisplayName("Ölçüt doğrulaması: ADMIN rolü, takımsız takım kapsamı, aralık dışı gün, bilinmeyen kaynak → 400")
    void criteria_validation() {
        assertThatThrownBy(() -> Criteria.from(crit("system_role", "ADMIN"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Criteria.from(crit("scope", "teams"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Criteria.from(crit("inactive_days", 0))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Criteria.from(crit("inactive_days", 99999))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Criteria.from(crit("auth_source", "AD"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Criteria.from(crit("scope", "everyone"))).isInstanceOf(IllegalArgumentException.class);
        Criteria c = Criteria.from(crit());
        assertThat(c.scope()).isEqualTo("all");
        assertThat(c.includeNeverLoggedIn()).isTrue();
        assertThat(c.authSource()).isEqualTo("ALL");
    }

    // ── Uygulama ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Uygulama: her hedef UserService.updateUser(active=false) yolundan geçer — TERMINATED sentinel + remember-me silinir")
    void apply_goesThroughUpdateUser() {
        Map<String, Object> res = service.apply(Criteria.from(crit()), 4, "  yıllık temizlik  ", session);

        assertThat(res).containsEntry("ok", 4).containsEntry("failed", 0).containsEntry("skipped", 0).containsEntry("operation_id", 77L);
        for (long id : new long[]{3, 4, 5, 7}) {
            AppUser u = db.get(id);
            assertThat(u.getActive()).as("user %s", id).isFalse();
            assertThat(u.getActiveSessionId()).startsWith(UserService.SESSION_TERMINATED_PREFIX);
            verify(rememberMeService).invalidateAllForUser(u.getUsername());
        }
        // Dışlananlara dokunulmaz
        assertThat(db.get(1L).getActive()).isTrue();
        assertThat(db.get(2L).getActive()).isTrue();
        verify(rememberMeService, never()).invalidateAllForUser("ADMIN1");
        verify(rememberMeService, never()).invalidateAllForUser("MUDUR");
        // Kullanıcı başına USER_UPDATE (fark) + bir özet
        verify(auditService, times(4)).recordAction(eq("USER_UPDATE"), any(jakarta.servlet.http.HttpSession.class),
                eq("USER"), anyString(), contains("bulk_deactivate"), contains("active"));
        verify(auditService).recordAction(eq("USER_BULK_DEACTIVATE"), any(jakarta.servlet.http.HttpSession.class),
                eq("USER_BULK_OPERATION"), eq("77"), contains("\"ok\":4"), isNull());
    }

    @Test
    @DisplayName("İşlem kaydı: yönetici, ölçüt, not, pasife alınan kimlikler, kişi başına işaret, sayılar, DONE")
    void apply_persistsOperation() {
        service.apply(Criteria.from(crit("scope", "teams", "team_ids", List.of(5))), 2, "not", session);

        UserBulkOperation op = savedOps.get(savedOps.size() - 1);
        assertThat(op.getKind()).isEqualTo(UserBulkOperation.KIND_DEACTIVATE);
        assertThat(op.getStatus()).isEqualTo(UserBulkOperation.STATUS_DONE);
        assertThat(op.getActor()).isEqualTo("ADMIN1");
        assertThat(op.getActorId()).isEqualTo(OPERATOR);
        assertThat(op.getNote()).isEqualTo("not");
        assertThat(op.getCriteriaJson()).contains("\"scope\":\"teams\"").contains("\"team_ids\":[5]");
        assertThat(UserBulkDeactivationService.parseIds(op.getUserIds())).containsExactlyInAnyOrder(3L, 4L);
        assertThat(op.getMarkers()).contains(db.get(3L).getActiveSessionId()).contains(db.get(4L).getActiveSessionId());
        assertThat(op.getTargetCount()).isEqualTo(2);
        assertThat(op.getOkCount()).isEqualTo(2);
        assertThat(op.getCreatedAt()).isEqualTo("2026-10-02T12:00:00Z");
        assertThat(op.getFinishedAt()).isNotNull();
    }

    @Test
    @DisplayName("Onaylanan sayı ≠ yeniden hesaplanan → ListChangedException (409), HİÇBİR kullanıcıya dokunulmaz, kayıt açılmaz")
    void apply_countMismatch_409() {
        assertThatThrownBy(() -> service.apply(Criteria.from(crit()), 5, null, session))
                .isInstanceOf(UserBulkDeactivationService.ListChangedException.class)
                .isInstanceOf(IllegalStateException.class)
                .satisfies(e -> assertThat(((UserBulkDeactivationService.ListChangedException) e).currentCount()).isEqualTo(4))
                .hasMessageContaining("önizlemeyi yenileyin");
        assertThat(db.values()).filteredOn(u -> u.getId() > 2 && u.getId() != 6).allMatch(AppUser::getActive);
        verify(opRepo, never()).save(any());
        verify(rememberMeService, never()).invalidateAllForUser(anyString());
    }

    @Test
    @DisplayName("Önizleme ile uygulama arasında ADMIN olan kullanıcı atlanır (taze kayıtta yeniden denetim)")
    void apply_userBecameAdmin_skipped() {
        List<Object[]> frozen = rows();               // sayım anındaki (bayat) projeksiyon
        when(userRepo.findBulkCandidateRows()).thenReturn(frozen);
        db.get(3L).setSystemRole("ADMIN");           // ALICE bu arada ADMIN yapıldı

        Map<String, Object> res = service.apply(Criteria.from(crit()), 4, null, session);

        assertThat(res).containsEntry("ok", 3).containsEntry("skipped", 1);
        assertThat(db.get(3L).getActive()).isTrue();
        verify(rememberMeService, never()).invalidateAllForUser("ALICE");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> skipped = (List<Map<String, Object>>) res.get("skipped_rows");
        assertThat(skipped).singleElement().satisfies(r -> {
            assertThat(r.get("id")).isEqualTo(3L);
            assertThat(r.get("reason")).isEqualTo(UserBulkDeactivationService.SKIP_NOW_ADMIN);
        });
        assertThat(UserBulkDeactivationService.parseIds(savedOps.get(savedOps.size() - 1).getUserIds())).doesNotContain(3L);
    }

    @Test
    @DisplayName("Bir kullanıcının hatası işlemi durdurmaz — satır hatası raporlanır, diğerleri sürer")
    void apply_perUserFailure_continues() {
        when(userRepo.save(any())).thenAnswer(i -> {
            AppUser u = i.getArgument(0);
            if ("BOB".equals(u.getUsername())) throw new IllegalStateException("db patladı");
            return u;
        });

        Map<String, Object> res = service.apply(Criteria.from(crit()), 4, null, session);

        assertThat(res).containsEntry("ok", 3).containsEntry("failed", 1);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> failures = (List<Map<String, Object>>) res.get("failures");
        assertThat(failures).singleElement().satisfies(f -> {
            assertThat(f.get("username")).isEqualTo("BOB");
            assertThat(f.get("error")).isEqualTo("db patladı");
        });
    }

    @Test
    @DisplayName("Sınır: 5000'den fazla hedef → 400 (ölçütü daraltın); hedef yok → 400; not 500'ü aşamaz")
    void apply_limits() {
        for (long id = 100; id < 100 + UserBulkDeactivationService.MAX_TARGETS + 1; id++) {
            add(id, "U" + id, "USER", true, "LOCAL", null, "2020-01-01T00:00:00", 9L);
        }
        int total = UserBulkDeactivationService.MAX_TARGETS + 1 + 4;
        Map<String, Object> p = service.preview(Criteria.from(crit()), OPERATOR);
        assertThat(p.get("over_limit")).isEqualTo(true);
        assertThat(p.get("total")).isEqualTo(total);
        assertThat((List<?>) p.get("targets")).hasSize(UserBulkDeactivationService.MAX_TARGETS);
        assertThatThrownBy(() -> service.apply(Criteria.from(crit()), total, null, session))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("5000");
        assertThatThrownBy(() -> service.apply(Criteria.from(crit("system_role", "USER", "auth_source", "LDAP",
                "scope", "teams", "team_ids", List.of(42))), 0, null, session))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.apply(Criteria.from(crit()), total, "x".repeat(501), session))
                .isInstanceOf(IllegalArgumentException.class);
        verify(opRepo, never()).save(any());
    }

    // ── Geri alma ───────────────────────────────────────────────────────────

    private UserBulkOperation doneOp(long id, String userIds, String markers) {
        UserBulkOperation op = new UserBulkOperation();
        op.setId(id);
        op.setKind(UserBulkOperation.KIND_DEACTIVATE);
        op.setStatus(UserBulkOperation.STATUS_DONE);
        op.setUserIds(userIds);
        op.setMarkers(markers);
        op.setOkCount(5);
        when(opRepo.findById(id)).thenReturn(Optional.of(op));
        return op;
    }

    @Test
    @DisplayName("Geri alma: YALNIZ bu işlemin hâlâ pasif + işareti değişmemiş + sonraki işlemde olmayan + ADMIN olmamış kullanıcıları açar")
    void undo_rules() {
        AppUser a = add(10, "U10", "USER", false, "LOCAL", null, null, 5L);
        a.setActiveSessionId("TERMINATED:m10");
        add(11, "U11", "USER", true, "LOCAL", null, null, 5L);                                  // bu arada açılmış
        add(12, "U12", "USER", false, "LOCAL", null, null, 5L).setActiveSessionId("TERMINATED:new"); // sonradan yeniden pasif
        add(13, "U13", "USER", false, "LOCAL", null, null, 5L).setActiveSessionId("TERMINATED:m13"); // sonraki toplu işlemde
        add(14, "U14", "ADMIN", false, "LOCAL", null, null, 5L).setActiveSessionId("TERMINATED:m14"); // rolü ADMIN olmuş
        doneOp(50L, "[10,11,12,13,14]",
                "{\"10\":\"TERMINATED:m10\",\"11\":\"TERMINATED:m11\",\"12\":\"TERMINATED:m12\",\"13\":\"TERMINATED:m13\",\"14\":\"TERMINATED:m14\"}");
        UserBulkOperation later = new UserBulkOperation();
        later.setId(60L);
        later.setUserIds("[13]");
        when(opRepo.findByKindAndIdGreaterThanAndUndoneAtIsNull(UserBulkOperation.KIND_DEACTIVATE, 50L)).thenReturn(List.of(later));
        when(opRepo.markUndone(eq(50L), anyString(), eq("ADMIN1"), eq(OPERATOR))).thenReturn(1);

        Map<String, Object> res = service.undo(50L, session);

        assertThat(res).containsEntry("ok", 1).containsEntry("skipped", 4).containsEntry("failed", 0);
        assertThat(db.get(10L).getActive()).isTrue();
        assertThat(db.get(12L).getActive()).isFalse();
        assertThat(db.get(13L).getActive()).isFalse();
        assertThat(db.get(14L).getActive()).isFalse();
        @SuppressWarnings("unchecked")
        Map<Object, Object> reasons = new HashMap<>();
        for (Map<String, Object> r : (List<Map<String, Object>>) res.get("skipped_rows")) reasons.put(r.get("id"), r.get("reason"));
        assertThat(reasons).containsEntry(11L, UserBulkDeactivationService.SKIP_ALREADY_ACTIVE)
                .containsEntry(12L, UserBulkDeactivationService.SKIP_CHANGED_SINCE)
                .containsEntry(13L, UserBulkDeactivationService.SKIP_LATER_OPERATION)
                .containsEntry(14L, UserBulkDeactivationService.SKIP_NOW_ADMIN);
        verify(opRepo).recordUndoResult(50L, 1, 4, 0);
        verify(auditService).recordAction(eq("USER_BULK_DEACTIVATE_UNDO"), any(jakarta.servlet.http.HttpSession.class),
                eq("USER_BULK_OPERATION"), eq("50"), contains("\"reactivated\":1"), isNull());
        // Yeniden aktifleştirme token silmez
        verify(rememberMeService, never()).invalidateAllForUser("U10");
    }

    @Test
    @DisplayName("Geri alma idempotent: ikinci çağrı 409 (IllegalStateException), kullanıcılara ikinci kez dokunulmaz")
    void undo_twice_409() {
        add(10, "U10", "USER", false, "LOCAL", null, null, 5L).setActiveSessionId("TERMINATED:m10");
        doneOp(50L, "[10]", "{\"10\":\"TERMINATED:m10\"}");
        when(opRepo.markUndone(eq(50L), anyString(), any(), any())).thenReturn(1, 0);

        service.undo(50L, session);
        db.get(10L).setActive(false);   // (başka bir yol yeniden kapatmış olsa bile)

        assertThatThrownBy(() -> service.undo(50L, session)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("zaten geri alındı");
        assertThat(db.get(10L).getActive()).isFalse();
    }

    @Test
    @DisplayName("Geri alma: zaten geri alınmış / bitmemiş işlem 409, bilinmeyen işlem 404")
    void undo_guards() {
        UserBulkOperation op = doneOp(50L, "[]", "{}");
        op.setUndoneAt("2026-10-02T12:00:00Z");
        assertThatThrownBy(() -> service.undo(50L, session)).isInstanceOf(IllegalStateException.class);
        op.setUndoneAt(null);
        op.setStatus(UserBulkOperation.STATUS_RUNNING);
        assertThatThrownBy(() -> service.undo(50L, session)).isInstanceOf(IllegalStateException.class);
        when(opRepo.findById(99L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.undo(99L, session)).isInstanceOf(java.util.NoSuchElementException.class);
        verify(opRepo, never()).markUndone(anyLong(), anyString(), any(), any());
    }

    @Test
    @DisplayName("Uç uca: uygula → geri al — işlemin pasife aldığı herkes yeniden aktif, işaretler eşleşir")
    void apply_thenUndo_roundTrip() {
        service.apply(Criteria.from(crit("scope", "teams", "team_ids", List.of(5))), 2, null, session);
        UserBulkOperation op = savedOps.get(savedOps.size() - 1);
        when(opRepo.findById(77L)).thenReturn(Optional.of(op));
        when(opRepo.markUndone(eq(77L), anyString(), any(), any())).thenReturn(1);

        Map<String, Object> res = service.undo(77L, session);

        assertThat(res).containsEntry("ok", 2).containsEntry("skipped", 0);
        assertThat(db.get(3L).getActive()).isTrue();
        assertThat(db.get(4L).getActive()).isTrue();
    }

    @Test
    @DisplayName("Geçmiş: son işlemler özetlenir; can_undo yalnız bitmiş, geri alınmamış ve en az bir kişiyi kapatmış işlemde")
    void history_summary() {
        UserBulkOperation a = doneOp(2L, "[3]", "{}");
        a.setCriteriaJson("{\"scope\":\"all\"}");
        a.setOkCount(1);
        UserBulkOperation b = doneOp(1L, "[4]", "{}");
        b.setUndoneAt("2026-10-02T12:00:00Z");
        when(opRepo.findTop20ByOrderByIdDesc()).thenReturn(List.of(a, b));

        List<Map<String, Object>> h = service.history();

        assertThat(h).hasSize(2);
        assertThat(h.get(0)).containsEntry("id", 2L).containsEntry("can_undo", true);
        assertThat(h.get(0).get("criteria")).isEqualTo(Map.of("scope", "all"));
        assertThat(h.get(1)).containsEntry("can_undo", false);
        assertThat(h.get(0)).doesNotContainKey("markers").doesNotContainKey("user_ids");
    }

    // ── Şema yaması + saklama ───────────────────────────────────────────────

    @Test
    @DisplayName("Şema yaması: user_bulk_operations applySchemaPatches'te açıkça kurulur ve entity'nin HER kolonunu içerir; saklama BOUNDED")
    void schemaPatchAndRetentionPresent() throws Exception {
        String src = Files.readString(Path.of("src", "main", "java", "com", "sitemonitor", "service", "SchedulerService.java"),
                StandardCharsets.UTF_8);
        int start = src.indexOf("CREATE TABLE IF NOT EXISTS user_bulk_operations(");
        assertThat(start).as("user_bulk_operations yaması").isGreaterThan(0);
        String block = src.substring(start, src.indexOf("\"\"\"", start));
        Set<String> cols = new java.util.HashSet<>();
        for (Field f : UserBulkOperation.class.getDeclaredFields()) {
            Column c = f.getAnnotation(Column.class);
            if (c != null) cols.add(c.name());
        }
        assertThat(cols).hasSizeGreaterThan(15);
        for (String c : cols) assertThat(block).as("yamada kolon %s", c).contains(c + " ");
        assertThat(block).contains("id BIGSERIAL PRIMARY KEY");

        assertThat(RetentionCatalog.byId("user-bulk-operations")).hasValueSatisfying(p -> {
            assertThat(p.table()).isEqualTo("user_bulk_operations");
            assertThat(p.mode()).isEqualTo(RetentionPolicy.Mode.BOUNDED);
        });
    }
}
