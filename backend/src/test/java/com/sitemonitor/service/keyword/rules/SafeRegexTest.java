package com.sitemonitor.service.keyword.rules;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/**
 * RE2J düzenli ifade kuralları (kw sözleşmesi §6.2): kayıt doğrulaması, Türkçe katlama, karmaşıklık sınırı, sayım tavanı ve
 * patolojik desen + 512 K girdiyle süre sınırı.
 */
class SafeRegexTest {

    /** foldPattern doğruluk tablosu: girdi, beklenen. */
    static final String[][] FOLD_TABLE = {
            {"IŞIK", "iŞiK"},                     // yalnız I / İ / ı / i katlanır (K büyük kalır; CASE_INSENSITIVE eşler)
            {"İstanbul", "istanbul"},
            {"ılık", "ilik"},
            {"\\Iabc", "\\Iabc"},                 // kaçış olduğu gibi
            {"\\p{Lu}I", "\\p{Lu}i"},             // \p{…} olduğu gibi, sonrası katlanır
            {"\\QİI\\EI", "\\QİI\\Ei"},           // \Q…\E içi olduğu gibi
            {"[IJK]", "[iJK]"},                   // sınıf içi düz harf katlanır
            {"[I-K]", "[I-K]"},                   // aralık başı katlanmaz
            {"[A-I]", "[A-I]"},                   // aralık sonu katlanmaz
            {"[\\-I]", "[\\-i]"},                 // kaçışlı tire aralık değil
            {"(?i)Iİ", "(?i)ii"},                 // bayrak harfi katlanmaz, sonrası katlanır
            {"(?P<Isim>I)", "(?P<Isim>i)"},       // grup adı olduğu gibi
            {"[[:alpha:]]I", "[[:alpha:]]i"},     // POSIX sınıfı
            {"\\x{130}I", "\\x{130}i"},
            {"abc", "abc"},
            {"", ""},
    };

    @Test
    @DisplayName("foldPattern doğruluk tablosu (kaçışlar, \\p{…}, \\Q…\\E, aralık uçları, bayraklar, grup adı)")
    void foldPatternTable() {
        for (String[] row : FOLD_TABLE) {
            assertThat(SafeRegex.foldPattern(row[0])).as(row[0]).isEqualTo(row[1]);
        }
        assertThat(SafeRegex.foldPattern(null)).isNull();
    }

    @Test
    @DisplayName("Kayıt doğrulaması: boş, uzun, satır sonu, sözdizimi (geri başvuru / bakış), boş eşleşme → RegexError")
    void validation() {
        assertThat(SafeRegex.validate("", false)).isNotNull();
        assertThat(SafeRegex.validate("a".repeat(201), false)).isNotNull();
        assertThat(SafeRegex.validate("a\nb", false)).isNotNull();
        assertThat(SafeRegex.validate("(a)\\1", false)).isNotNull();
        assertThat(SafeRegex.validate("a(?=b)", false)).isNotNull();
        assertThat(SafeRegex.validate("(abc", false).reasonTr()).contains("geçersiz");
        for (String empty : new String[]{"a*", "x?", "\\b", "^", "$", "a|", "(a|b)*", "(?m)^"}) {
            assertThat(SafeRegex.validate(empty, false)).as(empty).isNotNull();
            assertThat(SafeRegex.validate(empty, false).reasonTr()).as(empty).contains("boş metinle");
        }
        assertThat(SafeRegex.validate("v\\d+\\.\\d+", false)).isNull();
        assertThat(SafeRegex.validate("Sipariş no: \\d{6}", true)).isNull();
        assertThat(SafeRegex.validate("(?i)fiyat|stokta yok", false)).isNull();
        assertThatThrownBy(() -> SafeRegex.compile("(a", true)).isInstanceOf(SafeRegex.RegexError.class);
    }

