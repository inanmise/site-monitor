import { describe, it, expect } from 'vitest'
import { useState } from 'react'
import { render, screen, fireEvent, within } from './test-utils.jsx'
import KeywordMatchSection from '../components/keyword/KeywordMatchSection.jsx'
import { KW_EMPTY_FORM } from '../components/keyword/keywordFormModel.js'

/**
 * Keyword "Eşleşme ayarları" (kw sözleşmesi §4.3): Ham HTML (bugünkü) / Görünür metin seçim kartları, boşluk + karakter kodu
 * eşitleme anahtarı (yalnız Ham HTML'de — görünür metinde açık + kilitli, formdaki değer değişmez), Türkçe harf notu.
 */
function Harness({ initial = {}, startOpen = true, probe }) {
  const [form, setForm] = useState({ ...KW_EMPTY_FORM, ...initial })
  const [open, setOpen] = useState(startOpen)
  probe.form = form
  return <KeywordMatchSection form={form} setForm={setForm} open={open} onOpenChange={setOpen} />
}
const setup = (props = {}) => { const probe = {}; render(<Harness probe={probe} {...props} />); return probe }
const trigger = () => screen.getByRole('button', { name: /^(Match settings|Eşleşme ayarları)/ })
const scope = () => document.querySelector('[data-slot="kw-match-scope"]')
const normSwitch = () => within(document.querySelector('[data-slot="kw-normalize"]')).getByRole('switch')

describe('KeywordMatchSection', () => {
  it('varsayılan kapalı; açınca Ham HTML seçili ve Türkçe harf notu görünür', () => {
    setup({ startOpen: false })
    expect(trigger()).toHaveAttribute('aria-expanded', 'false')
    expect(scope()).toBeNull()
    fireEvent.click(trigger())
    expect(within(scope()).getByRole('radio', { name: /^(Raw HTML|Ham HTML)/ })).toHaveAttribute('data-state', 'checked')
    expect(document.querySelector('[data-slot="kw-turkish-note"]')).toHaveTextContent(/İ, I, ı/)
    expect(document.querySelector('[data-slot="kw-match-count"]')).toBeNull()
  })

  it('Görünür metin seçilir: açıklama (betik / JavaScript), anahtar açık + kilitli, form değeri DEĞİŞMEZ; rozet 1', () => {
    const probe = setup()
    const visible = within(scope()).getByRole('radio', { name: /^(Visible text|Görünür metin)/ })
    expect(visible).toHaveAccessibleName(/JavaScript/)
    fireEvent.click(visible)
    expect(probe.form.matchScope).toBe('VISIBLE')
    expect(normSwitch()).toBeChecked()
    expect(normSwitch()).toBeDisabled()
    expect(document.querySelector('[data-slot="kw-normalize"]')).toHaveAttribute('data-locked', 'true')
    expect(probe.form.normalizeWs).toBe(false)
    expect(document.querySelector('[data-slot="kw-match-count"]')).toHaveTextContent(/^(1 active|1 ayar açık)$/)
  })

  it('Ham HTML\'de normalleştirme anahtarı açılıp kapanır', () => {
    const probe = setup()
    expect(normSwitch()).not.toBeChecked()
    expect(normSwitch()).not.toBeDisabled()
    fireEvent.click(normSwitch())
    expect(probe.form.normalizeWs).toBe(true)
    fireEvent.click(normSwitch())
    expect(probe.form.normalizeWs).toBe(false)
  })

  it('saklı görünür metin kipiyle açılır', () => {
    setup({ initial: { matchScope: 'VISIBLE' } })
    expect(within(scope()).getByRole('radio', { name: /^(Visible text|Görünür metin)/ })).toHaveAttribute('data-state', 'checked')
  })
})
