import { Building2, CalendarClock, Clock, Globe, KeyRound, Link2 } from 'lucide-react'
import { formatDate, formatDateSec } from '../../api/client'
import { useT } from '../../i18n/index.jsx'
import { dateLocale } from '../../i18n/dateLocale.js'
import { toUtc } from '../../utils/localDay.js'
import { relativeTime } from '../admin/audit/auditFormat.js'
import { CertStatusBadge } from '../certcard/CertCardParts.jsx'
import { TONE_LABEL } from '../certcard/certCardModel.js'
import { ProgressBar } from '../ui/Progress.jsx'
import { Card } from '@/components/shadcn/card'
import { cn } from '@/lib/utils'
import { ToneChip, StatusChip } from './CertDetailsParts.jsx'
import { formatRelative, hostCoverage, issuerOf, keyInfo, lifetimeOf, trustSummary } from './certDetailsModel.js'

/**
 * "Sertifika Detayları" sekmesinin ÖZET kartı ve HIZLI BAKIŞ karoları (2026-09-28).
 *
 * Özet: durum rozeti (kartla aynı CertStatusBadge) + kalan gün (büyük, tonlu, tabular-nums) + bitiş tarihi ve göreli
 * süresi + geçerlilik ZAMAN ÇİZELGESİ (ui/Progress ProgressBar: role="progressbar", aria-valuenow = kullanılan yüzde;
 * bugünün konumu çubuğun üstünde işaretli) + başlangıç / toplam süre + son kontrol (göreli; kesin zaman ipucunda ve
 * ekran okuyucuda). Dolmuş / tarih yok / okunamadı durumları kendi metniyle. Tonlar kart kahraman panelinin
 * (certcard/CertCardParts) ailesi — SOL RENK ŞERİDİ YOK, ton tüm çerçeve + zeminle.
 *
 * Karolar: sertifika otoritesi (güven), anahtar (CA / uç sertifika), alternatif adlar (alan adı kapsaması), güven ve
 * zincir (zincir / iptal / dağıtım çipleri). Verisi olmayan karo çizilmez.
 * Test kancaları: `data-slot="cert-details-summary"` + `data-tone`, `cert-details-days`, `cert-details-tile` + `data-tile`.
 */

const BOX = {
  valid: 'bg-muted/40 dark:bg-muted/25',
  warning: 'border-amber-500/40 bg-amber-500/5 dark:bg-amber-500/10',
  high: 'border-orange-500/40 bg-orange-500/5 dark:bg-orange-500/10',
  critical: 'border-destructive/35 bg-destructive/5 dark:bg-destructive/10',
  expired: 'border-destructive/35 bg-destructive/5 dark:bg-destructive/10',
  error: 'border-destructive/30 bg-muted/40 dark:bg-muted/25',
}
const INK = {
  valid: 'text-success', warning: 'text-amber-600 dark:text-amber-400', high: 'text-orange-600 dark:text-orange-400',
  critical: 'text-destructive', expired: 'text-destructive', error: 'text-muted-foreground',
}
/** ProgressBar tonu; "high" ailede yok → turuncu `--pg-fill`, hata → nötr. */
const BAR_TONE = { valid: 'ok', warning: 'warn', critical: 'crit', expired: 'crit' }
const BAR_FILL = { high: '[--pg-fill:var(--color-orange-500)]', error: '[--pg-fill:var(--color-muted-foreground)]' }

function daysLabel(tone, days, t) {
  if (tone === 'error') return t('certcard.checkFailed')
  if (days == null) return t('modal.daysRemain')
  if (days < 0) return Math.abs(days) === 1 ? t('certcard.daySinceExpiry') : t('certcard.daysSinceExpiry')
  if (days === 0) return t('certcard.expiresToday')
  return days === 1 ? t('certcard.dayLeft') : t('card.daysLeft')
}

function Stamp({ iso, children, ...rest }) {
  return <time dateTime={toUtc(iso)} {...rest}>{children}</time>
}

/** Bugünün konumu: çubuğu kesen dikey işaret + altında "Bugün". Etiket uçlarda taşmasın diye yüzdeyle kaydırılır. */
function TodayMarker({ pct, t }) {
  const p = Math.max(0, Math.min(100, pct))
  return (
    <>
      <span aria-hidden="true" data-slot="cert-details-today"
        className="pointer-events-none absolute bottom-[-2px] h-3.5 w-1 -translate-x-1/2 rounded-full bg-foreground ring-2 ring-card"
        style={{ left: `${Math.max(0.5, Math.min(99.5, p))}%` }} />
      <span aria-hidden="true"
        className="pointer-events-none absolute top-full mt-1.5 text-[10.5px] font-semibold whitespace-nowrap text-foreground"
        style={{ left: `${p}%`, transform: `translateX(-${p}%)` }}>
        {t('cdp.today')}
      </span>
    </>
  )
}

