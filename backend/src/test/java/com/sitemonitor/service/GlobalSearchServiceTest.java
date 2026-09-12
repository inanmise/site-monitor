package com.sitemonitor.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Komut paleti araması (2026-09-12, #1) — gerçek SQL (H2/Postgres kipi), takım kapsaması çağıranın predicate'i ile. */
class GlobalSearchServiceTest {

    JdbcTemplate jdbc;
    GlobalSearchService svc;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource ds = new DriverManagerDataSource("jdbc:h2:mem:searchtest;DB_CLOSE_DELAY=-1;MODE=PostgreSQL", "sa", "");
        jdbc = new JdbcTemplate(ds);
        for (String t : List.of("certificate_inventory", "teams", "http_monitors", "ping_monitors", "port_monitors", "dns_monitors",
                "keyword_monitors", "page_monitors", "pagespeed_monitors", "scripted_monitors", "domain_monitors"))
            jdbc.update("DROP TABLE IF EXISTS " + t);
        jdbc.update("CREATE TABLE certificate_inventory(domain VARCHAR(253), team_id BIGINT, owner VARCHAR(200), description VARCHAR(500), active BOOLEAN)");
        jdbc.update("CREATE TABLE teams(id BIGINT, name VARCHAR(100))");
        jdbc.update("CREATE TABLE http_monitors(id BIGINT, name VARCHAR(100), url VARCHAR(300), team_id BIGINT)");
        jdbc.update("CREATE TABLE ping_monitors(id BIGINT, name VARCHAR(100), host VARCHAR(300), team_id BIGINT)");
        jdbc.update("CREATE TABLE port_monitors(id BIGINT, name VARCHAR(100), host VARCHAR(300), team_id BIGINT)");
        jdbc.update("CREATE TABLE dns_monitors(id BIGINT, name VARCHAR(100), domain VARCHAR(300), team_id BIGINT)");
        jdbc.update("CREATE TABLE keyword_monitors(id BIGINT, name VARCHAR(100), url VARCHAR(300), team_id BIGINT)");
        jdbc.update("CREATE TABLE page_monitors(id BIGINT, name VARCHAR(100), url VARCHAR(300), team_id BIGINT)");
        jdbc.update("CREATE TABLE pagespeed_monitors(id BIGINT, name VARCHAR(100), url VARCHAR(300), team_id BIGINT)");
        jdbc.update("CREATE TABLE scripted_monitors(id BIGINT, name VARCHAR(100), team_id BIGINT)");
        jdbc.update("CREATE TABLE domain_monitors(id BIGINT, name VARCHAR(100), domain VARCHAR(300), team_id BIGINT)");
        jdbc.update("INSERT INTO certificate_inventory VALUES ('shop.example.com', 1, 'Sahip A', 'Mağaza', TRUE), ('shop-old.example.com', 1, NULL, NULL, FALSE), ('other.example.com', 2, 'Shopkeeper', NULL, TRUE)");
        jdbc.update("INSERT INTO teams VALUES (1, 'Takım A'), (2, 'Takım B'), (3, 'Shop Takımı')");
        jdbc.update("INSERT INTO http_monitors VALUES (7, 'Ödeme', 'https://shop.example.com/pay', 1), (8, 'Gizli', 'https://shop.example.com/x', 2)");
        jdbc.update("INSERT INTO scripted_monitors VALUES (9, 'shop smoke', 1)");
        svc = new GlobalSearchService(jdbc);
    }

    @Test
    @DisplayName("'shop': aktif envanter (pasif elenir), sahip/açıklama eşleşmesi, izleme adı/URL, takım adı; kapsam predicate'i takım 2'yi düşürür")
    void searchScoped() {
        List<GlobalSearchService.Hit> hits = svc.search("Shop", teamId -> teamId != null && teamId != 2L);
        assertThat(hits).extracting(GlobalSearchService.Hit::kind, GlobalSearchService.Hit::id)
                .contains(org.assertj.core.groups.Tuple.tuple("certificate", "shop.example.com"),
                          org.assertj.core.groups.Tuple.tuple("http", "7"),
                          org.assertj.core.groups.Tuple.tuple("scripted", "9"),
                          org.assertj.core.groups.Tuple.tuple("team", "3"))
                .doesNotContain(org.assertj.core.groups.Tuple.tuple("certificate", "shop-old.example.com"),   // pasif
                                org.assertj.core.groups.Tuple.tuple("certificate", "other.example.com"),     // takım 2
                                org.assertj.core.groups.Tuple.tuple("http", "8"),                            // takım 2
                                org.assertj.core.groups.Tuple.tuple("team", "2"));
        GlobalSearchService.Hit cert = hits.stream().filter(h -> "certificate".equals(h.kind())).findFirst().orElseThrow();
        assertThat(cert.tab()).isEqualTo("dashboard");
        assertThat(cert.params()).containsEntry("domain", "shop.example.com");
        assertThat(cert.sub()).isEqualTo("Sahip A");
        GlobalSearchService.Hit http = hits.stream().filter(h -> "http".equals(h.kind())).findFirst().orElseThrow();
        assertThat(http.tab()).isEqualTo("http");
        assertThat(http.params()).containsEntry("monitor", "7");
        assertThat(http.sub()).isEqualTo("https://shop.example.com/pay");
    }

    @Test
    @DisplayName("global görüntüleyici (her takım) hepsini görür; 1 karakter → boş; % ve _ kaçırılır")
    void globalAndGuards() {
        assertThat(svc.search("shop", t -> true)).extracting(GlobalSearchService.Hit::id).contains("other.example.com", "8", "3");
        assertThat(svc.search("s", t -> true)).isEmpty();
        assertThat(svc.search("%", t -> true)).isEmpty();
        assertThat(svc.search("__", t -> true)).isEmpty();
    }
}
