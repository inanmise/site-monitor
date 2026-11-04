package com.sitemonitor.service.page.scan.deep;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Algoritma 3 katalogları ve URL yardımcıları — doğruluk tabloları (saf, ağ yok). */
class DeepCatalogsTest {

    @ParameterizedTest(name = "{0} → {1}")
    @CsvSource(delimiter = '|', value = {
            "https://www.google-analytics.com/analytics.js|analytics",
            "https://www.googletagmanager.com/gtm.js?id=GTM-X|tag_manager",
            "https://fonts.googleapis.com/css2?family=Inter|fonts",
            "https://maps.googleapis.com/maps/api/js|maps",
            "https://ajax.googleapis.com/ajax/libs/jquery/3.4.1/jquery.min.js|cdn",
            "https://www.google.com/recaptcha/api.js|captcha",
            "https://www.google.com/search|other",
            "https://static.hotjar.com/c/hotjar-1.js|session_replay",
            "https://connect.facebook.net/en_US/fbevents.js|social",
            "https://securepubads.g.doubleclick.net/tag/js/gpt.js|advertising",
            "https://js.stripe.com/v3/|payment",
            "https://www.youtube.com/embed/x|video",
            "https://widget.intercom.io/widget/x|chat",
            "https://cdn.optimizely.com/js/1.js|ab_testing",
            "https://challenges.cloudflare.com/turnstile/v0/api.js|captcha",
            "https://cdn.example.com/x.js|other",
            "not a url|other"})
    void thirdPartyCategories(String url, String category) {
        assertThat(ThirdPartyCatalog.categoryOf(url)).isEqualTo(category);
    }

    @Test
    @DisplayName("izleme kategorileri: analytics, advertising, social, session_replay")
    void trackingSet() {
        assertThat(ThirdPartyCatalog.tracking("analytics")).isTrue();
        assertThat(ThirdPartyCatalog.tracking("session_replay")).isTrue();
        assertThat(ThirdPartyCatalog.tracking("cdn")).isFalse();
        assertThat(ThirdPartyCatalog.tracking(null)).isFalse();
    }

    @ParameterizedTest(name = "{0} → {1} {2}")
    @CsvSource(delimiter = '|', nullValues = "-", value = {
            "https://code.jquery.com/jquery-1.12.4.min.js|jquery|1.12.4",
            "https://ajax.googleapis.com/ajax/libs/jquery/3.4.1/jquery.min.js|jquery|3.4.1",
            "https://cdn.jsdelivr.net/npm/jquery@3.6.0/dist/jquery.min.js|jquery|3.6.0",
            "https://example.com/static/v2.1/js/jquery.min.js|jquery|-",
            "https://example.com/js/jquery.validate.min.js|-|-",
            "https://example.com/js/jquery-migrate-3.0.0.min.js|-|-",
            "https://ajax.googleapis.com/ajax/libs/jqueryui/1.12.1/jquery-ui.min.js|jquery-ui|1.12.1",
            "https://cdnjs.cloudflare.com/ajax/libs/twitter-bootstrap/4.1.0/js/bootstrap.bundle.min.js|bootstrap|4.1.0",
            "https://cdn.jsdelivr.net/npm/bootstrap@5.3.2/dist/js/bootstrap.min.js|bootstrap|5.3.2",
            "https://ajax.googleapis.com/ajax/libs/angularjs/1.5.8/angular.min.js|angularjs|1.5.8",
            "https://cdnjs.cloudflare.com/ajax/libs/lodash.js/4.17.15/lodash.min.js|lodash|4.17.15",
            "https://cdnjs.cloudflare.com/ajax/libs/moment.js/2.24.0/moment-with-locales.min.js|moment|2.24.0",
            "https://example.com/underscore-min.js|underscore|-",
            "https://example.com/app.css|-|-"})
    void libraryFromUrl(String url, String name, String version) {
        JsLibraryCatalog.Library lib = JsLibraryCatalog.detect(url, null);
        if (name == null) {
            assertThat(lib).isNull();
            return;
        }
        assertThat(lib).isNotNull();
        assertThat(lib.name()).isEqualTo(name);
        assertThat(lib.version()).isEqualTo(version);
    }

