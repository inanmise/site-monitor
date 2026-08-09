/**
 * Sertifika envanterinin "Operasyonel Bilgiler" bayrakları — frontend'in TEK kaynağı.
 *
 * Bu 13 bayrak sertifika yenilemesinde kimin ne yapacağını belirler (Netscaler'da mı duruyor,
 * WAF'ta var mı, sunucuda mı değiştirilecek...). Sıra ekranda, formda, dışa aktarımda ve
 * e-postada aynı olmalı.
 *
 * NEDEN TEK DOSYA: liste eskiden ÜÇ yerde kopyalanmıştı (detay modalı, düzenleme formu, dışa
 * aktarım) ve kopyalardan biri — utils/exportInventory.js — `use_proxy`'yi kaçırmıştı; CSV ve
 * PDF çıktısı aylarca 13 yerine 12 bayrak bastı. Elle senkron tutulan liste bu projede
 * kanıtlanmış bir hata sınıfı.
 *
 * Backend eşi: backend/src/main/java/com/sitemonitor/service/CertificateInventoryOps.java
 * (`ALL`). İki liste anahtar ve SIRA olarak birebir aynı olmalı; `inventory-flags-sync`
 * bekçi testi Java dosyasını okuyup bunu her koşuda doğrular. Backend tarafında da entity ile
 * katalog arasındaki bağ reflection testiyle kilitli → zincir uçtan uca kapalı.
 */
export const INVENTORY_FLAGS = [
  { key: 'external_vendor',   labelKey: 'inv.formExternalVendor' },
  { key: 'action_required',   labelKey: 'inv.formActionRequired' },
  { key: 'openshift',         labelKey: 'inv.formOpenshift' },
  { key: 'ssl_pinning',       labelKey: 'inv.formSslPinning' },
  { key: 'internal_cert',     labelKey: 'inv.formInternal' },
  { key: 'jks_keystore',      labelKey: 'inv.formJksKeystore' },
  { key: 'server_update',     labelKey: 'inv.formServerUpdate' },
  { key: 'netscaler',         labelKey: 'inv.formNetscaler' },
  { key: 'waf_enabled',       labelKey: 'inv.formWafEnabled' },
  { key: 'in_use',            labelKey: 'inv.formInUse' },
  { key: 'ev_certificate',    labelKey: 'inv.formEvCert' },
  { key: 'transferred_to_sy', labelKey: 'inv.formTransferredToSy' },
  { key: 'use_proxy',         labelKey: 'inv.formUseProxy' },
]

/** Yeni envanter formunun bayrak varsayılanları: hepsi false. */
export const emptyFlags = () =>
  Object.fromEntries(INVENTORY_FLAGS.map(({ key }) => [key, false]))
