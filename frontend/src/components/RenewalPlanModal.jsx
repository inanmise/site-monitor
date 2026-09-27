import { useCallback, useId, useLayoutEffect, useMemo, useRef, useState } from 'react'
import { CalendarCheck2, CalendarPlus, Info, Trash2 } from 'lucide-react'
import { api } from '../api/client'
import { useDateLocale, useT } from '../i18n/index.jsx'
import { useToast } from './ui/Toast.jsx'
import { useDialog } from './ui/Dialog.jsx'
import ModalShell from './ui/ModalShell.jsx'
import AlertBanner from './ui/AlertBanner.jsx'
import DateTimeField from './ui/DateTimeField.jsx'
import Field from './ui/Field.jsx'
import { ProgressBar, Spinner } from './ui/Progress.jsx'
import { dayDiff, isWeekend, lastBusinessDay, todayKey } from '../pages/forecastModel.js'
import {
  NOTE_MAX, NOTE_WARN_AT, daysTone, formatDay, modKeyLabel, offDaySuggestion, planState, quickPicks,
  timelineModel, weekdayName,
} from './renewalPlanModel.js'
import { Badge } from '@/components/shadcn/badge'
import { Button } from '@/components/shadcn/button'
import { Kbd, KbdGroup } from '@/components/shadcn/kbd'
import { Textarea } from '@/components/shadcn/textarea'
import { ToggleGroup, ToggleGroupItem } from '@/components/shadcn/toggle-group'
import { cn } from '@/lib/utils'

/**
 * Yenileme planı penceresi — sertifika (Genel Bakış kartı "Planla", Uyarılar, Sertifika Takvimi) ve alan adı kaydı
 * (DomainMonitorPage) ORTAK. 2026-09-27: shadcn + mobil web yeniden tasarımı (önceki: tek satır ipucu + tarih + not).
 *
 * Sözleşme (DEĞİŞMEDİ — dört çağıran buna dayanır):
 *   row      { domain, renewal_planned_at, renewal_planned_note, renew_by_key, expiry_key } — tarihler 'yyyy-MM-dd'
 *            (yerel gün); renew_by_key / expiry_key null olabilir (alan adı izlemesi renew_by_key: null verir).
 *            `renewal_planned_by` (Takvim satırları taşır) varsa "kaydeden" olarak gösterilir, yoksa atlanır.
 *   onSaved(data) / onCleared(data)  API `data`'sı. Kapatmayı çağıran yapar.
 *   plan(date, note) / unplan()      verilirse envanter uçları (api.forecastPlan / forecastUnplan) yerine onlar.
 *   hint     verilirse açıklama metni odur VE sertifikaya özgü her şey gizlenir (renew-by satırı/çentiği/hızlı
 *            seçimi, "yeni sertifika" / "sertifika hatası" dili) — alan adı kaydında bunlar anlamsız (2026-09-22, H).
 *
 * Yerleşim (yukarıdan aşağı): önemli tarihler kartı (kayıtlı plan · renew-by · bitiş, kalan gün rozetleriyle; altında
 * bugün → bitiş şeridi, seçilen gün noktası canlı) → tarih + seçilen günün göreli açıklaması → hızlı seçim
 * (ToggleGroup; her seçim iş gününe düşer) → uyarılar (geçmiş / renew-by sonrası / bitiş günü / bitiş sonrası;
 * hafta sonu/tatil + tek tıkla iş gününe taşı) → not (sayaçlı) → açıklama dipnotu.
 *
 * Davranış:
 *   • Kaydet birincil; Ctrl/⌘+Enter kaydeder (tarih seçicide Enter takvimi açar, form yok → kazara gönderim yok).
 *   • Kaldır yalnız kayıtlı plan varsa; geri alınamaz olduğu için useDialog onayı ister.
 *   • Tarih/not değiştiyse İptal / Escape / X / örtü "değişiklikler atılsın mı?" sorar (useDialog).
 *   • İstek sürerken tüm düğmeler kapalı, ModalShell `busy` kapatma yollarını kilitler; çift Ctrl+Enter tek istek.
 *   • Açılışta odak tarih seçicide. Telefonda (<640 px) pencere alttan açılan sayfa olur; alt çubuk hep görünür.
 *   • Sunucuda tarih kuralı YOK (yalnız yyyy-MM-dd biçimi; ForecastController / DomainRenewalPlanService) → geç ya da
 *     geçmiş gün ENGELLENMEZ, yalnız uyarılır.
 */

