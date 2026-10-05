package com.sitemonitor.service.http.diagnose;

import com.sitemonitor.service.SafeRedirect;
import com.sitemonitor.service.http.HttpRequestOptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Ham ölçümün sayfa çekirdeği PROFİLİ (2026-10-05, Sayfa Bütünlüğü / Sayfa Hızı tanılaması): HTTP ve keyword kurucuları
 * profilsiz kalır (çıktı DEĞİŞMEZ — yönlendirme sınırı {@link SafeRedirect#MAX_HOPS}); profil yönlendirme sınırını taşır;
 * sıkıştırılmış önizleme yalnız gösterim için açılır (yarım akış → açılabilen kısım, bilinmeyen kodlama → metin değil).
 */
class RawHttpProbeProfileTest {

    private static byte[] gzip(String s) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(bos)) { gz.write(s.getBytes(StandardCharsets.UTF_8)); }
        return bos.toByteArray();
    }

    @Test
    @DisplayName("HTTP / keyword kurucuları profilsiz: yönlendirme sınırı SafeRedirect.MAX_HOPS; profil kendi sınırını taşır")
    void constructorsKeepHttpBehaviour() {
        RawHttpProbe.Spec http = new RawHttpProbe.Spec("monitor", "direct", "https://a.example.test/", "GET", null, 1000,
                true, true, HttpRequestOptions.NONE, null, 0, null, 0L);
        RawHttpProbe.Spec kw = new RawHttpProbe.Spec("monitor", "direct", "https://a.example.test/", "GET", null, 1000,
                false, true, HttpRequestOptions.NONE, null, 0, null, 0L, "UA", true);
        assertThat(http.profile()).isNull();
        assertThat(kw.profile()).isNull();
        assertThat(http.maxHops()).isEqualTo(SafeRedirect.MAX_HOPS);
        RawHttpProbe.Spec page = new RawHttpProbe.Spec("monitor", "direct", "https://a.example.test/", "GET", null, 1000,
                false, true, HttpRequestOptions.NONE, null, 0, null, 0L, "UA", false,
                new RawHttpProbe.Profile(Map.of("Accept-Encoding", "gzip, deflate"), 3, true));
        assertThat(page.maxHops()).isEqualTo(3);
    }

    @Test
    @DisplayName("gzip önizlemesi açılır; yarım akış açılabilen kısmı verir; identity olduğu gibi; br / bozuk → null")
    void inflatePreview() throws Exception {
        String html = "<html><body>" + "Merhaba dünya ".repeat(200) + "</body></html>";
        byte[] gz = gzip(html);
        assertThat(new String(RawHttpProbe.inflatePreview(gz, "gzip"), StandardCharsets.UTF_8)).isEqualTo(html);
        byte[] half = Arrays.copyOf(gz, gz.length / 2);
        byte[] partial = RawHttpProbe.inflatePreview(half, "gzip");
        assertThat(partial).isNotNull();
        assertThat(new String(partial, StandardCharsets.UTF_8)).startsWith("<html><body>Merhaba");
        byte[] plain = "düz".getBytes(StandardCharsets.UTF_8);
        assertThat(RawHttpProbe.inflatePreview(plain, "identity")).isSameAs(plain);
        assertThat(RawHttpProbe.inflatePreview(plain, "br")).isNull();
        assertThat(RawHttpProbe.inflatePreview(plain, "gzip")).isNull();
        // sıkıştırma bombası önizlemeyi şişiremez
        byte[] bomb = gzip("a".repeat(5 * RawHttpProbe.PREVIEW_BYTES));
        assertThat(RawHttpProbe.inflatePreview(bomb, "gzip")).hasSize(RawHttpProbe.PREVIEW_BYTES);
    }
}
