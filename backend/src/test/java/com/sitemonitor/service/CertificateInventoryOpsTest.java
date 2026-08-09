package com.sitemonitor.service;

import com.sitemonitor.model.CertificateInventory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Bekçi: envanterin operasyonel bayrak listesi entity ile senkron kalmalı.
 *
 * <p>Neden gerekli: aynı liste frontend'de üç kez kopyalanmış (detay modalı, düzenleme formu, dışa
 * aktarım) ve kopyalardan biri — {@code utils/exportInventory.js} — {@code use_proxy}'yi kaçırmış
 * durumda. Elle senkron tutulan liste bu projede kanıtlanmış bir hata sınıfı; backend kopyası bu
 * testle kilitlenir. Yeni bir operasyonel bayrak eklendiğinde derleme değil, BU test kırılır ve
 * bayrağın e-postaya da eklenmesi gerektiğini söyler.
 */
class CertificateInventoryOpsTest {

    /** Operasyonel bayrak sayılmayan Boolean alanlar (varsa) — gerekçeli muafiyet listesi. */
    private static final List<String> EXEMPT = List.of("active");

    private static List<String> booleanFieldNames() {
        List<String> out = new ArrayList<>();
        for (Field f : CertificateInventory.class.getDeclaredFields()) {
            if (f.getType() == Boolean.class && !EXEMPT.contains(f.getName())) out.add(f.getName());
        }
        return out;
    }

    @Test
    @DisplayName("Entity'deki her operasyonel Boolean alan etiket listesinde var")
    void everyBooleanFieldIsMapped() {
        CertificateInventory probe = new CertificateInventory();
        List<String> unmapped = new ArrayList<>();

        for (String fieldName : booleanFieldNames()) {
            // Alanı TRUE yap, listenin o alanı okuyup okumadığını çıktıdan anla.
            setBoolean(probe, fieldName, Boolean.TRUE);
            if (CertificateInventoryOps.enabledLabels(probe).isEmpty()) unmapped.add(fieldName);
            setBoolean(probe, fieldName, null);
        }

        assertThat(unmapped)
                .as("CertificateInventory'de tanımlı ama CertificateInventoryOps.ALL'da olmayan bayraklar — "
                    + "e-postada görünmezler; listeye Türkçe etiketiyle ekleyin")
                .isEmpty();
    }

    @Test
    @DisplayName("Etiketler benzersiz ve boş değil")
    void labelsAreUniqueAndNonBlank() {
        List<String> labels = CertificateInventoryOps.ALL.stream().map(CertificateInventoryOps.Flag::label).toList();
        assertThat(labels).doesNotContainNull().allSatisfy(l -> assertThat(l).isNotBlank());
        assertThat(labels).doesNotHaveDuplicates();
        assertThat(labels).hasSize(booleanFieldNames().size());
    }

    @Test
    @DisplayName("Yalnız TRUE olanlar döner; FALSE ve null atlanır, sıra korunur")
    void onlyTrueFlagsAreReturnedInDeclaredOrder() {
        CertificateInventory inv = new CertificateInventory();
        inv.setNetscaler(true);
        inv.setWafEnabled(true);
        inv.setInUse(true);
        inv.setOpenshift(false);          // FALSE → listelenmez
        inv.setSslPinning(null);          // null  → listelenmez

        assertThat(CertificateInventoryOps.enabledLabels(inv))
                .containsExactly("Netscaler", "WAF'ta Var", "Kullanım Durumu");
    }

    @Test
    @DisplayName("Kayıt yoksa boş liste (NPE değil)")
    void nullInventoryYieldsEmptyList() {
        assertThat(CertificateInventoryOps.enabledLabels(null)).isEmpty();
        assertThat(CertificateInventoryOps.enabledLabels(new CertificateInventory())).isEmpty();
    }

    private static void setBoolean(CertificateInventory target, String fieldName, Boolean value) {
        try {
            Field f = CertificateInventory.class.getDeclaredField(fieldName);
            f.setAccessible(true);
            f.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Alan yazılamadı: " + fieldName, e);
        }
    }
}
