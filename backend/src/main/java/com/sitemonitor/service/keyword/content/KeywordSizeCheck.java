package com.sitemonitor.service.keyword.content;

import com.sitemonitor.config.GlobalExceptionHandler.FieldValidationException;
import com.sitemonitor.util.Msg;

/**
 * Sayfa boyutu eşiği (kw sözleşmesi §7.1, D1) — saf. {@code body_bytes} alınan ham bayttır (≤ 2 MB). {@code min_bytes} doluysa
 * ve bayt &lt; min → {@code BODY_TOO_SMALL}; {@code max_bytes} doluysa ve (bayt &gt; max YA DA gövde kırpıldı) →
 * {@code BODY_TOO_LARGE} (kırpılan gövde &gt; 2 MB ≥ max demektir). İstek tamamlanmadıysa değerlendirilmez ({@code size_ok}
 * null). İki eşik de boşsa {@code size_ok} null (kapalı).
 */
public final class KeywordSizeCheck {

    private KeywordSizeCheck() {}

    /** Eşik alt / üst sınırı (bayt). */
    public static final int MIN_LIMIT = 1;
    public static final int MAX_LIMIT = 2_000_000;
    /** Neden kodları (be1 {@code KeywordFailureClassifier.CODES}'ta tanımlı; adlar kalıcı). */
    public static final String BODY_TOO_SMALL = "BODY_TOO_SMALL";
    public static final String BODY_TOO_LARGE = "BODY_TOO_LARGE";

    /**
     * @param sizeOk {@code null} = değerlendirilmedi (kapalı / istek tamamlanmadı)
     * @param reason {@link #BODY_TOO_SMALL} | {@link #BODY_TOO_LARGE} | {@code null}
     * @param detail TR açıklama (yalnız ihlalde)
     */
    public record Result(Boolean sizeOk, String reason, String detail) {
        public static final Result OFF = new Result(null, null, null);
    }

    public static Result evaluate(Integer minBytes, Integer maxBytes, long bodyBytes, boolean truncated, boolean requestCompleted) {
        if (minBytes == null && maxBytes == null) return Result.OFF;
        if (!requestCompleted) return Result.OFF;
        if (minBytes != null && bodyBytes < minBytes) {
            return new Result(false, BODY_TOO_SMALL, "Sayfa " + bodyBytes + " bayt; en az " + minBytes + " bayt bekleniyor.");
        }
        if (maxBytes != null && (bodyBytes > maxBytes || truncated)) {
            return new Result(false, BODY_TOO_LARGE, truncated
                    ? "Sayfa okuma sınırını (" + bodyBytes + " bayt) aştı; en fazla " + maxBytes + " bayt bekleniyor."
                    : "Sayfa " + bodyBytes + " bayt; en fazla " + maxBytes + " bayt bekleniyor.");
        }
        return new Result(true, null, null);
    }

    /** Doğrulanmış eşik çifti. */
    public record Limits(Integer minBytes, Integer maxBytes) {}

    /**
     * İstek değerleri ({@code minBytes} / {@code maxBytes}): boş / null → kapalı; tam sayı {@value #MIN_LIMIT} –
     * {@value #MAX_LIMIT}; ikisi doluysa min ≤ max. Geçersiz → 400 alan hatası.
     */
    public static Limits validate(Object min, Object max) {
        Integer lo = parse(min, "minBytes");
        Integer hi = parse(max, "maxBytes");
        if (lo != null && hi != null && lo > hi) {
            throw new FieldValidationException("maxBytes", Msg.t("En büyük boyut en küçük boyuttan küçük olamaz.",
                    "The maximum size cannot be smaller than the minimum size."));
        }
        return new Limits(lo, hi);
    }

    /** Tek eşik doğrulaması ({@code field} = istek anahtarı). */
    public static Integer parse(Object raw, String field) {
        if (raw == null) return null;
        if (raw instanceof String s && s.isBlank()) return null;
        Long v = null;
        if (raw instanceof Number n) {
            double d = n.doubleValue();
            if (d == Math.rint(d) && !Double.isInfinite(d)) v = n.longValue();
        } else {
            try { v = Long.parseLong(raw.toString().trim()); } catch (NumberFormatException ignore) { /* aşağıda hata */ }
        }
        if (v == null || v < MIN_LIMIT || v > MAX_LIMIT) {
            throw new FieldValidationException(field, Msg.t("Boyut " + MIN_LIMIT + " ile " + MAX_LIMIT + " bayt arasında bir tam sayı olmalı.",
                    "The size must be a whole number between " + MIN_LIMIT + " and " + MAX_LIMIT + " bytes."));
        }
        return v.intValue();
    }
}
