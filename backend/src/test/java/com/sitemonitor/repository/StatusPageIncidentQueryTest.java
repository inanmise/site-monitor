package com.sitemonitor.repository;

import com.sitemonitor.model.IncidentRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.TestPropertySource;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Durum Sayfası olay projeksiyonları (2026-10-01) — H2 entegrasyonu: JPQL (CASE ile önem sırası, sayfa tavanı, ISO metin
 * karşılaştırmalı çözülme penceresi, (kayıt takımı, giren takım) başına gruplu sayım) gerçek veritabanında çalışır;
 * projeksiyon YALNIZ özet sütunları + kapsam süzmesi için iki takım sütununu döndürür.
 */
@DataJpaTest
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
class StatusPageIncidentQueryTest {

    @Autowired IncidentRecordRepository repo;

    private IncidentRecord save(String title, String severity, String status, String occurredAt, String resolvedAt,
                                Long teamId, Long createdByTeamId) {
        IncidentRecord i = new IncidentRecord();
        i.setTitle(title); i.setSeverity(severity); i.setStatus(status); i.setCategory("APPLICATION");
        i.setOccurredAt(occurredAt); i.setResolvedAt(resolvedAt); i.setService("Ödeme API, Mobil");
        i.setTeamId(teamId); i.setTeamName(teamId == null ? null : "T" + teamId); i.setCreatedByTeamId(createdByTeamId);
        i.setDescription("GİZLİ teknik ayrıntı"); i.setRcaSummary("GİZLİ kök neden");
        return repo.save(i);
    }

    private IncidentRecord save(String title, String severity, String status, String occurredAt, String resolvedAt) {
        return save(title, severity, status, occurredAt, resolvedAt, 14L, 14L);
    }

    /** Gruplu sayımı "takım|giren → adet" metnine çevirir (sıra bağımsız karşılaştırma). */
    private static List<String> counts(List<Object[]> rows) {
        return rows.stream().map(r -> r[0] + "|" + r[1] + "→" + ((Number) r[2]).longValue()).sorted().toList();
    }

    @Test
    @DisplayName("Açık olaylar: çözülmemişler, önem (CRITICAL→LOW) sonra en yeni; tavan uygulanır; (takım, giren) sayımı; açıklama/kök neden projeksiyonda yok")
    void activeProjection() {
        save("düşük eski", "LOW", "OPEN", "2026-09-01T10:00:00", null);
        save("kritik eski", "CRITICAL", "INVESTIGATING", "2026-09-02T10:00:00", null, 99L, 14L);
        save("yüksek", "HIGH", "MITIGATED", "2026-09-28T10:00:00", null, 99L, 99L);
        save("kritik yeni", "CRITICAL", "OPEN", "2026-09-29T10:00:00", null);
        save("takımsız", "MEDIUM", "OPEN", "2026-09-27T10:00:00", null, null, null);
        save("çözülmüş", "CRITICAL", "RESOLVED", "2026-09-30T10:00:00", "2026-09-30T11:00:00");

        List<Object[]> rows = repo.statusPageActive(PageRequest.of(0, 50));
        assertThat(rows).extracting(r -> r[1]).containsExactly("kritik yeni", "kritik eski", "yüksek", "takımsız", "düşük eski");
        assertThat(rows.get(0)).hasSize(9);
        assertThat(Arrays.asList(rows.get(0)).subList(1, 9)).containsExactly("kritik yeni", "CRITICAL", "OPEN", "2026-09-29T10:00:00",
                "Ödeme API, Mobil", 14L, "T14", 14L);
        assertThat(rows.get(1)[6]).isEqualTo(99L);
        assertThat(rows.get(1)[8]).isEqualTo(14L);   // giren takım ayrı sütunda (kapsam süzmesi)
        assertThat(rows).allSatisfy(r -> assertThat(r).doesNotContain("GİZLİ teknik ayrıntı", "GİZLİ kök neden"));
        assertThat(repo.statusPageActive(PageRequest.of(0, 2))).hasSize(2);
        assertThat(counts(repo.statusPageActiveCounts())).containsExactly("14|14→2", "99|14→1", "99|99→1", "null|null→1");
    }

    @Test
    @DisplayName("Çözülenler: yalnız RESOLVED ve resolvedAt ≥ since (metin karşılaştırması), en son çözülen önce; gruplu sayım aynı pencere")
    void resolvedSince() {
        save("eski çözülen", "HIGH", "RESOLVED", "2026-09-01T10:00:00", "2026-09-01T12:00:00");
        save("yeni çözülen", "MEDIUM", "RESOLVED", "2026-09-29T08:00:00", "2026-09-29T09:30:00", 99L, 99L);
        save("daha yeni çözülen", "LOW", "RESOLVED", "2026-09-30T08:00:00", "2026-09-30T08:10:00Z");
        save("tarihsiz çözülen", "LOW", "RESOLVED", "2026-09-30T08:00:00", null);
        save("açık", "HIGH", "OPEN", "2026-09-30T08:00:00", null);

        List<Object[]> rows = repo.statusPageResolvedSince("2026-09-24T00:00:00", PageRequest.of(0, 50));
        assertThat(rows).extracting(r -> r[1]).containsExactly("daha yeni çözülen", "yeni çözülen");
        assertThat(rows.get(1)).hasSize(10);
        assertThat(rows.get(1)[4]).isEqualTo("2026-09-29T09:30:00");
        assertThat(rows.get(1)[9]).isEqualTo(99L);
        assertThat(counts(repo.statusPageResolvedSinceCounts("2026-09-24T00:00:00"))).containsExactly("14|14→1", "99|99→1");
    }
}
