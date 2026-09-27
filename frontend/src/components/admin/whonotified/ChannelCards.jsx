import { forwardRef } from 'react'
import { Mail, Users, Webhook, BellRing } from 'lucide-react'
import { useT } from '../../../i18n/index.jsx'
import CopyButton from '../../ui/CopyButton.jsx'
import HintPopover from '../../ui/HintPopover.jsx'
import AlertBanner from '../../ui/AlertBanner.jsx'
import { avatarBg, initialsOf } from '../../ui/UserBadge.jsx'
import ToneBadge, { DecisionBadge, OrgRoleBadge } from '../ToneBadge.jsx'
import { Avatar, AvatarFallback } from '@/components/shadcn/avatar'
import { Badge } from '@/components/shadcn/badge'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/shadcn/card'
import { Item, ItemActions, ItemContent, ItemDescription, ItemGroup, ItemMedia, ItemTitle } from '@/components/shadcn/item'
import { cn } from '@/lib/utils'
import { LEVEL_DOT } from './whoNotifiedModel.js'

/**
 * "Kim bilgilendirilir?" kanal kartları — e-posta / push / webhook alıcıları ve "bilgilendirilmeyenler".
 *
 * <p>Her satır aynı iskelet (shadcn Item): avatar (baş harf ya da kaynak ikonu) · ad + rozetler · alt satır
 * (adres / kullanıcı adı) · eylem. NEDEN dahil olduğu, kaynak rozetine dokununca açılan `ui/HintPopover`'da
 * yazar — telefonda hover yok, açıklama dokunuşla da okunur. Sol renk şeridi YOK (kalıcı kural); durum rozetle.
 */

/** Kanal kartı kabı — başlık (ikon + ad + sayı) + tek satır açıklama + satır listesi. */
export const ChannelCard = forwardRef(function ChannelCard({ channel, icon: Icon, title, count, description, children, className }, ref) {
  return (
    <Card ref={ref} data-slot="wn-channel" data-channel={channel} tabIndex={-1}
      className={cn('min-w-0 scroll-mt-4 gap-3 py-4 outline-none sm:py-5', className)}>
      <CardHeader className="gap-1 px-4 sm:px-5">
        <CardTitle role="heading" aria-level={5} className="flex items-center gap-2 text-base">
          <Icon aria-hidden="true" className="size-4 shrink-0 text-primary" />
          <span className="min-w-0">{title}</span>
          {count != null && <Badge variant="secondary" data-wn="count" className="tabular-nums">{count}</Badge>}
        </CardTitle>
        {description && <CardDescription className="text-xs">{description}</CardDescription>}
      </CardHeader>
      <CardContent className="px-3 sm:px-4">{children}</CardContent>
    </Card>
  )
})

/** Baş harf avatarı (kişi) ya da kaynak ikonu (grup / takım adresi / webhook). Süs — ad zaten satırda yazılı. */
export function PersonAvatar({ name, ident, icon: Icon }) {
  if (Icon) {
    return (
      <span aria-hidden="true" className="inline-flex size-9 shrink-0 items-center justify-center rounded-full bg-primary/10 text-primary">
        <Icon className="size-4" />
      </span>
    )
  }
  return (
    <Avatar aria-hidden="true" className="size-9">
      <AvatarFallback className="text-xs font-bold text-white" style={{ background: avatarBg(ident || name) }}>
        {initialsOf(name, ident)}
      </AvatarFallback>
    </Avatar>
  )
}

/** Satır — shadcn Item; `role="listitem"` (ItemGroup `role="list"`). */
function RecipientItem({ source, media, title, badges, description, mono = true, extra, actions }) {
  return (
    <Item role="listitem" size="sm" variant="outline" data-slot="wn-recipient" data-source={source}
      className="flex-nowrap items-start gap-3 px-3 py-2.5">
      <ItemMedia>{media}</ItemMedia>
      <ItemContent className="min-w-0 gap-1">
        <ItemTitle className="w-full min-w-0 flex-wrap gap-1.5">
          <span className="min-w-0 font-semibold break-words">{title}</span>
          {badges}
        </ItemTitle>
        {description && (
          <ItemDescription className={cn('line-clamp-none text-xs', mono ? 'font-mono break-all' : 'break-words')}>{description}</ItemDescription>
        )}
        {extra}
      </ItemContent>
      {actions && <ItemActions className="shrink-0">{actions}</ItemActions>}
    </Item>
  )
}

