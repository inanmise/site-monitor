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
  FUTURE_SLACK_MS, MAX_TYPED, floorMinute, formatLocal, hourOptions, liveReady, minuteOptions, parseIso, parseTyped, presetsFor, stepValue,
  toIso,
} from '../../utils/smartDateTime.js'

const pad = (n) => String(n).padStart(2, '0')

/**
 * Yazılabilir tarih + saat alanı (2026-11-17, kullanıcı isteği: Olay & Hata Geçmişi'nde yeni olay eklerken tarih girişi çok
 * zordu). `ui/DateTimeField` ile aynı değer sözleşmesi (UTC ISO, bölge eki yok); ondan farkı:
 *   • Alan YAZILIR: "16.11 14:30", "dün 09:00", "1430", "şimdi" … (`utils/smartDateTime.parseTyped`). Gün ile dakikası tam
 *     yazılmış saat yazılınca değer anında kaydedilir (`liveReady`); yalnız gün / yalnız saat / yarım yazım odak çıkınca, Enter
 *     ya da Ctrl/⌘+Enter ile çözülür. Okunamayan metin odaklanmadan ÖNCEKİ değeri geri getirir (yazarken kaydedilen ara değer
 *     kalmaz) ve alanın altında ne yazıldığı + örnekler söylenir; `onProblem(true)` formun kaydı durdurmasını sağlar.
 *   • Değişmeyen değer yeniden yazılmaz: aynı dakikayı veren metin `onChange` çağırmaz (uyarıdan gelen saniyeler korunur,
 *     form "kaydedilmemiş değişiklik" saymaz).
 *   • ↑ / ↓ 5 dk, Shift ile 1 saat adımlar.
 *   • Hızlı seçim çipleri: "Şimdi", "15 dk önce", "1 sa önce", "Dün bu saatte" — `anchor` verilirse (tespit / çözüm zamanı)
 *     "+5 dk … +2 sa" o ana göre. Gelecekteki ya da `min` öncesindeki an seçilemez (çip pasif, nedeni `title`'da).
 *   • Takvim düğmesi: ay / yıl açılır listeli takvim + saat ve dakika listeleri (5 dk adım; seçili dakika adımda değilse o da
 *     listededir) + "Şimdi" / "Tamam".
 *   • Alanın altında bağlam: "2 sa 15 dk önce · pazartesi" ya da "Oluş Zamanı zamanından 25 dk sonra"; gelecek / sıra hatası
 *     uyarı renginde (kaydı engellemez — sıra kuralını form denetler).
 * `id` / `describedBy` / `invalid` `ui/Field`'dan gelir (etiket ↔ yazılabilir alan bağı); `label` (alanın adı) düğmelerin
 * erişilebilir adlarını alana özgü yapar; `minLabel` en erken izin verilen anın adı (sıra uyarısı).
 */
