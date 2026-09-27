import { useCallback, useEffect, useId, useRef, useState } from 'react'
import { createPortal } from 'react-dom'
import { PhoneCall, Trash2, CalendarClock, X, ExternalLink, RotateCcw, Headset, UserRound } from 'lucide-react'
import { api } from '../../../api/client'
import { useT, useDateLocale } from '../../../i18n/index.jsx'
import { useToast } from '../../ui/Toast.jsx'
import { useDialog } from '../../ui/Dialog.jsx'
import Field from '../../ui/Field.jsx'
import DateTimeField from '../../ui/DateTimeField.jsx'
import SimpleTooltip from '../../ui/SimpleTooltip.jsx'
import StatusBlock from '../../ui/StatusBlock.jsx'
import AlertBanner from '../../ui/AlertBanner.jsx'
import { Spinner } from '../../ui/Progress.jsx'
import { useVisibleInterval } from '../../../hooks/useVisibleInterval.js'
import { Button } from '@/components/shadcn/button'
import { Badge } from '@/components/shadcn/badge'
import { Input } from '@/components/shadcn/input'
import { Textarea } from '@/components/shadcn/textarea'
import { Kbd } from '@/components/shadcn/kbd'
import { NativeSelect, NativeSelectOption } from '@/components/shadcn/native-select'
import { ToggleGroup, ToggleGroupItem } from '@/components/shadcn/toggle-group'
import { Skeleton } from '@/components/shadcn/skeleton'
import { Sheet, SheetContent, SheetHeader, SheetTitle, SheetDescription, SheetClose } from '@/components/shadcn/sheet'
import { cn } from '@/lib/utils'
import NocPersonPicker from './NocPersonPicker.jsx'
import { AlertLevelBadge, AlertTypeIcon } from './AlertBadges.jsx'
import {
  OUTCOMES, CHANNELS, QUICK_TIMES, DEFAULT_CHANNEL, NOTE_MAX, outcomeMeta, channelMeta, relTime, clockTime, fullTime,
  canDeleteNow, defaultPerson, validateCall, buildBody, summarizeCalls, toIso,
} from './nocCallModel.js'
import { navigateTo } from '../../../utils/navigate.js'
import { usePermissions } from '../../../contexts/PermissionsProvider.jsx'

/**
 * 7/24 ARAMA KAYDI arayüzü (2026-09-27; CONTRACT.md "Arama kaydı") — uyarının üzerinden "kim, ne zaman arandı, sonuç, not".
 *
 * Parçalar: `NocCallSection` (uyarı detayındaki bölüm: zaman çizelgesi + izni olana hızlı giriş formu),
 * `NocCallQuickSheet` (telefonda alttan açılan hızlı giriş), `NocCallIndicator` (liste satırı/kart rozeti),
 * `NocCallSummary` (Olaylar detayında salt okunur özet + bağlantı). Tüm yükler sıra-numaralı (seq-ref) — bayat yanıt
 * yeni uyarının listesini ezmez; durum güncellemeleri işlevsel. Telefon numarası HİÇ gösterilmez (`has_phone`).
 * Sol renk şeridi YOK: sonuç rozetle (ikon + metin), koyu tema `dark:` karşılıklarıyla.
 */

/** Kayıtları en yeni önce sıralar (aynı anda son eklenen önce) — sunucu sırasıyla aynı. */
function sortCalls(list) {
  return [...(Array.isArray(list) ? list : [])].sort((a, b) => {
    const d = String(b.contacted_at ?? '').localeCompare(String(a.contacted_at ?? ''))
    return d !== 0 ? d : Number(b.id ?? 0) - Number(a.id ?? 0)
  })
}

/** "Şimdi" — göreli zamanlar ve silme penceresi için 30 sn'de bir (gizli sekmede durur). */
function useNow(active = true) {
  const [nowMs, setNowMs] = useState(() => Date.now())
  useVisibleInterval(() => setNowMs(Date.now()), active ? 30_000 : 0, false)
  return nowMs
}

/**
 * Uyarının arama kayıtları: yükle (seq-ref), yerel ekle/çıkar. `onChange(summary)` yalnız YEREL değişiklikte çağrılır
 * (liste göstergesi yeniden yüklemeden güncellensin) — ilk yüklemede değil.
 */
