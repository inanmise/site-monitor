package com.sitemonitor.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link DnsCheckerService}.
 *
 * The service does live DNS lookups via JNDI. Test environments behave wildly
 * differently here: some CI runners have ISP wildcard DNS that "resolves"
 * anything to a captive portal, .invalid is sometimes hijacked, etc. So we
 * assert against the *contract* the rest of the app relies on -- the shape
 * of the returned map -- without making promises about the actual live
 * resolution result.
 */
class DnsCheckerServiceTest {

    private final AppSettingsService appSettings = mock(AppSettingsService.class);
    private final DnsCheckerService service = new DnsCheckerService(appSettings);

    @BeforeEach
    void stubSettings() {
        // getInt(key, fallback) → fallback (ör. query-timeout-ms = 2000)
        when(appSettings.getInt(anyString(), anyInt())).thenAnswer(inv -> inv.getArgument(1));
    }

    @Test
    @DisplayName("check returns a result map with success/values keys")
    void check_returnsExpectedShape() {
        Map<String, Object> r = service.check("example.com", "A");
        assertThat(r).containsKeys("success", "values");
        assertThat(r.get("values")).isInstanceOf(List.class);
    }

    @Test
    @DisplayName("toHostname: URL şema/userinfo/path/port/trailing-dot ayıklanır → çıplak host (sahte NXDOMAIN fix)")
    void toHostname_stripsUrlParts() {
        assertThat(DnsCheckerService.toHostname("https://www.example.com/basvuru/Juzdan/")).isEqualTo("www.example.com");
        assertThat(DnsCheckerService.toHostname("http://example.com:8443/path?q=1")).isEqualTo("example.com");
        assertThat(DnsCheckerService.toHostname("user@host.example.com/x")).isEqualTo("host.example.com");
        assertThat(DnsCheckerService.toHostname("WWW.Example.COM.")).isEqualTo("www.example.com");
    }

    @Test
    @DisplayName("toHostname: zaten çıplak host değişmez; port ayıklanır; null/boş güvenli")
    void toHostname_bareHostAndEdgeCases() {
        assertThat(DnsCheckerService.toHostname("www.example.com")).isEqualTo("www.example.com");
        assertThat(DnsCheckerService.toHostname("example.com:53")).isEqualTo("example.com");
        assertThat(DnsCheckerService.toHostname(null)).isNull();
        assertThat(DnsCheckerService.toHostname("   ")).isEqualTo("");
    }

    @Test
    @DisplayName("success=false branch always carries an error key")
    void check_failureCarriesErrorMessage() {
        // Try several inputs that should fail in most environments. Even if one of
        // them happens to resolve, at least one is virtually guaranteed to fail.
        Map<String, Object> r1 = service.check("", "A");
        Map<String, Object> r2 = service.check("totally-bogus-host-site-monitor-test.zzz", "AAAA");
        Map<String, Object> r3 = service.check("example.com", "FAKE-RECORD-TYPE");
        boolean anyFailed = false;
        for (Map<String, Object> r : new Map[]{ r1, r2, r3 }) {
            if (Boolean.FALSE.equals(r.get("success"))) {
                assertThat(r).containsKey("error");
                assertThat((List<?>) r.get("values")).isEmpty();
                anyFailed = true;
            }
        }
        assertThat(anyFailed)
            .as("at least one of the bogus inputs should fail; if all succeed your DNS resolver is hijacked")
            .isTrue();
    }

    @Test
    @DisplayName("values list is never null, regardless of outcome")
    void check_valuesListNeverNull() {
        assertThat(service.check("example.com", "A").get("values")).isNotNull();
        assertThat(service.check("",            "A").get("values")).isNotNull();
        assertThat(service.check("a.b.c.d.e.f", "AAAA").get("values")).isNotNull();
    }

