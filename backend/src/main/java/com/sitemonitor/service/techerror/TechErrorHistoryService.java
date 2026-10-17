package com.sitemonitor.service.techerror;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import com.sitemonitor.model.AppUser;
import com.sitemonitor.model.TechErrorChannel;
import com.sitemonitor.model.TechErrorReport;
import com.sitemonitor.model.TechErrorReportEvent;
import com.sitemonitor.model.TechErrorTemplateVersion;
import com.sitemonitor.repository.AppUserRepository;
import com.sitemonitor.repository.TechErrorReportEventRepository;
import com.sitemonitor.repository.TechErrorTemplateVersionRepository;
import com.sitemonitor.service.techerror.TechErrorTemplate.Template;
import com.sitemonitor.service.techerror.TechErrorValues.Values;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;

/**
 * Teknik hata raporu DEĞİŞİKLİK GEÇMİŞİ ve kanal şablonu SÜRÜM GEÇMİŞİ (2026-10-14, Faz B).
 *
 * <p><b>Rapor olayları</b> değişikliği yazan işlemin İÇİNDE eklenir ({@code TechErrorReportService} çağırır): değişiklik
 * geri alınırsa olay da. Fark hesabı ({@link TechErrorChangeDiff}) bir hata verirse kayıt bozulmaz — olay
 * "ayrıntı hesaplanamadı" diye yazılır. Rapor başına en çok {@link #MAX_EVENTS_PER_REPORT} olay; sınır
 * {@link #PRUNE_SLACK} kadar aşılınca en eskiler budanır (her eklemede silme sorgusu koşmasın).
 *
 * <p><b>Şablon sürümleri</b> şablon kaydıyla aynı işlemde yazılır; kanal başına en çok {@link #MAX_TEMPLATE_VERSIONS}.
 * Bu özellikten önce açılmış kanalda ilk kayıtta o anki sürüm de taban olarak yazılır (geçmiş ondan başlar).
 */
@Slf4j
@Service
public class TechErrorHistoryService {

    public static final int MAX_EVENTS_PER_REPORT = 500;
    public static final int PRUNE_SLACK = 50;
    public static final int MAX_TEMPLATE_VERSIONS = 100;
    public static final int MAX_PAGE = 50;
    static final int MAX_RECIPIENTS = 20;
    /**
     * Tek olayın ayrıntısı en çok bu kadar karakter (inceleme 2026-10-14: 200 değişiklik × uzun etiket / önizleme ≈ 300 KB
     * olabiliyordu; bir geçmiş sayfası onlarca MB'a çıkardı). Sığmayan değişiklikler yalnız sayılır ({@code more}).
     */
    static final int MAX_DETAIL_CHARS = 64_000;

    static final ObjectMapper JSON = new ObjectMapper();

    private final TechErrorReportEventRepository events;
    private final TechErrorTemplateVersionRepository versions;
    private final AppUserRepository users;

    public TechErrorHistoryService(TechErrorReportEventRepository events, TechErrorTemplateVersionRepository versions,
                                   AppUserRepository users) {
        this.events = events;
        this.versions = versions;
        this.users = users;
    }

    // ── Rapor olayları ───────────────────────────────────────────────────────────────────────────────

    /** Olay yazar (çağıranın işleminde; işlem yoksa kendi işleminde). {@code actorId == null} = sistem. */
    @Transactional
    public TechErrorReportEvent record(TechErrorReport r, String kind, Long actorId, Long reportVersion,
                                       Map<String, Object> detail, Instant at) {
        return insert(r, kind, actorId, reportVersion, detail, at);
    }

    /** Olay satırı + budama; işlem açmaz (çağıranınkine katılır, yoksa her depo çağrısı kendi işleminde). */
    private TechErrorReportEvent insert(TechErrorReport r, String kind, Long actorId, Long reportVersion,
                                        Map<String, Object> detail, Instant at) {
        TechErrorReportEvent e = new TechErrorReportEvent();
        e.setReportId(r.getId());
        e.setChannelId(r.getChannelId());
        e.setTeamId(r.getTeamId());
        e.setEventAt(at);
        e.setActorId(actorId);
        e.setActorName(nameOf(actorId));
        e.setKind(kind);
        e.setReportVersion(reportVersion);
        e.setDetailJson(json(detail));
        TechErrorReportEvent saved = events.save(e);
        prune(r.getId());
        return saved;
    }

