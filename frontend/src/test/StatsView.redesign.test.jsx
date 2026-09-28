import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, fireEvent, waitFor, within, act } from './test-utils.jsx'
import userEvent from '@testing-library/user-event'
import { TR, EN } from '../i18n/index.jsx'
import { pressMenuTrigger } from './helpers/dropdownMenu.js'

const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
const mobile = vi.hoisted(() => ({ value: false }))
vi.mock('../hooks/use-mobile.js', () => ({ useIsMobile: () => mobile.value }))
vi.mock('../api/client', async (orig) => {
  const real = await orig()
  return { ...real, api: withApiFallback({ getExecutiveStats: vi.fn() }) }
})
import { api } from '../api/client'
import StatsView from '../components/StatsView.jsx'
import {
  bucketOf, distribution, kpiCounts, matchesKpi, matrixRows, buildTeams, statsCsv, stateFromUrl, toUrlMapping, upcoming,
} from '../components/stats/statsModel.js'

/**
 * İstatistikler yeniden tasarımı (2026-09-28): KPI → tablo süzgeci, matris hücresi → süzgeç, çipler, gecikmeli arama,
 * sıralama, sayfalama, URL eşitleme (`st_*`), boş / sonuç yok / yükleniyor durumları, telefon kartları.
 *
 * Tarihler ŞİMDİ'den türetilir (kalan gün sunucunun hesapladığı sayı; `not_after`/`checked_at` yalnız gösterim) —
 * sabit tarih kayan pencerede zaman bombası olurdu. Tel biçimi gerçek: snake_case (`days_remaining`, `alert_level`…).
 */
const DAY = 86_400_000
const iso = (ms) => new Date(Date.now() + ms).toISOString().slice(0, 19)
const esc = (s) => String(s).replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
const fmt = (s, args) => args.reduce((acc, a, i) => acc.split(`{${i}}`).join(String(a)), s)
/** Anahtarın TR ya da EN metnine TAM eşleşen düzenli ifade (test dilden bağımsız). */
const both = (key, ...args) => new RegExp(`^(${esc(fmt(TR[key], args))}|${esc(fmt(EN[key], args))})$`)
const tierLbl = (n) => [`T${n} — ${TR[`tier.desc${n}`]}`, `T${n} — ${EN[`tier.desc${n}`]}`]
/** Matris hücresinin adı — takım · katman · seviye · sayı (TR ya da EN). */
const cellName = (team, tier, level, n) => {
  const tr = fmt(TR['stv.mxCell'], [team, tier ? tierLbl(tier)[0] : TR['stv.mxAllTiers'], TR[`ts.${level}`], n])
  const en = fmt(EN['stv.mxCell'], [team, tier ? tierLbl(tier)[1] : EN['stv.mxAllTiers'], EN[`ts.${level}`], n])
  return new RegExp(`^(${esc(tr)}|${esc(en)})$`)
}

function level(days, status) {
  if (status === 'error') return 'error'
  if (days == null) return 'valid'
  if (days < 0) return 'expired'
  if (days <= 7) return 'critical'
  if (days <= 15) return 'high'
  if (days <= 30) return 'warning'
  return 'valid'
}
function cert(domain, days, extra = {}) {
  const status = extra.status ?? 'valid'
  return {
    domain, days_remaining: days, not_after: days == null ? null : iso(days * DAY), checked_at: iso(-5 * 60_000),
    status, warning: days != null && days <= 30, alert_level: level(days, status), issuer_cn: 'R3', tier: 1,
    team_id: 1, team_name: 'Takım A', ...extra,
  }
}
const DIGI = 'DigiCert TLS RSA SHA256 2020 CA1'
const CERTS = [
  cert('ok-a.example.com', 120),
  cert('ok-b.example.com', 90, { tier: 2, team_id: 2, team_name: 'Takım B', issuer_cn: DIGI }),
  cert('soon30.example.com', 25),
  cert('soon14.example.com', 12, { tier: 2, issuer_cn: DIGI }),
  cert('soon7.example.com', 3),
  cert('gone.example.com', -4, { tier: 3, team_id: 2, team_name: 'Takım B' }),
  cert('down.example.com', null, { status: 'error', error: 'Connection reset', team_id: 2, team_name: 'Takım B' }),
]
/** /api/stats/teams — all_teams biçimi (sy_stats/ug_stats altında seviye bazlı alan listeleri). */
const TEAM_STATS = {
  mode: 'all_teams',
  teams: [
    { team_id: 1, team_name: 'Takım A',
      sy_stats: { valid_domains: ['ok-a.example.com'], warning_domains: ['soon30.example.com'], high_domains: ['soon14.example.com'], critical_domains: ['soon7.example.com'] },
      ug_stats: { expired_domains: ['gone.example.com'] } },
    { team_id: 2, team_name: 'Takım B',
      sy_stats: { valid_domains: ['ok-b.example.com'], expired_domains: ['gone.example.com'], error_domains: ['down.example.com'] },
      ug_stats: {} },
  ],
}

