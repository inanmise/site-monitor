import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor, within } from './test-utils.jsx'
import PageSpeedMonitorCard from '../components/pagespeed/PageSpeedMonitorCard.jsx'
import {
  budgetFor, breachWeek, humanizeBytes, humanizeMs, meterTone, metersFor, urlParts, NEAR_BUDGET,
} from '../components/pagespeed/pageSpeedCardModel.js'
import PageSpeedMonitorPage from '../components/PageSpeedMonitorPage.jsx'
import { MonitorStatusBadge, CARD_CHECK } from '../components/monitoring/MonitorCard.jsx'
import MonitorCardMeta from '../components/MonitorCardMeta.jsx'
import { Checkbox } from '@/components/shadcn/checkbox'

const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))

const MAINT_URL = 'https://bakim.example.com/'

vi.mock('../api/client', () => ({
  formatDate: (s) => s ?? '',
  formatDateSec: (s) => (s ? `exact:${s}` : ''),
  formatDateOnly: (s) => s ?? '',
  api: withApiFallback({
    monitoring: {
      getPageSpeedMonitors: vi.fn(),
      // MaintenanceBadge modül önbelleği dosya boyunca tek yanıtı paylaşır → tek, sabit yanıt.
      maintenance: { active: vi.fn(() => Promise.resolve({ success: true, data: { all: false, targets: ['https://bakim.example.com/'] } })) },
    },
    admin: { getTeams: vi.fn(() => Promise.resolve({ success: true, data: [] })) },
  }),
}))
import { api } from '../api/client'

/**
 * SAYFA HIZI KARTI (2026-09-27 yeniden tasarım): bütçe ölçerleri, okunur değerler, sunucu ihlal kararı, haftalık
 * çip, durumlar (duraklatılmış / bakım / alarm / kesinti), stretched başlık, eylem ve seçim adları, telefon 2×2.
 * Yerleşimin kendisi (taşma, 40 px dokunma alanı) jsdom'da ölçülemez → Playwright taraması (responsive.spec.js).
 */

const base = {
  id: 7, name: 'Vitrin', url: 'https://www.example.com/', team_id: 5, team_name: 'Takım A', group_name: 'Kurumsal Web',
  tags: 'prod', active: true, status: 'OK', active_alarm: false, alarm_level: null, alarm_acknowledged: null,
  response_ms: 1840, ttfb_ms: 340, total_bytes: 1.2 * 1024 * 1024, request_count: 84,
  max_load_ms: null, max_ttfb_ms: null, max_page_kb: null, max_requests: null,
  breached_metrics: [], bytes_truncated: false, capped: false, error: null, last_check: '2026-09-27T08:00:00',
}
const mon = (over = {}) => ({ ...base, ...over })
const statusKey = (m) => (m.status === 'OK' ? 'up' : m.status === 'SLOW' ? 'warn' : m.status === 'DOWN' ? 'down' : 'unknown')

function renderCard(m, extra = {}) {
  const onOpen = vi.fn()
  const utils = render(
    <PageSpeedMonitorCard monitor={m} status={statusKey(m)} onOpen={onOpen}
      badge={<MonitorStatusBadge status={statusKey(m)}>{m.status}</MonitorStatusBadge>}
      meta={<MonitorCardMeta monitor={m} />} {...extra} />,
  )
  return { ...utils, onOpen, card: utils.container.querySelector('[data-slot="card"]') }
}

const meter = (key) => document.querySelector(`[data-slot="budget-meter"][data-metric="${key}"]`)
const valueOf = (key) => meter(key).querySelector('[data-slot="meter-value"]').textContent.replace(/\s+/g, ' ').trim()
const barOf = (key) => meter(key).querySelector('[data-slot="progress"]')

