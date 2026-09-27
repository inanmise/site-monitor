package com.sitemonitor.repository;

import com.sitemonitor.model.NocCallLog;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.test.context.TestPropertySource;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 7/24 arama kaydı deposu (H2): uyarı listesi özetinin TEK sorgusu — her uyarının en son araması + toplam sayı —
 * ve detay sırası (en yeni önce, eşit anda son eklenen önce).
 */
@DataJpaTest
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
class NocCallLogRepositoryTest {

    @Autowired NocCallLogRepository repo;

    private NocCallLog call(long alertId, String name, String outcome, String at) {
        NocCallLog c = new NocCallLog();
        c.setAlertId(alertId);
        c.setTeamId(1L);
        c.setContactedName(name);
        c.setOutcome(outcome);
        c.setChannel("PHONE");
        c.setContactedAt(at);
        c.setCreatedAt(at);
        c.setCreatedBy("noc1");
        return repo.save(c);
    }

    @Test
    @DisplayName("özet: uyarı başına EN SON arama + sayı; eşit anda büyük id; kaydı olmayan uyarı satır üretmez")
    void summarizeLatestPerAlert() {
        call(10, "Kişi A", "NO_ANSWER", "2026-01-01T03:00:00");
        call(10, "Kişi B", "REACHED", "2026-01-01T03:12:00");
        call(10, "Kişi C", "VOICEMAIL", "2026-01-01T03:05:00");   // sonradan girilen ama DAHA ESKİ an
        call(11, "Kişi D", "BUSY", "2026-01-01T04:00:00");
        call(11, "Kişi E", "ESCALATED", "2026-01-01T04:00:00");  // aynı an → büyük id kazanır
        call(12, "Kişi F", "REACHED", "2026-01-01T05:00:00");     // sayfada değil

        Map<Long, Object[]> byAlert = new HashMap<>();
        for (Object[] r : repo.summarizeByAlertIds(List.of(10L, 11L, 13L))) byAlert.put(((Number) r[0]).longValue(), r);

        assertThat(byAlert).containsOnlyKeys(10L, 11L);
        assertThat(byAlert.get(10L)).containsExactly(10L, "Kişi B", "REACHED", "2026-01-01T03:12:00", 3L);
        assertThat(byAlert.get(11L)[1]).isEqualTo("Kişi E");
        assertThat(((Number) byAlert.get(11L)[4]).longValue()).isEqualTo(2L);
    }

    @Test
    @DisplayName("detay sırası: en yeni arama önce; aynı anda son eklenen önce")
    void detailOrder() {
        NocCallLog a = call(20, "Kişi A", "NO_ANSWER", "2026-01-01T03:00:00");
        NocCallLog b = call(20, "Kişi B", "REACHED", "2026-01-01T03:10:00");
        NocCallLog c = call(20, "Kişi C", "BUSY", "2026-01-01T03:10:00");
        call(21, "Başka", "REACHED", "2026-01-01T09:00:00");
        assertThat(repo.findByAlertIdOrderByContactedAtDescIdDesc(20L)).extracting(NocCallLog::getId)
                .containsExactly(c.getId(), b.getId(), a.getId());
    }
}
