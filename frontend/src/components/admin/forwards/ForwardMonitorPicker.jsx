import { useId, useMemo, useState } from 'react'
import { RefreshCw, Search, X } from 'lucide-react'
import AlertBanner from '../../ui/AlertBanner.jsx'
import StatusBlock from '../../ui/StatusBlock.jsx'
import { Badge } from '@/components/shadcn/badge'
import { Button } from '@/components/shadcn/button'
import { Checkbox } from '@/components/shadcn/checkbox'
import { Skeleton } from '@/components/shadcn/skeleton'
import { InputGroup, InputGroupAddon, InputGroupInput } from '@/components/shadcn/input-group'
import { NativeSelect, NativeSelectOption } from '@/components/shadcn/native-select'
import { cn } from '@/lib/utils'
import { WRAP_BADGE, typeIcon } from './ForwardParts.jsx'
import { FORWARD_TYPES, filterMonitorOptions, monitorKey, monitorLabel, typeLabel } from './alertForwardModel.js'

/** Seçim çipinin kaldır düğmesi: görünür 20 px, dokunmatikte görünmez ::after ile 40 px vuruş alanı. */
const CHIP_REMOVE = 'relative size-5 rounded-full text-muted-foreground hover:text-destructive pointer-coarse:after:absolute pointer-coarse:after:-inset-2.5 pointer-coarse:after:content-[\'\']'

/**
 * İletim kapsamı — "Seçilen izlemeler" seçicisi (sahip takımın izlemeleri, `GET /alert-forwards/monitors`). Tür süzgeci +
 * Türkçe katlamalı arama + onay kutulu liste; seçilenler kaldırılabilir çipler, sayaç "N / sınır". Sınıra varınca seçili
 * olmayan satırlar kilitlenir. Listede artık bulunmayan (silinmiş) bir izlemenin öğesi düzenlemede çip olarak kalır ve
 * kaldırılabilir. Okunamayan liste "okunamadı" + yeniden dene — asla "izleme yok".
 *
 * Props: `status` (loading|ready|error), `items` (normalizeMonitorOptions), `message`, `onRetry`, `value` (seçilenler
 * `[{type,id,name,target,…}]`), `onChange(next)`, `limit`, `t`, `invalid`, `describedBy`.
 */
