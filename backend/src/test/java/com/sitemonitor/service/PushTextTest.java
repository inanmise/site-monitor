package com.sitemonitor.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Push metin kuralları — üçü de kullanıcının telefonundaki KANITTAN doğdu.
 *
 * <p>Bildirimde görülenler: {@code "KRİTİK ? http://x: http://x yanıt vermiyor ? KRİTİK: ht?"}
 * ve {@code "DÜZELDİ ? ...: 3 sa 4 dk sonra normale döndü"} — oysa gerçek kesinti ~5 dakikaydı.
 */
class PushTextTest {

    // ── Karakter kümesi ─────────────────────────────────────────────────────

    @Test
    @DisplayName("pushSafe: Türkçe harfler KORUNUR (kanal onları taşıyor)")
    void pushSafe_keepsTurkish() {
        String s = "İzleme ağı çöktü — ŞÜPHELİ ığüşöç ĞÜŞÖÇI";
        assertThat(PushText.pushSafe(s)).contains("İzleme", "ağı", "ŞÜPHELİ", "ığüşöç", "ĞÜŞÖÇI");
    }

    @Test
    @DisplayName("pushSafe: kanalın taşımadığı tipografi ASCII karşılığına çevrilir (soru işareti kalmaz)")
    void pushSafe_translatesTypography() {
        assertThat(PushText.pushSafe("A ▸ B")).isEqualTo("A - B");
        assertThat(PushText.pushSafe("A — B")).isEqualTo("A - B");
        assertThat(PushText.pushSafe("A – B")).isEqualTo("A - B");
        assertThat(PushText.pushSafe("A • B")).isEqualTo("A - B");
        assertThat(PushText.pushSafe("uzun…")).isEqualTo("uzun...");
        assertThat(PushText.pushSafe("1✓/3✗")).isEqualTo("1OK/3X");
        assertThat(PushText.pushSafe("a → b")).isEqualTo("a -> b");
        assertThat(PushText.pushSafe("süre ≥ 5")).isEqualTo("süre >= 5");
        assertThat(PushText.pushSafe("“alıntı”")).isEqualTo("\"alıntı\"");
    }

    @Test
    @DisplayName("pushSafe: emoji ve taşınamayan şekiller DÜŞÜRÜLÜR — soru işareti dizisi üretilmez")
    void pushSafe_dropsUnencodable() {
        String out = PushText.pushSafe("Alarm 🚨 kritik ⛔ durum");
        assertThat(out).doesNotContain("?").contains("Alarm", "kritik", "durum");
    }

    @Test
    @DisplayName("pushSafe çıktısı kanal tarafından TAMAMEN kodlanabilir olmalı (kapı)")
    void pushSafe_outputIsChannelSafe() {
        String nasty = "KRİTİK ▸ a.example.com — 1✓/2✗ … 🚨 ≥ “x” ₺";
        assertThat(PushText.isChannelSafe(PushText.pushSafe(nasty))).isTrue();
        assertThat(PushText.isChannelSafe(nasty)).isFalse();   // girdi güvenli DEĞİLDİ
    }

    @Test
    @DisplayName("truncate: kırpma işareti de kanal-güvenli (… tek başına soru işaretine dönüyordu)")
    void truncate_usesAsciiEllipsis() {
        String out = PushText.truncate("abcdefghij", 8);
        assertThat(out).endsWith("...");
        assertThat(out.length()).isLessThanOrEqualTo(8);
        assertThat(PushText.isChannelSafe(out)).isTrue();
        assertThat(PushText.truncate("kısa", 20)).isEqualTo("kısa");   // sınır altı dokunulmaz
    }

    // ── Süre biçimi ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("compactDuration: sn / dk / sa dk / g sa merdiveni (incidentMeta.js ile AYNI)")
    void compactDuration_ladder() {
        assertThat(PushText.compactDuration(Duration.ofSeconds(45))).isEqualTo("45 sn");
        assertThat(PushText.compactDuration(Duration.ofSeconds(59))).isEqualTo("59 sn");
        assertThat(PushText.compactDuration(Duration.ofSeconds(60))).isEqualTo("1 dk");
        assertThat(PushText.compactDuration(Duration.ofMinutes(5))).isEqualTo("5 dk");
        assertThat(PushText.compactDuration(Duration.ofMinutes(59))).isEqualTo("59 dk");
        assertThat(PushText.compactDuration(Duration.ofMinutes(60))).isEqualTo("1 sa");
        assertThat(PushText.compactDuration(Duration.ofMinutes(123))).isEqualTo("2 sa 3 dk");
        assertThat(PushText.compactDuration(Duration.ofHours(24))).isEqualTo("1 g");
        // Gün sınırında SAAT bilgisi düşmemeli — eski biçim burada yalnız "3 gün" diyordu.
        assertThat(PushText.compactDuration(Duration.ofHours(76))).isEqualTo("3 g 4 sa");
    }

