package com.sitemonitor.service;

import com.sitemonitor.model.AlertEvent;
import com.sitemonitor.model.CertificateInventory;
import com.sitemonitor.model.DnsMonitor;
import com.sitemonitor.model.LatestCheck;
import com.sitemonitor.model.MaintenanceWindow;
import com.sitemonitor.model.NotificationLog;
import com.sitemonitor.model.UserPushDelivery;
import com.sitemonitor.model.WeakAlgorithmException;
import com.sitemonitor.model.DomainCheck;
import com.sitemonitor.model.DomainMonitor;
import com.sitemonitor.model.HttpMonitor;
import com.sitemonitor.model.PingMonitor;
import com.sitemonitor.model.PortMonitor;
import com.sitemonitor.repository.ActivityLogRepository;
import com.sitemonitor.repository.AlertEventRepository;
import com.sitemonitor.repository.CertificateInventoryRepository;
import com.sitemonitor.repository.LatestCheckRepository;
import com.sitemonitor.repository.MaintenanceWindowRepository;
import com.sitemonitor.repository.MonitorChangeLogRepository;
import com.sitemonitor.repository.NotificationLogRepository;
import com.sitemonitor.repository.UserPushDeliveryRepository;
import com.sitemonitor.repository.WeakAlgorithmExceptionRepository;
import com.sitemonitor.repository.DnsMonitorRepository;
import com.sitemonitor.repository.DomainCheckRepository;
import com.sitemonitor.repository.DomainMonitorRepository;
import com.sitemonitor.repository.HttpMonitorRepository;
import com.sitemonitor.repository.KeywordMonitorRepository;
import com.sitemonitor.repository.PageMonitorRepository;
import com.sitemonitor.repository.PageSpeedMonitorRepository;
import com.sitemonitor.repository.PingMonitorRepository;
import com.sitemonitor.repository.PortMonitorRepository;
import com.sitemonitor.repository.ScriptedMonitorRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * "Sizin için — bugün" izleme kartları (2026-09-19): kararsız / yavaşlayan / bayat / alan adı kaydı.
 * {@code now} enjekte edilir — kayan pencere, sabit fixture tarihi YOK (CI UTC / yerel Istanbul tuzağı).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@SuppressWarnings("unchecked")
