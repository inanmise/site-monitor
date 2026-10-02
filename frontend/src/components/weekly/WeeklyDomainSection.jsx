import { useId, useMemo, useState } from 'react'
import { ArrowDown, ArrowUp, ChevronDown, Copy, FilePenLine, Layers, ListPlus, Plus, Trash2 } from 'lucide-react'
import { useT } from '../../i18n/index.jsx'
import { useIsMobile } from '../../hooks/use-mobile.js'
import { useDialog } from '../ui/Dialog.jsx'
import KebabMenu from '../ui/KebabMenu.jsx'
import StatusBlock from '../ui/StatusBlock.jsx'
import { MarkdownView, MdField } from './WeeklyMdField.jsx'
import { PrevNoteToggle } from './WeeklyListExtras.jsx'
import { domainSummary, duplicateChannelIndexes } from './editorModel.js'
import { useElementWidthState } from '../../hooks/useElementWidth.js'
import { Badge } from '@/components/shadcn/badge'
import { Button } from '@/components/shadcn/button'
import { Input } from '@/components/shadcn/input'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/shadcn/table'
import { ToggleGroup, ToggleGroupItem } from '@/components/shadcn/toggle-group'
import { cn } from '@/lib/utils'

/*
 * Madde 4 — "Domain Bazlı Kritik İşlerin Durumu" (2026-09-27 yeniden tasarım). Saklanan veri DEĞİŞMEDİ:
 * `item4.channels = [{ id, name, notes_md }]` (alan adı + o alanın bu haftaki Markdown güncellemesi; ek anahtarlar
 * korunur). Eskiden her alan bir sekmeydi (20 alana kadar sekme şeridi taşıyordu, notu olmayan alan görünmüyordu).
 *
 * Düzenleyici: geniş kapta shadcn Table (sıra no · alan adı satır içi · güncelleme özeti · yukarı/aşağı + işlemler);
 * satıra basınca güncelleme düzenleyicisi satırın altında açılır (tek seferde bir). Dar kapta (telefon, kenar çubuğu açık
 * tablet) her alan bir kart + KebabMenu. Alan adında Enter altına yeni alan ekler; boş / yinelenen ad işaretlenir
 * (kontrol listesinde de). Okuma görünümü: özet satırı, çok alanda durum süzgeci, tablo / kart, güncellemesiz alan soluk.
 * Test kancaları: data-slot="wr-domain-table" | "wr-domain-row" | "wr-domain-card" | "wr-domain-summary" |
 * "wr-domain-filter" | "wr-domain-view" (+ data-filled).
 */