/** Çip kaldırma düğmesinin adı — etiket de dile göre çevrilir. */
const removeChip = (labelKey) => new RegExp(`^(${esc(fmt(TR['stv.removeFilter'], [TR[labelKey]]))}|${esc(fmt(EN['stv.removeFilter'], [EN[labelKey]]))})$`)
const rows = () => [...document.querySelectorAll('[data-slot="stats-row"]')].map((r) => r.getAttribute('data-domain'))
const tile = (key) => document.querySelector(`[data-slot="stat-item"][data-key="${key}"]`)
const chips = () => [...document.querySelectorAll('[data-slot="stats-chip"]')].map((c) => c.getAttribute('data-chip'))
const openMatrix = () => fireEvent.click(document.querySelector('[data-slot="stats-toggle"]'))

function renderView(props = {}) {
  return render(<StatsView certs={CERTS} teamStats={TEAM_STATS} onRowClick={vi.fn()} {...props} />)
}

beforeEach(() => {
  mobile.value = false
  window.history.replaceState(null, '', '/?tab=stats')
  api.getExecutiveStats.mockResolvedValue({ success: false })
})
afterEach(() => { window.history.replaceState(null, '', '/') })

describe('statsModel', () => {
  it('kovalar ve KÜMÜLATİF KPI pencereleri (30 gün 14 ve 7 günlükleri kapsar)', () => {
    expect(CERTS.map(bucketOf)).toEqual(['ok', 'ok', 'd30', 'd14', 'd7', 'expired', 'error'])
    expect(CERTS.filter((c) => matchesKpi(c, 'd30')).map((c) => c.domain)).toEqual(['soon30.example.com', 'soon14.example.com', 'soon7.example.com'])
    expect(kpiCounts(CERTS)).toMatchObject({ total: 7, valid: 2, d30: 3, d14: 2, d7: 1, expired: 1, error: 1, avgDays: Math.round((120 + 90 + 25 + 12 + 3) / 5) })
  })

  it('dağılım yalnız süresi bilinenler; hata ayrı sayılır', () => {
    const d = distribution(CERTS)
    expect(d.known).toBe(6)
    expect(d.error).toBe(1)
    expect(Object.fromEntries(d.rows.map((r) => [r.key, r.count]))).toEqual({ ok: 2, d30: 1, d14: 1, d7: 1, expired: 1 })
  })

  it('yaklaşan bitişler: dolmuş ve hatalı hariç, en yakın önce', () => {
    expect(upcoming(CERTS, 3).map((c) => c.domain)).toEqual(['soon7.example.com', 'soon14.example.com', 'soon30.example.com'])
  })

  it('matris: takım satırı tüm katmanlar, katman satırları seçili kapsam; dikkat isteyen önce', () => {
    const m = matrixRows(CERTS, buildTeams(TEAM_STATS))
    expect(m.map((r) => r.name)).toEqual(['Takım A', 'Takım B'])
    expect(m[0].total).toBe(5)                          // SY 4 + UG 1 (gone, T3)
    expect(m[0].counts).toMatchObject({ valid: 1, warning: 1, high: 1, critical: 1, expired: 1 })
    expect(m[0].tiers.map((t) => t.tier)).toEqual([1, 2])  // T3 üretim kapsamı dışında
    expect(matrixRows(CERTS, buildTeams(TEAM_STATS), [1, 2, 3, 4, 0])[0].tiers.map((t) => t.tier)).toEqual([1, 2, 3])
  })

  it('URL eşlemesi gidiş-dönüş; bilinmeyen değerler yok sayılır', () => {
    const s = { kpi: 'd14', q: 'x', issuers: ['A, Inc', 'R3'], team: '2', tier: 0, level: 'critical', sort: 'domain|desc', matrixOpen: true, allTiers: true }
    const m = toUrlMapping(s)
    expect(stateFromUrl((k, fb) => m[k] ?? fb)).toEqual(s)
    expect(stateFromUrl((k, fb) => ({ st_k: 'bogus', st_lvl: 'x', st_tier: '9', st_sort: 'nope' })[k] ?? fb))
      .toMatchObject({ kpi: null, level: null, tier: null, sort: 'priority|asc' })
  })

  it('CSV: formül ile başlayan hücre nötrlenir', () => {
    const csv = statsCsv([cert('=cmd|x.example.com', 10)], (k) => k, new Map())
    expect(csv).toContain("'=cmd|x.example.com")
  })
})

