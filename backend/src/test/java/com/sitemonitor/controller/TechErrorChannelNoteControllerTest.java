package com.sitemonitor.controller;

import com.sitemonitor.config.GlobalExceptionHandler.CodedConflictException;
import com.sitemonitor.config.GlobalExceptionHandler.FieldValidationException;
import com.sitemonitor.model.TechErrorChannel;
import com.sitemonitor.model.TechErrorChannelNote;
import com.sitemonitor.model.TechErrorChannelNoteImage;
import com.sitemonitor.model.TechErrorChannelNoteVersion;
import com.sitemonitor.repository.TechErrorChannelNoteImageRepository;
import com.sitemonitor.repository.TechErrorChannelNoteVersionRepository;
import com.sitemonitor.service.AuditService;
import com.sitemonitor.service.PermissionService;
import com.sitemonitor.service.RememberMeService;
import com.sitemonitor.service.UserService;
import com.sitemonitor.service.techerror.TechErrorChannelNoteService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Kanal notu uçları (2026-11-20): notu kanalın TAKIMI düzenler (üye + yönetici + global yönetici); AUDIT, kapsamlı müdür ve
 * düzenleme paylaşımı olan OKUR ama düzenleyemez (403); yalnız görüntüleme paylaşımıyla gören 403
 * {@code CHANNEL_NOTES_NOT_SHARED}; başka takım / olmayan kanal 404 {@code CHANNEL_NOT_FOUND}; görsel sunumunda okunamayan
 * her şey aynı 404 (varlık sızmaz). Servis sahte — kapı mantığı, yanıt biçimi ve denetim gerçek.
 */
@WebMvcTest(TechErrorChannelNoteController.class)
class TechErrorChannelNoteControllerTest {

    private static final long A = 1L, B = 2L;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String BASE = "/api/tech-error-reports/channel-notes";
    private static final String SECRET_TEXT = "GIZLI-NOT-METNI raporlama.ic.example.test";

    @Autowired MockMvc mvc;
    @Autowired TechErrorChannelNoteController controller;

    @MockitoBean RememberMeService rememberMeService;
    @MockitoBean UserService userService;
    @MockitoBean AuthController authController;
    @MockitoBean com.sitemonitor.service.HttpMetricsService httpMetricsService;

    @MockitoBean TechErrorChannelNoteService notes;
    @MockitoBean PermissionService permissions;
    @MockitoBean AuditService auditService;

    private static TechErrorChannel channel(long id, long team) {
        TechErrorChannel c = new TechErrorChannel();
        c.setId(id);
        c.setTeamId(team);
        c.setName("Kanal " + id);
        c.setActive(true);
        return c;
    }

    private static TechErrorChannelNote note(long channelId, long version, String body) {
        TechErrorChannelNote n = new TechErrorChannelNote();
        n.setId(700L + channelId);
        n.setChannelId(channelId);
        n.setBodyMd(body);
        n.setVersion(version);
        n.setCreatedAt(Instant.parse("2026-11-01T08:00:00Z"));
        n.setCreatedById(55L);
        n.setCreatedByName("Mehmet Demir");
        n.setUpdatedAt(Instant.parse("2026-11-19T07:30:00Z"));
        n.setUpdatedById(56L);
        n.setUpdatedByName("Ayşe Yılmaz (Dijital Kanallar / Kıdemli Uzman)");
        return n;
    }

    private static TechErrorChannelNoteImageRepository.Meta imageMeta(long id, long channelId, boolean linked) {
        return new TechErrorChannelNoteImageRepository.Meta() {
            public Long getId() { return id; }
            public Long getChannelId() { return channelId; }
            public String getContentType() { return "image/png"; }
            public String getFileName() { return "ekran-" + id + ".png"; }
            public int getWidth() { return 640; }
            public int getHeight() { return 360; }
            public long getSizeBytes() { return 1234L; }
            public Instant getUploadedAt() { return Instant.parse("2026-11-18T10:00:00Z"); }
            public Boolean getLinked() { return linked; }
        };
    }

    private static TechErrorChannelNoteImage image(long id, long channelId, String type) {
        TechErrorChannelNoteImage i = new TechErrorChannelNoteImage();
        i.setId(id);
        i.setChannelId(channelId);
        i.setContentType(type);
        i.setFileName("ekran-" + id + ".png");
        i.setWidth(640);
        i.setHeight(360);
        i.setSizeBytes(4);
        i.setData(new byte[]{ (byte) 0x89, 'P', 'N', 'G' });
        i.setUploadedAt(Instant.parse("2026-11-18T10:00:00Z"));
        i.setUploadedById(55L);
        return i;
    }

    private static TechErrorChannelNoteService.View view(long channelId, TechErrorChannelNote n,
                                                         List<TechErrorChannelNoteImageRepository.Meta> images, long bytes) {
        return new TechErrorChannelNoteService.View(channelId, n, images, bytes);
    }

    @BeforeEach
    void setUp() {
        when(permissions.allows(any(HttpSession.class), eq("tech_error_reports.read"), eq("view"))).thenReturn(true);
        when(permissions.allows(any(HttpSession.class), eq("tech_error_reports.crud"), eq("edit"))).thenAnswer(inv -> {
            HttpSession s = inv.getArgument(0);
            return !"AUDIT".equals(s.getAttribute("systemRole"));
        });
        when(notes.channel(any())).thenReturn(Optional.empty());
        when(notes.channel(10L)).thenReturn(Optional.of(channel(10, A)));
        when(notes.channel(20L)).thenReturn(Optional.of(channel(20, B)));
        // Görsel sunumu kapsamı yalnız takım kimliğiyle denetler (tam kanal satırı okunmaz)
        when(notes.channelTeamId(any())).thenReturn(Optional.empty());
        when(notes.channelTeamId(10L)).thenReturn(Optional.of(A));
        when(notes.channelTeamId(20L)).thenReturn(Optional.of(B));
        when(notes.unlinkedKeepDays()).thenReturn(30);
        when(notes.read(10L)).thenReturn(view(10, note(10, 4, "# Kaynak\n" + SECRET_TEXT),
                List.of(imageMeta(902, 10, false), imageMeta(901, 10, true)), 2468L));
        when(notes.read(20L)).thenReturn(view(20, null, List.of(), 0L));
        controller.uploadClock = System::currentTimeMillis;
    }

