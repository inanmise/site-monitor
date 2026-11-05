package com.sitemonitor.service.page.scan.deep;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Üçüncü taraf kategorisi (2026-11, kaynak analizi algoritma 3) — alan adı SONEKİ → kategori; SAF, ağ yok.
 *
 * <p>En uzun eşleşen sonek kazanır ({@code maps.googleapis.com} → maps, {@code fonts.googleapis.com} → fonts,
 * {@code ajax.googleapis.com} → cdn). Bazı girdiler yol önekine de bakar ({@code www.google.com/recaptcha} → captcha).
 * Bilinmeyen → {@value #OTHER}. Katalog bilgi amaçlıdır — "izleme" kararı da buradan çıkar ({@link #TRACKING}) ve arayüz
 * bunun sezgisel olduğunu söyler.
 */
public final class ThirdPartyCatalog {

    public static final String ANALYTICS = "analytics", ADVERTISING = "advertising", TAG_MANAGER = "tag_manager",
            SOCIAL = "social", CDN = "cdn", FONTS = "fonts", VIDEO = "video", CHAT = "chat", AB_TESTING = "ab_testing",
            SESSION_REPLAY = "session_replay", PAYMENT = "payment", MAPS = "maps", CAPTCHA = "captcha", OTHER = "other";

    /** Sabit kategori sırası (arayüz ve bölüm bu sırayla). */
    public static final List<String> CATEGORIES = List.of(ANALYTICS, ADVERTISING, TAG_MANAGER, SOCIAL, CDN, FONTS, VIDEO,
            CHAT, AB_TESTING, SESSION_REPLAY, PAYMENT, MAPS, CAPTCHA, OTHER);

    /** Sayfa ilk yüklenirken çalışınca "izin alınmadan izleme OLABİLİR" sayılan kategoriler. */
    public static final Set<String> TRACKING = Set.of(ANALYTICS, ADVERTISING, SOCIAL, SESSION_REPLAY);

    /** Yol önekli girdiler (host + yol başlangıcı). */
    private record PathRule(String host, String pathPrefix, String category) {}

    private static final List<PathRule> PATH_RULES = List.of(
            new PathRule("www.google.com", "/recaptcha", CAPTCHA),
            new PathRule("google.com", "/recaptcha", CAPTCHA),
            new PathRule("www.gstatic.com", "/recaptcha", CAPTCHA),
            new PathRule("www.recaptcha.net", "/", CAPTCHA));

    private static final Map<String, String> SUFFIXES = Map.ofEntries(
            // analytics
            Map.entry("google-analytics.com", ANALYTICS), Map.entry("analytics.google.com", ANALYTICS),
            Map.entry("mc.yandex.ru", ANALYTICS), Map.entry("mc.yandex.com", ANALYTICS),
            Map.entry("matomo.cloud", ANALYTICS), Map.entry("piwik.pro", ANALYTICS),
            Map.entry("segment.com", ANALYTICS), Map.entry("segment.io", ANALYTICS), Map.entry("mixpanel.com", ANALYTICS),
            Map.entry("amplitude.com", ANALYTICS), Map.entry("heapanalytics.com", ANALYTICS), Map.entry("heap.io", ANALYTICS),
            Map.entry("nr-data.net", ANALYTICS), Map.entry("newrelic.com", ANALYTICS),
            Map.entry("plausible.io", ANALYTICS), Map.entry("cloudflareinsights.com", ANALYTICS),
            Map.entry("omtrdc.net", ANALYTICS), Map.entry("2o7.net", ANALYTICS), Map.entry("chartbeat.com", ANALYTICS),
            Map.entry("quantserve.com", ANALYTICS), Map.entry("scorecardresearch.com", ANALYTICS),
            // advertising
            Map.entry("doubleclick.net", ADVERTISING), Map.entry("googlesyndication.com", ADVERTISING),
            Map.entry("googleadservices.com", ADVERTISING), Map.entry("adservice.google.com", ADVERTISING),
            Map.entry("criteo.com", ADVERTISING), Map.entry("criteo.net", ADVERTISING), Map.entry("taboola.com", ADVERTISING),
            Map.entry("outbrain.com", ADVERTISING), Map.entry("adnxs.com", ADVERTISING),
            Map.entry("rubiconproject.com", ADVERTISING), Map.entry("pubmatic.com", ADVERTISING),
            Map.entry("amazon-adsystem.com", ADVERTISING), Map.entry("ads-twitter.com", ADVERTISING),
            Map.entry("bat.bing.com", ADVERTISING), Map.entry("adform.net", ADVERTISING),
            Map.entry("smartadserver.com", ADVERTISING), Map.entry("demdex.net", ADVERTISING),
            Map.entry("analytics.tiktok.com", ADVERTISING), Map.entry("snap.licdn.com", ADVERTISING),
            Map.entry("ads.linkedin.com", ADVERTISING),
            // tag_manager
            Map.entry("googletagmanager.com", TAG_MANAGER), Map.entry("tiqcdn.com", TAG_MANAGER),
            Map.entry("adobedtm.com", TAG_MANAGER), Map.entry("ensighten.com", TAG_MANAGER),
            // social
            Map.entry("facebook.net", SOCIAL), Map.entry("facebook.com", SOCIAL), Map.entry("fbcdn.net", SOCIAL),
            Map.entry("platform.twitter.com", SOCIAL), Map.entry("twitter.com", SOCIAL), Map.entry("twimg.com", SOCIAL),
            Map.entry("licdn.com", SOCIAL), Map.entry("linkedin.com", SOCIAL), Map.entry("instagram.com", SOCIAL),
            Map.entry("pinterest.com", SOCIAL), Map.entry("pinimg.com", SOCIAL), Map.entry("addthis.com", SOCIAL),
            Map.entry("sharethis.com", SOCIAL), Map.entry("tiktok.com", SOCIAL),
            // cdn
            Map.entry("cdnjs.cloudflare.com", CDN), Map.entry("jsdelivr.net", CDN), Map.entry("unpkg.com", CDN),
            Map.entry("ajax.googleapis.com", CDN), Map.entry("code.jquery.com", CDN), Map.entry("bootstrapcdn.com", CDN),
            Map.entry("ajax.aspnetcdn.com", CDN), Map.entry("cloudfront.net", CDN), Map.entry("akamaihd.net", CDN),
            Map.entry("azureedge.net", CDN), Map.entry("fastly.net", CDN), Map.entry("b-cdn.net", CDN),
            // fonts
            Map.entry("fonts.googleapis.com", FONTS), Map.entry("fonts.gstatic.com", FONTS), Map.entry("typekit.net", FONTS),
            Map.entry("fontawesome.com", FONTS), Map.entry("fonts.bunny.net", FONTS),
            // video
            Map.entry("youtube.com", VIDEO), Map.entry("youtube-nocookie.com", VIDEO), Map.entry("ytimg.com", VIDEO),
            Map.entry("vimeo.com", VIDEO), Map.entry("vimeocdn.com", VIDEO), Map.entry("wistia.com", VIDEO),
            Map.entry("wistia.net", VIDEO), Map.entry("jwpcdn.com", VIDEO), Map.entry("brightcove.net", VIDEO),
            // chat
            Map.entry("intercom.io", CHAT), Map.entry("intercomcdn.com", CHAT), Map.entry("zdassets.com", CHAT),
            Map.entry("zendesk.com", CHAT), Map.entry("tawk.to", CHAT), Map.entry("livechatinc.com", CHAT),
            Map.entry("drift.com", CHAT), Map.entry("crisp.chat", CHAT), Map.entry("freshchat.com", CHAT),
            // ab_testing
            Map.entry("optimizely.com", AB_TESTING), Map.entry("visualwebsiteoptimizer.com", AB_TESTING),
            Map.entry("vwo.com", AB_TESTING), Map.entry("abtasty.com", AB_TESTING), Map.entry("googleoptimize.com", AB_TESTING),
            // session_replay
            Map.entry("hotjar.com", SESSION_REPLAY), Map.entry("hotjar.io", SESSION_REPLAY), Map.entry("clarity.ms", SESSION_REPLAY),
            Map.entry("fullstory.com", SESSION_REPLAY), Map.entry("mouseflow.com", SESSION_REPLAY),
            Map.entry("logrocket.com", SESSION_REPLAY), Map.entry("lr-ingest.io", SESSION_REPLAY),
            Map.entry("smartlook.com", SESSION_REPLAY), Map.entry("inspectlet.com", SESSION_REPLAY),
            Map.entry("contentsquare.net", SESSION_REPLAY),
            // payment
            Map.entry("stripe.com", PAYMENT), Map.entry("paypal.com", PAYMENT), Map.entry("paypalobjects.com", PAYMENT),
            Map.entry("iyzipay.com", PAYMENT), Map.entry("braintreegateway.com", PAYMENT), Map.entry("adyen.com", PAYMENT),
            // maps
            Map.entry("maps.googleapis.com", MAPS), Map.entry("maps.gstatic.com", MAPS), Map.entry("mapbox.com", MAPS),
            Map.entry("openstreetmap.org", MAPS), Map.entry("api-maps.yandex.ru", MAPS),
            // captcha
            Map.entry("hcaptcha.com", CAPTCHA), Map.entry("challenges.cloudflare.com", CAPTCHA));

    private ThirdPartyCatalog() {}

    /** Adresin kategorisi (yol önekli kurallar önce, sonra en uzun host soneki); bilinmeyen → {@value #OTHER}. */
    public static String categoryOf(String url) {
        String host = DeepUrls.host(url);
        if (host == null) return OTHER;
        String path = DeepUrls.path(url);
        for (PathRule r : PATH_RULES) {
            if (host.equals(r.host()) && path.startsWith(r.pathPrefix())) return r.category();
        }
        return categoryOfHost(host);
    }

    /** Host'un kategorisi: tam eşleşme ya da nokta sınırında sonek, en uzunu kazanır. */
    public static String categoryOfHost(String host) {
        if (host == null || host.isBlank()) return OTHER;
        String h = host.toLowerCase(Locale.ROOT);
        if (h.endsWith(".")) h = h.substring(0, h.length() - 1);
        // Sonekleri soldan kısalt: a.b.example.com → b.example.com → example.com (≤ etiket sayısı tur)
        String probe = h;
        while (true) {
            String c = SUFFIXES.get(probe);
            if (c != null) return c;
            int dot = probe.indexOf('.');
            if (dot < 0) return OTHER;
            probe = probe.substring(dot + 1);
        }
    }

    /** Kategori izleme sayılır mı (sayfa ilk yüklenirken çalışıyorsa "izinsiz olabilir"). */
    public static boolean tracking(String category) {
        return category != null && TRACKING.contains(category);
    }
}