    @Test
    @DisplayName("check does not throw on edge-case input")
    void check_doesNotThrowOnEdgeInput() {
        // Each of these must come back as a result map, not a thrown exception.
        service.check(null, "A");
        service.check("example.com", null);
        service.check("\t\t  \n", "A");
    }

    @Test
    @DisplayName("check captures response_ms regardless of success")
    void check_responseMsIsCaptured() {
        Map<String, Object> ok = service.check("example.com", "A");
        Map<String, Object> bad = service.check("totally-bogus-host-site-monitor-test.zzz", "A");
        assertThat(ok).containsKey("response_ms");
        assertThat(bad).containsKey("response_ms");
        assertThat(ok.get("response_ms")).isInstanceOf(Long.class);
        assertThat((Long) ok.get("response_ms")).isGreaterThanOrEqualTo(0L);
    }

    @Test
    @DisplayName("check carries ttl key (Long or null)")
    void check_ttlKeyAlwaysPresent() {
        Map<String, Object> r = service.check("example.com", "A");
        assertThat(r).containsKey("ttl");
        Object ttl = r.get("ttl");
        // ttl is either a Long (success path) or null (failure path) — never a String/Integer
        assertThat(ttl == null || ttl instanceof Long).isTrue();
    }

    @Test
    @DisplayName("enrichedQuery returns all standard record types and soa key")
    void enrichedQuery_shape() {
        Map<String, Object> data = service.enrichedQuery("example.com");
        assertThat(data).containsKeys("records", "soa", "authoritative_servers");
        @SuppressWarnings("unchecked")
        Map<String, Object> records = (Map<String, Object>) data.get("records");
        assertThat(records).containsKeys("A", "AAAA", "CNAME", "MX", "TXT", "NS");
    }

    // ── detectChange — round-robin vs real change ────────────────────────────

    @Test
    @DisplayName("detectChange: prev == null is NONE (first ever check)")
    void detectChange_firstCheck_isNone() {
        assertThat(DnsCheckerService.detectChange(null, "1.2.3.4"))
            .isEqualTo(DnsCheckerService.ChangeKind.NONE);
    }

    @Test
    @DisplayName("detectChange: identical strings is NONE")
    void detectChange_identical_isNone() {
        assertThat(DnsCheckerService.detectChange("1.2.3.4\n5.6.7.8", "1.2.3.4\n5.6.7.8"))
            .isEqualTo(DnsCheckerService.ChangeKind.NONE);
    }

    @Test
    @DisplayName("detectChange: same set in different order is NONE")
    void detectChange_sameSetReordered_isNone() {
        assertThat(DnsCheckerService.detectChange("1.2.3.4\n5.6.7.8", "5.6.7.8\n1.2.3.4"))
            .isEqualTo(DnsCheckerService.ChangeKind.NONE);
    }

    @Test
    @DisplayName("detectChange: subset overlap is ROTATED (CDN edge rotation)")
    void detectChange_subsetOverlap_isRotated() {
        // Example example: prev had two edge IPs, now returns one of them
        assertThat(DnsCheckerService.detectChange(
                "217.169.192.73\n217.169.204.113",
                "217.169.204.113"))
            .isEqualTo(DnsCheckerService.ChangeKind.ROTATED);
    }

    /**
     * Gecici cozumleme hatasi SAHTE "DEGISTI" uretmemeli (kullanici bildirimi).
     *
     * <p>Gercek vaka: kayit saatlerdir ayni IP'ye cozumleniyordu, bir tur basarisiz oldu (deger ""
     * yazildi), bir sonraki basarili tur AYNI IP'yi dondurdu -- ve satir "DEGISTI!" damgasi yedi.
     * Bos kume ile dolu kume AYRIK gorunuyor, ayriklik da CHANGED demek. Tersi de olurdu: dolu ->
     * bos gecisi de CHANGED sayiliyordu, yani tek bir gecici hata IKI sahte degisiklik uretiyordu.
     *
     * <p>Cagiranlar zaten yalnizca basarili turlari karsilastirmali; bu dal o kuralin
     * SURUKLENMESINE karsi son savunma. Gercek bir degisikligi maskeleyemez -- gercek degisiklik
     * iki DOLU kume gerektirir.
     */
    @Test
    @DisplayName("detectChange: bos -> dolu (cozumleme kurtuldu) NONE, sahte CHANGED degil")
    void detectChange_emptyToValue_isNone() {
        assertThat(DnsCheckerService.detectChange("", "217.169.192.122"))
            .isEqualTo(DnsCheckerService.ChangeKind.NONE);
    }

