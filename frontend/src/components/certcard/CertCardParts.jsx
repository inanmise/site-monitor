import {
  BellOff, Building2, CalendarCheck, CalendarClock, CalendarPlus, Copy, Globe, Info, KeyRound, Layers, Link2, Link2Off,
  MailWarning, Network, Pencil, RefreshCw, Server, ShieldAlert, ShieldCheck, ShieldX, Trash2, WifiOff,
} from 'lucide-react'
import { formatDateSec } from '../../api/client'
import { useT } from '../../i18n/index.jsx'
import { toUtc } from '../../utils/localDay.js'
import { relativeTime } from '../admin/audit/auditFormat.js'
import { CARD_LAYER } from '../monitoring/MonitorCard.jsx'
import CertificateLiveStrip from '../CertificateLiveStrip.jsx'
import { CheckNowButton, CheckRunningStrip, MON_ACT, MON_ACT_TONE } from '../ui/CheckRunning.jsx'
import HintPopover from '../ui/HintPopover.jsx'
import KebabMenu from '../ui/KebabMenu.jsx'
import { ProgressBar } from '../ui/Progress.jsx'
import SimpleTooltip from '../ui/SimpleTooltip.jsx'
import TeamBadge from '../ui/TeamBadge.jsx'
import { Badge } from '@/components/shadcn/badge'
import { Button } from '@/components/shadcn/button'
import { cn } from '@/lib/utils'
import { dateOnly, issuerNameOf, keyLabelOf, planState, sigShort } from './certCardModel.js'

/**
 * Sertifika kartının parçaları (2026-09-27 yeniden tasarım) — izleme kartlarıyla aynı görsel dil: tonlu kahraman
 * panel, küçük büyük harfli etiketler, gerekçe çipleri, meta satırı, göreli zaman + kesin ipucu, alta sabit alt çubuk.
 * SOL RENK ŞERİDİ YOK (kalıcı kural): durum rozetle ve kökte `data-status` ile, kritik/dolmuş kart TÜM kenarla.
 *
 * <p>Dokunma: etkileşimli çipler dokunmatik işaretçide `::after` ile 40 px yüksekliğe genişler ({@link TOUCH_CHIP});
 * ikon düğmeleri `pointer-coarse:size-10`. Yalnız-hover bilgi yok: gerekçelerin açıklaması HintPopover'da (dokununca
 * açılır), kesin zaman ekran okuyucuda ve ipucunda.
 */

/** Dokunmatikte ikon düğmesi 40 px (MonitorCardActions ile aynı). */
const TOUCH = 'pointer-coarse:size-10'
/** Dokunmatikte küçük çipin dokunma alanı: ::after dikeyde 8'er px taşar → 24 px çip 40 px hedef. */
export const TOUCH_CHIP = 'pointer-coarse:after:absolute pointer-coarse:after:inset-x-0 pointer-coarse:after:-inset-y-2'

