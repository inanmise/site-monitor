package com.sitemonitor.repository;

import com.sitemonitor.controller.AdminController;
import com.sitemonitor.model.AlertEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AlertEventRepository entegrasyon testleri (H2). M3: çift açık alarmda findOpenAlert güvenli;
 * M6: linkToStormIfOpen yalnız açık + bağsız satırı günceller (çözülmüş incident'i diriltmez).
 */
@DataJpaTest
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
class AlertEventRepositoryTest {

    @Autowired AlertEventRepository repo;
    @Autowired TeamRepository teamRepo;
    @Autowired CertificateInventoryRepository invRepo;

    private AlertEvent alert(String domain, String type, String createdAt, boolean resolved) {
        AlertEvent e = new AlertEvent();
        e.setDomain(domain);
        e.setAlertType(type);
        e.setAlertLevel("CRITICAL");
        e.setAcknowledged(false);
        e.setResolved(resolved);
        e.setCreatedAt(createdAt);
        return e;
    }

    @Test
    @DisplayName("findOpenAlert returns the newest and does NOT throw on duplicate open alerts (M3)")
    void findOpenAlert_multipleOpen_returnsNewestNoThrow() {
        repo.save(alert("dup.example.com", "HTTP_DOWN", "2026-07-01T10:00:00", false));
        repo.save(alert("dup.example.com", "HTTP_DOWN", "2026-07-02T10:00:00", false));   // newer

        // Before the fix, the single-Optional @Query threw IncorrectResultSizeDataAccessException here.
        Optional<AlertEvent> result = repo.findOpenAlert("dup.example.com", "HTTP_DOWN");

        assertThat(result).isPresent();
        assertThat(result.get().getCreatedAt()).isEqualTo("2026-07-02T10:00:00");   // newest
    }

    @Test
    @DisplayName("findOpenAlert is empty when nothing is open (M3)")
    void findOpenAlert_none_empty() {
        repo.save(alert("closed.example.com", "HTTP_DOWN", "2026-07-01T10:00:00", true)); // resolved
        assertThat(repo.findOpenAlert("closed.example.com", "HTTP_DOWN")).isEmpty();
    }

    @Test
    @DisplayName("stampNotificationSentIfOpen (2026-10-09): yalnız gönderim alanları, yalnız AÇIK satır — çözülmüş satır dirilmez, onay korunur")
    void stampNotificationSentIfOpen_onlyOpenRow_onlySendFields() {
        AlertEvent open = alert("stamp-open.example.com", "EXPIRY", "2026-07-01T10:00:00", false);
        open.setAcknowledgedNote("not");
        open = repo.save(open);
        AlertEvent resolved = alert("stamp-closed.example.com", "EXPIRY", "2026-07-01T10:00:00", true);
        resolved.setResolvedBy("kullanici");
        resolved = repo.save(resolved);

        assertThat(repo.stampNotificationSentIfOpen(open.getId(), "2026-07-02T10:00:00", 2, 5, "2026-07-07T00:00:00", "[]"))
                .isEqualTo(1);
        assertThat(repo.stampNotificationSentIfOpen(resolved.getId(), "2026-07-02T10:00:00", 2, 5, null, null))
                .isZero();

        AlertEvent o = repo.findById(open.getId()).orElseThrow();
        assertThat(o.getLastReAlertAt()).isEqualTo("2026-07-02T10:00:00");
        assertThat(o.getRealertCount()).isEqualTo(2);
        assertThat(o.getDaysRemaining()).isEqualTo(5);
        assertThat(o.getNotAfter()).isEqualTo("2026-07-07T00:00:00");
        assertThat(o.getAcknowledgedNote()).isEqualTo("not");   // gönderim dışı alan dokunulmadı
        AlertEvent r = repo.findById(resolved.getId()).orElseThrow();
        assertThat(r.getResolved()).isTrue();
        assertThat(r.getResolvedBy()).isEqualTo("kullanici");
        assertThat(r.getLastReAlertAt()).isNull();
    }

    @Test
    @DisplayName("linkToStormIfOpen links an OPEN unlinked alert but NOT a resolved one — no resurrection (M6)")
    void linkToStormIfOpen_onlyOpenUnlinked() {
        AlertEvent open     = repo.save(alert("open.example.com",   "HTTP_DOWN", "2026-07-01T10:00:00", false));
        AlertEvent resolved = repo.save(alert("closed.example.com", "HTTP_DOWN", "2026-07-01T10:00:00", true));

        assertThat(repo.linkToStormIfOpen(open.getId(), 42L)).isEqualTo(1);
        assertThat(repo.linkToStormIfOpen(resolved.getId(), 42L)).isZero();   // resolved satır dokunulmaz

        assertThat(repo.findById(open.getId()).orElseThrow().getStormId()).isEqualTo(42L);
        AlertEvent stillResolved = repo.findById(resolved.getId()).orElseThrow();
        assertThat(stillResolved.getStormId()).isNull();
        assertThat(stillResolved.getResolved()).isTrue();   // full-save ile resolved=false'a DÖNMEDİ (dirilmedi)
    }

    @Test
    @DisplayName("linkToStormIfOpen skips an already-linked alert (M6)")
    void linkToStormIfOpen_skipsAlreadyLinked() {
        AlertEvent e = alert("x.example.com", "HTTP_DOWN", "2026-07-01T10:00:00", false);
        e.setStormId(7L);
        e = repo.save(e);
        assertThat(repo.linkToStormIfOpen(e.getId(), 99L)).isZero();          // storm_id NOT NULL → dokunma
        assertThat(repo.findById(e.getId()).orElseThrow().getStormId()).isEqualTo(7L);
    }

    @Test
    @DisplayName("unlinkFromStorm clears only an OPEN member's link, leaves a resolved one (M6)")
    void unlinkFromStorm_onlyOpen() {
        AlertEvent open = alert("o.example.com", "HTTP_DOWN", "2026-07-01T10:00:00", false);
        open.setStormId(5L); open = repo.save(open);
        AlertEvent resolved = alert("r.example.com", "HTTP_DOWN", "2026-07-01T10:00:00", true);
        resolved.setStormId(5L); resolved = repo.save(resolved);

        assertThat(repo.unlinkFromStorm(open.getId())).isEqualTo(1);
        assertThat(repo.unlinkFromStorm(resolved.getId())).isZero();

        assertThat(repo.findById(open.getId()).orElseThrow().getStormId()).isNull();
        assertThat(repo.findById(resolved.getId()).orElseThrow().getStormId()).isEqualTo(5L);
    }

    // ── Alarm Geçmişi: sütun sıralaması + tarih yüklemleri (2026-10-01) ──────────────────────────────────

    private AlertEvent saved(String domain, String level, String createdAt, String resolvedAt, Long teamId) {
        AlertEvent e = alert(domain, "HTTP_DOWN", createdAt, resolvedAt != null);
        e.setAlertLevel(level);
        e.setResolvedAt(resolvedAt);
        e.setTeamId(teamId);
        return repo.save(e);
    }

    /** Süzgeçsiz findFiltered — yalnız görünüm (resolved) + tarih yüklemleri + sıralama. */
    private List<String> domains(Boolean resolved, String since, String until, String rs, String ru, Sort sort) {
        Page<AlertEvent> p = repo.findFiltered(resolved, since, until, rs, ru, null, null, null, false, List.of("-"),
                null, null, null, null, false, List.of(-1L), PageRequest.of(0, 50, sort));
        return p.getContent().stream().map(AlertEvent::getDomain).toList();
    }

    @Test
    @DisplayName("sort=level: CRITICAL > HIGH > WARNING (CASE sırası, alfabetik DEĞİL); aynı seviyede en yeni önce; asc tersi")
    void findFiltered_levelSort_usesSeverityRank() {
        saved("warn.example.com", "WARNING",  "2026-07-03T10:00:00", null, null);
        saved("crit-old.example.com", "CRITICAL", "2026-07-01T10:00:00", null, null);
        saved("high.example.com", "HIGH",     "2026-07-02T10:00:00", null, null);
        saved("crit-new.example.com", "CRITICAL", "2026-07-04T10:00:00", null, null);

        assertThat(domains(false, null, null, null, null, AdminController.AlertSort.of("level", "desc", false)))
                .containsExactly("crit-new.example.com", "crit-old.example.com", "high.example.com", "warn.example.com");
        assertThat(domains(false, null, null, null, null, AdminController.AlertSort.of("level", "asc", false)))
                .containsExactly("warn.example.com", "high.example.com", "crit-new.example.com", "crit-old.example.com");
    }

    @Test
    @DisplayName("sort=opened asc/desc; bilinmeyen anahtar varsayılana (açılış, en yeni önce) düşer")
    void findFiltered_openedSort_andUnknownKeyFallsBack() {
        saved("b.example.com", "HIGH", "2026-07-02T10:00:00", null, null);
        saved("a.example.com", "HIGH", "2026-07-01T10:00:00", null, null);
        saved("c.example.com", "HIGH", "2026-07-03T10:00:00", null, null);

        assertThat(domains(false, null, null, null, null, AdminController.AlertSort.of("opened", "asc", false)))
                .containsExactly("a.example.com", "b.example.com", "c.example.com");
        assertThat(domains(false, null, null, null, null, AdminController.AlertSort.of("opened", "desc", false)))
                .containsExactly("c.example.com", "b.example.com", "a.example.com");
        assertThat(domains(false, null, null, null, null, AdminController.AlertSort.of("DROP;TABLE", "sideways", false)))
                .containsExactly("c.example.com", "b.example.com", "a.example.com");
    }

    @Test
    @DisplayName("kapalı görünümde varsayılan kapanış anı (en yeni önce); sort=resolved asc tersi; sort=domain / type çalışır (team → ayrı test)")
    void findFiltered_resolvedTeamDomainTypeSort() {
        saved("x.example.com", "HIGH", "2026-07-01T10:00:00", "2026-07-05T10:00:00", 2L);
        saved("y.example.com", "HIGH", "2026-07-02T10:00:00", "2026-07-03T10:00:00", 1L);
        saved("z.example.com", "HIGH", "2026-07-03T10:00:00", "2026-07-04T10:00:00", 3L);

        assertThat(domains(true, null, null, null, null, AdminController.AlertSort.of(null, null, true)))
                .containsExactly("x.example.com", "z.example.com", "y.example.com");
        assertThat(domains(true, null, null, null, null, AdminController.AlertSort.of("resolved", "asc", true)))
                .containsExactly("y.example.com", "z.example.com", "x.example.com");
        assertThat(domains(true, null, null, null, null, AdminController.AlertSort.of("domain", "desc", true)))
                .containsExactly("z.example.com", "y.example.com", "x.example.com");
        assertThat(domains(true, null, null, null, null, AdminController.AlertSort.of("type", "asc", true)))
                .hasSize(3);
    }

    private Long teamNamed(String name) {
        com.sitemonitor.model.Team t = new com.sitemonitor.model.Team();
        t.setName(name); t.setActive(true);
        return teamRepo.save(t).getId();
    }

    @Test
    @DisplayName("sort=team (2026-10-01): takım ADINA göre (id'ye göre DEĞİL) — damgasız sertifika alarmı envanterin SY takım adıyla; aynı takımda en yeni önce; desc tersi")
    void findFiltered_teamSort_byName() {
        // Oluşturma (id) sırası Zeta < Alfa < Mavi < Beta; ad sırası Alfa < Beta < Mavi < Zeta — iki sıra FARKLI.
        Long zeta = teamNamed("Zeta"), alfa = teamNamed("Alfa"), mavi = teamNamed("Mavi"), beta = teamNamed("Beta");
        com.sitemonitor.model.CertificateInventory inv = new com.sitemonitor.model.CertificateInventory();
        inv.setDomain("cert.example.com"); inv.setPort(443); inv.setActive(true); inv.setTeamId(beta);
        invRepo.save(inv);

        saved("x.example.com", "HIGH", "2026-07-01T10:00:00", null, mavi);
        saved("y.example.com", "HIGH", "2026-07-02T10:00:00", null, zeta);
        saved("z.example.com", "HIGH", "2026-07-03T10:00:00", null, alfa);
        saved("z2.example.com", "HIGH", "2026-07-04T10:00:00", null, alfa);        // aynı takım, daha yeni → önce
        saved("cert.example.com", "HIGH", "2026-07-05T10:00:00", null, null);     // damgasız → envanter SY (Beta)

        assertThat(domains(false, null, null, null, null, AdminController.AlertSort.of("team", "asc", false)))
                .containsExactly("z2.example.com", "z.example.com", "cert.example.com", "x.example.com", "y.example.com");
        assertThat(domains(false, null, null, null, null, AdminController.AlertSort.of("team", "desc", false)))
                .containsExactly("y.example.com", "x.example.com", "cert.example.com", "z2.example.com", "z.example.com");

        // Sayfalama (COUNT sorgusu sıralamasız türetilir) ve kapsamlı sorgu (EXISTS takma adları) ile birlikte çalışır
        Page<AlertEvent> p = repo.findFiltered(false, null, null, null, null, null, null, null, false, List.of("-"),
                null, null, null, null, true, List.of(alfa, beta), PageRequest.of(0, 2, AdminController.AlertSort.of("team", "asc", false)));
        assertThat(p.getTotalElements()).isEqualTo(3);
        assertThat(p.getContent()).extracting(AlertEvent::getDomain).containsExactly("z2.example.com", "z.example.com");
    }

    @Test
    @DisplayName("since/until (açılış aralığı) AÇIK görünümde de uygulanır — 'son 1 saatte açılanlar'")
    void findFiltered_sinceUntil_applyInOpenView() {
        saved("early.example.com", "HIGH", "2026-07-01T10:00:00", null, null);
        saved("inside.example.com", "HIGH", "2026-07-05T10:00:00", null, null);
        saved("late.example.com", "HIGH", "2026-07-10T10:00:00", null, null);
        saved("closed-inside.example.com", "HIGH", "2026-07-05T11:00:00", "2026-07-06T00:00:00", null);

        assertThat(domains(false, "2026-07-04T00:00:00", "2026-07-06T23:59:59", null, null, AdminController.AlertSort.of(null, null, false)))
                .containsExactly("inside.example.com");
        // "Tümü" görünümü: açık + kapalı, açılış aralığında
        assertThat(domains(null, "2026-07-04T00:00:00", "2026-07-06T23:59:59", null, null, AdminController.AlertSort.of(null, null, null)))
                .containsExactly("closed-inside.example.com", "inside.example.com");
    }

    @Test
    @DisplayName("resolvedSince/resolvedUntil (kapanış aralığı) kapalı ve 'tümü' görünümünde uygulanır")
    void findFiltered_resolvedRange_closedAndAllViews() {
        saved("r1.example.com", "HIGH", "2026-07-01T10:00:00", "2026-07-02T10:00:00", null);
        saved("r2.example.com", "HIGH", "2026-07-01T10:00:00", "2026-07-08T10:00:00", null);
        saved("open.example.com", "HIGH", "2026-07-01T10:00:00", null, null);

        assertThat(domains(true, null, null, "2026-07-07T00:00:00", "2026-07-09T00:00:00", AdminController.AlertSort.of(null, null, true)))
                .containsExactly("r2.example.com");
        assertThat(domains(null, null, null, "2026-07-01T00:00:00", "2026-07-03T00:00:00", AdminController.AlertSort.of(null, null, null)))
                .containsExactly("r1.example.com");   // açık satırın resolvedAt'i null → aralığa girmez
    }
}
