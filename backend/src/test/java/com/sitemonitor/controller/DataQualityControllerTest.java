package com.sitemonitor.controller;

import com.sitemonitor.service.PermissionService;
import com.sitemonitor.service.noc.NocType;
import com.sitemonitor.service.quality.DataQualityService;
import com.sitemonitor.service.quality.DataQualitySource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code /api/data-quality} kapısı ve kapsamı: izin, görüş kapsamı (izleme okuma kuralı — 7/24 operatörü tüm takımlar),
 * Sahipsiz kovası yalnız tümünü görene, satır bayrakları kaydın KENDİ yazma kapısından (operatör bayrağı yazmayı açmaz).
 */
@SuppressWarnings("unchecked")
class DataQualityControllerTest {

    PermissionService perms;
    DataQualityController controller;

    @BeforeEach
    void setUp() {
        DataQualitySource source = mock(DataQualitySource.class);
        List<com.sitemonitor.model.CertificateInventory> inv = new ArrayList<>();
        inv.add(row(10, "a.example.com", 1L));
        inv.add(row(20, "b.example.com", 2L));
        inv.add(row(30, "orphan.example.com", null));
        List<DataQualitySource.MonitorFact> mons = List.of(
                new DataQualitySource.MonitorFact(NocType.PING, 5, "p", "h", 2L, true, true, null, "h", null));
        DataQualitySource.Facts facts = new DataQualitySource.Facts(
                List.of(new DataQualitySource.TeamFact(1, "A", true, true, true, true, false, false),
                        new DataQualitySource.TeamFact(2, "B", true, true, true, true, false, false)),
                Map.of(1L, 2, 2L, 2),
                Map.of(1L, new DataQualitySource.Escalation(true, true), 2L, new DataQualitySource.Escalation(true, true)),
                Set.of(), inv, new HashMap<>(), new HashMap<>(), new HashMap<>(), mons, Map.of(),
                new DataQualitySource.NocState(false, "NO_ACTIVE_GROUP", null, false));
        when(source.load(any())).thenReturn(facts);
        perms = mock(PermissionService.class);
        controller = new DataQualityController(new DataQualityService(source, mock(JdbcTemplate.class), 60_000), perms);
    }

    private static com.sitemonitor.model.CertificateInventory row(long id, String domain, Long team) {
        com.sitemonitor.model.CertificateInventory r = new com.sitemonitor.model.CertificateInventory();
        r.setId(id);
        r.setDomain(domain);
        r.setPort(443);
        r.setTeamId(team);
        r.setActive(true);
        r.setTier(null);   // her kayıtta bir bulgu (INV_NO_TIER) — satır bayrakları görünsün
        return r;
    }

    private static MockHttpSession session(String role, List<Long> view, List<Long> manage, List<Long> member, boolean noc) {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("systemRole", role);
        if (view != null) s.setAttribute("viewTeamIds", new ArrayList<>(view));
        if (manage != null) s.setAttribute("manageTeamIds", new ArrayList<>(manage));
        if (member != null) s.setAttribute("memberTeamIds", new ArrayList<>(member));
        if (noc) s.setAttribute(SessionScope.ATTR_NOC_OPERATOR, Boolean.TRUE);
        return s;
    }

    private Map<String, Object> summary(MockHttpSession s) {
        return (Map<String, Object>) controller.summary(false, s).getBody().get("data");
    }

    private Map<String, Object> detail(MockHttpSession s, String key) {
        return (Map<String, Object>) controller.team(key, false, s).getBody().get("data");
    }

    private static Set<Object> teamIds(Map<String, Object> data) {
        Set<Object> ids = new HashSet<>();
        for (Map<String, Object> t : (List<Map<String, Object>>) data.get("teams")) ids.add(t.get("id"));
        return ids;
    }

    private static Map<String, Object> firstItem(Map<String, Object> detail, String code) {
        for (Map<String, Object> r : (List<Map<String, Object>>) detail.get("rules")) {
            if (code.equals(r.get("code"))) return ((List<Map<String, Object>>) r.get("items")).get(0);
        }
        throw new AssertionError("kural yok: " + code);
    }

