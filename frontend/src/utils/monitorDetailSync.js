/**
 * AÇIK DETAY PENCERESİNİN KOPYASI ↔ GÜNCEL SATIR (2026-10-09 hata düzeltmesi: "açık detaydan ikinci düzenleme ilkini
 * sessizce geri alıyor").
 *
 * <p><b>Kusur:</b> dokuz izleme sayfası detay penceresinde izlemenin bir KOPYASINI tutar (`selected` / DNS'te
 * `detailMonitor`). Başarılı bir güncellemeden sonra yalnız liste yeniden yükleniyordu; açık pencerenin kopyası eski
 * değerlerde kalıyordu. Kullanıcı aynı pencereden ikinci kez "Düzenle" deyince form bu bayat kopyadan kuruluyor ve
 * kaydetme TAM yük gönderdiği için ilk düzenlemenin değerleri sessizce eskisine dönüyordu. Değişiklik geçmişinden
 * "eski hâline dön" (geri alma) ile 60 sn'lik liste yenilemesi (başka kullanıcının değişikliği) aynı bayatlığı üretiyordu.
 *
 * <p><b>Çözüm (üç küçük yardımcı, dokuz sayfada aynı):</b>
 * <ul>
 *   <li>{@link mergeSavedRow} — kaydetme yanıtındaki sunucu satırı açık pencerenin kopyasına işlenir (yalnız AYNI kimlik).</li>
 *   <li>{@link freshestRow} — "Düzenle" formu izlemenin güncel liste satırından kurulur (kopyanın üstüne birleştirilir:
 *       listede olmayan alan kopyadan kalır).</li>
 *   <li>{@link reloadAndSyncDetail} — geri almadan sonra liste yeniden yüklenir ve açık pencerenin kopyası tazelenir.</li>
 * </ul>
 * Hiçbiri yeni bir istek uydurmaz: kaynak her zaman sayfanın zaten aldığı sunucu yanıtıdır.
 */

const isRow = (x) => !!x && typeof x === 'object' && x.id != null

/**
 * Başarılı güncellemenin sunucu satırını açık detayın kopyasına işler. Kimlik kapısı: yanıt gelene kadar pencere
 * kapanmış ya da başka izlemeye geçilmiş olabilir — A'nın satırı B'nin penceresini değiştirmez, kapalı pencereyi açmaz.
 * Yeni kayıt (farklı kimlik) ya da gövdesiz yanıt kopyaya dokunmaz.
 *
 * @param {object|null} prev   açık pencerenin kopyası (yoksa null)
 * @param {object|null} saved  sunucunun döndürdüğü satır (`res.data`)
 * @returns {object|null}
 */
export function mergeSavedRow(prev, saved) {
  if (!isRow(prev) || !isRow(saved)) return prev
  return prev.id === saved.id ? { ...prev, ...saved } : prev
}

/**
 * Düzenleme formunun kaynağı: izlemenin GÜNCEL listedeki satırı. Liste her kayıttan / yenilemeden sonra sunucudan gelir;
 * detay kopyası yalnız kendi eylemleriyle güncellenir. Satır kopyanın ÜSTÜNE birleştirilir: listede bulunmayan bir alan
 * (ör. tetik yanıtının ek alanı) kopyadan kalır. Listede yoksa (silinmiş / süzülmüş) kopya aynen döner.
 *
 * @param {object|string|null} m  düzenlenecek izleme (kart satırı ya da detay kopyası)
 * @param {Array<object>} list    sayfanın güncel izleme listesi
 * @returns {object|string|null}
 */
export function freshestRow(m, list) {
  if (!isRow(m) || !Array.isArray(list)) return m
  const row = list.find((x) => isRow(x) && x.id === m.id)
  return row ? { ...m, ...row } : m
}

/**
 * Listeyi yeniden yükler ve açık detayın kopyasını TAZE satırla günceller (değişiklik geçmişinden geri alma sonrası:
 * geri alma ucu izleme satırını döndürmez, yalnız geri dönen alanların adlarını döndürür).
 *
 * @param {() => Promise<Array<object>|undefined>} load  sayfanın liste yüklemesi; başarıda satırları döndürür
 * @param {number|string} id                            açık pencerenin izleme kimliği
 * @param {(updater: (prev: object|null) => object|null) => void} setDetail  açık pencere kopyasının state ayarlayıcısı
 * @returns {Promise<object|null>} taze satır (bulunamadıysa null)
 */
export async function reloadAndSyncDetail(load, id, setDetail) {
  const rows = await load()
  if (id == null || !Array.isArray(rows)) return null
  const fresh = rows.find((x) => isRow(x) && x.id === id) || null
  if (fresh) setDetail((prev) => mergeSavedRow(prev, fresh))
  return fresh
}
