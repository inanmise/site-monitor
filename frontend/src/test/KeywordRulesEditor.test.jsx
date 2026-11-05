import { useState } from 'react'
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, fireEvent, within, waitFor, act } from './test-utils.jsx'
import KeywordRulesEditor from '../components/keyword/rules/KeywordRulesEditor.jsx'
import RuleTestResults from '../components/keyword/rules/RuleTestResults.jsx'
import { legacyRule } from '../components/keyword/rules/ruleModel.js'

const rule = (over = {}) => ({ id: 'r1', kind: 'TEXT', pattern: 'Fiyat', case_sensitive: false, operator: 'GTE', count: 1, critical: null, scope: null, label: null, ...over })

function Harness({ initial, logic = 'AND', spy, ...props }) {
  const [rules, setRules] = useState(initial)
  const [lg, setLg] = useState(logic)
  return <KeywordRulesEditor rules={rules} logic={lg} onChange={(r, l) => { setRules(r); setLg(l); spy?.(r, l) }} {...props} />
}

const cards = () => [...document.querySelectorAll('[data-slot="kw-rule-card"]')]
const field = (key) => document.querySelector(`[data-field="${key}"]`)
const fieldError = (key) => field(key)?.querySelector('[data-slot="field-error"]') ?? null

beforeEach(() => { localStorage.setItem('site-monitor-lang', 'tr') })
afterEach(() => { localStorage.setItem('site-monitor-lang', 'en') })

