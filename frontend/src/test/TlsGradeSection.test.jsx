import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor } from './test-utils.jsx'

const { api } = vi.hoisted(() => ({ api: { getTlsGrade: vi.fn(), rescanTlsProfile: vi.fn() } }))
vi.mock('../api/client', () => ({ api, formatDateSec: (s) => s || '', formatDate: (s) => s || '' }))

import TlsGradeSection from '../components/tlsgrade/TlsGradeSection.jsx'

/**
 * Sertifika penceresi → Sağlık → TLS notu bölümü (2026-10-10): not + "Neden?" + tüm nedenler (parametreli neden/çözüm),
 * protokol satırlarının tonu, zımbalama / zayıf takım, düşüş afişi, yeniden tarama (yalnız can_rescan), notlanamayan durum.
 */
const DATA = {
  domain: 'shop.example.com', port: 443, state: 'graded', state_reason: null, grade: 'B',
  reasons: [
    { code: 'TLS10_ENABLED', cap: 'B' },
    { code: 'NO_PFS', cap: 'B', params: ['TLS_RSA_WITH_AES_128_GCM_SHA256'] },
    { code: 'HSTS_SHORT', cap: 'A', params: [30] },
  ],
  decisive: ['TLS10_ENABLED', 'NO_PFS'],
  notes: [{ code: 'KEY_2030', cap: null, params: ['RSA', 2048] }],
  profile: {
    status: 'OK', probed_at: '2026-10-10T06:00:00', via: 'direct', port: 443,
    protocols: { tls13: 'NO', tls12: 'YES', tls11: 'NO', tls10: 'YES' },
    ocsp_stapling: 'NO', weak_cipher: 'NO', preferred_cipher: 'TLS_RSA_WITH_AES_128_GCM_SHA256', trigger: 'SCHEDULED',
  },
  negotiated: { tls_version: 'TLSv1.2', cipher_suite: 'TLS_RSA_WITH_AES_128_GCM_SHA256' },
  hsts: { status: 'ENABLED', max_age_days: 30, min_days: 180 },
  history: [{ from: 'A', to: 'B', direction: 'DROP', at: '2026-10-09T10:00:00', reasons: ['TLS10_ENABLED'] }],
  drop: { from: 'A', to: 'B', at: '2026-10-09T10:00:00' },
  can_rescan: true,
}

beforeEach(() => {
  vi.clearAllMocks()
  api.getTlsGrade.mockResolvedValue({ success: true, data: DATA })
})

describe('TlsGradeSection', () => {
  it('not, "Neden B?" ve tüm nedenler parametreleriyle', async () => {
    render(<TlsGradeSection domain="shop.example.com" />)
    const sec = await screen.findByRole('region', { name: /TLS configuration grade/ })
    expect(sec).toHaveAttribute('data-grade', 'B')
    expect(document.querySelector('[data-slot="tls-grade-why"]')).toHaveTextContent('Why B? TLS 1.0 is on · No forward secrecy')
    const rows = document.querySelectorAll('[data-slot="tls-grade-reason-row"]')
    expect([...rows].map((r) => r.dataset.code)).toEqual(['TLS10_ENABLED', 'NO_PFS', 'HSTS_SHORT'])
    expect(rows[1]).toHaveTextContent('TLS_RSA_WITH_AES_128_GCM_SHA256')
    expect(rows[1]).toHaveAttribute('data-decisive', 'true')
    expect(rows[2]).toHaveTextContent('max-age is 30 days')
    expect(rows[2]).toHaveTextContent('max A')
    expect(document.querySelector('[data-slot="tls-grade-note"]')).toHaveTextContent('RSA 2048 bits')
  })

  it('protokol satırlarının tonu: 1.0 açık kötü, 1.3 kapalı kötü, 1.1 kapalı iyi', async () => {
    render(<TlsGradeSection domain="shop.example.com" />)
    await screen.findByRole('region', { name: /TLS configuration grade/ })
    const tone = (p) => document.querySelector(`[data-slot="tls-proto"][data-proto="${p}"]`).dataset.tone
    expect(tone('tls10')).toBe('bad')
    expect(tone('tls13')).toBe('bad')
    expect(tone('tls11')).toBe('ok')
    expect(tone('tls12')).toBe('ok')
    expect(document.querySelector('[data-slot="tls-stapling"]')).toHaveAttribute('data-tone', 'bad')
    expect(document.querySelector('[data-slot="tls-weak"]')).toHaveAttribute('data-tone', 'ok')
    expect(screen.getByText('The TLS grade dropped A → B')).toBeInTheDocument()
  })

  it('yeniden tarama: sunucuya gider, yeni veri çizilir; can_rescan yoksa düğme yok', async () => {
    api.rescanTlsProfile.mockResolvedValue({ success: true, data: { ...DATA, grade: 'A', reasons: [{ code: 'NO_TLS13', cap: 'A' }], decisive: ['NO_TLS13'], drop: null } })
    const { unmount } = render(<TlsGradeSection domain="shop.example.com" />)
    fireEvent.click(await screen.findByRole('button', { name: 'Rescan TLS profile' }))
    await waitFor(() => expect(api.rescanTlsProfile).toHaveBeenCalledWith('shop.example.com'))
    await waitFor(() => expect(document.querySelector('[data-slot="tls-grade-section"]')).toHaveAttribute('data-grade', 'A'))
    unmount()
    api.getTlsGrade.mockResolvedValue({ success: true, data: { ...DATA, can_rescan: false } })
    render(<TlsGradeSection domain="shop.example.com" />)
    await screen.findByRole('region', { name: /TLS configuration grade/ })
    expect(screen.queryByRole('button', { name: 'Rescan TLS profile' })).toBeNull()
  })

  it('elle yüklenen sertifika: not yok, gerekçe metni, neden/profil bölümü yok', async () => {
    api.getTlsGrade.mockResolvedValue({ success: true, data: { state: 'not_applicable', state_reason: 'MANUAL', grade: null, reasons: [], notes: [], profile: null, history: [] } })
    render(<TlsGradeSection domain="upload-key" />)
    const sec = await screen.findByRole('region', { name: /TLS configuration grade/ })
    expect(sec).toHaveAttribute('data-state', 'not_applicable')
    expect(sec).toHaveTextContent('An uploaded certificate has no network endpoint')
    expect(document.querySelector('[data-slot="tls-grade-reason-row"]')).toBeNull()
    expect(document.querySelector('[data-slot="tls-proto"]')).toBeNull()
  })

  it('profil henüz yoksa dürüst metin; yükleme hatası afişte', async () => {
    api.getTlsGrade.mockResolvedValueOnce({ success: true, data: { ...DATA, profile: null } })
    const { unmount } = render(<TlsGradeSection domain="shop.example.com" />)
    expect(await screen.findByText(/has not been scanned yet/)).toBeInTheDocument()
    unmount()
    api.getTlsGrade.mockRejectedValueOnce(new Error('Server unreachable — check the VPN and try again'))
    render(<TlsGradeSection domain="shop.example.com" />)
    expect(await screen.findByText('Server unreachable — check the VPN and try again')).toBeInTheDocument()
  })
})
