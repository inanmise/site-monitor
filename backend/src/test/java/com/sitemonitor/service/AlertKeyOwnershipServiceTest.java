package com.sitemonitor.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Alarm anahtarı → sahip takım (2026-09-28, A1/A4) — gerçek SQL (H2/Postgres kipi). Bakım penceresi kapısı ve teyit
 * listesi süzgeci bu çözümlemeye dayanır; yanlış bir "sahip yok" kapıyı yanlış yere AÇAR.
 */
class AlertKeyOwnershipServiceTest {

    JdbcTemplate jdbc;
    AlertKeyOwnershipService svc;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource ds = new DriverManagerDataSource("jdbc:h2:mem:ownershiptest;DB_CLOSE_DELAY=-1;MODE=PostgreSQL", "sa", "");
        jdbc = new JdbcTemplate(ds);
        for (String t : List.of("certificate_inventory", "http_monitors", "ping_monitors", "port_monitors", "dns_monitors",
                "keyword_monitors", "page_monitors", "pagespeed_monitors", "scripted_monitors", "domain_monitors"))
            jdbc.update("DROP TABLE IF EXISTS " + t);
        jdbc.update("CREATE TABLE certificate_inventory(domain VARCHAR(253), team_id BIGINT, ug_team_id BIGINT, deleted_at VARCHAR(30))");
        jdbc.update("CREATE TABLE http_monitors(id BIGINT, name VARCHAR(100), url VARCHAR(300), team_id BIGINT)");
        jdbc.update("CREATE TABLE ping_monitors(id BIGINT, name VARCHAR(100), host VARCHAR(300), team_id BIGINT)");
        jdbc.update("CREATE TABLE port_monitors(id BIGINT, name VARCHAR(100), host VARCHAR(300), team_id BIGINT, standalone BOOLEAN, deleted_at VARCHAR(30))");
        jdbc.update("CREATE TABLE dns_monitors(id BIGINT, name VARCHAR(100), domain VARCHAR(300), team_id BIGINT, standalone BOOLEAN, deleted_at VARCHAR(30))");
        jdbc.update("CREATE TABLE keyword_monitors(id BIGINT, name VARCHAR(100), url VARCHAR(300), team_id BIGINT)");
        jdbc.update("CREATE TABLE page_monitors(id BIGINT, name VARCHAR(100), url VARCHAR(300), team_id BIGINT)");
        jdbc.update("CREATE TABLE pagespeed_monitors(id BIGINT, name VARCHAR(100), url VARCHAR(300), team_id BIGINT)");
        jdbc.update("CREATE TABLE scripted_monitors(id BIGINT, name VARCHAR(100), team_id BIGINT)");
        jdbc.update("CREATE TABLE domain_monitors(id BIGINT, name VARCHAR(100), domain VARCHAR(300), team_id BIGINT)");

        // Envanter: d.example.com SY=3 UG=4; ug-only.example.com SY yok UG=4; silinmiş kayıt sayılmaz
        jdbc.update("INSERT INTO certificate_inventory VALUES ('d.example.com', 3, 4, NULL), ('ug-only.example.com', NULL, 4, NULL),"
                + " ('gone.example.com', 7, NULL, '2026-09-01T00:00:00')");
        // Aynı URL'yi iki takım izliyor (HTTP takım başına tekil — takımlar arası mükerrer serbest)
        jdbc.update("INSERT INTO http_monitors VALUES (1, 'A', 'https://shared.example.com/', 1), (2, 'B', 'https://shared.example.com/', 2)");
        // Port: d üzerinde envanter-türevi satır BAYAT team_id=7 taşıyor (envanter sahibi 3 olmalı) + standalone takım 8;
        // envanteri olmayan türev satır (yetim) saklanan takıma düşer
        jdbc.update("INSERT INTO port_monitors VALUES (10, 'd', 'd.example.com', 7, FALSE, NULL), (11, 'd', 'd.example.com', 8, TRUE, NULL),"
                + " (12, 'orphan', 'orphan.example.com', 6, NULL, NULL)");
        // DNS: silinmiş standalone satır sayılmaz
        jdbc.update("INSERT INTO dns_monitors VALUES (20, 'd', 'd.example.com', 9, TRUE, '2026-09-27T10:00:00')");
        // Takımsız ping
        jdbc.update("INSERT INTO ping_monitors VALUES (30, 'legacy', 'legacy.example.com', NULL)");
        // Sentetik: alarm anahtarı ADDIR
        jdbc.update("INSERT INTO scripted_monitors VALUES (40, 'Giriş akışı', 5)");
        // Ping de d'yi izliyor (takım 1) — anahtar türden bağımsız
        jdbc.update("INSERT INTO ping_monitors VALUES (31, 'd ping', 'd.example.com', 1)");
        svc = new AlertKeyOwnershipService(jdbc);
    }

    @Test
    @DisplayName("sahipler: iki takımın aynı URL'si ikisi; çift kaynaklı port envantere; silinmiş/yok sayılmaz; takımsız null")
    void ownerTeams() {
        Map<String, Set<Long>> o = svc.ownerTeams(List.of("https://shared.example.com/", "d.example.com",
                "orphan.example.com", "legacy.example.com", "Giriş akışı", "ug-only.example.com", "gone.example.com",
                "yok.example.com"));

        assertThat(o.get("https://shared.example.com/")).containsExactlyInAnyOrder(1L, 2L);
        // envanter SY 3 + standalone port 8 + ping 1; bayat türev team_id 7 ve silinmiş DNS 9 YOK; UG 4 sahip DEĞİL
        assertThat(o.get("d.example.com")).containsExactlyInAnyOrder(3L, 8L, 1L);
        assertThat(o.get("orphan.example.com")).containsExactly(6L);
        Set<Long> nullOnly = new HashSet<>();
        nullOnly.add(null);
        assertThat(o.get("legacy.example.com")).isEqualTo(nullOnly);
        assertThat(o.get("Giriş akışı")).containsExactly(5L);
        assertThat(o.get("ug-only.example.com")).containsExactly(4L);      // SY yoksa UG sahip
        assertThat(o).doesNotContainKeys("gone.example.com", "yok.example.com");
    }

    @Test
    @DisplayName("görüş takımları: sahiplere envanterin UG takımı eklenir")
    void viewerTeams_includeOversight() {
        assertThat(svc.viewerTeams(List.of("d.example.com")).get("d.example.com")).containsExactlyInAnyOrder(3L, 4L, 8L, 1L);
    }

    @Test
    @DisplayName("parçalı IN: CHUNK'tan fazla anahtar tek çağrıda doğru çözülür; boş/null girdi boş harita")
    void chunkingAndEmpty() {
        List<String> many = new ArrayList<>();
        for (int i = 0; i < AlertKeyOwnershipService.CHUNK * 2 + 7; i++) many.add("x" + i + ".example.com");
        many.add("https://shared.example.com/");
        assertThat(svc.ownerTeams(many)).containsOnlyKeys("https://shared.example.com/");
        assertThat(svc.ownerTeams(List.of())).isEmpty();
        assertThat(svc.ownerTeams(null)).isEmpty();
    }
}
