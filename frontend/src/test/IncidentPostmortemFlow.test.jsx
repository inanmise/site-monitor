import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, waitFor, fireEvent, within } from './test-utils.jsx'

/**
 * Olay & Hata Geçmişi ↔ postmortem (2026-10-11): liste satırında postmortem / aksiyon rozetleri, ayrıntı panelinde
 * "Postmortem'i aç" penceresi, "Aksiyonlar" görünümü (URL `ih_view=actions`; süzgeçler isteğe gider, sayaç kutucukları
 * süzer, sahibi olduğu aksiyonun durumu değişir, "Olayı aç" ayrıntıyı sunucudan açar). Adlar sahte.
 */
const { apiMock, perms } = vi.hoisted(() => {
  const deep = (obj) => new Proxy(obj, {
    get(t, prop) {
      if (prop === 'then' || typeof prop === 'symbol') return undefined
      if (prop in t) return typeof t[prop] === 'object' && t[prop] !== null ? deep(t[prop]) : t[prop]
      t[prop] = vi.fn(() => Promise.resolve({ success: true, data: [] }))
      return t[prop]
    },
  })
  return { apiMock: deep({ incidents: {}, admin: {}, users: {} }), perms: { view: true, manage: true, del: true } }
})

vi.mock('../api/client', () => ({ api: apiMock, formatDate: (s) => s ?? '', formatDateSec: (s) => s ?? '', formatDateOnly: (s) => s ?? '' }))
vi.mock('../contexts/PermissionsProvider.jsx', () => ({
  usePermissions: () => ({ perms: {}, canView: () => perms.view, canEdit: () => perms.manage, canExecute: () => perms.del, refresh: () => {} }),
  PermissionsProvider: ({ children }) => children,
}))
vi.mock('../components/ui/MarkdownEditor.jsx', () => ({
  default: ({ value, onChange, editable = true }) => (editable
    ? <textarea data-slot="md-edit" value={value || ''} onChange={(e) => onChange(e.target.value)} />
    : <div data-slot="md-view">{value}</div>),
}))
vi.mock('@/components/shadcn/chart', async (importOriginal) => {
  const real = await importOriginal()
  return { ...real, ChartContainer: ({ children }) => <div data-slot="chart">{children}</div>, BarChart: () => <div data-slot="bar-probe" /> }
})

import IncidentHistoryPage from '../components/incidenthistory/IncidentHistoryPage.jsx'

const LONG_TEAM = 'Dijital Kanallar Mobil Bankacılık ve Uzun Adlı Uygulama Geliştirme Ekibi (Teknoloji / Kanal Yönetimi)'
const ROW = {
  id: 7, title: 'Ödeme kesintisi', occurred_at: '2026-10-10T08:00:00', severity: 'HIGH', status: 'RESOLVED', category: 'APPLICATION',
  team_id: 5, team_name: LONG_TEAM, postmortem_status: 'IN_REVIEW', actions_open: 2, actions_overdue: 1, actions_done: 1, actions_total: 3,
}
const ACTION = {
  id: 11, incident_id: 7, title: 'Sertifika bitişine 30 gün kala alarm kur', status: 'OPEN', kind: 'DETECT', priority: 'HIGH',
  owner_name: 'Ayşegül Nur Karaosmanoğlu-Yıldırımtekin', due_date: '2026-10-01', overdue: true, mine: true,
  incident_title: 'Ödeme kesintisi', incident_team_name: LONG_TEAM,
}

beforeEach(() => {
  vi.clearAllMocks()
  perms.view = true; perms.manage = true; perms.del = true
  window.history.replaceState({}, '', '/?tab=incident-history')
  apiMock.incidents.list = vi.fn(() => Promise.resolve({ success: true, data: [ROW], total: 1, page: 0, size: 20 }))
  apiMock.incidents.get = vi.fn(() => Promise.resolve({ success: true, data: ROW }))
  apiMock.incidents.trends = vi.fn(() => Promise.resolve({ success: true, data: { summary: { total: 1 }, by_severity: {}, by_status: {}, daily: [] } }))
  apiMock.incidents.postmortem = vi.fn(() => Promise.resolve({ success: true, data: {
    incident_id: 7, can_edit: true, today: '2026-10-11', actions: [ACTION], timeline: [],
    postmortem: { exists: true, status: 'IN_REVIEW', version: 2, summary: 'Özet', impact: '', root_cause: '', trigger: '', detection: '', resolution: '', went_well: '', went_wrong: '', lucky: '', lessons: '' },
  } }))
  apiMock.incidents.actions = vi.fn(() => Promise.resolve({ success: true, data: { items: [ACTION], total: 1, page: 0, size: 50, totals: { open: 4, overdue: 1, mine: 2 } } }))
  apiMock.incidents.updateAction = vi.fn((id, body) => Promise.resolve({ success: true, data: { ...ACTION, ...body } }))
})
afterEach(() => { window.history.replaceState({}, '', '/') })

