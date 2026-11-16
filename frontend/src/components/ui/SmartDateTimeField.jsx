import { useEffect, useId, useMemo, useRef, useState } from 'react'
import { CalendarClock, CalendarDays, X } from 'lucide-react'
import { useT } from '../../i18n/index.jsx'
import { dateLocale } from '../../i18n/dateLocale.js'
import { Button } from '@/components/shadcn/button'
import { Calendar } from '@/components/shadcn/calendar'
import { InputGroup, InputGroupAddon, InputGroupButton, InputGroupInput } from '@/components/shadcn/input-group'
import { Popover, PopoverContent, PopoverTrigger } from '@/components/shadcn/popover'
import { ScrollArea } from '@/components/shadcn/scroll-area'
import { ToggleGroup, ToggleGroupItem } from '@/components/shadcn/toggle-group'
import { cn } from '@/lib/utils'
import { useCalendarProps } from './DatePickerParts.jsx'
import { formatDuration } from '../../utils/incidentMeta.js'
import {
  FUTURE_SLACK_MS, MAX_TYPED, floorMinute, formatLocal, hourOptions, minuteOptions, parseIso, parseTyped, presetsFor, stepValue, toIso,
} from '../../utils/smartDateTime.js'

const pad = (n) => String(n).padStart(2, '0')

/**
 * Yazılabilir tarih + saat alanı (2026-11-17, kullanıcı isteği: Olay & Hata Geçmişi'nde yeni olay eklerken tarih girişi çok
 * zordu). `ui/DateTimeField` ile aynı değer sözleşmesi (UTC ISO, bölge eki yok); ondan farkı:
 *   • Alan YAZILIR: "16.11 14:30", "dün 09:00", "1430", "şimdi" … (`utils/smartDateTime.parseTyped`). Gün VE saat yazılınca
 *     değer anında kaydedilir; yalnız gün / yalnız saat odak çıkınca (ya da Enter) kaydedilir. Okunamayan metin önceki değeri
 *     SESSİZCE değiştirmez: önceki değer korunur ve alanın altında ne yazıldığı + örnekler söylenir.
 *   • ↑ / ↓ 5 dk, Shift ile 1 saat adımlar.
 *   • Hızlı seçim çipleri: "Şimdi", "15 dk önce", "1 sa önce", "Dün bu saatte" — `anchor` verilirse (tespit / çözüm zamanı)
 *     "+5 dk … +2 sa" o ana göre. Gelecekteki ya da `min` öncesindeki an seçilemez (çip pasif, nedeni `title`'da).
 *   • Takvim düğmesi: ay / yıl açılır listeli takvim + saat ve dakika listeleri (5 dk adım; seçili dakika adımda değilse o da
 *     listededir) + "Şimdi" / "Tamam".
 *   • Alanın altında bağlam: "2 sa 15 dk önce · pazartesi" ya da "Oluş Zamanı zamanından 25 dk sonra"; gelecek / sıra hatası
 *     uyarı renginde (kaydı engellemez — sıra kuralını form denetler).
 * `id` / `describedBy` / `invalid` `ui/Field`'dan gelir (etiket ↔ yazılabilir alan bağı).
 */
