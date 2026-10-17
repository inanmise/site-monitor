package com.sitemonitor.service.techerror;

import com.sitemonitor.service.techerror.TechErrorTemplate.Template;
import com.sitemonitor.service.techerror.TechErrorValues.Values;

import java.time.Instant;
import java.util.Map;

/**
 * PDF yazıcısının girdisi (2026-10-13): raporun kopyalanmış şablonu, değerleri, yuva → görsel ve kapak bilgileri.
 * Yazıcı ({@code service.report.TechErrorReportPdfWriter}) veritabanına dokunmaz; testler doğrudan kurar.
 */
public record TechErrorPdfInput(Template template, Values values, String channelName, String teamName, int year,
                                int month, String preparedBy, Instant generatedAt, Map<String, Image> images,
                                TechErrorTrend trend, Map<String, Object> previous) {

    /** Trend verisi olmadan (trend bölümü yoksa ya da testlerde): grafikler "en az iki ay" notuna düşer. */
    public TechErrorPdfInput(Template template, Values values, String channelName, String teamName, int year, int month,
                             String preparedBy, Instant generatedAt, Map<String, Image> images) {
        this(template, values, channelName, teamName, year, month, preparedBy, generatedAt, images, TechErrorTrend.empty(), Map.of());
    }

    /** Geçen ayın değerleri olmadan (2026-10-17 öncesi imza): kutucuk farkları ve {@code {d:…}} cümleleri yok. */
    public TechErrorPdfInput(Template template, Values values, String channelName, String teamName, int year, int month,
                             String preparedBy, Instant generatedAt, Map<String, Image> images, TechErrorTrend trend) {
        this(template, values, channelName, teamName, year, month, preparedBy, generatedAt, images, trend, Map.of());
    }

    public TechErrorPdfInput {
        if (trend == null) trend = TechErrorTrend.empty();
        if (previous == null) previous = Map.of();
    }

    /** Yuvaya yüklenmiş görsel: baytlar + doğrulanmış piksel ölçüsü (en-boy oranı için). */
    public record Image(byte[] data, int width, int height) { }

    /** Aynı girdi, görsel baytları olmadan (PDF üretildikten sonra posta gövdesi için). */
    public TechErrorPdfInput withoutImages() {
        return new TechErrorPdfInput(template, values, channelName, teamName, year, month, preparedBy, generatedAt, Map.of(), trend, previous);
    }

    /**
     * Aynı girdi, geçen ayın aynı kimlik / türdeki değerleriyle (2026-10-17, 4b): kutucuk farkları, {@code {d:…}} cümleleri.
     * {@code null} = geçen ayın raporu yok (fark gösterilmez).
     */
    public TechErrorPdfInput withPrevious(Map<String, Object> prev) {
        return new TechErrorPdfInput(template, values, channelName, teamName, year, month, preparedBy, generatedAt, images, trend,
                prev == null ? Map.of() : prev);
    }

    /** Geçen ayın değerleri ya da {@code null} (rapor yok) — fark hesapları "yok" ile "boş" arasında ayrım yapmaz. */
    public Map<String, Object> previousOrNull() {
        return previous.isEmpty() ? null : previous;
    }

    public String title() {
        return TechErrorTemplate.resolve(template.title(), channelName, year, month, template.lang());
    }

    public String resolve(String pattern) {
        return TechErrorTemplate.resolve(pattern, channelName, year, month, template.lang());
    }

    /** Metin bölümü gövdesi (daha geniş sınır). */
    public String resolveText(String pattern) {
        return TechErrorTemplate.resolveText(pattern, channelName, year, month, template.lang());
    }
}
