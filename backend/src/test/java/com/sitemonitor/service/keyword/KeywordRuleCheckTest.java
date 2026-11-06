package com.sitemonitor.service.keyword;

import com.sitemonitor.service.AppSettingsService;
import com.sitemonitor.service.KeywordCheckerService;
import com.sitemonitor.service.SsrfGuard;
import com.sitemonitor.service.keyword.content.KeywordChangeDetector;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Kontrol yolunda kural motoru + boyut + içerik değişimi (kw sözleşmesi §6 / §7 / §10.6, be2 Aşama 2) — gerçek HTTP yolu
 * (süreç içi sunucu). Eski tek kelime izlemesi bugünküyle AYNI sayım / alıntı / kararı verir; çoklu kural, iki eşik, bölge,
 * boyut ve değişim sonuç anahtarlarını üretir.
 */
class KeywordRuleCheckTest {

    private HttpServer server;
    private final AtomicReference<String> body = new AtomicReference<>("");
    private final AtomicReference<String> contentType = new AtomicReference<>("text/html; charset=utf-8");
    private final AtomicReference<Integer> status = new AtomicReference<>(200);

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    private String serve(String html) throws IOException {
        body.set(html);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            byte[] b = body.get().getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", contentType.get());
            ex.sendResponseHeaders(status.get(), b.length == 0 ? -1 : b.length);
            if (b.length > 0) try (var os = ex.getResponseBody()) { os.write(b); }
            else ex.close();
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/";
    }

    private static KeywordCheckerService checker() {
        AppSettingsService s = mock(AppSettingsService.class);
        when(s.getBoolean("site.monitor.monitoring.allow-loopback-targets", false)).thenReturn(true);
        when(s.getBoolean("site.monitor.monitoring.allow-internal-targets", true)).thenReturn(true);
        KeywordCheckerService svc = new KeywordCheckerService(new SsrfGuard(s));
        svc.init();
        return svc;
    }

    private static KeywordCheckSpec.Builder spec(String url, String keyword) {
        return KeywordCheckSpec.builder(url, keyword).timeoutMs(5000).diagnose(true).monitorLevel("HIGH");
    }

    static final String PAGE = "<html><body><h1>Kampanya</h1><div id=\"fiyat\">Fiyat: 1.250 TL</div>"
            + "<p>İndirim yok</p><script>var x='gizli';</script></body></html>";

    @Test
    @DisplayName("Eski tek kelime: sayım, alıntı, karar bugünküyle aynı; tek kural sonucu (r1) taşınır, sağlıklıysa seviye yok")
    void legacyParity() throws IOException {
        String url = serve(PAGE);
        Map<String, Object> r = checker().check(spec(url, "fiyat").build());
        assertThat(r.get("ok")).isEqualTo(true);
        assertThat(r.get("count")).as("ham kapsam: id=\"fiyat\" özniteliği + metin (bugünkü gibi)").isEqualTo(2);
        assertThat(r.get("found")).isEqualTo(true);
        assertThat((String) r.get("snippet")).contains("Fiyat: 1.250 TL");
        assertThat(r).doesNotContainKeys("level", "breach_level", "rules_summary", "size_ok", "content_changed", "baseline_missing");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rr = (List<Map<String, Object>>) r.get("rule_results");
        assertThat(rr).hasSize(1);
        assertThat(rr.get(0)).containsEntry("id", "r1").containsEntry("ok", true).containsEntry("count", 2);
        // koşul tutmuyor → bugünkü neden kodu, seviye izlemeninki
        Map<String, Object> bad = checker().check(spec(url, "Sepet").build());
        assertThat(bad.get("ok")).isEqualTo(false);
        assertThat(bad.get("failure_reason")).isEqualTo(KeywordFailureClassifier.KEYWORD_NOT_FOUND);
        assertThat(bad.get("level")).isEqualTo("HIGH");
        assertThat(bad).doesNotContainKey("breach_level");
    }

