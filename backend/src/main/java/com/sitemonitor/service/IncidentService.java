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

    /**
     * Takım adının kaynağı (2026-11-20, "Sağlam Kayıt"): {@code team_name} artık istemciden ALINMAZ, {@code team_id}'nin
     * veritabanındaki takımından okunur (istemci eski / yanlış adı yazamaz). Bean yoksa (elle kurulan birim testleri)
     * eski davranış: gövdedeki {@code team_name}.
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.sitemonitor.repository.TeamRepository teamRepo;

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
     *  (teamId VEYA createdByTeamId kapsamda). Boş liste → kapsamsız kullanıcı, hiçbir şey görmez. */
    public Page<IncidentRecord> list(String q, String severity, String category, String status,
                                     String service, String channel, String since, String until,
                                     Long teamId, Boolean slaBreached, Boolean open,
                                     List<Long> scope, Pageable pageable) {
        // Tarih sınırları ÖNCE doğrulanır (2026-11-20, "Sağlam Kayıt"): okunamayan değer alan adlı 400 — eskiden metin
        // olduğu gibi karşılaştırılıyor, "dün" gibi bir değer sessizce boş liste döndürüyordu.
        String from = filterStamp(since, "since");
        String to   = filterStamp(until, "until");
        boolean scoped = scope != null;
        if (scoped && scope.isEmpty()) return Page.empty(pageable);
        List<Long> scopeList = scoped ? scope : List.of(-1L);   // global'de dummy (scoped=false kısa-devre)
        // LIKE kalıpları: Locale.ROOT ile küçük harf + %, _, ! kaçışlı (sorguda ESCAPE '!') — "%" araması her kaydı bulmaz.
        String like = likePattern(q);
        String svc  = likePattern(service);
        // channel artık CSV saklanabildiğinden TAM eşleşme yerine CSV-içinde-geçen (LIKE) eşleşme.
        String chn  = likePattern(channel);
        return repo.findFiltered(like, blankToNull(severity), blankToNull(category), blankToNull(status),
                svc, chn, from, to, teamId, slaBreached, open,
                scoped, scopeList, pageable);
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

    /** Trend + özet: günlük seri, severity/category kırılımı, özet sayılar. */
    public Map<String, Object> trends(String since, String until, List<Long> scope) {
        boolean scoped = scope != null;                                          // null = global (admin/AUDIT)
        List<Long> scopeList = (scope != null && !scope.isEmpty()) ? scope : List.of(-1L); // boş kapsam → hiçbir şey eşleşmez
        String s = filterStamp(since, "since"), u = filterStamp(until, "until");   // listeyle aynı kural (okunamayan → 400)

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
