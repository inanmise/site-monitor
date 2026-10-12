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
 * Yalnız DOĞRUYKEN gelen paylaşım bayrakları (inceleme 5): satırda yoksa "false" demektir. Tetik yanıtında korunur;
 * liste satırında YOKSA kopyadan da düşer (paylaşım kalktı / kişi takıma katıldı — form gereksiz kilitli kalmasın).
 */
const SHARE_FLAGS = ['shared_access', 'routing_locked']

/** Liste satırının sunucu kapı bayrakları — tetik ("Şimdi kontrol et") yanıtı bunları taşımaz. */
const ROW_FLAGS = ['can_check', 'can_edit', 'can_delete', 'can_diagnose', ...SHARE_FLAGS]

/**
 * "Şimdi kontrol et" yanıtını açık pencerenin kopyasına koyarken satır bayraklarını korur (2026-10-12, takım erişim
 * paylaşımı): tetik yanıtı `can_*` alanlarını içermez; kopya onları kaybedince düğmeler rol kuralına düşüyor, paylaşımla
 * çalıştıran kişinin "Kontrol" düğmesi kayboluyor ya da sunucunun reddedeceği "Sil" beliriyordu. Yanıtta olan bayrak
 * kazanır; yoksa kopyadaki kalır. Diğer alanlar yanıttan gelir (eski davranış).
 *
 * @param {object|null} prev  açık pencerenin kopyası
 * @param {object|null} next  tetik yanıtının satırı (`res.data`)
 * @returns {object|null}
 */
export function withRowFlags(prev, next) {
  if (!isRow(next) || !isRow(prev) || prev.id !== next.id) return next
  let out = next
  for (const k of ROW_FLAGS) {
    if (next[k] === undefined && prev[k] !== undefined) {
      if (out === next) out = { ...next }
      out[k] = prev[k]
    }
  }
  return out
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
  if (!row) return m
  const out = { ...m, ...row }
  for (const k of SHARE_FLAGS) if (!(k in row)) delete out[k]   // liste satırı bu bayraklarda tek doğru kaynak
  return out
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
  if (fresh) setDetail((prev) => freshestRow(prev, [fresh]))   // liste satırı: paylaşım bayrakları da tazelenir
  return fresh
}