/** Neden-rozeti: görünür metin rozet, dokununca/tıklayınca açıklama (HintPopover — telefonda da açılır). */
function WhyBadge({ tone = 'muted', label, why, who, ariaLabel }) {
  const t = useT()
  return (
    <HintPopover content={why} aria-label={ariaLabel || t('wn.whyAria', label, who)} triggerClassName="pointer-coarse:min-h-10">
      <ToneBadge tone={tone} data-wn="source">{label}</ToneBadge>
    </HintPopover>
  )
}

/** Seviye rozeti — "≥ YÜKSEK" (renkli nokta + metin; renk tek başına anlam taşımaz). */
export function LevelBadge({ level, prefix = '', className }) {
  const t = useT()
  if (!level) return null
  return (
    <Badge variant="outline" data-level={level} className={cn('gap-1.5 font-semibold', className)}>
      <span aria-hidden="true" className={cn('size-2 rounded-full', LEVEL_DOT[level] || 'bg-muted-foreground')} />
      {prefix}{t(`sim.level.${level}`)}
    </Badge>
  )
}

const none = (t) => <p className="px-1 py-2 text-sm text-muted-foreground">{t('sim.none')}</p>
const roleLabel = (t, role) => (role ? t(`ec.role.${String(role).toLowerCase()}`) : '')

const SOURCE_TONE = { group: 'info', team: 'neutral', contact: 'muted', globalContact: 'warning' }
const SOURCE_ICON = { group: Users, team: Mail }

export function EmailCard({ view, level, cardRef }) {
  const t = useT()
  const lvl = (l) => (l ? t(`sim.level.${l}`) : '—')
  const whyOf = (r) => {
    if (r.source === 'group') return t('wn.why.group', r.groupName)
    if (r.source === 'team') return t('wn.why.team')
    if (r.source === 'globalContact') return t('wn.why.globalContact', lvl(r.minLevel))
    return t('wn.why.contact', lvl(r.minLevel), lvl(level))
  }
  return (
    <ChannelCard ref={cardRef} channel="email" icon={Mail} title={t('sim.emailTitle')} count={view.counts.email}
      description={t('wn.emailCardDesc')}>
      {view.emails.length === 0 ? none(t) : (
        <ItemGroup className="gap-2">
          {view.emails.map((r) => {
            const label = t(`wn.src.${r.source}`)
            return (
              <RecipientItem key={r.key} source={r.source}
                media={<PersonAvatar name={r.name} ident={r.email} icon={SOURCE_ICON[r.source]} />}
                title={r.name || r.email}
                badges={<>
                  <WhyBadge tone={SOURCE_TONE[r.source]} label={label} why={whyOf(r)} who={r.name || r.email} />
                  {r.role && <OrgRoleBadge role={r.role}>{roleLabel(t, r.role)}</OrgRoleBadge>}
                  {r.minLevel && <LevelBadge level={r.minLevel} prefix="≥ " />}
                </>}
                description={r.email}
                extra={r.also.length > 0 && (
                  <p data-slot="wn-also" className="text-xs text-muted-foreground">
                    {t('wn.alsoContact', r.also.map((a) => a.name).join(', '))}
                  </p>
                )}
                actions={<CopyButton value={r.email} label={t('wn.copyEmail', r.email)} copiedLabel={t('wn.copied')}
                  variant="ghost" className="size-10 md:size-8" />}
              />
            )
          })}
        </ItemGroup>
      )}
    </ChannelCard>
  )
}

