package com.sitemonitor.service.oncall;

import com.sitemonitor.model.AppUser;
import com.sitemonitor.model.OnCallOverride;
import com.sitemonitor.model.OnCallSchedule;
import com.sitemonitor.repository.AppUserRepository;
import com.sitemonitor.repository.OnCallOverrideRepository;
import com.sitemonitor.repository.OnCallScheduleRepository;
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
        if (bus != null) bus.register(CacheBusNames.ONCALL, memo::clear);
    }

    /** Çizelge / yer değiştirme değişti: bu pod'un belleği hemen, öteki pod'larınki veri yoluyla. */
    private void memoChanged() {
        memo.clear();
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
            return memo.get("notify:" + ids, CACHE_MS, false, () -> computeTargets(ids, true, Instant.now()));
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
        return memo.get("current:" + ids, CACHE_MS, false, () -> computeTargets(ids, false, Instant.now()));
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
        OnCallSchedule s = schedules.findByTeamId(teamId).orElseGet(() -> {
            OnCallSchedule n = new OnCallSchedule();
            n.setTeamId(teamId);
            return n;
        });
        s.setEnabled(enabled);
        s.setRotation(rot);
        s.setAnchor(anchor.trim());
        s.setMemberIds(members.isEmpty() ? null : String.join(",", members.stream().map(String::valueOf).toList()));
        s.setNotifyOncall(notifyOncall);
        s.setUpdatedAt(ISO.format(Instant.now()));
        s.setUpdatedBy(actor);
        OnCallSchedule saved = schedules.save(s);
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
