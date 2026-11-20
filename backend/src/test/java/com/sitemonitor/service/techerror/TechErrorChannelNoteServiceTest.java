package com.sitemonitor.service.techerror;

import com.sitemonitor.config.GlobalExceptionHandler.CodedConflictException;
import com.sitemonitor.config.GlobalExceptionHandler.CodedNotFoundException;
import com.sitemonitor.config.GlobalExceptionHandler.FieldValidationException;
import com.sitemonitor.model.AppUser;
import com.sitemonitor.model.TechErrorChannel;
import com.sitemonitor.model.TechErrorChannelNoteImage;
import com.sitemonitor.repository.AppUserRepository;
import com.sitemonitor.repository.TechErrorChannelNoteImageRepository;
import com.sitemonitor.repository.TechErrorChannelNoteRepository;
import com.sitemonitor.repository.TechErrorChannelNoteVersionRepository;
import com.sitemonitor.repository.TechErrorChannelRepository;
import com.sitemonitor.repository.TechErrorReportEventRepository;
import com.sitemonitor.repository.TechErrorReportImageRepository;
import com.sitemonitor.repository.TechErrorReportRepository;
import com.sitemonitor.repository.TechErrorTemplateVersionRepository;
import com.sitemonitor.service.retention.RetentionCatalog;
import com.sitemonitor.service.retention.RetentionPolicy;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * KANAL NOTLARI (2026-11-20) — H2'de GERÇEK SQL. {@code NOT_SUPPORTED}: uygulamadaki gibi işlemsiz çağıran; kanal satırı
 * kilidi, toplu bağ bayrağı cümleleri ve {@code @Modifying} sorguları kendi işlemlerinde çalışmalı.
 */
