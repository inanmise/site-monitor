package com.sitemonitor.repository;

import com.sitemonitor.model.AlertEvent;
import com.sitemonitor.model.CertificateInventory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Alarm Geçmişi "aralıkta aktif olanlar" kipi (2026-09-28, regresyon B3) — GERÇEK SQL (H2, PostgreSQL uyumluluk modu).
 *
 * <p>Haftalık erişilebilirlik e-postası "Haftanın alarmları"nı hafta içinde AÇIK olan tüm alarmlar olarak sayar
 * (önceki haftadan devredenler dahil; {@code WeeklyOutageReportService.loadWeekAlarms} üç sorgunun birleşimi).
 * E-postanın bağlantısı Alarm Geçmişi'ni {@code range=active} ile açar; bu sınıf o kipin yüklemini
 * ({@code activeFrom}: açılış ≤ bitiş VE (açık YA DA çözüm ≥ başlangıç)) veritabanında sınar: devreden alarm
 * GİRER, pencereden önce çözülen ve pencereden sonra açılan GİRMEZ; varsayılan kip ("aralıkta açılanlar") birebir
 * eskisi gibi; takım kapsamı (damgalı takım + envanter SY/UG) iki kipte de aynı; sayaç sorguları listeyle aynı kümeyi
 * sayar; kip, haftalık raporun üç sorgulu birleşimiyle AYNI kümeyi verir (e-postadaki sayı = bağlantıdaki liste).
 *
 * <p>Sabit tarihler bilinçli: sorgu "şimdi"ye göre kayan bir pencere uygulamaz, pencere parametreyle verilir.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.datasource.url=jdbc:h2:mem:alertactiverange;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;NON_KEYWORDS=VALUE",
        "spring.datasource.driver-class-name=org.h2.Driver",
})
class AlertActiveRangeQueryTest {

    /** ISO 39. hafta, Türkiye saati Pzt 00:00 – Paz 23:59:59 → UTC damga (sunucunun sözlüksel biçimi). */
    private static final String FROM = "2026-09-20T21:00:00";
    private static final String TO = "2026-09-27T20:59:59";
    private static final long OWN = 5L, FOREIGN = 9L;
    private static final List<Long> SCOPE = List.of(OWN);
    private static final List<Long> NONE = List.of(-1L);
    private static final List<String> NO_TYPES = AlertEventRepository.NO_TYPE_SCOPE;
    private static final Pageable PAGE = PageRequest.of(0, 100, Sort.by("domain"));

    @Autowired AlertEventRepository alerts;
    @Autowired CertificateInventoryRepository inventory;

    private void save(String domain, Long teamId, String createdAt, String resolvedAt) {
        AlertEvent e = new AlertEvent();
        e.setDomain(domain);
        e.setTeamId(teamId);
        e.setAlertType("HTTP_DOWN");
        e.setAlertLevel("CRITICAL");
        e.setAcknowledged(false);
        e.setCreatedAt(createdAt);
        e.setResolved(resolvedAt != null);
        e.setResolvedAt(resolvedAt);
        alerts.save(e);
    }

    @BeforeEach
    void seed() {
        save("in-window.example.com", OWN, "2026-09-22T08:00:00", "2026-09-22T09:00:00");        // hafta içinde açıldı + çözüldü
        save("in-window-open.example.com", OWN, "2026-09-26T08:00:00", null);                    // hafta içinde açıldı, hâlâ açık
        save("carried-open.example.com", OWN, "2026-09-10T06:00:00", null);                      // DEVREDEN, hâlâ açık
        save("carried-resolved.example.com", OWN, "2026-09-15T06:00:00", "2026-09-23T10:00:00"); // DEVREDEN, hafta içinde çözüldü
        save("carried-through.example.com", OWN, "2026-09-15T06:00:00", "2026-09-30T10:00:00");  // DEVREDEN, haftadan sonra çözüldü
        save("resolved-at-start.example.com", OWN, "2026-09-18T06:00:00", FROM);                // sınır: tam başlangıç anında çözüldü
        save("resolved-before.example.com", OWN, "2026-09-10T06:00:00", "2026-09-18T06:00:00");  // haftadan ÖNCE çözüldü
        save("opened-after.example.com", OWN, "2026-09-29T06:00:00", null);                      // haftadan SONRA açıldı
        save("foreign-carried.example.com", FOREIGN, "2026-09-10T06:00:00", null);               // yabancı takım, devreden
        save("inv-own-carried.example.com", null, "2026-09-12T06:00:00", null);                  // damgasız; envanter SY kendi
        CertificateInventory i = new CertificateInventory();
        i.setDomain("inv-own-carried.example.com"); i.setPort(443); i.setActive(true); i.setTeamId(OWN);
        inventory.save(i);
    }

    private List<String> list(String since, String activeFrom, boolean scoped, List<Long> scope, Long teamId) {
        return alerts.findFiltered(null, since, TO, null, null, activeFrom, null, null, false, NO_TYPES,
                null, null, null, teamId, scoped, scope, PAGE).getContent().stream().map(AlertEvent::getDomain).toList();
    }

