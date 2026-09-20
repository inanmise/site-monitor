import { useState, useEffect } from 'react'
import { Users, Mail, Webhook, BellRing, ChevronDown, ChevronUp } from 'lucide-react'
import { api } from '../../api/client'
import { useT } from '../../i18n/index.jsx'
import SearchableSelect from '../ui/SearchableSelect.jsx'
import SegmentedControl from '../ui/SegmentedControl.jsx'
import AlertBanner from '../ui/AlertBanner.jsx'
import TeamBadge from '../ui/TeamBadge.jsx'
import { LoadingBlock } from '../ui/Progress.jsx'

const LEVELS = ['WARNING', 'HIGH', 'CRITICAL']

/**
 * "Kim bilgilendirilir?" simülatörü (2026-09-20) — takım + seviye (+ izleme grubu) seçilir, alarm gitmeden
 * alıcı zinciri görünür: e-posta (izleme grubu → takım varsayılan grubu → takım adresi), eskalasyon
 * kişileri (seviye + takım/global düşüşü), kişi webhook'ları ve push alıcıları (yalnız global admin).
 * Sunucu gerçek gönderimle AYNI kararları kullanır ({@code EscalationService.simulateRecipients}).
 */
export default function RecipientSimulator({ teams = [], isAdmin, defaultTeamId = '' }) {
  const t = useT()
  const [open, setOpen] = useState(false)
  const [teamId, setTeamId] = useState(defaultTeamId ? String(defaultTeamId) : '')
  const [level, setLevel] = useState('HIGH')
  const [kind, setKind] = useState('CERT')
  const [groups, setGroups] = useState([])
  const [groupId, setGroupId] = useState('')
  const [data, setData] = useState(null)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState(null)

  // Takım değişince o takımın bildirim grupları (izleme grubu simülasyonu için).
  useEffect(() => {
    setGroupId(''); setGroups([])
    if (!teamId || !open) return
    let alive = true
    Promise.resolve(api.notificationGroups?.list?.(Number(teamId)))
      .then(res => { if (alive && res?.success) setGroups(Array.isArray(res.data) ? res.data : (res.data?.items || [])) })
      .catch(() => {})
    return () => { alive = false }
  }, [teamId, open])

  useEffect(() => {
    if (!open || !teamId) { setData(null); return }
    let alive = true
    setLoading(true); setError(null)
    api.admin.simulateRecipients({ teamId: Number(teamId), level, kind, groupId: groupId ? Number(groupId) : null })
      .then(res => {
        if (!alive) return
        if (res?.success) setData(res.data)
        else setError(res?.error || t('sim.error'))
      })
      .catch(e => { if (alive) setError(e?.message || t('sim.error')) })
      .finally(() => { if (alive) setLoading(false) })
    return () => { alive = false }
  }, [open, teamId, level, kind, groupId])   // eslint-disable-line react-hooks/exhaustive-deps

  const teamOptions = [{ value: '', label: t('sim.pickTeam') }, ...teams.map(tm => ({ value: String(tm.id), label: tm.name }))]

  return (
    <div className="admin-section sim-section" data-testid="recipient-sim">
      <div className="admin-section-header">
        <div>
          <h3><Users size={16} /> {t('sim.title')}</h3>
          <p className="section-desc">{t('sim.desc')}</p>
        </div>
        <button className="btn btn-secondary" onClick={() => setOpen(o => !o)} aria-expanded={open}>
          {open ? <ChevronUp size={14} /> : <ChevronDown size={14} />} {open ? t('sim.hide') : t('sim.show')}
        </button>
      </div>

      {open && (
        <>
          <div className="audit-filters sim-controls">
            <SearchableSelect value={teamId} onChange={setTeamId} placeholder={t('sim.pickTeam')} ariaLabel={t('sim.team')}
              searchThreshold={4} options={teamOptions} />
            <SegmentedControl value={level} onChange={setLevel} ariaLabel={t('sim.level')}
              options={LEVELS.map(l => ({ value: l, label: t(`sim.level.${l}`) }))} />
            <SegmentedControl value={kind} onChange={setKind} ariaLabel={t('sim.kind')}
              options={[{ value: 'CERT', label: t('sim.kindCert') }, { value: 'MONITOR', label: t('sim.kindMonitor') }]} />
            {groups.length > 0 && (
              <SearchableSelect value={groupId} onChange={setGroupId} placeholder={t('sim.groupDefault')} ariaLabel={t('sim.group')}
                options={[{ value: '', label: t('sim.groupDefault') }, ...groups.map(g => ({ value: String(g.id), label: g.name }))]} />
            )}
          </div>

          {!teamId && <p className="field-hint">{t('sim.pickTeamHint')}</p>}
          {loading && <LoadingBlock label={t('sim.loading')} size={16} />}
          {error && <AlertBanner tone="danger" role="alert">{error}</AlertBanner>}
          {data && !loading && <SimResult data={data} t={t} isAdmin={isAdmin} />}
        </>
      )}
    </div>
  )
}

