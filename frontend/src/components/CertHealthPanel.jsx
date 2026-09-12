import { useCallback, useEffect, useRef, useState } from 'react'
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
  const [confirming, setConfirming] = useState(false)
  const [open, setOpen] = useState(null)
  // "Yalnız sorunlular": liste 12+ satır ve çoğu temiz; "11/12 temiz" özeti EKSİK olanı
  // söylemiyordu, kullanıcı hangi satırın sorunlu olduğunu bulmak için hepsini geziyordu.
  const [onlyIssues, setOnlyIssues] = useState(false)
  // Uçuşan istek sayacı: yanıt döndüğünde "hâlâ bu domain mi" sorusunun cevabı.
  const seqRef = useRef(0)

  /**
   * Sağlık listesini yükler.
   *
   * <p><b>Neden sayaç ve neden `setData(null)`.</b> Modal KALICI mount'lu, yalnız `domain` prop'u
   * değişiyor. Eski hâlde domain değiştiğinde panel önceki domainin satırlarını ekranda TUTUYORDU
   * (yeni yanıt gelene kadar) ve iki yanıt yarışırsa geç dönen ESKİ domainin verisi kazanabiliyordu.
   * Sonuç: A kartını açan kullanıcı B'nin parmak izlerini/uyarısını A'ya ait sanıyor — sertifika
   * sağlığı gibi bir yüzeyde bu, yanlış domain hakkında hüküm kurdurur. Veri artık domain
   * değişiminde sıfırlanır (yükleniyor gösterilir) ve yalnız EN SON isteğin yanıtı yazılır.
   */
  const load = useCallback(async () => {
    const seq = ++seqRef.current
    setError(null)
    setData(null)
    try {
      const res = await api.getCertificateHealth(domain)
      if (seq !== seqRef.current) return
      if (res?.success) setData(res.data)
      else { setData(null); setError(res?.error || t('hlth.loadError')) }
    } catch (e) {
      if (seq !== seqRef.current) return
      setData(null)
      setError(e?.message || String(e))
    }
  }, [domain, t])

  useEffect(() => { load() }, [load])

  async function refresh() {
    const seq = seqRef.current
    setRefreshing(true)
    try {
      const res = await api.refreshCertificateHealth(domain)
      // Kullanıcı bu arada başka bir domaine geçtiyse yanıt BAŞKA bir kaydın canlı kontrolüdür.
      if (seq !== seqRef.current) return
      if (res?.success) { setData(res.data); setError(null) }
      else setError(res?.error || t('hlth.refreshError'))
    } catch (e) {
      if (seq !== seqRef.current) return
      setError(e?.message || String(e))
    } finally {
      if (seq === seqRef.current) setRefreshing(false)
    }
  }

  /**
   * "Planlı yenilemeydi" onayı — sunucu onayı kalıcılaştırır (kim/ne zaman/hangi parmak izi) ve
   * güncel listeyi döner; satır yeşile döner. Aynı yarış koruması: domain değiştiyse yanıt yazılmaz.
   */
  async function confirmRenewal() {
    const seq = seqRef.current
    setConfirming(true)
    try {
      const res = await api.confirmCertificateRenewal(domain)
      if (seq !== seqRef.current) return
      if (res?.success) { setData(res.data); setError(null) }
      else setError(res?.error || t('hlth.confirmError'))
    } catch (e) {
      if (seq !== seqRef.current) return
      setError(e?.message || String(e))
    } finally {
      if (seq === seqRef.current) setConfirming(false)
    }
  }

  if (error && !data) return <AlertBanner tone="danger" title={t('hlth.loadError')}>{error}</AlertBanner>
  if (!data) return <LoadingBlock label={t('modal.loading')} />

  const allRows = Array.isArray(data.rows) ? data.rows : []
  // "Sorunlu" = temiz OLMAYAN. UNKNOWN da dahildir: doğrulanamamış bir satır, kullanıcının
  // bakması gereken şeydir — "sorun yok" ile aynı kovaya konmamalı (bu ekranda karışık içerik
  // satırı tam olarak orada kayboluyordu).
  const isIssue = (r) => r.status !== 'OK' && r.status !== 'NA'
  const issueCount = allRows.filter(isIssue).length
  const rows = onlyIssues ? allRows.filter(isIssue) : allRows
  // Satır sırası SUNUCUDAN gelir; grup başlıkları yalnız görsel bölümlemedir.
  const byGroup = (g) => rows.filter(r => r.group === g)

  return (
    <div className="hlth-panel">
      {error && <AlertBanner tone="warning" title={t('hlth.refreshError')}>{error}</AlertBanner>}

      {/* Üst künye — geçerlilik penceresi ve kontrol zamanları tek satırda.
          İLK çip HANGİ HOST: liste, kaydın adını hiçbir yerde yazmıyordu; kalıcı mount'lu modalda
          yanlış domaine bakıp doğru sanmak bu yüzden mümkündü (parmak izi satırı bunun en pahalı
          örneği). Künye, sunucunun DÖNDÜĞÜ domaini gösterir — formdakini değil. */}
      <div className="hlth-meta">
        <span className="hlth-chip">
          <span className="hlth-chip-k">{t('hlth.host')}</span>
          <span className="hlth-chip-v">
            {data.domain || domain}{data.port && data.port !== 443 ? `:${data.port}` : ''}
          </span>
        </span>
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
        <span className="hlth-chip" title={data.check_interval_hours ? t('inv.formIntervalHint') : undefined}>
          <span className="hlth-chip-k">{t('hlth.nextCheck')}</span>
          <span className="hlth-chip-v">
            {data.next_check_at ? formatDateSec(data.next_check_at) : '—'}
            {/* Alan başına kontrol sıklığı (2026-09-12): genel saatlik zamanlamadan sapıyorsa yanında yazar. */}
            {data.check_interval_hours ? ` · ${t(`inv.interval${data.check_interval_hours}h`)}` : ''}
          </span>
        </span>

        <span className="hlth-meta-spacer" />

        {/* Özet (K7): doğrulanamayanlar paydaya girmez — "8/9" derken bilinmeyeni hata saymayız. */}
        <span className="hlth-summary">{t('hlth.summary', data.ok_count, data.evaluated_count)}</span>

        {/* Sorunluları TEK tıkla süz. Sayı düğmenin üstünde: kaç satırın ilgi beklediği,
            filtreyi açmadan da görünür. Sorun yoksa düğme hiç çizilmez — boş bir filtre
            sunmak "bir şey kaçırdım mı" sorusunu üretir. */}
        {issueCount > 0 && (
          <button type="button"
            className={`btn btn-sm hlth-filter${onlyIssues ? ' btn-primary' : ' btn-secondary'}`}
            aria-pressed={onlyIssues}
            onClick={() => setOnlyIssues(v => !v)}>
            <TriangleAlert size={13} />
            {onlyIssues ? t('hlth.showAll') : t('hlth.onlyIssues', issueCount)}
          </button>
        )}

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
                  confirming={confirming} onConfirmRenewal={confirmRenewal}
                  onToggle={() => setOpen(open === row.key ? null : row.key)} />
              ))}
            </div>
          </section>
        )
      })}
    </div>
  )
}

function HealthRow({ row, t, tlsModeUsed, expanded, onToggle, confirming = false, onConfirmRenewal }) {
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
          {cipher && <span className="hlth-cipher sys-mono">{cipher}<CopyButton value={cipher} as="span" /></span>}
        </span>

        {hasEvidence && <ChevronRight size={15} className="hlth-row-caret" aria-hidden="true" />}
      </button>

      {/* Onay düğmesi başlık <button>unun DIŞINDA: iç içe button geçersiz DOM'dur (TeamBadge dersi).
          Yalnız "değişti — planlı mıydı doğrula" durumunda çizilir; onaylanınca satır OK gelir. */}
      {row.key === 'pinnedFingerprint' && row.status === 'WARN' && row.action_key === 'confirmRenewal' && onConfirmRenewal && (
        <div className="hlth-row-cta">
          <button type="button" className="btn btn-sm btn-primary" onClick={onConfirmRenewal} disabled={confirming}>
            <ShieldCheck size={13} /> {confirming ? t('hlth.btn.confirming') : t('hlth.btn.confirmRenewal')}
          </button>
          <span className="hlth-row-cta-hint">{t('hlth.cta.confirmHint')}</span>
        </div>
      )}


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
