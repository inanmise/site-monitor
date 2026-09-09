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
 * DOKUZ İZLEME TÜRÜ, AYNI YETKİ KURALI.
 *
 * <p>{@code trigger*}, {@code update*} ve {@code delete*} uçlarının hepsi kaydın takımı üzerinde
 * yetki arar ({@code canOperateTeam}). Bir tür bunun yerine {@code requireAdmin} çağırırsa o tür
 * sessizce ayrışır ve kullanıcı bunu bir kural olarak değil ARIZA olarak görür.
 *
 * <p>Tam olarak bu yaşandı: DNS dokuz türün TEK istisnasıydı. Envanter-türevi bir DNS kaydında
 * kartın bütün düğmeleri kayboluyordu ve kullanıcı "düğmeler neden görünmüyor?" diye sordu.
 * Kuralın hiçbir yerde gerekçesi yazılı değildi ve koruyucu bir değeri de yoktu: envanter-türevi
 * DNS kaydı takımını ENVANTERDEN alır ({@code setTeamId(inv.getTeamId())}), yani aynı takım
 * yöneticisi zaten aynı domainin Port izlemesini ve envanter kaydının kendisini yönetebiliyordu.
 * Ayrıca ölçülmüş bir zararı vardı: toplu kontrol her satırda 403 dönüyor ve her 403 bir
 * ACCESS_DENIED denetim kaydı yazıyordu — 40 monitörlük sayfada tek tıklama 40 sahte güvenlik olayı.
 *
 * <p>Örnek düzeltmek yetmez: dokuz tür aynı kalıbı kopyalıyor ve sekizi doğruyken biri yanlıştı,
 * yani kalıp elle senkron tutulamıyor. Bu kapı KAYNAĞI tarar.
 */
class MonitorEndpointGateParityTest {

    private static final Path SOURCE =
            Path.of("src/main/java/com/sitemonitor/controller/MonitoringController.java");

    private static final List<String> TYPES = List.of(
            "Port", "Dns", "Keyword", "Http", "Ping", "Page", "PageSpeed", "Domain", "Scripted");

    private static final List<String> VERBS = List.of("trigger", "update", "delete");

    @Test
    @DisplayName("hiçbir izleme türü kardeşlerinden ayrı bir yetki kuralı kullanmaz")
    void noMonitorTypeUsesAStricterGateThanItsSiblings() throws Exception {
        List<String> lines = Files.readAllLines(SOURCE, StandardCharsets.UTF_8);
        List<String> offenders = new ArrayList<>();
        int checked = 0;

        for (String verb : VERBS) {
            for (String type : TYPES) {
                int start = indexOfMethod(lines, verb + type);
                if (start < 0) continue;          // o tür bu fiili desteklemiyor olabilir
                checked++;
                if (bodyUsesAdminGate(lines, start)) {
                    offenders.add(verb + type + " (satır " + (start + 1) + ")");
                }
            }
        }

        // Vakum koruması: metot adı deseni kayarsa hiçbir gövde taranmaz ve iddia SESSİZCE geçerdi.
        assertThat(checked)
                .as("kaynak taraması hiçbir uç bulamadı — kapı gerçekte bir şey ölçmüyor olurdu")
                .isGreaterThanOrEqualTo(20);

        assertThat(offenders)
                .as("kardeşlerinden ayrı (daha dar) yetki kuralı kullanan uç: arayüzde açıklamasız "
                        + "bir çıkmaz üretir — düğmeler kaybolur, kullanıcı arıza sanır")
                .isEmpty();
    }

    /** {@code public ResponseEntity<...> <ad>(} satırının indeksi; yoksa -1. */
    private static int indexOfMethod(List<String> lines, String name) {
        for (int i = 0; i < lines.size(); i++) {
            String l = lines.get(i);
            if (l.contains("public ResponseEntity") && l.contains(" " + name + "(")) return i;
        }
        return -1;
    }

