package com.sitemonitor.service.techerror;

import com.sitemonitor.model.IncidentRecord;
import com.sitemonitor.service.techerror.TechErrorTemplate.Section;
import com.sitemonitor.service.techerror.TechErrorTemplate.Template;
import com.sitemonitor.service.techerror.TechErrorValues.Issue;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.*;

import static com.sitemonitor.service.techerror.TechErrorIncidentLinks.*;

/**
 * Olay kaydı → anomali konusu alan eşlemesi (2026-11-20; SAF — veritabanına dokunmaz, doğruluk tablosuyla sınanır). Saat
 * dilimi Europe/Istanbul ({@link TechErrorReportService#ZONE}); olay zamanları UTC metin — ilk 19 karakter okunur
 * ({@code yyyy-MM-ddTHH:mm} de kabul edilir), okunamayan = boş.
 *
 * <p>Gruplar ve kurallar sözleşmede ({@code olay-ice-alma-api.md}); özet: konu = başlık (tek satır, ≤ 120, kelime
 * sınırında kırpılır); açıklama = seçilen parçalar (kök neden, iş etkisi, çözüm, teknik açıklama) kalın başlıklarla; tarih
 * yalnız raporun ayı içindeyse; saat aralığı yalnız tarih yazıldıysa ve aynı gün / ertesi gün (≤ 24 sa) kuralına uyuyorsa;
 * hata adedi; kesinti (Evet + ID / Hayır / cevapsız); müşteri etkisi (sayı > 0 → Evet, 0 → Hayır).
 *
 * <p>Bölümün sormadığı soruların değerleri {@link TechErrorValues#conform} ile düşer; o soruların notları da yazılmaz.
 */
public final class TechErrorIncidentMapper {

    private TechErrorIncidentMapper() {}

    /** Açıklamanın sınırı (konunun katı kuralı, {@link TechErrorValues#MAX_LONG_TEXT}). */
    static final int MAX_DESCRIPTION = TechErrorValues.MAX_LONG_TEXT;
    static final int MAX_TOPIC = TechErrorValues.MAX_TOPIC;
    private static final DateTimeFormatter DMY_HM = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");

    /** Eşlemenin girdisi (olay kaydının ilgili alanları). */
    public record Source(Long id, String title, String occurredAt, String resolvedAt, Long techErrorCount, Boolean outageRelated,
                         String outageId, Integer affectedCustomers, String rcaSummary, String businessImpact,
                         String resolutionSteps, String description, String updatedAt) {
        public static Source of(IncidentRecord e) {
            return new Source(e.getId(), e.getTitle(), e.getOccurredAt(), e.getResolvedAt(), e.getTechErrorCount(),
                    e.getOutageRelated(), e.getOutageId(), e.getAffectedCustomers(), e.getRcaSummary(), e.getBusinessImpact(),
                    e.getResolutionSteps(), e.getDescription(), e.getUpdatedAt());
        }
    }

    /**
     * Eşleme sonucu. {@code issue}: eşlenmiş değerler BÖLÜMÜN sorularına uydurulmuş ({@link TechErrorValues#conform}) hâlde;
     * {@code notes}: not kodları; {@code hidden}: "müşteri etkisi yok → konu rapora girmez" bölümünde Hayır;
     * {@code dateOutsideMonth}: olayın tarihi ayın dışında (önce ya da sonra) — senkron, konuda tarih varken boş tarihi yazmaz.
     */
    public record Result(Issue issue, List<String> notes, boolean hidden, boolean dateOutsideMonth) { }

