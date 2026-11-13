package com.sitemonitor.service.sqlplayground;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitemonitor.config.GlobalExceptionHandler.CodedConflictException;
import com.sitemonitor.config.GlobalExceptionHandler.FieldValidationException;
import com.sitemonitor.model.SqlSavedQuery;
import com.sitemonitor.repository.SqlSavedQueryRepository;
import com.sitemonitor.service.SqlPlaygroundService;
import com.sitemonitor.util.Msg;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;

/**
 * SQL Playground kayıtlı sorguları (2026-11-13, D1).
 *
 * <p><b>Görünürlük.</b> Sahibi her zaman görür; {@code shared} kaydı playground'u kullanan herkes OKUR ama yalnız sahibi
 * düzenler / siler (403). Başkasının paylaşılmamış kaydı → 404 (var olduğu bile söylenmez).
 *
 * <p><b>Sınırlar.</b> Başlık ≤ {@value #TITLE_MAX} (sahibi içinde benzersiz, Türkçe küçük harfe katlanarak), açıklama ≤
 * {@value #DESCRIPTION_MAX}, ≤ {@value #TAGS_MAX} etiket × {@value #TAG_MAX}, SQL ≤ {@link SqlPlaygroundService#MAX_SQL_CHARS},
 * kişi başına ≤ {@value #PER_USER_MAX} kayıt. SQL kaydederken ÇALIŞTIRMA ile aynı doğrulamadan geçer (salt-okunur kurallar
 * + hassas kolon reddi) → 400 alan {@code sql_text}. Parametre DEĞERLERİ saklanmaz, yalnız türleri.
 *
 * <p><b>Güncelleme.</b> Yalnız gönderilen alanlar + {@code version} (eski → 409 {@code CONCURRENT_MODIFICATION}); hiçbir
 * alan değişmediyse yazmaz ({@link Saved#changed()} false — çağıran denetim yazmaz).
 */
@Service
@RequiredArgsConstructor
public class SqlSavedQueryService {

    public static final int TITLE_MAX = 120;
    public static final int DESCRIPTION_MAX = 1000;
    public static final int TAGS_MAX = 10;
    public static final int TAG_MAX = 30;
    public static final int PER_USER_MAX = 200;
    public static final int PAGE_MAX = 100;

    private static final Locale TR = Locale.forLanguageTag("tr");
    private static final ObjectMapper JSON = new ObjectMapper();

    private final SqlSavedQueryRepository repo;
    private final SqlPlaygroundService playground;

    /** Kayıt / güncelleme sonucu: satır + gerçekten değişti mi + değişen alan adları (denetim için). */
    public record Saved(SqlSavedQuery entity, boolean changed, List<String> fields) {}

