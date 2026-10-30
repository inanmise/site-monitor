package com.sitemonitor.service.alarmfeed;

import com.sitemonitor.service.MonitorTypeCatalog;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** Alarm akışı satırları — kolon sözleşmesi, metinler, RCI kararlılığı / tekilliği, kapanış kopyası, maskeler (saf). */
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
        Map<String, Object> r = AlarmFeedRows.openRow(alarm("EXPIRY", "CRITICAL", "api.example.com", 5), DEF, "https://sm.example.com");
        assertThat(new ArrayList<>(r.keySet())).containsExactlyElementsOf(AlarmFeedRows.COLUMNS);
        assertThat(AlarmFeedRows.COLUMNS.subList(0, 9)).containsExactly(
                "CREATEDTIME", "SEVERITY", "RCI", "RCITYPE", "CATEGORY", "FILTERSOURCE", "TEXT", "BODY", "MAILS");
        assertThat(r).doesNotContainKey("EVENTKEY");
    }

    @Test
    @DisplayName("Açık satır: Türkiye saati, seviye eşlemesi, RCI = hedef · tür (gün sayısı RCI'ya girmez), takım + e-posta")
    void openRow() {
        Map<String, Object> r = AlarmFeedRows.openRow(alarm("EXPIRY", "CRITICAL", "api.example.com", 5), DEF, "https://sm.example.com");
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
                .doesNotContain("dakikadır");
    }

    @Test
    @DisplayName("Tür etiketi bütün alarm türlerinde TEKİL (aynı hedefte iki alarm türü aynı RCI'yı alamaz)")
    void labelsAreUniquePerAlertType() {
        Map<String, String> seen = new HashMap<>();
        for (String t : MonitorTypeCatalog.allAlertTypes()) {
            String prev = seen.put(AlarmFeedRows.typeLabel(t), t);
            assertThat(prev).as("etiket çakışması: %s ve %s", prev, t).isNull();
        }
        assertThat(AlarmFeedRows.typeLabel("PING_SLOW")).isEqualTo("Yavaş yanıt (Ping)");
        assertThat(AlarmFeedRows.typeLabel("PORT_SLOW")).isEqualTo("Yavaş yanıt (Port)");
        assertThat(AlarmFeedRows.typeLabel("PING_DOWN")).isEqualTo("Ping yanıtı yok");
    }

    @Test
    @DisplayName("Sorgusu farklı iki URL aynı RCI'yı almaz (ham hedefin kısa özeti); host / k6 adı olduğu gibi")
    void targetsStayDistinct() {
        Map<String, Object> a = AlarmFeedRows.openRow(alarm("HTTP_DOWN", "HIGH", "https://x.example.com/api?env=a", null), DEF, null);
        Map<String, Object> b = AlarmFeedRows.openRow(alarm("HTTP_DOWN", "HIGH", "https://x.example.com/api?env=b", null), DEF, null);
        assertThat(a.get("RCI")).isNotEqualTo(b.get("RCI"));
        assertThat((String) a.get("RCI")).startsWith("https://x.example.com/api #").doesNotContain("env=");
        Map<String, Object> k6 = AlarmFeedRows.openRow(alarm("SCRIPTED_FAIL", "HIGH", "Ödeme @ akışı #2", null), DEF, null);
        assertThat(k6.get("RCI")).isEqualTo("Ödeme @ akışı #2 · Sentetik senaryo başarısız");
    }

    @Test
    @DisplayName("RCI ömür boyu sabit: gün sayısı değişse de aynı; kapanış satırı aynı RCI + SEVERITY")
    void rciIsStable() {
        Map<String, Object> d5 = AlarmFeedRows.openRow(alarm("EXPIRY", "HIGH", "api.example.com", 5), DEF, null);
        Map<String, Object> d4 = AlarmFeedRows.openRow(alarm("EXPIRY", "HIGH", "api.example.com", 4), DEF, null);
        assertThat(d5.get("RCI")).isEqualTo(d4.get("RCI"));
        assertThat(AlarmFeedRows.eventKey(d5)).isEqualTo(AlarmFeedRows.eventKey(d4));
        Map<String, Object> snap = AlarmFeedRows.withSnapshotFields(d5, alarm("EXPIRY", "HIGH", "api.example.com", 5));
        Map<String, Object> res = AlarmFeedRows.closure(snap, DEF, "2026-10-30T11:20:00", AlarmFeedRows.REASON_RESOLVED, null, " (otomatik)");
        assertThat(AlarmFeedRows.eventKey(res)).isEqualTo(AlarmFeedRows.eventKey(d5));
        assertThat(res.get("SEVERITY")).isEqualTo("Major");
        assertThat(res.get("LINK")).isNull();
    }

    @Test
    @DisplayName("Kapanış: GÖNDERİLMİŞ kopyadan — STATUS=RESOLVED, RESOLVEDTIME, ÇÖZÜLDÜ metni, ilk satır yeni durum; kişi adı yok")
    void closureFromSnapshot() {
        AlarmFeedRows.Alarm a = new AlarmFeedRows.Alarm(7L, "HTTP_DOWN", "CRITICAL", "https://shop.example.com/login",
                "HTTP 503", null, "2026-10-30T10:00:00", true, "2026-10-30T10:25:00", "Ayşe Yılmaz", false, null,
                null, null, team(3, "Web", "web@example.com"), null);
        Map<String, Object> r = AlarmFeedRows.resolvedRow(a, DEF, null);
        assertThat(r.get("SEVERITY")).isEqualTo("Critical");
        assertThat(r.get("STATUS")).isEqualTo("RESOLVED");
        assertThat(r.get("RESOLVEDTIME")).isEqualTo("2026-10-30 13:25:00");
        assertThat(r.get("TEXT")).isEqualTo("(https://shop.example.com/login) ÇÖZÜLDÜ: HTTP/Web erişilemez");
        assertThat((String) r.get("BODY")).startsWith("Durum: ÇÖZÜLDÜ — 2026-10-30 13:25:00 (elle kapatıldı) · Seviye: Critical")
                .contains("Takım: Web <web@example.com>").doesNotContain("Ayşe");
        assertThat(r.get("MAILS")).isEqualTo("web@example.com");
        assertThat(r).doesNotContainKeys(AlarmFeedRows.SNAP_TARGET, AlarmFeedRows.SNAP_PROBLEM);
    }

    @Test
    @DisplayName("Kapanış nedenleri: seviye değişimi, anahtar değişimi, kapsam dışı, silinme")
    void closureReasons() {
        Map<String, Object> snap = AlarmFeedRows.withSnapshotFields(
                AlarmFeedRows.openRow(alarm("EXPIRY", "WARNING", "api.example.com", 9), DEF, null),
                alarm("EXPIRY", "WARNING", "api.example.com", 9));
        Map<String, Object> lc = AlarmFeedRows.closure(snap, DEF, "2026-10-30T11:00:00", AlarmFeedRows.REASON_LEVEL_CHANGE, "Critical", null);
        assertThat(lc.get("SEVERITY")).isEqualTo("Warning");
        assertThat(lc.get("TEXT")).isEqualTo("(api.example.com) Seviye değişti (yeni seviye Critical): Sertifika süresi doluyor");
        assertThat((String) lc.get("BODY")).startsWith("Durum: KAPANDI — seviye değişti, yeni seviye Critical (2026-10-30 14:00:00)");
        assertThat(AlarmFeedRows.closure(snap, DEF, "2026-10-30T11:00:00", AlarmFeedRows.REASON_KEY_CHANGE, null, null).get("TEXT"))
                .isEqualTo("(api.example.com) Kayıt yeni anahtarla sürüyor: Sertifika süresi doluyor");
        assertThat(AlarmFeedRows.closure(snap, DEF, "2026-10-30T11:00:00", AlarmFeedRows.REASON_SCOPE, null, null).get("TEXT"))
                .isEqualTo("(api.example.com) Akış kapsamından çıktı: Sertifika süresi doluyor");
        assertThat(AlarmFeedRows.closure(snap, DEF, "2026-10-30T11:00:00", AlarmFeedRows.REASON_GONE, null, null).get("TEXT"))
                .isEqualTo("(api.example.com) Alarm kaydı silindi: Sertifika süresi doluyor");
    }

    @Test
    @DisplayName("Kapanış nedeni / kişi: otomatik / sistem gerekçesi / elle")
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
    @DisplayName("Clear kipi: kapanışın SEVERITY'si clear metni; yalnız-hedef RCI; virgül ayracı; sabit RCITYPE")
    void clearModeAndOptions() {
        AlarmFeedSettings.Config c = new AlarmFeedSettings.Config(true, "WARNING", Set.of(), Set.of(), 15,
                "clear", "Normal", "P1", "P2", "P3", "target", "SM", "WEB", "comma", List.of(), 30, 2000, true);
        Map<String, Object> open = AlarmFeedRows.openRow(alarm("PING_DOWN", "WARNING", "host1", null), c, null);
        Map<String, Object> res = AlarmFeedRows.closure(AlarmFeedRows.withSnapshotFields(open, alarm("PING_DOWN", "WARNING", "host1", null)),
                c, "2026-10-30T11:00:00", AlarmFeedRows.REASON_RESOLVED, null, null);
        assertThat(open.get("SEVERITY")).isEqualTo("P3");
        assertThat(res.get("SEVERITY")).isEqualTo("Normal");
        assertThat(open.get("RCI")).isEqualTo("host1").isEqualTo(res.get("RCI"));
        assertThat(AlarmFeedRows.rciKey(open)).isEqualTo(AlarmFeedRows.rciKey(res));
        assertThat(open.get("RCITYPE")).isEqualTo("WEB");
        assertThat(open.get("FILTERSOURCE")).isEqualTo("SM");
        assertThat(open.get("MAILS")).isEqualTo("dijital@example.com, mobil@example.com");
    }

    @Test
    @DisplayName("Sır sızmaz: hedef ve iletideki HER URL kullanıcı bilgisi / sorgu olmadan; ad=değer sırrı maskeli")
    void masking() {
        AlarmFeedRows.Alarm a = new AlarmFeedRows.Alarm(9L, "KEYWORD", "HIGH",
                "https://user:pw@shop.example.com/path?token=abc#x",
                "Beklenen sözcük yok: https://bob:hunter2@shop.example.com/a?sig=SECRET123&x=1 (yönlendirme "
                        + "HTTPS://cdn.example.com/f?X-Amz-Signature=AWSSIG)", null, "2026-10-30T10:00:00",
                false, null, null, false, null, null, null, null, null);
        Map<String, Object> r = AlarmFeedRows.openRow(a, DEF, null);
        assertThat((String) r.get("RCI")).startsWith("https://shop.example.com/path #").doesNotContain("token").doesNotContain("pw");
        String body = (String) r.get("BODY");
        assertThat(body).doesNotContain("SECRET123").doesNotContain("hunter2").doesNotContain("bob")
                .doesNotContain("AWSSIG")
                .contains("https://shop.example.com/a").contains("Takım: atanmamış");
        assertThat(r.get("MAILS")).isNull();
        assertThat(AlarmFeedRows.safeText("x".repeat(10_000))).hasSizeLessThanOrEqualTo(4000);
    }

    @Test
    @DisplayName("Uzun metin sınırlanır; çok satırlı ileti tek satıra iner; onay + fırtına notu")
    void clipAndNotes() {
        String longMsg = "satır1\nsatır2\t" + "x".repeat(1000);
        AlarmFeedRows.Alarm a = new AlarmFeedRows.Alarm(5L, "SCRIPTED_FAIL", "CRITICAL", "Login senaryosu " + "y".repeat(400),
                longMsg, null, "2026-10-30T10:00:00", false, null, null, true, "2026-10-30T10:03:00", 12L, null, null, null);
        Map<String, Object> r = AlarmFeedRows.openRow(a, DEF, null);
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
