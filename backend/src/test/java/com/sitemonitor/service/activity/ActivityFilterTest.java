package com.sitemonitor.service.activity;

import com.sitemonitor.config.GlobalExceptionHandler.FieldValidationException;
import com.sitemonitor.service.activity.ActivityFilter.Dim;
import com.sitemonitor.service.activity.ActivityFilter.Params;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link ActivityFilter} — aktivite listesi / özeti / dışa aktarımının TEK süzgeç kuralı (2026-11-05, E): doğrulama
 * (400 alan adlarıyla), serbest ISO zaman, grup eşlemesi ve SQL parçasının boyut hariç tutma kuralı.
 */
class ActivityFilterTest {

    private static Params p(String type, Long monitorId, String monitor, String status, String from, String to,
                            Long teamId, String actor, String group, String reason, String q) {
        return new Params(type, monitorId, monitor, status, from, to, teamId, actor, group, reason, q);
    }

    private static ActivityFilter parse(Params params) {
        return ActivityFilter.parse(params, false, List.of());
    }

    private static String fieldOf(Runnable r) {
        try {
            r.run();
        } catch (FieldValidationException e) {
            return e.getFields().keySet().iterator().next();
        }
        throw new AssertionError("400 bekleniyordu");
    }

    @Test
    @DisplayName("eski parametreler birebir: tek durum, tür CSV (büyük harfe), bölgesiz UTC zaman, q → %…% (küçük harf)")
    void legacyParamsUnchanged() {
        ActivityFilter f = parse(Params.legacy("http, cert", 7L, "error", "2026-11-05T10:00:00", "2026-11-05T11:00:00", "Ödeme"));
        assertThat(f.types()).containsExactly("HTTP", "CERT");
        assertThat(f.monitorId()).isEqualTo(7L);
        assertThat(f.statuses()).containsExactly("ERROR");
        assertThat(f.from()).isEqualTo("2026-11-05T10:00:00");
        assertThat(f.to()).isEqualTo("2026-11-05T11:00:00");
        assertThat(f.q()).isEqualTo("%ödeme%");
        assertThat(f.hasFilters()).isTrue();
        assertThat(parse(Params.legacy(null, null, null, null, null, null)).hasFilters()).isFalse();
    }

    @Test
    @DisplayName("durum CSV (TIMEOUT dahil), yinelenen değer tekleşir; bilinmeyen durum reddedilmez (eskisi gibi eşleşmez)")
    void statusCsv() {
        assertThat(parse(p(null, null, null, "ERROR,timeout, ERROR", null, null, null, null, null, null, null)).statuses())
                .containsExactly("ERROR", "TIMEOUT");
        assertThat(parse(p(null, null, null, "FOO", null, null, null, null, null, null, null)).statuses()).containsExactly("FOO");
        assertThat(fieldOf(() -> parse(p(null, null, null, "ERR OR", null, null, null, null, null, null, null)))).isEqualTo("status");
        String eleven = String.join(",", "A,B,C,D,E,F,G,H,I,J,K".split(","));
        assertThat(fieldOf(() -> parse(p(null, null, null, eleven, null, null, null, null, null, null, null)))).isEqualTo("status");
    }

    @Test
    @DisplayName("serbest ISO: Z / ofset UTC'ye çevrilir, kesir atılır, yalnız tarih İSTANBUL günüdür; çözülemeyen → 400 alanıyla")
    void freeIsoTimes() {
        assertThat(ActivityFilter.normalizeTime("2026-11-05T13:00:00+03:00", false, "from")).isEqualTo("2026-11-05T10:00:00");
        assertThat(ActivityFilter.normalizeTime("2026-11-05T10:00:00Z", false, "from")).isEqualTo("2026-11-05T10:00:00");
        assertThat(ActivityFilter.normalizeTime("2026-11-05T10:00:00.987Z", false, "from")).isEqualTo("2026-11-05T10:00:00");
        assertThat(ActivityFilter.normalizeTime("2026-11-05T07:00:00-03:00", false, "from")).isEqualTo("2026-11-05T10:00:00");
        assertThat(ActivityFilter.normalizeTime("2026-11-05T10:00", false, "from")).isEqualTo("2026-11-05T10:00:00");
        // İstanbul 5 Kasım 00:00 = UTC 4 Kasım 21:00; gün sonu 23:59:59 = UTC 20:59:59
        assertThat(ActivityFilter.normalizeTime("2026-11-05", false, "from")).isEqualTo("2026-11-04T21:00:00");
        assertThat(ActivityFilter.normalizeTime("2026-11-05", true, "to")).isEqualTo("2026-11-05T20:59:59");
        assertThat(ActivityFilter.normalizeTime("  ", false, "from")).isNull();
        assertThat(fieldOf(() -> ActivityFilter.normalizeTime("dün", false, "from"))).isEqualTo("from");
        assertThat(fieldOf(() -> ActivityFilter.normalizeTime("2026-13-45T99:00:00", true, "to"))).isEqualTo("to");
        assertThat(fieldOf(() -> ActivityFilter.normalizeTime("2026-11-05T10:00:00Z".repeat(3), false, "from"))).isEqualTo("from");
    }

