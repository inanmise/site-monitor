package com.sitemonitor.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PIN-FARKINDALI TLS BAĞLAMI KURAN HER İSTEMCİ, PİNİ KENDİSİ TETİKLEYEBİLMELİ.
 *
 * <p>{@code TrustEvaluator.pinAwareOutboundSslContext} yalnızca ZATEN pinlenmiş bir CA'yı kabul
 * eder; ilk karşılaşmada pini KENDİSİ oluşturmaz. Oluşturan {@code CaAutoPinService.pinFromServer}
 * çağrısıdır ve o çağrı istemcinin gönderim yolunda olmak zorundadır.
 *
 * <p>Bu ayrım üretimde şöyle ısırdı: {@code RdapDomainExpiryService} bağlamı kuruyor ama tetikleyici
 * taşımıyordu. Kodun kendi yorumu bunu "RdapDomainClient ile aynı hedef hostlar, pinler oradan
 * gelir" diye gerekçelendiriyordu — varsayım yanlıştı. Bootstrap {@code data.iana.org}'a gider,
 * alan sorgusu ise TLD'nin YETKİLİ RDAP sunucusuna. Sonuç, TEST pod'unun günlüğünde yan yana
 * duruyordu: kardeş istemci 1200 TLD yüklerken bu servis her alan için
 * {@code PKIX path building failed} alıyor ve alan adı süre-bitişi kontrolü hiç çalışmıyordu —
 * üstelik hata DEBUG'a yazıldığı için üretimde (INFO) tamamen görünmezdi.
 *
 * <p>Örnek düzeltmek yetmez: sekiz dosya bu bağlamı kuruyor. Kapı KAYNAĞI tarar ve muafiyetleri
 * GEREKÇESİYLE listeler; yeni bir giden istemci tetikleyicisiz eklendiği gün kırılır.
 */
class OutboundAutoPinTriggerTest {

    private static final Path SERVICES =
            Path.of("src/main/java/com/sitemonitor/service");

    /**
     * Tetikleyici TAŞIMAMASI beklenen dosyalar — her biri gerekçeli.
     *
     * <p>Auto-pin (TOFU) kapsamı bilinçli olarak DARDIR: sertifika/HTTP kontrolleri ve RDAP. Bir
     * hedefin CA'sını ilk görüşte güvenilir saymak bir güven-ilk-kullanım ödünüdür; bildirim
     * kanallarına genişletilmesi ayrı bir karar konusudur ve bugüne kadar verilmemiştir.
     */
    private static final Set<String> EXEMPT = Set.of(
            // Sağlayıcının kendisi — bağlamı ÜRETİR, istemci değildir.
            "TrustEvaluator.java",
            // Bildirim kanalları: auto-pin kapsamı dışında (bkz. yukarıdaki gerekçe). Güven hatası
            // burada sessiz DEĞİL — teslimat FAILED olarak kaydedilir ve operatöre görünür.
            "UserPushService.java",
            "WebhookService.java",
            // Pin'i üreten servis; kendi içinde pinFromServer'ı tanımlar.
            "CaAutoPinService.java");

    @Test
    @DisplayName("pin-farkındalı bağlam kuran her istemcide pin tetikleyicisi var")
    void everyPinAwareClientCanTriggerItsOwnPin() throws IOException {
        List<String> offenders = new ArrayList<>();
        List<String> scanned = new ArrayList<>();

        try (Stream<Path> files = Files.list(SERVICES)) {
            for (Path p : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                String src = Files.readString(p, StandardCharsets.UTF_8);
                if (!src.contains("pinAwareOutboundSslContext")) continue;
                String name = p.getFileName().toString();
                scanned.add(name);
                if (EXEMPT.contains(name)) continue;
                if (!src.contains("pinFromServer(")) {
                    offenders.add(name);
                }
            }
        }

        // Vakum koruması: regex/yol kayarsa liste boşalır ve iddia SESSİZCE geçerdi.
        assertThat(scanned)
                .as("kaynak taraması boş döndü — kapı gerçekte hiçbir şey ölçmüyor olurdu")
                .hasSizeGreaterThan(4);

        assertThat(offenders)
                .as("pin-farkındalı bağlam kurup tetikleyici taşımayan istemci: ilk karşılaşmada "
                        + "PKIX ile düşer ve bir daha kendini toparlayamaz")
                .isEmpty();
    }

    @Test
    @DisplayName("muafiyet listesi ÖLÜ girdi taşımaz (dosya adı değişince fark edilsin)")
    void exemptionListHasNoStaleEntries() throws IOException {
        List<String> stale = new ArrayList<>();
        for (String name : EXEMPT) {
            Path p = SERVICES.resolve(name);
            if (!Files.isRegularFile(p)) { stale.add(name + " (dosya yok)"); continue; }
            if (!Files.readString(p, StandardCharsets.UTF_8).contains("pinAwareOutboundSslContext")) {
                stale.add(name + " (artık pin-farkındalı bağlam kurmuyor)");
            }
        }
        assertThat(stale)
                .as("muafiyet listesi kaynakla birlikte güncellenmeli, yoksa gerçek boşluğu gizler")
                .isEmpty();
    }
}
