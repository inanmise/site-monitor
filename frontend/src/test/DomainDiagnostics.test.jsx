import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor, within, act } from './test-utils.jsx'
import userEvent from '@testing-library/user-event'
import DomainDiagnostics from '../components/admin/DomainDiagnostics.jsx'

/**
 * Ayarlar → Alan Adı Tanılama (2026-09-26 zenginleştirme): form doğrulama + URL indirgeme, özet kartı (kalan gün
 * rozeti, kaynak rozeti, registrar, kayıtlı alan adı), zincir şeridi, son sorgular, hızlı eylemler (envanterde aç,
 * özeti kopyala), 429 şeridi. Sorgular rol/ad/`data-slot` ile; sabitler backend tel biçiminde (snake_case).
 */
const { adminMock, failures, copyMock, navMock } = vi.hoisted(() => ({
  adminMock: { runDomainExpiryDiagnostics: vi.fn(), getInventoryByDomain: vi.fn(), captureProxyCaChain: vi.fn() },
  failures: { list: [] },
  copyMock: vi.fn(() => Promise.resolve(true)),
  navMock: vi.fn(),
}))
vi.mock('../api/client', () => ({ api: { admin: adminMock }, getRecentFailures: () => failures.list, formatDate: (s) => s ?? '' }))
vi.mock('../utils/copyText.js', () => ({ copyText: (v) => copyMock(v) }))
vi.mock('../utils/navigate.js', () => ({ navigateTo: (...a) => navMock(...a) }))

const OK = { success: true, data: {
  domain: 'www.example.com', registrable: 'example.com', tld: 'com', source: 'RDAP_REGISTRY', whois_provider: null,
  expiry_date: '2027-03-14', days_remaining: 169, registrar: 'Örnek Tescil Ltd.', persisted: 2,
  steps: [
    { step: 'PSL', status: 'ok', detail: 'www.example.com → example.com (.com)', elapsed_ms: 1 },
    { step: 'IANA_BOOTSTRAP', status: 'ok', http_status: 200, elapsed_ms: 212 },
    { step: 'RDAP_REGISTRY', status: 'ok', detail: 'expiration: 2027-03-14', http_status: 200, elapsed_ms: 486 },
    { step: 'RDAP_ORG', status: 'skip', detail: 'gerekmedi' },
    { step: 'WHOIS', status: 'skip', detail: 'gerekmedi' },
  ],
} }

const FAIL = { success: true, data: {
  domain: 'portal.example.org', registrable: 'example.org', tld: 'org', source: 'FAILED', expiry_date: null, days_remaining: null, registrar: null,
  steps: [
    { step: 'PSL', status: 'ok', elapsed_ms: 1 },
    { step: 'IANA_BOOTSTRAP', status: 'fail', error_class: 'PKIX_TRUST', error: 'PKIX path building failed', elapsed_ms: 318 },
    { step: 'WHOIS', status: 'fail', error_class: 'CONNECT_TIMEOUT', error: 'connect timed out', elapsed_ms: 5003 },
  ],
} }

beforeEach(() => {
  vi.clearAllMocks()
  failures.list = []
  localStorage.clear()
  adminMock.runDomainExpiryDiagnostics.mockResolvedValue(OK)
  adminMock.getInventoryByDomain.mockResolvedValue({ success: true, data: { id: 118, domain: 'example.com', team_name: 'Takım A' } })
})

const input = () => screen.getByRole('textbox', { name: /^Domain$/ })
const queryBtn = () => screen.getByRole('button', { name: /^Query$/ })

