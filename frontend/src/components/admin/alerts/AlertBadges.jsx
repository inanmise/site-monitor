import { OctagonAlert, TriangleAlert, CircleAlert, CheckCircle2, Clock, RefreshCcw, MailX, ExternalLink, Bot, Siren, CloudLightning } from 'lucide-react'
import { useT } from '../../../i18n/index.jsx'
import { alertTypeMeta, alertTypeLabel } from '../../../utils/alertTypeMeta.js'
import { formatDuration } from '../../../utils/incidentMeta.js'
import { systemResolverKey } from '../../../utils/resolvedBy.js'
import UserBadge from '../../ui/UserBadge.jsx'
import ToneBadge from '../ToneBadge.jsx'
import { Badge } from '@/components/shadcn/badge'
import { Button } from '@/components/shadcn/button'
import { cn } from '@/lib/utils'
import { levelClass, alertHref, alertSourceTab, closesAutomatically } from './alertHistoryModel.js'

/**
 * Alarm Geçmişi'nin küçük, tekrar eden rozetleri — Olaylar konsolu (incidents/IncidentBadges) ile AYNI görsel dil:
 * önem rozeti aynı ton/ikon, durum rozeti aynı nabız/onay deseni, kaynağa bağlantı aynı link düğmesi. Sol renk
 * şeridi YOK; seviye yalnız rozetle (ve kritikte kartın tüm çerçevesiyle) okunur. Test kancaları: `data-slot` +
 * `data-level` / `data-status`; seviye rozeti ayrıca `data-level-badge` (tema sözleşmesi testi).
 */
const LEVEL_STYLE = {
  critical: { Icon: OctagonAlert, cls: 'border-transparent bg-destructive text-white' },
  high:     { Icon: TriangleAlert, cls: 'border-orange-500/40 bg-orange-500/10 text-orange-700 dark:text-orange-300' },
  warning:  { Icon: CircleAlert,   cls: 'border-amber-500/40 bg-amber-500/10 text-amber-700 dark:text-amber-300' },
}

/** Önem rozeti (alert_level) — kritik dolu kırmızı, yüksek turuncu, uyarı amber; bilinmeyen düz çerçeve. */
export function AlertLevelBadge({ level, className }) {
  const t = useT()
  const key = levelClass(level)
  const style = LEVEL_STYLE[key]
  if (!style) {
    return level
      ? <Badge variant="outline" data-slot="alert-level" data-level-badge="" data-level={key} className={cn('whitespace-nowrap', className)}>{level}</Badge>
      : null
  }
  return (
    <Badge variant="outline" data-slot="alert-level" data-level-badge="" data-level={key}
      className={cn('gap-1 font-bold tracking-wide whitespace-nowrap uppercase', style.cls, className)}>
      <style.Icon aria-hidden="true" />{t(`alh.level.${key}`)}
    </Badge>
  )
}

/** Durum rozeti — açık: kırmızı + nabız noktası; kapalı: yeşil onay (IncidentStatusBadge deseni). */
export function AlertStateBadge({ resolved, className }) {
  const t = useT()
  return resolved
    ? (
      <Badge variant="outline" data-slot="alert-state" data-status="resolved"
        className={cn('gap-1 border-success/30 bg-success/10 font-bold whitespace-nowrap text-success', className)}>
        <CheckCircle2 aria-hidden="true" />{t('alh.stateResolved')}
      </Badge>
    )
    : (
      <Badge variant="outline" data-slot="alert-state" data-status="open"
        className={cn('gap-1.5 border-destructive/30 bg-destructive/10 font-bold whitespace-nowrap text-destructive', className)}>
        <span aria-hidden="true" className="size-2 rounded-full bg-destructive ring-[3px] ring-destructive/20 motion-safe:animate-pulse" />
        {t('alh.stateOpen')}
      </Badge>
    )
}

