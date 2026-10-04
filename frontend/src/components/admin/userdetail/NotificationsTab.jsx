import { useState } from 'react'
import { BellOff, BellRing, Siren, SlidersHorizontal, X } from 'lucide-react'
import { api } from '../../../api/client'
import { useT, useDateLocale } from '../../../i18n/index.jsx'
import { useToast } from '../../ui/Toast.jsx'
import { toUtc } from '../../../utils/localDay.js'
import { familyLabel, snoozeParts } from '../../../utils/pushPrefs.js'
import { Button } from '@/components/shadcn/button'
import TeamBadge from '../../ui/TeamBadge.jsx'
import ToneBadge, { DecisionBadge, OrgRoleBadge } from '../ToneBadge.jsx'
import { SectionCard, SectionEmpty, SectionError, SectionSkeleton } from './parts.jsx'
import { Badge } from '@/components/shadcn/badge'
import { cn } from '@/lib/utils'

/** Eskalasyon seviyesi rengi (Eskalasyon Kişileri listesiyle aynı kimlik) — metin her zaman yanında. */
const LEVEL_DOT = { WARNING: 'bg-amber-500', HIGH: 'bg-orange-600', CRITICAL: 'bg-red-700' }

function levelLabel(t, lvl) {
  if (lvl === 'WARNING') return t('ec.level.warning')
  if (lvl === 'HIGH') return t('ec.level.high')
  if (lvl === 'CRITICAL') return t('ec.level.critical')
  return lvl || '—'
}
function roleLabel(t, role) {
  if (role === 'PO') return t('ec.role.po')
  if (role === 'TECH') return t('ec.role.tech')
  if (role === 'MANAGER') return t('ec.role.manager')
  if (role === 'CLEVEL') return t('ec.role.clevel')
  return role || '—'
}

/**
 * Bildirimler — (1) push kararı: birincil takım, YÜKSEK seviye alarm için "Kim alır?" motorunun bu kişi hakkındaki
 * kararı + NEDEN (grup ve asgari seviye / karar açıklaması); yalnız global yönetici (sunucu uç kuralı, eski panelle
 * aynı kapı). (2) Kişinin eskalasyon kayıtları: takım · rol · asgari seviye · webhook · pasif.
 */
export default function NotificationsTab({ user, isAdmin, push, contacts, teamMap, onChanged }) {
  const t = useT()
  const teamName = (id) => teamMap[Number(id)] || `#${id}`
  const p = push.data

  return (
    <div data-slot="ud-notifications" className="flex min-w-0 flex-col gap-3">
      {isAdmin && (
        <SectionCard icon={BellRing} title={t('ud.secPush')} description={t('ud.pushScope')}>
          {user.team_id == null ? <p className="m-0 text-sm text-muted-foreground">{t('ud.pushNoTeam')}</p>
            : push.status === 'error' ? <SectionError title={t('ud.errPush')} error={push.error} onRetry={push.reload} />
            : push.loading ? <SectionSkeleton rows={1} />
            : p == null ? <p data-slot="ud-push-unknown" className="m-0 text-sm text-muted-foreground">{t('usr.detailPushUnknown')}</p>
            : (
              <div data-slot="ud-push" className="flex min-w-0 flex-col gap-2">
                <div className="flex flex-wrap items-center gap-2">
                  <DecisionBadge decision={p.decision} className="h-auto max-w-full whitespace-normal text-left">{t('userpush.decision.' + p.decision)}</DecisionBadge>
                  {p.group && <Badge variant="outline" className="font-normal">{t('ud.pushGroup', p.group)}</Badge>}
                  {p.min_level && <Badge variant="outline" className="font-normal">{t('ud.levelAndAbove', levelLabel(t, p.min_level))}</Badge>}
                </div>
                <p className="m-0 text-sm leading-relaxed text-muted-foreground">
                  {p.decision === 'RECIPIENT'
                    ? (p.group ? t('ud.pushWhyRecipient', p.group, levelLabel(t, p.min_level || 'HIGH')) : t('ud.pushWhyRecipientPlain'))
                    : t('ud.pushWhyNot')}
                </p>
              </div>
            )}
          {user.push_opt_out && (
            <p className="m-0 flex items-start gap-1.5 text-xs text-muted-foreground"><BellOff aria-hidden="true" className="mt-0.5 size-3.5 shrink-0" />{t('usr.pushOptOutTitle')}</p>
          )}
        </SectionCard>
      )}

      {isAdmin && <PushPrefsCard user={user} onChanged={onChanged} />}

      <SectionCard icon={Siren} title={t('usr.detailContacts')} count={contacts.data ? contacts.data.length : null}>
        {contacts.status === 'error' ? <SectionError title={t('ud.errContacts')} error={contacts.error} onRetry={contacts.reload} />
          : contacts.loading && !contacts.data ? <SectionSkeleton rows={2} />
          : !contacts.data || contacts.data.length === 0 ? <SectionEmpty icon={Siren} title={t('usr.detailNoContacts')} description={t('ud.noContactsHint')} />
          : (
            <ul data-slot="ud-contacts" className="m-0 flex list-none flex-col gap-2 p-0">
              {contacts.data.map((c) => (
                <li key={c.id} data-contact-id={c.id} data-active={c.active ? 'true' : 'false'}
                  className={cn('flex min-w-0 flex-col gap-1.5 rounded-lg border px-3 py-2.5 sm:flex-row sm:flex-wrap sm:items-center', !c.active && 'bg-muted/40')}>
                  <span className="flex min-w-0 flex-wrap items-center gap-1.5">
                    {c.team_id ? <TeamBadge teamId={c.team_id} teamName={teamName(c.team_id)} size={12} /> : <span className="text-xs text-muted-foreground">{t('ud.allTeams')}</span>}
                    <OrgRoleBadge role={c.role}>{roleLabel(t, c.role)}</OrgRoleBadge>
                  </span>
                  <span className="flex min-w-0 flex-wrap items-center gap-1.5 sm:ml-auto">
                    <Badge variant="outline" data-level={c.min_alert_level} className="gap-1.5 font-semibold">
                      <span aria-hidden="true" className={cn('size-2 rounded-full', LEVEL_DOT[c.min_alert_level] || 'bg-muted-foreground')} />
                      {t('ud.levelAndAbove', levelLabel(t, c.min_alert_level))}
                    </Badge>
                    {c.webhook_url && <ToneBadge tone="success" data-webhook={c.webhook_type}>{c.webhook_type}</ToneBadge>}
                    {!c.active && <ToneBadge tone="danger">{t('ec.inactive')}</ToneBadge>}
                  </span>
                </li>
              ))}
            </ul>
          )}
      </SectionCard>
    </div>
  )
}