describe('KeywordRulesEditor', () => {
  it('eski izlemenin tek kuralı: VE / VEYA yok, silinemez; canlı açıklama sağlıklı + alarm cümlesi (sözlükten)', () => {
    render(<Harness initial={[legacyRule({ keyword: 'Hoş geldiniz', operator: 'GTE', match_count: 1 })]} monitorLevel="CRITICAL" />)
    expect(cards()).toHaveLength(1)
    expect(document.querySelector('[data-slot="kw-rule-logic"]')).toBeNull()
    expect(document.querySelector('[data-slot="kw-rule-delete"]')).toBeDisabled()
    expect(document.querySelector('[data-slot="kw-rule-desc-healthy"]').textContent).toBe('« Hoş geldiniz » sayfada en az bir kez geçmeli.')
    expect(document.querySelector('[data-slot="kw-rule-desc-warning"]').textContent).toBe('Alarm (Kritik): « Hoş geldiniz » sayfada hiç bulunmazsa.')
  })

  it('kural ekle: ikinci kart + VE / VEYA seçimi; odak yeni desende; 10’da ekleme kapanır', async () => {
    const spy = vi.fn()
    render(<Harness initial={[rule()]} spy={spy} />)
    fireEvent.click(screen.getByRole('button', { name: 'Kural ekle' }))
    expect(cards()).toHaveLength(2)
    await waitFor(() => expect(document.activeElement).toBe(cards()[1].querySelector('[data-slot="kw-rule-pattern"]')))
    expect(document.querySelector('[data-slot="kw-rule-logic"]')).not.toBeNull()
    fireEvent.click(screen.getByRole('button', { name: 'VEYA — en az biri' }))
    expect(spy).toHaveBeenLastCalledWith(expect.any(Array), 'OR')
    expect(document.querySelector('[data-slot="kw-rule-logic-hint"]').textContent).toMatch(/hiçbiri sağlanmazsa/)
    for (let i = 0; i < 8; i++) fireEvent.click(screen.getByRole('button', { name: 'Kural ekle' }))
    expect(cards()).toHaveLength(10)
    expect(screen.getByRole('button', { name: 'Kural ekle' })).toBeDisabled()
    expect(document.querySelector('[data-slot="kw-rule-count"]').textContent).toMatch(/En fazla 10/)
  })

  it('yukarı / aşağı taşıma sırayı değiştirir ve taşınan kartın düğmesi odakta kalır (sınırda karşı düğme)', async () => {
    render(<Harness initial={[rule({ id: 'a', pattern: 'Bir' }), rule({ id: 'b', pattern: 'İki' })]} />)
    const down = within(cards()[0]).getByRole('button', { name: 'Kural 1: aşağı taşı' })
    fireEvent.click(down)
    expect(cards().map((c) => c.closest('li').getAttribute('data-rule-id'))).toEqual(['b', 'a'])
    // Aşağı taşınan kart artık sonda — aşağı düğmesi kapalı, odak yukarı düğmesine
    await waitFor(() => expect(document.activeElement).toBe(cards()[1].querySelector('[data-move="up"]')))
  })

  it('silme onaylıdır; onaylanınca kart kalkar ve odak komşu karta geçer', async () => {
    render(<Harness initial={[rule({ id: 'a', pattern: 'Bir' }), rule({ id: 'b', pattern: 'İki' })]} />)
    fireEvent.click(within(cards()[0]).getByRole('button', { name: 'Kural 1: sil' }))
    const dlg = await screen.findByRole('dialog')
    expect(dlg).toHaveTextContent('Kural silinsin mi?')
    fireEvent.click(within(dlg).getByRole('button', { name: 'Kuralı sil' }))
    await waitFor(() => expect(cards()).toHaveLength(1))
    expect(cards()[0].closest('li').getAttribute('data-rule-id')).toBe('b')
  })

  it('adet koşulu kipi: işleç + adet; B10 (en az 0) ve boş adet dokunulunca alanın altında', () => {
    render(<Harness initial={[rule()]} />)
    fireEvent.click(screen.getByRole('button', { name: 'Adet koşulu' }))
    const count = document.querySelector('[data-slot="kw-rule-count-input"]')
    expect(count).not.toBeNull()
    fireEvent.change(count, { target: { value: '0' } })
    fireEvent.blur(count)
    expect(field('rules[0].count')).toHaveTextContent(/"En az 0 kez" her zaman sağlanır/)
    fireEvent.change(count, { target: { value: '' } })
    expect(field('rules[0].count')).toHaveTextContent(/pozitif bir tam sayı/)
    fireEvent.change(count, { target: { value: '5' } })
    expect(field('rules[0].count').textContent).not.toMatch(/tam sayı|sağlanır/)
    expect(document.querySelector('[data-slot="kw-rule-desc-healthy"]').textContent).toBe('« Fiyat » sayfada en az 5 kez geçmeli.')
  })

  it('iki seviyeli eşik: içermemeli kipinde kritik açılır (önerilen değerle), cümle iki seviye söyler; sıra hatası alanın altında', () => {
    render(<Harness initial={[rule({ pattern: 'hata', operator: 'LTE', count: 0 })]} showErrors />)
    const sw = document.querySelector('[data-slot="kw-rule-critical-toggle"]')
    expect(sw).not.toBeNull()
    fireEvent.click(sw)
    expect(document.querySelector('[data-slot="kw-rule-critical-count"]').value).toBe('5')
    expect(document.querySelector('[data-slot="kw-rule-desc-warning"]').textContent).toBe('Uyarı: « hata » sayfada bulunursa.')
    expect(document.querySelector('[data-slot="kw-rule-desc-critical"]').textContent).toBe('Kritik: « hata » sayfada 5 kezden fazla bulunursa.')
    fireEvent.change(document.querySelector('[data-slot="kw-rule-critical-count"]'), { target: { value: '0' } })
    expect(field('rules[0].critical')).toHaveTextContent(/uyarı sınırının ötesinde/)
  })

  it('içermeli kipinde kritik eşik sunulmaz (anlamsız)', () => {
    render(<Harness initial={[rule()]} />)
    expect(document.querySelector('[data-slot="kw-rule-critical"]')).toBeNull()
  })

  it('düzenli ifade: ipucu RE2; ileri bakış ve boş eşleşme dokunulunca reddedilir', () => {
    render(<Harness initial={[rule()]} />)
    fireEvent.click(screen.getByRole('button', { name: 'Düzenli ifade' }))
    const p = document.querySelector('[data-slot="kw-rule-pattern"]')
    fireEvent.change(p, { target: { value: 'a(?=b)' } })
    fireEvent.blur(p)
    expect(field('rules[0].pattern')).toHaveTextContent(/İleri \/ geri bakış/)
    fireEvent.change(p, { target: { value: 'x*' } })
    expect(field('rules[0].pattern')).toHaveTextContent(/boş metinle de eşleşir/)
    fireEvent.change(p, { target: { value: 'Sipariş no: \\d{6}' } })
    expect(fieldError('rules[0].pattern')).toBeNull()
    expect(field('rules[0].pattern')).toHaveTextContent(/RE2 sözdizimi/)
    expect(document.querySelector('[data-slot="kw-rule-desc-healthy"]').textContent).toBe('/Sipariş no: \\d{6}/ deseni sayfada en az bir kez geçmeli.')
  })

  it('kapsam: CSS seçici alanı çıkar, boşsa kayıt denemesinden sonra hata; açıklama bölgeyi söyler', () => {
    const { rerender } = render(<Harness initial={[rule()]} />)
    fireEvent.change(document.querySelector('[data-slot="kw-rule-scope"]'), { target: { value: 'CSS' } })
    expect(document.querySelector('[data-slot="kw-rule-scope-value"]')).not.toBeNull()
    expect(field('rules[0].scope').textContent).not.toMatch(/boş bırakılamaz/)
    rerender(<Harness initial={[rule()]} showErrors />)
    fireEvent.change(document.querySelector('[data-slot="kw-rule-scope"]'), { target: { value: 'CSS' } })
    expect(field('rules[0].scope')).toHaveTextContent(/CSS seçici boş bırakılamaz/)
    fireEvent.change(document.querySelector('[data-slot="kw-rule-scope-value"]'), { target: { value: '#durum' } })
    expect(document.querySelector('[data-slot="kw-rule-desc-healthy"]').textContent).toBe('« Fiyat » « #durum » bölgesinde en az bir kez geçmeli.')
  })

  it('sunucu alan hataları ilgili alanın altında; çizilmeyen alanın hatası kartın başında (kaybolmaz)', () => {
    render(<Harness initial={[rule(), rule({ id: 'r2', pattern: '(' , kind: 'REGEX' })]}
      errors={{ 'rules[1].pattern': 'Geçersiz desen (1. karakter): eksik ) — sunucu', 'rules[0].id': 'Kimlik mükerrer', rules: 'Kurallar eksik' }} />)
    expect(field('rules[1].pattern')).toHaveTextContent('Geçersiz desen (1. karakter): eksik ) — sunucu')
    expect(within(cards()[0]).getByText('Kimlik mükerrer')).toBeInTheDocument()
    expect(screen.getByText('Kurallar eksik')).toBeInTheDocument()
    expect(cards()[1].className).toMatch(/border-destructive/)
  })

  it('salt okunur: düğmeler ve alanlar kapalı', () => {
    render(<Harness initial={[rule(), rule({ id: 'r2' })]} disabled />)
    expect(screen.getByRole('button', { name: 'Kural ekle' })).toBeDisabled()
    for (const el of document.querySelectorAll('[data-slot="kw-rule-pattern"]')) expect(el).toBeDisabled()
  })
})

