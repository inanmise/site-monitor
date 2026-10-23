import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen } from './test-utils.jsx'
import ErrorBoundary from '../components/ErrorBoundary.jsx'
import {
  CHUNK_RELOAD_KEY, RELOAD_COOLDOWN_MS, MAX_REMEMBERED_BUILDS, claimChunkReload, currentBuildId, reloadForStaleBundle, reloader,
} from '../utils/chunkReloadGuard.js'
import { BUILD_VERSION } from '../utils/appVersion.js'

/**
 * Bayat paket yenileme döngüsü sigortası (2026-10-09): eskiden yalnız süre (15 sn) korunuyordu — yenilemeden sonra da
 * bayat varlık seti gelirse sayfa tekrar tekrar yenilenebiliyordu. Artık paket başına oturumda EN ÇOK BİR otomatik
 * yenileme; sonrası mevcut hata ekranı.
 *
 * Çok pod R2b (2026-10-23, MP-47) — kayıt biçimi {v:2, at, builds}; yeniden temellendi: eski test kaydın
 * {version, count} biçimini birebir bekliyordu. Ek kural: herhangi bir otomatik yenilemeden sonra 60 sn boyunca —
 * paket farklı olsa bile — yeni otomatik yenileme yok (yuvarlanan dağıtımda A ve B arka arkaya gelse de bir kez).
 */

const stored = () => JSON.parse(sessionStorage.getItem(CHUNK_RELOAD_KEY))
const T0 = 1_800_000_000_000

describe('claimChunkReload — paket başına tek hak + bekleme süresi', () => {
  beforeEach(() => { sessionStorage.clear() })
  afterEach(() => { vi.restoreAllMocks() })

  it('aynı pakette ikinci ve sonraki hatalar yenilemez; yeni dağıtım (farklı paket) bekleme bitince yeniden bir hak kazanır', () => {
    expect(claimChunkReload('20.1.0', T0)).toBe(true)
    expect(claimChunkReload('20.1.0', T0 + 1)).toBe(false)
    expect(claimChunkReload('20.1.0', T0 + 10 * RELOAD_COOLDOWN_MS)).toBe(false)    // süre geçse de aynı pakette hak yok
    expect(stored()).toEqual({ v: 2, at: T0, builds: { '20.1.0': 1 } })
    expect(claimChunkReload('20.2.0', T0 + RELOAD_COOLDOWN_MS)).toBe(true)
    expect(claimChunkReload('20.2.0', T0 + 3 * RELOAD_COOLDOWN_MS)).toBe(false)
  })

  it('bekleme: bir otomatik yenilemeden sonra 60 sn içinde BAŞKA paket için de yenileme yok (yuvarlanan dağıtım A → B)', () => {
    expect(claimChunkReload('/assets/index-A.js', T0)).toBe(true)
    expect(claimChunkReload('/assets/index-B.js', T0 + RELOAD_COOLDOWN_MS - 1)).toBe(false)
    expect(claimChunkReload('/assets/index-B.js', T0 + RELOAD_COOLDOWN_MS)).toBe(true)
    // A ↔ B gidip gelse de her paket bir kez: geri dönen A'nın hakkı yine bitmiş
    expect(claimChunkReload('/assets/index-A.js', T0 + 5 * RELOAD_COOLDOWN_MS)).toBe(false)
  })

  it('saat geri giderse (kayıt gelecekte) kayıt bekleme sayılmaz, sayaç yine geçerli', () => {
    expect(claimChunkReload('a', T0)).toBe(true)
    expect(claimChunkReload('b', T0 - 5000)).toBe(true)
    expect(claimChunkReload('a', T0 - 1)).toBe(false)
  })

  it('kayıt sınırlı: en çok MAX_REMEMBERED_BUILDS paket kimliği (en yeniler) tutulur', () => {
    for (let i = 0; i < MAX_REMEMBERED_BUILDS + 4; i++) expect(claimChunkReload(`p${i}`, T0 + i * RELOAD_COOLDOWN_MS)).toBe(true)
    expect(Object.keys(stored().builds)).toHaveLength(MAX_REMEMBERED_BUILDS)
    expect(stored().builds).toHaveProperty(`p${MAX_REMEMBERED_BUILDS + 3}`, 1)
  })

  it('eski biçimdeki kayıt ({version, count}) okunur; bozuk kayıt taze sayılır; depolama yazılamıyorsa hak YOK', () => {
    sessionStorage.setItem(CHUNK_RELOAD_KEY, JSON.stringify({ version: 'old', count: 1 }))
    expect(claimChunkReload('old', T0)).toBe(false)
    expect(claimChunkReload('new', T0)).toBe(true)
    sessionStorage.setItem(CHUNK_RELOAD_KEY, '{bozuk')
    expect(claimChunkReload('v1', T0)).toBe(true)
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => { throw new Error('blocked') })
    expect(claimChunkReload('v2', T0 + 10 * RELOAD_COOLDOWN_MS)).toBe(false)
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => { throw new Error('blocked') })
    expect(claimChunkReload('v3', T0 + 20 * RELOAD_COOLDOWN_MS)).toBe(false)
  })

  it('eski sürümün paketiyle (geri alma, ≤ 20.166.0) yan yana: eski {version, count} alanları korunur — iki taraf birbirinin hakkını sıfırlayamaz (D-4)', () => {
    // ≤ 20.166.0 kuralının birebir kopyası: yalnız {version, count} okur ve yazar (yeni alanları siler)
    const oldClaim = (version) => {
      let prev = null
      try { prev = JSON.parse(sessionStorage.getItem(CHUNK_RELOAD_KEY) || 'null') } catch { prev = null }
      const count = prev && typeof prev === 'object' && prev.version === version ? Number(prev.count) || 0 : 0
      if (count >= 1) return false
      sessionStorage.setItem(CHUNK_RELOAD_KEY, JSON.stringify({ version, count: count + 1 }))
      return true
    }
    let reloads = 0
    for (let i = 0; i < 10; i++) {
      // eski ve yeni paket sırayla yüklenemiyor (bekleme süresi dışında: her adım 2 dk sonra)
      if (claimChunkReload('/assets/index-NEW.js', T0 + i * 2 * RELOAD_COOLDOWN_MS)) reloads++
      if (oldClaim('20.166.0')) reloads++
    }
    expect(reloads).toBeLessThanOrEqual(3)                                // düzeltme olmadan 20 (sonsuz gidip gelme)
    expect(stored()).toMatchObject({ version: '20.166.0', count: 1 })
  })
})

