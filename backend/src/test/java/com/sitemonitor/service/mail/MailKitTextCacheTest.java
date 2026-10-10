package com.sitemonitor.service.mail;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Düz metin önbelleği (2026-10-10): aynı HTML iki kez üretilince kayıt SON üretilen dizeye bağlanmalı. WeakHashMap eşit
 * anahtarda eski anahtar nesnesini tuttuğu için kayıt erişilemeyen ilk dizeye bağlı kalıyor, çöp toplayıcı onu silince
 * metin {@code htmlToText} yedeğine düşüyordu (WeeklyAvailabilityEmailTest.plainTextParity aralıklı kırmızısı). GC'ye
 * dayanmadan sınanır: haritadaki anahtar nesnesinin kimliğine bakılır.
 */
class MailKitTextCacheTest {

    @SuppressWarnings("unchecked")
    private static Map<String, String> cache() throws Exception {
        Field f = MailKit.class.getDeclaredField("TEXT_BY_HTML");
        f.setAccessible(true);
        return (Map<String, String>) f.get(null);
    }

    @Test
    @DisplayName("eşit HTML ikinci kez kaydedilince anahtar YENİ dize nesnesidir ve metin günceldir")
    void reRememberedHtmlKeysOnLatestInstance() throws Exception {
        String content = "<html><body>önbellek-sınaması-" + System.nanoTime() + "</body></html>";
        String first = new String(content);
        String second = new String(content);
        assertThat(second).isEqualTo(first).isNotSameAs(first);

        MailKit.rememberText(first, "ilk");
        MailKit.rememberText(second, "ikinci");

        Map<String, String> map = cache();
        String key = null;
        synchronized (map) {
            for (String k : map.keySet()) if (content.equals(k)) key = k;
        }
        assertThat(key).as("anahtar son kaydedilen dize nesnesi").isSameAs(second);
        assertThat(MailKit.plainTextFor(second)).isEqualTo("ikinci");
    }
}
