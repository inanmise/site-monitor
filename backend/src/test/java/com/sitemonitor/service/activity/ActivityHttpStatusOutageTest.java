package com.sitemonitor.service.activity;

import com.sitemonitor.model.ActivityLog;
import com.sitemonitor.model.AlertEvent;
import com.sitemonitor.model.HttpCheck;
import com.sitemonitor.repository.ActivityLogRepository;
import com.sitemonitor.repository.AlertEventRepository;
import com.sitemonitor.repository.CertificateInventoryRepository;
import com.sitemonitor.repository.TeamRepository;
import com.sitemonitor.service.ActivityLogService;
import com.sitemonitor.service.CertificateService;
import com.sitemonitor.service.EscalationService;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.TestPropertySource;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Sözleşme incelemesi Y1 (2026-11-05) uçtan uca, GERÇEK yazıcı + gerçek sorgular (H2): HTTP checker durum uyuşmazlığında
 * {@code error} YAZMAZ, yalnız {@code error_detail.kind = STATUS_MISMATCH} — ardışık 3 zamanlanmış 503:
 * <ul>
 *   <li>aktivite satırı {@code failure_reason = HTTP_STATUS} (eskiden UNKNOWN → rozet "Kontrol tamamlanamadı");</li>
 *   <li>{@code reason=HTTP_STATUS} süzgeci bu satırları bulur;</li>
 *   <li>zaman çizelgesi bunu KESİNTİ sayar (kayıtlı durum WARNING kalır — liste KPI'ları değişmez);</li>
 *   <li>ayrıntıda çakışan alarm YAKLAŞIK bağlanır (WARNING kontrolde de aranır).</li>
 * </ul>
 */
@DataJpaTest
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
class ActivityHttpStatusOutageTest {

    @Autowired ActivityLogRepository activityRepo;
    @Autowired AlertEventRepository alertRepo;
    @Autowired CertificateInventoryRepository inventoryRepo;
    @Autowired TeamRepository teamRepo;
    @Autowired EntityManager em;
    @Autowired DataSource dataSource;

    static final String URL = "https://kart-api.ornekbanka.com.tr/health";
    static final long MON = 4_242L;
    static final String DETAIL = "{\"kind\":\"STATUS_MISMATCH\",\"phase\":\"RESPONSE\",\"url\":\"" + URL + "\",\"method\":\"GET\","
            + "\"expected_status\":\"2xx\",\"http_status\":503}";

    ActivityLogService writer;

    @BeforeEach
    void setUp() {
        writer = new ActivityLogService(activityRepo);
    }

    @SuppressWarnings("unchecked")
    private static <X> ObjectProvider<X> provider(X value) {
        ObjectProvider<X> p = mock(ObjectProvider.class);
        when(p.getIfAvailable()).thenReturn(value);
        return p;
    }

    private ActivityLog writeCheck(String at, boolean ok, Integer status) {
        HttpCheck c = new HttpCheck();
        c.setMonitorId(MON);
        c.setCheckedAt(at);
        c.setOk(ok);
        c.setHttpStatus(status);
        c.setResponseMs(95L);
        if (!ok) c.setErrorDetail(DETAIL);   // gerçek checker: error YOK, yalnız tanı
        em.persist(c);
        Map<String, Object> result = new HashMap<>();
        result.put("ok", ok);
        result.put("http_status", status);
        result.put("response_ms", 95);
        writer.recordCheck(ActivityLogService.HTTP, MON, "Kart API", URL, 5L, false, "scheduler", result,
                ActivityLogService.refs(c));
        ActivityLog row = activityRepo.findAll().stream().filter(a -> a.getCheckId() != null && a.getCheckId().equals(c.getId()))
                .findFirst().orElseThrow();
        row.setActivityTime(at);
        return activityRepo.save(row);
    }

    @Test
    @DisplayName("3 zamanlanmış 503 (error yok, STATUS_MISMATCH): neden HTTP_STATUS, süzgeç bulur, çizelgede kesinti, ayrıntıda yaklaşık alarm")
    void statusMismatchIsAnOutage() {
        writeCheck("2031-05-01T10:00:00", true, 200);
        ActivityLog f1 = writeCheck("2031-05-01T10:01:00", false, 503);
        writeCheck("2031-05-01T10:02:00", false, 503);
        ActivityLog f3 = writeCheck("2031-05-01T10:03:00", false, 503);
        writeCheck("2031-05-01T10:04:00", true, 200);

        assertThat(f1.getResultStatus()).as("kayıtlı durum değişmez (sözlük)").isEqualTo("WARNING");
        assertThat(f1.getFailureReason()).isEqualTo("HTTP_STATUS");
        assertThat(f1.getHttpStatus()).isEqualTo(503);

        // Süzgeç: reason=HTTP_STATUS üç satırı bulur
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        ActivityQueryService queries = new ActivityQueryService(jdbc);
        ActivityFilter f = ActivityFilter.parse(new ActivityFilter.Params(null, null, "HTTP:" + MON, null, null, null, null,
                null, null, "HTTP_STATUS", null), false, List.of());
        assertThat(queries.list(f, 0, 50).total()).isEqualTo(3);

        // Zaman çizelgesi: tek kesinti, erişilebilirlik %100 DEĞİL
        ActivityTimelineService timelines = new ActivityTimelineService(jdbc);
        Map<String, Object> t = timelines.timeline("HTTP:" + MON, "2031-05-01T09:00:00Z", "2031-05-01T11:00:00Z", false,
                List.of(), id -> true, Instant.parse("2031-05-01T11:00:00Z"));
        assertThat(t.get("outage_count")).isEqualTo(1);
        assertThat(((Number) t.get("availability_pct")).doubleValue()).isLessThan(100.0);

        // Ayrıntı: WARNING kontrolde çakışan alarm yaklaşık bağlanır
        AlertEvent ev = new AlertEvent();
        ev.setDomain(URL);
        ev.setAlertType(EscalationService.TYPE_HTTP_DOWN);
        ev.setAlertLevel("CRITICAL");
        ev.setTeamId(5L);
        ev.setCreatedAt("2031-05-01T10:02:30");
        ev.setResolved(true);
        ev.setResolvedAt("2031-05-01T10:16:00");
        ev.setResolvedBy("system");
        ev = alertRepo.save(ev);
        PermissionService perms = mock(PermissionService.class);
        when(perms.allows(any(jakarta.servlet.http.HttpSession.class), anyString(), anyString())).thenReturn(true);
        ActivityDetailService detail = new ActivityDetailService(activityRepo, alertRepo, inventoryRepo, teamRepo, perms,
                provider((CertificateService) null), provider((ScriptedCheckerService) null), provider((NocCallLogService) null),
                provider((tools.jackson.databind.ObjectMapper) null));
        detail.setEntityManager(em);
        MockHttpSession admin = new MockHttpSession();
        admin.setAttribute("authenticated", Boolean.TRUE);
        admin.setAttribute("username", "admin");
        admin.setAttribute("systemRole", "ADMIN");
        @SuppressWarnings("unchecked")
        Map<String, Object> alarm = (Map<String, Object>) detail.detail(f3, admin).get("alarm");
        assertThat(alarm).containsEntry("id", ev.getId()).containsEntry("match", "approximate");
    }

    private ActivityLog writeKeyword(String at, boolean ok, int count) {
        com.sitemonitor.model.KeywordResult kr = new com.sitemonitor.model.KeywordResult();
        kr.setMonitorId(KW);
        kr.setCheckedAt(at);
        kr.setFound(count > 0);
        kr.setOccurrences(count);
        kr.setOk(ok);
        kr.setHttpStatus(200);
        kr.setResponseMs(80L);
        if (!ok) {
            kr.setFailureReason("KEYWORD_FOUND_FORBIDDEN");
            kr.setFailureDetail("Yasaklı kelime sayfada 1 kez geçiyor");
        }
        em.persist(kr);
        // Gerçek yazıcının (SchedulerService.recheckKeyword) sonuç haritası: found + count + adet koşulunun sonucu ok
        Map<String, Object> result = new HashMap<>();
        result.put("found", count > 0);
        result.put("count", count);
        result.put("http_status", 200);
        result.put("response_ms", 80);
        result.put("ok", ok);
        writer.recordCheck(ActivityLogService.KEYWORD, KW, "Bakım duyurusu yok", KW_URL, 5L, false, "scheduler", result,
                ActivityLogService.refs(kr));
        ActivityLog row = activityRepo.findAll().stream().filter(a -> a.getCheckId() != null && a.getCheckId().equals(kr.getId()))
                .findFirst().orElseThrow();
        row.setActivityTime(at);
        return activityRepo.save(row);
    }

    static final long KW = 4_343L;
    static final String KW_URL = "https://www.ornekbanka.com.tr/";

    @Test
    @DisplayName("Hata avı Y1: Keyword \"içermemeli\" — kelime BULUNDU (found=true) ama koşul sağlanmadı (ok=false) → satır WARNING "
            + "(asla Başarılı), neden süzgeci bulur, zaman çizelgesinde kesinti, kök neden çapayı başarısız sayar")
    void keywordConditionFailureIsAnOutage() {
        writeKeyword("2031-06-01T10:00:00", true, 0);
        ActivityLog f1 = writeKeyword("2031-06-01T10:01:00", false, 1);
        writeKeyword("2031-06-01T10:02:00", false, 1);
        writeKeyword("2031-06-01T10:03:00", true, 0);
        em.flush();   // son satırın zaman düzeltmesi JDBC sorgularından önce yazılsın (JdbcTemplate otomatik flush yapmaz)

        assertThat(f1.getResultStatus()).isEqualTo("WARNING");
        assertThat(f1.getFailureReason()).isEqualTo("KEYWORD_FOUND_FORBIDDEN");

        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        ActivityQueryService queries = new ActivityQueryService(jdbc);
        ActivityFilter byReason = ActivityFilter.parse(new ActivityFilter.Params(null, null, "KEYWORD:" + KW, null, null, null,
                null, null, null, "KEYWORD_FOUND_FORBIDDEN", null), false, List.of());
        ActivityQueryService.PageResult hits = queries.list(byReason, 0, 50);
        assertThat(hits.total()).isEqualTo(2);
        assertThat(hits.rows()).extracting(ActivityLog::getResultStatus).containsOnly("WARNING");
        ActivityFilter ok = ActivityFilter.parse(new ActivityFilter.Params(null, null, "KEYWORD:" + KW, "SUCCESS", null, null,
                null, null, null, null, null), false, List.of());
        assertThat(queries.list(ok, 0, 50).total()).as("Başarılı yalnız koşulu sağlayan iki kontrol").isEqualTo(2);

        ActivityTimelineService timelines = new ActivityTimelineService(jdbc);
        Map<String, Object> t = timelines.timeline("KEYWORD:" + KW, "2031-06-01T09:00:00Z", "2031-06-01T11:00:00Z", false,
                List.of(), id -> true, Instant.parse("2031-06-01T11:00:00Z"));
        assertThat(t.get("outage_count")).isEqualTo(1);
        assertThat(((Number) t.get("availability_pct")).doubleValue()).isEqualTo(50.0);

        Map<String, Object> rel = new ActivityRelatedService(jdbc).related(f1.getId(), 120, false, List.of(), team -> true).body();
        assertThat(((Map<?, ?>) rel.get("anchor")).get("failing")).isEqualTo(true);
        assertThat(rel.get("total")).isEqualTo(1);   // 10:02'deki koşul hatası
    }

    @Test
    @DisplayName("Çizelge durum eşlemesi: HTTP / KEYWORD WARNING ve sentetik FAIL kesinti; NO_CHECKS, sorgu ihlali, yavaş uyarı kalır")
    void timelineStatusMapping() {
        assertThat(ActivityTimelineService.timelineStatus("HTTP", "SCHEDULED_CHECK", "WARNING", null)).isEqualTo("ERROR");
        assertThat(ActivityTimelineService.timelineStatus("KEYWORD", "MANUAL_CHECK", "WARNING", "KEYWORD_NOT_FOUND")).isEqualTo("ERROR");
        assertThat(ActivityTimelineService.timelineStatus("SCRIPTED", "SCHEDULED_CHECK", "WARNING", "SCRIPT_CHECK_FAILED")).isEqualTo("ERROR");
        assertThat(ActivityTimelineService.timelineStatus("SCRIPTED", "SCHEDULED_CHECK", "WARNING", null)).isEqualTo("ERROR");
        assertThat(ActivityTimelineService.timelineStatus("SCRIPTED", "SCHEDULED_CHECK", "WARNING", "SCRIPT_NO_CHECKS")).isEqualTo("WARNING");
        assertThat(ActivityTimelineService.timelineStatus("DBQUERY", "SCHEDULED_CHECK", "WARNING", null)).isEqualTo("WARNING");
        assertThat(ActivityTimelineService.timelineStatus("PAGESPEED", "SCHEDULED_CHECK", "WARNING", null)).isEqualTo("WARNING");
        assertThat(ActivityTimelineService.timelineStatus("HTTP", "ALERT_TRIGGERED", "WARNING", null)).isEqualTo("WARNING");
        assertThat(ActivityTimelineService.timelineStatus("HTTP", "SCHEDULED_CHECK", "SUCCESS", null)).isEqualTo("SUCCESS");
    }
}
