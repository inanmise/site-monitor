package com.sitemonitor.repository;

import com.sitemonitor.it.PostgresIntegration;
import com.sitemonitor.it.PostgresIt;
import com.sitemonitor.model.AlertEvent;
import com.sitemonitor.model.PageSpeedMonitor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Koşullu tek-kolon yazımları GERÇEK PostgreSQL'de (kapı incelemesi 2026-11-15) — {@code mvn -Ppostgres-it test}. H2 türsüz
 * parametreleri ve {@code TEXT = VARCHAR} karşılaştırmasını hoşgörüyle çözer; buradaki iddiaları yalnız PostgreSQL kanıtlar:
 * <ul>
 *   <li>{@code AlertEventRepository.setForwardEmailsIfUnchanged / IfNull} — iletim damgası birleştirme yarışı: okunan değer
 *       yerindeyse 1, değilse 0; NULL damga ayrı sorguyla (PostgreSQL türsüz {@code :eski IS NULL}'ı çözemez).</li>
 *   <li>{@code PageSpeedMonitorRepository.updateNextRunAtIfNull / IfEquals} — plan yazımı (klasik gövde ile gönderim yarışında
 *       tek kazanan; sorgu zaman aşımı ipucu PostgreSQL sürücüsünde de hatasız).</li>
 * </ul>
 */
@PostgresIntegration
class ConditionalStampPostgresTest {

    private static JdbcTemplate jdbc() { return PostgresIt.app().jdbc(); }

    @Test
    @DisplayName("PostgreSQL: iletim damgası — NULL iken yalnız IfNull yazar; dolu iken yalnız okunan değerle IfUnchanged yazar")
    void forwardEmailsConditionalWrites() {
        AlertEventRepository repo = PostgresIt.app().bean(AlertEventRepository.class);
        AlertEvent e = new AlertEvent();
        e.setDomain("pg-it-" + UUID.randomUUID() + ".example");
        e.setAlertType("HTTP_DOWN");
        e.setAlertLevel("WARNING");
        e.setCreatedAt("2026-11-15T10:00:00");
        Long id = repo.save(e).getId();
        try {
            String first = "{\"5\":[\"a@example.com\"]}";
            String second = "{\"5\":[\"a@example.com\",\"b@example.com\"]}";
            assertThat(repo.setForwardEmailsIfUnchanged(id, first, "{}")).as("NULL damga eşit değildir").isZero();
            assertThat(repo.setForwardEmailsIfNull(id, first)).isEqualTo(1);
            assertThat(repo.setForwardEmailsIfNull(id, second)).as("artık NULL değil").isZero();
            assertThat(repo.setForwardEmailsIfUnchanged(id, second, "{\"5\":[]}")).as("bayat okunan değer").isZero();
            assertThat(repo.setForwardEmailsIfUnchanged(id, second, first)).isEqualTo(1);
            assertThat(jdbc().queryForObject("SELECT forward_emails FROM alert_events WHERE id = ?", String.class, id))
                    .isEqualTo(second);
        } finally {
            jdbc().update("DELETE FROM alert_events WHERE id = ?", id);
        }
    }

    @Test
    @DisplayName("PostgreSQL: Sayfa Hızı planı — IfNull yalnız boş planı kurar, IfEquals yalnız okunan değer yerindeyse ilerletir")
    void pageSpeedNextRunConditionalWrites() {
        PageSpeedMonitorRepository repo = PostgresIt.app().bean(PageSpeedMonitorRepository.class);
        PageSpeedMonitor m = new PageSpeedMonitor();
        m.setName("pg-it-" + UUID.randomUUID());
        m.setUrl("https://pg-it.example/");
        m.setActive(false);
        Long id = repo.save(m).getId();
        try {
            assertThat(repo.updateNextRunAtIfEquals(id, "2026-11-15T10:00:00", "2026-11-15T10:05:00"))
                    .as("NULL plan eşit değildir").isZero();
            assertThat(repo.updateNextRunAtIfNull(id, "2026-11-15T10:00:00")).isEqualTo(1);
            assertThat(repo.updateNextRunAtIfNull(id, "2026-11-15T11:00:00")).as("plan zaten kurulu").isZero();
            assertThat(repo.updateNextRunAtIfEquals(id, "2026-11-15T09:59:59", "2026-11-15T10:05:00"))
                    .as("bayat okunan değer").isZero();
            assertThat(repo.updateNextRunAtIfEquals(id, "2026-11-15T10:00:00", "2026-11-15T10:05:00")).isEqualTo(1);
            assertThat(jdbc().queryForObject("SELECT next_run_at FROM pagespeed_monitors WHERE id = ?", String.class, id))
                    .isEqualTo("2026-11-15T10:05:00");
        } finally {
            jdbc().update("DELETE FROM pagespeed_monitors WHERE id = ?", id);
        }
    }
}