/** Tür simgesi — alertTypeMeta ikonu + rengi, kutulu (MonitorTypeIcon ile aynı boyut); ekran okuyucuya tür adı. */
export function AlertTypeIcon({ type, className }) {
  const t = useT()
  const { icon: Icon, color } = alertTypeMeta(type)
  const label = alertTypeLabel(t, type)
  return (
    <span data-slot="alert-type-icon" data-type={type || ''} title={label} style={{ color }}
      className={cn('inline-grid size-7 shrink-0 place-items-center rounded-md border bg-muted/60', className)}>
      <Icon aria-hidden="true" className="size-4" />
      <span className="sr-only">{label}</span>
    </span>
  )
}

/** Tür çipi — ikon + okunur ad, tür rengiyle (utils/alertTypeMeta tek kaynak; bilinmeyen tip ham adıyla). */
export function AlertTypeChip({ type, className }) {
  const t = useT()
  const { icon: Icon, color } = alertTypeMeta(type)
  return (
    <span data-slot="alert-type" data-type={type || ''} style={{ color }}
      className={cn('inline-flex min-w-0 items-center gap-1 text-[0.82em] font-semibold', className)}>
      <Icon aria-hidden="true" className="size-3.5 shrink-0" />
      <span className="truncate">{alertTypeLabel(t, type)}</span>
    </span>
  )
}

/**
 * "Ne kadardır açık" — açık alarmın en kritik sayısı, CANLI (`nowMs`). Eşiği aşan (stale) alarm vurgulanır:
 * çözülmemiş ya da unutulmuş. UTC ayrıştırma: sunucu damgaları zone'suz UTC (Europe/Istanbul'da 3 saat sapardı).
 * Test kancaları: `data-open-for` + `data-stale`.
 */
export function OpenDurationBadge({ createdAt, staleHours = 24, nowMs, className }) {
  const t = useT()
  if (!createdAt) return null
  const utc = createdAt.endsWith('Z') || createdAt.includes('+') ? createdAt : createdAt + 'Z'
  const ms = (nowMs ?? Date.now()) - new Date(utc).getTime()
  if (!Number.isFinite(ms) || ms < 0) return null
  const stale = ms >= staleHours * 3_600_000
  return (
    <Badge variant="outline" data-open-for="" data-stale={stale ? 'true' : 'false'}
      className={cn('gap-1 rounded-full font-semibold whitespace-nowrap text-muted-foreground tabular-nums',
        stale && 'border-orange-500/40 bg-orange-500/10 text-orange-700 dark:text-orange-300', className)}
      title={stale ? t('alh.openForStaleTip', staleHours) : t('alh.openForTip')}>
      <Clock aria-hidden="true" className="size-3" />{t('alh.openFor', formatDuration(ms, t))}
    </Badge>
  )
}

/** "Bu ay N. kez" — son 30 günde aynı hedef + tip; 1 ise ÇİZİLMEZ (her karta "1. kez" yazmak gürültü). */
export function RepeatBadge({ count, className }) {
  const t = useT()
  if (!count || count < 2) return null
  return (
    <Badge variant="outline" data-repeat="" className={cn('gap-1 rounded-full font-semibold whitespace-nowrap text-muted-foreground', className)} title={t('alh.repeatTip', count)}>
      <RefreshCcw aria-hidden="true" className="size-3" />{t('alh.repeat', count)}
    </Badge>
  )
}

/** E-posta gönderilemedi rozeti (email_failed_count > 0). */
export function SendFailedBadge({ count, className }) {
  const t = useT()
  if (!(Number(count) > 0)) return null
  return (
    <Badge variant="outline" data-slot="alert-send-failed"
      className={cn('gap-1 rounded-full border-destructive/30 bg-destructive/10 font-bold whitespace-nowrap text-destructive', className)} title={t('alh.sendFailedTip')}>
      <MailX aria-hidden="true" className="size-3" />{t('alh.sendFailed')}
    </Badge>
  )
}