    // ── Okuma ─────────────────────────────────────────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public Map<String, Object> list(String user, String scope, String q, String tag, int page, int size) {
        int sc = switch (scope == null ? "all" : scope.trim().toLowerCase(Locale.ROOT)) {
            case "mine" -> 0;
            case "shared" -> 1;
            case "", "all" -> 2;
            default -> throw new FieldValidationException("scope", Msg.t(
                    "Kapsam mine, shared ya da all olmalı.", "The scope must be mine, shared or all."));
        };
        String query = q == null ? "" : q.trim();
        if (query.length() > 200) {
            throw new FieldValidationException("q", Msg.t("Arama metni en çok 200 karakter olabilir.",
                    "The search text can be at most 200 characters."));
        }
        String t = tag == null ? "" : tag.trim();
        if (t.length() > TAG_MAX) {
            throw new FieldValidationException("tag", Msg.t("Etiket en çok " + TAG_MAX + " karakter olabilir.",
                    "A tag can be at most " + TAG_MAX + " characters."));
        }
        String like = "%" + SqlPlaygroundService.likeEscape(query.toLowerCase(Locale.ROOT)) + "%";
        String tagLike = t.isEmpty() ? "%" : "%," + SqlPlaygroundService.likeEscape(t.toLowerCase(Locale.ROOT)) + ",%";
        int sz = Math.max(1, Math.min(PAGE_MAX, size <= 0 ? 25 : size));
        int pg = Math.max(0, page);
        Page<SqlSavedQuery> p = repo.search(sc, user, like, tagLike, PageRequest.of(pg, sz,
                Sort.by(Sort.Order.desc("updatedAt"), Sort.Order.desc("id"))));
        List<Map<String, Object>> data = new ArrayList<>();
        for (SqlSavedQuery s : p.getContent()) data.add(row(s, user));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("data", data);
        out.put("total", p.getTotalElements());
        out.put("page", pg);
        out.put("size", sz);
        out.put("max_per_user", PER_USER_MAX);
        return out;
    }

    @Transactional(readOnly = true)
    public SqlSavedQuery get(long id, String user) {
        SqlSavedQuery s = repo.findById(id).orElseThrow(SqlSavedQueryService::notFound);
        if (!visible(s, user)) throw notFound();
        return s;
    }

    // ── Yazma ─────────────────────────────────────────────────────────────────────────────────────────────────

    @Transactional
    public Saved create(Map<String, Object> body, String user, String displayName) {
        String title = title(body.get("title"), true);
        String sql = sql(body.get("sql_text"), body.get("params"), true);
        String description = description(body.get("description"));
        String tags = tags(body.get("tags"));
        String params = paramsJson(body.get("params"));
        boolean shared = bool(body.get("shared"), false);
        if (repo.countByOwnerUsername(user) >= PER_USER_MAX) {
            throw new CodedConflictException("SQL_SAVED_LIMIT", Msg.t(
                    "Kişi başına en çok " + PER_USER_MAX + " kayıtlı sorgu tutulabilir. Kullanmadıklarınızı silip yeniden deneyin.",
                    "At most " + PER_USER_MAX + " saved queries are kept per person. Delete ones you don't use and try again."));
        }
        String key = titleKey(title);
        if (repo.findByOwnerUsernameAndTitleKey(user, key).isPresent()) throw titleExists();
        SqlSavedQuery s = new SqlSavedQuery();
        s.setOwnerUsername(user);
        s.setOwnerName(displayName == null || displayName.isBlank() ? user : clip(displayName, 200));
        s.setTitle(title);
        s.setTitleKey(key);
        s.setDescription(description);
        s.setTags(tags);
        s.setSqlText(sql);
        s.setParamsJson(params);
        s.setShared(shared);
        String now = Instant.now().toString();
        s.setCreatedAt(now);
        s.setUpdatedAt(now);
        try {
            s = repo.saveAndFlush(s);
        } catch (DataIntegrityViolationException e) {
            throw titleExists();   // eşzamanlı aynı başlık (benzersiz kısıt)
        }
        return new Saved(s, true, List.of("title", "sql_text", "description", "tags", "params", "shared"));
    }

    @Transactional
    public Saved update(long id, Map<String, Object> body, String user) {
        SqlSavedQuery s = repo.findById(id).orElseThrow(SqlSavedQueryService::notFound);
        if (!visible(s, user)) throw notFound();
        requireOwner(s, user);
        Object v = body.get("version");
        if (!(v instanceof Number num)) {
            throw new FieldValidationException("version", Msg.t(
                    "Kaydın sürümü (version) gönderilmeli; listeyi yenileyip yeniden deneyin.",
                    "The record's version must be sent; refresh the list and try again."));
        }
        if (!Objects.equals(s.getVersion(), num.longValue())) {
            throw new CodedConflictException("CONCURRENT_MODIFICATION", Msg.t(
                    "Bu kayıtlı sorgu siz düzenlerken değişti (başka bir sekmede ya da oturumda). Son hâlini açıp değişikliğinizi yeniden uygulayın.",
                    "This saved query changed while you were editing it (in another tab or session). Open the latest version and apply your change again."));
        }
        List<String> changed = new ArrayList<>();
        if (body.containsKey("title")) {
            String title = title(body.get("title"), true);
            if (!title.equals(s.getTitle())) {
                String key = titleKey(title);
                if (!key.equals(s.getTitleKey())) {
                    var other = repo.findByOwnerUsernameAndTitleKey(user, key);
                    if (other.isPresent() && !other.get().getId().equals(s.getId())) throw titleExists();
                }
                s.setTitle(title);
                s.setTitleKey(key);
                changed.add("title");
            }
        }
        if (body.containsKey("sql_text")) {
            String sql = sql(body.get("sql_text"), body.get("params"), true);
            if (!sql.equals(s.getSqlText())) {
                s.setSqlText(sql);
                changed.add("sql_text");
            }
        }
        if (body.containsKey("description")) {
            String d = description(body.get("description"));
            if (!Objects.equals(d, s.getDescription())) {
                s.setDescription(d);
                changed.add("description");
            }
        }
        if (body.containsKey("tags")) {
            String t = tags(body.get("tags"));
            if (!Objects.equals(t, s.getTags())) {
                s.setTags(t);
                changed.add("tags");
            }
        }
        if (body.containsKey("params")) {
            String p = paramsJson(body.get("params"));
            if (!Objects.equals(p, s.getParamsJson())) {
                s.setParamsJson(p);
                changed.add("params");
            }
        }
        if (body.containsKey("shared")) {
            boolean sh = bool(body.get("shared"), Boolean.TRUE.equals(s.getShared()));
            if (sh != Boolean.TRUE.equals(s.getShared())) {
                s.setShared(sh);
                changed.add("shared");
            }
        }
        if (changed.isEmpty()) return new Saved(s, false, List.of());
        s.setUpdatedAt(Instant.now().toString());
        try {
            s = repo.saveAndFlush(s);
        } catch (DataIntegrityViolationException e) {
            throw titleExists();
        }
        return new Saved(s, true, List.copyOf(changed));
    }

    @Transactional
    public SqlSavedQuery delete(long id, String user) {
        SqlSavedQuery s = repo.findById(id).orElseThrow(SqlSavedQueryService::notFound);
        if (!visible(s, user)) throw notFound();
        requireOwner(s, user);
        repo.delete(s);
        return s;
    }

    // ── Satır ─────────────────────────────────────────────────────────────────────────────────────────────────

    public Map<String, Object> row(SqlSavedQuery s, String user) {
        boolean mine = user != null && user.equals(s.getOwnerUsername());
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", s.getId());
        m.put("title", s.getTitle());
        m.put("description", s.getDescription());
        m.put("tags", tagList(s.getTags()));
        m.put("sql_text", s.getSqlText());
        m.put("params", paramTypes(s.getParamsJson()));
        m.put("shared", Boolean.TRUE.equals(s.getShared()));
        m.put("owner_name", s.getOwnerName());
        m.put("mine", mine);
        m.put("can_edit", mine);
        m.put("created_at", s.getCreatedAt());
        m.put("updated_at", s.getUpdatedAt());
        m.put("version", s.getVersion());
        return m;
    }

    // ── Doğrulama ─────────────────────────────────────────────────────────────────────────────────────────────

    private static boolean visible(SqlSavedQuery s, String user) {
        return (user != null && user.equals(s.getOwnerUsername())) || Boolean.TRUE.equals(s.getShared());
    }

    private static void requireOwner(SqlSavedQuery s, String user) {
        if (user == null || !user.equals(s.getOwnerUsername())) {
            throw new SecurityException(Msg.t(
                    "Bu kayıtlı sorgu başka birine ait; yalnız sahibi düzenleyebilir ya da silebilir. Kopyalayıp kendi kaydınız olarak saklayabilirsiniz.",
                    "This saved query belongs to someone else; only its owner can edit or delete it. You can copy it and save it as your own."));
        }
    }

    private static NoSuchElementException notFound() {
        return new NoSuchElementException(Msg.t(
                "Kayıtlı sorgu bulunamadı; silinmiş ya da paylaşımdan kaldırılmış olabilir.",
                "The saved query wasn't found; it may have been deleted or unshared."));
    }

    private static CodedConflictException titleExists() {
        return new CodedConflictException("SQL_SAVED_TITLE_EXISTS", Msg.t(
                "Bu başlıkla kayıtlı bir sorgunuz zaten var. Başka bir başlık seçin.",
                "You already have a saved query with this title. Choose another title."), Map.of("field", "title"));
    }

    static String titleKey(String title) {
        return collapse(title).toLowerCase(TR);
    }

    private static String title(Object raw, boolean required) {
        String t = raw == null ? "" : collapse(String.valueOf(raw));
        if (t.isEmpty() && required) {
            throw new FieldValidationException("title", Msg.t("Başlık gerekli.", "A title is required."));
        }
        if (t.length() > TITLE_MAX) {
            throw new FieldValidationException("title", Msg.t(
                    "Başlık en çok " + TITLE_MAX + " karakter olabilir.", "The title can be at most " + TITLE_MAX + " characters."));
        }
        return t;
    }

    private String sql(Object raw, Object params, boolean required) {
        String s = raw == null ? "" : String.valueOf(raw);
        if (s.isBlank()) {
            throw new FieldValidationException("sql_text", Msg.t("SQL metni gerekli.", "The SQL text is required."));
        }
        if (s.length() > SqlPlaygroundService.MAX_SQL_CHARS) {
            throw new FieldValidationException("sql_text", Msg.t(
                    "SQL en çok " + SqlPlaygroundService.MAX_SQL_CHARS + " karakter olabilir.",
                    "The SQL can be at most " + SqlPlaygroundService.MAX_SQL_CHARS + " characters."));
        }
        try {
            playground.prepare(s, Map.of(), false);
        } catch (SensitiveColumnException e) {
            throw new FieldValidationException(SensitiveColumnException.CODE, "sql_text", e.getMessage());
        } catch (FieldValidationException e) {
            throw new FieldValidationException(e.getCode(), "sql_text", e.getMessage());
        } catch (IllegalArgumentException e) {
            throw new FieldValidationException("sql_text", e.getMessage());
        }
        return s;
    }

    private static String description(Object raw) {
        if (raw == null) return null;
        String d = String.valueOf(raw).trim();
        if (d.isEmpty()) return null;
        if (d.length() > DESCRIPTION_MAX) {
            throw new FieldValidationException("description", Msg.t(
                    "Açıklama en çok " + DESCRIPTION_MAX + " karakter olabilir.",
                    "The description can be at most " + DESCRIPTION_MAX + " characters."));
        }
        return d;
    }

    /** Etiketler: dizi ya da virgüllü metin → ",a,b," (büyük/küçük harf duyarsız tekil); boş → null. */
    static String tags(Object raw) {
        if (raw == null) return null;
        List<String> parts = new ArrayList<>();
        if (raw instanceof List<?> l) {
            for (Object o : l) if (o != null) parts.add(String.valueOf(o));
        } else {
            for (String p : String.valueOf(raw).split(",")) parts.add(p);
        }
        Set<String> seen = new LinkedHashSet<>();
        List<String> out = new ArrayList<>();
        for (String p : parts) {
            String t = collapse(p);
            if (t.isEmpty()) continue;
            if (t.indexOf(',') >= 0 || t.length() > TAG_MAX) {
                throw new FieldValidationException("tags", Msg.t(
                        "Her etiket en çok " + TAG_MAX + " karakter olabilir ve virgül içeremez.",
                        "Each tag can be at most " + TAG_MAX + " characters and can't contain a comma."));
            }
            if (seen.add(t.toLowerCase(TR))) out.add(t);
        }
        if (out.size() > TAGS_MAX) {
            throw new FieldValidationException("tags", Msg.t(
                    "En çok " + TAGS_MAX + " etiket eklenebilir.", "At most " + TAGS_MAX + " tags can be added."));
        }
        return out.isEmpty() ? null : "," + String.join(",", out) + ",";
    }

    static List<String> tagList(String stored) {
        List<String> out = new ArrayList<>();
        if (stored == null) return out;
        for (String p : stored.split(",")) if (!p.isBlank()) out.add(p);
        return out;
    }

    /** Parametre türleri: {@code {ad: {type}}} ya da {@code {ad: "tür"}} → {@code {"ad":"tür"}} JSON; DEĞER saklanmaz. */
    static String paramsJson(Object raw) {
        if (!(raw instanceof Map<?, ?> m) || m.isEmpty()) return null;
        Map<String, String> types = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : m.entrySet()) {
            if (types.size() >= SqlParams.MAX_PARAMS) break;
            String name = String.valueOf(e.getKey());
            if (!SqlParams.isValidName(name, 0, name.length())) continue;
            Object v = e.getValue();
            String type = v instanceof Map<?, ?> pm && pm.get("type") != null ? String.valueOf(pm.get("type"))
                    : v instanceof String s ? s : "text";
            type = type.trim().toLowerCase(Locale.ROOT);
            types.put(name, SqlParams.TYPES.contains(type) ? type : "text");
        }
        if (types.isEmpty()) return null;
        try {
            return JSON.writeValueAsString(types);
        } catch (Exception e) {
            return null;
        }
    }

    /** Saklanan tür JSON'u → {@code {ad: {type}}}; bozuksa boş. */
    static Map<String, Object> paramTypes(String json) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (json == null || json.isBlank()) return out;
        try {
            Map<?, ?> m = JSON.readValue(json, Map.class);
            for (Map.Entry<?, ?> e : m.entrySet()) out.put(String.valueOf(e.getKey()), Map.of("type", String.valueOf(e.getValue())));
        } catch (Exception ignored) {
            // bozuk eski kayıt — türsüz döner
        }
        return out;
    }

    private static boolean bool(Object raw, boolean def) {
        if (raw instanceof Boolean b) return b;
        if (raw instanceof String s) return "true".equalsIgnoreCase(s.trim());
        return def;
    }

    private static String collapse(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length());
        boolean space = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isWhitespace(c) || Character.isSpaceChar(c)) {
                space = true;
                continue;
            }
            if (space && sb.length() > 0) sb.append(' ');
            space = false;
            sb.append(c);
        }
        return sb.toString();
    }

    private static String clip(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }
}
