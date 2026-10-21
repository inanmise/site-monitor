package com.sitemonitor.config.tracing;

import java.lang.management.ManagementFactory;
import java.util.List;
import java.util.Locale;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Bu JVM'de OpenTelemetry Java ajanı etkin mi (2026-10-21)? Test edilebilsin diye her girdi bir dikiş (seam):
 * {@link #jvm()} gerçek JVM'i okur, testler sahte girdi verir.
 *
 * <p><b>Algılama.</b> Resmî ajan (ve onu temel alan dağıtımlar — operatörün {@code autoinstrumentation-java} imajı,
 * Grafana / Splunk / Elastic sürümleri) {@code premain}'de kendi jar'ını ÖNYÜKLEME (bootstrap) sınıf yükleyicisine
 * ekler; {@value #BOOTSTRAP_CLASS} o andan itibaren {@code Class.forName(ad, false, null)} ile bulunur (2.32.0'da
 * doğrulandı). Yedek işaret: adında {@code otel} / {@code opentelemetry} geçen bir {@code -javaagent:} JVM argümanı
 * ({@code JAVA_TOOL_OPTIONS} ile verilenler de girdi argümanlarındadır; JaCoCo gibi başka ajanlar eşleşmez). Ajan
 * {@code otel.javaagent.enabled=false} (ya da {@code OTEL_JAVAAGENT_ENABLED=false}) ile kapatılmışsa sınıf yine
 * yüklüdür ama ajan hiçbir şey kurmaz → yok sayılır (ajanın kendi okuma sırası: sistem özelliği > ortam değişkeni).
 * Ajanın yapılandırma DOSYASI ({@code otel.javaagent.configuration-file}) okunmaz.
 */
public final class OpenTelemetryAgentProbe {

    /** Ajanın önyükleme sınıf yükleyicisine koyduğu, her sürümde bulunan giriş sınıfı. */
    static final String BOOTSTRAP_CLASS = "io.opentelemetry.javaagent.bootstrap.AgentInitializer";

    /**
     * Uygulamanın izlenen sitelere / üçüncü taraflara giden HTTP istemcilerini izleyen ajan enstrümantasyonları:
     * {@code java.net.http.HttpClient} (HTTP / Keyword / Sayfa / RDAP / web-WHOIS / webhook / push / GeoIP) ve
     * {@code HttpURLConnection} (HSTS, OCSP / CRL, port kontrolü). Ad = ajanın modül adı (2.32.0'da doğrulandı).
     */
    static final List<String> CLIENT_INSTRUMENTATIONS = List.of("java-http-client", "http-url-connection");

    /** Ajanın sorgu adı gizleme ayarı (ortam: {@code OTEL_INSTRUMENTATION_SANITIZATION_URL_EXPERIMENTAL_SENSITIVE_QUERY_PARAMETERS}). */
    static final String SENSITIVE_QUERY_KEY = "otel.instrumentation.sanitization.url.experimental.sensitive-query-parameters";

    /**
     * Ajan 2.32.0'ın yerleşik gizleme listesi ({@code HttpConstants.SENSITIVE_QUERY_PARAMETERS}). Ayar verilince bu liste
     * GENİŞLEMEZ, DEĞİŞTİRİLİR ({@code CommonConfig}: ayar null değilse {@code new HashSet(ayar)}) — o yüzden yeniden
     * yazılır. Eşleşme büyük/küçük harf duyarlı ve kodlanmamış ad üzerinden ({@code Set.contains}).
     */
    public static final List<String> AGENT_DEFAULT_SENSITIVE_QUERY_PARAMETERS = List.of(
            "AWSAccessKeyId", "Signature", "X-Amz-Signature", "X-Amz-Credential", "X-Amz-Security-Token", "sig",
            "X-Goog-Signature");

    /**
     * Uygulamanın SORGU DİZESİNDEN kabul ettiği kimlik bilgileri (2026-10-21 taraması): {@code token} — haftalık rapor
     * ({@code GET /api/weekly-reports/approve-link?token=…}) ve teknik hata raporu ({@code GET
     * /api/tech-error-reports/approve-link?token=…}) onay bağlantıları; 14 gün geçerli, kimliği doğrulanmamış onay.
     * Başka uç sorgu ile kimlik almaz (oturum çerezde, OTP / giriş JSON gövdesinde, metrik belirteci Authorization
     * başlığında) ve hiçbir kimlik URL YOLUNDA taşınmaz. Kapı: {@code OtelAgentQueryRedactionGateTest}.
     */
    public static final List<String> APP_QUERY_CREDENTIALS = List.of("token");

    /**
     * Helm'in ajana verdiği gizleme listesi (tek kaynak; Helm şablonu ve RUNBOOK bunun aynısını taşır — kapı denetler):
     * ajanın yerleşik listesi + uygulamanın sorgu kimlikleri + derinlemesine savunma için yaygın kimlik adları (istemci
     * enstrümantasyonu yeniden açılırsa izlenen URL'lerdeki anahtarlar; yanlış kullanan bir çağıran).
     */
    public static final List<String> SENSITIVE_QUERY_PARAMETERS = List.of(
            "AWSAccessKeyId", "Signature", "X-Amz-Signature", "X-Amz-Credential", "X-Amz-Security-Token", "sig",
            "X-Goog-Signature",
            "token",
            "access_token", "api_key", "apiKey", "apikey", "key", "code", "password", "secret", "signature",
            "client_secret", "subscription-key", "auth", "jsessionid", "otp");

    private final BooleanSupplier bootstrapClassPresent;
    private final Function<String, String> systemProperties;
    private final Function<String, String> environment;
    private final Supplier<List<String>> inputArguments;

    OpenTelemetryAgentProbe(BooleanSupplier bootstrapClassPresent, Function<String, String> systemProperties,
                            Function<String, String> environment, Supplier<List<String>> inputArguments) {
        this.bootstrapClassPresent = bootstrapClassPresent;
        this.systemProperties = systemProperties;
        this.environment = environment;
        this.inputArguments = inputArguments;
    }

    /** Gerçek JVM: önyükleme sınıfı, {@link System#getProperty}, {@link System#getenv}, JVM girdi argümanları. */
    public static OpenTelemetryAgentProbe jvm() {
        return new OpenTelemetryAgentProbe(OpenTelemetryAgentProbe::bootstrapClassLoadable, System::getProperty,
                System::getenv, OpenTelemetryAgentProbe::jvmInputArguments);
    }

    /** Ajan bu JVM'de etkin mi. */
    public boolean present() {
        if (!enabled(config("otel.javaagent.enabled"), true)) return false;
        return bootstrapClassPresent.getAsBoolean() || hasOtelJavaagentArgument(inputArguments.get());
    }

    /**
     * Ajanın java.net.http / HttpURLConnection istemci enstrümantasyonlarından en az biri açık mı (ajanın kuralı:
     * modüle özgü {@code otel.instrumentation.<ad>.enabled}, yoksa {@code otel.instrumentation.common.default-enabled},
     * yoksa açık).
     */
    public boolean instrumentsHttpClients() {
        boolean defaultEnabled = enabled(config("otel.instrumentation.common.default-enabled"), true);
        for (String name : CLIENT_INSTRUMENTATIONS) {
            if (enabled(config("otel.instrumentation." + name + ".enabled"), defaultEnabled)) return true;
        }
        return false;
    }

    /**
     * Ajanın {@code url.query} gizleme listesi uygulamanın sorgu kimliklerini ({@link #APP_QUERY_CREDENTIALS}) kapsıyor
     * mu. Ayar yoksa ajanın yerleşik listesi geçerlidir (kapsamaz); ayar varsa virgülle ayrılmış adlar, kırpılmış.
     */
    public boolean redactsQueryCredentials() {
        String raw = config(SENSITIVE_QUERY_KEY);
        List<String> names = AGENT_DEFAULT_SENSITIVE_QUERY_PARAMETERS;
        if (raw != null && !raw.isBlank()) {
            names = java.util.Arrays.stream(raw.split(",")).map(String::trim).filter(n -> !n.isEmpty()).toList();
        }
        return names.containsAll(APP_QUERY_CREDENTIALS);
    }

    /** Ajanın yapılandırma okuması: sistem özelliği, yoksa ortam değişkeni ({@code a.b-c} → {@code A_B_C}). */
    String config(String key) {
        String v = systemProperties.apply(key);
        if (v != null && !v.isBlank()) return v;
        return environment.apply(envName(key));
    }

    static String envName(String key) {
        return key.replace('.', '_').replace('-', '_').toUpperCase(Locale.ROOT);
    }

    /** Ajanın boolean okuması: boş = varsayılan, aksi hâlde yalnız "true" açıktır ({@code Boolean.parseBoolean}). */
    static boolean enabled(String raw, boolean whenUnset) {
        if (raw == null || raw.isBlank()) return whenUnset;
        return Boolean.parseBoolean(raw.trim());
    }

    /** {@code -javaagent:<yol>[=seçenekler]} içinde bir yol parçası {@code otel} ile başlıyor ya da {@code opentelemetry} içeriyor. */
    static boolean hasOtelJavaagentArgument(List<String> args) {
        if (args == null) return false;
        for (String arg : args) {
            if (arg == null || !arg.startsWith("-javaagent:")) continue;
            String path = arg.substring("-javaagent:".length());
            int opts = path.indexOf('=');
            if (opts >= 0) path = path.substring(0, opts);
            for (String part : path.toLowerCase(Locale.ROOT).split("[/\\\\]")) {
                if (part.startsWith("otel") || part.contains("opentelemetry")) return true;
            }
        }
        return false;
    }

    static boolean bootstrapClassLoadable() {
        try {
            Class.forName(BOOTSTRAP_CLASS, false, null);
            return true;
        } catch (ClassNotFoundException | LinkageError e) {
            return false;
        }
    }

    private static List<String> jvmInputArguments() {
        try {
            return ManagementFactory.getRuntimeMXBean().getInputArguments();
        } catch (RuntimeException | LinkageError e) {
            return List.of();
        }
    }
}
