import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor, fireEvent, within } from './test-utils.jsx'
import userEvent from '@testing-library/user-event'
import IncidentHistoryPage from '../components/IncidentHistoryPage.jsx'

/**
 * Olay ve Hata Geçmişi — 908 satır, 2026-08-19'a kadar HİÇ test dosyası yoktu (kapsam %24,
 * tamamı lazy-tabs-smoke'un salt mount'undan geliyordu).
 *
 * İki dal özellikle önemli:
 *  - YETKİSİZ kullanıcı erken return'e düşer (`canView('incidents.view')`). Bu erken return
 *    aynı dosyada bir hook-sırası hatasına da yol açmıştı: `useUrlQuerySync` onun ALTINDA
 *    çağrılıyordu ve yetkiler asenkron yüklenip `allowView` false→true dönünce hook sayısı
 *    değişiyordu. Buradaki iki test o düzeltmeyi de korur.
 *  - Liste + trend + seçenek uçları paralel çekilir; kısmi başarısızlık sayfayı düşürmemeli.
 */

const { apiMock, permMock } = vi.hoisted(() => {
  const target = { incidents: {}, admin: {} }
  const deep = (obj) => new Proxy(obj, {
    get(t, prop) {
      if (prop === 'then' || typeof prop === 'symbol') return undefined
      if (prop in t) return typeof t[prop] === 'object' && t[prop] !== null ? deep(t[prop]) : t[prop]
      t[prop] = vi.fn(() => Promise.resolve({ success: true, data: [] }))
      return t[prop]
    },
  })
  return { apiMock: deep(target), permMock: { allow: true } }
})

vi.mock('../api/client', () => ({
  api: apiMock,
  formatDate:     (s) => s ?? '',
  formatDateSec:  (s) => s ?? '',
  formatDateOnly: (s) => s ?? '',
}))

vi.mock('../contexts/PermissionsProvider.jsx', () => ({
  usePermissions: () => ({
    perms: {},
    canView:    () => permMock.allow,
    canEdit:    () => permMock.allow,
    canExecute: () => permMock.allow,
    refresh: () => {},
  }),
  PermissionsProvider: ({ children }) => children,
}))

import { api } from '../api/client'

const INCIDENTS = [
  {
    id: 1, title: 'Ödeme servisi kesintisi', occurred_at: '2026-08-18T09:00:00',
    detected_at: '2026-08-18T09:05:00', resolved_at: '2026-08-18T10:00:00',
    severity: 'CRITICAL', status: 'RESOLVED', category: 'APPLICATION',
    team_name: 'Ödeme', sla_breached: true, duration_minutes: 60,
  },
  {
    id: 2, title: 'DNS gecikmesi', occurred_at: '2026-08-17T14:00:00',
    severity: 'MEDIUM', status: 'OPEN', category: 'NETWORK',
    team_name: 'Altyapı', sla_breached: false,
  },
]

beforeEach(() => {
  vi.clearAllMocks()
  // 2026-09-28 yeniden tasarım: süzgeçler adrese (`ih_*`) yazılıyor — önceki testin süzgeci sonrakine taşınmasın.
  window.history.replaceState({}, '', '/')
  permMock.allow = true
  api.incidents.list.mockResolvedValue({ success: true, data: INCIDENTS, total: 2, page: 0, size: 50 })
  api.incidents.trends.mockResolvedValue({ success: true, data: [{ day: '2026-08-18', count: 1, critical: 1, high: 0, medium: 0, low: 0 }] })
  api.incidents.options.mockResolvedValue({ success: true, data: [] })
  api.admin.getTeams.mockResolvedValue({ success: true, data: [{ id: 1, name: 'Ödeme' }] })
})

describe('IncidentHistoryPage — yetki', () => {
  it('yetkisiz kullanıcıya erişim yok mesajı gösterir, listeyi ÇEKMEZ', async () => {
    permMock.allow = false
    render(<IncidentHistoryPage />)

    expect(document.querySelector('[data-slot="empty"]')).toBeTruthy()
  })

  it('yetki false→true dönerse hook sırası bozulmaz (yeniden render çökmez)', async () => {
    // Regresyon: useUrlQuerySync erken return'ün ALTINDAYDI; yetkiler asenkron yüklenince
    // hook sayısı değişiyor ve React "Rendered more hooks" ile patlıyordu.
    permMock.allow = false
    const { rerender } = render(<IncidentHistoryPage />)
    expect(document.querySelector('[data-slot="empty"]')).toBeTruthy()

    permMock.allow = true
    rerender(<IncidentHistoryPage />)

    await waitFor(() => expect(api.incidents.list).toHaveBeenCalled())
  })
})