@DataJpaTest
@Import({TechErrorChannelNoteService.class, TechErrorReportService.class, TechErrorHistoryService.class})
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class TechErrorChannelNoteServiceTest {

    private static final Instant T0 = Instant.parse("2026-10-13T09:00:00Z");

    @Autowired TechErrorChannelNoteService notes;
    @Autowired TechErrorReportService service;
    @Autowired TechErrorChannelRepository channels;
    @Autowired TechErrorChannelNoteRepository noteRepo;
    @Autowired TechErrorChannelNoteImageRepository imageRepo;
    @Autowired TechErrorChannelNoteVersionRepository versionRepo;
    @Autowired TechErrorReportRepository reports;
    @Autowired TechErrorReportImageRepository reportImages;
    @Autowired TechErrorReportEventRepository events;
    @Autowired TechErrorTemplateVersionRepository templateVersions;
    @Autowired AppUserRepository users;
    @Autowired PlatformTransactionManager txManager;
    @Autowired EntityManager em;
    @Autowired JdbcTemplate jdbc;

    private Long ayse;
    private Long chA;
    private Long chB;

    @BeforeEach
    void clean() {
        imageRepo.deleteAll();
        versionRepo.deleteAll();
        noteRepo.deleteAll();
        events.deleteAll();
        templateVersions.deleteAll();
        reportImages.deleteAll();
        reports.deleteAll();
        channels.deleteAll();
        users.deleteAll();
        service.useClock(Clock.fixed(T0, ZoneOffset.UTC));
        at(T0);
        notes.useLimits(TechErrorChannelNoteService.MAX_IMAGES, TechErrorChannelNoteService.MAX_TOTAL_BYTES,
                TechErrorChannelNoteService.MAX_ALL_BYTES);
        AppUser u = new AppUser();
        u.setUsername("n60146");
        u.setDisplayName("Ayşe Yılmaz (Dijital Kanallar / Kıdemli Uzman)");
        u.setActive(true);
        ayse = users.save(u).getId();
        chA = channel("Mobil").getId();
        chB = channel("İnternet Şubesi").getId();
    }

    private void at(Instant t) {
        notes.useClock(Clock.fixed(t, ZoneOffset.UTC));
    }

    private TechErrorChannel channel(String name) {
        return service.createChannel(1L, name, null, TechErrorTemplate.defaults("tr"), 7L);
    }

    private static byte[] png() {
        try {
            return TechErrorImageValidatorTest.png(64, 48);
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Geçerli PNG başlığı + dolgu: doğrulayıcı yalnız başlığı okur — bayt sınırları megabaytlarca veri üretmeden sınanır. */
    private static byte[] pngOfSize(int bytes) {
        return Arrays.copyOf(TechErrorImageValidatorTest.pngHeaderOnly(64, 64), bytes);
    }

    private Long upload(Long channelId) {
        return notes.upload(channelId, png(), "ekran.png", ayse).getId();
    }

    private TechErrorChannelNoteImage image(Long id) {
        return imageRepo.findById(id).orElseThrow();
    }

    private static String ref(Long id) {
        return "![görsel](" + TechErrorNoteRefs.PREFIX + id + ")";
    }

    private static CodedConflictException conflictOf(Runnable r) {
        return catchThrowableOfType(CodedConflictException.class, r::run);
    }

    // ── Kayıt ────────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("ilk kayıt: not yokken sürüm 0 / boş gövde; kayıt sürüm 1, oluşturan + kaydeden adı, bir sürüm satırı, hafif özet")
    void firstSave() {
        var empty = notes.read(chA);
        assertThat(empty.exists()).isFalse();
        assertThat(empty.version()).isZero();
        assertThat(empty.body()).isEmpty();
        assertThat(empty.images()).isEmpty();
        assertThat(empty.imageBytes()).isZero();
        assertThat(notes.summary(chA)).isEqualTo(TechErrorChannelNoteService.Summary.none());

        var res = notes.save(chA, 0, "# Kaynak\nVeri Qlik panosundan alınır.", ayse);
        assertThat(res.changed()).isTrue();
        assertThat(res.linkedImages()).isZero();
        assertThat(res.view().exists()).isTrue();
        assertThat(res.view().version()).isEqualTo(1L);
        assertThat(res.view().body()).isEqualTo("# Kaynak\nVeri Qlik panosundan alınır.");
        assertThat(res.view().note().getCreatedAt()).isEqualTo(T0);
        assertThat(res.view().note().getUpdatedAt()).isEqualTo(T0);
        assertThat(res.view().note().getCreatedByName()).isEqualTo("Ayşe Yılmaz (Dijital Kanallar / Kıdemli Uzman)");
        assertThat(res.view().note().getUpdatedByName()).isEqualTo("Ayşe Yılmaz (Dijital Kanallar / Kıdemli Uzman)");

        var read = notes.read(chA);
        assertThat(read.version()).isEqualTo(1L);
        assertThat(read.body()).isEqualTo("# Kaynak\nVeri Qlik panosundan alınır.");
        var s = notes.summary(chA);
        assertThat(s.exists()).isTrue();
        assertThat(s.empty()).isFalse();
        assertThat(s.version()).isEqualTo(1L);
        assertThat(s.updatedAt()).isEqualTo(T0);
        assertThat(s.updatedByName()).startsWith("Ayşe Yılmaz");
        assertThat(s.imageCount()).isZero();

        var vs = notes.versions(chA);
        assertThat(vs).hasSize(1);
        assertThat(vs.get(0).getVersion()).isEqualTo(1L);
        assertThat(vs.get(0).getCharCount()).isEqualTo("# Kaynak\nVeri Qlik panosundan alınır.".length());
        assertThat(vs.get(0).getImageCount()).isZero();
        assertThat(vs.get(0).getSavedByName()).startsWith("Ayşe Yılmaz");
        assertThat(notes.version(chA, 1).orElseThrow().getBodyMd()).isEqualTo("# Kaynak\nVeri Qlik panosundan alınır.");
        // diğer kanal etkilenmez
        assertThat(notes.read(chB).exists()).isFalse();
    }

    @Test
    @DisplayName("sürüm çakışması: eski sürümle kayıt 409 CONCURRENT_MODIFICATION (güncel sürüm, son kaydeden, zaman) ve hiçbir şey yazmaz")
    void versionConflict() {
        // not yokken 0 dışında bir sürüm → güncel sürüm 0, kaydeden yok
        CodedConflictException none = conflictOf(() -> notes.save(chA, 5, "x", ayse));
        assertThat(none.getCode()).isEqualTo("CONCURRENT_MODIFICATION");
        assertThat(none.getExtra()).containsEntry("current_version", 0L).containsEntry("updated_by_name", null)
                .containsEntry("updated_at", null);
        assertThat(noteRepo.count()).isZero();

        notes.save(chA, 0, "birinci", ayse);
        at(T0.plusSeconds(60));
        CodedConflictException stale = conflictOf(() -> notes.save(chA, 0, "ikinci (eski sürümle)", ayse));
        assertThat(stale.getCode()).isEqualTo("CONCURRENT_MODIFICATION");
        assertThat(stale.getExtra()).containsEntry("current_version", 1L)
                .containsEntry("updated_by_name", "Ayşe Yılmaz (Dijital Kanallar / Kıdemli Uzman)")
                .containsEntry("updated_at", T0.toString());
        assertThat(stale.getMessage()).contains("Ayşe Yılmaz");
        assertThat(notes.read(chA).body()).isEqualTo("birinci");
        assertThat(versionRepo.countByChannelId(chA)).isEqualTo(1L);

        var ok = notes.save(chA, 1, "ikinci", ayse);
        assertThat(ok.view().version()).isEqualTo(2L);
        assertThat(ok.view().note().getCreatedAt()).isEqualTo(T0);
        assertThat(ok.view().note().getUpdatedAt()).isEqualTo(T0.plusSeconds(60));
        assertThat(versionRepo.countByChannelId(chA)).isEqualTo(2L);
    }

    @Test
    @DisplayName("değişmeyen gövde: hiçbir şey yazılmaz (sürüm, zaman, sürüm satırı aynı); not yokken boş gövde satır açmaz")
    void noOpSaveWritesNothing() {
        var blank = notes.save(chA, 0, "  \n\t ", ayse);
        assertThat(blank.changed()).isFalse();
        assertThat(blank.view().exists()).isFalse();
        assertThat(noteRepo.count()).isZero();
        assertThat(versionRepo.count()).isZero();

        notes.save(chA, 0, "satır 1\nsatır 2", ayse);
        at(T0.plusSeconds(120));
        var same = notes.save(chA, 1, "satır 1\nsatır 2", ayse);
        assertThat(same.changed()).isFalse();
        assertThat(same.view().version()).isEqualTo(1L);
        // CRLF ile gelen aynı metin de değişiklik değildir
        var crlf = notes.save(chA, 1, "satır 1\r\nsatır 2", ayse);
        assertThat(crlf.changed()).isFalse();
        var read = notes.read(chA);
        assertThat(read.version()).isEqualTo(1L);
        assertThat(read.note().getUpdatedAt()).isEqualTo(T0);
        assertThat(versionRepo.countByChannelId(chA)).isEqualTo(1L);

        // var olan notu boşaltmak GERÇEK değişikliktir: satır kalır, özet "boş" der
        var cleared = notes.save(chA, 1, "   ", ayse);
        assertThat(cleared.changed()).isTrue();
        assertThat(cleared.view().version()).isEqualTo(2L);
        assertThat(cleared.view().body()).isEmpty();
        var s = notes.summary(chA);
        assertThat(s.exists()).isTrue();
        assertThat(s.empty()).isTrue();
    }

    @Test
    @DisplayName("yanıtı kaybolan kaydın yeniden denemesi: eski sürüm + kayıtlı metnin aynısı çakışma DEĞİL — değişiklik yok, güncel sürüm döner")
    void staleRetryOfSameBodyIsNoOp() {
        notes.save(chA, 0, "birinci", ayse);
        notes.save(chA, 1, "ikinci", ayse);                       // yanıtı istemciye ulaşmadı
        at(T0.plusSeconds(30));
        var retry = notes.save(chA, 1, "ikinci", ayse);           // istemci eski sürümle aynı metni yeniden gönderir
        assertThat(retry.changed()).isFalse();
        assertThat(retry.view().version()).isEqualTo(2L);
        assertThat(versionRepo.countByChannelId(chA)).isEqualTo(2L);
        // not metni / görsel baytı toString'e (günlük, hata iletisi) girmez
        assertThat(retry.view().note().toString()).doesNotContain("ikinci");
        assertThat(versionRepo.findAll().get(0).toString()).doesNotContain("birinci");
        // farklı metin yine çakışmadır
        assertThat(conflictOf(() -> notes.save(chA, 1, "üçüncü", ayse)).getCode()).isEqualTo("CONCURRENT_MODIFICATION");
    }

    @Test
    @DisplayName("önceden anılan kimlik: kayıtlı metin henüz yüklenmemiş bir görsel kimliğini anıyorsa o kimlikle yüklenen görsel BAĞLI doğar;"
            + " bayrağı kaçmış görsel değişmeyen kayıtta düzelir")
    void preReferencedImageIsLinked() {
        Long first = upload(chA);
        Long next = first + 1;                                   // kimlikler sıralı (H2 / PostgreSQL IDENTITY)
        notes.save(chA, 0, "eski " + ref(first) + " ve gelecek " + ref(next), ayse);
        assertThat(image(first).isLinked()).isTrue();
        TechErrorChannelNoteImage img = notes.upload(chA, png(), "sonra.png", ayse);
        assertThat(img.getId()).isEqualTo(next);
        assertThat(img.isLinked()).as("dönen satır bağlı").isTrue();
        assertThat(image(next).isLinked()).as("saklanan bayrak bağlı").isTrue();

        // Bayrağı elle düşürülmüş (yarışta kaçmış) görsel: aynı metinle kayıt bir şey YAZMAZ ama bayrağı düzeltir
        jdbc.update("UPDATE tech_error_channel_note_images SET linked = FALSE WHERE id = ?", next);
        var same = notes.save(chA, 1, "eski " + ref(first) + " ve gelecek " + ref(next), ayse);
        assertThat(same.changed()).isFalse();
        assertThat(image(next).isLinked()).isTrue();
        assertThat(versionRepo.countByChannelId(chA)).isEqualTo(1L);
    }

    @Test
    @DisplayName("gövde sınırı: 60 000 birim kabul, bir fazlası 400 body; sınırın iki katını aşan ham metin taranmadan reddedilir; null 400")
    void bodyCap() {
        int max = TechErrorChannelNoteService.MAX_CHARS;
        assertThat(max).isEqualTo(60_000);
        var ok = notes.save(chA, 0, "a".repeat(max), ayse);
        assertThat(ok.view().body()).hasSize(max);
        assertThat(notes.read(chA).body()).hasSize(max);

        assertThatThrownBy(() -> notes.save(chA, 1, "a".repeat(max + 1), ayse))
                .isInstanceOfSatisfying(FieldValidationException.class, e -> assertThat(e.getFields()).containsOnlyKeys("body"));
        assertThatThrownBy(() -> notes.save(chA, 1, "a".repeat(2 * max + 1), ayse))
                .isInstanceOfSatisfying(FieldValidationException.class, e -> assertThat(e.getFields()).containsOnlyKeys("body"));
        assertThatThrownBy(() -> notes.save(chA, 1, null, ayse))
                .isInstanceOfSatisfying(FieldValidationException.class, e -> assertThat(e.getFields()).containsOnlyKeys("body"));
        // CRLF'li 90 000 ham birim normalleşince tam 60 000 → kabul (uzunluk normalleşmiş metinde ölçülür)
        var crlf = notes.save(chA, 1, "x\r\n".repeat(max / 2), ayse);
        assertThat(crlf.view().body()).hasSize(max).doesNotContain("\r");
        assertThat(notes.read(chA).version()).isEqualTo(2L);
    }

    @Test
    @DisplayName("normalleştirme: CRLF / CR → LF, NUL ve eşsiz vekil karakterler atılır, eşli vekil (emoji) korunur")
    void normalisation() {
        String emoji = new String(Character.toChars(0x1F600));
        String raw = "a\r\nb\rc\u0000d" + '\uD83D' + "e" + '\uDE00' + "f" + emoji + "\n";
        assertThat(TechErrorChannelNoteService.normalizeBody(raw)).isEqualTo("a\nb\ncdef" + emoji + "\n");
        var res = notes.save(chA, 0, raw, ayse);
        assertThat(res.view().body()).isEqualTo("a\nb\ncdef" + emoji + "\n");
        assertThat(notes.read(chA).body()).isEqualTo("a\nb\ncdef" + emoji + "\n");
        // sonda yarım kalan üst vekil ve yalnız NUL
        assertThat(TechErrorChannelNoteService.normalizeBody("x" + '\uD83D')).isEqualTo("x");
        assertThat(TechErrorChannelNoteService.normalizeBody("\u0000\u0000")).isEmpty();
        assertThat(TechErrorChannelNoteService.normalizeBody("")).isEmpty();
    }

    @Test
    @DisplayName("bağ bayrakları: kayıt gövdede anılanı bağlar, anılmayanın bağını düşürür; damga YALNIZ bayrak değişince yenilenir;"
            + " bilinmeyen / başka kanalın kimliği hiçbir şeyi bağlamaz")
    void linkedFlagsFlipOnSave() {
        Long i1 = upload(chA);
        Long i2 = upload(chA);
        Long other = upload(chB);
        assertThat(image(i1).isLinked()).isFalse();
        assertThat(image(i1).getLinkChangedAt()).isEqualTo(T0);

        Instant t1 = T0.plusSeconds(600);
        at(t1);
        var r1 = notes.save(chA, 0, "Adım 1 " + ref(i1) + " yabancı " + ref(other) + " yok " + ref(999_999L), ayse);
        assertThat(r1.linkedImages()).isEqualTo(1);
        assertThat(image(i1).isLinked()).isTrue();
        assertThat(image(i1).getLinkChangedAt()).isEqualTo(t1);
        assertThat(image(i2).isLinked()).isFalse();
        assertThat(image(i2).getLinkChangedAt()).as("bayrağı değişmeyenin damgası aynı kalır").isEqualTo(T0);
        assertThat(image(other).isLinked()).as("başka kanalın görseli bağlanmaz").isFalse();
        assertThat(image(other).getLinkChangedAt()).isEqualTo(T0);
        assertThat(notes.summary(chA).imageCount()).isEqualTo(1L);
        assertThat(notes.versions(chA).get(0).getImageCount()).isEqualTo(1);

        Instant t2 = T0.plusSeconds(1200);
        at(t2);
        var r2 = notes.save(chA, 1, "Adım 2 " + ref(i2), ayse);
        assertThat(r2.linkedImages()).isEqualTo(1);
        assertThat(image(i1).isLinked()).isFalse();
        assertThat(image(i1).getLinkChangedAt()).isEqualTo(t2);
        assertThat(image(i2).isLinked()).isTrue();
        assertThat(image(i2).getLinkChangedAt()).isEqualTo(t2);

        Instant t3 = T0.plusSeconds(1800);
        at(t3);
        var r3 = notes.save(chA, 2, ref(i1) + "\n" + ref(i2) + "\n" + ref(i2), ayse);
        assertThat(r3.linkedImages()).isEqualTo(2);
        assertThat(image(i1).getLinkChangedAt()).isEqualTo(t3);
        assertThat(image(i2).isLinked()).isTrue();
        assertThat(image(i2).getLinkChangedAt()).as("zaten bağlıydı — damga yenilenmez").isEqualTo(t2);
        // görünüm: en yeni önce, bağ bayraklarıyla
        var view = notes.read(chA);
        assertThat(view.images()).extracting(TechErrorChannelNoteImageRepository.Meta::getId).containsExactly(i2, i1);
        assertThat(view.images()).allSatisfy(m -> assertThat(m.getLinked()).isTrue());
    }

    @Test
    @DisplayName("sürüm budama: 25 kayıttan en yeni 20 sürüm kalır; budanan sürüm bulunmaz")
    void versionPruningAtTwenty() {
        for (int i = 0; i < 25; i++) {
            at(T0.plusSeconds(i));
            notes.save(chA, i, "sürüm " + (i + 1), ayse);
        }
        assertThat(notes.read(chA).version()).isEqualTo(25L);
        assertThat(versionRepo.countByChannelId(chA)).isEqualTo(TechErrorChannelNoteService.MAX_VERSIONS).isEqualTo(20L);
        var vs = notes.versions(chA);
        assertThat(vs).hasSize(20);
        assertThat(vs.get(0).getVersion()).isEqualTo(25L);
        assertThat(vs.get(19).getVersion()).isEqualTo(6L);
        assertThat(notes.version(chA, 5)).isEmpty();
        assertThat(notes.version(chA, 6).orElseThrow().getBodyMd()).isEqualTo("sürüm 6");
        assertThat(notes.version(chA, 25).orElseThrow().getBodyMd()).isEqualTo("sürüm 25");
        // başka kanalın sürümleri budanmaz
        notes.save(chB, 0, "b", ayse);
        notes.save(chA, 25, "sürüm 26", ayse);
        assertThat(versionRepo.countByChannelId(chB)).isEqualTo(1L);
        assertThat(versionRepo.countByChannelId(chA)).isEqualTo(20L);
    }

    // ── Görseller ────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("görsel adedi: 40 yükleme kabul, 41.'si 409 NOTE_IMAGE_LIMIT; 24 saat sonra kullanılmayan yüklemeler sayılmaz")
    void imageCountCap() {
        assertThat(TechErrorChannelNoteService.MAX_IMAGES).isEqualTo(40);
        byte[] data = png();
        for (int i = 0; i < 40; i++) notes.upload(chA, data, "g" + i + ".png", ayse);
        CodedConflictException full = conflictOf(() -> notes.upload(chA, data, "fazla.png", ayse));
        assertThat(full.getCode()).isEqualTo("NOTE_IMAGE_LIMIT");
        assertThat(imageRepo.linkRows(chA)).hasSize(40);
        // başka kanalın sınırı ayrı
        assertThat(notes.upload(chB, data, "b.png", ayse).getId()).isNotNull();

        at(T0.plus(25, ChronoUnit.HOURS));
        assertThat(notes.upload(chA, data, "ertesi-gun.png", ayse).isLinked()).isFalse();
        assertThat(notes.read(chA).imageBytes()).as("yalnız son 24 saatin yüklemesi sayılır").isEqualTo(data.length);
    }

    @Test
    @DisplayName("sayım kuralı: notta kullanılan görsel 24 saatten sonra da sayılır, kullanılmayan sayılmaz")
    void linkedImagesKeepCounting() {
        notes.useLimits(3, TechErrorChannelNoteService.MAX_TOTAL_BYTES, TechErrorChannelNoteService.MAX_ALL_BYTES);
        Long a = upload(chA);
        Long b = upload(chA);
        upload(chA);
        assertThat(conflictOf(() -> upload(chA)).getCode()).isEqualTo("NOTE_IMAGE_LIMIT");
        notes.save(chA, 0, ref(a) + ref(b), ayse);

        at(T0.plus(25, ChronoUnit.HOURS));
        upload(chA);                                                        // 2 bağlı + 1 yeni = 3
        assertThat(conflictOf(() -> upload(chA)).getCode()).isEqualTo("NOTE_IMAGE_LIMIT");
    }

    @Test
    @DisplayName("bayt sınırı: sayılan baytlar + yeni görsel sınırı aşarsa 409 NOTE_IMAGE_TOTAL_LIMIT (sınırın kendisi kabul)")
    void imageBytesCap() {
        assertThat(TechErrorChannelNoteService.MAX_TOTAL_BYTES).isEqualTo(40L * 1024 * 1024);
        notes.useLimits(40, 3000, 100_000);
        for (int i = 0; i < 3; i++) notes.upload(chA, pngOfSize(1000), "p" + i + ".png", ayse);
        assertThat(notes.read(chA).imageBytes()).isEqualTo(3000L);
        CodedConflictException over = conflictOf(() -> notes.upload(chA, pngOfSize(100), "fazla.png", ayse));
        assertThat(over.getCode()).isEqualTo("NOTE_IMAGE_TOTAL_LIMIT");
        assertThat(imageRepo.linkRows(chA)).hasSize(3);
    }

    @Test
    @DisplayName("emniyet sınırı: sayılmayan (eski, kullanılmayan) satırlar da kanalın toplamına girer — 409 NOTE_IMAGE_TOTAL_LIMIT")
    void allBytesBackstop() {
        assertThat(TechErrorChannelNoteService.MAX_ALL_BYTES).isEqualTo(120L * 1024 * 1024);
        notes.useLimits(40, 3000, 5000);
        for (int i = 0; i < 3; i++) notes.upload(chA, pngOfSize(1000), "eski" + i + ".png", ayse);
        at(T0.plus(25, ChronoUnit.HOURS));
        assertThat(notes.read(chA).imageBytes()).as("eski yüklemeler sayılmıyor").isZero();
        notes.upload(chA, pngOfSize(1000), "y1.png", ayse);
        notes.upload(chA, pngOfSize(1000), "y2.png", ayse);                 // toplam 5000 = sınır
        CodedConflictException over = conflictOf(() -> notes.upload(chA, pngOfSize(1000), "y3.png", ayse));
        assertThat(over.getCode()).isEqualTo("NOTE_IMAGE_TOTAL_LIMIT");
        assertThat(over.getMessage()).contains("120 MB");
        assertThat(imageRepo.totalBytes(chA)).isEqualTo(5000L);
    }

    @Test
    @DisplayName("yükleme: doğrulama rapor görselleriyle aynı (PNG / JPEG değilse 400 file); satır bağsız doğar, ad temizlenir")
    void uploadValidationAndRow() {
        assertThatThrownBy(() -> notes.upload(chA, "bu bir görsel değil".getBytes(), "a.png", ayse))
                .isInstanceOfSatisfying(FieldValidationException.class, e -> assertThat(e.getFields()).containsOnlyKeys("file"));
        assertThat(imageRepo.count()).isZero();
        TechErrorChannelNoteImage img = notes.upload(chA, png(), "C:\\Users\\ayse\\Masaüstü\\ekran görüntüsü.png", ayse);
        assertThat(img.getContentType()).isEqualTo("image/png");
        assertThat(img.getWidth()).isEqualTo(64);
        assertThat(img.getHeight()).isEqualTo(48);
        assertThat(img.getSizeBytes()).isEqualTo(png().length);
        assertThat(img.isLinked()).isFalse();
        assertThat(img.getUploadedAt()).isEqualTo(T0);
        assertThat(img.getFileName()).doesNotContain("\\").doesNotContain("/");
        var meta = notes.imageMeta(img.getId()).orElseThrow();
        assertThat(meta.getChannelId()).isEqualTo(chA);
        assertThat(meta.getLinked()).isFalse();
        assertThat(notes.imageWithBytes(img.getId()).orElseThrow().getData()).isEqualTo(png());
        assertThat(notes.imageMeta(null)).isEmpty();
        assertThat(notes.imageMeta(987_654L)).isEmpty();
    }

    @Test
    @DisplayName("görsel silme: kayıtlı not anıyorsa 409 NOTE_IMAGE_IN_USE; anmıyorsa silinir; başka kanalın / olmayan görsel 404")
    void deleteImageRules() {
        Long used = upload(chA);
        Long free = upload(chA);
        Long foreign = upload(chB);
        notes.save(chA, 0, "kullanılan: " + ref(used), ayse);

        CodedConflictException inUse = conflictOf(() -> notes.deleteImage(chA, used));
        assertThat(inUse.getCode()).isEqualTo("NOTE_IMAGE_IN_USE");
        assertThat(imageRepo.findById(used)).isPresent();

        assertThatThrownBy(() -> notes.deleteImage(chA, foreign))
                .isInstanceOfSatisfying(CodedNotFoundException.class, e -> assertThat(e.getCode()).isEqualTo("NOTE_IMAGE_NOT_FOUND"));
        assertThat(imageRepo.findById(foreign)).as("başka kanalın görseli silinmez").isPresent();
        assertThatThrownBy(() -> notes.deleteImage(chA, 987_654L))
                .isInstanceOfSatisfying(CodedNotFoundException.class, e -> assertThat(e.getCode()).isEqualTo("NOTE_IMAGE_NOT_FOUND"));

        var meta = notes.deleteImage(chA, free);
        assertThat(meta.getId()).isEqualTo(free);
        assertThat(imageRepo.findById(free)).isEmpty();
        // nottan çıkarılıp kaydedilince silinebilir
        notes.save(chA, 1, "artık görsel yok", ayse);
        notes.deleteImage(chA, used);
        assertThat(imageRepo.linkRows(chA)).isEmpty();
    }

    @Test
    @DisplayName("olmayan kanal: kayıt, yükleme ve görsel silme 404 CHANNEL_NOT_FOUND — hiçbir satır yazılmaz")
    void missingChannel() {
        long gone = 987_654L;
        assertThatThrownBy(() -> notes.save(gone, 0, "x", ayse))
                .isInstanceOfSatisfying(CodedNotFoundException.class, e -> assertThat(e.getCode()).isEqualTo("CHANNEL_NOT_FOUND"));
        assertThatThrownBy(() -> notes.upload(gone, png(), "a.png", ayse))
                .isInstanceOfSatisfying(CodedNotFoundException.class, e -> assertThat(e.getCode()).isEqualTo("CHANNEL_NOT_FOUND"));
        assertThatThrownBy(() -> notes.deleteImage(gone, 1L))
                .isInstanceOfSatisfying(CodedNotFoundException.class, e -> assertThat(e.getCode()).isEqualTo("CHANNEL_NOT_FOUND"));
        assertThat(noteRepo.count()).isZero();
        assertThat(imageRepo.count()).isZero();
        assertThat(notes.channel(gone)).isEmpty();
        assertThat(notes.channel(null)).isEmpty();
        assertThat(notes.channel(chA)).isPresent();
    }

    // ── Kanal silme + saklama ────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("kanal silme: not + görseller + sürümler aynı işlemde silinir; diğer kanalın kayıtları kalır")
    void channelDeleteRemovesEverything() {
        Long a1 = upload(chA);
        upload(chA);
        Long b1 = upload(chB);
        notes.save(chA, 0, "A notu " + ref(a1), ayse);
        notes.save(chA, 1, "A notu 2", ayse);
        notes.save(chB, 0, "B notu " + ref(b1), ayse);

        assertThat(service.deleteChannel(chA)).isZero();
        assertThat(channels.findById(chA)).isEmpty();
        assertThat(noteRepo.findByChannelId(chA)).isEmpty();
        assertThat(imageRepo.linkRows(chA)).isEmpty();
        assertThat(versionRepo.countByChannelId(chA)).isZero();
        assertThat(notes.read(chB).body()).startsWith("B notu");
        assertThat(imageRepo.linkRows(chB)).hasSize(1);
        assertThat(versionRepo.countByChannelId(chB)).isEqualTo(1L);
        // silinen kanala geç gelen yazım satır bırakmaz
        assertThatThrownBy(() -> notes.save(chA, 0, "geç kalan", ayse)).isInstanceOf(CodedNotFoundException.class);
        assertThat(noteRepo.findByChannelId(chA)).isEmpty();
    }

    @Test
    @DisplayName("saklama kuralları (H2'de gerçek SQL): bağsız + süresi dolmuş görsel silinir, bağlı ve yeni olan kalır; kanalı"
            + " silinmiş not / görsel / sürüm öksüz kurallarıyla temizlenir")
    void retentionRules() {
        RetentionPolicy unlinked = RetentionCatalog.byId(TechErrorChannelNoteService.UNLINKED_POLICY_ID).orElseThrow();
        assertThat(unlinked.mode()).isEqualTo(RetentionPolicy.Mode.AGE);
        assertThat(unlinked.defaultDays()).isEqualTo(TechErrorChannelNoteService.UNLINKED_KEEP_DAYS).isEqualTo(30);
        assertThat(unlinked.minDays()).isEqualTo(7);
        assertThat(unlinked.table()).isEqualTo("tech_error_channel_note_images");
        assertThat(notes.unlinkedKeepDays()).as("saklama servisi yokken kod varsayılanı").isEqualTo(30);

        at(T0.minus(40, ChronoUnit.DAYS));
        Long linkedOld = upload(chA);
        Long unlinkedOld = upload(chA);
        notes.save(chA, 0, ref(linkedOld), ayse);
        at(T0.minus(5, ChronoUnit.DAYS));
        Long unlinkedNew = upload(chA);
        at(T0);

        String cutoff = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC)
                .format(T0.minus(30, ChronoUnit.DAYS));
        assertThat(jdbc.queryForObject(unlinked.countSql(), Long.class, cutoff)).isEqualTo(1L);
        assertThat(jdbc.update(unlinked.deleteSql(), cutoff)).isEqualTo(1);
        assertThat(imageRepo.findById(unlinkedOld)).isEmpty();
        assertThat(imageRepo.findById(linkedOld)).as("notta kullanılan görsele dokunulmaz").isPresent();
        assertThat(imageRepo.findById(unlinkedNew)).isPresent();

        // öksüz kuralları: kanal satırı servis DIŞINDA silinirse (temizlik başarısız oldu) kalan satırlar
        upload(chB);
        notes.save(chB, 0, "B", ayse);
        channels.deleteById(chB);
        for (String id : List.of("tech-error-channel-notes-orphan", "tech-error-channel-note-images-orphan",
                "tech-error-channel-note-versions-orphan")) {
            RetentionPolicy p = RetentionCatalog.byId(id).orElseThrow();
            assertThat(p.mode()).as(id).isEqualTo(RetentionPolicy.Mode.ORPHAN_ONLY);
            assertThat(jdbc.update(p.deleteSql())).as(id).isEqualTo(1);
        }
        assertThat(noteRepo.findByChannelId(chB)).isEmpty();
        assertThat(imageRepo.linkRows(chB)).isEmpty();
        assertThat(versionRepo.countByChannelId(chB)).isZero();
        assertThat(noteRepo.findByChannelId(chA)).as("kanalı duran not kalır").isPresent();
        assertThat(imageRepo.linkRows(chA)).hasSize(2);
        assertThat(versionRepo.countByChannelId(chA)).isEqualTo(1L);
    }

    // ── Kilit ve eşzamanlılık ────────────────────────────────────────────────────────────────────────

    /**
     * Kilit sırası: başka bir işlem KANAL satırını tutarken {@code writer} bekler ve bu sırada kanalın not / görsel
     * satırlarına DOKUNMAMIŞTIR — tutan işlem onları hemen güncelleyebilir (yazan önce not / görsel satırını alsaydı
     * tutan burada beklerdi: kilitlenme).
     */
    private void assertLocksChannelRowFirst(Long channelId, Runnable writer) throws Exception {
        TransactionTemplate tx = new TransactionTemplate(txManager);
        CountDownLatch locked = new CountDownLatch(1);
        AtomicInteger probed = new AtomicInteger(-1);
        AtomicLong probeMs = new AtomicLong(-1);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<Object> holder = pool.submit(() -> tx.execute(st -> {
                channels.lockRow(channelId);
                locked.countDown();
                try {
                    Thread.sleep(400);          // yazan başladı ve kanal satırını bekliyor
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                long t0 = System.nanoTime();
                int n = em.createNativeQuery("UPDATE tech_error_channel_note_images SET file_name = file_name WHERE channel_id = ?1")
                        .setParameter(1, channelId).executeUpdate();
                n += em.createNativeQuery("UPDATE tech_error_channel_notes SET updated_by_name = updated_by_name WHERE channel_id = ?1")
                        .setParameter(1, channelId).executeUpdate();
                probed.set(n);
                probeMs.set((System.nanoTime() - t0) / 1_000_000L);
                return null;
            }));
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
            long t0 = System.nanoTime();
            writer.run();
            long waited = (System.nanoTime() - t0) / 1_000_000L;
            holder.get(10, TimeUnit.SECONDS);
            assertThat(waited).as("yazan kanal satırını bekledi").isGreaterThanOrEqualTo(300L);
            assertThat(probed.get()).as("beklerken not / görsel satırları serbestti").isPositive();
            // Asıl kanıt: deneme kilit hatası (H2 "Deadlock detected" / zaman aşımı) almadan bitti. Süre sınırı yalnız güvenlik
            // ağıdır — 2 vCPU CI makinesinde GC / zamanlayıcı duraklaması küçük bir sınırı hatasız düşürürdü.
            assertThat(probeMs.get()).as("not / görsel satırı kilidi beklenmedi").isLessThan(2_000L);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("kilit sırası: kayıt, görsel yükleme, görsel silme ve kanal silme kanal satırını not / görsel satırlarından ÖNCE kilitler")
    void channelRowLockIsTakenFirst() throws Exception {
        Long keep = upload(chA);
        Long drop = upload(chA);
        notes.save(chA, 0, "ilk " + ref(keep), ayse);

        assertLocksChannelRowFirst(chA, () -> notes.save(chA, 1, "ikinci " + ref(keep), ayse));
        assertThat(notes.read(chA).version()).isEqualTo(2L);
        assertLocksChannelRowFirst(chA, () -> notes.upload(chA, png(), "yeni.png", ayse));
        assertThat(imageRepo.linkRows(chA)).hasSize(3);
        assertLocksChannelRowFirst(chA, () -> notes.deleteImage(chA, drop));
        assertThat(imageRepo.findById(drop)).isEmpty();
        assertLocksChannelRowFirst(chA, () -> service.deleteChannel(chA));
        assertThat(channels.findById(chA)).isEmpty();
        assertThat(noteRepo.findByChannelId(chA)).isEmpty();
        assertThat(imageRepo.linkRows(chA)).isEmpty();
    }

    @Test
    @DisplayName("eşzamanlı ilk kayıt: iki istek aynı anda sürüm 0 ile kaydeder — biri yazar, diğeri 409 (güncel sürüm 1); tek satır")
    void concurrentFirstSave() throws Exception {
        for (int round = 0; round < 5; round++) {
            Long ch = channel("Yarış " + round).getId();
            CountDownLatch start = new CountDownLatch(1);
            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                List<Future<Object>> fs = new ArrayList<>();
                for (int i = 0; i < 2; i++) {
                    String body = "yazan " + i;
                    Callable<Object> c = () -> {
                        start.await(10, TimeUnit.SECONDS);
                        try {
                            return notes.save(ch, 0, body, ayse);
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
                    Object o = f.get(20, TimeUnit.SECONDS);
                    if (o instanceof TechErrorChannelNoteService.SaveResult r) {
                        saved++;
                        assertThat(r.changed()).isTrue();
                        assertThat(r.view().version()).isEqualTo(1L);
                    } else {
                        conflicts++;
                        CodedConflictException e = (CodedConflictException) o;
                        assertThat(e.getCode()).isEqualTo("CONCURRENT_MODIFICATION");
                        assertThat(e.getExtra()).containsEntry("current_version", 1L);
                    }
                }
                assertThat(saved).as("tur %d", round).isEqualTo(1);
                assertThat(conflicts).as("tur %d", round).isEqualTo(1);
                assertThat(noteRepo.findByChannelId(ch)).isPresent();
                assertThat(versionRepo.countByChannelId(ch)).isEqualTo(1L);
            } finally {
                pool.shutdownNow();
            }
        }
    }

    @Test
    @DisplayName("tekil kısıt kaybı (kilidi atlayan eşzamanlı ilk kayıt): bozuk işlemde okuma yapılmaz — ek alansız 409")
    void uniqueLossBecomesPlainConflict() throws Exception {
        TransactionTemplate tx = new TransactionTemplate(txManager);
        CountDownLatch inserted = new CountDownLatch(1);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            // Kanal kilidini ALMADAN (eski / hatalı bir yazan gibi) işlenmemiş bir not satırı
            Future<Object> rogue = pool.submit(() -> tx.execute(st -> {
                em.createNativeQuery("INSERT INTO tech_error_channel_notes (channel_id, body_md, version) VALUES (?1, 'kaçak', 1)")
                        .setParameter(1, chA).executeUpdate();
                inserted.countDown();
                try {
                    Thread.sleep(400);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return null;
            }));
            assertThat(inserted.await(10, TimeUnit.SECONDS)).isTrue();
            CodedConflictException e = conflictOf(() -> notes.save(chA, 0, "benim notum", ayse));
            rogue.get(10, TimeUnit.SECONDS);
            assertThat(e).isNotNull();
            assertThat(e.getCode()).isEqualTo("CONCURRENT_MODIFICATION");
            assertThat(e.getExtra()).as("bozuk işlemde okuma yok — güncel durumu uç ekler").isNull();
            assertThat(notes.read(chA).body()).isEqualTo("kaçak");
            assertThat(versionRepo.countByChannelId(chA)).as("kaybedenin sürüm satırı yazılmadı").isZero();
            // uçtaki zenginleştirmenin kaynağı: işlem dışı hafif özet
            assertThat(notes.summary(chA).version()).isEqualTo(1L);
        } finally {
            pool.shutdownNow();
        }
    }
}
