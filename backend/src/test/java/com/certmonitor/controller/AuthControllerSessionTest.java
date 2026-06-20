package com.certmonitor.controller;

import com.certmonitor.repository.AuditLogRepository;
import com.certmonitor.service.AuditService;
import com.certmonitor.service.ClientIpResolver;
import com.certmonitor.service.RememberMeService;
import com.certmonitor.service.UserService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Tek aktif oturum — AuthController.invalidateOtherSessions birim testi (saf Mockito).
 * Spring Session principal-index üzerinden kullanıcının keepId dışındaki oturumlarını kapatır;
 * repo yokken (dev store-type=none) no-op olmalı (exception yok).
 */
class AuthControllerSessionTest {

    private AuthController newController() {
        // @RequiredArgsConstructor sırası: auditService, rememberMeService, userService, auditLogRepo, clientIpResolver
        return new AuthController(mock(AuditService.class), mock(RememberMeService.class),
                mock(UserService.class), mock(AuditLogRepository.class), mock(ClientIpResolver.class));
    }

    @Test
    @DisplayName("invalidateOtherSessions: keepId hariç tüm oturumları siler")
    void invalidateOtherSessions_deletesOthersKeepsCurrent() {
        AuthController ac = newController();
        @SuppressWarnings("unchecked")
        FindByIndexNameSessionRepository<Session> repo = mock(FindByIndexNameSessionRepository.class);
        ReflectionTestUtils.setField(ac, "sessionRepository", repo);

        Map<String, Session> sessions = new LinkedHashMap<>();
        sessions.put("S1", mock(Session.class));
        sessions.put("S2", mock(Session.class));
        sessions.put("KEEP", mock(Session.class));
        when(repo.findByIndexNameAndIndexValue(
                eq(FindByIndexNameSessionRepository.PRINCIPAL_NAME_INDEX_NAME), eq("alice")))
                .thenReturn(sessions);

        ac.invalidateOtherSessions("alice", "KEEP");

        verify(repo).deleteById("S1");
        verify(repo).deleteById("S2");
        verify(repo, never()).deleteById("KEEP");
    }

    @Test
    @DisplayName("invalidateOtherSessions: repo yok (dev none) → no-op, exception yok")
    void invalidateOtherSessions_nullRepo_noop() {
        AuthController ac = newController(); // sessionRepository null
        ac.invalidateOtherSessions("alice", "KEEP");
        // exception fırlatmamalı; doğrulanacak repo yok.
    }
}
