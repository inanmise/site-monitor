import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, waitFor, fireEvent, within, act } from './test-utils.jsx'
import { pressMenuTrigger } from './helpers/dropdownMenu.js'
import { EN } from '../i18n/index.jsx'
import WarningsPage from '../pages/WarningsPage.jsx'
import { analyze, enrich, groupOf, nextStepOf, reasonsOf, sortItems } from '../pages/warnings/warningsModel.js'
import { attentionCsv } from '../pages/warnings/AttentionParts.jsx'

/**
 * Dikkat Gerektiren Sertifikalar (2026-09-27 shadcn + mobil web yeniden tasarımı): "neden" kutucukları süzgeç (wa_why),
 * aciliyet grupları (Hemen müdahale → Yapılandırma sorunları → Bu ay yenilenecekler), gerekçe çipleri + önerilen sonraki
 * adım + eylemler, Liste | Kartlar görünümü, arama / takım faseti / çipler, sayfalama, boş ve süzgeç-boş durumlar, ağ
 * kesinti bölümünün varsayılan hâli, e-posta hatası atlaması, yükleme hatası, App tazelemesiyle yeniden yükleme.
 */
const { apiMock, navMock } = vi.hoisted(() => ({
  apiMock: { getWarnings: vi.fn(), getNetworkOutageHistory: vi.fn() },
  navMock: vi.fn(),
}))
vi.mock('../api/client', () => ({
  api: apiMock, formatDate: (s) => s ?? '', formatDateOnly: (s) => (s ? String(s).slice(0, 10) : ''), formatDateSec: (s) => s ?? '',
}))
vi.mock('../utils/navigate.js', () => ({ navigateTo: navMock, default: navMock }))

function inDays(n) { const d = new Date(); d.setDate(d.getDate() + n); return d.toISOString().slice(0, 19) }
const w = (domain, days, extra = {}) => ({
  domain, days_remaining: days, status: days == null ? 'error' : 'warning', warning: true, error: null,
  not_after: days == null ? null : inDays(days), checked_at: '2026-09-27T09:00:00', issuer_cn: 'Example CA',
  chain_status: 'VALID', deployment_status: 'OK', revocation_status: 'GOOD', trust_status: 'TRUSTED', security_flags: [], ...extra,
})
// /api/warnings ham satırları — takım/tier TAŞIMAZ (sunucu zenginleştirmiyor)
const WARNINGS = [
  w('later.example.com', 28),
  w('chain.example.com', 20, { chain_status: 'INCOMPLETE' }),
  w('err.example.com', null, { error: 'connect timeout' }),
  w('soon.example.com', 14),
  w('crit.example.com', 3),
  w('weak.example.com', 25, { public_key_algorithm: 'RSA', public_key_size: 1024 }),
  w('gone.example.com', -4),
]
// Pano listesi: takım / tier kaynağı
const CERTS = [
  { domain: 'gone.example.com', team_id: 1, team_name: 'Takım A', tier: 1 },
  { domain: 'crit.example.com', team_id: 1, team_name: 'Takım A', tier: 1 },
  { domain: 'chain.example.com', team_id: 1, team_name: 'Takım A', tier: 2 },
  { domain: 'soon.example.com', team_id: 1, team_name: 'Takım A', tier: 3 },
  { domain: 'err.example.com', team_id: 2, team_name: 'Takım B', tier: 2 },
  { domain: 'weak.example.com', team_id: 2, team_name: 'Takım B', tier: 4 },
]
const EXTRAS = { 'soon.example.com': { renewal: { planned_at: '2026-10-01', note: 'CSR ready', by: 'demo' } } }
const RESOLVED = { id: 1, status: 'RESOLVED', detected_at: '2026-09-20T10:00:00', resolved_at: '2026-09-20T10:20:00', duration_ms: 1_200_000, total_checks: 10, network_errors: 6, error_rate: 0.6, threshold: 0.5 }
const ONGOING = { ...RESOLVED, id: 2, status: 'ONGOING', resolved_at: null, detected_at: '2026-09-27T08:00:00' }

