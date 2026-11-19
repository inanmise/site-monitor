import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor, within } from './test-utils.jsx'
import OutageCalendar from '../components/admin/alerts/OutageCalendar.jsx'

const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))

vi.mock('../api/client', () => ({
  formatDate: (s) => s ?? '',
  api: withApiFallback({ admin: { getOutageCalendar: vi.fn() } }),
}))

import { api } from '../api/client'

/**
 * Takım kesinti takvimi bileşeni (2026-11-19): günler kova rengiyle çizilir, gelecek gün tıklanamaz, güne basma
 * `onPickDay(takım, gün)` çağırır; okuma hatası "kesinti yok" DEĞİL (hata + yeniden dene), eski sunucu "bu sürümde yok",
 * `truncated` uyarısı, geç gelen yanıt düşürülür.
 */
const NOW = Date.parse('2026-11-19T09:00:00Z')   // İstanbul 19 Kasım 12:00
const TEAMS = [{ id: 5, name: 'Takim A' }, { id: 6, name: 'Takim B' }]

function calResponse(teamId = 5, over = {}) {
  const days = []
  for (let d = 1; d <= 30; d++) {
    const day = `2026-11-${String(d).padStart(2, '0')}`
    if (d === 3) days.push({ day, outage_minutes: 135, alarms: 3, worst_level: 'CRITICAL' })
    else if (d === 10) days.push({ day, outage_minutes: 10, alarms: 1, worst_level: 'WARNING' })
    else if (d > 19) days.push({ day, outage_minutes: null, alarms: 0, worst_level: null })
    else days.push({ day, outage_minutes: 0, alarms: 0, worst_level: null })
  }
  return { success: true, data: { month: '2026-11', team_id: teamId, team_name: teamId === 5 ? 'Takim A' : 'Takim B', days, total_minutes: 145, truncated: false, ...over } }
}

const errorAnswer = (status, code, error = 'Server text') => {
  const o = { success: false, error }
  Object.defineProperty(o, 'errorInfo', { value: { status, code }, enumerable: false })
  return o
}

function renderCal(props = {}) {
  const onPickDay = vi.fn(), onMonthChange = vi.fn(), onTeamChange = vi.fn()
  const utils = render(<OutageCalendar teams={TEAMS} teamsState="ok" teamId="" primaryTeamId={5} month="2026-11" nowMs={NOW}
    onPickDay={onPickDay} onMonthChange={onMonthChange} onTeamChange={onTeamChange} {...props} />)
  return { ...utils, onPickDay, onMonthChange, onTeamChange }
}

const dayCell = (container, day) => container.querySelector(`[data-slot="outage-cal-day"][data-day="${day}"]`)

beforeEach(() => {
  vi.clearAllMocks()
  api.admin.getOutageCalendar.mockResolvedValue(calResponse())
})

