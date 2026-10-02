package com.sitemonitor.service;

import com.sitemonitor.model.MonitorGuide;
import com.sitemonitor.repository.MonitorGuideRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Alarm → rehber anahtarı ve gönderim başına TEK rehber sorgusu (2026-10-01). Anahtar, "Rehber &amp; Notlar"
 * sekmesinin yazdığı (monitor_type, target) ile birebir olmalı ({@code MonitorTargetTeams}): yanlış anahtar = not hiç
 * görünmez ama hiçbir test kırmızıya dönmez.
 */
class RunbookNoteServiceTest {

    @ParameterizedTest(name = "{0} → {2}")
    @CsvSource({
            "HTTP_DOWN,              https://a.example.com/,  HTTP,      https://a.example.com/",
            "HTTP_SLOW,              https://a.example.com/,  HTTP,      https://a.example.com/",
            "DOMAIN_EXPIRY,          https://a.example.com/,  HTTP,      https://a.example.com/",
            "KEYWORD,                https://k.example.com/,  KEYWORD,   https://k.example.com/",
            "KEYWORD_SSL,            https://k.example.com/,  KEYWORD,   https://k.example.com/",
            "PING_DOWN,              db.example.com,          PING,      db.example.com",
            "DNS_FAILURE,            example.com,             DNS,       example.com",
            "DNS_CHANGED,            example.com,             DNS,       example.com",
            "DOMAINMON_EXPIRY,       example.com,             DOMAIN,    example.com",
            "PAGE_INTEGRITY,         https://p.example.com/,  PAGE,      https://p.example.com/",
            "PAGESPEED_SLOW,         https://s.example.com/,  PAGESPEED, https://s.example.com/",
            "SCRIPTED_FAIL,          Ödeme akışı,             SCRIPTED,  Ödeme akışı",
    })
    @DisplayName("keyFor: izleme türleri hedefin kendi anahtarına eşlenir")
    void keyFor_monitorFamilies(String alertType, String domain, String type, String target) {
        RunbookNoteService.GuideKey k = RunbookNoteService.keyFor(alertType, domain, Map.of());
        assertThat(k).isEqualTo(new RunbookNoteService.GuideKey(type, target));
    }

    @Test
    @DisplayName("keyFor: PORT = host:port (port bağlamdan); port yoksa/bozuksa anahtar yok")
    void keyFor_portUsesContextPort() {
        assertThat(RunbookNoteService.keyFor("PORT_DOWN", "db.example.com", Map.of("port", 5432)))
                .isEqualTo(new RunbookNoteService.GuideKey("PORT", "db.example.com:5432"));
        assertThat(RunbookNoteService.keyFor("PORT_SLOW", "db.example.com", Map.of("port", "443")))
                .isEqualTo(new RunbookNoteService.GuideKey("PORT", "db.example.com:443"));
        assertThat(RunbookNoteService.keyFor("PORT_DOWN", "db.example.com", Map.of())).isNull();
        assertThat(RunbookNoteService.keyFor("PORT_DOWN", "db.example.com", null)).isNull();
        assertThat(RunbookNoteService.keyFor("PORT_DOWN", "db.example.com", Map.of("port", "x1"))).isNull();
    }

    @Test
    @DisplayName("keyFor: sertifika ailesi (EXPIRY, ACCESSIBILITY…) ve eksik hedef → anahtar yok")
    void keyFor_certAndMissing() {
        for (String t : new String[]{"EXPIRY", "REVOKED", "CHAIN_BROKEN", "ACCESSIBILITY", "HOSTNAME_MISMATCH"}) {
            assertThat(RunbookNoteService.keyFor(t, "a.example.com", Map.of())).as(t).isNull();
        }
        assertThat(RunbookNoteService.keyFor(null, "a", Map.of())).isNull();
        assertThat(RunbookNoteService.keyFor("PING_DOWN", null, Map.of())).isNull();
        assertThat(RunbookNoteService.keyFor("PING_DOWN", "  ", Map.of())).isNull();
        assertThat(RunbookNoteService.keyFor("PING_DOWN", "h".repeat(501), Map.of())).isNull();
    }

    @Test
    @DisplayName("plainGuide: TEK sorgu; markdown düz metne çevrilir")
    void plainGuide_singleLookupAndPlainText() {
        MonitorGuideRepository repo = mock(MonitorGuideRepository.class);
        MonitorGuide g = new MonitorGuide();
        g.setGuide("## Adımlar\n**Servisi** yeniden başlat");
        when(repo.findByMonitorTypeAndTarget("PING", "db.example.com")).thenReturn(Optional.of(g));
        RunbookNoteService svc = new RunbookNoteService(repo);

        assertThat(svc.plainGuide("PING_DOWN", "db.example.com", Map.of())).isEqualTo("Adımlar\nServisi yeniden başlat");
        verify(repo, times(1)).findByMonitorTypeAndTarget(anyString(), anyString());
    }

    @Test
    @DisplayName("plainGuide: rehber yok / boş / yalnız biçim işareti / sertifika türü / sorgu hatası → null (bildirim notsuz)")
    void plainGuide_nullCases() {
        MonitorGuideRepository repo = mock(MonitorGuideRepository.class);
        RunbookNoteService svc = new RunbookNoteService(repo);
        when(repo.findByMonitorTypeAndTarget("PING", "none.example.com")).thenReturn(Optional.empty());
        MonitorGuide blank = new MonitorGuide();
        blank.setGuide("  \n **** \n");
        when(repo.findByMonitorTypeAndTarget("PING", "blank.example.com")).thenReturn(Optional.of(blank));
        when(repo.findByMonitorTypeAndTarget("PING", "boom.example.com")).thenThrow(new RuntimeException("db down"));

        assertThat(svc.plainGuide("PING_DOWN", "none.example.com", Map.of())).isNull();
        assertThat(svc.plainGuide("PING_DOWN", "blank.example.com", Map.of())).isNull();
        assertThat(svc.plainGuide("PING_DOWN", "boom.example.com", Map.of())).isNull();
        assertThat(svc.plainGuide("EXPIRY", "cert.example.com", Map.of())).isNull();
        verify(repo, never()).findByMonitorTypeAndTarget(eq("CERT"), anyString());
        verify(repo, times(3)).findByMonitorTypeAndTarget(anyString(), anyString());
    }

    private static String eq(String s) { return org.mockito.ArgumentMatchers.eq(s); }
}
