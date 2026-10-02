package com.sitemonitor.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitemonitor.model.MaintenanceWindow;
import com.sitemonitor.model.Team;
import com.sitemonitor.repository.IncidentRecordRepository;
import com.sitemonitor.repository.MaintenanceWindowRepository;
import com.sitemonitor.repository.TeamRepository;
import com.sitemonitor.util.TtlMemo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static com.sitemonitor.service.StatusPageService.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Kurum içi Durum Sayfası (2026-10-01): izleme sağlığı / hizmet / kurum durumu kuralları, hizmet gruplama (takım + grup
 * adı, türler boyunca), bakım pencereleri, olay kaydı özetleri, 7 günlük rollup kullanılabilirliği, görüntüleyici izdüşümü
 * ("mevcudu bozma": izleme listesi / olay satırı / bakım satırı yalnız bugünkü kurallarla görülebilene; kalanı yalnız
 * sayı), sızıntı kapısı (URL/host/hedef/hata/açıklama ve kapsam dışı başlıklar yanıtta YOK) ve bellek.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class StatusPageServiceTest {

    @Mock MonitoringOverviewService overview;
    @Mock MaintenanceWindowRepository maintenanceRepo;
    @Mock IncidentRecordRepository incidentRepo;
    @Mock TeamRepository teamRepo;
    @Mock JdbcTemplate jdbc;

    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);
    private static String ago(long minutes) { return ISO.format(Instant.now().minus(minutes, ChronoUnit.MINUTES)); }

    /** Takım 14'ü gören kapsamlı kullanıcı — denetleyicinin kurduğu yüklemlerin aynısı (iki izin de var). */
    private static final Viewer SCOPED_14 = new Viewer(
            team -> Objects.equals(team, 14L),
            (team, creator) -> Objects.equals(team, 14L) || Objects.equals(creator, 14L),
            (team, all) -> Objects.equals(team, 14L) || Boolean.TRUE.equals(all) || team == null);

    private StatusPageService svc;
    private final List<Map<String, Object>> rows = new ArrayList<>();

    @BeforeEach
    void setUp() {
        svc = new StatusPageService(overview, new MaintenanceService(maintenanceRepo), maintenanceRepo, incidentRepo, teamRepo, jdbc);
        Team sy = new Team(); sy.setId(14L); sy.setName("SY");
        Team other = new Team(); other.setId(99L); other.setName("Ödeme");
        when(teamRepo.findAll()).thenReturn(List.of(sy, other));
        when(maintenanceRepo.findByActiveTrue()).thenReturn(List.of());
        when(incidentRepo.statusPageActive(any(Pageable.class))).thenReturn(List.of());
        when(incidentRepo.statusPageResolvedSince(anyString(), any(Pageable.class))).thenReturn(List.of());
        when(incidentRepo.statusPageActiveCounts()).thenReturn(List.of());
        when(incidentRepo.statusPageResolvedSinceCounts(anyString())).thenReturn(List.of());
        when(jdbc.queryForList(anyString(), anyString(), anyString())).thenReturn(List.of());
        when(overview.build(anyString(), any(), anyBoolean(), anyInt(), anyBoolean())).thenAnswer(inv -> {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("generated_at", "2026-10-01T09:00:00");
            out.put("monitors", rows);
            return out;
        });
    }

    /** Her şeyi gören görüntüleyicinin yanıtı (taban + izdüşüm, bellek yok). */
    private Map<String, Object> all(Instant now) { return StatusPageService.project(svc.computeBase(false, now), Viewer.all()); }
    private Map<String, Object> all() { return all(Instant.now()); }

    /** Pano satırı (MonitoringOverviewService.summarize çıktısıyla aynı alanlar). */
    private static Map<String, Object> row(String type, long id, String name, String target, Long team, String teamName,
                                           String group, String status) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("type", type); r.put("id", id); r.put("name", name); r.put("target", target);
        r.put("team_id", team); r.put("team_name", teamName);
        r.put("active", !"paused".equals(status) && !"deleted".equals(status));
        r.put("deleted", "deleted".equals(status));
        r.put("standalone", null);
        r.put("inventory_inactive", false);
        r.put("status", status);
        r.put("last_checked_at", "unknown".equals(status) ? null : ago(2));
        r.put("last_ok", "down".equals(status) ? Boolean.FALSE : "unknown".equals(status) ? null : Boolean.TRUE);
        r.put("response_ms", 120L);
        r.put("last_error", "down".equals(status) ? "Connection refused: " + target : null);
        r.put("interval_seconds", 300);
        r.put("open_alerts", 0);
        r.put("open_alert_level", null);
        r.put("open_since", null);
        r.put("open_acknowledged", false);
        r.put("group_name", group);
        return r;
    }

    private static Map<String, Object> withAlert(Map<String, Object> r, String level, String since, Boolean lastOk) {
        r.put("open_alerts", 1); r.put("open_alert_level", level); r.put("open_since", since); r.put("last_ok", lastOk);
        r.put("status", "down");
        return r;
    }

    /** Açık olay projeksiyon satırı: [id, title, severity, status, occurredAt, service, teamId, teamName, createdByTeamId]. */
    private static Object[] activeInc(long id, String title, String sev, Long team, String teamName, Long createdBy) {
        return new Object[]{id, title, sev, "OPEN", "2026-09-30T10:00:00", "Ödeme API", team, teamName, createdBy};
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> services(Map<String, Object> out) { return (List<Map<String, Object>>) out.get("services"); }
    private static Map<String, Object> service(Map<String, Object> out, String key) {
        return services(out).stream().filter(s -> key.equals(s.get("key"))).findFirst().orElseThrow(() -> new AssertionError("hizmet yok: " + key));
    }
    @SuppressWarnings("unchecked")
    private static Map<String, Object> sub(Map<String, Object> m, String k) { return (Map<String, Object>) m.get(k); }
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> list(Map<String, Object> m, String k) { return (List<Map<String, Object>>) m.get(k); }

    // ── Kurallar ─────────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("İzleme sağlığı: silinmiş/envanterden çıkmış sayılmaz; duraklatılmış, bakım, veri yok, gecikmiş, uyarı-alarmı, alan adı, düşük")
    void monitorHealth_rules() {
        assertThat(monitorHealth(row("http", 1, "a", "https://a", 14L, "SY", null, "deleted"), false)).isNull();
        Map<String, Object> inv = row("port", 2, "p", "h", 14L, "SY", null, "paused"); inv.put("inventory_inactive", true);
        assertThat(monitorHealth(inv, false)).isNull();
        assertThat(monitorHealth(row("http", 3, "a", "https://a", 14L, "SY", null, "paused"), true)).isEqualTo(M_PAUSED);
        assertThat(monitorHealth(row("http", 4, "a", "https://a", 14L, "SY", null, "down"), true)).isEqualTo(M_MAINTENANCE);
        assertThat(monitorHealth(row("http", 5, "a", "https://a", 14L, "SY", null, "unknown"), false)).isEqualTo(M_UNKNOWN);
        assertThat(monitorHealth(row("http", 6, "a", "https://a", 14L, "SY", null, "stale"), false)).isEqualTo(M_DEGRADED);
        assertThat(monitorHealth(row("http", 7, "a", "https://a", 14L, "SY", null, "up"), false)).isEqualTo(M_UP);
        // Son kontrol başarısız → düşük (alarm olmasa da)
        assertThat(monitorHealth(row("http", 8, "a", "https://a", 14L, "SY", null, "down"), false)).isEqualTo(M_DOWN);
        // Yalnız UYARI seviyeli açık alarm + son kontrol başarılı (yavaşlık) → bozulmuş, kesinti değil
        assertThat(monitorHealth(withAlert(row("http", 9, "a", "https://a", 14L, "SY", null, "up"), "WARNING", ago(5), true), false)).isEqualTo(M_DEGRADED);
        // HIGH/CRITICAL açık alarm → düşük (son kontrol başarılı olsa da — kurtarma teyidi bekleniyor)
        assertThat(monitorHealth(withAlert(row("http", 10, "a", "https://a", 14L, "SY", null, "up"), "critical", ago(5), true), false)).isEqualTo(M_DOWN);
        assertThat(monitorHealth(withAlert(row("ping", 11, "a", "h", 14L, "SY", null, "up"), "HIGH", ago(5), false), false)).isEqualTo(M_DOWN);
        // Alan adı kaydı sorunu erişim kesintisi değildir → en fazla bozulmuş
        assertThat(monitorHealth(withAlert(row("domain", 12, "d", "example.com", 14L, "SY", null, "up"), "CRITICAL", ago(5), false), false)).isEqualTo(M_DEGRADED);
    }

    @Test
    @DisplayName("Hizmet durumu: bakım / veri yok / yarıdan fazlası düşük → büyük kesinti, aksi kısmi / bozulmuş / çalışıyor")
    void serviceState_rules() {
        // (aktif, bakım, veri yok, düşük, bozulmuş)
        assertThat(serviceState(0, 0, 0, 0, 0)).isNull();
        assertThat(serviceState(3, 3, 0, 0, 0)).isEqualTo(MAINTENANCE);
        assertThat(serviceState(3, 1, 2, 0, 0)).isEqualTo(MAINTENANCE);       // değerlendirilecek yok, bakım var
        assertThat(serviceState(2, 0, 2, 0, 0)).isEqualTo(NO_DATA);
        assertThat(serviceState(1, 0, 0, 1, 0)).isEqualTo(MAJOR_OUTAGE);      // tek izleme düşük
        assertThat(serviceState(2, 0, 0, 1, 0)).isEqualTo(PARTIAL_OUTAGE);    // tam yarı → kısmi
        assertThat(serviceState(3, 0, 0, 2, 0)).isEqualTo(MAJOR_OUTAGE);      // yarıdan fazla
        assertThat(serviceState(4, 1, 1, 1, 0)).isEqualTo(PARTIAL_OUTAGE);    // E = 2, D = 1
        assertThat(serviceState(3, 1, 0, 0, 1)).isEqualTo(DEGRADED);          // bakım + bozulmuş → bozulmuş
        assertThat(serviceState(3, 1, 0, 0, 0)).isEqualTo(OPERATIONAL);       // bir kısmı bakımda, kalanı sağlıklı
        assertThat(serviceState(5, 0, 1, 0, 0)).isEqualTo(OPERATIONAL);
    }

    @Test
    @DisplayName("Kurum durumu = en kötü hizmet; hizmet yoksa no_data; sıra no_data < operational < maintenance < degraded < partial < major")
    void overallState_worstWins() {
        assertThat(overallState(List.of())).isEqualTo(NO_DATA);
        assertThat(overallState(List.of(NO_DATA, OPERATIONAL))).isEqualTo(OPERATIONAL);
        assertThat(overallState(List.of(OPERATIONAL, MAINTENANCE))).isEqualTo(MAINTENANCE);
        assertThat(overallState(List.of(MAINTENANCE, DEGRADED, OPERATIONAL))).isEqualTo(DEGRADED);
        assertThat(overallState(List.of(DEGRADED, PARTIAL_OUTAGE))).isEqualTo(PARTIAL_OUTAGE);
        assertThat(overallState(List.of(PARTIAL_OUTAGE, MAJOR_OUTAGE, OPERATIONAL))).isEqualTo(MAJOR_OUTAGE);
        assertThat(STATE_ORDER).containsExactly(NO_DATA, OPERATIONAL, MAINTENANCE, DEGRADED, PARTIAL_OUTAGE, MAJOR_OUTAGE);
    }

    // ── Hizmet gruplama ──────────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Hizmet = takım + grup adı (türler boyunca, harf duyarsız); grupsuzlar takım başına tek hizmet; silinmiş/tamamı duraklatılmış dışarıda")
    void groupsServicesAcrossTypes() {
        rows.add(row("http", 1, "Ödeme web", "https://pay.example.com", 14L, "SY", "Ödeme", "up"));
        rows.add(row("ping", 2, "Ödeme host", "10.0.0.5", 14L, "SY", " ödeme ", "down"));             // aynı hizmet (harf/boşluk)
        rows.add(withAlert(row("port", 3, "Ödeme 443", "10.0.0.5", 14L, "SY", "ÖDEME", "up"), "CRITICAL", "2026-10-01T08:00:00", false));
        rows.add(row("http", 4, "Kart web", "https://card.example.com", 14L, "SY", "Kart", "stale"));
        rows.add(row("http", 5, "Grupsuz 1", "https://x.example.com", 14L, "SY", null, "up"));
        rows.add(row("dns", 6, "Grupsuz 2", "x.example.com", 14L, "SY", "  ", "unknown"));            // boş grup = grupsuz
        rows.add(row("http", 7, "Silinmiş", "https://gone.example.com", 14L, "SY", "Ödeme", "deleted"));
        rows.add(row("http", 8, "Hep duraklatılmış", "https://p.example.com", 14L, "SY", "Arşiv", "paused"));
        rows.add(row("scripted", 9, "Başka takım", "Başka takım", 99L, "Ödeme", "Mobil", "up"));
        rows.add(row("http", 10, "Takımsız", "https://nt.example.com", null, null, "Ortak", "up"));

        Map<String, Object> out = all();

        assertThat(services(out)).extracting(s -> s.get("key"))
                .containsExactlyInAnyOrder("14|ödeme", "14|kart", "14|", "99|mobil", "-|ortak")
                .doesNotContain("14|arşiv");
        Map<String, Object> pay = service(out, "14|ödeme");
        // Ödeme: 3 aktif (http up, ping down, port down) → 2/3 düşük → büyük kesinti; silinmiş sayılmaz
        assertThat(pay).containsEntry("name", "Ödeme").containsEntry("ungrouped", false).containsEntry("team_name", "SY")
                .containsEntry("state", MAJOR_OUTAGE).containsEntry("monitors_total", 3).containsEntry("monitors_active", 3)
                .containsEntry("down", 2).containsEntry("up", 1).containsEntry("since", "2026-10-01T08:00:00");
        assertThat(pay.get("types")).isEqualTo(List.of("http", "ping", "port"));   // MonitorTypeCatalog sırası
        assertThat(service(out, "14|kart")).containsEntry("state", DEGRADED);
        Map<String, Object> ungrouped = service(out, "14|");
        assertThat(ungrouped).containsEntry("ungrouped", true).containsEntry("name", null).containsEntry("state", OPERATIONAL)
                .containsEntry("unknown", 1).containsEntry("since", null);
        assertThat(service(out, "-|ortak")).containsEntry("team_id", null).containsEntry("team_name", null);
        // Sıra: takım adı A→Z (Türkçe), takımsız en sonda
        assertThat(services(out)).extracting(s -> s.get("team_name")).last().isNull();

        Map<String, Object> overall = sub(out, "overall");
        assertThat(overall).containsEntry("state", MAJOR_OUTAGE).containsEntry("services_total", 5L)
                .containsEntry("monitors_down", 2L).containsEntry("monitors_degraded", 1L);
        assertThat(sub(overall, "by_state")).containsEntry(OPERATIONAL, 3L).containsEntry(DEGRADED, 1L).containsEntry(MAJOR_OUTAGE, 1L)
                .containsEntry(PARTIAL_OUTAGE, 0L);
        assertThat(out).containsEntry("generated_at", "2026-10-01T09:00:00");
        // Pano çağrısı kurum geneli: ALL kapsamı, tüm takımlar, 24 sa
        verify(overview).build(eq("ALL"), argThat(p -> p.test(null) && p.test(14L) && p.test(12345L)), eq(true), eq(24), eq(false));
    }

    @Test
    @DisplayName("7 günlük kullanılabilirlik: rollup'tan (tür|id) toplanır; DNS/alan adı rollup'ta yok → yalnız onlardan oluşan hizmette null")
    void uptimeFromDailyRollup() {
        rows.add(row("http", 1, "web", "https://pay.example.com", 14L, "SY", "Ödeme", "up"));
        rows.add(row("ping", 2, "host", "10.0.0.5", 14L, "SY", "Ödeme", "up"));
        rows.add(row("dns", 3, "dns", "pay.example.com", 14L, "SY", "DNS", "up"));
        Map<String, Object> a = new LinkedHashMap<>(Map.of("monitor_type", "HTTP", "monitor_key", "1", "total", 1000L, "up", 990L));
        Map<String, Object> b = new LinkedHashMap<>(Map.of("monitor_type", "PING", "monitor_key", "2", "total", 1000L, "up", 1000L));
        Map<String, Object> junk = new LinkedHashMap<>(Map.of("monitor_type", "UPTIME", "monitor_key", "pay.example.com", "total", 5L, "up", 0L));
        String today = java.time.LocalDate.now(ZoneOffset.UTC).toString();
        String from = java.time.LocalDate.now(ZoneOffset.UTC).minusDays(7).toString();
        when(jdbc.queryForList(anyString(), eq(from), eq(today))).thenReturn(List.of(a, b, junk));

        Map<String, Object> out = all();

        assertThat(service(out, "14|ödeme")).containsEntry("uptime_7d", 99.5);
        assertThat(service(out, "14|dns")).containsEntry("uptime_7d", null);
        assertThat(sub(out, "uptime")).containsEntry("available", true).containsEntry("days", 7).containsEntry("from", from);
        verify(jdbc).queryForList(eq(UPTIME_SQL), eq(from), eq(today));
    }

    @Test
    @DisplayName("Rollup okunamazsa (H2 / tablo yok): available=false, hizmet uptime'ı null — sayfa yine çizilir")
    void uptimeUnavailable() {
        rows.add(row("http", 1, "web", "https://pay.example.com", 14L, "SY", "Ödeme", "up"));
        when(jdbc.queryForList(anyString(), anyString(), anyString())).thenThrow(new RuntimeException("relation \"monitor_check_daily\" does not exist"));
        Map<String, Object> out = all();
        assertThat(sub(out, "uptime")).containsEntry("available", false);
        assertThat(service(out, "14|ödeme")).containsEntry("uptime_7d", null).containsEntry("state", OPERATIONAL);
    }

    // ── Bakım pencereleri ────────────────────────────────────────────────────────────────────────────────────────

    private static MaintenanceWindow window(long id, String name, Instant start, int minutes, String targetsJson, boolean all, Long team) {
        MaintenanceWindow w = new MaintenanceWindow();
        w.setId(id); w.setName(name); w.setStartAt(ISO.format(start)); w.setDurationMinutes(minutes); w.setRecurrence("NONE");
        w.setActive(true); w.setAllMonitors(all); w.setTargetsJson(targetsJson); w.setTeamId(team);
        w.setDescription("İç not: db parolası rotasyonu, nöbetçi 0555");
        return w;
    }
    private static MaintenanceWindow window(long id, String name, Instant start, int minutes, String targetsJson, boolean all) {
        return window(id, name, start, minutes, targetsJson, all, 14L);
    }

    @Test
    @DisplayName("Bakım: etkin pencere hedefindeki izlemeler 'maintenance'; tamamı bakımda → hizmet bakımda + bitiş; 7 gün içindeki yaklaşan listelenir, sonrası listelenmez")
    void maintenanceWindows() {
        Instant now = Instant.now();
        rows.add(row("http", 1, "web", "https://pay.example.com", 14L, "SY", "Ödeme", "down"));
        rows.add(row("ping", 2, "host", "10.0.0.5", 14L, "SY", "Ödeme", "up"));
        rows.add(row("http", 3, "kart", "https://card.example.com", 14L, "SY", "Kart", "down"));
        rows.add(row("ping", 4, "kart host", "10.0.0.9", 14L, "SY", "Kart", "up"));
        when(maintenanceRepo.findByActiveTrue()).thenReturn(List.of(
                window(1, "Ödeme bakım", now.minus(10, ChronoUnit.MINUTES), 60,
                        "[{\"type\":\"http\",\"target\":\"https://pay.example.com\",\"name\":\"web\"},{\"type\":\"ping\",\"target\":\"10.0.0.5\",\"name\":\"host\"}]", false),
                window(2, "Kart bakım", now.minus(5, ChronoUnit.MINUTES), 30,
                        "[{\"type\":\"http\",\"target\":\"https://card.example.com\",\"name\":\"kart\"}]", false),
                window(3, "Yarın DB", now.plus(1, ChronoUnit.DAYS), 120, "[{\"type\":\"ping\",\"target\":\"10.0.0.9\"}]", false),
                window(4, "Ay sonu", now.plus(20, ChronoUnit.DAYS), 60, "[]", false)));

        Map<String, Object> out = all(now);

        Map<String, Object> pay = service(out, "14|ödeme");
        assertThat(pay).containsEntry("state", MAINTENANCE).containsEntry("maintenance", 2).containsEntry("down", 0);
        assertThat((String) pay.get("maintenance_until")).isEqualTo(ISO.format(now.minus(10, ChronoUnit.MINUTES).plus(60, ChronoUnit.MINUTES)));
        // Kart: düşük olan HTTP bakımda, ping ayakta → çalışıyor (bakımdaki kesinti sayılmaz)
        assertThat(service(out, "14|kart")).containsEntry("state", OPERATIONAL).containsEntry("maintenance", 1);

        Map<String, Object> maint = sub(out, "maintenance");
        List<Map<String, Object>> active = list(maint, "active"), upcoming = list(maint, "upcoming");
        assertThat(active).extracting(m -> m.get("name")).containsExactly("Kart bakım", "Ödeme bakım");   // erken biten önce
        assertThat(upcoming).extracting(m -> m.get("name")).containsExactly("Yarın DB");
        assertThat(maint).containsEntry("active_total", 2L).containsEntry("active_hidden", 0L)
                .containsEntry("upcoming_total", 1L).containsEntry("upcoming_hidden", 0L).doesNotContainKey("_windows");
        Map<String, Object> w1 = active.get(1);
        assertThat(w1).containsEntry("state", "active").containsEntry("all_monitors", false).containsEntry("monitor_count", 2)
                .containsEntry("team_name", "SY").containsEntry("services_total", 1).doesNotContainKeys("description", "targets_json", "targets");
        @SuppressWarnings("unchecked") List<Map<String, Object>> names = (List<Map<String, Object>>) w1.get("services");
        assertThat(names).singleElement().satisfies(n -> assertThat(n).containsEntry("name", "Ödeme").containsEntry("team_name", "SY"));
        assertThat(sub(out, "overall")).containsEntry("state", MAINTENANCE).containsEntry("active_maintenance", 2L).containsEntry("upcoming_maintenance", 1L);
    }

    @Test
    @DisplayName("Bakım: 'tüm izlemeler' penceresi açıkken her hizmet bakımda; kurum durumu bakım")
    void maintenanceAllMonitors() {
        Instant now = Instant.now();
        rows.add(row("http", 1, "web", "https://pay.example.com", 14L, "SY", "Ödeme", "down"));
        rows.add(row("scripted", 2, "akış", "akış", 99L, "Ödeme", "Mobil", "up"));
        when(maintenanceRepo.findByActiveTrue()).thenReturn(List.of(window(9, "Veri merkezi taşıma", now.minus(1, ChronoUnit.HOURS), 240, null, true)));
        Map<String, Object> out = all(now);
        assertThat(services(out)).allSatisfy(s -> assertThat(s).containsEntry("state", MAINTENANCE).extractingByKey("maintenance_until").isNotNull());
        assertThat(sub(out, "overall")).containsEntry("state", MAINTENANCE);
        Map<String, Object> w = list(sub(out, "maintenance"), "active").get(0);
        assertThat(w).containsEntry("all_monitors", true).containsEntry("monitor_count", null).containsEntry("services_total", 0);
    }

    @Test
    @DisplayName("Bakım görünürlüğü (Bakım listesinin kuralı): kapsamlı kullanıcı kendi takımının + 'tüm izlemeler' + takımsız penceresini görür; başka takımınki yalnız SAYI; bitiş saati yalnız görülen pencereden")
    void maintenanceScopedViewer() {
        Instant now = Instant.now();
        rows.add(row("http", 1, "web", "https://pay.example.com", 14L, "SY", "Ödeme", "up"));
        rows.add(row("ping", 2, "mobil", "10.9.9.9", 99L, "Ödeme", "Mobil", "up"));
        when(maintenanceRepo.findByActiveTrue()).thenReturn(List.of(
                window(1, "SY bakımı", now.minus(10, ChronoUnit.MINUTES), 60, "[{\"type\":\"http\",\"target\":\"https://pay.example.com\"}]", false, 14L),
                window(2, "Ödeme takımının gizli bakımı", now.minus(10, ChronoUnit.MINUTES), 90, "[{\"type\":\"ping\",\"target\":\"10.9.9.9\"}]", false, 99L),
                window(3, "Takımsız eski pencere", now.minus(5, ChronoUnit.MINUTES), 30, "[]", false, null),
                window(4, "Ödeme takımının yarınki bakımı", now.plus(1, ChronoUnit.DAYS), 60, "[]", false, 99L),
                window(5, "Herkesi etkileyen yarınki bakım", now.plus(2, ChronoUnit.DAYS), 60, null, true, 99L)));

        Map<String, Object> base = svc.computeBase(false, now);
        Map<String, Object> scoped = StatusPageService.project(base, SCOPED_14);
        Map<String, Object> m = sub(scoped, "maintenance");
        assertThat(list(m, "active")).extracting(x -> x.get("name")).containsExactlyInAnyOrder("SY bakımı", "Takımsız eski pencere");
        assertThat(list(m, "upcoming")).extracting(x -> x.get("name")).containsExactly("Herkesi etkileyen yarınki bakım");
        assertThat(m).containsEntry("active_total", 3L).containsEntry("active_hidden", 1L)
                .containsEntry("upcoming_total", 2L).containsEntry("upcoming_hidden", 1L);
        // Mobil hizmeti bakımda (toplu durum kalır) ama bitiş saati gizli pencereden → kapsamlı kullanıcıya null
        assertThat(service(scoped, "99|mobil")).containsEntry("state", MAINTENANCE).containsEntry("maintenance_until", null);
        assertThat(service(scoped, "14|ödeme")).containsEntry("state", MAINTENANCE).extractingByKey("maintenance_until").isNotNull();

        // Global görüntüleyici hepsini görür
        Map<String, Object> g = sub(StatusPageService.project(base, Viewer.all()), "maintenance");
        assertThat(list(g, "active")).hasSize(3);
        assertThat(g).containsEntry("active_hidden", 0L).containsEntry("upcoming_hidden", 0L);
        assertThat(service(StatusPageService.project(base, Viewer.all()), "99|mobil")).extractingByKey("maintenance_until").isNotNull();

        // maintenance.view izni yoksa (yüklem false) hiçbir pencere satırı yok, hepsi sayı
        Map<String, Object> noPerm = sub(StatusPageService.project(base, new Viewer(t -> true, (t, c) -> true, (t, a) -> false)), "maintenance");
        assertThat(list(noPerm, "active")).isEmpty();
        assertThat(list(noPerm, "upcoming")).isEmpty();
        assertThat(noPerm).containsEntry("active_hidden", 3L).containsEntry("upcoming_hidden", 2L);
    }

    // ── Olay kayıtları ───────────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Olaylar (global görüntüleyici): açık ve son 7 günde çözülen — yalnız özet alanlar; adres biçimli servis girdisi atılır; süre hesaplanır")
    void incidents() {
        when(incidentRepo.statusPageActive(any(Pageable.class))).thenReturn(List.<Object[]>of(
                new Object[]{1L, "Ödeme gecikmesi", "HIGH", "INVESTIGATING", "2026-09-30T10:00:00", "Ödeme API, https://pay.example.com/api, Mobil, Ödeme API", 14L, "SY", 14L}));
        when(incidentRepo.statusPageActiveCounts()).thenReturn(List.<Object[]>of(new Object[]{14L, 14L, 3L}));
        when(incidentRepo.statusPageResolvedSince(anyString(), any(Pageable.class))).thenReturn(List.<Object[]>of(
                new Object[]{2L, "DNS sorunu", "MEDIUM", "2026-09-29T08:00:00", "2026-09-29T09:30:00", null, "DNS", 14L, "SY", 14L},
                new Object[]{3L, "Kayıtlı süre", "LOW", "2026-09-28T08:00:00", "2026-09-28T08:10:00", 45, null, null, null, null}));
        when(incidentRepo.statusPageResolvedSinceCounts(anyString())).thenReturn(List.<Object[]>of(new Object[]{14L, 14L, 1L}, new Object[]{null, null, 1L}));

        Map<String, Object> out = all();
        Map<String, Object> inc = sub(out, "incidents");

        assertThat(list(inc, "active")).singleElement().satisfies(m -> {
            assertThat(m).containsEntry("title", "Ödeme gecikmesi").containsEntry("severity", "HIGH").containsEntry("status", "INVESTIGATING")
                    .containsEntry("started_at", "2026-09-30T10:00:00").containsEntry("team_name", "SY").doesNotContainKey("_created_by_team_id");
            assertThat(m.get("services")).isEqualTo(List.of("Ödeme API", "Mobil"));
        });
        assertThat(inc).containsEntry("active_total", 3L).containsEntry("active_visible", 3L).containsEntry("active_hidden", 0L)
                .containsEntry("resolved_total", 2L).containsEntry("resolved_hidden", 0L).containsEntry("days", 7);
        assertThat(list(inc, "resolved")).extracting(m -> m.get("duration_minutes")).containsExactly(90L, 45L);
        assertThat(list(inc, "resolved").get(1).get("services")).isEqualTo(List.of());
        assertThat(sub(out, "overall")).containsEntry("active_incidents", 3L);
        // Çözülen penceresi: şimdi − 7 gün (UTC ISO); tabandaki satır tavanı 200 (kapsamlı süzme için geniş dilim)
        verify(incidentRepo, atLeastOnce()).statusPageResolvedSince(argThat(s -> s.compareTo(ago(7 * 24 * 60 + 5)) > 0 && s.compareTo(ago(7 * 24 * 60 - 5)) < 0),
                argThat(p -> p.getPageSize() == INCIDENT_BASE_LIMIT));
    }

    @Test
    @DisplayName("Olay görünürlüğü (Olaylar ekranının okuma kuralı): kapsamlı kullanıcı yalnız kayıt takımı YA DA giren takımı kendisinin olan satırları görür; kalanı yalnız SAYI (gruplu sayımdan, satır tavanından bağımsız)")
    void incidentsScopedViewer() {
        when(incidentRepo.statusPageActive(any(Pageable.class))).thenReturn(List.<Object[]>of(
                activeInc(1, "SY olayı", "CRITICAL", 14L, "SY", 14L),
                activeInc(2, "SY'nin Ödeme adına girdiği olay", "HIGH", 99L, "Ödeme", 14L),
                activeInc(3, "Ödeme takımının GİZLİ olayı", "HIGH", 99L, "Ödeme", 99L),
                activeInc(4, "Takımsız GİZLİ olay", "LOW", null, null, null)));
        // Gruplu sayım: satırlar + tavanın dışında kalan 5 başka-takım olayı
        when(incidentRepo.statusPageActiveCounts()).thenReturn(List.<Object[]>of(
                new Object[]{14L, 14L, 1L}, new Object[]{99L, 14L, 1L}, new Object[]{99L, 99L, 6L}, new Object[]{null, null, 1L}));
        when(incidentRepo.statusPageResolvedSince(anyString(), any(Pageable.class))).thenReturn(List.<Object[]>of(
                new Object[]{7L, "Ödeme'nin GİZLİ çözülen olayı", "HIGH", "2026-09-29T08:00:00", "2026-09-29T09:00:00", 60, "Mobil", 99L, "Ödeme", 99L},
                new Object[]{8L, "SY çözülen", "LOW", "2026-09-29T08:00:00", "2026-09-29T08:30:00", 30, null, 14L, "SY", 14L}));
        when(incidentRepo.statusPageResolvedSinceCounts(anyString())).thenReturn(List.<Object[]>of(
                new Object[]{99L, 99L, 1L}, new Object[]{14L, 14L, 1L}));

        Map<String, Object> base = svc.computeBase(false, Instant.now());
        Map<String, Object> inc = sub(StatusPageService.project(base, SCOPED_14), "incidents");
        assertThat(list(inc, "active")).extracting(m -> m.get("title")).containsExactly("SY olayı", "SY'nin Ödeme adına girdiği olay");
        assertThat(inc).containsEntry("active_total", 9L).containsEntry("active_visible", 2L).containsEntry("active_hidden", 7L);
        assertThat(list(inc, "resolved")).extracting(m -> m.get("title")).containsExactly("SY çözülen");
        assertThat(inc).containsEntry("resolved_total", 2L).containsEntry("resolved_visible", 1L).containsEntry("resolved_hidden", 1L);

        // incidents.view izni yok → satır yok, toplamlar kalır
        Map<String, Object> noPerm = sub(StatusPageService.project(base, new Viewer(t -> true, (t, c) -> false, (t, a) -> true)), "incidents");
        assertThat(list(noPerm, "active")).isEmpty();
        assertThat(noPerm).containsEntry("active_total", 9L).containsEntry("active_hidden", 9L).containsEntry("resolved_hidden", 2L);

        // Global görüntüleyici: hepsi
        Map<String, Object> g = sub(StatusPageService.project(base, Viewer.all()), "incidents");
        assertThat(list(g, "active")).hasSize(4);
        assertThat(g).containsEntry("active_hidden", 0L).containsEntry("active_visible", 9L);
        // Kurum geneli sayı herkese aynı (satır değil)
        assertThat(sub(StatusPageService.project(base, SCOPED_14), "overall")).containsEntry("active_incidents", 9L);
    }

    @Test
    @DisplayName("Yardımcılar: servis CSV'si, süre (kayıtlı ya da hesaplanan), UTC ayrıştırma")
    void helpers() {
        assertThat(serviceNames(null)).isEmpty();
        assertThat(serviceNames(" a ,, b , a , http://x/y ")).containsExactly("a", "b");
        assertThat(durationMinutes(12, null, null)).isEqualTo(12L);
        assertThat(durationMinutes(-1, "2026-09-29T08:00:00", "2026-09-29T08:30:00")).isEqualTo(30L);
        assertThat(durationMinutes(null, "2026-09-29T08:00", "2026-09-29T07:00")).isNull();   // ters sıra
        assertThat(parseUtc("2026-09-29T08:00:00Z")).isEqualTo(Instant.parse("2026-09-29T08:00:00Z"));
        assertThat(parseUtc("2026-09-29T11:00:00+03:00")).isEqualTo(Instant.parse("2026-09-29T08:00:00Z"));
        assertThat(parseUtc("2026-09-29T08:00")).isEqualTo(Instant.parse("2026-09-29T08:00:00Z"));
        assertThat(parseUtc("çöp")).isNull();
    }

    // ── Görünürlük + sızıntı kapısı ──────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("İzdüşüm: izleme listesi (ad+tür+durum) yalnız görülebilen takımın hizmetinde; diğerlerinde monitors_visible=false ve liste YOK; taban değişmez")
    void projection_visibility() {
        rows.add(row("http", 1, "Ödeme web", "https://pay.example.com", 14L, "SY", "Ödeme", "up"));
        rows.add(row("scripted", 2, "Mobil akış", "Mobil akış", 99L, "Ödeme", "Mobil", "down"));
        rows.add(row("http", 3, "Takımsız", "https://nt.example.com", null, null, null, "up"));
        ReflectionTestUtils.setField(svc, "cacheMs", 30_000L);   // taban paylaşılsın: izdüşümün onu kirletmediği sınansın

        Map<String, Object> view = svc.view("T:14", SCOPED_14, false);

        Map<String, Object> mine = service(view, "14|ödeme");
        assertThat(mine).containsEntry("monitors_visible", true).doesNotContainKey("_maintenance_windows");
        @SuppressWarnings("unchecked") List<Map<String, Object>> monitors = (List<Map<String, Object>>) mine.get("monitors");
        assertThat(monitors).singleElement().satisfies(m -> assertThat(m).containsOnlyKeys("name", "type", "status")
                .containsEntry("name", "Ödeme web").containsEntry("type", "http").containsEntry("status", "up"));
        assertThat(service(view, "99|mobil")).containsEntry("monitors_visible", false).doesNotContainKey("monitors")
                .containsEntry("state", MAJOR_OUTAGE).containsEntry("down", 1);
        assertThat(service(view, "-|")).containsEntry("monitors_visible", false).doesNotContainKey("monitors");

        // Global görüntüleyici hepsini görür; taban (paylaşılan) harita izdüşümle kirlenmedi
        Map<String, Object> all = svc.view("ALL", Viewer.all(), false);
        assertThat(services(all)).allSatisfy(s -> assertThat(s).containsEntry("monitors_visible", true).containsKey("monitors"));
        assertThat(services(svc.base(false))).allSatisfy(s -> assertThat(s).doesNotContainKey("monitors_visible").containsKey("monitors"));
        verify(overview, times(1)).build(anyString(), any(), anyBoolean(), anyInt(), anyBoolean());   // tek taban hesabı
    }

    @Test
    @DisplayName("SIZINTI KAPISI: yanıtta URL/host/hedef, hata metni, bakım açıklaması/hedefleri, olay adres girdisi, iç alanlar; kapsamlı kullanıcıda ayrıca başka takımın olay başlığı ve bakım adı YOK")
    void payload_hasNoTargetsErrorsOrSecrets() throws Exception {
        Instant now = Instant.now();
        rows.add(row("http", 1, "Ödeme web", "https://pay.example.com/login?token=abc", 14L, "SY", "Ödeme", "down"));
        rows.add(row("port", 2, "DB", "db-prod-01.corp.local", 14L, "SY", "Ödeme", "up"));
        rows.add(row("ping", 3, "Kart", "10.20.30.40", 99L, "Ödeme", "Kart", "down"));
        when(maintenanceRepo.findByActiveTrue()).thenReturn(List.of(
                window(1, "Planlı bakım", now.minus(1, ChronoUnit.MINUTES), 60, "[{\"type\":\"ping\",\"target\":\"10.20.30.40\",\"name\":\"Kart\"}]", false, 14L),
                window(2, "Ödeme ekibinin GİZLİ bakım adı", now.minus(1, ChronoUnit.MINUTES), 60, "[{\"type\":\"ping\",\"target\":\"10.20.30.40\"}]", false, 99L)));
        when(incidentRepo.statusPageActive(any(Pageable.class))).thenReturn(List.<Object[]>of(
                new Object[]{1L, "Ödeme gecikmesi", "HIGH", "OPEN", "2026-09-30T10:00:00", "https://pay.example.com/api", 14L, "SY", 14L},
                new Object[]{2L, "Ödeme ekibinin GİZLİ olay başlığı", "CRITICAL", "OPEN", "2026-09-30T10:00:00", "Kart", 99L, "Ödeme", 99L}));
        when(incidentRepo.statusPageActiveCounts()).thenReturn(List.<Object[]>of(new Object[]{14L, 14L, 1L}, new Object[]{99L, 99L, 1L}));

        String[] neverAnywhere = {"https://", "pay.example.com", "db-prod-01", "10.20.30.40", "token=abc",
                "Connection refused", "\"target\"", "\"url\"", "\"host\"", "last_error", "\"error\"",
                "description", "db parolası", "targets_json", "open_acknowledged", "response_ms", "interval_seconds",
                "_created_by_team_id", "_windows", "_active_rows", "_active_counts", "_resolved_rows", "_maintenance_windows"};
        // Kapsam dışı (hiçbir takımı görmeyen), takım 14 kapsamlı ve global görüntüleyici
        Viewer nobody = new Viewer(t -> false, (t, c) -> false, (t, a) -> Boolean.TRUE.equals(a) || t == null);
        for (Map<String, Object> v : List.of(svc.view("T:5", nobody, false), svc.view("T:14", SCOPED_14, false), svc.view("ALL", Viewer.all(), false))) {
            assertThat(new ObjectMapper().writeValueAsString(v)).doesNotContain(neverAnywhere);
        }
        String scoped = new ObjectMapper().writeValueAsString(svc.view("T:14", SCOPED_14, false));
        assertThat(scoped).doesNotContain("Ödeme ekibinin GİZLİ olay başlığı", "Ödeme ekibinin GİZLİ bakım adı")
                .contains("Ödeme gecikmesi", "Planlı bakım", "\"active_hidden\":1");
        String outsider = new ObjectMapper().writeValueAsString(svc.view("T:5", nobody, false));
        assertThat(outsider).doesNotContain("Ödeme web", "\"DB\"", "Ödeme gecikmesi", "Planlı bakım", "GİZLİ");
        String global = new ObjectMapper().writeValueAsString(svc.view("ALL", Viewer.all(), false));
        assertThat(global).contains("Ödeme ekibinin GİZLİ olay başlığı", "Ödeme ekibinin GİZLİ bakım adı");
    }

    // ── Bellek ───────────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Bellek: taban kurum genelinde TEK hesap (görüntüleyiciler paylaşır); fresh 5 sn'den eski kaydı tazeler, daha yeniyi okur; cacheMs=0 kapalı")
    void memo_andFresh() {
        long[] clock = {1_000_000L};
        ReflectionTestUtils.setField(svc, "cacheMs", 30_000L);
        ReflectionTestUtils.setField(svc, "baseMemo", new TtlMemo<Map<String, Object>>(4, () -> clock[0]));
        ReflectionTestUtils.setField(svc, "viewMemo", new TtlMemo<Map<String, Object>>(500, () -> clock[0]));
        rows.add(row("http", 1, "web", "https://pay.example.com", 14L, "SY", "Ödeme", "up"));

        Map<String, Object> a = svc.view("ALL", Viewer.all(), false);
        assertThat(svc.view("ALL", Viewer.all(), false)).isSameAs(a);
        svc.view("T:14", SCOPED_14, false);                                      // başka görüntüleyici: izdüşüm yeni, taban ortak
        verify(overview, times(1)).build(anyString(), any(), anyBoolean(), anyInt(), anyBoolean());
        verify(incidentRepo, times(1)).statusPageActive(any(Pageable.class));
        verify(incidentRepo, times(1)).statusPageActiveCounts();

        clock[0] += 3_000;                                                       // 3 sn: fresh sel koruması — hesap yok
        assertThat(svc.view("ALL", Viewer.all(), true)).isSameAs(a);
        verify(overview, never()).build(anyString(), any(), anyBoolean(), anyInt(), eq(true));

        clock[0] += 3_000;                                                       // 6 sn: fresh tazeler (pano da fresh)
        assertThat(svc.view("ALL", Viewer.all(), true)).isNotSameAs(a);
        verify(overview, times(1)).build(eq("ALL"), any(), eq(true), eq(24), eq(true));

        clock[0] += 31_000;                                                      // TTL aşıldı → düz yoklama yeniden hesaplar
        svc.view("T:14", SCOPED_14, false);
        verify(overview, times(2)).build(eq("ALL"), any(), eq(true), eq(24), eq(false));

        ReflectionTestUtils.setField(svc, "cacheMs", 0L);                       // kapalı: her çağrı hesaplar
        svc.view("ALL", Viewer.all(), false);
        svc.view("ALL", Viewer.all(), false);
        verify(overview, times(4)).build(eq("ALL"), any(), eq(true), eq(24), eq(false));
    }

    @Test
    @DisplayName("Pano verisi yoksa (izleme yok) kurum durumu no_data, listeler boş — çökmez")
    void emptyOrg() {
        Map<String, Object> out = svc.view("ALL", Viewer.all(), false);
        assertThat(services(out)).isEmpty();
        assertThat(sub(out, "overall")).containsEntry("state", NO_DATA).containsEntry("services_total", 0L);
        assertThat(sub(out, "incidents")).containsEntry("active_total", 0L).containsEntry("active_hidden", 0L);
        assertThat(sub(out, "maintenance")).containsEntry("active_total", 0L).containsEntry("upcoming_hidden", 0L);
    }
}
