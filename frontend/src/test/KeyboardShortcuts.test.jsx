import { describe, it, expect, vi, afterEach } from 'vitest'
import { render, screen, fireEvent, act, within, waitFor } from './test-utils.jsx'
import KeyboardShortcuts from '../components/KeyboardShortcuts.jsx'
import {
  GO_SHORTCUTS, SEQUENCE_MS, SHORTCUTS_EVENT, PAGE_SEARCH_ATTR,
  isTypingTarget, hasOpenOverlay, shortcutEligible, letterOf, goTarget, findPageSearch, focusPageSearch, isMacPlatform,
} from '../utils/keyboardShortcuts.js'

// Genel klavye kısayolları (2026-10-02, öneri 24): `?` liste, `/` sayfa araması, `g`+harf sekme. Yalnız EKLEME —
// yazı alanında, açık pencere/menüde ve değiştirici tuşla ÇALIŞMAZ; Esc/Tab'a dokunmaz.

const TABS = [
  { id: 'dashboard', label: 'Genel Bakış' },
  { id: 'monitoring', label: 'İzleme Panosu' },
  { id: 'alerthistory', label: 'Alarm Geçmişi' },
  { id: 'status', label: 'Durum Sayfası' },
  { id: 'http', label: 'HTTP / Website' },
  { id: 'domains', label: 'Sertifika Envanteri' },
  { id: 'all', label: 'Tüm Sertifikalar' },
  { id: 'help', label: 'Yardım' },
]

const key = (k, opts = {}, target = window) => {
  const ev = new KeyboardEvent('keydown', { key: k, bubbles: true, cancelable: true, ...opts })
  act(() => { target.dispatchEvent(ev) })
  return ev
}

describe('keyboardShortcuts — saf model', () => {
  // Bu bölüm RTL render'ı kullanmaz: elle kurulan DOM her testten sonra temizlenir.
  afterEach(() => { document.body.innerHTML = '' })

  it('yazı alanı ve tuşları kendisi kullanan bileşenler "yazıyor" sayılır; düğme/gövde sayılmaz', () => {
    document.body.innerHTML = `
      <input id="i" /><textarea id="ta"></textarea><select id="s"><option>a</option></select>
      <div contenteditable="true"><span id="ce">x</span></div><div contenteditable="false"><span id="nce">x</span></div>
      <button id="b">b</button><button id="cb" role="combobox">c</button>
      <div role="menu"><div id="mi" role="menuitem">m</div></div><div role="slider" id="sl"></div>`
    const $ = (id) => document.getElementById(id)
    for (const id of ['i', 'ta', 's', 'ce', 'cb', 'mi', 'sl']) expect(isTypingTarget($(id)), id).toBe(true)
    for (const id of ['b', 'nce']) expect(isTypingTarget($(id)), id).toBe(false)
    expect(isTypingTarget(document.body)).toBe(false)
    expect(isTypingTarget(null)).toBe(false)
  })

  it('açık pencere / uyarı penceresi / menü varsa kısayol yok', () => {
    expect(hasOpenOverlay(document)).toBe(false)
    for (const role of ['dialog', 'alertdialog', 'menu']) {
      document.body.innerHTML = `<div role="${role}"></div>`
      expect(hasOpenOverlay(document), role).toBe(true)
    }
  })

  it('shortcutEligible: Ctrl/⌘/Alt, işlenmiş olay ve IME bileşimi elenir', () => {
    const ev = (o = {}) => ({ key: '?', target: document.body, ...o })
    expect(shortcutEligible(ev(), document)).toBe(true)
    expect(shortcutEligible(ev({ ctrlKey: true }), document)).toBe(false)
    expect(shortcutEligible(ev({ metaKey: true }), document)).toBe(false)
    expect(shortcutEligible(ev({ altKey: true }), document)).toBe(false)
    expect(shortcutEligible(ev({ defaultPrevented: true }), document)).toBe(false)
    expect(shortcutEligible(ev({ isComposing: true }), document)).toBe(false)
    expect(shortcutEligible(ev({ keyCode: 229 }), document)).toBe(false)
    expect(shortcutEligible(ev({ shiftKey: true }), document)).toBe(true)   // '?' ve TR klavyede '/' Shift ister
  })

  it('letterOf: Caps Lock ve Türkçe İ aynı kısayol; çok karakterli tuş null', () => {
    expect(letterOf({ key: 'G' })).toBe('g')
    expect(letterOf({ key: 'İ' })).toBe('i')
    expect(letterOf({ key: 'I' })).toBe('i')
    expect(letterOf({ key: 'Enter' })).toBeNull()
    expect(letterOf({})).toBeNull()
  })

  it('goTarget: yalnız eşlenen harf ve kullanıcının açabildiği sekme', () => {
    expect(GO_SHORTCUTS.map((s) => `${s.key}:${s.tab}`)).toEqual(
      ['d:dashboard', 'm:monitoring', 'a:alerthistory', 's:status', 'h:http', 'i:domains', 'c:all'])
    expect(goTarget('d', new Set(['dashboard']))).toBe('dashboard')
    expect(goTarget('m', new Set(['dashboard']))).toBeNull()
    expect(goTarget('x', new Set(['dashboard']))).toBeNull()
    expect(goTarget('s', ['status'])).toBe('status')
    expect(SEQUENCE_MS).toBe(1000)
  })

  it('findPageSearch: görünür, etkin ilk kutu; gizli/devre dışı atlanır; focusPageSearch odaklanıp seçer', () => {
    document.body.innerHTML = `
      <div hidden><input id="h" ${PAGE_SEARCH_ATTR} /></div>
      <input id="d" ${PAGE_SEARCH_ATTR} disabled />
      <input id="v" ${PAGE_SEARCH_ATTR} value="abc" />`
    const v = document.getElementById('v')
    // jsdom yerleşim yapmaz: görünürlüğü yalnız işaretli öğe için doğrula
    for (const el of document.querySelectorAll(`[${PAGE_SEARCH_ATTR}]`)) el.checkVisibility = () => true
    expect(findPageSearch(document)).toBe(v)
    expect(focusPageSearch(document)).toBe(true)
    expect(document.activeElement).toBe(v)
    expect([v.selectionStart, v.selectionEnd]).toEqual([0, 3])
    v.checkVisibility = () => false
    expect(findPageSearch(document)).toBeNull()
    expect(focusPageSearch(document)).toBe(false)
  })

  it('isMacPlatform: Mac/iOS ⌘, diğerleri Ctrl', () => {
    expect(isMacPlatform({ platform: 'MacIntel' })).toBe(true)
    expect(isMacPlatform({ userAgentData: { platform: 'macOS' } })).toBe(true)
    expect(isMacPlatform({ platform: 'Win32' })).toBe(false)
    expect(isMacPlatform(null)).toBe(false)
  })
})

