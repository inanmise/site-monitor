package com.sitemonitor.service.dbquery;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Sorgu alarmının metinleri — özet, seviye, kod sınıfı ve özel konu satırı (2026-10-31). */
class DbQueryAlarmTextTest {

    static DbQueryEvaluator.RuleResult rule(String label, String kind, String actual, String expected, String level,
                                            boolean passed, boolean evaluated) {
        return new DbQueryEvaluator.RuleResult("r-" + label, label, kind, actual, expected, level, passed, evaluated, null,
                5.0, 5.0);
    }

    @Test
    @DisplayName("toApi / toJson / parse: API biçimi kimlik + ölçü alanlarını taşır, gidiş-dönüş aynı")
    void apiRoundTrip() {
        var list = List.of(rule("Bekleyen", "ROW_COUNT", "25", "≤ 10", "CRITICAL", false, true));
        var api = DbQueryAlarmText.toApi(list);
        assertThat(api.get(0)).containsEntry("id", "r-Bekleyen").containsEntry("kind", "ROW_COUNT")
                .containsEntry("passed", false).containsEntry("evaluated", true).containsEntry("measure", 5.0);
        assertThat(DbQueryAlarmText.parse(DbQueryAlarmText.toJson(list))).isEqualTo(api);
        assertThat(DbQueryAlarmText.parse("bozuk")).isEmpty();
        assertThat(DbQueryAlarmText.parse(null)).isEmpty();
    }

    @Test
    @DisplayName("ihlal özeti: yalnız değerlendirilip sağlanmayan kurallar; seviye 'kritik eşik' / 'uyarı eşiği'; yoksa 'kurallar sağlandı'")
    void breachSummary() {
        var api = DbQueryAlarmText.toApi(List.of(
                rule("Bekleyen sipariş", "ROW_COUNT", "1.250", "≤ 100", "CRITICAL", false, true),
                rule("Toplam", "COLUMN_VALUE", "5", "≥ 1", "WARNING", true, true),
                rule("Değişim", "CHANGE", null, "≤ %10", "WARNING", false, false),
                rule("", "TEXT_MATCH", "HATA", "= OK", "WARNING", false, true)));
        String s = DbQueryAlarmText.breachSummary(api);
        assertThat(s).isEqualTo("Bekleyen sipariş: gelen 1.250 · beklenen ≤ 100 (kritik eşik) · "
                + "Metin eşleşmesi: gelen HATA · beklenen = OK (uyarı eşiği)");
        // Büyük harfli seviye sözcüğü yok — alarmın kendi seviye rozetiyle çelişmesin.
        assertThat(s).doesNotContain("KRİTİK").doesNotContain("UYARI");
        assertThat(DbQueryAlarmText.breachSummary(List.of())).isEqualTo("kurallar sağlandı");

        Map<String, Object> big = new HashMap<>(api.get(0));
        big.put("label", "x".repeat(600));
        assertThat(DbQueryAlarmText.breachSummary(List.of(big))).hasSize(DbQueryAlarmText.SUMMARY_MAX).endsWith("…");
    }

    @Test
    @DisplayName("kural durumu metni: sağlandı / değerlendirilemedi / seviyeli ihlal")
    void ruleState() {
        assertThat(DbQueryAlarmText.ruleStateText(Map.of("passed", true, "evaluated", true))).isEqualTo("Sağlandı");
        assertThat(DbQueryAlarmText.ruleStateText(Map.of("passed", false, "evaluated", false))).isEqualTo("Değerlendirilemedi");
        assertThat(DbQueryAlarmText.ruleStateText(Map.of("passed", false, "evaluated", true, "level", "CRITICAL")))
                .isEqualTo("Kritik eşik ihlali");
        assertThat(DbQueryAlarmText.ruleStateText(null)).isEqualTo("—");
    }

    @Test
    @DisplayName("öğe hatası daima kod taşır; HİÇBİR kod ağ / DNS sınıfı değildir (tek veritabanının çöküşü bastırılmaz)")
    void codesAndOutageClass() {
        assertThat(DbQueryAlarmText.itemError("DB_TIMEOUT", "30 sn")).isEqualTo("[DB_TIMEOUT] 30 sn");
        assertThat(DbQueryAlarmText.itemError(null, "connect timed out")).isEqualTo("[DB_UNKNOWN] connect timed out");
        assertThat(DbQueryAlarmText.itemError(null, null)).isEqualTo("[DB_UNKNOWN] sorgu çalıştırılamadı");
        assertThat(DbQueryAlarmText.codeOf("[DB_AUTH] parola")).isEqualTo("DB_AUTH");
        assertThat(DbQueryAlarmText.codeOf("connect timed out")).isNull();
        assertThat(DbQueryAlarmText.codeOf("[X] y")).isNull();

        for (String c : List.of("DB_UNREACHABLE", "DB_TIMEOUT", "DB_DNS", "DB_UNKNOWN"))
            assertThat(DbQueryAlarmText.outageClassOf(c)).as(c).isEqualTo("OTHER");
        for (String c : List.of("DB_AUTH", "DB_SQL_ERROR", "DB_GUARD_REJECTED", "DB_BLOCKED", "DB_PROFILE_MISSING"))
            assertThat(DbQueryAlarmText.outageClassOf(c)).as(c).isEqualTo("CONFIG");
        assertThat(DbQueryAlarmText.outageClassOf(null)).isNull();
        assertThat(DbQueryAlarmText.failureLabel("DB_UNREACHABLE")).isEqualTo("Veritabanı sunucusuna ulaşılamadı");
        assertThat(DbQueryAlarmText.failureLabel("DB_YENI")).isEqualTo("DB_YENI");
        assertThat(DbQueryAlarmText.dbTypeLabel("MSSQL")).isEqualTo("Microsoft SQL Server");
    }

    @Test
    @DisplayName("özel konu: TR ve EN yer tutucular, bilinmeyen yer tutucu olduğu gibi, tek satır, 200 karakter tavanı")
    void mailSubject() {
        Map<String, String> v = Map.of("name", "Bekleyen Siparişler", "rows", "25", "level", "KRİTİK", "value", "25",
                "connection", "ERP Üretim", "purpose", "Birikme", "time", "31.10.2026 12:00");
        assertThat(DbQueryMailSubject.render("{seviye}: {ad} — {satir} satır ({baglanti})", v))
                .isEqualTo("KRİTİK: Bekleyen Siparişler — 25 satır (ERP Üretim)");
        assertThat(DbQueryMailSubject.render("{level} {name} {rows} {value} {time} {purpose}", v))
                .isEqualTo("KRİTİK Bekleyen Siparişler 25 25 31.10.2026 12:00 Birikme");
        assertThat(DbQueryMailSubject.render("{AD} {bilinmeyen} {", v)).isEqualTo("Bekleyen Siparişler {bilinmeyen} {");
        assertThat(DbQueryMailSubject.render("satır\r\nbirleşir\t sekme", v)).isEqualTo("satır birleşir sekme");
        assertThat(DbQueryMailSubject.render("x".repeat(500), v)).hasSize(DbQueryMailSubject.MAX_LENGTH).endsWith("…");
        assertThat(DbQueryMailSubject.render("   ", v)).isNull();
        assertThat(DbQueryMailSubject.render("{deger}", Map.of())).isNull();
        assertThat(DbQueryMailSubject.render("{ad}", null)).isNull();
        assertThat(DbQueryMailSubject.hasTemplate(" ")).isFalse();
    }
}
