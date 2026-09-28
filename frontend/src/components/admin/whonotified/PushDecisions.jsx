import { useState } from 'react'
import { BellRing, Check, Lock, Search, Settings2, UserCheck, UserCog, X } from 'lucide-react'
import { useT } from '../../../i18n/index.jsx'
import { navigateTo } from '../../../utils/navigate.js'
import AlertBanner from '../../ui/AlertBanner.jsx'
import HintPopover from '../../ui/HintPopover.jsx'
import SegmentedControl from '../../ui/SegmentedControl.jsx'
import { DecisionBadge, OrgRoleBadge } from '../ToneBadge.jsx'
import { Badge } from '@/components/shadcn/badge'
import { Button } from '@/components/shadcn/button'
import { InputGroup, InputGroupAddon, InputGroupInput } from '@/components/shadcn/input-group'
import { ItemGroup } from '@/components/shadcn/item'
import { ChannelCard, LevelBadge, PersonAvatar, RecipientItem } from './ChannelCards.jsx'
import { filterPushRows, pushReasonText } from './whoNotifiedModel.js'

/**
 * "Kim bilgilendirilir?" — push kartı (2026-09-28, kullanıcı: "kişi bazlı webhook bildirimlerini bu sekmede
 * göremedim"). Takımın HER değerlendirilen üyesi "Alır" / "Almaz" kararıyla ve almayanın NEDENİYLE listelenir;
 * karar kodları Ayarlar → Webhook Bildirimleri → "Kim alır?" ile aynı sözlüktür (`userpush.decision.*` — sunucuda
 * tek servis, `UserPushRecipientResolver.explain`). Her "Almaz" satırı bir sonraki adımı söyler (kullanıcıyı aç,
 * rol gruplarını düzenle, Etkinliklerim).
 *
 * <p>Görünürlüğü SUNUCU verir (`push_access`, `PushDecisionAccess`): FULL tüm üyeler, SELF yalnız izleyenin kendi
 * satırı, NONE hiç satır — o zaman kartta NEDEN ve kime başvurulacağı yazar. Kanal düzeyi engeller (global kapalı,
 * adres yok, takım/tür kapsam dışı, sessiz saat) herkesi aynı anda etkiler: kartın başında uyarı, "alır" diyen kişi
 * de o an "Almaz" sayılır (`effective`).
 */

const levelLabel = (t, l) => (l ? t(`sim.level.${l}`) : '—')

/** i18n'de karşılığı yoksa ham değer (özel org rolü / grup anahtarı ekranda anahtar olarak görünmesin diye). */
const labelOr = (t, key, raw) => {
  const v = t(key)
  return v === key ? raw : v
}
const pushGroupLabel = (t, g) => (g ? labelOr(t, `userpush.group.${g}`, g) : '—')
const orgRoleLabel = (t, r) => (r ? labelOr(t, `usr.orgRoleVal.${r}`, r) : '—')

/** Ayarlar → Webhook Bildirimleri'ni kim açabilir: global yönetici (FULL) her şeyi, kapsamlı müdür adres dışını. */
const canOpenSettings = (settings) => settings === 'FULL' || settings === 'LIMITED'

/**
 * "Almaz" satırı için bir sonraki adım: açıklama + (izin varsa) eylem düğmesi. Eylem yalnız izleyenin gerçekten
 * yapabileceği yere götürür; yapamıyorsa kime başvuracağını söyler.
 */
