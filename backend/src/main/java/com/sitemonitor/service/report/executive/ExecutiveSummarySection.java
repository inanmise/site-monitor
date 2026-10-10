package com.sitemonitor.service.report.executive;

/**
 * AYLIK YÖNETİCİ ÖZETİ BÖLÜM SAĞLAYICISI (2026-10-10) — özetin TEK genişleme noktası.
 *
 * <p>Her {@code @Component} uygulaması otomatik toplanır ({@code ExecutiveSummaryService} Spring'ten bütün
 * uygulamaları alır, {@link #order()} ile sıralar). Bir bölüm eklemek için:
 * <ol>
 *   <li>bu arayüzü uygulayan bir {@code @Component} yaz; {@link #compute} {@link SectionResult} döndürsün
 *       ({@link SectionResult#builder} — hükümler, gösterge kutuları, tablolar, notlar, isteğe bağlı {@code data});</li>
 *   <li>ay içinde SABİT sayıda toplu sorgu çalıştır (satır başına sorgu YOK). Envanter ve son sertifika durumu için
 *       {@link ExecutiveSummaryContext#latestCerts()} / {@link ExecutiveSummaryContext#inventory()} paylaşımlıdır —
 *       aynı veriyi ikinci kez okuma;</li>
 *   <li>i18n: TR + EN {@code exec.<key>.title}, {@code exec.<key>.verdict.<kod>}, {@code exec.<key>.kpi.<kod>}
 *       (+ {@code .hint}), {@code exec.<key>.table.<kod>}, {@code exec.<key>.col.<kod>}, {@code exec.<key>.note.<kod>}
 *       — kapı {@code ExecutiveSummaryI18nGateTest} yeni bölümün örnek çıktısını da tarar.</li>
 * </ol>
 * E-posta, PDF ve uygulama ekranı bölümü {@link SectionResult} üzerinden GENEL olarak çizer; başka bir dosyaya
 * dokunmak gerekmez. Hata fırlatan bölüm özetin geri kalanını düşürmez ({@link SectionResult#failed}).
 *
 * <p><b>Sıra numaraları</b> (yeni bölüm araya girebilsin diye onar onar):
 * <ul>
 *   <li>10 {@code availability} — erişilebilirlik hedefi uyumu</li>
 *   <li>20 {@code noise} — en gürültülü alarmlar</li>
 *   <li>30 {@code expirations} — yaklaşan sertifika bitişleri</li>
 *   <li>40 {@code renewals} — yenileme süresine uyum</li>
 *   <li>50 {@code tls-grade} — TLS yapılandırma notu dağılımı ({@link TlsGradeSection})</li>
 *   <li>60 {@code crypto-readiness} — kripto envanteri / PQC hazırlığı ({@link CryptoReadinessSection})</li>
 *   <li>70 {@code data-quality} — takım veri kalitesi puanı ({@link DataQualitySection})</li>
 * </ul>
 * Başka bir özelliğin KODLARINI (neden, kategori, kural …) sütun / parametre olarak yollayan bölüm, Türkçesini
 * {@link ExecFormat#CODE_LABELS_TR}'ye, i18n anahtar kalıbını {@link ExecFormat#CODE_LABEL_I18N}'e ve arayüz eşlemesini
 * {@code executiveModel.CODE_LABEL_KEY}'e ekler (kapı: {@code ExecutiveSummaryCodeLabelsGateTest}).
 */
public interface ExecutiveSummarySection {

    /** Kararlı bölüm anahtarı (URL, i18n öneki, PDF/e-posta çapası) — küçük harf, tire ile. */
    String key();

    /** Bölüm sırası (küçük önce). */
    int order();

    /** Türkçe bölüm başlığı (posta/PDF); arayüz {@code exec.<key>.title} kullanır. */
    String title();

    /**
     * Bölümü hesaplar. Ay sınırları Europe/Istanbul takvimine göredir ({@link ExecutiveSummaryContext}); bu metot
     * istek yolunda da çalışır → yalnız sabit sayıda toplu sorgu.
     */
    SectionResult compute(ExecutiveSummaryContext ctx);
}
