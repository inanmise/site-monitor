import { useState } from 'react'
import {
  CornerDownRight, CalendarClock, CircleAlert, CirclePause, CircleCheck, ShieldCheck, Layers, MailX, UserRound, UsersRound,
  CircleSlash, HelpCircle,
} from 'lucide-react'
import { formatDate } from '../../../api/client'
import { useDateLocale } from '../../../i18n/index.jsx'
import { TYPE_META } from '../../monitoring/overviewMeta.js'
import { formatDay } from '../../forwards/forwardModel.js'
import TeamBadge from '../../ui/TeamBadge.jsx'
import { Badge } from '@/components/shadcn/badge'
import { Button } from '@/components/shadcn/button'
import { cn } from '@/lib/utils'
import { SCOPE_CHIP_PREVIEW, daysLeft, expiringSoon, needsAttention, plural, scopeChips } from './alertForwardModel.js'

/**
 * Alarm iletimi listesinin ortak parçaları (tablo hücresi, kart ve "Bize iletilenler" satırı aynı parçaları kullanır).
 * Adlar KESİLMEZ: rozetler `rounded-md overflow-visible whitespace-normal` ile sarar (hap köşeleri çok satırlı metnin
 * uçlarını kırpmasın), metinler `[overflow-wrap:anywhere]`.
 */

/** Dar sütunda sarabilen rozet. */
export const WRAP_BADGE = 'h-auto rounded-md whitespace-normal overflow-visible text-left [overflow-wrap:anywhere]'

/** Tür → ikon (izleme menüsünün ikonları; cert = kalkan). */
export function typeIcon(type) {
  if (type === 'cert') return ShieldCheck
  return TYPE_META[type]?.Icon || Layers
}

const STATE_META = {
  active: { Icon: CircleCheck, cls: 'border-success/40 bg-success/10 text-success', variant: 'outline' },
  paused: { Icon: CirclePause, cls: 'border-border bg-muted text-muted-foreground', variant: 'outline' },
  expired: { Icon: CalendarClock, cls: 'border-border bg-muted text-muted-foreground', variant: 'outline' },
  recipient_inactive: { Icon: CircleSlash, cls: '', variant: 'warning' },
  no_targets: { Icon: CircleAlert, cls: '', variant: 'warning' },
  no_recipients: { Icon: CircleAlert, cls: '', variant: 'warning' },
  // Durum bilinmiyor (izleme dizini okunamadı): nötr — yeşil değil, uyarı da değil
  unknown: { Icon: HelpCircle, cls: 'border-border bg-muted text-muted-foreground', variant: 'outline' },
}

/** Durum rozeti (+ etkin ama adresi olmayan iletimde "yalnız push" uyarısı; adres sayısı okunamadıysa nötr not). */
export function StateBadge({ f, t }) {
  const meta = STATE_META[f.state] || { Icon: HelpCircle, cls: 'border-border bg-muted text-muted-foreground', variant: 'outline' }
  const Icon = meta.Icon
  return (
    <span className="flex min-w-0 flex-col items-start gap-1" data-slot="fwd-state-cell">
      <Badge variant={meta.variant} data-slot="fwd-state" data-state={f.state} className={cn(WRAP_BADGE, 'gap-1 font-semibold', meta.cls)}>
        <Icon aria-hidden="true" className="size-3.5 shrink-0" />{t(`fwd.state.${f.state}`)}
      </Badge>
      {f.state === 'active' && f.recipientAddressCount === 0 && (
        <Badge variant="warning" data-slot="fwd-no-address" className={cn(WRAP_BADGE, 'gap-1')}>
          <MailX aria-hidden="true" className="size-3.5 shrink-0" />{t('fwd.noAddress')}
        </Badge>
      )}
      {f.recipientAddressCount == null && f.state === 'active' && (
        <span className="text-xs text-muted-foreground" data-slot="fwd-address-unknown">{t('fwd.addressUnknown')}</span>
      )}
      {f.state === 'unknown' && (
        <span className="text-xs text-muted-foreground [overflow-wrap:anywhere]" data-slot="fwd-state-unknown">{t('fwd.stateUnknownHint')}</span>
      )}
    </span>
  )
}

