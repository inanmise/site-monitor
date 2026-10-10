package com.sitemonitor.service.quality;

import com.sitemonitor.model.CertificateInventory;
import com.sitemonitor.service.noc.NocConfigService;
import com.sitemonitor.service.noc.NocType;
import com.sitemonitor.service.quality.DataQualityEvaluator.Bucket;
import com.sitemonitor.service.quality.DataQualityEvaluator.Evaluation;
import com.sitemonitor.service.quality.DataQualityEvaluator.Finding;
import com.sitemonitor.service.quality.DataQualityScore.Count;
import com.sitemonitor.service.quality.DataQualitySource.Escalation;
import com.sitemonitor.service.quality.DataQualitySource.MonitorFact;
import com.sitemonitor.service.quality.DataQualitySource.NocState;
import com.sitemonitor.service.quality.DataQualitySource.TeamFact;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** Her kuralın doğruluk tablosu + kova (takım / Sahipsiz / kurum) davranışı. */
class DataQualityEvaluatorTest {

    private static final Instant NOW = Instant.parse("2026-10-10T09:00:00Z");

    private static Evaluation eval(DataQualityFixtures fx) {
        return DataQualityEvaluator.evaluate(fx.facts(), NOW);
    }

    private static Count count(Bucket b, DataQualityRule r) {
        return b.counts().get(r);
    }

    private static List<Finding> found(Bucket b, DataQualityRule r) {
        return b.findings().getOrDefault(r, List.of());
    }

    @Test
    @DisplayName("kusursuz takım + kayıt → 100, bulgu yok; Sahipsiz boş")
    void cleanTeam() {
        DataQualityFixtures fx = new DataQualityFixtures().team(1, "Ödeme");
        fx.inv(10, "pay.example.com", 1L);
        fx.monitor(NocType.HTTP, 5, "Pay API", "https://pay.example.com/api", 1L, true, "Ödeme", "GET https://pay.example.com/api");
        Evaluation e = eval(fx);
        Bucket t = e.team(1L);
        assertThat(t.score()).isEqualTo(100);
        assertThat(t.findingCount()).isZero();
        assertThat(e.unassigned().findingCount()).isZero();
        assertThat(e.org().score()).isEqualTo(100);
        assertThat(e.notes()).isEmpty();
    }

    // ── Sahiplik ────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("INV_NO_TEAM: takımsız / silinmiş takım / pasif takım → Sahipsiz kovası; takım puanına GİRMEZ")
    void ownershipInventory() {
        DataQualityFixtures fx = new DataQualityFixtures().team(1, "A");
        fx.teamFact(new TeamFact(2, "Eski", false, true, true, true, false, false));
        fx.inv(10, "ok.example.com", 1L);
        fx.inv(11, "none.example.com", null);
        fx.inv(12, "gone.example.com", 99L);
        fx.inv(13, "old.example.com", 2L);
        Evaluation e = eval(fx);
        assertThat(count(e.org(), DataQualityRule.INV_NO_TEAM)).isEqualTo(new Count(4, 3));
        assertThat(found(e.unassigned(), DataQualityRule.INV_NO_TEAM))
                .extracting(f -> f.detail().get("reason"))
                .containsExactlyInAnyOrder("NONE", "TEAM_MISSING", "TEAM_INACTIVE");
        assertThat(count(e.team(1L), DataQualityRule.INV_NO_TEAM)).as("takım kovasına sahiplik kuralı girmez").isNull();
        assertThat(e.team(2L)).as("pasif takım sıralamada yok").isNull();
        // Sahipsiz kaydın diğer kusurları da Sahipsiz kovasında sayılır
        assertThat(e.unassigned().items()).isEqualTo(3);
    }

