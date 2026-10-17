package com.sitemonitor.service.techerror;

import com.sitemonitor.service.techerror.TechErrorTemplate.Template;
import com.sitemonitor.service.techerror.TechErrorValues.Values;

import java.time.Instant;
import java.util.Map;

/**
 * PDF yazıcısının girdisi (2026-10-13): raporun kopyalanmış şablonu, değerleri, yuva → görsel ve kapak bilgileri.
 * Yazıcı ({@code service.report.TechErrorReportPdfWriter}) veritabanına dokunmaz; testler doğrudan kurar.
 *
 * <p>{@code sample} (2026-10-17, R19): şablon önizlemesi — değerler {@link TechErrorSampleValues} ile uydurulmuştur; yazıcı
 * ilk sayfaya "ÖRNEK ÖNİZLEME" şeridini basar. Gerçek raporlarda her zaman {@code false} (çıktı aynı kalır).
 */
public record TechErrorPdfInput(Template template, Values values, String channelName, String teamName, int year,
                                int month, String preparedBy, Instant generatedAt, Map<String, Image> images,
                                TechErrorTrend trend, Map<String, Object> previous, Provenance provenance, boolean sample) {

    /** Gerçek rapor (örnek değil) — 2026-10-17 öncesi tam imza. */
    public TechErrorPdfInput(Template template, Values values, String channelName, String teamName, int year, int month,
                             String preparedBy, Instant generatedAt, Map<String, Image> images, TechErrorTrend trend,
                             Map<String, Object> previous, Provenance provenance) {
        this(template, values, channelName, teamName, year, month, preparedBy, generatedAt, images, trend, previous, provenance, false);
    }

    /** Kimlik satırları olmadan (2026-10-17 öncesi imza): band / kapak eski "Hazırlayan · Üretim" satırını basar. */
    public TechErrorPdfInput(Template template, Values values, String channelName, String teamName, int year, int month,
                             String preparedBy, Instant generatedAt, Map<String, Image> images, TechErrorTrend trend,
                             Map<String, Object> previous) {
        this(template, values, channelName, teamName, year, month, preparedBy, generatedAt, images, trend, previous, null);
    }

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

    /**
     * Raporun kimliği (2026-10-17, 10. sürüm — kullanıcı isteği: "oluşturan kişi, onaylayan kişi bilgileri gözükmeli"):
     * oluşturan, son düzenleyen ve — onay akışlı kanalda — onaylayan. Posta bandı, PDF kapağı ve önizleme penceresi aynı
     * kaynağı basar. {@code approvalState}: {@code null} = kanalda onay akışı yok (satır basılmaz); yoksa
     * {@code none | pending | approved | rejected | stale} — yalnız {@code approved} (onay bu içeriği kapsıyor) onaylayanı taşır.
     * Üretimdeki "Hazırlayan" (son düzenleyen ya da oluşturan) {@code preparedBy} olarak kalır.
     */
    public record Provenance(String createdBy, Instant createdAt, String updatedBy, Instant updatedAt,
                             String approvalState, String approvedBy, Instant approvedAt) {
        public boolean approved() { return "approved".equals(approvalState); }
        /** Son düzenleyen oluşturandan farklıysa ayrıca basılır (aynı kişi aynı gün → tek satır). */
        public boolean updatedDiffers() {
            return updatedBy != null && !updatedBy.isBlank() && !updatedBy.equals(createdBy);
        }
    }

    public TechErrorPdfInput withProvenance(Provenance p) {
        return new TechErrorPdfInput(template, values, channelName, teamName, year, month, preparedBy, generatedAt, images, trend, previous, p,
                sample);
    }

    /** Aynı girdi, görsel baytları olmadan (PDF üretildikten sonra posta gövdesi için). */
    public TechErrorPdfInput withoutImages() {
        return new TechErrorPdfInput(template, values, channelName, teamName, year, month, preparedBy, generatedAt, Map.of(), trend, previous,
                provenance, sample);
    }

    /**
     * Aynı girdi, geçen ayın aynı kimlik / türdeki değerleriyle (2026-10-17, 4b): kutucuk farkları, {@code {d:…}} cümleleri.
     * {@code null} = geçen ayın raporu yok (fark gösterilmez).
     */
    public TechErrorPdfInput withPrevious(Map<String, Object> prev) {
        return new TechErrorPdfInput(template, values, channelName, teamName, year, month, preparedBy, generatedAt, images, trend,
                prev == null ? Map.of() : prev, provenance, sample);
    }

    /** Aynı girdi, şablon önizlemesi işaretiyle (R19). */
    public TechErrorPdfInput withSample(boolean s) {
        return new TechErrorPdfInput(template, values, channelName, teamName, year, month, preparedBy, generatedAt, images, trend, previous,
                provenance, s);
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
