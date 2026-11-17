import { describe, it, expect, vi, beforeEach } from 'vitest'
import { useState } from 'react'
import { render, screen, fireEvent, waitFor, within } from './test-utils.jsx'

// Haftalık rapor Markdown alanı (weekly/WeeklyMdField): Excel yapıştırma → tablo, girinti komutları, açıklamalı görsel
// yükleme (imleç konumuna ekleme), salt okunur görünüm. Ağır editör yerine yakalayan bir sahte: verilen komut kurucusu
// ve textarea davranışı test edilir (kütüphanenin kendi çizimi değil).
const captured = { props: null }
vi.mock('../components/ui/mdEditor/LazyMdEditor.jsx', () => ({
  default: (props) => {
    captured.props = props
    return <textarea aria-label={props.textareaProps?.['aria-label'] || 'md'} value={props.value} onChange={(e) => props.onChange(e.target.value)} />
  },
}))
vi.mock('../api/client', () => ({ api: { weeklyReports: { uploadImage: vi.fn() } } }))
vi.mock('../utils/imageDownscale', () => ({
  downscaleImage: vi.fn(async (f) => new File(['x'], f.name, { type: f.type })),   // 1 bayt: "optimize edildi"
}))

import { api } from '../api/client'
import { MdField, MarkdownView, fmtFileSize } from '../components/weekly/WeeklyMdField.jsx'

function Harness({ initial = '', editable = true }) {
  const [v, setV] = useState(initial)
  return (
    <>
      <MdField value={v} onChange={setV} editable={editable} reportId={42} label="Notlar" />
      <output data-testid="v">{v}</output>
    </>
  )
}
const val = () => screen.getByTestId('v').textContent
const area = () => screen.getByRole('textbox', { name: 'Notlar' })

/** Komut kurucusunu sahte mdCommands ile çalıştır → ad → komut eşlemi. */
function commands() {
  const named = (name) => ({ name, keyCommand: name })
  const md = new Proxy({ group: (list, opts) => ({ ...opts, children: list }), divider: { keyCommand: 'divider' } }, {
    get: (target, key) => (key in target ? target[key] : named(String(key))),
  })
  const list = captured.props.commands(md)
  return Object.fromEntries(list.filter((c) => c.name).map((c) => [c.name, c]))
}
/** Seçimi tutan sahte editorApi. */
function editorApi() {
  const calls = []
  return {
    calls,
    setSelectionRange: (r) => calls.push(['sel', r.start, r.end]),
    replaceSelection: (s) => calls.push(['rep', s]),
  }
}

beforeEach(() => { vi.clearAllMocks(); captured.props = null })

