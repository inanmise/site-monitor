import { useState } from 'react'
import { RefreshCw, SearchCheck } from 'lucide-react'
import { api } from '../../api/client'
import { useT } from '../../i18n/index.jsx'
import { useToast } from '../ui/Toast.jsx'
import { useDialog } from '../ui/Dialog.jsx'
import AlertBanner from '../ui/AlertBanner.jsx'
import TeamBadge from '../ui/TeamBadge.jsx'
import ToneBadge from './ToneBadge.jsx'
import { AdSupportBadge, LdapActionBadge, MembershipSourceBadge, MembershipSourceDetail } from './MembershipSource.jsx'
import { Button } from '@/components/shadcn/button'

const HINT = 'text-xs text-muted-foreground'
/** Uzun metinli rozet: sarsın, kutudan taşmasın. */
const WRAP = 'h-auto max-w-full whitespace-normal text-left [overflow-wrap:anywhere]'

/** "CN=Takım A,OU=…" → "Takım A" (yalnız gösterim). */
function cnOf(dn) {
  const first = String(dn || '').split(',')[0] || ''
  return first.replace(/^\s*CN\s*=\s*/i, '') || String(dn || '')
}

/**
 * Kullanıcı detayında "AD ile karşılaştır" (prod hatası 2026-09-26). Salt okunur canlı AD karşılaştırması:
 * her üyeliğin kaynağı + AD desteği + yeniden eşitlemede / girişte ne olacağı, AD'ye göre eksik üyelik,
 * takım sayılmayan gruplar ve müdür bağı (ea4 / manager nitelikleri ayrı ayrı). "AD'den yeniden eşitle"
 * onayla çalışır, sunucu denetime yazar (USER_LDAP_RESYNC). Yalnız global yönetici (sunucu 403 verir).
 */
