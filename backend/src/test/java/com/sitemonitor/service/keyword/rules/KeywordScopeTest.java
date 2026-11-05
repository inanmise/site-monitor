package com.sitemonitor.service.keyword.rules;

import com.sitemonitor.service.keyword.rules.KeywordRule.Kind;
import com.sitemonitor.service.keyword.rules.KeywordRule.Op;
import com.sitemonitor.service.keyword.rules.KeywordRule.Scope;
import com.sitemonitor.service.keyword.rules.KeywordRule.ScopeType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Kural bölgeleri (kw sözleşmesi §6.2): CSS (jsoup, en çok bir ayrıştırma, iç içe öğe tekilleştirmesi), JSON (yol, değer metni,
 * bulunamadı / geçersiz), sayfa metni kapsamı (RAW / RAW+normalize / VISIBLE) ve kayıt doğrulaması.
 */
class KeywordScopeTest {

    static final String HTML = "<html><head><style>.x{}</style><script>var fiyat='gizli';</script></head><body>"
            + "<div id='urun'><span class='fiyat'>1.250&nbsp;TL</span><div class='fiyat'>İndirim  yok</div></div>"
            + "<ul><li class='m'>Bir</li><li class='m'>İki</li></ul></body></html>";

    @Test
    @DisplayName("CSS: seçilen öğelerin metni satır satır; iç içe seçilen öğe yeniden sayılmaz; bölge yoksa SCOPE_NOT_FOUND")
    void css() {
        KeywordContent c = KeywordContent.builder(HTML).build();
        KeywordContent.ScopeText s = c.css(".fiyat");
        assertThat(s.note()).isNull();
        assertThat(s.normalized()).isTrue();
        assertThat(s.text()).isEqualTo("1.250 TL\nİndirim yok");
        assertThat(c.cssLines("li.m")).containsExactly("Bir", "İki");
        // div seçicisi: dış #urun seçilir, içteki div.fiyat ayrıca sayılmaz
        assertThat(c.cssLines("div")).hasSize(1);
        assertThat(c.css("#yok").note()).isEqualTo(KeywordRuleEvaluator.NOTE_SCOPE_NOT_FOUND);
        assertThat(c.css("").note()).isEqualTo(KeywordRuleEvaluator.NOTE_SCOPE_NOT_FOUND);
        assertThat(c.css("div[[[").note()).as("çalışmayan seçici → bölge yok").isEqualTo(KeywordRuleEvaluator.NOTE_SCOPE_NOT_FOUND);
    }

    @Test
    @DisplayName("CSS bölgesinde METİN iğnesi normalleştirilir (varlık + boşluk): '1.250&nbsp;TL' = '1.250 TL'")
    void cssNeedleNormalized() {
        KeywordRule r = new KeywordRule("a", Kind.TEXT, "1.250&nbsp;TL", false, Op.GTE, 1, null,
                new Scope(ScopeType.CSS, "span.fiyat"), null);
        var e = KeywordRuleEvaluator.evaluate(List.of(r), "AND", KeywordContent.builder(HTML).build(), null);
        assertThat(e.results().get(0).count()).isEqualTo(1);
    }

    @Test
    @DisplayName("JSON: dize / sayı / mantıksal / null / nesne; yol yok → SCOPE_NOT_FOUND; JSON değil → SCOPE_INVALID")
    void json() {
        String body = "{\"status\":\"UP\",\"n\":42,\"ok\":true,\"x\":null,\"items\":[{\"ad\":\"Fiyat\"}],\"obj\":{\"a\":1}}";
        KeywordContent c = KeywordContent.builder(body).build();
        assertThat(c.json("$.status").text()).isEqualTo("UP");
        assertThat(c.json("$.n").text()).isEqualTo("42");
        assertThat(c.json("ok").text()).isEqualTo("true");
        assertThat(c.json("$.x").text()).isEmpty();
        assertThat(c.json("$.x").note()).as("yol var, değer null").isNull();
        assertThat(c.json("$.items[0].ad").text()).isEqualTo("Fiyat");
        assertThat(c.json("$.obj").text()).isEqualTo("{\"a\":1}");
        assertThat(c.json("$.yok").note()).isEqualTo(KeywordRuleEvaluator.NOTE_SCOPE_NOT_FOUND);
        assertThat(c.json("$.items[5]").note()).isEqualTo(KeywordRuleEvaluator.NOTE_SCOPE_NOT_FOUND);
        assertThat(c.json("$.status").normalized()).isFalse();
        assertThat(KeywordContent.builder("<html>").build().json("$.a").note()).isEqualTo(KeywordRuleEvaluator.NOTE_SCOPE_INVALID);
        assertThat(KeywordContent.builder("").build().json("$.a").note()).isEqualTo(KeywordRuleEvaluator.NOTE_SCOPE_INVALID);
        assertThat(KeywordContent.builder("{\"a\":").build().json("$.a").note())
                .as("kırpılmış gövde").isEqualTo(KeywordRuleEvaluator.NOTE_SCOPE_INVALID);
    }

