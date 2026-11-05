import { describe, it, expect } from 'vitest'
import { useState } from 'react'
import { render, screen, fireEvent, within, act } from './test-utils.jsx'
import KeywordMailSubjectField from '../components/keyword/KeywordMailSubjectField.jsx'
import { KW_EMPTY_FORM } from '../components/keyword/keywordFormModel.js'
import { useFormErrors } from '../hooks/useFormErrors.js'

/**
 * Keyword alarm e-postasının özel konusu (E3, sözleşme §8.3): boş = standart konu; yer tutucu çipleri arayüz dilinde imlece
 * eklenir; canlı önizleme formdaki ad / kelimeyle; bilinmeyen yer tutucu uyarısı; paylaşımla düzenleyende kilitli.
 */
function Harness({ initial = {}, locked = false, probe }) {
  const [form, setForm] = useState({ ...KW_EMPTY_FORM, name: 'Portal', url: 'https://www.example.com/', keyword: 'Giriş', ...initial })
  const fe = useFormErrors('k')
  probe.form = form
  probe.fe = fe
  return <KeywordMailSubjectField form={form} setForm={setForm} fe={fe} locked={locked} />
}
const setup = (props = {}) => { const probe = {}; render(<Harness probe={probe} {...props} />); return probe }
const box = () => screen.getByRole('textbox', { name: /^(Custom subject \(optional\)|Özel konu \(isteğe bağlı\))$/ })
const chips = () => document.querySelector('[data-slot="kw-subject-placeholders"]')

describe('KeywordMailSubjectField', () => {
  it('boşken önizleme yok, ipucu standart konuyu anlatır; sekiz yer tutucu çipi', () => {
    setup()
    expect(document.querySelector('[data-slot="kw-subject-preview"]')).toBeNull()
    expect(document.querySelector('[data-slot="kw-subject"]')).toHaveTextContent(/standard subject|standart konu/)
    expect(within(chips()).getAllByRole('button')).toHaveLength(8)
  })

  it('çip yer tutucuyu ekler (arayüz dilinde), önizleme formdaki değerlerle dolar', () => {
    const probe = setup()
    fireEvent.change(box(), { target: { value: '[Alarm]' } })
    const nameChip = within(chips()).getByRole('button', { name: /\{(name|ad)\}/ })
    fireEvent.click(nameChip)
    expect(probe.form.mailSubjectTemplate).toMatch(/^\[Alarm\] \{(name|ad)\}$/)
    const preview = document.querySelector('[data-slot="kw-subject-preview-text"]')
    expect(preview).toHaveTextContent('[Alarm] Portal')
    fireEvent.click(within(chips()).getByRole('button', { name: /\{(keyword|kelime)\}/ }))
    expect(document.querySelector('[data-slot="kw-subject-preview-text"]')).toHaveTextContent('[Alarm] Portal Giriş')
  })

  it('TR ve EN yer tutucu aynı değer; bilinmeyen yer tutucu uyarısı (postada olduğu gibi kalır)', () => {
    setup()
    fireEvent.change(box(), { target: { value: '{ad} / {name} {foo}' } })
    expect(document.querySelector('[data-slot="kw-subject-preview-text"]')).toHaveTextContent('Portal / Portal {foo}')
    expect(document.querySelector('[data-slot="kw-subject-unknown"]')).toHaveTextContent('{foo}')
  })

  it('yalnız boşluk = boş şablon: önizleme yok (standart konu)', () => {
    setup()
    fireEvent.change(box(), { target: { value: '   ' } })
    expect(document.querySelector('[data-slot="kw-subject-preview"]')).toBeNull()
  })

  it('kilitli: alan ve çipler kapalı, not görünür', () => {
    setup({ locked: true, initial: { mailSubjectTemplate: '{ad}' } })
    expect(box()).toBeDisabled()
    for (const b of within(chips()).getAllByRole('button')) expect(b).toBeDisabled()
    expect(document.querySelector('[data-slot="kw-subject-locked"]')).toHaveTextContent(/sharing|paylaşım/i)
  })

  it('hata alanın altında; yazınca silinir', () => {
    const probe = setup()
    act(() => { probe.fe.check({ mailSubjectTemplate: 'Konu hatası metni burada' }) })
    expect(document.querySelector('[data-field="mailSubjectTemplate"]')).toHaveTextContent('Konu hatası metni burada')
    fireEvent.change(box(), { target: { value: 'x' } })
    expect(document.querySelector('[data-field="mailSubjectTemplate"]')).not.toHaveTextContent('Konu hatası metni burada')
  })
})