    @Test
    @DisplayName("Çoklu kural VE: metin + desen + CSS bölgesi; kural #1 sayımı / alıntısı; hepsi sağlandı → ok")
    void multiRuleAnd() throws IOException {
        String url = serve(PAGE);
        String rules = "[{\"id\":\"r1\",\"kind\":\"TEXT\",\"pattern\":\"indirim\",\"operator\":\"GTE\",\"count\":1},"
                + "{\"id\":\"r2\",\"kind\":\"REGEX\",\"pattern\":\"\\\\d+[.]\\\\d+ TL\",\"operator\":\"GTE\",\"count\":1},"
                + "{\"id\":\"r3\",\"kind\":\"TEXT\",\"pattern\":\"Fiyat\",\"operator\":\"EQ\",\"count\":1,"
                + "\"scope\":{\"type\":\"CSS\",\"value\":\"#fiyat\"}}]";
        Map<String, Object> r = checker().check(spec(url, "indirim").rulesJson(rules).build());
        assertThat(r.get("ok")).as(String.valueOf(r)).isEqualTo(true);
        assertThat(r.get("count")).as("kural #1 — Türkçe katlama İ → i").isEqualTo(1);
        assertThat((String) r.get("snippet")).contains("İndirim yok");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rr = (List<Map<String, Object>>) r.get("rule_results");
        assertThat(rr).extracting(x -> x.get("id")).containsExactly("r1", "r2", "r3");
        assertThat(rr).allSatisfy(x -> assertThat(x.get("ok")).isEqualTo(true));
    }

    @Test
    @DisplayName("İki eşik: kritik ihlal → level/breach_level CRITICAL, kural özeti; çok kuralda neden RULES_NOT_MET")
    void twoLevelsAndRulesNotMet() throws IOException {
        String url = serve("<p>hata hata hata hata hata</p><p>ok</p>");   // "hata" 5 kez
        String rules = "[{\"id\":\"r1\",\"kind\":\"TEXT\",\"pattern\":\"hata\",\"operator\":\"LT\",\"count\":2,"
                + "\"critical\":{\"operator\":\"LT\",\"count\":4}},"
                + "{\"id\":\"r2\",\"kind\":\"TEXT\",\"pattern\":\"ok\",\"operator\":\"GTE\",\"count\":1}]";
        Map<String, Object> r = checker().check(spec(url, "hata").rulesJson(rules).build());
        assertThat(r.get("ok")).isEqualTo(false);
        assertThat(r.get("level")).isEqualTo("CRITICAL");
        assertThat(r.get("breach_level")).isEqualTo("CRITICAL");
        assertThat((String) r.get("rules_summary")).contains("hata");
        assertThat(r.get("failure_reason")).isEqualTo(KeywordFailureClassifier.RULES_NOT_MET);
        assertThat((String) r.get("failure_detail")).startsWith("İçerik kuralları sağlanmadı");
        assertThat(r.get("count")).isEqualTo(5);
        // VEYA: r2 sağlandığı için ihlal yok
        Map<String, Object> or = checker().check(spec(url, "hata").rulesJson(rules).ruleLogic("OR").build());
        assertThat(or.get("ok")).isEqualTo(true);
        assertThat(or).doesNotContainKey("level");
    }

    @Test
    @DisplayName("Tek kural, bölge yok: neden SCOPE_NOT_FOUND, seviye izlemeninki (kuralın kritik eşiği yok)")
    void scopeNotFound() throws IOException {
        String url = serve(PAGE);
        String rules = "[{\"id\":\"r1\",\"kind\":\"TEXT\",\"pattern\":\"x\",\"operator\":\"GTE\",\"count\":1,"
                + "\"scope\":{\"type\":\"CSS\",\"value\":\".yok\"}}]";
        Map<String, Object> r = checker().check(spec(url, "x").rulesJson(rules).build());
        assertThat(r.get("ok")).isEqualTo(false);
        assertThat(r.get("failure_reason")).isEqualTo(KeywordFailureClassifier.SCOPE_NOT_FOUND);
        assertThat(r.get("level")).isEqualTo("HIGH");
        assertThat(r).doesNotContainKey("breach_level");
    }

