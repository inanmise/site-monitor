import { describe, it, expect, vi } from 'vitest'
import { render, screen } from './test-utils.jsx'

/**
 * Markdown editörü ilk düzenlenebilir çizimde yüklenir (2026-10-09). Sözleşme:
 *  - yüklenene dek editörle AYNI yükseklikte, yazma alanı biçimli iskelet (yerleşim zıplamaz);
 *  - komut kurucuları kütüphanenin `commands` nesnesiyle çağrılır, editöre DİZİ olarak geçer (prop'lar aynı);
 *  - `highlight` envanter editörü `common` girişini, diğerleri `nohighlight` girişini kullanır.
 */
const seen = vi.hoisted(() => ({ plain: null, highlight: null }))
vi.mock('@uiw/react-md-editor/nohighlight', () => ({
  default: (props) => { seen.plain = props; return <textarea aria-label="md-plain" readOnly value={props.value ?? ''} /> },
  commands: { bold: { name: 'bold' }, italic: { name: 'italic' }, divider: { name: 'divider' }, codeEdit: { name: 'edit' },
    codePreview: { name: 'preview' }, fullscreen: { name: 'fullscreen' }, group: (cmds, o) => ({ name: o?.name, cmds }) },
}))
vi.mock('@uiw/react-md-editor/common', () => ({
  default: (props) => { seen.highlight = props; return <textarea aria-label="md-highlight" readOnly value={props.value ?? ''} /> },
  commands: { divider: { name: 'divider' }, codeEdit: { name: 'edit' }, codePreview: { name: 'preview' }, fullscreen: { name: 'fullscreen' } },
}))

import MarkdownEditor from '../components/ui/MarkdownEditor.jsx'
import LazyMdEditor from '../components/ui/mdEditor/LazyMdEditor.jsx'

describe('Markdown editörü — tembel yükleme', () => {
  it('ui/MarkdownEditor: önce aynı yükseklikte iskelet, sonra editör; komutlar kütüphane nesnesiyle kurulur', async () => {
    render(<MarkdownEditor value="**merhaba**" onChange={() => {}} height={240} />)
    const skeleton = document.querySelector('[data-slot="md-editor-skeleton"]')
    expect(skeleton).not.toBeNull()
    expect(skeleton.style.height).toBe('240px')
    expect(skeleton).toHaveAttribute('role', 'status')
    expect(await screen.findByLabelText('md-plain')).toHaveValue('**merhaba**')
    expect(document.querySelector('[data-slot="md-editor-skeleton"]')).toBeNull()
    expect(seen.plain.height).toBe(240)
    expect(seen.plain.highlightEnable).toBe(false)
    expect(seen.plain.preview).toBe('edit')
    expect(Array.isArray(seen.plain.commands)).toBe(true)
    expect(seen.plain.commands.map((c) => c?.name)).toEqual(expect.arrayContaining(['title', 'bold', 'italic']))
    expect(seen.plain.extraCommands.map((c) => c?.name)).toEqual(['edit', 'preview', 'divider', 'fullscreen'])
  })

  it('salt okunur MarkdownEditor editörü hiç yüklemez (iskelet yok)', () => {
    render(<MarkdownEditor value="metin" editable={false} onChange={() => {}} />)
    expect(document.querySelector('[data-slot="md-editor-skeleton"]')).toBeNull()
  })

  it('highlight → `common` girişi; dizi komutlar olduğu gibi geçer; varsayılan iskelet yüksekliği 200 px', async () => {
    const extra = [{ name: 'copy-all' }]
    render(<LazyMdEditor highlight value="a" onChange={() => {}} extraCommands={extra} />)
    const skeleton = document.querySelector('[data-slot="md-editor-skeleton"]')
    expect(skeleton.style.height).toBe('200px')   // vurgulu parça bu dosyada ilk kez burada yüklenir
    expect(await screen.findByLabelText('md-highlight')).toBeInTheDocument()
    expect(seen.highlight.extraCommands).toBe(extra)
    expect(seen.highlight.commands).toBeUndefined()
  })
})
