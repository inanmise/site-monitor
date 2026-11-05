package com.sitemonitor.service.activity;

import com.sitemonitor.model.ActivityLog;
import com.sitemonitor.model.AlertEvent;
import com.sitemonitor.model.CertificateCheck;
import com.sitemonitor.model.MonitorChangeLog;
import com.sitemonitor.model.PortCheck;
import com.sitemonitor.model.Team;
import com.sitemonitor.model.UptimeCheck;
import com.sitemonitor.repository.ActivityLogRepository;
import com.sitemonitor.repository.AlertEventRepository;
import com.sitemonitor.repository.CertificateInventoryRepository;
import com.sitemonitor.repository.TeamRepository;
import com.sitemonitor.service.CertificateService;
import com.sitemonitor.service.PermissionService;
import com.sitemonitor.service.ScriptedCheckerService;
import com.sitemonitor.service.noc.NocCallLogService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.TestPropertySource;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * {@link ActivityDetailService} gerçek varlıklarla (H2): kontrol satırının TAM (check_id) / YAKLAŞIK (± 120 sn) eşlemesi,
 * aynı izlemenin önceki / sonraki kontrolü, ± 24 sa seri (aktivite satırlarından; kova), alarm TAM / YAKLAŞIK + açma
 * kapısı, değişiklik kaydı TAM / YAKLAŞIK, derin bağlantılar, izin yoksa kontrol gizli, sertifika / uptime alan adı anahtarı.
 */
@DataJpaTest
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
class ActivityDetailServiceH2Test {

    @Autowired ActivityLogRepository activityRepo;
    @Autowired AlertEventRepository alertRepo;
    @Autowired CertificateInventoryRepository inventoryRepo;
    @Autowired TeamRepository teamRepo;
    @Autowired EntityManager em;
    @Autowired javax.sql.DataSource dataSource;

    PermissionService perms;
    CertificateService certService;
    ActivityDetailService svc;

    static final String T = "2026-11-05T10:00:00";

    static String at(int deltaSeconds) {
        return ActivityDetailService.ISO.format(LocalDateTime.parse(T).toInstant(ZoneOffset.UTC).plusSeconds(deltaSeconds));
    }

    @SuppressWarnings("unchecked")
    private static <X> ObjectProvider<X> provider(X value) {
        ObjectProvider<X> p = mock(ObjectProvider.class);
        when(p.getIfAvailable()).thenReturn(value);
        return p;
    }

    @BeforeEach
    void setUp() {
        perms = mock(PermissionService.class);
        when(perms.allows(any(jakarta.servlet.http.HttpSession.class), anyString(), anyString())).thenReturn(true);
        certService = mock(CertificateService.class);
        svc = new ActivityDetailService(activityRepo, alertRepo, inventoryRepo, teamRepo, perms,
                provider(certService), provider((ScriptedCheckerService) null), provider((NocCallLogService) null),
                provider((tools.jackson.databind.ObjectMapper) null));
        svc.setEntityManager(em);
        // Serinin üst sınırı "şimdi": testler T + 24 sa'yi görsün (gerçek saat T'ye yakın olabilir)
        svc.setClock(java.time.Clock.fixed(Instant.parse("2030-01-01T00:00:00Z"), ZoneOffset.UTC));
    }

    private static MockHttpSession admin() {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("authenticated", Boolean.TRUE);
        s.setAttribute("username", "admin");
        s.setAttribute("systemRole", "ADMIN");
        return s;
    }

    private static MockHttpSession user(Long... teams) {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("authenticated", Boolean.TRUE);
        s.setAttribute("username", "u");
        s.setAttribute("systemRole", "USER");
        s.setAttribute("viewTeamIds", new ArrayList<>(List.of(teams)));
        return s;
    }

    private PortCheck port(long monitorId, String checkedAt, boolean open, String reason) {
        PortCheck c = new PortCheck();
        c.setMonitorId(monitorId);
        c.setCheckedAt(checkedAt);
        c.setOpen(open);
        c.setResponseMs(12L);
        c.setFailureReason(reason);
        em.persist(c);
        return c;
    }

