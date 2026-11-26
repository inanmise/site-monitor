import { useEffect, useMemo, useRef, useState } from 'react'
import { CalendarRange, History as HistoryIcon, RotateCcw } from 'lucide-react'
import { api } from '../../api/client'
import { useLanguage, useT } from '../../i18n/index.jsx'
import { localeFor } from '../../i18n/dateLocale.js'
import { errorInfoOf } from '../../utils/errorMessages.js'
import { Badge } from '@/components/shadcn/badge'
import { Button } from '@/components/shadcn/button'
import { Card } from '@/components/shadcn/card'
import { Skeleton } from '@/components/shadcn/skeleton'
import DateTimeField from '../ui/DateTimeField.jsx'
import Field from '../ui/Field.jsx'
import SegmentedControl from '../ui/SegmentedControl.jsx'
import StatusBlock from '../ui/StatusBlock.jsx'
import RoutineHeatmap, { COUNTED_STATUSES, normalizeStatus, statusSwatch } from './RoutineHeatmap.jsx'
import { addDays, daysBetween, errorKey, isIsoDate, istanbulParts, shortDate } from './routineModel.js'
import { cn } from '@/lib/utils'

/**
 * Nöbet Rutinleri — "Geçmiş" görünümü (`or_view=history`, 2026-11-26). Takımın seçilen aralıktaki gün durumları
 * (`GET /teams/{id}/calendar`) ısı haritası olarak (`RoutineHeatmap`) + üstte durum sayaçları.
 *
 * Aralık sahibi sayfadır (`or_from` / `or_to`): bu bileşen yalnız `onRangeChange({from, to})` çağırır; `null` = varsayılan
 * (bugünle biten 365 gün). Hazır aralıklar 30 / 90 / 365 gün + özel (iki tarih alanı; ≤ 366 gün, bitiş ≤ bugün — geçersiz
 * aralık alanın altında söylenir, İSTEK GİTMEZ). Aralık değişince eski veri hemen silinir (iskelet), geç gelen yanıt sıra
 * numarasıyla düşürülür, okuma `AbortController` ile iptal edilir; okunamazsa tehlike bloğu + "Tekrar dene" (asla "kayıt yok").
 *
 * `RangePicker` ve aralık yardımcıları İstatistik görünümüyle (`RoutineStats`) ortaktır — buradan dışa aktarılır.
 */

/** Hazır aralıklar (gün). 365 = varsayılan (adrese yazılmaz). */
export const RANGE_PRESETS = Object.freeze([30, 90, 365])
export const DEFAULT_RANGE_DAYS = 365
/** Sunucu sınırı: aralık en çok 366 gün (iki uç dahil). */
export const MAX_RANGE_DAYS = 366

/** İstanbul'a göre bugün (`yyyy-MM-dd`). */
export function istanbulToday(nowMs = Date.now()) {
  return istanbulParts(nowMs).date
}

/**
 * Adresteki (`null` olabilir) uçlardan etkin aralık: ikisi de yoksa bugünle biten 365 gün; yalnız bitiş varsa ondan geriye
 * 365 gün; yalnız başlangıç varsa bitiş bugün. Değerler DOĞRULANMAZ — `validateRange` söyler.
 */
export function resolveRange(from, to, today) {
  const f = isIsoDate(from) ? from : null
  const tt = isIsoDate(to) ? to : null
  if (f && tt) return { from: f, to: tt }
  if (tt) return { from: addDays(tt, -(DEFAULT_RANGE_DAYS - 1)), to: tt }
  if (f) return { from: f, to: today }
  return { from: addDays(today, -(DEFAULT_RANGE_DAYS - 1)), to: today }
}

/** Aralıktaki gün sayısı (iki uç dahil); geçersizse null. */
export function rangeDays(range) {
  const d = daysBetween(range?.from, range?.to)
  return d == null ? null : d + 1
}

/**
 * Aralık doğrulaması — geçerliyse null, değilse `{ field: 'from'|'to', key }` (alanın altında gösterilecek metin anahtarı).
 * Kurallar sunucuyla aynı: iki uç geçerli tarih, başlangıç ≤ bitiş, bitiş ≤ bugün, en çok 366 gün.
 */
export function validateRange(range, today) {
  if (!isIsoDate(range?.from)) return { field: 'from', key: 'or.hist.range.invalidFrom' }
  if (!isIsoDate(range?.to)) return { field: 'to', key: 'or.hist.range.invalidTo' }
  if (isIsoDate(today) && range.to > today) return { field: 'to', key: 'or.hist.range.futureTo' }
  if (range.from > range.to) return { field: 'from', key: 'or.hist.range.order' }
  if (rangeDays(range) > MAX_RANGE_DAYS) return { field: 'from', key: 'or.hist.range.tooLong' }
  return null
}