export default function SmartDateTimeField({
  id, value, onChange, disabled, clearable, min, anchor, anchorLabel, minLabel, label, invalid, describedBy, onProblem,
  presets = true,
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
  const [problem, setProblemState] = useState(null)   // okunamayan metin (ham) — önceki değer korundu
  const [open, setOpen] = useState(false)
  const focusedRef = useRef(false)
  const sentRef = useRef(value || '')
  // Odaklanmadan önceki (ya da son bilinçli kayıttaki) değer: yazarken eksik parça ondan alınır, okunamayan metin onu geri getirir
  const baselineRef = useRef(value || '')
  const onProblemRef = useRef(onProblem)
  onProblemRef.current = onProblem

  // Sorun bilgisi forma EŞZAMANLI iletilir (fare ile "Kaydet": odak çıkışı → bu satır → tıklama aynı olay zincirinde)
  function setProblem(p) {
    setProblemState(p)
    try { onProblemRef.current?.(!!p) } catch { /* yoksay */ }
  }
  useEffect(() => () => { try { onProblemRef.current?.(false) } catch { /* yoksay */ } }, [])

  // Dışarıdan gelen değer (çip, takvim, form sıfırlama) metni günceller; yazarken kendi canlı kaydımız metni ezmez.
  useEffect(() => {
    const v = value || ''
    if (focusedRef.current && v === sentRef.current) return
    sentRef.current = v
    if (!focusedRef.current) baselineRef.current = v
    setText(formatLocal(parseIso(v)))
  }, [value])

  /** Değeri kaydeder. `live`: yazarken ara kayıt (önceki değer çizgisini taşımaz). Aynı dakika → `onChange` yok. */
  function commit(d, live = false) {
    const cur = parseIso(value)
    const same = d ? !!cur && floorMinute(d).getTime() === floorMinute(cur).getTime() : !value
    const iso = same ? (value || '') : (d ? toIso(d) : '')
    sentRef.current = iso
    if (!live) baselineRef.current = iso
    setProblem(null)
    if (!same) onChange(iso)
  }

  /** Okunamayan metin: odaklanmadan önceki değeri (saniyeleriyle) geri getirir. */
  function restore(raw) {
    const base = baselineRef.current
    sentRef.current = base
    if (base !== (value || '')) onChange(base)
    setText(formatLocal(parseIso(base)))
    setProblem(String(raw).slice(0, MAX_TYPED))
  }

  function settle(raw) {
    const base = parseIso(baselineRef.current)
    if (base && raw === formatLocal(base)) { commit(base); setText(formatLocal(base)); return }
    const r = parseTyped(raw, { base })
    if (r.empty) { commit(null); setText(''); return }
    if (r.date) { commit(r.date); setText(formatLocal(r.date)); return }
    restore(raw)
  }

  function onType(e) {
    const raw = e.target.value
    setText(raw)
    setProblem(null)
    if (!liveReady(raw)) return
    const r = parseTyped(raw, { base: parseIso(baselineRef.current) })
    if (r.date && r.complete) commit(r.date, true)
  }

  function onKeyDown(e) {
    if (e.key === 'Enter' && (e.ctrlKey || e.metaKey)) {
      // Form kısayolu (Ctrl/⌘+Enter kaydeder): yazılan değer önce çözülsün; olay forma kadar kabarır
      settle(e.currentTarget.value)
      return
    }
    if (e.key === 'Enter') {
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

  // Değer yokken saat / gün kaynağı: önce çapa (oluş), sonra en erken an, en son şimdi — "tespit" alanında oluş gününü
  // seçmek oluştan önceki bir saate düşmesin
  const fallback = () => floorMinute(anchorDate || minDate || new Date())
  const notBeforeMin = (d) => (minDate && d.getTime() < floorMinute(minDate).getTime() ? floorMinute(minDate) : d)

  function pickDay(day) {
    if (!day) return
    const d = new Date(day)
    const src = selected || fallback()
    d.setHours(src.getHours(), src.getMinutes(), 0, 0)
    const out = selected ? d : notBeforeMin(d)
    commit(out)
    setText(formatLocal(out))
  }
  function pickPart(part, n) {
    const d = selected ? new Date(selected) : fallback()
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

  const context = contextText(t, selected, anchorDate, anchorLabel, minDate, minLabel, now)
  const ids = [describedBy, problem ? problemId : null, context ? contextId : null].filter(Boolean).join(' ') || undefined
  const clearShown = !!(clearable && value && !disabled)
  const named = (key, keyFor) => (label ? t(keyFor, label) : t(key))
  const anyFuture = presetItems.some((p) => p.disabled && p.future)
  const anyBeforeMin = presetItems.some((p) => p.disabled && !p.future)
  const disabledReason = (p) => (p.future ? t('sdt.p.futureTitle') : t('sdt.p.beforeMinTitle'))

  return (
    <div data-slot="smart-datetime" className="flex min-w-0 flex-col gap-1.5">
      <InputGroup data-disabled={disabled || undefined}>
        <InputGroupAddon><CalendarClock aria-hidden="true" /></InputGroupAddon>
        <InputGroupInput id={id} value={text} disabled={disabled} autoComplete="off" spellCheck={false} inputMode="text"
          maxLength={MAX_TYPED} placeholder={t('sdt.placeholder')} aria-invalid={invalid || !!problem || undefined}
          aria-describedby={ids} data-sdt="input"
          onFocus={() => { focusedRef.current = true; baselineRef.current = value || '' }}
          onBlur={(e) => { focusedRef.current = false; settle(e.currentTarget.value) }}
          onChange={onType} onKeyDown={onKeyDown} />
        {/* Negatif kenar payı yok: 40 px dokunmatik düğmelerle kap grubun dışına 5 px taşıyordu (telefonda yatay kaydırma) */}
        <InputGroupAddon align="inline-end" className="gap-0.5 pr-1 has-[>button]:mr-0">
          {clearShown && (
            <InputGroupButton size="icon-xs" aria-label={named('sdt.clear', 'sdt.clearFor')} data-slot="smart-datetime-clear"
              className="pointer-coarse:size-10" onClick={() => { commit(null); setText('') }}>
              <X aria-hidden="true" />
            </InputGroupButton>
          )}
          <Popover open={open} onOpenChange={(next) => { if (!disabled) setOpen(next) }}>
            <PopoverTrigger asChild>
              <InputGroupButton size="icon-xs" aria-label={named('sdt.openPicker', 'sdt.openPickerFor')} disabled={disabled}
                data-slot="smart-datetime-open" className="pointer-coarse:size-10">
                <CalendarDays aria-hidden="true" />
              </InputGroupButton>
            </PopoverTrigger>
            <PopoverContent align="end" sideOffset={6} collisionPadding={8} data-slot="smart-datetime-panel"
              className="z-(--z-menu) max-h-(--radix-popover-content-available-height) w-auto max-w-[calc(100vw-1rem)] overflow-y-auto p-0">
              <div className="flex flex-col sm:flex-row">
                <Calendar mode="single" captionLayout="dropdown" selected={selected || undefined}
                  defaultMonth={selected || anchorDate || now}
                  /* Yazımın kabul ettiği aralıkla uyumlu: 2000'den (eski olay kayıtları) gelecek yıla ya da seçili yıla */
                  startMonth={new Date(2000, 0)}
                  endMonth={new Date(Math.max(now.getFullYear() + 1, selected ? selected.getFullYear() : 0), 11)}
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
        <div role="group" aria-label={named('sdt.presets', 'sdt.presetsFor')} data-slot="smart-datetime-presets" className="flex flex-wrap gap-1.5">
          {presetItems.map((p) => {
            const label = presetLabel(t, p)
            const active = !!selected && floorMinute(selected).getTime() === p.at.getTime()
            return (
              <Button key={`${p.key}-${p.minutes ?? ''}`} type="button" variant={active ? 'secondary' : 'outline'} size="xs"
                className="h-7 rounded-full px-2.5 text-xs font-normal tabular-nums pointer-coarse:h-10 pointer-coarse:px-3"
                disabled={p.disabled} aria-pressed={active}
                aria-label={chipAria(t, p, anchorLabel, disabledReason)}
                title={p.future ? t('sdt.p.futureTitle') : p.disabled ? t('sdt.p.beforeMinTitle') : formatLocal(p.at)}
                data-slot="smart-datetime-preset" data-preset={`${p.key}${p.minutes ? `-${p.minutes}` : ''}`}
                onClick={() => pickDate(p.at)}>
                {label}
              </Button>
            )
          })}
        </div>
      )}
      {/* Soluk çiplerin nedeni görünür (title dokunmatikte ve ekran okuyucuda yok) */}
      {!disabled && (anyFuture || anyBeforeMin) && (
        <p className="text-xs text-muted-foreground [overflow-wrap:anywhere]" data-slot="smart-datetime-presets-note">
          {anyFuture && anyBeforeMin ? t('sdt.p.noteBoth', minLabel || anchorLabel || '')
            : anyFuture ? t('sdt.p.noteFuture') : t('sdt.p.noteBeforeMin', minLabel || anchorLabel || '')}
        </p>
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

function chipAria(t, p, anchorLabel, disabledReason) {
  const base = p.key === 'after' ? t('sdt.p.afterAria', formatDuration(p.minutes * 60_000, t), anchorLabel || '') : presetLabel(t, p)
  if (p.disabled) return `${base} — ${disabledReason(p)}`
  return p.key === 'after' ? base : undefined
}

function presetLabel(t, p) {
  if (p.key === 'now') return t('sdt.p.now')
  if (p.key === 'yesterday') return t('sdt.p.yesterday')
  const dur = formatDuration(p.minutes * 60_000, t)
  return p.key === 'ago' ? t('sdt.p.ago', dur) : t('sdt.p.after', dur)
}

/**
 * Alanın altındaki bağlam satırı — değer yoksa null. Sıra: gelecekteki an → çapadan (oluş) önce → en erken andan (tespit)
 * önce → çapaya göre "N sonra" → "N önce · gün".
 */
function contextText(t, selected, anchorDate, anchorLabel, minDate, minLabel, now) {
  if (!selected) return null
  const ahead = now.getTime() - selected.getTime()
  if (ahead < -FUTURE_SLACK_MS) return { text: t('sdt.inFuture', formatDuration(-ahead, t)), warn: true }
  const sel = floorMinute(selected).getTime()
  if (anchorDate && sel < floorMinute(anchorDate).getTime()) {
    return { text: t('sdt.beforeAnchor', formatDuration(floorMinute(anchorDate).getTime() - sel, t), anchorLabel || ''), warn: true }
  }
  if (minDate && sel < floorMinute(minDate).getTime()) {
    return { text: t('sdt.beforeAnchor', formatDuration(floorMinute(minDate).getTime() - sel, t), minLabel || ''), warn: true }
  }
  if (anchorDate) {
    const diff = sel - floorMinute(anchorDate).getTime()
    const label = anchorLabel || ''
    if (diff === 0) return { text: t('sdt.sameAsAnchor', label), warn: false }
    return { text: t('sdt.afterAnchor', formatDuration(diff, t), label), warn: false }
  }
  const diff = ahead
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
