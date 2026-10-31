package com.sitemonitor.service.page.scan;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Kaynak analizinde ölçülen tek nesne (2026-10-31). Alan adları API sözleşmesidir ({@link #toMap()} snake_case yazar);
 * {@code parent} ve {@code seq} yalnız tahmin içindir, yazılmaz.
 */
public final class ScanItem {

    public String url;
    public String category;
    public String contentType;
    /** HTTP durum kodu; aktarım hatası / güvenlik engeli → 0. */
    public int status;
    public long bytes;
    /** Bayt sayımı tavanda / süre bütçesinde kesildi ("en az bu kadar"). */
    public boolean truncated;
    /** "body" (indirilen bayt) | "header" (gövde tavanda kesildi, boyut Content-Length başlığından). */
    public String sizeSource = "body";
    public long ttfbMs;
    public long durationMs;
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

    /** Tahmin için: bu nesneyi bulduran stil dosyasının sırası (yoksa -1 = HTML). */
    public int parent = -1;
    /** Keşif sırası (HTML = 0). */
    public int seq;

    public boolean failed() {
        return status == 0 || status >= 400;
    }

    public boolean ok() {
        return status >= 200 && status < 400;
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
        return m;
    }
}
