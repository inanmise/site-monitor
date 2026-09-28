import { useId } from 'react'
import {
  AlertTriangle, BellRing, Building2, CalendarClock, CalendarPlus, CircleHelp, Clock, Database, Info, Play, Repeat, Stethoscope,
} from 'lucide-react'
import { formatDateSec } from '../../../api/client'
import { useT } from '../../../i18n/index.jsx'
import { toUtc } from '../../../utils/localDay.js'
import { relativeTime } from '../../admin/audit/auditFormat.js'
import { CHIP, CHIP_TONE } from '../../certcard/CertCardParts.jsx'
import { dateOnly } from '../../certcard/certCardModel.js'
import { MonitorPausedBadge } from '../../monitoring/MonitorCard.jsx'
import CopyButton from '../../ui/CopyButton.jsx'
import HintPopover from '../../ui/HintPopover.jsx'
import MaintenanceBadge from '../../ui/MaintenanceBadge.jsx'
import { ProgressBar, Spinner } from '../../ui/Progress.jsx'
import { Badge } from '@/components/shadcn/badge'
import { Button } from '@/components/shadcn/button'
import { cn } from '@/lib/utils'
import { DomainChangedChip, DomainEppChips, DomainProtection } from '../DomainCardParts.jsx'
import {
  WHOIS_PROVIDER_LABEL, daysTone, expiryKey, expiryTone, fmtExpiry, lifeOf, sourceTag, unknownReasonOf,
} from '../domainCardModel.js'
import { fmtClock, fmtLongDay, intervalHours, nextStepsOf, planBlockOf, sourceFamily } from './domainDetailModel.js'

/**
 * Alan Adı DETAY penceresinin başlık alanı (2026-09-28 yeniden tasarım — shadcn, mobil duyarlı). Eski düz
 * `DetailSummary` satırının (kalan gün · bitiş · registrar · kaynak · NS · son kontrol) + EPP rozet dizisinin YERİNE
 * geçer; kartla (domain/DomainMonitorCard) aynı görsel dil: tonlu kahraman panel, kalan kayıt süresi çubuğu, plan
 * çipi ailesi, koruma ve EPP çipleri. SOL RENK ŞERİDİ YOK (kalıcı kural) — ton panelin tamamında.
 *
 * <p>Okuma sırası: kimlik (telefonda tam alan adı + kopyala; izlemenin adı farklıysa) → bayraklar (aktif alarm + onay durumu · duraklatıldı · Değişti · bakım) → KAHRAMAN (kalan gün, uzun
 * bitiş tarihi + RDAP saati, kalan kayıt süresi çubuğu "365 günden 212 gün kaldı", dönem başlangıcı, YENİLEME PLANI
 * bloğu) | ÖZET (registrar + IANA, kaynak + açıklaması, son kontrol göreli + tam, kontrol sıklığı, alarm eşikleri,
 * "Şimdi kontrol et") → koruma + EPP çipleri (bir bakışta; ayrıntısı "Domain Kaydı" sekmesinde).
 * Bitiş bilinmiyorsa kahraman yerine NEDEN paneli: neden (hiç kontrol yok / sorgu hatası — tam metin, kopyalanabilir /
 * kaynak tarih döndürmedi) + sonraki adımlar + Şimdi kontrol et / Sorun Tanıla.
 *
 * <p>Yerleşim KAP genişliğine göre (`@container`): pencere telefonda tam ekran, tablette ~690 px, masaüstünde ~910 px —
 * görünüm alanı kırılma noktası pencerenin gerçek genişliğini söylemez. Dar kapta (telefon, tablet) tek sütun, ≥ 48rem'de
 * iki sütun — tablette iki sütunda özet ~240 px'e iniyor, uzun registrar adı altı satıra kırılıyordu (Playwright 768).
 *
 * <p>Sayfaya ait kablolama prop olarak gelir (yetki kapıları sayfada): `onCheck` (izlemeyi yönetebilen),
 * `onPlanRenewal` (paylaşılan RenewalPlanModal'ı sayfa açar), `onDiagnose` (yalnız yönetici). Verilmeyen eylem çizilmez.
 *
 * <p>Test kancaları: kök `domain-detail-header` (`data-tone` unknown|expired|critical|warning|ok), `domain-detail-identity`
 * (`domain-detail-domain`, `domain-detail-name`), `domain-detail-flags`,
 * `domain-detail-alarm`, `domain-detail-hero`, `domain-days`, `domain-expiry`, `domain-life`, `domain-detail-plan`
 * (`data-state` planned|overdue|cta|offer), `domain-detail-facts`, `domain-detail-check`, `domain-detail-unknown`
 * (`data-reason`), `domain-error`, `domain-next-steps`, `domain-detail-glance`.
 */

