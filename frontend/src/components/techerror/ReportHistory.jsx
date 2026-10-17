import { useCallback, useEffect, useRef, useState } from 'react'
import {
  CircleAlert, FilePlus2, Image as ImageIcon, ImageOff, ImagePlus, LayoutTemplate, MailCheck, PenLine, RefreshCw, Send, Timer, ShieldCheck } from 'lucide-react'
import { api } from '../../api/client'
import { useT } from '../../i18n/index.jsx'
import StatusBlock from '../ui/StatusBlock.jsx'
import { Button } from '@/components/shadcn/button'
import { Badge } from '@/components/shadcn/badge'
import { Skeleton } from '@/components/shadcn/skeleton'
import { Sheet, SheetContent, SheetDescription, SheetHeader, SheetTitle } from '@/components/shadcn/sheet'
import { cn } from '@/lib/utils'
import { fmtDateTime } from './TechErrorParts.jsx'
import { formatBytes, formatNumber, istanbulDay } from './techErrorModel.js'

const KIND_ICON = {
  CREATE: FilePlus2, VALUES: PenLine, IMAGE_ADD: ImagePlus, IMAGE_REPLACE: ImageIcon, IMAGE_DELETE: ImageOff,
  TEMPLATE_REFRESH: LayoutTemplate, SEND: Send, SEND_RESULT: MailCheck,
  // PO onayı (2026-10-17, S6)
  APPROVAL_REQUEST: ShieldCheck, APPROVAL: ShieldCheck,
}
const APPR_TONE = {
  PENDING: 'bg-amber-500/15 text-amber-800 dark:bg-amber-500/20 dark:text-amber-300',
  APPROVED: 'bg-green-600/10 text-green-800 dark:bg-green-500/15 dark:text-green-300',
  REJECTED: 'bg-destructive/10 text-destructive',
}
const SEND_TONE = {
  SENT: 'bg-green-600/10 text-green-800 dark:bg-green-500/15 dark:text-green-300',
  QUEUED: 'bg-primary/10 text-primary',
  SKIPPED: 'bg-amber-500/15 text-amber-800 dark:bg-amber-500/20 dark:text-amber-300',
  FAILED: 'bg-destructive/10 text-destructive',
  SUPERSEDED: 'bg-muted text-muted-foreground',
}
/** Sunucunun gönderilmeme sebebi kodları (TechErrorHistoryService.sendReason) — bilinmeyen kod gösterilmez. */
const SEND_REASONS = new Set(['MAIL_OFF', 'INACTIVE_RECIPIENTS', 'PDF_BUSY', 'PDF_TOO_LARGE', 'NO_RECIPIENT', 'APPROVAL_STALE'])
const FOLD_AT = 8

/** İstanbul saatiyle "HH:mm". */
function fmtTime(iso, lang) {
  const d = new Date(iso || '')
  if (Number.isNaN(d.getTime())) return '—'
  return new Intl.DateTimeFormat(lang === 'en' ? 'en-GB' : 'tr-TR', { hour: '2-digit', minute: '2-digit', timeZone: 'Europe/Istanbul' }).format(d)
}

function dayLabel(day, lang) {
  const d = new Date(`${day}T12:00:00Z`)
  if (Number.isNaN(d.getTime())) return day
  return new Intl.DateTimeFormat(lang === 'en' ? 'en-GB' : 'tr-TR', { day: 'numeric', month: 'long', year: 'numeric', timeZone: 'UTC' }).format(d)
}

/**
 * RAPOR DEĞİŞİKLİK GEÇMİŞİ (2026-10-14, Faz B): kim, ne zaman, hangi alanı neyden neye değiştirdi; görsel, şablon uygulama
 * ve gönderim olayları. Sağdan açılan panel (telefonda tam genişlik), gün başlıklarıyla gruplu, en yeni önce; "daha fazla"
 * anahtar kümesiyle eski olayları getirir. Yükleme hatası hiçbir zaman "değişiklik yok" diye gösterilmez. Bu özellikten
 * önce açılmış raporda geçmiş o andan başlar — sonda oluşturma / son kayıt bilgisi taban olarak gösterilir.
 */
