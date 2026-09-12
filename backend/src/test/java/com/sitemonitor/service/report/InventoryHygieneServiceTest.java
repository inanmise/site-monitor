package com.sitemonitor.service.report;

import com.sitemonitor.dto.CertificateDto;
import com.sitemonitor.model.CertificateInventory;
import com.sitemonitor.model.LatestCheck;
import com.sitemonitor.repository.LatestCheckRepository;
import com.sitemonitor.service.AppSettingsService;
import com.sitemonitor.service.CertificateService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Aylık envanter raporunun HİJYEN bölümü.
 *
 * <p>Bu sınıfın kapsamı %1.8'di — dört bulgu grubunun hiçbiri test edilmiyordu. Rapor yöneticinin
 * aksiyon aldığı bir belge: "hiç kontrol edilmemiş" listesi yanlışsa gerçek bir kör nokta gözden
 * kaçar, "bayat" listesi yanlışsa boş yere iş açılır.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class InventoryHygieneServiceTest {

    @Mock CertificateService certificateService;
    @Mock LatestCheckRepository latestRepo;
    @Mock AppSettingsService appSettings;
    @InjectMocks InventoryHygieneService service;

    private static final DateTimeFormatter ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);

    @BeforeEach
    void defaults() {
        when(appSettings.getInt(anyString(), anyInt())).thenReturn(65);
        when(latestRepo.findWeakAlgorithmCandidates()).thenReturn(List.of());
        when(certificateService.getAllLatest()).thenReturn(List.of());
    }

    private static CertificateInventory inv(String domain, Long teamId, Integer tier, Boolean active) {
        CertificateInventory i = new CertificateInventory();
        i.setDomain(domain); i.setTeamId(teamId); i.setTier(tier); i.setActive(active);
        // "Eksiksiz kayit"in tanimina sorumlu ekip de girdi; bos birakilan hal invNoContacts ile sinanir.
        i.setSvcMgmtContact("ekip@example.com");
        return i;
    }

    /** Sorumlu ekip alanlarinin DORDU de bos olan kayit. */
    private static CertificateInventory invNoContacts(String domain) {
        CertificateInventory i = inv(domain, 5L, 1, true);
        i.setSvcMgmtContact(null);
        return i;
    }

    private static CertificateDto dto(String domain, String status, String checkedAt) {
        CertificateDto d = new CertificateDto();
        d.setDomain(domain); d.setStatus(status); d.setCheckedAt(checkedAt);
        return d;
    }

    private static String minutesAgo(int m) {
        return ISO.format(Instant.now().minus(m, ChronoUnit.MINUTES));
    }

    private static InventoryHygieneService.Group group(InventoryHygieneService.Result r, String key) {
        return r.groups().stream().filter(g -> g.key().equals(key)).findFirst().orElse(null);
    }

    // ── 1) Envanter eksikleri ───────────────────────────────────────────────

    @Test
    @DisplayName("Takımsız/tier'sız kayıtlar bulgu olur; eksiksiz kayıt listelenmez")
    void missingFields() {
        var r = service.analyze(List.of(
                inv("takimsiz.com", null, 1, true),
                inv("tiersiz.com", 5L, null, true),
                inv("ikisi-de-yok.com", null, null, true),
                inv("temiz.com", 5L, 2, true)));

        var g = group(r, "missing");
        assertThat(g.total()).isEqualTo(3);
        assertThat(g.samples()).extracting("domain")
                .containsExactly("takimsiz.com", "tiersiz.com", "ikisi-de-yok.com");
        assertThat(g.samples().get(2).detail()).contains("takım").contains("tier");
        // Makine kodları (2026-09-12): Envanter sayfası bunlarla arayüz dilinde yazar
        assertThat(g.samples().get(2).codes()).containsExactly("no_team", "no_tier");
        assertThat(g.samples().get(0).codes()).containsExactly("no_team");
    }

    @Test
    @DisplayName("analyze(rows, cap): sayfa tavansız ister → tüm bulgular; e-posta MAX_PER_GROUP ile kırpılır")
    void capIsCallerChoice() {
        List<CertificateInventory> many = new java.util.ArrayList<>();
        for (int i = 0; i < 15; i++) many.add(inv("d" + i + ".example.com", null, 1, true));
        var capped = service.analyze(many);
        assertThat(group(capped, "missing").total()).isEqualTo(15);
        assertThat(group(capped, "missing").samples()).hasSize(InventoryHygieneService.MAX_PER_GROUP);
        var full = service.analyze(many, Integer.MAX_VALUE);
        assertThat(group(full, "missing").samples()).hasSize(15);
    }

    @Test
    @DisplayName("Sorumlu ekip eksigi AYRI grupta — takim/tier sinyalini kirletmez")
    void missingContacts_separateGroup() {
        var r = service.analyze(List.of(
                invNoContacts("ekipsiz.com"),
                inv("temiz.com", 5L, 2, true)));

        var g = group(r, "contacts");
        assertThat(g.total()).isEqualTo(1);
        assertThat(g.samples()).extracting("domain").containsExactly("ekipsiz.com");
        // Ayni kayit "missing" grubuna DUSMEZ: takim ve tier'i tamam.
        assertThat(r.groups()).noneMatch(x -> "missing".equals(x.key()));
    }

    @Test
    @DisplayName("Bir alan bile doluysa eksik SAYILMAZ — her sertifikanin dort ekiple iliskisi yok")
    void missingContacts_oneFilledIsEnough() {
        CertificateInventory only = invNoContacts("yalniz-waf.com");
        only.setWafAdminContact("waf@example.com");

        var r = service.analyze(List.of(only));

        assertThat(r.groups()).noneMatch(x -> "contacts".equals(x.key()));
    }

    @Test
    @DisplayName("PASİF kayıtlar hijyene girmez — sahibinden aksiyon beklenmiyor")
    void passiveRowsAreIgnored() {
        var r = service.analyze(List.of(inv("pasif.com", null, null, false)));
        assertThat(r.clean()).isTrue();
    }

    @Test
    @DisplayName("active=null AKTİF sayılır (eski kayıtlar rapordan düşmesin)")
    void nullActiveCountsAsActive() {
        var r = service.analyze(List.of(inv("eski.com", null, null, null)));
        assertThat(group(r, "missing").total()).isEqualTo(1);
    }

    // ── 2) Kontrol edilmemiş / bayat ────────────────────────────────────────

    @Test
    @DisplayName("Sonucu olmayan domain 'hiç kontrol edilmemiş', eşiği aşan 'bayat' olur")
    void uncheckedAndStale() {
        when(certificateService.getAllLatest()).thenReturn(List.of(
                dto("bayat.com", "valid", minutesAgo(120)),
                dto("taze.com", "valid", minutesAgo(5))));

        var r = service.analyze(List.of(
                inv("hic.com", 5L, 1, true),
                inv("bayat.com", 5L, 1, true),
                inv("taze.com", 5L, 1, true)));

        var g = group(r, "stale");
        assertThat(g.total()).isEqualTo(2);
        assertThat(g.samples().get(0).detail()).contains("hiç kontrol edilmemiş");
        assertThat(g.samples().get(1).detail()).contains("eşik: 65 dk");
    }

    @Test
    @DisplayName("Bayat satırındaki saat YEREL gösterilir — UTC dizesi olduğu gibi basılmaz")
    void staleTimeIsLocalised() {
        // 2026-08-22T15:00:00 UTC = Europe/Istanbul 18:00 (GMT+3).
        assertThat(InventoryHygieneService.shortTime("2026-08-22T15:00:00")).isEqualTo("22.08 18:00");
        // Gün sınırını da doğru geçer: UTC 22:30 → ertesi gün 01:30 yerel.
        assertThat(InventoryHygieneService.shortTime("2026-08-22T22:30:00")).isEqualTo("23.08 01:30");
    }

    @Test
    @DisplayName("Ayrıştırılamayan zaman raporu DÜŞÜRMEZ, ham değer kalır")
    void badTimestampDoesNotBreakReport() {
        assertThat(InventoryHygieneService.shortTime("tarih-degil")).isEqualTo("tarih-degil");
        assertThat(InventoryHygieneService.shortTime(null)).isEqualTo("null");
    }

    @Test
    @DisplayName("checkedAt null ise bayat SAYILMAZ (bilinmeyen zaman uydurma bulgu üretmesin)")
    void nullCheckedAtIsNotStale() {
        when(certificateService.getAllLatest()).thenReturn(List.of(dto("a.com", "valid", null)));
        var r = service.analyze(List.of(inv("a.com", 5L, 1, true)));
        assertThat(group(r, "stale")).isNull();
    }

    // ── 3) Kontrol hatası ───────────────────────────────────────────────────

    @Test
    @DisplayName("status=error bulgusu hata metniyle gelir; metin yoksa varsayılan açıklama")
    void checkErrors() {
        CertificateDto withMsg = dto("b.com", "error", minutesAgo(1));
        withMsg.setError("connect timed out");
        when(certificateService.getAllLatest()).thenReturn(List.of(
                withMsg, dto("a.com", "ERROR", minutesAgo(1)), dto("c.com", "valid", minutesAgo(1))));

        var g = group(service.analyze(List.of()), "error");
        assertThat(g.total()).isEqualTo(2);
        // Alfabetik sıra: rapor okuru aynı domaini hep aynı yerde bulsun.
        assertThat(g.samples().get(0).domain()).isEqualTo("a.com");
        assertThat(g.samples().get(0).detail()).isEqualTo("kontrol edilemedi");
        assertThat(g.samples().get(1).detail()).isEqualTo("connect timed out");
    }

    // ── 4) Sertifika sağlığı ────────────────────────────────────────────────

    @Test
    @DisplayName("Süresi dolmuş / iptal / zincir kırık / dağıtım eksik / zayıf birlikte raporlanır")
    void certificateHealth() {
        CertificateDto expired = dto("expired.com", "valid", minutesAgo(1));
        expired.setDaysRemaining(-3);
        CertificateDto revoked = dto("revoked.com", "valid", minutesAgo(1));
        revoked.setRevocationStatus("REVOKED");
        revoked.setChainStatus("BROKEN");
        revoked.setDeploymentStatus("INCOMPLETE");
        CertificateDto weak = dto("weak.com", "valid", minutesAgo(1));
        CertificateDto healthy = dto("ok.com", "valid", minutesAgo(1));
        healthy.setDaysRemaining(40);

        LatestCheck lc = new LatestCheck();
        lc.setDomain("weak.com");
        when(latestRepo.findWeakAlgorithmCandidates()).thenReturn(List.of(lc));
        when(certificateService.getAllLatest()).thenReturn(List.of(expired, revoked, weak, healthy));

        var g = group(service.analyze(List.of()), "health");
        assertThat(g.total()).isEqualTo(3);
        // SÜRESİ DOLMUŞ olan en üstte — rapor okuru en kritiği ilk görsün.
        assertThat(g.samples().get(0).domain()).isEqualTo("expired.com");
        assertThat(g.samples().get(0).detail()).contains("3 gün önce");
        assertThat(g.samples()).extracting("detail").anyMatch(d ->
                d.toString().contains("iptal") && d.toString().contains("zincir")
                        && d.toString().contains("dağıtım"));
    }

    @Test
    @DisplayName("Zayıf algoritma sorgusu patlarsa diğer bulgular yine üretilir")
    void weakQueryFailureIsContained() {
        when(latestRepo.findWeakAlgorithmCandidates()).thenThrow(new RuntimeException("db yok"));
        CertificateDto expired = dto("a.com", "valid", minutesAgo(1));
        expired.setDaysRemaining(-1);
        when(certificateService.getAllLatest()).thenReturn(List.of(expired));

        assertThat(group(service.analyze(List.of()), "health").total()).isEqualTo(1);
    }

    @Test
    @DisplayName("Canlı sonuçlar hiç okunamazsa rapor yine üretilir (envanter bulguları kalır)")
    void latestFailureIsContained() {
        when(certificateService.getAllLatest()).thenThrow(new RuntimeException("cache bozuk"));

        var r = service.analyze(List.of(inv("a.com", null, null, true)));
        assertThat(group(r, "missing").total()).isEqualTo(1);
        // Sonuç yoksa "hiç kontrol edilmemiş" grubu da dolar — sessiz kör nokta gizlenmez.
        assertThat(group(r, "stale").total()).isEqualTo(1);
    }

    // ── Kırpma ve sayımlar ──────────────────────────────────────────────────

    @Test
    @DisplayName("Grup başına en fazla 10 örnek listelenir, gerisi 'gizli' sayılır")
    void groupsAreTruncated() {
        List<CertificateInventory> many = new ArrayList<>();
        for (int i = 0; i < 14; i++) many.add(inv("d" + i + ".com", null, null, true));

        var g = group(service.analyze(many), "missing");
        assertThat(g.total()).isEqualTo(14);
        assertThat(g.samples()).hasSize(InventoryHygieneService.MAX_PER_GROUP);
        assertThat(g.hidden()).isEqualTo(4);
    }

    @Test
    @DisplayName("Bulgu yoksa sonuç TEMİZ ve boş grup listelenmez")
    void cleanResult() {
        when(certificateService.getAllLatest()).thenReturn(List.of(dto("a.com", "valid", minutesAgo(1))));
        var r = service.analyze(List.of(inv("a.com", 5L, 1, true)));

        assertThat(r.clean()).isTrue();
        assertThat(r.groups()).isEmpty();
    }

    @Test
    @DisplayName("counts: aktif/pasif/toplam — silinmemiş kayıtlar üzerinden")
    void counts() {
        var m = service.counts(List.of(
                inv("a.com", 5L, 1, true), inv("b.com", 5L, 1, null), inv("c.com", 5L, 1, false)));

        assertThat(m).containsEntry("active", 2).containsEntry("passive", 1).containsEntry("total", 3);
    }
}