/**
 * Telefonda (< 640 px) detay penceresi TAM EKRAN (MonitorDetailModal `className`'ine eklenir; paylaşılan kabuk
 * değişmez): köşe/kenar yok, 100dvh, içerik üstten başlar (ızgara satırları esnemez), başlık satırı YAPIŞKAN — örtü
 * kalmadığı için kapatma ve eylemler kaydırınca da elde kalır. Alt boşluk güvenli alanı gözetir.
 */
export const DETAIL_PHONE_FULLSCREEN = cn(
  'max-sm:top-0 max-sm:left-0 max-sm:h-dvh max-sm:max-h-dvh max-sm:w-full max-sm:max-w-full max-sm:translate-x-0 max-sm:translate-y-0',
  'max-sm:content-start max-sm:rounded-none max-sm:border-0 max-sm:px-4 max-sm:pt-0 max-sm:pb-[max(1rem,env(safe-area-inset-bottom))]',
  'max-sm:[&>[data-slot=dialog-header]]:sticky max-sm:[&>[data-slot=dialog-header]]:top-0 max-sm:[&>[data-slot=dialog-header]]:z-20',
  'max-sm:[&>[data-slot=dialog-header]]:-mx-4 max-sm:[&>[data-slot=dialog-header]]:border-b max-sm:[&>[data-slot=dialog-header]]:bg-background',
  'max-sm:[&>[data-slot=dialog-header]]:px-4 max-sm:[&>[data-slot=dialog-header]]:py-3',
)

/** Kahraman panel tonu — kartın kahramanıyla aynı değerler (tam panel zemini + kenar; şerit değil). */
const HERO_BOX = {
  ok: 'bg-muted/40 dark:bg-muted/25',
  warning: 'border-orange-500/40 bg-orange-500/5 dark:bg-orange-500/10',
  critical: 'border-destructive/35 bg-destructive/5 dark:bg-destructive/10',
  expired: 'border-destructive/50 bg-destructive/10 dark:bg-destructive/15',
}
/** Çubuk dolgusu (ProgressBar tonu); ≤ 30 gün turuncu — kalan gün mürekkebiyle aynı. */
const BAR_TONE = { ok: 'ok', critical: 'crit' }
/** Aktif alarm seviyesi → çip tonu (izleme kartının alarm rozetiyle aynı aile). */
const ALARM_TONE = { CRITICAL: 'bad', HIGH: 'high' }
/** Dokunmatikte 40 px hedef (fare/klavyede yoğun kalır). */
const TOUCH = 'pointer-coarse:h-10'

function daysLabel(tone, days, t) {
  if (tone === 'expired') return Math.abs(days) === 1 ? t('certcard.daySinceExpiry') : t('certcard.daysSinceExpiry')
  if (days === 0) return t('certcard.expiresToday')
  return days === 1 ? t('certcard.dayLeft') : t('card.daysLeft')
}

/** "Şimdi kontrol et" — başlıktaki ikon düğmesinin (MonitorModalActions) etiketli eşi; ad alan adını taşır. */
function CheckButton({ monitor: m, running, onCheck, variant = 'outline', className }) {
  const t = useT()
  const label = running ? t('mon.checkRunning') : t('dom.check')
  return (
    <Button type="button" variant={variant} size="sm" data-slot="domain-detail-check" onClick={onCheck} disabled={running}
      aria-busy={running || undefined} aria-label={t('a11y.rowAction', m.domain, label)}
      className={cn(TOUCH, 'aria-busy:disabled:opacity-100', className)}>
      {running ? <Spinner size={14} inline decorative /> : <Play aria-hidden="true" />}
      {label}
    </Button>
  )
}