    /** Olayı {@code section} bölümünün {@code issueId} kimlikli konusu olarak eşler. {@code parts}: açıklama parçaları (null = varsayılan). */
    public static Result map(Source src, Section section, Template t, YearMonth period, Collection<String> parts, String issueId) {
        TechErrorTemplate.IssueOptions o = section.opts();
        boolean en = t.en();
        LinkedHashSet<String> notes = new LinkedHashSet<>();

        // ── Konu ──
        String topic = TechErrorTemplate.line(src.title());
        if (topic.length() > MAX_TOPIC) {
            topic = clipWords(topic, MAX_TOPIC);
            notes.add(N_TOPIC_CLIPPED);
        }

        // ── Ne zaman? ──
        ZonedDateTime occurred = parse(src.occurredAt());
        ZonedDateTime resolved = parse(src.resolvedAt());
        String date = null, start = null, end = null;
        boolean nextDay = false, outside = false;
        String startedLine = null;
        if (occurred != null) {
            LocalDate d = occurred.toLocalDate();
            YearMonth ym = YearMonth.from(d);
            if (ym.equals(period)) {
                date = d.toString();
                String[] range = timeRange(occurred, resolved);
                if (range == null) {
                    if (o.askTime()) notes.add(N_TIME_UNKNOWN);
                } else {
                    start = range[0];
                    end = range[1];
                    nextDay = "1".equals(range[2]);
                }
            } else {
                outside = true;
                // Başlangıç satırı bölüm zaman sorusunu sorsa da sormasa da yazılır (inceleme 2026-11-20) — açıklamada en az
                // bir parça varsa (yalnız bu satırdan oluşan açıklama yazılmaz); notlar yalnız sorulan soru için
                if (ym.isBefore(period)) {
                    startedLine = (en ? "Start: " : "Başlangıç: ") + DMY_HM.format(occurred.toLocalDateTime());
                    if (o.askTime()) notes.add(N_STARTED_BEFORE_MONTH);
                } else if (o.askTime()) {
                    notes.add(N_DATE_OUTSIDE_MONTH);
                }
            }
        } else if (o.askTime()) {
            notes.add(N_TIME_UNKNOWN);
        }

        // ── Açıklama ──
        Desc desc = description(src, parts, en, startedLine);
        if (desc.clipped()) notes.add(N_DESCRIPTION_CLIPPED);

        // ── Hata adedi ──
        Number errors = src.techErrorCount() == null || src.techErrorCount() < 0 ? null : src.techErrorCount();

        // ── Kesinti ──
        Boolean outage = null;
        String outageId = null;
        if (Boolean.TRUE.equals(src.outageRelated())) {
            String id = TechErrorTemplate.line(src.outageId());
            if (id.length() > TechErrorValues.MAX_OUTAGE_ID) id = id.substring(0, TechErrorValues.MAX_OUTAGE_ID).trim();
            if (!id.isEmpty()) {
                outage = true;
                outageId = id;
            } else if (o.askOutage()) {
                notes.add(N_OUTAGE_ID_MISSING);
            }
        } else if (Boolean.FALSE.equals(src.outageRelated())) {
            outage = false;
        }

        // ── Müşteri etkisi ──
        Boolean impact = null;
        Number customers = null;
        Integer ac = src.affectedCustomers();
        if (ac != null && ac >= 0) {
            if (o.askImpact()) {
                if (ac > 0) {
                    impact = true;
                    customers = ac.longValue();
                } else {
                    impact = false;
                }
            } else {
                customers = ac.longValue();
            }
        }

        String title = TechErrorIncidentLinks.autoTitle(section.itemTitle(), topic);
        Issue raw = new Issue(issueId, topic, title, true, desc.text(), customers, errors, date, start, end, nextDay, outage,
                outageId, impact, Map.of(), null);
        Issue conformed = TechErrorValues.conform(List.of(raw), section).get(0);
        boolean hidden = o.hideTopic() && Boolean.FALSE.equals(conformed.impact());
        if (hidden) notes.add(N_WILL_BE_HIDDEN);
        List<String> ordered = new ArrayList<>();
        for (String n : NOTE_CODES) if (notes.contains(n)) ordered.add(n);
        return new Result(conformed, List.copyOf(ordered), hidden, outside);
    }

    /**
     * Saat aralığı {@code [başlangıç, bitiş, ertesiGün("1"/"0")]} ya da {@code null} (bilinmiyor): çözülmemiş, çözüm başlangıçtan
     * önce, dakikaya yuvarlanınca eşit, 24 saatten uzun ya da ertesi günden sonra biten.
     */
    static String[] timeRange(ZonedDateTime occurred, ZonedDateTime resolved) {
        if (occurred == null || resolved == null || resolved.isBefore(occurred)) return null;
        ZonedDateTime s = occurred.truncatedTo(ChronoUnit.MINUTES);
        ZonedDateTime e = resolved.truncatedTo(ChronoUnit.MINUTES);
        long minutes = Duration.between(s, e).toMinutes();
        if (minutes <= 0 || minutes > 24 * 60) return null;
        String hs = s.toLocalTime().toString().substring(0, 5);
        String he = e.toLocalTime().toString().substring(0, 5);
        LocalDate ds = s.toLocalDate(), de = e.toLocalDate();
        if (de.equals(ds) && he.compareTo(hs) > 0) return new String[]{ hs, he, "0" };
        if (de.equals(ds.plusDays(1)) && he.compareTo(hs) <= 0) return new String[]{ hs, he, "1" };
        return null;
    }