describe('RuleTestResults', () => {
  it('satırlar: etiket / sayım / ihlal seviyesi (MONITOR → izleme seviyesi) / not; genel hüküm', () => {
    render(<RuleTestResults monitorLevel="HIGH" level="MONITOR"
      rules={[rule({ id: 'a', label: 'Fiyat etiketi' }), rule({ id: 'b' })]}
      results={[
        { id: 'a', kind: 'TEXT', pattern_preview: 'Fiyat', count: 3, ok: true, level: 'NONE', note: null },
        { id: 'b', kind: 'REGEX', pattern_preview: 'hata\\d', count: 0, ok: false, level: 'MONITOR', note: 'SCOPE_NOT_FOUND' },
        { id: 'c', kind: 'TEXT', pattern_preview: 'x', count: 1, ok: false, level: 'BOGUS', note: 'NEW_NOTE' },
      ]} />)
    const rows = [...document.querySelectorAll('[data-slot="kw-rule-result"]')]
    expect(rows.map((r) => r.getAttribute('data-ok'))).toEqual(['true', 'false', 'false'])
    expect(rows[0]).toHaveTextContent('Fiyat etiketi')
    expect(rows[0]).toHaveTextContent('3 kez')
    expect(rows[1]).toHaveTextContent('Kural 2')
    expect(rows[1]).toHaveTextContent('/hata\\d/')
    expect(rows[1].getAttribute('data-level')).toBe('HIGH')
    expect(rows[1].querySelector('[data-slot="kw-rule-result-note"]')).toHaveTextContent(/Bölge bulunamadı/)
    expect(rows[2].querySelector('[data-slot="kw-rule-result-note"]')).toBeNull()   // bilinmeyen not ham düşmez
    expect(document.querySelector('[data-slot="kw-rule-results-verdict"]')).toHaveTextContent('Kurallar sağlanmadı — seviye: Yüksek')
  })

  it('tarama bütçesi / sayım tavanı notunda sayı ALT SINIR: "en az N kez" (asla tam sayı gibi değil)', () => {
    render(<RuleTestResults results={[
      { id: 'a', kind: 'TEXT', pattern_preview: 'x', count: 4210, ok: true, level: 'NONE', note: 'SCAN_TRUNCATED' },
      { id: 'b', kind: 'REGEX', pattern_preview: 'y', count: 10000, ok: false, level: 'WARNING', note: 'COUNT_CAPPED' },
      { id: 'c', kind: 'TEXT', pattern_preview: 'z', count: 1, ok: true, level: 'NONE', note: null },
    ]} />)
    const counts = [...document.querySelectorAll('[data-slot="kw-rule-result-count"]')]
    expect(counts.map((c) => c.textContent)).toEqual(['en az 4210 kez', 'en az 10000 kez', '1 kez'])
    expect(counts.map((c) => c.getAttribute('data-at-least'))).toEqual(['true', 'true', null])
    expect(document.querySelectorAll('[data-slot="kw-rule-result-note"]')[0]).toHaveTextContent(/alt sınırdır/)
  })

  it('hepsi sağlandı → yeşil hüküm; boyut satırı; boş / bozuk sonuç → hiçbir şey', () => {
    const { container, rerender } = render(<RuleTestResults results={[{ id: 'a', ok: true, count: 1 }]} sizeOk />)
    expect(document.querySelector('[data-slot="kw-rule-results"]').getAttribute('data-ok')).toBe('true')
    expect(document.querySelector('[data-slot="kw-rule-size"]')).toHaveTextContent('beklenen aralıkta')
    rerender(<RuleTestResults results={[{ id: 'a', ok: true, count: 1 }]} sizeOk={false} />)
    expect(document.querySelector('[data-slot="kw-rule-results"]').getAttribute('data-ok')).toBe('false')
    rerender(<RuleTestResults results={null} />)
    expect(container.innerHTML).toBe('')
    rerender(<RuleTestResults results={'bozuk'} />)
    expect(container.innerHTML).toBe('')
  })

  it('act uyarısı yok — salt sunum', async () => { await act(async () => {}) })
})
