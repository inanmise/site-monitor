import { render, screen, fireEvent, waitFor, within } from './test-utils.jsx'
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'

const { apiMock } = vi.hoisted(() => {
  const deep = (obj) => new Proxy(obj, {
    get(t, prop) {
      if (prop === 'then' || typeof prop === 'symbol') return undefined
      if (prop in t) return typeof t[prop] === 'object' && t[prop] !== null ? deep(t[prop]) : t[prop]
      t[prop] = vi.fn(() => Promise.resolve({ success: true, data: {} }))
      return t[prop]
    },
  })
  return { apiMock: deep({ monitoring: {}, admin: {} }) }
})

vi.mock('../api/client', () => ({
  api: apiMock,
  formatDateSec: (s) => s ?? '',
  formatDateOnly: (s) => s ?? '',
  formatDate: (s) => s ?? '',
}))
// Telefon kipi (jsdom medya sorgusu görmez → kanca mock'lanır; TEK varyant çizilir)
const mobile = vi.hoisted(() => ({ on: false }))
vi.mock('../hooks/use-mobile.js', () => ({ useIsMobile: () => mobile.on }))
// Pano: jsdom'da clipboard yok — kopyalama yardımcısı taklit edilir
const clip = vi.hoisted(() => ({ copyText: vi.fn(() => Promise.resolve(true)) }))
vi.mock('../utils/copyText.js', () => ({ copyText: clip.copyText }))

import { api } from '../api/client'
import MonitorChangesConsole from '../components/admin/MonitorChangesConsole.jsx'

const ROW = {
  kind: 'SCRIPTED', resource_id: 12, resource_name: 'Ödeme akışı', seq: 3, event_type: 'UPDATE',
  team_id: 5, team_name: 'Kanal takımı', actor: 'N23456', actor_name: 'Ada Lovelace',
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
  event_type: 'DELETE', changes: null, note: null, at: '2026-08-22T15:00:00', actor: null, actor_name: null,
  ip_address: null, user_agent: null,   // zamanlanmış/geri doldurma satırı: oturum yok → IP yok
}
const CREATED = {
  ...ROW, kind: 'HTTP', resource_id: 31, resource_name: 'Yeni HTTP izlemesi', seq: 0,
  event_type: 'CREATE', changes: null, note: null, at: '2026-08-22T16:00:00',
}

const KIND_COUNTS = {
  SCRIPTED: { CREATE: 3, UPDATE: 8, DELETE: 1 },
  PORT: { CREATE: 1, UPDATE: 2 },
  DNS: { CREATE: 0, UPDATE: 0 },        // hic hareket yok -> kart CIZILMEZ
}

function reply(rows, extra = {}) {
  return {
    success: true,
    data: {
      changes: rows, total: rows.length,
      event_counts: { CREATE: 4, UPDATE: 11, DELETE: 2 },
      kind_counts: KIND_COUNTS,
      ...extra,
    },
  }
}

const lastCall = () => api.monitoring.getRecentChanges.mock.calls.at(-1)[0]
/** Satır kabı: masaüstünde tablo satırı (`tr`), telefonda kart (`li`) — ikisi de `data-chg-row` taşır. */
const rowOf = (text) => screen.getByText(text).closest('[data-chg-row]')

beforeEach(() => {
  vi.clearAllMocks()
  mobile.on = false
  api.monitoring.getRecentChanges.mockResolvedValue(reply([ROW, DELETED]))
  api.monitoring.getChangeDetail.mockResolvedValue({ success: true, data: { ...ROW, snapshot: JSON.stringify({ name: 'Yeni ad', intervalSeconds: 60, active: true }) } })
  // Varsayilan: takim listesi BOS -> secici cizilmez. Suzgeci sinayan testler kendi verisini kurar.
  api.admin.getTeams.mockResolvedValue({ success: true, data: [] })
})
afterEach(() => { mobile.on = false })

/**
 * Ozet serit + tur kartlari VARSAYILAN KAPALI (izleme sayfalarindaki istatistik seridiyle ayni).
 * Onlari sinayan testler once basligi acmali; asagida ayrica varsayilanin kapali oldugu ve
 * basligin gercekten actigi da sinaniyor.
 */
function openStats(container) {
  // Başlık ui/CollapsibleSection (shadcn Collapsible) — tetik `data-slot="stats-toggle"` düğmesi
  fireEvent.click(container.querySelector('[data-slot="stats-toggle"]'))
}

