package com.sitemonitor.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.net.InetAddress;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * k6 koşumlarının kurumsal çıkış vekilini kullanması.
 *
 * <p>Saha vakası (2026-08): aynı pod sertifikayı vekil üzerinden çekebilirken k6 doğrudan
 * çıkıyordu; vekil zorunlu ağda TCP kabul edilip yutulduğu için 288 koşumun 288'i
 * {@code request timeout} ile düştü. Bu testler o yolun bir daha sessizce kopmamasını sağlar.
 */
class ScriptedProxyTest {

    private ProxySettings settings(String host, int port, String user, String pass, String noProxy) {
        ProxySettings p = new ProxySettings();
        ReflectionTestUtils.setField(p, "host", host);
        ReflectionTestUtils.setField(p, "port", port);
        ReflectionTestUtils.setField(p, "user", user);
        ReflectionTestUtils.setField(p, "pass", pass);
        ReflectionTestUtils.setField(p, "noProxy", noProxy);
        return p;
    }

    @Test
    @DisplayName("Vekil URL'i: kimliksiz ve kimlikli biçim")
    void proxyUrl() {
        assertThat(settings("proxy.akbank.com", 8080, "", "", "").proxyUrl())
                .isEqualTo("http://proxy.akbank.com:8080");
        assertThat(settings("proxy.akbank.com", 8080, "svc", "p@ss:1", "").proxyUrl())
                .isEqualTo("http://svc:p%40ss%3A1@proxy.akbank.com:8080");
    }

    @Test
    @DisplayName("Parola URL-encode edilir — `@`/`:` encode edilmezse URL yanlış host'a işaret eder")
    void passwordIsEncoded() {
        assertThat(settings("p.x", 3128, "u", "a@b", "").proxyUrl()).contains("a%40b").doesNotContain("a@b:");
    }

    @Test
    @DisplayName("Host boş / port 0 → vekil devre dışı, URL yok")
    void disabledWhenUnset() {
        assertThat(settings("", 8080, "", "", "").enabled()).isFalse();
        assertThat(settings("p.x", 0, "", "", "").enabled()).isFalse();
        assertThat(settings("", 0, "", "", "").proxyUrl()).isNull();
    }

    @Test
    @DisplayName("secretValue: yalnız parola maskelenecek değer olarak döner (yoksa null)")
    void secretValue() {
        assertThat(settings("p.x", 3128, "u", "gizli", "").secretValue()).isEqualTo("gizli");
        assertThat(settings("p.x", 3128, "u", "", "").secretValue()).isNull();
    }

    @Test
    @DisplayName("CIDR kapsama: IPv4 prefix hesabı (bayt sınırı içinde ve dışında)")
    void cidrContains() throws Exception {
        assertThat(ScriptedCheckerService.cidrContains("10.0.0.0/8", InetAddress.getByName("10.20.30.40"))).isTrue();
        assertThat(ScriptedCheckerService.cidrContains("10.0.0.0/8", InetAddress.getByName("11.0.0.1"))).isFalse();
        assertThat(ScriptedCheckerService.cidrContains("172.16.0.0/12", InetAddress.getByName("172.31.255.1"))).isTrue();
        assertThat(ScriptedCheckerService.cidrContains("172.16.0.0/12", InetAddress.getByName("172.32.0.1"))).isFalse();
        assertThat(ScriptedCheckerService.cidrContains("169.254.169.254/32", InetAddress.getByName("169.254.169.254"))).isTrue();
        // IPv4/IPv6 karışımı ve bozuk girdi asla istisna atmaz
        assertThat(ScriptedCheckerService.cidrContains("fc00::/7", InetAddress.getByName("10.0.0.1"))).isFalse();
        assertThat(ScriptedCheckerService.cidrContains("bozuk", InetAddress.getByName("10.0.0.1"))).isFalse();
    }

    @Test
    @DisplayName("İstek timeout'u denetimi: açık timeout yoksa uyarı, varsa uyarı YOK")
    void requestTimeoutAudit() {
        String noTimeout = "import http from 'k6/http';\nexport default function(){ http.get('https://x'); }";
        assertThat(ScriptedCheckerService.auditRequestTimeouts(noTimeout))
                .singleElement().asString().contains("60 sn");

        String withTimeout = "export default function(){ http.get('https://x', { timeout: '20s' }); }";
        assertThat(ScriptedCheckerService.auditRequestTimeouts(withTimeout)).isEmpty();

        // İstek hiç yoksa uyarı üretme (ör. yalnız hesaplama yapan script)
        assertThat(ScriptedCheckerService.auditRequestTimeouts("export default function(){ }")).isEmpty();

        // Yorum içindeki timeout SAYILMAZ — yoksa "yorumda yazıyor" diye uyarı susturulurdu
        String commentOnly = "// timeout: '20s'\nexport default function(){ http.post('https://x','{}'); }";
        assertThat(ScriptedCheckerService.auditRequestTimeouts(commentOnly)).hasSize(1);

        // REGRESYON: URL'deki `//` satır yorumu SANILMAMALI — sanılırsa aynı satırdaki timeout
        // silinir ve doğru yazılmış script'e yanlış uyarı basılır (bu testte yakalandı).
        String urlSameLine = "export default function(){ http.get('https://www.akbank.com', { timeout: '20s' }); }";
        assertThat(ScriptedCheckerService.auditRequestTimeouts(urlSameLine)).isEmpty();
        assertThat(ScriptedCheckerService.stripComments("const u = 'https://x'; // yorum"))
                .contains("https://x").doesNotContain("yorum");
    }

    @Test
    @DisplayName("scriptedDetail: check sayacı DOLU olsa da sebep eklenir (alarm e-postasındaki boşluk)")
    void detailCarriesReasonWithFailedChecks() {
        var res = new ScriptedCheckerService.ScriptedResult(
                "FAIL", false, 60300L, 0, 1, 2, null, null, null, null, "tail",
                "k6 check/threshold başarısız:\nRequest Failed — Get \"https://x\": request timeout", false);

        String detail = SchedulerService.scriptedDetail(res);

        assertThat(detail).startsWith("FAIL — 1✓/2✗");
        assertThat(detail).contains("request timeout");   // eskiden BU eksikti
    }

    @Test
    @DisplayName("via_proxy sonuçta taşınır (geçmiş satırda hangi koşumun vekilden geçtiği görünsün)")
    void resultCarriesViaProxy() {
        var r = new ProcessProbe.Result("out", 0, false);
        var s = new ScriptedCheckerService.Summary();
        assertThat(ScriptedCheckerService.buildResult("PASS", 10L, r, s, "out", null, true).viaProxy()).isTrue();
        assertThat(ScriptedCheckerService.buildResult("PASS", 10L, r, s, "out", null, false).viaProxy()).isFalse();
    }

    @Test
    @DisplayName("Blacklist muafiyeti: vekil kullanılmıyorsa liste AYNEN kalır")
    void blacklistUntouchedWithoutProxy() {
        List<String> cidrs = SsrfGuard.blacklistCidrs(false, false);
        assertThat(cidrs).contains("10.0.0.0/8", "169.254.169.254/32");
    }
}
