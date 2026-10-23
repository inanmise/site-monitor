import { BUILD_VERSION } from './appVersion.js'

/**
 * Bayat paket (deploy sonrası ChunkLoadError) için OTOMATİK TAM YENİLEME hakkı (2026-10-09, sonsuz döngü denetimi).
 *
 * <p>Eskiden koruma yalnız süreye bakıyordu (son yenilemeden 15 sn geçtiyse yine yenile): yenilemeden sonra da bayat
 * index / varlık seti gelirse (önbellekleyen ara katman, yarım dağıtım) sayfa her 15 sn'de ya da tembel bir parçaya
 * her dokunuşta yeniden yenileniyordu. Artık SAYAÇ: paket başına oturumda EN ÇOK BİR otomatik yenileme; sonrası mevcut
 * hata ekranı ("Yeniden yükle" düğmesi kullanıcıda). Yeni bir dağıtım (farklı paket) yeniden bir hak kazanır.
 * sessionStorage okunamıyor / yazılamıyorsa hak YOK — sayaç tutulamadan yenilemek döngü olurdu.
 *
 * <p><b>Yuvarlanan dağıtım (2026-10-23, çok pod sürüm 2b · MP-47).</b> Eski ve yeni pod birlikte sunarken bir sayfa
 * yüklemesi iki sürümün dosyalarını karıştırabilir. Üç yol AYNI hakkı paylaşır (tek kayıt, tek kural):
 * <ul>
 *   <li>{@code public/boot-guard.js} — paketin kendisi (giriş betiği / CSS) yüklenemezse, React hiç başlamadan;</li>
 *   <li>ErrorBoundary — tembel bir parça yüklenemezse;</li>
 *   <li>dil yükleyici — açılışta İngilizce sözlük parçası inmezse (i18n/index.jsx; ancak hak bittikten sonra Türkçe).</li>
 * </ul>
 * Paket kimliği giriş betiğinin yoludur ({@code /assets/index-<hash>.js}) — aynı VERSION dosyasıyla yapılan iki derleme
 * (develop) de ayrı sayılır; giriş betiği yoksa (geliştirme, testler) gömülü sürüm. Ayrıca HERHANGİ bir otomatik
 * yenilemeden sonra {@link RELOAD_COOLDOWN_MS} boyunca, paket farklı olsa bile, yeni otomatik yenileme yok: dağıtım
 * sırasında A ve B sürümü arka arkaya gelse de sayfa en çok BİR kez kendiliğinden yenilenir.
 *
 * <p>Kural {@code public/boot-guard.js} içinde derlemesiz düz JS olarak YİNELENİR (paket yüklenemediğinde bu modül de yoktur);
 * {@code bootGuard.test.js} ikisini aynı doğruluk tablosuyla sınar — birini değiştirirsen öbürünü de değiştir.
 */
export const CHUNK_RELOAD_KEY = 'eb-chunk-reload'
export const MAX_AUTO_RELOADS = 1
/** Herhangi bir otomatik yenilemeden sonra, paket fark etmeksizin, ikinci otomatik yenilemeye kadar en az bu süre. */
export const RELOAD_COOLDOWN_MS = 60_000
/** Kayıtta tutulan en çok paket kimliği (en yenileri kalır) — kayıt sınırsız büyümez. */
export const MAX_REMEMBERED_BUILDS = 8

/**
 * Yüklü paketin kimliği: ilk {@code /assets/} altındaki modül betiğinin yolu; yoksa ilk yerel modül betiği ({@code /@}
 * ile başlayan geliştirme sunucusu betikleri hariç); o da yoksa {@code fallback}. boot-guard.js ile AYNI kural.
 */
export function currentBuildId(doc = globalThis.document, fallback = BUILD_VERSION) {
  try {
    const scripts = doc ? doc.querySelectorAll('script[type="module"][src]') : []
    let local = ''
    for (const el of scripts) {
      let path
      try { path = new URL(el.getAttribute('src'), doc.baseURI).pathname } catch { continue }
      if (path.indexOf('/assets/') === 0) return path
      if (!local && path.indexOf('/@') !== 0) local = path
    }
    return local || String(fallback ?? '')
  } catch {
    return String(fallback ?? '')
  }
}

/**
 * Kaydı okur: yeni biçim {v:2, at, builds:{id:sayı}}; eski {version, count} biçimi de okunur; bozuksa boş.
 * Eski biçimin {version, count} alanları ({@code legacy}) yeniden yazarken KORUNUR (2026-10-23 inceleme, D-4): eski
 * sürümün paketi (geri alma, ≤ 20.166.0) yalnız onları okur — silinseler o paket kendi hakkını yeniden kazanırdı.
 */
function readRecord(raw) {
  let prev = null
  try { prev = JSON.parse(raw || 'null') } catch { prev = null }
  if (!prev || typeof prev !== 'object') return { at: NaN, builds: {}, legacy: null }
  const legacy = typeof prev.version === 'string' ? { version: prev.version, count: Number(prev.count) || 0 } : null
  if (prev.builds && typeof prev.builds === 'object') {
    return { at: Number(prev.at), builds: { ...prev.builds }, legacy }
  }
  if (legacy) return { at: NaN, builds: { [legacy.version]: legacy.count }, legacy }
  return { at: NaN, builds: {}, legacy: null }
}

/** Hak varsa sayacı artırır ve true döner; yoksa (hak bitti / bekleme süresinde / depolama yok) false. Asla fırlatmaz. */
export function claimChunkReload(version = currentBuildId(), now = Date.now()) {
  try {
    const rec = readRecord(sessionStorage.getItem(CHUNK_RELOAD_KEY))
    const v = String(version ?? '')
    // Bekleme: az önce (hangi paket olursa olsun) otomatik yenilendi. Saat geri gittiyse (at > now) kayıt yok sayılır.
    if (Number.isFinite(rec.at) && now >= rec.at && now - rec.at < RELOAD_COOLDOWN_MS) return false
    const count = Number(rec.builds[v]) || 0
    if (count >= MAX_AUTO_RELOADS) return false
    delete rec.builds[v]
    rec.builds[v] = count + 1                                // en sona: en yeni
    const ids = Object.keys(rec.builds)
    for (const old of ids.slice(0, Math.max(0, ids.length - MAX_REMEMBERED_BUILDS))) delete rec.builds[old]
    const next = { v: 2, at: now, builds: rec.builds }
    if (rec.legacy) { next.version = rec.legacy.version; next.count = rec.legacy.count }
    sessionStorage.setItem(CHUNK_RELOAD_KEY, JSON.stringify(next))
    return true
  } catch {
    return false
  }
}

/** Sayfayı yeniden yükleyen işlev — testler değiştirir (jsdom'da location.reload değiştirilemez). */
export const reloader = { reload: () => window.location.reload() }

/**
 * Bayat paket şüphesinde: hak varsa sayfayı yeniden yükler ve true döner (çağıran beklemeye geçer); hak yoksa false
 * (çağıran kendi yedek yolunu izler: hata ekranı, Türkçe sözlük…).
 */
export function reloadForStaleBundle(version) {
  if (!claimChunkReload(version)) return false
  try { reloader.reload() } catch { return false }
  return true
}