describe('MonitorChangesConsole — masaüstü tablo (shadcn Data Table görünümü)', () => {
  it('tüm türlerdeki değişiklikleri tek tabloda, künyesiyle gösterir; sütunlar zaman·kullanıcı·işlem·izleme·değişiklikler·IP', async () => {
    render(<MonitorChangesConsole />)
    await waitFor(() => expect(api.monitoring.getRecentChanges).toHaveBeenCalled())

    const table = await screen.findByRole('table')
    const heads = within(table).getAllByRole('columnheader').map(h => h.textContent)
    expect(heads.join('|')).toMatch(/Time\|User\|Action\|Monitor\|Changes\|IP address\|Details/)
    // Bir ekranda TEK tablo, kart listesi yok (tek varyant)
    expect(screen.getAllByRole('table')).toHaveLength(1)
    expect(document.querySelector('[data-slot="card"]')).toBeNull()

    expect(screen.getByText('Ödeme akışı')).toBeInTheDocument()
    expect(screen.getByText('Eski port')).toBeInTheDocument()
    // Tür + takım aynı künyede: "nerede" sorusunun cevabı. Takım adı tıklanabilir rozet (ayrı element).
    expect([...document.querySelectorAll('[data-slot="chg-row-meta"]')].some(e => /Synthetic · Kanal takımı/.test(e.textContent))).toBe(true)
    // Olay rozeti renkli Badge (data-event + data-tone) — sol renk şeridi YOK
    const del = rowOf('Eski port')
    expect(del.querySelector('[data-slot="badge"][data-event="delete"]')).toHaveAttribute('data-tone', 'danger')
    expect(rowOf('Ödeme akışı').querySelector('[data-event="update"]')).toHaveTextContent('Updated')
    // Kim: avatarlı kullanıcı rozeti; aktörsüz satır "SYSTEM" rozeti
    expect(rowOf('Ödeme akışı').querySelector('[data-slot="user-badge"]')).toHaveTextContent('Ada Lovelace')
    expect(within(del).getAllByText('SYSTEM').length).toBeGreaterThan(0)
    // Zaman: göreli metin, makine-okunur tam değer <time dateTime>
    expect(rowOf('Ödeme akışı').querySelector('time')).toHaveAttribute('datetime', '2026-08-22T14:03:11')
    // IP kopyalanabilir düğme (adı IP'yi taşır — satırlar ayırt edilir)
    expect(screen.getByRole('button', { name: 'Copy IP address 10.20.30.40' })).toBeInTheDocument()
  })

  it('tablo satırında en fazla 2 alan çipi, kalanı "+N more" (özet); çipler shadcn Badge', async () => {
    render(<MonitorChangesConsole />)
    const row = (await screen.findByText('Ödeme akışı')).closest('[data-chg-row]')

    // 5 değişiklik var; liste taranırken satır şişmesin diye 2 çip + özet.
    expect(row.querySelectorAll('[data-chip]')).toHaveLength(2)
    expect(within(row).getByText('+3 more')).toHaveAttribute('data-slot', 'badge')
    expect(row.querySelector('[data-chip="intervalSeconds"]')).toHaveTextContent('Check frequency5 min1 min')
    // Diff'i olmayan satır (silme) çip yerine tire
    expect(rowOf('Eski port').querySelectorAll('[data-chip]')).toHaveLength(0)
  })

  it('izleme adı gerçek bağlantı (?tab=<tür>&monitor=<id>&mtab=changes); sol tık uygulama içi geçiş yayar; silinmiş kayıt bağlantı DEĞİL', async () => {
    const nav = vi.fn()
    window.addEventListener('sm:navigate', nav)
    render(<MonitorChangesConsole />)
    const link = await screen.findByRole('link', { name: 'Ödeme akışı' })
    expect(link).toHaveAttribute('href', '?tab=scripted&monitor=12&mtab=changes')

    fireEvent.click(link)
    expect(nav).toHaveBeenCalledTimes(1)
    expect(nav.mock.calls[0][0].detail).toEqual({ tab: 'scripted', params: { monitor: 12, mtab: 'changes' } })
    // Bağlantıya tıklamak satırın ayrıntı panelini AÇMAZ
    expect(screen.queryByRole('dialog')).toBeNull()
    // Silinmiş izlemenin sayfası yok → ad düz metin
    expect(screen.queryByRole('link', { name: 'Eski port' })).toBeNull()
    window.removeEventListener('sm:navigate', nav)
  })

  it('satıra tıklamak (ya da Enter) ayrıntı panelini açar: tam fark tablosu, not, IP, tam zaman, izleme bağlantısı; o anki ayarlar ayrı uçtan', async () => {
    render(<MonitorChangesConsole />)
    const row = (await screen.findByText('Ödeme akışı')).closest('[data-chg-row]')
    expect(row).toHaveAttribute('tabindex', '0')
    expect(screen.queryByRole('dialog')).toBeNull()

    // Satırın zaman hücresine tıkla (izleme adı ayrı bağlantı, IP ayrı düğme — onlar paneli açmaz)
    fireEvent.click(row.querySelector('td'))
    const dlg = await screen.findByRole('dialog')
    expect(within(dlg).getByRole('heading', { name: /Ödeme akışı/ })).toBeInTheDocument()
    expect(within(dlg).getByText('2026-08-22T14:03:11', { selector: 'time' })).toBeInTheDocument()
    // Fark tablosu (audit/DiffTable) — 5 alan, eski/yeni hücreleri
    expect(within(dlg).getAllByRole('table')).toHaveLength(1)
    expect(within(dlg).getByText('1 min', { selector: '[data-diff="to"]' })).toBeInTheDocument()
    expect(within(dlg).getByText('5 min', { selector: '[data-diff="from"]' })).toBeInTheDocument()
    expect(within(dlg).getAllByText(/Check frequency|Timeout|Name|Active|URL/, { selector: '[data-diff="field"]' })).toHaveLength(5)
    expect(within(dlg).getByText('Zaman aşımı yetmiyordu')).toBeInTheDocument()
    expect(within(dlg).getByRole('button', { name: 'Copy IP address 10.20.30.40' })).toBeInTheDocument()
    expect(within(dlg).getByText('Chrome 126 · Windows')).toBeInTheDocument()
    expect(within(dlg).getByRole('link', { name: /Go to monitor/ })).toHaveAttribute('href', '?tab=scripted&monitor=12&mtab=changes')
    // Snapshot ayrı uçtan (liste yanıtı taşımaz): düzenlemede katlanır bölüm
    await waitFor(() => expect(api.monitoring.getChangeDetail).toHaveBeenCalledWith('scripted', 12, 3))
    fireEvent.click(within(dlg).getByRole('button', { name: /State after this change/ }))
    expect(await within(dlg).findByText('Yeni ad', { selector: 'dd' })).toBeInTheDocument()

    // Kapat → panel gider
    // İki kapatma yolu: başlıktaki X ve alt çubuktaki düğme — ikisi de "Close"; alttakini kullan
    fireEvent.click(within(dlg).getAllByRole('button', { name: 'Close' }).at(-1))
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())

    // Klavye: satır odaklı + Enter
    fireEvent.keyDown(row, { key: 'Enter' })
    expect(await screen.findByRole('dialog')).toBeInTheDocument()
  })

  it('oluşturma olayının panelinde fark yerine "İlk değerler" (snapshot) doğrudan açık gelir', async () => {
    api.monitoring.getRecentChanges.mockResolvedValue(reply([CREATED]))
    api.monitoring.getChangeDetail.mockResolvedValue({ success: true, data: { ...CREATED, snapshot: JSON.stringify({ url: 'https://www.example.com/', intervalSeconds: 300 }) } })
    render(<MonitorChangesConsole />)
    const row = (await screen.findByText('Yeni HTTP izlemesi')).closest('[data-chg-row]')
    fireEvent.click(row.querySelector('td'))
    const dlg = await screen.findByRole('dialog')
    await waitFor(() => expect(api.monitoring.getChangeDetail).toHaveBeenCalledWith('http', 31, 0))
    expect(await within(dlg).findByText('Initial values')).toBeInTheDocument()
    expect(within(dlg).getByText('https://www.example.com/', { selector: 'dd' })).toBeInTheDocument()
    expect(within(dlg).getByText('5 min', { selector: 'dd' })).toBeInTheDocument()
    expect(within(dlg).queryByRole('table')).toBeNull()   // fark tablosu yok
  })

  it('IP düğmesi panoya kopyalar ve 2 sn onay gösterir', async () => {
    render(<MonitorChangesConsole />)
    const btn = await screen.findByRole('button', { name: 'Copy IP address 10.20.30.40' })
    fireEvent.click(btn)
    await waitFor(() => expect(clip.copyText).toHaveBeenCalledWith('10.20.30.40'))
    expect(await screen.findByRole('button', { name: 'IP address copied' })).toBeInTheDocument()
    // Kopyalamak satırın ayrıntı panelini AÇMAZ
    expect(screen.queryByRole('dialog')).toBeNull()
  })

  it('yenile düğmesi listeyi yeniden ister', async () => {
    render(<MonitorChangesConsole />)
    await screen.findByText('Ödeme akışı')
    api.monitoring.getRecentChanges.mockClear()
    fireEvent.click(screen.getByRole('button', { name: 'Refresh' }))
    await waitFor(() => expect(api.monitoring.getRecentChanges).toHaveBeenCalledTimes(1))
  })
})

