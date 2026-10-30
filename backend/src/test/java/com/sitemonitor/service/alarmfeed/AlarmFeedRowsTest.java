package com.sitemonitor.service.alarmfeed;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Alarm akışı satırları — kolon sözleşmesi, metinler, RCI kararlılığı, maskeler (saf). */
class AlarmFeedRowsTest {

    static final AlarmFeedSettings.Config DEF = AlarmFeedSettings.defaults();

    static AlarmFeedRows.TeamInfo team(long id, String name, String... mails) {
        return new AlarmFeedRows.TeamInfo(id, name, List.of(mails));
    }

    static AlarmFeedRows.Alarm alarm(String type, String level, String target, Integer days) {
        return new AlarmFeedRows.Alarm(42L, type, level, target, "Sertifika 5 gün içinde sona erecek", days,
                "2026-10-30T10:55:00", false, null, null, false, null, null, "API Geçidi",
                team(3, "Dijital Kanallar (Bölüm / Ekip)", "dijital@example.com"),
                team(4, "Mobil Ekip", "mobil@example.com", "DIJITAL@example.com"));
    }

    @Test
    @DisplayName("Kolonlar: ekibin 9 kolonu istedikleri sırayla, sonra ek kolonlar; EVENTKEY yok")
    void columnsInOrder() {
        Map<String, Object> r = AlarmFeedRows.row(alarm("EXPIRY", "CRITICAL", "api.example.com", 5), DEF,
                "https://sm.example.com", "CRITICAL", true, null, null, null);
        assertThat(new ArrayList<>(r.keySet())).containsExactlyElementsOf(AlarmFeedRows.COLUMNS);
        assertThat(AlarmFeedRows.COLUMNS.subList(0, 9)).containsExactly(
                "CREATEDTIME", "SEVERITY", "RCI", "RCITYPE", "CATEGORY", "FILTERSOURCE", "TEXT", "BODY", "MAILS");
        assertThat(r).doesNotContainKey("EVENTKEY");
    }

    @Test
    @DisplayName("Açık satır: Türkiye saati, seviye eşlemesi, RCI = hedef · tür (gün sayısı RCI'ya girmez), takım + e-posta")
    void openRow() {
        Map<String, Object> r = AlarmFeedRows.row(alarm("EXPIRY", "CRITICAL", "api.example.com", 5), DEF,
                "https://sm.example.com", "CRITICAL", true, null, null, null);
        assertThat(r.get("CREATEDTIME")).isEqualTo("2026-10-30 13:55:00");
        assertThat(r.get("SEVERITY")).isEqualTo("Critical");
        assertThat(r.get("RCI")).isEqualTo("api.example.com · Sertifika süresi doluyor");
        assertThat(r.get("RCITYPE")).isEqualTo("Sertifika");
        assertThat(r.get("CATEGORY")).isEqualTo("Sertifika süresi doluyor");
        assertThat(r.get("FILTERSOURCE")).isEqualTo("SiteMonitor");
        assertThat(r.get("TEXT")).isEqualTo("(api.example.com) Sertifika süresi doluyor (5 gün kaldı)");
        assertThat(r.get("MAILS")).isEqualTo("dijital@example.com; mobil@example.com");   // tekil (büyük/küçük harf)
        assertThat(r.get("STATUS")).isEqualTo("OPEN");
        assertThat(r.get("RESOLVEDTIME")).isNull();
        assertThat(r.get("ALERTID")).isEqualTo(42L);
        assertThat(r.get("TEAM")).isEqualTo("Dijital Kanallar (Bölüm / Ekip)");
        assertThat(r.get("UGTEAM")).isEqualTo("Mobil Ekip");
        assertThat(r.get("LINK")).isEqualTo("https://sm.example.com/?tab=alerthistory&alert=42");
        String body = (String) r.get("BODY");
        assertThat(body).startsWith("Durum: AÇIK · Seviye: Critical")
                .contains("Kaynak: SiteMonitor · Sertifika izleme")
                .contains("Hedef: api.example.com · İzleme: API Geçidi")
                .contains("Başlangıç: 2026-10-30 13:55:00 (TSİ)")
                .contains("Takım: Dijital Kanallar (Bölüm / Ekip) <dijital@example.com> · UG: Mobil Ekip <mobil@example.com, DIJITAL@example.com>")
                .contains("Alarm #42 · https://sm.example.com/?tab=alerthistory&alert=42")
                .doesNotContain("dakikadır");   // göreli süre yok (aynı durum = aynı gövde)
    }

