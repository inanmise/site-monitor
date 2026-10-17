package com.sitemonitor.service.techerror;

import com.sitemonitor.model.TechErrorChannel;
import com.sitemonitor.repository.TechErrorReportExportRepository;
import com.sitemonitor.repository.TechErrorReportExportRepository.ExportRow;
import com.sitemonitor.repository.TechErrorReportRepository;
import com.sitemonitor.service.techerror.TechErrorTemplate.Field;
import com.sitemonitor.service.techerror.TechErrorTemplate.Template;
import com.sitemonitor.service.techerror.TechErrorValues.Values;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Kanal teknik hata raporu DEĞER dışa aktarımı (E23, 2026-10-18 — kullanıcı onayı: "gönderilmiş rapor değerleri, BI
 * için; kapsam + kişi maskesi kuralları"). Rapor × alan başına bir satır: her rapor KENDİ kopyalanmış şablonuyla okunur
 * (alan kimliği + türü o aya aittir — kanalın bugünkü şablonu değil); yalnız sayı / yüzde / evet-hayır / kısa metin
 * alanları (uzun metin, görsel, tablo, anomali konuları yok). Muaf alanın ({@code skip_if_no} "Hayır") değeri boş,
 * {@code exempt} işaretli; hesaplanan oran ({@code calc}) raporun SON KAYDINDAKİ saklı değeridir, {@code computed}
 * işaretli.
 *
 * <p><b>Kişi verisi yok.</b> İzdüşüm alıcı / gönderen / onaylayan / düzenleyen kimliklerini ve onay notunu okumaz bile;
 * satırda yalnız gönderim ZAMANI ({@code sent_at}) ve onay DURUMU ({@code approval_state}, yalnız onay akışlı kanalda).
 *
 * <p><b>Sabit sorgu sayısı.</b> Raporlar TEK sorguda ({@link TechErrorReportExportRepository#findForExport}) okunur;
 * kanallar ve takım adları çağırandan gelir. Tavan {@link #MAX_REPORTS} rapor: sorgu bir fazlasını ister, aşılırsa
 * {@link TooLargeException} (çağıran 400 {@code EXPORT_TOO_LARGE} döner — süzgeci daraltın).
 */
@Service
public class TechErrorExportService {

    /** Tek dışa aktarımdaki en çok rapor (aşan istek 400 ister: yıl / ay / takım / kanal ile daraltın). */
    public static final int MAX_REPORTS = 5_000;

    /** Satır sütunları — JSON {@code columns} ve CSV başlığı aynı sırayla. */
    public static final List<String> COLUMNS = List.of(
            "team_id", "team_name", "channel_id", "channel_name", "year", "month", "report_id", "report_status",
            "sent_at", "changed_after_send", "approval_state", "field_id", "field_label", "field_type", "value",
            "exempt", "computed");

    /** Dışa aktarılan alan türleri (uzun metin ve görseller hariç). */
    public static final Set<String> FIELD_TYPES = Set.of("number", "percent", "yesno", "text");

    /** Saklama hassasiyeti (TechErrorValues.compact ile aynı: en çok 6 ondalık). */
    static final int SCALE = 6;

    private final TechErrorReportExportRepository repo;

    public TechErrorExportService(TechErrorReportExportRepository repo) {
        this.repo = repo;
    }

    /**
     * Süzgeç. {@code year} null = tüm yıllar (yalnız tek kanal dışa aktarımında); {@code month} null = tüm aylar;
     * {@code sentOnly} = yalnız en az bir kez gönderilmiş raporlar.
     */
    public record Filter(Integer year, Integer month, boolean sentOnly) { }

    /** Rapor başına özet (kişi verisi yok). */
    public record ReportMeta(Long teamId, String teamName, Long channelId, String channelName, int year, int month,
                             Long reportId, String status, Instant sentAt, boolean changedAfterSend, String approvalState,
                             int fieldCount) { }

    public record Result(List<ReportMeta> reports, List<List<Object>> rows) { }

    /** Tavan aşıldı ({@link #MAX_REPORTS}). */
    public static class TooLargeException extends RuntimeException {
        public TooLargeException() {
            super("EXPORT_TOO_LARGE");
        }
    }

    /**
     * Dışa aktarır. {@code channels}: çağıranın OKUMA kapsamından geçmiş kanallar (takım + kanal süzgeci uygulanmış);
     * boşsa sorgu yapılmaz. {@code teamNames}: takım kimliği → ad. {@code now}: durum hesabı ("gönderiliyor" talebinin
     * tazeliği) için.
     */
    public Result export(List<TechErrorChannel> channels, Map<Long, String> teamNames, Filter f, Instant now) {
        if (channels == null || channels.isEmpty()) return new Result(List.of(), List.of());
        Map<Long, TechErrorChannel> byId = new LinkedHashMap<>();
        Map<Long, Integer> order = new HashMap<>();
        for (TechErrorChannel ch : channels) {
            if (ch == null || ch.getId() == null) continue;
            byId.putIfAbsent(ch.getId(), ch);
            order.putIfAbsent(ch.getId(), order.size());
        }
        if (byId.isEmpty()) return new Result(List.of(), List.of());
        int y1 = f.year() == null ? 0 : f.year();
        int y2 = f.year() == null ? 9999 : f.year();
        int m1 = f.month() == null ? 1 : f.month();
        int m2 = f.month() == null ? 12 : f.month();
        List<ExportRow> found = repo.findForExport(byId.keySet(), y1, y2, m1, m2, f.sentOnly(), Limit.of(MAX_REPORTS + 1));
        if (found.size() > MAX_REPORTS) throw new TooLargeException();

        List<ExportRow> sorted = new ArrayList<>(found);
        java.text.Collator col = java.text.Collator.getInstance(Locale.forLanguageTag("tr"));
        col.setStrength(java.text.Collator.SECONDARY);
        Map<Long, String> names = teamNames == null ? Map.of() : teamNames;
        sorted.sort(Comparator.<ExportRow, String>comparing(r -> names.getOrDefault(r.getTeamId(), ""), col)
                .thenComparing(ExportRow::getTeamId, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(r -> order.getOrDefault(r.getChannelId(), Integer.MAX_VALUE))
                .thenComparing(ExportRow::getReportYear)
                .thenComparing(ExportRow::getReportMonth)
                .thenComparing(ExportRow::getId));

        // Aynı kanalın ayları çoğu zaman AYNI kopyalanmış şablonu taşır: metin başına bir kez ayrıştırılır
        Map<String, Template> templates = new HashMap<>();
        List<ReportMeta> metas = new ArrayList<>(sorted.size());
        List<List<Object>> rows = new ArrayList<>();
        for (ExportRow r : sorted) {
            TechErrorChannel ch = byId.get(r.getChannelId());
            if (ch == null) continue;
            String tj = r.getTemplateJson() == null ? "" : r.getTemplateJson();
            Template t = templates.computeIfAbsent(tj, TechErrorTemplate::fromStored);
            Values v = TechErrorValues.fromStored(r.getValuesJson(), t);
            String status = TechErrorReportService.reportStatus(asRow(r), now);
            boolean changed = "changed".equals(status);
            String approval = TechErrorApprovalService.approvalRequired(ch)
                    ? approvalState(r.getApprovalState(), r.getApprovalRev(), r.getContentRev()) : null;
            String teamName = names.get(r.getTeamId());
            int year = r.getReportYear() == null ? 0 : r.getReportYear();
            int month = r.getReportMonth() == null ? 0 : r.getReportMonth();
            String sentAt = r.getLastSentAt() == null ? null : r.getLastSentAt().toString();
            int fieldCount = 0;
            for (Field fd : t.allFields()) {
                if (!FIELD_TYPES.contains(fd.type())) continue;
                boolean exempt = TechErrorTemplate.exempt(fd, v.fields());
                Object value = exempt ? null : exportValue(fd, v.fields().get(fd.id()));
                String label = TechErrorTemplate.resolve(fd.label(), ch.getName(), year, month, t.lang());
                rows.add(Collections.unmodifiableList(Arrays.asList(r.getTeamId(), teamName, ch.getId(), ch.getName(),
                        year, month, r.getId(), status, sentAt, changed, approval, fd.id(), label, fd.type(), value,
                        exempt, fd.calc() != null)));
                fieldCount++;
            }
            metas.add(new ReportMeta(r.getTeamId(), teamName, ch.getId(), ch.getName(), year, month, r.getId(), status,
                    r.getLastSentAt(), changed, approval, fieldCount));
        }
        return new Result(metas, rows);
    }

    /**
     * Ham değer: sayı / yüzde düz ondalık ({@link BigDecimal}, bilimsel gösterim yok, en çok 6 ondalık), evet/hayır
     * {@code Boolean}, kısa metin {@code String}; boş ya da türüne uymayan değer {@code null}.
     */
    static Object exportValue(Field f, Object raw) {
        if (raw == null || f == null) return null;
        return switch (f.type()) {
            case "number", "percent" -> raw instanceof Number n ? plainDecimal(n) : null;
            case "yesno" -> raw instanceof Boolean b ? b : null;
            case "text" -> raw instanceof String s ? (s.isEmpty() ? null : s) : String.valueOf(raw);
            default -> null;
        };
    }

    /** Sayıyı düz ondalığa çevirir: tam sayı kesirsiz, kesirli en çok 6 ondalık; ölçek eksiye düşmez ("1.2E+4" yazılmaz). */
    static BigDecimal plainDecimal(Number n) {
        if (n instanceof Long || n instanceof Integer || n instanceof Short || n instanceof Byte) {
            return BigDecimal.valueOf(n.longValue());
        }
        double d = n.doubleValue();
        if (!Double.isFinite(d)) return null;
        BigDecimal b = BigDecimal.valueOf(d).setScale(SCALE, RoundingMode.HALF_UP).stripTrailingZeros();
        return b.scale() < 0 ? b.setScale(0) : b;
    }

    /**
     * Onay durumu — rapor listesinin onay bloğuyla AYNI kural ({@code TechErrorReportController.approvalDto}): none |
     * pending | approved | rejected | stale (istek ya da onay, içerik değiştiği için eskidi).
     */
    public static String approvalState(String state, Long approvalRev, Long contentRev) {
        if (state == null) return "none";
        long content = contentRev == null ? 0L : contentRev;
        boolean stale = !"REJECTED".equals(state) && approvalRev != null && content > approvalRev;
        return stale ? "stale" : state.toLowerCase(Locale.ROOT);
    }

    /** Durum hesabının tek kaynağı satır görünümü ister: yalnız gereken alanlar dolu (kişi alanları boş). */
    static TechErrorReportRepository.Row asRow(ExportRow r) {
        return new TechErrorReportRepository.Row() {
            public Long getId() { return r.getId(); }
            public Long getChannelId() { return r.getChannelId(); }
            public Long getTeamId() { return r.getTeamId(); }
            public Integer getReportYear() { return r.getReportYear(); }
            public Integer getReportMonth() { return r.getReportMonth(); }
            public String getTitlePattern() { return null; }
            public String getTemplateLang() { return null; }
            public Instant getCreatedAt() { return null; }
            public Instant getUpdatedAt() { return null; }
            public Long getUpdatedById() { return null; }
            public Instant getContentChangedAt() { return r.getContentChangedAt(); }
            public Long getContentRev() { return r.getContentRev(); }
            public Long getSentRev() { return r.getSentRev(); }
            public String getSendState() { return r.getSendState(); }
            public Instant getSendClaimedAt() { return r.getSendClaimedAt(); }
            public Instant getLastSentAt() { return r.getLastSentAt(); }
            public Long getLastSentById() { return null; }
            public String getLastSentTo() { return null; }
            public Instant getLastAttemptAt() { return null; }
            public String getLastSendStatus() { return null; }
            public int getSendCount() { return 0; }
            public String getApprovalState() { return r.getApprovalState(); }
            public Long getApprovalRev() { return r.getApprovalRev(); }
            public Instant getApprovalRequestedAt() { return null; }
            public Long getApprovalRequestedById() { return null; }
            public Instant getApprovalDecidedAt() { return null; }
            public Long getApprovalDecidedById() { return null; }
            public String getApprovalNote() { return null; }
        };
    }
}
