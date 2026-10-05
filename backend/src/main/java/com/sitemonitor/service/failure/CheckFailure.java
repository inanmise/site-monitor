package com.sitemonitor.service.failure;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Bir başarısız kontrolün sınıflandırma SONUCU: neden kodu ({@link CheckFailureReason}) + küçük, yapısal ayrıntı
 * (evre, hedef, yol, zaman aşımı, rcode, paket kaybı, istisna zinciri …). Ayrıntı kayda {@code failure_detail} kolonunda
 * kompakt JSON olarak yazılır ({@link #json()}, ≤ {@link #DETAIL_MAX} karakter) — arayüz metni koddan kendi dilinde kurar,
 * ayrıntı yalnız "kayıttaki değerler" ızgarasını besler.
 *
 * <p>Güvenlik: {@link #with} her metin değerini {@link CheckFailureClassifier#sanitize} ile geçirir (URL kullanıcı bilgisi,
 * hassas sorgu parametreleri, Basic/Bearer/Cookie değerleri maskelenir; tek satır, kırpılmış). Kimlik bilgisi, vekil
 * parolası ya da çerez bu nesneye HİÇ verilmez — çağıranlar yalnız host/port/IP/sayı gibi zararsız değerler ekler.
 *
 * <p>Hiçbir yöntem istisna fırlatmaz: sınıflandırma ÜST VERİDİR, kontrolün kendisini asla bozamaz.
 */
public final class CheckFailure {

    /** Sonuç haritasındaki anahtarlar — checker'lar yazar, kalıcılaştırma noktaları okur. */
    public static final String KEY_REASON = "failure_reason";
    public static final String KEY_DETAIL = "failure_detail";

    /** {@code failure_detail} tavanı (kolon TEXT; satır başına yük küçük kalsın). */
    public static final int DETAIL_MAX = 4000;
    /** Tek metin değerinin tavanı. */
    static final int VALUE_MAX = 300;
    /** Liste değerlerinin eleman tavanı (IP listesi, istisna zinciri). */
    static final int LIST_MAX = 8;

    private static final ObjectMapper JSON = new ObjectMapper();

    private final CheckFailureReason reason;
    private final Map<String, Object> detail = new LinkedHashMap<>();

    private CheckFailure(CheckFailureReason reason) {
        this.reason = reason == null ? CheckFailureReason.UNKNOWN : reason;
        detail.put("phase", this.reason.phase.name());
    }

    public static CheckFailure of(CheckFailureReason reason) {
        return new CheckFailure(reason);
    }

    public CheckFailureReason reason() { return reason; }

    public String code() { return reason.name(); }

    /** Ayrıntının salt-okunur görünümü (testler ve birleştirme için). */
    public Map<String, Object> detail() { return Collections.unmodifiableMap(detail); }

    /**
     * Ayrıntıya bir değer ekler (null / boş metin / boş liste atlanır). Metinler temizlenir, listeler {@link #LIST_MAX}
     * elemana kısaltılır. Zincirlenebilir; asla fırlatmaz.
     */
    public CheckFailure with(String key, Object value) {
        try {
            if (key == null || value == null) return this;
            if (value instanceof CharSequence cs) {
                String s = CheckFailureClassifier.sanitize(cs.toString(), VALUE_MAX);
                if (s != null && !s.isBlank()) detail.put(key, s);
            } else if (value instanceof Collection<?> c) {
                List<Object> out = new ArrayList<>();
                for (Object o : c) {
                    if (o == null) continue;
                    if (out.size() >= LIST_MAX) break;
                    out.add(o instanceof CharSequence s ? CheckFailureClassifier.sanitize(s.toString(), VALUE_MAX) : o);
                }
                if (!out.isEmpty()) detail.put(key, out);
            } else if (value instanceof Number || value instanceof Boolean) {
                detail.put(key, value);
            } else {
                String s = CheckFailureClassifier.sanitize(String.valueOf(value), VALUE_MAX);
                if (s != null && !s.isBlank()) detail.put(key, s);
            }
        } catch (Exception ignore) { /* üst veri — kontrolü asla bozmaz */ }
        return this;
    }

    /** Değer YOKSA ekler (checker'ın ayrıntısı çağıranın genel bağlamından önceliklidir). */
    public CheckFailure withIfAbsent(String key, Object value) {
        if (key != null && !detail.containsKey(key)) with(key, value);
        return this;
    }

    /**
     * Kompakt JSON (≤ {@link #DETAIL_MAX}). Taşarsa önce istisna zinciri, sonra ileti kısaltılır, en son yalnız sayısal /
     * kısa alanlar kalır. Serileştirme başarısızsa null (kayıt yine yazılır, yalnız ayrıntı düşer).
     */
    public String json() {
        try {
            Map<String, Object> m = new LinkedHashMap<>(detail);
            String s = JSON.writeValueAsString(m);
            if (s.length() <= DETAIL_MAX) return s;
            m.remove("cause_chain");
            s = JSON.writeValueAsString(m);
            if (s.length() <= DETAIL_MAX) return s;
            if (m.get("message") instanceof String msg && msg.length() > 120) m.put("message", msg.substring(0, 119) + "…");
            s = JSON.writeValueAsString(m);
            if (s.length() <= DETAIL_MAX) return s;
            Map<String, Object> slim = new LinkedHashMap<>();
            for (Map.Entry<String, Object> e : m.entrySet()) {
                Object v = e.getValue();
                if (v instanceof Number || v instanceof Boolean || (v instanceof String str && str.length() <= 80)) slim.put(e.getKey(), v);
            }
            s = JSON.writeValueAsString(slim);
            return s.length() <= DETAIL_MAX ? s : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** Sonuç haritasına {@code failure_reason} + {@code failure_detail} yazar. Asla fırlatmaz. */
    public void applyTo(Map<String, Object> result) {
        if (result == null) return;
        try {
            result.put(KEY_REASON, code());
            String j = json();
            if (j != null) result.put(KEY_DETAIL, j);
        } catch (Exception ignore) { /* değişmez harita vb. — kontrol sonucu olduğu gibi kalır */ }
    }

    // ── Kalıcılaştırma noktaları için güvenli okuyucular ─────────────────────────────────────────

    /** Sonuç haritasındaki neden kodu — yalnız katalogdaki bir kodsa (bilinmeyen değer kolona yazılmaz). */
    public static String reasonOf(Map<String, ?> result) {
        if (result == null) return null;
        Object v = result.get(KEY_REASON);
        if (!(v instanceof String s) || s.isBlank() || s.length() > CheckFailureReason.CODE_MAX) return null;
        return CheckFailureReason.CODES.contains(s) ? s : null;
    }

    /** Sonuç haritasındaki ayrıntı JSON'u (tavanlı); yoksa null. */
    public static String detailOf(Map<String, ?> result) {
        if (result == null) return null;
        Object v = result.get(KEY_DETAIL);
        if (!(v instanceof String s) || s.isBlank()) return null;
        return s.length() <= DETAIL_MAX ? s : null;
    }

    /**
     * Yanlışlıkla bir yanıt gövdesine düşerse (ör. bir kayıt bileşeni olarak) boş-bean hatası yerine küçük bir harita
     * serileşsin — sözleşme yine {@code failure_reason} + {@code failure_detail} metinleridir, bu yalnız emniyet.
     */
    @com.fasterxml.jackson.annotation.JsonValue
    public Map<String, Object> asJson() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(KEY_REASON, code());
        m.put("detail", detail());
        return m;
    }

    @Override
    public String toString() {
        return code() + " " + detail;
    }
}
