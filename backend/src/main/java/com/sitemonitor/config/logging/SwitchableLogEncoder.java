package com.sitemonitor.config.logging;

import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.encoder.Encoder;
import ch.qos.logback.core.encoder.EncoderBase;
import org.springframework.boot.logging.logback.StructuredLogEncoder;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Günlük biçimi anahtarı (2026-10-11, kullanıcı kararı: "Prod JSON, yerel düz metin"). Tek appender'ın kodlayıcısı:
 * {@code format} = {@code text} (varsayılan) → {@link PatternLayoutEncoder} ({@code pattern}); {@code json} / {@code ecs}
 * → Spring Boot {@link StructuredLogEncoder} (Elastic Common Schema; MDC alanları — {@code requestId}, {@code traceId},
 * {@code spanId} — satıra eklenir); {@code logstash} / {@code gelf} doğrudan Boot biçimi.
 *
 * <p>Neden ayrı sınıf: logback'te koşullu yapılandırma Janino ister (sınıf yolunda yok) ve aynı dosyaya yazan iki appender
 * çakışır. Bilinmeyen biçim ya da JSON kodlayıcının açılamaması (Spring ortamı yok — örn. Boot dışı bir bağlam) düz
 * metne düşer ve logback durumuna UYARI yazar; günlük hiçbir koşulda kapanmaz.
 */
public class SwitchableLogEncoder extends EncoderBase<ILoggingEvent> {

    private String format = "text";
    private String jsonFormat = "ecs";
    private String pattern;
    private Charset charset = StandardCharsets.UTF_8;
    private Encoder<ILoggingEvent> delegate;

    public void setFormat(String format) {
        this.format = format;
    }

    /** {@code format=json} iken kullanılan Boot biçimi: ecs (varsayılan) | logstash | gelf. */
    public void setJsonFormat(String jsonFormat) {
        this.jsonFormat = jsonFormat;
    }

    public void setPattern(String pattern) {
        this.pattern = pattern;
    }

    public void setCharset(Charset charset) {
        if (charset != null) this.charset = charset;
    }

    /** Etkin kip: "text" ya da Boot yapılandırılmış biçim adı (test ve tanı için). */
    public String effectiveFormat() {
        return delegate instanceof StructuredLogEncoder ? structuredName() : "text";
    }

    static String normalize(String raw) {
        String f = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        return switch (f) {
            case "json", "ecs", "logstash", "gelf" -> f;
            default -> "text";
        };
    }

    private String structuredName() {
        String f = normalize(format);
        if (!"json".equals(f)) return f;
        String j = normalize(jsonFormat);
        return switch (j) {
            case "logstash", "gelf" -> j;
            default -> "ecs";
        };
    }

    @Override
    public void start() {
        String f = normalize(format);
        if (!"text".equals(f)) {
            try {
                StructuredLogEncoder json = new StructuredLogEncoder();
                json.setContext(getContext());
                json.setFormat(structuredName());
                json.setCharset(charset);
                json.start();
                delegate = json;
            } catch (RuntimeException e) {
                addWarn("JSON günlük kodlayıcısı açılamadı (" + e.getMessage() + ") — düz metne dönülüyor", e);
                delegate = null;
            }
        } else if (format != null && !format.isBlank() && !"text".equalsIgnoreCase(format.trim())) {
            addWarn("Bilinmeyen günlük biçimi '" + format + "' — düz metin kullanılıyor (text | json | ecs | logstash | gelf)");
        }
        if (delegate == null) {
            PatternLayoutEncoder text = new PatternLayoutEncoder();
            text.setContext(getContext());
            text.setPattern(pattern == null || pattern.isBlank() ? "%d %-5level [%thread] %logger : %msg%n" : pattern);
            text.setCharset(charset);
            text.start();
            delegate = text;
        }
        super.start();
    }

    @Override
    public void stop() {
        if (delegate != null) delegate.stop();
        super.stop();
    }

    @Override
    public byte[] headerBytes() {
        return delegate == null ? null : delegate.headerBytes();
    }

    @Override
    public byte[] encode(ILoggingEvent event) {
        return delegate == null ? new byte[0] : delegate.encode(event);
    }

    @Override
    public byte[] footerBytes() {
        return delegate == null ? null : delegate.footerBytes();
    }
}
