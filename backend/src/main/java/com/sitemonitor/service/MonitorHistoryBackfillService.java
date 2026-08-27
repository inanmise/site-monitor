package com.sitemonitor.service;

import com.sitemonitor.model.AuditLog;
import com.sitemonitor.model.MonitorChangeLog;
import com.sitemonitor.repository.AuditLogRepository;
import com.sitemonitor.repository.MonitorChangeLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Değişiklik geçmişini denetim kaydından TEK SEFER geri doldurur (K5).
 *
 * <p>Özellik yeni; ondan önce yapılan tüm izleme değişiklikleri yalnız {@code audit_log}'da
 * duruyor. Geçmiş sekmesi bomboş açılırsa kullanıcı "demek hiç değişmemiş" diye okur — oysa
 * kayıt VAR, sadece başka tabloda. Bu servis o satırları ürün tarafına taşır.
 *
 * <p><b>Bilinen sınır:</b> denetim kaydının kendi saklama süresi var (varsayılan 365 gün), yani
 * ondan eskisi zaten yok. Taşınan satırlar {@code AUDIT_BACKFILL} notuyla işaretlenir; kullanıcı
 * ekranda "bu satır denetimden geldi" bilgisini görür ve tam alan farkı olmayabileceğini anlar.
 *
 * <p><b>İdempotens iki katmanlı:</b> (1) tablodaki nişan satırı ikinci koşuyu hiç başlatmaz,
 * (2) satır bazında kaynak+olay+zaman kontrolü var. İkisi birden, yarıda kalan bir koşunun
 * tekrarında bile geçmişin çiftlenmemesini garanti eder.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MonitorHistoryBackfillService {

    /** Nişan satırının sahte kaynağı — gerçek bir izlemeyle çakışmasın diye ayrı bir tür. */
    static final String MARKER_KIND = "SYSTEM";
    private static final Long MARKER_ID = 0L;

    /**
     * Geri doldurma KAPSAMININ sürümü; nişanın {@code seq} kolonunda saklanır.
     *
     * <p>Neden gerekli: kapsam sonradan genişleyebiliyor. {@code PAGESPEED} türü aşağıdaki
     * haritalara ilk sürümde yazılmamıştı; nişan "koştu" dediği için ikinci koşu hiç
     * başlamıyordu ve o türün özellik ÖNCESİ değişiklikleri {@code audit_log}'da kilitli
     * kalıyordu — ekranda kart çıkıyor ama geçmişi eksik görünüyordu. Sürüm artınca bir kez
     * daha koşulur; satır bazındaki kaynak+olay+zaman kontrolü zaten taşınmış satırları
     * yeniden yazmaz, yani ikinci koşu YALNIZ yeni türün satırlarını taşır.
     *
     * <p>Sürüm ARTTIRMA kuralı: {@link #KIND_BY_RESOURCE} ya da {@link #EVENT_BY_ACTION}
     * genişlediğinde artır. Yalnız kod düzeltmesi yapıldıysa artırma — gereksiz tam tarama olur.
     *
     * <p>v1: ilk sürüm (8 tür) · v2: PAGESPEED eklendi.
     */
    private static final int BACKFILL_VERSION = 2;

    /** Denetimdeki kaynak türü → geçmiş türü. Buraya girmeyen kayıt taşınmaz (sessiz değil: sayılır). */
    private static final Map<String, String> KIND_BY_RESOURCE = Map.of(
            "PORT_MONITOR", MonitorHistoryService.PORT,
            "DNS_MONITOR", MonitorHistoryService.DNS,
            "KEYWORD_MONITOR", MonitorHistoryService.KEYWORD,
            "HTTP_MONITOR", MonitorHistoryService.HTTP,
            "PAGE_MONITOR", MonitorHistoryService.PAGE,
            "SCRIPTED_MONITOR", MonitorHistoryService.SCRIPTED,
            "DOMAIN_MONITOR", MonitorHistoryService.DOMAIN,
            "PING_MONITOR", MonitorHistoryService.PING,
            "PAGESPEED_MONITOR", MonitorHistoryService.PAGESPEED);

    /** Denetim olayı → geçmiş olayı. */
    private static final Map<String, String> EVENT_BY_ACTION = Map.of(
            "MONITOR_CREATE", MonitorHistoryService.CREATE,
            "MONITOR_UPDATE", MonitorHistoryService.UPDATE,
            "MONITOR_DELETE", MonitorHistoryService.DELETE);

    /** Geçmiş türü → kimlik kolonlarının yazılacağı tablo (ilk oluşturanı kartta göstermek için). */
    private static final Map<String, String> TABLE_BY_KIND = Map.of(
            MonitorHistoryService.PORT, "port_monitors",
            MonitorHistoryService.DNS, "dns_monitors",
            MonitorHistoryService.KEYWORD, "keyword_monitors",
            MonitorHistoryService.HTTP, "http_monitors",
            MonitorHistoryService.PAGE, "page_monitors",
            MonitorHistoryService.SCRIPTED, "scripted_monitors",
            MonitorHistoryService.DOMAIN, "domain_monitors",
            MonitorHistoryService.PING, "ping_monitors",
            MonitorHistoryService.PAGESPEED, "pagespeed_monitors");

    private final AuditLogRepository auditRepo;
    private final MonitorChangeLogRepository changeRepo;
    private final MonitorHistoryService history;
    private final JdbcTemplate jdbcTemplate;

    /**
     * Açılışta bir kez çalışır. Hiçbir arızası uygulamayı düşürmez — geçmiş eksik açılır, o kadar.
     *
     * @return taşınan satır sayısı (zaten koşmuşsa -1)
     */
    public int runOnce() {
        try {
            // Nişan VARSA ama sürümü eskiyse kapsam büyümüş demektir: bir kez daha koşulur.
            Integer marked = changeRepo
                    .findFirstByResourceKindAndEventTypeOrderByIdDesc(
                            MARKER_KIND, MonitorHistoryService.AUDIT_BACKFILL)
                    .map(MonitorChangeLog::getSeq)
                    .orElse(null);
            if (marked != null && marked >= BACKFILL_VERSION) {
                return -1;
            }
            int moved = 0, stamped = 0;
            // En eski CREATE satırı kimlik kolonlarını dolduracak; sıralama ARTAN olmalı.
            List<AuditLog> rows = auditRepo.findByEventTypeInOrderByEventTimeAsc(
                    List.copyOf(EVENT_BY_ACTION.keySet()));
            Map<String, Boolean> stampedIds = new LinkedHashMap<>();

            for (AuditLog a : rows) {
                String kind = KIND_BY_RESOURCE.get(a.getResourceType());
                String event = EVENT_BY_ACTION.get(a.getEventType());
                Long id = parseId(a.getResourceId());
                if (kind == null || event == null || id == null) continue;

                // Denetimde ayrı bir "ad" kolonu yok: monitörün adı `detail` alanında taşınıyor
                // (recordAction(..., resourceId, detail, changes) imzası). Geçmişte kaynak adı olarak
                // onu kullanıyoruz — aksi halde taşınan satırlar adsız kalırdı.
                if (history.recordBackfill(kind, id, a.getDetail(), a.getActorTeamId(), event,
                        a.getChanges(), a.getActor(), a.getActorId(), a.getIpAddress(), a.getEventTime())) {
                    moved++;
                }

                // İlk CREATE satırı = kaydı kimin oluşturduğu. Kolon zaten doluysa dokunulmaz.
                String key = kind + ":" + id;
                if (MonitorHistoryService.CREATE.equals(event) && !stampedIds.containsKey(key)) {
                    stampedIds.put(key, true);
                    if (stampCreator(kind, id, a)) stamped++;
                }
            }
            writeMarker(moved);
            log.info("İzleme geçmişi geri doldurma tamamlandı: {} satır taşındı, {} kayda oluşturan işlendi",
                    moved, stamped);
            return moved;
        } catch (Exception e) {
            // Nişan YAZILMAZ: sorun giderildikten sonra bir sonraki açılış yeniden dener.
            log.warn("İzleme geçmişi geri doldurma atlandı: {}", e.toString());
            return 0;
        }
    }

    /** Kimlik kolonlarını yalnız BOŞSA doldurur (elle düzeltilmiş bir kaydı ezmez). */
    private boolean stampCreator(String kind, Long id, AuditLog a) {
        String table = TABLE_BY_KIND.get(kind);
        if (table == null || a.getActor() == null) return false;
        try {
            int n = jdbcTemplate.update(
                    "UPDATE " + table + " SET created_by = ?, created_ip = ? WHERE id = ? AND created_by IS NULL",
                    a.getActor(), a.getIpAddress(), id);
            return n > 0;
        } catch (Exception e) {
            log.debug("Oluşturan künyesi yazılamadı ({} {}): {}", kind, id, e.toString());
            return false;
        }
    }

    /** Nişan satırı — "koştu" bilgisi ve kaç satır taşındığı burada durur. */
    private void writeMarker(int moved) {
        MonitorChangeLog marker = new MonitorChangeLog();
        marker.setResourceKind(MARKER_KIND);
        marker.setResourceId(MARKER_ID);
        marker.setResourceName("audit-backfill");
        marker.setSeq(BACKFILL_VERSION);
        marker.setEventType(MonitorHistoryService.AUDIT_BACKFILL);
        marker.setNote("Denetim kaydından " + moved + " satır taşındı (kapsam sürümü "
                + BACKFILL_VERSION + ")");
        marker.setActor("system");
        marker.setCreatedAt(java.time.format.DateTimeFormatter
                .ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(java.time.ZoneOffset.UTC)
                .format(java.time.Instant.now()));
        changeRepo.save(marker);
    }

    private static Long parseId(String raw) {
        try { return raw == null ? null : Long.valueOf(raw.trim()); }
        catch (Exception e) { return null; }
    }
}
