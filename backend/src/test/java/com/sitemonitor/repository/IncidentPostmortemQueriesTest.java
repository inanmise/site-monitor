package com.sitemonitor.repository;

import com.sitemonitor.model.IncidentActionItem;
import com.sitemonitor.model.IncidentPostmortem;
import com.sitemonitor.model.IncidentRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Postmortem + aksiyon sorguları (2026-10-11) — H2 entegrasyonu. JPQL'in kendisi (CASE toplamları, iki varlıklı birleşim,
 * kapsam / sahip VEYA'sı, son tarih sırası, koşullu sahiplenme) burada sınanır; mock'lu servis testleri bunu göremez.
 * Yazan sorgular (sahiplenme, silme) kendi işlemlerini taşımalı — {@code NOT_SUPPORTED} ile test edilir.
 */
@DataJpaTest
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
class IncidentPostmortemQueriesTest {

    private static final String TODAY = "2026-10-11";

    @Autowired IncidentRecordRepository incidents;
    @Autowired IncidentActionItemRepository actions;
    @Autowired IncidentPostmortemRepository postmortems;

    private IncidentRecord incident(String title, Long teamId, Long createdByTeamId) {
        IncidentRecord r = new IncidentRecord();
        r.setTitle(title);
        r.setOccurredAt("2026-10-01T08:00:00");
        r.setSeverity("HIGH");
        r.setStatus("RESOLVED");
        r.setCategory("APPLICATION");
        r.setSlaBreached(false);
        r.setTeamId(teamId);
        r.setCreatedByTeamId(createdByTeamId);
        r.setTeamName("Takım " + teamId);
        return incidents.save(r);
    }

    private IncidentActionItem action(Long incidentId, String title, String status, String due, Long owner) {
        IncidentActionItem a = new IncidentActionItem();
        a.setIncidentId(incidentId);
        a.setTitle(title);
        a.setKind("PREVENT");
        a.setPriority("MEDIUM");
        a.setStatus(status);
        a.setDueDate(due);
        a.setOwnerUserId(owner);
        a.setOwnerName(owner == null ? null : "Sahip " + owner);
        a.setCreatedAt("2026-10-02T08:00:00");
        return actions.save(a);
    }

    private List<String> titles(Page<Object[]> p) {
        return p.getContent().stream().map(r -> ((IncidentActionItem) r[0]).getTitle()).toList();
    }

    @Test
    @DisplayName("liste rozetleri: açık / gecikmiş / biten / toplam ve postmortem durumu sayfa için tek sorguda")
    void countsAndStatus() {
        IncidentRecord a = incident("A", 5L, 5L);
        IncidentRecord b = incident("B", 5L, 5L);
        action(a.getId(), "a1", "OPEN", "2026-10-01", null);          // gecikmiş
        action(a.getId(), "a2", "IN_PROGRESS", "2026-10-20", null);   // açık
        action(a.getId(), "a3", "DONE", "2026-09-01", null);          // biten (gecikmiş sayılmaz)
        action(a.getId(), "a4", "CANCELLED", null, null);
        IncidentPostmortem pm = new IncidentPostmortem();
        pm.setIncidentId(b.getId());
        pm.setStatus("PUBLISHED");
        postmortems.save(pm);

        Map<Long, Object[]> rows = actions.countsFor(List.of(a.getId(), b.getId()), TODAY).stream()
                .collect(Collectors.toMap(r -> ((Number) r[0]).longValue(), r -> r));
        assertThat(rows).containsOnlyKeys(a.getId());
        Object[] r = rows.get(a.getId());
        assertThat(List.of(num(r[1]), num(r[2]), num(r[3]), num(r[4]))).containsExactly(2L, 1L, 1L, 4L);
        assertThat(postmortems.statusFor(List.of(a.getId(), b.getId())))
                .singleElement().satisfies(x -> assertThat(x[1]).isEqualTo("PUBLISHED"));
    }

