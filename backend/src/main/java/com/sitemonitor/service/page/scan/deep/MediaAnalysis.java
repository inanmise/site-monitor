package com.sitemonitor.service.page.scan.deep;

import com.sitemonitor.service.page.scan.PageResourceDiscovery;
import com.sitemonitor.service.page.scan.ScanItem;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * C — Görsel ve yazı tipi (2026-11, kaynak analizi algoritma 3) → {@code analysis.media}. SAF.
 *
 * <ul>
 *   <li>Boyutsuz görsel: {@code <img>}'de genişlik VE yükseklik yok (öznitelik ya da satır içi stil / aspect-ratio) — yerleşim
 *       kayması (CLS) riski.</li>
 *   <li>{@code srcset}'siz geniş görsel: ≥ {@value #SRCSET_MIN_WIDTH} px, {@code srcset} / {@code <picture>} yok.</li>
 *   <li>Ekran dışı ama geç yüklenmeyen (SEZGİSEL): DOM sırasında ilk {@value #ABOVE_FOLD_IMAGES} görselden sonraki,
 *       {@code loading=lazy} olmayan, ≥ {@value #NOT_LAZY_MIN_BYTES} bayt içerik görselleri.</li>
 *   <li>Hareketli GIF: ilk baytlarda ≥ 2 kare.</li>
 *   <li>Yazı tipleri: aileler / ağırlıklar ({@code @font-face}), woff2 dışı biçim, {@code font-display}'siz aile, önyüklenen.</li>
 * </ul>
 */
public final class MediaAnalysis {

    static final int SRCSET_MIN_WIDTH = 1000;
    static final int ABOVE_FOLD_IMAGES = 4;
    static final long NOT_LAZY_MIN_BYTES = 10 * 1024L;
    static final int TOO_MANY_FAMILIES = 4, TOO_MANY_VARIANTS = 8;
    static final long MEDIUM_BYTES = 500 * 1024L;
    /** GIF'i videoya çevirmenin tahmini kazancı; woff → woff2 kazancı. */
    static final double SAVE_GIF = 0.80, SAVE_WOFF2 = 0.30;
    static final int MISSING_DIMENSIONS_MEDIUM = 5;

    public record Result(DeepSection section) {}

    private MediaAnalysis() {}

    public static Result build(List<ScanItem> page, HtmlFacts f, DeepContext ctx) {
        Map<String, Object> json = new LinkedHashMap<>();
        List<DeepRec> recs = new ArrayList<>();
        Map<String, ScanItem> imageByUrl = new HashMap<>();
        for (ScanItem it : page) if (PageResourceDiscovery.IMAGE.equals(it.category)) imageByUrl.putIfAbsent(it.url, it);

        // ── Görseller ─────────────────────────────────────────────────────────────────────
        Map<String, Object> images = new LinkedHashMap<>();
        List<Map<String, Object>> noDim = new ArrayList<>(), noSrcset = new ArrayList<>(), notLazy = new ArrayList<>(),
                gifs = new ArrayList<>();
        List<ScanItem> noDimItems = new ArrayList<>(), noSrcsetItems = new ArrayList<>(), notLazyItems = new ArrayList<>(),
                gifItems = new ArrayList<>();
        if (f != null) {
            Set<String> seen = new LinkedHashSet<>();
            for (HtmlFacts.ImageTag t : f.images) {
                ScanItem it = imageByUrl.get(t.url());
                if (it == null || !seen.add(t.url())) continue;
                if (!(t.hasWidth() && t.hasHeight())) {
                    noDim.add(row("url", DeepUrls.mask(it.url)));
                    noDimItems.add(it);
                }
                if (it.width != null && it.width >= SRCSET_MIN_WIDTH && !t.srcset() && !t.inPicture()) {
                    noSrcset.add(row("url", DeepUrls.mask(it.url), "width", it.width));
                    noSrcsetItems.add(it);
                }
                if (t.domIndex() >= ABOVE_FOLD_IMAGES && !t.lazy() && !it.lazy && it.bytes >= NOT_LAZY_MIN_BYTES) {
                    notLazy.add(row("url", DeepUrls.mask(it.url)));
                    notLazyItems.add(it);
                }
            }
        }
        for (ScanItem it : page) {
            if (!PageResourceDiscovery.IMAGE.equals(it.category) || it.gifFrames == null || it.gifFrames < 2) continue;
            gifs.add(row("url", DeepUrls.mask(it.url), "bytes", it.bytes, "frames", it.gifFrames));
            gifItems.add(it);
        }
        DeepSection.putList(images, "missing_dimensions", noDim);
        DeepSection.putList(images, "missing_srcset", noSrcset);
        DeepSection.putList(images, "not_lazy", notLazy);
        DeepSection.putList(images, "animated_gif", gifs);
        images.put("html_checked", f != null);
        json.put("images", images);
        if (!noDimItems.isEmpty()) {
            recs.add(new DeepRec("IMAGES_WITHOUT_DIMENSIONS",
                    noDimItems.size() >= MISSING_DIMENSIONS_MEDIUM ? DeepRec.MEDIUM : DeepRec.LOW,
                    DeepRec.params("count", noDimItems.size()), null, null, noDimItems, "MISSING_DIMENSIONS"));
        }
        if (!noSrcsetItems.isEmpty()) {
            long b = DeepSection.bytes(noSrcsetItems);
            recs.add(new DeepRec("MISSING_SRCSET", b >= MEDIUM_BYTES ? DeepRec.MEDIUM : DeepRec.LOW,
                    DeepRec.params("count", noSrcsetItems.size(), "min_width", SRCSET_MIN_WIDTH), null, null, noSrcsetItems, null));
        }
        if (!notLazyItems.isEmpty()) {
            long b = DeepSection.bytes(notLazyItems);
            recs.add(new DeepRec("OFFSCREEN_NOT_LAZY", b >= MEDIUM_BYTES ? DeepRec.MEDIUM : DeepRec.LOW,
                    DeepRec.params("count", notLazyItems.size(), "bytes", b), b, null, notLazyItems, "NOT_LAZY"));
        }
        if (!gifItems.isEmpty()) {
            long b = DeepSection.bytes(gifItems);
            long save = Math.round(b * SAVE_GIF);
            recs.add(new DeepRec("ANIMATED_GIF", b >= MEDIUM_BYTES ? DeepRec.MEDIUM : DeepRec.LOW,
                    DeepRec.params("count", gifItems.size(), "bytes", b), save, null, gifItems, "ANIMATED_GIF"));
        }

        // ── Yazı tipleri ──────────────────────────────────────────────────────────────────
        List<ScanItem> fontItems = page.stream().filter(i -> PageResourceDiscovery.FONT.equals(i.category)).toList();
        Map<String, Object> fonts = new LinkedHashMap<>();
        fonts.put("count", fontItems.size());
        fonts.put("bytes", DeepSection.bytes(fontItems));
        Map<String, Set<String>> weights = new LinkedHashMap<>();
        Map<String, Set<String>> files = new LinkedHashMap<>();
        Map<String, String> missingDisplay = new LinkedHashMap<>();   // aile → stil adresi
        Set<String> noDisplayUrls = new LinkedHashSet<>();
        for (FontFaces.Face face : ctx.fontFaces()) {
            String fam = face.family();
            weights.computeIfAbsent(fam, k -> new LinkedHashSet<>()).add(face.weight());
            if (face.url() != null) files.computeIfAbsent(fam, k -> new LinkedHashSet<>()).add(face.url());
            if (face.display() == null) {
                missingDisplay.putIfAbsent(fam, face.cssUrl());
                if (face.url() != null) noDisplayUrls.add(face.url());
            }
        }
        List<Map<String, Object>> families = new ArrayList<>();
        int variants = 0;
        for (Map.Entry<String, Set<String>> e : weights.entrySet()) {
            variants += e.getValue().size();
            families.add(row("family", e.getKey(), "weights", new ArrayList<>(e.getValue()), "files",
                    files.getOrDefault(e.getKey(), Set.of()).size()));
        }
        DeepSection.putList(fonts, "families", families);
        List<Map<String, Object>> nonWoff2 = new ArrayList<>();
        List<ScanItem> nonWoff2Items = new ArrayList<>();
        int unknownFormat = 0;
        for (ScanItem it : fontItems) {
            String fmt = fontFormat(it);
            if ("woff2".equals(fmt)) continue;
            // Biçimi bilinmeyen (application/octet-stream + uzantısız adres) woff2 olabilir: "woff2 değil" sayılmaz, kazanç
            // iddia edilmez — yalnız sayılır (inceleme D-8).
            if ("unknown".equals(fmt)) { unknownFormat++; continue; }
            nonWoff2.add(row("url", DeepUrls.mask(it.url), "format", fmt));
            nonWoff2Items.add(it);
        }
        DeepSection.putList(fonts, "non_woff2", nonWoff2);
        fonts.put("unknown_format", unknownFormat);
        List<Map<String, Object>> md = new ArrayList<>();
        for (Map.Entry<String, String> e : missingDisplay.entrySet()) {
            md.add(row("family", e.getKey(), "css_url", DeepUrls.mask(e.getValue())));
        }
        DeepSection.putList(fonts, "missing_display", md);
        int preloaded = 0;
        if (f != null) for (HtmlFacts.Hint h : f.hints) if ("preload".equals(h.rel()) && "font".equals(h.as())) preloaded++;
        fonts.put("preloaded", f == null ? null : preloaded);
        json.put("fonts", fonts);
        if (!md.isEmpty()) {
            List<ScanItem> about = fontItems.stream().filter(i -> noDisplayUrls.contains(i.url)).toList();
            recs.add(new DeepRec("FONT_DISPLAY_MISSING", DeepRec.LOW, DeepRec.params("count", md.size()), null, null, about,
                    "FONT_DISPLAY_MISSING"));
        }
        if (weights.size() > TOO_MANY_FAMILIES || variants > TOO_MANY_VARIANTS) {
            recs.add(new DeepRec("TOO_MANY_FONTS", DeepRec.LOW, DeepRec.params("families", weights.size(), "variants", variants),
                    null, null, fontItems, null));
        }
        if (!nonWoff2Items.isEmpty()) {
            long b = DeepSection.bytes(nonWoff2Items);
            recs.add(new DeepRec("NON_WOFF2_FONTS", DeepRec.LOW, DeepRec.params("count", nonWoff2Items.size(), "bytes", b),
                    Math.round(b * SAVE_WOFF2), null, nonWoff2Items, null));
        }
        return new Result(new DeepSection(json, recs));
    }

    /** Yazı tipi biçimi: içerik türünden, yoksa uzantıdan: woff2 | woff | ttf | otf | eot | svg | unknown. */
    static String fontFormat(ScanItem it) {
        String ct = it.contentType == null ? "" : it.contentType.toLowerCase(Locale.ROOT);
        if (ct.contains("woff2")) return "woff2";
        if (ct.contains("woff")) return "woff";
        if (ct.contains("ttf") || ct.contains("truetype")) return "ttf";
        if (ct.contains("otf") || ct.contains("opentype")) return "otf";
        if (ct.contains("fontobject")) return "eot";
        String e = PageResourceDiscovery.ext(it.url);
        return switch (e) {
            case "woff2", "woff", "ttf", "otf", "eot", "svg" -> e;
            default -> "unknown";
        };
    }

    private static Map<String, Object> row(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }
}
