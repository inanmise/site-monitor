import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { Globe, Activity, Users, LayoutGrid, UsersRound, FolderOpen, Tag } from 'lucide-react'
import { api } from '../api/client'
import { useT } from '../i18n/index.jsx'
import { navigateTo } from '../utils/navigate.js'
import { Dialog, DialogContent, DialogTitle } from '@/components/shadcn/dialog'
import { Command, CommandEmpty, CommandGroup, CommandInput, CommandItem, CommandList } from '@/components/shadcn/command'
import { Badge } from '@/components/shadcn/badge'
import { Kbd } from '@/components/shadcn/kbd'

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
    // Odak: shadcn Dialog açılışta ilk alana (arama kutusu) kendisi verir.
    if (open) { setQ(''); setRemote([]) }
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
    // Ürün turu komutu (2026-09-13): sekme listesinin sonunda; aramada 'tur' ile bulunur
    const tourLabel = t('tour.paletteCmd')
    const tourHit = (!needle || tourLabel.toLocaleLowerCase('tr').includes(needle) || 'tour'.includes(needle)) ? [{ kind: 'tab', id: '__tour', label: tourLabel, tab: null }] : []
    return tabs
      .filter((tb) => !needle || tb.label.toLocaleLowerCase('tr').includes(needle) || tb.id.includes(needle))
      .slice(0, needle ? 6 : 8)
      .map((tb) => ({ kind: 'tab', id: tb.id, label: tb.label, tab: tb.id }))
      .concat(tourHit)
  }, [q, tabs, t])

  const items = useMemo(() => [...tabHits, ...remote], [tabHits, remote])

  const go = useCallback((it) => {
    setOpen(false)
    if (!it) return
    if (it.id === '__tour') { try { window.dispatchEvent(new CustomEvent('sm:tour-start', { detail: { kind: 'main' } })) } catch { /* yoksay */ } return }
    if (it.kind === 'tab') { onTabChange?.(it.id); return }
    navigateTo(it.tab, it.params)
  }, [onTabChange])


  const kindLabel = (k) => t(`palette.kind.${MONITOR_KINDS.includes(k) ? 'monitor' : k}`)
  const groups = []
  for (const it of items) {
    const g = it.kind === 'tab' ? 'tab' : it.kind === 'certificate' ? 'certificate' : it.kind === 'team' ? 'team' : 'monitor'
    let grp = groups.find((x) => x.key === g)
    if (!grp) { grp = { key: g, items: [] }; groups.push(grp) }
    grp.items.push(it)
  }

  // shadcn Command (cmdk) + Dialog: ok tuşları, Enter ve etkin öğe vurgusu cmdk'den; süzmeyi BİZ yapıyoruz
  // (istemcide sekmeler + sunucuda /api/search) → shouldFilter={false}.
  return (
    <Dialog open={open} onOpenChange={setOpen}>
      <DialogContent showCloseButton={false} aria-describedby={undefined}
        className="top-[12vh] translate-y-0 gap-0 overflow-hidden p-0 sm:max-w-xl">
        <DialogTitle className="sr-only">{t('palette.title')}</DialogTitle>
        <Command shouldFilter={false} loop className="[&_[cmdk-group-heading]]:px-2 [&_[cmdk-group-heading]]:text-xs [&_[cmdk-group-heading]]:font-medium [&_[cmdk-group-heading]]:text-muted-foreground">
          <div className="relative">
            <CommandInput value={q} onValueChange={setQ} placeholder={t('palette.placeholder')} aria-label={t('palette.title')} className="h-11 pr-24" />
            {loading && <span className="absolute top-1/2 right-3 -translate-y-1/2 text-xs text-muted-foreground">{t('palette.searching')}</span>}
          </div>
          <CommandList className="max-h-[min(60vh,420px)]">
            <CommandEmpty>{q.trim().length >= 2 && !loading ? t('palette.noResults') : t('palette.hint')}</CommandEmpty>
            {groups.map((g) => (
              <CommandGroup key={g.key} heading={t(`palette.kind.${g.key}`)}>
                {g.items.map((it) => {
                  const Icon = KIND_ICON[it.kind] || Activity
                  return (
                    <CommandItem key={`${it.kind}-${it.id}`} value={`${it.kind}-${it.id}`} onSelect={() => go(it)} className="gap-2.5">
                      <Icon aria-hidden="true" />
                      <span className="flex min-w-0 flex-1 flex-col">
                        <span className="truncate">{it.label}{it.sub ? <span className="text-muted-foreground"> {it.sub}</span> : null}</span>
                        {/* 2026-09-20: takım / grup / etiket / tier — "hangi takımın?" sorusu sonuçta cevaplansın */}
                        {(it.team_name || it.group_name || it.tags || it.tier) && (
                          <span className="mt-0.5 flex flex-wrap gap-1">
                            {it.team_name && <Badge variant="secondary" className="h-4 gap-0.5 px-1.5 text-[10px]"><UsersRound aria-hidden="true" /> {it.team_name}</Badge>}
                            {it.group_name && <Badge variant="outline" className="h-4 gap-0.5 px-1.5 text-[10px]"><FolderOpen aria-hidden="true" /> {it.group_name}</Badge>}
                            {it.tier && <Badge variant="outline" className="h-4 px-1.5 text-[10px]">T{it.tier}</Badge>}
                            {it.tags && String(it.tags).split(',').map(x => x.trim()).filter(Boolean).slice(0, 4).map(tag => (
                              <Badge key={tag} variant="outline" className="h-4 gap-0.5 px-1.5 text-[10px]"><Tag aria-hidden="true" /> {tag}</Badge>
                            ))}
                          </span>
                        )}
                      </span>
                      {it.kind !== 'tab' && <span className="shrink-0 text-xs text-muted-foreground">{kindLabel(it.kind)}{MONITOR_KINDS.includes(it.kind) ? ` · ${it.kind}` : ''}</span>}
                    </CommandItem>
                  )
                })}
              </CommandGroup>
            ))}
          </CommandList>
          <div className="flex items-center gap-1.5 border-t px-3 py-2 text-xs text-muted-foreground">
            <Kbd>↑↓</Kbd> {t('palette.navigate')} · <Kbd>Enter</Kbd> {t('palette.open')} · <Kbd>Esc</Kbd> {t('palette.close')}
          </div>
        </Command>
      </DialogContent>
    </Dialog>
  )
}
