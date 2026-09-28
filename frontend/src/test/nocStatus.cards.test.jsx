import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor, within, act } from './test-utils.jsx'

const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))

vi.mock('../api/client', () => ({
  formatDate: (s) => s ?? '',
  formatDateSec: (s) => s ?? '',
  formatDateOnly: (s) => s ?? '',
  api: withApiFallback({ noc: { groupOptions: vi.fn() } }),
}))
const nav = vi.fn()
vi.mock('../utils/navigate.js', () => ({ navigateTo: (...a) => nav(...a), default: (...a) => nav(...a) }))

import { api } from '../api/client'
import { resetNocStateForTests } from '../components/noc/useNocState.js'
import { announceNocCoverageChange } from '../utils/nocCoverageEvent.js'
import { MonitorStatusBadge } from '../components/monitoring/MonitorCard.jsx'
import MonitorCardMeta from '../components/MonitorCardMeta.jsx'
import PingMonitorCard from '../components/ping/PingMonitorCard.jsx'
import HttpMonitorCard from '../components/http/HttpMonitorCard.jsx'
import KeywordMonitorCard from '../components/keyword/KeywordMonitorCard.jsx'
import PageMonitorCard from '../components/page/PageMonitorCard.jsx'
import PageSpeedMonitorCard from '../components/pagespeed/PageSpeedMonitorCard.jsx'
import ScriptedMonitorCard from '../components/scripted/ScriptedMonitorCard.jsx'
import DnsMonitorCard from '../components/dns/DnsMonitorCard.jsx'
import PortMonitorCard from '../components/port/PortMonitorCard.jsx'
import DomainMonitorCard from '../components/domain/DomainMonitorCard.jsx'
import CertificateCard from '../components/CertificateCard.jsx'

/**
 * 7/24 göstergesi KARTLARDA (2026-09-28, kullanıcı isteği: "kartların üzerinde … her bir izleme için bu bilgi
 * gözükmeli; mweb responsive"). Dokuz izleme kartı + Genel Bakış sertifika kartı:
 *  - gösterge HER kartta, İKİ yoğunlukta ve açık/kapalı İKİ durumda da çizilir (eski rozet yalnız Zengin + açık idi);
 *  - hep AYNI yerde: durum satırının SAĞ grubunun başı (MonitorCardTop; sertifika kartında katmanın solu);
 *  - tetik ve açıklamadaki eylem kartın detayını AÇMAZ (örtünün üstünde);
 *  - sayfada yüzlerce kart olsa da 7/24 durumu TEK istekle gelir; 7/24 yazması (olay) hepsini tazeler.
 */
const G = (id, name, { def = false, active = true } = {}) => ({ id, name, is_default: def, active })
function options({ disabled = [], hasActive = true } = {}) {
  api.noc.groupOptions.mockResolvedValue({
    success: true,
    data: { groups: [G(1, 'NOC Ana', { def: true })], disabled_types: disabled, has_active_group: hasActive, min_level: 'CRITICAL' },
  })
}

const base = {
  id: 9, name: 'Örnek', url: 'https://www.example.com/', host: 'h.example.com', domain: 'example.com', port: 443,
  protocol: 'TCP', record_type: 'A', value: '203.0.113.10', status: 'up', active: true, team_id: 1, team_name: 'Takım A',
  checked_at: '2026-09-26T09:00:00', last_check: '2026-09-26T09:00:00', script: 'export default function(){}',
  days_remaining: 120, expiry_date: '2027-01-26T00:00:00', noc_notify: true, noc_group_ids: [],
}
const badge = <MonitorStatusBadge status="up">OK</MonitorStatusBadge>
const meta = (m) => <MonitorCardMeta monitor={m} />
/** [ad, tür, satır adı, çizici(m, yoğunluk, ek)] */
const CARDS = [
  ['Ping', 'PING', (m) => m.host, (m, d, x) => <PingMonitorCard monitor={m} density={d} onOpen={() => {}} {...x} />],
  ['HTTP', 'HTTP', (m) => m.url, (m, d, x) => <HttpMonitorCard monitor={m} density={d} status="up" badge={badge} meta={meta(m)} onOpen={() => {}} {...x} />],
  ['Keyword', 'KEYWORD', (m) => m.url, (m, d, x) => <KeywordMonitorCard monitor={{ ...m, keyword: 'Giriş' }} density={d} status="up" badge={badge} meta={meta(m)} onOpen={() => {}} {...x} />],
  ['Page', 'PAGE', (m) => m.url, (m, d, x) => <PageMonitorCard monitor={m} density={d} status="up" badge={badge} onOpen={() => {}} {...x} />],
  ['PageSpeed', 'PAGESPEED', (m) => m.url, (m, d, x) => <PageSpeedMonitorCard monitor={m} density={d} status="up" badge={badge} meta={meta(m)} onOpen={() => {}} {...x} />],
  ['Scripted', 'SCRIPTED', (m) => m.name, (m, d, x) => <ScriptedMonitorCard monitor={m} density={d} status="up" badge={badge} onOpen={() => {}} {...x} />],
  ['DNS', 'DNS', (m) => m.domain, (m, d, x) => <DnsMonitorCard monitor={m} density={d} status="up" statusBadge={badge} meta={meta(m)} onOpen={() => {}} {...x} />],
  ['Port', 'PORT', (m) => `${m.host}:${m.port}`, (m, d, x) => <PortMonitorCard monitor={m} density={d} status="up" badge={badge} meta={meta(m)} onOpen={() => {}} {...x} />],
  ['Domain', 'DOMAIN', (m) => m.domain, (m, d, x) => <DomainMonitorCard monitor={m} density={d} status="up" badge={badge} meta={meta(m)} onOpen={() => {}} {...x} />],
]
const esc = (s) => s.replace(/[.*+?^${}()|[\]\\/]/g, '\\$&')
const indicators = () => [...document.querySelectorAll('[data-slot="noc-status"]')]
const triggerOf = (el) => el.closest('[data-slot="hint-trigger"]')

