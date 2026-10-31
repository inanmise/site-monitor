import { describe, it, expect, vi, beforeEach } from 'vitest'
import { useState } from 'react'
import { render, screen, waitFor, fireEvent, within } from './test-utils.jsx'
import { LANG_STORAGE_KEY } from '../i18n/dateLocale.js'
import { newRule } from '../components/dbquery/ruleModel.js'

// Kural oluşturucu: ekle (≤ 10, kimlik r-…), sil (onaylı), taşı, tür / işleç değişimi, sayı biçimi (TR "1.250,5"),
// geçersiz sayı alanın altında ve METİN olarak bildirilir (sessizce yutulmaz), insan dilinde özet, sunucu alan hataları
// `rules[N].<alt>` ilgili alanın altında, ANY / ALL, metin kuralında seviye, kolon önerileri, salt okunur.
const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
vi.mock('../api/client', () => ({ api: withApiFallback({}) }))
import RuleBuilder from '../components/dbquery/RuleBuilder.jsx'

function Harness({ initial = [], logic: l0 = 'ANY', spy, ...rest }) {
  const [rules, setRules] = useState(initial)
  const [logic, setLogic] = useState(l0)
  return (
    <RuleBuilder rules={rules} logic={logic} {...rest}
      onChange={(r) => { setRules(r); spy?.(r) }} onLogicChange={(x) => { setLogic(x); spy?.('logic:' + x) }} />
  )
}
const cards = () => [...document.querySelectorAll('[data-slot="dbq-rule-card"]')]
const summary = (i = 0) => cards()[i].querySelector('[data-slot="dbq-rule-summary"]').textContent

beforeEach(() => {
  try { localStorage.clear(); localStorage.setItem(LANG_STORAGE_KEY, 'tr') } catch { /* yok */ }
})

