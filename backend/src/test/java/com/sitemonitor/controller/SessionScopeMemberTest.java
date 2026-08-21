package com.sitemonitor.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ÜYELİK kapsamı ({@code memberTeamIds}) — şablon kütüphanesinin "takımın her üyesi düzenler"
 * kuralının dayanağı.
 *
 * <p>Bu kapsam 2026-08-20'de eklendi çünkü mevcut ikisi de bu soruyu cevaplayamıyordu:
 * {@code viewTeamIds} müdürde astların takımlarını içeriyor, {@code manageTeamIds} ise
 * USER'da BOŞ. İkisiyle de yazılan bir kural ya çok geniş ya çok dar olurdu.
 */
class SessionScopeMemberTest {

    private static MockHttpSession session(String role, List<Long> member, Long primaryTeam) {
        MockHttpSession s = new MockHttpSession();
        if (role != null) s.setAttribute("systemRole", role);
        if (member != null) s.setAttribute("memberTeamIds", new ArrayList<>(member));
        if (primaryTeam != null) s.setAttribute("teamId", primaryTeam);
        return s;
    }

    @Test
    @DisplayName("Üyelik listesi olduğu gibi okunur; çok-takımlı kullanıcı hepsini taşır")
    void readsMembershipList() {
        var s = session("USER", List.of(5L, 8L), 5L);
        assertThat(SessionScope.memberTeamIds(s)).containsExactly(5L, 8L);
        assertThat(SessionScope.isMemberOf(s, 5L)).isTrue();
        assertThat(SessionScope.isMemberOf(s, 8L)).isTrue();
        assertThat(SessionScope.isMemberOf(s, 9L)).isFalse();
    }

    @Test
    @DisplayName("ASLA null dönmez — yokluk 'kısıtsız' DEĞİL, 'hiçbir takımın üyesi değil' demektir")
    void neverReturnsNull() {
        assertThat(SessionScope.memberTeamIds(null)).isEmpty();
        assertThat(SessionScope.memberTeamIds(session("ADMIN", List.of(), null))).isEmpty();
        assertThat(SessionScope.isMemberOf(session("USER", List.of(), null), 5L)).isFalse();
        assertThat(SessionScope.isMemberOf(session("USER", List.of(5L), 5L), null)).isFalse();
    }

    /**
     * ROLLING DEPLOY: Spring Session JDBC oturumları pod ölümünden sağ çıkıyor. Yeni sürüme
     * geçerken eski pod'un ürettiği oturumlarda `memberTeamIds` YOKTUR. Bu testin koruduğu şey,
     * o kullanıcıların birkaç dakika boyunca "yetkiniz yok" duvarına toslamaması.
     */
    @Test
    @DisplayName("ESKİ oturumda nitelik yoksa birincil takıma düşülür — kullanıcı kilitlenmez")
    void fallsBackToPrimaryTeamForPreUpgradeSessions() {
        var old = new MockHttpSession();
        old.setAttribute("systemRole", "USER");
        old.setAttribute("teamId", 5L);          // memberTeamIds YOK (eski pod'un oturumu)

        assertThat(SessionScope.memberTeamIds(old)).containsExactly(5L);
        assertThat(SessionScope.isMemberOf(old, 5L)).isTrue();
        // Çok-takımlı kullanıcı geçici olarak DARALIR (ikinci takımı görünmez) ama kilitlenmez;
        // nitelik bir sonraki girişte doğru dolar. Daralma, kilitlenmeye tercih edilmiştir.
        assertThat(SessionScope.isMemberOf(old, 8L)).isFalse();
    }

    @Test
    @DisplayName("Ne nitelik ne birincil takım varsa boş liste — sessizce 'her şeye üye' olunmaz")
    void noAttributesMeansNoMembership() {
        var bare = new MockHttpSession();
        bare.setAttribute("systemRole", "ADMIN");
        assertThat(SessionScope.memberTeamIds(bare)).isEmpty();
        assertThat(SessionScope.isMemberOf(bare, 5L)).isFalse();
    }

    @Test
    @DisplayName("Üyelik YETKİ değildir: global admin bile yalnız kendi takımlarının üyesidir")
    void membershipIsNotAuthority() {
        // Global admin'in yetkisi isGlobalAdmin'den gelir; üyelik kapsamı onu genişletmez.
        var admin = session("ADMIN", List.of(5L), 5L);
        assertThat(SessionScope.isMemberOf(admin, 9L)).isFalse();
    }
}