export default function ReportHistorySheet({ reportId, open, onClose, uiLang, returnFocusRef }) {
  const t = useT()
  const [state, setState] = useState({ loading: false, error: null, events: [], hasMore: false, report: null, loaded: false })
  const [moreError, setMoreError] = useState(null)
  const [loadingMore, setLoadingMore] = useState(false)
  const seq = useRef(0)

  const load = useCallback(async () => {
    const my = ++seq.current
    // Önceki "daha eski" isteği geçersiz: meşgul bayrağı da sıfırlanır (inceleme 2026-10-14 — kapat / yeniden aç ya da
    // "Tekrar dene" sonrası düğme sonsuza dek meşgul kalıyordu)
    setLoadingMore(false)
    setMoreError(null)
    setState((s) => ({ ...s, loading: true, error: null }))
    try {
      const res = await api.techErrors.reportHistory(reportId)
      if (my !== seq.current) return
      if (res?.success && res.data) {
        setState({ loading: false, error: null, events: res.data.events || [], hasMore: !!res.data.has_more,
          report: res.data.report || null, capped: res.data.capped === true, maxEvents: res.data.max_events ?? null, loaded: true })
      } else {
        setState((s) => ({ ...s, loading: false, error: res?.error || t('te.hist.loadError') }))
      }
    } catch (e) {
      if (my !== seq.current) return
      setState((s) => ({ ...s, loading: false, error: e?.message || t('te.hist.loadError') }))
    }
  }, [reportId, t])

  useEffect(() => {
    if (open) load()
    else { seq.current++; setLoadingMore(false) }      // kapanınca geç gelen yanıt uygulanmaz
  }, [open, load])

  async function loadMore() {
    const last = state.events[state.events.length - 1]
    // Liste yeniden yüklenirken eski listenin son kimliğiyle istenmez (yeni ilk sayfa ile arada olay kaybolurdu)
    if (!last || loadingMore || state.loading) return
    const my = seq.current
    setLoadingMore(true)
    setMoreError(null)
    try {
      const res = await api.techErrors.reportHistory(reportId, last.id)
      if (my !== seq.current) return
      if (res?.success && res.data) {
        setState((s) => {
          const seen = new Set(s.events.map((e) => e.id))
          return { ...s, events: [...s.events, ...(res.data.events || []).filter((e) => !seen.has(e.id))], hasMore: !!res.data.has_more,
            capped: res.data.capped === true, maxEvents: res.data.max_events ?? s.maxEvents ?? null }
        })
      } else setMoreError(res?.error || t('te.hist.loadError'))
    } catch (e) {
      if (my === seq.current) setMoreError(e?.message || t('te.hist.loadError'))
    } finally {
      if (my === seq.current) setLoadingMore(false)
    }
  }

  const days = []
  for (const e of state.events) {
    const d = istanbulDay(e.at)
    if (!days.length || days[days.length - 1].day !== d) days.push({ day: d, items: [] })
    days[days.length - 1].items.push(e)
  }
  const reachedEnd = state.loaded && !state.hasMore
  const hasCreate = state.events.some((e) => e.kind === 'CREATE')

  return (
    <Sheet open={open} onOpenChange={(o) => { if (!o) onClose() }}>
      <SheetContent side="right" className="w-full gap-0 sm:max-w-lg" data-slot="te-history"
        onCloseAutoFocus={(e) => { if (returnFocusRef?.current) { e.preventDefault(); returnFocusRef.current.focus() } }}>
        <SheetHeader className="border-b pe-12">
          <SheetTitle>{t('te.hist.title')}</SheetTitle>
          <SheetDescription>{t('te.hist.desc')}</SheetDescription>
        </SheetHeader>
        <div className="flex min-h-0 flex-1 flex-col gap-4 overflow-y-auto p-4" data-slot="te-history-body">
          {state.loading && !state.loaded && (
            <div role="status" aria-live="polite" className="flex flex-col gap-3">
              <span className="sr-only">{t('te.loading')}</span>
              <Skeleton className="h-20 w-full rounded-lg motion-reduce:animate-none" />
              <Skeleton className="h-20 w-full rounded-lg motion-reduce:animate-none" />
            </div>
          )}
          {state.error && (
            <StatusBlock tone="danger" icon={CircleAlert} title={t('te.hist.loadErrorTitle')} description={state.error}
              actions={<Button type="button" variant="outline" onClick={load}>{t('te.retry')}</Button>} />
          )}
          {state.loaded && !state.error && state.events.length === 0 && (
            <p className="m-0 text-sm text-muted-foreground" data-slot="te-history-empty">{t('te.hist.empty')}</p>
          )}
          {/* Gün grupları başlıkla ayrılır, yer imi (region) değildir — 20 günlük geçmiş 20 yer imi eklerdi */}
          {days.map((g) => (
            <div key={g.day} className="flex flex-col gap-2">
              <h3 className="m-0 text-xs font-semibold tracking-wide text-muted-foreground uppercase">
                {dayLabel(g.day, uiLang)}
              </h3>
              <ol className="m-0 flex list-none flex-col gap-2 p-0">
                {g.items.map((e) => <EventItem key={e.id} event={e} uiLang={uiLang} />)}
              </ol>
            </div>
          ))}
          {state.hasMore && (
            <div className="flex flex-col items-start gap-2">
              <Button type="button" variant="outline" onClick={loadMore} disabled={loadingMore || state.loading}
                className="pointer-coarse:min-h-10" data-slot="te-history-more">
                {loadingMore && <RefreshCw aria-hidden="true" className="animate-spin motion-reduce:animate-none" />}
                {t('te.hist.more')}
              </Button>
              {moreError && <p className="m-0 text-sm text-destructive" role="alert">{moreError}</p>}
            </div>
          )}
          {reachedEnd && !state.error && !hasCreate && state.report && (
            <div className="rounded-lg border border-dashed p-3 text-sm text-muted-foreground" data-slot="te-history-baseline"
              data-capped={state.capped ? 'true' : undefined}>
              {/* Sınıra ulaşılmışsa en eskiler BUDANDI — "geçmiş bu özellikle başladı" denmez (inceleme 2026-10-14) */}
              <p className="m-0">{state.capped
                ? t('te.hist.capped', formatNumber(state.maxEvents ?? 500, uiLang))
                : t('te.hist.baseline')}</p>
              <p className="m-0 mt-1 [overflow-wrap:anywhere]">
                {t('te.hist.baselineCreated', fmtDateTime(state.report.created_at, uiLang), state.report.created_by_name || '—')}
              </p>
              {state.report.updated_at && (
                <p className="m-0 [overflow-wrap:anywhere]">
                  {t('te.hist.baselineUpdated', fmtDateTime(state.report.updated_at, uiLang), state.report.updated_by_name || '—')}
                </p>
              )}
            </div>
          )}
        </div>
      </SheetContent>
    </Sheet>
  )
}