// Telefonda (<640 px) alttan açılan sayfa: tam genişlik, alta yaslı, üst köşeler yuvarlak — birincil düğme başparmak
// bölgesinde. ModalShell'e dokunulmadı: sınıflar `className` ile eklenir (cn/twMerge; max-sm: medya kuralı temel
// konumu ezer). Yükseklik 92dvh tavanlı, gövde kayar (scrollBody), alt çubuk güvenli alan payıyla sabit.
const SHEET_ON_PHONE = cn(
  'max-sm:top-auto max-sm:bottom-0 max-sm:max-w-full max-sm:translate-y-0 max-sm:rounded-b-none',
  'max-sm:border-x-0 max-sm:border-b-0 max-sm:px-4 max-sm:pt-5 max-sm:pb-[max(1rem,env(safe-area-inset-bottom))]',
  'max-sm:max-h-[92dvh] max-sm:data-[state=open]:slide-in-from-bottom-6',
)

const BADGE_VARIANT = { ok: 'secondary', warning: 'warning', critical: 'destructive' }
const DOT_TONE = {
  ok: 'bg-primary',
  afterRenewBy: 'bg-amber-500',
  onExpiry: 'bg-amber-500',
  afterExpiry: 'bg-destructive',
  past: 'bg-destructive',
}
// Şerit dolgusu: ProgressBar'ın `--pg-fill` jetonu (CertCardParts / DomainMonitorPage ile aynı kalıp) — yarı saydam ton.
const FILL_TONE = {
  ok: '[--pg-fill:color-mix(in_oklab,var(--primary)_30%,transparent)]',
  afterRenewBy: '[--pg-fill:color-mix(in_oklab,var(--color-amber-500)_45%,transparent)]',
  onExpiry: '[--pg-fill:color-mix(in_oklab,var(--color-amber-500)_45%,transparent)]',
  afterExpiry: '[--pg-fill:color-mix(in_oklab,var(--destructive)_35%,transparent)]',
  past: '[--pg-fill:color-mix(in_oklab,var(--destructive)_35%,transparent)]',
}
const WARN_TONE = { past: 'warning', afterRenewBy: 'warning', onExpiry: 'warning', afterExpiry: 'danger' }

/** DateTimeField'in tetik düğmesi (shadcn Button, `data-slot="date-picker-trigger"`). */
function dateTrigger(root) { return root?.querySelector('[data-slot="date-picker-trigger"]') || null }
function setDescribedBy(el, ids) {
  if (!el) return
  if (ids) el.setAttribute('aria-describedby', ids)
  else el.removeAttribute('aria-describedby')
}

