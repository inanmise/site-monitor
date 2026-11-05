package com.sitemonitor.repository;

import com.sitemonitor.model.KeywordResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Anahtar kelime içerik uçlarının salt okuma sorguları GERÇEK Spring Data JPA / H2 üzerinde (kw son inceleme ŞÜPHE, 2026-11-05):
 * JPQL {@code LIMIT :limit} parametresi bağlanır ve en yeni satırları döner (sıra azalan), aralık süzer; {@code LIMIT 1}'li son
 * değişim satırı / son satır sorguları; {@code change_note} kolonu (O-3 / D-7) kalıcıdır. {@code NOT_SUPPORTED}: işlem sarmalı
 * olmadan.
 */
@DataJpaTest
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class KeywordContentQueryRepositoryTest {

    @Autowired KeywordContentQueryRepository queries;
    @Autowired KeywordResultRepository results;

    @AfterEach
    void cleanUp() {
        results.deleteAll();
    }

    private KeywordResult row(long monitorId, String at, Boolean changed, String note) {
        KeywordResult r = new KeywordResult();
        r.setMonitorId(monitorId);
        r.setCheckedAt(at);
        r.setOk(true);
        r.setOccurrences(1);
        r.setBodyBytes(100L);
        r.setContentChanged(changed);
        r.setChangeNote(note);
        return results.save(r);
    }

    @Test
    @DisplayName("contentSeriesRaw: LIMIT :limit bağlanır — aralıktaki en yeni N satır, azalan sıra; başka izleme / aralık dışı yok")
    void seriesLimitParameter() {
        for (int i = 0; i < 6; i++) row(5L, "2026-11-0" + (i + 1) + "T10:00:00", null, null);
        row(6L, "2026-11-03T10:00:00", null, null);
        List<Object[]> two = queries.contentSeriesRaw(5L, "2026-11-01T00:00:00", "2026-11-06T23:59:59", 2);
        assertThat(two).hasSize(2);
        assertThat(two.get(0)[0]).isEqualTo("2026-11-06T10:00:00");
        assertThat(two.get(1)[0]).isEqualTo("2026-11-05T10:00:00");
        List<Object[]> ranged = queries.contentSeriesRaw(5L, "2026-11-02T00:00:00", "2026-11-04T23:59:59", 100);
        assertThat(ranged).extracting(o -> o[0]).containsExactly("2026-11-04T10:00:00", "2026-11-03T10:00:00",
                "2026-11-02T10:00:00");
        assertThat(ranged.get(0)).hasSize(5);
    }

    @Test
    @DisplayName("latestChangeRow: değişimi ölçülmüş en yeni satır; latestRow: en yeni satır + change_note kalıcı")
    void latestRows() {
        row(7L, "2026-11-01T10:00:00", false, null);
        row(7L, "2026-11-02T10:00:00", true, null);
        row(7L, "2026-11-03T10:00:00", null, "HTTP_ERROR");
        assertThat(queries.latestChangeRow(7L)).singleElement()
                .satisfies(r -> assertThat(r.getCheckedAt()).isEqualTo("2026-11-02T10:00:00"));
        assertThat(queries.latestRow(7L)).singleElement().satisfies(r -> {
            assertThat(r.getCheckedAt()).isEqualTo("2026-11-03T10:00:00");
            assertThat(r.getChangeNote()).isEqualTo("HTTP_ERROR");
        });
        assertThat(queries.latestRow(99L)).isEmpty();
    }
}
