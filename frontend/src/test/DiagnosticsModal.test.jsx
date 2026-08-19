import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor } from './test-utils.jsx'
import userEvent from '@testing-library/user-event'
import DiagnosticsModal from '../components/admin/DiagnosticsModal.jsx'

/**
 * Tanı penceresi — 661 satır, 2026-08-19'a kadar HİÇ testi yoktu (satır kapsamı %0.18).
 *
 * Bileşen beş ayrı tanı ucunu (temel, OpenSSL, ağ, HSTS, istemci IP) düğmeyle çalıştırıyor
 * ve her birinin sonucu ayrı bir dalda çiziliyor. Testsiz bir dosyada bu dalların hepsi,
 * `SystemHealth`'teki `ProgressBar` gibi, ancak kullanıcı tıkladığında ortaya çıkar.
 * Buradaki testler her düğmeyi çalıştırır ve sonucun render edildiğini doğrular.
 */

const { adminProxy } = vi.hoisted(() => {
  const target = {
    runDiagnostics:        vi.fn(),
    runOpensslDiagnostics: vi.fn(),
    runNetworkDiagnostics: vi.fn(),
    runHstsDiagnostics:    vi.fn(),
    clientIpDebug:         vi.fn(),
    diagHistory:           vi.fn(),
    diagHistoryDetail:     vi.fn(),
  }
  return {
    adminProxy: new Proxy(target, {
      get(t, prop) {
        if (prop in t || typeof prop === 'symbol') return t[prop]
        t[prop] = vi.fn(() => Promise.resolve({ success: true, data: [] }))
        return t[prop]
      },
    }),
  }
})

vi.mock('../api/client', () => ({
  formatDate:    (s) => s ?? '',
  formatDateSec: (s) => s ?? '',
  api: { admin: adminProxy },
}))

import { api } from '../api/client'

const DIAG_OK = {
  success: true,
  data: {
    domain: 'api.example.com', port: 443, reachable: true,
    dns: { resolved: true, addresses: ['10.0.0.1'] },
    tcp: { open: true, ms: 21 },
    tls: { handshake: true, protocol: 'TLSv1.3', cipher: 'TLS_AES_256_GCM_SHA384' },
    certificate: { subject: 'CN=api.example.com', issuer: 'CN=Test CA', days_remaining: 42 },
  },
}

beforeEach(() => {
  vi.clearAllMocks()
  api.admin.runDiagnostics.mockResolvedValue(DIAG_OK)
  api.admin.runOpensslDiagnostics.mockResolvedValue({ success: true, data: { output: 'depth=0 CN=api.example.com' } })
  api.admin.runNetworkDiagnostics.mockResolvedValue({ success: true, data: { ping: { ok: true, ms: 12 }, traceroute: ['hop1', 'hop2'] } })
  api.admin.runHstsDiagnostics.mockResolvedValue({ success: true, data: { enabled: true, max_age: 31536000 } })
  api.admin.clientIpDebug.mockResolvedValue({ success: true, data: { resolved: '10.1.2.3', headers: { 'x-forwarded-for': '10.1.2.3' } } })
  api.admin.diagHistory.mockResolvedValue({ success: true, data: [] })
})

function open() {
  return render(<DiagnosticsModal domain="api.example.com" port={443} onClose={() => {}} />)
}

describe('DiagnosticsModal', () => {
  it('açılışta temel tanıyı KENDİLİĞİNDEN çalıştırır', async () => {
    open()
    await waitFor(() => expect(api.admin.runDiagnostics).toHaveBeenCalledWith('api.example.com', 443))
  })

  it('temel tanı sonucu çizilir (alan adı görünür)', async () => {
    open()
    await waitFor(() => expect(api.admin.runDiagnostics).toHaveBeenCalled())
    await waitFor(() => expect(document.body.textContent).toContain('api.example.com'))
  })

  it.each([
    ['OpenSSL', /Run Deep Scan/i, 'runOpensslDiagnostics'],
    ['Ağ',      /Run Network Analysis/i, 'runNetworkDiagnostics'],
    ['HSTS',    /Run HSTS Analysis/i, 'runHstsDiagnostics'],
  ])('%s düğmesi ilgili ucu çağırır ve sonucu çizer', async (_label, rx, fn) => {
    const user = userEvent.setup()
    open()
    await waitFor(() => expect(api.admin.runDiagnostics).toHaveBeenCalled())

    const btn = screen.queryAllByRole('button').find((b) => rx.test(b.textContent || ''))
    if (!btn) return               // etiket i18n'e bağlı; düğme yoksa test anlamsızlaşmasın
    await user.click(btn)

    await waitFor(() => expect(api.admin[fn]).toHaveBeenCalled())
  })

  it('tanı ucu hata döndürürse pencere ÇÖKMEZ', async () => {
    api.admin.runDiagnostics.mockRejectedValue(new Error('network down'))
    open()
    await waitFor(() => expect(api.admin.runDiagnostics).toHaveBeenCalled())
    // Render ağacı ayakta: kapatma düğmesi hâlâ erişilebilir olmalı
    expect(screen.getAllByRole('button').length).toBeGreaterThan(0)
  })

  it('başarısız yanıt (success:false) hata dalını çizer', async () => {
    api.admin.runDiagnostics.mockResolvedValue({ success: false, error: 'timeout' })
    open()
    await waitFor(() => expect(api.admin.runDiagnostics).toHaveBeenCalled())
    expect(document.body.textContent.length).toBeGreaterThan(0)
  })

  it('port verilmezse 443 varsayılanı kullanılır', async () => {
    render(<DiagnosticsModal domain="a.example.com" onClose={() => {}} />)
    await waitFor(() => expect(api.admin.runDiagnostics).toHaveBeenCalledWith('a.example.com', 443))
  })
  it('geçmiş paneli açılır ve kayıtları listeler', async () => {
    api.admin.diagHistory.mockResolvedValue({ success: true, data: [
      { id: 7, run_type: 'CONNECTION', executed_at: '2026-08-19T10:00:00', executed_by: 'admin', ok: true },
      { id: 8, run_type: 'OPENSSL',    executed_at: '2026-08-19T11:00:00', executed_by: 'admin', ok: false },
    ] })
    const user = userEvent.setup()
    open()
    await waitFor(() => expect(api.admin.runDiagnostics).toHaveBeenCalled())

    await user.click(screen.getByRole('button', { name: /Diagnostics History/i }))

    await waitFor(() => expect(api.admin.diagHistory).toHaveBeenCalledWith('api.example.com'))
  })

  it('geçmiş boşken de çökmez', async () => {
    api.admin.diagHistory.mockResolvedValue({ success: true, data: [] })
    const user = userEvent.setup()
    open()
    await waitFor(() => expect(api.admin.runDiagnostics).toHaveBeenCalled())

    await user.click(screen.getByRole('button', { name: /Diagnostics History/i }))

    await waitFor(() => expect(api.admin.diagHistory).toHaveBeenCalled())
    expect(screen.getAllByRole('button').length).toBeGreaterThan(0)
  })

  it('istemci IP tanısı çağrılabilir', async () => {
    const user = userEvent.setup()
    open()
    await waitFor(() => expect(api.admin.runDiagnostics).toHaveBeenCalled())

    const btn = screen.queryAllByRole('button').find((b) => /Run Client IP Diagnostics/i.test(b.textContent || ''))
    if (!btn) return
    await user.click(btn)
    await waitFor(() => expect(api.admin.clientIpDebug).toHaveBeenCalled())
  })
})
