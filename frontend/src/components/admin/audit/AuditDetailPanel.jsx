import { useEffect, useState } from 'react'
import { ChevronDown, ChevronRight, History, X } from 'lucide-react'
import { api, formatDate } from '../../../api/client'
import { useT } from '../../../i18n/index.jsx'
import AlertBanner from '../../ui/AlertBanner.jsx'
import CopyButton from '../../ui/CopyButton.jsx'
import UserBadge from '../../ui/UserBadge.jsx'
import { eventClass, eventLabel, parseDetail, fmtValue, actionSentence, OUTCOME_KEYS } from './auditFormat.js'

/**
 * Seçili denetim satırının TAM ayrıntısı — geniş ekranda yan panelde, dar ekranda modal
 * içinde AYNI bileşen çizilir (içerik iki yerde ikiye ayrılmasın).
 *
 * <p><b>Burası veri kaybının kapandığı yer.</b> Sunucu satırın 20'den fazla alanını
 * döndürüyordu; tablo bunların yalnız 10'unu gösteriyor, kalanlar hiçbir yerde
 * görünmüyordu: `seq` ve hash zinciri (kaydın kurcalanmadığının kanıtı), `correlation_id`
 * (aynı işlemin diğer olayları), `ip_reverse_host` (giriş anında çözülmüş PTR),
 * `actor_team_id`, tam `user_agent` ve düz metin `detail`.
 *
 * <p>Kapsam ({@code scope}) yönetici/self ayrımını taşır: self kullanıcının erişemeyeceği
 * uçlara (korelasyon, aktör geçmişi) hiç DOKUNULMAZ — render edilseydi kalıcı bir 403
 * yüzeyi olurdu.
 */
