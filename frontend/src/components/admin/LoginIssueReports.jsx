import { useState, useEffect, useCallback } from 'react'
import { Loader2, ChevronLeft, ChevronRight } from 'lucide-react'
import { api } from '../../api/client'
import { useT } from '../../i18n/index.jsx'
import { useToast } from '../ui/Toast.jsx'
import { usePermissions } from '../../contexts/PermissionsProvider.jsx'
import DateTimeField from '../ui/DateTimeField.jsx'

// Durum → rozet sınıfı (kırmızı YOK — sistem alarmlarına saklı). OPEN/IN_PROGRESS amber, RESOLVED yeşil.
const STATUS_BADGE = { OPEN: 'badge badge-warn', IN_PROGRESS: 'badge badge-warn', RESOLVED: 'badge badge-ok' }

function fmtDate(iso) {
  if (!iso) return '—'
  // Saklanan zamanlar ISO UTC (Z'siz) — Europe/Istanbul yerel saatine çevir (YYYY-MM-DD HH:mm).
  const hasTz = /[zZ]$|[+-]\d\d:?\d\d$/.test(iso)
  const d = new Date(hasTz ? iso : iso + 'Z')
  if (isNaN(d.getTime())) return iso.replace('T', ' ').slice(0, 16)
  return d.toLocaleString('sv-SE', {
    timeZone: 'Europe/Istanbul',
    year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit',
  })
}

// Filtre tarih sınırını YEREL gün → UTC ISO'ya çevirir (kayıtlar UTC saklanır; reportedAt >= since / <= until
// String karşılaştırması). endOfDay=true → günün sonu (23:59:59). IncidentHistoryPage ile aynı yardımcı.
function localDayToUtcIso(dateStr, endOfDay) {
  if (!dateStr) return undefined
  const [y, m, d] = dateStr.slice(0, 10).split('-').map(Number)
  if (!y || !m || !d) return undefined
  const dt = new Date(y, m - 1, d, endOfDay ? 23 : 0, endOfDay ? 59 : 0, endOfDay ? 59 : 0)
  const p = (n) => String(n).padStart(2, '0')
  return `${dt.getUTCFullYear()}-${p(dt.getUTCMonth() + 1)}-${p(dt.getUTCDate())}` +
         `T${p(dt.getUTCHours())}:${p(dt.getUTCMinutes())}:${p(dt.getUTCSeconds())}`
}

/**
 * Login Sorun Bildirimleri — admin triyaj ekranı. Public /api/login-help akışından DB'ye yazılan
 * kayıtları listeler; İşleme Al / Çözümlendi (zorunlu not) / Yeniden Aç durum akışını yönetir.
 * Yalnız issues.login-reports iznine sahip kullanıcıya AdminSettings'te görünür. Mevcut CSS sınıfları.
 */