    @Test
    @DisplayName("compactDuration: null ve negatif süre '-' döner (uydurma sayı yok)")
    void compactDuration_guards() {
        assertThat(PushText.compactDuration(null)).isEqualTo("-");
        assertThat(PushText.compactDuration(Duration.ofSeconds(-5))).isEqualTo("-");
    }

    // ── Saklanan damga ──────────────────────────────────────────────────────

    private static final DateTimeFormatter STORED =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);

    @Test
    @DisplayName("parseStoredUtc: damga UTC okunur — İstanbul yereli sanılırsa 3 saat sapar")
    void parseStoredUtc_isUtcNotLocal() {
        Instant fiveMinAgo = Instant.now().minus(Duration.ofMinutes(5));
        Instant parsed = PushText.parseStoredUtc(STORED.format(fiveMinAgo));
        assertThat(parsed).isNotNull();
        long drift = Math.abs(Duration.between(fiveMinAgo, parsed).toSeconds());
        assertThat(drift).as("UTC damga UTC olarak okunmalı").isLessThan(2);

        // Kusurun kendisi: bu damgadan hesaplanan süre DAKİKALARLA ölçülmeli, saatlerle değil.
        assertThat(PushText.compactDuration(Duration.between(parsed, Instant.now()))).isEqualTo("5 dk");
    }

    @Test
    @DisplayName("parseStoredUtc: ayrıştırılamayan damga null → çağıran '-' gösterir, uydurmaz")
    void parseStoredUtc_invalid() {
        assertThat(PushText.parseStoredUtc(null)).isNull();
        assertThat(PushText.parseStoredUtc("  ")).isNull();
        assertThat(PushText.parseStoredUtc("dün")).isNull();
        assertThat(PushText.istClock("dün")).isEqualTo("-");
    }

    @Test
    @DisplayName("istClock: UTC damga İstanbul saatine çevrilerek gösterilir")
    void istClock_convertsToIstanbul() {
        // 2026-06-15T09:30:00Z → İstanbul (UTC+3) 12:30
        assertThat(PushText.istClock("2026-06-15T09:30:00")).isEqualTo("12:30");
    }

    // ── Sebep cümlesi ───────────────────────────────────────────────────────

    @Test
    @DisplayName("reasonOf: baştaki SEVİYE öneki kırpılır — şablon zaten {seviye} yazıyor")
    void reasonOf_stripsLevelPrefix() {
        assertThat(PushText.reasonOf("KRİTİK: sunucu yanıt vermiyor", null))
                .isEqualTo("sunucu yanıt vermiyor");
        assertThat(PushText.reasonOf("YÜKSEK: sertifika bitiyor", null))
                .isEqualTo("sertifika bitiyor");
        assertThat(PushText.reasonOf("UYARI: yavaş", null)).isEqualTo("yavaş");
    }

    @Test
    @DisplayName("reasonOf: seviyeden sonra tekrarlanan ADRES de kırpılır (URL üç kez yazılıyordu)")
    void reasonOf_stripsRepeatedDomain() {
        String msg = "KRİTİK: http://a.example.com/health yanıt vermiyor. Alarm otomatik kapanır.";
        assertThat(PushText.reasonOf(msg, "http://a.example.com/health"))
                .isEqualTo("yanıt vermiyor. Alarm otomatik kapanır.");
    }

    @Test
    @DisplayName("reasonOf: bilgi taşıyan gövdeye DOKUNULMAZ; yalnız ilk satır alınır")
    void reasonOf_keepsBody() {
        assertThat(PushText.reasonOf("KRİTİK: a.example.com zincir bozuk\nikinci satır", "a.example.com"))
                .isEqualTo("zincir bozuk");
        assertThat(PushText.reasonOf("Beklenmedik bir durum", "a.example.com"))
                .isEqualTo("Beklenmedik bir durum");
        assertThat(PushText.reasonOf(null, "a.example.com")).isEmpty();
    }

    @Test
    @DisplayName("reasonOf: adres kırpılınca kalan cümle ilk harfi büyütülerek okunur olur")
    void capitalize_afterStrip() {
        String r = PushText.reasonOf("KRİTİK: a.example.com yanıt vermiyor", "a.example.com");
        assertThat(PushText.capitalize(r)).isEqualTo("Yanıt vermiyor");
    }
}
