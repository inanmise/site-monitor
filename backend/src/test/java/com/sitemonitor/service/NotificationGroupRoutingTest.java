package com.sitemonitor.service;

import com.sitemonitor.model.NotificationGroup;
import com.sitemonitor.model.Team;
import com.sitemonitor.repository.NotificationGroupRepository;
import com.sitemonitor.repository.TeamRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Yönlendirmenin UÇTAN UCA sözleşmesi: "grup varsa grup, yoksa takım maili" kuralının
 * huninin HER kolunda AYNI şekilde işlediği.
 *
 * <p><b>Neden servis seviyesinde ve neden bu kadar sade:</b> {@code EscalationService}'in üç
 * bildirim yolu (ilk alarm / çözüm / yeniden-gönderim) aynı iki özel metottan geçiyor
 * ({@code collectTeamEmails}, {@code teamRecipientEmails}). Onların ortak karar noktası ise tek
 * bir çağrı: {@code notificationGroups.overrideFor(teamId, stamp)}. Buradaki testler o karar
 * noktasının sözleşmesini pinler; "üç yolun aynı damgayla aynı alıcıyı bulduğu" iddiası
 * {@code EscalationServiceTest}'in 82 testinin DEĞİŞMEDEN yeşil kalmasıyla birlikte kanıtlanır.
 *
 * <p><b>BİRİNCİ YASA burada da:</b> grup deposu boşken her senaryo {@code NONE} döner ve çağıran
 * kendi {@code Team.email} koluna düşer — yani bu dosyadaki "grupsuz kurulum" testleri, üretimdeki
 * bugünkü davranışın aynen sürdüğünün kanıtıdır.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class NotificationGroupRoutingTest {

    private static final long SY = 1L;   // izlemenin sahibi takım
    private static final long UG = 2L;   // uygulama geliştirme takımı (sertifika alarmlarında ikinci alıcı)

    @Mock NotificationGroupRepository groupRepo;
    @Mock TeamRepository teamRepo;

    private NotificationGroupService service() {
        return new NotificationGroupService(groupRepo);
    }

    private static NotificationGroup g(long id, long teamId, String name, String emails,
                                       boolean isDefault, boolean active) {
        NotificationGroup x = new NotificationGroup();
        x.setId(id); x.setTeamId(teamId); x.setName(name); x.setEmails(emails);
        x.setIsDefault(isDefault); x.setActive(active);
        return x;
    }

    private static Team team(long id, String email) {
        Team t = new Team();
        t.setId(id); t.setName("Takım " + id); t.setEmail(email);
        return t;
    }

    // ── Grupsuz kurulum: bugünkü davranış ────────────────────────────────────

    @Test
    @DisplayName("GRUPSUZ KURULUM: her iki takım için de NONE — çağıran Team.email'e düşer")
    void noGroupsAtAll_bothTeamsFallThrough() {
        when(groupRepo.findFirstByTeamIdAndIsDefaultTrueAndActiveTrue(SY)).thenReturn(Optional.empty());
        when(groupRepo.findFirstByTeamIdAndIsDefaultTrueAndActiveTrue(UG)).thenReturn(Optional.empty());
        var s = service();

        assertThat(s.overrideFor(SY, null).applies()).isFalse();
        assertThat(s.overrideFor(UG, null).applies()).isFalse();
        // Team.email hiç OKUNMAZ burada: yedeği çağıran işletiyor (birinci yasanın mimari hâli).
        org.mockito.Mockito.verifyNoInteractions(teamRepo);
    }

    // ── Üç yolun ortak karar noktası ─────────────────────────────────────────

    @Test
    @DisplayName("ÜÇ YOL: aynı damga → ilk alarm, çözüm ve yeniden-gönderim AYNI alıcıyı bulur")
    void sameStamp_sameRecipientsOnAllThreePaths() {
        when(groupRepo.findById(10L)).thenReturn(Optional.of(
                g(10L, SY, "Ödeme Nöbetçi", "odeme@example.com", false, true)));
        var s = service();

        // Üç yol da AlertEvent'teki AYNI damgayı okur (sendCombinedAlert/sendResolutionNotification/
        // reNotify hepsi event.getNotificationGroupId()'yi geçirir).
        var first    = s.overrideFor(SY, 10L);
        var resolved = s.overrideFor(SY, 10L);
        var renotify = s.overrideFor(SY, 10L);

        assertThat(first.emails()).isEqualTo(resolved.emails()).isEqualTo(renotify.emails());
        assertThat(first.label()).isEqualTo("Grup: Ödeme Nöbetçi");
    }

    @Test
    @DisplayName("DAMGA CANLI DEĞERİ EZER: alarm sürerken monitörün grubu değişse de kapanış aynı gruba gider")
    void stampWins_evenIfMonitorGroupChangedMeanwhile() {
        // Damgalı grup (alarm açılırken) — monitör bu arada 20'ye geçmiş olsun.
        when(groupRepo.findById(10L)).thenReturn(Optional.of(
                g(10L, SY, "Eski Nöbet", "eski@example.com", false, true)));
        when(groupRepo.findFirstByTeamIdAndIsDefaultTrueAndActiveTrue(SY)).thenReturn(Optional.of(
                g(20L, SY, "Yeni Nöbet", "yeni@example.com", true, true)));

        // Çözüm bildirimi DAMGAYI geçirir → alarmı açan ekip kapandığını öğrenir.
        assertThat(service().overrideFor(SY, 10L).emails()).containsExactly("eski@example.com");
    }

    // ── Storm ve olay bildirimi: takım seviyesi, damga YOK ───────────────────

    @Test
    @DisplayName("STORM/OLAY: damgasız çözüm takımın VARSAYILANINI bulur (monitör grubunu DEĞİL)")
    void stormAndIncident_resolveTeamDefaultOnly() {
        // Bu takımda hem monitöre özel bir grup (10) hem de varsayılan (20) var.
        when(groupRepo.findById(10L)).thenReturn(Optional.of(
                g(10L, SY, "Ödeme Nöbetçi", "odeme@example.com", false, true)));
        when(groupRepo.findFirstByTeamIdAndIsDefaultTrueAndActiveTrue(SY)).thenReturn(Optional.of(
                g(20L, SY, "Takım Nöbet", "nobet@example.com", true, true)));

        // Storm birçok monitörü TEK maile topluyor, olayın ise monitörü hiç yok:
        // içlerinden birinin grubunu seçmek keyfî olurdu → damgasız çağrı.
        var ov = service().overrideFor(SY);

        assertThat(ov.emails()).containsExactly("nobet@example.com");
        assertThat(ov.source()).isEqualTo(NotificationGroupService.Source.TEAM_DEFAULT_GROUP);
    }

    // ── Çift takım (SY + UG) ─────────────────────────────────────────────────

    @Test
    @DisplayName("ÇİFT TAKIM: her takım KENDİ zincirinden çözülür, damga yalnız sahibine uygulanır")
    void twoTeams_resolvedIndependently() {
        when(groupRepo.findById(10L)).thenReturn(Optional.of(
                g(10L, SY, "SY Nöbet", "sy-nobet@example.com", false, true)));
        when(groupRepo.findFirstByTeamIdAndIsDefaultTrueAndActiveTrue(UG)).thenReturn(Optional.of(
                g(30L, UG, "UG Nöbet", "ug-nobet@example.com", true, true)));
        var s = service();

        // Sahibi takım damgayı alır; UG takımına damga UYGULANMAZ (o, izlemenin sahibi değil).
        assertThat(s.overrideFor(SY, 10L).emails()).containsExactly("sy-nobet@example.com");
        assertThat(s.overrideFor(UG, null).emails()).containsExactly("ug-nobet@example.com");
    }

    @Test
    @DisplayName("ÇİFT TAKIM: yalnız birinde grup varsa DİĞERİ eski yoluna (Team.email) düşer")
    void twoTeams_onlyOneHasGroup() {
        when(groupRepo.findFirstByTeamIdAndIsDefaultTrueAndActiveTrue(SY)).thenReturn(Optional.of(
                g(20L, SY, "SY Nöbet", "sy-nobet@example.com", true, true)));
        when(groupRepo.findFirstByTeamIdAndIsDefaultTrueAndActiveTrue(UG)).thenReturn(Optional.empty());
        var s = service();

        assertThat(s.overrideFor(SY, null).applies()).isTrue();
        // UG için NONE → çağıran UG'nin Team.email'ini ekler; kısmi geçiş güvenlidir.
        assertThat(s.overrideFor(UG, null).applies()).isFalse();
    }
}