describe('WeeklyMdField — MdField', () => {
  it('karakter sayacı, araç çubuğu komutlarının adları (i18n) ve ek komutlar', () => {
    render(<Harness initial="abc" />)
    expect(document.querySelector('[data-slot="wr-char-count"]').textContent).toMatch(/3/)
    const c = commands()
    expect(Object.keys(c)).toEqual(expect.arrayContaining(['title', 'indent', 'outdent', 'image-upload', 'unorderedListCommand', 'table']))
    expect(c.indent.buttonProps['aria-label']).toMatch(/Girinti|Indent/)
    const extra = captured.props.extraCommands(new Proxy({}, { get: (_, k) => ({ keyCommand: String(k) }) }))
    expect(extra.map((x) => x.keyCommand)).toEqual(['codeEdit', 'codePreview', 'divider', 'fullscreen'])
  })

  it('girinti / girinti azaltma seçili satırların TAMAMINA uygulanır, blok seçili kalır', () => {
    render(<Harness initial="a" />)
    const c = commands()
    const text = 'bir\niki\nüç'
    const ed = editorApi()
    c.indent.execute({ text, selection: { start: 5, end: 6 } }, ed)    // "iki" satırının ortası
    expect(ed.calls).toEqual([['sel', 4, 7], ['rep', '    iki'], ['sel', 4, 11]])
    const ed2 = editorApi()
    c.outdent.execute({ text: '      x\n  y', selection: { start: 0, end: 11 } }, ed2)
    expect(ed2.calls[1]).toEqual(['rep', '  x\ny'])                     // en çok 4 boşluk silinir
    const ed3 = editorApi()
    c.indent.execute({ text: 'son', selection: { start: 3, end: 3 } }, ed3)   // satır sonunda, '\n' yok
    expect(ed3.calls[1]).toEqual(['rep', '    son'])
  })

  it('Excel (TSV) yapıştırma imleç konumuna Markdown tablo ekler; düz metin yapıştırma karışmaz', async () => {
    render(<Harness initial="ÖNCE SONRA" />)
    const ta = area()
    ta.setSelectionRange(5, 5)
    const data = { getData: (type) => (type === 'text/plain' ? 'A\tB\n1\t2' : '') }
    fireEvent.paste(ta, { clipboardData: data })
    expect(val()).toMatch(/^ÖNCE \|/)
    expect(val()).toMatch(/\| A \| B \|/)
    expect(val()).toMatch(/SONRA$/)
    expect(await screen.findByText(/Tablo eklendi|Table inserted/)).toBeInTheDocument()   // başarı bildirimi
    const before = val()
    fireEvent.paste(ta, { clipboardData: { getData: (type) => (type === 'text/plain' ? 'düz metin' : '') } })
    expect(val()).toBe(before)                                          // tablo değil → editörün kendi yapıştırması
  })

  it('görsel: seçilince küçültülür, açıklamayla yüklenir, imleç konumuna eklenir; optimize bildirimi', async () => {
    api.weeklyReports.uploadImage.mockResolvedValue({ success: true, data: { id: 9 } })
    const { container } = render(<Harness initial="başlangıç son" />)
    const ta = area()
    ta.setSelectionRange(10, 10)
    fireEvent.keyUp(ta)                                                  // imleç kaydı
    commands()['image-upload'].execute()                                 // gizli dosya seçicisini açar (tıklama)
    const file = new File(['x'.repeat(5000)], 'grafik.png', { type: 'image/png' })
    fireEvent.change(container.querySelector('input[type="file"]'), { target: { files: [file] } })
    const dlg = await screen.findByRole('dialog')
    await waitFor(() => expect(within(dlg).getByText(/grafik\.png/)).toBeInTheDocument())
    await waitFor(() => expect(within(dlg).getByRole('button', { name: /^(Ekle|Insert)$/ })).not.toBeDisabled())
    fireEvent.change(within(dlg).getByRole('textbox'), { target: { value: '  Aylık grafik  ' } })
    fireEvent.click(within(dlg).getByRole('button', { name: /^(Ekle|Insert)$/ }))
    await waitFor(() => expect(api.weeklyReports.uploadImage).toHaveBeenCalledWith(42, expect.any(File), 'Aylık grafik'))
    await waitFor(() => expect(val()).toContain('![Aylık grafik](/api/weekly-reports/images/9)'))
    expect(val().startsWith('başlangıç ')).toBe(true)
    expect(val().indexOf('**Aylık grafik**')).toBeGreaterThan(9)
    expect(val().endsWith('son')).toBe(true)
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
  })

  it('görsel yükleme hatası: sunucu metni bildirilir, pencere açık kalır; ağ hatası genel metin', async () => {
    api.weeklyReports.uploadImage.mockResolvedValueOnce({ success: false, error: 'Görsel çok büyük (en çok 5 MB).' })
    const { container } = render(<Harness initial="" />)
    fireEvent.change(container.querySelector('input[type="file"]'), { target: { files: [new File(['y'], 'a.png', { type: 'image/png' })] } })
    const dlg = await screen.findByRole('dialog')
    await waitFor(() => expect(within(dlg).getByRole('button', { name: /^(Ekle|Insert)$/ })).not.toBeDisabled())
    fireEvent.click(within(dlg).getByRole('button', { name: /^(Ekle|Insert)$/ }))
    expect(await screen.findByText('Görsel çok büyük (en çok 5 MB).')).toBeInTheDocument()
    expect(val()).toBe('')
    api.weeklyReports.uploadImage.mockRejectedValueOnce(new Error('net'))
    fireEvent.click(within(dlg).getByRole('button', { name: /^(Ekle|Insert)$/ }))
    await waitFor(() => expect(api.weeklyReports.uploadImage).toHaveBeenCalledTimes(2))
    expect(val()).toBe('')
    fireEvent.click(within(dlg).getByRole('button', { name: /^(Vazgeç|Cancel)$/ }))
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
  })

  it('düzenlenemez: Markdown görünümü (editör yok)', () => {
    render(<Harness initial="**kalın**" editable={false} />)
    expect(screen.queryByRole('textbox', { name: 'Notlar' })).toBeNull()
    expect(screen.getByText('kalın').tagName).toBe('STRONG')
  })
})

describe('WeeklyMdField — yardımcılar', () => {
  it('MarkdownView boş değerde "empty" metni; fmtFileSize KB / MB', () => {
    render(<MarkdownView value="" empty="Not yok" />)
    expect(screen.getByText('Not yok')).toBeInTheDocument()
    expect(fmtFileSize(2048)).toMatch(/2(\.0)? ?KB/)
    expect(fmtFileSize(3 * 1024 * 1024)).toMatch(/3(\.0)? ?MB/)
  })
})
