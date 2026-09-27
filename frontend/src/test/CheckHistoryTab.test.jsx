import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor, within } from './test-utils.jsx'
import CheckHistoryTab from '../components/history/CheckHistoryTab.jsx'

const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))

vi.mock('../api/client', () => ({
  formatDate:     (s) => s ?? '',
  formatDateSec:  (s) => s ?? '',
  formatDateOnly: (s) => s ?? '',
  api: withApiFallback({
    monitoring: {
      getCheckHistory: vi.fn(),
      getCheckHistoryCsvUrl: vi.fn(() => '/api/monitoring/ping/1/history?format=csv'),
    },
  }),
}))
import { api } from '../api/client'

const item = (ts, up = true) => ({ id: Math.random(), checked_at: ts, up, rtt_ms: 10, error: up ? null : 'timeout' })

const envelope = (over = {}) => ({ success: true, data: {
  items: [item('2026-08-07T10:00:00'), item('2026-08-07T09:00:00', false), item('2026-08-06T12:00:00')],
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
    const seps = document.querySelectorAll('[data-slot="hist-day-sep"]')
    expect(seps.length).toBe(2)   // 07 Ağustos + 06 Ağustos
  })

  it('özet kutucukları: kontrol + hata (süzgeç, aria-pressed) · erişilebilirlik % · kesinti (salt gösterim)', async () => {
    renderTab()
    await screen.findByText('2026-08-07T10:00:00')
    const tiles = [...document.querySelectorAll('[data-slot="hist-tile"]')]
    expect(tiles).toHaveLength(4)
    expect(tiles[0].tagName).toBe('BUTTON')
    expect(tiles[0]).toHaveAttribute('aria-pressed', 'true')     // Tümü seçili
    expect(tiles[0].textContent).toContain('120')
    expect(tiles[1]).toHaveAttribute('aria-pressed', 'false')
    expect(tiles[1].textContent).toContain('7')
    expect(tiles[2].tagName).not.toBe('BUTTON')                  // erişilebilirlik süzgeç değil
    expect(tiles[2].textContent).toContain('94.17%')              // (120 − 7) / 120
    expect(tiles[3].textContent).toContain('0')                   // aralıkta kesinti alarmı yok
    // Sol renk şeridi yok (kullanıcı kuralı): kutucuk tam çerçeveli
    for (const tile of tiles) { expect(tile).toHaveClass('border'); expect(tile.className).not.toMatch(/border-l-/) }
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
    // 2026-09-26: alarm satırı SOL RENK ŞERİDİ taşımaz (kullanıcı kuralı) — tam çerçeve + hafif zemin
    const rows = [...document.querySelectorAll('[data-slot="hist-alert-row"]')]
    expect(rows.map((r) => r.dataset.kind)).toEqual(['resolved', 'triggered'])
    for (const r of rows) {
      expect(r.className).not.toMatch(/border-l|hist-alert-row/)
      expect(r).toHaveClass('border')
    }
    // Olay KARTI (2026-09-27): seviye rozeti, çözüm satırında süre (09:30 → 10:15 = 45 dk), alarm geçmişi bağlantısı
    expect(rows[1].querySelector('[data-slot="badge"][data-level="CRITICAL"]')).not.toBeNull()
    expect(rows[0].textContent).toMatch(/45 (dk|min)/)
    expect(within(rows[0]).getByRole('button', { name: /alarm geçmişinde aç|open in alert history/i })).toBeInTheDocument()
  })

  it('geniş ekranda satırlar shadcn Table: sütun başlıkları `columns`, sayfalama tablonun DIŞINDA, eski CSS ızgarası yok', async () => {
    renderTab({ gridClass: 'dom-rt-grid' })
    await screen.findByText('2026-08-07T10:00:00')
    const rowsBox = document.querySelector('[data-slot="hist-list"] > [data-slot="hist-rows"]')
    expect(rowsBox).toHaveAttribute('data-view', 'table')
    expect(rowsBox.querySelector('[data-slot="table-container"]')).not.toBeNull()
    expect(screen.getAllByRole('columnheader').map((h) => h.textContent)).toEqual(['Zaman', 'Durum', 'RTT', 'Detay'])
    // Sayfalama satır kabının DIŞINDA (genişlemez)
    expect(rowsBox.querySelector('[data-slot="pagination"], nav')).toBeNull()
    expect(document.querySelector('.upt-rt-grid, .upt-rt-head, .dom-rt-grid')).toBeNull()
  })

  it("'changed' kipi (DNS): değişen kayıt HATA değildir — kutucuk amber, erişilebilirlik zaman tabanlı (kesinti yoksa %100)", async () => {
    renderTab({ filterMode: 'changed' })
    await screen.findByText('2026-08-07T10:00:00')
    const tiles = [...document.querySelectorAll('[data-slot="hist-tile"]')]
    expect(tiles[1]).toHaveAttribute('data-tone', 'warning')          // 7 değişen kayıt → amber, kırmızı değil
    expect(tiles[2].textContent).toContain('100%')                     // aralıkta kesinti alarmı yok
    expect(tiles[2].textContent).not.toContain('94.17%')               // (120 − 7) / 120 DEĞİL
  })

  it('sütun sayısını AŞAN hücre (DNS değer farkı paneli) sütun kaymaz: altında tam genişlik ek satır olur', async () => {
    renderTab({
      renderRow: (c) => (<>
        <span>{c.checked_at}</span><span>{c.up ? 'UP' : 'DOWN'}</span><span>{c.rtt_ms}ms</span><span>{c.error || '—'}</span>
        {!c.up && <div data-testid="diff-panel">fark paneli</div>}
      </>),
    })
    await screen.findByText('2026-08-07T10:00:00')
    const extra = document.querySelector('[data-slot="hist-row-extra"]')
    expect(extra).not.toBeNull()
    const cell = extra.querySelector('td')
    expect(cell).toHaveAttribute('colspan', '4')
    expect(within(cell).getByTestId('diff-panel')).toBeInTheDocument()
    // Veri satırları sütun sayısı kadar hücre taşır (ek içerik ayrı satırda) — koşul false iken ek satır yok
    const dataRows = [...document.querySelectorAll('tbody tr')].filter((tr) => tr.children.length > 1)
    expect(dataRows.every((tr) => tr.children.length === 4)).toBe(true)
    expect(document.querySelectorAll('[data-slot="hist-row-extra"]')).toHaveLength(1)
  })

  it('telefonda satırlar KART: zaman + durum başlıkta, diğer hücreler sütun etiketiyle; tablo çizilmez', async () => {
    const width = window.innerWidth
    Object.defineProperty(window, 'innerWidth', { value: 390, configurable: true, writable: true })
    try {
      renderTab()
      await screen.findByText('2026-08-07T10:00:00')
      await waitFor(() => expect(document.querySelector('[data-slot="hist-rows"][data-view="cards"]')).not.toBeNull())
      expect(document.querySelector('table')).toBeNull()
      const card = document.querySelector('[data-slot="hist-card"]')
      expect(card.textContent).toContain('RTT')          // sütun etiketi hücrenin yanında
      expect(card.textContent).toContain('10ms')
      expect(document.querySelectorAll('[data-slot="hist-day-sep"]').length).toBe(2)
    } finally {
      Object.defineProperty(window, 'innerWidth', { value: width, configurable: true, writable: true })
    }
  })

  it('yoğunluk şeridi: dilime tıklayınca o alt-aralık from/to ile istenir (zoom)', async () => {
    renderTab()
    await screen.findByText('2026-08-07T10:00:00')
    const cells = document.querySelectorAll('[data-cell]')
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
    // 30g preset istenir ama backend yalnız son 5 günü döndürür (retention clamp) → uyarı.
    //
    // TARİH SABİT YAZILAMAZ. Bileşenin koşulu `range.from > now - preset` (CheckHistoryTab:75-85),
    // yani istenen pencereye GÖRE değerlendiriliyor. Fixture eskiden '2026-08-06' diye sabitti;
    // takvim ilerleyip o gün 30 günlük pencerenin dışına düşünce koşul sessizce false oldu ve
    // uyarı hiç çizilmedi — süit üretim koduna hiç dokunulmadan kızardı (2026-09-07 CI).
    // Referansı "şimdi"den türet: clamp mesafesi her koşumda 5 gün, yani daima pencerenin içinde.
    const clampedFrom = new Date(Date.now() - 5 * 86400000).toISOString().slice(0, 19)
    api.monitoring.getCheckHistory.mockResolvedValue(envelope({
      range: { from: clampedFrom, to: new Date().toISOString().slice(0, 19) },
    }))
    renderTab({ defaultPreset: 30 })
    await screen.findByText(/gösteriliyor|starting from/i)
  })

  it('CSV bağlantısı seçili filtre paramlarını taşır', async () => {
    renderTab()
    await screen.findByText('2026-08-07T10:00:00')
    const csv = document.querySelector('a[data-slot="hist-csv"]')
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

  it('Ozel Aralik uygulanmadan CSV baglantisi da days=custom URETMEZ (D20)', async () => {
    // load() bu durumu guard'liyordu ama csvParams ham `preset` yaziyordu: kullanici
    // "Ozel Aralik"i secip tarih uygulamadan CSV'ye basarsa `?days=custom` gidip backend
    // 500 veriyordu. Ayni kuralin iki yuzu - ifade load() ile BIREBIR ayni olmali.
    renderTab()
    await screen.findByText('2026-08-07T10:00:00')

    fireEvent.click(screen.getByRole('button', { name: /ozel aralik|özel aralık|custom range/i }))
    await new Promise(r => setTimeout(r, 50))

    const csvCalls = api.monitoring.getCheckHistoryCsvUrl.mock.calls
    expect(csvCalls.length).toBeGreaterThan(0)
    const params = csvCalls.at(-1)[2]
    expect(params.days).toBeUndefined()   // 'custom' de degil, hic YOK
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
        fail('2026-08-07T12:10:00', 'ayni hata'),   // öğlen UTC: her dilimde ayrı gün (gün ayracı yerel gün, ISSUE-007 kardeşi)
        fail('2026-08-06T11:50:00', 'ayni hata'),
      ]
      api.monitoring.getCheckHistory.mockResolvedValue(envelope({ items: acrossDays, total: 2 }))
      renderTab({ groupIdenticalErrors: true, rowSignature: sig })
      await screen.findByText('2026-08-07T12:10:00')
      // İki farklı güne düştükleri için gruplanmadılar: ikisi de doğrudan görünür
      expect(screen.getByText('2026-08-06T11:50:00')).toBeInTheDocument()
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

  describe('hata durumu', () => {
    it('API hata dönünce "kayıt yok" DEĞİL, hata bandı gösterir (yanlış teşhis bekçisi)', async () => {
      // Eskiden useCheckHistory error'u üretiyor ama CheckHistoryTab okumuyordu: 500/403'te kullanıcı
      // "Seçili aralıkta kayıt yok" görüp monitörün hiç kontrol edilmediğini sanıyordu.
      api.monitoring.getCheckHistory.mockResolvedValue({ success: false, error: 'HTTP 500 — sunucu hatası' })
      renderTab()

      expect(await screen.findByRole('alert')).toBeInTheDocument()
      expect(screen.getByText(/HTTP 500/)).toBeInTheDocument()
      expect(screen.queryByText(/kayıt yok|No records/i)).toBeNull()
    })

    it('hata bandındaki "Yeniden dene" isteği tekrar eder', async () => {
      api.monitoring.getCheckHistory.mockResolvedValue({ success: false, error: 'boom' })
      renderTab()
      await screen.findByRole('alert')
      const calls = api.monitoring.getCheckHistory.mock.calls.length

      fireEvent.click(screen.getByRole('button', { name: /yeniden dene|retry/i }))
      await waitFor(() => expect(api.monitoring.getCheckHistory.mock.calls.length).toBeGreaterThan(calls))
    })

    it('veri GERÇEKTEN boşsa hata değil, boş durum gösterilir', async () => {
      api.monitoring.getCheckHistory.mockResolvedValue({ success: true, data: {
        items: [], counts: { total: 0, fail: 0 }, buckets: [], alerts: [],
        range: { from: '2026-08-06T10:00:00', to: '2026-08-07T10:30:00' }, total: 0, page: 0, size: 50 } })
      renderTab()

      expect(await screen.findByText(/kayıt yok|No records/i)).toBeInTheDocument()
      expect(screen.queryByRole('alert')).toBeNull()
    })
  })
})
