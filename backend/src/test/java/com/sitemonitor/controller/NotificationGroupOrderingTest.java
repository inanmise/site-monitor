package com.sitemonitor.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BİLDİRİM GRUBU, NİHAİ TAKIMA GÖRE DOĞRULANIR.
 *
 * <p>{@code applyNotificationGroup} grubun monitörün takımına ait olup olmadığına bakar. Bu yüzden
 * çağrıldığı anda monitörün takımı KESİNLEŞMİŞ olmalıdır. On bir uçta değildi ve iki ayrı kusur
 * üretiyordu:
 *
 * <ul>
 *   <li><b>Create (4 uç):</b> çağrı {@code m.setTeamId(teamId)}'den önceydi; {@code m} yeni nesne
 *       olduğu için takım null'dı ve grup SEÇİLEREK izleme oluşturmak her seferinde 400 veriyordu.
 *       Hata mesajı da yanlıştı: "bu takıma ait değil" denen grup kullanıcının kendi takımınındı.</li>
 *   <li><b>Update (7 uç):</b> çağrı {@code resolveTeamChange}'den önceydi; grup ESKİ takıma göre
 *       doğrulanıp monitör sonra yeni takıma taşınıyordu. Tek istekte
 *       {@code {teamId: B, notificationGroupId: <A'nın grubu>}} kabul ediliyor, ortaya B takımına
 *       ait ama A takımının nöbetçi listesine alarm yollayan bir monitör çıkıyordu.</li>
 * </ul>
 *
 * <p>Örnek düzeltmek yetmez: dokuz izleme türü aynı kalıbı kopyalıyor ve beşi doğru, dördü
 * yanlıştı — yani kalıp elle senkron tutulamıyor. Bu kapı KAYNAĞI tarar: iki satırı da içeren her
 * metotta takım atamasının önce geldiğini zorlar. Derleyici bu sırayı göremez, testler de
 * göremezdi (mükerrer kontrolüne takılmayan bir gövde 400 dönüyordu ve kimse grup göndermiyordu).
 */
class NotificationGroupOrderingTest {

    private static final Path SOURCE =
            Path.of("src/main/java/com/sitemonitor/controller/MonitoringController.java");

    @Test
    @DisplayName("her metotta takım ataması applyNotificationGroup'tan ÖNCE gelir")
    void teamIsAssignedBeforeNotificationGroupIsValidated() throws Exception {
        List<String> lines = Files.readAllLines(SOURCE, StandardCharsets.UTF_8);

        // Metot sınırı: girinti 4 olan "public|private ... (" satırı. Kaba ama bu dosya için
        // yeterli ve kırılgan bir AST bağımlılığı getirmiyor.
        List<String> offenders = new ArrayList<>();
        int methodStart = 0;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).matches("^    (public|private|protected) .*\\(.*")) {
                check(lines, methodStart, i, offenders);
                methodStart = i;
            }
        }
        check(lines, methodStart, lines.size(), offenders);

        assertThat(offenders)
                .as("applyNotificationGroup, takım atanmadan ÖNCE çağrılıyor — create yolunda 400, "
                        + "update yolunda takımlar arası yanlış yönlendirme üretir")
                .isEmpty();
    }

    /** Bir metot gövdesinde sırayı denetler; ihlal varsa satır numarasıyla kaydeder. */
    private static void check(List<String> lines, int from, int to, List<String> offenders) {
        int apply = -1, team = -1;
        for (int i = from; i < to; i++) {
            String l = lines.get(i);
            // Yorum satırlarını atla — javadoc bu iki adı bilerek anıyor.
            String trimmed = l.stripLeading();
            if (trimmed.startsWith("*") || trimmed.startsWith("//") || trimmed.startsWith("/*")) continue;
            if (apply < 0 && l.contains("applyNotificationGroup(body")) apply = i;
            if (team < 0 && l.contains("m.setTeamId(")) team = i;
        }
        if (apply >= 0 && team >= 0 && team > apply) {
            offenders.add("satır " + (apply + 1) + ": setTeamId " + (team + 1) + "'de, yani SONRA");
        }
    }

    @Test
    @DisplayName("kapı gerçekten ısırıyor — ters sıralı gövde yakalanır")
    void gateBitesOnReversedOrder() {
        List<String> reversed = List.of(
                "    public void createSomething() {",
                "        m.setNotificationGroupId(applyNotificationGroup(body, m.getTeamId(), null));",
                "        m.setTeamId(teamId);",
                "    }");
        List<String> offenders = new ArrayList<>();
        check(reversed, 0, reversed.size(), offenders);
        assertThat(offenders).hasSize(1);
    }

    @Test
    @DisplayName("doğru sıra yanlış alarm üretmez")
    void gateAcceptsCorrectOrder() {
        List<String> correct = List.of(
                "    public void createSomething() {",
                "        m.setTeamId(teamId);",
                "        m.setNotificationGroupId(applyNotificationGroup(body, m.getTeamId(), null));",
                "    }");
        List<String> offenders = new ArrayList<>();
        check(correct, 0, correct.size(), offenders);
        assertThat(offenders).isEmpty();
    }
}
