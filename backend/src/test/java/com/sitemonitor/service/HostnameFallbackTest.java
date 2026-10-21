package com.sitemonitor.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Örnek kimliğinin host kısmı (2026-10-21, çok pod R1-A, MP-21): makine adı çözülemezse SABİT "node" dönmemeli — birden çok
 * pod "node-…" olursa açılış temizliği ("aynı host'un önceki örneği") canlı pod'ların kilitlerini ve tarama liderliği
 * kirasını siler. Sıra: makine adı → HOSTNAME → POD_NAME → rastgele.
 */
class HostnameFallbackTest {

    static String resolve(String local, Map<String, String> env) {
        AtomicInteger seq = new AtomicInteger();
        return SchedulerService.resolveHostname(() -> local, env::get, () -> "r" + seq.incrementAndGet());
    }

    @Test
    @DisplayName("makine adı varsa o kullanılır")
    void localHostWins() {
        assertThat(resolve("sitemonitor-7d9f8b6c5-abcde", Map.of("HOSTNAME", "baska"))).isEqualTo("sitemonitor-7d9f8b6c5-abcde");
    }

    @Test
    @DisplayName("makine adı çözülemezse HOSTNAME, o da yoksa POD_NAME ortam değişkeni (Kubernetes pod adı)")
    void envFallbacks() {
        assertThat(resolve(null, Map.of("HOSTNAME", "sitemonitor-1", "POD_NAME", "x"))).isEqualTo("sitemonitor-1");
        assertThat(resolve(" ", Map.of("POD_NAME", "sitemonitor-2"))).isEqualTo("sitemonitor-2");
        assertThat(resolve("localhost", Map.of("POD_NAME", "sitemonitor-3"))).as("localhost anlamsız").isEqualTo("sitemonitor-3");
    }

    @Test
    @DisplayName("hiçbiri yoksa RASTGELE kimlik — asla ortak sabit 'node' değil; iki pod farklı")
    void randomNeverConstant() {
        String a = SchedulerService.resolveHostname(() -> null, k -> null, () -> "aaaa1111");
        String b = SchedulerService.resolveHostname(() -> "node", k -> "", () -> "bbbb2222");
        assertThat(a).isEqualTo("host-aaaa1111");
        assertThat(b).isEqualTo("host-bbbb2222");
        assertThat(a).isNotEqualTo(b).isNotEqualTo("node");
    }

    @Test
    @DisplayName("LIKE kalıbında anlamı olan karakterler (% _) temizlenir — açılış temizliği başka host'u eşlemesin")
    void likeWildcardsCleaned() {
        assertThat(resolve("web_01%", Map.of())).isEqualTo("web-01");
    }

    @Test
    @DisplayName("çalışan JVM'in kimliği bu kurala uyar (boş değil, 'node' değil)")
    void currentInstance() {
        assertThat(SchedulerService.hostname()).isNotBlank().isNotEqualTo("node");
        assertThat(SchedulerService.instanceId()).startsWith(SchedulerService.hostname() + "-");
    }
}