describe('currentBuildId — paket kimliği giriş betiğinden (boot-guard.js ile aynı kural)', () => {
  afterEach(() => { document.head.querySelectorAll('script[data-test]').forEach((s) => s.remove()) })

  function addScript(src, type = 'module') {
    const s = document.createElement('script')
    if (type) s.setAttribute('type', type)
    s.setAttribute('src', src)
    s.setAttribute('data-test', '1')
    document.head.appendChild(s)
  }

  it('/assets/ modül betiği varsa onun yolu; yoksa ilk yerel modül (/@vite hariç); hiçbiri yoksa gömülü sürüm', () => {
    expect(currentBuildId()).toBe(BUILD_VERSION)
    addScript('/@vite/client')
    addScript('/src/main.jsx')
    expect(currentBuildId()).toBe('/src/main.jsx')
    addScript('/assets/index-CBxJA6rJ.js')
    expect(currentBuildId()).toBe('/assets/index-CBxJA6rJ.js')
  })

  it('klasik betik (boot-guard.js) paket kimliği sayılmaz', () => {
    addScript('/boot-guard.js', null)
    expect(currentBuildId()).toBe(BUILD_VERSION)
  })
})

describe('reloadForStaleBundle', () => {
  beforeEach(() => { sessionStorage.clear() })
  afterEach(() => { vi.restoreAllMocks() })

  it('hak varsa yeniden yükler ve true; hak yoksa yeniden yüklemez ve false', () => {
    const spy = vi.spyOn(reloader, 'reload').mockImplementation(() => {})
    expect(reloadForStaleBundle('x')).toBe(true)
    expect(spy).toHaveBeenCalledTimes(1)
    expect(reloadForStaleBundle('x')).toBe(false)
    expect(reloadForStaleBundle('y')).toBe(false)                         // bekleme süresi
    expect(spy).toHaveBeenCalledTimes(1)
  })
})

describe('ErrorBoundary — chunk yükleme hatası', () => {
  let errSpy
  let reloadSpy
  beforeEach(() => {
    sessionStorage.clear()
    errSpy = vi.spyOn(console, 'error').mockImplementation(() => {})
    reloadSpy = vi.spyOn(reloader, 'reload').mockImplementation(() => {})
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: true, json: async () => ({}) }))
  })
  afterEach(() => {
    errSpy.mockRestore()
    vi.restoreAllMocks()
    vi.unstubAllGlobals()
  })

  function ChunkBomb() {
    throw new Error('Failed to fetch dynamically imported module: /assets/IncidentsPage-abc123.js')
  }
  function CssBomb() {
    throw new Error('Unable to preload CSS for /assets/IncidentsPage-abc123.css')
  }

  it('ilk hata hakkı kullanır (tam yenileme); aynı pakette ikinci hata YENİLEMEZ, hata ekranı kalır', () => {
    const first = render(<ErrorBoundary><ChunkBomb /></ErrorBoundary>)
    expect(reloadSpy).toHaveBeenCalledTimes(1)
    expect(Object.values(stored().builds)).toEqual([1])
    first.unmount()
    render(<ErrorBoundary><ChunkBomb /></ErrorBoundary>)
    expect(reloadSpy).toHaveBeenCalledTimes(1)                            // hak tükendi — yeniden yükleme istenmedi
    expect(screen.getByRole('alert')).toBeInTheDocument()
  })

  it('tembel parçanın CSS\'i yüklenemedi ("Unable to preload CSS") da bayat paket sayılır: yenile, hata bildirimi GÖNDERME', () => {
    render(<ErrorBoundary><CssBomb /></ErrorBoundary>)
    expect(reloadSpy).toHaveBeenCalledTimes(1)
    expect(fetch).not.toHaveBeenCalled()
  })

  it('açılış koruması bu paket için hakkı kullandıysa ErrorBoundary yenilemez (ortak kayıt — en çok bir kez)', () => {
    expect(claimChunkReload()).toBe(true)                                // boot-guard.js aynı kaydı yazar
    render(<ErrorBoundary><ChunkBomb /></ErrorBoundary>)
    expect(reloadSpy).not.toHaveBeenCalled()
    expect(screen.getByRole('alert')).toBeInTheDocument()
  })
})
