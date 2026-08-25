package com.sitemonitor.service;

import com.sitemonitor.model.CertificateInventory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Sorumlu Ekipler etiket eşlemesi — sıra ve "yalnız dolu" kuralı.
 *
 * <p>Sıra sözleşmedir: kullanıcı aynı dizilişi üç yerde görür (envanter formu, detay modalı,
 * uyarı e-postası). Bir yerde sıra değişirse diğerleriyle uyuşmaz.
 */
class CertificateInventoryContactsTest {

    private static CertificateInventory inv(String svc, String dev, String iis, String waf) {
        CertificateInventory i = new CertificateInventory();
        i.setSvcMgmtContact(svc);
        i.setAppDevContact(dev);
        i.setIisAdminContact(iis);
        i.setWafAdminContact(waf);
        return i;
    }

    @Test
    @DisplayName("Dolu alanlar ALL sırasında döner")
    void filled_keepsDeclaredOrder() {
        Map<String, String> out = CertificateInventoryContacts.filled(
                inv("Ad Soyad - ad.soyad@example.com", "ekip@example.com",
                    "iis@example.com", "waf@example.com"));

        assertThat(out).containsExactly(
                Map.entry("Servis Yönetimi",     "Ad Soyad - ad.soyad@example.com"),
                Map.entry("Uygulama Geliştirme", "ekip@example.com"),
                Map.entry("IISAdmin Ekibi",      "iis@example.com"),
                Map.entry("WAFAdmin Ekibi",      "waf@example.com"));
    }

    @Test
    @DisplayName("Boş ve yalnız-boşluk alanlar atlanır, sıra bozulmaz")
    void filled_skipsBlanks() {
        Map<String, String> out = CertificateInventoryContacts.filled(
                inv(null, "   ", "iis@example.com", ""));

        assertThat(out).containsExactly(Map.entry("IISAdmin Ekibi", "iis@example.com"));
    }

    @Test
    @DisplayName("Değerler kırpılır")
    void filled_trims() {
        assertThat(CertificateInventoryContacts.filled(inv("  ekip@example.com  ", null, null, null)))
                .containsExactly(Map.entry("Servis Yönetimi", "ekip@example.com"));
    }

    @Test
    @DisplayName("Hiçbiri dolu değilse BOŞ harita — çağıran kartı hiç render etmez")
    void filled_allEmpty_returnsEmpty() {
        assertThat(CertificateInventoryContacts.filled(inv(null, null, null, null))).isEmpty();
        assertThat(CertificateInventoryContacts.filled(null)).isEmpty();
    }

    @Test
    @DisplayName("WAFAdmin satırı wafEnabled bayrağına BAĞLI DEĞİL — bayrak kapalıyken de gösterilir")
    void filled_notGatedByWafFlag() {
        // Bayrak yanlış işaretlenmiş olabilir; doldurulmuş bir iletişim bilgisini bayrak yüzünden
        // gizlemek, alarmı alan kişiden gerçek bilgiyi saklamak olurdu.
        CertificateInventory i = inv(null, null, null, "waf@example.com");
        i.setWafEnabled(false);

        assertThat(CertificateInventoryContacts.filled(i))
                .containsExactly(Map.entry("WAFAdmin Ekibi", "waf@example.com"));
    }
}