export default function SmartDateTimeField({
  id, value, onChange, disabled, clearable, min, anchor, anchorLabel, invalid, describedBy, presets = true,
}) {
  const t = useT()
  const cal = useCalendarProps()
  const ownId = useId()
  const problemId = `${ownId}-problem`
  const contextId = `${ownId}-context`
  const selected = parseIso(value)
  const minDate = parseIso(min)
  const anchorDate = parseIso(anchor)

  const [text, setText] = useState(() => formatLocal(selected))
  const [problem, setProblem] = useState(null)   // okunamayan metin (ham) — önceki değer korundu
  const [open, setOpen] = useState(false)
  const focusedRef = useRef(false)
  const sentRef = useRef(value || '')

  // Dışarıdan gelen değer (çip, takvim, form sıfırlama) metni günceller; yazarken kendi canlı kaydımız metni ezmez.
  useEffect(() => {
    const v = value || ''
    if (focusedRef.current && v === sentRef.current) return
    sentRef.current = v
    setText(formatLocal(parseIso(v)))
  }, [value])

  function commit(d) {
    const iso = d ? toIso(d) : ''
    sentRef.current = iso
    setProblem(null)
    if (iso !== (value || '')) onChange(iso)
  }

  function settle(raw) {
    const r = parseTyped(raw, { base: selected })
    if (r.empty) { commit(null); setText(''); return }
    if (r.date) { commit(r.date); setText(formatLocal(r.date)); return }
    setProblem(String(raw).slice(0, MAX_TYPED))
    setText(formatLocal(selected))
  }

  function onType(e) {
    const raw = e.target.value
    setText(raw)
    setProblem(null)
    const r = parseTyped(raw, { base: selected })
    if (r.date && r.complete) commit(r.date)
  }

  function onKeyDown(e) {
    if (e.key === 'Enter' && !e.ctrlKey && !e.metaKey) {
      e.preventDefault()
      settle(e.currentTarget.value)
      return
    }
    if ((e.key === 'ArrowUp' || e.key === 'ArrowDown') && !e.altKey) {
      const r = parseTyped(e.currentTarget.value, { base: selected })
      const next = stepValue(r.date || selected, e.key, e.shiftKey)
      if (!next) return
      e.preventDefault()
      commit(next)
      setText(formatLocal(next))
    }
  }

  function pickDay(day) {
    if (!day) return
    const d = new Date(day)
    if (selected) d.setHours(selected.getHours(), selected.getMinutes(), 0, 0)
    else { const now = new Date(); d.setHours(now.getHours(), now.getMinutes(), 0, 0) }
    commit(d)
    setText(formatLocal(d))
  }
  function pickPart(part, n) {
    const d = selected ? new Date(selected) : floorMinute(new Date())
    if (part === 'h') d.setHours(n); else d.setMinutes(n)
    d.setSeconds(0, 0)
    commit(d)
    setText(formatLocal(d))
  }
  function pickDate(d) {
    commit(d)
    setText(formatLocal(d))
  }

  const now = new Date()
  const presetItems = useMemo(() => (presets ? presetsFor({ anchor: anchorDate, min: minDate, now }) : []),
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [presets, anchor, min, Math.floor(now.getTime() / 60_000)])

  const context = contextText(t, selected, anchorDate, anchorLabel, now)
  const ids = [describedBy, problem ? problemId : null, context ? contextId : null].filter(Boolean).join(' ') || undefined
  const clearShown = !!(clearable && value && !disabled)

  return (
    <div data-slot="smart-datetime" className="flex min-w-0 flex-col gap-1.5">
      <InputGroup data-disabled={disabled || undefined}>
        <InputGroupAddon><CalendarClock aria-hidden="true" /></InputGroupAddon>
        <InputGroupInput id={id} value={text} disabled={disabled} autoComplete="off" spellCheck={false} inputMode="text"
          maxLength={MAX_TYPED} placeholder={t('sdt.placeholder')} aria-invalid={invalid || !!problem || undefined}
          aria-describedby={ids} data-slot="smart-datetime-input"
          onFocus={() => { focusedRef.current = true }}
          onBlur={(e) => { focusedRef.current = false; settle(e.currentTarget.value) }}
          onChange={onType} onKeyDown={onKeyDown} />
        {/* Negatif kenar payı yok: 40 px dokunmatik düğmelerle kap grubun dışına 5 px taşıyordu (telefonda yatay kaydırma) */}
        <InputGroupAddon align="inline-end" className="gap-0.5 pr-1 has-[>button]:mr-0">
          {clearShown && (
            <InputGroupButton size="icon-xs" aria-label={t('sdt.clear')} data-slot="smart-datetime-clear"
              className="pointer-coarse:size-10" onClick={() => { commit(null); setText('') }}>
              <X aria-hidden="true" />
            </InputGroupButton>
          )}
          <Popover open={open} onOpenChange={(next) => { if (!disabled) setOpen(next) }}>
            <PopoverTrigger asChild>
              <InputGroupButton size="icon-xs" aria-label={t('sdt.openPicker')} disabled={disabled}
                data-slot="smart-datetime-open" className="pointer-coarse:size-10">
                <CalendarDays aria-hidden="true" />
              </InputGroupButton>
            </PopoverTrigger>
            <PopoverContent align="end" sideOffset={6} collisionPadding={8} data-slot="smart-datetime-panel"
              className="z-(--z-menu) max-h-(--radix-popover-content-available-height) w-auto max-w-[calc(100vw-1rem)] overflow-y-auto p-0">
              <div className="flex flex-col sm:flex-row">
                <Calendar mode="single" captionLayout="dropdown" selected={selected || undefined}
                  defaultMonth={selected || anchorDate || now}
                  startMonth={new Date(2015, 0)} endMonth={new Date(now.getFullYear() + 1, 11)}
                  disabled={minDate ? { before: floorDay(minDate) } : undefined}
                  onSelect={(d, triggerDate) => pickDay(d || triggerDate)} autoFocus {...cal}
                  /* Ay listesi uygulama dilinde (shadcn varsayılanı tarayıcı dilini kullanıyordu: Türkçe ekranda "Nov") */
                  formatters={{ formatMonthDropdown: (d) => d.toLocaleString(dateLocale(), { month: 'short' }) }} />
                <div className="grid grid-cols-2 gap-2 border-t p-3 sm:w-40 sm:border-t-0 sm:border-l">
                  <TimeColumn open={open} label={t('sdt.hours')} slot="hours" options={hourOptions()}
                    value={selected ? selected.getHours() : null} onPick={(n) => pickPart('h', n)} />
                  <TimeColumn open={open} label={t('sdt.minutes')} slot="minutes" options={minuteOptions(selected ? selected.getMinutes() : null)}
                    value={selected ? selected.getMinutes() : null} onPick={(n) => pickPart('m', n)} />
                </div>
              </div>
              <div className="flex flex-wrap items-center justify-between gap-2 border-t px-3 py-2">
                <span className="text-sm font-medium tabular-nums" data-slot="smart-datetime-panel-value">
                  {selected ? formatLocal(selected) : <span className="font-normal text-muted-foreground">{t('sdt.noValue')}</span>}
                </span>
                <div className="flex gap-2">
                  <Button type="button" variant="outline" size="sm" className="pointer-coarse:h-10"
                    onClick={() => pickDate(floorMinute(new Date()))}>{t('sdt.p.now')}</Button>
                  <Button type="button" size="sm" className="pointer-coarse:h-10" onClick={() => setOpen(false)}>{t('dp.done')}</Button>
                </div>
              </div>
            </PopoverContent>
          </Popover>
        </InputGroupAddon>
      </InputGroup>

      {presetItems.length > 0 && !disabled && (
        <div role="group" aria-label={t('sdt.presets')} data-slot="smart-datetime-presets" className="flex flex-wrap gap-1.5">
          {presetItems.map((p) => {
            const label = presetLabel(t, p)
            const active = !!selected && floorMinute(selected).getTime() === p.at.getTime()
            return (
              <Button key={`${p.key}-${p.minutes ?? ''}`} type="button" variant={active ? 'secondary' : 'outline'} size="xs"
                className="h-7 rounded-full px-2.5 text-xs font-normal tabular-nums pointer-coarse:h-10 pointer-coarse:px-3"
                disabled={p.disabled} aria-pressed={active}
                aria-label={p.key === 'after' ? t('sdt.p.afterAria', formatDuration(p.minutes * 60_000, t), anchorLabel || '') : undefined}
                title={p.future ? t('sdt.p.futureTitle') : p.disabled ? t('sdt.p.beforeMinTitle') : formatLocal(p.at)}
                data-slot="smart-datetime-preset" data-preset={`${p.key}${p.minutes ? `-${p.minutes}` : ''}`}
                onClick={() => pickDate(p.at)}>
                {label}
              </Button>
            )
          })}
        </div>
      )}

      {problem ? (
        <p id={problemId} role="alert" className="text-xs text-destructive [overflow-wrap:anywhere]" data-slot="smart-datetime-problem">
          {t('sdt.unparsed', problem)}
        </p>
      ) : context ? (
        <p id={contextId} className={cn('text-xs [overflow-wrap:anywhere]', context.warn ? 'text-amber-700 dark:text-amber-300' : 'text-muted-foreground')}
          data-slot="smart-datetime-context" data-warn={context.warn || undefined}>
          {context.text}
        </p>
      ) : null}
    </div>
  )
}

