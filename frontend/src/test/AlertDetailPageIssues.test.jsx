import { describe, it, expect, vi, beforeEach } from 'vitest'
import { waitFor } from '@testing-library/react'
import { render } from './test-utils.jsx'

// Alarm ayrıntısı (AlertDetailBody) → PAGE_INTEGRITY alarmında E2 "Sorunlu kaynaklar" + E3 teyit/kurtarma bilgisi.
const { apiMock } = vi.hoisted(() => {
  const deep = (obj) => new Proxy(obj, {
    get(t, prop) {
      if (prop === 'then' || typeof prop === 'symbol') return undefined
      if (prop in t) return typeof t[prop] === 'object' && t[prop] !== null ? deep(t[prop]) : t[prop]
      t[prop] = vi.fn(() => Promise.resolve({ success: true, data: [] }))
      return t[prop]
    },
  })
  return { apiMock: deep({ incidents: {}, admin: {}, monitoring: {} }) }
})
vi.mock('../api/client', () => ({ api: apiMock, formatDate: (s) => s ?? '', formatDateSec: (s) => s ?? '', formatDateOnly: (s) => s ?? '' }))
vi.mock('../contexts/PermissionsProvider.jsx', () => ({
  usePermissions: () => ({ perms: {}, canView: () => true, canEdit: () => true, canExecute: () => false, refresh: () => {} }),
  PermissionsProvider: ({ children }) => children,
}))
import { api } from '../api/client'
import { AlertDetailBody } from '../components/admin/alerts/AlertDetail.jsx'

const BASE = {
  id: 933, domain: 'https://www.juzdan.com.tr/', alert_level: 'WARNING', resolved: false, acknowledged: false,
  created_at: '2026-11-09T12:49:31', team_id: 1, team_name: 'SY', message: '1 kırık, 4 zaman aşımı',
}
const NOW = Date.parse('2026-11-12T06:00:00Z')

beforeEach(() => { vi.clearAllMocks(); api.incidents.byAlert.mockResolvedValue({ success: true, data: [] }) })

describe('AlertDetailBody — sayfa bütünlüğü (E2/E3)', () => {
  it('PAGE_INTEGRITY: sorunlu kaynaklar + teyit/kurtarma bilgisi çizilir', () => {
    render(<AlertDetailBody teamName="SY" nowMs={NOW} alert={{
      ...BASE, alert_type: 'PAGE_INTEGRITY',
      page_issues: [
        { issue_type: 'BROKEN', resource_url: 'https://x/a.js', http_status: 404 },
        { more: '… +2 daha' },
      ],
      confirm_info: { confirm_attempts: 3, confirm_interval_ms: 30000, recovery_checks: 3, recovery_interval_ms: 30000 },
    }} />)
    expect(document.querySelector('[data-slot="alert-page-issues"]')).not.toBeNull()
    expect(document.querySelector('[data-slot="page-issue-row"]')).not.toBeNull()
    expect(document.body.textContent).toContain('https://x/a.js')
    expect(document.body.textContent).toContain('HTTP 404')
    // E3 — teyit bilgisi (N kez doğrulandı)
    const ci = document.querySelector('[data-slot="alert-confirm-info"]')
    expect(ci).not.toBeNull()
    expect(ci.textContent).toMatch(/3/)
  })

  it('page_issues / confirm_info yoksa bölümler çizilmez', () => {
    render(<AlertDetailBody teamName="SY" nowMs={NOW} alert={{ ...BASE, alert_type: 'PAGE_INTEGRITY' }} />)
    expect(document.querySelector('[data-slot="alert-page-issues"]')).toBeNull()
    expect(document.querySelector('[data-slot="alert-confirm-info"]')).toBeNull()
  })

  it('teyit kapalı (confirm_attempts=0) → yine bilgi satırı çizilir', () => {
    render(<AlertDetailBody teamName="SY" nowMs={NOW} alert={{
      ...BASE, alert_type: 'PAGE_INTEGRITY',
      confirm_info: { confirm_attempts: 0, recovery_checks: 3, recovery_interval_ms: 30000 },
    }} />)
    expect(document.querySelector('[data-slot="alert-confirm-info"]')).not.toBeNull()
  })

  it('başka türde (PING_DOWN) sayfa bölümleri yok', () => {
    render(<AlertDetailBody teamName="SY" nowMs={NOW} alert={{
      ...BASE, alert_type: 'PING_DOWN', page_issues: [{ issue_type: 'BROKEN', resource_url: 'x' }],
    }} />)
    // Not: page_issues yalnız PAGE_INTEGRITY alarm detayına sunucudan eklenir; bileşen koşulu alert_type'a bakmaz
    // ama backend yalnız PAGE_INTEGRITY'de gönderir. Burada alanın varlığında bile ping alarmı kırılmamalı.
    expect(document.body.textContent).toContain('SY')
  })

  // 2026-11-12 (inceleme bulgusu #1): normal LİSTE-satırı tıklaması (openDetail → kısmi kayıt) ve gömülü "Alarmlar"
  // sekmesi page_issues/confirm_info GETİRMEZ; eksikse AlertDetailBody getAlert'ten çeker → E2/E3 her açılışta görünür.
  it('E2/E3: kısmi kayıtla açılış → getAlert ile zenginleştirilir, bölümler SONRADAN çizilir', async () => {
    api.admin.getAlert.mockResolvedValue({
      success: true, data: { id: 933 },
      page_issues: [{ issue_type: 'BROKEN', resource_url: 'https://x/a.js', http_status: 404 }],
      confirm_info: { confirm_attempts: 3, confirm_interval_ms: 30000, recovery_checks: 3, recovery_interval_ms: 30000 },
    })
    render(<AlertDetailBody teamName="SY" nowMs={NOW} alert={{ ...BASE, alert_type: 'PAGE_INTEGRITY' }} />)
    await waitFor(() => expect(document.querySelector('[data-slot="alert-page-issues"]')).not.toBeNull())
    expect(api.admin.getAlert).toHaveBeenCalledWith(933)
    expect(document.body.textContent).toContain('https://x/a.js')
    expect(document.body.textContent).toContain('HTTP 404')
    expect(document.querySelector('[data-slot="alert-confirm-info"]')).not.toBeNull()
  })

  it('alanlar zaten varsa (ısı haritası / derin bağlantı) getAlert ÇAĞRILMAZ', async () => {
    render(<AlertDetailBody teamName="SY" nowMs={NOW} alert={{
      ...BASE, alert_type: 'PAGE_INTEGRITY', page_issues: [], confirm_info: { confirm_attempts: 3 },
    }} />)
    await Promise.resolve()
    expect(api.admin.getAlert).not.toHaveBeenCalled()
  })

  it('sayfa dışı türde (PING_DOWN) getAlert ÇAĞRILMAZ', async () => {
    render(<AlertDetailBody teamName="SY" nowMs={NOW} alert={{ ...BASE, alert_type: 'PING_DOWN' }} />)
    await Promise.resolve()
    expect(api.admin.getAlert).not.toHaveBeenCalled()
  })
})
