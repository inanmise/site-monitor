package com.sitemonitor.service.crypto;

import com.sitemonitor.service.crypto.CryptoClassifier.Category;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Kuantum sonrası (PQC) geçiş ÖNCELİĞİ — TEK kaynak (2026-10-10). Puan 0–100, dört bileşenin toplamı; ön yüz kuralı
 * kopyalamaz, {@link #ruleTable()} ile sunucudan aldığı tabloyu çizer ve satırdaki bileşenleri gösterir.
 *
 * <table>
 *   <caption>Bileşenler</caption>
 *   <tr><th>Bileşen</th><th>Ne ölçer</th><th>Puan</th></tr>
 *   <tr><td>exposure (≤40)</td><td>Maruziyet / etki alanı — envanter katmanı. Katman 1 müşteriye açık üretimdir:
 *       "şimdi topla, sonra çöz" (harvest-now-decrypt-later) saldırısının ve uzun ömürlü müşteri verisinin ilk
 *       hedefi.</td><td>T1 40 · T2 25 · T3 10 · T4 5 · katmansız 15 (sınıflandırılmamış kayıt gömülmesin)</td></tr>
 *   <tr><td>strength (≤30)</td><td>Bugünkü anahtar/imza gücü — geçiş kategorisi
 *       ({@link CryptoClassifier#category}).</td><td>BROKEN 30 · LEGACY 20 · UNKNOWN 15 · MODERN 10 · PQC_READY 0</td></tr>
 *   <tr><td>renewal (≤20)</td><td>Yenileme penceresi — yenileme geçişin DOĞAL ve en ucuz anıdır: yakında dolacak
 *       sertifika yeni anahtar/algoritmayla düzenlenir.</td><td>≤30 gün (dolmuş dahil) 20 · ≤90 15 · ≤180 10 ·
 *       ≤365 5 · daha uzun / bilinmiyor 0</td></tr>
 *   <tr><td>hndl (≤10)</td><td>Kayıtlı trafik riski — yalnız ağ uç noktaları: iç sertifika işaretli DEĞİL (dışarıdan
 *       erişilebilir olası) +5; ileriye dönük gizlilik YOK (tek anahtar kırılınca kayıtlı TÜM oturumlar açılır) +5.
 *       Yüklenen sertifika (anahtar deposu) 0.</td><td>0–10</td></tr>
 * </table>
 *
 * <p>{@code PQC_READY} satırın puanı 0, bandı {@code DONE}. Bant: ≥70 P1 (şimdi) · ≥50 P2 (sıradaki) · ≥30 P3
 * (planlı) · altı P4 (izle). Sıra: puan ↓, kalan gün ↑ (bilinmeyen sonda), katman ↑ (katmansız sonda), alan adı ↑.
 */
public final class PqcMigrationPriority {

    private PqcMigrationPriority() {}

    public static final int TIER1 = 40, TIER2 = 25, TIER3 = 10, TIER4 = 5, TIER_NONE = 15;
    public static final int STRENGTH_BROKEN = 30, STRENGTH_LEGACY = 20, STRENGTH_UNKNOWN = 15, STRENGTH_MODERN = 10;
    /** Yenileme penceresi: {gün üst sınırı, puan} — sıra önemlidir (ilk eşleşen). */
    public static final int[][] RENEWAL_STEPS = {{30, 20}, {90, 15}, {180, 10}, {365, 5}};
    public static final int HNDL_EXTERNAL = 5, HNDL_NO_PFS = 5;
    public static final int BAND_P1 = 70, BAND_P2 = 50, BAND_P3 = 30;

    /**
     * @param tier       envanter katmanı (1–4) ya da null
     * @param category   geçiş kategorisi
     * @param daysLeft   sertifikanın kalan günü (negatif = dolmuş) ya da null
     * @param network    ağ uç noktası mı (false = yüklenen sertifika / anahtar deposu)
     * @param internal   envanterde "iç sertifika" işaretli mi (null = işaretsiz)
     * @param pfs        ileriye dönük gizlilik: true var, false yok, null bilinmiyor / TLS yok
     */
    public record Input(Integer tier, Category category, Integer daysLeft, boolean network, Boolean internal, Boolean pfs) {}

    public record Score(int score, String band, int exposure, int strength, int renewal, int hndl) {
        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("score", score); m.put("band", band);
            m.put("exposure", exposure); m.put("strength", strength); m.put("renewal", renewal); m.put("hndl", hndl);
            return m;
        }
    }

    public static Score score(Input in) {
        if (in.category() == Category.PQC_READY) return new Score(0, "DONE", 0, 0, 0, 0);
        int exposure = exposure(in.tier());
        int strength = switch (in.category() == null ? Category.UNKNOWN : in.category()) {
            case BROKEN -> STRENGTH_BROKEN;
            case LEGACY -> STRENGTH_LEGACY;
            case MODERN -> STRENGTH_MODERN;
            default -> STRENGTH_UNKNOWN;
        };
        int renewal = renewal(in.daysLeft());
        int hndl = 0;
        if (in.network()) {
            if (!Boolean.TRUE.equals(in.internal())) hndl += HNDL_EXTERNAL;
            if (Boolean.FALSE.equals(in.pfs())) hndl += HNDL_NO_PFS;
        }
        int total = Math.max(0, Math.min(100, exposure + strength + renewal + hndl));
        return new Score(total, band(total), exposure, strength, renewal, hndl);
    }

    static int exposure(Integer tier) {
        if (tier == null) return TIER_NONE;
        return switch (tier) { case 1 -> TIER1; case 2 -> TIER2; case 3 -> TIER3; case 4 -> TIER4; default -> TIER_NONE; };
    }

    static int renewal(Integer daysLeft) {
        if (daysLeft == null) return 0;
        for (int[] step : RENEWAL_STEPS) if (daysLeft <= step[0]) return step[1];
        return 0;
    }

    public static String band(int score) {
        if (score >= BAND_P1) return "P1";
        if (score >= BAND_P2) return "P2";
        if (score >= BAND_P3) return "P3";
        return "P4";
    }

    /** Arayüzün "puan nasıl hesaplanır" tablosu — sabitlerin kendisinden üretilir (ikinci bir kopya yok). */
    public static Map<String, Object> ruleTable() {
        Map<String, Object> m = new LinkedHashMap<>();
        Map<String, Object> exposure = new LinkedHashMap<>();
        exposure.put("1", TIER1); exposure.put("2", TIER2); exposure.put("3", TIER3); exposure.put("4", TIER4);
        exposure.put("none", TIER_NONE);
        m.put("exposure", exposure);
        Map<String, Object> strength = new LinkedHashMap<>();
        strength.put(Category.BROKEN.name(), STRENGTH_BROKEN); strength.put(Category.LEGACY.name(), STRENGTH_LEGACY);
        strength.put(Category.UNKNOWN.name(), STRENGTH_UNKNOWN); strength.put(Category.MODERN.name(), STRENGTH_MODERN);
        strength.put(Category.PQC_READY.name(), 0);
        m.put("strength", strength);
        m.put("renewal", java.util.Arrays.stream(RENEWAL_STEPS)
                .map(s -> Map.<String, Object>of("max_days", s[0], "points", s[1])).toList());
        m.put("hndl", Map.of("external", HNDL_EXTERNAL, "no_pfs", HNDL_NO_PFS));
        m.put("bands", List.of(Map.of("band", "P1", "min", BAND_P1), Map.of("band", "P2", "min", BAND_P2),
                Map.of("band", "P3", "min", BAND_P3), Map.of("band", "P4", "min", 0)));
        m.put("max", 100);
        return m;
    }
}
