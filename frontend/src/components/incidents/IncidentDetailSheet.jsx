import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { createPortal } from 'react-dom'
import {
  X, Siren, Bell, UserCheck, MessageSquare, CheckCircle2, Send, Trash2, ExternalLink, Clock, Hash, Lock, RotateCcw,
  BellPlus, History, VolumeX, Wrench, Repeat,
} from 'lucide-react'
import { api } from '../../api/client'
import { useT, useDateLocale } from '../../i18n/index.jsx'
import { useToast } from '../ui/Toast.jsx'
import { formatIncidentTime } from '../../utils/incidentMeta.js'
import { navigateTo } from '../../utils/navigate.js'
import { LoadingBlock } from '../ui/Progress.jsx'
import TeamBadge from '../ui/TeamBadge.jsx'
import ReadOnlyBadge from '../ui/ReadOnlyBadge.jsx'
import { usePermissions } from '../../contexts/PermissionsProvider.jsx'
import CopyLinkButton from '../ui/CopyLinkButton.jsx'
import CopyableRef from '../ui/CopyableRef.jsx'
import { Sheet, SheetContent, SheetHeader, SheetTitle, SheetDescription, SheetClose } from '@/components/shadcn/sheet'
import { Button } from '@/components/shadcn/button'
import { Badge } from '@/components/shadcn/badge'
import { Card } from '@/components/shadcn/card'
import { Textarea } from '@/components/shadcn/textarea'
import { Separator } from '@/components/shadcn/separator'
import { cn } from '@/lib/utils'
import { IncidentStatusBadge, AckBadge, SeverityBadge, RootCauseChip, MonitorTypeIcon, MonitorLink, ResolvedBy } from './IncidentBadges.jsx'
import {
  buildTimeline, incidentHref, incidentLink, isOpen, isAcked, rowName, MONITOR_TYPE_LABEL_KEY, isForeign, canActOn, canDeleteIncident,
  canCommentOn, canDeleteComment, canNotifyOn, asAlert,
} from './incidentsModel.js'
import { NocCallSummary } from '../admin/alerts/NocCallLog.jsx'   // 7/24 arama kayıtları — salt okunur özet (2026-09-27)
// Zenginleştirme (2026-11-13): Alarm Geçmişi'nin parçaları — teslimat paneli, fırtına push'u, nöbetçi, olay kaydı köprüsü
import AlertNotificationsPanel, { EmailStatusBadge, mailTriggerText } from '../admin/alerts/AlertNotifications.jsx'
import { NocSentBadge } from '../admin/alerts/AlertBadges.jsx'
import { StormLink, StormPushSection } from '../admin/alerts/AlertStormPush.jsx'
import { normalizeStormPush, showStormPushSection } from '../admin/alerts/stormPushModel.js'
import { groupPushRows } from '../admin/alerts/alertHistoryModel.js'
import AlertOnCall from '../admin/alerts/AlertOnCall.jsx'
import AlertIncidentLink from '../admin/alerts/AlertIncidentLink.jsx'
import DbQueryAlertRows from '../dbquery/DbQueryAlertRows.jsx'
import PageIssueList from '../page/PageIssueList.jsx'
import LiveDuration from './LiveDuration.jsx'
import IncidentCheckEvidence from './IncidentCheckEvidence.jsx'
import { RecurrenceBadge } from './IncidentBadges.jsx'

// Kaydırma kilidi sayaçlı (ModalShell ile aynı sözleşme): iç içe pencerede erken açılmaz.
let scrollLocks = 0
let savedOverflow = ''

const EVENT_STYLE = {
  opened:       { Icon: Siren,        ink: 'border-destructive/40 bg-destructive/10 text-destructive' },
  notified:     { Icon: Bell,         ink: 'border-border bg-muted text-muted-foreground' },
  acknowledged: { Icon: UserCheck,    ink: 'border-sky-500/40 bg-sky-500/10 text-sky-700 dark:text-sky-300' },
  comment:      { Icon: MessageSquare, ink: 'border-primary/30 bg-primary/10 text-primary' },
  resolved:     { Icon: CheckCircle2, ink: 'border-success/40 bg-success/10 text-success' },
}

/**
 * Olayın teslimat kayıtları (e-posta günlüğü + push partileri + kapsayan fırtına push'u) — Alarm Geçmişi'nin
 * `useAlertDeliveries` kuralı, ek olarak YENİDEN DENE (`nonce`). `id` null (başka ekibin olayı: alıcılar takım içi veri)
 * → istek yok. E-posta günlüğü okunamazsa `failed` (zaman çizelgesi "bildirim yok" demez, bant söyler).
 */