    /** UTC metin zamanı → Istanbul; ilk 19 karakter ({@code yyyy-MM-ddTHH:mm:ss}) ya da 16 karakter ({@code …THH:mm}). */
    static ZonedDateTime parse(String s) {
        if (s == null) return null;
        String t = s.trim();
        if (t.length() >= 19) t = t.substring(0, 19);
        else if (t.length() == 16) t = t + ":00";
        else return null;
        if (t.charAt(10) == ' ') t = t.substring(0, 10) + "T" + t.substring(11);
        try {
            return LocalDateTime.parse(t).atZone(ZoneOffset.UTC).withZoneSameInstant(TechErrorReportService.ZONE);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /** {@code max} karaktere kelime sınırında kırpar ve "…" ekler (sonuç ≤ max). Kelime sınırı yoksa düz keser. */
    static String clipWords(String s, int max) {
        if (s.length() <= max) return s;
        int limit = max - 1;                                      // "…" için yer
        int cut = -1;
        for (int k = limit; k > limit / 2; k--) {
            if (k < s.length() && s.charAt(k) == ' ') {
                cut = k;
                break;
            }
        }
        String head = cut > 0 ? s.substring(0, cut) : s.substring(0, limit);
        if (!head.isEmpty() && Character.isHighSurrogate(head.charAt(head.length() - 1))) head = head.substring(0, head.length() - 1);
        return head.strip() + "…";
    }

    record Desc(String text, boolean clipped) { }

    /** Seçilen parçalar sırayla: kalın başlık satırı + dönüştürülmüş metin; 10 000'e satır sınırında kırpılır. */
    static Desc description(Source src, Collection<String> parts, boolean en, String startedLine) {
        Set<String> chosen = parts == null ? new HashSet<>(DEFAULT_PARTS) : new HashSet<>(parts);
        List<String> blocks = new ArrayList<>();
        boolean clipped = false;
        for (String p : PARTS) {
            if (!chosen.contains(p)) continue;
            String md = switch (p) {
                case P_RCA -> src.rcaSummary();
                case P_IMPACT -> src.businessImpact();
                case P_RESOLUTION -> src.resolutionSteps();
                default -> src.description();
            };
            TechErrorIncidentText.Converted c = TechErrorIncidentText.convert(md);
            if (c.inputClipped()) clipped = true;
            String body = TechErrorTemplate.multiline(c.text());
            if (TechErrorRichText.blank(body)) continue;
            blocks.add("**" + heading(p, en) + "**\n" + body);
        }
        // Başlangıç satırı yalnız açıklamada bir parça varsa (bütün parçalar boş → açıklama boş, sözleşme)
        if (startedLine != null && !blocks.isEmpty()) blocks.add(0, startedLine);
        String all = String.join("\n\n", blocks);
        if (all.length() > MAX_DESCRIPTION) {
            // Satır sınırında kes; son satır sınırı çok gerideyse (tek uzun paragraf) metni kaybetmemek için düz kes
            int cut = all.lastIndexOf('\n', MAX_DESCRIPTION);
            all = cut > MAX_DESCRIPTION / 2 ? all.substring(0, cut) : hardCut(all, MAX_DESCRIPTION);
            clipped = true;
        }
        all = TechErrorTemplate.multiline(all);
        if (all.length() > MAX_DESCRIPTION) {
            all = hardCut(all, MAX_DESCRIPTION);
            clipped = true;
        }
        return new Desc(all.stripTrailing(), clipped);
    }

    /** {@code max} karakterde keser; vekil çifti bölünmez. */
    private static String hardCut(String s, int max) {
        if (s.length() <= max) return s;
        int cut = max;
        if (Character.isHighSurrogate(s.charAt(cut - 1))) cut--;
        return s.substring(0, cut);
    }

    /** Parçanın kalın başlığı (şablon dilinde). */
    static String heading(String part, boolean en) {
        return switch (part) {
            case P_RCA -> en ? "Root cause" : "Kök neden";
            case P_IMPACT -> en ? "Business impact" : "İş etkisi";
            case P_RESOLUTION -> en ? "Resolution" : "Çözüm";
            default -> en ? "Technical description" : "Teknik açıklama";
        };
    }

    /** Önizlemenin açıklama özeti: ≤ 600 karakter. */
    public static String preview(String description) {
        if (description == null) return "";
        if (description.length() <= 600) return description;
        String head = description.substring(0, 599);
        if (Character.isHighSurrogate(head.charAt(head.length() - 1))) head = head.substring(0, 598);
        return head + "…";
    }
}
