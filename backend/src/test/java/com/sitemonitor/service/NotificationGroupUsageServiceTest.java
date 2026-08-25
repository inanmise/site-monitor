package com.sitemonitor.service;

import com.sitemonitor.model.*;
import com.sitemonitor.repository.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

/**
 * Grubun NEREDE kullanıldığı ve referansların TOPLU TAŞINMASI.
 *
 * <p>Bu servis silme kapısını besliyor: yanlış sayarsa ya kullanımdaki grup silinir (alarm
 * yönlendirmesi kullanıcı görmeden değişir) ya da kullanılmayan grup asla silinemez.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class NotificationGroupUsageServiceTest {

    private static final long GROUP = 7L;

    @Mock DnsMonitorRepository dnsRepo;
    @Mock DomainMonitorRepository domainRepo;
    @Mock HttpMonitorRepository httpRepo;
    @Mock KeywordMonitorRepository keywordRepo;
    @Mock PageMonitorRepository pageRepo;
    @Mock PageSpeedMonitorRepository pageSpeedRepo;
    @Mock PingMonitorRepository pingRepo;
    @Mock PortMonitorRepository portRepo;
    @Mock ScriptedMonitorRepository scriptedRepo;
    @Mock CertificateInventoryRepository inventoryRepo;

    @InjectMocks NotificationGroupUsageService service;

    private static PingMonitor ping(long id, String name) {
        PingMonitor m = new PingMonitor();
        m.setId(id); m.setName(name); m.setNotificationGroupId(GROUP);
        return m;
    }

    private static HttpMonitor http(long id, String name) {
        HttpMonitor m = new HttpMonitor();
        m.setId(id); m.setName(name); m.setNotificationGroupId(GROUP);
        return m;
    }

    private static CertificateInventory inv(long id, String domain) {
        CertificateInventory i = new CertificateInventory();
        i.setId(id); i.setDomain(domain); i.setNotificationGroupId(GROUP);
        return i;
    }

    @Test
    @DisplayName("Hiç referans yoksa kullanımda DEĞİL — grup silinebilir")
    void usage_none_isNotInUse() {
        var u = service.usage(GROUP);

        assertThat(u.inUse()).isFalse();
        assertThat(u.total()).isZero();
        assertThat(u.byType()).isEmpty();
    }

    @Test
    @DisplayName("Referanslar tür bazında sayılır; envanterde AD olarak domain gösterilir")
    void usage_countsByTypeAndNamesInventoryByDomain() {
        when(httpRepo.findByNotificationGroupId(GROUP)).thenReturn(List.of(http(1L, "Ödeme API")));
        when(pingRepo.findByNotificationGroupId(GROUP)).thenReturn(List.of(ping(2L, "GW"), ping(3L, "GW2")));
        when(inventoryRepo.findByNotificationGroupId(GROUP)).thenReturn(List.of(inv(4L, "a.example.com")));

        var u = service.usage(GROUP);

        assertThat(u.inUse()).isTrue();
        assertThat(u.total()).isEqualTo(4);
        assertThat(u.byType()).containsExactly(
                java.util.Map.entry("http", 1),
                java.util.Map.entry("ping", 2),
                java.util.Map.entry("inventory", 1));
        assertThat(u.items()).extracting("name")
                .containsExactly("Ödeme API", "GW", "GW2", "a.example.com");
    }

    @Test
    @DisplayName("Örnek listesi tavana takılır ama TOPLAM gerçeği söyler")
    void usage_capsItemsButNotTotal() {
        List<PingMonitor> many = new ArrayList<>();
        for (int i = 0; i < NotificationGroupUsageService.MAX_ITEMS + 20; i++) many.add(ping(i, "m" + i));
        when(pingRepo.findByNotificationGroupId(GROUP)).thenReturn(many);

        var u = service.usage(GROUP);

        // Ekrana 500 satır basmak ne okunur ne gerekli; sayaç yanıltmamalı.
        assertThat(u.items()).hasSize(NotificationGroupUsageService.MAX_ITEMS);
        assertThat(u.total()).isEqualTo(NotificationGroupUsageService.MAX_ITEMS + 20);
    }

    @Test
    @DisplayName("groupId null ise depoya HİÇ gidilmez")
    void usage_nullId_doesNotQuery() {
        var u = service.usage(null);

        assertThat(u.inUse()).isFalse();
        verifyNoInteractions(httpRepo, pingRepo, inventoryRepo);
    }

    @Test
    @DisplayName("Taşıma: TÜM türlerdeki referanslar hedefe yazılır ve sayılar döner")
    void reassign_movesEveryType() {
        when(httpRepo.findByNotificationGroupId(GROUP)).thenReturn(List.of(http(1L, "a")));
        when(pingRepo.findByNotificationGroupId(GROUP)).thenReturn(List.of(ping(2L, "b"), ping(3L, "c")));
        when(inventoryRepo.findByNotificationGroupId(GROUP)).thenReturn(List.of(inv(4L, "d.example.com")));

        var moved = service.reassign(GROUP, 99L);

        assertThat(moved).containsExactly(
                java.util.Map.entry("http", 1),
                java.util.Map.entry("ping", 2),
                java.util.Map.entry("inventory", 1));
        verify(httpRepo).saveAll(argThat(rows -> rows.iterator().next().getNotificationGroupId() == 99L));
        verify(pingRepo).saveAll(any());
        verify(inventoryRepo).saveAll(any());
    }

    @Test
    @DisplayName("Taşıma hedefi null: grup seçimi KALDIRILIR (takım varsayılanına düşer)")
    void reassign_toNull_clearsSelection() {
        when(pingRepo.findByNotificationGroupId(GROUP)).thenReturn(List.of(ping(2L, "b")));

        service.reassign(GROUP, null);

        verify(pingRepo).saveAll(argThat(rows -> rows.iterator().next().getNotificationGroupId() == null));
    }

    @Test
    @DisplayName("Referansı olmayan tür için depoya YAZMA yapılmaz (boş saveAll gürültüsü yok)")
    void reassign_skipsEmptyTypes() {
        when(pingRepo.findByNotificationGroupId(GROUP)).thenReturn(List.of(ping(2L, "b")));

        var moved = service.reassign(GROUP, 99L);

        assertThat(moved).containsOnlyKeys("ping");
        verify(httpRepo, never()).saveAll(any());
        verify(dnsRepo, never()).saveAll(any());
        verify(inventoryRepo, never()).saveAll(any());
    }
}
