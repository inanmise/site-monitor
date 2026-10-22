package com.sitemonitor.service;

import com.sitemonitor.repository.AlertEventRepository;
import com.sitemonitor.repository.AlertStormRepository;
import com.sitemonitor.repository.CertificateInventoryRepository;
import com.sitemonitor.repository.DnsMonitorRepository;
import com.sitemonitor.repository.DomainMonitorRepository;
import com.sitemonitor.repository.EscalationContactRepository;
import com.sitemonitor.repository.HttpMonitorRepository;
import com.sitemonitor.repository.KeywordMonitorRepository;
import com.sitemonitor.repository.PingMonitorRepository;
import com.sitemonitor.repository.PortMonitorRepository;
import com.sitemonitor.repository.TeamRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * ALERTS-4 (2026-10-22): kapanışın "duyuruldu" okuması gerçek SQL ile (H2, PostgreSQL modu) — satır var + {@code announced_at}
 * dolu = duyuruldu, satır var + boş = duyurulmadı, satır yok = eski kural (haritada anahtar yok).
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.datasource.url=jdbc:h2:mem:stormannounced;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;NON_KEYWORDS=VALUE",
        "spring.datasource.driver-class-name=org.h2.Driver"})
class StormAnnouncedRowsH2Test {

    @Autowired DataSource dataSource;

    @Test
    @DisplayName("announcedRows: üyelik satırları tek okumayla kimlik → announced_at (duyurusuz = null değer)")
    void announcedRows_realSql() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.update("DELETE FROM alert_storm_members");
        jdbc.update(StormService.SQL_MEMBER_INSERT, 91L, 1L, "2026-10-22T08:00:00", "PEER");
        jdbc.update(StormService.SQL_MEMBER_INSERT, 91L, 2L, "2026-10-22T08:00:01", "ATTACH");
        jdbc.update(StormService.SQL_MEMBER_INSERT, 92L, 3L, "2026-10-22T08:00:00", "PEER");   // başka fırtına
        jdbc.update(StormService.SQL_MEMBER_ANNOUNCED_PREFIX + "?)", "2026-10-22T08:00:05", 91L, 1L);

        StormService storm = new StormService(mock(AlertStormRepository.class), mock(AlertEventRepository.class),
                mock(AppSettingsService.class), mock(EmailNotificationService.class), mock(WebhookService.class),
                mock(TeamRepository.class), mock(EscalationContactRepository.class), mock(CertificateInventoryRepository.class),
                jdbc, mock(HttpMonitorRepository.class), mock(PortMonitorRepository.class), mock(KeywordMonitorRepository.class),
                mock(PingMonitorRepository.class), mock(DnsMonitorRepository.class), mock(DomainMonitorRepository.class),
                mock(NotificationGroupService.class));

        Map<Long, String> rows = storm.announcedRows(91L);
        assertThat(rows).containsOnlyKeys(1L, 2L);
        assertThat(rows.get(1L)).isEqualTo("2026-10-22T08:00:05");
        assertThat(rows.get(2L)).isNull();
        assertThat(storm.announcedRows(99L)).isEmpty();
    }
}
