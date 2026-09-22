import { describe, it, expect, vi } from 'vitest'
import { render, screen, fireEvent } from './test-utils.jsx'
import HttpErrorDetail from '../components/http/HttpErrorDetail.jsx'

vi.mock('../api/client', () => ({ formatDateSec: (s) => 'T(' + s + ')' }))

const t = (k, ...a) => k + (a.length ? '(' + a.join(',') + ')' : '')

/** HTTP hata tanısı paneli (2026-09-22): evre şeridi, yol, bekleme/zaman aşımı, IP'ler, ham zincir; tanısız satır notu. */
describe('HttpErrorDetail', () => {
  const detail = {
    kind: 'CONNECT_TIMEOUT', phase: 'CONNECT', url: 'https://a.example.com/health', method: 'GET', scheme: 'https',
    host: 'a.example.com', port: 443, via: 'direct', proxy: null, resolved_ips: ['192.0.2.10', '192.0.2.11'], dns_ms: 7,
    target_ip: '192.0.2.10', local_ip: '10.0.0.5', timeout_ms: 5000, elapsed_ms: 5012, verify_ssl: true, follow_redirects: true,
    redirects: ['https://a.example.com/login'], expected_status: '200-399', http_status: null,
    exception: 'java.net.http.HttpConnectTimeoutException', message: 'HTTP connect timed out',
    cause_chain: ['java.net.http.HttpConnectTimeoutException: HTTP connect timed out', 'java.net.ConnectException: connect timed out'],
  }
  const check = { id: 1, ok: false, error: 'HTTP connect timed out', checked_at: '2026-09-22T10:00:00', error_detail: JSON.stringify(detail) }

  it('zaman aşımı: TCP evresi takıldı, kaynak → hedef:port, bekleme + ayar + "dayandı", IP listesi, yönlendirme, ham zincir katlanır', () => {
    const { container } = render(<HttpErrorDetail check={check} t={t} />)
    expect(screen.getByText('httpdiag.kind.CONNECT_TIMEOUT')).toBeInTheDocument()
    expect(screen.getByText('httpdiag.hint.CONNECT_TIMEOUT')).toBeInTheDocument()
    expect(container.querySelector('.hdiag-phase--stuck .hdiag-phase-name').textContent).toBe('httpdiag.phase.CONNECT')
    expect(container.querySelectorAll('.hdiag-phase--done')).toHaveLength(1)      // DNS
    expect(container.querySelectorAll('.hdiag-phase--skipped')).toHaveLength(3)   // TLS, istek, yanıt
    expect(screen.getByText('10.0.0.5')).toBeInTheDocument()
    expect(screen.getByText('192.0.2.10:443')).toBeInTheDocument()
    expect(screen.getByText('httpdiag.viaDirect')).toBeInTheDocument()
    expect(screen.getByText(/5012 ms/)).toBeInTheDocument()
    expect(screen.getByText(/httpdiag\.timeoutSet\(5000\)/)).toBeInTheDocument()
    expect(screen.getByText('httpdiag.timeoutHit')).toBeInTheDocument()
    expect(container.querySelector('.hdiag-ip--target').textContent).toBe('192.0.2.10')
    expect(screen.getByText('192.0.2.11')).toBeInTheDocument()
    expect(screen.getByText(/httpdiag\.dnsMs\(7\)/)).toBeInTheDocument()
    expect(screen.getByText('https://a.example.com/login')).toBeInTheDocument()
    expect(screen.getByText(/httpdiag\.expected\(200-399\)/)).toBeInTheDocument()
    expect(screen.getByText('httpdiag.tlsStrict')).toBeInTheDocument()
    // Ham zincir kapalı başlar, tıklayınca açılır
    expect(container.querySelector('.hdiag-raw')).toBeNull()
    fireEvent.click(screen.getByText('httpdiag.rawToggle'))
    expect(container.querySelector('.hdiag-raw').textContent).toContain('java.net.ConnectException: connect timed out')
    // Panelin kendi kapatma düğmesi YOK — kapatmayı saran ModalShell veriyor (2026-09-23).
    expect(container.querySelector('.hdiag-close')).toBeNull()
  })

  it('vekil yolu: TCP hedefi vekil, asıl hedef rozetle; durum uyuşmazlığı yanıt evresinde', () => {
    const d = { ...detail, kind: 'STATUS_MISMATCH', phase: 'RESPONSE', via: 'proxy', proxy: 'proxy.example.net:8080', http_status: 503,
      elapsed_ms: 120, exception: null, cause_chain: [] }
    const { container } = render(<HttpErrorDetail check={{ ...check, error: null, http_status: 503, error_detail: d }} t={t} />)
    expect(screen.getByText('proxy.example.net:8080')).toBeInTheDocument()
    expect(screen.getByText('httpdiag.viaProxy(a.example.com:443)')).toBeInTheDocument()
    expect(container.querySelector('.hdiag-phase--stuck .hdiag-phase-name').textContent).toBe('httpdiag.phase.RESPONSE')
    expect(screen.getByText('HTTP 503')).toBeInTheDocument()
    expect(screen.queryByText('httpdiag.timeoutHit')).toBeNull()
    expect(screen.queryByText('httpdiag.rawToggle')).toBeNull()
  })

  it('tanısız (eski) satır: not + ham hata; tanı yoksa ve durum kodu varsa uyuşmazlık başlığı', () => {
    render(<HttpErrorDetail check={{ id: 2, ok: false, error: 'boom', checked_at: 'x' }} t={t} />)
    expect(screen.getByText('httpdiag.legacy')).toBeInTheDocument()
    expect(screen.getByText('boom')).toBeInTheDocument()
    render(<HttpErrorDetail check={{ id: 3, ok: false, http_status: 500, checked_at: 'x' }} t={t} />)
    expect(screen.getByText(/httpdiag.kind.STATUS_MISMATCH · HTTP 500/)).toBeInTheDocument()
  })
})