    /**
     * Gövdede UÇ DÜZEYİNDE admin kapısı var mı — girinti tabanlı kaba sınır yeterli: gövde,
     * imzanın girintisinde kapanan {@code }} satırında biter.
     *
     * <p>ALAN DÜZEYİ ayrımı: istek gövdesindeki BELİRLİ bir alana bağlı {@code requireAdmin}
     * meşrudur ve bu kapının konusu değildir. Örnek {@code updatePort}:
     * <pre>if (!blank(body.get("sendData"))) requireAdmin(session);   // ham payload → yalnız admin</pre>
     * Burada kısıtlanan KAYDA ERİŞİM değil, tek bir tehlikeli ALANIN yazılmasıdır; uç hâlâ
     * takım-kapsamlıdır. Ayırt edici işaret, koşulun {@code body.} okumasıdır: kaydın kimliğine
     * (takım/standalone) değil, İSTEĞİN İÇERİĞİNE bağlıdır. Kapı bu yüzden çağrının kendi satırına
     * ve onu saran koşula bakar; ikisinde de {@code body.} yoksa bu bir yetki kapısıdır.
     */
    private static boolean bodyUsesAdminGate(List<String> lines, int start) {
        List<String> recent = new ArrayList<>();
        for (int i = start + 1; i < lines.size(); i++) {
            String l = lines.get(i);
            if (l.equals("    }")) return false;                 // metot bitti
            String t = l.stripLeading();
            if (t.isEmpty() || t.startsWith("*") || t.startsWith("//") || t.startsWith("/*")) continue;
            if (l.contains("requireAdmin(")) {
                if (l.contains("body.")) continue;                            // aynı satırda alan koşulu
                if (!recent.isEmpty() && recent.get(recent.size() - 1).contains("body.")) continue;
                return true;
            }
            recent.add(l);
        }
        return false;
    }

    @Test
    @DisplayName("kapı gerçekten ısırıyor — requireAdmin içeren gövde yakalanır")
    void gateBitesOnAdminOnlyBody() {
        List<String> withAdmin = List.of(
                "    public ResponseEntity<Map<String, Object>> triggerFoo(Long id) {",
                "        requireAdmin(session);",
                "        return ok();",
                "    }");
        assertThat(bodyUsesAdminGate(withAdmin, 0)).isTrue();

        List<String> teamScoped = List.of(
                "    public ResponseEntity<Map<String, Object>> triggerFoo(Long id) {",
                "        if (!canOperateTeam(session, m.getTeamId())) return forbidden(\"...\");",
                "        return ok();",
                "    }");
        assertThat(bodyUsesAdminGate(teamScoped, 0)).isFalse();

        // Kaydın KİMLİĞİNE bağlı else-dalı da bir yetki kapısıdır — DNS'te tam olarak bu vardı.
        List<String> identityBranch = List.of(
                "    public ResponseEntity<Map<String, Object>> updateFoo(Long id) {",
                "        if (Boolean.TRUE.equals(m.getStandalone())) {",
                "            if (!canOperateTeam(session, m.getTeamId())) return forbidden(\"...\");",
                "        } else {",
                "            requireAdmin(session);",
                "        }",
                "        return ok();",
                "    }");
        assertThat(bodyUsesAdminGate(identityBranch, 0)).isTrue();
    }

    @Test
    @DisplayName("ALAN düzeyi admin kısıtı yetki kapısı sayılmaz (yanlış pozitif üretmez)")
    void fieldLevelAdminRestrictionIsNotAGate() {
        // updatePort'taki gerçek satır: kısıtlanan kayda erişim değil, tek bir tehlikeli alan.
        List<String> fieldLevel = List.of(
                "    public ResponseEntity<Map<String, Object>> updateFoo(Long id) {",
                "        if (!canOperateTeam(session, m.getTeamId())) return forbidden(\"...\");",
                "        if (body.containsKey(\"sendData\")) {",
                "            if (!blank(body.get(\"sendData\"))) requireAdmin(session);",
                "        }",
                "        return ok();",
                "    }");
        assertThat(bodyUsesAdminGate(fieldLevel, 0)).isFalse();
    }
}
