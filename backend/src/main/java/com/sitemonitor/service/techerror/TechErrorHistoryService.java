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

    /** Değer kaydının olayı: alan bazında fark. Fark hesaplanamazsa kayıt yine geçer, olay ayrıntısız yazılır. */
    @Transactional
    public void recordValues(TechErrorReport saved, Template t, Values before, Values after, String channelName,
                             Long by, Instant at) {
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
        record(saved, "VALUES", by, saved.getVersion(), detail, at);
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