describe('pageSpeedCardModel — saf yardımcılar', () => {
  it('humanizeMs: 1 sn altı tam ms, üstü tek ondalıklı saniye; önce yuvarlar (999.6 → 1.0 s)', () => {
    expect(humanizeMs(340)).toEqual({ num: '340', unit: 'ms' })
    expect(humanizeMs(1840)).toEqual({ num: '1.8', unit: 's' })
    expect(humanizeMs(999.6)).toEqual({ num: '1.0', unit: 's' })
    expect(humanizeMs(0)).toEqual({ num: '0', unit: 'ms' })
    expect(humanizeMs(null)).toBeNull()
    expect(humanizeMs('abc')).toBeNull()
  })

  it('humanizeBytes formatBytes ile aynı dili konuşur; kırpılmış okuma "≥" öneki alır', () => {
    expect(humanizeBytes(2 * 1024 * 1024)).toEqual({ prefix: '', num: '2.0', unit: 'MB' })
    expect(humanizeBytes(512 * 1024, true)).toEqual({ prefix: '≥', num: '512', unit: 'KB' })
    expect(humanizeBytes(null)).toBeNull()
  })

  it('budgetFor sunucu kuralıyla birebir: boş / 0 / negatif = eşik YOK; boyut KB → bayt', () => {
    expect(budgetFor({ max_load_ms: 2500 }, 'load')).toBe(2500)
    expect(budgetFor({ max_load_ms: 0 }, 'load')).toBeNull()
    expect(budgetFor({ max_ttfb_ms: '' }, 'ttfb')).toBeNull()
    expect(budgetFor({ max_requests: -5 }, 'requests')).toBeNull()
    expect(budgetFor({ max_page_kb: 2 }, 'size')).toBe(2048)
    expect(budgetFor({}, 'size')).toBeNull()
  })

  it('meterTone: ≤%80 rahat, %80–100 sınırda (tam eşik ihlal DEĞİL), aşınca kırmızı; sunucu ihlali her zaman kırmızı', () => {
    expect(meterTone({ key: 'load', ratio: NEAR_BUDGET })).toBe('ok')
    expect(meterTone({ key: 'load', ratio: 0.81 })).toBe('warn')
    expect(meterTone({ key: 'load', ratio: 1 })).toBe('warn')
    expect(meterTone({ key: 'load', ratio: 1.01 })).toBe('crit')
    expect(meterTone({ key: 'load', ratio: 0.3, breached: true })).toBe('crit')
    expect(meterTone({ key: 'load', ratio: null })).toBeNull()
    // TTFB eşiği sunucuda server_ms'e karşı değerlendirilir; görünen ttfb_ms aştı ama sunucu ihlal saymadı → sınırda
    expect(meterTone({ key: 'ttfb', ratio: 1.4 })).toBe('warn')
    expect(meterTone({ key: 'ttfb', ratio: 1.4, breached: true })).toBe('crit')
  })

  it('breachWeek: geçen hafta = 14 gün − 7 gün; 14 günde kontrol yoksa karşılaştırma yok', () => {
    expect(breachWeek({ n: 10, fail: 3 }, { n: 20, fail: 8 })).toEqual({ thisWeek: 3, lastWeek: 5, delta: -2, trend: 'better' })
    expect(breachWeek({ n: 10, fail: 4 }, { n: 20, fail: 5 })).toMatchObject({ lastWeek: 1, delta: 3, trend: 'worse' })
    expect(breachWeek({ n: 10, fail: 0 }, { n: 20, fail: 0 })).toMatchObject({ trend: 'same' })
    expect(breachWeek({ n: 10, fail: 6 }, { n: 20, fail: 2 })).toMatchObject({ lastWeek: 0 })   // bozuk veri eksiye düşmez
    expect(breachWeek({ n: 0, fail: 0 }, { n: 0, fail: 0 })).toBeNull()
    expect(breachWeek(null, { n: 5 })).toBeNull()
  })

  it('urlParts: https şeması gizlenir, düz http görünür; yol + sorgu ayrı; ayrıştırılamayan metin host olur', () => {
    expect(urlParts('https://www.example.com/')).toEqual({ scheme: '', host: 'www.example.com', path: '' })
    expect(urlParts('http://a.example.com/x?y=1')).toEqual({ scheme: 'http://', host: 'a.example.com', path: '/x?y=1' })
    expect(urlParts('not a url')).toEqual({ scheme: '', host: 'not a url', path: '' })
  })

  it('metersFor: dört ölçer sabit sırada; boyut kırpılması ve istek tavanı ALT SINIR bayrağı üretir', () => {
    const ms = metersFor(mon({ bytes_truncated: true, capped: true }))
    expect(ms.map((x) => x.key)).toEqual(['load', 'ttfb', 'size', 'requests'])
    expect(ms.map((x) => x.lowerBound)).toEqual([false, false, true, true])
  })
})

