package com.sitemonitor.service.userref;

import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * Yanıt ağacındaki kullanıcı kimliklerini opak kimliğe çevirir (2026-10-08, "bir kullanıcı başka bir kullanıcının
 * id'sini okuyamasın"). Kural ALAN ADINA bağlı ({@link com.sitemonitor.controller.IdentityMask} ile aynı yaklaşım):
 * yük hangi derinlikte olursa olsun {@link #KEYS} anahtarlarının değeri çevrilir; yeni bir yüzey aynı adları
 * kullandıkça kendiliğinden kapsanır. Adı belirsiz alanlar ({@code AppUser.id}, dizin satırının {@code id}'si) ağaç
 * kurulurken {@link UserRef} serileştiricisiyle çevrilir.
 *
 * <p>Yalnız GLOBAL OLMAYAN görüntüleyicinin yanıtında koşar; global admin'in yanıtı hiç dokunulmadan yazılır.
 * Ağaç her istekte yeni kurulur (ya da {@code JsonNode} gövde kopyalanır) — paylaşılan/önbellekteki nesne ASLA
 * değiştirilmez.
 */
public final class UserRefWire {

    private UserRefWire() { }

    /**
     * Kullanıcı kimliği taşıyan alan adları — TEK liste. snake_case yanıt alanları + camelCase karşılıkları (denetim
     * {@code changes} diff'i ve bazı haritalar camelCase yazar). Değer sayı, rakam metni, dizi ya da
     * {@code {"from":…,"to":…}} diff nesnesi olabilir — hepsinin yaprakları çevrilir.
     */
    public static final Set<String> KEYS = Set.of(
            "user_id", "user_ids", "manager_id", "leader_id", "actor_id", "author_id", "editing_user_id",
            "contacted_user_id", "manager_user_id", "executed_by_id", "created_by_id", "undone_by_id",
            "manager_id_before", "manager_id_after", "ad_manager_id", "db_manager_id",
            "userId", "userIds", "managerId", "leaderId", "actorId", "authorId", "editingUserId",
            "contactedUserId", "managerUserId", "executedById", "createdById", "undoneById");

    /** Değeri JSON METNİ olan alanlar: denetim {@code changes / detail}, değişiklik geçmişi {@code snapshot}. */
    public static final Set<String> JSON_TEXT_KEYS = Set.of("changes", "detail", "details", "snapshot");

    private static final Pattern KEY_IN_TEXT =
            Pattern.compile("\"(" + String.join("|", KEYS) + "|resource_type|resourceType)\"");

    private static final Pattern DIGITS = Pattern.compile("^[0-9]{1,18}$");

    /** Ağaç kurulurken etkin çevirici — yalnız {@link #toOpaque} içinde dolu (depolama serileştirmesi etkilenmez). */
    private static final ThreadLocal<Function<Long, String>> CTX = new ThreadLocal<>();

    /** {@link UserRef} serileştiricisi: bağlam yoksa (global admin, depolama, test) sayı; varsa opak kimlik. */
    static void write(Long id, JsonGenerator gen) {
        if (id == null) {
            gen.writeNull();
            return;
        }
        Function<Long, String> f = CTX.get();
        if (f == null) {
            gen.writeNumber(id.longValue());
            return;
        }
        String p = f.apply(id);
        if (p == null) gen.writeNull();
        else gen.writeString(p);
    }

    /** Gövdeyi opak kimlikli YENİ bir ağaca çevirir. {@code lookup}: sayısal id → opak kimlik (yoksa null). */
    public static JsonNode toOpaque(Object body, ObjectMapper json, Function<Long, String> lookup) {
        JsonNode tree;
        if (body instanceof JsonNode n) {
            tree = n.deepCopy();
        } else {
            CTX.set(lookup);
            try {
                tree = json.valueToTree(body);
            } finally {
                CTX.remove();
            }
        }
        rewrite(tree, json, lookup);
        return tree;
    }

    /** Ağacı yerinde çevirir (yalnız taze ağaç için). */
    static void rewrite(JsonNode node, ObjectMapper json, Function<Long, String> lookup) {
        if (node == null) return;
        if (node.isArray()) {
            for (JsonNode c : node) rewrite(c, json, lookup);
            return;
        }
        if (!node.isObject()) return;
        ObjectNode obj = (ObjectNode) node;
        boolean userResource = isUserResource(obj);
        List<Map.Entry<String, JsonNode>> entries = new ArrayList<>(obj.properties());
        for (Map.Entry<String, JsonNode> e : entries) {
            String k = e.getKey();
            JsonNode v = e.getValue();
            if (KEYS.contains(k)) {
                obj.set(k, convert(v, lookup));
            } else if (userResource && ("resource_id".equals(k) || "resourceId".equals(k))) {
                obj.set(k, convert(v, lookup));
            } else if (JSON_TEXT_KEYS.contains(k) && v != null && v.isString()) {
                String before = v.stringValue();
                String after = rewriteText(before, json, lookup);
                if (!after.equals(before)) obj.put(k, after);
            } else {
                rewrite(v, json, lookup);
            }
        }
    }

    /** Denetim satırı bir KULLANICI kaynağı mı ({@code resource_type = USER}) — o zaman {@code resource_id} de kimliktir. */
    private static boolean isUserResource(ObjectNode obj) {
        JsonNode t = obj.get("resource_type");
        if (t == null) t = obj.get("resourceType");
        return t != null && t.isString() && "USER".equals(t.stringValue());
    }

    /** Bir anahtarın değeri: sayı / rakam metni → opak kimlik; dizi / nesne → yaprakları; diğerleri aynen. */
    static JsonNode convert(JsonNode v, Function<Long, String> lookup) {
        if (v == null || v.isNull()) return v;
        JsonNodeFactory nf = JsonNodeFactory.instance;
        if (v.isIntegralNumber() && v.canConvertToLong()) return opaque(v.longValue(), lookup);
        if (v.isString()) {
            String s = v.stringValue().trim();
            return DIGITS.matcher(s).matches() ? opaque(Long.parseLong(s), lookup) : v;
        }
        if (v.isArray()) {
            ArrayNode out = nf.arrayNode();
            for (JsonNode c : v) out.add(convert(c, lookup));
            return out;
        }
        if (v.isObject()) {
            ObjectNode out = nf.objectNode();
            for (Map.Entry<String, JsonNode> e : v.properties()) out.set(e.getKey(), convert(e.getValue(), lookup));
            return out;
        }
        return v;
    }

    private static JsonNode opaque(long id, Function<Long, String> lookup) {
        String p = lookup.apply(id);
        return p == null ? JsonNodeFactory.instance.nullNode() : JsonNodeFactory.instance.stringNode(p);
    }

    /**
     * Elle yazılan çıktılar (denetim CSV/JSON dışa aktarımı) için: {@code resource_type = USER} satırının
     * {@code resource_id}'si opak kimliğe; diğer türler aynen.
     */
    public static String opaqueResourceId(String resourceType, String resourceId, Function<Long, String> lookup) {
        if (resourceId == null || !"USER".equals(resourceType)) return resourceId;
        String s = resourceId.trim();
        if (!DIGITS.matcher(s).matches()) return resourceId;
        return lookup.apply(Long.parseLong(s));
    }

    /** JSON metni içindeki kimlikler; metin JSON değilse ya da ayrıştırılamazsa aynen döner. */
    public static String rewriteText(String text, ObjectMapper json, Function<Long, String> lookup) {
        if (text == null) return null;
        String t = text.stripLeading();
        if (t.isEmpty() || (t.charAt(0) != '{' && t.charAt(0) != '[')) return text;
        if (!KEY_IN_TEXT.matcher(text).find()) return text;
        try {
            JsonNode tree = json.readTree(text);
            rewrite(tree, json, lookup);
            return json.writeValueAsString(tree);
        } catch (RuntimeException e) {
            return text;
        }
    }
}