function EventItem({ event: e, uiLang }) {
  const t = useT()
  const Icon = KIND_ICON[e.kind] || PenLine
  const d = e.detail || {}
  const who = e.system ? t('te.hist.system') : (e.actor_name || t('te.hist.unknownUser'))
  // Otomatik kayıt (2026-10-16): aynı kişinin 15 dk içindeki otomatik kayıtları TEK satırda birleşir (sunucu
  // `detail.auto` + `saves`); her değişikliğin ilk "önce"si ve son "sonra"sı görünür. Elle kayıt hiç birleşmez.
  const auto = e.kind === 'VALUES' && d.auto === true
  const saves = auto && Number.isFinite(Number(d.saves)) ? Number(d.saves) : 0
  return (
    <li className="flex gap-3 rounded-lg border p-3" data-slot="te-history-event" data-kind={e.kind}
      data-auto={auto ? 'true' : undefined}>
      <span className="mt-0.5 flex size-8 shrink-0 items-center justify-center rounded-md bg-muted text-muted-foreground" aria-hidden="true">
        <Icon className="size-4" />
      </span>
      <div className="flex min-w-0 flex-1 flex-col gap-1.5">
        <div className="flex flex-wrap items-baseline justify-between gap-x-3 gap-y-0.5">
          <div className="flex min-w-0 flex-wrap items-center gap-x-2 gap-y-1">
            {/* Bilinmeyen tür (daha yeni bir sürümün yazdığı) ham anahtar göstermez */}
            <p className="m-0 text-sm font-semibold [overflow-wrap:anywhere]">{t(`te.hist.kind.${KIND_ICON[e.kind] ? e.kind : 'OTHER'}`)}</p>
            {auto && (
              <Badge variant="secondary" className="rounded-md overflow-visible whitespace-normal [overflow-wrap:anywhere]"
                data-slot="te-history-auto">
                <Timer aria-hidden="true" />{t('te.hist.auto')}
              </Badge>
            )}
          </div>
          <time className="text-xs text-muted-foreground tabular-nums" dateTime={e.at}>{fmtTime(e.at, uiLang)}</time>
        </div>
        <p className="m-0 text-xs text-muted-foreground [overflow-wrap:anywhere]">{who}</p>
        {saves > 1 && (
          <p className="m-0 text-xs text-muted-foreground [overflow-wrap:anywhere]" data-slot="te-history-auto-saves">
            {t('te.hist.autoSaves', formatNumber(saves, uiLang))}
          </p>
        )}
        <EventBody kind={e.kind} detail={d} uiLang={uiLang} />
      </div>
    </li>
  )
}

