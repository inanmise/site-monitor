package com.sitemonitor.controller;

import com.sitemonitor.model.AppUser;
import com.sitemonitor.model.AuditLog;
import com.sitemonitor.model.LoginUnknownLockout;
import com.sitemonitor.repository.AppUserRepository;
import com.sitemonitor.repository.AuditLogRepository;
import com.sitemonitor.repository.CertificateInventoryRepository;
import com.sitemonitor.repository.EscalationContactRepository;
import com.sitemonitor.repository.LoginUnknownLockoutRepository;
import com.sitemonitor.repository.PasswordHistoryRepository;
import com.sitemonitor.repository.RememberMeTokenRepository;
import com.sitemonitor.repository.TeamRepository;
import com.sitemonitor.service.AppSettingsService;
import com.sitemonitor.service.AuditService;
import com.sitemonitor.service.ClientIpResolver;
import com.sitemonitor.service.DeviceHistoryService;
import com.sitemonitor.service.LoginIssueMailService;
import com.sitemonitor.service.LoginIssueService;
import com.sitemonitor.service.RememberMeService;
import com.sitemonitor.service.TourStateService;
import com.sitemonitor.service.UserService;
import com.sitemonitor.service.lockout.LockoutLadder;
import com.sitemonitor.service.lockout.UnknownUserLockoutService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Kullanıcı adı numaralandırması (2026-10-09) — BİLİNMEYEN ad, mevcut ETKİN hesapla HER kilit kademesinde ayırt edilemez.
 *
 * <p>Gerçek {@link AuthController} + gerçek {@link UserService} (merdiven, kilit kontrolü, ilerleyici kilit) + gerçek
 * {@link UnknownUserLockoutService}; "veritabanı" (app_users, login_unknown_lockouts, audit_log) tabloları paylaşılan
 * bellek haritaları, saat ortak sahte saat. Denetimin kaba kuvvet kararı {@code AuditService.recordLogin}'in kuralını
 * birebir uygular: aynı aktörün BLOCKED olmayan LOGIN_FAILED satırları {@code max(şimdi−600 sn, countSince)}'ten SONRA
 * sayılır, {@code önceki ≥ failuresNeeded−1} → BRUTE_FORCE. İki ayrı küme aynı betiği koşar (biri mevcut hesap, biri olmayan
 * ad); her yanıt (durum + gövde) ve denetime giden sayım bağlamı aynı olmalı.
 */
class AuthControllerUnknownLockoutParityTest {

    static final Instant T0 = Instant.parse("2026-10-09T08:00:00.250Z");   // saniye altı kesir: aşağı yuvarlama da sınanır
    static final List<Long> DURATIONS = List.of(30L, 120L, 600L, 1800L);
    static final List<Integer> FAILS = List.of(5, 3, 2, 1);
    static final String PASSWORD = "dogru-parola-1";

    /** Kademe başına beklenen: kilidi tetikleyen hata sayısı ve kilit süresi (son kademeden sonra son süre yinelenir). */
    static final int[] EXPECTED_FAILS = {5, 3, 2, 1, 1, 1};
    static final long[] EXPECTED_WAIT = {30, 120, 600, 1800, 1800, 1800};

    // ── Ortak altyapı ─────────────────────────────────────────────────────────────────────────────────────────────