/** Çip tonları — zemin + mürekkep + ince kenar, koyu karşılıklarıyla (Uyarılar sayfasının gerekçe çipleriyle aynı aile). */
export const CHIP_TONE = {
  bad: 'border-destructive/30 bg-destructive/10 text-destructive dark:bg-destructive/20',
  high: 'border-orange-500/30 bg-orange-500/10 text-orange-700 dark:bg-orange-500/20 dark:text-orange-300',
  warn: 'border-amber-500/35 bg-amber-500/10 text-amber-800 dark:bg-amber-500/20 dark:text-amber-300',
  weak: 'border-purple-500/30 bg-purple-500/10 text-purple-700 dark:bg-purple-500/20 dark:text-purple-300',
  ok: 'border-success/30 bg-success/10 text-success dark:bg-success/20',
  info: 'border-primary/25 bg-primary/10 text-primary dark:bg-primary/20',
  plan: 'border-violet-500/30 bg-violet-500/10 text-violet-800 dark:bg-violet-500/20 dark:text-violet-300',
  muted: 'border-border bg-muted text-muted-foreground',
}
/** Tıklanabilir çipte üzerine gelince zemin koyulaşır (ghost düğmenin kendi hover zemini/mürekkebi ezilir). */
export const CHIP_HOVER = {
  bad: 'hover:bg-destructive/20 hover:text-destructive dark:hover:bg-destructive/30',
  high: 'hover:bg-orange-500/20 hover:text-orange-700 dark:hover:bg-orange-500/30 dark:hover:text-orange-300',
  warn: 'hover:bg-amber-500/20 hover:text-amber-800 dark:hover:bg-amber-500/30 dark:hover:text-amber-300',
  weak: 'hover:bg-purple-500/20 hover:text-purple-700 dark:hover:bg-purple-500/30 dark:hover:text-purple-300',
  ok: 'hover:bg-success/20 hover:text-success dark:hover:bg-success/30',
  info: 'hover:bg-primary/20 hover:text-primary dark:hover:bg-primary/30',
  plan: 'hover:bg-violet-500/20 hover:text-violet-800 dark:hover:bg-violet-500/30 dark:hover:text-violet-300',
  muted: 'hover:bg-accent hover:text-foreground dark:hover:bg-accent',
}
/** Çip gövdesi (Badge ya da ghost Button üzerinde aynı ölçü). */
export const CHIP = "h-6 min-w-0 max-w-full gap-1 rounded-full border px-2 py-0 text-[11px] font-semibold whitespace-nowrap shadow-none has-[>svg]:px-2 [&_svg:not([class*='size-'])]:size-3"

// Durum rozeti (sözcükle durum) — tonlar izleme kartı rozetiyle aynı aile; dolmuş = dolu kırmızı (en ağır hâl).
const STATUS = {
  valid: 'bg-success/15 text-success dark:bg-success/20',
  warning: 'bg-amber-500/15 text-amber-700 dark:bg-amber-500/20 dark:text-amber-300',
  high: 'bg-orange-500/15 text-orange-700 dark:bg-orange-500/20 dark:text-orange-300',
  critical: 'bg-destructive/10 text-destructive dark:bg-destructive/20',
  expired: 'bg-destructive text-white dark:bg-destructive/80',
  error: 'border-destructive/40 bg-transparent text-destructive',
}

export function CertStatusBadge({ tone, label }) {
  return (
    <Badge variant={tone === 'error' ? 'outline' : 'secondary'} data-slot="cert-status" data-status={tone}
      className={cn('gap-1.5 px-2.5 py-[3px] text-[11px] font-bold tracking-[.03em]', STATUS[tone] ?? STATUS.valid)}>
      <span aria-hidden="true" className="size-1.5 shrink-0 rounded-full bg-current" />
      {label}
    </Badge>
  )
}

// Kritiklik katmanı rozeti — dolu, beyaz yazı (Uyarılar sayfasının TierBadge'iyle aynı renkler).
const TIER = { 1: 'bg-indigo-600 text-white', 2: 'bg-sky-600 text-white', 3: 'bg-cyan-600 text-white', 4: 'bg-zinc-500 text-white' }

export function CertTierBadge({ tier }) {
  const t = useT()
  if (!tier) return null
  return (
    <Badge data-slot="cert-tier" data-tier={tier} title={t('certcard.tierTip', tier)}
      className={cn('rounded px-1.5 text-[11px] font-extrabold tracking-[.02em]', TIER[tier] ?? TIER[4])}>
      T{tier}
    </Badge>
  )
}

// ── Kahraman panel: kalan gün + bitiş tarihi + kalan geçerlilik çubuğu ────────────────────────────────────────────
const HERO_BOX = {
  valid: 'bg-muted/40 dark:bg-muted/25',
  warning: 'border-amber-500/40 bg-amber-500/5 dark:bg-amber-500/10',
  high: 'border-orange-500/40 bg-orange-500/5 dark:bg-orange-500/10',
  critical: 'border-destructive/35 bg-destructive/5 dark:bg-destructive/10',
  expired: 'border-destructive/35 bg-destructive/5 dark:bg-destructive/10',
  error: 'bg-muted/40 dark:bg-muted/25',
}
const HERO_INK = {
  valid: 'text-success', warning: 'text-amber-600 dark:text-amber-400', high: 'text-orange-600 dark:text-orange-400',
  critical: 'text-destructive', expired: 'text-destructive', error: 'text-muted-foreground',
}
/** ProgressBar tonu; "high" ton ailesinde yok → turuncu dolgu `--pg-fill` ile. */
const BAR_TONE = { valid: 'ok', warning: 'warn', critical: 'crit' }

