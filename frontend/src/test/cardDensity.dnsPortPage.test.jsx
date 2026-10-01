import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, fireEvent, waitFor, within } from './test-utils.jsx'
import DnsMonitorPage from '../components/DnsMonitorPage.jsx'
import PortMonitorPage from '../components/PortMonitorPage.jsx'
import PageMonitorPage from '../components/PageMonitorPage.jsx'

const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))

vi.mock('../api/client', () => ({
  formatDate: (s) => s ?? '',
  formatDateSec: (s) => s ?? '',
  formatDateOnly: (s) => s ?? '',
  api: withApiFallback({ monitoring: {}, admin: {} }),
}))
import { api } from '../api/client'

/**
 * KART YOĞUNLUĞU — DNS / Port / Sayfa Bütünlüğü sayfaları (2026-09-27). Sayfa sözleşmesi:
 *   - seçici (ui/CardDensityToggle) araç çubuğunun İLK öğesi, `mr-auto` (süzgeçler sağda);
 *   - sayfa HER AÇILIŞTA Zengin; Kompakt seçimi yalnız sayfada kalındıkça geçerli, SAKLANMAZ (kullanıcı kararı);
 *   - ızgara `data-density` taşır, kartlar aynı yoğunlukta çizilir, Zengin parçalar Kompakt'ta DOM'a girmez;
 *   - Kompakt'ta davranış aynı: toplu seçim, başlıktan detay, eylemler (türev satırda "izlemeyi durdur" adı);
 *   - DNS / Port iki kaynaklı: kaynak rozeti (bağımsız / envanterden) İKİ görünümde de.
 */
const row = (id, extra) => ({
  id, name: null, team_id: 5, team_name: 'Takım A', group_name: 'Grup', tags: 'prod', active: true,
  checked_at: '2026-09-27T09:00:00', ...extra,
})

const PAGES = [
  ['Dns', DnsMonitorPage, 'getDnsMonitors', [
    row(1, { domain: 'a.example.com', record_type: 'A', value: '203.0.113.10', standalone: true }),
    row(2, { domain: 'b.example.com', record_type: 'A', value: '203.0.113.11', standalone: false }),
  ], (m) => m.domain, 'dns-source', 'dns-compact'],
  ['Port', PortMonitorPage, 'getPortMonitors', [
    row(1, { host: 'a.example.com', port: 443, protocol: 'TCP', status: 'open', response_ms: 12, standalone: true }),
    row(2, { host: 'b.example.com', port: 5432, protocol: 'TCP', status: 'closed', response_ms: null, error: 'Connection refused', standalone: null }),
  ], (m) => `${m.host}:${m.port}`, 'port-source', 'port-compact'],
  ['Page', PageMonitorPage, 'getPageMonitors', [
    row(1, { url: 'https://a.example.com/', mode: 'SINGLE_PAGE', status: 'OK', http_status: 200, total_resources: 10, broken_resources: 0 }),
    row(2, { url: 'https://b.example.com/', mode: 'SINGLE_PAGE', status: 'DOWN', http_status: 503, total_resources: 0, error: 'ana sayfa HTTP 503' }),
  ], (m) => m.url, null, 'page-compact'],
]

const esc = (s) => s.replace(/[.*+?^${}()|[\]\\/]/g, '\\$&')
const densityKeys = () => {
  const out = []
  for (let i = 0; i < localStorage.length; i++) { const k = localStorage.key(i); if (/cardMode/.test(k)) out.push(k) }
  return out
}

