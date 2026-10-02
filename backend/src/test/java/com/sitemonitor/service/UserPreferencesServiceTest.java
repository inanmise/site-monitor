package com.sitemonitor.service;

import com.sitemonitor.model.UserPreference;
import com.sitemonitor.repository.UserPreferenceRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Kişisel tercihler (öneri 23): beyaz liste (bilinmeyen anahtar 400), birleştirme anlamı (üst düzey değiştirir,
 * {@code local} girdi bazında birleşir), sınırlar (favori 200, liste başına 50 görünüm, belge 64 KB) ve şema yaması.
 * Depo bellekte taklit edilir — kayıt/okuma sırası gerçek davranışla aynı (findById → save).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class UserPreferencesServiceTest {

    @Mock UserPreferenceRepository repo;
    UserPreferencesService service;
    final Map<Long, UserPreference> db = new HashMap<>();

    @BeforeEach
    void setUp() {
        service = new UserPreferencesService(repo);
        when(repo.findById(anyLong())).thenAnswer(inv -> Optional.ofNullable(copy(db.get(inv.<Long>getArgument(0)))));
        when(repo.save(any(UserPreference.class))).thenAnswer(inv -> {
            UserPreference p = inv.getArgument(0);
            db.put(p.getUserId(), copy(p));
            return p;
        });
    }

    @AfterEach
    void reset() { RequestContextHolder.resetRequestAttributes(); }

    private static UserPreference copy(UserPreference p) {
        if (p == null) return null;
        UserPreference c = new UserPreference();
        c.setUserId(p.getUserId());
        c.setPrefs(p.getPrefs());
        c.setUpdatedAt(p.getUpdatedAt());
        return c;
    }

    private static Map<String, Object> fav(String type, Object id, String name) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", type);
        m.put("id", id);
        if (name != null) m.put("name", name);
        return m;
    }

    private static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    @Test
    @DisplayName("satır yoksa GET boş belge döner (updated_at null); ilk PUT satırı oluşturur")
    void emptyThenCreate() {
        var snap = service.get(7L);
        assertThat(snap.prefs()).isEmpty();
        assertThat(snap.updatedAt()).isNull();

        var r = service.merge(7L, map("landingTab", "monitoring"));
        assertThat(r.prefs()).containsEntry("landingTab", "monitoring");
        assertThat(r.changed()).containsExactly("landingTab");
        assertThat(r.updatedAt()).matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}Z");
        assertThat(service.get(7L).prefs()).containsEntry("landingTab", "monitoring");
        // Başka kullanıcının satırı etkilenmez
        assertThat(service.get(8L).prefs()).isEmpty();
    }

    @Test
    @DisplayName("bilinmeyen üst düzey anahtar 400 (IAE) — belge yazılmaz")
    void unknownKeyRejected() {
        assertThatThrownBy(() -> service.merge(1L, map("theme", "dark")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("theme");
        assertThatThrownBy(() -> service.merge(1L, map("userId", 99)))
                .isInstanceOf(IllegalArgumentException.class);
        verify(repo, never()).save(any());
    }

    @Test
    @DisplayName("hata metni arayüz dilinde (X-Lang: en)")
    void messageFollowsUiLanguage() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("X-Lang", "en");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(req));
        assertThatThrownBy(() -> service.merge(1L, map("bogus", 1)))
                .hasMessage("Unknown preference key: bogus");
    }

    @Test
    @DisplayName("üst düzey anahtar DEĞİŞTİRİLİR (birleşmez); null anahtarı siler; diğer anahtarlar korunur")
    void topLevelReplaceAndDelete() {
        service.merge(1L, map("favorites", List.of(fav("http", 1, "a"), fav("ping", 2, "b")), "landingTab", "http"));
        var r = service.merge(1L, map("favorites", List.of(fav("dns", 3, "c"))));
        assertThat((List<?>) r.prefs().get("favorites")).hasSize(1);
        assertThat(r.prefs()).containsEntry("landingTab", "http");

        var r2 = service.merge(1L, map("landingTab", null));
        assertThat(r2.prefs()).doesNotContainKey("landingTab").containsKey("favorites");
        var r3 = service.merge(1L, map("landingTab", ""));
        assertThat(r3.changed()).isEmpty();
    }

    @Test
    @DisplayName("favoriler: tür beyaz listesi, kimlik doğrulaması, tekilleşme, en çok 200")
    void favoritesValidation() {
        var r = service.merge(1L, map("favorites", List.of(fav("http", 5, " Ana sayfa "), fav("http", "5", "dup"), fav("domain", 6, null))));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> favs = (List<Map<String, Object>>) r.prefs().get("favorites");
        assertThat(favs).hasSize(2);
        assertThat(favs.get(0)).containsEntry("type", "http").containsEntry("id", 5L).containsEntry("name", "Ana sayfa");
        assertThat(favs.get(1)).doesNotContainKey("name");

        assertThatThrownBy(() -> service.merge(1L, map("favorites", List.of(fav("ssl", 1, null)))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.merge(1L, map("favorites", List.of(fav("http", "abc", null)))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.merge(1L, map("favorites", List.of(fav("http", 0, null)))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.merge(1L, map("favorites", "http:1")))
                .isInstanceOf(IllegalArgumentException.class);

        List<Object> many = new ArrayList<>();
        for (int i = 1; i <= UserPreferencesService.MAX_FAVORITES; i++) many.add(fav("ping", i, null));
        assertThat((List<?>) service.merge(1L, map("favorites", many)).prefs().get("favorites")).hasSize(200);
        many.add(fav("ping", 999, null));
        assertThatThrownBy(() -> service.merge(1L, map("favorites", many)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("200");
    }

    @Test
    @DisplayName("açılış sekmesi: biçim denetimi (küçük harf kimlik), boş = sil")
    void landingTabValidation() {
        assertThat(service.merge(1L, map("landingTab", "incident-history")).prefs()).containsEntry("landingTab", "incident-history");
        assertThatThrownBy(() -> service.merge(1L, map("landingTab", "<script>"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.merge(1L, map("landingTab", 5))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("kayıtlı görünümler: liste başına en çok 50, ad zorunlu/≤60, parametre adı/değeri denetlenir; boş liste düşer")
    void savedViewsValidation() {
        var view = map("name", "Kritikler", "params", map("mo_status", "down", "mo_team", "3"));
        var r = service.merge(1L, map("savedViews", map("monitoring", List.of(view), "http", List.of())));
        @SuppressWarnings("unchecked")
        Map<String, Object> sv = (Map<String, Object>) r.prefs().get("savedViews");
        assertThat(sv).containsOnlyKeys("monitoring");

        List<Object> many = new ArrayList<>();
        for (int i = 0; i <= UserPreferencesService.MAX_VIEWS_PER_LIST; i++) many.add(map("name", "v" + i, "params", map()));
        assertThatThrownBy(() -> service.merge(1L, map("savedViews", map("http", many))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("50");
        assertThatThrownBy(() -> service.merge(1L, map("savedViews", map("http", List.of(map("name", " ", "params", map()))))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.merge(1L, map("savedViews", map("http", List.of(map("name", "x".repeat(61), "params", map()))))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.merge(1L, map("savedViews", map("http", List.of(map("name", "x", "params", map("a-b", "1")))))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.merge(1L, map("savedViews", map("Bad Key", List.of()))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.merge(1L, map("savedViews", map("http", List.of(map("name", "x", "params", map("q", "y".repeat(501))))))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("local GİRDİ BAZINDA birleşir (null = sil); beyaz liste dışı anahtar 400; önekli aile kabul")
    void localMergesPerEntry() {
        service.merge(1L, map("local", map("sidebar-open", "false", "sm.pageSize.dashboard-certs", "25")));
        var r = service.merge(1L, map("local", map("sidebar-open", null, "today-panel-open", "true")));
        @SuppressWarnings("unchecked")
        Map<String, Object> local = (Map<String, Object>) r.prefs().get("local");
        assertThat(local).containsOnly(Map.entry("sm.pageSize.dashboard-certs", "25"), Map.entry("today-panel-open", "true"));
        assertThat(r.changed()).containsExactly("local");

        assertThatThrownBy(() -> service.merge(1L, map("local", map("site-monitor-theme", "dark"))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("site-monitor-theme");
        assertThatThrownBy(() -> service.merge(1L, map("local", map("sm.pageSize.", "25"))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.merge(1L, map("local", map("sidebar-open", true))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(UserPreferencesService.isLocalKey("sm.checkRun.teams.http")).isTrue();
        assertThat(UserPreferencesService.isLocalKey("sm.palette.recent")).isFalse();
        assertThat(UserPreferencesService.isLocalKey("site-monitor-remembered-user")).isFalse();
    }

    @Test
    @DisplayName("belge en çok 64 KB — aşımı 400 ve önceki belge korunur")
    void sizeCap() {
        service.merge(1L, map("landingTab", "http"));
        String big = "x".repeat(UserPreferencesService.MAX_LOCAL_VALUE);
        Map<String, Object> local = new LinkedHashMap<>();
        for (int i = 0; i < 5; i++) local.put("sm.pageSize.list" + i, big);
        assertThatThrownBy(() -> service.merge(1L, map("local", local)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("64");
        assertThat(service.get(1L).prefs()).containsOnlyKeys("landingTab");
        assertThatThrownBy(() -> service.merge(1L, map("local", map("sidebar-open", "y".repeat(UserPreferencesService.MAX_LOCAL_VALUE + 1)))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("aynı değer yeniden yazılınca değişiklik yok sayılır (yazım yok)")
    void noChangeNoWrite() {
        service.merge(1L, map("favorites", List.of(fav("http", 1, "a"))));
        String stamp = db.get(1L).getUpdatedAt();
        var r = service.merge(1L, map("favorites", List.of(fav("http", 1, "a"))));
        assertThat(r.changed()).isEmpty();
        assertThat(r.updatedAt()).isEqualTo(stamp);
        verify(repo, org.mockito.Mockito.times(1)).save(any());
    }

    @Test
    @DisplayName("bozuk / bilinmeyen anahtarlı kayıt okunurken temizlenir")
    void parseDropsUnknownKeys() {
        assertThat(UserPreferencesService.parse("{\"landingTab\":\"http\",\"evil\":1}")).containsOnlyKeys("landingTab");
        assertThat(UserPreferencesService.parse("not json")).isEmpty();
        assertThat(UserPreferencesService.parse("[1,2]")).isEmpty();
    }

    @Test
    @DisplayName("şema yaması: user_preferences tablosu applySchemaPatches'te açıkça kurulur; öksüz temizlik kuralı kayıtlı")
    void schemaPatchAndRetentionPresent() throws Exception {
        String src = Files.readString(Path.of("src", "main", "java", "com", "sitemonitor", "service", "SchedulerService.java"),
                StandardCharsets.UTF_8);
        assertThat(src).contains("CREATE TABLE IF NOT EXISTS user_preferences(")
                .contains("user_id BIGINT PRIMARY KEY");
        assertThat(com.sitemonitor.service.retention.RetentionCatalog.byId("user-preferences-orphan"))
                .hasValueSatisfying(p -> assertThat(p.table()).isEqualTo("user_preferences"));
    }
}
