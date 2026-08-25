package com.sitemonitor.service;

import com.sitemonitor.model.CertificateInventory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Sertifika envanterinin "Sorumlu Ekipler" alanları — etiket eşlemesinin TEK backend kaynağı.
 *
 * <p>{@link CertificateInventoryOps} ile aynı desen ve aynı gerekçe: aynı listenin iki kopyası
 * elle senkron tutulduğunda kaçınılmaz olarak sapıyor (o sınıfın notundaki {@code use_proxy}
 * vakası). Etiketler Türkçedir — e-posta şablonlarının tamamı Türkçe üretilir.
 *
 * <p>Sıra, envanter formu ve detay modalıyla birebir aynıdır: kullanıcı üç yerde (form, detay,
 * e-posta) aynı dizilişi görsün.
 *
 * <p>Bu alanlar alarm YÖNLENDİRMESİNE girmez; alıcıyı takım/bildirim-grubu zinciri belirler.
 * Buradaki değerler yalnız gösterilir — "bu sertifikayı kim yenileyecek" sorusunun cevabı.
 */
public final class CertificateInventoryContacts {

    private CertificateInventoryContacts() { }

    /** Tek alan: e-postada ve ekranda görünecek Türkçe etiket + entity'den okuyan getter. */
    public record Contact(String label, Function<CertificateInventory, String> getter) { }

    public static final List<Contact> ALL = List.of(
            new Contact("Servis Yönetimi",     CertificateInventory::getSvcMgmtContact),
            new Contact("Uygulama Geliştirme", CertificateInventory::getAppDevContact),
            new Contact("IISAdmin Ekibi",      CertificateInventory::getIisAdminContact),
            new Contact("WAFAdmin Ekibi",      CertificateInventory::getWafAdminContact)
    );

    /**
     * Yalnız DOLU alanların etiket→değer haritası, {@link #ALL} sırasında (ekleme sıralı).
     *
     * <p>Boş alan atlanır ve bir bayrağa BAĞLANMAZ: örneğin WAFAdmin satırını {@code wafEnabled}
     * bayrağına bağlamak cazip görünür ama bayrak yanlış işaretlenmiş olabilir; doldurulmuş bir
     * iletişim bilgisini bayrak yüzünden gizlemek, alarmı alan kişiden gerçek bilgiyi saklamak
     * olurdu. Doluysa göster, boşsa atla.
     *
     * <p>Hiçbiri dolu değilse BOŞ harita döner → çağıran kartı hiç render etmez.
     */
    public static Map<String, String> filled(CertificateInventory inv) {
        if (inv == null) return Map.of();
        Map<String, String> out = new LinkedHashMap<>();
        for (Contact c : ALL) {
            String v = c.getter().apply(inv);
            if (v != null && !v.isBlank()) out.put(c.label(), v.trim());
        }
        return out;
    }
}
