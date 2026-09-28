import { useT } from '../../../i18n/index.jsx'
import { formatDateSec } from '../../../api/client'
import { avatarBg, initialsOf } from '../../ui/UserBadge.jsx'
import ToneBadge from '../ToneBadge.jsx'
import { Pill } from '../HealthUi.jsx'
import { idleBand, loginStatus, relTime, splitDuration } from './uactModel.js'
import { avatarSrc, splitDisplayName } from './directoryModel.js'
import { Avatar, AvatarFallback, AvatarImage } from '@/components/shadcn/avatar'
import { cn } from '@/lib/utils'

/**
 * Kullanıcı Dizini'nin ortak küçük parçaları (tablo, kart ve ayrıntı paneli aynı dili konuşsun): durum noktalı avatar,
 * çevrimiçi/son görülme satırı, hesap ve tur rozetleri, göreli zaman / süre biçimleyicileri. Hepsi shadcn Avatar / Badge
 * (ToneBadge, Pill) üzerinde; SOL RENKLİ ŞERİT YOK — durum rozet + nokta ile.
 */

/**
 * Pencerenin ÜSTÜNDE açılan Sheet katmanı (ayrıntı + telefon süzgeç paneli): shadcn Sheet'in `z-50`'si ModalShell'in
 * (`--z-modal` 2000 + derinlik) altında kalırdı. +5: dizin penceresinin üstünde, açılır menü / onay (`--z-menu` /
 * `--z-dialog`) katmanlarının altında; üstüne açılan iç pencere (takım üyeleri, derinlik 1 → +10) yine üstte kalır.
 */
export const OVER_MODAL_Z = 'z-[calc(var(--z-modal)_+_5)]'

/** Göreli zaman ("3 dk önce") ve süre ("1 sa 05 dk") — i18n'li. */
export function useDirFormat() {
  const t = useT()
  const rel = (iso) => { const r = relTime(iso); return r ? t(`uact.rel.${r.unit}`, r.n) : '—' }
  const dur = (sec) => {
    const d = splitDuration(sec)
    return d.h > 0 ? `${d.h} ${t('chg.unitHour')} ${String(d.m).padStart(2, '0')} ${t('chg.unitMin')}` : `${d.m} ${t('chg.unitMin')}`
  }
  return { t, rel, dur }
}

/** Boşta bandı → nokta rengi: canlı yeşil, boşta/uzakta amber (çevrimdışında nokta yok). */
const DOT = { live: 'bg-success', idle: 'bg-amber-500', away: 'bg-amber-500', unknown: 'bg-amber-500' }

/** Avatar (AD fotoğrafı + baş harf yedeği) + sağ altta çevrimiçi noktası. */
export function PresenceAvatar({ row, size = 'md', className }) {
  const { name } = splitDisplayName(row?.display_name, row?.department)
  const band = row?.online ? idleBand(row.idle_sec) : null
  return (
    <span data-slot="udir-avatar" className={cn('relative inline-flex shrink-0', className)}>
      <Avatar className={size === 'lg' ? 'size-12' : size === 'sm' ? 'size-7' : 'size-9'}>
        {avatarSrc(row) && <AvatarImage src={avatarSrc(row)} alt="" className="object-cover" />}
        <AvatarFallback className={cn('font-semibold text-white', size === 'lg' ? 'text-sm' : 'text-[11px]')}
          style={{ background: avatarBg(row?.username || name) }}>
          {initialsOf(name, row?.username)}
        </AvatarFallback>
      </Avatar>
      {band && (
        <span aria-hidden="true" data-presence={band}
          className={cn('absolute -right-0.5 -bottom-0.5 rounded-full ring-2 ring-background', size === 'lg' ? 'size-3.5' : 'size-3', DOT[band])} />
      )}
    </span>
  )
}

/** Canlı "çevrimiçi" noktası (nabız; hareket azaltmada durağan). */
export function LiveDot({ className }) {
  return (
    <span aria-hidden="true" className={cn('relative inline-flex size-2.5 shrink-0', className)}>
      <span className="absolute inline-flex size-full animate-ping rounded-full bg-success opacity-50 motion-reduce:animate-none" />
      <span className="relative inline-flex size-2.5 rounded-full bg-success" />
    </span>
  )
}

/**
 * Varlık satırı: çevrimiçiyse "● Çevrimiçi" + "etkin" / "boşta 12 dk"; değilse son görülme (göreli; tam zaman `title`da,
 * dokunmatikte ayrıntı panelinde). `stack` iki satır, değilse tek satır.
 */
