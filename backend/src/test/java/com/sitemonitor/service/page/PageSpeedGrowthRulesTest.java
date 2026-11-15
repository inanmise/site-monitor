package com.sitemonitor.service.page;

import com.sitemonitor.model.PageSpeedMonitor;
import com.sitemonitor.service.page.PageSpeedRules.Growth;
import com.sitemonitor.service.page.PageSpeedRules.GrowthSample;
import com.sitemonitor.service.page.PageSpeedRules.Measurement;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Sayfa Hızı 2c — büyüme alarmı kuralları (2026-11-15, madde 13). Önceki 7 günün TAM zamanlanmış ölçümlerinin medyanına
 * göre büyüme yüzdesi ve isteğe bağlı öğrenilen bayt bandı. Kurallar saf; DB / ağ yok.
 *
 * <p>Hatalar sessizdir: eşik yönü kayarsa her ölçümde alarm ya da hiç alarm; az örnekle karar verilirse yeni izlemeler ilk
 * günlerinde sahte alarm açar; alt sınır değeri "eşik içinde" sayılırsa açık alarm kanıtsız kapanır.
 */
class PageSpeedGrowthRulesTest {

    private static PageSpeedMonitor growth(Integer bytesPct, Integer reqPct, Boolean band) {
        PageSpeedMonitor m = new PageSpeedMonitor();
        m.setGrowthBytesPct(bytesPct);
        m.setGrowthRequestsPct(reqPct);
        m.setGrowthBytesBand(band);
        return m;
    }

    private static List<GrowthSample> samples(int n, long bytes, int requests) {
        List<GrowthSample> out = new ArrayList<>();
        for (int i = 0; i < n; i++) out.add(new GrowthSample(bytes, requests, false));
        return out;
    }

    private static Measurement measure(long bytes, int requests, boolean lowerBound, Growth g) {
        return new Measurement(1000, 100, bytes, requests, null, null, null, null, lowerBound, g);
    }

    @Test
    @DisplayName("Anahtarlar SONA eklendi: önceki dokuz anahtarın sırası değişmedi; birleşik liste kolona (120) sığar")
    void keysAppendedAndFitColumn() {
        assertThat(PageSpeedRules.BREACH_KEYS.subList(0, 9))
                .containsExactly("LOAD", "TTFB", "SIZE", "REQUESTS", "MINSIZE", "JS", "IMG", "FONT", "THIRD");
        assertThat(PageSpeedRules.BREACH_KEYS.subList(9, 12)).containsExactly("GROWSIZE", "GROWREQ", "SIZEBAND");
        assertThat(PageSpeedRules.joinBreaches(PageSpeedRules.BREACH_KEYS).length()).isLessThanOrEqualTo(120);
    }

    @Test
    @DisplayName("Medyan: tek sayıda örnekte ortadaki, çiftte alttaki orta; boş liste referans değil")
    void medianRule() {
        Growth odd = PageSpeedRules.growthOf(List.of(new GrowthSample(300, 3, false),
                new GrowthSample(100, 1, false), new GrowthSample(200, 2, false)));
        assertThat(odd.medianBytes()).isEqualTo(200L);
        assertThat(odd.medianRequests()).isEqualTo(2);
        Growth even = PageSpeedRules.growthOf(List.of(new GrowthSample(400, 4, false), new GrowthSample(100, 1, false),
                new GrowthSample(300, 3, false), new GrowthSample(200, 2, false)));
        assertThat(even.medianBytes()).isEqualTo(200L);
        assertThat(even.samples()).isEqualTo(4);
        assertThat(PageSpeedRules.growthOf(List.of()).enough()).isFalse();
        assertThat(PageSpeedRules.growthOf(null).medianBytes()).isNull();
    }

    @Test
    @DisplayName("5'ten az örnek: büyüme karar VERİLEMEZ — ihlal yok, anahtar kararsız (açık alarm sahte kapanmaz)")
    void fewSamplesUndecided() {
        PageSpeedMonitor m = growth(20, 20, null);
        Growth g = PageSpeedRules.growthOf(samples(4, 100_000, 10));
        Measurement x = measure(1_000_000, 100, false, g);

        List<String> breached = PageSpeedRules.evaluate(m, x);
        assertThat(breached).isEmpty();
        assertThat(PageSpeedRules.undecided(m, x, breached)).containsExactly("GROWSIZE", "GROWREQ");
        // Referans hiç okunamadı (null) — yine kararsız
        Measurement noRef = measure(1_000_000, 100, false, null);
        assertThat(PageSpeedRules.undecided(m, noRef, PageSpeedRules.evaluate(m, noRef)))
                .containsExactly("GROWSIZE", "GROWREQ");
    }

