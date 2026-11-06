import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor, within } from './test-utils.jsx'

/**
 * İzleme sayfaları — formda yazmak kart ızgarasını ÇİZMEZ (2026-11-06, performans PF4 O2).
 *
 * Form state'i sayfa bileşeninde tutuluyor; eskiden formdaki her tuş sayfayı ve 25–100 kartın HEPSİNİ yeniden
 * çiziyordu (rozet, mini trend, eylem çubuğu). Izgara artık `useMemo` ile yalnız kendi verisi değişince çizilir;
 * olay işleyicileri `useCardHandlers` referansından okunur (bayat kapanış yok).
 *
 * Ölçüm: her kartın eylem çubuğu (`MonitorCardActions`) çizim sayacı. Karşı kanıt: karttaki seçim kutusu
 * işaretlenince (ızgara verisi değişti) kartlar yeniden çizilir ve kutu işaretli görünür; formu kapatıp karttan
 * "Düzenle" açmak O ANKİ işleyiciyle çalışır.
 */
const renders = vi.hoisted(() => ({ n: 0 }))
vi.mock('../components/MonitorCardActions.jsx', async (importOriginal) => {
  const m = await importOriginal()
  const Real = m.default
  return { ...m, default: (props) => { renders.n++; return <Real {...props} /> } }
})
const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
vi.mock('../api/client', () => ({
  formatDate: (s) => s ?? '',
  formatDateSec: (s) => s ?? '',
  formatDateOnly: (s) => s ?? '',
  api: withApiFallback({ monitoring: {}, admin: {} }),
}))
import { api } from '../api/client'
import HttpMonitorPage from '../components/HttpMonitorPage.jsx'
import KeywordMonitorPage from '../components/KeywordMonitorPage.jsx'
import PageMonitorPage from '../components/PageMonitorPage.jsx'
import PageSpeedMonitorPage from '../components/PageSpeedMonitorPage.jsx'
import DomainMonitorPage from '../components/DomainMonitorPage.jsx'
import PingMonitorPage from '../components/PingMonitorPage.jsx'
import PortMonitorPage from '../components/PortMonitorPage.jsx'
import DnsMonitorPage from '../components/DnsMonitorPage.jsx'
import DbQueryMonitorPage from '../components/DbQueryMonitorPage.jsx'

const PAGES = [
  ['Http', HttpMonitorPage, 'getHttpMonitors', (i) => ({ url: `https://h${i}.example.com/`, method: 'GET', status: 'up' })],
  ['Keyword', KeywordMonitorPage, 'getKeywordMonitors', (i) => ({ url: `https://k${i}.example.com/`, keyword: 'Giriş', operator: 'GTE', match_count: 1, status: 'up' })],
  ['Page', PageMonitorPage, 'getPageMonitors', (i) => ({ url: `https://p${i}.example.com/`, mode: 'SINGLE', status: 'OK' })],
  ['PageSpeed', PageSpeedMonitorPage, 'getPageSpeedMonitors', (i) => ({ url: `https://s${i}.example.com/`, status: 'OK', breached_metrics: [] })],
  ['Domain', DomainMonitorPage, 'getDomainMonitors', (i) => ({ domain: `d${i}.example.com`, status: 'OK', days_remaining: 90 })],
  ['Ping', PingMonitorPage, 'getPingMonitors', (i) => ({ host: `g${i}.example.com`, status: 'up', ip_version: 'V4', packet_count: 4 })],
  ['Port', PortMonitorPage, 'getPortMonitors', (i) => ({ host: `t${i}.example.com`, port: 443, protocol: 'TCP', status: 'open', standalone: true })],
  ['Dns', DnsMonitorPage, 'getDnsMonitors', (i) => ({ domain: `n${i}.example.com`, record_type: 'A', value: '203.0.113.10', standalone: true })],
  ['DbQuery', DbQueryMonitorPage, 'getDbQueryMonitors', () => ({ purpose: 'Bekleyen sipariş sayısını izler', profile_id: 3, profile: { id: 3, name: 'Sipariş DB', db_type: 'POSTGRESQL', host: 'db.example.com', port: 5432, database_name: 'orders' }, sql_text: 'SELECT count(*) FROM orders', rules: [], status: 'OK', breach_level: 'NONE', last_check: '2026-09-26T09:00:00' })],
]
const N = 12
const ADD = /^(Yeni Monitör|Yeni İzleme|İzleme Ekle|New Monitor|Add Monitor)$/
const EDIT = /— (Düzenle|Edit)$/

