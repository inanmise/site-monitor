package com.sitemonitor.service.dbgrowth;

import com.sitemonitor.service.dbgrowth.DbGrowthAdvisor.Context;
import com.sitemonitor.service.dbgrowth.DbGrowthAdvisor.Finding;
import com.sitemonitor.service.dbgrowth.DbGrowthAdvisor.TableFacts;
import com.sitemonitor.service.retention.RetentionCatalog;
import com.sitemonitor.service.retention.RetentionPolicy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Öneri kuralları (saf) — tablo testli. */
class DbGrowthAdvisorTest {

    static final long MB = 1L << 20;
    static final long GB = 1L << 30;

    private static RetentionPolicy policy(String id) {
        return RetentionCatalog.byId(id).orElseThrow();
    }

    private static TableFacts facts(String name, long total, Long table, Long index, Long live, Long dead, Long growth30,
                                    String policyId, Integer eff, Double oldest, Double eq, boolean failed) {
        List<RetentionPolicy> policies = policyId == null ? RetentionCatalog.byTable(name) : List.of(policy(policyId));
        RetentionPolicy primary = policyId == null ? (policies.isEmpty() ? null : policies.get(0)) : policy(policyId);
        return new TableFacts(name, total, table, index, live, dead, growth30, primary, policies, eff, oldest, eq, failed, null, null);
    }

    private static Context ctx(long db) {
        return new Context(db, 0, null, null, false, false);
    }

    private static List<String> codes(List<Finding> l) {
        return l.stream().map(Finding::code).toList();
    }

    @ParameterizedTest(name = "mevcut {0}, taban {1} → öneri {2}")
    @CsvSource({
            "365, 30, 180",
            "180, 30, 90",
            "90, 30, 30",
            "730, 90, 365",
            "60, 30, 30",
            "30, 30, ",
            "45, 30, 30",
            "1095, 90, 365",
            "7, 1, ",
    })
    void suggestDays(int current, int min, Integer expected) {
        assertThat(DbGrowthAdvisor.suggestDays(current, min)).isEqualTo(expected);
    }

    @Test
    @DisplayName("kısaltma adayı: veritabanının > %10'u, ayarlanabilir yaş kuralı, taban üstü → öneri + tekdüze tahmin + eylem apply_retention")
    void shortenCandidate() {
        TableFacts t = facts("port_checks", 2 * GB, GB, GB / 2, 10_000_000L, 0L, 0L, "series-port", 180, 200.0, null, false);
        List<Finding> l = DbGrowthAdvisor.advise(List.of(t), ctx(10 * GB));
        Finding f = l.stream().filter(x -> x.code().equals("SHORTEN_CANDIDATE")).findFirst().orElseThrow();
        assertThat(f.suggestedDays()).isEqualTo(90);
        assertThat(f.currentDays()).isEqualTo(180);
        assertThat(f.minDays()).isEqualTo(30);
        assertThat(f.settingKey()).isEqualTo("site.monitor.series.port.retention-days");
        assertThat(f.action()).isEqualTo("apply_retention");
        assertThat(f.estMethod()).isEqualTo("uniform");
        assertThat(f.estRows()).isEqualTo(5_000_000L);          // (180 − 90) / 180
        assertThat(f.estBytes()).isEqualTo(GB);
        assertThat(f.severity()).isEqualTo("info");             // pay %20 < %25, kapasite baskısı yok
        assertThat(f.params().get("compliance")).isEqualTo(false);
    }

