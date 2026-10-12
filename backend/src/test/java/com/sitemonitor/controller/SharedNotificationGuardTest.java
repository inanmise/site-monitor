package com.sitemonitor.controller;

import com.sitemonitor.model.HttpMonitor;
import com.sitemonitor.model.PingMonitor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Takım erişim paylaşımı (2026-10-12, inceleme 4): bildirim YÖNLENDİRMESİ paylaşılmaz. Kayda yalnız paylaşımla yazan kişinin
 * gövdesindeki bildirim alanları kayıtlı değerle aynıysa gövdeden düşer (tam yük gönderen form), farklıysa 403; kendi
 * kapsamıyla yazan kullanıcıda hiçbir şey değişmez. Varsayılan eşdeğerleri (null e-posta = açık, null 7/24 = kapalı, null
 * seviye = WARNING, grup kimliği metin/sayı, 7/24 grupları sırasız) aynı sayılır.
 */
class SharedNotificationGuardTest {

    private static final long OWN = 1L, SHARED = 2L;

    /** Takım 1 üyesi; takım 2'ye EDIT paylaşımı. */
    private static MockHttpSession grantee() {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("authenticated", Boolean.TRUE);
        s.setAttribute("systemRole", "USER");
        s.setAttribute("teamId", OWN);
        s.setAttribute("viewTeamIds", List.of(OWN));
        s.setAttribute("memberTeamIds", List.of(OWN));
        s.setAttribute("manageTeamIds", List.of());
        s.setAttribute(SessionScope.ATTR_GRANT_VIEW, List.of(SHARED));
        s.setAttribute(SessionScope.ATTR_GRANT_EXECUTE, List.of(SHARED));
        s.setAttribute(SessionScope.ATTR_GRANT_EDIT, List.of(SHARED));
        return s;
    }

    private static HttpMonitor stored() {
        HttpMonitor m = new HttpMonitor();
        m.setTeamId(SHARED);
        m.setNotifyEmail(null);          // null = açık
        m.setNotifyWebhook(true);
        m.setNotificationGroupId(12L);
        m.setNocNotify(null);            // null = kapalı
        m.setNocGroupIds("2,1");
        m.setAlertLevel(null);           // null = WARNING
        return m;
    }

    private static Map<String, Object> fullFormBody() {
        Map<String, Object> b = new HashMap<>();
        b.put("name", "yeni ad");
        b.put("notifyEmail", true);
        b.put("notifyWebhook", true);
        b.put("notificationGroupId", "12");
        b.put("nocNotify", false);
        b.put("nocGroupIds", List.of(1, 2));
        b.put("alertLevel", "WARNING");
        return b;
    }

    @Test
    @DisplayName("paylaşımla yazan + değişmemiş bildirim alanları: gövdeden düşer, diğer alanlar kalır, hata yok")
    void unchangedFieldsAreDropped() {
        Map<String, Object> body = fullFormBody();
        MonitoringController.guardSharedNotificationFields(grantee(), SHARED, body, stored());
        assertThat(body).containsOnlyKeys("name");
    }

    @Test
    @DisplayName("paylaşımla yazan + her bildirim alanının değişimi 403")
    void eachChangeIsRejected() {
        for (Map.Entry<String, Object> change : Map.<String, Object>of(
                "notifyEmail", false,
                "notifyWebhook", false,
                "notificationGroupId", 13,
                "nocNotify", true,
                "nocGroupIds", List.of(1),
                "alertLevel", "CRITICAL").entrySet()) {
            Map<String, Object> body = fullFormBody();
            body.put(change.getKey(), change.getValue());
            assertThatThrownBy(() -> MonitoringController.guardSharedNotificationFields(grantee(), SHARED, body, stored()))
                    .as(change.getKey())
                    .isInstanceOf(SecurityException.class);
        }
        // grup kaldırma (null) da bir değişikliktir
        Map<String, Object> body = fullFormBody();
        body.put("notificationGroupId", null);
        assertThatThrownBy(() -> MonitoringController.guardSharedNotificationFields(grantee(), SHARED, body, stored()))
                .isInstanceOf(SecurityException.class);
    }

    @Test
    @DisplayName("kendi kapsamıyla yazan kullanıcıda gövde DEĞİŞMEZ (bildirim alanları serbest)")
    void ownScopeUntouched() {
        Map<String, Object> body = fullFormBody();
        body.put("notifyEmail", false);
        body.put("alertLevel", "CRITICAL");
        HttpMonitor own = stored();
        own.setTeamId(OWN);
        MonitoringController.guardSharedNotificationFields(grantee(), OWN, body, own);
        assertThat(body).containsEntry("notifyEmail", false).containsEntry("alertLevel", "CRITICAL").hasSize(7);
    }

