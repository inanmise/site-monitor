package com.sitemonitor.service.metrics;

import com.sitemonitor.SiteMonitorApplication;
import com.sitemonitor.model.Team;
import com.sitemonitor.repository.TeamRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * UÇTAN UCA (gerçek uygulama + gerçek HTTP, 2026-10-18 bağımsız inceleme): kimliksiz {@code /metrics} iş verisi TAŞIMAZ;
 * iş ölçümleri yalnız {@code /metrics/sitemonitor}'da ve yalnız taşıyıcı belirteçle. Takım adı (iş etiketi) bilerek
 * yazılır ve iki uçta aranır.
 */
class MetricsEndpointsIntegrationTest {

    static final String TOKEN = "integration-token-0123456789abcdef";
    static final String TEAM = "Ödeme Sistemleri Gizli Takım";
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    private static ConfigurableApplicationContext start(String token) {
        String db = "metrics" + UUID.randomUUID().toString().replace("-", "");
        return new SpringApplicationBuilder(SiteMonitorApplication.class).run(
                "--server.port=0",
                "--spring.datasource.url=jdbc:h2:mem:" + db + ";DB_CLOSE_DELAY=-1;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE",
                "--spring.jpa.hibernate.ddl-auto=update",
                // Üretimin actuator eşlemesi (test özelliklerinde yalnız health açık): /metrics = Prometheus
                "--management.endpoints.web.exposure.include=health,prometheus,info",
                "--management.endpoints.web.path-mapping.prometheus=metrics",
                "--" + MetricsExportToken.PROPERTY + "=" + token);
    }

    private static HttpResponse<String> get(ConfigurableApplicationContext ctx, String path, String auth) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:"
                + ctx.getEnvironment().getProperty("local.server.port") + path)).GET();
        if (auth != null) b.header("Authorization", auth);
        return HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    @DisplayName("belirteçli: /metrics'te sitemonitor_monitor / target= / takım adı / aktarıcı ölçümü YOK; /metrics/sitemonitor yalnız "
            + "belirteçle 200 ve iş verisini taşır, belirteçsiz 401")
    void businessDataOnlyBehindTheToken() throws Exception {
        try (ConfigurableApplicationContext ctx = start(TOKEN)) {
            Team t = new Team();
            t.setName(TEAM);
            t.setActive(true);
            ctx.getBean(TeamRepository.class).save(t);
            MonitoringMetricsExporter exporter = ctx.getBean(MonitoringMetricsExporter.class);
            assertThat(exporter.tokenConfigured()).isTrue();
            exporter.refresh();

            HttpResponse<String> metrics = get(ctx, "/metrics", null);
            assertThat(metrics.statusCode()).isEqualTo(200);
            assertThat(metrics.body()).contains("jvm_memory_used_bytes")
                    .doesNotContain("sitemonitor_monitor").doesNotContain("sitemonitor_teams").doesNotContain("sitemonitor_exporter")
                    .doesNotContain("target=").doesNotContain(TEAM);

            assertThat(get(ctx, "/metrics/sitemonitor", null).statusCode()).isEqualTo(401);
            assertThat(get(ctx, "/metrics/sitemonitor", "Bearer wrong-token-0123456789").statusCode()).isEqualTo(401);
            HttpResponse<String> business = get(ctx, "/metrics/sitemonitor", "Bearer " + TOKEN);
            assertThat(business.statusCode()).isEqualTo(200);
            assertThat(business.headers().firstValue("Cache-Control")).hasValue("no-store");
            assertThat(business.body()).contains("sitemonitor_exporter_enabled 1.0").contains("sitemonitor_teams{").contains(TEAM);
        }
    }

    @Test
    @DisplayName("belirteçsiz kurulum: /metrics/sitemonitor 404, aktarıcı kapalı; /metrics yine JVM / HTTP ölçümleri")
    void noTokenMeansNoEndpoint() throws Exception {
        try (ConfigurableApplicationContext ctx = start("")) {
            assertThat(ctx.getBean(MonitoringMetricsExporter.class).tokenConfigured()).isFalse();
            assertThat(get(ctx, "/metrics/sitemonitor", "Bearer " + TOKEN).statusCode()).isEqualTo(404);
            HttpResponse<String> metrics = get(ctx, "/metrics", null);
            assertThat(metrics.statusCode()).isEqualTo(200);
            assertThat(metrics.body()).contains("jvm_memory_used_bytes").doesNotContain("sitemonitor_monitor");
        }
    }
}