    @Test
    @DisplayName("ters aralık → 400 alan from; eşit uçlar geçerli")
    void invertedRange() {
        assertThat(fieldOf(() -> parse(p(null, null, null, null, "2026-11-05T11:00:00", "2026-11-05T10:00:00Z",
                null, null, null, null, null)))).isEqualTo("from");
        ActivityFilter ok = parse(p(null, null, null, null, "2026-11-05T10:00:00", "2026-11-05T10:00:00", null, null, null, null, null));
        assertThat(ok.from()).isEqualTo(ok.to());
    }

    @Test
    @DisplayName("monitor=TÜR:kimlik → tür + kimlik (monitorId'yi ezer); biçim dışı → 400 alan monitor")
    void monitorParam() {
        ActivityFilter f = parse(p(null, 9L, "http:42", null, null, null, null, null, null, null, null));
        assertThat(f.monitorType()).isEqualTo("HTTP");
        assertThat(f.monitorId()).isEqualTo(42L);
        for (String bad : List.of("HTTP", "HTTP:", ":42", "HTTP:-1", "HT TP:1", "HTTP:42:1", "X".repeat(41) + ":1")) {
            assertThat(fieldOf(() -> parse(p(null, null, bad, null, null, null, null, null, null, null, null)))).as(bad).isEqualTo("monitor");
        }
    }

    @Test
    @DisplayName("grup CSV: bilinen beş grup (büyük/küçük harf duyarsız); bilinmeyen → 400 alan group")
    void groups() {
        assertThat(parse(p(null, null, null, null, null, null, null, null, "Check,alarm,check", null, null)).groups())
                .containsExactly("check", "alarm");
        assertThat(fieldOf(() -> parse(p(null, null, null, null, null, null, null, null, "checks", null, null)))).isEqualTo("group");
    }

    @Test
    @DisplayName("neden CSV: kod ve class:<SINIF> ayrı listelere; biçim dışı → 400 alan reason")
    void reasons() {
        ActivityFilter f = parse(p(null, null, null, null, null, null, null, null, null, "dns_nxdomain,class:network,CLASS:Dns", null));
        assertThat(f.reasons()).containsExactly("DNS_NXDOMAIN");
        assertThat(f.reasonClasses()).containsExactly("NETWORK", "DNS");
        assertThat(fieldOf(() -> parse(p(null, null, null, null, null, null, null, null, null, "DNS-X", null)))).isEqualTo("reason");
        assertThat(fieldOf(() -> parse(p(null, null, null, null, null, null, null, null, null, "class:", null)))).isEqualTo("reason");
        // tavan (inceleme ön yüz D8): seçilebilir katalog ~60 ve büyüyor → 100 kabul, 101 → 400
        StringBuilder hundred = new StringBuilder();
        for (int i = 0; i < ActivityFilter.MAX_REASONS; i++) hundred.append(i == 0 ? "" : ",").append("R").append(i);
        assertThat(ActivityFilter.MAX_REASONS).isEqualTo(100);
        assertThat(parse(p(null, null, null, null, null, null, null, null, null, hundred.toString(), null)).reasons()).hasSize(100);
        String more = hundred + ",R100";
        assertThat(fieldOf(() -> parse(p(null, null, null, null, null, null, null, null, null, more, null)))).isEqualTo("reason");
    }

    @Test
    @DisplayName("kişi küçük harfe (tam eşleşme); uzun kişi / uzun arama → 400")
    void actorAndQ() {
        assertThat(parse(p(null, null, null, null, null, null, null, "  Ahmet.Y ", null, null, null)).actor()).isEqualTo("ahmet.y");
        assertThat(fieldOf(() -> parse(p(null, null, null, null, null, null, null, "a".repeat(101), null, null, null)))).isEqualTo("actor");
        assertThat(fieldOf(() -> parse(p(null, null, null, null, null, null, null, null, null, null, "q".repeat(201))))).isEqualTo("q");
    }

