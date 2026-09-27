import { describe, it, expect, beforeEach, vi } from 'vitest'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { render, screen, fireEvent, renderHook, act } from './test-utils.jsx'
import CardDensityToggle from '../components/ui/CardDensityToggle.jsx'
import { MonitorCard, MonitorCardRich, useMonitorCard } from '../components/monitoring/MonitorCard.jsx'
import { useCardDensity } from '../hooks/useCardDensity.js'

/**
 * KART YOĞUNLUĞU STANDARDI — Kompakt / Zengin (2026-09-27, kullanıcı isteği: "sertifika kartlarındaki kompakt ve
 * zengin özelliğini diğer izleme tiplerindeki kartlar için de yapalım").
 *
 * Genel Bakış ve dokuz izleme sayfası AYNI seçiciyi (ui/CardDensityToggle) ve AYNI durum kancasını (useCardDensity)
 * kullanır; ızgara `data-density` taşır (Kompakt'ta satır başına daha çok kart), kart `density` alır ve yalnız-Zengin
 * bölümler MonitorCardRich içindedir.
 */
const SRC = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const COMPONENTS = path.join(SRC, 'components')
const read = (rel) => fs.readFileSync(path.join(COMPONENTS, rel), 'utf8')

const PAGES = {
  PingMonitorPage: 'ping/PingMonitorCard.jsx',
  DnsMonitorPage: 'dns/DnsMonitorCard.jsx',
  DomainMonitorPage: 'domain/DomainMonitorCard.jsx',
  HttpMonitorPage: 'http/HttpMonitorCard.jsx',
  KeywordMonitorPage: 'keyword/KeywordMonitorCard.jsx',
  PageMonitorPage: 'page/PageMonitorCard.jsx',
  PageSpeedMonitorPage: 'pagespeed/PageSpeedMonitorCard.jsx',
  PortMonitorPage: 'port/PortMonitorCard.jsx',
  ScriptedMonitorPage: 'scripted/ScriptedMonitorCard.jsx',
}

/**
 * Henüz geçmemiş sayfalar — CIRCIR: yalnız KÜÇÜLÜR. Sayfa geçince buradan silinir; liste boşalınca bu küme kalkar.
 * (Geçiş dalgalar hâlinde yapılıyor; 2026-09-27.)
 */
const PENDING = new Set([
  // Satır başına BİR sayfa: paralel geçişte her ajan yalnız kendi satırını siler.
])

