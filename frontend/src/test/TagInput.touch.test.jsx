import { describe, it, expect } from 'vitest'
import { useState } from 'react'
import { render, screen, fireEvent } from './test-utils'
import TagInput from '../components/ui/TagInput.jsx'

/**
 * TagInput dokunmatik hedef + uzun etiket (inceleme 2026-10-31). jsdom yerleşim yapmaz; burada sınıf SÖZLEŞMESİ
 * sınanır, ölçüm e2e'de (scripted-form-setup.spec.js — dokunmatikte kaldır düğmeleri ≥ 40 px ve üst üste binmez).
 *  - Kaldır düğmesinin dokunma alanı rozetin İÇİNDE gerçek boyuttur (pointer-coarse:size-10); rozet dışına taşan görünmez
 *    `::after` alanı YOK — sarılan satırlarda komşu rozetin üstüne biniyordu.
 *  - Uzun etiket sarılır (whitespace-normal, köşe rounded-md), rozet kutudan taşmaz (max-w-full).
 *  - Önizleme chip'i gerçek chip'le aynı ölçü sınıflarını taşır (işleme alınca yer değişmez).
 */
function Harness({ initial }) {
  const [v, setV] = useState(initial)
  return <TagInput label="Etiketler" value={v} onChange={setV} />
}

const SIZE_TOKENS = ['h-auto', 'max-w-full', 'rounded-md', 'whitespace-normal', 'pointer-coarse:min-h-10', 'pointer-coarse:py-0', 'pointer-coarse:pr-0']

describe('TagInput — dokunmatik hedef ve uzun etiket', () => {
  it('kaldır düğmesi rozet içinde 40 px; ::after alanı yok; uzun etiket sarılır', () => {
    const long = 'odeme-servisi-uretim-ortami-bolge-2-cok-uzun-etiket-adi-kesilmemeli'
    render(<Harness initial={`prod,${long}`} />)
    const btns = screen.getAllByRole('button', { name: /prod|odeme-servisi/ })
    expect(btns).toHaveLength(2)
    for (const b of btns) {
      expect(b.className).toMatch(/pointer-coarse:size-10/)
      expect(b.className).not.toMatch(/after:/)
    }
    const chips = document.querySelectorAll('[data-tag-chip]')
    expect(chips).toHaveLength(2)
    for (const c of chips) for (const tok of SIZE_TOKENS) expect(c.className.split(/\s+/)).toContain(tok)
    expect(screen.getByText(long).className).toMatch(/overflow-wrap:anywhere/)
  })

  it('önizleme chip\'i gerçek chip\'le aynı ölçü sınıflarını taşır', () => {
    render(<Harness initial="prod" />)
    fireEvent.change(screen.getByRole('textbox'), { target: { value: 'yeni' } })
    const pending = document.querySelector('[data-pending]')
    expect(pending).not.toBeNull()
    for (const tok of SIZE_TOKENS) expect(pending.className.split(/\s+/)).toContain(tok)
    expect(pending.querySelector('button').className).toMatch(/pointer-coarse:size-10/)
  })
})