    @Test
    @DisplayName("başlık yorumu adresi ezer; lodash başlığı Underscore sanılmaz; moment sürüm satırı")
    void libraryFromBanner() {
        assertThat(JsLibraryCatalog.detect("https://a.com/vendor.js", "/*! jQuery v1.11.3 | (c) 2005, 2015 jQuery Foundation */"))
                .isEqualTo(new JsLibraryCatalog.Library("jquery", "1.11.3"));
        assertThat(JsLibraryCatalog.detect("https://a.com/x.js", "/*! jQuery UI - v1.12.1 - 2016-09-14 */"))
                .isEqualTo(new JsLibraryCatalog.Library("jquery-ui", "1.12.1"));
        assertThat(JsLibraryCatalog.detect("https://a.com/b.js", "/*!\n  * Bootstrap v4.3.1 (https://getbootstrap.com/)"))
                .isEqualTo(new JsLibraryCatalog.Library("bootstrap", "4.3.1"));
        assertThat(JsLibraryCatalog.detect("https://a.com/l.js",
                "/** @license Lodash lodash.com/license | Underscore.js 1.8.3 underscorejs.org/LICENSE */ ;var VERSION=\"4.17.15\""))
                .isEqualTo(new JsLibraryCatalog.Library("lodash", "4.17.15"));
        assertThat(JsLibraryCatalog.detect("https://a.com/m.js", "//! moment.js\n//! version : 2.29.1\n"))
                .isEqualTo(new JsLibraryCatalog.Library("moment", "2.29.1"));
        assertThat(JsLibraryCatalog.detect("https://a.com/jquery.min.js", "/* jQuery Validation Plugin v1.19.5 */"))
                .as("eklenti başlığı jQuery sayılmaz; ad adresten").isEqualTo(new JsLibraryCatalog.Library("jquery", null));
    }

    @ParameterizedTest(name = "{0} {1} → {2}")
    @CsvSource(delimiter = '|', value = {
            "jquery|1.12.4|CVE-2020-11022,CVE-2020-11023,CVE-2019-11358,CVE-2015-9251",
            "jquery|3.4.1|CVE-2020-11022,CVE-2020-11023",
            "jquery|3.5.0|",
            "jquery|1.6.2|CVE-2020-11022,CVE-2020-11023,CVE-2019-11358,CVE-2015-9251,CVE-2012-6708,CVE-2011-4969",
            "jquery-ui|1.12.1|CVE-2022-31160,CVE-2021-41182,CVE-2021-41183,CVE-2021-41184",
            "jquery-ui|1.13.2|",
            "bootstrap|3.3.7|CVE-2019-8331,CVE-2018-14040,CVE-2018-14041,CVE-2018-14042",
            "bootstrap|3.4.0|CVE-2019-8331",
            "bootstrap|4.1.2|CVE-2019-8331",
            "bootstrap|4.3.1|",
            "bootstrap|5.3.2|",
            "angularjs|1.8.3|CVE-2022-25869",
            "lodash|4.17.15|CVE-2021-23337,CVE-2020-28500",
            "lodash|4.17.21|",
            "moment|2.29.1|CVE-2022-31129",
            "moment|2.17.0|CVE-2017-18214",
            "handlebars|4.7.7|",
            "underscore|1.8.3|CVE-2021-23358",
            "underscore|1.3.1|"})
    void vulnerabilityRanges(String name, String version, String ids) {
        List<String> got = JsLibraryCatalog.vulnsOf(new JsLibraryCatalog.Library(name, version)).stream()
                .map(JsLibraryCatalog.Vuln::id).toList();
        assertThat(got).containsExactlyElementsOf(ids == null || ids.isBlank() ? List.of() : List.of(ids.split(",")));
    }

    @Test
    @DisplayName("sürümü okunamayan kütüphane açıkla eşleşmez; sürüm karşılaştırması sayısal")
    void noVersionNoVulns() {
        assertThat(JsLibraryCatalog.vulnsOf(new JsLibraryCatalog.Library("jquery", null))).isEmpty();
        assertThat(JsLibraryCatalog.compare("1.10.0", "1.9.9")).isPositive();
        assertThat(JsLibraryCatalog.compare("3.5", "3.5.0")).isZero();
    }

    @ParameterizedTest(name = "{0} → {1}")
    @CsvSource(delimiter = '|', value = {
            "https://a.com/static/main.3f2a9c1b.js|true",
            "https://a.com/static/app-BkJz8x2Q.js|true",
            "https://a.com/js/app.js?v=12|true",
            "https://a.com/js/app.js?ver=1.2.3|true",
            "https://a.com/js/bootstrap4.min.js|false",
            "https://a.com/js/es2015.js|false",
            "https://a.com/js/app.js|false",
            "https://a.com/js/app.js?q=1|false"})
    void versionedUrls(String url, boolean versioned) {
        assertThat(DeepUrls.versioned(url)).isEqualTo(versioned);
    }

    @Test
    @DisplayName("maske: gizli şüpheli sorgu değeri maskelenir, diğerleri kalır")
    void mask() {
        assertThat(DeepUrls.mask("https://a.com/x.js?token=abc&v=2")).isEqualTo("https://a.com/x.js?token=*****&v=2");
        assertThat(DeepUrls.mask("https://a.com/x.js?v=2")).isEqualTo("https://a.com/x.js?v=2");
        assertThat(DeepUrls.mask(null)).isNull();
    }
}