    @Test
    @DisplayName("izin: data_quality.view/view her iki uçta da sorulur; reddedilirse 403 (SecurityException) yayılır")
    void permissionGate() {
        MockHttpSession s = session("USER", List.of(1L), List.of(), List.of(1L), false);
        summary(s);
        verify(perms).require(s, "data_quality.view", "view");
        doThrow(new SecurityException("yok")).when(perms).require(any(jakarta.servlet.http.HttpSession.class), eq("data_quality.view"), eq("view"));
        assertThatThrownBy(() -> controller.summary(false, s)).isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> controller.team("1", false, s)).isInstanceOf(SecurityException.class);
    }

    @Test
    @DisplayName("kapsamlı USER: yalnız kendi takımı; Sahipsiz yok; başka takımın ayrıntısı 404")
    void scopedUser() {
        MockHttpSession s = session("USER", List.of(1L), List.of(), List.of(1L), false);
        Map<String, Object> data = summary(s);
        assertThat(teamIds(data)).containsExactly(1L);
        assertThat(data).doesNotContainKey("unassigned");
        assertThatThrownBy(() -> controller.team("2", false, s)).isInstanceOf(NoSuchElementException.class);
        assertThatThrownBy(() -> controller.team("unassigned", false, s)).isInstanceOf(NoSuchElementException.class);
        // üye: envanter kaydını düzeltebilir; takım ayarı yönetim kapsamı ister
        assertThat(firstItem(detail(s, "1"), "INV_NO_TIER")).containsEntry("can_edit", true);
    }

    @Test
    @DisplayName("AUDIT (global görüntüleyici): tüm takımlar + Sahipsiz; hiçbir satırı düzeltemez")
    void audit() {
        MockHttpSession s = session("AUDIT", null, null, List.of(), false);
        Map<String, Object> data = summary(s);
        assertThat(teamIds(data)).containsExactlyInAnyOrder(1L, 2L);
        assertThat(data).containsKey("unassigned");
        assertThat(firstItem(detail(s, "2"), "INV_NO_TIER")).containsEntry("can_edit", false);
        assertThat(firstItem(detail(s, "unassigned"), "INV_NO_TEAM")).containsEntry("can_edit", false);
    }

    @Test
    @DisplayName("7/24 operatörü: okuma tüm takımlar + Sahipsiz; yazma bayrağı GENİŞLEMEZ (başka takımın satırı düzeltilemez)")
    void nocOperator() {
        MockHttpSession s = session("USER", List.of(1L), List.of(), List.of(1L), true);
        Map<String, Object> data = summary(s);
        assertThat(teamIds(data)).containsExactlyInAnyOrder(1L, 2L);
        assertThat(data).containsKey("unassigned");
        assertThat(firstItem(detail(s, "2"), "INV_NO_TIER")).containsEntry("can_edit", false);
        assertThat(firstItem(detail(s, "2"), "MON_NO_GROUP")).containsEntry("can_edit", false);
        assertThat(firstItem(detail(s, "1"), "INV_NO_TIER")).containsEntry("can_edit", true);
    }

    @Test
    @DisplayName("global admin: her şey görünür ve düzeltilebilir (Sahipsiz dahil)")
    void globalAdmin() {
        MockHttpSession s = session("ADMIN", null, null, List.of(), false);
        assertThat(teamIds(summary(s))).containsExactlyInAnyOrder(1L, 2L);
        assertThat(firstItem(detail(s, "unassigned"), "INV_NO_TEAM")).containsEntry("can_edit", true);
    }

    @Test
    @DisplayName("kapsamlı müdür: görüş alanındaki takımlar; yönetim kapsamındaki takımın ayarı düzeltilebilir")
    void scopedManager() {
        MockHttpSession s = session("ADMIN", List.of(1L, 2L), List.of(2L), List.of(), false);
        Map<String, Object> data = summary(s);
        assertThat(teamIds(data)).containsExactlyInAnyOrder(1L, 2L);
        assertThat(data).doesNotContainKey("unassigned");
        assertThat(firstItem(detail(s, "2"), "INV_NO_TIER")).containsEntry("can_edit", true);
        assertThat(firstItem(detail(s, "1"), "INV_NO_TIER")).containsEntry("can_edit", false);
    }

    @Test
    @DisplayName("yanıtta kullanıcı kimliği alanı yok (opak kimlik kuralı kendiliğinden sağlanır)")
    void noUserRefs() {
        String json = String.valueOf(summary(session("ADMIN", null, null, List.of(), false)))
                + detail(session("ADMIN", null, null, List.of(), false), "1");
        for (String k : List.of("user_id", "manager_id", "leader_id", "actor_id")) assertThat(json).doesNotContain(k + "=");
    }
}