const rowDomains = () => [...document.querySelectorAll('[data-slot="attn-row"]')].map((r) => r.getAttribute('data-domain'))
const rowGroups = () => [...document.querySelectorAll('[data-slot="attn-row"]')].map((r) => r.getAttribute('data-group'))
const rowOf = (domain) => document.querySelector(`[data-slot="attn-row"][data-domain="${domain}"]`)
const tile = (key) => document.querySelector(`[data-slot="stat-item"][data-key="${key}"]`)
const tileValue = (key) => within(tile(key)).getByText(/^\d+$/).textContent
const qs = () => new URLSearchParams(window.location.search)

function props(over = {}) {
  const checkNow = vi.fn()
  return {
    certs: CERTS, cardExtras: EXTRAS,
    silentAlertDomains: new Set(['crit.example.com']), mailFailureDomains: new Set(['err.example.com']),
    weakDomains: new Set(['weak.example.com']),
    onOpenCert: vi.fn(), onOpenHealth: vi.fn(), onPlanRenewal: vi.fn(), onMailFailure: vi.fn(), onCheckAll: vi.fn(),
    cardActions: vi.fn(() => ({ onCheckNow: checkNow, checking: false })),
    checkNow,
    ...over,
  }
}
async function renderLoaded(p = props()) {
  const utils = render(<WarningsPage {...p} />)
  await waitFor(() => expect(document.querySelector('[data-slot="attn-list"]')).not.toBeNull())
  return { ...utils, p }
}

const realWidth = window.innerWidth
beforeEach(() => {
  vi.clearAllMocks()
  window.history.replaceState(null, '', '/?tab=warnings')
  try { localStorage.clear() } catch { /* yoksay */ }
  apiMock.getWarnings.mockResolvedValue({ success: true, data: WARNINGS, timestamp: '2026-09-27T09:05:00' })
  apiMock.getNetworkOutageHistory.mockResolvedValue({ success: true, events: [RESOLVED] })
})
afterEach(() => { window.innerWidth = realWidth })

