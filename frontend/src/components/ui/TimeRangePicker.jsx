import { useState } from 'react'
import { Clock, ChevronDown } from 'lucide-react'
import { useT } from '../../i18n/index.jsx'
import { Button } from '@/components/shadcn/button'
import { Input } from '@/components/shadcn/input'
import { Popover, PopoverTrigger } from '@/components/shadcn/popover'
import { DatePopoverContent, DateTimePopover } from './DatePickerParts.jsx'

/** Hızlı aralıklar — dk cinsinden (Grafana benzeri). */
export const QUICK_RANGES = [
  { key: '5m',  minutes: 5 },
  { key: '15m', minutes: 15 },
  { key: '30m', minutes: 30 },
  { key: '1h',  minutes: 60 },
  { key: '3h',  minutes: 180 },
  { key: '6h',  minutes: 360 },
  { key: '12h', minutes: 720 },
  { key: '24h', minutes: 1440 },
  { key: '2d',  minutes: 2880 },
  { key: '7d',  minutes: 10080 },
]

const pad = (n) => String(n).padStart(2, '0')
/** Date → yerel "yyyy-MM-ddTHH:mm" (eski datetime-local değer biçimi; sunucuya gitmeden resolveRange UTC'ye çevirir). */
const toLocalInput = (d) => `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`
/** Yerel "yyyy-MM-ddTHH:mm" → Date (saat dilimi ekisiz ISO yerel saat olarak ayrıştırılır). */
const fromLocalInput = (v) => { const d = v ? new Date(v) : null; return d && !isNaN(d.getTime()) ? d : null }

/**
 * Grafana benzeri zaman-aralığı seçici. Tetik (saat ikonu + etiket) → shadcn Popover, iki sütun:
 * SOL = mutlak (Başlangıç/Bitiş — her biri Calendar + saat — ve Uygula), SAĞ = hızlı aralıklar (arama + liste).
 * Popover body'ye portal'lanır → overflow'lu ataya takılıp kırpılmaz; telefonda sütunlar alt alta.
 * value = { type:'rel', minutes, key } | { type:'abs', from, to } (from/to yerel "yyyy-MM-ddTHH:mm"); onChange(descriptor).
 */
export default function TimeRangePicker({ value, onChange }) {
  const t = useT()
  const [open, setOpen] = useState(false)
  const [search, setSearch] = useState('')
  const [from, setFrom] = useState(() => toLocalInput(new Date(Date.now() - 3600_000)))
  const [to, setTo] = useState(() => toLocalInput(new Date()))

  const label = value?.type === 'abs'
    ? `${value.from?.replace('T', ' ')} → ${value.to?.replace('T', ' ')}`
    : t('range.' + (value?.key || '1h'))

  const pickQuick = (r) => { onChange({ type: 'rel', minutes: r.minutes, key: r.key }); setOpen(false) }
  const applyAbs = () => { if (from && to) { onChange({ type: 'abs', from, to }); setOpen(false) } }

  const filtered = QUICK_RANGES.filter(r =>
    t('range.' + r.key).toLowerCase().includes(search.trim().toLowerCase()))

  return (
    <Popover open={open} onOpenChange={setOpen}>
      <PopoverTrigger asChild>
        <Button type="button" variant="outline" data-slot="time-range-trigger"
          className="group/trp h-9 max-w-full min-w-0 gap-2 px-3 font-normal has-[>svg]:px-3">
          <Clock aria-hidden="true" />
          <span className="min-w-0 truncate font-semibold">{label}</span>
          <ChevronDown aria-hidden="true"
            className="text-muted-foreground transition-transform duration-200 group-data-[state=open]/trp:rotate-180 motion-reduce:transition-none" />
        </Button>
      </PopoverTrigger>
      <DatePopoverContent className="w-[calc(100vw-1rem)] sm:w-[480px]">
        <div className="flex flex-col sm:flex-row">
          <div className="flex min-w-0 flex-1 flex-col gap-2.5 border-b p-3.5 sm:border-r sm:border-b-0">
            <div className="text-sm font-bold">{t('range.absolute')}</div>
            {/* Alan etiketi tetiğin İÇİNDE (erişilebilir ad "Başlangıç 26.09.2026 10:00" olur). */}
            <DateTimePopover label={t('range.from')} value={fromLocalInput(from)} onChange={(d) => setFrom(toLocalInput(d))} />
            <DateTimePopover label={t('range.to')} value={fromLocalInput(to)} onChange={(d) => setTo(toLocalInput(d))} />
            <Button type="button" size="sm" className="mt-0.5 w-full" onClick={applyAbs}>
              {t('range.apply')}
            </Button>
          </div>
          <div className="flex min-w-0 flex-1 flex-col gap-2 p-3.5">
            <Input placeholder={t('range.search')} aria-label={t('range.search')} value={search}
              onChange={e => setSearch(e.target.value)} />
            <div className="flex max-h-[270px] flex-col gap-0.5 overflow-y-auto">
              {filtered.map(r => {
                const active = value?.type === 'rel' && value.key === r.key
                return (
                  <Button key={r.key} type="button" size="sm" variant={active ? 'default' : 'ghost'}
                    aria-pressed={active} className="justify-start font-normal data-[variant=default]:font-semibold"
                    onClick={() => pickQuick(r)}>{t('range.' + r.key)}</Button>
                )
              })}
              {filtered.length === 0 && <div className="px-2.5 py-2 text-sm text-muted-foreground">—</div>}
            </div>
          </div>
        </div>
      </DatePopoverContent>
    </Popover>
  )
}

/** Descriptor → gerçek { from, to } UTC ISO (Z'siz). rel: now bazlı (canlı), abs: yerel→UTC. */
export function resolveRange(value) {
  if (value?.type === 'abs' && value.from && value.to) {
    return { from: new Date(value.from).toISOString().slice(0, 19), to: new Date(value.to).toISOString().slice(0, 19) }
  }
  const minutes = value?.minutes || 60
  const now = Date.now()
  return {
    from: new Date(now - minutes * 60000).toISOString().slice(0, 19),
    to: new Date(now).toISOString().slice(0, 19),
  }
}
