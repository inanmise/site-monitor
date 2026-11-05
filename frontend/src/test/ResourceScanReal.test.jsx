import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, fireEvent, waitFor, cleanup } from './test-utils.jsx'
import { pressMenuTrigger } from './helpers/dropdownMenu.js'

// Kaynak analizi — GERÇEK arka uç yanıtlarıyla (prs3 sözleşme incelemesi D9): sahte fikstürle yazılan testlerin
// kaçırdığı tel biçimi hataları (sayfa hata metni, ortak kaynak toplamı, bütçe nedeni, ham kod, "bilinmiyor → yeşil")
// burada gerçek dökümle yakalanır. Her görünümün metni + aria-label / title taranır: `undefined`, `NaN`, `[object Object]`,
// ham `prs.` anahtarı, doldurulmamış `{0}`, ham sunucu kodu.
const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
vi.mock('../api/client', () => ({
  formatDate: (s) => s ?? '',
  formatDateSec: (s) => s ?? '',
  formatDateOnly: (s) => s ?? '',
  api: withApiFallback({
    monitoring: {
      resourceScans: vi.fn(), resourceScan: vi.fn(), startResourceScan: vi.fn(), cancelResourceScan: vi.fn(),
      resourceBudget: vi.fn(), saveResourceBudget: vi.fn(), resourceScanTrend: vi.fn(),
    },
  }),
}))
vi.mock('../components/page/resourcescan/resourceScanExport.js', () => ({ exportResourceScanXlsx: vi.fn(), exportResourceScanPdf: vi.fn() }))

import { api } from '../api/client'
import { LANG_STORAGE_KEY } from '../i18n/dateLocale.js'
import { TR } from '../i18n/tr.js'
import { EN } from '../i18n/en.js'
import ResourceScanTab from '../components/page/resourcescan/ResourceScanTab.jsx'
import { resetWatchers } from '../components/page/resourcescan/resourceScanWatcher.js'
import {
  REAL_MONITOR, realBudget, realDetail, realList, realRunningSite, realStartSite, realTrend, withBudgetCapped, withBudgetUnreadable,
  withHtmlUnread, withSecurityUnread, withSiteIncomplete, withSitePages, withTotals, withTrendBusy,
} from './helpers/resourceScanRealFixtures.js'

const slot = (name, root = document) => root.querySelector(`[data-slot="${name}"]`)
const slots = (name, root = document) => [...root.querySelectorAll(`[data-slot="${name}"]`)]
const esc = (s) => s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
const openTab = (label) => fireEvent.mouseDown(screen.getByRole('tab', { name: new RegExp(`^${esc(label)}`) }), { button: 0 })

/** Ham sunucu kodları (arayüz bunları ÇEVİRİR; çıplak görünmeleri hata). */
const RAW_CODES = [
  'no_async_defer', 'no_media', 'css_import', 'same_content', 'same_path', 'unsafe_inline', 'unsafe_eval', 'not_https',
  'no_referrer_when_downgrade', 'no_http_only', 'no_same_site', 'none_without_secure', 'not_measured', 'dns_failed',
  'prototype_pollution', 'PAGE_HTTP', 'PAGE_FAILED', 'PAGE_OFFSITE', 'PAGE_NOT_HTML', 'NOT_SCANNED', 'CONNECTION_REFUSED', 'not_done',
]
function scanText(where) {
  const attrs = [...document.querySelectorAll('[aria-label],[title]')].map((e) => `${e.getAttribute('aria-label') || ''} ${e.getAttribute('title') || ''}`)
  const text = `${document.body.textContent}\n${attrs.join('\n')}`
  const bad = []
  for (const re of [/\bundefined\b/, /\bNaN\b/, /\[object Object\]/, /\{\d\}/, /\bprs\.[a-z]+\.[a-zA-Z]/]) {
    const m = re.exec(text)
    if (m) bad.push(`${where}: ${m[0]} … ${text.slice(Math.max(0, m.index - 60), m.index + 60).replace(/\s+/g, ' ')}`)
  }
  for (const c of RAW_CODES) if (new RegExp(`(^|[^A-Za-z0-9_/.-])${c}([^A-Za-z0-9_]|$)`).test(text)) bad.push(`${where}: ham kod ${c}`)
  return bad
}

function mockApi({ detail = realDetail, list = realList, trend = realTrend } = {}) {
  api.monitoring.resourceScans.mockImplementation(() => Promise.resolve(list()))
  api.monitoring.resourceScan.mockImplementation((_m, id) => Promise.resolve(detail(id)))
  api.monitoring.resourceBudget.mockImplementation(() => Promise.resolve(realBudget()))
  api.monitoring.resourceScanTrend.mockImplementation(() => Promise.resolve(trend()))
}