describe('MonitorChangesConsole — telefon kart listesi (useIsMobile)', () => {
  beforeEach(() => { mobile.on = true })

  it('TEK varyant: kartlar, tablo yok; üstte olay rozeti + kim + zaman, çipler (3 + "+N"), 40 px "Details" düğmesi paneli açar', async () => {
    render(<MonitorChangesConsole />)
    await screen.findByText('Ödeme akışı')
    expect(screen.queryByRole('table')).toBeNull()
    const cards = document.querySelectorAll('[data-slot="chg-rows"] [data-slot="card"]')
    expect(cards).toHaveLength(2)
    const card = rowOf('Ödeme akışı')
    expect(card.querySelector('[data-event="update"]')).toBeInTheDocument()
    expect(card.querySelector('[data-slot="user-badge"]')).toHaveTextContent('Ada Lovelace')
    expect(card.querySelector('time')).toHaveAttribute('datetime', '2026-08-22T14:03:11')
    expect(card.querySelectorAll('[data-chip]')).toHaveLength(3)
    expect(within(card).getByText('+2 more')).toBeInTheDocument()
    // İzleme adı bağlantı burada da
    expect(within(card).getByRole('link', { name: 'Ödeme akışı' })).toHaveAttribute('href', '?tab=scripted&monitor=12&mtab=changes')

    const details = within(card).getByRole('button', { name: 'Ödeme akışı — Details' })
    expect(details).toHaveClass('h-10')
    fireEvent.click(details)
    const dlg = await screen.findByRole('dialog')
    expect(within(dlg).getByText('1 min', { selector: '[data-diff="to"]' })).toBeInTheDocument()
  })

  it('süzgeçler "Filters" alt Sheet\'inde: etiketli seçiciler, seçim anında uygulanır, sayaç rozeti; "Clear filters" sıfırlar', async () => {
    api.admin.getTeams.mockResolvedValue({ success: true, data: [{ id: 5, name: 'Takım A' }, { id: 7, name: 'Takım B' }] })
    render(<MonitorChangesConsole />)
    await screen.findByText('Ödeme akışı')
    // Masaüstü seçicileri çizilmez
    expect(screen.queryByLabelText('Filter by monitor type')).toBeNull()

    fireEvent.click(screen.getByRole('button', { name: 'Filters' }))
    const dlg = await screen.findByRole('dialog')
    expect(within(dlg).getByRole('heading', { name: 'Filters' })).toBeInTheDocument()
    for (const label of ['Monitor type', 'Event', 'User', 'Team']) {
      expect(within(dlg).getByRole('combobox', { name: label })).toBeInTheDocument()
    }
    api.monitoring.getRecentChanges.mockClear()
    fireEvent.mouseDown(within(dlg).getByRole('combobox', { name: 'Event' }))
    fireEvent.mouseDown([...document.querySelectorAll('[role="option"]')].find(el => el.textContent.includes('Deleted')))
    await waitFor(() => expect(lastCall()).toMatchObject({ eventType: 'DELETE', page: 0 }))

    fireEvent.click(within(dlg).getByRole('button', { name: 'Show results' }))
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
    // Tetikte etkin sayısı (ekran okuyucuya "Active filters: 1") + etkin süzgeç rozeti
    expect(screen.getByRole('button', { name: /Filters.*Active filters: 1/ })).toBeInTheDocument()
    expect(document.querySelector('[data-filter-chip="event"]')).toHaveTextContent('Event: Deleted')

    fireEvent.click(screen.getByRole('button', { name: /Filters/ }))
    fireEvent.click(within(await screen.findByRole('dialog')).getByRole('button', { name: 'Clear filters' }))
    await waitFor(() => expect(lastCall()).toMatchObject({ eventType: '' }))
  })
})

