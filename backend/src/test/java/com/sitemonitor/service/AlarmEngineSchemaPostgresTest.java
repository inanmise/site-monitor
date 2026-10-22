package com.sitemonitor.service;

import com.sitemonitor.it.PostgresIntegration;
import com.sitemonitor.it.PostgresIt;
import com.sitemonitor.model.EscalationContact;
import com.sitemonitor.model.KeywordMonitor;
import com.sitemonitor.repository.EscalationContactRepository;
import com.sitemonitor.repository.KeywordMonitorRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Alarm motoru şema yamaları GERÇEK PostgreSQL'de (2026-10-22, PKG-2) — {@code mvn -Ppostgres-it test}.
 * <ul>
 *   <li>ADMIN-3: içerik izlemesi benzersizliği (takım, URL, ANAHTAR KELİME) — aynı sayfa farklı kelimeyle izlenir; eski
 *       (takım, URL) indeksi düşürüldü.</li>
 *   <li>DATA-3: {@code escalation_contacts.webhook_url} TEXT — Teams Workflows adresi (~290 karakter) saklanır.</li>
 * </ul>
 */
@PostgresIntegration
class AlarmEngineSchemaPostgresTest {

    private static JdbcTemplate jdbc() { return PostgresIt.app().jdbc(); }

    private static KeywordMonitor kw(String url, String keyword, Long team) {
        KeywordMonitor m = new KeywordMonitor();
        m.setName("kw-" + keyword + "-" + System.nanoTime());
        m.setUrl(url); m.setKeyword(keyword); m.setTeamId(team);
        return m;
    }

    @Test
    @DisplayName("ADMIN-3: aynı takım + URL, FARKLI kelime → ikisi de kaydedilir; aynı kelime (büyük/küçük harf fark etmez) → benzersizlik ihlali")
    void keywordIndex_isTeamUrlKeyword() {
        KeywordMonitorRepository repo = PostgresIt.app().bean(KeywordMonitorRepository.class);
        Long team = 990_001L;
        String url = "https://it-kw.example.com/sepet";
        try {
            repo.saveAndFlush(kw(url, "Giriş", team));
            repo.saveAndFlush(kw(url, "Kampanya", team));
            assertThatThrownBy(() -> repo.saveAndFlush(kw(url.toUpperCase(), "kampanya", team)))
                    .isInstanceOf(DataIntegrityViolationException.class);

            List<String> idx = jdbc().queryForList(
                    "SELECT indexname FROM pg_indexes WHERE tablename = 'keyword_monitors' AND indexname LIKE 'uq_kwm%'", String.class);
            assertThat(idx).contains("uq_kwm_team_url_kw").doesNotContain("uq_kwm_team_url");
        } finally {
            jdbc().update("DELETE FROM keyword_monitors WHERE team_id = ?", team);
        }
    }

    @Test
    @DisplayName("DATA-3: escalation_contacts.webhook_url TEXT — 300 karakterlik Teams Workflows adresi saklanır")
    void escalationWebhookUrl_isText() {
        String type = jdbc().queryForObject("SELECT data_type FROM information_schema.columns "
                + "WHERE table_name = 'escalation_contacts' AND column_name = 'webhook_url'", String.class);
        assertThat(type).isEqualTo("text");

        EscalationContactRepository repo = PostgresIt.app().bean(EscalationContactRepository.class);
        EscalationContact c = new EscalationContact();
        c.setName("IT webhook"); c.setRole("TECH"); c.setMinAlertLevel("WARNING"); c.setTeamId(990_002L); c.setActive(true);
        String url = "https://default0123456789abcdef0123456789abcdef.01.environment.api.powerplatform.com:443/powerautomate/"
                + "automations/direct/workflows/0123456789abcdef0123456789abcdef/triggers/manual/paths/invoke"
                + "?api-version=1&sp=%2Ftriggers%2Fmanual%2Frun&sv=1.0&sig=" + "x".repeat(60);
        assertThat(url.length()).isGreaterThan(255);
        c.setWebhookUrl(url);
        try {
            EscalationContact saved = repo.saveAndFlush(c);
            assertThat(repo.findById(saved.getId()).orElseThrow().getWebhookUrl()).isEqualTo(url);
        } finally {
            jdbc().update("DELETE FROM escalation_contacts WHERE team_id = ?", 990_002L);
        }
    }
}
