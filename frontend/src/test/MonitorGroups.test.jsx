import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor } from './test-utils.jsx'
import MonitorGroups from '../components/admin/MonitorGroups.jsx'

vi.mock('../api/client', () => ({
  api: {
    monitoring: {
      listGroups: vi.fn(),
      renameGroup: vi.fn(),
    },
  },
}))
import { api } from '../api/client'

// Backend snake_case. Aynı ad ("deneme") DNS + Ping türlerinde → takım+tür bazlı AYRI satırlar.
const GROUPS = [
  { id: 1, team_id: 1, team_name: 'Dijital SY', type: 'dns', name: 'deneme', count: 3 },
  { id: 2, team_id: 1, team_name: 'Dijital SY', type: 'ping', name: 'deneme', count: 1 },
]

describe('MonitorGroups', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    api.monitoring.listGroups.mockResolvedValue({ success: true, data: GROUPS })
    api.monitoring.renameGroup.mockResolvedValue({ success: true, data: { affected: 3 } })
  })

  it('renders team + type + group columns (same name in two types = two rows)', async () => {
    render(<MonitorGroups />)
    await waitFor(() => expect(api.monitoring.listGroups).toHaveBeenCalled())
    expect(await screen.findAllByText('deneme')).toHaveLength(2)          // dns + ping satırları
    expect(screen.getAllByText('Dijital SY').length).toBeGreaterThan(0)   // takım kolonu
  })

  it('inline rename → calls renameGroup(id, newName)', async () => {
    render(<MonitorGroups />)
    const renameButtons = await screen.findAllByRole('button', { name: /Rename|Yeniden Adlandır/i })
    fireEvent.click(renameButtons[0])   // ilk satır: id=1 (dns/deneme)
    const input = document.querySelector('.grp-input')
    expect(input).not.toBeNull()
    fireEvent.change(input, { target: { value: 'yeni' } })
    fireEvent.click(screen.getByRole('button', { name: /^Save$|^Kaydet$/i }))
    await waitFor(() => expect(api.monitoring.renameGroup).toHaveBeenCalledWith(1, 'yeni'))
  })

  // page/scripted TYPE_LABEL'da yoktu: rozet ham "page"/"scripted" yazıyor, tür adıyla arama da tutmuyordu.
  it('labels every group-carrying type, page + scripted included', async () => {
    api.monitoring.listGroups.mockResolvedValue({ success: true, data: [
      { id: 3, team_id: 1, team_name: 'Dijital SY', type: 'page',     name: 'g1', count: 2 },
      { id: 4, team_id: 1, team_name: 'Dijital SY', type: 'scripted', name: 'g2', count: 1 },
    ] })
    render(<MonitorGroups />)
    await waitFor(() => expect(api.monitoring.listGroups).toHaveBeenCalled())
    const badges = [...document.querySelectorAll('.grp-type-badge')].map(b => b.textContent)
    expect(badges).toHaveLength(2)
    expect(badges).not.toContain('page')
    expect(badges).not.toContain('scripted')
  })

  it('empty list → shows empty state', async () => {
    api.monitoring.listGroups.mockResolvedValue({ success: true, data: [] })
    render(<MonitorGroups />)
    await waitFor(() => expect(api.monitoring.listGroups).toHaveBeenCalled())
    expect(await screen.findByText(/No groups|Henüz grup/i)).toBeInTheDocument()
  })
})