beforeEach(() => {
  vi.clearAllMocks()
  resetWatchers()
  try { localStorage.clear(); localStorage.setItem(LANG_STORAGE_KEY, 'tr') } catch { /* yok */ }
  window.history.replaceState(null, '', '/?tab=page&monitor=77&mtab=resources')
  mockApi()
})
afterEach(() => { resetWatchers() })

async function mount(scanId) {
  if (scanId) window.history.replaceState(null, '', `/?tab=page&monitor=77&mtab=resources&prs=${scanId}`)
  render(<ResourceScanTab monitor={REAL_MONITOR} pollMs={15} />)
  await waitFor(() => expect(slot('prs-hero')).not.toBeNull())
}

/** Bütün bölümleri gezer, her görünümün metnini tarar. */
async function walk(D, { pages = true } = {}) {
  const bad = [...scanText('pano')]
  // bütçe penceresi (gerçek bütçe ucu)
  fireEvent.click(slot('prs-budget-open'))
  await waitFor(() => expect(slot('prs-budget-dialog')?.getAttribute('data-state')).toBe('ok'))
  await waitFor(() => expect(document.querySelector('[data-budget-key="max_page_kb"]')?.value).toBe('200'))
  bad.push(...scanText('bütçe penceresi'))
  fireEvent.keyDown(document.activeElement || document.body, { key: 'Escape' })
  await waitFor(() => expect(slot('prs-budget-dialog')).toBeNull())
  openTab(D['prs.sec.audit'])
  await waitFor(() => expect(slot('prs-audit')).not.toBeNull(), { timeout: 5000 })
  for (const a of ['loading', 'waste', 'media', 'security', 'seo', 'a11y']) {
    fireEvent.mouseDown(slots('prs-audit-tab').find((x) => x.getAttribute('data-area') === a), { button: 0 })
    await waitFor(() => expect(slot('prs-audit').getAttribute('data-area')).toBe(a))
    bad.push(...scanText(`denetim ${a}`))
  }
  if (pages) {
    openTab(D['prs.site.tab'])
    await waitFor(() => expect(slot('prs-site')).not.toBeNull())
    bad.push(...scanText('sayfalar'))
  }
  openTab(D['prs.trend.tab'])
  await waitFor(() => expect(slot('prs-trend-view')?.getAttribute('data-state')).toBe('ok'))
  bad.push(...scanText('eğilim'))
  openTab(D['prs.sec.history'])
  await waitFor(() => expect(slot('prs-history-row')).not.toBeNull())
  bad.push(...scanText('geçmiş'))
  return bad
}

describe('gerçek yanıt — bütün görünümler (TR + EN)', () => {
  it('site analizi (#3) TR: ham kod / anahtar / undefined yok; sayfa sayıları tutarlı; sayfa hatası sunucu cümlesi', async () => {
    await mount()
    expect(slot('prs-budget-band').getAttribute('data-state')).toBe('exceeded')
    expect(slots('prs-budget-row')).toHaveLength(7)
    // Sınır içinde olan tek kalem (yüklenme 69 ms ≤ 200 ms) — "Sınırda" DEĞİL
    const load = slots('prs-budget-row').find((r) => r.getAttribute('data-key') === 'max_load_ms')
    expect(load.getAttribute('data-ok')).toBe('ok')
    expect(load.textContent).toMatch(/Sınır içinde/)
    expect(slot('prs-sec-pages-count').textContent).toBe('3/5')
    const bad = await walk(TR)
    expect(bad).toEqual([])
  }, 30_000)

  it('Sayfalar: "3 / 5 sayfa analiz edildi", açılamayan sayfada sunucunun cümlesi, ortak kaynak sayısı; geçmişte aynı anlam', async () => {
    await mount()
    openTab(TR['prs.site.tab'])
    await waitFor(() => expect(slot('prs-site')).not.toBeNull())
    expect(slot('prs-site-summary').textContent).toMatch(/3 \/ 5 sayfa analiz edildi/)
    const errs = slots('prs-site-error').map((e) => e.textContent)
    expect(errs).toEqual(expect.arrayContaining([expect.stringMatching(/HTTP 500 döndürdü/), expect.stringMatching(/HTTP 404 döndürdü/)]))
    expect(slots('prs-site-tile').find((x) => x.getAttribute('data-key') === 'shared').textContent).toMatch(/7/)
    expect(slot('prs-site-capped')).toBeNull()
    openTab(TR['prs.sec.history'])
    await waitFor(() => expect(slot('prs-history-scope')).not.toBeNull())
    expect(slot('prs-history-scope').textContent).toMatch(/Site · 3 sayfa analiz edildi/)
  })

  it('EN: aynı tarama İngilizce arayüzde (ham kod / anahtar yok)', async () => {
    try { localStorage.setItem(LANG_STORAGE_KEY, 'en') } catch { /* yok */ }
    await mount()
    const bad = await walk(EN)
    expect(bad).toEqual([])
  }, 30_000)

  it('tek sayfa analizi (#1): sayfalar sekmesi yok; gezinti temiz', async () => {
    await mount(1)
    expect(slot('prs-sec-pages')).toBeNull()
    const bad = await walk(TR, { pages: false })
    expect(bad).toEqual([])
  }, 30_000)
})

