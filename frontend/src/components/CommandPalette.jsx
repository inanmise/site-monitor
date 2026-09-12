import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { createPortal } from 'react-dom'
import { Search, X, CornerDownLeft, Globe, Activity, Users, LayoutGrid } from 'lucide-react'
import { api } from '../api/client'
import { useT } from '../i18n/index.jsx'
import { navigateTo } from '../utils/navigate.js'

/**
 * Komut paleti (2026-09-12, zenginleştirme #1): Ctrl/Cmd+K → tek kutu; sekme adları istemcide, alan /
 * izleme / takım sunucudan (/api/search, takım kapsamlı). Ok tuşları + Enter; Esc kapatır.
 * Sonuca gidiş: sekme → onTabChange; diğerleri → navigateTo(tab, params) (App `sm:navigate` dinler:
 * ?domain= dashboard aramasına, ?monitor= izleme sayfasının derin bağlantısına, ?team= takım süzgecine düşer).
 */
const KIND_ICON = { tab: LayoutGrid, certificate: Globe, team: Users }
const MONITOR_KINDS = ['http', 'ping', 'port', 'dns', 'keyword', 'page', 'pagespeed', 'scripted', 'domain']

export default function CommandPalette({ tabs = [], onTabChange }) {
  const t = useT()
  const [open, setOpen] = useState(false)
  const [q, setQ] = useState('')
  const [remote, setRemote] = useState([])
  const [loading, setLoading] = useState(false)
  const [cursor, setCursor] = useState(0)
  const inputRef = useRef(null)
  const seq = useRef(0)

  // Ctrl/Cmd+K aç-kapa; yazı alanında yazarken bile çalışır (tarayıcı adres çubuğu odağını ezer).
  useEffect(() => {
    const onKey = (e) => {
      if ((e.ctrlKey || e.metaKey) && (e.key === 'k' || e.key === 'K')) { e.preventDefault(); setOpen((o) => !o) }
      else if (e.key === 'Escape') setOpen(false)
    }
    window.addEventListener('keydown', onKey)
    const onOpen = () => setOpen(true)
    window.addEventListener('sm:palette', onOpen)
    return () => { window.removeEventListener('keydown', onKey); window.removeEventListener('sm:palette', onOpen) }
  }, [])

  useEffect(() => {
    if (open) { setQ(''); setRemote([]); setCursor(0); setTimeout(() => inputRef.current?.focus(), 0) }
  }, [open])

  // Sunucu araması — 200 ms debounce, geç gelen yanıt atılır (seq).
  useEffect(() => {
    if (!open) return undefined
    const needle = q.trim()
    if (needle.length < 2) { setRemote([]); setLoading(false); return undefined }
    const my = ++seq.current
    setLoading(true)
    const h = setTimeout(async () => {
      try {
        const r = await api.search(needle)
        if (my !== seq.current) return
        setRemote(r?.success && Array.isArray(r.data) ? r.data : [])
      } catch { if (my === seq.current) setRemote([]) }
      finally { if (my === seq.current) setLoading(false) }
    }, 200)
    return () => clearTimeout(h)
  }, [q, open])

  const tabHits = useMemo(() => {
    const needle = q.trim().toLocaleLowerCase('tr')
    return tabs
      .filter((tb) => !needle || tb.label.toLocaleLowerCase('tr').includes(needle) || tb.id.includes(needle))
      .slice(0, needle ? 6 : 8)
      .map((tb) => ({ kind: 'tab', id: tb.id, label: tb.label, tab: tb.id }))
  }, [q, tabs])

  const items = useMemo(() => [...tabHits, ...remote], [tabHits, remote])
  useEffect(() => { setCursor(0) }, [items.length])

  const go = useCallback((it) => {
    setOpen(false)
    if (!it) return
    if (it.kind === 'tab') { onTabChange?.(it.id); return }
    navigateTo(it.tab, it.params)
  }, [onTabChange])

  function onInputKey(e) {
    if (e.key === 'ArrowDown') { e.preventDefault(); setCursor((c) => Math.min(items.length - 1, c + 1)) }
    else if (e.key === 'ArrowUp') { e.preventDefault(); setCursor((c) => Math.max(0, c - 1)) }
    else if (e.key === 'Enter') { e.preventDefault(); go(items[cursor]) }
  }

  if (!open) return null

  const kindLabel = (k) => t(`palette.kind.${MONITOR_KINDS.includes(k) ? 'monitor' : k}`)
  const groups = []
  for (const it of items) {
    const g = it.kind === 'tab' ? 'tab' : it.kind === 'certificate' ? 'certificate' : it.kind === 'team' ? 'team' : 'monitor'
    let grp = groups.find((x) => x.key === g)
    if (!grp) { grp = { key: g, items: [] }; groups.push(grp) }
    grp.items.push(it)
  }
  let flat = -1

  return createPortal(
    <div className="palette-overlay" onMouseDown={(e) => { if (e.target === e.currentTarget) setOpen(false) }}>
      <div className="palette" role="dialog" aria-modal="true" aria-label={t('palette.title')}>
        <div className="palette-input-row">
          <Search size={16} aria-hidden="true" />
          <input ref={inputRef} className="palette-input" value={q} onChange={(e) => setQ(e.target.value)} onKeyDown={onInputKey}
            placeholder={t('palette.placeholder')} aria-label={t('palette.title')} aria-activedescendant={items[cursor] ? `pal-${cursor}` : undefined} />
          {loading && <span className="palette-loading">{t('palette.searching')}</span>}
          <button type="button" className="palette-close" onClick={() => setOpen(false)} aria-label={t('app.close')}><X size={14} /></button>
        </div>
        <div className="palette-body" role="listbox">
          {items.length === 0 && (
            <div className="palette-empty">{q.trim().length >= 2 && !loading ? t('palette.noResults') : t('palette.hint')}</div>
          )}
          {groups.map((g) => (
            <div key={g.key} className="palette-group">
              <div className="palette-group-title">{t(`palette.kind.${g.key}`)}</div>
              {g.items.map((it) => {
                flat += 1
                const idx = flat
                const Icon = KIND_ICON[it.kind] || Activity
                return (
                  <button type="button" key={`${it.kind}-${it.id}`} id={`pal-${idx}`} role="option" aria-selected={idx === cursor}
                    className={`palette-item${idx === cursor ? ' is-active' : ''}`}
                    onMouseEnter={() => setCursor(idx)} onClick={() => go(it)}>
                    <Icon size={14} aria-hidden="true" />
                    <span className="palette-item-label">{it.label}</span>
                    {it.sub && <span className="palette-item-sub">{it.sub}</span>}
                    {it.kind !== 'tab' && <span className="palette-item-kind">{kindLabel(it.kind)}{MONITOR_KINDS.includes(it.kind) ? ` · ${it.kind}` : ''}</span>}
                    {idx === cursor && <CornerDownLeft size={12} className="palette-enter" aria-hidden="true" />}
                  </button>
                )
              })}
            </div>
          ))}
        </div>
        <div className="palette-foot">
          <kbd>↑↓</kbd> {t('palette.navigate')} · <kbd>Enter</kbd> {t('palette.open')} · <kbd>Esc</kbd> {t('palette.close')}
        </div>
      </div>
    </div>,
    document.body,
  )
}
