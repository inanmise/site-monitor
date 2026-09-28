package com.sitemonitor.service;

import com.sitemonitor.model.EscalationContact;
import com.sitemonitor.repository.EscalationContactRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * KAPI — eskalasyon kontağı KAPSAMI (2026-09-28 prod hatası: kontaksız takımın KRİTİK alarmı başka takımların
 * müdürlerine gidiyordu, çünkü "global" yedek yol {@code team_id}'yi süzmeyen sorgular kullanıyordu).
 *
 * <p>Kural: alarm alıcısı kontaklar YALNIZ {@link EscalationContactScope#forLevel} ile seçilir; o da yalnız takıma
 * süzülü sorgu çağırır. Bu sınıf iki yönden pinler:
 * <ul>
 *   <li><b>davranış</b> — takımsız istek depoya hiç gitmez; seviye → sorgu eşlemesi takım kimliğini taşır;</li>
 *   <li><b>kaynak</b> — depo arayüzünde takım süzgeçsiz okuma yalnız izinli listede; süzgeçsiz
 *       {@code findByActiveTrueOrderByRoleAsc} yalnız yönetim listesinde; seviye eşikli sorgular yalnız kapsam
 *       sınıfında (bir sonraki kopya — StormService'teki gibi — kurulursa build kırılır).</li>
 * </ul>
 */
class EscalationContactScopeTest {

    @Test
    @DisplayName("Takımsız alarm (teamId null): kişi YOK ve depoya hiç gidilmez (takımsız/global kontak alıcı değil)")
    void nullTeam_returnsNobody_withoutTouchingRepo() {
        EscalationContactRepository repo = mock(EscalationContactRepository.class);
        for (String lvl : new String[]{"CRITICAL", "HIGH", "WARNING", null, "bogus"}) {
            assertThat(EscalationContactScope.forLevel(repo, lvl, null)).isEmpty();
        }
        verifyNoInteractions(repo);
    }

    @Test
    @DisplayName("Seviye → takım süzgeçli sorgu: KRİTİK tüm etkin, YÜKSEK UYARI+YÜKSEK, diğerleri yalnız UYARI eşikli")
    void levelMapping_isTeamScoped() {
        EscalationContactRepository repo = mock(EscalationContactRepository.class);
        EscalationContact c = new EscalationContact();
        when(repo.findByTeamIdAndActiveTrueOrderByRoleAsc(7L)).thenReturn(List.of(c));
        when(repo.findByTeamIdAndMinAlertLevelInAndActiveTrue(7L, List.of("WARNING", "HIGH"))).thenReturn(List.of(c));
        when(repo.findByTeamIdAndMinAlertLevelAndActiveTrue(7L, "WARNING")).thenReturn(List.of(c));

        assertThat(EscalationContactScope.forLevel(repo, "CRITICAL", 7L)).containsExactly(c);
        assertThat(EscalationContactScope.forLevel(repo, " high ", 7L)).containsExactly(c);
        assertThat(EscalationContactScope.forLevel(repo, "WARNING", 7L)).containsExactly(c);
        assertThat(EscalationContactScope.forLevel(repo, null, 7L)).containsExactly(c);   // bilinmeyen → en dar küme

        verify(repo).findByTeamIdAndActiveTrueOrderByRoleAsc(7L);
        verify(repo).findByTeamIdAndMinAlertLevelInAndActiveTrue(7L, List.of("WARNING", "HIGH"));
        verify(repo, times(2)).findByTeamIdAndMinAlertLevelAndActiveTrue(7L, "WARNING");
        verify(repo, never()).findByActiveTrueOrderByRoleAsc();
        verifyNoMoreInteractions(repo);
    }

    /** Takım süzgeçli sorgu adı: {@code findBy|existsBy|countBy} + {@code TeamId} / {@code TeamIdIn} + And / OrderBy / son. */
    private static final java.util.regex.Pattern TEAM_SCOPED_QUERY =
            java.util.regex.Pattern.compile("(findBy|existsBy|countBy)TeamId(In)?(And.*|OrderBy.*)?");

    /** Takım süzgeci taşımayan okuma sorguları — yalnız bunlar; biri eklenirse bilinçli karar gerekir. */
    private static final Set<String> UNSCOPED_READS_ALLOWED = Set.of(
            "findByActiveTrueOrderByRoleAsc",   // yönetim listesi (global görüntüleyici) — alıcı çözümünde YASAK
            "findByUserId");                     // kullanıcı adı/e-posta eşitlemesi (UserService.updateUser)

    @Test
    @DisplayName("Depo: takım süzgeçsiz okuma yalnız izinli listede (yeni bir 'findBy…ActiveTrue' ancak bilinçli eklenir)")
    void repository_unscopedReadsAreWhitelisted() {
        Set<String> unscoped = new TreeSet<>();
        for (Method m : EscalationContactRepository.class.getDeclaredMethods()) {
            String n = m.getName();
            if (!(n.startsWith("findBy") || n.startsWith("existsBy") || n.startsWith("countBy"))) continue;
            // Takım süzgeci = ilk ölçüt "TeamId = ?" ya da "TeamId IN (…)". Eskiden adında "TeamId" GEÇEN her sorgu
            // süzülmüş sayılıyordu → `findByTeamIdIsNullAnd…` (takımsız = GLOBAL) ya da `…OrTeamId…` kapıdan geçerdi
            // (2026-09-28c ek-2). Desen sabit: TeamId(In)? ardından And / OrderBy / ad sonu.
            if (TEAM_SCOPED_QUERY.matcher(n).matches()) continue;
            unscoped.add(n);
        }
        assertThat(unscoped).as("takım süzgeçsiz kontak sorguları").isEqualTo(new TreeSet<>(UNSCOPED_READS_ALLOWED));
    }

    @Test
    @DisplayName("Kaynak: süzgeçsiz findByActiveTrueOrderByRoleAsc yalnız yönetim listesinde; seviye eşikli sorgular yalnız kapsam sınıfında")
    void source_recipientQueriesOnlyThroughScope() throws IOException {
        List<String> unscopedUse = new ArrayList<>();
        List<String> levelQueryUse = new ArrayList<>();
        Path root = mainRoot();
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path p : walk.filter(f -> f.toString().endsWith(".java")).toList()) {
                String file = p.getFileName().toString();
                if (file.equals("EscalationContactRepository.java")) continue;
                String src = stripComments(Files.readString(p, StandardCharsets.UTF_8));
                if (src.contains("findByActiveTrueOrderByRoleAsc(") && !file.equals("AdminController.java"))
                    unscopedUse.add(root.relativize(p).toString());
                if ((src.contains("findByTeamIdAndMinAlertLevel") || src.contains("MinAlertLevelAndActiveTrue")
                        || src.contains("MinAlertLevelInAndActiveTrue")) && !file.equals("EscalationContactScope.java"))
                    levelQueryUse.add(root.relativize(p).toString());
            }
        }
        assertThat(unscopedUse).as("takım süzgeçsiz kontak sorgusu alıcı çözümünde kullanılıyor").isEmpty();
        assertThat(levelQueryUse).as("seviye eşikli kontak seçimi EscalationContactScope dışında kopyalanmış").isEmpty();
    }

    private static Path mainRoot() {
        Path p = Path.of("src/main/java");
        return Files.isDirectory(p) ? p : Path.of("backend/src/main/java");
    }

    /** Yorumlar elenir: javadoc'ta eski sorgu adının geçmesi (tarihçe notu) ihlal sayılmaz. */
    private static String stripComments(String s) {
        return s.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//[^\\n]*", "");
    }
}
