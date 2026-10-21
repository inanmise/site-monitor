package com.sitemonitor.config.logging;

import ch.qos.logback.classic.pattern.ClassicConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;

import java.util.Map;

/**
 * Düz metin günlük satırındaki iz kimliği parçası (2026-10-21): {@code "trace=<id> "} ya da hiçbir şey.
 *
 * <p>Kaynak sırası: önce OpenTelemetry Java ajanının MDC anahtarı {@value #AGENT_KEY} (ajan, logback olayının MDC
 * haritasına kendi iz kimliğini ekler), yoksa Micrometer'ın {@value #MICROMETER_KEY}'i. Ajan varken iki değer zaten
 * aynıdır ({@code AgentTracingConfiguration}); farklı olursa ajanınki, iz arka ucunda bulunan iz olduğu için kazanır.
 * Boş, tamamı sıfır (geçersiz span bağlamı) ya da kimlik biçiminde olmayan değer yazılmaz — satır biçimi bozulmaz.
 * JSON biçimi bu dönüştürücüyü kullanmaz; MDC alanları olduğu gibi yazılır.
 */
public class TraceCorrelationConverter extends ClassicConverter {

    static final String AGENT_KEY = "trace_id";
    static final String MICROMETER_KEY = "traceId";
    private static final int MAX_LENGTH = 64;

    @Override
    public String convert(ILoggingEvent event) {
        Map<String, String> mdc = event.getMDCPropertyMap();
        if (mdc == null || mdc.isEmpty()) return "";
        String id = usable(mdc.get(AGENT_KEY));
        if (id == null) id = usable(mdc.get(MICROMETER_KEY));
        return id == null ? "" : "trace=" + id + " ";
    }

    /** Kimlik biçimi (harf / rakam, ≤ 64) ve en az bir sıfır olmayan karakter; aksi hâlde null. */
    static String usable(String raw) {
        if (raw == null || raw.isEmpty() || raw.length() > MAX_LENGTH) return null;
        boolean nonZero = false;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            boolean alnum = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
            if (!alnum) return null;
            if (c != '0') nonZero = true;
        }
        return nonZero ? raw : null;
    }
}
