package com.sitemonitor.service.evalstate;

import com.sitemonitor.service.EscalationService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Kapı (2026-10-23, çok pod Sürüm 3-1, A1; tasarım R3-1 §2.7): {@code EscalationService.MONITORING_ALERT_TYPES}'ın HER
 * üyesi bir izleme türüne eşlenir — yeni bir izleme alarm türü eşlemesi eklenene dek derleme düşer ("{@code HTTP_SLOW}'un
 * geçtiği her yere ekle" kuralı). Eşleme {@code SchedulerService.isStillMonitored}'ın önek dağıtımının aynasıdır: oradaki
 * her {@code startsWith("…")} öneki burada da bulunur.
 */
class RecheckRefsCoverageTest {

    @Test
    @DisplayName("Her izleme alarm türü bilinen bir izleme türüne eşlenir")
    void everyMonitoringAlertTypeMaps() {
        List<String> unmapped = new ArrayList<>();
        for (String t : EscalationService.MONITORING_ALERT_TYPES) {
            String m = RecheckRefs.monitorType(t);
            if (m == null || !RecheckRefs.MONITOR_TYPES.contains(m)) unmapped.add(t);
        }
        assertThat(unmapped).as("RecheckRefs.monitorType'a eşleme ekleyin").isEmpty();
    }

    @Test
    @DisplayName("Eşleme tablosu (30 tür) — beklenen değerler")
    void mappingTable() {
        Map<String, String> expected = new TreeMap<>(Map.ofEntries(
                Map.entry("ACCESSIBILITY", "uptime"),
                Map.entry("PORT_DOWN", "port"), Map.entry("PORT_SLOW", "port"),
                Map.entry("DNS_FAILURE", "dns"), Map.entry("DNS_CHANGED", "dns"), Map.entry("DNS_SLOW", "dns"),
                Map.entry("DNS_UNEXPECTED", "dns"), Map.entry("DNS_INCONSISTENT", "dns"),
                Map.entry("KEYWORD", "keyword"), Map.entry("KEYWORD_SLOW", "keyword"), Map.entry("KEYWORD_SSL", "keyword"),
                Map.entry("KEYWORD_DOMAIN_EXPIRY", "keyword"),
                Map.entry("PING_DOWN", "ping"), Map.entry("PING_SLOW", "ping"),
                Map.entry("HTTP_DOWN", "http"), Map.entry("HTTP_SSL", "http"), Map.entry("HTTP_SLOW", "http"),
                Map.entry("DOMAIN_EXPIRY", "http"),
                Map.entry("DOMAINMON_EXPIRY", "domain"), Map.entry("DOMAINMON_UNKNOWN", "domain"),
                Map.entry("DOMAINMON_STATUS", "domain"), Map.entry("DOMAINMON_CHANGED", "domain"),
                Map.entry("DOMAINMON_TRANSFER_LOCK", "domain"), Map.entry("DOMAINMON_BLACKLIST", "domain"),
                Map.entry("PAGE_DOWN", "page"), Map.entry("PAGE_INTEGRITY", "page"),
                Map.entry("SCRIPTED_FAIL", "scripted"), Map.entry("SCRIPTED_SLOW", "scripted"),
                Map.entry("PAGESPEED_DOWN", "pagespeed"), Map.entry("PAGESPEED_SLOW", "pagespeed"),
                Map.entry("DBQUERY_FAIL", "dbquery"), Map.entry("DBQUERY_THRESHOLD", "dbquery"),
                Map.entry("DBQUERY_SLOW", "dbquery")));
        assertThat(expected.keySet()).containsExactlyInAnyOrderElementsOf(EscalationService.MONITORING_ALERT_TYPES);
        Map<String, String> actual = new TreeMap<>();
        for (String t : expected.keySet()) actual.put(t, RecheckRefs.monitorType(t));
        assertThat(actual).isEqualTo(expected);
    }

    @Test
    @DisplayName("İzleme dışı / bilinmeyen tür → null (sertifika alarmları, boş, null)")
    void unknownIsNull() {
        assertThat(RecheckRefs.monitorType(EscalationService.TYPE_HOSTNAME_MISMATCH)).isNull();
        assertThat(RecheckRefs.monitorType(EscalationService.TYPE_UNTRUSTED_CA)).isNull();
        assertThat(RecheckRefs.monitorType("")).isNull();
        assertThat(RecheckRefs.monitorType(null)).isNull();
        assertThat(RecheckRefs.monitorType("PAGESPEEDX")).isNull();
    }

    @Test
    @DisplayName("SchedulerService.isStillMonitored'ın her öneki RecheckRefs'te de var (iki dağıtım ayrışmasın)")
    void mirrorsIsStillMonitoredPrefixes() throws IOException {
        String scheduler = Files.readString(Path.of("src/main/java/com/sitemonitor/service/SchedulerService.java"),
                StandardCharsets.UTF_8);
        int at = scheduler.indexOf("boolean isStillMonitored(MonitoringOutageService.SweepItem item)");
        assertThat(at).as("isStillMonitored bulunamadı").isPositive();
        String body = scheduler.substring(at, scheduler.indexOf("\n    }", at));
        Matcher m = Pattern.compile("startsWith\\(\"([A-Z_]+)\"\\)").matcher(body);
        List<String> prefixes = new ArrayList<>();
        while (m.find()) prefixes.add(m.group(1));
        assertThat(prefixes).as("önekler okunamadı").hasSizeGreaterThanOrEqualTo(9);
        String refs = Files.readString(Path.of("src/main/java/com/sitemonitor/service/evalstate/RecheckRefs.java"),
                StandardCharsets.UTF_8);
        for (String p : prefixes) {
            assertThat(refs).as("RecheckRefs öneki eksik: %s", p).contains("startsWith(\"" + p + "\")");
        }
    }
}