export function useNocCalls(alertId, { onChange } = {}) {
  const [state, setState] = useState({ loading: true, error: null, calls: [] })
  const seq = useRef(0)
  const dirty = useRef(false)
  const onChangeRef = useRef(onChange)
  onChangeRef.current = onChange
  // Bu uyarıdaki YEREL ekle/çıkar günlüğü: uçuştaki yükleme dönünce sunucu satırlarıyla KİMLİKLE birleşir. Eskiden
  // ekleme yüklemeyi geçersiz kılıyordu (seq++) → derin bağlantıda ilk liste gelmeden kaydeden operatör yalnız yeni
  // satırı görüyor, liste göstergesi "1 arama"ya düşüyordu.
  const local = useRef({ added: new Map(), dropped: new Set() })
  const merge = (rows) => {
    const { added, dropped } = local.current
    const byId = new Map((Array.isArray(rows) ? rows : []).map((r) => [String(r.id), r]))
    for (const [id, r] of added) if (!byId.has(id)) byId.set(id, r)
    return sortCalls([...byId.values()].filter((r) => !dropped.has(String(r.id))))
  }

  const load = useCallback(async () => {
    if (alertId == null) return
    const my = ++seq.current
    setState((s) => ({ ...s, loading: true, error: null }))
    let res
    try { res = await api.nocCalls.list(alertId) } catch (e) { res = { success: false, error: e?.message } }
    if (my !== seq.current) return
    if (res?.success) {
      // Yerel değişiklik birleştiyse gösterge BİRLEŞMİŞ özetle güncellensin (yalnız yeni satırın "1"i kalmasın)
      if (local.current.added.size || local.current.dropped.size) dirty.current = true
      setState({ loading: false, error: null, calls: merge(res.data) })
    } else setState((s) => ({ ...s, loading: false, error: res?.error || 'error' }))
  }, [alertId])

  useEffect(() => {
    local.current = { added: new Map(), dropped: new Set() }   // yeni uyarı → temiz günlük
    load()
    return () => { seq.current += 1 }   // uyarı değişti / kapandı → uçuştaki yanıt uygulanmaz
  }, [load])

  const add = useCallback((row) => {
    if (!row) return
    dirty.current = true
    local.current.added.set(String(row.id), row); local.current.dropped.delete(String(row.id))
    setState((s) => ({ ...s, loading: false, calls: sortCalls([row, ...s.calls.filter((c) => c.id !== row.id)]) }))
  }, [])
  const drop = useCallback((id) => {
    dirty.current = true
    local.current.dropped.add(String(id)); local.current.added.delete(String(id))
    setState((s) => ({ ...s, calls: s.calls.filter((c) => c.id !== id) }))
  }, [])

  useEffect(() => {
    if (!dirty.current) return
    dirty.current = false
    onChangeRef.current?.(summarizeCalls(state.calls))
  }, [state.calls])

  return { ...state, reload: load, add, drop }
}

/** Arama seçicisi kişileri (yalnız yazabilene; seq-ref). */
function useNocContacts(alertId, enabled) {
  const [state, setState] = useState({ loading: enabled, contacts: [], failed: false })
  useEffect(() => {
    if (!enabled || alertId == null) return undefined
    let alive = true
    setState((s) => ({ ...s, loading: true, failed: false }))
    Promise.resolve().then(() => api.nocCalls.contacts(alertId)).catch(() => null).then((res) => {
      if (!alive) return
      setState({ loading: false, contacts: res?.success && Array.isArray(res.data) ? res.data : [], failed: !res?.success })
    })
    return () => { alive = false }
  }, [alertId, enabled])
  return state
}

// ── Rozetler ────────────────────────────────────────────────────────────────

export function NocOutcomeBadge({ outcome, className }) {
  const t = useT()
  const m = outcomeMeta(outcome)
  if (!m) return <Badge variant="outline" className={className}>{outcome || '—'}</Badge>
  return (
    <Badge variant="outline" data-slot="noc-outcome" data-outcome={outcome} className={cn('gap-1 font-semibold', m.badge, className)}>
      <m.Icon aria-hidden="true" />{t(m.labelKey)}
    </Badge>
  )
}

/**
 * Liste satırı / kart göstergesi: "📞 2 arama · son: Ulaşıldı 03:12 · Kişi A" — ikonlu Badge (emoji değil). Kayıt yoksa
 * çizilmez. Uzun ad kırpılır (tam metin `title`'da).
 */
