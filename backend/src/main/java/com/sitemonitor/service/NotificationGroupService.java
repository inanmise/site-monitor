package com.sitemonitor.service;

import com.sitemonitor.model.NotificationGroup;
import com.sitemonitor.repository.NotificationGroupRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Bir takımın alarm alıcılarının GRUP bileşenini çözer.
 *
 * <p><b>Sözleşme — bilerek "tam zincir" DEĞİL:</b> bu servis yalnız
 * <em>"bu takım/damga için geçerli bir grup var mı?"</em> sorusunu yanıtlar. Grup yoksa
 * {@link Override#NONE} döner ve çağıran KENDİ mevcut {@code Team.email} kodunu aynen işletir.
 *
 * <p>Bu ayrım geliştirmenin <b>birinci yasasının</b> mekanik güvencesidir: hiç grup tanımlı
 * olmayan bir kurulumda eski yolun tek satırı bile değişmez — çünkü grup tablosu boşken bu servis
 * hep {@code NONE} döner. Zincirin tamamını buraya taşısaydık, {@code Team.email} yedeği artık bu
 * sınıfın içinden geçerdi ve mevcut alarm testleri servisi taklit ederken (mock) sessizce alıcısız
 * kalırdı — yani "davranış aynı" iddiası testlerle kanıtlanamaz hâle gelirdi.
 *
 * <p><b>Çözümleme sırası</b> (ikisi de burada, {@code Team.email} çağıranda):
 * <ol>
 *   <li>Alarma damgalanan grup — aktif VE o takıma ait ise</li>
 *   <li>Takımın aktif varsayılan grubu</li>
 * </ol>
 *
 * <p><b>Eskalasyon kişilerine DOKUNMAZ:</b> grup yalnız takım-maili bileşeninin yerine geçer;
 * {@code escalation_contacts} (müdür/severity katmanı) çağıranda aynen ÜSTÜNE eklenmeye devam
 * eder. Grup, müdürün haberini KESMEZ (K4).
 *
 * <p><b>Neden tek servis:</b> takım maili alarm ailesinde ÜÇ ayrı yerde çözülüyor
 * ({@code EscalationService} hunisi, {@code StormService}, {@code IncidentNotificationService}).
 * Üçü ayrı grup mantığı taşısaydı zamanla sapardı — normal alarm gruba, toplu kesinti takım
 * mailine giderdi.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationGroupService {

    private final NotificationGroupRepository groupRepo;

    /** Grup başına adres tavanı (K8) — doğrulama katmanı da bunu kullanır. */
    public static final int MAX_EMAILS_PER_GROUP = 15;

    /** Geçersiz kılmanın hangi halkadan geldiği — K9 kaynak etiketi. */
    public enum Source { GROUP, TEAM_DEFAULT_GROUP, NONE }

    /**
     * @param emails    grubun adresleri; {@code NONE} ise BOŞ (çağıran kendi yedeğine düşer)
     * @param source    hangi halkadan geldi
     * @param groupId   uygulanan grup (null → yok)
     * @param groupName arayüz etiketi için grup adı
     */
    public record Override(List<String> emails, Source source, Long groupId, String groupName) {

        public static final Override NONE = new Override(List.of(), Source.NONE, null, null);

        /** Grup gerçekten uygulandı mı — çağıranın tek karar noktası. */
        public boolean applies() { return source != Source.NONE && !emails.isEmpty(); }

        /** K9 kaynak etiketi: "Grup: Ödeme-Nöbetçi"; grup yoksa null (çağıran "Takım maili" yazar). */
        public String label() { return applies() ? "Grup: " + groupName : null; }
    }

    /**
     * Tek takım için grup geçersiz kılması.
     *
     * @param teamId         takım; null → {@code NONE}
     * @param stampedGroupId alarmda damgalı grup (K5). Storm ve olay bildiriminde DAİMA null
     *                       geçilir — o yollar birçok monitörü tek bildirimde topladığı (ya da
     *                       hiç monitörü olmadığı) için içlerinden birinin grubunu seçmek keyfî
     *                       olurdu; orada yalnız takımın varsayılanı anlamlıdır.
     */
    public Override overrideFor(Long teamId, Long stampedGroupId) {
        if (teamId == null) return Override.NONE;

        // 1) Damgalı grup. SAHİPLİK kontrolü şart: başka takıma ait bir id damgalanmışsa (veri
        //    taşıma, elle müdahale, takım değişimi) o takımın adreslerine posta göndermek sızıntı
        //    olurdu — yok sayılır ve UYARI loglanır, sessizce yutulmaz.
        if (stampedGroupId != null) {
            Optional<NotificationGroup> stamped = groupRepo.findById(stampedGroupId);
            if (stamped.isPresent()) {
                NotificationGroup g = stamped.get();
                if (!teamId.equals(g.getTeamId())) {
                    log.warn("Bildirim grubu {} takım {} ile eşleşmiyor (grubun takımı {}) — yok sayıldı",
                            g.getId(), teamId, g.getTeamId());
                } else if (!Boolean.TRUE.equals(g.getActive())) {
                    log.debug("Damgalı bildirim grubu {} pasif — zincirin kalanına düşülüyor", g.getId());
                } else {
                    List<String> emails = deliverableEmails(g.getEmails());
                    if (!emails.isEmpty()) {
                        return new Override(emails, Source.GROUP, g.getId(), g.getName());
                    }
                    warnUnusable(g);
                }
            }
        }

        // 2) Takımın aktif varsayılan grubu
        Optional<NotificationGroup> def = groupRepo.findFirstByTeamIdAndIsDefaultTrueAndActiveTrue(teamId);
        // Aktiflik BURADA da doğrulanır. Sorgu adı zaten aktif süzüyor ama garanti, bir metot
        // adının doğru kalmasına bağlı OLMAMALI: sorgu ileride değişirse ya da bu dal başka bir
        // kaynaktan beslenirse pasif grup sessizce uygulanır ve takım adresi atlanırdı.
        if (def.isPresent() && Boolean.TRUE.equals(def.get().getActive())) {
            List<String> emails = deliverableEmails(def.get().getEmails());
            if (!emails.isEmpty()) {
                return new Override(emails, Source.TEAM_DEFAULT_GROUP, def.get().getId(), def.get().getName());
            }
            warnUnusable(def.get());
        }

        // 3) Grup yok → çağıran KENDİ Team.email yoluna düşer (bugünkü davranış).
        return Override.NONE;
    }

    /**
     * Grup var ama GÖNDERİLEBİLİR adresi yok — zincir takım adresine düşecek.
     *
     * <p>Sessizce düşmek yeterli DEĞİL: alarm gitmeye devam eder ama kimse grubun bozuk
     * olduğunu öğrenmez. Uyarı, sorunu ops tarafında görünür kılar.
     */
    private void warnUnusable(NotificationGroup g) {
        log.warn("Bildirim grubu {} ('{}') gönderilebilir adres taşımıyor — takım adresine düşülüyor. "
               + "Kayıtlı içerik: '{}'", g.getId(), g.getName(), g.getEmails());
    }

    /** Damgasız kısayol — storm, olay bildirimi ve alarm zinciri dışı gönderimler için. */
    public Override overrideFor(Long teamId) {
        return overrideFor(teamId, null);
    }

    /**
     * GÖNDERİLEBİLİR adresler — çözümleme yolunun kullandığı süzgeç.
     *
     * <p>{@link #parseEmails} ham içeriği döner (ekranda yönetici ne kayıtlıysa onu görmeli).
     * Burada ayrıca BİÇİM süzgeci var: adres gibi görünmeyen girdiler atılır. Aksi halde
     * {@code emails} kolonunda "asdf" gibi bir içerik taşıyan grup UYGULANIR, alarm adres
     * olmayan bir şeye gider ve takım adresi ATLANIRDI. Yazma anında doğrulama var, ama eski
     * kayıtlar / doğrudan veritabanı müdahalesi / ileride eklenecek bir yazma yolu onu delebilir;
     * garanti gönderim anında da durmalı.
     */
    static List<String> deliverableEmails(String csv) {
        List<String> out = new ArrayList<>();
        for (String e : parseEmails(csv)) {
            if (DELIVERABLE.matcher(e).matches()) out.add(e);
        }
        return out;
    }

    /** Kasten gevşek: amaç yazım denetimi değil, "bu hiç adres değil"i elemek. */
    private static final java.util.regex.Pattern DELIVERABLE =
            java.util.regex.Pattern.compile("[^\\s@,;]+@[^\\s@,;]+\\.[^\\s@,;]{2,}");

    /** Normalize CSV → adres listesi (boşlar atılır, büyük/küçük harf duyarsız yinelenenler tekilleşir). */
    public static List<String> parseEmails(String csv) {
        if (csv == null || csv.isBlank()) return List.of();
        List<String> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String part : csv.split(",")) {
            String e = part.trim();
            if (!e.isEmpty() && seen.add(e.toLowerCase())) out.add(e);
        }
        return out;
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  CRUD — doğrulama ve değişmezler burada; controller ince kalır.
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Kabul edilebilir e-posta biçimi. Kasten GEVŞEK: RFC 5322'yi tam uygulayan bir regex
     * okunmaz olur ve kurumsal adreslerde yanlış-negatif üretir. Amaç yazım hatasını yakalamak
     * ("ali@" / "ali example.com"), postacı olmak değil.
     */
    private static final java.util.regex.Pattern EMAIL =
            java.util.regex.Pattern.compile("^[^\\s@,;]+@[^\\s@,;]+\\.[^\\s@,;]{2,}$");

    private static final int MAX_NAME = 100;

    /** Doğrulanmış, normalize edilmiş grup girdisi. */
    public record GroupInput(String name, List<String> emails, boolean makeDefault) {}

    /**
     * Ham gövdeyi doğrular ve normalize eder.
     *
     * <p>BOŞ GRUP REDDEDİLİR (K8): adresi olmayan bir grup bir monitöre seçilseydi alarm
     * SESSİZCE kimseye gitmezdi — sistemin verebileceği en kötü hata. "Kimseye gönderme"
     * niyeti için grubu pasife almak var.
     */
    public GroupInput validate(String rawName, List<String> rawEmails, boolean makeDefault) {
        String name = rawName != null ? rawName.trim() : "";
        if (name.isEmpty()) throw new IllegalArgumentException("Grup adı zorunludur");
        if (name.length() > MAX_NAME)
            throw new IllegalArgumentException("Grup adı en fazla " + MAX_NAME + " karakter olabilir");

        List<String> emails = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        if (rawEmails != null) {
            for (String raw : rawEmails) {
                if (raw == null) continue;
                // Tek alana virgülle yapıştırılmış liste de kabul edilir (kopyala-yapıştır gerçeği).
                for (String part : raw.split("[,;]")) {
                    String e = part.trim();
                    if (e.isEmpty()) continue;
                    if (!EMAIL.matcher(e).matches())
                        throw new IllegalArgumentException("Geçersiz e-posta adresi: " + e);
                    if (seen.add(e.toLowerCase())) emails.add(e);
                }
            }
        }
        if (emails.isEmpty())
            throw new IllegalArgumentException("Grup en az bir e-posta adresi içermelidir");
        if (emails.size() > MAX_EMAILS_PER_GROUP)
            throw new IllegalArgumentException(
                    "Bir grupta en fazla " + MAX_EMAILS_PER_GROUP + " adres olabilir (" + emails.size() + " girildi)");
        return new GroupInput(name, emails, makeDefault);
    }

    /** Takım içinde ad benzersizliği — {@code excludeId} güncellemede kendi satırını dışlar. */
    public void requireUniqueName(Long teamId, String name, Long excludeId) {
        if (groupRepo.existsByTeamAndName(teamId, name, excludeId))
            throw new IllegalArgumentException("Bu takımda '" + name + "' adlı bir grup zaten var");
    }

    @org.springframework.transaction.annotation.Transactional
    public NotificationGroup create(Long teamId, GroupInput in, String actor, String actorName) {
        requireUniqueName(teamId, in.name(), null);
        NotificationGroup g = new NotificationGroup();
        g.setTeamId(teamId);
        g.setName(in.name());
        g.setEmails(String.join(", ", in.emails()));
        g.setActive(true);
        g.setIsDefault(false);          // aşağıda tek-varsayılan kısıtıyla birlikte set edilir
        g.setCreatedAt(nowIso());
        g.setUpdatedAt(nowIso());
        g.setCreatedBy(actor);
        g.setCreatedByName(actorName);
        g.setUpdatedBy(actor);
        g.setUpdatedByName(actorName);
        g = groupRepo.save(g);
        if (in.makeDefault()) makeDefault(g);
        return g;
    }

    @org.springframework.transaction.annotation.Transactional
    public NotificationGroup update(NotificationGroup g, GroupInput in, String actor, String actorName) {
        requireUniqueName(g.getTeamId(), in.name(), g.getId());
        g.setName(in.name());
        g.setEmails(String.join(", ", in.emails()));
        g.setUpdatedAt(nowIso());
        g.setUpdatedBy(actor);
        g.setUpdatedByName(actorName);
        g = groupRepo.save(g);
        if (in.makeDefault()) {
            makeDefault(g);
        } else if (Boolean.TRUE.equals(g.getIsDefault())) {
            // Varsayılanlığı GERİ ALMAK serbest: takım "artık grup değil, takım maili" diyebilmeli.
            g.setIsDefault(false);
            g = groupRepo.save(g);
        }
        return g;
    }

    /**
     * Bu grubu takımın varsayılanı yapar ve diğerlerini indirir (K7: takım başına EN FAZLA BİR).
     *
     * <p>Kısıt uygulama katmanında: kısmi-unique indeks ({@code WHERE is_default}) Hibernate'in
     * {@code ddl-auto} yoluyla üretilmiyor ve dolu tabloya sonradan eklenirse mevcut çift
     * varsayılanlarda SESSİZCE düşerdi — projede yaşanmış tuzağın aynısı.
     */
    @org.springframework.transaction.annotation.Transactional
    public NotificationGroup makeDefault(NotificationGroup g) {
        int demoted = groupRepo.clearOtherDefaults(g.getTeamId(), g.getId());
        if (demoted > 0)
            log.info("Takım {} varsayılan bildirim grubu değişti → '{}' ({} grup indirildi)",
                    g.getTeamId(), g.getName(), demoted);
        if (!Boolean.TRUE.equals(g.getIsDefault())) {
            g.setIsDefault(true);
            g = groupRepo.save(g);
        }
        return g;
    }

    /**
     * KALICI silme — satır gider.
     *
     * <p>Eskiden yumuşak silmeydi ({@code active=false}) ve satır kalıyordu. İki gerçek soruna yol
     * açtı: (1) silinmiş grubun adı SONSUZA DEK rezerve kalıyordu — kullanıcı hiçbir yerde
     * göremediği bir kayda çarpıp "bu adda grup zaten var" uyarısı alıyordu; (2) hiçbir işe
     * yaramayan sahipsiz satırlar birikiyordu.
     *
     * <p>Kalıcı silme artık GÜVENLİ, çünkü silme yalnız grup HİÇBİR YERDE KULLANILMIYORKEN
     * mümkün ({@code NotificationGroupUsageService} + controller kapısı). Yani giden satırın
     * hiçbir izleme/envanter referansı yoktur.
     *
     * <p>Denetim kaydı çağıranda silmeden ÖNCE hazırlanır — satır gittikten sonra kimin neyi
     * sildiği hiçbir yerden okunamazdı.
     */
    @org.springframework.transaction.annotation.Transactional
    public void deletePermanently(NotificationGroup g) {
        log.info("Bildirim grubu kalıcı silindi: id={} takım={} ad='{}'",
                g.getId(), g.getTeamId(), g.getName());
        groupRepo.delete(g);
    }

    /** Arayüz gösterimi — CSV yerine dizi döner, "kaç adres" sayacı ekranda hesaplanmaz. */
    public java.util.Map<String, Object> toDto(NotificationGroup g) {
        java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("id", g.getId());
        m.put("team_id", g.getTeamId());
        m.put("name", g.getName());
        m.put("emails", parseEmails(g.getEmails()));
        m.put("is_default", Boolean.TRUE.equals(g.getIsDefault()));
        m.put("active", Boolean.TRUE.equals(g.getActive()));
        m.put("created_at", g.getCreatedAt());
        m.put("updated_at", g.getUpdatedAt());
        m.put("updated_by_name", g.getUpdatedByName());
        // 2026-09-20 (kullanıcı bildirimi): kim oluşturdu / kim güncelledi listede görünsün
        m.put("created_by", g.getCreatedBy());
        m.put("created_by_name", g.getCreatedByName());
        m.put("updated_by", g.getUpdatedBy());
        return m;
    }

    private static String nowIso() {
        return java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")
                .withZone(java.time.ZoneOffset.UTC).format(java.time.Instant.now());
    }
}
