package com.sitemonitor.service.oncallroutine;

import com.sitemonitor.service.oncallroutine.OnCallRoutineTemplate.Template;
import com.sitemonitor.service.oncallroutine.OnCallRoutineTemplateDiff.Summary;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Şablon sürüm farkı özeti (2026-11-26): eklenen / kaldırılan / değişen / taşınan sayıları, başlık / adres içermez. */
class OnCallRoutineTemplateDiffTest {

    static Template t(List<Map<String, Object>> sections) {
        return OnCallRoutineTemplate.parseStrict(Map.of("sections", sections));
    }

    @Test
    @DisplayName("ekle, kaldır, değiştir, taşı")
    void counts() {
        Template before = t(List.of(
                Map.of("id", "s1", "title", "A", "tasks", List.of(
                        Map.of("id", "a", "title", "Bir"), Map.of("id", "b", "title", "İki"), Map.of("id", "c", "title", "Üç"))),
                Map.of("id", "s2", "title", "B", "tasks", List.of(Map.of("id", "d", "title", "Dört")))));
        Template after = t(List.of(
                Map.of("id", "s1", "title", "A", "tasks", List.of(
                        Map.of("id", "b", "title", "İki"), Map.of("id", "a", "title", "Bir (yeni)"))),
                Map.of("id", "s2", "title", "B", "tasks", List.of(
                        Map.of("id", "d", "title", "Dört"), Map.of("id", "e", "title", "Beş")))));
        Summary s = OnCallRoutineTemplateDiff.summary(before, after);
        assertThat(s.added()).isEqualTo(1);     // e
        assertThat(s.removed()).isEqualTo(1);   // c
        assertThat(s.changed()).isEqualTo(1);   // a başlığı
        assertThat(s.moved()).isEqualTo(2);     // a ve b sırası
        assertThat(s.sections()).isEqualTo(2);
        assertThat(s.tasks()).isEqualTo(4);
        assertThat(s.active()).isEqualTo(4);
        assertThat(s.toMap()).containsOnlyKeys("sections", "tasks", "active", "added", "removed", "changed", "moved");
        assertThat(s.toMap().toString()).doesNotContain("Bir").doesNotContain("Dört");
    }

    @Test
    @DisplayName("ilk kayıt: önceki şablon yok → her görev eklendi")
    void firstSave() {
        Template after = t(List.of(Map.of("id", "s1", "title", "A", "tasks", List.of(Map.of("id", "a", "title", "x")))));
        Summary s = OnCallRoutineTemplateDiff.summary(null, after);
        assertThat(s.added()).isEqualTo(1);
        assertThat(s.removed()).isZero();
        assertThat(OnCallRoutineTemplateDiff.summary(after, after).changed()).isZero();
    }
}