describe('StatsView — KPI kutucukları tabloyu süzer', () => {
  it('"7 gün içinde" → yalnız o satır, çip çıkar; çipin × düğmesi süzgeci kaldırır; URL st_k', async () => {
    renderView()
    expect(rows()).toHaveLength(7)
    fireEvent.click(tile('d7'))
    expect(tile('d7')).toHaveAttribute('aria-pressed', 'true')
    expect(rows()).toEqual(['soon7.example.com'])
    expect(chips()).toEqual(['kpi'])
    await waitFor(() => expect(new URLSearchParams(window.location.search).get('st_k')).toBe('d7'))
    expect(new URLSearchParams(window.location.search).get('tab')).toBe('stats')   // uygulamanın anahtarına dokunulmaz
    fireEvent.click(screen.getByRole('button', { name: removeChip('stv.kpi.d7') }))
    expect(rows()).toHaveLength(7)
    expect(chips()).toEqual([])
  })

  it('"30 gün içinde" 14 ve 7 günlükleri de gösterir; aynı kutucuğa ikinci dokunuş süzgeci kaldırır', () => {
    renderView()
    fireEvent.click(tile('d30'))
    expect(rows().sort()).toEqual(['soon14.example.com', 'soon30.example.com', 'soon7.example.com'])
    fireEvent.click(tile('d30'))
    expect(tile('d30')).toHaveAttribute('aria-pressed', 'false')
    expect(rows()).toHaveLength(7)
  })

  it('araç çubuğundaki "Kalan süre" menüsü aynı durumu sürer', async () => {
    renderView()
    pressMenuTrigger(document.querySelector('[data-slot="stats-window"]'))
    fireEvent.click(await screen.findByRole('menuitemradio', { name: both('stv.kpi.expired') }))
    expect(rows()).toEqual(['gone.example.com'])
    expect(tile('expired')).toHaveAttribute('aria-pressed', 'true')
  })
})

