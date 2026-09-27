import { useEffect, useState } from 'react'
import { SearchCheck, RefreshCw } from 'lucide-react'
import { api } from '../../api/client'
import { useT } from '../../i18n/index.jsx'
import { useToast } from '../ui/Toast.jsx'
import { useDialog } from '../ui/Dialog.jsx'
import ModalShell from '../ui/ModalShell.jsx'
import AlertBanner from '../ui/AlertBanner.jsx'
import { LoadingBlock } from '../ui/Progress.jsx'
import ToneBadge from './ToneBadge.jsx'
import { AdSupportBadge, LdapActionBadge, MembershipSourceBadge, MembershipSourceDetail } from './MembershipSource.jsx'
import { Button } from '@/components/shadcn/button'

const HINT = 'text-xs text-muted-foreground'

/**
 * Takım → "AD ile üyelik denetimi" (prod hatası 2026-09-26: üyesi olmayan Kullanıcı X takımda görünüyordu).
 * Her üye için: üyeliğin KAYNAĞI (AD grubu / company / elle / taşıma / kaynak kaydı yok), AD bugün
 * destekliyor mu, yeniden eşitlemede ve girişte ne olur. Üstte takım müdürü AYRI satırda — türetilmişse
 * "takım üyesi değil" diye açıkça yazar ve hangi üyelerin ona bağlı olduğunu gösterir.
 * "Kilitsiz üyeleri AD'den eşitle" onayla çalışır; sunucu üye başına USER_LDAP_RESYNC + TEAM_LDAP_RESYNC yazar.
 * Telefonda tablo yerine kaydırmasız satır listesi (rozetler sarar).
 *
 * @param managerInfo {label, manual, reports: string[]} | null — TeamManager'ın türettiği takım müdürü
 */
export default function TeamLdapAuditModal({ team, managerInfo, onClose, onChanged }) {
  const t = useT()
  const toast = useToast()
  const { showConfirm } = useDialog()
  const [state, setState] = useState({ loading: true, data: null, error: null })
  const [busy, setBusy] = useState(false)
  const [nonce, setNonce] = useState(0)

  useEffect(() => {
    let alive = true
    setState((s) => ({ ...s, loading: true, error: null }))
    Promise.resolve(api.admin.teamLdapCheck(team.id))
      .then((r) => {
        if (!alive) return
        if (r?.success) setState({ loading: false, data: r.data || null, error: null })
        else setState({ loading: false, data: null, error: r?.error || t('mship.loadError') })
      })
      .catch((e) => { if (alive) setState({ loading: false, data: null, error: e?.message || t('mship.loadError') }) })
    return () => { alive = false }
  }, [team.id, nonce]) // eslint-disable-line react-hooks/exhaustive-deps

  async function resyncTeam() {
    const ok = await showConfirm({
      title: t('mship.resyncConfirmTitle'),
      message: t('tla.resyncConfirm', team.name),
      confirmText: t('tla.resync'),
      variant: 'danger',
    })
    if (!ok) return
    setBusy(true)
    try {
      const r = await api.admin.teamLdapResync(team.id)
      if (r?.success) {
        const d = r.data || {}
        const msg = t('tla.resyncDone', d.ok ?? 0, d.removed_from_team ?? 0, d.failed ?? 0)
        if ((d.failed ?? 0) > 0 && toast.warning) toast.warning(msg)
        else toast.success(msg)
        onChanged?.()
        setNonce((n) => n + 1)
      } else {
        toast.error(r?.error || t('mship.loadError'))
      }
    } catch (e) {
      toast.error(e?.message || t('mship.loadError'))
    } finally {
      setBusy(false)
    }
  }

  const members = Array.isArray(state.data?.members) ? state.data.members : []
  return (
    <ModalShell open onClose={onClose} title={t('tla.title', team.name)} icon={SearchCheck} size="lg" scrollBody busy={busy}
      footer={(
        <>
          <Button variant="secondary" onClick={onClose} disabled={busy}>{t('team.close')}</Button>
          <Button onClick={resyncTeam} disabled={busy || state.loading || members.length === 0} aria-busy={busy || undefined}>
            <RefreshCw size={14} aria-hidden="true" /> {t('tla.resync')}
          </Button>
        </>
      )}>
      <div className="flex flex-col gap-3" data-testid="team-ldap-audit">
        <p className={HINT}>{t('tla.intro')}</p>
        <div className="flex flex-col gap-0.5 rounded-md border border-border px-3 py-2 text-sm" data-testid="tla-manager">
          {managerInfo?.label
            ? <span className="[overflow-wrap:anywhere]">{t(managerInfo.manual ? 'tla.managerManual' : 'tla.managerDerived', managerInfo.label)}</span>
            : <span className="text-muted-foreground">{t('tla.managerNone')}</span>}
          {managerInfo?.label && !managerInfo.manual && managerInfo.reports?.length > 0 && (
            <span className={`${HINT} [overflow-wrap:anywhere]`}>{t('tla.managerReports', managerInfo.reports.join(', '))}</span>
          )}
        </div>
        {state.error && <AlertBanner tone="danger" role="alert">{state.error}</AlertBanner>}
        {state.loading ? <LoadingBlock label={t('mship.checking')} size={16} /> : members.length === 0 && !state.error ? (
          <p className="text-sm text-muted-foreground">{t('tla.empty')}</p>
        ) : (
          <ul className="flex list-none flex-col gap-2" data-testid="tla-members">
            {members.map((m) => (
              <li key={m.user_id} data-user-id={m.user_id} className="flex min-w-0 flex-col gap-1 rounded-md border border-border px-2.5 py-2">
                <span className="flex min-w-0 flex-wrap items-center gap-1.5">
                  <strong className="min-w-0 truncate" title={m.username}>{m.display_name || m.username}</strong>
                  {m.primary && <span className={HINT}>{t('tla.primary')}</span>}
                  {!m.active && <ToneBadge tone="muted">{t('tla.inactive')}</ToneBadge>}
                  {m.auth_source !== 'LDAP' && <ToneBadge tone="muted">{t('tla.local')}</ToneBadge>}
                  {m.team_locked && <ToneBadge tone="muted">{t('tla.locked')}</ToneBadge>}
                  <MembershipSourceBadge source={m.source} detail={m.detail} />
                  {m.ldap_found === false && <ToneBadge tone="warning">{t('tla.notInAd')}</ToneBadge>}
                  <AdSupportBadge supported={m.supported_by_ad} />
                </span>
                <span className="flex flex-wrap items-center gap-1.5">
                  <LdapActionBadge action={m.on_resync} label={t('mship.onResync')} />
                  <LdapActionBadge action={m.on_login} label={t('mship.onLogin')} />
                </span>
                <MembershipSourceDetail detail={m.detail} />
              </li>
            ))}
          </ul>
        )}
        {state.data?.truncated && <p className={HINT}>{t('tla.truncated', state.data.checked)}</p>}
      </div>
    </ModalShell>
  )
}
