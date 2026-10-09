package com.sitemonitor.service.noc;

import com.sitemonitor.model.NocNotificationGroup;
import com.sitemonitor.repository.NocNotificationGroupRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 7/24 (NOC) grupları: doğrulama, CRUD ve ALICI ÇÖZÜMLEME.
 *
 * <p><b>Çözümleme sırası</b> ({@link #resolveTargets}) — kapsam ekranı ve gönderim AYNI metodu kullanır, yani
 * "listede kapsanıyor görünen izleme e-postayı alamıyor" ayrışması yapısal olarak imkânsızdır:
 * <ol>
 *   <li>İzlemenin seçtiği gruplardan AKTİF olanlar;</li>
 *   <li>hiçbiri kalmadıysa (seçim yok / hepsi pasif ya da silinmiş) varsayılan aktif gruplar;</li>
 *   <li>hiç varsayılan yoksa TÜM aktif gruplar.</li>
 * </ol>
 * Hiç aktif grup yoksa hedef boştur → kapsam nedeni {@code NO_ACTIVE_GROUP}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NocGroupService {

    public static final int MAX_EMAILS = 50;
    public static final int MAX_NAME = 100;
    public static final int MAX_DESCRIPTION = 500;

    /** NotificationGroupService ile aynı, bilerek gevşek biçim denetimi (yazım hatasını yakalar, RFC değil). */
    private static final java.util.regex.Pattern EMAIL =
            java.util.regex.Pattern.compile("^[^\\s@,;]+@[^\\s@,;]+[.][^\\s@,;]{2,}$");

    private static final DateTimeFormatter ISO =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);

    private final NocNotificationGroupRepository repo;
    private final JdbcTemplate jdbc;

    // ── Doğrulama ────────────────────────────────────────────────────────────

    /** Doğrulanmış, normalize edilmiş grup girdisi. */
    public record GroupInput(String name, String description, List<String> emails, boolean active, boolean isDefault) {}

    /**
     * Gövde (camelCase: name, description, emails, active, isDefault) → doğrulanmış girdi.
     * Kurallar: ad zorunlu ≤100; açıklama ≤500; e-posta biçimi; harf duyarsız tekilleştirme; 1..50 adres.
     */
    public GroupInput validate(Map<String, Object> body) {
        String name = body.get("name") == null ? "" : body.get("name").toString().trim();
        if (name.isEmpty()) throw new IllegalArgumentException("Grup adı zorunludur");
        if (name.length() > MAX_NAME) throw new IllegalArgumentException("Grup adı en fazla " + MAX_NAME + " karakter olabilir");
        String desc = body.get("description") == null ? null : body.get("description").toString().trim();
        if (desc != null && desc.isEmpty()) desc = null;
        if (desc != null && desc.length() > MAX_DESCRIPTION)
            throw new IllegalArgumentException("Açıklama en fazla " + MAX_DESCRIPTION + " karakter olabilir");
        List<String> emails = normalizeEmails(body.get("emails"));
        boolean active = !(body.get("active") instanceof Boolean b) || b;
        boolean isDefault = body.get("isDefault") instanceof Boolean d && d;
        return new GroupInput(name, desc, emails, active, isDefault);
    }

    /** Dizi ya da virgüllü/noktalı virgüllü metin; boşluklar atılır; harf duyarsız tekil; 1..50. */
    static List<String> normalizeEmails(Object raw) {
        List<String> parts = new ArrayList<>();
        if (raw instanceof Collection<?> c) {
            for (Object o : c) if (o != null) parts.add(o.toString());
        } else if (raw != null) {
            parts.add(raw.toString());
        }
        List<String> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String p : parts) {
            for (String piece : p.split("[,;\\s]+")) {
                String e = piece.trim();
                if (e.isEmpty()) continue;
                // ReDoS (2026-10-09): uzunluk önce — kalıp uzun girdide karesel geri izler.
                if (e.length() > 254 || !EMAIL.matcher(e).matches()) throw new IllegalArgumentException("Geçersiz e-posta adresi: " + e);
                if (seen.add(e.toLowerCase(Locale.ROOT))) out.add(e);
            }
        }
        if (out.isEmpty()) throw new IllegalArgumentException("Grup en az bir e-posta adresi içermelidir");
        if (out.size() > MAX_EMAILS)
            throw new IllegalArgumentException("Bir grupta en fazla " + MAX_EMAILS + " adres olabilir (" + out.size() + " girildi)");
        return out;
    }

    // ── CRUD ─────────────────────────────────────────────────────────────────

    public List<NocNotificationGroup> list() {
        return repo.findAllByOrderByNameAsc();
    }

    @Transactional
    public NocNotificationGroup create(GroupInput in, String actor, String actorName) {
        if (repo.existsByNameIgnoreCase(in.name(), null))
            throw new IllegalArgumentException("'" + in.name() + "' adlı bir 7/24 grubu zaten var");
        NocNotificationGroup g = new NocNotificationGroup();
        apply(g, in, actor, actorName);
        g.setCreatedAt(g.getUpdatedAt());
        g.setCreatedBy(actor);
        return repo.save(g);
    }

    @Transactional
    public NocNotificationGroup update(NocNotificationGroup g, GroupInput in, String actor, String actorName) {
        if (repo.existsByNameIgnoreCase(in.name(), g.getId()))
            throw new IllegalArgumentException("'" + in.name() + "' adlı bir 7/24 grubu zaten var");
        apply(g, in, actor, actorName);
        return repo.save(g);
    }

    private static void apply(NocNotificationGroup g, GroupInput in, String actor, String actorName) {
        g.setName(in.name());
        g.setDescription(in.description());
        g.setEmails(String.join(", ", in.emails()));
        g.setActive(in.active());
        g.setIsDefault(in.isDefault());
        g.setUpdatedAt(ISO.format(Instant.now()));
        g.setUpdatedBy(actor);
        g.setUpdatedByName(actorName);
    }

    /**
     * Grubu siler ve bu grubu SEÇMİŞ izlemelerin listesinden kimliği çıkarır — liste boşalan izleme varsayılan
     * gruplara düşer. Silme ile temizlik TEK transaction: yarıda kalırsa izleme var olmayan bir gruba işaret
     * etmez (işaret etse de çözümleme pasif/silinmiş grubu atlıyor; bu, veriyi temiz tutar).
     *
     * @return listesi değişen izleme sayısı ({@code affected_monitors})
     */
    @Transactional
    public int deleteAndDetach(NocNotificationGroup g) {
        long id = g.getId();
        int affected = 0;
        String needle = "%" + id + "%";   // kaba ön süzgeç; kesin eşleşme aşağıda ayrıştırılarak yapılır
        for (NocType t : NocType.values()) {
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT id, noc_group_ids FROM " + t.table + " WHERE noc_group_ids LIKE ?", needle);
            for (Map<String, Object> r : rows) {
                String csv = r.get("noc_group_ids") == null ? null : r.get("noc_group_ids").toString();
                if (!NocGroupIds.parse(csv).contains(id)) continue;
                jdbc.update("UPDATE " + t.table + " SET noc_group_ids = ? WHERE id = ?",
                        NocGroupIds.without(csv, id), ((Number) r.get("id")).longValue());
                affected++;
            }
        }
        repo.delete(g);
        log.info("7/24 grubu silindi: id={} ad='{}' — {} izlemenin grup seçimi temizlendi", id, g.getName(), affected);
        return affected;
    }

    // ── Çözümleme ────────────────────────────────────────────────────────────

    /** Çözümlenmiş hedef: gruplar, tekil adresler ve hangi halkadan geldiği (MONITOR | DEFAULT | ALL | NONE). */
    public record Targets(List<NocNotificationGroup> groups, List<String> emails, String source) {
        public boolean any() { return !groups.isEmpty() && !emails.isEmpty(); }
        public List<Long> groupIds() { return groups.stream().map(NocNotificationGroup::getId).toList(); }
        public List<String> groupNames() { return groups.stream().map(NocNotificationGroup::getName).toList(); }
    }

    public Targets resolveTargets(String monitorGroupIds) {
        return resolveTargets(monitorGroupIds, repo.findAllByOrderByNameAsc());
    }

    /** Önceden yüklenmiş grup listesiyle — kapsam ekranı yüzlerce izlemeyi tek grup okumasıyla çözer. */
    public static Targets resolveTargets(String monitorGroupIds, List<NocNotificationGroup> all) {
        List<NocNotificationGroup> usable = new ArrayList<>();
        for (NocNotificationGroup g : all) if (usable(g)) usable.add(g);
        if (usable.isEmpty()) return new Targets(List.of(), List.of(), "NONE");

        List<Long> wanted = NocGroupIds.parse(monitorGroupIds);
        if (!wanted.isEmpty()) {
            Map<Long, NocNotificationGroup> byId = new HashMap<>();
            for (NocNotificationGroup g : usable) byId.put(g.getId(), g);
            List<NocNotificationGroup> picked = new ArrayList<>();
            for (Long id : wanted) if (byId.containsKey(id)) picked.add(byId.get(id));
            if (!picked.isEmpty()) return targets(picked, "MONITOR");
        }
        List<NocNotificationGroup> defaults = usable.stream().filter(g -> Boolean.TRUE.equals(g.getIsDefault())).toList();
        if (!defaults.isEmpty()) return targets(defaults, "DEFAULT");
        return targets(usable, "ALL");
    }

    /** Aktif VE gönderilebilir en az bir adresi olan grup. */
    static boolean usable(NocNotificationGroup g) {
        return g != null && Boolean.TRUE.equals(g.getActive()) && !emailsOf(g).isEmpty();
    }

    private static Targets targets(List<NocNotificationGroup> groups, String source) {
        Set<String> seen = new LinkedHashSet<>();
        List<String> emails = new ArrayList<>();
        for (NocNotificationGroup g : groups) {
            for (String e : emailsOf(g)) if (seen.add(e.toLowerCase(Locale.ROOT))) emails.add(e);
        }
        return new Targets(List.copyOf(groups), emails, source);
    }

    /** Kayıtlı adreslerden BİÇİMİ geçerli olanlar (eski/elle müdahale edilmiş kayıt gönderimi bozmasın). */
    public static List<String> emailsOf(NocNotificationGroup g) {
        if (g == null || g.getEmails() == null || g.getEmails().isBlank()) return List.of();
        List<String> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String part : g.getEmails().split(",")) {
            String e = part.trim();
            if (!e.isEmpty() && e.length() <= 254 && EMAIL.matcher(e).matches() && seen.add(e.toLowerCase(Locale.ROOT))) out.add(e);
        }
        return out;
    }

    /** En az bir kullanılabilir (aktif + adresli) grup var mı. */
    public static boolean anyUsable(List<NocNotificationGroup> all) {
        for (NocNotificationGroup g : all) if (usable(g)) return true;
        return false;
    }

    // ── API biçimleri ────────────────────────────────────────────────────────

    /** Yönetim listesi satırı. {@code revealEmails=false} → kapsamlı yönetici/denetçi adresleri GÖRMEZ. */
    public static Map<String, Object> toAdminDto(NocNotificationGroup g, boolean revealEmails,
                                                 int monitorCount, int explicitCount) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", g.getId());
        m.put("name", g.getName());
        m.put("description", g.getDescription());
        List<String> emails = emailsOf(g);
        m.put("emails", revealEmails ? emails : List.of());
        m.put("email_count", emails.size());
        m.put("emails_hidden", !revealEmails);
        m.put("active", Boolean.TRUE.equals(g.getActive()));
        m.put("is_default", Boolean.TRUE.equals(g.getIsDefault()));
        m.put("monitor_count", monitorCount);
        m.put("explicit_monitor_count", explicitCount);
        m.put("updated_at", g.getUpdatedAt());
        m.put("updated_by_name", g.getUpdatedByName());
        return m;
    }

    /** İzleme formu seçicisi — e-posta YOK. */
    public static Map<String, Object> toOptionDto(NocNotificationGroup g) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", g.getId());
        m.put("name", g.getName());
        m.put("is_default", Boolean.TRUE.equals(g.getIsDefault()));
        m.put("active", Boolean.TRUE.equals(g.getActive()));
        return m;
    }

    /**
     * Grup başına kullanım: {@code [0]} 7/24'ü AÇIK izlemelerden bu grubu GERÇEKTEN kullananlar (varsayılan ya
     * da "tümü" yoluyla gelenler dâhil), {@code [1]} grubu listesinde AÇIKÇA seçmiş izlemeler (silmede etkilenen).
     */
    public static Map<Long, int[]> usage(List<NocMonitorDirectory.Row> monitors, List<NocNotificationGroup> all) {
        Map<Long, int[]> out = new HashMap<>();
        for (NocNotificationGroup g : all) out.put(g.getId(), new int[2]);
        for (NocMonitorDirectory.Row r : monitors) {
            for (Long id : NocGroupIds.parse(r.nocGroupIds())) {
                int[] c = out.get(id);
                if (c != null) c[1]++;
            }
            if (!r.nocNotify()) continue;
            for (NocNotificationGroup g : resolveTargets(r.nocGroupIds(), all).groups()) {
                int[] c = out.get(g.getId());
                if (c != null) c[0]++;
            }
        }
        return out;
    }
}
