package com.sitemonitor.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Çözüm e-postası ↔ snapshot whitelist SÖZLEŞMESİ.
 *
 * <p><b>Neden var.</b> Çözüm maili alarm-anı bağlamını olaydan geri okuyor
 * ({@code deserializeContext(event.getContextJson())}) ve o JSON
 * {@code EscalationService.snapshotContext} whitelist'inden üretiliyor. Şablon whitelist'te
 * OLMAYAN bir anahtar okursa satır sessizce ölü koda dönüşüyor: derleme yeşil, testler yeşil,
 * üretici taraf değeri yazıyor, ama nöbetçi çözüm mailinde o bilgiyi hiçbir zaman görmüyor.
 * 2026-09-23 denetiminde {@code duration_ms} ve {@code failed_checks} tam olarak böyle
 * kaybolmuştu — "Süre (alarm anı)" ve "Düşen Doğrulamalar" satırları hiç çizilmiyordu.
 *
 * <p><b>Ne yapar.</b> {@code EmailTemplateBuilder}'ın ÇÖZÜM kurucularını (buildResolvedHtml /
 * buildResolvedText) kaynaktan okuyup {@code strCtx(ctx, "…")} anahtarlarını çıkarır ve her
 * birinin {@link EscalationService#RESOLVED_CONTEXT_KEYS} içinde ya da gerekçeli
 * {@link #RESOLUTION_TIME_KEYS} muafiyetinde olmasını şart koşar.
 */
class ResolvedMailContextKeysTest {

    /**
     * Snapshot'tan GELMEYEN, çözüm ANINDA hesaplanıp ctx'e konan anahtarlar.
     *
     * <p>Bunlar alarm-anı fotoğrafının parçası değil; {@code sendResolutionNotification} çözüm
     * anındaki taze veriden dolduruyor. Whitelist'e eklenmeleri YANLIŞ olurdu (alarm anındaki
     * değerleri yok, snapshot'ı şişirirlerdi).
     */
    private static final Map<String, String> RESOLUTION_TIME_KEYS = Map.ofEntries(
            Map.entry("resolved_checked_at",      "çözüm anındaki kontrol zamanı — taze veri, alarm anı değil"),
            Map.entry("resolved_page_status",     "çözüm anındaki sayfa durumu — taze veri"),
            Map.entry("resolved_total_resources", "çözüm anındaki kaynak sayısı — taze veri"),
            Map.entry("not_after",                "AlertEvent.not_after kolonundan gelir, contextJson'dan değil"),
            Map.entry("issuer",                   "çözüm anındaki sertifika sahibi — yenilenmiş olabilir"),
            Map.entry("issuer_cn",                "çözüm anındaki sertifika sahibi CN"),
            // Aşağıdaki altısı EscalationService.reconstructDomainContext'ten gelir: DOMAINMON_*
            // çözüm/resend maili bağlamı EN GÜNCEL domain_checks satırından kurulur, contextJson'dan
            // değil. Whitelist'e eklenmeleri yanlış olurdu — alarm anındaki değerleri yok.
            Map.entry("days",                     "reconstructDomainContext — DomainCheck.daysRemaining"),
            Map.entry("days_remaining",           "reconstructDomainContext — DomainCheck.daysRemaining"),
            Map.entry("expiry_date",              "reconstructDomainContext — DomainCheck.expiryDate"),
            Map.entry("registrar",                "reconstructDomainContext — DomainCheck.registrar"),
            Map.entry("source",                   "reconstructDomainContext — DomainCheck.source"),
            Map.entry("status_codes",             "reconstructDomainContext — DomainCheck.statusCodes (EPP)"),
            Map.entry("nameservers",              "reconstructDomainContext — DomainCheck.nameservers"));

    /** {@code strCtx(ctx, "anahtar")} — şablonun bağlam okuma yüzeyi. */
    private static final Pattern CTX_READ = Pattern.compile("strCtx\\(\\s*ctx\\s*,\\s*\"([a-z0-9_]+)\"");

    /** Çözüm kurucularının kaynak aralığı: ilk buildResolvedHtml → resolvedHeadline yardımcısı. */
    private static final String REGION_START = "public String buildResolvedHtml(";
    private static final String REGION_END   = "private static String resolvedHeadline(";

    private static Path templateSource() {
        Path p = Path.of("src/main/java/com/sitemonitor/service/EmailTemplateBuilder.java");
        if (Files.exists(p)) return p;
        return Path.of("backend").resolve(p);   // depo kökünden koşulduğunda
    }

    private static String resolvedBuildersRegion() {
        try {
            String src = Files.readString(templateSource(), StandardCharsets.UTF_8);
            int a = src.indexOf(REGION_START);
            int b = src.indexOf(REGION_END, a);
            assertThat(a).as("buildResolvedHtml bulunamadı — metot adı mı değişti?").isGreaterThan(-1);
            assertThat(b).as("resolvedHeadline bulunamadı — bölge sonu kaydı mı?").isGreaterThan(a);
            return src.substring(a, b);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Test
    @DisplayName("Tarama vakum DEĞİL — çözüm kurucuları bulunuyor ve bağlam okumaları çıkıyor")
    void scanIsNotVacuous() {
        String region = resolvedBuildersRegion();
        assertThat(region).hasSizeGreaterThan(2000);
        assertThat(CTX_READ.matcher(region).results().count())
                .as("çözüm kurucularında hiç strCtx okuması yok — regex mi eskidi?")
                .isGreaterThan(5);
    }

    @Test
    @DisplayName("Çözüm mailinin okuduğu HER bağlam anahtarı snapshot whitelist'inde (ya da gerekçeli muafiyette)")
    void everyKeyReadByTheResolutionMailIsSnapshotted() {
        Set<String> whitelist = new LinkedHashSet<>(EscalationService.RESOLVED_CONTEXT_KEYS);
        List<String> orphans = new ArrayList<>();

        Matcher m = CTX_READ.matcher(resolvedBuildersRegion());
        while (m.find()) {
            String key = m.group(1);
            if (whitelist.contains(key) || RESOLUTION_TIME_KEYS.containsKey(key)) continue;
            if (!orphans.contains(key)) orphans.add(key);
        }

        assertThat(orphans)
                .as("Çözüm maili bu anahtarları okuyor ama snapshotContext onları KAYDETMİYOR → "
                  + "satır sessizce çizilmiyor. Ya EscalationService.RESOLVED_CONTEXT_KEYS'e ekleyin, "
                  + "ya da çözüm anında hesaplanıyorsa RESOLUTION_TIME_KEYS'e GEREKÇESİYLE koyun.")
                .isEmpty();
    }

    @Test
    @DisplayName("Muafiyet listesi ÖLÜ kayıt taşımaz — cırcır yalnız küçülsün")
    void exemptionsAreAllAlive() {
        String region = resolvedBuildersRegion();
        List<String> dead = new ArrayList<>();
        RESOLUTION_TIME_KEYS.forEach((k, why) -> {
            if (!region.contains("\"" + k + "\"")) dead.add(k);
        });
        assertThat(dead)
                .as("Bu anahtarları çözüm maili artık okumuyor — muafiyet DÜŞMELİ")
                .isEmpty();
    }

    @Test
    @DisplayName("Regresyon: duration_ms ve failed_checks whitelist'te — sentetik çözüm bloğu ölü koda dönmesin")
    void scriptedMeasureKeysAreSnapshotted() {
        assertThat(EscalationService.RESOLVED_CONTEXT_KEYS)
                .as("SCRIPTED_SLOW çözüm mailindeki 'Süre (alarm anı)' satırı bu anahtara bağlı")
                .contains("duration_ms")
                .as("SCRIPTED_FAIL çözüm mailindeki 'Düşen Doğrulamalar' satırı bu anahtara bağlı")
                .contains("failed_checks");
    }
}
