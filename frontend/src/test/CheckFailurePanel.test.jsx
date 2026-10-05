import { describe, it, expect, vi } from 'vitest'
import { render, screen, fireEvent, within } from './test-utils.jsx'
import {
  CheckFailureBadge, CheckFailureBlock, CheckFailureCell, CheckFailurePanel,
} from '../components/checks/CheckFailurePanel.jsx'

/**
 * Kontrol geçmişi hata teşhisi hücresi + paneli (2026-10-05) — Ping, Port, DNS, Sayfa, Sayfa Hızı, Durum, Alan Adı ve
 * Sertifika geçmişinin ortak parçaları: neden rozeti + tek satır + aç/kapa; panelde Neden / Etkisi / Ne yapmalı, kayıttaki
 * ayrıntılar, kopyalanabilir ham hata, eski satır notu ve (yalnız verildiyse) tanılama düğmesi. Sorgular rol / data-slot.
 */
const slot = (root, s) => root.querySelector(`[data-slot="${s}"]`)

const PORT = { id: 2, host: 'db.example.test', port: 5432, protocol: 'TCP', timeout_ms: 4000 }
const fresh = {
  id: 77, open: false, response_ms: null, checked_at: '2026-10-05T09:00:00',
  error: 'vekil tüneli reddetti: HTTP/1.1 403 Forbidden — vekil bu porta tünel açmıyor olabilir (izinli: 443, 8443)',
  failure_reason: 'PROXY_REFUSED',
  failure_detail: JSON.stringify({ phase: 'CONNECT', via: 'proxy', proxy_status: 403, proxy_refused: true,
    allowed_ports: [443, 8443], target: 'db.example.test:5432', protocol: 'TCP', timeout_ms: 4000,
    exception: 'IOException', message: 'vekil tüneli reddetti: HTTP/1.1 403 Forbidden' }),
}
const legacyRow = { id: 78, open: false, checked_at: '2026-01-01T09:00:00', error: 'Connection refused: connect' }

describe('CheckFailureCell', () => {
  it('rozet (kısa neden) + tek satır + aç/kapa (aria-expanded, aria-controls, zamanlı erişilebilir ad)', () => {
    const onToggle = vi.fn()
    const { container, rerender } = render(<CheckFailureCell type="port" check={fresh} monitor={PORT} open={false}
      onToggle={onToggle} when="05.10 09:00" panelId="p-77" />)
    const cell = slot(container, 'chkfail-cell')
    expect(cell).toHaveAttribute('data-code', 'PROXY_REFUSED')
    expect(cell).toHaveAttribute('data-type', 'port')
    expect(cell).not.toHaveAttribute('data-legacy')
    expect(slot(container, 'chkfail-badge').textContent).toMatch(/^(Proxy did not open a tunnel|Vekil tüneli açmadı)$/)
    expect(slot(container, 'chkfail-oneline').textContent).toMatch(/db\.example\.test:5432/)
    expect(slot(container, 'chkfail-oneline').textContent).toMatch(/443, 8443/)
    const btn = screen.getByRole('button', { name: /05\.10 09:00 · (Proxy did not open a tunnel|Vekil tüneli açmadı) — (Show details|Ayrıntıyı göster)/ })
    expect(btn).toHaveAttribute('aria-expanded', 'false')
    expect(btn).not.toHaveAttribute('aria-controls')
    fireEvent.click(btn)
    expect(onToggle).toHaveBeenCalledTimes(1)
    rerender(<CheckFailureCell type="port" check={fresh} monitor={PORT} open onToggle={onToggle} when="05.10 09:00" panelId="p-77" />)
    const open = screen.getByRole('button', { name: /(Hide details|Ayrıntıyı gizle)/ })
    expect(open).toHaveAttribute('aria-expanded', 'true')
    expect(open).toHaveAttribute('aria-controls', 'p-77')
  })

  it('eski satır: en yakın neden + data-legacy; sağlıklı satırda hiçbir şey çizilmez', () => {
    const { container } = render(<CheckFailureCell type="port" check={legacyRow} monitor={PORT} onToggle={() => {}} when="x" />)
    expect(slot(container, 'chkfail-cell')).toHaveAttribute('data-legacy', 'true')
    expect(slot(container, 'chkfail-cell')).toHaveAttribute('data-code', 'CONNECT_REFUSED')
    const healthy = render(<CheckFailureCell type="port" check={{ open: true }} monitor={PORT} onToggle={() => {}} when="x" />)
    expect(healthy.container.firstChild).toBeNull()
  })
})