    /**
     * Otomatik kayıtların birleştiği pencere (2026-10-16, kullanıcı kararı: aynı kişinin 15 dk içindeki otomatik kayıtları tek
     * satır). Pencere İLK kayıttan sayılır (`first_at`): saatlerce süren yazım her 15 dk'da yeni satır + denetim kaydı açar.
     */
    public static final java.time.Duration AUTO_MERGE_WINDOW = java.time.Duration.ofMinutes(15);

    private static final java.util.concurrent.atomic.AtomicLong LAST_MERGE_WARN = new java.util.concurrent.atomic.AtomicLong();

    /** Değer kaydının olayı: alan bazında fark. Fark hesaplanamazsa kayıt yine geçer, olay ayrıntısız yazılır. */
    @Transactional
    public void recordValues(TechErrorReport saved, Template t, Values before, Values after, String channelName,
                             Long by, Instant at) {
        recordValues(saved, t, before, after, channelName, by, at, false);
    }

    /**
     * {@link #recordValues} + OTOMATİK kayıt (2026-10-16): aynı kişinin İLK kaydından {@link #AUTO_MERGE_WINDOW} içindeki
     * önceki otomatik değer olayı EN SON olaysa bu kayıt onunla BİRLEŞİR ({@link #mergeChanges}: kalıcı anahtar başına ilk
     * eski + son yeni değer; ham değeri eski hâline dönen satır düşer). Hiç fark kalmazsa olay SİLİNMEZ, "geri alındı"
     * olarak kalır (gönderimden sonra içerik sayacı arttı; boş geçmiş "değişti" durumunu açıklamasız bırakırdı). Dönüş:
     * birleşti mi (denetim kaydı pencere başına bir kez yazılır).
     *
     * <p>Eşzamanlılık: aynı raporun iki kaydı raporun sürümü + satır kilidiyle sıralanır (ikincisi 409 alır), görsel /
     * şablon / gönderim sonucu olayları da raporu kilitler. Gönderim olayı (kendi işleminde, kilitsiz) birkaç milisaniyelik
     * bir pencerede birleştirilen olaydan sonra yazılabilir — kabul edildi.
     */
    @Transactional
    public boolean recordValues(TechErrorReport saved, Template t, Values before, Values after, String channelName,
                                Long by, Instant at, boolean auto) {
        Map<String, Object> detail = new LinkedHashMap<>();
        try {
            TechErrorChangeDiff.Result d = TechErrorChangeDiff.diff(t, before, after, channelName, saved.getReportYear(),
                    saved.getReportMonth());
            List<Map<String, Object>> list = new ArrayList<>();
            int more = d.more();
            int used = 0;
            for (TechErrorChangeDiff.Change c : d.changes()) {
                Map<String, Object> m = c.toMap();
                int size = JSON.writeValueAsString(m).length() + 1;
                if (used + size > MAX_DETAIL_CHARS) {
                    more++;
                    continue;
                }
                used += size;
                list.add(m);
            }
            detail.put("changes", list);
            if (more > 0) detail.put("more", more);
        } catch (RuntimeException ex) {
            log.warn("Teknik hata raporu #{} değişiklik farkı hesaplanamadı: {}", saved.getId(), ex.toString());
            detail.put("changes", List.of());
            detail.put("unavailable", true);
        }
        if (auto) {
            detail.put("auto", true);
            if (mergeAuto(saved, detail, by, at)) return true;
            detail.put("saves", 1);
            detail.put("first_at", at.toString());
        }
        record(saved, "VALUES", by, saved.getVersion(), detail, at);
        return false;
    }