describe('MonitorChangesConsole — süzgeçler ve etkin süzgeç rozetleri', () => {
  it('masaüstü seçicileri ad taşır; olay süzgeci sunucuya eventType olarak gider, sayfa başa döner, rozet X ile kalkar', async () => {
    render(<MonitorChangesConsole />)
    await screen.findByText('Ödeme akışı')
    expect(document.querySelector('[data-slot="chg-active-filters"]')).toBeNull()
    api.monitoring.getRecentChanges.mockClear()

    fireEvent.mouseDown(screen.getByLabelText('Filter by event type'))
    fireEvent.mouseDown([...document.querySelectorAll('[role="option"]')].find(el => el.textContent.includes('Deleted')))
    await waitFor(() => expect(lastCall()).toMatchObject({ eventType: 'DELETE', page: 0 }))

    const chip = document.querySelector('[data-filter-chip="event"]')
    expect(chip).toHaveTextContent('Event: Deleted')
    fireEvent.click(within(chip).getByRole('button', { name: 'Remove the “Event: Deleted” filter' }))
    await waitFor(() => expect(lastCall()).toMatchObject({ eventType: '' }))
    expect(document.querySelector('[data-filter-chip]')).toBeNull()
  })

  it('"Clear filters" HEPSİNİ sıfırlar: tür, olay, arama ve zaman aralığı; ekran URL\'e süzgeç YAZMAZ', async () => {
    const { container } = render(<MonitorChangesConsole />)
    await screen.findByText('Ödeme akışı')

    fireEvent.mouseDown(screen.getByLabelText('Filter by monitor type'))
    fireEvent.mouseDown([...document.querySelectorAll('[role="option"]')].find(el => el.textContent === 'Port'))
    fireEvent.change(screen.getByLabelText('Search by monitor name…'), { target: { value: 'ödeme' } })
    fireEvent.click(within(container.querySelector('[data-slot="chg-range"]')).getByText('7d'))
    await waitFor(() => expect(lastCall()).toMatchObject({ kind: 'port', q: 'ödeme' }))
    expect(lastCall().from).not.toBe('')
    expect(document.querySelector('[data-filter-chip="kind"]')).toHaveTextContent('Monitor type: Port')
    // Uygulama parametre ad alanı (tab/domain/monitor/incident) korunur: bu ekran URL'e hiçbir şey yazmaz
    expect(window.location.search).toBe('')

    fireEvent.click(screen.getByRole('button', { name: 'Clear filters' }))
    await waitFor(() => expect(lastCall()).toMatchObject({ kind: '', q: '', from: '', to: '', eventType: '' }))
    expect(document.querySelector('[data-slot="chg-active-filters"]')).toBeNull()
    expect(screen.getByLabelText('Search by monitor name…')).toHaveValue('')
  })

  it('serbest arama istek parametresine yansır', async () => {
    render(<MonitorChangesConsole />)
    await screen.findByText('Ödeme akışı')
    api.monitoring.getRecentChanges.mockClear()

    fireEvent.change(screen.getByLabelText('Search by monitor name…'), { target: { value: 'ödeme' } })

    await waitFor(() => expect(api.monitoring.getRecentChanges).toHaveBeenCalledWith(
      expect.objectContaining({ q: 'ödeme', page: 0 })))
  })
})

