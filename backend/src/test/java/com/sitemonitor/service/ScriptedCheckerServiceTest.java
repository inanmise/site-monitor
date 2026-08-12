package com.sitemonitor.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Saf/statik karar + ayrıştırma + tarama mantığı (Spring/k6 gerektirmez). */
class ScriptedCheckerServiceTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @DisplayName("decideStatus: karar tablosu (PASS/FAIL/ERROR/TIMEOUT)")
    void decideStatus_table() {
        assertThat(ScriptedCheckerService.decideStatus(0, false, 0)).isEqualTo("PASS");
        assertThat(ScriptedCheckerService.decideStatus(0, false, 2)).isEqualTo("FAIL");   // exit 0 ama check kaldı
        assertThat(ScriptedCheckerService.decideStatus(99, false, 0)).isEqualTo("FAIL");  // k6 threshold
        assertThat(ScriptedCheckerService.decideStatus(107, false, 0)).isEqualTo("ERROR"); // script/runtime hatası
        assertThat(ScriptedCheckerService.decideStatus(-1, false, 0)).isEqualTo("ERROR");  // binary/çalıştırma hatası
        assertThat(ScriptedCheckerService.decideStatus(0, true, 0)).isEqualTo("TIMEOUT");  // timeout her şeyi ezer
        assertThat(ScriptedCheckerService.decideStatus(99, true, 5)).isEqualTo("TIMEOUT");
    }

    @Test
    @DisplayName("parseSummary: geçerli k6 özeti → check sayıları + http/iteration süreleri + per-check listesi")
    void parseSummary_valid() {
        String json = "{\"metrics\":{\"checks\":{\"passes\":3,\"fails\":1},"
                + "\"http_req_duration\":{\"avg\":120.5,\"p(95)\":250.0},"
                + "\"iteration_duration\":{\"avg\":800.0}},"
                + "\"root_group\":{\"checks\":{\"status is 200\":{\"passes\":2,\"fails\":0},"
                + "\"body ok\":{\"passes\":1,\"fails\":1}}}}";
        ScriptedCheckerService.Summary s = ScriptedCheckerService.parseSummary(json, mapper);
        assertThat(s.checksPassed).isEqualTo(3);
        assertThat(s.checksFailed).isEqualTo(1);
        assertThat(s.httpReqAvgMs).isEqualTo(121L);   // round(120.5)
        assertThat(s.httpReqP95Ms).isEqualTo(250L);
        assertThat(s.iterationMs).isEqualTo(800L);
        assertThat(s.checksJson).contains("status is 200").contains("body ok")
                .contains("\"passed\":true").contains("\"passed\":false");
    }

    @Test
    @DisplayName("parseSummary: bozuk/boş JSON → istisna yok, boş özet")
    void parseSummary_brokenIsSafe() {
        assertThat(ScriptedCheckerService.parseSummary("{bozuk", mapper).checksPassed).isNull();
        assertThat(ScriptedCheckerService.parseSummary("", mapper).checksFailed).isNull();
        assertThat(ScriptedCheckerService.parseSummary("{}", mapper).checksJson).isNull();
    }

    // ── 2026-08 regresyonu: 289/289 koşum NPE ile düştü ve TANI TAMAMEN KAYBOLDU ──────────────
    // decideStatus(int) primitive imzası, Summary.checksFailed (Integer) null iken argüman
    // değerlendirilirken auto-unboxing NPE atıyordu. NPE dıştaki catch'e düşüp err()'e gidiyor,
    // err() ise exitCode/durationMs/outputTail'i siliyordu → k6'nın gerçek çıktısı hiç kaydedilmedi.

    @Test
    @DisplayName("REGRESYON: metrics.checks olmayan özet → parseSummary null bırakır, decideStatus NPE ATMAZ")
    void decideStatus_nullChecks_noNpe() {
        // k6 hiç check() çalıştırmadıysa özet JSON'unda checks düğümü HİÇ olmaz
        String noChecks = "{\"metrics\":{\"http_req_duration\":{\"avg\":10.0}}}";
        ScriptedCheckerService.Summary s = ScriptedCheckerService.parseSummary(noChecks, mapper);
        assertThat(s.checksFailed).isNull();   // ayrıştırıcı null'ı KORUR (sözleşme)

        // exit 0 + hiç check + threshold yok + hata satırı yok ⇒ NO_CHECKS (sessiz PASS DEĞİL)
        assertThat(ScriptedCheckerService.decideStatus(0, false, s.checksPassed, s.checksFailed)).isEqualTo("NO_CHECKS");
        assertThat(ScriptedCheckerService.decideStatus(107, false, s.checksPassed, s.checksFailed)).isEqualTo("ERROR");
        assertThat(ScriptedCheckerService.decideStatus(99, false, s.checksPassed, s.checksFailed)).isEqualTo("FAIL");
        assertThat(ScriptedCheckerService.decideStatus(0, true, s.checksPassed, s.checksFailed)).isEqualTo("TIMEOUT");
    }

    @Test
    @DisplayName("REGRESYON: exit 0 + hiç check → sessiz PASS YOK; script patladıysa ERROR, threshold varsa PASS")
    void decideStatus_noChecks_isNotSilentPass() {
        // Gerçek k6 v0.49 ölçümü: default() içinde fırlatılan istisna iterasyonu iptal eder ama
        // k6 yine 0 ile çıkar ve metrics.checks HİÇ oluşmaz. Bu koşum "başarılı" sayılamaz.
        assertThat(ScriptedCheckerService.decideStatus(0, false, null, null, false, true)).isEqualTo("ERROR");
        // check() kullanmayıp yalnız options.thresholds ile doğrulayan script MEŞRUDUR
        assertThat(ScriptedCheckerService.decideStatus(0, false, null, null, true, false)).isEqualTo("PASS");
        // ne hata ne threshold → koştu ama hiçbir şey doğrulanmadı
        assertThat(ScriptedCheckerService.decideStatus(0, false, null, null, false, false)).isEqualTo("NO_CHECKS");
        // check ÇALIŞTIYSA (0 başarısız) normal PASS — NO_CHECKS'e düşmemeli
        assertThat(ScriptedCheckerService.decideStatus(0, false, 3, 0, false, false)).isEqualTo("PASS");
        // exit 99 her hâlükârda FAIL; timeout her şeyi ezer
        assertThat(ScriptedCheckerService.decideStatus(99, false, null, null, false, true)).isEqualTo("FAIL");
        assertThat(ScriptedCheckerService.decideStatus(0, true, null, null, false, true)).isEqualTo("TIMEOUT");
    }

    @Test
    @DisplayName("sanitizeScriptPath: geçici script yolu mesajdan temizlenir (yol ifşası + anlamsız)")
    void sanitizeScriptPath_removesTempPath() {
        String msg = "SyntaxError: file:///C:/Users/7636/AppData/Local/Temp/k6-script-4913795144.js: "
                + "Unexpected token (1:33)";
        String out = ScriptedCheckerService.sanitizeScriptPath(msg);
        assertThat(out).doesNotContain("Users").doesNotContain("Temp").doesNotContain("k6-script-");
        assertThat(out).contains("script:").contains("Unexpected token (1:33)");   // tanı korunuyor
        // Linux yolu da (pod'daki gerçek biçim)
        assertThat(ScriptedCheckerService.sanitizeScriptPath("at /tmp/k6-script-8471.js:22:5"))
                .isEqualTo("at script:22:5");
        assertThat(ScriptedCheckerService.sanitizeScriptPath(null)).isNull();
    }

    @Test
    @DisplayName("parseSummary: metrics.<ad>.thresholds VARLIĞI okunur (gerçek k6 0.49 çıktı şekli)")
    void parseSummary_thresholds() {
        // Gerçek 0.49 --summary-export çıktısından: boolean'ın anlamı yanıltıcı (burada eşik GEÇTİ),
        // bu yüzden yalnız VARLIĞA bakılıyor.
        String withThr = "{\"metrics\":{\"http_req_duration\":{\"avg\":5.0,\"thresholds\":{\"p(95)<10000\":false}}}}";
        assertThat(ScriptedCheckerService.parseSummary(withThr, mapper).hasThresholds).isTrue();
        String noThr = "{\"metrics\":{\"http_req_duration\":{\"avg\":5.0}}}";
        assertThat(ScriptedCheckerService.parseSummary(noThr, mapper).hasThresholds).isFalse();
        assertThat(ScriptedCheckerService.parseSummary("", mapper).hasThresholds).isFalse();
    }

    @Test
    @DisplayName("REGRESYON: tamamen boş/bozuk özet → NPE yok; parsed=false ile 'hiç özet' ayırt edilir")
    void decideStatus_emptySummary_noNpe() {
        ScriptedCheckerService.Summary empty = ScriptedCheckerService.parseSummary("", mapper);
        assertThat(empty.parsed).isFalse();
        assertThat(ScriptedCheckerService.decideStatus(-1, false, empty.checksPassed, empty.checksFailed))
                .isEqualTo("ERROR");
        // geçerli özette parsed=true — "bozuk özet" ile "hiç özet" artık ayrışıyor
        assertThat(ScriptedCheckerService.parseSummary("{\"metrics\":{}}", mapper).parsed).isTrue();
    }

    @Test
    @DisplayName("REGRESYON: beklenmeyen istisnada exitCode/durationMs/outputTail KAYBOLMAZ")
    void buildResult_keepsDiagnostics() {
        var r = new ProcessProbe.Result("ERRO[0001] GoError: dial tcp: i/o timeout", 107, false);
        var s = ScriptedCheckerService.parseSummary("", mapper);
        var res = ScriptedCheckerService.buildResult("ERROR", 1234L, r, s, r.output(), "hata");

        assertThat(res.exitCode()).isEqualTo(107);      // eskiden -1'e eziliyordu
        assertThat(res.durationMs()).isEqualTo(1234L);  // eskiden null'a eziliyordu
        assertThat(res.outputTail()).contains("GoError"); // eskiden null'a eziliyordu
        assertThat(res.ok()).isFalse();
    }

    @Test
    @DisplayName("extractErrorLines: ERRO[/GoError/level=error satırları ayıklanır, tekilleştirilir, tavanlanır")
    void extractErrorLines_contract() {
        String out = "\n  running (00m01.0s)\n"
                + "ERRO[0001] GoError: dial tcp 10.0.0.1:443: connect: connection refused\n"
                + "ERRO[0001] GoError: dial tcp 10.0.0.1:443: connect: connection refused\n"  // aynı satır
                + "time=\"x\" level=error msg=\"script aborted\"\n"
                + "default ✓ [--------] 1 VUs\n";
        String lines = ScriptedCheckerService.extractErrorLines(out);
        assertThat(lines).isNotNull();
        assertThat(lines.lines()).hasSize(2);                    // tekilleştirme çalıştı
        assertThat(lines).contains("connection refused").contains("script aborted");
        assertThat(lines).doesNotContain("running (00m01");      // gürültü alınmadı

        // eşleşme yoksa null → çağıran eski son-400-karakter davranışına düşer
        assertThat(ScriptedCheckerService.extractErrorLines("hepsi temiz\nbitti")).isNull();
        assertThat(ScriptedCheckerService.extractErrorLines(null)).isNull();
        assertThat(ScriptedCheckerService.extractErrorLines("")).isNull();

        // en fazla 3 k6 log satırı
        StringBuilder many = new StringBuilder();
        for (int i = 0; i < 10; i++) many.append("ERRO[000").append(i).append("] GoError: hata ").append(i).append('\n');
        assertThat(ScriptedCheckerService.extractErrorLines(many.toString()).lines()).hasSize(3);
    }

    /**
     * GERÇEK k6 v0.49.0 çıktısı ({@code k6 archive}, optional chaining içeren script).
     * Elle yazılmadı — {@code k6.exe archive} çıktısından alındı; yalnız 64 iç yığın çerçevesinden
     * 3'ü bırakıldı (davranış aynı, test okunur kalsın). Yol prod'daki geçici dosya adıyla birebir.
     */
    private static final String K6_049_SYNTAX_ERROR =
            "time=\"2026-08-11T03:20:59+03:00\" level=error msg=\"SyntaxError: "
            + "file:///tmp/k6-script-1854441804016005529.js: Unexpected token (46:29)"
            + "\\n  44 |       try {"
            + "\\n  45 |         const body = JSON.parse(r.body);"
            + "\\n> 46 |         const content = body?.choices?.[0]?.message?.content || '';"
            + "\\n     |                              ^"
            + "\\n  47 |         return content.toLowerCase().includes('paris');"
            + "\\n  48 |       } catch (e) {"
            + "\\n  49 |         return false;"
            + "\\n\\tat <internal/k6/compiler/lib/babel.min.js>:7:10099(24)"
            + "\\n\\tat h (<internal/k6/compiler/lib/babel.min.js>:4:30563(6))"
            + "\\n\\tat bound  (native)"
            + "\\n\" hint=\"script exception\"\n";

    @Test
    @DisplayName("decodeK6LogLine: logfmt msg=\"…\" alanı çözülür; alan yoksa/tırnak kapanmazsa satır bozulmaz")
    void decodeK6LogLine_contract() {
        assertThat(ScriptedCheckerService.decodeK6LogLine("time=\"x\" level=error msg=\"a\\nb\\tc\""))
                .isEqualTo("a\nb\tc");
        // kaçırılmış tırnak ve ters bölü korunur
        assertThat(ScriptedCheckerService.decodeK6LogLine("msg=\"de\\\"f\\\\g\"")).isEqualTo("de\"f\\g");
        // msg= alanı yok → dokunma
        assertThat(ScriptedCheckerService.decodeK6LogLine("ERRO[0001] GoError: reddedildi"))
                .isEqualTo("ERRO[0001] GoError: reddedildi");
        // tırnak kapanmamış → güvenli taraf, satırı olduğu gibi bırak
        assertThat(ScriptedCheckerService.decodeK6LogLine("level=error msg=\"yarım kalmış"))
                .isEqualTo("level=error msg=\"yarım kalmış");
        // hint= gibi msg sonrası alanlar atılır
        assertThat(ScriptedCheckerService.decodeK6LogLine("msg=\"tek\" hint=\"script exception\""))
                .isEqualTo("tek");
        assertThat(ScriptedCheckerService.decodeK6LogLine(null)).isNull();
    }

    @Test
    @DisplayName("extractErrorLines: k6 0.49 sözdizimi hatası okunur kod çerçevesine dönüşür, iç yığın gizlenir")
    void extractErrorLines_decodesBabelCodeFrame() {
        String lines = ScriptedCheckerService.extractErrorLines(K6_049_SYNTAX_ERROR);
        assertThat(lines).isNotNull();

        // 1) Kaçış dizileri gerçek satır sonlarına döndü — ekranda "\n" görünmeyecek.
        assertThat(lines).doesNotContain("\\n").doesNotContain("\\t");
        assertThat(lines.lines().count()).isGreaterThan(5);

        // 2) Asıl bilgi duruyor: hata türü, satır:sütun ve suçlu kaynak satırı.
        assertThat(lines).contains("Unexpected token (46:29)");
        assertThat(lines).contains("body?.choices?.[0]?.message?.content");

        // 3) Caret bir üstteki kod satırıyla HİZALI — girinti korunmalı, yoksa "neresi" bilgisi ölür.
        String[] all = lines.split("\n");
        int codeIdx = -1;
        for (int i = 0; i < all.length; i++) if (all[i].startsWith("> 46 |")) codeIdx = i;
        assertThat(codeIdx).isGreaterThanOrEqualTo(0);
        assertThat(all[codeIdx + 1]).endsWith("^");
        // Babel caret'i `?.` ikilisinin NOKTASINI gösterir (kaynak sütun 29) — Babel 6'nın
        // ayrıştıramadığı tam karakter. Bir sağı, yani indexOf("?.") + 1.
        assertThat(all[codeIdx + 1].indexOf('^')).isEqualTo(all[codeIdx].indexOf("?.") + 1);

        // 4) k6/Babel iç yığını gürültüsü atıldı — ama sessizce değil, sayısı söylendi.
        assertThat(lines).doesNotContain("<internal/").doesNotContain("(native)");
        assertThat(lines).contains("3 k6 iç yığın satırı gizlendi");

        // 5) summarizeError'ın uyguladığı yol temizliği çalışıyor: sunucu dosya yolu sızmıyor.
        assertThat(ScriptedCheckerService.sanitizeScriptPath(lines))
                .doesNotContain("/tmp/k6-script-").contains("script: Unexpected token");
    }

    /**
     * GERÇEK k6 v0.49.0 çıktısı: ulaşılamayan bir hedefe istek. k6 bunu HATA değil UYARI
     * seviyesinde basar ve asıl sebep {@code msg}'de değil ayrı bir {@code error} alanındadır.
     */
    private static final String K6_049_REQUEST_FAILED =
            "time=\"2026-08-11T11:08:38+03:00\" level=warning msg=\"Request Failed\" "
            + "error=\"Post \\\"http://192.0.2.1/v1/chat/completions\\\": request timeout\"\n";

    @Test
    @DisplayName("parseLogfmt: üst-seviye alanlar ayrışır, tırnak içindeki anahtar=değer yanıltmaz")
    void parseLogfmt_contract() {
        var f = ScriptedCheckerService.parseLogfmt(K6_049_REQUEST_FAILED.strip());
        assertThat(f.get("level")).isEqualTo("warning");
        assertThat(f.get("msg")).isEqualTo("Request Failed");
        assertThat(f.get("error")).isEqualTo("Post \"http://192.0.2.1/v1/chat/completions\": request timeout");

        // Tırnak İÇİNDE geçen bir `error=` üst-seviye alan sanılmamalı — naif indexOf bu tuzağa düşer.
        var g = ScriptedCheckerService.parseLogfmt("level=error msg=\"sunucu error=42 dedi\"");
        assertThat(g.get("msg")).isEqualTo("sunucu error=42 dedi");
        assertThat(g.get("error")).isNull();

        // logfmt olmayan satır → alan yok (çağıran satırı olduğu gibi bırakır)
        assertThat(ScriptedCheckerService.parseLogfmt("ERRO[0001] GoError: reddedildi")).isEmpty();
    }

    @Test
    @DisplayName("Başarısız istek: sebep uyarı satırından okunur ve FAIL metnine girer (eskiden kayboluyordu)")
    void requestFailed_reasonSurfacesOnFail() {
        // decodeK6LogLine msg + error'ı BİRLEŞTİRMELİ; yalnız msg alınsaydı geriye
        // "Request Failed" kalır ve kullanıcı NEDEN başarısız olduğunu yine bilemezdi.
        String decoded = ScriptedCheckerService.decodeK6LogLine(K6_049_REQUEST_FAILED.strip());
        assertThat(decoded).isEqualTo(
                "Request Failed — Post \"http://192.0.2.1/v1/chat/completions\": request timeout");

        // level=warning satırı ayıklamaya DAHİL (en sık gerçek arıza bu seviyede basılıyor)
        String lines = ScriptedCheckerService.extractErrorLines(K6_049_REQUEST_FAILED);
        assertThat(lines).isNotNull();
        assertThat(lines).contains("request timeout").contains("192.0.2.1");
    }

    @Test
    @DisplayName("FAIL metni: çıkış 0'da 'başarılı (çıkış 0)' etiketi YAZILMAZ (kendini yalanlıyordu)")
    void failText_omitsExitLabelOnZero() {
        // exitCodeLabel(0) = "başarılı" → "k6 check/threshold başarısız — başarılı (çıkış 0)".
        // Check'i düşen bir koşumun 0 ile çıkması normaldir; etiket bilgi katmaz, çelişki yaratır.
        assertThat(ScriptedCheckerService.exitCodeLabel(0, false)).isEqualTo("başarılı (çıkış 0)");
        // 99 (threshold) bilgi TAŞIR — o korunmalı.
        assertThat(ScriptedCheckerService.exitCodeLabel(99, false)).contains("threshold");
    }

    @Test
    @DisplayName("extractErrorLines: error= taşımayan sıradan k6 uyarıları gürültü sayılır, alınmaz")
    void extractErrorLines_ignoresPlainWarnings() {
        String out = "time=\"x\" level=warning msg=\"Insecure TLS verification enabled\"\n"
                   + "time=\"x\" level=info msg=\"init\"\n";
        assertThat(ScriptedCheckerService.extractErrorLines(out)).isNull();
    }

    @Test
    @DisplayName("extractErrorLines: kullanıcının KENDİ script çerçeveleri korunur, yalnız iç çerçeveler atılır")
    void extractErrorLines_keepsUserStackFrames() {
        // k6 boruya (non-TTY) yazarken logfmt kullanır: yığın, msg="…" içinde \n kaçışlarıyla gelir.
        String out = "time=\"x\" level=error msg=\"GoError: hedef yanıt vermedi"
                + "\\n\\tat file:///tmp/k6-script-42.js:17:9(24)"
                + "\\n\\tat <internal/k6/compiler/lib/babel.min.js>:7:10099(24)"
                + "\\n\\tat bound  (native)\"\n";
        String lines = ScriptedCheckerService.extractErrorLines(out);
        assertThat(lines).contains("k6-script-42.js:17:9");   // kullanıcının satırı = asıl aranan bilgi
        assertThat(lines).doesNotContain("babel.min.js").doesNotContain("(native)");
        assertThat(lines).contains("2 k6 iç yığın satırı gizlendi");
    }

    @Test
    @DisplayName("exitCodeLabel: k6 v0.49 çıkış kodları insan diline çevrilir; 105 bizim timeout'umuz DEĞİL")
    void exitCodeLabel_table() {
        assertThat(ScriptedCheckerService.exitCodeLabel(99, false)).contains("threshold");
        assertThat(ScriptedCheckerService.exitCodeLabel(107, false)).contains("çalışma-zamanı");
        assertThat(ScriptedCheckerService.exitCodeLabel(104, false)).contains("yapılandırma");
        // 105 = dış sinyal. ProcessProbe kendi timeout'unda exitCode'u -1 yaptığı için bu kod
        // bizim sonlandırmamız olamaz → pod restart / OOM / node tahliyesi işareti.
        assertThat(ScriptedCheckerService.exitCodeLabel(105, false)).contains("OOM");
        assertThat(ScriptedCheckerService.exitCodeLabel(-1, true)).contains("sonlandırıldı");
        assertThat(ScriptedCheckerService.exitCodeLabel(-1, false)).contains("başlatılamadı");
        assertThat(ScriptedCheckerService.exitCodeLabel(42, false)).contains("42");   // bilinmeyen → ham kod
    }

    @Test
    @DisplayName("auditEnvReferences: eksik __ENV referansı uyarılır; dinamik erişimde 'kullanılmıyor' SUSTURULUR")
    void auditEnvReferences_contract() {
        // 1) script okuyor ama tanım yok → en değerli uyarı (sohbetten kopyalanan script'lerde en sık kırılma)
        var w1 = ScriptedCheckerService.auditEnvReferences(
                "const u = __ENV.LLM_URL; const t = __ENV[\"TOKEN\"];", java.util.List.of("TOKEN"));
        assertThat(String.join("|", w1)).contains("LLM_URL").doesNotContain("TOKEN");

        // 2) tanımlı ama kullanılmıyor → bilgi amaçlı
        var w2 = ScriptedCheckerService.auditEnvReferences("const u = __ENV.A;", java.util.List.of("A", "B"));
        assertThat(String.join("|", w2)).contains("B");

        // 3) DİNAMİK erişim → "kullanılmıyor" yarısı susturulur (statik çözülemez)
        var w3 = ScriptedCheckerService.auditEnvReferences(
                "const k = 'A'; const v = __ENV[k];", java.util.List.of("A", "B"));
        assertThat(String.join("|", w3)).doesNotContain("kullanmıyor");

        // 4) yorum içindeki referans sayılmaz
        var w4 = ScriptedCheckerService.auditEnvReferences(
                "// eski: __ENV.OLD_TOKEN\n/* __ENV.OLD2 */\nconst a = __ENV.A;", java.util.List.of("A"));
        assertThat(String.join("|", w4)).doesNotContain("OLD_TOKEN").doesNotContain("OLD2");

        // 5) boş/null girdi güvenli
        assertThat(ScriptedCheckerService.auditEnvReferences(null, java.util.List.of("A"))).isEmpty();
        assertThat(ScriptedCheckerService.auditEnvReferences("", null)).isEmpty();
    }

    @Test
    @DisplayName("scanHardcodedSecrets: sabit-kodlu parola/token/apikey yakalanır (yalnız anahtar-adı raporlanır)")
    void scanHardcodedSecrets_detects() {
        String script = "const password = \"süpergizli123\";\n"
                + "let token: 'abc123def456';\n"
                + "headers = { Authorization: \"Bearer eyJhbGciOi\" };\n"
                + "const apiKey = \"sk-live-9f8e7d6c\";";
        var hits = ScriptedCheckerService.scanHardcodedSecrets(script);
        assertThat(hits).contains("password").contains("token").contains("authorization").contains("apikey");
        // DEĞERLER raporlanmaz (sızıntı yok)
        assertThat(String.join(",", hits)).doesNotContain("süpergizli123").doesNotContain("sk-live");
    }

    @Test
    @DisplayName("scanHardcodedSecrets: __ENV kullanan temiz script → bulgu yok")
    void scanHardcodedSecrets_cleanEnvUsage() {
        String clean = "const user = __ENV.USER; const pass = __ENV.PASS;\nhttp.post(url, { username: user, password: pass });";
        // password: <değişken> (literal değil) → yakalanmaz
        assertThat(ScriptedCheckerService.scanHardcodedSecrets(clean)).isEmpty();
        assertThat(ScriptedCheckerService.scanHardcodedSecrets(null)).isEmpty();
    }
}