    @Test
    @DisplayName("JSON nesne / dizi metni ≤ 64 000 karakter")
    void jsonContainerCapped() {
        String body = "{\"big\":\"" + "a".repeat(70_000) + "\",\"arr\":[\"" + "b".repeat(70_000) + "\"]}";
        KeywordContent c = KeywordContent.builder(body).build();
        assertThat(c.json("$.arr").text()).hasSize(KeywordContent.MAX_JSON_SCOPE_CHARS);
        assertThat(c.json("$.big").text()).as("dize değer kırpılmaz").hasSize(70_000);
    }

    @Test
    @DisplayName("Pahalı ayrıştırma en çok BİR kez: görünür metin üreticisi bir kez çağrılır, CSS / JSON önbellekli")
    void parsedOnce() {
        AtomicInteger visibleCalls = new AtomicInteger();
        KeywordContent c = KeywordContent.builder(HTML).visibleScope(true)
                .visibleText(s -> { visibleCalls.incrementAndGet(); return "görünür metin"; }).build();
        c.pageText();
        c.pageText();
        assertThat(visibleCalls.get()).isEqualTo(1);
        assertThat(c.css(".fiyat")).isSameAs(c.css(".fiyat"));
        KeywordContent j = KeywordContent.builder("{\"a\":\"b\"}").build();
        assertThat(j.json("$.a")).isSameAs(j.json("$.a"));
    }

    @Test
    @DisplayName("Sayfa metni kapsamı: RAW ham (betik dahil); RAW+normalize varlık/boşluk; VISIBLE görünür metin (betik yok)")
    void pageScopes() {
        KeywordRule fiyat = new KeywordRule("a", Kind.TEXT, "fiyat", false, Op.GTE, 1, null, null, null);
        var raw = KeywordRuleEvaluator.evaluate(List.of(fiyat), "AND", KeywordContent.builder(HTML).build(), null);
        assertThat(raw.results().get(0).count()).as("ham metin: betik + sınıf adları").isGreaterThanOrEqualTo(3);
        var vis = KeywordRuleEvaluator.evaluate(List.of(fiyat), "AND",
                KeywordContent.builder(HTML).visibleScope(true).build(), null);
        assertThat(vis.results().get(0).count()).as("görünür metinde 'fiyat' yok").isZero();

        KeywordRule spaced = new KeywordRule("b", Kind.TEXT, "İndirim yok", false, Op.GTE, 1, null, null, null);
        assertThat(KeywordRuleEvaluator.evaluate(List.of(spaced), "AND", KeywordContent.builder(HTML).build(), null)
                .results().get(0).count()).as("ham metinde çift boşluk").isZero();
        assertThat(KeywordRuleEvaluator.evaluate(List.of(spaced), "AND",
                KeywordContent.builder(HTML).normalizeWs(true).build(), null).results().get(0).count()).isEqualTo(1);
        assertThat(KeywordRuleEvaluator.evaluate(List.of(spaced), "AND",
                KeywordContent.builder(HTML).visibleScope(true).build(), null).results().get(0).count()).isEqualTo(1);
        assertThat(KeywordContent.builder(HTML).visibleScope(true).build().pageNormalized()).isTrue();
        assertThat(KeywordContent.builder(HTML).build().pageNormalized()).isFalse();
    }

    @Test
    @DisplayName("Kayıt doğrulaması: CSS boş / uzun / denetim karakteri / :matches / ~= / çift :has / geçersiz; JSON yolu")
    void validation() {
        assertThat(KeywordScopes.validateCss("#fiyat .tutar")).isNull();
        assertThat(KeywordScopes.validateCss("main h1:has(span)")).isNull();
        assertThat(KeywordScopes.validateCss("")).isNotNull();
        assertThat(KeywordScopes.validateCss("a".repeat(301))).isNotNull();
        assertThat(KeywordScopes.validateCss("div\nspan")).isNotNull();
        assertThat(KeywordScopes.validateCss("p:matchesOwn(x)")).isNotNull();
        assertThat(KeywordScopes.validateCss("p:MATCHES(x)")).isNotNull();
        assertThat(KeywordScopes.validateCss("[title~=a]")).isNotNull();
        assertThat(KeywordScopes.validateCss("a:has(b):has(c)")).isNotNull();
        assertThat(KeywordScopes.validateCss("div >>> ")).isNotNull();
        assertThat(KeywordScopes.validateJsonPath("$.a[0].b")).isNull();
        assertThat(KeywordScopes.validateJsonPath("")).isNotNull();
        assertThat(KeywordScopes.validateJsonPath("$.a[")).isNotNull();
    }
}
