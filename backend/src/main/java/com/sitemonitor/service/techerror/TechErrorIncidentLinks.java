package com.sitemonitor.service.techerror;

import com.sitemonitor.model.TechErrorReport;
import com.sitemonitor.repository.TechErrorReportRepository;
import com.sitemonitor.service.techerror.TechErrorValues.Issue;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.*;

/**
 * Anomali konusu ↔ olay kaydı CANLI bağının saf kuralları (2026-11-20): alan grupları, parmak izi, sabitleme (pin) tespiti,
 * "rapor dondurulmuş mu", CSV / JSON kodlaması. Veritabanına dokunmaz.
 *
 * <p><b>Grup anahtarları KALICI</b>: {@code topic}, {@code description}, {@code date}, {@code time}, {@code errors},
 * {@code outage}, {@code impact}. Açıklama parçaları KALICI: {@code rca}, {@code impact}, {@code resolution},
 * {@code technical}. Not kodları KALICI ({@link #NOTE_CODES}).
 */
public final class TechErrorIncidentLinks {

    private TechErrorIncidentLinks() {}

    public static final String G_TOPIC = "topic";
    public static final String G_DESCRIPTION = "description";
    public static final String G_DATE = "date";
    public static final String G_TIME = "time";
    public static final String G_ERRORS = "errors";
    public static final String G_OUTAGE = "outage";
    public static final String G_IMPACT = "impact";
    /** Grupların kanonik sırası (CSV ve yazım sırası). */
    public static final List<String> GROUPS = List.of(G_TOPIC, G_DESCRIPTION, G_DATE, G_TIME, G_ERRORS, G_OUTAGE, G_IMPACT);

    public static final String P_RCA = "rca";
    public static final String P_IMPACT = "impact";
    public static final String P_RESOLUTION = "resolution";
    public static final String P_TECHNICAL = "technical";
    /** Açıklama parçalarının kanonik sırası. */
    public static final List<String> PARTS = List.of(P_RCA, P_IMPACT, P_RESOLUTION, P_TECHNICAL);
    public static final List<String> DEFAULT_PARTS = List.of(P_RCA, P_IMPACT, P_RESOLUTION);

    public static final String N_TOPIC_CLIPPED = "topic_clipped";
    public static final String N_DESCRIPTION_CLIPPED = "description_clipped";
    public static final String N_STARTED_BEFORE_MONTH = "started_before_month";
    public static final String N_DATE_OUTSIDE_MONTH = "date_outside_month";
    public static final String N_TIME_UNKNOWN = "time_unknown";
    public static final String N_OUTAGE_ID_MISSING = "outage_id_missing";
    public static final String N_WILL_BE_HIDDEN = "will_be_hidden";
    public static final String N_VALUE_REFUSED = "value_refused";
    public static final String N_IMAGE_SKIPPED = "image_skipped";
    /** Not kodları (KALICI; arayüz {@code te.inc.note.<kod>}). */
    public static final List<String> NOTE_CODES = List.of(N_TOPIC_CLIPPED, N_DESCRIPTION_CLIPPED, N_STARTED_BEFORE_MONTH,
            N_DATE_OUTSIDE_MONTH, N_TIME_UNKNOWN, N_OUTAGE_ID_MISSING, N_WILL_BE_HIDDEN, N_VALUE_REFUSED, N_IMAGE_SKIPPED);

    /**
     * Bağ durumu (rapor ekranı): {@code ok} | {@code stale} | {@code frozen_stale} | {@code deleted} | {@code hidden}.
     * {@code hidden}: bakan olayı okuyamıyor YA DA olay artık raporun takımında değil (takım transferi, olayın takımı
     * değişti, rapor başka takıma taşındı) — o bağ hiçbir kipte yazılmaz (inceleme 2026-11-20).
     */
    public static final String S_OK = "ok";
    public static final String S_STALE = "stale";
    public static final String S_FROZEN_STALE = "frozen_stale";
    public static final String S_DELETED = "deleted";
    public static final String S_HIDDEN = "hidden";