    @Test
    @DisplayName("kısaltma adayı: pay ≥ %25 ya da kapasite baskısı → warn; kişisel veri → uyum işareti; analiz oranı tekdüzeyi ezer")
    void shortenSeverityAndCompliance() {
        TableFacts big = facts("audit_log", 3 * GB, GB, GB, 1_000_000L, 0L, 0L, "audit-log", 365, 400.0, null, false);
        Finding f = DbGrowthAdvisor.advise(List.of(big), ctx(10 * GB)).stream()
                .filter(x -> x.code().equals("SHORTEN_CANDIDATE")).findFirst().orElseThrow();
        assertThat(f.severity()).isEqualTo("warn");
        assertThat(f.params().get("compliance")).isEqualTo(true);
        assertThat(f.dataClass()).isEqualTo("SECURITY_AUDIT");
        TableFacts withHist = new TableFacts("port_checks", 2 * GB, GB, GB / 2, 1_000L, 0L, 0L, policy("series-port"),
                List.of(policy("series-port")), 180, 200.0, null, false, 0.25, "histogram");
        Finding h = DbGrowthAdvisor.advise(List.of(withHist), ctx(10 * GB)).stream()
                .filter(x -> x.code().equals("SHORTEN_CANDIDATE")).findFirst().orElseThrow();
        assertThat(h.estMethod()).isEqualTo("histogram");
        assertThat(h.estRows()).isEqualTo(250L);
        Context pressure = new Context(10 * GB, 12 * GB, 83.3, null, false, false);
        Finding p = DbGrowthAdvisor.advise(List.of(facts("port_checks", 2 * GB, GB, GB / 2, 1L, 0L, 0L, "series-port", 180, 200.0, null, false)),
                pressure).stream().filter(x -> x.code().equals("SHORTEN_CANDIDATE")).findFirst().orElseThrow();
        assertThat(p.severity()).isEqualTo("warn");
    }

    @Test
    @DisplayName("ayarı paylaşan kurallar: kardeşler, ortak taban = en büyük alt sınır, öneri hepsini sayar (2026-11-13 inceleme)")
    void sharedSettingKey() {
        RetentionPolicy notif = policy("notification-logs");
        List<RetentionPolicy> sib = DbGrowthAdvisor.keySiblings(notif);
        assertThat(sib).extracting(RetentionPolicy::table)
                .contains("notification_logs", "domain_expiry_reminders", "noc_deliveries", "alert_escalation_steps", "quiet_digest_items");
        assertThat(sib).allSatisfy(x -> assertThat(x.settingKey()).isEqualTo(notif.settingKey()));
        int floor = sib.stream().mapToInt(RetentionPolicy::minDays).max().orElseThrow();
        assertThat(DbGrowthAdvisor.keyFloor(notif)).isEqualTo(floor);
        // tek kurallı anahtar: yalnız kendisi
        RetentionPolicy port = policy("series-port");
        assertThat(DbGrowthAdvisor.keySiblings(port)).allSatisfy(x -> assertThat(x.settingKey()).isEqualTo(port.settingKey()));
        assertThat(DbGrowthAdvisor.keySiblings(null)).isEmpty();
        // sertifika serisi: iki tablo
        RetentionPolicy cert = policy("series-certificate");
        TableFacts t = facts("certificate_checks", 3 * GB, GB, GB, 1_000_000L, 0L, 0L, "series-certificate", 730, 800.0, null, false);
        Finding f = DbGrowthAdvisor.advise(List.of(t), ctx(10 * GB)).stream()
                .filter(x -> x.code().equals("SHORTEN_CANDIDATE")).findFirst().orElseThrow();
        assertThat(f.minDays()).isEqualTo(DbGrowthAdvisor.keyFloor(cert));
        assertThat((Integer) f.params().get("affected_count")).isEqualTo(DbGrowthAdvisor.keySiblings(cert).stream()
                .map(RetentionPolicy::table).distinct().toList().size()).isGreaterThanOrEqualTo(2);
        assertThat((List<String>) f.params().get("affected_tables")).contains("certificate_checks", "tls_grade_changes");
    }

    @Test
    @DisplayName("yasal tutma açıkken kısaltma adayı yalnız BİLGİ (hold kalkana dek hiçbir satır silinmez) ve hold işareti taşır")
    void shortenUnderLegalHoldIsInfo() {
        TableFacts big = facts("audit_log", 3 * GB, GB, GB, 1_000_000L, 0L, 0L, "audit-log", 365, 400.0, null, false);
        Context hold = new Context(10 * GB, 0, null, null, false, true);
        Finding f = DbGrowthAdvisor.advise(List.of(big), hold).stream()
                .filter(x -> x.code().equals("SHORTEN_CANDIDATE")).findFirst().orElseThrow();
        assertThat(f.severity()).isEqualTo("info");
        assertThat(f.params().get("hold")).isEqualTo(true);
    }

