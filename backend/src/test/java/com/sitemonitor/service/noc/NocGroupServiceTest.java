package com.sitemonitor.service.noc;

import com.sitemonitor.model.NocNotificationGroup;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 7/24 grup doğrulaması ve ALICI ÇÖZÜMLEME (izleme grupları → varsayılan → tüm aktif; pasif atlanır). */
class NocGroupServiceTest {

    private final NocGroupService svc = new NocGroupService(null, null);

    private static NocNotificationGroup g(long id, String name, boolean active, boolean def, String emails) {
        NocNotificationGroup x = new NocNotificationGroup();
        x.setId(id);
        x.setName(name);
        x.setActive(active);
        x.setIsDefault(def);
        x.setEmails(emails);
        return x;
    }

    private static Map<String, Object> body(Object emails) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", "  NOC Ana ");
        m.put("emails", emails);
        return m;
    }

    @Test
    @DisplayName("doğrulama: biçim, harf duyarsız tekilleştirme, boşluk/virgül ayrımı; ad kırpılır")
    void validateNormalizes() {
        NocGroupService.GroupInput in = svc.validate(body(List.of("noc@example.com", " NOC@example.com ",
                "yedek@example.com, ucuncu@example.com")));
        assertThat(in.name()).isEqualTo("NOC Ana");
        assertThat(in.emails()).containsExactly("noc@example.com", "yedek@example.com", "ucuncu@example.com");
        assertThat(in.active()).isTrue();
        assertThat(in.isDefault()).isFalse();
    }

    @Test
    @DisplayName("doğrulama: geçersiz adres, boş liste, 50'den fazla adres, adsız grup → 400")
    void validateRejects() {
        assertThatThrownBy(() -> svc.validate(body(List.of("noc@")))).hasMessageContaining("Geçersiz e-posta");
        assertThatThrownBy(() -> svc.validate(body(List.of()))).hasMessageContaining("en az bir");
        List<String> many = new ArrayList<>();
        for (int i = 0; i < 51; i++) many.add("n" + i + "@example.com");
        assertThatThrownBy(() -> svc.validate(body(many))).hasMessageContaining("en fazla 50");
        List<String> fifty = new ArrayList<>(many.subList(0, 50));
        assertThat(svc.validate(body(fifty)).emails()).hasSize(50);
        Map<String, Object> noName = body(List.of("noc@example.com"));
        noName.put("name", " ");
        assertThatThrownBy(() -> svc.validate(noName)).hasMessageContaining("adı zorunlu");
    }

    @Test
    @DisplayName("çözümleme: izlemenin seçtiği AKTİF gruplar; pasif/silinmiş seçim varsayılana düşer")
    void resolveMonitorGroups() {
        List<NocNotificationGroup> all = List.of(
                g(1, "A", true, false, "a@example.com"),
                g(2, "B", false, false, "b@example.com"),      // pasif
                g(3, "D", true, true, "d@example.com"));        // varsayılan
        var t = NocGroupService.resolveTargets("1,2", all);
        assertThat(t.source()).isEqualTo("MONITOR");
        assertThat(t.groupIds()).containsExactly(1L);
        assertThat(t.emails()).containsExactly("a@example.com");

        var onlyInactive = NocGroupService.resolveTargets("2,99", all);   // pasif + silinmiş
        assertThat(onlyInactive.source()).isEqualTo("DEFAULT");
        assertThat(onlyInactive.groupIds()).containsExactly(3L);
    }

    @Test
    @DisplayName("çözümleme: seçim yoksa varsayılanlar; varsayılan yoksa TÜM aktif gruplar; hiç aktif yoksa hedef boş")
    void resolveFallbacks() {
        List<NocNotificationGroup> withDefault = List.of(g(1, "A", true, false, "a@example.com"),
                g(3, "D", true, true, "d@example.com, D@example.com"));
        assertThat(NocGroupService.resolveTargets(null, withDefault).groupIds()).containsExactly(3L);
        assertThat(NocGroupService.resolveTargets(null, withDefault).emails()).containsExactly("d@example.com");

        List<NocNotificationGroup> noDefault = List.of(g(1, "A", true, false, "a@example.com, x@example.com"),
                g(2, "B", true, false, "b@example.com, a@example.com"), g(4, "P", false, false, "p@example.com"));
        var all = NocGroupService.resolveTargets("", noDefault);
        assertThat(all.source()).isEqualTo("ALL");
        assertThat(all.groupIds()).containsExactly(1L, 2L);
        assertThat(all.emails()).containsExactly("a@example.com", "x@example.com", "b@example.com");

        var none = NocGroupService.resolveTargets("1", List.of(g(4, "P", false, true, "p@example.com")));
        assertThat(none.any()).isFalse();
        assertThat(none.source()).isEqualTo("NONE");
    }

    @Test
    @DisplayName("adressiz (bozuk kayıt) grup kullanılamaz sayılır — seçilmiş olsa da atlanır")
    void unusableGroupSkipped() {
        List<NocNotificationGroup> all = List.of(g(1, "Bozuk", true, true, "asdf"), g(2, "B", true, false, "b@example.com"));
        var t = NocGroupService.resolveTargets("1", all);
        assertThat(t.groupIds()).containsExactly(2L);
        assertThat(NocGroupService.anyUsable(List.of(g(1, "Bozuk", true, true, "asdf")))).isFalse();
    }

    @Test
    @DisplayName("kullanım: GERÇEKTEN kullanan (7/24 açık) ve AÇIKÇA seçen izleme sayıları ayrı")
    void usageCounts() {
        List<NocNotificationGroup> all = List.of(g(1, "A", true, false, "a@example.com"), g(2, "D", true, true, "d@example.com"));
        List<NocMonitorDirectory.Row> rows = List.of(
                row(1, true, null),      // varsayılan (2) yoluyla
                row(2, true, "1"),       // açıkça 1
                row(3, false, "1"),      // 7/24 kapalı ama 1'i seçmiş → yalnız açık seçim sayılır
                row(4, true, "1,2"));
        Map<Long, int[]> u = NocGroupService.usage(rows, all);
        assertThat(u.get(1L)).containsExactly(2, 3);
        assertThat(u.get(2L)).containsExactly(2, 1);
    }

    @Test
    @DisplayName("NocGroupIds: biçim, tekillik, kaldırma ve gövde ayrıştırma (geçersiz öğe 400)")
    void groupIds() {
        assertThat(NocGroupIds.parse(" 3, 7,3,x,-1")).containsExactly(3L, 7L);
        assertThat(NocGroupIds.format(List.of(7L, 3L, 7L))).isEqualTo("7,3");
        assertThat(NocGroupIds.format(List.of())).isNull();
        assertThat(NocGroupIds.without("3,13,31", 3)).isEqualTo("13,31");
        assertThat(NocGroupIds.without("3", 3)).isNull();
        assertThat(NocGroupIds.fromBody(List.of(1, "2", 2L))).containsExactly(1L, 2L);
        assertThat(NocGroupIds.fromBody("4;5")).containsExactly(4L, 5L);
        assertThat(NocGroupIds.fromBody(null)).isEmpty();
        assertThatThrownBy(() -> NocGroupIds.fromBody(List.of("abc"))).isInstanceOf(IllegalArgumentException.class);
    }

    private static NocMonitorDirectory.Row row(long id, boolean noc, String groups) {
        return new NocMonitorDirectory.Row(NocType.PING, id, "p" + id, "h" + id, 1L, null, true, noc, groups, false);
    }
}
