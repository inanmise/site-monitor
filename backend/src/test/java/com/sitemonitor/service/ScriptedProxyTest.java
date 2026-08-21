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
                "k6 check/threshold başarısız:\nRequest Failed — Get \"https://x\": request timeout", false,
                ScriptedCheckerService.Phases.EMPTY);

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
    @DisplayName("SsrfGuard temel listesi iç ağı ve metadata'yı kapsıyor (blacklistFor'un girdisi)")
    void ssrfBaselineCoversInternalRanges() {
        List<String> cidrs = SsrfGuard.blacklistCidrs(false, false);
        assertThat(cidrs).contains("10.0.0.0/8", "169.254.169.254/32");
    }

    /** Sıkı kara liste (iç ağ + loopback dâhil) — muafiyet mantığının anlamlı çalışabilmesi için. */
    private static final List<String> STRICT = SsrfGuard.blacklistCidrs(false, false);

    private ScriptedCheckerService serviceWithGuard(ProxySettings p) {
        SsrfGuard guard = org.mockito.Mockito.mock(SsrfGuard.class);
        org.mockito.Mockito.when(guard.blacklistCidrs()).thenReturn(STRICT);
        return new ScriptedCheckerService(guard, null, defaultSettings(), null, p);
    }

    /**
     * Ayarları VARSAYILANLARINA döndüren stub. {@code buildProcessEnv} artık kaynak tavanlarını
     * (GOMAXPROCS/GOMEMLIMIT) buradan okuyor; null geçmek testi üretimde olmayan bir NPE ile
     * düşürürdü. Varsayılanı döndürmek üretim davranışının ta kendisi.
     */
    private static AppSettingsService defaultSettings() {
        AppSettingsService s = org.mockito.Mockito.mock(AppSettingsService.class);
        org.mockito.Mockito.when(s.getString(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString())).thenAnswer(i -> i.getArgument(1));
        return s;
    }

    @Test
    @DisplayName("blacklistFor: vekil kullanılmıyorsa liste AYNEN kalır (muafiyet uygulanmaz)")
    void blacklistUntouchedWithoutProxy() {
        // Eski hâli bu adı taşıyıp gövdesinde SsrfGuard'ı çağırıyordu: blacklistFor HİÇ koşmuyordu
        // ve denetimde "vekil kara-liste muafiyeti test edildi" yanılgısı üretiyordu.
        var svc = serviceWithGuard(settings("proxy.akbank.com", 8080, "", "", ""));
        assertThat(svc.blacklistFor(false)).isEqualTo(STRICT);
    }

    @Test
    @DisplayName("blacklistFor: vekil çözülemiyorsa muafiyet uygulanmaz (liste daralmaz)")
    void blacklistUnchangedWhenProxyUnresolvable() {
        var svc = serviceWithGuard(settings(TestHosts.UNRESOLVABLE, 8080, "", "", ""));
        assertThat(svc.blacklistFor(true)).isEqualTo(STRICT);
    }

    @Test
    @DisplayName("blacklistFor: vekili KAPSAYAN aralık düşer, hedef korumaları KALIR")
    void blacklistDropsOnlyProxyRange() {
        // "Vekili açtım ama yine çalışmıyor" durumunun kaynağı: --blacklist-ip k6'nın dialer'ında
        // uygulanır ve vekil kullanılırken k6 HEDEFE değil VEKİLE bağlanır.
        var svc = serviceWithGuard(settings("localhost", 8080, "", "", ""));

        List<String> out = svc.blacklistFor(true);

        assertThat(out).doesNotContain("127.0.0.0/8");             // vekili kapsayan aralık düştü
        assertThat(out).contains("169.254.169.254/32", "10.0.0.0/8");  // hedef koruması sürüyor
    }

    // ── buildProcessEnv: 288-koşumluk saha vakasının GEÇTİĞİ yol ─────────────────────────────
    // Bu yolun uzun süre tek satır testi yoktu; yalnız ProxySettings'in ürettiği STRING pinliydi.

    private ScriptedCheckerService service(ProxySettings p) {
        return new ScriptedCheckerService(null, null, defaultSettings(), null, p);
    }

    private static List<ScriptedCheckerService.EnvVar> env(ScriptedCheckerService.EnvVar... v) {
        return List.of(v);
    }

    @Test
    @DisplayName("Vekil değişkenleri GERÇEKTEN env haritasına giriyor (URL + NO_PROXY)")
    void proxyVarsReachProcessEnv() {
        var svc = service(settings("dmzproxy.aknet.akb", 8080, "", "", "akbank.com,localhost"));
        var secrets = new java.util.ArrayList<String>();

        var e = svc.buildProcessEnv(List.of(), ScriptedCheckerService.ProxyUse.AUTO, null, secrets);

        assertThat(e).containsEntry("HTTPS_PROXY", "http://dmzproxy.aknet.akb:8080")
                     .containsEntry("HTTP_PROXY", "http://dmzproxy.aknet.akb:8080")
                     .containsEntry("NO_PROXY", "akbank.com,localhost");
    }

    @Test
    @DisplayName("viaProxy=false → hiçbir vekil değişkeni konmaz (OFF gerçekten doğrudan)")
    void noProxyVarsWhenOff() {
        var svc = service(settings("dmzproxy.aknet.akb", 8080, "", "", "akbank.com"));

        var e = svc.buildProcessEnv(List.of(), ScriptedCheckerService.ProxyUse.DIRECT, null, new java.util.ArrayList<>());

        assertThat(e).doesNotContainKeys("HTTPS_PROXY", "HTTP_PROXY", "NO_PROXY");
    }

    @Test
    @DisplayName("Kullanıcının kendi HTTPS_PROXY'si EZİLMEZ (putIfAbsent sırası)")
    void userEnvWins() {
        var svc = service(settings("dmzproxy.aknet.akb", 8080, "", "", ""));

        var e = svc.buildProcessEnv(
                env(new ScriptedCheckerService.EnvVar("HTTPS_PROXY", "http://kendi:1234", false)),
                ScriptedCheckerService.ProxyUse.AUTO, null, new java.util.ArrayList<>());

        assertThat(e).containsEntry("HTTPS_PROXY", "http://kendi:1234");
    }

    @Test
    @DisplayName("Vekil parolası maskeleme listesine eklenir (çıktıya sızmasın)")
    void proxyPasswordCollectedForMasking() {
        var svc = service(settings("p.x", 3128, "svc", "P@ss w0rd", ""));
        var secrets = new java.util.ArrayList<String>();

        svc.buildProcessEnv(List.of(), ScriptedCheckerService.ProxyUse.AUTO, null, secrets);

        assertThat(secrets).contains("P@ss w0rd");
    }

    @Test
    @DisplayName("Secret env değerleri maskeleme listesine girer, secret OLMAYANLAR girmez")
    void secretEnvCollected() {
        var svc = service(settings("", 0, "", "", ""));
        var secrets = new java.util.ArrayList<String>();

        var e = svc.buildProcessEnv(env(
                new ScriptedCheckerService.EnvVar("TOKEN", "cok-gizli-deger", true),
                new ScriptedCheckerService.EnvVar("BASE_URL", "https://x", false)),
                ScriptedCheckerService.ProxyUse.DIRECT, null, secrets);

        assertThat(e).containsEntry("TOKEN", "cok-gizli-deger").containsEntry("BASE_URL", "https://x");
        assertThat(secrets).containsExactly("cok-gizli-deger");
    }

    @Test
    @DisplayName("Kurumsal CA verilmişse SSL_CERT_FILE konur — Java güvenirken k6 güvenmiyordu")
    void caBundleReachesEnv() {
        var svc = service(settings("", 0, "", "", ""));
        var ca = java.nio.file.Path.of("/tmp/k6-ca-test.pem");

        var e = svc.buildProcessEnv(List.of(), ScriptedCheckerService.ProxyUse.DIRECT, ca, new java.util.ArrayList<>());

        assertThat(e.get("SSL_CERT_FILE")).endsWith("k6-ca-test.pem");
        // CA yoksa değişken HİÇ konmaz (boş yol Go'da sistem havuzunu bozardı)
        assertThat(svc.buildProcessEnv(List.of(), ScriptedCheckerService.ProxyUse.DIRECT, null,
                new java.util.ArrayList<>())).doesNotContainKey("SSL_CERT_FILE");
    }

    @Test
    @DisplayName("Kaynak tavanları alt süreç ortamına GEÇER (GOMAXPROCS/GOMEMLIMIT)")
    void resourceLimitsReachProcessEnv() {
        // Script'ler ÜRETİM sistemlerinde koşuyor ve k6 ayrı bir süreç: pod limiti dışında onu
        // kısan hiçbir şey yoktu. Bu iki değişkeni Go runtime'ı doğrudan okur — sonsuz bir CPU
        // döngüsü tek çekirdekten, bellek de tavandan fazlasını yiyemez.
        var svc = service(settings("", 0, "", "", ""));

        var e = svc.buildProcessEnv(List.of(), ScriptedCheckerService.ProxyUse.DIRECT, null,
                new java.util.ArrayList<>());

        assertThat(e).containsEntry("GOMAXPROCS", "1").containsEntry("GOMEMLIMIT", "256MiB");
    }

    @Test
    @DisplayName("Kullanıcının kendi GOMAXPROCS'u EZİLMEZ (putIfAbsent) — bilinçli değer korunur")
    void userSuppliedLimitWins() {
        var svc = service(settings("", 0, "", "", ""));

        var e = svc.buildProcessEnv(env(new ScriptedCheckerService.EnvVar("GOMAXPROCS", "2", false)),
                ScriptedCheckerService.ProxyUse.DIRECT, null, new java.util.ArrayList<>());

        assertThat(e).containsEntry("GOMAXPROCS", "2");
    }

    // ── "Her zaman vekil üzerinden" (ON) GERÇEKTEN vekilden geçirir ──────────────────────────
    // ON uzun süre AUTO ile aynı env'i üretiyordu: NO_PROXY konduğu için Go, sonek eşleşen
    // (`akbank.com` ⇒ tüm alt alanlar) hedefleri doğrudan çıkarıyordu. Kullanıcı ON seçiyor,
    // ekran "vekil üzerinden" diyor, paket vekile hiç uğramıyordu.

    @Test
    @DisplayName("proxyUseFor: AUTO/boş ⇒ AUTO, ON ⇒ FORCED, OFF ⇒ DIRECT; vekil yoksa hepsi DIRECT")
    void proxyModeMapping() {
        var svc = service(settings("dmzproxy.aknet.akb", 8080, "", "", "akbank.com"));
        assertThat(svc.proxyUseFor(null)).isEqualTo(ScriptedCheckerService.ProxyUse.AUTO);
        assertThat(svc.proxyUseFor("AUTO")).isEqualTo(ScriptedCheckerService.ProxyUse.AUTO);
        assertThat(svc.proxyUseFor("on")).isEqualTo(ScriptedCheckerService.ProxyUse.FORCED);
        assertThat(svc.proxyUseFor("OFF")).isEqualTo(ScriptedCheckerService.ProxyUse.DIRECT);

        var noProxySvc = service(settings("", 0, "", "", ""));
        assertThat(noProxySvc.proxyUseFor("ON")).isEqualTo(ScriptedCheckerService.ProxyUse.DIRECT);
    }

    @Test
    @DisplayName("FORCED: vekil değişkenleri konur ama NO_PROXY KONMAZ (seçim gerçekten uygulanır)")
    void forcedOmitsNoProxy() {
        var svc = service(settings("dmzproxy.aknet.akb", 8080, "", "", "akbank.com,localhost"));

        var e = svc.buildProcessEnv(List.of(), ScriptedCheckerService.ProxyUse.FORCED, null,
                new java.util.ArrayList<>());

        assertThat(e).containsEntry("HTTPS_PROXY", "http://dmzproxy.aknet.akb:8080")
                     .containsEntry("HTTP_PROXY", "http://dmzproxy.aknet.akb:8080")
                     .doesNotContainKey("NO_PROXY");
    }

    @Test
    @DisplayName("FORCED ile AUTO'nun TEK farkı NO_PROXY — vekil URL'i ve maskeleme aynı kalır")
    void forcedDiffersOnlyByNoProxy() {
        var svc = service(settings("p.x", 3128, "svc", "gizli", "akbank.com"));
        var autoSecrets = new java.util.ArrayList<String>();
        var forcedSecrets = new java.util.ArrayList<String>();

        var auto = svc.buildProcessEnv(List.of(), ScriptedCheckerService.ProxyUse.AUTO, null, autoSecrets);
        var forced = svc.buildProcessEnv(List.of(), ScriptedCheckerService.ProxyUse.FORCED, null, forcedSecrets);

        assertThat(auto).containsKey("NO_PROXY");
        assertThat(forced).doesNotContainKey("NO_PROXY");
        assertThat(forced.get("HTTPS_PROXY")).isEqualTo(auto.get("HTTPS_PROXY"));
        assertThat(forcedSecrets).isEqualTo(autoSecrets).contains("gizli");
    }
}
