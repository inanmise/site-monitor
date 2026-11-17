import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import fs from 'node:fs'
import path from 'node:path'
import { act, fireEvent, render } from './test-utils.jsx'
import { GAPS_WIDGET_CATEGORIES, GapsWidget } from '../components/mydash/gapsWidget.jsx'
import { WIDGET_CATALOG } from '../components/mydash/myDashModel.js'
import { WIDGETS } from '../components/mydash/widgets.jsx'
import { onGapsRefreshRequest, onOpenGapsDialog, publishGapsSummary } from '../components/gaps/gapsBus.js'
import { GAP_CATEGORIES, GAPS_SESSION_KEY } from '../components/gaps/gapsModel.js'
import { gapsBadgeCount, sessionGapsCount } from '../components/gaps/useGapsSummary.js'
import { LANG_STORAGE_KEY } from '../i18n/dateLocale.js'

/**
 * Eksikler özeti (2026-11-15, D paketi): kullanıcı menüsü rozeti ve Kişisel Panom "Eksiklerim" kutusu. İkisi de ağa çıkmaz;
 * özeti eksikler penceresi yayınlar. Bilinmeyen hiçbir zaman yeşil değildir.
 */
const slot = (name, root = document) => root.querySelector(`[data-slot="${name}"]`)
const frame = (over = {}) => ({
  widget: { id: 'g', type: 'my_gaps', size: 'sm' }, edit: false, tv: false, canPrev: false, canNext: false,
  onMove: () => {}, onResize: () => {}, onRemove: () => {}, onStyle: () => {}, stat: false, ...over,
})
const READY = { status: 'ready', at: Date.now(), total: 4, high: 1, hidden: 0, unknown: false, complete: false,
  byCategory: { profile: 1, team: 2, monitors: 0, inventory: 0, tasks: 1 } }

let refreshes = 0
let opens = 0
let offs = []
beforeEach(() => {
  publishGapsSummary(null)
  refreshes = 0
  opens = 0
  offs = [onGapsRefreshRequest(() => { refreshes++ }), onOpenGapsDialog(() => { opens++ })]
  try { localStorage.clear(); localStorage.setItem(LANG_STORAGE_KEY, 'tr') } catch { /* yok */ }
})
afterEach(() => { offs.forEach((f) => f()); publishGapsSummary(null) })

describe('eksikler özeti — rozet', () => {
  it('rozet sayısı: hazır özetin toplamı; okunuyorsa / okunmadıysa oturum sayısı; 0 ya da bilinmiyorsa rozet yok', () => {
    expect(gapsBadgeCount({ status: 'ready', total: 3 }, 9)).toBe(3)
    expect(gapsBadgeCount({ status: 'ready', total: 0 }, 9)).toBeNull()
    expect(gapsBadgeCount({ status: 'loading' }, 5)).toBe(5)
    expect(gapsBadgeCount(null, null)).toBeNull()
    expect(gapsBadgeCount(null, 0)).toBeNull()
  })

  it('oturum sayısı aynı anahtardan okunur; bozuk / aşırı değer yok sayılır', () => {
    const src = fs.readFileSync(path.resolve(__dirname, '../components/gaps/useGapsSummary.js'), 'utf8')
    expect(/SESSION_KEY = '([^']+)'/.exec(src)[1]).toBe(GAPS_SESSION_KEY)
    localStorage.setItem(GAPS_SESSION_KEY, JSON.stringify({ count: 7 }))
    expect(sessionGapsCount()).toBe(7)
    localStorage.setItem(GAPS_SESSION_KEY, '{bozuk')
    expect(sessionGapsCount()).toBeNull()
    localStorage.setItem(GAPS_SESSION_KEY, JSON.stringify({ count: -1 }))
    expect(sessionGapsCount()).toBeNull()
    localStorage.setItem(GAPS_SESSION_KEY, JSON.stringify({ count: 1e9 }))
    expect(sessionGapsCount()).toBeNull()
  })

  it('kullanıcı menüsü rozeti açılış paketinde: hafif modül eksikler modellerini içe aktarmaz', () => {
    const src = fs.readFileSync(path.resolve(__dirname, '../components/gaps/useGapsSummary.js'), 'utf8')
    expect(src).not.toMatch(/from '\.\/(gapsModel|gapsSources|gapsExtra|gapsProfile)/)
  })
})