export function CertDetailsSummary({ d, tone, now = Date.now() }) {
  const t = useT()
  const locale = dateLocale()
  const life = lifetimeOf(d, now)
  const days = typeof d.days_remaining === 'number' ? d.days_remaining : (life ? life.remainingDays : null)
  const big = tone === 'error' || days == null ? '—' : Math.abs(days)
  const expired = days != null && days < 0
  const showBar = !!life && tone !== 'error'
  return (
    <Card data-slot="cert-details-summary" data-tone={tone} role="region" aria-label={t('cdp.summary')}
      className={cn('min-w-0 gap-4 px-4 py-4 shadow-none sm:px-5', BOX[tone] ?? BOX.valid)}>
      <div className="flex min-w-0 flex-wrap items-center justify-between gap-2">
        <div className="flex min-w-0 flex-wrap items-center gap-1.5">
          <CertStatusBadge tone={tone} label={t(TONE_LABEL[tone] ?? TONE_LABEL.valid)} />
          {life?.notYetValid && <ToneChip tone="warn">{t('cdp.notYetValid')}</ToneChip>}
        </div>
        {d.checked_at && (
          <p data-slot="cert-details-checked" className="m-0 inline-flex min-w-0 items-center gap-1.5 text-xs text-muted-foreground">
            <Clock aria-hidden="true" className="size-3.5 shrink-0" />
            <Stamp iso={d.checked_at} className="tabular-nums" title={formatDateSec(d.checked_at)}>
              {t('cdp.checked', relativeTime(d.checked_at, t, now) || formatDate(d.checked_at))}
              <span className="sr-only"> ({formatDateSec(d.checked_at)})</span>
            </Stamp>
          </p>
        )}
      </div>

      <div className="flex min-w-0 flex-wrap items-end justify-between gap-x-6 gap-y-3">
        <div className="min-w-0">
          <div data-slot="cert-details-days" className={cn('text-5xl leading-none font-bold tracking-tight tabular-nums', big === '—' ? 'text-muted-foreground/60' : (INK[tone] ?? INK.valid))}>
            {big}
          </div>
          <div className="mt-1.5 text-sm font-medium text-muted-foreground">{daysLabel(tone, days, t)}</div>
        </div>
        {d.not_after && (
          <div data-slot="cert-details-expiry" className="flex min-w-0 flex-col items-start gap-0.5 sm:items-end sm:text-right">
            <span className="text-[11px] font-semibold tracking-[.06em] text-muted-foreground uppercase">
              {expired ? t('certcard.expiredOn') : t('card.expiresShort')}
            </span>
            <Stamp iso={d.not_after} className="text-base font-semibold text-foreground tabular-nums">{formatDate(d.not_after)}</Stamp>
            {formatRelative(d.not_after, locale, now) && (
              <span className="text-xs text-muted-foreground">{formatRelative(d.not_after, locale, now)}</span>
            )}
          </div>
        )}
      </div>

      {showBar ? (
        <div className="flex min-w-0 flex-col gap-2">
          <div className="relative mb-5">
            <ProgressBar value={life.usedPct} max={100} size="md" label={t('cdp.lifeUsed')} showValue
              tone={BAR_TONE[tone]} className={cn('h-2.5', BAR_FILL[tone])} />
            <TodayMarker pct={life.usedPct} t={t} />
          </div>
          <div className="flex min-w-0 flex-wrap items-baseline justify-between gap-x-4 gap-y-1 text-xs text-muted-foreground">
            <span data-slot="cert-details-start" className="inline-flex min-w-0 flex-wrap items-baseline gap-x-1.5">
              <CalendarClock aria-hidden="true" className="size-3.5 shrink-0 self-center" />
              <span className="font-semibold">{t('cdp.validFrom')}</span>
              <Stamp iso={d.not_before} className="text-foreground tabular-nums">{formatDate(d.not_before)}</Stamp>
              {formatRelative(d.not_before, locale, now) && <span>· {formatRelative(d.not_before, locale, now)}</span>}
            </span>
            <span data-slot="cert-details-total" className="tabular-nums">{t('cdp.lifeTotal', life.totalDays)}</span>
          </div>
        </div>
      ) : !life ? (
        <div data-slot="cert-details-nodates" className="flex min-w-0 items-start gap-2 rounded-md border border-dashed px-3 py-2.5 text-sm">
          <CalendarClock aria-hidden="true" className="mt-0.5 size-4 shrink-0 text-muted-foreground" />
          <span className="min-w-0">
            <span className="block font-medium">{t('cdp.noDates')}</span>
            <span className="block text-xs text-muted-foreground">{t('cdp.noDatesHint')}</span>
          </span>
        </div>
      ) : null}
    </Card>
  )
}