export function NocCallIndicator({ count, last, className }) {
  const t = useT()
  const locale = useDateLocale()
  const n = Number(count ?? 0)
  if (!(n > 0)) return null
  const m = outcomeMeta(last?.outcome)
  const time = clockTime(last?.contacted_at, locale)
  const lastText = last ? [m ? t(m.labelKey) : last.outcome, time, last.contacted_name].filter(Boolean).join(' · ') : ''
  const countText = n === 1 ? t('nocCall.indicator.one') : t('nocCall.indicator.count', n)
  const full = last ? `${countText} · ${t('nocCall.indicator.last')}: ${lastText}` : countText
  return (
    <Badge variant="outline" data-slot="noc-call-indicator" data-count={n} title={full}
      className={cn('max-w-full min-w-0 gap-1 font-normal', className)}>
      <PhoneCall aria-hidden="true" className="text-muted-foreground" />
      <span className="font-semibold tabular-nums">{countText}</span>
      {last && (
        <span className="truncate">
          <span aria-hidden="true" className="text-muted-foreground"> · </span>
          <span className="text-muted-foreground">{t('nocCall.indicator.last')}:</span>{' '}
          {m && <m.Icon aria-hidden="true" className={cn('inline size-3', m.ink)} />}{' '}
          {lastText}
        </span>
      )}
    </Badge>
  )
}

// ── Zaman çizelgesi ─────────────────────────────────────────────────────────

/** Kayıt listesi, en yeni önce. Silme düğmesi yalnız pencere açıkken (sunucu `can_delete` + `delete_until`). */
export function NocCallTimeline({ calls, nowMs, onDelete, deleting, compact = false }) {
  const t = useT()
  const locale = useDateLocale()
  return (
    <ol data-slot="noc-call-timeline" className="m-0 flex list-none flex-col gap-2 p-0">
      {calls.map((c) => {
        const ch = channelMeta(c.channel)
        const deletable = onDelete && canDeleteNow(c, nowMs)
        const clock = clockTime(c.contacted_at, locale)
        return (
          <li key={c.id} data-slot="noc-call-entry" data-call-id={c.id} data-outcome={c.outcome}
            className="min-w-0 rounded-lg border bg-card px-3 py-2.5">
            <div className="flex min-w-0 items-start gap-2">
              <div className="flex min-w-0 flex-1 flex-col gap-1">
                <div className="flex min-w-0 flex-wrap items-center gap-x-2 gap-y-1">
                  <span className="inline-flex min-w-0 items-center gap-1.5 text-sm font-semibold">
                    <UserRound aria-hidden="true" className="size-4 shrink-0 text-muted-foreground" />
                    <span className="[overflow-wrap:anywhere]">{c.contacted_name || '—'}</span>
                  </span>
                  <NocOutcomeBadge outcome={c.outcome} />
                </div>
                <div className="flex min-w-0 flex-wrap items-center gap-x-2 gap-y-0.5 text-xs text-muted-foreground">
                  <SimpleTooltip content={fullTime(c.contacted_at, locale)}>
                    <time dateTime={c.contacted_at ? `${c.contacted_at}Z` : undefined} className="tabular-nums">
                      {relTime(c.contacted_at, nowMs, t)}{clock && <> · {clock}</>}
                    </time>
                  </SimpleTooltip>
                  <span aria-hidden="true">·</span>
                  <span className="inline-flex items-center gap-1"><ch.Icon aria-hidden="true" className="size-3.5" />{t(ch.labelKey)}</span>
                </div>
                {c.note && (
                  <p data-slot="noc-call-note" className={cn('m-0 text-sm whitespace-pre-wrap [overflow-wrap:anywhere]', compact && 'line-clamp-2')}>{c.note}</p>
                )}
                {/* Kaydeden ayrı satırda: dar ekranda sarınca "·" ayırıcısı satır sonunda asılı kalmasın */}
                {!compact && c.created_by_name && (
                  <span className="inline-flex min-w-0 items-center gap-1 text-[11px] text-muted-foreground">
                    <Headset aria-hidden="true" className="size-3 shrink-0" />
                    <span className="truncate">{t('nocCall.loggedBy', c.created_by_name)}</span>
                  </span>
                )}
              </div>
              {deletable && (
                <Button type="button" variant="ghost" size="icon" disabled={deleting === c.id}
                  aria-label={t('nocCall.deleteFor', c.contacted_name || '—', clock)} title={t('nocCall.delete')}
                  className="-mt-1 -mr-1.5 size-9 shrink-0 text-muted-foreground hover:text-destructive pointer-coarse:size-10"
                  onClick={() => onDelete(c)}>
                  {deleting === c.id ? <Spinner decorative size={16} /> : <Trash2 aria-hidden="true" />}
                </Button>
              )}
            </div>
          </li>
        )
      })}
    </ol>
  )
}

