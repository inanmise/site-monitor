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
