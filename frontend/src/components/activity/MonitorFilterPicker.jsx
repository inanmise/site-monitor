import { useEffect, useRef, useState } from 'react'
import { MonitorDot, ChevronDown } from 'lucide-react'
import { api } from '../../api/client'
import { useT } from '../../i18n/index.jsx'
import { Button } from '@/components/shadcn/button'
import { Popover, PopoverContent, PopoverTrigger } from '@/components/shadcn/popover'
import { Command, CommandInput, CommandItem, CommandList } from '@/components/shadcn/command'
import { cn } from '@/lib/utils'
import { Spinner } from '../ui/Progress.jsx'
import { TypeBadge } from './ActivityParts.jsx'
import { formatMonitorRef } from './activityModel.js'

/** Genel arama (komut paleti ucu) türü → aktivite izleme türü. Sertifika vuruşu envanter kimliği taşımadığından yok. */
const KIND_TO_TYPE = {
  http: 'HTTP', ping: 'PING', port: 'PORT', dns: 'DNS', keyword: 'KEYWORD', page: 'PAGE', pagespeed: 'PAGESPEED',
  scripted: 'SCRIPTED', dbquery: 'DBQUERY', domain: 'DOMAIN',
}
export const MIN_QUERY = 2
const DEBOUNCE_MS = 300
const MAX_Q = 120

/** Arama yanıtı → seçenekler (yalnız izleme türleri; kimliği sayı olmayan atlanır). */
export function monitorOptions(res) {
  const rows = Array.isArray(res?.data) ? res.data : []
  const out = []
  for (const h of rows) {
    const type = KIND_TO_TYPE[h?.kind]
    const id = Number(h?.id)
    if (!type || !Number.isSafeInteger(id) || id <= 0) continue
    out.push({ ref: formatMonitorRef(type, id), type, id, label: h.label || h.sub || '', sub: h.sub || '', team: h.team_name || '' })
  }
  return out
}

/**
 * İzleme süzgeci seçici (2026-11-05, E) — shadcn Combobox deseni (Popover + Command). Ad / hedef / grup / etiketle arar
 * (genel arama ucu, görüş kapsamı sunucuda); seçilen izleme `TYPE:id` olarak döner (`onChange(ref, label)`). Arama en
 * az 2 karakter; okunamazsa "okunamadı", asla "sonuç yok". Uzun adlar sarar.
 * Test kancaları: `data-slot="act-monitor-picker"`, sonuçlar `act-monitor-option` (+ `data-ref`).
 */
export default function MonitorFilterPicker({ value, label, onChange, className }) {
  const t = useT()
  const [open, setOpen] = useState(false)
  const [q, setQ] = useState('')
  const [state, setState] = useState({ status: 'idle', items: [] })
  const seq = useRef(0)

  useEffect(() => {
    const query = q.trim().slice(0, MAX_Q)
    const my = ++seq.current
    if (!open || query.length < MIN_QUERY) { setState({ status: 'idle', items: [] }); return undefined }
    setState((s) => ({ status: 'loading', items: s.items }))
    const timer = setTimeout(() => {
      api.search(query)
        .then((res) => {
          if (my !== seq.current) return
          setState(res?.success ? { status: 'ok', items: monitorOptions(res) } : { status: 'error', items: [] })
        })
        .catch(() => { if (my === seq.current) setState({ status: 'error', items: [] }) })
    }, DEBOUNCE_MS)
    return () => clearTimeout(timer)
  }, [q, open])

  const pick = (o) => {
    onChange?.(o.ref, o.label)
    setOpen(false)
    setQ('')
  }

  return (
    <Popover open={open} onOpenChange={(next) => { setOpen(next); if (!next) setQ('') }}>
      <PopoverTrigger asChild>
        <Button type="button" variant="outline" data-slot="act-monitor-picker" data-active={value ? 'true' : undefined}
          className={cn('h-auto min-h-9 justify-between gap-2 font-normal whitespace-normal pointer-coarse:min-h-10', !value && 'border-dashed', className)}
          aria-label={value ? t('act.flt.monitorSelected', label || value) : t('act.flt.monitor')}>
          <span className="flex min-w-0 items-center gap-2 text-left">
            <MonitorDot aria-hidden="true" className="size-4 shrink-0" />
            <span className="min-w-0 [overflow-wrap:anywhere]">{value ? (label || value) : t('act.flt.monitor')}</span>
          </span>
          <ChevronDown aria-hidden="true" className="size-4 shrink-0 opacity-60" />
        </Button>
      </PopoverTrigger>
      <PopoverContent align="start" className="z-(--z-menu) w-80 max-w-[calc(100vw-2rem)] p-0">
        <Command shouldFilter={false} loop>
          <CommandInput placeholder={t('act.flt.monitorPh')} value={q} onValueChange={setQ} maxLength={MAX_Q} />
          <CommandList>
            {state.status === 'idle' && (
              <p className="m-0 px-3 py-4 text-sm text-muted-foreground">{t('act.flt.monitorMin', MIN_QUERY)}</p>
            )}
            {state.status === 'loading' && state.items.length === 0 && (
              <div className="flex items-center gap-2 px-3 py-4 text-sm text-muted-foreground"><Spinner size={14} decorative /> {t('act.loading')}</div>
            )}
            {state.status === 'error' && (
              <p role="alert" className="m-0 px-3 py-4 text-sm text-destructive">{t('act.flt.monitorFailed')}</p>
            )}
            {state.status === 'ok' && state.items.length === 0 && (
              <p className="m-0 px-3 py-4 text-sm text-muted-foreground">{t('act.flt.monitorNone')}</p>
            )}
            {state.items.map((o) => (
              <CommandItem key={o.ref} value={o.ref} data-slot="act-monitor-option" data-ref={o.ref} onSelect={() => pick(o)}
                className="items-start gap-2 pointer-coarse:min-h-10">
                <TypeBadge type={o.type} />
                <span className="flex min-w-0 flex-col">
                  <span className="font-medium [overflow-wrap:anywhere]">{o.label}</span>
                  {(o.sub || o.team) && (
                    <span className="text-xs break-all text-muted-foreground">{[o.sub, o.team].filter(Boolean).join(' · ')}</span>
                  )}
                </span>
              </CommandItem>
            ))}
          </CommandList>
        </Command>
      </PopoverContent>
    </Popover>
  )
}