describe('StatsView — takım × katman matrisi', () => {
  it('hücre → takım + katman + seviye süzgeci (üç çip); aynı hücre tekrar → süzgeç kalkar', async () => {
    renderView()
    openMatrix()
    const cell = screen.getByRole('button', { name: cellName('Takım A', 1, 'critical', 1) })
    fireEvent.click(cell)
    expect(rows()).toEqual(['soon7.example.com'])
    expect(chips()).toEqual(['team', 'tier', 'level'])
    expect(screen.getByRole('button', { name: cellName('Takım A', 1, 'critical', 1) })).toHaveAttribute('aria-pressed', 'true')
    await waitFor(() => {
      const p = new URLSearchParams(window.location.search)
      expect([p.get('st_team'), p.get('st_tier'), p.get('st_lvl'), p.get('st_mx')]).toEqual(['1', '1', 'critical', '1'])
    })
    fireEvent.click(screen.getByRole('button', { name: cellName('Takım A', 1, 'critical', 1) }))
    expect(rows()).toHaveLength(7)
    expect(chips()).toEqual([])
  })

  it('takım satırı hücresi (tüm katmanlar) + takım süzgeci düğmesi; UG alanı da takıma dahil', () => {
    renderView()
    openMatrix()
    fireEvent.click(screen.getByRole('button', { name: cellName('Takım A', null, 'expired', 1) }))
    expect(rows()).toEqual(['gone.example.com'])            // UG takımı olarak dahil, T3 olsa da takım satırında sayılır
    fireEvent.click(screen.getByRole('button', { name: both('stv.mxFilterTeam', 'Takım B') }))
    expect(rows().sort()).toEqual(['down.example.com', 'gone.example.com', 'ok-b.example.com'])
    expect(chips()).toEqual(['team'])
  })

  it('"Tüm katmanlar" kapsamı T3 satırını açar', () => {
    renderView()
    openMatrix()
    expect(screen.queryByRole('button', { name: new RegExp(esc(tierLbl(3)[0]) + '|' + esc(tierLbl(3)[1])) })).toBeNull()
    fireEvent.click(screen.getByRole('button', { name: both('stv.mxScopeAll') }))
    expect(screen.getAllByRole('button', { name: new RegExp(esc(tierLbl(3)[0]) + '|' + esc(tierLbl(3)[1])) }).length).toBeGreaterThan(0)
  })

  it('takım verisi yoksa matris açılır ve boş durum söyler', () => {
    renderView({ teamStats: null })
    openMatrix()
    expect(screen.getByText(both('stv.mxEmpty'))).toBeInTheDocument()
  })
})

describe('StatsView — arama, sıralama, sayfalama', () => {
  it('gecikmeli arama sağlayıcıda da arar; URL st_q', async () => {
    const user = userEvent.setup()
    renderView()
    await user.type(screen.getByRole('searchbox', { name: both('stv.searchPh') }), 'digicert')
    await waitFor(() => expect(rows().sort()).toEqual(['ok-b.example.com', 'soon14.example.com']))
    expect(chips()).toEqual(['q'])
    await waitFor(() => expect(new URLSearchParams(window.location.search).get('st_q')).toBe('digicert'))
  })

  it('başlıktan sıralama: kalan gün artan, sonra azalan (aria-sort)', () => {
    renderView()
    const head = () => document.querySelector('[data-slot="stats-sort-head"][data-col="days"]')
    fireEvent.click(head())
    expect(rows()).toEqual(['gone.example.com', 'soon7.example.com', 'soon14.example.com', 'soon30.example.com', 'ok-b.example.com', 'ok-a.example.com', 'down.example.com'])
    expect(head().closest('th')).toHaveAttribute('aria-sort', 'ascending')
    fireEvent.click(head())
    expect(rows()[0]).toBe('down.example.com')
    expect(head().closest('th')).toHaveAttribute('aria-sort', 'descending')
  })

  it('varsayılan sıra: önce dikkat isteyenler (dolmuş, kritik, hata…)', () => {
    renderView()
    expect(rows().slice(0, 3)).toEqual(['gone.example.com', 'soon7.example.com', 'down.example.com'])
  })

  it('sayfalama standardı: 60 sertifika → 50 + 10; sayfa URL\'de, yenilemede geri gelir', async () => {
    const many = Array.from({ length: 60 }, (_, i) => cert(`host-${String(i).padStart(2, '0')}.example.com`, 40 + i))
    const { unmount } = render(<StatsView certs={many} teamStats={null} onRowClick={vi.fn()} />)
    expect(rows()).toHaveLength(50)
    fireEvent.click(screen.getByRole('button', { name: both('pg.next') }))
    expect(rows()).toHaveLength(10)
    await waitFor(() => expect(new URLSearchParams(window.location.search).get('st_page')).toBe('2'))
    unmount()
    render(<StatsView certs={many} teamStats={null} onRowClick={vi.fn()} />)
    expect(rows()).toHaveLength(10)
  })

  it('URL\'den açılış: st_k + st_sort durumu kurar, tab korunur', () => {
    window.history.replaceState(null, '', '/?tab=stats&st_k=d30&st_sort=days_remaining%7Casc')
    renderView()
    expect(tile('d30')).toHaveAttribute('aria-pressed', 'true')
    expect(rows()).toEqual(['soon7.example.com', 'soon14.example.com', 'soon30.example.com'])
  })

  it('satır tıklaması ve Enter sertifika penceresini açar; takım rozeti ve üye penceresi satıra SIZMAZ', async () => {
    const onRowClick = vi.fn()
    renderView({ onRowClick })
    const row = document.querySelector('[data-slot="stats-row"][data-domain="soon7.example.com"]')
    fireEvent.click(row)
    fireEvent.keyDown(row, { key: 'Enter' })
    expect(onRowClick.mock.calls).toEqual([['soon7.example.com'], ['soon7.example.com']])
    fireEvent.click(within(row).getAllByRole('button', { name: both('team.openMembers', 'Takım A') })[0])
    expect(onRowClick).toHaveBeenCalledTimes(2)
    // Üye penceresi portal'da çizilir ama React ağacında SATIRIN İÇİNDEDİR: penceredeki bir tıklama (ör. içeriğe)
    // olay kabarcığıyla satırın onClick'ine ulaşıp sertifika penceresini de açardı — takım hücresi bunu keser.
    const dlg = await screen.findByRole('dialog')
    fireEvent.click(dlg)
    fireEvent.keyDown(dlg, { key: 'Enter' })
    expect(onRowClick).toHaveBeenCalledTimes(2)
  })
})

