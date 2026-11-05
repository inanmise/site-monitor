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
    @DisplayName("tavan: 4000 karakteri aşan YENİ adresler düşer, sayılır; ilk eklenenler kalır")
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
    @DisplayName("tavan: damgada ZATEN olan adresler korunur, sığmayan YENİ adres düşer (eski takımın adresleri silinmez)")
    void cappedKeepsExisting() {
        List<String> many = new ArrayList<>();
        for (int i = 0; i < 400; i++) many.add("kisi-" + i + "@ornek-kurum.example.com");
        int[] first = {0};
        String full = ForwardEmailStamp.merge(null, Map.of(9L, many), first);   // 9 numaralı takım tavanı doldurur
        List<String> kept9 = ForwardEmailStamp.parse(full).get(9L);
        int[] dropped = {0};
        String next = ForwardEmailStamp.merge(full, Map.of(1L, List.of("yeni-alici@example.com")), dropped);
        assertThat(dropped[0]).isEqualTo(1);
        assertThat(ForwardEmailStamp.parse(next).get(9L)).as("açılışı almış adresler kalır").isEqualTo(kept9);
        assertThat(ForwardEmailStamp.parse(next)).doesNotContainKey(1L);
        assertThat(next.length()).isLessThanOrEqualTo(ForwardEmailStamp.MAX_CHARS);
        // eski metin tek başına tavanı aşıyorsa (elle yazılmış) hiçbir şey eklenmez, eski metin aynen döner
        StringBuilder big = new StringBuilder("{\"5\":[");
        for (int i = 0; i < 160; i++) big.append(i == 0 ? "" : ",").append("\"uzun-adres-").append(i).append("@example.com\"");
        big.append("]}");
        int[] d2 = {0};
        assertThat(ForwardEmailStamp.merge(big.toString(), Map.of(1L, List.of("a@x.com", "b@x.com")), d2)).isEqualTo(big.toString());
        assertThat(d2[0]).isEqualTo(2);
        assertThat(big.length()).isBetween(ForwardEmailStamp.MAX_CHARS + 1, ForwardEmailStamp.MAX_CHARS * 2);
    }

    @Test
    @DisplayName("damga: okuma + KOŞULLU yazma; boş teslimde hiç sorgu yok; hata yutulur")
    void stamp() {
        AlertEventRepository repo = mock(AlertEventRepository.class);
        ForwardEmailStamp.stamp(repo, 7L, Map.of());
        ForwardEmailStamp.stamp(repo, 7L, Map.of(3L, List.of()));
        ForwardEmailStamp.stamp(repo, null, Map.of(3L, List.of("a@x.com")));
        verifyNoInteractions(repo);

        when(repo.findForwardEmails(7L)).thenReturn("{\"3\":[\"a@x.com\"]}");
        when(repo.setForwardEmailsIfUnchanged(anyLong(), anyString(), anyString())).thenReturn(1);
        ForwardEmailStamp.stamp(repo, 7L, Map.of(3L, List.of("A@x.com")));   // değişiklik yok → yazım yok
        verify(repo, never()).setForwardEmailsIfUnchanged(anyLong(), anyString(), anyString());
        ForwardEmailStamp.stamp(repo, 7L, Map.of(4L, List.of("b@x.com")));
        verify(repo).setForwardEmailsIfUnchanged(7L, "{\"3\":[\"a@x.com\"],\"4\":[\"b@x.com\"]}", "{\"3\":[\"a@x.com\"]}");
        verify(repo, never()).setForwardEmails(anyLong(), anyString());

        // damga yokken: IS NULL koşullu yazım
        when(repo.setForwardEmailsIfNull(anyLong(), anyString())).thenReturn(1);
        ForwardEmailStamp.stamp(repo, 9L, Map.of(4L, List.of("b@x.com")));
        verify(repo).setForwardEmailsIfNull(9L, "{\"4\":[\"b@x.com\"]}");

        when(repo.findForwardEmails(8L)).thenThrow(new RuntimeException("db yok"));
        ForwardEmailStamp.stamp(repo, 8L, Map.of(4L, List.of("b@x.com")));   // fırlatmaz
    }

    @Test
    @DisplayName("yarış: araya başka yazım girerse yeniden okunur + birleştirilir (iki tarafın adresleri de kalır); en çok 3 deneme")
    void concurrentWriteRetried() {
        AlertEventRepository repo = mock(AlertEventRepository.class);
        String other = "{\"5\":[\"baska@x.com\"]}";
        when(repo.findForwardEmails(7L)).thenReturn(null, other);
        when(repo.setForwardEmailsIfNull(7L, "{\"4\":[\"b@x.com\"]}")).thenReturn(0);   // araya öteki gönderim girdi
        when(repo.setForwardEmailsIfUnchanged(anyLong(), anyString(), anyString())).thenReturn(1);
        ForwardEmailStamp.stamp(repo, 7L, Map.of(4L, List.of("b@x.com")));
        verify(repo).setForwardEmailsIfUnchanged(7L, "{\"4\":[\"b@x.com\"],\"5\":[\"baska@x.com\"]}", other);

        // hep tutmuyor → 3 denemede bırakır (sonsuz döngü yok)
        AlertEventRepository busy = mock(AlertEventRepository.class);
        when(busy.findForwardEmails(8L)).thenReturn(other);
        when(busy.setForwardEmailsIfUnchanged(anyLong(), anyString(), anyString())).thenReturn(0);
        ForwardEmailStamp.stamp(busy, 8L, Map.of(4L, List.of("b@x.com")));
        verify(busy, times(ForwardEmailStamp.MAX_ATTEMPTS)).setForwardEmailsIfUnchanged(anyLong(), anyString(), anyString());
    }
}