describe('RuleBuilder', () => {
  it('boş durum + kural ekle: yeni kural r- kimlikli, satır sayısı, özet "eşik girilmedi"', () => {
    const spy = vi.fn()
    render(<Harness spy={spy} />)
    expect(document.querySelector('[data-slot="dbq-rule-empty"]')).not.toBeNull()
    fireEvent.click(screen.getByRole('button', { name: /Kural ekle/ }))
    expect(cards()).toHaveLength(1)
    expect(spy.mock.calls[0][0][0]).toMatchObject({ kind: 'ROW_COUNT', operator: 'GT', warning: null, critical: null })
    expect(spy.mock.calls[0][0][0].id).toMatch(/^r-[0-9a-z]+$/)
    expect(summary()).toContain('eşik girilmedi')
    expect(document.querySelector('[data-slot="dbq-rule-missing"]')).not.toBeNull()
  })

  it('TR sayı biçimi: "1.250,5" → 1250.5; özet anında güncellenir; yazılan metin yeniden biçimlenmez', () => {
    const spy = vi.fn()
    render(<Harness spy={spy} initial={[newRule('ROW_COUNT', 'r-a')]} />)
    const warn = within(cards()[0]).getByLabelText(/Uyarı eşiği/)
    fireEvent.change(warn, { target: { value: '1.250,5' } })
    expect(spy.mock.calls.at(-1)[0][0].warning).toBe(1250.5)
    expect(warn).toHaveValue('1.250,5')
    expect(summary()).toContain("1.250,5'ten büyükse UYARI")
    const crit = within(cards()[0]).getByLabelText(/Kritik eşik/)
    fireEvent.change(crit, { target: { value: '100' } })
    expect(summary()).toBe("Özet: Satır sayısı 1.250,5'ten büyükse UYARI, 100'den büyükse KRİTİK.")
    // kritik uyarıdan gevşek → ipucu
    expect(document.querySelector('[data-slot="dbq-rule-strictness"]')).not.toBeNull()
  })

  it('geçersiz sayı: alanın altında hata + kurala METİN olarak geçer (sunucu da reddeder)', () => {
    const spy = vi.fn()
    render(<Harness spy={spy} initial={[newRule('ROW_COUNT', 'r-a')]} />)
    const warn = within(cards()[0]).getByLabelText(/Uyarı eşiği/)
    fireEvent.change(warn, { target: { value: '12abc' } })
    expect(spy.mock.calls.at(-1)[0][0].warning).toBe('12abc')
    expect(within(cards()[0]).getByText(/Sayı girin/)).toBeInTheDocument()
    expect(warn).toHaveAttribute('aria-invalid', 'true')
    fireEvent.change(warn, { target: { value: '' } })
    expect(spy.mock.calls.at(-1)[0][0].warning).toBeNull()
  })

  it('EN biçimi "1,250.5"', () => {
    try { localStorage.setItem(LANG_STORAGE_KEY, 'en') } catch { /* yok */ }
    const spy = vi.fn()
    render(<Harness spy={spy} initial={[newRule('ROW_COUNT', 'r-a')]} />)
    return waitFor(() => screen.getByLabelText(/Warning threshold/)).then((warn) => {
      fireEvent.change(warn, { target: { value: '1,250.5' } })
      expect(spy.mock.calls.at(-1)[0][0].warning).toBe(1250.5)
      expect(summary()).toContain('WARNING if the row count is greater than 1,250.5.')
    })
  })

  it('sunucu alan hataları ilgili kartın alanının altında; "rules" üstte', () => {
    render(<Harness initial={[newRule('ROW_COUNT', 'r-a'), newRule('COLUMN_VALUE', 'r-b')]}
      fieldErrors={{ rules: 'Kurallar okunamadı', 'rules[1].column': 'Kolon sorguda yok: TUTAR' }} />)
    expect(screen.getByText('Kurallar okunamadı')).toBeInTheDocument()
    const second = cards()[1]
    expect(within(second).getByText('Kolon sorguda yok: TUTAR')).toBeInTheDocument()
    expect(second.querySelector('[data-field="rules[1].column"]')).not.toBeNull()
    expect(within(cards()[0]).queryByText('Kolon sorguda yok: TUTAR')).toBeNull()
  })

  it('sunucunun her kural kodu bir yerde görünür: alan altında ya da kart başında (rules[N], .id, çizilmeyen alan)', () => {
    const text = { ...newRule('TEXT_MATCH', 'r-c'), column: 'DURUM', critical: null, warning: null }
    const empty = { ...newRule('TEXT_MATCH', 'r-d'), column: 'DURUM', operator: 'EMPTY', critical: '' }
    render(<Harness initial={[newRule('ROW_COUNT', 'r-a'), { ...newRule('ROW_COUNT', 'r b'), warning: 1 }, text, empty]}
      fieldErrors={{
        'rules[0]': 'Kural 1 okunamadı · Kural bir nesne olmalı · Kuralı silip yeniden ekleyin.',
        'rules[1].id': 'Kural 2 kimliği geçersiz · Kuralı silip yeniden ekleyin.',
        'rules[1].label': { code: 'DBQUERY_RULE_LABEL', message: 'Kural 2 adı çok uzun (en çok 120 karakter)' },
        // metin kuralı: seviye kritikte ama sunucu "eşik gerekli"yi warning'e yazar
        'rules[2].warning': 'Kural 3 için eşik gerekli · Bir eşik değeri girin.',
        // değersiz işleçte eşik alanı çizilmez
        'rules[3].critical': 'Kural 4 eşiği okunamadı',
      }} />)
    const [c0, c1, c2, c3] = cards()
    expect(within(c0).getByText(/Kural 1 okunamadı/)).toBeInTheDocument()
    expect(c0.querySelector('[data-slot="dbq-rule-server-errors"] [data-field="rules[0]"]')).not.toBeNull()
    expect(within(c1).getByText(/Kural 2 kimliği geçersiz/)).toBeInTheDocument()
    expect(c1.querySelector('[data-slot="dbq-rule-server-errors"] [data-field="rules[1].id"]')).not.toBeNull()
    // { code, message } biçimi de alanın altında ileti olarak
    expect(within(c1.querySelector('[data-field="rules[1].label"]')).getByText('Kural 2 adı çok uzun (en çok 120 karakter)')).toBeInTheDocument()
    expect(c1.querySelector('[data-slot="dbq-rule-server-errors"] [data-field="rules[1].label"]')).toBeNull()
    expect(within(c2).getByText(/Kural 3 için eşik gerekli/)).toBeInTheDocument()
    expect(c2.querySelector('[data-slot="dbq-rule-missing"]')).toBeNull()
    expect(within(c3).getByText('Kural 4 eşiği okunamadı')).toBeInTheDocument()
    expect(c3.closest('[data-slot="dbq-rule-card"]').className).toMatch(/border-destructive/)
  })

  it('sunucu sınırları istemcide anında: ad > 120, kolon > 128, metin > 500', () => {
    render(<Harness initial={[
      { ...newRule('ROW_COUNT', 'r-a'), label: 'a'.repeat(121), warning: 1 },
      { ...newRule('COLUMN_VALUE', 'r-b'), column: 'C'.repeat(129), warning: 1 },
      { ...newRule('TEXT_MATCH', 'r-c'), column: 'D', critical: 'h'.repeat(501) },
    ]} />)
    const [c0, c1, c2] = cards()
    expect(within(c0).getByText('Kural adı en çok 120 karakter olabilir — kısaltın.')).toBeInTheDocument()
    expect(within(c1).getByText(/Kolon adı en çok 128 karakter/)).toBeInTheDocument()
    expect(within(c2).getByText(/Aranan metin en çok 500 karakter/)).toBeInTheDocument()
  })

  it('negatif eşik hata değil, ipucu (satır sayısı)', () => {
    render(<Harness initial={[{ ...newRule('ROW_COUNT', 'r-a'), warning: -1 }]} />)
    expect(cards()[0].querySelector('[data-slot="dbq-rule-strictness"]').textContent).toContain('Satır sayısı hiç negatif olmaz')
    expect(screen.queryByText('Negatif olamaz.')).toBeNull()
  })

  it('sil onaylıdır; vazgeçilirse kural kalır', async () => {
    render(<Harness initial={[{ ...newRule('ROW_COUNT', 'r-a'), label: 'Bekleyen sipariş' }]} />)
    fireEvent.click(screen.getByRole('button', { name: 'Sil: Bekleyen sipariş' }))
    const dlg = await screen.findByRole('dialog')
    expect(dlg.textContent).toContain('Bekleyen sipariş')
    fireEvent.click(within(dlg).getByRole('button', { name: /Vazgeç|İptal/ }))
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
    expect(cards()).toHaveLength(1)
    fireEvent.click(screen.getByRole('button', { name: 'Sil: Bekleyen sipariş' }))
    fireEvent.click(within(await screen.findByRole('dialog')).getByRole('button', { name: 'Kuralı sil' }))
    await waitFor(() => expect(cards()).toHaveLength(0))
  })

  it('yukarı / aşağı taşı; sınırda düğme devre dışı', () => {
    render(<Harness initial={[{ ...newRule('ROW_COUNT', 'a'), label: 'Bir' }, { ...newRule('ROW_COUNT', 'b'), label: 'İki' }]} />)
    expect(screen.getByRole('button', { name: 'Yukarı taşı: Bir' })).toBeDisabled()
    expect(screen.getByRole('button', { name: 'Aşağı taşı: İki' })).toBeDisabled()
    fireEvent.click(screen.getByRole('button', { name: 'Aşağı taşı: Bir' }))
    expect(cards().map((c) => c.querySelector('span').textContent)).toEqual(['İki', 'Bir'])
  })

  it('en çok 10 kural: ekle düğmesi devre dışı', () => {
    const many = Array.from({ length: 10 }, (_, i) => newRule('ROW_COUNT', `r-${i}`))
    render(<Harness initial={many} />)
    expect(screen.getByRole('button', { name: /Kural ekle/ })).toBeDisabled()
    expect(screen.getByText('En çok 10 kural')).toBeInTheDocument()
  })

  it('tür değişimi kolon alanını açar; kolon önerileri seçici olarak; metin kuralında tek seviye', () => {
    const spy = vi.fn()
    render(<Harness spy={spy} initial={[newRule('ROW_COUNT', 'r-a')]} columns={[{ name: 'DURUM', type: 'VARCHAR' }]} />)
    const kind = within(cards()[0]).getByLabelText('Kural türü')
    fireEvent.change(kind, { target: { value: 'TEXT_MATCH' } })
    expect(cards()[0].getAttribute('data-kind')).toBe('TEXT_MATCH')
    expect(within(cards()[0]).getByRole('combobox', { name: 'Kolon' })).toBeInTheDocument()
    // seviye anahtarı: Uyarı'ya geç, metni yaz → warning alanına gider
    fireEvent.mouseDown(within(cards()[0]).getByRole('button', { name: /^Uyarı$/ }))
    fireEvent.click(within(cards()[0]).getByRole('button', { name: /^Uyarı$/ }))
    fireEvent.change(within(cards()[0]).getByLabelText(/Aranan metin/), { target: { value: 'HATA' } })
    const last = spy.mock.calls.filter((c) => Array.isArray(c[0])).at(-1)[0][0]
    expect(last).toMatchObject({ kind: 'TEXT_MATCH', operator: 'CONTAINS', warning: 'HATA', critical: null })
  })

  it('aralık işleci iki kutu; alt > üst hata', () => {
    render(<Harness initial={[{ ...newRule('ROW_COUNT', 'r-a'), operator: 'BETWEEN' }]} />)
    const lo = screen.getByLabelText('Uyarı eşiği — alt sınır')
    const hi = screen.getByLabelText('Uyarı eşiği — üst sınır')
    fireEvent.change(lo, { target: { value: '9' } })
    fireEvent.change(hi, { target: { value: '5' } })
    expect(within(cards()[0]).getByText(/Alt sınır üst sınırdan büyük olamaz/)).toBeInTheDocument()
  })

  it('ANY / ALL birleşimi', () => {
    const spy = vi.fn()
    render(<Harness spy={spy} />)
    expect(screen.getByText(/en yüksek ihlalin/)).toBeInTheDocument()
    const all = screen.getByRole('button', { name: 'Hepsi (ALL)' })
    fireEvent.mouseDown(all); fireEvent.click(all)
    expect(spy).toHaveBeenCalledWith('logic:ALL')
    expect(screen.getByText(/en düşük ihlalin/)).toBeInTheDocument()
  })

  it('salt okunur: ekle / sil / taşı devre dışı', () => {
    render(<Harness disabled initial={[{ ...newRule('ROW_COUNT', 'a'), label: 'Bir' }]} />)
    expect(screen.getByRole('button', { name: /Kural ekle/ })).toBeDisabled()
    expect(screen.getByRole('button', { name: 'Sil: Bir' })).toBeDisabled()
    expect(within(cards()[0]).getByLabelText(/Uyarı eşiği/)).toBeDisabled()
  })
})
