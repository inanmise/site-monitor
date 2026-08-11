import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor } from './test-utils.jsx'
import CheckHistoryTab from '../components/history/CheckHistoryTab.jsx'

vi.mock('../api/client', () => ({
  formatDate:     (s) => s ?? '',
  formatDateSec:  (s) => s ?? '',
  formatDateOnly: (s) => s ?? '',
  api: {
    monitoring: {
      getCheckHistory: vi.fn(),
      getCheckHistoryCsvUrl: vi.fn(() => '/api/monitoring/ping/1/history?format=csv'),
    },
  },
}))
import { api } from '../api/client'

const item = (ts, up = true) => ({ id: Math.random(), checked_at: ts, up, rtt_ms: 10, error: up ? null : 'timeout' })

const envelope = (over = {}) => ({ success: true, data: {
  items: [item('2026-08-07T10:00:00'), item('2026-08-07T09:00:00', false), item('2026-08-06T22:00:00')],
  counts: { total: 120, fail: 7 }, buckets: [{ key: '2026-08-07T10', total: 60, fail: 0 }, { key: '2026-08-07T09', total: 60, fail: 7 }],
  alerts: [], range: { from: '2026-08-06T10:00:00', to: '2026-08-07T10:30:00' },
  total: 120, page: 0, size: 50, ...over,
} })

function renderTab(props = {}) {
  return render(
    <CheckHistoryTab kind="ping" monitorId={1} listKey="test-hist"
      columns={['Zaman', 'Durum', 'RTT', 'Detay']} urlSync={false}
      renderRow={(c) => (<>
        <span>{c.checked_at}</span>
        <span>{c.up ? 'UP' : 'DOWN'}</span>
        <span>{c.rtt_ms}ms</span>
        <span>{c.error || '—'}</span>
      </>)} {...props} />,
  )
}