export function PresenceLine({ row, stack = true }) {
  const { t, rel, dur } = useDirFormat()
  if (!row.online) {
    return row.last_seen
      ? <span className="tabular-nums" title={formatDateSec(row.last_seen)}>{rel(row.last_seen)}</span>
      : <span className="text-muted-foreground">—</span>
  }
  const band = idleBand(row.idle_sec)
  const sub = band === 'live' ? t('udir.bandLive') : (row.idle_sec >= 0 ? t('udir.idleFor', dur(row.idle_sec)) : t('udir.bandUnknown'))
  return (
    <span className={cn('inline-flex min-w-0 gap-x-1.5', stack ? 'flex-col items-start' : 'flex-wrap items-center')}>
      <span className="inline-flex items-center gap-1.5 font-semibold text-success" data-presence-text="">
        <span aria-hidden="true" className={cn('size-2 rounded-full', DOT[band])} />{t('uact.dirOnline')}
      </span>
      <span className={cn('text-xs tabular-nums', band === 'live' ? 'text-muted-foreground' : 'text-amber-700 dark:text-amber-300')}>{sub}</span>
    </span>
  )
}

/** Yalnız boşta metni ("etkin" / "boşta 12 dk") — ayrıntı panelinin "Boşta" alanı (üstte zaten "Çevrimiçi" rozeti var). */
export function IdleText({ row }) {
  const { t, dur } = useDirFormat()
  if (!row.online) return '—'
  const band = idleBand(row.idle_sec)
  const text = band === 'live' ? t('udir.bandLive') : (row.idle_sec >= 0 ? t('udir.idleFor', dur(row.idle_sec)) : t('udir.bandUnknown'))
  return (
    <span className={cn('inline-flex items-center gap-1.5 tabular-nums', band !== 'live' && 'text-amber-700 dark:text-amber-300')}>
      <span aria-hidden="true" className={cn('size-2 rounded-full', DOT[band])} />{text}
    </span>
  )
}

/** Varlık rozeti: çevrimiçiyse "● Çevrimiçi" (başarı tonu), değilse giriş durumu hapı (bugün / bu hafta / 30+ gün / hiç). */
export function PresenceBadge({ row }) {
  const t = useT()
  if (row.online) {
    return (
      <ToneBadge tone="success" data-presence-badge="online" className="gap-1.5">
        <span aria-hidden="true" className="size-1.5 rounded-full bg-success" />{t('uact.dirOnline')}
      </ToneBadge>
    )
  }
  const st = loginStatus(row)
  return <Pill tone={st} status={st}>{t(`uact.st.${st}`)}</Pill>
}

/** Hesap rozetleri: pasif ve/veya kalıcı kilitli (ikisi birden olabilir); ikisi de değilse "Aktif". `data-account`. */
export function AccountBadges({ row, className }) {
  const t = useT()
  const out = []
  if (row.active === false) out.push(<ToneBadge key="i" tone="danger" data-account="inactive">{t('usr.inactive')}</ToneBadge>)
  if (row.permanent_lock) out.push(<ToneBadge key="l" tone="danger" data-account="locked">{t('usr.permLocked')}</ToneBadge>)
  if (!out.length) out.push(<ToneBadge key="a" tone="success" data-account="active">{t('usr.active')}</ToneBadge>)
  return <span className={cn('inline-flex flex-wrap gap-1', className)}>{out}</span>
}

/** Ürün turu hapı ("Tur: tamamladı"); tam zaman `title`da. */
export function TourPill({ row, withLabel = true }) {
  const t = useT()
  const tour = row.tour_status || 'none'
  return (
    <Pill tone={tour} status={`tour-${tour}`} title={`${t('uact.colTour')}${row.tour_at ? ' · ' + formatDateSec(row.tour_at) : ''}`}>
      {withLabel ? `${t('uact.colTour')}: ` : ''}{t(`uact.tour.${tour}`)}
    </Pill>
  )
}

/** Kimlik izi "Gizli" durumu — ortak `ui/MaskedValue` (Kullanıcı / Oturum yüzeyleri buradan içe aktarır). */
export { default as MaskedValue } from '../../ui/MaskedValue.jsx'

/** Tanım listesi satırı (kart ve ayrıntı paneli): etiket üstte küçük, değer altta. */
export function DirField({ label, children, mono = false, full = false, className }) {
  return (
    <div className={cn('flex min-w-0 flex-col gap-0.5', full && 'col-span-full', className)}>
      <dt className="text-[11px] font-medium text-muted-foreground">{label}</dt>
      <dd className={cn('min-w-0 text-[13px] leading-snug break-words', mono && 'font-mono text-xs')}>{children ?? '—'}</dd>
    </div>
  )
}