// ── Hızlı giriş formu ───────────────────────────────────────────────────────

const isMac = () => { try { return /Mac|iPhone|iPad/.test(navigator.platform || navigator.userAgent || '') } catch { return false } }

/**
 * Hızlı arama kaydı formu — telefonda ve masaüstünde hızlı: kişi (arama listesinin ilki önseçili), sonuç büyük çiplerle
 * tek dokunuş, kanal varsayılan Telefon, zaman varsayılan "Şimdi" (+5/15 dk önce, başka zaman için tarih-saat seçici),
 * not (sayaçlı). Ctrl/⌘+Enter kaydeder; kaydedince form sıfırlanır ama KİŞİ KALIR, odak sonuç çiplerine döner.
 *
 * @param focusKey  0'dan farklı ve değişince form görünüme kaydırılır ve sonuç çipleri odaklanır (derin bağlantı / "Arama kaydet").
 */
export function NocCallForm({ alert: a, onSaved, focusKey = 0, className, bare = false }) {
  const t = useT()
  const toast = useToast()
  const uid = useId()
  const { loading: contactsLoading, contacts } = useNocContacts(a?.id, true)
  const [person, setPerson] = useState(null)
  const personTouched = useRef(false)
  const [outcome, setOutcome] = useState('')
  const [channel, setChannel] = useState(DEFAULT_CHANNEL)
  const [time, setTime] = useState({ mode: 'now', custom: '' })
  const [note, setNote] = useState('')
  const [errors, setErrors] = useState({})
  const [busy, setBusy] = useState(false)
  const rootRef = useRef(null)
  const outcomeRef = useRef(null)
  const otherRef = useRef(null)
  const alive = useRef(true)
  // StrictMode (geliştirme) efekti bağla-çöz-bağla koşar: yalnız temizlikte false yazmak ref'i KALICI false bırakırdı
  // (kayıt sonrası kod hiç çalışmaz, düğme "Kaydediliyor…"da asılı kalırdı — tarayıcıda yakalandı, jsdom'da görünmez).
  useEffect(() => { alive.current = true; return () => { alive.current = false } }, [])

  // Kişiler gelince arama listesinin ilki önseçili — kullanıcı seçim yaptıysa ezilmez.
  useEffect(() => {
    if (personTouched.current) return
    setPerson(defaultPerson(contacts))
  }, [contacts])

  const focusOutcome = useCallback(() => {
    const root = outcomeRef.current
    const el = root?.querySelector('[role="radio"][data-state="on"]') || root?.querySelector('[role="radio"]')
    el?.focus({ preventScroll: true })
  }, [])

  useEffect(() => {
    if (!focusKey) return undefined
    const id = setTimeout(() => {
      // Bölüm başlığı da görünsün: formun değil, bölümün başına kaydır (yoksa başlık Sheet başlığının altında kalıyordu).
      const target = rootRef.current?.closest('[data-slot="noc-call-section"]') || rootRef.current
      try { target?.scrollIntoView({ block: 'start', behavior: 'smooth' }) } catch { /* jsdom */ }
      focusOutcome()
    }, 60)
    return () => clearTimeout(id)
  }, [focusKey, focusOutcome])

  const pickPerson = (p) => {
    personTouched.current = true
    setPerson(p)
    setErrors((e) => ({ ...e, person: undefined }))
    if (p?.kind === 'other') setTimeout(() => otherRef.current?.focus(), 30)
  }

  async function submit() {
    if (busy) return
    const nowMs = Date.now()
    const form = { person, outcome, channel, time, note }
    const errs = validateCall(form, { alertCreatedAt: a?.created_at, nowMs })
    setErrors(errs)
    if (Object.keys(errs).length > 0) {
      if (errs.outcome) focusOutcome()
      else if (errs.person && person?.kind === 'other') otherRef.current?.focus()
      return
    }
    setBusy(true)
    let res
    // Bayrak finally'de (kapı busyFlagFinally); söküldüyse state yazılmaz (StrictMode'da "Kaydediliyor…" takılması).
    try { res = await api.nocCalls.create(a.id, buildBody(form, nowMs)) } catch (e) { res = { success: false, error: e?.message } } finally { if (alive.current) setBusy(false) }
    if (!alive.current) return
    if (res?.success) {
      toast.success(t('nocCall.saved'))
      onSaved?.(res.data)
      // Sonraki kayıt için sıfırla — KİŞİ kalır (aynı kişiyi tekrar arama sık), odak sonuç çiplerine.
      setOutcome('')
      setChannel(DEFAULT_CHANNEL)
      setTime({ mode: 'now', custom: '' })
      setNote('')
      setErrors({})
      setTimeout(focusOutcome, 0)
    } else {
      toast.error(res?.error || t('nocCall.saveError'))
    }
  }

  const onKeyDown = (e) => {
    if ((e.ctrlKey || e.metaKey) && e.key === 'Enter') { e.preventDefault(); submit() }
  }

  const nowIso = toIso(Date.now())
  const err = (k) => (errors[k] ? t(errors[k]) : undefined)

  return (
    <div ref={rootRef} data-slot="noc-call-form" role="group" aria-labelledby={`${uid}-title`} onKeyDown={onKeyDown}
      className={cn('min-w-0 scroll-mt-4 rounded-lg border bg-muted/30 p-3 sm:p-4', className)}>
      {/* `bare` (telefon Sheet'i): başlık Sheet'in kendi başlığında — ikinci kez yazılmaz, ad yine bağlı kalır */}
      <h4 id={`${uid}-title`} className={cn('mb-3 flex items-center gap-2 text-sm font-semibold', bare && 'sr-only')}>
        <PhoneCall aria-hidden="true" className="size-4 text-primary" />{t('nocCall.form.title')}
      </h4>

      <div className="grid min-w-0 grid-cols-[minmax(0,1fr)_7.5rem] gap-x-3 sm:grid-cols-[minmax(0,1fr)_10rem]">
        <Field label={t('nocCall.person.label')} required error={err('person')} className="mb-3 min-w-0">
          {({ id, describedBy, invalid }) => (
            <div className="flex min-w-0 flex-col gap-2 [&_[role=combobox]]:h-10">
              <NocPersonPicker id={id} describedBy={describedBy} invalid={invalid} contacts={contacts} loading={contactsLoading}
                value={person} onChange={pickPerson} disabled={busy} />
              {person?.kind === 'other' && (
                <Input ref={otherRef} value={person.name} maxLength={200} disabled={busy}
                  aria-label={t('nocCall.person.otherName')} placeholder={t('nocCall.person.otherPh')}
                  aria-invalid={invalid} aria-describedby={describedBy} className="h-10"
                  onChange={(e) => { const v = e.target.value; setPerson({ kind: 'other', name: v }); setErrors((x) => ({ ...x, person: undefined })) }} />
              )}
            </div>
          )}
        </Field>
        <Field label={t('nocCall.channel.label')} className="mb-3 min-w-0">
          {({ id }) => (
            <div className="min-w-0 [&_[data-slot=native-select-wrapper]]:w-full">
              <NativeSelect id={id} value={channel} disabled={busy} onChange={(e) => setChannel(e.target.value)} className="h-10">
                {CHANNELS.map((c) => <NativeSelectOption key={c.value} value={c.value}>{t(c.labelKey)}</NativeSelectOption>)}
              </NativeSelect>
            </div>
          )}
        </Field>
      </div>

      <Field label={t('nocCall.outcome.label')} required error={err('outcome')} className="mb-3">
        {({ id, describedBy, invalid }) => (
          // Sarmalayıcı div ref'i taşır: shadcn ToggleGroup forwardRef DEĞİL (React 18 ref'i düşürür).
          <div ref={outcomeRef} className="min-w-0">
          <ToggleGroup id={id} type="single" variant="outline" spacing={2} value={outcome} disabled={busy}
            aria-label={t('nocCall.outcome.label')} aria-describedby={describedBy} aria-invalid={invalid}
            onValueChange={(v) => { if (v) { setOutcome(v); setErrors((e) => ({ ...e, outcome: undefined })) } }}
            className="grid w-full grid-cols-2 gap-2 sm:grid-cols-3">
            {OUTCOMES.map((o) => (
              <ToggleGroupItem key={o.value} value={o.value}
                className={cn('h-11 w-full min-w-0 justify-start gap-2 rounded-md bg-background px-3 text-left whitespace-normal', o.chip)}>
                <o.Icon aria-hidden="true" className="size-4" />
                <span className="min-w-0 leading-tight">{t(o.labelKey)}</span>
              </ToggleGroupItem>
            ))}
          </ToggleGroup>
          </div>
        )}
      </Field>

      <Field label={t('nocCall.time.label')} error={err('time')} className="mb-3">
        {({ id, describedBy }) => (
          <div className="flex min-w-0 flex-col gap-2">
            <ToggleGroup id={id} type="single" variant="outline" spacing={1} value={time.mode} disabled={busy}
              aria-label={t('nocCall.time.label')} aria-describedby={describedBy}
              onValueChange={(v) => { if (v) { setTime((x) => ({ mode: v, custom: v === 'custom' && !x.custom ? nowIso : x.custom })); setErrors((e) => ({ ...e, time: undefined })) } }}
              className="flex w-full flex-wrap gap-1.5">
              {QUICK_TIMES.map((q) => (
                <ToggleGroupItem key={q.key} value={q.key} className="h-10 rounded-md bg-background px-3 data-[state=on]:border-primary data-[state=on]:bg-primary/10 data-[state=on]:text-primary">
                  {t(q.labelKey)}
                </ToggleGroupItem>
              ))}
              <ToggleGroupItem value="custom" className="h-10 gap-1.5 rounded-md bg-background px-3 data-[state=on]:border-primary data-[state=on]:bg-primary/10 data-[state=on]:text-primary">
                <CalendarClock aria-hidden="true" />{t('nocCall.time.custom')}
              </ToggleGroupItem>
            </ToggleGroup>
            {time.mode === 'custom' && (
              <div className="max-w-xs [&_[data-slot=button]]:h-10">
                <DateTimeField value={time.custom} max={nowIso} disabled={busy}
                  onChange={(v) => { setTime({ mode: 'custom', custom: v }); setErrors((e) => ({ ...e, time: undefined })) }} />
              </div>
            )}
          </div>
        )}
      </Field>

      <Field label={t('nocCall.note.label')} error={err('note')} className="mb-3"
        hint={<span className="flex justify-between gap-2"><span>{t('nocCall.note.hint')}</span>
          <span data-slot="noc-note-count" className={cn('shrink-0 tabular-nums', note.length > NOTE_MAX && 'text-destructive')}>{note.length}/{NOTE_MAX}</span></span>}>
        {({ id, describedBy, invalid }) => (
          <Textarea id={id} value={note} rows={3} disabled={busy} aria-describedby={describedBy} aria-invalid={invalid}
            placeholder={t('nocCall.note.placeholder')} className="min-h-20 bg-background"
            onChange={(e) => { setNote(e.target.value); if (errors.note) setErrors((x) => ({ ...x, note: undefined })) }} />
        )}
      </Field>

      <div className="flex flex-wrap items-center justify-end gap-2">
        <span className="mr-auto inline-flex items-center gap-1 text-xs text-muted-foreground max-sm:hidden pointer-coarse:hidden">
          <Kbd>{isMac() ? '⌘' : 'Ctrl'}</Kbd><Kbd>↵</Kbd><span>{t('nocCall.form.shortcut')}</span>
        </span>
        <Button type="button" className="h-10 w-full sm:w-auto" onClick={submit} disabled={busy} aria-busy={busy || undefined}>
          {busy ? <Spinner decorative size={16} /> : <PhoneCall aria-hidden="true" />}
          {busy ? t('nocCall.form.saving') : t('nocCall.form.save')}
        </Button>
      </div>
    </div>
  )
}

