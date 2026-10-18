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
        assertThat(svc.approvalReminder(CTX, approvers(1L), 9L, "Teknik Kişi", 3, AT, "2026-10-05")).isZero();
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
            assertThat(svc.approvalReminder(CTX, approvers(1L), 9L, "Teknik Kişi", 3, AT, "2026-10-05")).isZero();
            svc.decided(CTX, 9L, 1L, "PO Bir", false, "not", null, null, AT);
            svc.delivered(CTX, false, 9L, "Teknik Kişi", 2L, AT, List.of());
            svc.sendFailed(CTX, false, 9L, TechErrorPushService.Failure.FAILED, null, AT);
        }).doesNotThrowAnyException();

        TechErrorPushService none = new TechErrorPushService(appSettings, users);
        assertThatCode(() -> none.delivered(CTX, false, 9L, "x", 1L, AT, List.of())).doesNotThrowAnyException();
        assertThat(none.approvalReminder(CTX, approvers(1L), 9L, "x", 3, AT, "2026-10-05")).isZero();
    }

    // ── 2. Onay hatırlatması ──────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("onay hatırlatması: tüm PO'lar (e-postasız dahil, isteyen hariç), gün taşıyan anahtar, N gün metni; kuyruğa alınan sayı döner")
    void approvalReminder() {
        int queued = svc.approvalReminder(CTX, approvers(1L, 3L, 9L), 9L, "Teknik Kişi", 1, AT, "2026-10-05");
        assertThat(queued).isEqualTo(2);
        ArgumentCaptor<UserPushService.LocalizedText> text = ArgumentCaptor.forClass(UserPushService.LocalizedText.class);
        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        List<List<UserPushService.DirectRecipient>> calls = directCalls(1, text, key, TechErrorPushService.TRIGGER_APPROVAL_REMINDER);
        assertThat(names(calls.get(0))).containsExactly("po1", "po3");
        assertThat(key.getValue()).isEqualTo("te-appr-remind:42:" + AT.toEpochMilli() + ":2026-10-05");
        assertThat(text.getValue().tr()).contains("1 gündür onayınızı bekliyor");
        assertThat(text.getValue().en()).contains("for 1 day;");
        assertThat(TechErrorPushService.reminderText(CTX, 3, "x").en()).contains("for 3 days;");
        assertThat(svc.approvalReminder(CTX, approvers(9L), 9L, "Teknik Kişi", 1, AT, "2026-10-05")).isZero();   // yalnız isteyen
    }

    // ── 3. Karar ──────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("karar → isteyen: onay + gönderim sonucu (gönderildi / kuyrukta / BAŞARISIZ kod), red + notun ilk 120 karakteri; kendi kararı ve pasif isteyen atlanır")
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
        assertThat(t.get(2).tr()).contains("e-posta BAŞARISIZ (PDF_BUSY)").contains("yeniden gönderin");
        assertThat(t.get(2).en()).contains("FAILED (PDF_BUSY)");
        assertThat(t.get(3).tr()).contains("reddedildi").contains("Reddeden: PO Bir.").contains("Not: Tablo eksik.");
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