describe('eksikler özeti — Kişisel Panom kutusu', () => {
  it('katalogda, çizilebilir, varsayılan düzende ve hazır düzenlerde değil; kategoriler pencereyle aynı', () => {
    const cat = WIDGET_CATALOG.find((w) => w.type === 'my_gaps')
    expect(cat).toBeTruthy()
    expect(cat.perm ?? null).toBeNull()
    expect(WIDGETS.my_gaps?.Component).toBe(GapsWidget)
    expect(/^[a-z_]{1,40}$/.test('my_gaps')).toBe(true)
    expect(GAPS_WIDGET_CATEGORIES).toEqual(GAP_CATEGORIES)
  })

  it('özet yoksa bir kez okuma ister (pencere açmaz); "Denetle" yeniden ister; düzenleme kipinde ve TV\'de istemez', () => {
    render(<GapsWidget frame={frame()} />)
    expect(refreshes).toBe(1)
    expect(opens).toBe(0)
    expect(slot('mydash-gaps-idle')).not.toBeNull()
    fireEvent.click(slot('mydash-gaps-check'))
    expect(refreshes).toBe(2)
  })

  it.each([['düzenleme', { edit: true }], ['TV', { tv: true }]])('%s kipinde okuma istenmez, düğme yok', (_n, over) => {
    render(<GapsWidget frame={frame(over)} />)
    expect(refreshes).toBe(0)
    expect(slot('mydash-gaps-check')).toBeNull()
  })

  it('eksik varsa sayı + kategori kutucukları; yüksek önemli eksik kırmızı; tıklama pencereyi açar', () => {
    publishGapsSummary(READY)
    render(<GapsWidget frame={frame()} />)
    expect(refreshes).toBe(0)
    const box = slot('mydash-gaps')
    expect(box.getAttribute('data-total')).toBe('4')
    expect([...box.querySelectorAll('[data-slot^="mydash-gaps-"]')].map((n) => n.getAttribute('data-slot')))
      .toEqual(expect.arrayContaining(['mydash-gaps-total', 'mydash-gaps-profile', 'mydash-gaps-team', 'mydash-gaps-tasks']))
    expect(slot('mydash-gaps-monitors')).toBeNull()
    expect(document.querySelector('[data-tone="danger"]')).not.toBeNull()
    fireEvent.click(slot('mydash-gaps-total'))
    expect(opens).toBe(1)
  })

  it('her şey okundu ve eksik yok → yeşil; bir kaynak okunamadıysa eksik yok bile gri ("kısmi")', () => {
    publishGapsSummary({ ...READY, total: 0, high: 0, complete: true, byCategory: { profile: 0, team: 0, monitors: 0, inventory: 0, tasks: 0 } })
    const r = render(<GapsWidget frame={frame()} />)
    expect(document.querySelector('[data-tone="success"]')).not.toBeNull()
    r.unmount()
    act(() => publishGapsSummary({ ...READY, total: 0, high: 0, complete: false, unknown: true,
      byCategory: { profile: 0, team: 0, monitors: 0, inventory: 0, tasks: 0 } }))
    render(<GapsWidget frame={frame()} />)
    expect(document.querySelector('[data-tone="success"]')).toBeNull()
    expect(document.body.textContent).toMatch(/Bazı denetimler okunamadı|okunamadı/)
  })

  it('düzenleme kipinde tıklama pencereyi açmaz', () => {
    publishGapsSummary(READY)
    render(<GapsWidget frame={frame({ edit: true })} />)
    const total = slot('mydash-gaps-total')
    if (total) fireEvent.click(total)
    expect(opens).toBe(0)
  })
})

describe('kişisel push tercihleri kartına bağlantı (ma_sec=push)', () => {
  it('ma_ öneki sayfa durumu ön ekleri arasında (adres çubuğu eşitlemesi tanır)', async () => {
    const { PAGE_STATE_PREFIXES } = await import('../hooks/useUrlQuerySync.js')
    expect(PAGE_STATE_PREFIXES).toContain('ma_')
  })
})

describe('eksikler penceresi — tembel modüller', () => {
  const dialogSrc = fs.readFileSync(path.resolve(__dirname, '../components/gaps/GapsDialog.jsx'), 'utf8')
  const promptSrc = fs.readFileSync(path.resolve(__dirname, '../components/gaps/GapsPromptCore.jsx'), 'utf8')
  it('dışa aktarma (Excel yazıcısı) yalnız tıklamada; düzeltme pencereleri tembel', () => {
    expect(dialogSrc).not.toMatch(/^import .*gapsExport/m)
    expect(dialogSrc).toMatch(/import\('\.\/gapsExport\.js'\)/)
    expect(promptSrc).toMatch(/lazy\(\(\) => import\('\.\/GapsFix\.jsx'\)\)/)
    expect(promptSrc).toMatch(/lazy\(\(\) => import\('\.\/GapsDialog\.jsx'\)\)/)
  })
})
