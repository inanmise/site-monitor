package com.sitemonitor.service.techerror;

import com.sitemonitor.config.GlobalExceptionHandler.CodedConflictException;
import com.sitemonitor.config.GlobalExceptionHandler.CodedNotFoundException;
import com.sitemonitor.config.GlobalExceptionHandler.FieldValidationException;
import com.sitemonitor.model.AppUser;
import com.sitemonitor.model.TechErrorChannel;
import com.sitemonitor.model.TechErrorChannelNote;
import com.sitemonitor.model.TechErrorChannelNoteImage;
import com.sitemonitor.model.TechErrorChannelNoteVersion;
import com.sitemonitor.repository.AppUserRepository;
import com.sitemonitor.repository.TechErrorChannelNoteImageRepository;
import com.sitemonitor.repository.TechErrorChannelNoteRepository;
import com.sitemonitor.repository.TechErrorChannelNoteVersionRepository;
import com.sitemonitor.repository.TechErrorChannelRepository;
import com.sitemonitor.service.retention.RetentionCatalog;
import com.sitemonitor.util.Msg;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * KANAL NOTLARI (2026-11-20, kullanıcı isteği: her teknik hata raporu kanalına "bu kanalın verisini nereden / nasıl
 * alıyorum" notu). Kanal başına TEK Markdown belgesi + görseller + son {@link #MAX_VERSIONS} kaydın sürümü. Kapsam (kim
 * okur / düzenler) UÇTA denetlenir ({@code TechErrorChannelNoteController}); burada kurallar ve yazım sırası yaşar.
 *
 * <ul>
 *   <li><b>Kilit.</b> Her yazım (kayıt, görsel yükleme, görsel silme) işlemin İLK veritabanı işi olarak kanal satırını
 *       kilitler ({@code SELECT … FOR UPDATE}). Okunan sürüm, görsel sınırları ve bağ bayrakları bu kilit altında
 *       kesindir. Kanal silme rapor satırlarından sonra aynı kilidi alır — sıra raporlar → kanal → notlar; bu sınıf
 *       rapor satırına hiç dokunmaz.</li>
 *   <li><b>Sürüm.</b> İstemci okuduğu sürümü gönderir (not yokken 0); saklı sürümle aynı değilse 409
 *       {@code CONCURRENT_MODIFICATION} (güncel sürüm, son kaydeden, zaman). Aynı gövde → hiçbir şey yazılmaz.</li>
 *   <li><b>Gövde.</b> Uzunluk taramadan ÖNCE denetlenir; CRLF / CR → LF, NUL ve eşsiz vekil (surrogate) karakterler
 *       atılır (PostgreSQL TEXT NUL kabul etmez); ≤ {@link #MAX_CHARS} UTF-16 birimi. Yalnız boşluk → boş metin.</li>
 *   <li><b>Görseller.</b> Rapor görselleriyle aynı doğrulama. Sınıra sayılanlar: notta kullanılanlar + son 24 saatte
 *       yüklenenler (≤ {@link #MAX_IMAGES} adet, ≤ 40 MB); kanalın BÜTÜN satırları ≤ 120 MB (emniyet). Kayıt, kanalın
 *       görsellerinin bağ bayrağını toplu cümleyle yeniden hesaplar (bayt okunmaz); bağsız görsel 30 gün sonra saklama
 *       kuralıyla silinir.</li>
 * </ul>
 * Not metni ve görsel baytı hiçbir günlüğe yazılmaz.
 */
@Slf4j
@Service
public class TechErrorChannelNoteService {

    /** Gövde sınırı (UTF-16 birimi). */
    public static final int MAX_CHARS = 60_000;
    /** Sınıra sayılan görsel adedi (notta kullanılan + son 24 saatte yüklenen). */
    public static final int MAX_IMAGES = 40;
    /** Sınıra sayılan görsellerin toplam baytı. */
    public static final long MAX_TOTAL_BYTES = 40L * 1024 * 1024;
    /** Kanalın BÜTÜN görsel satırlarının (bağsız / eski dahil) toplam baytı — üst emniyet sınırı. */
    public static final long MAX_ALL_BYTES = 120L * 1024 * 1024;
    /** Tutulan sürüm sayısı. */
    public static final int MAX_VERSIONS = 20;
    /** Bağsız görselin saklandığı gün (saklama kuralının varsayılanı; ayar değiştirilebilir). */
    public static final int UNLINKED_KEEP_DAYS = 30;
    /** Yeni yüklenen (henüz kaydedilmemiş taslaktaki) görselin sınıra sayıldığı süre. */
    public static final Duration RECENT_UPLOAD = Duration.ofHours(24);
    /** Bağsız görselleri silen saklama kuralının kimliği ({@code RetentionCatalog}). */
    public static final String UNLINKED_POLICY_ID = "tech-error-channel-note-images-unlinked";
    /** Tek okumada listelenen görsel satırı üst sınırı (en yeni önce) — liste sınırsız büyümesin. */
    static final int MAX_LISTED_IMAGES = 2000;
    private static final int IN_CHUNK = 500;

    private final TechErrorChannelRepository channels;
    private final TechErrorChannelNoteRepository notes;
    private final TechErrorChannelNoteImageRepository images;
    private final TechErrorChannelNoteVersionRepository versions;
    private final AppUserRepository users;
    private volatile Clock clock = Clock.systemUTC();

    /** Bağsız görsel süresinin ETKİN değeri (Ayarlar → Veri Saklama). Yoksa (dilim testleri) kod varsayılanı. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    com.sitemonitor.service.retention.RetentionService retention;

    public TechErrorChannelNoteService(TechErrorChannelRepository channels, TechErrorChannelNoteRepository notes,
                                       TechErrorChannelNoteImageRepository images,
                                       TechErrorChannelNoteVersionRepository versions, AppUserRepository users) {
        this.channels = channels;
        this.notes = notes;
        this.images = images;
        this.versions = versions;
        this.users = users;
    }

    /** Etkin görsel sınırları — üretimde sabitlerin kendisi; yalnız {@link #useLimits} (test dikişi) değiştirir. */
    private volatile int maxImages = MAX_IMAGES;
    private volatile long maxTotalBytes = MAX_TOTAL_BYTES;
    private volatile long maxAllBytes = MAX_ALL_BYTES;

    public void useClock(Clock c) {
        this.clock = c == null ? Clock.systemUTC() : c;
    }

    /**
     * Test dikişi (public: işlem vekili üzerinden de hedefe ulaşsın) — görsel sınırlarını küçültür ki bayt sınırları
     * onlarca megabayt yüklemeden sınanabilsin. Üretim kodu ÇAĞIRMAZ; istemciye bildirilen sınırlar sabitlerdir.
     */
    public void useLimits(int images, long totalBytes, long allBytes) {
        this.maxImages = images;
        this.maxTotalBytes = totalBytes;
        this.maxAllBytes = allBytes;
    }

    private Instant now() {
        return Instant.now(clock).truncatedTo(ChronoUnit.MILLIS);
    }

    // ── Sonuç türleri ────────────────────────────────────────────────────────────────────────────────

    /**
     * Notun tam görünümü: not (yoksa {@code null}), kanalın görselleri (baytsız, en yeni önce) ve sınıra sayılan bayt.
     */
    public record View(Long channelId, TechErrorChannelNote note, List<TechErrorChannelNoteImageRepository.Meta> images,
                       long imageBytes) {
        public boolean exists() { return note != null; }
        public long version() { return note == null ? 0L : note.getVersion(); }
        public String body() { return note == null || note.getBodyMd() == null ? "" : note.getBodyMd(); }
    }

    /** Kayıt sonucu: KİLİT ALTINDA okunan görünüm, gerçekten değişti mi, kayıtlı metnin andığı (bağlı) görsel sayısı. */
    public record SaveResult(View view, boolean changed, int linkedImages) { }

    /** Rapor ekranı / rapor listesi için hafif özet (metin ve bayt okunmadan). */
    public record Summary(boolean exists, boolean empty, long version, Instant updatedAt, String updatedByName, long imageCount) {
        static Summary none() { return new Summary(false, true, 0L, null, null, 0L); }
    }

    // ── Okuma ────────────────────────────────────────────────────────────────────────────────────────

    /** Kanal satırı (kapsam denetimi için — takım her istekte buradan türetilir). */
    public Optional<TechErrorChannel> channel(Long id) {
        return id == null ? Optional.empty() : channels.findById(id);
    }

    /** Kanalın takım kimliği (tam satır okunmaz; görsel sunumu her görselde çağırır). Kanal yoksa boş. */
    public Optional<Long> channelTeamId(Long id) {
        if (id == null) return Optional.empty();
        List<Long> rows = channels.teamIdOf(id);
        return rows.isEmpty() || rows.get(0) == null ? Optional.empty() : Optional.of(rows.get(0));
    }

    @Transactional(readOnly = true)
    public View read(Long channelId) {
        return viewOf(channelId, notes.findByChannelId(channelId).orElse(null), now());
    }

    /** TEK hafif sorgu; not yoksa {@code exists = false}. Okuma hatası çağırana çıkar ("not yok" denmez). */
    public Summary summary(Long channelId) {
        List<Object[]> rows = notes.summaryRows(channelId);
        if (rows.isEmpty() || rows.get(0) == null) return Summary.none();
        Object[] r = rows.get(0);
        long version = r[0] instanceof Number n ? n.longValue() : 0L;
        Instant updatedAt = r[1] instanceof Instant i ? i : null;
        String updatedBy = r[2] == null ? null : String.valueOf(r[2]);
        long length = r[3] instanceof Number n ? n.longValue() : 0L;
        long imageCount = r[4] instanceof Number n ? n.longValue() : 0L;
        return new Summary(true, length == 0, version, updatedAt, updatedBy, imageCount);
    }

    public List<TechErrorChannelNoteVersionRepository.Meta> versions(Long channelId) {
        return versions.findByChannelIdOrderByVersionDesc(channelId, PageRequest.of(0, MAX_VERSIONS));
    }

    public Optional<TechErrorChannelNoteVersion> version(Long channelId, long version) {
        return versions.findByChannelIdAndVersion(channelId, version);
    }

    /** Görselin baytsız satırı (kapsam denetimi bayt yüklenmeden yapılır). */
    public Optional<TechErrorChannelNoteImageRepository.Meta> imageMeta(Long imageId) {
        return imageId == null ? Optional.empty() : images.findMetaById(imageId);
    }

    /** Görsel, baytlarıyla — yalnız sunum ucu çağırır (kapsam denetiminden SONRA). */
    public Optional<TechErrorChannelNoteImage> imageWithBytes(Long imageId) {
        return imageId == null ? Optional.empty() : images.findById(imageId);
    }

    /** Bağsız görselin saklandığı gün sayısının etkin değeri (saklama ayarı; okunamazsa kod varsayılanı). */
    public int unlinkedKeepDays() {
        com.sitemonitor.service.retention.RetentionService r = retention;
        if (r != null) {
            try {
                return RetentionCatalog.byId(UNLINKED_POLICY_ID).map(r::effectiveDays).orElse(UNLINKED_KEEP_DAYS);
            } catch (RuntimeException e) {
                log.debug("Kanal notu görsel saklama süresi okunamadı, varsayılan kullanılıyor: {}", e.toString());
            }
        }
        return UNLINKED_KEEP_DAYS;
    }

    // ── Kayıt ────────────────────────────────────────────────────────────────────────────────────────

    /**
     * Notu kaydeder. {@code expectedVersion}: istemcinin okuduğu sürüm (not yokken 0) — kilit altında saklı sürümle
     * karşılaştırılır. Gövde saklı gövdeyle aynıysa (not yokken boş gövde dahil) hiçbir şey yazılmaz
     * ({@code changed = false}). Gerçek değişiklikte: not (sürüm + 1), sürüm satırı (en yeni {@link #MAX_VERSIONS}
     * kalır) ve kanalın görsellerinin bağ bayrağı.
     */
    @Transactional
    public SaveResult save(Long channelId, long expectedVersion, String rawBody, Long by) {
        String body = normalizeBody(rawBody);                       // saf doğrulama — kilitten önce, veritabanına dokunmaz
        lockChannel(channelId);                                     // işlemin İLK veritabanı işi
        TechErrorChannelNote note = notes.findByChannelId(channelId).orElse(null);
        long stored = note == null ? 0L : note.getVersion();
        Instant now = now();
        String before = note == null ? "" : note.getBodyMd();
        if (body.equals(before)) {
            // Değişiklik yok — sürüm farklı olsa bile: yanıtı kaybolan bir kaydın yeniden denemesi (istemci eski sürümü
            // gönderir, metin zaten kayıtlı) "başkası kaydetti" çakışması DEĞİLDİR; güncel görünüm döner. Bağ bayrakları
            // yine metne göre düzeltilir (yazma yalnız değişen satırlarda): kayıtlı metnin önceden andığı bir kimlikle
            // sonradan yüklenen görsel ya da yarışta kaçmış bayrak burada iyileşir.
            relink(channelId, TechErrorNoteRefs.ids(body), now);
            return new SaveResult(viewOf(channelId, note, now), false, 0);
        }
        if (stored != expectedVersion) {
            throw conflict(Long.valueOf(stored), note == null ? null : note.getUpdatedByName(),
                    note == null ? null : note.getUpdatedAt());
        }
        String actor = nameOf(by);
        if (note == null) {
            note = new TechErrorChannelNote();
            note.setChannelId(channelId);
            note.setVersion(1L);
            note.setCreatedAt(now);
            note.setCreatedById(by);
            note.setCreatedByName(actor);
        }
        note.setBodyMd(body);
        note.setUpdatedAt(now);
        note.setUpdatedById(by);
        note.setUpdatedByName(actor);
        TechErrorChannelNote saved;
        try {
            saved = notes.saveAndFlush(note);
        } catch (DataIntegrityViolationException e) {
            // Kanal kilidini atlayan eşzamanlı bir İLK kayıt tekil kısıtı kazandı. PostgreSQL'de işlem artık bozuk (25P02):
            // burada kazananı OKUMAK 500 verirdi — ayrıntısız 409; uç güncel durumu yeni bir okumayla ekler
            throw conflict(null, null, null);
        }
        int linked = relink(channelId, TechErrorNoteRefs.ids(body), now);
        TechErrorChannelNoteVersion v = new TechErrorChannelNoteVersion();
        v.setChannelId(channelId);
        v.setVersion(saved.getVersion());
        v.setBodyMd(body);
        v.setSavedAt(now);
        v.setSavedByName(actor);
        v.setCharCount(body.length());
        v.setImageCount(linked);
        versions.save(v);
        if (versions.countByChannelId(channelId) > MAX_VERSIONS) {
            List<Long> edge = versions.versionsNewestFirst(channelId, PageRequest.of(MAX_VERSIONS, 1));
            if (!edge.isEmpty()) versions.deleteUpTo(channelId, edge.get(0));
        }
        return new SaveResult(viewOf(channelId, saved, now), true, linked);
    }

    /**
     * Kanalın bütün görsel satırlarının bağ bayrağını yeni gövdeye göre düzeltir: bağlı ⇔ kimliği gövdede anılıyor.
     * Yalnız DEĞİŞEN satırlara dokunulur (damga onlarda yenilenir). Bayt okunmaz. Bağlı görsel sayısını döner.
     */
    private int relink(Long channelId, Set<Long> refs, Instant now) {
        List<Long> toLink = new ArrayList<>();
        List<Long> toUnlink = new ArrayList<>();
        int linked = 0;
        for (Object[] row : images.linkRows(channelId)) {
            Long id = ((Number) row[0]).longValue();
            boolean was = Boolean.TRUE.equals(row[1]);
            boolean is = refs.contains(id);
            if (is) linked++;
            if (is && !was) toLink.add(id);
            else if (!is && was) toUnlink.add(id);
        }
        for (int i = 0; i < toLink.size(); i += IN_CHUNK) {
            images.setLinked(channelId, toLink.subList(i, Math.min(toLink.size(), i + IN_CHUNK)), true, now);
        }
        for (int i = 0; i < toUnlink.size(); i += IN_CHUNK) {
            images.setLinked(channelId, toUnlink.subList(i, Math.min(toUnlink.size(), i + IN_CHUNK)), false, now);
        }
        return linked;
    }

    // ── Görseller ────────────────────────────────────────────────────────────────────────────────────

    /**
     * Görsel yükler (her yükleme yeni satır; bağsız doğar). Sınırlar kanal kilidi altında veritabanından sayılır:
     * sayılan adet ≥ {@link #MAX_IMAGES} → 409 {@code NOTE_IMAGE_LIMIT}; sayılan bayt + yeni &gt; 40 MB ya da kanalın
     * bütün satırları + yeni &gt; 120 MB → 409 {@code NOTE_IMAGE_TOTAL_LIMIT}.
     */
    @Transactional
    public TechErrorChannelNoteImage upload(Long channelId, byte[] data, String fileName, Long by) {
        TechErrorImageValidator.Checked ck = TechErrorImageValidator.check(data);   // saf doğrulama — kilitten önce
        lockChannel(channelId);                                     // işlemin İLK veritabanı işi
        Instant now = now();
        long[] counted = counted(channelId, now);
        if (counted[0] >= maxImages) {
            throw new CodedConflictException("NOTE_IMAGE_LIMIT", Msg.t(
                    "Kanal notunda en çok " + MAX_IMAGES + " görsel olabilir (notta kullanılanlar ve son 24 saatte yüklenenler sayılır). Kullanmadığınız görselleri silin ya da nottan çıkarıp notu kaydedin; yüklenip kullanılmayan görseller 24 saat sonra sayılmaz.",
                    "A channel note can hold at most " + MAX_IMAGES + " images (images used in the note and images uploaded in the last 24 hours count). Delete the images you don't use, or remove them from the note and save it; uploaded but unused images stop counting after 24 hours."));
        }
        if (counted[1] + data.length > maxTotalBytes) {
            throw new CodedConflictException("NOTE_IMAGE_TOTAL_LIMIT", Msg.t(
                    "Kanal notundaki görsellerin toplamı 40 MB'ı geçemez. Kullanmadığınız görselleri silin ya da büyük görselleri küçültüp (ya da JPEG olarak kaydedip) yeniden yükleyin.",
                    "The images of a channel note can add up to at most 40 MB. Delete the images you don't use, or make the large images smaller (or save them as JPEG) and upload again."));
        }
        if (images.totalBytes(channelId) + data.length > maxAllBytes) {
            throw new CodedConflictException("NOTE_IMAGE_TOTAL_LIMIT", Msg.t(
                    "Bu kanalda kullanılmayan çok fazla görsel birikti (kanal başına toplam 120 MB sınırı). Kullanılmayan görselleri silip yeniden deneyin; silinmeyenler saklama süresi dolunca kendiliğinden temizlenir.",
                    "Too many unused images have piled up for this channel (120 MB in total per channel). Delete the unused images and try again; the rest are cleaned up automatically when their retention period ends."));
        }
        TechErrorChannelNoteImage img = new TechErrorChannelNoteImage();
        img.setChannelId(channelId);
        img.setContentType(ck.contentType());
        img.setFileName(TechErrorReportService.fileName(fileName));
        img.setWidth(ck.width());
        img.setHeight(ck.height());
        img.setSizeBytes(data.length);
        img.setData(data);
        img.setUploadedAt(now);
        img.setUploadedById(by);
        img.setLinked(false);
        img.setLinkChangedAt(now);
        TechErrorChannelNoteImage saved = images.saveAndFlush(img);
        // Kayıtlı metin bu kimliği ÖNCEDEN anıyorsa (kimlikler sıralı; elle yazılmış ya da başka kanaldan yapıştırılmış
        // bir başvuru) görsel bağlı doğar — aksi halde metinde görünür ama saklama süresi dolunca silinirdi.
        String body = notes.findByChannelId(channelId).map(TechErrorChannelNote::getBodyMd).orElse("");
        if (TechErrorNoteRefs.references(body, saved.getId())) {
            images.setLinked(channelId, List.of(saved.getId()), true, now);
            saved.setLinked(true);
        }
        return saved;
    }

    /**
     * Görseli siler — yalnız KAYITLI not metni onu anmıyorsa (anıyorsa 409 {@code NOTE_IMAGE_IN_USE}); başka kanalın ya
     * da olmayan görsel 404 {@code NOTE_IMAGE_NOT_FOUND}. Silinen görselin baytsız satırını döner (denetim için).
     */
    @Transactional
    public TechErrorChannelNoteImageRepository.Meta deleteImage(Long channelId, Long imageId) {
        lockChannel(channelId);                                     // işlemin İLK veritabanı işi
        TechErrorChannelNoteImageRepository.Meta meta = imageMeta(imageId).orElse(null);
        if (meta == null || !channelId.equals(meta.getChannelId())) throw imageNotFound();
        String body = notes.findByChannelId(channelId).map(TechErrorChannelNote::getBodyMd).orElse("");
        if (TechErrorNoteRefs.references(body, imageId)) {
            throw new CodedConflictException("NOTE_IMAGE_IN_USE", Msg.t(
                    "Bu görsel kayıtlı notta kullanılıyor; silinmedi. Önce görseli nottan çıkarıp notu kaydedin, sonra silin.",
                    "This image is used in the saved note, so it wasn't deleted. Remove the image from the note and save the note first, then delete it."));
        }
        if (images.deleteOne(imageId, channelId) == 0) throw imageNotFound();
        return meta;
    }

    /** Kanal silinirken (çağıranın işleminde, kanal kilidinden SONRA): not + görseller + sürümler. Toplu, bayt okumaz. */
    @Transactional
    public void deleteForChannel(Long channelId) {
        images.deleteAllForChannel(channelId);
        versions.deleteAllForChannel(channelId);
        notes.deleteAllForChannel(channelId);
    }

    // ── Yardımcılar ──────────────────────────────────────────────────────────────────────────────────

    private void lockChannel(Long channelId) {
        if (channelId == null || channels.lockRow(channelId).isEmpty()) throw channelNotFound();
    }

    /** Sınıra sayılan satırlar: [adet, bayt]. */
    private long[] counted(Long channelId, Instant now) {
        List<Object[]> rows = images.countedFor(channelId, now.minus(RECENT_UPLOAD));
        if (rows.isEmpty() || rows.get(0) == null) return new long[]{ 0L, 0L };
        Object[] r = rows.get(0);
        return new long[]{ r[0] instanceof Number n ? n.longValue() : 0L, r[1] instanceof Number n ? n.longValue() : 0L };
    }

    private View viewOf(Long channelId, TechErrorChannelNote note, Instant now) {
        List<TechErrorChannelNoteImageRepository.Meta> list =
                images.findByChannelIdOrderByIdDesc(channelId, PageRequest.of(0, MAX_LISTED_IMAGES));
        return new View(channelId, note, list, counted(channelId, now)[1]);
    }

    /**
     * Gövde kuralı (saf; veritabanına dokunmaz). Uzunluk taramadan ÖNCE denetlenir: normalleştirme metni yalnız
     * KISALTIR (CRLF → LF en çok yarıya indirir), bu yüzden sınırın iki katını aşan ham metin okunmadan reddedilir.
     * Sonra tek doğrusal geçiş: CRLF / CR → LF, NUL ve eşsiz vekil karakterler atılır. Yalnız boşluk → boş metin.
     */
    public static String normalizeBody(String raw) {
        if (raw == null) {
            throw new FieldValidationException("body", Msg.t(
                    "Not metni gönderilmedi. Sayfayı yenileyip tekrar deneyin.",
                    "The note text wasn't sent. Reload the page and try again."));
        }
        if (raw.length() > 2 * MAX_CHARS) throw bodyTooLong(raw.length());
        int n = raw.length();
        StringBuilder sb = new StringBuilder(n);
        for (int i = 0; i < n; i++) {
            char c = raw.charAt(i);
            if (c == '\r') {
                sb.append('\n');
                if (i + 1 < n && raw.charAt(i + 1) == '\n') i++;
            } else if (c == 0) {
                // NUL: PostgreSQL TEXT kabul etmez — atılır
            } else if (Character.isHighSurrogate(c)) {
                if (i + 1 < n && Character.isLowSurrogate(raw.charAt(i + 1))) {
                    sb.append(c).append(raw.charAt(i + 1));
                    i++;
                }
                // eşsiz üst vekil atılır
            } else if (!Character.isLowSurrogate(c)) {
                sb.append(c);
            }
            // eşsiz alt vekil atılır
        }
        if (sb.length() > MAX_CHARS) throw bodyTooLong(sb.length());
        String out = sb.toString();
        return out.isBlank() ? "" : out;
    }

    private static FieldValidationException bodyTooLong(int length) {
        return new FieldValidationException("body", Msg.t(
                "Kanal notu en çok " + MAX_CHARS + " karakter olabilir (şu an " + length + "). Metni kısaltıp yeniden kaydedin.",
                "A channel note can be at most " + MAX_CHARS + " characters (it is " + length + " now). Shorten the text and save again."));
    }

    /**
     * 409 {@code CONCURRENT_MODIFICATION}. {@code currentVersion} doluysa (kilit altında okunan sürüm; not yoksa 0) güncel
     * sürüm, son kaydeden ve zaman ek alan olarak taşınır; {@code null} ise ek alan yok (bozuk işlemde okuma yapılmaz —
     * uç, işlem dışında yeni bir okumayla ekler).
     */
    public static CodedConflictException conflict(Long currentVersion, String updatedByName, Instant updatedAt) {
        Map<String, Object> extra = null;
        if (currentVersion != null) {
            extra = new LinkedHashMap<>();
            extra.put("current_version", currentVersion);
            extra.put("updated_by_name", updatedByName);
            extra.put("updated_at", updatedAt == null ? null : updatedAt.toString());
        }
        String who = updatedByName == null || updatedByName.isBlank() ? "" : " (" + updatedByName + ")";
        return new CodedConflictException("CONCURRENT_MODIFICATION", Msg.t(
                "Kanal notunu siz düzenlerken başka biri" + who + " kaydetti; değişikliğiniz kaydedilmedi. Güncel notu yükleyip kendi değişikliğinizi onun üzerine uygulayın.",
                "Someone else" + who + " saved the channel note while you were editing it, so your change wasn't saved. Load the latest note and apply your change on top of it."),
                extra);
    }

    public static CodedNotFoundException channelNotFound() {
        return new CodedNotFoundException("CHANNEL_NOT_FOUND", Msg.t(
                "Kanal bulunamadı; silinmiş olabilir ya da görme yetkiniz yok. Listeyi yenileyin.",
                "The channel wasn't found; it may have been deleted or you can't see it. Refresh the list."));
    }

    public static CodedNotFoundException imageNotFound() {
        return new CodedNotFoundException("NOTE_IMAGE_NOT_FOUND", Msg.t(
                "Görsel bulunamadı; silinmiş olabilir. Notu yenileyin.",
                "The image wasn't found; it may have been deleted. Reload the note."));
    }

    public static CodedNotFoundException versionNotFound() {
        return new CodedNotFoundException("NOTE_VERSION_NOT_FOUND", Msg.t(
                "Bu not sürümü bulunamadı; yalnız son " + MAX_VERSIONS + " kayıt tutulur. Sürüm listesini yenileyin.",
                "This note version wasn't found; only the last " + MAX_VERSIONS + " saves are kept. Reload the version list."));
    }

    private String nameOf(Long userId) {
        if (userId == null) return null;
        try {
            return users.findById(userId).map(TechErrorChannelNoteService::display).orElse(null);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String display(AppUser u) {
        String n = u.getDisplayName() != null && !u.getDisplayName().isBlank() ? u.getDisplayName() : u.getUsername();
        return n != null && n.length() > 200 ? n.substring(0, 200) : n;
    }
}
