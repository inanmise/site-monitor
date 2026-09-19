import { memo } from 'react'
import { ShieldAlert, Siren, Activity, Fingerprint, CalendarPlus, CalendarCheck, Link2, Wrench, Users, Check } from 'lucide-react'
import { useT } from '../i18n/index.jsx'
import { formatDate, formatDateOnly } from '../api/client'
import Sparkline from './ui/Sparkline.jsx'
import { navigateTo } from '../utils/navigate.js'

/**
 * Genel Bakış sertifika kartı — ZENGİN görünüm bloğu (2026-09-19, kullanıcı seçimi 8/8). `/api/certificates/card-extras`
 * verisiyle beslenir; kart alan adıyla eşleştirir. Kompakt görünümde hiç çizilmez (kart bugünkü hâlinde kalır).
 * Blokların hepsi tıklanabilir: sağlık → Sağlık sekmesi, alarm → Alarm Geçmişi, değişim → Onayla, plan → Planla,
 * paylaşılan → hover listesi, kontak → hover/kopya. Kart onClick'i yutulur (stopPropagation) — yoksa detay da açılır.
 */
const HF_KEYS = ['revocation', 'trust', 'sanMatch', 'chain', 'signature', 'keySize', 'intermediate', 'pinnedFingerprint', 'protocol', 'cipher', 'pfs', 'hsts', 'mixedContent']
const CRITICAL_HF = new Set(['revocation', 'trust', 'sanMatch', 'chain'])

function stop(fn) { return (e) => { e.stopPropagation(); fn?.(e) } }

