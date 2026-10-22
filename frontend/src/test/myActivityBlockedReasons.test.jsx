import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor } from './test-utils.jsx'
import { TR } from '../i18n/tr.js'
import { EN } from '../i18n/en.js'
import { reasonText, sentence, isRateLimitedRow, reasonCode } from '../components/myactivity/activityModel.js'
import DeviceHistoryPanel from '../components/DeviceHistoryPanel.jsx'
import { api } from '../api/client'

vi.mock('../api/client', async () => {
  const actual = await vi.importActual('../api/client')
  return {
    ...actual,
    api: {
      me: { getMyDevices: vi.fn(), getMyDeviceLogins: vi.fn(), revokeRememberedDevice: vi.fn(), logoutOtherDevices: vi.fn(), reportSuspiciousLogin: vi.fn() },
      admin: { getUserDevices: vi.fn(), getUserDeviceLogins: vi.fn() },
    },
  }
})

const tOf = (dict) => (key, ...args) => {
  let s = dict[key] ?? key
  args.forEach((a, i) => { s = s.split(`{${i}}`).join(String(a ?? '')) })
  return s
}
const t = tOf(EN)
const tr = tOf(TR)
const row = (failure_reason, outcome = 'BLOCKED') => ({ event_type: 'LOGIN_FAILED', outcome, failure_reason })

/**
 * UIA-G3 (2026-10-22): "Etkinliklerim" engellenen HER girişi "çok fazla deneme" diye yazıyordu — kullanıcı adı ön eki,
 * LDAP girişi kapalı, kilitli hesap (kod), hatta pasif hesap / bakım satırları. Yalnız GERÇEK hız sınırı satırı (kodsuz
 * "Rate limited: …", AuditService#recordRateLimited) öyle okunur. Doğruluk tablosu: [gerekçe, cümle anahtarı, gerekçe anahtarı].
 */
const TABLE = [
  ['Rate limited: too many login attempts from 10.0.0.1', 'myact.s.blocked', 'myact.reason.rateLimited'],
  [null, 'myact.s.blocked', 'myact.reason.rateLimited'],
  ["USERNAME_PREFIX: kullanıcı adı izinli bir ön ekle başlamıyor — giriş reddedildi", 'myact.s.refused', 'myact.reason.usernamePrefix'],
  ["LDAP_LOGIN_DISABLED: LDAP ile giriş kapalı — AD'ye gidilmedi", 'myact.s.refused', 'myact.reason.ldapDisabled'],
  ['ACCOUNT_LOCKED: hesap kilitli — kimlik doğrulandı, giriş reddedildi (OTP_PUSH)', 'myact.s.refused', 'myact.reason.accountLocked'],
  ['ACCOUNT_INACTIVE: pasif hesap — kimlik doğrulandı, giriş reddedildi (LOCAL)', 'myact.s.refused', 'lastLogin.reasonAccountInactive'],
  ['MAINTENANCE: sistem bakımı — yalnız global yöneticiler giriş yapabilir (LDAP)', 'myact.s.refused', 'lastLogin.reasonMaintenance'],
  ['YENI_KOD: gelecekte eklenen bir engel', 'myact.s.refused', null],
]

describe('Etkinliklerim — engellenen giriş gerekçeleri (UIA-G3)', () => {
  it.each(TABLE)('%s → cümle %s, gerekçe %s', (reason, sentenceKey, reasonKey) => {
    const r = row(reason)
    expect(sentence(r, t).text).toBe(EN[sentenceKey])
    expect(sentence(r, tr).text).toBe(TR[sentenceKey])
    expect(reasonText(r, t)).toBe(reasonKey ? EN[reasonKey] : null)
    // Hız sınırı metni YALNIZ kodsuz satırda
    expect(isRateLimitedRow(r)).toBe(sentenceKey === 'myact.s.blocked')
  })

  it('engellenmemiş başarısız giriş bugünkü gibi (hatalı parola / ham metin)', () => {
    expect(reasonText(row("BAD_PASSWORD: attempt #3/5 for 'demo'", 'FAILURE'), t)).toBe(EN['lastLogin.reasonBadPassword'])
    expect(reasonText(row('beklenmeyen', 'FAILURE'), t)).toBe('beklenmeyen')
    expect(isRateLimitedRow(row('BAD_PASSWORD: x', 'FAILURE'))).toBe(false)
  })

  it('reasonCode yalnız BÜYÜK harfli öneki kod sayar', () => {
    expect(reasonCode('Rate limited: x')).toBe('')
    expect(reasonCode('USERNAME_PREFIX: x')).toBe('USERNAME_PREFIX')
    expect(reasonCode('')).toBe('')
  })
})

const CURRENT = { known: true, ua_summary: 'Windows · Chrome', device: 'desktop', ip: '88.1.2.3', city: 'Istanbul', country: 'TR', login_at: '2026-08-24T09:15:00', last_seen_at: '2026-08-24T18:00:00' }
const devicesPayload = () => ({ success: true, data: { current: CURRENT, remembered: [], retention_days: 365 } })
const loginsPayload = (rows = []) => ({ success: true, data: { rows, total: rows.length, page: 0, total_pages: 1, retention_days: 365 } })

describe('Cihaz geçmişi — başarısız denemeler (UIA-G4)', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    api.me.getMyDevices.mockResolvedValue(devicesPayload())
  })

  it('okuma düşerse "deneme yok" DENMEZ; Tekrar dene yeniden okur', async () => {
    api.me.getMyDeviceLogins.mockImplementation(async (q) => (q?.failed ? { success: false, status: 500 } : loginsPayload()))
    render(<DeviceHistoryPanel />)
    fireEvent.click(await screen.findByRole('button', { name: new RegExp(EN['dev.failedTitle']) }))
    await screen.findByText(EN['dev.failedAttemptsLoadFailed'])
    expect(screen.queryByText(EN['dev.noFailed'])).toBeNull()
    api.me.getMyDeviceLogins.mockImplementation(async (q) => (q?.failed ? loginsPayload([]) : loginsPayload()))
    fireEvent.click(screen.getAllByRole('button', { name: EN['dev.retry'] }).at(-1))
    await screen.findByText(EN['dev.noFailed'])
  })

  it('ağ hatası (reddedilen istek) da hata durumudur — yakalanmamış söz reddi yok', async () => {
    api.me.getMyDeviceLogins.mockImplementation(async (q) => { if (q?.failed) throw new Error('net'); return loginsPayload() })
    render(<DeviceHistoryPanel />)
    fireEvent.click(await screen.findByRole('button', { name: new RegExp(EN['dev.failedTitle']) }))
    await screen.findByText(EN['dev.failedAttemptsLoadFailed'])
  })

  it('gerekçe çevrilir: ham "KOD: Türkçe metin" basılmaz', async () => {
    const failedRow = { id: 5, at: '2026-08-24T09:15:00', ua_summary: 'Windows · Chrome', device: 'desktop', ip: '1.1.1.1', anomaly_flags: [],
      outcome: 'BLOCKED', failure_reason: 'USERNAME_PREFIX: kullanıcı adı izinli bir ön ekle başlamıyor — giriş reddedildi' }
    api.me.getMyDeviceLogins.mockImplementation(async (q) => (q?.failed ? loginsPayload([failedRow]) : loginsPayload()))
    render(<DeviceHistoryPanel />)
    fireEvent.click(await screen.findByRole('button', { name: new RegExp(EN['dev.failedTitle']) }))
    await waitFor(() => expect(document.body.textContent).toContain(EN['myact.reason.usernamePrefix']))
    expect(document.body.textContent).not.toContain('USERNAME_PREFIX:')
  })
})
