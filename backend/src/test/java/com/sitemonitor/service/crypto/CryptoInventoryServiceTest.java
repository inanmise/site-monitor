package com.sitemonitor.service.crypto;

import com.sitemonitor.model.CertificateInventory;
import com.sitemonitor.model.LatestCheck;
import com.sitemonitor.model.ManualCertificateVersion;
import com.sitemonitor.model.Team;
import com.sitemonitor.model.WeakAlgorithmException;
import com.sitemonitor.repository.CertificateInventoryRepository;
import com.sitemonitor.repository.LatestCheckRepository;
import com.sitemonitor.repository.ManualCertificateVersionRepository;
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
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Kripto envanteri servisi (2026-10-10): satır sınıflandırması (ağ + manuel), ara sertifika SHA-1 kalıntısı, kapsam
 * (Zayıf Algoritma raporuyla aynı), öncelik sırası, takım kırılımı, bellek ve "satır başına sorgu yok". Tarihler
 * KAYAN — sabit fixture tarihi yazılmaz (zaman bombası).
 */
@ExtendWith(MockitoExtension.class)
@SuppressWarnings("unchecked")
class CryptoInventoryServiceTest {

    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);

    @Mock CertificateInventoryRepository inventoryRepo;
    @Mock LatestCheckRepository latestCheckRepo;
    @Mock TeamRepository teamRepo;
    @Mock ManualCertificateVersionRepository versionRepo;
    @Mock WeakAlgorithmExceptionRepository exceptionRepo;

    CryptoInventoryService svc;

    @BeforeEach
    void setUp() {
        svc = new CryptoInventoryService(inventoryRepo, latestCheckRepo, teamRepo, versionRepo, exceptionRepo);
        lenient().when(teamRepo.findAll()).thenReturn(List.of(team(1, "Takım A"), team(2, "Takım B"), team(3, "Takım C")));
        lenient().when(exceptionRepo.findAll()).thenReturn(List.of());
    }

    // ── Yardımcılar ──────────────────────────────────────────────────────────────────────

    private static Team team(long id, String name) { Team t = new Team(); t.setId(id); t.setName(name); return t; }

    private static CertificateInventory inv(long id, String domain, Long teamId, Integer tier) {
        CertificateInventory i = new CertificateInventory();
        i.setId(id); i.setDomain(domain); i.setPort(443); i.setActive(true); i.setTeamId(teamId); i.setTier(tier);
        i.setOwner("Sahip " + id);
        return i;
    }

    private static String inDays(long days) { return ISO.format(Instant.now().plus(days, ChronoUnit.DAYS)); }

    private static LatestCheck lc(String domain, String keyAlg, Integer size, String sig, long daysLeft, String chainJson) {
        LatestCheck c = new LatestCheck();
        c.setDomain(domain); c.setPublicKeyAlgorithm(keyAlg); c.setPublicKeySize(size); c.setSignatureAlgorithm(sig);
        c.setNotAfter(inDays(daysLeft)); c.setDaysRemaining((int) daysLeft); c.setStatus("valid");
        c.setTlsVersion("TLSv1.3"); c.setCipherSuite("TLS_AES_256_GCM_SHA384");
        c.setCheckedAt(ISO.format(Instant.now().minusSeconds(600)));
        c.setChainDetails(chainJson);
        return c;
    }

    private static String chain(String leafSig, String intSig, String rootSig) {
        return "[{\"position\":0,\"is_leaf\":true,\"is_root\":false,\"subject\":\"CN=leaf\",\"signature_algorithm\":\"" + leafSig + "\"},"
             + "{\"position\":1,\"is_leaf\":false,\"is_root\":false,\"subject\":\"CN=Example Issuing CA,O=Example\",\"signature_algorithm\":\"" + intSig + "\"},"
             + "{\"position\":2,\"is_leaf\":false,\"is_root\":true,\"subject\":\"CN=Example Root\",\"signature_algorithm\":\"" + rootSig + "\"}]";
    }

    private static List<Map<String, Object>> rows(Map<String, Object> body) { return (List<Map<String, Object>>) body.get("rows"); }

    private static Map<String, Object> rowOf(Map<String, Object> body, String domain) {
        return rows(body).stream().filter(r -> domain.equals(r.get("domain"))).findFirst().orElseThrow();
    }

    private static Map<String, Object> summary(Map<String, Object> body) { return (Map<String, Object>) body.get("summary"); }

    // ── Testler ──────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("boş filo: tüm bölümler döner, sayaçlar sıfır, kural tablosu ve eşikler var")
    void emptyFleet() {
        when(inventoryRepo.findByActiveTrueOrderByDomainAsc()).thenReturn(List.of());
        Map<String, Object> body = svc.buildUncached(null, Instant.now());
        assertThat(body).containsKeys("generated_at", "data_as_of", "scope", "summary", "algorithms", "signatures",
                "signature_algorithms", "teams", "unowned", "rows", "rule", "thresholds");
        assertThat(rows(body)).isEmpty();
        assertThat(summary(body)).containsEntry("total", 0);
        assertThat((Map<String, Object>) body.get("thresholds")).containsEntry("rsa_2030_min_bits", 3072)
                .containsEntry("sunset", "2030-12-31").containsEntry("kex_observable", false);
        assertThat((Map<String, Object>) body.get("scope")).containsEntry("all", true);
        verify(latestCheckRepo, never()).findAll();          // alan adı yoksa son kontrol tablosu okunmaz
    }

    @Test
    @DisplayName("ağ satırları: kova, imza özeti, PQC durumu, kategori, eylem ve ara sertifika SHA-1 kalıntısı")
    void networkRowsClassified() {
        when(inventoryRepo.findByActiveTrueOrderByDomainAsc()).thenReturn(List.of(
                inv(1, "legacy.example.com", 1L, 1), inv(2, "modern.example.com", 1L, 4),
                inv(3, "sha1int.example.com", 2L, 2), inv(4, "unchecked.example.com", 2L, 3)));
        when(latestCheckRepo.findAll()).thenReturn(List.of(
                lc("legacy.example.com", "RSA", 2048, "SHA256withRSA", 20, chain("SHA256withRSA", "SHA256withRSA", "SHA1withRSA")),
                lc("modern.example.com", "EC", 256, "SHA256withECDSA", 400, "[]"),
                lc("sha1int.example.com", "RSA", 4096, "SHA256withRSA", 200, chain("SHA256withRSA", "SHA1withRSA", "SHA256withRSA")),
                lc("other.example.com", "RSA", 1024, "MD5withRSA", 10, null)));   // envanterde değil → sayılmaz

        Map<String, Object> body = svc.buildUncached(null, Instant.now());
        assertThat(rows(body)).hasSize(4);

        Map<String, Object> legacy = rowOf(body, "legacy.example.com");
        assertThat(legacy).containsEntry("key_bucket", "RSA_2048").containsEntry("key_family", "RSA")
                .containsEntry("sig_hash", "SHA256").containsEntry("pqc", "VULNERABLE").containsEntry("category", "LEGACY")
                .containsEntry("action", "renew").containsEntry("source", "NETWORK").containsEntry("data_source", "CHECK")
                .containsEntry("pfs", true).containsEntry("team_name", "Takım A").containsEntry("owner", "Sahip 1");
        assertThat((List<String>) legacy.get("remnants")).isEmpty();          // kök SHA-1 risk değil
        assertThat((Map<String, Object>) legacy.get("priority")).containsEntry("score", 85).containsEntry("band", "P1");
        assertThat(legacy.get("migrate_by")).isEqualTo(String.valueOf(legacy.get("not_after")).substring(0, 10));

        Map<String, Object> modern = rowOf(body, "modern.example.com");
        assertThat(modern).containsEntry("key_bucket", "EC_P256").containsEntry("category", "MODERN").containsEntry("action", "pqc_plan");
        assertThat((Map<String, Object>) modern.get("priority")).containsEntry("score", 20).containsEntry("band", "P4");

        Map<String, Object> sha1 = rowOf(body, "sha1int.example.com");
        assertThat(sha1).containsEntry("key_bucket", "RSA_4096").containsEntry("category", "BROKEN").containsEntry("action", "replace")
                .containsEntry("intermediate_count", 1);
        assertThat((List<String>) sha1.get("remnants")).containsExactly("SHA1_INTERMEDIATE");
        assertThat((List<Map<String, Object>>) sha1.get("weak_intermediates")).singleElement()
                .satisfies(w -> assertThat(w).containsEntry("subject", "Example Issuing CA").containsEntry("signature_algorithm", "SHA1withRSA"));

        Map<String, Object> none = rowOf(body, "unchecked.example.com");
        assertThat(none).containsEntry("key_bucket", "UNKNOWN").containsEntry("pqc", "UNKNOWN").containsEntry("category", "UNKNOWN")
                .containsEntry("action", "collect").containsEntry("data_source", "NONE");

        Map<String, Object> rem = (Map<String, Object>) summary(body).get("remnants");
        assertThat(rem).containsEntry("sha1_leaf", 0).containsEntry("sha1_intermediate", 1).containsEntry("sha1_root", 1)
                .containsEntry("chains_examined", 2).containsEntry("affected", 1);
        assertThat((Map<String, Object>) summary(body).get("by_pqc")).containsEntry("VULNERABLE", 3).containsEntry("UNKNOWN", 1);
        assertThat((Map<String, Object>) summary(body).get("by_category")).containsEntry("BROKEN", 1).containsEntry("LEGACY", 1)
                .containsEntry("MODERN", 1).containsEntry("UNKNOWN", 1).containsEntry("PQC_READY", 0);
        assertThat(summary(body)).containsEntry("vulnerable_expiring_90d", 1).containsEntry("checked", 3).containsEntry("unchecked", 1);
    }

    @Test
    @DisplayName("öncelik sırası: puan ↓, sonra kalan gün ↑; rank 1'den başlar; dağılım payları toplamı 100")
    void priorityOrder() {
        when(inventoryRepo.findByActiveTrueOrderByDomainAsc()).thenReturn(List.of(
                inv(1, "a.example.com", 1L, 4), inv(2, "b.example.com", 1L, 1), inv(3, "c.example.com", 1L, 1)));
        when(latestCheckRepo.findAll()).thenReturn(List.of(
                lc("a.example.com", "EC", 256, "SHA256withECDSA", 400, "[]"),
                lc("b.example.com", "RSA", 2048, "SHA256withRSA", 200, "[]"),    // 40 + 20 + 5 + 5 = 70
                lc("c.example.com", "RSA", 2048, "SHA256withRSA", 150, "[]")));  // 40 + 20 + 10 + 5 = 75
        Map<String, Object> body = svc.buildUncached(null, Instant.now());
        assertThat(rows(body).stream().map(r -> r.get("domain")).toList())
                .containsExactly("c.example.com", "b.example.com", "a.example.com");
        assertThat(rows(body).stream().map(r -> r.get("rank")).toList()).containsExactly(1, 2, 3);
        double share = ((List<Map<String, Object>>) body.get("algorithms")).stream().mapToDouble(a -> (double) a.get("share")).sum();
        assertThat(share).isBetween(99.8, 100.2);
    }

    @Test
    @DisplayName("manuel sertifika: algoritma GEÇERLİ sürümün kayıtlı alanlarından; TLS/PFS yok; HNDL puanı 0")
    void manualCertificateFromVersion() {
        CertificateInventory m = inv(10, "keystore-app-prod", 1L, 2);
        m.setCertSource(CertificateInventory.SOURCE_MANUAL);
        m.setManualVersion(3);
        when(inventoryRepo.findByActiveTrueOrderByDomainAsc()).thenReturn(List.of(m));
        ManualCertificateVersion v = new ManualCertificateVersion();
        v.setInventoryId(10L); v.setVersion(3); v.setCurrent(true);
        v.setKeyAlg("RSA"); v.setKeySize(2048); v.setSignatureAlgorithm("SHA1withRSA"); v.setNotAfter(inDays(45));
        v.setUploadedBy("gizli.kullanici"); v.setUploadedAt(ISO.format(Instant.now().minusSeconds(3600)));
        when(versionRepo.findByInventoryIdInAndCurrentTrue(anyCollection())).thenReturn(List.of(v));
        when(latestCheckRepo.findAll()).thenReturn(List.of());

        Map<String, Object> body = svc.buildUncached(null, Instant.now());
        Map<String, Object> r = rowOf(body, "keystore-app-prod");
        assertThat(r).containsEntry("source", "MANUAL").containsEntry("data_source", "UPLOAD").containsEntry("manual_version", 3)
                .containsEntry("key_bucket", "RSA_2048").containsEntry("sig_hash", "SHA1").containsEntry("category", "BROKEN")
                .containsEntry("port", null).containsEntry("tls_version", null).containsEntry("pfs", null);
        assertThat((List<String>) r.get("remnants")).containsExactly("SHA1_LEAF");
        assertThat((Integer) r.get("days_remaining")).isBetween(44, 45);
        // 25 (T2) + 30 (BROKEN) + 15 (≤90 gün) + 0 (manuel: kayıtlı trafik yok)
        assertThat((Map<String, Object>) r.get("priority")).containsEntry("score", 70).containsEntry("hndl", 0);
        assertThat(r.toString()).doesNotContain("gizli.kullanici");   // yükleyen kullanıcı adı dışarı çıkmaz
        assertThat(summary(body)).containsEntry("manual", 1).containsEntry("network", 0);
    }

    @Test
    @DisplayName("manuel sürüm yoksa son değerlendirme sonucuna (latest_checks) düşer")
    void manualFallsBackToLatestCheck() {
        CertificateInventory m = inv(11, "vendor-pem", 1L, 3);
        m.setCertSource(CertificateInventory.SOURCE_MANUAL);
        when(inventoryRepo.findByActiveTrueOrderByDomainAsc()).thenReturn(List.of(m));
        when(versionRepo.findByInventoryIdInAndCurrentTrue(anyCollection())).thenReturn(List.of());
        when(latestCheckRepo.findAll()).thenReturn(List.of(lc("vendor-pem", "EC", 384, "SHA384withECDSA", 300, "[]")));
        Map<String, Object> r = rowOf(svc.buildUncached(null, Instant.now()), "vendor-pem");
        assertThat(r).containsEntry("source", "MANUAL").containsEntry("data_source", "UPLOAD").containsEntry("key_bucket", "EC_P384")
                .containsEntry("category", "MODERN").containsEntry("tls_version", null);
    }

    @Test
    @DisplayName("kapsam: Zayıf Algoritma raporuyla aynı — SY ya da UG takımı kapsamda; sahipsiz ve başka takım görünmez")
    void scopeFilter() {
        CertificateInventory ug = inv(3, "ug.example.com", 3L, 2);
        ug.setUgTeamId(1L);
        when(inventoryRepo.findByActiveTrueOrderByDomainAsc()).thenReturn(List.of(
                inv(1, "mine.example.com", 1L, 1), inv(2, "other.example.com", 2L, 1), ug, inv(4, "orphan.example.com", null, 1)));
        when(latestCheckRepo.findByDomainIn(anyCollection())).thenAnswer(a -> {
            Collection<String> ds = a.getArgument(0);
            List<LatestCheck> out = new ArrayList<>();
            for (String d : ds) out.add(lc(d, "RSA", 2048, "SHA256withRSA", 100, "[]"));
            return out;
        });
        Map<String, Object> body = svc.buildUncached(List.of(1L), Instant.now());
        assertThat(rows(body).stream().map(r -> r.get("domain")).toList())
                .containsExactlyInAnyOrder("mine.example.com", "ug.example.com");
        assertThat((Map<String, Object>) body.get("scope")).containsEntry("all", false);
        assertThat((List<Map<String, Object>>) ((Map<String, Object>) body.get("scope")).get("teams"))
                .singleElement().satisfies(t -> assertThat(t).containsEntry("id", 1L).containsEntry("name", "Takım A"));
        verify(latestCheckRepo, never()).findAll();      // kapsamlı görünüm tüm tabloyu okumaz
        verify(latestCheckRepo, times(1)).findByDomainIn(anyCollection());
        // takım kırılımı birincil (SY) takıma göre: UG ile görünen satır kendi SY takımı altında
        List<Map<String, Object>> teams = (List<Map<String, Object>>) body.get("teams");
        assertThat(teams.stream().map(t -> t.get("team_name")).toList()).containsExactlyInAnyOrder("Takım A", "Takım C");
        assertThat((Map<String, Object>) body.get("unowned")).containsEntry("total", 0);
    }

    @Test
    @DisplayName("takım kırılımı: kategori + bant sayıları, en yüksek puan, en yakın geçiş tarihi; sahipsiz ayrı")
    void teamBreakdown() {
        when(inventoryRepo.findByActiveTrueOrderByDomainAsc()).thenReturn(List.of(
                inv(1, "a1.example.com", 1L, 1), inv(2, "a2.example.com", 1L, 4), inv(3, "orphan.example.com", null, 3)));
        when(latestCheckRepo.findAll()).thenReturn(List.of(
                lc("a1.example.com", "RSA", 1024, "SHA256withRSA", 10, "[]"),
                lc("a2.example.com", "EC", 256, "SHA256withECDSA", 400, "[]"),
                lc("orphan.example.com", "RSA", 2048, "SHA1withRSA", 50, "[]")));
        Map<String, Object> body = svc.buildUncached(null, Instant.now());
        Map<String, Object> a = ((List<Map<String, Object>>) body.get("teams")).get(0);
        assertThat(a).containsEntry("team_name", "Takım A").containsEntry("total", 2).containsEntry("vulnerable", 2)
                .containsEntry("top_score", 95).containsEntry("next_migrate_by", LocalDate.now(java.time.ZoneId.of("Europe/Istanbul")).toString());
        assertThat((Map<String, Object>) a.get("by_category")).containsEntry("BROKEN", 1).containsEntry("MODERN", 1);
        assertThat((Map<String, Object>) a.get("by_band")).containsEntry("P1", 1).containsEntry("P4", 1);
        Map<String, Object> un = (Map<String, Object>) body.get("unowned");
        assertThat(un).containsEntry("total", 1).containsEntry("remnants", 1);
    }

    @Test
    @DisplayName("satır başına sorgu yok: 2 manuel kayıt → sürüm deposuna TEK çağrı; zayıf algoritma istisnası satıra işlenir")
    void onePassQueries() {
        CertificateInventory m1 = inv(20, "m1", 1L, 2); m1.setCertSource(CertificateInventory.SOURCE_MANUAL);
        CertificateInventory m2 = inv(21, "m2", 1L, 2); m2.setCertSource(CertificateInventory.SOURCE_MANUAL);
        when(inventoryRepo.findByActiveTrueOrderByDomainAsc()).thenReturn(List.of(m1, m2, inv(22, "n.example.com", 1L, 1)));
        when(versionRepo.findByInventoryIdInAndCurrentTrue(anyCollection())).thenReturn(List.of());
        when(latestCheckRepo.findAll()).thenReturn(List.of(lc("n.example.com", "RSA", 2048, "SHA1withRSA", 30, "[]")));
        WeakAlgorithmException ex = new WeakAlgorithmException();
        ex.setDomain("n.example.com"); ex.setUntil(LocalDate.now().plusDays(30).toString());
        when(exceptionRepo.findAll()).thenReturn(List.of(ex));
        Map<String, Object> body = svc.buildUncached(null, Instant.now());
        verify(versionRepo, times(1)).findByInventoryIdInAndCurrentTrue(anyCollection());
        verify(latestCheckRepo, times(1)).findAll();
        verify(teamRepo, times(1)).findAll();
        assertThat((Map<String, Object>) rowOf(body, "n.example.com").get("exception")).containsEntry("expired", false);
    }

    @Test
    @DisplayName("bellek: aynı kapsam ikinci çağrıda depoya gitmez; kapsamlar ayrı anahtar; fresh en fazla 5 sn'de bir")
    void memo() {
        svc.cacheMs = 60_000;
        when(inventoryRepo.findByActiveTrueOrderByDomainAsc()).thenReturn(List.of());
        svc.build(null);
        svc.build(null);
        verify(inventoryRepo, times(1)).findByActiveTrueOrderByDomainAsc();
        svc.build(List.of(1L));
        verify(inventoryRepo, times(2)).findByActiveTrueOrderByDomainAsc();
        svc.build(null, true);   // girdi < 5 sn önce hesaplandı → bellekten
        verify(inventoryRepo, times(2)).findByActiveTrueOrderByDomainAsc();
        // üst düzey kopya: çağıranın değişikliği paylaşılan belleğe yansımaz
        Map<String, Object> first = svc.build(null);
        first.put("rows", List.of(Map.of("x", 1)));
        assertThat(rows(svc.build(null))).isEmpty();
    }

    @Test
    @DisplayName("özet (yönetici raporu için): kategori/PQC/bant sayıları + ilk N öncelikli uç nokta, DONE hariç")
    void summaryForReuse() {
        when(inventoryRepo.findByActiveTrueOrderByDomainAsc()).thenReturn(List.of(
                inv(1, "a.example.com", 1L, 1), inv(2, "b.example.com", 1L, 2), inv(3, "pqc.example.com", 1L, 1)));
        when(latestCheckRepo.findAll()).thenReturn(List.of(
                lc("a.example.com", "RSA", 2048, "SHA256withRSA", 20, "[]"),
                lc("b.example.com", "EC", 256, "SHA256withECDSA", 400, "[]"),
                lc("pqc.example.com", "ML-DSA-65", null, "ML-DSA-65", 10, "[]")));
        Map<String, Object> s = svc.summary(null, 5);
        assertThat(s).containsKeys("total", "by_category", "by_pqc", "by_band", "remnants", "top", "generated_at", "data_as_of");
        List<Map<String, Object>> top = (List<Map<String, Object>>) s.get("top");
        assertThat(top.stream().map(t -> t.get("domain")).toList()).containsExactly("a.example.com", "b.example.com");
        assertThat(top.get(0)).containsKeys("rank", "score", "band", "category", "team_name");
        assertThat((Map<String, Object>) s.get("by_pqc")).containsEntry("PQC", 1);
        assertThat(svc.summary(null, 1).get("top")).asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.LIST).hasSize(1);
    }

    @Test
    @DisplayName("aynı alan adının ikinci envanter kaydı tekrar sayılmaz (Zayıf Algoritma raporuyla aynı tekilleştirme)")
    void dedupeByDomain() {
        when(inventoryRepo.findByActiveTrueOrderByDomainAsc()).thenReturn(List.of(
                inv(1, "dup.example.com", 1L, 1), inv(2, "dup.example.com", 2L, 4)));
        when(latestCheckRepo.findAll()).thenReturn(List.of(lc("dup.example.com", "RSA", 3072, "SHA256withRSA", 100, "[]")));
        Map<String, Object> body = svc.buildUncached(null, Instant.now());
        assertThat(rows(body)).singleElement().satisfies(r -> assertThat(r).containsEntry("team_name", "Takım A"));
        verify(versionRepo, never()).findByInventoryIdInAndCurrentTrue(any());
    }
}
