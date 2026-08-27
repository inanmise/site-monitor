package com.sitemonitor.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Parola politikasının alt sınırı İKİ yerde yazılı ve ikisi AYNI olmalı.
 *
 * <p>{@code application.properties} çalışan uygulamanın değeridir; {@code UserService}'teki
 * {@code @Value} yedeği ise özellik dosyası hiç yüklenmediğinde (birim testi, gömülü bağlam)
 * devreye girer. Ayrışırlarsa aynı kural iki ortamda iki farklı şey söyler ve fark yalnız
 * "neden burada 6, orada 12 istiyor" diye sorulduğunda ortaya çıkar — sessiz bir tutarsızlık.
 *
 * <p><b>Neden bir de DEĞERİ pinliyoruz:</b> alt sınır 2026-08-27'de kullanıcı isteğiyle 12'den
 * 6'ya indirildi. Bu bilinçli bir güvenlik gevşetmesiydi; kazara geri gelmesi ya da kazara
 * daha da düşmesi fark edilmeden olmamalı. Testin kırılması "yanlış yaptın" demez, "bu değeri
 * gerçekten değiştirmek istediğine emin misin" der.
 */
class PasswordPolicyDefaultTest {

    private static final int EXPECTED_MIN = 6;

    private static final Path PROPS = Path.of("src/main/resources/application.properties");
    private static final Path SERVICE = Path.of("src/main/java/com/sitemonitor/service/UserService.java");

    private static int matchInt(Path file, String regex) throws Exception {
        Matcher m = Pattern.compile(regex).matcher(Files.readString(file));
        assertThat(m.find()).as("desen bulunamadı: %s (%s)", regex, file).isTrue();
        return Integer.parseInt(m.group(1));
    }

    @Test
    @DisplayName("Alt sınır varsayılanı 6 (bilinçli karar — kazara değişmesin)")
    void minLengthDefaultIsSix() throws Exception {
        assertThat(matchInt(PROPS, "password[.]min-length=[$][{]PASSWORD_MIN_LENGTH:([0-9]+)[}]"))
                .isEqualTo(EXPECTED_MIN);
    }

    /**
     * Üç ayarın ÜÇÜ de eşleşmeli. Yalnız alt sınıra bakmak yetmezdi: bu test yazılırken üst
     * sınır (properties 64 / kod 128) ve geçmiş sayısı (5 / 3) de ayrışmış durumdaydı ve
     * kimse fark etmemişti — tam da bu testin var olma sebebi.
     */
    @org.junit.jupiter.params.ParameterizedTest(name = "{0}")
    @org.junit.jupiter.params.provider.CsvSource({
            "min-length,     PASSWORD_MIN_LENGTH",
            "max-length,     PASSWORD_MAX_LENGTH",
            "history-count,  PASSWORD_HISTORY_COUNT",
    })
    @DisplayName("@Value yedeği properties ile AYNI — ayrışırsa kural ortama göre değişir")
    void valueFallbackMatchesProperties(String key, String env) throws Exception {
        int fromProps = matchInt(PROPS, "password[.]" + key + "=[$][{]" + env + ":([0-9]+)[}]");
        int fromCode = matchInt(SERVICE, "password[.]" + key + ":([0-9]+)[}]");

        assertThat(fromCode).as("%s: properties %d, kod %d", key, fromProps, fromCode)
                .isEqualTo(fromProps);
    }

    /**
     * Elle atanan parolanın alt sınırı, sistemin KENDİ ürettiği geçici parolanın uzunluğunu
     * AŞMAMALI. Aşarsa yönetici "parolayı sıfırla" ile kullanıcıya, o kullanıcının kendisinin
     * asla giremeyeceği kadar kısa bir parola göndermiş olur — tutarsızlık tam da bu yüzden
     * 12'lik alt sınırla 10 karakterlik üreteç arasında zaten vardı.
     */
    @Test
    @DisplayName("Alt sınır, üretilen geçici parolanın uzunluğunu aşmaz")
    void minLengthDoesNotExceedGeneratedPassword() {
        assertThat(com.sitemonitor.util.PasswordGenerator.generate().length())
                .as("üretilen geçici parola alt sınırın altında kalıyor")
                .isGreaterThanOrEqualTo(EXPECTED_MIN);
    }
}