/**
 * Kimlik satırı: telefonda pencere başlığı UZUN alan adını tek satırda KIRPAR — tam ad burada (eş aralıklı, kırılarak,
 * kopyalanabilir); izlemenin adı alan adından farklıysa her genişlikte gösterilir. Kısa ad + ad yoksa satır hiç çizilmez.
 */
/**
 * Başlığın durum rozeti + eylemlerin yanında kırpılmadan sığdığı yaklaşık karakter sayısı (Playwright ölçümü): telefonda
 * ~22, tablette (< 1024) ~34, masaüstünde ~54. Alan adı o genişlikte sığmıyorsa tam hâli yalnız o genişliklerde yazılır;
 * sığdığı genişlikte başlığın tekrarı olurdu. `null` = alan adı satırı gerekmez.
 */
function domainRowHide(len) {
  if (len <= 22) return null
  if (len <= 34) return 'sm:hidden'
  if (len <= 54) return 'lg:hidden'
  return ''
}

function DetailIdentity({ monitor: m }) {
  const t = useT()
  const domain = String(m.domain || '')
  const name = String(m.name || '').trim()
  const hasName = !!name && name !== domain
  const hide = domainRowHide(domain.length)
  const long = hide !== null
  if (!hasName && !long) return null
  return (
    <div data-slot="domain-detail-identity" className={cn('flex min-w-0 items-start gap-2', !hasName && hide)}>
      <p className="min-w-0 flex-1 text-sm leading-snug">
        {long && <span data-slot="domain-detail-domain" className={cn('block font-mono font-medium break-all', hide)}>{domain}</span>}
        {hasName && <span data-slot="domain-detail-name" className="block text-muted-foreground">{name}</span>}
      </p>
      {long && (
        <CopyButton value={domain} label={t('a11y.rowAction', domain, t('certcard.copyDomain'))} copiedLabel={t('certcard.domainCopied')}
          variant="ghost" buttonSize="icon-sm" className={cn('-my-1 shrink-0 pointer-coarse:size-10', hide)} />
      )}
    </div>
  )
}

/** Bayrak satırı: aktif alarm (seviye + onay durumu), duraklatıldı, Değişti, bakım. Hiçbiri yoksa satır çizilmez. */
function DetailFlags({ monitor: m }) {
  const t = useT()
  const level = m.alarm_level
  const levelKey = level ? `notify.level.${level}` : null
  const levelText = levelKey && t(levelKey) !== levelKey ? t(levelKey) : null
  return (
    <div data-slot="domain-detail-flags" className="flex min-w-0 flex-wrap items-center gap-2 empty:hidden">
      {m.active_alarm && (
        <Badge variant="outline" data-slot="domain-detail-alarm" data-level={level || undefined}
          data-acknowledged={m.alarm_acknowledged ? 'true' : 'false'}
          className={cn(CHIP, 'h-7 px-2.5 text-xs', CHIP_TONE[ALARM_TONE[level] ?? 'warn'])}>
          <AlertTriangle aria-hidden="true" className={cn('size-3.5', !m.alarm_acknowledged && 'animate-pulse motion-reduce:animate-none')} />
          <span className="min-w-0 truncate">
            {t('dom.activeAlarm')}{levelText ? ` · ${levelText}` : ''}
            <span className="font-normal opacity-80"> · {m.alarm_acknowledged ? t('domdet.alarmAcked') : t('domdet.alarmUnacked')}</span>
          </span>
        </Badge>
      )}
      {m.active === false && (
        <span className="flex min-w-0 flex-wrap items-center gap-2">
          <MonitorPausedBadge className="h-7 px-2.5 text-xs" />
          <span className="text-xs text-muted-foreground">{t('domdet.pausedHint')}</span>
        </span>
      )}
      <DomainChangedChip monitor={m} />
      <MaintenanceBadge target={m.domain} className="h-7" />
    </div>
  )
}

/**
 * Yenileme planı bloğu (kahraman panelin altı): plan varsa tarih · bitişten kaç gün önce · kaydeden · not (gecikmişse
 * kırmızı + açıklama) ve "Planı düzenle"; plan yoksa kalan gün uyarı eşiğindeyken belirgin "Yenileme planla" kısayolu,
 * eşik uzaksa sakin bir düğme. Hepsi sayfanın `onPlanRenewal`'ı ile paylaşılan RenewalPlanModal'ı açar.
 */
