package com.sitemonitor.service.ldap;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * AD grubu → sistem rolü eşlemesi (2026-10-11, kullanıcı kararı: "açılırsa, elle değişeni ezmez") — SAF kural: ağ yok,
 * veritabanı yok. Giriş/eşitleme ({@code LdapProvisioningService}) ve "kim ne olacak" önizlemesi
 * ({@code LdapRoleMappingPreviewService}) AYNI kararı buradan alır.
 *
 * <p><b>Eşleşme.</b> Kural metni {@code =} VE virgül içeriyorsa TAM DN'dir: kullanıcının {@code memberOf} DN'lerinden biriyle
 * (büyük/küçük harf ve virgül çevresindeki boşluklar yok sayılarak) birebir eşleşmelidir. Değilse grup ADIDIR (CN; "CN=" öneki olabilir):
 * DN'nin ilk RDN'i {@code CN=<ad>} olan grup eşleşir. "Alt dize" eşleşmesi BİLİNÇLİ yok — "Admin" kuralı "NotAdmins"
 * grubunu yakalamamalı. Yalnız DOĞRUDAN üyelikler sayılır (AD {@code memberOf}'u iç içe grupları açmaz).
 *
 * <p><b>Öncelik.</b> Listedeki İLK eşleşen kural geçerlidir (sıra yöneticinin elinde).
 *
 * <p><b>Eşleşme yoksa ({@code fallback}).</b> {@code null}/boş = BUGÜNKÜ kural (ürün sahibi / müdür → TEAM_ADMIN,
 * diğerleri USER; elle verilmiş ADMIN/AUDIT korunur). Sabit bir rol de seçilebilir; o da ADMIN/AUDIT'i düşürmez.
 */
public final class LdapRoleMapping {

    private LdapRoleMapping() {}

    /** Eşlenebilen sistem rolleri (AppUser.systemRole). */
    public static final List<String> ROLES = List.of("ADMIN", "TEAM_ADMIN", "AUDIT", "USER");
    /** Eşleşme yoksa bugünkü kural. */
    public static final String FALLBACK_DERIVED = "DERIVED";
    /** Bir yapılandırmadaki en çok kural ve kural metninin en çok uzunluğu (girdi sınırı). */
    public static final int MAX_RULES = 50;
    public static final int MAX_GROUP_LENGTH = 512;

    /** Tek kural: grup (DN ya da CN) → rol. */
    public record Rule(String group, String role) {
        public boolean isDn() { return isDnSpec(group); }
    }

    /** Tam DN mi: {@code =} VE virgül içerir. Tek RDN'li "CN=Ad" grup ADIDIR (ekrandaki örnek "CN=CertAdmins" gibi). */
    public static boolean isDnSpec(String spec) {
        return spec != null && spec.contains("=") && spec.contains(",");
    }

    /** Karar kaynağı: GROUP = açık kural eşleşti, FALLBACK = sabit varsayılan rol, DERIVED = bugünkü kural. */
    public enum Source { GROUP, FALLBACK, DERIVED }

    /** Karar: hedef rol ({@code DERIVED}'da null) + kaynağı + eşleşen kural (yoksa null). */
    public record Decision(String role, Source source, Rule rule) {}

    /** Geçerli rol mü (büyük harfe çevrilerek). */
    public static boolean isRole(String role) {
        return role != null && ROLES.contains(role.trim().toUpperCase(Locale.ROOT));
    }

    /** Ayar kaydındaki {group, role} haritalarını kurallara çevirir; boş/geçersiz satırlar atlanır, sıra korunur. */
    public static List<Rule> rulesOf(Collection<? extends Map<String, String>> raw) {
        List<Rule> out = new ArrayList<>();
        if (raw == null) return out;
        for (Map<String, String> m : raw) {
            if (m == null) continue;
            String g = m.get("group");
            String r = m.get("role");
            if (g == null || g.isBlank() || !isRole(r)) continue;
            out.add(new Rule(g.trim(), r.trim().toUpperCase(Locale.ROOT)));
            if (out.size() >= MAX_RULES) break;
        }
        return out;
    }

    /** Kararı verir. {@code groups} = kullanıcının doğrudan grup DN'leri (memberOf). */
    public static Decision decide(List<Rule> rules, String fallback, Collection<String> groups) {
        Rule hit = firstMatch(rules, groups);
        if (hit != null) return new Decision(hit.role(), Source.GROUP, hit);
        String fb = normalizeFallback(fallback);
        return FALLBACK_DERIVED.equals(fb)
                ? new Decision(null, Source.DERIVED, null)
                : new Decision(fb, Source.FALLBACK, null);
    }

    /** "DERIVED" ya da geçerli bir rol; bilinmeyen/boş → DERIVED. */
    public static String normalizeFallback(String fallback) {
        if (fallback == null || fallback.isBlank()) return FALLBACK_DERIVED;
        String f = fallback.trim().toUpperCase(Locale.ROOT);
        return ROLES.contains(f) ? f : FALLBACK_DERIVED;
    }

    /** Listedeki İLK eşleşen kural (yoksa null). */
    public static Rule firstMatch(List<Rule> rules, Collection<String> groups) {
        if (rules == null || rules.isEmpty() || groups == null || groups.isEmpty()) return null;
        List<String> dns = new ArrayList<>();
        List<String> cns = new ArrayList<>();
        for (String g : groups) {
            if (g == null || g.isBlank()) continue;
            dns.add(normalizeDn(g));
            String cn = firstCn(g);
            if (cn != null) cns.add(cn.toLowerCase(Locale.ROOT));
        }
        for (Rule r : rules) {
            if (matches(r, dns, cns)) return r;
        }
        return null;
    }

    /** Kural, normalize DN'ler / küçük harfli CN'ler kümesiyle eşleşir mi. */
    static boolean matches(Rule r, Collection<String> normalizedDns, Collection<String> lowerCns) {
        if (r == null || r.group() == null) return false;
        if (r.isDn()) return normalizedDns.contains(normalizeDn(r.group()));
        String want = r.group().trim();
        if (want.regionMatches(true, 0, "CN=", 0, 3)) want = want.substring(3);
        return lowerCns.contains(want.trim().toLowerCase(Locale.ROOT));
    }

    /** Karşılaştırma için DN: küçük harf, kaçışsız virgüllerin çevresindeki boşluklar silinir. */
    public static String normalizeDn(String dn) {
        if (dn == null) return "";
        String s = dn.trim();
        StringBuilder out = new StringBuilder(s.length());
        int n = Math.min(s.length(), 4096);   // girdi sınırı — DN'ler kısa; uzun girdi doğrusal taramayla kesilir
        for (int i = 0; i < n; i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < n) { out.append(c).append(s.charAt(++i)); continue; }
            if (c == ',') {
                while (out.length() > 0 && out.charAt(out.length() - 1) == ' ') out.setLength(out.length() - 1);
                out.append(',');
                while (i + 1 < n && s.charAt(i + 1) == ' ') i++;
                continue;
            }
            out.append(c);
        }
        return out.toString().toLowerCase(Locale.ROOT);
    }

    /** DN'nin ilk RDN'i CN ise değeri (kaçışlar çözülmüş), değilse null. */
    public static String firstCn(String dn) {
        if (dn == null) return null;
        String s = dn.trim();
        if (!s.regionMatches(true, 0, "CN=", 0, 3)) return null;
        StringBuilder v = new StringBuilder();
        int n = Math.min(s.length(), 4096);
        for (int i = 3; i < n; i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < n) { v.append(s.charAt(++i)); continue; }
            if (c == ',') break;
            v.append(c);
        }
        String out = v.toString().trim();
        return out.isEmpty() ? null : out;
    }

    /** Rolün "ayrıcalık" sırası — önizleme özetinde yükseltme / düşürme ayrımı için (ADMIN > AUDIT > TEAM_ADMIN > USER). */
    public static int rank(String role) {
        if (role == null) return 0;
        return switch (role) {
            case "ADMIN" -> 4;
            case "AUDIT" -> 3;
            case "TEAM_ADMIN" -> 2;
            case "USER" -> 1;
            default -> 0;
        };
    }

    /** Sabit varsayılan rolün düşüremeyeceği roller (bugünkü kuralla aynı koruma). */
    public static final Set<String> PROTECTED_FROM_FALLBACK = Set.of("ADMIN", "AUDIT");
}
