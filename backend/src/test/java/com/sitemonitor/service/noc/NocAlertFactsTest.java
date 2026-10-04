package com.sitemonitor.service.noc;

import com.sitemonitor.model.AlertEvent;
import com.sitemonitor.model.NocDelivery;
import com.sitemonitor.repository.NocDeliveryRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * "7/24'e iletildi" rozetinin kaynağı (2026-10-04): açılış teslimi gitmiş sayılan (SENT / SENT_VIA_STORM / QUEUED_RETRY…)
 * alarm {@code noc_sent_at} alır; FAILED/SKIPPED/SENDING almaz; sayfa başına TEK sorgu; sorgu düşerse alanlar boş kalır.
 */
class NocAlertFactsTest {

    private static AlertEvent ev(long id) {
        AlertEvent e = new AlertEvent();
        e.setId(id);
        return e;
    }

    private static NocDelivery d(long alertId, String status) {
        NocDelivery d = new NocDelivery();
        d.setAlertEventId(alertId); d.setDedupeKey("alert:" + alertId + ":OPEN"); d.setPhase("OPEN"); d.setStatus(status);
        d.setCreatedAt("2026-10-04T08:00:00"); d.setUpdatedAt("2026-10-04T08:00:03");
        return d;
    }

    @Test
    @DisplayName("gitmiş sayılan durumlar damgalanır (fırtına ayrıca), diğerleri temizlenir; tek sorgu, doğru anahtarlar")
    void decorate() {
        NocDeliveryRepository repo = mock(NocDeliveryRepository.class);
        when(repo.findByDedupeKeyIn(anyCollection())).thenReturn(List.of(
                d(1, "SENT"), d(2, "SENT_VIA_STORM"), d(3, "QUEUED_RETRY: 421"), d(4, "FAILED: x"), d(5, "SENDING"),
                d(6, "SKIPPED_DISABLED")));
        List<AlertEvent> rows = new ArrayList<>();
        for (long i = 1; i <= 7; i++) rows.add(ev(i));
        rows.get(3).setNocSentAt("eski");   // önceden yazılmış değer temizlenmeli
        new NocAlertFacts(repo).decorate(rows);

        assertThat(rows.get(0).getNocSentAt()).isEqualTo("2026-10-04T08:00:03");
        assertThat(rows.get(0).getNocViaStorm()).isFalse();
        assertThat(rows.get(1).getNocViaStorm()).isTrue();
        assertThat(rows.get(2).getNocSentAt()).isNotNull();
        for (int i = 3; i < 7; i++) assertThat(rows.get(i).getNocSentAt()).as("#%d", i + 1).isNull();
        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<Collection<String>> keys = org.mockito.ArgumentCaptor.forClass(Collection.class);
        verify(repo, times(1)).findByDedupeKeyIn(keys.capture());
        assertThat(keys.getValue()).contains("alert:1:OPEN", "alert:7:OPEN").hasSize(7);
    }

    @Test
    @DisplayName("sorgu düşerse liste yine döner (alan boş); boş liste sorgu atmaz")
    void failureAndEmpty() {
        NocDeliveryRepository repo = mock(NocDeliveryRepository.class);
        when(repo.findByDedupeKeyIn(anyCollection())).thenThrow(new RuntimeException("db"));
        List<AlertEvent> rows = List.of(ev(1));
        new NocAlertFacts(repo).decorate(rows);
        assertThat(rows.get(0).getNocSentAt()).isNull();
        NocDeliveryRepository unused = mock(NocDeliveryRepository.class);
        new NocAlertFacts(unused).decorate(List.of());
        verify(unused, times(0)).findByDedupeKeyIn(anyCollection());
        assertThat(NocAlertFacts.sent(null)).isFalse();
        assertThat(NocAlertFacts.sentAt(null)).isNull();
    }
}
