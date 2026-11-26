package com.sitemonitor.repository;

import com.sitemonitor.model.IncidentRecord;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;

/**
 * Olay kaydı süzgeç sorguları (2026-11-26, "Bul ve Raporla" — Paket 3): liste, dışa aktarım ve eğilim AYNI süzgeci kullanır.
 * Sorgu yalnız VERİLEN koşullarla kurulur (boş süzgeç = koşulsuz); kullanıcı metni HİÇBİR zaman sorgu metnine girmez — her
 * değer adlı parametredir. Seçili sütun / sıralama / ek koşul metinleri yalnız koddaki sabitlerden gelir (çağıran
 * {@link com.sitemonitor.service.IncidentService}).
 */
public interface IncidentRecordQueries {

    /**
     * Aramanın baktığı alanlar (JPA öznitelik adları) — {@code q} hepsinde {@code LOWER(alan) LIKE :q ESCAPE '!'} ile aranır.
     * Sıra eşleşen-alan ipucunun önceliğidir ({@code IncidentService.matchField}). Kanal BİLEREK yok (eski kural: "%" araması
     * yalnız başlığında % geçen kaydı bulur; kanal süzgeci ayrıdır).
     */
    List<String> SEARCH_ATTRIBUTES = List.of("title", "service", "rcaSummary", "description", "resolutionSteps",
            "businessImpact", "tags", "errorCode", "functionCode", "channelCode", "affectedApp", "affectedSystems",
            "affectedServices", "problemTypes");

    /**
     * Hazır (kaçışlı) süzgeç değerleri — null = koşul yok. {@code q} küçük harfli + {@code %…%} sarılmış LIKE kalıbı;
     * {@code qid} {@code #123} / {@code 123} aramasının kimliği; {@code *Token} CSV alanında TAM öğe kalıbı
     * ({@code %,değer,%}, veritabanında iki taraf da LOWER); {@code errorCode} / {@code createdBy} tam (büyük-küçük harf
     * duyarsız); {@code postmortem} NONE | DRAFT | IN_REVIEW | PUBLISHED. {@code scope} null = tüm takımlar, boş = hiçbiri.
     */
    record Filter(String q, Long qid, String severity, String category, String status, String service, String channel,
                  String since, String until, Long teamId, Boolean slaBreached, Boolean open,
                  String serviceToken, String channelToken, String problemTypeToken, String tagToken,
                  String errorCode, String createdBy, String postmortem, List<Long> scope) {

        /** Yalnız tarih aralığı + kapsam (eğilimin "ek süzgeç yok" yolu ile aynı nüfus). */
        public static Filter dates(String since, String until, List<Long> scope) {
            return new Filter(null, null, null, null, null, null, null, since, until, null, null, null,
                    null, null, null, null, null, null, null, scope);
        }

        /** Aynı süzgeç, başka tarih aralığıyla (Bugün / Son 7 / Son 30 gün pencereleri tarih süzgecinden bağımsızdır). */
        public Filter withDates(String from, String to) {
            return new Filter(q, qid, severity, category, status, service, channel, from, to, teamId, slaBreached, open,
                    serviceToken, channelToken, problemTypeToken, tagToken, errorCode, createdBy, postmortem, scope);
        }
    }

    /**
     * Süzgeçli sayfa. {@code orderBy} {@code IncidentService.SORTS} sabitlerinden biri ({@code " ORDER BY …"}); kullanıcı
     * metni almaz. Kapsam boşsa boş sayfa (sorgu yok).
     */
    Page<IncidentRecord> findFiltered(Filter f, Pageable pageable, String orderBy);

    /** Süzgeçli sayım (kapsam boşsa 0, sorgu yok). */
    long countFiltered(Filter f);

    /**
     * Süzgeçli izdüşüm: {@code SELECT <select> FROM IncidentRecord i WHERE <süzgeç> [AND <extra>…] <tail>}. Satır HER ZAMAN
     * {@code Object[]} (tek sütunda da). {@code limit} &le; 0 = sınırsız. {@code select} / {@code extra} / {@code tail}
     * yalnız koddaki sabitler.
     */
    List<Object[]> selectFiltered(String select, Filter f, List<String> extra, String tail, int limit);

    /**
     * Seçenek yönetimi (2026-11-26): bir takımın kayıtlarında bir alanın değerleri — {@code [id, değer]}, yalnız dolu
     * değerler, kimlik sırasıyla en çok {@code limit} satır (inceleme 2026-11-26: sınırsız okuma yok; çağıran {@code limit + 1}
     * isteyip kesildiğini anlar) ve {@link #OPTION_QUERY_TIMEOUT_MS} ms sorgu zaman aşımıyla. Takımın kaydı = kaydın takımı
     * bu takım ya da takımsız kayıtta giren takım bu takım. {@code attribute} yalnız
     * {@code errorCode | functionCode | channelCode | service | channel} olabilir (başkası {@link IllegalArgumentException}).
     */
    List<Object[]> teamFieldValues(String attribute, Long teamId, int limit);

    /**
     * Birden çok olay satırını çağıranın işlemi boyunca KİLİTLER (2026-11-26, seçenek yeniden adlandırma): kimlik SIRASIYLA
     * (iki eşzamanlı yeniden adlandırma birbirini kilitlenmeye sokmaz). Kilit beklemesi SINIRLI (inceleme 2026-11-26):
     * PostgreSQL'de işlem içi {@code SET LOCAL lock_timeout} ({@link #LOCK_TIMEOUT_MS} ms) — süre dolunca 55P03 →
     * {@code PessimisticLockingFailureException} (503 {@code LOCK_CONFLICT}, işlem geri alınır, önceki parçaların kilitleri
     * bırakılır); her yerde ayrıca sorgu zaman aşımı. Kilitlenen kimlikler döner (silinmiş kayıt dönmez).
     */
    default List<Long> lockRows(java.util.Collection<Long> ids) {
        return lockRows(ids, LOCK_TIMEOUT_MS);
    }

    /** {@link #lockRows(java.util.Collection)} — kilit bekleme süresi verilerek (test ve özel çağıranlar). */
    List<Long> lockRows(java.util.Collection<Long> ids, long lockTimeoutMs);

    /** Satır kilidi bekleme üst sınırı (ms). */
    long LOCK_TIMEOUT_MS = 10_000L;
    /** Liste / sayım / izdüşüm sorgularının zaman aşımı (ms) — uzun bir sorgu havuz soket süresine dek beklemez. */
    int FILTER_QUERY_TIMEOUT_MS = 30_000;
    /** Seçenek kullanım / yeniden adlandırma taramasının zaman aşımı (ms). */
    int OPTION_QUERY_TIMEOUT_MS = 15_000;
}
