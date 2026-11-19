package com.sitemonitor.service.monitorcsv;

import com.sitemonitor.model.DbConnectionProfile;
import com.sitemonitor.model.NocNotificationGroup;
import com.sitemonitor.model.NotificationGroup;
import com.sitemonitor.model.Team;
import com.sitemonitor.repository.DbConnectionProfileRepository;
import com.sitemonitor.repository.DbQueryMonitorRepository;
import com.sitemonitor.repository.DnsMonitorRepository;
import com.sitemonitor.repository.DomainMonitorRepository;
import com.sitemonitor.repository.HttpMonitorRepository;
import com.sitemonitor.repository.KeywordMonitorRepository;
import com.sitemonitor.repository.NocNotificationGroupRepository;
import com.sitemonitor.repository.NotificationGroupRepository;
import com.sitemonitor.repository.PageMonitorRepository;
import com.sitemonitor.repository.PageSpeedMonitorRepository;
import com.sitemonitor.repository.PingMonitorRepository;
import com.sitemonitor.repository.PortMonitorRepository;
import com.sitemonitor.repository.ScriptedMonitorRepository;
import com.sitemonitor.repository.TeamRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * İzleme CSV dışa / içe aktarımı — UÇTAN UCA (2026-11-19). GERÇEK Spring bağlamı ({@code test} profili, H2) ve GERÇEK izleme
 * uçları (MonitoringController create / update — {@link MonitoringControllerCsvWriter}). Her tür için: kaynak takımda izleme
 * oluştur → dışa aktar → kaynağı sil (UAT → prod gibi) → hedef takıma önizleme (HİÇBİR şey yazmaz, denetim de) → gerçek koşum →
 * hedefi dışa aktar = kaynak (sır ve yalnız-bilgi sütunları hariç) → aynı dosya yeniden: "değişiklik yok" → tek hücre değişince
 * yalnız o alan güncellenir. Sırlar dışa aktarım baytlarında YOK. Ayrıca belirsiz ad, başka takımın dbquery adı, bulunamayan
 * bildirim grubu, yetkisiz takım ve bilinmeyen sütun.
 */
// Kendi H2 örneği + NON_KEYWORDS=VALUE: test profilinin ortak H2'sinde dns_records ("value" sütunu) oluşmuyor ve DNS
// güncelleme ucu son kaydı okurken düşüyordu (PostgreSQL'de sorun yok) — bkz. HistoryQueryGrammarTest
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:monitorcsv;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;NON_KEYWORDS=VALUE")
@ActiveProfiles("test")
@Timeout(value = 300, unit = TimeUnit.SECONDS)
class MonitorCsvServiceIntegrationTest {

    @Autowired MonitorCsvService service;
    @Autowired MonitorCsvWriter writer;
    @Autowired TeamRepository teamRepo;
    @Autowired NotificationGroupRepository groupRepo;
    @Autowired NocNotificationGroupRepository nocRepo;
    @Autowired DbConnectionProfileRepository connRepo;
    @Autowired JdbcTemplate jdbc;
    @Autowired HttpMonitorRepository httpRepo;
    @Autowired KeywordMonitorRepository keywordRepo;
    @Autowired PageMonitorRepository pageRepo;
    @Autowired PageSpeedMonitorRepository pageSpeedRepo;
    @Autowired PingMonitorRepository pingRepo;
    @Autowired PortMonitorRepository portRepo;
    @Autowired DnsMonitorRepository dnsRepo;
    @Autowired DomainMonitorRepository domainRepo;
    @Autowired ScriptedMonitorRepository scriptedRepo;
    @Autowired DbQueryMonitorRepository dbQueryRepo;

    String sfx;
    Team teamA;
    Team teamB;
    Team teamC;
    Long nocId;
    MockHttpSession admin;

    @BeforeEach
    void setUp() {
        sfx = UUID.randomUUID().toString().substring(0, 6);
        teamA = team("CSV Kaynak " + sfx);
        teamB = team("CSV Hedef " + sfx);
        teamC = team("CSV Yabancı " + sfx);
        for (Team t : List.of(teamA, teamB)) {
            NotificationGroup g = new NotificationGroup();
            g.setTeamId(t.getId());
            g.setName("CSV Bildirim " + sfx);
            g.setEmails("ops-" + sfx + "@example.invalid");
            g.setActive(true);
            g.setIsDefault(false);
            groupRepo.save(g);
            DbConnectionProfile p = new DbConnectionProfile();
            p.setTeamId(t.getId());
            p.setName("CSV Bağlantı " + sfx);
            p.setDbType("ORACLE");   // maliyet ön kontrolü Oracle'da bağlanmadan "bilinmiyor" döner — test ağa çıkmaz
            p.setHost("dbq-csv.example.invalid");
            p.setPort(1521);
            p.setDatabaseName("ORCL");
            p.setUsername("ro");
            connRepo.save(p);
        }
        NocNotificationGroup noc = new NocNotificationGroup();
        noc.setName("CSV NOC " + sfx);
        noc.setEmails("noc-" + sfx + "@example.invalid");
        noc.setActive(true);
        noc.setIsDefault(false);
        nocId = nocRepo.save(noc).getId();
        admin = new MockHttpSession();
        admin.setAttribute("systemRole", "ADMIN");
        admin.setAttribute("username", "csv-admin-" + sfx);
    }

