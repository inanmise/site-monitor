import { render, screen, fireEvent, waitFor, within, act } from './test-utils.jsx'
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
// Telefon kipi (jsdom medya sorgusu görmez → kanca mock'lanır)
const mobile = vi.hoisted(() => ({ on: false }))
vi.mock('../hooks/use-mobile.js', () => ({ useIsMobile: () => mobile.on }))
// Pano: jsdom'da clipboard yok — kopyalama yardımcısı taklit edilir
const clip = vi.hoisted(() => ({ copyText: vi.fn(() => Promise.resolve(true)) }))
vi.mock('../utils/copyText.js', () => ({ copyText: clip.copyText }))
// CSV indirme: gerçek toCsv (kaçış + formül nötrleme), indirme casus
const dl = vi.hoisted(() => ({ downloadCsv: vi.fn() }))
vi.mock('../utils/csvExport.js', async (importOriginal) => ({ ...(await importOriginal()), downloadCsv: dl.downloadCsv }))

import { api } from '../api/client'
import MonitorChangesConsole from '../components/admin/MonitorChangesConsole.jsx'

/** Sunucu damgası biçimi: UTC, saniye hassasiyeti, Z'siz (MonitorChangeLog.createdAt). */
const iso = (ms) => new Date(ms).toISOString().slice(0, 19)

const ROW = {
  kind: 'SCRIPTED', resource_id: 12, resource_name: 'Ödeme akışı', seq: 3, event_type: 'UPDATE',
  team_id: 5, team_name: 'Takım A', actor: 'N23456', actor_name: 'Ada Lovelace',
  ip_address: '10.20.30.40', user_agent: 'Mozilla/5.0 (Windows NT 10.0) Chrome/126.0.0.0',
  at: '2026-08-22T14:03:11', note: 'Zaman aşımı yetmiyordu', resource_deleted: false,
  changes: JSON.stringify({
    intervalSeconds: { from: 300, to: 60 },
    timeoutMs: { from: 30000, to: 45000 },
    // DİKKAT: çip değerleri satır ADIYLA çakışmamalı — yoksa sorgular iki öğe bulur.
    name: { from: 'Eski ad', to: 'Yeni ad' },
    expectedStatus: { from: 200, to: 301 },
    url: { from: 'a', to: 'b' },
  }),
}
const DELETED = {
  ...ROW, kind: 'PORT', resource_id: 7, resource_name: 'Eski port', seq: 9,
  event_type: 'DELETE', changes: null, note: null, at: '2026-08-22T15:00:00', actor: null, actor_name: null,
  ip_address: null, user_agent: null,   // zamanlanmış/geri doldurma satırı: oturum yok → IP yok
  // Gerçek tel biçimi: izleme hâlâ silinmişse sunucu silme satırında da true döner (son geçmiş olayı DELETE)
  resource_deleted: true,
}
const CREATED = {
  ...ROW, kind: 'HTTP', resource_id: 31, resource_name: 'Yeni HTTP izlemesi', seq: 0,
  event_type: 'CREATE', changes: null, note: null, at: '2026-08-22T16:00:00',
}

const KIND_COUNTS = {
  SCRIPTED: { CREATE: 3, UPDATE: 8, DELETE: 1 },
  PORT: { CREATE: 1, UPDATE: 2 },
  DNS: { CREATE: 0, UPDATE: 0 },        // hiç hareket yok → kart ÇİZİLMEZ
}

/** `/changes/summary` gerçek tel biçimi (snake_case). */
const SUMMARY = {
  total: 17,
  event_counts: { CREATE: 4, UPDATE: 11, DELETE: 2, PAUSE: 3, RESUME: 1 },
  kind_counts: KIND_COUNTS,
  daily_since: null, daily: [],
  top_resources: [
    // Adlar liste satırlarından FARKLI: `getByText('Ödeme akışı')` kartlarla çakışmasın
    { kind: 'SCRIPTED', resource_id: 12, resource_name: 'Sepet akışı', team_id: 5, team_name: 'Takım A', count: 9, deleted: false },
    { kind: 'PORT', resource_id: 7, resource_name: 'Kapanan port', team_id: 5, team_name: 'Takım A', count: 4, deleted: true },
  ],
  actors: [
    { actor: 'N23456', actor_id: 41, actor_name: 'Ada Lovelace', count: 12 },
    { actor: 'N11111', actor_id: 42, actor_name: 'Ad Soyad', count: 3 },
  ],
}

function reply(rows, extra = {}) {
  return { success: true, data: { changes: rows, total: rows.length, page: 0, size: 25, ...extra } }
}

const lastCall = () => api.monitoring.getRecentChanges.mock.calls.at(-1)[0]
const lastSummary = () => api.monitoring.getChangeSummary.mock.calls.at(-1)[0]
/** Satır kabı — zaman çizelgesi öğesi (`li[data-chg-row]`). */
const rowOf = (text) => screen.getByText(text).closest('[data-chg-row]')
const openDetail = (name) => fireEvent.click(screen.getByRole('button', { name: `${name} — Details` }))
const kpi = (key) => document.querySelector(`[data-kpi="${key}"]`)
const params = () => new URLSearchParams(window.location.search)
/** Bir sözü (promise) dışarıdan çözmek için. */
function deferred() {
  let resolve
  const promise = new Promise(r => { resolve = r })
  return { promise, resolve }
}

beforeEach(() => {
  vi.clearAllMocks()
  mobile.on = false
  // URL testler arasında TAŞINMASIN (konsol ch_* anahtarlarını okur ve yazar)
  window.history.replaceState(null, '', '/?tab=monitorchanges')
  api.monitoring.getRecentChanges.mockResolvedValue(reply([ROW, DELETED]))
  api.monitoring.getChangeSummary.mockResolvedValue({ success: true, data: SUMMARY })
  api.monitoring.getChangeDetail.mockResolvedValue({ success: true, data: { ...ROW, snapshot: JSON.stringify({ name: 'Yeni ad', intervalSeconds: 60, active: true }) } })
  // Varsayılan: takım listesi BOŞ → seçici çizilmez. Süzgeci sınayan testler kendi verisini kurar.
  api.admin.getTeams.mockResolvedValue({ success: true, data: [] })
})
afterEach(() => { mobile.on = false })

/** Tür kırılımı VARSAYILAN KAPALI (katlanır bölüm) — sınayan testler önce açar. */
function openKinds(container) {
  fireEvent.click(container.querySelector('[data-slot="stats-toggle"]'))
}

