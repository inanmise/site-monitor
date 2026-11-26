package com.sitemonitor.repository;

import com.sitemonitor.model.CertificateInventory;
import com.sitemonitor.model.LatestCheck;
import com.sitemonitor.model.MaintenanceWindow;
import com.sitemonitor.service.ActivityLogService;
import com.sitemonitor.service.CertRenewalPlanService;
import com.sitemonitor.service.MonitorHistoryService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * H paketi (2026-11-26) — sertifika envanteri ve bakım penceresi HEDEFLİ yazımları, gerçek SQL (H2, işlemsiz):
 * <ul>
 *   <li>B7: plan yazımı / kaldırması / kapanışı yalnız plan kolonlarını yazar; okunduğu andaki eski varlığın kaydı planı
 *       geri yazmaz (kolonlar {@code updatable = false}) ve {@code @DynamicUpdate} eşzamanlı alan düzenlemesini ezmez.</li>
 *   <li>B5: plan tabanı (parmak izi + bitiş) plan anında sunulan sertifikadan yazılır; kontrol daha uzun geçerli yeni
 *       sertifika görünce plan koşullu kapanır.</li>
 *   <li>B2: beklenen parmak izi koşullu temizlenir — arada formdan yeni değer girildiyse dokunulmaz.</li>
 *   <li>İzleme 2: bakım penceresi duraklat / sürdür koşullu hedefli yazım — aynı durumda 0 satır, eşzamanlı ad düzenlemesi kalır.</li>
 * </ul>
 */