    @Test
    @DisplayName("RCI ömür boyu sabit: gün sayısı değişse de, açık ya da çözülmüş olsa da aynı")
    void rciIsStable() {
        Map<String, Object> d5 = AlarmFeedRows.row(alarm("EXPIRY", "HIGH", "api.example.com", 5), DEF, null, "HIGH", true, null, null, null);
        Map<String, Object> d4 = AlarmFeedRows.row(alarm("EXPIRY", "HIGH", "api.example.com", 4), DEF, null, "HIGH", true, null, null, null);
        Map<String, Object> res = AlarmFeedRows.row(alarm("EXPIRY", "HIGH", "api.example.com", 4), DEF, null, "HIGH", false,
                "2026-10-30T11:20:00", AlarmFeedRows.REASON_RESOLVED, null);
        assertThat(d5.get("RCI")).isEqualTo(d4.get("RCI")).isEqualTo(res.get("RCI"));
        assertThat(d5.get("SEVERITY")).isEqualTo("Major").isEqualTo(res.get("SEVERITY"));
        assertThat(d5.get("LINK")).isNull();   // dış adres tanımlı değil
    }

    @Test
    @DisplayName("Çözüldü satırı: aynı SEVERITY + STATUS=RESOLVED + RESOLVEDTIME; kişi adı yazılmaz")
    void resolvedRow() {
        AlarmFeedRows.Alarm a = new AlarmFeedRows.Alarm(7L, "HTTP_DOWN", "CRITICAL", "https://shop.example.com/login",
                "HTTP 503", null, "2026-10-30T10:00:00", true, "2026-10-30T10:25:00", "Ayşe Yılmaz", false, null,
                null, null, team(3, "Web", "web@example.com"), null);
        Map<String, Object> r = AlarmFeedRows.row(a, DEF, null, "CRITICAL", false, "2026-10-30T10:25:00",
                AlarmFeedRows.REASON_RESOLVED, null);
        assertThat(r.get("SEVERITY")).isEqualTo("Critical");
        assertThat(r.get("STATUS")).isEqualTo("RESOLVED");
        assertThat(r.get("RESOLVEDTIME")).isEqualTo("2026-10-30 13:25:00");
        assertThat(r.get("TEXT")).isEqualTo("(https://shop.example.com/login) ÇÖZÜLDÜ: HTTP/Web erişilemez");
        assertThat((String) r.get("BODY")).contains("Durum: ÇÖZÜLDÜ — 2026-10-30 13:25:00 (elle kapatıldı)")
                .doesNotContain("Ayşe");
        assertThat(r.get("MAILS")).isEqualTo("web@example.com");
        assertThat(r.get("UGTEAM")).isNull();
    }

    @Test
    @DisplayName("Kapanış gerekçesi: otomatik / sistem gerekçesi / elle")
    void closer() {
        assertThat(AlarmFeedRows.closer(closed(null))).isEqualTo(" (otomatik)");
        assertThat(AlarmFeedRows.closer(closed("system"))).isEqualTo(" (otomatik)");
        assertThat(AlarmFeedRows.closer(closed("Sistem (otomatik)"))).isEqualTo(" (otomatik)");
        assertThat(AlarmFeedRows.closer(closed("Sistem (izleme duraklatıldı)"))).isEqualTo(" (sistem: izleme duraklatıldı)");
        assertThat(AlarmFeedRows.closer(closed("Mehmet Demir"))).isEqualTo(" (elle kapatıldı)");
    }

    private static AlarmFeedRows.Alarm closed(String by) {
        return new AlarmFeedRows.Alarm(1L, "PING_DOWN", "HIGH", "h", null, null, "2026-10-30T10:00:00", true,
                "2026-10-30T10:05:00", by, false, null, null, null, null, null);
    }

    @Test
    @DisplayName("Clear kipi: çözülen satırın SEVERITY'si clear metni; yalnız-hedef RCI; virgül ayracı; sabit RCITYPE")
    void clearModeAndOptions() {
        AlarmFeedSettings.Config c = new AlarmFeedSettings.Config(true, "WARNING", java.util.Set.of(), java.util.Set.of(), 15,
                "clear", "Normal", "P1", "P2", "P3", "target", "SM", "WEB", "comma", List.of(), 30, 2000, true);
        Map<String, Object> open = AlarmFeedRows.row(alarm("PING_DOWN", "WARNING", "host1", null), c, null, "WARNING",
                true, null, null, null);
        Map<String, Object> res = AlarmFeedRows.row(alarm("PING_DOWN", "WARNING", "host1", null), c, null, "WARNING",
                false, "2026-10-30T11:00:00", AlarmFeedRows.REASON_RESOLVED, null);
        assertThat(open.get("SEVERITY")).isEqualTo("P3");
        assertThat(res.get("SEVERITY")).isEqualTo("Normal");
        assertThat(open.get("RCI")).isEqualTo("host1").isEqualTo(res.get("RCI"));
        assertThat(open.get("RCITYPE")).isEqualTo("WEB");
        assertThat(open.get("FILTERSOURCE")).isEqualTo("SM");
        assertThat(open.get("MAILS")).isEqualTo("dijital@example.com, mobil@example.com");
    }