describe('PageSpeedMonitorCard — bütçe ölçerleri', () => {
  beforeEach(() => { vi.clearAllMocks() })

  it('okunur değerler: "1.8 s", "340 ms", "1.2 MB", "84 req"', () => {
    renderCard(mon())
    expect(valueOf('load')).toBe('1.8 s')
    expect(valueOf('ttfb')).toBe('340 ms')
    expect(valueOf('size')).toBe('1.2 MB')
    expect(valueOf('requests')).toMatch(/^84 (req|istek)$/)
  })

  it('bütçe tonları: rahat / sınırda / aşıldı; çubuk değer/bütçe, bütçe satırı ve yüzde görünür', () => {
    renderCard(mon({
      max_load_ms: 5000,          // 1840 / 5000 = %37 → rahat
      max_ttfb_ms: 400,           // 340 / 400 = %85 → sınırda
      max_page_kb: 1024,          // 1.2 MB / 1 MB = %120 → aşıldı (sunucu henüz ölçmediyse de)
      max_requests: 84,           // tam eşik = %100 → sınırda (ihlal kesin büyüktür)
    }))
    expect(meter('load')).toHaveAttribute('data-tone', 'ok')
    expect(meter('ttfb')).toHaveAttribute('data-tone', 'warn')
    expect(meter('size')).toHaveAttribute('data-tone', 'crit')
    expect(meter('size')).toHaveAttribute('data-over', 'true')
    expect(meter('requests')).toHaveAttribute('data-tone', 'warn')
    expect(meter('load')).not.toHaveAttribute('data-over')

    // Çubuk: değer/bütçe (aşımda bütçeye kırpılır → dolu); aynı değer metin olarak göründüğü için süs (aria-hidden)
    expect(barOf('load')).toHaveAttribute('aria-valuemax', '5000')
    expect(barOf('load')).toHaveAttribute('aria-valuenow', '1840')
    expect(barOf('size')).toHaveAttribute('aria-valuenow', String(1024 * 1024))
    expect(barOf('load').closest('[aria-hidden="true"]')).not.toBeNull()

    const cap = (key) => meter(key).querySelector('[data-slot="meter-budget"]').textContent
    expect(cap('load')).toMatch(/^(Budget|Eşik) 5\.0 s(37%|%37)$/)
    expect(cap('size')).toMatch(/^(Budget|Eşik) 1\.0 MB(120%|%120)$/)
    expect(cap('requests')).toMatch(/84 (req|istek)/)
    // Ekran okuyucu: tonun sözlü karşılığı
    expect(within(meter('size')).getByText(/^(Over budget|Eşik aşıldı)$/)).toHaveClass('sr-only')
    expect(within(meter('ttfb')).getByText(/^(Close to budget|Eşiğe yakın)$/)).toHaveClass('sr-only')
    expect(within(meter('load')).getByText(/^(Within budget|Eşiğin altında)$/)).toHaveClass('sr-only')
  })

  it('bütçesi olmayan ölçü yalnız değeri gösterir: çubuk YOK, "bütçe yok" satırı', () => {
    renderCard(mon())
    for (const key of ['load', 'ttfb', 'size', 'requests']) {
      expect(barOf(key)).toBeNull()
      expect(meter(key)).toHaveAttribute('data-tone', 'none')
      expect(meter(key).querySelector('[data-slot="meter-budget"]').textContent).toMatch(/^(No budget set|Eşik tanımlı değil)$/)
    }
  })

  it('sunucu ihlali ölçeri vurgular — oran düşük olsa bile (eşik ölçümden sonra gevşetilmiş); ayrı rozet duvarı YOK', () => {
    renderCard(mon({ status: 'SLOW', max_load_ms: 9000, breached_metrics: ['LOAD'] }))
    expect(meter('load')).toHaveAttribute('data-tone', 'crit')
    expect(meter('load')).toHaveAttribute('data-over', 'true')
    expect(screen.queryByText(/Load time over threshold|Yükleme eşiği aşıldı/)).toBeNull()
  })

  it('ölçülmemiş değer tire; bütçe varsa satırı kalır ama çubuk çizilmez', () => {
    renderCard(mon({ response_ms: null, max_load_ms: 3000 }))
    expect(valueOf('load')).toBe('—')
    expect(barOf('load')).toBeNull()
    expect(meter('load').querySelector('[data-slot="meter-budget"]').textContent).toMatch(/3\.0 s$/)
  })

  it('istek tavanı / boyut kırpması: "≥" + dokun-gör açıklama; açıklamaya basmak detayı AÇMAZ', async () => {
    const { onOpen } = renderCard(mon({ capped: true, request_count: 200 }))
    expect(valueOf('requests')).toMatch(/^≥ 200 (req|istek)$/)
    fireEvent.click(within(meter('requests')).getByRole('button', { name: /real figure may be higher|gerçek değer daha yüksek/ }))
    expect(await screen.findByRole('tooltip')).toHaveTextContent(/Resource cap reached|Kaynak tavanına ulaşıldı/)
    expect(onOpen).not.toHaveBeenCalled()
  })

  it('telefon 2×2: ölçer ızgarası iki sütunla başlar, yalnız geniş kartta (kap ≥ 36rem) dörtlü olur', () => {
    renderCard(mon())
    const grid = document.querySelector('[data-slot="pspd-meters"]')
    expect(grid).toHaveAttribute('role', 'group')
    expect(grid).toHaveAccessibleName(/Performance budget|Performans eşikleri/)
    expect(grid.className).toMatch(/(^|\s)grid-cols-2(\s|$)/)
    expect(grid.className).toMatch(/@xl:grid-cols-4/)
    expect(grid.parentElement.className).toMatch(/@container/)
    expect(grid.querySelectorAll('[data-slot="budget-meter"]')).toHaveLength(4)
  })
})