@DataJpaTest
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class CertInventoryTargetedWritesTest {

    @Autowired CertificateInventoryRepository inventoryRepo;
    @Autowired LatestCheckRepository latestRepo;
    @Autowired MaintenanceWindowRepository maintenanceRepo;

    @AfterEach
    void cleanUp() {
        inventoryRepo.deleteAll();
        latestRepo.deleteAll();
        maintenanceRepo.deleteAll();
    }

    private CertRenewalPlanService svc() {
        return new CertRenewalPlanService(inventoryRepo, latestRepo, mock(MonitorHistoryService.class),
                mock(ActivityLogService.class));
    }

    private static MockHttpSession session() {
        MockHttpSession s = new MockHttpSession();
        s.setAttribute("username", "ops");
        s.setAttribute("displayName", "Ops Kişi");
        return s;
    }

    private CertificateInventory inventory(String domain) {
        CertificateInventory inv = new CertificateInventory();
        inv.setDomain(domain);
        inv.setPort(443);
        inv.setActive(true);
        inv.setTeamId(1L);
        inv.setDescription("ilk");
        return inventoryRepo.save(inv);
    }

    private void served(String domain, String fp, String notBefore, String notAfter) {
        LatestCheck lc = latestRepo.findById(domain).orElse(new LatestCheck());
        lc.setDomain(domain);
        lc.setFingerprint(fp);
        lc.setNotBefore(notBefore);
        lc.setNotAfter(notAfter);
        latestRepo.save(lc);
    }

    @Test
    @DisplayName("B5/B7: plan tabanı sunulan sertifikadan; eski varlık kaydı planı geri yazmaz; yenileme planı koşullu kapatır")
    void planBaseStaleSaveAndAutoClose() {
        CertificateInventory inv = inventory("plan.example.com");
        served("plan.example.com", "AAAA", "2026-01-01T00:00:00", "2026-12-01T00:00:00");
        CertificateInventory stale = inventoryRepo.findById(inv.getId()).orElseThrow();   // plan öncesi okuma

        svc().plan(inventoryRepo.findById(inv.getId()).orElseThrow(), "2026-11-20", "satın alma", session());
        CertificateInventory planned = inventoryRepo.findById(inv.getId()).orElseThrow();
        assertThat(planned.getRenewalPlannedAt()).isEqualTo("2026-11-20");
        assertThat(planned.getRenewalPlannedFingerprint()).isEqualTo("AAAA");
        assertThat(planned.getRenewalPlannedNotAfter()).isEqualTo("2026-12-01T00:00:00");
        assertThat(planned.getUpdatedBy()).isEqualTo("ops");

        // Eski (plansız) okuma bir alan düzenlemesiyle kaydedilir — plan kolonları YAZILMAZ.
        stale.setDescription("başka alan");
        inventoryRepo.save(stale);
        CertificateInventory after = inventoryRepo.findById(inv.getId()).orElseThrow();
        assertThat(after.getRenewalPlannedAt()).isEqualTo("2026-11-20");
        assertThat(after.getDescription()).isEqualTo("başka alan");

        // Aynı sertifika → kapanmaz; plan gününden ÖNCE verilmiş, daha uzun geçerli yeni sertifika → kapanır (erken yenileme).
        CertificateInventory forCheck = inventoryRepo.findById(inv.getId()).orElseThrow();
        assertThat(svc().onCheckResult(forCheck, "AAAA", "2026-01-01T00:00:00", "2026-12-01T00:00:00")).isFalse();
        forCheck.setDescription("ignored");   // bellek kopyası — yazılmaz
        assertThat(svc().onCheckResult(forCheck, "BBBB", "2026-11-10T00:00:00", "2027-12-01T00:00:00")).isTrue();
        CertificateInventory closed = inventoryRepo.findById(inv.getId()).orElseThrow();
        assertThat(closed.getRenewalPlannedAt()).isNull();
        assertThat(closed.getRenewalPlannedFingerprint()).isNull();
        assertThat(closed.getDescription()).as("kapanış yalnız plan kolonlarını yazar").isEqualTo("başka alan");
    }

    @Test
    @DisplayName("Bağımsız inceleme: tabansız YENİ plan geçmiş tarihli olsa da kapanmaz; ilk kontrol tabanı benimser, gerçek yenileme kapatır")
    void newBaselessPlanAdoptsBaseOnRealSql() {
        CertificateInventory inv = inventory("adopt.example.com");   // kontrol yok → taban boş
        svc().plan(inventoryRepo.findById(inv.getId()).orElseThrow(), "2026-11-01", null, session());
        CertificateInventory planned = inventoryRepo.findById(inv.getId()).orElseThrow();
        assertThat(planned.getRenewalPlannedFingerprint()).isNull();
        assertThat(planned.getRenewalPlannedRecordedAt()).as("bu sürümün planı").isNotBlank();

        // Plan gününden sonra verilmiş sertifika: eski kural kapatırdı → artık yalnız taban benimsenir
        assertThat(svc().onCheckResult(inventoryRepo.findById(inv.getId()).orElseThrow(),
                "AAAA", "2026-11-20T00:00:00", "2026-12-10T00:00:00")).isFalse();
        CertificateInventory adopted = inventoryRepo.findById(inv.getId()).orElseThrow();
        assertThat(adopted.getRenewalPlannedAt()).isEqualTo("2026-11-01");
        assertThat(adopted.getRenewalPlannedFingerprint()).isEqualTo("AAAA");
        assertThat(adopted.getRenewalPlannedNotAfter()).isEqualTo("2026-12-10T00:00:00");

        // Benimseme tabanı varken bir daha yazmaz (koşul: parmak izi NULL)
        assertThat(inventoryRepo.adoptRenewalPlanBase(inv.getId(), "2026-11-01", "", "ZZZZ", null)).isZero();
        assertThat(inventoryRepo.findById(inv.getId()).orElseThrow().getRenewalPlannedFingerprint()).isEqualTo("AAAA");

        assertThat(svc().onCheckResult(inventoryRepo.findById(inv.getId()).orElseThrow(),
                "BBBB", "2026-12-01T00:00:00", "2027-12-10T00:00:00")).isTrue();
        CertificateInventory closed = inventoryRepo.findById(inv.getId()).orElseThrow();
        assertThat(closed.getRenewalPlannedAt()).isNull();
        assertThat(closed.getRenewalPlannedRecordedAt()).as("kapanış yazılma anını da siler").isNull();
    }

    @Test
    @DisplayName("Bağımsız inceleme: ESKİ plan (yazılma anı boş) eski kuralla kapanır; benimseme sorgusu ona dokunmaz")
    void legacyPlanKeepsTheOldRuleOnRealSql() {
        // Eski sürümün yazdığı plan: plan kolonları INSERT'te yazılır (insertable), yazılma anı boş kalır
        CertificateInventory inv = new CertificateInventory();
        inv.setDomain("legacy.example.com");
        inv.setPort(443);
        inv.setActive(true);
        inv.setTeamId(1L);
        inv.setRenewalPlannedAt("2026-11-01");
        inv = inventoryRepo.save(inv);
        CertificateInventory legacy = inventoryRepo.findById(inv.getId()).orElseThrow();
        assertThat(legacy.getRenewalPlannedAt()).isEqualTo("2026-11-01");
        assertThat(legacy.getRenewalPlannedRecordedAt()).isNull();
        assertThat(inventoryRepo.adoptRenewalPlanBase(inv.getId(), "2026-11-01", "", "AAAA", null)).isZero();
        assertThat(svc().onCheckResult(legacy, "AAAA", "2026-11-20T00:00:00", "2026-12-10T00:00:00")).isTrue();
        assertThat(inventoryRepo.findById(inv.getId()).orElseThrow().getRenewalPlannedAt()).isNull();
    }

    @Test
    @DisplayName("B7: arada değişen plan koşullu kaldırmayla silinmez; değişmemiş plan silinir")
    void conditionalUnplan() {
        CertificateInventory inv = inventory("unplan.example.com");
        CertRenewalPlanService svc = svc();
        svc.plan(inventoryRepo.findById(inv.getId()).orElseThrow(), "2026-11-20", "not 1", session());
        CertificateInventory read = inventoryRepo.findById(inv.getId()).orElseThrow();
        // Başka sekme notu değiştirdi
        svc.plan(inventoryRepo.findById(inv.getId()).orElseThrow(), "2026-11-20", "not 2", session());
        assertThat(svc.unplan(read, session())).isFalse();
        assertThat(inventoryRepo.findById(inv.getId()).orElseThrow().getRenewalPlannedNote()).isEqualTo("not 2");
        assertThat(svc.unplan(inventoryRepo.findById(inv.getId()).orElseThrow(), session())).isTrue();
        assertThat(inventoryRepo.findById(inv.getId()).orElseThrow().getRenewalPlannedAt()).isNull();
    }

    @Autowired org.springframework.transaction.PlatformTransactionManager txManager;

    @Test
    @DisplayName("@DynamicUpdate: işlem içinde okunan (yönetilen) kaydın kaydı yalnız DEĞİŞEN kolonu yazar — arada başka işlemin "
            + "hedefli temizlediği beklenen parmak izi geri yazılmaz (updateInventory @Transactional yolu)")
    void dynamicUpdateKeepsConcurrentEdits() throws Exception {
        CertificateInventory inv = inventory("dyn.example.com");
        inv.setExpectedFingerprint("OLD");
        inventoryRepo.save(inv);
        org.springframework.transaction.support.TransactionTemplate tx =
                new org.springframework.transaction.support.TransactionTemplate(txManager);
        tx.executeWithoutResult(status -> {
            CertificateInventory managed = inventoryRepo.findById(inv.getId()).orElseThrow();
            assertThat(managed.getExpectedFingerprint()).isEqualTo("OLD");
            // Kontrol hattı başka iş parçacığında, KENDİ işleminde temizler (dağıtım tamamlandı) ve işlemini bitirir.
            java.util.concurrent.CompletableFuture.runAsync(() -> {
                org.springframework.transaction.support.TransactionTemplate other =
                        new org.springframework.transaction.support.TransactionTemplate(txManager);
                other.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
                other.executeWithoutResult(s -> assertThat(
                        inventoryRepo.clearExpectedFingerprintIfUnchanged(inv.getId(), "OLD")).isEqualTo(1));
            }).join();
            // Form isteği başka bir alanı değiştirir; beklenen parmak izi anahtarı gövdede yok → bellekteki değer aynen kalır.
            managed.setDescription("form yazdı");
            managed.setExpectedFingerprint(managed.getExpectedFingerprint());
            inventoryRepo.save(managed);
        });
        CertificateInventory now = inventoryRepo.findById(inv.getId()).orElseThrow();
        assertThat(now.getDescription()).isEqualTo("form yazdı");
        assertThat(now.getExpectedFingerprint()).as("kontrol hattının temizlemesi korunur").isNull();
    }

    @Test
    @DisplayName("B2: beklenen parmak izi koşullu temizlenir; arada yeni değer girildiyse dokunulmaz")
    void conditionalExpectedFingerprintClear() {
        CertificateInventory inv = inventory("fp.example.com");
        inv.setExpectedFingerprint("OLD");
        inventoryRepo.save(inv);
        assertThat(inventoryRepo.clearExpectedFingerprintIfUnchanged(inv.getId(), "OTHER")).isZero();
        assertThat(inventoryRepo.findById(inv.getId()).orElseThrow().getExpectedFingerprint()).isEqualTo("OLD");
        assertThat(inventoryRepo.clearExpectedFingerprintIfUnchanged(inv.getId(), "OLD")).isEqualTo(1);
        assertThat(inventoryRepo.findById(inv.getId()).orElseThrow().getExpectedFingerprint()).isNull();
        assertThat(inventoryRepo.clearExpectedFingerprintIfUnchanged(inv.getId(), "OLD")).isZero();
    }

    @Autowired CertificateCheckRepository checkRepo;

    private void check(String domain, String checkedAt, String fp, String notAfter) {
        com.sitemonitor.model.CertificateCheck c = new com.sitemonitor.model.CertificateCheck();
        c.setDomain(domain);
        c.setCheckedAt(checkedAt);
        c.setCreatedAt(checkedAt);
        c.setFingerprint(fp);
        c.setNotAfter(notAfter);
        checkRepo.save(c);
    }

    @Test
    @DisplayName("B2: firstSightingOf — bu uçta parmak izinin İLK görülmesi (en eski satır, başka alan adı sayılmaz)")
    void firstSightingOfReadsTheEarliestRowOfThisDomain() {
        try {
            check("other.example.com", "2025-01-01T00:00:00", "XX", "2026-01-01T00:00:00");
            check("fs.example.com", "2025-03-01T00:00:00", "YY", "2026-03-01T00:00:00");
            check("fs.example.com", "2025-02-01T00:00:00", "XX", "2026-02-01T00:00:00");
            check("fs.example.com", "2025-04-01T00:00:00", "XX", "2026-02-01T00:00:00");
            String all = "2000-01-01T00:00:00";
            java.util.List<Object[]> rows = checkRepo.firstSightingOf("fs.example.com", java.util.List.of("XX", "xx"), all);
            assertThat(rows).hasSize(1);
            assertThat(rows.get(0)[0]).isEqualTo("2025-02-01T00:00:00");
            assertThat(rows.get(0)[1]).isEqualTo("2026-02-01T00:00:00");
            assertThat(checkRepo.firstSightingOf("fs.example.com", java.util.List.of("ZZ"), all)).isEmpty();
            // Pencere (bağımsız inceleme): alt sınırdan eski satır okunmaz — ilk görülme pencere içindeki en eski satır
            java.util.List<Object[]> windowed = checkRepo.firstSightingOf("fs.example.com", java.util.List.of("XX"),
                    "2025-03-15T00:00:00");
            assertThat(windowed).hasSize(1);
            assertThat(windowed.get(0)[0]).isEqualTo("2025-04-01T00:00:00");
            assertThat(checkRepo.firstSightingOf("fs.example.com", java.util.List.of("XX"), "2025-05-01T00:00:00")).isEmpty();
        } finally {
            checkRepo.deleteAll();
        }
    }

    @Test
    @DisplayName("B1: findTierWarningCandidates — bayrağı kalkık olmayan, hatasız, eşik içindeki satırlar")
    void tierWarningCandidatesQuery() {
        LatestCheck a = new LatestCheck(); a.setDomain("a.example.com"); a.setDaysRemaining(40); a.setWarning(false); a.setStatus("valid");
        LatestCheck b = new LatestCheck(); b.setDomain("b.example.com"); b.setDaysRemaining(40); b.setWarning(null); b.setStatus(null);
        LatestCheck c = new LatestCheck(); c.setDomain("c.example.com"); c.setDaysRemaining(20); c.setWarning(true); c.setStatus("warning");
        LatestCheck d = new LatestCheck(); d.setDomain("d.example.com"); d.setDaysRemaining(40); d.setWarning(false); d.setStatus("error");
        LatestCheck e = new LatestCheck(); e.setDomain("e.example.com"); e.setDaysRemaining(70); e.setWarning(false); e.setStatus("valid");
        latestRepo.saveAll(java.util.List.of(a, b, c, d, e));
        assertThat(latestRepo.findTierWarningCandidates(60)).extracting(LatestCheck::getDomain)
                .containsExactlyInAnyOrder("a.example.com", "b.example.com");
    }

    @Test
    @DisplayName("İzleme 2: bakım duraklat / sürdür koşullu — aynı durumda 0 satır; eşzamanlı ad düzenlemesi kalır")
    void maintenanceToggleIsTargeted() {
        MaintenanceWindow w = new MaintenanceWindow();
        w.setName("Gece bakımı");
        w.setStartAt("2026-11-26T22:00:00");
        w.setDurationMinutes(60);
        w.setActive(true);
        w.setCreatedAt("2026-11-26T10:00:00");
        w.setUpdatedAt("2026-11-26T10:00:00");
        w = maintenanceRepo.save(w);
        MaintenanceWindow edit = maintenanceRepo.findById(w.getId()).orElseThrow();
        edit.setName("Gece bakımı (güncel)");
        maintenanceRepo.save(edit);

        assertThat(maintenanceRepo.setActiveIfChanged(w.getId(), false, "2026-11-26T11:00:00")).isEqualTo(1);
        assertThat(maintenanceRepo.setActiveIfChanged(w.getId(), false, "2026-11-26T11:00:01")).isZero();
        MaintenanceWindow now = maintenanceRepo.findById(w.getId()).orElseThrow();
        assertThat(now.getActive()).isFalse();
        assertThat(now.getName()).isEqualTo("Gece bakımı (güncel)");
        assertThat(now.getUpdatedAt()).isEqualTo("2026-11-26T11:00:00");
        assertThat(maintenanceRepo.setActiveIfChanged(w.getId(), true, "2026-11-26T12:00:00")).isEqualTo(1);
        assertThat(maintenanceRepo.findById(w.getId()).orElseThrow().getActive()).isTrue();
    }
}
