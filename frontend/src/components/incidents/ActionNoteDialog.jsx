import { useEffect, useId, useMemo, useRef, useState } from 'react'
import {
  UserCheck, CheckCircle2, X, Check, Circle, CircleAlert, ChevronDown, Info, Mail, MailX, BellOff, BellRing, PhoneCall,
} from 'lucide-react'
import { useT, useDateLocale } from '../../i18n/index.jsx'
import { Dialog, DialogContent, DialogDescription, DialogPortal, DialogTitle } from '@/components/shadcn/dialog'
import { Button } from '@/components/shadcn/button'
import { Textarea } from '@/components/shadcn/textarea'
import { Toggle } from '@/components/shadcn/toggle'
import { Label } from '@/components/shadcn/label'
import { Kbd, KbdGroup } from '@/components/shadcn/kbd'
import { cn } from '@/lib/utils'
import AlertBanner from '../ui/AlertBanner.jsx'
import TeamBadge from '../ui/TeamBadge.jsx'
import UserBadge from '../ui/UserBadge.jsx'
import { Spinner } from '../ui/Progress.jsx'
import { AlertLevelBadge, AlertTypeIcon, AlertTypeChip } from '../admin/alerts/AlertBadges.jsx'
import { AckBadge } from './IncidentBadges.jsx'
import { durationMs, formatDuration, parseUtc } from '../../utils/incidentMeta.js'
import {
  NOTE_RULE, noteRuleState, normalizeAction, chipsFor, hasTemplate, applyTemplate, levelMix, BULK_PREVIEW,
  isLongMessage, focusReturnPlan, returnFocus,
} from './actionNoteModel.js'

/**
 * Gerekçeli SAHİPLEN / ÇÖZ penceresi (2026-09-28 yeniden tasarım, shadcn) — Olaylar konsolu ve Alarm Geçmişi (tekli +
 * toplu) aynı pencereyi açar. Olay çoğu zaman kesinti ORTASINDA açılır: sakin, hızlı, tek ekranda "ne yapıyorum, neyi
 * etkiliyor, ne yazmam gerek".
 *
 * <p><b>Ne yapar (sunucudan doğrulandı).</b> Sahiplen (`/acknowledge` → EscalationService.acknowledge): sahiplenen
 * olarak oturum sahibinin adı + an + not yazılır; o alarm için periyodik TEKRAR HATIRLATMA (re-alert) durur; önem
 * seviyesi yükselirse sahiplenme DÜŞER ve yeni seviye bildirimi gider. Sahiplenme kimseye bildirim göndermez. Çöz
 * (`/resolve`): kayıt kapanır (idempotent), çözen + not yazılır, açılışı alan ekibe ÇÖZÜLDÜ e-postası/push'u gider
 * (izlemede e-posta kapalıysa yalnız push); sorun sürerse izleme teyitten sonra YENİ alarm açar. Not her iki yolda
 * denetim günlüğüne ve alarm zaman çizelgesine adla düşer. Toplu yolda aynı not hepsine yazılır, kapsam dışı atlanır.
 *
 * <p><b>Neden kendi Dialog'u (ModalShell değil).</b> Pencere `--z-dialog` katmanında açılır (yerini aldığı
 * useDialog notu gibi): sağdan açılan detay çekmeceleri (1000/1001) ve gömülü kullanımda ModalShell yığını
 * (2000 + derinlik) ALTTA kalmalı — ModalShell'in derinlik katmanı, aynı ağaçtaki kardeş detay penceresiyle AYNI
 * z'ye düşerdi. Telefonda (< 640 px) alttan açılan sayfa (bottom sheet), geniş ekranda ~32rem ortalanmış pencere:
 * tek DOM, yerleşim yalnız Tailwind kesme noktasıyla. ModalShell deseni aynen korunur: Radix non-modal + kendi
 * scrim'i, sayaçlı kaydırma kilidi, dış tıklama kapatmaz, odak kapanışta tetikleyiciye döner.
 *
 * <p><b>Etkileşim sözleşmesi.</b> Odak açılışta nottadır. Ctrl/⌘+Enter gönderir. Escape / X / İptal / scrim: not boşsa
 * kapatır, doluysa SATIR İÇİ "silinsin mi?" sorar (odak "Yazmaya devam et"te; Escape soruyu kapatır). Kural canlı
 * kontrol listesiyle gösterilir, kırmızı yalnız ilk blur/gönderim denemesinden sonra. Gönderirken her şey kilitli,
 * `aria-busy`, çift gönderim yok. Sunucu hatası (kural, 403, ağ) pencerede kalır — pencere KAPANMAZ, not kaybolmaz.
 *
 * @param action   'ack' | 'resolve'
 * @param subject  'alert' | 'incident' — tekil başlık metni ("Alarmı onayla" / "Olayı onayla")
 * @param items    ActionContext[] (actionNoteModel.contextFromAlert / contextFromIncident); 2+ = toplu görünüm
 * @param onSubmit async (note) => ({ ok: true }) | ({ ok: false, error }) — sunucu çağrısı ÇAĞIRANDA (toast, yenileme)
 * @param onClose  (reason: 'submitted' | 'cancel') => void — çağıran pencereyi kaldırır
 *
 * Test kancaları: pencere `data-action-note` + `data-action` + `data-bulk`; `data-slot="action-note-context|action-note-target|
 * action-note-chips|action-note-rule|action-note-discard"`, kural `data-state`, gönder `data-action-submit`, çip `data-chip`.
 * (shadcn'in kendi `data-slot`'ları — dialog-content / button — EZİLMEZ: CSS sıfırlaması ve e2e onlara bağlı.)
 */

