import { useCallback, useEffect, useState } from 'react'
import { Check, X, HelpCircle, TriangleAlert, RefreshCw, ChevronRight, ShieldCheck, Lock, Globe } from 'lucide-react'
import { api, formatDateSec } from '../api/client'
import { useT } from '../i18n/index.jsx'
import AlertBanner from './ui/AlertBanner.jsx'
import CopyButton from './ui/CopyButton.jsx'
import { LoadingBlock } from './ui/Progress.jsx'

/**
 * Sertifika Sağlık Kontrol Listesi.
 *
 * <p><b>Burada sağlık MANTIĞI yoktur.</b> Hangi satırın hangi durumda olduğuna backend'deki tek
 * çekirdek karar verir ({@code CertificateHealthService}); bu bileşen yalnız sunar. Arayüzde
 * ikinci bir if-else zinciri yazılsaydı iki taraf zamanla ayrışır ve aynı sertifika için farklı
 * hüküm gösterilirdi.
 *
 * <p><b>Açılış ağ beklemez (K2):</b> panel kalıcı son kontrolle anında çizilir; canlı el sıkışması
 * yalnız "Şimdi kontrol et" ile koşar.
 *
 * <p><b>UNKNOWN gri çizilir, kırmızı DEĞİL:</b> kurumsal proxy arkasında OCSP/CRL erişilemez, eski
 * kayıtlarda protokol/cipher boştur. Doğrulanamayanı hata gibi göstermek yanlış alarm üretir.
 */

/** Durum → ikon + sınıf. Bilinmeyen durum nötr çizilir (sessiz boşluk olmaz). */
const STATUS_STYLE = {
  OK: { Icon: Check, cls: 'ok' },
  WARN: { Icon: TriangleAlert, cls: 'warn' },
  FAIL: { Icon: X, cls: 'fail' },
  UNKNOWN: { Icon: HelpCircle, cls: 'unknown' },
  NA: { Icon: HelpCircle, cls: 'na' },
}

/** Grup → başlık anahtarı + ikon. */
const GROUPS = [
  { key: 'certificate', Icon: ShieldCheck },
  { key: 'transport', Icon: Lock },
  { key: 'application', Icon: Globe },
]

/** Kanıt haritasında ham JSON gibi görünen alanları okunur kısaltmaya çevirir. */
function evidenceText(value) {
  if (value == null) return '—'
  const s = String(value)
  return s.length > 160 ? s.slice(0, 160) + '…' : s
}

export default function CertHealthPanel({ domain, canRefresh = true }) {
  const t = useT()
  const [data, setData] = useState(null)
  const [error, setError] = useState(null)
  const [refreshing, setRefreshing] = useState(false)
  const [open, setOpen] = useState(null)

  const load = useCallback(async () => {
    setError(null)
    try {
      const res = await api.getCertificateHealth(domain)
      if (res?.success) setData(res.data)
      else { setData(null); setError(res?.error || t('hlth.loadError')) }
    } catch (e) {
      setData(null)
      setError(e?.message || String(e))
    }
  }, [domain, t])

  useEffect(() => { load() }, [load])

  async function refresh() {
    setRefreshing(true)
    try {
      const res = await api.refreshCertificateHealth(domain)
      if (res?.success) { setData(res.data); setError(null) }
      else setError(res?.error || t('hlth.refreshError'))
    } catch (e) {
      setError(e?.message || String(e))
    } finally {
      setRefreshing(false)
    }
  }

  if (error && !data) return <AlertBanner tone="danger" title={t('hlth.loadError')}>{error}</AlertBanner>
  if (!data) return <LoadingBlock label={t('modal.loading')} />

  const rows = Array.isArray(data.rows) ? data.rows : []
  // Satır sırası SUNUCUDAN gelir; grup başlıkları yalnız görsel bölümlemedir.
  const byGroup = (g) => rows.filter(r => r.group === g)

  return (
    <div className="hlth-panel">
      {error && <AlertBanner tone="warning" title={t('hlth.refreshError')}>{error}</AlertBanner>}

      {/* Üst künye — geçerlilik penceresi ve kontrol zamanları tek satırda. */}
      <div className="hlth-meta">
        <span className="hlth-chip">
          <span className="hlth-chip-k">{t('hlth.validFrom')}</span>
          <span className="hlth-chip-v">{data.not_before ? formatDateSec(data.not_before) : '—'}</span>
        </span>
        <span className="hlth-chip">
          <span className="hlth-chip-k">{t('hlth.validUntil')}</span>
          <span className="hlth-chip-v">{data.not_after ? formatDateSec(data.not_after) : '—'}</span>
        </span>
        <span className={`hlth-chip hlth-chip--days${daysTone(data.days_remaining)}`}>
          <span className="hlth-chip-k">{t('hlth.daysRemaining')}</span>
          <span className="hlth-chip-v">
            {data.days_remaining == null ? '—' : t('hlth.daysValue', data.days_remaining)}
          </span>
        </span>
        <span className="hlth-chip">
          <span className="hlth-chip-k">{t('hlth.lastCheck')}</span>
          <span className="hlth-chip-v">{data.checked_at ? formatDateSec(data.checked_at) : '—'}</span>
        </span>
        <span className="hlth-chip">
          <span className="hlth-chip-k">{t('hlth.nextCheck')}</span>
          <span className="hlth-chip-v">{data.next_check_at ? formatDateSec(data.next_check_at) : '—'}</span>
        </span>

        <span className="hlth-meta-spacer" />

        {/* Özet (K7): doğrulanamayanlar paydaya girmez — "8/9" derken bilinmeyeni hata saymayız. */}
        <span className="hlth-summary">{t('hlth.summary', data.ok_count, data.evaluated_count)}</span>

        {canRefresh && (
          <button type="button" className="btn btn-sm btn-secondary hlth-refresh"
            onClick={refresh} disabled={refreshing}>
            <RefreshCw size={13} className={refreshing ? 'hlth-spin' : undefined} />
            {refreshing ? t('hlth.checking') : t('hlth.checkNow')}
          </button>
        )}
      </div>

      {GROUPS.map(({ key, Icon }) => {
        const groupRows = byGroup(key)
        if (groupRows.length === 0) return null
        return (
          <section key={key} className="hlth-group">
            <h4 className="hlth-group-title"><Icon size={14} /> {t('hlth.group.' + key)}</h4>
            <div className="hlth-rows">
              {groupRows.map(row => (
                <HealthRow key={row.key} row={row} t={t}
                  tlsModeUsed={data.tls_mode_used}
                  expanded={open === row.key}
                  onToggle={() => setOpen(open === row.key ? null : row.key)} />
              ))}
            </div>
          </section>
        )
      })}
    </div>
  )
}

