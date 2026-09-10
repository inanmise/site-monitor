package com.sitemonitor.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sitemonitor.model.AppUser;
import com.sitemonitor.repository.AppUserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Kişi-webhook alıcı çözümü: alarm olayından (takım + seviye) sicil listesi üretir.
 *
 * <p>Zincir: aktif takım ÜYELERİ ({@code AppUser.teamIds ∋ event.teamId}) → unvan-grubu süzgeci
 * (K2 karma model: PO = orgRole kesin eşleşme; Yönetici/Uzman = AD title desen-eşleme) → şiddet
 * kuralı (Yönetici grubu yalnız HIGH/CRITICAL — mevcut müdür-eskalasyon sözleşmesinin kanal içi
 * eşleniği) → E1 opt-out. E-posta/bildirim-grupları bu çözüme KARIŞMAZ: kanal bağımsızlığı
 * yalnız gönderimde değil ALICI SEÇİMİNDE de geçerli.
 *
 * <p>Grup yapılandırması {@code site.monitor.userpush.role-groups} JSON'undan CANLI okunur —
 * kurum unvanları değişince ayar yetişir, dağıtım gerekmez. Tüm gruplar varsayılan KAPALI:
 * hiçbir grup açılmadıysa kimseye gitmez (global anahtar gibi bilinçli sessiz başlangıç).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserPushRecipientResolver {

    /** Varsayılan grup seti — ayar boşken de tanımlı olsun (hepsi KAPALI). */
    static final String DEFAULT_GROUPS_JSON = """
            {"yonetici":{"enabled":false,"source":"title","patterns":["*Yönetici*","*Müdür*"],"minLevel":"HIGH"},
             "uzman":{"enabled":false,"source":"title","patterns":["*Uzman*"],"minLevel":"WARNING"},
             "po":{"enabled":false,"source":"orgRole","patterns":["PO"],"minLevel":"WARNING"}}""";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final AppUserRepository userRepo;
    private final AppSettingsService appSettings;

    /** Çözüm sonucu: gönderilecekler + nedenleriyle atlananlar (görünmez sessizlik yok). */
    public record Recipient(String username, String displayName, String skipReason) {}

    /**
     * ÇÖZÜM (RESOLVE) alıcıları: açılışta gerçekten push ALAN kullanıcılar. Seviye eşiği, grup
     * kuralı, sessiz saat ve takım çözümlemesi burada UYGULANMAZ — karar açılışta verildi; "düştü"
     * mesajını alan herkes "düzeldi"yi de almalı (kullanıcı kararı 2026-09-10). Yalnız iki güncel
     * gerçek sorgulanır: hesap hâlâ aktif mi ve kişi o aradan opt-out yapmış mı.
     */
    public List<Recipient> resolvePrior(List<String> usernames) {
        Map<String, Recipient> out = new LinkedHashMap<>();
        for (String raw : usernames == null ? List.<String>of() : usernames) {
            String username = raw == null ? "" : raw.trim();
            if (username.isEmpty() || "-".equals(username) || out.containsKey(username)) continue;
            AppUser u = userRepo.findByUsername(username).orElse(null);
            if (u == null || !Boolean.TRUE.equals(u.getActive())) continue;   // ayrılmış/pasif hesaba çözüm gitmez
            String display = u.getDisplayName() != null ? u.getDisplayName() : username;
            out.put(username, Boolean.TRUE.equals(u.getPushOptOut())
                    ? new Recipient(username, display, "SKIPPED_USER_OPT_OUT")
                    : new Recipient(username, display, null));
        }
        return new ArrayList<>(out.values());
    }

    public List<Recipient> resolve(Long teamId, String alertLevel) {
        if (teamId == null) return List.of();
        Map<String, GroupRule> groups = groupRules();
        if (groups.values().stream().noneMatch(g -> g.enabled)) return List.of();

        int level = levelValue(alertLevel);
        // Tekilleştirme: çoklu takım üyeliğinde aynı kişi bir kez (LinkedHashMap sıra korur).
        Map<String, Recipient> out = new LinkedHashMap<>();
        for (AppUser u : userRepo.findByMembershipTeamId(teamId)) {
            if (!Boolean.TRUE.equals(u.getActive())) continue;
            GroupRule match = matchGroup(groups, u);
            if (match == null || !match.enabled) continue;                       // grubu yok/kapalı → aday değil
            if (level < levelValue(match.minLevel)) continue;                    // şiddet kuralı (Yönetici=HIGH+)
            String username = u.getUsername() == null ? "" : u.getUsername().trim();
            String display = u.getDisplayName() != null ? u.getDisplayName() : username;
            if (username.isEmpty()) {
                out.putIfAbsent("(bos)#" + u.getId(), new Recipient("-", display, "SKIPPED_NO_ID"));
            } else if (Boolean.TRUE.equals(u.getPushOptOut())) {
                out.putIfAbsent(username, new Recipient(username, display, "SKIPPED_USER_OPT_OUT"));
            } else {
                out.put(username, new Recipient(username, display, null));       // opt-out satırını ezebilir mi?
            }
        }
        return new ArrayList<>(out.values());
    }

    /** Kullanıcının eşleştiği İLK açık grup; açık grup eşleşmezse kapalı eşleşme; hiç yoksa null. */
    private GroupRule matchGroup(Map<String, GroupRule> groups, AppUser u) {
        GroupRule fallback = null;
        for (GroupRule g : groups.values()) {
            boolean hit = "orgRole".equalsIgnoreCase(g.source)
                    ? matchesAny(u.getOrgRole(), g.patterns, true)
                    : matchesAny(u.getTitle(), g.patterns, false);
            if (!hit) continue;
            if (g.enabled) return g;
            if (fallback == null) fallback = g;
        }
        return fallback;
    }

    /** Basit joker eşleme: {@code *X*} içeren desenler; Türkçe harfe duyarlı lower ile. */
    static boolean matchesAny(String value, List<String> patterns, boolean exact) {
        if (value == null || value.isBlank() || patterns == null) return false;
        String v = value.toLowerCase(new Locale("tr", "TR")).trim();
        for (String p : patterns) {
            if (p == null || p.isBlank()) continue;
            String pat = p.toLowerCase(new Locale("tr", "TR")).trim();
            if (exact) { if (v.equals(pat)) return true; continue; }
            String core = pat.replace("*", "");
            boolean starts = pat.startsWith("*"), ends = pat.endsWith("*");
            if (starts && ends) { if (v.contains(core)) return true; }
            else if (ends)      { if (v.startsWith(core)) return true; }
            else if (starts)    { if (v.endsWith(core)) return true; }
            else                { if (v.equals(core)) return true; }
        }
        return false;
    }

    record GroupRule(String key, boolean enabled, String source, List<String> patterns, String minLevel) {}

    Map<String, GroupRule> groupRules() {
        String json = appSettings.getString("site.monitor.userpush.role-groups", DEFAULT_GROUPS_JSON);
        Map<String, GroupRule> out = new LinkedHashMap<>();
        try {
            JsonNode root = MAPPER.readTree(json == null || json.isBlank() ? DEFAULT_GROUPS_JSON : json);
            root.properties().forEach(e -> {
                JsonNode n = e.getValue();
                List<String> pats = new ArrayList<>();
                if (n.path("patterns").isArray()) n.path("patterns").forEach(x -> pats.add(x.asText()));
                out.put(e.getKey(), new GroupRule(e.getKey(),
                        n.path("enabled").asBoolean(false),
                        n.path("source").asText("title"),
                        pats,
                        n.path("minLevel").asText("WARNING")));
            });
        } catch (Exception ex) {
            log.warn("userpush role-groups ayrıştırılamadı — tüm gruplar kapalı sayılıyor: {}", ex.toString());
        }
        return out;
    }

    static int levelValue(String level) {
        return switch (level == null ? "" : level) {
            case "CRITICAL" -> 3;
            case "HIGH" -> 2;
            default -> 1;
        };
    }
}