    @Test
    @DisplayName("JSON bölgesi: yol değeri aranır; gövde JSON değilse SCOPE_INVALID")
    void jsonScope() throws IOException {
        contentType.set("application/json");
        String url = serve("{\"status\":\"UP\",\"items\":[{\"name\":\"Ödeme\"}]}");
        String rules = "[{\"id\":\"r1\",\"kind\":\"TEXT\",\"pattern\":\"UP\",\"operator\":\"EQ\",\"count\":1,"
                + "\"case_sensitive\":true,\"scope\":{\"type\":\"JSON\",\"value\":\"$.status\"}}]";
        assertThat(checker().check(spec(url, "UP").rulesJson(rules).build()).get("ok")).isEqualTo(true);
        body.set("<html>bakım</html>");
        Map<String, Object> r = checker().check(spec(url, "UP").rulesJson(rules).build());
        assertThat(r.get("failure_reason")).isEqualTo(KeywordFailureClassifier.SCOPE_INVALID);
    }

    @Test
    @DisplayName("Boyut: üst sınır aşıldı → BODY_TOO_LARGE (kurallar tutsa da), seviye izlemeninki; alt sınır → BODY_TOO_SMALL")
    void sizeLimits() throws IOException {
        String url = serve(PAGE);
        Map<String, Object> big = checker().check(spec(url, "fiyat").maxBytes(50).build());
        assertThat(big.get("ok")).isEqualTo(false);
        assertThat(big.get("size_ok")).isEqualTo(false);
        assertThat(big.get("failure_reason")).isEqualTo(KeywordFailureClassifier.BODY_TOO_LARGE);
        assertThat(big.get("level")).isEqualTo("HIGH");
        Map<String, Object> small = checker().check(spec(url, "fiyat").minBytes(100_000).build());
        assertThat(small.get("failure_reason")).isEqualTo(KeywordFailureClassifier.BODY_TOO_SMALL);
        Map<String, Object> fine = checker().check(spec(url, "fiyat").minBytes(10).maxBytes(100_000).build());
        assertThat(fine.get("ok")).isEqualTo(true);
        assertThat(fine.get("size_ok")).isEqualTo(true);
    }

    @Test
    @DisplayName("Değişim: temel yok → baseline_missing + anlık görüntü çağırana; aynı → false; farklı → true + özet; rakam maskesi")
    void changeDetection() throws IOException {
        String url = serve("<div><p>Kampanya 15.11.2026 tarihine kadar</p><p>Fiyat 1250 TL</p></div>");
        var capture = new KeywordCheckSpec.ChangeCapture();
        Map<String, Object> first = checker().check(spec(url, "Fiyat").changeDetect(true).changeMask("DIGITS")
                .changeCapture(capture).build());
        assertThat(first.get("baseline_missing")).isEqualTo(true);
        assertThat(first).doesNotContainKey("content_changed");
        assertThat(first.get("content_fingerprint")).isNotNull();
        assertThat(capture.snapshot()).isNotNull();
        assertThat(capture.specHash()).isEqualTo(KeywordChangeDetector.specHash("DIGITS", null,
                com.sitemonitor.service.keyword.KeywordRequestRules.requestFingerprint(spec(url, "Fiyat").build())));
        var base = new KeywordChangeDetector.BaselineView(capture.snapshot().fingerprint(), capture.specHash(),
                capture.snapshot().text(), capture.snapshot().textTruncated(), "2026-11-05T06:00:00", "AUTO");
        // rakamlar değişti ama maskeli → değişim yok
        body.set("<div><p>Kampanya 30.12.2026 tarihine kadar</p><p>Fiyat 1399 TL</p></div>");
        Map<String, Object> same = checker().check(spec(url, "Fiyat").changeDetect(true).changeMask("DIGITS").baseline(base).build());
        assertThat(same.get("content_changed")).isEqualTo(false);
        assertThat(same).doesNotContainKeys("change_summary", "baseline_missing");
        assertThat(same.get("baseline_taken_at")).isEqualTo("2026-11-05T06:00:00");
        // metin değişti → değişim + özet
        body.set("<div><p>Kampanya sona erdi</p><p>Fiyat 1250 TL</p></div>");
        Map<String, Object> changed = checker().check(spec(url, "Fiyat").changeDetect(true).changeMask("DIGITS").baseline(base).build());
        assertThat(changed.get("content_changed")).isEqualTo(true);
        @SuppressWarnings("unchecked")
        Map<String, Object> sum = (Map<String, Object>) changed.get("change_summary");
        assertThat(sum.get("added")).isEqualTo(List.of("Kampanya sona erdi"));
        assertThat(changed.get("ok")).as("değişim kontrolün kararını DEĞİŞTİRMEZ").isEqualTo(true);
        // başka maskeyle alınmış temel → yok sayılır
        Map<String, Object> other = checker().check(spec(url, "Fiyat").changeDetect(true).changeMask("DATES").baseline(base).build());
        assertThat(other.get("baseline_missing")).isEqualTo(true);
    }

