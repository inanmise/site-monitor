package com.sitemonitor.config.logging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.LoggingEvent;
import ch.qos.logback.core.CoreConstants;
import ch.qos.logback.core.pattern.DynamicConverter;
import ch.qos.logback.core.status.Status;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.core.env.Environment;
import org.springframework.mock.env.MockEnvironment;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Günlük biçimi anahtarı (2026-10-11): düz metin deseni + istek kimliği; JSON (ECS) satırında requestId / traceId ve
 * Türkçe karakter; Spring ortamı yoksa ya da biçim bilinmiyorsa düz metne düşüş; sevk edilen logback-spring.xml'de her
 * kodlayıcının anahtar olduğu. 2026-10-21: düz metindeki iz kimliği OpenTelemetry Java ajanının {@code trace_id}'si,
 * yoksa Micrometer'ın {@code traceId}'si ({@link TraceCorrelationConverter}, sevk edilen {@code conversionRule} ile).
 */
class SwitchableLogEncoderTest {

    private LoggerContext ctx;

    @BeforeEach
    void setUp() throws Exception {
        ctx = new LoggerContext();
        registerShippedConversionRules(ctx);
        ctx.start();
    }

    /**
     * Sevk edilen logback-spring.xml'deki {@code <conversionRule>} satırlarını bağlama kaydeder — Joran'ın yaptığı gibi;
     * kural adı ya da sınıf yanlışsa desen testleri düşer (kurulum gerçek dosyadan okunur).
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    static void registerShippedConversionRules(LoggerContext ctx) throws Exception {
        String xml = Files.readString(Path.of("src/main/resources/logback-spring.xml"), StandardCharsets.UTF_8);
        Matcher m = Pattern.compile("<conversionRule\\s+conversionWord=\"([^\"]+)\"\\s+class=\"([^\"]+)\"")
                .matcher(xml);
        Map<String, Supplier<DynamicConverter>> rules = new HashMap<>();
        while (m.find()) {
            Class<?> type = Class.forName(m.group(2));
            rules.put(m.group(1), () -> {
                try {
                    return (DynamicConverter) type.getDeclaredConstructor().newInstance();
                } catch (ReflectiveOperationException e) {
                    throw new IllegalStateException(e);
                }
            });
        }
        assertThat(rules).containsKey("traceCorrelation");
        ctx.putObject(CoreConstants.PATTERN_RULE_REGISTRY_FOR_SUPPLIERS, rules);
    }

    @AfterEach
    void tearDown() {
        MDC.clear();
        ctx.stop();
    }

    private LoggingEvent event(String msg, Map<String, String> mdc) {
        Logger logger = ctx.getLogger("com.sitemonitor.test");
        LoggingEvent e = new LoggingEvent(Logger.class.getName(), logger, Level.INFO, msg, null, null);
        e.setMDCPropertyMap(mdc);
        return e;
    }

    private SwitchableLogEncoder encoder(String format, String pattern) {
        SwitchableLogEncoder enc = new SwitchableLogEncoder();
        enc.setContext(ctx);
        enc.setFormat(format);
        enc.setPattern(pattern);
        enc.start();
        return enc;
    }

    /** Sevk edilen desendeki korelasyon parçası — testler gerçek dosyadaki değeri kullanır. */
    static String shippedCorrelationPattern() throws Exception {
        String xml = Files.readString(Path.of("src/main/resources/logback-spring.xml"), StandardCharsets.UTF_8);
        Matcher m = Pattern.compile("name=\"PAT_CORR\"\\s+value=\"([^\"]+)\"").matcher(xml);
        assertThat(m.find()).as("logback-spring.xml PAT_CORR").isTrue();
        return m.group(1);
    }

    @Test
    @DisplayName("düz metin: rid ve trace yalnız varken yazılır (sevk edilen desen)")
    void textCorrelation() throws Exception {
        SwitchableLogEncoder enc = encoder("text", "%level : " + shippedCorrelationPattern() + "%msg%n");
        assertThat(enc.effectiveFormat()).isEqualTo("text");
        String both = new String(enc.encode(event("merhaba", Map.of("requestId", "abc123", "traceId", "4bf92f35"))), StandardCharsets.UTF_8);
        assertThat(both).isEqualTo("INFO : rid=abc123 trace=4bf92f35 merhaba" + System.lineSeparator());
        String ridOnly = new String(enc.encode(event("merhaba", Map.of("requestId", "abc123"))), StandardCharsets.UTF_8);
        assertThat(ridOnly).isEqualTo("INFO : rid=abc123 merhaba" + System.lineSeparator());
        String none = new String(enc.encode(event("merhaba", Map.of())), StandardCharsets.UTF_8);
        assertThat(none).isEqualTo("INFO : merhaba" + System.lineSeparator());
    }