export function PushCard({ view, cardRef }) {
  const t = useT()
  const { push } = view
  const groupLabel = (g) => {
    if (!g) return '—'
    const key = `userpush.group.${g}`
    const v = t(key)
    return v === key ? g : v
  }
  return (
    <ChannelCard ref={cardRef} channel="push" icon={BellRing} title={t('sim.pushTitle')}
      count={push.available ? view.counts.push : null} description={t('wn.pushCardDesc')}>
      {!push.available ? (
        push.error
          ? <AlertBanner tone="danger" className="mb-0">{t('wn.pushError', push.error)}</AlertBanner>
          : <p className="px-1 py-2 text-sm text-muted-foreground">{t('wn.pushAdminOnly')}</p>
      ) : push.recipients.length === 0 ? none(t) : (
        <ItemGroup className="gap-2">
          {push.recipients.map((p) => (
            <RecipientItem key={p.key} source="push"
              media={<PersonAvatar name={p.name} ident={p.username} />}
              title={p.name}
              badges={<>
                <HintPopover content={t('wn.why.push', groupLabel(p.group), p.minLevel ? t(`sim.level.${p.minLevel}`) : '—')}
                  aria-label={t('wn.whyAria', t('userpush.decision.RECIPIENT'), p.name)} triggerClassName="pointer-coarse:min-h-10">
                  <DecisionBadge decision="RECIPIENT">{t('userpush.decision.RECIPIENT')}</DecisionBadge>
                </HintPopover>
                {p.orgRole && <OrgRoleBadge role={p.orgRole}>{t(`usr.orgRoleVal.${p.orgRole}`)}</OrgRoleBadge>}
              </>}
              description={p.username}
              extra={p.group && (
                <p className="flex flex-wrap items-center gap-1.5 text-xs text-muted-foreground">
                  {t('wn.pushGroup', groupLabel(p.group))}
                  {p.minLevel && <LevelBadge level={p.minLevel} prefix="≥ " className="h-5 text-[11px]" />}
                </p>
              )}
            />
          ))}
        </ItemGroup>
      )}
    </ChannelCard>
  )
}

const WEBHOOK_TYPE = { TEAMS: 'Microsoft Teams', SLACK: 'Slack' }

export function WebhookCard({ view, cardRef, className }) {
  const t = useT()
  return (
    <ChannelCard ref={cardRef} channel="webhook" icon={Webhook} title={t('sim.webhookTitle')} count={view.counts.webhook}
      description={t('wn.webhookCardDesc')} className={className}>
      {view.webhooks.length === 0 ? none(t) : (
        <ItemGroup className="gap-2">
          {view.webhooks.map((w) => (
            <RecipientItem key={w.key} source="webhook"
              media={<PersonAvatar name={w.name} icon={Webhook} />}
              title={w.name}
              badges={<>
                <ToneBadge tone="success">{WEBHOOK_TYPE[w.type] || w.type || '—'}</ToneBadge>
                {/* E-posta kartındaki aynı kişinin rozetinden AYRI ad (aynı ad iki düğmede = belirsiz) */}
                <WhyBadge label={t('wn.src.contact')} why={t('wn.why.webhook', w.name)} who={w.name}
                  ariaLabel={t('wn.whyWebhookAria', t('wn.src.contact'), w.name)} />
              </>}
              description={w.target}
            />
          ))}
        </ItemGroup>
      )}
    </ChannelCard>
  )
}

/** Bilgilendirilmeyenler — değerlendirilip elenen kişiler ve gerekçesi (push kararı / e-posta adresi yok). */
export function ExcludedList({ view }) {
  const t = useT()
  const reasonText = (code) => {
    const key = code === 'NO_EMAIL' ? 'wn.reason.NO_EMAIL' : `userpush.decision.${code}`
    const v = t(key)
    return v === key ? code : v
  }
  if (view.excluded.length === 0) {
    return <p className="px-1 py-2 text-sm text-muted-foreground">{t('wn.excludedNone')}</p>
  }
  return (
    <ItemGroup className="gap-2">
      {view.excluded.map((x) => (
        <RecipientItem key={x.key} source={`excluded-${x.channel}`}
          media={<PersonAvatar name={x.name} ident={x.sub} />}
          title={x.name}
          badges={<>
            <ToneBadge tone="neutral" data-wn="channel">
              {x.channel === 'push' ? <BellRing aria-hidden="true" /> : <Mail aria-hidden="true" />}
              {x.channel === 'push' ? t('wn.channelPush') : t('wn.channelEmail')}
            </ToneBadge>
            <DecisionBadge decision={x.reason} className="max-w-full whitespace-normal">{reasonText(x.reason)}</DecisionBadge>
          </>}
          description={x.channel === 'email' ? roleLabel(t, x.sub) : x.sub} mono={x.channel !== 'email'}
        />
      ))}
    </ItemGroup>
  )
}

