import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { api } from '../api/client.js'
import { LANG_STORAGE_KEY } from '../i18n/dateLocale.js'

/**
 * Grafana panosu JSON indirmesi (2026-10-18, bağımsız inceleme): dosya ham `fetch` ile iner (blob), ama 401 ortak oturum
 * yolundan geçer — bir oturum yoklaması `request()`'in 401 dalını (oturum bitti / pasif hesap / bakım) tetikler; ağ
 * hatasında tarayıcının teknik metni ("Failed to fetch") kullanıcıya gitmez.
 */
const res = (status, { body = null, blob = null, disposition = null } = {}) => ({
  ok: status >= 200 && status < 300,
  status,
  headers: { get: (h) => (h.toLowerCase() === 'content-disposition' ? disposition : null) },
  json: () => (body == null ? Promise.reject(new Error('no json')) : Promise.resolve(body)),
  blob: () => Promise.resolve(blob),
})

let originalFetch
beforeEach(() => {
  originalFetch = global.fetch
  try { localStorage.setItem(LANG_STORAGE_KEY, 'tr') } catch { /* yok */ }
  try { sessionStorage.removeItem('sm.session.active') } catch { /* yok */ }
})
afterEach(() => {
  vi.restoreAllMocks()
  global.fetch = originalFetch
  try { localStorage.removeItem(LANG_STORAGE_KEY) } catch { /* yok */ }
})

describe('api.admin.downloadGrafanaDashboard', () => {
  it('başarı: blob + sunucunun dosya adı', async () => {
    const blob = new Blob(['{}'], { type: 'application/json' })
    global.fetch = vi.fn().mockResolvedValueOnce(res(200, { blob,
      disposition: 'attachment; filename="sitemonitor-grafana-dashboard-20.157.0.json"' }))
    const r = await api.admin.downloadGrafanaDashboard()
    expect(r).toEqual({ success: true, blob, name: 'sitemonitor-grafana-dashboard-20.157.0.json' })
    expect(global.fetch.mock.calls[0][0]).toBe('/api/admin/grafana/dashboard')
  })

  it('401: oturum yoklaması ortak oturum yolunu tetikler; sonuç gövdesiz, teknik metin yok', async () => {
    global.fetch = vi.fn()
      .mockResolvedValueOnce(res(401, { body: { success: false } }))
      .mockResolvedValueOnce(res(401, { body: { success: false } }))
    const r = await api.admin.downloadGrafanaDashboard()
    expect(r).toMatchObject({ success: false, status: 401, error: null })
    expect(global.fetch).toHaveBeenCalledTimes(2)
    expect(global.fetch.mock.calls[1][0]).toBe('/api/session/ping')
  })

  it('ağ hatası: tarayıcı metni ("Failed to fetch") dönmez — sayfa kendi açıklayıcı iletisini kullanır', async () => {
    global.fetch = vi.fn().mockRejectedValueOnce(new TypeError('Failed to fetch'))
    const r = await api.admin.downloadGrafanaDashboard()
    expect(r).toMatchObject({ success: false, status: 0, error: null, code: 'NETWORK_ERROR' })
  })

  it('403: sunucunun açıklayıcı iletisi korunur', async () => {
    global.fetch = vi.fn().mockResolvedValueOnce(res(403, { body: { success: false, error: 'Yalnız global yöneticiler indirebilir.', code: 'FORBIDDEN' } }))
    const r = await api.admin.downloadGrafanaDashboard()
    expect(r).toMatchObject({ success: false, status: 403, error: 'Yalnız global yöneticiler indirebilir.', code: 'FORBIDDEN' })
  })
})
