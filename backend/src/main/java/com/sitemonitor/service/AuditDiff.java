package com.sitemonitor.service;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Denetim before/after diff üretici + hassas-alan maskeleyici. Çıktı: {@code {"alan":{"from":x,"to":y}}} JSON.
 * Yalnız DEĞİŞEN alanlar yazılır; fark yoksa {@code null}. Parola/token/secret/apiKey içeren anahtarların
 * from/to değerleri ASLA düz metin taşımaz ({@code ***} maskelenir) — kurcalanamazlık + gizlilik.
 *
 * <p><b>Değer sertleştirmesi (2026-09).</b> Anahtar adına bakan maskeleme TEK BAŞINA yetmiyordu; üç
 * boşluk vardı ve üçü de ayar uçlarına diff açıldığı anda gerçek bir sızıntıya/şişmeye dönüşürdü:
 * <ul>
 *   <li><b>Adı masum, değeri kimlik bilgisi.</b> {@code site.monitor.userpush.webhook-url} içinde
 *       {@code url} geçiyor, kara listede {@code url} yok → webhook adresi (yol parçasında token
 *       taşır) düz metin yazılırdı. Artık değer GÖVDESİ de temizleniyor ({@code //user:pass@},
 *       {@code ?token=…}).</li>
 *   <li><b>İkili içerik.</b> {@code logo-data} yüzlerce KB base64 — {@code changes} sütununa akıp
 *       satırı da, ekranı da boğardı. Artık {@code {"bytes":n}} olarak daralıyor.</li>
 *   <li><b>Uzun değer.</b> Tavan {@link #MAX_VALUE_LEN}; aşan değer görünür biçimde kırpılır
 *       (sessiz kesme, denetimde "değer buydu" yanılgısı üretir).</li>
 * </ul>
 * Sertleştirme {@link #diff} ve {@link #snapshotJson}'ın İÇİNE konuldu (ayrı bir {@code diffMasked}
 * adı yerine): 20 mevcut çağrı yerinin hepsi tek satır değişiklik olmadan korunsun, ve "hangisini
 * çağırmalıyım" sorusu hiç doğmasın. Hash yalnız SAKLANAN metinden hesaplandığı için eski satırlar
 * etkilenmez, zincir kırılmaz.
 */
public final class AuditDiff {

    private AuditDiff() {}

    public static final String MASK = "***";

    /** Tek bir değerin denetim çıktısındaki tavanı; aşan değer görünür biçimde kırpılır. */
    public static final int MAX_VALUE_LEN = 512;

    /** Kırpılmamış hâlin baş kısmı — kuyruk atılırken okunabilir bir bağlam kalsın. */
    private static final int TRUNCATED_HEAD = 200;

    /** Bir koleksiyon değerinde yazılacak en fazla öğe; fazlası tek bir "+N daha" öğesiyle özetlenir. */
    static final int MAX_LIST_ITEMS = 20;

    /** Değeri ikili/gömülü varlık olan anahtarlar — içerik değil BOYUT yazılır. */
    private static final java.util.regex.Pattern BINARY_KEY = java.util.regex.Pattern.compile(
            "(?i)(^|[._-])(logo|icon|favicon|image|avatar)([._-]?data)?$|[._-]data$");

    /** İki durum haritasını karşılaştırır; yalnız değişen alanları JSON diff olarak döner. Fark yoksa {@code null}. */
    public static String diff(Map<String, Object> before, Map<String, Object> after) {
        Map<String, Object> b = before != null ? before : Map.of();
        Map<String, Object> a = after != null ? after : Map.of();
        Set<String> keys = new LinkedHashSet<>();
        keys.addAll(b.keySet());
        keys.addAll(a.keySet());

        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (String k : keys) {
            Object from = b.get(k), to = a.get(k);
            if (Objects.equals(norm(from), norm(to))) continue;   // değişmemiş → atla
            if (!first) sb.append(",");
            first = false;
            sb.append(jsonStr(k)).append(":{\"from\":")
              .append(safeVal(k, from))
              .append(",\"to\":")
              .append(safeVal(k, to))
              .append("}");
        }
        sb.append("}");
        return first ? null : sb.toString();
    }

    /** Hassaslık kararı merkezî {@link SecretMask}'ten alınır (tek kara-liste); audit çıktısı {@link #MASK} kullanır. */
    public static boolean isSensitive(String key) {
        return SecretMask.isSensitive(key);
    }

    /** Bir entity'nin verilen alanlarını (getter/isX ile) haritaya çeker — güncelleme öncesi/sonrası snapshot. */
    public static java.util.Map<String, Object> snapshot(Object bean, String... fields) {
        java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
        if (bean == null) return m;
        for (String f : fields) {
            String cap = Character.toUpperCase(f.charAt(0)) + f.substring(1);
            Object v = invokeGetter(bean, "get" + cap);
            if (v == null) v = invokeGetter(bean, "is" + cap);
            // Koleksiyonu KOPYALA — before snapshot sonradan yerinde mutasyona uğramasın (üyelik değişimi kaçmasın).
            if (v instanceof java.util.Collection<?> c) v = new java.util.ArrayList<>(c);
            m.put(f, v);
        }
        return m;
    }

    private static Object invokeGetter(Object bean, String getter) {
        try {
            return bean.getClass().getMethod(getter).invoke(bean);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Bir snapshot haritasını düz JSON nesnesine çevirir ({@code {"alan":deger,…}}) — hassas
     * alanlar yine {@link #MASK}'lenir.
     *
     * <p>Neden burada: maskeleme kara-listesi ve JSON kaçışı zaten bu sınıfın içinde. Snapshot'ı
     * başka bir yerde Jackson ile yazmak, "hangi alan hassas" kararını İKİNCİ bir yere kopyalardı
     * ve o kopya kaçınılmaz olarak ayrışırdı — parolayı düz metin yazan taraf da o olurdu.
     *
     * @return JSON nesnesi; {@code null}/boş haritada {@code null}
     */
    public static String snapshotJson(Map<String, Object> snapshot) {
        if (snapshot == null || snapshot.isEmpty()) return null;
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> e : snapshot.entrySet()) {
            if (!first) sb.append(",");
            first = false;
            sb.append(jsonStr(e.getKey())).append(":")
              .append(safeVal(e.getKey(), e.getValue()));
        }
        return sb.append("}").toString();
    }

    /** Tek bir değeri anahtar adına göre maskeler (serbest detay üretiminde). */
    public static String maskValue(String key, Object value) {
        return isSensitive(key) ? MASK : String.valueOf(value);
    }

    private static String norm(Object o) { return o == null ? null : String.valueOf(o); }

    /**
     * Bir değeri denetime yazılabilir JSON parçasına çevirir — sınıf başındaki üç sertleştirme
     * kuralı burada uygulanır. {@link AuditDetail} de aynı yolu kullanır: "hangi değer nasıl
     * yazılır" kararının İKİ kopyası olmaz.
     *
     * <p>Sıra önemlidir: önce anahtar-adı maskesi (en güçlü kural), sonra ikili daraltma (uzunluk
     * kırpma base64'ü yine de yazardı), sonra gövde temizleme, en sonda uzunluk tavanı.
     */
    static String safeVal(String key, Object value) {
        if (isSensitive(key)) return jsonStr(MASK);
        if (value == null) return "null";
        if (value instanceof Number || value instanceof Boolean) return value.toString();

        if (value instanceof java.util.Map<?, ?> m) return mapVal(m);
        if (value instanceof java.util.Collection<?> c) return listVal(key, c);
        if (value.getClass().isArray()) return listVal(key, java.util.Arrays.asList((Object[]) value));

        String s = String.valueOf(value);
        if (isBinary(key, s)) return "{\"bytes\":" + s.length() + "}";
        s = scrubCredentials(s);
        if (s.length() > MAX_VALUE_LEN) {
            s = s.substring(0, TRUNCATED_HEAD) + "…(+" + (s.length() - TRUNCATED_HEAD) + ")";
        }
        return jsonStr(s);
    }

    /** Anahtar ikili varlık mı, ya da değer bir data-URI mi (ad bilgi vermese de içerik ele veriyor). */
    private static boolean isBinary(String key, String value) {
        return (key != null && BINARY_KEY.matcher(key).find()) || value.startsWith("data:");
    }

    /**
     * Değer GÖVDESİNDEKİ kimlik bilgilerini temizler. Yalnız URL/query görünümlü metinlere dokunur:
     * {@code SecretMask.maskJdbcUrl} boş girdide {@code "(ayarsız)"} döndürdüğü için her metne
     * uygulanamaz — sıradan bir boş alan denetimde "(ayarsız)" diye görünürdü.
     */
    private static String scrubCredentials(String s) {
        if (s == null || s.isBlank()) return s;
        String out = s;
        if (out.contains("://")) out = SecretMask.maskJdbcUrl(out);
        if (out.indexOf('?') >= 0 || out.indexOf('&') >= 0) out = SecretMask.maskUrlQuery(out);
        return out;
    }

    /** Koleksiyon → JSON dizi; {@link #MAX_LIST_ITEMS} üstü tek bir "+N daha" öğesiyle GÖRÜNÜR biçimde özetlenir. */
    private static String listVal(String key, java.util.Collection<?> c) {
        StringBuilder sb = new StringBuilder("[");
        int i = 0;
        for (Object o : c) {
            if (i == MAX_LIST_ITEMS) {
                sb.append(i > 0 ? "," : "").append(jsonStr("+" + (c.size() - MAX_LIST_ITEMS) + " daha"));
                break;
            }
            if (i > 0) sb.append(",");
            sb.append(safeVal(key, o));
            i++;
        }
        return sb.append("]").toString();
    }

    /** İç içe harita → JSON nesnesi (anahtar başına maskeleme yine geçerli). */
    private static String mapVal(java.util.Map<?, ?> m) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<?, ?> e : m.entrySet()) {
            if (!first) sb.append(",");
            first = false;
            String k = String.valueOf(e.getKey());
            sb.append(jsonStr(k)).append(":").append(safeVal(k, e.getValue()));
        }
        return sb.append("}").toString();
    }

    static String jsonVal(Object o) {
        if (o == null) return "null";
        if (o instanceof Number || o instanceof Boolean) return o.toString();
        return jsonStr(String.valueOf(o));
    }

    static String jsonStr(String s) {
        StringBuilder b = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"'  -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default   -> { if (c < 0x20) b.append(String.format("\\u%04x", (int) c)); else b.append(c); }
            }
        }
        return b.append('"').toString();
    }
}