    private ActivityLog act(String type, Long monitorId, String target, String time, String action, String status) {
        ActivityLog a = new ActivityLog();
        a.setActivityTime(time);
        a.setMonitorType(type);
        a.setMonitorId(monitorId);
        a.setMonitorName("Ödeme portu");
        a.setTarget(target);
        a.setAction(action);
        a.setResultStatus(status);
        a.setResponseMs(10L);
        a.setTeamId(5L);
        a.setActor("scheduler");
        return activityRepo.save(a);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> m(Object o) {
        return (Map<String, Object>) o;
    }

    @Test
    @DisplayName("check_id → TAM eşleşme + aynı izlemenin önceki / sonraki kontrolü (türün geçmiş satır biçimi)")
    void exactCheck_withNeighbours() {
        PortCheck before = port(7, at(-60), true, null);
        PortCheck self = port(7, at(0), false, "CONNECT_REFUSED");
        PortCheck after = port(7, at(60), true, null);
        port(8, at(0), true, null);   // başka izleme — karışmaz
        ActivityLog a = act("PORT", 7L, "odeme.local:8443", T, "SCHEDULED_CHECK", "ERROR");
        a.setCheckId(self.getId());
        a.setFailureReason("CONNECT_REFUSED");
        a.setFailureDetail("{\"phase\":\"CONNECT\",\"target\":\"odeme.local:8443\"}");
        activityRepo.save(a);

        Map<String, Object> d = svc.detail(a, admin());

        Map<String, Object> check = m(d.get("check"));
        assertThat(check).containsEntry("type", "port").containsEntry("match", "exact");
        assertThat(m(check.get("row"))).containsEntry("id", self.getId()).containsEntry("failure_reason", "CONNECT_REFUSED")
                .containsEntry("open", false).containsKey("checked_at");
        assertThat(m(d.get("previous_check"))).containsEntry("id", before.getId());
        assertThat(m(d.get("next_check"))).containsEntry("id", after.getId());
        // komşu kontrolün aktivite satırı yok → activity_id null (anahtar yine var)
        assertThat(m(d.get("previous_check"))).containsEntry("activity_id", null);
        Map<String, Object> data = m(d.get("data"));
        assertThat(data).containsEntry("check_id", self.getId()).containsEntry("failure_reason", "CONNECT_REFUSED");
        assertThat(m(data.get("failure_detail"))).containsEntry("phase", "CONNECT");
    }

    @Test
    @DisplayName("Önceki / sonraki kontrolün activity_id'si: check_id eşleşen satır; yoksa bağsız (eski) satırlardan en yakını; "
            + "başka kontrole bağlı satır seçilmez")
    void neighbourActivityIds() {
        PortCheck before = port(7, at(-60), true, null);
        PortCheck self = port(7, at(0), false, null);
        PortCheck after = port(7, at(60), true, null);
        ActivityLog beforeRow = act("PORT", 7L, "h:1", at(-59), "SCHEDULED_CHECK", "SUCCESS");
        beforeRow.setCheckId(before.getId());
        activityRepo.save(beforeRow);
        ActivityLog otherCheck = act("PORT", 7L, "h:1", at(60), "MANUAL_CHECK", "SUCCESS");
        otherCheck.setCheckId(999_999L);   // başka kontrolün satırı — zamanca en yakın olsa da seçilmez
        activityRepo.save(otherCheck);
        ActivityLog legacyAfter = act("PORT", 7L, "h:1", at(62), "SCHEDULED_CHECK", "SUCCESS");
        ActivityLog a = act("PORT", 7L, "h:1", T, "SCHEDULED_CHECK", "ERROR");
        a.setCheckId(self.getId());

        Map<String, Object> d = svc.detail(a, admin());
        assertThat(m(d.get("previous_check"))).containsEntry("id", before.getId()).containsEntry("activity_id", beforeRow.getId());
        assertThat(m(d.get("next_check"))).containsEntry("id", after.getId()).containsEntry("activity_id", legacyAfter.getId());
    }

    @Test
    @DisplayName("Sorgu izlemesi kontrol satırı: geçmiş ucunun biçimi + error_message eş adı")
    void dbqueryRowAlias() {
        com.sitemonitor.model.DbQueryCheck c = new com.sitemonitor.model.DbQueryCheck();
        c.setMonitorId(12L);
        c.setCheckedAt(T);
        c.setOk(false);
        c.setStatus("FAIL");
        c.setErrorMessage("ORA-00942");
        em.persist(c);
        ActivityLog a = act("DBQUERY", 12L, "conn", T, "SCHEDULED_CHECK", "ERROR");
        a.setCheckId(c.getId());
        Map<String, Object> row = m(m(svc.detail(a, admin()).get("check")).get("row"));
        assertThat(row).containsEntry("status", "FAIL").containsEntry("error", "ORA-00942")
                .containsEntry("error_message", "ORA-00942").containsKey("breach_level").containsKey("rule_results");
    }

    @Test
    @DisplayName("check_id yok (eski satır) → ± 120 sn içindeki EN YAKIN kontrol YAKLAŞIK; pencere dışı → null")
    void approximateCheck() {
        port(7, at(-100), true, null);
        PortCheck near = port(7, at(15), false, null);
        ActivityLog a = act("PORT", 7L, "odeme.local:8443", T, "SCHEDULED_CHECK", "ERROR");
        Map<String, Object> d = svc.detail(a, admin());
        assertThat(m(d.get("check"))).containsEntry("match", "approximate");
        assertThat(m(m(d.get("check")).get("row"))).containsEntry("id", near.getId());

        ActivityLog far = act("PORT", 9L, "x:1", T, "SCHEDULED_CHECK", "SUCCESS");
        port(9, at(500), true, null);
        assertThat(svc.detail(far, admin()).get("check")).isNull();
    }

    @Test
    @DisplayName("monitoring.read yoksa kontrol satırları GİZLİ (check_hidden); yaşam döngüsü satırında kontrol aranmaz")
    void checkHiddenWithoutPermission_andLifecycleHasNoCheck() {
        PortCheck self = port(7, at(0), true, null);
        ActivityLog a = act("PORT", 7L, "odeme.local:8443", T, "SCHEDULED_CHECK", "SUCCESS");
        a.setCheckId(self.getId());
        when(perms.allows(any(jakarta.servlet.http.HttpSession.class), eq("monitoring.read"), eq("view"))).thenReturn(false);
        Map<String, Object> d = svc.detail(a, user(5L));
        assertThat(d).containsEntry("check_hidden", true);
        assertThat(d.get("check")).isNull();

        ActivityLog created = act("PORT", 7L, "odeme.local:8443", T, "CREATED", "SUCCESS");
        Map<String, Object> d2 = svc.detail(created, admin());
        assertThat(d2.get("check")).isNull();
        assertThat(d2).doesNotContainKey("check_hidden");
    }

    @Test
    @DisplayName("Seri: ± 24 sa, aynı izlemenin YALNIZ kontrol satırları, zaman sıralı; pencere dışı / başka izleme / yaşam döngüsü yok")
    void seriesFromActivityRows() {
        act("PORT", 7L, "h:1", at(-3600), "SCHEDULED_CHECK", "SUCCESS");
        act("PORT", 7L, "h:1", at(-60), "MANUAL_CHECK", "ERROR");
        ActivityLog self = act("PORT", 7L, "h:1", T, "SCHEDULED_CHECK", "SUCCESS");
        act("PORT", 7L, "h:1", at(120), "CONFIG_CHANGED", "SUCCESS");
        act("PORT", 7L, "h:1", at(-25 * 3600), "SCHEDULED_CHECK", "SUCCESS");
        act("PORT", 8L, "h:2", at(10), "SCHEDULED_CHECK", "SUCCESS");

        Map<String, Object> series = m(svc.detail(self, admin()).get("series"));
        List<Map<String, Object>> points = (List<Map<String, Object>>) series.get("points");
        assertThat(points).extracting(p -> p.get("status")).containsExactly("SUCCESS", "ERROR", "SUCCESS");
        assertThat(series).containsEntry("truncated", false).containsEntry("from", at(-24 * 3600));
    }

    @Test
    @DisplayName("Seri kovası: 300'ü aşan satır eşit zaman kovalarına iner; ortalama ms, EN KÖTÜ durum; boş kova null")
    void seriesBuckets() {
        Instant from = LocalDateTime.parse(T).toInstant(ZoneOffset.UTC);
        Instant to = from.plusSeconds(3000);
        List<Object[]> rows = new ArrayList<>();
        for (int i = 0; i < 400; i++) {
            rows.add(new Object[]{ActivityDetailService.ISO.format(from.plusSeconds(i)), (long) i, i == 3 ? "ERROR" : "SUCCESS"});
        }
        List<Map<String, Object>> pts = ActivityDetailService.points(rows, from, to);
        assertThat(pts).hasSize(ActivityDetailService.SERIES_MAX_POINTS);
        // kova genişliği 10 sn → ilk kova 0..9 sn: ortalama 4.5 → 5, içinde ERROR var
        assertThat(pts.get(0)).containsEntry("ms", 5L).containsEntry("status", "ERROR");
        assertThat(pts.get(100)).containsEntry("ms", null).containsEntry("status", null);   // 1000. sn sonrası satır yok
    }

    @Test
    @DisplayName("Alarm: alert_event_id TAM; hata kontrolünde aynı izlemenin kapsayan alarmı YAKLAŞIK; açma kapısı kapsamla")
    void alarmExactAndApproximate() {
        AlertEvent ev = new AlertEvent();
        ev.setDomain("odeme.local");
        ev.setAlertType("PORT_DOWN");
        ev.setAlertLevel("HIGH");
        ev.setTeamId(5L);
        ev.setCreatedAt(at(-300));
        ev.setResolved(true);
        ev.setResolvedAt(at(600));
        ev.setResolvedBy("system");
        ev = alertRepo.save(ev);

        ActivityLog trig = act("PORT", 7L, "odeme.local:8443", at(-300), "ALERT_TRIGGERED", "ERROR");
        trig.setAlertEventId(ev.getId());
        Map<String, Object> exact = m(svc.detail(trig, admin()).get("alarm"));
        assertThat(exact).containsEntry("id", ev.getId()).containsEntry("match", "exact").containsEntry("resolved_by_kind", "auto")
                .containsEntry("can_open", true).containsEntry("level", "HIGH");

        ActivityLog check = act("PORT", 7L, "odeme.local:8443", T, "SCHEDULED_CHECK", "ERROR");
        Map<String, Object> d = svc.detail(check, user(5L));
        assertThat(m(d.get("alarm"))).containsEntry("id", ev.getId()).containsEntry("match", "approximate")
                .containsEntry("can_open", true);
        assertThat(m(m(d.get("links")).get("alert"))).containsEntry("tab", "alerthistory");

        // başka takımın kullanıcısı: YAKLAŞIK eşlenen ve açamayacağı alarmın meta verisi dönmez (inceleme D2), bağlantı yok
        Map<String, Object> other = svc.detail(check, user(99L));
        assertThat(other.get("alarm")).isNull();
        assertThat(m(other.get("links")).get("alert")).isNull();
        // TAM eşleşme (satırın kendi alarmı) açamasa da meta veriyi taşır
        assertThat(m(svc.detail(trig, user(99L)).get("alarm"))).containsEntry("can_open", false);

        // başarılı kontrol yaklaşık alarm aramaz
        assertThat(svc.detail(act("PORT", 7L, "odeme.local:8443", T, "SCHEDULED_CHECK", "SUCCESS"), admin()).get("alarm")).isNull();
    }

    @Test
    @DisplayName("Değişiklik: change_seq TAM; eski satırda aynı kaynağın ± 120 sn içindeki aynı olay türü YAKLAŞIK; bağlantılar")
    void changeExactAndApproximate() {
        MonitorChangeLog c = new MonitorChangeLog();
        c.setResourceKind("PORT");
        c.setResourceId(7L);
        c.setResourceName("Ödeme portu");
        c.setSeq(0);
        c.setEventType("CREATE");
        c.setTeamId(5L);
        c.setCreatedAt(at(2));
        em.persist(c);

        ActivityLog created = act("PORT", 7L, "odeme.local:8443", T, "CREATED", "SUCCESS");
        Map<String, Object> d = svc.detail(created, admin());
        assertThat(m(d.get("change"))).containsEntry("kind", "port").containsEntry("id", 7L).containsEntry("seq", 0)
                .containsEntry("match", "approximate");
        Map<String, Object> links = m(d.get("links"));
        assertThat(m(links.get("changes"))).containsEntry("tab", "port");
        assertThat(m(m(links.get("changes")).get("params"))).containsEntry("monitor", 7L).containsEntry("mtab", "changes");
        assertThat(m(m(links.get("history")).get("params"))).containsEntry("mtab", "control");

        ActivityLog cfg = act("PORT", 7L, "odeme.local:8443", T, "CONFIG_CHANGED", "SUCCESS");
        cfg.setChangeSeq(4);
        assertThat(m(svc.detail(cfg, admin()).get("change"))).containsEntry("seq", 4).containsEntry("match", "exact");

        // DELETE olayı CREATE kaydıyla eşleşmez
        assertThat(svc.detail(act("PORT", 7L, "odeme.local:8443", T, "DELETED", "SUCCESS"), admin()).get("change")).isNull();
    }

    @Test
    @DisplayName("Sertifika (alan adı anahtarı) + uptime (alan adı:port) kontrol eşlemesi; sertifika satırı geçmiş eşleyicisinden")
    void certAndUptimeKeys() {
        CertificateCheck cc = new CertificateCheck();
        cc.setDomain("x.example.com");
        cc.setCheckedAt(at(5));
        cc.setStatus("error");
        cc.setError("Connection refused");
        cc.setErrorClass("NETWORK");
        em.persist(cc);
        com.sitemonitor.dto.CertificateDto dto = new com.sitemonitor.dto.CertificateDto();
        dto.setDomain("x.example.com");
        when(certService.historyRow(any(CertificateCheck.class))).thenReturn(dto);
        ActivityLog cert = act("CERT", 3L, "x.example.com", T, "MANUAL_CHECK", "ERROR");
        Map<String, Object> d = svc.detail(cert, admin());
        assertThat(m(d.get("check"))).containsEntry("type", "cert").containsEntry("match", "approximate");
        assertThat(m(m(d.get("check")).get("row"))).containsEntry("domain", "x.example.com");
        assertThat(m(m(d.get("links")).get("monitor"))).containsEntry("tab", "dashboard");

        UptimeCheck uc = new UptimeCheck();
        uc.setDomain("x.example.com");
        uc.setPort(8443);
        uc.setStatus("down");
        uc.setCheckedAt(T);
        em.persist(uc);
        UptimeCheck other = new UptimeCheck();
        other.setDomain("x.example.com");
        other.setPort(443);
        other.setStatus("up");
        other.setCheckedAt(T);
        em.persist(other);
        ActivityLog up = act("UPTIME", 3L, "x.example.com:8443", T, "SCHEDULED_CHECK", "ERROR");
        up.setCheckId(uc.getId());
        Map<String, Object> du = svc.detail(up, admin());
        assertThat(m(m(du.get("check")).get("row"))).containsEntry("port", 8443).containsEntry("status", "down");
        assertThat(m(m(du.get("links")).get("monitor"))).containsEntry("tab", "uptime");
    }

    @Test
    @DisplayName("Takım adı + aktör / istek kimliği; tanılama kapısı: izin + takım; dbquery'de tanılama yok")
    void teamActorAndDiagnose() {
        Team t = new Team();
        t.setName("Ödeme Ekibi");
        t = teamRepo.save(t);
        ActivityLog a = act("PORT", 7L, "odeme.local:8443", T, "MANUAL_CHECK", "SUCCESS");
        a.setTeamId(t.getId());
        a.setActor("ayse");
        a.setRequestId("rq-77");
        Map<String, Object> d = svc.detail(a, admin());
        assertThat(m(d.get("data"))).containsEntry("team_name", "Ödeme Ekibi").containsEntry("request_id", "rq-77");
        assertThat(m(d.get("actor"))).containsEntry("name", "ayse").containsEntry("request_id", "rq-77");
        assertThat(m(d.get("links"))).containsEntry("can_diagnose", true);

        assertThat(m(svc.detail(a, user(999L)).get("links"))).containsEntry("can_diagnose", false);
        ActivityLog dbq = act("DBQUERY", 7L, "conn", T, "SCHEDULED_CHECK", "SUCCESS");
        assertThat(m(svc.detail(dbq, admin()).get("links"))).containsEntry("can_diagnose", false);
    }

    @Test
    @DisplayName("Yardımcılar: alarm anahtarı türe göre; failure_detail düz metin {text}; bilinmeyen tür bağlantısız")
    void helpers() {
        ActivityLog p = new ActivityLog();
        p.setMonitorType("DNS");
        p.setTarget("x.com A");
        assertThat(ActivityDetailService.alarmKey(p)).isEqualTo("x.com");
        p.setMonitorType("SCRIPTED");
        p.setMonitorName("Giriş akışı");
        assertThat(ActivityDetailService.alarmKey(p)).isEqualTo("Giriş akışı");
        assertThat(ActivityDetailService.alertTypesOf("HTTP")).doesNotContain("ACCESSIBILITY").contains("HTTP_DOWN");
        assertThat(ActivityDetailService.alertTypesOf("UPTIME")).containsExactly("ACCESSIBILITY");
        assertThat(ActivityDetailService.failureDetailObject("Anahtar kelime yok")).containsEntry("text", "Anahtar kelime yok");
        assertThat(ActivityDetailService.failureDetailObject("{\"phase\":\"X\"}")).containsEntry("phase", "X");
        assertThat(ActivityDetailService.failureDetailObject(" ")).isNull();
        p.setMonitorType("NOPE");
        assertThat(ActivityDetailService.monitorLink(p, "NOPE", null)).isNull();
        assertThat(ActivityDetailService.hostOf("https://a.example.com:8443/x")).isEqualTo("a.example.com");
    }

    // ── İnceleme düzeltmeleri (2026-11-05) ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Y1: önceki / sonraki ± 7 gün penceresinde; pencere dışı → null; aynı saniyedeki satırlar kimlikle sıralanır")
    void neighbourWindowAndSameSecond() {
        port(21, at(-8 * 86_400), true, null);                 // pencere dışı (önceki)
        PortCheck sixDays = port(21, at(-6 * 86_400), true, null);
        PortCheck selfA = port(21, at(0), false, null);
        port(21, at(8 * 86_400), true, null);                  // pencere dışı (sonraki)
        ActivityLog a = act("PORT", 21L, "h:1", T, "SCHEDULED_CHECK", "ERROR");
        a.setCheckId(selfA.getId());
        Map<String, Object> d = svc.detail(a, admin());
        assertThat(m(d.get("previous_check"))).containsEntry("id", sixDays.getId());
        assertThat(d.get("next_check")).as("8 gün sonraki kontrol pencere dışı").isNull();

        // Aynı saniye: önceki = aynı saniyede daha küçük kimlik, sonraki = aynı saniyede daha büyük kimlik
        PortCheck tieBefore = port(22, at(0), true, null);
        PortCheck self = port(22, at(0), false, null);
        PortCheck tieAfter = port(22, at(0), true, null);
        ActivityLog b = act("PORT", 22L, "h:2", T, "SCHEDULED_CHECK", "ERROR");
        b.setCheckId(self.getId());
        Map<String, Object> db = svc.detail(b, admin());
        assertThat(m(db.get("previous_check"))).containsEntry("id", tieBefore.getId());
        assertThat(m(db.get("next_check"))).containsEntry("id", tieAfter.getId());
    }

    @Test
    @DisplayName("Y1: okunan adayların hepsi çapayla aynı saniyede ve öbür yandaysa katı arama sonrakini bulur")
    void neighbourStrictFallback() {
        for (int i = 0; i < ActivityDetailService.NEIGHBOUR_FETCH + 4; i++) port(23, at(0), true, null);
        PortCheck self = port(23, at(0), false, null);
        PortCheck later = port(23, at(60), true, null);
        ActivityLog a = act("PORT", 23L, "h:3", T, "SCHEDULED_CHECK", "ERROR");
        a.setCheckId(self.getId());
        Map<String, Object> d = svc.detail(a, admin());
        assertThat(m(d.get("next_check"))).containsEntry("id", later.getId());
        assertThat(m(d.get("previous_check"))).containsEntry("checked_at", T);   // aynı saniyedeki daha eski bir kontrol
    }

    @Test
    @DisplayName("O1: izleme görülemeyen takıma taşındı → kontrol / komşular gizli (check_hidden, team), satır ayrıntısı gizli, "
            + "seri yalnız görülebilen satırlar, izleme bağlantısı yok; yönetici hepsini görür; silinmiş izleme satırın kapısıyla")
    void monitorMovedToHiddenTeam() {
        svc.setTimelines(new ActivityTimelineService(new org.springframework.jdbc.core.JdbcTemplate(dataSource)));
        com.sitemonitor.model.PortMonitor pm = new com.sitemonitor.model.PortMonitor();
        pm.setName("Taşınan port");
        pm.setHost("moved.local");
        pm.setPort(9443);
        pm.setTeamId(9L);
        pm.setStandalone(true);
        em.persist(pm);
        PortCheck self = port(pm.getId(), at(0), false, "CONNECT_REFUSED");
        port(pm.getId(), at(60), false, "CONNECT_REFUSED");     // taşımadan sonra B'nin kontrolü
        act("PORT", pm.getId(), "moved.local:9443", at(-60), "SCHEDULED_CHECK", "SUCCESS");   // takım 5 (A) dönemi
        ActivityLog bRow = act("PORT", pm.getId(), "moved.local:9443", at(60), "SCHEDULED_CHECK", "ERROR");
        bRow.setTeamId(9L);
        activityRepo.save(bRow);
        ActivityLog a = act("PORT", pm.getId(), "moved.local:9443", T, "SCHEDULED_CHECK", "ERROR");
        a.setCheckId(self.getId());
        a.setFailureDetail("{\"phase\":\"CONNECT\",\"resolved\":[\"10.0.0.9\"]}");
        activityRepo.save(a);

        Map<String, Object> d = svc.detail(a, user(5L));
        assertThat(d).containsEntry("check_hidden", true).containsEntry("check_hidden_reason", "team");
        assertThat(d.get("check")).isNull();
        assertThat(d.get("previous_check")).isNull();
        assertThat(d.get("next_check")).isNull();
        assertThat(m(d.get("data")).get("failure_detail")).isNull();
        List<Map<String, Object>> points = (List<Map<String, Object>>) m(d.get("series")).get("points");
        assertThat(points).extracting(p -> p.get("status")).containsExactly("SUCCESS", "ERROR");   // B'nin satırı yok
        assertThat(m(d.get("links")).get("monitor")).isNull();
        assertThat(m(d.get("links"))).containsEntry("can_diagnose", false);

        Map<String, Object> ad = svc.detail(a, admin());
        assertThat(ad).doesNotContainKey("check_hidden");
        assertThat(m(ad.get("check"))).containsEntry("match", "exact");
        assertThat(m(ad.get("next_check"))).isNotNull();
        assertThat((List<?>) m(ad.get("series")).get("points")).hasSize(3);

        // Silinmiş izleme (tabloda yok): satırın kapısı geçerli — kontrol görünür
        PortCheck gone = port(77_777L, at(0), false, null);
        ActivityLog g = act("PORT", 77_777L, "gone.local:1", T, "SCHEDULED_CHECK", "ERROR");
        g.setCheckId(gone.getId());
        assertThat(m(svc.detail(g, user(5L)).get("check"))).containsEntry("match", "exact");
    }

    @Test
    @DisplayName("D3: monitoring.read yoksa satırın kendi failure_detail'i de gizlenir")
    void checkHiddenAlsoHidesRowFailureDetail() {
        ActivityLog a = act("PORT", 7L, "odeme.local:8443", T, "SCHEDULED_CHECK", "ERROR");
        a.setFailureDetail("{\"phase\":\"CONNECT\"}");
        a.setFailureReason("CONNECT_REFUSED");
        when(perms.allows(any(jakarta.servlet.http.HttpSession.class), eq("monitoring.read"), eq("view"))).thenReturn(false);
        Map<String, Object> d = svc.detail(a, user(5L));
        assertThat(d).containsEntry("check_hidden_reason", "permission");
        assertThat(m(d.get("data"))).containsEntry("failure_detail", null).containsEntry("failure_reason", "CONNECT_REFUSED");
    }

    @Test
    @DisplayName("D4: alerts.read yoksa alarm meta verisi dönmez (alarm null + alarm_hidden), alarm bağlantısı yok")
    void alarmHiddenWithoutAlertsRead() {
        AlertEvent ev = new AlertEvent();
        ev.setDomain("odeme.local");
        ev.setAlertType("PORT_DOWN");
        ev.setAlertLevel("HIGH");
        ev.setTeamId(5L);
        ev.setCreatedAt(at(-300));
        ev.setResolved(false);
        ev = alertRepo.save(ev);
        ActivityLog trig = act("PORT", 7L, "odeme.local:8443", at(-300), "ALERT_TRIGGERED", "ERROR");
        trig.setAlertEventId(ev.getId());
        when(perms.allows(any(jakarta.servlet.http.HttpSession.class), eq("alerts.read"), eq("view"))).thenReturn(false);
        Map<String, Object> d = svc.detail(trig, user(5L));
        assertThat(d.get("alarm")).isNull();
        assertThat(d).containsEntry("alarm_hidden", true).doesNotContainKey("alarm_error");
        assertThat(m(d.get("links")).get("alert")).isNull();
    }

    @Test
    @DisplayName("D9: seri tavanı çapanın İKİ YANINI ayrı sınırlar — 6 100 eski satır olay anını ve sonrasını silmez")
    void seriesKeepsBothSidesOfTheAnchor() {
        org.springframework.jdbc.core.JdbcTemplate jdbc = new org.springframework.jdbc.core.JdbcTemplate(dataSource);
        Instant t0 = LocalDateTime.parse(T).toInstant(ZoneOffset.UTC);
        List<Object[]> batch = new ArrayList<>();
        for (int i = 6100; i >= 1; i--) {
            batch.add(new Object[]{ActivityDetailService.ISO.format(t0.minusSeconds(i * 14L)), "SUCCESS"});
        }
        for (int i = 1; i <= 5; i++) batch.add(new Object[]{ActivityDetailService.ISO.format(t0.plusSeconds(i * 10L)), "ERROR"});
        jdbc.batchUpdate("INSERT INTO activity_log (activity_time, monitor_type, monitor_id, target, action, result_status,"
                + " team_id, response_ms, monitor_name, actor) VALUES (?, 'PORT', 31, 'h:31', 'SCHEDULED_CHECK', ?, 5, 10, 'm',"
                + " 'scheduler')", batch);
        ActivityLog self = act("PORT", 31L, "h:31", T, "SCHEDULED_CHECK", "SUCCESS");

        Map<String, Object> series = m(svc.detail(self, admin()).get("series"));
        assertThat(series).containsEntry("truncated", true);
        List<Map<String, Object>> pts = (List<Map<String, Object>>) series.get("points");
        assertThat(pts).hasSize(ActivityDetailService.SERIES_MAX_POINTS);
        // ± 24 sa / 300 kova = 576 sn; çapa + sonraki 50 sn kova 150'de — sonraki satırlar ERROR
        assertThat(pts.get(150)).containsEntry("status", "ERROR");
        assertThat(pts.get(149).get("ms")).as("çapadan hemen önce").isNotNull();
        // en eski satırlar (çapa − 85 400 sn …) tavan dışında: ilk kovalar boş
        assertThat(pts.get(1).get("ms")).isNull();
    }

    @Test
    @DisplayName("Sözleşme D6: seri üst sınırı şimdiyi aşmaz (yeni aktivitede sağ yarı geleceğe uzanmaz)")
    void seriesNeverReachesTheFuture() {
        svc.setClock(java.time.Clock.fixed(LocalDateTime.parse(T).toInstant(ZoneOffset.UTC).plusSeconds(3600), ZoneOffset.UTC));
        ActivityLog self = act("PORT", 41L, "h:41", T, "SCHEDULED_CHECK", "SUCCESS");
        Map<String, Object> series = m(svc.detail(self, admin()).get("series"));
        assertThat(series).containsEntry("from", at(-24 * 3600)).containsEntry("to", at(3600));
    }

    @Test
    @DisplayName("Sözleşme O1 / D5: sertifika kontrol satırı error_class / error_stage / resolved_ips / response_ms taşır; "
            + "sertifika ve erişilebilirlikte geçmiş bağlantısı yok (izleme bağlantısıyla aynı yer)")
    void certRowCarriesDiagnosisFields() {
        CertificateCheck cc = new CertificateCheck();
        cc.setDomain("y.example.com");
        cc.setCheckedAt(T);
        cc.setStatus("error");
        cc.setError("Connection timeout after 10s");
        cc.setErrorClass("NETWORK");
        cc.setErrorStage("tls-handshake");
        cc.setResolvedIps("10.1.2.3");
        cc.setResponseMs(210);
        em.persist(cc);
        com.sitemonitor.dto.CertificateDto dto = new com.sitemonitor.dto.CertificateDto();
        dto.setDomain("y.example.com");
        when(certService.historyRow(any(CertificateCheck.class))).thenReturn(dto);
        ActivityLog cert = act("CERT", 3L, "y.example.com", T, "SCHEDULED_CHECK", "ERROR");
        cert.setCheckId(cc.getId());
        Map<String, Object> d = svc.detail(cert, admin());
        assertThat(m(m(d.get("check")).get("row"))).containsEntry("error_class", "NETWORK")
                .containsEntry("error_stage", "tls-handshake").containsEntry("resolved_ips", "10.1.2.3")
                .containsEntry("response_ms", 210);
        Map<String, Object> links = m(d.get("links"));
        assertThat(links.get("monitor")).isNotNull();
        assertThat(links.get("history")).isNull();
        ActivityLog up = act("UPTIME", 3L, "y.example.com:443", T, "SCHEDULED_CHECK", "SUCCESS");
        assertThat(m(svc.detail(up, admin()).get("links")).get("history")).isNull();
    }

    @Test
    @DisplayName("Sözleşme Y1: WARNING kontrol satırında da (HTTP durum uyuşmazlığı) yaklaşık alarm aranır")
    void approximateAlarmOnWarningCheck() {
        AlertEvent ev = new AlertEvent();
        ev.setDomain("https://w.example.com/");
        ev.setAlertType("HTTP_DOWN");
        ev.setAlertLevel("CRITICAL");
        ev.setTeamId(5L);
        ev.setCreatedAt(at(-60));
        ev.setResolved(false);
        ev = alertRepo.save(ev);
        ActivityLog w = act("HTTP", 51L, "https://w.example.com/", T, "SCHEDULED_CHECK", "WARNING");
        assertThat(m(svc.detail(w, admin()).get("alarm"))).containsEntry("id", ev.getId()).containsEntry("match", "approximate");
    }

    @Test
    @DisplayName("FR D5: alarm satırı alert_info nesnesi taşır (result_detail JSON'undan); kontrol satırında anahtar yok")
    void alertInfoInRow() {
        ActivityLog a = act("PORT", 7L, "odeme.local", T, "ALERT_RESOLVED", "SUCCESS");
        a.setResultDetail("{\"alert_type\":\"PORT_DOWN\",\"level\":\"HIGH\",\"closed_by\":\"manual\",\"closer\":\"Ayşe\"}");
        Map<String, Object> data = m(svc.detail(a, admin()).get("data"));
        assertThat(m(data.get("alert_info"))).containsEntry("alert_type", "PORT_DOWN").containsEntry("closed_by", "manual")
                .containsEntry("closer", "Ayşe");
        ActivityLog bad = act("PORT", 7L, "odeme.local", T, "ALERT_TRIGGERED", "ERROR");
        bad.setResultDetail("bozuk");
        assertThat(m(svc.detail(bad, admin()).get("data"))).containsEntry("alert_info", null);
        ActivityLog check = act("PORT", 7L, "odeme.local:1", T, "SCHEDULED_CHECK", "SUCCESS");
        assertThat(m(svc.detail(check, admin()).get("data"))).doesNotContainKey("alert_info");
    }
}
