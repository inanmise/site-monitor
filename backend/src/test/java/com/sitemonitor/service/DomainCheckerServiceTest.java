package com.sitemonitor.service;

import com.sitemonitor.repository.DomainCheckRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
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
    @Mock ActivityLogService activityLog;

    DomainCheckerService svc;

    /** DNSBL varsayilanlarini donen hafif AppSettings mock'u (bu testlerde hic sorgulanmaz). */
    private static AppSettingsService appSettingsForDnsbl() {
        AppSettingsService a = org.mockito.Mockito.mock(AppSettingsService.class);
        org.mockito.Mockito.lenient().when(a.getString(anyString(), any()))
                .thenAnswer(i -> i.getArgument(1));
        org.mockito.Mockito.lenient().when(a.getInt(anyString(), org.mockito.ArgumentMatchers.anyInt()))
                .thenAnswer(i -> i.getArgument(1));
        return a;
    }

    @BeforeEach
    void setup() {
        PublicSuffixService psl = new PublicSuffixService();
        psl.load();
        // Kara liste izlemesi monitor bazli opt-in; bu testlerin hicbiri acmiyor -> sorgu kosmaz.
        DnsblCheckerService dnsbl = new DnsblCheckerService(dns, appSettingsForDnsbl());
        svc = new DomainCheckerService(psl, rdap, whois, dns, checkRepo, activityLog, dnsbl);
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

    // ── Transfer kilidi (K1) ─────────────────────────────────────────────────

    private static final String FUTURE = java.time.LocalDate.now().plusDays(400) + "T00:00:00Z";

    @Test
    @DisplayName("Kilit DÖRT durumlu: registry ve registrar kilidi AYRI raporlanır")
    void transferLockStates() {
        lenient().when(whois.anySourceEnabled()).thenReturn(false);

        when(rdap.lookup(eq("example.com"), any())).thenReturn(rdapOk(FUTURE, List.of("clientTransferProhibited")));
        assertThat(svc.test("example.com", 30, 7).get("transfer_lock")).isEqualTo("CLIENT");

        when(rdap.lookup(eq("example.net"), any())).thenReturn(rdapOk(FUTURE, List.of("serverTransferProhibited")));
        assertThat(svc.test("example.net", 30, 7).get("transfer_lock")).isEqualTo("SERVER");

        when(rdap.lookup(eq("example.org"), any()))
                .thenReturn(rdapOk(FUTURE, List.of("clientTransferProhibited", "serverTransferProhibited")));
        assertThat(svc.test("example.org", 30, 7).get("transfer_lock")).isEqualTo("BOTH");

        when(rdap.lookup(eq("test.com"), any())).thenReturn(rdapOk(FUTURE, List.of("ok")));
        assertThat(svc.test("test.com", 30, 7).get("transfer_lock")).isEqualTo("NONE");
    }

    /**
     * UNKNOWN ≠ NONE. EPP statü listesi yalnız RDAP'ta standarttır; WHOIS/.tr yollarında kilit
     * biçimi TLD'ye göre değişir. "Kilit yok" diye okumak, .tr envanterinin TAMAMINI sahte
     * alarma boğardı.
     */
    @Test
    @DisplayName("WHOIS kaynağında kilit DOĞRULANAMAZ — 'yok' DENMEZ, alarm üretilmez")
    void whoisSourceYieldsUnknownLock() {
        when(rdap.lookup(anyString(), any())).thenReturn(Map.of("source", "NONE", "error", "no rdap"));
        when(whois.anySourceEnabled()).thenReturn(true);
        Map<String, Object> w = new HashMap<>(rdapOk(FUTURE, List.of()));
        w.put("source", "WHOIS");
        when(whois.lookup(anyString())).thenReturn(w);

        Map<String, Object> r = svc.test("example.com.tr", 30, 7);

        assertThat(r.get("transfer_lock")).isEqualTo("UNKNOWN");
        assertThat(r.get("no_transfer_lock")).isEqualTo(false);
    }

    /**
     * K1 AYRIŞMA KAPISI. Kilit yokluğu eskiden {@code epp_warn}'a OR'lanıyordu: "autoRenewPeriod"
     * ile "transfer kilidi yok" aynı alarma düşüyor ve ayırt edilemiyordu. Artık STATUS yalnız
     * EPP kodlarına bakar.
     */
    @Test
    @DisplayName("Kilit yokluğu artık epp_warn'a KARIŞMAZ (STATUS alarmı yalnız EPP kodları)")
    void missingLockNoLongerPollutesEppWarn() {
        lenient().when(whois.anySourceEnabled()).thenReturn(false);
        when(rdap.lookup(eq("test.com"), any())).thenReturn(rdapOk(FUTURE, List.of("ok")));

        Map<String, Object> r = svc.test("test.com", 30, 7);

        assertThat(r.get("no_transfer_lock")).isEqualTo(true);
        assertThat(r.get("epp_warn")).as("kilit yokluğu EPP uyarısı DEĞİLDİR").isEqualTo(false);
        // Kart durumu yine WARNING: sorun görünür kalmalı, yalnız alarm ailesi ayrıştı.
        assertThat(r.get("status")).isEqualTo("WARNING");
    }

    @Test
    @DisplayName("Kilit alarmı KAPALIYSA kart durumunu da etkilemez ('kapalı' gerçekten kapalı)")
    void lockAlertOffLeavesStatusClean() {
        lenient().when(whois.anySourceEnabled()).thenReturn(false);
        when(rdap.lookup(eq("test.com"), any())).thenReturn(rdapOk(FUTURE, List.of("ok")));
        when(checkRepo.findTopByMonitorIdAndSourceNotOrderByCheckedAtDesc(anyLong(), anyString()))
                .thenReturn(java.util.Optional.empty());

        com.sitemonitor.model.DomainMonitor m = new com.sitemonitor.model.DomainMonitor();
        m.setId(1L); m.setDomain("test.com");
        m.setTransferLockAlert(false);
        m.setBlacklistEnabled(false);

        Map<String, Object> r = svc.check(m);

        assertThat(r.get("no_transfer_lock")).isEqualTo(true);
        assertThat(r.get("status")).as("anahtar kapalıyken kilit kart rengini etkilemez").isEqualTo("OK");
    }

    // ── Kara liste opt-in (K2/K3) ────────────────────────────────────────────

    @Test
    @DisplayName("Kara liste KAPALIYKEN hiç sorgulanmaz — durum SKIPPED")
    void blacklistDisabledIsSkipped() {
        lenient().when(whois.anySourceEnabled()).thenReturn(false);
        when(rdap.lookup(eq("example.org"), any())).thenReturn(rdapOk(FUTURE, List.of("clientTransferProhibited")));
        when(checkRepo.findTopByMonitorIdAndSourceNotOrderByCheckedAtDesc(anyLong(), anyString()))
                .thenReturn(java.util.Optional.empty());

        com.sitemonitor.model.DomainMonitor m = new com.sitemonitor.model.DomainMonitor();
        m.setId(2L); m.setDomain("example.org");
        m.setBlacklistEnabled(false);

        assertThat(svc.check(m).get("blacklist_status")).isEqualTo(DnsblCheckerService.SKIPPED);
    }

    // ── Değişiklik tespiti: DNSSEC (K4) ──────────────────────────────────────

    /** DNSSEC geçişi (imzalıdan imzasıza) ciddi bir ele geçirme sinyali ve buraya HİÇ bakılmıyordu. */
    @Test
    @DisplayName("DNSSEC değişimi DEĞİŞİKLİK sayılır ve detayda yazar")
    void dnssecChangeIsDetected() {
        lenient().when(whois.anySourceEnabled()).thenReturn(false);
        Map<String, Object> now = new HashMap<>(rdapOk(FUTURE, List.of("clientTransferProhibited")));
        now.put("dnssec", "unsigned");
        when(rdap.lookup(eq("example.net"), any())).thenReturn(now);

        com.sitemonitor.model.DomainCheck prev = new com.sitemonitor.model.DomainCheck();
        prev.setRegistrar("Test Registrar");
        prev.setNameservers("ns1.example.com");
        prev.setStatusCodes("clientTransferProhibited");
        prev.setDnssec("signed");
        when(checkRepo.findTopByMonitorIdAndSourceNotOrderByCheckedAtDesc(anyLong(), anyString()))
                .thenReturn(java.util.Optional.of(prev));

        com.sitemonitor.model.DomainMonitor m = new com.sitemonitor.model.DomainMonitor();
        m.setId(3L); m.setDomain("example.net");

        Map<String, Object> r = svc.check(m);

        assertThat(r.get("changed")).isEqualTo(true);
        assertThat(String.valueOf(r.get("change_detail"))).contains("DNSSEC");
    }

    @Test
    @DisplayName("2026-08 regresyon kilidi: RDAP yok + socket-WHOIS KAPALI + .tr web-whois AÇIK → lookup ÇAĞRILIR, veri gelir")
    void trWebWhois_runsInScheduledFlow_whenSocketWhoisDisabled() {
        // .tr'nin RDAP'ı yoktur; DOMAIN_WHOIS_ENABLED=false (prod chart kararı) iken gate yalnız
        // enabled()'a bakarsa .tr web-whois hiç denenmez ve domain UNKNOWN kalır (prod'da yaşandı).
        when(rdap.lookup(eq("wingscard.com.tr"), any()))
                .thenReturn(Map.of("source", "NONE", "error", "no rdap for .tr"));
        when(whois.anySourceEnabled()).thenReturn(true);   // socket=false ama tr-web=true
        Map<String, Object> w = new HashMap<>();
        w.put("source", "WHOIS");
        w.put("expiry_date", LocalDate.now().plusDays(1175).toString());
        w.put("registrar", "İHS Kurumsal");
        when(whois.lookup("wingscard.com.tr")).thenReturn(w);

        Map<String, Object> r = svc.test("wingscard.com.tr", 30, 7);

        assertThat(r.get("status")).isEqualTo("OK");
        assertThat(r.get("source")).isEqualTo("WHOIS");
        org.mockito.Mockito.verify(whois).lookup("wingscard.com.tr");
    }

    @Test
    @DisplayName("tüm WHOIS kaynakları kapalıyken lookup HİÇ çağrılmaz → UNKNOWN")
    void allWhoisSourcesDisabled_lookupNeverCalled() {
        when(rdap.lookup(eq("example.com"), any()))
                .thenReturn(Map.of("source", "NONE", "error", "rdap fail"));
        lenient().when(whois.anySourceEnabled()).thenReturn(false);

        Map<String, Object> r = svc.test("example.com", 30, 7);

        assertThat(r.get("status")).isEqualTo("UNKNOWN");
        org.mockito.Mockito.verify(whois, org.mockito.Mockito.never()).lookup(anyString());
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

    // ── Tam-eşik kenarları (days <= criticalDays → CRITICAL; days <= warningDays → WARNING) ──────────
    // Date-only string'ler gün sınırında off-by-one keser; kesin eşik için +1saat tamponlu ISO instant.

    /** Tam N gün sonra biten expiry (ChronoUnit.DAYS.between kesin N verir; test <1sa sürdüğü için tampon güvenli). */
    private static String expiryInDays(int days) {
        return DateTimeFormatter.ISO_INSTANT.format(Instant.now().plus(days, ChronoUnit.DAYS).plus(1, ChronoUnit.HOURS));
    }

    @Test
    @DisplayName("gün == kritik eşik → CRITICAL (days<=criticalDays)")
    void test_daysEqualsCritical_isCritical() {
        when(rdap.lookup(eq("example.com"), any())).thenReturn(rdapOk(expiryInDays(7), List.of("client transfer prohibited")));
        Map<String, Object> r = svc.test("example.com", 30, 7);
        assertThat(r.get("status")).isEqualTo("CRITICAL");
        assertThat(r.get("days_remaining")).isEqualTo(7);
    }

    @Test
    @DisplayName("gün == kritik eşik + 1 → WARNING (kritik değil ama uyarı içinde)")
    void test_daysEqualsCriticalPlusOne_isWarning() {
        when(rdap.lookup(eq("example.com"), any())).thenReturn(rdapOk(expiryInDays(8), List.of("client transfer prohibited")));
        Map<String, Object> r = svc.test("example.com", 30, 7);
        assertThat(r.get("status")).isEqualTo("WARNING");
        assertThat(r.get("days_remaining")).isEqualTo(8);
    }

    @Test
    @DisplayName("gün == uyarı eşik → WARNING (days<=warningDays)")
    void test_daysEqualsWarning_isWarning() {
        when(rdap.lookup(eq("example.com"), any())).thenReturn(rdapOk(expiryInDays(30), List.of("client transfer prohibited")));
        Map<String, Object> r = svc.test("example.com", 30, 7);
        assertThat(r.get("status")).isEqualTo("WARNING");
        assertThat(r.get("days_remaining")).isEqualTo(30);
    }

    @Test
    @DisplayName("gün == uyarı eşik + 1 → OK (transfer kilidi varken)")
    void test_daysEqualsWarningPlusOne_isOk() {
        when(rdap.lookup(eq("example.com"), any())).thenReturn(rdapOk(expiryInDays(31), List.of("client transfer prohibited")));
        Map<String, Object> r = svc.test("example.com", 30, 7);
        assertThat(r.get("status")).isEqualTo("OK");
        assertThat(r.get("days_remaining")).isEqualTo(31);
    }

    @Test
    @DisplayName("zaten süresi geçmiş (days<0) → CRITICAL")
    void test_alreadyExpired_negativeDays_isCritical() {
        when(rdap.lookup(eq("example.com"), any())).thenReturn(rdapOk(
                DateTimeFormatter.ISO_INSTANT.format(Instant.now().minus(10, ChronoUnit.DAYS)),
                List.of("client transfer prohibited")));
        Map<String, Object> r = svc.test("example.com", 30, 7);
        assertThat(r.get("status")).isEqualTo("CRITICAL");
        assertThat((Integer) r.get("days_remaining")).isNegative();
    }
}
