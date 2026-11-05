package com.sitemonitor.service.keyword.rules;

import com.sitemonitor.service.keyword.KeywordFold;

import java.util.Locale;

/**
 * Anahtar kelime izlemesinin TEK kuralı (kw sözleşmesi §6.1, 2026-11-05) — değişmez, doğrulanmış değer. Ham biçim
 * ({@code rules_json} ve API {@code rules}) {@link KeywordRules} ile okunur / yazılır; burada yalnız anlam durur.
 *
 * <p><b>Koşul SAĞLIKLI koşuldur</b> (bugünkü tek kelime anlamı): {@code operator}/{@code count} = uyarı eşiği ("en az 1 kez
 * geçmeli"); {@code critical} isteğe bağlı İKİNCİ sağlıklı koşul (kritik eşik). Örnek "≥ 5 hata satırı uyarı, ≥ 20 kritik" =
 * {@code LT 5} + kritik {@code LT 20}. Arayüz bunu alarm eşiği olarak gösterebilir; saklama hep sağlıklı koşul.
 *
 * @param id            {@code [a-z0-9-]{1,20}}, liste içinde tekil
 * @param kind          {@link Kind#TEXT} (düz metin, Türkçe katlamalı) ya da {@link Kind#REGEX} (RE2J — {@link SafeRegex})
 * @param pattern       TEXT ≤ 255, REGEX ≤ 200 karakter; boş değil, satır sonu yok
 * @param caseSensitive büyük/küçük harf duyarlı mı (varsayılan duyarsız — iki taraf {@link KeywordFold} ile katlanır)
 * @param operator      sağlıklı koşulun işleci
 * @param count         sağlıklı koşulun eşiği (0 – tavan)
 * @param critical      isteğe bağlı kritik sağlıklı koşul (aynı yön ailesi, daha geniş sınır); {@code null} = yok
 * @param scope         arama bölgesi; {@code null} = sayfa ({@link ScopeType#PAGE})
 * @param label         gösterim etiketi (≤ 80) ya da {@code null}
 */
public record KeywordRule(String id, Kind kind, String pattern, boolean caseSensitive, Op operator, int count,
                          Threshold critical, Scope scope, String label) {

    /** Kural türü. Adlar kalıcıdır ({@code rules_json}). */
    public enum Kind { TEXT, REGEX }

    /** Arama bölgesi türü. Adlar kalıcıdır. */
    public enum ScopeType { PAGE, CSS, JSON }

    /**
     * Adet koşulu işleci — SAĞLIKLI = (gözlenen adet) [işleç] (eşik). {@code KeywordCheckerService.evaluate} ile birebir aynı
     * (eşlik testi {@code KeywordRulesTest.opParityWithLegacy}).
     */
    public enum Op {
        GTE, LTE, EQ, GT, LT;

        /** Sağlıklı mı. */
        public boolean healthy(int observed, int n) {
            return switch (this) {
                case LTE -> observed <= n;
                case EQ  -> observed == n;
                case GT  -> observed >  n;
                case LT  -> observed <  n;
                case GTE -> observed >= n;
            };
        }

        /** Üst sınır ailesi ("az olmalı"): LTE, LT. */
        public boolean upper() { return this == LTE || this == LT; }

        /** Alt sınır ailesi ("çok olmalı"): GTE, GT. */
        public boolean lower() { return this == GTE || this == GT; }

        /** Etkin sınır (tam sayı): LTE n → n, LT n → n-1, GTE n → n, GT n → n+1, EQ n → n. */
        public long bound(int n) {
            return switch (this) {
                case LT -> (long) n - 1;
                case GT -> (long) n + 1;
                default -> n;
            };
        }

        /** Türkçe ifade ("en az 3 kez") — {@code KeywordCheckerService.opPhrase} ile AYNI biçim. */
        public String phraseTr(int n) {
            return switch (this) {
                case LTE -> "en fazla " + n + " kez";
                case EQ  -> "tam olarak " + n + " kez";
                case GT  -> n + " kezden fazla";
                case LT  -> n + " kezden az";
                case GTE -> "en az " + n + " kez";
            };
        }

        /** Ham değer → işleç; tanınmayan / boş → {@code null} (çağıran kendi varsayılanını seçer). */
        public static Op parse(Object raw) {
            if (raw == null) return null;
            String s = raw.toString().trim().toUpperCase(Locale.ROOT);
            for (Op o : values()) if (o.name().equals(s)) return o;
            return null;
        }
    }

    /** Kritik (ikinci) sağlıklı koşul. */
    public record Threshold(Op operator, int count) {}

    /** Arama bölgesi: {@code value} CSS seçici ya da JSON yolu (PAGE'de {@code null}). */
    public record Scope(ScopeType type, String value) {
        public static final Scope PAGE = new Scope(ScopeType.PAGE, null);
    }

    /** Etkin bölge türü ({@code scope == null} → PAGE). */
    public ScopeType scopeType() {
        return scope == null || scope.type() == null ? ScopeType.PAGE : scope.type();
    }

    /** Etkin bölge değeri (PAGE'de {@code null}). */
    public String scopeValue() {
        return scopeType() == ScopeType.PAGE ? null : scope.value();
    }

    /** Sayım tavanı: TEXT {@link KeywordFold#MAX_COUNT}, REGEX {@link SafeRegex#MAX_COUNT}. */
    public int cap() {
        return kind == Kind.REGEX ? SafeRegex.MAX_COUNT : KeywordFold.MAX_COUNT;
    }

    /**
     * "Hiç geçmemeli" kuralı mı (B12): {@code LTE 0}, {@code EQ 0} ya da {@code LT 1}. Eski {@code alert_condition}
     * (CONTAINS) türetimi ve "İstenmeyen ifade bulundu" başlığı bunu kullanır.
     */
    public boolean absenceRule() {
        return absence(operator, count);
    }

    /** {@link #absenceRule()} kuralının ham hâli (eski tek alan yolu için de). */
    public static boolean absence(Op op, int n) {
        if (op == null) return false;
        return ((op == Op.LTE || op == Op.EQ) && n == 0) || (op == Op.LT && n == 1);
    }

    /** Basit kural mı: düz metin, sayfa bölgesi, kritik eşik ve etiket YOK (eski tek kelime alanlarına sığar). */
    public boolean simple() {
        return kind == Kind.TEXT && scopeType() == ScopeType.PAGE && critical == null
                && (label == null || label.isBlank());
    }
}