function PlanBlock({ block, monitor: m, onPlanRenewal }) {
  const t = useT()
  if (!block) return null
  const name = (label) => t('a11y.rowAction', m.domain, label)
  if (block.kind === 'plan') {
    const overdue = block.state === 'overdue'
    const meta = [block.before > 0 ? t('domdet.planBefore', block.before) : null, block.by ? t('forecast.planSetBy', block.by) : null]
      .filter(Boolean).join(' · ')
    return (
      <div data-slot="domain-detail-plan" data-state={block.state}
        className="flex min-w-0 flex-col gap-2.5 border-t pt-3 @md:flex-row @md:items-center @md:justify-between">
        <div className="flex min-w-0 items-start gap-2.5">
          <span aria-hidden="true" className={cn('grid size-8 shrink-0 place-items-center rounded-lg border', CHIP_TONE[overdue ? 'bad' : 'plan'])}>
            <CalendarClock className="size-4" />
          </span>
          <div className="min-w-0">
            <p className={cn('text-sm font-semibold', overdue && 'text-destructive')}>{t(`certcard.plan.${block.state}`, dateOnly(block.at))}</p>
            {meta && <p className="text-xs text-muted-foreground">{meta}</p>}
            {overdue && <p className="mt-0.5 text-xs text-destructive">{t('domdet.planOverdueHint')}</p>}
            {block.note && <p data-slot="domain-detail-plan-note" className="mt-1 text-xs [overflow-wrap:anywhere] text-foreground/80 italic">“{block.note}”</p>}
          </div>
        </div>
        {onPlanRenewal && (
          <Button type="button" variant="outline" size="sm" onClick={onPlanRenewal} aria-label={name(t('forecast.editPlan'))}
            className={cn(TOUCH, 'shrink-0 self-start @md:self-center')}>
            <CalendarClock aria-hidden="true" />{t('forecast.editPlan')}
          </Button>
        )}
      </div>
    )
  }
  const cta = block.kind === 'cta'
  const days = m.days_remaining
  const text = cta ? (days < 0 ? t('domdet.planNoneExpired') : t('domdet.planNoneDue', days)) : t('domdet.planNone')
  return (
    <div data-slot="domain-detail-plan" data-state={block.kind}
      className="flex min-w-0 flex-col gap-2.5 border-t pt-3 @md:flex-row @md:items-center @md:justify-between">
      <p className={cn('flex min-w-0 items-start gap-2 text-sm', cta ? 'font-medium text-foreground' : 'text-muted-foreground')}>
        <CalendarPlus aria-hidden="true" className="mt-0.5 size-4 shrink-0" />
        <span className="min-w-0">{text}</span>
      </p>
      {onPlanRenewal && (
        <Button type="button" variant={cta ? 'default' : 'ghost'} size="sm" onClick={onPlanRenewal} aria-label={name(t('ccx.planCta'))}
          className={cn(TOUCH, 'shrink-0 self-start @md:self-center', !cta && 'text-primary hover:text-primary')}>
          <CalendarPlus aria-hidden="true" />{t('ccx.planCta')}
        </Button>
      )}
    </div>
  )
}

