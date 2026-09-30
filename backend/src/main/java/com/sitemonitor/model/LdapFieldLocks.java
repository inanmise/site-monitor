package com.sitemonitor.model;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * LDAP kaynaklı profil alanlarının ALAN BAŞINA kilidi (ürün kararı 2026-09-29, kod denetimi A1-O1).
 *
 * <p><b>Kural:</b> yönetici bir LDAP kullanıcısının AD'den gelen bir alanını (görünen ad, e-posta, sicil, ad, soyad,
 * ünvan, telefon, departman, seviye, müdürlük, müdür) ELLE değiştirirse o alan kilitlenir; LDAP girişi, "AD'den
 * yeniden eşitle" ve müdür tazeleme kilitli alanı EZMEZ. Kilit kendiliğinden kalkmaz — yalnız global yönetici, alan
 * başına bilinçli "kilidi kaldır" işlemiyle kaldırır; kaldırılınca alan bir sonraki eşitlemede AD değerine döner.
 * Mevcut {@code role_locked} / {@code org_role_locked} / {@code team_locked} bayrakları aynen kalır (sistem rolü,
 * org rolü ve AD grup üyelikleri o üç kilitle yönetilir); burası profil alanlarını kapsar. Fotoğraf elle
 * düzenlenemediği için kilit konusu değildir (her girişte AD'den tazelenir).
 *
 * <p><b>Saklama:</b> {@code app_users.locked_fields} — tek, NULL bırakılabilir metin kolonu (virgülle ayrılmış
 * anahtarlar, ör. {@code email,title}). Neden ayrı boolean kolonlar değil: dolu tabloya her yeni kolon bir
 * {@code ddl-auto}/yama adımı ister (bkz. dolu tabloya NOT NULL kolon tuzağı); yeni bir AD alanı eklendiğinde
 * şema değişmeden yalnız {@link #FIELDS} büyür. Anahtarlar küçük harf, sırası {@link #FIELDS} ile sabit; bilinmeyen
 * anahtar okunurken atılır (ileri/geri uyum). Tel biçimi: {@code locked_field_keys} (JSON dizi) —
 * {@link AppUser#getLockedFieldKeys()}.
 *
 * <p>Kilit örtüktür: yönetici LDAP kullanıcısının AD kaynaklı alanını elle değiştirince o alan kilitlenir; LDAP tanı çıktısı ({@code locked_fields}) kilitli alanları listeler.
 */
public final class LdapFieldLocks {

    public static final String DISPLAY_NAME  = "display_name";
    public static final String EMAIL         = "email";
    public static final String EMPLOYEE_ID   = "employee_id";
    public static final String FIRST_NAME    = "first_name";
    public static final String LAST_NAME     = "last_name";
    public static final String TITLE         = "title";
    public static final String PHONE         = "phone";
    public static final String DEPARTMENT    = "department";
    public static final String COMPANY_LEVEL = "company_level";
    /** Müdürlük kimliği + adı birlikte (AD extensionAttribute5 tek nitelik). */
    public static final String MUDURLUK      = "mudurluk";
    /** Müdür sicili + müdür bağı ({@code manager_sicil} + {@code manager_id}) birlikte. */
    public static final String MANAGER       = "manager";

    /** Kanonik sıra — frontend listesiyle aynı (sync testi). */
    public static final List<String> FIELDS = List.of(
            DISPLAY_NAME, EMAIL, EMPLOYEE_ID, FIRST_NAME, LAST_NAME, TITLE, PHONE, DEPARTMENT, COMPANY_LEVEL,
            MUDURLUK, MANAGER);

    private LdapFieldLocks() {}

    public static boolean isValid(String field) {
        return field != null && FIELDS.contains(field.trim().toLowerCase(Locale.ROOT));
    }

    /** LDAP hesabı mı ({@code auth_source=LDAP}); null/LOCAL → hayır. Kilit yalnız LDAP hesabında anlamlıdır. */
    public static boolean isLdapUser(AppUser u) {
        return u != null && "LDAP".equalsIgnoreCase(u.getAuthSource());
    }

    /** CSV → tekil, kanonik sıralı küme (bilinmeyen anahtar atılır). */
    public static Set<String> parse(String csv) {
        LinkedHashSet<String> raw = new LinkedHashSet<>();
        if (csv != null) for (String p : csv.split(",")) { String k = p.trim().toLowerCase(Locale.ROOT); if (!k.isEmpty()) raw.add(k); }
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String f : FIELDS) if (raw.contains(f)) out.add(f);
        return out;
    }

    /** Küme → CSV (kanonik sıra); boş küme → {@code null} (kolon NULL kalır). */
    public static String format(Set<String> fields) {
        if (fields == null || fields.isEmpty()) return null;
        List<String> ordered = new ArrayList<>();
        for (String f : FIELDS) if (fields.contains(f)) ordered.add(f);
        return ordered.isEmpty() ? null : String.join(",", ordered);
    }

    public static Set<String> of(AppUser u) {
        return u == null ? new LinkedHashSet<>() : parse(u.getLockedFields());
    }

    public static List<String> keys(String csv) {
        return new ArrayList<>(parse(csv));
    }

    public static boolean isLocked(AppUser u, String field) {
        return u != null && field != null && parse(u.getLockedFields()).contains(field);
    }

    /** Alanı kilitler; kilit YENİ konduysa true. Geçersiz alan adı → {@link IllegalArgumentException}. */
    public static boolean lock(AppUser u, String field) {
        String f = requireValid(field);
        Set<String> cur = of(u);
        boolean added = cur.add(f);
        if (added) u.setLockedFields(format(cur));
        return added;
    }

    /** Alanın kilidini kaldırır; kilit VARDIYSA true. */
    public static boolean unlock(AppUser u, String field) {
        String f = requireValid(field);
        Set<String> cur = of(u);
        boolean removed = cur.remove(f);
        if (removed) u.setLockedFields(format(cur));
        return removed;
    }

    private static String requireValid(String field) {
        if (!isValid(field)) throw new IllegalArgumentException("Unknown LDAP field: " + field);
        return field.trim().toLowerCase(Locale.ROOT);
    }
}
