import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { api, getRecentFailures } from '../api/client.js'
import { LANG_STORAGE_KEY } from '../i18n/dateLocale.js'

/**
 * `api.manualCerts.*` (2026-10-06) — çok parçalı yükleme uçları: yol + yöntem + FormData gövdesi (Content-Type'ı
 * tarayıcı koyar), X-Lang her istekte; 400/409 gövdeleri durumla birlikte döner (alan hatası / kod ayrımı); şifre
 * hiçbir yere (başarısız çağrı halkası dâhil) yazılmaz.
 */
function captureFetch(status = 200, body = { success: true, data: {} }) {
  global.fetch = vi.fn().mockResolvedValue({
    ok: status >= 200 && status < 300, status,
    headers: { get: () => 'application/json' },
    json: async () => body,
  })
  return global.fetch
}

describe('api.manualCerts', () => {
  const originalFetch = global.fetch
  beforeEach(() => { try { localStorage.setItem(LANG_STORAGE_KEY, 'tr') } catch { /* yok */ } })
  afterEach(() => { global.fetch = originalFetch; try { localStorage.removeItem(LANG_STORAGE_KEY) } catch { /* yok */ } })

  it('analyze: POST /api/manual-certs/analyze, gövde FormData, Content-Type YOK, X-Lang var', async () => {
    const f = captureFetch()
    const fd = new FormData()
    fd.append('text', '-----BEGIN CERTIFICATE-----')
    await api.manualCerts.analyze(fd)
    const [url, opts] = f.mock.calls[0]
    expect(url).toBe('/api/manual-certs/analyze')
    expect(opts.method).toBe('POST')
    expect(opts.body).toBe(fd)
    expect(opts.headers['Content-Type']).toBeUndefined()
    expect(opts.headers['X-Lang']).toBe('tr')
    expect(opts.credentials).toBe('include')
  })

  it('create / batch / renew yolları ve yöntemleri', async () => {
    const f = captureFetch()
    const fd = new FormData()
    await api.manualCerts.create(fd)
    await api.manualCerts.createBatch(fd)
    await api.manualCerts.renew(42, fd)
    expect(f.mock.calls.map(([u, o]) => `${o.method} ${u}`)).toEqual([
      'POST /api/manual-certs', 'POST /api/manual-certs/batch', 'POST /api/manual-certs/42/versions',
    ])
    for (const [, o] of f.mock.calls) expect(o.body).toBe(fd)
  })

  it('list / get / evaluate / pemUrl', async () => {
    const f = captureFetch()
    await api.manualCerts.list()
    await api.manualCerts.get(7)
    await api.manualCerts.evaluate(7)
    expect(f.mock.calls.map(([u, o]) => `${o.method || 'GET'} ${u}`)).toEqual([
      'GET /api/manual-certs', 'GET /api/manual-certs/7', 'POST /api/manual-certs/7/evaluate',
    ])
    expect(api.manualCerts.pemUrl(7, 3)).toBe('/api/manual-certs/7/versions/3/pem')
  })

  it('409 gövdesi kodla ve durumla döner (KEY_EXISTS); 400 alan hataları korunur', async () => {
    captureFetch(409, { success: false, code: 'KEY_EXISTS', error: 'var' })
    const r409 = await api.manualCerts.create(new FormData())
    expect(r409).toMatchObject({ success: false, code: 'KEY_EXISTS', status: 409 })
    captureFetch(400, { success: false, errors: { domain: 'geçersiz' } })
    const r400 = await api.manualCerts.create(new FormData())
    expect(r400).toMatchObject({ status: 400, errors: { domain: 'geçersiz' } })
  })

  it('şifre başarısız çağrı halkasına girmez (yalnız yol + durum)', async () => {
    captureFetch(400, { success: false })
    const fd = new FormData()
    fd.append('password', 'cok-gizli-sifre')
    await api.manualCerts.analyze(fd)
    const ring = JSON.stringify(getRecentFailures())
    expect(ring).toContain('/manual-certs/analyze')
    expect(ring).not.toContain('cok-gizli-sifre')
  })
})
