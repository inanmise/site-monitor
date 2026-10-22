package com.sitemonitor.repository;

import com.sitemonitor.model.PageMonitor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.withSettings;

/**
 * DATA-9 (2026-10-22): sertifika sağlığının "karışık içerik" satırı {@code LIKE %domain%} ile bulunan sayfa izlemesinden
 * besleniyordu — {@code example.com} için {@code www.example.com}, {@code bank.com} için {@code internetbank.com.tr}
 * (çoğu kez başka takımın) izlemesi. Artık yalnız host'u TAM eşleşenler.
 */
class PageMonitorHostMatchTest {

    private static PageMonitor mon(long id, String url) {
        PageMonitor m = new PageMonitor();
        m.setId(id);
        m.setUrl(url);
        m.setActive(true);
        return m;
    }

    @Test
    @DisplayName("findActiveForHost: LIKE adaylarından yalnız host'u tam eşleşenler (alt alan / içeren ad DIŞARIDA)")
    void onlyExactHost() {
        PageMonitorRepository repo = mock(PageMonitorRepository.class, withSettings().defaultAnswer(CALLS_REAL_METHODS));
        doReturn(List.of(
                mon(1, "https://www.example.com/"),
                mon(2, "https://example.com/giris?x=1"),
                mon(3, "https://EXAMPLE.com:8443/"),
                mon(4, "https://internetexample.com.tr/"),
                mon(5, "not a url"),
                mon(6, null)))
                .when(repo).findByUrlContainingIgnoreCaseAndActiveTrue(anyString());

        assertThat(repo.findActiveForHost("example.com")).extracting(PageMonitor::getId).containsExactly(2L, 3L);
        assertThat(repo.existsActiveForHost("example.com")).isTrue();
        assertThat(repo.findActiveForHost("  ")).isEmpty();
        assertThat(repo.findActiveForHost(null)).isEmpty();
    }

    @Test
    @DisplayName("urlHostMatches: büyük/küçük harf ve sondaki nokta duyarsız; ayrıştırılamayan URL eşleşmez")
    void hostRule() {
        assertThat(PageMonitorRepository.urlHostMatches("https://Bank.com./", "bank.com")).isTrue();
        assertThat(PageMonitorRepository.urlHostMatches("https://bank.com/a", "bank.com.")).isTrue();
        assertThat(PageMonitorRepository.urlHostMatches("https://internetbank.com.tr/", "bank.com")).isFalse();
        assertThat(PageMonitorRepository.urlHostMatches("https://www.bank.com/", "bank.com")).isFalse();
        assertThat(PageMonitorRepository.urlHostMatches("ht tp://bank.com", "bank.com")).isFalse();
        assertThat(PageMonitorRepository.urlHostMatches(null, "bank.com")).isFalse();
    }
}