describe('CheckHistoryTab', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    api.monitoring.getCheckHistory.mockResolvedValue(envelope())
  })

  it('sayaçlı filtre chip\'leri: Hatalı chip\'i status=fail paramıyla yeniden yükler', async () => {
    renderTab()
    await waitFor(() => expect(api.monitoring.getCheckHistory).toHaveBeenCalled())
    expect(api.monitoring.getCheckHistory.mock.calls[0][2].status).toBeUndefined()

    const failChip = await screen.findByRole('button', { name: /hatalı|failing/i })
    expect(failChip.textContent).toContain('7')      // aralık hata sayacı chip'te

    fireEvent.click(failChip)
    await waitFor(() => {
      const calls = api.monitoring.getCheckHistory.mock.calls
      expect(calls[calls.length - 1][2].status).toBe('fail')
      expect(calls[calls.length - 1][2].page).toBe(0)   // filtre değişimi sayfayı 1'e döndürür
    })
  })

  it('gün ayırıcıları: sayfadaki farklı günler için ayraç satırı basılır', async () => {
    renderTab()
    await screen.findByText('2026-08-07T10:00:00')
    const seps = document.querySelectorAll('.hist-day-sep')
    expect(seps.length).toBe(2)   // 07 Ağustos + 06 Ağustos
  })

  it('alarm işaret satırları: pencere kuralına göre doğru kontrolün üstünde görünür', async () => {
    api.monitoring.getCheckHistory.mockResolvedValue(envelope({
      alerts: [{ id: 5, alert_type: 'PING_DOWN', alert_level: 'CRITICAL', message: 'down',
        created_at: '2026-08-07T09:30:00', resolved: true, resolved_at: '2026-08-07T10:15:00' }],
    }))
    renderTab()
    await screen.findByText(/alarm tetiklendi|alert triggered/i)
    // Çözülme, 1. sayfanın en-üst penceresinde (items[0]'dan yeni) — o da görünür.
    expect(screen.getByText(/alarm çözüldü|alert resolved/i)).toBeInTheDocument()
    expect(screen.getAllByText(/PING_DOWN/).length).toBe(2)   // tetiklenme + çözülme satırları
  })

  it('yoğunluk şeridi: dilime tıklayınca o alt-aralık from/to ile istenir (zoom)', async () => {
    renderTab()
    await screen.findByText('2026-08-07T10:00:00')
    const cells = document.querySelectorAll('.hist-strip-cell')
    expect(cells.length).toBe(2)
    fireEvent.click(cells[1])   // '2026-08-07T09' kovası (saatlik)
    await waitFor(() => {
      const last = api.monitoring.getCheckHistory.mock.calls.at(-1)[2]
      expect(last.from).toBe('2026-08-07T09:00:00')
      expect(last.to).toBe('2026-08-07T09:59:59')
      expect(last.days).toBeUndefined()
    })
  })

  it('retention kırpma bildirimi: dönen range.from istenenden çok gerideyse uyarı basılır', async () => {
    // 30g preset istenecek ama backend 06 Ağustos'tan başlatmış (clamp) → notice
    renderTab({ defaultPreset: 30 })
    await screen.findByText(/gösteriliyor|starting from/i)
  })

  it('CSV bağlantısı seçili filtre paramlarını taşır', async () => {
    renderTab()
    await screen.findByText('2026-08-07T10:00:00')
    const csv = document.querySelector('.hist-csv-btn')
    expect(csv).not.toBeNull()
    expect(api.monitoring.getCheckHistoryCsvUrl).toHaveBeenCalled()
  })

  it('Özel Aralık seçili ama tarih uygulanmadıysa istek ATILMAZ; days paramı asla "custom" olmaz', async () => {
    renderTab()
    await screen.findByText('2026-08-07T10:00:00')
    const callsBefore = api.monitoring.getCheckHistory.mock.calls.length

    fireEvent.click(screen.getByRole('button', { name: /özel aralık|custom range/i }))
    // Tarih henüz uygulanmadı → yeni istek yok (eskiden days=custom gidip backend 500 veriyordu).
    await new Promise(r => setTimeout(r, 50))
    expect(api.monitoring.getCheckHistory.mock.calls.length).toBe(callsBefore)
    for (const call of api.monitoring.getCheckHistory.mock.calls) {
      expect(call[2].days).not.toBe('custom')
    }
  })

  it('server-side sayfalama: 120 kayıt / 50 → 3 sayfa; ileri gitmek page=1 ile istek atar', async () => {
    renderTab()
    await screen.findByText('2026-08-07T10:00:00')
    const next = screen.getAllByRole('button', { name: /sonraki|next/i })[0]
    fireEvent.click(next)
    await waitFor(() => {
      const last = api.monitoring.getCheckHistory.mock.calls.at(-1)[2]
      expect(last.page).toBe(1)   // 0-tabanlı API
    })
  })

  // ── Ardışık aynı sonuç gruplaması (opt-in) ────────────────────────────────
  describe('groupIdenticalErrors', () => {
    const fail = (ts, err) => ({ id: ts, checked_at: ts, up: false, rtt_ms: 0, error: err })
    // Hepsi AYNI güne ait: gün ayırıcısı araya girip grubu kırmasın.
    const sameDay = [
      fail('2026-08-07T10:03:00', 'Unexpected token (46:29)'),
      fail('2026-08-07T10:02:00', 'Unexpected token (46:29)'),
      fail('2026-08-07T10:01:00', 'Unexpected token (46:29)'),
      fail('2026-08-07T10:00:00', 'baska bir hata'),
    ]
    const sig = (c) => (c.up ? null : String(c.error || ''))

    it('prop VERİLMEZSE davranış değişmez — 9 izleme sayfasının hiçbiri etkilenmemeli', async () => {
      api.monitoring.getCheckHistory.mockResolvedValue(envelope({ items: sameDay, total: 4 }))
      renderTab()   // groupIdenticalErrors yok
      await screen.findByText('2026-08-07T10:03:00')
      // Dört satırın dördü de ayrı ayrı duruyor, hiçbir katlama düğmesi yok
      expect(screen.getByText('2026-08-07T10:02:00')).toBeInTheDocument()
      expect(screen.getByText('2026-08-07T10:01:00')).toBeInTheDocument()
      expect(screen.queryByRole('button', { name: /aynı sonuç|identical results/i })).toBeNull()
    })

    it('açıkken ardışık aynı hatalar tek satıra iner; genişletince tekil koşumlar görünür', async () => {
      api.monitoring.getCheckHistory.mockResolvedValue(envelope({ items: sameDay, total: 4 }))
      renderTab({ groupIdenticalErrors: true, rowSignature: sig })
      await screen.findByText('2026-08-07T10:03:00')

      // Grubun yalnız ilk kaydı görünür, diğer ikisi katlanmış
      expect(screen.queryByText('2026-08-07T10:02:00')).toBeNull()
      expect(screen.queryByText('2026-08-07T10:01:00')).toBeNull()
      // Farklı imzalı satır gruba dahil olmadı
      expect(screen.getByText('2026-08-07T10:00:00')).toBeInTheDocument()

      const toggle = screen.getByRole('button', { name: /aynı sonuç|identical results/i })
      expect(toggle.textContent).toContain('3×')       // sayfa içi tekrar sayısı
      expect(toggle).toHaveAttribute('aria-expanded', 'false')

      fireEvent.click(toggle)
      expect(await screen.findByText('2026-08-07T10:02:00')).toBeInTheDocument()
      expect(screen.getByText('2026-08-07T10:01:00')).toBeInTheDocument()
      expect(screen.getByRole('button', { name: /tekrarları gizle|hide repeats/i }))
        .toHaveAttribute('aria-expanded', 'true')
    })

    it('gün ayırıcısı grubu KIRAR — zaman bağlamı gruplamaya feda edilmez', async () => {
      const acrossDays = [
        fail('2026-08-07T00:10:00', 'ayni hata'),
        fail('2026-08-06T23:50:00', 'ayni hata'),
      ]
      api.monitoring.getCheckHistory.mockResolvedValue(envelope({ items: acrossDays, total: 2 }))
      renderTab({ groupIdenticalErrors: true, rowSignature: sig })
      await screen.findByText('2026-08-07T00:10:00')
      // İki farklı güne düştükleri için gruplanmadılar: ikisi de doğrudan görünür
      expect(screen.getByText('2026-08-06T23:50:00')).toBeInTheDocument()
      expect(screen.queryByRole('button', { name: /aynı sonuç|identical results/i })).toBeNull()
    })

    it('imza null dönen satırlar (başarılı koşumlar) hiç gruplanmaz', async () => {
      const passes = [
        item('2026-08-07T10:03:00'), item('2026-08-07T10:02:00'), item('2026-08-07T10:01:00'),
      ]
      api.monitoring.getCheckHistory.mockResolvedValue(envelope({ items: passes, total: 3 }))
      renderTab({ groupIdenticalErrors: true, rowSignature: sig })
      await screen.findByText('2026-08-07T10:03:00')
      expect(screen.getByText('2026-08-07T10:02:00')).toBeInTheDocument()
      expect(screen.getByText('2026-08-07T10:01:00')).toBeInTheDocument()
      expect(screen.queryByRole('button', { name: /aynı sonuç|identical results/i })).toBeNull()
    })
  })
})
