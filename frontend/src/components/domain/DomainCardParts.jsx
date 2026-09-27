import {
  Activity, Ban, Building2, CalendarClock, CalendarPlus, CircleHelp, Info, ListX, Lock, LockOpen, Server, ServerCrash,
  ShieldCheck, ShieldOff, ShieldQuestion,
} from 'lucide-react'
import { useT } from '../../i18n/index.jsx'
import { CARD_LAYER, MonitorCardTag } from '../monitoring/MonitorCard.jsx'
import { CHIP, CHIP_HOVER, CHIP_TONE, TOUCH_CHIP } from '../certcard/CertCardParts.jsx'
import { dateOnly } from '../certcard/certCardModel.js'
import HintPopover from '../ui/HintPopover.jsx'
import { ProgressBar } from '../ui/Progress.jsx'
import { Badge } from '@/components/shadcn/badge'
import { Button } from '@/components/shadcn/button'
import { cn } from '@/lib/utils'
import {
  WHOIS_PROVIDER_LABEL, blacklistOf, cardSourceTag, daysTone, dnssecOf, eppChips, expiryKey, fmtExpiry, lockOf, nsOf,
  sourceFamily, unknownReasonOf,
} from './domainCardModel.js'

/**
 * Alan Adı kartının parçaları (2026-09-27 yeniden tasarım) — izleme kartlarıyla (Port / HTTP / Ping) ve panodaki
 * sertifika kartıyla (tonlu kahraman panel, kalan süre çubuğu, plan çipi) AYNI görsel dil. SOL RENK ŞERİDİ YOK (kalıcı
 * kural): durum rozetle ve kökte `data-status` ile.
 *
 * <p>Dokunma: açıklamalı çipler HintPopover tetiği (fare, klavye VE dokunuşla açılır — yalnız-hover bilgi yok); tetik
 * örtünün üstünde (`CARD_LAYER`), dokunmatik işaretçide `::after` ile 40 px yüksekliğe genişler ({@link CHIP_TOUCH}).
 */

/** Detay penceresindeki EPP listesi (sayfada) — mor ton: alan adı serisinin rengi (Vade Takvimi ile aynı). */
export const EPP_BADGE = 'border-violet-600/30 bg-violet-600/10 px-[7px] py-px text-[10.5px] font-semibold text-violet-700 dark:text-violet-300'

/** Küçük çip (20 px) — koruma ve EPP satırları; ton `CHIP_TONE` (sertifika kartının çip ailesi). */
const CHIP_SM = "h-5 min-w-0 max-w-full gap-1 rounded-md border px-1.5 py-0 text-[10.5px] font-semibold whitespace-nowrap shadow-none pointer-coarse:min-w-10 [&_svg:not([class*='size-'])]:size-3"
/** Dokunmatikte 20 px çipin dokunma alanı: ::after dikeyde 10'ar px taşar → 40 px hedef. */
const CHIP_TOUCH = 'pointer-coarse:after:absolute pointer-coarse:after:inset-x-0 pointer-coarse:after:-inset-y-2.5'
/** Çip dokun-gör tetiği: örtünün üstünde, 40 px dokunma alanı (HintPopover'ın min-h-8'i ::after'a bırakılır). */
const CHIP_TRIGGER = cn(CARD_LAYER, CHIP_TOUCH, 'max-w-full rounded-md pointer-coarse:min-h-0')
/** Tek satırlık küçük metin tetiği (~16 px): ::after dikeyde 12'şer px taşar → 40 px hedef. */
const TEXT_TOUCH = 'pointer-coarse:after:absolute pointer-coarse:after:inset-x-0 pointer-coarse:after:-inset-y-3'

/** Açıklamalı küçük çip: `hint` varsa dokun-gör tetiği, yoksa düz rozet. Etiket DOĞRUDAN rozetin metnidir. */
function HintChip({ hint, name, variant = 'secondary', tone, chip, className, children }) {
  const badge = (
    <Badge variant={variant} data-chip={chip} data-tone={tone} className={cn(CHIP_SM, CHIP_TONE[tone] ?? CHIP_TONE.muted, className)}>
      {children}
    </Badge>
  )
  if (!hint) return badge
  return <HintPopover content={hint} triggerClassName={CHIP_TRIGGER} aria-label={name}>{badge}</HintPopover>
}

