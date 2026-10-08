package com.sitemonitor.util;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Kullanıcıya dönen hata metinlerinin bekçisi (2026-10-08, "hata mesajları çok açıklayıcı olsun").
 *
 * <p>İki iş yapar:
 * <ol>
 *   <li>{@link #isTechnical}: metin kullanıcıya gösterilmemesi gereken TEKNİK içerik mi (Java sınıf/paket adı, yığın
 *       çerçevesi, JDBC/Hibernate iletisi, {@code For input string}, {@code No value present} …)? Öyleyse çağıran
 *       açıklayıcı bir metne düşer ve ham metni yalnız LOG'a yazar.</li>
 *   <li>{@link #localizeKnown}: kod tabanında sık fırlatılan, tek dilli ve kısa güvenlik/bulunamadı iletilerini
 *       ("Admin access required", "User not found: 42", "Bu takımın izlemesini düzenleyemezsiniz" …) istek dilinde
 *       (Msg.t) NE oldu · NEDEN · NE yapmalı biçiminde yeniden yazar. Bilinmeyen ileti {@code null} döner → çağıran
 *       metni olduğu gibi kullanır (servislerin kendi doğrulama iletileri korunur).</li>
 * </ol>
 *
 * <p>BİLİNÇLİ OLARAK DOKUNULMAYANLAR: ön yüzün metinle dallandığı iletiler ("Invalid admin password", "Current
 * password…", "recently used", "too short/long", "no email" — PasswordChangeModal / AdminAutoResetModal) ve
 * BÜYÜK_HARF_KOD biçimli iletiler ({@link #isCodeLike}; ör. WEEKLY_REPORTS_DISABLED). Denetim kayıtları ve log
 * satırları bu sınıfı KULLANMAZ — onlar ham (Türkçe) metni tutar.
 */
public final class ErrorTexts {

    private ErrorTexts() {}

    private static final Pattern TECHNICAL = Pattern.compile(
            "\\b(?:java|javax|jakarta|sun)\\.[a-z]+\\."                                       // java.lang.…
            + "|\\borg\\.(?:springframework|hibernate|apache|postgresql|h2|slf4j|bouncycastle)\\."
            + "|\\bcom\\.(?:sitemonitor|fasterxml|zaxxer)\\.|\\btools\\.jackson\\."
            + "|\\bat [\\w$.<>]+\\([\\w$]+\\.java:\\d+\\)"                                    // yığın çerçevesi
            + "|(?:^|[\\s:(])[A-Z][A-Za-z0-9]+(?:Exception|Error)\\b"                         // NullPointerException
            + "|(?i:could not execute statement|SQLState|SQL \\[|\\bJDBC\\b|PSQLException|LazyInitialization"
            + "|No EntityManager|\\bhibernate\\b|duplicate key value|violates (?:foreign key|not-null|check|unique)"
            + "|^for input string:|^no enum constant |illegal base64|^name for argument|^no value present$"
            + "|^cannot invoke \"|because \"[\\w.]+\" is null|^index \\d+ out of bounds)");

    private static final Pattern CODE_LIKE = Pattern.compile("[A-Z][A-Z0-9_]{2,}");

    /** Kullanıcıya gösterilmemesi gereken teknik metin mi? {@code null}/boş da "gösterilemez" sayılır. */
    public static boolean isTechnical(String msg) {
        if (msg == null || msg.isBlank()) return true;
        return TECHNICAL.matcher(msg).find();
    }

    /** BÜYÜK_HARF_KOD (ör. VERSION_CONFLICT) — ön yüz bazı ekranlarda bununla dallanır; metin korunur, kod olarak da taşınır. */
    public static boolean isCodeLike(String msg) {
        return msg != null && CODE_LIKE.matcher(msg.trim()).matches();
    }

    /**
     * Kullanıcıya gösterilecek metin: istisnanın kendi iletisi teknik değilse odur, teknikse verilen açıklayıcı yedek.
     * Denetleyicilerin {@code catch (Exception e) → "error", e.getMessage()} kalıbı yerine kullanılır.
     */
    public static String safeMessage(Throwable e, String fallbackTr, String fallbackEn) {
        String m = e == null ? null : e.getMessage();
        return isTechnical(m) ? Msg.t(fallbackTr, fallbackEn) : m;
    }

    // ── Bilinen tek dilli iletiler ────────────────────────────────────────────

    private record Pair(String tr, String en) { }

    private static final String ASK_MANAGER_TR = " Gerekiyorsa takım yöneticinizden ya da sistem yöneticisinden bu izni isteyin.";
    private static final String ASK_MANAGER_EN = " If you need it, ask your team manager or a system administrator to grant it.";

    private static final Pair AUTH = new Pair(
            "Oturumunuz bulunamadı ya da sona erdi. Sayfayı yenileyip yeniden giriş yapın.",
            "Your session was not found or has ended. Reload the page and sign in again.");
    private static final Pair ADMIN_ONLY = new Pair(
            "Bu işlem yalnız sistem yöneticilerine açık. Gerekiyorsa bir sistem yöneticisinden işlemi yapmasını ya da size yetki vermesini isteyin.",
            "Only system administrators can do this. If you need it, ask a system administrator to do it for you or to grant you access.");
    private static final Pair ADMIN_OR_TEAM_ADMIN = new Pair(
            "Bu işlem yalnız sistem yöneticilerine ve takım yöneticilerine açık. Gerekiyorsa takım yöneticinizden yardım isteyin.",
            "Only system administrators and team managers can do this. If you need it, ask your team manager for help.");

    private static final Map<String, Pair> EXACT = new LinkedHashMap<>();
    static {
        EXACT.put("Not authenticated", AUTH);
        EXACT.put("Authentication required", AUTH);
        EXACT.put("Admin access required", ADMIN_ONLY);
        EXACT.put("Global admin required", ADMIN_ONLY);
        EXACT.put("Yalnız yönetici", ADMIN_ONLY);
        EXACT.put("Admin or team-admin required", ADMIN_OR_TEAM_ADMIN);
        EXACT.put("Not allowed for this team", new Pair(
                "Bu işlem bu takım için size açık değil; yalnız kendi takım(lar)ınızın kayıtlarında işlem yapabilirsiniz. Gerekiyorsa o takımın yöneticisine başvurun.",
                "You can’t do this for that team; you can only work on records of your own team(s). If needed, ask that team’s manager."));
        EXACT.put("Bu takımın izlemesini düzenleyemezsiniz", new Pair(
                "Bu takımın izlemesini düzenleyemezsiniz; yalnız kendi takım(lar)ınızın izlemelerini değiştirebilirsiniz. Değişiklik gerekiyorsa o takımın yöneticisine başvurun.",
                "You can’t edit this team’s monitor; you can only change monitors of your own team(s). If a change is needed, ask that team’s manager."));
        EXACT.put("Bu takımın izlemesini çalıştıramazsınız", new Pair(
                "Bu takımın izlemesini çalıştıramazsınız; yalnız kendi takım(lar)ınızın izlemelerini elle kontrol edebilirsiniz. Gerekiyorsa o takımın yöneticisine başvurun.",
                "You can’t run this team’s monitor; you can only check monitors of your own team(s) manually. If needed, ask that team’s manager."));
        EXACT.put("Silme yetkisi yok (yalnız takım yöneticisi/ADMIN)", new Pair(
                "Bu kaydı silme yetkiniz yok; silme yalnız takım yöneticilerine ve sistem yöneticilerine açık. Gerekiyorsa takım yöneticinizden silmesini isteyin.",
                "You don’t have permission to delete this record; only team managers and system administrators can delete. If needed, ask your team manager to delete it."));
        EXACT.put("Bu domain'i görüntüleme yetkiniz yok", new Pair(
                "Bu alan adını görüntüleme yetkiniz yok; başka bir takıma ait. Gerekiyorsa o takımın yöneticisinden erişim isteyin.",
                "You don’t have access to this domain; it belongs to another team. If needed, ask that team’s manager for access."));
        EXACT.put("Domain not found in your team's inventory", new Pair(
                "Bu alan adı takım(lar)ınızın envanterinde bulunamadı; başka bir takıma ait ya da silinmiş olabilir. Envanter listesini yenileyip tekrar deneyin.",
                "This domain isn’t in your team’s inventory; it may belong to another team or have been deleted. Refresh the inventory list and try again."));
        EXACT.put("Cannot view another team's members", new Pair(
                "Başka bir takımın üyelerini görüntüleyemezsiniz; yalnız kendi takım(lar)ınızın üye listesine erişiminiz var.",
                "You can’t view another team’s members; you only have access to the member lists of your own team(s)."));
        EXACT.put("Cannot manage another team's members", new Pair(
                "Başka bir takımın üyelerini yönetemezsiniz; yalnız kendi takım(lar)ınızın üyelerini ekleyip çıkarabilirsiniz. Gerekiyorsa o takımın yöneticisine başvurun.",
                "You can’t manage another team’s members; you can only add or remove members of your own team(s). If needed, ask that team’s manager."));
    }

    /** "Bu işlem için yetkiniz yok: alerts.edit" / "incidents.view yetkisi gerekli" → izin adı. */
    private static final Pattern PERM_PREFIX = Pattern.compile("^Bu işlem için yetkiniz yok: ?(\\S{1,80})$");
    private static final Pattern PERM_SUFFIX = Pattern.compile("^([a-z_]+\\.[a-z_]+) yetkisi gerekli$");

    /** "<Varlık> not found: <kimlik>" ailesi → (TR ad, EN ad). */
    private static final Map<String, Pair> NOT_FOUND_PREFIX = new LinkedHashMap<>();
    static {
        NOT_FOUND_PREFIX.put("User not found: ", new Pair("Kullanıcı", "User"));
        NOT_FOUND_PREFIX.put("Team not found: ", new Pair("Takım", "Team"));
        NOT_FOUND_PREFIX.put("Alert not found: ", new Pair("Alarm", "Alert"));
        NOT_FOUND_PREFIX.put("Note not found: ", new Pair("Not", "Note"));
        NOT_FOUND_PREFIX.put("Not bulunamadı: ", new Pair("Not", "Note"));
        NOT_FOUND_PREFIX.put("Inventory item not found: ", new Pair("Envanter kaydı", "Inventory record"));
        NOT_FOUND_PREFIX.put("Image not found: ", new Pair("Görsel", "Image"));
        NOT_FOUND_PREFIX.put("Contact not found: ", new Pair("Eskalasyon kişisi", "Escalation contact"));
        NOT_FOUND_PREFIX.put("Threshold not found: ", new Pair("Eşik", "Threshold"));
        NOT_FOUND_PREFIX.put("Guide link not found: ", new Pair("Rehber bağlantısı", "Guide link"));
        NOT_FOUND_PREFIX.put("Yorum bulunamadı: ", new Pair("Yorum", "Comment"));
        NOT_FOUND_PREFIX.put("Weekly report not found: ", new Pair("Haftalık rapor", "Weekly report"));
        NOT_FOUND_PREFIX.put("Incident not found: ", new Pair("Olay kaydı", "Incident"));
        NOT_FOUND_PREFIX.put("Incident bulunamadı: ", new Pair("Olay kaydı", "Incident"));
        NOT_FOUND_PREFIX.put("Grup bulunamadı: ", new Pair("Grup", "Group"));
        NOT_FOUND_PREFIX.put("Diagnostic run not found: ", new Pair("Tanılama kaydı", "Diagnostic run"));
        NOT_FOUND_PREFIX.put("Bakım penceresi bulunamadı: ", new Pair("Bakım penceresi", "Maintenance window"));
    }

    private static final Pattern NUMERIC_ID = Pattern.compile("#?\\d{1,19}");

    /**
     * Bilinen kısa iletinin istek dilinde açıklayıcı karşılığı; bilinmiyorsa {@code null} (çağıran metni olduğu gibi
     * kullanır). Kimlik/izin adı gibi değişken kısım korunur (en çok 80 karakter).
     */
    public static String localizeKnown(String raw) {
        if (raw == null) return null;
        String msg = raw.trim();
        Pair exact = EXACT.get(msg);
        if (exact != null) return Msg.t(exact.tr(), exact.en());
        Matcher pm = PERM_PREFIX.matcher(msg);
        if (!pm.matches()) pm = PERM_SUFFIX.matcher(msg);
        if (pm.matches()) {
            String perm = pm.group(1);
            return Msg.t("Bu işlem için yetkiniz yok (" + perm + " izni gerekiyor)." + ASK_MANAGER_TR,
                    "You don’t have permission for this action (it needs " + perm + ")." + ASK_MANAGER_EN);
        }
        for (Map.Entry<String, Pair> e : NOT_FOUND_PREFIX.entrySet()) {
            if (msg.startsWith(e.getKey())) {
                String id = clip(msg.substring(e.getKey().length()).trim());
                if (id.isEmpty()) return null;
                String shown = NUMERIC_ID.matcher(id).matches() ? (id.startsWith("#") ? id : "#" + id) : id;
                Pair n = e.getValue();
                return Msg.t(n.tr() + " bulunamadı (" + shown + "); silinmiş ya da taşınmış olabilir. Listeyi yenileyip tekrar deneyin.",
                        n.en() + " not found (" + shown + "); it may have been deleted or moved. Refresh the list and try again.");
            }
        }
        return null;
    }

    private static String clip(String s) {
        return s.length() > 80 ? s.substring(0, 80) + "…" : s;
    }
}
