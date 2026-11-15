package com.sitemonitor.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Kaynak kapısı (kapı incelemesi 2026-11-15): izlenen adresler uygulama günlüğüne HAM yazılmaz. Prod DEBUG seviyesinde koşar ve
 * JSON günlükleri toplanır; {@code ?token=…}, {@code ?sig=…}, {@code user:pass@host} taşıyan bir izleme adresi her kontrol
 * hatasında günlüğe düşüyordu. Bir {@code log.*(…)} çağrısının (biçim dizesinden sonraki) argümanı çıplak bir adres ifadesiyse
 * ({@code url}, {@code m.getUrl()}, {@code target}, {@code finalUrl}, {@code sanitize(url)} …) kapı kırmızı:
 * {@link SecretMask#logUrl} ile sarın. Ayrıca {@link SecretMask#logUrl} kuralının birim testi.
 */
class LogUrlMaskingGateTest {

    private static final Path SRC = Path.of("src/main/java/com/sitemonitor");
    private static final Pattern LOG_CALL = Pattern.compile("\\blog\\.(trace|debug|info|warn|error)\\(");
    private static final Pattern URL_ARG = Pattern.compile(
            "^(?:[A-Za-z_]\\w*(?:\\(\\))?\\.)*(?:getUrl\\(\\)|url|finalUrl|rawUrl|targetUrl|getFinalUrl\\(\\)|target|getTarget\\(\\)"
                    + "|target\\(\\)|uri|getUri\\(\\))$");
    private static final Pattern SANITIZE = Pattern.compile("^(?:\\w+\\.)?sanitize\\((.*)\\)$");

    /** Kaynaktaki çıplak adres argümanları ({@code dosya:satır: argüman}). */
    static List<String> offenders(String file, String s) {
        List<String> out = new ArrayList<>();
        Matcher m = LOG_CALL.matcher(s);
        while (m.find()) {
            List<String> args = args(s, m.end());
            for (int k = 1; k < args.size(); k++) {
                String a = args.get(k).replaceAll("\\s+", " ");
                Matcher sm = SANITIZE.matcher(a);
                String inner = sm.matches() ? sm.group(1) : a;
                if (URL_ARG.matcher(inner).matches()) {
                    int line = 1;
                    for (int i = 0; i < m.start(); i++) if (s.charAt(i) == '\n') line++;
                    out.add(file + ":" + line + ": " + a);
                }
            }
        }
        return out;
    }

    /** Üst düzey argümanlar — dize / karakter sabitleri ve iç içe parantez gözetilir (doğrusal). */
    private static List<String> args(String s, int i) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        int depth = 0, n = s.length();
        while (i < n) {
            char c = s.charAt(i);
            if (c == '"' || c == '\'') {
                int j = i + 1;
                while (j < n && s.charAt(j) != c) j += s.charAt(j) == '\\' ? 2 : 1;
                cur.append(s, i, Math.min(n, j + 1));
                i = j + 1;
                continue;
            }
            if (c == '(' || c == '[' || c == '{') depth++;
            else if (c == ')' || c == ']' || c == '}') {
                if (depth == 0) { out.add(cur.toString().trim()); return out; }
                depth--;
            } else if (c == ',' && depth == 0) {
                out.add(cur.toString().trim());
                cur.setLength(0);
                i++;
                continue;
            }
            cur.append(c);
            i++;
        }
        return out;
    }

    @Test
    @DisplayName("hiçbir günlük çağrısı izleme adresini HAM yazmaz (SecretMask.logUrl)")
    void noRawUrlInLogCalls() throws IOException {
        List<String> found = new ArrayList<>();
        try (Stream<Path> files = Files.walk(SRC)) {
            for (Path f : files.filter(x -> x.toString().endsWith(".java")).toList()) {
                found.addAll(offenders(SRC.relativize(f).toString(), Files.readString(f, StandardCharsets.UTF_8)));
            }
        }
        assertThat(found).as("izleme adresi günlüğe ham yazılıyor — SecretMask.logUrl(...) ile sarın").isEmpty();
    }

    @Test
    @DisplayName("kapı çıplak adres argümanını yakalar; sarılmış / ilgisiz argümanı yakalamaz")
    void gateSelfTest() {
        String src = """
                class X {
                    void a(Monitor m, String url, String target) {
                        log.warn("HTTP check failed for {}: {}", m.getUrl(), e.getMessage());
                        log.debug("x {} {}", url,
                                e.toString());
                        log.info("y {}", sanitize(url));
                        log.info("z {}", target);
                        log.info("ok {} {}", SecretMask.logUrl(m.getUrl()), m.getId());
                        log.info("ok {}", target.length() > 80 ? target.substring(0, 80) : target);
                        log.info("ok \\"{}\\" {}", "url", urlCount);
                    }
                }
                """;
        assertThat(offenders("X.java", src)).hasSize(4);
    }

    @Test
    @DisplayName("SecretMask.logUrl: kimlik bilgisi atılır, sorgu / parça kesilir, satır sonu boşluk, uzunluk tavanı; yol kalır")
    void logUrlRule() {
        assertThat(SecretMask.logUrl("https://user:p%40ss@shop.example.com:8443/api/v1?token=abc&x=1#frag"))
                .isEqualTo("https://shop.example.com:8443/api/v1?…");
        assertThat(SecretMask.logUrl("https://a@b@host.example/p")).isEqualTo("https://host.example/p");
        assertThat(SecretMask.logUrl("https://host.example/a/b")).isEqualTo("https://host.example/a/b");
        assertThat(SecretMask.logUrl("https://host.example?sig=1")).isEqualTo("https://host.example?…");
        assertThat(SecretMask.logUrl("host.example:443")).isEqualTo("host.example:443");
        assertThat(SecretMask.logUrl("https://h.example/p\nINJECTED")).isEqualTo("https://h.example/p INJECTED");
        assertThat(SecretMask.logUrl(null)).isNull();
        // Kullanıcı bilgisi yolun içindeki '@' değil: yol korunur
        assertThat(SecretMask.logUrl("https://h.example/u/@me")).isEqualTo("https://h.example/u/@me");
        String longUrl = "https://h.example/" + "a".repeat(1000);
        assertThat(SecretMask.logUrl(longUrl)).hasSize(SecretMask.LOG_URL_MAX + 1).endsWith("…");
        assertThat(SecretMask.logUrls(List.of("https://u:p@h/x?y=1"))).containsExactly("https://h/x?…");
    }
}
