package com.sitemonitor.service;

import com.sitemonitor.config.GlobalExceptionHandler.FieldValidationException;
import com.sitemonitor.model.IncidentImage;
import com.sitemonitor.model.IncidentOption;
import com.sitemonitor.model.IncidentRecord;
import com.sitemonitor.repository.IncidentImageRepository;
import com.sitemonitor.repository.IncidentOptionRepository;
import com.sitemonitor.repository.IncidentRecordRepository;
import com.sitemonitor.service.incident.IncidentPostmortemService;
import com.sitemonitor.util.Msg;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SRE olay/hata ledger servisi — manuel CRUD + filtreli arama + trend agregatları.
 * İzin kontrolü controller'da (permissionService.allows); bu servis sadece
 * doğrulama + zaman damgası + actor bilgisini damgalar. Mevcut akışlardan bağımsız.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IncidentService {

    private final IncidentRecordRepository repo;
    private final IncidentImageRepository imageRepo;
    private final IncidentOptionRepository optionRepo;

    private static final DateTimeFormatter ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);

    static final Set<String> SEVERITIES = Set.of("CRITICAL", "HIGH", "MEDIUM", "LOW");
    static final Set<String> STATUSES   = Set.of("OPEN", "INVESTIGATING", "MITIGATED", "RESOLVED");
    static final Set<String> CATEGORIES = Set.of("DATABASE", "NETWORK", "CERTIFICATE",
                                                 "APPLICATION", "INFRASTRUCTURE", "OTHER");

    /** Yönetilen seçenek tipleri (incident sayfasından genişletilebilir dropdown'lar). */
    static final String OPT_CHANNEL       = "CHANNEL";
    static final String OPT_DOMAIN        = "DOMAIN";
    static final String OPT_ERROR_CODE    = "ERROR_CODE";
    static final String OPT_FUNCTION_CODE = "FUNCTION_CODE";
    static final String OPT_CHANNEL_CODE  = "CHANNEL_CODE";
    static final Set<String> OPTION_TYPES = Set.of(OPT_CHANNEL, OPT_DOMAIN, OPT_ERROR_CODE, OPT_FUNCTION_CODE, OPT_CHANNEL_CODE);

    /** Açılışta tohumlanan varsayılan kanallar (banka kanalları; kullanıcı ekleyip çıkarabilir). */
    private static final List<String> CHANNEL_DEFAULTS = List.of(
            "Bireysel İnternet Şubesi", "Kurumsal İnternet Şubesi", "Mobil Şube",
            "Çağrı Merkezi - Inbound", "Çağrı Merkezi - Outbound", "IVR",
            "ATM", "POS / Ödeme", "Public Web", "API / Açık Bankacılık");
    private static final Pattern IMG_REF = Pattern.compile("/api/incidents/images/(\\d+)");

    @Value("${site.monitor.incident.image-max-bytes:5242880}")
    private long imageMaxBytes;

    /**
     * Olay güncellenince yayımlanan olay ({@link IncidentRecordChangedEvent}, 2026-11-20): teknik hata raporu konularının canlı
     * bağı işlem İŞLENDİKTEN sonra senkronlar. Yoksa (elle kurulan birim testleri) yayımlanmaz.
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private org.springframework.context.ApplicationEventPublisher events;

    /**
     * Takım adının kaynağı (2026-11-20, "Sağlam Kayıt"): {@code team_name} artık istemciden ALINMAZ, {@code team_id}'nin
     * veritabanındaki takımından okunur (istemci eski / yanlış adı yazamaz). Bean yoksa (elle kurulan birim testleri)
     * eski davranış: gövdedeki {@code team_name}.
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.sitemonitor.repository.TeamRepository teamRepo;

    /**
     * Teknik hata raporu konularının olay bağları (2026-11-26): seçenek yeniden adlandırma / birleştirme eşleme kaynağına
     * dokunmaz — bağ durumu "ok" kalsın diye eşit bağlar yeni {@code updated_at}'e taşınır. Bean yoksa (elle kurulan birim
     * testleri) atlanır.
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.sitemonitor.repository.TechErrorReportIncidentRepository techErrorLinks;

    /**
     * Takım adı veritabanından; takım yok / depo yok → boş. Okuma hatası YAKALANMAZ: işlem içinde yutulan depo hatası işlemi
     * "yalnız geri al" işaretler ve kayıt yine düşerdi — hata olduğu gibi yükselir (503 / 500, kayıt yazılmaz).
     */
    private Optional<String> teamNameOf(Long teamId) {
        if (teamId == null || teamRepo == null) return Optional.empty();
        return teamRepo.findById(teamId).map(com.sitemonitor.model.Team::getName);
    }

    private void publishChanged(Long id) {
        if (events == null || id == null) return;
        try {
            events.publishEvent(new IncidentRecordChangedEvent(id));
        } catch (RuntimeException e) {
            // yayımlama en iyi çaba — olay kaydının kaydı hiçbir koşulda bu yüzden düşmez
            log.warn("Olay kaydı #{} değişikliği yayımlanamadı: {}", id, e.toString());
        }
    }

    private String now() { return ISO.format(Instant.now()); }

    // ── Read ────────────────────────────────────────────────────────────────

    /** scope = null → global (admin/AUDIT) tüm olaylar; dolu liste → yalnız o takım(lar)ın olayları
     *  (teamId VEYA createdByTeamId kapsamda). Boş liste → kapsamsız kullanıcı, hiçbir şey görmez. Eski imza — yeni süzgeçler
     *  ve sıralama olmadan {@link #list(ListFilter, List, Pageable, String)}. */
    public Page<IncidentRecord> list(String q, String severity, String category, String status,
                                     String service, String channel, String since, String until,
                                     Long teamId, Boolean slaBreached, Boolean open,
                                     List<Long> scope, Pageable pageable) {
        return list(ListFilter.legacy(q, severity, category, status, service, channel, since, until, teamId, slaBreached, open),
                scope, pageable, null);
    }

    // ── Bul ve Raporla (2026-11-26, Paket 3) ─────────────────────────────────────────────────────────────────────────

    /** Postmortem durum süzgecinin değerleri ({@code NONE} = raporu yok). */
    public static final List<String> POSTMORTEM_FILTERS = List.of("NONE", "DRAFT", "IN_REVIEW", "PUBLISHED");
    /** Süzgeç metinlerinin (arama, alan, etiket, kod, kaydeden) üst sınırı — aşan değer alan adlı 400. */
    public static final int MAX_FILTER_TEXT = 200;

    /** Sıralama anahtarları → ORDER BY (beyaz liste; hepsi {@code id DESC} ile biter — sayfalar arası kararlı sıra). */
    public static final Map<String, String> SORTS;
    public static final String DEFAULT_SORT = "occurred_desc";
    static {
        Map<String, String> s = new LinkedHashMap<>();
        s.put("occurred_desc", " ORDER BY i.occurredAt DESC, i.id DESC");
        s.put("occurred_asc", " ORDER BY i.occurredAt ASC, i.id DESC");
        s.put("severity", " ORDER BY CASE UPPER(i.severity) WHEN 'CRITICAL' THEN 0 WHEN 'HIGH' THEN 1 WHEN 'MEDIUM' THEN 2"
                + " WHEN 'LOW' THEN 3 ELSE 4 END, i.occurredAt DESC, i.id DESC");
        s.put("status", " ORDER BY CASE i.status WHEN 'OPEN' THEN 0 WHEN 'INVESTIGATING' THEN 1 WHEN 'MITIGATED' THEN 2"
                + " WHEN 'RESOLVED' THEN 3 ELSE 4 END, i.occurredAt DESC, i.id DESC");
        // Süre: en uzun önce; süresiz kayıt sonda (PostgreSQL DESC'te NULL'ı başa koyar — CASE ile açıkça sona)
        s.put("duration", " ORDER BY CASE WHEN i.durationMinutes IS NULL THEN 1 ELSE 0 END, i.durationMinutes DESC,"
                + " i.occurredAt DESC, i.id DESC");
        s.put("updated", " ORDER BY CASE WHEN i.updatedAt IS NULL THEN 1 ELSE 0 END, i.updatedAt DESC, i.id DESC");
        SORTS = Collections.unmodifiableMap(s);
    }

    /** Beyaz listedeki sıralama anahtarı; boş / bilinmeyen → varsayılan (eski pod gibi sessizce — hata değil). */
    public static String sortKey(String raw) {
        if (raw == null) return DEFAULT_SORT;
        String k = raw.trim().toLowerCase(Locale.ROOT);
        return SORTS.containsKey(k) ? k : DEFAULT_SORT;
    }

    /**
     * Liste / dışa aktarım / eğilim süzgeci — istemcinin gönderdiği HAM değerler (doğrulama {@link #toQuery}'de). Eski on bir
     * alan + yeni yedi alan: alan (servis / domain) TAM öğe, kanal TAM öğe (eski {@code channel} alt dize kuralı ayrı kalır),
     * sorun türü, hata kodu, etiket, kaydeden, postmortem durumu.
     */
    public record ListFilter(String q, String severity, String category, String status, String service, String channel,
                             String since, String until, Long teamId, Boolean slaBreached, Boolean open,
                             String serviceExact, String channelExact, String problemType, String errorCode, String tag,
                             String createdBy, String postmortem) {

        public static ListFilter legacy(String q, String severity, String category, String status, String service, String channel,
                                        String since, String until, Long teamId, Boolean slaBreached, Boolean open) {
            return new ListFilter(q, severity, category, status, service, channel, since, until, teamId, slaBreached, open,
                    null, null, null, null, null, null, null);
        }

        public static ListFilter dates(String since, String until) {
            return legacy(null, null, null, null, null, null, since, until, null, null, null);
        }

        /** Tarih aralığı dışında süzgeç var mı — yoksa eğilim bugünkü sorgularla (aynı sayılar) hesaplanır. */
        public boolean onlyDates() {
            return blankToNull(q) == null && blankToNull(severity) == null && blankToNull(category) == null
                    && blankToNull(status) == null && blankToNull(service) == null && blankToNull(channel) == null
                    && teamId == null && slaBreached == null && open == null
                    && blankToNull(serviceExact) == null && blankToNull(channelExact) == null && blankToNull(problemType) == null
                    && blankToNull(errorCode) == null && blankToNull(tag) == null && blankToNull(createdBy) == null
                    && blankToNull(postmortem) == null;
        }

        /** Yeni (Paket 3) süzgeçlerden biri var mı — eski sürüm bunları yok sayar, arayüz uyarır. */
        public boolean hasExtended() {
            return blankToNull(serviceExact) != null || blankToNull(channelExact) != null || blankToNull(problemType) != null
                    || blankToNull(errorCode) != null || blankToNull(tag) != null || blankToNull(createdBy) != null
                    || blankToNull(postmortem) != null;
        }
    }

    /**
     * Ham süzgeç → sorgu süzgeci: tarihler {@link #filterStamp} (okunamayan → alan adlı 400); arama / eski alan / kanal LIKE
     * kalıbı; yeni metin süzgeçleri en çok {@value #MAX_FILTER_TEXT} karakter (aşan → alan adlı 400); postmortem durumu beyaz
     * liste. Kapsam olduğu gibi (null = tümü, boş = hiçbiri).
     */
    public static com.sitemonitor.repository.IncidentRecordQueries.Filter toQuery(ListFilter f, List<Long> scope) {
        ListFilter x = f == null ? ListFilter.dates(null, null) : f;
        String from = filterStamp(x.since(), "since");
        String to   = filterStamp(x.until(), "until");
        String q = filterText(x.q(), "q");
        return new com.sitemonitor.repository.IncidentRecordQueries.Filter(
                likePattern(q), idQuery(q),
                blankToNull(x.severity()), blankToNull(x.category()), blankToNull(x.status()),
                likePattern(x.service()), likePattern(x.channel()),   // eski kural: CSV-içinde-geçen (alt dize)
                from, to, x.teamId(), x.slaBreached(), x.open(),
                tokenPattern(filterText(x.serviceExact(), "service_exact")),
                tokenPattern(filterText(x.channelExact(), "channel_exact")),
                tokenPattern(filterText(x.problemType(), "problem_type")),
                tokenPattern(filterText(x.tag(), "tag")),
                filterText(x.errorCode(), "error_code"),
                filterText(x.createdBy(), "created_by"),
                postmortemFilter(x.postmortem()),
                scope);
    }

    /**
     * Süzgeçli liste (2026-11-26). {@code sort} {@link #SORTS} anahtarı (bilinmeyen → varsayılan); sayfa boyutu / numarası
     * {@code pageable}'dan (onun sıralaması kullanılmaz).
     */
    public Page<IncidentRecord> list(ListFilter f, List<Long> scope, Pageable pageable, String sort) {
        return repo.findFiltered(toQuery(f, scope), pageable, SORTS.get(sortKey(sort)));
    }

    /** Dışa aktarım planı: süzgeç + sıralama sırasıyla en çok {@code max} kayıt kimliği ve süzgecin toplamı. */
    public record ExportIds(long total, List<Long> ids) { }

    /**
     * Dışa aktarımın kimlik listesi (2026-11-26) — TEK dar sorgu ({@code SELECT i.id … ORDER BY …}, en çok {@code max} + 1
     * satır) ve yalnız tavan aşılınca bir sayım. Kayıtlar sonra kimlik parçalarıyla birincil anahtardan okunur
     * ({@link #loadOrdered}): ofsetli sayfa (her parçada büyüyen OFFSET + her dolu sayfada yeniden sayım) yerine sabit maliyet.
     */
    public ExportIds exportIds(ListFilter f, List<Long> scope, String sort, int max) {
        com.sitemonitor.repository.IncidentRecordQueries.Filter q = toQuery(f, scope);
        List<Object[]> rows = repo.selectFiltered("i.id", q, null, SORTS.get(sortKey(sort)), max + 1);
        List<Long> ids = new ArrayList<>(Math.min(rows.size(), max));
        for (int k = 0; k < rows.size() && k < max; k++) ids.add(((Number) rows.get(k)[0]).longValue());
        long total = rows.size() <= max ? rows.size() : repo.countFiltered(q);
        return new ExportIds(total, ids);
    }

    /** Kimlik sırasıyla kayıtlar (tek birincil anahtar okuması); arada silinen kayıt atlanır. */
    public List<IncidentRecord> loadOrdered(List<Long> ids) {
        if (ids == null || ids.isEmpty()) return List.of();
        Map<Long, IncidentRecord> byId = new HashMap<>();
        for (IncidentRecord e : repo.findAllById(ids)) byId.put(e.getId(), e);
        List<IncidentRecord> out = new ArrayList<>(ids.size());
        for (Long id : ids) {
            IncidentRecord e = byId.get(id);
            if (e != null) out.add(e);
        }
        return out;
    }

    /** Boş → null; kırpılır; {@value #MAX_FILTER_TEXT} karakteri aşan → alan adlı 400. */
    static String filterText(String raw, String field) {
        if (raw == null || raw.isBlank()) return null;
        String s = raw.trim();
        if (s.length() > MAX_FILTER_TEXT) {
            throw new FieldValidationException(field, Msg.t(
                    "Süzgeç değeri en çok " + MAX_FILTER_TEXT + " karakter olabilir. Daha kısa bir değerle yeniden arayın.",
                    "A filter value can be at most " + MAX_FILTER_TEXT + " characters. Search again with a shorter value."));
        }
        return s;
    }

    /**
     * CSV alanında TAM öğe kalıbı: {@code %,değer,%} — {@code !} {@code %} {@code _} kaçışlı (sorguda {@code ESCAPE '!'});
     * küçük harfe veritabanında (iki taraf {@code LOWER}) çevrilir. Boş → null.
     */
    static String tokenPattern(String value) {
        if (value == null || value.isBlank()) return null;
        String s = value.trim().replace("!", "!!").replace("%", "!%").replace("_", "!_");
        return "%," + s + ",%";
    }

    /** {@code #123} / {@code 123} araması → kayıt kimliği; başka metin → null. */
    static Long idQuery(String q) {
        if (q == null) return null;
        String s = q.trim();
        if (s.startsWith("#")) s = s.substring(1).trim();
        if (s.isEmpty() || s.length() > 18) return null;
        for (int k = 0; k < s.length(); k++) if (s.charAt(k) < '0' || s.charAt(k) > '9') return null;
        return Long.valueOf(s);
    }

    /** Postmortem durum süzgeci: boş → null; {@link #POSTMORTEM_FILTERS} dışında → alan adlı 400. */
    static String postmortemFilter(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String s = raw.trim().toUpperCase(Locale.ROOT).replace('-', '_');
        if (!POSTMORTEM_FILTERS.contains(s)) {
            throw new FieldValidationException("postmortem", Msg.t(
                    "Postmortem durumu okunamadı. Raporu olmayan, taslak, incelemede ya da yayımlanmış seçeneklerinden birini seçin.",
                    "The postmortem state could not be read. Choose one of: none, draft, in review or published."));
        }
        return s;
    }

    /** Eşleşen-alan ipucunun anahtarları — {@link com.sitemonitor.repository.IncidentRecordQueries#SEARCH_ATTRIBUTES} sırasıyla. */
    static final List<String> MATCH_FIELD_KEYS = List.of("title", "service", "rca_summary", "description", "resolution_steps",
            "business_impact", "tags", "error_code", "function_code", "channel_code", "affected_app", "affected_systems",
            "affected_services", "problem_types");

    /**
     * Aramanın bu kayıtta eşleştiği İLK alan ({@link #MATCH_FIELD_KEYS} sırası; kimlik eşleşmesi {@code id}). Eşleşmeyi
     * veritabanı LOWER'ı yaptığından burada bulunamayan (yerel ayar farkı) kayıt null döner — ipucu yazılmaz.
     */
    public static String matchField(IncidentRecord e, String q) {
        if (e == null || q == null || q.isBlank()) return null;
        String needle = q.trim().toLowerCase(Locale.ROOT);
        Long id = idQuery(q);
        if (id != null && id.equals(e.getId())) return "id";
        String[] values = {e.getTitle(), e.getService(), e.getRcaSummary(), e.getDescription(), e.getResolutionSteps(),
                e.getBusinessImpact(), e.getTags(), e.getErrorCode(), e.getFunctionCode(), e.getChannelCode(),
                e.getAffectedApp(), e.getAffectedSystems(), e.getAffectedServices(), e.getProblemTypes()};
        for (int k = 0; k < values.length; k++) {
            if (values[k] != null && values[k].toLowerCase(Locale.ROOT).contains(needle)) return MATCH_FIELD_KEYS.get(k);
        }
        return null;
    }

    /**
     * Arama metni → LIKE kalıbı: boş → null; kırpılır, {@link Locale#ROOT} ile küçük harfe çevrilir (sunucunun yerel ayarı
     * Türkçe olsa da "I" → "ı" olmaz), {@code !} {@code %} {@code _} karakterleri {@code !} ile kaçırılır (sorgularda
     * {@code ESCAPE '!'}). {@code AlertEventRepository.incidentLike} ile aynı kural.
     */
    static String likePattern(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String s = raw.trim().toLowerCase(Locale.ROOT)
                .replace("!", "!!").replace("%", "!%").replace("_", "!_");
        return "%" + s + "%";
    }

    /**
     * Liste / trend tarih sınırı → karşılaştırılabilir metin. Boş → null. Gün ({@code yyyy-MM-dd}) olduğu gibi kalır (eski
     * istemciler); bölgesiz / Z'li / ofsetli zaman damgası {@link IncidentPostmortemService#norm} ile
     * {@code yyyy-MM-ddTHH:mm:ss} UTC'ye çevrilir. Okunamayan değer → alan adlı 400 ({@code since} / {@code until}).
     */
    static String filterStamp(String raw, String field) {
        if (raw == null || raw.isBlank()) return null;
        String s = raw.trim();
        if (s.length() == 10 && isDay(s)) return s;
        String n = IncidentPostmortemService.norm(s);
        if (n == null || !yearInRange(n)) {
            String shown = s.length() > 40 ? s.substring(0, 40) + "…" : s;
            boolean start = "since".equals(field);
            throw new FieldValidationException(field, Msg.t(
                    (start ? "Başlangıç" : "Bitiş") + " tarihi okunamadı (\"" + shown + "\"). Tarihi yyyy-MM-ddTHH:mm:ss "
                            + "biçiminde (UTC) gönderin ya da tarih süzgecini temizleyip yeniden deneyin.",
                    "The " + (start ? "start" : "end") + " date could not be read (\"" + shown + "\"). Send it as "
                            + "yyyy-MM-ddTHH:mm:ss (UTC), or clear the date filter and try again."));
        }
        return n;
    }

    private static boolean isDay(String s) {
        try {
            java.time.LocalDate.parse(s);
            return true;
        } catch (java.time.format.DateTimeParseException e) {
            return false;
        }
    }

    public IncidentRecord get(Long id) {
        return repo.findById(id).orElseThrow(() -> new NoSuchElementException("Incident not found: " + id));
    }

    /** Trend + özet: günlük seri, severity/category kırılımı, özet sayılar. Yalnız tarih aralığı — {@link #trends(ListFilter, List)}. */
    public Map<String, Object> trends(String since, String until, List<Long> scope) {
        return trends(ListFilter.dates(since, until), scope);
    }

    /** Zamanlama satırlarının (MTTD + ortanca MTTR) üst sınırı — en yeni bu kadar kayıt; aşılırsa {@code capped}. */
    static final int TREND_TIMING_MAX_ROWS = 20_000;
    /** Süzgeçli günlük serinin okuduğu (UTC saat × önem) grup satırı üst sınırı — en yeni saatler kalır; aşılırsa {@code capped}. */
    public static final int TREND_DAILY_MAX_ROWS = 200_000;
    private static final java.time.ZoneId IST = java.time.ZoneId.of("Europe/Istanbul");

    /**
     * Trend + özet (2026-11-26, "Bul ve Raporla"): listenin süzgeçleriyle. Tarih aralığı DIŞINDA süzgeç yoksa sayılar bugünkü
     * sorgularla hesaplanır (aynı sonuç); varsa her sayı süzgeçli sorgulardan. İki yolda da ek alanlar: MTTD (tespit − oluş,
     * ortalama + ortanca), ortanca MTTR, takım kırılımı, etkilenen müşteri toplamı. Ortancalar en yeni
     * {@value #TREND_TIMING_MAX_ROWS} kayıt üzerinden ({@code capped}). Bilinmeyen değer null (ekranda "—"), uydurma 0 yok.
     */
    public Map<String, Object> trends(ListFilter filter, List<Long> scope) {
        return trends(filter, scope, false);
    }

    /**
     * {@code dailyOnly} (inceleme 2026-11-26): günlük grafik yalnız {@code daily}'yi okur — özet sayıları, kırılımlar, MTTD /
     * ortancalar (20 000 zaman satırı) ve müşteri toplamı HESAPLANMAZ; yanıt {@code {daily, [daily_capped], filtered,
     * daily_only}} (aramalı süzgeçte ~10 LIKE taraması yerine tek sorgu).
     */
    public Map<String, Object> trends(ListFilter filter, List<Long> scope, boolean dailyOnly) {
        ListFilter lf = filter == null ? ListFilter.dates(null, null) : filter;
        com.sitemonitor.repository.IncidentRecordQueries.Filter qf = toQuery(lf, scope);   // okunamayan değer → alan adlı 400
        boolean filtered = !lf.onlyDates();
        if (dailyOnly) {
            Map<String, Object> out = new LinkedHashMap<>();
            if (filtered) {
                DailyHours dh = filteredDaily(qf);
                out.put("daily", dh.daily());
                if (dh.capped()) out.put("daily_capped", true);
            } else {
                boolean scoped = scope != null;
                List<Long> scopeList = (scope != null && !scope.isEmpty()) ? scope : List.of(-1L);
                out.put("daily", dailyOf(repo.occurredAtSeverityInRange(qf.since(), qf.until(), scoped, scopeList)));
            }
            out.put("filtered", filtered);
            out.put("daily_only", true);
            return out;
        }
        Map<String, Object> out = filtered ? filteredTrends(qf) : legacyTrends(qf.since(), qf.until(), scope);
        out.put("filtered", filtered);
        addTrendExtras(out, qf);
        return out;
    }

    /** Süzgeçli günlük seri + tavan aşıldı mı. */
    record DailyHours(List<Map<String, Object>> daily, boolean capped) { }

    /**
     * Süzgeçli günlük seri: veritabanında UTC SAATİNE göre gruplanır (satır başına değil) — İstanbul sabit UTC+3 olduğundan
     * saat kovası yerel güne kayıpsız çevrilir; okunan satır sayısı dolu saat × önem sayısından gelir (en çok
     * {@value #TREND_DAILY_MAX_ROWS}, en yeni saatler kalır).
     */
    private DailyHours filteredDaily(com.sitemonitor.repository.IncidentRecordQueries.Filter qf) {
        List<Object[]> hourRows = repo.selectFiltered("SUBSTRING(i.occurredAt, 1, 13), i.severity, COUNT(i)", qf, null,
                " GROUP BY SUBSTRING(i.occurredAt, 1, 13), i.severity ORDER BY SUBSTRING(i.occurredAt, 1, 13) DESC",
                TREND_DAILY_MAX_ROWS + 1);
        boolean capped = hourRows.size() > TREND_DAILY_MAX_ROWS;
        if (capped) hourRows = hourRows.subList(0, TREND_DAILY_MAX_ROWS);
        return new DailyHours(dailyOfHours(hourRows), capped);
    }

    /** "Bugün / Son 7 gün / Son 30 gün" pencerelerinin başları — İstanbul takvim günü (bugün dahil 1 / 7 / 30 gün). */
    static String[] windowStarts(java.time.LocalDate today) {
        return new String[]{
                ISO.format(today.atStartOfDay(IST).toInstant()),
                ISO.format(today.minusDays(6).atStartOfDay(IST).toInstant()),
                ISO.format(today.minusDays(29).atStartOfDay(IST).toInstant())};
    }

    /**
     * {@link #dailyOf} ile aynı sonuç, saat kovalarından: satır {@code [UTC "yyyy-MM-ddTHH", önem, sayı]}. Saat ayrıştırılamazsa
     * ham UTC günü (ilk 10 karakter); 10 karakterden kısa değer sayılmaz (eski kural).
     */
    static List<Map<String, Object>> dailyOfHours(List<Object[]> rows) {
        TreeMap<String, long[]> dayBuckets = new TreeMap<>();   // gün → [critical, high, medium, low, total]
        for (Object[] row : rows) {
            String h = (String) row[0];
            if (h == null || h.length() < 10) continue;
            long cnt = row.length > 2 && row[2] instanceof Number n ? n.longValue() : 0L;
            String day;
            try {
                java.time.LocalDateTime utc = java.time.LocalDateTime.parse(h.length() >= 13 ? h.substring(0, 13) + ":00:00" : h);
                day = utc.atZone(java.time.ZoneOffset.UTC).withZoneSameInstant(IST).toLocalDate().toString();
            } catch (Exception ex) {
                day = h.substring(0, 10);
            }
            long[] b = dayBuckets.computeIfAbsent(day, k -> new long[5]);
            int idx = severityIndex(row.length > 1 ? (String) row[1] : null);
            if (idx >= 0) b[idx] += cnt;
            b[4] += cnt;
        }
        List<Map<String, Object>> daily = new ArrayList<>();
        dayBuckets.forEach((d, b) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("day", d);
            m.put("count", b[4]);
            m.put("critical", b[0]); m.put("high", b[1]); m.put("medium", b[2]); m.put("low", b[3]);
            daily.add(m);
        });
        return daily;
    }

    /** Günlük trend — olay zamanı UTC'den İstanbul gününe + önem kırılımı. Satır: {@code [occurredAt, severity]}. */
    private static List<Map<String, Object>> dailyOf(List<Object[]> rows) {
        // Günlük trend — olay zamanını UTC'den Europe/Istanbul'a çevirip YEREL güne göre grupla + ÖNEM kırılımı
        // (tabloda saatler IST gösterildiğinden; ham UTC günü gece-yarısı kayıtlarını bir önceki güne kaydırırdı).
        TreeMap<String, long[]> dayBuckets = new TreeMap<>();   // gün → [critical, high, medium, low, total]
        for (Object[] row : rows) {
            String oa = (String) row[0];
            if (oa == null || oa.length() < 10) continue;
            String day;
            try {
                java.time.LocalDateTime utc = java.time.LocalDateTime.parse(oa.substring(0, Math.min(19, oa.length())));
                day = utc.atZone(java.time.ZoneOffset.UTC).withZoneSameInstant(IST).toLocalDate().toString();
            } catch (Exception ex) {
                day = oa.substring(0, 10);   // ayrıştırılamazsa ham UTC günü
            }
            long[] b = dayBuckets.computeIfAbsent(day, k -> new long[5]);
            int idx = severityIndex(row.length > 1 ? (String) row[1] : null);
            if (idx >= 0) b[idx]++;
            b[4]++;
        }
        List<Map<String, Object>> daily = new ArrayList<>();
        dayBuckets.forEach((d, b) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("day", d);
            m.put("count", b[4]);
            m.put("critical", b[0]); m.put("high", b[1]); m.put("medium", b[2]); m.put("low", b[3]);
            daily.add(m);
        });
        return daily;
    }

    /** Ek süzgeçsiz yol — bugünkü sorgular (sayılar aynı; yalnız 7 / 30 gün penceresi takvim gününe hizalandı). */
    private Map<String, Object> legacyTrends(String s, String u, List<Long> scope) {
        boolean scoped = scope != null;                                          // null = global (admin/AUDIT)
        List<Long> scopeList = (scope != null && !scope.isEmpty()) ? scope : List.of(-1L); // boş kapsam → hiçbir şey eşleşmez

        List<Map<String, Object>> daily = dailyOf(repo.occurredAtSeverityInRange(s, u, scoped, scopeList));

        Map<String, Long> bySeverity = toCountMap(repo.countBySeverity(s, u, scoped, scopeList));
        Map<String, Long> byStatus   = toCountMap(repo.countByStatus(s, u, scoped, scopeList));
        Map<String, Long> byCategory = toCountMap(repo.countByCategory(s, u, scoped, scopeList));
        // channel CSV olabildiğinden combo'ya göre değil, virgülle bölüp TEKİL kanal bazında say.
        Map<String, Long> byChannel = new LinkedHashMap<>();
        for (Object[] row : repo.countByChannel(s, u, scoped, scopeList)) {
            String csv = (String) row[0];
            long cnt = ((Number) row[1]).longValue();
            if (csv == null) continue;
            for (String part : csv.split(",")) {
                String c = part.trim();
                if (!c.isEmpty()) byChannel.merge(c, cnt, Long::sum);
            }
        }
        long total    = repo.countRange(s, u, scoped, scopeList);
        long critical = bySeverity.getOrDefault("CRITICAL", 0L);
        long sla      = repo.countSlaBreached(s, u, scoped, scopeList);
        long open     = repo.countOpen(s, u, scoped, scopeList);
        long resolved = Math.max(0, total - open); // open = status<>RESOLVED → resolved = total - open
        long resolvedWithinSla = repo.countResolvedWithinSla(s, u, scoped, scopeList);
        // Zaman önayar kartları (Bugün / Son 7 / Son 30 gün) — sayfanın tarih filtresinden BAĞIMSIZ sabit pencereler.
        // İstanbul TAKVİM günü (2026-11-26): bugün dahil 1 / 7 / 30 gün. Eskiden 7 / 30 gün kayan UTC penceresiydi, karta
        // tıklayınca süzgeç 8 takvim günü kuruyordu — kart ile liste farklı sayı gösteriyordu.
        String[] w = windowStarts(java.time.LocalDate.now(IST));
        long today   = repo.countRange(w[0], null, scoped, scopeList);
        long last7d  = repo.countRange(w[1], null, scoped, scopeList);
        long last30d = repo.countRange(w[2], null, scoped, scopeList);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("daily", daily);
        out.put("by_severity", bySeverity);
        out.put("by_status", byStatus);
        out.put("by_category", byCategory);
        out.put("by_channel", byChannel);
        // MTTR (2026-09-28, Olay & Hata Geçmişi KPI'ı): çözülmüş olayların ortalama süresi (dk, yuvarlanmış) + örneklem.
        // Çözülmüş-süreli olay yoksa null (ekranda "—"; uydurma 0 dakika yazılmaz). Map.of 10 çifte sınırlı ve null
        // kabul etmez → sıralı LinkedHashMap.
        Long mttrMinutes = null;
        long mttrSample = 0;
        List<Object[]> dur = repo.resolvedDurationStats(s, u, scoped, scopeList);
        if (dur != null && !dur.isEmpty() && dur.get(0) != null) {
            Object[] row = dur.get(0);
            if (row.length > 1 && row[1] instanceof Number n) mttrSample = n.longValue();
            if (row.length > 0 && row[0] instanceof Number avg && mttrSample > 0) mttrMinutes = Math.round(avg.doubleValue());
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("total", total);
        summary.put("critical", critical);
        summary.put("sla_breached", sla);
        summary.put("open", open);
        summary.put("resolved", resolved);
        summary.put("resolved_within_sla", resolvedWithinSla);
        summary.put("today", today);
        summary.put("last_7d", last7d);
        summary.put("last_30d", last30d);
        summary.put("mttr_minutes", mttrMinutes);
        summary.put("mttr_sample", mttrSample);
        out.put("summary", summary);
        return out;
    }

    /** Çözülmüş-süreli olay koşulu (MTTR nüfusu) — eski {@code resolvedDurationStats} ile aynı. */
    private static final List<String> RESOLVED_WITH_DURATION = List.of(
            "i.status = 'RESOLVED'", "i.durationMinutes IS NOT NULL", "i.durationMinutes >= 0");

    /**
     * Süzgeçli yol — her sayı süzgeçli sorgulardan: önem / durum / kategori / SLA tek gruplu sorguda, kanal ayrı gruplu (CSV
     * tekil kanallara bölünür), günlük seri dar satırlardan (en çok {@value #TREND_DAILY_MAX_ROWS}), Bugün / 7 / 30 gün
     * pencereleri süzgeç + pencere başı (tarih aralığından bağımsız).
     */
    private Map<String, Object> filteredTrends(com.sitemonitor.repository.IncidentRecordQueries.Filter qf) {
        DailyHours dh = filteredDaily(qf);
        boolean dailyCapped = dh.capped();
        List<Map<String, Object>> daily = dh.daily();

        Map<String, Long> bySeverity = new LinkedHashMap<>();
        Map<String, Long> byStatus = new LinkedHashMap<>();
        Map<String, Long> byCategory = new LinkedHashMap<>();
        long total = 0, sla = 0, open = 0, resolvedWithinSla = 0;
        for (Object[] r : repo.selectFiltered("i.severity, i.status, i.category, i.slaBreached, COUNT(i)", qf, null,
                " GROUP BY i.severity, i.status, i.category, i.slaBreached", 0)) {
            long cnt = r[4] instanceof Number n ? n.longValue() : 0L;
            String sev = (String) r[0], st = (String) r[1], cat = (String) r[2];
            Boolean slaB = (Boolean) r[3];
            if (sev != null) bySeverity.merge(sev, cnt, Long::sum);
            if (st != null) byStatus.merge(st, cnt, Long::sum);
            if (cat != null) byCategory.merge(cat, cnt, Long::sum);
            total += cnt;
            if (Boolean.TRUE.equals(slaB)) sla += cnt;
            if (st != null && !"RESOLVED".equals(st)) open += cnt;
            if ("RESOLVED".equals(st) && Boolean.FALSE.equals(slaB)) resolvedWithinSla += cnt;
        }
        Map<String, Long> byChannel = new LinkedHashMap<>();
        for (Object[] row : repo.selectFiltered("i.channel, COUNT(i)", qf, List.of("i.channel IS NOT NULL"),
                " GROUP BY i.channel", 0)) {
            String csv = (String) row[0];
            long cnt = ((Number) row[1]).longValue();
            if (csv == null) continue;
            for (String part : csv.split(",")) {
                String c = part.trim();
                if (!c.isEmpty()) byChannel.merge(c, cnt, Long::sum);
            }
        }
        String[] w = windowStarts(java.time.LocalDate.now(IST));
        long today   = repo.countFiltered(qf.withDates(w[0], null));
        long last7d  = repo.countFiltered(qf.withDates(w[1], null));
        long last30d = repo.countFiltered(qf.withDates(w[2], null));

        Long mttrMinutes = null;
        long mttrSample = 0;
        List<Object[]> dur = repo.selectFiltered("AVG(i.durationMinutes), COUNT(i)", qf, RESOLVED_WITH_DURATION, null, 0);
        if (!dur.isEmpty() && dur.get(0) != null) {
            Object[] row = dur.get(0);
            if (row.length > 1 && row[1] instanceof Number n) mttrSample = n.longValue();
            if (row.length > 0 && row[0] instanceof Number avg && mttrSample > 0) mttrMinutes = Math.round(avg.doubleValue());
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("daily", daily);
        out.put("by_severity", bySeverity);
        out.put("by_status", byStatus);
        out.put("by_category", byCategory);
        out.put("by_channel", byChannel);
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("total", total);
        summary.put("critical", bySeverity.getOrDefault("CRITICAL", 0L));
        summary.put("sla_breached", sla);
        summary.put("open", open);
        summary.put("resolved", Math.max(0, total - open));
        summary.put("resolved_within_sla", resolvedWithinSla);
        summary.put("today", today);
        summary.put("last_7d", last7d);
        summary.put("last_30d", last30d);
        summary.put("mttr_minutes", mttrMinutes);
        summary.put("mttr_sample", mttrSample);
        out.put("summary", summary);
        if (dailyCapped) out.put("daily_capped", true);
        return out;
    }

    /**
     * İki yolda da eklenen alanlar: takım kırılımı ({@code by_team}, en çok kayıt önce, takımsız {@code team_id} null), etkilenen
     * müşteri toplamı ({@code customers_sum} — değer girilmiş kayıt yoksa null, {@code customers_sample}), MTTD (tespit − oluş;
     * tespit zamanı olan ve oluştan önce olmayan kayıtlar — ortalama + ortanca, {@code mttd_sample}) ve ortanca MTTR. Ortancalar
     * en yeni {@value #TREND_TIMING_MAX_ROWS} kayıttan; aşılırsa {@code capped=true} ({@code rows_read}).
     */
    @SuppressWarnings("unchecked")
    private void addTrendExtras(Map<String, Object> out, com.sitemonitor.repository.IncidentRecordQueries.Filter qf) {
        Map<String, Object> summary = (Map<String, Object>) out.get("summary");
        List<Map<String, Object>> byTeam = new ArrayList<>();
        for (Object[] r : repo.selectFiltered("i.teamId, MAX(i.teamName), COUNT(i)", qf, null, " GROUP BY i.teamId", 0)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("team_id", r[0]);
            m.put("team_name", r[1]);
            m.put("count", r[2] instanceof Number n ? n.longValue() : 0L);
            byTeam.add(m);
        }
        java.text.Collator col = java.text.Collator.getInstance(Locale.forLanguageTag("tr"));
        byTeam.sort((a, b) -> {
            int c = Long.compare((Long) b.get("count"), (Long) a.get("count"));
            if (c != 0) return c;
            if (a.get("team_id") == null) return 1;   // takımsız sonda
            if (b.get("team_id") == null) return -1;
            return col.compare(String.valueOf(a.get("team_name")), String.valueOf(b.get("team_name")));
        });
        out.put("by_team", byTeam);

        Long customersSum = null;
        long customersSample = 0;
        List<Object[]> cust = repo.selectFiltered("SUM(i.affectedCustomers), COUNT(i.affectedCustomers)", qf, null, null, 0);
        if (!cust.isEmpty() && cust.get(0) != null) {
            Object[] row = cust.get(0);
            if (row.length > 1 && row[1] instanceof Number n) customersSample = n.longValue();
            if (customersSample > 0 && row[0] instanceof Number s) customersSum = s.longValue();
        }
        summary.put("customers_sum", customersSum);
        summary.put("customers_sample", customersSample);

        List<Object[]> timing = repo.selectFiltered("i.occurredAt, i.detectedAt, i.durationMinutes, i.status", qf, null,
                " ORDER BY i.occurredAt DESC, i.id DESC", TREND_TIMING_MAX_ROWS + 1);
        boolean capped = timing.size() > TREND_TIMING_MAX_ROWS;
        if (capped) timing = timing.subList(0, TREND_TIMING_MAX_ROWS);
        List<Double> detect = new ArrayList<>();
        List<Double> repair = new ArrayList<>();
        for (Object[] r : timing) {
            Double d = minutesBetweenStamps((String) r[0], (String) r[1]);
            if (d != null) detect.add(d);
            if ("RESOLVED".equals(r[3]) && r[2] instanceof Number n && n.intValue() >= 0) repair.add(n.doubleValue());
        }
        summary.put("mttd_minutes", detect.isEmpty() ? null : Math.round(detect.stream().mapToDouble(Double::doubleValue).average().orElse(0)));
        summary.put("mttd_median_minutes", median(detect));
        summary.put("mttd_sample", (long) detect.size());
        summary.put("mttr_median_minutes", median(repair));
        out.put("capped", capped || Boolean.TRUE.equals(out.get("daily_capped")));
        out.put("rows_read", timing.size());
    }

    /** İki UTC zaman damgası arası dakika (ikinci ilkinden önceyse ya da okunamazsa null). */
    static Double minutesBetweenStamps(String from, String to) {
        java.time.LocalDateTime a = timingStamp(from);
        java.time.LocalDateTime b = timingStamp(to);
        if (a == null || b == null) return null;
        long secs = java.time.Duration.between(a, b).getSeconds();
        return secs < 0 ? null : secs / 60d;
    }

    /** {@code yyyy-MM-ddTHH:mm[:ss…]} UTC damgası → zaman (saniyesiz eski kayıt ":00" sayılır); okunamazsa null. */
    static java.time.LocalDateTime timingStamp(String v) {
        if (v == null) return null;
        String s = v.trim();
        if (s.length() >= 19) s = s.substring(0, 19);
        else if (s.length() == 16) s = s + ":00";
        else return null;
        try {
            return java.time.LocalDateTime.parse(s);
        } catch (java.time.format.DateTimeParseException e) {
            return null;
        }
    }

    /** Ortanca (çift sayıda ortadaki ikisinin ortalaması), yuvarlanmış dakika; boş → null. */
    static Long median(List<Double> values) {
        if (values == null || values.isEmpty()) return null;
        List<Double> s = new ArrayList<>(values);
        Collections.sort(s);
        int n = s.size();
        double m = n % 2 == 1 ? s.get(n / 2) : (s.get(n / 2 - 1) + s.get(n / 2)) / 2d;
        return Math.round(m);
    }

    // ── Write ───────────────────────────────────────────────────────────────

    @Transactional
    public IncidentRecord create(Map<String, Object> body, String actorName, Long actorId, Long actorTeamId) {
        IncidentRecord e = new IncidentRecord();
        applyBody(e, body, true);
        e.setCreatedBy(actorName);
        e.setCreatedById(actorId);
        e.setCreatedByTeamId(actorTeamId);
        e.setCreatedAt(now());
        e.setUpdatedAt(now());
        e.setUpdatedBy(actorName);
        IncidentRecord saved = repo.save(e);
        linkImages(saved, actorName);
        return saved;
    }

    /**
     * Kısmi güncelleme (2026-11-20, "Sağlam Kayıt"): gövdede OLMAYAN anahtar dokunulmaz, açık JSON null temizler.
     *
     * <p><b>Çakışma koruması.</b> Gövde {@code version} taşıyorsa (düzenleme formu açılışta okuduğu değeri gönderir) kayıttaki
     * sürümle karşılaştırılır; farklıysa 409 {@code CONCURRENT_MODIFICATION} + {@code current_version}, {@code updated_by},
     * {@code updated_at} — hiçbir alan yazılmaz. {@code version} YOKSA bugünkü davranış (son yazan kazanır; eski istemci /
     * eski pod / iç çağıranlar). Okuma ile yazma arasındaki yarış Hibernate'in kendi sürüm denetimine düşer
     * ({@code saveAndFlush} → {@code ObjectOptimisticLockingFailureException} → aynı 409). Yanıttaki kayıt YENİ sürümü taşır.
     */
    @Transactional
    public IncidentRecord update(Long id, Map<String, Object> body, String actorName) {
        IncidentRecord e = get(id);
        requireVersion(e, body);
        applyBody(e, body, false);
        e.setUpdatedAt(now());
        e.setUpdatedBy(actorName);
        IncidentRecord saved = repo.saveAndFlush(e);
        linkImages(saved, actorName);
        publishChanged(saved.getId());
        return saved;
    }

    /**
     * İstemcinin gördüğü sürüm ({@code version}) kayıttakiyle aynı mı. Anahtar yok / null / boş metin → denetim yok
     * (bugünkü davranış). Negatif olmayan tam sayı değilse alan adlı 400; farklıysa 409 {@code CONCURRENT_MODIFICATION}.
     */
    static void requireVersion(IncidentRecord e, Map<String, Object> body) {
        if (body == null || !body.containsKey("version")) return;
        Object raw = body.get("version");
        if (raw == null || (raw instanceof String s && s.isBlank())) return;
        Long sent = strictWhole(raw, Long.MAX_VALUE);
        if (sent == null) {
            throw new FieldValidationException("version", Msg.t(
                    "Kayıt sürümü okunamadı: 0 ya da daha büyük bir tam sayı olmalı. Sayfayı yenileyip kaydı yeniden açın.",
                    "The record version could not be read: it must be a whole number of 0 or more. Reload the page and open the record again."));
        }
        long current = e.getVersion() == null ? 0L : e.getVersion();
        if (sent == current) return;
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("current_version", current);
        extra.put("updated_by", e.getUpdatedBy());
        extra.put("updated_at", e.getUpdatedAt());
        String who = e.getUpdatedBy() == null || e.getUpdatedBy().isBlank() ? null : e.getUpdatedBy();
        throw new com.sitemonitor.config.GlobalExceptionHandler.CodedConflictException("CONCURRENT_MODIFICATION", Msg.t(
                "Kayıt siz düzenlerken " + (who != null ? who + " tarafından " : "başka biri tarafından ")
                        + "değiştirildi; değişikliğiniz kaydedilmedi. Son hâlini yükleyip değişikliğinizi yeniden uygulayın.",
                "This record was changed by " + (who != null ? who : "someone else")
                        + " while you were editing it, so your change wasn't saved. Load the latest version and apply your change again."),
                extra);
    }

    @Transactional
    public IncidentRecord delete(Long id) {
        IncidentRecord e = get(id);
        repo.delete(e);
        return e;
    }

    /**
     * Toplu takım transferi — seçili olayların takımını (teamId+teamName) değiştirir. Takım adı veritabanındaki takımdan
     * okunur (2026-11-20; satır yoksa null — PUT ile aynı; istemcinin adı yalnız takım deposu yokken). Her kaydın sürümü artar
     * (Hibernate @Version): o sırada açık bir düzenleme formu kaydederken 409 alır.
     */
    @Transactional
    public int transfer(List<Long> ids, Long teamId, String teamName, String actor) {
        if (ids == null || ids.isEmpty() || teamId == null) return 0;
        List<IncidentRecord> recs = repo.findAllById(ids);
        String ts = now();
        // Ad yalnız veritabanındaki takım satırından (PUT ile aynı kural); satır yoksa null. İstemcinin adı yalnız takım deposu
        // yokken (elle kurulan birim testleri — eski davranış) kullanılır.
        String name = teamRepo == null ? teamName : teamNameOf(teamId).orElse(null);
        for (IncidentRecord e : recs) {
            e.setTeamId(teamId);
            e.setTeamName(name);
            e.setUpdatedAt(ts);
            e.setUpdatedBy(actor);
        }
        repo.saveAll(recs);
        for (IncidentRecord r : recs) publishChanged(r.getId());
        return recs.size();
    }

    // ── Görseller (markdown alanlarına gömülür) ───────────────────────────────

    @Transactional
    public IncidentImage storeImage(Long incidentId, String caption, MultipartFile file, String actorName) {
        // Kayda yükleme SIRAYA girer (2026-11-20): olay satırı işlem boyunca kilitlenir — toplam denetimi ile ekleme arasına
        // aynı kaydın başka bir yüklemesi giremez (iki eşzamanlı yükleme birlikte sınırı aşamaz). Yoksa 404.
        IncidentRecord target = null;
        if (incidentId != null) {
            repo.lockRow(incidentId);
            target = get(incidentId);
        }
        // incidentId null = taslak yükleme (yeni olay henüz kaydedilmedi); kaydedince
        // create/update markdown'daki görsel id'lerini linkImages ile olaya bağlar.
        // Tam çözünürlük görsel (2026-11-20, "Hızlı Giriş"): istemci artık 1000 px / 70 KB'a küçültmüyor — tür BAYTLARDAN,
        // ölçü başlıktan (çözmeden) denetlenir; saklanan tür istemcinin başlığı değil baytların türüdür.
        if (file == null || file.isEmpty()) {
            throw new FieldValidationException("file", Msg.t(
                    "Dosya boş. Görseli PNG ya da JPEG olarak kaydedip yeniden yükleyin.",
                    "The file is empty. Save the image as PNG or JPEG and upload it again."));
        }
        if (file.getSize() > imageMaxBytes) {
            // Gövde okunmadan (büyük dosya belleğe alınmaz) — doğrulayıcının iletisiyle aynı
            throw com.sitemonitor.service.incident.IncidentImageValidator.tooLarge(imageMaxBytes);
        }
        byte[] data;
        try {
            data = file.getBytes();
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Görsel okunamadı", e);
        }
        com.sitemonitor.service.incident.IncidentImageValidator.Checked checked =
                com.sitemonitor.service.incident.IncidentImageValidator.check(data, imageMaxBytes);
        requireImageTotal(incidentId, target, actorName, data.length);
        IncidentImage img = new IncidentImage();
        img.setIncidentId(incidentId);
        String cap = caption == null ? null : caption.trim();
        img.setCaption(cap == null || cap.isEmpty() ? null : (cap.length() > 300 ? cap.substring(0, 300) : cap));
        img.setContentType(checked.contentType());
        img.setSizeBytes((long) data.length);
        img.setData(data);
        img.setCreatedBy(actorName);
        img.setCreatedAt(now());
        return imageRepo.save(img);
    }

    /** Kayıt başına görsellerin toplam üst sınırı (bayt) — {@code site.monitor.incident.image-total-max-bytes}. */
    @Value("${site.monitor.incident.image-total-max-bytes:41943040}")
    private long imageTotalMaxBytes = 40L * 1024 * 1024;

    /** Taslak görsellerin toplamında sayılan pencere: kullanıcının son 24 saatte yüklediği, olaya bağlanmamış görseller. */
    static final java.time.Duration DRAFT_WINDOW = java.time.Duration.ofHours(24);

    /** Toplamda sayılan, kayıtlı metinde geçen görsel kimliklerinin üst sınırı (daha fazlası sayılmaz — kullanıcı lehine). */
    static final int MAX_COUNTED_REFS = 1000;

    /**
     * Kayıt başına görsel toplamı (2026-11-20; inceleme düzeltmesi): bu dosya +
     * <ul>
     *   <li>düzenleme — olaya bağlı görsellerden kaydın SAKLANAN dört markdown metninde geçenler (yaşı ne olursa olsun) ve
     *       son 24 saatte bu kayda yüklenip henüz metne girmemiş olanlar (süren bir düzenleme oturumu); metinden çıkarılıp
     *       kaydedilen görsel ve kullanılmadan kalan eski yükleme SAYILMAZ (aksi hâlde birkaç tam çözünürlük denemesi kaydı
     *       kalıcı olarak kilitlerdi — görsel silme yolu yok);</li>
     *   <li>yeni kayıt — kullanıcının son 24 saatteki bağlanmamış taslakları.</li>
     * </ul>
     * sınırı aşamaz. Aşan yükleme alan adlı 400 (kod {@code INCIDENT_IMAGE_TOTAL_LIMIT}). Kaydedilirken bağlanan taslaklar
     * yeniden denetlenmez (kabul edilen sınır). Düzenlemede çağıran olay satırını kilitlemiştir.
     */
    private void requireImageTotal(Long incidentId, IncidentRecord target, String actorName, long adding) {
        long cap = imageTotalMaxBytes > 0 ? imageTotalMaxBytes : 40L * 1024 * 1024;
        String since = ISO.format(Instant.now().minus(DRAFT_WINDOW));
        Long current;
        if (incidentId != null) {
            List<Long> refs = new ArrayList<>(imageRefs(target));
            if (refs.size() > MAX_COUNTED_REFS) refs = refs.subList(0, MAX_COUNTED_REFS);
            if (refs.isEmpty()) refs = List.of(-1L);
            current = imageRepo.sumCountedForIncident(incidentId, refs, since);
        } else {
            current = actorName == null ? Long.valueOf(0) : imageRepo.sumDraftSizeByCreator(actorName, since);
        }
        long used = current == null ? 0 : current;
        if (used + adding > cap) {
            long mb = Math.max(1, cap / (1024 * 1024));
            throw new FieldValidationException("INCIDENT_IMAGE_TOTAL_LIMIT", "file", Msg.t(
                    "Bu kayda eklenen görsellerin toplamı en çok " + mb + " MB olabilir (şu an " + mbText(used)
                            + " MB sayılıyor). Artık gerekmeyen görselleri metinden çıkarıp kaydedin; yüklenip hiç "
                            + "kullanılmayan görseller 24 saat sonra sayılmaz. Ya da daha küçük bir görsel yükleyin.",
                    "The images added to this record can total at most " + mb + " MB (" + mbText(used)
                            + " MB counted now). Remove images you no longer need from the text and save; images that "
                            + "were uploaded but never used stop counting after 24 hours. Or upload a smaller image."));
        }
    }

    /** Kaydın dört markdown metninde geçen olay görseli kimlikleri (sıralı, tekil). */
    private static Set<Long> imageRefs(IncidentRecord e) {
        Set<Long> ids = new java.util.LinkedHashSet<>();
        if (e == null) return ids;
        for (String body : List.of(
                Optional.ofNullable(e.getRcaSummary()).orElse(""),
                Optional.ofNullable(e.getDescription()).orElse(""),
                Optional.ofNullable(e.getResolutionSteps()).orElse(""),
                Optional.ofNullable(e.getBusinessImpact()).orElse(""))) {
            Matcher m = IMG_REF.matcher(body);
            while (m.find()) {
                try { ids.add(Long.valueOf(m.group(1))); } catch (NumberFormatException ignored) { /* aşırı uzun sayı */ }
            }
        }
        return ids;
    }

    private static String mbText(long bytes) {
        return String.format(Locale.ROOT, "%.1f", bytes / (1024d * 1024d));
    }

    public IncidentImage getImage(Long imageId) {
        return imageRepo.findById(imageId)
                .orElseThrow(() -> new NoSuchElementException("Image not found: " + imageId));
    }

    // ── Yönetilen seçenekler (kanal / domain) — incident sayfasından genişletilebilir ─────────

    /** Tip için seçenek listesi = kayıtlı seçenekler ∪ olaylarda fiilen kullanılan değerler
     *  (case-insensitive, alfabetik). Böylece API'den/eski kayıttan gelen değer de dropdown'da görünür. */
    public List<String> listOptions(String type, Long teamId, boolean admin) {
        String t = normType(type);
        Set<String> set = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        // Kayıtlı seçenekler — admin: tüm takımlar; kullanıcı: global + kendi takımı.
        List<IncidentOption> opts = admin ? optionRepo.findByTypeOrderByValueAsc(t)
                                          : optionRepo.findByTypeForTeam(t, teamId);
        for (IncidentOption o : opts) set.add(o.getValue());
        // Olaylarda fiilen kullanılan değerler — takıma göre (sızıntı yok). admin: tüm olaylar;
        // takımlı kullanıcı: kendi takımının olayları; takımsız non-admin: olay-türevi öneri yok.
        if (admin || teamId != null) {
            Long dt = admin ? null : teamId;   // null → distinct sorgusu tümünü döndürür (admin)
            List<String> distinct = OPT_CHANNEL.equals(t) ? repo.distinctChannels(dt)
                    : OPT_DOMAIN.equals(t) ? repo.distinctServices(dt)
                    : OPT_ERROR_CODE.equals(t) ? repo.distinctErrorCodes(dt)
                    : OPT_FUNCTION_CODE.equals(t) ? repo.distinctFunctionCodes(dt)
                    : OPT_CHANNEL_CODE.equals(t) ? repo.distinctChannelCodes(dt) : List.of();
            for (String csv : distinct) {
                if (csv == null) continue;
                for (String part : csv.split(",")) {
                    String v = part.trim();
                    if (!v.isEmpty()) set.add(v);
                }
            }
        }
        return new ArrayList<>(set);
    }

    /** Incident sayfasından yeni seçenek ekler (creatable dropdown). Seçenek ekleyenin takımına yazılır
     *  (admin → global). Aynı değer kapsamda (global + o takım) zaten varsa eklenmez. */
    @Transactional
    public String addOption(String type, String value, String actor, Long teamId) {
        String t = normType(type);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Değer boş olamaz");
        String v = value.trim();
        if (v.length() > 150) v = v.substring(0, 150);
        ensureOption(t, v, actor, teamId);
        return v;
    }

    /** Incident sayfasından bir seçeneği siler. Kullanıcı yalnız KENDİ takımının seçeneğini silebilir
     *  (global/başka takım dokunulmaz); admin her şeyi silebilir. Not: değer bir olayda fiilen
     *  kullanılıyorsa listOptions union'ı onu yine gösterir. */
    @Transactional
    public void removeOption(String type, String value, Long teamId, boolean admin) {
        String t = normType(type);
        if (value == null || value.isBlank()) return;
        String v = value.trim();
        List<IncidentOption> found = admin ? optionRepo.findByTypeAndValue(t, v)
                : (teamId == null ? List.of() : optionRepo.findByTypeAndValueAndTeam(t, v, teamId));
        if (!found.isEmpty()) optionRepo.deleteAll(found);
    }

    // ── Seçenek yönetimi: yeniden adlandır / birleştir (2026-11-26, "Bul ve Raporla") ────────────────────────────────────

    /** Seçenek türü → olay kaydı özniteliği (CSV alanları birden çok değer taşır). */
    static final Map<String, String> OPTION_ATTRIBUTE = Map.of(
            OPT_CHANNEL, "channel", OPT_DOMAIN, "service",
            OPT_ERROR_CODE, "errorCode", OPT_FUNCTION_CODE, "functionCode", OPT_CHANNEL_CODE, "channelCode");
    /** Virgülle ayrılmış (çok değerli) seçenek türleri. */
    static final Set<String> CSV_OPTION_TYPES = Set.of(OPT_CHANNEL, OPT_DOMAIN);
    /** Tek istekte birleştirilebilecek en çok kaynak değer. */
    public static final int MAX_RENAME_SOURCES = 20;
    /** Tek istekte yeniden yazılabilecek en çok kayıt — aşan istek hiçbir şey yazmaz (alan adlı 400). */
    public static final int MAX_RENAME_RECORDS = 5_000;
    /** Kullanım listesinin okuduğu en çok kayıt (inceleme 2026-11-26) — aşılırsa sayılar alt sınırdır ({@code truncated}). */
    public static final int OPTION_USAGE_MAX_ROWS = 20_000;
    /** Yeniden adlandırma aday taramasının en çok kaydı — aşılırsa hiçbir şey yazılmaz (alan adlı 400). */
    public static final int RENAME_SCAN_MAX_ROWS = 50_000;
    /** Okuma tavanları (testler küçültür; üretimde yukarıdaki sabitler). */
    int optionUsageMaxRows = OPTION_USAGE_MAX_ROWS;
    int renameScanMaxRows = RENAME_SCAN_MAX_ROWS;

    /** Bir değerin kullanımı: değer (ilk görülen yazım), takımın kayıt sayısı, takım seçeneği mi, genel seçenek mi. */
    public record OptionUsage(String value, long records, boolean teamOption, boolean globalOption) { }

    /**
     * Kullanım listesi + okuma tavanı: {@code truncated} = takımın {@link #OPTION_USAGE_MAX_ROWS} kaydından fazlası vardı —
     * sayılar alt sınırdır, tavanın ötesindeki kayıtlarda geçen değer listede olmayabilir.
     */
    public record UsageReport(List<OptionUsage> values, boolean truncated, int rowsRead) { }

    /** Yeniden adlandırma / birleştirme sonucu. */
    public record RenameResult(String type, Long teamId, String to, boolean merge, int records, Map<String, Integer> perValue,
                               List<Long> recordIds, int optionsRemoved, List<String> globalKept) { }

    /**
     * Takımın bir seçenek türündeki değerleri ve kullanım sayıları (2026-11-26): takımın kayıtlarında geçen (CSV alanında her
     * öğe ayrı) + takımın ve genel seçenek satırları. Eşleşme büyük-küçük harf duyarsız (seçenek karşılaştırmasıyla aynı);
     * gösterilen yazım: seçenek satırınınki, yoksa kayıtlarda ilk görülen. Takımın kaydı = kaydın takımı bu takım ya da
     * takımsız kayıtta giren takım bu takım.
     */
    public UsageReport optionUsage(String type, Long teamId) {
        String t = normType(type);
        Map<String, String> spelling = new LinkedHashMap<>();
        Map<String, Long> counts = new HashMap<>();
        Set<String> teamOpt = new HashSet<>(), globalOpt = new HashSet<>();
        for (IncidentOption o : optionRepo.findByTypeForTeam(t, teamId)) {
            if (o.getValue() == null || o.getValue().isBlank()) continue;
            String k = o.getValue().trim().toLowerCase(Locale.ROOT);
            spelling.putIfAbsent(k, o.getValue().trim());
            if (o.getTeamId() == null) globalOpt.add(k); else teamOpt.add(k);
        }
        boolean csv = CSV_OPTION_TYPES.contains(t);
        int cap = optionUsageMaxRows;
        List<Object[]> rows = repo.teamFieldValues(OPTION_ATTRIBUTE.get(t), teamId, cap + 1);
        boolean truncated = rows.size() > cap;
        if (truncated) rows = rows.subList(0, cap);
        for (Object[] r : rows) {
            Set<String> seen = new HashSet<>();
            for (String v : csv ? splitCsv((String) r[1]) : List.of(((String) r[1]).trim())) {
                if (v.isEmpty()) continue;
                String k = v.toLowerCase(Locale.ROOT);
                spelling.putIfAbsent(k, v);
                if (seen.add(k)) counts.merge(k, 1L, Long::sum);
            }
        }
        List<OptionUsage> out = new ArrayList<>();
        spelling.forEach((k, v) -> out.add(new OptionUsage(v, counts.getOrDefault(k, 0L), teamOpt.contains(k), globalOpt.contains(k))));
        java.text.Collator col = java.text.Collator.getInstance(Locale.forLanguageTag("tr"));
        out.sort((a, b) -> col.compare(a.value(), b.value()));
        return new UsageReport(out, truncated, rows.size());
    }

    /**
     * Seçenek değerlerini TEK takımın kayıtlarında yeniden adlandırır / birleştirir (2026-11-26). Kaynak değerlerden biriyle
     * eşleşen TAM öğe (CSV alanında virgüller arası, kod alanında değerin tamamı; büyük-küçük harf duyarsız, kırpılmış)
     * hedefle değişir; birleşmede aynı kayıtta iki kez geçen hedef tekilleşir (ilk yerinde kalır). Her yeniden yazılan kayıt
     * sürümü artırır (iyimser kilit — o anda açık bir düzenleme formu 409 alır), {@code updated_at} / {@code updated_by}
     * damgalanır. Yazmadan önce satırlar kimlik sırasıyla KİLİTLENİR ve yeni değer güncel değerden hesaplanır (arada kaydedilen
     * düzenleme ezilmez). {@link IncidentRecordChangedEvent} YAYIMLANMAZ: seçenek alanları teknik hata raporu eşlemesinde yok;
     * olayla eşit (bayat olmayan) rapor bağlarının {@code incident_updated_at}'i aynı işlemde yeni zamana taşınır
     * ({@code TechErrorReportIncidentRepository.carryInSyncLinks}) — bağ "güncel" kalır, senkron kuyruğu dolmaz.
     * Takımın kaynak değerli seçenek satırları silinir, hedef yoksa takıma eklenir;
     * GENEL seçenek satırlarına dokunulmaz ({@code globalKept}). Hepsi tek işlem: bir kayıt yazılamazsa hiçbiri yazılmaz.
     */
    @Transactional
    public RenameResult renameOptions(String type, Long teamId, List<String> fromRaw, String toRaw, String actor) {
        String t = normType(type);
        if (teamId == null) {
            throw new FieldValidationException("team_id", Msg.t("Takım seçin.", "Choose a team."));
        }
        boolean csv = CSV_OPTION_TYPES.contains(t);
        int maxLen = csv ? 150 : MAX_CODE;
        String to = toRaw == null ? "" : toRaw.trim();
        if (to.isEmpty()) {
            throw new FieldValidationException("to", Msg.t("Yeni değeri yazın.", "Enter the new value."));
        }
        if (to.length() > maxLen) {
            throw new FieldValidationException("to", Msg.t("Yeni değer en çok " + maxLen + " karakter olabilir.",
                    "The new value can be at most " + maxLen + " characters."));
        }
        if (csv && to.indexOf(',') >= 0) {
            throw new FieldValidationException("to", Msg.t(
                    "Yeni değer virgül içeremez (virgül değerleri ayırır). Virgülsüz bir ad yazın.",
                    "The new value can't contain a comma (commas separate values). Enter a name without a comma."));
        }
        LinkedHashMap<String, String> from = new LinkedHashMap<>();   // küçük harf → yazım
        if (fromRaw != null) {
            for (String f : fromRaw) {
                if (f == null || f.isBlank()) continue;
                String v = f.trim();
                if (v.length() > 150) {
                    throw new FieldValidationException("from", Msg.t("Eski değer en çok 150 karakter olabilir.",
                            "An old value can be at most 150 characters."));
                }
                from.putIfAbsent(v.toLowerCase(Locale.ROOT), v);
            }
        }
        if (from.isEmpty()) {
            throw new FieldValidationException("from", Msg.t("Değiştirilecek en az bir değer seçin.", "Choose at least one value to change."));
        }
        if (from.size() > MAX_RENAME_SOURCES) {
            throw new FieldValidationException("from", Msg.t(
                    "Tek seferde en çok " + MAX_RENAME_SOURCES + " değer birleştirilebilir.",
                    "At most " + MAX_RENAME_SOURCES + " values can be merged at once."));
        }
        String toKey = to.toLowerCase(Locale.ROOT);
        if (from.size() == 1 && from.containsKey(toKey) && from.get(toKey).equals(to)) {
            throw new FieldValidationException("to", Msg.t(
                    "Yeni değer eskisiyle aynı. Farklı bir ad yazın.", "The new value is the same as the old one. Enter a different name."));
        }

        String attribute = OPTION_ATTRIBUTE.get(t);
        int fieldMax = csv ? MAX_LIST : MAX_CODE;
        // 1) Aday tarama — dar izdüşüm (id, değer), kilitsiz: hangi kayıtların değişeceği, sınırlar, birleştirme mi.
        List<Long> ids = new ArrayList<>();
        boolean targetUsed = false;
        int scanCap = renameScanMaxRows;
        List<Object[]> scan = repo.teamFieldValues(attribute, teamId, scanCap + 1);
        if (scan.size() > scanCap) {
            throw new FieldValidationException("from", Msg.t(
                    "Bu takımda bu alanı dolu " + scanCap + " kayıttan fazlası var; tek seferde taranamıyor. "
                            + "Hiçbir kayıt değiştirilmedi — sistem yöneticisine başvurun.",
                    "This team has more than " + scanCap + " records with this field filled; they can't be scanned"
                            + " at once. No record was changed — contact the system administrator."));
        }
        for (Object[] r : scan) {
            Long id = ((Number) r[0]).longValue();
            String cur = (String) r[1];
            OptionRewrite rw = rewriteOption(cur, csv, from, to, toKey);
            if (rw.targetUsed()) targetUsed = true;
            if (rw.hits().isEmpty()) continue;
            requireFits(rw.value(), fieldMax, id, csv);
            if (!Objects.equals(rw.value(), cur)) ids.add(id);
        }
        if (ids.size() > MAX_RENAME_RECORDS) {
            throw new FieldValidationException("from", Msg.t(
                    ids.size() + " kayıt değişecek; tek seferde en çok " + MAX_RENAME_RECORDS + " kayıt değiştirilebilir. "
                            + "Değerleri daha küçük gruplarla birleştirin.",
                    ids.size() + " records would change; at most " + MAX_RENAME_RECORDS + " records can be changed at once. "
                            + "Merge the values in smaller groups."));
        }
        boolean merge = from.size() > 1 || targetUsed;
        String ts = now();
        Map<String, Integer> perValue = new LinkedHashMap<>();
        for (String k : from.keySet()) perValue.put(from.get(k), 0);
        List<IncidentRecord> saved = new ArrayList<>();
        // 2) Yazma — 500'lük parçalar, kimlik sırasıyla: satırlar KİLİTLENİR, yeni değer kilitten sonra okunan GÜNCEL değerden
        //    yeniden hesaplanır (aday taramadan sonra kaydedilen bir düzenleme sessizce ezilmez; takımı değişen kayıt atlanır).
        for (int k = 0; k < ids.size(); k += 500) {
            List<Long> part = ids.subList(k, Math.min(ids.size(), k + 500));
            repo.lockRows(part);
            // Önce PLAN: kilitten sonra okunan güncel değerden hangi kayıt gerçekten değişecek (takımı değişen, değeri artık
            // eşleşmeyen ya da sonucu aynı kalan kayıt atlanır). Varlıklar henüz DEĞİŞTİRİLMEZ — aşağıdaki toplu bağ güncellemesi
            // otomatik flush yapar; kirli varlık o anda yazılsaydı updated_at önce değişir, bağ "olayla eşit" sayılmazdı.
            List<IncidentRecord> chunk = repo.findAllById(part);
            List<IncidentRecord> changed = new ArrayList<>();
            List<String> values = new ArrayList<>();
            for (IncidentRecord e : chunk) {
                boolean teamsRecord = Objects.equals(e.getTeamId(), teamId)
                        || (e.getTeamId() == null && Objects.equals(e.getCreatedByTeamId(), teamId));
                if (!teamsRecord) continue;
                String cur = optionField(e, attribute);
                OptionRewrite rw = rewriteOption(cur, csv, from, to, toKey);
                if (rw.hits().isEmpty() || Objects.equals(rw.value(), cur)) continue;
                requireFits(rw.value(), fieldMax, e.getId(), csv);
                // Değer başına sayı YALNIZ gerçekten değişen kayıtları sayar (inceleme 2026-11-26): sonucu aynı kalan kayıt
                // ("ATM" → "ATM") sayılmaz — bildirim ve denetim yazılan kayıtlarla tutarlı.
                for (String h : rw.hits()) perValue.merge(h, 1, Integer::sum);
                changed.add(e);
                values.add(rw.value());
            }
            if (changed.isEmpty()) continue;
            // Teknik hata raporu bağları: seçenek alanları eşleme kaynağında YOK — olay değişti diye bağ bayat görünmesin. Satırlar
            // kilitliyken ve henüz yazılmadan (eski updated_at veritabanındayken) olayla EŞİT bağlar yeni zamana taşınır; aynı
            // işlem. YALNIZ değişecek kayıtların bağları (inceleme 2026-11-26: atlanan kaydın updated_at'i değişmez — bağı
            // taşınsaydı yersiz "bayat" görünürdü). Kilit sırası: olay satırları → bağ satırları. IncidentRecordChangedEvent
            // BİLEREK yayımlanmaz (eşlenecek bir şey değişmedi; 5 000 olay senkron kuyruğunu taşırırdı).
            if (techErrorLinks != null) {
                techErrorLinks.carryInSyncLinks(changed.stream().map(IncidentRecord::getId).toList(), ts);
            }
            for (int j = 0; j < changed.size(); j++) {
                IncidentRecord e = changed.get(j);
                String v = values.get(j);
                switch (attribute) {
                    case "channel" -> e.setChannel(v);
                    case "service" -> e.setService(v);
                    case "errorCode" -> e.setErrorCode(v);
                    case "functionCode" -> e.setFunctionCode(v);
                    default -> e.setChannelCode(v);
                }
                e.setUpdatedAt(ts);
                e.setUpdatedBy(actor);
            }
            saved.addAll(repo.saveAll(changed));
        }
        repo.flush();   // satırlar kilitli — sürüm çakışması olmaz; bir kayıt yazılamazsa işlem geri alınır, hiçbiri yazılmaz

        // Seçenek satırları: takımın kaynak değerli satırları silinir (hedefle aynı yazımdaki hariç), hedef yoksa eklenir.
        int removed = 0;
        List<String> globalKept = new ArrayList<>();
        for (IncidentOption o : optionRepo.findByTypeForTeam(t, teamId)) {
            if (o.getValue() == null) continue;
            String k = o.getValue().trim().toLowerCase(Locale.ROOT);
            if (!from.containsKey(k)) continue;
            if (o.getTeamId() == null) {
                if (!o.getValue().trim().equals(to)) globalKept.add(o.getValue().trim());
                continue;
            }
            if (o.getValue().trim().equals(to)) continue;
            optionRepo.delete(o);
            removed++;
        }
        optionRepo.flush();
        ensureOption(t, to, actor, teamId);
        List<Long> savedIds = saved.stream().map(IncidentRecord::getId).sorted().toList();
        return new RenameResult(t, teamId, to, merge, saved.size(), perValue,
                savedIds.size() > 200 ? savedIds.subList(0, 200) : savedIds, removed, globalKept);
    }

    /**
     * Bir alan değerinin yeniden yazımı: {@code hits} = eşleşen kaynak değerlerin (seçilen yazımla) listesi (boş = eşleşme
     * yok), {@code value} = yeni değer (CSV'de öğeler tekilleşir, ilk yerinde kalır), {@code targetUsed} = hedef zaten bu
     * değerde ayrı bir öğe olarak geçiyor (birleştirme sayılır).
     */
    record OptionRewrite(String value, List<String> hits, boolean targetUsed) { }

    static OptionRewrite rewriteOption(String cur, boolean csv, Map<String, String> from, String to, String toKey) {
        if (cur == null) return new OptionRewrite(null, List.of(), false);
        List<String> parts = csv ? splitCsv(cur) : List.of(cur.trim());
        List<String> hits = new ArrayList<>();
        boolean targetUsed = false;
        List<String> next = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String p : parts) {
            if (p.isEmpty()) continue;
            String k = p.toLowerCase(Locale.ROOT);
            if (k.equals(toKey) && !from.containsKey(k)) targetUsed = true;
            String v = p;
            if (from.containsKey(k)) {
                hits.add(from.get(k));
                v = to;
            }
            if (seen.add(v.toLowerCase(Locale.ROOT))) next.add(v);
        }
        String value = csv ? String.join(", ", next) : (next.isEmpty() ? null : next.get(0));
        return new OptionRewrite(value, hits, targetUsed);
    }

    private static void requireFits(String value, int max, Long id, boolean csv) {
        if (value == null || value.length() <= max) return;
        throw new FieldValidationException("to", Msg.t(
                "Kayıt #" + id + " için yeni değer alanın sınırını (" + max + " karakter) aşıyor. Daha kısa bir ad yazın;"
                        + " hiçbir kayıt değiştirilmedi.",
                "For record #" + id + " the new value exceeds the field limit (" + max + " characters). Enter a shorter"
                        + " name; no record was changed."));
    }

    private static String optionField(IncidentRecord e, String attribute) {
        return switch (attribute) {
            case "channel" -> e.getChannel();
            case "service" -> e.getService();
            case "errorCode" -> e.getErrorCode();
            case "functionCode" -> e.getFunctionCode();
            default -> e.getChannelCode();
        };
    }

    /** CSV değeri → kırpılmış öğeler (boşlar atılır). */
    static List<String> splitCsv(String csv) {
        if (csv == null) return List.of();
        List<String> out = new ArrayList<>();
        for (String part : csv.split(",")) {
            String v = part.trim();
            if (!v.isEmpty()) out.add(v);
        }
        return out;
    }

    /** Açılışta varsayılan kanalları GLOBAL (team_id=null) tohumlar — yalnız hiç CHANNEL seçeneği yoksa
     *  (kullanıcının sildiği varsayılanlar her restart'ta geri gelmesin). */
    @Transactional
    public void seedOptions() {
        if (!optionRepo.findByTypeOrderByValueAsc(OPT_CHANNEL).isEmpty()) return;
        for (String ch : CHANNEL_DEFAULTS) ensureOption(OPT_CHANNEL, ch, "system", null);
    }

    /** Yoksa ekler (case-insensitive, kapsam=global+takım). Yarışta sessizce yok say. */
    private void ensureOption(String type, String value, String actor, Long teamId) {
        if (value == null || value.isBlank()) return;
        String v = value.trim();
        if (!optionRepo.findScoped(type, v, teamId).isEmpty()) return;
        optionRepo.save(new IncidentOption(type, v, actor, now(), teamId));
    }

    private static String normType(String type) {
        // Locale.ROOT: Türkçe yerel ayarda "domain".toUpperCase() "DOMAİN" olurdu. Bilinmeyen tür girdiyi yansıtmaz (inceleme
        // 2026-11-26): alan adlı 400, iki dilli metin (FieldValidationException bir IllegalArgumentException — eski yakalayanlar
        // aynen çalışır).
        String t = type == null ? "" : type.trim().toUpperCase(Locale.ROOT);
        if (!OPTION_TYPES.contains(t)) {
            throw new FieldValidationException("type", Msg.t(
                    "Seçenek türü tanınmadı. Kanal, alan adı, hata kodu, fonksiyon kodu ya da kanal kodu seçin.",
                    "The option type was not recognised. Choose channel, domain, error code, function code or channel code."));
        }
        return t;
    }

    /** Markdown alanlarındaki /api/incidents/images/{id} referanslarını olaya bağlar —
     *  create modunda (id'siz) yüklenen taslak görseller kaydedince olaya iliştirilir.
     *
     *  <p><b>Yalnız KENDİ taslağını sahiplenir</b> (prod kapısı 2026-09-25, Y-1): eskiden referans verilen HER
     *  görsel bu olaya taşınıyordu — kullanıcı açıklamasına başka takımın olayındaki görselin id'sini yazarak
     *  görseli kendi olayına çekebiliyor, sahibi takım da 403 alıyordu. Artık yalnız henüz olaya bağlanmamış
     *  (taslak) ve AYNI kullanıcının yüklediği görseller bağlanır; başka olayın görseline dokunulmaz. */
    private void linkImages(IncidentRecord e, String actorName) {
        Set<Long> ids = imageRefs(e);
        if (ids.isEmpty()) return;
        for (IncidentImage img : imageRepo.findAllById(ids)) {
            boolean ownDraft = img.getIncidentId() == null && actorName != null
                    && actorName.equalsIgnoreCase(img.getCreatedBy());
            if (ownDraft) {
                img.setIncidentId(e.getId());
                imageRepo.save(img);
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Snake_case body → entity. create=true ise zorunlu alanlar doğrulanır.
     *
     * <p>Kurallar (2026-11-20, "Sağlam Kayıt"): anahtar gövdede YOKSA alan dokunulmaz, açık JSON null temizler; YALNIZ
     * gövdede olan ve kayıttakinden FARKLI değer doğrulanır (eski, bugünkü kurala uymayan değer düzenlemeyi kilitlemez);
     * hata alan adlı 400 ({@link FieldValidationException}); hiçbir alan doğrulama bitmeden yazılmaz. {@code team_name}
     * istemciden alınmaz (takım deposundan). Süre gönderilmezse iki zamandan hesaplanır.
     */
    private void applyBody(IncidentRecord e, Map<String, Object> body, boolean create) {
        if (body == null) body = Map.of();
        // Teknik hata raporu konusuna içe alınacak üç opsiyonel alan (2026-11-20): teknik hata adedi, kesinti kaynaklı mı,
        // kesinti ID. Bu üçü gönderildiğinde HER ZAMAN doğrulanır (değişmemiş olsa da — kayıtlı değerleri zaten geçerlidir).
        boolean hasTechErrorCount = body.containsKey(F_TECH_ERROR_COUNT);
        boolean hasOutageRelated  = body.containsKey(F_OUTAGE_RELATED);
        boolean hasOutageId       = body.containsKey(F_OUTAGE_ID);
        Long techErrorCount   = hasTechErrorCount ? strictTechErrorCount(body.get(F_TECH_ERROR_COUNT)) : null;
        Boolean outageRelated = hasOutageRelated ? strictOutageRelated(body.get(F_OUTAGE_RELATED)) : null;
        String outageId       = hasOutageId ? strictOutageId(body.get(F_OUTAGE_ID)) : null;

        // ── Öteki alanlar (2026-11-20, "Sağlam Kayıt"): YALNIZ gövdede olan ve kayıttakinden FARKLI değer doğrulanır —
        // değişmeyen (eski kayıttan kalma, bugünkü kurala uymayan) değer düzenlemeyi kilitlemez. Hepsi önce bir yazım
        // listesine doğrulanır, HİÇBİR alan doğrulama bitmeden yazılmaz (geçersiz değerde kayıt yarım değişmez).
        List<Runnable> w = new ArrayList<>();
        text(body, "title", e.getTitle(), create, true, MAX_TITLE, w, e::setTitle);
        String occ = stamp(body, "occurred_at", e.getOccurredAt(), create, true, w, e::setOccurredAt);
        String det = stamp(body, "detected_at", e.getDetectedAt(), create, false, w, e::setDetectedAt);
        String res = stamp(body, "resolved_at", e.getResolvedAt(), create, false, w, e::setResolvedAt);
        boolean occChanged = occ != KEEP, detChanged = det != KEEP, resChanged = res != KEEP;
        String occEff = occChanged ? occ : e.getOccurredAt();
        String detEff = detChanged ? det : e.getDetectedAt();
        String resEff = resChanged ? res : e.getResolvedAt();
        checkOrder(occEff, detEff, resEff, occChanged, detChanged, resChanged);
        choice(body, "severity", SEVERITIES, e.getSeverity(), create, w, e::setSeverity);
        choice(body, "status", STATUSES, e.getStatus(), create, w, e::setStatus);
        choice(body, "category", CATEGORIES, e.getCategory(), create, w, e::setCategory);
        text(body, "error_code", e.getErrorCode(), create, false, MAX_CODE, w, e::setErrorCode);
        text(body, "function_code", e.getFunctionCode(), create, false, MAX_CODE, w, e::setFunctionCode);
        text(body, "channel_code", e.getChannelCode(), create, false, MAX_CODE, w, e::setChannelCode);
        text(body, "service", e.getService(), create, false, MAX_LIST, w, e::setService);
        text(body, "channel", e.getChannel(), create, false, MAX_LIST, w, e::setChannel);
        text(body, "affected_services", e.getAffectedServices(), create, false, MAX_SHORT, w, e::setAffectedServices);
        text(body, "problem_types", e.getProblemTypes(), create, false, MAX_LIST, w, e::setProblemTypes);
        text(body, "affected_app", e.getAffectedApp(), create, false, MAX_SHORT, w, e::setAffectedApp);
        text(body, "affected_systems", e.getAffectedSystems(), create, false, MAX_LIST, w, e::setAffectedSystems);
        text(body, "runbook_url", e.getRunbookUrl(), create, false, MAX_LIST, w, e::setRunbookUrl);
        runbookUrl(body, e.getRunbookUrl(), create);
        text(body, "tags", e.getTags(), create, false, MAX_SHORT, w, e::setTags);
        text(body, "rca_summary", e.getRcaSummary(), create, false, MAX_MARKDOWN_CHARS, w, e::setRcaSummary);
        text(body, "description", e.getDescription(), create, false, MAX_MARKDOWN_CHARS, w, e::setDescription);
        text(body, "resolution_steps", e.getResolutionSteps(), create, false, MAX_MARKDOWN_CHARS, w, e::setResolutionSteps);
        text(body, "business_impact", e.getBusinessImpact(), create, false, MAX_MARKDOWN_CHARS, w, e::setBusinessImpact);
        whole(body, "affected_customers", e.getAffectedCustomers(), w, e::setAffectedCustomers);
        whole(body, "affected_transactions", e.getAffectedTransactions(), w, e::setAffectedTransactions);
        boolean durationSent = body.containsKey("duration_minutes");
        whole(body, "duration_minutes", e.getDurationMinutes(), w, e::setDurationMinutes);
        budget(body, e.getErrorBudgetBurnPct(), w, e::setErrorBudgetBurnPct);
        slaBreached(body, e.getSlaBreached(), w, e::setSlaBreached);
        team(body, e, create, w);
        // Kaynak alarm bağı — varlık + görünürlük doğrulaması controller'da (IncidentAlertLinkService.validate).
        if (body.containsKey("alert_event_id")) {
            Long alert = toLong(body.get("alert_event_id"));
            w.add(() -> e.setAlertEventId(alert));
        }
        // Süre (dk) kayıtta yoksa ve iki zaman da biliniyorsa sunucu hesaplar (aşağı yuvarlanmış dakika, ≥ 0). Gövdedeki
        // açık süre ASLA ezilmez. Çözülme zamanı bu istekte silinen (yeniden açılan) kaydın HESAPLANMIŞ süresi de silinir.
        Integer stored = e.getDurationMinutes();
        Integer oldComputed = minutesBetween(e.getOccurredAt(), e.getResolvedAt(), false);
        Integer oldRounded = minutesBetween(e.getOccurredAt(), e.getResolvedAt(), true);
        Integer computed = minutesBetween(occEff, resEff, false);
        boolean reopened = resChanged && res == null && e.getResolvedAt() != null;

        for (Runnable r : w) r.run();     // ← doğrulama bitti: yazım burada başlar

        if (!durationSent) {
            if (reopened) {
                if (stored != null && (stored.equals(oldComputed) || stored.equals(oldRounded))) e.setDurationMinutes(null);
            } else if (computed != null && (create || occChanged || resChanged || stored == null)) {
                e.setDurationMinutes(computed);
            }
        }
        if (create && e.getSlaBreached() == null)           e.setSlaBreached(false);
        // Üç yeni alan (yukarıda doğrulandı): anahtar gövdede YOKSA dokunulmaz, açık JSON null temizler.
        if (hasTechErrorCount)                              e.setTechErrorCount(techErrorCount);
        if (hasOutageRelated)                               e.setOutageRelated(outageRelated);
        if (hasOutageId)                                    e.setOutageId(outageId);
        // Kesinti ID yalnız cevap "Evet" iken tutulur: hayır / bilinmiyor → null (yalnız cevabı değiştiren istek de ID'yi siler).
        if (!Boolean.TRUE.equals(e.getOutageRelated()))     e.setOutageId(null);
    }

    // ── Alan doğrulaması (2026-11-20, "Sağlam Kayıt") ─────────────────────────────────────────────────────────────

    /** Kolon sınırları — entity'deki {@code @Column(length)} ile AYNI (IncidentServiceRobustTest entity'den okuyup karşılaştırır). */
    static final int MAX_TITLE = 255;
    static final int MAX_CODE = 100;
    static final int MAX_SHORT = 255;
    static final int MAX_LIST = 500;
    /** Markdown alanları (TEXT kolon) — postmortem bölümüyle aynı üst sınır. */
    static final int MAX_MARKDOWN_CHARS = 20_000;
    /** Hata bütçesi tüketimi (%) üst sınırı — anlamsız büyük değer reddedilir. */
    static final double MAX_ERROR_BUDGET = 1_000_000d;

    /** "Bu alan bu istekte değişmedi" işareti ({@link #stamp} dönüşü; == ile karşılaştırılır). */
    private static final String KEEP = new String("\u0000keep");

    /** Alan adı → {TR, EN} etiket (hata iletilerinde). */
    private static final Map<String, String[]> LABELS = Map.ofEntries(
            Map.entry("title", new String[]{"Başlık", "Title"}),
            Map.entry("occurred_at", new String[]{"Oluş zamanı", "Occurred at"}),
            Map.entry("detected_at", new String[]{"Tespit zamanı", "Detected at"}),
            Map.entry("resolved_at", new String[]{"Çözülme zamanı", "Resolved at"}),
            Map.entry("severity", new String[]{"Önem", "Severity"}),
            Map.entry("status", new String[]{"Durum", "Status"}),
            Map.entry("category", new String[]{"Kategori", "Category"}),
            Map.entry("error_code", new String[]{"Hata kodu", "Error code"}),
            Map.entry("function_code", new String[]{"Fonksiyon kodu", "Function code"}),
            Map.entry("channel_code", new String[]{"Kanal kodu", "Channel code"}),
            Map.entry("service", new String[]{"Servis / domain", "Service / domain"}),
            Map.entry("channel", new String[]{"Kanal", "Channel"}),
            Map.entry("affected_services", new String[]{"Etkilenen servisler", "Affected services"}),
            Map.entry("problem_types", new String[]{"Problem tipi", "Problem type"}),
            Map.entry("affected_app", new String[]{"Etkilenen uygulama", "Affected application"}),
            Map.entry("affected_systems", new String[]{"Etkilenen sistemler", "Affected systems"}),
            Map.entry("runbook_url", new String[]{"Runbook adresi", "Runbook URL"}),
            Map.entry("tags", new String[]{"Etiketler", "Tags"}),
            Map.entry("rca_summary", new String[]{"Kök neden özeti", "Root cause summary"}),
            Map.entry("description", new String[]{"Açıklama", "Description"}),
            Map.entry("resolution_steps", new String[]{"Çözüm adımları", "Resolution steps"}),
            Map.entry("business_impact", new String[]{"İş etkisi", "Business impact"}),
            Map.entry("affected_customers", new String[]{"Etkilenen müşteri adedi", "Affected customers"}),
            Map.entry("affected_transactions", new String[]{"Etkilenen işlem adedi", "Affected transactions"}),
            Map.entry("duration_minutes", new String[]{"Süre (dk)", "Duration (min)"}),
            Map.entry("error_budget_burn_pct", new String[]{"Error budget tüketimi (%)", "Error budget burn (%)"}),
            Map.entry("sla_breached", new String[]{"SLA ihlali", "SLA breached"}),
            Map.entry("team_id", new String[]{"Takım", "Team"}));

    private static String tr(String field) { String[] l = LABELS.get(field); return l == null ? field : l[0]; }
    private static String en(String field) { String[] l = LABELS.get(field); return l == null ? field : l[1]; }

    private static FieldValidationException required(String field) {
        return new FieldValidationException(field, Msg.t(
                tr(field) + " zorunludur ve boş bırakılamaz. Alanı doldurup yeniden kaydedin.",
                en(field) + " is required and cannot be left empty. Fill in the field and save again."));
    }

    /** Gövdedeki metin değer ham hâliyle kayıttakine eşit mi (kırpılmamış eski değer de "değişmedi" sayılır). */
    private static boolean sameText(Object raw, String v, String stored) {
        return java.util.Objects.equals(v, stored) || (raw instanceof String s && s.equals(stored));
    }

    /** Liste / nesne gibi metin olmayan değer → alan adlı 400 (eskiden {@code toString} ile "[a, b]" yazılırdı). */
    private static void requireScalar(String field, Object raw) {
        if (raw == null || raw instanceof String || raw instanceof Number || raw instanceof Boolean) return;
        throw new FieldValidationException(field, Msg.t(
                tr(field) + " metin olmalı; liste ya da nesne gönderilemez. Değeri tek bir metin olarak yazın.",
                en(field) + " must be text; a list or an object cannot be sent. Enter the value as a single piece of text."));
    }

    /** Metin alanı: kırpılır, boş → null; değişmişse uzunluk (ve zorunluluk) denetlenir. */
    private static void text(Map<String, Object> body, String field, String stored, boolean create, boolean required,
                             int max, List<Runnable> w, java.util.function.Consumer<String> set) {
        if (!body.containsKey(field)) {
            if (create && required) throw required(field);
            return;
        }
        Object raw = body.get(field);
        String v = str(body, field);
        if (!create && sameText(raw, v, stored)) return;
        requireScalar(field, raw);
        if (v == null) {
            if (required) throw required(field);
        } else if (v.length() > max) {
            throw new FieldValidationException(field, Msg.t(
                    tr(field) + " en çok " + max + " karakter olabilir (girilen: " + v.length() + "). Metni kısaltıp yeniden kaydedin.",
                    en(field) + " can be at most " + max + " characters (entered: " + v.length() + "). Shorten the text and save again."));
        }
        w.add(() -> set.accept(v));
    }

    /**
     * Runbook bağlantısı (2026-11-20, "Hızlı Giriş"): yalnız gövdede olan ve DEĞİŞEN değer denetlenir (eski, kurala uymayan
     * kayıtlı değer düzenlemeyi kilitlemez). Uzunluk {@link #text} ile; burada yalnız biçim: http(s) şeması + bir sunucu adı,
     * boşluk / denetim karakteri yok. Ayrıntı alanında hedefi yeni sekmede açılan bir bağlantı olarak gösterilir — başka bir
     * şema (javascript:, data:, file:) asla kabul edilmez.
     */
    private static void runbookUrl(Map<String, Object> body, String stored, boolean create) {
        if (!body.containsKey("runbook_url")) return;
        Object raw = body.get("runbook_url");
        String v = str(body, "runbook_url");
        if (!create && sameText(raw, v, stored)) return;
        if (v == null || v.length() > MAX_LIST) return;   // boş → temizlenir; uzunluk hatası text() içinde
        if (runbookUrlProblem(v)) {
            throw new FieldValidationException("runbook_url", Msg.t(
                    "Runbook adresi http:// ya da https:// ile başlayan geçerli bir web adresi olmalı (ör. "
                            + "https://wiki.example.com/runbook/odeme). Adresi düzeltip yeniden kaydedin.",
                    "The runbook URL must be a valid web address starting with http:// or https:// (e.g. "
                            + "https://wiki.example.com/runbook/payments). Correct the address and save again."));
        }
    }

    /** Runbook adresi kurala UYMUYOR mu: http(s) + sunucu adı + boşluk / denetim karakteri yok. Saf, test için açık. */
    static boolean runbookUrlProblem(String v) {
        if (v == null || v.isEmpty()) return false;
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            if (c <= 0x20 || c == 0x7f || Character.isWhitespace(c) || Character.isISOControl(c)) return true;
        }
        String lower = v.toLowerCase(Locale.ROOT);
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) return true;
        try {
            java.net.URI uri = new java.net.URI(v);
            // getHost() alt çizgili kurumsal adlarda (wiki_host) null döner → yetki kısmı (host[:port]) yeterli
            String authority = uri.getRawAuthority();
            return authority == null || authority.isBlank() || authority.startsWith("@") || authority.startsWith(":");
        } catch (java.net.URISyntaxException ex) {
            return true;
        }
    }

    /** Sabit seçenekli alan (önem / durum / kategori): büyük harfe çevrilir; değişmişse listede olmalı. */
    private static void choice(Map<String, Object> body, String field, Set<String> allowed, String stored, boolean create,
                               List<Runnable> w, java.util.function.Consumer<String> set) {
        if (!body.containsKey(field)) {
            if (create) throw required(field);
            return;
        }
        Object raw = body.get(field);
        requireScalar(field, raw);
        String v = str(body, field);
        if (v == null) throw required(field);
        String up = v.toUpperCase(Locale.ROOT);
        if (!create && up.equals(stored)) return;
        if (!allowed.contains(up)) {
            String shown = v.length() > 40 ? v.substring(0, 40) + "…" : v;
            String list = String.join(", ", new TreeSet<>(allowed));
            throw new FieldValidationException(field, Msg.t(
                    tr(field) + " değeri geçersiz (\"" + shown + "\"). Şunlardan birini seçin: " + list + ".",
                    en(field) + " has an invalid value (\"" + shown + "\"). Choose one of: " + list + "."));
        }
        w.add(() -> set.accept(up));
    }

    /**
     * Zaman damgası alanı. Dönüş: {@link #KEEP} = bu istekte değişmedi; null = temizlendi; aksi halde yazılacak normal
     * biçim ({@code yyyy-MM-ddTHH:mm:ss} UTC, {@link IncidentPostmortemService#norm}). Değişen değer okunabilmeli ve yılı
     * 1970–2100 arasında olmalı.
     */
    private static String stamp(Map<String, Object> body, String field, String stored, boolean create, boolean required,
                                List<Runnable> w, java.util.function.Consumer<String> set) {
        if (!body.containsKey(field)) {
            if (create && required) throw required(field);
            return KEEP;
        }
        Object raw = body.get(field);
        String v = str(body, field);
        if (!create && sameText(raw, v, stored)) return KEEP;
        requireScalar(field, raw);
        if (v == null) {
            if (required) throw required(field);
            w.add(() -> set.accept(null));
            return null;
        }
        String n = IncidentPostmortemService.norm(v);
        if (n == null || !yearInRange(n)) {
            String shown = v.length() > 40 ? v.substring(0, 40) + "…" : v;
            throw new FieldValidationException(field, Msg.t(
                    tr(field) + " okunamadı (\"" + shown + "\"). Tarihi takvimden seçin ya da yyyy-MM-ddTHH:mm:ss biçiminde "
                            + "(UTC, 1970–2100 arası) yazın.",
                    en(field) + " could not be read (\"" + shown + "\"). Pick the date from the calendar or write it as "
                            + "yyyy-MM-ddTHH:mm:ss (UTC, between 1970 and 2100)."));
        }
        w.add(() -> set.accept(n));
        return n;
    }

    private static boolean yearInRange(String normalized) {
        try {
            int y = Integer.parseInt(normalized.substring(0, 4));
            return y >= 1970 && y <= 2100;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static java.time.LocalDateTime parseStamp(String s) {
        String n = s == null ? null : IncidentPostmortemService.norm(s);
        if (n == null) return null;
        try {
            return java.time.LocalDateTime.parse(n);
        } catch (java.time.format.DateTimeParseException e) {
            return null;
        }
    }

    /**
     * Sıra: oluş ≤ tespit ≤ çözülme. Bir çift YALNIZ iki zamanından biri bu istekte değiştiyse denetlenir (eski, tutarsız
     * kayıt başlık düzenlemesinde kilitlenmez). Hata her zaman SONRAKİ alana yazılır (istemcideki kuralla aynı alan).
     */
    private static void checkOrder(String occ, String det, String res, boolean occChanged, boolean detChanged, boolean resChanged) {
        java.time.LocalDateTime o = parseStamp(occ), d = parseStamp(det), r = parseStamp(res);
        if ((occChanged || detChanged) && o != null && d != null && d.isBefore(o)) {
            throw new FieldValidationException("detected_at", Msg.t(
                    "Tespit zamanı oluş zamanından önce olamaz. Olay önce oluşur, sonra tespit edilir — iki zamanı kontrol edin.",
                    "Detected at cannot be earlier than occurred at. An incident occurs first and is detected after — check both times."));
        }
        if ((detChanged || resChanged) && d != null && r != null && r.isBefore(d)) {
            throw new FieldValidationException("resolved_at", Msg.t(
                    "Çözülme zamanı tespit zamanından önce olamaz. Çözülme, tespitten sonra olmalı — iki zamanı kontrol edin.",
                    "Resolved at cannot be earlier than detected at. Resolution must come after detection — check both times."));
        }
        if ((occChanged || resChanged) && o != null && r != null && r.isBefore(o)) {
            throw new FieldValidationException("resolved_at", Msg.t(
                    "Çözülme zamanı oluş zamanından önce olamaz. Olay oluştuktan sonra çözülür — iki zamanı kontrol edin.",
                    "Resolved at cannot be earlier than occurred at. An incident is resolved after it occurs — check both times."));
        }
    }

    /** Oluş ↔ çözülme farkı (dk): aşağı (floor) ya da en yakına (round, istemcinin otomatik süresi) yuvarlanmış; negatif / okunamaz → null. */
    static Integer minutesBetween(String occurred, String resolved, boolean round) {
        java.time.LocalDateTime o = parseStamp(occurred), r = parseStamp(resolved);
        if (o == null || r == null || r.isBefore(o)) return null;
        long sec = java.time.Duration.between(o, r).getSeconds();
        long min = round ? Math.round(sec / 60d) : sec / 60;
        return min > Integer.MAX_VALUE ? null : (int) min;
    }

    /**
     * Sayıyı karşılaştırılabilir hâle getirir: JSON sayısı ya da yalnız rakam / tek nokta içeren metin → BigDecimal;
     * okunamazsa null. Doğrusal, düzenli ifade yok; 40 karakterden uzun metin okunmaz.
     */
    private static java.math.BigDecimal decimalOf(Object v) {
        if (v instanceof Number num) return toDecimal(num);
        if (!(v instanceof String s)) return null;
        String t = s.strip();
        if (t.isEmpty() || t.length() > MAX_NUMBER_TEXT) return null;
        int from = t.charAt(0) == '-' ? 1 : 0;
        if (from == t.length()) return null;
        boolean dot = false, digit = false;
        for (int i = from; i < t.length(); i++) {
            char c = t.charAt(i);
            if (c == '.') {
                if (dot) return null;
                dot = true;
            } else if (c >= '0' && c <= '9') {
                digit = true;
            } else {
                return null;
            }
        }
        if (!digit || t.charAt(t.length() - 1) == '.' || t.charAt(from) == '.') return null;
        return new java.math.BigDecimal(t);
    }

    /** Gövdedeki sayı kayıttakiyle AYNI mı (ör. "15" = 15, 12.0 = 12) — aynıysa doğrulanmaz, dokunulmaz. */
    private static boolean sameNumber(Object raw, Number stored) {
        if (raw == null || (raw instanceof String s && s.isBlank())) return stored == null;
        if (stored == null) return false;
        java.math.BigDecimal a = decimalOf(raw);
        java.math.BigDecimal b = toDecimal(stored);                       // NaN / sonsuz kayıtlı değer → null
        return a != null && b != null && a.compareTo(b) == 0;
    }

    /** Negatif olmayan TAM sayı (JSON tam sayı, 12.0 ya da yalnız rakam metni) ≤ max; aksi halde null. */
    private static Long strictWhole(Object raw, long max) {
        if (raw instanceof String s && s.strip().indexOf('.') >= 0) return null;   // "12.0" metni tam sayı değildir
        java.math.BigDecimal d = decimalOf(raw);
        if (d == null || d.signum() < 0 || d.stripTrailingZeros().scale() > 0) return null;
        if (d.compareTo(java.math.BigDecimal.valueOf(max)) > 0) return null;
        return d.longValueExact();
    }

    /** Tam sayı alanı (müşteri / işlem adedi, süre): boş → null; değişmişse 0 … 2.147.483.647 arası tam sayı olmalı. */
    private static void whole(Map<String, Object> body, String field, Integer stored, List<Runnable> w,
                              java.util.function.Consumer<Integer> set) {
        if (!body.containsKey(field)) return;
        Object raw = body.get(field);
        if (sameNumber(raw, stored)) return;
        if (raw == null || (raw instanceof String s && s.isBlank())) {
            w.add(() -> set.accept(null));
            return;
        }
        Long n = strictWhole(raw, Integer.MAX_VALUE);
        if (n == null) {
            java.math.BigDecimal d = decimalOf(raw);
            boolean negative = d != null && d.signum() < 0;
            throw new FieldValidationException(field, negative
                    ? Msg.t(tr(field) + " negatif olamaz. 0 ya da daha büyük bir tam sayı yazın; bilinmiyorsa alanı boş bırakın.",
                            en(field) + " cannot be negative. Enter a whole number of 0 or more, or leave the field empty if it is unknown.")
                    : Msg.t(tr(field) + " 0 ile 2.147.483.647 arasında bir tam sayı olmalı (ör. 120): ondalık, binlik ayraçlı ya da "
                                    + "metin değer kabul edilmez. Yalnız rakam yazın; bilinmiyorsa alanı boş bırakın.",
                            en(field) + " must be a whole number between 0 and 2,147,483,647 (e.g. 120): decimals, thousands "
                                    + "separators and text are not accepted. Use digits only, or leave the field empty if it is unknown."));
        }
        int value = n.intValue();
        w.add(() -> set.accept(value));
    }

    /** Hata bütçesi tüketimi (%): boş → null; değişmişse 0 … 1.000.000 arası sonlu, düz ondalık sayı (üstel yazım yok). */
    private static void budget(Map<String, Object> body, Double stored, List<Runnable> w,
                               java.util.function.Consumer<Double> set) {
        String field = "error_budget_burn_pct";
        if (!body.containsKey(field)) return;
        Object raw = body.get(field);
        if (sameNumber(raw, stored)) return;
        if (raw == null || (raw instanceof String s && s.isBlank())) {
            w.add(() -> set.accept(null));
            return;
        }
        java.math.BigDecimal d = decimalOf(raw);
        if (d == null || d.signum() < 0 || d.compareTo(java.math.BigDecimal.valueOf(MAX_ERROR_BUDGET)) > 0) {
            throw new FieldValidationException(field, Msg.t(
                    tr(field) + " 0 ile 1.000.000 arasında bir sayı olmalı (ör. 12.5). Ondalık ayırıcı olarak nokta kullanın; "
                            + "bilinmiyorsa alanı boş bırakın.",
                    en(field) + " must be a number between 0 and 1,000,000 (e.g. 12.5). Use a dot as the decimal separator, "
                            + "or leave the field empty if it is unknown."));
        }
        double value = d.doubleValue();
        w.add(() -> set.accept(value));
    }

    /** SLA ihlali: JSON true / false, "true" / "false" metni; null / boş → false (sütun NOT NULL). Başka değer → 400. */
    private static void slaBreached(Map<String, Object> body, Boolean stored, List<Runnable> w,
                                    java.util.function.Consumer<Boolean> set) {
        String field = "sla_breached";
        if (!body.containsKey(field)) return;
        Object raw = body.get(field);
        Boolean v;
        if (raw == null) v = false;
        else if (raw instanceof Boolean b) v = b;
        else if (raw instanceof String s && s.isBlank()) v = false;
        else if (raw instanceof String s && "true".equalsIgnoreCase(s.strip())) v = true;
        else if (raw instanceof String s && "false".equalsIgnoreCase(s.strip())) v = false;
        else throw new FieldValidationException(field, Msg.t(
                    "SLA ihlali yalnız evet (true) ya da hayır (false) olabilir. Kutuyu işaretleyin ya da işareti kaldırın.",
                    "SLA breached can only be yes (true) or no (false). Tick the box or clear it."));
        if (v.equals(stored)) return;
        Boolean value = v;
        w.add(() -> set.accept(value));
    }

    /**
     * Takım: {@code team_id} katı (pozitif tam sayı ya da boş); ad VERİTABANINDAN okunur. Gövdedeki {@code team_name}
     * yalnız takım deposu yoksa (elle kurulan birim testleri — eski davranış) kullanılır. Takım değişmediyse ad yalnız takım
     * satırı bulunursa tazelenir (takım yeniden adlandırıldıysa); bulunamazsa kayıttaki ad kalır.
     */
    private void team(Map<String, Object> body, IncidentRecord e, boolean create, List<Runnable> w) {
        if (teamRepo == null) {                                       // eski davranış (yalnız depo yokken)
            if (body.containsKey("team_id")) {
                Long id = toLong(body.get("team_id"));
                w.add(() -> e.setTeamId(id));
            }
            if (body.containsKey("team_name")) {
                String name = str(body, "team_name");
                w.add(() -> e.setTeamName(name));
            }
            return;
        }
        if (!body.containsKey("team_id")) return;                     // team_name tek başına yok sayılır
        Object raw = body.get("team_id");
        Long id;
        if (raw == null || (raw instanceof String s && s.isBlank())) id = null;
        else {
            Long n = strictWhole(raw, Long.MAX_VALUE);
            if (n == null || n <= 0) {
                throw new FieldValidationException("team_id", Msg.t(
                        "Takım kimliği okunamadı. Listeden bir takım seçip yeniden kaydedin.",
                        "The team id could not be read. Choose a team from the list and save again."));
            }
            id = n;
        }
        boolean changed = create || !java.util.Objects.equals(id, e.getTeamId());
        Optional<String> dbName = teamNameOf(id);
        if (changed) {
            String name = dbName.orElse(null);
            w.add(() -> { e.setTeamId(id); e.setTeamName(name); });
        } else if (dbName.isPresent()) {
            String name = dbName.get();
            w.add(() -> e.setTeamName(name));
        }
    }

    // ── Teknik hata adedi · kesinti kaynaklı mı · kesinti ID (2026-11-20) — katı doğrulama ─────────────────────────

    static final String F_TECH_ERROR_COUNT = "tech_error_count";
    static final String F_OUTAGE_RELATED   = "outage_related";
    static final String F_OUTAGE_ID        = "outage_id";
    /** Teknik hata adedi üst sınırı (10^15) — teknik hata raporundaki sayı sınırıyla aynı. */
    static final long MAX_TECH_ERROR_COUNT = 1_000_000_000_000_000L;
    /** Kesinti ID en çok bu kadar karakter (kolon VARCHAR(60); rapor konusundaki kesinti ID sınırıyla aynı). */
    static final int MAX_OUTAGE_ID = 60;
    /** Rakam metni bundan uzunsa sayıya ÇEVRİLMEDEN aralık dışı sayılır (sınırsız girdi üzerinde iş yapılmaz). */
    private static final int MAX_NUMBER_TEXT = 40;
    private static final java.math.BigDecimal MAX_TECH_ERROR_COUNT_BD = java.math.BigDecimal.valueOf(MAX_TECH_ERROR_COUNT);

    /**
     * Teknik hata adedi: null / boş metin → null (bilinmiyor); 0 … 10^15 arası TAM sayı. JSON sayısı (12, 12.0) ya da yalnız
     * rakamlardan oluşan metin ("1250") kabul edilir; ondalık (12.7), binlik ayraçlı ("1.250"), üstel yazılmış metin, sayı
     * olmayan değer (metin, boolean, liste), negatif ve 10^15 üstü → alan adlı 400. Mevcut sayısal alanların gevşekliği
     * ("abc" → null, 12.7 → 12) burada YOK.
     */
    static Long strictTechErrorCount(Object v) {
        if (v == null) return null;
        java.math.BigDecimal n = null;
        if (v instanceof Number num) {
            n = toDecimal(num);
        } else if (v instanceof String s) {
            String t = s.strip();
            if (t.isEmpty()) return null;
            if (digitsOnly(t)) {
                // 40 haneden uzun rakam dizisi ayrıştırılmaz: aralık dışı olduğu kesin (işaretine göre "negatif" / "çok büyük")
                n = t.length() <= MAX_NUMBER_TEXT ? new java.math.BigDecimal(t)
                        : t.charAt(0) == '-' ? java.math.BigDecimal.ONE.negate() : MAX_TECH_ERROR_COUNT_BD.add(java.math.BigDecimal.ONE);
            }
        }
        if (n == null || n.stripTrailingZeros().scale() > 0) {
            throw new FieldValidationException(F_TECH_ERROR_COUNT, Msg.t(
                    "Teknik hata adedi tam sayı olmalı (ör. 1250): ondalık, binlik ayraçlı ya da metin değer kabul edilmez. Yalnız rakam yazın; bilinmiyorsa alanı boş bırakın.",
                    "Technical error count must be a whole number (e.g. 1250): decimals, thousands separators and text are not accepted. Use digits only, or leave the field empty if the count is unknown."));
        }
        if (n.signum() < 0) {
            throw new FieldValidationException(F_TECH_ERROR_COUNT, Msg.t(
                    "Teknik hata adedi negatif olamaz. 0 ya da daha büyük bir tam sayı yazın; bilinmiyorsa alanı boş bırakın.",
                    "Technical error count cannot be negative. Enter a whole number of 0 or more, or leave the field empty if the count is unknown."));
        }
        if (n.compareTo(MAX_TECH_ERROR_COUNT_BD) > 0) {
            throw new FieldValidationException(F_TECH_ERROR_COUNT, Msg.t(
                    "Teknik hata adedi çok büyük: en çok 1.000.000.000.000.000 (10^15) olabilir. Değeri kontrol edip yeniden yazın.",
                    "Technical error count is too large: the maximum is 1,000,000,000,000,000 (10^15). Check the value and enter it again."));
        }
        return n.longValueExact();
    }

    /** JSON sayısı → BigDecimal; NaN / sonsuz → null (tam sayı değil). JSON sayı uzunluğunu ayrıştırıcı zaten sınırlar. */
    private static java.math.BigDecimal toDecimal(Number num) {
        if (num instanceof Integer || num instanceof Long || num instanceof Short || num instanceof Byte) {
            return java.math.BigDecimal.valueOf(num.longValue());
        }
        if (num instanceof java.math.BigDecimal bd) return bd;
        if (num instanceof java.math.BigInteger bi) return new java.math.BigDecimal(bi);
        double d = num.doubleValue();
        if (Double.isNaN(d) || Double.isInfinite(d)) return null;
        return java.math.BigDecimal.valueOf(d);
    }

    /** Yalnız ASCII rakam (başta tek '-' olabilir → negatif olarak okunur) — doğrusal, regex'siz; 1000 karakterden uzun metin sayı sayılmaz. */
    private static boolean digitsOnly(String t) {
        if (t.length() > 1000) return false;
        int from = t.charAt(0) == '-' ? 1 : 0;
        if (from == t.length()) return false;
        for (int i = from; i < t.length(); i++) {
            char c = t.charAt(i);
            if (c < '0' || c > '9') return false;
        }
        return true;
    }

    /** Kesinti kaynaklı mı: yalnız JSON true / false / null. "true" metni, 1 / 0 ve başka her şey → alan adlı 400. */
    static Boolean strictOutageRelated(Object v) {
        if (v == null) return null;
        if (v instanceof Boolean b) return b;
        throw new FieldValidationException(F_OUTAGE_RELATED, Msg.t(
                "\"Kesinti kaynaklı mı?\" cevabı yalnız evet (true), hayır (false) ya da boş (bilinmiyor) olabilir. Evet ya da Hayır seçin; bilinmiyorsa seçimi kaldırın.",
                "The \"Caused by an outage?\" answer can only be yes (true), no (false) or empty (unknown). Choose Yes or No, or clear the choice if it is unknown."));
    }

    /**
     * Kesinti ID: null / boş metin → null; metin baştan ve sondan kırpılır, tek satır ve en çok 60 karakter olmalı. Metin
     * olmayan değer (sayı, liste, nesne), İÇİNDE satır sonu ya da fazla uzunluk → alan adlı 400. Değerin TUTULUP
     * tutulmayacağına (cevap "Evet" mi) {@link #applyBody} karar verir; doğrulama cevaptan bağımsızdır — gönderilen değer her
     * zaman denetlenir.
     */
    static String strictOutageId(Object v) {
        if (v == null) return null;
        if (!(v instanceof String s)) {
            throw new FieldValidationException(F_OUTAGE_ID, Msg.t(
                    "Kesinti ID metin olmalı (ör. \"INC-2026-0915\"). Kesinti kaydının kimliğini tırnak içinde metin olarak gönderin.",
                    "Outage ID must be text (e.g. \"INC-2026-0915\"). Send the identifier of the outage record as a quoted string."));
        }
        String t = s.strip();
        if (t.isEmpty()) return null;
        for (int i = 0; i < t.length(); i++) {
            if (isLineBreak(t.charAt(i))) {
                throw new FieldValidationException(F_OUTAGE_ID, Msg.t(
                        "Kesinti ID tek satır olmalı; satır sonu içeremez. Kimliği tek satırda yazın (ör. INC-2026-0915).",
                        "Outage ID must be a single line; it cannot contain a line break. Enter the identifier on one line (e.g. INC-2026-0915)."));
            }
        }
        if (t.length() > MAX_OUTAGE_ID) {
            throw new FieldValidationException(F_OUTAGE_ID, Msg.t(
                    "Kesinti ID en çok " + MAX_OUTAGE_ID + " karakter olabilir (girilen: " + t.length() + "). Yalnız kesinti kaydının kimliğini yazın.",
                    "Outage ID can be at most " + MAX_OUTAGE_ID + " characters (entered: " + t.length() + "). Enter only the identifier of the outage record."));
        }
        return t;
    }

    /** Satır sonu sayılan karakterler: LF, VT, FF, CR, NEL (0x85), satır ayırıcı (0x2028), paragraf ayırıcı (0x2029). */
    private static boolean isLineBreak(char c) {
        return (c >= 0x0A && c <= 0x0D) || c == 0x85 || c == 0x2028 || c == 0x2029;
    }

    private static String blankToNull(String s) { return (s == null || s.isBlank()) ? null : s; }

    /** Önem → günlük trend kova indeksi (CRITICAL=0, HIGH=1, MEDIUM=2, LOW=3; bilinmeyen=-1). */
    private static int severityIndex(String sev) {
        if (sev == null) return -1;
        switch (sev) {
            case "CRITICAL": return 0;
            case "HIGH":     return 1;
            case "MEDIUM":   return 2;
            case "LOW":      return 3;
            default:         return -1;
        }
    }

    private static Map<String, Long> toCountMap(List<Object[]> rows) {
        Map<String, Long> m = new LinkedHashMap<>();
        for (Object[] r : rows) m.put((String) r[0], ((Number) r[1]).longValue());
        return m;
    }

    private static String str(Map<String, Object> body, String key) {
        Object v = body.get(key);
        if (v == null) return null;
        String s = v.toString().trim();
        return s.isEmpty() ? null : s;
    }

    private static Long toLong(Object v) {
        if (v == null) return null;
        if (v instanceof Number n) return n.longValue();
        String s = v.toString().trim();
        if (s.isEmpty()) return null;
        try { return Long.parseLong(s); } catch (Exception e) { return null; }
    }
}