describe('IncidentHistoryPage — liste', () => {
  it('yetkiliyken listeyi ve trendi çeker', async () => {
    render(<IncidentHistoryPage />)
    await waitFor(() => {
      expect(api.incidents.list).toHaveBeenCalled()
      expect(api.incidents.trends).toHaveBeenCalled()
    })
  })

  it('olay başlıkları çizilir', async () => {
    render(<IncidentHistoryPage />)
    await waitFor(() => expect(document.body.textContent).toContain('Ödeme servisi kesintisi'))
    expect(document.body.textContent).toContain('DNS gecikmesi')
  })

  it('boş listede çökmez', async () => {
    api.incidents.list.mockResolvedValue({ success: true, data: [], total: 0, page: 0, size: 50 })
    render(<IncidentHistoryPage />)
    await waitFor(() => expect(api.incidents.list).toHaveBeenCalled())
    expect(screen.getAllByRole('button').length).toBeGreaterThan(0)
  })

  it('liste ucu REDDEDİLSE bile sayfa ayakta kalır', async () => {
    api.incidents.list.mockRejectedValue(new Error('boom'))
    render(<IncidentHistoryPage />)
    await waitFor(() => expect(api.incidents.list).toHaveBeenCalled())
    expect(document.body.textContent.length).toBeGreaterThan(0)
  })

  it('trend ucu başarısız olsa da liste çizilir', async () => {
    api.incidents.trends.mockRejectedValue(new Error('trend down'))
    render(<IncidentHistoryPage />)
    await waitFor(() => expect(document.body.textContent).toContain('Ödeme servisi kesintisi'))
  })
})

describe('IncidentHistoryPage — etkileşim', () => {
  it('özet akordiyonu açılıp kapanır (varsayılan gizli dal)', async () => {
    const user = userEvent.setup()
    render(<IncidentHistoryPage />)
    await waitFor(() => expect(api.incidents.list).toHaveBeenCalled())

    // D2 (2026-09-26): projenin tek katlanır şeridi ui/CollapsibleSection (data-slot="stats-toggle")
    const acc = document.querySelector('[data-slot="stats-toggle"]')
    expect(acc).toBeTruthy()
    const before = acc.getAttribute('aria-expanded')
    await user.click(acc)
    expect(document.querySelector('[data-slot="stats-toggle"]').getAttribute('aria-expanded')).not.toBe(before)
  })

  it('önem filtresi değişince liste yeniden çekilir', async () => {
    const user = userEvent.setup()
    render(<IncidentHistoryPage />)
    await waitFor(() => expect(api.incidents.list).toHaveBeenCalled())
    const firstCalls = api.incidents.list.mock.calls.length

    const sev = screen.getByRole('combobox', { name: /^(Önem|Severity)$/ })
    await user.selectOptions(sev, 'CRITICAL')

    await waitFor(() => expect(api.incidents.list.mock.calls.length).toBeGreaterThan(firstCalls))
  })

  it('arama kutusuna yazınca liste yenilenir', async () => {
    const user = userEvent.setup()
    render(<IncidentHistoryPage />)
    await waitFor(() => expect(api.incidents.list).toHaveBeenCalled())
    const firstCalls = api.incidents.list.mock.calls.length

    const input = screen.getByRole('searchbox')
    expect(input).toBeTruthy()
    await user.type(input, 'ödeme')

    await waitFor(() => expect(api.incidents.list.mock.calls.length).toBeGreaterThan(firstCalls))
  })

  it('durum ve kategori filtreleri de listeyi tetikler', async () => {
    const user = userEvent.setup()
    render(<IncidentHistoryPage />)
    await waitFor(() => expect(api.incidents.list).toHaveBeenCalled())

    const selects = [...document.querySelectorAll('[data-slot="incident-filters"] select')]
    expect(selects.length).toBeGreaterThanOrEqual(3)
    for (const sel of selects.slice(0, 3)) {
      const opt = [...sel.options].find(o => o.value)
      if (opt) await user.selectOptions(sel, opt.value)
    }

    await waitFor(() => expect(document.body.textContent.length).toBeGreaterThan(0))
  })
})