/**
 * Kaynak etiketi (RDAP / WHOIS · isimtescil.net) — kart üstündeki tür etiketi dilinde (MonitorCardTag); ne anlama
 * geldiği (RDAP'tan kilit/DNSSEC doğrulanır, WHOIS'ten her zaman değil; cevabı veren .tr kaynağı) dokun-gör balonunda.
 * "NONE" etiket olarak yazılmaz (kahraman panel "bilinmiyor"u anlatır).
 */
export function DomainSourceTag({ monitor: m }) {
  const t = useT()
  const tag = cardSourceTag(m)
  if (!tag) return null
  const fam = sourceFamily(m.source)
  const provider = m.whois_provider && WHOIS_PROVIDER_LABEL[m.whois_provider]
  const hint = [fam ? t(`domcard.src.${fam}`) : null, provider ? t('domcard.src.via', provider) : null].filter(Boolean).join('\n')
  const body = <MonitorCardTag data-source={m.source}>{tag}</MonitorCardTag>
  if (!hint) return <span data-slot="domain-source" className="inline-flex">{body}</span>
  return (
    <span data-slot="domain-source" className="inline-flex">
      <HintPopover content={hint} align="end" aria-label={t('a11y.rowAction', m.domain, `${t('dom.source')}: ${tag}`)}
        triggerClassName={cn(CARD_LAYER, 'rounded-sm pointer-coarse:min-h-10 pointer-coarse:min-w-10 pointer-coarse:px-1')}>
        {body}
      </HintPopover>
    </span>
  )
}

/**
 * "Değişti" çipi — son kontrol kayıt bilgisinde (registrar / NS / EPP / DNSSEC) değişiklik gördü. Eskiden yalnız ipuçlu
 * bir ikondu (dokunmatikte hiç açılmıyordu); artık görünür sözcük + ayrıntı dokun-gör balonunda.
 */
export function DomainChangedChip({ monitor: m }) {
  const t = useT()
  if (!m.changed) return null
  const label = m.change_detail ? `${t('dom.changedTip')} — ${m.change_detail}` : t('dom.changedTip')
  return (
    <span data-slot="domain-changed" className="inline-flex">
      <HintPopover content={label} aria-label={t('a11y.rowAction', m.domain, t('dom.dashChanged'))}
        triggerClassName={cn(CARD_LAYER, 'rounded-md pointer-coarse:min-h-10')}>
        <Badge variant="outline" className={cn('h-[22px] gap-1 rounded-md px-1.5 text-[11px] font-bold', CHIP_TONE.plan)}>
          <Activity aria-hidden="true" className="size-3" />{t('dom.dashChanged')}
        </Badge>
      </HintPopover>
    </span>
  )
}

/** Registrar satırı (sertifika kartındaki "veren" satırının eşi); tam ad + IANA kimliği `title`'da. */
export function DomainRegistrarLine({ monitor: m }) {
  const t = useT()
  if (!m.registrar) return null
  const title = [m.registrar, m.registrar_iana_id ? `IANA ${m.registrar_iana_id}` : null].filter(Boolean).join(' · ')
  return (
    <p data-slot="domain-registrar" className={cn(CARD_LAYER, 'mt-1 flex w-fit max-w-full min-w-0 items-center gap-1 text-xs text-muted-foreground')}>
      <Building2 aria-hidden="true" className="size-3.5 shrink-0 opacity-70" />
      <span className="sr-only">{t('dom.registrar')}: </span>
      <span className="min-w-0 truncate" title={title}>{m.registrar}</span>
    </p>
  )
}

// ── Kahraman panel: kalan gün + bitiş tarihi + kalan kayıt süresi çubuğu ─────────────────────────────────────────────
const HERO_BOX = {
  ok: 'bg-muted/40 dark:bg-muted/25',
  warning: 'border-orange-500/40 bg-orange-500/5 dark:bg-orange-500/10',
  critical: 'border-destructive/35 bg-destructive/5 dark:bg-destructive/10',
  expired: 'border-destructive/50 bg-destructive/10 dark:bg-destructive/15',
  unknown: 'border-dashed bg-muted/40 dark:bg-muted/20',
}
/** Çubuk dolgusu — ProgressBar tonu; ≤ 30 gün turuncu (kalan gün mürekkebiyle aynı), `--pg-fill` ile. */
const BAR_TONE = { ok: 'ok', critical: 'crit' }

function daysLabel(tone, days, t) {
  if (tone === 'expired') return Math.abs(days) === 1 ? t('certcard.daySinceExpiry') : t('certcard.daysSinceExpiry')
  if (days === 0) return t('certcard.expiresToday')
  return days === 1 ? t('certcard.dayLeft') : t('card.daysLeft')
}