    /**
     * Son olay aynı kişinin pencere içindeki otomatik değer olayıysa onunla birleştirir; birleştirdiyse true. Okuma / JSON
     * hatası birleştirmeyi bırakır (yeni olay yazılır). Veritabanı hatası işlemi zaten geri alınacak işaretler — kaydın
     * kendisi de düşer, olay yazımı hatasıyla aynı; burada yutulması bunu değiştirmez.
     */
    @SuppressWarnings("unchecked")
    private boolean mergeAuto(TechErrorReport saved, Map<String, Object> detail, Long by, Instant at) {
        if (by == null) return false;
        try {
            List<TechErrorReportEvent> last = events.findByReportIdOrderByIdDesc(saved.getId(), org.springframework.data.domain.PageRequest.of(0, 1));
            if (last.isEmpty()) return false;
            TechErrorReportEvent e = last.get(0);
            if (!"VALUES".equals(e.getKind()) || !by.equals(e.getActorId()) || e.getEventAt() == null) return false;
            Map<String, Object> old = JSON.readValue(e.getDetailJson() == null ? "{}" : e.getDetailJson(), Map.class);
            if (!Boolean.TRUE.equals(old.get("auto"))) return false;
            Instant first = firstAt(old, e.getEventAt());
            if (first.isBefore(at.minus(AUTO_MERGE_WINDOW))) return false;     // pencere İLK kayıttan
            List<Map<String, Object>> merged = mergeChanges((List<Map<String, Object>>) old.getOrDefault("changes", List.of()),
                    (List<Map<String, Object>>) detail.getOrDefault("changes", List.of()));
            // Taşan (sayılan) satırlar iki kayıtta aynı alana ait olabilir: toplam üst sınırdır, kesin sayı değil
            int more = intOf(old.get("more")) + intOf(detail.get("more"));
            List<Map<String, Object>> kept = new ArrayList<>();
            int used = 0;
            for (Map<String, Object> m : merged) {
                int size = JSON.writeValueAsString(m).length() + 1;
                if (kept.size() >= TechErrorChangeDiff.MAX_CHANGES || used + size > MAX_DETAIL_CHARS) { more++; continue; }
                used += size;
                kept.add(m);
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("changes", kept);
            if (more > 0) out.put("more", more);
            if (Boolean.TRUE.equals(old.get("unavailable")) || Boolean.TRUE.equals(detail.get("unavailable"))) {
                out.put("unavailable", true);
            }
            if (kept.isEmpty() && more == 0 && !out.containsKey("unavailable")) out.put("reverted", true);
            out.put("auto", true);
            out.put("saves", intOf(old.get("saves")) + 1);
            out.put("first_at", first.toString());
            e.setDetailJson(json(out));
            e.setEventAt(at);
            e.setReportVersion(saved.getVersion());
            events.save(e);
            return true;
        } catch (RuntimeException ex) {
            long now = System.currentTimeMillis();
            long prev = LAST_MERGE_WARN.get();
            if (now - prev > 3_600_000L && LAST_MERGE_WARN.compareAndSet(prev, now)) {
                log.warn("Teknik hata raporu #{} otomatik kayıt olayı birleştirilemedi (saatte bir yazılır): {}", saved.getId(), ex.toString());
            }
            return false;
        }
    }

    private static Instant firstAt(Map<String, Object> detail, Instant fallback) {
        Object v = detail.get("first_at");
        if (v instanceof String s) {
            try { return Instant.parse(s); } catch (java.time.format.DateTimeParseException ignore) { /* eski biçim */ }
        }
        return fallback;
    }

    private static int intOf(Object o) {
        return o instanceof Number n ? n.intValue() : 0;
    }

    /**
     * İki değişiklik listesini birleştirir (eski önce). Kalıcı ANAHTARI ({@code key}) olan satırlar anahtar başına tek satır
     * olur — ilk görünüş sırasında: ilk eski değer (+ parmak izi, uzunluk) ile son yeni değer (+ parmak izi, uzunluk, en
     * yeni ad); işlem son duruma göre yeniden yazılır (konu: ekle / çıkar, özet: elle / otomatik / değişti, diğerleri set /
     * clear / change); iki parmak izi de varsa ve eşitse (ham değer eski hâline döndü) satır düşer. Sıralama satırı
     * (reorder) anahtarıyla bir kez kalır. Anahtarsız satır (eski kayıt) olduğu gibi, sırasıyla eklenir — asla düşmez.
     */
    static List<Map<String, Object>> mergeChanges(List<Map<String, Object>> older, List<Map<String, Object>> newer) {
        Map<Object, Map<String, Object>> rows = new LinkedHashMap<>();
        int legacy = 0;
        List<Map<String, Object>> all = new ArrayList<>(older);
        all.addAll(newer);
        for (Map<String, Object> c : all) {
            Object key = c.get("key");
            if (!(key instanceof String k) || k.isEmpty()) {
                rows.put("legacy#" + (legacy++), new LinkedHashMap<>(c));
                continue;
            }
            Map<String, Object> prev = rows.get(k);
            if (prev == null) {
                rows.put(k, new LinkedHashMap<>(c));
                continue;
            }
            if ("reorder".equals(c.get("op"))) continue;
            Map<String, Object> m = new LinkedHashMap<>(prev);
            m.put("label", c.get("label"));
            m.put("section", c.get("section"));
            m.put("after", c.get("after"));
            copyOrRemove(c, m, "after_length");
            copyOrRemove(c, m, "ah");
            m.put("op", opOf(k, m));
            rows.put(k, m);
        }
        // Konunun en son adı (yeni satırlar sonra geldiği için sonuncusu kazanır): birleşmiş "eklendi" satırı onu gösterir
        Map<String, Object> lastSubject = new java.util.HashMap<>();
        for (Map<String, Object> c : all) {
            if (c.get("key") instanceof String k && k.startsWith("i:") && c.get("subject") != null && k.lastIndexOf(':') > 1) {
                lastSubject.put(k.substring(0, k.lastIndexOf(':') + 1), c.get("subject"));
            }
        }
        // Pencerede eklenen ya da silinen konunun parça satırları (açıklama, adet …) düşer: tek kayıttaki fark da yeni konu
        // için yalnız "eklendi" yazar
        java.util.Set<String> wholeIssue = new java.util.HashSet<>();
        for (Map.Entry<Object, Map<String, Object>> en : rows.entrySet()) {
            if (en.getKey() instanceof String k && k.endsWith(":item")) {
                Object bh = en.getValue().get("bh"), ah = en.getValue().get("ah");
                if (bh != null && !bh.equals(ah)) wholeIssue.add(k.substring(0, k.length() - "item".length()));
            }
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map.Entry<Object, Map<String, Object>> en : rows.entrySet()) {
            Map<String, Object> m = en.getValue();
            if (!(en.getKey() instanceof String k) || k.startsWith("legacy#")) { out.add(m); continue; }
            Object bh = m.get("bh"), ah = m.get("ah");
            if (bh != null && bh.equals(ah)) continue;                                 // ham değer eski hâline döndü
            if (!k.endsWith(":item") && k.startsWith("i:") && k.lastIndexOf(':') > 1
                    && wholeIssue.contains(k.substring(0, k.lastIndexOf(':') + 1))) continue;
            if (k.endsWith(":item") && "add".equals(m.get("op"))) {
                Object name = lastSubject.get(k.substring(0, k.length() - "item".length()));
                if (name != null) {
                    m.put("label", name);
                    m.put("after", name);
                    m.put("subject", name);
                }
            }
            out.add(m);
        }
        return out;
    }

    private static void copyOrRemove(Map<String, Object> from, Map<String, Object> to, String key) {
        if (from.containsKey(key)) to.put(key, from.get(key));
        else to.remove(key);
    }

    /** Birleşmiş satırın işlemi: önceki / sonraki parmak izine ("0" = boş) ve satırın türüne göre. */
    private static String opOf(String key, Map<String, Object> m) {
        boolean wasEmpty = "0".equals(m.get("bh")) || (m.get("bh") == null && m.get("before") == null);
        boolean nowEmpty = "0".equals(m.get("ah")) || (m.get("ah") == null && m.get("after") == null);
        if (key.endsWith(":item")) return wasEmpty ? "add" : "remove";
        if (key.startsWith("s:")) return wasEmpty ? "manual" : nowEmpty ? "auto" : "change";
        return wasEmpty ? "set" : nowEmpty ? "clear" : "change";
    }

    /**
     * Gönderim olayı (gönderim akışından SONRA, kendi işleminde): yazılamazsa gönderim sonucu değişmez — yalnız günlüğe
     * düşer. {@code recipients} en çok {@link #MAX_RECIPIENTS} adres; {@code reason} gönderilmeme sebebinin kodu
     * ({@link #sendReason}).
     */
    public void recordSend(TechErrorReport r, String kind, Long by, String state, Collection<String> recipients,
                           String reason, Instant at) {
        try {
            insert(r, kind, by, null, sendDetail(state, recipients, reason), at);
        } catch (RuntimeException e) {
            log.warn("Teknik hata raporu #{} gönderim olayı yazılamadı: {}", r.getId(), e.toString());
        }
    }

    public static Map<String, Object> sendDetail(String state, Collection<String> recipients, String reason) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("state", state);
        if (reason != null) detail.put("reason", reason);
        if (recipients != null && !recipients.isEmpty()) {
            List<String> to = new ArrayList<>(recipients);
            detail.put("recipient_count", to.size());
            detail.put("recipients", to.size() > MAX_RECIPIENTS ? List.copyOf(to.subList(0, MAX_RECIPIENTS)) : to);
        }
        return detail;
    }

