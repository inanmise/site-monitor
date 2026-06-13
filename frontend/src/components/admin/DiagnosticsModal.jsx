import { useState, useEffect } from 'react'
import { Loader2 } from 'lucide-react'
import { api, formatDate } from '../../api/client'
import { useToast } from '../ui/Toast.jsx'
import { useT } from '../../i18n/index.jsx'

function ShowField({ label, value, mono, full }) {
  return (
    <div className={`show-field${full ? ' show-field-full' : ''}`}>
      <span className="show-field-label">{label}</span>
      <span className={`show-field-value${mono ? ' show-field-mono' : ''}`}>{value ?? '—'}</span>
    </div>
  )
}

/**
 * Bağlantı/SSL/ağ derin tanılama modalı — hem Domain Envanteri hem Durum İzleme
 * kartlarından kullanılır. Backend uçları (POST /admin/diagnostics[/openssl|/network],
 * GET /diagnostics/history[/{id}]) herhangi bir domain+port için admin-only çalışır.
 * Props: { domain, port, onClose }. Mount'ta otomatik runDiag.
 */
export default function DiagnosticsModal({ domain, port, onClose }) {
  const t = useT()
  const toast = useToast()
  const item = { domain, port: port || 443 }
  const [diag, setDiag]       = useState({ item, loading: true }) // { item, loading?, data?, error?, ossl?, net? }
  const [history, setHistory] = useState(null)                    // { item, loading?, items?, detail? }

  useEffect(() => { runDiag(item) }, []) // eslint-disable-line react-hooks/exhaustive-deps

  async function runDiag(it) {
    setDiag({ item: it, loading: true })
    try {
      const res = await api.admin.runDiagnostics(it.domain, it.port || 443)
      setDiag(res?.success
        ? { item: it, data: res.data }
        : { item: it, error: res?.error || t('inv.diagError') })
    } catch {
      setDiag({ item: it, error: t('inv.diagError') })
    }
  }

  const DIAG_STEP_KEYS = {
    'dns':           'inv.diagStepDns',
    'tcp-connect':   'inv.diagStepTcp',
    'proxy-connect': 'inv.diagStepProxyConnect',
    'tls-handshake': 'inv.diagStepTls',
    'cert-ok':       'inv.diagStepCertOk',
  }
  const diagStepLabel = (step) => DIAG_STEP_KEYS[step] ? t(DIAG_STEP_KEYS[step]) : (step || '—')

  /** Derin SSL/TLS taraması (openssl) — mevcut tanılama modalında gösterilir. */
  async function runOpenssl(it) {
    setDiag((d) => ({ ...d, ossl: { loading: true } }))
    try {
      const res = await api.admin.runOpensslDiagnostics(it.domain, it.port || 443)
      setDiag((d) => ({ ...d, ossl: res?.success ? { data: res.data } : { error: res?.error || t('inv.diagError') } }))
    } catch {
      setDiag((d) => ({ ...d, ossl: { error: t('inv.diagError') } }))
    }
  }

  async function runNetwork(it) {
    setDiag((d) => ({ ...d, net: { loading: true } }))
    try {
      const res = await api.admin.runNetworkDiagnostics(it.domain, it.port || 443)
      setDiag((d) => ({ ...d, net: res?.success ? { data: res.data } : { error: res?.error || t('inv.diagError') } }))
    } catch {
      setDiag((d) => ({ ...d, net: { error: t('inv.diagError') } }))
    }
  }

  /** Ağ derin analizi sonucu — kontrol kartları + ham çıktı (canlı + geçmiş). */
  function renderNetwork(data) {
    if (!data) return null
    const statusBadge = (s) => {
      if (s === 'ok') return <span className="badge badge-ok">{t('inv.diagOk')}</span>
      if (s === 'fail') return <span className="badge badge-err">{t('inv.diagError')}</span>
      if (s === 'na') return <span className="badge">{t('inv.netNa')}</span>
      return <span className="badge" style={{ background: '#fef3c7', color: '#92400e' }}>warn</span>
    }
    return (
      <>
        {(data.checks ?? []).map((c) => (
          <details key={c.key} style={{ marginBottom: 6, border: '1px solid var(--border)', borderRadius: 6 }}>
            <summary style={{ cursor: 'pointer', padding: '7px 10px', display: 'flex', alignItems: 'center', gap: 8, flexWrap: 'wrap' }}>
              <strong>{c.label}</strong>
              {statusBadge(c.status)}
              <span style={{ fontSize: '.85em', color: 'var(--text-muted)' }}>{c.summary}</span>
            </summary>
            <pre className="show-pre" style={{ margin: '0 8px 8px', maxHeight: 260 }}>{c.output}</pre>
          </details>
        ))}
      </>
    )
  }

  // ── Tanılama geçmişi ──
  async function openHistory(it) {
    setHistory({ item: it, loading: true })
    const res = await api.admin.diagHistory(it.domain)
    setHistory(res?.success ? { item: it, items: res.data ?? [] } : { item: it, error: res?.error || t('inv.diagError') })
  }

  const histTypeLabel = (rt) => rt === 'OPENSSL' ? t('inv.histTypeOpenssl')
    : rt === 'NETWORK' ? t('inv.histTypeNetwork') : t('inv.histTypeConnection')

  async function openHistoryDetail(id) {
    const res = await api.admin.diagHistoryDetail(id)
    if (res?.success) {
      let parsed = null
      try { parsed = JSON.parse(res.data.result_json) } catch { /* */ }
      setHistory((h) => ({ ...h, detail: { ...res.data, result: parsed } }))
    } else {
      toast.error(res?.error || t('inv.diagError'))
    }
  }

  /** openssl protokol risk rozeti. */
  const osslRiskBadge = (p) => {
    if (!p.supported) return <span className="badge">{t('inv.osslDisabled')}</span>
    return p.risk === 'HIGH'
      ? <span className="badge badge-err">{t('inv.osslRiskHigh')}</span>
      : <span className="badge badge-ok">{t('inv.osslRiskOk')}</span>
  }

  /** openssl sonuç gövdesi — hem canlı tarama hem geçmiş detayında kullanılır. */
  function renderOssl(data) {
    if (!data) return null
    if (data.available === false) {
      return <div className="alert-msg">{t('inv.osslUnavailable')}</div>
    }
    const cert = data.certificate || {}
    return (
      <>
        {data.reachable === false && (
          <div className="alert-msg" style={{ background: '#fde8e8', color: '#9b1c1c' }}>
            {t('inv.osslUnreachable')}
          </div>
        )}
        <ShowField label={t('inv.osslVersion')} mono value={data.version || '—'} />
        <div className="show-section-header">{t('inv.osslProtocols')}</div>
        <div className="health-table-wrap">
          <table className="health-table">
            <thead>
              <tr>
                <th>{t('inv.osslColProto')}</th>
                <th>{t('inv.osslColState')}</th>
                <th>{t('inv.osslColRisk')}</th>
              </tr>
            </thead>
            <tbody>
              {(data.protocols ?? []).map((p) => (
                <tr key={p.proto}>
                  <td><strong>{p.proto}</strong></td>
                  <td>{p.supported ? t('inv.osslEnabled') : t('inv.osslDisabled')}</td>
                  <td>{osslRiskBadge(p)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        <div className="show-section-header">{t('inv.osslNegotiated')}</div>
        <div className="show-grid-2">
          <ShowField label="Protocol" mono value={data.negotiated?.protocol || '—'} />
          <ShowField label="Cipher" mono value={data.negotiated?.cipher || '—'} />
        </div>
        <div className="show-section-header">{t('inv.osslCert')}</div>
        <div className="show-grid-2">
          <ShowField label="Subject" mono value={cert.subject || '—'} />
          <ShowField label="Issuer" mono value={cert.issuer || '—'} />
          <ShowField label="Not After" mono value={cert.not_after || '—'} />
          <ShowField label="Key / Sig" mono
            value={`${cert.key_bits ? cert.key_bits + ' bit' : '—'} · ${cert.signature_algorithm || '—'}`} />
        </div>
        {(data.flags ?? []).length > 0 && (
          <div style={{ margin: '8px 0' }}>
            {data.flags.map((f) => (
              <span key={f} className="badge badge-err" style={{ marginRight: 6 }}>
                {t('inv.osslFlag' + f.split('_').map((s) => s.charAt(0) + s.slice(1).toLowerCase()).join('')) || f}
              </span>
            ))}
          </div>
        )}
        <div className="show-section-header">{t('inv.osslRaw')}</div>
        {(data.raw ?? []).map((r, i) => (
          <details key={i} style={{ marginBottom: 6 }}>
            <summary className="show-field-mono" style={{ cursor: 'pointer', color: 'var(--text-muted)' }}>
              {r.cmd}
            </summary>
            <pre className="show-pre">{r.output}</pre>
          </details>
        ))}
      </>
    )
  }

  return (
    <>
      {/* ── Bağlantı Tanılama Modalı ── */}
      {diag && (
        <div className="modal-overlay" onClick={onClose}>
          <div className="modal-box modal-show" onClick={(e) => e.stopPropagation()}>

            <div className="show-header">
              <div className="show-header-title">
                <span className="show-domain">{t('inv.diagTitle', diag.item.domain)}</span>
                <span className="show-badge show-badge-port">:{diag.item.port || 443}</span>
              </div>
              <button type="button" className="show-close" aria-label={t('app.dismiss')} onClick={onClose}>✕</button>
            </div>

            <div className="show-body">

              {diag.loading && (
                <div className="show-field show-field-full">
                  <span className="show-field-value">
                    <Loader2 size={14} className="spin" /> {t('inv.diagRunning')}
                  </span>
                </div>
              )}

              {diag.error && (
                <div className="alert-msg">{diag.error}</div>
              )}

              {diag.data && (
                <>
                  <div className="show-section-header">{t('inv.diagSource')}</div>
                  <div className="show-grid-2">
                    <ShowField label={t('inv.diagSourceHost')} mono
                      value={diag.data.source?.hostname || '—'} />
                    <ShowField label={t('inv.diagSourceIps')} mono
                      value={diag.data.source?.ips?.length ? diag.data.source.ips.join(', ') : '—'} />
                  </div>

                  <div className="show-section-header">{t('inv.diagDns')}</div>
                  <div className="show-grid-2">
                    <ShowField label={t('inv.diagDnsIps')} mono value={
                      diag.data.dns?.error
                        ? <span className="badge badge-err">{diag.data.dns.error}</span>
                        : (diag.data.dns?.ips?.length ? diag.data.dns.ips.join(', ') : '—')
                    } />
                    <ShowField label={t('inv.diagColElapsed')} value={diag.data.dns?.elapsed_ms ?? '—'} />
                  </div>

                  <div className="show-section-header">{t('inv.diagMatrix')}</div>
                  <div className="health-table-wrap">
                    <table className="health-table">
                      <thead>
                        <tr>
                          <th>{t('inv.diagColCombo')}</th>
                          <th>{t('inv.diagColStep')}</th>
                          <th>{t('inv.diagColElapsed')}</th>
                          <th>{t('inv.diagColResult')}</th>
                        </tr>
                      </thead>
                      <tbody>
                        {(diag.data.combos ?? []).map((c) => (
                          <tr key={c.id}>
                            <td>
                              <strong>{c.via === 'proxy' ? t('inv.diagViaProxy') : t('inv.diagViaDirect')}</strong>
                              {' + '}{c.tls_mode}
                              {c.source_ip && (
                                <div className="show-field-mono" style={{ color: 'var(--text-muted)', marginTop: 2 }}>
                                  {c.source_ip}:{c.source_port} → {c.peer_ip}:{c.peer_port}
                                  {c.via === 'proxy' && ` (${t('inv.diagViaProxy')}) → ${diag.item.domain}:${diag.item.port || 443}`}
                                </div>
                              )}
                              {c.via === 'proxy' && diag.data.proxy_address && (
                                <div className="show-field-mono" style={{ color: 'var(--text-muted)', marginTop: 2 }}>
                                  {t('inv.diagProxyVia')}: {diag.data.proxy_address}
                                </div>
                              )}
                            </td>
                            <td>{diagStepLabel(c.step_reached)}</td>
                            <td>{c.elapsed_ms ?? '—'}</td>
                            <td>
                              <span className={c.status === 'ok' ? 'badge badge-ok' : 'badge badge-err'}>
                                {c.status === 'ok' ? t('inv.diagOk') : (c.error_class || 'ERROR')}
                              </span>
                              {c.status === 'ok'
                                ? <span style={{ marginLeft: 8 }}>{c.subject} · {c.days_remaining}d · {c.tls_version}</span>
                                : <span style={{ marginLeft: 8 }}>{(c.error || '').slice(0, 120)}</span>}
                            </td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  </div>

                  {/* ── Derin SSL/TLS taraması (openssl) ── */}
                  <div className="show-section-header" style={{ marginTop: 18 }}>{t('inv.osslTitle')}</div>
                  {!diag.ossl && (
                    <button className="btn btn-secondary btn-sm-p" style={{ marginTop: 6 }}
                      onClick={() => runOpenssl(diag.item)}>{t('inv.osslRun')}</button>
                  )}
                  {diag.ossl?.loading && (
                    <div className="show-field-value"><Loader2 size={14} className="spin" /> {t('inv.diagRunning')}</div>
                  )}
                  {diag.ossl?.error && <div className="alert-msg">{diag.ossl.error}</div>}
                  {diag.ossl?.data && renderOssl(diag.ossl.data)}

                  {/* ── Ağ Derin Analizi ── */}
                  <div className="show-section-header" style={{ marginTop: 18 }}>{t('inv.netTitle')}</div>
                  {!diag.net && (
                    <button className="btn btn-secondary btn-sm-p" style={{ marginTop: 6 }}
                      onClick={() => runNetwork(diag.item)}>{t('inv.netRun')}</button>
                  )}
                  {diag.net?.loading && (
                    <div className="show-field-value"><Loader2 size={14} className="spin" /> {t('inv.diagRunning')}</div>
                  )}
                  {diag.net?.error && <div className="alert-msg">{diag.net.error}</div>}
                  {diag.net?.data && renderNetwork(diag.net.data)}
                </>
              )}

              <div className="modal-actions">
                <button className="btn btn-secondary" onClick={onClose}>{t('app.dismiss')}</button>
                <button className="btn btn-secondary" onClick={() => openHistory(diag.item)}>
                  {t('inv.diagHistory')}
                </button>
                <button className="btn btn-primary" onClick={() => runDiag(diag.item)} disabled={!!diag.loading}>
                  {t('inv.diagRerun')}
                </button>
              </div>

            </div>
          </div>
        </div>
      )}

      {/* ── Tanılama geçmişi ── */}
      {history && (
        <div className="modal-overlay" onClick={() => setHistory(null)}>
          <div className="modal-box modal-show" onClick={(e) => e.stopPropagation()}
            style={{ maxWidth: 820, width: '100%', maxHeight: '88vh', display: 'flex', flexDirection: 'column' }}>
            <div className="show-header">
              <div className="show-header-title">
                <span className="show-domain">{t('inv.diagHistory')} — {history.item.domain}</span>
              </div>
              <button type="button" className="show-close" aria-label={t('app.dismiss')} onClick={() => setHistory(null)}>✕</button>
            </div>
            <div className="show-body" style={{ overflowY: 'auto' }}>
              {history.loading && (
                <div className="show-field-value"><Loader2 size={14} className="spin" /> {t('inv.diagRunning')}</div>
              )}
              {history.error && <div className="alert-msg">{history.error}</div>}

              {history.items && !history.detail && (
                history.items.length ? (
                  <div className="health-table-wrap">
                    <table className="health-table">
                      <thead>
                        <tr>
                          <th>{t('inv.histColType')}</th>
                          <th>{t('inv.histColWho')}</th>
                          <th>{t('inv.histColWhen')}</th>
                          <th>{t('inv.histColSource')}</th>
                          <th>{t('inv.histColResult')}</th>
                          <th></th>
                        </tr>
                      </thead>
                      <tbody>
                        {history.items.map((h) => (
                          <tr key={h.id}>
                            <td>{histTypeLabel(h.run_type)}</td>
                            <td>{h.executed_by}</td>
                            <td>{formatDate(h.executed_at)}</td>
                            <td className="show-field-mono">{h.source_ip || '—'}</td>
                            <td>
                              <span className={h.success ? 'badge badge-ok' : 'badge badge-err'}>
                                {h.success ? t('inv.diagOk') : t('inv.diagError')}
                              </span>
                              {h.summary && <span style={{ marginLeft: 8 }}>{h.summary}</span>}
                            </td>
                            <td>
                              <button className="btn-sm btn-show" onClick={() => openHistoryDetail(h.id)}>
                                {t('inv.histView')}
                              </button>
                            </td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  </div>
                ) : (
                  <div className="empty-state">{t('inv.histEmpty')}</div>
                )
              )}

              {history.detail && (
                <>
                  <button className="btn btn-secondary btn-sm-p" style={{ marginBottom: 10 }}
                    onClick={() => setHistory((h) => ({ ...h, detail: null }))}>← {t('inv.diagHistory')}</button>
                  <div className="show-grid-2">
                    <ShowField label={t('inv.histColWho')} value={history.detail.executed_by} />
                    <ShowField label={t('inv.histColWhen')} value={formatDate(history.detail.executed_at)} />
                    <ShowField label={t('inv.histColSource')} mono value={history.detail.source_ip || '—'} />
                    <ShowField label={t('inv.histColType')} value={histTypeLabel(history.detail.run_type)} />
                  </div>
                  {/* OPENSSL → openssl render; NETWORK → ağ kartları; CONNECTION → combo matrisi */}
                  {history.detail.run_type === 'OPENSSL'
                    ? <div style={{ marginTop: 8 }}>{renderOssl(history.detail.result)}</div>
                    : history.detail.run_type === 'NETWORK'
                    ? <div style={{ marginTop: 8 }}>{renderNetwork(history.detail.result)}</div>
                    : (
                      <>
                        <div className="show-section-header" style={{ marginTop: 8 }}>{t('inv.diagMatrix')}</div>
                        <div className="health-table-wrap">
                          <table className="health-table">
                            <thead>
                              <tr>
                                <th>{t('inv.diagColCombo')}</th>
                                <th>{t('inv.diagColStep')}</th>
                                <th>{t('inv.diagColElapsed')}</th>
                                <th>{t('inv.diagColResult')}</th>
                              </tr>
                            </thead>
                            <tbody>
                              {(history.detail.result?.combos ?? []).map((c) => (
                                <tr key={c.id}>
                                  <td><strong>{c.via === 'proxy' ? t('inv.diagViaProxy') : t('inv.diagViaDirect')}</strong>{' + '}{c.tls_mode}</td>
                                  <td>{diagStepLabel(c.step_reached)}</td>
                                  <td>{c.elapsed_ms ?? '—'}</td>
                                  <td>
                                    <span className={c.status === 'ok' ? 'badge badge-ok' : 'badge badge-err'}>
                                      {c.status === 'ok' ? t('inv.diagOk') : (c.error_class || 'ERROR')}
                                    </span>
                                  </td>
                                </tr>
                              ))}
                            </tbody>
                          </table>
                        </div>
                      </>
                    )}
                </>
              )}

              <div className="modal-actions">
                <button className="btn btn-secondary" onClick={() => setHistory(null)}>{t('app.dismiss')}</button>
              </div>
            </div>
          </div>
        </div>
      )}
    </>
  )
}