/** Bitişi bilinmeyen kartın başlığı + nedeni (hiç kontrol yok / sorgu hatası / kaynak tarih döndürmedi); Kompakt'ta tek satır. */
function UnknownBody({ monitor: m, compact }) {
  const t = useT()
  const r = unknownReasonOf(m)
  const why = r.kind === 'never' ? t('domcard.unknownNever')
    : r.kind === 'error' ? t('domcard.unknownError', r.detail)
      : r.source ? t('domcard.unknownNoData', r.source) : t('domcard.unknownNoSource')
  return (
    <div data-slot="domain-unknown" data-reason={r.kind} className="min-w-0">
      <p className="flex min-w-0 items-center gap-1.5 text-sm leading-tight font-bold text-muted-foreground">
        <CircleHelp aria-hidden="true" className="size-4 shrink-0" />
        <span className="min-w-0">{t('domcard.unknownTitle')}</span>
      </p>
      <p data-slot="domain-unknown-why" title={compact ? why : undefined}
        className={cn('mt-0.5 min-w-0 pl-[22px] text-xs leading-snug [overflow-wrap:anywhere]', compact ? 'truncate' : 'line-clamp-2',
          r.kind === 'error' ? 'font-medium text-foreground/80' : 'text-muted-foreground')}>
        {why}
      </p>
    </div>
  )
}

/**
 * Kalan gün (büyük, {@link daysTone} mürekkebi) · bitiş tarihi (sağda) · kalan kayıt süresi çubuğu (son güncelleme →
 * bitiş; dolmuş / bilinmeyen kartta yok) · "365 günden 212 gün kaldı" (dönemin ayrıntısı dokun-gör balonunda) +
 * yenileme planı çipi ya da kısayolu (`end`). Bitiş bilinmiyorsa kesik kenarlı nötr panel nedeni söyler.
 * Test kancaları: `domain-hero` (`data-tone`), `domain-days`, `domain-expiry`, `domain-life`, `domain-unknown`.
 */
export function DomainHero({ monitor: m, tone, life, end, compact = false }) {
  const t = useT()
  const days = m.days_remaining
  const unknown = tone === 'unknown'
  if (compact) return <CompactHero monitor={m} tone={tone} end={end} />
  const showBar = !!life && !unknown && tone !== 'expired'
  const lifeHint = life && [
    t('dom.lifeTip', fmtExpiry(life.start), life.elapsed, life.total),
    m.registration_date ? t('domcard.registeredOn', fmtExpiry(m.registration_date)) : null,
  ].filter(Boolean).join('\n')
  const hasFoot = showBar || end
  return (
    <div data-slot="domain-hero" data-tone={tone} role="group" aria-label={t('dom.expiry')}
      className={cn('mb-3 min-w-0 rounded-lg border px-3 py-2.5', HERO_BOX[tone])}>
      {unknown ? <UnknownBody monitor={m} /> : (
        <div className="flex min-w-0 items-start justify-between gap-3">
          <div className="min-w-0">
            <div data-slot="domain-days" className={cn('text-[2rem] leading-none font-bold tracking-tight tabular-nums', daysTone(days))}>
              {tone === 'expired' ? Math.abs(days) : days}
            </div>
            <div className="mt-1 text-xs font-medium text-muted-foreground">{daysLabel(tone, days, t)}</div>
          </div>
          {m.expiry_date && (
            <div data-slot="domain-expiry" className="flex min-w-0 shrink-0 flex-col items-end gap-0.5 text-right">
              <span className="text-[10px] font-semibold tracking-[.06em] text-muted-foreground uppercase">
                {tone === 'expired' ? t('certcard.expiredOn') : t('card.expiresShort')}
              </span>
              <time dateTime={expiryKey(m) || undefined} className="text-sm font-semibold text-foreground tabular-nums">{fmtExpiry(m.expiry_date)}</time>
            </div>
          )}
        </div>
      )}
      {hasFoot && (
        <div className="mt-2.5">
          {showBar && (
            // decorative: aynı değer hemen altta METİN ("365 günden 212 gün kaldı"); çubuk onun görsel eşi.
            <ProgressBar value={life.remaining} max={life.total} size="sm" decorative tone={BAR_TONE[tone]}
              className={cn('h-1.5', tone === 'warning' && '[--pg-fill:var(--color-orange-500)]')} />
          )}
          <div className={cn('flex min-w-0 flex-wrap items-center justify-between gap-x-2 gap-y-1.5', showBar && 'mt-1.5')}>
            {showBar ? (
              <HintPopover content={lifeHint} aria-label={t('a11y.rowAction', m.domain, t('certcard.validityLeft', life.remaining, life.total))}
                triggerClassName={cn(CARD_LAYER, TEXT_TOUCH, 'rounded-sm pointer-coarse:min-h-0')}>
                <span data-slot="domain-life" className="inline-flex items-center gap-1 text-[11px] text-muted-foreground tabular-nums">
                  {t('certcard.validityLeft', life.remaining, life.total)}
                  <Info aria-hidden="true" className="size-3 opacity-60" />
                </span>
              </HintPopover>
            ) : <span />}
            {end && <span className="ml-auto flex min-w-0 flex-wrap items-center justify-end gap-1.5">{end}</span>}
          </div>
        </div>
      )}
    </div>
  )
}

