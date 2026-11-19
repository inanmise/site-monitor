package com.sitemonitor.service.monitorbulk;

import com.sitemonitor.config.GlobalExceptionHandler.FieldValidationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Genişletilmiş toplu düzenleme (2026-11-19) — saf kurallar: istek ayrıştırma, aralık sınırları, etiket birleştirme. */
class MonitorBulkEditTest {

    private static Map<String, Object> body(Object... kv) {
        Map<String, Object> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    private static String fieldOf(Runnable r) {
        try {
            r.run();
        } catch (FieldValidationException e) {
            return e.getFields().keySet().iterator().next();
        }
        return null;
    }

    @Test
    @DisplayName("ayrıştırma: tür / eylem / kimlikler doğrulanır; tekrar eden kimlik bir kez; 200 sınırı")
    void parseBasics() {
        var r = MonitorBulkEdit.parse(body("type", "HTTP", "action", "interval", "ids", List.of(3, 3, 5), "interval_seconds", 600));
        assertThat(r.type()).isEqualTo("http");
        assertThat(r.ids()).containsExactly(3L, 5L);
        assertThat(r.intervalSeconds()).isEqualTo(600);
        assertThat(fieldOf(() -> MonitorBulkEdit.parse(body("type", "x", "action", "interval", "ids", List.of(1))))).isEqualTo("type");
        assertThat(fieldOf(() -> MonitorBulkEdit.parse(body("type", "http", "action", "delete", "ids", List.of(1))))).isEqualTo("action");
        assertThat(fieldOf(() -> MonitorBulkEdit.parse(body("type", "http", "action", "interval", "ids", List.of())))).isEqualTo("ids");
        assertThat(fieldOf(() -> MonitorBulkEdit.parse(body("type", "http", "action", "interval", "ids", List.of(-1))))).isEqualTo("ids");
        List<Integer> many = new ArrayList<>();
        for (int i = 1; i <= MonitorBulkEdit.MAX_ITEMS + 1; i++) many.add(i);
        assertThat(fieldOf(() -> MonitorBulkEdit.parse(body("type", "http", "action", "interval", "ids", many, "interval_seconds", 600))))
                .isEqualTo("ids");
        assertThatThrownBy(() -> MonitorBulkEdit.parse(null)).isInstanceOf(FieldValidationException.class);
        // çok uzun ham liste (tekrarlarla bile) ayrıştırılmadan reddedilir
        List<Integer> dupes = new ArrayList<>();
        for (int i = 0; i <= MonitorBulkEdit.RAW_LIST_MAX; i++) dupes.add(1);
        assertThat(fieldOf(() -> MonitorBulkEdit.parse(body("type", "http", "action", "interval", "ids", dupes, "interval_seconds", 600))))
                .isEqualTo("ids");
        List<String> blanks = new ArrayList<>();
        for (int i = 0; i <= MonitorBulkEdit.RAW_LIST_MAX; i++) blanks.add("");
        assertThat(fieldOf(() -> MonitorBulkEdit.parse(body("type", "http", "action", "tags", "ids", List.of(1), "tags_add", blanks))))
                .isEqualTo("tags_add");
    }

    @Test
    @DisplayName("aralık: tür başına alt / üst sınır; ondalık ya da metin reddedilir")
    void intervalRanges() {
        assertThat(fieldOf(() -> MonitorBulkEdit.parse(body("type", "domain", "action", "interval", "ids", List.of(1), "interval_seconds", 60))))
                .isEqualTo("interval_seconds");
        assertThat(MonitorBulkEdit.parse(body("type", "domain", "action", "interval", "ids", List.of(1), "interval_seconds", 3600)).intervalSeconds())
                .isEqualTo(3600);
        assertThat(fieldOf(() -> MonitorBulkEdit.parse(body("type", "pagespeed", "action", "interval", "ids", List.of(1), "interval_seconds", 60))))
                .isEqualTo("interval_seconds");
        assertThat(fieldOf(() -> MonitorBulkEdit.parse(body("type", "http", "action", "interval", "ids", List.of(1), "interval_seconds", 172800))))
                .isEqualTo("interval_seconds");
        assertThat(MonitorBulkEdit.parse(body("type", "dbquery", "action", "interval", "ids", List.of(1), "interval_seconds", 172800)).intervalSeconds())
                .isEqualTo(172800);
        assertThat(fieldOf(() -> MonitorBulkEdit.parse(body("type", "http", "action", "interval", "ids", List.of(1), "interval_seconds", 60.5))))
                .isEqualTo("interval_seconds");
        assertThat(fieldOf(() -> MonitorBulkEdit.parse(body("type", "http", "action", "interval", "ids", List.of(1), "interval_seconds", "60"))))
                .isEqualTo("interval_seconds");
        for (String t : MonitorBulkEdit.TYPES) {
            assertThat(MonitorBulkEdit.minInterval(t)).isPositive();
            assertThat(MonitorBulkEdit.maxInterval(t)).isGreaterThan(MonitorBulkEdit.minInterval(t));
        }
    }

    @Test
    @DisplayName("seviye ve kanallar: yalnız üç seviye; kanalda en az bir değişiklik; grup 'set' kimlik ister")
    void levelAndChannels() {
        assertThat(MonitorBulkEdit.parse(body("type", "ping", "action", "alert_level", "ids", List.of(1), "alert_level", "high")).level())
                .isEqualTo("HIGH");
        assertThat(fieldOf(() -> MonitorBulkEdit.parse(body("type", "ping", "action", "alert_level", "ids", List.of(1), "alert_level", "LOW"))))
                .isEqualTo("alert_level");
        assertThat(fieldOf(() -> MonitorBulkEdit.parse(body("type", "ping", "action", "channels", "ids", List.of(1))))).isEqualTo("channels");
        assertThat(fieldOf(() -> MonitorBulkEdit.parse(body("type", "ping", "action", "channels", "ids", List.of(1), "notify_email", "yes"))))
                .isEqualTo("notify_email");
        assertThat(fieldOf(() -> MonitorBulkEdit.parse(body("type", "ping", "action", "channels", "ids", List.of(1), "group_mode", "set"))))
                .isEqualTo("notification_group_id");
        assertThat(fieldOf(() -> MonitorBulkEdit.parse(body("type", "ping", "action", "channels", "ids", List.of(1), "group_mode", "bogus"))))
                .isEqualTo("notification_group_id");
        var r = MonitorBulkEdit.parse(body("type", "ping", "action", "channels", "ids", List.of(1), "notify_email", false,
                "group_mode", "set", "notification_group_id", 7));
        assertThat(r.notifyEmail()).isFalse();
        assertThat(r.notifyWebhook()).isNull();
        assertThat(r.groupId()).isEqualTo(7L);
        assertThat(r.routingAction()).isTrue();
        assertThat(MonitorBulkEdit.parse(body("type", "ping", "action", "channels", "ids", List.of(1), "group_mode", "default")).groupMode())
                .isEqualTo("default");
    }

    @Test
    @DisplayName("etiket isteği: boş eklenip çıkarılamaz, virgül / uzunluk sınırı, aynı etiket iki yanda olamaz")
    void tagRequest() {
        assertThat(fieldOf(() -> MonitorBulkEdit.parse(body("type", "http", "action", "tags", "ids", List.of(1))))).isEqualTo("tags_add");
        assertThat(fieldOf(() -> MonitorBulkEdit.parse(body("type", "http", "action", "tags", "ids", List.of(1), "tags_add", List.of("a,b")))))
                .isEqualTo("tags_add");
        assertThat(fieldOf(() -> MonitorBulkEdit.parse(body("type", "http", "action", "tags", "ids", List.of(1), "tags_add", List.of("x".repeat(51))))))
                .isEqualTo("tags_add");
        assertThat(fieldOf(() -> MonitorBulkEdit.parse(body("type", "http", "action", "tags", "ids", List.of(1),
                "tags_add", List.of("prod"), "tags_remove", List.of("PROD"))))).isEqualTo("tags_remove");
        var r = MonitorBulkEdit.parse(body("type", "http", "action", "tags", "ids", List.of(1), "tags_add", List.of(" prod ", "Prod", ""),
                "tags_remove", List.of("old")));
        assertThat(r.tagsAdd()).containsExactly("prod");
        assertThat(r.tagsRemove()).containsExactly("old");
        assertThat(r.routingAction()).isFalse();
    }

    @Test
    @DisplayName("etiket birleştirme: sıra korunur, yeni sona, büyük/küçük harf duyarsız; aynı küme karşılaştırması")
    void tagMerge() {
        List<String> cur = MonitorBulkEdit.tagsOf(" web, Prod ,web,, api ");
        assertThat(cur).containsExactly("web", "Prod", "api");
        assertThat(MonitorBulkEdit.mergeTags(cur, List.of("PROD", "new"), List.of("API")))
                .containsExactly("web", "Prod", "new");
        assertThat(MonitorBulkEdit.sameTags(cur, List.of("API", "prod", "WEB"))).isTrue();
        assertThat(MonitorBulkEdit.sameTags(cur, List.of("web"))).isFalse();
        assertThat(MonitorBulkEdit.mergeTags(List.of("a"), List.of(), List.of("A"))).isEmpty();
        assertThat(MonitorBulkEdit.joinTags(List.of("a", "b"))).isEqualTo("a,b");
        assertThat(MonitorBulkEdit.tagsOf(null)).isEmpty();
    }
}
