package com.sitemonitor.service;

import com.sitemonitor.model.AlertEvent;
import com.sitemonitor.model.UserPushDelivery;
import com.sitemonitor.repository.AlertEventRepository;
import com.sitemonitor.repository.UserPushDeliveryRepository;
import com.sitemonitor.repository.UserPushScopeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Push DİLİ (2026-10-04, onaylı öneri 5) — Türkçe çıktı BAYT BAYT bugünkü, İngilizce her kurucu için var, toplu istek dil
 * başına ayrılır.
 *
 * <p>Türkçe altın değerler bilerek SABİT metindir (şablondan yeniden hesaplanmaz): bir yeniden düzenleme Türkçe metni bir
 * harf bile değiştirirse bu test kırmızı olur.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PushLanguageTest {

    @Mock AppSettingsService appSettings;
    @Mock UserPushDeliveryRepository deliveryRepo;
    @Mock UserPushScopeRepository scopeRepo;
    @Mock UserPushRecipientResolver resolver;
    @Mock AlertEventRepository alertEventRepo;
    @Mock SecretCipher secretCipher;
    @Mock TrustEvaluator trustEvaluator;
    @Mock CaAutoPinService caAutoPinService;

    private UserPushService service;
    private final List<UserPushDelivery> store = new ArrayList<>();
    private static final DateTimeFormatter STORED =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);

    @BeforeEach
    void setUp() {
        service = new UserPushService(appSettings, deliveryRepo, scopeRepo, resolver,
                alertEventRepo, secretCipher, trustEvaluator, caAutoPinService);
        when(appSettings.getString(anyString(), any())).thenAnswer(i -> i.getArgument(1));
        when(appSettings.getBoolean(anyString(), any(Boolean.class))).thenAnswer(i -> i.getArgument(1));
        when(appSettings.getBoolean(org.mockito.ArgumentMatchers.eq("site.monitor.userpush.enabled"), any(Boolean.class))).thenReturn(true);
        when(appSettings.getInt(anyString(), any(Integer.class))).thenAnswer(i -> i.getArgument(1));
        when(appSettings.getCsv(anyString(), anyString())).thenReturn(List.of("1"));
        when(scopeRepo.findByScopeTypeAndScopeKey(anyString(), anyString())).thenReturn(Optional.empty());
        when(deliveryRepo.save(any())).thenAnswer(i -> { store.add(i.getArgument(0)); return i.getArgument(0); });
        when(deliveryRepo.findDuePending(anyString(), any())).thenReturn(List.of());   // worker hiçbir şey göndermez
        when(deliveryRepo.existsByAlertEventIdAndDedupeKeyAndUsername(anyLong(), anyString(), anyString())).thenReturn(false);
        when(deliveryRepo.existsByDedupeKeyAndUsername(anyString(), anyString())).thenReturn(false);
        when(deliveryRepo.countRecentForUser(anyString(), anyString())).thenReturn(0L);
    }

    private static AlertEvent event(String type, String level, String domain, String message, String createdAt) {
        AlertEvent e = new AlertEvent();
        e.setId(7L);
        e.setTeamId(3L);
        e.setAlertType(type);
        e.setAlertLevel(level);
        e.setDomain(domain);
        e.setMessage(message);
        e.setCreatedAt(createdAt);
        return e;
    }

    // ── Türkçe: bayt bayt bugünkü ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("TR altın: kesinti şablonu bugünkü metni üretir (dil parametresiz eski yol = tr yolu)")
    void turkish_down_isByteIdentical() {
        AlertEvent e = event(EscalationService.TYPE_HTTP_DOWN, "CRITICAL", "https://shop.example.com",
                "KRİTİK: https://shop.example.com bağlantı zaman aşımı", "2026-10-04T11:05:00");
        String expected = "KRİTİK: https://shop.example.com yanıt vermiyor. Başlangıç 14:05. Bağlantı zaman aşımı";
        assertThat(service.buildMessage(e, "OPEN", null)).isEqualTo(expected);
        assertThat(service.buildMessage(e, "OPEN", null, "tr")).isEqualTo(expected);
        assertThat(service.buildMessage(e, "OPEN", null, null)).as("dil yok = tr").isEqualTo(expected);
    }

    @Test
    @DisplayName("TR altın: süre bitişi (tarih dd.MM.yyyy, 'SSL sertifikası'), yavaşlık (ölçü adı 'yanıt') değişmedi")
    void turkish_expiry_and_slow_areByteIdentical() {
        AlertEvent exp = event("EXPIRY", "HIGH", "api.example.com", "YÜKSEK: api.example.com sertifika bitiyor", "2026-10-04T08:00:00");
        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put("days_remaining", 14);
        ctx.put("not_after", "2026-10-18T20:59:59");
        assertThat(service.buildMessage(exp, "OPEN", ctx))
                .isEqualTo("YÜKSEK: api.example.com - SSL sertifikası 14 gün içinde doluyor (18.10.2026).");

        AlertEvent slow = event(EscalationService.TYPE_HTTP_SLOW, "WARNING", "web.example.com", "UYARI: yavaş", "2026-10-04T08:00:00");
        Map<String, Object> sctx = new LinkedHashMap<>();
        sctx.put("response_ms", 1500);
        sctx.put("threshold_ms", 1000);
        assertThat(service.buildMessage(slow, "OPEN", sctx))
                .isEqualTo("UYARI: web.example.com yavaş - yanıt 1500 ms (eşik 1000 ms). Başlangıç 11:00.");
    }

    @Test
    @DisplayName("TR: süre birimleri ve seviye sözcüğü eski yardımcılarla AYNI (PushText / EscalationService)")
    void turkish_helpers_delegateToLegacy() {
        for (long s : new long[]{45, 300, 7380, 273600}) {
            assertThat(PushI18n.compactDuration(Duration.ofSeconds(s), "tr")).isEqualTo(PushText.compactDuration(Duration.ofSeconds(s)));
        }
        for (String l : new String[]{"CRITICAL", "HIGH", "WARNING", "INFO", null}) {
            assertThat(PushI18n.levelWord(l, "tr")).isEqualTo(EscalationService.levelWordTr(l));
        }
        assertThat(PushI18n.date("2026-09-22T20:59:59", "tr")).isEqualTo(PushText.istDate("2026-09-22T20:59:59"));
        assertThat(PushI18n.expiringWhat("DOMAINMON_EXPIRY", "tr")).isEqualTo(UserPushService.expiringWhat("DOMAINMON_EXPIRY"));
    }

    // ── İngilizce: her kurucu ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("EN: kesinti / süre bitişi / yavaşlık / çözüm şablonları İngilizce, seviye ve tarih/süre biçimi çevrilmiş")
    void english_alarmTemplates() {
        AlertEvent e = event(EscalationService.TYPE_HTTP_DOWN, "CRITICAL", "https://shop.example.com",
                "KRİTİK: https://shop.example.com bağlantı zaman aşımı", "2026-10-04T11:05:00");
        assertThat(service.buildMessage(e, "OPEN", null, "en"))
                .isEqualTo("CRITICAL: https://shop.example.com is not responding. Started 14:05. Bağlantı zaman aşımı");

        AlertEvent exp = event("DOMAINMON_EXPIRY", "WARNING", "example.org", "UYARI: kayıt", "2026-10-04T08:00:00");
        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put("days", 20);
        ctx.put("not_after", "2026-10-24T09:00:00");
        assertThat(service.buildMessage(exp, "OPEN", ctx, "en"))
                .isEqualTo("WARNING: example.org - domain registration expires in 20 days (24 Oct 2026).");

        AlertEvent slow = event(EscalationService.TYPE_HTTP_SLOW, "HIGH", "web.example.com", "YÜKSEK: yavaş", "2026-10-04T08:00:00");
        Map<String, Object> sctx = new LinkedHashMap<>();
        sctx.put("rtt_ms", 300);
        sctx.put("limit_ms", 120);
        assertThat(service.buildMessage(slow, "OPEN", sctx, "en"))
                .isEqualTo("HIGH: web.example.com is slow - ping 300 ms (threshold 120 ms). Started 11:00.");

        AlertEvent resolved = event(EscalationService.TYPE_HTTP_DOWN, "CRITICAL", "svc.example.com", "x",
                STORED.format(Instant.now().minus(Duration.ofMinutes(5)).minusSeconds(10)));
        assertThat(service.buildMessage(resolved, "RESOLVE", null, "en"))
                .startsWith("RESOLVED: svc.example.com is back to normal. Duration 5 min (started ");
        assertThat(service.buildMessage(resolved, "RESOLVE", null, "tr"))
                .startsWith("DÜZELDİ: svc.example.com normale döndü. Süre 5 dk (başlangıç ");
    }

    @Test
    @DisplayName("EN: varsayılan İngilizce şablon her şablon anahtarı için var ve AYNI yer tutucuları kullanır; yönetici EN şablonu kazanır")
    void english_templates_coverEveryKey_andAdminOverrideWins() {
        assertThat(PushI18n.DEFAULT_TEMPLATES_EN.keySet()).containsExactlyInAnyOrderElementsOf(UserPushService.DEFAULT_TEMPLATES.keySet());
        for (String v : PushI18n.DEFAULT_TEMPLATES_EN.values()) {
            for (String ph : v.replaceAll("[^{}a-z_]", " ").split("\\s+")) {
                if (ph.startsWith("{")) assertThat(UserPushService.KNOWN_PLACEHOLDERS).contains(ph.replaceAll("[{}]", ""));
            }
            assertThat(PushText.isChannelSafe(v)).as("kanal güvenli: %s", v).isTrue();
        }
        when(appSettings.getString(org.mockito.ArgumentMatchers.eq("site.monitor.userpush.template.down.en"), any()))
                .thenReturn("{seviye} ALERT {ad}");
        AlertEvent e = event(EscalationService.TYPE_HTTP_DOWN, "HIGH", "x.example.com", "x", "2026-10-04T08:00:00");
        assertThat(service.buildMessage(e, "OPEN", null, "en")).isEqualTo("HIGH ALERT x.example.com");
        assertThat(service.buildMessage(e, "OPEN", null, "tr")).startsWith("YÜKSEK: x.example.com yanıt vermiyor");
    }

    @Test
    @DisplayName("EN: süre birimleri '45 s / 5 min / 2 h 3 min / 3 d 4 h'")
    void english_compactDuration() {
        assertThat(PushI18n.compactDuration(Duration.ofSeconds(45), "en")).isEqualTo("45 s");
        assertThat(PushI18n.compactDuration(Duration.ofMinutes(5), "en")).isEqualTo("5 min");
        assertThat(PushI18n.compactDuration(Duration.ofMinutes(123), "en")).isEqualTo("2 h 3 min");
        assertThat(PushI18n.compactDuration(Duration.ofHours(76), "en")).isEqualTo("3 d 4 h");
        assertThat(PushI18n.compactDuration(Duration.ofHours(2), "en")).isEqualTo("2 h");
    }

    @Test
    @DisplayName("EN/TR: özet, eskalasyon adımı öneki ve kendine test metinleri iki dilde; kanal güvenli")
    void channelTexts_bothLanguages() {
        Map<String, Integer> lv = new LinkedHashMap<>();
        lv.put("WARNING", 11);
        lv.put("CRITICAL", 3);
        PushI18n.OverflowLast last = new PushI18n.OverflowLast("site-x", "CRITICAL", "OPEN", "2026-10-04T11:05:00");
        assertThat(PushI18n.overflowSummary(14, lv, last, "tr")).isEqualTo(
                "SiteMonitor: saat tavanı nedeniyle 14 bildirim gönderilmedi (3 kritik, 11 uyarı). Son: site-x - KRİTİK (14:05). Ayrıntılar SiteMonitor'da.");
        assertThat(PushI18n.overflowSummary(14, lv, last, "en")).isEqualTo(
                "SiteMonitor: 14 notifications were held back by the hourly limit (3 critical, 11 warning). Latest: site-x - CRITICAL (14:05). Details in SiteMonitor.");
        assertThat(PushI18n.overflowSummary(1, Map.of("HIGH", 1),
                new PushI18n.OverflowLast("Alarm fırtınası", "HIGH", "STORM", "2026-10-04T11:05:00"), "en"))
                .contains("1 notification was held back").contains("Latest: Alert storm - HIGH");
        assertThat(PushI18n.escalationStepPrefix(15, "tr")).isEqualTo("[ESKALASYON · 15 dk onaysız] ");
        assertThat(PushI18n.escalationStepPrefix(15, "en")).isEqualTo("[ESCALATION · 15 min unacknowledged] ");
        Instant now = Instant.parse("2026-10-04T11:05:00Z");
        assertThat(PushI18n.selfTest(now, "tr")).isEqualTo("SiteMonitor test bildirimi: push kanalınız çalışıyor (14:05).");
        assertThat(PushI18n.selfTest(now, "en")).isEqualTo("SiteMonitor test notification: your push channel works (14:05).");
        for (String s : List.of(PushI18n.overflowSummary(14, lv, last, "tr"), PushI18n.escalationStepPrefix(15, "tr"),
                PushI18n.selfTest(now, "tr"))) {
            assertThat(PushText.isChannelSafe(s)).as(s).isTrue();
        }
    }

    @Test
    @DisplayName("Takım/fırtına bildirimi iki dilli: İngilizce alıcı İngilizce metni alır; İngilizce metin yoksa Türkçe (eski çağıranlar)")
    void localizedText_forLang() {
        UserPushService.LocalizedText t = new UserPushService.LocalizedText("Türkçe metin", "English text");
        assertThat(t.forLang("tr")).isEqualTo("Türkçe metin");
        assertThat(t.forLang("en")).isEqualTo("English text");
        assertThat(t.forLang(null)).isEqualTo("Türkçe metin");
        assertThat(UserPushService.LocalizedText.of("Yalnız Türkçe").forLang("en")).isEqualTo("Yalnız Türkçe");
    }

    // ── Kişi başına dil + toplu istek ayrımı ──────────────────────────────────────────────────

    @Test
    @DisplayName("Alarm push'u alıcının dilinde kurulur; TR ve EN alıcı AYRI toplu isteğe (batch) düşer, TR satırı bugünküyle aynı")
    void perRecipientLanguage_splitsBatches() {
        AlertEvent e = event(EscalationService.TYPE_HTTP_DOWN, "CRITICAL", "https://shop.example.com",
                "KRİTİK: https://shop.example.com bağlantı zaman aşımı", "2026-10-04T11:05:00");
        when(alertEventRepo.findById(7L)).thenReturn(Optional.of(e));
        when(resolver.resolve(3L, "CRITICAL")).thenReturn(List.of(
                new UserPushRecipientResolver.Recipient("N00001", "Bir", null, "tr"),
                new UserPushRecipientResolver.Recipient("N00002", "İki", null, "en"),
                new UserPushRecipientResolver.Recipient("N00003", "Üç", null, "tr")));
        service.enqueueAlert(7L, "INITIAL", 3L, null);

        assertThat(store).hasSize(3);
        UserPushDelivery tr1 = store.get(0), en = store.get(1), tr2 = store.get(2);
        assertThat(tr1.getMessage()).isEqualTo("KRİTİK: https://shop.example.com yanıt vermiyor. Başlangıç 14:05. Bağlantı zaman aşımı");
        assertThat(tr2.getMessage()).isEqualTo(tr1.getMessage());
        assertThat(en.getMessage()).startsWith("CRITICAL: https://shop.example.com is not responding.");
        assertThat(tr1.getPushLang()).isEqualTo("tr");
        assertThat(en.getPushLang()).isEqualTo("en");
        assertThat(tr1.getBatchId()).isEqualTo(tr2.getBatchId());
        assertThat(en.getBatchId()).isNotEqualTo(tr1.getBatchId()).isEqualTo(tr1.getBatchId() + "-en");
        assertThat(tr1.getTitle()).isEqualTo("Site Monitor");
    }

    @Test
    @DisplayName("İngilizce başlık: ayar boşsa Türkçe başlık ayarı (ürün adı çevrilmez), doluysa İngilizce başlık")
    void englishTitle_fallsBackToTurkishTitle() {
        when(appSettings.getString(org.mockito.ArgumentMatchers.eq("site.monitor.userpush.title"), any())).thenReturn("Kurum Alarm");
        assertThat(service.titleSetting("en")).isEqualTo("Kurum Alarm");
        when(appSettings.getString(org.mockito.ArgumentMatchers.eq("site.monitor.userpush.title.en"), any())).thenReturn("Org Alerts");
        assertThat(service.titleSetting("en")).isEqualTo("Org Alerts");
        assertThat(service.titleSetting("tr")).isEqualTo("Kurum Alarm");
    }

    @Test
    @DisplayName("Takım bildirimi iki dilli: dil başına ayrı metin + batch; Türkçe metin değişmedi")
    void teamNotice_localizedPerRecipient() {
        when(resolver.resolve(5L, "WARNING")).thenReturn(List.of(
                new UserPushRecipientResolver.Recipient("N00011", "A", null, "tr"),
                new UserPushRecipientResolver.Recipient("N00012", "B", null, "en")));
        Map<String, Object> out = service.enqueueTeamNoticeLocalized(5L, "WEEKLY_REPORT", "WARNING", "WEEKLY_REPORT", "T 2026-W40",
                new UserPushService.LocalizedText("[Haftalık rapor] T onaylandı", "[Weekly report] T was approved"), "WR:1", java.util.Set.of());
        assertThat(out.get("queued")).isEqualTo(2);
        assertThat(store).extracting(UserPushDelivery::getMessage)
                .containsExactly("[Haftalık rapor] T onaylandı", "[Weekly report] T was approved");
        assertThat(store.get(0).getBatchId()).isNotEqualTo(store.get(1).getBatchId());
    }

    @Test
    @DisplayName("Doğrudan bildirim (haftalık rapor → müdür) kişinin dilinde; dil bilgisi yoksa Türkçe")
    void direct_localized() {
        service.enqueueDirectLocalized(List.of(
                        new UserPushService.DirectRecipient("M00001", "Müdür", false, "en"),
                        new UserPushService.DirectRecipient("M00002", "Müdür 2", false)),
                5L, "WEEKLY_REPORT", "WARNING", "WEEKLY_REPORT", "T",
                new UserPushService.LocalizedText("tr metin", "en text"), "k:MGR");
        assertThat(store).extracting(UserPushDelivery::getMessage).containsExactly("en text", "tr metin");
    }
}