    @Test
    @DisplayName("düz metin: OpenTelemetry Java ajanının trace_id'si önce, yoksa Micrometer traceId; ikisi yoksa hiçbiri")
    void textCorrelationAgentTraceId() throws Exception {
        SwitchableLogEncoder enc = encoder("text", "%level : " + shippedCorrelationPattern() + "%msg%n");
        String nl = System.lineSeparator();
        String agentOnly = new String(enc.encode(event("x", Map.of("requestId", "r1",
                "trace_id", "0af7651916cd43dd8448eb211c80319c", "span_id", "b7ad6b7169203331"))), StandardCharsets.UTF_8);
        assertThat(agentOnly).isEqualTo("INFO : rid=r1 trace=0af7651916cd43dd8448eb211c80319c x" + nl);
        String micrometerOnly = new String(enc.encode(event("x", Map.of("traceId", "4bf92f3577b34da6a3ce929d0e0e4736"))),
                StandardCharsets.UTF_8);
        assertThat(micrometerOnly).isEqualTo("INFO : trace=4bf92f3577b34da6a3ce929d0e0e4736 x" + nl);
        String both = new String(enc.encode(event("x", Map.of("trace_id", "0af7651916cd43dd8448eb211c80319c",
                "traceId", "4bf92f3577b34da6a3ce929d0e0e4736"))), StandardCharsets.UTF_8);
        assertThat(both).as("ajanınki kazanır — iz arka ucundaki iz")
                .isEqualTo("INFO : trace=0af7651916cd43dd8448eb211c80319c x" + nl);
        String agentZeroMicrometerReal = new String(enc.encode(event("x", Map.of(
                "trace_id", "00000000000000000000000000000000", "traceId", "4bf92f3577b34da6a3ce929d0e0e4736"))),
                StandardCharsets.UTF_8);
        assertThat(agentZeroMicrometerReal).isEqualTo("INFO : trace=4bf92f3577b34da6a3ce929d0e0e4736 x" + nl);
        String zeros = new String(enc.encode(event("x", Map.of("requestId", "r2",
                "traceId", "00000000000000000000000000000000"))), StandardCharsets.UTF_8);
        assertThat(zeros).as("geçersiz (sıfır) span bağlamı yazılmaz").isEqualTo("INFO : rid=r2 x" + nl);
        String none = new String(enc.encode(event("x", Map.of())), StandardCharsets.UTF_8);
        assertThat(none).isEqualTo("INFO : x" + nl);
        String junk = new String(enc.encode(event("x", Map.of("traceId", "abc def\nINFO sahte"))), StandardCharsets.UTF_8);
        assertThat(junk).as("kimlik biçiminde olmayan değer satırı bozamaz").isEqualTo("INFO : x" + nl);
    }

    @Test
    @DisplayName("TraceCorrelationConverter.usable: boş / sıfır / uzun / biçimsiz değer reddedilir")
    void usableIds() {
        assertThat(TraceCorrelationConverter.usable("4bf92f3577b34da6a3ce929d0e0e4736")).isNotNull();
        assertThat(TraceCorrelationConverter.usable("0000000000000000")).isNull();
        assertThat(TraceCorrelationConverter.usable("")).isNull();
        assertThat(TraceCorrelationConverter.usable(null)).isNull();
        assertThat(TraceCorrelationConverter.usable("a".repeat(65))).isNull();
        assertThat(TraceCorrelationConverter.usable("4bf9-2f35")).isNull();
    }

    @Test
    @DisplayName("json: ajanın trace_id / span_id MDC alanları olduğu gibi yazılır")
    void jsonKeepsAgentMdc() {
        ctx.putObject(Environment.class.getName(),
                new MockEnvironment().withProperty("spring.application.name", "site-monitor"));
        SwitchableLogEncoder enc = encoder("json", "%msg%n");
        String line = new String(enc.encode(event("x", Map.of("requestId", "r1",
                "trace_id", "0af7651916cd43dd8448eb211c80319c", "span_id", "b7ad6b7169203331"))), StandardCharsets.UTF_8);
        assertThat(line).contains("\"trace_id\":\"0af7651916cd43dd8448eb211c80319c\"")
                .contains("\"span_id\":\"b7ad6b7169203331\"");
    }