describe('MonitorChangesConsole — yükleme / boş / hata durumları', () => {
  it('ilk yüklemede Skeleton satırları (tablo iskeleti), veri gelince kaybolur', async () => {
    let resolve
    api.monitoring.getRecentChanges.mockReturnValue(new Promise(r => { resolve = r }))
    render(<MonitorChangesConsole />)
    expect(document.querySelectorAll('[data-skeleton]').length).toBeGreaterThan(0)
    expect(document.querySelector('[data-slot="skeleton"]')).not.toBeNull()
    resolve(reply([ROW]))
    await screen.findByText('Ödeme akışı')
    expect(document.querySelector('[data-skeleton]')).toBeNull()
  })

  it('süzgeç YOKKEN boş: "henüz kayıt yok" (temizle düğmesi yok); süzgeç VARKEN: "eşleşen yok" + Clear filters', async () => {
    api.monitoring.getRecentChanges.mockResolvedValue(reply([]))
    render(<MonitorChangesConsole />)
    const empty = await screen.findByText('No changes recorded yet')
    expect(empty.closest('[data-slot="empty"]')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Clear filters' })).toBeNull()

    fireEvent.change(screen.getByLabelText('Search by monitor name…'), { target: { value: 'yok' } })
    expect(await screen.findByText('No changes match your filters')).toBeInTheDocument()
    expect(screen.getByText(/Clear them and try again/)).toBeInTheDocument()
    // Boş durumun kendi eylemi (rozet satırındaki "Clear filters" ile aynı işi yapar)
    const noMatch = screen.getByText('No changes match your filters').closest('[data-slot="empty"]')
    fireEvent.click(within(noMatch).getByRole('button', { name: 'Clear filters' }))
    expect(await screen.findByText('No changes recorded yet')).toBeInTheDocument()
  })

  it('sunucu hatası uyarı bandı + "Try again"; konsol çökmez, boş durum ikinci kez "sonuç yok" demez', async () => {
    api.monitoring.getRecentChanges.mockResolvedValue({ success: false, error: 'yetkiniz yok' })
    render(<MonitorChangesConsole />)

    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent('yetkiniz yok')
    expect(document.querySelector('[data-slot="empty"]')).toBeNull()
    api.monitoring.getRecentChanges.mockResolvedValue(reply([ROW]))
    fireEvent.click(within(alert).getByRole('button', { name: 'Try again' }))
    expect(await screen.findByText('Ödeme akışı')).toBeInTheDocument()
    expect(screen.queryByRole('alert')).toBeNull()
  })

  it('BOZUK changes alanı satırı düşürmez — künye okunmaya devam eder', async () => {
    api.monitoring.getRecentChanges.mockResolvedValue(reply([{ ...ROW, changes: '{bozuk' }]))
    render(<MonitorChangesConsole />)

    expect(await screen.findByText('Ödeme akışı')).toBeInTheDocument()
    expect(document.querySelector('[data-chip]')).toBeNull()
  })

  it('adı olmayan kaynak kimliğiyle gösterilir (silinmiş kayıt boş satır olmaz)', async () => {
    api.monitoring.getRecentChanges.mockResolvedValue(reply([{ ...DELETED, resource_name: null }]))
    render(<MonitorChangesConsole />)

    const row = (await screen.findByText('#7')).closest('[data-chg-row]')
    expect(within(row).getByText('Deleted')).toBeInTheDocument()
  })
})

describe('MonitorChangesConsole — özet şeridi ve tür kartları (olay/tür süzgeci)', () => {
  it('özet şeridi PENCERENİN tamamını özetler, sayfalanan listeyi değil', async () => {
    const { container } = render(<MonitorChangesConsole />)
    await screen.findByText('Ödeme akışı')
    openStats(container)

    // Toplam = olay sayaçlarının toplamı (4+11+2), sunucunun sayfa "total"i (2) DEĞİL.
    const values = [...container.querySelectorAll('[data-slot="stat-value"]')].map(n => n.textContent)
    expect(values).toEqual(['17', '4', '11', '2'])
    expect(container.querySelectorAll('[data-slot="stats-panel"] [data-slot="stat-item"]')).toHaveLength(4)
  })

  it('özet kartı olay süzgecidir — Silinen kartı eventType=DELETE + rozet; tekrar tıklayınca süzgeç kalkar', async () => {
    const { container } = render(<MonitorChangesConsole />)
    await screen.findByText('Ödeme akışı')
    openStats(container)
    const del = container.querySelector('[data-slot="stat-item"][data-tone="critical"]')
    expect(del).toHaveAttribute('aria-pressed', 'false')
    fireEvent.click(del)
    await waitFor(() => expect(lastCall()).toMatchObject({ eventType: 'DELETE', page: 0 }))
    expect(container.querySelector('[data-slot="stat-item"][data-tone="critical"]')).toHaveAttribute('aria-pressed', 'true')
    expect(document.querySelector('[data-filter-chip="event"]')).toHaveTextContent('Event: Deleted')
    fireEvent.click(container.querySelector('[data-slot="stat-item"][data-tone="critical"]'))
    await waitFor(() => expect(lastCall()).toMatchObject({ eventType: '' }))
  })

  it('her tür için toplam ve olay kırılımı gösterir, hareketsiz türü ÇİZMEZ', async () => {
    const { container } = render(<MonitorChangesConsole />)
    await screen.findByText('Ödeme akışı')
    openStats(container)

    const cards = [...container.querySelectorAll('[data-kind-card]')]
    // "Tümü" + Sentetik + Port = 3; DNS'in hiç kaydı yok, kart üretmez (11 boş kutu gürültüdür).
    expect(cards).toHaveLength(3)
    expect(within(cards[0]).getByText('15')).toBeInTheDocument()      // genel toplam 12 + 3
    expect(within(cards[1]).getByText('Synthetic')).toBeInTheDocument()
    expect(within(cards[1]).getByText('12')).toBeInTheDocument()
    expect(within(cards[1]).getByText('3')).toBeInTheDocument()       // CREATE
    expect(within(cards[1]).getByText('8')).toBeInTheDocument()       // UPDATE
    expect(within(cards[1]).getByText('1')).toBeInTheDocument()       // DELETE
    // PORT kartında DELETE yok → 3 değil 2 çip.
    const portCard = cards.find(c => c.textContent.includes('Port'))
    expect(portCard.querySelectorAll('[data-kind-chip]')).toHaveLength(2)
  })

  it('karta tıklamak listeyi o türe daraltır (rozet çıkar), tekrar tıklamak açar; "Tümü" kartı temizler', async () => {
    const { container } = render(<MonitorChangesConsole />)
    await screen.findByText('Ödeme akışı')
    openStats(container)
    api.monitoring.getRecentChanges.mockClear()

    const cards = () => [...container.querySelectorAll('[data-kind-card]')]
    const portCard = () => cards().find(c => c.textContent.includes('Port'))
    fireEvent.click(portCard())
    await waitFor(() => expect(lastCall()).toMatchObject({ kind: 'port', page: 0 }))
    expect(portCard()).toHaveAttribute('aria-pressed', 'true')
    expect(document.querySelector('[data-filter-chip="kind"]')).toHaveTextContent('Monitor type: Port')

    fireEvent.click(portCard())
    await waitFor(() => expect(lastCall()).toMatchObject({ kind: '' }))

    fireEvent.click(portCard())
    await waitFor(() => expect(cards()[0]).toHaveAttribute('aria-pressed', 'false'))
    fireEvent.click(cards()[0])
    await waitFor(() => expect(lastCall()).toMatchObject({ kind: '' }))
  })

  it('hiç kayıt yoksa kart ızgarası çizilmez (boş kutular gösterilmez)', async () => {
    api.monitoring.getRecentChanges.mockResolvedValue(reply([], { kind_counts: {} }))
    const { container } = render(<MonitorChangesConsole />)
    await screen.findByText('No changes recorded yet')
    openStats(container)
    expect(container.querySelector('[data-slot="change-kind-grid"]')).toBeNull()
  })

  it('kartlar VARSAYILAN KAPALI; başlık açar/kapatır; tür/olay süzgeci kapalıyken de araç çubuğunda erişilebilir', async () => {
    const { container } = render(<MonitorChangesConsole />)
    await screen.findByText('Ödeme akışı')

    const toggle = container.querySelector('[data-slot="stats-toggle"]')
    expect(toggle).toHaveAttribute('aria-expanded', 'false')
    expect(container.querySelectorAll('[data-kind-card]')).toHaveLength(0)
    expect(container.querySelectorAll('[data-slot="stat-value"]')).toHaveLength(0)
    expect(container.querySelector('[data-slot="chg-filters"]')).toBeInTheDocument()
    expect(screen.getByLabelText('Filter by monitor type')).toBeInTheDocument()

    openStats(container)
    expect(container.querySelectorAll('[data-slot="stat-value"]')).toHaveLength(4)
    expect(container.querySelectorAll('[data-kind-card]').length).toBeGreaterThan(0)
    expect(toggle).toHaveAttribute('aria-expanded', 'true')
    openStats(container)
    expect(container.querySelectorAll('[data-kind-card]')).toHaveLength(0)
  })

  it('PAGESPEED kartı çevrilmiş adla çizilir, ham anahtarla değil', async () => {
    api.monitoring.getRecentChanges.mockResolvedValue(
      reply([ROW], { kind_counts: { PAGESPEED: { CREATE: 1, UPDATE: 2 } } }))
    const { container } = render(<MonitorChangesConsole />)
    await screen.findByText('Ödeme akışı')
    openStats(container)

    const card = [...container.querySelectorAll('[data-kind-card]')]
      .find(c => c.textContent.includes('Page Speed'))
    expect(card, 'PAGESPEED karti cizilmedi').toBeTruthy()
    expect(container.textContent).not.toContain('chg.kind.')
  })
})

/**
 * Zaman aralığı — kullanıcı isteği (2026-08-22): 7/15/30/45/60/90 + özel tarih. Hassas noktalar: (1) aralık YEREL
 * saatle kurulmalı — toISOString() UTC'ye kaydırır; (2) sayaçlar/kartlar da aralığı uygulamalı.
 */
describe('MonitorChangesConsole — zaman aralığı', () => {
  const seg = (container) => container.querySelector('[data-slot="chg-range"]')
  const pick = (container, label) => fireEvent.click(within(seg(container)).getByText(label))

  /** Gönderilen sınır UTC'dir ('Z' eklenerek Date'e çevrilir); yerel saatle gönderilseydi "bugün" 3 saat geç başlardı. */
  const asInstant = (iso) => {
    expect(iso).toMatch(/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}$/)   // saniye hassasiyeti, Z'siz
    return new Date(iso + 'Z')
  }

  it('varsayılan TÜM zaman: tarih süzgeci gönderilmez', async () => {
    render(<MonitorChangesConsole />)
    await screen.findByText('Ödeme akışı')
    expect(lastCall()).toMatchObject({ from: '', to: '' })
  })

  it('gün pencereleri (7/15/30/45/60/90) doğru başlangıcı gönderir', async () => {
    const { container } = render(<MonitorChangesConsole />)
    await screen.findByText('Ödeme akışı')

    for (const days of [7, 15, 30, 45, 60, 90]) {
      api.monitoring.getRecentChanges.mockClear()
      pick(container, `${days}d`)
      await waitFor(() => expect(api.monitoring.getRecentChanges).toHaveBeenCalled())

      const { from, to } = lastCall()
      const start = asInstant(from)
      // "Son N gün" bugünü DÂHİL sayar → başlangıç (N-1) gün önceki günün 00:00'ı.
      const expected = new Date()
      expected.setDate(expected.getDate() - (days - 1))
      expected.setHours(0, 0, 0, 0)
      expect(start.getTime()).toBe(expected.getTime())
      expect(to).toBe('')                       // açık uçlu: "şimdiye kadar"
    }
  })

  it('Bugün seçimi günün 00:00\'ını gönderir (UTC kaymasi YOK)', async () => {
    const { container } = render(<MonitorChangesConsole />)
    await screen.findByText('Ödeme akışı')
    api.monitoring.getRecentChanges.mockClear()

    pick(container, 'Today')
    await waitFor(() => expect(api.monitoring.getRecentChanges).toHaveBeenCalled())

    const start = asInstant(lastCall().from)
    const midnight = new Date()
    midnight.setHours(0, 0, 0, 0)
    expect(start.getTime()).toBe(midnight.getTime())
  })

  it('Tümü seçimi tarih süzgecini temizler', async () => {
    const { container } = render(<MonitorChangesConsole />)
    await screen.findByText('Ödeme akışı')

    pick(container, '30d')
    await waitFor(() => expect(lastCall().from).not.toBe(''))

    pick(container, 'All')
    await waitFor(() => expect(lastCall()).toMatchObject({ from: '', to: '' }))
  })

  it('Özel seçilince tarih aralığı seçici (Calendar + Popover) açılır', async () => {
    const { container } = render(<MonitorChangesConsole />)
    await screen.findByText('Ödeme akışı')

    expect(container.querySelector('[data-slot="date-picker-trigger"]')).toBeNull()
    pick(container, 'Custom')
    expect(container.querySelectorAll('[data-slot="date-picker-trigger"]').length).toBeGreaterThan(0)
  })

  it('Tümü seçiliyken Özele geçince seçicinin varsayılanı HEMEN uygulanır', async () => {
    const { container } = render(<MonitorChangesConsole />)
    await screen.findByText('Ödeme akışı')
    expect(lastCall()).toMatchObject({ from: '', to: '' })

    pick(container, 'Custom')

    await waitFor(() => expect(lastCall().from).not.toBe(''))
    const start = asInstant(lastCall().from)
    const expected = new Date()
    expected.setDate(expected.getDate() - 29)
    expected.setHours(0, 0, 0, 0)
    expect(start.getTime()).toBe(expected.getTime())
    expect(lastCall().to).not.toBe('')
  })

  it('AKTİF bir pencereden Özele geçince aralık DEĞİŞMEZ', async () => {
    const { container } = render(<MonitorChangesConsole />)
    await screen.findByText('Ödeme akışı')

    pick(container, '90d')
    await waitFor(() => expect(lastCall().from).not.toBe(''))
    const before = lastCall().from

    pick(container, 'Custom')
    expect(lastCall().from).toBe(before)
  })

  it('aralık değişince sayfa BAŞA döner (3. sayfada 90 gün seçip boş liste görmeyelim)', async () => {
    api.monitoring.getRecentChanges.mockResolvedValue(reply([ROW, DELETED], { total: 300 }))
    const { container } = render(<MonitorChangesConsole />)
    await screen.findByText('Ödeme akışı')

    fireEvent.click(screen.getByRole('button', { name: /next|sonraki|›/i }))
    await waitFor(() => expect(lastCall().page).toBe(1))

    pick(container, '15d')
    await waitFor(() => expect(lastCall().page).toBe(0))
  })
})