describe('CheckFailurePanel', () => {
  it('Neden / Etkisi / Ne yapmalı + kayıttaki ayrıntılar + kopyalanabilir teknik ayrıntı; tanılama verilmezse düğme YOK', () => {
    const { container } = render(<CheckFailurePanel type="port" check={fresh} monitor={PORT} id="p-77" />)
    const panel = slot(container, 'chkfail-panel')
    expect(panel).toHaveAttribute('id', 'p-77')
    expect(screen.getByRole('region', { name: /(Failure detail|Hata ayrıntısı)/ })).toBe(panel)
    expect(slot(panel, 'chkfail-why').textContent).toMatch(/403/)
    expect(slot(panel, 'chkfail-effect').textContent.length).toBeGreaterThan(10)
    expect(slot(panel, 'chkfail-fix').textContent.length).toBeGreaterThan(10)
    expect(slot(panel, 'chkfail-legacy')).toBeNull()
    const keys = [...panel.querySelectorAll('[data-slot="chkfail-details"] [data-key]')].map((e) => e.getAttribute('data-key'))
    expect(keys).toEqual(['phase', 'target', 'via', 'protocol', 'proxyStatus', 'allowedPorts', 'timeoutMs', 'exception'])
    expect(panel.querySelector('[data-key="via"]').textContent).toMatch(/(corporate proxy|kurumsal vekil)/i)
    expect(panel.querySelector('[data-key="proxyStatus"]').textContent).toBe('HTTP 403')
    const tech = slot(panel, 'chkfail-technical')
    expect(tech.textContent).toContain('vekil tüneli reddetti: HTTP/1.1 403 Forbidden')
    expect(within(tech).getByRole('button', { name: /(Copy technical detail|Teknik ayrıntıyı kopyala)/ })).toBeInTheDocument()
    expect(slot(panel, 'chkfail-diagnose')).toBeNull()
  })

  it('tanılama ikinci aşama için takılabilir: canDiagnose + onDiagnose verilince düğme çalışır', () => {
    const onDiagnose = vi.fn()
    const { container } = render(<CheckFailurePanel type="port" check={fresh} monitor={PORT} canDiagnose onDiagnose={onDiagnose} />)
    fireEvent.click(within(slot(container, 'chkfail-panel')).getByRole('button', { name: /(Diagnose this check|Bu kontrolü tanıla)/ }))
    expect(onDiagnose).toHaveBeenCalledTimes(1)
    const noHandler = render(<CheckFailurePanel type="port" check={fresh} monitor={PORT} canDiagnose />)
    expect(noHandler.container.querySelector('[data-slot="chkfail-diagnose"]')).toBeNull()
  })

  it('eski satır: "ayrıntı kaydedilmemiş" notu; showRaw=false teknik bloğu gizler; sağlıklı satırda boş', () => {
    const { container } = render(<CheckFailurePanel type="port" check={legacyRow} monitor={PORT} showRaw={false} />)
    expect(slot(container, 'chkfail-legacy')).not.toBeNull()
    expect(slot(container, 'chkfail-technical')).toBeNull()
    const healthy = render(<CheckFailurePanel type="ping" check={{ up: true }} monitor={{}} />)
    expect(healthy.container.firstChild).toBeNull()
  })

  it('her tür için başarısız satır paneli çizer (DNS rcode, sayfa sayaçları, alan adı, sertifika aşaması)', () => {
    const cases = [
      ['dns', { value: '', error: 'NXDOMAIN', record_type: 'A', failure_reason: 'DNS_NXDOMAIN',
        failure_detail: JSON.stringify({ phase: 'DNS', rcode: 'NXDOMAIN', record_type: 'A', target: 'nx.example.test' }) },
        { domain: 'nx.example.test' }, 'DNS_NXDOMAIN', ['rcode', 'recordType']],
      ['page', { status: 'DEGRADED', ok: false, broken_resources: 3, timeout_count: 1, total_resources: 40 },
        { url: 'https://site.example.test/' }, 'RESOURCES_BROKEN', ['broken', 'timeouts', 'totalResources']],
      ['domain', { status: 'UNKNOWN', error: 'rdap http 404', checked_at: '2026-10-05T09:00:00' },
        { domain: 'example.test' }, 'RDAP_NOT_FOUND', ['target']],
      ['cert', { status: 'error', error_class: 'NETWORK', error: 'Connection timeout after 6s', error_stage: 'tcp-connect',
        resolved_ips: '192.0.2.5', domain: 'api.example.test' }, {}, 'CONNECT_TIMEOUT', ['errorStage', 'resolvedIps']],
    ]
    for (const [type, row, mon, code, keys] of cases) {
      const { container, unmount } = render(<CheckFailurePanel type={type} check={row} monitor={mon} />)
      const panel = slot(container, 'chkfail-panel')
      expect(panel, type).toHaveAttribute('data-code', code)
      const got = [...panel.querySelectorAll('[data-key]')].map((e) => e.getAttribute('data-key'))
      for (const k of keys) expect(got, `${type}: ${k}`).toContain(k)
      unmount()
    }
  })
})

describe('CheckFailureBadge / CheckFailureBlock', () => {
  it('rozet yalnız başarısız satırda; blok kapalıyken özet, açıkken özet + panel', () => {
    const cert = { status: 'error', error_class: 'DNS', error: 'Domain resolution failed', domain: 'nx.example.test' }
    const b = render(<CheckFailureBadge type="cert" check={cert} />)
    expect(slot(b.container, 'chkfail-badge')).toHaveAttribute('data-code', 'DNS_RESOLVE')
    b.unmount()
    expect(render(<CheckFailureBadge type="cert" check={{ status: 'valid' }} />).container.firstChild).toBeNull()

    const row = { status: 'UNKNOWN', error: 'rdap http 429', checked_at: '2026-10-05T09:00:00' }
    const closed = render(<CheckFailureBlock type="domain" check={row} monitor={{ domain: 'example.test' }} open={false} onToggle={() => {}} when="x" />)
    expect(slot(closed.container, 'chkfail-cell')).toHaveAttribute('data-code', 'RDAP_RATE_LIMITED')
    expect(slot(closed.container, 'chkfail-panel')).toBeNull()
    closed.unmount()
    const opened = render(<CheckFailureBlock type="domain" check={row} monitor={{ domain: 'example.test' }} open onToggle={() => {}} when="x" panelId="d-1" />)
    expect(slot(opened.container, 'chkfail-panel')).toHaveAttribute('id', 'd-1')
  })
})