describe.each(PAGES)('%s izleme sayfası — Kompakt / Zengin', (name, Page, listFn, rows, labelOf, sourceSlot, compactSlot) => {
  beforeEach(() => {
    vi.clearAllMocks()
    localStorage.clear()
    api.monitoring[listFn].mockResolvedValue({ success: true, data: rows })
  })
  afterEach(() => { localStorage.clear() })

  const mount = () => render(<Page systemRole="ADMIN" teamId={5} teamName="Takım A" myTeams={[{ id: 5, name: 'Takım A' }]} />)
  const grid = (c) => c.querySelector('.upt-grid')
  const cards = (c) => [...c.querySelectorAll('.upt-grid > [data-slot="card"]')]
  // Kart sırası varsayılan kuraldan gelir (sorunlu → grup → ad, 2026-10-01) — kart etiketle bulunur, konumla değil
  const cardOf = (c, lbl) => cards(c).find((el) => el.querySelector(`[aria-label^="${lbl} — "]`))
  const toggle = () => document.querySelector('[data-slot="card-density-toggle"]')
  const pick = (re) => fireEvent.click(within(toggle()).getByRole('radio', { name: re }))

  it('açılışta Zengin; seçici araç çubuğunun İLK öğesi (mr-auto); Kompakt\'a geçince ızgara + kartlar Kompakt, Zengin parçalar DOM\'dan çıkar; geri dönülebilir', async () => {
    const { container } = mount()
    await waitFor(() => expect(cards(container)).toHaveLength(2))
    const tg = toggle()
    expect(tg.parentElement.firstElementChild).toBe(tg)
    expect(tg.className).toMatch(/(^|\s)mr-auto(\s|$)/)
    expect(within(tg).getByRole('radio', { name: /^(Rich|Zengin)$/ })).toHaveAttribute('data-state', 'on')
    expect(grid(container)).toHaveAttribute('data-density', 'rich')
    for (const c of cards(container)) expect(c).toHaveAttribute('data-density', 'rich')
    expect(container.querySelector('[data-slot="monitor-card-rich"]')).not.toBeNull()
    expect(container.querySelector(`[data-slot="${compactSlot}"]`)).toBeNull()

    pick(/^(Compact|Kompakt)$/)
    expect(within(toggle()).getByRole('radio', { name: /^(Compact|Kompakt)$/ })).toHaveAttribute('data-state', 'on')
    expect(grid(container)).toHaveAttribute('data-density', 'compact')
    for (const c of cards(container)) {
      expect(c).toHaveAttribute('data-density', 'compact')
      expect(c.querySelector(`[data-slot="${compactSlot}"]`)).not.toBeNull()
    }
    expect(container.querySelector('[data-slot="monitor-card-rich"]')).toBeNull()

    pick(/^(Rich|Zengin)$/)
    expect(grid(container)).toHaveAttribute('data-density', 'rich')
    expect(container.querySelector('[data-slot="monitor-card-rich"]')).not.toBeNull()
  })

  it('Kompakt SAKLANMAZ: sayfadan çıkıp yeniden açınca Zengin; tarayıcı depolamasına yoğunluk yazılmaz', async () => {
    const first = mount()
    await waitFor(() => expect(cards(first.container)).toHaveLength(2))
    pick(/^(Compact|Kompakt)$/)
    expect(grid(first.container)).toHaveAttribute('data-density', 'compact')
    expect(densityKeys()).toEqual([])
    first.unmount()

    const again = mount()
    await waitFor(() => expect(cards(again.container)).toHaveLength(2))
    expect(grid(again.container)).toHaveAttribute('data-density', 'rich')
    expect(within(toggle()).getByRole('radio', { name: /^(Rich|Zengin)$/ })).toHaveAttribute('data-state', 'on')
  })

  it('Kompakt\'ta davranış aynı: toplu seçim kutusu seçer, başlık detayı açar, eylemler satır adını taşır', async () => {
    const { container } = mount()
    await waitFor(() => expect(cards(container)).toHaveLength(2))
    pick(/^(Compact|Kompakt)$/)
    const label = labelOf(rows[0])
    const c1 = cardOf(container, label)
    fireEvent.click(within(c1).getByRole('checkbox', { name: new RegExp(esc(label)) }))
    await waitFor(() => expect(container.querySelector('[data-slot="bulk-action-bar"]')).not.toBeNull())
    expect(container.querySelector('[data-slot="bulk-action-bar"]').textContent).toMatch(/1/)
    expect(within(c1).getByRole('checkbox', { name: new RegExp(esc(label)) })).toHaveAttribute('data-state', 'checked')
    for (const act of [/(Check now|Şimdi kontrol et|Kontrol et|Check)/, /(Edit|Düzenle)/]) {
      expect(within(c1).getAllByRole('button', { name: new RegExp(`^${esc(label)} — ${act.source}`) }).length).toBeGreaterThan(0)
    }
    fireEvent.click(within(c1).getByRole('button', { name: new RegExp(`^${esc(label)} — (open details|detayları aç)$`) }))
    expect(await screen.findByRole('dialog')).toBeInTheDocument()
  })

  if (sourceSlot) {
    it('iki kaynaklı: kaynak rozeti İKİ görünümde de; türev satırda silme adı "izlemeyi durdur" Kompakt\'ta da', async () => {
      const { container } = mount()
      await waitFor(() => expect(cards(container)).toHaveLength(2))
      const sources = () => [labelOf(rows[0]), labelOf(rows[1])]
        .map((l) => cardOf(container, l)?.querySelector(`[data-slot="${sourceSlot}"]`)?.getAttribute('data-source'))
      expect(sources()).toEqual(['standalone', 'inventory'])
      pick(/^(Compact|Kompakt)$/)
      expect(sources()).toEqual(['standalone', 'inventory'])
      const derived = cardOf(container, labelOf(rows[1]))
      expect(within(derived).getByRole('button', {
        name: new RegExp(`^${esc(labelOf(rows[1]))} — (Stop monitoring \\(inventory-derived record is kept\\)|İzlemeyi durdur \\(envanter-türevi kayıt silinmez\\))$`),
      })).toBeInTheDocument()
    })
  }
})