export default function ForwardMonitorPicker({ status, items = [], message, onRetry, value = [], onChange, limit = 200, t, invalid, describedBy }) {
  const [type, setType] = useState('')
  const [q, setQ] = useState('')
  const base = useId()
  const selected = useMemo(() => new Set(value.map(monitorKey)), [value])
  const counts = useMemo(() => {
    const m = new Map()
    for (const it of items) m.set(it.type, (m.get(it.type) || 0) + 1)
    return m
  }, [items])
  const types = FORWARD_TYPES.filter((ty) => counts.has(ty))
  const shown = useMemo(() => filterMonitorOptions(items, { type, q }), [items, type, q])
  const full = value.length >= limit

  const toggle = (m, on) => {
    const k = monitorKey(m)
    if (on) { if (!selected.has(k) && !full) onChange([...value, m]) } else onChange(value.filter((x) => monitorKey(x) !== k))
  }
  const shownKeys = shown.map(monitorKey)
  const allShownPicked = shown.length > 0 && shownKeys.every((k) => selected.has(k))
  const pickShown = () => {
    if (allShownPicked) { const drop = new Set(shownKeys); onChange(value.filter((x) => !drop.has(monitorKey(x)))); return }
    const next = [...value]
    for (const m of shown) {
      if (next.length >= limit) break
      if (!selected.has(monitorKey(m))) next.push(m)
    }
    onChange(next)
  }

  if (status === 'loading') {
    return (
      <div className="flex flex-col gap-2" data-slot="fwd-monitor-picker" data-status="loading" role="status" aria-label={t('fwd.mon.loading')}>
        <Skeleton className="h-10 w-full" />
        {[0, 1, 2].map((i) => <Skeleton key={i} className="h-10 w-full" />)}
      </div>
    )
  }
  if (status === 'error') {
    return (
      <div data-slot="fwd-monitor-picker" data-status="error" className="min-w-0">
        <AlertBanner tone="danger" className="mb-0"
          actions={<Button type="button" variant="secondary" size="sm" className="gap-1.5 pointer-coarse:h-10" onClick={onRetry}><RefreshCw aria-hidden="true" />{t('fwd.retry')}</Button>}>
          {message || t('fwd.mon.loadFailed')}
        </AlertBanner>
        <SelectedChips value={value} items={items} t={t} onRemove={(m) => toggle(m, false)} />
      </div>
    )
  }

  return (
    <div className="flex min-w-0 flex-col gap-2" data-slot="fwd-monitor-picker" data-status="ready">
      {items.length === 0 ? (
        <StatusBlock tone="neutral" description={t('fwd.mon.none')} className="my-0 py-4 md:py-4" />
      ) : (
        <>
          <div className="flex min-w-0 flex-wrap items-center gap-2">
            <NativeSelect value={type} onChange={(e) => setType(e.target.value)} aria-label={t('fwd.mon.typeFilter')}
              className="h-10 min-w-0 max-w-full" data-slot="fwd-monitor-type">
              <NativeSelectOption value="">{t('fwd.mon.allTypes', items.length)}</NativeSelectOption>
              {types.map((ty) => <NativeSelectOption key={ty} value={ty}>{`${typeLabel(ty, t)} (${counts.get(ty)})`}</NativeSelectOption>)}
            </NativeSelect>
            <InputGroup className="h-10 min-w-0 flex-[1_1_12rem]">
              <InputGroupInput value={q} onChange={(e) => setQ(e.target.value)} placeholder={t('fwd.mon.search')} aria-label={t('fwd.mon.search')}
                data-slot="fwd-monitor-search" />
              <InputGroupAddon><Search aria-hidden="true" /></InputGroupAddon>
            </InputGroup>
          </div>
          <div className="flex min-w-0 flex-wrap items-center justify-between gap-2">
            <span className={cn('text-xs font-semibold tabular-nums', full ? 'text-amber-700 dark:text-amber-300' : 'text-muted-foreground')}
              data-slot="fwd-monitor-count" aria-live="polite">
              {t('fwd.mon.count', value.length, limit)}
            </span>
            <Button type="button" variant="outline" size="sm" className="min-h-8 pointer-coarse:min-h-10" onClick={pickShown}
              disabled={shown.length === 0 || (!allShownPicked && full)} data-slot="fwd-monitor-pick-shown">
              {allShownPicked ? t('fwd.mon.unpickShown') : t('fwd.mon.pickShown', shown.length)}
            </Button>
          </div>
          <ul className="m-0 flex max-h-72 min-w-0 list-none flex-col gap-0.5 overflow-y-auto rounded-lg border p-1" data-slot="fwd-monitor-list"
            aria-describedby={describedBy} aria-invalid={invalid || undefined}>
            {shown.map((m, i) => {
              const k = monitorKey(m)
              const on = selected.has(k)
              const cid = `${base}-${i}`
              const Icon = typeIcon(m.type)
              return (
                <li key={k} className="min-w-0">
                  <label htmlFor={cid} data-slot="fwd-monitor-item" data-key={k}
                    className={cn('flex min-h-10 min-w-0 cursor-pointer items-start gap-2.5 rounded-md px-2 py-2 hover:bg-muted',
                      on && 'bg-primary/5', !on && full && 'cursor-not-allowed opacity-60')}>
                    <Checkbox id={cid} checked={on} disabled={!on && full} onCheckedChange={(v) => toggle(m, v === true)} className="mt-0.5" />
                    <span className="flex min-w-0 flex-1 flex-col gap-0.5">
                      <span className="flex min-w-0 flex-wrap items-center gap-1.5 text-sm font-medium">
                        <Icon aria-hidden="true" className="size-3.5 shrink-0 text-muted-foreground" />
                        <span className="min-w-0 [overflow-wrap:anywhere]">{m.name || m.target || k}</span>
                        {m.active === false && <Badge variant="outline" className={cn(WRAP_BADGE, 'font-normal text-muted-foreground')}>{t('fwd.monitorPaused')}</Badge>}
                      </span>
                      <span className="text-xs text-muted-foreground [overflow-wrap:anywhere]">
                        {typeLabel(m.type, t)}{m.target && m.target !== m.name ? ` · ${m.target}` : ''}
                      </span>
                    </span>
                  </label>
                </li>
              )
            })}
            {shown.length === 0 && <li className="px-2 py-3 text-sm text-muted-foreground">{t('fwd.mon.noMatch')}</li>}
          </ul>
          {full && <p className="m-0 text-xs text-amber-700 dark:text-amber-300" data-slot="fwd-monitor-full">{t('fwd.mon.full', limit)}</p>}
        </>
      )}
      <SelectedChips value={value} items={items} t={t} onRemove={(m) => toggle(m, false)} />
    </div>
  )
}

/** Seçilenler — kaldırılabilir çipler (listede olmayan / silinmiş izleme de görünür). */
function SelectedChips({ value, items, t, onRemove }) {
  const byKey = new Map(items.map((m) => [monitorKey(m), m]))
  if (value.length === 0) return <p className="m-0 text-xs text-muted-foreground" data-slot="fwd-monitor-none-picked">{t('fwd.mon.nothingPicked')}</p>
  return (
    <ul className="m-0 flex min-w-0 list-none flex-wrap gap-1.5 p-0" data-slot="fwd-monitor-chips">
      {value.map((v) => {
        const k = monitorKey(v)
        const m = byKey.get(k) || v
        const Icon = typeIcon(m.type)
        // Listede bulunan izleme bilinir; listede yoksa kayıtlı öğenin durumu (silinmiş / dizin okunamadı)
        const name = monitorLabel(m, t)
        return (
          <li key={k} className="min-w-0 max-w-full">
            <Badge variant="outline" data-slot="fwd-monitor-chip" data-key={k}
              className={cn(WRAP_BADGE, 'gap-1 py-1 pr-1 pl-2 font-normal', m.missing !== false && !byKey.has(k) && 'text-muted-foreground')}>
              <Icon aria-hidden="true" className="size-3.5 shrink-0" />
              <span className="min-w-0 [overflow-wrap:anywhere]">{name}</span>
              <Button type="button" variant="ghost" size="icon-xs" className={CHIP_REMOVE} aria-label={t('fwd.mon.remove', name)}
                onClick={() => onRemove(m)}>
                <X aria-hidden="true" className="size-3" />
              </Button>
            </Badge>
          </li>
        )
      })}
    </ul>
  )
}
