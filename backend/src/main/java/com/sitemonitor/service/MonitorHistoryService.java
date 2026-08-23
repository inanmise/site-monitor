package com.sitemonitor.service;

import com.sitemonitor.model.MonitorChangeLog;
import com.sitemonitor.repository.MonitorChangeLogRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

/**
 * İzleme YAPILANDIRMASI değişiklik geçmişinin TEK yazım noktası.
 *
 * <p><b>Denetimin yerine geçmez, yanına yazar.</b> {@code auditService.recordAction(...)} çağrıları
 * olduğu gibi kalır (hash zinciri, admin kapısı, kendi retention'ı). Burası ürün-görünür katman:
 * takım üyesi kendi izlemesinin geçmişini görebilsin diye ayrı bir tabloya yazar. Neden ayrı
 * olduğu {@link MonitorChangeLog} javadoc'unda.
 *
 * <p><b>Diff İKİNCİ kez icat edilmez:</b> {@link AuditDiff#diff} kullanılır, yani hassas alan
 * maskesi ({@code ***}) denetimle birebir aynı kara-listeden gelir.
 *
 * <p><b>Best-effort sözleşmesi:</b> buradaki hiçbir arıza kullanıcının kaydını düşürmez —
 * istisna yutulur ve {@code log.warn} yazılır ({@code ActivityLogService} ile aynı duruş).
 * Ama sessiz de kalmaz: geçmişin boşalması fark edilebilir olmalı.
 *
 * <p><b>SINIR — buraya ne YAZILMAZ:</b> kontrol sonuçları, manuel "Şimdi Kontrol Et" tetikleri ve
 * alarm olayları. Onların yeri {@code ActivityLog} / {@code *Check} tablolarıdır. Karışırsa
 * "neyi değiştirdik" sinyali kontrol gürültüsünde boğulur ve bu tablonun tek faydası kaybolur.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MonitorHistoryService {

    // ── Kaynak türleri ──────────────────────────────────────────────────────────────────────
    public static final String PORT = "PORT";
    public static final String DNS = "DNS";
    public static final String KEYWORD = "KEYWORD";
    public static final String HTTP = "HTTP";
    public static final String PAGE = "PAGE";
    public static final String PAGESPEED = "PAGESPEED";
    public static final String SCRIPTED = "SCRIPTED";
    public static final String DOMAIN = "DOMAIN";
    public static final String PING = "PING";
    public static final String INVENTORY = "INVENTORY";
    public static final String GROUP = "GROUP";
    public static final String MAINTENANCE = "MAINTENANCE";

    /** Arayüzün `kind` yol parametresi → depolanan tür. Tek kaynak; sözleşme testi bunu sayar. */
    public static final Map<String, String> KIND_BY_PATH = Map.ofEntries(
            Map.entry("port", PORT), Map.entry("dns", DNS), Map.entry("keyword", KEYWORD),
            Map.entry("http", HTTP), Map.entry("page", PAGE), Map.entry("pagespeed", PAGESPEED),
            Map.entry("scripted", SCRIPTED),
            Map.entry("domain", DOMAIN), Map.entry("ping", PING),
            Map.entry("inventory", INVENTORY), Map.entry("group", GROUP),
            Map.entry("maintenance", MAINTENANCE));

    // ── Olay türleri ────────────────────────────────────────────────────────────────────────
    public static final String CREATE = "CREATE";
    public static final String UPDATE = "UPDATE";
    public static final String DELETE = "DELETE";
    public static final String RESTORE = "RESTORE";
    public static final String GROUP_RENAME = "GROUP_RENAME";
    public static final String AUDIT_BACKFILL = "AUDIT_BACKFILL";

    private static final DateTimeFormatter ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);

    private final MonitorChangeLogRepository repo;
    private final ClientIpResolver clientIpResolver;

    /**
     * Bir yapılandırma olayını kaydeder.
     *
     * @param before olay ÖNCESİ snapshot ({@link AuditDiff#snapshot}); CREATE'te null
     * @param after  olay SONRASI snapshot; DELETE'te son durum
     * @param note   kullanıcının yazdığı opsiyonel değişiklik nedeni
     * @return yazılan satır ya da null (yazılmadıysa / hata olduysa)
     */
    public MonitorChangeLog record(String kind, Long id, String name, Long teamId, String eventType,
                                   Map<String, Object> before, Map<String, Object> after,
                                   String note, HttpSession session) {
        if (kind == null || id == null || eventType == null) return null;
        try {
            String changes = AuditDiff.diff(before, after);
            // UPDATE'te hiçbir alan değişmediyse satır YAZILMAZ: "kaydet"e basmak tek başına
            // geçmişte bir olay değildir ve zaman çizelgesini boş satırlarla doldururdu.
            if (UPDATE.equals(eventType) && changes == null && isBlank(note)) return null;

            MonitorChangeLog row = new MonitorChangeLog();
            row.setResourceKind(kind);
            row.setResourceId(id);
            row.setResourceName(name);
            row.setSeq(nextSeq(kind, id));
            row.setEventType(eventType);
            row.setTeamId(teamId);
            row.setChanges(changes);
            row.setSnapshot(AuditDiff.snapshotJson(after != null ? after : before));
            row.setNote(isBlank(note) ? null : note.trim());
            row.setCreatedAt(ISO.format(Instant.now()));

            HttpServletRequest request = currentRequest();
            row.setActor(strAttr(session, "username"));
            row.setActorId(longAttr(session, "userId"));
            row.setActorName(strAttr(session, "fullName"));
            row.setIpAddress(request == null ? null : clientIpResolver.resolve(request));
            row.setUserAgent(request == null ? null : request.getHeader("User-Agent"));

            return repo.save(row);
        } catch (Exception e) {
            log.warn("İzleme geçmişi yazılamadı ({} {} / {}): {}", kind, id, eventType, e.toString());
            return null;
        }
    }

    /** Geriye dönük doldurma için: aktör/IP/zaman DIŞARIDAN verilir (oturum bağlamı yoktur). */
    public void recordBackfill(String kind, Long id, String name, Long teamId, String eventType,
                               String changes, String actor, Long actorId, String ip,
                               String createdAt) {
        try {
            // Aynı kaynak+olay+zaman üçlüsü zaten varsa ATLA — backfill iki kez koşarsa geçmiş
            // çiftlenirdi ve bunu geri almak elle temizlik gerektirirdi.
            if (repo.existsByResourceKindAndResourceIdAndEventTypeAndCreatedAt(kind, id, eventType, createdAt)) return;

            MonitorChangeLog row = new MonitorChangeLog();
            row.setResourceKind(kind);
            row.setResourceId(id);
            row.setResourceName(name);
            row.setSeq(nextSeq(kind, id));
            // Olayın GERÇEK türü korunur; kaynağı ayırt etmek için not düşülür — kullanıcı
            // "bu satır nereden geldi" diye sorduğunda cevap ekranda olsun.
            row.setEventType(eventType);
            row.setTeamId(teamId);
            row.setChanges(changes);
            row.setNote("Denetim kaydından taşındı (" + AUDIT_BACKFILL + ")");
            row.setActor(actor);
            row.setActorId(actorId);
            row.setIpAddress(ip);
            row.setCreatedAt(createdAt);
            repo.save(row);
        } catch (Exception e) {
            log.warn("Geçmiş geri-doldurma satırı yazılamadı ({} {}): {}", kind, id, e.toString());
        }
    }

    /**
     * Kaynak başına sıradaki {@code seq}.
     *
     * <p>Kilit ALINMAZ: eşzamanlı iki güncelleme aynı numarayı alabilir ve bu kabul edilir —
     * sıralama {@code createdAt}+{@code id} ile zaten kesin, {@code seq} yalnız okunabilirlik
     * ("0 = ilk kayıt") içindir. Kilit almak her yazma yolunu yavaşlatırdı.
     */
    private int nextSeq(String kind, Long id) {
        return repo.findMaxSeq(kind, id).map(v -> v + 1).orElse(0);
    }

    /** Aktörün kimlik künyesini entity'ye basar (kart künyesi geçmişe gitmeden okusun). */
    public void stampCreated(Object entity, HttpSession session) {
        HttpServletRequest request = currentRequest();
        setIfPresent(entity, "setCreatedBy", strAttr(session, "username"));
        setIfPresent(entity, "setCreatedByName", strAttr(session, "fullName"));
        setIfPresent(entity, "setCreatedIp", request == null ? null : clientIpResolver.resolve(request));
    }

    public void stampUpdated(Object entity, HttpSession session) {
        setIfPresent(entity, "setUpdatedBy", strAttr(session, "username"));
        setIfPresent(entity, "setUpdatedByName", strAttr(session, "fullName"));
    }

    /** Setter yoksa sessizce geçer — kimlik kolonu olmayan bir entity çağrıyı düşürmesin. */
    private static void setIfPresent(Object bean, String setter, String value) {
        if (bean == null) return;
        try {
            bean.getClass().getMethod(setter, String.class).invoke(bean, value);
        } catch (Exception ignored) {
            // kolon yok → künye yok; geçmiş tablosu yine yazılıyor
        }
    }

    private static HttpServletRequest currentRequest() {
        try {
            var attrs = RequestContextHolder.getRequestAttributes();
            if (attrs instanceof ServletRequestAttributes sra) return sra.getRequest();
        } catch (Exception ignored) { /* zamanlanmış iş bağlamı — istek yok */ }
        return null;
    }

    private static String strAttr(HttpSession s, String key) {
        Object v = s != null ? s.getAttribute(key) : null;
        return v != null ? v.toString() : null;
    }

    private static Long longAttr(HttpSession s, String key) {
        Object v = s != null ? s.getAttribute(key) : null;
        return v instanceof Number n ? n.longValue() : null;
    }

    private static boolean isBlank(String s) { return s == null || s.trim().isEmpty(); }

    /**
     * Snapshot'taki değerleri entity'ye geri yazar (K6 — "geri döndür").
     *
     * <p><b>Maskeli değer geri YAZILMAZ.</b> Hassas alanlar snapshot'a {@code ***} olarak
     * girdiği için geri yazmak, gerçek parolayı üç yıldızla EZMEK demek olurdu — izleme
     * sessizce kimlik doğrulayamaz hâle gelirdi. O alanlar olduğu gibi bırakılır ve
     * atlandıkları çağırana bildirilir ki kullanıcı uyarılabilsin.
     *
     * <p>Yalnız {@code allowed} listesindeki alanlara dokunulur: kimlik kolonları
     * ({@code createdBy}…), {@code id} ve zaman damgaları geçmişin konusu değildir.
     *
     * @return [0] = geri yazılan alanlar, [1] = maskeli olduğu için atlananlar
     */
    public static List<List<String>> applySnapshot(Object entity, Map<String, Object> snapshot,
                                                   String[] allowed, java.util.Set<String> skip) {
        List<String> applied = new java.util.ArrayList<>();
        List<String> masked = new java.util.ArrayList<>();
        if (entity == null || snapshot == null) return List.of(applied, masked);

        for (String field : allowed) {
            if (skip != null && skip.contains(field)) continue;
            if (!snapshot.containsKey(field)) continue;
            Object value = snapshot.get(field);
            if (AuditDiff.MASK.equals(value)) { masked.add(field); continue; }

            String setter = "set" + Character.toUpperCase(field.charAt(0)) + field.substring(1);
            for (var m : entity.getClass().getMethods()) {
                if (!m.getName().equals(setter) || m.getParameterCount() != 1) continue;
                try {
                    Object coerced = coerce(value, m.getParameterTypes()[0]);
                    // İlkel tipe null yazılamaz; alanı atlamak, çökmekten iyidir.
                    if (coerced == null && m.getParameterTypes()[0].isPrimitive()) break;
                    m.invoke(entity, coerced);
                    applied.add(field);
                } catch (Exception e) {
                    log.warn("Geri yükleme alanı atlandı ({}): {}", field, e.toString());
                }
                break;
            }
        }
        return List.of(applied, masked);
    }

    /** JSON'dan gelen gevşek tipi setter'ın beklediği tipe çevirir (Integer↔Long↔String↔Boolean). */
    private static Object coerce(Object value, Class<?> target) {
        if (value == null) return null;
        String s = value.toString();
        if (target == String.class) return s;
        if (target == Integer.class || target == int.class) return (int) Double.parseDouble(s);
        if (target == Long.class || target == long.class) return (long) Double.parseDouble(s);
        if (target == Double.class || target == double.class) return Double.parseDouble(s);
        if (target == Boolean.class || target == boolean.class) return Boolean.parseBoolean(s);
        return target.isInstance(value) ? value : null;
    }

    /** Değişen alan adları — Activity akışındaki CONFIG_CHANGED özeti için. */
    public static List<String> changedFields(String changesJson) {
        if (changesJson == null || changesJson.length() < 3) return List.of();
        List<String> out = new java.util.ArrayList<>();
        var m = java.util.regex.Pattern.compile("\"([^\"]+)\":\\{\"from\"").matcher(changesJson);
        while (m.find()) out.add(m.group(1));
        return out;
    }
}