function EventBody({ kind, detail: d, uiLang }) {
  const t = useT()
  const [all, setAll] = useState(false)
  if (kind === 'VALUES') {
    const changes = Array.isArray(d.changes) ? d.changes : []
    // Birleşmiş otomatik kayıtta bir kaydın farkı hesaplanamadıysa diğerlerinin satırları yine görünür (2. tur)
    if (d.unavailable && !changes.length) return <p className="m-0 text-sm text-muted-foreground">{t('te.hist.unavailable')}</p>
    // Otomatik kayıtlarda yazılanların hepsi geri alındı (2026-10-16): satır kalır, ne olduğunu söyler
    if (!changes.length && d.reverted) {
      return <p className="m-0 text-sm text-muted-foreground" data-slot="te-history-reverted">{t('te.hist.autoReverted')}</p>
    }
    if (!changes.length && d.more > 0) {
      return <p className="m-0 text-xs text-muted-foreground">{t('te.hist.moreChanges', formatNumber(d.more, uiLang))}</p>
    }
    if (!changes.length) return <p className="m-0 text-sm text-muted-foreground">{t('te.hist.noDetail')}</p>
    const shown = all ? changes : changes.slice(0, FOLD_AT)
    return (
      <div className="flex flex-col gap-1.5">
        {d.unavailable && <p className="m-0 text-xs text-muted-foreground">{t('te.hist.unavailable')}</p>}
        <ul className="m-0 flex list-none flex-col gap-1.5 p-0" data-slot="te-history-changes">
          {shown.map((c, i) => <ChangeRow key={i} change={c} uiLang={uiLang} />)}
        </ul>
        {changes.length > FOLD_AT && !all && (
          <Button type="button" variant="link" size="sm" className="h-auto w-fit p-0 pointer-coarse:min-h-10" onClick={() => setAll(true)}>
            {t('te.hist.showAll', changes.length)}
          </Button>
        )}
        {d.more > 0 && <p className="m-0 text-xs text-muted-foreground">{t('te.hist.moreChanges', formatNumber(d.more, uiLang))}</p>}
      </div>
    )
  }
  if (kind === 'IMAGE_ADD' || kind === 'IMAGE_REPLACE' || kind === 'IMAGE_DELETE') {
    const meta = [d.file_name, d.width && d.height ? `${d.width}×${d.height}` : null,
      d.size_bytes != null ? formatBytes(d.size_bytes, uiLang) : null].filter(Boolean).join(' · ')
    return (
      <div className="text-sm">
        <p className="m-0 [overflow-wrap:anywhere]">{d.slot || '—'}</p>
        {meta && <p className="m-0 text-xs text-muted-foreground [overflow-wrap:anywhere]">{meta}</p>}
      </div>
    )
  }
  if (kind === 'TEMPLATE_REFRESH') {
    // Sayılar okunamadıysa "0 silindi" denmez — bilinmiyor
    const known = !d.unavailable && Number.isFinite(d.removed_values) && Number.isFinite(d.removed_images)
    return (
      <p className="m-0 text-sm [overflow-wrap:anywhere]">
        {known
          ? t('te.hist.refreshBody', d.template_version ?? '—', formatNumber(d.removed_values, uiLang), formatNumber(d.removed_images, uiLang))
          : t('te.hist.refreshBodyUnknown', d.template_version ?? '—')}
      </p>
    )
  }
  if (kind === 'CREATE') return <p className="m-0 text-sm">{t('te.hist.createBody', d.template_version ?? '—')}</p>
  if (kind === 'SEND' || kind === 'SEND_RESULT') {
    const raw = String(d.state || '').toUpperCase()
    const state = SEND_TONE[raw] ? raw : 'UNKNOWN'
    const to = Array.isArray(d.recipients) ? d.recipients : []
    const extra = (d.recipient_count || to.length) - to.length
    const reason = SEND_REASONS.has(d.reason) ? d.reason : null
    return (
      <div className="flex flex-col gap-1 text-sm">
        <Badge className={cn('w-fit rounded-md overflow-visible whitespace-normal', SEND_TONE[state] || 'bg-muted text-muted-foreground')}
          data-slot="te-history-send-state" data-state={state}>{t(`te.hist.send.${state}`)}</Badge>
        {reason && <p className="m-0 text-xs [overflow-wrap:anywhere]" data-slot="te-history-send-reason">{t(`te.hist.reason.${reason}`)}</p>}
        {to.length > 0 && (
          <p className="m-0 text-xs text-muted-foreground [overflow-wrap:anywhere]">
            {t('te.hist.recipients', to.join(', '))}{extra > 0 ? ` ${t('te.hist.recipientsMore', extra)}` : ''}
          </p>
        )}
        {/* A3 (2026-10-17): alıcı kaynağında sınırın (20) dışında kalan adresler posta ALMADI — gönderim bunu geçmişe yazar */}
        {Number(d.dropped_recipients) > 0 && (
          <p className="m-0 text-xs text-muted-foreground [overflow-wrap:anywhere]" data-slot="te-history-send-dropped">
            {t('te.hist.recipientsDropped', formatNumber(Number(d.dropped_recipients), uiLang))}
          </p>
        )}
        {/* Yöneticilere gönderimde Bilgi (takım adresi) sınırın dışında kalanlar (inceleme 2026-10-17) */}
        {Number(d.dropped_cc) > 0 && (
          <p className="m-0 text-xs text-muted-foreground [overflow-wrap:anywhere]" data-slot="te-history-send-cc-dropped">
            {t('te.hist.ccDropped', formatNumber(Number(d.dropped_cc), uiLang))}
          </p>
        )}
        {/* Kuyruk sonucunun zamanı sonucun OKUNDUĞU an — teslim anı bilinmez */}
        {kind === 'SEND_RESULT' && state !== 'SUPERSEDED' && (
          <p className="m-0 text-xs text-muted-foreground">{t('te.hist.resultTimeNote')}</p>
        )}
      </div>
    )
  }
  if (kind === 'APPROVAL_REQUEST' || kind === 'APPROVAL') {
    const raw = String(d.state || '').toUpperCase()
    const state = APPR_TONE[raw] ? raw : 'UNKNOWN'
    const to = Array.isArray(d.recipients) ? d.recipients : []
    const via = d.via === 'email_token' ? 'email_token' : d.via ? 'app' : null
    return (
      <div className="flex flex-col gap-1 text-sm">
        <Badge className={cn('w-fit rounded-md overflow-visible whitespace-normal', APPR_TONE[state] || 'bg-muted text-muted-foreground')}
          data-slot="te-history-approval-state" data-state={state}>{t(`te.hist.appr.${state}`)}</Badge>
        {via && <p className="m-0 text-xs text-muted-foreground">{t(`te.hist.appr.via.${via}`)}</p>}
        {d.note && <p className="m-0 text-xs [overflow-wrap:anywhere]" data-slot="te-history-approval-note">{t('te.appr.note')}: {d.note}</p>}
        {to.length > 0 && (
          <p className="m-0 text-xs text-muted-foreground [overflow-wrap:anywhere]">{t('te.hist.recipients', to.join(', '))}</p>
        )}
      </div>
    )
  }
  return null
}

