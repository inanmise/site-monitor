package com.sitemonitor.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * KAPSAMLI YÖNETİCİ, TOPLU "ŞİMDİ KONTROL ET" (2026-09-29 prod olayı).
 *
 * <p>AD kaynaklı müdür {@code systemRole=ADMIN} ile gelir ama {@code viewTeamIds}/{@code manageTeamIds} doludur —
 * GLOBAL DEĞİLDİR. Dokuz türün tetik ucu kaydın (etkin) takımı üzerinde
 * {@link SessionScope#canOperateTeam} sorar; toplu kontrol istemci tarafı fan-out olduğundan AYNI kapıdan geçer.
 * Liste satırındaki {@code can_check} bayrağı da bu kuraldan türer (arayüz sayacı ve toplu kuyruk onu kullanır) —
 * kural değişirse bayrak ile uç ayrışmasın diye tek kaynak burada pinlenir.
 */
class ScopedAdminManualCheckGateTest {

    private static MockHttpSession session(String role, List<Long> view, List<Long> manage, Long primary) {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("authenticated", Boolean.TRUE);
        s.setAttribute("username", "u");
        s.setAttribute("systemRole", role);
        if (view != null) s.setAttribute("viewTeamIds", new ArrayList<>(view));
        if (manage != null) s.setAttribute("manageTeamIds", new ArrayList<>(manage));
        if (primary != null) s.setAttribute("teamId", primary);
        return s;
    }

    @Test
    @DisplayName("Kapsamlı müdür (ADMIN + kapsam [2]) başka takımın (3) izlemesini ÇALIŞTIRAMAZ; kendi takımını çalıştırır")
    void scopedAdmin_cannotOperateForeignTeam() {
        MockHttpSession mudur = session("ADMIN", List.of(2L), List.of(2L), 2L);
        assertThat(SessionScope.isGlobalAdmin(mudur)).isFalse();
        assertThat(SessionScope.canOperateTeam(mudur, 2L)).isTrue();
        assertThat(SessionScope.canOperateTeam(mudur, 3L)).isFalse();
        assertThat(SessionScope.canOperateTeam(mudur, null)).as("sahipsiz kayıt").isFalse();
    }

    @Test
    @DisplayName("Global yönetici her takımın izlemesini çalıştırır (kapsam listesi YOK)")
    void globalAdmin_operatesEveryTeam() {
        MockHttpSession admin = session("ADMIN", null, null, null);
        assertThat(SessionScope.isGlobalAdmin(admin)).isTrue();
        assertThat(SessionScope.canOperateTeam(admin, 3L)).isTrue();
    }

    @Test
    @DisplayName("Takım yöneticisi (TEAM_ADMIN) yönettiği ama ÜYESİ olmadığı takımı da çalıştırır; yönetmediğini çalıştıramaz")
    void teamAdmin_operatesManagedTeams() {
        MockHttpSession lead = session("TEAM_ADMIN", List.of(2L, 4L), List.of(2L, 4L), 2L);
        assertThat(SessionScope.canOperateTeam(lead, 4L)).isTrue();
        assertThat(SessionScope.canOperateTeam(lead, 3L)).isFalse();
    }
}
