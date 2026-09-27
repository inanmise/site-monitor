package com.sitemonitor.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * YAPI KAPISI: {@code @Transactional} bir metot, AYNI sınıfın transactional OLMAYAN bir metodundan niteleyicisiz
 * ({@code foo(..)} ya da {@code this.foo(..)}) çağrılamaz.
 *
 * <p><b>Neden var (bug regresyon 2026-09-27, BO4/O1).</b> Spring'in {@code @Transactional}'ı (ve {@code @CacheEvict},
 * {@code @Async}) PROXY üzerinden işler; sınıf içi çağrı proxy'yi atlar ve anotasyon hiç devreye girmez — derleme
 * de testler de yeşil kalır. İki üretim örneği bulundu:
 * <ul>
 *   <li>{@code SchedulerService.writeResourceBreakdown}: "tek tx" sanılan sil + yaz iki ayrı commit'ti; yazma
 *       düşünce son iyi Kaynak Kırılımı kayboluyordu (09-23'te "kapalı" işaretlenmişti, fiilen açıktı).</li>
 *   <li>{@code InventoryAutoPurgeService.scheduled → purgeOlderThan}: tx'siz koşan {@code @Modifying} silme
 *       {@code TransactionRequiredException} atıyor, catch yutuyordu → gece otomatik boşaltma HİÇ çalışmıyordu.</li>
 * </ul>
 * {@code RepositoryWriteTransactionGuardTest} yalnız repository anotasyonuna bakar; bu sınıfı göremez.
 *
 * <p><b>Kural.</b> Çağıran metot da {@code @Transactional} ise (aynı tx'e katılır) serbest. Değilse: proxy
 * üzerinden çağır ({@code @Autowired @Lazy self} deseni — EscalationService/CertificateService/InventoryAutoPurgeService),
 * atomik işi ayrı bir bean'e/repository default metoduna taşı, ya da aşağıdaki listeye GEREKÇESİYLE ekle.
 *
 * <p>Tarayıcı bilinçli olarak sade (satır tabanlı): sınıf düzeyinde {@code @Transactional} taşıyan dosyaları
 * atlar, aşırı yüklemeyi ada göre eşler (yanlış pozitif olursa listeye gerekçeyle yazılır). Kendini sınayan
 * testler aşağıda — kapı gerçekten ısırıyor mu diye.
 */
class TransactionalSelfInvocationGuardTest {

    private static final Path MAIN = Path.of("src/main/java");

    /** "Sınıf#çağıran->çağrılan" → gerekçe. Liste bilinçli karar noktasıdır, kısayol değil. */
    private static final Map<String, String> ALLOWED = Map.of(
            "DomainExpiryRefreshService#refreshAll->persistToInventory",
            "persistToInventory satır başına save eder ve TÜM istisnaları kendi içinde yutar (return 0). Bir tx içinde"
                    + " koşsaydı yutulan hata tx'i rollback-only yapar, commit UnexpectedRollbackException ile TÜM tazeleme"
                    + " turunu düşürürdü. Satır başına commit burada bilinçli; eksik kalan satırı sonraki tur tamamlar.");

    private static final Pattern TX = Pattern.compile("@(?:org[.]springframework[.]transaction[.]annotation[.])?Transactional[(]?");
    private static final Pattern DECL = Pattern.compile(
            "^[ \t]+(?:(?:public|protected|private|static|final|synchronized|abstract|default)[ \t]+)*"
                    + "(?:<[^>]+>[ \t]+)?[A-Za-z_][\\w.]*(?:<.*>)?(?:\\[\\])*[ \t]+([A-Za-z_]\\w*)[ \t]*[(]");
    private static final Set<String> NOT_A_TYPE = Set.of("return", "new", "throw", "else", "case", "yield", "assert");

    record Method(String name, int line, boolean tx) {}

    /** Bir kaynak dosyanın ihlalleri: "Sınıf#çağıran->çağrılan (satır N)". Paket görünür: öz-test sınar. */
    static List<String> violations(String className, List<String> lines) {
        for (String l : lines) {
            if (l.startsWith("@") && TX.matcher(l).lookingAt()) return List.of();   // sınıf düzeyi tx
        }
        List<Method> methods = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            String l = lines.get(i);
            String t = l.trim();
            if (t.startsWith("*") || t.startsWith("//") || t.startsWith("/*") || t.endsWith(";")) continue;
            Matcher m = DECL.matcher(l);
            if (!m.find()) continue;
            String firstWord = t.split("[ \t(<]")[0];
            if (NOT_A_TYPE.contains(firstWord)) continue;
            boolean tx = false;
            for (int j = i - 1; j >= 0; j--) {
                String a = lines.get(j).trim();
                if (!a.startsWith("@")) break;
                if (TX.matcher(a).lookingAt()) tx = true;
            }
            methods.add(new Method(m.group(1), i, tx));
        }
        Set<String> txNames = new HashSet<>();
        for (Method m : methods) if (m.tx()) txNames.add(m.name());
        Set<Integer> declLines = new HashSet<>();
        for (Method m : methods) declLines.add(m.line());

        List<String> out = new ArrayList<>();
        for (String callee : txNames) {
            Pattern call = Pattern.compile("(?<![\\w.])(?:this[.])?" + Pattern.quote(callee) + "[ \t]*[(]");
            for (int i = 0; i < lines.size(); i++) {
                if (declLines.contains(i)) continue;
                String t = lines.get(i).trim();
                if (t.startsWith("*") || t.startsWith("//") || t.startsWith("/*")) continue;
                if (!call.matcher(lines.get(i)).find()) continue;
                Method enclosing = null;
                for (Method m : methods) if (m.line() < i) enclosing = m;
                if (enclosing == null || enclosing.tx() || enclosing.name().equals(callee)) continue;
                out.add(className + "#" + enclosing.name() + "->" + callee + " (satır " + (i + 1) + ")");
            }
        }
        return out;
    }

    private static String key(String violation) {
        return violation.substring(0, violation.indexOf(" (satır"));
    }

    private static Map<String, List<String>> scanMain() throws IOException {
        Map<String, List<String>> found = new TreeMap<>();
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path p : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                String cls = p.getFileName().toString().replace(".java", "");
                List<String> v = violations(cls, Files.readAllLines(p));
                if (!v.isEmpty()) found.put(p.toString(), v);
            }
        }
        return found;
    }

    @Test
    @DisplayName("KAPI: @Transactional metot aynı sınıfın tx'siz metodundan proxy'siz çağrılmıyor")
    void noSelfInvokedTransactionalFromNonTransactionalCaller() throws IOException {
        List<String> bad = new ArrayList<>();
        scanMain().values().forEach(v -> v.stream().filter(x -> !ALLOWED.containsKey(key(x))).forEach(bad::add));
        assertThat(bad)
                .as("Sınıf içi çağrı Spring proxy'sini atlar → @Transactional/@CacheEvict HİÇ işlemez. Proxy üzerinden"
                        + " çağır (self), işi ayrı bean'e taşı ya da ALLOWED'a gerekçesiyle ekle.")
                .isEmpty();
    }

    @Test
    @DisplayName("ALLOWED listesi bayatlamaz: her girdi bugün gerçekten bir eşleşmeye karşılık gelir")
    void allowListHasNoDeadEntries() throws IOException {
        Set<String> live = new HashSet<>();
        scanMain().values().forEach(v -> v.forEach(x -> live.add(key(x))));
        assertThat(live).containsAll(ALLOWED.keySet());
    }

    @Test
    @DisplayName("Öz-test: tx'siz çağıran → ISIRIR; tx'li çağıran, self./başka nesne üzerinden çağrı → serbest")
    void scannerBites() {
        List<String> src = List.of(
                "public class Ornek {",
                "    public void zamanlanmis() {",
                "        Sonuc r = temizle(5);",
                "        this.temizle(6);",
                "        self.temizle(7);",
                "        baska.temizle(8);",
                "    }",
                "",
                "    @Transactional",
                "    public void disTx() {",
                "        temizle(9);",
                "    }",
                "",
                "    @org.springframework.transaction.annotation.Transactional(readOnly = false)",
                "    public Sonuc temizle(int gun) {",
                "        return null;",
                "    }",
                "}");
        assertThat(violations("Ornek", src)).containsExactly(
                "Ornek#zamanlanmis->temizle (satır 3)", "Ornek#zamanlanmis->temizle (satır 4)");

        List<String> classLevel = new ArrayList<>(src);
        classLevel.add(0, "@Transactional");
        assertThat(violations("Ornek", classLevel)).isEmpty();
    }
}