const key = (s) => String(s ?? '').trim().toLocaleLowerCase('tr')
/** Güncellemenin ilk anlamlı satırı (liste/başlık/alıntı işaretleri ve vurgu karakterleri atılmış) — satır özeti. */
const firstLine = (md) => String(md ?? '').split('\n')
  .map((l) => l.replace(/^\s*(?:#{1,6}\s+|>\s*|(?:[-*+]|\d+[.)])\s+(?:\[[ xX]\]\s+)?)/, '').replace(/[*_`~]/g, '').trim())
  .find(Boolean) || ''

/** Özet satırı: "6 alan · 4 güncellemeli · 2 güncellemesiz". */
function Summary({ channels }) {
  const t = useT()
  const s = domainSummary(channels)
  return (
    <p data-slot="wr-domain-summary" className="text-sm text-muted-foreground">
      <span className="font-semibold text-foreground">{t('wr.dc.count', s.total)}</span>
      <span aria-hidden="true"> · </span>{t('wr.dc.withUpdate', s.withUpdate)}
      {s.without > 0 && <><span aria-hidden="true"> · </span><span className="text-amber-700 dark:text-amber-400">{t('wr.dc.without', s.without)}</span></>}
    </p>
  )
}

function FilledBadge({ filled }) {
  const t = useT()
  return (
    <Badge variant="outline" className={cn('shrink-0 rounded-full', filled ? 'border-success/40 text-success' : 'text-muted-foreground')}>
      {filled ? t('wr.dc.updated') : t('wr.dc.noUpdate')}
    </Badge>
  )
}

/** Dar kap kararı: ölçülen kap < 640 px ya da (ölçüm yoksa) telefon. */
function useNarrow() {
  const phone = useIsMobile()
  const [width, ref] = useElementWidthState()
  return [width > 0 ? width < 640 : phone, ref]
}

/**
 * Düzenleyici. `channels` → `onChange(next)` (tüm dizi); `templateNames` takım kanal şablonu ("Şablondan tamamla");
 * `prevChannels` geçen haftanın kanalları (aynı ada göre "geçen haftanın notu"); `prevWeekLabel` etiket.
 */
export function DomainWorkEditor({ channels, onChange, reportId, templateNames = [], prevChannels = [], prevWeekLabel }) {
  const t = useT()
  const { showConfirm } = useDialog()
  const [narrow, setBox] = useNarrow()
  const list = useMemo(() => channels || [], [channels])
  const [openId, setOpenId] = useState(() => list[0]?.id ?? null)
  const [focusId, setFocusId] = useState(null)
  const [prevOpen, setPrevOpen] = useState({})
  const panelBase = useId()
  const dups = useMemo(() => duplicateChannelIndexes(list), [list])
  const missing = useMemo(() => {
    const have = new Set(list.map((c) => key(c.name)))
    return templateNames.filter((n) => !have.has(key(n)))
  }, [list, templateNames])
  const prevByName = useMemo(() => new Map((prevChannels || []).map((c) => [key(c.name), c.notes_md])), [prevChannels])

  const newRow = (name) => ({ id: 'c-' + Date.now() + '-' + Math.random().toString(36).slice(2, 6), name, notes_md: '' })
  const setAt = (idx, patchObj) => onChange(list.map((c, i) => (i === idx ? { ...c, ...patchObj } : c)))
  function addAt(pos) {
    const row = newRow(t('wr.newChannelName'))
    const next = [...list]
    next.splice(pos, 0, row)
    onChange(next)
    setOpenId(row.id); setFocusId(row.id)
  }
  function duplicate(idx) {
    const src = list[idx]
    const row = { ...src, id: newRow('').id, name: `${src.name} ${t('wr.dc.copySuffix')}` }
    const next = [...list]
    next.splice(idx + 1, 0, row)
    onChange(next)
    setOpenId(row.id); setFocusId(row.id)
  }
  function move(idx, dir) {
    const j = idx + dir
    if (j < 0 || j >= list.length) return
    const next = [...list]
    ;[next[idx], next[j]] = [next[j], next[idx]]
    onChange(next)
  }
  async function remove(idx) {
    const ch = list[idx]
    const ok = await showConfirm({
      title: t('wr.deleteChannel'),
      message: t('wr.confirmDeleteChannel', ch.name),
      variant: 'danger',
      confirmText: t('wr.deleteChannel'),
      cancelText: t('wr.cancel'),
    })
    if (!ok) return
    onChange(list.filter((_, i) => i !== idx))
    if (openId === ch.id) setOpenId(list[idx + 1]?.id ?? list[idx - 1]?.id ?? null)
  }
  function fillTemplate() {
    if (!missing.length) return
    const stamp = Date.now()
    const rows = missing.map((n, i) => ({ id: `c-${stamp}-${i}`, name: n, notes_md: '' }))
    onChange([...list, ...rows])
    setOpenId(rows[0].id)
  }
  const toggle = (id) => setOpenId((cur) => (cur === id ? null : id))

  const nameError = (ch, idx) => (!String(ch.name ?? '').trim() ? t('wr.dc.nameRequired') : dups.has(idx) ? t('wr.dc.nameDuplicate') : null)
  const nameInput = (ch, idx, className) => {
    const err = nameError(ch, idx)
    const errId = `${panelBase}-err-${idx}`
    return (
      <div className={cn('flex min-w-0 flex-col gap-1', className)}>
        <Input value={ch.name ?? ''} aria-label={t('wr.dc.nameFor', idx + 1)} aria-invalid={err ? true : undefined}
          aria-describedby={err ? errId : undefined} autoFocus={focusId === ch.id}
          onFocus={(e) => { if (focusId === ch.id) { e.target.select(); setFocusId(null) } }}
          onChange={(e) => setAt(idx, { name: e.target.value })}
          onKeyDown={(e) => { if (e.key === 'Enter' && !e.nativeEvent.isComposing) { e.preventDefault(); addAt(idx + 1) } }} />
        {err && <p id={errId} className="text-xs text-destructive">{err}</p>}
      </div>
    )
  }
  const menuItems = (ch, idx) => [
    { label: openId === ch.id ? t('wr.dc.hideUpdate') : t('wr.dc.editUpdate'), icon: <FilePenLine aria-hidden="true" />, onClick: () => toggle(ch.id) },
    ...(idx > 0 ? [{ label: t('wr.dc.moveUp'), icon: <ArrowUp aria-hidden="true" />, onClick: () => move(idx, -1) }] : []),
    ...(idx < list.length - 1 ? [{ label: t('wr.dc.moveDown'), icon: <ArrowDown aria-hidden="true" />, onClick: () => move(idx, 1) }] : []),
    { label: t('wr.dc.duplicate'), icon: <Copy aria-hidden="true" />, onClick: () => duplicate(idx) },
    { label: t('wr.deleteChannel'), icon: <Trash2 aria-hidden="true" />, danger: true, onClick: () => remove(idx) },
  ]
  const rowName = (ch, idx) => String(ch.name ?? '').trim() || t('wr.dc.rowN', idx + 1)
  const updateToggle = (ch, idx) => {
    const filled = !!String(ch.notes_md ?? '').trim()
    const open = openId === ch.id
    return (
      <Button type="button" variant="ghost" aria-expanded={open} aria-controls={`${panelBase}-p-${ch.id}`}
        aria-label={t(open ? 'wr.dc.hideUpdateFor' : 'wr.dc.editUpdateFor', rowName(ch, idx))}
        onClick={() => toggle(ch.id)}
        className="h-auto min-h-10 w-full min-w-0 justify-start gap-2 px-2 py-1.5 text-left font-normal whitespace-normal">
        <FilledBadge filled={filled} />
        <span className={cn('min-w-0 flex-1 truncate text-sm', !filled && 'text-muted-foreground italic')}>
          {filled ? firstLine(ch.notes_md) : t('wr.dc.addUpdateHint')}
        </span>
        <ChevronDown aria-hidden="true" className={cn('size-4 shrink-0 text-muted-foreground transition-transform motion-reduce:transition-none', open && 'rotate-180')} />
      </Button>
    )
  }
  const panel = (ch, idx) => {
    const prev = prevByName.get(key(ch.name))
    return (
      <div id={`${panelBase}-p-${ch.id}`} data-slot="wr-domain-panel" className="flex min-w-0 flex-col gap-2">
        <MdField value={ch.notes_md} editable reportId={reportId} label={t('wr.ed.notesFor', rowName(ch, idx))}
          onChange={(v) => setAt(idx, { notes_md: v })} height={200} />
        <PrevNoteToggle open={!!prevOpen[ch.id]} onToggle={() => setPrevOpen((o) => ({ ...o, [ch.id]: !o[ch.id] }))} note={prev} weekLabel={prevWeekLabel} />
      </div>
    )
  }

  const toolbar = (
    <div className="flex min-w-0 flex-col gap-2 sm:flex-row sm:flex-wrap sm:items-center sm:justify-between">
      <Summary channels={list} />
      <div className="flex flex-wrap items-center gap-2">
        {missing.length > 0 && (
          <Button type="button" variant="outline" size="sm" className="border-primary/40 text-primary pointer-coarse:h-10" onClick={fillTemplate}
            title={t('wr.fillChannelsTitle', missing.join(', '))}>
            <ListPlus aria-hidden="true" /> {t('wr.fillChannels', missing.length)}
          </Button>
        )}
        {list.length > 0 && (
          <Button type="button" size="sm" className="pointer-coarse:h-10" onClick={() => addAt(list.length)}>
            <Plus aria-hidden="true" /> {t('wr.addChannel')}
          </Button>
        )}
      </div>
    </div>
  )

  if (!list.length) {
    return (
      <div ref={setBox} className="min-w-0">
        <StatusBlock tone="neutral" icon={Layers} title={t('wr.dc.emptyTitle')} description={t('wr.dc.emptyDesc')} className="border border-dashed"
          actions={(
            <div className="flex flex-wrap justify-center gap-2">
              <Button type="button" className="pointer-coarse:h-10" onClick={() => addAt(0)}><Plus aria-hidden="true" /> {t('wr.dc.addFirst')}</Button>
              {missing.length > 0 && (
                <Button type="button" variant="outline" className="pointer-coarse:h-10" onClick={fillTemplate}><ListPlus aria-hidden="true" /> {t('wr.fillChannels', missing.length)}</Button>
              )}
            </div>
          )} />
      </div>
    )
  }

  return (
    <div ref={setBox} data-slot="wr-domain-editor" className="flex min-w-0 flex-col gap-3">
      {toolbar}
      {!narrow ? (
        <div className="min-w-0 overflow-hidden rounded-lg border">
          <Table data-slot="wr-domain-table" className="table-fixed text-sm">
            <TableHeader className="bg-muted/50">
              <TableRow className="hover:bg-transparent">
                <TableHead className="w-10 text-center font-semibold">#</TableHead>
                <TableHead className="w-[34%] font-semibold">{t('wr.dc.colDomain')}</TableHead>
                <TableHead className="font-semibold">{t('wr.dc.colUpdate')}</TableHead>
                <TableHead className="w-32 text-right"><span className="sr-only">{t('wr.actions')}</span></TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {list.map((ch, idx) => {
                const filled = !!String(ch.notes_md ?? '').trim()
                const open = openId === ch.id
                return [
                  <TableRow key={ch.id} data-slot="wr-domain-row" data-filled={filled || undefined} data-state={open ? 'selected' : undefined}
                    className="align-top hover:bg-transparent data-[state=selected]:bg-muted/40">
                    <TableCell className="pt-3.5 text-center text-xs font-semibold text-muted-foreground tabular-nums">{idx + 1}</TableCell>
                    <TableCell className="whitespace-normal">{nameInput(ch, idx)}</TableCell>
                    <TableCell className="whitespace-normal">{updateToggle(ch, idx)}</TableCell>
                    <TableCell className="text-right whitespace-nowrap">
                      <span className="inline-flex items-center gap-0.5">
                        <Button type="button" variant="ghost" size="icon-sm" className="pointer-coarse:size-10" disabled={idx === 0} onClick={() => move(idx, -1)}
                          aria-label={t('wr.dc.moveUpFor', rowName(ch, idx))} title={t('wr.dc.moveUp')}><ArrowUp aria-hidden="true" /></Button>
                        <Button type="button" variant="ghost" size="icon-sm" className="pointer-coarse:size-10" disabled={idx === list.length - 1} onClick={() => move(idx, 1)}
                          aria-label={t('wr.dc.moveDownFor', rowName(ch, idx))} title={t('wr.dc.moveDown')}><ArrowDown aria-hidden="true" /></Button>
                        <KebabMenu label={t('wr.actions')} rowLabel={rowName(ch, idx)} items={menuItems(ch, idx)} />
                      </span>
                    </TableCell>
                  </TableRow>,
                  open && (
                    <TableRow key={`${ch.id}-panel`} className="hover:bg-transparent">
                      <TableCell colSpan={4} className="bg-muted/20 px-3 pt-2 pb-3 whitespace-normal">{panel(ch, idx)}</TableCell>
                    </TableRow>
                  ),
                ]
              })}
            </TableBody>
          </Table>
        </div>
      ) : (
        <ul className="flex list-none flex-col gap-2 p-0">
          {list.map((ch, idx) => {
            const filled = !!String(ch.notes_md ?? '').trim()
            return (
              <li key={ch.id} data-slot="wr-domain-card" data-filled={filled || undefined}
                className="flex min-w-0 flex-col gap-2 rounded-lg border bg-card p-2.5">
                <div className="flex min-w-0 items-start gap-2">
                  <span aria-hidden="true" className="mt-2.5 inline-flex size-6 shrink-0 items-center justify-center rounded-full bg-muted text-xs font-semibold tabular-nums">{idx + 1}</span>
                  {nameInput(ch, idx, 'flex-1')}
                  <KebabMenu label={t('wr.actions')} rowLabel={rowName(ch, idx)} items={menuItems(ch, idx)} />
                </div>
                {updateToggle(ch, idx)}
                {openId === ch.id && panel(ch, idx)}
              </li>
            )
          })}
          <li>
            <Button type="button" variant="outline" className="h-10 w-full border-dashed" onClick={() => addAt(list.length)}>
              <Plus aria-hidden="true" /> {t('wr.addChannel')}
            </Button>
          </li>
        </ul>
      )}
    </div>
  )
}

/** Okuma / onay görünümü: özet, çok alanda süzgeç çipleri, tablo (geniş) / kart (dar); güncellemesiz alan soluk. */
export function DomainWorkView({ channels }) {
  const t = useT()
  const [narrow, setBox] = useNarrow()
  const [filter, setFilter] = useState('all')
  const list = channels || []
  const s = domainSummary(list)
  if (!list.length) {
    return <p ref={setBox} className="text-sm text-muted-foreground">{t('wr.dc.emptyRead')}</p>
  }
  const shown = list.map((ch, idx) => ({ ch, idx, filled: !!String(ch.notes_md ?? '').trim() }))
    .filter((r) => filter === 'all' || (filter === 'with' ? r.filled : !r.filled))
  return (
    <div ref={setBox} data-slot="wr-domain-view" className="flex min-w-0 flex-col gap-3">
      <div className="flex min-w-0 flex-wrap items-center justify-between gap-2">
        <Summary channels={list} />
        {list.length > 4 && (
          <ToggleGroup type="single" value={filter} onValueChange={(v) => v && setFilter(v)} spacing={1.5}
            aria-label={t('wr.dc.filter')} data-slot="wr-domain-filter" className="flex flex-wrap print:hidden">
            {[['all', t('wr.dc.fAll', s.total)], ['with', t('wr.dc.fWith', s.withUpdate)], ['without', t('wr.dc.fWithout', s.without)]].map(([v, label]) => (
              <ToggleGroupItem key={v} value={v} data-chip={v}
                className="h-8 flex-none rounded-full border px-3 text-xs font-medium data-[state=on]:border-primary data-[state=on]:bg-primary data-[state=on]:text-primary-foreground pointer-coarse:h-10">
                {label}
              </ToggleGroupItem>
            ))}
          </ToggleGroup>
        )}
      </div>
      {!narrow ? (
        <div className="min-w-0 overflow-hidden rounded-lg border">
          <Table data-slot="wr-domain-table" className="table-fixed text-sm">
            <TableHeader className="bg-muted/50">
              <TableRow className="hover:bg-transparent">
                <TableHead className="w-10 text-center font-semibold">#</TableHead>
                <TableHead className="w-[28%] font-semibold">{t('wr.dc.colDomain')}</TableHead>
                <TableHead className="font-semibold">{t('wr.dc.colUpdate')}</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {shown.map(({ ch, idx, filled }) => (
                <TableRow key={ch.id} data-slot="wr-domain-row" data-filled={filled || undefined} className="align-top hover:bg-transparent">
                  <TableCell className="text-center text-xs font-semibold text-muted-foreground tabular-nums">{idx + 1}</TableCell>
                  <TableCell className="whitespace-normal">
                    <span className="flex min-w-0 flex-col items-start gap-1">
                      <span className="font-semibold [overflow-wrap:anywhere]">{ch.name}</span>
                      <FilledBadge filled={filled} />
                    </span>
                  </TableCell>
                  <TableCell className="whitespace-normal">
                    <MarkdownView value={ch.notes_md} empty={t('wr.dc.noUpdateThisWeek')} className={cn(!filled && 'italic')} />
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </div>
      ) : (
        <ul className="flex list-none flex-col gap-2 p-0">
          {shown.map(({ ch, idx, filled }) => (
            <li key={ch.id} data-slot="wr-domain-card" data-filled={filled || undefined} className="min-w-0 rounded-lg border bg-card p-3">
              <div className="mb-1.5 flex min-w-0 items-start justify-between gap-2">
                <span className="min-w-0 font-semibold [overflow-wrap:anywhere]"><span className="text-muted-foreground tabular-nums">{idx + 1}.</span> {ch.name}</span>
                <FilledBadge filled={filled} />
              </div>
              <MarkdownView value={ch.notes_md} empty={t('wr.dc.noUpdateThisWeek')} className={cn('text-sm', !filled && 'italic')} />
            </li>
          ))}
        </ul>
      )}
      {shown.length === 0 && <p className="text-sm text-muted-foreground">{t('wr.dc.noneInFilter')}</p>}
    </div>
  )
}
