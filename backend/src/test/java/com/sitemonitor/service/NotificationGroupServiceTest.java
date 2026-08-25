package com.sitemonitor.service;

import com.sitemonitor.model.NotificationGroup;
import com.sitemonitor.repository.NotificationGroupRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Çözümleme matrisi ve doğrulama kuralları.
 *
 * <p><b>Bu dosyanın birinci işi:</b> {@code overrideFor}'un grup YOKKEN hiçbir şey uydurmadığını
 * kanıtlamak. Servis kasten "tam zincir" değil — {@code Team.email} yedeği çağıranda kalıyor —
 * ve bu ayrım geliştirmenin birinci yasasının (grupsuz kurulumda davranış değişmez) mekanik
 * güvencesi. Zincir buraya taşınsaydı, mevcut alarm testleri servisi mock'ladığında sessizce
 * alıcısız kalır ve "davranış aynı" iddiası kanıtlanamaz hâle gelirdi.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class NotificationGroupServiceTest {

    private static final long TEAM_A = 1L;
    private static final long TEAM_B = 2L;

    @Mock NotificationGroupRepository repo;
    @InjectMocks NotificationGroupService service;

    private static NotificationGroup group(long id, long teamId, String name, String emails,
                                           boolean isDefault, boolean active) {
        NotificationGroup g = new NotificationGroup();
        g.setId(id);
        g.setTeamId(teamId);
        g.setName(name);
        g.setEmails(emails);
        g.setIsDefault(isDefault);
        g.setActive(active);
        return g;
    }

    // ── Çözümleme matrisi ────────────────────────────────────────────────────

    @Nested
    @DisplayName("overrideFor — çözümleme zinciri")
    class Resolution {

        @Test
        @DisplayName("Grup tablosu boşsa NONE döner — çağıran Team.email yoluna düşer (BİRİNCİ YASA)")
        void noGroups_returnsNone() {
            when(repo.findFirstByTeamIdAndIsDefaultTrueAndActiveTrue(TEAM_A)).thenReturn(Optional.empty());

            var ov = service.overrideFor(TEAM_A, null);

            assertThat(ov.applies()).isFalse();
            assertThat(ov.emails()).isEmpty();
            assertThat(ov.source()).isEqualTo(NotificationGroupService.Source.NONE);
            assertThat(ov.label()).isNull();
        }

        @Test
        @DisplayName("Damgalı grup uygulanır — takım varsayılanının ÖNÜNDE")
        void stampedGroup_winsOverDefault() {
            when(repo.findById(10L)).thenReturn(Optional.of(
                    group(10L, TEAM_A, "Ödeme Nöbetçi", "odeme@example.com", false, true)));

            var ov = service.overrideFor(TEAM_A, 10L);

            assertThat(ov.applies()).isTrue();
            assertThat(ov.emails()).containsExactly("odeme@example.com");
            assertThat(ov.source()).isEqualTo(NotificationGroupService.Source.GROUP);
            assertThat(ov.label()).isEqualTo("Grup: Ödeme Nöbetçi");
            // Damga çözdüyse varsayılan sorgusu HİÇ çalışmamalı (gereksiz sorgu = sweep maliyeti).
            verify(repo, never()).findFirstByTeamIdAndIsDefaultTrueAndActiveTrue(anyLong());
        }

        @Test
        @DisplayName("Damga yoksa takımın varsayılan grubu uygulanır")
        void noStamp_usesTeamDefault() {
            when(repo.findFirstByTeamIdAndIsDefaultTrueAndActiveTrue(TEAM_A)).thenReturn(Optional.of(
                    group(20L, TEAM_A, "Takım Nöbet", "nobet@example.com, yedek@example.com", true, true)));

            var ov = service.overrideFor(TEAM_A, null);

            assertThat(ov.applies()).isTrue();
            assertThat(ov.emails()).containsExactly("nobet@example.com", "yedek@example.com");
            assertThat(ov.source()).isEqualTo(NotificationGroupService.Source.TEAM_DEFAULT_GROUP);
        }

        @Test
        @DisplayName("Damgalı grup PASİFse zincirin kalanına düşer")
        void stampedInactive_fallsThrough() {
            when(repo.findById(10L)).thenReturn(Optional.of(
                    group(10L, TEAM_A, "Silinmiş", "eski@example.com", false, false)));
            when(repo.findFirstByTeamIdAndIsDefaultTrueAndActiveTrue(TEAM_A)).thenReturn(Optional.of(
                    group(20L, TEAM_A, "Takım Nöbet", "nobet@example.com", true, true)));

            var ov = service.overrideFor(TEAM_A, 10L);

            assertThat(ov.emails()).containsExactly("nobet@example.com");
            assertThat(ov.source()).isEqualTo(NotificationGroupService.Source.TEAM_DEFAULT_GROUP);
        }

        @Test
        @DisplayName("Damgalı grup SİLİNMİŞse (kayıt yok) zincirin kalanına düşer")
        void stampedMissing_fallsThrough() {
            when(repo.findById(10L)).thenReturn(Optional.empty());
            when(repo.findFirstByTeamIdAndIsDefaultTrueAndActiveTrue(TEAM_A)).thenReturn(Optional.empty());

            assertThat(service.overrideFor(TEAM_A, 10L).applies()).isFalse();
        }

        @Test
        @DisplayName("SIZINTI KAPISI: başka takımın grubu damgalıysa YOK SAYILIR")
        void stampedForeignTeam_ignored() {
            // Veri taşıma / elle müdahale / takım değişimi bu durumu üretebilir. Kabul edilseydi
            // bir takımın alarmı diğerinin nöbetçi listesine giderdi — hem yanlış yönlendirme
            // hem de o listeyi dolaylı ifşa.
            when(repo.findById(99L)).thenReturn(Optional.of(
                    group(99L, TEAM_B, "B Takımı Nöbet", "b-takim@example.com", false, true)));
            when(repo.findFirstByTeamIdAndIsDefaultTrueAndActiveTrue(TEAM_A)).thenReturn(Optional.of(
                    group(20L, TEAM_A, "A Nöbet", "a-nobet@example.com", true, true)));

            var ov = service.overrideFor(TEAM_A, 99L);

            assertThat(ov.emails()).containsExactly("a-nobet@example.com");
            assertThat(ov.emails()).doesNotContain("b-takim@example.com");
        }

        @Test
        @DisplayName("BOŞ adresli grup uygulanmaz — alarm sessizce kimseye gitmesin")
        void emptyGroup_fallsThrough() {
            when(repo.findById(10L)).thenReturn(Optional.of(group(10L, TEAM_A, "Boş", "   ", false, true)));
            when(repo.findFirstByTeamIdAndIsDefaultTrueAndActiveTrue(TEAM_A)).thenReturn(Optional.empty());

            assertThat(service.overrideFor(TEAM_A, 10L).applies()).isFalse();
        }

        @Test
        @DisplayName("teamId null ise NONE — sorgu bile çalışmaz")
        void nullTeam_returnsNone() {
            assertThat(service.overrideFor(null, 10L).applies()).isFalse();
            verify(repo, never()).findById(any());
        }

        @Test
        @DisplayName("Adresler tekilleşir ve boşluklar kırpılır (büyük/küçük harf duyarsız)")
        void emails_dedupedAndTrimmed() {
            when(repo.findById(10L)).thenReturn(Optional.of(
                    group(10L, TEAM_A, "G", " a@x.com , A@X.COM ,, b@x.com ", false, true)));

            assertThat(service.overrideFor(TEAM_A, 10L).emails()).containsExactly("a@x.com", "b@x.com");
        }
    }

    // ── Doğrulama (K8) ───────────────────────────────────────────────────────

    @Nested
    @DisplayName("validate — giriş kuralları")
    class Validation {

        @Test
        @DisplayName("Adsız grup reddedilir")
        void blankName_rejected() {
            assertThatThrownBy(() -> service.validate("  ", List.of("a@x.com"), false))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Grup adı");
        }

        @Test
        @DisplayName("BOŞ grup reddedilir — adressiz grup alarmı sessizce yutardı")
        void noEmails_rejected() {
            assertThatThrownBy(() -> service.validate("Nöbet", List.of(), false))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("en az bir e-posta");
        }

        @Test
        @DisplayName("Geçersiz adres reddedilir (yazım hatası yakalanır)")
        void malformedEmail_rejected() {
            assertThatThrownBy(() -> service.validate("Nöbet", List.of("ali@"), false))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Geçersiz e-posta");
        }

        @Test
        @DisplayName("15 adres tavanı aşılamaz")
        void tooManyEmails_rejected() {
            List<String> many = new java.util.ArrayList<>();
            for (int i = 0; i <= NotificationGroupService.MAX_EMAILS_PER_GROUP; i++) many.add("u" + i + "@x.com");

            assertThatThrownBy(() -> service.validate("Nöbet", many, false))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("en fazla");
        }

        @Test
        @DisplayName("Tek alana yapıştırılmış virgüllü liste ayrıştırılır ve tekilleşir")
        void pastedCsv_isSplit() {
            var in = service.validate("Nöbet", List.of("a@x.com, b@x.com; a@X.com"), false);
            assertThat(in.emails()).containsExactly("a@x.com", "b@x.com");
        }

        @Test
        @DisplayName("100 karakterden uzun ad reddedilir")
        void longName_rejected() {
            assertThatThrownBy(() -> service.validate("x".repeat(101), List.of("a@x.com"), false))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    // ── CRUD ─────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("create / update / toDto")
    class Crud {

        @Test
        @DisplayName("create: adresler normalize CSV olarak yazılır, kimlik alanları damgalanır")
        void create_writesNormalizedCsvAndIdentity() {
            when(repo.save(any())).thenAnswer(i -> i.getArgument(0));

            NotificationGroup g = service.create(TEAM_A,
                    new NotificationGroupService.GroupInput("Nöbet", List.of("a@example.com", "b@example.com"), false),
                    "ayse", "Ayşe Yılmaz");

            assertThat(g.getTeamId()).isEqualTo(TEAM_A);
            assertThat(g.getEmails()).isEqualTo("a@example.com, b@example.com");
            assertThat(g.getActive()).isTrue();
            assertThat(g.getIsDefault()).isFalse();
            assertThat(g.getCreatedBy()).isEqualTo("ayse");
            assertThat(g.getCreatedByName()).isEqualTo("Ayşe Yılmaz");
            assertThat(g.getCreatedAt()).isNotBlank();
            assertThat(g.getUpdatedAt()).isNotBlank();
            // makeDefault istenmedi → diğer varsayılanlara DOKUNULMAZ.
            verify(repo, never()).clearOtherDefaults(anyLong(), anyLong());
        }

        @Test
        @DisplayName("create + varsayılan iste: diğer varsayılanlar indirilir")
        void create_asDefault_demotesOthers() {
            when(repo.save(any())).thenAnswer(i -> {
                NotificationGroup x = i.getArgument(0);
                if (x.getId() == null) x.setId(77L);       // JPA kimlik atamasının yerine
                return x;
            });
            when(repo.clearOtherDefaults(eq(TEAM_A), anyLong())).thenReturn(1);

            NotificationGroup g = service.create(TEAM_A,
                    new NotificationGroupService.GroupInput("Nöbet", List.of("a@example.com"), true),
                    "ayse", "Ayşe");

            assertThat(g.getIsDefault()).isTrue();
            verify(repo).clearOtherDefaults(TEAM_A, 77L);
        }

        @Test
        @DisplayName("update: ad/adres güncellenir, güncelleyen damgalanır")
        void update_writesFieldsAndActor() {
            NotificationGroup existing = group(10L, TEAM_A, "Eski", "eski@example.com", false, true);
            when(repo.save(any())).thenAnswer(i -> i.getArgument(0));

            NotificationGroup g = service.update(existing,
                    new NotificationGroupService.GroupInput("Yeni", List.of("yeni@example.com"), false),
                    "burak", "Burak D.");

            assertThat(g.getName()).isEqualTo("Yeni");
            assertThat(g.getEmails()).isEqualTo("yeni@example.com");
            assertThat(g.getUpdatedBy()).isEqualTo("burak");
            assertThat(g.getUpdatedByName()).isEqualTo("Burak D.");
        }

        @Test
        @DisplayName("update: VARSAYILANLIK GERİ ALINABİLİR — takım 'artık takım maili' diyebilmeli")
        void update_canClearDefault() {
            // Kullanıcının dogrudan tetikledigi bir yol: varsayilan grubu duzenleyip anahtari
            // kapatmak. Bu dal hic calismamisti; sessizce calismasaydi kullanici anahtari
            // kapatir, kaydeder ve grup varsayilan KALMAYA devam ederdi.
            NotificationGroup existing = group(10L, TEAM_A, "Nöbet", "n@example.com", true, true);
            when(repo.save(any())).thenAnswer(i -> i.getArgument(0));

            NotificationGroup g = service.update(existing,
                    new NotificationGroupService.GroupInput("Nöbet", List.of("n@example.com"), false),
                    "burak", "Burak");

            assertThat(g.getIsDefault()).isFalse();
            verify(repo, never()).clearOtherDefaults(anyLong(), anyLong());
        }

        @Test
        @DisplayName("update + varsayılan iste: diğerleri indirilir")
        void update_makeDefault_demotesOthers() {
            NotificationGroup existing = group(10L, TEAM_A, "Nöbet", "n@example.com", false, true);
            when(repo.save(any())).thenAnswer(i -> i.getArgument(0));
            when(repo.clearOtherDefaults(TEAM_A, 10L)).thenReturn(2);

            NotificationGroup g = service.update(existing,
                    new NotificationGroupService.GroupInput("Nöbet", List.of("n@example.com"), true),
                    "burak", "Burak");

            assertThat(g.getIsDefault()).isTrue();
            verify(repo).clearOtherDefaults(TEAM_A, 10L);
        }

        @Test
        @DisplayName("toDto: adresler DİZİ olur (arayüz sayacı CSV ayrıştırmasın)")
        void toDto_splitsEmails() {
            var dto = service.toDto(group(10L, TEAM_A, "Nöbet", "a@example.com, b@example.com", true, true));

            assertThat(dto.get("id")).isEqualTo(10L);
            assertThat(dto.get("team_id")).isEqualTo(TEAM_A);
            assertThat(dto.get("emails")).isEqualTo(List.of("a@example.com", "b@example.com"));
            assertThat(dto.get("is_default")).isEqualTo(true);
            assertThat(dto.get("active")).isEqualTo(true);
        }

        @Test
        @DisplayName("toDto: null bayraklar false döner (arayüzde 'undefined' rozet çıkmasın)")
        void toDto_nullFlagsBecomeFalse() {
            NotificationGroup g = new NotificationGroup();
            g.setId(1L); g.setTeamId(TEAM_A); g.setName("X"); g.setEmails(null);
            g.setIsDefault(null); g.setActive(null);

            var dto = service.toDto(g);

            assertThat(dto.get("emails")).isEqualTo(List.of());
            assertThat(dto.get("is_default")).isEqualTo(false);
            assertThat(dto.get("active")).isEqualTo(false);
        }
    }

    // ── Tek-varsayılan kısıtı (K7) ───────────────────────────────────────────

    @Nested
    @DisplayName("makeDefault — takım başına EN FAZLA bir varsayılan")
    class DefaultConstraint {

        @Test
        @DisplayName("Yeni varsayılan, takımın diğer varsayılanlarını indirir")
        void makeDefault_demotesOthers() {
            NotificationGroup g = group(30L, TEAM_A, "Yeni", "y@x.com", false, true);
            when(repo.clearOtherDefaults(TEAM_A, 30L)).thenReturn(1);
            when(repo.save(any())).thenAnswer(i -> i.getArgument(0));

            var saved = service.makeDefault(g);

            verify(repo).clearOtherDefaults(TEAM_A, 30L);
            assertThat(saved.getIsDefault()).isTrue();
        }

        @Test
        @DisplayName("Silme KALICIDIR — satır gider, yumuşak silme kalıntısı bırakmaz")
        void deletePermanently_removesRow() {
            NotificationGroup g = group(30L, TEAM_A, "Nöbet", "n@example.com", true, true);

            service.deletePermanently(g);

            // Yumuşak silme satırı bırakıyordu ve o satırın ADI sonsuza dek rezerve kalıyordu:
            // kullanıcı hiçbir yerde göremediği bir kayda çarpıp "bu adda grup zaten var"
            // uyarısı alıyordu. Silme yalnız grup KULLANILMIYORKEN mümkün olduğu için kalıcı
            // silmek güvenli.
            verify(repo).delete(g);
            verify(repo, never()).save(any());
        }

        @Test
        @DisplayName("Takım içinde aynı ad reddedilir")
        void duplicateName_rejected() {
            when(repo.existsByTeamAndName(eq(TEAM_A), eq("Nöbet"), any())).thenReturn(true);

            assertThatThrownBy(() -> service.requireUniqueName(TEAM_A, "Nöbet", null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("zaten var");
        }
    }
}