export function pushFix(t, row, { access, settings, level, onNavigate }) {
  const openUser = access === 'FULL' && onNavigate ? {
    kind: 'user', label: t('wn.fixOpenUser'), aria: t('wn.fixOpenUserAria', row.name), icon: UserCog,
    onClick: () => onNavigate('users', { g_q: row.username || row.name }),
  } : null
  const settingsFix = (text) => (canOpenSettings(settings)
    ? { text, action: { kind: 'settings', label: t('wn.fixPushGroups'), aria: t('wn.fixPushGroupsAria', row.name), icon: Settings2,
        onClick: () => navigateTo('settings', { sec: 'userpush' }) } }
    : { text: `${text} ${t('wn.pushAskAdmin')}`, action: null })
  switch (row.effective) {
    case 'NO_ORG_ROLE':
      return row.isSelf && access !== 'FULL'
        ? { text: t('wn.fix.NO_ORG_ROLE.self'), action: null }
        : { text: t('wn.fix.NO_ORG_ROLE'), action: openUser }
    case 'NO_GROUP': return settingsFix(t('wn.fix.NO_GROUP', orgRoleLabel(t, row.orgRole)))
    case 'GROUP_DISABLED': return settingsFix(t('wn.fix.GROUP_DISABLED', pushGroupLabel(t, row.group)))
    case 'ALL_GROUPS_OFF': return settingsFix(t('wn.fix.ALL_GROUPS_OFF'))
    case 'BELOW_MIN_LEVEL':
      return settingsFix(t('wn.fix.BELOW_MIN_LEVEL', pushGroupLabel(t, row.group), levelLabel(t, row.minLevel), levelLabel(t, level)))
    case 'SKIPPED_USER_OPT_OUT':
      return row.isSelf
        ? { text: t('wn.fix.SKIPPED_USER_OPT_OUT.self'),
            action: { kind: 'myactivity', label: t('wn.fixMyActivity'), aria: null, icon: UserCheck, onClick: () => navigateTo('myactivity') } }
        : { text: t('wn.fix.SKIPPED_USER_OPT_OUT'), action: null }
    case 'INACTIVE': return { text: t('wn.fix.INACTIVE'), action: openUser }
    case 'SKIPPED_NO_ID': return { text: t('wn.fix.SKIPPED_NO_ID'), action: openUser }
    case 'MISSING_MEMBERSHIP': return { text: t('wn.fix.MISSING_MEMBERSHIP'), action: openUser }
    default: return null
  }
}

/** Tek kişi satırı — karar rozeti, org rolü; almıyorsa sade gerekçe + sonraki adım. */
function PushRow({ row, ctx }) {
  const t = useT()
  const fix = row.receives || row.blockedByChannel ? null : pushFix(t, row, ctx)
  const Act = fix?.action?.icon
  const decisionBadge = (
    <DecisionBadge decision={row.receives ? 'RECIPIENT' : row.effective} data-wn="push-decision">
      {row.receives ? <Check aria-hidden="true" /> : <X aria-hidden="true" />}
      {row.receives ? t('wn.pushYes') : t('wn.pushNo')}
    </DecisionBadge>
  )
  return (
    <RecipientItem source="push" data-receives={row.receives ? 'true' : 'false'}
      media={<PersonAvatar name={row.name} ident={row.username} />}
      title={row.name}
      badges={<>
        {row.isSelf && <Badge variant="secondary" data-wn="self">{t('wn.pushYou')}</Badge>}
        {row.receives ? (
          <HintPopover content={t('wn.why.push', pushGroupLabel(t, row.group), levelLabel(t, row.minLevel))}
            aria-label={t('wn.whyAria', t('wn.pushYes'), row.name)} triggerClassName="pointer-coarse:min-h-10">
            {decisionBadge}
          </HintPopover>
        ) : decisionBadge}
        {row.orgRole && <OrgRoleBadge role={row.orgRole}>{orgRoleLabel(t, row.orgRole)}</OrgRoleBadge>}
      </>}
      description={row.username}
      extra={row.receives ? (row.group && (
        <p className="flex flex-wrap items-center gap-1.5 text-xs text-muted-foreground">
          {t('wn.pushGroup', pushGroupLabel(t, row.group))}
          {row.minLevel && <LevelBadge level={row.minLevel} prefix="≥ " className="h-5 text-[11px]" />}
        </p>
      )) : (
        <div data-slot="wn-push-why" data-reason={row.effective} className="flex min-w-0 flex-col items-start gap-1.5">
          <p className="text-xs font-semibold text-foreground">{pushReasonText(t, row.effective)}</p>
          {row.blockedByChannel
            ? <p className="text-xs text-muted-foreground">{t('wn.pushWouldReceive')}</p>
            : fix?.text && <p data-slot="wn-push-fix" className="text-xs text-muted-foreground">{fix.text}</p>}
          {fix?.action && (
            <Button type="button" variant="outline" size="sm" className="h-10 md:h-8" data-fix={fix.action.kind}
              aria-label={fix.action.aria || undefined} onClick={fix.action.onClick}>
              <Act aria-hidden="true" /> {fix.action.label}
            </Button>
          )}
        </div>
      )}
    />
  )
}