/** Hızlı bakış karosu — simge + küçük başlık, değer, alt satır, rozet(ler). */
function Tile({ slot, icon: Icon, title, value, sub, tone, children }) {
  return (
    <li className="min-w-0">
      <Card data-slot="cert-details-tile" data-tile={slot} data-tone={tone}
        className="h-full min-w-0 gap-1.5 px-3.5 py-3 shadow-none">
        <span className="flex min-w-0 items-start gap-1.5 text-[11px] leading-tight font-semibold tracking-[.05em] text-muted-foreground uppercase">
          <Icon aria-hidden="true" className="size-3.5 shrink-0" />
          <span className="min-w-0 [overflow-wrap:anywhere]">{title}</span>
        </span>
        <span className="min-w-0 text-[15px] leading-snug font-semibold [overflow-wrap:anywhere]">{value}</span>
        {sub && <span className="min-w-0 text-xs text-muted-foreground [overflow-wrap:anywhere]">{sub}</span>}
        {children && <span className="mt-auto flex min-w-0 flex-wrap gap-1 pt-1">{children}</span>}
      </Card>
    </li>
  )
}

export function CertDetailsTiles({ d, sanList }) {
  const t = useT()
  const issuer = issuerOf(d)
  const key = keyInfo(d)
  const trust = trustSummary(d)
  const trustChip = trust.chips.find((c) => c.kind === 'trust')
  const coverage = hostCoverage(d, sanList)
  const wild = sanList.filter((s) => s.wildcard).length
  const tiles = []

  if (issuer.cn || issuer.org) {
    tiles.push(
      <Tile key="ca" slot="ca" icon={Building2} title={t('cdp.tile.ca')} value={issuer.cn || issuer.org}
        sub={issuer.cn && issuer.org && issuer.org !== issuer.cn ? issuer.org : null} tone={trustChip?.tone ?? 'muted'}>
        {trustChip ? <StatusChip chip={trustChip} /> : <ToneChip tone="muted">{t('cdp.trust.none')}</ToneChip>}
      </Tile>,
    )
  }
  if (key.label || key.sig) {
    tiles.push(
      <Tile key="key" slot="key" icon={KeyRound} title={t('cdp.tile.key')} value={key.label || key.sig}
        sub={key.label && key.sigShort ? t('cdp.signedWith', key.sigShort) : null} tone={d.is_ca ? 'info' : 'muted'}>
        {d.is_ca != null && <ToneChip tone={d.is_ca ? 'info' : 'muted'}>{d.is_ca ? t('cdp.caCert') : t('cdp.leafCert')}</ToneChip>}
      </Tile>,
    )
  }
  if (sanList.length > 0) {
    tiles.push(
      <Tile key="san" slot="san" icon={Globe} title={t('cdp.tile.san')}
        value={sanList.length === 1 ? t('cdp.sanOne') : t('cdp.sanMany', sanList.length)}
        sub={wild ? (wild === 1 ? t('cdp.wildOne') : t('cdp.wildMany', wild)) : null}
        tone={coverage === 'fail' ? 'bad' : coverage === 'ok' ? 'ok' : 'muted'}>
        {coverage && <ToneChip tone={coverage === 'fail' ? 'bad' : 'ok'}>{t(`cdp.host.${coverage}`)}</ToneChip>}
      </Tile>,
    )
  }
  const others = trust.chips.filter((c) => c.kind !== 'trust')
  if (others.length > 0) {
    tiles.push(
      <Tile key="trust" slot="trust" icon={Link2} title={t('cdp.tile.trust')} value={t(`cdp.sum.${trust.tone}`)} tone={trust.tone}>
        {others.map((c) => <StatusChip key={c.kind} chip={c} />)}
      </Tile>,
    )
  }
  if (!tiles.length) return null
  return (
    <ul data-slot="cert-details-tiles" aria-label={t('cdp.tiles')}
      className="m-0 grid min-w-0 list-none grid-cols-2 gap-2 p-0 sm:gap-2.5 lg:grid-cols-4">
      {tiles}
    </ul>
  )
}