beforeEach(() => {
  vi.clearAllMocks()
  resetNocStateForTests()
  options()
})

describe('dokuz izleme kartı — yer, yoğunluk, durum', () => {
  for (const [name, type, label, card] of CARDS) {
    it(`${name}: Zengin hap + Kompakt ikon, durum satırının sağ grubunun BAŞINDA; kapalıda da görünür`, async () => {
      for (const density of ['rich', 'compact']) {
        for (const notify of [true, false]) {
          const r = render(card({ ...base, noc_notify: notify }, density))
          const list = indicators()
          expect(list, `${name}/${density}/${notify}: tam bir gösterge`).toHaveLength(1)
          const s = list[0]
          expect(s).toHaveAttribute('data-state', notify ? 'on' : 'off')
          if (density === 'compact') expect(s).toHaveAttribute('data-compact', 'true')
          else expect(s).not.toHaveAttribute('data-compact')
          // Yer: kart başlığının İLK satırı (durum satırı), sağ grubun İLK öğesi
          const trigger = triggerOf(s)
          const header = s.closest('[data-slot="card-header"]')
          expect(header.firstElementChild.contains(trigger), 'durum satırında değil').toBe(true)
          expect(trigger.parentElement.firstElementChild, 'sağ grubun başında değil').toBe(trigger)
          expect(trigger.parentElement.className).toMatch(/\bml-auto\b/)
          expect(trigger.parentElement.className).toMatch(/\bz-10\b/)   // CARD_LAYER — örtünün üstünde
          // Ad satırı ve durumu taşır; sabit metin değil
          expect(trigger).toHaveAccessibleName(new RegExp(`^${esc(label(base))} — (7/24 ${notify ? 'açık' : 'kapalı'}|24/7 ${notify ? 'on' : 'off'})$`))
          r.unmount()
        }
      }
      expect(type).toMatch(/^[A-Z]+$/)
    })
  }

  for (const [name, type, label, card] of CARDS) {
    it(`${name}: göstergeye ve açıklamadaki eyleme tıklamak kartın detayını AÇMAZ; düzenleyebilen "düzenle", diğeri "Kapsam" görür`, async () => {
      const onOpen = vi.fn()
      const r = render(card(base, 'rich', { onOpen, canEdit: true }))
      await waitFor(() => expect(indicators()[0]).toHaveAttribute('data-verified', 'true'))
      fireEvent.click(triggerOf(indicators()[0]))
      const dlg = await screen.findByRole('dialog')
      const edit = within(dlg).getByRole('link', { name: /7\/24 ayarını düzenle|Edit 24\/7 setting/ })
      fireEvent.click(edit)
      expect(onOpen).not.toHaveBeenCalled()
      expect(nav).toHaveBeenCalledWith(expect.any(String), { monitor: base.id, open: 'noc' })
      r.unmount()
      nav.mockClear()
      render(card(base, 'compact', { onOpen }))
      fireEvent.click(triggerOf(indicators()[0]))
      const dlg2 = await screen.findByRole('dialog')
      expect(within(dlg2).queryByRole('link', { name: /7\/24 ayarını düzenle|Edit 24\/7 setting/ })).toBeNull()
      fireEvent.click(within(dlg2).getByRole('link', { name: /7\/24 Kapsamı’nda gör|View in 24\/7 Coverage/ }))
      expect(nav).toHaveBeenCalledWith('noc', { n_type: type, n_q: label(base) })
      expect(onOpen).not.toHaveBeenCalled()
    })
  }
})