// ── Uyarı detayındaki bölüm ─────────────────────────────────────────────────

/**
 * "7/24 Arama Kayıtları" — uyarı detayı (Sheet / ModalShell) içinde: başlık + sayı, izni olana hızlı giriş formu,
 * zaman çizelgesi (en yeni önce) ya da boş durum. Silme: kaydı giren 15 dk içinde / global yönetici (onaylı).
 */
export function NocCallSection({ alert: a, canWrite = false, focusKey = 0, onChanged }) {
  const t = useT()
  const toast = useToast()
  const { showConfirm } = useDialog()
  const calls = useNocCalls(a?.id, { onChange: (summary) => onChanged?.(a?.id, summary) })
  const nowMs = useNow(calls.calls.some((c) => c.can_delete) || calls.calls.length > 0)
  const [deleting, setDeleting] = useState(null)

  async function remove(row) {
    const ok = await showConfirm({
      title: t('nocCall.deleteTitle'), message: t('nocCall.deleteMsg', row.contacted_name || '—'),
      variant: 'danger', confirmText: t('nocCall.delete'), cancelText: t('nocCall.cancel'),
    })
    if (!ok) return
    setDeleting(row.id)
    let res
    // Bayrak finally'de temizlenir (kapı busyFlagFinally): toast/drop atsa bile düğme "siliniyor"da takılı kalmaz.
    try { res = await api.nocCalls.remove(a.id, row.id) } catch (e) { res = { success: false, error: e?.message } } finally { setDeleting(null) }
    if (res?.success) { calls.drop(row.id); toast.success(t('nocCall.deleted')) }
    else { toast.error(res?.error || t('nocCall.deleteError')); calls.reload() }
  }

  const n = calls.calls.length
  return (
    <section data-slot="noc-call-section" aria-labelledby={`noc-calls-${a?.id}`} className="mt-5 min-w-0">
      <h3 id={`noc-calls-${a?.id}`} className="mb-2 flex items-center gap-2 text-xs font-bold tracking-wide text-muted-foreground uppercase">
        <PhoneCall aria-hidden="true" className="size-3.5" />{t('nocCall.title')}
        {n > 0 && <Badge variant="secondary" className="h-5 rounded-full px-1.5 tabular-nums">{n}</Badge>}
      </h3>
      <div className="flex min-w-0 flex-col gap-3">
        {canWrite && <NocCallForm alert={a} focusKey={focusKey} onSaved={calls.add} />}
        {calls.error && (
          <AlertBanner tone="danger" className="mb-0" title={t('nocCall.loadError')}
            actions={<Button type="button" variant="outline" size="sm" onClick={calls.reload}><RotateCcw aria-hidden="true" />{t('nocCall.retry')}</Button>} />
        )}
        {calls.loading && n === 0 && !calls.error ? (
          <div aria-hidden="true" className="flex flex-col gap-2"><Skeleton className="h-16 w-full rounded-lg" /><Skeleton className="h-16 w-full rounded-lg" /></div>
        ) : n === 0 ? (!calls.error && (
          <StatusBlock tone="neutral" icon={PhoneCall} title={t('nocCall.empty')}
            description={canWrite ? t('nocCall.emptyWriter') : t('nocCall.emptyReader')}
            className="gap-2 rounded-lg border border-dashed px-4 py-5 text-[0.8em] md:px-4 md:py-5" />
        )) : (
          <NocCallTimeline calls={calls.calls} nowMs={nowMs} onDelete={remove} deleting={deleting} />
        )}
      </div>
    </section>
  )
}