const cards = (container) => [...container.querySelectorAll('.upt-grid > [data-slot="card"]')]

describe.each(PAGES)('%s izleme sayfası — form yazımı kart ızgarasını çizmez', (name, Page, listFn, fieldsOf) => {
  beforeEach(() => {
    vi.clearAllMocks()
    renders.n = 0
    window.history.replaceState(null, '', '/')
    const rows = Array.from({ length: N }, (_, k) => ({
      id: k + 1, name: `izleme-${String(k + 1).padStart(2, '0')}`, team_id: 5, team_name: 'Takım A', group_name: 'Grup', tags: 'prod',
      active: true, checked_at: '2026-09-26T09:00:00', can_edit: true, can_check: true, can_delete: true, ...fieldsOf(k + 1),
    }))
    api.monitoring[listFn].mockResolvedValue({ success: true, data: rows })
  })

  it('formdaki tuşlar kartları yeniden çizmez; seçim değişince çizer; karttan Düzenle güncel işleyiciyle açılır', async () => {
    const { container } = render(<Page systemRole="ADMIN" teamId={5} teamName="Takım A" myTeams={[{ id: 5, name: 'Takım A' }]} />)
    await waitFor(() => expect(cards(container)).toHaveLength(N))
    await waitFor(() => expect(renders.n).toBeGreaterThanOrEqual(N))

    fireEvent.click(screen.getByRole('button', { name: ADD }))
    const dlg = await screen.findByRole('dialog')
    const input = within(dlg).getAllByRole('textbox').find((el) => !el.disabled && !el.readOnly)
    expect(input).toBeTruthy()
    // Diyalog açılışının kendi etkileri (takım grupları, etiketler …) otursun
    await new Promise((r) => setTimeout(r, 50))
    const before = renders.n
    for (const v of ['a', 'ab', 'abc', 'abcd', 'abcde']) fireEvent.change(input, { target: { value: v } })
    await waitFor(() => expect(input).toHaveValue('abcde'))
    expect(renders.n - before).toBe(0)

    // Formu kapat (değişiklik var → onay sorulabilir); kapatma yolu sayfanın kendi düğmesi
    fireEvent.click(within(dlg).getByRole('button', { name: /^(Vazgeç|İptal|Cancel)$/ }))
    let confirmed = false
    await waitFor(() => {
      // "Değişiklikler atılsın mı?" onayı (ui/Dialog, role=dialog) asenkron açılır — son düğme onaydır
      const extra = screen.queryAllByRole('dialog').find((d) => d !== dlg && !dlg.contains(d))
      if (extra && !confirmed) { confirmed = true; fireEvent.click(within(extra).getAllByRole('button').at(-1)) }
      expect(screen.queryAllByRole('dialog')).toHaveLength(0)
    })

    // Karşı kanıt 1: seçim kutusu ızgara verisi → kartlar çizilir ve kutu işaretlenir
    const first = cards(container)[0]
    const box = within(first).getByRole('checkbox')
    const beforeSel = renders.n
    fireEvent.click(box)
    await waitFor(() => expect(within(cards(container)[0]).getByRole('checkbox')).toHaveAttribute('data-state', 'checked'))
    expect(renders.n).toBeGreaterThan(beforeSel)

    // Karşı kanıt 2: karttaki "Düzenle" — ızgara form kapandıktan sonra yeniden çizilmedi; işleyici güncel olmalı
    fireEvent.click(within(cards(container)[1]).getByRole('button', { name: EDIT }))
    const edit = await screen.findByRole('dialog')
    expect(within(edit).queryAllByRole('textbox').some((el) => /izleme-02|example\.com/.test(el.value))).toBe(true)
  }, 20_000)
})
