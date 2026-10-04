package com.sitemonitor.service;

import com.sitemonitor.model.AlertEvent;
import com.sitemonitor.model.AlertStorm;
import com.sitemonitor.model.Team;
import com.sitemonitor.model.UserPushDelivery;
import com.sitemonitor.model.UserPushScope;
import com.sitemonitor.repository.*;
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
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Fırtına push'u ↔ üye alarm bağının KAYDI (2026-10-04) — gerçek {@link StormService} + gerçek {@link UserPushService} +
 * gerçek {@link StormPushCoverageService} (yazdığı JDBC taklit edilir; teslimat deposu bellek-içi, outbox boşaltılmaz →
 * hiçbir push isteği çıkmaz).
 *
 * <ul>
 *   <li>Açılış / günlük tekrar / çözüm bildirimi kapsadığı üye kümesini TAM yazar (çözüm yalnız kurtulanları).</li>
 *   <li>Kanal kararı satırıyla biten bildirim (ör. takım push'u kapalı) de kapsamı yazar — alarm "gönderilmedi: neden" der.</li>
 *   <li>Takım yalıtımı: TEAM:A bildirimi TEAM:B alarmını kapsamaz.</li>
 *   <li>Bildirim başına TEK JDBC gidiş-dönüşü; yazma düşerse push kararı ve satırları BİREBİR aynı.</li>
 *   <li>Bireysel push kipinde toplu push yok → kayıt yok (ayar fırtına sürerken açıldıysa simetrik çözüm push'u kaydedilir).</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class StormPushCoverageRecordTest {

    @Mock AlertEventRepository alertEventRepo;
    @Mock AppSettingsService appSettings;
    @Mock EmailNotificationService emailService;
    @Mock WebhookService webhookService;
    @Mock TeamRepository teamRepo;
    @Mock EscalationContactRepository contactRepo;
    @Mock CertificateInventoryRepository inventoryRepo;
    @Mock NotificationLogRepository notificationLogRepo;
    @Mock NotificationGroupService notificationGroups;
    @Mock AlertStormRepository stormRepo;
    @Mock JdbcTemplate stormJdbc;
    @Mock HttpMonitorRepository httpRepo;
    @Mock PortMonitorRepository portRepo;
    @Mock KeywordMonitorRepository keywordRepo;
    @Mock PingMonitorRepository pingRepo;
    @Mock DnsMonitorRepository dnsRepo;
    @Mock DomainMonitorRepository domainMonitorRepo;
    @Mock UserPushDeliveryRepository deliveryRepo;
    @Mock UserPushScopeRepository scopeRepo;
    @Mock UserPushRecipientResolver resolver;
    @Mock SecretCipher secretCipher;
    @Mock TrustEvaluator trustEvaluator;
    @Mock CaAutoPinService caAutoPinService;
    /** Kapsam kaydının JDBC'si — StormService'in üyelik JDBC'sinden AYRI: gidiş-dönüş sayısı doğrudan sayılır. */
    @Mock JdbcTemplate coverageJdbc;

    static final long TEAM_A = 14L;
    static final long TEAM_B = 15L;
    static final long STORM = 7L;
    static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);
    static final List<String> USERS = List.of("N00001", "N00002");

    UserPushService push;
    StormService engine;
    StormPushCoverageService coverage;

    final List<UserPushDelivery> store = new ArrayList<>();
    final AtomicLong pushSeq = new AtomicLong();
    /** Her batchUpdate çağrısının satırları (çağrı başına bir liste). */
    final List<List<Object[]>> batches = new ArrayList<>();
    boolean pushIndividual = false;
    boolean channelEnabled = true;

    @BeforeEach
    void setUp() {
        push = new UserPushService(appSettings, deliveryRepo, scopeRepo, resolver, alertEventRepo, secretCipher,
                trustEvaluator, caAutoPinService);
        coverage = new StormPushCoverageService(coverageJdbc, alertEventRepo, stormRepo, deliveryRepo);
        push.setStormPushCoverage(coverage);
        engine = new StormService(stormRepo, alertEventRepo, appSettings, emailService, webhookService, teamRepo, contactRepo,
                inventoryRepo, stormJdbc, httpRepo, portRepo, keywordRepo, pingRepo, dnsRepo, domainMonitorRepo,
                notificationGroups);
        ReflectionTestUtils.setField(engine, "userPushService", push);
        ReflectionTestUtils.setField(engine, "objectMapper", new com.fasterxml.jackson.databind.ObjectMapper());
        ReflectionTestUtils.setField(engine, "notificationLogRepo", notificationLogRepo);

        when(appSettings.getBoolean(anyString(), anyBoolean())).thenAnswer(i -> {
            String k = i.getArgument(0);
            if ("site.monitor.userpush.enabled".equals(k)) return channelEnabled;
            if (StormService.KEY_PUSH_INDIVIDUAL.equals(k)) return pushIndividual;
            return i.getArgument(1);
        });
        when(appSettings.getInt(anyString(), anyInt())).thenAnswer(i -> i.getArgument(1));
        when(appSettings.getString(anyString(), any())).thenAnswer(i -> i.getArgument(1));
        when(appSettings.getCsv(anyString(), anyString())).thenReturn(List.of("1"));

        for (long id : new long[]{TEAM_A, TEAM_B}) {
            Team t = new Team();
            t.setId(id); t.setName("Takım " + id); t.setEmail("takim-" + id + "@example.com");
            when(teamRepo.findById(id)).thenReturn(Optional.of(t));
        }
        when(resolver.resolve(any(), any())).thenReturn(USERS.stream()
                .map(u -> new UserPushRecipientResolver.Recipient(u, "Kişi " + u, null)).toList());
        when(resolver.resolvePrior(anyList())).thenAnswer(i -> ((List<String>) i.getArgument(0)).stream()
                .map(u -> new UserPushRecipientResolver.Recipient(u, "Kişi " + u, null)).toList());
        when(scopeRepo.findByScopeTypeAndScopeKey(anyString(), anyString())).thenReturn(Optional.empty());

        when(deliveryRepo.save(any())).thenAnswer(i -> {
            UserPushDelivery d = i.getArgument(0);
            if (d.getId() == null) { d.setId(pushSeq.incrementAndGet()); store.add(d); }
            return d;
        });
        when(deliveryRepo.existsByDedupeKeyAndUsername(any(), any())).thenAnswer(i -> store.stream()
                .anyMatch(d -> Objects.equals(d.getDedupeKey(), i.getArgument(0)) && Objects.equals(d.getUsername(), i.getArgument(1))));
        when(deliveryRepo.existsByDedupeKeyAndUsernameAndTeamId(any(), any(), any())).thenAnswer(i -> store.stream()
                .anyMatch(d -> Objects.equals(d.getDedupeKey(), i.getArgument(0)) && Objects.equals(d.getUsername(), i.getArgument(1))
                        && Objects.equals(d.getTeamId(), i.getArgument(2))));
        when(deliveryRepo.findByDedupeKeyStartingWithAndTeamIdAndStatusOrderByIdAsc(any(), any(), any())).thenAnswer(i -> store.stream()
                .filter(d -> d.getDedupeKey() != null && d.getDedupeKey().startsWith(i.getArgument(0))
                        && Objects.equals(d.getTeamId(), i.getArgument(1)) && Objects.equals(d.getStatus(), i.getArgument(2))).toList());
        when(deliveryRepo.countRecentForUser(any(), any())).thenReturn(0L);

        when(coverageJdbc.batchUpdate(anyString(), anyList())).thenAnswer(i -> {
            List<Object[]> rows = i.getArgument(1);
            batches.add(new ArrayList<>(rows));
            return new int[rows.size()];
        });
    }

    // ── Yardımcılar ───────────────────────────────────────────────────────────────────────────────────────────────

    AlertEvent member(long id, long team, boolean resolved) {
        AlertEvent e = new AlertEvent();
        e.setId(id);
        e.setDomain("https://h" + id + ".example.com");
        e.setAlertType(EscalationService.TYPE_HTTP_DOWN);
        e.setAlertLevel("WARNING");
        e.setTeamId(team);
        e.setStormId(STORM);
        e.setResolved(resolved);
        e.setAcknowledged(false);
        e.setCreatedAt(ISO.format(Instant.now().minus(10, ChronoUnit.MINUTES)));
        e.setContextJson("{\"team_id\":" + team + ",\"monitor_id\":" + id + "}");
        return e;
    }

    AlertStorm storm(boolean resolved) {
        AlertStorm s = new AlertStorm();
        s.setId(STORM);
        s.setScopeKey("TEAM:" + TEAM_A);
        s.setScopeType("TEAM");
        s.setTeamId(TEAM_A);
        s.setResolved(resolved);
        s.setCreatedAt(ISO.format(Instant.now().minus(2, ChronoUnit.HOURS)));
        if (resolved) s.setResolvedAt(ISO.format(Instant.now()));
        return s;
    }

    /** Kayıtlı satırlar: [storm, team, key, trigger, alarm]. */
    List<List<Object>> rows() {
        List<List<Object>> out = new ArrayList<>();
        for (List<Object[]> b : batches) for (Object[] r : b) out.add(List.of(r[0], r[1], r[2], r[3], r[4]));
        return out;
    }

    static List<Object> row(long team, String key, String trigger, long alarm) {
        return List.of(STORM, team, key, trigger, alarm);
    }

    void markAllSent() {
        store.forEach(d -> { if ("PENDING".equals(d.getStatus())) d.setStatus("SENT"); });
    }

    // ── Kapsam kümeleri ───────────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("açılış / günlük tekrar / çözüm: kapsam TAM üye kümesiyle yazılır — çözüm yalnız kurtulan üyeyi kapsar")
    void initial_daily_resolve_exactMemberSets() {
        AlertEvent a = member(11, TEAM_A, false);
        AlertEvent b = member(12, TEAM_A, false);

        ReflectionTestUtils.invokeMethod(engine, "sendStormAlert", storm(false), List.of(a, b), "INITIAL");
        assertThat(rows()).containsExactly(
                row(TEAM_A, "storm:7:INITIAL", "INITIAL", 11), row(TEAM_A, "storm:7:INITIAL", "INITIAL", 12));
        markAllSent();

        batches.clear();
        LocalDate d0 = LocalDate.now(ZoneId.of("Europe/Istanbul"));
        ReflectionTestUtils.invokeMethod(engine, "sendStormAlert", storm(false), List.of(a, b), "DAILY_REALERT");
        LocalDate d1 = LocalDate.now(ZoneId.of("Europe/Istanbul"));
        assertThat(rows()).hasSize(2);
        assertThat(rows()).extracting(r -> r.get(2)).allSatisfy(k ->
                assertThat(k).isIn("storm:7:DAILY_REALERT:" + d0, "storm:7:DAILY_REALERT:" + d1));
        assertThat(rows()).extracting(r -> r.get(4)).containsExactly(11L, 12L);
        assertThat(rows()).extracting(r -> r.get(3)).containsOnly("DAILY_REALERT");

        batches.clear();
        b.setResolved(true);
        ReflectionTestUtils.invokeMethod(engine, "sendStormRecovery", storm(true), List.of(b), List.of(a));
        assertThat(rows()).containsExactly(row(TEAM_A, "storm-resolved:7", "RESOLVE", 12));
        // Çözüm push'u gerçekten gitti (açılışı alanlara) — kayıt kararla tutarlı.
        assertThat(store).filteredOn(d -> "storm-resolved:7".equals(d.getDedupeKey()))
                .extracting(UserPushDelivery::getUsername).containsExactlyElementsOf(USERS);
    }

    @Test
    @DisplayName("kanal karar satırıyla biten bildirim de kapsamı yazar (takım push'u kapalı → SKIPPED_TEAM_OFF + kapsam)")
    void skipRowNotice_isCovered() {
        UserPushScope off = new UserPushScope();
        off.setScopeType("TEAM"); off.setScopeKey(String.valueOf(TEAM_A)); off.setEnabled(false);
        when(scopeRepo.findByScopeTypeAndScopeKey("TEAM", String.valueOf(TEAM_A))).thenReturn(Optional.of(off));
        AlertEvent a = member(21, TEAM_A, false);

        ReflectionTestUtils.invokeMethod(engine, "sendStormAlert", storm(false), List.of(a), "INITIAL");

        assertThat(store).singleElement().satisfies(d -> {
            assertThat(d.getUsername()).isEqualTo(UserPushService.SYSTEM_USER);
            assertThat(d.getStatus()).isEqualTo("SKIPPED_TEAM_OFF");
            assertThat(d.getDedupeKey()).isEqualTo("storm:7:INITIAL");
        });
        assertThat(rows()).containsExactly(row(TEAM_A, "storm:7:INITIAL", "INITIAL", 21));
    }

    @Test
    @DisplayName("takım yalıtımı: iki takımın üyeleri ayrı dağıtımda — TEAM:A bildirimi yalnız A alarmını, TEAM:B yalnız B'yi kapsar")
    void teamIsolation() {
        AlertEvent a = member(31, TEAM_A, false);
        AlertEvent c = member(32, TEAM_B, false);

        ReflectionTestUtils.invokeMethod(engine, "sendStormAlert", storm(false), List.of(a, c), "INITIAL");

        assertThat(rows()).containsExactlyInAnyOrder(
                row(TEAM_A, "storm:7:INITIAL", "INITIAL", 31), row(TEAM_B, "storm:7:INITIAL", "INITIAL", 32));
        assertThat(rows()).noneMatch(r -> r.get(1).equals(TEAM_A) && r.get(4).equals(32L));
        assertThat(rows()).noneMatch(r -> r.get(1).equals(TEAM_B) && r.get(4).equals(31L));

        // Doğrudan çağrı (savunma): TEAM:A bildirimine yanlışlıkla B alarmı verilse de kapsanmaz.
        batches.clear();
        push.enqueueStormNotice(8L, TEAM_A, "INITIAL", "WARNING", List.of(a, c), "m");
        assertThat(rows()).extracting(r -> r.get(4)).containsExactly(31L);
    }

    @Test
    @DisplayName("bildirim başına TEK JDBC gidiş-dönüşü; kayıt düşerse push kararı ve satırları BİREBİR aynı")
    void oneRoundTrip_failureDoesNotChangeDecision() {
        AlertEvent a = member(41, TEAM_A, false);
        AlertEvent b = member(42, TEAM_A, false);
        Map<String, Object> ok = push.enqueueStormNotice(9L, TEAM_A, "INITIAL", "WARNING", List.of(a, b), "3 monitör");
        verify(coverageJdbc, times(1)).batchUpdate(eq(StormPushCoverageService.SQL_INSERT), anyList());
        verifyNoMoreInteractions(coverageJdbc);
        List<String> okRows = store.stream().map(d -> d.getUsername() + "|" + d.getStatus() + "|" + d.getDedupeKey()).toList();

        // Aynı bildirim, bu kez kayıt patlıyor (teslimat deposu boşaltıldı — tekilleştirme ilk koşuyu görmesin)
        store.clear();
        reset(coverageJdbc);
        when(coverageJdbc.batchUpdate(anyString(), anyList())).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("db down"));
        Map<String, Object> failed = push.enqueueStormNotice(9L, TEAM_A, "INITIAL", "WARNING", List.of(a, b), "3 monitör");

        assertThat(failed.get("queued")).isEqualTo(ok.get("queued"));
        assertThat(failed.get("skipped")).isEqualTo(ok.get("skipped"));
        assertThat(failed.get("reason")).isEqualTo(ok.get("reason"));
        assertThat(store.stream().map(d -> d.getUsername() + "|" + d.getStatus() + "|" + d.getDedupeKey()).toList()).isEqualTo(okRows);
        verify(coverageJdbc, times(1)).batchUpdate(eq(StormPushCoverageService.SQL_INSERT), anyList());
    }

    @Test
    @DisplayName("bireysel push kipi: toplu push yok → kayıt yok; ayar fırtına sürerken açıldıysa simetrik çözüm push'u kaydedilir")
    void pushIndividual_noCoverage_exceptMidSwitchResolve() {
        pushIndividual = true;
        AlertEvent a = member(51, TEAM_A, false);
        ReflectionTestUtils.invokeMethod(engine, "sendStormAlert", storm(false), List.of(a), "INITIAL");
        a.setResolved(true);
        ReflectionTestUtils.invokeMethod(engine, "sendStormRecovery", storm(true), List.of(a), List.<AlertEvent>of());
        assertThat(batches).isEmpty();
        verifyNoInteractions(coverageJdbc);

        // Fırtına ayar KAPALIYKEN açıldı (toplu açılış gitti), sonra ayar açıldı: çözüm yine toplu gider ve kaydedilir.
        pushIndividual = false;
        AlertEvent b = member(52, TEAM_A, false);
        ReflectionTestUtils.invokeMethod(engine, "sendStormAlert", storm(false), List.of(b), "INITIAL");
        markAllSent();
        batches.clear();
        pushIndividual = true;
        b.setResolved(true);
        ReflectionTestUtils.invokeMethod(engine, "sendStormRecovery", storm(true), List.of(b), List.<AlertEvent>of());
        assertThat(rows()).containsExactly(row(TEAM_A, "storm-resolved:7", "RESOLVE", 52));
    }

    @Test
    @DisplayName("push kanalı global KAPALI: bildirim satırı yazılmaz → kapsam da yazılmaz")
    void channelDisabled_noCoverage() {
        channelEnabled = false;
        ReflectionTestUtils.invokeMethod(engine, "sendStormAlert", storm(false), List.of(member(61, TEAM_A, false)), "INITIAL");
        assertThat(store).isEmpty();
        verifyNoInteractions(coverageJdbc);
    }
}