function heroLabel(tone, days, t) {
  if (tone === 'error') return t('certcard.checkFailed')
  if (tone === 'expired') return Math.abs(days) === 1 ? t('certcard.daySinceExpiry') : t('certcard.daysSinceExpiry')
  if (days === 0) return t('certcard.expiresToday')
  return days === 1 ? t('certcard.dayLeft') : t('card.daysLeft')
}

/**
 * Kalan gün (büyük, tonlu) · bitiş tarihi (sağda) · kalan geçerlilik çubuğu (not_before → not_after; dolmuş/hatalı
 * kartta yok) · "90 günden 5 gün kaldı" + yeni yenilendi rozeti ya da yenileme planı çipi (`end`).
 */
export function CertHero({ cert, tone, validity, renewedDays, end }) {
  const t = useT()
  const days = cert.days_remaining
  const known = days !== null && days !== undefined
  const display = tone === 'error' || !known ? '—' : tone === 'expired' ? Math.abs(days) : days
  const showBar = !!validity && tone !== 'expired' && tone !== 'error'
  const renewed = renewedDays != null && (
    <Badge variant="secondary" data-slot="cert-renewed" title={t('card.renewedTip', dateOnly(cert.not_before))}
      className="h-5 gap-1 rounded-md bg-success/15 px-1.5 text-[10.5px] font-semibold text-success dark:bg-success/20">
      <RefreshCw aria-hidden="true" /> {renewedDays === 0 ? t('card.renewedToday') : t('card.renewedAgo', renewedDays)}
    </Badge>
  )
  const hasFoot = showBar || renewed || end
  return (
    <div data-slot="cert-hero" data-tone={tone} className={cn('mb-3 rounded-lg border px-3 py-2.5', HERO_BOX[tone])}>
      <div className="flex min-w-0 items-start justify-between gap-3">
        <div className="min-w-0">
          <div data-slot="cert-days" className={cn('text-[2rem] leading-none font-bold tracking-tight tabular-nums', HERO_INK[tone])}>{display}</div>
          <div className="mt-1 text-xs font-medium text-muted-foreground">{heroLabel(tone, days, t)}</div>
        </div>
        {cert.not_after && (
          <div data-slot="cert-expiry" className="flex min-w-0 shrink-0 flex-col items-end gap-0.5 text-right">
            <span className="text-[10px] font-semibold tracking-[.06em] text-muted-foreground uppercase">
              {tone === 'expired' ? t('certcard.expiredOn') : t('card.expiresShort')}
            </span>
            <time dateTime={toUtc(cert.not_after)} className="text-sm font-semibold text-foreground tabular-nums">
              {dateOnly(cert.not_after)}
            </time>
          </div>
        )}
      </div>
      {hasFoot && (
        <div className="mt-2.5">
          {showBar && (
            // decorative: aynı değer hemen altta METİN ("90 günden 5 gün kaldı"); çubuk onun görsel eşi.
            <ProgressBar value={validity.remaining} max={validity.total} size="sm" decorative tone={BAR_TONE[tone]}
              className={cn('h-1.5', tone === 'high' && '[--pg-fill:var(--color-orange-500)]')} />
          )}
          <div className={cn('flex min-w-0 flex-wrap items-center justify-between gap-x-2 gap-y-1.5', showBar && 'mt-1.5')}>
            {showBar
              ? <span data-slot="cert-validity" className="text-[11px] text-muted-foreground tabular-nums">{t('certcard.validityLeft', validity.remaining, validity.total)}</span>
              : <span />}
            {(renewed || end) && <span className="ml-auto flex min-w-0 flex-wrap items-center justify-end gap-1.5">{renewed}{end}</span>}
          </div>
        </div>
      )}
    </div>
  )
}