/**
 * Alarm fırtınası üyeliği rozeti (storm_id dolu) — 2026-09-30, prod olayı: fırtınaya devredilen alarmın bireysel e-postası
 * ve push'u gitmez, takım toplu fırtına bildirimiyle haberdar edilir. Eskiden bu bağ ekranda hiç görünmüyordu ve
 * kullanıcı "neden bildirim gelmedi" sorusunun cevabını arıyordu.
 */
export function StormBadge({ stormId, className }) {
  const t = useT()
  if (stormId == null) return null
  return (
    <Badge variant="outline" data-slot="alert-storm" data-storm-id={stormId}
      className={cn('gap-1 rounded-full border-violet-500/40 bg-violet-500/10 font-semibold whitespace-nowrap text-violet-700 dark:text-violet-300', className)}
      title={t('alh.storm.tip')}>
      <CloudLightning aria-hidden="true" className="size-3" />{t('alh.storm.badge', stormId)}
    </Badge>
  )
}

/** Kaynağa git — izleme sekmesi + arama ya da Tüm Sertifikalar + alan adı. Kart tıklamasına SIZMAZ. */
export function AlertSourceLink({ alert: a, className, iconOnly = false, variant = 'outline', size = 'sm', ariaLabel }) {
  const t = useT()
  const label = alertSourceTab(a?.alert_type) ? t('alh.openMonitor') : t('alh.openCerts')
  return (
    // Preflight yok: <a> tarayıcı varsayılanı (mavi + altı çizili) çizilirdi → renk ve çizgi açıkça verilir.
    <Button asChild variant={variant} size={size} className={cn('no-underline', variant === 'outline' && 'text-foreground', className)}>
      <a href={alertHref(a)} title={label} aria-label={ariaLabel ?? (iconOnly ? label : undefined)} onClick={(e) => e.stopPropagation()}>
        <ExternalLink aria-hidden="true" />{iconOnly ? null : label}
      </a>
    </Button>
  )
}

/** Kim çözdü — sistem jetonu (otomatik kapanış: system / inventory_delete / inventory_deactivate) ya da kişi. */
export function AlertResolvedBy({ by, className }) {
  const t = useT()
  if (!by) return null
  const key = systemResolverKey(by)
  if (key) {
    return (
      <span data-slot="resolved-by" data-token={String(by)} className={cn('inline-flex items-center gap-1.5 text-muted-foreground', className)}>
        <Bot aria-hidden="true" className="size-3.5 shrink-0" />{t(key)}
      </span>
    )
  }
  return <span data-slot="resolved-by" className={cn('inline-flex min-w-0', className)}><UserBadge username={by} inline size="sm" /></span>
}

/**
 * "Neden hâlâ açık?" çipleri (2026-09-12, #16): onay durumu, GERÇEK e-posta gönderim sayısı (notification_logs —
 * kademe kontağı listesi DEĞİL, 2026-09-17), push gönderildi/başarısız ve hiç ulaşmadıysa kırmızı uyarı.
 * 2026-09-29: İLK çip kapanış kuralı — otomatik kapanan türde "kontroller düzelince kendiliğinden kapanır", değişiklik
 * alarmında "yalnız elle kapanır". Eskiden panel yalnız onay/bildirim gösteriyordu; sağlıklı izlemede asılı kalan alarmda
 * "kimseye ulaşmadı" çipi alarmın açık kalma NEDENİ gibi okunuyordu (bildirim, kapanışı etkilemez).
 * Test kancası: `data-why` (role="group"), kapanış çipi `data-why-close` + `data-auto`.
 * 2026-09-29 (fırtına): alarm bir alarm fırtınasının ÜYESİYSE (`storm_id`) bireysel e-posta/push bilinçli olarak
 * gönderilmez — bildirim takımın TEK toplu fırtına postasına devredilir ve bu alarma yazılmaz. Sayaçlar 0 olduğu için
 * "kimseye ulaşmadı" (kırmızı) çipi yanıltıcıydı; üyede onun yerine "Fırtına bildirimine devredildi" (bilgi) görünür.
 * Test kancası `data-why-storm`.
 */
