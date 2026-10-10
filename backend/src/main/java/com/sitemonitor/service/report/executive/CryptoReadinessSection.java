package com.sitemonitor.service.report.executive;

import com.sitemonitor.service.crypto.CryptoClassifier;
import com.sitemonitor.service.crypto.CryptoInventoryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.sitemonitor.service.report.executive.SectionResult.*;

/**
 * (f) KRİPTO ENVANTERİ / PQC HAZIRLIĞI — RAPOR ANI fotoğrafı (kategori, PQC durumu, imza kalıntıları, geçiş öncelik
 * bantları ve öncelik listesinin başı).
 *
 * <h2>Kaynak</h2>
 * {@link CryptoInventoryService#summary(List, int)} KURUM GENELİ ({@code viewTeamIds = null} — yönetici özeti kurum geneli
 * bir rapordur; okuyabilen yalnız global görüntüleyicidir, ekranla posta aynı sayıyı görür). Servisin kendi belleğinden
 * gelir (≤ 120 sn); sınıflandırma ({@code CryptoClassifier}) ve öncelik ({@code PqcMigrationPriority}) burada YENİDEN
 * hesaplanmaz. Bu bölüm ek sorgu yapmaz.
 *
 * <h2>Paylar</h2>
 * Paydalar anahtarı OKUNAN uç noktalardır: kuantuma açık payı = VULNERABLE / (toplam − PQC durumu bilinmeyen); 2030 altı
 * payı = LEGACY / (toplam − kategorisi bilinmeyen). Veri olmayan kayıt payı sulandırmaz, ayrıca sayılır.
 *
 * <h2>Durum eşikleri</h2>
 * <ul>
 *   <li>{@code NO_DATA} — aktif kayıt yok ya da hiçbirinin anahtar bilgisi okunmadı;</li>
 *   <li>{@code CRITICAL} — bugün zayıf ({@code BROKEN}: MD5/SHA-1 imza, kısa anahtar ya da zayıf ara sertifika) en az bir
 *       uç nokta;</li>
 *   <li>{@code ATTENTION} — 2030 altı ({@code LEGACY}) payı ≥ %{@value #LEGACY_WARN_PCT};</li>
 *   <li>{@code OK} — aksi.</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CryptoReadinessSection implements ExecutiveSummarySection {

    public static final String KEY = "crypto-readiness";
    static final int ORDER = 60;
    static final String TITLE = "Kripto envanteri ve PQC hazırlığı";
    /** Öncelik listesinin başı (posta / PDF okunurluğu). */
    static final int TOP_N = 10;
    /** 2030 altı (LEGACY) payı bu yüzdeye ulaşırsa "takip gerekli". */
    static final int LEGACY_WARN_PCT = 25;
    static final List<String> BANDS = List.of("P1", "P2", "P3", "P4", "DONE");

    private final CryptoInventoryService crypto;

    @Override public String key() { return KEY; }
    @Override public int order() { return ORDER; }
    @Override public String title() { return TITLE; }

    @Override
    public SectionResult compute(ExecutiveSummaryContext ctx) {
        return evaluate(ctx, crypto.summary(null, TOP_N));
    }

    @SuppressWarnings("unchecked")
    SectionResult evaluate(ExecutiveSummaryContext ctx, Map<String, Object> s) {
        Map<String, Object> sum = s == null ? Map.of() : s;
        String asOf = sum.get("generated_at") instanceof String g && !g.isBlank() ? g : ctx.nowIso();
        SectionResult.Builder b = SectionResult.builder(KEY, ORDER, TITLE).snapshot(asOf).headlineKpi("broken");

        int total = intOf(sum.get("total"));
        int checked = intOf(sum.get("checked"));
        int unchecked = intOf(sum.get("unchecked"));
        Map<String, Object> byCat = map(sum.get("by_category"));
        Map<String, Object> byPqc = map(sum.get("by_pqc"));
        Map<String, Object> byBand = map(sum.get("by_band"));
        Map<String, Object> rem = map(sum.get("remnants"));

        int broken = intOf(byCat.get("BROKEN"));
        int legacy = intOf(byCat.get("LEGACY"));
        int pqcReady = intOf(byCat.get("PQC_READY"));
        int unknownCat = intOf(byCat.get("UNKNOWN"));
        int vulnerable = intOf(byPqc.get("VULNERABLE"));
        int knownPqc = Math.max(0, total - intOf(byPqc.get("UNKNOWN")));
        int knownCat = Math.max(0, total - unknownCat);
        int remnantAffected = intOf(rem.get("affected"));
        int remLeaf = intOf(rem.get("md5_leaf")) + intOf(rem.get("sha1_leaf"));
        int remInt = intOf(rem.get("md5_intermediate")) + intOf(rem.get("sha1_intermediate"));
        int legacyReissue = intOf(sum.get("legacy_reissue"));
        int p1 = intOf(byBand.get("P1")), p2 = intOf(byBand.get("P2")), p3 = intOf(byBand.get("P3")), p4 = intOf(byBand.get("P4"));
        int toMigrate = p1 + p2 + p3 + p4;
        Double vulnerableShare = knownPqc == 0 ? null : round1(100.0 * vulnerable / knownPqc);
        Double legacyShare = knownCat == 0 ? null : round1(100.0 * legacy / knownCat);
        boolean noData = total == 0 || checked == 0;

        // ── Durum + hükümler ──
        if (total == 0) {
            b.status(NO_DATA).verdict("NO_DATA", T_NEUTRAL, "Kripto envanterinde aktif kayıt yok.");
        } else if (checked == 0) {
            b.status(NO_DATA).verdict("NO_KEY_DATA", T_NEUTRAL, total
                    + " kaydın anahtar bilgisi henüz okunmadı (ilk sertifika kontrolü bekleniyor).", total);
        } else {
            boolean warn = legacyShare != null && legacyShare >= LEGACY_WARN_PCT;
            b.status(broken > 0 ? CRITICAL : warn ? ATTENTION : OK);
            if (broken > 0) {
                b.verdict("BROKEN", T_BAD, broken + " uç nokta bugün zayıf (MD5/SHA-1 imza, kısa anahtar ya da zayıf ara "
                        + "sertifika) — hemen değiştirilmeli.", broken);
            } else {
                b.verdict("NO_BROKEN", T_OK, "Bugün zayıf algoritma kullanan uç nokta yok.");
            }
            b.verdict("QUANTUM", T_NEUTRAL, "Anahtarı okunan " + knownPqc + " uç noktanın " + vulnerable
                    + " tanesi kuantuma açık (RSA / ECC); PQC ya da hibrit: " + pqcReady + ".", knownPqc, vulnerable, pqcReady);
            if (legacy > 0) {
                b.verdict("LEGACY", warn ? T_WARN : T_NEUTRAL, legacy + " uç nokta 2030 altı anahtar kullanıyor (RSA 2048–3071 "
                        + "/ DSA, pay " + ExecFormat.pct(legacyShare, 1) + "); " + legacyReissue
                        + " tanesinin bitişi 2030 sonunu aşıyor.", legacy, pct(legacyShare), legacyReissue);
            }
            if (remnantAffected > 0) {
                b.verdict("REMNANTS", T_BAD, remnantAffected + " uç noktada SHA-1 / MD5 imza kalıntısı var (" + remLeaf
                        + " yaprak, " + remInt + " ara sertifika).", remnantAffected, remLeaf, remInt);
            }
            if (p1 > 0) {
                b.verdict("P1", T_WARN, "Geçiş önceliği P1 (şimdi) bandında " + p1 + " uç nokta var.", p1);
            }
        }

        // ── Göstergeler ──
        b.kpi(new Kpi("broken", "Bugün zayıf", noData ? null : Integer.valueOf(broken), "int", noData ? T_NEUTRAL : broken > 0 ? T_BAD : T_OK,
                "MD5/SHA-1 imza ya da kısa anahtar", List.of(), null, null, null));
        b.kpi(new Kpi("vulnerable_share", "Kuantuma açık", vulnerableShare, "pct", T_NEUTRAL,
                knownPqc + " uç noktanın " + vulnerable + " tanesi · " + pqcReady + " PQC / hibrit",
                List.of(knownPqc, vulnerable, pqcReady), null, null, null));
        b.kpi(new Kpi("legacy", "2030 altı", noData ? null : Integer.valueOf(legacy), "int",
                legacyShare != null && legacyShare >= LEGACY_WARN_PCT ? T_WARN : T_NEUTRAL,
                legacyReissue + " tanesinin bitişi 2030 sonunu aşıyor", List.of(legacyReissue), null, null, null));
        b.kpi(new Kpi("remnants", "SHA-1 / MD5 kalıntısı", noData ? null : Integer.valueOf(remnantAffected), "int",
                noData ? T_NEUTRAL : remnantAffected > 0 ? T_BAD : T_OK,
                remLeaf + " yaprak · " + remInt + " ara sertifika", List.of(remLeaf, remInt), null, null, null));
        b.kpi(new Kpi("p1", "Öncelik P1 (şimdi)", noData ? null : Integer.valueOf(p1), "int", noData ? T_NEUTRAL : p1 > 0 ? T_WARN : T_OK,
                "P2 " + p2 + " · P3 " + p3 + " · P4 " + p4, List.of(p2, p3, p4), null, null, null));
        b.kpi(new Kpi("unknown", "Veri yok", unknownCat, "int", unknownCat > 0 ? T_WARN : T_NEUTRAL,
                unchecked + " kayıt henüz kontrol edilmedi", List.of(unchecked), null, null, null));

        // ── Tablolar ──
        List<Map<String, Object>> catRows = new ArrayList<>();
        for (CryptoClassifier.Category c : CryptoClassifier.Category.values()) {
            int n = intOf(byCat.get(c.name()));
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("category", c.name());
            r.put("endpoints", n);
            r.put("share", total == 0 ? 0.0 : round1(100.0 * n / total));
            r.put("state", categoryTone(c.name(), n));
            catRows.add(r);
        }
        b.table(new Table("categories", "Geçiş kategorisine göre", List.of(
                new Column("category", "Kategori", "crypto_category"),
                new Column("endpoints", "Uç nokta", "int"),
                new Column("share", "Pay", "pct"),
                new Column("state", "Durum", "status")),
                total == 0 ? List.of() : catRows, total == 0 ? 0 : catRows.size(), "Kripto envanterinde kayıt yok."));

        List<Map<String, Object>> bandRows = new ArrayList<>();
        for (String band : BANDS) {
            int n = intOf(byBand.get(band));
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("band", band);
            r.put("endpoints", n);
            r.put("share", total == 0 ? 0.0 : round1(100.0 * n / total));
            bandRows.add(r);
        }
        b.table(new Table("bands", "Geçiş öncelik bandına göre", List.of(
                new Column("band", "Öncelik", "pqc_band"),
                new Column("endpoints", "Uç nokta", "int"),
                new Column("share", "Pay", "pct")),
                total == 0 ? List.of() : bandRows, total == 0 ? 0 : bandRows.size(), "Kripto envanterinde kayıt yok."));

        List<Map<String, Object>> topRows = new ArrayList<>();
        Object topRaw = sum.get("top");
        if (topRaw instanceof List<?> list) {
            for (Object o : list) {
                if (!(o instanceof Map<?, ?> m) || topRows.size() >= TOP_N) continue;
                Map<String, Object> t = (Map<String, Object>) m;
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("domain", t.get("domain"));
                r.put("team", t.get("team_name"));
                r.put("team_id", t.get("team_id"));
                r.put("tier", t.get("tier"));
                r.put("category", t.get("category"));
                r.put("band", t.get("band"));
                r.put("score", t.get("score"));
                r.put("days", t.get("days_remaining"));
                topRows.add(r);
            }
        }
        b.table(new Table("migration", "Öncelikli geçiş listesi", List.of(
                new Column("domain", "Sertifika", "text"),
                new Column("team", "Takım", "team"),
                new Column("tier", "Seviye", "tier"),
                new Column("category", "Kategori", "crypto_category"),
                new Column("band", "Öncelik", "pqc_band"),
                new Column("score", "Puan", "int"),
                new Column("days", "Kalan", "days")),
                topRows, Math.max(toMigrate, topRows.size()), "Geçiş bekleyen uç nokta yok."));

        // ── Notlar ──
        b.note("ASOF", "Rapor anı fotoğrafı: " + stampOf(asOf) + " itibarıyla kurum geneli — aktif envanterin son kontrolleri "
                + "ve dosyadan yüklenen sertifikalar.", datetime(asOf));
        b.note("METHOD", "Kategori ve öncelik Kripto Envanteri'nin kuralıyladır (NIST SP 800-131A: RSA 2048 2030 sonunda "
                + "emekli; puan = maruziyet + bugünkü güç + yenileme penceresi + kayıtlı trafik riski). Paylar anahtarı okunan "
                + "uç noktalar üzerinden hesaplanır.");
        b.note("KEX", "TLS anahtar değişimi grubu saklanmadığından hibrit anahtar değişimi (ör. X25519MLKEM768) gözlenemez; "
                + "PQC hazırlığı yalnız sertifika anahtarı ve imzasına göredir.");
        Map<String, Object> catData = new LinkedHashMap<>();
        for (CryptoClassifier.Category c : CryptoClassifier.Category.values()) catData.put(c.name(), intOf(byCat.get(c.name())));
        Map<String, Object> bandData = new LinkedHashMap<>();
        for (String band : BANDS) bandData.put(band, intOf(byBand.get(band)));
        b.data("by_category", catData).data("by_band", bandData).data("total", total).data("checked", checked)
                .data("vulnerable_share", vulnerableShare).data("legacy_share", legacyShare);
        return b.build();
    }

    /** Kategori satırının durum tonu (sayı sıfırsa nötr). */
    static String categoryTone(String category, int n) {
        if (n == 0) return T_NEUTRAL;
        return switch (category) {
            case "BROKEN" -> T_BAD;
            case "LEGACY" -> T_WARN;
            case "MODERN", "PQC_READY" -> T_OK;
            default -> T_NEUTRAL;
        };
    }

    private static int intOf(Object v) {
        return v instanceof Number n ? n.intValue() : 0;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object v) {
        return v instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    private static String stampOf(String utcIso) {
        java.time.Instant t = AlarmNoiseSection.parse(utcIso);
        return t == null ? utcIso : ExecFormat.stamp(t);
    }

    static double round1(double v) { return Math.round(v * 10) / 10.0; }
}
