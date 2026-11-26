import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { useRef } from 'react'
import { render, screen, act, waitFor } from './test-utils.jsx'

/**
 * Nöbet Rutinleri — sayfa kısayolları (sözleşme §9.6). Pinlenenler:
 *  - `routineKeyAction`: j k x n p s o c t [ ] Shift+D → eylem adı; başka tuş / Shift + başka harf → null;
 *  - `focusedTaskKey`: odaktaki öğenin en yakın `[data-slot="or-task"]` satırının `data-key`'i;
 *  - `useRoutineKeys`: eylem odaktaki anahtarla çağrılır ve tuş tüketilir; yazı alanında, açık pencere / menüde, IME
 *    bileşiminde, Ctrl / ⌘ / Alt ile ve genel `g` + harf dizisinin ikinci tuşunda ÇALIŞMAZ (`shortcutEligible`); `false`
 *    dönen eylem tuşu tüketmez; kapalıyken dinleyici yok, sökülünce kalkar;
 *  - `OnCallRoutinesPage` kuruluyken `OR_PAGE_CONTEXT` kayıtlı (kısayol listesindeki grup), sökülünce değil.
 */
const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
vi.mock('../api/client', async (orig) => ({
  ...(await orig()),
  api: withApiFallback({ oncallRoutines: { overview: vi.fn(), day: vi.fn() } }),
}))

import { api } from '../api/client'
import { focusedTaskKey, routineKeyAction, useRoutineKeys } from '../components/oncallroutine/routineKeys.js'
import { SEQUENCE_MS } from '../utils/keyboardShortcuts.js'
import { OR_PAGE_CONTEXT, activeShortcutContexts } from '../utils/shortcutContexts.js'
import OnCallRoutinesPage from '../components/oncallroutine/OnCallRoutinesPage.jsx'

const KEY_A = 'DAY:2026-11-26:t-a1'
const KEY_B = 'WEEK:2026-11-23:t-w2'
const NAMES = ['next', 'prev', 'toggle', 'note', 'problem', 'skip', 'links', 'section', 'complete', 'dayPrev', 'dayNext', 'today']

/** Tuş → beklenen eylem (Caps Lock açıkken de aynı; Shift+D bölüm). */
const ACTION_TABLE = [
  [{ key: 'j' }, 'next'], [{ key: 'k' }, 'prev'], [{ key: 'x' }, 'toggle'], [{ key: 'n' }, 'note'],
  [{ key: 'p' }, 'problem'], [{ key: 's' }, 'skip'], [{ key: 'o' }, 'links'], [{ key: 'c' }, 'complete'],
  [{ key: 't' }, 'today'], [{ key: '[' }, 'dayPrev'], [{ key: ']' }, 'dayNext'],
  [{ key: 'D', shiftKey: true }, 'section'], [{ key: 'd', shiftKey: true }, 'section'],
  [{ key: 'J' }, 'next'], [{ key: 'X' }, 'toggle'], [{ key: 'C' }, 'complete'],
]

/** Bu sayfanın kısayolu OLMAYAN tuşlar. */
const NULL_TABLE = [
  { key: 'd' }, { key: 'g' }, { key: 'a' }, { key: 'z' }, { key: 'i' }, { key: 'ı' }, { key: 'İ' }, { key: '/' },
  { key: '?', shiftKey: true }, { key: 'Enter' }, { key: ' ' }, { key: 'Escape' }, { key: 'Tab' }, { key: 'ArrowDown' },
  { key: 'J', shiftKey: true }, { key: 'X', shiftKey: true }, { key: 'C', shiftKey: true }, { key: 'T', shiftKey: true },
  { key: 'N', shiftKey: true }, { key: 'Shift', shiftKey: true }, {},
]

const mkActions = (over = {}) => ({ ...Object.fromEntries(NAMES.map((n) => [n, vi.fn()])), ...over })

/** Odaktaki öğeye (yoksa gövdeye) keydown yollar; olay pencereye kabarır. */
function press(key, opts = {}) {
  const target = document.activeElement && document.activeElement !== document.documentElement ? document.activeElement : document.body
  const ev = new KeyboardEvent('keydown', { key, bubbles: true, cancelable: true, ...opts })
  act(() => { target.dispatchEvent(ev) })
  return ev
}

