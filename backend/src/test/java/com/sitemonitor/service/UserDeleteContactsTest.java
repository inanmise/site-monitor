package com.sitemonitor.service;

import com.sitemonitor.model.AppUser;
import com.sitemonitor.model.EscalationContact;
import com.sitemonitor.repository.AppUserRepository;
import com.sitemonitor.repository.CertificateInventoryRepository;
import com.sitemonitor.repository.EscalationContactRepository;
import com.sitemonitor.repository.PasswordHistoryRepository;
import com.sitemonitor.repository.TeamRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * H paketi B1 (2026-11-26): kullanıcı silinince ona bağlı eskalasyon kişileri AYNI silmede pasife alınır ve nedeni
 * ({@code deactivated_reason = 'USER_DELETED'}) yazılır; alıcı süzgeci silinmiş kullanıcıya bağlı (eski veri) kişiyi de
 * düşürür. Gerçek SQL (H2), işlem DIŞINDA — hedefli UPDATE'in kendi işlemini açtığı ve başka kullanıcıya yeniden bağlanan
 * kişiye yazmadığı kanıtlanır.
 */
@DataJpaTest
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class UserDeleteContactsTest {

    @Autowired AppUserRepository userRepo;
    @Autowired TeamRepository teamRepo;
    @Autowired CertificateInventoryRepository inventoryRepo;
    @Autowired EscalationContactRepository contactRepo;
    @Autowired PasswordHistoryRepository passwordHistoryRepo;

    private final List<Long> contacts = new ArrayList<>();
    private final List<Long> users = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        for (Long id : contacts) if (contactRepo.existsById(id)) contactRepo.deleteById(id);
        for (Long id : users) if (userRepo.existsById(id)) userRepo.deleteById(id);
        contacts.clear();
        users.clear();
    }

    @Test
    @DisplayName("deleteUser: bağlı kişiler pasif + USER_DELETED (bağ korunur); başka kullanıcının ve bağsız kişi dokunulmaz")
    void deleteUser_deactivatesLinkedContacts() {
        AppUser u = user("H_DEL_U1");
        AppUser v = user("H_DEL_V1");
        EscalationContact active = contact(u.getId(), "Ayşe", true);
        EscalationContact alreadyOff = contact(u.getId(), "Ayşe eski", false);
        EscalationContact other = contact(v.getId(), "Veli", true);
        EscalationContact free = contact(null, "Takım kutusu", true);

        UserService.DeleteImpact impact = service().deleteUser(u.getId());

        assertThat(userRepo.existsById(u.getId())).isFalse();
        EscalationContact a = contactRepo.findById(active.getId()).orElseThrow();
        assertThat(a.getActive()).isFalse();
        assertThat(a.getDeactivatedReason()).isEqualTo(EscalationContact.DEACTIVATED_USER_DELETED);
        assertThat(a.getUserId()).as("bağ korunur — arayüz ve süzgeç silinmiş kullanıcıyı oradan bilir").isEqualTo(u.getId());
        assertThat(a.getEmail()).isEqualTo("ayse@example.com");
        EscalationContact b = contactRepo.findById(alreadyOff.getId()).orElseThrow();
        assertThat(b.getActive()).isFalse();
        assertThat(b.getDeactivatedReason()).isEqualTo(EscalationContact.DEACTIVATED_USER_DELETED);
        assertThat(contactRepo.findById(other.getId()).orElseThrow().getActive()).isTrue();
        assertThat(contactRepo.findById(other.getId()).orElseThrow().getDeactivatedReason()).isNull();
        assertThat(contactRepo.findById(free.getId()).orElseThrow().getActive()).isTrue();

        assertThat(impact.deactivatedContacts()).extracting(UserService.DeactivatedContact::id)
                .containsExactlyInAnyOrder(active.getId(), alreadyOff.getId());
        assertThat(impact.deactivatedContacts()).filteredOn(c -> c.id().equals(active.getId()))
                .singleElement().extracting(UserService.DeactivatedContact::wasActive).isEqualTo(true);
        assertThat(impact.deactivatedContacts()).filteredOn(c -> c.id().equals(alreadyOff.getId()))
                .singleElement().extracting(UserService.DeactivatedContact::wasActive).isEqualTo(false);
    }

    @Test
    @DisplayName("hedefli UPDATE koşullu: okuma ile yazma arasında başka kullanıcıya bağlanan kişiye yazılmaz (0 satır)")
    void deactivate_isConditionalOnTheLink() {
        AppUser u = user("H_DEL_U2");
        AppUser v = user("H_DEL_V2");
        EscalationContact c = contact(u.getId(), "Ayşe", true);
        // Eşzamanlı tekil düzenleme: kişi bu arada başka kullanıcıya bağlandı
        EscalationContact edit = contactRepo.findById(c.getId()).orElseThrow();
        edit.setUserId(v.getId());
        contactRepo.save(edit);

        assertThat(contactRepo.deactivateForDeletedUser(c.getId(), u.getId(), EscalationContact.DEACTIVATED_USER_DELETED)).isZero();
        EscalationContact now = contactRepo.findById(c.getId()).orElseThrow();
        assertThat(now.getActive()).isTrue();
        assertThat(now.getDeactivatedReason()).isNull();
        assertThat(now.getUserId()).isEqualTo(v.getId());
    }

    @Test
    @DisplayName("eski veri: silinmiş kullanıcıya bağlı ETKİN kişi — findDanglingUserIds onu bulur, alıcı süzgeci düşürür")
    void guard_dropsContactsOfDeletedUsers() {
        AppUser u = user("H_DEL_U3");
        EscalationContact legacy = contact(u.getId(), "Eski kişi", true);
        EscalationContact live = contact(user("H_DEL_V3").getId(), "Canlı", true);
        // Eski yol: kullanıcı silinmiş, kişi etkin kalmış (bu sürümden önceki silme)
        userRepo.deleteById(u.getId());

        // Paylaşılan H2 bağlamında başka testlerin artığı olabilir — yalnız kendi satırlarımız denetlenir.
        assertThat(contactRepo.findDanglingUserIds()).contains(u.getId()).doesNotContain(live.getUserId());

        InactiveRecipientGuard withLayer = new InactiveRecipientGuard(userRepo);
        withLayer.setContactRepo(contactRepo);
        List<EscalationContact> both = List.of(contactRepo.findById(legacy.getId()).orElseThrow(),
                contactRepo.findById(live.getId()).orElseThrow());
        List<EscalationContact> out = withLayer.withoutInactive(both, "test");
        assertThat(out).extracting(EscalationContact::getId).containsExactly(live.getId());
        assertThat(InactiveRecipientGuard.droppedCount(out)).isEqualTo(1);
        assertThat(withLayer.isInactiveContact(both.get(0))).isTrue();

        // Kişi deposu olmayan (el yapımı) süzgeç bugünkü gibi davranır
        InactiveRecipientGuard legacyGuard = new InactiveRecipientGuard(userRepo);
        assertThat(legacyGuard.withoutInactive(both, "test")).isSameAs(both);
    }

    private UserService service() {
        return new UserService(userRepo, teamRepo, inventoryRepo, contactRepo, passwordHistoryRepo);
    }

    private AppUser user(String username) {
        AppUser u = new AppUser();
        u.setUsername(username);
        u.setDisplayName(username);
        u.setEmail(username.toLowerCase() + "@example.com");
        u.setSystemRole("USER");
        u.setActive(true);
        u = userRepo.save(u);
        users.add(u.getId());
        return u;
    }

    private EscalationContact contact(Long userId, String name, boolean active) {
        EscalationContact c = new EscalationContact();
        c.setUserId(userId);
        c.setName(name);
        c.setEmail("ayse@example.com");
        c.setRole("TECH");
        c.setMinAlertLevel("WARNING");
        c.setTeamId(1L);
        c.setActive(active);
        c = contactRepo.save(c);
        contacts.add(c.getId());
        return c;
    }
}