    /**
     * Gönderim günlüğü durumundan arayüzün gösterdiği SEBEP kodu (yalnız gönderilmeyenler için): MAIL_OFF (posta kapalı),
     * INACTIVE_RECIPIENTS (adreslerin hepsi pasif kullanıcının), PDF_BUSY (PDF sırası dolu), PDF_TOO_LARGE, NO_RECIPIENT;
     * bilinmeyen null — arayüz yalnız durumu gösterir.
     */
    public static String sendReason(String status) {
        if (status == null) return null;
        if (status.startsWith("SKIPPED_DISABLED")) return "MAIL_OFF";
        if (status.startsWith("SKIPPED") && status.contains("pasif")) return "INACTIVE_RECIPIENTS";
        if (status.startsWith("SKIPPED") && status.contains("PDF")) return "PDF_BUSY";
        if (status.startsWith("SKIPPED_NO_RECIPIENT") || status.startsWith("SKIPPED: alıcı yok")) return "NO_RECIPIENT";
        if (status.startsWith("FAILED") && status.contains("PDF e-posta sınırını")) return "PDF_TOO_LARGE";
        if (status.startsWith("SKIPPED") && status.contains("onay eskidi")) return "APPROVAL_STALE";
        return null;
    }

    /** Sınır {@link #PRUNE_SLACK} kadar aşıldıysa en yeni {@link #MAX_EVENTS_PER_REPORT} olay dışındakiler silinir. */
    void prune(Long reportId) {
        if (events.countByReportId(reportId) <= MAX_EVENTS_PER_REPORT + PRUNE_SLACK) return;
        List<Long> edge = events.idsNewestFirst(reportId, PageRequest.of(MAX_EVENTS_PER_REPORT, 1));
        if (!edge.isEmpty()) events.deleteUpTo(reportId, edge.get(0));
    }

