package com.sitemonitor.controller;

import com.sitemonitor.model.AppUser;
import com.sitemonitor.model.AuditLog;
import com.sitemonitor.model.Team;
import com.sitemonitor.repository.AppUserRepository;
import com.sitemonitor.repository.AuditLogRepository;
import com.sitemonitor.repository.TeamRepository;
import com.sitemonitor.service.AppSettingsService;
import com.sitemonitor.service.PageUsageService;
import com.sitemonitor.service.UserActivityService;
import com.sitemonitor.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * KAPI (2026-09-28c, B1): Kullanıcı / Oturum payload'ının TAMAMI kimlik izi maskesinden geçer.
 *
 * <p>Payload GERÇEK {@link UserActivityService#getOverview()} ile üretilir (elle yazılmış bir Map değil) — böylece
 * servise yeni bir yüzey eklenip bilinen IP / tarayıcı / konum değerlerini taşırsa bu test kırılır. Fixture'da
 * önceki maskenin kaçırdığı her yapı bulunur: {@code top_sources}, {@code details.{logins,failed,anomalies}},
 * Map olan {@code anomalies} ({@code recent[]}), {@code heatmaps[].cells}, {@code active_users}, {@code login_status}.
 * Maskelenmiş yük JSON'a çevrilir ve bilinen değerlerin HİÇBİR YERDE geçmediği iddia edilir (anahtar adından
 * bağımsız); pozitif kontrol: global görüntüleyici yükünde hepsi VAR (yoksa test değer aramıyor demektir).
 * Örnek IP'ler RFC 5737 belgeleme blokları (192.0.2.x / 198.51.100.x / 203.0.113.x).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class IdentityMaskGateTest {

    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);
    private static final ObjectMapper JSON = new ObjectMapper();

    /** Başkalarının izi — maskeli yükte HİÇBİRİ geçmemeli. */
    private static final List<String> OTHERS_TRACE = List.of(
            "198.51.100.21", "198.51.100.22", "192.0.2.31", "192.0.2.32", "192.0.2.33",
            "GateBrowser/77.1", "GateCity", "GateLand", "Gate Org ISP", "gate-rdns.example.com");
    /** Görüntüleyicinin KENDİ izi — kendi satırlarında kalır. */
    private static final String SELF_IP = "203.0.113.50";

    @Mock AuditLogRepository auditLogRepo;
    @Mock AppUserRepository userRepo;
    @Mock TeamRepository teamRepo;
    @Mock UserService userService;
    @Mock PageUsageService pageUsage;
    @Mock AppSettingsService appSettings;
    @Mock JdbcTemplate jdbc;

    private UserActivityService service;

    private static AppUser user(long id, String name, String lastIp, String prevIp, String failedIp) {
        AppUser u = new AppUser();
        u.setId(id); u.setUsername(name); u.setTeamId(5L); u.setActive(true);
        u.setLastLoginAt(ISO.format(Instant.now().minus(1, ChronoUnit.HOURS)));
        u.setLastLoginIp(lastIp); u.setPrevLoginIp(prevIp); u.setLastFailedLoginIp(failedIp);
        u.setActiveSessionId("sess-" + id);
        return u;
    }

    private static AuditLog ev(long id, String actor, String ip, String outcome, String flags, int minutesAgo) {
        AuditLog a = new AuditLog();
        a.setId(id);
        a.setEventType("SUCCESS".equals(outcome) ? "LOGIN" : "LOGIN_FAILED");
        a.setEventTime(ISO.format(Instant.now().minus(minutesAgo, ChronoUnit.MINUTES)));
        a.setActor(actor); a.setActorTeamId(5L); a.setOutcome(outcome); a.setAnomalyFlags(flags);
        a.setIpAddress(ip); a.setIpCity("GateCity"); a.setIpCountry("GateLand"); a.setIpOrg("Gate Org ISP");
        a.setIpReverseHost("gate-rdns.example.com");
        a.setUserAgent("Mozilla/5.0 (Windows NT 10.0) GateBrowser/77.1");
        a.setSessionId("sess-" + id);
        return a;
    }

    @BeforeEach
    void setUp() {
        service = new UserActivityService(auditLogRepo, userRepo, teamRepo, userService, pageUsage, jdbc, appSettings);
        Team t = new Team(); t.setId(5L); t.setName("Takım A");
        when(teamRepo.findAll()).thenReturn(List.of(t));
        when(appSettings.getInt(eq("site.monitor.ui.inactivity-minutes"), anyInt())).thenReturn(30);
        when(pageUsage.rowsSince(anyInt())).thenReturn(List.of());

        AppUser bob = user(1, "bob", "192.0.2.31", "192.0.2.32", "192.0.2.33");
        AppUser alice = user(2, "alice", SELF_IP, SELF_IP, SELF_IP);
        when(userRepo.findAll()).thenReturn(List.of(bob, alice));
        when(userRepo.findAllWithActiveSession()).thenReturn(List.of(bob, alice));
        when(userService.hasLiveSession(any())).thenReturn(true);

        List<AuditLog> window = new ArrayList<>(List.of(
                ev(11, "bob", "198.51.100.21", "SUCCESS", null, 30),
                ev(12, "bob", "198.51.100.22", "FAILURE", "UNUSUAL_IP", 20),   // başarısız + anomali
                ev(13, "alice", SELF_IP, "SUCCESS", "OFF_HOURS", 10)));
        when(auditLogRepo.findLoginEventsSince(any(), anyString())).thenReturn(window);
        when(auditLogRepo.findTopByActorAndSessionIdAndEventTypeOrderByEventTimeDesc(eq("bob"), any(), any()))
                .thenReturn(Optional.of(window.get(0)));
        when(auditLogRepo.findTopByActorAndSessionIdAndEventTypeOrderByEventTimeDesc(eq("alice"), any(), any()))
                .thenReturn(Optional.of(window.get(2)));
    }

    @SuppressWarnings("unchecked")
    @Test
    @DisplayName("fixture önceki maskenin kaçırdığı TÜM yapıları taşır (yoksa kapı sınıfı yakalamaz)")
    void fixtureCoversEverySurface() {
        Map<String, Object> o = service.getOverview();
        assertThat((List<?>) o.get("top_sources")).isNotEmpty();
        Map<String, Object> details = (Map<String, Object>) o.get("details");
        assertThat((List<?>) details.get("logins")).isNotEmpty();
        assertThat((List<?>) details.get("failed")).isNotEmpty();
        assertThat((List<?>) details.get("anomalies")).isNotEmpty();
        assertThat(o.get("anomalies")).isInstanceOf(Map.class);
        assertThat((List<?>) ((Map<String, Object>) o.get("anomalies")).get("recent")).isNotEmpty();
        List<Map<String, Object>> heat = (List<Map<String, Object>>) o.get("heatmaps");
        assertThat(heat.stream().anyMatch(h -> !((Map<?, ?>) h.get("cells")).isEmpty())).isTrue();
        assertThat((List<?>) o.get("active_users")).hasSize(2);
        assertThat((List<?>) o.get("login_status")).hasSize(2);
    }

    @Test
    @DisplayName("maskeli yük (USER / kapsamlı müdür): bilinen IP / konum / kuruluş / ters DNS / tarayıcı HİÇBİR yerde yok")
    void maskedPayloadCarriesNoTrace() {
        Map<String, Object> raw = service.getOverview();
        String visible = JSON.writeValueAsString(IdentityMask.apply(raw, true, "admin"));
        // Pozitif kontrol: aynı değerler maskesiz yükte GERÇEKTEN var.
        for (String v : OTHERS_TRACE) assertThat(visible).as("maskesiz yükte %s olmalı", v).contains(v);
        assertThat(visible).contains(SELF_IP).contains("\"top_sources\"");

        Map<String, Object> masked = IdentityMask.apply(raw, false, null);
        String json = JSON.writeValueAsString(masked);
        for (String v : OTHERS_TRACE) assertThat(json).as("maskeli yükte %s kalmamalı", v).doesNotContain(v);
        assertThat(json).doesNotContain(SELF_IP);
        assertThat(masked).doesNotContainKey("top_sources").containsEntry(IdentityMask.FLAG, true);
        for (String f : IdentityMask.FIELDS) assertThat(json).as("anahtar %s", f).doesNotContain("\"" + f + "\"");
        // Sayılar / özetler kalır (bölüm çizilebilsin).
        assertThat(json).contains("\"summary\"").contains("\"counts\"").contains("\"matrix\"");
    }

    @Test
    @DisplayName("kişinin KENDİ satırları (username / actor) izini korur; başkalarınınki düşer")
    void selfRowsKeepTheirOwnTrace() {
        String json = JSON.writeValueAsString(IdentityMask.apply(service.getOverview(), false, "ALICE"));
        assertThat(json).contains(SELF_IP);
        for (String v : List.of("198.51.100.21", "198.51.100.22", "192.0.2.31", "192.0.2.32", "192.0.2.33")) {
            assertThat(json).doesNotContain(v);
        }
        assertThat(json).doesNotContain("\"top_sources\"");   // IP anahtarlı liste kendi satırı olsa da gitmez
    }

    @Test
    @DisplayName("maske paylaşılan önbellek nesnesini DEĞİŞTİRMEZ (copy-on-write) — sonraki global görüntüleyici tam veri alır")
    void cachedPayloadIsNeverMutated() {
        Map<String, Object> raw = service.getOverview();
        String before = JSON.writeValueAsString(raw);
        IdentityMask.apply(raw, false, null);
        IdentityMask.apply(raw, false, "bob");
        assertThat(JSON.writeValueAsString(raw)).isEqualTo(before);
    }

    // ── Kaynak kapısı (2026-09-28c ek): kardeş yüzeyler ────────────────────────────────────────────────────────
    // Değişiklik geçmişi uçları (Yönetim Paneli /history, bildirim grubu geçmişi, saklama değişiklikleri, İzleme
    // Değişiklikleri, giriş sorunu bildirimleri) eylemi yapanın IP / tarayıcısını satıra koyuyordu ve maskeden
    // geçmiyordu. Davranış testleri her ucun kendi denetleyici testinde (USER / kapsamlı müdür → yok; global admin /
    // AUDIT → var); bu kapı YENİ bir yüzeyi yakalar: kimlik izi getter'ını bir Map'e koyan her denetleyici / servis
    // IdentityMask'i anmalı ya da aşağıda gerekçeli istisna olmalı.

    private static final java.util.regex.Pattern TRACE_PUT = java.util.regex.Pattern.compile(
            "[.]put[(][ ]*\"[^\"]+\"[ ]*,[^;]*?[.]get(IpAddress|UserAgent|IpCity|IpCountry|IpOrg|IpReverseHost"
                    + "|LastLoginIp|PrevLoginIp|LastFailedLoginIp)[(][)]");

    /** Maskeyi kendisi uygulamayan ama güvenli olan üreticiler — gerekçesiyle. Eskiyen istisna da kırmızıdır. */
    private static final Map<String, String> TRACE_ALLOWED = Map.of(
            "UserActivityService.java", "yük SystemController'da IdentityMask'ten geçer (/user-activity, /user-activity/user/{u})",
            "DeviceHistoryService.java", "tüketicileri AuditController (requireAuditAccess: global admin / AUDIT) ve "
                    + "AuthController (kişinin KENDİ cihazları)");

    @Test
    @DisplayName("kaynak kapısı: kimlik izini Map satırına koyan her denetleyici / servis IdentityMask'ten geçer (ya da gerekçeli istisna)")
    void everyTraceProducerGoesThroughTheMask() throws Exception {
        java.nio.file.Path root = java.nio.file.Path.of("src/main/java/com/sitemonitor");
        if (!java.nio.file.Files.isDirectory(root)) root = java.nio.file.Path.of("backend/src/main/java/com/sitemonitor");
        List<String> offenders = new ArrayList<>();
        java.util.Set<String> producers = new java.util.TreeSet<>();
        try (var walk = java.nio.file.Files.walk(root)) {
            for (java.nio.file.Path p : walk.filter(f -> f.toString().endsWith(".java")).toList()) {
                String src = java.nio.file.Files.readString(p, java.nio.charset.StandardCharsets.UTF_8);
                if (!TRACE_PUT.matcher(src).find()) continue;
                String name = p.getFileName().toString();
                producers.add(name);
                if (!src.contains("IdentityMask") && !TRACE_ALLOWED.containsKey(name)) offenders.add(name);
            }
        }
        assertThat(offenders).as("kimlik izini maskesiz döndüren üretici").isEmpty();
        assertThat(producers).as("tarama vakum değil").contains("AdminController.java", "NotificationGroupController.java",
                "RetentionAdminController.java", "MonitoringController.java", "LoginIssueController.java");
        assertThat(producers).as("eskiyen istisna").containsAll(TRACE_ALLOWED.keySet());
    }

    @Test
    @DisplayName("satır işareti: izi düşürülen satır identity_masked=true taşır; kendi satırı ve izsiz satır işaretlenmez")
    void droppedRowsAreMarked() {
        Map<String, Object> payload = Map.of("items", List.of(
                Map.of("actor", "bob", "ip", "192.0.2.5"),
                Map.of("actor", "alice", "ip", "192.0.2.6"),
                Map.of("actor", "carol", "outcome", "SUCCESS")));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) IdentityMask.apply(payload, false, "alice").get("items");
        assertThat(rows.get(0)).doesNotContainKey("ip").containsEntry(IdentityMask.FLAG, true);
        assertThat(rows.get(1)).containsEntry("ip", "192.0.2.6").doesNotContainKey(IdentityMask.FLAG);
        assertThat(rows.get(2)).doesNotContainKey(IdentityMask.FLAG);
    }

    @Test
    @DisplayName("JSON metni alanı (autoContextJson): metnin içindeki iz düşer, izsiz kısım kalır; bozuk metin alanı düşer")
    void jsonTextFieldIsCleaned() {
        Map<String, Object> row = Map.of("username", "bob",
                "autoContextJson", "{\"url\":\"/app\",\"ip\":\"192.0.2.9\",\"userAgent\":\"GateBrowser/5\"}");
        Map<String, Object> masked = IdentityMask.apply(row, false, null);
        assertThat(String.valueOf(masked.get("autoContextJson"))).contains("/app").doesNotContain("192.0.2.9").doesNotContain("GateBrowser");
        assertThat(IdentityMask.apply(row, false, "bob")).containsEntry("autoContextJson", row.get("autoContextJson"));   // kendi bildirimi
        assertThat(IdentityMask.apply(Map.of("autoContextJson", "{bozuk"), false, null)).doesNotContainKey("autoContextJson");
        assertThat(IdentityMask.apply(Map.of("autoContextJson", "{\"url\":\"/x\"}"), false, null))
                .containsEntry("autoContextJson", "{\"url\":\"/x\"}");   // izsiz metin aynen
    }

    @Test
    @DisplayName("kullanıcı zaman çizelgesi (başkasının): olay satırlarında iz yok, sayılar kalır")
    void timelineOfSomeoneElseIsMasked() {
        Map<String, Object> tl = service.userTimeline("bob", 20);
        assertThat(JSON.writeValueAsString(tl)).contains("198.51.100.21").contains("GateBrowser/77.1");   // pozitif kontrol
        Map<String, Object> masked = IdentityMask.apply(tl, false, null);
        String json = JSON.writeValueAsString(masked);
        for (String v : OTHERS_TRACE) assertThat(json).doesNotContain(v);
        assertThat(masked).containsKeys("logins", "failed", "distinct_ips").containsEntry(IdentityMask.FLAG, true);
    }
}