    @Test
    @DisplayName("kısaltma YOK: küçük pay, küçük tablo, tabanda, ayarlanamaz kural")
    void noShorten() {
        assertThat(codes(DbGrowthAdvisor.advise(List.of(facts("port_checks", 500 * MB, 300 * MB, 100 * MB, 1L, 0L, 0L,
                "series-port", 180, 200.0, null, false)), ctx(10 * GB)))).doesNotContain("SHORTEN_CANDIDATE");
        assertThat(codes(DbGrowthAdvisor.advise(List.of(facts("port_checks", 40 * MB, 30 * MB, 5 * MB, 1L, 0L, 0L,
                "series-port", 180, 200.0, null, false)), ctx(100 * MB)))).doesNotContain("SHORTEN_CANDIDATE");
        assertThat(codes(DbGrowthAdvisor.advise(List.of(facts("port_checks", 2 * GB, GB, GB / 2, 1L, 0L, 0L,
                "series-port", 30, 200.0, null, false)), ctx(10 * GB)))).doesNotContain("SHORTEN_CANDIDATE");
    }

    @Test
    @DisplayName("henüz dolmadı: en eski < saklama − 1 gün, dengelenme bilinir → NOT_SATURATED (dolmaya kalan gün)")
    void notSaturated() {
        TableFacts t = facts("activity_log", 400 * MB, 300 * MB, 80 * MB, 1_000L, 0L, 0L, "activity-log", 365, 90.0, 1_600.0 * MB, false);
        Finding f = DbGrowthAdvisor.advise(List.of(t), ctx(10 * GB)).stream()
                .filter(x -> x.code().equals("NOT_SATURATED")).findFirst().orElseThrow();
        assertThat(f.params().get("days_to_saturate")).isEqualTo(275);
        assertThat(f.params().get("saturation_pct")).isEqualTo(24.7);
        assertThat(f.severity()).isEqualTo("info");
        Context cap = new Context(10 * GB, 11 * GB, 90.9, null, false, false);
        assertThat(DbGrowthAdvisor.advise(List.of(t), cap).stream().filter(x -> x.code().equals("NOT_SATURATED"))
                .findFirst().orElseThrow().severity()).isEqualTo("warn");
    }

    @Test
    @DisplayName("kural dışı büyüyen tablo → NO_RULE; sınırlı / dışarıda yönetilen ama büyüyen → BOUNDED_GROWING")
    void ruleless() {
        TableFacts noRule = new TableFacts("custom_blob_table", 500 * MB, 400 * MB, 50 * MB, 1L, 0L, 100 * MB, null, List.of(),
                null, null, null, false, null, null);
        assertThat(codes(DbGrowthAdvisor.advise(List.of(noRule), ctx(10 * GB)))).contains("NO_RULE");
        TableFacts small = new TableFacts("custom_blob_table", 500 * MB, 400 * MB, 50 * MB, 1L, 0L, 10 * MB, null, List.of(),
                null, null, null, false, null, null);
        assertThat(codes(DbGrowthAdvisor.advise(List.of(small), ctx(10 * GB)))).doesNotContain("NO_RULE");
        TableFacts bounded = facts("schema_table_registry", 300 * MB, 200 * MB, 50 * MB, 1L, 0L, 100 * MB, "schema-table-registry",
                null, null, null, false);
        Finding b = DbGrowthAdvisor.advise(List.of(bounded), ctx(10 * GB)).stream()
                .filter(x -> x.code().equals("BOUNDED_GROWING")).findFirst().orElseThrow();
        assertThat(b.action()).isEqualTo("code");
        assertThat(b.params().get("mode")).isEqualTo("BOUNDED");
    }

    @Test
    @DisplayName("temizlik geride: yalnız düz yaş kuralı ({t}); koruyucu WHERE'li kural (açık alarm) eski satırı bilerek tutar; yasal saklamada yok")
    void cleanupBehind() {
        TableFacts behind = facts("port_checks", 100 * MB, 80 * MB, 10 * MB, 1L, 0L, 0L, "series-port", 180, 190.0, null, false);
        assertThat(codes(DbGrowthAdvisor.advise(List.of(behind), ctx(10 * GB)))).contains("CLEANUP_BEHIND");
        TableFacts failed = facts("port_checks", 100 * MB, 80 * MB, 10 * MB, 1L, 0L, 0L, "series-port", 180, 100.0, null, true);
        assertThat(codes(DbGrowthAdvisor.advise(List.of(failed), ctx(10 * GB)))).contains("CLEANUP_BEHIND");
        TableFacts guarded = facts("alert_events", 100 * MB, 80 * MB, 10 * MB, 1L, 0L, 0L, "alert-events", 365, 900.0, null, false);
        assertThat(codes(DbGrowthAdvisor.advise(List.of(guarded), ctx(10 * GB)))).doesNotContain("CLEANUP_BEHIND");
        Context hold = new Context(10 * GB, 0, null, null, false, true);
        List<Finding> l = DbGrowthAdvisor.advise(List.of(behind), hold);
        assertThat(codes(l)).contains("LEGAL_HOLD").doesNotContain("CLEANUP_BEHIND");
    }

