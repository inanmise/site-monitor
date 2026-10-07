import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { api, getRecentFailures } from '../api/client.js'
import { LANG_STORAGE_KEY } from '../i18n/dateLocale.js'
import { extractedFormData } from '../components/manualcert/manualCertModel.js'

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

  it('deleteVersion: DELETE /api/manual-certs/{id}/versions/{vid}; 409 CURRENT_VERSION kodla + durumla döner', async () => {
    const f = captureFetch(200, { success: true, data: { deleted_version: 2, versions_count: 2 } })
    const ok = await api.manualCerts.deleteVersion(7, 103)
    const [url, opts] = f.mock.calls[0]
    expect(`${opts.method} ${url}`).toBe('DELETE /api/manual-certs/7/versions/103')
    expect(ok).toMatchObject({ success: true, data: { deleted_version: 2 } })
    captureFetch(409, { success: false, code: 'CURRENT_VERSION', error: 'güncel' })
    expect(await api.manualCerts.deleteVersion(7, 104)).toMatchObject({ success: false, code: 'CURRENT_VERSION', status: 409 })
  })

  it('409 gövdesi kodla ve durumla döner (KEY_EXISTS); 400 alan hataları korunur', async () => {
    captureFetch(409, { success: false, code: 'KEY_EXISTS', error: 'var' })
    const r409 = await api.manualCerts.create(new FormData())
    expect(r409).toMatchObject({ success: false, code: 'KEY_EXISTS', status: 409 })
    captureFetch(400, { success: false, errors: { domain: 'geçersiz' } })
    const r400 = await api.manualCerts.create(new FormData())
    expect(r400).toMatchObject({ status: 400, errors: { domain: 'geçersiz' } })
  })

  it('2026-10-08: yükleme uçlarının gövdesi yalnız `extracted` (+ ek alanlar) — dosya, metin, şifre, özel anahtar YOK', async () => {
    const f = captureFetch()
    const extraction = { format: 'PKCS12', file_name: 'a.pfx', size_bytes: 9, entries: [{ alias: 'srv', key_entry: true, certs: ['QUJD'] }],
      csr_pem: [], private_keys_removed: 1, password_used: true, notes: [] }
    await api.manualCerts.analyze(extractedFormData(extraction))
    await api.manualCerts.create(extractedFormData(extraction, { ref: 'AB', domain: 'x' }))
    await api.manualCerts.createBatch(extractedFormData(extraction, { items: [{ ref: 'AB', domain: 'x' }] }))
    await api.manualCerts.renew(4, extractedFormData(extraction, { ref: 'AB', allow_same: 'true' }))
    expect(f).toHaveBeenCalledTimes(4)
    for (const [, opts] of f.mock.calls) {
      const fd = opts.body
      expect(fd).toBeInstanceOf(FormData)
      for (const k of ['file', 'text', 'password']) expect(fd.has(k), k).toBe(false)
      const raw = await fd.get('extracted').text()
      expect(raw).not.toMatch(/PRIVATE KEY|password/)
      expect(JSON.parse(raw).entries[0].certs).toEqual(['QUJD'])
    }
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
