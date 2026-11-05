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

    @ParameterizedTest(name = "[{index}] {0} → {1} {2}")
    @CsvSource(delimiter = '#', quoteCharacter = '"', nullValues = "-", value = {
            // Gerçek eklenti başlıkları: bağımlılık ifadesi kütüphanenin kendi sürümü SAYILMAZ (inceleme O-1).
            "https://a.com/js/jquery.form.min.js#-#-#/*!\\n * jQuery Form Plugin\\n * version: 4.3.0\\n * Requires jQuery v1.7.2 or later\\n */",
            "https://a.com/js/vendor.js#-#-#/*!\\n * jQuery Form Plugin\\n * version: 4.3.0\\n * Requires jQuery v1.7.2 or later\\n */",
            "https://a.com/js/jquery.validate.js#-#-#/*!\\n * jQuery Validation Plugin v1.19.5\\n * Requires jQuery v1.11.4+\\n */",
            "https://a.com/js/select2.min.js#-#-#/*! Select2 4.0.13 | https://github.com/select2/select2/blob/master/LICENSE.md */",
            "https://a.com/js/bootstrap-datepicker.min.js#-#-#/*!\\n * Datepicker for Bootstrap v1.9.0 (https://github.com/uxsolutions/bootstrap-datepicker)\\n */",
            "https://a.com/js/theme.js#-#-#/*!\\n * Theme based on Bootstrap v4.1.3\\n */",
            "https://a.com/js/plugin.js#-#-#/* compatible with jQuery v1.9.1 and later */",
            "https://a.com/js/daterangepicker.js#-#-#/**\\n* @version: 3.1\\n* @author: Dan Grossman\\n*/ // needs moment.js and jQuery",
            // Eklenti adresi olan dosyada kütüphane başlığı olsa da (aynı aile) kabul edilmez.
            "https://a.com/js/jquery.cookie.js#-#-#/*! jQuery v3.6.0 | (c) OpenJS Foundation */",
            "https://a.com/js/angular-route.min.js#-#-#/*\\n @license AngularJS v1.8.2\\n (c) 2010-2020 Google LLC.\\n*/",
            "https://a.com/js/moment-timezone.js#-#-#//! moment.js\\n//! version : 2.29.1\\n",
            // Gerçek kütüphane başlıkları kabul edilmeye devam eder.
            "https://a.com/js/vendor.js#jquery#3.6.0#/*! jQuery v3.6.0 | (c) OpenJS Foundation and other contributors */",
            "https://a.com/js/vendor.js#jquery#3.6.0#/*!\\n * jQuery JavaScript Library v3.6.0\\n * https://jquery.com/\\n */",
            "https://a.com/js/bundle.js#jquery#3.4.1#!function(){};/*! jQuery v3.4.1 | (c) JS Foundation */",
            "https://a.com/js/angular.min.js#angularjs#1.8.2#/*\\n AngularJS v1.8.2\\n (c) 2010-2020 Google LLC.\\n*/",
            "https://a.com/js/ng.js#angularjs#1.7.9#/**\\n * @license AngularJS v1.7.9\\n * (c) 2010-2018 Google, Inc.\\n */",
            "https://a.com/js/hb.js#handlebars#4.7.7#/**!\\n\\n @license\\n handlebars v4.7.7\\n\\nCopyright (C) 2011-2019 */",
            "https://a.com/js/u.js#underscore#1.13.1#//     Underscore.js 1.13.1\\n//     https://underscorejs.org\\n",
            "https://a.com/js/jquery-3.4.1-custom.js#jquery#3.4.1#/*! jQuery v3.4.1 | (c) JS Foundation */",
            "https://a.com/js/jquery-ui-1.12.1.custom.min.js#jquery-ui#1.12.1#/*! jQuery UI - v1.12.1 - 2016-09-14 */"})
    void pluginBannersAreNotTheLibrary(String url, String name, String version, String banner) {
        JsLibraryCatalog.Library lib = JsLibraryCatalog.detect(url, banner.replace("\\n", "\n"));
        if (name == null) {
            assertThat(lib).as("eklenti / bağımlılık ifadesi kütüphane sayılmaz").isNull();
            return;
        }
        assertThat(lib).isEqualTo(new JsLibraryCatalog.Library(name, version));
    }

    @Test
    @DisplayName("eklenti başlığı açık listesine ve sürüm çakışmasına girmez (sahte VULNERABLE_LIBRARY / DUPLICATE_LIBRARIES yok)")
    void pluginBannerHasNoVulns() {
        JsLibraryCatalog.Library form = JsLibraryCatalog.detect("https://a.com/js/jquery.form.min.js",
                "/*!\n * jQuery Form Plugin\n * version: 4.3.0\n * Requires jQuery v1.7.2 or later\n */");
        assertThat(JsLibraryCatalog.vulnsOf(form)).isEmpty();
        assertThat(JsLibraryCatalog.pluginOf("https://a.com/js/jquery.form.min.js")).isEqualTo("jquery");
        assertThat(JsLibraryCatalog.pluginOf("https://a.com/js/bootstrap-datepicker.min.js")).isEqualTo("bootstrap");
        assertThat(JsLibraryCatalog.pluginOf("https://a.com/js/jquery.ui.datepicker.js")).isEqualTo("jquery-ui");
        assertThat(JsLibraryCatalog.pluginOf("https://a.com/js/jquery.min.js")).isNull();
        assertThat(JsLibraryCatalog.pluginOf("https://a.com/js/jquery-3.4.1.min.js")).isNull();
        assertThat(JsLibraryCatalog.pluginOf("https://a.com/js/jquery-v3.4.1.js")).isNull();
        assertThat(JsLibraryCatalog.pluginOf("https://a.com/js/bootstrap.bundle.min.js")).isNull();
        assertThat(JsLibraryCatalog.pluginOf("https://a.com/js/moment-with-locales.min.js")).isNull();
        assertThat(JsLibraryCatalog.pluginOf("https://a.com/js/jquery2x.js")).isNull();
        assertThat(JsLibraryCatalog.pluginOf(null)).isNull();
    }

    @Test
    @DisplayName("yorum başı bağlamı: satır başı, @license, satır ortasında açılan yorum; önünde sözcük varsa hayır")
    void commentStartContext() {
        assertThat(JsLibraryCatalog.atCommentStart("/*! jQuery v3", 4)).isTrue();
        assertThat(JsLibraryCatalog.atCommentStart(";x();/*! jQuery v3", 9)).isTrue();
        assertThat(JsLibraryCatalog.atCommentStart(" * @license AngularJS v1", 12)).isTrue();
        assertThat(JsLibraryCatalog.atCommentStart(" * Requires jQuery v1", 12)).isFalse();
        assertThat(JsLibraryCatalog.atCommentStart("/*! Requires jQuery v1", 13)).isFalse();
        assertThat(JsLibraryCatalog.atCommentStart(" ".repeat(100) + "jQuery v1", 100)).as("sınırlı geri tarama").isFalse();
        assertThat(JsLibraryCatalog.laterQualifier("v1.7.2 or later", 6)).isTrue();
        assertThat(JsLibraryCatalog.laterQualifier("v1.11.4+", 7)).isTrue();
        assertThat(JsLibraryCatalog.laterQualifier("v3.6.0 | (c) OpenJS Foundation and other", 6)).isFalse();
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