/** Aralık bir hazır aralığa denk mi (bitiş bugün ve gün sayısı 30 / 90 / 365) — değilse 'custom'. */
export function presetOf(range, today) {
  if (validateRange(range, today) || range.to !== today) return 'custom'
  const n = rangeDays(range)
  return RANGE_PRESETS.includes(n) ? n : 'custom'
}

/** Okuma hatasının kullanıcı metni: sunucunun (zaten çevrilmiş) iletisi, yoksa kodun `or.err.*` metni, yoksa yedek. */
export function failureText(res, t, fallbackKey) {
  if (res && typeof res === 'object') {
    const msg = typeof res.error === 'string' && res.error.trim() ? res.error
      : (typeof res.message === 'string' && res.message.trim() ? res.message : null)
    if (msg) return msg
    const key = errorKey(errorInfoOf(res)?.code)
    if (key) return t(key)
  }
  return t(fallbackKey)
}

/**
 * Aralık seçici (Geçmiş ve İstatistik ortak). Hazır aralıklar `SegmentedControl` (30 / 90 / 365 gün / Özel); "Özel"de iki
 * gün alanı (`DateTimeField dateOnly`). Geçerli bir taslak hemen `onRangeChange` ile yayımlanır; geçersiz taslak alanın
 * altında söylenir ve YAYIMLANMAZ. `error` (isteğe bağlı) aralığın altında gösterilen dış hata (ör. dışa aktarım çok büyük).
 *
 * Test kancaları: `or-range`, `or-range-presets`, `or-range-text`, `or-range-custom`, `or-range-from`, `or-range-to`,
 * `or-range-error`.
 */
export function RangePicker({ from = null, to = null, today, onRangeChange = () => {}, error = null, disabled = false, className = '' }) {
  const t = useT()
  const range = resolveRange(from, to, today)
  const detected = presetOf(range, today)
  const [customOpen, setCustomOpen] = useState(detected === 'custom')
  const mode = customOpen || detected === 'custom' ? 'custom' : detected

  // Dışarıdan (adres, geri düğmesi) gelen aralık değişince taslak ona eşitlenir — render sırasında ayarlanır (efekt değil):
  // geçersiz taslak, dış değer değişmedikçe yerinde kalır.
  const rangeKey = `${range.from}|${range.to}`
  const [draft, setDraft] = useState(range)
  const [draftKey, setDraftKey] = useState(rangeKey)
  if (draftKey !== rangeKey) {
    setDraftKey(rangeKey)
    setDraft(range)
  }
  const draftError = mode === 'custom' ? validateRange(draft, today) : null
  // Metin YÜRÜRLÜKTEKİ aralığı söyler (geçersiz taslak alanın altında); dış aralık da geçersizse "seçilmedi"
  const appliedValid = !validateRange(range, today)

  const options = useMemo(() => [
    ...RANGE_PRESETS.map((n) => ({ value: n, label: t('or.hist.range.days', n) })),
    { value: 'custom', label: t('or.hist.range.custom'), icon: CalendarRange },
  ], [t])

  const pick = (value) => {
    if (disabled) return
    if (value === 'custom') {
      setCustomOpen(true)
      return
    }
    setCustomOpen(false)
    if (value === DEFAULT_RANGE_DAYS) onRangeChange({ from: null, to: null })
    else onRangeChange({ from: addDays(today, -(value - 1)), to: today })
  }

  const commit = (next) => {
    setDraft(next)
    if (!validateRange(next, today)) onRangeChange({ from: next.from, to: next.to })
  }

  return (
    <div data-slot="or-range" data-mode={mode === 'custom' ? 'custom' : `d${mode}`} className={cn('flex min-w-0 flex-col gap-3', className)}>
      <div className="flex flex-wrap items-center gap-x-3 gap-y-2">
        <div data-slot="or-range-presets" className="max-w-full">
          <SegmentedControl value={mode} onChange={pick} options={options} ariaLabel={t('or.hist.range.aria')}
            className="max-w-full flex-wrap" />
        </div>
        <p data-slot="or-range-text" className="min-w-0 text-xs text-muted-foreground tabular-nums [overflow-wrap:anywhere]">
          {appliedValid
            ? t('or.hist.range.text', shortDate(range.from, true), shortDate(range.to, true), rangeDays(range))
            : t('or.hist.range.textUnknown')}
        </p>
      </div>
      {mode === 'custom' && (
        <div data-slot="or-range-custom" className="grid gap-x-3 sm:grid-cols-2 sm:max-w-xl">
          <div data-slot="or-range-from" className="min-w-0">
            <Field label={t('or.hist.range.from')} error={draftError?.field === 'from' ? t(draftError.key, MAX_RANGE_DAYS) : null} className="mb-0">
              {({ id, describedBy, invalid }) => (
                <DateTimeField dateOnly id={id} describedBy={describedBy} invalid={invalid} disabled={disabled}
                  value={draft.from || ''} max={isIsoDate(draft.to) && draft.to <= today ? draft.to : today}
                  onChange={(v) => commit({ ...draft, from: v || null })} />
              )}
            </Field>
          </div>
          <div data-slot="or-range-to" className="min-w-0">
            <Field label={t('or.hist.range.to')} error={draftError?.field === 'to' ? t(draftError.key, MAX_RANGE_DAYS) : null} className="mb-0">
              {({ id, describedBy, invalid }) => (
                <DateTimeField dateOnly id={id} describedBy={describedBy} invalid={invalid} disabled={disabled}
                  value={draft.to || ''} min={isIsoDate(draft.from) ? draft.from : undefined} max={today}
                  onChange={(v) => commit({ ...draft, to: v || null })} />
              )}
            </Field>
          </div>
        </div>
      )}
      {error && (
        <p data-slot="or-range-error" role="alert" className="text-xs font-medium text-destructive [overflow-wrap:anywhere]">{error}</p>
      )}
    </div>
  )
}

