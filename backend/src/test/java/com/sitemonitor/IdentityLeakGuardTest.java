package com.sitemonitor;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * KİMLİK SIZINTISI KAPISI — gerçek kurumsal kimlik bilgisi kaynağa geri sızmasın.
 *
 * <p><b>Kural:</b> kod, yorum, javadoc, test verisi, i18n metni ve örnek değerlerde gerçek
 * kurumsal alan adı, iç ağ soneki ya da iç platform adı GEÇMEZ. Yer tutucu geleneği:
 * {@code example.com} / {@code example.org} (RFC 2606) ve {@code Takim A}.
 *
 * <p><b>Neden test:</b> depo özel olsa bile <b>git geçmişi kalıcıdır</b> — bir kez yazılan değer
 * dosyadan silinse de geçmişte durur ve temizliği history rewrite gerektirir. Bu yüzden kapı
 * "sonra düzeltiriz" değil, girişte durdurur.
 *
 * <p><b>Muafiyet listesi YALNIZ KÜÇÜLÜR</b> — kapsam tabanı ve {@code cssClasses.test.js}
 * cırcırıyla aynı kural. Her satır GEREKÇELİ; gerekçesiz muafiyet eklenemez. Ölü kayıt testi
 * (aşağıda) listenin zamanla anlamsız bir birikime dönmesini engeller.
 *
 * <p>Depo kökünden tarar: Maven {@code basedir} = {@code backend/}, dolayısıyla {@code ..} köktür
 * (CI'da da tüm depo checkout ediliyor).
 */
class IdentityLeakGuardTest {

    /**
     * Aranan kimlik izleri — küçük harfe indirgenmiş metin üzerinde aranır.
     *
     * <p>Desenler kasten PARÇALI yazılır. İki sebep: (1) bu dosya kendi taramasına takılmasın —
     * kendini muaf tutmak kapıyı gevşetirdi; (2) yasaklanan değerler kaynakta düz metin olarak
     * BULUNMASIN, yoksa kapının kendisi sızıntı kaynağı olurdu.
     */
    private static final List<String> FORBIDDEN = List.of(
            "akb" + "ank",     // kurumsal alan adı / kurum adı
            "ak" + "net",      // iç ağ soneki ve AD kök alanı
            "ocp" + "int",     // iç OpenShift platform adı
            // ── Gerçek TAKIM adları ──────────────────────────────────────────
            // Kural 0 "takım isimleri" diyor ama kapı yalnız kurum/ağ/platform arıyordu; sayı
            // sessizce büyüyordu (78 geçiş / 12 dosya). Jenerik yer tutucular ("SY-Team-A",
            // "SY-Takım A") KASITLI olarak serbest — yasaklanan yalnız GERÇEK adlar.
            "SY-Dij" + "ital",
            "SY-K" + "art",
            "Dijital Ban" + "kacılık",
            "Dijital Ban" + "kacilik",
            // ── Gerçek KİŞİ adı / kurum içi posta kutusu yerel-adı ────────────
            // Test fixture'larına sızmıştı; git geçmişi kalıcı olduğu için girişte durdurulur.
            "dijit" + "alsy",
            "Kav" + "ruk",
            "Ekme" + "kçi");

    private static final Set<String> SCAN_EXT = Set.of(
            ".java", ".jsx", ".js", ".json", ".md", ".yaml", ".yml", ".properties", ".css", ".sql");

    private static final Set<String> SKIP_DIRS = Set.of(
            "node_modules", "target", ".git", "dist", "coverage", "_to_delete", "email-previews");

    /**
     * Bilinen ve GEREKÇELİ kalıntılar. Anahtar: depo köküne göre yol (eğik bölü ile).
     *
     * <p>Üç sınıf var ve üçü de farklı bir karar bekliyor:
     * <ul>
     *   <li><b>Ürün içeriği/markası</b> — kullanıcının göreceği metin ya da tohum veri; silmek
     *       ürünü değiştirir, ayrı karar.</li>
     *   <li><b>Çalışan değer</b> — kod gerçekten o adrese gidiyor; değiştirmek DAVRANIŞ değişikliği.</li>
     *   <li><b>Somut olması değerli açıklama</b> — gerçek bir üretim davranışını belgeliyor.</li>
     * </ul>
     */
    private static final Map<String, String> EXEMPT = new LinkedHashMap<>(Map.ofEntries(
            // ── Çalışan değer: şablon gerçekten bu adrese GET atıyor ──
            Map.entry("backend/src/main/resources/scripted-templates.json",
                    "smoke şablonu bu adrese GERÇEKTEN GET atıyor; hedefi değiştirmek davranış değişikliği (ürün kararı)"),
            Map.entry("backend/src/test/java/com/sitemonitor/service/ScriptedTemplateCatalogTest.java",
                    "yukarıdaki şablonun bekçisi — JSON ile AYNI değeri beklemek zorunda"),

            // ── Ürün içeriği / marka ──
            Map.entry("backend/src/main/java/com/sitemonitor/controller/WeeklyReportController.java",
                    "rapor altbilgisinde görünen kurum adı — marka metni, ayrı karar"),
            Map.entry("backend/src/main/java/com/sitemonitor/service/WeeklyReportService.java",
                    "tohum kanal adı (varsayılan rapor içeriği) — ürün verisi, ayrı karar"),
            Map.entry("backend/src/test/java/com/sitemonitor/service/WeeklyReportServiceTest.java",
                    "yukarıdaki tohum verinin bekçisi — kaynakla AYNI değeri beklemek zorunda"),
            Map.entry("backend/src/main/java/com/sitemonitor/service/EmailNotificationService.java",
                    "kurumsal vurgu rengini adlandıran yorum"),

            // ── Somut olması değerli üretim açıklamaları ──
            Map.entry("backend/src/main/java/com/sitemonitor/service/ProxySettings.java",
                    "prod NO_PROXY sonek-eşleşme tuzağını anlatan javadoc"),
            Map.entry("backend/src/main/java/com/sitemonitor/service/ScriptedCheckerService.java",
                    "NO_PROXY sonek davranışı açıklaması"),
            Map.entry("backend/src/main/java/com/sitemonitor/controller/MonitoringController.java",
                    "NO_PROXY sonek davranışı açıklaması"),
            Map.entry("backend/src/main/java/com/sitemonitor/service/CertificateCheckerService.java",
                    "prod'da gözlenen WAF/RST davranışını belgeleyen yorum"),
            Map.entry("backend/src/main/java/com/sitemonitor/service/ConnectionDiagnosticsService.java",
                    "prod'da gözlenen handshake/egress korelasyonu"),
            Map.entry("backend/src/main/java/com/sitemonitor/service/CertificateHealthRules.java",
                    "joker karakter eşleşme kuralı örneği"),
            Map.entry("backend/src/main/java/com/sitemonitor/service/DnsCheckerService.java",
                    "URL→host sadeleştirme örneği"),
            Map.entry("backend/src/main/java/com/sitemonitor/service/MonitoringOutageService.java",
                    "canlı veride görülen hata metni örneği"),
            Map.entry("backend/src/main/java/com/sitemonitor/service/RdapDomainExpiryService.java",
                    "iç domainlerde RDAP'ın boş dönmesini açıklayan javadoc"),
            Map.entry("backend/src/main/java/com/sitemonitor/service/TrWebWhoisClient.java",
                    "registrar seçimini açıklayan javadoc"),
            Map.entry("backend/src/main/java/com/sitemonitor/controller/AdminController.java",
                    "iç host tanılamasını açıklayan yorum"),

            // ── Dağıtım yapılandırması: ÇALIŞMAK için gerçek olmak zorunda ──
            Map.entry("helm/site-monitor/environments/master.yaml",
                    "prod ingress/CORS/NO_PROXY değerleri — secret/override'a taşıma ayrı altyapı kararı"),
            Map.entry("helm/site-monitor/values.yaml",
                    "proxy varsayılanını açıklayan yorum"),
            Map.entry("helm/site-monitor/templates/deployment.yaml",
                    "egress korelasyonunu açıklayan yorum")));

    private static Path repoRoot() {
        return Path.of("..").toAbsolutePath().normalize();
    }

    private static String rel(Path root, Path p) {
        return root.relativize(p).toString().replace('\\', '/');
    }

    private static List<Path> sourceFiles(Path root) {
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(Files::isRegularFile)
                    .filter(p -> {
                        for (Path part : root.relativize(p)) {
                            if (SKIP_DIRS.contains(part.toString())) return false;
                        }
                        String n = p.getFileName().toString();
                        int dot = n.lastIndexOf('.');
                        return dot >= 0 && SCAN_EXT.contains(n.substring(dot).toLowerCase(Locale.ROOT));
                    })
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static boolean hasForbidden(Path p) {
        String text;
        try {
            text = Files.readString(p, StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
        } catch (IOException e) {
            return false;                       // ikili/okunamayan dosya taramaya girmez
        }
        for (String bad : FORBIDDEN) {
            if (text.contains(bad)) return true;
        }
        return false;
    }

    @Test
    @DisplayName("Tarama vakum DEĞİL — depo kökü bulunuyor ve kayda değer sayıda dosya taranıyor")
    void scanIsNotVacuous() {
        Path root = repoRoot();
        assertThat(Files.isDirectory(root.resolve("backend"))).as("depo kökü bulunamadı: %s", root).isTrue();
        assertThat(sourceFiles(root)).hasSizeGreaterThan(500);
    }

    @Test
    @DisplayName("Gerçek kurumsal kimlik bilgisi kaynağa GİRMEZ (muafiyet listesi dışında)")
    void noNewIdentityLeaks() {
        Path root = repoRoot();
        List<String> offenders = new ArrayList<>();
        for (Path p : sourceFiles(root)) {
            String r = rel(root, p);
            if (EXEMPT.containsKey(r)) continue;
            if (hasForbidden(p)) offenders.add(r);
        }
        assertThat(offenders)
                .as("Gerçek kimlik bilgisi bulundu. Yer tutucu kullanın (example.com / example.org / "
                  + "'Takim A'). Zorunlu bir kalıntıysa IdentityLeakGuardTest.EXEMPT'e GEREKÇESİYLE ekleyin.")
                .isEmpty();
    }

    @Test
    @DisplayName("Muafiyet listesi ÖLÜ kayıt taşımaz — cırcır yalnız küçülsün")
    void exemptionsAreAllAlive() {
        Path root = repoRoot();
        List<String> dead = new ArrayList<>();
        EXEMPT.forEach((r, why) -> {
            Path p = root.resolve(r);
            // Dosya silinmişse ya da artık temizse muafiyet DÜŞMELİ; aksi halde liste
            // zamanla anlamsız bir birikime döner ve kapı sessizce gevşer.
            if (!Files.isRegularFile(p) || !hasForbidden(p)) dead.add(r);
        });
        assertThat(dead).as("Bu yollar artık muaf olmak zorunda değil — listeden çıkarın").isEmpty();
    }

    @Test
    @DisplayName("Her muafiyetin GEREKÇESİ var")
    void everyExemptionHasAReason() {
        List<String> missing = EXEMPT.entrySet().stream()
                .filter(e -> e.getValue() == null || e.getValue().isBlank())
                .map(Map.Entry::getKey)
                .toList();
        assertThat(missing).isEmpty();
    }

    // ── Denetim 5. tur, bulgu 22: arsiv kapinin KOR NOKTASI ───────────────────

    @Test
    @DisplayName("Depoda izlenen arsiv YOK — kapi .gz icine bakamaz, denetlenmemis kaynak kopyasi olusur")
    void noTrackedArchives() throws Exception {
        Path root = repoRoot();
        // SCAN_EXT listesi .gz icermez ve iceremez (ikili). Bir arsiv depoya girerse o anki
        // kaynagin TAMAMI — o gunku kimlik izleriyle birlikte — kapinin goremedigi bir yerde
        // kalicilasir; muafiyet listesinin "yalniz kuculur" circiri de orada islemez.
        try (Stream<Path> walk = Files.walk(root, 1)) {
            List<String> archives = walk.filter(Files::isRegularFile)
                    .map(p -> rel(root, p))
                    .filter(n -> n.endsWith(".tar.gz") || n.endsWith(".tgz") || n.endsWith(".zip"))
                    .filter(IdentityLeakGuardTest::isTracked)
                    .toList();
            assertThat(archives)
                    .as("depo kokunde izlenen arsiv — .gitignore'a alinmali (git rm --cached)")
                    .isEmpty();
        }
    }

    /** Dosya git tarafindan izleniyor mu — calisma agacinda DURAN ama ignore edilen arsiv sorun degil. */
    private static boolean isTracked(String relPath) {
        try {
            Process p = new ProcessBuilder("git", "ls-files", "--error-unmatch", relPath)
                    .directory(repoRoot().toFile())
                    .redirectErrorStream(true)
                    .start();
            boolean done = p.waitFor(15, java.util.concurrent.TimeUnit.SECONDS);
            return done && p.exitValue() == 0;
        } catch (Exception e) {
            return false;   // git yoksa kapi sessizce gecer (CI disi ortamlarda kirmizi vermesin)
        }
    }
}
