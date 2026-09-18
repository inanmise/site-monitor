package com.sitemonitor.service;

import com.sitemonitor.dto.CertListQuery;
import com.sitemonitor.dto.CertificateDto;
import com.sitemonitor.model.CertificateInventory;
import com.sitemonitor.model.LatestCheck;
import com.sitemonitor.model.Team;
import com.sitemonitor.repository.AlertThresholdRepository;
import com.sitemonitor.repository.CertificateCheckRepository;
import com.sitemonitor.repository.CertificateInventoryRepository;
import com.sitemonitor.repository.LatestCheckRepository;
import com.sitemonitor.repository.TeamRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import tools.jackson.databind.ObjectMapper;

import java.util.*;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.when;

/**
 * Tüm Sertifikalar sorgusu (2026-09-13 zenginleştirme): durum süzgeci satırla AYNI hükmü okur,
 * vade penceresi / takım / güvensiz / tier / port / parmak izi süzgeçleri, facet sayaçları
 * (kendi boyutu hariç), paylaşılan parmak izi sayımı, sıralama beyaz listesi ve CSV dışa aktarma.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CertificateListQueryTest {

    @Mock CertificateCheckRepository checkRepo;
    @Mock LatestCheckRepository latestRepo;
    @Mock CertificateCheckerService checkerService;
    @Mock MaintenanceService maintenanceService;
    @Mock CertificateInventoryRepository inventoryRepo;
    @Mock TeamRepository teamRepo;
    @Mock AlertThresholdRepository alertThresholdRepo;
    @Mock ActivityLogService activityLog;

    private CertificateService service;
    private final List<CertificateInventory> inventory = new ArrayList<>();
    private final List<LatestCheck> latest = new ArrayList<>();

    @BeforeEach
    void setUp() {
        service = new CertificateService(checkRepo, latestRepo, checkerService, maintenanceService,
                inventoryRepo, new ObjectMapper(), teamRepo, alertThresholdRepo, activityLog);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "self", service);
        when(checkerService.serializeSan(any())).thenReturn("[]");
        when(checkerService.deserializeSan(any())).thenReturn(Collections.emptyList());
        when(alertThresholdRepo.findFirstByActiveTrue()).thenReturn(Optional.empty());   // kritik ≤7, yüksek ≤15
        when(inventoryRepo.findByActiveTrueOrderByDomainAsc()).thenAnswer(i -> inventory);
        when(latestRepo.findByDomainIn(anyCollection())).thenAnswer(i -> latest);
        when(teamRepo.findAllById(any())).thenAnswer(i -> {
            List<Team> out = new ArrayList<>();
            for (Long id : (Iterable<Long>) i.getArgument(0)) { Team t = new Team(); t.setId(id); t.setName("Takım " + (char) ('A' + id - 1)); out.add(t); }
            return out;
        });

        // Örnek filo (example.com): seviye çeşitliliği + iki alan aynı sertifikayı paylaşıyor
        add("expired.example.com", "valid", true, -3, 1L, 1, 443, "FP-1", "TRUSTED");
        add("crit.example.com", "warning", true, 5, 1L, 1, 443, "FP-2", "TRUSTED");
        add("high.example.com", "warning", true, 10, 2L, 2, 8443, "FP-3", "TRUSTED");
        add("warn.example.com", "warning", true, 25, 2L, null, 443, "FP-4", "TRUSTED");
        add("ok.example.com", "valid", false, 200, null, 3, 443, "FP-5", "TRUSTED");
        add("shared-a.example.com", "valid", false, 120, 1L, 2, 443, "FP-SHARED", "TRUSTED");
        add("shared-b.example.com", "valid", false, 120, 2L, 2, 443, "FP-SHARED", "UNTRUSTED");   // güvensiz
        add("err.example.com", "error", true, null, 1L, 1, 443, null, null);
    }

    private void add(String domain, String status, boolean warning, Integer days, Long teamId, Integer tier, int port, String fp, String trust) {
        CertificateInventory inv = new CertificateInventory();
        inv.setDomain(domain); inv.setActive(true); inv.setTeamId(teamId); inv.setTier(tier); inv.setPort(port);
        inv.setCheckIntervalHours(domain.startsWith("ok") ? 6 : null);
        if (domain.startsWith("ok")) { inv.setGroupName("Ödeme"); inv.setTags("prod, kritik"); }   // Genel Bakış grup/etiket filtresi (2026-09-18)
        inventory.add(inv);
        LatestCheck c = new LatestCheck();
        c.setDomain(domain); c.setStatus(status); c.setWarning(warning); c.setDaysRemaining(days);
        c.setFingerprint(fp); c.setTrustStatus(trust); c.setIssuerCn("Example CA"); c.setSubject("CN=" + domain);
        c.setNotAfter("2027-01-01T00:00:00"); c.setCheckedAt("2026-09-13T10:00:00");
        c.setPublicKeyAlgorithm("RSA"); c.setPublicKeySize(domain.startsWith("high") ? 4096 : 2048);
        latest.add(c);
    }

    private CertListQuery q(String status, String team, String window, boolean insecure, Integer tier, String port, String fp) {
        return new CertListQuery(1, 50, "domain", "asc", "", "", status, team, window, insecure, tier, port, fp);
    }

    @SuppressWarnings("unchecked")
    private List<String> domains(Map<String, Object> res) {
        return ((List<CertificateDto>) res.get("data")).stream().map(CertificateDto::getDomain).collect(Collectors.toList());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> facets(Map<String, Object> res) { return (Map<String, Object>) res.get("facets"); }

    @Test
    @DisplayName("durum süzgeci alert_level diliyle: 'expired' yalnız süresi dolmuşu, 'critical' dolmuşu DAHİL ETMEZ")
    void statusFilterSpeaksAlertLevel() {
        assertThat(domains(service.getPaginated(q("expired", "", "", false, null, "", ""), null)))
                .containsExactly("expired.example.com");
        assertThat(domains(service.getPaginated(q("critical", "", "", false, null, "", ""), null)))
                .containsExactly("crit.example.com");
        assertThat(domains(service.getPaginated(q("high", "", "", false, null, "", ""), null)))
                .containsExactly("high.example.com");
        assertThat(domains(service.getPaginated(q("valid", "", "", false, null, "", ""), null)))
                .containsExactly("ok.example.com", "shared-a.example.com", "shared-b.example.com");
        assertThat(domains(service.getPaginated(q("error", "", "", false, null, "", ""), null)))
                .containsExactly("err.example.com");
    }

    @Test
    @DisplayName("vade penceresi: 30 = 0..30 gün (dolmuş hariç); 'expired' = negatif")
    void windowFilter() {
        assertThat(domains(service.getPaginated(q("", "", "30", false, null, "", ""), null)))
                .containsExactly("crit.example.com", "high.example.com", "warn.example.com");
        assertThat(domains(service.getPaginated(q("", "", "expired", false, null, "", ""), null)))
                .containsExactly("expired.example.com");
    }

    @Test
    @DisplayName("takım süzgeci kimlikle, '__none__' takımsızı getirir; tier/port/güvensiz/parmak izi süzgeçleri")
    void teamTierPortInsecureFpFilters() {
        assertThat(domains(service.getPaginated(q("", "2", "", false, null, "", ""), null)))
                .containsExactly("high.example.com", "shared-b.example.com", "warn.example.com");
        assertThat(domains(service.getPaginated(q("", "__none__", "", false, null, "", ""), null)))
                .containsExactly("ok.example.com");
        assertThat(domains(service.getPaginated(q("", "", "", false, 1, "", ""), null)))
                .containsExactly("crit.example.com", "err.example.com", "expired.example.com");
        assertThat(domains(service.getPaginated(q("", "", "", false, null, "nonstd", ""), null)))
                .containsExactly("high.example.com");
        assertThat(domains(service.getPaginated(q("", "", "", true, null, "", ""), null)))
                .containsExactly("shared-b.example.com");
        assertThat(domains(service.getPaginated(q("", "", "", false, null, "", "fp-shared"), null)))
                .containsExactly("shared-a.example.com", "shared-b.example.com");
    }

    @Test
    @DisplayName("facet'ler kendi boyutu HARİÇ sayılır: 'critical' seçiliyken öteki seviyelerin sayısı kaybolmaz")
    @SuppressWarnings("unchecked")
    void facetsExcludeOwnDimension() {
        Map<String, Object> res = service.getPaginated(q("critical", "", "", false, null, "", ""), null);
        Map<String, Integer> levels = (Map<String, Integer>) facets(res).get("levels");
        assertThat(levels).containsEntry("expired", 1).containsEntry("critical", 1).containsEntry("high", 1)
                .containsEntry("warning", 1).containsEntry("valid", 3).containsEntry("error", 1);
        // Takım süzgeci uygulanınca seviye facet'i o takıma daralır (öteki boyutlar uygulanır)
        Map<String, Object> team1 = service.getPaginated(q("", "1", "", false, null, "", ""), null);
        Map<String, Integer> lv1 = (Map<String, Integer>) facets(team1).get("levels");
        assertThat(lv1).containsEntry("valid", 1).containsEntry("high", 0);
        List<Map<String, Object>> teams = (List<Map<String, Object>>) facets(team1).get("teams");
        assertThat(teams).extracting(m -> m.get("name")).containsExactly("Takım A", "Takım B");
        assertThat(facets(team1).get("no_team")).isEqualTo(1);
        // Güvensiz / 443-dışı sayaçları takım 1'e daralır (ikisi de takım 2'de) → 0
        assertThat(facets(team1).get("insecure")).isEqualTo(0);
        assertThat(facets(team1).get("nonstd_port")).isEqualTo(0);
        assertThat(facets(team1).get("all")).isEqualTo(8);
        Map<String, Object> none = facets(service.getPaginated(q("", "", "", false, null, "", ""), null));
        assertThat(none.get("insecure")).isEqualTo(1);
        assertThat(none.get("nonstd_port")).isEqualTo(1);
        assertThat((Map<String, Integer>) none.get("windows")).containsEntry("expired", 1).containsEntry("7", 1).containsEntry("30", 3).containsEntry("90", 3);
        assertThat((Map<String, Integer>) none.get("tiers")).containsEntry("1", 3).containsEntry("2", 3).containsEntry("3", 1);
    }

    @Test
    @DisplayName("paylaşılan parmak izi: yalnız >1 olanlar 'shared' haritasında; DTO cache'i değişmez; 'shared' sıralaması")
    @SuppressWarnings("unchecked")
    void sharedFingerprintCounts() {
        Map<String, Object> res = service.getPaginated(q("", "", "", false, null, "", ""), null);
        Map<String, Integer> shared = (Map<String, Integer>) res.get("shared");
        assertThat(shared).containsExactlyInAnyOrderEntriesOf(Map.of("shared-a.example.com", 2, "shared-b.example.com", 2));
        List<String> desc = domains(service.getPaginated(new CertListQuery(1, 50, "shared", "desc", "", "", "", "", "", false, null, "", ""), null));
        assertThat(desc.subList(0, 2)).containsExactlyInAnyOrder("shared-a.example.com", "shared-b.example.com");
    }

    @Test
    @DisplayName("sıralama beyaz listesi: team/key_size/tier bilinir, bilinmeyen anahtar alan adına düşer; alan aramasi SAN/konu'ya da bakar")
    void sortWhitelistAndTextSearch() {
        List<String> byKey = domains(service.getPaginated(new CertListQuery(1, 50, "key_size", "desc", "", "", "", "", "", false, null, "", ""), null));
        assertThat(byKey.get(0)).isEqualTo("high.example.com");
        List<String> byTier = domains(service.getPaginated(new CertListQuery(1, 50, "tier", "asc", "", "", "", "", "", false, null, "", ""), null));
        assertThat(byTier.subList(0, 3)).containsExactlyInAnyOrder("expired.example.com", "crit.example.com", "err.example.com");
        List<String> unknown = domains(service.getPaginated(new CertListQuery(1, 50, "__proto__", "asc", "", "", "", "", "", false, null, "", ""), null));
        assertThat(unknown.get(0)).isEqualTo("crit.example.com");
        // "CN=ok" konuda geçer, alan adında geçmez
        List<String> subj = domains(service.getPaginated(new CertListQuery(1, 50, "domain", "asc", "CN=ok", "", "", "", "", false, null, "", ""), null));
        assertThat(subj).containsExactly("ok.example.com");
    }

    @Test
    @DisplayName("group_name + tags DTO'ya taşınır (Genel Bakış grup/etiket filtresi); envanterde boşsa null")
    void groupAndTagsOnDto() {
        CertificateDto ok = service.getAllLatest().stream().filter(c -> c.getDomain().startsWith("ok")).findFirst().orElseThrow();
        assertThat(ok.getGroupName()).isEqualTo("Ödeme");
        assertThat(ok.getTags()).isEqualTo("prod, kritik");
        CertificateDto other = service.getAllLatest().stream().filter(c -> c.getDomain().startsWith("warn")).findFirst().orElseThrow();
        assertThat(other.getGroupName()).isNull();
        assertThat(other.getTags()).isNull();
    }

    @Test
    @DisplayName("check_interval_hours DTO'ya taşınır (tablo 'bayat' rozeti)")
    void checkIntervalOnDto() {
        CertificateDto ok = service.getAllLatest().stream().filter(c -> c.getDomain().startsWith("ok")).findFirst().orElseThrow();
        assertThat(ok.getCheckIntervalHours()).isEqualTo(6);
    }

    @Test
    @DisplayName("CSV: başlık istemcinin sütun sırasıyla, bilinmeyen sütun atlanır, formül/virgül kaçırılır, süzgeç uygulanır")
    void csvExport() {
        String csv = service.exportCsv(q("", "2", "", false, null, "", ""), null, List.of("domain", "status", "shared", "bogus", "team"));
        String[] lines = csv.split("\r\n");   // Csv.row CRLF ile biter (ortak kural)
        assertThat(lines[0]).isEqualTo("domain,status,shared,team");
        assertThat(lines).hasSize(4);   // başlık + takım 2'nin 3 satırı
        assertThat(lines[3]).isEqualTo("warn.example.com,warning,1,Takım B");
        assertThat(csv).contains("shared-b.example.com,valid,2,Takım B");
        // Hücre kaçışı ortak kuraldan (com.sitemonitor.util.Csv — CsvExportGuardTest kapısı); virgüllü veren tırnaklanır
        latest.get(0).setIssuerCn("Example, Inc CA");
        assertThat(service.exportCsv(q("", "", "", false, null, "", "fp-1"), null, List.of("domain", "issuer")))
                .contains("expired.example.com,\"Example, Inc CA\"");
        // Sütun verilmezse tüm sütunlar
        assertThat(service.exportCsv(q("", "", "", false, null, "", ""), null, List.of()).split("\r\n")[0]).startsWith("domain,issuer,subject,team,");
    }
}