describe('tek istek — sayfada yüzlerce kart', () => {
  it('90 kart (dokuz tür × 10) TEK 7/24 isteği; yeniden bağlanınca (taze) yeni istek yok; 7/24 yazması olayı hepsini tazeler', async () => {
    const many = () => (
      <div>
        {CARDS.flatMap(([name, , , card]) => Array.from({ length: 10 }, (_, i) => (
          <div key={`${name}-${i}`}>{card({ ...base, id: i + 1 }, i % 2 ? 'compact' : 'rich')}</div>
        )))}
      </div>
    )
    const r = render(many())
    expect(indicators()).toHaveLength(90)
    await waitFor(() => expect(indicators().every((s) => s.getAttribute('data-verified') === 'true')).toBe(true))
    expect(api.noc.groupOptions).toHaveBeenCalledTimes(1)
    r.unmount()
    render(many())
    await new Promise((res) => setTimeout(res, 0))
    expect(api.noc.groupOptions).toHaveBeenCalledTimes(1)

    // Ayarlar'da HTTP türü kapatıldı (api/client başarılı 7/24 yazmasında olayı yayar) → tek yeni istek, HTTP kartları "iletilmiyor"
    options({ disabled: ['HTTP'] })
    act(() => { announceNocCoverageChange() })
    await waitFor(() => expect(document.querySelectorAll('[data-slot="noc-status"][data-state="blocked"]')).toHaveLength(10))
    expect(api.noc.groupOptions).toHaveBeenCalledTimes(2)
    expect(document.querySelectorAll('[data-slot="noc-status"][data-reason="TYPE_DISABLED"]')).toHaveLength(10)
  })
})

describe('Genel Bakış sertifika kartı', () => {
  const cert = (over = {}) => ({
    domain: 'www.example.com', issuer_cn: 'Test CA', issuer: 'Test CA', not_after: '2027-01-01T00:00:00',
    checked_at: '2026-09-26T09:00:00', status: 'valid', warning: false, days_remaining: 90, tier: 2, noc_notify: true, ...over,
  })

  it('sağ grubun başında, katmanın solunda; Kompakt (ek yok) ikon, Zengin (kart eki) hap; kapalıda da görünür', async () => {
    const r = render(<CertificateCard cert={cert()} onClick={() => {}} />)
    let s = indicators()[0]
    expect(s).toHaveAttribute('data-compact', 'true')
    expect(s).toHaveAttribute('data-state', 'on')
    const trigger = triggerOf(s)
    expect(trigger.parentElement.firstElementChild).toBe(trigger)
    expect(trigger.parentElement.className).toMatch(/\bml-auto\b/)
    expect(trigger.className).toMatch(/\bz-10\b/)
    expect(trigger).toHaveAccessibleName(/^www\.example\.com — (7\/24 açık|24\/7 on)$/)
    r.unmount()
    render(<CertificateCard cert={cert({ noc_notify: false })} onClick={() => {}} extra={{}} />)
    s = indicators()[0]
    expect(s).not.toHaveAttribute('data-compact')
    expect(s).toHaveAttribute('data-state', 'off')
  })

  it('düzenleyebilen: "7/24 ayarını düzenle" kartın Düzenle işleyicisini çağırır; kart penceresi AÇILMAZ', async () => {
    const onClick = vi.fn()
    const onEdit = vi.fn()
    render(<CertificateCard cert={cert()} onClick={onClick} onEdit={onEdit} />)
    await waitFor(() => expect(indicators()[0]).toHaveAttribute('data-verified', 'true'))
    fireEvent.click(triggerOf(indicators()[0]))
    const dlg = await screen.findByRole('dialog')
    fireEvent.click(within(dlg).getByRole('button', { name: /7\/24 ayarını düzenle|Edit 24\/7 setting/ }))
    expect(onEdit).toHaveBeenCalledTimes(1)
    expect(onClick).not.toHaveBeenCalled()
  })

  it('SSL türü Ayarlar\'da kapalıysa "iletilmiyor"; düzenleyemeyen Kapsam bağlantısı (SSL + alan adı) görür', async () => {
    options({ disabled: ['SSL'] })
    render(<CertificateCard cert={cert()} onClick={() => {}} extra={{}} />)
    await waitFor(() => expect(indicators()[0]).toHaveAttribute('data-reason', 'TYPE_DISABLED'))
    fireEvent.click(triggerOf(indicators()[0]))
    const dlg = await screen.findByRole('dialog')
    fireEvent.click(within(dlg).getByRole('link', { name: /7\/24 Kapsamı’nda gör|View in 24\/7 Coverage/ }))
    expect(nav).toHaveBeenCalledWith('noc', { n_type: 'SSL', n_q: 'www.example.com' })
  })

  it('satır noc_notify taşımıyorsa (eski sunucu yanıtı) gösterge YOK ve istek atılmaz; katman yerinde kalır', async () => {
    const { container } = render(<CertificateCard cert={cert({ noc_notify: undefined })} onClick={() => {}} />)
    await new Promise((res) => setTimeout(res, 0))
    expect(indicators()).toHaveLength(0)
    expect(api.noc.groupOptions).not.toHaveBeenCalled()
    expect(container.querySelector('[data-slot="card-header"]').firstElementChild.querySelector('[data-slot="cert-tier"]')).not.toBeNull()
  })
})