/**
 * Yenileme planı çipi: plan varsa tarihli çip (tıklanınca plan penceresi — yetki/işleyici yoksa salt bilgi), yoksa ≤ 30
 * gün kalan sertifikada "Yenileme planla" kısayolu. Adı alan adını taşır (50 kartta 50 özdeş "Planla" duyulmasın).
 */
export function CertPlanChip({ chip, cert, onPlanRenewal }) {
  const t = useT()
  if (!chip) return null
  const stop = (fn) => (e) => { e.stopPropagation(); fn() }
  if (chip.kind === 'cta') {
    return (
      <Button type="button" variant="outline" size="xs" data-slot="cert-plan" data-state="none"
        aria-label={t('a11y.rowAction', cert.domain, t('ccx.planCta'))} onClick={stop(() => onPlanRenewal(cert, null))}
        className={cn(CARD_LAYER, TOUCH_CHIP, CHIP, 'border-primary/50 bg-transparent text-primary hover:bg-primary/10 hover:text-primary dark:border-primary/50 dark:bg-transparent dark:hover:bg-primary/15')}>
        <CalendarPlus aria-hidden="true" /> {t('ccx.planCta')}
      </Button>
    )
  }
  const plan = chip.plan
  const st = planState(plan)
  const text = t(`certcard.plan.${st}`, dateOnly(plan.planned_at))
  const tone = st === 'done' ? 'ok' : st === 'overdue' ? 'bad' : 'plan'
  const Icon = st === 'done' ? CalendarCheck : CalendarClock
  const title = [plan.by, plan.note].filter(Boolean).join(' · ') || undefined
  if (!onPlanRenewal) {
    return (
      <Badge variant="outline" data-slot="cert-plan" data-state={st} title={title} className={cn(CHIP, CHIP_TONE[tone])}>
        <Icon aria-hidden="true" />{text}
      </Badge>
    )
  }
  return (
    <Button type="button" variant="ghost" size="xs" data-slot="cert-plan" data-state={st} title={title}
      aria-label={t('a11y.rowAction', cert.domain, text)} onClick={stop(() => onPlanRenewal(cert, plan))}
      className={cn(CARD_LAYER, TOUCH_CHIP, CHIP, CHIP_TONE[tone], CHIP_HOVER[tone])}>
      <Icon aria-hidden="true" />{text}
    </Button>
  )
}

/** Veren (CA) + anahtar/imza özeti: "Example Trust Services · RSA 2048 · SHA-256"; zayıfsa kırmızı + kalkan simgesi. */
export function CertIssuerLine({ cert, tone, isWeak }) {
  const t = useT()
  const issuer = issuerNameOf(cert)
  const key = tone === 'error' ? null : keyLabelOf(cert)
  if (!issuer && !key) return null
  const strength = isWeak === undefined ? 'unknown' : isWeak ? 'weak' : 'strong'
  const sig = sigShort(cert.signature_algorithm)
  const title = [strength === 'unknown' ? null : t(isWeak ? 'card.algoWeak' : 'card.algoStrong'), cert.signature_algorithm]
    .filter(Boolean).join(' · ')
  const KeyIcon = strength === 'weak' ? ShieldAlert : strength === 'strong' ? ShieldCheck : KeyRound
  return (
    <p data-slot="cert-issuer-line" className="mb-2.5 flex min-w-0 flex-wrap items-center gap-x-3 gap-y-1 text-xs text-muted-foreground">
      {issuer && (
        <span data-slot="cert-issuer" className="inline-flex min-w-0 max-w-full items-center gap-1">
          <Building2 aria-hidden="true" className="size-3.5 shrink-0 opacity-70" />
          <span className="sr-only">{t('card.issuer')} </span>
          <span className="min-w-0 truncate">{issuer}</span>
        </span>
      )}
      {key && (
        <span data-slot="cert-algo" data-strength={strength} title={title || undefined}
          className={cn('inline-flex min-w-0 items-center gap-1 font-medium tabular-nums', strength === 'weak' ? 'text-destructive' : 'text-foreground/80')}>
          <KeyIcon aria-hidden="true" className={cn('size-3.5 shrink-0', strength === 'strong' && 'text-success')} />
          <span className="min-w-0 truncate">{key}{sig ? ` · ${sig}` : ''}</span>
        </span>
      )}
    </p>
  )
}