/**
 * Takim suzgeci (2026-08-27): ekran TUM takim kullanicilarina acik; kapsam ucta (viewTeamIds), burasi SUNUM.
 */
describe('MonitorChangesConsole — takim suzgeci', () => {
  const twoTeams = () => api.admin.getTeams.mockResolvedValue(
    { success: true, data: [{ id: 5, name: 'Takım A' }, { id: 7, name: 'Takım B' }] })
  const oneTeam = () => api.admin.getTeams.mockResolvedValue(
    { success: true, data: [{ id: 5, name: 'Takım A' }] })

  it('TEK takim goren kullanicida secici HIC cizilmez', async () => {
    oneTeam()
    render(<MonitorChangesConsole />)
    await screen.findByText('Ödeme akışı')
    await waitFor(() => expect(api.admin.getTeams).toHaveBeenCalled())
    expect(screen.queryByLabelText('Filter by team')).toBeNull()
  })

  it('COK takim gorulunce secici cizilir; secim teamId olarak istege girer, sayfa basa doner, rozet cikar', async () => {
    twoTeams()
    render(<MonitorChangesConsole />)
    await screen.findByText('Ödeme akışı')

    // SearchableSelect yerli <select> degil: tetigi mouseDown ile ac, secenegi mouseDown ile sec.
    const trigger = await screen.findByLabelText('Filter by team')
    api.monitoring.getRecentChanges.mockClear()
    fireEvent.mouseDown(trigger)
    fireEvent.mouseDown([...document.querySelectorAll('[role="option"]')]
      .find(el => el.textContent.includes('Takım B')))

    await waitFor(() => expect(lastCall().teamId).toBe('7'))
    expect(lastCall().page).toBe(0)
    expect(document.querySelector('[data-filter-chip="team"]')).toHaveTextContent('Team: Takım B')
  })

  it('takim secilmemisken teamId istege HIC konmaz', async () => {
    twoTeams()
    render(<MonitorChangesConsole />)
    await screen.findByText('Ödeme akışı')
    expect(lastCall()).not.toHaveProperty('teamId')
  })

  it('takim ucu PATLARSA konsol cokmez, liste calisir', async () => {
    api.admin.getTeams.mockRejectedValue(new Error('403'))
    render(<MonitorChangesConsole />)
    expect(await screen.findByText('Ödeme akışı')).toBeInTheDocument()
    expect(screen.queryByLabelText('Filter by team')).toBeNull()
  })
})

describe('MonitorChangesConsole — kapsam notu', () => {
  it('takim kullanicisina gosterilir', async () => {
    render(<MonitorChangesConsole globalViewer={false} />)
    await screen.findByText('Ödeme akışı')
    expect(screen.getByText(/Only changes for the teams you can see/)).toBeInTheDocument()
  })

  it('global yoneticide gosterilmez (onun icin dogru degil)', async () => {
    render(<MonitorChangesConsole globalViewer />)
    await screen.findByText('Ödeme akışı')
    expect(screen.queryByText(/Only changes for the teams you can see/)).toBeNull()
  })
})
