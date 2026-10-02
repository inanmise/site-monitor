import { Fragment, useEffect, useMemo, useRef, useState } from 'react'
import { Keyboard } from 'lucide-react'
import { useT } from '../i18n/index.jsx'
import ModalShell from './ui/ModalShell.jsx'
import { Kbd, KbdGroup } from '@/components/shadcn/kbd'
import {
  GO_SHORTCUTS, MODIFIER_KEYS, SEQUENCE_MS, SHORTCUTS_EVENT,
  focusPageSearch, goTarget, isMacPlatform, letterOf, shortcutEligible,
} from '../utils/keyboardShortcuts.js'

/**
 * Genel klavye kısayolları + "Klavye kısayolları" listesi (2026-10-02, öneri 24). Kurallar ve neden koşulları:
 * `utils/keyboardShortcuts.js`. Nav içinde, komut paletinin yanında çizilir — `tabs` paletle AYNI liste (menünün
 * görünürlük kuralları), `onTabChange` Nav'ın `go`'su (telefonda çekmeceyi de kapatır).
 *
 * Liste: `?`, komut paletinin "Klavye kısayolları" eylemi ve kullanıcı menüsü (`sm:shortcuts` olayı) açar. Pencere
 * ModalShell (shadcn Dialog): telefonda tam yükseklik, gövde kayar; geniş ekranda iki sütun. Mevcut kısayollar da
 * listelenir (palet, kenar çubuğu, gönder, Esc, palet içi, ürün turu, envanter ayrıntısı, ekran görüntüsü görüntüleyici).
 *
 * Test kancaları: `data-slot="shortcuts-dialog"` (gövde), `shortcut-group` (`data-group`), `shortcut-row` (`data-shortcut`).
 */

// Telefonda (< 640 px) tam ekran; kabuk (ModalShell scrollBody) yalnız gövdeyi kaydırır.
const PHONE_FULLSCREEN = [
  'max-sm:top-0 max-sm:left-0 max-sm:h-dvh max-sm:max-h-dvh max-sm:w-full max-sm:max-w-full',
  'max-sm:translate-x-0 max-sm:translate-y-0 max-sm:rounded-none max-sm:border-0',
  'max-sm:pb-[max(1rem,env(safe-area-inset-bottom))]',
].join(' ')

/** Bir satırın tuşları: akorlar (ör. ['Ctrl','K']) `join` ile ('then' | 'or') ayrılır. */
function Keys({ chords, join, t }) {
  const sep = join === 'then' ? t('shortcuts.then') : t('shortcuts.or')
  return (
    <span className="flex shrink-0 flex-wrap items-center justify-end gap-1 text-xs text-muted-foreground">
      {chords.map((chord, i) => (
        <Fragment key={i}>
          {i > 0 && <span>{sep}</span>}
          <KbdGroup>{chord.map((k) => <Kbd key={k}>{k}</Kbd>)}</KbdGroup>
        </Fragment>
      ))}
    </span>
  )
}

