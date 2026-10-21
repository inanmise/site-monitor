package com.sitemonitor.config.tracing;

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
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * KAPI (2026-10-21, inceleme bulgusu): OpenTelemetry Java ajanı sunucu span'larında {@code url.query}'yi kaydeder ve
 * 2.32.0 yalnız birkaç bulut imza adını gizler. Onay bağlantılarının {@code ?token=}'ı (14 gün geçerli) iz arka ucuna
 * gitmesin diye Helm ajana bir gizleme listesi verir; o ayar ajanın listesinin YERİNE geçer.
 *
 * <ul>
 *   <li>Helm şablonundaki liste = {@link OpenTelemetryAgentProbe#SENSITIVE_QUERY_PARAMETERS} (tek kaynak) ve ajanın
 *       yerleşik listesini + uygulamanın sorgu kimliklerini içerir.</li>
 *   <li>Kaynak taraması: bir denetleyici sorgu dizesinden kimlik benzeri bir ad okursa ({@code @RequestParam},
 *       {@code getParameter}) o ad {@link OpenTelemetryAgentProbe#APP_QUERY_CREDENTIALS}'ta olmalı — yeni bir
 *       {@code ?apiKey=} ucu listeye girmeden sızamaz.</li>
 *   <li>URL YOLUNDA kimlik yok: gizleyici yolu kapsamaz, yol değişkeni adları kimlik benzeri olamaz.</li>
 *   <li>RUNBOOK aynı listeyi (ajan bloğu tablosu + sürüm beklemeden geçici çözüm) taşır.</li>
 * </ul>
 */
class OtelAgentQueryRedactionGateTest {

    private static final Path MAIN = Path.of("src/main/java/com/sitemonitor");
    private static final Path HELPERS = Path.of("../helm/site-monitor/templates/_helpers.tpl");
    private static final Path DEPLOYMENT = Path.of("../helm/site-monitor/templates/deployment.yaml");
    private static final Path RUNBOOK = Path.of("../docs/RUNBOOK.md");
    private static final String ENV = "OTEL_INSTRUMENTATION_SANITIZATION_URL_EXPERIMENTAL_SENSITIVE_QUERY_PARAMETERS";

    /** Kimlik taşıyabilecek ad (SecretMask / RequestLoggingFilter sözlüğüyle aynı aile). */
    static final Pattern CREDENTIAL_NAME = Pattern.compile(
            "(?i)token|secret|passw|parola|sifre|api_?key|private_?key|credential|session_?id|jsessionid|otp|mfa"
                    + "|verification_?code|^key$|^code$|^pin$|^auth|^sig$|signature|ticket|nonce");

    private static final Pattern REQUEST_PARAM = Pattern.compile(
            "@RequestParam(?:\\(([^)]*)\\))?\\s+(?:final\\s+)?[\\w.]+(?:\\s*<[^>]*>)?(?:\\[])?\\s+(\\w+)");
    private static final Pattern GET_PARAMETER = Pattern.compile("getParameter(?:Values)?\\(\"([^\"]+)\"\\)");
    private static final Pattern MAPPING_PATH_VAR = Pattern.compile("@\\w*Mapping\\([^)]*\\)", Pattern.DOTALL);
    private static final Pattern PATH_VAR = Pattern.compile("\\{(\\w+)(?::[^}]*)?}");

    /** Kimlik benzeri adlı ama kimlik OLMAYAN yol değişkenleri ("Dosya.java: {ad}" → gerekçe). */
    private static final Map<String, String> PATH_VAR_NOT_A_SECRET = Map.of(
            "DataQualityController.java: {key}", "takım kimliği ya da 'unassigned' (veri kalitesi takım satırı)");

    private static String shippedHelmList() throws IOException {
        String tpl = Files.readString(HELPERS, StandardCharsets.UTF_8);
        Matcher m = Pattern.compile("define \"site-monitor.otelSensitiveQueryParameters\" -}}\\s*\\n([^\\n{]+)\\n")
                .matcher(tpl);
        assertThat(m.find()).as("_helpers.tpl site-monitor.otelSensitiveQueryParameters").isTrue();
        return m.group(1).trim();
    }

    @Test
    @DisplayName("Helm listesi = Java tek kaynağı; ajanın yerleşik listesi + uygulamanın sorgu kimlikleri içinde")
    void helmListMatchesSingleSource() throws IOException {
        assertThat(shippedHelmList()).isEqualTo(String.join(",", OpenTelemetryAgentProbe.SENSITIVE_QUERY_PARAMETERS));
        assertThat(OpenTelemetryAgentProbe.SENSITIVE_QUERY_PARAMETERS)
                .as("ayar ajanın listesinin YERİNE geçer → yerleşik adlar tekrar yazılmalı")
                .containsAll(OpenTelemetryAgentProbe.AGENT_DEFAULT_SENSITIVE_QUERY_PARAMETERS)
                .containsAll(OpenTelemetryAgentProbe.APP_QUERY_CREDENTIALS)
                .doesNotHaveDuplicates()
                .allSatisfy(n -> assertThat(n).doesNotContain(",").isEqualTo(n.trim()));
        String deployment = Files.readString(DEPLOYMENT, StandardCharsets.UTF_8);
        assertThat(deployment).contains("\"" + ENV + "\" (include \"site-monitor.otelSensitiveQueryParameters\" .)");
    }

    @Test
    @DisplayName("kaynak taraması: sorgu dizesinden okunan her kimlik benzeri ad APP_QUERY_CREDENTIALS'ta; hiçbiri eskimiş değil")
    void everyQueryCredentialIsRedacted() throws IOException {
        Map<String, String> credentialParams = new TreeMap<>();
        for (Path f : javaFiles()) {
            String src = Files.readString(f, StandardCharsets.UTF_8);
            for (String name : queryParamNames(src)) {
                if (CREDENTIAL_NAME.matcher(name).find()) credentialParams.putIfAbsent(name, f.getFileName().toString());
            }
        }
        assertThat(credentialParams).as("bugünkü tarama: yalnız onay bağlantılarının token'ı").containsKey("token");
        assertThat(OpenTelemetryAgentProbe.APP_QUERY_CREDENTIALS)
                .as("kimlik benzeri sorgu adları (ad → ilk dosya): " + credentialParams)
                .containsAll(credentialParams.keySet());
        assertThat(credentialParams.keySet()).as("APP_QUERY_CREDENTIALS'ta artık okunmayan ad kalmasın")
                .containsAll(OpenTelemetryAgentProbe.APP_QUERY_CREDENTIALS);
    }

    @Test
    @DisplayName("URL YOLUNDA kimlik yok: hiçbir eşleme yolu kimlik benzeri bir değişken taşımaz (gizleyici yolu kapsamaz)")
    void noCredentialInUrlPath() throws IOException {
        List<String> offenders = new ArrayList<>();
        List<String> exemptSeen = new ArrayList<>();
        for (Path f : javaFiles()) {
            String src = Files.readString(f, StandardCharsets.UTF_8);
            Matcher mapping = MAPPING_PATH_VAR.matcher(src);
            while (mapping.find()) {
                Matcher v = PATH_VAR.matcher(mapping.group());
                while (v.find()) {
                    String id = f.getFileName() + ": {" + v.group(1) + "}";
                    if (PATH_VAR_NOT_A_SECRET.containsKey(id)) exemptSeen.add(id);
                    if (CREDENTIAL_NAME.matcher(v.group(1)).find() && !PATH_VAR_NOT_A_SECRET.containsKey(id)) {
                        offenders.add(id);
                    }
                }
            }
        }
        assertThat(offenders).isEmpty();
        assertThat(exemptSeen).as("muafiyet listesinde eskimiş giriş kalmasın").containsAll(PATH_VAR_NOT_A_SECRET.keySet());
    }

    @Test
    @DisplayName("tarayıcı kendini sınar: açık ad, name=/value= ve parametre adı okunur; defaultValue ad sayılmaz")
    void scannerReadsNames() {
        String src = "a(@RequestParam(required = false) String token, @RequestParam(\"apiKey\") String k,"
                + " @RequestParam(name = \"x_sig\", required = false) String s, @RequestParam(defaultValue = \"0\") int page,"
                + " @RequestParam Map<String, String> params, @RequestParam final Long teamId) { req.getParameter(\"otp\"); }";
        assertThat(queryParamNames(src)).containsExactlyInAnyOrder("token", "apiKey", "x_sig", "page", "params", "teamId",
                "otp");
        assertThat(CREDENTIAL_NAME.matcher("apiKey").find()).isTrue();
        assertThat(CREDENTIAL_NAME.matcher("page").find()).isFalse();
        assertThat(CREDENTIAL_NAME.matcher("monitorKey").find()).as("^key$ — kimlik değil").isFalse();
    }

    @Test
    @DisplayName("RUNBOOK: Helm tablosu ve sürüm beklemeden geçici çözüm aynı listeyi taşır")
    void runbookCarriesTheList() throws IOException {
        String runbook = Files.readString(RUNBOOK, StandardCharsets.UTF_8);
        String full = ENV + "=" + String.join(",", OpenTelemetryAgentProbe.SENSITIVE_QUERY_PARAMETERS);
        assertThat(runbook.split(Pattern.quote(full), -1).length - 1)
                .as("tablo satırı + kubectl geçici çözümü").isGreaterThanOrEqualTo(2);
    }

    static List<String> queryParamNames(String src) {
        List<String> names = new ArrayList<>();
        Matcher m = REQUEST_PARAM.matcher(src);
        while (m.find()) {
            String args = m.group(1);
            String name = m.group(2);
            if (args != null) {
                Matcher only = Pattern.compile("^\\s*\"([^\"]+)\"").matcher(args);
                Matcher named = Pattern.compile("\\b(?:name|value)\\s*=\\s*\"([^\"]+)\"").matcher(args);
                if (only.find()) name = only.group(1);
                else if (named.find()) name = named.group(1);
            }
            names.add(name);
        }
        Matcher g = GET_PARAMETER.matcher(src);
        while (g.find()) names.add(g.group(1));
        return names;
    }

    private static List<Path> javaFiles() throws IOException {
        try (Stream<Path> s = Files.walk(MAIN)) {
            return s.filter(p -> p.toString().endsWith(".java")).toList();
        }
    }
}