function Harness({ enabled = true, actions, overlay = null }) {
  const ref = useRef(null)
  ref.current = actions
  useRoutineKeys(enabled, ref)
  return (
    <div>
      <div data-slot="or-task" data-key={KEY_A}>
        <button type="button" role="checkbox" aria-checked="false" aria-label="Görev A işaret">A</button>
      </div>
      <div data-slot="or-task" data-key={KEY_B}>
        <button type="button" role="checkbox" aria-checked="false" aria-label="Görev B işaret">B</button>
        <span><button type="button">Görev B bağlantıları</button></span>
        <input aria-label="Görev B not" />
        <textarea aria-label="Görev B açıklama" />
        <button type="button" role="combobox" aria-expanded="false" aria-label="Görev B seçim">seç</button>
      </div>
      <div data-slot="or-task">
        <button type="button">Anahtarsız satır</button>
      </div>
      <div contentEditable suppressContentEditableWarning tabIndex={0} aria-label="Serbest metin">yaz</div>
      <button type="button">Dış düğme</button>
      {overlay && <div role={overlay} aria-label="Açık katman">katman</div>}
    </div>
  )
}

const focusOn = (name, role = 'button') => { act(() => { screen.getByRole(role, { name }).focus() }) }
/** A satırının onay kutusuna odak (gerçek satırdaki gibi `role="checkbox"` düğme). */
const focusA = () => focusOn('Görev A işaret', 'checkbox')

describe('routineKeyAction — tuş tablosu', () => {
  it('her sayfa kısayolu kendi eylem adına eşlenir', () => {
    for (const [ev, want] of ACTION_TABLE) expect(routineKeyAction(ev), JSON.stringify(ev)).toBe(want)
  })

  it('başka tuşlar ve Shift + başka harf null; olay yoksa null', () => {
    for (const ev of NULL_TABLE) expect(routineKeyAction(ev), JSON.stringify(ev)).toBeNull()
    expect(routineKeyAction(null)).toBeNull()
    expect(routineKeyAction(undefined)).toBeNull()
  })
})

describe('focusedTaskKey', () => {
  it('odaktaki öğenin en yakın görev satırının anahtarı; satır dışında / anahtarsız satırda null', () => {
    render(<Harness enabled={false} actions={mkActions()} />)
    focusA()
    expect(focusedTaskKey()).toBe(KEY_A)
    focusOn('Görev B bağlantıları') // satırın içindeki iç içe öğe
    expect(focusedTaskKey()).toBe(KEY_B)
    act(() => { screen.getByRole('textbox', { name: 'Görev B not' }).focus() })
    expect(focusedTaskKey()).toBe(KEY_B)
    focusOn('Anahtarsız satır')
    expect(focusedTaskKey()).toBeNull()
    focusOn('Dış düğme')
    expect(focusedTaskKey()).toBeNull()
    act(() => { document.activeElement.blur() })
    expect(focusedTaskKey()).toBeNull()
  })

  it('belge yoksa / odak yoksa / öğe `closest` bilmiyorsa null', () => {
    expect(focusedTaskKey(null)).toBeNull()
    expect(focusedTaskKey({ activeElement: null })).toBeNull()
    expect(focusedTaskKey({ activeElement: {} })).toBeNull()
  })
})

