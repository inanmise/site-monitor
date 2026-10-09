import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen } from './test-utils.jsx'
import ErrorBoundary from '../components/ErrorBoundary.jsx'
import { CHUNK_RELOAD_KEY, claimChunkReload } from '../utils/chunkReloadGuard.js'

/**
 * Bayat paket yenileme döngüsü sigortası (2026-10-09): eskiden yalnız süre (15 sn) korunuyordu — yenilemeden sonra da
 * bayat varlık seti gelirse sayfa tekrar tekrar yenilenebiliyordu. Artık paket sürümü başına oturumda EN ÇOK BİR
 * otomatik yenileme; sonrası mevcut hata ekranı.
 */

const stored = () => JSON.parse(sessionStorage.getItem(CHUNK_RELOAD_KEY))

describe('claimChunkReload — sürüm başına tek hak', () => {
  beforeEach(() => { sessionStorage.clear() })
  afterEach(() => { vi.restoreAllMocks() })

  it('aynı sürümde ikinci ve sonraki hatalar yenilemez; yeni dağıtım (farklı sürüm) yeniden bir hak kazanır', () => {
    expect(claimChunkReload('20.1.0')).toBe(true)
    expect(claimChunkReload('20.1.0')).toBe(false)
    expect(claimChunkReload('20.1.0')).toBe(false)
    expect(stored()).toEqual({ version: '20.1.0', count: 1 })
    expect(claimChunkReload('20.2.0')).toBe(true)
    expect(claimChunkReload('20.2.0')).toBe(false)
  })

  it('bozuk kayıt taze sayılır; depolama yazılamıyorsa hak YOK (sayaçsız yenileme döngü olurdu)', () => {
    sessionStorage.setItem(CHUNK_RELOAD_KEY, '{bozuk')
    expect(claimChunkReload('v1')).toBe(true)
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => { throw new Error('blocked') })
    expect(claimChunkReload('v2')).toBe(false)
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => { throw new Error('blocked') })
    expect(claimChunkReload('v3')).toBe(false)
  })
})

describe('ErrorBoundary — chunk yükleme hatası', () => {
  let errSpy
  beforeEach(() => {
    sessionStorage.clear()
    errSpy = vi.spyOn(console, 'error').mockImplementation(() => {})
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: true, json: async () => ({}) }))
  })
  afterEach(() => {
    errSpy.mockRestore()
    vi.unstubAllGlobals()
  })

  function ChunkBomb() {
    throw new Error('Failed to fetch dynamically imported module: /assets/IncidentsPage-abc123.js')
  }

  it('ilk hata hakkı kullanır (tam yenileme); aynı sürümde ikinci hata YENİLEMEZ, hata ekranı kalır', () => {
    const first = render(<ErrorBoundary><ChunkBomb /></ErrorBoundary>)
    expect(stored().count).toBe(1)
    first.unmount()
    render(<ErrorBoundary><ChunkBomb /></ErrorBoundary>)
    expect(stored().count).toBe(1)                      // hak tükendi — yeniden yükleme istenmedi
    expect(screen.getByRole('alert')).toBeInTheDocument()
  })
})