describe('PageSpeedMonitorCard — haftalık çip, durumlar, başlık', () => {
  it('haftalık çip: kötüleşme ▲ + düz cümle adı; hiç aşım yoksa "yok" metni; veri yoksa çip yok', async () => {
    const { unmount } = renderCard(mon(), { week7: { n: 10, fail: 4 }, week14: { n: 20, fail: 5 } })
    const chip = document.querySelector('[data-slot="breach-week"]')
    expect(chip).toHaveAttribute('data-trend', 'worse')
    expect(chip.textContent).toMatch(/▲3/)
    const trigger = screen.getByRole('button', { name: /^(Over budget this week: 4, 3 more than last week|Bu hafta eşik aşımı: 4, geçen haftadan 3 fazla)$/ })
    fireEvent.click(trigger)
    expect(await screen.findByRole('tooltip')).toHaveTextContent(/this week 4 · last week 1|bu hafta 4 · geçen hafta 1/)
    unmount()

    const second = renderCard(mon(), { week7: { n: 10, fail: 0 }, week14: { n: 20, fail: 0 } })
    expect(document.querySelector('[data-slot="breach-week"]')).toHaveAttribute('data-trend', 'same')
    expect(document.querySelector('[data-slot="breach-week"]').textContent).toMatch(/No budget breaches this week|Bu hafta eşik aşımı yok/)
    second.unmount()

    renderCard(mon(), { week7: { n: 0, fail: 0 }, week14: { n: 0, fail: 0 } })
    expect(document.querySelector('[data-slot="breach-week"]')).toBeNull()
  })

  it('duraklatılmış: kesik kenar kancası + "Duraklatıldı"; bakımdaki URL bakım rozeti taşır', async () => {
    const { card } = renderCard(mon({ active: false, url: MAINT_URL }))
    expect(card).toHaveAttribute('data-inactive', 'true')
    expect(card.querySelector('[data-slot="monitor-paused"]')).not.toBeNull()
    await waitFor(() => expect(card.querySelector('[data-slot="maintenance-badge"]')).not.toBeNull())
  })

  it('aktif alarm: tüm kart dış çizgisi (data-alarm) + görünür seviye rozeti; sol şerit sınıfı YOK', () => {
    const { card } = renderCard(mon({ status: 'SLOW', active_alarm: true, alarm_level: 'HIGH', alarm_acknowledged: false }))
    expect(card).toHaveAttribute('data-alarm', 'true')
    expect(card.querySelector('[data-slot="monitor-alarm"]')).toHaveAttribute('data-level', 'HIGH')
    expect(card.className).not.toMatch(/border-l-|before:|inset_4px/)
  })

  it('kesinti: hata satırı görünür, kart TÜM kenarı kırmızı tonda (alarm yokken)', () => {
    const { card } = renderCard(mon({ status: 'DOWN', response_ms: null, error: 'Connection timed out after 10000 ms' }))
    expect(card).toHaveAttribute('data-status', 'down')
    expect(card.querySelector('[data-slot="pspd-error"]').textContent).toBe('Connection timed out after 10000 ms')
    expect(card.className).toMatch(/border-destructive\/45/)
  })

  it('stretched başlık detayı açar; adı tam URL, görünen metin host vurgulu (https şeması gizli)', () => {
    const { onOpen } = renderCard(mon({ url: 'https://www.example.com/giris?lang=tr' }))
    const title = screen.getByRole('button', { name: /^https:\/\/www\.example\.com\/giris\?lang=tr — (open details|detayları aç)$/ })
    expect(title).toHaveAttribute('data-monitor-open', 'true')
    expect(title.textContent).toBe('www.example.com/giris?lang=tr')
    fireEvent.click(title)
    expect(onOpen).toHaveBeenCalledTimes(1)
  })

  it('son kontrol: göreli zaman görünür, kesin damga ipucunda (<time dateTime>)', () => {
    renderCard(mon())
    const time = document.querySelector('[data-slot="monitor-card-time"] time')
    expect(time).toHaveAttribute('dateTime', '2026-09-27T08:00:00')
    expect(time).toHaveAttribute('title', 'exact:2026-09-27T08:00:00')
    expect(time.textContent).toMatch(/ago|önce|just now|az önce/)
  })

  it('seçim kutusu yuvası örtünün üstünde ve satır adlı', () => {
    const m = mon()
    renderCard(m, { selection: <Checkbox className={CARD_CHECK} checked={false} aria-label={`Select ${m.url}`} /> })
    const box = screen.getByRole('checkbox', { name: `Select ${m.url}` })
    expect(box.className).toMatch(/relative z-10/)
  })
})

