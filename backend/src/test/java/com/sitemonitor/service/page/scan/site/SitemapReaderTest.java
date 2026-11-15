package com.sitemonitor.service.page.scan.site;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.zip.GZIPOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/** Site haritası okuma kuralları (2026-11-15): doğrusal {@code <loc>} taraması, dizin, gzip tavanı, robots satırları. */
class SitemapReaderTest {

    static byte[] gzip(byte[] in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(bos)) { gz.write(in); }
        return bos.toByteArray();
    }

    @Test
    @DisplayName("robots.txt: Sitemap satırları sırayla, tekil, büyük/küçük harf ve yorum; göreli adres tabana göre; başka şema yok")
    void robotsSitemaps() {
        String robots = "User-agent: *\nDisallow: /x\nSitemap: https://a.example/sm.xml\nsitemap:/rel.xml # yorum\n"
                + "SITEMAP: https://a.example/sm.xml\nSitemap: ftp://a.example/no.xml\nSitemap:\n";
        assertThat(SitemapReader.robotsSitemaps(robots, "https://a.example/"))
                .containsExactly("https://a.example/sm.xml", "https://a.example/rel.xml");
        assertThat(SitemapReader.robotsSitemaps(null, "https://a.example/")).isEmpty();
        StringBuilder many = new StringBuilder();
        for (int i = 0; i < 50; i++) many.append("Sitemap: https://a.example/s").append(i).append(".xml\n");
        assertThat(SitemapReader.robotsSitemaps(many.toString(), "https://a.example/")).hasSize(SitemapReader.MAX_ROBOTS_SITEMAPS);
    }

    @Test
    @DisplayName("urlset: ad alanı öneki, CDATA, varlıklar; boş / çok uzun loc atlanır; dizin değil")
    void parseUrlset() {
        String xml = "<?xml version=\"1.0\"?><sm:urlset xmlns:sm=\"x\">"
                + "<sm:url><sm:loc> https://a.example/a?x=1&amp;y=2 </sm:loc></sm:url>"
                + "<url><LOC><![CDATA[https://a.example/b<c>]]></LOC></url>"
                + "<url><loc></loc></url>"
                + "<url><location>https://a.example/not-a-loc</location></url>"
                + "<url><loc>" + "x".repeat(SitemapReader.MAX_LOC_CHARS + 1) + "</loc></url>"
                + "<url><loc>https://a.example/&#252;r&#x00FC;n</loc></url>"
                + "</sm:urlset>";
        SitemapReader.Parsed p = SitemapReader.parse(xml);
        assertThat(p.index()).isFalse();
        assertThat(p.truncated()).isFalse();
        assertThat(p.locs()).containsExactly("https://a.example/a?x=1&y=2", "https://a.example/b<c>", "https://a.example/ürün");
    }

    @Test
    @DisplayName("sitemapindex dizin olarak tanınır; tavan aşılınca truncated")
    void parseIndexAndCap() {
        SitemapReader.Parsed idx = SitemapReader.parse("<sitemapindex><sitemap><loc>https://a.example/s1.xml</loc></sitemap></sitemapindex>");
        assertThat(idx.index()).isTrue();
        assertThat(idx.locs()).containsExactly("https://a.example/s1.xml");
        StringBuilder big = new StringBuilder("<urlset>");
        for (int i = 0; i < SitemapReader.MAX_LOCS_PER_FILE + 5; i++) big.append("<url><loc>https://a.example/p").append(i).append("</loc></url>");
        big.append("</urlset>");
        SitemapReader.Parsed p = SitemapReader.parse(big.toString());
        assertThat(p.locs()).hasSize(SitemapReader.MAX_LOCS_PER_FILE);
        assertThat(p.truncated()).isTrue();
    }

    @Test
    @DisplayName("bozuk / kapanmamış girdi: döngü sonlanır (doğrusal), istisna yok")
    void malformedInputTerminates() {
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            assertThat(SitemapReader.parse("<urlset><url><loc>https://a.example/x").locs()).isEmpty();
            assertThat(SitemapReader.parse("<loc><![CDATA[never closed").locs()).isEmpty();
            assertThat(SitemapReader.parse("<<<<<<<<" + "<".repeat(200_000)).locs()).isEmpty();
            assertThat(SitemapReader.parse("&".repeat(100_000)).locs()).isEmpty();
            String open = "<loc>" + "a<".repeat(100_000);
            SitemapReader.parse(open);
            assertThat(SitemapReader.parse("").locs()).isEmpty();
            assertThat(SitemapReader.parse(null).locs()).isEmpty();
        });
        assertThat(SitemapReader.unescape("a&unknown;b&#99999999;c&#xZZ;")).isEqualTo("a&unknown;b&#99999999;c&#xZZ;");
    }

    @Test
    @DisplayName("gzip açılır; açılınca tavanı aşan dosya reddedilir (sıkıştırma bombası yok)")
    void gzipDecodeAndCap() throws IOException {
        byte[] xml = "<urlset><url><loc>https://a.example/g</loc></url></urlset>".getBytes(StandardCharsets.UTF_8);
        assertThat(SitemapReader.parse(SitemapReader.decode(gzip(xml))).locs()).containsExactly("https://a.example/g");
        assertThat(SitemapReader.decode(xml)).contains("https://a.example/g");
        assertThat(SitemapReader.decode(new byte[0])).isEmpty();
        // 60 MB sıfır → birkaç yüz KB gzip; açılırken 50 MB'ta durur
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(bos)) {
            byte[] zeros = new byte[1 << 20];
            for (int i = 0; i < 60; i++) gz.write(zeros);
        }
        byte[] bomb = bos.toByteArray();
        assertThatThrownBy(() -> SitemapReader.decode(bomb)).isInstanceOf(IOException.class);
        assertThatThrownBy(() -> SitemapReader.decode(new byte[]{0x1f, (byte) 0x8b, 1, 2, 3})).isInstanceOf(IOException.class);
    }

    @Test
    @DisplayName("dizin mi: yalnız KÖK öğe — stil sayfası / ilk adres 'sitemapindex' içerse de urlset; önekli dizin tanınır")
    void indexIsRootElementOnly() {
        SitemapReader.Parsed urlset = SitemapReader.parse("<?xml version=\"1.0\"?><?xml-stylesheet href=\"/sitemapindex.xsl\"?>"
                + "<!-- sitemapindex --><urlset><url><loc>https://a.example/sitemapindex</loc></url></urlset>");
        assertThat(urlset.index()).isFalse();
        assertThat(urlset.locs()).containsExactly("https://a.example/sitemapindex");
        SitemapReader.Parsed idx = SitemapReader.parse("<?xml version=\"1.0\"?><!DOCTYPE x><!-- yorum -->"
                + "<sm:sitemapindex xmlns:sm=\"http://www.sitemaps.org/schemas/sitemap/0.9\"><sm:sitemap><sm:loc>https://a.example/s1.xml"
                + "</sm:loc></sm:sitemap></sm:sitemapindex>");
        assertThat(idx.index()).isTrue();
        assertThat(idx.locs()).containsExactly("https://a.example/s1.xml");
        assertThat(SitemapReader.isIndex("<!-- kapanmayan yorum")).isFalse();
        assertThat(SitemapReader.isIndex("")).isFalse();
    }

    @Test
    @DisplayName("uzantı öğeleri: image:loc sayfa adresi sayılmaz; yorumdaki loc okunmaz; bozuk uzantı sonraki kaydı yutmaz")
    void extensionLocsAndCommentsSkipped() {
        SitemapReader.Parsed p = SitemapReader.parse("<urlset xmlns:image=\"x\"><url><loc>https://a.example/p1</loc>"
                + "<image:image><image:loc>https://a.example/i.jpg</image:loc><image:title>t</image:title></image:image></url>"
                + "<!-- a > <loc>https://a.example/in-comment</loc> -->"
                + "<url><loc>https://a.example/p2</loc><video:video><video:content_loc>https://a.example/v.mp4</video:content_loc>"
                + "</video:video><xhtml:link rel=\"alternate\" href=\"https://a.example/en\"/></url>"
                + "<url><loc>https://a.example/p3</loc><mobile:mobile><mobile:loc>https://a.example/m</mobile:loc></url>"
                + "<url><loc>https://a.example/p4</loc></url></urlset>");
        assertThat(p.locs()).containsExactly("https://a.example/p1", "https://a.example/p2", "https://a.example/p3",
                "https://a.example/p4");
        SitemapReader.Parsed cdata = SitemapReader.parse("<urlset><![CDATA[<loc>https://a.example/x</loc>]]>"
                + "<url><loc><![CDATA[https://a.example/q?a=1&b=2]]></loc></url></urlset>");
        assertThat(cdata.locs()).containsExactly("https://a.example/q?a=1&b=2");
    }

    @Test
    @DisplayName("mutlak adres: göreli → tabana göre; başka şema / host yok → null")
    void absolute() {
        assertThat(SitemapReader.absolute("/a.xml", "https://a.example/")).isEqualTo("https://a.example/a.xml");
        assertThat(SitemapReader.absolute("https://b.example/x", "https://a.example/")).isEqualTo("https://b.example/x");
        assertThat(SitemapReader.absolute("javascript:alert(1)", "https://a.example/")).isNull();
        assertThat(SitemapReader.absolute("mailto:x@y", null)).isNull();
        assertThat(SitemapReader.absolute("http://[bad", null)).isNull();
        assertThat(SitemapReader.absolute(" ", null)).isNull();
    }
}
