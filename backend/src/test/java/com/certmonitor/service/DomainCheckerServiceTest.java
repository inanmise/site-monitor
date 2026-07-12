package com.certmonitor.service;

import com.certmonitor.repository.DomainCheckRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/** Orkestratör 4-seviyeli durum mantığı (RDAP/WHOIS/DNS mock'lu). */
@ExtendWith(MockitoExtension.class)
class DomainCheckerServiceTest {

    @Mock RdapDomainClient rdap;
    @Mock WhoisDomainClient whois;
    @Mock DnsCheckerService dns;
    @Mock DomainCheckRepository checkRepo;

    DomainCheckerService svc;

    @BeforeEach
    void setup() {
        PublicSuffixService psl = new PublicSuffixService();
        psl.load();
        svc = new DomainCheckerService(psl, rdap, whois, dns, checkRepo);
        lenient().when(whois.enabled()).thenReturn(false);
        lenient().when(dns.check(anyString(), eq("NS"))).thenReturn(Map.of("success", true));
        // A/AAAA çözümü (Domain Kaydı): varsayılan boş → çoğu test reverse-DNS PTR beklemesine takılmasın (hız).
        lenient().when(dns.check(anyString(), eq("A"))).thenReturn(Map.of("values", List.of()));
        lenient().when(dns.check(anyString(), eq("AAAA"))).thenReturn(Map.of("values", List.of()));
    }

    private Map<String, Object> rdapOk(String expiry, List<String> status) {
        Map<String, Object> m = new HashMap<>();
        m.put("source", "RDAP");
        m.put("expiry_date", expiry);
        m.put("registrar", "Test Registrar");
        m.put("status_codes", status);
        m.put("nameservers", List.of("ns1.example.com"));
        return m;
    }

    @Test
    @DisplayName("uzak bitiş + transfer kilidi → OK")
    void ok() {
        when(rdap.lookup(eq("example.com"), any())).thenReturn(rdapOk(LocalDate.now().plusDays(200).toString(), List.of("client transfer prohibited")));
        assertThat(svc.test("example.com", 30, 7).get("status")).isEqualTo("OK");
    }

    @Test
    @DisplayName("kritik eşiğe yakın bitiş → CRITICAL")
    void critical() {
        when(rdap.lookup(eq("example.com"), any())).thenReturn(rdapOk(LocalDate.now().plusDays(3).toString(), List.of("client transfer prohibited")));
        assertThat(svc.test("example.com", 30, 7).get("status")).isEqualTo("CRITICAL");
    }

    @Test
    @DisplayName("uyarı eşiği içinde → WARNING")
    void warning() {
        when(rdap.lookup(eq("example.com"), any())).thenReturn(rdapOk(LocalDate.now().plusDays(20).toString(), List.of("client transfer prohibited")));
        assertThat(svc.test("example.com", 30, 7).get("status")).isEqualTo("WARNING");
    }

    @Test
    @DisplayName("redemptionPeriod EPP → anında CRITICAL (bitiş uzak olsa da)")
    void redemptionPeriod() {
        when(rdap.lookup(eq("example.com"), any())).thenReturn(rdapOk(LocalDate.now().plusDays(200).toString(), List.of("redemption period")));
        Map<String, Object> r = svc.test("example.com", 30, 7);
        assertThat(r.get("status")).isEqualTo("CRITICAL");
        assertThat(r.get("epp_critical")).isEqualTo(true);
    }

    @Test
    @DisplayName("registration alanları out'a akar: registrar_iana_id, dnssec, resolved_ips")
    @SuppressWarnings("unchecked")
    void registrationFieldsFlowThrough() {
        Map<String, Object> info = rdapOk(LocalDate.now().plusDays(200).toString(), List.of("client transfer prohibited"));
        info.put("registrar_iana_id", "292");
        info.put("dnssec", "signed");
        when(rdap.lookup(eq("example.com"), any())).thenReturn(info);
        when(dns.check(anyString(), eq("A"))).thenReturn(Map.of("values", List.of("192.0.2.1")));   // TEST-NET-1 (RFC5737)
        Map<String, Object> r = svc.test("example.com", 30, 7);
        assertThat(r.get("registrar_iana_id")).isEqualTo("292");
        assertThat(r.get("dnssec")).isEqualTo("signed");
        assertThat((List<String>) r.get("resolved_ips")).contains("192.0.2.1");
    }

    @Test
    @DisplayName("veri yok (source NONE) → UNKNOWN")
    void unknownNoData() {
        Map<String, Object> none = new HashMap<>();
        none.put("source", "NONE");
        none.put("error", "rdap http 404");
        when(rdap.lookup(eq("example.com"), any())).thenReturn(none);
        assertThat(svc.test("example.com", 30, 7).get("status")).isEqualTo("UNKNOWN");
    }

    @Test
    @DisplayName("tarih ayrıştırılamıyor → UNKNOWN (veri yok ≠ sorun yok)")
    void unknownUnparseableDate() {
        when(rdap.lookup(eq("example.com"), any())).thenReturn(rdapOk("not-a-date", List.of()));
        assertThat(svc.test("example.com", 30, 7).get("status")).isEqualTo("UNKNOWN");
    }

    @Test
    @DisplayName("daysUntil parse edilemeyeni null verir")
    void daysUntil() {
        assertThat(DomainCheckerService.daysUntil("not-a-date")).isNull();
        assertThat(DomainCheckerService.daysUntil(null)).isNull();
    }
}
