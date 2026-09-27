package com.sitemonitor.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * KAPI: java.net.http yanıt gövdesi yalnız SÜRE + TAVAN sınırlı yoldan okunur (prod kapısı 2026-09-25, N1).
 *
 * <p><b>Neden.</b> {@code HttpRequest.timeout} yalnız başlıklara kadar işler; gövde zamanlayıcısız okunur ve
 * istemcinin soket okuma zaman aşımı yoktur. {@code BodyHandlers.discarding()}/{@code ofString()} gövde bitene
 * dek dönmez; çıplak {@code resp.body().readNBytes(…)} EOF gelene dek bloklar. Başlığı gönderip gövdeyi
 * bitirmeyen tek bir hedef (SSE, MJPEG kamera) HTTP/Keyword/Sayfa sweep'ini kalıcı donduruyor, test uçlarında
 * Tomcat havuzunu tüketiyordu. Kardeşi PortCheckerService.readStatusLine bu sınıfı zaten kapatmıştı — bu kapı
 * SINIFI kapatır. (Bu javadoc eskiden ChainValidationService'i de "kapatmış" sayıyordu; YANLIŞTI: OCSP/CRL
 * {@code HttpURLConnection} ile yalnız okuma-arası zaman aşımı + bayt tavanıyla okunuyordu — BO8, 2026-09-27.)
 *
 * <p><b>Kural.</b> Üretimde {@code discarding()}, {@code ofString()}, {@code ofByteArray()} gövde işleyicileri
 * yasak; {@code ofInputStream()} kullanan her dosya gövdeyi {@code HttpBodies} (withDeadline / drain /
 * readPreview / readCapped) üzerinden okur. {@code HttpURLConnection} gövdesi ({@code getInputStream} /
 * {@code getErrorStream}) çıplak okunmaz: {@code HttpBodies.deadline(conn, …)} toplam süre bekçisi +
 * {@code open()} / {@code body()} üzerinden okunur.
 */
class HttpBodyDeadlineGateTest {

    /** {@code HttpURLConnection x} / {@code HttpsURLConnection x} bildirimleri (desen eşleşmesi dâhil). */
    private static final Pattern URLCONN_VAR =
            Pattern.compile("Https?URLConnection[ \t]+([A-Za-z_][A-Za-z0-9_]*)[ \t]*[=;,)]");

    @Test
    @DisplayName("KAPI (BO8): HttpURLConnection gövdesi çıplak getInputStream/getErrorStream ile okunmaz — toplam süre bekçisi şart")
    void httpUrlConnectionBodiesAreTimeBounded() throws IOException {
        Path root = mainRoot();
        List<String> offenders = new ArrayList<>();
        int guarded = 0;
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path f : walk.filter(x -> x.toString().endsWith(".java")).toList()) {
                String rel = root.relativize(f).toString().replace(File.separatorChar, '/');
                if (rel.endsWith("util/HttpBodies.java")) continue;
                List<String> lines = codeLines(f);
                java.util.Set<String> vars = new java.util.HashSet<>();
                for (String l : lines) {
                    var m = URLCONN_VAR.matcher(l);
                    while (m.find()) vars.add(m.group(1));
                    if (l.contains("HttpBodies.deadline(")) guarded++;
                }
                for (String v : vars) {
                    Pattern read = Pattern.compile("(?<![A-Za-z0-9_.])" + Pattern.quote(v) + "[.]get(Input|Error)Stream[(]");
                    for (String l : lines) {
                        if (read.matcher(l).find()) offenders.add(rel + ": " + l.trim());
                    }
                }
            }
        }
        assertThat(guarded).as("tarama vakum — HttpBodies.deadline kullanıcısı bulunamadı (OCSP + CRL)").isGreaterThanOrEqualTo(2);
        assertThat(offenders)
                .as("HttpURLConnection gövdesi süre bekçisiz okunuyor: setReadTimeout yalnız okumalar ARASINI sınırlar, "
                  + "bayt bayt damlatan uç iş parçacığını süresiz tutar. HttpBodies.deadline(conn, ms, ad).open() kullanın.")
                .isEmpty();
    }

    private static final Pattern UNBOUNDED_HANDLER =
            Pattern.compile("BodyHandlers[.](discarding|ofString|ofByteArray|ofLines|ofFile)[(]");
    private static final Pattern STREAM_HANDLER = Pattern.compile("BodyHandlers[.]ofInputStream[(]");
    private static final Pattern COMMENT_LINE = Pattern.compile("^[ \t]*([*]|//|/[*])");

    private static Path mainRoot() {
        Path p = Path.of("src/main/java");
        return Files.isDirectory(p) ? p : Path.of("backend/src/main/java");
    }

    private static List<String> codeLines(Path f) throws IOException {
        List<String> out = new ArrayList<>();
        for (String l : Files.readAllLines(f, StandardCharsets.UTF_8)) {
            if (!COMMENT_LINE.matcher(l).find()) out.add(l);
        }
        return out;
    }

    @Test
    @DisplayName("KAPI: süresiz gövde işleyicisi yok; ofInputStream kullanan her dosya HttpBodies üzerinden okur")
    void responseBodiesAreTimeBounded() throws IOException {
        Path root = mainRoot();
        List<String> offenders = new ArrayList<>();
        int streamUsers = 0;
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path f : walk.filter(x -> x.toString().endsWith(".java")).toList()) {
                String rel = root.relativize(f).toString().replace(File.separatorChar, '/');
                if (rel.endsWith("util/HttpBodies.java")) continue;
                List<String> lines = codeLines(f);
                String code = String.join("\n", lines);
                for (String l : lines) {
                    if (UNBOUNDED_HANDLER.matcher(l).find()) offenders.add(rel + ": " + l.trim());
                }
                if (STREAM_HANDLER.matcher(code).find()) {
                    streamUsers++;
                    if (!code.contains("HttpBodies.")) offenders.add(rel + ": ofInputStream gövdesi HttpBodies'ten geçmiyor");
                }
            }
        }
        assertThat(streamUsers).as("tarama vakum — hiç ofInputStream kullanıcısı bulunamadı").isGreaterThan(5);
        assertThat(offenders)
                .as("Gövde süresiz okunuyor: java.net.http zaman aşımı yalnız başlıklara kadar işler. "
                  + "BodyHandlers.ofInputStream() + HttpBodies.withDeadline/drain/readPreview/readCapped kullanın.")
                .isEmpty();
    }
}
