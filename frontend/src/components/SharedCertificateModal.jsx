import { useEffect, useState } from 'react'
import { Link2, ShieldCheck, ExternalLink, Layers } from 'lucide-react'
import { api, formatDate, formatDateSec } from '../api/client'
import { useT } from '../i18n/index.jsx'
import ModalShell from './ui/ModalShell.jsx'
import { LoadingBlock } from './ui/Progress.jsx'
import AlertBanner from './ui/AlertBanner.jsx'
import StatusBlock from './ui/StatusBlock.jsx'
import TeamBadge from './ui/TeamBadge.jsx'
import CopyableRef from './ui/CopyableRef.jsx'
import { navigateTo } from '../utils/navigate.js'

/**
 * Paylaşılan sertifika penceresi (2026-09-22, kullanıcı isteği): kart üzerindeki "N alan aynı sertifikayı paylaşıyor"
 * çipi tıklanınca açılır. Cevapladığı soru: "bu sertifikayı yenilersem BAŞKA nereleri etkiler, kimi haberdar etmeliyim,
 * nereye kurulacak?" — bu yüzden her eş için takım, platform, tier, port, durum/kalan gün, son kontrol gösterilir.
 * Alan adına tıklanınca o alanın kartına/envanterine gidilir. Kapsam dışı eşler sayıyla belirtilir (varlık gizlenmez).
 */
export default function SharedCertificateModal({ domain, onClose, onSelectDomain }) {
  const t = useT()
  const [state, setState] = useState({ loading: true, data: null, error: null })

  useEffect(() => {
    let alive = true
    setState({ loading: true, data: null, error: null })
    api.getSharedCertificate(domain)
      .then((r) => { if (alive) setState({ loading: false, data: r?.success ? r.data : null, error: r?.success ? null : (r?.error || t('shc.error')) }) })
      .catch((e) => { if (alive) setState({ loading: false, data: null, error: e?.message || t('shc.error') }) })
    return () => { alive = false }
  }, [domain]) // eslint-disable-line react-hooks/exhaustive-deps

  const d = state.data
  const peers = d?.peers || []
  const others = peers.filter((p) => !p.self)
  const cls = (p) => p.days_remaining == null ? '' : p.days_remaining < 0 ? 'shc-crit' : p.days_remaining <= 7 ? 'shc-crit' : p.days_remaining <= 30 ? 'shc-warn' : ''

  return (
    <ModalShell open onClose={onClose} title={t('shc.title')} icon={Link2} size="lg" scrollBody>
      {state.loading ? <LoadingBlock label={t('modal.loading')} fullWidth /> : state.error ? (
        <AlertBanner tone="danger" role="alert">{state.error}</AlertBanner>
      ) : (<>
        <p className="shc-lead">{t('shc.lead', others.length, domain)}</p>

        {/* Sertifikanın kendisi — hangi sertifikadan söz ediyoruz */}
        <dl className="shc-cert">
          <dt>{t('shc.subject')}</dt><dd>{d?.subject || '—'}</dd>
          <dt>{t('shc.issuer')}</dt><dd><ShieldCheck size={12} /> {d?.issuer || '—'}</dd>
          <dt>{t('shc.expiry')}</dt>
          <dd>{d?.not_after ? formatDate(d.not_after) : '—'}{d?.days_remaining != null && <span className="inv-dim"> · {t('shc.daysLeft', d.days_remaining)}</span>}</dd>
          <dt>{t('shc.fingerprint')}</dt>
          <dd>{d?.fingerprint ? <CopyableRef value={d.fingerprint} copyLabel={t('shc.copyFp')} copiedLabel={t('err.copied')} /> : '—'}</dd>
          {Array.isArray(d?.san) && d.san.length > 0 && (<>
            <dt>{t('shc.san', d.san.length)}</dt>
            <dd className="shc-san">{d.san.map((s) => <span key={s} className="shc-san-chip">{s}</span>)}</dd>
          </>)}
        </dl>

        <div className="shc-peers-head">{t('shc.peersTitle', peers.length)}{d?.hidden > 0 && <span className="inv-dim"> · {t('shc.hidden', d.hidden)}</span>}</div>
        {peers.length === 0 ? <StatusBlock tone="neutral" icon={Link2} title={t('shc.none')} /> : (
          <table className="admin-table shc-table" data-testid="shc-table">
            <thead>
              <tr>
                <th>{t('shc.colDomain')}</th><th>{t('shc.colTeam')}</th><th>{t('shc.colPlatform')}</th>
                <th>{t('shc.colTier')}</th><th>{t('shc.colStatus')}</th><th>{t('shc.colChecked')}</th>
              </tr>
            </thead>
            <tbody>
              {peers.map((p) => (
                <tr key={p.domain} className={p.self ? 'shc-self' : ''}>
                  <td>
                    <button type="button" className="inv-domain" onClick={() => { onSelectDomain?.(p.domain); onClose?.() }} title={t('shc.openDomain')}>
                      {p.domain} <ExternalLink size={11} />
                    </button>
                    {p.self && <span className="ccx-chip ccx-chip--info shc-self-chip">{t('shc.thisDomain')}</span>}
                    {!p.in_inventory && <span className="ccx-chip shc-ext-chip" title={t('shc.notInInventoryTip')}>{t('shc.notInInventory')}</span>}
                    {p.port && p.port !== 443 && <span className="inv-dim"> :{p.port}</span>}
                  </td>
                  <td>{p.team_name ? <TeamBadge teamId={p.team_id} teamName={p.team_name} /> : <span className="inv-dim">—</span>}</td>
                  <td>{p.platform ? <span className="inv-platform" title={p.platform_detail || ''}><Layers size={11} /> {p.platform}</span> : <span className="inv-dim">—</span>}</td>
                  <td>{p.tier ? `T${p.tier}` : <span className="inv-dim">—</span>}</td>
                  <td className={cls(p)}>
                    {p.days_remaining == null ? '—' : p.days_remaining < 0 ? t('shc.expiredAgo', Math.abs(p.days_remaining)) : t('shc.daysLeft', p.days_remaining)}
                    {p.not_after && <span className="inv-dim"> · {formatDate(p.not_after)}</span>}
                  </td>
                  <td className="inv-dim">{p.checked_at ? formatDateSec(p.checked_at) : '—'}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
        <p className="field-hint shc-foot">{t('shc.foot')}</p>
        <div className="shc-actions">
          <button type="button" className="btn btn-sm btn-secondary" onClick={() => { navigateTo('renewal'); onClose?.() }}>{t('shc.toRenewal')}</button>
        </div>
      </>)}
    </ModalShell>
  )
}
