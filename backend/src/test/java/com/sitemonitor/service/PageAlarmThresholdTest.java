package com.sitemonitor.service;

import com.sitemonitor.model.PageMonitor;
import com.sitemonitor.service.PageCheckerService.ResourceIssue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * E5 (2026-11-12): alarm için en az KIRIK kaynak eşiği ({@link PageMonitor#getMinBrokenResources()}). Eşik yalnız
 * BROKEN kaynakları geçitler; TIMEOUT / MIXED_CONTENT kendi aç/kapa ayarlarıyla sayılır. Varsayılan 1 = eski davranış
 * (golden değişmez). TEK kaynak: {@link PageCheckerService#resultAlarmWorthy}.
 */
class PageAlarmThresholdTest {

    private static PageMonitor monitor(Integer minBroken) {
        PageMonitor m = new PageMonitor();
        m.setUrl("https://ornek.test/");
        m.setMinBrokenResources(minBroken);
        m.setAlertThirdParty(false);
        m.setAlertTimeout(true);
        m.setAlertMixedContent(true);
        return m;
    }

    private static ResourceIssue broken(String url) {
        return new ResourceIssue(url, "SCRIPT", "https://ornek.test/", "BROKEN", true, 500, 10L);
    }

    private static ResourceIssue timeout(String url) {
        return new ResourceIssue(url, "IMG", "https://ornek.test/", "TIMEOUT", true, null, 5000L);
    }

    private static ResourceIssue mixed(String url) {
        return new ResourceIssue(url, "IMG", "https://ornek.test/", "MIXED_CONTENT", true, null, null);
    }

    @Test
    @DisplayName("Varsayılan (null/1): bir alarm-uygun kırık alarm açar (eski davranış)")
    void defaultOneBrokenOpens() {
        assertThat(PageCheckerService.minBrokenResources(monitor(null))).isEqualTo(1);
        assertThat(PageCheckerService.resultAlarmWorthy(monitor(null), List.of(broken("a.js")))).isTrue();
        assertThat(PageCheckerService.resultAlarmWorthy(monitor(1), List.of(broken("a.js")))).isTrue();
        assertThat(PageCheckerService.resultAlarmWorthy(monitor(1), List.of())).isFalse();
    }

    @Test
    @DisplayName("Eşik 2: tek kırık alarm açmaz, iki kırık açar")
    void thresholdTwo() {
        assertThat(PageCheckerService.resultAlarmWorthy(monitor(2), List.of(broken("a.js")))).isFalse();
        assertThat(PageCheckerService.resultAlarmWorthy(monitor(2), List.of(broken("a.js"), broken("b.js")))).isTrue();
    }

    @Test
    @DisplayName("Eşik timeout/mixed'i ETKİLEMEZ: tek timeout / tek mixed yine alarm açar (kendi aç/kapasıyla)")
    void thresholdOnlyGatesBroken() {
        assertThat(PageCheckerService.resultAlarmWorthy(monitor(5), List.of(timeout("a.png")))).isTrue();
        assertThat(PageCheckerService.resultAlarmWorthy(monitor(5), List.of(mixed("http://x/a.png")))).isTrue();
        PageMonitor noTimeout = monitor(5);
        noTimeout.setAlertTimeout(false);   // timeout kapalıyken sayılmaz
        assertThat(PageCheckerService.resultAlarmWorthy(noTimeout, List.of(timeout("a.png")))).isFalse();
    }

    @Test
    @DisplayName("Eşik altı kırık + bir timeout → timeout açar (karışık; eşik yalnız kırığı geçitler)")
    void belowBrokenButTimeoutOpens() {
        assertThat(PageCheckerService.resultAlarmWorthy(monitor(3), List.of(broken("a.js"), timeout("b.png")))).isTrue();
    }

    @Test
    @DisplayName("minBrokenResources clamp: 0/negatif → 1; null → 1; geçerli değer korunur")
    void clamp() {
        assertThat(PageCheckerService.minBrokenResources(monitor(0))).isEqualTo(1);
        assertThat(PageCheckerService.minBrokenResources(monitor(-5))).isEqualTo(1);
        assertThat(PageCheckerService.minBrokenResources(monitor(null))).isEqualTo(1);
        assertThat(PageCheckerService.minBrokenResources(monitor(7))).isEqualTo(7);
    }

    @Test
    @DisplayName("null güvenliği: null izleme / null liste false")
    void nullSafety() {
        assertThat(PageCheckerService.resultAlarmWorthy(null, List.of(broken("a.js")))).isFalse();
        assertThat(PageCheckerService.resultAlarmWorthy(monitor(1), null)).isFalse();
    }
}
