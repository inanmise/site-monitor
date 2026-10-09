package com.sitemonitor.service.userref;

import com.sitemonitor.model.AppUser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Opak kullanıcı kimliği — yanıt ağacı çevirisi (2026-10-08, kullanıcı kararı: "bir kullanıcı başka bir kullanıcının
 * id'sini okuyamasın"; global admin hariç herkes). Kural alan adına bağlı; adı belirsiz {@code id} yalnız
 * {@link UserRef} / {@code AppUser.id} serileştiricisiyle çevrilir; depolama serileştirmesi (bağlam yok) sayı kalır.
 */
class UserRefWireTest {

    private static final JsonMapper MAPPER = JsonMapper.builder()
            .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE).build();

    private static final String P5 = "00000000-0000-4000-8000-000000000005";
    private static final String P7 = "00000000-0000-4000-8000-000000000007";
    private static final Function<Long, String> LOOKUP = id -> id == 5L ? P5 : id == 7L ? P7 : null;

    @Test
    @DisplayName("anahtarlı alanlar opak kimliğe döner; bilinmeyen kullanıcı null; kullanıcı olmayan id'ler aynen")
    void keyedFields() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("leader_id", 7L);
        row.put("user_ids", List.of(5L, 7L));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", 99L);
        body.put("user_id", 5L);
        body.put("manager_id", "7");
        body.put("team_id", 3L);
        body.put("actor_id", 12345L);
        body.put("rows", List.of(row));
        body.put("contactedUserId", 5);

        JsonNode t = UserRefWire.toOpaque(body, MAPPER, LOOKUP);

        assertThat(t.get("id").asLong()).isEqualTo(99L);
        assertThat(t.get("user_id").stringValue()).isEqualTo(P5);
        assertThat(t.get("manager_id").stringValue()).isEqualTo(P7);
        assertThat(t.get("team_id").asLong()).isEqualTo(3L);
        assertThat(t.get("actor_id").isNull()).isTrue();
        assertThat(t.get("rows").get(0).get("leader_id").stringValue()).isEqualTo(P7);
        assertThat(t.get("rows").get(0).get("user_ids").get(0).stringValue()).isEqualTo(P5);
        assertThat(t.get("rows").get(0).get("user_ids").get(1).stringValue()).isEqualTo(P7);
        assertThat(t.get("contactedUserId").stringValue()).isEqualTo(P5);
        assertThat(t.toString()).doesNotContain("12345");
    }

    @Test
    @DisplayName("AppUser.id ve dizin satırındaki UserRef opak; public_id ayrı alan olarak sızmaz")
    void entityAndUserRef() {
        AppUser u = new AppUser();
        u.setId(5L);
        u.setUsername("ali");
        u.setManagerId(7L);
        u.setPublicId(P5);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("data", List.of(u));
        body.put("dir", List.of(Map.of("id", UserRef.of(7L), "username", "veli")));

        JsonNode t = UserRefWire.toOpaque(body, MAPPER, LOOKUP);

        JsonNode user = t.get("data").get(0);
        assertThat(user.get("id").stringValue()).isEqualTo(P5);
        assertThat(user.get("manager_id").stringValue()).isEqualTo(P7);
        assertThat(user.has("public_id")).isFalse();
        assertThat(t.get("dir").get(0).get("id").stringValue()).isEqualTo(P7);
    }

    @Test
    @DisplayName("bağlam dışında (global admin, depolama, günlük) UserRef ve AppUser.id SAYI yazılır — bugünkü hâl")
    void outsideWireContextStaysNumeric() {
        assertThat(MAPPER.writeValueAsString(Map.of("x", UserRef.of(5L)))).isEqualTo("{\"x\":5}");
        AppUser u = new AppUser();
        u.setId(5L);
        u.setUsername("ali");
        assertThat(MAPPER.valueToTree(u).get("id").asLong()).isEqualTo(5L);
        assertThat(MAPPER.writeValueAsString(Map.of("x", UserRef.of(null) == null ? "yok" : "var"))).contains("yok");
    }

    @Test
    @DisplayName("denetim satırı: USER kaynağının resource_id'si ve changes/detail JSON metnindeki kimlikler çevrilir; diğer türler aynen")
    void auditRows() {
        Map<String, Object> userRow = new LinkedHashMap<>();
        userRow.put("resource_type", "USER");
        userRow.put("resource_id", "5");
        userRow.put("changes", "{\"managerId\":{\"from\":5,\"to\":7},\"displayName\":{\"from\":\"a\",\"to\":\"b\"}}");
        userRow.put("detail", "{\"user_id\":7,\"username\":\"veli\"}");
        Map<String, Object> teamRow = new LinkedHashMap<>();
        teamRow.put("resource_type", "TEAM");
        teamRow.put("resource_id", "5");
        teamRow.put("detail", "düz metin 5");
        teamRow.put("changes", "{\"userIds\":{\"from\":[5],\"to\":[5,7]}}");

        JsonNode t = UserRefWire.toOpaque(Map.of("data", List.of(userRow, teamRow)), MAPPER, LOOKUP);

        JsonNode u = t.get("data").get(0);
        assertThat(u.get("resource_id").stringValue()).isEqualTo(P5);
        JsonNode changes = MAPPER.readTree(u.get("changes").stringValue());
        assertThat(changes.get("managerId").get("from").stringValue()).isEqualTo(P5);
        assertThat(changes.get("managerId").get("to").stringValue()).isEqualTo(P7);
        assertThat(changes.get("displayName").get("to").stringValue()).isEqualTo("b");
        assertThat(MAPPER.readTree(u.get("detail").stringValue()).get("user_id").stringValue()).isEqualTo(P7);

        JsonNode team = t.get("data").get(1);
        assertThat(team.get("resource_id").stringValue()).isEqualTo("5");
        assertThat(team.get("detail").stringValue()).isEqualTo("düz metin 5");
        JsonNode teamChanges = MAPPER.readTree(team.get("changes").stringValue());
        assertThat(teamChanges.get("userIds").get("to").get(1).stringValue()).isEqualTo(P7);
    }

    @Test
    @DisplayName("hızlı yol: çevrilecek özellik adı yoksa tek serileştirme (RawValue) ve UserRef yine opak; ad varsa tam yol")
    void fastPath() {
        Map<String, Object> plain = new LinkedHashMap<>();
        plain.put("id", UserRef.of(5L));
        plain.put("team_id", 3L);
        plain.put("note", "user_id kelimesi değerde geçebilir");   // değer içindeki ad tetiklemez
        Object out = UserRefWire.toOpaqueFast(plain, MAPPER, LOOKUP);
        assertThat(out).isInstanceOf(tools.jackson.databind.util.RawValue.class);
        JsonNode parsed = MAPPER.readTree(String.valueOf(((tools.jackson.databind.util.RawValue) out).rawValue()));
        assertThat(parsed.get("id").stringValue()).isEqualTo(P5);
        assertThat(parsed.get("team_id").asLong()).isEqualTo(3L);

        Map<String, Object> keyed = Map.of("user_id", 7L);
        Object slow = UserRefWire.toOpaqueFast(keyed, MAPPER, LOOKUP);
        assertThat(slow).isInstanceOf(JsonNode.class);
        assertThat(((JsonNode) slow).get("user_id").stringValue()).isEqualTo(P7);

        Object detail = UserRefWire.toOpaqueFast(Map.of("detail", "{\"user_id\":5}"), MAPPER, LOOKUP);
        assertThat(detail).isInstanceOf(JsonNode.class);
        assertThat(((JsonNode) detail).get("detail").stringValue()).contains(P5);
    }

    @Test
    @DisplayName("JsonNode gövde kopyalanır — paylaşılan/önbellekteki ağaç değişmez")
    void jsonNodeBodyIsCopied() {
        ObjectNode orig = MAPPER.createObjectNode().put("user_id", 5);
        JsonNode t = UserRefWire.toOpaque(orig, MAPPER, LOOKUP);
        assertThat(t.get("user_id").stringValue()).isEqualTo(P5);
        assertThat(orig.get("user_id").asLong()).isEqualTo(5L);
    }

    @Test
    @DisplayName("elle yazılan dışa aktarım yardımcıları: USER resource_id, JSON olmayan metin aynen")
    void exportHelpers() {
        assertThat(UserRefWire.opaqueResourceId("USER", "5", LOOKUP)).isEqualTo(P5);
        assertThat(UserRefWire.opaqueResourceId("USER", "bulk", LOOKUP)).isEqualTo("bulk");
        assertThat(UserRefWire.opaqueResourceId("TEAM", "5", LOOKUP)).isEqualTo("5");
        assertThat(UserRefWire.rewriteText("User not found: 5", MAPPER, LOOKUP)).isEqualTo("User not found: 5");
        assertThat(UserRefWire.rewriteText("{\"count\":5}", MAPPER, LOOKUP)).isEqualTo("{\"count\":5}");
        assertThat(UserRefWire.rewriteText("{\"user_id\":5}", MAPPER, LOOKUP)).isEqualTo("{\"user_id\":\"" + P5 + "\"}");
        assertThat(UserRefWire.rewriteText("{bozuk \"user_id\":5", MAPPER, LOOKUP)).isEqualTo("{bozuk \"user_id\":5");
    }
}
