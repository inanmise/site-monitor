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
 * {@code exempt} işaretli. Hesaplanan alanlar ({@code calc}) PDF / e-posta gibi CANLI hesaplanır
 * ({@code TechErrorReportService.pdfInput} ile aynı: {@link TechErrorCalc#apply} + aynı kanalın bir önceki takvim ayının
 * raporu, {@link TechErrorReportService#sameTypeFields} kuralıyla); {@code computed} yalnız sonuç {@code OK} iken doğrudur —
 * formül kullanılamadığında (MANUAL / BAD_REF: ör. kanalın ilk raporunda değişim oranı) girilen değer kalır,
 * {@code computed = false}.
 *
 * <p><b>Kişi verisi yok.</b> İzdüşüm alıcı / gönderen / onaylayan / düzenleyen kimliklerini ve onay notunu okumaz bile;
 * satırda yalnız gönderim ZAMANI ({@code sent_at}) ve onay DURUMU ({@code approval_state}, yalnız onay akışlı kanalda).
 *
 * <p><b>Sabit sorgu sayısı (iki).</b> Önce ucuz bir SAYIM ({@link TechErrorReportExportRepository#countForExport}, JSON
 * okunmaz): {@link #MAX_REPORTS} raporu aşan süzgeç hiçbir şey yüklenmeden {@link TooLargeException} (çağıran 400
 * {@code EXPORT_TOO_LARGE} döner — süzgeci daraltın). Sonra TEK yükleme sorgusu
 * ({@link TechErrorReportExportRepository#findForExport}): dışa aktarılanlar + her birinin önceki ayı (en çok iki kat).
 * Kanallar ve takım adları çağırandan gelir.
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
        // Ay yalnız yılla anlamlıdır (denetleyici yılsız ayı 400 ile reddeder); yıl yok = tüm yıllar
        int pFrom = f.year() == null ? period(0, 1) : period(f.year(), f.month() == null ? 1 : f.month());
        int pTo = f.year() == null ? period(9999, 12) : period(f.year(), f.month() == null ? 12 : f.month());

        // 1) Ucuz sayım (JSON okunmaz): tavanı aşan istek belleğe hiçbir şey yüklemeden reddedilir
        long count = repo.countForExport(byId.keySet(), pFrom, pTo, f.sentOnly());
        if (count > MAX_REPORTS) throw new TooLargeException();
        if (count == 0) return new Result(List.of(), List.of());
        // 2) Tek yükleme: dışa aktarılanlar + her birinin önceki takvim ayı (canlı hesap için) — en çok iki kat
        List<ExportRow> found = repo.findForExport(byId.keySet(), pFrom - 1, pFrom, pTo, f.sentOnly(),
                Limit.of(2 * MAX_REPORTS + 1));
        if (found.size() > 2 * MAX_REPORTS) throw new TooLargeException();
        Map<String, ExportRow> byPeriod = new HashMap<>();
        List<ExportRow> sorted = new ArrayList<>();
        for (ExportRow r : found) {
            if (r.getChannelId() == null || r.getReportYear() == null || r.getReportMonth() == null) continue;
            byPeriod.put(r.getChannelId() + ":" + period(r.getReportYear(), r.getReportMonth()), r);
            int p = period(r.getReportYear(), r.getReportMonth());
            if (p >= pFrom && p <= pTo && (!f.sentOnly() || r.getLastSentAt() != null)) sorted.add(r);
        }
        // Sayım ile yükleme arasında açılan raporlar tavanı aşırdıysa yine reddedilir
        if (sorted.size() > MAX_REPORTS) throw new TooLargeException();

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
            Template t = template(templates, r.getTemplateJson());
            Values stored = TechErrorValues.fromStored(r.getValuesJson(), t);
            // Hesaplanan alanlar PDF gibi CANLI (TechErrorReportService.pdfInput): aynı kanalın bir önceki takvim ayının
            // raporu, bu şablondaki alanlarla AYNI kimlik ve türdeki değerleri (sameTypeFields — tek kural)
            ExportRow prev = byPeriod.get(r.getChannelId() + ":" + (period(r.getReportYear(), r.getReportMonth()) - 1));
            Map<String, Object> previous = null;
            if (prev != null) {
                Template pt = template(templates, prev.getTemplateJson());
                previous = TechErrorReportService.sameTypeFields(pt, TechErrorValues.fromStored(prev.getValuesJson(), pt), t);
            }
            TechErrorCalc.Applied applied = TechErrorCalc.applyDetailed(t, stored, previous);
            Map<String, Object> live = applied.values().fields();
            String status = TechErrorReportService.reportStatus(asRow(r), now);
            // Durumdan BAĞIMSIZ: taze bir gönderim talebi ("sending") sürerken de gönderilenden farklı olabilir
            boolean changed = TechErrorReportService.changedAfterSend(asRow(r));
            String approval = TechErrorApprovalService.approvalRequired(ch)
                    ? approvalState(r.getApprovalState(), r.getApprovalRev(), r.getContentRev()) : null;
            String teamName = names.get(r.getTeamId());
            int year = r.getReportYear();
            int month = r.getReportMonth();
            String sentAt = r.getLastSentAt() == null ? null : r.getLastSentAt().toString();
            int fieldCount = 0;
            for (Field fd : t.allFields()) {
                if (!FIELD_TYPES.contains(fd.type())) continue;
                boolean exempt = TechErrorTemplate.exempt(fd, stored.fields());
                Object value = exempt ? null : exportValue(fd, live.get(fd.id()));
                boolean computed = !exempt && applied.status(fd.id()) == TechErrorCalc.Status.OK;
                String label = TechErrorTemplate.resolve(fd.label(), ch.getName(), year, month, t.lang());
                rows.add(Collections.unmodifiableList(Arrays.asList(r.getTeamId(), teamName, ch.getId(), ch.getName(),
                        year, month, r.getId(), status, sentAt, changed, approval, fd.id(), label, fd.type(), value,
                        exempt, computed)));
                fieldCount++;
            }
            metas.add(new ReportMeta(r.getTeamId(), teamName, ch.getId(), ch.getName(), year, month, r.getId(), status,
                    r.getLastSentAt(), changed, approval, fieldCount));
        }
        return new Result(metas, rows);
    }

    /** Dönem numarası: {@code yıl × 12 + ay} — ardışık aylar ardışık sayılar (Ocak'ın öncesi önceki yılın Aralık'ı). */
    static int period(int year, int month) {
        return year * 12 + month;
    }

    private static Template template(Map<String, Template> cache, String json) {
        return cache.computeIfAbsent(json == null ? "" : json, TechErrorTemplate::fromStored);
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