describe('WarningsPage — başlık, kutucuklar, gruplar', () => {
  it('başlık + meta (toplam, acil, son güncelleme) + saatlik not açıklaması; zenginleştirme takım adını Pano listesinden getirir', async () => {
    await renderLoaded()
    expect(screen.getByRole('heading', { name: EN['app.warningsTitle'] })).toBeInTheDocument()
    expect(document.querySelector('[data-slot="attn-meta-total"]')).toHaveTextContent(EN['attn.metaTotal'].replace('{0}', '7'))
    expect(document.querySelector('[data-slot="attn-meta-now"]')).toHaveTextContent(EN['attn.metaNow'].replace('{0}', '3'))
    expect(screen.getByText(/2026-09-27T09:05:00/)).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: EN['attn.hourlyChip'] }))
    expect(await screen.findByRole('tooltip')).toHaveTextContent(EN['app.sslHourlyNote'])
    expect(within(rowOf('err.example.com')).getByText('Takım B')).toBeInTheDocument()
    expect(within(rowOf('gone.example.com')).getByText('T1')).toBeInTheDocument()
  })

  it('kutucuklar sayar; kutucuk süzgeçtir (aria-pressed, URL wa_why, çip); ikinci basış kaldırır', async () => {
    await renderLoaded()
    expect(tileValue('expired')).toBe('1')
    expect(tileValue('week')).toBe('1')
    expect(tileValue('month')).toBe('4')
    expect(tileValue('chain')).toBe('1')
    expect(tileValue('error')).toBe('1')
    expect(tileValue('weak')).toBe('1')
    expect(tileValue('silent')).toBe('1')
    expect(tileValue('mail')).toBe('1')
    fireEvent.click(tile('chain'))
    expect(tile('chain')).toHaveAttribute('aria-pressed', 'true')
    await waitFor(() => expect(rowDomains()).toEqual(['chain.example.com']))
    await waitFor(() => expect(qs().get('wa_why')).toBe('chain'))
    expect(document.querySelector('[data-slot="attn-chip"][data-chip="why"]')).not.toBeNull()
    fireEvent.click(tile('chain'))
    expect(tile('chain')).toHaveAttribute('aria-pressed', 'false')
    await waitFor(() => expect(rowDomains()).toHaveLength(7))
    await waitFor(() => expect(qs().get('wa_why')).toBeNull())
  })

  it('zayıf-algoritma verisi yoksa kutucuk yok; sessiz alarm / e-posta kutucuğu yalnız sayı varsa', async () => {
    await renderLoaded(props({ weakDomains: null, silentAlertDomains: new Set(), mailFailureDomains: new Set() }))
    expect(tile('weak')).toBeNull()
    expect(tile('silent')).toBeNull()
    expect(tile('mail')).toBeNull()
    expect(tile('expired')).not.toBeNull()
  })

  it('aciliyet grupları sırayla: Hemen müdahale → Yapılandırma sorunları → Bu ay yenilenecekler; grup içi dolmuş → ≤7 → erişilemeyen', async () => {
    await renderLoaded()
    expect(rowDomains()).toEqual([
      'gone.example.com', 'crit.example.com', 'err.example.com',
      'chain.example.com', 'weak.example.com',
      'soon.example.com', 'later.example.com',
    ])
    expect(rowGroups()).toEqual(['now', 'now', 'now', 'config', 'config', 'soon', 'soon'])
    const groups = [...document.querySelectorAll('[data-slot="attn-group"]')]
    expect(groups.map((g) => g.getAttribute('data-group'))).toEqual(['now', 'config', 'soon'])
    expect(groups[0]).toHaveTextContent(EN['attn.group.now'])
    expect(within(groups[0]).getByText('3', { selector: '[data-slot="attn-group-count"]' })).toBeInTheDocument()
  })
})

describe('WarningsPage — gerekçeler, sonraki adım, eylemler', () => {
  it('gerekçe çipleri + sonraki adım metni + birincil eylem (adı alan adını taşır) doğru geri çağrıyı tetikler', async () => {
    const { p } = await renderLoaded()
    const chain = rowOf('chain.example.com')
    const reasons = [...chain.querySelectorAll('[data-slot="attn-reason"]')].map((c) => c.getAttribute('data-reason'))
    expect(reasons).toEqual(['chainIncomplete', 'days'])
    expect(within(chain).getByText(EN['attn.next.fixChain'])).toBeInTheDocument()
    fireEvent.click(within(chain).getByRole('button', { name: `chain.example.com — ${EN['attn.act.health']}` }))
    expect(p.onOpenHealth).toHaveBeenCalledWith('chain.example.com')

    const crit = rowOf('crit.example.com')
    expect(within(crit).getByText(EN['attn.r.days'].replace('{0}', '3'))).toBeInTheDocument()
    expect(within(crit).getByText(EN['attn.r.silent'])).toBeInTheDocument()
    fireEvent.click(within(crit).getByRole('button', { name: `crit.example.com — ${EN['forecast.planRenewal']}` }))
    expect(p.onPlanRenewal).toHaveBeenCalledWith(expect.objectContaining({ domain: 'crit.example.com', team_name: 'Takım A' }), null)

    const err = rowOf('err.example.com')
    expect(within(err).getByText('connect timeout')).toBeInTheDocument()
    fireEvent.click(within(err).getByRole('button', { name: `err.example.com — ${EN['inv.checkNow']}` }))
    expect(p.checkNow).toHaveBeenCalledTimes(1)

    const soon = rowOf('soon.example.com')
    expect(within(soon).getByText(EN['attn.next.followPlan'].replace('{0}', '2026-10-01'))).toBeInTheDocument()
    expect(soon.querySelector('[data-slot="attn-plan"]')).toHaveAttribute('data-state', 'planned')
    expect(within(soon).getByRole('button', { name: `soon.example.com — ${EN['forecast.editPlan']}` })).toBeInTheDocument()
  })

  it('e-posta hatası çipi Sistem Sağlığı atlamasını çağırır; alan adı detayı açar; menü envanterde açar', async () => {
    const { p } = await renderLoaded()
    const err = rowOf('err.example.com')
    fireEvent.click(within(err).getByRole('button', { name: `err.example.com — ${EN['attn.act.mail']}` }))
    expect(p.onMailFailure).toHaveBeenCalledWith('err.example.com')
    fireEvent.click(within(err).getByRole('button', { name: EN['card.openDetailFor'].replace('{0}', 'err.example.com') }))
    expect(p.onOpenCert).toHaveBeenCalledWith('err.example.com')
    pressMenuTrigger(within(err).getByRole('button', { name: `err.example.com — ${EN['tbl.actions']}` }))
    fireEvent.click(await screen.findByRole('menuitem', { name: EN['attn.act.inventory'] }))
    expect(navMock).toHaveBeenCalledWith('domains', { i_q: 'err.example.com' })
  })
})

