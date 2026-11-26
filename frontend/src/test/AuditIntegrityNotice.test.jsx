import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor, fireEvent } from './test-utils.jsx'
import AuditLogViewer from '../components/admin/AuditLogViewer.jsx'
import { INTEGRITY_INCOMPLETE, INTEGRITY_RUNNING, integrityFailure, isIntegrityNotice } from '../components/admin/audit/integrityModel.js'

/**
 * Denetim zinciri doğrulaması tamamlanamadı / sürüyor (H paketi 2026-11-26, yönetim B2). Sunucu 409
 * `AUDIT_VERIFY_INCOMPLETE` (süre sınırı; `checked`, `checked_to_seq`, `unverified`) ya da `AUDIT_VERIFY_RUNNING` döner —
 * ikisi de zincirin BOZUK olduğu anlamına gelmez. Ekran sunucu metnini uyarı tonuyla gösterir: yeşil "Zincir sağlam" ya
 * da kırmızı "Zincir BOZUK" asla. Başka başarısızlık (ağ, 5xx) bugünkü "Doğrulama hatası" rozeti.
 */
const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
vi.mock('../hooks/use-mobile.js', () => ({ useIsMobile: () => false }))
vi.mock('../api/client', () => ({
  formatDate: (s) => s ?? '',
  api: withApiFallback({
    admin: {
      getAuditLogs: vi.fn(),
      getAuditStats: vi.fn(),
      getAuditIntegrity: vi.fn(),
      getAuditEventTypes: vi.fn(),
      auditExportUrl: vi.fn(() => 'http://x/export'),
    },
  }),
}))
import { api } from '../api/client'

describe('integrityModel — doğrulama başarısızlığı', () => {
  it('INCOMPLETE: kod, sunucu metni ve sayılar (üst düzey ya da data altında); RUNNING; bilinmeyen kod → genel hata', () => {
    expect(integrityFailure({ success: false, code: INTEGRITY_INCOMPLETE, error: 'Süre doldu', checked: 1200, checked_to_seq: 1300, unverified: 99 }))
      .toEqual({ error: true, code: INTEGRITY_INCOMPLETE, message: 'Süre doldu', checked: 1200, checked_to_seq: 1300, unverified: 99 })
    expect(integrityFailure({ success: false, error_code: INTEGRITY_INCOMPLETE, data: { checked: 5, checked_to_seq: 9, unverified: 1 } }))
      .toMatchObject({ code: INTEGRITY_INCOMPLETE, message: null, checked: 5, checked_to_seq: 9, unverified: 1 })
    expect(integrityFailure({ success: false, code: INTEGRITY_RUNNING, error: 'Sürüyor' })).toMatchObject({ code: INTEGRITY_RUNNING, message: 'Sürüyor' })
    // Bilinmeyen kod / ağ hatası: kod yok, metin taşınmaz (bugünkü genel rozet)
    expect(integrityFailure({ success: false, code: 'DB_UNAVAILABLE', error: 'x' })).toMatchObject({ code: null, message: null })
    expect(integrityFailure(null)).toMatchObject({ error: true, code: null, checked: null })
    // Sayı olmayan / negatif değer okunmaz
    expect(integrityFailure({ code: INTEGRITY_INCOMPLETE, checked: '12', unverified: -1 })).toMatchObject({ checked: null, unverified: null })
    expect(isIntegrityNotice(integrityFailure({ code: INTEGRITY_RUNNING }))).toBe(true)
    expect(isIntegrityNotice(integrityFailure(null))).toBe(false)
    expect(isIntegrityNotice({ ok: true })).toBe(false)
  })
})