/** Satırlardan durum sayaçları (yalnız aralıkta olan, bugünden sonra olmayan satırlar). */
export function summarizeDays(days, { today } = {}) {
  const out = { workdays: 0, COMPLETED: 0, COMPLETED_PARTIAL: 0, INCOMPLETE: 0, MISSED: 0, OPEN: 0, recorded: 0 }
  for (const d of Array.isArray(days) ? days : []) {
    if (!d || !isIsoDate(d.date)) continue
    if (isIsoDate(today) && d.date > today) continue
    const st = normalizeStatus(d.status)
    if (d.workday && st !== 'FUTURE' && st !== 'PAUSED') out.workdays += 1
    if (COUNTED_STATUSES.includes(st)) {
      out[st] += 1
      out.recorded += 1
    }
  }
  return out
}

const SUMMARY_ORDER = ['COMPLETED', 'COMPLETED_PARTIAL', 'INCOMPLETE', 'MISSED', 'OPEN']

function SummaryChips({ summary, t, locale }) {
  return (
    <ul data-slot="or-hist-summary" className="flex flex-wrap items-center gap-2" aria-label={t('or.hist.summaryAria')}>
      <li>
        <Badge variant="outline" data-slot="or-hist-chip" data-status="WORKDAYS"
          className="h-auto gap-1.5 overflow-visible rounded-md px-2 py-1 text-xs whitespace-normal">
          <span className="text-muted-foreground">{t('or.hist.workdays')}</span>
          <span className="font-semibold tabular-nums">{summary.workdays.toLocaleString(locale)}</span>
        </Badge>
      </li>
      {SUMMARY_ORDER.map((st) => {
        const sw = statusSwatch(st)
        return (
          <li key={st}>
            <Badge variant="outline" data-slot="or-hist-chip" data-status={st} data-count={summary[st]}
              className={cn('h-auto gap-1.5 overflow-visible rounded-md px-2 py-1 text-xs whitespace-normal', summary[st] === 0 && 'text-muted-foreground')}>
              <span aria-hidden="true" className={cn('size-2.5 shrink-0 rounded-[3px]', sw.className)} style={sw.style} />
              <span className={summary[st] === 0 ? undefined : 'text-muted-foreground'}>{t(`or.status.${st}`)}</span>
              <span className="font-semibold tabular-nums">{summary[st].toLocaleString(locale)}</span>
            </Badge>
          </li>
        )
      })}
    </ul>
  )
}

function HistoryLoading({ t }) {
  return (
    <Card data-slot="or-hist-loading" aria-busy="true" aria-label={t('or.hist.loading')} className="gap-4 px-4 py-4 sm:px-5">
      <div className="flex flex-wrap gap-2">
        {[0, 1, 2, 3, 4, 5].map((i) => <Skeleton key={i} className="h-7 w-24 rounded-md" />)}
      </div>
      <Skeleton className="h-[132px] w-full rounded-lg" />
      <div className="flex flex-wrap gap-3">
        {[0, 1, 2, 3, 4].map((i) => <Skeleton key={i} className="h-3 w-20" />)}
      </div>
    </Card>
  )
}