describe('useRoutineKeys — bağlama ve eylem çağrısı', () => {
  it('her kısayol odaktaki satırın anahtarıyla kendi eylemini çağırır ve tuşu tüketir; başka eylem çağrılmaz', () => {
    const actions = mkActions()
    render(<Harness actions={actions} />)
    for (const [init, name] of ACTION_TABLE) {
      for (const n of NAMES) actions[n].mockClear()
      focusA()
      const { key, ...rest } = init
      const ev = press(key, rest)
      expect(actions[name], `${key} → ${name}`).toHaveBeenCalledTimes(1)
      expect(actions[name]).toHaveBeenCalledWith(KEY_A)
      expect(ev.defaultPrevented, `${key} tüketilir`).toBe(true)
      for (const n of NAMES) if (n !== name) expect(actions[n], `${key} ≠ ${n}`).not.toHaveBeenCalled()
    }
  })

  it('başka satırda odak → o satırın anahtarı; satır dışında odak → null ile çağrılır', () => {
    const actions = mkActions()
    render(<Harness actions={actions} />)
    focusOn('Görev B bağlantıları')
    press('x')
    expect(actions.toggle).toHaveBeenLastCalledWith(KEY_B)
    focusOn('Dış düğme')
    press('j')
    expect(actions.next).toHaveBeenLastCalledWith(null)
    act(() => { document.activeElement.blur() })
    press('c')
    expect(actions.complete).toHaveBeenLastCalledWith(null)
  })

  it('sayfanın olmayan tuşları hiçbir eylemi çağırmaz ve tüketilmez', () => {
    const actions = mkActions()
    render(<Harness actions={actions} />)
    focusA()
    for (const init of NULL_TABLE) {
      if (!init.key) continue
      const { key, ...rest } = init
      const ev = press(key, rest)
      expect(ev.defaultPrevented, key).toBe(false)
    }
    for (const n of NAMES) expect(actions[n], n).not.toHaveBeenCalled()
  })

  it('`false` dönen eylem tuşu TÜKETMEZ (tarayıcı davranışı kalır); `true` / dönüşsüz tüketir', () => {
    const actions = mkActions({ toggle: vi.fn(() => false), note: vi.fn(() => true) })
    render(<Harness actions={actions} />)
    focusA()
    const ev1 = press('x')
    expect(actions.toggle).toHaveBeenCalledWith(KEY_A)
    expect(ev1.defaultPrevented).toBe(false)
    const ev2 = press('n')
    expect(actions.note).toHaveBeenCalledWith(KEY_A)
    expect(ev2.defaultPrevented).toBe(true)
    const ev3 = press('p')
    expect(actions.problem).toHaveBeenCalled()
    expect(ev3.defaultPrevented).toBe(true)
  })

  it('eylem tanımlı değilse ya da eylem nesnesi yoksa tuş yok sayılır (hata yok, tüketilmez)', () => {
    const partial = { next: vi.fn() }
    const { rerender } = render(<Harness actions={partial} />)
    focusA()
    const ev = press('x')
    expect(ev.defaultPrevented).toBe(false)
    expect(press('j').defaultPrevented).toBe(true)
    expect(partial.next).toHaveBeenCalledWith(KEY_A)
    rerender(<Harness actions={null} />)
    expect(() => press('j')).not.toThrow()
    rerender(<Harness actions={{ toggle: 'işlev değil' }} />)
    expect(press('x').defaultPrevented).toBe(false)
  })
})

describe('useRoutineKeys — çalışmadığı durumlar (shortcutEligible)', () => {
  it('yazı alanında odak (input / textarea / contenteditable / tuşları kendisi kullanan bileşen) → eylem yok', () => {
    const actions = mkActions()
    render(<Harness actions={actions} />)
    const targets = [
      () => screen.getByRole('textbox', { name: 'Görev B not' }),
      () => screen.getByRole('textbox', { name: 'Görev B açıklama' }),
      () => screen.getByLabelText('Serbest metin'),
      () => screen.getByRole('combobox', { name: 'Görev B seçim' }),
    ]
    for (const get of targets) {
      act(() => { get().focus() })
      for (const k of ['x', 'j', 'n', 'c', '[', 't']) {
        const ev = press(k)
        expect(ev.defaultPrevented, `${get().tagName} ${k}`).toBe(false)
      }
      expect(press('D', { shiftKey: true }).defaultPrevented).toBe(false)
    }
    for (const n of NAMES) expect(actions[n], n).not.toHaveBeenCalled()
  })

  it('açık pencere / uyarı penceresi / menü varken eylem yok', () => {
    for (const role of ['dialog', 'alertdialog', 'menu']) {
      const actions = mkActions()
      const { unmount } = render(<Harness actions={actions} overlay={role} />)
      focusA()
      for (const k of ['x', 'j', 'c', 's']) expect(press(k).defaultPrevented, `${role} ${k}`).toBe(false)
      for (const n of NAMES) expect(actions[n], `${role} ${n}`).not.toHaveBeenCalled()
      unmount()
    }
  })

  it('IME bileşimi sürerken eylem yok', () => {
    const actions = mkActions()
    render(<Harness actions={actions} />)
    focusA()
    expect(press('x', { isComposing: true }).defaultPrevented).toBe(false)
    expect(press('j', { isComposing: true }).defaultPrevented).toBe(false)
    expect(actions.toggle).not.toHaveBeenCalled()
    expect(actions.next).not.toHaveBeenCalled()
  })

  it('Ctrl / ⌘ / Alt (AltGr dahil) ile eylem yok — tarayıcı / işletim sistemi kısayolları korunur', () => {
    const actions = mkActions()
    render(<Harness actions={actions} />)
    focusA()
    for (const mod of [{ ctrlKey: true }, { metaKey: true }, { altKey: true }, { ctrlKey: true, altKey: true }]) {
      for (const k of ['x', 'c', 'j', 't', '[']) expect(press(k, mod).defaultPrevented, `${JSON.stringify(mod)} ${k}`).toBe(false)
      expect(press('D', { ...mod, shiftKey: true }).defaultPrevented).toBe(false)
    }
    for (const n of NAMES) expect(actions[n], n).not.toHaveBeenCalled()
  })

  it('başka bir işleyicinin tükettiği tuş yok sayılır', () => {
    const actions = mkActions()
    render(<Harness actions={actions} />)
    focusA()
    const eat = (e) => e.preventDefault()
    window.addEventListener('keydown', eat, true)
    try {
      press('x')
      press('j')
    } finally {
      window.removeEventListener('keydown', eat, true)
    }
    expect(actions.toggle).not.toHaveBeenCalled()
    expect(actions.next).not.toHaveBeenCalled()
  })
})