/**
 * Bitiş: "Süresiz" ya da tarih + son günler rozeti. Bitiş SAF bir gündür ("yyyy-MM-dd", İstanbul günü): yerel tarihe
 * gün olarak çevrilir (`formatDay`) — UTC gece yarısı sayıp tarayıcı saat dilimine kaydırmak bir gün önceyi gösterirdi.
 */
export function Expiry({ f, t }) {
  const locale = useDateLocale()
  if (!f.expiresOn) return <span className="text-sm text-muted-foreground" data-slot="fwd-expiry">{t('fwd.noExpiry')}</span>
  const d = daysLeft(f.expiresOn)
  return (
    <span className="flex min-w-0 flex-col items-start gap-1" data-slot="fwd-expiry">
      <span className="text-sm tabular-nums">{t('fwd.until', formatDay(f.expiresOn, locale))}</span>
      {expiringSoon(f) && (
        <Badge variant="warning" data-slot="fwd-expiring" className={cn(WRAP_BADGE, 'tabular-nums')}>
          {d <= 0 ? t('fwd.lastDay') : plural(t, 'fwd.daysLeft', d)}
        </Badge>
      )}
    </span>
  )
}

/** Alıcı: takım rozeti + "Tüm takım" ya da üye adlarının TAMAMI (pasif / takımdan ayrılmış üye rozetli). */
export function Recipient({ f, t }) {
  const members = f.recipientMembers
  return (
    <span className="flex min-w-0 flex-col items-start gap-1.5" data-slot="fwd-recipient">
      <TeamBadge teamId={f.recipientTeamId} teamName={f.recipientTeamName || t('fwd.unknownTeam')} wrap static />
      {f.recipientTeamActive === false && (
        <Badge variant="warning" data-slot="fwd-team-inactive" className={WRAP_BADGE}>{t('fwd.teamInactive')}</Badge>
      )}
      {f.recipientKind === 'MEMBERS' ? (
        <ul className="m-0 flex min-w-0 list-none flex-wrap gap-1 p-0" data-slot="fwd-members">
          {members.map((m, i) => (
            <li key={`${m.userId ?? 'x'}-${i}`} className="min-w-0 max-w-full">
              <Badge variant="outline" data-slot="fwd-member" data-active={m.active === false ? 'false' : 'true'}
                className={cn(WRAP_BADGE, 'gap-1 font-normal', (m.active === false || m.inTeam === false) && 'text-muted-foreground')}>
                <UserRound aria-hidden="true" className="size-3.5 shrink-0" />
                {/* Ek (pasif / ayrıldı) adla AYNI satır içi metinde: ayrı esnek öğe dar rozette harf harf sarıyordu */}
                <span className="min-w-0 [overflow-wrap:anywhere]">
                  {m.displayName || t('fwd.unknownPerson')}
                  {m.active === false && <span className="font-semibold whitespace-nowrap text-destructive"> · {t('fwd.memberPassive')}</span>}
                  {m.active !== false && m.inTeam === false && <span className="font-semibold whitespace-nowrap text-amber-700 dark:text-amber-300"> · {t('fwd.memberLeft')}</span>}
                </span>
              </Badge>
            </li>
          ))}
        </ul>
      ) : (
        <span className="flex items-center gap-1 text-xs text-muted-foreground" data-slot="fwd-whole-team">
          <UsersRound aria-hidden="true" className="size-3.5 shrink-0" />{t('fwd.rcpt.wholeTeam')}
        </span>
      )}
    </span>
  )
}