    @Test
    @DisplayName("json: ECS satırı; requestId + traceId alanları, düzey, Türkçe ileti bozulmaz")
    void jsonEcs() throws Exception {
        MockEnvironment env = new MockEnvironment().withProperty("spring.application.name", "site-monitor");
        ctx.putObject(Environment.class.getName(), env);
        SwitchableLogEncoder enc = encoder("json", "%msg%n");
        assertThat(enc.effectiveFormat()).isEqualTo("ecs");
        String line = new String(enc.encode(event("Şema yamaları: 3 uygulandı", Map.of("requestId", "abc123", "traceId", "4bf92f35"))),
                StandardCharsets.UTF_8).trim();
        JsonNode n = new ObjectMapper().readTree(line);
        assertThat(n.path("message").asText()).isEqualTo("Şema yamaları: 3 uygulandı");
        assertThat(line).contains("Şema yamaları");   // satırda kaçışsız (grep ile bulunur)
        assertThat(n.path("log").path("level").asText(n.path("log.level").asText())).isEqualTo("INFO");
        assertThat(line).contains("\"requestId\":\"abc123\"").contains("\"traceId\":\"4bf92f35\"");
        assertThat(line).contains("site-monitor");
        assertThat(line).startsWith("{");
    }

    @Test
    @DisplayName("json + logstash çeşidi seçilebilir")
    void jsonLogstash() {
        ctx.putObject(Environment.class.getName(), new MockEnvironment());
        SwitchableLogEncoder enc = new SwitchableLogEncoder();
        enc.setContext(ctx);
        enc.setFormat("json");
        enc.setJsonFormat("logstash");
        enc.start();
        assertThat(enc.effectiveFormat()).isEqualTo("logstash");
        String line = new String(enc.encode(event("x", Map.of("requestId", "r1"))), StandardCharsets.UTF_8);
        assertThat(line).contains("\"@version\"").contains("\"requestId\":\"r1\"");
    }

    @Test
    @DisplayName("Spring ortamı yoksa JSON açılamaz → düz metne düşer ve UYARI bırakır; günlük kapanmaz")
    void jsonWithoutEnvironmentFallsBack() {
        SwitchableLogEncoder enc = encoder("json", "%level %msg%n");
        assertThat(enc.effectiveFormat()).isEqualTo("text");
        assertThat(new String(enc.encode(event("x", Map.of())), StandardCharsets.UTF_8)).startsWith("INFO x");
        assertThat(ctx.getStatusManager().getCopyOfStatusList())
                .anyMatch(s -> s.getLevel() == Status.WARN && s.getMessage().contains("JSON"));
    }

    @Test
    @DisplayName("bilinmeyen / boş biçim → düz metin")
    void unknownFormat() {
        assertThat(encoder("yaml", "%msg%n").effectiveFormat()).isEqualTo("text");
        assertThat(encoder("", "%msg%n").effectiveFormat()).isEqualTo("text");
        assertThat(encoder(null, "%msg%n").effectiveFormat()).isEqualTo("text");
        assertThat(SwitchableLogEncoder.normalize(" JSON ")).isEqualTo("json");
    }

    @Test
    @DisplayName("sevk edilen logback-spring.xml: her kodlayıcı anahtar; biçim ve istek kimliği her desende")
    void shippedConfig() throws Exception {
        String xml = Files.readString(Path.of("src/main/resources/logback-spring.xml"), StandardCharsets.UTF_8);
        assertThat(xml).doesNotContain("PatternLayoutEncoder");
        int encoders = xml.split("<encoder ", -1).length - 1;
        int switchable = xml.split("com.sitemonitor.config.logging.SwitchableLogEncoder", -1).length - 1;
        assertThat(encoders).isEqualTo(switchable).isGreaterThanOrEqualTo(4);
        assertThat(xml.split("<format>\\$\\{LOG_FORMAT}</format>", -1).length - 1).isEqualTo(encoders);
        assertThat(xml).as("iz kimliği dönüştürücüsü sevk edilen yapılandırmada kayıtlı")
                .contains("<conversionRule conversionWord=\"traceCorrelation\" class=\""
                        + TraceCorrelationConverter.class.getName() + "\"/>");
        assertThat(shippedCorrelationPattern()).contains("%traceCorrelation");
        for (String pat : new String[]{"PAT_CONSOLE", "PAT_FILE", "PAT_AUDIT"}) {
            Matcher m = Pattern.compile("name=\"" + pat + "\"\\s+value=\"([^\"]+)\"").matcher(xml);
            assertThat(m.find()).isTrue();
            assertThat(m.group(1)).as(pat).contains("${PAT_CORR}");
        }
    }
}