    /** {@code capped}: rapor olay sınırına ulaştı — en eski olaylar budanmış olabilir (arayüz "başlangıç" demez). */
    public record HistoryPage(List<TechErrorReportEvent> events, boolean hasMore, Instant recordedSince, boolean capped) { }

    /** En yeni önce; {@code beforeId} verilirse ondan eskiler (anahtar kümesi). {@code limit} 1..{@link #MAX_PAGE}. */
    public HistoryPage reportHistory(Long reportId, Long beforeId, int limit) {
        int n = Math.max(1, Math.min(MAX_PAGE, limit));
        PageRequest page = PageRequest.of(0, n + 1);
        List<TechErrorReportEvent> list = beforeId == null ? events.findByReportIdOrderByIdDesc(reportId, page)
                : events.findByReportIdAndIdLessThanOrderByIdDesc(reportId, beforeId, page);
        boolean more = list.size() > n;
        boolean capped = !more && events.countByReportId(reportId) >= MAX_EVENTS_PER_REPORT;
        return new HistoryPage(more ? list.subList(0, n) : list, more, events.firstEventAt(reportId), capped);
    }

    @Transactional
    public void deleteForReport(Long reportId) {
        events.deleteAllForReport(reportId);
    }

    @Transactional
    public void deleteForReports(List<Long> reportIds) {
        for (int i = 0; i < reportIds.size(); i += 500) events.deleteAllForReports(reportIds.subList(i, Math.min(reportIds.size(), i + 500)));
    }