    @Test
    @DisplayName("SQL: kapsam + teamId kesişir; boyut hariç tutma yalnız tür / durum / grup koşulunu düşürür")
    void whereExclusion() {
        ActivityFilter f = ActivityFilter.parse(p("HTTP", null, null, "ERROR,TIMEOUT", "2026-11-05T00:00:00", null, 3L,
                "u", "alarm", "DNS_NXDOMAIN", "x"), true, Arrays.asList(1L, null, 3L));
        assertThat(f.scope()).containsExactly(1L, 3L);
        ActivityFilter.Sql all = f.where();
        assertThat(all.where()).contains("team_id IN (?, ?)").contains("team_id = ?").contains("monitor_type IN (?)")
                .contains("result_status IN (?, ?)").contains("activity_time >= ?").contains("LOWER(actor) = ?")
                .contains("action LIKE 'ALERT!_%' ESCAPE '!'").contains("failure_reason IN (?)").contains("LOWER(target) LIKE ?");
        assertThat(all.args()).containsExactly(1L, 3L, 3L, "HTTP", "ERROR", "TIMEOUT", "2026-11-05T00:00:00", "u",
                "DNS_NXDOMAIN", "%x%", "%x%", "%x%");
        ActivityFilter.Sql noTypeGroup = f.where(EnumSet.of(Dim.TYPE, Dim.GROUP));
        assertThat(noTypeGroup.where()).doesNotContain("monitor_type IN").doesNotContain("ALERT!_").contains("result_status IN");
        ActivityFilter.Sql noStatus = f.where(EnumSet.of(Dim.STATUS));
        assertThat(noStatus.where()).doesNotContain("result_status").contains("monitor_type IN");
        ActivityFilter.Sql more = all.and("id < ?", 5L);
        assertThat(more.where()).endsWith(" AND id < ?");
        assertThat(more.args()).endsWith(5L);
    }

    @Test
    @DisplayName("kapsamlı ama boş kapsam → '1 = 0' (asla 'IN ()'); hem kod hem sınıf → VEYA")
    void emptyScopeAndReasonOr() {
        ActivityFilter f = ActivityFilter.parse(p(null, null, null, null, null, null, null, null, null,
                "DNS_NXDOMAIN,class:NETWORK", null), true, List.of());
        assertThat(f.where().where()).contains("1 = 0").doesNotContain("IN ()")
                .contains("(failure_reason IN (?) OR (failure_reason IS NULL AND error_class IN (?)))");
        ActivityFilter onlyClass = parse(p(null, null, null, null, null, null, null, null, null, "class:DNS", null));
        assertThat(onlyClass.where().where()).contains("(failure_reason IS NULL AND error_class IN (?))")
                .doesNotContain("failure_reason IN");
    }

    @Test
    @DisplayName("eylem → grup: kontrol, alarm (ALERT_*), bildirim (NOC_* + EXPIRY_REMINDER), değişiklik (+ RENEWAL_*), diğer")
    void groupOf() {
        assertThat(ActivityFilter.groupOf("SCHEDULED_CHECK")).isEqualTo("check");
        assertThat(ActivityFilter.groupOf("MANUAL_CHECK")).isEqualTo("check");
        assertThat(ActivityFilter.groupOf("ALERT_TRIGGERED")).isEqualTo("alarm");
        assertThat(ActivityFilter.groupOf("ALERT_ACKNOWLEDGED")).isEqualTo("alarm");
        assertThat(ActivityFilter.groupOf("NOC_CALL_LOGGED")).isEqualTo("notification");
        assertThat(ActivityFilter.groupOf("EXPIRY_REMINDER")).isEqualTo("notification");
        assertThat(ActivityFilter.groupOf("CONFIG_CHANGED")).isEqualTo("change");
        assertThat(ActivityFilter.groupOf("PAUSED")).isEqualTo("change");
        assertThat(ActivityFilter.groupOf("RENEWAL_PLANNED")).isEqualTo("change");
        assertThat(ActivityFilter.groupOf("TLS_GRADE_DROPPED")).isEqualTo("other");
        assertThat(ActivityFilter.groupOf("ALERTX")).isEqualTo("other");
        assertThat(ActivityFilter.groupOf(null)).isEqualTo("other");
        assertThat(ActivityFilter.GROUPS).containsExactly("check", "alarm", "notification", "change", "other");
    }
}