describe('PageSpeedMonitorPage — kart yuvaları (seçim + eylemler satır adlı)', () => {
  it('toplu seçim kutusu ve kart eylemlerinin adları URL taşır; başlık detayı açar', async () => {
    api.monitoring.getPageSpeedMonitors.mockResolvedValue({ success: true, data: [mon()] })
    render(<PageSpeedMonitorPage systemRole="ADMIN" teamId={5} teamName="Takım A" myTeams={[{ id: 5, name: 'Takım A' }]} />)
    const card = await waitFor(() => { const c = document.querySelector('.upt-grid > [data-slot="card"]'); expect(c).not.toBeNull(); return c })
    expect(within(card).getByRole('checkbox', { name: /https:\/\/www\.example\.com\/.*(bulk|toplu)/i })).toBeInTheDocument()
    const named = within(card).getAllByRole('button', { name: /^https:\/\/www\.example\.com\/ — / })
    // başlık + kontrol + düzenle + kopyala + sil
    expect(named.length).toBeGreaterThanOrEqual(5)
    fireEvent.click(within(card).getByRole('button', { name: /^https:\/\/www\.example\.com\/ — (open details|detayları aç)$/ }))
    expect(await screen.findByRole('dialog')).toBeInTheDocument()
  })
})

/**
 * KART YOĞUNLUĞU (2026-09-27): Zengin = bugünkü tam kart (dört ölçer, trend/SLA, haftalık çip, grup/vekil meta);
 * Kompakt = durum satırı + URL + TEK ana ölçü (yükleme, bütçe tonu + ince çubuk) + gerekiyorsa tek satır neden + yalnız
 * takım rozeti + alt çubuk. Yalnız-Zengin bölümler Kompakt'ta DOM'a hiç girmez (MonitorCardRich).
 */
