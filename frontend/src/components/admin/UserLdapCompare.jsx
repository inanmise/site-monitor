import { useState } from 'react'
import { Lock, LockOpen, RefreshCw, SearchCheck } from 'lucide-react'
import { api } from '../../api/client'
import { useT } from '../../i18n/index.jsx'
import { useIsMobile } from '../../hooks/use-mobile.js'
import { useToast } from '../ui/Toast.jsx'
import { useDialog } from '../ui/Dialog.jsx'
import AlertBanner from '../ui/AlertBanner.jsx'
import TeamBadge from '../ui/TeamBadge.jsx'
import ToneBadge from './ToneBadge.jsx'
import { AdSupportBadge, LdapActionBadge, MembershipSourceBadge, MembershipSourceDetail } from './MembershipSource.jsx'
import { FIELD_LABEL_KEYS } from './userdetail/userDetailModel.js'
import { Button } from '@/components/shadcn/button'
import { Badge } from '@/components/shadcn/badge'
import { Card } from '@/components/shadcn/card'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/shadcn/table'

/** Alan satırı durumu: kilitli > farklı > aynı. */
function fieldStatus(f) {
  return f.locked ? 'locked' : f.differs ? 'differs' : 'same'
}

function FieldStatusBadge({ status }) {
  const t = useT()
  if (status === 'locked') {
    return <Badge variant="warning" data-field-status="locked" className="gap-1"><Lock aria-hidden="true" className="size-3" />{t('mship.f.locked')}</Badge>
  }
  if (status === 'differs') return <Badge variant="secondary" data-field-status="differs">{t('mship.f.differs')}</Badge>
  return <Badge variant="outline" data-field-status="same" className="text-muted-foreground">{t('mship.f.same')}</Badge>
}

/** Değer; boşsa soluk "boş". */
function FieldValue({ value }) {
  const t = useT()
  if (value == null || value === '') return <span className="text-muted-foreground italic">{t('mship.f.empty')}</span>
  return <span className="[overflow-wrap:anywhere]">{String(value)}</span>
}

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
 *
 * <p>Alan karşılaştırması (2026-09-30): `check.fields` — 11 AD alanı (AD değeri / uygulama değeri / kilit / fark),
 * ≥ 768 px tablo, telefonda kart listesi (tek varyant çizilir — `useIsMobile`). Kilitli alanda "AD'ye geri ver"
 * yalnız `globalAdmin`; başarıda karşılaştırma yenilenir. `fields` yoksa (eski yanıt) blok çizilmez.
 */
export default function UserLdapCompare({ user, onResynced, onFieldUnlocked, globalAdmin = false }) {
  const t = useT()
  const toast = useToast()
  const { showConfirm } = useDialog()
  const mobile = useIsMobile()
  const [check, setCheck] = useState(null)
  const [loading, setLoading] = useState(false)
  const [busy, setBusy] = useState(false)
  const [unlocking, setUnlocking] = useState(null)
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

  async function unlockField(key) {
    if (unlocking) return
    setUnlocking(key)
    try {
      const r = await api.admin.unlockUserField(user.id, key)
      if (r?.success) {
        toast.success(t('usr.fieldUnlocked'))
        onFieldUnlocked?.()
        await runCheck()
      } else {
        toast.error(r?.error || t('mship.loadError'))
      }
    } catch (e) {
      toast.error(e?.message || t('mship.loadError'))
    } finally {
      setUnlocking(null)
    }
  }

  const rows = Array.isArray(check?.memberships) ? check.memberships : []
  // Yalnız bilinen anahtarlar (etiketi olan); eski yanıtta `fields` yok → boş → blok çizilmez.
  const fields = Array.isArray(check?.fields) ? check.fields.filter((f) => f && FIELD_LABEL_KEYS[f.key]) : []
  const unlockButton = (f) => (globalAdmin && f.locked ? (
    <Button type="button" variant="outline" size="sm" className="h-10 md:h-8" disabled={loading || busy || unlocking != null}
      aria-busy={unlocking === f.key || undefined} onClick={() => unlockField(f.key)}>
      <LockOpen aria-hidden="true" />{t('usr.fieldUnlock')}
    </Button>
  ) : null)
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
          {fields.length > 0 && (
            <div className="flex min-w-0 flex-col gap-1.5" data-testid="ldap-compare-fields" data-layout={mobile ? 'cards' : 'table'}>
              <span className="text-sm font-semibold">{t('mship.f.title')}</span>
              <p className={`m-0 ${HINT}`}>{t('mship.f.intro')}</p>
              {mobile ? (
                <ul className="m-0 flex list-none flex-col gap-2 p-0">
                  {fields.map((f) => {
                    const status = fieldStatus(f)
                    return (
                      <li key={f.key} data-field={f.key} data-field-status={status}>
                        <Card className="gap-2 px-3 py-2.5 shadow-none">
                          <div className="flex min-w-0 flex-wrap items-center justify-between gap-1.5">
                            <span className="text-sm font-semibold">{t(FIELD_LABEL_KEYS[f.key])}</span>
                            <FieldStatusBadge status={status} />
                          </div>
                          <dl className="m-0 grid grid-cols-[auto_minmax(0,1fr)] gap-x-3 gap-y-1 text-sm">
                            <dt className="text-xs font-semibold text-muted-foreground uppercase">{t('mship.f.colAd')}</dt>
                            <dd className="m-0 min-w-0"><FieldValue value={f.ad} /></dd>
                            <dt className="text-xs font-semibold text-muted-foreground uppercase">{t('mship.f.colLocal')}</dt>
                            <dd className="m-0 min-w-0"><FieldValue value={f.local} /></dd>
                          </dl>
                          {unlockButton(f)}
                        </Card>
                      </li>
                    )
                  })}
                </ul>
              ) : (
                <Table>
                  <TableHeader>
                    <TableRow>
                      <TableHead>{t('mship.f.colField')}</TableHead>
                      <TableHead>{t('mship.f.colAd')}</TableHead>
                      <TableHead>{t('mship.f.colLocal')}</TableHead>
                      <TableHead>{t('mship.f.colStatus')}</TableHead>
                    </TableRow>
                  </TableHeader>
                  <TableBody>
                    {fields.map((f) => {
                      const status = fieldStatus(f)
                      return (
                        <TableRow key={f.key} data-field={f.key} data-field-status={status}>
                          <TableCell className="font-medium whitespace-normal">{t(FIELD_LABEL_KEYS[f.key])}</TableCell>
                          <TableCell className="whitespace-normal"><FieldValue value={f.ad} /></TableCell>
                          <TableCell className="whitespace-normal"><FieldValue value={f.local} /></TableCell>
                          <TableCell className="whitespace-normal">
                            <span className="flex flex-wrap items-center gap-1.5">
                              <FieldStatusBadge status={status} />
                              {unlockButton(f)}
                            </span>
                          </TableCell>
                        </TableRow>
                      )
                    })}
                  </TableBody>
                </Table>
              )}
            </div>
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
