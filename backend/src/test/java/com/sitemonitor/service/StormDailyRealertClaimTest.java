package com.sitemonitor.service;

import com.sitemonitor.model.AlertEvent;
import com.sitemonitor.model.AlertStorm;
import com.sitemonitor.model.NotificationLog;
import com.sitemonitor.model.Team;
import com.sitemonitor.repository.AlertEventRepository;
import com.sitemonitor.repository.AlertStormRepository;
import com.sitemonitor.repository.CertificateInventoryRepository;
import com.sitemonitor.repository.DnsMonitorRepository;
import com.sitemonitor.repository.DomainMonitorRepository;
import com.sitemonitor.repository.EscalationContactRepository;
import com.sitemonitor.repository.HttpMonitorRepository;
import com.sitemonitor.repository.KeywordMonitorRepository;
import com.sitemonitor.repository.NotificationLogRepository;
import com.sitemonitor.repository.PingMonitorRepository;
import com.sitemonitor.repository.PortMonitorRepository;
import com.sitemonitor.repository.TeamRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Fırtına GÜNLÜK TOPLU TEKRARI gönderimden ÖNCE sahiplenilir (2026-10-09, sonsuz döngü düzeltmesi).
 *
 * <p>Damga eskiden gönderimden SONRA yazılıyordu: gönderim ile damga arasında bir istisna (damga UPDATE'i düştü) 30 sn
 * sonraki yaşam döngüsü turu için tekrarı yine "vakti gelmiş" bırakıyor, aynı toplu posta / webhook / push her turda
 * yeniden gidiyordu. Bu test veritabanını bir alanla taklit eder: her tur fırtınayı "veritabanından" TAZE okur (bellekteki
 * varlık değil) — döngü yalnız böyle görünür.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class StormDailyRealertClaimTest {

    @Mock AlertStormRepository stormRepo;
    @Mock AlertEventRepository alertEventRepo;
    @Mock AppSettingsService appSettings;
    @Mock EmailNotificationService emailService;
    @Mock WebhookService webhookService;
    @Mock TeamRepository teamRepo;
    @Mock EscalationContactRepository contactRepo;
    @Mock CertificateInventoryRepository inventoryRepo;
    @Mock JdbcTemplate jdbcTemplate;
    @Mock HttpMonitorRepository httpRepo;
    @Mock PortMonitorRepository portRepo;
    @Mock KeywordMonitorRepository keywordRepo;
    @Mock PingMonitorRepository pingRepo;
    @Mock DnsMonitorRepository dnsRepo;
    @Mock DomainMonitorRepository domainRepo;
    @Mock NotificationGroupService notificationGroups;
    @Mock NotificationLogRepository notificationLogRepo;

    private static final DateTimeFormatter ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);
    private static final long STORM_ID = 7L;

    private StormService storm;
    /** "Veritabanındaki" alert_storms.last_re_alert_at. */
    private String dbLastReAlertAt;
    private final String createdAt = ago(3 * 24 * 60);
    private final List<AlertEvent> members = new ArrayList<>();
    private final AtomicBoolean claimThrows = new AtomicBoolean();
    private final AtomicInteger claimCalls = new AtomicInteger();

    @BeforeEach
    void setUp() {
        storm = new StormService(stormRepo, alertEventRepo, appSettings, emailService, webhookService,
                teamRepo, contactRepo, inventoryRepo, jdbcTemplate,
                httpRepo, portRepo, keywordRepo, pingRepo, dnsRepo, domainRepo, notificationGroups);
        ReflectionTestUtils.setField(storm, "notificationLogRepo", notificationLogRepo);
        when(appSettings.getBoolean(anyString(), anyBoolean())).thenAnswer(i -> i.getArgument(1));
        when(appSettings.getInt(anyString(), anyInt())).thenAnswer(i -> i.getArgument(1));
        when(appSettings.getString(anyString(), anyString())).thenAnswer(i -> i.getArgument(1));
        Team t = new Team();
        t.setId(14L); t.setName("Takım A"); t.setEmail("a@example.com");
        when(teamRepo.findById(14L)).thenReturn(Optional.of(t));

        // HA kilidi alınır.
        when(jdbcTemplate.update(startsWith("DELETE FROM scheduler_lock"), any(), any())).thenReturn(1);
        when(jdbcTemplate.update(startsWith("INSERT INTO scheduler_lock"), any(), any(), any())).thenReturn(1);
        // Her tur fırtınayı veritabanından TAZE okur.
        when(stormRepo.findByResolvedFalse()).thenAnswer(i -> List.of(dbStorm()));
        for (long id = 1; id <= 3; id++) members.add(down(id));
        when(alertEventRepo.findByStormId(STORM_ID)).thenAnswer(i -> members);
        // Sahiplenme: koşullu UPDATE — damga okunan değerdeyse ileri alınır.
        when(jdbcTemplate.update(eq(StormService.SQL_REALERT_CLAIM), any(Object[].class))).thenAnswer(i -> claim(i.getArguments()));
        when(jdbcTemplate.update(eq(StormService.SQL_REALERT_CLAIM_UNSTAMPED), any(Object[].class))).thenAnswer(i -> claim(i.getArguments()));
    }

    private int claim(Object[] a) {
        claimCalls.incrementAndGet();
        if (claimThrows.get()) throw new RuntimeException("bağlantı koptu");
        Object seen = a.length > 3 ? a[3] : null;
        if (!Objects.equals(dbLastReAlertAt, seen)) return 0;
        dbLastReAlertAt = String.valueOf(a[1]);
        return 1;
    }

    private AlertStorm dbStorm() {
        AlertStorm s = new AlertStorm();
        s.setId(STORM_ID); s.setScopeKey("TEAM:14"); s.setScopeType("TEAM"); s.setTeamId(14L); s.setResolved(false);
        s.setCreatedAt(createdAt);
        s.setLastMemberAt(ago(1));   // taze — mühürlü değil
        s.setLastReAlertAt(dbLastReAlertAt);
        return s;
    }

    private static String ago(long minutes) { return ISO.format(Instant.now().minus(minutes, ChronoUnit.MINUTES)); }

    private AlertEvent down(long id) {
        AlertEvent e = new AlertEvent();
        e.setId(id); e.setDomain("host" + id + ".example.com"); e.setAlertType(EscalationService.TYPE_SCRIPTED_FAIL);
        e.setAlertLevel("WARNING"); e.setTeamId(14L); e.setResolved(false); e.setAcknowledged(false);
        e.setCreatedAt(ago(2 * 24 * 60)); e.setStormId(STORM_ID);
        return e;
    }

    /** Günlük toplu tekrar postasının üye günlüğü satırları (gönderim başına üye sayısı kadar). */
    private long realertLogRows() {
        return mockingDetails(notificationLogRepo).getInvocations().stream()
                .filter(inv -> inv.getMethod().getName().equals("save"))
                .map(inv -> (NotificationLog) inv.getArgument(0))
                .filter(n -> StormService.TRIGGER_STORM_REALERT.equals(n.getTrigger()))
                .count();
    }

    @Test
    @DisplayName("DÖNGÜ KAPISI: gönderimden sonra damga UPDATE'i düşse de sonraki turlar toplu tekrarı YENİDEN GÖNDERMEZ (sahiplenme gönderimden önce)")
    void stampFailsAfterSend_nextTicksDoNotResend() {
        dbLastReAlertAt = ago(25 * 60);   // son toplu posta 25 sa önce → tekrar vakti geldi
        doThrow(new RuntimeException("bağlantı koptu"))
                .when(jdbcTemplate).update(startsWith("UPDATE alert_storms SET notified_teams"), any(Object[].class));

        storm.lifecycleSweep();
        long afterFirst = realertLogRows();
        assertThat(afterFirst).as("ilk turda toplu tekrar gitti (üye başına günlük satırı)").isEqualTo(members.size());

        storm.lifecycleSweep();
        storm.lifecycleSweep();
        storm.lifecycleSweep();

        assertThat(realertLogRows()).as("damga yazılamadı diye her turda yeniden gitmez").isEqualTo(afterFirst);
        assertThat(claimCalls.get()).as("sahiplenme damgayı ilerletti → sonraki turlarda vakit gelmedi").isEqualTo(1);
    }

    @Test
    @DisplayName("Sahiplenme 0 satır (başka tur/pod damgayı ilerletti) → bu tur göndermez")
    void claimLost_noSend() {
        dbLastReAlertAt = ago(25 * 60);
        doReturn(0).when(jdbcTemplate).update(eq(StormService.SQL_REALERT_CLAIM), any(Object[].class));

        storm.lifecycleSweep();

        assertThat(realertLogRows()).isZero();
        verify(jdbcTemplate, never()).update(startsWith("UPDATE alert_storms SET notified_teams"), any(Object[].class));
    }

    @Test
    @DisplayName("Sahiplenme yazılamazsa (DB hatası) bu tur hiçbir şey göndermez; DB düzelince sonraki tur TEK kez gönderir")
    void claimThrows_noSend_thenSendsOnceWhenDbRecovers() {
        dbLastReAlertAt = ago(25 * 60);
        claimThrows.set(true);
        storm.lifecycleSweep();
        storm.lifecycleSweep();
        assertThat(realertLogRows()).isZero();

        claimThrows.set(false);
        storm.lifecycleSweep();
        storm.lifecycleSweep();
        assertThat(realertLogRows()).isEqualTo(members.size());
    }

    @Test
    @DisplayName("Damgası hiç yazılmamış fırtına (açılış damgası düştü, createdAt 3 gün önce) → IS NULL sahiplenmesiyle TEK gönderim")
    void unstampedStorm_claimsWithIsNullVariant() {
        dbLastReAlertAt = null;

        storm.lifecycleSweep();
        storm.lifecycleSweep();

        verify(jdbcTemplate).update(eq(StormService.SQL_REALERT_CLAIM_UNSTAMPED), any(Object[].class));
        verify(jdbcTemplate, never()).update(eq(StormService.SQL_REALERT_CLAIM), any(Object[].class));
        assertThat(realertLogRows()).isEqualTo(members.size());
    }

    @Test
    @DisplayName("Vakti gelmemiş tekrar: sahiplenme sorgusu HİÇ çalışmaz (normal yol birebir)")
    void notDue_noClaim() {
        dbLastReAlertAt = ago(60);

        storm.lifecycleSweep();

        assertThat(claimCalls.get()).isZero();
        assertThat(realertLogRows()).isZero();
    }
}