describe('Olay geçmişi — postmortem akışı', () => {
  it('liste rozetleri; ayrıntıdan postmortem penceresi açılır', async () => {
    render(<IncidentHistoryPage />)
    const badges = await waitFor(() => {
      const el = document.querySelector('[data-slot="pm-badges"]')
      expect(el).not.toBeNull()
      return el
    })
    expect(badges.querySelector('[data-slot="pm-badge-status"]')).toHaveAttribute('data-status', 'IN_REVIEW')
    expect(badges.querySelector('[data-slot="pm-badge-actions"]')).toHaveAttribute('data-tone', 'danger')
    fireEvent.click(screen.getAllByText('Ödeme kesintisi')[0])
    const open = await waitFor(() => {
      const el = document.querySelector('[data-slot="ih-open-postmortem"]')
      expect(el).not.toBeNull()
      return el
    })
    fireEvent.click(open)
    await waitFor(() => expect(document.querySelector('[data-slot="pm-modal"] [data-slot="pm-section"]')).not.toBeNull())
    expect(apiMock.incidents.postmortem).toHaveBeenCalledWith(7)
  })

  it('Aksiyonlar görünümü: süzgeçler isteğe gider, sahibinin durumu değişir, olayı açar', async () => {
    window.history.replaceState({}, '', '/?tab=incident-history&ih_view=actions&ih_amine=1')
    render(<IncidentHistoryPage />)
    const row = await waitFor(() => {
      const el = document.querySelector('[data-slot="pm-actions-list"] [data-slot="pm-action"]')
      expect(el).not.toBeNull()
      return el
    })
    expect(apiMock.incidents.actions).toHaveBeenLastCalledWith(expect.objectContaining({ status: 'open', mine: true, overdue: false }))
    expect(row).toHaveTextContent(LONG_TEAM)
    expect(row).toHaveAttribute('data-due', 'overdue')
    expect(document.querySelector('[data-slot="ih-results"]')).toBeNull()   // olay listesi gizli

    fireEvent.click(document.querySelector('[data-slot="pm-filter-overdue"]'))
    await waitFor(() => expect(apiMock.incidents.actions).toHaveBeenLastCalledWith(expect.objectContaining({ overdue: true, mine: true })))
    fireEvent.click(screen.getByRole('button', { name: /^(Finished|Biten)$/ }))
    await waitFor(() => expect(apiMock.incidents.actions).toHaveBeenLastCalledWith(expect.objectContaining({ status: 'done' })))

    fireEvent.change(within(document.querySelector('[data-slot="pm-actions-list"] [data-slot="pm-action"]')).getByRole('combobox'), { target: { value: 'DONE' } })
    await waitFor(() => expect(apiMock.incidents.updateAction).toHaveBeenCalledWith(11, { status: 'DONE' }))

    fireEvent.click(document.querySelector('[data-slot="pm-open-incident"]'))
    await waitFor(() => expect(apiMock.incidents.get).toHaveBeenCalledWith(7))
    await waitFor(() => expect(document.querySelector('[data-slot="ih-detail"]')).not.toBeNull())
  })

  it('görünüm geçişi URL\'ye yazılır; sayaç kutucuğu süzer', async () => {
    render(<IncidentHistoryPage />)
    await waitFor(() => expect(document.querySelector('[data-slot="ih-view-switch"]')).not.toBeNull())
    fireEvent.click(screen.getByRole('button', { name: /^(Action items|Aksiyonlar)$/ }))
    await waitFor(() => expect(window.location.search).toContain('ih_view=actions'))
    const kpi = await waitFor(() => {
      const el = document.querySelector('[data-slot="pm-kpi"][data-kind="mine"]')
      expect(el).not.toBeNull()
      return el
    })
    expect(kpi).toHaveTextContent('2')
    fireEvent.click(kpi)
    await waitFor(() => expect(apiMock.incidents.actions).toHaveBeenLastCalledWith(expect.objectContaining({ mine: true })))
  })
})