/** Takım · platform · kontrol yolu (Doğrudan/Proxy). Platform ayrıntısı yalnız zengin görünümde metin olarak. */
export function CertMeta({ cert, rich }) {
  const t = useT()
  const viaLabel = cert.via === 'proxy' ? t('card.viaProxy') : t('card.viaDirect')
  if (!cert.team_name && !cert.platform && !cert.via) return null
  return (
    <div data-slot="cert-meta" className="mb-3 flex min-w-0 flex-wrap items-center gap-x-3 gap-y-1.5 text-xs text-muted-foreground">
      {cert.team_name && (
        <span className={cn(CARD_LAYER, 'flex min-w-0 items-center')}>
          {/* Dokunmatikte 18 px rozetin alanı ::after ile 40 px'e (sarmalayıcı `relative`) */}
          <TeamBadge teamId={cert.team_id} teamName={cert.team_name}
            className="pointer-coarse:after:absolute pointer-coarse:after:inset-x-0 pointer-coarse:after:-inset-y-[11px]" />
        </span>
      )}
      {cert.platform && (
        <Badge variant="outline" data-slot="cert-platform"
          title={`${t('card.platform')}: ${cert.platform_name || cert.platform}${cert.platform_detail ? ` · ${cert.platform_detail}` : ''}`}
          className={cn(CARD_LAYER, 'h-5 min-w-0 max-w-full gap-1 rounded-md bg-muted/60 px-1.5 text-[11px] font-semibold text-foreground [&>svg]:text-muted-foreground')}>
          <Layers aria-hidden="true" />
          <span data-slot="cert-platform-name" className="whitespace-nowrap">{cert.platform_name || cert.platform}</span>
          {rich && cert.platform_detail && (
            <span data-slot="cert-platform-detail" className="min-w-0 truncate font-medium text-muted-foreground">· {cert.platform_detail}</span>
          )}
        </Badge>
      )}
      {cert.via && (
        <span data-slot="cert-via" title={t('card.viaTooltip', viaLabel, cert.tls_mode_used || '—')}
          className={cn(CARD_LAYER, 'inline-flex items-center gap-1 whitespace-nowrap')}>
          {cert.via === 'proxy' ? <Network aria-hidden="true" className="size-3 shrink-0" /> : <Globe aria-hidden="true" className="size-3 shrink-0" />}
          {viaLabel}
        </span>
      )}
    </div>
  )
}

const REASON_ICON = {
  revoked: ShieldX, untrusted: ShieldAlert, hostname: ShieldAlert, chainBroken: Link2Off, chainIncomplete: Link2Off,
  deployment: Server, intermediate: Link2, weak: KeyRound,
}

/**
 * Güven / zincir gerekçe çipleri (güvenilmeyen CA, ad uyuşmazlığı, zincir, iptal, zayıf algoritma …). Her çip bir
 * HintPopover tetiği: açıklama fareyle, klavyeyle VE dokununca açılır; ad alan adını taşır.
 */
export function CertReasons({ reasons, cert }) {
  const t = useT()
  if (!reasons.length) return null
  return (
    <ul data-slot="cert-reasons" aria-label={t('certcard.reasons')} className="m-0 mb-3 flex min-w-0 list-none flex-wrap gap-x-1.5 gap-y-2 p-0">
      {reasons.map((r) => {
        const Icon = REASON_ICON[r.key] ?? ShieldAlert
        const label = t(`attn.r.${r.key}`, r.n)
        const detail = r.key === 'weak'
          ? [keyLabelOf(cert), cert.signature_algorithm].filter(Boolean).join(' · ')
          : null
        return (
          <li key={r.key} className="min-w-0">
            <HintPopover content={detail ? `${t(r.hint)}\n${detail}` : t(r.hint)}
              aria-label={t('a11y.rowAction', cert.domain, label)}
              triggerClassName={cn(CARD_LAYER, TOUCH_CHIP, 'max-w-full rounded-full pointer-coarse:min-h-0')}>
              <Badge variant="outline" data-slot="cert-reason" data-reason={r.key} data-tone={r.tone}
                className={cn(CHIP, CHIP_TONE[r.tone])}>
                <Icon aria-hidden="true" />
                <span className="truncate">{label}</span>
                <Info aria-hidden="true" className="size-3 opacity-60" />
              </Badge>
            </HintPopover>
          </li>
        )
      })}
    </ul>
  )
}

