import { describe, it, expect, vi, afterEach } from 'vitest'
import { render } from './test-utils.jsx'

/**
 * Olay ayrıntısı — "Etki ve sınıflandırma" kartındaki iki isteğe bağlı satır (2026-11-20): "Teknik hata adedi" ve "Kesinti
 * kaynaklı". Satırlar YALNIZ değer girilmişse çizilir: girilmemiş (null / anahtar yok) kayıtta kart eskisiyle aynıdır;
 * bilinmeyen cevap asla "Hayır" yazılmaz. Adet arayüz dilinin sayı biçimiyle (TR 1.250 · EN 1,250), kesinti ID yalnız cevap
 * Evet iken gösterilir.
 */
vi.mock('../api/client', () => ({ api: {}, formatDate: (s) => s ?? '' }))
vi.mock('../components/ui/MarkdownEditor.jsx', () => ({ default: ({ value }) => <div data-slot="md-view">{value}</div> }))

import HistoryDetailSheet from '../components/incidenthistory/HistoryDetailSheet.jsx'

const rec = (over = {}) => ({
  id: 5, title: 'Ödeme kesintisi', occurred_at: '2026-10-01T06:00:00', severity: 'CRITICAL', status: 'OPEN',
  category: 'NETWORK', team_id: 1, team_name: 'Takım A', sla_breached: false, ...over,
})

afterEach(() => { localStorage.removeItem('site-monitor-lang') })

function show(over, lang = 'tr') {
  localStorage.setItem('site-monitor-lang', lang)
  const view = render(<HistoryDetailSheet record={rec(over)} onClose={() => {}} />)
  const facts = document.querySelector('[data-slot="ih-detail-facts"]')
  const row = (slot) => facts.querySelector(`[data-fact="${slot}"]`)
  /** "Etiket: değer" (dt + dd) — satır yoksa null. */
  const text = (slot) => {
    const el = row(slot)
    return el ? `${el.querySelector('dt').textContent}: ${el.querySelector('dd').textContent}` : null
  }
  return { ...view, facts, row, text }
}

describe('HistoryDetailSheet — teknik hata adedi ve kesinti kaynaklı', () => {
  it('girilmemiş kayıtta iki satır da YOK (anahtar yok ≡ null); bilinmeyen cevap "Hayır" yazılmaz', () => {
    const absent = show({})
    expect(absent.row('tech-faults')).toBeNull()
    expect(absent.row('outage')).toBeNull()
    expect(absent.facts.textContent).not.toMatch(/Teknik hata adedi|Kesinti kaynaklı|Kesinti ID|Hayır/)
    const labelsAbsent = [...absent.facts.querySelectorAll('dt')].map((d) => d.textContent)
    absent.unmount()

    const nulls = show({ tech_error_count: null, outage_related: null, outage_id: null })
    expect(nulls.row('tech-faults')).toBeNull()
    expect(nulls.row('outage')).toBeNull()
    // Kartın satırları anahtarsız kayıtla AYNI (yeni alanlar görünümü değiştirmez)
    expect([...nulls.facts.querySelectorAll('dt')].map((d) => d.textContent)).toEqual(labelsAbsent)
    nulls.unmount()

    // Cevap verilmemişken başıboş bir kimlik de satır açmaz
    const stray = show({ outage_related: null, outage_id: 'INC-1' })
    expect(stray.row('outage')).toBeNull()
    expect(stray.facts.textContent).not.toContain('INC-1')
  })

  it('Türkçe: "Teknik hata adedi: 1.250" ve "Kesinti kaynaklı: Evet · Kesinti ID: INC-2026-0915"', () => {
    const v = show({ tech_error_count: 1250, outage_related: true, outage_id: 'INC-2026-0915' })
    expect(v.text('tech-faults')).toBe('Teknik hata adedi: 1.250')
    expect(v.text('outage')).toBe('Kesinti kaynaklı: Evet · Kesinti ID: INC-2026-0915')
    // "Error Budget" satırından sonra, "Kaydeden"den önce
    const labels = [...v.facts.querySelectorAll('dt')].map((d) => d.textContent)
    expect(labels.indexOf('Teknik hata adedi')).toBeGreaterThan(labels.indexOf('Error Budget Tüketimi (%)'))
    expect(labels.indexOf('Kesinti kaynaklı')).toBe(labels.indexOf('Teknik hata adedi') + 1)
    expect(labels.indexOf('Kaydeden')).toBe(labels.indexOf('Kesinti kaynaklı') + 1)
  })

  it('İngilizce: sayı arayüz dilinde (1,250) ve metinler çevrili', () => {
    const v = show({ tech_error_count: 1250, outage_related: true, outage_id: 'INC-2026-0915' }, 'en')
    expect(v.text('tech-faults')).toBe('Technical error count: 1,250')
    expect(v.text('outage')).toBe('Caused by an outage: Yes · Outage ID: INC-2026-0915')
  })

  it('Evet ama kimlik yok → yalnız "Evet"; Hayır → "Hayır" (başıboş kimlik gösterilmez)', () => {
    const yes = show({ outage_related: true, outage_id: null })
    expect(yes.text('outage')).toBe('Kesinti kaynaklı: Evet')
    expect(yes.row('tech-faults')).toBeNull()
    yes.unmount()

    const blank = show({ outage_related: true, outage_id: '   ' })
    expect(blank.text('outage')).toBe('Kesinti kaynaklı: Evet')
    blank.unmount()

    const no = show({ outage_related: false, outage_id: 'INC-ESKI' })
    expect(no.text('outage')).toBe('Kesinti kaynaklı: Hayır')
    expect(no.facts.textContent).not.toContain('INC-ESKI')
  })

  it('0 adet girilmiş bir değerdir (satır çizilir); büyük sayı binlik ayraçlı; okunamayan adet satır açmaz', () => {
    const zero = show({ tech_error_count: 0 })
    expect(zero.text('tech-faults')).toBe('Teknik hata adedi: 0')
    expect(zero.row('outage')).toBeNull()
    zero.unmount()

    const big = show({ tech_error_count: 1000000000000000 })
    expect(big.text('tech-faults')).toBe('Teknik hata adedi: 1.000.000.000.000.000')
    big.unmount()

    for (const bad of ['abc', -5, 12.5, '']) {
      const v = show({ tech_error_count: bad })
      expect(v.row('tech-faults'), `adet ${JSON.stringify(bad)}`).toBeNull()
      v.unmount()
    }
  })

  it('uzun kesinti ID kırpılmaz: değer hücresi sarar (overflow-wrap) ve kimliğin tamamı okunur', () => {
    const id = 'INC-2026-0915-ODEME-SISTEMLERI-KART-ISLEMLERI-BOLGE-MERKEZ-0001'
    const v = show({ outage_related: true, outage_id: id })
    const dd = v.row('outage').querySelector('dd')
    expect(dd.textContent).toBe(`Evet · Kesinti ID: ${id}`)
    expect(dd.className).toContain('[overflow-wrap:anywhere]')
    expect(dd.className).not.toMatch(/truncate|line-clamp/)
  })
})
