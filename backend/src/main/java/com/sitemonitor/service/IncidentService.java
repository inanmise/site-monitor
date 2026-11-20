package com.sitemonitor.service;

import com.sitemonitor.config.GlobalExceptionHandler.FieldValidationException;
import com.sitemonitor.model.IncidentImage;
import com.sitemonitor.model.IncidentOption;
import com.sitemonitor.model.IncidentRecord;
import com.sitemonitor.repository.IncidentImageRepository;
import com.sitemonitor.repository.IncidentOptionRepository;
import com.sitemonitor.repository.IncidentRecordRepository;
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
    private static final Set<String> ALLOWED_IMAGE_TYPES =
            Set.of("image/png", "image/jpeg", "image/gif", "image/webp", "image/bmp");
    private static final Pattern IMG_REF = Pattern.compile("/api/incidents/images/(\\d+)");

    @Value("${site.monitor.incident.image-max-bytes:5242880}")
    private long imageMaxBytes;

    /**
     * Olay güncellenince yayımlanan olay ({@link IncidentRecordChangedEvent}, 2026-11-20): teknik hata raporu konularının canlı
     * bağı işlem İŞLENDİKTEN sonra senkronlar. Yoksa (elle kurulan birim testleri) yayımlanmaz.
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private org.springframework.context.ApplicationEventPublisher events;

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

        // Günlük trend — olay zamanını UTC'den Europe/Istanbul'a çevirip YEREL güne göre grupla + ÖNEM kırılımı
        // (tabloda saatler IST gösterildiğinden; ham UTC günü gece-yarısı kayıtlarını bir önceki güne kaydırırdı).
        java.time.ZoneId ist = java.time.ZoneId.of("Europe/Istanbul");
        TreeMap<String, long[]> dayBuckets = new TreeMap<>();   // gün → [critical, high, medium, low, total]
        for (Object[] row : repo.occurredAtSeverityInRange(s, u, scoped, scopeList)) {
            String oa = (String) row[0];
            if (oa == null || oa.length() < 10) continue;
            String day;
            try {
                java.time.LocalDateTime utc = java.time.LocalDateTime.parse(oa.substring(0, Math.min(19, oa.length())));
                day = utc.atZone(java.time.ZoneOffset.UTC).withZoneSameInstant(ist).toLocalDate().toString();
            } catch (Exception ex) {
                day = oa.substring(0, 10);   // ayrıştırılamazsa ham UTC günü
            }
            long[] b = dayBuckets.computeIfAbsent(day, k -> new long[5]);
            int idx = severityIndex((String) row[1]);
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
        // Bugün = Europe/Istanbul yerel günün başlangıcından; 7/30 gün = kayan UTC pencere.
        long today   = repo.countRange(ISO.format(java.time.LocalDate.now(ist).atStartOfDay(ist).toInstant()), null, scoped, scopeList);
        long last7d  = repo.countRange(ISO.format(java.time.Instant.now().minus(7,  java.time.temporal.ChronoUnit.DAYS)), null, scoped, scopeList);
        long last30d = repo.countRange(ISO.format(java.time.Instant.now().minus(30, java.time.temporal.ChronoUnit.DAYS)), null, scoped, scopeList);

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

    @Transactional
    public IncidentRecord update(Long id, Map<String, Object> body, String actorName) {
        IncidentRecord e = get(id);
        applyBody(e, body, false);
        e.setUpdatedAt(now());
        e.setUpdatedBy(actorName);
        IncidentRecord saved = repo.save(e);
        linkImages(saved, actorName);
        publishChanged(saved.getId());
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
        for (IncidentRecord r : recs) publishChanged(r.getId());
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
     *  create modunda (id'siz) yüklenen taslak görseller kaydedince olaya iliştirilir.
     *
     *  <p><b>Yalnız KENDİ taslağını sahiplenir</b> (prod kapısı 2026-09-25, Y-1): eskiden referans verilen HER
     *  görsel bu olaya taşınıyordu — kullanıcı açıklamasına başka takımın olayındaki görselin id'sini yazarak
     *  görseli kendi olayına çekebiliyor, sahibi takım da 403 alıyordu. Artık yalnız henüz olaya bağlanmamış
     *  (taslak) ve AYNI kullanıcının yüklediği görseller bağlanır; başka olayın görseline dokunulmaz. */
    private void linkImages(IncidentRecord e, String actorName) {
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
            boolean ownDraft = img.getIncidentId() == null && actorName != null
                    && actorName.equalsIgnoreCase(img.getCreatedBy());
            if (ownDraft) {
                img.setIncidentId(e.getId());
                imageRepo.save(img);
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Snake_case body → entity. create=true ise zorunlu alanlar doğrulanır. */
    private void applyBody(IncidentRecord e, Map<String, Object> body, boolean create) {
        // Teknik hata raporu konusuna içe alınacak üç opsiyonel alan (2026-11-20): teknik hata adedi, kesinti kaynaklı mı,
        // kesinti ID. Kural öncekilerle aynı — anahtar gövdede YOKSA dokunulmaz (eski istemci / eski pod değerleri silmez),
        // açık JSON null temizler. Öncekilerden farkı: bu üç alan KATI ayrıştırılır (alan adlı 400); mevcut alanların gevşek
        // ayrıştırması bilinçli olarak DEĞİŞMEDİ. Doğrulama HERHANGİ bir alan yazılmadan ÖNCE yapılır: geçersiz değerde
        // (yönetilen) kayıt yarım değişmiş kalmaz.
        boolean hasTechErrorCount = body.containsKey(F_TECH_ERROR_COUNT);
        boolean hasOutageRelated  = body.containsKey(F_OUTAGE_RELATED);
        boolean hasOutageId       = body.containsKey(F_OUTAGE_ID);
        Long techErrorCount   = hasTechErrorCount ? strictTechErrorCount(body.get(F_TECH_ERROR_COUNT)) : null;
        Boolean outageRelated = hasOutageRelated ? strictOutageRelated(body.get(F_OUTAGE_RELATED)) : null;
        String outageId       = hasOutageId ? strictOutageId(body.get(F_OUTAGE_ID)) : null;

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
        // Kaynak alarm bağı — varlık + görünürlük doğrulaması controller'da (IncidentAlertLinkService.validate).
        if (body.containsKey("alert_event_id"))             e.setAlertEventId(toLong(body.get("alert_event_id")));
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
        // Üç yeni alan (yukarıda doğrulandı): anahtar gövdede YOKSA dokunulmaz, açık JSON null temizler.
        if (hasTechErrorCount)                              e.setTechErrorCount(techErrorCount);
        if (hasOutageRelated)                               e.setOutageRelated(outageRelated);
        if (hasOutageId)                                    e.setOutageId(outageId);
        // Kesinti ID yalnız cevap "Evet" iken tutulur: hayır / bilinmiyor → null (yalnız cevabı değiştiren istek de ID'yi siler).
        if (!Boolean.TRUE.equals(e.getOutageRelated()))     e.setOutageId(null);
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