describe('PageSpeedMonitorCard — yoğunluk (Kompakt / Zengin)', () => {
  const richOnly = () => document.querySelector('[data-slot="monitor-card-rich"]')
  const primary = () => document.querySelector('[data-slot="pspd-primary"]')
  const reason = () => document.querySelector('[data-slot="pspd-reason"]')
  const slots = { week7: { n: 10, fail: 4 }, week14: { n: 20, fail: 5 }, spark: { hours: [] } }

  it('Zengin (varsayılan): tam kart — dört ölçer, haftalık çip, grup meta; kompakt özet YOK', () => {
    const { card } = renderCard(mon({ proxy_effective: 'direct' }), slots)
    expect(card).toHaveAttribute('data-density', 'rich')
    expect(richOnly()).not.toBeNull()
    expect(document.querySelectorAll('[data-slot="budget-meter"]')).toHaveLength(4)
    expect(document.querySelector('[data-slot="breach-week"]')).not.toBeNull()
    expect(document.querySelector('[data-slot="meta-group"]')).not.toBeNull()
    expect(document.querySelector('[data-slot="meta-proxy"]')).not.toBeNull()
    expect(primary()).toBeNull()
    expect(reason()).toBeNull()
  })

  it('Kompakt: yalnız-Zengin bölümler DOM\'da YOK; ana ölçü + takım rozeti + başlık/seçim/eylem/zaman kalır', () => {
    const m = mon({ proxy_effective: 'direct', max_load_ms: 2500 })
    const onEdit = vi.fn()
    const { card, onOpen } = renderCard(m, {
      ...slots, density: 'compact',
      selection: <Checkbox className={CARD_CHECK} checked={false} aria-label={`Select ${m.url}`} />,
      actions: <button type="button" onClick={onEdit}>edit-probe</button>,
    })
    expect(card).toHaveAttribute('data-density', 'compact')
    expect(richOnly()).toBeNull()
    for (const slot of ['pspd-meters', 'budget-meter', 'breach-week', 'meta-group', 'meta-proxy', 'pspd-error']) {
      expect(document.querySelector(`[data-slot="${slot}"]`), slot).toBeNull()
    }
    // Ana ölçü: yükleme süresi, bütçe tonunda (1840 / 2500 = %74 → rahat), ince bütçe çubuğu
    expect(primary()).toHaveAttribute('data-metric', 'load')
    expect(primary()).toHaveAttribute('data-tone', 'ok')
    expect(primary().querySelector('[data-slot="pspd-primary-value"]').textContent.replace(/\s+/g, ' ').trim()).toBe('1.8 s')
    expect(primary().querySelector('[data-slot="pspd-primary-budget"]').textContent).toMatch(/^(Budget|Eşik) 2\.5 s · (74%|%74)$/)
    expect(primary().querySelector('[data-slot="progress"]')).toHaveAttribute('aria-valuemax', '2500')
    expect(within(primary()).getByText(/^(Within budget|Eşiğin altında)$/)).toHaveClass('sr-only')
    // Takım rozeti TEK meta öğesi
    const meta = document.querySelector('[data-slot="monitor-card-meta"]')
    expect(meta.querySelector('[data-slot="meta-team"]')).toHaveTextContent('Takım A')
    expect(meta.children).toHaveLength(1)
    // Davranış aynı: başlık detayı açar, seçim kutusu ve eylem yuvası yerinde, son kontrol zamanı alt çubukta
    expect(screen.getByRole('checkbox', { name: `Select ${m.url}` })).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'edit-probe' }))
    expect(onEdit).toHaveBeenCalledTimes(1)
    fireEvent.click(screen.getByRole('button', { name: /(open details|detayları aç)$/ }))
    expect(onOpen).toHaveBeenCalledTimes(1)
    expect(document.querySelector('[data-slot="monitor-card-time"] time')).toHaveAttribute('dateTime', '2026-09-27T08:00:00')
  })

  it('Kompakt, bütçesiz ölçü: yalnız değer — çubuk, bütçe metni ve neden satırı YOK', () => {
    renderCard(mon(), { density: 'compact' })
    expect(primary()).toHaveAttribute('data-tone', 'none')
    expect(primary().querySelector('[data-slot="pspd-primary-budget"]')).toBeNull()
    expect(primary().querySelector('[data-slot="progress"]')).toBeNull()
    expect(reason()).toBeNull()
  })

  it('Kompakt kesinti: TEK satır hata nedeni (kırpılır), tam metin dokun-gör açıklamada; açmak detayı AÇMAZ', async () => {
    const error = 'Connection timed out after 10000 ms while waiting for the upstream proxy at proxy.example.com to respond'
    const { onOpen } = renderCard(mon({ status: 'DOWN', response_ms: null, error }), { density: 'compact' })
    expect(reason()).toHaveAttribute('data-reason', 'error')
    expect(reason().textContent).toBe(error)
    expect(reason().className).toMatch(/(^|\s)truncate(\s|$)/)
    expect(primary().querySelector('[data-slot="pspd-primary-value"]').textContent).toBe('—')
    const trigger = screen.getByRole('button', { name: error })
    expect(trigger.className).toMatch(/relative z-10/)
    fireEvent.click(trigger)
    expect(await screen.findByRole('tooltip')).toHaveTextContent(error)
    expect(onOpen).not.toHaveBeenCalled()
    expect(document.querySelector('[data-slot="pspd-error"]')).toBeNull()   // iki satırlık Zengin hata satırı yok
  })

  it('Kompakt eşik aşımı: neden satırı ana ölçü DIŞINDA aşan ölçüleri sayar; yalnız yükleme aştıysa neden yok, ana ölçü kırmızı', () => {
    const { unmount } = renderCard(mon({
      status: 'SLOW', response_ms: 3200, max_load_ms: 2500, total_bytes: 1.4 * 1024 * 1024, max_page_kb: 1024,
      request_count: 120, max_requests: 100, breached_metrics: ['LOAD', 'SIZE', 'REQUESTS'],
    }), { density: 'compact' })
    expect(primary()).toHaveAttribute('data-tone', 'crit')
    expect(reason()).toHaveAttribute('data-reason', 'budget')
    expect(reason().textContent).toMatch(/^(Over budget — Size 1\.4 MB \(budget 1\.0 MB\) · Requests 120 \(budget 100\)|Eşik aşıldı — Boyut 1\.4 MB \(eşik 1\.0 MB\) · İstek 120 \(eşik 100\))$/)
    unmount()

    renderCard(mon({ status: 'SLOW', response_ms: 3200, max_load_ms: 2500, breached_metrics: ['LOAD'] }), { density: 'compact' })
    expect(primary()).toHaveAttribute('data-tone', 'crit')
    expect(within(primary()).getByText(/^(Over budget|Eşik aşıldı)$/)).toHaveClass('sr-only')
    expect(reason()).toBeNull()
  })

  it('Kompakt: duraklatılmış / alarm / bakım durumları korunur', async () => {
    const { card } = renderCard(mon({ active: false, url: MAINT_URL, status: 'SLOW', active_alarm: true, alarm_level: 'CRITICAL' }), { density: 'compact' })
    expect(card).toHaveAttribute('data-inactive', 'true')
    expect(card).toHaveAttribute('data-alarm', 'true')
    expect(card.querySelector('[data-slot="monitor-paused"]')).not.toBeNull()
    expect(card.querySelector('[data-slot="monitor-alarm"]')).toHaveAttribute('data-level', 'CRITICAL')
    await waitFor(() => expect(card.querySelector('[data-slot="maintenance-badge"]')).not.toBeNull())
  })
})

