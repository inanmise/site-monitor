package com.sitemonitor.service.monitorbulk;

import com.sitemonitor.config.GlobalExceptionHandler.FieldValidationException;
import com.sitemonitor.util.Msg;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * İzleme sayfalarında GENİŞLETİLMİŞ toplu düzenleme (2026-11-19, kullanıcı kararı: "kontrol aralığı, alarm seviyesi, bildirim
 * kanalları + grup, etiketler; sunucuda tek istek, önizleme + satır satır sonuç") — SAF kurallar (Spring'siz, tablo testli).
 *
 * <p>İstek TEK türdür (çubuk bir tür sayfasında çizilir): {@code type} ({@link #TYPES}), {@code action} ({@link #ACTIONS}),
 * {@code ids} (≤ {@link #MAX_ITEMS}, tekrarlar atılır) ve eyleme göre parametreler. Geçersiz istek {@link FieldValidationException}
 * (400 {@code VALIDATION_FAILED} + alan) — hiçbir satıra dokunulmadan.
 *
 * <p>Satır kararları ({@link #REASONS}, kalıcı kodlar — ön yüz {@code adm.bulk.reason.<kod>} okur): görünmeyen ya da silinmiş
 * {@code not_found} (adı verilmez), işletilemeyen takım {@code out_of_scope}, paylaşımla (yalnız erişim paylaşımı üzerinden)
 * yazan kişinin yönlendirme alanı (seviye / kanal / grup) {@code routing_locked}, değer zaten aynı {@code unchanged}, CRON
 * zamanlamalı izlemenin aralığı (aralık plandan gelir) {@code schedule_cron}, son etiketi silmek (en az bir etiket zorunlu)
 * {@code last_tag}, başka takımın bildirim grubu {@code group_other_team}, istek süre bütçesi bittiği için denenmeyen satır
 * {@code time_budget}; tekil güncelleme ucunun değeri reddettiği (4xx / doğrulama) başarısız satır {@code rejected} — yalnız
 * beklenmeyen sunucu hatası {@code error}.
 */
public final class MonitorBulkEdit {

    private MonitorBulkEdit() {}

    public static final int MAX_ITEMS = 200;
    public static final int MAX_TAGS = 20;
    public static final int TAG_MAX_CHARS = 50;
    /** Ham liste üst sınırı (tekrar / boş öğe dahil) — çok uzun bir liste ayrıştırılmadan reddedilir. */
    static final int RAW_LIST_MAX = 2_000;

    /** Tür anahtarları (izleme sayfası = `nocType` küçük harf). */
    public static final List<String> TYPES = List.of("http", "keyword", "page", "pagespeed", "ping", "port", "dns", "domain",
            "scripted", "dbquery");
    public static final List<String> ACTIONS = List.of("interval", "alert_level", "channels", "tags");
    public static final List<String> LEVELS = List.of("WARNING", "HIGH", "CRITICAL");
    public static final List<String> GROUP_MODES = List.of("keep", "default", "set");
    public static final List<String> REASONS = List.of("not_found", "out_of_scope", "routing_locked", "unchanged",
            "schedule_cron", "last_tag", "group_other_team", "time_budget", "rejected");

    /** Aralık alt sınırı (sn) — formdaki en küçük seçenek; Sayfa Hızı {@code PageSpeedMonitor.MIN_INTERVAL_SECONDS}. */
    public static int minInterval(String type) {
        return switch (type) {
            case "page", "scripted", "dbquery" -> 60;
            case "pagespeed" -> 300;
            case "domain" -> 3600;
            default -> 30;
        };
    }

    /** Aralık üst sınırı (sn): veritabanı sorgusu 7 gün, diğerleri 1 gün (formdaki en büyük seçenek). */
    public static int maxInterval(String type) {
        return "dbquery".equals(type) ? 7 * 86_400 : 86_400;
    }

    /** Ayrıştırılmış istek. {@code groupMode}: keep = dokunma, default = takımın varsayılanı (null), set = {@code groupId}. */
    public record Request(String type, String action, List<Long> ids, Integer intervalSeconds, String level,
                          Boolean notifyEmail, Boolean notifyWebhook, String groupMode, Long groupId,
                          List<String> tagsAdd, List<String> tagsRemove) {
        public boolean routingAction() {
            return "alert_level".equals(action) || "channels".equals(action);
        }
    }

    public static Request parse(Map<String, Object> body) {
        if (body == null) throw field("action", "İstek gövdesi boş.", "The request body is empty.");
        String type = lower(body.get("type"));
        if (type == null || !TYPES.contains(type))
            throw field("type", "İzleme türü tanınmadı.", "The monitor type is not recognised.");
        String action = lower(body.get("action"));
        if (action == null || !ACTIONS.contains(action))
            throw field("action", "Toplu işlem tanınmadı — aralık, alarm seviyesi, bildirim kanalları ya da etiket seçin.",
                    "The bulk action is not recognised — choose interval, alert level, notification channels or tags.");
        List<Long> ids = ids(body.get("ids"));
        Integer interval = null;
        String level = null;
        Boolean email = null, webhook = null;
        String groupMode = "keep";
        Long groupId = null;
        List<String> add = List.of(), remove = List.of();
        switch (action) {
            case "interval" -> {
                if (!(body.get("interval_seconds") instanceof Number n) || n.doubleValue() != Math.floor(n.doubleValue()))
                    throw field("interval_seconds", "Kontrol aralığını seçin.", "Choose a check interval.");
                long v = n.longValue();
                if (v < minInterval(type) || v > maxInterval(type))
                    throw field("interval_seconds",
                            "Bu izleme türü için aralık " + minInterval(type) + " – " + maxInterval(type) + " saniye olmalı.",
                            "For this monitor type the interval must be " + minInterval(type) + "–" + maxInterval(type) + " seconds.");
                interval = (int) v;
            }
            case "alert_level" -> {
                String l = body.get("alert_level") == null ? null : body.get("alert_level").toString().trim().toUpperCase(Locale.ROOT);
                if (l == null || !LEVELS.contains(l))
                    throw field("alert_level", "Alarm seviyesini seçin: Uyarı, Yüksek ya da Kritik.",
                            "Choose an alert level: Warning, High or Critical.");
                level = l;
            }
            case "channels" -> {
                email = bool(body.get("notify_email"), "notify_email");
                webhook = bool(body.get("notify_webhook"), "notify_webhook");
                String mode = body.get("group_mode") == null ? "keep" : lower(body.get("group_mode"));
                if (mode == null || !GROUP_MODES.contains(mode))
                    throw field("notification_group_id", "Bildirim grubu seçimi tanınmadı.", "The notification group choice is not recognised.");
                groupMode = mode;
                if ("set".equals(mode)) {
                    if (!(body.get("notification_group_id") instanceof Number g) || g.longValue() <= 0)
                        throw field("notification_group_id", "Bildirim grubunu seçin.", "Choose a notification group.");
                    groupId = g.longValue();
                }
                if (email == null && webhook == null && "keep".equals(groupMode))
                    throw field("channels", "Değiştirilecek bir kanal ya da grup seçin.", "Choose a channel or group to change.");
            }
            case "tags" -> {
                add = tagList(body.get("tags_add"), "tags_add");
                remove = tagList(body.get("tags_remove"), "tags_remove");
                if (add.isEmpty() && remove.isEmpty())
                    throw field("tags_add", "Eklenecek ya da çıkarılacak en az bir etiket yazın.", "Enter at least one tag to add or remove.");
                for (String a : add) for (String r : remove) if (a.equalsIgnoreCase(r))
                    throw field("tags_remove", "Aynı etiket hem eklenip hem çıkarılamaz: " + a, "A tag can't be both added and removed: " + a);
            }
            default -> throw new IllegalStateException(action);
        }
        return new Request(type, action, ids, interval, level, email, webhook, groupMode, groupId, add, remove);
    }

    // ── Etiketler ────────────────────────────────────────────────────────────────

    /** Saklı etiket metni → liste (virgülle ayrık, kırpılır, boş atılır, büyük/küçük harf duyarsız tekrarlar bir kez). */
    public static List<String> tagsOf(String raw) {
        List<String> out = new ArrayList<>();
        if (raw == null) return out;
        Set<String> seen = new LinkedHashSet<>();
        for (String p : raw.split(",", -1)) {
            String t = p.trim();
            if (!t.isEmpty() && seen.add(t.toLowerCase(Locale.ROOT))) out.add(t);
        }
        return out;
    }

    /** Ekle + çıkar: mevcut sıra korunur, yeni etiketler sona; eşleme büyük/küçük harf duyarsız. */
    public static List<String> mergeTags(List<String> current, List<String> add, List<String> remove) {
        List<String> out = new ArrayList<>();
        Set<String> drop = new LinkedHashSet<>();
        for (String r : remove) drop.add(r.toLowerCase(Locale.ROOT));
        Set<String> seen = new LinkedHashSet<>();
        for (String t : current) if (!drop.contains(t.toLowerCase(Locale.ROOT)) && seen.add(t.toLowerCase(Locale.ROOT))) out.add(t);
        for (String a : add) if (!drop.contains(a.toLowerCase(Locale.ROOT)) && seen.add(a.toLowerCase(Locale.ROOT))) out.add(a);
        return out;
    }

    /** Aynı etiket kümesi mi (sıra ve büyük/küçük harf önemsiz). */
    public static boolean sameTags(List<String> a, List<String> b) {
        Set<String> x = new LinkedHashSet<>(), y = new LinkedHashSet<>();
        for (String t : a) x.add(t.toLowerCase(Locale.ROOT));
        for (String t : b) y.add(t.toLowerCase(Locale.ROOT));
        return x.equals(y);
    }

    public static String joinTags(List<String> tags) {
        return String.join(",", tags);
    }

    // ── Yardımcılar ──────────────────────────────────────────────────────────────

    private static List<Long> ids(Object raw) {
        if (!(raw instanceof List<?> list) || list.isEmpty())
            throw field("ids", "En az bir izleme seçin.", "Select at least one monitor.");
        if (list.size() > RAW_LIST_MAX)
            throw field("ids", "Tek seferde en çok " + MAX_ITEMS + " izleme düzenlenebilir.",
                    "At most " + MAX_ITEMS + " monitors can be edited at once.");
        LinkedHashSet<Long> out = new LinkedHashSet<>();
        for (Object o : list) {
            if (!(o instanceof Number n) || n.longValue() <= 0)
                throw field("ids", "Seçilen izleme kimliği geçersiz.", "A selected monitor id is invalid.");
            out.add(n.longValue());
            if (out.size() > MAX_ITEMS)
                throw field("ids", "Tek seferde en çok " + MAX_ITEMS + " izleme düzenlenebilir.",
                        "At most " + MAX_ITEMS + " monitors can be edited at once.");
        }
        return List.copyOf(out);
    }

    private static Boolean bool(Object o, String field) {
        if (o == null) return null;
        if (o instanceof Boolean b) return b;
        throw field(field, "Kanal seçimi açık ya da kapalı olmalı.", "A channel choice must be on or off.");
    }

    private static List<String> tagList(Object raw, String field) {
        if (raw == null) return List.of();
        if (!(raw instanceof List<?> list)) throw field(field, "Etiket listesi geçersiz.", "The tag list is invalid.");
        if (list.size() > RAW_LIST_MAX)
            throw field(field, "Tek seferde en çok " + MAX_TAGS + " etiket.", "At most " + MAX_TAGS + " tags at once.");
        List<String> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (Object o : list) {
            String t = o == null ? "" : o.toString().trim();
            if (t.isEmpty()) continue;
            if (t.indexOf(',') >= 0 || t.length() > TAG_MAX_CHARS)
                throw field(field, "Her etiket en çok " + TAG_MAX_CHARS + " karakter olabilir ve virgül içeremez: " + clip(t),
                        "Each tag can be at most " + TAG_MAX_CHARS + " characters and can't contain a comma: " + clip(t));
            if (seen.add(t.toLowerCase(Locale.ROOT))) out.add(t);
            if (out.size() > MAX_TAGS)
                throw field(field, "Tek seferde en çok " + MAX_TAGS + " etiket.", "At most " + MAX_TAGS + " tags at once.");
        }
        return out;
    }

    private static String clip(String s) {
        return s.length() > 60 ? s.substring(0, 60) + "…" : s;
    }

    private static String lower(Object o) {
        if (o == null) return null;
        String s = o.toString().trim().toLowerCase(Locale.ROOT);
        return s.isEmpty() ? null : s;
    }

    private static FieldValidationException field(String field, String tr, String en) {
        return new FieldValidationException(field, Msg.t(tr, en));
    }
}
