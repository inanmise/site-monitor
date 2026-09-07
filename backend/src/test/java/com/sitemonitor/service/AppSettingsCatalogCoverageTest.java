package com.sitemonitor.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * KAPI: {@code appSettings.getX("site.monitor…")} ile okunan HER anahtar
 * {@link AppSettingsCatalog}'ta olmalı — ya da burada gerekçesiyle muaf tutulmalı.
 *
 * <p><b>Neden gerekli.</b> {@code AppSettingsService} katalogda olmayan bir anahtarı
 * {@code IllegalArgumentException("Bilinmeyen ayar: …")} ile REDDEDER ve Genel Ayarlar ekranı
 * listesini yalnız katalogdan kurar. Yani katalog dışı bir anahtar kodda "canlı okunuyor" gibi
 * görünür ama gerçekte yöneticinin ona ERİŞİMİ YOKTUR: değiştirmenin tek yolu
 * {@code application.properties}'i düzenleyip yeniden başlatmaktır.
 *
 * <p>Bu sessizce büyümüştü: denetimde 12 anahtar bu durumda bulundu. En görünür örneği
 * Sıklık grubuydu — {@code MonitoringController} on türün varsayılan aralığını okuyor ama
 * katalogda sekizi vardı, DNS ve Sentetik satırları ekranda HİÇ çıkmıyordu. Hiçbir test
 * bakmadığı için kusur ancak elle fark edilebilirdi.
 *
 * <p>Kardeş kapı: {@code frontend/src/test/settings-labels-sync.test.jsx} — o da bu kataloğu
 * kaynak alıp her ayarın iki dilde etiketi olmasını zorunlu kılıyor. İkisi birlikte zinciri
 * kapatıyor: anahtar katalogda olacak VE etiketi bulunacak.
 */
class AppSettingsCatalogCoverageTest {

    /**
     * BİLEREK katalog dışı tutulan anahtarlar. Buraya ekleme yapmak "bu ayar yönetici
     * arayüzünden değiştirilemez" sözü vermektir — gerekçesini yaz, yoksa sıradaki denetim
     * bunu yeniden kusur olarak bulur.
     */
    private static final Map<String, String> INTENTIONALLY_UNMANAGED = Map.of(
            "site.monitor.audit.archive-dir",
            "Dosya sistemi YOLU. Yönetici arayüzünden değiştirilebilir olması, denetim "
            + "arşivinin pod üzerinde herhangi bir dizine yazılmasına izin verirdi; "
            + "ortam değişkeni / properties ile yönetilir."
    );

    /** {@code appSettings.getBoolean("site.monitor.x", …)} — dize LİTERALİ olan çağrılar. */
    private static final Pattern READ = Pattern.compile(
            "appSettings\\.get\\w+\\(\\s*\"(site\\.monitor\\.[^\"]+)\"");

    private static final Path MAIN = Path.of("src/main/java/com/sitemonitor");

    @Test
    @DisplayName("SOZLESME: kodda okunan her ayar anahtari katalogda (ya da gerekcesiyle muaf)")
    void everySettingReadInCode_isInCatalogOrExempt() throws IOException {
        Set<String> catalog = AppSettingsCatalog.ALL.stream()
                .map(AppSettingsCatalog.Setting::key)
                .collect(java.util.stream.Collectors.toSet());

        Map<String, String> orphans = new TreeMap<>();   // anahtar → onu okuyan dosya
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String src = Files.readString(f, StandardCharsets.UTF_8);
                Matcher m = READ.matcher(src);
                while (m.find()) {
                    String key = m.group(1);
                    // Önek + son ek ile kurulan anahtarlar (userpush şablonları) bu desende
                    // nokta ile biter; katalogda tam adlarıyla kayıtlılar.
                    if (key.endsWith(".")) continue;
                    if (catalog.contains(key) || INTENTIONALLY_UNMANAGED.containsKey(key)) continue;
                    orphans.put(key, f.getFileName().toString());
                }
            }
        }

        assertThat(orphans)
                .as("katalogda OLMAYAN ayar anahtarları — yönetici bunları değiştiremez, "
                    + "kod ise 'canlı okunuyor' gibi görünür")
                .isEmpty();
    }

    @Test
    @DisplayName("muafiyet listesi BAYATLAMAZ: her girdi hala kodda okunuyor olmali")
    void exemptionList_hasNoStaleEntries() throws IOException {
        StringBuilder all = new StringBuilder();
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                all.append(Files.readString(f, StandardCharsets.UTF_8));
            }
        }
        String src = all.toString();

        List<String> stale = INTENTIONALLY_UNMANAGED.keySet().stream()
                .filter(k -> !src.contains(k))
                .toList();

        assertThat(stale)
                .as("artık kodda okunmayan muafiyetler — listeden çıkarılmalı, "
                    + "yoksa liste gerçekte neyi koruduğunu anlatmaz")
                .isEmpty();
    }

    @Test
    @DisplayName("muaf tutulan her anahtarin GEREKCESI yazili")
    void everyExemptionHasReason() {
        assertThat(INTENTIONALLY_UNMANAGED.values())
                .allSatisfy(reason -> assertThat(reason).isNotBlank().hasSizeGreaterThan(30));
    }

    @Test
    @DisplayName("tarayici gercekten calisiyor (bos sonuc = desen ya da yol bayatlamis)")
    void scannerSelfCheck() throws IOException {
        int found = 0;
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                Matcher m = READ.matcher(Files.readString(f, StandardCharsets.UTF_8));
                while (m.find()) found++;
            }
        }
        // Kendi kendini denetleyen kapı: desen bozulursa test sessizce "hiç yetim yok" derdi.
        assertThat(found).as("appSettings okuma cagrisi sayisi").isGreaterThan(100);
    }
}