describe('useRoutineKeys — genel `g` + harf dizisi', () => {
  let clock
  let spy
  beforeEach(() => {
    clock = { now: 1_000_000 }
    spy = vi.spyOn(Date, 'now').mockImplementation(() => clock.now)
  })
  afterEach(() => { spy.mockRestore() })

  it('`g` bu sayfanın eylemi değildir; `g` + harf (SEQUENCE_MS içinde) ikinci tuşu çalışmaz, tüketilmez', () => {
    const actions = mkActions()
    render(<Harness actions={actions} />)
    focusA()
    expect(press('g').defaultPrevented).toBe(false)
    clock.now += 300
    expect(press('c').defaultPrevented).toBe(false) // genel `g` `c` (Tüm Sertifikalar) — günü tamamla AÇILMAZ
    expect(actions.complete).not.toHaveBeenCalled()
    clock.now += 100
    press('c') // dizi tüketildi: sıradaki `c` sayfanındır
    expect(actions.complete).toHaveBeenCalledWith(KEY_A)
  })

  it('`g` üzerinden SEQUENCE_MS geçtiyse harf sayfanın kısayoludur', () => {
    const actions = mkActions()
    render(<Harness actions={actions} />)
    focusA()
    press('g')
    clock.now += SEQUENCE_MS + 1
    expect(press('x').defaultPrevented).toBe(true)
    expect(actions.toggle).toHaveBeenCalledWith(KEY_A)
  })

  it('tam SEQUENCE_MS sınırında hâlâ dizinin ikinci tuşu sayılır', () => {
    const actions = mkActions()
    render(<Harness actions={actions} />)
    focusA()
    press('g')
    clock.now += SEQUENCE_MS
    press('x')
    expect(actions.toggle).not.toHaveBeenCalled()
  })

  it('Shift+G dizi başlatmaz (sonraki harf sayfanındır)', () => {
    const actions = mkActions()
    render(<Harness actions={actions} />)
    focusA()
    press('G', { shiftKey: true })
    clock.now += 100
    press('x')
    expect(actions.toggle).toHaveBeenCalledWith(KEY_A)
  })

  it('SÖZLEŞME: yalnız değiştirici tuş (Shift) diziyi bozmaz — `g`, Shift, Shift+D genel `g`+`d` sayılır, bölüm tamamlanmaz', () => {
    // Genel işleyici (KeyboardShortcuts.jsx) MODIFIER_KEYS'i yok sayar: `g` sonra Shift basılı `D` = `g` + `d`.
    const actions = mkActions()
    render(<Harness actions={actions} />)
    focusA()
    press('g')
    clock.now += 100
    press('Shift', { shiftKey: true })
    clock.now += 100
    press('D', { shiftKey: true })
    expect(actions.section).not.toHaveBeenCalled()
  })
})