// Kaydırma kilidi sayaçlı (ModalShell / detay çekmeceleriyle aynı sözleşme): iç içe açılışta erken açılmaz.
let scrollLocks = 0
let savedOverflow = ''

function useScrollLock() {
  useEffect(() => {
    if (scrollLocks === 0) { savedOverflow = document.body.style.overflow; document.body.style.overflow = 'hidden' }
    scrollLocks += 1
    return () => { scrollLocks -= 1; if (scrollLocks === 0) document.body.style.overflow = savedOverflow }
  }, [])
}

/**
 * Telefon klavyesi: Chrome/Safari klavye açıkken yalnız GÖRSEL görünüm alanını küçültür; `bottom:0` sabit sayfa
 * klavyenin ALTINDA kalırdı. Görsel görünüm alanının alttan payı ölçülür, sayfa o kadar yukarı alınır (yalnız < 640).
 */
function useKeyboardInset() {
  const [inset, setInset] = useState(0)
  useEffect(() => {
    const vv = typeof window !== 'undefined' ? window.visualViewport : null
    if (!vv) return undefined
    const update = () => setInset(Math.max(0, Math.round(window.innerHeight - vv.height - vv.offsetTop)))
    update()
    vv.addEventListener('resize', update)
    vv.addEventListener('scroll', update)
    return () => { vv.removeEventListener('resize', update); vv.removeEventListener('scroll', update) }
  }, [])
  return inset
}

const isPhone = () => {
  try { return window.matchMedia('(max-width: 639.98px)').matches } catch { return false }
}

const isMac = () => {
  try { return /Mac|iPhone|iPad/.test(navigator.platform || navigator.userAgent || '') } catch { return false }
}

/** Başlık ikonu tonu — sahiplen dikkat (amber), çöz başarı (yeşil). Koyu tema karşılıklarıyla. */
const HEAD = {
  ack:     { Icon: UserCheck,    tile: 'bg-amber-500/15 text-amber-700 dark:text-amber-300' },
  resolve: { Icon: CheckCircle2, tile: 'bg-success/12 text-green-700 dark:text-green-400' },
}

/** Kısa yerel tarih-saat (tam damga `title`'da). */
function shortStamp(iso, locale) {
  const d = parseUtc(iso)
  if (!d) return '—'
  try { return d.toLocaleString(locale, { day: '2-digit', month: 'short', hour: '2-digit', minute: '2-digit', hour12: false }) } catch { return iso }
}
function fullStamp(iso, locale) {
  const d = parseUtc(iso)
  if (!d) return undefined
  try { return d.toLocaleString(locale, { dateStyle: 'full', timeStyle: 'long' }) } catch { return iso }
}

/** Bağlam kartındaki anahtar-değer. */
function Fact({ label, children, className }) {
  return (
    <div className={cn('flex min-w-0 flex-col gap-0.5', className)}>
      <dt className="text-[10.5px] font-semibold tracking-wider text-muted-foreground uppercase">{label}</dt>
      <dd className="m-0 min-w-0 text-xs [overflow-wrap:anywhere]">{children}</dd>
    </div>
  )
}