    @Test
    @DisplayName("Büyüme yüzdesi KESİN büyüktür: tam eşik ihlal değil, bir bayt fazlası ihlal; istek sayısı ayrı")
    void boundary() {
        PageSpeedMonitor m = growth(20, 50, null);
        Growth g = PageSpeedRules.growthOf(samples(5, 100_000, 10));
        assertThat(PageSpeedRules.evaluate(m, measure(120_000, 15, false, g))).isEmpty();
        assertThat(PageSpeedRules.evaluate(m, measure(120_001, 15, false, g))).containsExactly("GROWSIZE");
        assertThat(PageSpeedRules.evaluate(m, measure(100_000, 16, false, g))).containsExactly("GROWREQ");
        // Küçülme ihlal değil; karar verildi (kararsız değil)
        Measurement small = measure(50_000, 5, false, g);
        assertThat(PageSpeedRules.evaluate(m, small)).isEmpty();
        assertThat(PageSpeedRules.undecided(m, small, List.of())).isEmpty();
        assertThat(PageSpeedRules.measuredOf(small, "GROWSIZE")).isEqualTo(-50L);
    }

    @Test
    @DisplayName("Kısmi ölçüm (alt sınır): eşiği aşan değer kanıttır, aşmayan kararsızdır")
    void lowerBound() {
        PageSpeedMonitor m = growth(20, null, null);
        Growth g = PageSpeedRules.growthOf(samples(6, 100_000, 10));
        Measurement big = measure(200_000, 10, true, g);
        assertThat(PageSpeedRules.evaluate(m, big)).containsExactly("GROWSIZE");
        Measurement within = measure(110_000, 10, true, g);
        List<String> breached = PageSpeedRules.evaluate(m, within);
        assertThat(breached).isEmpty();
        assertThat(PageSpeedRules.undecided(m, within, breached)).containsExactly("GROWSIZE");
    }

    @Test
    @DisplayName("Büyüme eşiği yoksa referans hiç okunmaz — eşik yok, kararsız yok, bütçe tanımlı değil")
    void notConfigured() {
        PageSpeedMonitor m = growth(null, null, null);
        Measurement x = measure(900_000, 90, false, null);
        assertThat(PageSpeedRules.evaluate(m, x)).isEmpty();
        assertThat(PageSpeedRules.undecided(m, x, List.of())).isEmpty();
        assertThat(PageSpeedRules.budgetDefined(m)).isFalse();
        assertThat(PageSpeedRules.budgetDefined(growth(10, null, null))).isTrue();
        assertThat(PageSpeedRules.budgetDefined(growth(null, null, true))).isTrue();
    }

    @Test
    @DisplayName("Öğrenilen bant: kararlı geçmişin çok üstü SIZEBAND; bant kurulurken kararsız; bant içi temiz")
    void band() {
        PageSpeedMonitor m = growth(null, null, true);
        List<GrowthSample> stable = new ArrayList<>();
        for (int i = 0; i < 20; i++) stable.add(new GrowthSample(100_000 + (i % 3) * 500, 10, false));

        Growth g = PageSpeedRules.growthOf(stable).withBand(PageSpeedRules.bandVerdict(200_000L, stable));
        Measurement out = measure(200_000, 10, false, g);
        assertThat(PageSpeedRules.evaluate(m, out)).containsExactly("SIZEBAND");
        assertThat(PageSpeedRules.breachDetail(m, List.of("SIZEBAND"), out)).startsWith("SIZEBAND:").endsWith(">195");

        Growth gIn = PageSpeedRules.growthOf(stable).withBand(PageSpeedRules.bandVerdict(100_500L, stable));
        Measurement in = measure(100_500, 10, false, gIn);
        assertThat(PageSpeedRules.evaluate(m, in)).isEmpty();
        assertThat(PageSpeedRules.undecided(m, in, List.of())).isEmpty();

        List<GrowthSample> few = stable.subList(0, 3);
        Growth gFew = PageSpeedRules.growthOf(few).withBand(PageSpeedRules.bandVerdict(200_000L, few));
        Measurement building = measure(200_000, 10, false, gFew);
        assertThat(PageSpeedRules.evaluate(m, building)).isEmpty();
        assertThat(PageSpeedRules.undecided(m, building, List.of())).containsExactly("SIZEBAND");
    }

    @Test
    @DisplayName("Bant: bandı aşmış (işaretli) ölçümler tabana katılmaz — kalıcı büyüme bandı kendi kendine genişletemez")
    void bandExcludesBreaches() {
        List<GrowthSample> hist = new ArrayList<>();
        for (int i = 0; i < 5; i++) hist.add(new GrowthSample(200_000, 10, true));   // yeni, işaretli
        for (int i = 0; i < 20; i++) hist.add(new GrowthSample(100_000, 10, false));
        var v = PageSpeedRules.bandVerdict(200_000L, hist);
        assertThat(v).isNotNull();
        assertThat(v.evaluated()).isTrue();
        assertThat(v.breached()).isTrue();
        assertThat(v.mean()).isEqualTo(100_000d);
    }