/**
 * Kompakt kahraman: kalan gün (küçük) + etiket · sağda bitiş tarihi; çubuk ve dönem ayrıntısı YOK (Zengin'de).
 * Bitiş bilinmiyorsa başlık + tek satırlık neden. Varsa plan çipi altta.
 *
 * Dar kutu (2026-09-27, Kompakt ızgara tabanı 250 px → 1440'ta satırda 4 kart): kahramanın içi 16rem'den darsa
 * (`@container`) tarih sayının ALTINA iner ve etiketiyle tek satır olur ("DOLDU 26.04.2027"); yan yana düzen
 * "3 gün önce dol…" diye etiketi kesiyordu. Geniş kutuda (telefon, tablet) düzen eskisiyle aynı.
 */
function CompactHero({ monitor: m, tone, end }) {
  const t = useT()
  const days = m.days_remaining
  return (
    <div data-slot="domain-hero" data-tone={tone} data-compact="true" role="group" aria-label={t('dom.expiry')}
      className={cn('@container mb-2.5 min-w-0 rounded-lg border px-3 py-2', HERO_BOX[tone])}>
      {tone === 'unknown' ? <UnknownBody monitor={m} compact /> : (
        <div className="flex min-w-0 flex-col items-start gap-1 @min-[16rem]:flex-row @min-[16rem]:items-center @min-[16rem]:justify-between @min-[16rem]:gap-3">
          <div className="flex max-w-full min-w-0 items-baseline gap-1.5">
            <span data-slot="domain-days" className={cn('text-2xl leading-none font-bold tracking-tight tabular-nums', daysTone(days))}>
              {tone === 'expired' ? Math.abs(days) : days}
            </span>
            <span className="min-w-0 truncate text-xs font-medium text-muted-foreground">{daysLabel(tone, days, t)}</span>
          </div>
          {m.expiry_date && (
            <div data-slot="domain-expiry" className="flex shrink-0 items-baseline gap-1.5 leading-tight @min-[16rem]:flex-col @min-[16rem]:items-end @min-[16rem]:gap-0 @min-[16rem]:text-right">
              <span className="text-[9.5px] font-semibold tracking-[.06em] text-muted-foreground uppercase">
                {tone === 'expired' ? t('certcard.expiredOn') : t('card.expiresShort')}
              </span>
              <time dateTime={expiryKey(m) || undefined} className="text-xs font-semibold text-foreground tabular-nums">{fmtExpiry(m.expiry_date)}</time>
            </div>
          )}
        </div>
      )}
      {end && <div className="mt-2 flex min-w-0 flex-wrap justify-end gap-1.5">{end}</div>}
    </div>
  )
}

/**
 * Yenileme planı çipi: plan varsa tarihli çip ("Yenileme planlı · 06/10/2026 · kaydeden"; gecikmişse kırmızı) —
 * planı yönetebilen kişide paylaşılan RenewalPlanModal'ı açar, diğerlerinde kaydeden + not dokun-gör balonunda.
 * Plan yoksa ve kısayol uygunsa (bkz. domainCardModel.planChipOf) "Yenileme planla". Ad alan adını taşır.
 * Test kancası: `data-slot="domain-plan"` + `data-state` (planned | overdue | none).
 */