/** Kapsam çipleri: ilk {@link SCOPE_CHIP_PREVIEW} görünür, fazlası "+N daha" ile açılır — açılınca HEPSİ. */
export function ScopeChips({ f, t }) {
  const [open, setOpen] = useState(false)
  const chips = scopeChips(f, t)
  const shown = open ? chips : chips.slice(0, SCOPE_CHIP_PREVIEW)
  const rest = chips.length - shown.length
  return (
    <span className="flex min-w-0 flex-col items-start gap-1" data-slot="fwd-scope" data-kind={f.scopeKind || ''}>
      <ul className="m-0 flex min-w-0 list-none flex-wrap gap-1 p-0">
        {shown.map((c) => {
          const Icon = c.kind === 'all' ? Layers : typeIcon(c.type)
          return (
            <li key={c.key} className="min-w-0 max-w-full">
              <Badge variant={c.kind === 'all' ? 'secondary' : 'outline'} data-slot="fwd-scope-chip" data-kind={c.kind}
                data-missing={c.missing || undefined}
                data-unknown={c.unknown || undefined}
                className={cn(WRAP_BADGE, 'gap-1 font-normal', c.missing && 'text-muted-foreground line-through', c.unknown && 'text-muted-foreground')}>
                <Icon aria-hidden="true" className="size-3.5 shrink-0" />
                <span className="min-w-0 [overflow-wrap:anywhere]">
                  {c.label}
                  {c.paused && <span className="whitespace-nowrap text-muted-foreground"> · {t('fwd.monitorPaused')}</span>}
                </span>
              </Badge>
            </li>
          )
        })}
      </ul>
      {chips.length > SCOPE_CHIP_PREVIEW && (
        <Button type="button" variant="link" size="sm" data-slot="fwd-scope-more" aria-expanded={open}
          className="h-auto min-h-8 px-0 text-xs pointer-coarse:min-h-10" onClick={() => setOpen((v) => !v)}>
          {open ? t('fwd.scope.less') : t('fwd.scope.more', rest)}
        </Button>
      )}
    </span>
  )
}

/** "Ekleyen · tarih" (+ güncelleyen farklıysa). */
export function Meta({ f, t }) {
  return (
    <span className="flex min-w-0 flex-col gap-0.5 text-xs text-muted-foreground [overflow-wrap:anywhere]" data-slot="fwd-meta">
      <span>{t('fwd.addedBy', f.createdBy || '—', f.createdAt ? formatDate(f.createdAt) : '—')}</span>
      {f.updatedAt && f.updatedAt !== f.createdAt && (
        <span>{t('fwd.updatedBy', f.updatedBy || '—', formatDate(f.updatedAt))}</span>
      )}
    </span>
  )
}

/** Not (varsa). */
export function Note({ f }) {
  if (!f.note) return null
  return <p className="m-0 text-xs text-muted-foreground [overflow-wrap:anywhere]" data-slot="fwd-note">{f.note}</p>
}

/**
 * Sahip → alıcı akışı (kart ve "Bize iletilenler"): her genişlikte DİKEY — üstte sahip, altında girintili ok ile alıcı.
 * Yan yana dizilişte uzun adlar sarınca ok satır başına düşüp akış okunmuyordu (telefon).
 */
export function Flow({ f, t }) {
  return (
    <span className="flex min-w-0 flex-col gap-1.5" data-slot="fwd-flow">
      <span className="flex min-w-0 flex-col gap-0.5">
        <span className="text-[11px] font-semibold tracking-wide text-muted-foreground uppercase">{t('fwd.col.owner')}</span>
        <TeamBadge teamId={f.ownerTeamId} teamName={f.ownerTeamName || t('fwd.unknownTeam')} wrap static />
      </span>
      <span className="flex min-w-0 items-start gap-1.5 ps-1">
        <CornerDownRight aria-hidden="true" className="mt-0.5 size-4 shrink-0 text-muted-foreground" />
        <span className="flex min-w-0 flex-1 flex-col gap-0.5">
          <span className="text-[11px] font-semibold tracking-wide text-muted-foreground uppercase">{t('fwd.col.recipient')}</span>
          <Recipient f={f} t={t} />
        </span>
      </span>
    </span>
  )
}

/** Dikkat gerektiren satırın kısa açıklaması (rozetin altında; neden ve ne yapılmalı). */
export function AttentionHint({ f, t }) {
  if (!needsAttention(f)) return null
  const key = f.state === 'active' ? 'fwd.attention.noAddress' : `fwd.attention.${f.state}`
  return <p className="m-0 text-xs text-amber-700 dark:text-amber-300 [overflow-wrap:anywhere]" data-slot="fwd-attention">{t(key)}</p>
}
