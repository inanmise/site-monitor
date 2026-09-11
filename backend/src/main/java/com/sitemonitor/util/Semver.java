package com.sitemonitor.util;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Uygulama sürümü karşılaştırması (Sürüm & Dağıtım Geçmişi). {@code VersionLabels.SEMVER} script
 * sürümlemesi için ve private; burada 20 satırlık ayrı bir tip daha temiz: "v" öneki ve "-rc"
 * benzeri son ekler tolere edilir, ayrıştırılamayan sürüm {@link Optional#empty()} (kilitlemez).
 */
public record Semver(int major, int minor, int patch) implements Comparable<Semver> {

    private static final Pattern P = Pattern.compile("^v?(\\d+)\\.(\\d+)\\.(\\d+)");

    public static Optional<Semver> parse(String s) {
        if (s == null) return Optional.empty();
        Matcher m = P.matcher(s.trim());
        if (!m.find()) return Optional.empty();
        try {
            return Optional.of(new Semver(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3))));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    /** İkisi de ayrıştırılıyorsa karşılaştırma sonucu, aksi halde empty ("yön bilinmiyor"). */
    public static Optional<Integer> compare(String a, String b) {
        Optional<Semver> x = parse(a), y = parse(b);
        if (x.isEmpty() || y.isEmpty()) return Optional.empty();
        return Optional.of(x.get().compareTo(y.get()));
    }

    /** Bump türü sürüm farkından: major | minor | patch | same; ayrıştırılamıyorsa unknown. */
    public static String bumpKind(String from, String to) {
        Optional<Semver> a = parse(from), b = parse(to);
        if (a.isEmpty() || b.isEmpty()) return "unknown";
        if (a.get().major != b.get().major) return "major";
        if (a.get().minor != b.get().minor) return "minor";
        if (a.get().patch != b.get().patch) return "patch";
        return "same";
    }

    @Override
    public int compareTo(Semver o) {
        if (major != o.major) return Integer.compare(major, o.major);
        if (minor != o.minor) return Integer.compare(minor, o.minor);
        return Integer.compare(patch, o.patch);
    }

    @Override
    public String toString() { return major + "." + minor + "." + patch; }
}
