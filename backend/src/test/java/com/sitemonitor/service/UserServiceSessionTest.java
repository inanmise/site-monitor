package com.sitemonitor.service;

import com.sitemonitor.model.AppUser;
import com.sitemonitor.repository.AppUserRepository;
import com.sitemonitor.repository.CertificateInventoryRepository;
import com.sitemonitor.repository.EscalationContactRepository;
import com.sitemonitor.repository.PasswordHistoryRepository;
import com.sitemonitor.repository.TeamRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Tek aktif oturum (single active session per user) — store-agnostik registry birim testi.
 * Kullanıcının {@code activeSessionId} alanı: yeni login en güncel oturumu kaydeder; AuthInterceptor
 * her istekte {@link UserService#isCurrentSession} ile karşılaştırır, eşleşmeyen eski oturumu kapatır.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class UserServiceSessionTest {

    @Mock AppUserRepository userRepo;
    @Mock TeamRepository teamRepo;
    @Mock CertificateInventoryRepository inventoryRepo;
    @Mock EscalationContactRepository contactRepo;
    @Mock PasswordHistoryRepository passwordHistoryRepo;

    private UserService service;

    private static final DateTimeFormatter ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);

    @BeforeEach
    void setUp() {
        service = new UserService(userRepo, teamRepo, inventoryRepo, contactRepo, passwordHistoryRepo);
        ReflectionTestUtils.setField(service, "activeWindowSeconds", 120L);
        when(userRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private AppUser user(String username, String activeSessionId) {
        AppUser u = new AppUser();
        u.setUsername(username);
        u.setActiveSessionId(activeSessionId);
        return u;
    }

    private AppUser user(String username, String activeSessionId, String lastSeenAt) {
        AppUser u = user(username, activeSessionId);
        u.setLastSeenAt(lastSeenAt);
        return u;
    }

    private String agoSeconds(long secs) {
        return ISO.format(Instant.now().minusSeconds(secs));
    }

    // ── recordActiveSession ─────────────────────────────────────────────────────

    @Test
    @DisplayName("recordActiveSession: kullanıcının activeSessionId'sini günceller + kaydeder")
    void recordActiveSession_setsAndSaves() {
        AppUser u = user("alice", "OLD");
        when(userRepo.findByUsername("alice")).thenReturn(Optional.of(u));

        service.recordActiveSession("alice", "NEW");

        ArgumentCaptor<AppUser> captor = ArgumentCaptor.forClass(AppUser.class);
        verify(userRepo).save(captor.capture());
        assertThat(captor.getValue().getActiveSessionId()).isEqualTo("NEW");
    }

    @Test
    @DisplayName("recordActiveSession: null username/sessionId → repo'ya dokunmaz")
    void recordActiveSession_null_noop() {
        service.recordActiveSession(null, "NEW");
        service.recordActiveSession("alice", null);
        verify(userRepo, never()).findByUsername(any());
        verify(userRepo, never()).save(any());
    }

    @Test
    @DisplayName("recordActiveSession: kullanıcı yok → save çağrılmaz (exception yok)")
    void recordActiveSession_userMissing_noSave() {
        when(userRepo.findByUsername("ghost")).thenReturn(Optional.empty());
        service.recordActiveSession("ghost", "NEW");
        verify(userRepo, never()).save(any());
    }

    // ── isSessionSuperseded ─────────────────────────────────────────────────────

    @Test
    @DisplayName("isSessionSuperseded: kayıtlı oturumla eşleşir → false (geçersiz kılınmadı)")
    void isSessionSuperseded_match_false() {
        when(userRepo.findActiveSessionIdByUsername("alice")).thenReturn(Optional.of("S1"));
        assertThat(service.isSessionSuperseded("alice", "S1")).isFalse();
    }

    @Test
    @DisplayName("isSessionSuperseded: farklı (daha yeni) oturum kayıtlı → true (bu eski oturum kapatılmalı)")
    void isSessionSuperseded_mismatch_true() {
        when(userRepo.findActiveSessionIdByUsername("alice")).thenReturn(Optional.of("S2"));
        assertThat(service.isSessionSuperseded("alice", "S1")).isTrue();
    }

    @Test
    @DisplayName("isSessionSuperseded: activeSessionId null (kayıt yok) → false (zorlama yok)")
    void isSessionSuperseded_noRecord_false() {
        when(userRepo.findActiveSessionIdByUsername("alice")).thenReturn(Optional.empty());
        assertThat(service.isSessionSuperseded("alice", "S1")).isFalse();
    }

    @Test
    @DisplayName("isSessionSuperseded: null username/sessionId → false (zorlama yok, repo'ya dokunmaz)")
    void isSessionSuperseded_null_false() {
        assertThat(service.isSessionSuperseded(null, "S1")).isFalse();
        assertThat(service.isSessionSuperseded("alice", null)).isFalse();
        verify(userRepo, never()).findByUsername(any());
    }

    // ── clearActiveSession ──────────────────────────────────────────────────────

    @Test
    @DisplayName("clearActiveSession: eşleşen oturum → activeSessionId null + kaydet")
    void clearActiveSession_match_clears() {
        AppUser u = user("alice", "S1");
        when(userRepo.findByUsername("alice")).thenReturn(Optional.of(u));

        service.clearActiveSession("alice", "S1");

        ArgumentCaptor<AppUser> captor = ArgumentCaptor.forClass(AppUser.class);
        verify(userRepo).save(captor.capture());
        assertThat(captor.getValue().getActiveSessionId()).isNull();
    }

    @Test
    @DisplayName("clearActiveSession: eşleşmeyen oturum (yarıştaki yeni oturum) → kaydetmez")
    void clearActiveSession_mismatch_noSave() {
        when(userRepo.findByUsername("alice")).thenReturn(Optional.of(user("alice", "S2")));
        service.clearActiveSession("alice", "S1");
        verify(userRepo, never()).save(any());
    }

    // ── terminateActiveSession (admin kick) ──────────────────────────────────────

    @Test
    @DisplayName("terminateActiveSession: sentinel set eder ve gerçek oturum artık superseded olur")
    void terminateActiveSession_setsSentinel_andSupersedesRealSession() {
        AppUser u = user("alice", "REAL-SESSION");
        when(userRepo.findByUsername("alice")).thenReturn(Optional.of(u));

        service.terminateActiveSession("alice");

        ArgumentCaptor<AppUser> captor = ArgumentCaptor.forClass(AppUser.class);
        verify(userRepo).save(captor.capture());
        String sentinel = captor.getValue().getActiveSessionId();
        assertThat(sentinel).startsWith(UserService.SESSION_TERMINATED_PREFIX);
        // Sentinel gerçek oturuma eşleşmez → kullanıcının sonraki isteği superseded (atılır).
        // isSessionSuperseded artık activeSessionId'yi projeksiyon sorgusuyla okur → sentinel'i döndür.
        when(userRepo.findActiveSessionIdByUsername("alice")).thenReturn(Optional.of(sentinel));
        assertThat(service.isSessionSuperseded("alice", "REAL-SESSION")).isTrue();
    }

    @Test
    @DisplayName("terminateActiveSession: null username → repo'ya dokunmaz")
    void terminateActiveSession_null_noop() {
        service.terminateActiveSession(null);
        verify(userRepo, never()).findByUsername(any());
        verify(userRepo, never()).save(any());
    }

    // ── clearAllActiveSessions (açılışta stale temizliği) ────────────────────────

    @Test
    @DisplayName("clearAllActiveSessions: repo'ya delege eder ve temizlenen sayıyı döner")
    void clearAllActiveSessions_delegatesToRepo() {
        when(userRepo.clearAllActiveSessions()).thenReturn(2);
        assertThat(service.clearAllActiveSessions()).isEqualTo(2);
        verify(userRepo).clearAllActiveSessions();
    }

    // ── lastSeenAt tazelik (hasLiveSession) ──────────────────────────────────────

    @Test
    @DisplayName("hasLiveSession: taze ping (pencere içi) → true")
    void hasLiveSession_fresh_true() {
        assertThat(service.hasLiveSession(user("alice", "S1", agoSeconds(10)))).isTrue();
    }

    @Test
    @DisplayName("hasLiveSession: bayat ping (pencere dışı) → false")
    void hasLiveSession_stale_false() {
        assertThat(service.hasLiveSession(user("alice", "S1", agoSeconds(1000)))).isFalse();
    }

    @Test
    @DisplayName("hasLiveSession: lastSeenAt yok → false")
    void hasLiveSession_noLastSeen_false() {
        assertThat(service.hasLiveSession(user("alice", "S1", null))).isFalse();
    }

    @Test
    @DisplayName("hasLiveSession: TERMINATED sentinel → false (taze olsa bile)")
    void hasLiveSession_sentinel_false() {
        assertThat(service.hasLiveSession(
                user("alice", UserService.SESSION_TERMINATED_PREFIX + "x", agoSeconds(5)))).isFalse();
    }

    @Test
    @DisplayName("hasLiveSession: activeSessionId yok → false")
    void hasLiveSession_noSession_false() {
        assertThat(service.hasLiveSession(user("alice", null, agoSeconds(5)))).isFalse();
    }

    // ── touchActiveSession (ping → lastSeenAt tazele) ────────────────────────────

    @Test
    @DisplayName("touchActiveSession: repo.touchLastSeen'i kullanıcı+oturum ile çağırır")
    void touchActiveSession_callsRepo() {
        service.touchActiveSession("alice", "S1");
        verify(userRepo).touchLastSeen(eq("alice"), eq("S1"), any());
    }

    @Test
    @DisplayName("touchActiveSession: null parametre → repo'ya dokunmaz")
    void touchActiveSession_null_noop() {
        service.touchActiveSession(null, "S1");
        service.touchActiveSession("alice", null);
        verify(userRepo, never()).touchLastSeen(any(), any(), any());
    }

    @Test
    @DisplayName("recordActiveSession: lastSeenAt'i de (taze) yazar")
    void recordActiveSession_setsLastSeen() {
        when(userRepo.findByUsername("alice")).thenReturn(Optional.of(user("alice", null)));
        service.recordActiveSession("alice", "NEW");
        ArgumentCaptor<AppUser> captor = ArgumentCaptor.forClass(AppUser.class);
        verify(userRepo).save(captor.capture());
        assertThat(captor.getValue().getLastSeenAt()).isNotNull();
    }

    // ── F2/F3 (CPU denetimi): supersede TTL cache + touch debounce ────────────

    @Test
    @DisplayName("F2: TTL içinde canlı oturumun (eşleşen sid) ikinci supersede kontrolü DB'ye gitmez; sonuç cache'ten aynı")
    void supersede_cachedWithinTtl_singleSelect() {
        // 2026-10-09: önbellek yalnız EŞLEŞME cevabında güvenilir (canlı oturumun her isteği = sıcak yol, tek SELECT).
        // Eskiden önbellekteki FARKLI sid de DB'ye gitmeden "geçersiz kılındı" sayılıyordu — çok pod'da yeni oturumu
        // öldüren hata; o yol artık yeniden okur (bkz. supersede_cachedMismatch_reReadsDbBeforeKilling).
        ReflectionTestUtils.setField(service, "supersedeCacheMs", 5_000L);
        when(userRepo.findActiveSessionIdByUsername("ALICE")).thenReturn(Optional.of("NEWSID"));

        assertThat(service.isSessionSuperseded("ALICE", "NEWSID")).isFalse();
        assertThat(service.isSessionSuperseded("ALICE", "NEWSID")).isFalse();   // cache'ten karşılaştırma
        assertThat(service.isSessionSuperseded("ALICE", "NEWSID")).isFalse();

        verify(userRepo, times(1)).findActiveSessionIdByUsername("ALICE");
    }

    @Test
    @DisplayName("Çok pod (2026-10-09): önbellekteki sid FARKLIYSA oturum öldürülmeden önce DB yeniden okunur ve önbellek tazelenir")
    void supersede_cachedMismatch_reReadsDbBeforeKilling() {
        ReflectionTestUtils.setField(service, "supersedeCacheMs", 5_000L);
        // Pod A önbelleği eski oturumu (OLDSID) tutuyor; kullanıcı pod B'den zorla giriş yaptı → DB'de NEWSID.
        when(userRepo.findActiveSessionIdByUsername("ALICE"))
                .thenReturn(Optional.of("OLDSID"))
                .thenReturn(Optional.of("NEWSID"));
        assertThat(service.isSessionSuperseded("ALICE", "OLDSID")).isFalse();   // önbellek: OLDSID

        // Yeni oturumun ilk isteği pod A'ya düşer: önbellek farklı der → DB'den doğrulanır → öldürülMEZ.
        assertThat(service.isSessionSuperseded("ALICE", "NEWSID")).isFalse();
        // Önbellek tazelendi: yeni oturumun sonraki istekleri DB'ye gitmez, eski oturum ise (DB'den doğrulanmış) kapanır.
        assertThat(service.isSessionSuperseded("ALICE", "NEWSID")).isFalse();
        verify(userRepo, times(2)).findActiveSessionIdByUsername("ALICE");
        assertThat(service.isSessionSuperseded("ALICE", "OLDSID")).isTrue();
        verify(userRepo, times(3)).findActiveSessionIdByUsername("ALICE");
    }

    @Test
    @DisplayName("Supersede önbelleği yeni girişte COMMIT'ten sonra da boşaltılır (commit öncesi okunan bayat sid kalmaz)")
    void supersede_evictedAgainAfterCommit() {
        ReflectionTestUtils.setField(service, "supersedeCacheMs", 5_000L);
        ReflectionTestUtils.setField(service, "stampDedupeSeconds", 30L);
        when(userRepo.findByUsername("ALICE")).thenReturn(Optional.of(user("ALICE", "OLDSID")));
        when(userRepo.findActiveSessionIdByUsername("ALICE")).thenReturn(Optional.of("OLDSID"));

        org.springframework.transaction.support.TransactionSynchronizationManager.initSynchronization();
        try {
            service.recordSuccessfulLogin("ALICE", "NEWSID", "10.0.0.1", UserService.LoginMethod.PASSWORD);
            // Commit'ten ÖNCE eşzamanlı istek DB'deki eski değeri okuyup önbelleğe yazar.
            assertThat(service.isSessionSuperseded("ALICE", "OLDSID")).isFalse();
            var syncs = org.springframework.transaction.support.TransactionSynchronizationManager.getSynchronizations();
            assertThat(syncs).isNotEmpty();
            syncs.forEach(org.springframework.transaction.support.TransactionSynchronization::afterCommit);
        } finally {
            org.springframework.transaction.support.TransactionSynchronizationManager.clearSynchronization();
        }
        // Commit sonrası boşaltma: sonraki kontrol önbellekten değil DB'den.
        service.isSessionSuperseded("ALICE", "OLDSID");
        verify(userRepo, times(2)).findActiveSessionIdByUsername("ALICE");
    }

    @Test
    @DisplayName("F2: recordActiveSession/terminateActiveSession cache'i evict eder → sonraki kontrol DB'den")
    void supersede_evictedOnSessionMutation() {
        ReflectionTestUtils.setField(service, "supersedeCacheMs", 5_000L);
        when(userRepo.findActiveSessionIdByUsername("ALICE")).thenReturn(Optional.of("SID1"));
        when(userRepo.findByUsername("ALICE")).thenReturn(Optional.of(user("ALICE", "SID1")));

        service.isSessionSuperseded("ALICE", "SID1");        // cache dolar
        service.terminateActiveSession("ALICE");             // evict
        service.isSessionSuperseded("ALICE", "SID1");        // yeniden DB

        verify(userRepo, times(2)).findActiveSessionIdByUsername("ALICE");
    }

    @Test
    @DisplayName("F3: aynı kullanıcı+oturum için hızlı ardışık ping'lerde UPDATE 1 kez; farklı oturum yazar")
    void touch_debounced_perSession() {
        ReflectionTestUtils.setField(service, "touchDebounceMs", 60_000L);

        service.touchActiveSession("alice", "S1");
        service.touchActiveSession("alice", "S1");   // debounce penceresi içinde → yazma yok
        service.touchActiveSession("alice", "S2");   // farklı oturum → yazar

        verify(userRepo, times(1)).touchLastSeen(eq("alice"), eq("S1"), any());
        verify(userRepo, times(1)).touchLastSeen(eq("alice"), eq("S2"), any());
    }

    @Test
    @DisplayName("ayrıldım sinyali (2026-10-02): yalnız bu oturumun lastSeenAt'i silinir; sonraki ping debounce'a takılmadan yazar")
    void markLeft_clearsLastSeen_andResetsDebounce() {
        ReflectionTestUtils.setField(service, "touchDebounceMs", 60_000L);

        service.touchActiveSession("alice", "S1");   // yazar
        service.markLeft("alice", "S1");             // damga silinir + debounce kaydı düşer
        service.touchActiveSession("alice", "S1");   // geri dönüş/yenileme: 60 sn beklemeden YAZAR

        verify(userRepo).clearLastSeen("alice", "S1");
        verify(userRepo, times(2)).touchLastSeen(eq("alice"), eq("S1"), any());
    }

    @Test
    @DisplayName("ayrıldım sinyali: kullanıcı/oturum yoksa hiçbir şey yazılmaz")
    void markLeft_noop_withoutIdentity() {
        service.markLeft(null, "S1");
        service.markLeft("alice", null);
        verify(userRepo, times(0)).clearLastSeen(any(), any());
    }
}