    /** Geçmiş olayı türleri (≤ 30 karakter, KALICI). */
    public static final String EV_IMPORT = "INCIDENT_IMPORT";
    public static final String EV_SYNC = "INCIDENT_SYNC";
    public static final String EV_UNLINK = "INCIDENT_UNLINK";

    private static final char SEP = '\u001F';

    // ── Parmak izi ───────────────────────────────────────────────────────────────────────────────────

    /**
     * Grubun parmak izi: konunun ŞU ANKİ saklı değerlerinin kanonik dizgesinin SHA-256'sının ilk 16 onaltılık hanesi.
     * Alanlar {@code \u001F} ile birleşir; yok / boş = "", sayılar {@code stripTrailingZeros().toPlainString()} (kısaltma
     * üssü yok sayılır), mantıksal "1" / "0". Konu yoksa (null) boş değerlerle hesaplanır.
     */
    public static String fingerprint(String group, Issue i) {
        return sha16(canonical(group, i));
    }

    static String canonical(String group, Issue i) {
        if (i == null) i = new Issue("x", null, null, false, null, null, null);
        return switch (group) {
            case G_TOPIC -> s(i.topic());
            case G_DESCRIPTION -> s(i.description());
            case G_DATE -> s(i.date());
            case G_TIME -> s(i.start()) + SEP + s(i.end()) + SEP + (i.endNextDay() ? "1" : "0");
            case G_ERRORS -> num(i.errors());
            case G_OUTAGE -> bool(i.outage()) + SEP + s(i.outageId());
            case G_IMPACT -> bool(i.impact()) + SEP + num(i.customers());
            default -> throw new IllegalArgumentException("unknown group: " + group);
        };
    }

    private static String s(String v) {
        return v == null || v.isBlank() ? "" : v;
    }

    /**
     * Olayın eşleme KAYNAĞININ izi (inceleme 2026-11-20): eşlemenin okuduğu olay alanları — başlık, başlangıç / çözüm
     * zamanı, hata adedi, kesinti + Kesinti ID, etkilenen müşteri — ve bağın SEÇİLİ açıklama parçalarının metni (eşlemenin
     * okuduğu ilk {@link TechErrorIncidentText#MAX_INPUT_CHARS} karakter). Olayın {@code updated_at}'i saniye çözünürlüklü:
     * aynı saniyedeki ikinci düzenlemenin olay senkronu kaybolursa (kuyruk dolu, pod kapandı) yetişme bu izden yakalar.
     * Eşlenmiş değerlerin değil ham kaynağın izidir: şablonu / bölümü okumaz (ucuz); DÖRT açıklama parçası da (bağın seçimi
     * ne olursa olsun) izdedir — seçilmemiş parçanın değişmesi yalnız boş bir senkron demektir (yazılacak grup yoksa
     * yazılmaz), kaçan değişiklik olmaz.
     */
    public static String sourceFp(TechErrorIncidentMapper.Source src) {
        if (src == null) return null;
        StringBuilder b = new StringBuilder(256);
        b.append(s(src.title())).append(SEP).append(s(src.occurredAt())).append(SEP).append(s(src.resolvedAt())).append(SEP)
                .append(num(src.techErrorCount())).append(SEP).append(bool(src.outageRelated())).append(SEP)
                .append(s(src.outageId())).append(SEP).append(num(src.affectedCustomers()));
        for (String p : PARTS) {
            b.append(SEP);
            String md = switch (p) {
                case P_RCA -> src.rcaSummary();
                case P_IMPACT -> src.businessImpact();
                case P_RESOLUTION -> src.resolutionSteps();
                default -> src.description();
            };
            if (md == null) continue;
            b.append(md.length() > TechErrorIncidentText.MAX_INPUT_CHARS ? md.substring(0, TechErrorIncidentText.MAX_INPUT_CHARS) : md);
        }
        return sha16(b.toString());
    }

