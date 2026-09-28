import { describe, it, expect, vi } from 'vitest'
import { render } from './test-utils.jsx'

/**
 * HİÇ KONTROL EDİLMEMİŞ KART — dokuz tür, Kompakt + Zengin (2026-09-28, kullanıcı bildirimi: "kaydettikten sonra açılan
 * kartta veriler yansımıyor, boş bir görünüm oluyor").
 *
 * Sözleşme (monitoring/MonitorCard → MonitorPendingText / MonitorCardPending):
 *  - kontrol KOŞARKEN (kartın `running`'i = sayfanın isRunning(id)): dönen gösterge + "İlk kontrol yapılıyor…"
 *    (`data-slot="monitor-first-check"`), kök `data-running="true"`;
 *  - koşmuyorken (başlatılamadı / zamanlayıcıyı bekliyor): türün bekleme metni ("İlk kontrol bekleniyor" …) —
 *    gövde ASLA boş değil. Önceden Ping (Kompakt'ta gövde tamamen boş) ve Sayfa Hızı (yalnız "—" ölçerler) boş görünüyordu.
 */
const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
vi.mock('../api/client', () => ({
  formatDate: (s) => s ?? '', formatDateSec: (s) => (s ? `exact:${s}` : ''), formatDateOnly: (s) => s ?? '',
  api: withApiFallback({ monitoring: { maintenance: { active: vi.fn(() => Promise.resolve({ success: true, data: { all: false, targets: [] } })) } } }),
}))

import PingMonitorCard from '../components/ping/PingMonitorCard.jsx'
import DnsMonitorCard from '../components/dns/DnsMonitorCard.jsx'
import PortMonitorCard from '../components/port/PortMonitorCard.jsx'
import DomainMonitorCard from '../components/domain/DomainMonitorCard.jsx'
import KeywordMonitorCard from '../components/keyword/KeywordMonitorCard.jsx'
import PageMonitorCard from '../components/page/PageMonitorCard.jsx'
import PageSpeedMonitorCard from '../components/pagespeed/PageSpeedMonitorCard.jsx'
import ScriptedMonitorCard from '../components/scripted/ScriptedMonitorCard.jsx'
import HttpMonitorCard from '../components/http/HttpMonitorCard.jsx'

const NEVER = { id: 5, name: 'Yeni izleme', active: true, team_id: 1, team_name: 'Takım A', group_name: 'Kurumsal Web', tags: 'prod', status: 'unknown', checked_at: null }

/** [tür, kart, hiç-kontrol satırı, bekleme metni (EN — testler İngilizce çalışır), bekleme yuvası] */
const CARDS = [
  ['http', HttpMonitorCard, { url: 'https://www.example.com/' }, 'Waiting for the first check', 'http-pending'],
  ['ping', PingMonitorCard, { host: 'gw.example.com' }, 'Waiting for the first check', 'ping-pending'],
  ['keyword', KeywordMonitorCard, { url: 'https://www.example.com/', keyword: 'Welcome', operator: 'GTE', match_count: 1 }, 'Waiting for the first check', 'keyword-result'],
  ['page', PageMonitorCard, { url: 'https://www.example.com/', mode: 'SINGLE_PAGE' }, 'Waiting for its first check', ['page-integrity', 'page-compact']],
  ['pagespeed', PageSpeedMonitorCard, { url: 'https://www.example.com/', status: 'unknown', last_check: null }, 'Waiting for the first check', 'pspd-pending'],
  ['scripted', ScriptedMonitorCard, { script: 'export default function () {}' }, 'Waiting for its first run', ['scripted-result', 'scripted-compact-run']],
  ['dns', DnsMonitorCard, { domain: 'www.example.com', record_type: 'A', standalone: true, value: null }, 'Awaiting the first check', ['dns-values-empty', 'dns-compact-empty']],
  ['port', PortMonitorCard, { host: 'db.example.com', port: 5432, protocol: 'TCP', standalone: true, response_ms: null }, 'Awaiting first check', ['port-result-title', 'port-compact']],
  ['domain', DomainMonitorCard, { domain: 'example.com', status: 'UNKNOWN', days_remaining: null, expiry_date: null, source: null }, 'Expiry date unknown', 'domain-unknown'],
]

const slotOf = (slot, density) => (Array.isArray(slot) ? slot[density === 'rich' ? 0 : 1] : slot)

describe.each(CARDS)('hiç kontrol edilmemiş kart — %s', (kind, Card, row, idleText, slot) => {
  describe.each(['rich', 'compact'])('%s', (density) => {
    it('koşmuyorken: türün bekleme metni görünür, gövde boş DEĞİL, "İlk kontrol yapılıyor" YOK', () => {
      const { container } = render(<Card monitor={{ ...NEVER, ...row }} density={density} onOpen={() => {}} />)
      const card = container.querySelector('[data-slot="card"]')
      expect(card).not.toHaveAttribute('data-running')
      const pending = container.querySelector(`[data-slot="${slotOf(slot, density)}"]`)
      expect(pending, `${kind}/${density} bekleme yuvası`).not.toBeNull()
      expect(pending.textContent).toContain(idleText)
      expect(container.querySelector('[data-slot="monitor-first-check"]')).toBeNull()
      expect(container.querySelector('[data-slot="card-content"]').textContent.trim()).not.toBe('')
    })

    it('ilk kontrol KOŞARKEN: aynı yuvada dönen gösterge + "Running the first check…"', () => {
      const { container } = render(<Card monitor={{ ...NEVER, ...row }} density={density} running onOpen={() => {}} />)
      expect(container.querySelector('[data-slot="card"]')).toHaveAttribute('data-running', 'true')
      const first = container.querySelector('[data-slot="monitor-first-check"]')
      expect(first, `${kind}/${density}`).not.toBeNull()
      expect(first).toHaveTextContent(/^Running the first check…$/)
      expect(first.querySelector('[data-slot="spinner"]')).not.toBeNull()
      // Yuva korunur (test kancaları / kutu aynı), metin değişir.
      const pending = container.querySelector(`[data-slot="${slotOf(slot, density)}"]`)
      expect(pending, `${kind}/${density} yuva korunur`).not.toBeNull()
      expect(pending.contains(first)).toBe(true)
    })
  })

  it('sonucu OLAN kartta `running` bekleme metni üretmez (yeniden kontrol ölçüleri gizlemez)', () => {
    const checked = { ...NEVER, ...row, status: 'up', checked_at: '2026-09-28T09:00:00', last_check: '2026-09-28T09:00:00',
      value: '192.0.2.1', response_ms: 20, http_status: 200, ok: true, found: true, occurrences: 1, rtt_ms: 10, packet_loss: 0,
      total_resources: 10, broken_resources: 0, timeout_count: 0, mixed_content_count: 0, days_remaining: 100, expiry_date: '2027-01-06' }
    if (kind === 'port') checked.status = 'open'
    if (kind === 'page' || kind === 'domain' || kind === 'pagespeed') checked.status = 'OK'
    if (kind === 'scripted') { checked.status = 'PASS'; checked.duration_ms = 800; checked.checks_passed = 1; checked.checks_failed = 0 }
    const { container } = render(<Card monitor={checked} density="rich" running onOpen={() => {}} />)
    expect(container.querySelector('[data-slot="monitor-first-check"]')).toBeNull()
  })
})
