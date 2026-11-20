package com.sitemonitor.repository;

import com.sitemonitor.config.GlobalExceptionHandler.CodedConflictException;
import com.sitemonitor.config.GlobalExceptionHandler.CodedNotFoundException;
import com.sitemonitor.it.PostgresIntegration;
import com.sitemonitor.it.PostgresIt;
import com.sitemonitor.model.TechErrorChannel;
import com.sitemonitor.service.retention.RetentionCatalog;
import com.sitemonitor.service.retention.RetentionPolicy;
import com.sitemonitor.service.techerror.TechErrorChannelNoteService;
import com.sitemonitor.service.techerror.TechErrorNoteRefs;
import com.sitemonitor.service.techerror.TechErrorReportService;
import com.sitemonitor.service.techerror.TechErrorTemplate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.PessimisticLockingFailureException;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Kanal notları (2026-11-20) GERÇEK PostgreSQL'de — H2'nin göremedikleri: sütun türleri (TEXT / bytea — Hibernate'in
 * {@code oid}'e düşmediği), kanal satırı kilidi ({@code SELECT … FOR UPDATE}) ile sıraya giren yazımlar, kilidi atlayan
 * eşzamanlı ilk kayıtta tekil kısıt ihlalinin 409 olması (bozuk işlemde okuma → 25P02 / 500 YOK), NUL içeren metin
 * (PostgreSQL TEXT kabul etmez — servis atar), yükleme ile kanal silmenin kilitlenmeden (40P01) bitmesi ve saklama
 * kuralının {@code timestamptz} sütununda çalışan SQL'i.
 */
@PostgresIntegration
class TechErrorChannelNotePostgresTest {

    private static final long WAIT_MS = 15_000;

    private static TechErrorReportService reports() {
        return PostgresIt.app().bean(TechErrorReportService.class);
    }

    private static TechErrorChannelNoteService notes() {
        return PostgresIt.app().bean(TechErrorChannelNoteService.class);
    }

    private static TechErrorChannel channel(String what) {
        long team = 765_430L + (long) (Math.random() * 1000);
        String tag = "tecn-it-" + UUID.randomUUID().toString().substring(0, 8);
        return reports().createChannel(team, tag + " " + what, null, TechErrorTemplate.defaults("tr"), null);
    }

    private static void cleanup(Long channelId) {
        var jdbc = PostgresIt.app().jdbc();
        jdbc.update("DELETE FROM tech_error_channel_note_images WHERE channel_id = ?", channelId);
        jdbc.update("DELETE FROM tech_error_channel_note_versions WHERE channel_id = ?", channelId);
        jdbc.update("DELETE FROM tech_error_channel_notes WHERE channel_id = ?", channelId);
        try {
            if (PostgresIt.app().bean(TechErrorChannelRepository.class).existsById(channelId)) reports().deleteChannel(channelId);
        } catch (RuntimeException e) {
            jdbc.update("DELETE FROM tech_error_channels WHERE id = ?", channelId);
        }
    }

    private static byte[] png() {
        try {
            BufferedImage img = new BufferedImage(64, 48, BufferedImage.TYPE_INT_RGB);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(img, "png", out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static long count(String table, Long channelId) {
        Long n = PostgresIt.app().jdbc().queryForObject("SELECT COUNT(*) FROM " + table + " WHERE channel_id = ?", Long.class, channelId);
        return n == null ? 0 : n;
    }

    /** Sorgu metni {@code fragment} içeren BAŞKA bir oturum kilit bekliyor mu (en çok {@link #WAIT_MS}). */
    private static boolean waitsOnLock(String fragment) throws InterruptedException {
        long until = System.currentTimeMillis() + WAIT_MS;
        while (System.currentTimeMillis() < until) {
            Integer n = PostgresIt.app().jdbc().queryForObject(
                    "SELECT COUNT(*) FROM pg_stat_activity WHERE datname = current_database() AND pid <> pg_backend_pid() "
                            + "AND wait_event_type = 'Lock' AND query ILIKE ?", Integer.class, "%" + fragment + "%");
            if (n != null && n > 0) return true;
            Thread.sleep(50);
        }
        return false;
    }

    private static String columnType(String table, String column) {
        return PostgresIt.app().jdbc().queryForObject(
                "SELECT data_type FROM information_schema.columns WHERE table_schema = current_schema() AND table_name = ? "
                        + "AND column_name = ?", String.class, table, column);
    }

    @Test
    @DisplayName("şema: gövde TEXT, görsel bytea (oid değil), bağ damgası zaman damgası; kanal başına tek not ve (kanal, sürüm) tekil")
    void columnTypesAndUniques() {
        assertThat(columnType("tech_error_channel_notes", "body_md")).isEqualTo("text");
        assertThat(columnType("tech_error_channel_note_versions", "body_md")).isEqualTo("text");
        assertThat(columnType("tech_error_channel_note_images", "data")).isEqualTo("bytea");
        assertThat(columnType("tech_error_channel_note_images", "linked")).isEqualTo("boolean");
        assertThat(columnType("tech_error_channel_note_images", "link_changed_at")).startsWith("timestamp");
        assertThat(columnType("tech_error_channel_notes", "version")).isEqualTo("bigint");
        List<String> noteIdx = PostgresIt.app().jdbc().queryForList(
                "SELECT indexdef FROM pg_indexes WHERE tablename = 'tech_error_channel_notes'", String.class);
        assertThat(noteIdx).anySatisfy(d -> assertThat(d).containsIgnoringCase("UNIQUE").contains("(channel_id)"));
        List<String> versionIdx = PostgresIt.app().jdbc().queryForList(
                "SELECT indexdef FROM pg_indexes WHERE tablename = 'tech_error_channel_note_versions'", String.class);
        assertThat(versionIdx).anySatisfy(d -> assertThat(d).containsIgnoringCase("UNIQUE").contains("(channel_id, version)"));
        List<String> imageIdx = PostgresIt.app().jdbc().queryForList(
                "SELECT indexdef FROM pg_indexes WHERE tablename = 'tech_error_channel_note_images'", String.class);
        assertThat(imageIdx).anySatisfy(d -> assertThat(d).contains("(channel_id)"));
    }

    @Test
    @DisplayName("metin: NUL ve eşsiz vekil karakterler atılarak kaydedilir (PostgreSQL TEXT NUL kabul etmez), Türkçe + emoji + 60 000"
            + " karakter aynen döner; hafif özet metni okumadan 'boş mu' der")
    void nulAndLongText() {
        TechErrorChannel ch = channel("Metin");
        try {
            String emoji = new String(Character.toChars(0x1F4CA));
            String raw = "Veri \u0000kaynağı: İÇ raporlama" + '\uD83D' + " aracı " + emoji + "\r\nşçğüöı ŞÇĞÜÖİ";
            var res = notes().save(ch.getId(), 0, raw, null);
            assertThat(res.changed()).isTrue();
            String expected = "Veri kaynağı: İÇ raporlama aracı " + emoji + "\nşçğüöı ŞÇĞÜÖİ";
            assertThat(notes().read(ch.getId()).body()).isEqualTo(expected);
            assertThat(notes().summary(ch.getId()).empty()).isFalse();

            String big = "ğ".repeat(TechErrorChannelNoteService.MAX_CHARS);
            var second = notes().save(ch.getId(), 1, big, null);
            assertThat(second.view().version()).isEqualTo(2L);
            assertThat(notes().read(ch.getId()).body()).isEqualTo(big);
            assertThat(notes().version(ch.getId(), 2).orElseThrow().getBodyMd()).hasSize(TechErrorChannelNoteService.MAX_CHARS);
            assertThat(notes().versions(ch.getId())).hasSize(2);

            notes().save(ch.getId(), 2, " \n ", null);
            var s = notes().summary(ch.getId());
            assertThat(s.exists()).isTrue();
            assertThat(s.empty()).isTrue();
            assertThat(s.version()).isEqualTo(3L);
        } finally {
            cleanup(ch.getId());
        }
    }

    @Test
    @DisplayName("bağ bayrakları + sınır sayımı gerçek SQL'de: kayıt anılanı bağlar, sayım bağlı + son 24 saat; görsel baytı aynen döner")
    void linkFlagsAndCounts() {
        TechErrorChannel ch = channel("Görsel");
        try {
            byte[] data = png();
            Long a = notes().upload(ch.getId(), data, "a.png", null).getId();
            Long b = notes().upload(ch.getId(), data, "b.png", null).getId();
            var res = notes().save(ch.getId(), 0, "![a](" + TechErrorNoteRefs.PREFIX + a + ")", null);
            assertThat(res.linkedImages()).isEqualTo(1);
            var view = notes().read(ch.getId());
            assertThat(view.images()).extracting(TechErrorChannelNoteImageRepository.Meta::getId).containsExactly(b, a);
            assertThat(view.images()).filteredOn(m -> Boolean.TRUE.equals(m.getLinked()))
                    .extracting(TechErrorChannelNoteImageRepository.Meta::getId).containsExactly(a);
            assertThat(view.imageBytes()).isEqualTo(2L * data.length);
            assertThat(notes().summary(ch.getId()).imageCount()).isEqualTo(1L);
            assertThat(notes().imageWithBytes(a).orElseThrow().getData()).isEqualTo(data);
            assertThatThrownBy(() -> notes().deleteImage(ch.getId(), a))
                    .isInstanceOfSatisfying(CodedConflictException.class, e -> assertThat(e.getCode()).isEqualTo("NOTE_IMAGE_IN_USE"));
            notes().deleteImage(ch.getId(), b);
            assertThat(count("tech_error_channel_note_images", ch.getId())).isEqualTo(1L);
        } finally {
            cleanup(ch.getId());
        }
    }

    @Test
    @DisplayName("kanal satırı FOR UPDATE: başka bir oturum kanal satırını kilitliyken not kaydı BEKLER, kilit kalkınca çalışır")
    void channelRowLockSerializesWrites() throws Exception {
        TechErrorChannel ch = channel("Kilit");
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try (Connection raw = PostgresIt.app().newSession()) {
            raw.setAutoCommit(false);
            try (PreparedStatement ps = raw.prepareStatement("SELECT id FROM tech_error_channels WHERE id = ? FOR UPDATE")) {
                ps.setLong(1, ch.getId());
                ps.executeQuery().close();
            }
            Future<TechErrorChannelNoteService.SaveResult> write = pool.submit(() -> notes().save(ch.getId(), 0, "kilitli yazım", null));
            assertThat(waitsOnLock("FOR UPDATE")).as("not kaydı kanal satırı kilidini beklemeli").isTrue();
            assertThat(write.isDone()).isFalse();
            assertThat(count("tech_error_channel_notes", ch.getId())).as("beklerken hiçbir şey yazılmadı").isZero();
            raw.commit();
            assertThat(write.get(30, TimeUnit.SECONDS).view().version()).isEqualTo(1L);
        } finally {
            pool.shutdownNow();
            cleanup(ch.getId());
        }
    }

    @Test
    @DisplayName("eşzamanlı ilk kayıt: N istek aynı anda sürüm 0 ile kaydeder — tam BİRİ yazar, diğerleri 409 CONCURRENT_MODIFICATION"
            + " (güncel sürüm 1); tek not, tek sürüm satırı; 500 yok")
    void concurrentFirstSave() throws Exception {
        for (int round = 0; round < 3; round++) {
            TechErrorChannel ch = channel("Yarış " + round);
            int n = 6;
            ExecutorService pool = Executors.newFixedThreadPool(n);
            try {
                CountDownLatch start = new CountDownLatch(1);
                List<Future<Object>> fs = new ArrayList<>();
                for (int i = 0; i < n; i++) {
                    String body = "yazan " + i;
                    Callable<Object> c = () -> {
                        start.await(10, TimeUnit.SECONDS);
                        try {
                            return notes().save(ch.getId(), 0, body, null);
                        } catch (CodedConflictException e) {
                            return e;
                        }
                    };
                    fs.add(pool.submit(c));
                }
                start.countDown();
                int saved = 0;
                int conflicts = 0;
                for (Future<Object> f : fs) {
                    Object o = f.get(60, TimeUnit.SECONDS);          // başka istisna (500) ExecutionException olarak düşer
                    if (o instanceof TechErrorChannelNoteService.SaveResult) {
                        saved++;
                    } else {
                        conflicts++;
                        CodedConflictException e = (CodedConflictException) o;
                        assertThat(e.getCode()).isEqualTo("CONCURRENT_MODIFICATION");
                        assertThat(e.getExtra()).containsEntry("current_version", 1L);
                    }
                }
                assertThat(saved).as("tur %d", round).isEqualTo(1);
                assertThat(conflicts).as("tur %d", round).isEqualTo(n - 1);
                assertThat(count("tech_error_channel_notes", ch.getId())).isEqualTo(1L);
                assertThat(count("tech_error_channel_note_versions", ch.getId())).isEqualTo(1L);
            } finally {
                pool.shutdownNow();
                cleanup(ch.getId());
            }
        }
    }

    @Test
    @DisplayName("tekil kısıt kaybı: kanal kilidini atlayan (ham) bir işlem not eklerken servis kaydı tekil kısıta takılır → 409"
            + " CONCURRENT_MODIFICATION (bozuk işlemde okuma yok — 25P02 / 500 değil); kazananın satırı kalır")
    void uniqueLossIsConflictNot500() throws Exception {
        TechErrorChannel ch = channel("Tekil");
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try (Connection raw = PostgresIt.app().newSession()) {
            raw.setAutoCommit(false);
            try (PreparedStatement ps = raw.prepareStatement(
                    "INSERT INTO tech_error_channel_notes (channel_id, body_md, version) VALUES (?, 'ham not', 1)")) {
                ps.setLong(1, ch.getId());
                ps.executeUpdate();
            }
            Future<TechErrorChannelNoteService.SaveResult> write = pool.submit(() -> notes().save(ch.getId(), 0, "servis notu", null));
            // Servisin satır eklemesi, ham işlemin henüz onaylanmamış aynı anahtarını (channel_id) bekler
            assertThat(waitsOnLock("tech_error_channel_notes")).as("ekleme tekil kısıtta beklemeli").isTrue();
            raw.commit();
            assertThatThrownBy(() -> write.get(30, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .cause().isInstanceOf(CodedConflictException.class)
                    .satisfies(c -> {
                        assertThat(((CodedConflictException) c).getCode()).isEqualTo("CONCURRENT_MODIFICATION");
                        assertThat(((CodedConflictException) c).getExtra()).as("bozuk işlemde okuma yapılmadı").isNull();
                    });
            assertThat(notes().read(ch.getId()).body()).isEqualTo("ham not");
            assertThat(count("tech_error_channel_note_versions", ch.getId())).isZero();
            // Uç, güncel durumu işlemin DIŞINDA bu özetle ekler
            assertThat(notes().summary(ch.getId()).version()).isEqualTo(1L);
        } finally {
            pool.shutdownNow();
            cleanup(ch.getId());
        }
    }

    @Test
    @DisplayName("yükleme / kayıt ile kanal silme aynı anda: kilitlenme (40P01) yok — yazım ya silmeden önce biter ya 404 alır; kanalın"
            + " notu, görselleri ve sürümleri geride KALMAZ (rapor kilidi → kanal kilidi sırası)")
    void uploadVersusChannelDeleteNoDeadlock() throws Exception {
        byte[] data = png();
        for (int round = 0; round < 8; round++) {
            TechErrorChannel ch = channel("Silme " + round);
            Long id = ch.getId();
            var period = reports().defaultPeriod();
            reports().createReport(ch, period.getYear(), period.getMonthValue(), null);     // silme rapor satırlarını da kilitlesin
            notes().save(id, 0, "ilk", null);
            ExecutorService pool = Executors.newFixedThreadPool(3);
            try {
                CountDownLatch start = new CountDownLatch(1);
                Callable<Object> upload = () -> {
                    start.await(10, TimeUnit.SECONDS);
                    try {
                        return notes().upload(id, data, "y.png", null).getId();
                    } catch (CodedNotFoundException e) {
                        return e;
                    }
                };
                Callable<Object> save = () -> {
                    start.await(10, TimeUnit.SECONDS);
                    try {
                        return notes().save(id, 1, "ikinci", null);
                    } catch (CodedNotFoundException | CodedConflictException e) {
                        return e;
                    }
                };
                Callable<Object> delete = () -> {
                    start.await(10, TimeUnit.SECONDS);
                    return reports().deleteChannel(id);
                };
                List<Future<Object>> fs = List.of(pool.submit(upload), pool.submit(save), pool.submit(delete));
                start.countDown();
                for (Future<Object> f : fs) {
                    try {
                        Object o = f.get(60, TimeUnit.SECONDS);
                        if (o instanceof CodedNotFoundException e) assertThat(e.getCode()).isEqualTo("CHANNEL_NOT_FOUND");
                    } catch (ExecutionException e) {
                        assertThat(e.getCause()).as("tur %d: kilitlenme / kilit hatası olmamalı", round)
                                .isNotInstanceOf(PessimisticLockingFailureException.class);
                        throw e;
                    }
                }
                assertThat(PostgresIt.app().bean(TechErrorChannelRepository.class).existsById(id)).isFalse();
                assertThat(count("tech_error_channel_notes", id)).as("tur %d", round).isZero();
                assertThat(count("tech_error_channel_note_images", id)).as("tur %d", round).isZero();
                assertThat(count("tech_error_channel_note_versions", id)).as("tur %d", round).isZero();
            } finally {
                pool.shutdownNow();
                cleanup(id);
            }
        }
    }

    @Test
    @DisplayName("saklama kuralları gerçek PostgreSQL'de: bağsız görsel kuralı timestamptz sütununda CAST'li kesimle çalışır (bağsız +"
            + " eski silinir, bağlı ve yeni kalır); öksüz kurallarının SQL'i geçerli")
    void retentionSqlRuns() {
        TechErrorChannel ch = channel("Saklama");
        Long id = ch.getId();
        try {
            byte[] data = png();
            Long linkedOld = notes().upload(id, data, "bagli.png", null).getId();
            Long unlinkedOld = notes().upload(id, data, "bagsiz-eski.png", null).getId();
            Long unlinkedNew = notes().upload(id, data, "bagsiz-yeni.png", null).getId();
            notes().save(id, 0, "![x](" + TechErrorNoteRefs.PREFIX + linkedOld + ")", null);
            var jdbc = PostgresIt.app().jdbc();
            // 45 gün öncesine çek (bağlı + bir bağsız); üçüncüsü bugünün damgasıyla kalır
            jdbc.update("UPDATE tech_error_channel_note_images SET link_changed_at = link_changed_at - INTERVAL '45 days' "
                    + "WHERE id IN (?, ?)", linkedOld, unlinkedOld);
            RetentionPolicy p = RetentionCatalog.byId(TechErrorChannelNoteService.UNLINKED_POLICY_ID).orElseThrow();
            String cutoff = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC)
                    .format(Instant.now().minus(30, ChronoUnit.DAYS));
            // Paylaşılan test veritabanında başka kanalların satırına dokunmamak için kuralın WHERE gövdesi bu kanala daraltılır
            String scoped = " AND channel_id = " + id;
            assertThat(jdbc.queryForObject(p.countSql() + scoped, Long.class, cutoff)).isEqualTo(1L);
            assertThat(jdbc.update(p.deleteSql() + scoped, cutoff)).isEqualTo(1);
            List<Long> left = jdbc.queryForList("SELECT id FROM tech_error_channel_note_images WHERE channel_id = ? ORDER BY id",
                    Long.class, id);
            assertThat(left).containsExactly(linkedOld, unlinkedNew).doesNotContain(unlinkedOld);
            // Kuralın kendi (daraltılmamış) sayımı ve öksüz kurallarının sayımı hata vermeden koşar
            assertThat(jdbc.queryForObject(p.countSql(), Long.class, cutoff)).isNotNull();
            for (String orphan : List.of("tech-error-channel-notes-orphan", "tech-error-channel-note-images-orphan",
                    "tech-error-channel-note-versions-orphan")) {
                RetentionPolicy o = RetentionCatalog.byId(orphan).orElseThrow();
                assertThat(jdbc.queryForObject(o.countSql(), Long.class)).as(orphan).isNotNull();
            }
        } finally {
            cleanup(id);
        }
    }
}
