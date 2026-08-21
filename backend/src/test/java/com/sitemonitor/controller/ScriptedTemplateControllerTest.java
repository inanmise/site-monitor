package com.sitemonitor.controller;

import com.sitemonitor.model.ScriptedTemplate;
import com.sitemonitor.model.ScriptedTemplateVersion;
import com.sitemonitor.repository.ScriptedTemplateRepository;
import com.sitemonitor.repository.ScriptedTemplateVersionRepository;
import com.sitemonitor.service.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Şablon sürümleme, denetim kaydı ve içerik kuralları.
 *
 * <p>Görünürlük/yetki matrisi ayrı dosyada ({@link ScriptedTemplateVisibilityTest}) — burada
 * yetkili aktörün davranışı sınanır.
 */
@WebMvcTest(ScriptedTemplateController.class)
class ScriptedTemplateControllerTest {

    private static final long TEAM_A = 1L;
    private static final long ID = 11L;
    private static final String SCRIPT = "export default function () { }";

    @Autowired MockMvc mvc;

    @MockitoBean ScriptedTemplateRepository templateRepo;
    @MockitoBean ScriptedTemplateVersionRepository versionRepo;
    @MockitoBean PermissionService permissionService;
    @MockitoBean AuditService auditService;
    @MockitoBean CertificateService certificateService;
    @MockitoBean ScriptedCheckerService scriptedChecker;

    @MockitoBean AppSettingsService appSettings;
    @MockitoBean RememberMeService rememberMeService;
    @MockitoBean UserService userService;
    @MockitoBean HttpMetricsService httpMetricsService;
    @MockitoBean AuthController authController;

    private MockHttpSession admin;