export function DomainPlanChip({ chip, monitor: m, onPlanRenewal }) {
  const t = useT()
  if (!chip) return null
  const open = (e) => { e.stopPropagation(); onPlanRenewal?.() }
  if (chip.kind === 'cta') {
    return (
      <Button type="button" variant="outline" size="xs" data-slot="domain-plan" data-state="none"
        aria-label={t('a11y.rowAction', m.domain, t('ccx.planCta'))} onClick={open}
        className={cn(CARD_LAYER, TOUCH_CHIP, CHIP, 'border-primary/50 bg-transparent text-primary hover:bg-primary/10 hover:text-primary dark:border-primary/50 dark:bg-transparent dark:hover:bg-primary/15')}>
        <CalendarPlus aria-hidden="true" /> {t('ccx.planCta')}
      </Button>
    )
  }
  const text = t(`certcard.plan.${chip.state}`, dateOnly(chip.at))
  const tone = chip.state === 'overdue' ? 'bad' : 'plan'
  const detail = [chip.by ? t('forecast.planSetBy', chip.by) : null, chip.note].filter(Boolean).join('\n')
  const body = (
    <>
      <CalendarClock aria-hidden="true" />
      <span className="min-w-0 truncate">
        {text}
        {chip.by && <span className="font-normal opacity-80"> · {chip.by}</span>}
      </span>
    </>
  )
  if (!onPlanRenewal) {
    const badge = (
      <Badge variant="outline" data-slot="domain-plan" data-state={chip.state} className={cn(CHIP, CHIP_TONE[tone])}>{body}</Badge>
    )
    return detail
      ? <HintPopover content={detail} align="end" aria-label={t('a11y.rowAction', m.domain, text)}
          triggerClassName={cn(CARD_LAYER, TOUCH_CHIP, 'max-w-full rounded-full pointer-coarse:min-h-0')}>{badge}</HintPopover>
      : badge
  }
  return (
    <Button type="button" variant="ghost" size="xs" data-slot="domain-plan" data-state={chip.state} title={detail || undefined}
      aria-label={t('a11y.rowAction', m.domain, text)} onClick={open}
      className={cn(CARD_LAYER, TOUCH_CHIP, CHIP, CHIP_TONE[tone], CHIP_HOVER[tone])}>
      {body}
    </Button>
  )
}

const LOCK_LABEL = { BOTH: 'dom.lockBoth', SERVER: 'dom.lockServer', CLIENT: 'dom.lockClient', NONE: 'dom.lockNone', UNKNOWN: 'dom.lockUnknown' }
const BL_LABEL = { CLEAN: 'dom.blClean', UNKNOWN: 'dom.blUnknown' }

/**
 * Koruma çipleri: transfer kilidi · DNSSEC · kara liste · ad sunucuları. Her çipin anlamı ve yapılacak iş dokun-gör
 * balonunda (dokunmatikte de açılır). Bilinmeyen değer "temiz/kilitli" gösterilmez: kilit "Doğrulanamadı", izlenmeyen
 * kara liste hiç çizilmez, ölçülmemiş NS "çözülmüyor" sayılmaz. Etiket rozetin doğrudan metnidir; `data-tone` ok|bad|muted.
 * Test kancaları: `domain-protection`, çip başına `data-chip="lock|dnssec|blacklist|ns"`.
 */