    @Test
    @DisplayName("olaylar arası liste: kapsam VEYA sahip; durum / bana atanan / gecikmiş / arama; son tarihli önce")
    void searchScopeAndFilters() {
        IncidentRecord own = incident("Ödeme kesintisi", 5L, 5L);
        IncidentRecord foreign = incident("Başka takım olayı", 9L, 9L);
        IncidentRecord enteredByUs = incident("Bizim girdiğimiz", 9L, 5L);
        action(own.getId(), "Alarm eşiği", "OPEN", "2026-10-20", null);
        action(own.getId(), "Runbook yaz", "OPEN", "2026-10-05", 77L);
        action(own.getId(), "Tarihsiz iş", "IN_PROGRESS", null, null);
        action(own.getId(), "Bitti", "DONE", "2026-10-01", null);
        action(foreign.getId(), "Yabancı iş", "OPEN", "2026-10-03", null);
        action(foreign.getId(), "Bana atanan yabancı iş", "OPEN", "2026-10-30", 77L);
        action(enteredByUs.getId(), "Girdiğimiz olayın işi", "OPEN", "2026-10-25", null);
        List<String> open = List.of("OPEN", "IN_PROGRESS");

        Page<Object[]> scoped = actions.search(true, List.of(5L), 77L, false, open, false, TODAY, "", PageRequest.of(0, 50));
        assertThat(titles(scoped)).containsExactly("Runbook yaz", "Alarm eşiği", "Girdiğimiz olayın işi", "Bana atanan yabancı iş", "Tarihsiz iş");
        assertThat(scoped.getContent().get(0)[1]).isEqualTo("Ödeme kesintisi");
        // 2026-10-12: satır olayın takım + giren takım kimliğini de taşır ("Olayı aç" okuma kapısı sunucuda hesaplanır)
        assertThat(scoped.getContent().get(0)).hasSize(6);

        assertThat(titles(actions.search(true, List.of(-1L), null, false, open, false, TODAY, "", PageRequest.of(0, 50)))).isEmpty();
        assertThat(titles(actions.search(false, List.of(-1L), null, false, open, false, TODAY, "", PageRequest.of(0, 50)))).hasSize(6);
        assertThat(titles(actions.search(true, List.of(5L), 77L, true, open, false, TODAY, "", PageRequest.of(0, 50))))
                .containsExactly("Runbook yaz", "Bana atanan yabancı iş");
        assertThat(titles(actions.search(true, List.of(5L), 77L, false, open, true, TODAY, "", PageRequest.of(0, 50))))
                .containsExactly("Runbook yaz");
        assertThat(titles(actions.search(true, List.of(5L), 77L, false, List.of("DONE", "CANCELLED"), false, TODAY, "", PageRequest.of(0, 50))))
                .containsExactly("Bitti");
        assertThat(titles(actions.search(true, List.of(5L), 77L, false, open, false, TODAY, "%ödeme%", PageRequest.of(0, 50))))
                .containsExactly("Runbook yaz", "Alarm eşiği", "Tarihsiz iş");
        assertThat(actions.search(true, List.of(5L), 77L, false, open, false, TODAY, "", PageRequest.of(0, 2)).getTotalElements()).isEqualTo(5);

        Object[] t = actions.totals(true, List.of(5L), 77L, TODAY).get(0);
        assertThat(List.of(num(t[0]), num(t[1]), num(t[2]))).containsExactly(5L, 1L, 2L);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName("hatırlatma: adaylar açık + sahipli + son tarihi yarına kadar; sahiplenme koşullu ve kendi işleminde")
    void reminderClaims() {
        IncidentRecord inc = incident("H", 5L, 5L);
        try {
            IncidentActionItem due = action(inc.getId(), "yarın", "OPEN", "2026-10-12", 1L);
            action(inc.getId(), "sahipsiz", "OPEN", "2026-10-01", null);
            action(inc.getId(), "uzak", "OPEN", "2026-11-01", 1L);
            action(inc.getId(), "bitmiş", "DONE", "2026-10-01", 1L);
            assertThat(actions.reminderCandidates("2026-10-12")).extracting(IncidentActionItem::getTitle).containsExactly("yarın");

            assertThat(actions.claimFirstReminder(due.getId(), TODAY)).isEqualTo(1);
            assertThat(actions.claimFirstReminder(due.getId(), TODAY)).isZero();        // ikinci pod
            assertThat(actions.claimNextReminder(due.getId(), "2026-10-01", "2026-10-12")).isZero();   // eski önceki değer
            assertThat(actions.claimNextReminder(due.getId(), TODAY, "2026-10-12")).isEqualTo(1);
            assertThat(actions.findById(due.getId()).orElseThrow().getRemindedOn()).isEqualTo("2026-10-12");

            IncidentPostmortem pm = new IncidentPostmortem();
            pm.setIncidentId(inc.getId());
            pm.setStatus("DRAFT");
            postmortems.save(pm);
            assertThat(actions.deleteForIncident(inc.getId())).isEqualTo(4);
            assertThat(postmortems.deleteForIncident(inc.getId())).isEqualTo(1);
        } finally {
            actions.deleteAll();
            postmortems.deleteAll();
            incidents.deleteAll();
        }
    }

    @Test
    @DisplayName("postmortem sürümü kayıtta artar (eşzamanlı düzenleme koruması)")
    void versionIncrements() {
        IncidentRecord inc = incident("V", 5L, 5L);
        IncidentPostmortem pm = new IncidentPostmortem();
        pm.setIncidentId(inc.getId());
        pm.setStatus("DRAFT");
        pm = postmortems.saveAndFlush(pm);
        Long v0 = pm.getVersion();
        pm.setSummary("özet");
        pm = postmortems.saveAndFlush(pm);
        assertThat(pm.getVersion()).isGreaterThan(v0);
        assertThat(postmortems.findByIncidentId(inc.getId())).isPresent();
    }

    private static long num(Object o) { return o instanceof Number n ? n.longValue() : 0L; }
}