    @Test
    @DisplayName("Seviye değişimi ve kapsam dışı kapanışlarının metni yeni seviyeyi / nedeni söyler")
    void levelChangeAndScopeTexts() {
        AlarmFeedRows.Alarm a = alarm("EXPIRY", "CRITICAL", "api.example.com", 3);
        Map<String, Object> lc = AlarmFeedRows.row(a, DEF, null, "WARNING", false, "2026-10-30T11:00:00",
                AlarmFeedRows.REASON_LEVEL_CHANGE, "CRITICAL");
        assertThat(lc.get("SEVERITY")).isEqualTo("Warning");
        assertThat(lc.get("TEXT")).isEqualTo("(api.example.com) Seviye değişti (yeni seviye Critical): Sertifika süresi doluyor");
        assertThat((String) lc.get("BODY")).startsWith("Durum: KAPANDI — seviye değişti, yeni seviye Critical (2026-10-30 14:00:00)");
        Map<String, Object> sc = AlarmFeedRows.row(a, DEF, null, "CRITICAL", false, "2026-10-30T11:00:00",
                AlarmFeedRows.REASON_SCOPE, null);
        assertThat(sc.get("TEXT")).isEqualTo("(api.example.com) Akış kapsamından çıktı: Sertifika süresi doluyor");
    }

    @Test
    @DisplayName("Hedef adresi sorgu / parça / kullanıcı bilgisi olmadan; iletideki hassas sorgu parametresi maskeli")
    void masking() {
        AlarmFeedRows.Alarm a = new AlarmFeedRows.Alarm(9L, "KEYWORD", "HIGH",
                "https://user:pw@shop.example.com/path?token=abc#x",
                "Beklenen sözcük yok: https://shop.example.com/a?token=SECRET123&x=1", null, "2026-10-30T10:00:00",
                false, null, null, false, null, null, null, null, null);
        Map<String, Object> r = AlarmFeedRows.row(a, DEF, null, "HIGH", true, null, null, null);
        assertThat((String) r.get("RCI")).startsWith("https://shop.example.com/path ·").doesNotContain("token").doesNotContain("pw");
        assertThat((String) r.get("BODY")).doesNotContain("SECRET123").contains("Takım: atanmamış");
        assertThat(r.get("MAILS")).isNull();
    }

    @Test
    @DisplayName("Uzun metin sınırlanır; çok satırlı ileti tek satıra iner; onay + fırtına notu")
    void clipAndNotes() {
        String longMsg = "satır1\nsatır2\t" + "x".repeat(1000);
        AlarmFeedRows.Alarm a = new AlarmFeedRows.Alarm(5L, "SCRIPTED_FAIL", "CRITICAL", "Login senaryosu " + "y".repeat(400),
                longMsg, null, "2026-10-30T10:00:00", false, null, null, true, "2026-10-30T10:03:00", 12L, null, null, null);
        Map<String, Object> r = AlarmFeedRows.row(a, DEF, null, "CRITICAL", true, null, null, null);
        assertThat(((String) r.get("RCI")).length()).isLessThanOrEqualTo(AlarmFeedRows.RCI_MAX);
        assertThat(((String) r.get("TEXT")).length()).isLessThanOrEqualTo(AlarmFeedRows.TEXT_MAX);
        String body = (String) r.get("BODY");
        assertThat(body.length()).isLessThanOrEqualTo(AlarmFeedRows.BODY_MAX);
        assertThat(body).contains("satır1 satır2 x").contains("Not: Onaylandı (2026-10-30 13:03:00) · Alarm fırtınası #12 üyesi");
    }

    @Test
    @DisplayName("Türkiye saati: Z'li, kesirli ya da okunamaz değer")
    void istanbul() {
        assertThat(AlarmFeedRows.istanbul("2026-10-30T21:30:00")).isEqualTo("2026-10-31 00:30:00");
        assertThat(AlarmFeedRows.istanbul("2026-10-30T21:30:00.123Z")).isEqualTo("2026-10-31 00:30:00");
        assertThat(AlarmFeedRows.istanbul("bozuk")).isNull();
        assertThat(AlarmFeedRows.istanbul(null)).isNull();
    }
}