    @Test
    @DisplayName("İnceleme 2026-11-15: TAM okunan geçmişte pencerenin başına dek uzanan (≥ 5) aşım dizisi yeni düzeydir — seyrek "
            + "izleme bant alarmında kalmaz; okuma kesildiyse (complete=false) uyum yok; dizi başa ulaşmıyorsa eski düzey kalır")
    void bandAdaptsOnCompleteSparseHistory() {
        // Günlük izleme: 7 günlük pencerede yalnız 6 örnek, hepsi yeni (bandı aşmış, işaretli) düzeyde
        List<GrowthSample> sparse = new ArrayList<>();
        for (int i = 0; i < 6; i++) sparse.add(new GrowthSample(200_000, 10, true));
        var adopted = PageSpeedRules.bandVerdict(200_000L, sparse, true);
        assertThat(adopted.evaluated()).isTrue();
        assertThat(adopted.breached()).isFalse();
        assertThat(adopted.mean()).isEqualTo(200_000d);

        // aynı örnekler, okuma sınıra takıldı → dizi geçmişin tamamı sayılmaz → taban kurulamaz (karar yok)
        var cut = PageSpeedRules.bandVerdict(200_000L, sparse, false);
        assertThat(cut.evaluated()).isFalse();
        assertThat(PageSpeedRules.bandVerdict(200_000L, sparse)).isEqualTo(cut);   // eski imza = kesik

        // aşım dizisi pencerenin başına ULAŞMIYOR (öncesinde eski düzey var) → eski düzey taban, ihlal sürer
        List<GrowthSample> mixed = new ArrayList<>(sparse);
        for (int i = 0; i < 10; i++) mixed.add(new GrowthSample(100_000, 10, false));
        var kept = PageSpeedRules.bandVerdict(200_000L, mixed, true);
        assertThat(kept.evaluated()).isTrue();
        assertThat(kept.breached()).isTrue();
        assertThat(kept.mean()).isEqualTo(100_000d);

        // withGrowth complete bayrağını banda geçirir
        PageSpeedMonitor m = growth(null, null, true);
        m.setId(9L);
        var res = new com.sitemonitor.service.PageSpeedCheckerService.Result("OK", 200, 100, 200, 1000, 200_000, 10, 0, false, false,
                List.of(), null, List.of(), null, 0, null, false, null, List.of(), null);
        assertThat(com.sitemonitor.service.PageSpeedCheckerService.withGrowth(res, m, sparse, true).breached()).isEmpty();
        assertThat(com.sitemonitor.service.PageSpeedCheckerService.withGrowth(res, m, sparse, true).undecided()).isEmpty();
        assertThat(com.sitemonitor.service.PageSpeedCheckerService.withGrowth(res, m, sparse, false).undecided())
                .containsExactly("SIZEBAND");
    }

    @Test
    @DisplayName("Posta tablosu: büyüme satırı yüzde|medyan, bant alt|üst|up; referans yoksa medyan boş")
    void thresholdTable() {
        PageSpeedMonitor m = growth(20, 30, true);
        List<GrowthSample> s = samples(5, 102_400, 10);
        Growth g = PageSpeedRules.growthOf(s).withBand(PageSpeedRules.bandVerdict(204_800L, s));
        Measurement x = measure(204_800, 14, false, g);
        List<String> breached = PageSpeedRules.evaluate(m, x);
        String table = PageSpeedRules.thresholdTable(m, x, breached);
        assertThat(table).contains("GROWSIZE\t20|100\t200\t1\t0");
        assertThat(table).contains("GROWREQ\t30|10\t14\t1\t0");
        assertThat(table).containsPattern("SIZEBAND\t\\d+\\|\\d+\\|up\t200\t1\t0");

        Measurement noRef = measure(204_800, 14, false, Growth.NONE);
        String t2 = PageSpeedRules.thresholdTable(m, noRef, List.of());
        assertThat(t2).contains("GROWSIZE\t20|\t200\t0\t0").contains("SIZEBAND\t||up\t200\t0\t0");
    }

    @Test
    @DisplayName("Tam sayı aritmetiği: büyük baytlarda taşma yok, yüzde aşağı yuvarlanır")
    void arithmetic() {
        assertThat(PageSpeedRules.grewBeyond(3_000_000_000L, 1_000_000_000L, 199)).isTrue();
        assertThat(PageSpeedRules.grewBeyond(3_000_000_000L, 1_000_000_000L, 200)).isFalse();
        assertThat(PageSpeedRules.growthPct(150L, 100L)).isEqualTo(50L);
        assertThat(PageSpeedRules.growthPct(99L, 100L)).isEqualTo(-1L);
        assertThat(PageSpeedRules.growthPct(1L, 0L)).isNull();
        assertThat(PageSpeedRules.grewBeyond(10L, 0L, 10)).isFalse();
    }
}