class TodayMonitorInsightsServiceTest {

    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);
    private static final Instant NOW = Instant.parse("2026-09-19T12:00:00Z");

    @Mock ActivityLogRepository activityRepo;
    @Mock CertificateInventoryRepository inventoryRepo;
    @Mock NotificationLogRepository notificationLogRepo;
    @Mock UserPushDeliveryRepository pushDeliveryRepo;
    @Mock AlertEventRepository alertEventRepo;
    @Mock LatestCheckRepository latestCheckRepo;
    @Mock WeakAlgorithmExceptionRepository exceptionRepo;
    @Mock CertificateHealthService healthService;
    @Mock DomainCheckRepository domainCheckRepo;
    @Mock HttpMonitorRepository httpRepo;
    @Mock PortMonitorRepository portRepo;
    @Mock PingMonitorRepository pingRepo;
    @Mock DnsMonitorRepository dnsRepo;
    @Mock KeywordMonitorRepository keywordRepo;
    @Mock PageMonitorRepository pageRepo;
    @Mock PageSpeedMonitorRepository pageSpeedRepo;
    @Mock ScriptedMonitorRepository scriptedRepo;
    @Mock DomainMonitorRepository domainRepo;
    @Mock MaintenanceWindowRepository maintenanceRepo;
    @Mock MonitorChangeLogRepository changeLogRepo;
    TodayMonitorInsightsService svc;

    private static String at(long minutesAgo) { return ISO.format(NOW.minus(Duration.ofMinutes(minutesAgo))); }

    private static HttpMonitor http(long id, String name, boolean active, int intervalSec, String createdAt) {
        HttpMonitor m = new HttpMonitor(); m.setId(id); m.setName(name); m.setUrl("https://" + name + ".example.com/health");
        m.setTeamId(1L); m.setActive(active); m.setIntervalSeconds(intervalSec); m.setCreatedAt(createdAt); return m;
    }
    private static DomainMonitor dom(long id, String domain, boolean active) {
        DomainMonitor m = new DomainMonitor(); m.setId(id); m.setName(domain); m.setDomain(domain); m.setTeamId(1L); m.setActive(active); return m;
    }
    private static DomainCheck dc(long monitorId, Integer days) {
        DomainCheck c = new DomainCheck(); c.setMonitorId(monitorId); c.setDaysRemaining(days); c.setExpiryDate("2026-10-01"); c.setRegistrar("Registrar X"); return c;
    }

    @BeforeEach
    void setUp() {
        svc = new TodayMonitorInsightsService(activityRepo, inventoryRepo, notificationLogRepo, pushDeliveryRepo, alertEventRepo,
                latestCheckRepo, exceptionRepo, healthService, domainCheckRepo, httpRepo, portRepo, pingRepo, dnsRepo,
                keywordRepo, pageRepo, pageSpeedRepo, scriptedRepo, domainRepo,
                maintenanceRepo, new MaintenanceService(maintenanceRepo), changeLogRepo);
        for (var r : List.of(portRepo, pingRepo, dnsRepo, keywordRepo, pageRepo, pageSpeedRepo, scriptedRepo, domainRepo, httpRepo))
            when(r.findAll()).thenReturn(List.of());
        when(activityRepo.monitorsWithFailureSince(anyString())).thenReturn(List.of());
        when(activityRepo.statusSequence(anyString(), anyList(), anyString())).thenReturn(List.of());
        when(activityRepo.avgResponseByMonitor(anyString(), anyString())).thenReturn(List.of());
        when(activityRepo.lastCheckByMonitor(anyString())).thenReturn(List.of());
        when(domainCheckRepo.findLatestPerMonitor()).thenReturn(List.of());
        when(inventoryRepo.findByActiveTrueOrderByDomainAsc()).thenReturn(List.of());
        when(notificationLogRepo.findAllSince(anyString())).thenReturn(List.of());
        when(pushDeliveryRepo.findByStatusAndCreatedAtGreaterThanEqualOrderByIdDesc(anyString(), anyString())).thenReturn(List.of());
        when(latestCheckRepo.findAll()).thenReturn(List.of());
        when(exceptionRepo.findAll()).thenReturn(List.of());
        when(maintenanceRepo.findByActiveTrue()).thenReturn(List.of());
        when(changeLogRepo.lastActiveChange(any())).thenReturn(List.of());
        when(alertEventRepo.findByCreatedAtGreaterThanEqualOrderByCreatedAtDesc(anyString())).thenReturn(List.of());
        when(alertEventRepo.findByResolvedAtGreaterThanEqual(anyString())).thenReturn(List.of());
        when(healthService.thresholdResolution()).thenReturn(ThresholdResolution.fixed(null));   // tier bazlı çözüm (2026-09-20): 30/15/7
    }

    // ── Teslim edilemeyen bildirim ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("bildirim: FAILED e-posta/webhook (alarm üstünden takım+alan) ve FAILED push (takım) girer; SENT/SKIPPED girmez; hata metni 'FAILED:' önekinden arındırılır; yeni üstte")
    void notifications_failedOnly() {
        NotificationLog okMail = new NotificationLog(); okMail.setAlertEventId(1L); okMail.setSentAt(at(60)); okMail.setEmailStatus("SENT"); okMail.setWebhookStatus("SKIPPED");
        NotificationLog badMail = new NotificationLog(); badMail.setAlertEventId(1L); badMail.setSentAt(at(30)); badMail.setEmailStatus("FAILED: 550 mailbox unavailable"); badMail.setWebhookStatus("SENT"); badMail.setRecipientEmail("ops@example.com"); badMail.setSubject("[Site Monitor] down");
        NotificationLog badHook = new NotificationLog(); badHook.setAlertEventId(2L); badHook.setSentAt(at(10)); badHook.setEmailStatus("SKIPPED_DISABLED"); badHook.setWebhookStatus("FAILED:connect timed out"); badHook.setRecipientEmail("hook");
        when(notificationLogRepo.findAllSince(anyString())).thenReturn(List.of(okMail, badMail, badHook));
        AlertEvent e1 = new AlertEvent(); e1.setId(1L); e1.setDomain("a.example.com"); e1.setTeamId(1L); e1.setAlertType("HTTP_DOWN");
        AlertEvent e2 = new AlertEvent(); e2.setId(2L); e2.setDomain("b.example.com"); e2.setTeamId(2L); e2.setAlertType("PORT_DOWN");
        when(alertEventRepo.findAllById(any())).thenReturn(List.of(e1, e2));
        UserPushDelivery push = new UserPushDelivery(); push.setStatus("FAILED"); push.setUsername("u1"); push.setDisplayName("Kullanıcı Bir"); push.setError("HTTP 500 from provider"); push.setCreatedAt(at(5)); push.setTeamId(1L); push.setMonitorName("api"); push.setAlertEventId(1L);
        when(pushDeliveryRepo.findByStatusAndCreatedAtGreaterThanEqualOrderByIdDesc(eq("FAILED"), anyString())).thenReturn(List.of(push));

        List<Map<String, Object>> n = svc.snapshot(NOW).notifications();

        assertThat(n).extracting(m -> m.get("channel")).containsExactly("PUSH", "WEBHOOK", "EMAIL");
        assertThat(n.get(2)).containsEntry("target", "ops@example.com").containsEntry("error", "550 mailbox unavailable")
                .containsEntry("domain", "a.example.com").containsEntry("team_id", 1L).containsEntry("alert_event_id", 1L).containsEntry("monitor_name", "HTTP_DOWN");
        assertThat(n.get(1)).containsEntry("error", "connect timed out").containsEntry("team_id", 2L);
        assertThat(n.get(0)).containsEntry("target", "Kullanıcı Bir").containsEntry("error", "HTTP 500 from provider").containsEntry("monitor_name", "api");
    }

    // ── Sertifika sağlık bulguları ──────────────────────────────────────────────────────────

    private static CertificateHealthService.HealthRow hrow(String key, CertificateHealthRules.Status st, String valueKey, Object... args) {
        return new CertificateHealthService.HealthRow(key, "certificate", st, valueKey, List.of(args), "none", List.of(), Map.of());
    }

    @Test
    @DisplayName("sağlık: FAIL satırlar bulgu (expiry hariç), kritik anahtar (trust/chain/sanMatch/revocation) üstte; süresi dolmamış zayıf-algoritma istisnası signature/keySize'ı susturur, dolmuş istisna susturmaz; kontrolsüz alan atlanır")
    void health_findingsWithExceptionSilencing() {
        CertificateInventory a = new CertificateInventory(); a.setDomain("a.example.com"); a.setTeamId(1L); a.setActive(true);
        CertificateInventory b = new CertificateInventory(); b.setDomain("b.example.com"); b.setTeamId(1L); b.setActive(true);
        CertificateInventory c = new CertificateInventory(); c.setDomain("c.example.com"); c.setTeamId(2L); c.setActive(true);
        CertificateInventory d = new CertificateInventory(); d.setDomain("d.example.com"); d.setTeamId(2L); d.setActive(true);
        CertificateInventory never = new CertificateInventory(); never.setDomain("never.example.com"); never.setActive(true);
        when(inventoryRepo.findByActiveTrueOrderByDomainAsc()).thenReturn(List.of(a, b, c, d, never));
        LatestCheck la = new LatestCheck(); la.setDomain("a.example.com");
        LatestCheck lb = new LatestCheck(); lb.setDomain("b.example.com");
        LatestCheck lc = new LatestCheck(); lc.setDomain("c.example.com");
        LatestCheck ld = new LatestCheck(); ld.setDomain("d.example.com");
        when(latestCheckRepo.findAll()).thenReturn(List.of(la, lb, lc, ld));
        // "bugün" enjekte edilen NOW'dan (2026-09-23: health() artık duvar saatini okumuyor) — kurum zonunda.
        String today = NOW.atZone(ZoneId.of("Europe/Istanbul")).toLocalDate().toString();
        WeakAlgorithmException live = new WeakAlgorithmException(); live.setDomain("b.example.com"); live.setUntil(LocalDate.parse(today).plusDays(3).toString());
        WeakAlgorithmException dead = new WeakAlgorithmException(); dead.setDomain("c.example.com"); dead.setUntil(LocalDate.parse(today).minusDays(1).toString());
        when(exceptionRepo.findAll()).thenReturn(List.of(live, dead));
        // a: zayıf imza + süre (süre hariç) → 1 bulgu, kritik değil; b: zayıf imza SUSTURULMUŞ + zincir kırık → 1 bulgu kritik;
        // c: zayıf imza (istisna dolmuş → görünür); d: hepsi OK/UNKNOWN → yok
        when(healthService.evaluate(eq(la), any(), eq(false), eq(30), eq(7))).thenReturn(new CertificateHealthService.HealthResult(List.of(
                hrow("expiry", CertificateHealthRules.Status.FAIL, "daysLeft", 3), hrow("signature", CertificateHealthRules.Status.FAIL, "weakAlgorithm", "SHA1withRSA")), 0, 2));
        when(healthService.evaluate(eq(lb), any(), eq(false), eq(30), eq(7))).thenReturn(new CertificateHealthService.HealthResult(List.of(
                hrow("signature", CertificateHealthRules.Status.FAIL, "weakAlgorithm", "SHA1withRSA"), hrow("chain", CertificateHealthRules.Status.FAIL, "broken")), 0, 2));
        when(healthService.evaluate(eq(lc), any(), eq(false), eq(30), eq(7))).thenReturn(new CertificateHealthService.HealthResult(List.of(
                hrow("keySize", CertificateHealthRules.Status.FAIL, "shortKey", "RSA", 1024)), 0, 1));
        when(healthService.evaluate(eq(ld), any(), eq(false), eq(30), eq(7))).thenReturn(new CertificateHealthService.HealthResult(List.of(
                hrow("trust", CertificateHealthRules.Status.OK, "trusted"), hrow("chain", CertificateHealthRules.Status.UNKNOWN, "unverified")), 1, 1));

        List<Map<String, Object>> h = svc.snapshot(NOW).health();

        assertThat(h).extracting(m -> m.get("domain")).containsExactly("b.example.com", "a.example.com", "c.example.com");
        assertThat(h.get(0)).containsEntry("critical", true).containsEntry("silenced", true);
        assertThat((List<?>) h.get(0).get("findings")).hasSize(1);
        assertThat(((List<Map<String, Object>>) h.get(0).get("findings")).get(0)).containsEntry("key", "chain").containsEntry("value_key", "broken");
        assertThat(((List<Map<String, Object>>) h.get(1).get("findings")).get(0)).containsEntry("key", "signature").containsEntry("value_args", List.of("SHA1withRSA"));
        assertThat(h.get(2)).containsEntry("critical", false).containsEntry("silenced", false).containsEntry("team_id", 2L);
    }

    // ── Kararsız ────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("kararsız: UP↔DOWN geçişi ≥3 olan izleme girer (WARNING 'iyi' sayılır), 2 geçiş girmez, pasif izleme girmez; çok geçiş üstte, son durum DOWN/UP")
    void flapping_countsTransitions() {
        when(httpRepo.findAll()).thenReturn(List.of(http(1, "a", true, 60, at(10_000)), http(2, "b", true, 60, at(10_000)),
                http(3, "c", false, 60, at(10_000)), http(4, "d", true, 60, at(10_000))));
        when(activityRepo.monitorsWithFailureSince(anyString())).thenReturn(List.<Object[]>of(new Object[]{"HTTP", 1L}, new Object[]{"HTTP", 2L}, new Object[]{"HTTP", 3L}, new Object[]{"HTTP", 4L}));
        List<Object[]> seq = new ArrayList<>();
        // 1: S E S E S → 4 geçiş, son UP
        int t = 100; for (String st : List.of("SUCCESS", "ERROR", "SUCCESS", "ERROR", "SUCCESS")) seq.add(new Object[]{1L, at(t--), st});
        // 2: S E S → 2 geçiş (eşik altı)
        t = 100; for (String st : List.of("SUCCESS", "ERROR", "SUCCESS")) seq.add(new Object[]{2L, at(t--), st});
        // 3: pasif — 4 geçiş olsa da girmez
        t = 100; for (String st : List.of("SUCCESS", "TIMEOUT", "SUCCESS", "ERROR", "WARNING")) seq.add(new Object[]{3L, at(t--), st});
        // 4: W E W E → 3 geçiş, son DOWN
        t = 100; for (String st : List.of("WARNING", "ERROR", "WARNING", "ERROR")) seq.add(new Object[]{4L, at(t--), st});
        when(activityRepo.statusSequence(eq("HTTP"), anyList(), anyString())).thenReturn(seq);

        List<Map<String, Object>> f = svc.snapshot(NOW).flapping();

        assertThat(f).extracting(m -> m.get("monitor_id")).containsExactly(1L, 4L);
        assertThat(f.get(0)).containsEntry("transitions", 4).containsEntry("last_status", "UP").containsEntry("type", "HTTP")
                .containsEntry("domain", "a.example.com").containsEntry("team_id", 1L);
        assertThat(f.get(1)).containsEntry("transitions", 3).containsEntry("last_status", "DOWN");
    }

    // ── Yavaşlayan ──────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("yavaşlayan: 24 saat ortalaması tabanın ≥1,5 katı VE ≥100 ms fazla → girer; oran var ama fark <100 ms girmez; örnek <3 girmez; DOMAIN girmez")
    void slow_ratioAndDeltaGate() {
        when(httpRepo.findAll()).thenReturn(List.of(http(1, "slow", true, 60, at(10_000)), http(2, "tiny", true, 60, at(10_000)),
                http(3, "few", true, 60, at(10_000)), http(4, "fine", true, 60, at(10_000))));
        when(domainRepo.findAll()).thenReturn(List.of(dom(9, "d.example.com", true)));
        String dayAgo = at(24 * 60);
        // bugün (from = 24 saat önce)
        when(activityRepo.avgResponseByMonitor(eq(dayAgo), anyString())).thenReturn(List.<Object[]>of(
                new Object[]{"HTTP", 1L, 450.0, 20L},   // taban 200 → 2,25× ve +250 ms → girer
                new Object[]{"HTTP", 2L, 16.0, 20L},    // taban 10 → 1,6× ama +6 ms → girmez
                new Object[]{"HTTP", 3L, 900.0, 2L},    // 2 örnek → girmez
                new Object[]{"HTTP", 4L, 210.0, 20L},   // taban 200 → 1,05× → girmez
                new Object[]{"DOMAIN", 9L, 5000.0, 5L}));
        // taban (to = 24 saat önce)
        when(activityRepo.avgResponseByMonitor(anyString(), eq(dayAgo))).thenReturn(List.<Object[]>of(
                new Object[]{"HTTP", 1L, 200.0, 100L}, new Object[]{"HTTP", 2L, 10.0, 100L},
                new Object[]{"HTTP", 3L, 100.0, 100L}, new Object[]{"HTTP", 4L, 200.0, 100L},
                new Object[]{"DOMAIN", 9L, 100.0, 7L}));

        List<Map<String, Object>> s = svc.snapshot(NOW).slow();

        assertThat(s).hasSize(1);
        assertThat(s.get(0)).containsEntry("monitor_id", 1L).containsEntry("today_ms", 450L).containsEntry("baseline_ms", 200L)
                .containsEntry("ratio", 2.3).containsEntry("samples", 20);
    }

    // ── Bayat ───────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("bayat: son kontrol 2×aralığı (taban 5 dk) aşan aktif izleme girer; hiç kontrolü olmayan yeni izleme yaratılış anından ölçülür; pasifler sayılır; en eski üstte")
    void stale_twoIntervalsWithFloor() {
        when(httpRepo.findAll()).thenReturn(List.of(
                http(1, "old", true, 60, at(10_000)),      // son kontrol 30 dk önce, eşik max(2 dk, 5 dk)=5 dk → bayat
                http(2, "fresh", true, 60, at(10_000)),    // son kontrol 3 dk önce → taze (eşik 5 dk)
                http(3, "slowly", true, 1800, at(10_000)), // aralık 30 dk, son kontrol 50 dk önce, eşik 60 dk → taze
                http(4, "never-new", true, 60, at(2)),     // hiç kontrol yok, 2 dk önce yaratıldı → taze
                http(5, "never-old", true, 60, at(600)),   // hiç kontrol yok, 10 saat önce yaratıldı → bayat (age 600 dk, never)
                http(8, "ancient", true, 60, at(30 * 1440)), // hiç kontrol yok, 30 gün önce yaratıldı → "7+ gündür yok" (yaş pencereyle sınırlı, never DEĞİL)
                http(6, "paused", false, 60, at(10_000)),  // duraklatılmış → bayat değil, paused listesinde
                http(7, "paused2", false, 60, at(10_000))));
        when(activityRepo.lastCheckByMonitor(anyString())).thenReturn(List.<Object[]>of(
                new Object[]{"HTTP", 1L, at(30)}, new Object[]{"HTTP", 2L, at(3)}, new Object[]{"HTTP", 3L, at(50)}));

        TodayMonitorInsightsService.Snapshot snap = svc.snapshot(NOW);

        assertThat(snap.paused()).extracting(m -> m.get("monitor_id")).containsExactlyInAnyOrder(6L, 7L);   // 2026-09-23: sayı değil satır
        assertThat(snap.stale()).extracting(m -> m.get("monitor_id")).containsExactly(8L, 5L, 1L);
        assertThat(snap.stale().get(2)).containsEntry("age_min", 30L).containsEntry("expected_min", 5L).containsEntry("interval_sec", 60)
                .containsEntry("last_check", at(30)).containsEntry("never", false);
        assertThat(snap.stale().get(1)).containsEntry("last_check", null).containsEntry("never", true).containsEntry("age_min", 600L);
        assertThat(snap.stale().get(0)).containsEntry("last_check", null).containsEntry("never", false).containsEntry("age_min", 7L * 1440);
    }

    // ── Alan adı kaydı ──────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("alan adı kaydı: 30 gün ve altı (dolmuş dâhil) girer, en az gün üstte; 31+ gün, pasif izleme ve gün bilgisi olmayan girmez")
    void domains_within30Days() {
        when(domainRepo.findAll()).thenReturn(List.of(dom(1, "a.example.com", true), dom(2, "b.example.com", true),
                dom(3, "c.example.com", false), dom(4, "d.example.com", true), dom(5, "e.example.com", true)));
        when(domainCheckRepo.findLatestPerMonitor()).thenReturn(List.of(dc(1, 12), dc(2, -3), dc(3, 1), dc(4, 31), dc(5, null)));

        TodayMonitorInsightsService.Snapshot snap = svc.snapshot(NOW);

        assertThat(snap.domains()).extracting(m -> m.get("monitor_id")).containsExactly(2L, 1L);
        assertThat(snap.domainsExpired()).isEqualTo(1);
        assertThat(snap.domains().get(0)).containsEntry("days", -3).containsEntry("expiry_date", "2026-10-01").containsEntry("registrar", "Registrar X")
                .containsEntry("type", "DOMAIN").containsEntry("domain", "b.example.com");
    }

    // ── Ortak ───────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("hedef → çıplak host: URL şeması/yol, host:port, kullanıcı@ ve büyük harf sadeleşir; Sentetik (hedefsiz) null")
    void hostOf() {
        assertThat(TodayMonitorInsightsService.hostOf("https://API.Example.com:8443/health?x=1")).isEqualTo("api.example.com");
        assertThat(TodayMonitorInsightsService.hostOf("mail.example.com:25")).isEqualTo("mail.example.com");
        assertThat(TodayMonitorInsightsService.hostOf("user@db.example.com")).isEqualTo("db.example.com");
        assertThat(TodayMonitorInsightsService.hostOf(null)).isNull();
        assertThat(TodayMonitorInsightsService.hostOf("  ")).isNull();
    }

    @Test
    @DisplayName("bayat: envanter-türevi (standalone değil) DNS/Port izlemesi alanı aktif envanterde DEĞİLSE öksüzdür — süpürme atlıyor, bayat sayılmaz; standalone ve envanterdeki girer")
    void stale_skipsOrphanedInventoryDerived() {
        CertificateInventory inv = new CertificateInventory(); inv.setDomain("Shop.example.com"); inv.setActive(true);
        when(inventoryRepo.findByActiveTrueOrderByDomainAsc()).thenReturn(List.of(inv));
        PortMonitor orphan = new PortMonitor(); orphan.setId(1L); orphan.setName("gone"); orphan.setHost("gone.example.com"); orphan.setPort(443); orphan.setActive(true); orphan.setStandalone(false); orphan.setCreatedAt(at(10_000));
        PortMonitor derived = new PortMonitor(); derived.setId(2L); derived.setName("shop"); derived.setHost("shop.example.com"); derived.setPort(443); derived.setActive(true); derived.setStandalone(null); derived.setCreatedAt(at(10_000));
        PortMonitor alone = new PortMonitor(); alone.setId(3L); alone.setName("alone"); alone.setHost("x.example.com"); alone.setPort(22); alone.setActive(true); alone.setStandalone(true); alone.setCreatedAt(at(10_000));
        when(portRepo.findAll()).thenReturn(List.of(orphan, derived, alone));
        DnsMonitor dnsOrphan = new DnsMonitor(); dnsOrphan.setId(4L); dnsOrphan.setName("gone-dns"); dnsOrphan.setDomain("gone.example.com"); dnsOrphan.setActive(true); dnsOrphan.setCreatedAt(at(10_000));
        when(dnsRepo.findAll()).thenReturn(List.of(dnsOrphan));

        assertThat(svc.snapshot(NOW).stale()).extracting(m -> m.get("monitor_id")).containsExactlyInAnyOrder(2L, 3L);
    }

    @Test
    @DisplayName("bir kartın sorgusu düşerse o kart boş, diğerleri hesaplanır (panel yıkılmaz); takımsız DNS satırı domain taşır")
    void oneBlockFailing_othersSurvive() {
        when(activityRepo.monitorsWithFailureSince(anyString())).thenThrow(new IllegalStateException("db"));
        DnsMonitor d = new DnsMonitor(); d.setId(7L); d.setName("ns"); d.setDomain("Shop.example.com"); d.setActive(true); d.setIntervalSeconds(60); d.setCreatedAt(at(10_000)); d.setStandalone(true);
        when(dnsRepo.findAll()).thenReturn(List.of(d));
        PortMonitor p = new PortMonitor(); p.setId(8L); p.setName("db"); p.setHost("db.example.com"); p.setPort(5432); p.setActive(true); p.setIntervalSeconds(60); p.setCreatedAt(at(10_000)); p.setStandalone(true);
        when(portRepo.findAll()).thenReturn(List.of(p));
        PingMonitor g = new PingMonitor(); g.setId(9L); g.setName("gw"); g.setHost("10.0.0.1"); g.setTeamId(2L); g.setActive(true); g.setIntervalSeconds(60); g.setCreatedAt(at(10_000));
        when(pingRepo.findAll()).thenReturn(List.of(g));

        TodayMonitorInsightsService.Snapshot snap = svc.snapshot(NOW);

        assertThat(snap.flapping()).isEmpty();
        assertThat(snap.stale()).extracting(m -> m.get("monitor_id")).containsExactlyInAnyOrder(7L, 8L, 9L);
        Map<String, Object> dns = snap.stale().stream().filter(m -> m.get("monitor_id").equals(7L)).findFirst().orElseThrow();
        assertThat(dns).containsEntry("type", "DNS").containsEntry("domain", "shop.example.com").containsEntry("team_id", null);
        Map<String, Object> port = snap.stale().stream().filter(m -> m.get("monitor_id").equals(8L)).findFirst().orElseThrow();
        assertThat(port).containsEntry("type", "PORT").containsEntry("target", "db.example.com:5432").containsEntry("domain", "db.example.com");
    }

    // ── Susturulmuş ve bakımda (2026-09-23) ─────────────────────────────────────────────────

    @Test
    @DisplayName("duraklatılmış: 'ne zamandır' değişiklik geçmişindeki active değişiminden (kesin), yoksa son güncellemeden (yaklaşık), o da yoksa bilinmiyor (sonda); en eski üstte; öksüz envanter-türevi Port sayılmaz")
    void paused_sinceFromChangeLogThenUpdatedAt() {
        HttpMonitor exact = http(6, "exact", false, 60, at(20 * 1440)); exact.setUpdatedAt(at(60));         // adı dün değişmiş olabilir — kesin kaynak geçmiş
        HttpMonitor approx = http(7, "approx", false, 60, at(20 * 1440)); approx.setUpdatedAt(at(10 * 1440));
        HttpMonitor unknown = http(9, "unknown", false, 60, null);
        HttpMonitor running = http(10, "running", true, 60, at(20 * 1440));
        when(httpRepo.findAll()).thenReturn(List.of(exact, approx, unknown, running));
        PortMonitor orphan = new PortMonitor(); orphan.setId(11L); orphan.setName("gone"); orphan.setHost("gone.example.com"); orphan.setPort(443);
        orphan.setActive(false); orphan.setStandalone(false);
        when(portRepo.findAll()).thenReturn(List.of(orphan));
        when(changeLogRepo.lastActiveChange(any())).thenReturn(List.<Object[]>of(new Object[]{"HTTP", 6L, at(3 * 1440)}, new Object[]{"PING", 7L, at(1)}));

        List<Map<String, Object>> p = svc.snapshot(NOW).paused();

        assertThat(p).extracting(m -> m.get("monitor_id")).containsExactly(7L, 6L, 9L);
        assertThat(p.get(0)).containsEntry("kind", "PAUSED").containsEntry("paused_days", 10L).containsEntry("paused_since_exact", false);
        assertThat(p.get(1)).containsEntry("paused_since", at(3 * 1440)).containsEntry("paused_days", 3L).containsEntry("paused_since_exact", true);
        assertThat(p.get(2)).containsEntry("paused_since", null).containsEntry("paused_days", null);
        // PING:7 satırı HTTP:7'ye karışmaz (anahtar tür+id)
        assertThat(p.get(0).get("paused_since")).isEqualTo(at(10 * 1440));
    }

    private static MaintenanceWindow mw(long id, String name, String startAt, int durMin, Long team, boolean all) {
        MaintenanceWindow w = new MaintenanceWindow(); w.setId(id); w.setName(name); w.setStartAt(startAt); w.setDurationMinutes(durMin);
        w.setRecurrence("NONE"); w.setTimezone("Europe/Istanbul"); w.setActive(true); w.setTeamId(team); w.setAllMonitors(all);
        w.setTargetsJson("[{\"type\":\"HTTP\",\"target\":\"a.example.com\"},{\"type\":\"PING\",\"target\":\"10.0.0.1\"}]");
        return w;
    }

    @Test
    @DisplayName("bakım: şu an süren (bitişiyle) ve 24 saat içinde başlayacak pencereler girer, süren üstte; 24 saatten ileri ve bitmiş olan girmez; tüm-monitör/takımsız pencere herkese açık")
    void maintenance_activeAndSoon() {
        when(maintenanceRepo.findByActiveTrue()).thenReturn(List.of(
                mw(1, "yakında", at(-300), 60, 2L, false),        // 5 saat sonra başlar
                mw(2, "şimdi", at(30), 60, null, true),           // 30 dk önce başladı, 30 dk sonra biter
                mw(3, "uzak", at(-30 * 60), 60, 1L, false),       // 30 saat sonra → girmez
                mw(4, "bitti", at(180), 60, 1L, false)));         // 2 saat önce bitti → girmez

        List<Map<String, Object>> m = svc.snapshot(NOW).maintenance();

        assertThat(m).extracting(r -> r.get("window_id")).containsExactly(2L, 1L);
        assertThat(m.get(0)).containsEntry("kind", "MAINT_ACTIVE").containsEntry("until", at(-30)).containsEntry("public", true)
                .containsEntry("target_count", -1);
        assertThat(m.get(1)).containsEntry("kind", "MAINT_SOON").containsEntry("next_start", at(-300)).containsEntry("public", false)
                .containsEntry("team_id", 2L).containsEntry("target_count", 2).containsEntry("duration_min", 60);
    }

    @Test
    @DisplayName("istisna: 7 gün içinde (bugün dâhil) dolacaklar girer, en yakın üstte; dolmuş, 7 günden uzak ve süresiz olan girmez; takım envanterden")
    void exceptions_expiringSoon() {
        LocalDate today = NOW.atZone(ZoneId.of("Europe/Istanbul")).toLocalDate();
        CertificateInventory inv = new CertificateInventory(); inv.setDomain("b.example.com"); inv.setTeamId(4L); inv.setActive(true);
        when(inventoryRepo.findByActiveTrueOrderByDomainAsc()).thenReturn(List.of(inv));
        WeakAlgorithmException in3 = new WeakAlgorithmException(); in3.setDomain("b.example.com"); in3.setUntil(today.plusDays(3).toString()); in3.setReason("Tedarikçi yenileyecek");
        WeakAlgorithmException todayEx = new WeakAlgorithmException(); todayEx.setDomain("t.example.com"); todayEx.setUntil(today.toString());
        WeakAlgorithmException gone = new WeakAlgorithmException(); gone.setDomain("g.example.com"); gone.setUntil(today.minusDays(1).toString());
        WeakAlgorithmException far = new WeakAlgorithmException(); far.setDomain("f.example.com"); far.setUntil(today.plusDays(8).toString());
        WeakAlgorithmException forever = new WeakAlgorithmException(); forever.setDomain("e.example.com");
        when(exceptionRepo.findAll()).thenReturn(List.of(in3, todayEx, gone, far, forever));

        List<Map<String, Object>> ex = svc.snapshot(NOW).exceptions();

        assertThat(ex).extracting(r -> r.get("domain")).containsExactly("t.example.com", "b.example.com");
        assertThat(ex.get(0)).containsEntry("days_left", 0L).containsEntry("team_id", null);
        assertThat(ex.get(1)).containsEntry("days_left", 3L).containsEntry("team_id", 4L).containsEntry("reason", "Tedarikçi yenileyecek");
    }

    @Test
    @DisplayName("son 24 saat: açılan ve çözülen alarmlar (çözülmemiş satır 'çözülen' sayılmaz) + parmak izi son 24 saatte değişen sertifikalar")
    void recent_openedResolvedRenewed() {
        AlertEvent o1 = new AlertEvent(); o1.setId(1L); o1.setTeamId(1L); o1.setDomain("a.example.com"); o1.setAlertLevel("CRITICAL");
        AlertEvent r1 = new AlertEvent(); r1.setId(2L); r1.setTeamId(2L); r1.setResolved(true);
        AlertEvent reopened = new AlertEvent(); reopened.setId(3L); reopened.setResolved(false);   // çözülmüş-sonra-yeniden-açılmış
        when(alertEventRepo.findByCreatedAtGreaterThanEqualOrderByCreatedAtDesc(ISO.format(NOW.minus(Duration.ofHours(24))))).thenReturn(List.of(o1));
        when(alertEventRepo.findByResolvedAtGreaterThanEqual(ISO.format(NOW.minus(Duration.ofHours(24))))).thenReturn(List.of(r1, reopened));
        LatestCheck fresh = new LatestCheck(); fresh.setDomain("new.example.com"); fresh.setFingerprintChangedAt(at(90));
        LatestCheck old = new LatestCheck(); old.setDomain("old.example.com"); old.setFingerprintChangedAt(at(26 * 60));
        LatestCheck none = new LatestCheck(); none.setDomain("none.example.com");
        when(latestCheckRepo.findAll()).thenReturn(List.of(fresh, old, none));

        Map<String, List<Map<String, Object>>> r = svc.snapshot(NOW).recent();

        assertThat(r.get("opened")).extracting(m -> m.get("id")).containsExactly(1L);
        assertThat(r.get("opened").get(0)).containsEntry("team_id", 1L).containsEntry("domain", "a.example.com");
        assertThat(r.get("resolved")).extracting(m -> m.get("id")).containsExactly(2L);
        assertThat(r.get("renewed")).extracting(m -> m.get("domain")).containsExactly("new.example.com");
    }
}
