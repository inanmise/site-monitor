package com.sitemonitor.controller;

import com.sitemonitor.model.AlertEvent;
import com.sitemonitor.repository.AlertEventRepository;
import com.sitemonitor.service.EscalationService;
import com.sitemonitor.service.PermissionService;
import com.sitemonitor.service.dbquery.DbQueryAlertRowsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpSession;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** {@code GET /api/monitoring/dbquery/alerts/{id}/rows} — alarm anındaki sorgu sonucu; paylaşımla okuyana yalnız sayı. */
class DbQueryAlertRowsControllerTest {

    PermissionService perms;
    AlertEventRepository alerts;
    DbQueryAlertRowsService rows;
    DbQueryAlertRowsController ctl;

    static final DbQueryAlertRowsService.Rows STORED = new DbQueryAlertRowsService.Rows("2026-10-31T09:00:00",
            List.of("id", "tutar"), List.of(List.of("1", "100")), List.of("tutar"), 25, false, false);

    @BeforeEach
    void setUp() {
        perms = mock(PermissionService.class);
        alerts = mock(AlertEventRepository.class);
        rows = mock(DbQueryAlertRowsService.class);
        ctl = new DbQueryAlertRowsController(perms, alerts, rows);
        when(rows.stored(anyLong())).thenReturn(Optional.of(STORED));
    }

    static AlertEvent alarm(long id, String type, Long team) {
        AlertEvent e = new AlertEvent();
        e.setId(id);
        e.setAlertType(type);
        e.setTeamId(team);
        return e;
    }

    static MockHttpSession user(Long team, List<Long> grantView) {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("authenticated", Boolean.TRUE);
        s.setAttribute("systemRole", "USER");
        s.setAttribute("teamId", team);
        s.setAttribute("viewTeamIds", List.of(team));
        s.setAttribute("memberTeamIds", List.of(team));
        s.setAttribute("manageTeamIds", List.of());
        if (grantView != null) s.setAttribute(SessionScope.ATTR_GRANT_VIEW, grantView);
        return s;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> data(ResponseEntity<Map<String, Object>> r) {
        return (Map<String, Object>) r.getBody().get("data");
    }

    @Test
    @DisplayName("takım üyesi: satırlar + kolonlar + maske listesi; yanıt no-store")
    void member_seesRows() {
        when(alerts.findById(1L)).thenReturn(Optional.of(alarm(1L, EscalationService.TYPE_DBQUERY_THRESHOLD, 7L)));
        var r = ctl.rows(1L, user(7L, null));
        assertThat(r.getHeaders().getCacheControl()).contains("no-store");
        assertThat(data(r)).containsEntry("hidden", false).containsEntry("row_count", 25)
                .containsEntry("columns", List.of("id", "tutar")).containsEntry("masked", List.of("tutar"));
        assertThat(data(r).get("rows")).isEqualTo(List.of(List.of("1", "100")));
    }

    @Test
    @DisplayName("yalnız erişim paylaşımıyla okuyan: satır VERİSİ yok, yalnız sayı (hidden)")
    void grantOnly_hidden() {
        when(alerts.findById(2L)).thenReturn(Optional.of(alarm(2L, EscalationService.TYPE_DBQUERY_FAIL, 9L)));
        var r = ctl.rows(2L, user(7L, List.of(9L)));
        assertThat(data(r)).containsEntry("hidden", true).containsEntry("row_count", 25)
                .doesNotContainKeys("rows", "columns", "masked");
    }

    @Test
    @DisplayName("başka takımın alarmı → 403; alarm yok → 404; izin yok → istisna, depo okunmaz")
    void scopeAndErrors() {
        when(alerts.findById(3L)).thenReturn(Optional.of(alarm(3L, EscalationService.TYPE_DBQUERY_SLOW, 9L)));
        assertThatThrownBy(() -> ctl.rows(3L, user(7L, null))).isInstanceOf(SecurityException.class);
        when(alerts.findById(4L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> ctl.rows(4L, user(7L, null))).isInstanceOf(NoSuchElementException.class);

        MockHttpSession s = user(7L, null);
        doThrow(new SecurityException("izin")).when(perms).require(s, "alerts.read", "view");
        assertThatThrownBy(() -> ctl.rows(1L, s)).isInstanceOf(SecurityException.class);
        verify(alerts, never()).findById(1L);
    }

    @Test
    @DisplayName("sorgu alarmı değilse ya da kayıt yoksa data null (eski alarm / satırlar maile eklenmiyor)")
    void nonDbQueryOrNoRows_null() {
        when(alerts.findById(5L)).thenReturn(Optional.of(alarm(5L, EscalationService.TYPE_HTTP_DOWN, 7L)));
        assertThat(ctl.rows(5L, user(7L, null)).getBody()).containsEntry("data", null);
        when(alerts.findById(6L)).thenReturn(Optional.of(alarm(6L, EscalationService.TYPE_DBQUERY_THRESHOLD, 7L)));
        when(rows.stored(6L)).thenReturn(Optional.empty());
        assertThat(ctl.rows(6L, user(7L, null)).getBody()).containsEntry("data", null);
    }

    @Test
    @DisplayName("İnceleme K4: 7/24 operatörü alarmı okur ama satırları GÖRMEZ (hidden + sayı); kendi takımının alarmında tam; sahipsiz alarmda gizli")
    void nocOperator_hiddenUnlessOwnTeam() {
        var noc = mock(com.sitemonitor.service.noc.NocCallLogService.class);
        org.springframework.test.util.ReflectionTestUtils.setField(ctl, "nocCallLog", noc);
        MockHttpSession op = user(5L, null);
        op.setAttribute(SessionScope.ATTR_NOC_OPERATOR, Boolean.TRUE);
        op.setAttribute(SessionScope.ATTR_NOC_TEAM_IDS, List.of(5L));
        when(noc.seesAllAlerts(op)).thenReturn(true);

        // Başka takımın alarmı: erişim var (alarm okunur), satır verisi YOK
        when(alerts.findById(8L)).thenReturn(Optional.of(alarm(8L, EscalationService.TYPE_DBQUERY_THRESHOLD, 9L)));
        assertThat(data(ctl.rows(8L, op))).containsEntry("hidden", true).containsEntry("row_count", 25)
                .doesNotContainKeys("rows", "columns", "masked");
        // Operatörün KENDİ takımının alarmı: kendi kapsamı → tam
        when(alerts.findById(9L)).thenReturn(Optional.of(alarm(9L, EscalationService.TYPE_DBQUERY_THRESHOLD, 5L)));
        assertThat(data(ctl.rows(9L, op))).containsEntry("hidden", false);
        // Sahipsiz (takımsız) alarm: yalnız global görüntüleyici tam görür
        when(alerts.findById(10L)).thenReturn(Optional.of(alarm(10L, EscalationService.TYPE_DBQUERY_FAIL, null)));
        assertThat(data(ctl.rows(10L, op))).containsEntry("hidden", true);
    }

    @Test
    @DisplayName("global görüntüleyici (AUDIT) tüm alarmların satırlarını görür")
    void globalViewer_seesAll() {
        when(alerts.findById(7L)).thenReturn(Optional.of(alarm(7L, EscalationService.TYPE_DBQUERY_THRESHOLD, 99L)));
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("authenticated", Boolean.TRUE);
        s.setAttribute("systemRole", "AUDIT");
        assertThat(data(ctl.rows(7L, s))).containsEntry("hidden", false);
    }
}
