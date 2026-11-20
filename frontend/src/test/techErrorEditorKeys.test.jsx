import { describe, it, expect, afterEach } from 'vitest'
import { render, screen } from './test-utils.jsx'
import { isSaveShortcut, sectionToggleStep } from '../components/techerror/editorKeys.js'
import { TE_EDITOR_CONTEXT, activeShortcutContexts, registerShortcutContext } from '../utils/shortcutContexts.js'
import ShortcutsDialog from '../components/ShortcutsDialog.jsx'

// Kanal raporu düzenleyicisi kısayolları (A4, 2026-11-20): Alt+↓ / Alt+↑ bölüm başlıkları arasında gezinir (hiçbir bölümü
// açmaz — saf yardımcı yalnız hedefi döndürür); `?` listesi düzenleyiciye özgü grubu YALNIZ düzenleyici kuruluyken gösterir.
function build(extra = '') {
  document.body.innerHTML = `
    <div id="root">
      <h1 id="title">Başlık</h1>
      <div data-section="a"><h3><button data-slot="te-section-toggle" id="ta">A</button></h3><div><input id="ia" /></div></div>
      <div data-section="b"><h3><button data-slot="te-section-toggle" id="tb">B</button></h3><div><button id="bb">x</button></div></div>
      <div data-section="c" hidden><h3><button data-slot="te-section-toggle" id="tc">C</button></h3></div>
      <div data-section="d"><h3><button data-slot="te-section-toggle" id="td" disabled>D</button></h3></div>
      <div data-section="e"><h3><button data-slot="te-section-toggle" id="te">E</button></h3></div>
      ${extra}
      <div id="bar"><button id="save">Kaydet</button></div>
    </div>`
  return document.getElementById('root')
}
const $ = (id) => document.getElementById(id)

describe('sectionToggleStep — bölüm gezintisi', () => {
  afterEach(() => { document.body.innerHTML = '' })

  it('kart içindeki odaktan komşu kartın başlığına; gizli / devre dışı başlıklar atlanır; uçta null', () => {
    const root = build()
    expect(sectionToggleStep(root, $('ia'), 1)).toBe($('tb'))
    expect(sectionToggleStep(root, $('bb'), 1)).toBe($('te'))       // C gizli, D devre dışı
    expect(sectionToggleStep(root, $('te'), -1)).toBe($('tb'))
    expect(sectionToggleStep(root, $('ta'), -1)).toBeNull()
    expect(sectionToggleStep(root, $('te'), 1)).toBeNull()
  })

  it('kart dışı odak: belge sırası; kök dışı / gövde odağı uçlara gider', () => {
    const root = build()
    expect(sectionToggleStep(root, $('title'), 1)).toBe($('ta'))
    expect(sectionToggleStep(root, $('save'), -1)).toBe($('te'))
    expect(sectionToggleStep(root, $('save'), 1)).toBeNull()
    expect(sectionToggleStep(root, $('title'), -1)).toBeNull()
    expect(sectionToggleStep(root, document.body, 1)).toBe($('ta'))
    expect(sectionToggleStep(root, document.body, -1)).toBe($('te'))
    expect(sectionToggleStep(root, null, 1)).toBe($('ta'))
  })

  it('geçersiz yön / kök ya da başlık yoksa null', () => {
    const root = build()
    expect(sectionToggleStep(root, $('ia'), 0)).toBeNull()
    expect(sectionToggleStep(root, $('ia'), 2)).toBeNull()
    expect(sectionToggleStep(null, $('ia'), 1)).toBeNull()
    document.body.innerHTML = '<div id="r"><button id="x">x</button></div>'
    expect(sectionToggleStep($('r'), $('x'), 1)).toBeNull()
  })
})

describe('kısayol bağlamları', () => {
  it('iç içe kayıt sayaçla; geri alma bir kez çalışır', () => {
    expect(activeShortcutContexts().has('t-ctx')).toBe(false)
    const a = registerShortcutContext('t-ctx')
    const b = registerShortcutContext('t-ctx')
    expect(activeShortcutContexts().has('t-ctx')).toBe(true)
    a()
    a()                                          // ikinci çağrı sayacı ikinci kez düşürmez
    expect(activeShortcutContexts().has('t-ctx')).toBe(true)
    b()
    expect(activeShortcutContexts().has('t-ctx')).toBe(false)
    expect(typeof registerShortcutContext('')).toBe('function')
    expect(activeShortcutContexts().has('')).toBe(false)
  })

  it('`?` listesi düzenleyici grubunu yalnız bağlam etkinken gösterir', () => {
    const first = render(<ShortcutsDialog open onClose={() => {}} tabs={[]} />)
    expect(document.querySelector('[data-group="te-editor"]')).toBeNull()
    first.unmount()

    const off = registerShortcutContext(TE_EDITOR_CONTEXT)
    try {
      render(<ShortcutsDialog open onClose={() => {}} tabs={[]} />)
      const grp = document.querySelector('[data-group="te-editor"]')
      expect(grp).not.toBeNull()
      expect([...grp.querySelectorAll('[data-slot="shortcut-row"]')].map((r) => r.getAttribute('data-shortcut')))
        .toEqual(['te-save', 'te-section-next', 'te-section-prev'])
      expect(screen.getByRole('heading', { name: /rapor düzenleyicisi|report editor/i })).toBeInTheDocument()
    } finally {
      off()
    }
  })
})

describe('isSaveShortcut — Ctrl/⌘+S (inceleme 2026-11-20)', () => {
  it('s / S (Ctrl ya da ⌘); Alt / Shift ile değil; Latin olmayan düzende fiziksel S; Latin harf veren düzende başka harf değil', () => {
    expect(isSaveShortcut({ key: 's', ctrlKey: true })).toBe(true)
    expect(isSaveShortcut({ key: 'S', metaKey: true })).toBe(true)
    expect(isSaveShortcut({ key: 's', ctrlKey: true, shiftKey: true })).toBe(false)
    expect(isSaveShortcut({ key: 's', ctrlKey: true, altKey: true })).toBe(false)
    expect(isSaveShortcut({ key: 's' })).toBe(false)
    expect(isSaveShortcut({ key: 'ы', code: 'KeyS', ctrlKey: true })).toBe(true)     // Rusça
    expect(isSaveShortcut({ key: 'σ', code: 'KeyS', metaKey: true })).toBe(true)     // Yunanca
    expect(isSaveShortcut({ key: 'o', code: 'KeyS', ctrlKey: true })).toBe(false)    // Dvorak: Ctrl+O
    expect(isSaveShortcut({ key: 's', code: 'KeyO', ctrlKey: true })).toBe(true)     // Dvorak: Ctrl+S
    expect(isSaveShortcut(null)).toBe(false)
  })
})
