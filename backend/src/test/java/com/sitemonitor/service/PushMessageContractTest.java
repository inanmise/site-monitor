package com.sitemonitor.service;

import com.sitemonitor.model.AlertEvent;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Push MESAJININ sözleşmesi — kullanıcının telefonunda gördüğü satırın kendisi.
 *
 * <p>Buradaki kapılar üç somut şikâyetten doğdu: mesajda {@code ?} karakterleri, "KRİTİK"in iki
 * kez geçmesi ve adresin tekrarlanması, bir de normale dönüş süresinin 3 saat fazla hesaplanması.
 *
 * <p><b>En kritik kapı yer tutucu kapsamıdır.</b> {@code fillTemplate} yalnız haritada BULUNAN
 * anahtarı değiştirir; bir yer tutucu {@code buildMessage} ya da {@code sendTest} tarafından
 * doldurulmazsa mesajda çıplak {@code {baslangic}} olarak kalır ve bunu hiçbir mevcut test
 * yakalamazdı.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PushMessageContractTest {

    @Mock AppSettingsService appSettings;
    @Mock UserPushDeliveryRepository deliveryRepo;
    @Mock UserPushScopeRepository scopeRepo;
    @Mock UserPushRecipientResolver resolver;
    @Mock AlertEventRepository alertEventRepo;
    @Mock SecretCipher secretCipher;
    @Mock TrustEvaluator trustEvaluator;
    @Mock CaAutoPinService caAutoPinService;

    private UserPushService service;

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([a-zA-Z]+)\\}");
    /** AlertEvent.createdAt biçimi — EscalationService.now() ile AYNI (UTC). */
    private static final DateTimeFormatter STORED =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);

    @BeforeEach
    void setUp() {
        service = new UserPushService(appSettings, deliveryRepo, scopeRepo, resolver,
                alertEventRepo, secretCipher, trustEvaluator, caAutoPinService);
        when(appSettings.getString(anyString(), any())).thenAnswer(i -> i.getArgument(1));
        when(appSettings.getBoolean(anyString(), any(Boolean.class))).thenAnswer(i -> i.getArgument(1));
        when(appSettings.getInt(anyString(), any(Integer.class))).thenAnswer(i -> i.getArgument(1));
        when(deliveryRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        when(deliveryRepo.findTop50ByStatusOrderByIdAsc(anyString())).thenReturn(List.of());
    }

    private static AlertEvent event(String level, String domain, String message, Instant createdAt) {
        AlertEvent e = new AlertEvent();
        e.setId(1L);
        e.setAlertLevel(level);
        e.setAlertType(EscalationService.TYPE_HTTP_DOWN);
        e.setDomain(domain);
        e.setMessage(message);
        e.setCreatedAt(STORED.format(createdAt));
        return e;
    }

    private static Set<String> placeholdersIn(String template) {
        Set<String> found = new LinkedHashSet<>();
        Matcher m = PLACEHOLDER.matcher(template);
        while (m.find()) found.add(m.group(1));
        return found;
    }

    // ── Yer tutucu sözleşmesi ───────────────────────────────────────────────

    @Test
    @DisplayName("KAPI: varsayılan şablonlardaki her yer tutucu KNOWN_PLACEHOLDERS içinde olmalı")
    void defaultTemplates_useOnlyKnownPlaceholders() {
        for (var e : UserPushService.DEFAULT_TEMPLATES.entrySet()) {
            assertThat(UserPushService.KNOWN_PLACEHOLDERS)
                    .as("şablon '%s' bilinmeyen yer tutucu kullanıyor", e.getKey())
                    .containsAll(placeholdersIn(e.getValue()));
        }
    }

    @Test
    @DisplayName("KAPI: buildMessage BÜTÜN bilinen yer tutucuları doldurur (çıplak {x} kalmaz)")
    void buildMessage_fillsEveryKnownPlaceholder() {
        String all = UserPushService.KNOWN_PLACEHOLDERS.stream()
                .map(p -> "{" + p + "}").reduce("", (a, b) -> a + " " + b);
        when(appSettings.getString(eq("site.monitor.userpush.template.down"), any())).thenReturn(all);

        String msg = service.buildMessage(
                event("CRITICAL", "a.example.com", "KRİTİK: a.example.com yanıt vermiyor", Instant.now()),
                "OPEN", Map.of());

        assertThat(msg).as("doldurulmayan yer tutucu mesajda çıplak kalır").doesNotContain("{").doesNotContain("}");
    }

    @Test
    @DisplayName("KAPI: sendTest örnek haritası da BÜTÜN yer tutucuları doldurur")
    void sendTest_fillsEveryKnownPlaceholder() {
        String all = UserPushService.KNOWN_PLACEHOLDERS.stream()
                .map(p -> "{" + p + "}").reduce("", (a, b) -> a + " " + b);
        when(appSettings.getString(eq("site.monitor.userpush.template.test"), any())).thenReturn(all);

        Map<String, Object> out = service.sendTest(List.of("u1"), "test", null);

        assertThat(String.valueOf(out.get("message"))).doesNotContain("{").doesNotContain("}");
    }

    // ── Karakter kümesi ─────────────────────────────────────────────────────

    @Test
    @DisplayName("KAPI: varsayılan şablonların hiçbiri kanalın taşıyamadığı karakter içermez")
    void defaultTemplates_areChannelSafe() {
        for (var e : UserPushService.DEFAULT_TEMPLATES.entrySet()) {
            assertThat(PushText.isChannelSafe(e.getValue()))
                    .as("şablon '%s' kanalda soru işaretine dönecek karakter içeriyor: %s",
                            e.getKey(), e.getValue())
                    .isTrue();
        }
    }

    @Test
    @DisplayName("Üretilen mesaj, alarm metni tipografi içerse bile kanal-güvenli çıkar")
    void builtMessage_isChannelSafe() {
        String msg = service.buildMessage(
                event("CRITICAL", "a.example.com",
                        "KRİTİK: a.example.com sentetik testi başarısız — 1✓/3✗ … 🚨", Instant.now()),
                "OPEN", Map.of());

        assertThat(PushText.isChannelSafe(msg)).isTrue();
        assertThat(msg).doesNotContain("?");
    }

    // ── Mesaj kurgusu ───────────────────────────────────────────────────────

    @Test
    @DisplayName("Seviye BİR kez, adres BİR kez geçer (eskiden seviye iki, adres üç kezdi)")
    void builtMessage_noDuplicateLevelOrDomain() {
        String domain = "http://a.example.com/health";
        String msg = service.buildMessage(
                event("CRITICAL", domain,
                        "KRİTİK: " + domain + " adresine HTTP isteği başarısız. Alarm otomatik kapanır.",
                        Instant.now()),
                "OPEN", Map.of());

        assertThat(countOf(msg, "KRİTİK")).as("seviye tek kez: %s", msg).isEqualTo(1);
        assertThat(countOf(msg, domain)).as("adres tek kez: %s", msg).isEqualTo(1);
    }

    @Test
    @DisplayName("Mesaj sorunun BAŞLAMA saatini taşır")
    void builtMessage_carriesStartClock() {
        Instant created = Instant.parse("2026-06-15T09:30:00Z");
        String msg = service.buildMessage(
                event("CRITICAL", "a.example.com", "KRİTİK: a.example.com yanıt vermiyor", created),
                "OPEN", Map.of());
        assertThat(msg).contains("12:30");   // İstanbul (UTC+3)
    }

    // ── Süre ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Çözüm mesajındaki süre DAKİKALARLA ölçülür — UTC/İstanbul karışımı +3 saat ekliyordu")
    void resolveMessage_durationHasNoTimezoneDrift() {
        String msg = service.buildMessage(
                event("CRITICAL", "a.example.com", "KRİTİK: a.example.com yanıt vermiyor",
                        Instant.now().minus(Duration.ofMinutes(5))),
                "RESOLVE", Map.of());

        assertThat(msg).contains("5 dk");
        assertThat(msg).as("3 saatlik saat dilimi sapması geri gelmemeli: %s", msg).doesNotContain(" sa ");
    }

    @Test
    @DisplayName("Çözüm mesajı hem başlangıç hem bitiş saatini yazar")
    void resolveMessage_carriesBothClocks() {
        Instant created = Instant.parse("2026-06-15T09:30:00Z");
        String msg = service.buildMessage(
                event("CRITICAL", "a.example.com", "KRİTİK: a.example.com yanıt vermiyor", created),
                "RESOLVE", Map.of());
        assertThat(msg).contains("başlangıç 12:30").contains("bitiş");
    }

    private static int countOf(String haystack, String needle) {
        int n = 0, i = 0;
        while ((i = haystack.indexOf(needle, i)) >= 0) { n++; i += needle.length(); }
        return n;
    }

    // ── Denetim 5. tur, bulgu 15 + 23 ─────────────────────────────────────────

    @Test
    @DisplayName("Bulgu 15: sertifika alarmı 'yanıt vermiyor' DEMEZ — kendi şablon ailesi var")
    void certAlert_usesCertTemplate() {
        assertThat(UserPushService.templateKeyFor(EscalationService.TYPE_HOSTNAME_MISMATCH, "OPEN"))
                .isEqualTo("cert");
        assertThat(UserPushService.templateKeyFor(EscalationService.TYPE_UNTRUSTED_CA, "OPEN"))
                .isEqualTo("cert");
        assertThat(UserPushService.templateKeyFor("REVOKED", "OPEN")).isEqualTo("cert");
        assertThat(UserPushService.templateKeyFor("CHAIN_BROKEN", "OPEN")).isEqualTo("cert");
        // Regresyon: diğer aileler yerinde kalır.
        assertThat(UserPushService.templateKeyFor(EscalationService.TYPE_HTTP_DOWN, "OPEN")).isEqualTo("down");
        assertThat(UserPushService.templateKeyFor("EXPIRY", "OPEN")).isEqualTo("expiry");
        assertThat(UserPushService.templateKeyFor(EscalationService.TYPE_HTTP_DOWN, "RESOLVE")).isEqualTo("resolved");
    }

    @Test
    @DisplayName("Bulgu 15: KANIT (IP ve CN) push mesajına ULAŞIR — sebep kırpması onu düşürmez")
    void certAlert_carriesEvidence() {
        AlertEvent e = event("CRITICAL", "olmayan.example.com",
                "KRİTİK: olmayan.example.com adresinde sunulan sertifika BU ALAN ADINI KAPSAMIYOR. "
                        + "Tarayıcılar bağlantıyı reddeder; yanlış yönlendirme ya da DNS ele geçirme olabilir.",
                Instant.now());
        e.setAlertType(EscalationService.TYPE_HOSTNAME_MISMATCH);

        String msg = service.buildMessage(e, "OPEN",
                Map.of("resolved_ip", "192.0.2.55", "subject", "192.0.2.55"));

        assertThat(msg).contains("192.0.2.55");
        assertThat(msg).doesNotContain("yanıt vermiyor");
        assertThat(PushText.isChannelSafe(msg)).isTrue();
    }

    @Test
    @DisplayName("Bulgu 23: {degisen} de seviye önekini ve adres tekrarını kırpar ({neden} ile aynı çekirdek)")
    void changedTemplate_stripsLevelAndDomain() {
        AlertEvent e = event("HIGH", "example.com",
                "UYARI: example.com DNS kaydı değişti — kontrol edin.", Instant.now());
        e.setAlertType(EscalationService.TYPE_DNS_CHANGED);

        String msg = service.buildMessage(e, "OPEN", Map.of());

        assertThat(countOf(msg, "example.com")).as("adres tek kez: %s", msg).isEqualTo(1);
        assertThat(msg).doesNotContain("UYARI: example.com");
    }

    // ── Denetim 5. tur, bulgu 5: teslimat damgalari UTC ───────────────────────
    //
    // Damgalar Istanbul yereliyle yaziliyordu; arayuzdeki toUtc zone tasimayan her damgaya 'Z'
    // ekledigi icin 14:03'te giden push gunlukte 17:03 gorunuyordu. AlertEvent.createdAt UTC
    // oldugu icin ayni ekranda iki damga 3 saat kayik duruyordu.

    @Test
    @DisplayName("KAPI: UserPushDelivery damgasi UTC yazilir (AlertEvent ile simetrik)")
    void deliveryStampIsUtc() {
        org.mockito.ArgumentCaptor<com.sitemonitor.model.UserPushDelivery> cap =
                org.mockito.ArgumentCaptor.forClass(com.sitemonitor.model.UserPushDelivery.class);

        service.sendTest(List.of("u1"), "test", null);

        org.mockito.Mockito.verify(deliveryRepo, org.mockito.Mockito.atLeastOnce()).save(cap.capture());
        String stamp = cap.getAllValues().get(0).getCreatedAt();
        assertThat(stamp).isNotNull();

        Instant parsed = PushText.parseStoredUtc(stamp);
        assertThat(parsed).as("damga UTC olarak ayristirilabilmeli").isNotNull();
        long driftSec = Math.abs(Duration.between(parsed, Instant.now()).toSeconds());
        assertThat(driftSec)
                .as("damga Istanbul yereliyle yazilirsa UTC olarak okundugunda 3 saat sapar: %s", stamp)
                .isLessThan(120);
    }

    // ── Yavaşlık bildirimi ölçüyü TAŞIMALI ──────────────────────────────────
    //
    // "slow" şablonu ölçü ve eşiği olay bağlamından okur. Bağlamdaki adlar tür tür değiştiği
    // (rtt_ms / response_ms / duration_ms, limit_ms / threshold_ms / slow_threshold_ms) ve şablon
    // sabit metric/value/threshold aradığı için bu üçlü telefona "-" olarak düşüyordu: kullanıcı
    // "yavaş" diye bir bildirim alıyor ama NE KADAR yavaş olduğunu göremiyordu.

    @Test
    @DisplayName("KAPI: PING_SLOW bildiriminde ölçüm ve eşik SAYIYLA görünür")
    void pingSlow_carriesMeasurement() {
        AlertEvent e = event("HIGH", "sunucu1.example.com",
                "YÜKSEK: sunucu1.example.com ping yanıt süresi kendi taban çizgisinin üstüne çıktı",
                Instant.now());
        e.setAlertType(EscalationService.TYPE_PING_SLOW);

        String msg = service.buildMessage(e, "OPEN", Map.of(
                "rtt_ms", 240L, "baseline_ms", 150L, "limit_ms", 180L,
                "threshold_percent", 20, "baseline_window_minutes", 10));

        assertThat(msg).as("ölçüm mesajda yok: %s", msg).contains("240 ms");
        assertThat(msg).as("eşik mesajda yok: %s", msg).contains("180 ms");
    }

    @Test
    @DisplayName("KAPI: BÜTÜN yavaşlık türleri ölçü+eşik doldurur (yeni tür eklenince burası da güncellenmeli)")
    void everySlowType_fillsMeasurement() {
        Map<String, Map<String, Object>> ctxByType = new java.util.LinkedHashMap<>();
        ctxByType.put(EscalationService.TYPE_PING_SLOW,     Map.of("rtt_ms", 240L, "limit_ms", 180L));
        ctxByType.put(EscalationService.TYPE_PORT_SLOW,     Map.of("response_ms", 4200L, "threshold_ms", 3000));
        ctxByType.put(EscalationService.TYPE_KEYWORD_SLOW,  Map.of("response_ms", 4200L, "threshold_ms", 3000));
        ctxByType.put(EscalationService.TYPE_DNS_SLOW,      Map.of("response_ms", 1200L, "slow_threshold_ms", 800));
        ctxByType.put(EscalationService.TYPE_SCRIPTED_SLOW, Map.of("duration_ms", 12000L, "threshold_ms", 9000));

        for (var en : ctxByType.entrySet()) {
            AlertEvent e = event("HIGH", "a.example.com", "YÜKSEK: a.example.com yavaş", Instant.now());
            e.setAlertType(en.getKey());
            String msg = service.buildMessage(e, "OPEN", en.getValue());
            assertThat(msg).as("%s: eşik doldurulmamış — %s", en.getKey(), msg).doesNotContain("eşik -");
            assertThat(msg).as("%s: ölçüm doldurulmamış — %s", en.getKey(), msg).doesNotContain("yavaş - yanıt -");
        }
    }

    @Test
    @DisplayName("Üreticinin AÇIK metric/value/threshold anahtarı türetmeyi EZER")
    void explicitContextKeysWin() {
        AlertEvent e = event("HIGH", "a.example.com", "YÜKSEK: a.example.com yavaş", Instant.now());
        e.setAlertType(EscalationService.TYPE_PORT_SLOW);

        String msg = service.buildMessage(e, "OPEN", Map.of(
                "response_ms", 4200L, "threshold_ms", 3000,
                "metric", "el ile", "value", "9 br", "threshold", "5 br"));

        assertThat(msg).contains("el ile", "9 br", "5 br").doesNotContain("4200 ms");
    }
}