    private static String bool(Boolean b) {
        return b == null ? "" : b ? "1" : "0";
    }

    static String num(Number n) {
        if (n == null) return "";
        try {
            return new BigDecimal(n.toString()).stripTrailingZeros().toPlainString();
        } catch (NumberFormatException e) {
            return n.toString();
        }
    }

    private static String sha16(String in) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(in.getBytes(StandardCharsets.UTF_8));
            StringBuilder b = new StringBuilder(16);
            for (int k = 0; k < 8; k++) b.append(String.format("%02x", d[k] & 0xFF));
            return b.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    // ── Kodlama ──────────────────────────────────────────────────────────────────────────────────────

    /** {@code fields_fp} JSON'u → grup → iz (hoşgörülü: okunamayan / bilinmeyen anahtar düşer). */
    public static Map<String, String> parseFp(String json) {
        Map<String, String> out = new LinkedHashMap<>();
        if (json == null || json.isBlank() || json.length() > 4096) return out;
        try {
            Object raw = TechErrorTemplate.JSON.readValue(json, Object.class);
            if (raw instanceof Map<?, ?> m) {
                for (String g : GROUPS) {
                    Object v = m.get(g);
                    if (v instanceof String sv && sv.length() <= 64) out.put(g, sv);
                }
            }
        } catch (RuntimeException e) {
            // okunamayan iz = hiç yazılmamış (sabitleme tespiti o grubu atlar)
        }
        return out;
    }

    /** Grup → iz haritası → JSON (grup sırasıyla; boşsa null). */
    public static String fpJson(Map<String, String> fp) {
        if (fp == null || fp.isEmpty()) return null;
        Map<String, String> ordered = new LinkedHashMap<>();
        for (String g : GROUPS) if (fp.get(g) != null) ordered.put(g, fp.get(g));
        return ordered.isEmpty() ? null : TechErrorTemplate.JSON.writeValueAsString(ordered);
    }

