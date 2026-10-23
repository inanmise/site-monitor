package com.sitemonitor.service;

import com.sitemonitor.model.AlertEvent;
import com.sitemonitor.model.CertificateInventory;
import com.sitemonitor.model.PortMonitor;
import com.sitemonitor.model.SmtpSettings;
import com.sitemonitor.repository.AlertEventRepository;
import com.sitemonitor.repository.AlertEventSilentCloseRepository;
import com.sitemonitor.repository.AlertThresholdRepository;
import com.sitemonitor.repository.CertificateInventoryRepository;
import com.sitemonitor.repository.DnsMonitorRepository;
import com.sitemonitor.repository.DnsRecordRepository;
import com.sitemonitor.repository.DomainCheckRepository;
import com.sitemonitor.repository.DomainMonitorRepository;
import com.sitemonitor.repository.EscalationContactRepository;
import com.sitemonitor.repository.LatestCheckRepository;
import com.sitemonitor.repository.NotificationLogRepository;
import com.sitemonitor.repository.PageCheckRepository;
import com.sitemonitor.repository.PortMonitorRepository;
import com.sitemonitor.repository.TeamRepository;
import com.sitemonitor.it.MultiPodTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.AdditionalAnswers;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import javax.sql.DataSource;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Çok pod R2b · F (2026-10-23) — alarm satırının BAYAT bütün-varlık kaydı kapanmış alarmı YENİDEN AÇMAZ ("hayalet alarm").
 * Her yol GERÇEK H2 üstünde: okuma ile kayıt arasında (çoğu zaman SMTP gönderimi — burada kişi push tetiği sahtesinin içinde)
 * başka bir yol / pod alarmı kapatır ya da onaylar; yolun kaydı ardından yalnız KENDİ değiştirdiği kolonları yazar
 * ({@link AlertEventFieldWriter}) — alarm kapalı / onaylı kalır, yolun kararları (gönderim, sayaç, damga) aynıdır.
 *
 * <p>Kapsanan yollar: onay; sertifika ilk bildirimi, damgasız olayın takım damgası, terfi, günlük hatırlatma ve yarım ilk
 * bildirim ({@code saveUnlessClosedMeanwhile}); izleme ilk bildirimi (bireysel + fırtınaya bağlı), terfi, yarım ilk
 * bildirim, günlük hatırlatma; elle yeniden bildirim; çözüm bildirimindeki takım damgası; türev Port/DNS geçişi; yazıcının
 * takım taşıma ve anahtar taşıma ifadeleri. {@code NOT_SUPPORTED}: test işlemi yarışı gizlerdi.
 */