function SimResult({ data, t, isAdmin }) {
  const emails = data.team_emails || []
  const contacts = data.contacts || []
  const webhooks = data.webhooks || []
  const push = Array.isArray(data.push) ? data.push : null
  const pushRecipients = push ? push.filter(p => p.decision === 'RECIPIENT') : []
  const nothing = emails.length === 0 && contacts.length === 0 && webhooks.length === 0 && pushRecipients.length === 0
  return (
    <div className="sim-result">
      <div className="sim-summary">
        <span className="sim-chip"><Mail size={13} /> {t('sim.sumEmail', data.email_total ?? 0)}</span>
        <span className="sim-chip"><Webhook size={13} /> {t('sim.sumWebhook', webhooks.length)}</span>
        {push && <span className="sim-chip"><BellRing size={13} /> {t('sim.sumPush', pushRecipients.length, push.length)}</span>}
        {data.team_name && <TeamBadge teamId={data.team_id} teamName={data.team_name} size={12} />}
      </div>
      {nothing && <AlertBanner tone="warning">{t('sim.nobody')}</AlertBanner>}
      {!data.managers_included && <p className="field-hint">{t('sim.warningOnlyTeam')}</p>}
      {data.contacts_fallback_global && <AlertBanner tone="warning">{t('sim.fallbackGlobal')}</AlertBanner>}

      <div className="sim-grid">
        <div className="sim-block">
          <h4><Mail size={14} /> {t('sim.emailTitle')}</h4>
          {emails.length === 0 && contacts.length === 0 ? <p className="field-hint">{t('sim.none')}</p> : (
            <ul className="sim-list">
              {emails.map(e => (
                <li key={`t-${e.email}`}><span className="audit-mono">{e.email}</span> <span className="sim-src">{e.source}</span></li>
              ))}
              {contacts.map(c => (
                <li key={`c-${c.id}`} className={c.email_duplicate ? 'is-dup' : ''}>
                  <span className="audit-mono">{c.email || '—'}</span>
                  <span className="sim-src">{c.name} · {t(`ec.role.${String(c.role || '').toLowerCase()}`)} · ≥ {t(`sim.level.${c.min_level}`)}</span>
                  {c.email_duplicate && <span className="sim-dup">{t('sim.dup')}</span>}
                </li>
              ))}
            </ul>
          )}
        </div>
        <div className="sim-block">
          <h4><Webhook size={14} /> {t('sim.webhookTitle')}</h4>
          {webhooks.length === 0 ? <p className="field-hint">{t('sim.none')}</p> : (
            <ul className="sim-list">
              {webhooks.map(w => <li key={w.id}><span className="badge badge-ok">{w.type}</span> <span className="audit-mono">{w.target}</span> <span className="sim-src">{w.name}</span></li>)}
            </ul>
          )}
        </div>
        {isAdmin && (
          <div className="sim-block sim-block--wide">
            <h4><BellRing size={14} /> {t('sim.pushTitle')}</h4>
            {!push ? <p className="field-hint">{data.push_error || t('sim.none')}</p> : push.length === 0 ? <p className="field-hint">{t('sim.none')}</p> : (
              /* 2026-09-21 (kullanıcı bildirimi): satır satır serbest metin yerine sütunlu tablo — kişi / grup / karar hizalı */
              <table className="sim-table">
                <thead><tr><th>{t('sim.pushColPerson')}</th><th>{t('sim.pushColGroup')}</th><th>{t('sim.pushColDecision')}</th></tr></thead>
                <tbody>
                  {push.map((m, i) => (
                    <tr key={(m.username || '') + i} className={m.decision === 'RECIPIENT' ? '' : 'is-skipped'}>
                      <td><strong>{m.display_name || m.displayName || m.username}</strong>{m.username && (m.display_name || m.displayName) && <span className="sim-src sim-sub audit-mono">{m.username}</span>}</td>
                      <td className="sim-src">{m.group || '—'}{m.min_level ? ` · ≥ ${m.min_level}` : ''}</td>
                      <td><span className={`up-decision up-decision--${m.decision}`}>{t('userpush.decision.' + m.decision)}</span></td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
          </div>
        )}
      </div>
    </div>
  )
}
