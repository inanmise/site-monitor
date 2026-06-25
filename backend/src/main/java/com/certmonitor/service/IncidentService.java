package com.certmonitor.service;

import com.certmonitor.model.IncidentImage;
import com.certmonitor.model.IncidentOption;
import com.certmonitor.model.IncidentRecord;
import com.certmonitor.repository.IncidentImageRepository;
import com.certmonitor.repository.IncidentOptionRepository;
import com.certmonitor.repository.IncidentRecordRepository;
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
    private static final Set<String> ALLOWED_IMAGE_TYPES =
            Set.of("image/png", "image/jpeg", "image/gif", "image/webp", "image/bmp");
    private static final Pattern IMG_REF = Pattern.compile("/api/incidents/images/(\\d+)");

    @Value("${cert.monitor.incident.image-max-bytes:5242880}")
    private long imageMaxBytes;

    private String now() { return ISO.format(Instant.now()); }

    // ── Read ────────────────────────────────────────────────────────────────

    /** scope = null → global (admin/AUDIT) tüm olaylar; dolu liste → yalnız o takım(lar)ın olayları
     *  (teamId VEYA createdByTeamId kapsamda). Boş liste → kapsamsız kullanıcı, hiçbir şey görmez. */
    public Page<IncidentRecord> list(String q, String severity, String category, String status,
                                     String service, String channel, String since, String until,
                                     Long teamId, Boolean slaBreached, Boolean open,
                                     List<Long> scope, Pageable pageable) {
        boolean scoped = scope != null;
        if (scoped && scope.isEmpty()) return Page.empty(pageable);
        List<Long> scopeList = scoped ? scope : List.of(-1L);   // global'de dummy (scoped=false kısa-devre)
        String like = (q != null && !q.isBlank()) ? "%" + q.trim().toLowerCase() + "%" : null;
        String svc  = (service != null && !service.isBlank()) ? "%" + service.trim().toLowerCase() + "%" : null;
        // channel artık CSV saklanabildiğinden TAM eşleşme yerine CSV-içinde-geçen (LIKE) eşleşme.
        String chn  = (channel != null && !channel.isBlank()) ? "%" + channel.trim().toLowerCase() + "%" : null;
        return repo.findFiltered(like, blankToNull(severity), blankToNull(category), blankToNull(status),
                svc, chn, blankToNull(since), blankToNull(until), teamId, slaBreached, open,
                scoped, scopeList, pageable);
    }

    public IncidentRecord get(Long id) {
        return repo.findById(id).orElseThrow(() -> new NoSuchElementException("Incident not found: " + id));
    }

    /** Trend + özet: günlük seri, severity/category kırılımı, özet sayılar. */
    public Map<String, Object> trends(String since, String until, List<Long> scope) {
        boolean scoped = scope != null;                                          // null = global (admin/AUDIT)
        List<Long> scopeList = (scope != null && !scope.isEmpty()) ? scope : List.of(-1L); // boş kapsam → hiçbir şey eşleşmez
        String s = blankToNull(since), u = blankToNull(until);
        List<Map<String, Object>> daily = new ArrayList<>();
        for (Object[] row : repo.countByDay(s, u, scoped, scopeList)) {
            daily.add(Map.of("day", row[0], "count", ((Number) row[1]).longValue()));
        }
        Map<String, Long> bySeverity = toCountMap(repo.countBySeverity(s, u, scoped, scopeList));
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

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("daily", daily);
        out.put("by_severity", bySeverity);
        out.put("by_category", byCategory);
        out.put("by_channel", byChannel);
        out.put("summary", Map.of("total", total, "critical", critical, "sla_breached", sla,
                "open", open, "resolved", resolved));
        return out;
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
        linkImages(saved);
        return saved;
    }

    @Transactional
    public IncidentRecord update(Long id, Map<String, Object> body, String actorName) {
        IncidentRecord e = get(id);
        applyBody(e, body, false);
        e.setUpdatedAt(now());
        e.setUpdatedBy(actorName);
        IncidentRecord saved = repo.save(e);
        linkImages(saved);
        return saved;
    }

    @Transactional
    public IncidentRecord delete(Long id) {
        IncidentRecord e = get(id);
        repo.delete(e);
        return e;
    }

    /** Toplu takım transferi — seçili olayların takımını (teamId+teamName) değiştirir. */
    @Transactional
    public int transfer(List<Long> ids, Long teamId, String teamName, String actor) {
        if (ids == null || ids.isEmpty() || teamId == null) return 0;
        List<IncidentRecord> recs = repo.findAllById(ids);
        String ts = now();
        for (IncidentRecord e : recs) {
            e.setTeamId(teamId);
            e.setTeamName(teamName);
            e.setUpdatedAt(ts);
            e.setUpdatedBy(actor);
        }
        repo.saveAll(recs);
        return recs.size();
    }

    // ── Görseller (markdown alanlarına gömülür) ───────────────────────────────

    @Transactional
    public IncidentImage storeImage(Long incidentId, String caption, MultipartFile file, String actorName) {
        if (incidentId != null) get(incidentId); // verilmişse olay var mı doğrula (yoksa 404)
        // incidentId null = taslak yükleme (yeni olay henüz kaydedilmedi); kaydedince
        // create/update markdown'daki görsel id'lerini linkImages ile olaya bağlar.
        if (file == null || file.isEmpty()) throw new IllegalArgumentException("Dosya boş");
        if (file.getSize() > imageMaxBytes)
            throw new IllegalArgumentException("Görsel çok büyük (limit " + (imageMaxBytes / 1024 / 1024) + "MB)");
        String ct = file.getContentType() != null ? file.getContentType().toLowerCase() : "";
        if (!ALLOWED_IMAGE_TYPES.contains(ct))
            throw new IllegalArgumentException("Desteklenmeyen görsel tipi: " + ct);
        try {
            IncidentImage img = new IncidentImage();
            img.setIncidentId(incidentId);
            img.setCaption(caption != null ? caption.trim() : null);
            img.setContentType(ct);
            img.setSizeBytes(file.getSize());
            img.setData(file.getBytes());
            img.setCreatedBy(actorName);
            img.setCreatedAt(now());
            return imageRepo.save(img);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Görsel okunamadı", e);
        }
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
        String t = type == null ? "" : type.trim().toUpperCase();
        if (!OPTION_TYPES.contains(t)) throw new IllegalArgumentException("Geçersiz seçenek tipi: " + type);
        return t;
    }

    /** Markdown alanlarındaki /api/incidents/images/{id} referanslarını olaya bağlar —
     *  create modunda (id'siz) yüklenen taslak görseller kaydedince olaya iliştirilir. */
    private void linkImages(IncidentRecord e) {
        Set<Long> ids = new HashSet<>();
        for (String body : List.of(
                Optional.ofNullable(e.getRcaSummary()).orElse(""),
                Optional.ofNullable(e.getDescription()).orElse(""),
                Optional.ofNullable(e.getResolutionSteps()).orElse(""),
                Optional.ofNullable(e.getBusinessImpact()).orElse(""))) {
            Matcher m = IMG_REF.matcher(body);
            while (m.find()) ids.add(Long.valueOf(m.group(1)));
        }
        if (ids.isEmpty()) return;
        for (IncidentImage img : imageRepo.findAllById(ids)) {
            if (!Objects.equals(img.getIncidentId(), e.getId())) {
                img.setIncidentId(e.getId());
                imageRepo.save(img);
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Snake_case body → entity. create=true ise zorunlu alanlar doğrulanır. */
    private void applyBody(IncidentRecord e, Map<String, Object> body, boolean create) {
        if (body.containsKey("title") || create)            e.setTitle(reqStr(body, "title", e.getTitle(), create));
        if (body.containsKey("occurred_at") || create)      e.setOccurredAt(reqStr(body, "occurred_at", e.getOccurredAt(), create));
        if (body.containsKey("severity") || create)         e.setSeverity(reqEnum(body, "severity", SEVERITIES, e.getSeverity(), create));
        if (body.containsKey("status") || create)           e.setStatus(reqEnum(body, "status", STATUSES, e.getStatus(), create));
        if (body.containsKey("category") || create)         e.setCategory(reqEnum(body, "category", CATEGORIES, e.getCategory(), create));
        if (body.containsKey("error_code"))                 e.setErrorCode(str(body, "error_code"));
        if (body.containsKey("function_code"))              e.setFunctionCode(str(body, "function_code"));
        if (body.containsKey("channel_code"))               e.setChannelCode(str(body, "channel_code"));
        if (body.containsKey("service"))                    e.setService(str(body, "service"));
        if (body.containsKey("channel"))                    e.setChannel(str(body, "channel"));
        if (body.containsKey("team_id"))                    e.setTeamId(toLong(body.get("team_id")));
        if (body.containsKey("team_name"))                  e.setTeamName(str(body, "team_name"));
        if (body.containsKey("detected_at"))                e.setDetectedAt(str(body, "detected_at"));
        if (body.containsKey("resolved_at"))                e.setResolvedAt(str(body, "resolved_at"));
        if (body.containsKey("rca_summary"))                e.setRcaSummary(str(body, "rca_summary"));
        if (body.containsKey("description"))                e.setDescription(str(body, "description"));
        if (body.containsKey("resolution_steps"))           e.setResolutionSteps(str(body, "resolution_steps"));
        if (body.containsKey("business_impact"))            e.setBusinessImpact(str(body, "business_impact"));
        if (body.containsKey("affected_services"))          e.setAffectedServices(str(body, "affected_services"));
        if (body.containsKey("problem_types"))              e.setProblemTypes(str(body, "problem_types"));
        if (body.containsKey("affected_app"))               e.setAffectedApp(str(body, "affected_app"));
        if (body.containsKey("affected_systems"))           e.setAffectedSystems(str(body, "affected_systems"));
        if (body.containsKey("affected_customers"))         e.setAffectedCustomers(toInt(body.get("affected_customers")));
        if (body.containsKey("affected_transactions"))      e.setAffectedTransactions(toInt(body.get("affected_transactions")));
        if (body.containsKey("runbook_url"))                e.setRunbookUrl(str(body, "runbook_url"));
        if (body.containsKey("tags"))                       e.setTags(str(body, "tags"));
        if (body.containsKey("sla_breached"))               e.setSlaBreached(Boolean.TRUE.equals(toBool(body.get("sla_breached"))));
        if (body.containsKey("error_budget_burn_pct"))      e.setErrorBudgetBurnPct(toDouble(body.get("error_budget_burn_pct")));
        if (body.containsKey("duration_minutes"))           e.setDurationMinutes(toInt(body.get("duration_minutes")));
        if (create && e.getSlaBreached() == null)           e.setSlaBreached(false);
    }

    private static String blankToNull(String s) { return (s == null || s.isBlank()) ? null : s; }

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

    private static String reqStr(Map<String, Object> body, String key, String current, boolean create) {
        String v = str(body, key);
        if (v == null) {
            if (create) throw new IllegalArgumentException(key + " zorunludur");
            return current;
        }
        return v;
    }

    private static String reqEnum(Map<String, Object> body, String key, Set<String> allowed, String current, boolean create) {
        String v = str(body, key);
        if (v == null) {
            if (create) throw new IllegalArgumentException(key + " zorunludur");
            return current;
        }
        String up = v.toUpperCase();
        if (!allowed.contains(up)) throw new IllegalArgumentException("Geçersiz " + key + ": " + v);
        return up;
    }

    private static Boolean toBool(Object v) {
        if (v == null) return null;
        if (v instanceof Boolean b) return b;
        return "true".equalsIgnoreCase(v.toString());
    }
    private static Double toDouble(Object v) {
        if (v == null) return null;
        if (v instanceof Number n) return n.doubleValue();
        try { return Double.parseDouble(v.toString()); } catch (Exception e) { return null; }
    }
    private static Integer toInt(Object v) {
        if (v == null) return null;
        if (v instanceof Number n) return n.intValue();
        try { return Integer.parseInt(v.toString()); } catch (Exception e) { return null; }
    }
    private static Long toLong(Object v) {
        if (v == null) return null;
        if (v instanceof Number n) return n.longValue();
        String s = v.toString().trim();
        if (s.isEmpty()) return null;
        try { return Long.parseLong(s); } catch (Exception e) { return null; }
    }
}