describe('PageSpeedMonitorPage — kart görünümü seçicisi', () => {
  it('seçici araç çubuğunun İLK öğesi; sayfa Zengin açılır, Kompakt\'a geçiş ızgarayı/kartı değiştirir; seçim KALICI DEĞİL', async () => {
    api.monitoring.getPageSpeedMonitors.mockResolvedValue({ success: true, data: [mon(), mon({ id: 8, url: 'https://shop.example.com/' })] })
    const first = render(<PageSpeedMonitorPage systemRole="ADMIN" teamId={5} teamName="Takım A" myTeams={[{ id: 5, name: 'Takım A' }]} />)
    const grid = await waitFor(() => { const g = document.querySelector('.upt-grid'); expect(g).not.toBeNull(); return g })
    expect(grid).toHaveAttribute('data-density', 'rich')
    const toggle = document.querySelector('[data-slot="card-density-toggle"]')
    expect(toggle.parentElement).toHaveClass('upt-toolbar')
    expect(toggle.parentElement.firstElementChild).toBe(toggle)
    expect(toggle.className).toMatch(/(^|\s)mr-auto(\s|$)/)
    expect(grid.querySelectorAll('[data-slot="monitor-card-rich"]')).toHaveLength(2)

    fireEvent.click(within(toggle).getByRole('radio', { name: /Kompakt|Compact/ }))
    expect(grid).toHaveAttribute('data-density', 'compact')
    expect(grid.querySelectorAll('[data-slot="card"][data-density="compact"]')).toHaveLength(2)
    expect(grid.querySelectorAll('[data-slot="monitor-card-rich"]')).toHaveLength(0)
    expect(grid.querySelectorAll('[data-slot="pspd-primary"]')).toHaveLength(2)
    // toplu seçim Kompakt'ta da çalışır
    fireEvent.click(within(grid).getAllByRole('checkbox')[0])
    expect(await waitFor(() => { const bar = document.querySelector('[data-slot="bulk-action-bar"]'); expect(bar).not.toBeNull(); return bar })).toHaveTextContent(/1 (selected|seçili)/)
    first.unmount()

    // Sayfadan çıkıp yeniden açınca yine Zengin (kullanıcı kararı 2026-09-27: izleme sayfaları her açılışta Zengin)
    render(<PageSpeedMonitorPage systemRole="ADMIN" teamId={5} teamName="Takım A" myTeams={[{ id: 5, name: 'Takım A' }]} />)
    await waitFor(() => expect(document.querySelector('.upt-grid')).toHaveAttribute('data-density', 'rich'))
    expect(document.querySelectorAll('.upt-grid [data-slot="monitor-card-rich"]')).toHaveLength(2)
  })
})
