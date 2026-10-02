package com.sitemonitor.service;

import com.sitemonitor.model.EscalationContact;
import com.sitemonitor.repository.AppUserRepository;
import jakarta.mail.Message;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.*;

/**
 * Pasif kullanıcı bildirim süzgeci (2026-10-02, kullanıcı kararı: pasif kullanıcıya hiçbir bildirim gitmez).
 * Kişi düzeyi ({@code user_id}) + merkezî e-posta ağı (yalnız pasife ait adres) + önbellek (posta başına sorgu yok).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class InactiveRecipientGuardTest {

    @Mock AppUserRepository userRepo;
    InactiveRecipientGuard guard;

    @BeforeEach
    void setUp() {
        guard = new InactiveRecipientGuard(userRepo);
        when(userRepo.findInactiveIdsAndEmails()).thenReturn(List.of());
        when(userRepo.findActiveEmailsLowerIn(anyCollection())).thenReturn(List.of());
    }

    private void passive(Object[]... rows) {
        when(userRepo.findInactiveIdsAndEmails()).thenReturn(Arrays.asList(rows));
        guard.evict();
    }

    private static EscalationContact contact(long id, Long userId, String email) {
        EscalationContact c = new EscalationContact();
        c.setId(id); c.setUserId(userId); c.setEmail(email); c.setName("Kişi " + id); c.setActive(true);
        return c;
    }

    private static MimeMessage mail(String[] to, String[] cc) throws Exception {
        MimeMessage m = new MimeMessage(Session.getInstance(new Properties()));
        m.setSubject("[Site Monitor] KRİTİK · test");
        if (to != null) m.setRecipients(Message.RecipientType.TO, InternetAddress.parse(String.join(",", to)));
        if (cc != null) m.setRecipients(Message.RecipientType.CC, InternetAddress.parse(String.join(",", cc)));
        return m;
    }

    private static List<String> addrs(MimeMessage m, Message.RecipientType type) throws Exception {
        List<String> out = new ArrayList<>();
        if (m.getRecipients(type) != null) for (var a : m.getRecipients(type)) out.add(((InternetAddress) a).getAddress());
        return out;
    }

    @Test
    @DisplayName("Pasif kullanıcı YOK: kişi listesi AYNI nesne, e-posta mesajına dokunulmaz (bayt bayt bugünkü yol)")
    void noPassiveUsers_isNoop() throws Exception {
        List<EscalationContact> cs = List.of(contact(1, 10L, "a@x.com"), contact(2, null, "team@x.com"));
        assertThat(guard.withoutInactive(cs, "test")).isSameAs(cs);
        assertThat(InactiveRecipientGuard.droppedCount(cs)).isZero();

        MimeMessage m = mail(new String[]{"a@x.com", "team@x.com"}, null);
        assertThat(guard.filter(m).skipStatus()).isNull();
        assertThat(guard.filter(m).dropped()).isZero();
        assertThat(addrs(m, Message.RecipientType.TO)).containsExactly("a@x.com", "team@x.com");
    }

    @Test
    @DisplayName("Kişi düzeyi: user_id'si pasif kullanıcıya bağlı kişi düşer, bağsız (serbest metin) kişi kalır; düşen sayı listede taşınır")
    void contactLinkedToPassiveUser_dropped() {
        passive(new Object[]{500L, "gone@x.com"});
        EscalationContact passiveMgr = contact(1, 500L, "gone@x.com");
        EscalationContact activeTech = contact(2, 10L, "tech@x.com");
        EscalationContact freeText = contact(3, null, "oncall@x.com");

        List<EscalationContact> out = guard.withoutInactive(List.of(passiveMgr, activeTech, freeText), "test");

        assertThat(out).containsExactly(activeTech, freeText);
        assertThat(InactiveRecipientGuard.droppedCount(out)).isEqualTo(1);
        assertThat(guard.isInactiveContact(passiveMgr)).isTrue();
        assertThat(guard.isInactiveContact(freeText)).isFalse();
    }

    @Test
    @DisplayName("Merkezî ağ: yalnız pasife ait adres düşer; aynı adresi AKTİF biri de kullanıyorsa ve takım kutusu kalır")
    void mailFilter_dropsOnlyPassiveOnlyAddresses() throws Exception {
        passive(new Object[]{500L, "Gone@X.com "}, new Object[]{501L, "shared@x.com"});
        when(userRepo.findActiveEmailsLowerIn(anyCollection())).thenReturn(List.of("shared@x.com"));
        guard.evict();

        MimeMessage m = mail(new String[]{"team@x.com", "gone@x.com", "shared@x.com"}, new String[]{"GONE@x.com"});
        InactiveRecipientGuard.MailFilterResult r = guard.filter(m);

        assertThat(r.skipStatus()).isNull();
        assertThat(r.dropped()).isEqualTo(2);
        assertThat(addrs(m, Message.RecipientType.TO)).containsExactly("team@x.com", "shared@x.com");
        assertThat(addrs(m, Message.RecipientType.CC)).isEmpty();
        assertThat(guard.isInactiveOnlyEmail("gone@x.com")).isTrue();
        assertThat(guard.isInactiveOnlyEmail("shared@x.com")).isFalse();
    }

    @Test
    @DisplayName("Merkezî ağ: TÜM alıcılar pasife aitse gönderim yapılmaz → SKIPPED: pasif kullanıcı")
    void mailFilter_allPassive_skipStatus() throws Exception {
        passive(new Object[]{500L, "gone@x.com"});
        MimeMessage m = mail(new String[]{"gone@x.com"}, null);

        InactiveRecipientGuard.MailFilterResult r = guard.filter(m);

        assertThat(r.skipStatus()).isEqualTo(InactiveRecipientGuard.STATUS_SKIPPED).isEqualTo("SKIPPED: pasif kullanıcı");
    }

    @Test
    @DisplayName("Önbellek: TTL içinde posta başına sorgu YOK; evict() sonrası tazelenir (aktiflik değişimi bu pod'da anında)")
    void cache_noQueryPerMail_evictRefreshes() throws Exception {
        passive(new Object[]{500L, "gone@x.com"});
        for (int i = 0; i < 5; i++) guard.filter(mail(new String[]{"team@x.com"}, null));
        guard.isInactiveUser(500L);
        verify(userRepo, times(1)).findInactiveIdsAndEmails();

        when(userRepo.findInactiveIdsAndEmails()).thenReturn(List.of());   // kullanıcı yeniden aktifleştirildi
        assertThat(guard.isInactiveUser(500L)).isTrue();                     // TTL içinde eski görüntü
        guard.evict();
        assertThat(guard.isInactiveUser(500L)).isFalse();
        verify(userRepo, times(2)).findInactiveIdsAndEmails();
    }

    @Test
    @DisplayName("Okuma hatası: süzgeç yok (fail-open) — DB hıçkırığı alarm postasını düşürmez")
    void readFailure_failsOpen() throws Exception {
        when(userRepo.findInactiveIdsAndEmails()).thenThrow(new RuntimeException("db down"));
        MimeMessage m = mail(new String[]{"gone@x.com"}, null);
        assertThat(guard.filter(m).skipStatus()).isNull();
        assertThat(addrs(m, Message.RecipientType.TO)).containsExactly("gone@x.com");
    }
}