describe('KeyboardShortcuts — genel kısayollar ve liste', () => {
  it('`?` listeyi açar: mevcut kısayollar (Ctrl K, Ctrl B, Ctrl Enter, Esc) + yeniler; Esc kapatır', async () => {
    render(<KeyboardShortcuts tabs={TABS} onTabChange={vi.fn()} />)
    expect(screen.queryByRole('dialog')).toBeNull()
    const ev = key('?', { shiftKey: true })
    expect(ev.defaultPrevented).toBe(true)
    // Liste penceresi ilk açılışta tembel yüklenir (2026-10-09) — dinleyici ilk tuştan itibaren çalışır (yukarıda).
    const dlg = await screen.findByRole('dialog', { name: /Klavye kısayolları|Keyboard shortcuts/ })
    const row = (id) => dlg.querySelector(`[data-shortcut="${id}"]`)
    for (const id of ['help', 'search', 'palette', 'sidebar', 'escape', 'submit', 'palette-move', 'palette-open',
      'tour-next', 'tour-prev', 'tour-close', 'drawer-step', 'image-step']) expect(row(id), id).not.toBeNull()
    const kbds = (id) => [...row(id).querySelectorAll('[data-slot="kbd"]')].map((k) => k.textContent)
    expect(kbds('palette')).toEqual(['Ctrl', 'K'])
    expect(kbds('sidebar')).toEqual(['Ctrl', 'B'])
    expect(kbds('submit')).toEqual(['Ctrl', 'Enter'])
    expect(kbds('help')).toEqual(['?'])
    expect(kbds('search')).toEqual(['/'])
    // Gezinme: yalnız verilen sekmeler, adlarıyla; dizi "G sonra D"
    const go = dlg.querySelector('[data-group="go"]')
    expect([...go.querySelectorAll('[data-slot="shortcut-row"]')].map((r) => r.getAttribute('data-shortcut')))
      .toEqual(['go-d', 'go-m', 'go-a', 'go-s', 'go-h', 'go-i', 'go-c'])
    expect(within(row('go-d')).getByText('Genel Bakış')).toBeInTheDocument()
    expect(kbds('go-d')).toEqual(['G', 'D'])
    expect(row('go-d')).toHaveTextContent(/sonra|then/)
    expect(row('sql')).toBeNull()   // SQL Playground sekmesi görünmüyor → satır yok
    fireEvent.keyDown(document.activeElement || document.body, { key: 'Escape' })
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
  })

  it('`sm:shortcuts` olayı listeyi açar; görünmeyen sekmenin `g` satırı yok, SQL satırı yalnız SQL sekmesiyle', async () => {
    render(<KeyboardShortcuts tabs={[{ id: 'dashboard', label: 'Pano' }, { id: 'sqlplayground', label: 'SQL' }]} onTabChange={vi.fn()} />)
    act(() => { window.dispatchEvent(new CustomEvent(SHORTCUTS_EVENT)) })
    const dlg = await screen.findByRole('dialog')
    expect([...dlg.querySelectorAll('[data-group="go"] [data-slot="shortcut-row"]')].map((r) => r.getAttribute('data-shortcut'))).toEqual(['go-d'])
    expect(dlg.querySelector('[data-shortcut="sql"]')).not.toBeNull()
  })

  it('`?` yazı alanında, açık pencerede ya da Ctrl ile ÇALIŞMAZ ve tuşu tüketmez', () => {
    render(<><input aria-label="kutu" /><KeyboardShortcuts tabs={TABS} onTabChange={vi.fn()} /></>)
    const input = screen.getByLabelText('kutu')
    input.focus()
    expect(key('?', { shiftKey: true }, input).defaultPrevented).toBe(false)
    expect(screen.queryByRole('dialog')).toBeNull()
    input.blur()
    expect(key('?', { ctrlKey: true, shiftKey: true }).defaultPrevented).toBe(false)
    expect(screen.queryByRole('dialog')).toBeNull()
    const other = document.createElement('div')
    other.setAttribute('role', 'dialog')
    document.body.appendChild(other)
    expect(key('?', { shiftKey: true }).defaultPrevented).toBe(false)
    expect(screen.queryByRole('dialog', { name: /Klavye|Keyboard/ })).toBeNull()
    other.remove()
  })

  it('`g` sonra harf: açık sekmeye gider; sekme yoksa ya da 1 sn geçtiyse hiçbir şey yapmaz', () => {
    const onTab = vi.fn()
    const now = vi.spyOn(Date, 'now')
    let t = 1_000_000
    now.mockImplementation(() => t)
    render(<KeyboardShortcuts tabs={TABS.filter((tb) => tb.id !== 'status')} onTabChange={onTab} />)
    key('g')
    t += 400
    key('Shift')                                   // yalnız değiştirici: diziyi bozmaz
    expect(key('d').defaultPrevented).toBe(true)
    expect(onTab).toHaveBeenLastCalledWith('dashboard')
    key('g'); t += 200
    expect(key('s').defaultPrevented).toBe(false)  // Durum Sayfası bu kullanıcıya açık değil
    key('g'); t += SEQUENCE_MS + 1
    key('m')                                       // süre doldu
    key('d')                                       // `g` olmadan tek harf
    expect(onTab).toHaveBeenCalledTimes(1)
    key('G', { shiftKey: true }); t += 100          // Caps Lock / Shift: aynı kısayol
    key('M')
    expect(onTab).toHaveBeenLastCalledWith('monitoring')
    key('g'); key('x'); key('a')                   // eşleşmeyen harf diziyi bitirir
    expect(onTab).toHaveBeenCalledTimes(2)
    key('g'); key('a', { ctrlKey: true })          // değiştiricili harf kısayol değil
    expect(onTab).toHaveBeenCalledTimes(2)
    now.mockRestore()
  })

  it('`/` sayfanın arama kutusuna odaklanır; kutu yoksa tuşu tüketmez (tarayıcı davranışı korunur)', () => {
    const { unmount } = render(<KeyboardShortcuts tabs={TABS} onTabChange={vi.fn()} />)
    expect(key('/').defaultPrevented).toBe(false)
    unmount()
    render(<><input aria-label="ara" data-page-search="" defaultValue="api" /><KeyboardShortcuts tabs={TABS} onTabChange={vi.fn()} /></>)
    const input = screen.getByLabelText('ara')
    input.checkVisibility = () => true
    const ev = key('/', { shiftKey: true })        // TR klavyede `/` = Shift+7
    expect(ev.defaultPrevented).toBe(true)
    expect(document.activeElement).toBe(input)
    expect(input.selectionEnd).toBe(3)
    // Kutudayken `/` yazılır, yeniden yakalanmaz
    expect(key('/', {}, input).defaultPrevented).toBe(false)
  })

  it('Escape ve Tab hiç işlenmez', () => {
    render(<KeyboardShortcuts tabs={TABS} onTabChange={vi.fn()} />)
    expect(key('Escape').defaultPrevented).toBe(false)
    expect(key('Tab').defaultPrevented).toBe(false)
  })
})