    // ── Oturumlar ────────────────────────────────────────────────────────────────────────────────────

    private static MockHttpSession user(String role, long team, List<Long> manage) {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("authenticated", Boolean.TRUE);
        s.setAttribute("username", "u");
        s.setAttribute("userId", 50L);
        s.setAttribute("systemRole", role);
        s.setAttribute("teamId", team);
        s.setAttribute("viewTeamIds", List.of(team));
        s.setAttribute("memberTeamIds", List.of(team));
        s.setAttribute("manageTeamIds", manage);
        return s;
    }

    private static MockHttpSession member() {
        return user("USER", A, List.of());
    }

    private static MockHttpSession manager() {
        return user("TEAM_ADMIN", A, List.of(A));
    }

    private static MockHttpSession otherTeam() {
        return user("USER", B, List.of());
    }

    /** Kapsamlı müdür: takımı GÖRÜR (viewTeamIds) ama üyesi / yöneticisi değil → okur, düzenleyemez. */
    private static MockHttpSession viewer() {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("authenticated", Boolean.TRUE);
        s.setAttribute("username", "mudur");
        s.setAttribute("userId", 70L);
        s.setAttribute("systemRole", "ADMIN");
        s.setAttribute("viewTeamIds", List.of(A));
        s.setAttribute("memberTeamIds", List.of());
        s.setAttribute("manageTeamIds", List.of());
        return s;
    }

    private static MockHttpSession audit() {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("authenticated", Boolean.TRUE);
        s.setAttribute("username", "denetci");
        s.setAttribute("userId", 60L);
        s.setAttribute("systemRole", "AUDIT");
        return s;
    }

    private static MockHttpSession globalAdmin() {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("authenticated", Boolean.TRUE);
        s.setAttribute("username", "admin");
        s.setAttribute("userId", 1L);
        s.setAttribute("systemRole", "ADMIN");
        return s;
    }

    /** Kendi takımı B; A takımını YALNIZ erişim paylaşımıyla görür ({@code edit}: EDIT düzeyi; değilse VIEW). */
    private static MockHttpSession grantee(boolean edit) {
        MockHttpSession s = user("USER", B, List.of());
        s.setAttribute(SessionScope.ATTR_GRANT_VIEW, new ArrayList<>(List.of(A)));
        if (edit) s.setAttribute(SessionScope.ATTR_GRANT_EDIT, new ArrayList<>(List.of(A)));
        return s;
    }

    private static String body(Object version, Object text) throws Exception {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("version", version);
        m.put("body", text);
        return JSON.writeValueAsString(m);
    }

    /** Yanıtın hiçbir düzeyinde kullanıcı kimliği anahtarı yok (yalnız adlar). */
    private static void assertNoUserIds(MvcResult r) throws Exception {
        String raw = r.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        List<String> keys = new ArrayList<>();
        collectKeys(JSON.readValue(raw, Map.class), keys);
        assertThat(keys).isNotEmpty().noneMatch(k -> k.endsWith("_by_id") || k.endsWith("ById") || k.equals("user_id")
                || k.equals("uploaded_by") || k.equals("created_by") || k.equals("updated_by"));
    }

    private static void collectKeys(Object o, List<String> out) {
        if (o instanceof Map<?, ?> m) {
            for (Map.Entry<?, ?> e : m.entrySet()) {
                out.add(String.valueOf(e.getKey()));
                collectKeys(e.getValue(), out);
            }
        } else if (o instanceof Collection<?> c) {
            for (Object x : c) collectKeys(x, out);
        }
    }

