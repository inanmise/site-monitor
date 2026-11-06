package com.sitemonitor.repository;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.util.ArrayList;
import java.util.List;

/**
 * Tek izleme (tek anahtar) kontrol sorgularının sırası (performans 2026-11-06, PF1 madde 1).
 *
 * <p><b>Sorun:</b> {@code WHERE monitor_id = ? [AND checked_at aralığı] ORDER BY checked_at DESC LIMIT n} biçiminde
 * PostgreSQL eşitliği bilinen kolonu sıralamadan atar; hem tekil {@code checked_at} indeksi hem bileşik
 * {@code (monitor_id, checked_at)} indeksi sırayı verir ve planlayıcı fiziksel korelasyonu yüksek tekil indeksi seçip
 * {@code monitor_id}'yi SÜZGEÇLE eler. Duraklatılmış / son kontrolü eski bir izlemede (ya da "yalnız hatalar"
 * süzgecinde) tüm aralığın satırları okunur: yerel 2,3 M satırlık port_checks'te 30 günlük Kontrol Geçmişi 206–337 ms.
 *
 * <p><b>Sağlam biçim:</b> {@code monitor_id IN (:id, :id) … ORDER BY monitor_id DESC, checked_at DESC}. İki öğeli liste
 * ({@code = ANY(ARRAY[$1, $2])}) eşitlik sınıfı kurmaz, {@code monitor_id} sıralamada kalır; bu sırayı YALNIZ bileşik indeks
 * verir. Dizi koşulu B-ağacında eşitlik türündendir: arkasındaki {@code checked_at} sınırları da dizin koşulu olur ve taramayı
 * tam yerinde başlatıp bitirir (duraklatılmış izlemenin boş 30 günü: 177 bin tampon → 7). Seçicilik tahmini gerçekçidir
 * (2 × 1/n_distinct) — {@code BETWEEN :id AND :id} denendi: varsayılan aralık seçiciliği (0,005) tahmini bire düşürüp bazı
 * tablolarda (page_checks) BitmapAnd + sıralama seçtiriyordu. Yinelenen değer çalışmada tekilleştirilir; sonuç aynıdır
 * ({@code monitor_id} sonuç kümesinde sabit).
 *
 * <p><b>Aralıklı sorgularda üst sınır ({@code UNTIL_*}):</b> {@code checked_at >= :from AND checked_at <= :to} çifti
 * GENEL planda (pgjdbc 5 çalıştırmadan sonra sunucuda hazırlar; PostgreSQL değeri bilmeden planlayabilir) varsayılan aralık
 * seçiciliği 0,005 ile tahmin edilir; az izlemeli / küçük tablolarda (yerel keyword_results: 3 izleme) planlayıcı tekil
 * {@code checked_at} dizininden aralığın TÜMÜNÜ okuyup sıralamayı ucuz sanar ve özel plandan ucuz göründüğü için seçer —
 * etkin izlemenin 500 satırlık serisi bile tüm aralığı okur. Üst sınır bu yüzden
 * {@code (x.checkedAt <= :to OR (CAST(:to AS String) IS NULL AND x.checkedAt <> x.checkedAt))} biçimindedir: anlamı aynı
 * (ikinci kol hiçbir satırda doğru olamaz — {@code :to} boşken de satır dönmez), özel planda sabit katlanır ve iki sınır
 * birlikte dizin koşulu olur; genel planda ise üst sınır aralık çifti sayılmaz (alt sınır 1/3 seçicilik), bileşik dizin
 * {@code (monitor_id, checked_at >= :from)} koşuluyla seçilir. Sabitler takma ada göre: {@code c}, {@code r}, {@code pc},
 * {@code u}.
 *
 * <p>Sayfalı ({@link Pageable}) sorgularda sıra çağırandan gelir ({@code CheckHistoryService}: {@code checkedAt DESC};
 * alan adı eğilimi: {@code checkedAt ASC}); bu yardımcı anahtar kolonları AYNI yönle başa ekler. Kapı:
 * {@code SingleMonitorQueryGateTest}, {@code SingleMonitorQueryPlanPostgresTest}.
 */
final class MonitorIndexSort {

    /** Üst sınır, takma ad {@code c} (sınıf açıklaması: genel planda aralık çifti sayılmaz). */
    static final String UNTIL_C = "(c.checkedAt <= :to OR (CAST(:to AS String) IS NULL AND c.checkedAt <> c.checkedAt))";
    /** Üst sınır, takma ad {@code r}. */
    static final String UNTIL_R = "(r.checkedAt <= :to OR (CAST(:to AS String) IS NULL AND r.checkedAt <> r.checkedAt))";
    /** Üst sınır, takma ad {@code pc}. */
    static final String UNTIL_PC = "(pc.checkedAt <= :to OR (CAST(:to AS String) IS NULL AND pc.checkedAt <> pc.checkedAt))";
    /** Üst sınır, takma ad {@code u}. */
    static final String UNTIL_U = "(u.checkedAt <= :to OR (CAST(:to AS String) IS NULL AND u.checkedAt <> u.checkedAt))";

    private MonitorIndexSort() {}

    /**
     * {@code p}'nin sırasının başına {@code leadProperties}'i ilk sıralama ölçütünün yönüyle ekler (zaten varsa
     * eklenmez). Sırasız ya da sayfasız istek olduğu gibi döner (sorgu ORDER BY'sız kalır — eski davranış).
     */
    static Pageable leading(Pageable p, String... leadProperties) {
        if (p == null || p.isUnpaged() || p.getSort().isUnsorted()) return p;
        Sort sort = p.getSort();
        Sort.Direction dir = sort.iterator().next().getDirection();
        List<Sort.Order> orders = new ArrayList<>();
        for (String lead : leadProperties) {
            if (sort.getOrderFor(lead) == null) orders.add(new Sort.Order(dir, lead));
        }
        if (orders.isEmpty()) return p;
        for (Sort.Order o : sort) orders.add(o);
        return PageRequest.of(p.getPageNumber(), p.getPageSize(), Sort.by(orders));
    }
}
