import { describe, it, expect, beforeEach } from 'vitest'
import { render, fireEvent } from './test-utils.jsx'
import ImageInsights from '../components/page/resourcescan/imagetool/ImageInsights.jsx'
import { resultSummary } from '../components/page/resourcescan/imagetool/imageToolModel.js'
import { LANG_STORAGE_KEY } from '../i18n/dateLocale.js'

const slot = (name, root = document) => root.querySelector(`[data-slot="${name}"]`)
const slots = (name, root = document) => [...root.querySelectorAll(`[data-slot="${name}"]`)]

function summary(over = {}) {
  return resultSummary({
    bytes: 4_000, width: 200, height: 80, type: 'png', savingPct: 55.5, kept: false, resized: false, quality: null, colors: 12,
    similarity: 0.99, notes: [],
    original: { bytes: 9_000, width: 200, height: 80, type: 'png', hasAlpha: true },
    analysis: {
      input: { bitDepth: 16, colorModel: 'rgba', ppi: { x: 96, y: 96 } },
      output: { bitDepth: 4, colorModel: 'palette', paletteSize: 12, ppi: null, ppiChecked: true },
      colors: { count: 12, atLeast: false },
      candidates: [{ type: 'png', bytes: 4_000, colors: 12 }, { type: 'webp', bytes: 5_200, quality: 80 }],
      svgCandidate: true, banding: 'bit_depth',
    },
    ...over,
  })
}

beforeEach(() => {
  try { localStorage.clear(); localStorage.setItem(LANG_STORAGE_KEY, 'tr') } catch { /* yok */ }
})

describe('görsel aracı — ayrıntılı karşılaştırma bölümü', () => {
  it('kapalı başlar; açılınca tablo, renk sayısı, biçimler, öneriler ve adımlar — ham anahtar yok', () => {
    render(<ImageInsights result={summary()} />)
    const toggle = slot('prs-it-insights-toggle')
    expect(toggle.getAttribute('aria-expanded')).toBe('false')
    expect(slot('prs-it-ins-table')).toBeNull()
    fireEvent.click(toggle)
    expect(toggle.getAttribute('aria-expanded')).toBe('true')
    const rows = slots('prs-it-ins-row')
    const by = Object.fromEntries(rows.map((r) => [r.getAttribute('data-key'), r]))
    expect(by.dims.textContent).toMatch(/200 × 80/)
    expect(by.ppi.textContent).toMatch(/96 PPI/)
    expect(by.ppi.textContent).toMatch(/Kayıtlı değil/)
    expect(by.color.textContent).toMatch(/RGB \+ saydamlık · 16 bit/)
    expect(by.color.textContent).toMatch(/Palet \(12 renk\)/)
    expect(by.color.getAttribute('data-changed')).toBe('true')
    expect(by.size.getAttribute('data-changed')).toBe('true')
    expect(by.dims.getAttribute('data-changed')).toBeNull()
    expect(slot('prs-it-ins-colors').textContent).toMatch(/12 farklı renk/)
    const gains = slots('prs-it-ins-gain')
    expect(gains.map((g) => g.getAttribute('data-type'))).toEqual(['png', 'webp'])
    expect(gains[0].getAttribute('data-chosen')).toBe('true')
    expect(gains[0].textContent).toMatch(/−%56/)
    const recs = slots('prs-it-ins-rec').map((r) => r.getAttribute('data-code'))
    expect(recs).toEqual(['SVG', 'BANDING'])
    expect(slot('prs-it-ins-recs').textContent).toMatch(/vektör \(SVG\)|Vektör \(SVG\)/)
    expect(slot('prs-it-ins-recs').textContent).toMatch(/16 bit renk derinliğinde/)
    const steps = slots('prs-it-ins-step').map((s) => s.getAttribute('data-code'))
    expect(steps).toEqual(['LOCAL', 'PNG_PALETTE', 'METADATA'])
    expect(slot('prs-it-ins-how').textContent).toMatch(/12 renklik bir palete/)
    expect(document.body.textContent).not.toMatch(/prs\.it\.ins|undefined|NaN/)
  })

  it('analiz yoksa (eski motor) bölüm hiç çizilmez', () => {
    const { container } = render(<ImageInsights result={summary({ analysis: undefined })} />)
    expect(container.querySelector('[data-slot="prs-it-insights"]')).toBeNull()
  })

  it('İngilizce: satır ve adım metinleri İngilizce', () => {
    localStorage.setItem(LANG_STORAGE_KEY, 'en')
    render(<ImageInsights result={summary()} />)
    fireEvent.click(slot('prs-it-insights-toggle'))
    expect(slot('prs-it-ins-table').textContent).toMatch(/Pixel density/)
    expect(slot('prs-it-ins-how').textContent).toMatch(/12-colour palette/)
    expect(document.body.textContent).not.toMatch(/prs\.it\.ins/)
  })
})