    // ── Okuma ────────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("okuma: üye notu, görselleri (en yeni önce, baytsız), sınırları ve bayrakları alır; kullanıcı kimliği hiçbir düzeyde yok")
    void readOwnTeam() throws Exception {
        MvcResult r = mvc.perform(get(BASE + "/10").session(member()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.channel_id").value(10))
                .andExpect(jsonPath("$.data.exists").value(true))
                .andExpect(jsonPath("$.data.version").value(4))
                .andExpect(jsonPath("$.data.body").value("# Kaynak\n" + SECRET_TEXT))
                .andExpect(jsonPath("$.data.char_count").value(("# Kaynak\n" + SECRET_TEXT).length()))
                .andExpect(jsonPath("$.data.updated_at").value("2026-11-19T07:30:00Z"))
                .andExpect(jsonPath("$.data.updated_by_name").value("Ayşe Yılmaz (Dijital Kanallar / Kıdemli Uzman)"))
                .andExpect(jsonPath("$.data.created_at").value("2026-11-01T08:00:00Z"))
                .andExpect(jsonPath("$.data.images.length()").value(2))
                .andExpect(jsonPath("$.data.images[0].id").value(902))
                .andExpect(jsonPath("$.data.images[0].linked").value(false))
                .andExpect(jsonPath("$.data.images[1].id").value(901))
                .andExpect(jsonPath("$.data.images[1].linked").value(true))
                .andExpect(jsonPath("$.data.images[1].file_name").value("ekran-901.png"))
                .andExpect(jsonPath("$.data.images[1].content_type").value("image/png"))
                .andExpect(jsonPath("$.data.images[1].width").value(640))
                .andExpect(jsonPath("$.data.images[1].height").value(360))
                .andExpect(jsonPath("$.data.images[1].size_bytes").value(1234))
                .andExpect(jsonPath("$.data.images[1].uploaded_at").value("2026-11-18T10:00:00Z"))
                .andExpect(jsonPath("$.data.images[1].data").doesNotExist())
                .andExpect(jsonPath("$.data.image_bytes").value(2468))
                .andExpect(jsonPath("$.data.limits.max_chars").value(60000))
                .andExpect(jsonPath("$.data.limits.max_images").value(40))
                .andExpect(jsonPath("$.data.limits.max_image_bytes").value(5242880))
                .andExpect(jsonPath("$.data.limits.max_total_bytes").value(41943040))
                .andExpect(jsonPath("$.data.limits.unlinked_keep_days").value(30))
                .andExpect(jsonPath("$.data.limits.max_versions").value(20))
                .andExpect(jsonPath("$.data.can_edit").value(true))
                .andExpect(jsonPath("$.data.shared_access").value(false))
                .andReturn();
        assertNoUserIds(r);
    }

    @Test
    @DisplayName("okuma: not yokken exists false, sürüm 0, boş gövde — hata değil")
    void readWithoutNote() throws Exception {
        mvc.perform(get(BASE + "/20").session(otherTeam()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.channel_id").value(20))
                .andExpect(jsonPath("$.data.exists").value(false))
                .andExpect(jsonPath("$.data.version").value(0))
                .andExpect(jsonPath("$.data.body").value(""))
                .andExpect(jsonPath("$.data.char_count").value(0))
                .andExpect(jsonPath("$.data.updated_at").isEmpty())
                .andExpect(jsonPath("$.data.updated_by_name").isEmpty())
                .andExpect(jsonPath("$.data.images.length()").value(0))
                .andExpect(jsonPath("$.data.image_bytes").value(0))
                .andExpect(jsonPath("$.data.can_edit").value(true));
    }

    @Test
    @DisplayName("okuma kapsamı: yönetici / global yönetici düzenler; AUDIT, kapsamlı müdür ve düzenleme paylaşımı okur (can_edit false);"
            + " görüntüleme paylaşımı 403 CHANNEL_NOTES_NOT_SHARED; başka takım, 7/24 operatörü ve olmayan kanal 404 CHANNEL_NOT_FOUND")
    void readScopeMatrix() throws Exception {
        mvc.perform(get(BASE + "/10").session(manager())).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.can_edit").value(true));
        mvc.perform(get(BASE + "/10").session(globalAdmin())).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.can_edit").value(true))
                .andExpect(jsonPath("$.data.shared_access").value(false));
        mvc.perform(get(BASE + "/10").session(audit())).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.body").value(containsString("# Kaynak")))
                .andExpect(jsonPath("$.data.can_edit").value(false));
        mvc.perform(get(BASE + "/10").session(viewer())).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.can_edit").value(false));
        mvc.perform(get(BASE + "/10").session(grantee(true))).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.body").value(containsString("# Kaynak")))
                .andExpect(jsonPath("$.data.can_edit").value(false))
                .andExpect(jsonPath("$.data.shared_access").value(true));
        verify(notes, times(5)).read(10L);

        // yalnız görüntüleme paylaşımı: kanal görünür, notlar paylaşılmaz — metin okunmaz
        mvc.perform(get(BASE + "/10").session(grantee(false)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("CHANNEL_NOTES_NOT_SHARED"))
                .andExpect(jsonPath("$.error").value(containsString("düzenleme düzeyinde")))
                .andExpect(content().string(not(containsString("GIZLI-NOT-METNI"))));
        // başka takım / olmayan kanal: aynı kodlu 404
        mvc.perform(get(BASE + "/10").session(otherTeam()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CHANNEL_NOT_FOUND"));
        mvc.perform(get(BASE + "/999").session(member()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CHANNEL_NOT_FOUND"));
        // 7/24 operatörlüğü hiçbir şeyi genişletmez
        MockHttpSession noc = otherTeam();
        noc.setAttribute("nocOperator", Boolean.TRUE);
        noc.setAttribute("nocTeamIds", List.of(B));
        mvc.perform(get(BASE + "/10").session(noc))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CHANNEL_NOT_FOUND"));
        verify(notes, times(5)).read(10L);

        // rol izni yok → 403 (kanal okunmadan)
        when(permissions.allows(any(HttpSession.class), eq("tech_error_reports.read"), eq("view"))).thenReturn(false);
        mvc.perform(get(BASE + "/10").session(member())).andExpect(status().isForbidden());
        mvc.perform(get(BASE + "/10/versions").session(member())).andExpect(status().isForbidden());
        mvc.perform(get(BASE + "/images/901").session(member())).andExpect(status().isForbidden());
    }

    // ── Kayıt ────────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("kayıt: üye kaydeder — yanıt GET biçimi + changed; denetim kanal / takım / sürüm / sayılar taşır, metni ASLA")
    void saveAuditsWithoutText() throws Exception {
        String text = "# Kaynak v2\n" + SECRET_TEXT + " ![g](te-note-img:901)";
        when(notes.save(eq(10L), eq(4L), eq(text), eq(50L))).thenReturn(new TechErrorChannelNoteService.SaveResult(
                view(10, note(10, 5, text), List.of(imageMeta(901, 10, true)), 1234L), true, 1));
        MvcResult r = mvc.perform(put(BASE + "/10").session(member()).contentType(MediaType.APPLICATION_JSON).content(body(4, text)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.changed").value(true))
                .andExpect(jsonPath("$.data.version").value(5))
                .andExpect(jsonPath("$.data.exists").value(true))
                .andExpect(jsonPath("$.data.body").value(text))
                .andExpect(jsonPath("$.data.images[0].linked").value(true))
                .andExpect(jsonPath("$.data.limits.max_chars").value(60000))
                .andExpect(jsonPath("$.data.can_edit").value(true))
                .andReturn();
        assertNoUserIds(r);
        verify(auditService).recordAction(eq("TECH_ERROR_CHANNEL_NOTE_SAVE"), any(HttpSession.class), any(HttpServletRequest.class),
                eq("TECH_ERROR_CHANNEL"), eq("10"), argThat((String d) -> d != null
                        && d.contains("\"channel_id\":10") && d.contains("Kanal 10") && d.contains("\"team_id\":1")
                        && d.contains("\"version\":5") && d.contains("\"char_count\":" + text.length())
                        && d.contains("\"image_count\":1")
                        && !d.contains("GIZLI-NOT-METNI") && !d.contains("Kaynak v2") && !d.contains("example.test")));
        verify(auditService, times(1)).recordAction(anyString(), any(HttpSession.class), any(HttpServletRequest.class), any(), any(), any());

        // yönetici ve global yönetici de kaydeder
        when(notes.save(eq(10L), eq(5L), eq("x"), any())).thenReturn(new TechErrorChannelNoteService.SaveResult(
                view(10, note(10, 6, "x"), List.of(), 0L), true, 0));
        mvc.perform(put(BASE + "/10").session(manager()).contentType(MediaType.APPLICATION_JSON).content(body(5, "x")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.version").value(6));
        mvc.perform(put(BASE + "/10").session(globalAdmin()).contentType(MediaType.APPLICATION_JSON).content(body(5, "x")))
                .andExpect(status().isOk());
        verify(notes).save(10L, 5L, "x", 1L);
    }

    @Test
    @DisplayName("kayıt: değişmeyen gövde changed false döner ve DENETLENMEZ")
    void unchangedSaveIsNotAudited() throws Exception {
        when(notes.save(eq(10L), eq(4L), anyString(), eq(50L))).thenReturn(new TechErrorChannelNoteService.SaveResult(
                view(10, note(10, 4, "aynı"), List.of(), 0L), false, 0));
        mvc.perform(put(BASE + "/10").session(member()).contentType(MediaType.APPLICATION_JSON).content(body(4, "aynı")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.changed").value(false))
                .andExpect(jsonPath("$.data.version").value(4));
        verify(auditService, never()).recordAction(anyString(), any(HttpSession.class), any(HttpServletRequest.class), any(), any(), any());
    }

    @Test
    @DisplayName("kayıt kapsamı: AUDIT, kapsamlı müdür ve düzenleme paylaşımı 403 (okur, düzenleyemez); görüntüleme paylaşımı 403"
            + " CHANNEL_NOTES_NOT_SHARED; başka takım 404 — hiçbiri servise ulaşmaz")
    void saveScopeMatrix() throws Exception {
        String b = body(4, "x");
        mvc.perform(put(BASE + "/10").session(audit()).contentType(MediaType.APPLICATION_JSON).content(b))
                .andExpect(status().isForbidden());
        mvc.perform(put(BASE + "/10").session(viewer()).contentType(MediaType.APPLICATION_JSON).content(b))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mvc.perform(put(BASE + "/10").session(grantee(true)).contentType(MediaType.APPLICATION_JSON).content(b))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"))
                .andExpect(jsonPath("$.error").value(containsString("erişim paylaşımı notu okutur")));
        mvc.perform(put(BASE + "/10").session(grantee(false)).contentType(MediaType.APPLICATION_JSON).content(b))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CHANNEL_NOTES_NOT_SHARED"));
        mvc.perform(put(BASE + "/10").session(otherTeam()).contentType(MediaType.APPLICATION_JSON).content(b))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CHANNEL_NOT_FOUND"));
        mvc.perform(put(BASE + "/999").session(member()).contentType(MediaType.APPLICATION_JSON).content(b))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CHANNEL_NOT_FOUND"));
        verify(notes, never()).save(anyLong(), anyLong(), any(), any());
        verify(auditService, never()).recordAction(anyString(), any(HttpSession.class), any(HttpServletRequest.class), any(), any(), any());
    }

    @Test
    @DisplayName("kayıt doğrulama: sürüm 0 ya da pozitif TAM sayı değilse 400 version; gövde metin değilse 400 body; servisin alan"
            + " hatası aynen 400 body")
    void saveValidation() throws Exception {
        for (String bad : List.of("{\"body\":\"x\"}", "{\"version\":\"4\",\"body\":\"x\"}", "{\"version\":-1,\"body\":\"x\"}",
                "{\"version\":1.5,\"body\":\"x\"}", "{\"version\":null,\"body\":\"x\"}", "{\"version\":true,\"body\":\"x\"}",
                "{\"version\":123456789012345678901234567890,\"body\":\"x\"}")) {
            mvc.perform(put(BASE + "/10").session(member()).contentType(MediaType.APPLICATION_JSON).content(bad))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.field").value("version"));
        }
        for (String bad : List.of("{\"version\":4}", "{\"version\":4,\"body\":null}", "{\"version\":4,\"body\":12}",
                "{\"version\":4,\"body\":[\"x\"]}", "{\"version\":4,\"body\":{\"md\":\"x\"}}")) {
            mvc.perform(put(BASE + "/10").session(member()).contentType(MediaType.APPLICATION_JSON).content(bad))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                    .andExpect(jsonPath("$.field").value("body"));
        }
        verify(notes, never()).save(anyLong(), anyLong(), any(), any());

        // sürüm 0 geçerlidir (not yokken); boş gövde geçerlidir
        when(notes.save(eq(10L), eq(0L), eq(""), eq(50L))).thenReturn(new TechErrorChannelNoteService.SaveResult(
                view(10, null, List.of(), 0L), false, 0));
        mvc.perform(put(BASE + "/10").session(member()).contentType(MediaType.APPLICATION_JSON).content(body(0, "")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.exists").value(false))
                .andExpect(jsonPath("$.data.changed").value(false));

        when(notes.save(eq(10L), eq(4L), eq("uzun"), eq(50L))).thenThrow(new FieldValidationException("body",
                "Kanal notu en çok 60000 karakter olabilir (şu an 60001). Metni kısaltıp yeniden kaydedin."));
        mvc.perform(put(BASE + "/10").session(member()).contentType(MediaType.APPLICATION_JSON).content(body(4, "uzun")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.field").value("body"))
                .andExpect(jsonPath("$.fields.body").value(containsString("60000")));
    }

    @Test
    @DisplayName("çakışma: 409 CONCURRENT_MODIFICATION + current_version / updated_by_name / updated_at; ek alansız çakışma (tekil"
            + " kısıt kaybı) ve @Version çakışması güncel durumu işlem DIŞINDAKİ özetten alır; özet de okunamazsa yine 409")
    void conflictExtras() throws Exception {
        when(notes.save(eq(10L), eq(3L), anyString(), eq(50L))).thenThrow(TechErrorChannelNoteService.conflict(
                7L, "Ayşe Yılmaz (Dijital Kanallar / Kıdemli Uzman)", Instant.parse("2026-11-19T07:30:00Z")));
        mvc.perform(put(BASE + "/10").session(member()).contentType(MediaType.APPLICATION_JSON).content(body(3, "x")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("CONCURRENT_MODIFICATION"))
                .andExpect(jsonPath("$.current_version").value(7))
                .andExpect(jsonPath("$.updated_by_name").value("Ayşe Yılmaz (Dijital Kanallar / Kıdemli Uzman)"))
                .andExpect(jsonPath("$.updated_at").value("2026-11-19T07:30:00Z"))
                .andExpect(jsonPath("$.error").value(containsString("Ayşe Yılmaz")));
        verify(notes, never()).summary(anyLong());

        // ek alansız (servis bozuk işlemde okumadı) → uç yeni okumayla tamamlar
        when(notes.save(eq(10L), eq(0L), anyString(), eq(50L))).thenThrow(TechErrorChannelNoteService.conflict(null, null, null));
        when(notes.summary(10L)).thenReturn(new TechErrorChannelNoteService.Summary(true, false, 1L,
                Instant.parse("2026-11-20T06:00:00Z"), "Mehmet Demir", 0L));
        mvc.perform(put(BASE + "/10").session(member()).contentType(MediaType.APPLICATION_JSON).content(body(0, "x")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONCURRENT_MODIFICATION"))
                .andExpect(jsonPath("$.current_version").value(1))
                .andExpect(jsonPath("$.updated_by_name").value("Mehmet Demir"))
                .andExpect(jsonPath("$.updated_at").value("2026-11-20T06:00:00Z"));

        // kanal kilidini atlayan yazım @Version'a takıldı → aynı 409, güncel durumla
        when(notes.save(eq(10L), eq(2L), anyString(), eq(50L))).thenThrow(new OptimisticLockingFailureException("stale"));
        mvc.perform(put(BASE + "/10").session(member()).contentType(MediaType.APPLICATION_JSON).content(body(2, "x")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONCURRENT_MODIFICATION"))
                .andExpect(jsonPath("$.current_version").value(1));

        // güncel durum da okunamadı → ek alansız aynı 409 (500 değil)
        when(notes.summary(10L)).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("db"));
        mvc.perform(put(BASE + "/10").session(member()).contentType(MediaType.APPLICATION_JSON).content(body(0, "x")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONCURRENT_MODIFICATION"))
                .andExpect(jsonPath("$.current_version").doesNotExist());
        verify(auditService, never()).recordAction(anyString(), any(HttpSession.class), any(HttpServletRequest.class), any(), any(), any());
    }

    // ── Görseller ────────────────────────────────────────────────────────────────────────────────────

    private static MockMultipartFile png(String name) {
        return new MockMultipartFile("file", name, "image/png", new byte[]{ (byte) 0x89, 'P', 'N', 'G' });
    }

    @Test
    @DisplayName("görsel yükleme: yanıt görsel + başvuru (te-note-img:<kimlik>), bağsız doğar; denetimde kimlik + boyut var, dosya adı / bayt yok")
    void uploadImage() throws Exception {
        TechErrorChannelNoteImage saved = image(905, 10, "image/png");
        saved.setFileName("gizli-ekran-adi.png");
        when(notes.upload(eq(10L), any(), eq("gizli-ekran-adi.png"), eq(50L))).thenReturn(saved);
        MvcResult r = mvc.perform(multipart(BASE + "/10/images").file(png("gizli-ekran-adi.png")).session(member()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.image.id").value(905))
                .andExpect(jsonPath("$.data.image.file_name").value("gizli-ekran-adi.png"))
                .andExpect(jsonPath("$.data.image.content_type").value("image/png"))
                .andExpect(jsonPath("$.data.image.width").value(640))
                .andExpect(jsonPath("$.data.image.height").value(360))
                .andExpect(jsonPath("$.data.image.size_bytes").value(4))
                .andExpect(jsonPath("$.data.image.uploaded_at").value("2026-11-18T10:00:00Z"))
                .andExpect(jsonPath("$.data.image.linked").value(false))
                .andExpect(jsonPath("$.data.image.data").doesNotExist())
                .andExpect(jsonPath("$.data.ref").value("te-note-img:905"))
                .andReturn();
        assertNoUserIds(r);
        verify(auditService).recordAction(eq("TECH_ERROR_CHANNEL_NOTE_IMAGE_ADD"), any(HttpSession.class), any(HttpServletRequest.class),
                eq("TECH_ERROR_CHANNEL"), eq("10"), argThat((String d) -> d != null && d.contains("\"image_id\":905")
                        && d.contains("\"size_bytes\":4") && d.contains("\"channel_id\":10") && !d.contains("gizli-ekran-adi")));
    }

    @Test
    @DisplayName("görsel yükleme reddi: boş / 5 MB üstü dosya 400 file; sınır 409 kodları; düzenleyemeyen 403, göremeyen 404 — servise ulaşmaz")
    void uploadRefusals() throws Exception {
        mvc.perform(multipart(BASE + "/10/images").file(new MockMultipartFile("file", "bos.png", "image/png", new byte[0])).session(member()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.field").value("file"));
        mvc.perform(multipart(BASE + "/10/images")
                        .file(new MockMultipartFile("file", "dev.png", "image/png", new byte[5 * 1024 * 1024 + 1])).session(member()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.field").value("file"))
                .andExpect(jsonPath("$.error").value(containsString("5 MB")));
        mvc.perform(multipart(BASE + "/10/images").file(png("a.png")).session(audit())).andExpect(status().isForbidden());
        mvc.perform(multipart(BASE + "/10/images").file(png("a.png")).session(viewer())).andExpect(status().isForbidden());
        mvc.perform(multipart(BASE + "/10/images").file(png("a.png")).session(grantee(true)))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mvc.perform(multipart(BASE + "/10/images").file(png("a.png")).session(grantee(false)))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("CHANNEL_NOTES_NOT_SHARED"));
        mvc.perform(multipart(BASE + "/10/images").file(png("a.png")).session(otherTeam()))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("CHANNEL_NOT_FOUND"));
        verify(notes, never()).upload(anyLong(), any(), any(), any());

        when(notes.upload(eq(10L), any(), any(), eq(50L)))
                .thenThrow(new CodedConflictException("NOTE_IMAGE_LIMIT", "Kanal notunda en çok 40 görsel olabilir."))
                .thenThrow(new CodedConflictException("NOTE_IMAGE_TOTAL_LIMIT", "Kanal notundaki görsellerin toplamı 40 MB'ı geçemez."))
                .thenThrow(new FieldValidationException("file", "Yalnız PNG ya da JPEG görsel yüklenebilir."));
        mvc.perform(multipart(BASE + "/10/images").file(png("a.png")).session(member()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("NOTE_IMAGE_LIMIT"));
        mvc.perform(multipart(BASE + "/10/images").file(png("a.png")).session(member()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("NOTE_IMAGE_TOTAL_LIMIT"));
        mvc.perform(multipart(BASE + "/10/images").file(png("a.png")).session(member()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("file"));
        verify(auditService, never()).recordAction(eq("TECH_ERROR_CHANNEL_NOTE_IMAGE_ADD"), any(HttpSession.class),
                any(HttpServletRequest.class), any(), any(), any());
    }

    @Test
    @DisplayName("yükleme yük sınırı: kişi başına dakikada 30 — 31.'si 429 RATE_LIMITED + Retry-After + retry_after_seconds; reddedilen"
            + " sayılmaz, başka kullanıcı etkilenmez, pencere geçince yeniden açılır; kapsam reddi hak yemez")
    void uploadRateLimit() throws Exception {
        long[] now = { 5_000_000_000L };
        controller.uploadClock = () -> now[0];
        when(notes.upload(anyLong(), any(), any(), any())).thenReturn(image(906, 10, "image/png"));
        MockHttpSession heavy = member();
        heavy.setAttribute("userId", 7771L);
        // kapsam reddi (başka takımın kanalı) hak yemez
        MockHttpSession outsider = otherTeam();
        outsider.setAttribute("userId", 7771L);
        for (int i = 0; i < 5; i++) {
            mvc.perform(multipart(BASE + "/10/images").file(png("a.png")).session(outsider)).andExpect(status().isNotFound());
        }
        for (int i = 0; i < TechErrorChannelNoteController.UPLOADS_PER_MINUTE; i++) {
            now[0] += 1_000L;
            mvc.perform(multipart(BASE + "/10/images").file(png("a.png")).session(heavy)).andExpect(status().isOk());
        }
        assertThat(TechErrorChannelNoteController.UPLOADS_PER_MINUTE).isEqualTo(30);
        now[0] += 1_000L;                                   // ilk yüklemeden 30 sn sonra: pencerenin dolmasına 30 sn var
        for (int i = 0; i < 3; i++) {
            mvc.perform(multipart(BASE + "/10/images").file(png("a.png")).session(heavy))
                    .andExpect(status().isTooManyRequests())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.code").value("RATE_LIMITED"))
                    .andExpect(jsonPath("$.retry_after_seconds").value(30))
                    .andExpect(header().string("Retry-After", "30"))
                    .andExpect(jsonPath("$.error").value(containsString("30")));
        }
        verify(notes, times(30)).upload(anyLong(), any(), any(), any());
        // başka kullanıcı etkilenmez
        MockHttpSession other = member();
        other.setAttribute("userId", 7772L);
        mvc.perform(multipart(BASE + "/10/images").file(png("a.png")).session(other)).andExpect(status().isOk());
        // en eski yükleme pencereden düşünce bir hak açılır (reddedilen 3 deneme sayılmadı)
        now[0] += 30_000L;
        mvc.perform(multipart(BASE + "/10/images").file(png("a.png")).session(heavy)).andExpect(status().isOk());
        mvc.perform(multipart(BASE + "/10/images").file(png("a.png")).session(heavy)).andExpect(status().isTooManyRequests());
        now[0] += 61_000L;
        mvc.perform(multipart(BASE + "/10/images").file(png("a.png")).session(heavy)).andExpect(status().isOk());
    }

    @Test
    @DisplayName("görsel sunumu: okuyan baytları saklanan türle, inline + nosniff alır; başka takımın görseli, görüntüleme paylaşımı,"
            + " olmayan kimlik ve kanalı silinmiş görsel AYNI 404 — bayt yüklenmeden (IDOR)")
    void serveImage() throws Exception {
        when(notes.imageMeta(901L)).thenReturn(Optional.of(imageMeta(901, 10, true)));
        when(notes.imageMeta(950L)).thenReturn(Optional.of(imageMeta(950, 20, true)));          // B takımının kanalı
        when(notes.imageMeta(960L)).thenReturn(Optional.of(imageMeta(960, 999, false)));        // kanalı yok
        when(notes.imageMeta(970L)).thenReturn(Optional.empty());
        when(notes.imageWithBytes(901L)).thenReturn(Optional.of(image(901, 10, "image/png")));
        when(notes.imageWithBytes(950L)).thenReturn(Optional.of(image(950, 20, "image/png")));

        mvc.perform(get(BASE + "/images/901").session(member()))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.IMAGE_PNG))
                .andExpect(content().bytes(new byte[]{ (byte) 0x89, 'P', 'N', 'G' }))
                .andExpect(header().string("Content-Disposition", containsString("inline")))
                .andExpect(header().string("Content-Disposition", containsString("ekran-901.png")))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                // rapor görseli ucuyla aynı: genel /api "no-store" başlığının YERİNE geçer (görsel değişmez — her yükleme yeni kimlik)
                .andExpect(header().stringValues("Cache-Control", "private, max-age=3600"));
        // not metni uçları genel kuralda kalır: önbelleğe alınmaz
        mvc.perform(get(BASE + "/10").session(member()))
                .andExpect(header().stringValues("Cache-Control", "no-store, no-cache, must-revalidate"));
        // AUDIT ve düzenleme paylaşımı okur
        mvc.perform(get(BASE + "/images/901").session(audit())).andExpect(status().isOk());
        mvc.perform(get(BASE + "/images/901").session(grantee(true))).andExpect(status().isOk());
        verify(notes, times(3)).imageWithBytes(901L);

        // başka takımın görseli kimliğiyle istenirse 404 — varlığı sızmaz, baytı okunmaz
        mvc.perform(get(BASE + "/images/950").session(member()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOTE_IMAGE_NOT_FOUND"));
        // yalnız görüntüleme paylaşımı: 403 değil 404 (not paylaşılmıyor; görselin varlığı da söylenmez)
        mvc.perform(get(BASE + "/images/901").session(grantee(false)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOTE_IMAGE_NOT_FOUND"));
        mvc.perform(get(BASE + "/images/901").session(otherTeam())).andExpect(status().isNotFound());
        mvc.perform(get(BASE + "/images/960").session(globalAdmin())).andExpect(status().isNotFound());
        mvc.perform(get(BASE + "/images/970").session(member()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOTE_IMAGE_NOT_FOUND"));
        verify(notes, times(3)).imageWithBytes(901L);
        verify(notes, never()).imageWithBytes(950L);
        verify(notes, never()).imageWithBytes(960L);
        // kendi takımı okur
        mvc.perform(get(BASE + "/images/950").session(otherTeam())).andExpect(status().isOk());

        // meta ile bayt arasında silinen görsel: 404; beklenmeyen saklı tür tarayıcıya çalıştırılabilir tür olarak gitmez
        when(notes.imageMeta(980L)).thenReturn(Optional.of(imageMeta(980, 10, false)));
        when(notes.imageWithBytes(980L)).thenReturn(Optional.empty());
        mvc.perform(get(BASE + "/images/980").session(member())).andExpect(status().isNotFound());
        when(notes.imageMeta(990L)).thenReturn(Optional.of(imageMeta(990, 10, false)));
        when(notes.imageWithBytes(990L)).thenReturn(Optional.of(image(990, 10, "text/html")));
        mvc.perform(get(BASE + "/images/990").session(member()))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_OCTET_STREAM))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"));
        when(notes.imageWithBytes(991L)).thenReturn(Optional.of(image(991, 10, "image/jpeg")));
        when(notes.imageMeta(991L)).thenReturn(Optional.of(imageMeta(991, 10, false)));
        mvc.perform(get(BASE + "/images/991").session(member()))
                .andExpect(status().isOk()).andExpect(content().contentType(MediaType.IMAGE_JPEG));
    }

    @Test
    @DisplayName("görsel silme: üye siler + denetim; kayıtlı notta kullanılan 409 NOTE_IMAGE_IN_USE; başka kanalın görseli 404;"
            + " düzenleyemeyen 403")
    void deleteImage() throws Exception {
        when(notes.deleteImage(10L, 902L)).thenReturn(imageMeta(902, 10, false));
        mvc.perform(delete(BASE + "/10/images/902").session(member()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.deleted").value(true));
        verify(auditService).recordAction(eq("TECH_ERROR_CHANNEL_NOTE_IMAGE_DELETE"), any(HttpSession.class),
                any(HttpServletRequest.class), eq("TECH_ERROR_CHANNEL"), eq("10"),
                argThat((String d) -> d != null && d.contains("\"image_id\":902") && d.contains("\"size_bytes\":1234")
                        && !d.contains("ekran-902")));

        when(notes.deleteImage(10L, 901L)).thenThrow(new CodedConflictException("NOTE_IMAGE_IN_USE",
                "Bu görsel kayıtlı notta kullanılıyor; silinmedi."));
        mvc.perform(delete(BASE + "/10/images/901").session(member()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("NOTE_IMAGE_IN_USE"));
        when(notes.deleteImage(10L, 950L)).thenThrow(TechErrorChannelNoteService.imageNotFound());
        mvc.perform(delete(BASE + "/10/images/950").session(member()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOTE_IMAGE_NOT_FOUND"));

        mvc.perform(delete(BASE + "/10/images/902").session(audit())).andExpect(status().isForbidden());
        mvc.perform(delete(BASE + "/10/images/902").session(grantee(true))).andExpect(status().isForbidden());
        mvc.perform(delete(BASE + "/10/images/902").session(grantee(false)))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("CHANNEL_NOTES_NOT_SHARED"));
        mvc.perform(delete(BASE + "/10/images/902").session(otherTeam()))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("CHANNEL_NOT_FOUND"));
        verify(notes, times(1)).deleteImage(10L, 902L);
        verify(auditService, times(1)).recordAction(eq("TECH_ERROR_CHANNEL_NOTE_IMAGE_DELETE"), any(HttpSession.class),
                any(HttpServletRequest.class), any(), any(), any());
    }

    // ── Sürümler ─────────────────────────────────────────────────────────────────────────────────────

    private static TechErrorChannelNoteVersionRepository.Meta versionMeta(long version, int chars, int images) {
        return new TechErrorChannelNoteVersionRepository.Meta() {
            public long getVersion() { return version; }
            public Instant getSavedAt() { return Instant.parse("2026-11-19T07:30:00Z").minusSeconds(60 * (10 - version)); }
            public String getSavedByName() { return "Ayşe Yılmaz (Dijital Kanallar / Kıdemli Uzman)"; }
            public int getCharCount() { return chars; }
            public int getImageCount() { return images; }
        };
    }

    @Test
    @DisplayName("sürümler: liste gövdesiz (en yeni önce, en çok 20); tek sürüm gövdeyle; olmayan sürüm 404 NOTE_VERSION_NOT_FOUND;"
            + " okuma kapsamı notla aynı")
    void versions() throws Exception {
        when(notes.versions(10L)).thenReturn(List.of(versionMeta(4, 120, 2), versionMeta(3, 80, 1)));
        MvcResult list = mvc.perform(get(BASE + "/10/versions").session(member()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.max_versions").value(20))
                .andExpect(jsonPath("$.data.versions.length()").value(2))
                .andExpect(jsonPath("$.data.versions[0].version").value(4))
                .andExpect(jsonPath("$.data.versions[0].saved_at").value("2026-11-19T07:24:00Z"))
                .andExpect(jsonPath("$.data.versions[0].saved_by_name").value("Ayşe Yılmaz (Dijital Kanallar / Kıdemli Uzman)"))
                .andExpect(jsonPath("$.data.versions[0].char_count").value(120))
                .andExpect(jsonPath("$.data.versions[0].image_count").value(2))
                .andExpect(jsonPath("$.data.versions[0].body").doesNotExist())
                .andExpect(jsonPath("$.data.versions[1].version").value(3))
                .andReturn();
        assertNoUserIds(list);

        TechErrorChannelNoteVersion v = new TechErrorChannelNoteVersion();
        v.setId(8L);
        v.setChannelId(10L);
        v.setVersion(3L);
        v.setBodyMd("# Eski sürüm\n" + SECRET_TEXT);
        v.setSavedAt(Instant.parse("2026-11-19T07:23:00Z"));
        v.setSavedByName("Mehmet Demir");
        v.setCharCount(80);
        v.setImageCount(1);
        when(notes.version(10L, 3L)).thenReturn(Optional.of(v));
        when(notes.version(10L, 99L)).thenReturn(Optional.empty());
        MvcResult one = mvc.perform(get(BASE + "/10/versions/3").session(audit()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version").value(3))
                .andExpect(jsonPath("$.data.body").value("# Eski sürüm\n" + SECRET_TEXT))
                .andExpect(jsonPath("$.data.saved_at").value("2026-11-19T07:23:00Z"))
                .andExpect(jsonPath("$.data.saved_by_name").value("Mehmet Demir"))
                .andReturn();
        assertNoUserIds(one);
        mvc.perform(get(BASE + "/10/versions/99").session(member()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOTE_VERSION_NOT_FOUND"));

        mvc.perform(get(BASE + "/10/versions").session(grantee(true))).andExpect(status().isOk());
        mvc.perform(get(BASE + "/10/versions").session(grantee(false)))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("CHANNEL_NOTES_NOT_SHARED"));
        mvc.perform(get(BASE + "/10/versions/3").session(grantee(false)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CHANNEL_NOTES_NOT_SHARED"))
                .andExpect(content().string(not(containsString("GIZLI-NOT-METNI"))));
        mvc.perform(get(BASE + "/10/versions").session(otherTeam()))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("CHANNEL_NOT_FOUND"));
        mvc.perform(get(BASE + "/10/versions/3").session(otherTeam()))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("CHANNEL_NOT_FOUND"));
    }

    // ── Kapsam yardımcıları (tek kaynak) ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("kapsam doğruluk tablosu: canReadNotes / canEditNotes — rapor ucu ve kanal satırı bayrakları aynı kuralı kullanır")
    void scopeHelpers() {
        assertThat(TechErrorChannelNoteController.canReadNotes(member(), A)).isTrue();
        assertThat(TechErrorChannelNoteController.canEditNotes(member(), A)).isTrue();
        assertThat(TechErrorChannelNoteController.canReadNotes(manager(), A)).isTrue();
        assertThat(TechErrorChannelNoteController.canEditNotes(manager(), A)).isTrue();
        assertThat(TechErrorChannelNoteController.canReadNotes(globalAdmin(), A)).isTrue();
        assertThat(TechErrorChannelNoteController.canEditNotes(globalAdmin(), A)).isTrue();
        assertThat(TechErrorChannelNoteController.canReadNotes(audit(), A)).isTrue();
        assertThat(TechErrorChannelNoteController.canEditNotes(audit(), A)).isFalse();
        assertThat(TechErrorChannelNoteController.canReadNotes(viewer(), A)).isTrue();
        assertThat(TechErrorChannelNoteController.canEditNotes(viewer(), A)).isFalse();
        assertThat(TechErrorChannelNoteController.canReadNotes(grantee(true), A)).isTrue();
        assertThat(TechErrorChannelNoteController.canEditNotes(grantee(true), A)).isFalse();
        assertThat(TechErrorChannelNoteController.canReadNotes(grantee(false), A)).isFalse();
        assertThat(TechErrorChannelNoteController.canEditNotes(grantee(false), A)).isFalse();
        assertThat(TechErrorChannelNoteController.canReadNotes(otherTeam(), A)).isFalse();
        assertThat(TechErrorChannelNoteController.canEditNotes(otherTeam(), A)).isFalse();
        assertThat(TechErrorChannelNoteController.canReadNotes(member(), null)).isFalse();
        assertThat(TechErrorChannelNoteController.canEditNotes(member(), null)).isFalse();
        // DELETE düzeyi paylaşım EDIT'i de taşır (birikimli listeler) → okur, düzenleyemez
        MockHttpSession del = grantee(true);
        del.setAttribute(SessionScope.ATTR_GRANT_DELETE, new ArrayList<>(List.of(A)));
        assertThat(TechErrorChannelNoteController.canReadNotes(del, A)).isTrue();
        assertThat(TechErrorChannelNoteController.canEditNotes(del, A)).isFalse();
        // üyeliği olan birinin ayrıca görüntüleme paylaşımı da olsa notu okur ve düzenler
        MockHttpSession memberToo = new MockHttpSession();
        memberToo.setAttribute("systemRole", "USER");
        memberToo.setAttribute("teamId", A);
        memberToo.setAttribute("viewTeamIds", List.of());
        memberToo.setAttribute("memberTeamIds", List.of(A));
        memberToo.setAttribute("manageTeamIds", List.of());
        memberToo.setAttribute(SessionScope.ATTR_GRANT_VIEW, new ArrayList<>(List.of(A)));
        assertThat(TechErrorChannelNoteController.canReadNotes(memberToo, A)).isTrue();
        assertThat(TechErrorChannelNoteController.canEditNotes(memberToo, A)).isTrue();

        // özet bloğu: okuyamayan için sorgu yok
        Map<String, Object> hidden = TechErrorChannelNoteController.summaryBlock(notes, grantee(false), channel(10, A));
        assertThat(hidden).containsOnly(Map.entry("readable", false));
        verify(notes, never()).summary(anyLong());
        assertThat(TechErrorChannelNoteController.summaryBlock(null, member(), channel(10, A)))
                .containsOnly(Map.entry("readable", true), Map.entry("unavailable", true));
        assertThat(TechErrorChannelNoteController.summaryBlock(notes, member(), null)).containsOnly(Map.entry("readable", false));
    }
}
