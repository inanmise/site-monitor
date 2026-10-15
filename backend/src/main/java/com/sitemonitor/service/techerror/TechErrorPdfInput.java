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
                                TechErrorTrend trend) {

    /** Trend verisi olmadan (trend bölümü yoksa ya da testlerde): grafikler "en az iki ay" notuna düşer. */
    public TechErrorPdfInput(Template template, Values values, String channelName, String teamName, int year, int month,
                             String preparedBy, Instant generatedAt, Map<String, Image> images) {
        this(template, values, channelName, teamName, year, month, preparedBy, generatedAt, images, TechErrorTrend.empty());
    }

    public TechErrorPdfInput {
        if (trend == null) trend = TechErrorTrend.empty();
    }

    /** Yuvaya yüklenmiş görsel: baytlar + doğrulanmış piksel ölçüsü (en-boy oranı için). */
    public record Image(byte[] data, int width, int height) { }

    /** Aynı girdi, görsel baytları olmadan (PDF üretildikten sonra posta gövdesi için). */
    public TechErrorPdfInput withoutImages() {
        return new TechErrorPdfInput(template, values, channelName, teamName, year, month, preparedBy, generatedAt, Map.of(), trend);
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