export default function RenewalPlanModal({ row, onClose, onSaved, onCleared, plan = null, unplan = null, hint = null }) {
  const t = useT(); const toast = useToast(); const { showConfirm } = useDialog()
  const locale = useDateLocale()
  const uid = useId()

  const certMode = !hint
  const saved = row.renewal_planned_at || ''
  const renewBy = certMode ? (row.renew_by_key || null) : null
  const expiry = row.expiry_key || null
  const today = todayKey()
  // Varsayılan gün: kayıtlı plan > renew-by (iş gününe çekilmiş; geçmişe düştüyse boş — geç kalınmış kayıtta geçmiş bir
  // günü önermek yerine kullanıcı hızlı seçimden "sonraki iş günü"nü alır) > boş.
  const [initial] = useState(() => {
    const suggested = renewBy ? lastBusinessDay(renewBy) : ''
    return { date: saved || (suggested && suggested >= today ? suggested : ''), note: row.renewal_planned_note || '' }
  })
  const [date, setDate] = useState(initial.date)
  const [note, setNote] = useState(initial.note)
  const [busy, setBusy] = useState(null)               // null | 'save' | 'clear'
  const inflight = useRef(false)                       // çift Ctrl+Enter / çift tık → tek istek
  const confirming = useRef(false)                     // "atılsın mı?" penceresi açıkken ikinci kez sorma

  const dirty = date !== initial.date || note !== initial.note
  // Kayıtlı planı değiştirmeden "kaydet" anlamsız; yeni planda önerilen gün (renew-by) değişiklik saymadan kaydedilir.
  const canSave = !!date && !busy && (!saved || dirty)

  const fmt = useCallback((key, opts) => formatDay(key, locale, opts), [locale])
  const count = useCallback((n) => (Math.abs(n) === 1 ? t('forecast.planDaysOne') : t('forecast.planDaysMany', Math.abs(n))), [t])
  const relToday = useCallback((n) => {
    if (n === 0) return t('forecast.planRelToday')
    if (n === 1) return t('forecast.planRelTomorrow')
    if (n === -1) return t('forecast.planRelYesterday')
    return n > 0 ? t('forecast.planRelIn', count(n)) : t('forecast.planRelAgo', count(n))
  }, [t, count])

  const state = planState({ today, renewBy, expiry, planned: date })
  const picks = useMemo(() => quickPicks({ today, renewBy, expiry }), [today, renewBy, expiry])
  const activePick = picks.find((p) => p.date === date)?.key ?? ''
  const timeline = timelineModel({ today, renewBy, expiry, planned: date })
  const off = date && state !== 'past' ? offDaySuggestion(date, today) : null

  // Seçilen günün göreli açıklaması: "Wednesday · in 3 days · 2 days before the renew-by date".
  let relation = null
  if (date) {
    const parts = [weekdayName(date, locale), relToday(dayDiff(today, date))]
    const anchor = renewBy || expiry
    if (anchor) {
      const d = dayDiff(date, anchor)
      const [before, on, after] = renewBy
        ? ['forecast.planRelBeforeRenewBy', 'forecast.planRelOnRenewBy', 'forecast.planRelAfterRenewBy']
        : ['forecast.planRelBeforeExpiry', 'forecast.planRelOnExpiry', 'forecast.planRelAfterExpiry']
      parts.push(d > 0 ? t(before, count(d)) : d === 0 ? t(on) : t(after, count(d)))
    }
    relation = parts.filter(Boolean).join(' · ')
  }

  const warnings = []
  if (state === 'past') warnings.push({ kind: 'past', text: t('forecast.planPastWarn') })
  else if (state === 'afterExpiry') warnings.push({ kind: 'afterExpiry', text: certMode ? t('forecast.planAfterExpiryWarnCert', fmt(expiry)) : t('forecast.planAfterExpiryWarn', fmt(expiry)) })
  else if (state === 'onExpiry') warnings.push({ kind: 'onExpiry', text: t('forecast.planOnExpiryWarn') })
  else if (state === 'afterRenewBy') warnings.push({ kind: 'afterRenewBy', text: t('forecast.planAfterRenewByWarn', fmt(renewBy)) })

  const relId = `${uid}-rel`
  const warnId = (kind) => `${uid}-w-${kind}`
  const describedBy = [
    relation && relId, ...warnings.map((w) => warnId(w.kind)), off && warnId('offday'),
  ].filter(Boolean).join(' ')

  // DateTimeField kimlik/aria prop'u İLETMİYOR (ui/ — bu işin kapsamı dışında); tetik düğmesine açıklama bağı DOM'da
  // kurulur. React bu özniteliği hiç yönetmediği için sonraki çizimler onu silmez. Açılışta odak da tarih tetiğine
  // verilir: ref geri çağrısı düzen evresinde koşar, Radix FocusScope'un "ilk odaklanabilir öğe (X)" effect'inden
  // ÖNCE — odak zaten içerideyse FocusScope dokunmaz (autoFocus ile aynı yol).
  const dateWrap = useRef(null)
  const describedByRef = useRef('')
  const attachDateWrap = useCallback((el) => {
    dateWrap.current = el
    if (!el) return
    const tr = dateTrigger(el)
    setDescribedBy(tr, describedByRef.current)
    ;(tr || el.querySelector('button, input'))?.focus({ preventScroll: true })
  }, [])
  // Pencere içeriği portal'la bir SONRAKİ çizimde bağlanır: son değer ref'te tutulur, bağlanınca ref geri çağrısı uygular.
  useLayoutEffect(() => {
    describedByRef.current = describedBy
    setDescribedBy(dateTrigger(dateWrap.current), describedBy)
  }, [describedBy])

  async function save() {
    if (!canSave || inflight.current) return
    inflight.current = true; setBusy('save')
    try {
      const r = plan ? await plan(date, note) : await api.forecastPlan(row.domain, date, note)
      if (r?.success) { toast.success(t('forecast.planSaved', row.domain)); onSaved(r.data) }
      else toast.error(r?.error || t('forecast.planError'))
    } catch (e) { toast.error(e?.message || t('forecast.planError')) }
    finally { inflight.current = false; setBusy(null) }
  }

  async function clear() {
    if (inflight.current) return
    const ok = await showConfirm({
      title: t('forecast.planClearTitle'),
      message: t('forecast.planClearMsg', row.domain, fmt(saved)),
      confirmText: t('forecast.planClear'),
      cancelText: t('forecast.planKeep'),
      variant: 'danger',
    })
    if (!ok || inflight.current) return
    inflight.current = true; setBusy('clear')
    try {
      const r = unplan ? await unplan() : await api.forecastUnplan(row.domain)
      if (r?.success) { toast.success(t('forecast.planCleared', row.domain)); onCleared(r.data) }
      else toast.error(r?.error || t('forecast.planError'))
    } catch (e) { toast.error(e?.message || t('forecast.planError')) }
    finally { inflight.current = false; setBusy(null) }
  }

  /** Tüm kapatma yolları (İptal, X, Escape, örtü) buradan: kaydedilmemiş değişiklik varsa önce sorar. */
  async function requestClose() {
    if (inflight.current || confirming.current) return
    if (!dirty) { onClose(); return }
    confirming.current = true
    try {
      const ok = await showConfirm({
        title: t('forecast.planDiscardTitle'),
        message: t('forecast.planDiscardMsg'),
        confirmText: t('forecast.planDiscardConfirm'),
        cancelText: t('forecast.planKeepEditing'),
        variant: 'warning',
      })
      if (ok) onClose()
    } finally { confirming.current = false }
  }

  function onKeyDown(e) {
    if (e.key === 'Enter' && (e.ctrlKey || e.metaKey) && !e.defaultPrevented) {
      e.preventDefault()
      save()
    }
  }

  // ── Önemli tarihler kartı ──
  const dateRows = []
  if (saved) {
    dateRows.push({ kind: 'saved', label: t('forecast.planSavedLabel'), date: saved,
      sub: row.renewal_planned_by ? t('forecast.planSetBy', row.renewal_planned_by) : null,
      tone: saved < today ? 'warning' : 'ok' })
  }
  if (renewBy) {
    dateRows.push({ kind: 'renewBy', label: t('forecast.planRenewByLabel'), date: renewBy, tone: renewBy < today ? 'warning' : 'ok' })
  }
  if (expiry) {
    const left = dayDiff(today, expiry)
    dateRows.push({ kind: 'expiry', label: left < 0 ? t('forecast.planExpiredLabel') : t('forecast.planExpiresLabel'),
      date: expiry, tone: left < 0 ? 'critical' : daysTone(left) })
  }

  const mod = modKeyLabel()

  return (
    <ModalShell open onClose={requestClose} busy={!!busy} size="sm" scrollBody icon={CalendarPlus}
      className={SHEET_ON_PHONE}
      title={<span className="min-w-0 [overflow-wrap:anywhere]">{t('forecast.planTitle', row.domain)}</span>}
      footer={(
        <div data-slot="plan-footer" className="flex w-full flex-col gap-2 sm:flex-row sm:items-center" onKeyDown={onKeyDown}>
          {saved && (
            <Button type="button" variant="outline" onClick={clear} disabled={!!busy} aria-busy={busy === 'clear' || undefined}
              className="min-h-10 border-destructive/30 text-destructive hover:bg-destructive/10 hover:text-destructive sm:mr-auto sm:pointer-fine:min-h-9 dark:border-destructive/40 dark:hover:bg-destructive/20">
              {busy === 'clear' ? <Spinner decorative className="size-4" /> : <Trash2 aria-hidden="true" />}
              {t('forecast.planClear')}
            </Button>
          )}
          <div className="grid grid-cols-2 gap-2 sm:ml-auto sm:flex">
            <Button type="button" variant="outline" onClick={requestClose} disabled={!!busy} className="min-h-10 sm:pointer-fine:min-h-9">
              {t('inv.cancel')}
            </Button>
            <Button type="button" onClick={save} disabled={!canSave} aria-busy={busy === 'save' || undefined}
              aria-keyshortcuts="Control+Enter Meta+Enter" className="min-h-10 sm:pointer-fine:min-h-9">
              {busy === 'save' ? <Spinner decorative className="size-4" /> : <CalendarCheck2 aria-hidden="true" />}
              {t('forecast.planSave')}
              <KbdGroup aria-hidden="true" className="ml-0.5 hidden md:inline-flex">
                <Kbd className="bg-primary-foreground/15 text-primary-foreground">{mod}</Kbd>
                <Kbd className="bg-primary-foreground/15 text-primary-foreground">↵</Kbd>
              </KbdGroup>
            </Button>
          </div>
        </div>
      )}>
      <div data-slot="renewal-plan" className="flex flex-col gap-5 pb-1" onKeyDown={onKeyDown}>
        {(dateRows.length > 0 || timeline) && (
          <section data-slot="plan-summary" className="rounded-lg border bg-muted/40 p-3 sm:p-4">
            {dateRows.length > 0 && (
              <dl className="grid grid-cols-[auto_minmax(0,1fr)_auto] items-center gap-x-3 gap-y-2.5 text-sm">
                {dateRows.map((r) => (
                  <div key={r.kind} data-slot="plan-date-row" data-kind={r.kind} className="contents">
                    <dt className="text-muted-foreground">{r.label}</dt>
                    <dd className="min-w-0 font-medium tabular-nums">
                      {fmt(r.date)}
                      {r.sub && <span className="block truncate text-xs font-normal text-muted-foreground" title={r.sub}>{r.sub}</span>}
                    </dd>
                    <dd className="justify-self-end">
                      <Badge variant={BADGE_VARIANT[r.tone]} className="tabular-nums">{relToday(dayDiff(today, r.date))}</Badge>
                    </dd>
                  </div>
                ))}
              </dl>
            )}
            {timeline && (
              <PlanTimeline model={timeline} plannedLabel={date ? fmt(date, { year: false }) : null}
                todayLabel={t('forecast.planTodayLabel')} expiryLabel={t('forecast.planExpiresLabel')}
                renewByLabel={t('forecast.planRenewByLabel')} className={dateRows.length > 0 ? 'mt-4' : ''} />
            )}
          </section>
        )}

        <div className="flex flex-col gap-2">
          {/* Tarih: proje geneli ui/DateTimeField (gün kipi, yyyy-MM-dd). Kimlik prop'u almadığı için etiket seçiciyi
              SARAR (etiket ↔ tetik bağı). Takvim body'ye portal'lanır (ModalShell modal değil → tıklanabilir). */}
          <label ref={attachDateWrap} className="flex flex-col gap-1.5 text-sm font-semibold">
            <span>{t('forecast.planDate')}</span>
            <DateTimeField dateOnly value={date} onChange={setDate} />
          </label>
          {relation && <p id={relId} data-slot="plan-relation" className="text-xs text-muted-foreground">{relation}</p>}

          {picks.length > 0 && (
            <div className="mt-1 flex flex-col gap-1.5">
              <span id={`${uid}-quick`} className="text-xs font-medium text-muted-foreground">{t('forecast.planQuick')}</span>
              <ToggleGroup type="single" variant="outline" spacing={2} value={activePick} aria-labelledby={`${uid}-quick`}
                data-slot="plan-quick" disabled={!!busy}
                onValueChange={(v) => { const p = picks.find((x) => x.key === v); if (p) setDate(p.date) }}
                className="grid w-full grid-cols-[repeat(auto-fit,minmax(6.5rem,1fr))]">
                {picks.map((p) => (
                  <ToggleGroupItem key={p.key} value={p.key} data-pick={p.key}
                    className="h-auto min-h-11 w-full flex-col gap-0.5 px-2 py-1.5 whitespace-normal data-[state=on]:border-primary/60 data-[state=on]:bg-primary/10 sm:min-h-10">
                    <span className="text-xs leading-tight font-medium sm:text-[13px]">{t(PICK_LABEL[p.key])}</span>
                    {' '}
                    <span className="text-[11px] leading-tight font-normal text-muted-foreground tabular-nums">{fmt(p.date, { year: false })}</span>
                  </ToggleGroupItem>
                ))}
              </ToggleGroup>
            </div>
          )}

          {(warnings.length > 0 || off) && (
            <div className="mt-1 flex flex-col gap-2">
              {warnings.map((w) => (
                <AlertBanner key={w.kind} tone={WARN_TONE[w.kind]} className="mb-0">
                  <p id={warnId(w.kind)} data-slot="plan-warning" data-kind={w.kind}>{w.text}</p>
                </AlertBanner>
              ))}
              {off && (
                <AlertBanner tone="warning" className="mb-0">
                  <p id={warnId('offday')} data-slot="plan-warning" data-kind="offday">
                    {isWeekend(date) ? t('forecast.planWeekend', fmt(date)) : t('forecast.planHoliday', fmt(date))}
                    {' '}
                    {off.direction === 'before' ? t('forecast.planOffDayBefore', fmt(off.date)) : t('forecast.planOffDayAfter', fmt(off.date))}
                  </p>
                  <Button type="button" variant="outline" size="sm" disabled={!!busy} onClick={() => setDate(off.date)}
                    className="mt-1 min-h-10 sm:pointer-fine:min-h-8">
                    {t('forecast.planMoveTo', fmt(off.date, { year: false }))}
                  </Button>
                </AlertBanner>
              )}
            </div>
          )}
        </div>

        <Field label={t('forecast.planNote')} className="mb-0"
          hint={(
            <span className="flex items-baseline justify-between gap-3">
              <span>{t('forecast.planNoteOptional')}</span>
              <span data-slot="plan-note-counter" className={cn('tabular-nums', note.length >= NOTE_WARN_AT && 'font-medium text-amber-700 dark:text-amber-400')}>
                <span aria-hidden="true">{note.length}/{NOTE_MAX}</span>
                <span className="sr-only">{t('forecast.planNoteCountSr', note.length, NOTE_MAX)}</span>
              </span>
            </span>
          )}>
          {({ id, describedBy: noteDescribedBy }) => (
            <Textarea id={id} rows={3} maxLength={NOTE_MAX} value={note} disabled={!!busy} aria-describedby={noteDescribedBy}
              onChange={(e) => setNote(e.target.value)} placeholder={t('forecast.planNotePh')} className="min-h-20" />
          )}
        </Field>

        {/* hint: çağıran yüzey açıklamayı değiştirebilir (alan adında "yeni sertifika" anlamsız — 2026-09-22, H) */}
        <p data-slot="plan-hint" className="flex gap-2 text-xs leading-relaxed text-muted-foreground">
          <Info aria-hidden="true" className="mt-0.5 size-3.5 shrink-0" />
          <span className="min-w-0">{hint || t('forecast.planIntroCert')}</span>
        </p>
      </div>
    </ModalShell>
  )
}

