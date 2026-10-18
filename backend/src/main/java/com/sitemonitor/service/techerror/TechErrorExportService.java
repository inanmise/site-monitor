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
 * raporu, {@link TechErrorReportService#sameTypeFields} kuralıyla); {@code computed} yalnız sonuç {@code OK} iken doğrudur.
 * {@code calc_status} hesaplanan alanın SONUCUNU söyler ({@link TechErrorCalc.Status} adı): OK = hesaplandı; MISSING / ZERO /
 * NEGATIVE / EQUAL / RANGE = formül var ama bu ay sonuç yok (değer boş — elle girilmiş değil); MANUAL / BAD_REF = formül
 * kullanılamıyor (ör. kanalın ilk raporunda değişim oranı), girilen değer kalır. Hesabı olmayan (ya da muaf) alanda boş.
 *
 * <p><b>Bellek ≈ 1×.</b> Yüklenen satırlar TEK geçişte ayrıştırılır ({@link Loaded}: durum sütunları + paylaşılan şablon +
 * yalnız alan / hedef değerleri) ve her satırın JSON metinleri ayrıştırılır ayrıştırılmaz bırakılır (listenin yuvası
 * boşaltılır); şablon önbelleği metnin kendisini değil SHA-256 özetini anahtar tutar.
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
            "exempt", "computed", "calc_status");

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
        // Kendi kopyamız: Spring Data'nın listesi değiştirilemez olabilir; her yuva satırı ayrıştırılır ayrıştırılmaz
        // boşaltılır → JSON metinleri (şablon ≤ 64 KB, değerler ≤ 256 KB) ayrıştırılmış nesnelerle birlikte yaşamaz
        List<ExportRow> found = new ArrayList<>(repo.findForExport(byId.keySet(), pFrom - 1, pFrom, pTo, f.sentOnly(),
                Limit.of(2 * MAX_REPORTS + 1)));
        if (found.size() > 2 * MAX_REPORTS) throw new TooLargeException();
        // Ucuz ön geçiş (JSON'a dokunmaz): sayım ile yükleme arasında açılan raporlar tavanı aştıysa ayrıştırmadan reddedilir
        int exported = 0;
        for (ExportRow r : found) {
            if (valid(r) && exported(r, pFrom, pTo, f.sentOnly())) exported++;
        }
        if (exported > MAX_REPORTS) throw new TooLargeException();

        Map<String, Template> templates = new HashMap<>();
        java.security.MessageDigest sha = sha256();
        Map<String, Loaded> byPeriod = new HashMap<>();
        List<Loaded> sorted = new ArrayList<>(exported);
        for (int i = 0; i < found.size(); i++) {
            ExportRow r = found.set(i, null);
            if (!valid(r)) continue;
            Loaded l = load(r, templates, sha);
            byPeriod.put(l.channelId + ":" + l.period, l);
            if (exported(r, pFrom, pTo, f.sentOnly())) sorted.add(l);
        }
        found = null;

        java.text.Collator col = java.text.Collator.getInstance(Locale.forLanguageTag("tr"));
        col.setStrength(java.text.Collator.SECONDARY);
        Map<Long, String> names = teamNames == null ? Map.of() : teamNames;
        sorted.sort(Comparator.<Loaded, String>comparing(r -> names.getOrDefault(r.teamId, ""), col)
                .thenComparing(r -> r.teamId, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(r -> order.getOrDefault(r.channelId, Integer.MAX_VALUE))
                .thenComparingInt(r -> r.year)
                .thenComparingInt(r -> r.month)
                .thenComparing(r -> r.id, Comparator.nullsLast(Comparator.naturalOrder())));

        List<ReportMeta> metas = new ArrayList<>(sorted.size());
        List<List<Object>> rows = new ArrayList<>();
        for (Loaded r : sorted) {
            TechErrorChannel ch = byId.get(r.channelId);
            if (ch == null) continue;
            Template t = r.template;
            Values stored = r.values;
            // Hesaplanan alanlar PDF gibi CANLI (TechErrorReportService.pdfInput): aynı kanalın bir önceki takvim ayının
            // raporu, bu şablondaki alanlarla AYNI kimlik ve türdeki değerleri (sameTypeFields — tek kural)
            Loaded prev = byPeriod.get(r.channelId + ":" + (r.period - 1));
            Map<String, Object> previous = prev == null ? null
                    : TechErrorReportService.sameTypeFields(prev.template, prev.values, t);
            TechErrorCalc.Applied applied = TechErrorCalc.applyDetailed(t, stored, previous);
            Map<String, Object> live = applied.values().fields();
            String status = TechErrorReportService.reportStatus(asRow(r), now);
            // Durumdan BAĞIMSIZ: taze bir gönderim talebi ("sending") sürerken de gönderilenden farklı olabilir
            boolean changed = TechErrorReportService.changedAfterSend(asRow(r));
            String approval = TechErrorApprovalService.approvalRequired(ch)
                    ? approvalState(r.approvalState, r.approvalRev, r.contentRev) : null;
            String teamName = names.get(r.teamId);
            int year = r.year;
            int month = r.month;
            String sentAt = r.lastSentAt == null ? null : r.lastSentAt.toString();
            int fieldCount = 0;
            for (Field fd : t.allFields()) {
                if (!FIELD_TYPES.contains(fd.type())) continue;
                boolean exempt = TechErrorTemplate.exempt(fd, stored.fields());
                Object value = exempt ? null : exportValue(fd, live.get(fd.id()));
                TechErrorCalc.Status cs = exempt || fd.calc() == null ? null : applied.status(fd.id());
                boolean computed = cs == TechErrorCalc.Status.OK;
                String label = TechErrorTemplate.resolve(fd.label(), ch.getName(), year, month, t.lang());
                rows.add(Collections.unmodifiableList(Arrays.asList(r.teamId, teamName, ch.getId(), ch.getName(),
                        year, month, r.id, status, sentAt, changed, approval, fd.id(), label, fd.type(), value,
                        exempt, computed, cs == null ? null : cs.name())));
                fieldCount++;
            }
            metas.add(new ReportMeta(r.teamId, teamName, ch.getId(), ch.getName(), year, month, r.id, status,
                    r.lastSentAt, changed, approval, fieldCount));
        }
        return new Result(metas, rows);
    }

    private static boolean valid(ExportRow r) {
        return r != null && r.getChannelId() != null && r.getReportYear() != null && r.getReportMonth() != null;
    }

    /** Satır dışa aktarılır mı (aralıkta + durum süzgeci) — değilse yalnız bir sonraki ayın canlı hesabı için yüklenmiştir. */
    private static boolean exported(ExportRow r, int pFrom, int pTo, boolean sentOnly) {
        int p = period(r.getReportYear(), r.getReportMonth());
        return p >= pFrom && p <= pTo && (!sentOnly || r.getLastSentAt() != null);
    }

    /**
     * Ayrıştırılmış rapor satırı — JSON metni TUTMAZ. Şablon önbellekten paylaşılır; değerlerden yalnız dışa aktarımın ve
     * canlı hesabın okuduğu alan + hedef haritaları kalır (uzun metinler, anomali konuları, tablolar atılır).
     */
    static final class Loaded {
        final Long id;
        final Long channelId;
        final Long teamId;
        final int year;
        final int month;
        final int period;
        final Instant contentChangedAt;
        final Long contentRev;
        final Long sentRev;
        final String sendState;
        final Instant sendClaimedAt;
        final Instant lastSentAt;
        final String approvalState;
        final Long approvalRev;
        final Template template;
        final Values values;

        private Loaded(ExportRow r, Template template, Values values) {
            this.id = r.getId();
            this.channelId = r.getChannelId();
            this.teamId = r.getTeamId();
            this.year = r.getReportYear();
            this.month = r.getReportMonth();
            this.period = period(year, month);
            this.contentChangedAt = r.getContentChangedAt();
            this.contentRev = r.getContentRev();
            this.sentRev = r.getSentRev();
            this.sendState = r.getSendState();
            this.sendClaimedAt = r.getSendClaimedAt();
            this.lastSentAt = r.getLastSentAt();
            this.approvalState = r.getApprovalState();
            this.approvalRev = r.getApprovalRev();
            this.template = template;
            this.values = values;
        }
    }

    /** Satırı bir kez ayrıştırır: şablon özetine göre paylaşılır (aynı kanalın ayları çoğu zaman AYNI şablonu taşır). */
    private static Loaded load(ExportRow r, Map<String, Template> templates, java.security.MessageDigest sha) {
        String tj = r.getTemplateJson();
        String key = tj == null || tj.isEmpty() ? "" : java.util.HexFormat.of().formatHex(
                sha.digest(tj.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        Template t = templates.get(key);
        if (t == null) {
            t = TechErrorTemplate.fromStored(tj);
            templates.put(key, t);
        }
        Values v = TechErrorValues.fromStored(r.getValuesJson(), t);
        Values slim = new Values(v.fields(), Map.of(), Map.of(), Map.of(), v.targets());
        return new Loaded(r, t, slim);
    }

    private static java.security.MessageDigest sha256() {
        try {
            return java.security.MessageDigest.getInstance("SHA-256");
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 yok", e);        // her JDK'da zorunlu algoritma
        }
    }

    /** Dönem numarası: {@code yıl × 12 + ay} — ardışık aylar ardışık sayılar (Ocak'ın öncesi önceki yılın Aralık'ı). */
    static int period(int year, int month) {
        return year * 12 + month;
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
    static TechErrorReportRepository.Row asRow(Loaded r) {
        return new TechErrorReportRepository.Row() {
            public Long getId() { return r.id; }
            public Long getChannelId() { return r.channelId; }
            public Long getTeamId() { return r.teamId; }
            public Integer getReportYear() { return r.year; }
            public Integer getReportMonth() { return r.month; }
            public Integer getTemplateVersion() { return null; }   // durum hesabı kullanmaz (D19 alanı)
            public String getTitlePattern() { return null; }
            public String getTemplateLang() { return null; }
            public Instant getCreatedAt() { return null; }
            public Instant getUpdatedAt() { return null; }
            public Long getUpdatedById() { return null; }
            public Instant getContentChangedAt() { return r.contentChangedAt; }
            public Long getContentRev() { return r.contentRev; }
            public Long getSentRev() { return r.sentRev; }
            public String getSendState() { return r.sendState; }
            public Instant getSendClaimedAt() { return r.sendClaimedAt; }
            public Instant getLastSentAt() { return r.lastSentAt; }
            public Long getLastSentById() { return null; }
            public String getLastSentTo() { return null; }
            public Instant getLastAttemptAt() { return null; }
            public String getLastSendStatus() { return null; }
            public int getSendCount() { return 0; }
            public String getApprovalState() { return r.approvalState; }
            public Long getApprovalRev() { return r.approvalRev; }
            public Instant getApprovalRequestedAt() { return null; }
            public Long getApprovalRequestedById() { return null; }
            public Instant getApprovalDecidedAt() { return null; }
            public Long getApprovalDecidedById() { return null; }
            public String getApprovalNote() { return null; }
        };
    }
}
