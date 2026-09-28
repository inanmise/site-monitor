import { useCallback, useEffect, useRef, useState } from 'react'
import { createPortal } from 'react-dom'
import {
  X, Siren, Bell, UserCheck, MessageSquare, CheckCircle2, Send, Trash2, ExternalLink, Clock, Hash, Lock,
} from 'lucide-react'
import { api } from '../../api/client'
import { useT, useDateLocale } from '../../i18n/index.jsx'
import { useToast } from '../ui/Toast.jsx'
import { durationMs, formatDuration, formatIncidentTime } from '../../utils/incidentMeta.js'
import { LoadingBlock } from '../ui/Progress.jsx'
import TeamBadge from '../ui/TeamBadge.jsx'
import ReadOnlyBadge from '../ui/ReadOnlyBadge.jsx'
import UserBadge from '../ui/UserBadge.jsx'
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
} from './incidentsModel.js'
import { NocCallSummary } from '../admin/alerts/NocCallLog.jsx'   // 7/24 arama kayıtları — salt okunur özet (2026-09-27)

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
  incident, onClose, isAdmin = false, onAck, onResolve, onDelete, onCommentDelta, focusComposer = false,
}) {
  const t = useT()
  const dateLocale = useDateLocale()
  const toast = useToast()
  const [comments, setComments] = useState([])
  const [notifications, setNotifications] = useState([])
  const [loading, setLoading] = useState(true)
  const [body, setBody] = useState('')
  const [saving, setSaving] = useState(false)
  const [nowMs, setNowMs] = useState(Date.now())
  const composerRef = useRef(null)
  const id = incident?.id
  const foreign = isForeign(incident)
  const nocOperator = usePermissions().canEdit('noc_calls.write')

  const load = useCallback(async () => {
    if (id == null) return
    setLoading(true)
    try {
      const [c, n] = await Promise.all([
        api.monitoring.incidents.comments(id).catch(() => null),
        // Bildirim geçmişi (e-posta günlüğü): alerts.read + takım kapsamı — yetkisiz/hatalı yanıt sessizce boş kalır.
        // Başka ekibin olayında HİÇ istenmez: alıcılar takım içi veri (uç 403 döner, boşuna istek olurdu).
        foreign ? Promise.resolve(null) : api.admin.getAlertNotifications(id).catch(() => null),
      ])
      setComments(c?.success ? (c.data ?? []) : [])
      setNotifications(n?.success && Array.isArray(n.data) ? n.data : [])
    } finally {
      setLoading(false)
    }
  }, [id, foreign])
  useEffect(() => { load() }, [load])

  // Süren olayın süresi canlı (1 sn); kapalı olayda gereksiz.
  useEffect(() => {
    if (!isOpen(incident)) return undefined
    const i = setInterval(() => setNowMs(Date.now()), 1000)
    return () => clearInterval(i)
  }, [incident])

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
    if (!text || saving || !canActOn(incident)) return
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
  const dur = formatDuration(durationMs(incident.started_at, incident.resolved_at, nowMs), t)
  const timeline = buildTimeline({ incident, comments, notifications })
  const typeKey = MONITOR_TYPE_LABEL_KEY[incident.monitor?.type] || MONITOR_TYPE_LABEL_KEY.cert
  const nComments = incident.comment_count ?? comments.length
  const act = canActOn(incident)

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
              <span className="truncate">{t('incov.detailTitle', incident.id)}</span>
            </SheetTitle>
            <SheetDescription className="mt-0.5 line-clamp-2 [overflow-wrap:anywhere]">{name}</SheetDescription>
          </div>
          <SheetClose asChild>
            <Button type="button" variant="ghost" size="icon" className="-mt-1 -mr-2 shrink-0 text-muted-foreground" aria-label={t('app.close')}>
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
            <span className="ml-auto inline-flex items-center gap-1 text-xs whitespace-nowrap text-muted-foreground tabular-nums">
              <Clock aria-hidden="true" className="size-3.5" />{open ? t('incov.ongoingFor', dur) : t('incov.lastedFor', dur)}
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
              <Button type="button" variant="outline" size="sm" className="h-9" onClick={() => onAck(incident)}>
                <UserCheck aria-hidden="true" />{t('incov.ack')}
              </Button>
            )}
            {act && open && onResolve && (
              <Button type="button" variant="success" size="sm" className="h-9" onClick={() => onResolve(incident)}>
                <CheckCircle2 aria-hidden="true" />{t('incov.resolve')}
              </Button>
            )}
            {!foreign && (
              <Button asChild variant="outline" size="sm" className="h-9">
                <a href={incidentHref(incident)}><ExternalLink aria-hidden="true" />{t('incov.openMonitor')}</a>
              </Button>
            )}
            <CopyLinkButton variant="outline" className="h-9" url={incidentLink(incident.id)} />
            {canDeleteIncident(incident, isAdmin) && onDelete && (
              <Button type="button" variant="ghost" size="sm" className="ml-auto h-9 text-muted-foreground hover:bg-destructive/10 hover:text-destructive"
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
                  ? <TeamBadge teamId={incident.team_id} teamName={incident.team_name} />
                  : <span className="text-muted-foreground">{t('incov.noTeam')}</span>}
              </Fact>
              <Fact label={t('incov.colRootCause')} className="sm:col-span-2">
                <RootCauseChip rc={incident.root_cause} />
                {incident.message && <div className="mt-1 text-xs text-muted-foreground [overflow-wrap:anywhere]">{incident.message}</div>}
              </Fact>
              <Fact label={t('incov.colStarted')}><span title={incident.started_at}>{formatIncidentTime(incident.started_at, dateLocale)}</span></Fact>
              <Fact label={t('incov.colDuration')}><span className="tabular-nums">{dur}</span></Fact>
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

          {/* 7/24 arama kayıtları: olay = uyarı (aynı kimlik) — son 3 kayıt + Alarm Geçmişi bağlantısı */}
          {(!foreign || nocOperator) && <NocCallSummary key={incident.id} alertId={incident.id} />}

          {/* Zaman çizelgesi */}
          <h3 className="mt-5 mb-2 text-xs font-bold tracking-wide text-muted-foreground uppercase">{t('incov.timeline')}</h3>
          {loading ? <LoadingBlock label={t('tbl.loading')} fullWidth /> : (
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
                        <span className="text-sm font-semibold">
                          {ev.kind === 'opened' && t('incov.ev.opened')}
                          {ev.kind === 'notified' && (ev.recipient ? t('incov.ev.notified', ev.recipient) : t('incov.ev.notifiedShort'))}
                          {ev.kind === 'acknowledged' && (ev.by ? t('incov.ev.acknowledgedBy', ev.by) : t('incov.ev.acknowledged'))}
                          {ev.kind === 'comment' && ev.author}
                          {ev.kind === 'resolved' && t('incov.ev.resolved')}
                        </span>
                        {ev.kind === 'opened' && <SeverityBadge level={ev.level} className="text-[0.7em]" />}
                        {ev.kind === 'notified' && ev.trigger && <Badge variant="outline" className="text-[0.7em]">{ev.trigger}</Badge>}
                        <span className="ml-auto text-xs whitespace-nowrap text-muted-foreground" title={ev.when || undefined}>{when}</span>
                        {ev.kind === 'comment' && act && (
                          <Button type="button" variant="ghost" size="icon-xs" title={t('incov.delete')}
                            aria-label={t('a11y.rowAction', `${ev.author} · ${when}`, t('incov.delete'))}
                            className="text-muted-foreground hover:text-destructive pointer-coarse:size-8" onClick={() => removeComment(ev.commentId)}>
                            <Trash2 aria-hidden="true" />
                          </Button>
                        )}
                      </div>
                      {ev.kind === 'opened' && ev.message && <p className="mt-1 text-xs text-muted-foreground [overflow-wrap:anywhere]">{ev.message}</p>}
                      {ev.kind === 'notified' && (ev.email || ev.webhook) && (
                        <p className="mt-1 flex flex-wrap gap-x-3 text-xs text-muted-foreground">
                          {ev.email && <span>{t('incov.emailLabel')}: <span className="font-medium text-foreground/80">{ev.email}</span></span>}
                          {ev.webhook && <span>{t('incov.webhookLabel')}: <span className="font-medium text-foreground/80">{ev.webhook}</span></span>}
                        </p>
                      )}
                      {ev.kind === 'comment' && (
                        <div className="mt-1 flex min-w-0 items-start gap-2">
                          <span className="mt-0.5 shrink-0"><UserBadge username={ev.authorUsername || ev.author} displayName={ev.author} inline size="sm" nameOnly /></span>
                          <p className="m-0 min-w-0 text-sm whitespace-pre-wrap [overflow-wrap:anywhere]">{ev.body}</p>
                        </div>
                      )}
                      {ev.kind === 'resolved' && ev.by && <p className="mt-1 text-xs"><ResolvedBy by={ev.by} /></p>}
                    </div>
                  </li>
                )
              })}
            </ol>
          )}
        </div>

        {/* Yorum yazıcı — altta sabit; Ctrl/⌘+Enter gönderir. Eylem hakkı yoksa yerinde nedeni yazar. */}
        <Separator />
        {!act ? (
          <p data-slot="incident-composer-locked"
            className="m-0 flex shrink-0 items-center gap-2 px-4 py-3 pb-[max(0.75rem,env(safe-area-inset-bottom))] text-xs text-muted-foreground">
            <Lock aria-hidden="true" className="size-3.5 shrink-0" />
            {foreign ? t('incov.composerLocked.foreign') : t('incov.composerLocked.noPermission')}
          </p>
        ) : (
          <form data-slot="incident-composer" className="shrink-0 flex flex-col gap-2 px-4 py-3 pb-[max(0.75rem,env(safe-area-inset-bottom))]"
            onSubmit={(e) => { e.preventDefault(); submitComment() }}>
            <Textarea ref={composerRef} rows={2} value={body} placeholder={t('incov.commentPlaceholder')} aria-label={t('incov.commentPlaceholder')}
              onChange={(e) => setBody(e.target.value)}
              onKeyDown={(e) => { if ((e.ctrlKey || e.metaKey) && e.key === 'Enter') { e.preventDefault(); submitComment() } }} />
            <div className="flex items-center justify-between gap-2">
              <span className="text-[11px] text-muted-foreground">{t('incov.commentHint')}</span>
              <Button type="submit" size="sm" className="h-9" disabled={saving || !body.trim()} aria-busy={saving || undefined}>
                <Send aria-hidden="true" />{t('incov.addComment')}
              </Button>
            </div>
          </form>
        )}
      </SheetContent>
    </Sheet>
  )
}
