package com.certmonitor.service;

import com.certmonitor.model.RememberMeToken;
import com.certmonitor.repository.RememberMeTokenRepository;
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

    // ── validate ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("validate: known, non-expired token → returns username")
    void validate_knownNonExpiredToken_returnsUsername() {
        RememberMeToken t = token("my-token", "alice", Instant.now().getEpochSecond() + 600);
        when(repo.findByToken("my-token")).thenReturn(Optional.of(t));

        Optional<String> result = service.validate("my-token");
        assertThat(result).contains("alice");
    }

    @Test
    @DisplayName("validate: expired token → empty")
    void validate_expiredToken_returnsEmpty() {
        RememberMeToken t = token("old-token", "alice", Instant.now().getEpochSecond() - 1);
        when(repo.findByToken("old-token")).thenReturn(Optional.of(t));

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
        verify(repo).deleteByToken("tok-123");
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
}
