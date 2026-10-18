package com.sitemonitor.service.techerror;

import com.sitemonitor.config.GlobalExceptionHandler.CodedConflictException;
import com.sitemonitor.config.GlobalExceptionHandler.FieldValidationException;
import com.sitemonitor.model.AppUser;
import com.sitemonitor.model.TechErrorTeamTemplate;
import com.sitemonitor.repository.AppUserRepository;
import com.sitemonitor.repository.TechErrorTeamTemplateRepository;
import com.sitemonitor.service.techerror.TechErrorTemplate.Template;
import com.sitemonitor.util.Msg;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * Takım ŞABLON KÜTÜPHANESİ (2026-10-18, kullanıcı kararı D17): takım başına en çok {@link #MAX_PER_TEAM} adlandırılmış
 * kanal şablonu; biri "takım varsayılanı" (yeni kanal penceresi onu ilk kaynak olarak seçer, şablon düzenleyicisinin
 * "Varsayılana dön" menüsü onu da sunar). Kapsam (kim hangi takımın kütüphanesini okur / yazar) UÇTA denetlenir.
 *
 * <ul>
 *   <li>Ad takım içinde tekil (Türkçe küçük harf, kanallarla aynı kural), en çok {@link #MAX_NAME} karakter.</li>
 *   <li>Şablon yazılırken KATI doğrulanır (çağıran {@code TechErrorTemplate.normalize} ile verir), okunurken hoşgörülü.</li>
 *   <li>Her yazım takımın satırını kilitler ({@code SELECT … FOR UPDATE}): iki eşzamanlı "varsayılan yap" / ekleme
 *       sırayla çalışır — en çok bir varsayılan, 20 kayıt sınırı ve ad tekilliği kesin. Yeni varsayılan yazılmadan önce
 *       takımın diğer varsayılanı koşullu toplu UPDATE ile düşer (PostgreSQL'de kısmi tekil indeks ek güvence).</li>
 *   <li>Güncelleme yalnız gönderilen alanları değiştirir, okunan sürümle (eski sürüm → 409); değişiklik yoksa hiçbir şey
 *       yazılmaz.</li>
 * </ul>
 */
@Service
public class TechErrorTeamTemplateService {

    public static final int MAX_PER_TEAM = 20;
    public static final int MAX_NAME = 100;
    /** PostgreSQL kısmi tekil indeksi (takım başına tek varsayılan) — {@code SchedulerService.applySchemaPatches}. */
    static final String DEFAULT_INDEX = "uq_tett_one_default";

    private final TechErrorTeamTemplateRepository repo;
    private final AppUserRepository users;
    private volatile Clock clock = Clock.systemUTC();

    public TechErrorTeamTemplateService(TechErrorTeamTemplateRepository repo, AppUserRepository users) {
        this.repo = repo;
        this.users = users;
    }

    public void useClock(Clock c) {
        this.clock = c == null ? Clock.systemUTC() : c;
    }

    private Instant now() {
        return Instant.now(clock).truncatedTo(ChronoUnit.MILLIS);
    }

    // ── Okuma ────────────────────────────────────────────────────────────────────────────────────────

    /** Takımın kütüphanesi: önce varsayılan, sonra ad (Türkçe, büyük/küçük harf duyarsız). Şablon gövdesi yok. */
    public List<TechErrorTeamTemplateRepository.Meta> list(Long teamId) {
        List<TechErrorTeamTemplateRepository.Meta> out = new ArrayList<>(repo.findByTeamId(teamId));
        java.text.Collator col = java.text.Collator.getInstance(Locale.forLanguageTag("tr"));
        col.setStrength(java.text.Collator.SECONDARY);
        out.sort(Comparator.comparing((TechErrorTeamTemplateRepository.Meta m) -> !Boolean.TRUE.equals(m.getDefaultEntry()))
                .thenComparing(m -> m.getName() == null ? "" : m.getName(), col)
                .thenComparing(TechErrorTeamTemplateRepository.Meta::getId));
        return out;
    }

    /** Takımın kaydı; yoksa ya da başka takımınsa 404. */
    public TechErrorTeamTemplate get(Long teamId, Long id) {
        if (teamId == null || id == null) throw notFound();
        return repo.findByIdAndTeamId(id, teamId).orElseThrow(TechErrorTeamTemplateService::notFound);
    }

    /** Takımın kaydı (bulunamazsa boş) — yeni kanal kaynağı doğrulaması 404 yerine alan hatası verir. */
    public Optional<TechErrorTeamTemplate> find(Long teamId, Long id) {
        if (teamId == null || id == null) return Optional.empty();
        return repo.findByIdAndTeamId(id, teamId);
    }

    public Optional<TechErrorTeamTemplate> teamDefault(Long teamId) {
        return teamId == null ? Optional.empty() : repo.findFirstByTeamIdAndDefaultEntryTrueOrderByIdAsc(teamId);
    }

    /**
     * Kaydın şablonu, HOŞGÖRÜLÜ okuma (eski kayıt kural sıkılaşsa da açılır) — ama okunamayan kayıt varsayılana DÜŞMEZ
     * (2026-10-18 inceleme): bozuk JSON / bölümsüz kayıt {@link TechErrorTemplate#fromStored} gibi sistem TR şablonunu
     * "seçtiğiniz kayıt" diye vermesin. Okunamazsa 409 {@code TEMPLATE_UNREADABLE}.
     */
    public static Template readableTemplate(TechErrorTeamTemplate e) {
        return readableTemplate(e, null);
    }

    /** {@link #readableTemplate(TechErrorTeamTemplate)}; {@code field} doluysa hata alanı (yeni kanal: seçicinin altında). */
    public static Template readableTemplate(TechErrorTeamTemplate e, String field) {
        return TechErrorTemplate.readStored(e.getTemplateJson()).orElseThrow(() -> unreadable(e, field));
    }

    /**
     * Kanala KOPYALANACAK şablon: hoşgörülü okunur ({@link #readableTemplate} — okunamıyorsa 409 {@code TEMPLATE_UNREADABLE},
     * alan {@code library_template_id}; sistem şablonuna sessizce düşülmez), sonra KATI yeniden doğrulanır (kanal şablonu
     * kaydıyla aynı kural; 400 {@code VALIDATION_FAILED} alan {@code template}).
     */
    public static Template validatedTemplate(TechErrorTeamTemplate e) {
        return TechErrorTemplate.normalize(TechErrorTemplate.toMap(readableTemplate(e, "library_template_id")));
    }

    static CodedConflictException unreadable(TechErrorTeamTemplate e, String field) {
        String name = e.getName() == null ? "" : e.getName();
        Map<String, Object> extra = field == null ? null : Map.of("field", field);
        return new CodedConflictException("TEMPLATE_UNREADABLE", Msg.t(
                "Kütüphanedeki “" + name + "” şablonu okunamadı; kayıt bozulmuş olabilir. Sistem şablonu yerine kullanılmadı — bu kaydı kütüphaneden silip şablonu bir kanalın şablon ekranından yeniden kaydedin.",
                "The library template “" + name + "” couldn't be read; the entry may be damaged. The system template wasn't used instead — delete this entry from the library and save the template again from a channel's template screen."),
                extra);
    }

    // ── Yazma ────────────────────────────────────────────────────────────────────────────────────────

    /** Yeni kayıt. {@code makeDefault}: takım varsayılanı yap (öncekini düşürür). */
    @Transactional
    public TechErrorTeamTemplate create(Long teamId, String rawName, Template template, boolean makeDefault, Long by) {
        lockTeam(teamId);
        String name = name(rawName);
        String key = TechErrorReportService.nameKey(name);
        if (repo.existsByTeamIdAndNameKey(teamId, key)) throw nameExists(name);
        if (repo.countByTeamId(teamId) >= MAX_PER_TEAM) {
            throw new CodedConflictException("TEMPLATE_LIBRARY_LIMIT", Msg.t(
                    "Takımın şablon kütüphanesinde en çok " + MAX_PER_TEAM + " şablon olabilir. Kullanmadığınız bir şablonu silip tekrar deneyin ya da var olanın üzerine kaydedin.",
                    "A team's template library can hold at most " + MAX_PER_TEAM + " templates. Delete one you no longer use and try again, or save over an existing one."));
        }
        if (makeDefault) repo.clearOtherDefaults(teamId, -1L);
        Instant now = now();
        String actor = nameOf(by);
        TechErrorTeamTemplate e = new TechErrorTeamTemplate();
        e.setTeamId(teamId);
        e.setName(name);
        e.setNameKey(key);
        applyTemplate(e, template);
        e.setDefaultEntry(makeDefault);
        e.setCreatedAt(now);
        e.setCreatedById(by);
        e.setCreatedByName(actor);
        e.setUpdatedAt(now);
        e.setUpdatedById(by);
        e.setUpdatedByName(actor);
        try {
            return repo.saveAndFlush(e);
        } catch (DataIntegrityViolationException ex) {
            throw integrity(ex, name);
        }
    }

    /**
     * Güncelleme sonucu: kayıt + gerçekten değişen alanlar (boşsa hiçbir şey yazılmadı) + KİLİT ALTINDA okunan eski ad
     * (denetimin {@code name_before}'u — kilit dışında okunan ad eşzamanlı bir yeniden adlandırmayı kaçırırdı).
     */
    public record UpdateResult(TechErrorTeamTemplate entry, List<String> changed, String nameBefore) {
        public UpdateResult(TechErrorTeamTemplate entry, List<String> changed) {
            this(entry, changed, entry == null ? null : entry.getName());
        }

        public boolean isChanged() {
            return !changed.isEmpty();
        }
    }

    /**
     * YALNIZ gönderilen alanlar: {@code name} ({@code nameSent}), {@code template} (null = gönderilmedi), {@code makeDefault}
     * (null = gönderilmedi; false = varsayılanlıktan çıkar). {@code expectedVersion} okunan sürüm — farklıysa 409.
     */
    @Transactional
    public UpdateResult update(Long teamId, Long id, Long expectedVersion, boolean nameSent, String rawName,
                               Template template, Boolean makeDefault, Long by) {
        lockTeam(teamId);
        TechErrorTeamTemplate e = get(teamId, id);
        if (expectedVersion == null) {
            throw new FieldValidationException("version", Msg.t(
                    "Kütüphane kaydının okunan sürümü gönderilmedi. Listeyi yenileyip tekrar deneyin.",
                    "The version of the library entry you read wasn't sent. Reload the list and try again."));
        }
        if (!expectedVersion.equals(e.getVersion())) throw new OptimisticLockingFailureException("tech_error_team_template version");
        String nameBefore = e.getName();
        List<String> changed = new ArrayList<>();
        if (nameSent) {
            String name = name(rawName);
            if (!name.equals(e.getName())) {
                String key = TechErrorReportService.nameKey(name);
                if (repo.existsByTeamIdAndNameKeyAndIdNot(teamId, key, e.getId())) throw nameExists(name);
                e.setName(name);
                e.setNameKey(key);
                changed.add("name");
            }
        }
        if (template != null) {
            String json = TechErrorTemplate.toJson(template);
            if (!json.equals(e.getTemplateJson())) {
                applyTemplate(e, template);
                changed.add("template");
            }
        }
        if (makeDefault != null && makeDefault != e.isDefaultEntry()) {
            if (makeDefault) repo.clearOtherDefaults(teamId, e.getId());
            e.setDefaultEntry(makeDefault);
            changed.add("default");
        }
        if (changed.isEmpty()) return new UpdateResult(e, List.of(), nameBefore);
        e.setUpdatedAt(now());
        e.setUpdatedById(by);
        e.setUpdatedByName(nameOf(by));
        try {
            return new UpdateResult(repo.saveAndFlush(e), List.copyOf(changed), nameBefore);
        } catch (DataIntegrityViolationException ex) {
            throw integrity(ex, e.getName());
        }
    }

    /**
     * SİSTEMİN şablon güncellemesi (tek seferlik göçler, 2026-10-18): yalnız şablon, okunan sürümle (farklıysa
     * {@link OptimisticLockingFailureException} — ekibin aynı andaki kaydı ezilmez); "son güncelleyen" {@code actorName}
     * (kullanıcı kimliği yok). Kayıt / takım yoksa {@link NoSuchElementException}. Değişiklik yoksa hiçbir şey yazılmaz
     * (dönüş false).
     */
    @Transactional
    public boolean systemUpdateTemplate(Long teamId, Long id, Long expectedVersion, Template template, String actorName) {
        lockTeam(teamId);
        TechErrorTeamTemplate e = get(teamId, id);
        if (expectedVersion == null || !expectedVersion.equals(e.getVersion())) {
            throw new OptimisticLockingFailureException("tech_error_team_template version");
        }
        if (TechErrorTemplate.toJson(template).equals(e.getTemplateJson())) return false;
        applyTemplate(e, template);
        e.setUpdatedAt(now());
        e.setUpdatedById(null);
        e.setUpdatedByName(actorName);
        repo.saveAndFlush(e);
        return true;
    }

    /** Kaydı KALICI siler; silinen kaydı döner (denetim adı için). Kanallar etkilenmez (şablonlarının kopyasını taşırlar). */
    @Transactional
    public TechErrorTeamTemplate delete(Long teamId, Long id) {
        lockTeam(teamId);
        TechErrorTeamTemplate e = get(teamId, id);
        repo.delete(e);
        repo.flush();
        return e;
    }

    // ── Yardımcılar ──────────────────────────────────────────────────────────────────────────────────

    private void lockTeam(Long teamId) {
        if (teamId == null || repo.lockTeamRow(teamId).isEmpty()) {
            throw new NoSuchElementException(Msg.t(
                    "Takım bulunamadı; silinmiş olabilir ya da görme yetkiniz yok. Listeyi yenileyin.",
                    "The team wasn't found; it may have been deleted or you can't see it. Refresh the list."));
        }
    }

    private static void applyTemplate(TechErrorTeamTemplate e, Template t) {
        e.setTemplateJson(TechErrorTemplate.toJson(t));
        e.setTemplateLang(t.lang());
        e.setSectionCount(t.sections().size());
        e.setFieldCount(t.allFields().size());
        e.setSlotCount(t.allSlots().size());
    }

    static String name(String raw) {
        String n = TechErrorTemplate.line(raw);
        if (n.isEmpty()) throw new FieldValidationException("name", Msg.t(
                "Şablona bir ad verin (ör. “Mobil kanallar şablonu”).", "Give the template a name (for example “Mobile channels template”)."));
        if (n.length() > MAX_NAME) throw new FieldValidationException("name", Msg.t(
                "Şablon adı en çok " + MAX_NAME + " karakter olabilir; daha kısa bir ad yazın.",
                "The template name can be at most " + MAX_NAME + " characters; write a shorter name."));
        return n;
    }

    private String nameOf(Long userId) {
        if (userId == null) return null;
        try {
            return users.findById(userId).map(TechErrorTeamTemplateService::display).orElse(null);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private static String display(AppUser u) {
        String n = u.getDisplayName() != null && !u.getDisplayName().isBlank() ? u.getDisplayName() : u.getUsername();
        return n != null && n.length() > 200 ? n.substring(0, 200) : n;
    }

    private static RuntimeException integrity(DataIntegrityViolationException ex, String name) {
        String m = String.valueOf(ex.getMostSpecificCause() == null ? ex.getMessage() : ex.getMostSpecificCause().getMessage());
        if (m.toLowerCase(Locale.ROOT).contains(DEFAULT_INDEX)) {
            return new CodedConflictException("CONCURRENT_MODIFICATION", Msg.t(
                    "Takım varsayılanı siz kaydederken başka biri tarafından değiştirildi; kayıt yapılmadı. Listeyi yenileyip tekrar deneyin.",
                    "Someone else changed the team default while you were saving, so nothing was saved. Reload the list and try again."));
        }
        return nameExists(name);
    }

    private static CodedConflictException nameExists(String name) {
        return new CodedConflictException("TEMPLATE_NAME_EXISTS", Msg.t(
                "Takımın kütüphanesinde “" + name + "” adlı bir şablon zaten var. Başka bir ad seçin ya da o kaydın üzerine kaydedin.",
                "The team's library already has a template named “" + name + "”. Choose another name or save over that entry."));
    }

    static NoSuchElementException notFound() {
        return new NoSuchElementException(Msg.t(
                "Kütüphane şablonu bulunamadı; silinmiş olabilir ya da görme yetkiniz yok. Listeyi yenileyin.",
                "The library template wasn't found; it may have been deleted or you can't see it. Reload the list."));
    }
}