    private Team team(String name) {
        Team t = new Team();
        t.setName(name);
        t.setActive(true);
        return teamRepo.save(t);
    }

    private Long groupOf(Team t) {
        return groupRepo.findByTeamIdOrderByNameAsc(t.getId()).get(0).getId();
    }

    private Long connOf(Team t) {
        return connRepo.findByTeamIdOrderByNameAsc(t.getId()).get(0).getId();
    }

    @SuppressWarnings("unchecked")
    private JpaRepository<Object, Long> repo(String type) {
        Object r = switch (type) {
            case "http" -> httpRepo;
            case "keyword" -> keywordRepo;
            case "page" -> pageRepo;
            case "pagespeed" -> pageSpeedRepo;
            case "ping" -> pingRepo;
            case "port" -> portRepo;
            case "dns" -> dnsRepo;
            case "domain" -> domainRepo;
            case "scripted" -> scriptedRepo;
            case "dbquery" -> dbQueryRepo;
            default -> throw new IllegalArgumentException(type);
        };
        return (JpaRepository<Object, Long>) r;
    }

    /** Türün zengin kaynak gövdesi (gerçek uca verilir) — sırlar dahil. */
    private Map<String, Object> sourceBody(String type, String name) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("name", name);
        b.put("teamId", teamA.getId());
        b.put("groupName", "CSV Grup " + sfx);
        b.put("tags", "csv,göç");
        b.put("active", false);   // arka plan taramaları test izlemesini koşmasın
        b.put("confirmAttempts", 2);
        b.put("recoveryChecks", 3);
        b.put("alertLevel", "HIGH");
        b.put("notifyEmail", false);
        b.put("notificationGroupId", groupOf(teamA));
        b.put("nocNotify", true);
        b.put("nocGroupIds", List.of(nocId));
        switch (type) {
            case "http" -> {
                b.put("url", "https://csv-http-" + sfx + ".example.invalid/health?x=1");
                b.put("method", "POST");
                b.put("expectedStatus", "200-299");
                b.put("requestBody", "{\"a\":1,\n\"b\":\"iki, satır\"}");
                b.put("requestContentType", "application/json");
                b.put("basicAuthUser", "svc");
                b.put("basicAuthPass", "secret-http-pass-" + sfx);
                b.put("customHeaders", "X-Api-Key: hdr-secret-value-" + sfx);
                b.put("intervalSeconds", 120);
                b.put("timeoutMs", 5000);
                b.put("followRedirects", false);
                b.put("slowResponseEnabled", true);
                b.put("slowThresholdMs", 1500);
            }
            case "keyword" -> {
                b.put("url", "https://csv-kw-" + sfx + ".example.invalid/");
                b.put("keyword", "Hoş geldiniz");
                b.put("operator", "GTE");
                b.put("matchCount", 2);
                b.put("basicAuthUser", "kwuser");
                b.put("basicAuthPass", "secret-kw-pass-" + sfx);
                b.put("customHeaders", "X-Token: kw-hdr-secret-" + sfx);
                b.put("timeoutMs", 10000);
                b.put("intervalSeconds", 300);
                b.put("mailSubjectTemplate", "{ad} — {neden}");
                b.put("minBytes", 10);
            }
            case "page" -> {
                b.put("url", "https://csv-page-" + sfx + ".example.invalid/");
                b.put("mode", "SITE_CRAWL");
                b.put("crawlDepth", 2);
                b.put("crawlMaxPages", 50);
                b.put("excludePatterns", "*/logout*\n*/admin*");
                b.put("slowResourceMs", 3000);
                b.put("minBrokenResources", 2);
                b.put("intervalSeconds", 600);
            }
            case "pagespeed" -> {
                b.put("url", "https://csv-ps-" + sfx + ".example.invalid/");
                b.put("intervalSeconds", 1800);
                b.put("maxLoadMs", 4000);
                b.put("maxPageKb", 3000);
                b.put("maxJsKb", 800);
                b.put("userAgent", "CSV-UA/1.0");
                b.put("basicAuthUser", "psuser");
                b.put("basicAuthPass", "secret-ps-pass-" + sfx);
                b.put("schedule_mode", "INTERVAL");
                b.put("windows", List.of(Map.of("days", List.of(1, 2, 3, 4, 5), "from", "08:00", "to", "20:00")));
                b.put("skip_holidays", true);
            }
            case "ping" -> {
                b.put("host", "csv-ping-" + sfx + ".example.invalid");
                b.put("ipVersion", "v4");
                b.put("packetCount", 3);
                b.put("intervalSeconds", 120);
            }
            case "port" -> {
                b.put("host", "csv-port-" + sfx + ".example.invalid");
                b.put("port", 8443);
                b.put("protocol", "TCP");
                b.put("expect", "-SSH");   // formül koruması: '-' ile başlayan değer geri aynen gelmeli
                b.put("timeoutMs", 3000);
            }
            case "dns" -> {
                b.put("domain", "csv-dns-" + sfx + ".example.invalid");
                b.put("recordType", "MX");
                b.put("expectedValue", "mx1.example.invalid\nmx2.example.invalid");
                b.put("propagationCheck", true);
                b.put("dnsChangeAlertEnabled", false);
            }
            case "domain" -> {
                b.put("domain", "csvsample" + sfx + ".com");
                b.put("thresholdsCsv", "60,30,7");
                b.put("warningDays", 45);
                b.put("criticalDays", 10);
                b.put("intervalSeconds", 86400);
                b.put("transferLockAlert", true);
                b.put("blacklistEnabled", false);
            }
            case "scripted" -> {
                b.put("description", "CSV ile taşınan senaryo\nikinci satır");
                b.put("script", "import http from 'k6/http';\nimport { check } from 'k6';\n\nexport default function () {\n"
                        + "  const r = http.get(__ENV.BASE, { headers: { Authorization: __ENV.TOKEN } });\n"
                        + "  check(r, { 'ok': (x) => x.status === 200 });\n}\n");
                b.put("env", List.of(Map.of("name", "BASE", "value", "https://csv-k6.example.invalid", "secret", false),
                        Map.of("name", "TOKEN", "value", "s3cr3t-k6-token-" + sfx, "secret", true)));
                b.put("timeoutSeconds", 60);
                b.put("intervalSeconds", 600);
            }
            case "dbquery" -> {
                b.put("profile_id", connOf(teamA));
                b.put("purpose", "Sipariş kuyruğu birikmesin");
                b.put("sql_text", "SELECT COUNT(*) AS n FROM orders WHERE status = 'NEW'");
                b.put("rules", List.of(Map.of("kind", "ROW_COUNT", "operator", "GT", "warning", 0)));
                b.put("interval_seconds", 600);
                b.put("max_rows", 100);
                b.put("query_timeout_seconds", 30);
                b.put("mail_include_rows", true);
                b.put("mail_columns", List.of("n"));
                b.put("mail_columns_mode", "SELECTED");
                b.put("mail_subject_template", "{ad}");
            }
            default -> throw new IllegalArgumentException(type);
        }
        return b;
    }

    private int auditRows() {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM audit_log", Integer.class);
        return n == null ? 0 : n;
    }

    private long countInTeam(String type, Long teamId) {
        return service.load(type).stream().filter(e -> teamId.equals(MonitorCsvService.teamOf(e))).count();
    }

    /** CSV → başlık sırasıyla satır haritaları. */
    private static List<Map<String, String>> rows(String csv) throws Exception {
        MonitorCsvParser.Parsed p = MonitorCsvParser.parse(csv);
        List<Map<String, String>> out = new ArrayList<>();
        for (MonitorCsvParser.Record r : p.rows()) {
            Map<String, String> m = new LinkedHashMap<>();
            for (int i = 0; i < p.header().cells().size(); i++) {
                m.put(p.header().cells().get(i), i < r.cells().size() ? MonitorCsvCodec.unguard(r.cells().get(i)) : "");
            }
            out.add(m);
        }
        return out;
    }

    private static final Map<String, List<String>> EXPECTED_SECRETS = Map.of(
            "http", List.of("basic_auth_pass", "custom_headers"),
            "keyword", List.of("basic_auth_pass", "custom_headers"),
            "pagespeed", List.of("basic_auth_pass"),
            "scripted", List.of("env:TOKEN"));

    @ParameterizedTest(name = "{0}: dışa aktar → kaynağı sil → önizleme (yazmaz) → içe aktar = kaynak; yeniden = değişiklik yok; tek hücre = tek alan")
    @ValueSource(strings = {"http", "keyword", "page", "pagespeed", "ping", "port", "dns", "domain", "scripted", "dbquery"})
    void roundTrip(String type) throws Exception {
        String name = ("http".equals(type) ? "=CSV Formül " : "CSV " + type + " ") + sfx;
        MonitorCsvWriter.Result created = writer.create(type, sourceBody(type, name), admin);
        assertThat(created.ok()).as("kaynak oluşturulamadı: " + created.message() + " / " + created.field()).isTrue();
        Long sourceId = created.id();
        assertThat(sourceId).isNotNull();

        MonitorCsvService.Export ex = service.export(type, admin, teamA.getId(), null);
        assertThat(ex.rows()).isEqualTo(1);
        String csvA = ex.csv();
        assertThat(csvA).startsWith("﻿");
        for (String secret : List.of("secret-http-pass-", "hdr-secret-value-", "secret-kw-pass-", "kw-hdr-secret-",
                "secret-ps-pass-", "s3cr3t-k6-token-")) {
            assertThat(csvA).as("sır dışa aktarımda: " + secret).doesNotContain(secret);
        }
        assertThat(csvA).contains("CSV Bildirim " + sfx).contains("CSV NOC " + sfx).contains("CSV Kaynak " + sfx);

        // UAT → prod: kaynak kaldırılır (Port / DNS / dbquery tekilliği takımlar arası), dosya hedef takıma çevrilir
        repo(type).deleteById(sourceId);
        String csvB = csvA.replace("CSV Kaynak " + sfx, "CSV Hedef " + sfx);

        int auditBefore = auditRows();
        MonitorCsvService.ImportResult dry = service.importCsv(type, csvB, true, admin);
        assertThat(dry.rows()).hasSize(1);
        MonitorCsvService.RowResult dr = dry.rows().get(0);
        assertThat(dr.action()).as("önizleme: " + dr.message() + " / " + dr.field()).isEqualTo(MonitorCsvService.A_CREATE);
        assertThat(dr.secrets()).containsExactlyInAnyOrderElementsOf(EXPECTED_SECRETS.getOrDefault(type, List.of()));
        assertThat(countInTeam(type, teamB.getId())).as("önizleme yazmaz").isZero();
        assertThat(auditRows()).as("önizleme denetim yazmaz").isEqualTo(auditBefore);

        MonitorCsvService.ImportResult real = service.importCsv(type, csvB, false, admin);
        MonitorCsvService.RowResult rr = real.rows().get(0);
        assertThat(rr.status()).as("gerçek koşum: " + rr.message() + " / " + rr.field()).isEqualTo(MonitorCsvService.S_DONE);
        assertThat(countInTeam(type, teamB.getId())).isEqualTo(1);

        String csvB2 = service.export(type, admin, teamB.getId(), null).csv();
        Map<String, String> expected = rows(csvB).get(0);
        Map<String, String> actual = rows(csvB2).get(0);
        for (MonitorCsvSpec.Column c : MonitorCsvSpec.of(type).columns()) {
            if (c.info()) continue;
            assertThat(actual.get(c.header())).as(type + "." + c.header()).isEqualTo(expected.get(c.header()));
        }

        MonitorCsvService.ImportResult again = service.importCsv(type, csvB, true, admin);
        assertThat(again.rows().get(0).action()).as("aynı dosya: " + again.rows().get(0).changed()).isEqualTo(MonitorCsvService.A_UNCHANGED);

        // Tek hücre: confirm_attempts 2 → 4 → yalnız o alan
        String changedCsv = MonitorCsvCodec.row(new ArrayList<>(rows(csvB).get(0).keySet()));
        Map<String, String> row = new LinkedHashMap<>(rows(csvB).get(0));
        row.put("confirm_attempts", "4");
        changedCsv += MonitorCsvCodec.row(new ArrayList<>(row.values()));
        MonitorCsvService.ImportResult upd = service.importCsv(type, changedCsv, true, admin);
        assertThat(upd.rows().get(0).action()).isEqualTo(MonitorCsvService.A_UPDATE);
        assertThat(upd.rows().get(0).changed()).containsExactly("confirm_attempts");
        assertThat(upd.rows().get(0).secrets()).as("güncellemede saklı sırlar korunur; yalnız eksik olanlar")
                .containsExactlyInAnyOrderElementsOf(EXPECTED_SECRETS.getOrDefault(type, List.of()));
        MonitorCsvService.ImportResult updReal = service.importCsv(type, changedCsv, false, admin);
        assertThat(updReal.rows().get(0).status()).as(updReal.rows().get(0).message()).isEqualTo(MonitorCsvService.S_DONE);
        Object stored = service.load(type).stream().filter(e -> teamB.getId().equals(MonitorCsvService.teamOf(e))).findFirst().orElseThrow();
        assertThat(MonitorCsvSpec.prop("confirmAttempts").apply(stored)).isEqualTo(4);
        assertThat(auditRows()).as("gerçek koşum izleme denetimini yazar").isGreaterThan(auditBefore);
    }

    @Test
    @DisplayName("takımda aynı adla iki izleme → satır hatası (tahmin yok); dosyada aynı takım + ad iki kez → ikincisi hata")
    void ambiguousAndDuplicateRows() {
        for (int i = 0; i < 2; i++) {
            Map<String, Object> b = sourceBody("http", "CSV İkiz " + sfx);
            b.put("url", "https://csv-twin-" + i + "-" + sfx + ".example.invalid/");
            b.remove("basicAuthPass");
            b.remove("customHeaders");
            assertThat(writer.create("http", b, admin).ok()).isTrue();
        }
        String csv = "name,team,interval_seconds\n"
                + "CSV İkiz " + sfx + ",CSV Kaynak " + sfx + ",90\n"
                + "Yeni " + sfx + ",CSV Kaynak " + sfx + ",90\n"
                + "yeni " + sfx + ",CSV Kaynak " + sfx + ",90\n";
        MonitorCsvService.ImportResult r = service.importCsv("http", csv, true, admin);
        assertThat(r.rows().get(0).action()).isEqualTo(MonitorCsvService.A_ERROR);
        assertThat(r.rows().get(0).field()).isEqualTo("name");
        // yeni kayıt: zorunlu sütunlar (url, grup, etiket) boş → hata, ilk eksik alan adıyla
        assertThat(r.rows().get(1).action()).isEqualTo(MonitorCsvService.A_ERROR);
        assertThat(r.rows().get(1).message()).contains("url");
        assertThat(r.rows().get(2).action()).isEqualTo(MonitorCsvService.A_ERROR);
        assertThat(r.rows().get(2).field()).isEqualTo("name");
    }

    @Test
    @DisplayName("dbquery adı başka takımda kullanılıyorsa satır hatası; bulunamayan bildirim grubu / bağlantı adı satır hatası")
    void referenceErrors() {
        Map<String, Object> b = sourceBody("dbquery", "CSV Küresel " + sfx);
        assertThat(writer.create("dbquery", b, admin).ok()).isTrue();
        String csv = "name,team,connection,purpose,sql_text,group_name,tags,notification_group\n"
                + "CSV Küresel " + sfx + ",CSV Hedef " + sfx + ",CSV Bağlantı " + sfx + ",Amaç metni burada,SELECT 1 AS x FROM dual,G,t,\n"
                + "CSV Başka " + sfx + ",CSV Hedef " + sfx + ",Yok Bağlantı,Amaç metni burada,SELECT 1 AS x FROM dual,G,t,\n"
                + "CSV Üçüncü " + sfx + ",CSV Hedef " + sfx + ",CSV Bağlantı " + sfx + ",Amaç metni burada,SELECT 1 AS x FROM dual,G,t,Olmayan Grup\n";
        MonitorCsvService.ImportResult r = service.importCsv("dbquery", csv, true, admin);
        assertThat(r.rows().get(0).action()).isEqualTo(MonitorCsvService.A_ERROR);
        assertThat(r.rows().get(0).field()).isEqualTo("name");
        assertThat(r.rows().get(1).field()).isEqualTo("connection");
        assertThat(r.rows().get(2).field()).isEqualTo("notification_group");
    }

    @Test
    @DisplayName("dbquery: SQL güvenlik denetimi önizlemede (DELETE reddedilir) — hiçbir şey yazılmaz")
    void dbquerySqlGuard() {
        String csv = "name,team,connection,purpose,sql_text,group_name,tags\n"
                + "CSV Kötü " + sfx + ",CSV Hedef " + sfx + ",CSV Bağlantı " + sfx + ",Amaç metni burada,DELETE FROM orders,G,t\n";
        MonitorCsvService.ImportResult r = service.importCsv("dbquery", csv, false, admin);
        assertThat(r.rows().get(0).action()).isEqualTo(MonitorCsvService.A_ERROR);
        assertThat(r.rows().get(0).field()).isEqualTo("sql_text");
        assertThat(r.rows().get(0).status()).isEqualTo(MonitorCsvService.S_FAILED);
        assertThat(countInTeam("dbquery", teamB.getId())).isZero();
    }

    @Test
    @DisplayName("yetkisiz takım: kullanıcı yalnız A'nın üyesi — C takımındaki satır hata; kendi takımı geçer")
    void teamWithoutPermission() {
        MockHttpSession user = new MockHttpSession();
        user.setAttribute("systemRole", "USER");
        user.setAttribute("username", "csv-user-" + sfx);
        user.setAttribute("teamId", teamA.getId());
        user.setAttribute("viewTeamIds", List.of(teamA.getId()));
        user.setAttribute("memberTeamIds", List.of(teamA.getId()));
        String csv = "name,team,url,group_name,tags\n"
                + "CSV Yetkisiz " + sfx + ",CSV Yabancı " + sfx + ",https://csv-noperm-" + sfx + ".example.invalid/,G,t\n"
                + "CSV Yetkili " + sfx + ",CSV Kaynak " + sfx + ",https://csv-perm-" + sfx + ".example.invalid/,G,t\n";
        MonitorCsvService.ImportResult r = service.importCsv("http", csv, true, user);
        assertThat(r.rows().get(0).action()).isEqualTo(MonitorCsvService.A_ERROR);
        assertThat(r.rows().get(0).field()).isEqualTo("team");
        assertThat(r.rows().get(1).action()).isEqualTo(MonitorCsvService.A_CREATE);
    }

    @Test
    @DisplayName("başlık: bilinmeyen / yinelenen sütun ve eksik anahtar sütun dosyanın tamamını reddeder")
    void headerErrors() {
        assertThatThrownBy(() -> service.importCsv("http", "name,team,password\nA,B,x\n", true, admin))
                .isInstanceOfSatisfying(MonitorCsvService.CsvRequestException.class, e -> {
                    assertThat(e.reason()).isEqualTo("unknown_column");
                    assertThat(e.column()).isEqualTo("password");
                });
        assertThatThrownBy(() -> service.importCsv("http", "name,team,url,url\nA,B,x,y\n", true, admin))
                .isInstanceOfSatisfying(MonitorCsvService.CsvRequestException.class, e -> assertThat(e.reason()).isEqualTo("duplicate_column"));
        assertThatThrownBy(() -> service.importCsv("http", "name,url\nA,x\n", true, admin))
                .isInstanceOfSatisfying(MonitorCsvService.CsvRequestException.class, e -> {
                    assertThat(e.reason()).isEqualTo("missing_column");
                    assertThat(e.column()).isEqualTo("team");
                });
    }

    @Test
    @DisplayName("bağımsız inceleme: sırrı taşınmayan YENİ izleme duraklatılmış oluşturulur (önizleme paused, kayıt active=false); sırsız satır etkin kalır")
    void secretMissingCreatesPaused() {
        String csv = "name,team,url,group_name,tags,active,basic_auth_user\n"
                + "CSV Sırlı " + sfx + ",CSV Kaynak " + sfx + ",https://csv-sec-" + sfx + ".example.invalid/,G,t,true,svc\n"
                + "CSV Sırsız " + sfx + ",CSV Kaynak " + sfx + ",https://csv-nosec-" + sfx + ".example.invalid/,G,t,true,\n";
        MonitorCsvService.ImportResult dry = service.importCsv("http", csv, true, admin);
        assertThat(dry.rows().get(0).paused()).isTrue();
        assertThat(dry.rows().get(0).secrets()).containsExactly("basic_auth_pass");
        assertThat(dry.rows().get(1).paused()).isFalse();
        MonitorCsvService.ImportResult real = service.importCsv("http", csv, false, dry.planToken(), admin);
        assertThat(real.rows()).allSatisfy(r -> assertThat(r.status()).as(r.message()).isEqualTo(MonitorCsvService.S_DONE));
        assertThat(real.rows().get(0).paused()).isTrue();
        Map<String, Object> active = new HashMap<>();
        for (Object e : service.load("http")) {
            if (teamA.getId().equals(MonitorCsvService.teamOf(e))) active.put(MonitorCsvService.nameOf(e), MonitorCsvSpec.prop("active").apply(e));
        }
        assertThat(active).containsEntry("CSV Sırlı " + sfx, false).containsEntry("CSV Sırsız " + sfx, true);
    }

    @Test
    @DisplayName("bağımsız inceleme: önizlemeden sonra izleme değişirse gerçek koşum 409 (PlanChangedException) — hiçbir satır yazılmaz; aynı veriyle belirteç aynı")
    void planChangedBetweenPreviewAndApply() {
        Map<String, Object> b = sourceBody("ping", "CSV Plan " + sfx);
        assertThat(writer.create("ping", b, admin).ok()).isTrue();
        String csv = "name,team,host,confirm_attempts,group_name,tags\n"
                + "CSV Plan " + sfx + ",CSV Kaynak " + sfx + ",csv-ping-" + sfx + ".example.invalid,5,G,t\n"
                + "CSV Yeni Plan " + sfx + ",CSV Kaynak " + sfx + ",csv-new-" + sfx + ".example.invalid,2,G,t\n";
        MonitorCsvService.ImportResult dry = service.importCsv("ping", csv, true, admin);
        assertThat(dry.planToken()).hasSize(32);
        assertThat(service.importCsv("ping", csv, true, admin).planToken()).isEqualTo(dry.planToken());
        // başkası confirm_attempts'i dosyadaki değere getirdi → plan "güncelle"den "değişiklik yok"a döner
        Object stored = service.load("ping").stream().filter(e -> ("CSV Plan " + sfx).equals(MonitorCsvService.nameOf(e))).findFirst().orElseThrow();
        Map<String, Object> upd = new LinkedHashMap<>();
        upd.put("confirmAttempts", 5);
        assertThat(writer.update("ping", MonitorCsvService.idOf(stored), upd, admin).ok()).isTrue();
        long before = countInTeam("ping", teamA.getId());
        assertThatThrownBy(() -> service.importCsv("ping", csv, false, dry.planToken(), admin))
                .isInstanceOf(MonitorCsvService.PlanChangedException.class);
        assertThat(countInTeam("ping", teamA.getId())).as("hiçbir satır yazılmadı").isEqualTo(before);
        // belirteçsiz (eski istemci) koşum karşılaştırmaz
        assertThat(service.importCsv("ping", csv, false, null, admin).rows().get(1).status()).isEqualTo(MonitorCsvService.S_DONE);
    }

    @Test
    @DisplayName("bağımsız inceleme: takım adı Türkçe katlanır — \"İZMİR\" / \"izmir\" / \"IZMIR\" aynı takım")
    void turkishTeamNameFolding() {
        team("İzmir CSV " + sfx);
        for (String variant : List.of("IZMIR CSV " + sfx, "izmir csv " + sfx, "İZMİR CSV " + sfx)) {
            String csv = "name,team,host,group_name,tags\nCSV Katla " + sfx + "," + variant + ",csv-fold-" + sfx + ".example.invalid,G,t\n";
            MonitorCsvService.RowResult r = service.importCsv("ping", csv, true, admin).rows().get(0);
            assertThat(r.action()).as(variant + ": " + r.message()).isEqualTo(MonitorCsvService.A_CREATE);
        }
        assertThat(MonitorCsvService.lower(" İSTANBUL ")).isEqualTo(MonitorCsvService.lower("istanbul"))
                .isEqualTo(MonitorCsvService.lower("ISTANBUL")).isEqualTo(MonitorCsvService.lower("ıstanbul"));
    }

    @Test
    @DisplayName("bağımsız inceleme: dışa aktarımda bildirim grubu adı yalnız kendi kapsamındaki takımda; pasif grup yazılmaz")
    void exportGroupNamesOwnScopeAndActiveOnly() throws Exception {
        assertThat(writer.create("ping", sourceBody("ping", "CSV Kapsam " + sfx), admin).ok()).isTrue();
        MockHttpSession noc = new MockHttpSession();
        noc.setAttribute("systemRole", "USER");
        noc.setAttribute("username", "csv-noc-" + sfx);
        noc.setAttribute("viewTeamIds", List.of(teamC.getId()));
        noc.setAttribute("nocOperator", true);
        Map<String, String> asNoc = rows(service.export("ping", noc, teamA.getId(), null).csv()).get(0);
        assertThat(asNoc.get("name")).isEqualTo("CSV Kapsam " + sfx);
        assertThat(asNoc.get("notification_group")).as("7/24 operatörü başka takımın grup adını görmez").isEmpty();
        assertThat(rows(service.export("ping", admin, teamA.getId(), null).csv()).get(0).get("notification_group"))
                .isEqualTo("CSV Bildirim " + sfx);

        NotificationGroup g = groupRepo.findById(groupOf(teamA)).orElseThrow();
        g.setActive(false);
        groupRepo.save(g);
        assertThat(rows(service.export("ping", admin, teamA.getId(), null).csv()).get(0).get("notification_group"))
                .as("pasif grup adı yazılmaz (içe aktarım çözemez)").isEmpty();
    }

    @Test
    @DisplayName("bağımsız inceleme: uygulama kapanırken gerçek koşum yazmaz — satırlar atlandı, stopped=true")
    void closingStopsRealRun() {
        String csv = "name,team,host,group_name,tags\nCSV Kapanış " + sfx + ",CSV Kaynak " + sfx + ",csv-close-" + sfx + ".example.invalid,G,t\n";
        service.closingForTest(true);
        try {
            MonitorCsvService.ImportResult r = service.importCsv("ping", csv, false, admin);
            assertThat(r.stopped()).isTrue();
            assertThat(r.rows().get(0).status()).isEqualTo(MonitorCsvService.S_SKIPPED);
            assertThat(countInTeam("ping", teamA.getId())).isZero();
        } finally {
            service.closingForTest(false);
        }
    }

    @Test
    @DisplayName("bağımsız inceleme: dbquery — aynı SQL yeniden içe aktarılınca güvenlik denetimi koşmaz; denetimin süre sınırı satır hatası değil")
    void dbqueryGuardOnlyForChangedSql() {
        Map<String, Object> b = sourceBody("dbquery", "CSV Denetim " + sfx);
        b.put("teamId", teamB.getId());
        b.put("profile_id", connOf(teamB));
        b.put("notificationGroupId", groupOf(teamB));
        MonitorCsvWriter.Result made = writer.create("dbquery", b, admin);
        assertThat(made.ok()).as(made.message() + " / " + made.field()).isTrue();
        String same = "name,team,connection,purpose,sql_text,group_name,tags\n"
                + "CSV Denetim " + sfx + ",CSV Hedef " + sfx + ",CSV Bağlantı " + sfx + ",Sipariş kuyruğu birikmesin,"
                + "SELECT COUNT(*) AS n FROM orders WHERE status = 'NEW',CSV Grup " + sfx + ",\"csv,göç\"\n";
        String changed = same.replace("status = 'NEW'", "status = 'OLD'");
        try (var guard = org.mockito.Mockito.mockStatic(com.sitemonitor.service.dbquery.DbQuerySqlGuard.class, org.mockito.Mockito.CALLS_REAL_METHODS)) {
            MonitorCsvService.RowResult r = service.importCsv("dbquery", same, true, admin).rows().get(0);
            assertThat(r.action()).as(r.message() + " / " + r.changed()).isIn(MonitorCsvService.A_UNCHANGED, MonitorCsvService.A_UPDATE);
            assertThat(r.changed()).doesNotContain("sql_text");
            guard.verify(() -> com.sitemonitor.service.dbquery.DbQuerySqlGuard.check(org.mockito.ArgumentMatchers.anyString(),
                    org.mockito.ArgumentMatchers.any()), org.mockito.Mockito.never());

            // denetimin süre / izin sınırı: satır hatası değil (kayıt ucu yine denetler)
            com.sitemonitor.service.dbquery.DbQuerySqlGuard.Result timeout = new com.sitemonitor.service.dbquery.DbQuerySqlGuard.Result(false,
                    List.of(new com.sitemonitor.service.dbquery.DbQuerySqlGuard.Violation(com.sitemonitor.service.dbquery.DbQuerySqlGuard.SQL_UNPARSABLE,
                            com.sitemonitor.service.dbquery.DbQuerySqlGuard.TOKEN_TIMEOUT, "zaman", null, null)), List.of());
            guard.when(() -> com.sitemonitor.service.dbquery.DbQuerySqlGuard.check(org.mockito.ArgumentMatchers.anyString(),
                    org.mockito.ArgumentMatchers.any())).thenReturn(timeout);
            MonitorCsvService.RowResult r2 = service.importCsv("dbquery", changed, true, admin).rows().get(0);
            assertThat(r2.action()).as(r2.message()).isEqualTo(MonitorCsvService.A_UPDATE);
            assertThat(r2.changed()).contains("sql_text");
        }
    }

    @Test
    @DisplayName("hatalı hücre: sayı / mantıksal / alarm seviyesi / geçersiz URL — sütun adıyla satır hatası; Türkçe Excel ';' ayırıcısı okunur")
    void cellErrorsAndSemicolon() {
        String csv = "name;team;url;interval_seconds;active;alert_level;group_name;tags\n"
                + "S1 " + sfx + ";CSV Kaynak " + sfx + ";https://csv-s1-" + sfx + ".example.invalid/;altmış;true;HIGH;G;t\n"
                + "S2 " + sfx + ";CSV Kaynak " + sfx + ";https://csv-s2-" + sfx + ".example.invalid/;60;belki;HIGH;G;t\n"
                + "S3 " + sfx + ";CSV Kaynak " + sfx + ";https://csv-s3-" + sfx + ".example.invalid/;60;true;ACİL;G;t\n"
                + "S4 " + sfx + ";CSV Kaynak " + sfx + ";not a url;60;true;HIGH;G;t\n"
                + "S5 " + sfx + ";CSV Kaynak " + sfx + ";https://csv-s5-" + sfx + ".example.invalid/;60;evet;critical;G;t\n";
        MonitorCsvService.ImportResult r = service.importCsv("http", csv, true, admin);
        Map<String, String> fieldByRow = new HashMap<>();
        for (MonitorCsvService.RowResult x : r.rows()) fieldByRow.put(x.name(), x.field());
        assertThat(fieldByRow.get("S1 " + sfx)).isEqualTo("interval_seconds");
        assertThat(fieldByRow.get("S2 " + sfx)).isEqualTo("active");
        assertThat(fieldByRow.get("S3 " + sfx)).isEqualTo("alert_level");
        assertThat(fieldByRow.get("S4 " + sfx)).isEqualTo("url");
        assertThat(r.rows().get(4).action()).isEqualTo(MonitorCsvService.A_CREATE);
    }
}