    @Test
    @DisplayName("MON_NO_TEAM: yalnız AKTİF BAĞIMSIZ izleme; türev DNS/Port ve duraklatılmış sayılmaz")
    void ownershipMonitor() {
        DataQualityFixtures fx = new DataQualityFixtures().team(1, "A");
        fx.monitor(NocType.PING, 1, "p1", "h1", null, true, "g", "h1");
        fx.monitor(NocType.PING, 2, "p2", "h2", null, false, "g", "h2");
        fx.add(new MonitorFact(NocType.DNS, 3, "d", "x.example.com (A)", null, true, false, null, "x|A", null));
        Evaluation e = eval(fx);
        assertThat(count(e.org(), DataQualityRule.MON_NO_TEAM)).isEqualTo(new Count(1, 1));
        assertThat(found(e.unassigned(), DataQualityRule.MON_NO_TEAM)).extracting(Finding::id).containsExactly(1L);
    }

    // ── Envanter ────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("INV_NO_TIER yalnız aktif kayıtlar; INV_TIER_SUSPECT yalnız katmanlı kayıtlar (nedenle)")
    void tierRules() {
        DataQualityFixtures fx = new DataQualityFixtures().team(1, "A");
        fx.inv(10, "a.example.com", 1L).setTier(null);
        fx.inv(11, "uat-b.example.com", 1L).setTier(1);
        CertificateInventory paused = fx.inv(12, "c.example.com", 1L);
        paused.setTier(null);
        paused.setActive(false);
        Bucket t = eval(fx).team(1L);
        assertThat(count(t, DataQualityRule.INV_NO_TIER)).isEqualTo(new Count(2, 1));
        assertThat(count(t, DataQualityRule.INV_TIER_SUSPECT)).isEqualTo(new Count(1, 1));
        Finding f = found(t, DataQualityRule.INV_TIER_SUSPECT).get(0);
        assertThat(f.detail()).containsEntry("reason", TierHeuristics.NONPROD_NAME_ON_PROD_TIER)
                .containsEntry("token", "uat").containsEntry("tier", 1);
    }

    @Test
    @DisplayName("hijyen bulguları: sorumlu yok / hiç kontrol yok / bayat — InventoryHygieneService kodlarından")
    void hygieneRules() {
        DataQualityFixtures fx = new DataQualityFixtures().team(1, "A");
        fx.inv(10, "A.example.com", 1L);
        fx.inv(11, "b.example.com", 1L);
        fx.hygiene("a.example.com", "no_contacts", "never_checked");
        fx.hygiene("b.example.com", "stale");
        Bucket t = eval(fx).team(1L);
        assertThat(count(t, DataQualityRule.INV_NO_CONTACTS)).isEqualTo(new Count(2, 1));
        assertThat(count(t, DataQualityRule.INV_NEVER_CHECKED)).isEqualTo(new Count(2, 1));
        assertThat(count(t, DataQualityRule.INV_STALE_CHECK)).isEqualTo(new Count(2, 1));
        assertThat(found(t, DataQualityRule.INV_STALE_CHECK)).extracting(Finding::id).containsExactly(11L);
    }

    @Test
    @DisplayName("INV_CHECK_FAILING: son durum hata VE 7 günde ≥ 3 hata; tek seferlik hata sayılmaz; sayı okunamazsa hata yeter")
    void repeatedErrors() {
        DataQualityFixtures fx = new DataQualityFixtures().team(1, "A");
        fx.inv(10, "x.example.com", 1L);
        fx.inv(11, "y.example.com", 1L);
        fx.inv(12, "z.example.com", 1L);
        fx.hygiene("x.example.com", "error").hygiene("y.example.com", "error");
        fx.errorCounts.put("x.example.com", 3);
        fx.errorCounts.put("y.example.com", 1);
        fx.errorCounts.put("z.example.com", 9);   // son durum temiz → kusur değil
        fx.hygieneErrors.put("x.example.com", "Connection refused");
        Bucket t = eval(fx).team(1L);
        assertThat(count(t, DataQualityRule.INV_CHECK_FAILING)).isEqualTo(new Count(3, 1));
        Finding f = found(t, DataQualityRule.INV_CHECK_FAILING).get(0);
        assertThat(f.id()).isEqualTo(10L);
        assertThat(f.detail()).containsEntry("errors_7d", 3).containsEntry("error", "Connection refused");

        fx.errorCounts = null;   // hata sayısı okunamadı
        assertThat(count(eval(fx).team(1L), DataQualityRule.INV_CHECK_FAILING)).isEqualTo(new Count(3, 2));
    }

