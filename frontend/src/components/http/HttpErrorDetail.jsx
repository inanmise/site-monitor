import { useState } from 'react'
import { AlertTriangle, ArrowRight, ChevronDown, ChevronRight, Info } from 'lucide-react'
import { formatDateSec } from '../../api/client'
import { parseHttpDiag, phaseStates, routeOf, hitTimeout, STRIP_PHASES } from '../../utils/httpDiag.js'

/**
 * HTTP/Website kontrol hatasının tanı paneli (2026-09-22): "ne oldu, hangi evrede, kimden kime, ne kadar bekledi".
 *
 * Sentetik izlemenin CheckDetail deseniyle aynı yerleşim: geçmiş satırına tıklanınca listenin ALTINDA açılır.
 * İçerik: evre şeridi (DNS → TCP → TLS → istek → yanıt; takılan kırmızı), tür başlığı + açıklama/öneri (i18n,
 * `httpdiag.kind.*` / `httpdiag.hint.*`), yol satırı (kaynak IP → hedef IP:port, vekil), bekleme/zaman aşımı,
 * çözümlenen IP'ler, yönlendirme zinciri, beklenen/gelen durum kodu ve ham istisna zinciri (katlanır).
 * Tanı olmayan (eski) satırda yalnız ham hata metni + not gösterilir — sessiz boşluk yok.
 */
export default function HttpErrorDetail({ check, t, onClose }) {
  const [raw, setRaw] = useState(false)
  if (!check) return null
  const d = parseHttpDiag(check)
  const kind = d?.kind || 'UNKNOWN'
  const states = d ? phaseStates(d) : null
  const route = routeOf(d)
  const timedOut = hitTimeout(d)

  return (
    <div className="hdiag" data-testid="http-error-detail">
      <div className="hdiag-head">
        <span className="hdiag-title"><AlertTriangle size={14} /> {t('httpdiag.title')}</span>
        <span className="hdiag-when">{formatDateSec(check.checked_at)}</span>
        {onClose && <button type="button" className="btn btn-sm btn-secondary hdiag-close" onClick={onClose}>{t('httpdiag.close')}</button>}
      </div>

      {!d ? (
        <>
          <div className="hdiag-legacy"><Info size={13} /> {t('httpdiag.legacy')}</div>
          {check.error && <pre className="hdiag-raw">{check.error}</pre>}
          {!check.error && check.http_status != null && <div className="hdiag-kind">{t('httpdiag.kind.STATUS_MISMATCH')} · HTTP {check.http_status}</div>}
        </>
      ) : (
        <>
          {/* Evre şeridi */}
          <ol className="hdiag-phases" aria-label={t('httpdiag.phasesLabel')}>
            {STRIP_PHASES.map((ph) => (
              <li key={ph} className={`hdiag-phase hdiag-phase--${states[ph]}`}>
                <span className="hdiag-phase-name">{t('httpdiag.phase.' + ph)}</span>
                <span className="hdiag-phase-state">{t('httpdiag.state.' + states[ph])}</span>
              </li>
            ))}
          </ol>

          {/* Tür + açıklama */}
          <div className="hdiag-kind">{t('httpdiag.kind.' + kind)}</div>
          <p className="hdiag-hint">{t('httpdiag.hint.' + kind)}</p>

          {/* Yol: kaynak → hedef */}
          <div className="hdiag-route">
            <span className="hdiag-route-k">{t('httpdiag.route')}</span>
            <code className="hdiag-ip">{route.from}</code>
            <ArrowRight size={13} />
            <code className="hdiag-ip">{route.to}{route.port != null ? `:${route.port}` : ''}</code>
            {route.viaProxy
              ? <span className="hdiag-via">{t('httpdiag.viaProxy', route.behind)}</span>
              : <span className="hdiag-via">{t('httpdiag.viaDirect')}</span>}
          </div>

          <dl className="hdiag-grid">
            <dt>{t('httpdiag.url')}</dt><dd><code>{d.method || 'GET'}</code> {d.url}</dd>
            <dt>{t('httpdiag.waited')}</dt>
            <dd className={timedOut ? 'hdiag-bad' : ''}>
              {d.elapsed_ms != null ? `${d.elapsed_ms} ms` : '—'}
              {d.timeout_ms != null && <span className="inv-dim"> · {t('httpdiag.timeoutSet', d.timeout_ms)}</span>}
              {timedOut && <span className="hdiag-flag"> {t('httpdiag.timeoutHit')}</span>}
            </dd>
            <dt>{t('httpdiag.resolved')}</dt>
            <dd>
              {Array.isArray(d.resolved_ips) && d.resolved_ips.length
                ? d.resolved_ips.map((ip) => <code key={ip} className={`hdiag-ip${ip === d.target_ip ? ' hdiag-ip--target' : ''}`}>{ip}</code>)
                : <span className="inv-dim">{t('httpdiag.noResolved')}</span>}
              {d.dns_ms != null && <span className="inv-dim"> · {t('httpdiag.dnsMs', d.dns_ms)}</span>}
            </dd>
            {(d.http_status != null || d.expected_status) && (<>
              <dt>{t('httpdiag.status')}</dt>
              <dd>{d.http_status != null ? `HTTP ${d.http_status}` : '—'}{d.expected_status && <span className="inv-dim"> · {t('httpdiag.expected', d.expected_status)}</span>}</dd>
            </>)}
            {Array.isArray(d.redirects) && d.redirects.length > 0 && (<>
              <dt>{t('httpdiag.redirects')}</dt>
              <dd><ol className="hdiag-redirects">{d.redirects.map((u, i) => <li key={i}><code>{u}</code></li>)}</ol></dd>
            </>)}
            <dt>{t('httpdiag.tls')}</dt>
            <dd>{String(d.scheme).toLowerCase() === 'https' ? (d.verify_ssl ? t('httpdiag.tlsStrict') : t('httpdiag.tlsTrustAll')) : t('httpdiag.tlsNone')}{d.follow_redirects === false && <span className="inv-dim"> · {t('httpdiag.noFollow')}</span>}</dd>
          </dl>

          {(d.exception || check.error) && (
            <div className="hdiag-rawbox">
              <button type="button" className="hdiag-rawtoggle" aria-expanded={raw} onClick={() => setRaw((v) => !v)}>
                {raw ? <ChevronDown size={13} /> : <ChevronRight size={13} />} {t('httpdiag.rawToggle')}
              </button>
              {raw && (
                <pre className="hdiag-raw">
                  {check.error ? check.error + '\n' : ''}
                  {Array.isArray(d.cause_chain) && d.cause_chain.length ? d.cause_chain.join('\n  ↳ ') : (d.exception || '')}
                </pre>
              )}
            </div>
          )}
        </>
      )}
    </div>
  )
}