export default function KeyboardShortcuts({ tabs = [], onTabChange }) {
  const t = useT()
  const [open, setOpen] = useState(false)
  const pendingAt = useRef(0)            // `g` basıldığı an (ms); 0 = dizi yok
  const tabIdsRef = useRef(new Set())
  const goRef = useRef(onTabChange)
  const tabIds = useMemo(() => new Set(tabs.map((tb) => tb.id)), [tabs])
  tabIdsRef.current = tabIds
  goRef.current = onTabChange

  useEffect(() => {
    const onKey = (e) => {
      if (MODIFIER_KEYS.has(e.key)) return
      const now = Date.now()
      const pending = pendingAt.current > 0 && now - pendingAt.current <= SEQUENCE_MS
      pendingAt.current = 0
      if (!shortcutEligible(e)) return
      const letter = letterOf(e)
      if (pending) {
        const tab = goTarget(letter, tabIdsRef.current)
        if (tab) { e.preventDefault(); goRef.current?.(tab); return }
      }
      if (e.key === '?') { e.preventDefault(); setOpen(true); return }
      if (e.key === '/') { if (focusPageSearch()) e.preventDefault(); return }
      if (letter === 'g') pendingAt.current = now
    }
    const onOpen = () => setOpen(true)
    window.addEventListener('keydown', onKey)
    window.addEventListener(SHORTCUTS_EVENT, onOpen)
    return () => {
      window.removeEventListener('keydown', onKey)
      window.removeEventListener(SHORTCUTS_EVENT, onOpen)
    }
  }, [])

  const mac = useMemo(() => isMacPlatform(), [])
  const groups = useMemo(() => {
    if (!open) return []
    const MOD = mac ? '⌘' : 'Ctrl'
    const ALT = mac ? '⌥' : 'Alt'
    const label = Object.fromEntries(tabs.map((tb) => [tb.id, tb.label]))
    const go = GO_SHORTCUTS.filter((s) => tabIds.has(s.tab))
      .map((s) => ({ id: `go-${s.key}`, label: label[s.tab], chords: [['G'], [s.key.toUpperCase()]], join: 'then' }))
    return [
      {
        id: 'general', title: t('shortcuts.grp.general'), rows: [
          { id: 'help', label: t('shortcuts.help'), chords: [['?']] },
          { id: 'search', label: t('shortcuts.search'), chords: [['/']] },
          { id: 'palette', label: t('shortcuts.palette'), chords: [[MOD, 'K']] },
          { id: 'sidebar', label: t('shortcuts.sidebar'), chords: [[MOD, 'B']] },
          { id: 'escape', label: t('shortcuts.escape'), chords: [['Esc']] },
        ],
      },
      go.length > 0 && { id: 'go', title: t('shortcuts.grp.go'), hint: t('shortcuts.goHint'), rows: go },
      {
        id: 'forms', title: t('shortcuts.grp.forms'), rows: [
          { id: 'submit', label: t('shortcuts.submit'), chords: [[MOD, 'Enter']] },
          tabIds.has('sqlplayground') && { id: 'sql', label: t('shortcuts.sqlRun'), chords: [[MOD, 'Enter']] },
        ].filter(Boolean),
      },
      {
        id: 'palette', title: t('shortcuts.grp.palette'), rows: [
          { id: 'palette-move', label: t('shortcuts.paletteMove'), chords: [['↑'], ['↓']] },
          { id: 'palette-open', label: t('shortcuts.paletteOpen'), chords: [['↵']] },
        ],
      },
      {
        id: 'tour', title: t('shortcuts.grp.tour'), rows: [
          { id: 'tour-next', label: t('shortcuts.tourNext'), chords: [['→'], ['Enter']] },
          { id: 'tour-prev', label: t('shortcuts.tourPrev'), chords: [['←']] },
          { id: 'tour-close', label: t('tour.close'), chords: [['Esc']] },
        ],
      },
      {
        id: 'windows', title: t('shortcuts.grp.windows'), rows: [
          { id: 'drawer-step', label: t('shortcuts.drawerStep'), chords: [[ALT, '→'], [ALT, '←']] },
          { id: 'image-step', label: t('shortcuts.imageStep'), chords: [['→'], ['←']] },
        ],
      },
    ].filter(Boolean)
  }, [open, mac, tabs, tabIds, t])

  return (
    <ModalShell open={open} onClose={() => setOpen(false)} title={t('shortcuts.title')} icon={Keyboard} size="lg"
      scrollBody className={PHONE_FULLSCREEN}>
      <div data-slot="shortcuts-dialog" className="flex flex-col gap-4">
        <p className="m-0 text-sm text-muted-foreground">{t('shortcuts.intro')}</p>
        <div className="grid grid-cols-1 gap-x-8 gap-y-5 md:grid-cols-2">
          {groups.map((g) => (
            <section key={g.id} data-slot="shortcut-group" data-group={g.id} aria-labelledby={`sc-grp-${g.id}`} className="min-w-0">
              <h3 id={`sc-grp-${g.id}`} className="m-0 text-xs font-semibold tracking-wide text-muted-foreground uppercase">{g.title}</h3>
              {g.hint && <p className="m-0 mt-1 text-xs text-muted-foreground">{g.hint}</p>}
              <ul className="m-0 mt-1.5 flex list-none flex-col p-0">
                {g.rows.map((r) => (
                  <li key={r.id} data-slot="shortcut-row" data-shortcut={r.id}
                    className="flex min-h-10 items-center justify-between gap-3 border-b py-1.5 text-sm last:border-b-0">
                    <span className="min-w-0">{r.label}</span>
                    <Keys chords={r.chords} join={r.join} t={t} />
                  </li>
                ))}
              </ul>
            </section>
          ))}
        </div>
      </div>
    </ModalShell>
  )
}
