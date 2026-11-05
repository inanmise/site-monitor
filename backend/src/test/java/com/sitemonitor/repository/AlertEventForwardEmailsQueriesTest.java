package com.sitemonitor.repository;

import com.sitemonitor.model.AlertEvent;
import com.sitemonitor.service.ForwardEmailStamp;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Alarm iletimi damgası (2026-11-05) — gerçek SQL (H2): hedefli okuma / yazma; varlık kaydı damgayı EZMEZ
 * ({@code insertable/updatable = false}); okunan varlık damgayı taşır (çözüm postası buradan okur).
 */
@DataJpaTest
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AlertEventForwardEmailsQueriesTest {

    @Autowired AlertEventRepository repo;

    @Test
    @DisplayName("damga hedefli yazılır / okunur; varlık kaydı ezmez; birleştirerek damgalama")
    void stampRoundTrip() {
        AlertEvent e = new AlertEvent();
        e.setDomain("a.example.com");
        e.setAlertType("HTTP_DOWN");
        e.setAlertLevel("HIGH");
        e.setResolved(false);
        e.setAcknowledged(false);
        e.setCreatedAt("2026-11-05T10:00:00");
        e.setForwardEmails("{\"9\":[\"yok@x.com\"]}");   // insertable=false — yazılmaz
        e = repo.save(e);
        Long id = e.getId();
        try {
            assertThat(repo.findForwardEmails(id)).isNull();
            ForwardEmailStamp.stamp(repo, id, Map.of(3L, List.of("b@x.com")));
            ForwardEmailStamp.stamp(repo, id, Map.of(3L, List.of("B@x.com", "c@x.com"), 4L, List.of("d@x.com")));
            assertThat(repo.findForwardEmails(id)).isEqualTo("{\"3\":[\"b@x.com\",\"c@x.com\"],\"4\":[\"d@x.com\"]}");

            AlertEvent read = repo.findById(id).orElseThrow();
            assertThat(ForwardEmailStamp.of(read)).containsEntry(3L, List.of("b@x.com", "c@x.com"));
            read.setForwardEmails(null);
            read.setMessage("güncellendi");
            repo.save(read);   // varlık kaydı damgayı ezmez
            assertThat(repo.findForwardEmails(id)).contains("d@x.com");
        } finally {
            repo.deleteById(id);
        }
    }

    @Test
    @DisplayName("koşullu yazım (gerçek SQL): IS NULL yalnız boş damgada; eşitlik yalnız okunan değer hâlâ yerindeyse — yarışan yazım 0 satır")
    void conditionalWrites() {
        AlertEvent e = new AlertEvent();
        e.setDomain("b.example.com");
        e.setAlertType("HTTP_DOWN");
        e.setAlertLevel("HIGH");
        e.setResolved(false);
        e.setAcknowledged(false);
        e.setCreatedAt("2026-11-06T10:00:00");
        Long id = repo.save(e).getId();
        try {
            assertThat(repo.setForwardEmailsIfUnchanged(id, "{\"1\":[\"x@x.com\"]}", "{}")).as("damga yok — eşitlik tutmaz").isZero();
            assertThat(repo.setForwardEmailsIfNull(id, "{\"3\":[\"a@x.com\"]}")).isEqualTo(1);
            assertThat(repo.setForwardEmailsIfNull(id, "{\"9\":[\"z@x.com\"]}")).as("artık boş değil").isZero();
            assertThat(repo.setForwardEmailsIfUnchanged(id, "{\"4\":[\"b@x.com\"]}", "{\"3\":[\"eski@x.com\"]}")).isZero();
            assertThat(repo.findForwardEmails(id)).isEqualTo("{\"3\":[\"a@x.com\"]}");
            assertThat(repo.setForwardEmailsIfUnchanged(id, "{\"3\":[\"a@x.com\"],\"4\":[\"b@x.com\"]}", "{\"3\":[\"a@x.com\"]}"))
                    .isEqualTo(1);
            // stamp: araya giren yazımı görür ve birleştirir
            ForwardEmailStamp.stamp(repo, id, Map.of(5L, List.of("c@x.com")));
            assertThat(ForwardEmailStamp.parse(repo.findForwardEmails(id))).containsOnlyKeys(3L, 4L, 5L);
        } finally {
            repo.deleteById(id);
        }
    }
}
