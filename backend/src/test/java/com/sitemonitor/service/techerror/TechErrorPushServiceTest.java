package com.sitemonitor.service.techerror;

import com.sitemonitor.model.AppUser;
import com.sitemonitor.repository.AppUserRepository;
import com.sitemonitor.service.AppSettingsService;
import com.sitemonitor.service.PushText;
import com.sitemonitor.service.UserPushService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Teknik hata raporu push bildirimleri (2026-10-18) — alıcılar, dışlamalar, tekilleştirme anahtarları, ayar anahtarları,
 * modül görünürlüğü, pasif hesap, dil, kişisel tercih satırı, hata yalıtımı ve metin kuralları (push kanalı ISO-8859-9:
 * {@link PushText#pushSafe} metni DEĞİŞTİRMEZ; bağlantı yok).
 */
class TechErrorPushServiceTest {

    private final AppSettingsService appSettings = mock(AppSettingsService.class);
    private final AppUserRepository users = mock(AppUserRepository.class);
    private final UserPushService push = mock(UserPushService.class);
    private final TechErrorVisibilityService visibility = mock(TechErrorVisibilityService.class);
    private final Map<String, String> settings = new HashMap<>();
    private final Map<Long, AppUser> byId = new HashMap<>();
    private TechErrorPushService svc;

    private static final Instant AT = Instant.parse("2026-10-05T06:15:00Z");
    private static final TechErrorPushService.Ctx CTX = new TechErrorPushService.Ctx(42L, 7L, "Mobil Bankacılık", 2026, 9);

    @BeforeEach
    void setUp() {
        when(appSettings.getBoolean(anyString(), anyBoolean())).thenAnswer(inv -> {
            String v = settings.get((String) inv.getArgument(0));
            return v == null ? inv.getArgument(1) : Boolean.parseBoolean(v);
        });
        when(users.findById(anyLong())).thenAnswer(inv -> Optional.ofNullable(byId.get((Long) inv.getArgument(0))));
        when(users.findAllById(any())).thenAnswer(inv -> {
            List<AppUser> out = new ArrayList<>();
            for (Object id : (Iterable<?>) inv.getArgument(0)) if (byId.containsKey(id)) out.add(byId.get(id));
            return out;
        });
        when(push.enqueueDirectLocalized(any(), any(), anyString(), anyString(), anyString(), anyString(), any(), anyString()))
                .thenAnswer(inv -> Map.of("queued", ((List<?>) inv.getArgument(0)).size()));
        when(push.enqueueTeamNoticeLocalized(any(), anyString(), anyString(), anyString(), anyString(), any(), anyString(), any()))
                .thenReturn(Map.of("queued", 3));
        when(visibility.teamVisible(any())).thenReturn(true);
        svc = new TechErrorPushService(appSettings, users);
        svc.setPush(push);
        svc.setVisibility(visibility);
        user(1L, "po1", "PO Bir", "po1@example.com", "tr");
        user(2L, "po2", "PO İki", "ortak@example.com", "en");
        user(3L, "po3", "PO Üç", null, "tr");                 // e-postası yok: uygulama içinde onaylar
        user(4L, "po4", "PO Dört", "ORTAK@example.com", "tr"); // aynı kutuyu paylaşan PO (onaylayıcı listesinde adres null)
        user(9L, "tech", "Teknik Kişi", "tech@example.com", "tr");
    }

    private AppUser user(Long id, String name, String display, String mail, String lang) {
        AppUser u = new AppUser();
        u.setId(id);
        u.setUsername(name);
        u.setDisplayName(display);
        u.setEmail(mail);
        u.setPushLang(lang);
        u.setActive(true);
        byId.put(id, u);
        return u;
    }

    private static List<TechErrorApprovalService.Approver> approvers(Long... ids) {
        List<TechErrorApprovalService.Approver> out = new ArrayList<>();
        for (Long id : ids) out.add(new TechErrorApprovalService.Approver(id, "PO " + id, id == 3L || id == 4L ? null : "po" + id + "@example.com"));
        return out;
    }

    @SuppressWarnings("unchecked")
    private List<List<UserPushService.DirectRecipient>> directCalls(int n, ArgumentCaptor<UserPushService.LocalizedText> text,
                                                                    ArgumentCaptor<String> key, String trigger) {
        ArgumentCaptor<List<UserPushService.DirectRecipient>> list = ArgumentCaptor.forClass(List.class);
        verify(push, times(n)).enqueueDirectLocalized(list.capture(), eq(7L), eq(trigger), eq("WARNING"), eq("TECH_ERROR_REPORT"),
                eq("Mobil Bankacılık Eylül 2026"), text.capture(), key.capture());
        return list.getAllValues();
    }

    private static List<String> names(List<UserPushService.DirectRecipient> l) {
        return l.stream().map(UserPushService.DirectRecipient::username).toList();
    }

    // ── 1. Onay isteği ────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("onay isteği: postayla aynı PO'lar (e-postası olmayan dahil), isteyen HİÇ; postası ulaşana (ortak kutu dahil) 'e-postanızdan', ulaşmayana 'size ulaşmadı'; dil kişinin")
    void approvalRequestRecipients() {
        int queued = svc.approvalRequested(CTX, approvers(1L, 2L, 3L, 4L, 9L), 9L, "Teknik Kişi", false,
                List.of("po1@example.com", "ortak@example.com"), AT);
        ArgumentCaptor<UserPushService.LocalizedText> text = ArgumentCaptor.forClass(UserPushService.LocalizedText.class);
        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        List<List<UserPushService.DirectRecipient>> calls = directCalls(2, text, key, TechErrorPushService.TRIGGER_APPROVAL);
        assertThat(queued).isEqualTo(4);
        assertThat(names(calls.get(0))).containsExactly("po1", "po2", "po4");          // ortak kutu: po4'ün adresi de ulaştı
        assertThat(names(calls.get(1))).containsExactly("po3");                        // e-postası yok
        assertThat(calls.get(0).get(1).lang()).isEqualTo("en");
        assertThat(text.getAllValues().get(0).tr()).contains("onayınızı bekliyor").contains("e-postanızdan").contains("Talep eden: Teknik Kişi.");
        assertThat(text.getAllValues().get(1).tr()).contains("Onay e-postası size ulaşmadı");
        assertThat(text.getAllValues().get(1).en()).contains("did not reach you").contains("Requested by: Teknik Kişi.");
        assertThat(key.getAllValues()).containsOnly("te-appr:42:" + AT.toEpochMilli());
    }

    @Test
    @DisplayName("yeniden gönderilen istek: ayrı anahtar + 'Hatırlatma' metni; posta hiç gitmediyse (PDF sırası dolu) herkes 'ulaşmadı' alır")
    void approvalResend() {
        svc.approvalRequested(CTX, approvers(1L, 2L), 9L, "Teknik Kişi", true, List.of(), AT);
        ArgumentCaptor<UserPushService.LocalizedText> text = ArgumentCaptor.forClass(UserPushService.LocalizedText.class);
        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        List<List<UserPushService.DirectRecipient>> calls = directCalls(1, text, key, TechErrorPushService.TRIGGER_APPROVAL);
        assertThat(names(calls.get(0))).containsExactly("po1", "po2");
        assertThat(key.getValue()).isEqualTo("te-appr-resend:42:" + AT.toEpochMilli());
        assertThat(text.getValue().tr()).startsWith("[Teknik hata raporu] Hatırlatma: Mobil Bankacılık Eylül 2026 raporu hâlâ onayınızı bekliyor")
                .contains("size ulaşmadı");
        assertThat(text.getValue().en()).startsWith("[Technical error report] Reminder: Mobil Bankacılık September 2026 is still waiting");
    }

    @Test
    @DisplayName("pasif PO, kişisel tercih (asgari seviye HIGH → satır SKIPPED_USER_LEVEL), opt-out bayrağı servise taşınır")
    void passiveAndPreferences() {
        byId.get(2L).setActive(false);
        byId.get(1L).setPushMinLevel("HIGH");
        byId.get(3L).setPushOptOut(true);
        svc.approvalRequested(CTX, approvers(1L, 2L, 3L), null, null, false, List.of("po1@example.com"), AT);
        ArgumentCaptor<UserPushService.LocalizedText> text = ArgumentCaptor.forClass(UserPushService.LocalizedText.class);
        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        List<List<UserPushService.DirectRecipient>> calls = directCalls(2, text, key, TechErrorPushService.TRIGGER_APPROVAL);
        assertThat(names(calls.get(0))).containsExactly("po1");
        assertThat(calls.get(0).get(0).skipReason()).isEqualTo("SKIPPED_USER_LEVEL");
        assertThat(names(calls.get(1))).containsExactly("po3");
        assertThat(calls.get(1).get(0).optOut()).isTrue();
        assertThat(text.getAllValues().get(0).tr()).doesNotContain("Talep eden");   // isteyen adı yoksa satır yok
    }

    // ── Ayar / görünürlük / hata yalıtımı ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("her olayın kendi anahtarı: kapalıysa o olay için HİÇ push yok, diğerleri sürer")
    void togglesOff() {
        for (String k : TechErrorReportSettings.PUSH_KEYS) settings.put(k, "false");
        svc.approvalRequested(CTX, approvers(1L), 9L, "Teknik Kişi", false, List.of(), AT);
        assertThat(svc.approvalReminder(CTX, approvers(1L), 9L, "Teknik Kişi", 3, AT, "2026-10-05")).isEmpty();
        svc.decided(CTX, 9L, 1L, "PO Bir", true, null, "SENT", null, AT);
        svc.delivered(CTX, false, 9L, "Teknik Kişi", 2L, AT, List.of());
        svc.sendFailed(CTX, false, 9L, TechErrorPushService.Failure.FAILED, null, AT);
        verify(push, never()).enqueueDirectLocalized(any(), any(), anyString(), anyString(), anyString(), anyString(), any(), anyString());
        verify(push, never()).enqueueTeamNoticeLocalized(any(), anyString(), anyString(), anyString(), anyString(), any(), anyString(), any());
        assertThat(svc.decisionGoesTo(9L, 1L)).isFalse();

        settings.put(TechErrorReportSettings.KEY_PUSH_DELIVERED, "true");
        svc.delivered(CTX, false, 9L, "Teknik Kişi", 2L, AT, List.of());
        verify(push).enqueueTeamNoticeLocalized(eq(7L), eq(TechErrorPushService.TRIGGER_SENT), eq("WARNING"), eq("TECH_ERROR_REPORT"),
                anyString(), any(), anyString(), any());
    }

    @Test
    @DisplayName("modül takıma kapalı → push yok; push servisi yok → sessiz; enqueue hatası çağırana yayılmaz")
    void hiddenModuleAndFailureIsolation() {
        when(visibility.teamVisible(7L)).thenReturn(false);
        svc.approvalRequested(CTX, approvers(1L), 9L, "Teknik Kişi", false, List.of(), AT);
        svc.delivered(CTX, true, 9L, "Teknik Kişi", 2L, AT, List.of());
        verify(push, never()).enqueueDirectLocalized(any(), any(), anyString(), anyString(), anyString(), anyString(), any(), anyString());
        verify(push, never()).enqueueTeamNoticeLocalized(any(), anyString(), anyString(), anyString(), anyString(), any(), anyString(), any());

        when(visibility.teamVisible(7L)).thenReturn(true);
        doThrow(new IllegalStateException("db down")).when(push)
                .enqueueDirectLocalized(any(), any(), anyString(), anyString(), anyString(), anyString(), any(), anyString());
        doThrow(new IllegalStateException("db down")).when(push)
                .enqueueTeamNoticeLocalized(any(), anyString(), anyString(), anyString(), anyString(), any(), anyString(), any());
        assertThatCode(() -> {
            assertThat(svc.approvalRequested(CTX, approvers(1L), 9L, "Teknik Kişi", false, List.of(), AT)).isZero();
            assertThat(svc.approvalReminder(CTX, approvers(1L), 9L, "Teknik Kişi", 3, AT, "2026-10-05")).isEmpty();
            svc.decided(CTX, 9L, 1L, "PO Bir", false, "not", null, null, AT);
            svc.delivered(CTX, false, 9L, "Teknik Kişi", 2L, AT, List.of());
            svc.sendFailed(CTX, false, 9L, TechErrorPushService.Failure.FAILED, null, AT);
        }).doesNotThrowAnyException();

        TechErrorPushService none = new TechErrorPushService(appSettings, users);
        assertThatCode(() -> none.delivered(CTX, false, 9L, "x", 1L, AT, List.of())).doesNotThrowAnyException();
        assertThat(none.approvalReminder(CTX, approvers(1L), 9L, "x", 3, AT, "2026-10-05")).isEmpty();
    }

    // ── 2. Onay hatırlatması ──────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("onay hatırlatması: tüm PO'lar (e-postasız dahil, isteyen hariç), gün taşıyan anahtar, N gün metni; push'u kuyruğa alınan PO kimlikleri döner")
    void approvalReminder() {
        Set<Long> queued = svc.approvalReminder(CTX, approvers(1L, 3L, 9L), 9L, "Teknik Kişi", 1, AT, "2026-10-05");
        assertThat(queued).containsExactly(1L, 3L);
        ArgumentCaptor<UserPushService.LocalizedText> text = ArgumentCaptor.forClass(UserPushService.LocalizedText.class);
        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        List<List<UserPushService.DirectRecipient>> calls = directCalls(1, text, key, TechErrorPushService.TRIGGER_APPROVAL_REMINDER);
        assertThat(names(calls.get(0))).containsExactly("po1", "po3");
        assertThat(key.getValue()).isEqualTo("te-appr-remind:42:" + AT.toEpochMilli() + ":2026-10-05");
        assertThat(text.getValue().tr()).contains("1 gündür onayınızı bekliyor");
        assertThat(text.getValue().en()).contains("for 1 day;");
        assertThat(TechErrorPushService.reminderText(CTX, 3, "x").en()).contains("for 3 days;");
        assertThat(svc.approvalReminder(CTX, approvers(9L), 9L, "Teknik Kişi", 1, AT, "2026-10-05")).isEmpty();   // yalnız isteyen
    }

    @Test
    @DisplayName("inceleme 2026-10-18: kimin push'u kuyrukta — servis queued_usernames verirse kişi kişi; vermeyen sonuçta yalnız herkes kuyruktaysa herkes, aksi hâlde kimse")
    void approvalReminderReportsWhoWasQueued() {
        org.mockito.Mockito.doReturn(Map.of("queued", 1, "queued_usernames", List.of("po3"))).when(push)
                .enqueueDirectLocalized(any(), any(), anyString(), anyString(), anyString(), anyString(), any(), anyString());
        assertThat(svc.approvalReminder(CTX, approvers(1L, 3L), 9L, "Teknik Kişi", 2, AT, "2026-10-05")).containsExactly(3L);
        List<AppUser> people = List.of(byId.get(1L), byId.get(3L));
        assertThat(TechErrorPushService.queuedIds(people, Map.of("queued", 2))).containsExactly(1L, 3L);
        assertThat(TechErrorPushService.queuedIds(people, Map.of("queued", 1))).isEmpty();     // kim olduğu bilinmiyor
        assertThat(TechErrorPushService.queuedIds(people, Map.of("queued", 0, "queued_usernames", List.of()))).isEmpty();
        assertThat(TechErrorPushService.queuedIds(people, null)).isEmpty();
    }

    // ── 3. Karar ──────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("karar → isteyen: onay + gönderim sonucu (gönderildi / kuyrukta / PDF sırası dolu — ham kod yok), red → 'ret notunu Site Monitor'de görüntüleyin' (not push'a YAZILMAZ); kendi kararı ve pasif isteyen atlanır")
    void decision() {
        svc.decided(CTX, 9L, 1L, "PO Bir", true, null, "SENT", null, AT);
        svc.decided(CTX, 9L, 1L, "PO Bir", true, null, "QUEUED", null, AT.plusSeconds(1));
        svc.decided(CTX, 9L, 1L, "PO Bir", true, null, null, "PDF_BUSY", AT.plusSeconds(2));
        String note = "Tablo eksik; bkz. https://wiki.corp.local/te ve ayse@banka.com.tr — Srv01.Corp.Local'a bakın.";
        svc.decided(CTX, 9L, 1L, "PO Bir", false, note, null, null, AT.plusSeconds(3));
        ArgumentCaptor<UserPushService.LocalizedText> text = ArgumentCaptor.forClass(UserPushService.LocalizedText.class);
        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        List<List<UserPushService.DirectRecipient>> calls = directCalls(4, text, key, TechErrorPushService.TRIGGER_DECISION);
        assertThat(calls).allSatisfy(l -> assertThat(names(l)).containsExactly("tech"));
        List<UserPushService.LocalizedText> t = text.getAllValues();
        assertThat(t.get(0).tr()).contains("onaylandı ve yöneticilere gönderildi").endsWith("Onaylayan: PO Bir.");
        assertThat(t.get(1).tr()).contains("kuyrukta");
        assertThat(t.get(2).tr()).contains("PDF sırası dolu").contains("yeniden gönderin").doesNotContain("PDF_BUSY").doesNotContain("BAŞARISIZ");
        assertThat(t.get(2).en()).contains("PDF queue busy").contains("send it again").doesNotContain("PDF_BUSY");
        // Red: notun KENDİSİ hiçbir dilde push'ta yok (push uygulaması kurum ağının dışında — karar 2026-10-18)
        assertThat(t.get(3).tr()).isEqualTo("[Teknik hata raporu] Mobil Bankacılık Eylül 2026 raporu reddedildi. Ret notu Site Monitor'de. Reddeden: PO Bir.");
        assertThat(t.get(3).en()).isEqualTo("[Technical error report] Mobil Bankacılık September 2026 was rejected. See note in Site Monitor. Rejected by: PO Bir.");
        for (String s : new String[] { t.get(3).tr(), t.get(3).en() }) {
            assertThat(s).doesNotContain("Tablo").doesNotContain("wiki").doesNotContain("@").doesNotContain("Srv01").doesNotContain("Corp");
        }
        assertThat(key.getAllValues()).containsExactly("te-decision:42:" + AT.toEpochMilli(), "te-decision:42:" + (AT.toEpochMilli() + 1000),
                "te-decision:42:" + (AT.toEpochMilli() + 2000), "te-decision:42:" + (AT.toEpochMilli() + 3000));

        // isteyen kararı kendisi verdi / isteyen yok / pasif → hiç push
        svc.decided(CTX, 1L, 1L, "PO Bir", true, null, "SENT", null, AT);
        svc.decided(CTX, null, 1L, "PO Bir", true, null, "SENT", null, AT);
        byId.get(9L).setActive(false);
        svc.decided(CTX, 9L, 1L, "PO Bir", true, null, "SENT", null, AT);
        verify(push, times(4)).enqueueDirectLocalized(any(), any(), anyString(), anyString(), anyString(), anyString(), any(), anyString());
        assertThat(svc.decisionGoesTo(1L, 1L)).isFalse();
        assertThat(svc.decisionGoesTo(9L, 1L)).isTrue();
    }

    @Test
    @DisplayName("inceleme 2026-10-18: onaydan sonraki gönderimin her sonucu KENDİ cümlesi (atlandı ≠ başarısız, eskiyen onay yeniden onay ister, zaten gönderiliyor, PDF sırası dolu, PDF çok büyük, ulaşmadı); ham kod asla metinde değil, TR + EN")
    void approvedOutcomePhrases() {
        Map<String, String[]> want = new java.util.LinkedHashMap<>();
        want.put("SENT", new String[] { "onaylandı ve yöneticilere gönderildi", "was approved and sent to the executives" });
        want.put("QUEUED", new String[] { "kuyrukta", "queued for the executives" });
        want.put("SKIPPED", new String[] { "E-posta kanalı kapalı ya da etkin alıcı yok", "The e-mail channel is off or there is no active recipient" });
        want.put("APPROVAL_STALE", new String[] { "değişti; gönderilmedi. Onaydan sonra değişti; yeniden onaya gönderin", "submit it for approval again" });
        want.put("SEND_IN_PROGRESS", new String[] { "zaten gönderiliyor. Sonuç Site Monitor'de raporda görünecek", "already being sent. The result will show" });
        want.put("PDF_BUSY", new String[] { "PDF sırası dolu", "PDF queue busy" });
        // R2a (MP-41): önceki gönderim 421 kuyruğunda — yeniden gönderilmedi
        want.put("SEND_QUEUED", new String[] { "önceki gönderim kuyrukta. Önceki gönderim posta kuyruğunda", "earlier send still queued" });
        want.put("PDF_TOO_LARGE", new String[] { "PDF çok büyük, gitmedi. PDF yöneticilere e-posta sınırını aşıyor", "the PDF is too large. It exceeds the e-mail limit" });
        want.put("FAILED", new String[] { "yöneticilere ulaşmadı. Raporu Site Monitor'den yeniden gönderin", "did not reach the executives" });
        want.put("MAIL_FAILED", new String[] { "yöneticilere ulaşmadı", "did not reach the executives" });
        want.put("EXEC_RECIPIENTS_MISSING", new String[] { "yöneticilere ulaşmadı", "did not reach the executives" });
        for (Map.Entry<String, String[]> e : want.entrySet()) {
            String code = e.getKey();
            // sendState (SENT / QUEUED / SKIPPED / FAILED) ya da sendError kodu — ikisi de aynı cümleye
            boolean state = List.of("SENT", "QUEUED", "SKIPPED", "FAILED").contains(code);
            UserPushService.LocalizedText t = TechErrorPushService.decisionText(CTX, true, "PO Bir", null, state ? code : null, state ? null : code);
            assertThat(t.tr()).as(code).contains(e.getValue()[0]).doesNotContain(code.contains("_") ? code : "§").doesNotContain("BAŞARISIZ")
                    .doesNotContain("(" + code + ")").endsWith("Onaylayan: PO Bir.");
            assertThat(t.en()).as(code).contains(e.getValue()[1]).doesNotContain(code.contains("_") ? code : "§").doesNotContain("FAILED")
                    .endsWith("Approved by: PO Bir.");
            assertThat(PushText.pushSafe(t.tr())).isEqualTo(t.tr());
            assertThat(PushText.pushSafe(t.en())).isEqualTo(t.en());
        }
        // Atlanan gönderim başarısız sayılmaz: "ulaşmadı / yeniden gönderin" demez
        assertThat(TechErrorPushService.decisionText(CTX, true, "x", null, "SKIPPED", null).tr()).doesNotContain("ulaşmadı").doesNotContain("yeniden gönderin");
        // Bilinmeyen kod da ham yazılmaz
        assertThat(TechErrorPushService.decisionText(CTX, true, "x", null, null, "SOME_NEW_CODE").tr()).doesNotContain("SOME_NEW_CODE").contains("ulaşmadı");
    }

    @Test
    @DisplayName("inceleme 2026-10-18 (3. tur): CANLI push tavanı (120 / 200 / 320) — 66 karakterlik kanal + 71 karakterlik AD adıyla her onay sonucu ve red TR + EN kesilmeden sığar; çekirdek cümle ve dönem her tavanda; düşme sırası imza → kanal adı → öneri; 320'de hepsi tam")
    void textsFitTheConfiguredLimit() {
        String adName = "Ayşe Yılmaz Kayaoğlu (Dijital Kanallar Müdürlüğü / Kıdemli Ürün Sahibi)";
        String channel = "Bireysel Mobil Bankacılık ve Dijital Şube Kanalları Web Uygulaması";
        TechErrorPushService.Ctx longCh = new TechErrorPushService.Ctx(42L, 7L, channel, 2026, 9);
        assertThat(channel.length()).isGreaterThanOrEqualTo(60);
        assertThat(adName.length()).isGreaterThanOrEqualTo(68);
        List<String> codes = List.of("SENT", "QUEUED", "SKIPPED", "FAILED", "APPROVAL_STALE", "SEND_IN_PROGRESS", "SEND_QUEUED",
                "PDF_BUSY", "PDF_TOO_LARGE", "MAIL_FAILED", "EXEC_RECIPIENTS_MISSING", "REJECTED");
        for (int limit : new int[] { TechErrorPushService.MIN_MESSAGE_LIMIT, 120, 200, 320 }) {
            for (String code : codes) {
                boolean rejected = "REJECTED".equals(code);
                boolean state = List.of("SENT", "QUEUED", "SKIPPED", "FAILED").contains(code);
                for (TechErrorPushService.Ctx ctx : List.of(CTX, longCh)) {
                    UserPushService.LocalizedText t = TechErrorPushService.decisionText(ctx, !rejected, adName, "not", state ? code : null,
                            state || rejected ? null : code, limit);
                    for (boolean en : new boolean[] { false, true }) {
                        String s = en ? t.en() : t.tr();
                        String where = limit + " " + code + " " + en + ": " + s;
                        assertThat(PushText.truncate(PushText.pushSafe(s), limit)).as(where).isEqualTo(s);
                        assertThat(s.length()).as(where).isLessThanOrEqualTo(limit);
                        String core = rejected ? (en ? TechErrorPushService.REJECT_CORE_EN : TechErrorPushService.REJECT_CORE_TR).trim()
                                : TechErrorPushService.approvedParts(code, en)[0];
                        assertThat(s).as(where).contains(core).contains(en ? "September 2026" : "Eylül 2026")
                                .startsWith(en ? "[Technical error report] " : "[Teknik hata raporu] ");
                        assertThat(s.endsWith(".")).as("imza ya yok ya tam: " + where).isTrue();
                        if (limit == 320) {
                            // Geniş tavanda hiçbir şey düşmez: tam kanal adı, öneri, tam imza
                            assertThat(s).as(where).contains(ctx.channelName())
                                    .contains(rejected ? (en ? TechErrorPushService.REJECT_ADVICE_EN : TechErrorPushService.REJECT_ADVICE_TR)
                                            : TechErrorPushService.approvedParts(code, en)[1])
                                    .endsWith(adName + ".");
                        }
                    }
                }
            }
        }
        // Düşme sırası (200, uzun kanal): öneri KALIR, kanal adı kısalır, imza düşer
        String busy = TechErrorPushService.decisionText(longCh, true, adName, null, null, "PDF_BUSY", 200).en();
        assertThat(busy).contains(TechErrorPushService.approvedOutcome("PDF_BUSY", true)).contains("...").doesNotContain("Approved by");
        // 120: öneri düşer (kanal adı kısalsa da sığmıyor), imza da; kısalan kanal adı + dönem + çekirdek kalır
        String narrow = TechErrorPushService.decisionText(longCh, true, adName, null, null, "PDF_BUSY", 120).en();
        assertThat(narrow).startsWith("[Technical error report] Bireysel").contains("...")
                .endsWith("September 2026 " + TechErrorPushService.approvedParts("PDF_BUSY", true)[0])
                .doesNotContain(TechErrorPushService.approvedParts("PDF_BUSY", true)[1].trim()).doesNotContain("Approved by");
        // 80 (en küçük tavan): yalnız başlık + dönem + çekirdek
        String min = TechErrorPushService.decisionText(longCh, true, adName, null, null, "PDF_BUSY", 80).en();
        assertThat(min).isEqualTo("[Technical error report] September 2026 " + TechErrorPushService.approvedParts("PDF_BUSY", true)[0]);
        // Kısa ad kuralı
        assertThat(TechErrorPushService.shortName(adName)).isEqualTo("Ayşe Yılmaz Kayaoğlu");
        assertThat(TechErrorPushService.shortName("  ")).isEmpty();
        assertThat(TechErrorPushService.shortName("A".repeat(60)).length()).isLessThanOrEqualTo(40);
    }

    @Test
    @DisplayName("inceleme 2026-10-18 (3. tur): karar push'u kişinin CANLI tavanını kullanır (messageCharLimit 120 → metin ≤ 120, okunamaz / 0 → 200)")
    void decisionUsesTheLiveLimit() {
        TechErrorPushService.Ctx longCh = new TechErrorPushService.Ctx(42L, 7L,
                "Bireysel Mobil Bankacılık ve Dijital Şube Kanalları Web Uygulaması", 2026, 9);
        when(push.messageCharLimit()).thenReturn(120);
        svc.decided(longCh, 9L, 1L, "Ayşe Yılmaz Kayaoğlu (Dijital Kanallar Müdürlüğü / Kıdemli Ürün Sahibi)", true, null, null, "PDF_BUSY", AT);
        ArgumentCaptor<UserPushService.LocalizedText> text = ArgumentCaptor.forClass(UserPushService.LocalizedText.class);
        verify(push).enqueueDirectLocalized(any(), eq(7L), eq(TechErrorPushService.TRIGGER_DECISION), eq("WARNING"), eq("TECH_ERROR_REPORT"),
                anyString(), text.capture(), anyString());
        assertThat(text.getValue().tr().length()).isLessThanOrEqualTo(120);
        assertThat(text.getValue().en().length()).isLessThanOrEqualTo(120);
        assertThat(text.getValue().en()).contains(TechErrorPushService.approvedParts("PDF_BUSY", true)[0]);
    }

    // ── 4. Gönderildi ─────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("gönderildi → takım bildirimi: gönderen ve bu olay için zaten push alan hariç, anahtar rapor + sayaç + talep anı, hedef kitle metinde (adres yok)")
    void delivered() {
        svc.delivered(CTX, true, 1L, "PO Bir", 5L, AT, List.of(9L));
        ArgumentCaptor<UserPushService.LocalizedText> text = ArgumentCaptor.forClass(UserPushService.LocalizedText.class);
        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Set<String>> excluded = ArgumentCaptor.forClass(Set.class);
        verify(push).enqueueTeamNoticeLocalized(eq(7L), eq(TechErrorPushService.TRIGGER_SENT), eq("WARNING"), eq("TECH_ERROR_REPORT"),
                eq("Mobil Bankacılık Eylül 2026"), text.capture(), key.capture(), excluded.capture());
        assertThat(excluded.getValue()).containsExactlyInAnyOrder("po1", "tech");
        assertThat(key.getValue()).isEqualTo("te-sent:42:5:" + AT.toEpochMilli());
        assertThat(text.getValue().tr()).isEqualTo("[Teknik hata raporu] Mobil Bankacılık Eylül 2026 raporu yöneticilere gönderildi. Gönderen: PO Bir.");
        assertThat(text.getValue().en()).isEqualTo("[Technical error report] Mobil Bankacılık September 2026 was sent to the executives. Sent by: PO Bir.");
        assertThat(TechErrorPushService.deliveredText(CTX, false, "x").tr()).contains("takım adresine gönderildi");
        assertThat(text.getValue().tr()).doesNotContain("@");
    }

    @Test
    @DisplayName("son inceleme 2026-10-18: onay → yöneticilere gönderim KUYRUKTA → sonra teslim: isteyen karar push'unda 'kuyrukta' bilgisini aldı ve takımın 'gönderildi' bildiriminden düşülür (kararı veren PO gönderen olarak zaten hariç) — aynı teslim için ikinci push yok; karar push'u kapalı / kendi kararı → isteyen kuyrukla saklanmaz, takım bildirimini alır")
    void queuedApprovalDeliveryIsNotPushedTwice() {
        // 1) karar: PO Bir (1) onayladı, isteyen Teknik Kişi (9); yöneticilere gönderim kuyrukta
        assertThat(svc.decisionGoesTo(9L, 1L)).isTrue();
        svc.decided(CTX, 9L, 1L, "PO Bir", true, null, "QUEUED", null, AT);
        ArgumentCaptor<UserPushService.LocalizedText> text = ArgumentCaptor.forClass(UserPushService.LocalizedText.class);
        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        List<List<UserPushService.DirectRecipient>> calls = directCalls(1, text, key, TechErrorPushService.TRIGGER_DECISION);
        assertThat(names(calls.get(0))).containsExactly("tech");
        assertThat(text.getValue().tr()).contains("yönetici gönderimi kuyrukta");
        // 2) kuyruk teslim edildi: sonuçlandırma kuyrukla saklanan kişiyi (isteyen) geçer → takım bildiriminde ikisi de yok
        svc.delivered(CTX, true, 1L, "PO Bir", 5L, AT.plusSeconds(1), List.of(9L));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Set<String>> excluded = ArgumentCaptor.forClass(Set.class);
        verify(push).enqueueTeamNoticeLocalized(eq(7L), eq(TechErrorPushService.TRIGGER_SENT), eq("WARNING"), eq("TECH_ERROR_REPORT"),
                eq("Mobil Bankacılık Eylül 2026"), any(), eq("te-sent:42:5:" + AT.plusSeconds(1).toEpochMilli()), excluded.capture());
        assertThat(excluded.getValue()).containsExactlyInAnyOrder("po1", "tech");
        // 3) isteyen karar push'u almadıysa (kendi kararı ya da karar push'u kapalı) gönderen saklamaz → takım bildirimini alır
        assertThat(svc.decisionGoesTo(9L, 9L)).isFalse();
        settings.put(TechErrorReportSettings.KEY_PUSH_DECISION, "false");
        assertThat(svc.decisionGoesTo(9L, 1L)).isFalse();
        svc.delivered(CTX, true, 1L, "PO Bir", 6L, AT.plusSeconds(2), List.of());
        verify(push, times(2)).enqueueTeamNoticeLocalized(eq(7L), eq(TechErrorPushService.TRIGGER_SENT), anyString(), anyString(),
                anyString(), any(), anyString(), excluded.capture());
        assertThat(excluded.getValue()).containsExactly("po1");
    }

    @Test
    @DisplayName("kullanıcı kararı 2026-10-19 ('gitsin'): onaydan sonra KUYRUKTAKİ yöneticilere gönderim teslim edilince isteyen DOĞRUDAN 'teslim edildi' push'u alır ve takım bildiriminden düşülü kalır — iki push da teslimin AYNI anahtarıyla (kişi başına tek push); kendi onayı / isteyen yok / pasif / 'Gönderildi' ayarı kapalı → push yok; opt-out ve kişisel tercih kendi satırını yazar")
    void queuedDeliveryTellsTheRequesterDirectly() {
        // 1) teslim: isteyen Teknik Kişi (9), onaylayıp gönderen PO Bir (1)
        svc.queuedDeliveredToRequester(CTX, true, 9L, 1L, "PO Bir", 5L, AT);
        svc.delivered(CTX, true, 1L, "PO Bir", 5L, AT, List.of(9L));
        ArgumentCaptor<UserPushService.LocalizedText> text = ArgumentCaptor.forClass(UserPushService.LocalizedText.class);
        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        List<List<UserPushService.DirectRecipient>> calls = directCalls(1, text, key, TechErrorPushService.TRIGGER_SENT);
        assertThat(names(calls.get(0))).containsExactly("tech");
        assertThat(text.getValue().tr()).isEqualTo(
                "[Teknik hata raporu] Mobil Bankacılık Eylül 2026 raporu yöneticilere teslim edildi. Kuyruktaki gönderim tamamlandı. Onaylayan: PO Bir.");
        assertThat(text.getValue().en()).isEqualTo(
                "[Technical error report] Mobil Bankacılık September 2026 was delivered to the executives. The queued send has completed. Approved by: PO Bir.");
        String deliveryKey = "te-sent:42:5:" + AT.toEpochMilli();
        assertThat(key.getValue()).isEqualTo(deliveryKey).isEqualTo(TechErrorPushService.sentKey(CTX, 5L, AT));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Set<String>> excluded = ArgumentCaptor.forClass(Set.class);
        verify(push).enqueueTeamNoticeLocalized(eq(7L), eq(TechErrorPushService.TRIGGER_SENT), eq("WARNING"), eq("TECH_ERROR_REPORT"),
                eq("Mobil Bankacılık Eylül 2026"), any(), eq(deliveryKey), excluded.capture());
        assertThat(excluded.getValue()).containsExactlyInAnyOrder("po1", "tech");   // isteyen ve gönderen takım bildiriminde yok
        assertThat(TechErrorPushService.requesterDeliveredText(CTX, false, "PO Bir", 200).tr())
                .contains("takım adresine teslim edildi").endsWith("Gönderen: PO Bir.");

        // 2) push yazılmayan durumlar: kendi onayı (isteyen = gönderen), isteyen yok, pasif, ayar kapalı, modül kapalı
        svc.queuedDeliveredToRequester(CTX, true, 1L, 1L, "PO Bir", 5L, AT);
        svc.queuedDeliveredToRequester(CTX, true, null, 1L, "PO Bir", 5L, AT);
        byId.get(9L).setActive(false);
        svc.queuedDeliveredToRequester(CTX, true, 9L, 1L, "PO Bir", 5L, AT);
        byId.get(9L).setActive(true);
        settings.put(TechErrorReportSettings.KEY_PUSH_DELIVERED, "false");
        svc.queuedDeliveredToRequester(CTX, true, 9L, 1L, "PO Bir", 5L, AT);
        settings.remove(TechErrorReportSettings.KEY_PUSH_DELIVERED);
        when(visibility.teamVisible(7L)).thenReturn(false);
        svc.queuedDeliveredToRequester(CTX, true, 9L, 1L, "PO Bir", 5L, AT);
        when(visibility.teamVisible(7L)).thenReturn(true);
        verify(push, times(1)).enqueueDirectLocalized(any(), any(), anyString(), anyString(), anyString(), anyString(), any(), anyString());

        // 3) opt-out ve asgari seviye: kişi yine listede — servis atlama satırını yazar (sessiz kayıp yok)
        byId.get(9L).setPushOptOut(true);
        svc.queuedDeliveredToRequester(CTX, true, 9L, 1L, "PO Bir", 6L, AT);
        byId.get(9L).setPushOptOut(false);
        byId.get(9L).setPushMinLevel("HIGH");
        svc.queuedDeliveredToRequester(CTX, true, 9L, 1L, "PO Bir", 7L, AT);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<UserPushService.DirectRecipient>> list = ArgumentCaptor.forClass(List.class);
        verify(push, times(3)).enqueueDirectLocalized(list.capture(), eq(7L), eq(TechErrorPushService.TRIGGER_SENT), anyString(),
                anyString(), anyString(), any(), anyString());
        assertThat(list.getAllValues().get(1).get(0).optOut()).isTrue();
        assertThat(list.getAllValues().get(2).get(0).skipReason()).isEqualTo("SKIPPED_USER_LEVEL");

        // 4) hata yayılmaz
        doThrow(new IllegalStateException("db down")).when(push)
                .enqueueDirectLocalized(any(), any(), anyString(), anyString(), anyString(), anyString(), any(), anyString());
        byId.get(9L).setPushMinLevel(null);
        assertThatCode(() -> svc.queuedDeliveredToRequester(CTX, true, 9L, 1L, "PO Bir", 8L, AT)).doesNotThrowAnyException();
        TechErrorPushService none = new TechErrorPushService(appSettings, users);
        assertThatCode(() -> none.queuedDeliveredToRequester(CTX, true, 9L, 1L, "x", 1L, AT)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("isteyenin 'teslim edildi' push'u CANLI tavana sığar (120 / 200 / 320; 66 karakterlik kanal + 71 karakterlik AD adı): çekirdek cümle ve dönem her tavanda, imza ya tam ya kısa ya yok; 320'de hepsi tam")
    void requesterDeliveredTextFitsTheLimit() {
        String adName = "Ayşe Yılmaz Kayaoğlu (Dijital Kanallar Müdürlüğü / Kıdemli Ürün Sahibi)";
        TechErrorPushService.Ctx longCh = new TechErrorPushService.Ctx(42L, 7L,
                "Bireysel Mobil Bankacılık ve Dijital Şube Kanalları Web Uygulaması", 2026, 9);
        for (int limit : new int[] { TechErrorPushService.MIN_MESSAGE_LIMIT, 120, 200, 320 }) {
            for (boolean exec : new boolean[] { true, false }) {
                for (TechErrorPushService.Ctx ctx : List.of(CTX, longCh)) {
                    UserPushService.LocalizedText t = TechErrorPushService.requesterDeliveredText(ctx, exec, adName, limit);
                    for (boolean en : new boolean[] { false, true }) {
                        String s = en ? t.en() : t.tr();
                        String where = limit + " " + exec + " " + en + ": " + s;
                        assertThat(s.length()).as(where).isLessThanOrEqualTo(limit);
                        assertThat(PushText.truncate(PushText.pushSafe(s), limit)).as(where).isEqualTo(s);
                        assertThat(s).as(where).contains(en ? "September 2026" : "Eylül 2026")
                                .contains(exec ? (en ? "was delivered to the executives." : "raporu yöneticilere teslim edildi.")
                                        : (en ? "was delivered to the team address." : "raporu takım adresine teslim edildi."))
                                .startsWith(en ? "[Technical error report] " : "[Teknik hata raporu] ")
                                .doesNotContain("http").doesNotContain("://");
                        assertThat(s.endsWith(".")).as(where).isTrue();
                        if (limit > TechErrorPushService.MIN_MESSAGE_LIMIT) {
                            assertThat(s).as(where).contains(en ? TechErrorPushService.DELIVERED_ADVICE_EN.trim()
                                    : TechErrorPushService.DELIVERED_ADVICE_TR.trim());
                        }
                        if (limit == 320) assertThat(s).as(where).contains(ctx.channelName()).endsWith(adName + ".");
                    }
                }
            }
        }
        // Canlı tavan: messageCharLimit 120 → metin ≤ 120
        when(push.messageCharLimit()).thenReturn(120);
        svc.queuedDeliveredToRequester(longCh, true, 9L, 1L, adName, 5L, AT);
        ArgumentCaptor<UserPushService.LocalizedText> text = ArgumentCaptor.forClass(UserPushService.LocalizedText.class);
        verify(push).enqueueDirectLocalized(any(), eq(7L), eq(TechErrorPushService.TRIGGER_SENT), eq("WARNING"), eq("TECH_ERROR_REPORT"),
                anyString(), text.capture(), anyString());
        assertThat(text.getValue().tr().length()).isLessThanOrEqualTo(120);
        assertThat(text.getValue().en().length()).isLessThanOrEqualTo(120);
        assertThat(text.getValue().tr()).contains("yöneticilere teslim edildi");
    }

    // ── 5. Gönderilemedi ──────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("gönderilemedi → gönderen: anında / kuyruk sonunda / sonucu alınamadı ayrı metin ve anahtar; pasif gönderen atlanır")
    void sendFailed() {
        svc.sendFailed(CTX, false, 9L, TechErrorPushService.Failure.FAILED, "PDF_TOO_LARGE", AT);
        svc.sendFailed(CTX, true, 9L, TechErrorPushService.Failure.QUEUE_FAILED, null, AT.plusSeconds(1));
        svc.sendFailed(CTX, false, 9L, TechErrorPushService.Failure.UNKNOWN, null, AT.plusSeconds(2));
        ArgumentCaptor<UserPushService.LocalizedText> text = ArgumentCaptor.forClass(UserPushService.LocalizedText.class);
        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        List<List<UserPushService.DirectRecipient>> calls = directCalls(3, text, key, TechErrorPushService.TRIGGER_SEND_FAILED);
        assertThat(calls).allSatisfy(l -> assertThat(names(l)).containsExactly("tech"));
        List<UserPushService.LocalizedText> t = text.getAllValues();
        assertThat(t.get(0).tr()).contains("takım adresine gönderilemedi (PDF e-posta boyut sınırını aşıyor)").contains("yeniden gönderin");
        assertThat(t.get(1).tr()).contains("yöneticilere gönderilemedi (posta sunucusu teslim edemedi)");
        assertThat(t.get(2).tr()).contains("önceki gönderiminin sonucu alınamadı");
        assertThat(key.getAllValues()).containsExactly("te-fail:42:" + AT.toEpochMilli(), "te-fail:42:" + (AT.toEpochMilli() + 1000),
                "te-fail:42:" + (AT.toEpochMilli() + 2000) + ":unknown");
        byId.get(9L).setActive(false);
        svc.sendFailed(CTX, false, 9L, TechErrorPushService.Failure.FAILED, null, AT);
        svc.sendFailed(CTX, false, null, TechErrorPushService.Failure.FAILED, null, AT);
        verify(push, times(3)).enqueueDirectLocalized(any(), any(), anyString(), anyString(), anyString(), anyString(), any(), anyString());
    }

    @Test
    @DisplayName("inceleme 2026-10-18: birden çok alıcılı 'gönderilemedi' (kuyrukta başarısız yönetici gönderimi → gönderen PO + onaya gönderen) — tek çağrı, kişiler tekil, pasif / bilinmeyen atlanır, anahtar aynı")
    void sendFailedToSeveral() {
        byId.get(2L).setActive(false);
        svc.sendFailedTo(CTX, true, java.util.Arrays.asList(1L, 9L, 1L, null, 2L, 77L), TechErrorPushService.Failure.QUEUE_FAILED, null, AT);
        ArgumentCaptor<UserPushService.LocalizedText> text = ArgumentCaptor.forClass(UserPushService.LocalizedText.class);
        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        List<List<UserPushService.DirectRecipient>> calls = directCalls(1, text, key, TechErrorPushService.TRIGGER_SEND_FAILED);
        assertThat(names(calls.get(0))).containsExactly("po1", "tech");
        assertThat(key.getValue()).isEqualTo("te-fail:42:" + AT.toEpochMilli());
        assertThat(text.getValue().tr()).contains("yöneticilere gönderilemedi (posta sunucusu teslim edemedi)");
        // hiç geçerli alıcı yok → çağrı yok
        svc.sendFailedTo(CTX, true, List.of(2L, 77L), TechErrorPushService.Failure.QUEUE_FAILED, null, AT);
        svc.sendFailedTo(CTX, true, List.of(), TechErrorPushService.Failure.QUEUE_FAILED, null, AT);
        svc.sendFailedTo(CTX, true, null, TechErrorPushService.Failure.QUEUE_FAILED, null, AT);
        verify(push, times(1)).enqueueDirectLocalized(any(), any(), anyString(), anyString(), anyString(), anyString(), any(), anyString());
    }

    @Test
    @DisplayName("inceleme 2026-10-19: EN KÜÇÜK push tavanında (80) her sonuç metni — 8 onay sonucu + bilinmeyen kod, red, isteyene "
            + "teslim (yönetici / takım) — TR + EN, 12 ayın her biri, kısa / 66 karakterlik kanal + 71 karakterlik AD adıyla kesilmeden "
            + "sığar: başlık + dönem + çekirdek cümle her zaman var, öneri ve imza önce düşer")
    void outcomeTextsFitTheMinimumLimit() {
        int limit = TechErrorPushService.MIN_MESSAGE_LIMIT;
        assertThat(limit).isEqualTo(80);
        String adName = "Ayşe Yılmaz Kayaoğlu (Dijital Kanallar Müdürlüğü / Kıdemli Ürün Sahibi)";
        String channel = "Bireysel Mobil Bankacılık ve Dijital Şube Kanalları Web Uygulaması";
        List<String> codes = List.of("SENT", "QUEUED", "SKIPPED", "FAILED", "APPROVAL_STALE", "SEND_IN_PROGRESS", "SEND_QUEUED",
                "PDF_BUSY", "PDF_TOO_LARGE", "MAIL_FAILED", "SOME_NEW_CODE");
        int checked = 0;
        for (int month = 1; month <= 12; month++) {
            for (String ch : List.of("", "Mobil", channel)) {
                TechErrorPushService.Ctx ctx = new TechErrorPushService.Ctx(42L, 7L, ch, 2026, month);
                List<String[]> cases = new ArrayList<>();   // { ad, tr metni, en metni, tr çekirdek, en çekirdek }
                for (String code : codes) {
                    boolean state = List.of("SENT", "QUEUED", "SKIPPED", "FAILED").contains(code);
                    UserPushService.LocalizedText t = TechErrorPushService.decisionText(ctx, true, adName, null,
                            state ? code : null, state ? null : code, limit);
                    cases.add(new String[] { code, t.tr(), t.en(), TechErrorPushService.approvedParts(code, false)[0],
                            TechErrorPushService.approvedParts(code, true)[0] });
                }
                UserPushService.LocalizedText rej = TechErrorPushService.decisionText(ctx, false, adName, "not", null, null, limit);
                cases.add(new String[] { "REJECTED", rej.tr(), rej.en(), TechErrorPushService.REJECT_CORE_TR.trim(),
                        TechErrorPushService.REJECT_CORE_EN.trim() });
                for (boolean exec : new boolean[] { true, false }) {
                    UserPushService.LocalizedText d = TechErrorPushService.requesterDeliveredText(ctx, exec, adName, limit);
                    cases.add(new String[] { "DELIVERED-" + exec, d.tr(), d.en(),
                            exec ? "raporu yöneticilere teslim edildi." : "raporu takım adresine teslim edildi.",
                            exec ? "was delivered to the executives." : "was delivered to the team address." });
                }
                for (String[] c : cases) {
                    for (boolean en : new boolean[] { false, true }) {
                        String s = en ? c[2] : c[1];
                        String where = month + " / " + ch.length() + " / " + c[0] + " / " + (en ? "EN" : "TR") + ": " + s;
                        assertThat(s.length()).as(where).isLessThanOrEqualTo(limit);
                        assertThat(PushText.truncate(PushText.pushSafe(s), limit)).as(where).isEqualTo(s);
                        assertThat(s).as(where).startsWith(en ? TechErrorPushService.HEAD_EN : TechErrorPushService.HEAD_TR)
                                .contains(com.sitemonitor.service.mail.TechErrorReminderMail.period(2026, month, en))
                                .contains(en ? c[4] : c[3])
                                .doesNotContain("http");
                        assertThat(s.endsWith(".")).as(where).isTrue();
                        checked++;
                    }
                }
            }
        }
        assertThat(checked).isEqualTo(12 * 3 * (11 + 1 + 2) * 2);   // R2a MP-41: + SEND_QUEUED
        // Kısa kanal + kısa ayda öneri sığarsa kalır (önce imza düşer, sonra kanal adı): red notu yönlendirmesi 80'de de görünür
        String shortRej = TechErrorPushService.decisionText(new TechErrorPushService.Ctx(42L, 7L, "Mobil", 2026, 5), false,
                adName, null, null, null, limit).tr();
        assertThat(shortRej).isEqualTo("[Teknik hata raporu] Mayıs 2026 raporu reddedildi. Ret notu Site Monitor'de.");
    }

    // ── Metin kuralları ───────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("her metin (TR + EN): push kanalının karakter kümesinde (pushSafe değiştirmez), bağlantı yok, başlıkla başlar, boş değil")
    void textsArePushSafe() {
        List<UserPushService.LocalizedText> all = new ArrayList<>();
        for (boolean resend : new boolean[] { false, true }) {
            for (boolean reached : new boolean[] { false, true }) all.add(TechErrorPushService.requestText(CTX, resend, reached, "Ayşe Yılmaz (Dijital / Çağrı)"));
        }
        all.add(TechErrorPushService.reminderText(CTX, 4, "Ayşe Yılmaz"));
        all.add(TechErrorPushService.decisionText(CTX, true, "Şükrü Öztürk", null, "SENT", null));
        all.add(TechErrorPushService.decisionText(CTX, true, "Şükrü Öztürk", null, "QUEUED", null));
        all.add(TechErrorPushService.decisionText(CTX, true, "Şükrü Öztürk", null, "FAILED", null));
        all.add(TechErrorPushService.decisionText(CTX, true, "Şükrü Öztürk", null, null, "MAIL_FAILED"));
        all.add(TechErrorPushService.decisionText(CTX, false, "Şükrü Öztürk", "Düzeltin — tablo ve “özet” eksik…\nikinci satır", null, null));
        for (boolean exec : new boolean[] { false, true }) {
            all.add(TechErrorPushService.deliveredText(CTX, exec, "Ayşe Yılmaz"));
            all.add(TechErrorPushService.requesterDeliveredText(CTX, exec, "Şükrü Öztürk (Dijital / Çağrı)", 200));
            for (TechErrorPushService.Failure f : TechErrorPushService.Failure.values()) {
                all.add(TechErrorPushService.failedText(CTX, exec, f, "PDF_TOO_LARGE"));
                all.add(TechErrorPushService.failedText(CTX, exec, f, null));
            }
        }
        for (int i = 0; i < all.size(); i++) {
            UserPushService.LocalizedText t = all.get(i);
            for (String s : new String[] { t.tr(), t.en() }) {
                String safe = s;
                // Red notu push'a girmez (karar 2026-10-18): özel karakterli not da metni değiştirmez — HER metin pushSafe-değişmez
                assertThat(PushText.pushSafe(s)).as("pushSafe metni değiştirmemeli: %s", s).isEqualTo(s);
                assertThat(safe).isNotBlank().doesNotContain("http").doesNotContain("www.").doesNotContain("://").doesNotContain("\n");
            }
            assertThat(t.tr()).startsWith("[Teknik hata raporu] ");
            assertThat(t.en()).startsWith("[Technical error report] ");
        }
        // Boş ad satırı yazılmaz; ad noktayla bitiyorsa ikinci nokta yok
        assertThat(TechErrorPushService.byLine(" Talep eden: ", " ")).isEmpty();
        assertThat(TechErrorPushService.byLine(" Talep eden: ", "Ltd. Şti.")).isEqualTo(" Talep eden: Ltd. Şti.");
    }
}