    @Test
    @DisplayName("Değişim yalnız kabul edilen yanıtta: durum kuralı reddettiyse değişim anahtarı yok, anlık görüntü alınmaz")
    void changeSkippedWhenStatusRejected() throws IOException {
        status.set(503);
        String url = serve("<p>Bakımdayız</p>");
        var capture = new KeywordCheckSpec.ChangeCapture();
        Map<String, Object> r = checker().check(spec(url, "Bakım").changeDetect(true).expectedStatus("2xx").changeCapture(capture).build());
        assertThat(r.get("status_accepted")).isEqualTo(false);
        assertThat(r).doesNotContainKeys("content_fingerprint", "baseline_missing", "content_changed");
        assertThat(capture.snapshot()).isNull();
    }

    @Test
    @DisplayName("checkDetailed: aynı ölçümün içerik görünümü + kural sonuçları döner (canlı eşleşme bağlamı için)")
    void detailedCarriesContent() throws IOException {
        String url = serve(PAGE);
        var d = checker().checkDetailed(spec(url, "fiyat").build());
        assertThat(d.verdict()).isNotNull();
        assertThat(d.verdict().content().pageText()).contains("Fiyat: 1.250 TL");
        var ctx = com.sitemonitor.service.keyword.rules.KeywordMatchContexts.forRules(
                spec(url, "fiyat").build().rules(), d.verdict().evaluation().results(), d.verdict().content(), "RAW", List.of());
        assertThat(ctx).hasSize(1);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> contexts = (List<Map<String, Object>>) ctx.get(0).get("contexts");
        assertThat(contexts).hasSize(2);
        assertThat(contexts.get(0)).containsEntry("match", "fiyat").containsEntry("source", "RAW");
        assertThat(contexts.get(1)).containsEntry("match", "Fiyat");
        assertThat((String) contexts.get(1).get("after")).startsWith(": 1.250 TL");
    }
    // ── Hata avı 2026-11-06 ──────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("hata avı 2026-11-06: derlenemeyen saklı düzenli ifade ('içermemeli') kontrolü sağlıksız yapar — neden RULES_NOT_MET "
            + "(\"kelime bulunamadı\" değil), özet nedeni söyler, kural notu PATTERN_INVALID")
    void storedInvalidRegexIsNotSilentlyGreen() throws IOException {
        String url = serve(PAGE);
        Map<String, Object> r = checker().check(spec(url, "(bozuk")
                .rulesJson("[{\"id\":\"r1\",\"kind\":\"REGEX\",\"pattern\":\"(bozuk\",\"operator\":\"LTE\",\"count\":0}]").build());
        assertThat(r.get("ok")).isEqualTo(false);
        assertThat(r.get("failure_reason")).isEqualTo(KeywordFailureClassifier.RULES_NOT_MET);
        assertThat((String) r.get("failure_detail")).contains("düzenli ifade derlenemedi");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> results = (List<Map<String, Object>>) r.get("rule_results");
        assertThat(results.get(0)).containsEntry("note", "PATTERN_INVALID").containsEntry("ok", false);
    }

    /** 20.186 ve öncesinin {@code spec_hash} biçimi (sürüm 1, istek izi YOK) — yükseltme testinin "eski satırı". */
    private static String legacySpecHash(String maskCsv, String selector) throws Exception {
        String raw = "v1|" + (maskCsv == null ? "" : maskCsv) + "|" + (selector == null ? "" : selector);
        byte[] d = java.security.MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8));
        return java.util.HexFormat.of().formatHex(d).substring(0, 16);
    }

    @Test
    @DisplayName("hata avı 2026-11-06 H4#3: eski biçimde (istek izsiz) saklanmış temel ilk kontrolde BİR KEZ yenilenir — içerik "
            + "değişmiş olsa da karşılaştırılmaz (alarm açılmaz), yeni anlık görüntü çağırana; sonraki kontrol yeni temelle kıyaslar")
    void legacyBaselineIsRefreshedOnceWithoutAlarm() throws Exception {
        String url = serve("<div><p>Kampanya bugün</p><p>Fiyat 1250 TL</p></div>");
        // eski satır: başka bir metnin parmak izi + eski biçim özet (aynı maske / seçici)
        var legacy = new KeywordChangeDetector.BaselineView("e".repeat(64), legacySpecHash("DIGITS", null), "eski metin", false,
                "2026-11-01T06:00:00", "AUTO");
        var capture = new KeywordCheckSpec.ChangeCapture();
        Map<String, Object> first = checker().check(spec(url, "Fiyat").changeDetect(true).changeMask("DIGITS").baseline(legacy)
                .changeCapture(capture).build());
        assertThat(first.get("baseline_missing")).isEqualTo(true);
        assertThat(first).doesNotContainKey("content_changed");   // KEYWORD_CHANGED kalemi yok → alarm açılmaz / kapanmaz
        assertThat(capture.snapshot()).isNotNull();
        assertThat(capture.specHash()).isNotEqualTo(legacy.specHash());
        // zamanlayıcı yenilenmiş temeli alır (refreshIfStale) → sonraki kontrol kıyaslar
        var fresh = new KeywordChangeDetector.BaselineView(capture.snapshot().fingerprint(), capture.specHash(),
                capture.snapshot().text(), capture.snapshot().textTruncated(), "2026-11-06T06:00:00", "AUTO");
        Map<String, Object> second = checker().check(spec(url, "Fiyat").changeDetect(true).changeMask("DIGITS").baseline(fresh).build());
        assertThat(second.get("content_changed")).isEqualTo(false);
        assertThat(second).doesNotContainKey("baseline_missing");
    }

    @Test
    @DisplayName("hata avı 2026-11-06 H4#3: ESKİ URL'yle (bayat görev) alınmış temel yeni URL'nin kontrolünde kullanılmaz — "
            + "baseline_missing (yenilenir), kalıcı sahte KEYWORD_CHANGED olmaz; aynı URL'de kıyaslanır")
    void baselineOfOtherRequestIsNotCompared() throws Exception {
        String url = serve("<p>Eski sayfa: Fiyat 1250 TL</p>");
        var oldCapture = new KeywordCheckSpec.ChangeCapture();
        checker().check(spec(url, "Fiyat").changeDetect(true).changeCapture(oldCapture).build());
        var oldBase = new KeywordChangeDetector.BaselineView(oldCapture.snapshot().fingerprint(), oldCapture.specHash(),
                oldCapture.snapshot().text(), false);
        // aynı sunucu, başka yol (izlemenin URL'si değişti) → içerik farklı ama karşılaştırılmaz
        body.set("<p>Yeni sayfa: tamamen başka içerik, Fiyat 999 TL</p>");
        Map<String, Object> moved = checker().check(spec(url + "yeni", "Fiyat").changeDetect(true).baseline(oldBase).build());
        assertThat(moved.get("baseline_missing")).isEqualTo(true);
        assertThat(moved).doesNotContainKey("content_changed");
        // yöntem / kimlik değişimi de aynı (istek tanımı başka)
        Map<String, Object> other = checker().check(spec(url, "Fiyat").changeDetect(true).basicAuthUser("baska").baseline(oldBase).build());
        assertThat(other.get("baseline_missing")).isEqualTo(true);
        // aynı istek → kıyaslanır (içerik değişti)
        Map<String, Object> same = checker().check(spec(url, "Fiyat").changeDetect(true).baseline(oldBase).build());
        assertThat(same.get("content_changed")).isEqualTo(true);
    }
}