describe('OutageCalendar', () => {
  it('birincil takımla ister; günleri kova ve erişilebilir adla çizer; güne basma onPickDay(takım, gün)', async () => {
    const { container, onPickDay } = renderCal()
    await waitFor(() => expect(dayCell(container, '2026-11-03')).toBeTruthy())
    expect(api.admin.getOutageCalendar).toHaveBeenCalledWith({ team: '5', month: '2026-11' })

    const d3 = dayCell(container, '2026-11-03')
    expect(d3.tagName).toBe('BUTTON')
    expect(d3).toHaveAttribute('data-bucket', '3')
    expect(d3).toHaveAccessibleName('3 November: 2 h 15 min of outage, 3 alerts, worst level Critical. Select to list that day’s alerts.')
    expect(dayCell(container, '2026-11-10')).toHaveAttribute('data-bucket', '1')

    const d1 = dayCell(container, '2026-11-01')
    expect(d1).toHaveAttribute('data-bucket', 'zero')
    expect(d1).toHaveAccessibleName('1 November: no outage. Select to list that day’s alerts.')
    expect(d1.style.backgroundColor).toBe('')   // nötr — yeşil ya da kırmızı değil

    // Gelecek gün düğme değil, tıklanamaz
    const d25 = dayCell(container, '2026-11-25')
    expect(d25.tagName).toBe('DIV')
    expect(d25).toHaveAttribute('data-bucket', 'future')

    // Bugün işaretli
    expect(dayCell(container, '2026-11-19')).toHaveAttribute('data-today', 'true')

    fireEvent.click(d3)
    expect(onPickDay).toHaveBeenCalledWith('5', '2026-11-03')

    // Özet: toplam, kesintili gün, en uzun gün
    const sum = container.querySelector('[data-slot="outage-cal-summary"]')
    expect(sum).toHaveTextContent('Takim A')
    expect(sum).toHaveTextContent('Total outage: 2 h 25 min')
    expect(sum).toHaveTextContent('2 days with outages')
    expect(sum).toHaveTextContent('Longest: 3 November (2 h 15 min)')

    // Dar ekran listesi: yalnız kesintili günler, tarih sıralı; tıklanır
    const list = screen.getByRole('list', { name: 'Days with outages' })
    const items = within(list).getAllByRole('button')
    expect(items.map((b) => b.getAttribute('data-day'))).toEqual(['2026-11-03', '2026-11-10'])
    fireEvent.click(items[1])
    expect(onPickDay).toHaveBeenLastCalledWith('5', '2026-11-10')
  })

  it('URL takımı birincil takımın önüne geçer; ay düğmeleri onMonthChange; bu ayda "Sonraki" ve "Bu ay" kapalı', async () => {
    const { onMonthChange } = renderCal({ teamId: '6' })
    await waitFor(() => expect(api.admin.getOutageCalendar).toHaveBeenCalledWith({ team: '6', month: '2026-11' }))
    expect(screen.getByRole('button', { name: 'Next month' })).toBeDisabled()
    expect(screen.getByRole('button', { name: 'This month' })).toBeDisabled()
    fireEvent.click(screen.getByRole('button', { name: 'Previous month' }))
    expect(onMonthChange).toHaveBeenCalledWith('2026-10')
  })

  it('geçmiş ayda "Sonraki" ve "Bu ay" açık; 24 ay öncesinde "Önceki" kapalı', async () => {
    api.admin.getOutageCalendar.mockResolvedValue({ ...calResponse(), data: { ...calResponse().data, month: '2024-11' } })
    const { onMonthChange } = renderCal({ month: '2024-11' })
    await waitFor(() => expect(api.admin.getOutageCalendar).toHaveBeenCalled())
    expect(screen.getByRole('button', { name: 'Previous month' })).toBeDisabled()
    fireEvent.click(screen.getByRole('button', { name: 'This month' }))
    expect(onMonthChange).toHaveBeenCalledWith('2026-11')
  })

  it('okuma hatası: "kesinti yok" değil, hata bloğu + Yeniden dene yeniden ister', async () => {
    api.admin.getOutageCalendar.mockResolvedValueOnce(errorAnswer(503, 'DB_UNAVAILABLE', 'The outage calendar can’t be read right now.'))
    const { container } = renderCal()
    await screen.findByText('Outage calendar unavailable')
    expect(screen.getByText('The outage calendar can’t be read right now.')).toBeInTheDocument()
    expect(container.querySelector('[data-slot="outage-cal-day"]')).toBeNull()
    expect(screen.queryByText('No outage this month.')).toBeNull()
    fireEvent.click(screen.getByRole('button', { name: 'Try again' }))
    await waitFor(() => expect(dayCell(container, '2026-11-03')).toBeTruthy())
    expect(api.admin.getOutageCalendar).toHaveBeenCalledTimes(2)
  })

  it('ağ hatası (istisna) da hata bloğu', async () => {
    api.admin.getOutageCalendar.mockRejectedValueOnce(new Error('The connection to the server was lost.'))
    renderCal()
    await screen.findByText('The connection to the server was lost.')
  })

  it('eski sunucu (400 INVALID_PARAMETER) → "bu sürümde yok", hata değil', async () => {
    api.admin.getOutageCalendar.mockResolvedValue(errorAnswer(400, 'INVALID_PARAMETER'))
    renderCal()
    await screen.findByText('No outage calendar in this version')
    expect(screen.queryByText('Outage calendar unavailable')).toBeNull()
  })

  it('truncated: uyarı satırı', async () => {
    api.admin.getOutageCalendar.mockResolvedValue(calResponse(5, { truncated: true }))
    renderCal()
    await screen.findByText('The calendar may be incomplete')
  })

  it('geç gelen yanıt düşürülür: önceki takımın cevabı sonraki takımın takvimini ezmez', async () => {
    let resolveFirst
    api.admin.getOutageCalendar
      .mockImplementationOnce(() => new Promise((r) => { resolveFirst = r }))
      .mockImplementationOnce(() => Promise.resolve(calResponse(6)))
    const { rerender, container } = renderCal({ teamId: '5' })
    await waitFor(() => expect(api.admin.getOutageCalendar).toHaveBeenCalledTimes(1))
    rerender(<OutageCalendar teams={TEAMS} teamsState="ok" teamId="6" primaryTeamId={5} month="2026-11" nowMs={NOW} />)
    await waitFor(() => expect(container.querySelector('[data-slot="outage-cal-summary"]')).toHaveTextContent('Takim B'))
    resolveFirst(calResponse(5))
    await new Promise((r) => setTimeout(r, 20))
    expect(container.querySelector('[data-slot="outage-cal-summary"]')).toHaveTextContent('Takim B')
    expect(container.querySelector('[data-slot="outage-calendar"]')).toHaveAttribute('data-team', '6')
  })

  it('takım yok: liste boş → bilgi bloğu; okunamadı → hata bloğu; istek atılmaz', async () => {
    const { unmount } = renderCal({ teams: [], primaryTeamId: null })
    await screen.findByText('No team for the calendar')
    unmount()
    renderCal({ teams: [], teamsState: 'failed', primaryTeamId: null })
    await screen.findByText('Team list unavailable')
    expect(api.admin.getOutageCalendar).not.toHaveBeenCalled()
  })

  it('takım listesi yüklenirken birincil takımla yine ister', async () => {
    renderCal({ teams: [], teamsState: 'loading' })
    await waitFor(() => expect(api.admin.getOutageCalendar).toHaveBeenCalledWith({ team: '5', month: '2026-11' }))
  })
})