    /** Saklı ayrıntı → harita (okunamazsa boş; okuma hiç bozulmaz). */
    public static Map<String, Object> detail(TechErrorReportEvent e) {
        if (e.getDetailJson() == null || e.getDetailJson().isBlank()) return Map.of();
        try {
            return JSON.readValue(e.getDetailJson(), new TypeReference<LinkedHashMap<String, Object>>() { });
        } catch (Exception ex) {
            return Map.of("unavailable", true);
        }
    }

    // ── Şablon sürümleri ─────────────────────────────────────────────────────────────────────────────

    /** Sürümün kopyasını yazar (çağıranın işleminde). Aynı sürüm zaten varsa dokunmaz. */
    @Transactional
    public void recordTemplateVersion(TechErrorChannel ch, int version, String templateJson, Instant at, Long by) {
        recordTemplateVersion(ch, version, templateJson, at, by, null);
    }

    /** {@code actorName}: kişi değil sistem yazdıysa görünen ad (ör. otomatik oran göçü); null → kaydedenin adı. */
    @Transactional
    public void recordTemplateVersion(TechErrorChannel ch, int version, String templateJson, Instant at, Long by, String actorName) {
        if (versions.existsByChannelIdAndVersion(ch.getId(), version)) return;
        Template t = TechErrorTemplate.fromStored(templateJson);
        TechErrorTemplateVersion v = new TechErrorTemplateVersion();
        v.setChannelId(ch.getId());
        v.setTeamId(ch.getTeamId());
        v.setVersion(version);
        v.setTemplateJson(templateJson);
        v.setSavedAt(at);
        v.setSavedById(by);
        v.setSavedByName(actorName != null ? actorName : nameOf(by));
        v.setSectionCount(t.sections().size());
        v.setFieldCount(t.allFields().size());
        v.setSlotCount(t.allSlots().size());
        versions.save(v);
        if (versions.countByChannelId(ch.getId()) > MAX_TEMPLATE_VERSIONS) {
            List<Integer> edge = versions.versionsNewestFirst(ch.getId(), PageRequest.of(MAX_TEMPLATE_VERSIONS, 1));
            if (!edge.isEmpty()) versions.deleteUpTo(ch.getId(), edge.get(0));
        }
    }

    public List<com.sitemonitor.repository.TechErrorTemplateVersionRepository.Meta> templateVersions(Long channelId) {
        return versions.findByChannelIdOrderByVersionDesc(channelId, PageRequest.of(0, MAX_TEMPLATE_VERSIONS));
    }

    public Optional<TechErrorTemplateVersion> templateVersion(Long channelId, int version) {
        return versions.findByChannelIdAndVersion(channelId, version);
    }

    @Transactional
    public void deleteForChannel(Long channelId) {
        versions.deleteAllForChannel(channelId);
    }

    // ── Yardımcılar ──────────────────────────────────────────────────────────────────────────────────

    /** Kullanıcının o anki görünen adı (yoksa kullanıcı adı); bulunamazsa ya da sistemse null. 200 karakterde kesilir. */
    String nameOf(Long userId) {
        if (userId == null) return null;
        try {
            return users.findById(userId).map(TechErrorHistoryService::display).orElse(null);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String display(AppUser u) {
        String n = u.getDisplayName() != null && !u.getDisplayName().isBlank() ? u.getDisplayName() : u.getUsername();
        return n != null && n.length() > 200 ? n.substring(0, 200) : n;
    }

    private static String json(Map<String, Object> detail) {
        if (detail == null || detail.isEmpty()) return null;
        try {
            return JSON.writeValueAsString(detail);
        } catch (Exception e) {
            return null;
        }
    }
}