describe('StatsView — boş, sonuç yok, yükleniyor', () => {
  it('hiç sertifika yok → boş durum; "Domain ekle" yalnız yetkiliye', () => {
    const onAddDomain = vi.fn()
    const { unmount } = render(<StatsView certs={[]} teamStats={null} canAddDomain onAddDomain={onAddDomain} />)
    expect(screen.getByText(both('stv.emptyTitle'))).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: both('inv.addBtn') }))
    expect(onAddDomain).toHaveBeenCalled()
    unmount()
    render(<StatsView certs={[]} teamStats={null} onAddDomain={onAddDomain} />)
    expect(screen.queryByRole('button', { name: both('inv.addBtn') })).toBeNull()
  })

  it('süzgeçle sonuç yok → eylemli boş durum (temizle + yetkiliye Domain ekle)', async () => {
    const user = userEvent.setup()
    const { unmount } = renderView({ canAddDomain: true, onAddDomain: vi.fn() })
    await user.type(screen.getByRole('searchbox', { name: both('stv.searchPh') }), 'nothing-here')
    expect(await screen.findByText(both('stv.noResults'))).toBeInTheDocument()
    const block = screen.getByText(both('stv.noResults')).closest('[data-slot="empty"]')
    expect(within(block).getByRole('button', { name: both('inv.addBtn') })).toBeInTheDocument()
    fireEvent.click(within(block).getByRole('button', { name: both('app.clearFilters') }))
    expect(rows()).toHaveLength(7)
    unmount()
    const u2 = userEvent.setup()
    renderView({ canAddDomain: false, onAddDomain: vi.fn() })
    await u2.type(screen.getByRole('searchbox', { name: both('stv.searchPh') }), 'nothing-here')
    const block2 = (await screen.findByText(both('stv.noResults'))).closest('[data-slot="empty"]')
    expect(within(block2).queryByRole('button', { name: both('inv.addBtn') })).toBeNull()
  })

  it('ilk veri gelene kadar iskelet (yalancı "sertifika yok" değil)', () => {
    render(<StatsView certs={[]} teamStats={null} loading />)
    expect(document.querySelector('[data-slot="stats-skeleton"]')).not.toBeNull()
    expect(screen.getByRole('status')).toHaveTextContent(both('stv.loading'))
    expect(screen.queryByText(both('stv.emptyTitle'))).toBeNull()
  })
})