/** Tek alan değişikliği: ad (+ bölüm), eski → yeni; uzun metinde toplam uzunluk. */
function ChangeRow({ change: c, uiLang }) {
  const t = useT()
  const empty = <span className="text-muted-foreground italic">{t('te.hist.emptyValue')}</span>
  const len = (v, n) => (n != null && v != null && n > v.length ? ` ${t('te.hist.chars', formatNumber(n, uiLang))}` : '')
  let body
  switch (c.op) {
    case 'add': body = <span>{t('te.hist.op.add')}</span>; break
    case 'remove': body = <span>{t('te.hist.op.remove')}</span>; break
    case 'reorder': body = <span>{t('te.hist.op.reorder')}</span>; break
    case 'auto': body = <span>{t('te.hist.op.auto')}</span>; break
    case 'manual':
      body = <span>{t('te.hist.op.manual')}: <span className="text-foreground">“{c.after}”{len(c.after, c.after_length)}</span></span>
      break
    default:
      body = (
        <span className="flex flex-wrap items-baseline gap-x-1.5">
          <span className={cn(c.before != null && 'line-through decoration-muted-foreground/60')}>
            {c.before != null ? <>{c.before}{len(c.before, c.before_length)}</> : empty}
          </span>
          <span aria-hidden="true" className="text-muted-foreground">→</span>
          <span className="sr-only">{t('te.hist.becomes')}</span>
          <span className="font-medium text-foreground">{c.after != null ? <>{c.after}{len(c.after, c.after_length)}</> : empty}</span>
        </span>
      )
  }
  return (
    <li className="rounded-md bg-muted/40 px-2.5 py-1.5 text-sm [overflow-wrap:anywhere]" data-slot="te-history-change" data-op={c.op}>
      <p className="m-0 font-medium">{c.label}</p>
      {c.section && c.section !== c.label && <p className="m-0 text-xs text-muted-foreground">{c.section}</p>}
      <div className="mt-0.5 text-muted-foreground">{body}</div>
    </li>
  )
}
