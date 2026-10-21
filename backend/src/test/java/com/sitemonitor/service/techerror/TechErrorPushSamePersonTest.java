package com.sitemonitor.service.techerror;

import com.sitemonitor.model.AppUser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Aynı kişi kuralı (2026-10-21) × teknik hata raporu onay hatırlatması: PO'nun iki hesabı aynı kişidir; push öbür hesaba
 * kuyruğa girdiyse atlanan hesap da "ulaşıldı" sayılır (karar kişi başına) — yoksa yeniden denemede aynı hatırlatma atlanan
 * hesaba ikinci kez giderdi. Fixture kimlikleri SAHTE sicillerdir.
 */
class TechErrorPushSamePersonTest {

    private static AppUser po(long id, String username) {
        AppUser u = new AppUser();
        u.setId(id);
        u.setUsername(username);
        u.setActive(true);
        return u;
    }

    @Test
    @DisplayName("same_person: kuyruğa giren hesabın öbür hesabı ulaşılmış sayılır; kuyruğa girmeyen hesaba bağlı olan sayılmaz")
    void samePersonCountsAsReached() {
        List<AppUser> people = List.of(po(1, "MP60146"), po(2, "N60146"), po(3, "N00003"));
        Map<String, Object> res = Map.of(
                "queued", 1,
                "queued_usernames", List.of("N60146"),
                "same_person", Map.of("MP60146", "N60146"));
        assertThat(TechErrorPushService.queuedIds(people, res)).containsExactlyInAnyOrder(1L, 2L);

        Map<String, Object> notQueued = Map.of(
                "queued", 0,
                "queued_usernames", List.of(),
                "same_person", Map.of("MP60146", "N60146"));   // seçilen hesap ör. saat sınırına takıldı → kimseye ulaşmadı
        assertThat(TechErrorPushService.queuedIds(people, notQueued)).isEmpty();

        // Eski sonuç biçimi (same_person yok) — bugünkü davranış
        assertThat(TechErrorPushService.queuedIds(people, Map.of("queued", 1, "queued_usernames", List.of("N00003"))))
                .containsExactly(3L);
    }
}