    private static final List<String> ACTIVE_OWN = List.of(
            "carried-open.example.com", "carried-resolved.example.com", "carried-through.example.com",
            "in-window-open.example.com", "in-window.example.com", "inv-own-carried.example.com",
            "resolved-at-start.example.com");

    @Test
    @DisplayName("aktif kip: devreden (açık / hafta içinde çözülen / haftadan sonra çözülen) GİRER; önce çözülen ve sonra açılan GİRMEZ")
    void activeModeIncludesCarriedOver() {
        assertThat(list(null, FROM, true, SCOPE, null)).containsExactlyElementsOf(ACTIVE_OWN);
    }

    @Test
    @DisplayName("varsayılan kip DEĞİŞMEDİ: yalnız hafta içinde AÇILANLAR (devreden yok)")
    void defaultModeIsOpenedInRange() {
        assertThat(list(FROM, null, true, SCOPE, null))
                .containsExactly("in-window-open.example.com", "in-window.example.com");
    }

    @Test
    @DisplayName("kapsam iki kipte de aynı: yabancı takımın devreden alarmı kapsamlı sorguda YOK, global sorguda VAR; takım süzgeci = kapsam")
    void scopeIsolationHolds() {
        assertThat(list(null, FROM, true, SCOPE, null)).doesNotContain("foreign-carried.example.com");
        assertThat(list(null, FROM, false, NONE, null)).contains("foreign-carried.example.com").hasSize(ACTIVE_OWN.size() + 1);
        // E-posta bağlantısının &team= süzgeci (global görüntüleyici için): kapsamla aynı küme, envanter SY dahil
        assertThat(list(null, FROM, false, NONE, OWN)).containsExactlyElementsOf(ACTIVE_OWN);
        // Takım süzgeci kapsamı GENİŞLETMEZ: kapsamı OWN olan kullanıcı FOREIGN süzgeciyle hiçbir şey görmez
        assertThat(list(null, FROM, true, SCOPE, FOREIGN)).isEmpty();
    }

    @Test
    @DisplayName("sayaçlar listeyle aynı kümeyi sayar (tip + seviye/sahiplenme fasetleri) — aktif kipte de")
    void countsFollowActiveMode() {
        long byType = alerts.countFilteredByType(null, null, TO, null, null, FROM, null, false, NO_TYPES,
                null, null, null, null, true, SCOPE).stream().mapToLong(r -> (Long) r[1]).sum();
        long facets = alerts.countFacets(null, null, TO, null, null, FROM, null, null, false, NO_TYPES,
                null, null, true, SCOPE).stream().mapToLong(r -> (Long) r[2]).sum();
        assertThat(byType).isEqualTo(ACTIVE_OWN.size());
        assertThat(facets).isEqualTo(ACTIVE_OWN.size());
        long byTypeDefault = alerts.countFilteredByType(null, FROM, TO, null, null, null, null, false, NO_TYPES,
                null, null, null, null, true, SCOPE).stream().mapToLong(r -> (Long) r[1]).sum();
        assertThat(byTypeDefault).isEqualTo(2);
    }

    @Test
    @DisplayName("aktif kip durum süzgeciyle birleşir: yalnız açıklar / yalnız kapalılar")
    void activeModeCombinesWithStatus() {
        List<String> open = alerts.findFiltered(Boolean.FALSE, null, TO, null, null, FROM, null, null, false, NO_TYPES,
                null, null, null, null, true, SCOPE, PAGE).getContent().stream().map(AlertEvent::getDomain).toList();
        assertThat(open).containsExactly("carried-open.example.com", "in-window-open.example.com", "inv-own-carried.example.com");
        List<String> closed = alerts.findFiltered(Boolean.TRUE, null, TO, null, null, FROM, null, null, false, NO_TYPES,
                null, null, null, null, true, SCOPE, PAGE).getContent().stream().map(AlertEvent::getDomain).toList();
        assertThat(closed).containsExactly("carried-resolved.example.com", "carried-through.example.com",
                "in-window.example.com", "resolved-at-start.example.com");
    }

    @Test
    @DisplayName("PARİTE: aktif kip = haftalık raporun üç sorgulu birleşimi (e-postadaki alarm sayısı = bağlantının listesi)")
    void activeModeMatchesWeeklyReportUnion() {
        // WeeklyOutageReportService.loadWeekAlarms ile BİREBİR aynı üç çağrı (eski 10'lu imza)
        Set<String> union = new TreeSet<>();
        for (var p : List.of(
                alerts.findFiltered(null, FROM, TO, null, null, null, null, true, SCOPE, PAGE),
                alerts.findFiltered(false, null, FROM, null, null, null, null, true, SCOPE, PAGE),
                alerts.findFiltered(true, null, FROM, FROM, null, null, null, true, SCOPE, PAGE))) {
            p.getContent().forEach(e -> union.add(e.getDomain()));
        }
        assertThat(union).containsExactlyElementsOf(list(null, FROM, true, SCOPE, null));
    }
}
