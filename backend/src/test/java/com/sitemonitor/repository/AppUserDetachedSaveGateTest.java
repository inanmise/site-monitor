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
 * KAYNAK KAPISI (2026-10-23, çok pod Sürüm 2a — MP-51): {@code AppUserRepository.save(...)} yalnız kullanıcıyı AYNI işlemde
 * okuyup kaydeden {@code @Transactional} yöntemlerde çağrılır. İşlem DIŞINDA okunmuş (ayrık) bir {@code AppUser}'ı
 * {@code save()} ile birleştirmek (merge) satırın tüm alanlarını o okumadaki değerlerle yazar — başka bir istekte (başka pod)
 * işlenen pasifleştirme, kilit, rol ya da yeni oturum kimliği geri alınır; {@code @DynamicUpdate} bunu ÖNLEMEZ. 20.165.1'de
 * sekiz yol böyleydi (push tercihleri, ürün turu, profil e-postası, yönetici profil alanları, kilit temizliği).
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

    @Test
    @DisplayName("AppUserRepository.save yalnız @Transactional yöntemlerde (kullanıcı aynı işlemde okunur) — ayrık nesne birleştirme yok")
    void appUserSaveOnlyInsideTransactions() throws IOException {
        List<String> offenders = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String src = Files.readString(f, StandardCharsets.UTF_8);
                Matcher fm = FIELD.matcher(src);
                Set<String> repoNames = new LinkedHashSet<>();
                while (fm.find()) repoNames.add(fm.group(1));
                if (repoNames.isEmpty()) continue;
                String[] lines = src.split("\n", -1);
                String cls = f.getFileName().toString().replace(".java", "");
                for (int i = 0; i < lines.length; i++) {
                    for (String repo : repoNames) {
                        if (!lines[i].matches(".*\\b" + Pattern.quote(repo) + "\\.save(?:All|AndFlush)?\\(.*")) continue;
                        String trimmed = lines[i].strip();
                        if (trimmed.startsWith("//") || trimmed.startsWith("*")) continue;
                        int decl = enclosingMethod(lines, i);
                        String method = decl < 0 ? "?" : methodName(lines[decl]);
                        String id = cls + "#" + method;
                        seen.add(id);
                        if (ALLOWED.containsKey(id)) continue;
                        if (decl < 0 || !annotatedTransactional(lines, decl)) {
                            offenders.add(f + ":" + (i + 1) + " " + id + " → " + trimmed);
                        }
                    }
                }
            }
        }
        assertThat(offenders)
                .as("AppUserRepository.save işlem DIŞINDA (ayrık nesne birleştirme riski). Kullanıcıyı @Transactional yöntemde "
                        + "okuyup kaydedin ya da hedefli UPDATE yazın (AppUserRepository#update*).")
                .isEmpty();
        assertThat(seen).as("kapı gerçekten bir şey tarıyor").contains("UserService#updateUser");
        for (String allowed : ALLOWED.keySet()) {
            assertThat(seen).as("izin listesinde bayat satır: %s", allowed).contains(allowed);
        }
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