export default function HistoryView({
  teamId, teamName, from = null, to = null, onRangeChange = () => {}, onOpenDay = () => {}, clockOffsetMs = 0,
}) {
  const t = useT()
  const { lang } = useLanguage()
  const locale = localeFor(lang)
  // Sunucunun "bugün"ü bilinince o esas alınır (istemci saati kayık olabilir); ilk istekte İstanbul'a göre istemci günü.
  const [serverToday, setServerToday] = useState(null)
  const today = serverToday || istanbulToday(Date.now() + (Number(clockOffsetMs) || 0))
  const range = resolveRange(from, to, today)
  const rangeError = validateRange(range, today)
  const [state, setState] = useState({ status: 'idle', data: null, res: null })
  const [nonce, setNonce] = useState(0)
  const seqRef = useRef(0)

  const hasTeam = teamId != null && teamId !== ''
  const blocked = !hasTeam || !!rangeError
  useEffect(() => {
    const seq = ++seqRef.current
    if (blocked) {
      setState({ status: 'idle', data: null, res: null })
      return undefined
    }
    const ctrl = new AbortController()
    setState({ status: 'loading', data: null, res: null })   // aralık / takım değişti: eski veri hemen gider
    ;(async () => {
      try {
        const res = await api.oncallRoutines.calendar(teamId, { from: range.from, to: range.to, signal: ctrl.signal })
        if (seq !== seqRef.current || ctrl.signal.aborted) return
        if (res === null) return   // oturum bitti — 401 yolu zaten işledi
        if (res?.success === true && res.data && Array.isArray(res.data.days)) {
          if (isIsoDate(res.data.today)) setServerToday((prev) => (prev === res.data.today ? prev : res.data.today))
          setState({ status: 'ok', data: res.data, res: null })
        } else {
          setState({ status: 'error', data: null, res })
        }
      } catch (e) {
        if (seq !== seqRef.current || ctrl.signal.aborted) return
        setState({ status: 'error', data: null, res: e })
      }
    })()
    return () => ctrl.abort()
  }, [teamId, range.from, range.to, blocked, nonce])

  const data = state.status === 'ok' ? state.data : null
  const gridFrom = isIsoDate(data?.from) ? data.from : range.from
  const gridTo = isIsoDate(data?.to) ? data.to : range.to
  const gridToday = isIsoDate(data?.today) ? data.today : today
  const summary = useMemo(() => summarizeDays(data?.days, { today: gridToday }), [data, gridToday])
  const since = isIsoDate(data?.routine_since) && data.routine_since > gridFrom ? data.routine_since : null

  return (
    <section data-slot="or-history" data-state={state.status} className="flex min-w-0 flex-col gap-4">
      <Card className="gap-3 px-4 py-4 sm:px-5">
        <div className="flex min-w-0 items-start gap-2">
          <HistoryIcon aria-hidden="true" className="mt-0.5 size-4 shrink-0 text-muted-foreground" />
          <div className="min-w-0">
            <h2 className="text-sm font-semibold">{t('or.hist.title')}</h2>
            <p className="text-xs text-muted-foreground [overflow-wrap:anywhere]">{t('or.hist.subtitle')}</p>
          </div>
        </div>
        <RangePicker from={from} to={to} today={today} onRangeChange={onRangeChange} disabled={!hasTeam} />
      </Card>

      {state.status === 'loading' && <HistoryLoading t={t} />}

      {state.status === 'error' && (
        <div data-slot="or-hist-failed">
          <StatusBlock tone="danger" title={t('or.hist.readTitle')}
            description={failureText(state.res, t, 'or.hist.readBody')}
            details={state.res && typeof state.res === 'object' ? state.res : undefined}
            actions={(
              <Button type="button" variant="outline" data-slot="or-hist-retry" onClick={() => setNonce((n) => n + 1)}>
                <RotateCcw aria-hidden="true" />{t('or.hist.retry')}
              </Button>
            )} />
        </div>
      )}

      {data && (
        <Card data-slot="or-hist-body" className="gap-4 px-4 py-4 sm:px-5">
          <SummaryChips summary={summary} t={t} locale={locale} />
          {since && (
            <p data-slot="or-hist-since" className="text-xs text-muted-foreground [overflow-wrap:anywhere]">
              {t('or.hist.since', shortDate(since, true))}
            </p>
          )}
          {summary.recorded === 0 && (
            <p data-slot="or-hist-empty" className="text-xs text-muted-foreground [overflow-wrap:anywhere]">{t('or.hist.empty')}</p>
          )}
          <RoutineHeatmap days={data.days} from={gridFrom} to={gridTo} today={gridToday} onOpenDay={onOpenDay} teamName={teamName} />
        </Card>
      )}
    </section>
  )
}
