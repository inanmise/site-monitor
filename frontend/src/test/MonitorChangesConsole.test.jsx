import { render, screen, fireEvent, waitFor, within } from './test-utils.jsx'
import { describe, it, expect, vi, beforeEach } from 'vitest'

const { apiMock } = vi.hoisted(() => {
  const deep = (obj) => new Proxy(obj, {
    get(t, prop) {
      if (prop === 'then' || typeof prop === 'symbol') return undefined
      if (prop in t) return typeof t[prop] === 'object' && t[prop] !== null ? deep(t[prop]) : t[prop]
      t[prop] = vi.fn(() => Promise.resolve({ success: true, data: {} }))
      return t[prop]
    },
  })
  return { apiMock: deep({ monitoring: {} }) }
})

vi.mock('../api/client', () => ({
  api: apiMock,
  formatDateSec: (s) => s ?? '',
  formatDateOnly: (s) => s ?? '',
  formatDate: (s) => s ?? '',
}))

import { api } from '../api/client'
import MonitorChangesConsole from '../components/admin/MonitorChangesConsole.jsx'

const ROW = {
  kind: 'SCRIPTED', resource_id: 12, resource_name: 'Ödeme akışı', seq: 3, event_type: 'UPDATE',
  team_id: 5, team_name: 'Kanal takımı', actor: 'N70678', actor_name: 'Ada Lovelace',
  ip_address: '10.20.30.40', user_agent: 'Mozilla/5.0 (Windows NT 10.0) Chrome/126.0.0.0',
  at: '2026-08-22T14:03:11', note: 'Zaman aşımı yetmiyordu',
  changes: JSON.stringify({
    intervalSeconds: { from: 300, to: 60 },
    timeoutMs: { from: 30000, to: 45000 },
    // DİKKAT: çip değerleri satır ADIYLA çakışmamalı — yoksa sorgular iki öğe bulur.
    name: { from: 'Eski ad', to: 'Yeni ad' },
    active: { from: false, to: true },
    url: { from: 'a', to: 'b' },
  }),
}
const DELETED = {
  ...ROW, kind: 'PORT', resource_id: 7, resource_name: 'Eski port', seq: 9,
  event_type: 'DELETE', changes: null, note: null, at: '2026-08-22T15:00:00',
}

function reply(rows, extra = {}) {
  return {
    success: true,
    data: { changes: rows, total: rows.length, event_counts: { CREATE: 4, UPDATE: 11, DELETE: 2 }, ...extra },
  }
}

beforeEach(() => {
  vi.clearAllMocks()
  api.monitoring.getRecentChanges.mockResolvedValue(reply([ROW, DELETED]))
})