describe('MonitorChangesConsole — zaman çizelgesi', () => {
  it('tüm türlerdeki değişiklikleri tek listede, künyesiyle gösterir: olay rozeti, kim, tür · takım, zaman, IP', async () => {
    render(<MonitorChangesConsole />)
    await screen.findByText('Ödeme akışı')

    // Tek liste — eski tablo/kart ikilisi yok
    expect(screen.queryByRole('table')).toBeNull()
    expect(document.querySelectorAll('[data-slot="chg-timeline"]')).toHaveLength(1)
    expect(document.querySelectorAll('[data-chg-row]')).toHaveLength(2)
    // Tür + takım aynı künyede: "nerede" sorusunun cevabı. Takım adı tıklanabilir rozet (ayrı element).
    expect([...document.querySelectorAll('[data-slot="chg-row-meta"]')].some(e => /Synthetic\s*·\s*Takım A/.test(e.textContent))).toBe(true)
    // Olay rozeti renkli Badge (data-event + data-tone) — sol renk şeridi YOK
    const del = rowOf('Eski port')
    expect(del.querySelector('[data-slot="badge"][data-event="delete"]')).toHaveAttribute('data-tone', 'danger')
    expect(rowOf('Ödeme akışı').querySelector('[data-event="update"]')).toHaveTextContent('Updated')
    // Kim: avatarlı kullanıcı rozeti; aktörsüz satır "SYSTEM" rozeti
    expect(rowOf('Ödeme akışı').querySelector('[data-slot="user-badge"]')).toHaveTextContent('Ada Lovelace')
    expect(within(del).getAllByText('SYSTEM').length).toBeGreaterThan(0)
    // Zaman: makine-okunur tam değer <time dateTime>; tam tarih-saat dokun-gör balonunda (düğme)
    expect(rowOf('Ödeme akışı').querySelector('time')).toHaveAttribute('datetime', '2026-08-22T14:03:11')
    expect(rowOf('Ödeme akışı').querySelector('[data-slot="chg-time"]').tagName).toBe('BUTTON')
    // IP kopyalanabilir düğme (adı IP'yi taşır — satırlar ayırt edilir)
    expect(screen.getByRole('button', { name: 'Copy IP address 10.20.30.40' })).toBeInTheDocument()
    // Değişiklik nedeni satırda
    expect(within(rowOf('Ödeme akışı')).getByText('Zaman aşımı yetmiyordu')).toBeInTheDocument()
    // Sol renk şeridi/kenarı YOK (kullanıcı kuralı 2026-09-26)
    expect(document.querySelector('[data-slot="chg-timeline"] [class*="border-l-"]')).toBeNull()
  })

  it('satırda en fazla 3 alan çipi, kalanı "+N more"; "Show changes" farkı satır İÇİNDE açar', async () => {
    render(<MonitorChangesConsole />)
    const row = (await screen.findByText('Ödeme akışı')).closest('[data-chg-row]')

    expect(row.querySelectorAll('[data-chip]')).toHaveLength(3)
    expect(within(row).getByText('+2 more')).toHaveAttribute('data-slot', 'badge')
    expect(row.querySelector('[data-chip="intervalSeconds"]')).toHaveTextContent('Check frequency5 min1 min')
    // Diff'i olmayan satır (silme) çip ve "farkı göster" düğmesi çizmez
    expect(rowOf('Eski port').querySelectorAll('[data-chip]')).toHaveLength(0)
    expect(rowOf('Eski port').querySelector('[data-diff-toggle]')).toBeNull()

    const toggle = within(row).getByRole('button', { name: 'Show changes (5)' })
    expect(toggle).toHaveAttribute('aria-expanded', 'false')
    fireEvent.click(toggle)
    const diff = row.querySelector('[data-slot="chg-diff"]')
    expect(diff).not.toBeNull()
    expect(diff.querySelectorAll('[data-diff-row]')).toHaveLength(5)
    expect(within(diff).getByText('1 min', { selector: '[data-diff="to"]' })).toBeInTheDocument()
    expect(within(row).getByRole('button', { name: 'Hide changes' })).toHaveAttribute('aria-expanded', 'true')
    // Satır içi fark ayrıntı panelini AÇMAZ
    expect(screen.queryByRole('dialog')).toBeNull()
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

  it('izlemesi SONRADAN silinmiş güncelleme satırı: ölü bağlantı yok, "deleted" rozeti', async () => {
    api.monitoring.getRecentChanges.mockResolvedValue(reply([{ ...ROW, resource_deleted: true }]))
    render(<MonitorChangesConsole />)
    const row = (await screen.findByText('Ödeme akışı')).closest('[data-chg-row]')
    expect(within(row).queryByRole('link')).toBeNull()
    expect(row.querySelector('[data-slot="chg-deleted"]')).toHaveTextContent('deleted')
    // Olay yine güncelleme — silinme rozeti ek bilgidir
    expect(row.querySelector('[data-event="update"]')).toBeInTheDocument()
    // Ayrıntı panelinde de "İzlemeye git" yok
    openDetail('Ödeme akışı')
    const dlg = await screen.findByRole('dialog')
    expect(within(dlg).queryByRole('link', { name: /Go to monitor/ })).toBeNull()
    expect(dlg.querySelector('[data-slot="chg-deleted"]')).toBeInTheDocument()
  })

  // 2026-09-28 regresyon B1: silinip GERİ YÜKLENEN izleme — sunucu `resource_deleted: false` der (son olayı geri yükleme).
  it('silinip geri yüklenen izleme: silme satırı bile bağlantı taşır, "deleted" rozeti yok; CSV "Deleted" yalnız hâlâ silinmişte', async () => {
    const restoredDelete = { ...DELETED, resource_deleted: false }
    const restored = { ...DELETED, resource_name: 'Geri gelen port', seq: 10, event_type: 'RESTORE', at: '2026-08-22T16:00:00', resource_deleted: false }
    const stillGone = { ...DELETED, resource_id: 8, resource_name: 'Kapalı port', seq: 4, event_type: 'UPDATE', at: '2026-08-22T13:00:00', resource_deleted: true }
    api.monitoring.getRecentChanges.mockResolvedValue(reply([restored, restoredDelete, stillGone]))
    render(<MonitorChangesConsole />)
    expect(await screen.findByRole('link', { name: 'Eski port' })).toHaveAttribute('href', '?tab=port&monitor=7&mtab=changes')
    expect(screen.getByRole('link', { name: 'Geri gelen port' })).toBeInTheDocument()
    expect(rowOf('Eski port').querySelector('[data-slot="chg-deleted"]')).toBeNull()
    expect(rowOf('Geri gelen port').querySelector('[data-slot="chg-deleted"]')).toBeNull()
    // Kontrol grubu: hâlâ silinmiş izlemenin satırı rozetli ve bağlantısız
    expect(rowOf('Kapalı port').querySelector('[data-slot="chg-deleted"]')).toHaveTextContent('deleted')
    expect(screen.queryByRole('link', { name: 'Kapalı port' })).toBeNull()

    fireEvent.click(screen.getByRole('button', { name: 'Download as CSV' }))
    await waitFor(() => expect(dl.downloadCsv).toHaveBeenCalledTimes(1))
    const [, csv] = dl.downloadCsv.mock.calls[0]
    const yes = csv.split('\r\n').filter(l => /(^|,)"?Yes"?(,|$)/.test(l))
    expect(yes).toHaveLength(1)
    expect(yes[0]).toContain('Kapalı port')
  })

  it('duraklatma / sürdürme: active alanını çeviren satır ek rozet taşır', async () => {
    const paused = { ...ROW, seq: 4, resource_name: 'Duraklatılan', changes: JSON.stringify({ active: { from: true, to: false } }) }
    const resumed = { ...ROW, seq: 5, resource_name: 'Sürdürülen', changes: JSON.stringify({ active: { from: false, to: true } }) }
    api.monitoring.getRecentChanges.mockResolvedValue(reply([paused, resumed]))
    render(<MonitorChangesConsole />)
    await screen.findByText('Duraklatılan')
    expect(rowOf('Duraklatılan').querySelector('[data-event="pause"]')).toHaveTextContent('Paused')
    expect(rowOf('Duraklatılan').querySelector('[data-event="pause"]')).toHaveAttribute('data-tone', 'warning')
    expect(rowOf('Sürdürülen').querySelector('[data-event="resume"]')).toHaveTextContent('Resumed')
    expect(rowOf('Sürdürülen').querySelector('[data-event="pause"]')).toBeNull()
  })

  it('IP düğmesi panoya kopyalar ve 2 sn onay gösterir; ayrıntı panelini açmaz', async () => {
    render(<MonitorChangesConsole />)
    const btn = await screen.findByRole('button', { name: 'Copy IP address 10.20.30.40' })
    fireEvent.click(btn)
    await waitFor(() => expect(clip.copyText).toHaveBeenCalledWith('10.20.30.40'))
    expect(await screen.findByRole('button', { name: 'IP address copied' })).toBeInTheDocument()
    expect(screen.queryByRole('dialog')).toBeNull()
  })

  it('yenile düğmesi listeyi VE özeti yeniden ister', async () => {
    render(<MonitorChangesConsole />)
    await screen.findByText('Ödeme akışı')
    api.monitoring.getRecentChanges.mockClear()
    api.monitoring.getChangeSummary.mockClear()
    fireEvent.click(screen.getByRole('button', { name: 'Refresh' }))
    await waitFor(() => expect(api.monitoring.getRecentChanges).toHaveBeenCalledTimes(1))
    expect(api.monitoring.getChangeSummary).toHaveBeenCalledTimes(1)
  })
})

describe('MonitorChangesConsole — güne göre gruplama (tarihler şimdiden türetilir)', () => {
  it('Bugün / Dün / tarih başlıkları, yerel güne göre, sırayı koruyarak; başlıkta adet', async () => {
    const now = Date.now()
    const y = new Date(now); y.setDate(y.getDate() - 1); y.setHours(12, 0, 0, 0)
    const old = new Date(now); old.setDate(old.getDate() - 3); old.setHours(12, 0, 0, 0)
    const rows = [
      { ...ROW, seq: 20, resource_name: 'Bugünkü A', at: iso(now) },
      { ...ROW, seq: 19, resource_name: 'Bugünkü B', at: iso(now - 1000) },
      { ...ROW, seq: 18, resource_name: 'Dünkü', at: iso(y.getTime()) },
      { ...ROW, seq: 17, resource_name: 'Eskisi', at: iso(old.getTime()) },
    ]
    api.monitoring.getRecentChanges.mockResolvedValue(reply(rows))
    render(<MonitorChangesConsole />)
    await screen.findByText('Bugünkü A')

    const days = [...document.querySelectorAll('[data-slot="chg-day"]')]
    expect(days).toHaveLength(3)
    const pad = (n) => String(n).padStart(2, '0')
    const key = (d) => `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`
    expect(days.map(d => d.getAttribute('data-day'))).toEqual([key(new Date(now)), key(y), key(old)])
    const heads = days.map(d => within(d).getByRole('heading', { level: 3 }))
    expect(heads[0]).toHaveTextContent(/^Today/)
    expect(heads[0]).toHaveTextContent('2 changes')
    expect(heads[1]).toHaveTextContent(/^Yesterday/)
    expect(heads[1]).toHaveTextContent('1 change')
    const expected = new Intl.DateTimeFormat('en-GB', { weekday: 'long', day: 'numeric', month: 'long',
      ...(old.getFullYear() === new Date(now).getFullYear() ? {} : { year: 'numeric' }) }).format(old)
    expect(heads[2]).toHaveTextContent(expected)
    // Satırlar kendi gününün altında, sıra korunur
    expect([...days[0].querySelectorAll('[data-chg-row]')].map(r => r.textContent.includes('Bugünkü A'))).toEqual([true, false])
    expect(within(days[2]).getByText('Eskisi')).toBeInTheDocument()
    // Başlık yapışkan; telefonda üst çubuğun (h-14) altında
    expect(heads[0].className).toMatch(/sticky/)
    expect(heads[0].className).toMatch(/top-14/)
  })
})

describe('MonitorChangesConsole — alan farkı (değer türüne göre)', () => {
  const TYPED = {
    ...ROW, seq: 30, resource_name: 'Tür karması',
    changes: JSON.stringify({
      verifySsl: { from: false, to: true },
      intervalSeconds: { from: 300, to: 3600 },
      tags: { from: ['prod', 'web'], to: ['prod', 'api'] },
      headers: { from: { 'X-A': '1' }, to: { 'X-A': '2' } },
      basicAuthPassEnc: { from: '***', to: '***' },
      keyword: { from: null, to: 'ödeme' },
      script: { from: 'line1\nline2\nline3', to: 'line1\nline2 changed\nline3' },
      description: { from: 'x'.repeat(10), to: 'y'.repeat(220) },
      teamId: { from: 2, to: 1 },
    }),
  }

  async function openTyped() {
    api.monitoring.getRecentChanges.mockResolvedValue(reply([TYPED]))
    render(<MonitorChangesConsole />)
    await screen.findByText('Tür karması')
    fireEvent.click(within(rowOf('Tür karması')).getByRole('button', { name: /Show changes/ }))
    return rowOf('Tür karması').querySelector('[data-slot="chg-diff"]')
  }
  const cell = (diff, key, side) => diff.querySelector(`[data-diff-row="${key}"] [data-diff="${side}"]`)

  it('boolean açık/kapalı rozeti, süre insancıl, gizli değer kilitli, boş değer "empty"', async () => {
    const diff = await openTyped()
    expect(cell(diff, 'verifySsl', 'from').querySelector('[data-bool="off"]')).toHaveTextContent('Off')
    expect(cell(diff, 'verifySsl', 'to').querySelector('[data-bool="on"]')).toHaveTextContent('On')
    expect(cell(diff, 'intervalSeconds', 'from')).toHaveTextContent('5 min')
    expect(cell(diff, 'intervalSeconds', 'to')).toHaveTextContent('1 hr')
    expect(cell(diff, 'basicAuthPassEnc', 'to')).toHaveTextContent('hidden value')
    expect(cell(diff, 'basicAuthPassEnc', 'to')).not.toHaveTextContent('***')
    expect(cell(diff, 'keyword', 'from')).toHaveTextContent('empty')
    expect(cell(diff, 'keyword', 'to')).toHaveTextContent('ödeme')
    // Takım kimliği: dizinde yoksa "#id" (boş kutu değil)
    expect(cell(diff, 'teamId', 'from')).toHaveTextContent('#2')
  })

  it('liste farkı eklenen/çıkan öğeleri ayırır; nesne biçimli JSON; çok satırlı metin satır farkı', async () => {
    const diff = await openTyped()
    const tags = diff.querySelector('[data-diff-row="tags"]')
    expect(tags).toHaveAttribute('data-mode', 'list')
    expect(tags.querySelector('[data-list="removed"]')).toHaveTextContent('web')
    expect(tags.querySelector('[data-list="added"]')).toHaveTextContent('api')
    expect(tags.querySelector('[data-list="kept"]')).toHaveTextContent('prod')

    expect(cell(diff, 'headers', 'to').querySelector('pre').textContent).toContain('"X-A": "2"')

    const script = diff.querySelector('[data-diff-row="script"]')
    expect(script).toHaveAttribute('data-mode', 'lines')
    expect(script.querySelector('[data-line="del"]')).toHaveTextContent('line2')
    expect(script.querySelector('[data-line="add"]')).toHaveTextContent('line2 changed')
    expect(script).toHaveTextContent('+1 / −1 lines')
  })

  it('uzun tek satırlık metin katlanır; "Show full value" açar', async () => {
    const diff = await openTyped()
    const to = cell(diff, 'description', 'to')
    expect(to.querySelector('.line-clamp-3')).not.toBeNull()
    const more = within(to).getByRole('button', { name: 'Show full value' })
    fireEvent.click(more)
    expect(to.querySelector('.line-clamp-3')).toBeNull()
    expect(within(to).getByRole('button', { name: 'Show less' })).toHaveAttribute('aria-expanded', 'true')
  })
})

describe('MonitorChangesConsole — ayrıntı paneli', () => {
  it('Ayrıntılar düğmesi paneli açar: tam fark, not, IP, tam zaman, izleme bağlantısı; o anki ayarlar ayrı uçtan', async () => {
    render(<MonitorChangesConsole />)
    await screen.findByText('Ödeme akışı')
    expect(screen.queryByRole('dialog')).toBeNull()
    const open = screen.getByRole('button', { name: 'Ödeme akışı — Details' })
    expect(open).toHaveAttribute('data-open-detail')
    expect(open).toHaveAttribute('aria-haspopup', 'dialog')

    fireEvent.click(open)
    const dlg = await screen.findByRole('dialog')
    expect(within(dlg).getByRole('heading', { name: /Ödeme akışı/ })).toBeInTheDocument()
    expect(within(dlg).getByText('2026-08-22T14:03:11', { selector: 'time' })).toBeInTheDocument()
    // Alan farkı — 5 alan, önce/sonra hücreleri (tablo değil, fark listesi)
    const diff = dlg.querySelector('[data-slot="chg-diff"]')
    expect(within(dlg).queryByRole('table')).toBeNull()
    expect(within(diff).getByText('1 min', { selector: '[data-diff="to"]' })).toBeInTheDocument()
    expect(within(diff).getByText('5 min', { selector: '[data-diff="from"]' })).toBeInTheDocument()
    expect(within(diff).getAllByText(/Check frequency|Timeout|Name|URL|Expected status code/, { selector: '[data-diff="field"]' })).toHaveLength(5)
    expect(within(dlg).getByText('Zaman aşımı yetmiyordu')).toBeInTheDocument()
    expect(within(dlg).getByRole('button', { name: 'Copy IP address 10.20.30.40' })).toBeInTheDocument()
    expect(within(dlg).getByText('Chrome 126 · Windows')).toBeInTheDocument()
    expect(within(dlg).getByRole('link', { name: /Go to monitor/ })).toHaveAttribute('href', '?tab=scripted&monitor=12&mtab=changes')
    // Snapshot ayrı uçtan (liste yanıtı taşımaz): düzenlemede katlanır bölüm
    await waitFor(() => expect(api.monitoring.getChangeDetail).toHaveBeenCalledWith('scripted', 12, 3))
    fireEvent.click(within(dlg).getByRole('button', { name: /State after this change/ }))
    expect(await within(dlg).findByText('Yeni ad', { selector: 'dd' })).toBeInTheDocument()

    // Kapat → panel gider (iki yol: başlıktaki X ve alt çubuk — ikisi de "Close")
    fireEvent.click(within(dlg).getAllByRole('button', { name: 'Close' }).at(-1))
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
  })

  it('ham kayıt (JSON) katlanır, çözülmüş changes + snapshot taşır, kopyalanır; bağlantı kopyalama ch_id taşır', async () => {
    render(<MonitorChangesConsole />)
    await screen.findByText('Ödeme akışı')
    openDetail('Ödeme akışı')
    const dlg = await screen.findByRole('dialog')
    await waitFor(() => expect(api.monitoring.getChangeDetail).toHaveBeenCalled())

    expect(dlg.querySelector('[data-slot="chg-raw"]')).toBeNull()
    fireEvent.click(within(dlg).getByRole('button', { name: /Show raw record/ }))
    const raw = await waitFor(() => {
      const el = dlg.querySelector('[data-slot="chg-raw"]')
      expect(el).not.toBeNull()
      return el
    })
    await waitFor(() => expect(JSON.parse(raw.textContent).snapshot).toEqual({ name: 'Yeni ad', intervalSeconds: 60, active: true }))
    const json = JSON.parse(raw.textContent)
    expect(json.changes.intervalSeconds).toEqual({ from: 300, to: 60 })   // metin değil, çözülmüş nesne
    expect(json.resource_name).toBe('Ödeme akışı')

    fireEvent.click(within(dlg).getByRole('button', { name: 'Copy raw record' }))
    await waitFor(() => expect(clip.copyText).toHaveBeenCalledWith(raw.textContent))

    // Paylaşılabilir bağlantı: yalnız tab + ch_id (süzgeçler değil)
    fireEvent.click(within(dlg).getByRole('button', { name: /Ödeme akışı — Copy link/ }))
    await waitFor(() => expect(clip.copyText).toHaveBeenLastCalledWith(expect.stringMatching(/\?tab=monitorchanges&ch_id=scripted%3A12%3A3$/)))
  })

  it('"Only this monitor" listeyi o izlemeye daraltır: kind + resourceId, rozet "Monitor: <ad>"', async () => {
    render(<MonitorChangesConsole />)
    await screen.findByText('Ödeme akışı')
    openDetail('Ödeme akışı')
    const dlg = await screen.findByRole('dialog')
    fireEvent.click(within(dlg).getByRole('button', { name: 'Only this monitor' }))
    await waitFor(() => expect(lastCall()).toMatchObject({ kind: 'scripted', resourceId: 12, page: 0 }))
    expect(document.querySelector('[data-filter-chip="res"]')).toHaveTextContent('Monitor: Ödeme akışı')
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
  })

  it('oluşturma olayının panelinde fark yerine "Initial values" (snapshot) doğrudan açık gelir', async () => {
    api.monitoring.getRecentChanges.mockResolvedValue(reply([CREATED]))
    api.monitoring.getChangeDetail.mockResolvedValue({ success: true, data: { ...CREATED, snapshot: JSON.stringify({ url: 'https://www.example.com/', intervalSeconds: 300, tags: ['a', 'b'] }) } })
    render(<MonitorChangesConsole />)
    await screen.findByText('Yeni HTTP izlemesi')
    openDetail('Yeni HTTP izlemesi')
    const dlg = await screen.findByRole('dialog')
    await waitFor(() => expect(api.monitoring.getChangeDetail).toHaveBeenCalledWith('http', 31, 0))
    expect(await within(dlg).findByText('Initial values')).toBeInTheDocument()
    expect(within(dlg).getByText('https://www.example.com/', { selector: 'dd' })).toBeInTheDocument()
    expect(within(dlg).getByText('5 min', { selector: 'dd' })).toBeInTheDocument()
    expect(within(dlg).getByText('a, b', { selector: 'dd' })).toBeInTheDocument()   // liste "[object]" değil
    expect(dlg.querySelector('[data-slot="chg-diff"]')).toBeNull()
  })
})

describe('MonitorChangesConsole — telefon (useIsMobile)', () => {
  beforeEach(() => { mobile.on = true })

  it('aynı zaman çizelgesi; satırdaki 40 px Ayrıntılar düğmesi paneli açar (telefonda tam ekran)', async () => {
    render(<MonitorChangesConsole />)
    await screen.findByText('Ödeme akışı')
    expect(screen.queryByRole('table')).toBeNull()
    const details = within(rowOf('Ödeme akışı')).getByRole('button', { name: 'Ödeme akışı — Details' })
    expect(details.className).toMatch(/max-md:size-10/)
    fireEvent.click(details)
    const dlg = await screen.findByRole('dialog')
    expect(dlg.className).toMatch(/w-full/)
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
    expect(screen.getByRole('button', { name: /Filters.*Active filters: 1/ })).toBeInTheDocument()
    expect(document.querySelector('[data-filter-chip="event"]')).toHaveTextContent('Event: Deleted')

    fireEvent.click(screen.getByRole('button', { name: /Filters/ }))
    fireEvent.click(within(await screen.findByRole('dialog')).getByRole('button', { name: 'Clear filters' }))
    await waitFor(() => expect(lastCall()).toMatchObject({ eventType: '' }))
  })
})

describe('MonitorChangesConsole — süzgeçler, rozetler ve URL', () => {
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

  it('olay süzgecinde duraklatma / sürdürme de var (sunucu desene çevirir)', async () => {
    render(<MonitorChangesConsole />)
    await screen.findByText('Ödeme akışı')
    fireEvent.mouseDown(screen.getByLabelText('Filter by event type'))
    fireEvent.mouseDown([...document.querySelectorAll('[role="option"]')].find(el => el.textContent === 'Paused'))
    await waitFor(() => expect(lastCall()).toMatchObject({ eventType: 'PAUSE' }))
  })

  it('"Clear filters" HEPSİNİ sıfırlar: tür, olay, arama ve zaman aralığı — rozetlerin hepsi görünür', async () => {
    const { container } = render(<MonitorChangesConsole />)
    await screen.findByText('Ödeme akışı')

    fireEvent.mouseDown(screen.getByLabelText('Filter by monitor type'))
    fireEvent.mouseDown([...document.querySelectorAll('[role="option"]')].find(el => el.textContent === 'Port'))
    fireEvent.change(screen.getByLabelText('Search by monitor name…'), { target: { value: 'ödeme' } })
    fireEvent.click(within(container.querySelector('[data-slot="chg-range"]')).getByText('7d'))
    await waitFor(() => expect(lastCall()).toMatchObject({ kind: 'port', q: 'ödeme' }))
    expect(lastCall().from).not.toBe('')
    expect(document.querySelector('[data-filter-chip="kind"]')).toHaveTextContent('Monitor type: Port')
    expect(document.querySelector('[data-filter-chip="q"]')).toHaveTextContent('Search: ödeme')
    expect(document.querySelector('[data-filter-chip="range"]')).toHaveTextContent('Time range: Last 7 days')

    fireEvent.click(screen.getByRole('button', { name: 'Clear filters' }))
    await waitFor(() => expect(lastCall()).toMatchObject({ kind: '', q: '', from: '', to: '', eventType: '' }))
    expect(document.querySelector('[data-slot="chg-active-filters"]')).toBeNull()
    expect(screen.getByLabelText('Search by monitor name…')).toHaveValue('')
  })

  it('URL: süzgeçler ch_* anahtarlarına yazılır, uygulamanın tab anahtarı korunur; temizleyince silinir', async () => {
    const { container } = render(<MonitorChangesConsole />)
    await screen.findByText('Ödeme akışı')
    fireEvent.mouseDown(screen.getByLabelText('Filter by monitor type'))
    fireEvent.mouseDown([...document.querySelectorAll('[role="option"]')].find(el => el.textContent === 'Port'))
    fireEvent.change(screen.getByLabelText('Search by monitor name…'), { target: { value: 'ödeme' } })
    fireEvent.click(within(container.querySelector('[data-slot="chg-range"]')).getByText('30d'))

    await waitFor(() => expect(params().get('ch_kind')).toBe('port'))
    await waitFor(() => expect(params().get('ch_q')).toBe('ödeme'))
    expect(params().get('ch_range')).toBe('30')
    expect(params().get('ch_from')).toBeNull()   // hazır pencere göreli kalır — sabit tarih yazılmaz
    expect(params().get('tab')).toBe('monitorchanges')
    // Uygulama anahtarlarına dokunulmaz, çıplak page/q yazılmaz (namespace kuralı)
    for (const k of params().keys()) expect(k === 'tab' || k.startsWith('ch_')).toBe(true)

    fireEvent.click(screen.getByRole('button', { name: 'Clear filters' }))
    await waitFor(() => expect([...params().keys()]).toEqual(['tab']))
  })

  it('URL: açılışta ch_* anahtarları okunur (derin bağlantı) — ilk istek süzgeçli ve doğru sayfada', async () => {
    window.history.replaceState(null, '', '/?tab=monitorchanges&ch_kind=port&ch_ev=DELETE&ch_q=eski&ch_page=2&ch_range=7&ch_kind_bogus=1')
    api.monitoring.getRecentChanges.mockResolvedValue(reply([DELETED], { total: 60 }))
    render(<MonitorChangesConsole />)
    await screen.findByText('Eski port')
    const first = api.monitoring.getRecentChanges.mock.calls[0][0]
    expect(first).toMatchObject({ kind: 'port', eventType: 'DELETE', q: 'eski', page: 1 })
    expect(first.from).toMatch(/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}$/)
    expect(document.querySelector('[data-filter-chip="kind"]')).toHaveTextContent('Monitor type: Port')
    expect(screen.getByLabelText('Search by monitor name…')).toHaveValue('eski')
    // Özet de aynı pencereyle
    expect(lastSummary()).toMatchObject({ from: first.from })
  })

  it('URL: geçersiz değerler sessizce varsayılana düşer (bozuk bağlantı ekranı kırmaz)', async () => {
    window.history.replaceState(null, '', '/?tab=monitorchanges&ch_kind=telepati&ch_ev=HACK&ch_team=abc&ch_range=999&ch_res=x')
    render(<MonitorChangesConsole />)
    await screen.findByText('Ödeme akışı')
    expect(lastCall()).toMatchObject({ kind: '', eventType: '', from: '' })
    expect(lastCall()).not.toHaveProperty('teamId')
    expect(lastCall()).not.toHaveProperty('resourceId')
  })

  it('URL: ch_id ayrıntı panelini açar (ayrıntı ucundan) ve panel kapanınca silinir', async () => {
    window.history.replaceState(null, '', '/?tab=monitorchanges&ch_id=scripted:12:3')
    render(<MonitorChangesConsole />)
    const dlg = await screen.findByRole('dialog')
    expect(api.monitoring.getChangeDetail).toHaveBeenCalledWith('scripted', 12, 3)
    expect(within(dlg).getByRole('heading', { name: /Ödeme akışı/ })).toBeInTheDocument()
    // Derin bağlantıyla gelen satır snapshot'ı zaten taşır — ikinci istek yok
    expect(api.monitoring.getChangeDetail).toHaveBeenCalledTimes(1)
    fireEvent.click(within(dlg).getAllByRole('button', { name: 'Close' }).at(-1))
    await waitFor(() => expect(params().get('ch_id')).toBeNull())
  })

  // 2026-09-28 regresyon B1 (b): ayrıntı ucu artık `resource_deleted` taşır — derin bağlantıyla açılan GÜNCELLEME
  // satırının izlemesi sonradan silindiyse panel "deleted" rozeti gösterir, ölü "Go to monitor" bağlantısı çizmez.
  it('URL: ch_id ile açılan ayrıntı, izlemesi sonradan silinmişse "deleted" rozeti taşır ve izlemeye bağlantı vermez', async () => {
    api.monitoring.getChangeDetail.mockResolvedValue({ success: true, data: { ...ROW, resource_deleted: true, snapshot: JSON.stringify({ name: 'Yeni ad' }) } })
    window.history.replaceState(null, '', '/?tab=monitorchanges&ch_id=scripted:12:3')
    render(<MonitorChangesConsole />)
    const dlg = await screen.findByRole('dialog')
    expect(within(dlg).getByRole('heading', { name: /Ödeme akışı/ })).toBeInTheDocument()
    expect(dlg.querySelector('[data-slot="chg-deleted"]')).toHaveTextContent('deleted')
    expect(within(dlg).queryByRole('link', { name: /Go to monitor/ })).toBeNull()
    // "Only this monitor" süzgeci silinmiş izlemede de çalışır (geçmişi listelenir)
    expect(within(dlg).getByRole('button', { name: /Only this monitor/ })).toBeInTheDocument()
  })

  it('URL: ch_id ile açılan SİLME olayı, izleme geri yüklendiyse (resource_deleted=false) izlemeye bağlantı verir, rozetsiz', async () => {
    api.monitoring.getChangeDetail.mockResolvedValue({ success: true, data: { ...DELETED, resource_deleted: false, snapshot: JSON.stringify({ name: 'Eski port' }) } })
    window.history.replaceState(null, '', '/?tab=monitorchanges&ch_id=port:7:9')
    render(<MonitorChangesConsole />)
    const dlg = await screen.findByRole('dialog')
    expect(api.monitoring.getChangeDetail).toHaveBeenCalledWith('port', 7, 9)
    expect(within(dlg).getByRole('link', { name: /Go to monitor/ })).toHaveAttribute('href', '?tab=port&monitor=7&mtab=changes')
    expect(dlg.querySelector('[data-slot="chg-deleted"]')).toBeNull()
  })

  it('serbest arama istek parametresine yansır (debounce)', async () => {
    render(<MonitorChangesConsole />)
    await screen.findByText('Ödeme akışı')
    api.monitoring.getRecentChanges.mockClear()
    fireEvent.change(screen.getByLabelText('Search by monitor name…'), { target: { value: 'ödeme' } })
    await waitFor(() => expect(api.monitoring.getRecentChanges).toHaveBeenCalledWith(
      expect.objectContaining({ q: 'ödeme', page: 0, counts: false })))
  })
})

describe('MonitorChangesConsole — yükleme, bayat-yükleme ve yarış', () => {
  it('ilk yüklemede iskelet, veri gelince kaybolur', async () => {
    const d = deferred()
    api.monitoring.getRecentChanges.mockReturnValue(d.promise)
    render(<MonitorChangesConsole />)
    expect(document.querySelectorAll('[data-skeleton]').length).toBeGreaterThan(0)
    expect(document.querySelector('[data-slot="skeleton"]')).not.toBeNull()
    await act(async () => { d.resolve(reply([ROW])) })
    await screen.findByText('Ödeme akışı')
    expect(document.querySelector('[data-slot="chg-timeline"] [data-skeleton]')).toBeNull()
  })

  it('süzgeç değişince ÖNCEKİ sonuç ekranda kalır (iskelet yok), gecikince soluklaşır; yanıt gelince yenisi çizilir', async () => {
    render(<MonitorChangesConsole />)
    await screen.findByText('Ödeme akışı')
    const d = deferred()
    api.monitoring.getRecentChanges.mockReturnValue(d.promise)

    fireEvent.mouseDown(screen.getByLabelText('Filter by event type'))
    fireEvent.mouseDown([...document.querySelectorAll('[role="option"]')].find(el => el.textContent.includes('Deleted')))

    const list = document.querySelector('[data-slot="chg-timeline"]')
    await waitFor(() => expect(list).toHaveAttribute('aria-busy', 'true'))
    expect(screen.getByText('Ödeme akışı')).toBeInTheDocument()          // bayat sonuç duruyor
    expect(document.querySelector('[data-slot="chg-timeline"] [data-skeleton]')).toBeNull()
    await waitFor(() => expect(list).toHaveAttribute('data-stale'))       // useDelayedFlag (180 ms)

    await act(async () => { d.resolve(reply([DELETED])) })
    await waitFor(() => expect(screen.queryByText('Ödeme akışı')).toBeNull())
    expect(screen.getByText('Eski port')).toBeInTheDocument()
    expect(document.querySelector('[data-slot="chg-timeline"]')).not.toHaveAttribute('aria-busy')
    expect(document.querySelector('[data-slot="chg-timeline"]')).not.toHaveAttribute('data-stale')
  })

  it('yarış: GEÇ gelen eski yanıt yeni sonucu EZMEZ, yükleme bayrağı son turda düşer', async () => {
    render(<MonitorChangesConsole />)
    await screen.findByText('Ödeme akışı')
    const slow = deferred()
    const fast = deferred()
    api.monitoring.getRecentChanges.mockReturnValueOnce(slow.promise).mockReturnValueOnce(fast.promise)

    fireEvent.mouseDown(screen.getByLabelText('Filter by event type'))
    fireEvent.mouseDown([...document.querySelectorAll('[role="option"]')].find(el => el.textContent.includes('Deleted')))
    await waitFor(() => expect(lastCall()).toMatchObject({ eventType: 'DELETE' }))
    fireEvent.mouseDown(screen.getByLabelText('Filter by event type'))
    fireEvent.mouseDown([...document.querySelectorAll('[role="option"]')].find(el => el.textContent.includes('Created')))
    await waitFor(() => expect(lastCall()).toMatchObject({ eventType: 'CREATE' }))

    await act(async () => { fast.resolve(reply([CREATED])) })
    expect(await screen.findByText('Yeni HTTP izlemesi')).toBeInTheDocument()
    await act(async () => { slow.resolve(reply([DELETED])) })    // bayat — yazılmamalı
    expect(screen.queryByText('Eski port')).toBeNull()
    expect(screen.getByText('Yeni HTTP izlemesi')).toBeInTheDocument()
    expect(document.querySelector('[data-slot="chg-timeline"]')).not.toHaveAttribute('aria-busy')
  })

  it('süzgeç YOKKEN boş: "henüz kayıt yok" (temizle yok); süzgeç VARKEN: "eşleşen yok" + Clear filters', async () => {
    api.monitoring.getRecentChanges.mockResolvedValue(reply([]))
    render(<MonitorChangesConsole />)
    const empty = await screen.findByText('No changes recorded yet')
    expect(empty.closest('[data-slot="empty"]')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Clear filters' })).toBeNull()

    fireEvent.change(screen.getByLabelText('Search by monitor name…'), { target: { value: 'yok' } })
    expect(await screen.findByText('No changes match your filters')).toBeInTheDocument()
    expect(screen.getByText(/Clear them and try again/)).toBeInTheDocument()
    const noMatch = screen.getByText('No changes match your filters').closest('[data-slot="empty"]')
    fireEvent.click(within(noMatch).getByRole('button', { name: 'Clear filters' }))
    expect(await screen.findByText('No changes recorded yet')).toBeInTheDocument()
  })

  it('sunucu hatası uyarı bandı + "Try again"; boş durum ikinci kez "sonuç yok" demez', async () => {
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

  it('istek FIRLATIRSA da bant çıkar, yükleme bayrağı sızmaz', async () => {
    api.monitoring.getRecentChanges.mockRejectedValue(new Error('ağ yok'))
    render(<MonitorChangesConsole />)
    expect(await screen.findByRole('alert')).toHaveTextContent('ağ yok')
    expect(screen.getByRole('button', { name: 'Refresh' })).not.toHaveAttribute('aria-busy')
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

describe('MonitorChangesConsole — dönem özeti kartları', () => {
  it('özet ayrı uçtan: dönem toplamı, olay dağılımı (duraklatma/sürdürme dâhil), en çok değişen izlemeler, en aktif kişiler', async () => {
    render(<MonitorChangesConsole />)
    await screen.findByText('Ödeme akışı')
    await waitFor(() => expect(kpi('total')).not.toBeNull())
    expect(kpi('total').querySelector('[data-slot="kpi-value"]')).toHaveTextContent('17')
    const ev = (k) => kpi('events').querySelector(`[data-kpi-event="${k}"]`)
    expect(ev('CREATE')).toHaveTextContent('4')
    expect(ev('UPDATE')).toHaveTextContent('11')
    expect(ev('DELETE')).toHaveTextContent('2')
    expect(ev('PAUSE')).toHaveTextContent('3')
    expect(ev('RESUME')).toHaveTextContent('1')
    // İzlemeler: silinmiş olan rozetli; kişiler avatarlı ad
    expect(kpi('monitors').querySelectorAll('[data-kpi-resource]')).toHaveLength(2)
    expect(kpi('monitors').querySelector('[data-kpi-resource="port:7"]')).toHaveTextContent('deleted')
    expect(kpi('people').querySelector('[data-kpi-actor="N23456"] [data-slot="user-badge"]')).toHaveTextContent('Ada Lovelace')
    // Liste istekleri sayaç İSTEMEZ (sayaçlar özetten)
    expect(lastCall()).toMatchObject({ counts: false })
    // Özet isteği pencere + saat dilimi taşır
    expect(lastSummary()).toMatchObject({ from: '', to: '' })
    expect(lastSummary()).toHaveProperty('tz')
  })

  it('olay kartı süzgeçtir: Silinen → eventType=DELETE + rozet; tekrar basınca kalkar', async () => {
    render(<MonitorChangesConsole />)
    await screen.findByText('Ödeme akışı')
    const del = await waitFor(() => {
      const b = kpi('events')?.querySelector('[data-kpi-event="DELETE"]')
      expect(b).toBeTruthy()
      return b
    })
    expect(del).toHaveAttribute('aria-pressed', 'false')
    fireEvent.click(del)
    await waitFor(() => expect(lastCall()).toMatchObject({ eventType: 'DELETE', page: 0 }))
    expect(kpi('events').querySelector('[data-kpi-event="DELETE"]')).toHaveAttribute('aria-pressed', 'true')
    expect(document.querySelector('[data-filter-chip="event"]')).toHaveTextContent('Event: Deleted')
    fireEvent.click(kpi('events').querySelector('[data-kpi-event="DELETE"]'))
    await waitFor(() => expect(lastCall()).toMatchObject({ eventType: '' }))
  })

  it('izleme kartı liste süzgecidir (tür + kimlik); kişi kartı kişi süzgecidir', async () => {
    render(<MonitorChangesConsole />)
    await screen.findByText('Ödeme akışı')
    await waitFor(() => expect(kpi('monitors')).not.toBeNull())
    fireEvent.click(kpi('monitors').querySelector('[data-kpi-resource="scripted:12"]'))
    await waitFor(() => expect(lastCall()).toMatchObject({ kind: 'scripted', resourceId: 12, page: 0 }))
    expect(kpi('monitors').querySelector('[data-kpi-resource="scripted:12"]')).toHaveAttribute('aria-pressed', 'true')
    expect(document.querySelector('[data-filter-chip="res"]')).toHaveTextContent('Monitor: Sepet akışı')

    fireEvent.click(kpi('people').querySelector('[data-kpi-actor="N23456"]'))
    await waitFor(() => expect(lastCall()).toMatchObject({ actor: 'N23456' }))
    expect(document.querySelector('[data-filter-chip="actor"]')).toHaveTextContent('User: Ada Lovelace')
    // Kişi süzgecinin seçenekleri PENCEREDEKİ kişilerden (görünen sayfada olmayan kişi de var)
    fireEvent.mouseDown(screen.getByLabelText('Filter by user'))
    expect([...document.querySelectorAll('[role="option"]')].some(o => o.textContent.includes('Ad Soyad'))).toBe(true)
  })

  it('özet YALNIZ pencere / takım değişince yeniden istenir — sayfa, tür, olay, arama istemez', async () => {
    api.monitoring.getRecentChanges.mockResolvedValue(reply([ROW, DELETED], { total: 300 }))
    const { container } = render(<MonitorChangesConsole />)
    await screen.findByText('Ödeme akışı')
    await waitFor(() => expect(api.monitoring.getChangeSummary).toHaveBeenCalledTimes(1))

    fireEvent.click(screen.getByRole('button', { name: /next|sonraki|›/i }))
    await waitFor(() => expect(lastCall().page).toBe(1))
    fireEvent.mouseDown(screen.getByLabelText('Filter by event type'))
    fireEvent.mouseDown([...document.querySelectorAll('[role="option"]')].find(el => el.textContent.includes('Deleted')))
    await waitFor(() => expect(lastCall()).toMatchObject({ eventType: 'DELETE' }))
    expect(api.monitoring.getChangeSummary).toHaveBeenCalledTimes(1)

    fireEvent.click(within(container.querySelector('[data-slot="chg-range"]')).getByText('7d'))
    await waitFor(() => expect(api.monitoring.getChangeSummary).toHaveBeenCalledTimes(2))
    expect(lastSummary().from).toBe(lastCall().from)
  })

  it('günlük eğri: pencerenin her günü bir çubuk, boş günler taban; erişilebilir özet', async () => {
    const today = new Date()
    const pad = (n) => String(n).padStart(2, '0')
    const key = (d) => `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`
    const d2 = new Date(today); d2.setDate(d2.getDate() - 2)
    api.monitoring.getChangeSummary.mockResolvedValue({ success: true, data: {
      ...SUMMARY, daily: [{ day: key(d2), count: 5 }, { day: key(today), count: 2 }] } })
    const { container } = render(<MonitorChangesConsole />)
    await screen.findByText('Ödeme akışı')
    fireEvent.click(within(container.querySelector('[data-slot="chg-range"]')).getByText('7d'))
    // "Tümü" penceresinde eğri ilk kayıtlı günden başlar (3 çubuk); 7 gün seçilince pencerenin 7 günü
    await waitFor(() => expect(container.querySelectorAll('[data-slot="chg-kpi-trend"] rect')).toHaveLength(7))
    const svg = container.querySelector('[data-slot="chg-kpi-trend"]')
    expect(svg.querySelector(`rect[data-day="${key(d2)}"]`).getAttribute('class')).toMatch(/fill-primary/)
    const d1 = new Date(today); d1.setDate(d1.getDate() - 1)
    expect(svg.querySelector(`rect[data-day="${key(d1)}"]`).getAttribute('class')).toMatch(/fill-border/)
    expect(svg).toHaveAttribute('role', 'img')
    expect(svg.getAttribute('aria-label')).toMatch(/busiest day .* \(5\)/)
  })

  it('özet ucu hata verirse uyarı + yeniden dene; liste çalışmaya devam eder', async () => {
    api.monitoring.getChangeSummary.mockResolvedValue({ success: false, error: 'özet yok' })
    render(<MonitorChangesConsole />)
    expect(await screen.findByText('Ödeme akışı')).toBeInTheDocument()
    expect(await screen.findByText('Couldn’t load the period summary')).toBeInTheDocument()
    api.monitoring.getChangeSummary.mockResolvedValue({ success: true, data: SUMMARY })
    const banner = screen.getByText('Couldn’t load the period summary').closest('[data-slot="alert"]')
    fireEvent.click(within(banner).getByRole('button', { name: 'Try again' }))
    await waitFor(() => expect(kpi('total')).not.toBeNull())
  })

  it('tür kırılımı katlanır (varsayılan kapalı): her tür toplam + kırılım, hareketsiz tür ÇİZİLMEZ; kart tür süzgecidir', async () => {
    const { container } = render(<MonitorChangesConsole />)
    await screen.findByText('Ödeme akışı')
    await waitFor(() => expect(api.monitoring.getChangeSummary).toHaveBeenCalled())
    const toggle = container.querySelector('[data-slot="stats-toggle"]')
    expect(toggle).toHaveAttribute('aria-expanded', 'false')
    expect(container.querySelectorAll('[data-kind-card]')).toHaveLength(0)
    openKinds(container)
    expect(toggle).toHaveAttribute('aria-expanded', 'true')

    const cards = () => [...container.querySelectorAll('[data-kind-card]')]
    await waitFor(() => expect(cards()).toHaveLength(3))   // Tümü + Sentetik + Port; DNS yok
    expect(within(cards()[0]).getByText('15')).toBeInTheDocument()
    expect(within(cards()[1]).getByText('Synthetic')).toBeInTheDocument()
    expect(within(cards()[1]).getByText('12')).toBeInTheDocument()
    const portCard = () => cards().find(c => c.textContent.includes('Port'))
    expect(portCard().querySelectorAll('[data-kind-chip]')).toHaveLength(2)

    api.monitoring.getRecentChanges.mockClear()
    fireEvent.click(portCard())
    await waitFor(() => expect(lastCall()).toMatchObject({ kind: 'port', page: 0 }))
    expect(portCard()).toHaveAttribute('aria-pressed', 'true')
    expect(document.querySelector('[data-filter-chip="kind"]')).toHaveTextContent('Monitor type: Port')
    fireEvent.click(cards()[0])
    await waitFor(() => expect(lastCall()).toMatchObject({ kind: '' }))
  })

  it('PAGESPEED kartı çevrilmiş adla çizilir, ham anahtarla değil', async () => {
    api.monitoring.getChangeSummary.mockResolvedValue({ success: true, data: { ...SUMMARY, kind_counts: { PAGESPEED: { CREATE: 1, UPDATE: 2 } } } })
    const { container } = render(<MonitorChangesConsole />)
    await screen.findByText('Ödeme akışı')
    openKinds(container)
    await waitFor(() => expect([...container.querySelectorAll('[data-kind-card]')].some(c => c.textContent.includes('Page Speed'))).toBe(true))
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
  const asInstant = (value) => {
    expect(value).toMatch(/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}$/)   // saniye hassasiyeti, Z'siz
    return new Date(value + 'Z')
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

  it('Bugün seçimi günün 00:00\'ını gönderir (UTC kayması YOK)', async () => {
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

  it('Tümü seçiliyken Özele geçince seçicinin varsayılanı HEMEN uygulanır; URL sabit uçları taşır', async () => {
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
    await waitFor(() => expect(params().get('ch_range')).toBe('custom'))
    expect(params().get('ch_from')).toBe(lastCall().from)
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
    await waitFor(() => expect(params().get('ch_page')).toBe('2'))

    pick(container, '15d')
    await waitFor(() => expect(lastCall().page).toBe(0))
  })
})

describe('MonitorChangesConsole — CSV', () => {
  it('süzülmüş listenin TAMAMINI 200\'lük sayfalarla ister, formül enjeksiyonunu nötrler', async () => {
    const evil = { ...ROW, seq: 40, resource_name: '=cmd|\'/c calc\'!A1', note: '+SUM(1)' }
    api.monitoring.getRecentChanges.mockResolvedValue(reply([ROW, evil]))
    render(<MonitorChangesConsole />)
    await screen.findByText('Ödeme akışı')
    fireEvent.mouseDown(screen.getByLabelText('Filter by event type'))
    fireEvent.mouseDown([...document.querySelectorAll('[role="option"]')].find(el => el.textContent.includes('Updated')))
    await waitFor(() => expect(lastCall()).toMatchObject({ eventType: 'UPDATE' }))

    api.monitoring.getRecentChanges.mockClear()
    fireEvent.click(screen.getByRole('button', { name: 'Download as CSV' }))
    await waitFor(() => expect(dl.downloadCsv).toHaveBeenCalledTimes(1))
    expect(lastCall()).toMatchObject({ eventType: 'UPDATE', page: 0, size: 200, counts: false })
    const [name, csv] = dl.downloadCsv.mock.calls[0]
    expect(name).toMatch(/^monitor-changes-\d{8}-\d{4}\.csv$/)
    expect(csv).toContain('Time (UTC)')
    expect(csv).toContain('Check frequency: 5 min → 1 min')
    expect(csv).toContain('2026-08-22T14:03:11Z')
    expect(csv).toContain("'=cmd")          // başa tek tırnak: Excel formül saymaz
    expect(csv).toContain("'+SUM(1)")
    expect(await screen.findByText('CSV downloaded (2 records)')).toBeInTheDocument()
  })
})

/**
 * Takım süzgeci (2026-08-27): ekran TÜM takım kullanıcılarına açık; kapsam UÇTA (viewTeamIds), burası SUNUM.
 */
describe('MonitorChangesConsole — takım süzgeci ve kapsam', () => {
  const twoTeams = () => api.admin.getTeams.mockResolvedValue(
    { success: true, data: [{ id: 5, name: 'Takım A' }, { id: 7, name: 'Takım B' }] })
  const oneTeam = () => api.admin.getTeams.mockResolvedValue(
    { success: true, data: [{ id: 5, name: 'Takım A' }] })

  it('TEK takım gören kullanıcıda seçici HİÇ çizilmez; istekler teamId taşımaz', async () => {
    oneTeam()
    render(<MonitorChangesConsole />)
    await screen.findByText('Ödeme akışı')
    await waitFor(() => expect(api.admin.getTeams).toHaveBeenCalled())
    expect(screen.queryByLabelText('Filter by team')).toBeNull()
    expect(lastCall()).not.toHaveProperty('teamId')
    expect(lastSummary()).not.toHaveProperty('teamId')
  })

  it('ÇOK takım görülünce seçici çizilir; seçim teamId olarak listeye VE özete girer, sayfa başa döner, rozet çıkar', async () => {
    twoTeams()
    render(<MonitorChangesConsole />)
    await screen.findByText('Ödeme akışı')

    const trigger = await screen.findByLabelText('Filter by team')
    api.monitoring.getRecentChanges.mockClear()
    fireEvent.mouseDown(trigger)
    fireEvent.mouseDown([...document.querySelectorAll('[role="option"]')].find(el => el.textContent.includes('Takım B')))

    await waitFor(() => expect(lastCall().teamId).toBe('7'))
    expect(lastCall().page).toBe(0)
    await waitFor(() => expect(lastSummary().teamId).toBe('7'))
    expect(document.querySelector('[data-filter-chip="team"]')).toHaveTextContent('Team: Takım B')
  })

  it('takım ucu PATLARSA konsol çökmez, liste çalışır', async () => {
    api.admin.getTeams.mockRejectedValue(new Error('403'))
    render(<MonitorChangesConsole />)
    expect(await screen.findByText('Ödeme akışı')).toBeInTheDocument()
    expect(screen.queryByLabelText('Filter by team')).toBeNull()
  })

  it('kapsam notu takım kullanıcısına gösterilir, global yöneticide gösterilmez', async () => {
    const { unmount } = render(<MonitorChangesConsole globalViewer={false} />)
    await screen.findByText('Ödeme akışı')
    expect(screen.getByText(/Only changes for the teams you can see/)).toBeInTheDocument()
    unmount()
    render(<MonitorChangesConsole globalViewer />)
    await screen.findByText('Ödeme akışı')
    expect(screen.queryByText(/Only changes for the teams you can see/)).toBeNull()
  })
})