    /** CSV → izinli değerler (izinli listenin sırasıyla; bilinmeyen düşer). */
    public static LinkedHashSet<String> parseCsv(String csv, List<String> allowed) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        if (csv == null || csv.isBlank() || csv.length() > 4096) return out;
        Set<String> got = new HashSet<>();
        int n = 0;
        for (String part : csv.split(",", 64)) {
            if (++n > 64) break;
            got.add(part.trim());
        }
        for (String a : allowed) if (got.contains(a)) out.add(a);
        return out;
    }

    /** Değerler → CSV (izinli listenin sırasıyla); boşsa null. */
    public static String csv(Collection<String> values, List<String> order) {
        if (values == null || values.isEmpty()) return null;
        StringBuilder b = new StringBuilder();
        for (String o : order) {
            if (!values.contains(o)) continue;
            if (!b.isEmpty()) b.append(',');
            b.append(o);
        }
        return b.isEmpty() ? null : b.toString();
    }

    // ── Sabitleme ────────────────────────────────────────────────────────────────────────────────────

    /**
     * Sabitleme tespiti: sabitlenmemiş ve izi olan her grup için konunun şu anki izi kayıtlı izden farklıysa grup sabitlenir.
     * Yeni küme döner (değişmediyse aynı içerik); {@code issue} null ise konu yok — çağıran bağı siler.
     */
    public static LinkedHashSet<String> detectPins(Issue issue, Map<String, String> fp, Set<String> pinned) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String g : GROUPS) {
            if (pinned.contains(g)) {
                out.add(g);
                continue;
            }
            String saved = fp.get(g);
            if (saved != null && !saved.equals(fingerprint(g, issue))) out.add(g);
        }
        return out;
    }

    // ── Dondurulmuş rapor ────────────────────────────────────────────────────────────────────────────

    /**
     * Rapor kendiliğinden değişmez mi: durum {@code sent} / {@code queued} / {@code sending}, ya da onay {@code pending}
     * ya da {@code approved} (eskimemiş — onay DTO'sunun kuralı: içerik sayacı onaylanan revizyonu geçmediyse) VE kanalın
     * onay akışı hâlâ var ({@code approvalFlow}: {@link TechErrorApprovalService#approvalRequired}; yönetici alıcıları
     * kaldırılan kanalda bekleyen / verilmiş onay raporu dondurmaz — inceleme 2026-11-20). {@code approvalFlow} yalnız onay
     * durumu belirleyiciyse okunur (çoğu raporda kanal okunmaz).
     */
    public static boolean frozen(TechErrorReport r, java.util.function.BooleanSupplier approvalFlow, Instant now) {
        return frozen(row(r), approvalFlow, now);
    }

    /** {@link #frozen(TechErrorReport, java.util.function.BooleanSupplier, Instant)} — durum satırı izdüşümüyle (metin yok). */
    public static boolean frozen(TechErrorReportRepository.Row r, java.util.function.BooleanSupplier approvalFlow, Instant now) {
        String st = TechErrorReportService.reportStatus(r, now);
        if ("sent".equals(st) || "queued".equals(st) || "sending".equals(st)) return true;
        String a = r.getApprovalState();
        if (!"PENDING".equals(a) && !"APPROVED".equals(a)) return false;
        long content = r.getContentRev() == null ? 0L : r.getContentRev();
        boolean stale = r.getApprovalRev() != null && content > r.getApprovalRev();
        if (stale) return false;
        return approvalFlow == null || approvalFlow.getAsBoolean();
    }

    /** Onay akışı bilinmeden (temkinli: akış var sayılır). */
    public static boolean frozen(TechErrorReport r, Instant now) {
        return frozen(r, () -> true, now);
    }

    /** Varlıktan durum satırı görünümü (tek kural kaynağı {@link TechErrorReportService#reportStatus} okusun). */
    static TechErrorReportRepository.Row row(TechErrorReport r) {
        return new TechErrorReportRepository.Row() {
            public Long getId() { return r.getId(); }
            public Long getChannelId() { return r.getChannelId(); }
            public Long getTeamId() { return r.getTeamId(); }
            public Integer getReportYear() { return r.getReportYear(); }
            public Integer getReportMonth() { return r.getReportMonth(); }
            public String getTitlePattern() { return r.getTitlePattern(); }
            public String getTemplateLang() { return r.getTemplateLang(); }
            public Integer getTemplateVersion() { return r.getTemplateVersion(); }
            public Instant getCreatedAt() { return r.getCreatedAt(); }
            public Instant getUpdatedAt() { return r.getUpdatedAt(); }
            public Long getUpdatedById() { return r.getUpdatedById(); }
            public Instant getContentChangedAt() { return r.getContentChangedAt(); }
            public Long getContentRev() { return r.getContentRev(); }
            public Long getSentRev() { return r.getSentRev(); }
            public String getSendState() { return r.getSendState(); }
            public Instant getSendClaimedAt() { return r.getSendClaimedAt(); }
            public Instant getLastSentAt() { return r.getLastSentAt(); }
            public Long getLastSentById() { return r.getLastSentById(); }
            public String getLastSentTo() { return r.getLastSentTo(); }
            public Instant getLastAttemptAt() { return r.getLastAttemptAt(); }
            public String getLastSendStatus() { return r.getLastSendStatus(); }
            public int getSendCount() { return r.getSendCount(); }
            public String getApprovalState() { return r.getApprovalState(); }
            public Long getApprovalRev() { return r.getApprovalRev(); }
            public Instant getApprovalRequestedAt() { return r.getApprovalRequestedAt(); }
            public Long getApprovalRequestedById() { return r.getApprovalRequestedById(); }
            public Instant getApprovalDecidedAt() { return r.getApprovalDecidedAt(); }
            public Long getApprovalDecidedById() { return r.getApprovalDecidedById(); }
            public String getApprovalNote() { return r.getApprovalNote(); }
        };
    }

    // ── Konu alanları ────────────────────────────────────────────────────────────────────────────────

    /** Konunun bir grubunu başka bir konunun (eşlenmiş) değerleriyle değiştirir; başlık otomatikse konudan yeniden üretilir. */
    static Issue withGroup(Issue cur, String group, Issue from, String itemTitlePattern) {
        return switch (group) {
            case G_TOPIC -> {
                String title = cur.titleAuto() ? autoTitle(itemTitlePattern, from.topic()) : cur.title();
                yield new Issue(cur.id(), from.topic(), title, cur.titleAuto(), cur.description(), cur.customers(), cur.errors(),
                        cur.date(), cur.start(), cur.end(), cur.endNextDay(), cur.outage(), cur.outageId(), cur.impact(),
                        cur.captions(), cur.share());
            }
            case G_DESCRIPTION -> new Issue(cur.id(), cur.topic(), cur.title(), cur.titleAuto(), from.description(), cur.customers(),
                    cur.errors(), cur.date(), cur.start(), cur.end(), cur.endNextDay(), cur.outage(), cur.outageId(), cur.impact(),
                    cur.captions(), cur.share());
            case G_DATE -> new Issue(cur.id(), cur.topic(), cur.title(), cur.titleAuto(), cur.description(), cur.customers(),
                    cur.errors(), from.date(), cur.start(), cur.end(), cur.endNextDay(), cur.outage(), cur.outageId(), cur.impact(),
                    cur.captions(), cur.share());
            case G_TIME -> new Issue(cur.id(), cur.topic(), cur.title(), cur.titleAuto(), cur.description(), cur.customers(),
                    cur.errors(), cur.date(), from.start(), from.end(), from.endNextDay(), cur.outage(), cur.outageId(), cur.impact(),
                    cur.captions(), cur.share());
            case G_ERRORS -> new Issue(cur.id(), cur.topic(), cur.title(), cur.titleAuto(), cur.description(), cur.customers(),
                    from.errors(), cur.date(), cur.start(), cur.end(), cur.endNextDay(), cur.outage(), cur.outageId(), cur.impact(),
                    cur.captions(), cur.share());
            case G_OUTAGE -> new Issue(cur.id(), cur.topic(), cur.title(), cur.titleAuto(), cur.description(), cur.customers(),
                    cur.errors(), cur.date(), cur.start(), cur.end(), cur.endNextDay(), from.outage(), from.outageId(), cur.impact(),
                    cur.captions(), cur.share());
            case G_IMPACT -> new Issue(cur.id(), cur.topic(), cur.title(), cur.titleAuto(), cur.description(), from.customers(),
                    cur.errors(), cur.date(), cur.start(), cur.end(), cur.endNextDay(), cur.outage(), cur.outageId(), from.impact(),
                    cur.captions(), cur.share());
            default -> throw new IllegalArgumentException("unknown group: " + group);
        };
    }

    /** Otomatik başlık: bölümün başlık kalıbı + konu (istemcinin {@code resolveIssueTitle} kuralı), başlık sınırına sığdırılır. */
    static String autoTitle(String pattern, String topic) {
        String t = TechErrorTemplate.resolveIssueTitle(pattern, topic == null ? "" : topic);
        return t.length() > TechErrorTemplate.MAX_LABEL ? t.substring(0, TechErrorTemplate.MAX_LABEL) : t;
    }

    /** Katı doğrulamanın alan anahtarının son parçası → grup (konu görseli açıklaması ve bilinmeyen → null). */
    static String groupOfField(String field) {
        if (field == null) return null;
        return switch (field) {
            case "topic", "title" -> G_TOPIC;
            case "description" -> G_DESCRIPTION;
            case "date" -> G_DATE;
            case "start", "end" -> G_TIME;
            case "errors" -> G_ERRORS;
            case "outage", "outage_id" -> G_OUTAGE;
            case "impact", "customers" -> G_IMPACT;
            default -> null;
        };
    }
}
