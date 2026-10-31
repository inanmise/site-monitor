package com.sitemonitor.repository;

import com.sitemonitor.model.DbQueryCheck;
import com.sitemonitor.model.DbQueryMonitor;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Veritabanı sorgu izlemesi zamanlayıcısının (run) sorguları gerçek JPA / H2 üzerinde (2026-10-31 inceleme düzeltmeleri):
 * değişim tabanı (zamanlanmış satırlar, elle / yeniden kontrol hariç — Y1), ardışık atlama sayacı (O5) ve varlık kaydının
 * planı / atlama kolonlarını EZMEMESİ (D4 / K6 — {@code updatable = false}). Yazma yolları {@code NOT_SUPPORTED}: hedefli
 * UPDATE'ler kendi işlemini açmalı.
 */
@DataJpaTest
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class DbQueryRunRepositoriesTest {

    @Autowired DbQueryMonitorRepository monitors;
    @Autowired DbQueryCheckRepository checks;
    @Autowired EntityManager em;

    /**
     * İşlemsiz (NOT_SUPPORTED) yazımlar geri alınmaz: aynı Spring bağlamını paylaşan öteki {@code @DataJpaTest} sınıfları
     * (ör. DbQueryApiRepositoriesTest) bu kayıtları görmesin diye her test kendi satırlarını siler.
     */
    @AfterEach
    void cleanUp() {
        checks.deleteAll();
        monitors.deleteAll();
    }

    private DbQueryMonitor mon(String name) {
        DbQueryMonitor m = new DbQueryMonitor();
        m.setTeamId(1L);
        m.setName(name);
        m.setActive(true);
        m.setSqlText("SELECT 1");
        return monitors.save(m);
    }

    private DbQueryCheck check(long monitorId, String at, boolean ok, Boolean manual, Boolean recheck, String json) {
        DbQueryCheck c = new DbQueryCheck();
        c.setMonitorId(monitorId);
        c.setCheckedAt(at);
        c.setOk(ok);
        c.setStatus(ok ? "OK" : "FAIL");
        c.setManual(manual);
        c.setRecheck(recheck);
        c.setRuleResultsJson(json);
        return checks.save(c);
    }

    @Test
    @DisplayName("Y1: değişim tabanı — son iki ZAMANLANMIŞ ok satırı (yeniden yeniye); elle, teyit / kurtarma ve hatalı satır hariç")
    void recentScheduledOkExcludesOutOfBand() {
        long id = mon("Taban").getId();
        check(id, "2026-11-02T06:00:00", true, null, null, "[1]");       // eski satır (bayraklar yok) = zamanlanmış
        check(id, "2026-11-02T06:05:00", true, false, false, "[2]");
        check(id, "2026-11-02T06:05:30", true, false, true, "[teyit]");
        check(id, "2026-11-02T06:06:00", true, true, false, "[elle]");
        check(id, "2026-11-02T06:10:00", false, false, false, "[hata]");
        List<DbQueryCheck> recent = checks.findRecentScheduledOk(id);
        assertThat(recent).extracting(DbQueryCheck::getRuleResultsJson).containsExactly("[2]", "[1]");
        assertThat(checks.findRecentScheduledOk(id + 999)).isEmpty();
    }

    @Test
    @DisplayName("O-2 / D-1: son zamanlanmış satır (hatalı da) ve çapadan ÖNCEKİ zamanlanmış ok satırı; aynı saniye kimlikle ayrılır")
    void anchoredBaselineQueries() {
        long id = mon("Çapa").getId();
        check(id, "2026-11-02T06:00:00", true, null, null, "[S1]");
        DbQueryCheck s2 = check(id, "2026-11-02T06:01:00", true, false, false, "[S2]");
        DbQueryCheck s2b = check(id, "2026-11-02T06:01:00", true, false, false, "[S2b]");   // aynı saniye
        check(id, "2026-11-02T06:01:30", true, false, true, "[teyit]");
        check(id, "2026-11-02T06:01:40", true, true, false, "[elle]");
        DbQueryCheck s3 = check(id, "2026-11-02T06:02:00", true, false, false, "[S3]");
        DbQueryCheck s4 = check(id, "2026-11-02T06:03:00", false, false, false, "[S4 hata]");

        assertThat(checks.findLatestScheduled(id)).get().extracting(DbQueryCheck::getId).isEqualTo(s4.getId());
        assertThat(checks.findLatestScheduled(id + 999)).isEmpty();

        // Çapa S3 → S3'ten önceki en yeni zamanlanmış ok = S2b (teyit / elle satırları atlanır)
        assertThat(checks.findScheduledOkBefore(id, s3.getCheckedAt(), s3.getId())).get()
                .extracting(DbQueryCheck::getRuleResultsJson).isEqualTo("[S2b]");
        // Çapa S2b → aynı saniyedeki daha küçük kimlik (S2)
        assertThat(checks.findScheduledOkBefore(id, s2b.getCheckedAt(), s2b.getId())).get()
                .extracting(DbQueryCheck::getRuleResultsJson).isEqualTo("[S2]");
        // Kimlik bilinmiyor (-1) → yalnız önceki saniyeler: S1
        assertThat(checks.findScheduledOkBefore(id, s2.getCheckedAt(), -1L)).get()
                .extracting(DbQueryCheck::getRuleResultsJson).isEqualTo("[S1]");
        // Hatalı satır taban olmaz: çapa S4 → S3
        assertThat(checks.findScheduledOkBefore(id, s4.getCheckedAt(), s4.getId())).get()
                .extracting(DbQueryCheck::getRuleResultsJson).isEqualTo("[S3]");
        assertThat(checks.findScheduledOkBefore(id, "2026-11-02T06:00:00", 0L)).isEmpty();
    }

    @Test
    @DisplayName("O5: atlama sayacı hedefli artar / sıfırlanır; ilk atlanma zamanı korunur")
    void skipCounter() {
        long id = mon("Atlanan").getId();
        assertThat(monitors.markDbQuerySkipped(id, "2026-11-02T06:00:00")).isEqualTo(1);
        monitors.markDbQuerySkipped(id, "2026-11-02T06:00:30");
        DbQueryMonitor m = monitors.findById(id).orElseThrow();
        assertThat(m.getSkippedRuns()).isEqualTo(2);
        assertThat(m.getSkippedSince()).as("ilk atlanma").isEqualTo("2026-11-02T06:00:00");
        assertThat(monitors.clearDbQuerySkipped(id)).isEqualTo(1);
        m = monitors.findById(id).orElseThrow();
        assertThat(m.getSkippedRuns()).isZero();
        assertThat(m.getSkippedSince()).isNull();
    }

    @Test
    @DisplayName("D4 / K6: varlık kaydı (düzenleme) bayat planı ve atlama sayacını EZMEZ — yalnız hedefli UPDATE yazar")
    void entitySaveDoesNotOverwriteSchedulerColumns() {
        DbQueryMonitor m = mon("Düzenlenen");
        long id = m.getId();
        monitors.updateNextRunAt(id, "2026-11-02T06:05:00");
        monitors.markDbQuerySkipped(id, "2026-11-02T06:00:00");
        // Düzenleme ekranının bayat okuması: plan / sayaç eski değerde
        m.setNextRunAt("2020-01-01T00:00:00");
        m.setSkippedRuns(0);
        m.setSkippedSince(null);
        m.setPurpose("Yeni amaç metni");
        monitors.save(m);
        DbQueryMonitor fresh = monitors.findById(id).orElseThrow();
        assertThat(fresh.getPurpose()).isEqualTo("Yeni amaç metni");
        assertThat(fresh.getNextRunAt()).isEqualTo("2026-11-02T06:05:00");
        assertThat(fresh.getSkippedRuns()).isEqualTo(1);
        assertThat(fresh.getSkippedSince()).isEqualTo("2026-11-02T06:00:00");
        monitors.updateNextRunAt(id, null);
        assertThat(monitors.findById(id).orElseThrow().getNextRunAt()).isNull();
    }
}