// ── Telefon: alttan açılan hızlı giriş ──────────────────────────────────────

let scrollLocks = 0
let savedOverflow = ''

/**
 * Telefonda "Arama kaydet" — alttan açılan Sheet: uyarı özeti, hızlı form (sonuç çipleri odakta) ve son 3 kayıt;
 * "Tüm ayrıntılar" uyarı detayını açar. AlertDetailSheet ile aynı gerekçeyle `modal={false}` + kendi scrim'i (içinden
 * açılan seçici/onay pencereleri body'ye portal'lanır, tıklanabilir kalmalı); katman yüzen yardım düğmesinin üstünde.
 */
export function NocCallQuickSheet({ alert: a, onClose, onOpenDetail, onChanged }) {
  const t = useT()
  const calls = useNocCalls(a?.id, { onChange: (summary) => onChanged?.(a?.id, summary) })
  const nowMs = useNow(true)
  const [focusKey] = useState(1)
  useEffect(() => {
    if (scrollLocks === 0) { savedOverflow = document.body.style.overflow; document.body.style.overflow = 'hidden' }
    scrollLocks += 1
    return () => { scrollLocks -= 1; if (scrollLocks === 0) document.body.style.overflow = savedOverflow }
  }, [])
  if (!a) return null
  const recent = calls.calls.slice(0, 3)
  return (
    <Sheet open modal={false} onOpenChange={(next) => { if (!next) onClose?.() }}>
      {createPortal(
        <div data-slot="dialog-overlay" aria-hidden="true"
          className="fixed inset-0 z-[1000] bg-black/50 animate-in fade-in-0 motion-reduce:animate-none"
          onClick={(e) => { if (e.target === e.currentTarget) onClose?.() }} />,
        document.body,
      )}
      <SheetContent side="bottom" showCloseButton={false} data-slot="noc-call-sheet" data-alert-id={a.id} aria-modal="true"
        onInteractOutside={(e) => e.preventDefault()} onCloseAutoFocus={(e) => e.preventDefault()}
        className="z-[1001] flex max-h-[92dvh] flex-col gap-0 rounded-t-xl p-0">
        <SheetHeader className="shrink-0 flex-row items-start justify-between gap-3 border-b px-4 py-3 text-left">
          <div className="min-w-0">
            <SheetTitle className="flex items-center gap-2">
              <PhoneCall aria-hidden="true" className="size-4 text-primary" />{t('nocCall.logCall')}
            </SheetTitle>
            <SheetDescription className="mt-1 flex min-w-0 items-start gap-2">
              <AlertTypeIcon type={a.alert_type} className="shrink-0" />
              <span className="flex min-w-0 flex-wrap items-center gap-x-1.5 gap-y-1 pt-1">
                <span className="min-w-0 font-medium text-foreground [overflow-wrap:anywhere]">{a.domain}</span>
                <AlertLevelBadge level={a.alert_level} className="text-[0.75em]" />
              </span>
            </SheetDescription>
          </div>
          <SheetClose asChild>
            <Button type="button" variant="ghost" size="icon" className="-mt-1 -mr-2 size-10 shrink-0 text-muted-foreground" aria-label={t('app.close')}>
              <X aria-hidden="true" />
            </Button>
          </SheetClose>
        </SheetHeader>
        <div className="min-h-0 flex-1 overflow-y-auto px-4 pt-3 pb-[max(1rem,env(safe-area-inset-bottom))]">
          <NocCallForm alert={a} focusKey={focusKey} onSaved={calls.add} bare className="border-0 bg-transparent p-0 sm:p-0" />
          {recent.length > 0 && (
            <div className="mt-4">
              <h3 className="mb-2 text-xs font-bold tracking-wide text-muted-foreground uppercase">{t('nocCall.recent')}</h3>
              <NocCallTimeline calls={recent} nowMs={nowMs} compact />
            </div>
          )}
          {onOpenDetail && (
            <Button type="button" variant="outline" className="mt-4 h-10 w-full" onClick={() => onOpenDetail(a)}>
              <ExternalLink aria-hidden="true" />{t('nocCall.openDetail')}
            </Button>
          )}
        </div>
      </SheetContent>
    </Sheet>
  )
}