/** Kontrol hatası — neden tek bakışta (en fazla iki satır, yalnız-hover yok). */
export function CertErrorNote({ error }) {
  if (!error) return null
  return (
    <p data-slot="cert-error" role="note"
      className="mb-3 flex min-w-0 items-start gap-1.5 rounded-md bg-destructive/10 px-2 py-1.5 text-xs leading-snug font-medium text-destructive dark:bg-destructive/15">
      <WifiOff aria-hidden="true" className="mt-px size-3.5 shrink-0" />
      <span className="line-clamp-2 min-w-0 break-words">{error}</span>
    </p>
  )
}

/**
 * Bildirim durumu satırı: "şu an" şeridi (son erişim kontrolü + son alarm) · sessiz alarm · e-posta gönderim sorunu.
 * Test kancası `data-slot="cert-card-chips"`, çipler `data-chip="silent" | "mail"`.
 */
export function CertNotices({ cert, live, hasSilentAlert, hasMailFailure, onMailFailureClick }) {
  const t = useT()
  const hasLive = !!(live && (live.uptime || live.alert))
  if (!hasLive && !hasSilentAlert && !hasMailFailure) return null
  return (
    <div data-slot="cert-card-chips" className="mb-3 flex min-w-0 flex-wrap items-center gap-x-1.5 gap-y-2">
      {hasLive && (
        // Şerit düğmesi ~23 px: dokunmatikte ::after ile 40 px alan (sarmalayıcı `relative` kutusuna göre)
        <span className={cn(CARD_LAYER, 'flex min-w-0 max-w-full',
          'pointer-coarse:[&>button]:after:absolute pointer-coarse:[&>button]:after:inset-x-0 pointer-coarse:[&>button]:after:-inset-y-[9px]')}>
          <CertificateLiveStrip domain={cert.domain} uptime={live.uptime} alert={live.alert} />
        </span>
      )}
      {hasSilentAlert && (
        <Badge variant="outline" data-chip="silent" className={cn(CHIP, CHIP_TONE.warn, 'h-auto min-h-6 py-0.5 whitespace-normal')}>
          <BellOff aria-hidden="true" />
          <span>{t('card.silentAlert')}</span>
        </Badge>
      )}
      {hasMailFailure && (onMailFailureClick ? (
        <Button type="button" variant="ghost" size="xs" data-chip="mail" title={t('card.mailFailureTooltip')}
          aria-label={t('a11y.rowAction', cert.domain, t('card.mailFailure'))}
          onClick={(e) => { e.stopPropagation(); onMailFailureClick() }}
          className={cn(CARD_LAYER, TOUCH_CHIP, CHIP, CHIP_TONE.bad, CHIP_HOVER.bad)}>
          <MailWarning aria-hidden="true" />
          <span>{t('card.mailFailure')}</span>
        </Button>
      ) : (
        <Badge variant="outline" data-chip="mail" title={t('card.mailFailureTooltip')} className={cn(CHIP, CHIP_TONE.bad)}>
          <MailWarning aria-hidden="true" />
          <span>{t('card.mailFailure')}</span>
        </Badge>
      ))}
    </div>
  )
}

/** Son kontrol — göreli ("12 dk önce"); kesin zaman ipucunda ve ekran okuyucuda. Hiç kontrol yoksa açıkça söyler. */
export function CertCheckedAt({ at }) {
  const t = useT()
  if (!at) return <span data-slot="cert-checked-at" data-never="true">{t('certcard.never')}</span>
  const exact = formatDateSec(at)
  return (
    <SimpleTooltip content={exact}>
      <time data-slot="cert-checked-at" dateTime={toUtc(at)} className={cn(CARD_LAYER, 'cursor-default')}>
        {relativeTime(at, t) || exact}
        <span className="sr-only"> ({exact})</span>
      </time>
    </SimpleTooltip>
  )
}