    @BeforeEach
    void setUp() {
        when(certificateService.teamNamesById()).thenReturn(Map.of(TEAM_A, "Takım A"));
        when(scriptedChecker.version()).thenReturn("v0.49.0");
        when(templateRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        when(templateRepo.countDuplicateInScope(anyString(), any(), any())).thenReturn(0L);
        when(versionRepo.findTopByTemplateIdOrderBySequenceNoDesc(anyLong())).thenReturn(Optional.empty());
        when(templateRepo.findById(ID)).thenReturn(Optional.of(existing()));

        admin = new MockHttpSession();
        admin.setAttribute("authenticated", Boolean.TRUE);
        admin.setAttribute("username", "ahmet");
        admin.setAttribute("fullName", "Ahmet Yılmaz");
        admin.setAttribute("systemRole", "ADMIN");
    }

    private static ScriptedTemplate existing() {
        ScriptedTemplate t = new ScriptedTemplate();
        t.setId(ID);
        t.setTeamId(TEAM_A);
        t.setName("Mevcut");
        t.setScript(SCRIPT);
        t.setDescription("açıklama");
        t.setWhenToUse("ne zaman");
        t.setTags("api,smoke");
        t.setActive(true);
        t.setCurrentVersion("1.0.0");
        t.setCreatedAt("2026-08-01T10:00:00");
        t.setCreatedBy("seed");
        return t;
    }

    private ScriptedTemplateVersion capturedVersion() {
        ArgumentCaptor<ScriptedTemplateVersion> cap = ArgumentCaptor.forClass(ScriptedTemplateVersion.class);
        verify(versionRepo).save(cap.capture());
        return cap.getValue();
    }

    // ── Sürümleme ────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("CREATE → 1.0.0 / seq 0 / eventType CREATE; kapsam sürüm satırına da yazılır")
    void createWritesFirstVersion() throws Exception {
        mvc.perform(post("/api/monitoring/scripted/templates").session(admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"teamId\":1,\"name\":\"Yeni\",\"script\":\"" + SCRIPT + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.current_version").value("1.0.0"));

        var v = capturedVersion();
        assertThat(v.getVersion()).isEqualTo("1.0.0");
        assertThat(v.getSequenceNo()).isZero();
        assertThat(v.getEventType()).isEqualTo("CREATE");
        // teamId sürüm satırında: PROMOTE/DEMOTE geçmişi join'siz okunabilsin.
        assertThat(v.getTeamId()).isEqualTo(TEAM_A);
        assertThat(v.getCreatedBy()).isEqualTo("ahmet");
        assertThat(v.getCreatedByName()).isEqualTo("Ahmet Yılmaz");
    }

    @Test
    @DisplayName("Yalnız AD/AÇIKLAMA değişince sürüm YAZILMAZ — geçmiş ayar düzenlemeleriyle şişmez")
    void metadataOnlyEditWritesNoVersion() throws Exception {
        mvc.perform(put("/api/monitoring/scripted/templates/" + ID).session(admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"description\":\"yeni açıklama\",\"whenToUse\":\"yeni\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.current_version").value("1.0.0"));

        verify(versionRepo, never()).save(any());
        // Ama denetim kaydı YAZILIR: metin değişikliği de "kim ne zaman" sorusuna girer.
        verify(auditService).recordAction(eq("TEMPLATE_UPDATE"), any(jakarta.servlet.http.HttpSession.class),
                eq("SCRIPTED_TEMPLATE"), eq(String.valueOf(ID)), anyString(), any());
    }

    @Test
    @DisplayName("Script değişince EDIT sürümü; bumpType uygulanır (minor → 1.1.0)")
    void scriptEditWritesVersionWithBump() throws Exception {
        ScriptedTemplateVersion last = new ScriptedTemplateVersion();
        last.setVersion("1.0.0");
        last.setSequenceNo(0);
        when(versionRepo.findTopByTemplateIdOrderBySequenceNoDesc(ID)).thenReturn(Optional.of(last));

        mvc.perform(put("/api/monitoring/scripted/templates/" + ID).session(admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"script\":\"export default function () { /* v2 */ }\",\"bumpType\":\"minor\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.current_version").value("1.1.0"));

        var v = capturedVersion();
        assertThat(v.getEventType()).isEqualTo("EDIT");
        assertThat(v.getSequenceNo()).isEqualTo(1);
    }

    @Test
    @DisplayName("env TANIMI değişmesi de içerik değişikliğidir — sürüm yazılır")
    void envChangeWritesVersion() throws Exception {
        mvc.perform(put("/api/monitoring/scripted/templates/" + ID).session(admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"env\":[{\"name\":\"BASE_URL\",\"desc\":\"hedef\"}]}"))
                .andExpect(status().isOk());
        assertThat(capturedVersion().getEventType()).isEqualTo("EDIT");
    }

    @Test
    @DisplayName("restoredFrom → RESTORE olayı, notu kendiliğinden yazılır")
    void restoreIsItsOwnEventType() throws Exception {
        mvc.perform(put("/api/monitoring/scripted/templates/" + ID).session(admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"script\":\"export default function () { /* eski */ }\",\"restoredFrom\":\"1.0.0\"}"))
                .andExpect(status().isOk());

        var v = capturedVersion();
        assertThat(v.getEventType()).isEqualTo("RESTORE");
        assertThat(v.getNote()).contains("1.0.0").contains("geri yüklendi");
    }

    /**
     * Sürüm geçmişi YARDIMCI bir kayıttır. Yazılamadı diye kullanıcının şablonu düşmemeli —
     * {@code writeScriptVersion} ikizindeki yutan try/catch'in sözleşmesi.
     */
    @Test
    @DisplayName("Sürüm satırı yazılamazsa kayıt YİNE DE başarılı olur (geçmiş ana işlemi kırmaz)")
    void versionWriteFailureDoesNotFailTheSave() throws Exception {
        when(versionRepo.save(any())).thenThrow(new RuntimeException("DB down"));

        mvc.perform(post("/api/monitoring/scripted/templates").session(admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"teamId\":1,\"name\":\"Yeni\",\"script\":\"" + SCRIPT + "\"}"))
                .andExpect(status().isOk());

        verify(templateRepo, atLeastOnce()).save(any());
    }

    // ── İçerik kuralları ─────────────────────────────────────────────────────────────────────

    /** Şablon paylaşılmak için var: gizli DEĞER taşımaz, yalnız değişken TANIMI. */
    @Test
    @DisplayName("env girdisinde `value` anahtarı → 400 (sessizce ayıklanmaz, GÜRÜLTÜLÜ reddedilir)")
    void envValueKeyIsRejected() throws Exception {
        mvc.perform(post("/api/monitoring/scripted/templates").session(admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"teamId\":1,\"name\":\"X\",\"script\":\"" + SCRIPT + "\","
                                + "\"env\":[{\"name\":\"TOKEN\",\"value\":\"s3cret\"}]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("gizli değer")));
        verify(templateRepo, never()).save(any());
    }

    /**
     * Monitör tarafında bu tarama {@code hardcoded-secret-policy} ayarına bağlı ve varsayılanı
     * WARN. Şablon bilinçli olarak DAHA KATI: bir monitörün script'i tek kayıtta yaşar, şablon
     * kopyalanmak için vardır — sızan değer N monitöre çoğalır ve kaynağı geriye izlenemez.
     */
    @Test
    @DisplayName("Gövdedeki sabit-kodlu secret KOŞULSUZ 400 — ayar WARN olsa bile")
    void hardcodedSecretIsAlwaysBlockedForTemplates() throws Exception {
        when(appSettings.getString(eq("site.monitor.scripted.hardcoded-secret-policy"), anyString()))
                .thenReturn("WARN");

        String leaky = "export default function () { const o = { password: 'sup3rgizli99' }; }";
        mvc.perform(post("/api/monitoring/scripted/templates").session(admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"teamId\":1,\"name\":\"X\",\"script\":\"" + leaky + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("__ENV")));
        verify(templateRepo, never()).save(any());
    }

    /**
     * Koşulsuz BLOCK, tarayıcının YANLIŞ-POZİTİFLERİNİ de koşulsuz hâle getirir — bu yüzden
     * 2026-08-20'de düzeltilen {@code 'Bearer ' + token} muafiyeti burada ayrıca pinlenir.
     * Yerleşik {@code oauth2-client-credentials} şablonu tam olarak bu kalıbı kullanıyor;
     * muafiyet gerilerse o şablon HİÇ kaydedilemez hâle gelir ve kaçış yolu da yoktur.
     */
    @Test
    @DisplayName("`Authorization: 'Bearer ' + token` engellenmez — gerçek değer değişkenden gelir")
    void concatenatedPrefixIsNotTreatedAsHardcodedSecret() throws Exception {
        String legit = "export default function () { const h = { Authorization: 'Bearer ' + token }; }";
        mvc.perform(post("/api/monitoring/scripted/templates").session(admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"teamId\":1,\"name\":\"OAuth\",\"script\":\"" + legit + "\"}"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("`export default function` yoksa 400 — k6 böyle bir script'i hiç çalıştıramaz")
    void scriptWithoutDefaultExportIsRejected() throws Exception {
        mvc.perform(post("/api/monitoring/scripted/templates").session(admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"teamId\":1,\"name\":\"X\",\"script\":\"console.log(1)\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Aynı kapsamda mükerrer ad → 400")
    void duplicateNameInScopeRejected() throws Exception {
        when(templateRepo.countDuplicateInScope(eq("Mevcut"), eq(TEAM_A), any())).thenReturn(1L);

        mvc.perform(post("/api/monitoring/scripted/templates").session(admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"teamId\":1,\"name\":\"Mevcut\",\"script\":\"" + SCRIPT + "\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Engellemeyen kusurlar warnings[] ile YANITTA taşınır (kaydı düşürmez)")
    void warningsRideAlongInTheResponse() throws Exception {
        mvc.perform(post("/api/monitoring/scripted/templates").session(admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        // açıklama ve whenToUse boş → WARN, BLOCK değil
                        .content("{\"teamId\":1,\"name\":\"X\",\"script\":\"" + SCRIPT + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.warnings").isArray())
                .andExpect(jsonPath("$.data.warnings.length()").value(org.hamcrest.Matchers.greaterThan(0)));
    }

    // ── Serileştirme sözleşmesi ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Etiketler varlıkta CSV, API'de DİZİ — iki yönde de dönüşür")
    void tagsRoundTripBetweenCsvAndArray() throws Exception {
        mvc.perform(get("/api/monitoring/scripted/templates/" + ID).session(admin))
                .andExpect(jsonPath("$.data.tags[0]").value("api"))
                .andExpect(jsonPath("$.data.tags[1]").value("smoke"));

        ArgumentCaptor<ScriptedTemplate> cap = ArgumentCaptor.forClass(ScriptedTemplate.class);
        mvc.perform(put("/api/monitoring/scripted/templates/" + ID).session(admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tags\":[\"perf\",\"soak\"]}"))
                .andExpect(status().isOk());
        verify(templateRepo, atLeastOnce()).save(cap.capture());
        assertThat(cap.getValue().getTags()).isEqualTo("perf,soak");
    }

    @Test
    @DisplayName("Liste yanıtı script GÖVDESİ taşımaz; tekil uç taşır")
    void listOmitsScriptBodyButDetailCarriesIt() throws Exception {
        when(templateRepo.findActiveGeneral()).thenReturn(List.of());
        when(templateRepo.findAllActive()).thenReturn(List.of(existing()));

        mvc.perform(get("/api/monitoring/scripted/templates").session(admin))
                .andExpect(jsonPath("$.data.templates[0].script").doesNotExist())
                .andExpect(jsonPath("$.data.templates[0].script_chars").value(SCRIPT.length()));

        mvc.perform(get("/api/monitoring/scripted/templates/" + ID).session(admin))
                .andExpect(jsonPath("$.data.script").value(SCRIPT));
    }

    /**
     * Seçicinin ürettiği {@code tpl:<token>} — seed'lenen yerleşik satır ESKİ monitörlerin
     * kolonundaki dizeyle aynı olmak zorunda, yoksa mevcut monitörlerin şablon paneli kaybolur.
     */
    @Test
    @DisplayName("select_token: yerleşikte builtin_key, kullanıcı şablonunda sayısal id")
    void selectTokenPreservesLegacyTemplateStrings() throws Exception {
        mvc.perform(get("/api/monitoring/scripted/templates/" + ID).session(admin))
                .andExpect(jsonPath("$.data.select_token").value("11"));   // kullanıcı şablonu → id

        ScriptedTemplate builtin = existing();
        builtin.setBuiltinKey("smoke-health");
        when(templateRepo.findById(ID)).thenReturn(Optional.of(builtin));
        mvc.perform(get("/api/monitoring/scripted/templates/" + ID).session(admin))
                .andExpect(jsonPath("$.data.select_token").value("smoke-health"));
    }

    // ── Denetim ──────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Her mutasyon denetim kaydı üretir — Denetim ekranı ve aktivite istatistikleri kendiliğinden kapsar")
    void everyMutationIsAudited() throws Exception {
        mvc.perform(post("/api/monitoring/scripted/templates").session(admin)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"teamId\":1,\"name\":\"Yeni\",\"script\":\"" + SCRIPT + "\"}"));
        mvc.perform(post("/api/monitoring/scripted/templates/" + ID + "/promote").session(admin));
        mvc.perform(delete("/api/monitoring/scripted/templates/" + ID).session(admin));

        verify(auditService).recordAction(eq("TEMPLATE_CREATE"), any(jakarta.servlet.http.HttpSession.class),
                eq("SCRIPTED_TEMPLATE"), anyString(), anyString(), any());
        verify(auditService).recordAction(eq("TEMPLATE_PROMOTE"), any(jakarta.servlet.http.HttpSession.class),
                eq("SCRIPTED_TEMPLATE"), anyString(), anyString(), any());
        verify(auditService).recordAction(eq("TEMPLATE_DELETE"), any(jakarta.servlet.http.HttpSession.class),
                eq("SCRIPTED_TEMPLATE"), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("Yumuşak silme kimin sildiğini KAYDEDER (K4'ün vaadi `active` tek başına karşılamaz)")
    void softDeleteRecordsWhoAndWhen() throws Exception {
        ArgumentCaptor<ScriptedTemplate> cap = ArgumentCaptor.forClass(ScriptedTemplate.class);
        mvc.perform(delete("/api/monitoring/scripted/templates/" + ID).session(admin))
                .andExpect(status().isOk());
        verify(templateRepo).save(cap.capture());
        assertThat(cap.getValue().getActive()).isFalse();
        assertThat(cap.getValue().getDeletedBy()).isEqualTo("ahmet");
        assertThat(cap.getValue().getDeletedAt()).isNotBlank();
    }
}