export default function AuditDetailPanel({ row, scope = 'admin', onClose, onOpenTimeline, onDrill }) {
  const t = useT()
  const [extra, setExtra] = useState(null)       // { correlated, recent } | null
  const [extraError, setExtraError] = useState(false)
  const [showRaw, setShowRaw] = useState(false)
  const [showChain, setShowChain] = useState(false)

  const isAdmin = scope === 'admin'

  useEffect(() => {
    if (!row || !isAdmin) { setExtra(null); return }
    let alive = true
    setExtra(null)
    setExtraError(false)

    const load = {}
    const calls = []
    if (row.correlation_id) {
      calls.push(api.admin.getAuditCorrelated(row.correlation_id)
        .then(r => { if (r?.success) load.correlated = r.data }))
    }
    if (row.actor_id) {
      calls.push(api.admin.getAuditActorHistory(row.actor_id, 6)
        .then(r => { if (r?.success) load.recent = r.data }))
    }
    Promise.allSettled(calls).then(results => {
      if (!alive) return
      // Sessiz `.catch(() => {})` yerine GÖRÜNÜR hata: bu bölümün neden boş olduğu belli olsun.
      if (results.some(r => r.status === 'rejected')) setExtraError(true)
      setExtra(load)
    })
    return () => { alive = false }
  }, [row, isAdmin])

  if (!row) return null

  const { changes, detailObj, detailText } = parseDetail(row)
  const outcomeKey = OUTCOME_KEYS[row.outcome]

  return (
    <div className="aud-detail-body">
      <div className="aud-detail-head">
        <div className="aud-detail-title">
          <span className={`audit-event-badge ${eventClass(row.event_type)}`} title={row.event_type}>
            {eventLabel(row.event_type, t)}
          </span>
          <span className={`audit-outcome-badge ${row.outcome?.toLowerCase() || ''}`}>
            {outcomeKey ? t(outcomeKey) : (row.outcome || '—')}
          </span>
        </div>
        {onClose && (
          <button className="aud-detail-close" onClick={onClose} aria-label={t('audit.close')}>
            <X size={16} />
          </button>
        )}
      </div>

      <div className="audit-sentence">{actionSentence(row, t, changes)}</div>
      {row.failure_reason && (
        <AlertBanner tone="warning" className="aud-detail-reason">{row.failure_reason}</AlertBanner>
      )}

      {/* ── Kim / nereden / neye ─────────────────────────────────────── */}
      <div className="aud-section-title">{t('audit.sectionWho')}</div>
      <dl className="aud-kv">
        <dt>{t('audit.colTime')}</dt>
        <dd className="audit-mono">{formatDate(row.event_time)}</dd>

        <dt>{t('audit.colActor')}</dt>
        <dd>
          {row.actor ? <UserBadge username={row.actor} inline size="sm" /> : t('audit.systemActor')}
          {row.actor_role && <span className="audit-sub"> · {row.actor_role}</span>}
        </dd>

        <dt>{t('audit.colIp')}</dt>
        <dd className="audit-mono">
          {row.ip_address || '—'}
          {row.ip_address && <CopyButton value={row.ip_address} className="btn btn-sm aud-copy" size={12} />}
          {row.ip_reverse_host && <div className="audit-sub">{row.ip_reverse_host}</div>}
          {(row.ip_country || row.ip_city) && (
            <div className="audit-sub">{[row.ip_country, row.ip_city].filter(Boolean).join(', ')}</div>
          )}
          {row.ip_org && <div className="audit-sub">{row.ip_org}</div>}
        </dd>

        <dt>{t('audit.colBrowser')}</dt>
        <dd>
          {row.ua_summary || '—'}
          {row.user_agent && <div className="audit-sub aud-ua">{row.user_agent}</div>}
        </dd>

        {(row.resource_type || row.resource_id) && (
          <>
            <dt>{t('audit.colResource')}</dt>
            <dd>
              <button className="audit-link" onClick={() => onDrill?.(row.resource_type, row.resource_id)}
                title={t('audit.resourceHistory')}>
                {row.resource_type}{row.resource_id ? ':' + row.resource_id : ''}
              </button>
              {row.resource_id && onOpenTimeline && (
                <button className="audit-timeline-btn" title={t('audit.resourceTimeline')}
                  onClick={() => onOpenTimeline(row.resource_type, row.resource_id)}>
                  <History size={13} />
                </button>
              )}
            </dd>
          </>
        )}

        {row.correlation_id && (
          <>
            <dt>{t('audit.correlationId')}</dt>
            <dd className="audit-mono">
              {row.correlation_id}
              <CopyButton value={row.correlation_id} className="btn btn-sm aud-copy" size={12} />
            </dd>
          </>
        )}
      </dl>

      {/* ── Değişiklikler ─────────────────────────────────────────────── */}
      {changes && (
        <>
          <div className="aud-section-title">{t('audit.sectionChanges')} <span className="audit-sub">({changes.length})</span></div>
          <table className="audit-diff-table">
            <thead>
              <tr><th>{t('audit.diffField')}</th><th>{t('audit.diffFrom')}</th><th>{t('audit.diffTo')}</th></tr>
            </thead>
            <tbody>
              {changes.map(([field, change]) => (
                <tr key={field}>
                  <td className="audit-diff-field">{field}</td>
                  <td className="audit-diff-from">{fmtValue(change?.from)}</td>
                  <td className="audit-diff-to">{fmtValue(change?.to)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </>
      )}

      {/* ── Ayrıntı (JSON ya da düz metin) ────────────────────────────── */}
      {(detailObj || detailText) && (
        <>
          <div className="aud-section-title">{t('audit.sectionDetail')}</div>
          {detailObj && (
            <dl className="aud-kv">
              {Object.entries(detailObj).map(([k, v]) => (
                <div key={k} className="aud-kv-row">
                  <dt>{k}</dt>
                  <dd className="audit-mono">{fmtValue(v)}</dd>
                </div>
              ))}
            </dl>
          )}
          {/* Düz metin ayrıntı: eski kayıtlar JSON değil. Bugüne kadar HİÇ gösterilmiyordu. */}
          {detailText && <pre className="audit-detail-text">{detailText}</pre>}
        </>
      )}

      {/* ── Bütünlük (zincir) — katlanır ──────────────────────────────── */}
      <button className="aud-disclosure" onClick={() => setShowChain(v => !v)} aria-expanded={showChain}>
        {showChain ? <ChevronDown size={14} /> : <ChevronRight size={14} />}
        {t('audit.sectionChain')}
      </button>
      {showChain && (
        <dl className="aud-kv aud-kv--mono">
          <dt>seq</dt><dd className="audit-mono">{row.seq ?? '—'}</dd>
          <dt>row_hash</dt><dd className="audit-mono aud-hash">{row.row_hash || '—'}</dd>
          <dt>prev_hash</dt><dd className="audit-mono aud-hash">{row.prev_hash || '—'}</dd>
        </dl>
      )}

      {/* ── İlişkili olaylar / aktörün son eylemleri (yalnız yönetici kapsamı) ── */}
      {isAdmin && extraError && (
        <AlertBanner tone="warning">{t('audit.relatedError')}</AlertBanner>
      )}
      {isAdmin && extra?.correlated?.length > 1 && (
        <RelatedList title={`${t('audit.relatedEvents')} (${extra.correlated.length})`} rows={extra.correlated} t={t} />
      )}
      {isAdmin && extra?.recent?.length > 0 && (
        <RelatedList title={t('audit.actorRecent')} rows={extra.recent} t={t} />
      )}

      {/* ── Ham kayıt — denetçi ham türle filtreler ve kopyalar ───────── */}
      <button className="aud-disclosure" onClick={() => setShowRaw(v => !v)} aria-expanded={showRaw}>
        {showRaw ? <ChevronDown size={14} /> : <ChevronRight size={14} />}
        {t('audit.sectionRaw')}
      </button>
      {showRaw && (
        <div className="aud-raw">
          <CopyButton value={JSON.stringify(row, null, 2)} className="btn btn-sm aud-raw-copy" size={12} />
          <pre>{JSON.stringify(row, null, 2)}</pre>
        </div>
      )}
    </div>
  )
}

function RelatedList({ title, rows, t }) {
  return (
    <div className="audit-related">
      <div className="audit-related-title">{title}</div>
      {rows.map(e => (
        <div key={e.id} className="audit-related-row">
          <span className={`audit-event-badge ${eventClass(e.event_type)}`} title={e.event_type}>
            {eventLabel(e.event_type, t)}
          </span>
          <span className="audit-related-sub">{[e.resource_type, e.resource_id].filter(Boolean).join(':') || '—'}</span>
          <span className="audit-related-time">{formatDate(e.event_time)}</span>
        </div>
      ))}
    </div>
  )
}