describe('StatsView — genel bakış kartları + operasyon özeti', () => {
  it('yaklaşan bitişler en yakın önce; satır pencereyi açar; "tabloda göster" 30 gün + gün sırası', () => {
    const onRowClick = vi.fn()
    renderView({ onRowClick })
    const items = [...document.querySelectorAll('[data-slot="stats-upcoming-item"]')].map((b) => b.getAttribute('data-domain'))
    expect(items).toEqual(['soon7.example.com', 'soon14.example.com', 'soon30.example.com', 'ok-b.example.com', 'ok-a.example.com'])
    fireEvent.click(screen.getByRole('button', { name: both('card.openDetailFor', 'soon7.example.com') }))
    expect(onRowClick).toHaveBeenCalledWith('soon7.example.com')
    fireEvent.click(screen.getByRole('button', { name: both('stv.upInTable') }))
    expect(tile('d30')).toHaveAttribute('aria-pressed', 'true')
    expect(rows()).toEqual(['soon7.example.com', 'soon14.example.com', 'soon30.example.com'])
  })

  it('dağılım göstergesi sayıları + bilinmeyen notu', () => {
    renderView()
    const dist = [...document.querySelectorAll('[data-slot="stats-dist-row"]')].map((r) => [r.getAttribute('data-bucket'), r.textContent])
    expect(dist.map(([k]) => k)).toEqual(['ok', 'd30', 'd14', 'd7', 'expired'])
    expect(dist[0][1]).toMatch(/2/)
    expect(screen.getByText(both('stv.distUnknown', 1, 0))).toBeInTheDocument()
  })

  it('operasyon özetindeki takım satırı tabloyu o takıma süzer', async () => {
    api.getExecutiveStats.mockResolvedValue({ success: true, data: {
      certs: { total: 7, ok: 2, health_pct: 28.6 }, alerts: { open: 1 }, sla: {}, window_days: 30,
      teams: [{ team_id: 2, team_name: 'Takım B', total: 3, ok: 1, health_pct: 33.3 }, { team_id: 1, team_name: 'Takım A', total: 4, ok: 1, health_pct: 25 }],
    } })
    renderView()
    const row = await waitFor(() => {
      const r = document.querySelector('[data-slot="exs-team-row"]')
      if (!r) throw new Error('yok')
      return r
    })
    await act(async () => { fireEvent.click(row) })
    expect(rows().sort()).toEqual(['down.example.com', 'gone.example.com', 'ok-b.example.com'])
    expect(chips()).toEqual(['team'])
  })
})

describe('StatsView — telefon (kart görünümü)', () => {
  it('tablo yerine kartlar; kart düğmesi pencereyi açar; matris kartlarında seviye çipleri süzer', () => {
    mobile.value = true
    const onRowClick = vi.fn()
    renderView({ onRowClick })
    expect(document.querySelector('[data-slot="stats-table"]')).toBeNull()
    expect(document.querySelectorAll('[data-slot="stats-card"]')).toHaveLength(7)
    fireEvent.click(within(document.querySelector('[data-slot="stats-card"][data-domain="ok-a.example.com"]')).getByRole('button', { name: both('card.openDetailFor', 'ok-a.example.com') }))
    expect(onRowClick).toHaveBeenCalledWith('ok-a.example.com')
    openMatrix()
    expect(document.querySelector('[data-slot="stats-matrix"]')).toBeNull()
    expect(document.querySelectorAll('[data-slot="stats-matrix-card"]')).toHaveLength(2)
    fireEvent.click(screen.getByRole('button', { name: cellName('Takım A', 2, 'high', 1) }))
    expect([...document.querySelectorAll('[data-slot="stats-card"]')].map((c) => c.getAttribute('data-domain'))).toEqual(['soon14.example.com'])
  })

  it('telefonda sıfır sayılı kutucuklar gizli (Toplam ve Sağlıklı kalır)', () => {
    mobile.value = true
    render(<StatsView certs={[cert('ok-a.example.com', 120)]} teamStats={null} onRowClick={vi.fn()} />)
    expect([...document.querySelectorAll('[data-slot="stat-item"]')].map((b) => b.getAttribute('data-key'))).toEqual(['total', 'valid'])
  })
})
