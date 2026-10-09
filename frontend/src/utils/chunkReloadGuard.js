import { BUILD_VERSION } from './appVersion.js'

/**
 * Bayat paket (deploy sonrası ChunkLoadError) için OTOMATİK TAM YENİLEME hakkı (2026-10-09, sonsuz döngü denetimi).
 *
 * <p>Eskiden koruma yalnız süreye bakıyordu (son yenilemeden 15 sn geçtiyse yine yenile): yenilemeden sonra da bayat
 * index / varlık seti gelirse (önbellekleyen ara katman, yarım dağıtım) sayfa her 15 sn'de ya da tembel bir parçaya
 * her dokunuşta yeniden yenileniyordu. Artık SAYAÇ: paket sürümü (gömülü `BUILD_VERSION`) başına oturumda EN ÇOK BİR
 * otomatik yenileme; sonrası mevcut hata ekranı ("Yeniden yükle" düğmesi kullanıcıda). Yeni bir dağıtım (farklı paket
 * sürümü) yeniden bir hak kazanır. sessionStorage okunamıyor / yazılamıyorsa hak YOK — sayaç tutulamadan yenilemek
 * döngü olurdu.
 */
export const CHUNK_RELOAD_KEY = 'eb-chunk-reload'
export const MAX_AUTO_RELOADS = 1

/** Hak varsa sayacı artırır ve true döner; yoksa (hak bitti / depolama yok) false. Asla fırlatmaz. */
export function claimChunkReload(version = BUILD_VERSION) {
  try {
    let prev = null
    try { prev = JSON.parse(sessionStorage.getItem(CHUNK_RELOAD_KEY) || 'null') } catch { prev = null }
    const v = String(version ?? '')
    const count = prev && typeof prev === 'object' && prev.version === v ? Number(prev.count) || 0 : 0
    if (count >= MAX_AUTO_RELOADS) return false
    sessionStorage.setItem(CHUNK_RELOAD_KEY, JSON.stringify({ version: v, count: count + 1 }))
    return true
  } catch {
    return false
  }
}