describe('WarningsPage — görünüm, süzgeçler, sayfalama', () => {
  it('Liste | Kartlar: Kartlar Pano kartını (değişmeden) + gerekçe şeridini çizer; tercih URL wa_view + yerel depoda', async () => {
    await renderLoaded()
    fireEvent.click(screen.getByRole('button', { name: EN['attn.viewCards'] }))
    await waitFor(() => expect(document.querySelectorAll('[data-slot="attn-card-cell"]')).toHaveLength(7))
    expect(document.querySelector('[data-slot="attn-table"]')).toBeNull()
    const cell = document.querySelector('[data-slot="attn-card-cell"][data-domain="crit.example.com"]')
    expect(cell.querySelector('[data-cert-open]')).not.toBeNull()
    expect(cell.querySelector('[data-chip="silent"]')).not.toBeNull()                 // kartın kendi çipi
    expect(cell.querySelector('[data-slot="attn-strip"] [data-reason="silent"]')).toBeNull()   // şeritte tekrarlanmaz
    await waitFor(() => expect(qs().get('wa_view')).toBe('cards'))
    expect(localStorage.getItem('sm.warnings.view')).toBe('cards')
    fireEvent.click(screen.getByRole('button', { name: EN['attn.viewList'] }))
    await waitFor(() => expect(document.querySelector('[data-slot="attn-table"]')).not.toBeNull())
    await waitFor(() => expect(qs().get('wa_view')).toBeNull())
  })

  it('arama + takım faseti + çipler + Filtreleri temizle; URL wa_q / wa_team', async () => {
    await renderLoaded()
    fireEvent.change(screen.getByRole('searchbox'), { target: { value: 'weak' } })
    await waitFor(() => expect(rowDomains()).toEqual(['weak.example.com']))
    await waitFor(() => expect(qs().get('wa_q')).toBe('weak'))
    fireEvent.click(screen.getByRole('button', { name: EN['attn.removeFilter'].replace('{0}', '“weak”') }))
    await waitFor(() => expect(rowDomains()).toHaveLength(7))

    fireEvent.click(screen.getByRole('button', { name: new RegExp(`^${EN['inv.filterTeam']}`) }))
    fireEvent.click(await screen.findByRole('option', { name: /^Takım B/ }))
    await waitFor(() => expect(rowDomains()).toEqual(['err.example.com', 'weak.example.com']))
    await waitFor(() => expect(qs().get('wa_team')).toBe('2'))
    expect(screen.getByRole('button', { name: EN['attn.removeFilter'].replace('{0}', EN['attn.chipTeam'].replace('{0}', 'Takım B')) })).toBeInTheDocument()
    expect(document.querySelector('[data-slot="attn-count"]')).toHaveTextContent(EN['attn.shown'].replace('{0}', '2').replace('{1}', '7'))
    fireEvent.click(screen.getByRole('button', { name: EN['app.clearFilters'] }))
    await waitFor(() => expect(rowDomains()).toHaveLength(7))
  })

  it('süzgeç boş durumu: "eşleşen yok" + Filtreleri temizle listeyi geri getirir', async () => {
    await renderLoaded()
    fireEvent.change(screen.getByRole('searchbox'), { target: { value: 'nothing-matches' } })
    expect(await screen.findByText(EN['attn.noneFiltered'])).toBeInTheDocument()
    // faset düğmeleri kaybolmaz (yerleşim zıplamaz; seçenekler tüm satırlardan, sayılar süzülmüş)
    expect(screen.getByRole('button', { name: new RegExp(`^${EN['inv.filterTeam']}`) })).toBeInTheDocument()
    const empty = document.querySelector('[data-slot="attn-list"] [data-slot="empty"]')
    fireEvent.click(within(empty).getByRole('button', { name: EN['app.clearFilters'] }))
    await waitFor(() => expect(rowDomains()).toHaveLength(7))
  })

  it('derin bağlantı: ?wa_why=month&wa_sort=domain süzgeci ve sıralamayı açar', async () => {
    window.history.replaceState(null, '', '/?tab=warnings&wa_why=month&wa_sort=domain')
    await renderLoaded()
    expect(tile('month')).toHaveAttribute('aria-pressed', 'true')
    // 8–30 gün: chain(20), later(28), soon(14), weak(25) — gruplar korunur, grup içinde alfabetik
    expect(rowDomains()).toEqual(['chain.example.com', 'weak.example.com', 'later.example.com', 'soon.example.com'])
    expect(document.querySelector('[data-slot="attn-sort"]')).toHaveTextContent(EN['attn.sort.domain'])
  })

  it('standart sayfalama: 60 satırda 50 + sayfa çubuğu; wa_page=2 kalan 10 satırı açar', async () => {
    const many = Array.from({ length: 60 }, (_, i) => w(`h${String(i).padStart(2, '0')}.example.com`, 20))
    apiMock.getWarnings.mockResolvedValue({ success: true, data: many })
    window.history.replaceState(null, '', '/?tab=warnings&wa_page=2')
    await renderLoaded(props({ certs: [] }))
    await waitFor(() => expect(rowDomains()).toHaveLength(10))
    expect(rowDomains()[0]).toBe('h50.example.com')
    expect(screen.getByRole('navigation')).toBeInTheDocument()
  })

  it('telefon (390 px): liste dikkat kartlarıyla çizilir — sonraki adım kutusu + 40 px iki düğme', async () => {
    window.innerWidth = 390
    render(<WarningsPage {...props({ weakDomains: new Set() })} />)
    await waitFor(() => expect(document.querySelectorAll('[data-slot="attn-card"]')).toHaveLength(7))
    expect(document.querySelector('[data-slot="attn-table"]')).toBeNull()
    const card = document.querySelector('[data-slot="attn-card"][data-domain="gone.example.com"]')
    expect(within(card).getByText(EN['attn.next.renewNow'])).toBeInTheDocument()
    expect(within(card).getByRole('button', { name: `gone.example.com — ${EN['renewal.openCert']}` })).toBeInTheDocument()
    // Telefonda sıfır sayılı kutucuk gösterilmez (masaüstünde "Zayıf algoritma 0" görünürdü); sayılı olanlar kalır
    expect(tile('weak')).toBeNull()
    expect(tile('expired')).not.toBeNull()
  })

  it('masaüstünde zayıf-algoritma verisi varken sıfır sayı da gösterilir (bilinen sıfır ≠ bilinmiyor)', async () => {
    await renderLoaded(props({ weakDomains: new Set() }))
    expect(tileValue('weak')).toBe('0')
  })
})