@DataJpaTest
@Import(AlertEventFieldWriter.class)
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AlertEventStaleWriteMultiPodTest {

    private static final DateTimeFormatter ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);
    private static final String PORT = EscalationService.TYPE_PORT_DOWN;
    private static final String CTX = "{\"monitor_id\":1,\"team_id\":7,\"standalone\":true}";

    @Autowired DataSource dataSource;
    @Autowired AlertEventRepository realRepo;
    @Autowired AlertEventSilentCloseRepository closeRepo;
    @Autowired CertificateInventoryRepository inventoryRepo;
    @Autowired AlertEventFieldWriter writer;

    JdbcTemplate jdbc;
    UserPushService push;
    StormService storm;
    MaintenanceService maintenance;
    EscalationService selfMock;
    boolean withWriter = true;

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute(MultiPodTestSupport.SCHEDULER_LOCK_DDL);
        jdbc.update("DELETE FROM scheduler_lock");
        realRepo.deleteAll();
        inventoryRepo.deleteAll();
        push = mock(UserPushService.class);
        storm = mock(StormService.class);
        maintenance = mock(MaintenanceService.class);
        selfMock = mock(EscalationService.class);
    }

    @AfterEach
    void tearDown() {
        realRepo.deleteAll();
        inventoryRepo.deleteAll();
        jdbc.update("DELETE FROM scheduler_lock");
    }

    // ── kurulum ─────────────────────────────────────────────────────────────────────────────────────────────

    private AlertEventRepository delegating() {
        return mock(AlertEventRepository.class, AdditionalAnswers.delegatesTo(realRepo));
    }

    @SuppressWarnings("unchecked")
    private EscalationService pod(AlertEventRepository repo) {
        AppSettingsService settings = mock(AppSettingsService.class);
        when(settings.getBoolean(anyString(), anyBoolean())).thenAnswer(i -> i.getArgument(1));
        when(settings.getInt(anyString(), org.mockito.ArgumentMatchers.anyInt())).thenAnswer(i -> i.getArgument(1));
        SmtpSettingsService smtp = mock(SmtpSettingsService.class);
        SmtpSettings s = new SmtpSettings();
        s.setInterDomainDelayMs(0);
        when(smtp.getOrDefaults()).thenReturn(s);
        EscalationService svc = new EscalationService(repo, mock(AlertThresholdRepository.class),
                mock(EscalationContactRepository.class), inventoryRepo, mock(EmailNotificationService.class),
                mock(WeeklyAvailabilityReportService.class), mock(WebhookService.class), new ObjectMapper(),
                mock(NotificationLogRepository.class), mock(LatestCheckRepository.class), mock(TeamRepository.class),
                smtp, maintenance, storm, push, mock(DomainMonitorRepository.class),
                mock(DomainCheckRepository.class), mock(DnsRecordRepository.class), mock(PageCheckRepository.class),
                mock(NotificationGroupService.class), settings);
        ReflectionTestUtils.setField(svc, "self", selfMock);
        svc.setJdbcTemplate(jdbc);
        ObjectProvider<UserPushService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(push);
        svc.setSilentAlarmCloser(new SilentAlarmCloser(closeRepo, realRepo, inventoryRepo, provider));
        if (withWriter) svc.setFieldWriter(writer);
        return svc;
    }

    private static String ago(long amount, ChronoUnit unit) {
        return ISO.format(Instant.now().minus(amount, unit));
    }

    private AlertEvent open(String domain, String type, String level, String ctx, String createdAt, String lastReAlertAt) {
        AlertEvent e = new AlertEvent();
        e.setDomain(domain);
        e.setAlertType(type);
        e.setAlertLevel(level);
        e.setMessage("eski");
        e.setTeamId(7L);
        e.setResolved(false);
        e.setAcknowledged(false);
        e.setCreatedAt(createdAt);
        e.setLastReAlertAt(lastReAlertAt);
        e.setRealertCount(0);
        e.setContextJson(ctx);
        return realRepo.save(e);
    }

    private void inventory(String domain) {
        CertificateInventory inv = new CertificateInventory();
        inv.setDomain(domain);
        inv.setPort(443);
        inv.setActive(true);
        inv.setTeamId(7L);
        inventoryRepo.save(inv);
    }

    private static Map<String, Object> expiryResult(String domain, int days) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("domain", domain);
        m.put("status", "warning");
        m.put("warning", true);
        m.put("days_remaining", days);
        m.put("revocation_status", "VALID");
        m.put("chain_status", "VALID");
        m.put("deployment_status", "OK");
        return m;
    }

    private static Map<String, Object> monitorCtx() {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("monitor_id", 1);
        c.put("team_id", 7);
        c.put("standalone", true);
        return c;
    }

    private AlertEvent row(Long id) {
        return realRepo.findById(id).orElseThrow();
    }

    private Long onlyId(String domain) {
        List<AlertEvent> all = realRepo.findAll().stream().filter(e -> domain.equals(e.getDomain())).toList();
        assertThat(all).hasSize(1);
        return all.get(0).getId();
    }

    private void closeElsewhere(Long id) {
        jdbc.update("UPDATE alert_events SET resolved = TRUE, resolved_by = 'kullanici', resolved_at = ? WHERE id = ?",
                ISO.format(Instant.now()), id);
    }

    private void closeElsewhere(String domain) {
        jdbc.update("UPDATE alert_events SET resolved = TRUE, resolved_by = 'kullanici', resolved_at = ? WHERE domain = ?",
                ISO.format(Instant.now()), domain);
    }

    private void ackElsewhere(String domain) {
        jdbc.update("UPDATE alert_events SET acknowledged = TRUE, acknowledged_by = 'ops', acknowledged_at = ? WHERE domain = ?",
                ISO.format(Instant.now()), domain);
    }

    /** Kişi push tetiği (gönderim penceresinin içi) çalışırken {@code during} koşar — eşzamanlı kapanış / onay. */
    private void duringSend(String trigger, Runnable during) {
        doAnswer(i -> {
            during.run();
            return null;
        }).when(push).enqueueAlert(any(), eq(trigger), any(), any(), any());
    }

    private long pushes(String trigger) {
        return mockingDetails(push).getInvocations().stream()
                .filter(i -> i.getMethod().getName().equals("enqueueAlert") && trigger.equals(i.getArgument(1))).count();
    }

    private static void assertClosedBy(AlertEvent r, String by) {
        assertThat(r.getResolved()).as("kapanmış alarm yeniden açılmadı").isTrue();
        assertThat(r.getResolvedBy()).isEqualTo(by);
    }

    // ── onay ────────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Onay: okuma ile yazım arasında kapanan alarm KAPALI kalır (onay yazılmaz), yanıt taze satır")
    void acknowledge_closedBetweenReadAndWrite_staysClosed() {
        AlertEvent a = open("ack.example.com", PORT, "HIGH", CTX, ago(1, ChronoUnit.HOURS), ago(1, ChronoUnit.HOURS));
        AlertEventRepository repo = delegating();
        doAnswer(i -> {
            Optional<AlertEvent> r = realRepo.findById(i.getArgument(0));
            closeElsewhere(a.getId());
            return r;
        }).doAnswer(i -> realRepo.findById(i.getArgument(0))).when(repo).findById(a.getId());

        AlertEvent out = pod(repo).acknowledge(a.getId(), "Ayşe Yılmaz", "bakıyoruz");

        AlertEvent r = row(a.getId());
        assertClosedBy(r, "kullanici");
        assertThat(r.getAcknowledged()).as("kapanmış alarma onay yazılmaz").isFalse();
        assertThat(out.getResolved()).as("yanıt taze satır").isTrue();
    }

    @Test
    @DisplayName("Onay: yalnız onay kolonları — okumadan sonra yazılan yeniden uyarı damgası korunur; not yazılır, yanıt onaylı kopya")
    void acknowledge_writesOnlyAckColumns() {
        AlertEvent a = open("ack2.example.com", PORT, "HIGH", CTX, ago(1, ChronoUnit.HOURS), ago(1, ChronoUnit.HOURS));
        String stamp = ISO.format(Instant.now());
        AlertEventRepository repo = delegating();
        doAnswer(i -> {
            Optional<AlertEvent> r = realRepo.findById(i.getArgument(0));
            jdbc.update("UPDATE alert_events SET last_re_alert_at = ? WHERE id = ?", stamp, a.getId());
            return r;
        }).when(repo).findById(a.getId());

        AlertEvent out = pod(repo).acknowledge(a.getId(), "Ayşe Yılmaz", "  bakıyoruz ");

        AlertEvent r = row(a.getId());
        assertThat(r.getAcknowledged()).isTrue();
        assertThat(r.getAcknowledgedBy()).isEqualTo("Ayşe Yılmaz");
        assertThat(r.getAcknowledgedNote()).isEqualTo("bakıyoruz");
        assertThat(r.getLastReAlertAt()).isEqualTo(stamp);
        assertThat(r.getResolved()).isFalse();
        assertThat(out.getAcknowledged()).isTrue();
        assertThat(out.getAcknowledgedAt()).isEqualTo(r.getAcknowledgedAt());
    }

    // ── sertifika yolu ───────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Sertifika ilk bildirimi: gönderim sırasında kapanan alarm, gönderim sonrası kayıtla yeniden AÇILMAZ; bildirim damgası yazılır")
    void certInitial_closedDuringSend_staysClosed() {
        String domain = "cert-init.example.com";
        inventory(domain);
        duringSend("INITIAL", () -> closeElsewhere(domain));

        pod(delegating()).processResults(List.of(expiryResult(domain, 25)));

        AlertEvent r = row(onlyId(domain));
        assertClosedBy(r, "kullanici");
        assertThat(r.getLastReAlertAt()).as("gönderim damgası yazıldı").isNotNull();
        assertThat(pushes("INITIAL")).isEqualTo(1);
    }

    @Test
    @DisplayName("Sertifika ilk bildirimi: gönderim sırasında gelen onay korunur")
    void certInitial_ackDuringSend_staysAcknowledged() {
        String domain = "cert-init-ack.example.com";
        inventory(domain);
        duringSend("INITIAL", () -> ackElsewhere(domain));

        pod(delegating()).processResults(List.of(expiryResult(domain, 25)));

        AlertEvent r = row(onlyId(domain));
        assertThat(r.getAcknowledged()).isTrue();
        assertThat(r.getAcknowledgedBy()).isEqualTo("ops");
        assertThat(r.getResolved()).isFalse();
    }

    @Test
    @DisplayName("Sertifika damgasız olayın takım damgası: tur başı okumadan sonra kapanan alarm yeniden AÇILMAZ")
    void certTeamStamp_closedAfterBatchRead_staysClosed() {
        String domain = "cert-stamp.example.com";
        inventory(domain);
        AlertEvent a = open(domain, "EXPIRY", "WARNING", null, ago(2, ChronoUnit.DAYS), ago(1, ChronoUnit.HOURS));
        jdbc.update("UPDATE alert_events SET team_id = NULL WHERE id = ?", a.getId());
        AlertEventRepository repo = delegating();
        doAnswer(i -> {
            List<AlertEvent> r = realRepo.findOpenByDomainIn(i.getArgument(0));
            closeElsewhere(a.getId());
            return r;
        }).when(repo).findOpenByDomainIn(any());

        pod(repo).processResults(List.of(expiryResult(domain, 25)));

        AlertEvent r = row(a.getId());
        assertClosedBy(r, "kullanici");
        assertThat(r.getTeamId()).as("damga yazıldı").isEqualTo(7L);
    }

    @Test
    @DisplayName("Sertifika terfisi: terfi bildirimi gönderilirken kapanan alarm, terfi kaydıyla yeniden AÇILMAZ; ESCALATION bir kez")
    void certEscalation_closedDuringSend_staysClosed() {
        String domain = "cert-esc.example.com";
        inventory(domain);
        AlertEvent a = open(domain, "EXPIRY", "WARNING", null, ago(2, ChronoUnit.DAYS), ago(1, ChronoUnit.HOURS));
        // terfi kaydı gönderimden ÖNCE: kapanış bu kayıttan önce olur (alıcı sorgusu sırasında) — kayıt yine açmamalı
        AtomicBoolean armed = new AtomicBoolean(true);
        EscalationService p = pod(delegating());
        EscalationContactRepository contacts = (EscalationContactRepository) ReflectionTestUtils.getField(p, "contactRepo");
        org.mockito.stubbing.Answer<Object> closeOnce = i -> {
            if (armed.getAndSet(false)) closeElsewhere(a.getId());
            return List.of();
        };
        when(contacts.findByTeamIdAndActiveTrueOrderByRoleAsc(any())).thenAnswer(closeOnce);
        when(contacts.findByTeamIdAndMinAlertLevelInAndActiveTrue(any(), any())).thenAnswer(closeOnce);
        when(contacts.findByTeamIdAndMinAlertLevelAndActiveTrue(any(), any())).thenAnswer(closeOnce);

        p.processResults(List.of(expiryResult(domain, 3)));

        AlertEvent r = row(a.getId());
        assertClosedBy(r, "kullanici");
        assertThat(r.getAlertLevel()).as("terfi kolonları yazıldı").isNotEqualTo("WARNING");
        assertThat(pushes("ESCALATION")).isEqualTo(1);
    }

    @Test
    @DisplayName("Sertifika günlük hatırlatması: taze okumadan SONRA kapanan alarm, hatırlatma kaydıyla yeniden AÇILMAZ; sayaç bir kez")
    void certDailyRealert_closedAfterFreshRead_staysClosed() {
        String domain = "cert-realert.example.com";
        inventory(domain);
        AlertEvent a = open(domain, "EXPIRY", "WARNING", null, ago(3, ChronoUnit.DAYS), ago(25, ChronoUnit.HOURS));
        AtomicBoolean armed = new AtomicBoolean(false);
        duringSend("DAILY_REALERT", () -> armed.set(true));
        AlertEventRepository repo = delegating();
        doAnswer(i -> {
            Optional<AlertEvent> r = realRepo.findById(i.getArgument(0));
            if (armed.getAndSet(false)) closeElsewhere(a.getId());   // saveUnlessClosedMeanwhile'ın taze okumasından sonra
            return r;
        }).when(repo).findById(a.getId());

        pod(repo).processResults(List.of(expiryResult(domain, 25)));

        AlertEvent r = row(a.getId());
        assertClosedBy(r, "kullanici");
        assertThat(r.getRealertCount()).isEqualTo(1);
        assertThat(pushes("DAILY_REALERT")).isEqualTo(1);
    }

    @Test
    @DisplayName("Sertifika yarım ilk bildirimi (damgasız, 10 dk): taze okumadan SONRA kapanan alarm yeniden AÇILMAZ")
    void certInitialMissing_closedAfterFreshRead_staysClosed() {
        String domain = "cert-missing.example.com";
        inventory(domain);
        AlertEvent a = open(domain, "EXPIRY", "WARNING", null, ago(10, ChronoUnit.MINUTES), null);
        AtomicBoolean armed = new AtomicBoolean(false);
        duringSend("INITIAL", () -> armed.set(true));
        AlertEventRepository repo = delegating();
        doAnswer(i -> {
            Optional<AlertEvent> r = realRepo.findById(i.getArgument(0));
            if (armed.getAndSet(false)) closeElsewhere(a.getId());
            return r;
        }).when(repo).findById(a.getId());

        pod(repo).processResults(List.of(expiryResult(domain, 25)));

        AlertEvent r = row(a.getId());
        assertClosedBy(r, "kullanici");
        assertThat(r.getLastReAlertAt()).isNotNull();
        assertThat(pushes("INITIAL")).isEqualTo(1);
    }

    // ── izleme yolu ─────────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("İzleme ilk bildirimi (bireysel): gönderim sırasında kapanan alarm yeniden AÇILMAZ; onay da korunur")
    void monitoringInitial_closedOrAckedDuringSend() {
        duringSend("INITIAL", () -> closeElsewhere("mon-init.example.com"));
        pod(delegating()).processConfirmedOutage("mon-init.example.com", PORT, "CRITICAL", monitorCtx());
        assertClosedBy(row(onlyId("mon-init.example.com")), "kullanici");

        duringSend("INITIAL", () -> ackElsewhere("mon-init-ack.example.com"));
        pod(delegating()).processConfirmedOutage("mon-init-ack.example.com", PORT, "CRITICAL", monitorCtx());
        AlertEvent r = row(onlyId("mon-init-ack.example.com"));
        assertThat(r.getAcknowledged()).isTrue();
        assertThat(r.getNotifiedContacts()).isEqualTo("[]");
        assertThat(r.getLastReAlertAt()).isNotNull();
    }

    @Test
    @DisplayName("İzleme ilk bildirimi (fırtınaya bağlandı): değerlendirme sırasında kapanan alarm yeniden AÇILMAZ; fırtına bağı yazılır")
    void monitoringInitial_stormSuppressed_closedMeanwhile_staysClosed() {
        String domain = "mon-storm.example.com";
        when(storm.evaluate(any(), any())).thenAnswer(i -> {
            AlertEvent e = i.getArgument(0);
            closeElsewhere(e.getId());
            e.setStormId(5L);
            return StormService.StormAction.SUPPRESSED;
        });

        pod(delegating()).processConfirmedOutage(domain, PORT, "CRITICAL", monitorCtx());

        AlertEvent r = row(onlyId(domain));
        assertClosedBy(r, "kullanici");
        assertThat(r.getStormId()).isEqualTo(5L);
        assertThat(r.getLastReAlertAt()).isNotNull();
    }

    @Test
    @DisplayName("İzleme terfisi: okumadan sonra gelen onay terfi kaydıyla DÜŞMEZ (okunan onaysızdı); gönderim sırasında kapanan alarm yeniden AÇILMAZ")
    void monitoringEscalation_ackKept_andClosedDuringSendStaysClosed() {
        String domain = "mon-esc.example.com";
        AlertEvent a = open(domain, PORT, "HIGH", CTX, ago(1, ChronoUnit.DAYS), ago(2, ChronoUnit.HOURS));
        AlertEventRepository repo = delegating();
        doAnswer(i -> {
            Optional<AlertEvent> r = realRepo.findOpenAlert(i.getArgument(0), i.getArgument(1));
            ackElsewhere(domain);
            return r;
        }).when(repo).findOpenAlert(eq(domain), eq(PORT));
        duringSend("ESCALATION", () -> closeElsewhere(a.getId()));

        pod(repo).processConfirmedOutage(domain, PORT, "CRITICAL", monitorCtx());

        AlertEvent r = row(a.getId());
        assertClosedBy(r, "kullanici");
        assertThat(r.getAcknowledged()).as("okumadan sonra gelen onay korunur").isTrue();
        assertThat(r.getAlertLevel()).isEqualTo("CRITICAL");
        assertThat(pushes("ESCALATION")).isEqualTo(1);
    }

    @Test
    @DisplayName("İzleme yarım ilk bildirimi ve günlük hatırlatması: gönderim sırasında kapanan alarm yeniden AÇILMAZ")
    void monitoringInitialMissingAndDailyRealert_closedDuringSend() {
        String d1 = "mon-missing.example.com";
        AlertEvent m = open(d1, PORT, "CRITICAL", CTX, ago(10, ChronoUnit.MINUTES), null);
        duringSend("INITIAL", () -> closeElsewhere(m.getId()));
        pod(delegating()).processConfirmedOutage(d1, PORT, "CRITICAL", monitorCtx());
        assertClosedBy(row(m.getId()), "kullanici");
        assertThat(pushes("INITIAL")).isEqualTo(1);

        String d2 = "mon-realert.example.com";
        AlertEvent d = open(d2, PORT, "CRITICAL", CTX, ago(3, ChronoUnit.DAYS), ago(25, ChronoUnit.HOURS));
        duringSend("DAILY_REALERT", () -> closeElsewhere(d.getId()));
        pod(delegating()).processConfirmedOutage(d2, PORT, "CRITICAL", monitorCtx());
        AlertEvent r = row(d.getId());
        assertClosedBy(r, "kullanici");
        assertThat(r.getRealertCount()).isEqualTo(1);
        assertThat(pushes("DAILY_REALERT")).isEqualTo(1);
    }

    // ── elle yeniden bildirim, çözüm damgası ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Elle yeniden bildirim: okuma ile kayıt arasında kapanan alarm yeniden AÇILMAZ")
    void reNotify_closedBetweenReadAndWrite_staysClosed() {
        AlertEvent a = open("renotify.example.com", PORT, "HIGH", CTX, ago(1, ChronoUnit.DAYS), ago(2, ChronoUnit.HOURS));
        AlertEventRepository repo = delegating();
        doAnswer(i -> {
            Optional<AlertEvent> r = realRepo.findById(i.getArgument(0));
            closeElsewhere(a.getId());
            return r;
        }).doAnswer(i -> realRepo.findById(i.getArgument(0))).when(repo).findById(a.getId());

        pod(repo).reNotify(a.getId());

        AlertEvent r = row(a.getId());
        assertClosedBy(r, "kullanici");
        assertThat(r.getNotifiedContacts()).isEqualTo("[]");
    }

    @Test
    @DisplayName("Çözüm bildirimi takım damgası: yalnız team_id — okumadan sonra çözülen fırtına bağı geri yazılmaz")
    void resolutionTeamStamp_writesOnlyTeam() {
        String domain = "res-stamp.example.com";
        inventory(domain);
        AlertEvent a = open(domain, "EXPIRY", "WARNING", null, ago(2, ChronoUnit.DAYS), ago(1, ChronoUnit.HOURS));
        jdbc.update("UPDATE alert_events SET team_id = NULL, resolved = TRUE, resolved_by = 'system', resolved_at = ?, "
                + "storm_id = 5 WHERE id = ?", ISO.format(Instant.now()), a.getId());
        AlertEvent stale = row(a.getId());
        jdbc.update("UPDATE alert_events SET storm_id = NULL WHERE id = ?", a.getId());   // fırtına kapanışı bağı çözdü

        pod(delegating()).sendResolutionNotificationAsync(stale, "Sistem (otomatik)", "RESOLUTION");

        AlertEvent r = row(a.getId());
        assertThat(r.getTeamId()).isEqualTo(7L);
        assertThat(r.getStormId()).as("bayat fırtına bağı geri yazılmadı").isNull();
        assertClosedBy(r, "system");
    }

    // ── yazıcı: takım taşıma, anahtar taşıma, yasak kolonlar; türev geçiş ─────────────────────────────────

    @Test
    @DisplayName("Takım taşıma: yalnız AÇIK alarmların team_id'si — kapanmış olayın damgası ve açık alarmın onayı korunur")
    void moveOpenAlertsToTeam_onlyOpenRows() {
        AlertEvent open = open("move-a.example.com", PORT, "HIGH", CTX, ago(1, ChronoUnit.DAYS), null);
        AlertEvent closed = open("move-b.example.com", PORT, "HIGH", CTX, ago(1, ChronoUnit.DAYS), null);
        AlertEvent other = open("move-c.example.com", PORT, "HIGH", CTX, ago(1, ChronoUnit.DAYS), null);
        jdbc.update("UPDATE alert_events SET team_id = 8 WHERE id IN (?, ?)", open.getId(), closed.getId());
        closeElsewhere(closed.getId());
        ackElsewhere("move-a.example.com");

        assertThat(writer.moveOpenAlertsToTeam(8L, 9L)).isEqualTo(1);

        assertThat(row(open.getId()).getTeamId()).isEqualTo(9L);
        assertThat(row(open.getId()).getAcknowledged()).isTrue();
        assertThat(row(closed.getId()).getTeamId()).isEqualTo(8L);
        assertClosedBy(row(closed.getId()), "kullanici");
        assertThat(row(other.getId()).getTeamId()).isEqualTo(7L);
    }

    @Test
    @DisplayName("Anahtar taşıma (yalnız açık): kapanmış alarm taşınmaz ve yeniden açılmaz; açık alarmın onayı korunur")
    void writeValuesOnlyOpen_rekey() {
        AlertEvent open = open("eski-ad", EscalationService.TYPE_SCRIPTED_FAIL, "HIGH", CTX, ago(1, ChronoUnit.DAYS), null);
        AlertEvent closed = open("eski-ad", EscalationService.TYPE_SCRIPTED_SLOW, "HIGH", CTX, ago(1, ChronoUnit.DAYS), null);
        closeElsewhere(closed.getId());
        jdbc.update("UPDATE alert_events SET acknowledged = TRUE WHERE id = ?", open.getId());

        assertThat(writer.writeValues(open.getId(), Map.of("domain", "yeni-ad"), true)).isEqualTo(1);
        assertThat(writer.writeValues(closed.getId(), Map.of("domain", "yeni-ad"), true)).isZero();

        assertThat(row(open.getId()).getDomain()).isEqualTo("yeni-ad");
        assertThat(row(open.getId()).getAcknowledged()).isTrue();
        assertThat(row(closed.getId()).getDomain()).isEqualTo("eski-ad");
        assertClosedBy(row(closed.getId()), "kullanici");
    }

    @Test
    @DisplayName("Yazıcı kapanış / salt okunur kolonları ASLA yazmaz — programlama hatası")
    void writerRejectsCloseColumns() {
        AlertEvent a = open("guard.example.com", PORT, "HIGH", CTX, ago(1, ChronoUnit.DAYS), null);
        for (String p : List.of("resolved", "resolvedAt", "resolvedBy", "resolvedNote", "resolvedSilently", "id",
                "oncallEmails", "resolutionNotify", "resolutionNotifyAt", "bogus")) {
            assertThatThrownBy(() -> writer.write(a, false, p)).as(p).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> writer.writeValues(a.getId(), java.util.Collections.singletonMap(p, null), false))
                    .as(p).isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(row(a.getId()).getResolved()).isFalse();
    }

    @Test
    @DisplayName("Türev Port/DNS geçişi: okumadan sonra kapanan alarm yeniden AÇILMAZ ve sayılmaz; açık olanın yalnız bağlamı / takımı yazılır")
    void derivedRouting_closedAfterRead_staysClosed() {
        String d1 = "derived-a.example.com", d2 = "derived-b.example.com";
        inventory(d1);
        inventory(d2);
        jdbc.update("UPDATE certificate_inventory SET team_id = 5");
        AlertEvent closedLater = open(d1, PORT, "HIGH", "{\"port\":443,\"monitor_id\":1,\"team_id\":4}",
                ago(1, ChronoUnit.DAYS), null);
        AlertEvent stays = open(d2, PORT, "HIGH", "{\"port\":443,\"monitor_id\":1,\"team_id\":4}", ago(1, ChronoUnit.DAYS), null);
        jdbc.update("UPDATE alert_events SET team_id = 4");
        AlertEventRepository repo = delegating();
        doAnswer(i -> {
            List<AlertEvent> r = realRepo.findAllOpenOrderBySeverity();
            closeElsewhere(closedLater.getId());
            ackElsewhere(d2);
            return r;
        }).when(repo).findAllOpenOrderBySeverity();
        PortMonitorRepository ports = mock(PortMonitorRepository.class);
        PortMonitor derived = new PortMonitor();
        derived.setId(1L);
        derived.setStandalone(false);
        when(ports.findById(1L)).thenReturn(Optional.of(derived));
        DerivedMonitorAlertRouting routing = new DerivedMonitorAlertRouting(repo, ports, mock(DnsMonitorRepository.class),
                inventoryRepo, new ObjectMapper());
        if (withWriter) routing.setFieldWriter(writer);

        assertThat(routing.migrateOpenDerivedAlerts()).as("kapanan sayılmaz").isEqualTo(1);

        assertClosedBy(row(closedLater.getId()), "kullanici");
        AlertEvent s = row(stays.getId());
        assertThat(s.getTeamId()).isEqualTo(5L);
        assertThat(s.getContextJson()).doesNotContain("team_id");
        assertThat(s.getAcknowledged()).as("okumadan sonra gelen onay korunur").isTrue();
        verify(repo, never()).save(any());
    }
}