export default function LoginIssueReports() {
  const t = useT()
  const toast = useToast()
  const { canView } = usePermissions()
  const allowView = canView('issues.login-reports')  // izinsiz erişimde (bayat nav state) kalıcı spinner yerine temiz mesaj

  const [rows, setRows] = useState(null)       // null = yükleniyor
  const [counts, setCounts] = useState({ OPEN: 0, IN_PROGRESS: 0, RESOLVED: 0 })
  const [statusFilter, setStatusFilter] = useState('OPEN')  // '' = tümü
  const [q, setQ] = useState('')               // hata mesajı / açıklama / kullanıcı içinde arama
  const [since, setSince] = useState('')       // bildirim tarihi >= (yerel gün)
  const [until, setUntil] = useState('')       // bildirim tarihi <= (yerel gün)
  const [page, setPage] = useState(0)
  const [size, setSize] = useState(20)
  const [total, setTotal] = useState(0)
  const [detail, setDetail] = useState(null)   // seçili kaydın tam detayı
  const [note, setNote] = useState('')
  const [busy, setBusy] = useState(false)
  const [zoom, setZoom] = useState(null)       // büyütülen ekran görüntüsü (data-URL) — uygulama-içi lightbox
  const [openMail, setOpenMail] = useState(null) // açık mail satırı (gönderen/konu/gövde) — index

  const load = useCallback(async () => {
    if (!allowView) { setRows([]); return }   // izin yoksa 403 fetch + toast tetikleme
    const res = await api.admin.getLoginIssues({
      status: statusFilter || undefined,
      q: q.trim() || undefined,
      since: localDayToUtcIso(since, false),
      until: localDayToUtcIso(until, true),
      page, size,
    })
    if (res?.success) { setRows(res.data || []); setTotal(res.total || 0); setCounts(res.counts || counts) }
    else toast.error(res?.error || t('settings.loadError'))
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [statusFilter, q, since, until, page, size, allowView])

  useEffect(() => { load() }, [load])
  // Filtre/boyut değişince ilk sayfaya dön (page load'ı tekrar tetikler; zaten 0 ise load dep'lerden fırlar).
  useEffect(() => { setPage(0) }, [statusFilter, q, since, until, size])

  async function openDetail(id) {
    setOpenMail(null)
    const res = await api.admin.getLoginIssue(id)
    if (res?.success) { setDetail(res.data); setNote(res.data.resolutionNote || '') }  // mevcut notu önyükle (kaybolmasın)
    else toast.error(res?.error || t('settings.loadError'))
  }

  async function changeStatus(newStatus) {
    if (!detail) return
    if (newStatus === 'RESOLVED' && !note.trim()) { toast.error(t('loginIssues.noteRequired')); return }
    setBusy(true)
    // Not her durumda gönderilir (İşleme Al'da da) — kalıcı çalışma notu; boşsa backend mevcut notu korur.
    const res = await api.admin.updateLoginIssueStatus(detail.id, {
      status: newStatus,
      resolutionNote: note.trim() || undefined,
    })
    setBusy(false)
    if (res?.success) {
      toast.success(t('loginIssues.statusUpdated'))
      setDetail(res.data)
      setNote(res.data.resolutionNote || '')
      load()
    } else {
      toast.error(res?.error || t('loginIssues.statusError'))
    }
  }

  if (!allowView) {
    return <div className="admin-section"><div className="empty-state">{t('loginIssues.noAccess')}</div></div>
  }
  if (!rows) {
    return <div className="admin-section"><Loader2 className="spin" size={20} /> {t('settings.loading')}</div>
  }

  const STAT_CARDS = [
    { key: 'OPEN', label: t('loginIssues.statusOpen'), warn: true },
    { key: 'IN_PROGRESS', label: t('loginIssues.statusInProgress'), warn: true },
    { key: 'RESOLVED', label: t('loginIssues.statusResolved'), warn: false },
  ]

  return (
    <div className="admin-section">
      <h3>{t('loginIssues.title')}</h3>
      <p className="section-desc">{t('loginIssues.desc')}</p>

      {/* Sayaçlar (son 30 gün) — tıklayınca durum filtresi */}
      <div className="audit-stats-row">
        {STAT_CARDS.map((c) => (
          <button
            key={c.key}
            className={`audit-stat-card${c.warn ? ' warn' : ''}${statusFilter === c.key ? ' active' : ''}`}
            onClick={() => setStatusFilter((s) => (s === c.key ? '' : c.key))}
          >
            <div className="audit-stat-value">{counts[c.key] ?? 0}</div>
            <div className="audit-stat-label">{c.label}</div>
          </button>
        ))}
      </div>

      <div className="ldap-actions" style={{ margin: '10px 0' }}>
        <button className={`btn btn-secondary btn-sm-p${statusFilter === '' ? ' active' : ''}`}
          onClick={() => setStatusFilter('')}>{t('loginIssues.filterAll')}</button>
      </div>

      {/* Arama (hata mesajı / açıklama / kullanıcı) + bildirim tarihi aralığı */}
      <div className="inv-stats-pills" style={{ marginBottom: 12, gap: 8, alignItems: 'center' }}>
        <input className="filter-input" placeholder={t('loginIssues.searchPlaceholder')} value={q}
          onChange={(e) => setQ(e.target.value)} style={{ minWidth: 220 }} />
        <span className="inc-date-pair">
          <span className="inc-date-lbl">{t('loginIssues.dateFrom')}</span>
          <DateTimeField dateOnly clearable className="dtf-inline" placeholder={t('loginIssues.dateFrom')}
            value={since} onChange={(v) => setSince(v || '')} />
        </span>
        <span className="inc-date-pair">
          <span className="inc-date-lbl">{t('loginIssues.dateTo')}</span>
          <DateTimeField dateOnly clearable className="dtf-inline" placeholder={t('loginIssues.dateTo')}
            value={until} onChange={(v) => setUntil(v || '')} />
        </span>
      </div>

      {rows.length === 0 ? (
        <div className="empty-state">{t('loginIssues.empty')}</div>
      ) : (
        <div className="admin-table-wrap">
          <table className="admin-table">
            <thead>
              <tr>
                <th>{t('loginIssues.colRef')}</th>
                <th>{t('loginIssues.colMessage')}</th>
                <th>{t('loginIssues.colUser')}</th>
                <th>{t('loginIssues.colIp')}</th>
                <th>{t('loginIssues.colReportedAt')}</th>
                <th>{t('loginIssues.colResolvedAt')}</th>
                <th>{t('loginIssues.colImages')}</th>
                <th>{t('loginIssues.colStatus')}</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((r) => (
                <tr key={r.id} onClick={() => openDetail(r.id)} style={{ cursor: 'pointer' }}>
                  <td>{r.refCode}</td>
                  <td>{r.messageSummary}</td>
                  <td>{r.username || '—'}</td>
                  <td>{r.ipAddress || '—'}</td>
                  <td style={{ whiteSpace: 'nowrap' }}>{fmtDate(r.reportedAt)}</td>
                  <td style={{ whiteSpace: 'nowrap' }}>{fmtDate(r.resolvedAt)}</td>
                  <td>{r.imageCount > 0 ? r.imageCount : '—'}</td>
                  <td><span className={STATUS_BADGE[r.status] || 'badge'}>{t('loginIssues.status' + statusPascal(r.status))}</span></td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {/* Sayfalama — AlertHistory/IncidentHistory ile aynı .alh-* deseni (paylaşılan CSS). */}
      {total > 0 && (
        <div className="alh-pagination" style={{ marginTop: 10 }}>
          <div className="alh-page-size">
            <span>{t('inc.perPage')}</span>
            {[10, 20, 50].map((n) => (
              <button key={n} className={`alh-size-btn${size === n ? ' is-active' : ''}`} onClick={() => setSize(n)}>{n}</button>
            ))}
          </div>
          <div className="alh-page-info">{t('inc.pageOf', page + 1, Math.max(1, Math.ceil(total / size)))} · {total} {t('inc.records')}</div>
          <div className="alh-page-nav">
            <button disabled={page === 0} onClick={() => setPage((p) => p - 1)}><ChevronLeft size={13} /> {t('inc.prev')}</button>
            <button disabled={page + 1 >= Math.max(1, Math.ceil(total / size))} onClick={() => setPage((p) => p + 1)}>{t('inc.next')} <ChevronRight size={13} /></button>
          </div>
        </div>
      )}

      {detail && (
        <div className="modal-overlay" onClick={() => setDetail(null)}>
          <div className="modal-box modal-wide" onClick={(e) => e.stopPropagation()}>
            <h3>{detail.refCode} · <span className={STATUS_BADGE[detail.status] || 'badge'}>
              {t('loginIssues.status' + statusPascal(detail.status))}</span></h3>

            {/* Gövde — modal-wide padding:0 olduğundan içeriğe kenar boşluğu + kaydırma burada verilir */}
            <div style={{ padding: '0 28px', overflowY: 'auto', flex: '1 1 auto', minHeight: 0 }}>
            {/* Meta — sabit 2 kolon (etiket:değer); auto-fit grid kaymasını önler */}
            <div style={{ display: 'grid', gridTemplateColumns: 'max-content 1fr', columnGap: 16, rowGap: 6,
              alignItems: 'baseline', margin: '4px 0 14px' }}>
              <b>{t('loginIssues.colUser')}</b><span>{detail.username || '—'}</span>
              <b>{t('loginIssues.colEmail')}</b><span>{detail.reporterEmail || '—'}</span>
              <b>{t('loginIssues.colReportedAt')}</b><span>{fmtDate(detail.reportedAt)}</span>
              <b>{t('loginIssues.colIp')}</b><span>{detail.ipAddress || '—'}</span>
            </div>

            {detail.errorText && (
              <div style={{ marginTop: 10 }}>
                <div style={{ fontWeight: 700, fontSize: '.9em', marginBottom: 4 }}>{t('loginIssues.errorText')}</div>
                <div style={{ whiteSpace: 'pre-wrap', fontFamily: 'monospace', fontSize: 13, lineHeight: 1.5,
                  background: 'var(--bg,#f8fafc)', border: '1px solid var(--border,#e5e7eb)', borderRadius: 6, padding: '8px 10px' }}>
                  {detail.errorText}</div>
              </div>
            )}

            <div style={{ marginTop: 10 }}>
              <div style={{ fontWeight: 700, fontSize: '.9em', marginBottom: 4 }}>{t('loginIssues.message')}</div>
              <div style={{ whiteSpace: 'pre-wrap', lineHeight: 1.5,
                background: 'var(--bg,#f8fafc)', border: '1px solid var(--border,#e5e7eb)', borderRadius: 6, padding: '8px 10px' }}>
                {detail.message}</div>
            </div>

            {detail.images && detail.images.length > 0 && (
              <div style={{ marginTop: 10 }}>
                <div style={{ fontWeight: 700, fontSize: '.9em', marginBottom: 4 }}>{t('loginIssues.images')} ({detail.images.length})</div>
                <div style={{ display: 'flex', flexWrap: 'wrap', gap: 8 }}>
                  {detail.images.map((src, i) => (
                    <img key={i} src={src} alt={`shot ${i + 1}`} title={t('loginIssues.imageZoomHint')}
                      onClick={() => setZoom(src)}
                      style={{ height: 96, width: 'auto', maxWidth: 180, objectFit: 'cover', cursor: 'zoom-in',
                        border: '1px solid var(--border,#e5e7eb)', borderRadius: 6 }} />
                  ))}
                </div>
              </div>
            )}

            {detail.userAgent && (
              <div style={{ marginTop: 10 }}>
                <div style={{ fontWeight: 700, fontSize: '.9em', marginBottom: 4 }}>{t('loginIssues.userAgent')}</div>
                <div style={{ fontSize: 12, color: 'var(--text-light,#64748b)', wordBreak: 'break-all' }}>{detail.userAgent}</div>
              </div>
            )}

            {/* Gönderilen e-postalar — kime/ne zaman/hangi tür + teslim durumu (mail geçmişi). */}
            <div style={{ marginTop: 14 }}>
              <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 6 }}>
                <div style={{ fontWeight: 700, fontSize: '.9em' }}>
                  {t('loginIssues.mailHistory')}
                  {detail.mailHistory && detail.mailHistory.length > 0 &&
                    <span style={{ fontWeight: 400, fontSize: 11, color: 'var(--text-light,#64748b)', marginLeft: 8 }}>
                      {t('loginIssues.mailRowHint')}</span>}
                </div>
                <button className="btn btn-secondary btn-sm-p" onClick={() => openDetail(detail.id)}>{t('loginIssues.mailRefresh')}</button>
              </div>
              {(!detail.mailHistory || detail.mailHistory.length === 0) ? (
                <div className="hint">{t('loginIssues.mailNone')}</div>
              ) : (
                <div className="admin-table-wrap">
                  <table className="admin-table">
                    <thead>
                      <tr>
                        <th>{t('loginIssues.mailColType')}</th>
                        <th>{t('loginIssues.mailColTo')}</th>
                        <th>{t('loginIssues.mailColStatus')}</th>
                        <th>{t('loginIssues.mailColWhen')}</th>
                      </tr>
                    </thead>
                    <tbody>
                      {detail.mailHistory.map((ml, i) => {
                        const info = mailStatusInfo(ml.status, t)
                        const open = openMail === i
                        const rows = [
                          <tr key={i} onClick={() => setOpenMail(open ? null : i)} style={{ cursor: 'pointer' }}>
                            <td>{open ? '▾ ' : '▸ '}{mailTypeLabel(ml.mailType, t)}</td>
                            <td style={{ wordBreak: 'break-all' }}>
                              {ml.to || '—'}
                              {ml.cc ? <div style={{ fontSize: 11, color: 'var(--text-light,#64748b)' }}>CC: {ml.cc}</div> : null}
                            </td>
                            <td>
                              <span style={{ display: 'inline-block', padding: '1px 8px', borderRadius: 999,
                                fontSize: 12, fontWeight: 600, background: info.bg, color: info.color }}>{info.label}</span>
                              {ml.forced ? <span title={t('loginIssues.mailForcedHint')}
                                style={{ marginLeft: 6, fontSize: 11, color: 'var(--text-light,#64748b)' }}>⚡</span> : null}
                              {ml.error ? <div style={{ fontSize: 11, color: '#b91c1c', marginTop: 2, wordBreak: 'break-word' }}>{ml.error}</div> : null}
                            </td>
                            <td style={{ whiteSpace: 'nowrap' }}>{fmtDate(ml.sentAt)}</td>
                          </tr>,
                        ]
                        if (open) {
                          rows.push(
                            <tr key={i + '-content'}>
                              <td colSpan={4} style={{ background: 'var(--bg,#f8fafc)' }}>
                                <div style={{ fontSize: 12, marginBottom: 4 }}><b>{t('loginIssues.mailFrom')}:</b> {ml.from || '—'}</div>
                                <div style={{ fontSize: 12, marginBottom: 6 }}><b>{t('loginIssues.mailSubject')}:</b> {ml.subject || '—'}</div>
                                {ml.body ? (
                                  <iframe title={`mail-${i}`} sandbox="" srcDoc={mailBodyWithImages(ml.body, detail.images)}
                                    style={{ width: '100%', height: 340, border: '1px solid var(--border,#e5e7eb)',
                                      borderRadius: 6, background: '#fff' }} />
                                ) : (
                                  <div className="hint">{t('loginIssues.mailNoContent')}</div>
                                )}
                              </td>
                            </tr>
                          )
                        }
                        return rows
                      })}
                    </tbody>
                  </table>
                </div>
              )}
            </div>

            {detail.status === 'RESOLVED' && (
              <div className="threshold-field" style={{ marginTop: 8 }}>
                <label>{t('loginIssues.resolutionNote')}</label>
                <div style={{ whiteSpace: 'pre-wrap' }}>{detail.resolutionNote}</div>
                <div className="hint">{t('loginIssues.resolvedBy')}: {detail.resolvedBy || '—'} · {fmtDate(detail.resolvedAt)}</div>
              </div>
            )}

            {detail.status !== 'RESOLVED' && (
              <div className="threshold-field" style={{ marginTop: 8 }}>
                <label>{t('loginIssues.resolutionNote')} *</label>
                <textarea rows={3} value={note}
                  style={{ width: '100%', resize: 'vertical' }}
                  placeholder={t('loginIssues.notePlaceholder')} maxLength={2000}
                  onChange={(e) => setNote(e.target.value)} />
              </div>
            )}
            </div>{/* /gövde */}

            <div className="modal-actions">
              {detail.status === 'OPEN' && (
                <button className="btn btn-secondary" disabled={busy} onClick={() => changeStatus('IN_PROGRESS')}>
                  {t('loginIssues.actionInProgress')}</button>
              )}
              {detail.status !== 'RESOLVED' && (
                <button className="btn btn-primary" disabled={busy} onClick={() => changeStatus('RESOLVED')}>
                  {t('loginIssues.actionResolve')}</button>
              )}
              {detail.status === 'RESOLVED' && (
                <button className="btn btn-warning" disabled={busy} onClick={() => changeStatus('OPEN')}>
                  {t('loginIssues.actionReopen')}</button>
              )}
              <button className="btn btn-secondary" onClick={() => setDetail(null)}>{t('dom.close')}</button>
            </div>
          </div>
        </div>
      )}

      {/* Lightbox — resmi uygulama içinde tam boy okunur gösterir (data-URL yeni sekmede boş açılıyordu) */}
      {zoom && (
        <div className="modal-overlay" style={{ zIndex: 3000, background: 'rgba(0,0,0,.85)', cursor: 'zoom-out' }}
          onClick={() => setZoom(null)} title={t('loginIssues.imageCloseHint')}>
          {/* Resme de arka plana da tıklayınca kapanır (stopPropagation YOK) */}
          <img src={zoom} alt="" onClick={() => setZoom(null)}
            style={{ maxWidth: '94vw', maxHeight: '94vh', objectFit: 'contain', cursor: 'zoom-out',
              boxShadow: '0 8px 40px rgba(0,0,0,.5)', borderRadius: 4 }} />
        </div>
      )}
    </div>
  )
}

function statusPascal(s) {
  if (s === 'IN_PROGRESS') return 'InProgress'
  if (s === 'RESOLVED') return 'Resolved'
  return 'Open'
}

// Mail durum rozeti — SENT yeşil, FAILED kırmızı, SKIPPED/QUEUED nötr/amber. Inline stil (global CSS'e bağlı değil).
function mailStatusInfo(status, t) {
  const s = status || ''
  if (s === 'SENT') return { label: t('loginIssues.mailSent'), bg: '#dcfce7', color: '#15803d' }
  if (s.startsWith('FAILED')) return { label: t('loginIssues.mailFailed'), bg: '#fee2e2', color: '#b91c1c' }
  if (s.startsWith('SKIPPED')) return { label: t('loginIssues.mailSkipped'), bg: '#f3f4f6', color: '#6b7280' }
  if (s.startsWith('QUEUED')) return { label: t('loginIssues.mailQueued'), bg: '#fef3c7', color: '#b45309' }
  return { label: s || '—', bg: '#f3f4f6', color: '#6b7280' }
}

function mailTypeLabel(type, t) {
  if (type === 'REPORT_ADMIN') return t('loginIssues.mailTypeReport')
  if (type === 'REPORTER_ACK') return t('loginIssues.mailTypeAck')
  if (type === 'RESOLVED') return t('loginIssues.mailTypeResolved')
  return type || '—'
}

// Mail gövdesindeki cid:shotN görsel referanslarını raporun kayıtlı data-URL'leriyle değiştirir — böylece
// uygulama-içi önizleme iframe'inde ekran görüntüleri görünür (cid: yalnız gerçek mail istemcisinde çözülür).
function mailBodyWithImages(body, images) {
  if (!body || !images || images.length === 0) return body
  return body.replace(/src=(['"])cid:shot(\d+)\1/gi, (m, quote, idx) => {
    const url = images[Number(idx)]
    return url ? `src=${quote}${url}${quote}` : m
  })
}