const PICK_LABEL = {
  renewBy: 'forecast.planPickRenewBy',
  beforeExpiry: 'forecast.planPickBeforeExpiry',
  nextWorkingDay: 'forecast.planPickNextDay',
  week: 'forecast.planPickWeek',
  twoWeeks: 'forecast.planPickTwoWeeks',
}

/**
 * Bugün → bitiş şeridi. Görsel özet; metin karşılığı hemen üstteki tarih listesi ve seçilen günün göreli açıklaması
 * (aynı bilgiler) olduğu için ekran okuyucudan gizlidir. Etiketler `translateX(-p%)` ile yaslanır: %0'da sola, %100'de
 * sağa dayanır, ortada ortalanır — dar ekranda kutudan taşmaz. renew-by etiketi uçlara çok yakınsa (bugün/bitiş
 * etiketiyle çakışır) çizilmez; çentik ve tarih listesi yine gösterir.
 */
function PlanTimeline({ model, plannedLabel, todayLabel, expiryLabel, renewByLabel, className }) {
  const { renewBy, planned, state } = model
  const anchored = (p) => ({ left: `${p}%`, transform: `translateX(-${p}%)` })
  return (
    <div aria-hidden="true" data-slot="plan-timeline" data-state={state || undefined} className={cn('select-none', className)}>
      <div className="relative h-4">
        {planned != null && plannedLabel && (
          <span className={cn('absolute top-0 text-[11px] leading-4 font-semibold whitespace-nowrap tabular-nums transition-[left,transform] duration-150 motion-reduce:transition-none',
            state === 'ok' ? 'text-primary' : state === 'afterExpiry' || state === 'past' ? 'text-destructive' : 'text-amber-700 dark:text-amber-400')}
            style={anchored(planned)}>{plannedLabel}</span>
        )}
      </div>
      <div className="relative mt-1 h-2">
        {/* Katmanlar: zemin şeridi → geç yenileme penceresi (renew-by → bitiş) → bugünden seçilen güne dolgu (proje
            ProgressBar'ı, zemini saydam; dolgu `--pg-fill` ile plan durumunun tonunda). Dolgu EN ÜSTTE: yarı saydam iki
            renk üst üste binip bulanık bir ara ton üretmesin. */}
        <div className="absolute inset-0 rounded-full bg-foreground/10" />
        {renewBy != null && <div className="absolute inset-y-0 right-0 rounded-r-full bg-amber-500/25" style={{ left: `${renewBy}%` }} />}
        <ProgressBar value={planned ?? 0} decorative className={cn('bg-transparent', FILL_TONE[state] || FILL_TONE.ok)} />
        {renewBy != null && (
          <span data-slot="plan-timeline-renewby" className="absolute -top-1 h-4 w-0.5 -translate-x-1/2 rounded-full bg-amber-600 dark:bg-amber-400" style={{ left: `${renewBy}%` }} />
        )}
        {planned != null && (
          <span data-slot="plan-timeline-planned"
            className={cn('absolute top-1/2 size-3.5 -translate-x-1/2 -translate-y-1/2 rounded-full border-2 border-background shadow-sm transition-[left] duration-150 motion-reduce:transition-none', DOT_TONE[state] || DOT_TONE.ok)}
            style={{ left: `${planned}%` }} />
        )}
      </div>
      <div className="relative mt-1.5 h-4 text-[11px] leading-4 text-muted-foreground">
        <span className="absolute left-0">{todayLabel}</span>
        {renewBy != null && renewBy > 22 && renewBy < 78 && (
          <span className="absolute whitespace-nowrap text-amber-700 dark:text-amber-400" style={anchored(renewBy)}>{renewByLabel}</span>
        )}
        <span className="absolute right-0">{expiryLabel}</span>
      </div>
    </div>
  )
}
