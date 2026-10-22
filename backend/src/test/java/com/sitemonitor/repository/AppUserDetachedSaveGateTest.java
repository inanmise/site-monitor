package com.sitemonitor.repository;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * KAYNAK KAPISI (2026-10-23, çok pod Sürüm 2a — MP-51): {@code AppUserRepository.save(x)} yalnız {@code x}'i AYNI
 * {@code @Transactional} yöntemin içinde OKUYUP (find* / getReferenceById, ya da {@code new AppUser()}) kaydeden yerlerde.
 * İşlem DIŞINDA okunmuş (ayrık) bir {@code AppUser}'ı {@code save()} ile birleştirmek (merge) satırın tüm alanlarını o
 * okumadaki değerlerle yazar — başka bir istekte (başka pod) işlenen pasifleştirme, kilit, rol ya da yeni oturum kimliği geri
 * alınır; {@code @DynamicUpdate} bunu ÖNLEMEZ. 20.165.1'de sekiz yol böyleydi: dördü işlem dışı yöntemde kayıt, dördü
 * {@code @Transactional} bir yöntemin PARAMETRE olarak aldığı ayrık nesneyi kaydetmesi (ör. eski {@code savePushOptOut} —
 * inceleme 2026-10-23, LIM-1: yalnız {@code @Transactional} denetimi bu ikinci biçimi yakalamıyordu).
 *
 * <p>İki kural, her {@code save(x)} çağrısı için: (1) çevreleyen yöntem {@code @Transactional}; (2) {@code x} o yöntemin
 * içinde çağrıdan ÖNCE taze okunmuş: {@code x = …find…(…)} / {@code getReferenceById} / {@code new AppUser()}, bir
 * {@code Optional}'dan ({@code y.get()} / {@code orElse…}, y taze), {@code for (AppUser x : …find…(…))} ya da
 * {@code …find…(…).map/ifPresent(x -> …)} lambdası. Parametre ya da kaynağı görülmeyen değişken = ihlal.
 *
 * <p>Yeni bir yazım yolu: kullanıcıyı {@code @Transactional} yöntemin içinde okuyup kaydedin ya da yalnız değişen sütunları
 * yazan hedefli bir {@code @Modifying} sorgu ekleyin ({@code AppUserRepository#update*}). {@link #ALLOWED} yalnız
 * çağıranının işlemini kullandığı doğrulanmış özel yardımcıları taşır — gerekçesiz satır eklenemez.
 */
class AppUserDetachedSaveGateTest {

    private static final Path MAIN = Path.of("src/main/java/com/sitemonitor");

    /** {@code Dosya#yöntem} → gerekçe (çağıran @Transactional). */
    private static final Map<String, String> ALLOWED = Map.of(
            "LdapProvisioningService#upsert",
            "özel çekirdek; yalnız @Transactional provisionFromAd / resyncFromAd'den (ve onların içindeki müdür provizyonundan) "
                    + "çağrılır — satır aynı işlemde findByUsername ile okunur");

    private static final Pattern FIELD = Pattern.compile("\\bAppUserRepository\\s+(\\w+)\\s*[;=)]");
    private static final Pattern METHOD = Pattern.compile(
            "^    (?! )(?:@\\S+\\s+)*(?:(?:public|protected|private|static|final|synchronized|default)\\s+)*"
                    + "(?!(?:if|for|while|switch|catch|return|new|else|try|do)\\b)"
                    + "[\\w<>\\[\\],.?]+(?:\\s+[\\w<>\\[\\],.?]+)*?\\s+(\\w+)\\s*\\(");
    /** Taze okuma ifadesi (sağ taraf). */
    private static final Pattern FRESH_RHS = Pattern.compile("\\bfind\\w*\\(|\\bgetReferenceById\\(|\\bnew\\s+AppUser\\s*\\(|AppUser::new");
    /** {@code y.get()} / {@code y.orElse…(} — y'nin kendisi taze olmalı. */
    private static final Pattern OPTIONAL_RHS = Pattern.compile("^\\s*(\\w+)\\s*\\.\\s*(?:get|orElse\\w*)\\s*\\(");
    private static final int MAX_DEPTH = 3;

    @Test
    @DisplayName("AppUserRepository.save(x): @Transactional yöntemde VE x aynı yöntemde taze okunmuş — ayrık nesne birleştirme yok")
    void appUserSaveOnlyOnFreshEntitiesInsideTransactions() throws IOException {
        List<String> offenders = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String cls = f.getFileName().toString().replace(".java", "");
                for (String o : offenders(cls, Files.readString(f, StandardCharsets.UTF_8), seen)) offenders.add(f + ": " + o);
            }
        }
        assertThat(offenders)
                .as("AppUserRepository.save işlem DIŞINDA ya da ayrık (parametre / yöntemde okunmamış) bir nesneyle — "
                        + "birleştirme araya giren pasifleştirmeyi geri alır. Kullanıcıyı @Transactional yöntemde okuyup kaydedin "
                        + "ya da hedefli UPDATE yazın (AppUserRepository#update*).")
                .isEmpty();
        assertThat(seen).as("kapı gerçekten bir şey tarıyor").contains("UserService#updateUser", "TeamAdminService#moveAll");
        for (String allowed : ALLOWED.keySet()) {
            assertThat(seen).as("izin listesinde bayat satır: %s", allowed).contains(allowed);
        }
    }

    // ── Kapının kendi doğruluğu (LIM-1) ───────────────────────────────────────────────────────────────────────

    private static final String HEADER = "class Fixture {\n    private final AppUserRepository userRepo;\n\n";

    @Test
    @DisplayName("NEGATİF: 20.165.1'in savePushOptOut biçimi (@Transactional + PARAMETRE olarak gelen ayrık nesneyi save) yakalanır")
    void flagsTransactionalSaveOfDetachedParameter() {
        String src = HEADER
                + "    /** E1 opt-out kalıcılığı. */\n"
                + "    @Transactional\n"
                + "    public void savePushOptOut(AppUser user) {\n"
                + "        userRepo.save(user);\n"
                + "    }\n"
                + "\n"
                + "    @Transactional\n"
                + "    public AppUser savePushQuietHours(AppUser user, QuietHours.Config cfg) {\n"
                + "        user.setPushQuietStart(cfg.start());\n"
                + "        return userRepo.save(user);\n"
                + "    }\n}\n";
        assertThat(offenders("Fixture", src, new LinkedHashSet<>()))
                .hasSize(2)
                .allMatch(o -> o.contains("ayrık"))
                .anyMatch(o -> o.contains("Fixture#savePushOptOut"))
                .anyMatch(o -> o.contains("Fixture#savePushQuietHours"));
    }

    @Test
    @DisplayName("NEGATİF: 20.165.1'in checkLockout biçimi (işlem DIŞI yöntem, satır içeride okunsa da) yakalanır")
    void flagsNonTransactionalSave() {
        String src = HEADER
                + "    public LockoutStatus checkLockout(String username) {\n"
                + "        return userRepo.findByUsername(username).map(u -> {\n"
                + "            u.setLockoutUntil(null);\n"
                + "            userRepo.save(u);\n"
                + "            return new LockoutStatus(false, 0);\n"
                + "        }).orElse(null);\n"
                + "    }\n}\n";
        assertThat(offenders("Fixture", src, new LinkedHashSet<>()))
                .singleElement().satisfies(o -> assertThat(o).contains("Fixture#checkLockout").contains("@Transactional değil"));
    }

    @Test
    @DisplayName("POZİTİF: yöntem içinde taze okuma (findById, Optional.get, lambda, for-each, new AppUser, save sonrası yeniden atama) geçer")
    void acceptsFreshReads() {
        String src = HEADER
                + "    @Transactional\n"
                + "    public void a(Long id) {\n"
                + "        AppUser user = userRepo.findById(id)\n"
                + "                .orElseThrow();\n"
                + "        user.setActive(false);\n"
                + "        userRepo.save(user);\n"
                + "    }\n\n"
                + "    @Transactional\n"
                + "    public void b(String n) {\n"
                + "        Optional<AppUser> existing = userRepo.findByUsername(n);\n"
                + "        AppUser u = existing.get();\n"
                + "        u = userRepo.save(u);\n"
                + "        AppUser saved = userRepo.save(u);\n"
                + "    }\n\n"
                + "    @Transactional\n"
                + "    public void c(String n) {\n"
                + "        userRepo.findByUsername(n).ifPresent(u -> {\n"
                + "            if (x) { u.setX(1); userRepo.save(u); }\n"
                + "        });\n"
                + "        for (AppUser s : userRepo.findByManagerId(1L)) {\n"
                + "            userRepo.save(s);\n"
                + "        }\n"
                + "        AppUser fresh = new AppUser();\n"
                + "        userRepo.save(fresh);\n"
                + "    }\n}\n";
        Set<String> seen = new LinkedHashSet<>();
        assertThat(offenders("Fixture", src, seen)).isEmpty();
        assertThat(seen).containsExactly("Fixture#a", "Fixture#b", "Fixture#c");
    }

    // ── Çözümleyici ───────────────────────────────────────────────────────────────────────────────────────────

    /** Bir kaynak dosyadaki ihlaller ({@code Sınıf#yöntem satır → neden}); taranan çağrı yerleri {@code seen}'e yazılır. */
    static List<String> offenders(String cls, String src, Set<String> seen) {
        List<String> out = new ArrayList<>();
        Matcher fm = FIELD.matcher(src);
        Set<String> repoNames = new LinkedHashSet<>();
        while (fm.find()) repoNames.add(fm.group(1));
        if (repoNames.isEmpty()) return out;
        String[] lines = src.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String trimmed = lines[i].strip();
            if (trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*")) continue;
            for (String repo : repoNames) {
                Matcher call = Pattern.compile("\\b" + Pattern.quote(repo) + "\\.save(?:All|AndFlush)?\\(([^;]*)\\)").matcher(lines[i]);
                if (!call.find()) continue;
                int decl = enclosingMethod(lines, i);
                String method = decl < 0 ? "?" : methodName(lines[decl]);
                String id = cls + "#" + method;
                seen.add(id);
                if (ALLOWED.containsKey(id)) continue;
                String where = id + " satır " + (i + 1) + " → " + trimmed;
                if (decl < 0 || !annotatedTransactional(lines, decl)) {
                    out.add(where + " — yöntem @Transactional değil");
                    continue;
                }
                String arg = call.group(1).strip();
                boolean fresh = arg.matches("\\w+") ? freshVar(arg, lines, decl, i - 1, 0) : FRESH_RHS.matcher(arg).find();
                if (!fresh) out.add(where + " — '" + arg + "' ayrık (parametre ya da bu yöntemde taze okunmamış)");
            }
        }
        return out;
    }

    /**
     * {@code x}, {@code decl} (hariç) .. {@code from} (dahil) arasında taze okunmuş mu — geriye doğru en yakın kaynak belirler
     * ({@code x = repo.save(x)} kaynak sayılmaz, arama yukarı sürer).
     */
    static boolean freshVar(String x, String[] lines, int decl, int from, int depth) {
        if (depth > MAX_DEPTH) return false;
        String q = Pattern.quote(x);
        Pattern lambda = Pattern.compile("\\.\\s*(?:map|ifPresent|ifPresentOrElse|forEach|filter)\\s*\\(\\s*\\(?\\s*" + q + "\\s*\\)?\\s*->");
        Pattern forEach = Pattern.compile("for\\s*\\(\\s*(?:final\\s+)?AppUser\\s+" + q + "\\s*:\\s*(.+)\\)");
        Pattern assign = Pattern.compile("(?<![\\w.])" + q + "\\s*=(?!=)\\s*(.*)$");
        for (int i = from; i > decl; i--) {
            String line = lines[i];
            Matcher lm = lambda.matcher(line);
            if (lm.find()) return receiverFresh(line.substring(0, lm.start()), lines, decl, i, depth);
            Matcher fe = forEach.matcher(line);
            if (fe.find()) return rhsFresh(fe.group(1), lines, decl, i, depth);
            Matcher am = assign.matcher(line);
            if (am.find()) {
                String rhs = am.group(1);
                if (!rhs.contains(";") && i + 1 < lines.length) rhs = rhs + " " + lines[i + 1];
                if (rhs.contains(".save")) continue;   // x = repo.save(x) → asıl kaynak daha yukarıda
                return rhsFresh(rhs, lines, decl, i, depth);
            }
        }
        return false;   // parametre ya da kaynağı yöntemde görünmüyor
    }

    private static boolean rhsFresh(String rhs, String[] lines, int decl, int at, int depth) {
        if (FRESH_RHS.matcher(rhs).find()) return true;
        Matcher om = OPTIONAL_RHS.matcher(rhs);
        return om.find() && freshVar(om.group(1), lines, decl, at - 1, depth + 1);
    }

    private static boolean receiverFresh(String receiver, String[] lines, int decl, int at, int depth) {
        if (FRESH_RHS.matcher(receiver).find()) return true;
        Matcher m = Pattern.compile("(\\w+)\\s*$").matcher(receiver.strip());
        return m.find() && freshVar(m.group(1), lines, decl, at - 1, depth + 1);
    }

    /** Satırın içinde bulunduğu yöntem bildiriminin satır numarası (4 boşluk girintili bildirime geri tarama). */
    static int enclosingMethod(String[] lines, int from) {
        for (int i = from; i >= 0; i--) {
            if (METHOD.matcher(lines[i]).find()) return i;
        }
        return -1;
    }

    static String methodName(String declLine) {
        Matcher m = METHOD.matcher(declLine);
        return m.find() ? m.group(1) : "?";
    }

    /** Bildirimin hemen üstündeki ek açıklamalarda (Javadoc atlanır) {@code @Transactional} var mı. */
    static boolean annotatedTransactional(String[] lines, int decl) {
        if (lines[decl].contains("@Transactional") || lines[decl].contains("annotation.Transactional")) return true;
        for (int i = decl - 1; i >= 0 && i >= decl - 8; i--) {
            String t = lines[i].strip();
            if (t.isEmpty()) break;
            if (t.startsWith("@Transactional") || t.startsWith("@org.springframework.transaction.annotation.Transactional")) {
                return !t.contains("NOT_SUPPORTED") && !t.contains("NEVER");
            }
            if (t.startsWith("@") || t.startsWith("*") || t.startsWith("/**") || t.startsWith("*/") || t.startsWith("//")) continue;
            break;
        }
        return false;
    }
}
