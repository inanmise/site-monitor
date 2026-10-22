import { describe, it, expect, vi, afterEach } from 'vitest'

const dl = vi.hoisted(() => ({ ok: true, calls: [] }))
vi.mock('../utils/csvExport.js', () => ({
  downloadBlob: (name, blob) => { dl.calls.push([name, blob]); return dl.ok },
}))
import { downloadAuditExport } from '../components/admin/audit/auditExport.js'

/**
 * Denetim dışa aktarma indirmesi (20.165.1 bugfix, UIA-9): `utils/csvExport.downloadBlob` (bağlantı DOM'da, URL 60 sn) —
 * 0 ms'de geri alınan URL Safari / iPad'de indirmeyi başlatmıyor ama "dışa aktarıldı" yazıyordu. Tarayıcı indirmeyi
 * başlatamazsa sonuç başarı DEĞİL (`status: -1`).
 */
afterEach(() => { vi.unstubAllGlobals(); dl.ok = true; dl.calls = [] })

const response = (over = {}) => ({
  ok: true, status: 200, blob: async () => new Blob(['a,b']), headers: { get: () => 'attachment; filename="audit-2026.csv"' }, ...over,
})

describe('downloadAuditExport', () => {
  it('sunucunun dosya adıyla downloadBlob çağrılır; başarı', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => response()))
    expect(await downloadAuditExport('/api/x', 'audit.csv')).toEqual({ ok: true, status: 200 })
    expect(dl.calls[0][0]).toBe('audit-2026.csv')
  })
  it('tarayıcı indirmeyi başlatamazsa ok:false, status -1 (başarı tostu çıkmaz)', async () => {
    dl.ok = false
    vi.stubGlobal('fetch', vi.fn(async () => response()))
    expect(await downloadAuditExport('/api/x', 'audit.csv')).toEqual({ ok: false, status: -1 })
  })
  it('429 ve ağ hatası eskisi gibi', async () => {
    vi.stubGlobal('fetch', vi.fn(async () => response({ ok: false, status: 429 })))
    expect(await downloadAuditExport('/api/x', 'audit.csv')).toEqual({ ok: false, status: 429 })
    vi.stubGlobal('fetch', vi.fn(async () => { throw new TypeError('Failed to fetch') }))
    expect(await downloadAuditExport('/api/x', 'audit.csv')).toEqual({ ok: false, status: 0 })
  })
})
