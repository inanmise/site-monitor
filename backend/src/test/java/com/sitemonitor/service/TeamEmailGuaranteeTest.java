package com.sitemonitor.service;

import com.sitemonitor.model.NotificationGroup;
import com.sitemonitor.repository.NotificationGroupRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * TAKIM ADRESİ GARANTİSİ — grup tarafında NE bozulursa bozulsun alarm alıcısız kalmaz.
 *
 * <p>Bu dosya tek bir değişmezi savunuyor: <b>grup yolundan kullanılabilir bir adres çıkmıyorsa
 * {@code overrideFor} DAİMA {@code NONE} döner</b>, çağıran da kendi {@code Team.email} koluna
 * düşer. Yani bildirim grupları bir <i>ek katman</i>dır; arkalarındaki takım adresi her zaman
 * yerinde durur.
 *
 * <p>Neden ayrı ve matris hâlinde: bu garanti üç ayrı gönderim yolunun (alarm hunisi, toplu
 * kesinti, olay bildirimi) ORTAK karar noktasına dayanıyor. Tek bir dal yanlışlıkla "uygulandı"
 * derse alarm bozuk bir gruba gider ve takım hiçbir şey duymaz — sessiz ve pahalı bir hata.
 * Senaryoları tek tek değil topluca sabitlemek, ileride eklenecek bir dalın da aynı sözleşmeye
 * uymasını zorunlu kılar.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TeamEmailGuaranteeTest {

    private static final long TEAM = 1L;
    private static final long OTHER_TEAM = 2L;
    private static final long STAMP = 10L;

    @Mock NotificationGroupRepository repo;

    private NotificationGroupService service() {
        return new NotificationGroupService(repo);
    }

    private static NotificationGroup group(Consumer<NotificationGroup> tweak) {
        NotificationGroup g = new NotificationGroup();
        g.setId(STAMP);
        g.setTeamId(TEAM);
        g.setName("Nöbet");
        g.setEmails("nobet@example.com");
        g.setActive(true);
        g.setIsDefault(false);
        tweak.accept(g);
        return g;
    }

    /** Grubun bozulabileceği HER hâl — hepsinin sonucu aynı olmalı: takım adresine düş. */
    static Stream<Object[]> brokenGroups() {
        return Stream.of(
                new Object[]{"grup KAYDI YOK (kalıcı silindi)",        null},
                new Object[]{"grup PASİF (eski yumuşak silme)",        group(g -> g.setActive(false))},
                new Object[]{"grup BAŞKA takıma ait",                  group(g -> g.setTeamId(OTHER_TEAM))},
                new Object[]{"adres alanı NULL",                       group(g -> g.setEmails(null))},
                new Object[]{"adres alanı BOŞ",                        group(g -> g.setEmails(""))},
                new Object[]{"adres alanı yalnız BOŞLUK",              group(g -> g.setEmails("   "))},
                new Object[]{"adres alanı yalnız AYIRICI",             group(g -> g.setEmails(" , , "))},
                new Object[]{"adres DEĞİL (serbest metin)",            group(g -> g.setEmails("asdf"))},
                new Object[]{"adres DEĞİL (ad yazılmış)",              group(g -> g.setEmails("Nöbet Listesi"))},
                new Object[]{"adres yarım (@ var, alan yok)",          group(g -> g.setEmails("ad.soyad@"))},
                new Object[]{"adres yarım (alan var, ad yok)",         group(g -> g.setEmails("@example.com"))},
                new Object[]{"adres uzantısız",                        group(g -> g.setEmails("ad@localhost"))},
                new Object[]{"hepsi bozuk (çoklu)",                    group(g -> g.setEmails("asdf, qwer, @x"))},
                new Object[]{"grup ADI boş (ama adres de bozuk)",      group(g -> { g.setName(""); g.setEmails("asdf"); })});
    }

    @ParameterizedTest(name = "DAMGALI grup bozuk → takım adresi: {0}")
    @MethodSource("brokenGroups")
    void stampedGroupBroken_fallsBackToTeamEmail(String label, NotificationGroup g) {
        when(repo.findById(STAMP)).thenReturn(Optional.ofNullable(g));
        when(repo.findFirstByTeamIdAndIsDefaultTrueAndActiveTrue(TEAM)).thenReturn(Optional.empty());

        var ov = service().overrideFor(TEAM, STAMP);

        assertThat(ov.applies())
                .as("Grup uygulanmamalı (%s) — aksi halde alarm kullanılamaz bir adrese gider "
                  + "ve takım hiçbir şey duymaz", label)
                .isFalse();
        assertThat(ov.emails()).isEmpty();
    }

    @ParameterizedTest(name = "VARSAYILAN grup bozuk → takım adresi: {0}")
    @MethodSource("brokenGroups")
    void defaultGroupBroken_fallsBackToTeamEmail(String label, NotificationGroup g) {
        // Varsayılan sorgusu zaten aktif+varsayılan süzüyor; burada asıl sınanan ADRES tarafı.
        when(repo.findFirstByTeamIdAndIsDefaultTrueAndActiveTrue(TEAM))
                .thenReturn(Optional.ofNullable(g == null || g.getTeamId() == null
                        || g.getTeamId() != TEAM ? null : g));

        var ov = service().overrideFor(TEAM, null);

        assertThat(ov.applies()).as(label).isFalse();
    }

    @Test
    @DisplayName("Bozuk adresler ELENİR, sağlam olan KALIR — kısmi bozukluk alarmı düşürmez")
    void partiallyBrokenGroup_keepsUsableAddresses() {
        // Grup tamamen atılsaydı, tek bir yazım hatası yüzünden nöbetçi listesi devre dışı
        // kalır ve alarm sessizce takım kutusuna kayardı.
        when(repo.findById(STAMP)).thenReturn(Optional.of(
                group(g -> g.setEmails("asdf, nobet@example.com, @bozuk, yedek@example.com"))));

        var ov = service().overrideFor(TEAM, STAMP);

        assertThat(ov.applies()).isTrue();
        assertThat(ov.emails()).containsExactly("nobet@example.com", "yedek@example.com");
    }

    @Test
    @DisplayName("SAĞLAM grup normal şekilde uygulanır — garanti, çalışan grubu engellemez")
    void healthyGroup_stillApplies() {
        when(repo.findById(STAMP)).thenReturn(Optional.of(group(g -> { })));

        var ov = service().overrideFor(TEAM, STAMP);

        assertThat(ov.applies()).isTrue();
        assertThat(ov.emails()).containsExactly("nobet@example.com");
    }

    @Test
    @DisplayName("Ekranda ham içerik GİZLENMEZ — yönetici bozuk girdiyi görüp düzeltebilmeli")
    void toDto_showsRawStoredValue() {
        var dto = service().toDto(group(g -> g.setEmails("asdf, nobet@example.com")));

        // Gönderimde elenen değer YÖNETİCİDEN saklanmaz; aksi halde "neden mail gitmiyor"
        // sorusunun cevabı hiçbir ekranda görünmezdi.
        assertThat(dto.get("emails")).isEqualTo(List.of("asdf", "nobet@example.com"));
    }
}
