package com.sitemonitor.repository;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Kaynak kapısı (performans 2026-11-06, PF1 madde 1): tek izleme kontrol sorguları {@code monitorId = :id … ORDER BY
 * checkedAt DESC [LIMIT | Pageable | findTop]} biçiminde YAZILMAZ. Bu biçimde PostgreSQL eşitliği bilinen kolonu sıralamadan
 * atıp tekil {@code checked_at} dizinini geriye tarar, {@code monitor_id}'yi SÜZGEÇLE eler — duraklatılmış izlemenin 30 günlük
 * Kontrol Geçmişi yerel 2,3 M satırlık port_checks'te 206–337 ms, son başarısı eski DNS izlemesinin değişiklik tabanı genel
 * planda 64–106 ms. Sağlam biçim: {@code monitorId IN (:id, :id) … ORDER BY monitorId DESC, checkedAt DESC} (sayfalı
 * sorgularda varsayılan metot + {@link MonitorIndexSort#leading}). {@code monitorId BETWEEN :id AND :id} de yasak: varsayılan
 * aralık seçiciliği tahmini bire düşürüp page_checks'te BitmapAnd + sıralama seçtiriyordu. Plan kanıtı:
 * {@code SingleMonitorQueryPlanPostgresTest}.
 */
class SingleMonitorQueryGateTest {

    private static final Path REPO_DIR = Path.of("src/main/java/com/sitemonitor/repository");

    /** Türetilmiş "en yeni" metodu: findTop…ByMonitorId…OrderByCheckedAtDesc / findFirst… */
    private static final Pattern DERIVED_TOP = Pattern.compile(
            "\\b(find(?:Top|First)\\d*ByMonitorId\\w*OrderByCheckedAtDesc)\\s*\\(");
    /** Türetilmiş sayfalı aralık metodu (soyut — varsayılan metot değil): Page<…> findByMonitorId…CheckedAtBetween(…, Pageable). */
    private static final Pattern DERIVED_PAGE = Pattern.compile(
            "(^|\\n)\\s*(?:org\\.springframework\\.data\\.domain\\.)?Page<\\w+>\\s+(findByMonitorId\\w*CheckedAtBetween)\\s*\\([^)]*Pageable[^)]*\\)\\s*;");
    /** Denenip bırakılan biçim: {@code monitorId BETWEEN :id AND :id}. */
    private static final Pattern BETWEEN_MONITOR = Pattern.compile("\\bmonitorId\\s+BETWEEN\\s+:(\\w+)\\s+AND\\s+:\\1\\b");
    /** Sağlam biçim: {@code monitorId IN (:id, :id)}. */
    private static final Pattern IN_FORM = Pattern.compile("\\bmonitorId IN \\(:(\\w+), :\\1\\)");
    /**
     * IN biçimli sorguda (aynı ek açıklama, sayım sorgusundan önce) çıplak üst sınır {@code x.checkedAt <= :to}: genel planda
     * aralık çifti sayılır, tekil checked_at dizini + sıralama seçilebilir → {@code MonitorIndexSort.UNTIL_*} kullanılmalı.
     */
    // Doğrusal tarama (2026-11-06): eski gruplu tekrar desenleri (`(?:[^"\\]|\\.)*`, `(?:(?!countQuery)[^@;])*?`) karakter
    // başına özyineleme yapıyordu — uzun bir sorgu metni testi StackOverflowError ile düşürdü (CLAUDE.md "Things that bite").
    private static final Pattern IN_PAIR = Pattern.compile("\\b(?:monitorId|domain) IN \\(:(\\w+), :\\1\\)");
    private static final Pattern UPPER_TO = Pattern.compile("\\b\\w+\\.checkedAt <= :to\\b");

    /** IN biçimli sorgudan sonraki ilk '@' / ';' sınırına (sayım sorgusundan önce) dek çıplak üst sınır var mı. */
    static List<String> rawUpperBounds(String joinedSrc) {
        List<String> out = new ArrayList<>();
        Matcher m = IN_PAIR.matcher(joinedSrc);
        while (m.find()) {
            int end = joinedSrc.length();
            int at = joinedSrc.indexOf('@', m.end());
            int semi = joinedSrc.indexOf(';', m.end());
            if (at >= 0) end = Math.min(end, at);
            if (semi >= 0) end = Math.min(end, semi);
            String seg = joinedSrc.substring(m.end(), end);
            int cq = seg.indexOf("countQuery");
            if (cq >= 0) seg = seg.substring(0, cq);
            Matcher u = UPPER_TO.matcher(seg);
            if (u.find()) out.add(m.group() + seg.substring(0, u.end()));
        }
        return out;
    }

    /** @Query metni (Java dize birleştirmeleri dahil) ve ardından gelen ek açıklama kuyruğu — {@code [sql, tail]}. */
    static List<String[]> queries(String src) {
        List<String[]> out = new ArrayList<>();
        int from = 0;
        while (true) {
            int q = src.indexOf("@Query(", from);
            if (q < 0) break;
            int i = q + "@Query(".length();
            from = i;
            i = skipWs(src, i);
            if (src.startsWith("value", i)) {
                int j = skipWs(src, i + 5);
                if (j < src.length() && src.charAt(j) == '=') i = skipWs(src, j + 1);
            }
            if (i >= src.length() || src.charAt(i) != '"') continue;
            StringBuilder sql = new StringBuilder();
            while (i < src.length() && src.charAt(i) == '"') {
                int k = i + 1;
                while (k < src.length() && src.charAt(k) != '"') k += src.charAt(k) == '\\' ? 2 : 1;
                if (k >= src.length()) break;
                sql.append(src, i + 1, k);
                i = skipWs(src, k + 1);
                if (i < src.length() && src.charAt(i) == '+') i = skipWs(src, i + 1); else break;
            }
            // Kuyruk: ek açıklamanın kapanışına (")" + satır sonu) dek, en çok 200 karakter (eski desenle aynı pencere)
            int limit = Math.min(src.length(), i + 200);
            int close = -1;
            for (int k = i; k < limit; k++) {
                if (src.charAt(k) != ')') continue;
                int n = k + 1;
                while (n < src.length() && (src.charAt(n) == ' ' || src.charAt(n) == '\t' || src.charAt(n) == '\r')) n++;
                if (n < src.length() && src.charAt(n) == '\n') { close = k; break; }
            }
            if (close < 0) continue;
            out.add(new String[]{sql.toString(), src.substring(i, close)});
            from = close;
        }
        return out;
    }

    private static int skipWs(String s, int i) {
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
        return i;
    }
    private static final Pattern EQ_MONITOR = Pattern.compile("\\b(\\w+)\\.monitorId\\s*=\\s*:\\w+");
    private static final Pattern ORDER_CHECKED = Pattern.compile("ORDER BY\\s+(\\w+)\\.checkedAt\\b", Pattern.CASE_INSENSITIVE);

    /**
     * Bilinçli istisnalar: dosya → gerekçe. {@code pagespeed_resources}'ta (monitor_id, checked_at) dizini YOK; ihlal anları
     * {@code keep_reason = 'BREACH'} ile {@code idx_psr_reason_checked}'ten okunur (BREACH satırları azdır) — IN biçimi
     * orada sırayı veren bir dizin bulamaz.
     */
    private static final java.util.Map<String, String> ALLOWED = java.util.Map.of(
            "PageSpeedResourceRepository.java", "monitor_id + checked_at dizini yok; keep_reason = 'BREACH' dizini kullanılır");

    static String joined(String javaStringConcat) {
        return javaStringConcat.replaceAll("\"\\s*\\+\\s*\"", "");
    }

    /** Bir kaynak dosyadaki ihlaller (dosya adıyla). */
    static List<String> offenders(String file, String src) {
        List<String> out = new ArrayList<>();
        Matcher t = DERIVED_TOP.matcher(src);
        while (t.find()) {
            // Bildirimden önceki ifade sınırına (; { }) dek @Query var mı?
            int start = Math.max(Math.max(src.lastIndexOf(';', t.start()), src.lastIndexOf('{', t.start())), src.lastIndexOf('}', t.start()));
            String head = src.substring(Math.max(0, start), t.start());
            if (!head.contains("@Query(")) out.add(file + ": türetilmiş " + t.group(1) + " (@Query + IN (:id, :id) biçimi yok)");
        }
        Matcher bw = BETWEEN_MONITOR.matcher(joined(src));
        while (bw.find()) out.add(file + ": " + bw.group() + " (BETWEEN — IN (:id, :id) kullanın)");
        for (String up : rawUpperBounds(joined(src)))
            out.add(file + ": çıplak üst sınır (MonitorIndexSort.UNTIL_* yok): " + up.replaceAll("\\s+", " "));
        Matcher p = DERIVED_PAGE.matcher(src);
        while (p.find()) out.add(file + ": soyut sayfalı " + p.group(2) + " (varsayılan metot + MonitorIndexSort.leading yok)");
        for (String[] q : queries(src)) {
            if (q[1].contains("nativeQuery")) continue;
            String sql = q[0];
            Matcher eq = EQ_MONITOR.matcher(sql);
            Matcher ord = ORDER_CHECKED.matcher(sql);
            if (eq.find() && ord.find() && eq.group(1).equals(ord.group(1))) {
                out.add(file + ": " + sql.replaceAll("\\s+", " "));
            }
        }
        return out;
    }

    @Test
    @DisplayName("Tek izleme sıralı sorguları IN (:id, :id) + ORDER BY monitorId, checkedAt biçiminde (türetilmiş / eşitlik / BETWEEN yok)")
    void singleMonitorOrderedQueriesUseTheInForm() throws IOException {
        List<String> offenders = new ArrayList<>();
        int inForm = 0;
        try (Stream<Path> files = Files.list(REPO_DIR)) {
            for (Path f : files.filter(x -> x.toString().endsWith(".java")).toList()) {
                String src = Files.readString(f, StandardCharsets.UTF_8);
                if (ALLOWED.containsKey(f.getFileName().toString())) continue;
                offenders.addAll(offenders(f.getFileName().toString(), src));
                Matcher m = IN_FORM.matcher(src);
                while (m.find()) inForm++;
            }
        }
        assertThat(inForm).as("IN (:id, :id) biçimli sorgu bulunamadı — kapı yanlış dizini mi okuyor?").isGreaterThanOrEqualTo(50);
        assertThat(offenders).as("monitorId = :id + ORDER BY checkedAt → monitorId IN (:id, :id) + ORDER BY monitorId, checkedAt")
                .isEmpty();
    }

    @Test
    @DisplayName("Kapı eski biçimleri yakalar (türetilmiş findTop, soyut sayfalı metot, eşitlikli JPQL, BETWEEN)")
    void gateDetectsTheOldForms() {
        String src = """
                public interface X extends JpaRepository<PortCheck, Long> {
                    Page<PortCheck> findByMonitorIdAndOpenFalseAndCheckedAtBetween(Long monitorId, String from, String to, Pageable p);
                    Optional<PortCheck> findTopByMonitorIdOrderByCheckedAtDesc(Long monitorId);
                    @Query("SELECT pc FROM PortCheck pc WHERE pc.monitorId = :id AND pc.checkedAt >= :since "
                         + "ORDER BY pc.checkedAt DESC LIMIT :limit")
                    List<PortCheck> findRecentByMonitorIdSince(@Param("id") Long id, @Param("since") String since, @Param("limit") int limit);
                    @Query("SELECT c FROM PortCheck c WHERE c.monitorId BETWEEN :id AND :id ORDER BY c.monitorId DESC, c.checkedAt DESC")
                    List<PortCheck> betweenForm(@Param("id") Long id);
                    @Query("SELECT c.checkedAt FROM PortCheck c WHERE c.monitorId IN (:id, :id) AND c.checkedAt >= :from "
                         + "AND c.checkedAt <= :to ORDER BY c.monitorId DESC, c.checkedAt DESC LIMIT :limit")
                    List<Object[]> rawUpper(@Param("id") Long id, @Param("from") String from, @Param("to") String to, @Param("limit") int limit);
                }
                """;
        assertThat(offenders("X.java", src)).hasSize(5);
        String good = """
                public interface Y extends JpaRepository<PortCheck, Long> {
                    default Page<PortCheck> findByMonitorIdAndCheckedAtBetween(Long monitorId, String from, String to, Pageable p) {
                        return historyPage(monitorId, from, to, MonitorIndexSort.leading(p, "monitorId"));
                    }
                    @Query("SELECT c FROM PortCheck c WHERE c.monitorId IN (:id, :id) ORDER BY c.monitorId DESC, c.checkedAt DESC LIMIT 1")
                    Optional<PortCheck> findTopByMonitorIdOrderByCheckedAtDesc(@Param("id") Long monitorId);
                    @Query(value = "SELECT t.bucket FROM port_checks c WHERE c.monitor_id = :id ORDER BY c.checked_at", nativeQuery = true)
                    List<Object[]> nativeOne(@Param("id") Long id);
                    @Query(value = "SELECT c FROM PortCheck c WHERE c.monitorId IN (:id, :id) AND c.checkedAt >= :from AND "
                         + MonitorIndexSort.UNTIL_C,
                           countQuery = "SELECT COUNT(c.monitorId) FROM PortCheck c WHERE c.monitorId = :id AND c.checkedAt >= :from "
                         + "AND c.checkedAt <= :to")
                    Page<PortCheck> historyPage(@Param("id") Long id, @Param("from") String from, @Param("to") String to, Pageable p);
                }
                """;
        assertThat(offenders("Y.java", good)).isEmpty();
    }

    @Test
    @DisplayName("MonitorIndexSort.leading: anahtar kolonları ilk ölçütün yönüyle başa ekler; sırasız / sayfasız dokunulmaz")
    void leadingPrependsKeyColumnsWithTheSameDirection() {
        Pageable desc = MonitorIndexSort.leading(PageRequest.of(2, 50, Sort.by(Sort.Direction.DESC, "checkedAt")), "monitorId");
        assertThat(desc.getPageNumber()).isEqualTo(2);
        assertThat(desc.getPageSize()).isEqualTo(50);
        assertThat(desc.getSort()).containsExactly(Sort.Order.desc("monitorId"), Sort.Order.desc("checkedAt"));

        Pageable asc = MonitorIndexSort.leading(PageRequest.of(0, 5000, Sort.by("checkedAt").ascending()), "domain", "port");
        assertThat(asc.getSort()).containsExactly(Sort.Order.asc("domain"), Sort.Order.asc("port"), Sort.Order.asc("checkedAt"));

        PageRequest unsorted = PageRequest.of(0, 1);
        assertThat(MonitorIndexSort.leading(unsorted, "monitorId")).isSameAs(unsorted);
        assertThat(MonitorIndexSort.leading(Pageable.unpaged(), "monitorId").isUnpaged()).isTrue();
        PageRequest already = PageRequest.of(0, 10, Sort.by(Sort.Direction.DESC, "monitorId", "checkedAt"));
        assertThat(MonitorIndexSort.leading(already, "monitorId")).isSameAs(already);
    }
}