/** Kahraman: kalan gün (büyük) · uzun bitiş tarihi (+ RDAP saati) · kalan kayıt süresi çubuğu + metni · plan bloğu. */
function ExpiryHero({ monitor: m, tone, block, onPlanRenewal }) {
  const t = useT()
  const days = m.days_remaining
  const life = lifeOf(m)
  const showBar = !!life && tone !== 'expired'
  const clock = fmtClock(m.expiry_date)
  const lifeHint = life && [
    t('dom.lifeTip', fmtExpiry(life.start), life.elapsed, life.total),
    m.registration_date ? t('domcard.registeredOn', fmtExpiry(m.registration_date)) : null,
  ].filter(Boolean).join('\n')
  return (
    <div data-slot="domain-detail-hero" data-tone={tone} role="group" aria-label={t('dom.expiry')}
      // @container: kalan gün + bitiş yan yana mı alt alta mı KAHRAMANIN kendi genişliğine göre (tablette ~370 px — üst
      // kabın genişliği "yan yana" derken satır yarım kırılıp etiket tarihten kopuyordu); plan bloğu da buna uyar.
      className={cn('@container flex min-w-0 flex-col gap-3 rounded-xl border p-4', HERO_BOX[tone])}>
      <div className="flex min-w-0 flex-col gap-2 @md:flex-row @md:flex-wrap @md:items-end @md:justify-between @md:gap-x-6">
        <p className="flex min-w-0 items-baseline gap-2">
          <span data-slot="domain-days" className={cn('text-5xl leading-none font-bold tracking-tight tabular-nums', daysTone(days))}>
            {tone === 'expired' ? Math.abs(days) : days}
          </span>
          <span className="text-sm font-medium text-muted-foreground">{daysLabel(tone, days, t)}</span>
        </p>
        {m.expiry_date && (
          <p data-slot="domain-expiry" className="flex min-w-0 flex-col gap-0.5 @md:items-end @md:text-right">
            <span className="text-[10.5px] font-semibold tracking-[.06em] text-muted-foreground uppercase">
              {tone === 'expired' ? t('certcard.expiredOn') : t('card.expiresShort')}
            </span>
            <time dateTime={expiryKey(m) || undefined} className="text-sm font-semibold text-foreground">
              {fmtLongDay(m.expiry_date)}
              {clock && <span className="font-normal text-muted-foreground tabular-nums"> · {clock}</span>}
            </time>
          </p>
        )}
      </div>
      {showBar && (
        <div className="min-w-0">
          {/* decorative: aynı değer hemen altta METİN ("365 günden 212 gün kaldı"); çubuk onun görsel eşi. */}
          <ProgressBar value={life.remaining} max={life.total} size="sm" decorative tone={BAR_TONE[tone]}
            className={cn('h-2', tone === 'warning' && '[--pg-fill:var(--color-orange-500)]')} />
          <div className="mt-1.5 flex min-w-0 flex-wrap items-center justify-between gap-x-3 gap-y-1 text-xs text-muted-foreground">
            <span data-slot="domain-life" className="font-medium text-foreground/80 tabular-nums">{t('certcard.validityLeft', life.remaining, life.total)}</span>
            <span className="inline-flex min-w-0 items-center gap-1">
              {t('domdet.periodFrom', fmtExpiry(life.start))}
              <HintPopover content={lifeHint} align="end" aria-label={t('domdet.periodHelp')}
                triggerClassName="size-6 justify-center rounded-full text-muted-foreground hover:text-foreground pointer-coarse:size-10">
                <Info aria-hidden="true" className="size-3.5" />
              </HintPopover>
            </span>
          </div>
        </div>
      )}
      <PlanBlock block={block} monitor={m} onPlanRenewal={onPlanRenewal} />
    </div>
  )
}

/**
 * Bitişi bilinmeyen kayıt: neden + (hata varsa) tam hata metni + sonraki adımlar + eylemler. Hiç kontrol yokken ve ilk
 * sorgu ŞU AN koşarken neden "İlk kontrol yapılıyor…" olur (kartla aynı: "Henüz kontrol edilmedi" o sırada yanlış).
 */