/**
 * Kanal düzeyi durum — herkesi aynı anda etkileyen engel (uyarı + düzeltme yeri) ya da kısmi bilgi (bazı izleme
 * türlerinde push kapalı; sessiz saat penceresi bu seviyeyi susturuyor ama şu an dışındayız).
 */
function ChannelStatus({ channel, settings }) {
  const t = useT()
  if (!channel) return null
  const settingsButton = (
    <Button type="button" variant="outline" size="sm" className="h-10 md:h-8" data-fix="settings"
      onClick={() => navigateTo('settings', { sec: 'userpush' })}>
      <Settings2 aria-hidden="true" /> {t('wn.pushOpenSettings')}
    </Button>
  )
  if (channel.block) {
    const quiet = channel.block === 'SKIPPED_QUIET_HOURS'
    const body = quiet
      ? t('wn.pushBlock.SKIPPED_QUIET_HOURS', channel.quietStart || '—', channel.quietEnd || '—', levelLabel(t, channel.quietMinLevel))
      : t(`wn.pushBlock.${channel.block}`)
    // Adres GLOBAL_ONLY: kapsamlı müdür Ayarlar'ı açar ama adresi giremez → global yöneticiye yönlendir.
    const canFix = !quiet && (settings === 'FULL' || (settings === 'LIMITED' && channel.block !== 'NOT_CONFIGURED'))
    const ask = quiet || canFix ? null : (settings === 'LIMITED' ? t('wn.pushAskGlobal') : t('wn.pushAskAdmin'))
    return (
      <div data-slot="wn-push-channel" data-block={channel.block}>
        <AlertBanner tone={quiet ? 'info' : 'warning'} title={t('wn.pushBlockedTitle')} className="mb-0">
          {body}{ask ? ` ${ask}` : ''}
          {canFix && <div className="mt-2.5 flex flex-wrap gap-2">{settingsButton}</div>}
        </AlertBanner>
      </div>
    )
  }
  const typesOff = channel.disabledTypes.map((f) => labelOr(t, `userpush.type.${f}`, f))
  const quietNote = channel.quietStart && !channel.quietActive && channel.quietBlocksLevel
  if (typesOff.length === 0 && !quietNote) return null
  return (
    <div data-slot="wn-push-channel" className="flex flex-col gap-2">
      {typesOff.length > 0 && (
        <AlertBanner tone="info" className="mb-0">
          {t('wn.pushTypesOff', typesOff.join(', '))}
          {canOpenSettings(settings) && <div className="mt-2.5 flex flex-wrap gap-2">{settingsButton}</div>}
        </AlertBanner>
      )}
      {quietNote && (
        <AlertBanner tone="info" className="mb-0">
          {t('wn.pushQuietNote', channel.quietStart, channel.quietEnd, levelLabel(t, channel.quietMinLevel))}
        </AlertBanner>
      )}
    </div>
  )
}

/**
 * @param view          buildView() çıktısı
 * @param level         senaryo seviyesi (BELOW_MIN_LEVEL açıklaması için)
 * @param filter        'all' | 'yes' | 'no' — SimResult'ta tutulur ("Push almaz" kutucuğu süzgeci açar)
 * @param onNavigate    AdminPanel sekme geçişi (Kullanıcılar'da kişiyi aç)
 */
