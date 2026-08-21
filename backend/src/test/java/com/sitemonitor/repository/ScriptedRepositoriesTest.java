package com.sitemonitor.repository;

import com.sitemonitor.model.ScriptedCheck;
import com.sitemonitor.model.ScriptedDraft;
import com.sitemonitor.model.ScriptedMonitor;
import com.sitemonitor.model.ScriptedScriptVersion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.test.context.TestPropertySource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Sentetik izlemenin üç repository'si (H2) — bu sorgular CI'da HİÇ koşmuyordu.
 *
 * <p>Neden gerekli: JPQL metinleri ve türetilmiş metot adları yalnız uygulama ayağa kalkarken
 * (ya da çağrıldıkları anda) doğrulanır. Yanlış bir alan adı — {@code r.durationMs} yerine
 * {@code r.duration} — derlenir, testler yeşil kalır ve hata ilk kez ÜRETİMDE, o ekran
 * açıldığında patlar. Burada her sorgu en az bir kez gerçekten çalıştırılır.
 *
 * <p>Native (Postgres'e özgü) iki sorgu bilinçli olarak DIŞARIDA: {@code findLatestPerMonitor}
 * (CROSS JOIN LATERAL) ve {@code historyHistogram} (türetilmiş tablo + substr). Bunlar H2'de
 * farklı davranır; doğrulukları Postgres'te {@code SqlSamplesIntegrationTest} kapsamındadır.
 */
@DataJpaTest
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
class ScriptedRepositoriesTest {

    @Autowired ScriptedMonitorRepository monitorRepo;
    @Autowired ScriptedCheckRepository checkRepo;
    @Autowired ScriptedScriptVersionRepository versionRepo;
    @Autowired ScriptedDraftRepository draftRepo;

    private ScriptedMonitor mon(String name, Long teamId, String group, boolean active) {
        ScriptedMonitor m = new ScriptedMonitor();
        m.setName(name);
        m.setTeamId(teamId);
        m.setGroupName(group);
        m.setActive(active);
        m.setScript("export default function () {}");
        m.setCreatedAt("2026-08-01T10:00:00");
        return m;
    }

    private ScriptedCheck check(long monitorId, boolean ok, long durationMs, String at) {
        ScriptedCheck c = new ScriptedCheck();
        c.setMonitorId(monitorId);
        c.setOk(ok);
        c.setStatus(ok ? "PASS" : "FAIL");
        c.setDurationMs(durationMs);
        c.setCheckedAt(at);
        return c;
    }

    private ScriptedScriptVersion version(long monitorId, int seq, String label) {
        ScriptedScriptVersion v = new ScriptedScriptVersion();
        v.setMonitorId(monitorId);
        v.setSequenceNo(seq);
        v.setVersion(label);
        v.setEventType("EDIT");
        v.setScript("export default function () {}");
        v.setCreatedAt("2026-08-0" + seq + "T10:00:00");
        v.setCreatedBy("tester");
        return v;
    }

    // ── ScriptedMonitorRepository ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("existsDuplicate: ad karşılaştırması BÜYÜK/küçük harf duyarsız ve TAKIMA göre ayrışır")
    void existsDuplicate_caseInsensitivePerTeam() {
        monitorRepo.save(mon("Login Akışı", 1L, "Kanal", true));

        assertThat(monitorRepo.existsDuplicate("login akışı", 1L, null)).isTrue();
        assertThat(monitorRepo.existsDuplicate("Login Akışı", 2L, null)).isFalse();   // başka takım
        assertThat(monitorRepo.existsDuplicate("Başka Ad", 1L, null)).isFalse();
    }

    @Test
    @DisplayName("existsDuplicate: kendi kaydını mükerrer saymaz (excludeId) ve takımsız kayıtları eşler")
    void existsDuplicate_excludesSelfAndHandlesNullTeam() {
        ScriptedMonitor saved = monitorRepo.save(mon("Tek", 1L, "G", true));
        assertThat(monitorRepo.existsDuplicate("Tek", 1L, saved.getId())).isFalse();

        monitorRepo.save(mon("Takımsız", null, "G", true));
        assertThat(monitorRepo.existsDuplicate("takımsız", null, null)).isTrue();
        assertThat(monitorRepo.existsDuplicate("Takımsız", 1L, null)).isFalse();
    }

    @Test
    @DisplayName("groupCountsByTeam: takım+grup başına sayar, grubu boş olanları HİÇ saymaz")
    void groupCountsByTeam_skipsBlankGroups() {
        monitorRepo.save(mon("a", 1L, "Kanal", true));
        monitorRepo.save(mon("b", 1L, "Kanal", false));      // pasif de sayılır (grup sayımı aktiflikten bağımsız)
        monitorRepo.save(mon("c", 2L, "Kanal", true));
        monitorRepo.save(mon("d", 1L, "", true));
        monitorRepo.save(mon("e", 1L, null, true));

        List<Object[]> rows = monitorRepo.groupCountsByTeam();

        assertThat(rows).hasSize(2);
        assertThat(rows).anySatisfy(r -> {
            assertThat(r[0]).isEqualTo(1L); assertThat(r[1]).isEqualTo("Kanal");
            assertThat(((Number) r[2]).longValue()).isEqualTo(2L);
        });
        assertThat(rows).anySatisfy(r -> {
            assertThat(r[0]).isEqualTo(2L); assertThat(((Number) r[2]).longValue()).isEqualTo(1L);
        });
    }

    @Test
    @DisplayName("renameGroupForTeam: YALNIZ o takımın eşleşen grubunu (harf duyarsız) günceller")
    void renameGroupForTeam_scopedToTeam() {
        monitorRepo.save(mon("a", 1L, "eski", true));
        monitorRepo.save(mon("b", 1L, "ESKI", true));
        monitorRepo.save(mon("c", 2L, "eski", true));

        int updated = monitorRepo.renameGroupForTeam(1L, "Eski", "Yeni");

        assertThat(updated).isEqualTo(2);
        assertThat(monitorRepo.findFirstByNameOrderByIdAsc("c")).get()
                .extracting(ScriptedMonitor::getGroupName).isEqualTo("eski");   // diğer takım dokunulmadı
    }

    @Test
    @DisplayName("BİLİNEN SINIR: SQL LOWER() Türkçe İ'yi eşlemez — 'ESKİ' grubu 'eski' ile aynı sayılmaz")
    void renameGroupForTeam_turkishDottedCapitalIsNotMatched() {
        monitorRepo.save(mon("a", 1L, "eski", true));
        monitorRepo.save(mon("b", 1L, "ESKİ", true));      // U+0130 — LOWER() 'eski̇' (i + birleşen nokta) üretir

        int updated = monitorRepo.renameGroupForTeam(1L, "eski", "Yeni");

        // Bu satır DAVRANIŞI SABİTLER, doğruluğu onaylamaz: 'ESKİ' grubu yeniden adlandırmanın
        // dışında kalır ve ekranda ayrı bir grup olarak asılı kalır. Sorgu tüm izleme türlerinde
        // aynı olduğundan düzeltme (ör. Türkçe-farkında normalize) türden bağımsız ele alınmalı.
        assertThat(updated).isEqualTo(1);
        assertThat(monitorRepo.findFirstByNameOrderByIdAsc("b")).get()
                .extracting(ScriptedMonitor::getGroupName).isEqualTo("ESKİ");
    }

    @Test
    @DisplayName("findByActiveTrue / countByActiveTrue / findAllByOrderByNameAsc: türetilmiş adlar gerçekten çalışıyor")
    void derivedQueriesRun() {
        monitorRepo.save(mon("Zeta", 1L, "G", true));
        monitorRepo.save(mon("Alfa", 1L, "G", true));
        monitorRepo.save(mon("Pasif", 1L, "G", false));

        assertThat(monitorRepo.findByActiveTrue()).hasSize(2);
        assertThat(monitorRepo.countByActiveTrue()).isEqualTo(2);
        assertThat(monitorRepo.findAllByOrderByNameAsc())
                .extracting(ScriptedMonitor::getName).containsExactly("Alfa", "Pasif", "Zeta");
    }

    // ── ScriptedCheckRepository ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("monitorIdsWithSuccess: en az bir PASS'i olan monitörleri döner, hiç geçmemişi dönmez")
    void monitorIdsWithSuccess() {
        checkRepo.save(check(1L, false, 100, "2026-08-01T10:00:00"));
        checkRepo.save(check(1L, true,  120, "2026-08-01T10:05:00"));
        checkRepo.save(check(2L, false, 130, "2026-08-01T10:00:00"));

        assertThat(checkRepo.monitorIdsWithSuccess()).containsExactly(1L);
    }

    @Test
    @DisplayName("Sayfalı aralık + hata filtresi + sayaçlar aynı aralığı görüyor")
    void rangeQueriesAgree() {
        checkRepo.save(check(5L, true,  100, "2026-08-01T10:00:00"));
        checkRepo.save(check(5L, false, 900, "2026-08-01T11:00:00"));
        checkRepo.save(check(5L, true,  110, "2026-08-05T10:00:00"));   // aralık DIŞI

        String from = "2026-08-01T00:00:00", to = "2026-08-02T00:00:00";
        var page = checkRepo.findByMonitorIdAndCheckedAtBetween(5L, from, to,
                org.springframework.data.domain.PageRequest.of(0, 10));
        var failPage = checkRepo.findByMonitorIdAndOkFalseAndCheckedAtBetween(5L, from, to,
                org.springframework.data.domain.PageRequest.of(0, 10));

        assertThat(page.getTotalElements()).isEqualTo(2);
        assertThat(failPage.getTotalElements()).isEqualTo(1);
        assertThat(checkRepo.countByMonitorIdAndCheckedAtBetween(5L, from, to)).isEqualTo(2);
        assertThat(checkRepo.countByMonitorIdAndOkFalseAndCheckedAtBetween(5L, from, to)).isEqualTo(1);
    }

    @Test
    @DisplayName("findRecentByMonitorId(Since) ve findTop…: en yeni önce, LIMIT uygulanır")
    void recentQueries() {
        checkRepo.save(check(6L, true, 100, "2026-08-01T10:00:00"));
        checkRepo.save(check(6L, true, 200, "2026-08-02T10:00:00"));
        checkRepo.save(check(6L, true, 300, "2026-08-03T10:00:00"));

        assertThat(checkRepo.findRecentByMonitorId(6L, 2))
                .extracting(ScriptedCheck::getDurationMs).containsExactly(300L, 200L);
        assertThat(checkRepo.findRecentByMonitorIdSince(6L, "2026-08-02T00:00:00", 10))
                .extracting(ScriptedCheck::getDurationMs).containsExactly(300L, 200L);
        assertThat(checkRepo.findTopByMonitorIdOrderByCheckedAtDesc(6L)).get()
                .extracting(ScriptedCheck::getDurationMs).isEqualTo(300L);
    }

    @Test
    @DisplayName("weeklyStatsByMonitor: monitör başına toplam/başarılı/ortalama süre — haftalık raporun kaynağı")
    void weeklyStatsByMonitor() {
        checkRepo.save(check(7L, true,  100, "2026-08-01T10:00:00"));
        checkRepo.save(check(7L, false, 300, "2026-08-01T11:00:00"));
        checkRepo.save(check(8L, true,  200, "2026-08-01T12:00:00"));

        List<Object[]> rows = checkRepo.weeklyStatsByMonitor(List.of(7L, 8L),
                "2026-08-01T00:00:00", "2026-08-02T00:00:00");

        assertThat(rows).hasSize(2);
        Object[] r7 = rows.stream().filter(r -> ((Number) r[0]).longValue() == 7L).findFirst().orElseThrow();
        assertThat(((Number) r7[1]).longValue()).isEqualTo(2L);      // toplam
        assertThat(((Number) r7[2]).longValue()).isEqualTo(1L);      // başarılı
        assertThat(((Number) r7[3]).doubleValue()).isEqualTo(200.0); // ortalama süre
    }

    @Test
    @DisplayName("responseSeriesRaw ve historyBounds: süre grafiği ve 'veri şu tarihten beri' bilgisi")
    void seriesAndBounds() {
        checkRepo.save(check(9L, true, 100, "2026-08-01T10:00:00"));
        checkRepo.save(check(9L, true, 150, "2026-08-03T10:00:00"));

        List<Object[]> series = checkRepo.responseSeriesRaw(9L,
                "2026-08-01T00:00:00", "2026-08-04T00:00:00", 10);
        assertThat(series).hasSize(2);
        assertThat(series.get(0)[0]).isEqualTo("2026-08-03T10:00:00");   // en yeni önce

        List<Object[]> bounds = checkRepo.historyBounds(9L);
        assertThat(bounds.get(0)[0]).isEqualTo("2026-08-01T10:00:00");
        assertThat(bounds.get(0)[1]).isEqualTo("2026-08-03T10:00:00");
    }

    // ── ScriptedScriptVersionRepository ──────────────────────────────────────────────────────

    @Test
    @DisplayName("Sürüm sorguları: en yeni üstte, MAX(sequenceNo) ve sayım monitöre göre ayrışır")
    void versionQueries() {
        versionRepo.save(version(1L, 1, "1.0.0"));
        versionRepo.save(version(1L, 2, "1.1.0"));
        versionRepo.save(version(2L, 1, "1.0.0"));

        assertThat(versionRepo.findByMonitorIdOrderBySequenceNoDesc(1L))
                .extracting(ScriptedScriptVersion::getVersion).containsExactly("1.1.0", "1.0.0");
        assertThat(versionRepo.findMaxSequenceNo(1L)).contains(2);
        assertThat(versionRepo.findTopByMonitorIdOrderBySequenceNoDesc(1L)).get()
                .extracting(ScriptedScriptVersion::getVersion).isEqualTo("1.1.0");
        assertThat(versionRepo.countByMonitorId(1L)).isEqualTo(2);
        assertThat(versionRepo.countByMonitorId(3L)).isZero();
    }

    @Test
    @DisplayName("findMaxSequenceNo: hiç sürümü olmayan monitörde BOŞ döner (ilk sürüm 1'den başlar)")
    void findMaxSequenceNo_emptyWhenNoVersions() {
        assertThat(versionRepo.findMaxSequenceNo(42L)).isEmpty();
    }

    // ── ScriptedDraftRepository ──────────────────────────────────────────────────────────────

    private ScriptedDraft draft(String owner, String key, Long monitorId) {
        ScriptedDraft d = new ScriptedDraft();
        d.setOwner(owner);
        d.setMonitorKey(key);
        d.setMonitorId(monitorId);
        d.setFormJson("{\"name\":\"yarim\"}");
        d.setUpdatedAt("2026-08-21T10:00:00");
        return d;
    }

    /**
     * REGRESYON: taslak silme UYGULAMADAKİ gibi, yani AMBİYANS TRANSACTION OLMADAN koşmalı.
     *
     * <p>{@code @DataJpaTest} her testi bir transaction'a sarar; bu sarmal yüzünden türetilmiş
     * silmenin tx eksikliği testte GÖRÜNMEZ. Üretimde ise {@code MonitoringController} transactional
     * değil ve {@code open-in-view=false} — {@code deleteByOwnerAndMonitorKey} tx'siz çalışıp
     * {@code TransactionRequiredException} ile düşüyor, çağıran yutuyor, kullanıcı "taslak silindi"
     * bildirimini alıyor ama taslak duruyordu. {@code NOT_SUPPORTED} bu koşulu birebir kurar:
     * repository metodundaki {@code @Transactional} kaldırılırsa bu test KIRMIZI olur.
     */
    @Test
    @org.springframework.transaction.annotation.Transactional(
            propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    @DisplayName("deleteByOwnerAndMonitorKey: dışarıda transaction YOKKEN gerçekten siler (yalnız o kullanıcının taslağını)")
    void deleteDraft_worksWithoutAmbientTransaction() {
        draftRepo.save(draft("AYSE", "new", null));
        draftRepo.save(draft("AYSE", "7", 7L));
        draftRepo.save(draft("MEHMET", "new", null));

        int deleted = draftRepo.deleteByOwnerAndMonitorKey("AYSE", "new");

        assertThat(deleted).isEqualTo(1);
        assertThat(draftRepo.findByOwnerAndMonitorKey("AYSE", "new")).isEmpty();
        assertThat(draftRepo.findByOwnerAndMonitorKey("AYSE", "7")).isPresent();      // aynı kullanıcının diğer taslağı
        assertThat(draftRepo.findByOwnerAndMonitorKey("MEHMET", "new")).isPresent();  // başka kullanıcı dokunulmadı

        draftRepo.deleteAll();   // NOT_SUPPORTED: yazımlar gerçekten commit edildi, sınıfı kirletmesin
    }

    /** Monitör silinince taslakları da düşer — bu da tx'siz yoldan çağrılıyor (aynı tuzak). */
    @Test
    @org.springframework.transaction.annotation.Transactional(
            propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    @DisplayName("deleteByMonitorId: dışarıda transaction YOKKEN o monitörün taslaklarını siler")
    void deleteDraftByMonitorId_worksWithoutAmbientTransaction() {
        draftRepo.save(draft("AYSE", "9", 9L));
        draftRepo.save(draft("MEHMET", "9", 9L));
        draftRepo.save(draft("AYSE", "10", 10L));

        int deleted = draftRepo.deleteByMonitorId(9L);

        assertThat(deleted).isEqualTo(2);        // monitör bazlı: TÜM kullanıcıların taslağı
        assertThat(draftRepo.findByOwnerAndMonitorKey("AYSE", "10")).isPresent();

        draftRepo.deleteAll();
    }
}
