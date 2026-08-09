package com.sitemonitor.service;

import com.sitemonitor.model.CertificateInventory;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Sertifika envanterinin "Operasyonel Bilgiler" bayrakları — etiket eşlemesinin TEK backend kaynağı.
 *
 * <p>Bu 13 bayrak sertifika yenilemesinde kimin ne yapacağını belirler (Netscaler'da mı duruyor,
 * WAF'ta var mı, sunucuda mı değiştirilecek...). Uyarı e-postasında yalnız <b>Evet</b> olanlar
 * listelenir: "Hayır"ları da basmak 13 satırlık gürültü üretir ve okunurluğu düşürür.
 *
 * <p>Sıra, ekrandaki envanter detay modalıyla birebir aynıdır
 * ({@code frontend/src/components/inventory/InventoryDetails.jsx}) — kullanıcı iki yerde aynı
 * dizilişi görsün diye. Etiketler Türkçedir; e-posta şablonlarının tamamı Türkçe üretilir.
 *
 * <p>{@code CertificateInventoryOpsTest} bekçisi, entity'de tanımlı her operasyonel {@code Boolean}
 * alanın bu listede yer aldığını reflection ile doğrular. Gerekçe somut: aynı listenin frontend
 * kopyalarından biri ({@code utils/exportInventory.js}) {@code use_proxy}'yi kaçırmış durumda —
 * elle senkron tutulan liste bu projede kanıtlanmış bir hata sınıfı.
 */
public final class CertificateInventoryOps {

    private CertificateInventoryOps() { }

    /** Tek bayrak: e-postada görünecek Türkçe etiket + entity'den okuyan getter. */
    public record Flag(String label, Function<CertificateInventory, Boolean> getter) { }

    public static final List<Flag> ALL = List.of(
            new Flag("Dış Firma",                  CertificateInventory::getExternalVendor),
            new Flag("Aksiyon Alma",               CertificateInventory::getActionRequired),
            new Flag("OpenShift",                  CertificateInventory::getOpenshift),
            new Flag("SSL Pinning",                CertificateInventory::getSslPinning),
            new Flag("Internal",                   CertificateInventory::getInternalCert),
            new Flag("JKS-Keystore",               CertificateInventory::getJksKeystore),
            new Flag("Sunucuda Değiştirilecek",    CertificateInventory::getServerUpdate),
            new Flag("Netscaler",                  CertificateInventory::getNetscaler),
            new Flag("WAF'ta Var",                 CertificateInventory::getWafEnabled),
            new Flag("Kullanım Durumu",            CertificateInventory::getInUse),
            new Flag("EV Sertifikası",             CertificateInventory::getEvCertificate),
            new Flag("SY'ye Aktarıldı",            CertificateInventory::getTransferredToSy),
            new Flag("Proxy Üzerinden Kontrol Et", CertificateInventory::getUseProxy)
    );

    /** Yalnız TRUE olan bayrakların etiketleri, {@link #ALL} sırasında. Kayıt yoksa boş liste. */
    public static List<String> enabledLabels(CertificateInventory inv) {
        if (inv == null) return List.of();
        List<String> out = new ArrayList<>();
        for (Flag f : ALL) {
            if (Boolean.TRUE.equals(f.getter().apply(inv))) out.add(f.label());
        }
        return out;
    }
}