describe('WarningsPage — durumlar ve tazeleme', () => {
  it('uyarı yoksa "her şey yolunda" (başarı tonu); kutucuk/araç çubuğu yok; kesinti bölümü kapalı', async () => {
    apiMock.getWarnings.mockResolvedValue({ success: true, data: [] })
    render(<WarningsPage {...props()} />)
    const ok = await screen.findByText(EN['app.noWarnings'])
    expect(ok.closest('[data-slot="empty"]')).toHaveAttribute('data-tone', 'success')
    expect(document.querySelector('[data-slot="stats-panel"]')).toBeNull()
    expect(document.querySelector('[data-slot="attn-toolbar"]')).toBeNull()
    expect(document.querySelector('[data-slot="attn-meta-total"]')).toHaveTextContent(EN['attn.metaClear'])
    const toggle = await screen.findByRole('button', { name: EN['app.networkOutageHistoryTitle'] })
    expect(toggle).toHaveAttribute('aria-expanded', 'false')
  })

  it('SÜREN ağ kesintisi varsa geçmiş bölümü açık başlar ve kartlar görünür', async () => {
    apiMock.getNetworkOutageHistory.mockResolvedValue({ success: true, events: [ONGOING, RESOLVED] })
    await renderLoaded()
    const toggle = await screen.findByRole('button', { name: EN['app.networkOutageHistoryTitle'] })
    await waitFor(() => expect(toggle).toHaveAttribute('aria-expanded', 'true'))
    expect(document.querySelector('[data-slot="net-outage-history"] [data-status="ongoing"]')).not.toBeNull()
    fireEvent.click(toggle)
    await waitFor(() => expect(toggle).toHaveAttribute('aria-expanded', 'false'))
  })

  it('yükleme sırasında iskelet (yalancı "uyarı yok" YOK); hata → role=alert + yeniden dene', async () => {
    let resolve
    apiMock.getWarnings.mockReturnValueOnce(new Promise((r) => { resolve = r }))
    render(<WarningsPage {...props()} />)
    expect(document.querySelector('[data-slot="attn-skeleton"]')).not.toBeNull()
    expect(screen.queryByText(EN['app.noWarnings'])).toBeNull()
    await act(async () => { resolve({ success: false, error: 'boom' }) })
    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent('boom')
    fireEvent.click(within(alert).getByRole('button', { name: EN['forecast.retry'] }))
    await waitFor(() => expect(document.querySelectorAll('[data-slot="attn-row"]')).toHaveLength(7))
    expect(screen.queryByRole('alert')).toBeNull()
  })

  it('App tazelemesi (refreshKey değişimi) listeyi yeniden yükler; başlıktaki "Şimdi Kontrol Et" App akışını çağırır', async () => {
    const p = props({ refreshKey: 't1' })
    const { rerender } = render(<WarningsPage {...p} />)
    await waitFor(() => expect(apiMock.getWarnings).toHaveBeenCalledTimes(1))
    rerender(<WarningsPage {...p} refreshKey="t2" />)
    await waitFor(() => expect(apiMock.getWarnings).toHaveBeenCalledTimes(2))
    fireEvent.click(screen.getByRole('button', { name: EN['app.checkNow'] }))
    expect(p.onCheckAll).toHaveBeenCalledTimes(1)
  })

  it('CSV: süzülmüş TÜM satırlar, gerekçe + sonraki adım metinleriyle; formül nötrlenir', async () => {
    const created = []
    const origCreate = URL.createObjectURL, origRevoke = URL.revokeObjectURL
    URL.createObjectURL = vi.fn((b) => { created.push(b); return 'blob:x' })
    URL.revokeObjectURL = vi.fn()
    try {
      await renderLoaded()
      fireEvent.click(screen.getByRole('button', { name: EN['attn.exportCsv'] }))
      expect(created).toHaveLength(1)
    } finally { URL.createObjectURL = origCreate; URL.revokeObjectURL = origRevoke }
    const t = (k, ...a) => a.reduce((s, v, i) => s.replace(`{${i}}`, v), EN[k] ?? k)
    const ctx = { weak: null, silent: null, mail: null, plans: {} }
    const text = attentionCsv([analyze({ domain: '=cmd.example.com', days_remaining: 2, status: 'warning' }, ctx)], t)
    expect(text.startsWith('﻿' + EN['attn.csv.domain'])).toBe(true)
    expect(text).toContain("'=cmd.example.com")
    expect(text).toContain(EN['attn.group.now'])
    expect(text).toContain(EN['attn.next.renewNow'])
  })
})