describe('gerçek yanıt + inceleme sonrası tel değişiklikleri', () => {
  it('HTML okunamadı (be1 D-1): yükleme / israf / görsel alanı "bilinmiyor", asla "Sorun yok"; puan bileşeni hesaplanamadı', async () => {
    mockApi({ detail: (id) => (id === 1 ? { success: true, data: withHtmlUnread(realDetail(1).data) } : realDetail(id)) })
    await mount(1)
    const rb = slots('prs-score').find((x) => x.getAttribute('data-key') === 'render_blocking')
    expect(slot('prs-score-unknown', rb)).not.toBeNull()
    expect(rb.textContent).toMatch(/—\/100/)
    openTab(TR['prs.sec.audit'])
    await waitFor(() => expect(slot('prs-audit')).not.toBeNull(), { timeout: 5000 })
    const st = (a) => slots('prs-audit-tab').find((x) => x.getAttribute('data-area') === a).getAttribute('data-state-summary')
    // yükleme alanında öneri var (MISSING_PRECONNECT …) → bulgu; israf / görselde öneri olsa da okunamayan HTML "ok" yapmaz
    for (const a of ['loading', 'waste', 'media']) expect(st(a)).not.toBe('ok')
    fireEvent.mouseDown(slots('prs-audit-tab').find((x) => x.getAttribute('data-area') === 'loading'), { button: 0 })
    await waitFor(() => expect(slot('prs-a-rb-unknown')).not.toBeNull())
    expect(slot('prs-a-rb').textContent).not.toMatch(new RegExp(esc(TR['prs.deep.rb.none'])))
  })

  it('güvenlik başlıkları okunamadı: güvenlik alanı "Sorun yok" demez', async () => {
    const base = realDetail(1).data
    const d = withSecurityUnread(base)
    d.analysis.recommendations = d.analysis.recommendations.filter((r) => !['INSECURE_COOKIES', 'MISSING_SRI', 'SECURITY_HEADERS_MISSING',
      'VULNERABLE_LIBRARY', 'WEAK_CSP', 'MIXED_CONTENT', 'THIRD_PARTY_TRACKING'].includes(r.code))
    mockApi({ detail: (id) => (id === 1 ? { success: true, data: d } : realDetail(id)) })
    await mount(1)
    openTab(TR['prs.sec.audit'])
    await waitFor(() => expect(slot('prs-audit')).not.toBeNull(), { timeout: 5000 })
    const sec = slots('prs-audit-tab').find((x) => x.getAttribute('data-area') === 'security')
    expect(sec.getAttribute('data-state-summary')).toBe('unknown')
    expect(sec.textContent).not.toMatch(/Sorun yok/)
  })

  it('bütçe: kısmi analizde kalem "Kısmi ölçüm" + değer (çelişki yok); okunamayan değerlendirme "aşılan yok" demez', async () => {
    mockApi({ detail: (id) => (id === 2 ? { success: true, data: withBudgetCapped(realDetail(2).data) }
      : id === 1 ? { success: true, data: withBudgetUnreadable(realDetail(1).data) } : realDetail(id)) })
    await mount(2)
    const row = slots('prs-budget-row').find((r) => r.getAttribute('data-key') === 'max_load_ms')
    expect(row.getAttribute('data-ok')).toBe('unknown')
    expect(row.getAttribute('data-reason')).toBe('capped')
    expect(row.textContent).toMatch(/Kısmi ölçüm/)
    expect(row.textContent).toMatch(/kısmi ölçümde 50 ms — kesin değil/)
    expect(row.textContent).not.toMatch(/Ölçülemedi/)
    cleanup()
    await mount(1)
    expect(slot('prs-budget-band').getAttribute('data-state')).toBe('unreadable')
    expect(slot('prs-budget-band').textContent).toMatch(/Bütçe değerlendirmesi okunamadı/)
    expect(slot('prs-budget-band').textContent).not.toMatch(/Aşılan kalem yok/)
    expect(slots('prs-budget-row')).toHaveLength(0)
  })

  it('site eksik + kod biçiminde hatalar: taranmayan sayfa notu, kod çevirisi (ham kod yok), ortak kaynak toplamı 120', async () => {
    mockApi({ detail: (id) => (id === 3 ? { success: true, data: withSiteIncomplete(realDetail(3).data, { asCodes: true }) } : realDetail(id)) })
    await mount()
    openTab(TR['prs.site.tab'])
    await waitFor(() => expect(slot('prs-site')).not.toBeNull())
    expect(slot('prs-site-capped').textContent).toMatch(/1 sayfa taranmadı/)
    expect(slot('prs-site-summary').textContent).toMatch(/3 \/ 6 sayfa analiz edildi/)
    const texts = [...slots('prs-site-error'), ...slots('prs-site-skip')].map((e) => e.textContent)
    expect(texts).toEqual(expect.arrayContaining([
      expect.stringMatching(/başka bir siteye yönlendirdi/), expect.stringMatching(/Sayfaya ulaşılamadı/), expect.stringMatching(/indirme sınırı doldu/)]))
    expect(slots('prs-site-tile').find((x) => x.getAttribute('data-key') === 'shared').textContent).toMatch(/120/)
    expect(slot('prs-site-shared').textContent).toMatch(/\+113 ortak kaynak daha/)
    expect(scanText('site eksik')).toEqual([])
  })

  it('üçüncü taraf host listesi kesik → "+N"; biçimi okunamayan yazı tipi sayısı söylenir', async () => {
    mockApi({ detail: (id) => (id === 1 ? { success: true, data: withTotals(realDetail(1).data) } : realDetail(id)) })
    await mount(1)
    openTab(TR['prs.sec.audit'])
    await waitFor(() => expect(slot('prs-audit')).not.toBeNull(), { timeout: 5000 })
    fireEvent.mouseDown(slots('prs-audit-tab').find((x) => x.getAttribute('data-area') === 'media'), { button: 0 })
    await waitFor(() => expect(slot('prs-c-font-unknown')).not.toBeNull())
    expect(slot('prs-c-font-unknown').textContent).toMatch(/Biçimi okunamayan 2 yazı tipi/)
    fireEvent.mouseDown(slots('prs-audit-tab').find((x) => x.getAttribute('data-area') === 'security'), { button: 0 })
    await waitFor(() => expect(slot('prs-d-tp-hosts-more')).not.toBeNull())
    expect(slot('prs-d-tp-hosts-more').textContent).toMatch(/\+7 sunucu daha/)
  })

  it('eğilim: büyüme şu an hesaplanamadı (asla "büyüyen nesne yok") + kesilen pencere notu', async () => {
    mockApi({ trend: () => withTrendBusy(realTrend()) })
    await mount()
    openTab(TR['prs.trend.tab'])
    await waitFor(() => expect(slot('prs-trend-view')?.getAttribute('data-state')).toBe('ok'))
    expect(slot('prs-tv-growth-unavailable').textContent).toMatch(/şu an hesaplanamadı/)
    expect(slot('prs-tv-growth-empty')).toBeNull()
    expect(slot('prs-tv-truncated').textContent).toMatch(/yalnız en yeni 200 analiz/)
  })

  it('süren site koşusu: sayfa listesi seçilmeden "Site"; seçilince "Site · sayfa 2 / 5" (be2 biçimi)', async () => {
    let polls = 0
    api.monitoring.startResourceScan.mockResolvedValue(realStartSite())
    api.monitoring.resourceScan.mockImplementation((_m, id) => {
      if (id !== 3) return Promise.resolve(realDetail(id))
      polls += 1
      return Promise.resolve(polls < 2 ? realRunningSite() : withSitePages(realRunningSite(), 2, 5))
    })
    await mount(1)
    pressMenuTrigger(slot('prs-start-more'))
    fireEvent.click(await screen.findByRole('menuitem', { name: /Siteyi analiz et/ }))
    await waitFor(() => expect(slot('prs-site-dialog')).not.toBeNull())
    fireEvent.click(slot('prs-site-confirm'))
    await waitFor(() => expect(slot('prs-run-site')).not.toBeNull())
    await waitFor(() => expect(slot('prs-run-site').textContent).toBe('Site · sayfa 2 / 5'))
  })
})