export default function UserLdapCompare({ user, onResynced }) {
  const t = useT()
  const toast = useToast()
  const { showConfirm } = useDialog()
  const [check, setCheck] = useState(null)
  const [loading, setLoading] = useState(false)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState(null)

  async function runCheck() {
    setLoading(true)
    setError(null)
    try {
      const r = await api.admin.userLdapCheck(user.id)
      if (r?.success) setCheck(r.data || null)
      else setError(r?.error || t('mship.loadError'))
    } catch (e) {
      setError(e?.message || t('mship.loadError'))
    } finally {
      setLoading(false)
    }
  }

  async function resync() {
    const ok = await showConfirm({
      title: t('mship.resyncConfirmTitle'),
      message: t('mship.resyncConfirmMsg', user.display_name || user.username),
      confirmText: t('mship.resync'),
    })
    if (!ok) return
    setBusy(true)
    try {
      const r = await api.admin.userLdapResync(user.id)
      if (r?.success) {
        toast.success(t('mship.resyncDone'))
        onResynced?.(r.data || null)
        await runCheck()
      } else {
        toast.error(r?.error || t('mship.loadError'))
      }
    } catch (e) {
      toast.error(e?.message || t('mship.loadError'))
    } finally {
      setBusy(false)
    }
  }

  const rows = Array.isArray(check?.memberships) ? check.memberships : []
  const toAdd = Array.isArray(check?.to_add) ? check.to_add : []
  const ignored = Array.isArray(check?.ignored_groups) ? check.ignored_groups : []
  const mgr = check?.manager || null
  const none = t('mship.mgrNone')
  const adMgr = mgr ? `${mgr.ad_sicil || none}${mgr.ad_manager_name ? ` (${mgr.ad_manager_name})` : mgr.ad_sicil ? ` (${t('mship.mgrNotInApp')})` : ''}` : ''
  const dbMgr = mgr ? `${mgr.db_sicil || none}${mgr.db_manager_name ? ` (${mgr.db_manager_name})` : ''}` : ''
  const candidates = mgr?.candidates ? Object.entries(mgr.candidates).filter(([, v]) => v).map(([k, v]) => `${k}=${v}`).join(', ') : ''

  return (
    <div className="flex flex-col gap-2.5" data-testid="ldap-compare" aria-busy={loading || busy || undefined}>
      <div className="flex flex-wrap items-center gap-2">
        <Button type="button" variant="outline" size="sm" onClick={runCheck} disabled={loading || busy}>
          <SearchCheck size={14} aria-hidden="true" /> {loading ? t('mship.checking') : t('mship.check')}
        </Button>
        {check?.found && (
          <Button type="button" size="sm" onClick={resync} disabled={busy || loading}>
            <RefreshCw size={14} aria-hidden="true" /> {t('mship.resync')}
          </Button>
        )}
      </div>
      {error && <AlertBanner tone="danger" role="alert">{error}</AlertBanner>}
      {check && check.found === false && (
        <AlertBanner tone="warning">{check.reason === 'LOCAL_ACCOUNT' ? t('mship.local') : t('mship.notFound')}</AlertBanner>
      )}
      {check?.found && (
        <>
          {check.team_locked && <p className={HINT}>{t('mship.locked')}</p>}
          {rows.length > 0 && (
            <ul className="flex list-none flex-col gap-2" data-testid="ldap-compare-rows">
              {rows.map((r) => (
                <li key={r.team_id} data-team-id={r.team_id} className="flex min-w-0 flex-col gap-1 rounded-md border border-border px-2.5 py-2">
                  <span className="flex min-w-0 flex-wrap items-center gap-1.5">
                    <TeamBadge teamId={r.team_id} teamName={r.team_name || `#${r.team_id}`} size={12} />
                    <MembershipSourceBadge source={r.source} detail={r.detail} />
                    <AdSupportBadge supported={r.supported_by_ad} />
                  </span>
                  <span className="flex flex-wrap items-center gap-1.5">
                    <LdapActionBadge action={r.on_resync} label={t('mship.onResync')} />
                    <LdapActionBadge action={r.on_login} label={t('mship.onLogin')} />
                  </span>
                  <MembershipSourceDetail detail={r.detail} />
                </li>
              ))}
            </ul>
          )}
          {toAdd.length > 0 && (
            <div className="flex flex-wrap items-center gap-1.5 text-sm" data-testid="ldap-compare-add">
              <span className="font-semibold">{t('mship.toAdd')}:</span>
              {toAdd.map((d) => (
                <span key={`${d.team_name}-${d.team_id ?? 'new'}`} className="inline-flex items-center gap-1">
                  <span className="break-all">{d.team_name}</span>
                  <LdapActionBadge action={d.on_resync} />
                </span>
              ))}
            </div>
          )}
          {ignored.length > 0 && (
            <p className={`${HINT} [overflow-wrap:anywhere]`}>
              {t('mship.ignored', ignored.length)}{' '}
              {ignored.map((g) => `${cnOf(g.dn)} (${t(`mship.ignoredReason.${g.reason}`)})`).join(', ')}
            </p>
          )}
          {mgr && (
            <div className="flex flex-col gap-1 text-sm" data-testid="ldap-compare-manager">
              <span className="font-semibold">{t('mship.mgrTitle')}</span>
              <span className="[overflow-wrap:anywhere]">{t('mship.mgrAd', adMgr)}</span>
              <span className="[overflow-wrap:anywhere]">{t('mship.mgrDb', dbMgr)}</span>
              <span className="flex flex-wrap gap-1.5">
                {/* Uzun uyarı metni telefonda SARAR (rozet varsayılanı nowrap — 390 px'te pencereyi yana kaydırıyordu) */}
                {mgr.attributes_disagree && <ToneBadge tone="warning" data-flag="disagree" className={WRAP}>{t('mship.mgrDisagree', candidates)}</ToneBadge>}
                {mgr.matches_ad === false && <ToneBadge tone="warning" data-flag="mismatch" className={WRAP}>{t('mship.mgrMismatch')}</ToneBadge>}
                {mgr.db_consistent === false && <ToneBadge tone="danger" data-flag="inconsistent" className={WRAP}>{t('mship.mgrInconsistent')}</ToneBadge>}
              </span>
            </div>
          )}
        </>
      )}
    </div>
  )
}