/** "N önce" — kartlardaki "Açık kalma" rozetiyle AYNI biçimleyici (Intl'in yuvarlaması "43 dakika önce"/"42 dk" ayrışırdı). */
const agoText = (iso, nowMs, t) => {
  const ms = durationMs(iso, null, nowMs)
  return ms == null ? '—' : ms < 60_000 ? t('inbox.justNow') : t('actnote.ago', formatDuration(ms, t))
}

/**
 * Tekil bağlam: hedef (mono) + önem/tür rozetleri + alarm mesajı (tek satır, açılır), ardından kısa bilgiler: açıldı
 * (göreli = açık kalma süresi, ve mutlak), takım, sahiplenen, bildirimler, 7/24 arama kaydı. Göreli zaman ile açık kalma
 * süresi açık alarmda AYNI sayı — bir kez yazılır.
 */
function SingleContext({ ctx, nowMs }) {
  const t = useT()
  const locale = useDateLocale()
  const [expanded, setExpanded] = useState(false)
  const long = isLongMessage(ctx.message)
  const team = ctx.teamName || ctx.teamId != null
    ? <TeamBadge static teamId={ctx.teamId} teamName={ctx.teamName || undefined} className="-mx-1" />
    : null
  const mail = ctx.mail
  const push = ctx.push
  return (
    <section data-slot="action-note-context" aria-label={t('actnote.context')} className="rounded-lg border bg-muted/40 p-3">
      <div className="flex min-w-0 items-start gap-2.5">
        <AlertTypeIcon type={ctx.alertType} />
        <div className="min-w-0 flex-1">
          <p data-slot="action-note-target" title={ctx.title} className="m-0 truncate font-mono text-sm leading-snug font-semibold">{ctx.title}</p>
          {ctx.subtitle && <p title={ctx.subtitle} className="m-0 truncate font-mono text-xs text-muted-foreground">{ctx.subtitle}</p>}
          <div className="mt-1.5 flex min-w-0 flex-wrap items-center gap-1.5">
            <AlertLevelBadge level={ctx.level} />
            {ctx.acknowledged && <AckBadge />}
            {ctx.alertType && <AlertTypeChip type={ctx.alertType} className="text-xs" />}
          </div>
          {ctx.message && (
            <div data-slot="action-note-message" className="mt-1.5 min-w-0">
              {long ? (
                // Uzun mesaj tek satır; tamamı için TÜM SATIR dokunma hedefi (dokunmatikte ≥ 40 px).
                <Button type="button" variant="ghost" aria-expanded={expanded} onClick={() => setExpanded((v) => !v)}
                  className="-mx-1.5 h-auto min-h-7 w-[calc(100%+0.75rem)] items-start justify-between gap-2 px-1.5 py-1 text-left text-xs font-normal whitespace-normal text-muted-foreground pointer-coarse:min-h-10">
                  <span className={cn('min-w-0 [overflow-wrap:anywhere]', expanded ? 'whitespace-pre-wrap' : 'line-clamp-1')}>{ctx.message}</span>
                  <span className="inline-flex shrink-0 items-center gap-0.5 font-medium text-foreground/80">
                    {expanded ? t('actnote.showLess') : t('actnote.showMore')}
                    <ChevronDown aria-hidden="true" className={cn('size-3.5 transition-transform motion-reduce:transition-none', expanded && 'rotate-180')} />
                  </span>
                </Button>
              ) : (
                <p className="m-0 text-xs text-muted-foreground [overflow-wrap:anywhere]">{ctx.message}</p>
              )}
            </div>
          )}
        </div>
      </div>

      <dl className="m-0 mt-3 grid grid-cols-2 gap-x-4 gap-y-2.5 border-t pt-2.5 sm:grid-cols-3">
        <Fact label={t('actnote.opened')}>
          {ctx.openedAt ? (
            <span className="flex flex-col">
              <span data-slot="action-note-duration" className="font-medium tabular-nums">{agoText(ctx.openedAt, nowMs, t)}</span>
              <span data-slot="action-note-opened-at" className="text-muted-foreground tabular-nums" title={fullStamp(ctx.openedAt, locale)}>{shortStamp(ctx.openedAt, locale)}</span>
            </span>
          ) : '—'}
        </Fact>
        <Fact label={t('incov.colTeam')}>{team ?? <span className="text-muted-foreground">{t('incov.noTeam')}</span>}</Fact>
        {ctx.acknowledged && (
          <Fact label={t('alh.fact.ack')}>
            <span data-slot="action-note-owner" className="flex min-w-0 flex-col gap-0.5">
              {ctx.ackBy ? <UserBadge username={ctx.ackBy} inline size="sm" /> : <span className="text-muted-foreground">—</span>}
              {ctx.ackAt && <span className="text-muted-foreground tabular-nums">{agoText(ctx.ackAt, nowMs, t)}</span>}
            </span>
          </Fact>
        )}
        {(mail || push) && (
          <Fact label={t('actnote.notifications')}>
            <span data-slot="action-note-notifs" className="flex flex-wrap items-center gap-x-2.5 gap-y-0.5 tabular-nums">
              {mail && (
                <span className="inline-flex items-center gap-1"><Mail aria-hidden="true" className="size-3.5 text-muted-foreground" />{t('actnote.mail', mail.sent)}</span>
              )}
              {mail?.failed > 0 && (
                <span className="inline-flex items-center gap-1 text-destructive"><MailX aria-hidden="true" className="size-3.5" />{t('alh.mailsFailed', mail.failed)}</span>
              )}
              {push && (
                <span className="inline-flex items-center gap-1"><BellRing aria-hidden="true" className="size-3.5 text-muted-foreground" />{t('actnote.push', push.sent)}</span>
              )}
              {push?.failed > 0 && (
                <span data-slot="action-note-push-failed" className="inline-flex items-center gap-1 text-destructive"><BellOff aria-hidden="true" className="size-3.5" />{t('alh.mailsFailed', push.failed)}</span>
              )}
            </span>
          </Fact>
        )}
        {ctx.nocCalls > 0 && (
          <Fact label={t('actnote.nocCalls')}>
            <span data-slot="action-note-noc" className="inline-flex items-center gap-1 font-medium">
              <PhoneCall aria-hidden="true" className="size-3.5 text-muted-foreground" />
              {ctx.nocCalls === 1 ? t('nocCall.indicator.one') : t('nocCall.indicator.count', ctx.nocCalls)}
            </span>
          </Fact>
        )}
      </dl>
    </section>
  )
}