describe('AuditLogViewer — doğrulama tamamlanamadı / sürüyor', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    localStorage.clear()
    window.history.replaceState(null, '', '/')
    api.admin.getAuditStats.mockResolvedValue({ success: true, data: { total_24h: 0, by_day_14d: [] } })
    api.admin.getAuditLogs.mockResolvedValue({ success: true, data: [], total: 0, page: 0 })
    api.admin.getAuditEventTypes.mockResolvedValue({ success: true, data: [] })
  })

  const verify = () => fireEvent.click(screen.getByRole('button', { name: /Bütünlüğü doğrula|Verify integrity/ }))
  const notice = () => document.querySelector('[data-slot="audit-integrity-notice"]')

  it('409 INCOMPLETE → uyarı şeridi sunucu metni + denetlenen sayılar; yeşil / kırmızı yok', async () => {
    api.admin.getAuditIntegrity.mockResolvedValue({ success: false, status: 409, code: INTEGRITY_INCOMPLETE,
      error: 'Zincir doğrulaması 75 sn sınırına takıldı; 12000 kayıt denetlendi.', checked: 12000, checked_to_seq: 12345, unverified: 678 })
    render(<AuditLogViewer />)
    await waitFor(() => expect(api.admin.getAuditLogs).toHaveBeenCalled())
    verify()
    await waitFor(() => expect(notice()).not.toBeNull())
    expect(notice().getAttribute('data-code')).toBe(INTEGRITY_INCOMPLETE)
    expect(notice().textContent).toContain('Zincir doğrulaması 75 sn sınırına takıldı')
    expect(notice().querySelector('[data-slot="audit-integrity-checked"]').textContent).toMatch(/12[.,]000/)
    expect(notice().querySelector('[data-slot="audit-integrity-checked"]').textContent).toMatch(/#12345/)
    expect(notice().querySelector('[data-slot="audit-integrity-checked"]').textContent).toMatch(/678/)
    const badge = document.querySelector('[data-integrity="error"]')
    expect(badge.getAttribute('data-tone')).toBe('warning')
    expect(badge.getAttribute('data-code')).toBe(INTEGRITY_INCOMPLETE)
    expect(screen.queryByRole('alert')).toBeNull()                       // kırmızı "Zincir BOZUK" şeridi yok
    expect(document.body.textContent).not.toMatch(/Zincir sağlam|Chain intact|Zincir BOZUK|Chain BROKEN/)
  })

  it('409 RUNNING → "başka doğrulama sürüyor" şeridi; sunucu metni yoksa kendi metni; sonra başarılı doğrulama şeridi kaldırır', async () => {
    api.admin.getAuditIntegrity.mockResolvedValue({ success: false, status: 409, error_code: INTEGRITY_RUNNING })
    render(<AuditLogViewer />)
    await waitFor(() => expect(api.admin.getAuditLogs).toHaveBeenCalled())
    verify()
    await waitFor(() => expect(notice()).not.toBeNull())
    expect(notice().getAttribute('data-code')).toBe(INTEGRITY_RUNNING)
    expect(notice().textContent).toMatch(/başka bir zincir doğrulaması sürüyor|Another chain verification is running/)
    expect(notice().querySelector('[data-slot="audit-integrity-checked"]')).toBeNull()
    expect(screen.queryByRole('alert')).toBeNull()

    api.admin.getAuditIntegrity.mockResolvedValue({ success: true, data: { ok: true, checked: 42 } })
    verify()
    expect(await screen.findByText(/Zincir sağlam|Chain intact/)).toBeInTheDocument()
    expect(notice()).toBeNull()
    expect(document.querySelector('[data-integrity="error"]')).toBeNull()
  })

  it('ağ hatası / bilinmeyen kod → bugünkü genel "Doğrulama hatası" rozeti, şerit yok', async () => {
    api.admin.getAuditIntegrity.mockRejectedValue(new Error('offline'))
    render(<AuditLogViewer />)
    await waitFor(() => expect(api.admin.getAuditLogs).toHaveBeenCalled())
    verify()
    await waitFor(() => expect(document.querySelector('[data-integrity="error"]')).not.toBeNull())
    expect(document.querySelector('[data-integrity="error"]').textContent).toMatch(/Doğrulama hatası|Verification error/)
    expect(notice()).toBeNull()
  })
})