    @Test
    @DisplayName("hijyen okunamazsa hijyen kuralları UYGULANMAZ (puan bozulmaz) ve not düşülür")
    void hygieneUnavailable() {
        DataQualityFixtures fx = new DataQualityFixtures().team(1, "A");
        fx.inv(10, "x.example.com", 1L);
        fx.hygiene = null;
        Evaluation e = eval(fx);
        assertThat(count(e.team(1L), DataQualityRule.INV_NO_CONTACTS)).isNull();
        assertThat(count(e.team(1L), DataQualityRule.INV_CHECK_FAILING)).isNull();
        assertThat(e.notes()).extracting(DataQualityEvaluator.Note::code).contains("HYGIENE_UNAVAILABLE");
    }

    // ── 7/24 ────────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("NOC_CRITICAL_UNCOVERED: katman 1–2 aktif kayıt, 7/24 anahtarı kapalı → kusur; katman 3 uygun değil")
    void nocCritical() {
        DataQualityFixtures fx = new DataQualityFixtures().team(1, "A");
        fx.inv(10, "a.example.com", 1L).setNocNotify(false);
        fx.inv(11, "b.example.com", 1L).setTier(1);
        fx.inv(12, "c.example.com", 1L).setTier(3);
        CertificateInventory off = fx.inv(13, "d.example.com", 1L);
        off.setTier(4);
        off.setNocNotify(false);
        Bucket t = eval(fx).team(1L);
        assertThat(count(t, DataQualityRule.NOC_CRITICAL_UNCOVERED)).isEqualTo(new Count(2, 1));
        assertThat(found(t, DataQualityRule.NOC_CRITICAL_UNCOVERED).get(0).detail()).containsEntry("tier", 2);
    }

    @Test
    @DisplayName("7/24 kullanılamıyorsa (aktif grup yok / SSL türü kapalı) kural uygulanmaz, kurum notu düşer")
    void nocNotConfigured() {
        DataQualityFixtures fx = new DataQualityFixtures().team(1, "A");
        fx.inv(10, "a.example.com", 1L).setNocNotify(false);
        fx.inv(11, "b.example.com", 1L).setTier(3);
        fx.noc = new NocState(false, "NO_ACTIVE_GROUP",
                new NocConfigService.Config(Set.of(), "HIGH", true, null, null, null), false);
        Evaluation e = eval(fx);
        assertThat(count(e.team(1L), DataQualityRule.NOC_CRITICAL_UNCOVERED)).isNull();
        assertThat(e.notes()).singleElement().satisfies(n -> {
            assertThat(n.code()).isEqualTo("NOC_NOT_CONFIGURED");
            assertThat(n.params()).containsEntry("reason", "NO_ACTIVE_GROUP").containsEntry("critical", 1);
        });
    }

    // ── İzlemeler ───────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("MON_PAUSED_LONG: 30. günden itibaren; değişiklik geçmişi önce, yoksa son güncelleme; bilinmeyen süre kusur değil")
    void pausedLong() {
        DataQualityFixtures fx = new DataQualityFixtures().team(1, "A");
        fx.add(new MonitorFact(NocType.HTTP, 1, "h1", "u1", 1L, false, true, "g", null, "2026-09-01T00:00:00"));
        fx.add(new MonitorFact(NocType.HTTP, 2, "h2", "u2", 1L, false, true, "g", null, "2026-09-11T10:00:00"));   // 29 gün
        fx.add(new MonitorFact(NocType.PING, 3, "p3", "x", 1L, false, true, "g", null, "2026-10-09T00:00:00"));
        fx.paused.put("PING:3", "2026-08-01T00:00:00");   // geçmiş: 70 gün önce
        fx.add(new MonitorFact(NocType.PING, 4, "p4", "y", 1L, false, true, "g", null, null));   // süre bilinmiyor
        CertificateInventory inv = fx.inv(10, "a.example.com", 1L);
        inv.setActive(false);
        inv.setUpdatedAt("2026-07-01T00:00:00");
        Bucket t = eval(fx).team(1L);
        assertThat(count(t, DataQualityRule.MON_PAUSED_LONG)).isEqualTo(new Count(5, 3));
        assertThat(found(t, DataQualityRule.MON_PAUSED_LONG))
                .extracting(f -> f.type() + ":" + f.id() + ":" + f.detail().get("exact"))
                .containsExactlyInAnyOrder("HTTP:1:false", "PING:3:true", "SSL:10:false");
    }