function useIncidentDeliveries(id, nonce) {
  const [state, setState] = useState({ loading: id != null, failed: false, history: [], push: [], stormPush: normalizeStormPush(null) })
  useEffect(() => {
    if (id == null) { setState({ loading: false, failed: false, history: [], push: [], stormPush: normalizeStormPush(null) }); return undefined }
    let alive = true
    setState((st) => ({ ...st, loading: true, failed: false }))
    Promise.all([
      Promise.resolve().then(() => api.admin.getAlertNotifications(id)).catch(() => null),
      Promise.resolve().then(() => api.admin.getAlertPushDeliveries(id)).catch(() => null),
      Promise.resolve().then(() => api.admin.getAlertStormPush(id)).catch(() => null),
    ]).then(([n, p, sp]) => {
      if (!alive) return
      setState({
        loading: false,
        failed: !(n?.success && Array.isArray(n.data)),
        history: n?.success && Array.isArray(n.data) ? n.data : [],
        push: p?.success && Array.isArray(p.data) ? p.data : [],
        stormPush: normalizeStormPush(sp),
      })
    })
    return () => { alive = false }
  }, [id, nonce])
  return state
}

/** PAGE_INTEGRITY: alarm anının sorunlu kaynakları yalnız tekil alarm yanıtında (`page_issues`) — gerekirse çekilir. */
function usePageIssues(incident, enabled) {
  const id = incident?.id
  const need = enabled && incident?.alert_type === 'PAGE_INTEGRITY' && id != null
  const [issues, setIssues] = useState(null)
  useEffect(() => {
    setIssues(null)
    if (!need) return undefined
    let alive = true
    Promise.resolve().then(() => api.admin.getAlert(id)).catch(() => null).then((res) => {
      if (!alive || !res?.success || !res.data || String(res.data.id) !== String(id)) return
      setIssues(Array.isArray(res.page_issues) ? res.page_issues : null)
    })
    return () => { alive = false }
  }, [id, need])
  return issues
}

/** Uygulama içi geçiş: değiştirici tuşla / orta tıkla bağlantının `href`'i yeni sekmede, düz tıkla aynı sekmede. */
function inApp(e, tab, params, onLeave) {
  if (e.metaKey || e.ctrlKey || e.shiftKey || e.altKey || e.button === 1) return
  e.preventDefault()
  onLeave?.()
  navigateTo(tab, params)
}

const SECTION = 'mt-5 mb-2 text-xs font-bold tracking-wide text-muted-foreground uppercase'
/** Eylem düğmeleri: masaüstünde 36 px, dokunmatikte 40 px. */
const ACTION = 'h-9 pointer-coarse:h-10'

/** Anahtar-değer satırı (temel bilgiler kartı). */
function Fact({ label, children, className }) {
  return (
    <div className={cn('flex min-w-0 flex-col gap-0.5', className)}>
      <dt className="text-[10.5px] font-semibold tracking-wider text-muted-foreground uppercase">{label}</dt>
      <dd className="m-0 min-w-0 text-sm [overflow-wrap:anywhere]">{children}</dd>
    </div>
  )
}

/**
 * Olay detayı — sağdan açılan Sheet (telefonda tam genişlik): durum şeridi, eylemler, temel bilgiler, zaman
 * çizelgesi (açılış → bildirimler → onay → yorumlar → çözüm) ve altta sabit yorum yazıcı (Ctrl/⌘+Enter gönderir).
 *
 * <p><b>Neden `modal={false}` + kendi scrim'i.</b> ModalShell ile aynı gerekçe: Radix modal kipi body'ye
 * `pointer-events:none` yazar ve odağı DOM kapsamına hapseder; içinden açılan takım üyeleri penceresi
 * (TeamBadge → ModalShell) ve onay diyalogları (useDialog) body'ye portal'lanır. Non-modal + scrim + sayaçlı
 * kaydırma kilidi o pencereleri tıklanabilir bırakır; kapatma yolları scrim, X ve Escape (Radix katmanı).
 *
 * <p>Zaman çizelgesinde SOL RENK ŞERİDİ YOK (kullanıcı kuralı): nötr `border` çizgisi + tonlu ikon daireleri.
 *
 * <p><b>Başka ekibin olayı</b> (`can_manage:false`, org geneli salt okunur görünürlük 2026-09-28): durum şeridinin altında
 * "Başka takımın kaydı — salt okunur" rozeti + sahibi ekibin TeamBadge'i ve tek satır açıklama; onayla/çöz/sil, izleme
 * bağlantısı, yorum yazıcı ve yorum silme YOK. Bildirim alıcıları / teslimat günlüğü İSTENMEZ (takım içi veri, uç zaten
 * 403); 7/24 arama özeti yalnız 7/24 operatörüne (`noc_calls.write`, sunucu tüm uyarıları okutur). Kendi olayında
 * eylem izni yoksa (`can_act:false`, ör. AUDIT) aynı denetimler gizlenir ve yazıcının yerinde nedeni yazar.
 */
