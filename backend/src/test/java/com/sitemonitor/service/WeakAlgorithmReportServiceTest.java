package com.sitemonitor.service;

import com.sitemonitor.model.CertificateInventory;
import com.sitemonitor.model.LatestCheck;
import com.sitemonitor.model.Team;
import com.sitemonitor.model.WeakAlgorithmException;
import com.sitemonitor.repository.CertificateCheckRepository;
import com.sitemonitor.repository.CertificateInventoryRepository;
import com.sitemonitor.repository.LatestCheckRepository;
import com.sitemonitor.repository.TeamRepository;
import com.sitemonitor.repository.WeakAlgorithmExceptionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Zayıf Algoritma Raporu zengin gövdesi (2026-09-12). Tarihler KAYAN (now'dan türetilir) — sabit
 * fixture tarihi yazılmaz (zaman bombası tuzağı).
 */
@ExtendWith(MockitoExtension.class)
@SuppressWarnings("unchecked")
class WeakAlgorithmReportServiceTest {

    private static final DateTimeFormatter ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);

    @Mock LatestCheckRepository latestCheckRepo;
    @Mock CertificateInventoryRepository inventoryRepo;
    @Mock TeamRepository teamRepo;
    @Mock CertificateCheckRepository certificateCheckRepo;
    @Mock WeakAlgorithmExceptionRepository exceptionRepo;

    WeakAlgorithmReportService svc;

    @BeforeEach
    void setUp() {
        svc = new WeakAlgorithmReportService(latestCheckRepo, inventoryRepo, teamRepo, certificateCheckRepo, exceptionRepo);
        lenient().when(certificateCheckRepo.weakObservationsSince(anyString())).thenReturn(List.of());
        lenient().when(exceptionRepo.findAll()).thenReturn(List.of());
    }

    private static CertificateInventory inv(String domain, Long teamId) {
        CertificateInventory i = new CertificateInventory();
        i.setDomain(domain); i.setPort(443); i.setActive(true); i.setTeamId(teamId); i.setOwner("Sahip");
        return i;
    }

    private static LatestCheck lc(String domain, String sig, String keyAlg, Integer keySize, String tls, String cipher) {
        LatestCheck c = new LatestCheck();
        c.setDomain(domain); c.setSignatureAlgorithm(sig); c.setPublicKeyAlgorithm(keyAlg); c.setPublicKeySize(keySize);
        c.setTlsVersion(tls); c.setCipherSuite(cipher); c.setStatus("valid");
        c.setCheckedAt(ISO.format(Instant.now().minusSeconds(600)));
        c.setChainStatus("VALID"); c.setTrustStatus("TRUSTED"); c.setRevocationStatus("VALID"); c.setOcspUrl("http://ocsp.example.com");
        return c;
    }

    private static Team team(long id, String name) { Team t = new Team(); t.setId(id); t.setName(name); return t; }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> rows(Object o) { return (List<Map<String, Object>>) o; }

    @Test
    @DisplayName("boş filo: eski sözleşme (data/total/critical/high) + tarama özeti/kurallar/dağılım bölümleri her zaman döner")
    void build_emptyFleet_hasAllSections() {
        when(inventoryRepo.findByActiveTrueOrderByDomainAsc()).thenReturn(List.of());
        when(latestCheckRepo.findAll()).thenReturn(List.of());
        when(teamRepo.findAll()).thenReturn(List.of());

        Map<String, Object> b = svc.build();

        assertThat(b).containsKeys("data", "total", "critical", "high", "scan", "rules", "distribution",
                "outlook", "tls", "chain", "teams", "trend", "exceptions");
        assertThat(b.get("total")).isEqualTo(0);
        assertThat(((Map<?, ?>) b.get("scan")).get("active_domains")).isEqualTo(0);
        // Kural kataloğu boş filoda da tam listelenir (her kural "eşleşen: 0")
        assertThat(rows(b.get("rules"))).hasSize(WeakAlgorithmReportService.RULES.size())
                .allSatisfy(r -> assertThat(r.get("matched")).isEqualTo(0));
        assertThat(rows(((Map<?, ?>) b.get("trend")).get("series"))).hasSize(30);
    }

    @Test
    @DisplayName("zayıf sertifika: satır + kural sayacı + takım kırılımı; SHA-1 HIGH (classifyWeakness ile aynı); temiz alan yalnız dağılıma girer")
    void build_classifiesAndCounts() {
        when(inventoryRepo.findByActiveTrueOrderByDomainAsc()).thenReturn(List.of(
                inv("sha1.example.com", 1L), inv("ok.example.com", 1L), inv("nobody.example.com", null)));
        when(latestCheckRepo.findAll()).thenReturn(List.of(
                lc("sha1.example.com", "SHA1withRSA", "RSA", 2048, "TLSv1.2", "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256"),
                lc("ok.example.com", "SHA256withRSA", "RSA", 4096, "TLSv1.3", "TLS_AES_256_GCM_SHA384"),
                lc("nobody.example.com", "SHA256withRSA", "RSA", 1024, "TLSv1.3", "TLS_AES_256_GCM_SHA384")));
        when(teamRepo.findAll()).thenReturn(List.of(team(1L, "Takım A")));

        Map<String, Object> b = svc.build();

        assertThat(b.get("total")).isEqualTo(2);
        assertThat(b.get("critical")).isEqualTo(1L);   // RSA 1024
        assertThat(b.get("high")).isEqualTo(1L);       // SHA-1
        List<Map<String, Object>> data = rows(b.get("data"));
        assertThat(data.get(0).get("domain")).isEqualTo("nobody.example.com");   // CRITICAL üstte
        assertThat(data.get(1)).containsEntry("team_name", "Takım A").containsEntry("owner", "Sahip");
        assertThat((List<String>) data.get(1).get("rule_keys")).containsExactly("sig.sha1");

        Map<String, Integer> hits = new java.util.HashMap<>();
        rows(b.get("rules")).forEach(r -> hits.put((String) r.get("key"), (Integer) r.get("matched")));
        assertThat(hits).containsEntry("sig.sha1", 1).containsEntry("key.rsa1024", 1).containsEntry("key.rsa2048", 0);

        Map<?, ?> teams = (Map<?, ?>) b.get("teams");
        assertThat(rows(teams.get("rows")).get(0)).containsEntry("team_name", "Takım A").containsEntry("total", 2).containsEntry("weak", 1);
        assertThat((Map<String, Object>) teams.get("unowned")).containsEntry("total", 1).containsEntry("weak", 1);

        Map<?, ?> dist = (Map<?, ?>) b.get("distribution");
        assertThat(rows(dist.get("signature"))).extracting(m -> m.get("label")).containsExactly("SHA256withRSA", "SHA1withRSA");
        assertThat(rows(dist.get("tls"))).extracting(m -> m.get("label")).containsExactly("TLSv1.3", "TLSv1.2");
    }

    @Test
    @DisplayName("2030 görünümü: RSA 2048 bugün temiz ama 3072 eşiğinde etkilenir; RSA 4096 ve EC etkilenmez")
    void build_outlook2030() {
        when(inventoryRepo.findByActiveTrueOrderByDomainAsc()).thenReturn(List.of(
                inv("rsa2048.example.com", 1L), inv("rsa4096.example.com", 1L), inv("ec.example.com", 1L)));
        when(latestCheckRepo.findAll()).thenReturn(List.of(
                lc("rsa2048.example.com", "SHA256withRSA", "RSA", 2048, "TLSv1.3", "TLS_AES_256_GCM_SHA384"),
                lc("rsa4096.example.com", "SHA256withRSA", "RSA", 4096, "TLSv1.3", "TLS_AES_256_GCM_SHA384"),
                lc("ec.example.com", "SHA256withECDSA", "EC", 256, "TLSv1.3", "TLS_AES_256_GCM_SHA384")));
        when(teamRepo.findAll()).thenReturn(List.of());

        Map<?, ?> outlook = (Map<?, ?>) svc.build().get("outlook");
        assertThat(outlook.get("affected")).isEqualTo(1);
        assertThat(outlook.get("rsa_min_bits")).isEqualTo(3072);
        assertThat(rows(outlook.get("rows")).get(0)).containsEntry("domain", "rsa2048.example.com").containsEntry("reason", "key.rsa3072");
    }

    @Test
    @DisplayName("TLS ve zincir bulguları sertifika algoritmasından bağımsız: TLS 1.0 + RC4 → CRITICAL; CBC → MEDIUM; kırık zincir + OCSP/CRL yok")
    void build_tlsAndChainFindings() {
        LatestCheck legacy = lc("legacy.example.com", "SHA256withRSA", "RSA", 2048, "TLSv1", "TLS_RSA_WITH_RC4_128_SHA");
        LatestCheck cbc = lc("cbc.example.com", "SHA256withRSA", "RSA", 2048, "TLSv1.2", "TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA");
        LatestCheck broken = lc("broken.example.com", "SHA256withRSA", "RSA", 2048, "TLSv1.3", "TLS_AES_256_GCM_SHA384");
        broken.setChainStatus("BROKEN"); broken.setOcspUrl(null); broken.setCrlUrl(null); broken.setIntermediateDaysRemaining(12);
        when(inventoryRepo.findByActiveTrueOrderByDomainAsc()).thenReturn(List.of(
                inv("legacy.example.com", 1L), inv("cbc.example.com", 1L), inv("broken.example.com", 1L)));
        when(latestCheckRepo.findAll()).thenReturn(List.of(legacy, cbc, broken));
        when(teamRepo.findAll()).thenReturn(List.of());

        Map<String, Object> b = svc.build();
        assertThat(b.get("total")).isEqualTo(0);   // sertifika algoritması temiz

        List<Map<String, Object>> tls = rows(((Map<?, ?>) b.get("tls")).get("rows"));
        assertThat(tls).hasSize(2);
        assertThat(tls.get(0)).containsEntry("domain", "legacy.example.com").containsEntry("severity", "CRITICAL");
        assertThat((List<String>) tls.get(0).get("findings")).containsExactly("tls.legacy", "cipher.weak", "pfs.none");
        assertThat(tls.get(1)).containsEntry("domain", "cbc.example.com").containsEntry("severity", "MEDIUM");

        List<Map<String, Object>> chain = rows(((Map<?, ?>) b.get("chain")).get("rows"));
        assertThat(chain).hasSize(1);
        assertThat((List<String>) chain.get(0).get("findings")).containsExactly("chain.broken", "revocation.nourl", "intermediate.expiring");
        assertThat(chain.get(0)).containsEntry("severity", "HIGH").containsEntry("intermediate_days", 12);
    }

    @Test
    @DisplayName("tarama özeti: hiç kontrol edilmemiş, 24 saatte kontrol edilen ve hatalı alanlar ayrı sayılır")
    void build_scanSummary() {
        LatestCheck fresh = lc("fresh.example.com", "SHA256withRSA", "RSA", 2048, "TLSv1.3", "TLS_AES_256_GCM_SHA384");
        LatestCheck stale = lc("stale.example.com", "SHA256withRSA", "RSA", 2048, "TLSv1.3", "TLS_AES_256_GCM_SHA384");
        stale.setCheckedAt(ISO.format(Instant.now().minusSeconds(3 * 86400)));
        stale.setStatus("error");
        when(inventoryRepo.findByActiveTrueOrderByDomainAsc()).thenReturn(List.of(
                inv("fresh.example.com", 1L), inv("stale.example.com", 1L), inv("never.example.com", 1L)));
        when(latestCheckRepo.findAll()).thenReturn(List.of(fresh, stale));
        when(teamRepo.findAll()).thenReturn(List.of());

        Map<String, Object> scan = (Map<String, Object>) svc.build().get("scan");
        assertThat(scan).containsEntry("active_domains", 3).containsEntry("checked", 2)
                .containsEntry("checked_24h", 1).containsEntry("never_checked", 1).containsEntry("error", 1);
        assertThat(scan.get("latest_checked_at")).isEqualTo(fresh.getCheckedAt());
    }

    @Test
    @DisplayName("istisna: satıra işlenir, süresi geçmişse expired=true; excepted sayacı")
    void build_exceptionsAttached() {
        WeakAlgorithmException live = new WeakAlgorithmException();
        live.setDomain("sha1.example.com"); live.setReason("yenileme planlı"); live.setUntil(LocalDate.now(ZoneOffset.UTC).plusDays(30).toString());
        WeakAlgorithmException old = new WeakAlgorithmException();
        old.setDomain("rsa.example.com"); old.setUntil(LocalDate.now(ZoneOffset.UTC).minusDays(1).toString());
        when(exceptionRepo.findAll()).thenReturn(List.of(live, old));
        when(inventoryRepo.findByActiveTrueOrderByDomainAsc()).thenReturn(List.of(inv("sha1.example.com", 1L), inv("rsa.example.com", 1L)));
        when(latestCheckRepo.findAll()).thenReturn(List.of(
                lc("sha1.example.com", "SHA1withRSA", "RSA", 2048, "TLSv1.3", "TLS_AES_256_GCM_SHA384"),
                lc("rsa.example.com", "SHA256withRSA", "RSA", 1024, "TLSv1.3", "TLS_AES_256_GCM_SHA384")));
        when(teamRepo.findAll()).thenReturn(List.of());

        Map<String, Object> b = svc.build();
        assertThat(b.get("excepted")).isEqualTo(2L);
        Map<String, Object> sha1 = rows(b.get("data")).stream().filter(r -> "sha1.example.com".equals(r.get("domain"))).findFirst().orElseThrow();
        assertThat((Map<String, Object>) sha1.get("exception")).containsEntry("reason", "yenileme planlı").containsEntry("expired", false);
        Map<String, Object> rsa = rows(b.get("data")).stream().filter(r -> "rsa.example.com".equals(r.get("domain"))).findFirst().orElseThrow();
        assertThat((Map<String, Object>) rsa.get("exception")).containsEntry("expired", true);
        assertThat(rows(b.get("exceptions"))).hasSize(2);
    }

    @Test
    @DisplayName("trend: gün başına zayıf alan; pencere içinde ilk görülen = detected, artık zayıf olmayan = resolved")
    void trend_detectedAndResolved() {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        when(certificateCheckRepo.weakObservationsSince(anyString())).thenReturn(List.of(
                new Object[] {"gone.example.com", today.minusDays(10).toString()},
                new Object[] {"gone.example.com", today.minusDays(9).toString()},
                new Object[] {"still.example.com", today.minusDays(2).toString()},
                new Object[] {"still.example.com", today.toString()},
                new Object[] {"ancient.example.com", today.minusDays(60).toString()}));   // pencere dışı → yok sayılır

        Map<String, Object> t = svc.trend(Instant.now(), Set.of("still.example.com"));

        List<Map<String, Object>> series = rows(t.get("series"));
        assertThat(series).hasSize(30);
        assertThat(series.get(29)).containsEntry("day", today.toString()).containsEntry("weak", 1);
        assertThat(series.get(19)).containsEntry("day", today.minusDays(10).toString()).containsEntry("weak", 1);
        assertThat(rows(t.get("detected"))).extracting(m -> m.get("domain")).containsExactly("gone.example.com", "still.example.com");
        assertThat(rows(t.get("resolved"))).hasSize(1);
        assertThat(rows(t.get("resolved")).get(0)).containsEntry("domain", "gone.example.com").containsEntry("day", today.minusDays(9).toString());
    }

    @Test
    @DisplayName("istisna kaydı: bitiş tarihi zorunlu ve geçmişte olamaz; mevcut kayıt üzerine yazılır; temizleme id ile siler")
    void exception_setAndClear() {
        assertThatThrownBy(() -> svc.setException("a.example.com", "x", null, "admin")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> svc.setException("a.example.com", "x", "2020-01-01", "admin"))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("until_past");
        assertThatThrownBy(() -> svc.setException("a.example.com", "x", "bugün", "admin")).isInstanceOf(IllegalArgumentException.class);

        WeakAlgorithmException existing = new WeakAlgorithmException();
        existing.setId(7L); existing.setDomain("a.example.com"); existing.setReason("eski");
        when(exceptionRepo.findByDomain("a.example.com")).thenReturn(Optional.of(existing));
        when(exceptionRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        String until = LocalDate.now(ZoneOffset.UTC).plusDays(10).toString();
        WeakAlgorithmException saved = svc.setException("a.example.com", " yeni ", until, "admin");
        assertThat(saved.getId()).isEqualTo(7L);
        assertThat(saved.getReason()).isEqualTo("yeni");
        assertThat(saved.getUntil()).isEqualTo(until);
        assertThat(saved.getCreatedBy()).isEqualTo("admin");

        assertThat(svc.clearException("a.example.com")).isTrue();
        verify(exceptionRepo).deleteById(7L);
        when(exceptionRepo.findByDomain("none.example.com")).thenReturn(Optional.empty());
        assertThat(svc.clearException("none.example.com")).isFalse();
        verify(exceptionRepo, org.mockito.Mockito.times(1)).deleteById(anyLong());   // ikinci çağrı silmedi
    }

    @Test
    @DisplayName("CSV: başlık + üç kaynak (certificate/tls/chain) tek dosyada; bulgular ' | ' ile birleşik")
    void csv_allSources() {
        LatestCheck legacy = lc("legacy.example.com", "SHA1withRSA", "RSA", 2048, "TLSv1", "TLS_RSA_WITH_RC4_128_SHA");
        when(inventoryRepo.findByActiveTrueOrderByDomainAsc()).thenReturn(List.of(inv("legacy.example.com", 1L)));
        when(latestCheckRepo.findAll()).thenReturn(List.of(legacy));
        when(teamRepo.findAll()).thenReturn(List.of(team(1L, "Takım A")));

        String csv = svc.toCsv(svc.build());
        String[] lines = csv.trim().split("\\r?\\n");
        assertThat(lines[0]).startsWith("source,severity,domain,team");
        assertThat(lines).hasSize(3);
        assertThat(lines[1]).startsWith("certificate,HIGH,legacy.example.com,Takım A,Sahip,SHA1withRSA,RSA 2048,TLSv1,");
        assertThat(lines[2]).startsWith("tls,CRITICAL,legacy.example.com").contains("tls.legacy | cipher.weak | pfs.none");
    }

    @Test
    @DisplayName("haftalık e-posta yardımcısı: takım alanlarında zayıf sayısı + taranan sayısı")
    void weakAndScannedFor_countsWithinTeam() {
        LatestCheck weak = lc("w.example.com", "MD5withRSA", "RSA", 2048, null, null);
        LatestCheck other = lc("other.example.com", "SHA1withRSA", "RSA", 2048, null, null);   // başka takım
        LatestCheck clean = lc("c.example.com", "SHA256withRSA", "RSA", 2048, null, null);
        int[] r = WeakAlgorithmReportService.weakAndScannedFor(List.of("w.example.com", "c.example.com", "never.example.com"),
                List.of(weak, other), Map.of("w.example.com", weak, "c.example.com", clean));
        assertThat(r).containsExactly(1, 2);
    }
}
