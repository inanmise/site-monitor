/**
 * Kayıtlı sütun tercihi × kataloğa SONRADAN eklenen sütun (2026-09-22 QA bulgusu).
 *
 * Tablo görünümü localStorage'a sütun ANAHTAR LİSTESİ olarak yazılır. Katalogda yeni bir
 * varsayılan sütun açıldığında (ör. envanterdeki "Platform") bu liste donmuş olduğundan sütun
 * eski kullanıcıya HİÇ görünmüyordu: özelliği yalnız hiç görünüm kaydetmemiş kullanıcı görüyordu.
 *
 * Çözüm: görünümle birlikte "o an katalogda olan anahtarlar" (`colsKnown`) da saklanır.
 * Kullanıcının BİLMEDİĞİ yeni bir varsayılan sütun listeye eklenir; bildiği ama kapattığı sütun
 * geri gelmez. `colsKnown` yoksa (bu düzeltmeden önce yazılmış görünüm) modelin dondurulmuş
 * `LEGACY_KNOWN_COLS` listesi devreye girer.
 */

/**
 * @param {string[]} saved    kayıtlı sütun anahtarları
 * @param {{key:string, fixed?:boolean, def?:boolean}[]} catalog  sütun kataloğu (sıra önemli)
 * @param {string[]} known    görünüm yazıldığında katalogda olan anahtarlar
 * @returns {string[]} yeni varsayılan sütunlar katalog sırasındaki yerine eklenmiş liste
 */
export function mergeNewDefaultCols(saved, catalog, known) {
  if (!Array.isArray(saved) || saved.length === 0) return saved
  const knownSet = new Set(Array.isArray(known) ? known : [])
  const have = new Set(saved)
  const out = [...saved]
  catalog.forEach((c, ci) => {
    if (!(c.fixed || c.def) || have.has(c.key) || knownSet.has(c.key)) return
    // Katalog sırasını koru: kendisinden ÖNCE gelen son kayıtlı sütunun arkasına gir.
    const before = new Set(catalog.slice(0, ci).map((x) => x.key))
    let at = out.length
    for (let i = out.length - 1; i >= 0; i--) { if (before.has(out[i])) { at = i + 1; break } }
    out.splice(at, 0, c.key)
    have.add(c.key)
  })
  return out
}
