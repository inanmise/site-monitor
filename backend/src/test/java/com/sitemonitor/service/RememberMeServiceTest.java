package com.sitemonitor.service;

import com.sitemonitor.model.RememberMeToken;
import com.sitemonitor.repository.RememberMeTokenRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RememberMeServiceTest {

    @Mock RememberMeTokenRepository repo;

    private RememberMeService service;

    @BeforeEach
    void setUp() {
        service = new RememberMeService(repo);
        ReflectionTestUtils.setField(service, "ttlSeconds", 604800L);
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    // ── generateToken ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("generateToken returns non-null, non-blank string")
    void generateToken_returnsNonNullString() {
        String token = service.generateToken("alice");
        assertThat(token).isNotNull().isNotBlank();
    }

    @Test
    @DisplayName("generateToken persists entity with correct username")
    void generateToken_persistsWithCorrectUsername() {
        ArgumentCaptor<RememberMeToken> captor = ArgumentCaptor.forClass(RememberMeToken.class);

        service.generateToken("alice");

        verify(repo).save(captor.capture());
        assertThat(captor.getValue().getUsername()).isEqualTo("alice");
    }

    @Test
    @DisplayName("generateToken persists entity with future expiry")
    void generateToken_persistsWithFutureExpiry() {
        ArgumentCaptor<RememberMeToken> captor = ArgumentCaptor.forClass(RememberMeToken.class);

        service.generateToken("alice");

        verify(repo).save(captor.capture());
        assertThat(captor.getValue().getExpiresAt()).isGreaterThan(Instant.now().getEpochSecond());
    }

    @Test
    @DisplayName("generateToken: DB'de ham token DEĞİL SHA-256 hash saklanır (dönen ham token cookie'ye gider)")
    void generateToken_storesHashNotRaw() {
        ArgumentCaptor<RememberMeToken> captor = ArgumentCaptor.forClass(RememberMeToken.class);
        String raw = service.generateToken("alice");
        verify(repo).save(captor.capture());
        assertThat(captor.getValue().getToken()).isEqualTo(RememberMeService.sha256(raw)).isNotEqualTo(raw);
    }

    // ── validate ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("validate: known, non-expired token → returns username")
    void validate_knownNonExpiredToken_returnsUsername() {
        RememberMeToken t = token("my-token", "alice", Instant.now().getEpochSecond() + 600);
        when(repo.findByToken(RememberMeService.sha256("my-token"))).thenReturn(Optional.of(t));   // DB'de hash saklanır

        Optional<String> result = service.validate("my-token");
        assertThat(result).contains("alice");
    }

    @Test
    @DisplayName("validate: expired token → empty")
    void validate_expiredToken_returnsEmpty() {
        RememberMeToken t = token("old-token", "alice", Instant.now().getEpochSecond() - 1);
        when(repo.findByToken(RememberMeService.sha256("old-token"))).thenReturn(Optional.of(t));

        assertThat(service.validate("old-token")).isEmpty();
    }

    @Test
    @DisplayName("validate: unknown token → empty")
    void validate_unknownToken_returnsEmpty() {
        when(repo.findByToken("unknown")).thenReturn(Optional.empty());
        assertThat(service.validate("unknown")).isEmpty();
    }

    @Test
    @DisplayName("validate: null token → empty (no repo call)")
    void validate_nullToken_returnsEmpty() {
        assertThat(service.validate(null)).isEmpty();
        verify(repo, never()).findByToken(any());
    }

    @Test
    @DisplayName("validate: blank token → empty (no repo call)")
    void validate_blankToken_returnsEmpty() {
        assertThat(service.validate("   ")).isEmpty();
        verify(repo, never()).findByToken(any());
    }

    // ── invalidate ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("invalidate: known token → calls deleteByToken")
    void invalidate_knownToken_callsDeleteByToken() {
        service.invalidate("tok-123");
        verify(repo).deleteByToken(RememberMeService.sha256("tok-123"));
    }

    @Test
    @DisplayName("invalidate: null token → repo NOT called")
    void invalidate_nullToken_doesNotCallRepo() {
        service.invalidate(null);
        verify(repo, never()).deleteByToken(any());
    }

    // ── invalidateAllForUser (tek aktif oturum) ────────────────────────────────

    @Test
    @DisplayName("invalidateAllForUser: kullanıcı adı → deleteByUsername çağrılır")
    void invalidateAllForUser_callsDeleteByUsername() {
        service.invalidateAllForUser("alice");
        verify(repo).deleteByUsername("alice");
    }

    @Test
    @DisplayName("invalidateAllForUser: null/blank → repo çağrılmaz")
    void invalidateAllForUser_blank_doesNotCallRepo() {
        service.invalidateAllForUser(null);
        service.invalidateAllForUser("  ");
        verify(repo, never()).deleteByUsername(any());
    }

    // ── cleanExpired ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("cleanExpired → calls deleteExpired with current epoch second (±5s tolerance)")
    void cleanExpired_callsDeleteExpiredWithCurrentTime() {
        ArgumentCaptor<Long> captor = ArgumentCaptor.forClass(Long.class);
        long before = Instant.now().getEpochSecond();

        service.cleanExpired();

        verify(repo).deleteExpired(captor.capture());
        long captured = captor.getValue();
        long after = Instant.now().getEpochSecond();
        assertThat(captured).isBetween(before - 5, after + 5);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private RememberMeToken token(String token, String username, long expiresAt) {
        RememberMeToken t = new RememberMeToken();
        t.setToken(token);
        t.setUsername(username);
        t.setExpiresAt(expiresAt);
        return t;
    }

    // ── Cihaz meta'si (Cihaz Gecmisi ekrani) ─────────────────────────────────

    @Test
    @DisplayName("Uretim cihaz meta'sini yazar; HAM UA saklanmaz, yalniz OZET")
    void generateStoresDeviceMeta() {
        ArgumentCaptor<RememberMeToken> cap = ArgumentCaptor.forClass(RememberMeToken.class);
        String ua = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/120 Safari/537.36";

        service.generateToken("N68753", "10.1.2.3", ua);

        verify(repo).save(cap.capture());
        RememberMeToken saved = cap.getValue();
        assertThat(saved.getUaSummary()).isEqualTo("Windows · Chrome");
        assertThat(saved.getIpAddress()).isEqualTo("10.1.2.3");
        assertThat(saved.getCreatedAt()).isNotBlank();
        // Ham UA hicbir kolona yazilmaz — ekranda gerekirse audit satirindan okunur.
        assertThat(saved.getUaSummary()).doesNotContain("Mozilla");
    }

    @Test
    @DisplayName("Basarili dogrulama SON KULLANIM izini gunceller (kullanilmayan cihaz taze gorunmesin)")
    void validateStampsLastUsed() {
        RememberMeToken t = new RememberMeToken();
        t.setUsername("N68753");
        t.setExpiresAt(Instant.now().getEpochSecond() + 3600);
        when(repo.findByToken(anyString())).thenReturn(Optional.of(t));

        var user = service.validate("ham-token", "10.9.9.9");

        assertThat(user).contains("N68753");
        assertThat(t.getLastUsedAt()).isNotBlank();
        assertThat(t.getIpAddress()).isEqualTo("10.9.9.9");
        verify(repo).save(t);
    }

    @Test
    @DisplayName("Iz yazimi PATLASA BILE giris engellenmez (best-effort)")
    void validateSurvivesTrailWriteFailure() {
        // Bir metadata guncelleme hatasi kullaniciyi disarida birakmamali.
        RememberMeToken t = new RememberMeToken();
        t.setUsername("N68753");
        t.setExpiresAt(Instant.now().getEpochSecond() + 3600);
        when(repo.findByToken(anyString())).thenReturn(Optional.of(t));
        when(repo.save(any(RememberMeToken.class))).thenThrow(new RuntimeException("DB down"));

        assertThat(service.validate("ham-token", "10.9.9.9")).contains("N68753");
    }

    @Test
    @DisplayName("SURESI DOLMUS token'da iz YAZILMAZ ve kullanici donmez")
    void expiredTokenLeavesNoTrail() {
        RememberMeToken t = new RememberMeToken();
        t.setUsername("N68753");
        t.setExpiresAt(Instant.now().getEpochSecond() - 1);
        when(repo.findByToken(anyString())).thenReturn(Optional.of(t));

        assertThat(service.validate("ham-token", "10.9.9.9")).isEmpty();
        assertThat(t.getLastUsedAt()).isNull();
        verify(repo, never()).save(any(RememberMeToken.class));
    }
}