function UnknownPanel({ monitor: m, running, onCheck, onDiagnose, block, onPlanRenewal }) {
  const t = useT()
  const stepsId = useId()
  const r = unknownReasonOf(m)
  const error = String(m.error || '').trim()
  const why = r.kind === 'never' ? (running ? t('domdet.firstRunning') : t('domcard.unknownNever'))
    : r.kind === 'error' ? t('domdet.whyError')
      : r.source ? t('domcard.unknownNoData', r.source) : t('domcard.unknownNoSource')
  const steps = nextStepsOf(m, { canDiagnose: !!onDiagnose })
  return (
    <div data-slot="domain-detail-unknown" data-reason={r.kind} role="group" aria-label={t('domcard.unknownTitle')}
      className="flex min-w-0 flex-col gap-3 rounded-xl border border-dashed bg-muted/40 p-4 dark:bg-muted/20">
      <div className="min-w-0">
        <p className="flex min-w-0 items-center gap-2 text-base font-bold">
          {running && r.kind === 'never'
            ? <Spinner size={18} inline decorative />
            : <CircleHelp aria-hidden="true" className="size-5 shrink-0 text-muted-foreground" />}
          <span className="min-w-0">{t('domcard.unknownTitle')}</span>
        </p>
        <p data-slot="domain-unknown-why" className="mt-1 text-sm text-muted-foreground">{why}</p>
      </div>
      {r.kind === 'error' && error && (
        <div className="flex min-w-0 items-start gap-2">
          <pre data-slot="domain-error" tabIndex={0} aria-label={t('domdet.errorDetail')}
            className="max-h-40 min-w-0 flex-1 overflow-auto rounded-md border bg-background/70 p-2.5 font-mono text-xs leading-relaxed whitespace-pre-wrap [overflow-wrap:anywhere]">
            {error}
          </pre>
          <CopyButton value={error} label={t('domdet.copyError')} copiedLabel={t('domdet.copied')} buttonSize="icon-sm"
            className="shrink-0 pointer-coarse:size-10" />
        </div>
      )}
      <div data-slot="domain-next-steps" className="min-w-0">
        <p id={stepsId} className="text-[10.5px] font-semibold tracking-[.06em] text-muted-foreground uppercase">{t('domdet.nextSteps')}</p>
        <ol aria-labelledby={stepsId} className="mt-1.5 flex list-decimal flex-col gap-1 pl-5 text-sm marker:text-muted-foreground">
          {steps.map((s) => <li key={s} data-step={s}>{t(`domdet.step.${s}`)}</li>)}
        </ol>
      </div>
      {(onCheck || onDiagnose) && (
        <div className="flex min-w-0 flex-wrap gap-2">
          {onCheck && <CheckButton monitor={m} running={running} onCheck={onCheck} variant="default" />}
          {onDiagnose && (
            <Button type="button" variant="outline" size="sm" onClick={onDiagnose} className={TOUCH}
              aria-label={t('a11y.rowAction', m.domain, t('dexp.diagnose'))}>
              <Stethoscope aria-hidden="true" />{t('dexp.diagnose')}
            </Button>
          )}
        </div>
      )}
      {block?.kind === 'plan' && <PlanBlock block={block} monitor={m} onPlanRenewal={onPlanRenewal} />}
    </div>
  )
}

function Fact({ icon: Icon, label, children }) {
  return (
    <>
      <dt className="flex items-center gap-1.5 text-xs font-medium whitespace-nowrap text-muted-foreground">
        <Icon aria-hidden="true" className="size-3.5 shrink-0 opacity-80" />{label}
      </dt>
      <dd className="min-w-0 text-sm [overflow-wrap:anywhere]">{children}</dd>
    </>
  )
}

/** Özet: registrar + IANA · kaynak (+ açıklama) · son kontrol (göreli + tam) · sıklık · eşikler · Şimdi kontrol et. */
function DetailFacts({ monitor: m, running, onCheck, showCheck }) {
  const t = useT()
  const src = m.source && m.source !== 'NONE' ? sourceTag(m.source, m.whois_provider) : null
  const fam = sourceFamily(m.source)
  const provider = m.whois_provider && WHOIS_PROVIDER_LABEL[m.whois_provider]
  const srcHint = [fam ? t(`domcard.src.${fam}`) : null, provider ? t('domcard.src.via', provider) : null].filter(Boolean).join('\n')
  const at = m.checked_at
  const exact = at ? formatDateSec(at) : null
  const hours = intervalHours(m)
  const warn = m.warning_days, crit = m.critical_days
  return (
    <div data-slot="domain-detail-facts" className="flex min-w-0 flex-col gap-3 rounded-xl border bg-card p-4">
      <dl className="grid min-w-0 grid-cols-[auto_minmax(0,1fr)] items-baseline gap-x-4 gap-y-2.5">
        <Fact icon={Building2} label={t('dom.registrar')}>
          {m.registrar ? <span data-slot="domain-detail-registrar">{m.registrar}</span> : '—'}
          {m.registrar_iana_id && <span className="text-xs whitespace-nowrap text-muted-foreground tabular-nums"> · IANA {m.registrar_iana_id}</span>}
        </Fact>
        <Fact icon={Database} label={t('dom.source')}>
          {src ? (
            <span className="inline-flex min-w-0 flex-wrap items-center gap-1">
              <span data-slot="domain-detail-source" className="font-medium">{src}</span>
              {srcHint && (
                <HintPopover content={srcHint} align="end" aria-label={t('a11y.rowAction', m.domain, `${t('dom.source')}: ${src}`)}
                  triggerClassName="size-6 justify-center rounded-full text-muted-foreground hover:text-foreground pointer-coarse:size-10">
                  <Info aria-hidden="true" className="size-3.5" />
                </HintPopover>
              )}
            </span>
          ) : '—'}
        </Fact>
        <Fact icon={Clock} label={t('dom.lastCheck')}>
          {at ? (
            <span data-slot="domain-detail-checked" className="flex min-w-0 flex-col">
              <time dateTime={toUtc(at)} className="font-medium">{relativeTime(at, t) || exact}</time>
              <span className="text-xs text-muted-foreground tabular-nums">{exact}</span>
            </span>
          ) : <span data-slot="domain-detail-checked" data-never="true" className="text-muted-foreground">{t('domdet.neverChecked')}</span>}
        </Fact>
        {hours && (
          <Fact icon={Repeat} label={t('domdet.interval')}>{hours === 1 ? t('domdet.everyHour') : t('domdet.everyHours', hours)}</Fact>
        )}
        {warn != null && crit != null && (
          <Fact icon={BellRing} label={t('domdet.thresholds')}>{t('domdet.thresholdsValue', warn, crit)}</Fact>
        )}
      </dl>
      {showCheck && onCheck && <CheckButton monitor={m} running={running} onCheck={onCheck} className="w-full @md:w-auto @md:self-start" />}
    </div>
  )
}