// ── Derin bağlantı regresyonu (2026-09-26, sayfalama standardı) ─────────────
// `useEffect(() => { setPage(0) }, [effFilters, size])` MOUNT'ta da koşuyordu: `?page=3` ile gelen
// kullanıcı ilk istekte 3. sayfayı alıyor, hemen ardından 1. sayfaya düşüyor ve useUrlQuerySync
// param'ı adresten de siliyordu. Artık sıfırlama yalnız süzgeç DEĞERİ değişince.
describe('IncidentHistoryPage — derin bağlantı sayfası', () => {
  it('?page=3&ps=25 → ilk ve SONRAKİ istekler page=2 (0-tabanlı), size=25; adres korunur', async () => {
    window.history.replaceState({}, '', '/?tab=incident-history&page=3&ps=25')
    api.incidents.list.mockResolvedValue({ success: true, data: INCIDENTS, total: 200, page: 2, size: 25 })
    render(<IncidentHistoryPage />)
    await waitFor(() => expect(api.incidents.list).toHaveBeenCalled())
    expect(api.incidents.list.mock.calls[0][0]).toMatchObject({ page: 2, size: 25 })
    await screen.findByRole('navigation', { name: /Sayfalama|Pagination/ })
    await new Promise(r => setTimeout(r, 400))   // debounce'lu URL yazımı + olası mount-sıfırlama
    for (const [args] of api.incidents.list.mock.calls) expect(args).toMatchObject({ page: 2 })
    expect(screen.getByRole('button', { name: /^(Sayfa|Page) 3$/ })).toHaveAttribute('aria-current', 'page')
    const q = new URLSearchParams(window.location.search)
    expect(q.get('page')).toBe('3')
    expect(q.get('ps')).toBe('25')
    window.history.replaceState({}, '', '/')
  })

  it('geçersiz ps (listede yok) yok sayılır; sayfa 99 toplam gelince son sayfaya çekilir', async () => {
    window.history.replaceState({}, '', '/?page=99&ps=33')
    api.incidents.list.mockResolvedValue({ success: true, data: INCIDENTS, total: 120, page: 0, size: 50 })
    render(<IncidentHistoryPage />)
    await waitFor(() => expect(api.incidents.list).toHaveBeenCalled())
    expect(api.incidents.list.mock.calls[0][0]).toMatchObject({ page: 98, size: 50 })
    await waitFor(() => expect(api.incidents.list).toHaveBeenLastCalledWith(expect.objectContaining({ page: 2, size: 50 })))
    window.history.replaceState({}, '', '/')
  })
})

describe('IncidentHistoryPage — shadcn (D2, 2026-09-26)', () => {
  it('özet açılınca 14 MonitorStatsBar kartı; "Kritik" kartı severity=CRITICAL süzer ve aria-pressed olur', async () => {
    api.incidents.trends.mockResolvedValue({ success: true, data: { summary: { total: 5, critical: 2 }, by_severity: { HIGH: 1 }, by_status: {}, daily: [] } })
    render(<IncidentHistoryPage />)
    await waitFor(() => expect(api.incidents.list).toHaveBeenCalled())
    fireEvent.click(document.querySelector('[data-slot="stats-toggle"]'))
    const cards = await waitFor(() => {
      const c = document.querySelectorAll('[data-slot="stat-item"]')
      expect(c).toHaveLength(14)
      return c
    })
    const crit = [...cards].find(c => c.getAttribute('data-tone') === 'critical')
    expect(crit).toHaveAttribute('aria-pressed', 'false')
    fireEvent.click(crit)
    await waitFor(() => expect(api.incidents.list).toHaveBeenLastCalledWith(expect.objectContaining({ severity: 'CRITICAL' })))
    expect([...document.querySelectorAll('[data-slot="stat-item"]')].find(c => c.getAttribute('data-tone') === 'critical'))
      .toHaveAttribute('aria-pressed', 'true')
  })

  // 2026-09-28 yeniden tasarım: ayrıntı artık ÖNCE-OKUMA Sheet'i (role=dialog) — salt okunur form (devre dışı
  // metin kutuları) yerine başlık + işlenmiş içerik. Korunan sözleşme aynı: satır klavyeyle (Enter) açılır, açılan
  // pencere o kaydın başlığını taşır ve hiçbir düzenlenebilir alan içermez.
  it('satır Enter ile ayrıntı penceresini (Sheet, role=dialog) açar; toplu seçim kutusunun adı satırı ayırır', async () => {
    render(<IncidentHistoryPage />)
    await waitFor(() => expect(document.body.textContent).toContain('Ödeme servisi kesintisi'))
    expect(screen.getByRole('checkbox', { name: /Ödeme servisi kesintisi/ })).toBeInTheDocument()
    const row = screen.getByRole('row', { name: /Ödeme servisi kesintisi/ })
    fireEvent.keyDown(row, { key: 'Enter' })
    const dlg = await screen.findByRole('dialog')
    expect(within(dlg).getByRole('heading', { name: 'Ödeme servisi kesintisi' })).toBeInTheDocument()
    expect(within(dlg).queryByRole('textbox')).toBeNull()
  })

  it('telefonda (390 px) kart listesi — tablo yok, düzenle düğmesi satırı adıyla ayırır', async () => {
    const w = window.innerWidth
    window.innerWidth = 390
    try {
      render(<IncidentHistoryPage />)
      await waitFor(() => expect(document.body.textContent).toContain('Ödeme servisi kesintisi'))
      expect(document.querySelector('table')).toBeNull()
      expect(screen.getByRole('button', { name: /^Ödeme servisi kesintisi — (Düzenle|Edit)$/ })).toHaveAttribute('data-size', 'icon')
    } finally {
      window.innerWidth = w
    }
  })
})
