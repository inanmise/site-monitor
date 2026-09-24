import { describe, it, expect } from 'vitest'
import { readFileSync } from 'node:fs'

/**
 * "Sizin için — bugün" yerleşim kuralları (2026-09-24, kullanıcı ekran görüntüsü). jsdom yerleşim yapmaz —
 * taşma ve hiza tarayıcıda doğrulandı; burada KAYNAK kuralı pinlenir ki bir sonraki stil düzenlemesi
 * sessizce geri almasın.
 *
 * 1) Teslim edilemeyen bildirimin uzun hata metni (`.today-days`, nowrap) kartın ~200px dışına taşıp yandaki
 *    kartın üstüne biniyordu → genişlik sınırı + "…".
 * 2) Satırdaki kartlar farklı boyda bitiyor, "Tümünü gör" farklı hizalarda duruyordu → ızgara stretch (eşit
 *    yükseklik), bağlantı kartın dibinde (margin-top:auto).
 */
const css = readFileSync('src/App.css', 'utf8')
const rule = (sel) => {
  const m = css.match(new RegExp(`(^|\\n)${sel.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}\\s*\\{([^}]*)\\}`))
  if (!m) throw new Error(`kural yok: ${sel}`)
  return m[2]
}

describe('Sizin için — bugün: kart taşması ve hiza', () => {
  it('.today-days tek satırda kalır ama kart genişliğini aşamaz ("…" ile kesilir)', () => {
    const r = rule('.today-days')
    expect(r).toMatch(/white-space:\s*nowrap/)
    expect(r).toMatch(/max-width:\s*100%/)
    expect(r).toMatch(/overflow:\s*hidden/)
    expect(r).toMatch(/text-overflow:\s*ellipsis/)
  })

  it('.today-muted uzun, boşluksuz metni (URL, e-posta) kırar; .today-finding rozeti de kart içinde kalır', () => {
    expect(rule('.today-muted')).toMatch(/overflow-wrap:\s*anywhere/)
    const f = rule('.today-finding')
    expect(f).toMatch(/max-width:\s*100%/)
    expect(f).toMatch(/text-overflow:\s*ellipsis/)
  })

  it('"Tümünü gör" penceresi ayrıntı görünümü: uzun metin kesilmez, pencereyi kaydırmadan alt satıra sarar', () => {
    const r = rule('.today-modal-row .today-days')
    expect(r).toMatch(/white-space:\s*normal/)
    expect(r).toMatch(/overflow-wrap:\s*anywhere/)
  })

  it('ızgara kartları eşit yükseklikte açar (align-items:start YOK) ve "Tümünü gör" kartın dibindedir', () => {
    expect(rule('.today-grid')).not.toMatch(/align-items:\s*start/)
    expect(rule('.today-card')).toMatch(/flex-direction:\s*column/)
    expect(rule('.today-card-go')).toMatch(/margin-top:\s*auto/)
  })
})