/**
 * @param {object}   monitor        GET /monitoring/domain satırı (snake_case) — sayfanın `selected`'ı
 * @param {boolean}  running        bu izleme ŞU AN kontrol ediliyor (sayfanın isRunning(id))
 * @param {Function} onCheck        Şimdi kontrol et — verilmezse düğme yok
 * @param {Function} onPlanRenewal  yenileme planı penceresi — verilmezse plan bilgisi salt okunur, kısayol yok
 * @param {Function} onDiagnose     Sorun Tanıla (yönetici) — bitiş bilinmiyorken sonraki adım
 */
export default function DomainDetailHeader({ monitor: m, running = false, onCheck, onPlanRenewal, onDiagnose, className }) {
  const t = useT()
  const tone = expiryTone(m.days_remaining)
  const block = planBlockOf(m, !!onPlanRenewal)
  const unknown = tone === 'unknown'
  return (
    <section data-slot="domain-detail-header" data-tone={tone} aria-label={t('domdet.summary')}
      className={cn('@container flex min-w-0 flex-col gap-3', className)}>
      <DetailIdentity monitor={m} />
      <DetailFlags monitor={m} />
      {/* items-start: kutular kendi boyunda — kısa kahraman (dolmuş kayıt: çubuk yok) özetin boyuna uzayıp içi boş kalmasın */}
      <div className="grid min-w-0 items-start gap-3 @3xl:grid-cols-[minmax(0,1.45fr)_minmax(0,1fr)]">
        {unknown
          ? <UnknownPanel monitor={m} running={running} onCheck={onCheck} onDiagnose={onDiagnose} block={block} onPlanRenewal={onPlanRenewal} />
          : <ExpiryHero monitor={m} tone={tone} block={block} onPlanRenewal={onPlanRenewal} />}
        <DetailFacts monitor={m} running={running} onCheck={onCheck} showCheck={!unknown} />
      </div>
      {/* Bir bakışta koruma + EPP (kartın çipleri; ayrıntı "Domain Kaydı" sekmesinde). Çiplerin kendi alt boşluğu sıfırlanır.
          Dokunmatikte çip satırları arası 20 px: çiplerin ::after ile 40 px'e büyüyen dokunma alanları üst üste binmesin
          (8 px aralıkta alttaki satır üstteki çipin alt yarısını yutuyordu — Playwright 390 isabet ölçümü). */}
      <div data-slot="domain-detail-glance" className="flex min-w-0 flex-col gap-2 empty:hidden pointer-coarse:gap-5 [&>ul]:mb-0 pointer-coarse:[&>ul]:gap-y-5">
        <DomainProtection monitor={m} />
        <DomainEppChips monitor={m} max={6} />
      </div>
    </section>
  )
}
