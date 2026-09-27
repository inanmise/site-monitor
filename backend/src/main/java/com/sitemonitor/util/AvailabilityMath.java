package com.sitemonitor.util;

/**
 * Erişilebilirlik yüzdesinin TEK yuvarlama kuralı (prod kapısı 2026-09-25, O-5).
 *
 * <p><b>Kural.</b> Başarısız kontrol varken sonuç ASLA %100 gösterilmez: yuvarlama tavanı
 * {@code 100 - 10^-ondalık} olur (1 ondalıkta 99,9 · 2 ondalıkta 99,99). Aksi hâlde yüksek hacimde tek bir
 * hata yok olur — 10.080 kontrolde (haftalık, 1 dk aralık) 1 hata %99,990 → round1 ile "%100,0"; kart kırmızı
 * noktayla "%100,0"ı yan yana gösteriyordu.
 *
 * <p><b>Geçmişi.</b> Kural önce {@code MonitoringController.uptimePct}'te, sonra (R6) {@code MonitorSparklineService.pct}'te
 * uygulandı; haftalık istatistik (3 yer), kimliksiz durum sayfası ve kart trendinin tek ondalıklı {@code up_pct}'i
 * — aynı dosyada — korumasız kalmıştı. Örnek kapanmış, sınıf açık kalmıştı: bütün çağrı yerleri buraya bağlanır,
 * kapı {@code AvailabilityMathTest}.
 */
public final class AvailabilityMath {

    private AvailabilityMath() {}

    /**
     * @param total    toplam (bakım hariç) kontrol sayısı
     * @param ok       başarılı kontrol sayısı
     * @param decimals gösterilecek ondalık hane (0..4)
     * @return yüzde; {@code total <= 0} ise {@code null} (veri yok ≠ %100)
     */
    public static Double pct(long total, long ok, int decimals) {
        if (total <= 0) return null;
        double scale = Math.pow(10, Math.max(0, Math.min(4, decimals)));
        double p = Math.round(100.0 * scale * ok / total) / scale;
        if (ok < total && p >= 100.0) p = (100.0 * scale - 1) / scale;   // hata varken tavan: 99,9 / 99,99 …
        return p;
    }
}