    @Test
    @DisplayName("MON_NO_GROUP: aktif bağımsız izlemelerde boş grup adı; türev ve duraklatılmış uygun değil")
    void noGroup() {
        DataQualityFixtures fx = new DataQualityFixtures().team(1, "A");
        fx.monitor(NocType.PING, 1, "p1", "h1", 1L, true, " ", "h1");
        fx.monitor(NocType.PING, 2, "p2", "h2", 1L, true, "Çekirdek", "h2");
        fx.monitor(NocType.PING, 3, "p3", "h3", 1L, false, null, "h3");
        fx.add(new MonitorFact(NocType.PORT, 4, "pt", "x:443", 1L, true, false, null, "x:443", null));
        Bucket t = eval(fx).team(1L);
        assertThat(count(t, DataQualityRule.MON_NO_GROUP)).isEqualTo(new Count(2, 1));
    }

    @Test
    @DisplayName("MON_DUPLICATE: aynı takım + tür + normalize hedef; en küçük kimlik korunur, diğerleri kusur")
    void duplicates() {
        DataQualityFixtures fx = new DataQualityFixtures().team(1, "A").team(2, "B");
        fx.monitor(NocType.HTTP, 7, "Asıl", "https://a.example.com", 1L, true, "g", "GET https://a.example.com");
        fx.monitor(NocType.HTTP, 9, "Kopya", "https://A.example.com/", 1L, true, "g", "GET https://a.example.com");
        fx.monitor(NocType.HTTP, 8, "Başka takım", "https://a.example.com", 2L, true, "g", "GET https://a.example.com");
        fx.monitor(NocType.HTTP, 10, "POST", "https://a.example.com", 1L, true, "g", "POST https://a.example.com");
        fx.monitor(NocType.HTTP, 11, "Duraklı", "https://a.example.com", 1L, false, "g", "GET https://a.example.com");
        fx.monitor(NocType.PAGE, 12, "Sayfa", "https://a.example.com", 1L, true, "g", null);
        Evaluation e = eval(fx);
        Bucket a = e.team(1L);
        assertThat(count(a, DataQualityRule.MON_DUPLICATE)).isEqualTo(new Count(3, 1));
        Finding f = found(a, DataQualityRule.MON_DUPLICATE).get(0);
        assertThat(f.id()).isEqualTo(9L);
        assertThat(f.detail()).containsEntry("duplicate_of_id", 7L).containsEntry("duplicate_of_name", "Asıl");
        assertThat(count(e.team(2L), DataQualityRule.MON_DUPLICATE)).isEqualTo(new Count(1, 0));
    }

    // ── Takım ───────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("takım kuralları: üye yok, lider/müdür yok ya da pasif, adres yok, YÜKSEK/KRİTİK eskalasyon eksik")
    void teamRules() {
        DataQualityFixtures fx = new DataQualityFixtures();
        fx.teamFact(new TeamFact(1, "Boş", true, false, true, false, true, false));   // lider + müdür PASİF, e-posta yok
        fx.escalation.put(1L, new Escalation(false, true));                       // yalnız KRİTİK
        fx.inv(10, "a.example.com", 1L);
        Bucket t = eval(fx).team(1L);
        assertThat(count(t, DataQualityRule.TEAM_NO_MEMBERS)).isEqualTo(new Count(1, 1));
        assertThat(found(t, DataQualityRule.TEAM_NO_MANAGER).get(0).detail())
                .containsEntry("leader", "INACTIVE").containsEntry("manager", "INACTIVE");
        assertThat(count(t, DataQualityRule.TEAM_NO_NOTIFY_ADDRESS)).isEqualTo(new Count(1, 1));
        assertThat(found(t, DataQualityRule.TEAM_NO_ESCALATION).get(0).detail()).containsEntry("missing", List.of("HIGH"));

        fx.groupAddress.add(1L);   // adresli bildirim grubu yeter
        assertThat(count(eval(fx).team(1L), DataQualityRule.TEAM_NO_NOTIFY_ADDRESS)).isEqualTo(new Count(1, 0));
    }

