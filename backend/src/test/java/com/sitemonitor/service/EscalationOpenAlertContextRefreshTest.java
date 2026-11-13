package com.sitemonitor.service;

import com.sitemonitor.model.AlertEvent;
import com.sitemonitor.repository.AlertEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Constructor;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Bulgu 1 (2026-11-13, sayfa bütünlüğü): günlük derin tarama hâlâ sorunlu dediğinde açık alarmın kaynağı (sorunlu sayfalar,
 * kırpılma, kapsam) TAZELENİR — bağlam yalnız açılışta yazılıyordu ve sık taramanın hedefli ölçümü eski listeye bakıp alarmı
 * yanlış kapatıyordu. Yazım HEDEFLİ (yalnız contextJson, yalnız açık alarm); bağlamın alarm-anı kalanı korunur.
 */
class EscalationOpenAlertContextRefreshTest {

    private static final String URL = "https://site.example.com/";
    private final tools.jackson.databind.ObjectMapper json = new tools.jackson.databind.ObjectMapper();
    private AlertEventRepository repo;
    private AlertEventFieldWriter writer;
    private EscalationService service;

    @SuppressWarnings("unchecked")
    private static <T> T build(Class<T> type, Map<Class<?>, Object> provided) throws Exception {
        Constructor<?> c = java.util.Arrays.stream(type.getConstructors())
                .max(Comparator.comparingInt(Constructor::getParameterCount)).orElseThrow();
        Class<?>[] pt = c.getParameterTypes();
        Object[] args = new Object[pt.length];
        for (int i = 0; i < pt.length; i++) args[i] = provided.containsKey(pt[i]) ? provided.get(pt[i]) : mock(pt[i]);
        return (T) c.newInstance(args);
    }

    @BeforeEach
    void setUp() throws Exception {
        repo = mock(AlertEventRepository.class);
        writer = mock(AlertEventFieldWriter.class);
        Map<Class<?>, Object> provided = new HashMap<>();
        provided.put(AlertEventRepository.class, repo);
        provided.put(tools.jackson.databind.ObjectMapper.class, json);
        service = build(EscalationService.class, provided);
        service.setFieldWriter(writer);
        when(writer.writeValues(anyLong(), any(), anyBoolean())).thenReturn(1);
    }

    /** Tazelemeyi isteyen izleme (id 6, takım 7) — açık alarmın sahibi olmalı (inceleme 2026-11-13, DÜŞÜK 7). */
    private static final Map<String, Object> OWNER = Map.of("monitor_id", 6L, "team_id", 7L);

    private AlertEvent alarm(long id, long teamId, String contextJson) {
        AlertEvent e = new AlertEvent();
        e.setId(id); e.setDomain(URL); e.setAlertType(EscalationService.TYPE_PAGE_INTEGRITY); e.setResolved(false);
        e.setTeamId(teamId);
        e.setContextJson(contextJson);
        return e;
    }

    private void openAlarm(Map<String, Object> ctx) {
        Map<String, Object> c = new LinkedHashMap<>(ctx);
        c.putIfAbsent("monitor_id", 6);
        when(repo.findOpenAlerts(URL, EscalationService.TYPE_PAGE_INTEGRITY))
                .thenReturn(List.of(alarm(140L, 7L, json.writeValueAsString(c))));
    }

    @Test
    @DisplayName("açık alarmın kaynağı tazelenir — yalnız contextJson, yalnız AÇIK satır; alarm-anı alanları korunur")
    @SuppressWarnings("unchecked")
    void refreshesOnlyTheGivenKeys() {
        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put("integrity_scope", "HOME");
        ctx.put("problem_rows", "BROKEN\thttps://site.example.com/a.js\t404");
        ctx.put("team_id", 7);
        openAlarm(ctx);
        Map<String, Object> patch = new LinkedHashMap<>();
        patch.put("integrity_scope", "CRAWL");
        patch.put("problem_pages", List.of(URL + "sozlesmeler"));
        patch.put("problem_pages_truncated", true);

        assertThat(service.refreshOpenAlertContext(URL, EscalationService.TYPE_PAGE_INTEGRITY, OWNER, patch)).isTrue();
        ArgumentCaptor<Map<String, Object>> values = ArgumentCaptor.forClass(Map.class);
        verify(writer).writeValues(eq(140L), values.capture(), eq(true));
        assertThat(values.getValue()).containsOnlyKeys("contextJson");
        Map<String, Object> written = json.readValue((String) values.getValue().get("contextJson"), Map.class);
        assertThat(written).containsEntry("integrity_scope", "CRAWL").containsEntry("problem_pages_truncated", true)
                .containsEntry("problem_pages", List.of(URL + "sozlesmeler"))
                .containsEntry("problem_rows", "BROKEN\thttps://site.example.com/a.js\t404").containsEntry("team_id", 7);
    }

