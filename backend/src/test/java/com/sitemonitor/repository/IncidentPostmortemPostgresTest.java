package com.sitemonitor.repository;

import com.sitemonitor.it.PostgresIntegration;
import com.sitemonitor.it.PostgresIt;
import com.sitemonitor.model.IncidentActionItem;
import com.sitemonitor.model.IncidentPostmortem;
import com.sitemonitor.model.IncidentRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Postmortem + aksiyon sorguları (2026-10-11) GERÇEK PostgreSQL'de: CASE toplamları (bigint), iki varlıklı birleşim +
 * kapsam / sahip VEYA'sı, LOWER LIKE (olay başlığında), son tarih sırası (NULL sonda), koşullu sahiplenme ve benzersizlik kısıtı.
 */
@PostgresIntegration
class IncidentPostmortemPostgresTest {

    @Test
    @DisplayName("rozet sayımı, olaylar arası liste, sayaçlar ve hatırlatma sahiplenmesi PostgreSQL'de doğru")
    void queries() {
        IncidentRecordRepository incidents = PostgresIt.app().bean(IncidentRecordRepository.class);
        IncidentActionItemRepository actions = PostgresIt.app().bean(IncidentActionItemRepository.class);
        IncidentPostmortemRepository postmortems = PostgresIt.app().bean(IncidentPostmortemRepository.class);
        String tag = "pm-it-" + UUID.randomUUID().toString().substring(0, 8);
        long team = 876_540L + (long) (Math.random() * 1000);
        long me = 9_876_000L + (long) (Math.random() * 1000);

        IncidentRecord own = incidents.save(incident(tag + " Ödeme kesintisi", team));
        IncidentRecord foreign = incidents.save(incident(tag + " Başka", team + 1));
        try {
            actions.saveAll(List.of(
                    action(own.getId(), tag + "-a", "OPEN", "2026-10-05", me),
                    action(own.getId(), tag + "-b", "IN_PROGRESS", null, null),
                    action(own.getId(), tag + "-c", "DONE", "2026-09-01", null),
                    action(foreign.getId(), tag + "-d", "OPEN", "2026-10-30", me),
                    action(foreign.getId(), tag + "-e", "OPEN", "2026-10-02", null)));
            IncidentPostmortem pm = new IncidentPostmortem();
            pm.setIncidentId(own.getId());
            pm.setStatus("PUBLISHED");
            postmortems.save(pm);

            Object[] counts = actions.countsFor(List.of(own.getId()), "2026-10-11").get(0);
            assertThat(List.of(num(counts[1]), num(counts[2]), num(counts[3]), num(counts[4]))).containsExactly(2L, 1L, 1L, 3L);
            assertThat(postmortems.statusFor(List.of(own.getId(), foreign.getId()))).hasSize(1);

            var page = actions.search(true, List.of(team), me, false, List.of("OPEN", "IN_PROGRESS"), false, "2026-10-11",
                    "%" + tag.toLowerCase() + "%", PageRequest.of(0, 50));
            assertThat(page.getContent()).extracting(r -> ((IncidentActionItem) r[0]).getTitle())
                    .containsExactly(tag + "-a", tag + "-d", tag + "-b");
            assertThat(page.getTotalElements()).isEqualTo(3);
            // Olay başlığında arama (yerel ayardan bağımsız ASCII parça: C yerel ayarlı kümede LOWER('Ö') küçülmez)
            var byIncident = actions.search(true, List.of(team), me, false, List.of("OPEN", "IN_PROGRESS"), false, "2026-10-11",
                    "%" + tag.toLowerCase() + " %kesintisi%", PageRequest.of(0, 50));
            assertThat(byIncident.getContent()).extracting(r -> ((IncidentActionItem) r[0]).getTitle()).containsExactly(tag + "-a", tag + "-b");

            Object[] totals = actions.totals(true, List.of(team), me, "2026-10-11").get(0);
            assertThat(num(totals[2])).isGreaterThanOrEqualTo(2L);

            IncidentActionItem first = actions.findByIncidentIdOrderByIdAsc(own.getId()).get(0);
            assertThat(actions.claimFirstReminder(first.getId(), "2026-10-11")).isEqualTo(1);
            assertThat(actions.claimFirstReminder(first.getId(), "2026-10-11")).isZero();
            assertThat(actions.claimNextReminder(first.getId(), "2026-10-11", "2026-10-18")).isEqualTo(1);

            IncidentPostmortem dup = new IncidentPostmortem();
            dup.setIncidentId(own.getId());
            dup.setStatus("DRAFT");
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> postmortems.saveAndFlush(dup))
                    .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        } finally {
            actions.deleteForIncident(own.getId());
            actions.deleteForIncident(foreign.getId());
            postmortems.deleteForIncident(own.getId());
            incidents.deleteAllById(List.of(own.getId(), foreign.getId()));
        }
    }

    private static IncidentRecord incident(String title, long team) {
        IncidentRecord r = new IncidentRecord();
        r.setTitle(title);
        r.setOccurredAt("2026-10-01T08:00:00");
        r.setSeverity("HIGH");
        r.setStatus("RESOLVED");
        r.setCategory("APPLICATION");
        r.setSlaBreached(false);
        r.setTeamId(team);
        r.setCreatedByTeamId(team);
        return r;
    }

    private static IncidentActionItem action(Long incidentId, String title, String status, String due, Long owner) {
        IncidentActionItem a = new IncidentActionItem();
        a.setIncidentId(incidentId);
        a.setTitle(title);
        a.setKind("PREVENT");
        a.setPriority("MEDIUM");
        a.setStatus(status);
        a.setDueDate(due);
        a.setOwnerUserId(owner);
        a.setOwnerName(owner == null ? null : "Sahip");
        a.setCreatedAt("2026-10-02T08:00:00");
        return a;
    }

    private static long num(Object o) { return o instanceof Number n ? n.longValue() : 0L; }
}