export function DomainProtection({ monitor: m }) {
  const t = useT()
  // Hiç kontrol edilmemiş kartta çiplerin hepsi "doğrulanamadı" olurdu — kahraman panel "henüz kontrol edilmedi" diyor.
  if (!m.checked_at) return null
  const lock = lockOf(m)
  const sec = dnssecOf(m)
  const bl = blacklistOf(m)
  const ns = nsOf(m)
  const name = (label) => t('a11y.rowAction', m.domain, label)
  const lockLabel = t(LOCK_LABEL[lock.state])
  // "Doğrulanamadı" tek başına neyin doğrulanamadığını söylemez → bilinmeyen kilitte çip adı taşır
  const lockText = lock.state === 'UNKNOWN' ? `${t('dom.transferLock')}: ${lockLabel}` : lockLabel
  const lockHint = lock.state === 'UNKNOWN' && m.source === 'WHOIS' ? t('dreg.whyLockWhois') : t(`domcard.lock.${lock.state}`)
  const LockIcon = lock.tone === 'ok' ? Lock : lock.tone === 'bad' ? LockOpen : ShieldQuestion
  const secLabel = sec === 'signed' ? t('dreg.dnssecSigned') : t('dreg.dnssecUnsigned')
  const blLabel = bl && (bl.status === 'LISTED' ? t('dom.blListed').replace('{n}', String(bl.lists.length || '?')) : t(BL_LABEL[bl.status]))
  const blHint = bl && (bl.status === 'LISTED' ? [t('dom.dashBlacklistedHint'), ...bl.lists].join('\n') : t(`domcard.bl.${bl.status}`))
  const nsBad = ns.resolves === false
  const nsLabel = nsBad ? (ns.count ? t('domcard.nsNotResolving', ns.count) : t('dom.dashNsFail')) : t('dom.nsCount', ns.count)
  const nsHint = [
    ns.count ? `${t('dreg.nameservers')}:\n${ns.list.join('\n')}` : null,
    ns.resolves === false ? t('domcard.nsFailHint') : ns.resolves === true ? t('domcard.nsOkHint') : null,
  ].filter(Boolean).join('\n')
  return (
    <ul data-slot="domain-protection" aria-label={t('dreg.protection')} className="m-0 mb-2 flex min-w-0 list-none flex-wrap gap-x-1.5 gap-y-2 p-0">
      <li className="min-w-0">
        <HintChip chip="lock" tone={lock.tone} hint={`${t('dom.transferLock')}: ${lockLabel}\n${lockHint}`} name={name(`${t('dom.transferLock')}: ${lockLabel}`)}>
          <LockIcon aria-hidden="true" />{lockText}
        </HintChip>
      </li>
      {sec && (
        <li className="min-w-0">
          <HintChip chip="dnssec" tone={sec === 'signed' ? 'ok' : 'muted'} hint={t(`domcard.dnssec.${sec}`)} name={name(`DNSSEC ${secLabel}`)}>
            {sec === 'signed' ? <ShieldCheck aria-hidden="true" /> : <ShieldOff aria-hidden="true" />}DNSSEC {secLabel}
          </HintChip>
        </li>
      )}
      {bl && (
        <li className="min-w-0">
          <HintChip chip="blacklist" tone={bl.tone} hint={blHint} name={name(`${t('dom.blacklist')}: ${blLabel}`)}>
            {bl.status === 'LISTED' ? <Ban aria-hidden="true" /> : <ListX aria-hidden="true" />}{t('dom.blacklist')}: {blLabel}
          </HintChip>
        </li>
      )}
      {(ns.count > 0 || nsBad) && (
        <li className="min-w-0">
          <HintChip chip="ns" tone={nsBad ? 'bad' : 'muted'} hint={nsHint} name={name(nsLabel)}>
            {nsBad ? <ServerCrash aria-hidden="true" /> : <Server aria-hidden="true" />}{nsLabel}
          </HintChip>
        </li>
      )}
    </ul>
  )
}

const EPP_CHIP_TONE = { bad: 'bad', warn: 'warn', info: 'plan' }

/**
 * EPP durum kodları — önem sırasıyla ilk dört çip, kalanı "+N" (kalan kodlar ve açıklamaları o çipin balonunda). Her
 * çipin sade dilde açıklaması (`epp.<kod>`) dokun-gör balonunda; kritik kodlar (client hold, redemption …) kırmızı,
 * uyarı kodları amber, kilitler mor. Test kancası `domain-epp`, çip `data-chip="epp"` + `data-tone`, fazlası `epp-more`.
 */
export function DomainEppChips({ monitor: m, max = 4 }) {
  const t = useT()
  const { shown, rest } = eppChips(m.status_codes, max)
  if (!shown.length) return null
  const desc = (c) => { const k = `epp.${c.key}`; const d = t(k); return d !== k ? d : null }
  return (
    <ul data-slot="domain-epp" aria-label={t('dreg.eppStatus')} className="m-0 mb-2 flex min-w-0 list-none flex-wrap gap-x-1.5 gap-y-2 p-0">
      {shown.map((c) => (
        <li key={c.code} className="min-w-0">
          <HintChip chip="epp" variant="outline" tone={EPP_CHIP_TONE[c.tone]} hint={desc(c)} name={t('a11y.rowAction', m.domain, c.label)}
            className="font-medium">
            {c.label}
          </HintChip>
        </li>
      ))}
      {rest.length > 0 && (
        <li className="min-w-0">
          <HintChip chip="epp-more" variant="outline" tone="muted" name={t('a11y.rowAction', m.domain, t('domcard.moreEpp', rest.length))}
            hint={rest.map((c) => { const d = desc(c); return d ? `${c.label} — ${d}` : c.label }).join('\n')}
            className="justify-center pointer-coarse:min-w-10">
            +{rest.length}
          </HintChip>
        </li>
      )}
    </ul>
  )
}
