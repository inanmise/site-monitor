/**
 * Sorumlu Ekipler alanları — TEK frontend kaynağı.
 *
 * Sıra backend'deki `CertificateInventoryContacts.ALL` ve envanter detay modalıyla AYNI:
 * kullanıcı üç yerde (form, detay, e-posta) aynı dizilişi görsün.
 *
 * Neden bileşen dosyasında DEĞİL: bu liste hem forma hem detay ekranına lazım. Formda dururken
 * detay ekranı onu import etmek zorunda kalıyordu ve form `@uiw/react-md-editor`'ı statik import
 * ettiği için markdown editörü sertifika modalının paketine giriyordu — hiç kullanılmadan.
 * `utils/inventoryFlags.js` ile aynı desen: sabit liste bağımsız, bağımlılıksız modülde durur.
 */
export const CONTACT_FIELDS = [
  { key: 'svc_mgmt_contact',  labelKey: 'inv.formSvcMgmt' },
  { key: 'app_dev_contact',   labelKey: 'inv.formAppDev' },
  { key: 'iis_admin_contact', labelKey: 'inv.formIisAdmin' },
  { key: 'waf_admin_contact', labelKey: 'inv.formWafAdmin' },
]

/**
 * YUMUŞAK uyarı: değer bir e-posta yazmaya çalışıyor ama biçimi bozuk görünüyor.
 *
 * Kaydı ENGELLEMEZ — alan serbest metindir ("Ad Soyad - ad.soyad@example.com", yalnız ad, yalnız
 * adres hepsi geçerli). Engelleseydik "Ad Soyad (izinde)" gibi meşru bir değeri de reddederdik.
 * Yalnız "@ yazmış ama adres tamamlanmamış" hâlini işaret eder.
 */
export function looksLikeBrokenEmail(value) {
  const v = (value ?? '').trim()
  if (!v.includes('@')) return false
  return !/[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}/.test(v)
}
