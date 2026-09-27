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
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * KAPI: alıcı e-posta adresleri uygulama günlüğüne DÜZ yazılmaz (prod kapısı 2026-09-25, P4-1).
 *
 * <p><b>Geçmişi.</b> 2026-09-09'da O20 "alıcı adresleri artık loglanmıyor" diye kapatıldı; düzeltme yalnız
 * {@code EscalationService}'teki özet satırını sayıya çevirdi. Oysa her mail {@code EmailNotificationService}
 * gönderim hunisinden geçiyor ve oradaki {@code ✓ E-posta gönderildi: TO={}} satırı adresleri INFO'da basmaya
 * devam ediyordu (prod DEBUG ile koşuyor, 30 günlük dosya + {@code kubectl logs}). Yedi kardeş serviste de
 * aynı desen vardı. Örnek kapanmış, SINIF açık kalmıştı — bu kapı sınıfı kapatır.
 *
 * <p><b>Kural.</b> Biçim dizesinde {@code TO={}} / {@code cc={}} / {@code alıcı={}} geçen her INFO/WARN/ERROR/DEBUG
 * günlük ifadesi argümanını {@code SecretMask.maskEmails}/{@code maskEmail}'den geçirir. TRACE bilinçli
 * dışarıda: {@code com.sitemonitor.mail} TRACE'i yalnız SMTP teşhisi için elle açılır.
 *
 * <p>DESEN NOTU: regex kaçışsız karakter sınıflarıyla yazıldı ({@code [.]}, {@code [(]}) — kardeş kapılarla aynı.
 */
class LogRecipientMaskGateTest {

    /** Günlük çağrısının başlangıcı (TRACE hariç). */
    private static final Pattern LOG_CALL = Pattern.compile("[A-Za-z_]*(log|LOG)[.](info|warn|error|debug)[(]");

    /** Biçim dizesindeki alıcı yer tutucusu: TO={} / cc=[{}] / alıcı={} … */
    private static final Pattern RECIPIENT_TOKEN = Pattern.compile(
            "(?<![A-Za-z_])(TO|To|to|CC|Cc|cc|alıcı|alici|recipients?)=[\\[]?[{][}]");

    private static final Pattern COMMENT_LINE = Pattern.compile("^[ \t]*([*]|//|/[*])");

    /** Gerekçeli muafiyet: "dosya|ifadede geçen parça" → NEDEN adres değil. Cırcır yalnız küçülür. */
    private static final Map<String, String> EXEMPT = Map.of(
            "com/sitemonitor/controller/AdminController.java|from={} to={}",
            "to = hedef TAKIM kimliği (envanter takım taşıma), adres değil",
            "com/sitemonitor/service/CertificateCheckerService.java|from={} to={}",
            "to = yeniden deneme AŞAMASI, adres değil",
            "com/sitemonitor/service/EscalationService.java|alıcı={} | webhooks={}",
            "alıcı = SAYI (allEmails.size()) — O20 düzeltmesinin kendisi");

    private static Path mainRoot() {
        Path p = Path.of("src/main/java");
        return Files.isDirectory(p) ? p : Path.of("backend/src/main/java");
    }

    /** Dosyadaki günlük ifadeleri (çok satırlı olanlar birleştirilir). */
    private static List<String> logStatements(Path f) throws IOException {
        List<String> out = new ArrayList<>();
        List<String> lines = Files.readAllLines(f, StandardCharsets.UTF_8);
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (COMMENT_LINE.matcher(line).find() || !LOG_CALL.matcher(line).find()) continue;
            StringBuilder stmt = new StringBuilder(line.trim());
            int j = i;
            while (!lines.get(j).trim().endsWith(");") && j + 1 < lines.size() && j - i < 8) {
                j++;
                stmt.append(' ').append(lines.get(j).trim());
            }
            out.add((i + 1) + ": " + stmt);
        }
        return out;
    }

    private static boolean exempt(String rel, String stmt) {
        for (String k : EXEMPT.keySet()) {
            int bar = k.indexOf('|');
            if (k.substring(0, bar).equals(rel) && stmt.contains(k.substring(bar + 1))) return true;
        }
        return false;
    }

    @Test
    @DisplayName("KAPI: TO={}/cc={}/alıcı={} basan günlük ifadesi adresi SecretMask'ten geçirir")
    void recipientLogsAreMasked() throws IOException {
        Path root = mainRoot();
        List<String> offenders = new ArrayList<>();
        int scanned = 0;
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path f : walk.filter(x -> x.toString().endsWith(".java")).toList()) {
                String rel = root.relativize(f).toString().replace(File.separatorChar, '/');
                for (String stmt : logStatements(f)) {
                    if (!RECIPIENT_TOKEN.matcher(stmt).find()) continue;
                    scanned++;
                    if (stmt.contains("maskEmail") || exempt(rel, stmt)) continue;
                    offenders.add(rel + ":" + stmt);
                }
            }
        }
        assertThat(scanned).as("hiç alıcı günlüğü bulunamadı — kapı vakum (desen bozulmuş olabilir)").isGreaterThan(20);
        assertThat(offenders)
                .as("Alıcı adresi günlüğe DÜZ yazılıyor (INFO 30 gün saklanıyor, kubectl logs ile okunuyor). "
                  + "SecretMask.maskEmails(...) kullanın; adres zaten notification_log'da tam duruyor.")
                .isEmpty();
    }

    @Test
    @DisplayName("Muafiyetler ÖLÜ kayıt taşımaz")
    void exemptionsAreAlive() throws IOException {
        Path root = mainRoot();
        List<String> dead = new ArrayList<>();
        for (String k : EXEMPT.keySet()) {
            int bar = k.indexOf('|');
            Path f = root.resolve(k.substring(0, bar));
            boolean alive = Files.exists(f) && logStatements(f).stream()
                    .anyMatch(s -> s.contains(k.substring(bar + 1)) && RECIPIENT_TOKEN.matcher(s).find());
            if (!alive) dead.add(k);
        }
        assertThat(dead).as("Bu muafiyetlerin ifadesi artık yok — muafiyet DÜŞMELİ").isEmpty();
    }
}