describe('DomainDiagnostics', () => {
  it('geçersiz alan adı: alan hatası çizilir, uç ÇAĞRILMAZ; boş girdi düğmeyi kapalı tutar', async () => {
    const user = userEvent.setup()
    render(<DomainDiagnostics />)
    expect(queryBtn()).toBeDisabled()
    await user.type(input(), 'localhost')
    expect(queryBtn()).toBeEnabled()
    await user.click(queryBtn())
    expect(adminMock.runDomainExpiryDiagnostics).not.toHaveBeenCalled()
    expect(input()).toHaveAttribute('aria-invalid', 'true')
    expect(screen.getByText('Enter a valid domain name (e.g. example.com).')).toBeInTheDocument()
    // yazmaya devam edince hata kalkar
    await user.type(input(), '.example.com')
    expect(input()).not.toHaveAttribute('aria-invalid')
  })

  it('URL yapıştırılırsa sunucu adına indirgenir ve o adla sorgulanır (Enter ile)', async () => {
    const user = userEvent.setup()
    render(<DomainDiagnostics />)
    await user.type(input(), 'https://WWW.Example.com/giris?x=1{Enter}')
    await waitFor(() => expect(adminMock.runDomainExpiryDiagnostics).toHaveBeenCalledWith('www.example.com'))
    expect(input()).toHaveValue('www.example.com')
  })

  it('başarılı sorgu: özet kartı (169 gün, "Expiry found", kaynak rozeti, registrar, kayıtlı alan adı), zincir şeridi, adım kartları, toplam süre', async () => {
    const user = userEvent.setup()
    render(<DomainDiagnostics />)
    await user.type(input(), 'www.example.com{Enter}')
    const result = await waitFor(() => document.querySelector('[data-slot="dexp-result"]'))
    expect(result).toHaveAttribute('data-found', 'true')
    expect(result.querySelector('[data-slot="dexp-days"]')).toHaveTextContent('169')
    expect(result.querySelector('[data-slot="dexp-status"]')).toHaveTextContent('Expiry found')
    expect(result.querySelector('[data-slot="dexp-status"]')).toHaveAttribute('data-tone', 'success')
    expect(result.querySelector('[data-slot="dexp-source"]')).toHaveTextContent('RDAP_REGISTRY')
    expect(within(result).getByText('Örnek Tescil Ltd.')).toBeInTheDocument()
    expect(within(result).getByText('example.com · .com')).toBeInTheDocument()
    expect(within(result).getByText('2 row(s) updated')).toBeInTheDocument()
    const chain = result.querySelector('[data-slot="dexp-chain"]')
    expect([...chain.querySelectorAll('[data-status]')].map((el) => el.getAttribute('data-status'))).toEqual(['ok', 'ok', 'ok', 'skip', 'skip'])
    const steps = result.querySelectorAll('[data-slot="dexp-steps"] > li')
    expect(steps).toHaveLength(5)
    expect(within(result).getByText('699 ms in total')).toBeInTheDocument()
    expect(screen.getByRole('heading', { level: 4, name: 'www.example.com — lookup result' })).toBeInTheDocument()
  })

  it('başarısız sorgu: "Expiry not found" danger, hata sınıfı rozetleri ve ipuçları', async () => {
    adminMock.runDomainExpiryDiagnostics.mockResolvedValue(FAIL)
    const user = userEvent.setup()
    render(<DomainDiagnostics />)
    await user.type(input(), 'portal.example.org{Enter}')
    const result = await waitFor(() => document.querySelector('[data-slot="dexp-result"]'))
    expect(result).toHaveAttribute('data-found', 'false')
    expect(result.querySelector('[data-slot="dexp-status"]')).toHaveTextContent('Expiry not found')
    expect(result.querySelector('[data-slot="dexp-status"]')).toHaveAttribute('data-tone', 'danger')
    expect(within(result).getByText('PKIX_TRUST')).toBeInTheDocument()
    expect(within(result).getByText(/proxy SSL-inspection root CA is not in the JVM truststore/)).toBeInTheDocument()
    expect(within(result).getByText(/Port 43 egress may be blocked/)).toBeInTheDocument()
  })

  it('son sorgular: başarılı sorgu localStorage\'a yazılır, çip olarak listelenir, çipe basınca yeniden sorgular, temizlenebilir', async () => {
    const user = userEvent.setup()
    render(<DomainDiagnostics />)
    expect(document.querySelector('[data-slot="dexp-recent"]')).toBeNull()
    await user.type(input(), 'www.example.com{Enter}')
    await waitFor(() => expect(JSON.parse(localStorage.getItem('sm.dexp.recent'))).toEqual(['www.example.com']))
    const recent = document.querySelector('[data-slot="dexp-recent"]')
    await user.click(within(recent).getByRole('button', { name: 'Look up www.example.com again' }))
    await waitFor(() => expect(adminMock.runDomainExpiryDiagnostics).toHaveBeenCalledTimes(2))
    await user.click(within(recent).getByRole('button', { name: 'Clear recent lookups' }))
    expect(document.querySelector('[data-slot="dexp-recent"]')).toBeNull()
    expect(localStorage.getItem('sm.dexp.recent')).toBeNull()
  })

  it('hızlı eylemler: envanterde kayıt varsa "Open in inventory" i_q ile Domain Envanteri\'ne gider; "Copy summary" düz metni panoya yazar', async () => {
    const user = userEvent.setup()
    render(<DomainDiagnostics />)
    await user.type(input(), 'www.example.com{Enter}')
    await waitFor(() => expect(adminMock.getInventoryByDomain).toHaveBeenCalledWith('example.com'))
    const openBtn = await screen.findByRole('button', { name: /^Open in inventory$/ })
    await waitFor(() => expect(openBtn).toBeEnabled())
    await user.click(openBtn)
    expect(navMock).toHaveBeenCalledWith('domains', { i_q: 'example.com' })
    await user.click(screen.getByRole('button', { name: /Copy summary/ }))
    await waitFor(() => expect(copyMock).toHaveBeenCalled())
    const text = copyMock.mock.calls[0][0]
    expect(text).toContain('Expiry: 2027-03-14 (169 days left)')
    expect(text).toContain('Registrar: Örnek Tescil Ltd.')
    expect(text).toContain('Source: RDAP_REGISTRY')
  })

  it('envanterde kayıt yoksa "Open in inventory" kapalı ve not düşer', async () => {
    adminMock.getInventoryByDomain.mockResolvedValue({ success: true, data: null })
    const user = userEvent.setup()
    render(<DomainDiagnostics />)
    await user.type(input(), 'www.example.com{Enter}')
    await waitFor(() => expect(document.querySelector('[data-slot="dexp-not-in-inventory"]')).toBeInTheDocument())
    expect(screen.getByRole('button', { name: /Open in inventory/ })).toBeDisabled()
  })

  it('429: dostça geri sayımlı şerit; ham sunucu metni ve sonuç yok; süre dolunca yeniden dene', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    try {
      const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime })
      failures.list = [{ path: '/admin/diagnostics/domain-expiry', status: 429, at: 'x' }]
      adminMock.runDomainExpiryDiagnostics.mockResolvedValue({ success: false, error: 'Çok fazla tanılama isteği' })
      render(<DomainDiagnostics />)
      await user.type(input(), 'www.example.com{Enter}')
      const banner = await screen.findByRole('alert')
      expect(banner).toHaveAttribute('data-tone', 'warning')
      expect(banner).toHaveTextContent('Rate limit reached')
      expect(screen.queryByText('Çok fazla tanılama isteği')).toBeNull()
      expect(document.querySelector('[data-slot="dexp-result"]')).toBeNull()
      await act(async () => { vi.advanceTimersByTime(61_000) })
      failures.list = []
      adminMock.runDomainExpiryDiagnostics.mockResolvedValue(OK)
      await user.click(within(banner).getByRole('button', { name: /Run Again/ }))
      await waitFor(() => expect(adminMock.runDomainExpiryDiagnostics).toHaveBeenCalledTimes(2))
    } finally {
      vi.useRealTimers()
    }
  })

  it('sunucu hatası (429 değil) kırmızı şerit + toast', async () => {
    adminMock.runDomainExpiryDiagnostics.mockResolvedValue({ success: false, error: 'tanı ucu yanıt vermedi' })
    const user = userEvent.setup()
    render(<DomainDiagnostics />)
    await user.type(input(), 'www.example.com{Enter}')
    const alert = await screen.findByRole('alert')
    expect(alert).toHaveAttribute('data-tone', 'danger')
    expect(alert).toHaveTextContent('tanı ucu yanıt vermedi')
  })
})