export function WhyOpenChips({ alert: a, push, className }) {
  const t = useT()
  const sent = push?.sent ?? 0, failed = push?.failed ?? 0, skipped = push?.skipped ?? 0
  const mailSent = Number(a?.email_sent_count ?? 0), mailFailed = Number(a?.email_failed_count ?? 0)
  const stormMember = a?.storm_id != null
  const nobody = mailSent === 0 && sent === 0 && !stormMember
  const auto = closesAutomatically(a?.alert_type)
  const chip = 'rounded-full font-normal'
  return (
    <div data-why="" role="group" aria-label={t('alh.whyOpen')} className={cn('flex min-w-0 flex-wrap items-center gap-1.5 text-[0.78em]', className)}>
      <span className="text-[0.9em] font-bold tracking-wide text-muted-foreground uppercase">{t('alh.whyOpen')}</span>
      <ToneBadge tone={auto ? 'info' : 'warning'} className={chip} data-why-close="" data-auto={auto ? 'true' : 'false'}
        title={t(auto ? 'alh.whyAutoTip' : 'alh.whyManualTip')}>
        {t(auto ? 'alh.whyAuto' : 'alh.whyManual')}
      </ToneBadge>
      <ToneBadge tone={a.acknowledged ? 'success' : 'warning'} className={chip}>
        {a.acknowledged ? t('alh.whyAcked', a.acknowledged_by || '—') : t('alh.whyUnacked')}
      </ToneBadge>
      <ToneBadge tone={mailFailed > 0 ? 'danger' : mailSent > 0 ? 'neutral' : 'muted'} className={chip}>
        {mailSent > 0
          ? (mailFailed > 0 ? t('alh.whyEmailMixed', mailSent, mailFailed) : t('alh.whyEmail', mailSent))
          : (mailFailed > 0 ? t('alh.whyEmailFailed', mailFailed) : t('alh.whyEmailNone'))}
      </ToneBadge>
      <ToneBadge tone={failed > 0 ? 'danger' : sent > 0 ? 'neutral' : 'muted'} className={chip} title={push ? t('alh.whyPushTip', sent, failed, skipped) : undefined}>
        {push ? t('alh.whyPush', sent, failed) : t('alh.whyPushNone')}
      </ToneBadge>
      {stormMember && (
        <ToneBadge tone="info" className={chip} data-why-storm="" title={t('alh.whyStormTip')}>{t('alh.whyStorm')}</ToneBadge>
      )}
      {nobody && <ToneBadge tone="danger" className={chip}>{t('alh.whyNobody')}</ToneBadge>}
    </div>
  )
}

/**
 * Sahiplen / Çöz / Tekrar bildir KAPALIYSA nedeni (alertHistoryModel.actBlockReason): başka takımın uyarısı (7/24
 * operatörü), izin yok (`alerts.actions`) ya da izin yok ama arama kaydı girilebilir (AUDIT 7/24 operatörü).
 * Kart ve detay aynı metni gösterir. Test kancası `data-slot="alert-act-blocked"` + `data-reason`.
 */
export function ActBlockedNote({ reason, className }) {
  const t = useT()
  if (!reason) return null
  const text = reason === 'perm' ? t('alh.actNoPermission')
    : reason === 'permNoc' ? t('alh.actNoPermissionNoc')
      : t('alh.actOtherTeam')
  return <p data-slot="alert-act-blocked" data-reason={reason} className={cn('basis-full text-xs text-muted-foreground', className)}>{text}</p>
}

/** Sayfa başlığındaki durum ikonu (ikon kutusu). */
export const ALERT_PAGE_ICON = Siren