describe('MonitorChangesConsole', () => {
  it('tüm türlerdeki değişiklikleri tek listede, künyesiyle gösterir', async () => {
    render(<MonitorChangesConsole />)
    await waitFor(() => expect(api.monitoring.getRecentChanges).toHaveBeenCalled())

    expect(await screen.findByText('Ödeme akışı')).toBeInTheDocument()
    expect(screen.getByText('Eski port')).toBeInTheDocument()
    // Tür + takım aynı satırda: "nerede" sorusunun cevabı.
    expect(screen.getByText(/Synthetic · Kanal takımı/)).toBeInTheDocument()
    // "Silindi" hem olay süzgecinde hem satır rozetinde geçer — satırdakini arıyoruz.
    const rows = screen.getByText('Eski port').closest('.chg-rows')
    expect(within(rows).getByText('Deleted')).toBeInTheDocument()
  })

  it('özet şeridi olay sayaçlarını sunucudan alır', async () => {
    const { container } = render(<MonitorChangesConsole />)
    await screen.findByText('Ödeme akışı')

    const values = [...container.querySelectorAll('.audit-stat-value')].map(n => n.textContent)
    expect(values).toEqual(['2', '4', '11', '2'])
  })

  it('kapalı satırda ilk 3 alan görünür, kalanı "+N alan" olarak özetlenir', async () => {
    const { container } = render(<MonitorChangesConsole />)
    await screen.findByText('Ödeme akışı')

    // 5 değişiklik var; liste taranırken satır şişmesin diye 3 çip + özet.
    expect(container.querySelectorAll('.chg-row-chips .chg-chip')).toHaveLength(4)
    expect(screen.getByText('+2 more')).toBeInTheDocument()
  })

  it('satır açılınca tam diff, not, IP ve izlemeye derin bağlantı gelir', async () => {
    render(<MonitorChangesConsole />)
    const head = (await screen.findByText('Ödeme akışı')).closest('button')

    fireEvent.click(head)
    expect(head).toHaveAttribute('aria-expanded', 'true')

    expect(screen.getByText('Zaman aşımı yetmiyordu')).toBeInTheDocument()
    expect(screen.getByText('10.20.30.40')).toBeInTheDocument()
    // Konsol ile izlemenin kendi sekmesi birbirine bağlanır.
    expect(screen.getByRole('link', { name: 'Go to monitor' }))
      .toHaveAttribute('href', '?tab=scripted&monitor=12&mtab=changes')
  })

  it('olay süzgeci sunucuya eventType olarak gider ve sayfa başa döner', async () => {
    render(<MonitorChangesConsole />)
    await screen.findByText('Ödeme akışı')
    api.monitoring.getRecentChanges.mockClear()

    fireEvent.click(screen.getByRole('button', { name: 'Deletions only' }))

    await waitFor(() => expect(api.monitoring.getRecentChanges).toHaveBeenCalledWith(
      expect.objectContaining({ eventType: 'DELETE', page: 0 })))
  })

  it('serbest arama istek parametresine yansır', async () => {
    render(<MonitorChangesConsole />)
    await screen.findByText('Ödeme akışı')
    api.monitoring.getRecentChanges.mockClear()

    fireEvent.change(screen.getByLabelText('Search by monitor name…'), { target: { value: 'ödeme' } })

    await waitFor(() => expect(api.monitoring.getRecentChanges).toHaveBeenCalledWith(
      expect.objectContaining({ q: 'ödeme', page: 0 })))
  })

  it('sonuç yoksa süzgeçleri temizlemeyi öneren boş durum gösterilir', async () => {
    api.monitoring.getRecentChanges.mockResolvedValue(reply([]))
    render(<MonitorChangesConsole />)

    expect(await screen.findByText('No changes recorded yet')).toBeInTheDocument()
    expect(screen.getByText(/Clear them and try again/)).toBeInTheDocument()
  })

  it('sunucu hatası uyarı olarak gösterilir, konsol çökmez', async () => {
    api.monitoring.getRecentChanges.mockResolvedValue({ success: false, error: 'yetkiniz yok' })
    render(<MonitorChangesConsole />)

    expect(await screen.findByText('yetkiniz yok')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Clear filters' })).toBeInTheDocument()
  })

  it('BOZUK changes alanı satırı düşürmez — künye okunmaya devam eder', async () => {
    api.monitoring.getRecentChanges.mockResolvedValue(reply([{ ...ROW, changes: '{bozuk' }]))
    const { container } = render(<MonitorChangesConsole />)

    expect(await screen.findByText('Ödeme akışı')).toBeInTheDocument()
    expect(container.querySelector('.chg-chip')).toBeNull()
  })

  it('adı olmayan kaynak kimliğiyle gösterilir (silinmiş kayıt boş satır olmaz)', async () => {
    api.monitoring.getRecentChanges.mockResolvedValue(reply([{ ...DELETED, resource_name: null }]))
    const { container } = render(<MonitorChangesConsole />)

    const row = await screen.findByText('#7')
    expect(within(container.querySelector('.chg-row')).getByText('Deleted')).toBeInTheDocument()
    expect(row).toBeInTheDocument()
  })
})