/** Toplu bağlam: sayı + önem dağılımı + ilk birkaç kayıt ("+N daha"); sahiplenmede zaten sahiplenilmişler notu. */
function BulkContext({ items, action }) {
  const t = useT()
  const mix = levelMix(items)
  const preview = items.slice(0, BULK_PREVIEW)
  const rest = items.length - preview.length
  const acked = action === 'ack' ? items.filter((c) => c.acknowledged).length : 0
  return (
    <section data-slot="action-note-context" data-bulk="" aria-label={t('actnote.context')} className="rounded-lg border bg-muted/40 p-3">
      <div className="flex flex-wrap items-center gap-x-3 gap-y-1.5">
        <span className="text-sm font-semibold">{t('actnote.bulk.count', items.length)}</span>
        <span role="group" aria-label={t('actnote.levelMix')} className="flex flex-wrap items-center gap-1.5">
          {mix.filter((m) => m.level !== 'UNKNOWN').map((m) => (
            <span key={m.level} data-slot="action-note-mix" data-level={m.level} className="inline-flex items-center gap-1">
              <AlertLevelBadge level={m.level} />
              <span className="text-xs font-semibold text-muted-foreground tabular-nums">×{m.count}</span>
            </span>
          ))}
        </span>
      </div>
      <ul className="m-0 mt-2.5 flex list-none flex-col divide-y overflow-hidden rounded-md border bg-background p-0">
        {preview.map((c) => (
          <li key={c.id} data-slot="action-note-item" className="flex min-w-0 items-center gap-2 px-2.5 py-2">
            <AlertTypeIcon type={c.alertType} className="size-6" />
            <span title={c.title} className="min-w-0 flex-1 truncate font-mono text-xs font-medium">{c.title}</span>
            <AlertLevelBadge level={c.level} className="text-[0.68rem]" />
          </li>
        ))}
      </ul>
      {rest > 0 && <p data-slot="action-note-more" className="m-0 mt-1.5 text-xs text-muted-foreground">{t('actnote.bulk.more', rest)}</p>}
      {acked > 0 && (
        <p className="m-0 mt-2 flex items-start gap-1.5 text-xs text-amber-700 dark:text-amber-300">
          <Info aria-hidden="true" className="mt-px size-3.5 shrink-0" />{t('actnote.bulk.alreadyAcked', acked)}
        </p>
      )}
    </section>
  )
}