describe('kart yoğunluğu — sayfa sözleşmesi', () => {
  it('Genel Bakış ortak seçiciyi kullanır (satır içi ToggleGroup yok)', () => {
    const app = fs.readFileSync(path.join(SRC, 'App.jsx'), 'utf8')
    expect(app).toMatch(/<CardDensityToggle\b/)
    expect(app).not.toMatch(/<ToggleGroup\b/)
  })

  for (const [page, card] of Object.entries(PAGES)) {
    it(`${page}: seçici + kanca + ızgara data-density + kart density`, () => {
      if (PENDING.has(page)) return
      const src = read(`${page}.jsx`)
      expect(src, 'useCardDensity(<sayfa>) yok').toMatch(/useCardDensity\(\s*'[a-z]+'/)
      expect(src, '<CardDensityToggle> yok').toMatch(/<CardDensityToggle\b/)
      expect(src, 'ızgara data-density taşımıyor').toMatch(/className="upt-grid"[^>]*data-density=\{|data-density=\{[^}]+\}[^>]*className="upt-grid"/)
      const cardSrc = read(card)
      expect(cardSrc, 'kart MonitorCard\'a density geçmiyor').toMatch(/<MonitorCard\b[^>]*\bdensity=\{/)
      expect(cardSrc, 'yalnız-Zengin bölüm MonitorCardRich içinde değil').toMatch(/<MonitorCardRich\b/)
    })
  }

  it('bekleme listesi yalnız küçülür: geçmiş bir sayfa listede unutulmamış', () => {
    const stale = [...PENDING].filter((p) => /<CardDensityToggle\b/.test(read(`${p}.jsx`)))
    expect(stale, 'geçen sayfayı PENDING\'den silin').toEqual([])
  })
})

describe('useCardDensity', () => {
  beforeEach(() => { localStorage.clear(); vi.restoreAllMocks() })

  // Kullanıcı kararı 2026-09-27: izleme sayfaları HER AÇILIŞTA Zengin başlar; Kompakt yalnız sayfada kalındığı sürece.
  it('izleme sayfası: varsayılan Zengin; Kompakt KALICI DEĞİL (yeniden açılınca Zengin); geçersiz değer yok sayılır', () => {
    const first = renderHook(() => useCardDensity('ping'))
    expect(first.result.current[0]).toBe('rich')
    act(() => first.result.current[1]('compact'))
    expect(first.result.current[0]).toBe('compact')
    act(() => first.result.current[1]('huge'))
    expect(first.result.current[0]).toBe('compact')
    expect(Object.keys(localStorage).filter((k) => /cardMode|density/i.test(k))).toEqual([])
    first.unmount()
    // sayfa yeniden açıldı → Zengin
    expect(renderHook(() => useCardDensity('ping')).result.current[0]).toBe('rich')
  })

  it('eski sürümden kalan kayıtlı Kompakt tercihi izleme sayfasını Kompakt AÇMAZ', () => {
    localStorage.setItem('sm.cardMode.http', 'compact')
    expect(renderHook(() => useCardDensity('http')).result.current[0]).toBe('rich')
  })

  it('depolama engelliyse de çalışır (hiç okumaz/yazmaz)', () => {
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => { throw new DOMException('blocked', 'SecurityError') })
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => { throw new DOMException('blocked', 'SecurityError') })
    const { result } = renderHook(() => useCardDensity('dns'))
    expect(result.current[0]).toBe('rich')
    act(() => result.current[1]('compact'))
    expect(result.current[0]).toBe('compact')
  })

  it('Genel Bakış: ilk açılışta Zengin, oturum içinde son seçim (App durumu), tarayıcıya yazılmaz, çıkışta Zengin', () => {
    const app = fs.readFileSync(path.join(SRC, 'App.jsx'), 'utf8')
    expect(app).toMatch(/const \[cardMode, setCardMode\] = useState\('rich'\)/)
    expect(app).not.toMatch(/dash-card-mode/)
    expect(app.match(/setCardMode\('rich'\)/g)?.length, "iki çıkış yolu da Zengin'e döndürmeli").toBe(2)
  })
})

describe('CardDensityToggle + MonitorCardRich', () => {
  it('seçici: iki seçenek, seçili olana tıklamak boşaltmaz, diğerine tıklamak bildirir', () => {
    const onChange = vi.fn()
    render(<CardDensityToggle value="rich" onChange={onChange} />)
    const group = document.querySelector('[data-slot="card-density-toggle"]')
    expect(group).toHaveAttribute('aria-label')
    fireEvent.click(screen.getByRole('radio', { name: /Zengin|Rich/ }))
    expect(onChange).not.toHaveBeenCalled()
    fireEvent.click(screen.getByRole('radio', { name: /Kompakt|Compact/ }))
    expect(onChange).toHaveBeenCalledWith('compact')
  })

  it('MonitorCard data-density taşır; MonitorCardRich yalnız Zengin\'de DOM\'a girer', () => {
    function Probe() { return <span data-testid="d">{useMonitorCard().density}</span> }
    const { rerender } = render(<MonitorCard density="compact"><Probe /><MonitorCardRich><p>ayrıntı</p></MonitorCardRich></MonitorCard>)
    expect(document.querySelector('[data-slot="card"]')).toHaveAttribute('data-density', 'compact')
    expect(screen.getByTestId('d')).toHaveTextContent('compact')
    expect(screen.queryByText('ayrıntı')).toBeNull()
    rerender(<MonitorCard><Probe /><MonitorCardRich><p>ayrıntı</p></MonitorCardRich></MonitorCard>)
    expect(document.querySelector('[data-slot="card"]')).toHaveAttribute('data-density', 'rich')
    expect(screen.getByText('ayrıntı').closest('[data-slot="monitor-card-rich"]')).not.toBeNull()
  })
})
