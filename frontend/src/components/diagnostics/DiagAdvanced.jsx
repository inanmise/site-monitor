import { useT } from '../../i18n/index.jsx'
import AlertBanner from '../ui/AlertBanner.jsx'
import ToneBadge from '../admin/ToneBadge.jsx'
import { TH, TD, DataTable, KV_GRID } from '../admin/HealthUi.jsx'
import { TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/shadcn/table'
import { cn } from '@/lib/utils'
import { ShowField, PRE, MUTED, Disclosure, Section } from './DiagParts.jsx'

/**
 * Ek (derin) analiz sonuçlarının çizimi — openssl, ağ komutları, HSTS, istemci IP, JDK TLS parmak izi ve
 * ham kombinasyon matrisi. Eski DiagnosticsModal'dan DAVRANIŞ DEĞİŞMEDEN taşındı (2026-09-26); canlı koşu
 * ve geçmiş ayrıntısı aynı bileşenleri kullanır. Tümü shadcn (Table, Badge, Collapsible, Alert).
 */

/** Ağ derin analizi — kontrol kartları + ham çıktı. */
export function NetworkResult({ data }) {
  const t = useT()
  if (!data) return null
  const statusBadge = (s) => {
    if (s === 'ok') return <ToneBadge tone="success">{t('inv.diagOk')}</ToneBadge>
    if (s === 'fail') return <ToneBadge tone="danger">{t('inv.diagError')}</ToneBadge>
    if (s === 'na') return <ToneBadge tone="muted">{t('inv.netNa')}</ToneBadge>
    return <ToneBadge tone="warning">warn</ToneBadge>
  }
  return (
    <div data-slot="diag-network">
      {(data.checks ?? []).map((c) => (
        <Disclosure key={c.key} bordered summary={<>
          <strong>{c.label}</strong>
          {statusBadge(c.status)}
          <span className={cn('min-w-0 text-[.85em] break-words', MUTED)}>{c.summary}</span>
        </>}>
          <pre className={PRE}>{c.output}</pre>
        </Disclosure>
      ))}
    </div>
  )
}

/** HSTS analiz sonucu — karar + yönergeler + "neyi nasıl kontrol etti" + "neyi bulamaz". */
export function HstsResult({ data }) {
  const t = useT()
  if (!data) return null
  const verdictBadge = (v) => {
    if (v === 'ENFORCED') return <ToneBadge tone="success">{t('inv.hstsEnforced')}</ToneBadge>
    if (v === 'NOT_ENFORCED') return <ToneBadge tone="warning">{t('inv.hstsNotEnforced')}</ToneBadge>
    if (v === 'ABSENT') return <ToneBadge tone="danger">{t('inv.hstsAbsent')}</ToneBadge>
    return <ToneBadge tone="danger">{t('inv.hstsConnectFail')}</ToneBadge>
  }
  const stBadge = (s) => {
    if (s === 'ok') return <ToneBadge tone="success">✓</ToneBadge>
    if (s === 'fail') return <ToneBadge tone="danger">✗</ToneBadge>
    if (s === 'na') return <ToneBadge tone="muted">–</ToneBadge>
    return <ToneBadge tone="warning">!</ToneBadge>
  }
  const yesNo = (b) => (b == null ? '—' : b ? t('inv.hstsYes') : t('inv.hstsNo'))
  return (
    <div data-slot="diag-hsts">
      <div className="mt-1 mb-2">{verdictBadge(data.verdict)}</div>
      <ShowField full mono label={t('inv.hstsHeaderField')} value={data.raw_value || t('inv.hstsNone')} />
      {data.header_present && (
        <div className={KV_GRID}>
          <ShowField label={t('inv.hstsMaxAge')} value={data.max_age != null ? data.max_age + ' s' : '—'} />
          <ShowField label={t('inv.hstsIncludeSub')} value={yesNo(data.include_subdomains)} />
          <ShowField label={t('inv.hstsPreload')} value={yesNo(data.preload)} />
        </div>
      )}
      {data.http_redirects_to_https !== undefined && (
        <div className="mt-2"><ShowField label={t('inv.hstsHttpRedirect')} value={yesNo(data.http_redirects_to_https)} /></div>
      )}

      <Section>{t('inv.hstsChecks')}</Section>
      {(data.checks ?? []).map((c) => (
        <div key={c.key} className="mb-[7px]">
          <div className="flex flex-wrap items-center gap-1.5">
            {stBadge(c.status)} <strong className="text-[.9em]">{c.result}</strong>
          </div>
          <div className={cn('ml-0.5 text-[.82em]', MUTED)}>{c.how}</div>
        </div>
      ))}

      {(data.response_headers ?? []).length > 0 && (
        <div className="mt-2.5">
          <Disclosure summary={<span className="text-[.88em] font-semibold">{t('inv.hstsRespHeaders')} ({data.header_count ?? data.response_headers.length})</span>}>
            <div className={cn(PRE, 'mt-1.5 max-h-[300px]')}>
              {data.status_line && <div className={MUTED}>{data.status_line}</div>}
              {data.response_headers.map((h, i) => {
                const isSts = String(h.name).toLowerCase() === 'strict-transport-security'
                return (
                  <div key={i} data-sts={isSts ? 'true' : undefined} className={cn(isSts && 'rounded-sm bg-green-500/15 font-bold')}>
                    <span className={isSts ? 'text-green-600 dark:text-green-400' : 'text-primary'}>{h.name}</span>: {h.value}
                  </div>
                )
              })}
            </div>
          </Disclosure>
        </div>
      )}

      {(data.notes ?? []).length > 0 && (
        <>
          <Section>{t('inv.hstsNotes')}</Section>
          <ul className={cn('mt-1 ml-[18px] list-disc text-[.83em]', MUTED)}>
            {data.notes.map((n, i) => <li key={i} className="mb-1">{n}</li>)}
          </ul>
        </>
      )}
      {data.error && <AlertBanner tone="danger" className="mt-2">{data.error}</AlertBanner>}
    </div>
  )
}

/** Sunucu bu isteği (admin'in tarayıcısı → pod) nasıl görüyor: proxy/forwarding başlıkları + çözülen client IP. */
export function ClientIpResult({ data }) {
  const t = useT()
  if (!data) return null
  const headers = data.headers || {}
  const anyHeader = Object.values(headers).some((v) => v != null && String(v).trim() !== '')
  return (
    <div data-slot="diag-clientip">
      <div className={KV_GRID}>
        <ShowField label={t('inv.cipResolved')} mono value={data.resolved || '—'} />
        <ShowField label={t('inv.cipRemoteAddr')} mono value={data.remote_addr || '—'} />
      </div>
      <Section>{t('inv.cipHeaders')}</Section>
      <DataTable>
        <TableHeader><TableRow><TableHead className={TH}>{t('inv.cipHeader')}</TableHead><TableHead className={TH}>{t('inv.cipValue')}</TableHead></TableRow></TableHeader>
        <TableBody>
          {Object.entries(headers).map(([k, v]) => (
            <TableRow key={k}>
              <TableCell className={cn(TD, 'font-mono text-xs')}>{k}</TableCell>
              <TableCell className={cn(TD, 'font-mono text-xs break-all')}>
                {v == null || String(v).trim() === '' ? <span className={MUTED}>null</span> : String(v)}
              </TableCell>
            </TableRow>
          ))}
        </TableBody>
      </DataTable>
      {!anyHeader && <AlertBanner tone="warning" className="mt-2">{t('inv.cipNoHeaders')}</AlertBanner>}
    </div>
  )
}

/**
 * Çalışan JVM'in TLS istemci parmak izi: JDK sürümü + browser/default modda sunulan protokol/cipher/ALPN.
 * JDK sürümleri arası el sıkışma farkını karşılaştırmak için (aynı teşhisi iki JDK'da koş, bu bölümü kıyasla).
 */
export function TlsClientInfo({ tc }) {
  const t = useT()
  if (!tc) return null
  const arr = (v) => (Array.isArray(v) && v.length ? v.join(', ') : t('inv.diagTlsDefaultVal'))
  const mode = (m, label) => !m ? null : (
    <Disclosure bordered summary={<strong>{label}</strong>}>
      <ShowField full label={t('inv.diagTlsProtocols')} mono value={arr(m.protocols)} />
      <ShowField full label={t('inv.diagTlsAlpn')} mono value={arr(m.application_protocols)} />
      <ShowField full label={t('inv.diagTlsCiphers')} mono value={arr(m.cipher_suites)} />
      <ShowField full label={t('inv.diagTlsSig')} mono value={arr(m.signature_schemes)} />
      <ShowField full label={t('inv.diagTlsGroups')} mono value={arr(m.named_groups)} />
    </Disclosure>
  )
  return (
    <div data-slot="diag-tlsclient">
      <ShowField label={t('inv.diagTlsJdk')} mono value={`${tc.java_version || '—'}${tc.java_vendor ? ' · ' + tc.java_vendor : ''}`} />
      <div className={cn('mt-1 mb-2 text-[.82em]', MUTED)}>{t('inv.diagTlsHint')}</div>
      {mode(tc.browser, t('inv.diagTlsBrowserMode'))}
      {mode(tc.default, t('inv.diagTlsDefaultMode'))}
    </div>
  )
}

/** Bağlantı kombinasyon matrisi — canlı tanı (`detailed`: kaynak/eş adresi + sonuç özeti) ve geçmiş detayı. */
export function CombosTable({ combos, detailed = false, item, proxyAddress }) {
  const t = useT()
  const DIAG_STEP_KEYS = {
    'dns': 'inv.diagStepDns', 'tcp-connect': 'inv.diagStepTcp', 'proxy-connect': 'inv.diagStepProxyConnect',
    'tls-handshake': 'inv.diagStepTls', 'cert-ok': 'inv.diagStepCertOk',
  }
  const stepLabel = (step) => (DIAG_STEP_KEYS[step] ? t(DIAG_STEP_KEYS[step]) : (step || '—'))
  return (
    <DataTable>
      <TableHeader><TableRow>
        <TableHead className={TH}>{t('inv.diagColCombo')}</TableHead>
        <TableHead className={TH}>{t('inv.diagColStep')}</TableHead>
        <TableHead className={cn(TH, 'hidden sm:table-cell')}>{t('inv.diagColElapsed')}</TableHead>
        <TableHead className={TH}>{t('inv.diagColResult')}</TableHead>
      </TableRow></TableHeader>
      <TableBody>
        {(combos ?? []).map((c) => (
          <TableRow key={c.id} data-combo-status={c.status}>
            <TableCell className={cn(TD, 'align-top')}>
              <strong>{c.via === 'proxy' ? t('inv.diagViaProxy') : t('inv.diagViaDirect')}</strong>
              {' + '}{c.tls_mode}
              {detailed && c.source_ip && (
                <div className={cn('mt-0.5 font-mono text-xs break-all', MUTED)}>
                  {c.source_ip}:{c.source_port} → {c.peer_ip}:{c.peer_port}
                  {c.via === 'proxy' && item && ` (${t('inv.diagViaProxy')}) → ${item.domain}:${item.port || 443}`}
                </div>
              )}
              {detailed && c.via === 'proxy' && proxyAddress && (
                <div className={cn('mt-0.5 font-mono text-xs break-all', MUTED)}>{t('inv.diagProxyVia')}: {proxyAddress}</div>
              )}
              <div className={cn('mt-0.5 text-xs tabular-nums sm:hidden', MUTED)}>{c.elapsed_ms ?? '—'} ms</div>
            </TableCell>
            <TableCell className={cn(TD, 'align-top')}>{stepLabel(c.step_reached)}</TableCell>
            <TableCell className={cn(TD, 'hidden align-top tabular-nums sm:table-cell')}>{c.elapsed_ms ?? '—'}</TableCell>
            <TableCell className={cn(TD, 'align-top')}>
              <ToneBadge tone={c.status === 'ok' ? 'success' : 'danger'}>
                {c.status === 'ok' ? t('inv.diagOk') : (c.error_class || 'ERROR')}
              </ToneBadge>
              {detailed && (c.status === 'ok'
                ? <span className="ml-2 break-words">{c.subject} · {c.days_remaining}d · {c.tls_version}{c.cipher_suite ? ' · ' + c.cipher_suite : ''}{c.alpn ? ' · ALPN:' + c.alpn : ''}</span>
                : <span className="ml-2 break-words">{(c.error || '').slice(0, 120)}</span>)}
            </TableCell>
          </TableRow>
        ))}
      </TableBody>
    </DataTable>
  )
}

/** openssl protokol risk rozeti. */
function OsslRiskBadge({ p }) {
  const t = useT()
  if (!p.supported) return <ToneBadge tone="muted">{t('inv.osslDisabled')}</ToneBadge>
  return p.risk === 'HIGH'
    ? <ToneBadge tone="danger">{t('inv.osslRiskHigh')}</ToneBadge>
    : <ToneBadge tone="success">{t('inv.osslRiskOk')}</ToneBadge>
}

/** openssl sonuç gövdesi — hem canlı tarama hem geçmiş detayında kullanılır. */
export function OpensslResult({ data }) {
  const t = useT()
  if (!data) return null
  if (data.available === false) return <AlertBanner tone="warning">{t('inv.osslUnavailable')}</AlertBanner>
  const cert = data.certificate || {}
  return (
    <div data-slot="diag-openssl">
      {data.reachable === false && <AlertBanner tone="danger">{t('inv.osslUnreachable')}</AlertBanner>}
      <ShowField label={t('inv.osslVersion')} mono value={data.version || '—'} />
      <Section>{t('inv.osslProtocols')}</Section>
      <DataTable>
        <TableHeader><TableRow>
          <TableHead className={TH}>{t('inv.osslColProto')}</TableHead>
          <TableHead className={TH}>{t('inv.osslColState')}</TableHead>
          <TableHead className={TH}>{t('inv.osslColRisk')}</TableHead>
        </TableRow></TableHeader>
        <TableBody>
          {(data.protocols ?? []).map((p) => (
            <TableRow key={p.proto}>
              <TableCell className={TD}><strong>{p.proto}</strong></TableCell>
              <TableCell className={TD}>{p.supported ? t('inv.osslEnabled') : t('inv.osslDisabled')}</TableCell>
              <TableCell className={TD}><OsslRiskBadge p={p} /></TableCell>
            </TableRow>
          ))}
        </TableBody>
      </DataTable>
      <Section>{t('inv.osslNegotiated')}</Section>
      <div className={KV_GRID}>
        <ShowField label="Protocol" mono value={data.negotiated?.protocol || '—'} />
        <ShowField label="Cipher" mono value={data.negotiated?.cipher || '—'} />
      </div>
      <Section>{t('inv.osslCert')}</Section>
      <div className={KV_GRID}>
        <ShowField label="Subject" mono value={cert.subject || '—'} />
        <ShowField label="Issuer" mono value={cert.issuer || '—'} />
        <ShowField label="Not After" mono value={cert.not_after || '—'} />
        <ShowField label="Key / Sig" mono value={`${cert.key_bits ? cert.key_bits + ' bit' : '—'} · ${cert.signature_algorithm || '—'}`} />
      </div>
      {(data.flags ?? []).length > 0 && (
        <div className="my-2 flex flex-wrap gap-1.5">
          {data.flags.map((f) => (
            <ToneBadge key={f} tone="danger">
              {t('inv.osslFlag' + f.split('_').map((s) => s.charAt(0) + s.slice(1).toLowerCase()).join('')) || f}
            </ToneBadge>
          ))}
        </div>
      )}
      <Section>{t('inv.osslRaw')}</Section>
      {(data.raw ?? []).map((r, i) => (
        <Disclosure key={i} triggerClassName={cn('font-mono text-xs break-all', MUTED)} summary={<span className="min-w-0 break-all">{r.cmd}</span>}>
          <pre className={PRE}>{r.output}</pre>
        </Disclosure>
      ))}
    </div>
  )
}
