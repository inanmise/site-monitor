package com.sitemonitor.service.oncallroutine;

import com.sitemonitor.config.GlobalExceptionHandler.CodedForbiddenException;
import com.sitemonitor.config.GlobalExceptionHandler.CodedNotFoundException;
import com.sitemonitor.config.GlobalExceptionHandler.FieldValidationException;
import com.sitemonitor.controller.OnCallRoutineController;
import com.sitemonitor.service.AppSettingsService;
import com.sitemonitor.service.AuditService;
import com.sitemonitor.service.PermissionService;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.when;

/**
 * Nöbet rutini denetleyicisi (2026-11-26) — rol / kapsam matrisi gerçek servis + H2 ile: üye okur ve işaretler, USER şablon
 * düzenleyemez, TEAM_ADMIN / kapsamlı yönetici düzenler, AUDIT yalnız okur, başka takım / erişim paylaşımı / 7/24 operatörü
 * takımı GÖRMEZ (404), modül kapalı takımda üye 403 (kurum geneli görüntüleyici okur ama yazamaz), şablon boyu, devralma
 * üyelik kuralı, görsel IDOR + ETag, denetim kayıtları (değişmeyen şablon denetim yazmaz).
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class OnCallRoutineControllerTest {

    static final Long TEAM = OnCallRoutineTestRig.TEAM;
    static final LocalDate THU = LocalDate.parse("2026-11-26");

    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager txm;

    OnCallRoutineTestRig rig;
    final PermissionService ps = mock(PermissionService.class);
    final AuditService audit = mock(AuditService.class);
    OnCallRoutineController ctrl;

    static final Map<String, Set<String>> ROLE_GRANTS = Map.of(
            "ADMIN", Set.of("*"),
            "TEAM_ADMIN", Set.of("oncall_routines.read/view", "oncall_routines.work/edit", "oncall_routines.manage/edit"),
            "USER", Set.of("oncall_routines.read/view", "oncall_routines.work/edit"),
            "AUDIT", Set.of("oncall_routines.read/view"));

    static boolean allows(HttpSession s, String r, String a) {
        Set<String> g = ROLE_GRANTS.get(String.valueOf(s.getAttribute("systemRole")));
        return g != null && (g.contains("*") || g.contains(r + "/" + a));
    }

    @BeforeEach
    void setUp() {
        OnCallRoutineVisibility.setCurrent(null);
        rig = new OnCallRoutineTestRig(jdbc, txm);
        rig.clean();
        when(ps.allows(any(HttpSession.class), anyString(), anyString()))
                .thenAnswer(i -> allows(i.getArgument(0), i.getArgument(1), i.getArgument(2)));
        doAnswer(i -> {
            if (!allows(i.getArgument(0), i.getArgument(1), i.getArgument(2))) throw new SecurityException("yetki yok");
            return null;
        }).when(ps).require(any(HttpSession.class), anyString(), anyString());
        ctrl = new OnCallRoutineController(rig.svc, mock(OnCallRoutineStatsService.class), ps, audit);
    }

    @AfterEach
    void tearDown() {
        OnCallRoutineVisibility.setCurrent(null);
        rig.clean();
    }

    // ── Oturumlar ────────────────────────────────────────────────────────────────────────────

    static MockHttpSession member(long userId, long team, String role) {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("userId", userId);
        s.setAttribute("username", "N" + userId);
        s.setAttribute("displayName", "Kişi " + userId);
        s.setAttribute("systemRole", role);
        s.setAttribute("teamId", team);
        s.setAttribute("memberTeamIds", List.of(team));
        s.setAttribute("viewTeamIds", List.of(team));
        s.setAttribute("manageTeamIds", "TEAM_ADMIN".equals(role) ? List.of(team) : List.of());
        return s;
    }

    static MockHttpSession global(String role) {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("userId", 1L);
        s.setAttribute("username", "admin");
        s.setAttribute("displayName", "Yönetici");
        s.setAttribute("systemRole", role);
        return s;
    }

    /** AD müdürü: takım 7'yi yönetir, kendisi takım 5'in üyesi. */
    static MockHttpSession scopedManager() {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("userId", 30L);
        s.setAttribute("username", "N30");
        s.setAttribute("displayName", "Müdür");
        s.setAttribute("systemRole", "ADMIN");
        s.setAttribute("teamId", 5L);
        s.setAttribute("memberTeamIds", List.of(5L));
        s.setAttribute("viewTeamIds", List.of(5L, TEAM));
        s.setAttribute("manageTeamIds", List.of(TEAM));
        return s;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> data(ResponseEntity<Map<String, Object>> r) {
        assertThat(r.getStatusCode().value()).isEqualTo(200);
        assertThat(r.getBody()).containsEntry("success", true);
        return (Map<String, Object>) r.getBody().get("data");
    }

    long auditCount(String code) {
        return mockingDetails(audit).getInvocations().stream()
                .filter(i -> i.getMethod().getName().equals("recordAction") && code.equals(i.getArgument(0)))
                .count();
    }

    void configure() {
        rig.saveTemplate(OnCallRoutineTestRig.template());
    }

    static Map<String, Object> templateBody(int version) {
        Map<String, Object> b = new HashMap<>();
        b.put("version", version);
        b.put("template", OnCallRoutineTestRig.template());
        return b;
    }

    static Map<String, Object> tick(String task, String state) {
        Map<String, Object> b = new HashMap<>();
        b.put("date", "2026-11-26");
        b.put("task_id", task);
        b.put("period_kind", "DAY");
        b.put("period_key", "2026-11-26");
        b.put("state", state);
        return b;
    }

    static String codeOf(Throwable e) {
        if (e instanceof CodedNotFoundException n) return n.getCode();
        if (e instanceof CodedForbiddenException f) return f.getCode();
        if (e instanceof FieldValidationException v) return v.getCode();
        return null;
    }

    // ── Okuma ────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("okuma: üye, AUDIT, global ve kapsamlı yönetici okur; başka takım, paylaşım ve 7/24 operatörü 404; izinsiz rol 403")
    void readMatrix() {
        configure();
        assertThat(data(ctrl.day(TEAM, "2026-11-26", member(10, TEAM, "USER")))).containsKey("groups");
        assertThat(data(ctrl.day(TEAM, "2026-11-26", global("AUDIT")))).containsEntry("can_work", false);
        assertThat(data(ctrl.day(TEAM, "2026-11-26", global("ADMIN")))).containsEntry("can_edit_template", true);
        assertThat(data(ctrl.day(TEAM, "2026-11-26", scopedManager()))).containsEntry("can_edit_template", true);
        assertThat(data(ctrl.day(TEAM, "2026-11-26", member(10, TEAM, "USER"))))
                .containsEntry("can_work", true).containsEntry("can_edit_template", false);

        assertThatThrownBy(() -> ctrl.day(TEAM, "2026-11-26", member(40, 8L, "USER")))
                .satisfies(e -> assertThat(codeOf(e)).isEqualTo("TEAM_NOT_FOUND"));
        MockHttpSession grant = member(41, 8L, "USER");
        grant.setAttribute("grantViewTeamIds", List.of(TEAM));
        grant.setAttribute("grantEditTeamIds", List.of(TEAM));
        assertThatThrownBy(() -> ctrl.day(TEAM, "2026-11-26", grant))
                .satisfies(e -> assertThat(codeOf(e)).isEqualTo("TEAM_NOT_FOUND"));
        MockHttpSession noc = member(42, 8L, "USER");
        noc.setAttribute("nocOperator", true);
        noc.setAttribute("nocTeamIds", List.of(8L));
        assertThatThrownBy(() -> ctrl.day(TEAM, "2026-11-26", noc))
                .satisfies(e -> assertThat(codeOf(e)).isEqualTo("TEAM_NOT_FOUND"));
        assertThatThrownBy(() -> ctrl.day(TEAM, "2026-11-26", member(43, TEAM, "NONE"))).isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> ctrl.day(TEAM, "26.11.2026", member(10, TEAM, "USER")))
                .isInstanceOf(FieldValidationException.class)
                .satisfies(e -> assertThat(((FieldValidationException) e).getFields()).containsKey("date"));
    }

    // ── Yazma ────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("işaret: üye, kapsamlı yönetici ve global yönetici yazar; AUDIT yazamaz; yanıt 'changed' taşır")
    void workMatrix() {
        configure();
        assertThat(data(ctrl.setItemState(TEAM, tick("d1", "DONE"), member(10, TEAM, "USER")))).containsEntry("changed", true);
        assertThat(data(ctrl.setItemState(TEAM, tick("d1", "DONE"), member(11, TEAM, "USER")))).containsEntry("changed", false);
        assertThat(data(ctrl.setItemState(TEAM, tick("d2", "DONE"), scopedManager()))).containsEntry("changed", true);
        assertThat(data(ctrl.setItemState(TEAM, tick("opt", "DONE"), global("ADMIN")))).containsEntry("changed", true);
        assertThatThrownBy(() -> ctrl.setItemState(TEAM, tick("d1", "PENDING"), global("AUDIT")))
                .isInstanceOf(SecurityException.class);
        assertThat(rig.store.item(TEAM, "DAY", "2026-11-26", "d1").state()).isEqualTo("DONE");
        Map<String, Object> skip = tick("d1", "SKIPPED");
        skip.put("skip_reason", "x");
        assertThatThrownBy(() -> ctrl.setItemState(TEAM, skip, member(10, TEAM, "USER")))
                .isInstanceOf(FieldValidationException.class)
                .satisfies(e -> assertThat(((FieldValidationException) e).getFields()).containsKey("skip_reason"));
    }

    @Test
    @DisplayName("şablon: USER düzenleyemez; TEAM_ADMIN ve kapsamlı yönetici düzenler; değişmeyen kayıt denetim yazmaz")
    void templateMatrixAndAudit() {
        assertThatThrownBy(() -> ctrl.saveTemplate(TEAM, templateBody(0), member(10, TEAM, "USER"), new MockHttpServletRequest()))
                .isInstanceOf(SecurityException.class);
        assertThat(rig.store.team(TEAM)).isNull();
        Map<String, Object> d = data(ctrl.saveTemplate(TEAM, templateBody(0), member(12, TEAM, "TEAM_ADMIN"),
                new MockHttpServletRequest()));
        assertThat(d).containsEntry("template_version", 1).containsEntry("changed", true);
        assertThat(auditCount(OnCallRoutineCodes.AUDIT_TEMPLATE_SAVE)).isEqualTo(1);
        Map<String, Object> same = data(ctrl.saveTemplate(TEAM, templateBody(1), scopedManager(), new MockHttpServletRequest()));
        assertThat(same).containsEntry("changed", false);
        assertThat(auditCount(OnCallRoutineCodes.AUDIT_TEMPLATE_SAVE)).isEqualTo(1);
        Map<String, Object> bad = templateBody(1);
        bad.put("version", "1");
        assertThatThrownBy(() -> ctrl.saveTemplate(TEAM, bad, scopedManager(), new MockHttpServletRequest()))
                .isInstanceOf(FieldValidationException.class)
                .satisfies(e -> assertThat(((FieldValidationException) e).getFields()).containsKey("version"));
        assertThatThrownBy(() -> ctrl.saveTemplate(TEAM, templateBody(1), global("AUDIT"), new MockHttpServletRequest()))
                .isInstanceOf(SecurityException.class);
    }

    @Test
    @DisplayName("şablon gövdesi 512 KB'tan büyükse ayrıştırılmadan reddedilir (alan 'template'), hiçbir şey yazılmaz")
    void templateSizeCap() {
        MockHttpServletRequest big = new MockHttpServletRequest();
        big.setContent(new byte[600 * 1024]);
        assertThatThrownBy(() -> ctrl.saveTemplate(TEAM, templateBody(0), member(12, TEAM, "TEAM_ADMIN"), big))
                .isInstanceOf(FieldValidationException.class)
                .satisfies(e -> assertThat(((FieldValidationException) e).getFields()).containsKey("template"));
        assertThat(rig.store.team(TEAM)).isNull();
        assertThat(auditCount(OnCallRoutineCodes.AUDIT_TEMPLATE_SAVE)).isZero();
    }

    @Test
    @DisplayName("modül takım için kapalıysa: üye 403 ROUTINE_MODULE_HIDDEN; global yönetici okur ama yazamaz")
    void moduleHidden() {
        configure();
        AppSettingsService settings = mock(AppSettingsService.class);
        when(settings.getString(eq(OnCallRoutineVisibility.KEY_TEAM_IDS), anyString())).thenReturn("8");
        OnCallRoutineVisibility.setCurrent(new OnCallRoutineVisibility(settings));
        assertThatThrownBy(() -> ctrl.day(TEAM, "2026-11-26", member(10, TEAM, "USER")))
                .isInstanceOf(CodedForbiddenException.class)
                .satisfies(e -> assertThat(codeOf(e)).isEqualTo("ROUTINE_MODULE_HIDDEN"));
        assertThat(data(ctrl.day(TEAM, "2026-11-26", global("ADMIN")))).containsEntry("can_work", false);
        assertThatThrownBy(() -> ctrl.setItemState(TEAM, tick("d1", "DONE"), global("ADMIN")))
                .satisfies(e -> assertThat(codeOf(e)).isEqualTo("ROUTINE_MODULE_HIDDEN"));
        assertThat(rig.store.item(TEAM, "DAY", "2026-11-26", "d1")).isNull();
    }

    @Test
    @DisplayName("devralma: üye başka üyeyi sorumlu yapar (çizelge yok); üye olmayan kişi ASSIGNEE_NOT_MEMBER; denetim kaydı")
    void assignmentRules() {
        configure();
        rig.person(99L, "N99", "Dışarıdan", true, false);
        Map<String, Object> body = new HashMap<>();
        body.put("scope", "DAY");
        body.put("key", "2026-11-26");
        body.put("user_id", 11);
        @SuppressWarnings("unchecked")
        Map<String, Object> resp = (Map<String, Object>) data(ctrl.assign(TEAM, body, member(10, TEAM, "USER"),
                new MockHttpServletRequest())).get("responsible");
        assertThat(resp).containsEntry("user_id", 11L).containsEntry("source", "TAKEOVER_DAY").containsEntry("is_me", false);
        assertThat(auditCount(OnCallRoutineCodes.AUDIT_TAKEOVER)).isEqualTo(1);

        body.put("user_id", 99);
        assertThatThrownBy(() -> ctrl.assign(TEAM, body, member(10, TEAM, "USER"), new MockHttpServletRequest()))
                .isInstanceOf(FieldValidationException.class)
                .satisfies(e -> assertThat(codeOf(e)).isEqualTo("ASSIGNEE_NOT_MEMBER"));
        body.put("user_id", 11);
        assertThatThrownBy(() -> ctrl.assign(TEAM, body, global("AUDIT"), new MockHttpServletRequest()))
                .isInstanceOf(SecurityException.class);
        assertThat(auditCount(OnCallRoutineCodes.AUDIT_TAKEOVER)).isEqualTo(1);

        Map<String, Object> cleared = data(ctrl.clearAssignment(TEAM, "DAY", "2026-11-26", member(10, TEAM, "USER"),
                new MockHttpServletRequest()));
        assertThat(cleared).containsEntry("deleted", true);
        assertThat(auditCount(OnCallRoutineCodes.AUDIT_TAKEOVER_CLEAR)).isEqualTo(1);
        Map<String, Object> again = data(ctrl.clearAssignment(TEAM, "DAY", "2026-11-26", member(10, TEAM, "USER"),
                new MockHttpServletRequest()));
        assertThat(again).containsEntry("deleted", false);
        assertThat(auditCount(OnCallRoutineCodes.AUDIT_TAKEOVER_CLEAR)).isEqualTo(1);
    }

    @Test
    @DisplayName("devralma notu: tek sınır 200 (kolon) — 201–300 karakter 400 alan 'note', hiçbir şey yazılmaz; 200 kabul, kesilmeden saklanır")
    void assignmentNoteLimit() {
        configure();
        Map<String, Object> body = new HashMap<>();
        body.put("scope", "DAY");
        body.put("key", "2026-11-26");
        body.put("user_id", 11);
        for (int len : new int[] {201, 250, 300}) {
            body.put("note", "n".repeat(len));
            assertThatThrownBy(() -> ctrl.assign(TEAM, body, member(10, TEAM, "USER"), new MockHttpServletRequest()))
                    .as("uzunluk " + len)
                    .isInstanceOf(FieldValidationException.class)
                    .satisfies(e -> assertThat(((FieldValidationException) e).getFields()).containsKey("note"));
        }
        assertThat(rig.count("oncall_routine_assignments")).isZero();
        assertThat(auditCount(OnCallRoutineCodes.AUDIT_TAKEOVER)).isZero();
        String ok = "Ö".repeat(200);
        body.put("note", ok);
        @SuppressWarnings("unchecked")
        Map<String, Object> resp = (Map<String, Object>) data(ctrl.assign(TEAM, body, member(10, TEAM, "USER"),
                new MockHttpServletRequest())).get("responsible");
        @SuppressWarnings("unchecked")
        Map<String, Object> a = (Map<String, Object>) resp.get("assignment");
        assertThat(a).containsEntry("note", ok);
        assertThat(rig.jdbc.queryForObject("SELECT note FROM oncall_routine_assignments WHERE team_id = ?", String.class, TEAM))
                .isEqualTo(ok);
    }

    @Test
    @DisplayName("görsel yükleme: doğrulamada / sınırda reddedilen deneme dakika hakkını harcamaz (inceleme #8); geçerli yükleme yine geçer")
    void uploadRefusedInValidationDoesNotSpendQuota() throws Exception {
        configure();
        var created = rig.svc.createProblem(rig.ctx(), THU, "d1", "DAY", "2026-11-26", "HIGH", "Kuyruk birikti", false,
                OnCallRoutineTestRig.A);
        long pid = created.problem().id();
        org.springframework.mock.web.MockMultipartFile notImage = new org.springframework.mock.web.MockMultipartFile(
                "file", "bozuk.png", "image/png", "bu bir görsel değil".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        MockHttpSession s = member(10, TEAM, "USER");
        for (int i = 0; i < 35; i++) {   // sınır dakikada 30: reddedilenler sayılsaydı 31.'de 429 olurdu
            assertThatThrownBy(() -> ctrl.uploadProblemImage(pid, notImage, s))
                    .as("deneme " + i).isInstanceOf(IllegalArgumentException.class);
        }
        org.springframework.mock.web.MockMultipartFile png = new org.springframework.mock.web.MockMultipartFile(
                "file", "ekran.png", "image/png", OnCallRoutineTestRig.png(20));
        ResponseEntity<Map<String, Object>> ok = ctrl.uploadProblemImage(pid, png, s);
        assertThat(ok.getStatusCode().value()).isEqualTo(200);
        // sorun başına 4 görsel sınırı: sınırda reddedilen deneme de sayılmaz
        for (int i = 0; i < 3; i++) assertThat(ctrl.uploadProblemImage(pid, png, s).getStatusCode().value()).isEqualTo(200);
        for (int i = 0; i < 30; i++) {
            assertThatThrownBy(() -> ctrl.uploadProblemImage(pid, png, s)).as("sınır " + i)
                    .isInstanceOf(com.sitemonitor.config.GlobalExceptionHandler.CodedConflictException.class);
        }
        // 4 başarılı yükleme sayıldı; başka soruna yükleme hâlâ serbest
        var other = rig.svc.createProblem(rig.ctx(), THU, null, null, null, "LOW", "Genel bir gözlem", false,
                OnCallRoutineTestRig.A);
        assertThat(ctrl.uploadProblemImage(other.problem().id(), png, s).getStatusCode().value()).isEqualTo(200);
    }

    @Test
    @DisplayName("sorun görseli: başka takımın görseli IMAGE_NOT_FOUND (varlık sızmaz); ETag + nosniff; If-None-Match → 304")
    void problemImageScope() {
        configure();
        var created = rig.svc.createProblem(rig.ctx(), THU, "d1", "DAY", "2026-11-26", "HIGH", "Kuyruk birikti", false,
                OnCallRoutineTestRig.A);
        long pid = created.problem().id();
        long imageId = rig.svc.uploadImage(created.problem(), OnCallRoutineTestRig.png(20), "ekran.png",
                OnCallRoutineTestRig.A).id();
        assertThatThrownBy(() -> ctrl.image(pid, imageId, null, member(40, 8L, "USER")))
                .isInstanceOf(CodedNotFoundException.class)
                .satisfies(e -> assertThat(codeOf(e)).isEqualTo("IMAGE_NOT_FOUND"));
        ResponseEntity<byte[]> ok = ctrl.image(pid, imageId, null, member(10, TEAM, "USER"));
        assertThat(ok.getStatusCode().value()).isEqualTo(200);
        assertThat(ok.getBody()).isNotEmpty();
        assertThat(ok.getHeaders().getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
        String etag = ok.getHeaders().getETag();
        assertThat(etag).startsWith("\"or-img-" + imageId + "-");
        assertThat(ctrl.image(pid, imageId, etag, member(10, TEAM, "USER")).getStatusCode().value()).isEqualTo(304);
        // görsel başka sorunun kimliğiyle istenirse yok sayılır
        var other = rig.svc.createProblem(rig.ctx(), THU, null, null, null, "LOW", "Genel", false, OnCallRoutineTestRig.A);
        assertThatThrownBy(() -> ctrl.image(other.problem().id(), imageId, null, member(10, TEAM, "USER")))
                .satisfies(e -> assertThat(codeOf(e)).isEqualTo("IMAGE_NOT_FOUND"));
        assertThatThrownBy(() -> ctrl.image(pid + 1000, imageId, null, member(10, TEAM, "USER")))
                .satisfies(e -> assertThat(codeOf(e)).isEqualTo("IMAGE_NOT_FOUND"));
    }

    @Test
    @DisplayName("gün tamamla: yanıt gün + ilerleme + bildirim durumu; denetim kaydı bir kez; skip_pending nesne olmalı")
    void completeDay() {
        configure();
        ctrl.setItemState(TEAM, tick("d1", "DONE"), member(10, TEAM, "USER"));
        ctrl.setItemState(TEAM, tick("d2", "DONE"), member(10, TEAM, "USER"));
        Map<String, Object> body = new HashMap<>();
        body.put("version", 0);
        Map<String, Object> d = data(ctrl.completeDay(TEAM, "2026-11-26", body, member(10, TEAM, "USER"),
                new MockHttpServletRequest()));
        assertThat(d).containsKeys("day", "progress", "notice");
        @SuppressWarnings("unchecked")
        Map<String, Object> day = (Map<String, Object>) d.get("day");
        assertThat(day).containsEntry("status", "COMPLETED");
        assertThat(auditCount(OnCallRoutineCodes.AUDIT_DAY_COMPLETE)).isEqualTo(1);
        Map<String, Object> skip = new HashMap<>();
        skip.put("version", 1);
        skip.put("skip_pending", "neden");
        assertThatThrownBy(() -> ctrl.completeDay(TEAM, "2026-11-26", skip, member(10, TEAM, "USER"), new MockHttpServletRequest()))
                .isInstanceOf(FieldValidationException.class)
                .satisfies(e -> assertThat(((FieldValidationException) e).getFields()).containsKey("skip_pending.reason"));
        assertThat(auditCount(OnCallRoutineCodes.AUDIT_DAY_COMPLETE)).isEqualTo(1);
        assertThatThrownBy(() -> ctrl.completeDay(TEAM, "2026-11-26", body, global("AUDIT"), new MockHttpServletRequest()))
                .isInstanceOf(SecurityException.class);
    }
}