// 2026-09-27 (silme ≠ duraklatma, deleted_at): bağımsız Port izlemesi KALICI silinir; envanterden türeyen satır yalnız
// DURDURULUR ve listede kalır. Onay metni bunu doğru söylemeli (DNS ikiziyle aynı ayrım) — eskiden türev satıra da
// "kalıcı olarak silinecek" deniyordu.
describe('Port — silme onayı kaynağa göre', () => {
  const rows = PAGES.find(([n]) => n === 'Port')[3]
  beforeEach(() => {
    vi.clearAllMocks()
    api.monitoring.getPortMonitors.mockResolvedValue({ success: true, data: rows })
    api.monitoring.deletePortMonitor.mockResolvedValue({ success: true })
  })
  const mount = () => render(<PortMonitorPage systemRole="ADMIN" teamId={5} teamName="Takım A" myTeams={[{ id: 5, name: 'Takım A' }]} />)
  const cardsOf = (c) => [...c.querySelectorAll('.upt-grid > [data-slot="card"]')]

  it('türev satır: "izlemeyi durdur" metni ve düğmesi; bağımsız satır: kalıcı silme metni', async () => {
    const { container } = mount()
    await waitFor(() => expect(cardsOf(container)).toHaveLength(2))

    const portCard = (lbl) => cardsOf(container).find((el) => el.querySelector(`[aria-label^="${lbl} — "]`))   // sıra kuraldan
    fireEvent.click(within(portCard('b.example.com:5432')).getByRole('button', { name: /b\.example\.com:5432 — (Stop monitoring|İzlemeyi durdur)/ }))
    let dlg = await screen.findByRole('dialog')
    expect(dlg).toHaveTextContent(/inventory record|envanter kaydından/)
    expect(dlg).not.toHaveTextContent(/for good|kalıcı olarak/)
    expect(within(dlg).getAllByRole('button').at(-1)).toHaveTextContent(/^(Stop monitoring|İzlemeyi durdur)$/)
    fireEvent.click(within(dlg).getAllByRole('button').at(-1))
    await waitFor(() => expect(api.monitoring.deletePortMonitor).toHaveBeenCalledWith(2))

    fireEvent.click(within(portCard('a.example.com:443')).getByRole('button', { name: /a\.example\.com:443 — (Delete|Sil)/ }))
    dlg = await screen.findByRole('dialog')
    expect(dlg).toHaveTextContent(/for good|kalıcı olarak/)
  })
})