    static final class MutableClock extends Clock {
        private volatile Instant now;
        MutableClock(Instant start) { this.now = start; }
        void advance(Duration d) { now = now.plus(d); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    /** Pod'ların ortak "veritabanı" + saat. */
    static final class Cluster {
        final MutableClock clock = new MutableClock(T0);
        final Map<String, AppUser> users = new ConcurrentHashMap<>();
        final Map<String, LoginUnknownLockout> unknownRows = new ConcurrentHashMap<>();
        final List<AuditLog> auditRows = new CopyOnWriteArrayList<>();
        /** recordLogin'e verilen kilit bağlamı: "countSince|failuresNeeded". */
        final List<String> loginContexts = new CopyOnWriteArrayList<>();
        final AppUserRepository userRepo = mock(AppUserRepository.class);
        final LoginUnknownLockoutRepository lockRepo = mock(LoginUnknownLockoutRepository.class);
        final AuditService audit = mock(AuditService.class);
        final ClientIpResolver ip = mock(ClientIpResolver.class);
        final UserService userService;

        Cluster(boolean lockRepoDown) {
            when(userRepo.findByUsername(anyString()))
                    .thenAnswer(inv -> Optional.ofNullable(users.get(upper(inv.getArgument(0)))));
            when(userRepo.findByUsernameAndActiveTrue(anyString()))
                    .thenAnswer(inv -> Optional.ofNullable(users.get(upper(inv.getArgument(0))))
                            .filter(u -> Boolean.TRUE.equals(u.getActive())));
            when(userRepo.save(any())).thenAnswer(inv -> {
                AppUser u = inv.getArgument(0);
                users.put(upper(u.getUsername()), u);
                return u;
            });
            if (lockRepoDown) {
                var down = new DataAccessResourceFailureException("veritabanı yok");
                when(lockRepo.findById(anyString())).thenThrow(down);
                when(lockRepo.updateStep(anyString(), anyInt(), any(), any(), any())).thenThrow(down);
                when(lockRepo.insertStepIfAbsent(anyString(), anyInt(), any(), any(), any())).thenThrow(down);
            } else {
                wireLockRepo();
            }
            when(ip.resolve(any())).thenReturn("10.66.0.1");
            wireAudit();

            UserService real = new UserService(userRepo, mock(TeamRepository.class),
                    mock(CertificateInventoryRepository.class), mock(EscalationContactRepository.class),
                    mock(PasswordHistoryRepository.class));
            userService = spy(real);
            ReflectionTestUtils.setField(userService, "lockoutDurationsSecs", DURATIONS);
            ReflectionTestUtils.setField(userService, "lockoutFailuresNeeded", FAILS);
            ReflectionTestUtils.setField(userService, "lockoutClock", clock);
        }

        /** login_unknown_lockouts: koşullu UPDATE / INSERT … ON CONFLICT DO NOTHING anlamı. */
        private void wireLockRepo() {
            when(lockRepo.findById(anyString())).thenAnswer(inv -> Optional.ofNullable(copy(unknownRows.get(inv.getArgument(0)))));
            when(lockRepo.updateStep(anyString(), anyInt(), any(), any(), any())).thenAnswer(inv -> {
                LoginUnknownLockout r = unknownRows.get(inv.getArgument(0));
                if (r == null) return 0;
                r.setLockoutLevel(inv.getArgument(1));
                r.setLockoutUntil(inv.getArgument(2));
                r.setLastLockoutAt(inv.getArgument(3));
                r.setUpdatedAt(inv.getArgument(4));
                return 1;
            });
            when(lockRepo.insertStepIfAbsent(anyString(), anyInt(), any(), any(), any())).thenAnswer(inv -> {
                LoginUnknownLockout r = new LoginUnknownLockout();
                r.setUsernameKey(inv.getArgument(0));
                r.setLockoutLevel(inv.getArgument(1));
                r.setLockoutUntil(inv.getArgument(2));
                r.setLastLockoutAt(inv.getArgument(3));
                r.setCreatedAt(inv.getArgument(4));
                r.setUpdatedAt(inv.getArgument(4));
                return unknownRows.putIfAbsent(r.getUsernameKey(), r) == null ? 1 : 0;
            });
        }

        /** AuditService.recordLogin / recordRateLimited — kaba kuvvet kuralı birebir, ortak saatle. */
        private void wireAudit() {
            when(audit.recordLogin(any(), any(), any(), any(), any(), any(), any(),
                    anyBoolean(), any(), any(), anyInt(), any())).thenAnswer(inv -> {
                String actor = inv.getArgument(0);
                boolean success = inv.getArgument(7);
                String countSince = inv.getArgument(9);
                int failuresNeeded = inv.getArgument(10);
                loginContexts.add(countSince + "|" + failuresNeeded);
                Instant now = clock.instant();
                AuditLog e = new AuditLog();
                e.setActor(actor);
                e.setEventType(success ? "LOGIN" : "LOGIN_FAILED");
                e.setOutcome(success ? "SUCCESS" : "FAILURE");
                e.setEventTime(LockoutLadder.ISO.format(now));
                if (!success && actor != null && !actor.isBlank()) {
                    String tenMinAgo = LockoutLadder.ISO.format(now.minusSeconds(600));
                    String since = countSince != null && countSince.compareTo(tenMinAgo) > 0 ? countSince : tenMinAgo;
                    long prev = auditRows.stream()
                            .filter(r -> actor.equals(r.getActor()) && "LOGIN_FAILED".equals(r.getEventType())
                                    && r.getEventTime().compareTo(since) > 0 && !"BLOCKED".equals(r.getOutcome()))
                            .count();
                    if (prev >= failuresNeeded - 1) e.setAnomalyFlags("BRUTE_FORCE");
                }
                auditRows.add(e);
                return e;
            });
            doAnswer(inv -> {
                AuditLog e = new AuditLog();
                e.setActor(inv.getArgument(0));
                e.setEventType("LOGIN_FAILED");
                e.setOutcome("BLOCKED");
                e.setEventTime(LockoutLadder.ISO.format(clock.instant()));
                auditRows.add(e);
                return null;
            }).when(audit).recordRateLimited(any(), any(), any(), any());
        }

        /** Bir "pod": kendi denetleyicisi, kendi bilinmeyen-ad servisi (kendi bellek yedeği), ortak veritabanı. */
        AuthController pod() {
            AuthController ctl = new AuthController(audit, mock(RememberMeService.class), mock(DeviceHistoryService.class),
                    mock(RememberMeTokenRepository.class), mock(LoginIssueService.class), mock(LoginIssueMailService.class),
                    mock(AppSettingsService.class), userService, mock(TourStateService.class),
                    mock(AuditLogRepository.class), ip);
            ReflectionTestUtils.setField(ctl, "maxLoginAttempts", 100_000);   // IP oran sınırı bu testin konusu değil
            ReflectionTestUtils.setField(ctl, "blockSeconds", 30);
            ReflectionTestUtils.setField(ctl, "bootstrapAdminUsername", "admin");
            ReflectionTestUtils.setField(ctl, "lockoutDurationsSecs", DURATIONS);
            ReflectionTestUtils.setField(ctl, "lockoutFailuresNeeded", FAILS);
            UnknownUserLockoutService svc = new UnknownUserLockoutService(lockRepo, userService);
            ReflectionTestUtils.setField(svc, "clock", clock);
            ctl.setUnknownLockouts(svc);
            return ctl;
        }

        void addActiveLocalUser(String canonical) {
            AppUser u = new AppUser();
            u.setId(7L);
            u.setUsername(canonical);
            u.setSystemRole("USER");
            u.setActive(true);
            u.setAuthSource("LOCAL");
            u.setPasswordHash(new BCryptPasswordEncoder(4).encode(PASSWORD));
            users.put(upper(canonical), u);
        }

        private static LoginUnknownLockout copy(LoginUnknownLockout r) {
            if (r == null) return null;
            LoginUnknownLockout c = new LoginUnknownLockout();
            c.setUsernameKey(r.getUsernameKey());
            c.setLockoutLevel(r.getLockoutLevel());
            c.setLockoutUntil(r.getLockoutUntil());
            c.setLastLockoutAt(r.getLastLockoutAt());
            c.setCreatedAt(r.getCreatedAt());
            c.setUpdatedAt(r.getUpdatedAt());
            return c;
        }
    }

    static String upper(String s) {
        return s == null ? null : s.strip().toUpperCase(Locale.ROOT);
    }

    static ResponseEntity<Map<String, Object>> attempt(AuthController pod, String username, String password) {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/login");
        req.addHeader("User-Agent", "parity-test");
        return pod.login(Map.of("username", username, "password", password), req, new MockHttpServletResponse());
    }

    /** Yanıtın istemcinin gördüğü hâli: durum + gövde (alan sırası dahil). */
    static String seen(ResponseEntity<Map<String, Object>> r) {
        return r.getStatusCode().value() + " " + r.getBody();
    }

    static long waitSeconds(ResponseEntity<Map<String, Object>> r) {
        return ((Number) r.getBody().get("wait_seconds")).longValue();
    }

    /**
     * Ortak betik: her kademede kilitlenene dek 2 sn arayla yanlış parola, kilitliyken bir yoklama, sonra kilidin bitimini
     * bekle. Yanıtlar sırasıyla döner.
     */
    static List<String> runLadder(Cluster c, AuthController pod, String typedName, int levels) {
        List<String> transcript = new ArrayList<>();
        for (int level = 1; level <= levels; level++) {
            int attempts = 0;
            ResponseEntity<Map<String, Object>> r;
            do {
                r = attempt(pod, typedName, "yanlis-parola");
                transcript.add("L" + level + " fail#" + (attempts + 1) + " → " + seen(r));
                attempts++;
                c.clock.advance(Duration.ofSeconds(2));
            } while (r.getStatusCode().value() != 423 && attempts < 8);

            assertThat(attempts).as("kademe %d: kilidi tetikleyen hata sayısı", level)
                    .isEqualTo(EXPECTED_FAILS[Math.min(level, EXPECTED_FAILS.length) - 1]);
            long dur = EXPECTED_WAIT[Math.min(level, EXPECTED_WAIT.length) - 1];
            assertThat(waitSeconds(r)).as("kademe %d: kilit süresi", level).isEqualTo(dur);

            // Kilit sürerken (2 sn sonra): parola denenmez, kalan süre (aşağı yuvarlanmış) döner.
            ResponseEntity<Map<String, Object>> probe = attempt(pod, typedName, PASSWORD);
            transcript.add("L" + level + " probe → " + seen(probe));
            assertThat(probe.getStatusCode().value()).isEqualTo(423);
            assertThat(waitSeconds(probe)).isEqualTo(dur - 3);   // 2 sn + 0,25 sn kesir → aşağı yuvarlama

            c.clock.advance(Duration.ofSeconds(dur));   // kilit bitti
        }
        return transcript;
    }

    // ── Testler ───────────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Kademe 1..6: bilinmeyen ad ile mevcut ETKİN hesap aynı yanıtları alır (gereken hata 5/3/2/1/1/1, wait_seconds 30/120/600/1800/1800/1800)")
    void everyLevel_unknownNameIndistinguishableFromExistingAccount() {
        Cluster existing = new Cluster(false);
        existing.addActiveLocalUser("ALICE");
        List<String> existingSeen = runLadder(existing, existing.pod(), "alice", 6);

        Cluster unknown = new Cluster(false);
        List<String> unknownSeen = runLadder(unknown, unknown.pod(), "ghost", 6);

        // İstemcinin gördüğü her yanıt (durum + gövde) sırasıyla birebir aynı.
        assertThat(unknownSeen).containsExactlyElementsOf(existingSeen);
        assertThat(existingSeen).hasSize(19);   // 5+3+2+1+1+1 = 13 hata + 6 yoklama
        // Denetime giden kaba kuvvet bağlamı (sayım başlangıcı + eşik) da aynı — merdiven tek kaynak.
        assertThat(unknown.loginContexts).containsExactlyElementsOf(existing.loginContexts);
        // Denetim olay dizisi (tür / sonuç / kaba kuvvet bayrağı) aynı.
        assertThat(eventShape(unknown.auditRows)).containsExactlyElementsOf(eventShape(existing.auditRows));

        // Mevcut hesap: bugünkü yol (app_users + ACCOUNT_LOCKED). Olmayan ad: satır login_unknown_lockouts'ta,
        // app_users'a HİÇ yazılmaz, ACCOUNT_LOCKED YAZILMAZ.
        assertThat(existing.users.get("ALICE").getFailedBlockCount()).isEqualTo(6);
        verify(existing.audit, times(6)).recordAction(eq("ACCOUNT_LOCKED"), eq("ALICE"), any(), any(), any(), eq("USER"),
                eq("ALICE"), any(), any(), any(), any());
        assertThat(unknown.unknownRows.get("GHOST").getLockoutLevel()).isEqualTo(6);
        assertThat(unknown.users).isEmpty();
        verify(unknown.userRepo, never()).save(any());
        verify(unknown.audit, never()).recordAction(eq("ACCOUNT_LOCKED"), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any());
        verify(unknown.userService, never()).applyProgressiveLockout(anyString());
    }

    @Test
    @DisplayName("Zamanlama: parola özeti olmayan / bilinmeyen ad da her başarısız denemede bir BCrypt maliyeti öder")
    void unknownName_paysPasswordHashCost() {
        Cluster existing = new Cluster(false);
        existing.addActiveLocalUser("ALICE");
        attempt(existing.pod(), "alice", "yanlis");
        verify(existing.userService, never()).burnPasswordCheck(any());   // authenticate zaten BCrypt yaptı

        Cluster unknown = new Cluster(false);
        AuthController pod = unknown.pod();
        attempt(pod, "ghost", "yanlis");
        attempt(pod, "ghost", "yanlis");
        verify(unknown.userService, times(2)).burnPasswordCheck("yanlis");
    }

    @Test
    @DisplayName("Çok pod: A pod'unda başlayan kilidi B pod'u kalan süreyle görür; merdiven pod'lar arasında sürer — mevcut hesapla aynı")
    void crossPod_secondPodSeesRemainingSecondsAndContinuesLadder() {
        List<String> existingSeen = crossPodScript(existingCluster(), "alice");
        List<String> unknownSeen = crossPodScript(new Cluster(false), "ghost");
        assertThat(unknownSeen).containsExactlyElementsOf(existingSeen);
    }

    private static Cluster existingCluster() {
        Cluster c = new Cluster(false);
        c.addActiveLocalUser("ALICE");
        return c;
    }

    private static List<String> crossPodScript(Cluster c, String name) {
        AuthController podA = c.pod();
        AuthController podB = c.pod();
        List<String> seen = new ArrayList<>();

        ResponseEntity<Map<String, Object>> r = null;
        for (int i = 0; i < 5; i++) {
            r = attempt(podA, name, "yanlis");
            seen.add("A " + seen(r));
            c.clock.advance(Duration.ofSeconds(1));
        }
        assertThat(r.getStatusCode().value()).isEqualTo(423);
        assertThat(waitSeconds(r)).isEqualTo(30);

        c.clock.advance(Duration.ofSeconds(9));   // kilitten 10 sn sonra, başka pod
        ResponseEntity<Map<String, Object>> onB = attempt(podB, name, "yanlis");
        seen.add("B " + seen(onB));
        assertThat(onB.getStatusCode().value()).isEqualTo(423);
        assertThat(waitSeconds(onB)).isEqualTo(19);   // 30 − 10 − 0,25 kesir → 19 (taze yanıt DEĞİL)

        c.clock.advance(Duration.ofSeconds(25));   // kilit bitti
        // 2. kademe B'de: 3 hata → 120 sn (A'nın yazdığı kademeden devam).
        for (int i = 0; i < 3; i++) {
            r = attempt(podB, name, "yanlis");
            seen.add("B " + seen(r));
            c.clock.advance(Duration.ofSeconds(1));
        }
        assertThat(r.getStatusCode().value()).isEqualTo(423);
        assertThat(waitSeconds(r)).isEqualTo(120);
        ResponseEntity<Map<String, Object>> onA = attempt(podA, name, "yanlis");
        seen.add("A " + seen(onA));
        assertThat(waitSeconds(onA)).isEqualTo(118);
        return seen;
    }

    @Test
    @DisplayName("Veritabanı yazılamıyor/okunamıyor: giriş 500 vermez — bellek içi 1. kademe kilidi (eski davranış)")
    void lockStoreDown_fallsBackToInMemoryLevelOne() {
        Cluster c = new Cluster(true);
        AuthController pod = c.pod();

        ResponseEntity<Map<String, Object>> r = null;
        for (int i = 0; i < 5; i++) {
            r = attempt(pod, "ghost", "yanlis");
            assertThat(r.getStatusCode().value()).isIn(401, 423);
            c.clock.advance(Duration.ofSeconds(1));
        }
        assertThat(r.getStatusCode().value()).isEqualTo(423);
        assertThat(waitSeconds(r)).isEqualTo(30);

        ResponseEntity<Map<String, Object>> blocked = attempt(pod, "Ghost", "yanlis");
        assertThat(blocked.getStatusCode().value()).isEqualTo(423);
        assertThat(waitSeconds(blocked)).isBetween(1L, 30L);
        verify(c.lockRepo, atLeastOnce()).findById("GHOST");
        assertThat(c.users).isEmpty();
    }

    @Test
    @DisplayName("Bilinmeyen adın kilit satırı yalnız hesap YOKKEN okunur — aynı adla sonradan açılan hesabı eski satır kilitlemez")
    void staleUnknownRow_doesNotLockLaterCreatedAccount() {
        Cluster c = new Cluster(false);
        LoginUnknownLockout stale = new LoginUnknownLockout();
        stale.setUsernameKey("BOB");
        stale.setLockoutLevel(4);
        stale.setLastLockoutAt(LockoutLadder.ISO.format(T0));
        stale.setLockoutUntil(LockoutLadder.ISO.format(T0.plusSeconds(1800)));   // hâlâ 30 dk kilitli
        c.unknownRows.put("BOB", stale);
        c.addActiveLocalUser("BOB");   // yönetici aynı adla hesap açtı
        AuthController pod = c.pod();

        ResponseEntity<Map<String, Object>> r = attempt(pod, "bob", "yanlis");
        assertThat(r.getStatusCode().value()).isEqualTo(401);   // hesabın kendi durumu: kilitsiz, ilk hata
        verify(c.lockRepo, never()).findById(any());
        verify(c.userService).checkLockout("bob");
        verify(c.userService).failuresNeededForLevel(0);
    }

    private static List<String> eventShape(List<AuditLog> rows) {
        return rows.stream()
                .map(r -> r.getEventType() + "/" + r.getOutcome() + "/" + (r.getAnomalyFlags() == null ? "" : r.getAnomalyFlags()))
                .toList();
    }
}