    @Test
    @DisplayName("türün taşımadığı alan (varlıkta yok) sessizce düşer; varsayılanlı tür (Ping) aynı değerde düşer")
    void unknownPropertyDropped() {
        Map<String, Object> body = new HashMap<>();
        body.put("notifyEmail", false);
        body.put("alertLevel", "CRITICAL");
        MonitoringController.guardSharedNotificationFields(grantee(), SHARED, body, new Object());
        assertThat(body).isEmpty();
        Map<String, Object> ping = new HashMap<>();
        ping.put("notifyWebhook", true);
        ping.put("nocGroupIds", null);
        PingMonitor stored = new PingMonitor();
        stored.setTeamId(SHARED);
        MonitoringController.guardSharedNotificationFields(grantee(), SHARED, ping, stored);
        assertThat(ping).isEmpty();
    }

    @Test
    @DisplayName("eşdeğerler: null/true e-posta, null/false 7/24, null/WARNING/geçersiz seviye, sayı/metin grup, sırasız 7/24 grupları")
    void equivalences() {
        assertThat(MonitoringController.sameNotifyValue("notifyEmail", null, true)).isTrue();
        assertThat(MonitoringController.sameNotifyValue("notifyEmail", false, null)).isFalse();
        assertThat(MonitoringController.sameNotifyValue("nocNotify", null, false)).isTrue();
        assertThat(MonitoringController.sameNotifyValue("nocNotify", true, null)).isFalse();
        assertThat(MonitoringController.sameNotifyValue("alertLevel", "WARNING", null)).isTrue();
        assertThat(MonitoringController.sameNotifyValue("alertLevel", "warning", "WARNING")).isTrue();
        assertThat(MonitoringController.sameNotifyValue("alertLevel", "HIGH", null)).isFalse();
        assertThat(MonitoringController.sameNotifyValue("notificationGroupId", "12", 12L)).isTrue();
        assertThat(MonitoringController.sameNotifyValue("notificationGroupId", "", null)).isTrue();
        assertThat(MonitoringController.sameNotifyValue("notificationGroupId", "x", null)).isFalse();
        assertThat(MonitoringController.sameNotifyValue("nocGroupIds", List.of(2, 1), "1,2")).isTrue();
        assertThat(MonitoringController.sameNotifyValue("nocGroupIds", List.of(), null)).isTrue();
        assertThat(MonitoringController.sameNotifyValue("nocGroupIds", List.of(3), "1,2")).isFalse();
    }

    @Test
    @DisplayName("inceleme 6: alan adı kritik gün eşiği alarm SEVİYESİNİ belirler — paylaşımla değiştirilemez; aynı değer düşer, "
            + "sayı olmayan gönderim (uygulanmaz) zararsız; en az 1 kuralı aynı")
    void criticalDaysIsRouting() {
        assertThat(MonitoringController.sameNotifyValue("criticalDays", 7, 7)).isTrue();
        assertThat(MonitoringController.sameNotifyValue("criticalDays", 3650, 7)).isFalse();
        assertThat(MonitoringController.sameNotifyValue("criticalDays", 0, 1)).isTrue();      // applyDomainFields en az 1 yazar
        assertThat(MonitoringController.sameNotifyValue("criticalDays", "3650", 7)).isTrue(); // sayı değil → uygulanmaz
        assertThat(MonitoringController.sameNotifyValue("criticalDays", 7, null)).isTrue();   // kayıtlı null = 7 (inceleme 7)
        assertThat(MonitoringController.sameNotifyValue("criticalDays", 14, null)).isFalse();
        com.sitemonitor.model.DomainMonitor stored = new com.sitemonitor.model.DomainMonitor();
        stored.setTeamId(SHARED);
        stored.setCriticalDays(7);
        Map<String, Object> body = new HashMap<>();
        body.put("criticalDays", 3650);
        assertThatThrownBy(() -> MonitoringController.guardSharedNotificationFields(grantee(), SHARED, body, stored))
                .isInstanceOf(SecurityException.class);
        Map<String, Object> same = new HashMap<>();
        same.put("criticalDays", 7);
        same.put("warningDays", 60);                                    // uyarı eşiği operasyoneldir — serbest
        MonitoringController.guardSharedNotificationFields(grantee(), SHARED, same, stored);
        assertThat(same).containsOnlyKeys("warningDays");
        // kendi kapsamıyla yazan serbest
        Map<String, Object> own = new HashMap<>();
        own.put("criticalDays", 30);
        stored.setTeamId(OWN);
        MonitoringController.guardSharedNotificationFields(grantee(), OWN, own, stored);
        assertThat(own).containsEntry("criticalDays", 30);
    }
}
