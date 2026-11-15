package com.sitemonitor.service.oncall;

import com.sitemonitor.model.AppUser;
import com.sitemonitor.model.OnCallOverride;
import com.sitemonitor.model.OnCallSchedule;
import com.sitemonitor.repository.AppUserRepository;
import com.sitemonitor.repository.OnCallOverrideRepository;
import com.sitemonitor.repository.OnCallScheduleRepository;
import com.sitemonitor.repository.OnCallWeeklyNoticeRepository;
import com.sitemonitor.model.OnCallWeeklyNotice;
import com.sitemonitor.service.cache.CacheBus;
import com.sitemonitor.service.cache.CacheBusNames;
import com.sitemonitor.util.Msg;
import com.sitemonitor.util.TtlMemo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Takım nöbet çizelgesi (2026-10-11, kullanıcı kararı: "takım seçerse nöbetçiye de"). Çizelge / üye / yer değiştirme
 * kaydı, o anki nöbetçi ({@link OnCallRotation}) ve bildirim yolu için "bu takımın şu anki nöbetçisi" sorgusu.
 *
 * <p><b>Bildirim yolu ucuz:</b> {@link #notifyTargets(Collection)} yalnız {@code notifyOncall} açık çizelgeleri ve geçerli
 * yer değiştirmeleri okur (2 sorgu + kişiler), sonuç takım kümesi başına {@link #CACHE_MS} bellekte — alarm başına
 * sorgu yok. Kayıt / yer değiştirme belleği temizler; öteki pod'lar önbellek veri yoluyla (2026-10-22, çok pod R2-A,
 * MP-30: {@link CacheBus}, ad {@link CacheBusNames#ONCALL}) en geç bir yoklama aralığında (~2 sn) görür — eskiden
 * {@link #CACHE_MS} boyunca tarama lideri ÖNCEKİ nöbetçiye alarm gönderiyordu.
 */
@Slf4j
@Service
public class OnCallService {

    static final long CACHE_MS = 30_000;
    public static final int MAX_MEMBERS = 50;
    static final int OVERRIDE_RETENTION_DAYS = 180;
    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);
    private static final Pattern ANCHOR = Pattern.compile("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}");

    private final OnCallScheduleRepository schedules;
    private final OnCallOverrideRepository overrides;
    private final AppUserRepository users;
    private final TtlMemo<Map<Long, OnCallPerson>> memo = new TtlMemo<>(256);

    public OnCallService(OnCallScheduleRepository schedules, OnCallOverrideRepository overrides, AppUserRepository users) {
        this.schedules = schedules;
        this.overrides = overrides;
        this.users = users;
    }

    /** Pod'lar arası boşaltma (MP-30) — yoksa (birim testi, tek pod eski kurulum) yalnız bu pod'un belleği. */
    private volatile CacheBus cacheBus;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setCacheBus(CacheBus bus) {
        this.cacheBus = bus;
        if (bus != null) bus.register(CacheBusNames.ONCALL, this::memoClearLocal);
    }

    /**
     * Haftalık nöbet bildirimi gönderim kayıtları (2026-11-15) — isteğe bağlı: elle kurulan testlerde yoksa pencere "son
     * sonuç" göstermez ve sıradaki gönderim haftanın gönderildiğini bilmeden hesaplanır; takım silme bu satırlara dokunmaz.
     */
    private volatile OnCallWeeklyNoticeRepository weeklyNotices;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setWeeklyNotices(OnCallWeeklyNoticeRepository repo) {
        this.weeklyNotices = repo;
    }

    /**
     * Bellek kuşağı (çok pod R2-A inceleme): anahtara girer ve her boşaltmada artar — boşaltmadan ÖNCE başlamış bir hesap
     * eski nöbetçiyi eski kuşağın anahtarına yazar, kimse okumaz ({@code TechErrorAnalyticsService} deseni).
     */
    private final java.util.concurrent.atomic.AtomicLong memoGeneration = new java.util.concurrent.atomic.AtomicLong();

    /** Yalnız bu pod (öteki pod'un yayını da bunu çağırır; yeniden yayın yok). */
    void memoClearLocal() {
        memoGeneration.incrementAndGet();
        memo.clear();
    }

    /** Çizelge / yer değiştirme değişti: bu pod'un belleği hemen, öteki pod'larınki veri yoluyla. */
    private void memoChanged() {
        memoClearLocal();
        CacheBus b = cacheBus;
        if (b != null) b.bump(CacheBusNames.ONCALL);
    }

    /** O anki nöbetçi (bildirim ve ekran). {@code until} = dilim bitişi (UTC ISO). */
    public record OnCallPerson(Long teamId, Long userId, String username, String displayName, String email, boolean active,
                               boolean override, String until) { }

    // ── Bildirim yolu ────────────────────────────────────────────────────────────────────────

    /**
     * {@code notifyOncall} açık takımların ŞU ANKİ nöbetçileri (etkin hesaplar). Takım kümesi başına 30 sn bellekli; hata
     * olursa boş harita (bildirim davranışı değişmez — nöbetçi eklenmez, WARN).
     */
    public Map<Long, OnCallPerson> notifyTargets(Collection<Long> teamIds) {
        if (teamIds == null || teamIds.isEmpty()) return Map.of();
        Set<Long> ids = new java.util.TreeSet<>();
        for (Long t : teamIds) if (t != null) ids.add(t);
        if (ids.isEmpty()) return Map.of();
        try {
            return memo.get(memoGeneration.get() + "|notify:" + ids, CACHE_MS, false, () -> computeTargets(ids, true, Instant.now()));
        } catch (Exception e) {
            log.warn("Nöbetçi okunamadı (bildirim nöbetçisiz sürer): {}", e.toString());
            return Map.of();
        }
    }

    /** Ekran: takımların şu anki nöbetçisi (bildirim seçeneğinden bağımsız; etkin çizelgeler). */
    public Map<Long, OnCallPerson> currentFor(Collection<Long> teamIds) {
        if (teamIds == null || teamIds.isEmpty()) return Map.of();
        Set<Long> ids = new java.util.TreeSet<>();
        for (Long t : teamIds) if (t != null) ids.add(t);
        if (ids.isEmpty()) return Map.of();
        return memo.get(memoGeneration.get() + "|current:" + ids, CACHE_MS, false, () -> computeTargets(ids, false, Instant.now()));
    }

    Map<Long, OnCallPerson> computeTargets(Set<Long> teamIds, boolean onlyNotify, Instant now) {
        Map<Long, OnCallSchedule> byTeam = new HashMap<>();
        for (OnCallSchedule s : schedules.findByTeamIdIn(teamIds)) {
            if (!Boolean.TRUE.equals(s.getEnabled())) continue;
            if (onlyNotify && !Boolean.TRUE.equals(s.getNotifyOncall())) continue;
            byTeam.put(s.getTeamId(), s);
        }
        if (byTeam.isEmpty()) return Map.of();
        Map<Long, List<OnCallRotation.Override>> ovr = new HashMap<>();
        for (OnCallOverride o : overrides.findByTeamIdInAndEndsAtGreaterThan(byTeam.keySet(), ISO.format(now))) {
            ovr.computeIfAbsent(o.getTeamId(), k -> new ArrayList<>()).add(toOverride(o));
        }
        Map<Long, OnCallRotation.Slot> slots = new LinkedHashMap<>();
        Set<Long> userIds = new LinkedHashSet<>();
        for (OnCallSchedule s : byTeam.values()) {
            OnCallRotation.Slot slot = OnCallRotation.current(config(s), ovr.getOrDefault(s.getTeamId(), List.of()), now);
            if (slot.userId() == null) continue;
            slots.put(s.getTeamId(), slot);
            userIds.add(slot.userId());
        }
        if (slots.isEmpty()) return Map.of();
        Map<Long, AppUser> byId = new HashMap<>();
        for (AppUser u : users.findAllById(userIds)) byId.put(u.getId(), u);
        Map<Long, OnCallPerson> out = new LinkedHashMap<>();
        for (Map.Entry<Long, OnCallRotation.Slot> e : slots.entrySet()) {
            AppUser u = byId.get(e.getValue().userId());
            if (u == null) continue;
            boolean active = !Boolean.FALSE.equals(u.getActive());
            if (onlyNotify && !active) continue;   // pasif hesaba bildirim gitmez (InactiveRecipientGuard ile aynı kural)
            out.put(e.getKey(), new OnCallPerson(e.getKey(), u.getId(), u.getUsername(), u.getDisplayName(), u.getEmail(), active,
                    e.getValue().override(), e.getValue().end() == null ? null : ISO.format(e.getValue().end())));
        }
        return out;
    }

    // ── Ekran / yönetim ──────────────────────────────────────────────────────────────────────

    /** Çizelge görünümü: ayarlar, sıralı üyeler (ad), şu anki nöbetçi, yaklaşan dilimler, gelecekteki yer değiştirmeler. */
    public Map<String, Object> view(Long teamId, Instant now) {
        OnCallSchedule s = schedules.findByTeamId(teamId).orElse(null);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("team_id", teamId);
        out.put("configured", s != null);
        OnCallRotation.Config cfg = s == null ? null : config(s);
        List<OnCallOverride> ovRows = overrides.findByTeamIdAndEndsAtGreaterThanOrderByStartsAtAscIdAsc(teamId, ISO.format(now));
        List<OnCallRotation.Override> ovs = ovRows.stream().map(OnCallService::toOverride).toList();
        Set<Long> ids = new LinkedHashSet<>();
        if (cfg != null) ids.addAll(cfg.members());
        for (OnCallOverride o : ovRows) ids.add(o.getUserId());
        Map<Long, AppUser> byId = new HashMap<>();
        if (!ids.isEmpty()) for (AppUser u : users.findAllById(ids)) byId.put(u.getId(), u);

        out.put("enabled", s != null && Boolean.TRUE.equals(s.getEnabled()));
        out.put("rotation", s == null ? OnCallSchedule.WEEKLY : s.getRotation());
        out.put("anchor", s == null ? null : s.getAnchor());
        out.put("notify_oncall", s != null && Boolean.TRUE.equals(s.getNotifyOncall()));
        out.put("updated_at", s == null ? null : s.getUpdatedAt());
        out.put("updated_by", s == null ? null : s.getUpdatedBy());
        putWeeklyNotice(out, teamId, s, now);
        List<Map<String, Object>> members = new ArrayList<>();
        if (cfg != null) for (Long id : cfg.members()) members.add(person(id, byId.get(id)));
        out.put("members", members);
        OnCallRotation.Slot cur = OnCallRotation.current(cfg, ovs, now);
        out.put("current", cur.userId() == null ? null : slotJson(cur, byId.get(cur.userId())));
        List<Map<String, Object>> next = new ArrayList<>();
        for (OnCallRotation.Slot sl : OnCallRotation.upcoming(cfg, now, 8)) next.add(slotJson(sl, byId.get(sl.userId())));
        out.put("upcoming", next);
        List<Map<String, Object>> ovOut = new ArrayList<>();
        for (OnCallOverride o : ovRows) {
            Map<String, Object> m = new LinkedHashMap<>(person(o.getUserId(), byId.get(o.getUserId())));
            m.put("id", o.getId());
            m.put("starts_at", o.getStartsAt());
            m.put("ends_at", o.getEndsAt());
            m.put("note", o.getNote());
            m.put("created_by", o.getCreatedBy());
            ovOut.add(m);
        }
        out.put("overrides", ovOut);
        return out;
    }

    private static Map<String, Object> person(Long id, AppUser u) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("user_id", id);
        m.put("name", u == null ? null : (u.getDisplayName() == null || u.getDisplayName().isBlank() ? u.getUsername() : u.getDisplayName()));
        m.put("username", u == null ? null : u.getUsername());
        m.put("active", u != null && !Boolean.FALSE.equals(u.getActive()));
        m.put("has_email", u != null && u.getEmail() != null && !u.getEmail().isBlank());
        m.put("missing", u == null);
        return m;
    }

    private static Map<String, Object> slotJson(OnCallRotation.Slot sl, AppUser u) {
        Map<String, Object> m = new LinkedHashMap<>(person(sl.userId(), u));
        m.put("starts_at", sl.start() == null ? null : ISO.format(sl.start()));
        m.put("ends_at", sl.end() == null ? null : ISO.format(sl.end()));
        m.put("override", sl.override());
        return m;
    }

    /** Çizelgeyi kaydeder (doğrulamalı). {@code memberIds} sıralı, çözülmüş kullanıcı kimlikleri. */
    public OnCallSchedule save(Long teamId, boolean enabled, String rotation, String anchor, List<Long> memberIds,
                               boolean notifyOncall, String actor) {
        return saveImpl(teamId, enabled, rotation, anchor, memberIds, notifyOncall, null, actor);
    }

    /**
     * Çizelge + haftalık bildirim ayarı tek kayıtta (2026-11-15). {@code notice} null / boş = bildirim kolonlarına dokunulmaz.
     * Bildirim doğrulaması HER ŞEYDEN ÖNCE (hatalı istek hiçbir şey yazmaz). Tek işlem: çizelgenin tam kaydı bildirim
     * kolonlarını YAZMAZ (varlıkta {@code updatable = false}); bildirim değişikliği hedefli tek kolonlu UPDATE'lerle gider.
     */
    @org.springframework.transaction.annotation.Transactional
    public OnCallSchedule save(Long teamId, boolean enabled, String rotation, String anchor, List<Long> memberIds,
                               boolean notifyOncall, WeeklyNoticePatch notice, String actor) {
        return saveImpl(teamId, enabled, rotation, anchor, memberIds, notifyOncall, notice, actor);
    }

    private OnCallSchedule saveImpl(Long teamId, boolean enabled, String rotation, String anchor, List<Long> memberIds,
                                    boolean notifyOncall, WeeklyNoticePatch notice, String actor) {
        OnCallSchedule existing = schedules.findByTeamId(teamId).orElse(null);
        OnCallSchedule noticeBefore = existing == null ? null : copyNotice(existing);   // okunan bildirim değerleri
        OnCallSchedule draft = existing == null ? new OnCallSchedule() : copyNotice(existing);
        boolean noticeChanged = notice != null && !notice.isEmpty() && applyNotice(draft, notice, Instant.now());
        String rot = OnCallSchedule.DAILY.equalsIgnoreCase(rotation) ? OnCallSchedule.DAILY : OnCallSchedule.WEEKLY;
        if (rotation != null && !rotation.isBlank() && !OnCallSchedule.DAILY.equalsIgnoreCase(rotation)
                && !OnCallSchedule.WEEKLY.equalsIgnoreCase(rotation)) {
            throw field("rotation", "Rotasyon günlük ya da haftalık olmalı.", "Rotation must be daily or weekly.");
        }
        if (anchor == null || !ANCHOR.matcher(anchor.trim()).matches()) {
            throw field("anchor", "İlk devir anını tarih ve saatiyle girin (yyyy-AA-GG SS:dd).",
                    "Enter the first handover with its date and time (yyyy-MM-dd HH:mm).");
        }
        try {
            LocalDateTime.parse(anchor.trim());
        } catch (Exception e) {
            throw field("anchor", "İlk devir anı geçerli bir tarih değil.", "The first handover is not a valid date.");
        }
        List<Long> members = new ArrayList<>();
        if (memberIds != null) for (Long id : memberIds) if (id != null && !members.contains(id)) members.add(id);
        if (members.size() > MAX_MEMBERS) {
            throw field("members", "Bir çizelgede en çok " + MAX_MEMBERS + " kişi olabilir.", "A schedule can have at most " + MAX_MEMBERS + " people.");
        }
        // Silinmiş hesap listeden DÜŞER (2026-10-22, UIA-A5 / K11): kullanıcı silinince çizelgenin member_ids'inden çıkarılmıyordu;
        // görünüm onu "silinmiş" (missing:true) gösteriyor ama her kayıt "Listedeki bir kişi bulunamadı; sayfayı yenileyin" ile
        // reddediliyordu — sayfayı yenilemek hiçbir şeyi değiştirmiyordu, çizelge hiç kaydedilemiyordu. Var olmayan kişi zaten hiç
        // nöbetçi olamaz; kayıtta sessizce düşer, bir INFO satırı kalır (yalnız adet).
        if (!members.isEmpty()) {
            Set<Long> found = new java.util.HashSet<>();
            for (AppUser u : users.findAllById(members)) found.add(u.getId());
            int before = members.size();
            members.removeIf(id -> !found.contains(id));
            if (members.size() < before) {
                log.info("Nöbet çizelgesi (takım #{}): artık var olmayan {} hesap listeden çıkarıldı", teamId, before - members.size());
            }
        }
        if (enabled && members.isEmpty()) {
            throw field("members", "Çizelgeyi açmak için en az bir nöbetçi ekleyin.", "Add at least one person to turn the schedule on.");
        }
        OnCallSchedule s = existing != null ? existing : new OnCallSchedule();
        if (existing == null) s.setTeamId(teamId);
        if (noticeChanged) {
            s.setWeeklyNoticeEnabled(draft.getWeeklyNoticeEnabled());
            s.setWeeklyNoticeMail(draft.getWeeklyNoticeMail());
            s.setWeeklyNoticePush(draft.getWeeklyNoticePush());
            s.setWeeklyNoticeDay(draft.getWeeklyNoticeDay());
            s.setWeeklyNoticeTime(draft.getWeeklyNoticeTime());
            s.setWeeklyNoticeSince(draft.getWeeklyNoticeSince());
        }
        s.setEnabled(enabled);
        s.setRotation(rot);
        s.setAnchor(anchor.trim());
        s.setMemberIds(members.isEmpty() ? null : String.join(",", members.stream().map(String::valueOf).toList()));
        s.setNotifyOncall(notifyOncall);
        s.setUpdatedAt(ISO.format(Instant.now()));
        s.setUpdatedBy(actor);
        OnCallSchedule saved = schedules.save(s);
        // Var olan çizelgede bildirim kolonları tam kayıtla YAZILMAZ: yalnız isteğin taşıdığı ve değişen kolonlar hedefli
        if (noticeChanged && noticeBefore != null) writeNoticeColumns(teamId, notice, noticeBefore, draft);
        memoChanged();
        return saved;
    }

    /** Yer değiştirme ekler (kişi var olmalı; bitiş başlangıçtan sonra; en çok 90 gün). Eski satırları budar. */
    public OnCallOverride addOverride(Long teamId, Long userId, String startsAt, String endsAt, String note, String actor) {
        Instant start = parse(startsAt, "starts_at");
        Instant end = parse(endsAt, "ends_at");
        if (!end.isAfter(start)) throw field("ends_at", "Bitiş başlangıçtan sonra olmalı.", "The end must be after the start.");
        if (Duration.between(start, end).toDays() > 90) {
            throw field("ends_at", "Bir yer değiştirme en çok 90 gün sürebilir.", "An override can last at most 90 days.");
        }
        if (userId == null || users.findById(userId).isEmpty()) {
            throw field("user_id", "Nöbeti devralacak kişiyi seçin.", "Pick the person taking over the shift.");
        }
        // Çizelgesiz takımda yer değiştirme hiçbir yerde geçerli olmaz (bildirim, takım satırı, 7/24) — sessizce kaydedilmez.
        if (schedules.findByTeamId(teamId).isEmpty()) {
            throw field("user_id", "Yer değiştirme eklemeden önce takımın nöbet çizelgesini kaydedin.",
                    "Save the team's on-call schedule before adding an override.");
        }
        OnCallOverride o = new OnCallOverride();
        o.setTeamId(teamId);
        o.setUserId(userId);
        o.setStartsAt(ISO.format(start));
        o.setEndsAt(ISO.format(end));
        o.setNote(note == null ? null : (note.length() > 200 ? note.substring(0, 200) : note.trim()));
        o.setCreatedAt(ISO.format(Instant.now()));
        o.setCreatedBy(actor);
        OnCallOverride saved = overrides.save(o);
        try {
            overrides.deleteEndedBefore(ISO.format(Instant.now().minus(Duration.ofDays(OVERRIDE_RETENTION_DAYS))));
        } catch (Exception e) {
            log.debug("eski nöbet yer değiştirmeleri budanamadı: {}", e.toString());
        }
        memoChanged();
        return saved;
    }

    /** Takım silinince: çizelge ve yer değiştirmeler (hata takım silmeyi geri almaz). */
    public void deleteForTeam(Long teamId) {
        try {
            schedules.findByTeamId(teamId).ifPresent(schedules::delete);
            overrides.deleteByTeam(teamId);
            OnCallWeeklyNoticeRepository wn = weeklyNotices;
            if (wn != null) wn.deleteByTeam(teamId);      // haftalık bildirim gönderim kayıtları (2026-11-15)
        } catch (Exception e) {
            log.warn("Silinen takımın nöbet çizelgesi temizlenemedi (takım {}): {}", teamId, e.toString());
        }
        memoChanged();
    }

    public OnCallOverride findOverride(Long id) {
        return id == null ? null : overrides.findById(id).orElse(null);
    }

    public void deleteOverride(OnCallOverride o) {
        overrides.delete(o);
        memoChanged();
    }

    // ── Haftalık nöbet bildirimi (2026-11-15) ───────────────────────────────────────────────

    /**
     * Haftalık bildirim ayarı değişikliği — YALNIZ gönderilen alanlar (null = dokunma). Takım kendisi açar (varsayılan
     * kapalı); kanal e-posta (takımın alarm adresi) ve / veya push (takım üyeleri); gün 1–7 (Pazartesi = 1), saat "HH:mm".
     */
    public record WeeklyNoticePatch(Boolean enabled, Boolean mail, Boolean push, Integer day, String time) {
        public boolean isEmpty() {
            return enabled == null && mail == null && push == null && day == null && time == null;
        }
    }

    /** Yalnız bildirim ayarı kaydı sonucu: {@code changed} false = hiçbir şey yazılmadı (denetim de yok). */
    public record WeeklyNoticeSave(OnCallSchedule schedule, boolean changed) { }

    /**
     * Yalnız haftalık bildirim ayarını kaydeder (çizelgenin geri kalanına dokunmayan hedefli UPDATE). Çizelge kaydedilmemişse
     * 400 ({@code weekly_notice.enabled}). Değişiklik yoksa yazmaz.
     */
    @org.springframework.transaction.annotation.Transactional
    public WeeklyNoticeSave saveWeeklyNotice(Long teamId, WeeklyNoticePatch patch, String actor) {
        OnCallSchedule existing = schedules.findByTeamId(teamId).orElse(null);
        if (existing == null) {
            throw field("weekly_notice.enabled", "Haftalık bildirimi açmadan önce takımın nöbet çizelgesini kaydedin.",
                    "Save the team's on-call schedule before turning on the weekly notice.");
        }
        if (patch == null || patch.isEmpty()) return new WeeklyNoticeSave(existing, false);
        Instant now = Instant.now();
        OnCallSchedule draft = copyNotice(existing);
        if (!applyNotice(draft, patch, now)) return new WeeklyNoticeSave(existing, false);
        String stamp = ISO.format(now);
        if (schedules.touchUpdated(teamId, stamp, actor) == 0) throw new java.util.NoSuchElementException("schedule");
        writeNoticeColumns(teamId, patch, existing, draft);
        OnCallSchedule out = existing;
        out.setWeeklyNoticeEnabled(draft.getWeeklyNoticeEnabled());
        out.setWeeklyNoticeMail(draft.getWeeklyNoticeMail());
        out.setWeeklyNoticePush(draft.getWeeklyNoticePush());
        out.setWeeklyNoticeDay(draft.getWeeklyNoticeDay());
        out.setWeeklyNoticeTime(draft.getWeeklyNoticeTime());
        out.setWeeklyNoticeSince(draft.getWeeklyNoticeSince());
        out.setUpdatedAt(stamp);
        out.setUpdatedBy(actor);
        return new WeeklyNoticeSave(out, true);
    }

    /**
     * Bildirim kolonlarını YALNIZ isteğin taşıdığı ve okunan değerden farklı olanlar için, kolon başına hedefli UPDATE ile
     * yazar (inceleme 2026-11-15): iki yöneticinin eşzamanlı farklı alan değişikliği (biri gün, öteki saat) birbirini ezmez;
     * isteğin taşımadığı kolon (bayat okunmuş değer) geri yazılmaz. {@code since} yalnız zamanlama değiştiyse.
     */
    private void writeNoticeColumns(Long teamId, WeeklyNoticePatch p, OnCallSchedule before, OnCallSchedule after) {
        OnCallWeeklyNoticeSlots.Settings b = OnCallWeeklyNoticeSlots.Settings.of(before);
        OnCallWeeklyNoticeSlots.Settings a = OnCallWeeklyNoticeSlots.Settings.of(after);
        if (p.enabled() != null && a.enabled() != b.enabled()) schedules.setWeeklyNoticeEnabled(teamId, a.enabled());
        if (p.mail() != null && a.mail() != b.mail()) schedules.setWeeklyNoticeMail(teamId, a.mail());
        if (p.push() != null && a.push() != b.push()) schedules.setWeeklyNoticePush(teamId, a.push());
        if (p.day() != null && a.day() != b.day()) schedules.setWeeklyNoticeDay(teamId, a.day());
        if (p.time() != null && !a.time().equals(b.time())) schedules.setWeeklyNoticeTime(teamId, a.time());
        if (!java.util.Objects.equals(before.getWeeklyNoticeSince(), after.getWeeklyNoticeSince())) {
            schedules.setWeeklyNoticeSince(teamId, after.getWeeklyNoticeSince());
        }
    }

    /** Denetim farkı için ayarın etkin (varsayılanları uygulanmış) hâli; çizelge yoksa boş. */
    public Map<String, Object> weeklyNoticeSnapshot(Long teamId) {
        return noticeSnapshot(schedules.findByTeamId(teamId).orElse(null));
    }

    /** Satırın bildirim ayarı — denetim farkı anahtarları (camelCase). */
    public static Map<String, Object> noticeSnapshot(OnCallSchedule s) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (s == null) return m;
        OnCallWeeklyNoticeSlots.Settings st = OnCallWeeklyNoticeSlots.Settings.of(s);
        m.put("weeklyNoticeEnabled", st.enabled());
        m.put("weeklyNoticeMail", st.mail());
        m.put("weeklyNoticePush", st.push());
        m.put("weeklyNoticeDay", st.day());
        m.put("weeklyNoticeTime", st.time());
        return m;
    }

    /**
     * Değişikliği satıra uygular (doğrulamalı; hatada satır değişmez). Değişen yoksa false. "Açık + hiç kanal yok" reddedilir
     * — yalnız açma / kanal alanlarından biri gönderildiyse (değişmeyen değer yeniden doğrulanmaz). Açma ya da gün / saat
     * değişince {@code weeklyNoticeSince} = şimdi ("zamanı kaçırıldı" izi o andan önceki dilimi saymaz).
     */
    static boolean applyNotice(OnCallSchedule s, WeeklyNoticePatch p, Instant now) {
        if (p.day() != null && !OnCallWeeklyNoticeSlots.validDay(p.day())) {
            throw field("weekly_notice.day", "Gün Pazartesi (1) ile Pazar (7) arasında olmalı.", "The day must be Monday (1) to Sunday (7).");
        }
        String time = p.time() == null ? null : p.time().trim();
        if (time != null && !OnCallWeeklyNoticeSlots.validTime(time)) {
            throw field("weekly_notice.time", "Saati SS:dd biçiminde girin (ör. 08:30).", "Enter the time as HH:mm (e.g. 08:30).");
        }
        OnCallWeeklyNoticeSlots.Settings before = OnCallWeeklyNoticeSlots.Settings.of(s);
        boolean enabled = p.enabled() != null ? p.enabled() : before.enabled();
        boolean mail = p.mail() != null ? p.mail() : before.mail();
        boolean push = p.push() != null ? p.push() : before.push();
        int day = p.day() != null ? p.day() : before.day();
        String t = time != null ? time : before.time();
        boolean channelTouched = p.enabled() != null || p.mail() != null || p.push() != null;
        if (channelTouched && enabled && !mail && !push) {
            throw field("weekly_notice.mail", "Bildirim açıkken en az bir kanal (e-posta ya da push) seçin.",
                    "Pick at least one channel (e-mail or push) while the notice is on.");
        }
        boolean changed = enabled != before.enabled() || mail != before.mail() || push != before.push()
                || day != before.day() || !t.equals(before.time());
        if (!changed) return false;
        boolean timing = enabled && (!before.enabled() || day != before.day() || !t.equals(before.time()));
        s.setWeeklyNoticeEnabled(enabled);
        s.setWeeklyNoticeMail(mail);
        s.setWeeklyNoticePush(push);
        s.setWeeklyNoticeDay(day);
        s.setWeeklyNoticeTime(t);
        if (timing) s.setWeeklyNoticeSince(ISO.format(now));
        return true;
    }

    private static OnCallSchedule copyNotice(OnCallSchedule s) {
        OnCallSchedule c = new OnCallSchedule();
        c.setWeeklyNoticeEnabled(s.getWeeklyNoticeEnabled());
        c.setWeeklyNoticeMail(s.getWeeklyNoticeMail());
        c.setWeeklyNoticePush(s.getWeeklyNoticePush());
        c.setWeeklyNoticeDay(s.getWeeklyNoticeDay());
        c.setWeeklyNoticeTime(s.getWeeklyNoticeTime());
        c.setWeeklyNoticeSince(s.getWeeklyNoticeSince());
        return c;
    }

    /**
     * Görünüme ayar + sıradaki gönderim + son sonuç. Gönderim kayıtları okunamazsa {@code weekly_notice_next} /
     * {@code weekly_notice_last} null ve {@code weekly_notice_unreadable} true (bilinmeyen "gönderilmedi" değildir).
     */
    private void putWeeklyNotice(Map<String, Object> out, Long teamId, OnCallSchedule s, Instant now) {
        OnCallWeeklyNoticeSlots.Settings st = OnCallWeeklyNoticeSlots.Settings.of(s);
        Map<String, Object> wn = new LinkedHashMap<>();
        wn.put("enabled", st.enabled());
        wn.put("mail", st.mail());
        wn.put("push", st.push());
        wn.put("day", st.day());
        wn.put("time", st.time());
        out.put("weekly_notice", wn);
        OnCallWeeklyNoticeRepository repo = weeklyNotices;
        try {
            // Hafta "bitti" = satırı var ve yeniden denenmeyecek (yeniden denenecek FAILED / e-postası iletilemeyen PARTIAL
            // bitmiş sayılmaz — inceleme 2026-11-15)
            Instant next = OnCallWeeklyNoticeSlots.next(st, now, repo == null ? null
                    : key -> repo.findByTeamIdAndWeekKey(teamId, key).map(r -> !OnCallWeeklyNoticeService.willRetry(r)).orElse(false));
            out.put("weekly_notice_next", next == null ? null : ISO.format(next));
            OnCallWeeklyNotice last = repo == null ? null : repo.findTopByTeamIdOrderByWeekKeyDescIdDesc(teamId).orElse(null);
            out.put("weekly_notice_last", last == null ? null : lastJson(last, now));
        } catch (RuntimeException e) {
            log.debug("Haftalık nöbet bildirimi kayıtları okunamadı (takım {}): {}", teamId, e.toString());
            out.put("weekly_notice_next", null);
            out.put("weekly_notice_last", null);
            out.put("weekly_notice_unreadable", true);
        }
    }

    /**
     * Son satır: hafta, durum, neden, an, posta / push sınıfı (ham metin yok), deneme sayısı, {@code retry} (pencere içinde
     * yeniden denenecek mi — yoksa sonuç kesin) ve {@code stale} (bayat SENDING: sonuç bilinmiyor).
     */
    private static Map<String, Object> lastJson(OnCallWeeklyNotice n, Instant now) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("week", n.getWeekKey());
        m.put("status", n.getStatus());
        m.put("reason", n.getReason());
        m.put("at", n.getFinishedAt() != null ? n.getFinishedAt() : n.getClaimedAt());
        m.put("slot_at", n.getSlotAt());
        m.put("mail", mailOutcome(n.getMailStatus()));
        m.put("push", n.getPushStatus());
        m.put("push_count", n.getPushCount());
        m.put("attempts", n.getAttempts());
        m.put("max_attempts", OnCallWeeklyNoticeService.MAX_ATTEMPTS);
        Instant slot = OnCallWeeklyNoticeSlots.parseIso(n.getSlotAt());
        boolean retry = OnCallWeeklyNoticeService.willRetry(n) && slot != null
                && now.isBefore(OnCallWeeklyNoticeSlots.catchUpEnd(slot));
        m.put("retry", retry);
        Instant claimed = OnCallWeeklyNoticeSlots.parseIso(n.getClaimedAt());
        m.put("stale", OnCallWeeklyNotice.SENDING.equals(n.getStatus()) && claimed != null
                && claimed.isBefore(now.minus(OnCallWeeklyNoticeService.STALE_SENDING)));
        return m;
    }

    /**
     * Posta durumu → sınıf (ham SMTP metni istemciye gitmez): delivered | queued (posta sunucusu yeniden deneyecek) |
     * skipped | failed | off | null.
     */
    static String mailOutcome(String mailStatus) {
        if (mailStatus == null || mailStatus.isBlank()) return null;
        if (mailStatus.startsWith(OnCallWeeklyNoticeService.MAIL_OFF)) return "off";
        if (mailStatus.startsWith("SENT")) return "delivered";
        if (mailStatus.startsWith("QUEUED")) return "queued";
        if (mailStatus.startsWith("SKIPPED")) return "skipped";
        return "failed";
    }

    // ── yardımcılar ──────────────────────────────────────────────────────────────────────────

    static OnCallRotation.Config config(OnCallSchedule s) {

        List<Long> members = new ArrayList<>();
        if (s.getMemberIds() != null) for (String p : s.getMemberIds().split(",")) {
            String v = p.trim();
            if (v.isEmpty()) continue;
            try { members.add(Long.parseLong(v)); } catch (NumberFormatException ignored) { /* bozuk satır atlanır */ }
            if (members.size() >= MAX_MEMBERS) break;
        }
        LocalDateTime anchor = null;
        try { anchor = s.getAnchor() == null ? null : LocalDateTime.parse(s.getAnchor()); } catch (Exception ignored) { /* null */ }
        return new OnCallRotation.Config(Boolean.TRUE.equals(s.getEnabled()), OnCallSchedule.DAILY.equals(s.getRotation()) ? 1 : 7,
                anchor, members);
    }

    static OnCallRotation.Override toOverride(OnCallOverride o) {
        return new OnCallRotation.Override(o.getId() == null ? 0 : o.getId(), o.getUserId(),
                parseIso(o.getStartsAt()), parseIso(o.getEndsAt()));
    }

    private static Instant parseIso(String s) {
        try {
            return LocalDateTime.parse(s.length() > 19 ? s.substring(0, 19) : s).toInstant(ZoneOffset.UTC);
        } catch (Exception e) {
            return Instant.EPOCH;
        }
    }

    /** İstemci zamanı: UTC ISO ("…Z" ya da eksiz). */
    private static Instant parse(String v, String fieldName) {
        if (v == null || v.isBlank() || v.length() > 40) throw field(fieldName, "Tarih ve saati girin.", "Enter the date and time.");
        try {
            String t = v.trim();
            return t.endsWith("Z") ? Instant.parse(t) : LocalDateTime.parse(t.length() > 19 ? t.substring(0, 19) : t).toInstant(ZoneOffset.UTC);
        } catch (Exception e) {
            throw field(fieldName, "Tarih ve saat geçerli değil.", "The date and time are not valid.");
        }
    }

    private static RuntimeException field(String name, String tr, String en) {
        return new com.sitemonitor.config.GlobalExceptionHandler.FieldValidationException(name, Msg.t(tr, en));
    }
}