    @Test
    @DisplayName("detectChange: dolu -> bos (cozumleme dustu) NONE -- bu DNS_FAILURE'in isi")
    void detectChange_valueToEmpty_isNone() {
        assertThat(DnsCheckerService.detectChange("217.169.192.122", ""))
            .isEqualTo(DnsCheckerService.ChangeKind.NONE);
    }

    @Test
    @DisplayName("detectChange: bos taraf ELENIR ama gercek degisiklik hala CHANGED")
    void detectChange_realChangeStillDetected() {
        // Bos-taraf dali gercek degisikligi maskelemiyor: iki taraf da dolu.
        assertThat(DnsCheckerService.detectChange("1.2.3.4", "9.9.9.9"))
            .isEqualTo(DnsCheckerService.ChangeKind.CHANGED);
    }

    @Test
    @DisplayName("detectChange: new IP added with one shared is ROTATED")
    void detectChange_partialOverlap_isRotated() {
        assertThat(DnsCheckerService.detectChange(
                "1.2.3.4",
                "1.2.3.4\n9.9.9.9"))
            .isEqualTo(DnsCheckerService.ChangeKind.ROTATED);
    }

    @Test
    @DisplayName("unexpectedValues: beklenmeyen değer raporlanır; rotasyon (alt küme) tolere edilir")
    void unexpectedValues_flexible() {
        // beklenmeyen yeni değer → raporla
        assertThat(DnsCheckerService.unexpectedValues("1.2.3.4\n5.6.7.8", List.of("9.9.9.9")))
            .containsExactly("9.9.9.9");
        // rotasyon: canlı = beklenenin alt kümesi → sapma yok
        assertThat(DnsCheckerService.unexpectedValues("1.2.3.4\n5.6.7.8", List.of("1.2.3.4")))
            .isEmpty();
        // beklenen boş/null = kilit kapalı → boş
        assertThat(DnsCheckerService.unexpectedValues("", List.of("9.9.9.9"))).isEmpty();
        assertThat(DnsCheckerService.unexpectedValues(null, List.of("9.9.9.9"))).isEmpty();
        // karışık: bir beklenen + bir beklenmeyen → yalnız beklenmeyeni raporla
        assertThat(DnsCheckerService.unexpectedValues("1.2.3.4", List.of("1.2.3.4", "9.9.9.9")))
            .containsExactly("9.9.9.9");
    }

    @Test
    @DisplayName("detectChange: disjoint sets is CHANGED (real change)")
    void detectChange_disjoint_isChanged() {
        assertThat(DnsCheckerService.detectChange("1.2.3.4", "9.9.9.9"))
            .isEqualTo(DnsCheckerService.ChangeKind.CHANGED);
        assertThat(DnsCheckerService.detectChange(
                "217.169.192.73\n217.169.204.113",
                "5.5.5.5\n6.6.6.6"))
            .isEqualTo(DnsCheckerService.ChangeKind.CHANGED);
    }

    // ── withinExpected — beklenen-set flip bastırma (DNS_CHANGED suppress) ───

    @Test
    @DisplayName("withinExpected: beklenen boş/null → false (kilit kapalı, alarm davranışı değişmez)")
    void withinExpected_emptyExpected_isFalse() {
        assertThat(DnsCheckerService.withinExpected(null, List.of("1.2.3.4"))).isFalse();
        assertThat(DnsCheckerService.withinExpected("", List.of("1.2.3.4"))).isFalse();
        assertThat(DnsCheckerService.withinExpected("   \n  ", List.of("1.2.3.4"))).isFalse();
    }