/** Kural satırı: karşılandı (yeşil) · bekliyor (nötr) · eksik (kırmızı — yalnız blur/gönderim denemesinden sonra). */
function RuleItem({ met, error, label, progress }) {
  const t = useT()
  const state = met ? 'met' : error ? 'error' : 'pending'
  const Icon = met ? CheckCircle2 : error ? CircleAlert : Circle
  return (
    <li data-slot="action-note-rule" data-state={state}
      className={cn('flex items-center gap-1.5 transition-colors motion-reduce:transition-none',
        met ? 'text-green-700 dark:text-green-400' : error ? 'text-destructive' : 'text-muted-foreground')}>
      <Icon aria-hidden="true" className="size-3.5 shrink-0" />
      <span>{label}</span>
      {!met && <span className="tabular-nums opacity-80">· {progress}</span>}
      <span className="sr-only">— {met ? t('actnote.rule.met') : t('actnote.rule.unmet')}</span>
    </li>
  )
}

export default function ActionNoteDialog({ action = 'ack', subject = 'alert', items = [], onSubmit, onClose }) {
  const t = useT()
  const act = normalizeAction(action)
  const resolve = act === 'resolve'
  const list = useMemo(() => (Array.isArray(items) ? items.filter(Boolean) : []), [items])
  const bulk = list.length > 1
  const single = list[0] ?? null

  const [note, setNote] = useState('')
  const [touched, setTouched] = useState(false)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState(null)
  const [confirmDiscard, setConfirmDiscard] = useState(false)
  const [nowMs, setNowMs] = useState(() => Date.now())
  // Odak iadesi planı AÇILIŞ ANINDA (render'da): menüden açıldıysa menünün tetiği, sonradan kalkarsa kartın eylemleri.
  const [focusPlan] = useState(() => focusReturnPlan(typeof document === 'undefined' ? null : document.activeElement))
  const busyRef = useRef(false)
  const noteRef = useRef(null)
  const keepRef = useRef(null)
  const rulesRef = useRef(null)
  const caretToEnd = useRef(false)
  const uid = useId()
  const noteId = `${uid}-note`
  const rulesId = `${uid}-rules`
  const hintId = `${uid}-hint`
  const discardId = `${uid}-discard`

  const rule = noteRuleState(note)
  const showErrors = touched && !rule.valid
  const kbInset = useKeyboardInset()
  useScrollLock()

  useEffect(() => () => { returnFocus(focusPlan) }, [focusPlan])
  // Süre ve göreli zaman canlı kalsın (pencere kısa ömürlü — 30 sn yeter).
  useEffect(() => {
    const id = setInterval(() => setNowMs(Date.now()), 30_000)
    return () => clearInterval(id)
  }, [])
  // Çip sonrası imleç sona + odak notta (çip odak çalmasın; klavye kullanıcısı yazmaya devam etsin).
  useEffect(() => {
    if (!caretToEnd.current) return
    caretToEnd.current = false
    const el = noteRef.current
    if (!el) return
    el.focus()
    try { el.setSelectionRange(el.value.length, el.value.length) } catch { /* yoksay */ }
  }, [note])
  useEffect(() => { if (confirmDiscard) keepRef.current?.focus() }, [confirmDiscard])
  // Telefon klavyesi açılınca (görsel görünüm alanı küçüldü) yazılan alan klavyenin üstünde, görünür kalsın.
  // Effect DOM işlendikten sonra koşar: yeni --an-kb yerleşimi hazır, kaydırma eşzamanlı. Önkoşul: içerikte `transition-none`
  // (shadcn'in `duration-200`'ü geçiş özelliği `all` iken bottom/max-height'ı 200 ms kaydırıyordu → kaydırma ESKİ yerleşime göre).
  useEffect(() => {
    if (kbInset > 0 && typeof document !== 'undefined' && document.activeElement === noteRef.current) {
      try { noteRef.current?.scrollIntoView({ block: 'nearest' }) } catch { /* yoksay */ }
    }
  }, [kbInset])

  const chips = chipsFor(act)
  const n = list.length
  const title = bulk
    ? t(resolve ? 'actnote.title.resolveBulk' : 'actnote.title.ackBulk', n)
    : subject === 'incident'
      ? t(resolve ? 'incov.resolveDialogTitle' : 'incov.ackDialogTitle')
      : t(resolve ? 'actnote.title.resolveAlert' : 'actnote.title.ackAlert')
  const description = bulk
    ? t(resolve ? 'actnote.desc.resolveBulk' : 'actnote.desc.ackBulk', n)
    : t(resolve ? 'actnote.desc.resolve' : 'actnote.desc.ack')
  const confirmLabel = bulk ? title : t(resolve ? 'actnote.confirm.resolve' : 'actnote.confirm.ack')
  const head = HEAD[act]

  function focusNote() { noteRef.current?.focus() }
  /** Not alanını (ve altındaki kural listesini) kaydırma alanında görünür kıl — hata / klavye sonrası. */
  function revealNote() {
    try { noteRef.current?.scrollIntoView({ block: 'nearest', behavior: 'smooth' }) } catch { /* yoksay */ }
  }

  function requestClose() {
    if (busyRef.current) return
    if (confirmDiscard) { keepRef.current?.focus(); return }   // soru açık: karar düğmelerle verilir
    if (note.trim()) { setConfirmDiscard(true); return }
    onClose?.('cancel')
  }

  function keepEditing() {
    setConfirmDiscard(false)
    // Soru kapanınca düğmeler geri gelir; odak nota (bir sonraki çizimde).
    setTimeout(focusNote, 0)
  }

  function pickChip(text) {
    if (busyRef.current) return
    caretToEnd.current = true
    setNote((cur) => applyTemplate(cur, text))
    setError(null)
  }

  async function submit() {
    if (busyRef.current || confirmDiscard) return
    const state = noteRuleState(note)
    if (!state.valid) { setTouched(true); focusNote(); return }
    busyRef.current = true
    setError(null)
    setBusy(true)
    let res
    try {
      res = await onSubmit?.(state.trimmed)
    } catch (e) {
      res = { ok: false, error: e?.message }
    } finally {
      busyRef.current = false
      setBusy(false)
    }
    if (res?.ok === true) { onClose?.('submitted'); return }
    // Pencere AÇIK kalır, not korunur; hata altta sabit alanda (her zaman görünür), odak yeniden notta.
    setError(res?.error || t('actnote.error.fallback'))
    // Hata alt alanı büyütür: not + kural listesi görünür kalsın (liste alana kaydırılır), odak kaydırmayı geri almasın.
    setTimeout(() => {
      try { rulesRef.current?.scrollIntoView({ block: 'nearest' }) } catch { /* yoksay */ }
      noteRef.current?.focus({ preventScroll: true })
    }, 0)
  }

  function onFormKeyDown(e) {
    if (e.key === 'Enter' && (e.ctrlKey || e.metaKey)) { e.preventDefault(); submit() }
  }

  function onNoteFocus() {
    // Telefonda klavye açılınca alan görünür kalsın (görsel görünüm alanı küçüldükten sonra).
    if (!isPhone()) return
    setTimeout(revealNote, 320)
  }

  const mac = isMac()

  return (
    <Dialog open modal={false} onOpenChange={(next) => { if (!next) requestClose() }}>
      {/* Scrim: Radix non-modal kipte DialogOverlay çizmez (ModalShell deseni). Yalnız scrim'in KENDİSİNE tıklama. */}
      <DialogPortal>
        <div data-slot="dialog-overlay" aria-hidden="true"
          className="fixed inset-0 z-(--z-dialog) bg-black/50 animate-in fade-in-0 motion-reduce:animate-none"
          onClick={(e) => { if (e.target === e.currentTarget) requestClose() }} />
      </DialogPortal>
      <DialogContent
        showCloseButton={false}
        aria-modal="true"
        data-action-note=""
        data-action={act}
        data-bulk={bulk ? 'true' : undefined}
        style={{ '--an-kb': `${kbInset}px` }}
        onOpenAutoFocus={(e) => { e.preventDefault(); focusNote() }}
        onCloseAutoFocus={(e) => e.preventDefault()}
        onInteractOutside={(e) => e.preventDefault()}
        onEscapeKeyDown={(e) => {
          e.preventDefault()
          if (busyRef.current) return
          if (confirmDiscard) keepEditing()
          else requestClose()
        }}
        className={cn(
          // transition-none: taban `duration-200` + varsayılan `transition-property: all` klavye payını (bottom/max-h) canlandırıyordu;
          // giriş animasyonu (animate-in) geçiş değil, etkilenmez.
          'z-[calc(var(--z-dialog)+1)] flex flex-col gap-0 overflow-hidden p-0 transition-none',
          // Telefon (< 640): alttan açılan sayfa — klavye açıkken onun üstünde (--an-kb), yükseklik görünür alana göre
          'top-auto bottom-(--an-kb) left-0 max-h-[calc(100dvh-var(--an-kb)-0.75rem)] max-w-none translate-x-0 translate-y-0',
          'rounded-t-2xl rounded-b-none border-x-0 border-b-0 max-sm:data-[state=open]:slide-in-from-bottom-6',
          // ≥ 640: ortalanmış pencere (~32rem)
          'sm:top-[50%] sm:bottom-auto sm:left-[50%] sm:max-h-[min(88vh,calc(100dvh-2rem))] sm:max-w-lg sm:translate-x-[-50%] sm:translate-y-[-50%] sm:rounded-lg sm:border',
        )}
      >
        <form noValidate onSubmit={(e) => { e.preventDefault(); submit() }} onKeyDown={onFormKeyDown}
          aria-busy={busy || undefined} className="flex min-h-0 flex-1 flex-col">
          {/* Telefon tutamacı (süs) */}
          <div aria-hidden="true" className={cn('mx-auto mt-2 h-1.5 w-10 shrink-0 rounded-full bg-muted-foreground/25 sm:hidden', kbInset > 0 && 'hidden')} />

          <div data-slot="action-note-header" className="flex shrink-0 items-start gap-3 px-4 pt-3 pb-3 sm:px-6 sm:pt-5">
            <span aria-hidden="true" className={cn('grid size-10 shrink-0 place-items-center rounded-full', head.tile)}>
              <head.Icon className="size-5" />
            </span>
            <div className="min-w-0 flex-1 pt-0.5">
              <DialogTitle className="text-base leading-snug font-semibold sm:text-lg">{title}</DialogTitle>
              <DialogDescription className={cn('mt-1 text-[13px] leading-relaxed text-muted-foreground sm:text-sm', kbInset > 0 && 'max-sm:sr-only')}>
                {description}
              </DialogDescription>
            </div>
            <Button type="button" variant="ghost" size="icon" disabled={busy} onClick={requestClose}
              className="-mt-1 -mr-2 size-10 shrink-0 text-muted-foreground" aria-label={t('app.close')}>
              <X aria-hidden="true" />
            </Button>
          </div>

          <div data-slot="action-note-body" className="min-h-0 flex-1 overflow-y-auto overscroll-contain px-4 pb-4 sm:px-6">
            {bulk ? <BulkContext items={list} action={act} /> : single && <SingleContext ctx={single} nowMs={nowMs} />}

            <div data-slot="action-note-field" className="mt-4 flex flex-col gap-2">
              <Label htmlFor={noteId} className="gap-1 font-semibold">
                {t('alh.note.label')}<span data-slot="field-required" aria-hidden="true" className="text-destructive">*</span>
              </Label>

              {/* Hazır gerekçeler: telefonda tek satır yatay kayar (klavye açıkken not görünür kalsın), genişte sarar.
                  Basılı çip = metni notta; yeniden basmak çıkarır. Fare basışı odağı ÇALMAZ (not odakta kalır). */}
              <div role="group" aria-label={t('actnote.chips')} data-slot="action-note-chips"
                className="-mx-4 flex gap-2 overflow-x-auto px-4 pb-1 [scrollbar-width:none] sm:mx-0 sm:flex-wrap sm:overflow-visible sm:px-0 sm:pb-0">
                {chips.map((c) => {
                  const text = t(c.text)
                  const on = hasTemplate(note, text)
                  return (
                    <Toggle key={c.id} variant="outline" size="sm" pressed={on} disabled={busy} title={text}
                      data-chip={c.id} onMouseDown={(e) => e.preventDefault()} onPressedChange={() => pickChip(text)}
                      className="h-10 shrink-0 rounded-full px-3.5 text-xs font-medium sm:h-8 sm:px-3 sm:pointer-coarse:h-10 data-[state=on]:border-primary/50 data-[state=on]:bg-primary/10 data-[state=on]:text-primary dark:data-[state=on]:bg-primary/20">
                      {on && <Check aria-hidden="true" />}{t(c.label)}
                    </Toggle>
                  )
                })}
              </div>

              <Textarea
                ref={noteRef}
                id={noteId}
                rows={4}
                value={note}
                readOnly={busy}
                required
                aria-required="true"
                aria-invalid={showErrors || undefined}
                aria-describedby={`${rulesId} ${hintId}`}
                placeholder={t(resolve ? 'actnote.note.placeholderResolve' : 'actnote.note.placeholderAck')}
                onChange={(e) => { setNote(e.target.value); if (error) setError(null) }}
                onBlur={() => { if (note.trim()) setTouched(true) }}
                onFocus={onNoteFocus}
                className="min-h-24 resize-y read-only:cursor-progress read-only:opacity-70"
              />

              <div className="flex items-start justify-between gap-3">
                <ul ref={rulesRef} id={rulesId} data-slot="action-note-rules" aria-label={t('actnote.rules')} className="m-0 flex list-none flex-col gap-1 p-0 text-xs">
                  <RuleItem met={rule.charsOk} error={showErrors} label={t('actnote.rule.chars', NOTE_RULE.minChars)}
                    progress={`${Math.min(rule.chars, NOTE_RULE.minChars)}/${NOTE_RULE.minChars}`} />
                  <RuleItem met={rule.wordsOk} error={showErrors} label={t('actnote.rule.words', NOTE_RULE.minWords, NOTE_RULE.minWordLen)}
                    progress={`${Math.min(rule.words, NOTE_RULE.minWords)}/${NOTE_RULE.minWords}`} />
                </ul>
                <span data-slot="action-note-counter" className="shrink-0 pt-px text-xs text-muted-foreground tabular-nums">
                  {t('actnote.counter', rule.chars)}
                </span>
              </div>
              <p id={hintId} className="m-0 text-xs text-muted-foreground">
                {bulk ? t('actnote.note.hintBulk', n) : t('actnote.note.hint')}
              </p>
              {/* Kural sağlandığı AN bir kez duyurulur (her tuşta değil). */}
              <span role="status" className="sr-only">{rule.valid ? t('actnote.rule.ready') : ''}</span>
            </div>
          </div>

          <div data-slot="action-note-footer"
            className="flex shrink-0 flex-col gap-3 border-t bg-background px-4 pt-3 pb-[max(0.75rem,env(safe-area-inset-bottom))] sm:px-6 sm:py-4">
            {error && (
              <AlertBanner tone="danger" role="alert" title={t('incov.actionError')} className="mb-0">
                {error}
                <span className="mt-1 block text-muted-foreground">{t('actnote.error.kept')}</span>
              </AlertBanner>
            )}
            {confirmDiscard ? (
              <div data-slot="action-note-discard" className="flex flex-col gap-2 sm:flex-row sm:items-center sm:justify-between">
                <p id={discardId} className="m-0 text-sm font-medium">{t('actnote.discard.title')}</p>
                <div className="grid grid-cols-2 gap-2 sm:flex">
                  <Button type="button" variant="ghost" className="h-11 text-destructive hover:bg-destructive/10 hover:text-destructive sm:h-9 sm:pointer-coarse:h-10"
                    aria-describedby={discardId} onClick={() => onClose?.('cancel')}>
                    {t('actnote.discard.confirm')}
                  </Button>
                  <Button ref={keepRef} type="button" variant="outline" className="h-11 sm:h-9 sm:pointer-coarse:h-10" aria-describedby={discardId} onClick={keepEditing}>
                    {t('actnote.discard.keep')}
                  </Button>
                </div>
              </div>
            ) : (
              <div className="grid grid-cols-[auto_1fr] gap-2 sm:flex sm:items-center sm:justify-end">
                <span data-slot="action-note-shortcut" className="mr-auto hidden items-center gap-1.5 text-xs text-muted-foreground sm:pointer-fine:inline-flex">
                  <KbdGroup><Kbd>{mac ? '⌘' : 'Ctrl'}</Kbd><Kbd>Enter</Kbd></KbdGroup>{t('actnote.shortcut')}
                </span>
                <Button type="button" variant="outline" className="h-11 px-4 sm:h-9 sm:pointer-coarse:h-10" disabled={busy} onClick={requestClose}>
                  {t('dlg.cancel')}
                </Button>
                <Button type="submit" data-action-submit="" variant={resolve ? 'success' : 'default'}
                  className="h-auto min-h-11 min-w-0 py-2 leading-tight whitespace-normal sm:h-9 sm:min-h-0 sm:whitespace-nowrap sm:pointer-coarse:h-10"
                  disabled={busy} aria-busy={busy || undefined}>
                  {busy ? <Spinner decorative size={16} /> : <head.Icon aria-hidden="true" />}
                  {busy ? t('actnote.saving') : confirmLabel}
                </Button>
              </div>
            )}
          </div>
        </form>
      </DialogContent>
    </Dialog>
  )
}