export default function IncidentDetailSheet({
  incident, onClose, isAdmin = false, onAck, onResolve, onDelete, onCommentDelta, focusComposer = false, onNotify,
  notifying = false,
}) {
  const t = useT()
  const dateLocale = useDateLocale()
  const toast = useToast()
  const [comments, setComments] = useState([])
  const [commentsFailed, setCommentsFailed] = useState(false)
  const [loading, setLoading] = useState(true)
  const [deliveryNonce, setDeliveryNonce] = useState(0)
  const [body, setBody] = useState('')
  const [saving, setSaving] = useState(false)
  const composerRef = useRef(null)
  const id = incident?.id
  const foreign = isForeign(incident)
  const perms = usePermissions()
  const nocOperator = perms.canEdit('noc_calls.write')
  // Alarm Geçmişi tekil okuması sunucuda requireAlertReadScope: kendi kapsamı ya da tüm uyarıları gören (7/24 konsolu) —
  // başka ekibin org geneli okunan olayında bağlantı 403'e götürürdü (2026-11-13 inceleme)
  const canOpenAlert = !foreign || perms.scope?.noc_console === true
  // Bildirim geçmişi (e-posta günlüğü + push + fırtına push'u): alerts.read + takım kapsamı. Başka ekibin olayında HİÇ
  // istenmez — alıcılar takım içi veri (uç 403 döner, boşuna istek olurdu).
  const deliveries = useIncidentDeliveries(foreign ? null : id, deliveryNonce)
  const pageIssues = usePageIssues(incident, !foreign)
  const pushGroups = useMemo(() => groupPushRows(deliveries.push), [deliveries.push])

  const load = useCallback(async () => {
    if (id == null) return
    setLoading(true)
    try {
      const c = await Promise.resolve().then(() => api.monitoring.incidents.comments(id)).catch(() => null)
      // Okunamayan yorum listesi "yorum yok" DEĞİLDİR (2026-11-13): bant + yeniden dene; eski liste yerinde kalır.
      if (c?.success) { setComments(Array.isArray(c.data) ? c.data : []); setCommentsFailed(false) }
      else setCommentsFailed(true)
    } finally {
      setLoading(false)
    }
  }, [id])
  useEffect(() => { load() }, [load])

  // Odak: açılışta Radix içeriğe verir; derin bağlantı `action=comment` ise yazıcıya. Kapanışta tetikleyiciye geri.
  useEffect(() => {
    const previous = document.activeElement
    return () => { if (previous && typeof previous.focus === 'function' && document.contains(previous)) previous.focus() }
  }, [])
  useEffect(() => {
    if (!focusComposer || loading) return
    const el = composerRef.current
    if (el) { el.focus(); el.scrollIntoView?.({ block: 'nearest' }) }
  }, [focusComposer, loading])

  // Arka plan kaydırma kilidi (sayaçlı).
  useEffect(() => {
    if (scrollLocks === 0) { savedOverflow = document.body.style.overflow; document.body.style.overflow = 'hidden' }
    scrollLocks += 1
    return () => { scrollLocks -= 1; if (scrollLocks === 0) document.body.style.overflow = savedOverflow }
  }, [])

  async function submitComment() {
    const text = body.trim()
    if (!text || saving || !canCommentOn(incident)) return
    setSaving(true)
    try {
      const res = await api.monitoring.incidents.addComment(id, text)
      if (!res?.success) { toast.error(res?.error || t('incov.commentError')); return }
      setBody('')
      toast.success(t('incov.commentAdded'))
      onCommentDelta?.(1)
      load()
    } catch {
      toast.error(t('incov.commentError'))
    } finally {
      setSaving(false)
    }
  }
  async function removeComment(commentId) {
    try {
      const res = await api.monitoring.incidents.deleteComment(commentId)
      if (!res?.success) { toast.error(res?.error || t('incov.commentError')); return }
      toast.success(t('incov.commentDeleted'))
      onCommentDelta?.(-1)
      load()
    } catch {
      toast.error(t('incov.commentError'))
    }
  }

  if (!incident) return null
  const open = isOpen(incident)
  const name = incident.monitor?.name || incident.domain || '—'
  // Süre kendi yaprağında tazelenir (LiveDuration): pencere saniyede bir yeniden çizilmez (2026-11-13).
  const durText = (fmt) => (
    <LiveDuration since={incident.started_at} until={incident.resolved_at} live={open} format={fmt} />
  )
  const timeline = buildTimeline({ incident, comments, notifications: deliveries.history })
  const typeKey = MONITOR_TYPE_LABEL_KEY[incident.monitor?.type] || MONITOR_TYPE_LABEL_KEY.cert
  const nComments = incident.comment_count ?? comments.length
  const act = canActOn(incident)
  const canComment = canCommentOn(incident)
  const commentById = new Map(comments.map((c) => [String(c.id), c]))
  const alertish = asAlert(incident)
  const ctx = contextFacts(incident)

  return (
    <Sheet open modal={false} onOpenChange={(next) => { if (!next) onClose?.() }}>
      {/* Katman: shadcn Sheet'in z-50'si sayfanın yüzen yardım düğmesinin (App.css .help-fab, 900) ALTINDA kalır; ModalShell (2000)
          ve onay diyalogları (--z-dialog) yine üstte açılsın diye 1000/1001. */}
      {/* data-slot="dialog-overlay": Sheet bir Radix Dialog'dur; ModalShell scrim'iyle aynı kanca (rowAccessibleNames kapısı
          bu örtüyü pasif tıklama sayar — kapatma ayrıca X ve Escape ile). */}
      {createPortal(
        <div data-slot="dialog-overlay" aria-hidden="true"
          className="fixed inset-0 z-[1000] bg-black/50 animate-in fade-in-0 motion-reduce:animate-none"
          onClick={(e) => { if (e.target === e.currentTarget) onClose?.() }} />,
        document.body,
      )}
      <SheetContent side="right" showCloseButton={false} data-slot="incident-detail" data-incident-id={incident.id}
        aria-modal="true"
        onInteractOutside={(e) => e.preventDefault()}
        onCloseAutoFocus={(e) => e.preventDefault()}
        className="z-[1001] flex h-[100dvh] w-full flex-col gap-0 p-0 sm:w-[min(42rem,calc(100vw-2rem))] sm:max-w-none">
        <SheetHeader className="shrink-0 flex-row items-start justify-between gap-3 border-b px-4 py-3 text-left">
          <div className="min-w-0">
            <SheetTitle className="flex items-center gap-2 leading-snug">
              <MonitorTypeIcon type={incident.monitor?.type} />
              <span className="min-w-0 [overflow-wrap:anywhere]">{t('incov.detailTitle', incident.id)}</span>
            </SheetTitle>
            <SheetDescription className="mt-0.5 [overflow-wrap:anywhere]">{name}</SheetDescription>
          </div>
          <SheetClose asChild>
            <Button type="button" variant="ghost" size="icon" className="-mt-1 -mr-2 shrink-0 text-muted-foreground pointer-coarse:size-10" aria-label={t('app.close')}>
              <X aria-hidden="true" />
            </Button>
          </SheetClose>
        </SheetHeader>

        <div data-slot="incident-detail-body" className="min-h-0 flex-1 overflow-y-auto px-4 pb-4">
          {/* Durum şeridi */}
          <div className="flex flex-wrap items-center gap-1.5 pt-3">
            <IncidentStatusBadge status={incident.status} />
            {open && isAcked(incident) && <AckBadge />}
            <SeverityBadge level={incident.alert_level} />
            <RootCauseChip rc={incident.root_cause} />
            <RecurrenceBadge count={incident.recurrence_7d} />
            <span className="ml-auto inline-flex items-center gap-1 text-xs whitespace-nowrap text-muted-foreground tabular-nums">
              <Clock aria-hidden="true" className="size-3.5" />
              {durText((d) => (open ? t('incov.ongoingFor', d) : t('incov.lastedFor', d)))}
            </span>
          </div>

          {/* Başka ekibin olayı — salt okunur (rozet + sahibi ekip + tek satır açıklama) */}
          {foreign && (
            <div data-slot="incident-read-only" className="mt-3 flex min-w-0 flex-col gap-1.5">
              <ReadOnlyBadge teamId={incident.team_id} teamName={incident.team_name} />
              <p className="m-0 text-xs text-muted-foreground">{t('incov.foreignNote')}</p>
            </div>
          )}

          {/* Eylemler — API'nin sunduğu kadar: onayla / çöz (gerekçeli), izlemeyi aç, bağlantıyı kopyala, sil (yönetici) */}
          <div data-slot="incident-actions" className="mt-3 flex flex-wrap items-center gap-2">
            {act && open && !isAcked(incident) && onAck && (
              <Button type="button" variant="outline" size="sm" className={ACTION} onClick={() => onAck(incident)}>
                <UserCheck aria-hidden="true" />{t('incov.ack')}
              </Button>
            )}
            {act && open && onResolve && (
              <Button type="button" variant="success" size="sm" className={ACTION} onClick={() => onResolve(incident)}>
                <CheckCircle2 aria-hidden="true" />{t('incov.resolve')}
              </Button>
            )}
            {/* Yeniden bildir (2026-11-13): sunucunun `can_notify` bayrağı — kendi görüş kapsamı + alerts.actions */}
            {canNotifyOn(incident) && onNotify && (
              <Button type="button" variant="outline" size="sm" className={ACTION} data-slot="incident-renotify"
                disabled={notifying} aria-busy={notifying || undefined} onClick={() => onNotify(incident)}>
                <BellPlus aria-hidden="true" />{notifying ? t('alh.sending') : t('alh.renotify')}
              </Button>
            )}
            {!foreign && (
              <Button asChild variant="outline" size="sm" className={ACTION}>
                <a href={incidentHref(incident)}><ExternalLink aria-hidden="true" />{t('incov.openMonitor')}</a>
              </Button>
            )}
            {canOpenAlert && <Button asChild variant="outline" size="sm" className={ACTION}>
              <a data-slot="incident-open-alert" href={`?tab=alerthistory&alert=${encodeURIComponent(incident.id)}`}
                onClick={(e) => inApp(e, 'alerthistory', { alert: String(incident.id) }, onClose)}>
                <History aria-hidden="true" />{t('incov.openInAlertHistory')}
              </a>
            </Button>}
            {/* Olay kaydı köprüsü (Alarm Geçmişi ile aynı): "Olay kaydı aç" ya da bağlı kaydın bağlantısı */}
            <AlertIncidentLink alert={alertish} teamName={incident.team_name} actBlocked={foreign} />
            <CopyLinkButton variant="outline" className={ACTION} url={incidentLink(incident.id)} />
            {canDeleteIncident(incident, isAdmin) && onDelete && (
              <Button type="button" variant="ghost" size="sm" className="ml-auto h-9 text-muted-foreground hover:bg-destructive/10 hover:text-destructive pointer-coarse:h-10"
                aria-label={t('a11y.rowAction', rowName(incident, dateLocale), t('incov.delete'))} onClick={() => onDelete(incident)}>
                <Trash2 aria-hidden="true" />{t('incov.delete')}
              </Button>
            )}
          </div>

          {/* Temel bilgiler */}
          <Card data-slot="incident-facts" className="mt-4 gap-0 py-0">
            <h3 className="px-4 pt-3 text-xs font-bold tracking-wide text-muted-foreground uppercase">{t('incov.facts')}</h3>
            <dl className="m-0 grid grid-cols-1 gap-x-4 gap-y-3 px-4 py-3 sm:grid-cols-2">
              <Fact label={t('incov.colMonitor')} className="sm:col-span-2">
                {/* Başka ekibin izleme sayfası takım kapsamlı — bağlantı çıkmaz sokak olurdu, ad düz metin */}
                {foreign ? <span className="font-semibold">{name}</span> : <MonitorLink inc={incident} className="text-[1em]" />}
                {incident.domain && incident.domain !== name && <div className="text-xs text-muted-foreground [overflow-wrap:anywhere]">{incident.domain}</div>}
              </Fact>
              <Fact label={t('incov.monitorType')}>{t(typeKey)}</Fact>
              <Fact label={t('incov.colTeam')}>
                {(incident.team_name || incident.team_id != null)
                  ? <TeamBadge wrap teamId={incident.team_id} teamName={incident.team_name} />
                  : <span className="text-muted-foreground">{t('incov.noTeam')}</span>}
              </Fact>
              <Fact label={t('incov.colRootCause')} className="sm:col-span-2">
                <RootCauseChip rc={incident.root_cause} />
                {incident.message && <div className="mt-1 text-xs text-muted-foreground [overflow-wrap:anywhere]">{incident.message}</div>}
              </Fact>
              <Fact label={t('incov.colStarted')}><span title={incident.started_at}>{formatIncidentTime(incident.started_at, dateLocale)}</span></Fact>
              <Fact label={t('incov.colDuration')}>{durText(null)}</Fact>
              {!open && incident.resolved_at && (
                <Fact label={t('incov.resolved')} className="sm:col-span-2">
                  <span title={incident.resolved_at}>{formatIncidentTime(incident.resolved_at, dateLocale)}</span>
                  {incident.resolved_by && <div className="mt-1 text-xs"><ResolvedBy by={incident.resolved_by} /></div>}
                </Fact>
              )}
              <Fact label={t('incov.colComments')}>{nComments === 1 ? t('incov.commentOne') : t('incov.comments', nComments)}</Fact>
              <Fact label={t('incov.incidentId')}>
                <span className="inline-flex items-center gap-1 font-mono text-xs"><Hash aria-hidden="true" className="size-3 text-muted-foreground" />
                  <CopyableRef value={String(incident.id)} copyLabel={t('share.copyLink')} copiedLabel={t('share.copied')} />
                </span>
              </Fact>
            </dl>
          </Card>

          {/* Bağlam (2026-11-13): gerekçeler, sessiz kapanış, hatırlatma, bakım, fırtına, 7/24 iletimi, nöbetçi */}
          {(ctx.any || !foreign) && (
            <Card data-slot="incident-context" className="mt-4 gap-0 py-0">
              <h3 className="px-4 pt-3 text-xs font-bold tracking-wide text-muted-foreground uppercase">{t('incov.context')}</h3>
              <dl className="m-0 grid grid-cols-1 gap-x-4 gap-y-3 px-4 py-3 sm:grid-cols-2">
                {ctx.ackNote && (
                  <Fact label={t('incov.ctx.ackNote')} className="sm:col-span-2">
                    <span data-slot="incident-ack-note" className="whitespace-pre-wrap">{ctx.ackNote}</span>
                  </Fact>
                )}
                {ctx.resolveNote && (
                  <Fact label={t('incov.ctx.resolveNote')} className="sm:col-span-2">
                    <span data-slot="incident-resolve-note" className="whitespace-pre-wrap">{ctx.resolveNote}</span>
                  </Fact>
                )}
                {ctx.silent && (
                  <Fact label={t('incov.ctx.silent')} className="sm:col-span-2">
                    <span data-slot="incident-silent" className="inline-flex items-start gap-1.5 text-xs text-muted-foreground">
                      <VolumeX aria-hidden="true" className="mt-0.5 size-3.5 shrink-0" />{t('incov.ctx.silentHint')}
                    </span>
                  </Fact>
                )}
                {ctx.realertKnown && (
                  <Fact label={t('incov.ctx.realert')}>
                    <span data-slot="incident-realert" className="inline-flex items-center gap-1.5">
                      <Repeat aria-hidden="true" className="size-3.5 shrink-0 text-muted-foreground" />
                      {ctx.realertCount > 0
                        ? t('incov.ctx.realertValue', ctx.realertCount, ctx.lastReAlertAt ? formatIncidentTime(ctx.lastReAlertAt, dateLocale) : '—')
                        : t('incov.ctx.realertNone')}
                    </span>
                  </Fact>
                )}
                {ctx.maintenance && (
                  <Fact label={t('incov.ctx.maintenance')} className="sm:col-span-2">
                    <span data-slot="incident-maintenance" className="inline-flex items-start gap-1.5 text-xs text-muted-foreground">
                      <Wrench aria-hidden="true" className="mt-0.5 size-3.5 shrink-0" />{t('incov.ctx.maintenanceHint')}
                    </span>
                  </Fact>
                )}
                {(incident.storm_id != null || incident.noc_sent_at) && (
                  <div className="flex min-w-0 flex-wrap items-center gap-2 sm:col-span-2">
                    {incident.storm_id != null && <StormLink stormId={incident.storm_id} />}
                    <NocSentBadge sentAt={incident.noc_sent_at} viaStorm={incident.noc_via_storm} long />
                  </div>
                )}
                {!foreign && <AlertOnCall alert={alertish} Fact={Fact} />}
              </dl>
            </Card>
          )}

          {/* 7/24 arama kayıtları: olay = uyarı (aynı kimlik) — son 3 kayıt + Alarm Geçmişi bağlantısı */}
          {(!foreign || nocOperator) && <NocCallSummary key={incident.id} alertId={incident.id} />}

          {/* Türe özgü ayrıntı: veritabanı sorgusu satırları (yalnız DBQUERY_*), sayfa bütünlüğü sorunlu kaynakları */}
          {!foreign && <DbQueryAlertRows alertId={incident.id} alertType={incident.alert_type} />}
          {Array.isArray(pageIssues) && pageIssues.length > 0 && (
            <section data-slot="incident-page-issues">
              <h3 className={SECTION}>{t('alh.pageIssuesTitle')}</h3>
              <PageIssueList issues={pageIssues} max={20} />
            </section>
          )}

          {/* Kontrol kanıtları — Aktivite kaydından başarısız / zaman aşımı kontrolleri */}
          <IncidentCheckEvidence incident={incident} onLeave={onClose} />

          {/* Zaman çizelgesi */}
          <h3 className={SECTION}>{t('incov.timeline')}</h3>
          {commentsFailed && (
            <div data-slot="incident-comments-failed" role="alert"
              className="mb-2 flex flex-wrap items-center gap-2 rounded-md border border-destructive/30 bg-destructive/5 px-3 py-2 text-xs text-destructive">
              <span className="min-w-0 flex-1 [overflow-wrap:anywhere]">{t('incov.commentsLoadFailed')}</span>
              <Button type="button" variant="outline" size="sm" className={ACTION} onClick={load}><RotateCcw aria-hidden="true" />{t('incov.retry')}</Button>
            </div>
          )}
          {deliveries.failed && (
            <div data-slot="incident-notifications-failed" role="alert"
              className="mb-2 flex flex-wrap items-center gap-2 rounded-md border border-destructive/30 bg-destructive/5 px-3 py-2 text-xs text-destructive">
              <span className="min-w-0 flex-1 [overflow-wrap:anywhere]">{t('incov.notificationsLoadFailed')}</span>
              <Button type="button" variant="outline" size="sm" className={ACTION} onClick={() => setDeliveryNonce((n) => n + 1)}>
                <RotateCcw aria-hidden="true" />{t('incov.retry')}
              </Button>
            </div>
          )}
          {loading && comments.length === 0 && !commentsFailed ? <LoadingBlock label={t('tbl.loading')} fullWidth /> : (
            <ol data-slot="incident-timeline" className="relative m-0 flex list-none flex-col gap-3 p-0 pl-9 before:absolute before:top-3 before:bottom-3 before:left-[15px] before:w-px before:bg-border">
              {timeline.map((ev) => {
                const s = EVENT_STYLE[ev.kind]
                const when = ev.when ? formatIncidentTime(ev.when, dateLocale) : t('incov.ev.timeUnknown')
                return (
                  <li key={ev.id} data-slot="timeline-event" data-kind={ev.kind} className="relative min-w-0">
                    <span aria-hidden="true" className={cn('absolute top-0 -left-9 grid size-8 place-items-center rounded-full border', s.ink)}>
                      <s.Icon className="size-4" />
                    </span>
                    <div className="min-w-0 rounded-lg border bg-card px-3 py-2">
                      <div className="flex min-w-0 flex-wrap items-center gap-x-2 gap-y-0.5">
                        <span className="min-w-0 text-sm font-semibold [overflow-wrap:anywhere]">
                          {ev.kind === 'opened' && t('incov.ev.opened')}
                          {ev.kind === 'notified' && (ev.recipient ? t('incov.ev.notified', ev.recipient) : t('incov.ev.notifiedShort'))}
                          {ev.kind === 'acknowledged' && (ev.by ? t('incov.ev.acknowledgedBy', ev.by) : t('incov.ev.acknowledged'))}
                          {ev.kind === 'comment' && ev.author}
                          {ev.kind === 'resolved' && t('incov.ev.resolved')}
                        </span>
                        {ev.kind === 'opened' && <SeverityBadge level={ev.level} className="text-[0.7em]" />}
                        {ev.kind === 'notified' && ev.trigger && (
                          <Badge variant="outline" className="h-auto overflow-visible rounded-md text-[0.7em] whitespace-normal">{mailTriggerText(t, ev.trigger)}</Badge>
                        )}
                        <span className="ml-auto text-xs whitespace-nowrap text-muted-foreground" title={ev.when || undefined}>{when}</span>
                        {ev.kind === 'comment' && canDeleteComment(commentById.get(String(ev.commentId)), incident) && (
                          <Button type="button" variant="ghost" size="icon-xs" title={t('incov.delete')}
                            aria-label={t('a11y.rowAction', `${ev.author} · ${when}`, t('incov.delete'))}
                            className="text-muted-foreground hover:text-destructive pointer-coarse:size-10" onClick={() => removeComment(ev.commentId)}>
                            <Trash2 aria-hidden="true" />
                          </Button>
                        )}
                      </div>
                      {ev.kind === 'opened' && ev.message && <p className="mt-1 text-xs text-muted-foreground [overflow-wrap:anywhere]">{ev.message}</p>}
                      {ev.kind === 'notified' && (ev.email || ev.webhook) && (
                        <p className="mt-1 flex flex-wrap items-center gap-x-3 gap-y-1 text-xs text-muted-foreground">
                          {ev.email && <span className="inline-flex flex-wrap items-center gap-1">{t('incov.emailLabel')}: <EmailStatusBadge status={ev.email} /></span>}
                          {ev.webhook && <span className="inline-flex flex-wrap items-center gap-1">{t('incov.webhookLabel')}: <EmailStatusBadge status={ev.webhook} /></span>}
                        </p>
                      )}
                      {ev.kind === 'comment' && (
                        <p className="m-0 mt-1 min-w-0 text-sm whitespace-pre-wrap [overflow-wrap:anywhere]">{ev.body}</p>
                      )}
                      {ev.kind === 'resolved' && ev.by && <p className="mt-1 text-xs"><ResolvedBy by={ev.by} /></p>}
                    </div>
                  </li>
                )
              })}
            </ol>
          )}

          {/* Bildirim teslimatları — e-posta önizlemesi + push partileri + kapsayan fırtına push'u (Alarm Geçmişi paneli) */}
          {!foreign && (
            <section data-slot="incident-deliveries">
              <h3 className={SECTION}>{t('incov.notifications')}</h3>
              {showStormPushSection(incident, deliveries.loading ? null : deliveries.stormPush) && (
                <StormPushSection data={deliveries.stormPush} loading={deliveries.loading} />
              )}
              <AlertNotificationsPanel history={deliveries.history} loadingHistory={deliveries.loading} loadFailed={deliveries.failed}
                pushGroups={pushGroups} alertLevel={incident.alert_level} />
            </section>
          )}
        </div>

        {/* Yorum yazıcı — altta sabit; Ctrl/⌘+Enter gönderir. Eylem hakkı yoksa yerinde nedeni yazar. */}
        <Separator />
        {!canComment ? (
          <p data-slot="incident-composer-locked"
            className="m-0 flex shrink-0 items-center gap-2 px-4 py-3 pb-[max(0.75rem,env(safe-area-inset-bottom))] text-xs text-muted-foreground">
            <Lock aria-hidden="true" className="size-3.5 shrink-0" />
            {foreign ? t('incov.composerLocked.foreign')
              : incident?.can_comment === false ? t('incov.composerLocked.noComment') : t('incov.composerLocked.noPermission')}
          </p>
        ) : (
          <form data-slot="incident-composer" className="shrink-0 flex flex-col gap-2 px-4 py-3 pb-[max(0.75rem,env(safe-area-inset-bottom))]"
            onSubmit={(e) => { e.preventDefault(); submitComment() }}>
            <Textarea ref={composerRef} rows={2} value={body} placeholder={t('incov.commentPlaceholder')} aria-label={t('incov.commentPlaceholder')}
              onChange={(e) => setBody(e.target.value)}
              onKeyDown={(e) => { if ((e.ctrlKey || e.metaKey) && e.key === 'Enter') { e.preventDefault(); submitComment() } }} />
            <div className="flex items-center justify-between gap-2">
              <span className="text-[11px] text-muted-foreground">{t('incov.commentHint')}</span>
              <Button type="submit" size="sm" className={ACTION} disabled={saving || !body.trim()} aria-busy={saving || undefined}>
                <Send aria-hidden="true" />{t('incov.addComment')}
              </Button>
            </div>
          </form>
        )}
      </SheetContent>
    </Sheet>
  )
}

/** Bağlam kartının alanları — yalnız sunucunun gönderdiği (tam satır) değerler; yoksa satır çizilmez. */
function contextFacts(inc) {
  const ackNote = typeof inc?.acknowledged_note === 'string' && inc.acknowledged_note.trim() ? inc.acknowledged_note : null
  const resolveNote = typeof inc?.resolved_note === 'string' && inc.resolved_note.trim() ? inc.resolved_note : null
  const silent = inc?.resolved_silently === true
  const realertKnown = Object.prototype.hasOwnProperty.call(inc || {}, 'realert_count')
  const realertCount = Number(inc?.realert_count) || 0
  const maintenance = inc?.in_maintenance === true
  const any = Boolean(ackNote || resolveNote || silent || maintenance || inc?.storm_id != null || inc?.noc_sent_at)
  return { ackNote, resolveNote, silent, realertKnown, realertCount, lastReAlertAt: inc?.last_re_alert_at || null, maintenance, any }
}