function floorDay(d) {
  const c = new Date(d)
  c.setHours(0, 0, 0, 0)
  return c
}

function presetLabel(t, p) {
  if (p.key === 'now') return t('sdt.p.now')
  if (p.key === 'yesterday') return t('sdt.p.yesterday')
  const dur = formatDuration(p.minutes * 60_000, t)
  return p.key === 'ago' ? t('sdt.p.ago', dur) : t('sdt.p.after', dur)
}

/** Alanın altındaki bağlam satırı: "N önce · gün" / "Gelecekte …" / "<çapa> zamanından N sonra" — değer yoksa null. */
function contextText(t, selected, anchorDate, anchorLabel, now) {
  if (!selected) return null
  if (anchorDate) {
    const diff = floorMinute(selected).getTime() - floorMinute(anchorDate).getTime()
    const label = anchorLabel || ''
    if (diff === 0) return { text: t('sdt.sameAsAnchor', label), warn: false }
    if (diff > 0) return { text: t('sdt.afterAnchor', formatDuration(diff, t), label), warn: false }
    return { text: t('sdt.beforeAnchor', formatDuration(-diff, t), label), warn: true }
  }
  const diff = now.getTime() - selected.getTime()
  if (diff < -FUTURE_SLACK_MS) return { text: t('sdt.inFuture', formatDuration(-diff, t)), warn: true }
  const day = selected.toLocaleDateString(dateLocale(), { weekday: 'long' })
  if (Math.abs(diff) < 60_000) return { text: `${t('sdt.justNow')} · ${day}`, warn: false }
  return { text: `${t('sdt.ago', formatDuration(diff, t))} · ${day}`, warn: false }
}