    @Test
    @DisplayName("Karmaşıklık: iç içe yineleme derlemeden ÖNCE reddedilir (hızlı); program > 300 komut reddedilir")
    void complexity() {
        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
            assertThat(SafeRegex.validate("((a{1000}){1000}){1000}", false)).isNotNull();
            assertThat(SafeRegex.validate("(((a{30}){30}){30}){30}", false)).isNotNull();
        });
        assertThat(SafeRegex.estimate("((a{1000}){1000}){1000}")).isGreaterThan(SafeRegex.MAX_ESTIMATE);
        assertThat(SafeRegex.validate("[a-z]{900}q", false)).as("program 903").isNotNull();
        assertThat(SafeRegex.validate("(?i)(abc|def|ghi|jkl|mno|pqr|stu|vwx|yz){3,20}zz", false)).as("program 741").isNotNull();
        assertThat(SafeRegex.estimate("abc")).isLessThan(10);
        assertThat(SafeRegex.estimate("(?i)a")).isLessThan(5);
        assertThat(SafeRegex.validate("\\d{3}-\\d{4}", false)).isNull();
        assertThat(SafeRegex.validate("(foo|bar|baz){10}", false)).isNull();
    }

    @Test
    @DisplayName("Sayım: örtüşmesiz, ilk eşleşme konumu, Türkçe katlama (duyarsız), duyarlı mod")
    void counting() {
        SafeRegex.Compiled ci = SafeRegex.compile("istanbul", false);
        SafeRegex.Count c = SafeRegex.count(ci, "İSTANBUL ve istanbul ve Istanbul");
        assertThat(c.count()).isEqualTo(3);
        assertThat(c.firstStart()).isZero();
        assertThat(c.firstEnd()).isEqualTo(8);
        assertThat(c.capped()).isFalse();
        assertThat(c.scanTruncated()).isFalse();

        SafeRegex.Compiled cs = SafeRegex.compile("Istanbul", true);
        assertThat(SafeRegex.count(cs, "İSTANBUL ve istanbul ve Istanbul").count()).isEqualTo(1);

        SafeRegex.Compiled num = SafeRegex.compile("\\d{6}", false);
        assertThat(SafeRegex.count(num, "Sipariş 123456 ve 654321, 1234567").count()).isEqualTo(3);   // 7 hane = 1 eşleşme + kalan
        assertThat(SafeRegex.count(null, "x").count()).isZero();
        assertThat(SafeRegex.count(num, null).count()).isZero();
    }

    @Test
    @DisplayName("Tavan: sayım 10 000'de durur (capped); 512 K'dan uzun metin kırpılır (scanTruncated)")
    void caps() {
        SafeRegex.Compiled a = SafeRegex.compile("a", true);
        SafeRegex.Count many = SafeRegex.count(a, "a".repeat(20_000));
        assertThat(many.count()).isEqualTo(SafeRegex.MAX_COUNT);
        assertThat(many.capped()).isTrue();

        SafeRegex.Compiled z = SafeRegex.compile("zz", true);
        String big = "x".repeat(SafeRegex.REGEX_SCAN_CAP) + "zz";
        SafeRegex.Count tail = SafeRegex.count(z, big);
        assertThat(tail.count()).as("512 K ötesi aranmaz").isZero();
        assertThat(tail.scanTruncated()).isTrue();
    }

    @Test
    @DisplayName("Patolojik desen + 512 K girdi ≤ 2 sn: karesel art arda arama zaman bütçesiyle kesilir (alt sınır sayım)")
    void pathologicalBounded() {
        String hay = "a".repeat(SafeRegex.REGEX_SCAN_CAP);
        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
            // her eşleşme metnin sonuna kadar tarar → art arda aramalar karesel olurdu (ölçüm: 200 arama 10 sn)
            SafeRegex.Compiled q = SafeRegex.compile("a[^z]*z|a", true);
            SafeRegex.Count c = SafeRegex.count(q, hay, SafeRegex.MAX_COUNT, 800_000_000L);
            assertThat(c.count()).isGreaterThan(0);
            assertThat(c.timedOut()).isTrue();
            assertThat(c.scanTruncated()).isTrue();
        });
        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
            SafeRegex.Compiled p = SafeRegex.compile("(x+x+)+y", true);
            assertThat(SafeRegex.count(p, "x".repeat(SafeRegex.REGEX_SCAN_CAP)).count()).isZero();
        });
    }

    @Test
    @DisplayName("Saklı desen hoşgörülü: geçersiz → null (fırlatmaz); önbellek aynı nesneyi döndürür")
    void storedAndCache() {
        assertThat(SafeRegex.compileStored("(bozuk", false)).isNull();
        assertThat(SafeRegex.compileStored("a*", false)).isNull();
        assertThat(SafeRegex.compile("önbellek\\d", false)).isSameAs(SafeRegex.compile("önbellek\\d", false));
        assertThat(SafeRegex.compile("önbellek\\d", true)).isNotSameAs(SafeRegex.compile("önbellek\\d", false));
    }
}
