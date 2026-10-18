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
    @DisplayName("karar → isteyen: onay + gönderim sonucu (gönderildi / kuyrukta / PDF sırası dolu — ham kod yok), red + notun ilk 120 karakteri; kendi kararı ve pasif isteyen atlanır")
    void decision() {
        svc.decided(CTX, 9L, 1L, "PO Bir", true, null, "SENT", null, AT);
        svc.decided(CTX, 9L, 1L, "PO Bir", true, null, "QUEUED", null, AT.plusSeconds(1));
        svc.decided(CTX, 9L, 1L, "PO Bir", true, null, null, "PDF_BUSY", AT.plusSeconds(2));
        String longNote = "Tablo eksik. ".repeat(20);
        svc.decided(CTX, 9L, 1L, "PO Bir", false, longNote, null, null, AT.plusSeconds(3));
        ArgumentCaptor<UserPushService.LocalizedText> text = ArgumentCaptor.forClass(UserPushService.LocalizedText.class);
        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        List<List<UserPushService.DirectRecipient>> calls = directCalls(4, text, key, TechErrorPushService.TRIGGER_DECISION);
        assertThat(calls).allSatisfy(l -> assertThat(names(l)).containsExactly("tech"));
        List<UserPushService.LocalizedText> t = text.getAllValues();
        assertThat(t.get(0).tr()).contains("onaylandı ve yöneticilere gönderildi").endsWith("Onaylayan: PO Bir.");
        assertThat(t.get(1).tr()).contains("kuyrukta");
        assertThat(t.get(2).tr()).contains("PDF sırası dolu").contains("yeniden gönderin").doesNotContain("PDF_BUSY").doesNotContain("BAŞARISIZ");
        assertThat(t.get(2).en()).contains("PDF queue was busy").doesNotContain("PDF_BUSY");
        // Uzun not tavanı doldurur: not İMZADAN önce gelir, sığmayan imza / öneri düşer (not kesilmez)
        assertThat(t.get(3).tr()).contains("reddedildi").contains("Not: Tablo eksik.").doesNotContain("Reddeden");
        String noteShown = t.get(3).tr().substring(t.get(3).tr().indexOf("Not: ") + 5);
        assertThat(noteShown.length()).isLessThanOrEqualTo(TechErrorPushService.NOTE_CHARS + 3);
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
        want.put("QUEUED", new String[] { "kuyrukta", "is queued" });
        want.put("SKIPPED", new String[] { "e-posta kanalı kapalı ya da etkin alıcı yok", "the e-mail channel is off or there is no active recipient" });
        want.put("APPROVAL_STALE", new String[] { "sonradan değiştiği için yöneticilere gönderilmedi; yeniden onaya gönderin", "submit it for approval again" });
        want.put("SEND_IN_PROGRESS", new String[] { "zaten gönderiliyor, sonuç Site Monitor'de raporda görünecek", "already being sent and the result will show" });
        want.put("PDF_BUSY", new String[] { "PDF sırası dolu", "the PDF queue was busy" });
        want.put("PDF_TOO_LARGE", new String[] { "PDF e-posta boyut sınırını aştığı için", "the PDF is over the e-mail size limit" });
        want.put("FAILED", new String[] { "yöneticilere e-posta ulaşmadı. Raporu Site Monitor'den yeniden gönderin", "did not reach the executives" });
        want.put("MAIL_FAILED", new String[] { "yöneticilere e-posta ulaşmadı", "did not reach the executives" });
        want.put("EXEC_RECIPIENTS_MISSING", new String[] { "yöneticilere e-posta ulaşmadı", "did not reach the executives" });
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
    @DisplayName("inceleme 2026-10-18 (2. tur): 60 karakterlik kanal + 68 karakterlik AD adıyla HER onay sonucu TR + EN tavana (200) kesilmeden sığar — sonuç cümlesi ve öneri tam, dönem görünür; sığmayan önce imza düşer / kısalır, sonra kanal adı kısalır")
    void approvedOutcomesFitWithLongNames() {
        String adName = "Ayşe Yılmaz Kayaoğlu (Dijital Kanallar Müdürlüğü / Kıdemli Ürün Sahibi)";
        TechErrorPushService.Ctx longCh = new TechErrorPushService.Ctx(42L, 7L,
                "Bireysel Mobil Bankacılık ve Dijital Şube Kanalları Web Uygulaması", 2026, 9);
        assertThat(longCh.channelName().length()).isGreaterThanOrEqualTo(60);
        assertThat(adName.length()).isGreaterThanOrEqualTo(68);
        for (String code : List.of("SENT", "QUEUED", "SKIPPED", "FAILED", "APPROVAL_STALE", "SEND_IN_PROGRESS", "PDF_BUSY",
                "PDF_TOO_LARGE", "MAIL_FAILED", "EXEC_RECIPIENTS_MISSING")) {
            boolean state = List.of("SENT", "QUEUED", "SKIPPED", "FAILED").contains(code);
            for (TechErrorPushService.Ctx ctx : List.of(CTX, longCh)) {
                UserPushService.LocalizedText t = TechErrorPushService.decisionText(ctx, true, adName, null, state ? code : null, state ? null : code);
                for (boolean en : new boolean[] { false, true }) {
                    String s = en ? t.en() : t.tr();
                    String shown = PushText.truncate(PushText.pushSafe(s), 200);
                    assertThat(shown).as(code + " " + en + ": " + s).isEqualTo(s);
                    assertThat(s.length()).as(code).isLessThanOrEqualTo(200);
                    assertThat(s).as(code).contains(TechErrorPushService.approvedOutcome(code, en))
                            .contains(en ? "September 2026" : "Eylül 2026");
                    // İmza ya hiç yok ya TAM (yarım "Onaylayan: Ayşe Yılmaz Kaya (Dijital Kan..." asla)
                    assertThat(s.endsWith(".")).as(code).isTrue();
                }
            }
            // Kısa kanal adı kısalmaz
            assertThat(TechErrorPushService.decisionText(CTX, true, adName, null, state ? code : null, state ? null : code).tr())
                    .as(code).contains("Mobil Bankacılık Eylül 2026");
        }
        // SENT ve kısa adla tam imza
        assertThat(TechErrorPushService.decisionText(CTX, true, "PO Bir", null, "SENT", null).tr()).endsWith("Onaylayan: PO Bir.");
    }

    @Test
    @DisplayName("inceleme 2026-10-18: red notu İMZADAN ÖNCE — 60+ karakterlik gerçek AD adıyla, push tavanı (200) uygulandıktan sonra notun ilk 120 karakteri kalır (kısa not tam); ad sığmazsa kısalır ya da düşer; TR + EN")
    void rejectionNoteSurvivesTheCap() {
        String adName = "Ayşe Yılmaz Kaya (Dijital Kanallar Müdürlüğü / Kıdemli Ürün Sahibi)";
        assertThat(adName.length()).isGreaterThan(60);
        String longNote = "Özet tablosundaki teknik hata oranı ile SLA değerleri tutarsız; Ağustos rakamlarını yeniden kontrol edin, "
                + "kota satırı da eksik.\n\nAyrıca yöneticiler için kısa bir açıklama ekleyin; grafikler eski aya ait görünüyor.";
        assertThat(longNote.length()).isGreaterThan(200);
        String norm = longNote.replaceAll("\\s+", " ").trim();
        String head120 = norm.substring(0, TechErrorPushService.NOTE_CHARS).trim();
        TechErrorPushService.Ctx longChannel = new TechErrorPushService.Ctx(42L, 7L,
                "Bireysel Mobil Bankacılık ve Dijital Şube Kanalları (Android / iOS / Web)", 2026, 9);
        for (TechErrorPushService.Ctx ctx : List.of(CTX, longChannel)) {
            UserPushService.LocalizedText t = TechErrorPushService.decisionText(ctx, false, adName, longNote, null, null);
            for (String s : new String[] { t.tr(), t.en() }) {
                String shown = PushText.truncate(PushText.pushSafe(s), 200);
                assertThat(shown).as(s).contains(head120).doesNotContain("Dijital Kanallar Müdürlüğü");
                assertThat(shown.length()).isLessThanOrEqualTo(200);
                assertThat(shown).as("dönem her zaman görünür").containsAnyOf("Eylül 2026", "September 2026");
            }
            assertThat(t.tr()).startsWith("[Teknik hata raporu] ").contains("raporu reddedildi.");
            assertThat(t.en()).startsWith("[Technical error report] ").contains("was rejected.");
            assertThat(t.tr().indexOf("Not: ")).isPositive();
        }
        // Kısa not: not TAM, öneri ve imza (sığdığı kadar — önce parantezsiz kısa ad) NOTTAN SONRA
        UserPushService.LocalizedText s = TechErrorPushService.decisionText(CTX, false, adName, "Tablo eksik.", null, null);
        for (String m : new String[] { s.tr(), s.en() }) {
            String shown = PushText.truncate(PushText.pushSafe(m), 200);
            assertThat(shown).isEqualTo(m).contains("Tablo eksik.");
        }
        assertThat(s.tr()).contains("Not: Tablo eksik.").contains("Düzeltip yeniden onaya gönderin.").contains("Reddeden: Ayşe Yılmaz Kaya");
        assertThat(s.tr().indexOf("Not: ")).isLessThan(s.tr().indexOf("Reddeden: "));
        assertThat(s.en()).contains("Note: Tablo eksik.").contains("Rejected by: Ayşe Yılmaz Kaya");
        assertThat(s.en().indexOf("Note: ")).isLessThan(s.en().indexOf("Rejected by: "));
        // Düşük tavan (80): not en çok yer alır, imza ve öneri düşer — başlık + dönem durur
        String tiny = TechErrorPushService.decisionText(CTX, false, adName, longNote, null, null, 80).tr();
        assertThat(tiny).doesNotContain("Reddeden").doesNotContain("Düzeltip").contains("Eylül 2026");
        // Kısa ad kuralı
        assertThat(TechErrorPushService.shortName(adName)).isEqualTo("Ayşe Yılmaz Kaya");
        assertThat(TechErrorPushService.shortName("  ")).isEmpty();
        assertThat(TechErrorPushService.shortName("A".repeat(60)).length()).isLessThanOrEqualTo(40);
    }

    // ── 6. Not içindeki bağlantı / adres ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("inceleme 2026-10-18: red notunda URL, www, e-posta, iç sunucu adı, IPv4, UNC yolu → [bağlantı] / [link]; düz metin, sürüm, sayı, kısaltma kalır; karar push'u bunu uygular")
    void noteLinksAreScrubbed() {
        String ph = TechErrorPushService.LINK_TR;
        assertThat(TechErrorPushService.scrubLinks("bkz. https://jira.intra/browse/TE-12 ve http://x.y", ph)).isEqualTo("bkz. [bağlantı] ve [bağlantı]");
        assertThat(TechErrorPushService.scrubLinks("(www.ornek.com.tr),", ph)).isEqualTo("([bağlantı]),");
        assertThat(TechErrorPushService.scrubLinks("yaz: ali.veli@sirket.com.tr; ya da ali@srv01.", ph)).isEqualTo("yaz: [bağlantı]; ya da [bağlantı].");
        assertThat(TechErrorPushService.scrubLinks("srv01.corp.local ve app.bank.intranet:8443/x", ph)).isEqualTo("[bağlantı] ve [bağlantı]");
        assertThat(TechErrorPushService.scrubLinks("10.12.0.5:8080 ve \\\\fs01\\paylaşım", ph)).isEqualTo("[bağlantı] ve [bağlantı]");
        assertThat(TechErrorPushService.scrubLinks("localhost:8080 api.ornek.com mailto:x", ph)).isEqualTo("[bağlantı] [bağlantı] [bağlantı]");
        assertThat(TechErrorPushService.scrubLinks("jira.bank.com.tr", TechErrorPushService.LINK_EN)).isEqualTo("[link]");
        // Kalanlar: düz metin, sürüm, binlik ayraçlı sayı, kısaltma, Türkçe noktalı sözcük, saat, @ ile anma
        String keep = "Tablo eksik. v1.2 1.250.000 e.g. A.Ş. eksik.Ama Tablo.Özet saat 10:30 %0,5 @ali 3.5";
        assertThat(TechErrorPushService.scrubLinks(keep, ph)).isEqualTo(keep);
        assertThat(TechErrorPushService.scrubLinks(null, ph)).isEmpty();

        svc.decided(CTX, 9L, 1L, "PO Bir", false, "Düzeltin: https://wiki.corp.local/te ve ayse@banka.com.tr", null, null, AT);
        ArgumentCaptor<UserPushService.LocalizedText> text = ArgumentCaptor.forClass(UserPushService.LocalizedText.class);
        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        directCalls(1, text, key, TechErrorPushService.TRIGGER_DECISION);
        assertThat(text.getValue().tr()).contains("Not: Düzeltin: [bağlantı] ve [bağlantı]").doesNotContain("wiki").doesNotContain("@");
        assertThat(text.getValue().en()).contains("Note: Düzeltin: [link] ve [link]").doesNotContain("corp.local");
    }

    @Test
    @DisplayName("inceleme 2026-10-18 (2. tur): Türkçe ekli / büyük harfli / virgülle bitişik / NBSP ve tireli / yollu / portlu / IPv6 adresler ayıklanır; dosya adı, cümle sınırı, numaralı madde, bilinmeyen son ek DEĞİŞMEZ")
    void noteScrubTurkishFormsBothWays() {
        String ph = TechErrorPushService.LINK_TR;
        Map<String, String> leaks = new java.util.LinkedHashMap<>();
        leaks.put("srv01.corp.local'a bakın", "[bağlantı]'a bakın");
        leaks.put("10.20.30.40'ta duruyor", "[bağlantı]'ta duruyor");
        leaks.put("portal.kurum.com.tr'de", "[bağlantı]'de");
        leaks.put("jira.corp.local’da kayıt", "[bağlantı]’da kayıt");
        leaks.put("İNTRANET.KURUM.LOCAL adresi", "[bağlantı] adresi");
        leaks.put("a.corp.local,b.corp.local;c.kurum.com.tr", "[bağlantı],[bağlantı];[bağlantı]");
        leaks.put("srv01.corp.local üzerinde", "[bağlantı] üzerinde");
        leaks.put("srv01.corp.local—ayrıntı", "[bağlantı]—ayrıntı");
        leaks.put("app.kurum.local–kapalı", "[bağlantı]–kapalı");
        leaks.put("bkz. intranet/abc", "bkz. [bağlantı]");
        leaks.put("portal.kurum/rapor", "[bağlantı]");
        leaks.put("srv01:8080 kapalı", "[bağlantı] kapalı");
        leaks.put("fe80::1 ve 2001:db8::8a2e:370:7334", "[bağlantı] ve [bağlantı]");
        leaks.put("[2001:db8::1]:8443", "[bağlantı]");
        leaks.put("https://wiki.corp.local/x'te", "[bağlantı]'te");
        leaks.put("Jira.corp.local", "[bağlantı]");
        for (Map.Entry<String, String> e : leaks.entrySet()) {
            assertThat(TechErrorPushService.scrubLinks(e.getKey(), ph)).as(e.getKey()).isEqualTo(e.getValue());
        }
        // Normal metin aynen kalır
        List<String> keep = List.of("rapor.v2.pdf", "config.app.json", "Ekler: tablo.xlsx, notlar.txt, liste.csv, karar.docx, ekran.png",
                "Sorun devam ediyor.Test ortamı da etkilendi", "Kapandı.Site çalışıyor", "Sorun giderildi.Online işlemler açık",
                "Madde 2.test aşaması", "main.co", "ve/veya", "Toplam:120", "saat 10:30:00", "Not:5", "Site Monitor'de",
                "Tablo eksik. v1.2 1.250.000 e.g. A.Ş. eksik.Ama Tablo.Özet saat 10:30 %0,5 @ali 3.5", "TR/EN — Ağustos/Eylül");
        for (String k : keep) assertThat(TechErrorPushService.scrubLinks(k, ph)).as(k).isEqualTo(k);
        // EN yer tutucu
        assertThat(TechErrorPushService.scrubLinks("srv01.corp.local'a", TechErrorPushService.LINK_EN)).isEqualTo("[link]'a");
        // ASCII küçültme indeksleri korur ("İ" birleşik nokta almaz)
        assertThat(TechErrorPushLinkScrubber.asciiLower("İNTRANET")).isEqualTo("İntranet");
    }

    @Test
    @DisplayName("inceleme 2026-10-18: bağlantı ayıklama DOĞRUSAL ve sınırlı — uzun / düşmanca girdiler (tek dev jeton, binlerce jeton, tekrar eden :// @ .) hızla biter, sınıra taşan jeton hiç yazılmaz")
    void scrubLinksIsLinearAndBounded() {
        String[] hostile = {
                "a@".repeat(200_000), "http:/".repeat(200_000) + "/", ".".repeat(400_000), "www." + "a.".repeat(200_000) + "local",
                "a.b ".repeat(200_000), "x".repeat(1_999) + " https://intra.corp.local/" + "y".repeat(500_000),
                ("1." + "2.".repeat(50)) .repeat(5_000), "(".repeat(300_000) + "srv.corp.local" + ")".repeat(300_000),
        };
        org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(java.time.Duration.ofSeconds(3), () -> {
            for (String h : hostile) {
                String out = TechErrorPushService.scrubLinks(h, TechErrorPushService.LINK_TR);
                assertThat(out.length()).isLessThanOrEqualTo(TechErrorPushService.SCRUB_MAX_INPUT + 16);
                assertThat(out).doesNotContain("://").doesNotContain("corp.local").doesNotContain("intra");
                String text = TechErrorPushService.decisionText(CTX, false, "PO", h, null, null).tr();
                assertThat(PushText.truncate(PushText.pushSafe(text), 200).length()).isLessThanOrEqualTo(200);
            }
        });
        // Sınırı aşan tek jeton yazılmaz (yarım adres sızmaz); sınırdan önce biten jetonlar işlenir
        String edge = "not " + "z".repeat(TechErrorPushService.SCRUB_MAX_INPUT) + "@corp.local";
        assertThat(TechErrorPushService.scrubLinks(edge, "[x]")).isEqualTo("not ");
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
            for (TechErrorPushService.Failure f : TechErrorPushService.Failure.values()) {
                all.add(TechErrorPushService.failedText(CTX, exec, f, "PDF_TOO_LARGE"));
                all.add(TechErrorPushService.failedText(CTX, exec, f, null));
            }
        }
        for (int i = 0; i < all.size(); i++) {
            UserPushService.LocalizedText t = all.get(i);
            for (String s : new String[] { t.tr(), t.en() }) {
                String safe = s;
                // Kullanıcı notu kanal dışı işaret taşıyabilir (— “ …): pushSafe çevirir; SABİT metinler değişmez
                if (i != 9) {
                    assertThat(PushText.pushSafe(s)).as("pushSafe metni değiştirmemeli: %s", s).isEqualTo(s);
                } else {
                    safe = PushText.pushSafe(s);
                    assertThat(safe).doesNotContain("—").doesNotContain("“").doesNotContain("…");
                }
                assertThat(safe).isNotBlank().doesNotContain("http").doesNotContain("www.").doesNotContain("://").doesNotContain("\n");
            }
            assertThat(t.tr()).startsWith("[Teknik hata raporu] ");
            assertThat(t.en()).startsWith("[Technical error report] ");
        }
        // Not tek satıra katlanır (push tek satır okunur); boş ad satırı yazılmaz; ad noktayla bitiyorsa ikinci nokta yok
        assertThat(TechErrorPushService.noteHead("a\n\nb")).isEqualTo("a b");
        assertThat(TechErrorPushService.byLine(" Talep eden: ", " ")).isEmpty();
        assertThat(TechErrorPushService.byLine(" Talep eden: ", "Ltd. Şti.")).isEqualTo(" Talep eden: Ltd. Şti.");
    }
}