/**
 * Eylemler — geniş ekranda kanonik ikon dörtlüsü (Şimdi kontrol et · Düzenle · Kopyala · Sil; MonitorCardActions ile aynı
 * görünüm), telefonda (< 640 px) Şimdi kontrol et + "Diğer işlemler" menüsü (Düzenle · Kopyala · Yenileme planla · Sil).
 * Düğmeler işleyici VARLIĞINA bağlı (yetkisiz kullanıcıda çizilmez). Adlar kartı ayırır (alan adı + eylem).
 */
export function CertActions({ cert, plan, onCheckNow, onEdit, onDuplicate, onDelete, onPlanRenewal, checking, deleting }) {
  const t = useT()
  const named = (label) => t('a11y.rowAction', cert.domain, label)
  const hasIcons = !!(onCheckNow || onEdit || onDuplicate || onDelete)
  const planned = plan && plan.planned_at && !plan.done
  const menu = [
    onEdit && { label: t('inv.edit'), icon: <Pencil aria-hidden="true" />, onClick: onEdit },
    onDuplicate && { label: t('mon.duplicate'), icon: <Copy aria-hidden="true" />, onClick: onDuplicate },
    onPlanRenewal && {
      label: planned ? t('forecast.editPlan') : t('forecast.planRenewal'), icon: <CalendarPlus aria-hidden="true" />,
      onClick: () => onPlanRenewal(cert, plan ?? null),
    },
    onDelete && { label: t('inv.delete'), icon: <Trash2 aria-hidden="true" />, onClick: onDelete, danger: true, hidden: !!deleting },
  ].filter(Boolean)
  if (!hasIcons && !menu.length) return null
  return (
    <span className="flex items-center gap-1.5">
      {hasIcons && (
        // Grubun tamamında stopPropagation: kart dışı bir kapta kullanılırsa her tıklama detayı da açmasın.
        <span data-slot="cert-card-actions" className="flex shrink-0 items-center gap-1" onClick={(e) => e.stopPropagation()}>
          <CheckRunningStrip running={!!checking} />
          {onCheckNow && (
            <CheckNowButton running={!!checking} onClick={onCheckNow} title={t('app.checkNow')} rowLabel={cert.domain} className={TOUCH} />
          )}
          {onEdit && (
            <SimpleTooltip content={t('inv.edit')}>
              <Button type="button" variant="outline" size="icon-sm" className={cn(MON_ACT, TOUCH, MON_ACT_TONE.edit, 'max-sm:hidden')}
                onClick={onEdit} aria-label={named(t('inv.edit'))}>
                <Pencil size={13} aria-hidden="true" />
              </Button>
            </SimpleTooltip>
          )}
          {onDuplicate && (
            <SimpleTooltip content={t('mon.duplicate')}>
              <Button type="button" variant="outline" size="icon-sm" className={cn(MON_ACT, TOUCH, MON_ACT_TONE.copy, 'max-sm:hidden')}
                onClick={onDuplicate} aria-label={named(t('mon.duplicate'))}>
                <Copy size={13} aria-hidden="true" />
              </Button>
            </SimpleTooltip>
          )}
          {onDelete && (
            <SimpleTooltip content={t('inv.delete')}>
              <Button type="button" variant="outline" size="icon-sm" className={cn(MON_ACT, TOUCH, MON_ACT_TONE.danger, 'max-sm:hidden')}
                disabled={deleting} onClick={onDelete} aria-label={named(t('inv.delete'))}>
                <Trash2 size={13} aria-hidden="true" />
              </Button>
            </SimpleTooltip>
          )}
        </span>
      )}
      {menu.length > 0 && (
        <span data-slot="cert-card-more" className="sm:hidden" onClick={(e) => e.stopPropagation()}>
          <KebabMenu items={menu} rowLabel={cert.domain} label={t('alh.moreActions')} />
        </span>
      )}
    </span>
  )
}
