package com.sitemonitor.service.page.scan;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Kaynak analizinde ölçülen tek nesne (2026-10-31). Alan adları API sözleşmesidir ({@link #toMap()} snake_case yazar);
 * {@code parent} ve {@code seq} yalnız tahmin içindir, yazılmaz.
 *
 * <p>Algoritma 2 (2026-11) ek alanları: {@code host}, {@code found_in}, {@code final_url}, {@code headers}, {@code findings},
 * {@code verdict}, {@code via}, {@code optimized_bytes} (yalnız ölçülen görsellerde). Eski kayıtlarda yoktur — okuyan taraf
 * yokluğu "bu analizde kaydedilmemiş" sayar, sunucu eski kayıt için uydurmaz.
 */
public final class ScanItem {

    /** Karar değerleri — Sayfa Bütünlüğü izlemesinin kuralıyla aynı (bkz. {@code PageResourceScanService.verdictOf}). */
    public static final String OK = "ok", BROKEN = "broken", UNVERIFIED = "unverified", BLOCKED = "blocked", SKIPPED = "skipped";
    /** Boyut kaynağı: indirilen bayt / {@code Content-Length} başlığı / bilinmiyor (gövdesiz yoklamada başlık yok). */
    public static final String SIZE_BODY = "body", SIZE_HEADER = "header", SIZE_UNKNOWN = "unknown";

    public String url;
    public String category;
    public String contentType;
    /** HTTP durum kodu; aktarım hatası / güvenlik engeli / indirilmedi → 0. */
    public int status;
    public long bytes;
    /** Bayt sayımı tavanda / süre bütçesinde kesildi ("en az bu kadar"). */
    public boolean truncated;
    /** "body" (indirilen bayt) | "header" (Content-Length) | "unknown" (gövdesiz yoklama, başlık yok). */
    public String sizeSource = SIZE_BODY;
    /** Algoritma 2: YALNIZ son adımın ilk baytı (yönlendirme hariç). */
    public long ttfbMs;
    public long durationMs;
    /** Yönlendirme adımlarının toplam süresi (algoritma 2). */
    public long redirectMs;
    /** Tahmini sayfa yüklemesindeki başlangıç ofseti (şelale); tahmine girmeyen nesnede null. */
    public Long startMs;
    public boolean thirdParty;
    public boolean compressed;
    /** max-age saniyesi; no-store / no-cache → -1; başlık yok → null. */
    public Long cacheTtlS;
    public Integer width;
    public Integer height;
    public int redirects;
    public boolean fromCss;
    public boolean pageLoad;
    public boolean lazy;
    /** Kısa hata kodu ({@code CheckFailureReason} adı ya da {@code BLOCKED}); başarılı yanıtta null. */
    public String error;

    /** Host (küçük harf). */
    public String host;
    /** Bulunduğu yer: stil dosyasının URL'si ya da {@code page}; HTML satırının kendisinde null. */
    public String foundIn;
    /** Yönlendirme sonrası son adres; yönlendirme yoksa null. */
    public String finalUrl;
    /** Seçili yanıt başlıkları (değerler ≤ 200 karakter; çerez / kimlik başlığı ASLA). Ölçülmediyse null. */
    public Map<String, String> headers;
    /** Karar ({@link #OK} …); null = eski kayıt / testte elle kurulan satır → durum kuralı ({@link #failed()}). */
    public String verdict;
    /** Bu nesnenin çekim yolu: {@code proxy} | {@code direct}; indirilmediyse null. */
    public String via;
    /** Ölçülmüş görsel kazancı: sunucuda yeniden kodlanmış boyut (yalnız ölçülen görsellerde; diğerlerinde null). */
    public Long optimizedBytes;
    /** Bu nesneye uyan öneri kodları ({@code PageResourceAnalyzer} yazar — öneriyi üreten kuralın AYNISI). */
    public final Set<String> findings = new LinkedHashSet<>();

    // ── Algoritma 3 (2026-11, derin analiz) — eski kayıtta yok ──
    /** HTTP sürümü ({@code 1.1} | {@code 2}); ölçülmediyse null. */
    public String httpVersion;
    /** Tel gövdesinin SHA-256'sı (onaltılık, 64); gövde tam okunmadıysa null. Yazılırken ilk 16 karakter. */
    public String contentHash;
    /** Küçültülmüş mü (yalnız betik / stil; örnek okunamadı / belirsiz → null). */
    public Boolean minified;
    /** Tespit edilen kütüphane (yalnız betik). */
    public String libraryName, libraryVersion;
    /** {@code sourceMappingURL} (çözülmüş mutlak adres); yoksa null. */
    public String sourceMap;
    /** Oluşturmayı engelliyor mu ({@code <head>}'deki async / defer'siz betik, medyasız stil, onların {@code @import}'u). */
    public boolean renderBlocking;
    /** SRI ({@code integrity}) var mı — yalnız HTML öğesinden gelen betik / stil; diğerlerinde null. */
    public Boolean integrity;
    /** Üçüncü taraf kategorisi ({@code ThirdPartyCatalog}); birinci tarafta null. */
    public String thirdPartyCategory;
    /** GIF kare sayısı (ilk baytlardan, alt sınır) — yalnız bellekte. */
    public Integer gifFrames;
    /** {@code Alt-Svc}'de h3 duyuruldu mu — yalnız bellekte (host tablosu). */
    public boolean h3Advertised;
    /** ETag / Last-Modified var mı — yalnız bellekte (doğrulayıcı kuralı). */
    public boolean validator;

    /** Tahmin için: bu nesneyi bulduran stil dosyasının sırası (yoksa -1 = HTML). */
    public int parent = -1;
    /** Keşif sırası (HTML = 0). */
    public int seq;

    /** Kırık mı (hata puanına ve BROKEN_* önerilerine giren tek sınıf). Karar yoksa eski durum kuralı. */
    public boolean failed() {
        if (verdict != null) return BROKEN.equals(verdict);
        return status == 0 || status >= 400;
    }

    /** İndirilmedi (ör. {@code preload="none"} medya) — sayfa boyutuna, isteğe, nota girmez. */
    public boolean skipped() {
        return SKIPPED.equals(verdict);
    }

    public boolean ok() {
        return status >= 200 && status < 400;
    }

    /**
     * Ölçüm kopyası (prs3 G — çok sayfalı analiz, 2026-11): ölçülen alanlar aynen, analizin YAZDIĞI alanlar sıfırlanır
     * ({@code startMs}, {@code findings}, {@code renderBlocking}, {@code integrity}, {@code thirdPartyCategory}, {@code parent},
     * {@code seq}). Sayfalar arası ortak nesne bir kez ölçülür; her sayfanın analizi kendi kopyasını işaretler (asıl nesne
     * ana sayfanın analizinde kalır). Yeni bir alan buraya da eklenmeli — {@code ScanItemCopyTest} yansımayla denetler.
     */
    public ScanItem measurementCopy() {
        ScanItem c = new ScanItem();
        c.url = url;
        c.category = category;
        c.contentType = contentType;
        c.status = status;
        c.bytes = bytes;
        c.truncated = truncated;
        c.sizeSource = sizeSource;
        c.ttfbMs = ttfbMs;
        c.durationMs = durationMs;
        c.redirectMs = redirectMs;
        c.thirdParty = thirdParty;
        c.compressed = compressed;
        c.cacheTtlS = cacheTtlS;
        c.width = width;
        c.height = height;
        c.redirects = redirects;
        c.fromCss = fromCss;
        c.pageLoad = pageLoad;
        c.lazy = lazy;
        c.error = error;
        c.host = host;
        c.foundIn = foundIn;
        c.finalUrl = finalUrl;
        c.headers = headers == null ? null : new LinkedHashMap<>(headers);
        c.verdict = verdict;
        c.via = via;
        c.optimizedBytes = optimizedBytes;
        c.httpVersion = httpVersion;
        c.contentHash = contentHash;
        c.minified = minified;
        c.libraryName = libraryName;
        c.libraryVersion = libraryVersion;
        c.sourceMap = sourceMap;
        c.gifFrames = gifFrames;
        c.h3Advertised = h3Advertised;
        c.validator = validator;
        // Analizin yazdıkları: startMs = null, findings boş, renderBlocking = false, integrity / thirdPartyCategory = null,
        // parent = -1, seq = 0 (yeni nesnenin varsayılanları)
        return c;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("url", url);
        m.put("category", category);
        m.put("content_type", contentType);
        m.put("status", status);
        m.put("bytes", bytes);
        m.put("truncated", truncated);
        m.put("size_source", sizeSource);
        m.put("ttfb_ms", ttfbMs);
        m.put("duration_ms", durationMs);
        m.put("start_ms", startMs);
        m.put("third_party", thirdParty);
        m.put("compressed", compressed);
        m.put("cache_ttl_s", cacheTtlS);
        m.put("width", width);
        m.put("height", height);
        m.put("redirects", redirects);
        m.put("from_css", fromCss);
        m.put("page_load", pageLoad);
        m.put("lazy", lazy);
        m.put("error", error);
        // ── algoritma 2 ──
        m.put("host", host);
        m.put("found_in", foundIn);
        m.put("final_url", finalUrl);
        m.put("headers", headers == null ? null : new LinkedHashMap<>(headers));
        m.put("findings", new ArrayList<>(findings));
        m.put("verdict", verdict);
        m.put("via", via);
        if (optimizedBytes != null) m.put("optimized_bytes", optimizedBytes);
        return m;
    }

    /**
     * Algoritma 3 satırı: {@link #toMap()} + derin alanlar. Adresler {@code DeepUrls.mask} ile (gizli değer şüphesi olan sorgu
     * parametresi {@code *****}); aynı kural bölüm listelerinde de uygulanır.
     */
    public Map<String, Object> toDeepMap() {
        Map<String, Object> m = toMap();
        m.put("url", com.sitemonitor.service.page.scan.deep.DeepUrls.mask(url));
        m.put("final_url", com.sitemonitor.service.page.scan.deep.DeepUrls.mask(finalUrl));
        if (foundIn != null && !"page".equals(foundIn)) m.put("found_in", com.sitemonitor.service.page.scan.deep.DeepUrls.mask(foundIn));
        m.put("http_version", httpVersion);
        m.put("content_hash", contentHash == null ? null : contentHash.substring(0, Math.min(16, contentHash.length())));
        m.put("minified", minified);
        if (libraryName != null) {
            Map<String, Object> lib = new LinkedHashMap<>();
            lib.put("name", libraryName);
            lib.put("version", libraryVersion);
            m.put("library", lib);
        } else {
            m.put("library", null);
        }
        m.put("source_map", com.sitemonitor.service.page.scan.deep.DeepUrls.mask(sourceMap));
        m.put("render_blocking", renderBlocking);
        m.put("integrity", integrity);
        m.put("third_party_category", thirdPartyCategory);
        return m;
    }
}
