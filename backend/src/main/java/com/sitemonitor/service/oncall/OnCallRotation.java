package com.sitemonitor.service.oncall;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Nöbet rotasyonu — SAF hesap (2026-10-11). Dönem uzunluğu günlük ya da haftalık; ilk dönem {@code anchor}'da (İstanbul
 * yerel saati) başlar ve sıralı üyeler dönem başına bir kişi döner. Yer değiştirme (override) aralığına giren an için
 * rotasyon yerine o kişi; çakışmada EN SON eklenen kazanır. İstanbul'da yaz saati yok (UTC+3 sabit) — dönemler tam
 * 24 / 168 saattir. Döngü sınırlı: yaklaşan dönem sayısı en çok {@link #MAX_UPCOMING}.
 */
public final class OnCallRotation {

    private OnCallRotation() {}

    public static final ZoneId TZ = ZoneId.of("Europe/Istanbul");
    public static final int MAX_UPCOMING = 30;

    /** Rotasyon yapılandırması. {@code periodDays} 1 (günlük) ya da 7 (haftalık). */
    public record Config(boolean enabled, int periodDays, LocalDateTime anchor, List<Long> members) { }

    /** Yer değiştirme (kimlik sıralaması = ekleme sırası). */
    public record Override(long id, long userId, Instant start, Instant end) { }

    /** Bir nöbet dilimi: kim, ne zamandan ne zamana, yer değiştirme mi. {@code userId} null → nöbetçi yok. */
    public record Slot(Long userId, Instant start, Instant end, boolean override, Long overrideId) { }

    public static Instant anchorInstant(Config c) {
        return c.anchor().atZone(TZ).toInstant();
    }

    static Duration period(Config c) {
        return Duration.ofDays(c.periodDays() <= 1 ? 1 : 7);
    }

    /** Rotasyonun (yer değiştirme hariç) {@code at} anındaki dilimi; başlamadıysa ya da üye yoksa null kullanıcılı dilim. */
    public static Slot baseSlot(Config c, Instant at) {
        if (c == null || !c.enabled() || c.members() == null || c.members().isEmpty() || c.anchor() == null) {
            return new Slot(null, at, null, false, null);
        }
        Instant anchor = anchorInstant(c);
        Duration p = period(c);
        if (at.isBefore(anchor)) return new Slot(null, at, anchor, false, null);
        long idx = Duration.between(anchor, at).toMillis() / p.toMillis();
        Instant start = anchor.plus(p.multipliedBy(idx));
        Long who = c.members().get((int) Math.floorMod(idx, (long) c.members().size()));
        return new Slot(who, start, start.plus(p), false, null);
    }

    /**
     * {@code at} anındaki nöbetçi: geçerli yer değiştirme varsa o (en son eklenen), yoksa rotasyon. Yer değiştirme yalnız ETKİN
     * çizelgede geçerlidir (2026-10-12): bildirim ve takım satırı çizelgesiz takımı atladığından pencere de "nöbetçi yok" der.
     */
    public static Slot current(Config c, List<Override> overrides, Instant at) {
        Override o = activeOverride(overrides, at);
        if (o != null && c != null && c.enabled()) return new Slot(o.userId(), o.start(), o.end(), true, o.id());
        return baseSlot(c, at);
    }

    static Override activeOverride(List<Override> overrides, Instant at) {
        if (overrides == null) return null;
        return overrides.stream()
                .filter(o -> !at.isBefore(o.start()) && at.isBefore(o.end()))
                .max(Comparator.comparingLong(Override::id))
                .orElse(null);
    }

    /** Haftalık bildirimde gösterilecek dilim sınırı (7 günde günlük rotasyon + yer değiştirmeler; fazlası kesilir). */
    public static final int MAX_TIMELINE = 50;

    /**
     * {@code [from, to)} aralığının nöbet zaman çizelgesi (2026-11-15, haftalık nöbet bildirimi): rotasyon devirleri VE yer
     * değiştirme başlangıç / bitişleri sınır alınır, her parça {@link #current} ile çözülür (aynı öncelik kuralı — yeniden
     * türetilmez); aynı kişinin (ve aynı yer değiştirme bayrağının) art arda parçaları birleşir. En çok {@code max} parça
     * ({@link #MAX_TIMELINE} tavanlı). Yer değiştirme listesi çağıranın verdiği kadardır (sınırlı sorgu).
     */
    public static List<Slot> timeline(Config c, List<Override> overrides, Instant from, Instant to, int max) {
        return timelineCounted(c, overrides, from, to, max).slots();
    }

    /**
     * Zaman çizelgesi + sığmayan parça sayısı (inceleme 2026-11-15: tavan sessizce kesiyordu). {@code omitted} = tavandan
     * sonra gelen (birleştirilmiş) parça adedi; 0 = tamamı listede.
     */
    public record Timeline(List<Slot> slots, int omitted) {
        public Timeline {
            slots = slots == null ? List.of() : List.copyOf(slots);
            omitted = Math.max(0, omitted);
        }
    }

    /** {@link #timeline} ile aynı kural; ayrıca tavanı aşan parçaları sayar (döngüler aynı sınırlarla). */
    public static Timeline timelineCounted(Config c, List<Override> overrides, Instant from, Instant to, int max) {
        List<Slot> out = new ArrayList<>();
        if (from == null || to == null || !to.isAfter(from)) return new Timeline(out, 0);
        int cap = Math.max(1, Math.min(max, MAX_TIMELINE));
        java.util.TreeSet<Instant> cuts = new java.util.TreeSet<>();
        cuts.add(from);
        cuts.add(to);
        if (c != null && c.enabled() && c.anchor() != null && c.members() != null && !c.members().isEmpty()) {
            Instant anchor = anchorInstant(c);
            Duration p = period(c);
            if (anchor.isAfter(from) && anchor.isBefore(to)) cuts.add(anchor);
            Instant t = from.isBefore(anchor) ? anchor : baseSlot(c, from).end();
            for (int i = 0; t != null && t.isBefore(to) && i < 400; i++) {   // 7 günde en çok 8 devir; sınır güvenlik ağı
                if (t.isAfter(from)) cuts.add(t);
                t = t.plus(p);
            }
        }
        if (overrides != null) {
            int n = 0;
            for (Override o : overrides) {
                if (++n > 500) break;
                if (o.start() != null && o.start().isAfter(from) && o.start().isBefore(to)) cuts.add(o.start());
                if (o.end() != null && o.end().isAfter(from) && o.end().isBefore(to)) cuts.add(o.end());
            }
        }
        Instant prev = null;
        int omitted = 0;
        Slot lastOmitted = null;            // tavandan sonraki son parça (birleşme kuralı sayımda da geçerli)
        for (Instant cut : cuts) {
            if (prev != null) {
                Slot s = current(c, overrides, prev);
                if (omitted > 0) {
                    if (!(java.util.Objects.equals(lastOmitted.userId(), s.userId()) && lastOmitted.override() == s.override())) {
                        omitted++;
                        lastOmitted = s;
                    }
                } else {
                    Slot last = out.isEmpty() ? null : out.get(out.size() - 1);
                    if (last != null && java.util.Objects.equals(last.userId(), s.userId()) && last.override() == s.override()) {
                        out.set(out.size() - 1, new Slot(last.userId(), last.start(), cut, last.override(), last.overrideId()));
                    } else if (out.size() >= cap) {
                        omitted = 1;
                        lastOmitted = s;
                    } else {
                        out.add(new Slot(s.userId(), prev, cut, s.override(), s.override() ? s.overrideId() : null));
                    }
                }
            }
            prev = cut;
        }
        return new Timeline(out, omitted);
    }

    /**
     * Şu andan başlayarak rotasyonun yaklaşan dilimleri (en çok {@code count}, sınır {@link #MAX_UPCOMING}). Yer
     * değiştirmeler ayrıca listelenir; burada yalnız rotasyon sırası.
     */
    public static List<Slot> upcoming(Config c, Instant now, int count) {
        List<Slot> out = new ArrayList<>();
        int n = Math.max(0, Math.min(count, MAX_UPCOMING));
        if (c == null || !c.enabled() || c.members() == null || c.members().isEmpty() || c.anchor() == null) return out;
        Instant anchor = anchorInstant(c);
        Instant t = now.isBefore(anchor) ? anchor : now;
        for (int i = 0; i < n; i++) {
            Slot s = baseSlot(c, t);
            if (s.userId() == null || s.end() == null) break;
            out.add(s);
            t = s.end();
        }
        return out;
    }
}