/**
 * Kişisel push tercihleri (2026-10-04, onaylı öneri 4) — SALT OKUNUR: en düşük seviye, izleme türleri, push dili, etkin
 * susturma. Yönetici (global ya da kişinin takımını yöneten kapsamlı) yalnız etkin susturmayı KALDIRABİLİR (sunucu kapısı
 * kullanıcı yönetimi kapısıdır, denetim PUSH_SNOOZE_CLEAR); öteki tercihler kişinindir.
 */
function PushPrefsCard({ user, onChanged }) {
  const t = useT()
  const locale = useDateLocale()
  const toast = useToast()
  const [busy, setBusy] = useState(false)
  const until = user.push_snooze_until
  const active = !!until && new Date(toUtc(until)).getTime() > Date.now()
  const parts = active ? snoozeParts(until, locale) : null
  const level = user.push_min_level ? levelLabel(t, user.push_min_level) : null
  const fams = user.push_families ? String(user.push_families).split(',').filter(Boolean) : null
  const none = !level && !fams && user.push_lang !== 'en' && !active

  async function clear() {
    setBusy(true)
    try {
      const res = await api.admin.clearUserPushSnooze(user.id)
      if (res?.success) { toast.success(t('ud.pushPrefs.cleared')); onChanged?.() }
      else toast.error(res?.error || t('ud.pushPrefs.clearError'))
    } catch (e) {
      toast.error(e?.message || t('ud.pushPrefs.clearError'))
    } finally {
      setBusy(false)
    }
  }

  return (
    <SectionCard icon={SlidersHorizontal} title={t('ud.pushPrefs.title')}>
      <div data-slot="ud-push-prefs" className="flex min-w-0 flex-col gap-2">
        {none ? <p className="m-0 text-sm text-muted-foreground">{t('ud.pushPrefs.none')}</p> : (
          <div className="flex min-w-0 flex-wrap items-center gap-1.5">
            <Badge variant="outline" className="font-normal">{t('ud.pushPrefs.level', level || t('mypush.level.ALL'))}</Badge>
            <Badge variant="outline" className="h-auto max-w-full whitespace-normal font-normal">
              {t('ud.pushPrefs.families', fams ? fams.map((f) => familyLabel(f, t)).join(', ') : t('ud.pushPrefs.allFamilies'))}
            </Badge>
            <Badge variant="outline" className="font-normal">{t('ud.pushPrefs.lang', user.push_lang === 'en' ? t('mypush.lang.en') : t('mypush.lang.tr'))}</Badge>
          </div>
        )}
        <div className="flex min-w-0 flex-col gap-2 sm:flex-row sm:items-center sm:justify-between">
          <span data-slot="ud-push-snooze" data-active={active ? 'true' : 'false'}
            className={cn('flex min-w-0 items-start gap-1.5 text-sm', active ? 'font-semibold' : 'text-muted-foreground')}>
            {active ? <BellOff aria-hidden="true" className="mt-0.5 size-4 shrink-0 text-amber-600 dark:text-amber-400" />
              : <BellRing aria-hidden="true" className="mt-0.5 size-4 shrink-0" />}
            {active
              ? `${t('ud.pushPrefs.snoozed', parts?.date ? `${parts.date} ${parts.time}` : parts?.time || '')}${user.push_snooze_critical !== false ? ` · ${t('ud.pushPrefs.criticalStill')}` : ''}`
              : t('ud.pushPrefs.notSnoozed')}
          </span>
          {active && (
            <Button type="button" variant="outline" size="sm" className="self-start pointer-coarse:h-10 max-sm:h-10 sm:self-auto"
              disabled={busy} aria-busy={busy || undefined} onClick={clear}>
              <X aria-hidden="true" /> {t('ud.pushPrefs.clear')}
            </Button>
          )}
        </div>
      </div>
    </SectionCard>
  )
}