// ── Olaylar detayı: salt okunur özet ────────────────────────────────────────

/**
 * Olay (uyarı) detayında salt okunur özet: son 3 kayıt + sayı + "Alarm Geçmişi'nde aç". Matris anlık görüntüsünde
 * `noc_calls.write` varsa bağlantı `n_call=1` taşır — formu açıp açmamaya yine Alarm Geçmişi SUNUCUNUN `noc_can_write`'ıyla
 * karar verir (kapsamlı müdür matriste ADMIN görünür ama yazamaz → yalnız detay açılır). Etiket bu yüzden nötr.
 * Olaylar ekranı AYNI uyarı kimliklerini kullanır (alert_events).
 */
export function NocCallSummary({ alertId }) {
  const t = useT()
  const canWrite = usePermissions().canEdit('noc_calls.write')
  const calls = useNocCalls(alertId)
  const nowMs = useNow(calls.calls.length > 0)
  const n = calls.calls.length
  const href = `?tab=alerthistory&alert=${encodeURIComponent(alertId)}${canWrite ? '&n_call=1' : ''}`
  if (calls.loading && n === 0) return <Skeleton aria-hidden="true" className="mt-5 h-16 w-full rounded-lg" />
  if (calls.error) return null   // salt okunur özet: hata sessiz (detayın geri kalanını bozmasın)
  return (
    <section data-slot="noc-call-summary" aria-labelledby={`noc-sum-${alertId}`} className="mt-5 min-w-0">
      <h3 id={`noc-sum-${alertId}`} className="mb-2 flex items-center gap-2 text-xs font-bold tracking-wide text-muted-foreground uppercase">
        <PhoneCall aria-hidden="true" className="size-3.5" />{t('nocCall.title')}
        {n > 0 && <Badge variant="secondary" className="h-5 rounded-full px-1.5 tabular-nums">{n}</Badge>}
      </h3>
      {n === 0
        ? <p className="m-0 text-sm text-muted-foreground">{t('nocCall.empty')}</p>
        : <NocCallTimeline calls={calls.calls.slice(0, 3)} nowMs={nowMs} compact />}
      <Button asChild variant="outline" size="sm" className="mt-2 h-9 pointer-coarse:h-10">
        <a href={href} className="text-foreground no-underline"
          onClick={(e) => {
            if (e.button !== 0 || e.metaKey || e.ctrlKey || e.shiftKey || e.altKey) return   // yeni sekme: tarayıcıya bırak
            e.preventDefault()
            navigateTo('alerthistory', { alert: String(alertId), ...(canWrite ? { n_call: '1' } : {}) })
          }}>
          <ExternalLink aria-hidden="true" />{t('nocCall.openInHistory')}
        </a>
      </Button>
    </section>
  )
}