describe('warningsModel — saf kurallar', () => {
  const ctx = { weak: new Set(['w.example.com']), silent: new Set(), mail: new Set(), plans: {} }
  it('grup: tarayıcı reddi (güvenilmeyen CA / ad uyuşmazlığı / iptal) "Hemen", dağıtım + ara sertifika "Yapılandırma"', () => {
    expect(groupOf({ domain: 'a', days_remaining: 20, security_flags: ['HOSTNAME_MISMATCH'] }, ctx)).toBe('now')
    expect(groupOf({ domain: 'a', days_remaining: 20, revocation_status: 'REVOKED' }, ctx)).toBe('now')
    expect(groupOf({ domain: 'a', days_remaining: 20, deployment_status: 'INCOMPLETE' }, ctx)).toBe('config')
    expect(groupOf({ domain: 'a', days_remaining: 20, intermediate_days_remaining: 10 }, ctx)).toBe('config')
    expect(groupOf({ domain: 'w.example.com', days_remaining: 20 }, ctx)).toBe('config')
    expect(groupOf({ domain: 'a', days_remaining: 20 }, ctx)).toBe('soon')
  })
  it('sonraki adım önceliği: erişilemeyen > iptal > dolmuş; planlı ve gecikmiş plan', () => {
    expect(nextStepOf({ domain: 'a', status: 'error', revocation_status: 'REVOKED' }, ctx).key).toBe('checkEndpoint')
    expect(nextStepOf({ domain: 'a', days_remaining: -2, revocation_status: 'REVOKED' }, ctx).key).toBe('replaceRevoked')
    const plans = { a: { renewal: { planned_at: '2026-09-01', overdue: true } } }
    expect(nextStepOf({ domain: 'a', days_remaining: 3 }, { ...ctx, plans })).toEqual({ key: 'planOverdue', action: 'plan', date: '2026-09-01' })
  })
  it('gerekçe metin anahtarları: bugün / yarın / 1 gün önce', () => {
    expect(reasonsOf({ domain: 'a', days_remaining: 0 }, ctx)[0].key).toBe('today')
    expect(reasonsOf({ domain: 'a', days_remaining: 1 }, ctx)[0].key).toBe('days1')
    expect(reasonsOf({ domain: 'a', days_remaining: -1 }, ctx)[0].key).toBe('expired1')
  })
  it('zenginleştirme uyarı satırının kendi değerini ezmez; sıralama grupları korur', () => {
    const [r] = enrich([{ domain: 'a', tier: 3 }], [{ domain: 'a', tier: 1, team_name: 'Takım A' }])
    expect(r).toMatchObject({ tier: 3, team_name: 'Takım A' })
    const items = [{ domain: 'z', days_remaining: 3 }, { domain: 'b', days_remaining: 20 }, { domain: 'a', days_remaining: 5 }].map((x) => analyze(x, ctx))
    expect(sortItems(items, 'domain').map((i) => i.row.domain)).toEqual(['a', 'z', 'b'])
  })
})