describe('useRoutineKeys — yaşam döngüsü', () => {
  it('kapalıyken hiçbir tuş dinlenmez; açılınca dinlenir; yeniden kapanınca dinleyici kalkar', () => {
    const actions = mkActions()
    const { rerender } = render(<Harness enabled={false} actions={actions} />)
    focusA()
    for (const [init] of ACTION_TABLE) {
      const { key, ...rest } = init
      expect(press(key, rest).defaultPrevented, key).toBe(false)
    }
    for (const n of NAMES) expect(actions[n], n).not.toHaveBeenCalled()

    rerender(<Harness enabled actions={actions} />)
    press('x')
    expect(actions.toggle).toHaveBeenCalledTimes(1)

    rerender(<Harness enabled={false} actions={actions} />)
    press('x')
    expect(actions.toggle).toHaveBeenCalledTimes(1)
  })

  it('sökülünce dinleyici kalkar', () => {
    const actions = mkActions()
    const { unmount } = render(<Harness actions={actions} />)
    focusA()
    press('j')
    expect(actions.next).toHaveBeenCalledTimes(1)
    unmount()
    press('j')
    press('x')
    expect(actions.next).toHaveBeenCalledTimes(1)
    expect(actions.toggle).not.toHaveBeenCalled()
  })

  it('eylemler her çizimde yenilense de (ref) en son eylem çağrılır, dinleyici yeniden bağlanmaz', () => {
    const first = mkActions()
    const second = mkActions()
    const { rerender } = render(<Harness actions={first} />)
    rerender(<Harness actions={second} />)
    focusA()
    press('x')
    expect(first.toggle).not.toHaveBeenCalled()
    expect(second.toggle).toHaveBeenCalledWith(KEY_A)
  })
})

describe('OnCallRoutinesPage — kısayol bağlamı', () => {
  const OVERVIEW = {
    success: true,
    status: 200,
    data: {
      module_visible: true,
      default_team_id: 12,
      teams: [{
        team_id: 12, team_name: 'Dijital Kanallar SY', member: true, can_work: true, can_edit_template: true,
        can_manage: false, configured: true, enabled: true, tasks_active: 24,
        today: { date: '2026-11-26', workday: true, status: 'OPEN', required: 18, resolved: 12, overdue: 2, problems_open: 3 },
        responsible: { user_id: 'u-1', name: 'Ayşe Yılmaz (Dijital Kanallar / Uygulama Destek Uzmanı)', is_me: true, active: true, source: 'SCHEDULE' },
      }],
    },
  }

  beforeEach(() => {
    window.history.replaceState(null, '', '/?tab=oncallroutines')
    api.oncallRoutines.overview.mockReset()
    api.oncallRoutines.day.mockReset()
    api.oncallRoutines.overview.mockResolvedValue(OVERVIEW)
    api.oncallRoutines.day.mockImplementation(() => new Promise(() => {})) // gün sayfası hiç gelmez (yükleniyor)
  })
  afterEach(() => { window.history.replaceState(null, '', '/') })

  it('sayfa kuruluyken OR_PAGE_CONTEXT kayıtlı, sökülünce kalkar', async () => {
    expect(activeShortcutContexts().has(OR_PAGE_CONTEXT)).toBe(false)
    const { unmount, container } = render(<OnCallRoutinesPage />)
    expect(activeShortcutContexts().has(OR_PAGE_CONTEXT)).toBe(true)
    await waitFor(() => expect(api.oncallRoutines.day).toHaveBeenCalled())
    expect(api.oncallRoutines.day.mock.calls[0][0]).toBe(12)
    expect(container.querySelector('[data-slot="or-page"]')?.getAttribute('data-team')).toBe('12')
    expect(activeShortcutContexts().has(OR_PAGE_CONTEXT)).toBe(true)
    unmount()
    expect(activeShortcutContexts().has(OR_PAGE_CONTEXT)).toBe(false)
  })

  it('genel bakış okunamasa da (yükleniyor / hata) bağlam yalnız sayfa kuruluyken kayıtlı', async () => {
    api.oncallRoutines.overview.mockResolvedValue({ success: false, status: 500, code: 'INTERNAL_ERROR', error: 'Sunucu hatası' })
    const { unmount, container } = render(<OnCallRoutinesPage />)
    expect(activeShortcutContexts().has(OR_PAGE_CONTEXT)).toBe(true)
    await waitFor(() => expect(container.querySelector('[data-slot="or-load-failed"]')).not.toBeNull())
    expect(activeShortcutContexts().has(OR_PAGE_CONTEXT)).toBe(true)
    unmount()
    expect(activeShortcutContexts().has(OR_PAGE_CONTEXT)).toBe(false)
  })

  it('StrictMode (etkiler iki kez): kayıt sayaçlı — sökülünce kayıt kalmaz', async () => {
    const { unmount } = render(<OnCallRoutinesPage />, { reactStrictMode: true })
    expect(activeShortcutContexts().has(OR_PAGE_CONTEXT)).toBe(true)
    await waitFor(() => expect(api.oncallRoutines.day).toHaveBeenCalled())
    unmount()
    expect(activeShortcutContexts().has(OR_PAGE_CONTEXT)).toBe(false)
  })
})