/**
 * Saat / dakika sütunu: shadcn ToggleGroup (tek seçim, dikey, tek sekme durağı — ok tuşları gezer, Enter / Space seçer) bir
 * ScrollArea içinde. Panel açılınca seçili öğe görünür alanın ortasına kaydırılır.
 */
function TimeColumn({ open, label, slot, options, value, onPick }) {
  const labelId = useId()
  const ref = useRef(null)
  useEffect(() => {
    if (!open) return undefined
    const id = requestAnimationFrame(() => {
      // Yalnız listenin kendi kaydırma alanı kaydırılır — scrollIntoView açılır paneli ve diyaloğu da kaydırıp telefonda
      // takvim başlığını görünmez yapıyordu
      const vp = ref.current?.querySelector('[data-slot="scroll-area-viewport"]')
      const item = vp?.querySelector('[data-state="on"]')
      if (!vp || !item) return
      const vr = vp.getBoundingClientRect()
      const ir = item.getBoundingClientRect()
      vp.scrollTop += (ir.top - vr.top) - (vr.height - ir.height) / 2
    })
    return () => cancelAnimationFrame(id)
  }, [open, value])
  return (
    <div className="flex min-w-0 flex-col gap-1">
      <span id={labelId} className="text-xs font-medium text-muted-foreground">{label}</span>
      <div ref={ref} className="min-w-0">
      <ScrollArea className="h-40 rounded-md border sm:h-[14.5rem]">
        <ToggleGroup type="single" orientation="vertical" spacing={1} aria-labelledby={labelId} data-slot={`smart-datetime-${slot}`}
          value={value == null ? '' : String(value)} onValueChange={(v) => { if (v !== '') onPick(Number(v)) }}
          className="w-full flex-col items-stretch p-1">
          {options.map((n) => (
            <ToggleGroupItem key={n} value={String(n)} aria-label={`${label} ${pad(n)}`}
              className="h-8 w-full justify-center px-2 text-sm tabular-nums pointer-coarse:h-10 data-[state=on]:bg-primary data-[state=on]:text-primary-foreground">
              {pad(n)}
            </ToggleGroupItem>
          ))}
        </ToggleGroup>
      </ScrollArea>
      </div>
    </div>
  )
}
