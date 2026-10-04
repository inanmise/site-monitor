package com.sitemonitor.service;

import com.sitemonitor.model.AlertEvent;
import com.sitemonitor.model.AppUser;
import com.sitemonitor.model.EscalationContact;
import com.sitemonitor.model.UserPushDelivery;
import com.sitemonitor.repository.AlertEventRepository;
import com.sitemonitor.repository.AppUserRepository;
import com.sitemonitor.repository.UserPushDeliveryRepository;
import com.sitemonitor.repository.UserPushScopeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Kişisel bildirim tercihleri (2026-10-04, onaylı öneri 4) + KRİTİK tavan muafiyeti (öneri 2) + eskalasyon adımı push'u
 * (öneri 6). Tercih YOKSA çözüm ve gönderim bugünküyle aynıdır (her testin "tercih yok" kolu bunu sınar).
 */
class UserPushPreferencesTest {

    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);
    private static final String GROUPS_ON = """
            {"uzman":{"enabled":true,"source":"orgRole","patterns":["TECH"],"minLevel":"WARNING"}}""";

    static AppUser user(String username) {
        AppUser u = new AppUser();
        u.setId((long) Math.abs(username.hashCode()));
        u.setUsername(username);
        u.setDisplayName("Kişi " + username);
        u.setOrgRole("TECH");
        u.setActive(true);
        u.setTeamIds(new LinkedHashSet<>(Set.of(5L)));
        return u;
    }

    // ── Çözümleyici ─────────────────────────────────────────────────────────────────────────────

    @Nested
    @ExtendWith(MockitoExtension.class)
    @MockitoSettings(strictness = Strictness.LENIENT)
    class Resolver {
        @Mock AppUserRepository userRepo;
        @Mock AppSettingsService appSettings;
        UserPushRecipientResolver resolver;

        @BeforeEach
        void setUp() {
            resolver = new UserPushRecipientResolver(userRepo, appSettings);
            when(appSettings.getString(eq("site.monitor.userpush.role-groups"), anyString())).thenReturn(GROUPS_ON);
        }

        @Test
        @DisplayName("tercih YOK: alıcı bugünkü gibi (neden yok, dil tr) — eski üç alanlı kayıtla eşit")
        void noPreferences_identicalToToday() {
            when(userRepo.findByMembershipTeamId(5L)).thenReturn(List.of(user("N00001")));
            var out = resolver.resolve(5L, "WARNING", List.of("http"));
            assertThat(out).containsExactly(new UserPushRecipientResolver.Recipient("N00001", "Kişi N00001", null));
        }

        @Test
        @DisplayName("SKIPPED_USER_LEVEL: alarm kişinin en düşük seviyesinin altında; eşit/üstü geçer")
        void minLevel() {
            AppUser u = user("N00001");
            u.setPushMinLevel("HIGH");
            when(userRepo.findByMembershipTeamId(5L)).thenReturn(List.of(u));
            assertThat(resolver.resolve(5L, "WARNING").get(0).skipReason()).isEqualTo(UserPushRecipientResolver.SKIPPED_USER_LEVEL);
            assertThat(resolver.resolve(5L, "HIGH").get(0).skipReason()).isNull();
            assertThat(resolver.resolve(5L, "CRITICAL").get(0).skipReason()).isNull();
        }

        @Test
        @DisplayName("SKIPPED_USER_TYPE: aile izin listesinde değil (aile biliniyorsa); bilinmiyorsa süzgeç yok; aile seviye nedenini ezer")
        void families() {
            AppUser u = user("N00001");
            u.setPushFamilies("cert,domain");
            u.setPushMinLevel("CRITICAL");
            when(userRepo.findByMembershipTeamId(5L)).thenReturn(List.of(u));
            assertThat(resolver.resolve(5L, "CRITICAL", List.of("http")).get(0).skipReason()).isEqualTo(UserPushRecipientResolver.SKIPPED_USER_TYPE);
            assertThat(resolver.resolve(5L, "WARNING", List.of("http")).get(0).skipReason())
                    .as("aile kalıcı tercih → seviye nedeninden önce").isEqualTo(UserPushRecipientResolver.SKIPPED_USER_TYPE);
            assertThat(resolver.resolve(5L, "CRITICAL", List.of("cert")).get(0).skipReason()).isNull();
            assertThat(resolver.resolve(5L, "CRITICAL", null).get(0).skipReason()).as("aile bilinmiyor").isNull();
            assertThat(resolver.resolve(5L, "CRITICAL", List.of("http", "domain")).get(0).skipReason()).as("fırtına: bir üye yeter").isNull();
        }

        @Test
        @DisplayName("SKIPPED_USER_SNOOZE: susturma sürerken; 'kritikler yine gelsin' (vars.) KRİTİK'i geçirir, kapalıysa onu da susturur; geçmiş susturma etkisiz")
        void snooze() {
            AppUser u = user("N00001");
            u.setPushSnoozeUntil(ISO.format(Instant.now().plusSeconds(3600)));
            when(userRepo.findByMembershipTeamId(5L)).thenReturn(List.of(u));
            assertThat(resolver.resolve(5L, "HIGH").get(0).skipReason()).isEqualTo(UserPushRecipientResolver.SKIPPED_USER_SNOOZE);
            assertThat(resolver.resolve(5L, "CRITICAL").get(0).skipReason()).isNull();
            u.setPushSnoozeCritical(false);
            assertThat(resolver.resolve(5L, "CRITICAL").get(0).skipReason()).isEqualTo(UserPushRecipientResolver.SKIPPED_USER_SNOOZE);
            u.setPushSnoozeUntil(ISO.format(Instant.now().minusSeconds(60)));
            assertThat(resolver.resolve(5L, "HIGH").get(0).skipReason()).as("süresi dolmuş").isNull();
            u.setPushSnoozeUntil("bozuk");
            assertThat(resolver.resolve(5L, "HIGH").get(0).skipReason()).as("bozuk damga susturmaz").isNull();
        }

        @Test
        @DisplayName("opt-out her tercihten önce; dil alıcıda taşınır")
        void optOutFirst_andLang() {
            AppUser u = user("N00001");
            u.setPushOptOut(true);
            u.setPushMinLevel("CRITICAL");
            u.setPushLang("en");
            when(userRepo.findByMembershipTeamId(5L)).thenReturn(List.of(u));
            var r = resolver.resolve(5L, "WARNING", List.of("http")).get(0);
            assertThat(r.skipReason()).isEqualTo("SKIPPED_USER_OPT_OUT");
            assertThat(r.lang()).isEqualTo("en");
        }

        @Test
        @DisplayName("ÇÖZÜM (resolvePrior) tercihlerden MUAF: susturma/seviye/aile varken de 'düzeldi' gider (yalnız opt-out)")
        void resolvePrior_exempt() {
            AppUser u = user("N00001");
            u.setPushMinLevel("CRITICAL");
            u.setPushFamilies("cert");
            u.setPushSnoozeUntil(ISO.format(Instant.now().plusSeconds(3600)));
            u.setPushSnoozeCritical(false);
            when(userRepo.findByUsername("N00001")).thenReturn(Optional.of(u));
            assertThat(resolver.resolvePrior(List.of("N00001")).get(0).skipReason()).isNull();
        }

        @Test
        @DisplayName("eskalasyon kişisi → kullanıcı: açık bağ > tekil aktif e-posta (harf duyarsız); belirsiz / yok / yalnız pasif → push yok + neden")
        void resolveContact_matching() {
            EscalationContact linked = new EscalationContact();
            linked.setId(1L); linked.setUserId(10L); linked.setEmail("ignored@example.com");
            AppUser lu = user("N00010");
            when(userRepo.findById(10L)).thenReturn(Optional.of(lu));
            assertThat(resolver.resolveContact(linked, "HIGH", null).recipient().username()).isEqualTo("N00010");
            lu.setActive(false);
            assertThat(resolver.resolveContact(linked, "HIGH", null).skipReason()).isEqualTo(UserPushRecipientResolver.SKIPPED_USER_INACTIVE);

            EscalationContact byMail = new EscalationContact();
            byMail.setId(2L); byMail.setEmail("  Ops.Lead@Example.com ");
            AppUser a = user("N00020"); a.setEmail("ops.lead@example.com");
            when(userRepo.findAllByEmailLower("ops.lead@example.com")).thenReturn(List.of(a));
            assertThat(resolver.resolveContact(byMail, "HIGH", null).recipient().username()).isEqualTo("N00020");

            AppUser b = user("N00021"); b.setEmail("ops.lead@example.com");
            when(userRepo.findAllByEmailLower("ops.lead@example.com")).thenReturn(List.of(a, b));
            assertThat(resolver.resolveContact(byMail, "HIGH", null).skipReason()).isEqualTo(UserPushRecipientResolver.SKIPPED_AMBIGUOUS_USER);

            b.setActive(false);
            assertThat(resolver.resolveContact(byMail, "HIGH", null).recipient().username()).as("pasif ikiz belirsizlik yaratmaz").isEqualTo("N00020");
            a.setActive(false);
            assertThat(resolver.resolveContact(byMail, "HIGH", null).skipReason()).isEqualTo(UserPushRecipientResolver.SKIPPED_USER_INACTIVE);

            when(userRepo.findAllByEmailLower("ops.lead@example.com")).thenReturn(List.of());
            assertThat(resolver.resolveContact(byMail, "HIGH", null).skipReason()).isEqualTo(UserPushRecipientResolver.SKIPPED_NO_USER_MATCH);
        }

        @Test
        @DisplayName("eskalasyon kişisi eşleşince kişisel kurallar: opt-out, aile, seviye, susturma (kritik istisnası)")
        void resolveContact_personalRules() {
            EscalationContact c = new EscalationContact();
            c.setId(3L); c.setUserId(30L);
            AppUser u = user("N00030");
            when(userRepo.findById(30L)).thenReturn(Optional.of(u));
            assertThat(resolver.resolveContact(c, "HIGH", List.of("http")).recipient().skipReason()).isNull();
            u.setPushFamilies("cert");
            assertThat(resolver.resolveContact(c, "HIGH", List.of("http")).recipient().skipReason()).isEqualTo(UserPushRecipientResolver.SKIPPED_USER_TYPE);
            u.setPushFamilies(null);
            u.setPushSnoozeUntil(ISO.format(Instant.now().plusSeconds(600)));
            assertThat(resolver.resolveContact(c, "HIGH", null).recipient().skipReason()).isEqualTo(UserPushRecipientResolver.SKIPPED_USER_SNOOZE);
            assertThat(resolver.resolveContact(c, "CRITICAL", null).recipient().skipReason()).isNull();
            u.setPushOptOut(true);
            assertThat(resolver.resolveContact(c, "CRITICAL", null).recipient().skipReason()).isEqualTo("SKIPPED_USER_OPT_OUT");
        }
    }

    // ── Gönderim servisi ────────────────────────────────────────────────────────────────────────

    @Nested
    @ExtendWith(MockitoExtension.class)
    @MockitoSettings(strictness = Strictness.LENIENT)
    class Service {
        @Mock AppSettingsService appSettings;
        @Mock UserPushDeliveryRepository deliveryRepo;
        @Mock UserPushScopeRepository scopeRepo;
        @Mock UserPushRecipientResolver resolver;
        @Mock AlertEventRepository alertEventRepo;
        @Mock SecretCipher secretCipher;
        @Mock TrustEvaluator trustEvaluator;
        @Mock CaAutoPinService caAutoPinService;
        @Mock SystemMaintenanceService maintenance;
        UserPushService service;
        final List<UserPushDelivery> store = new ArrayList<>();

        @BeforeEach
        void setUp() {
            service = new UserPushService(appSettings, deliveryRepo, scopeRepo, resolver,
                    alertEventRepo, secretCipher, trustEvaluator, caAutoPinService);
            service.setSystemMaintenance(maintenance);
            when(appSettings.getString(anyString(), any())).thenAnswer(i -> i.getArgument(1));
            when(appSettings.getBoolean(anyString(), any(Boolean.class))).thenAnswer(i -> i.getArgument(1));
            when(appSettings.getBoolean(eq("site.monitor.userpush.enabled"), any(Boolean.class))).thenReturn(true);
            when(appSettings.getInt(anyString(), any(Integer.class))).thenAnswer(i -> i.getArgument(1));
            when(appSettings.getCsv(anyString(), anyString())).thenReturn(List.of("1"));
            when(scopeRepo.findByScopeTypeAndScopeKey(anyString(), anyString())).thenReturn(Optional.empty());
            when(deliveryRepo.save(any())).thenAnswer(i -> { store.add(i.getArgument(0)); return i.getArgument(0); });
            when(deliveryRepo.findDuePending(anyString(), any())).thenReturn(List.of());
            when(deliveryRepo.existsByAlertEventIdAndDedupeKeyAndUsername(anyLong(), anyString(), anyString())).thenReturn(false);
            when(deliveryRepo.countRecentForUser(anyString(), anyString())).thenReturn(0L);
        }

        AlertEvent event(String level) {
            AlertEvent e = new AlertEvent();
            e.setId(9L); e.setTeamId(5L); e.setAlertType(EscalationService.TYPE_HTTP_DOWN); e.setAlertLevel(level);
            e.setDomain("svc.example.com"); e.setMessage("x"); e.setCreatedAt("2026-10-04T08:00:00");
            when(alertEventRepo.findById(9L)).thenReturn(Optional.of(e));
            return e;
        }

        @Test
        @DisplayName("tercih nedenleri satıra YAZILIR (sessiz kayıp yok); aile süzgeci olayın ailesiyle uygulanır")
        void preferenceSkips_areWrittenAsRows() {
            event("HIGH");
            when(resolver.resolve(5L, "HIGH")).thenReturn(List.of(
                    new UserPushRecipientResolver.Recipient("N00001", "A", UserPushRecipientResolver.SKIPPED_USER_LEVEL),
                    new UserPushRecipientResolver.Recipient("N00002", "B", UserPushRecipientResolver.SKIPPED_USER_SNOOZE),
                    new UserPushRecipientResolver.Recipient("N00003", "C", null, "tr", Set.of("cert")),
                    new UserPushRecipientResolver.Recipient("N00004", "D", null, "tr", Set.of("http"))));
            service.enqueueAlert(9L, "INITIAL", 5L, null);
            assertThat(store).extracting(UserPushDelivery::getUsername, UserPushDelivery::getStatus).containsExactly(
                    org.assertj.core.groups.Tuple.tuple("N00001", "SKIPPED_USER_LEVEL"),
                    org.assertj.core.groups.Tuple.tuple("N00002", "SKIPPED_USER_SNOOZE"),
                    org.assertj.core.groups.Tuple.tuple("N00003", "SKIPPED_USER_TYPE"),
                    org.assertj.core.groups.Tuple.tuple("N00004", "PENDING"));
        }

        @Test
        @DisplayName("KRİTİK tavan muafiyeti: ayar KAPALI (vars.) → tavandaki kişi RATE_LIMITED; AÇIK → KRİTİK gider, YÜKSEK yine tavana takılır")
        void criticalBypass() {
            // doReturn: arka plan kuyruk iş parçacığı (kickDrain) sahteyi çağırırken when(...) yarışı WrongTypeOfReturnValue verir
            doReturn(999L).when(deliveryRepo).countRecentForUser(anyString(), anyString());
            event("CRITICAL");
            when(resolver.resolve(5L, "CRITICAL")).thenReturn(List.of(new UserPushRecipientResolver.Recipient("N00001", "A", null)));
            service.enqueueAlert(9L, "INITIAL", 5L, null);
            assertThat(store.get(0).getStatus()).isEqualTo("RATE_LIMITED");

            store.clear();
            when(appSettings.getBoolean(eq("site.monitor.userpush.critical-bypass-cap"), any(Boolean.class))).thenReturn(true);
            service.enqueueAlert(9L, "ESCALATION", 5L, null);
            assertThat(store.get(0).getStatus()).isEqualTo("PENDING");

            store.clear();
            event("HIGH");
            when(resolver.resolve(5L, "HIGH")).thenReturn(List.of(new UserPushRecipientResolver.Recipient("N00001", "A", null)));
            service.enqueueAlert(9L, "INITIAL", 5L, null);
            assertThat(store.get(0).getStatus()).isEqualTo("RATE_LIMITED");
        }

        @Test
        @DisplayName("kendine test (sendDirect) tercihlerden ve çözümleyiciden BAĞIMSIZ: alıcı çözülmez, satır yazılmaz")
        void selfTest_notAffectedByPreferences() {
            UserPushService.DirectResult r = service.sendSelfTest("N00001", "en");
            assertThat(r.ok()).isFalse();
            assertThat(r.error()).isEqualTo("NOT_CONFIGURED");   // adres yok → gönderim denenmez
            verify(resolver, never()).resolve(any(), any());
            assertThat(store).isEmpty();
        }

        // ── Eskalasyon adımı push'u ─────────────────────────────────────────────────────────────

        EscalationContact contact() {
            EscalationContact c = new EscalationContact();
            c.setId(77L); c.setTeamId(5L); c.setUserId(30L); c.setDelayMinutes(15);
            return c;
        }

        @Test
        @DisplayName("adım push'u: tek aktif kullanıcıya ESCALATION_STEP satırı, metin '[ESKALASYON · 15 dk onaysız] …' (kişinin dilinde), dedupe (alarm, kişi, seviye)")
        void stepPush_writesRow() {
            AlertEvent e = event("HIGH");
            when(resolver.resolveContact(any(), eq("HIGH"), any())).thenReturn(new UserPushRecipientResolver.ContactMatch(
                    new UserPushRecipientResolver.Recipient("N00030", "Otuz", null, "tr"), null));
            assertThat(service.enqueueEscalationStep(e, contact(), 15)).isEqualTo("PENDING");
            UserPushDelivery d = store.get(0);
            assertThat(d.getTrigger()).isEqualTo(UserPushService.TRIGGER_ESCALATION_STEP);
            assertThat(d.getDedupeKey()).isEqualTo("ESC_STEP:77:HIGH");
            assertThat(d.getUsername()).isEqualTo("N00030");
            assertThat(d.getMessage()).startsWith("[ESKALASYON · 15 dk onaysız] YÜKSEK: svc.example.com yanıt vermiyor.");
            assertThat(PushText.isChannelSafe(d.getMessage())).isTrue();

            store.clear();
            when(resolver.resolveContact(any(), eq("HIGH"), any())).thenReturn(new UserPushRecipientResolver.ContactMatch(
                    new UserPushRecipientResolver.Recipient("N00030", "Otuz", null, "en"), null));
            service.enqueueEscalationStep(e, contact(), 15);
            assertThat(store.get(0).getMessage()).startsWith("[ESCALATION · 15 min unacknowledged] HIGH: svc.example.com is not responding.");

            store.clear();
            doReturn(true).when(deliveryRepo).existsByAlertEventIdAndDedupeKeyAndUsername(9L, "ESC_STEP:77:HIGH", "N00030");   // doReturn: kickDrain yarışı
            assertThat(service.enqueueEscalationStep(e, contact(), 15)).isNull();
            assertThat(store).as("aynı (alarm, kişi, seviye) ikinci kez yazılmaz").isEmpty();
        }

        @Test
        @DisplayName("adım push'u: eşleşme yok / belirsiz → push YOK, nedeni sistem satırında; bakım susturması → SKIPPED_SYSTEM_MAINTENANCE")
        void stepPush_noMatch_and_maintenance_leaveTrace() {
            AlertEvent e = event("CRITICAL");
            when(resolver.resolveContact(any(), any(), any())).thenReturn(
                    new UserPushRecipientResolver.ContactMatch(null, UserPushRecipientResolver.SKIPPED_AMBIGUOUS_USER));
            assertThat(service.enqueueEscalationStep(e, contact(), 15)).isEqualTo(UserPushRecipientResolver.SKIPPED_AMBIGUOUS_USER);
            assertThat(store.get(0).getUsername()).isEqualTo(UserPushService.SYSTEM_USER);
            assertThat(store.get(0).getStatus()).isEqualTo(UserPushRecipientResolver.SKIPPED_AMBIGUOUS_USER);
            assertThat(store.get(0).getDisplayName()).isEqualTo("(eskalasyon kişisi #77)");

            store.clear();
            when(maintenance.notificationsMuted()).thenReturn(true);
            assertThat(service.enqueueEscalationStep(e, contact(), 15)).isEqualTo(SystemMaintenanceService.PUSH_SKIPPED);
            verify(resolver, org.mockito.Mockito.times(1)).resolveContact(any(), any(), any());   // bakımda kişi çözülmez
        }

        @Test
        @DisplayName("adım push'u: kişisel neden satırda; saat tavanı (KRİTİK muafiyeti ayarla); ayar kapalı / kanal kapalı → hiçbir şey")
        void stepPush_personalRules_cap_and_gates() {
            AlertEvent e = event("CRITICAL");
            when(resolver.resolveContact(any(), any(), any())).thenReturn(new UserPushRecipientResolver.ContactMatch(
                    new UserPushRecipientResolver.Recipient("N00030", "Otuz", UserPushRecipientResolver.SKIPPED_USER_SNOOZE, "tr"), null));
            assertThat(service.enqueueEscalationStep(e, contact(), 15)).isEqualTo(UserPushRecipientResolver.SKIPPED_USER_SNOOZE);

            store.clear();
            when(resolver.resolveContact(any(), any(), any())).thenReturn(new UserPushRecipientResolver.ContactMatch(
                    new UserPushRecipientResolver.Recipient("N00030", "Otuz", null, "tr"), null));
            doReturn(999L).when(deliveryRepo).countRecentForUser(anyString(), anyString());   // doReturn: kickDrain yarışı
            assertThat(service.enqueueEscalationStep(e, contact(), 15)).isEqualTo("RATE_LIMITED");
            when(appSettings.getBoolean(eq("site.monitor.userpush.critical-bypass-cap"), any(Boolean.class))).thenReturn(true);
            store.clear();
            assertThat(service.enqueueEscalationStep(e, contact(), 15)).isEqualTo("PENDING");

            store.clear();
            when(appSettings.getBoolean(eq("site.monitor.escalation.step-push-enabled"), any(Boolean.class))).thenReturn(false);
            assertThat(service.enqueueEscalationStep(e, contact(), 15)).isNull();
            when(appSettings.getBoolean(eq("site.monitor.escalation.step-push-enabled"), any(Boolean.class))).thenReturn(true);
            when(appSettings.getBoolean(eq("site.monitor.userpush.enabled"), any(Boolean.class))).thenReturn(false);
            assertThat(service.enqueueEscalationStep(e, contact(), 15)).isNull();
            assertThat(store).isEmpty();
        }

        @Test
        @DisplayName("takım bildirimi: seviye/susturma tercih nedenleri geçerli; kişisel sessiz saat (bugünkü gibi) takım bildirimini susturmaz")
        void teamNotice_preferences() {
            when(resolver.resolve(5L, "WARNING")).thenReturn(List.of(
                    new UserPushRecipientResolver.Recipient("N00001", "A", UserPushRecipientResolver.SKIPPED_USER_LEVEL),
                    new UserPushRecipientResolver.Recipient("N00002", "B", UserPushRecipientResolver.SKIPPED_USER_QUIET_HOURS),
                    new UserPushRecipientResolver.Recipient("N00003", "C", null, "tr", Set.of("cert"))));
            Map<String, Object> out = service.enqueueTeamNotice(5L, "SCRIPTED_DISABLED", "WARNING", "SCRIPTED", "x", "m", "k");
            assertThat(out.get("queued")).isEqualTo(1);
            assertThat(store).extracting(UserPushDelivery::getUsername, UserPushDelivery::getStatus).containsExactly(
                    org.assertj.core.groups.Tuple.tuple("N00001", "SKIPPED_USER_LEVEL"),
                    org.assertj.core.groups.Tuple.tuple("N00002", "PENDING"),
                    org.assertj.core.groups.Tuple.tuple("N00003", "SKIPPED_USER_TYPE"));
        }
    }
}