    @Test
    @DisplayName("izlemesi olmayan takım: adres + eskalasyon kuralı uygulanmaz (alarm çıkmaz); üye/lider kuralı uygulanır")
    void teamWithoutMonitoring() {
        DataQualityFixtures fx = new DataQualityFixtures();
        fx.teamFact(new TeamFact(1, "İK", true, false, false, false, false, false));
        Bucket t = eval(fx).team(1L);
        assertThat(count(t, DataQualityRule.TEAM_NO_NOTIFY_ADDRESS)).isNull();
        assertThat(count(t, DataQualityRule.TEAM_NO_ESCALATION)).isNull();
        assertThat(count(t, DataQualityRule.TEAM_NO_MEMBERS)).isEqualTo(new Count(1, 1));
        assertThat(found(t, DataQualityRule.TEAM_NO_MANAGER).get(0).detail())
                .containsEntry("leader", "NONE").containsEntry("manager", "NONE");
        assertThat(t.score()).isZero();
    }

    @Test
    @DisplayName("kurum kovası her öğeyi sayar; takımlar ad sırasıyla; kurum puanı formülle aynı")
    void orgBucket() {
        DataQualityFixtures fx = new DataQualityFixtures().team(2, "Zeta").team(1, "alfa");
        fx.inv(10, "a.example.com", 1L).setTier(null);
        fx.inv(11, "b.example.com", 2L);
        Evaluation e = eval(fx);
        assertThat(e.teams()).extracting(Bucket::teamName).containsExactly("alfa", "Zeta");
        assertThat(count(e.org(), DataQualityRule.INV_NO_TIER)).isEqualTo(new Count(2, 1));
        assertThat(count(e.org(), DataQualityRule.TEAM_NO_MEMBERS)).isEqualTo(new Count(2, 0));
        assertThat(e.org().score()).isEqualTo(DataQualityScore.score(e.org().counts()));
        assertThat(e.team(1L).score()).isLessThan(e.team(2L).score());
    }

    @Test
    @DisplayName("tarih ayrıştırma: Z'li / Z'siz / kesirli / bozuk")
    void parse() {
        assertThat(DataQualityEvaluator.parse("2026-10-01T10:00:00")).isEqualTo(Instant.parse("2026-10-01T10:00:00Z"));
        assertThat(DataQualityEvaluator.parse("2026-10-01T10:00:00Z")).isEqualTo(Instant.parse("2026-10-01T10:00:00Z"));
        assertThat(DataQualityEvaluator.parse("2026-10-01T10:00:00.123")).isEqualTo(Instant.parse("2026-10-01T10:00:00Z"));
        assertThat(DataQualityEvaluator.parse("2026-10-01T13:00:00+03:00")).isEqualTo(Instant.parse("2026-10-01T10:00:00Z"));
        assertThat(DataQualityEvaluator.parse("dün")).isNull();
        assertThat(DataQualityEvaluator.parse(null)).isNull();
    }

    @Test
    @DisplayName("yanıt anahtarları: bulgu ayrıntısında kullanıcı kimliği yok")
    void noUserIdsInDetail() {
        DataQualityFixtures fx = new DataQualityFixtures();
        fx.teamFact(new TeamFact(1, "A", true, false, true, false, true, false));
        fx.inv(10, "a.example.com", 1L);
        for (List<Finding> list : eval(fx).team(1L).findings().values()) {
            for (Finding f : list) {
                assertThat(f.detail().keySet()).doesNotContain("user_id", "manager_id", "leader_id");
                for (Object v : f.detail().values()) assertThat(v).isNotInstanceOf(Map.class);
            }
        }
    }
}
