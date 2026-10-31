package com.sitemonitor.service;

import com.sitemonitor.service.dbquery.DbQueryAlarmText;
import com.sitemonitor.service.dbquery.DbQueryAlertRowsService;
import com.sitemonitor.service.dbquery.DbQueryEvaluator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Veritabanı sorgu alarmı e-postası (2026-10-31, kullanıcı isteği: "ne bekleniyordu, ne geldi, ne çalıştı, sorgu ne zaman
 * çalıştı, kaç satır döndü, satırlar kolon bazlı tablo") — HTML ve düz metin aynı bilgi; satırlar yalnız gönderilen postada,
 * bildirim günlüğü kopyasında yalnız sayı.
 */
class DbQueryAlarmMailTest {

    EmailTemplateBuilder b;

    @BeforeEach
    void setUp() {
        AppSettingsService app = mock(AppSettingsService.class);
        when(app.getString(eq("site.monitor.app.base-url"), any())).thenAnswer(i -> i.getArgument(1));
        b = new EmailTemplateBuilder(app);
        ReflectionTestUtils.setField(b, "appBaseUrl", "http://cm.local");
    }

    static Map<String, Object> ctx(String status) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("monitor_id", 42L);
        c.put("monitor_name", "Bekleyen Siparişler");
        c.put("purpose", "Ödeme bekleyen siparişler 10'u geçmesin");
        c.put("profile_name", "ERP Üretim");
        c.put("db_type", "ORACLE");
        c.put("dbquery_target", "erpdb.corp.local:1521/ERP");
        c.put("checked_at", "2026-10-31T09:00:00");
        c.put("duration_ms", 1250L);
        c.put("threshold_ms", 1000);
        c.put("row_count", 25);
        c.put("metric_value", 25.0);
        c.put("dbquery_status", status);
        c.put("dbquery_sql", "SELECT id, musteri, tutar, kart_no FROM siparis WHERE durum = 'BEKLIYOR'");
        var rules = DbQueryAlarmText.toApi(List.of(
                new DbQueryEvaluator.RuleResult("r1", "Bekleyen sipariş", "ROW_COUNT", "25", "≤ 10", "CRITICAL",
                        false, true, null, 25.0, 25.0),
                new DbQueryEvaluator.RuleResult("r2", "Toplam tutar", "COLUMN_VALUE", "5.000", "≥ 1", "WARNING",
                        true, true, null, 5000.0, 5000.0)));
        c.put("rule_results", rules);
        c.put("rule_summary", DbQueryAlarmText.breachSummary(rules));
        return c;
    }

    static DbQueryAlertRowsService.Rows rows(int n) {
        List<List<String>> r = new ArrayList<>();
        for (int i = 0; i < n; i++) r.add(java.util.Arrays.asList(String.valueOf(i + 1), "Müşteri " + (i + 1), "1" + i + "0",
                i == 0 ? null : DbQueryAlertRowsService.MASK));
        return new DbQueryAlertRowsService.Rows("2026-10-31T09:00:00", List.of("id", "musteri", "tutar", "kart_no"), r,
                List.of("kart_no"), 25, false, false);
    }

    EmailTemplateBuilder.AlertMail mail(String type, String level, Map<String, Object> c) {
        return new EmailTemplateBuilder.AlertMail(type, level, "Bekleyen Siparişler", "KRİTİK: sorgu kuralı ihlal edildi.",
                null, c, "Takım A");
    }

    @Test
    @DisplayName("eşik alarmı: amaç, bağlantı (kullanıcısız), çalışma zamanı, süre, satır sayısı, kurallar tablosu, sorgu ve satır tablosu")
    void thresholdMailCarriesEverything() {
        Map<String, Object> c = ctx("BREACH");
        c.put(DbQueryAlertRowsService.CTX_ROWS, rows(3));
        String html = b.buildHtml(mail("DBQUERY_THRESHOLD", "CRITICAL", c));
        String text = b.buildText(mail("DBQUERY_THRESHOLD", "CRITICAL", c));
        for (String s : new String[]{html, text}) {
            assertThat(s).contains("Veritabanı Sorgu İzleme")
                    .contains("Ödeme bekleyen siparişler 10")
                    .contains("ERP Üretim").contains("Oracle").contains("erpdb.corp.local:1521/ERP")
                    .contains("1250 ms").contains("Bekleyen sipariş").contains("≤ 10").contains("Kritik eşik ihlali")
                    .contains("Sağlandı").contains("Müşteri 2").contains("kart_no (maskeli)").contains("NULL")
                    .contains("25 satır döndü")
                    .doesNotContain("Sertifika İzleme").doesNotContain("CA/PKI");
        }
        assertThat(html).contains("Çalıştırılan Sorgu").contains("FROM siparis").contains("/?tab=dbquery&amp;monitor=42");
        assertThat(text).contains("Çalıştırılan Sorgu:").contains("FROM siparis");
        // 25 satırın 3'ü taşındı → "+22 satır daha" notu; tablonun tamamı veritabanında.
        assertThat(html).contains("+22 satır daha");
        assertThat(text).contains("+22 satır daha");
    }

    @Test
    @DisplayName("bildirim günlüğü kopyası: satır YOK, yalnız 'N satır alıcılardaki postada' notu; satırlar maile kapalıysa söylenir")
    void logCopyAndRowsOff() {
        Map<String, Object> c = ctx("BREACH");
        c.put(DbQueryAlertRowsService.CTX_ROWS_WITHHELD, 25);
        String html = b.buildHtml(mail("DBQUERY_THRESHOLD", "CRITICAL", c));
        assertThat(html).contains("25 satır").contains("bildirim günlüğündeki").doesNotContain("Müşteri 1");

        Map<String, Object> off = ctx("BREACH");
        off.put("dbquery_rows_off", true);
        assertThat(b.buildHtml(mail("DBQUERY_THRESHOLD", "CRITICAL", off))).contains("maile eklenmiyor");
        assertThat(b.buildText(mail("DBQUERY_THRESHOLD", "CRITICAL", off))).contains("maile eklenmiyor");
    }

    @Test
    @DisplayName("sorgu hatası: hata türü + maskeli hata; satır tablosu yok")
    void failMail() {
        Map<String, Object> c = ctx("FAIL");
        c.remove("rule_results");
        c.remove("rule_summary");
        c.put("failure_reason", "DB_AUTH");
        c.put("error", "ORA-01017: invalid username/password");
        String html = b.buildHtml(mail("DBQUERY_FAIL", "HIGH", c));
        assertThat(html).contains("Veritabanı Sorgusu Çalıştırılamadı").contains("Kimlik doğrulama hatası")
                .contains("ORA-01017").doesNotContain("Sorgu Sonucu (alarm anı)");
        assertThat(b.buildText(mail("DBQUERY_FAIL", "HIGH", c))).contains("Kimlik doğrulama hatası");
    }

    @Test
    @DisplayName("çok uzun satırlar: posta bütçesi aşılmaz, kesilen satırlar '+N satır daha' olur")
    void rowBudget() {
        List<List<String>> r = new ArrayList<>();
        for (int i = 0; i < 50; i++) r.add(List.of(String.valueOf(i), "x".repeat(199), "y".repeat(199), "z".repeat(199)));
        var big = new DbQueryAlertRowsService.Rows("2026-10-31T09:00:00", List.of("a", "b", "c", "d"), r, List.of(),
                1000, true, true);
        Map<String, Object> c = ctx("BREACH");
        c.put(DbQueryAlertRowsService.CTX_ROWS, big);
        String html = b.buildHtml(mail("DBQUERY_THRESHOLD", "CRITICAL", c));
        assertThat(html).contains("En az 1000 satır döndü").contains("satır daha").contains("kısaltıldı");
        assertThat(html.length()).isLessThan(90_000);
    }

    @Test
    @DisplayName("çözüm e-postası: alarm anındaki sorun + güncel durum; satır etiketi 'Alan Adı' değil 'İzleme'")
    void resolvedMail() {
        Map<String, Object> c = ctx("BREACH");
        c.put("resolved_dbquery_status", "OK");
        c.put("resolved_row_count", "3");
        c.put("resolved_checked_at", "2026-10-31T10:00:00");
        String html = b.buildResolvedHtml("Bekleyen Siparişler", "DBQUERY_THRESHOLD", "Sistem (otomatik)",
                "2026-10-31T10:00:00", "2026-10-31T09:00:00", c);
        String text = b.buildResolvedText("Bekleyen Siparişler", "DBQUERY_THRESHOLD", "Sistem (otomatik)",
                "2026-10-31T10:00:00", "2026-10-31T09:00:00", c);
        assertThat(html).contains("İzleme");
        for (String s : new String[]{html, text}) {
            assertThat(s).contains("Sorgu sonucu kuralları yeniden sağlıyor")
                    .contains("Bekleyen sipariş").contains("Sorgu çalışıyor, kurallar sağlanıyor")
                    .contains("Dönen Satır (güncel)").doesNotContain("Alan Adı");
        }
    }
}