export function PushCard({ view, level, cardRef, filter = 'all', onFilterChange, onNavigate, className }) {
  const t = useT()
  const { push, counts } = view
  const [query, setQuery] = useState('')
  const ctx = { access: push.access, settings: push.settings, level, onNavigate }

  let description = t('wn.pushCardDescNone')
  if (push.access === 'FULL') description = t('wn.pushCardDescFull')
  else if (push.access === 'SELF') description = t('wn.pushCardDescSelf')

  let body
  if (push.error) {
    body = <AlertBanner tone="danger" className="mb-0">{t('wn.pushError', push.error)}</AlertBanner>
  } else if (push.access === 'NONE') {
    const reason = push.accessReason === 'NOT_MEMBER' ? 'NOT_MEMBER' : 'UNKNOWN'
    body = (
      <div data-slot="wn-push-hidden" data-reason={push.accessReason || 'UNKNOWN'}>
        <AlertBanner tone="info" icon={Lock} title={t('wn.pushHiddenTitle')} className="mb-0">
          <span className="block">{t(`wn.pushHidden.${reason}`)}</span>
          <span className="mt-1.5 block">{t('wn.pushHiddenAsk')}</span>
        </AlertBanner>
      </div>
    )
  } else if (push.access === 'SELF') {
    body = (
      <div className="flex flex-col gap-2">
        {push.self
          ? <ItemGroup className="gap-2"><PushRow row={push.self} ctx={ctx} /></ItemGroup>
          : <p data-slot="wn-push-self-missing" className="px-1 py-2 text-sm text-muted-foreground">{t('wn.pushSelfMissing')}</p>}
        <p className="px-1 text-xs text-muted-foreground">{t('wn.pushSelfNote')}</p>
      </div>
    )
  } else if (push.total === 0) {
    body = <p className="px-1 py-2 text-sm text-muted-foreground">{t('wn.pushNoMembers')}</p>
  } else {
    const rows = filterPushRows(push.rows, filter, query)
    body = (
      <div className="flex flex-col gap-3">
        <div data-slot="wn-push-toolbar" className="flex flex-col gap-2 sm:flex-row sm:flex-wrap sm:items-center sm:justify-between">
          <SegmentedControl value={filter} onChange={(v) => onFilterChange?.(v)} ariaLabel={t('wn.pushFilterAria')}
            className="w-full sm:w-fit [&>[data-slot=toggle-group-item]]:h-10 [&>[data-slot=toggle-group-item]]:flex-auto [&>[data-slot=toggle-group-item]]:px-2 sm:[&>[data-slot=toggle-group-item]]:flex-none sm:[&>[data-slot=toggle-group-item]]:px-3 md:[&>[data-slot=toggle-group-item]]:h-8"
            options={[
              { value: 'all', label: t('wn.pushFilterAll', push.total) },
              { value: 'yes', label: t('wn.pushFilterYes', counts.push) },
              { value: 'no', label: t('wn.pushFilterNo', counts.pushNot) },
            ]} />
          <InputGroup className="h-10 w-full sm:w-64 sm:max-w-xs md:h-9">
            <InputGroupInput type="search" value={query} onChange={(e) => setQuery(e.target.value)}
              placeholder={t('wn.pushSearch')} aria-label={t('wn.pushSearch')} autoComplete="off" />
            <InputGroupAddon><Search aria-hidden="true" /></InputGroupAddon>
          </InputGroup>
        </div>
        {rows.length === 0
          ? <p data-slot="wn-push-empty" className="px-1 py-2 text-sm text-muted-foreground">{t('wn.pushFilterEmpty')}</p>
          : <ItemGroup className="gap-2">{rows.map((r) => <PushRow key={r.key} row={r} ctx={ctx} />)}</ItemGroup>}
      </div>
    )
  }

  return (
    <ChannelCard ref={cardRef} channel="push" icon={BellRing} title={t('wn.pushTitle')} className={className}
      count={push.access === 'FULL' && !push.error ? `${counts.push}/${push.total}` : null} description={description}>
      <div className="flex min-w-0 flex-col gap-3">
        {push.access === 'FULL' && push.accessReason === 'TEAM_MANAGER' && (
          <p className="px-1 text-xs text-muted-foreground">{t('wn.pushAccessManager')}</p>
        )}
        <ChannelStatus channel={push.channel} settings={push.settings} />
        {body}
        {push.access !== 'NONE' && !push.error && (
          <p className="px-1 text-xs text-muted-foreground">{t('wn.pushMonitorFlag')}</p>
        )}
      </div>
    </ChannelCard>
  )
}
