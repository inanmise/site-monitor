import { describe, it, expect } from 'vitest'
import { useState } from 'react'
import { render, screen, fireEvent } from './test-utils.jsx'
import CodeEditor from '../components/ui/CodeEditor.jsx'

// Kod düzenleyici (2026-10-31, k6r E1 + "satıra git"): textarea'nın erişilebilir adı / açıklaması kütüphaneye
// geçirilemiyordu (etiketsiz "düzenlenebilir metin"); dışarıdan "N. satıra git" isteği satırı seçer.

function Harness({ gotoLine, ...rest }) {
  const [v, setV] = useState('a\nbb\nccc\ndddd')
  return <CodeEditor value={v} onChange={setV} gotoLine={gotoLine} {...rest} />
}

describe('CodeEditor — erişilebilir ad ve satıra git', () => {
  it('ariaLabel / ariaDescribedBy textarea\'ya yazılır; verilmezse öznitelik yok', () => {
    const { rerender } = render(<Harness ariaLabel="k6 script" ariaDescribedBy="ipucu" />)
    const ta = screen.getByRole('textbox', { name: 'k6 script' })
    expect(ta).toHaveAttribute('aria-describedby', 'ipucu')
    rerender(<Harness />)
    expect(document.querySelector('textarea').hasAttribute('aria-label')).toBe(false)
    expect(document.querySelector('textarea').hasAttribute('aria-describedby')).toBe(false)
  })

  it('gotoLine: sıra numarası değişince satırı seçer ve odaklar; satır sayısını aşan istek son satıra', () => {
    const { rerender } = render(<Harness gotoLine={{ line: 3, seq: 1 }} />)
    const ta = document.querySelector('textarea')
    expect(document.activeElement).toBe(ta)
    expect(ta.value.slice(ta.selectionStart, ta.selectionEnd)).toBe('ccc')
    // Aynı seq → yeniden seçmez (kullanıcı başka yere tıkladıysa bozulmaz)
    ta.setSelectionRange(0, 0)
    rerender(<Harness gotoLine={{ line: 3, seq: 1 }} />)
    expect(ta.selectionStart).toBe(0)
    rerender(<Harness gotoLine={{ line: 99, seq: 2 }} />)
    expect(ta.value.slice(ta.selectionStart, ta.selectionEnd)).toBe('dddd')
    // Geçersiz istek hiçbir şey yapmaz
    ta.setSelectionRange(0, 0)
    rerender(<Harness gotoLine={{ line: 0, seq: 3 }} />)
    expect(ta.selectionStart).toBe(0)
    fireEvent.blur(ta)
  })
})
