import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor } from './test-utils'

const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))

const state = vi.hoisted(() => ({ byTeam: {} }))

vi.mock('../api/client', () => ({
  api: withApiFallback({
    notificationGroups: {
      list: vi.fn(async (teamId) => ({ success: true, data: { groups: state.byTeam[String(teamId)] ?? [] } })),
    },
  }),
}))

const { api } = await import('../api/client')
const NotificationGroupSelect = (await import('../components/ui/NotificationGroupSelect.jsx')).default

const g = (over = {}) => ({ id: 1, team_id: 1, name: 'Payments', active: true, is_default: false, ...over })

describe('NotificationGroupSelect', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    state.byTeam = {}
  })

  it('Takım seçilmeden liste ÇEKİLMEZ ve seçici kilitli olur', async () => {
    render(<NotificationGroupSelect teamId="" value="" onChange={() => {}} />)
    // Takımsız bir istek boş liste döndürürdü ama yine de gereksiz bir sorgudur;
    // dahası kullanıcıya "grup yok" izlenimi verirdi — oysa henüz sorulmadı.
    expect(api.notificationGroups.list).not.toHaveBeenCalled()
    expect(await screen.findByText(/Choose a team first/)).toBeTruthy()
  })

  it('Takım değişince liste YENİDEN yüklenir (eski takımın grupları kalmaz)', async () => {
    state.byTeam = { 1: [g()], 2: [g({ id: 9, team_id: 2, name: 'Infra' })] }
    const { rerender } = render(<NotificationGroupSelect teamId="1" value="" onChange={() => {}} />)
    await waitFor(() => expect(api.notificationGroups.list).toHaveBeenCalledWith('1', true))

    rerender(<NotificationGroupSelect teamId="2" value="" onChange={() => {}} />)
    await waitFor(() => expect(api.notificationGroups.list).toHaveBeenCalledWith('2', true))

    fireEvent.mouseDown(screen.getByRole('button'))
    expect(await screen.findByText('Infra')).toBeTruthy()
    expect(screen.queryByText('Payments')).toBeNull()
  })

  it('SİLİNMİŞ grup seçiliyse rozetle YİNE gösterilir', async () => {
    // Gizlenseydi seçici boş görünür, kullanıcı "takım varsayılanı" sanırdı —
    // oysa izleme hâlâ silinmiş gruba işaret ediyor.
    state.byTeam = { 1: [g({ id: 5, name: 'Eski Nöbet', active: false })] }
    render(<NotificationGroupSelect teamId="1" value="5" onChange={() => {}} />)

    expect(await screen.findByText(/Eski Nöbet \(deleted\)/)).toBeTruthy()
  })

  it('SİLİNMİŞ grup seçili DEĞİLSE yeni seçenek olarak sunulmaz', async () => {
    state.byTeam = { 1: [g({ id: 5, name: 'Eski Nöbet', active: false }), g({ id: 6, name: 'Güncel' })] }
    render(<NotificationGroupSelect teamId="1" value="" onChange={() => {}} />)
    await waitFor(() => expect(api.notificationGroups.list).toHaveBeenCalled())

    fireEvent.mouseDown(screen.getByRole('button'))
    expect(await screen.findByText('Güncel')).toBeTruthy()
    expect(screen.queryByText(/Eski Nöbet/)).toBeNull()
  })

  it('Varsayılan grup listede yıldızla ayırt edilir', async () => {
    state.byTeam = { 1: [g({ name: 'Nöbet', is_default: true })] }
    render(<NotificationGroupSelect teamId="1" value="" onChange={() => {}} />)
    await waitFor(() => expect(api.notificationGroups.list).toHaveBeenCalled())

    fireEvent.mouseDown(screen.getByRole('button'))
    expect(await screen.findByText('Nöbet ★')).toBeTruthy()
  })

  it('Boş seçim "Takım varsayılanı" olarak görünür — eksiklik gibi durmaz', async () => {
    state.byTeam = { 1: [g()] }
    render(<NotificationGroupSelect teamId="1" value="" onChange={() => {}} />)
    expect(await screen.findByText(/Team default/)).toBeTruthy()
  })
})