    @Test
    @DisplayName("değişiklik yoksa yazmaz; açık alarm yoksa yazmaz; anlık görüntü dışı anahtar programlama hatası")
    void noOpCases() {
        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put("integrity_scope", "CRAWL");
        ctx.put("problem_pages", List.of(URL + "a"));
        ctx.put("problem_pages_truncated", false);
        openAlarm(ctx);
        Map<String, Object> same = new LinkedHashMap<>(ctx);
        assertThat(service.refreshOpenAlertContext(URL, EscalationService.TYPE_PAGE_INTEGRITY, OWNER, same)).isFalse();
        verify(writer, never()).writeValues(anyLong(), any(), anyBoolean());

        when(repo.findOpenAlerts(URL, EscalationService.TYPE_PAGE_INTEGRITY)).thenReturn(List.of());
        assertThat(service.refreshOpenAlertContext(URL, EscalationService.TYPE_PAGE_INTEGRITY, OWNER,
                Map.of("integrity_scope", "HOME"))).isFalse();
        verify(writer, never()).writeValues(anyLong(), any(), anyBoolean());

        assertThatThrownBy(() -> service.refreshOpenAlertContext(URL, EscalationService.TYPE_PAGE_INTEGRITY, OWNER,
                Map.of("bilinmeyen_anahtar", 9))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("inceleme 2026-11-13 (DÜŞÜK 7): okunamayan bağlam YAZILMAZ — yalnız yamayla yeniden yazmak alarm anını silerdi")
    void unreadableContextIsNotOverwritten() {
        when(repo.findOpenAlerts(URL, EscalationService.TYPE_PAGE_INTEGRITY))
                .thenReturn(List.of(alarm(140L, 7L, "{bozuk-json")));
        assertThat(service.refreshOpenAlertContext(URL, EscalationService.TYPE_PAGE_INTEGRITY, OWNER,
                Map.of("integrity_scope", "CRAWL"))).isFalse();
        verify(writer, never()).writeValues(anyLong(), any(), anyBoolean());
    }

    @Test
    @DisplayName("inceleme 2026-11-13 (DÜŞÜK 7): aynı URL'yi izleyen BAŞKA takımın açık alarmının kaynağı değişmez; "
            + "sahibin (daha eski) alarmı tazelenir")
    void onlyTheOwnersAlarmIsRefreshed() {
        AlertEvent other = alarm(150L, 8L, json.writeValueAsString(Map.of("monitor_id", 99, "team_id", 8,
                "integrity_scope", "HOME")));
        when(repo.findOpenAlerts(URL, EscalationService.TYPE_PAGE_INTEGRITY)).thenReturn(List.of(other));
        assertThat(service.refreshOpenAlertContext(URL, EscalationService.TYPE_PAGE_INTEGRITY, OWNER,
                Map.of("integrity_scope", "CRAWL"))).as("yalnız başka takımın alarmı açık").isFalse();
        verify(writer, never()).writeValues(anyLong(), any(), anyBoolean());

        AlertEvent mine = alarm(140L, 7L, json.writeValueAsString(Map.of("monitor_id", 6, "team_id", 7,
                "integrity_scope", "HOME")));
        when(repo.findOpenAlerts(URL, EscalationService.TYPE_PAGE_INTEGRITY)).thenReturn(List.of(other, mine));   // en yeni önde
        assertThat(service.refreshOpenAlertContext(URL, EscalationService.TYPE_PAGE_INTEGRITY, OWNER,
                Map.of("integrity_scope", "CRAWL"))).isTrue();
        verify(writer).writeValues(eq(140L), any(), eq(true));
        verify(writer, never()).writeValues(eq(150L), any(), anyBoolean());
    }
}
