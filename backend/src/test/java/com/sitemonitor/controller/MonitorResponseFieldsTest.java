package com.sitemonitor.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * İZLEME YANITI ALAN KAPISI — kaydedilen bir alan GERİ DÖNMEZSE ekran onu hiç göstermez.
 *
 * <p><b>Gerçek vaka:</b> Bildirim Grubu alanı entity'ye, şemaya, forma ve kaydetme yoluna
 * eklendi; değer veritabanına DOĞRU yazılıyordu. Ama izleme listesi uçları yanıtı ELLE kuruyor
 * ({@code enrichDomain}, {@code enrichHttp}, …) ve alan o haritalara eklenmemişti. Sonuç:
 * kullanıcı grubu seçiyor, kaydediyor, formu yeniden açıyor ve "Takım varsayılanı" görüyor —
 * seçimi kaybolmuş sanıyor. Oysa veri yerindeydi, yalnız geri gönderilmiyordu.
 *
 * <p>Frontend tarafındaki {@code monitorFormWiring.test.js} bu halkayı GÖREMEZ: o, formun
 * {@code m.notification_group_id} okuduğunu doğrular, API'nin onu gönderdiğini değil. Zincirin
 * iki ucu iki ayrı kapı ister.
 *
 * <p>Kaynak taraması tercih edildi çünkü dokuz ucun hepsini MockMvc ile ayağa kaldırmak
 * ({@code @WebMvcTest} + onlarca bean) bu tek değişmezi doğrulamak için orantısız olurdu.
 */
class MonitorResponseFieldsTest {

    /** Yanıtı elle kuran dokuz izleme kurucusu. Yeni bir tür eklenirse buraya da eklenir. */
    private static final List<String> BUILDERS = List.of(
            "enrichDomain", "enrichHttp", "enrichKeyword", "enrichPing", "enrichPort",
            "enrichDns", "enrichPage", "enrichPageSpeed", "enrichScripted");

    /**
     * Yanıtta bulunması ZORUNLU alanlar.
     *
     * <p>{@code team_id} zaten vardı ve kontrol grubu görevi görüyor: tarama bozulursa (metot
     * bulunamaz, gövde yanlış kesilir) bu da düşer ve testin vakum olmadığı anlaşılır.
     */
    private static final List<String> REQUIRED_KEYS = List.of("team_id", "notification_group_id");

    private static String source() {
        Path p = Path.of("src", "main", "java", "com", "sitemonitor", "controller", "MonitoringController.java");
        try {
            return Files.readString(p, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Metot gövdesini kaba ama yeterli biçimde kes: imzadan bir sonraki metot imzasına kadar. */
    private static String bodyOf(String src, String method) {
        Matcher m = Pattern.compile("Map<String, Object> " + method + "\\s*\\(").matcher(src);
        if (!m.find()) return null;
        int start = m.end();
        Matcher next = Pattern.compile("\\n    (private|public|protected|static)[^\\n]*\\(").matcher(src);
        int end = src.length();
        if (next.find(start)) end = next.start();
        return src.substring(start, end);
    }

    @Test
    @DisplayName("Dokuz izleme kurucusunun HEPSİ zorunlu alanları yanıta koyar")
    void everyBuilderReturnsRequiredKeys() {
        String src = source();
        List<String> problems = new ArrayList<>();

        for (String builder : BUILDERS) {
            String body = bodyOf(src, builder);
            if (body == null) {
                problems.add(builder + " → metot bulunamadı (liste güncel mi?)");
                continue;
            }
            for (String key : REQUIRED_KEYS) {
                if (!body.contains("\"" + key + "\"")) {
                    problems.add(builder + " → " + key);
                }
            }
        }

        assertThat(problems)
                .as("Bu alanlar yanıtta DÖNMEZSE form onları hiç gösteremez: kullanıcı değeri "
                  + "kaydeder, formu açar ve kaybolmuş sanır (veri aslında yerindedir).")
                .isEmpty();
    }
}