    @Test
    @DisplayName("ölü satır ≥ %20 ve ≥ 10k → DEAD_TUPLES (≥ %50 warn); TOAST ≥ %50 ve ≥ 100 MB → TOAST_HEAVY")
    void deadAndToast() {
        TableFacts dead = facts("port_checks", 100 * MB, 80 * MB, 10 * MB, 30_000L, 40_000L, 0L, "series-port", 180, 50.0, null, false);
        Finding d = DbGrowthAdvisor.advise(List.of(dead), ctx(10 * GB)).stream()
                .filter(x -> x.code().equals("DEAD_TUPLES")).findFirst().orElseThrow();
        assertThat(d.severity()).isEqualTo("warn");
        assertThat(d.action()).isEqualTo("dba");
        TableFacts toast = facts("page_resource_scans", 400 * MB, 50 * MB, 20 * MB, 100L, 0L, 0L, "page-resource-scans", 365, 50.0, null, false);
        assertThat(codes(DbGrowthAdvisor.advise(List.of(toast), ctx(10 * GB)))).contains("TOAST_HEAVY");
        assertThat(DbGrowthAdvisor.deadTuples("t", 100_000, 5_000, null, 1)).isNull();
        assertThat(DbGrowthAdvisor.deadTuples("t", 10_000, 20_000, "2026-11-01T00:00:00", 1).params().get("last_autovacuum"))
                .isEqualTo("2026-11-01T00:00:00");
    }

    @ParameterizedTest(name = "kullanım {0}, dolma {1}, geçmiş {2} → {3}")
    @CsvSource({
            "90, , true, critical",
            "50, 20, true, critical",
            "50, 20, false, ",
            "75, , true, warn",
            "50, 60, true, warn",
            "50, 200, true, ",
    })
    void capacity(double pct, Integer days, boolean history, String expected) {
        Context c = new Context(5 * GB, 10 * GB, pct, days, history, false);
        Finding f = DbGrowthAdvisor.capacity(c);
        if (expected == null) assertThat(f).isNull();
        else assertThat(f.severity()).isEqualTo(expected);
        assertThat(DbGrowthAdvisor.capacity(new Context(5 * GB, 0, 99.0, 1, true, false))).isNull();
    }

    @Test
    @DisplayName("sıra: önem (critical → info), sonra tahmini bayt / tablo boyutu")
    void ordering() {
        TableFacts small = facts("port_checks", 100 * MB, 80 * MB, 10 * MB, 30_000L, 40_000L, 0L, "series-port", 180, 50.0, null, false);
        TableFacts large = facts("ping_checks", 900 * MB, 700 * MB, 100 * MB, 30_000L, 40_000L, 0L, "series-ping", 180, 50.0, null, false);
        Context c = new Context(5 * GB, 5 * GB, 99.0, null, false, false);
        List<Finding> l = DbGrowthAdvisor.advise(List.of(small, large), c);
        assertThat(l.get(0).code()).isEqualTo("CAPACITY");
        List<String> deadTables = l.stream().filter(x -> x.code().equals("DEAD_TUPLES")).map(Finding::table).toList();
        assertThat(deadTables).containsExactly("ping_checks", "port_checks");
        assertThat(DbGrowthAdvisor.CODES).doesNotHaveDuplicates();
        for (Finding f : l) {
            assertThat(DbGrowthAdvisor.CODES).contains(f.code());
            assertThat(DbGrowthAdvisor.SEVERITIES).contains(f.severity());
            assertThat(DbGrowthAdvisor.ACTIONS).contains(f.action());
        }
    }
}