function HealthRow({ row, t, tlsModeUsed, expanded, onToggle }) {
  const style = STATUS_STYLE[row.status] || STATUS_STYLE.UNKNOWN
  const { Icon } = style
  const evidence = row.evidence && typeof row.evidence === 'object' ? row.evidence : {}
  const hasEvidence = Object.keys(evidence).length > 0
  const noAction = row.action_key === 'none'
  const cipher = evidence.cipher_suite

  // TLS_MODE=browser: istemci 1.2'ye sabitli olabilir → anlaşılan sürüm sunucunun MAKSİMUMU
  // olmayabilir (K6). Ek el sıkışma yapmak yerine satır altına dürüst bir not koyuyoruz.
  const showTlsModeNote = row.key === 'protocol' && String(tlsModeUsed || '').toLowerCase() === 'browser'

  return (
    <div className={`hlth-row hlth-row--${style.cls}${expanded ? ' is-open' : ''}`}>
      <button type="button" className="hlth-row-head" aria-expanded={expanded}
        onClick={hasEvidence ? onToggle : undefined}
        disabled={!hasEvidence}>
        <span className={`hlth-mark hlth-mark--${style.cls}`} aria-hidden="true"><Icon size={16} /></span>

        <span className="hlth-row-main">
          <span className="hlth-row-title">{t(`hlth.row.${row.key}.title`)}</span>
          <span className="hlth-row-desc">{t(`hlth.row.${row.key}.desc`)}</span>
          {showTlsModeNote && <span className="hlth-row-note">{t('hlth.note.browserMode')}</span>}
        </span>

        <span className={`hlth-row-action${noAction ? ' is-none' : ''}`}>
          {t(`hlth.act.${row.action_key}`, ...(row.action_args || []))}
        </span>

        <span className="hlth-row-value">
          <span className={`hlth-value-badge hlth-value-badge--${style.cls}`}>
            {t(`hlth.val.${row.value_key}`, ...(row.value_args || []))}
          </span>
          {cipher && <span className="hlth-cipher sys-mono">{cipher}<CopyButton value={cipher} /></span>}
        </span>

        {hasEvidence && <ChevronRight size={15} className="hlth-row-caret" aria-hidden="true" />}
      </button>

      {expanded && hasEvidence && (
        <dl className="hlth-evidence">
          {Object.entries(evidence).map(([k, v]) => (
            <div key={k} className="hlth-evidence-row">
              <dt>{t(`hlth.ev.${k}`) === `hlth.ev.${k}` ? k : t(`hlth.ev.${k}`)}</dt>
              <dd>{evidenceText(v)}</dd>
            </div>
          ))}
        </dl>
      )}
    </div>
  )
}

/** Kalan gün çipinin tonu — eşik hükmü SUNUCUDA, bu yalnız çipin rengi. */
function daysTone(days) {
  if (days == null) return ''
  if (days < 0) return ' is-fail'
  if (days <= 7) return ' is-fail'
  if (days <= 30) return ' is-warn'
  return ' is-ok'
}
