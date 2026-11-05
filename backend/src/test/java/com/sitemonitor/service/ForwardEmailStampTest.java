package com.sitemonitor.service;

import com.sitemonitor.model.AlertEvent;
import com.sitemonitor.repository.AlertEventRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Alarm iletimi damgası ({@code alert_events.forward_emails}, 2026-11-05): ayrıştırma, birleştirme, tavan, hedefli yazım. */
class ForwardEmailStampTest {

    @Test
    @DisplayName("ayrıştırma: alıcı takım → adresler; bozuk / boş / sayısal olmayan anahtar → yok sayılır")
    void parse() {
        assertThat(ForwardEmailStamp.parse(null)).isEmpty();
        assertThat(ForwardEmailStamp.parse("  ")).isEmpty();
        assertThat(ForwardEmailStamp.parse("{bozuk")).isEmpty();
        assertThat(ForwardEmailStamp.parse("[\"a@x\"]")).isEmpty();
        Map<Long, List<String>> m = ForwardEmailStamp.parse("{\"5\":[\"a@x.com\",\"A@X.com\",\" b@x.com \"],\"x\":[\"c@x\"],\"7\":\"d@x\"}");
        assertThat(m).containsOnlyKeys(5L);
        assertThat(m.get(5L)).containsExactly("a@x.com", "b@x.com");
        AlertEvent e = new AlertEvent();
        e.setForwardEmails("{\"9\":[\"k@x.com\"]}");
        assertThat(ForwardEmailStamp.of(e)).containsEntry(9L, List.of("k@x.com"));
        assertThat(ForwardEmailStamp.of(null)).isEmpty();
    }

    @Test
    @DisplayName("birleştirme: eski + yeni, büyük/küçük harf duyarsız tekil, alıcı takım artan; açılışı alanlar korunur")
    void merge() {
        String json = ForwardEmailStamp.merge("{\"9\":[\"b@x.com\"]}",
                Map.of(3L, List.of("a@x.com"), 9L, List.of("B@X.com", "c@x.com")), new int[1]);
        Map<Long, List<String>> m = ForwardEmailStamp.parse(json);
        assertThat(m.keySet()).containsExactly(3L, 9L);
        assertThat(m.get(9L)).containsExactly("b@x.com", "c@x.com");
        assertThat(json).isEqualTo("{\"3\":[\"a@x.com\"],\"9\":[\"b@x.com\",\"c@x.com\"]}");
    }

    @Test
    @DisplayName("tavan: 4000 karakteri aşan adresler SONDAN düşer, sayılır; ilk eklenenler kalır")
    void capped() {
        List<String> many = new ArrayList<>();
        for (int i = 0; i < 400; i++) many.add("kisi-" + i + "@ornek-kurum.example.com");
        int[] dropped = {0};
        String json = ForwardEmailStamp.merge(null, Map.of(1L, many), dropped);
        assertThat(json.length()).isLessThanOrEqualTo(ForwardEmailStamp.MAX_CHARS);
        assertThat(dropped[0]).isPositive();
        List<String> kept = ForwardEmailStamp.parse(json).get(1L);
        assertThat(kept).startsWith("kisi-0@ornek-kurum.example.com").hasSize(400 - dropped[0]);
    }

    @Test
    @DisplayName("damga: tek hedefli okuma + yazma; boş teslimde hiç sorgu yok; hata yutulur")
    void stamp() {
        AlertEventRepository repo = mock(AlertEventRepository.class);
        ForwardEmailStamp.stamp(repo, 7L, Map.of());
        ForwardEmailStamp.stamp(repo, 7L, Map.of(3L, List.of()));
        ForwardEmailStamp.stamp(repo, null, Map.of(3L, List.of("a@x.com")));
        verifyNoInteractions(repo);

        when(repo.findForwardEmails(7L)).thenReturn("{\"3\":[\"a@x.com\"]}");
        ForwardEmailStamp.stamp(repo, 7L, Map.of(3L, List.of("A@x.com")));   // değişiklik yok → yazım yok
        verify(repo, never()).setForwardEmails(anyLong(), anyString());
        ForwardEmailStamp.stamp(repo, 7L, Map.of(4L, List.of("b@x.com")));
        verify(repo).setForwardEmails(7L, "{\"3\":[\"a@x.com\"],\"4\":[\"b@x.com\"]}");

        when(repo.findForwardEmails(8L)).thenThrow(new RuntimeException("db yok"));
        ForwardEmailStamp.stamp(repo, 8L, Map.of(4L, List.of("b@x.com")));   // fırlatmaz
    }
}