    @Test
    @DisplayName("withinExpected: canlı değerlerin TAMAMI beklenen settteyse true (iç/dış IP flip'i)")
    void withinExpected_allInSet_isTrue() {
        String expected = "192.168.1.10\n217.169.196.197";
        assertThat(DnsCheckerService.withinExpected(expected, List.of("192.168.1.10"))).isTrue();
        assertThat(DnsCheckerService.withinExpected(expected, List.of("217.169.196.197"))).isTrue();
        assertThat(DnsCheckerService.withinExpected(expected,
                List.of("192.168.1.10", "217.169.196.197"))).isTrue();
    }

    @Test
    @DisplayName("withinExpected: set dışında TEK değer bile varsa false (alarm devam)")
    void withinExpected_anyOutsider_isFalse() {
        String expected = "192.168.1.10\n217.169.196.197";
        assertThat(DnsCheckerService.withinExpected(expected, List.of("9.9.9.9"))).isFalse();
        assertThat(DnsCheckerService.withinExpected(expected,
                List.of("192.168.1.10", "9.9.9.9"))).isFalse();
    }

    @Test
    @DisplayName("withinExpected: canlı boş/null → false (boş sonuç bilinen-iyi sayılmaz)")
    void withinExpected_emptyLive_isFalse() {
        assertThat(DnsCheckerService.withinExpected("1.2.3.4", List.of())).isFalse();
        assertThat(DnsCheckerService.withinExpected("1.2.3.4", null)).isFalse();
        assertThat(DnsCheckerService.withinExpected("1.2.3.4", List.of(""))).isFalse();
    }

    @Test
    @DisplayName("withinExpected: satır trim davranışı splitLines ile aynı (boşluklu girdi eşleşir)")
    void withinExpected_trimsLines() {
        assertThat(DnsCheckerService.withinExpected("  1.2.3.4  \n\n 5.6.7.8 ",
                List.of("1.2.3.4", "5.6.7.8"))).isTrue();
    }

    @Test
    @DisplayName("changeCtxOf: null kayıt → boş map (çağıran generic mesaja düşer)")
    void changeCtxOf_null_isEmpty() {
        assertThat(DnsCheckerService.changeCtxOf(null)).isEmpty();
    }

    @Test
    @DisplayName("changeCtxOf: çok satırlı değerler old/new_values listelerine ayrışır + changed_at taşınır")
    void changeCtxOf_splitsValues() {
        com.sitemonitor.model.DnsRecord r = new com.sitemonitor.model.DnsRecord();
        r.setRecordType("A");
        r.setPreviousValue("1.2.3.4\n5.6.7.8");
        r.setValue("9.9.9.9");
        r.setCheckedAt("2026-08-02T01:32:00");
        Map<String, Object> ctx = DnsCheckerService.changeCtxOf(r);
        assertThat(ctx.get("record_type")).isEqualTo("A");
        assertThat(ctx.get("old_values")).isEqualTo(List.of("1.2.3.4", "5.6.7.8"));
        assertThat(ctx.get("new_values")).isEqualTo(List.of("9.9.9.9"));
        assertThat(ctx.get("changed_at")).isEqualTo("2026-08-02T01:32:00");
    }

    @Test
    @DisplayName("resolverConfigInfo: servers/source/timeout_ms anahtarları döner (şeffaflık payload'ı)")
    void resolverConfigInfo_shape() {
        Map<String, Object> info = service.resolverConfigInfo();
        assertThat(info).containsKeys("servers", "source", "timeout_ms");
        assertThat(info.get("servers")).isInstanceOf(List.class);
        assertThat(info.get("source")).isEqualTo("os");
        assertThat(info.get("timeout_ms")).isEqualTo(2000);
    }
}