function CertificateCardExtras({ cert, extra, onOpenHealth, onConfirmRenewal, onPlanRenewal, confirming = false }) {
  const t = useT()
  if (!extra) return null
  const { health, alerts, uptime, change, renewal, shared, maintenance, contacts } = extra
  const failed = (health?.failed || []).filter((k) => HF_KEYS.includes(k))
  const days = cert.days_remaining
  const showPlanCta = !renewal && days != null && days >= 0 && days <= 30 && onPlanRenewal
  const contactList = contacts ? [['app_dev', contacts.app_dev], ['iis_admin', contacts.iis_admin], ['svc_mgmt', contacts.svc_mgmt], ['waf_admin', contacts.waf_admin]].filter(([, v]) => v) : []
  const nothing = !health && !alerts && !uptime && !change && !renewal && !shared && !maintenance && !contacts
  if (nothing) return null

  return (
    <div className="ccx" onClick={(e) => e.stopPropagation()}>
      {/* Sağlık bulguları */}
      {health && (
        <div className="ccx-row" title={t('ccx.healthTip', health.ok, health.evaluated)}>
          <button type="button" className={`ccx-chip${failed.length ? (failed.some((k) => CRITICAL_HF.has(k)) ? ' ccx-chip--bad' : ' ccx-chip--warn') : ' ccx-chip--ok'}`} onClick={stop(() => onOpenHealth?.(cert.domain))}>
            <ShieldAlert size={11} />
            {failed.length === 0 ? t('ccx.healthClean', health.ok, health.evaluated) : t('ccx.healthFindings', failed.length)}
          </button>
          {failed.slice(0, 3).map((k) => (
            <button key={k} type="button" className={`ccx-mini${CRITICAL_HF.has(k) ? ' is-bad' : ''}`} onClick={stop(() => onOpenHealth?.(cert.domain))} title={t(`hlth.row.${k}.title`)}>{t(`today.hf.${k}`)}</button>
          ))}
          {failed.length > 3 && <span className="ccx-more">+{failed.length - 3}</span>}
        </div>
      )}

      {/* Açık alarm */}
      {alerts?.count > 0 && (
        <div className="ccx-row">
          <button type="button" className={`ccx-chip ccx-chip--${String(alerts.level || 'warning').toLowerCase()}${alerts.all_acked ? ' is-acked' : ''}`}
            title={(alerts.types || []).join(', ') + (alerts.all_acked ? ` · ${t('today.acked')}` : '')}
            onClick={stop(() => navigateTo('alerthistory', alerts.first_id ? { incident: alerts.first_id } : undefined))}>
            <Siren size={11} /> {t('ccx.openAlerts', alerts.count)} · {alerts.level}{alerts.all_acked ? <Check size={10} /> : null}
          </button>
        </div>
      )}

      {/* Erişilebilirlik 24 sa */}
      {uptime && (uptime.pct24 != null || uptime.last_status) && (
        <div className="ccx-row ccx-uptime" title={t('ccx.uptimeTip', uptime.checks24 ?? 0, uptime.last_at ? formatDate(uptime.last_at) : '—')}>
          <Activity size={11} className={uptime.last_status === 'up' ? 'ccx-ok' : uptime.last_status ? 'ccx-bad' : ''} />
          <span className={`ccx-pct${uptime.pct24 != null && uptime.pct24 < 99 ? (uptime.pct24 < 95 ? ' is-bad' : ' is-warn') : ''}`}>{uptime.pct24 == null ? '—' : `%${uptime.pct24}`}</span>
          <span className="ccx-muted">{t('ccx.uptime24')}</span>
          {uptime.last_ms != null && <span className="ccx-muted">· {uptime.last_ms} ms</span>}
          {Array.isArray(uptime.points) && uptime.points.some((p) => p != null) && (
            <span className="ccx-spark"><Sparkline data={uptime.points.map((p) => (p == null ? 0 : p))} width={72} height={16} color={uptime.pct24 != null && uptime.pct24 < 95 ? '#dc2626' : '#059669'} /></span>
          )}
        </div>
      )}

      {/* Sertifika değişimi / pin */}
      {change && (
        <div className="ccx-row">
          <span className={`ccx-chip ${change.mismatch ? 'ccx-chip--bad' : 'ccx-chip--warn'}`} title={change.changed_at ? t('ccx.changedAt', formatDate(change.changed_at)) : ''}>
            <Fingerprint size={11} /> {change.mismatch ? t('ccx.pinMismatch') : t('ccx.changed')}
          </span>
          {!change.mismatch && onConfirmRenewal && (
            <button type="button" className="ccx-act" disabled={confirming} onClick={stop(() => onConfirmRenewal(cert.domain))} title={t('ccx.confirmTip')}>{t('ccx.confirm')}</button>
          )}
        </div>
      )}

      {/* Yenileme planı */}
      {(renewal || showPlanCta) && (
        <div className="ccx-row">
          {renewal ? (
            <button type="button" className={`ccx-chip ${renewal.overdue ? 'ccx-chip--bad' : renewal.done ? 'ccx-chip--ok' : 'ccx-chip--info'}`}
              title={[renewal.by, renewal.note].filter(Boolean).join(' · ')} onClick={stop(() => onPlanRenewal?.(cert, renewal))}>
              {renewal.done ? <CalendarCheck size={11} /> : <CalendarPlus size={11} />}
              {renewal.overdue ? t('ccx.planOverdue', formatDateOnly(renewal.planned_at)) : renewal.done ? t('ccx.planDone', formatDateOnly(renewal.planned_at)) : t('ccx.plan', formatDateOnly(renewal.planned_at))}
              {renewal.by && <span className="ccx-muted"> · {renewal.by}</span>}
            </button>
          ) : (
            <button type="button" className="ccx-act" onClick={stop(() => onPlanRenewal?.(cert, null))}><CalendarPlus size={11} /> {t('ccx.planCta')}</button>
          )}
        </div>
      )}

      {/* Paylaşılan sertifika + SAN, bakım, kontaklar — tek satırda küçük rozetler */}
      {(shared || maintenance || contacts) && (
        <div className="ccx-row">
          {shared && shared.count > 0 && (
            <span className="ccx-mini ccx-mini--info" title={`${t('ccx.sharedTip')}\n${(shared.domains || []).join('\n')}${shared.count > (shared.domains || []).length ? `\n…` : ''}`}>
              <Link2 size={10} /> {t('ccx.shared', shared.count)}{shared.san_count > 1 ? ` · ${t('ccx.san', shared.san_count)}` : ''}
            </span>
          )}
          {shared && shared.count === 0 && shared.san_count > 1 && <span className="ccx-mini" title={t('ccx.sanTip')}>{t('ccx.san', shared.san_count)}</span>}
          {maintenance && (
            <span className={`ccx-mini ${maintenance.active ? 'ccx-mini--warn' : ''}`} title={maintenance.name || ''}>
              <Wrench size={10} /> {maintenance.active ? t('ccx.maintActive', maintenance.until ? formatDate(maintenance.until) : '—') : t('ccx.maintSoon', maintenance.next_start ? formatDate(maintenance.next_start) : '—')}
            </span>
          )}
          {contacts && (contacts.missing ? (
            <span className="ccx-mini ccx-mini--warn" title={t('ccx.contactsMissingTip')}><Users size={10} /> {t('ccx.contactsMissing')}</span>
          ) : (
            <span className="ccx-mini" title={contactList.map(([k, v]) => `${t(`ccx.contact.${k}`)}: ${v}`).join('\n')}><Users size={10} /> {t('ccx.contacts', contactList.length)}</span>
          ))}
        </div>
      )}
    </div>
  )
}

export default memo(CertificateCardExtras)
