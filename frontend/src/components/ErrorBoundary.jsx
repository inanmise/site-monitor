import { Component, useState } from 'react'
import { currentVersion } from '../utils/appVersion.js'
import { AlertOctagon, Bug } from 'lucide-react'
import { useT } from '../i18n/index.jsx'
import IssueReportModal from './IssueReportModal.jsx'
import StatusBlock from './ui/StatusBlock.jsx'
import AlertBanner from './ui/AlertBanner.jsx'
import CopyableRef from './ui/CopyableRef.jsx'
import CopyButton from './ui/CopyButton.jsx'

/**
 * Çökme ekranı. İki kural bu yüzeyi diğerlerinden ayırır:
 *
 * 1) Kendisi ASLA patlamamalı — bunu yakalayacak bir üst sınır yok (main.jsx'te ErrorBoundary
 *    en içte), patlarsa kullanıcı beyaz ekran görür. Bu yüzden burada kullanılan her şey
 *    provider'sız da çalışır: useT çalışan varsayılana düşer, CopyableRef toast kullanmaz.
 * 2) DÜRÜST olmalı — otomatik bildirim gitmediyse bunu söylemeli. Eskiden üç sessiz catch
 *    vardı ve kullanıcı "bildirildi" satırının yokluğundan durumu çıkarmak zorundaydı.
 *
 * Ekranda TEK bir role="alert" bulunur (kök blok). Bildirim uyarısı role="status" taşır:
 * iki alert ekran okuyucuda araya girer, ayrıca getByRole('alert') sorguları çoğullaşırdı.
 */
function ErrorFallback({ onReload, errorText, reportRef, reportState }) {
  const t = useT()
  const [reportOpen, setReportOpen] = useState(false)
  const reportFailed = reportState === 'failed'

  return (
    <StatusBlock
      tone="danger"
      role="alert"
      icon={AlertOctagon}
      title={t('err.title')}
      description={t('err.detail')}
      actions={
        <>
          {/* Bildirim gidemediyse kullanıcının yapabileceği tek şey elle bildirmek —
              o buton öne çıkar, yenileme ikincil kalır. */}
          <button type="button" className={reportFailed ? 'btn' : 'btn btn-primary'} onClick={onReload}>
            {t('err.reload')}
          </button>
          {/* Otomatik bildirim gittiyse bu buton AYNI kayda kullanıcı bağlamı ekler
              (linkedReference ile bağlanır — mükerrer çökme kaydı AÇILMAZ). */}
          <button type="button" className={reportFailed ? 'btn btn-primary' : 'btn'}
                  onClick={() => setReportOpen(true)}>
            <Bug size={15} style={{ marginRight: 6 }} />
            {t('err.reportBtn')}
          </button>
        </>
      }
    >
      <IssueReportModal open={reportOpen} onClose={() => setReportOpen(false)}
                        errorText={errorText} linkedReference={reportRef} />

      {reportState === 'sent' && (
        // Bildirim başarılı — kullanıcı "yöneticiye ilettim mi?" diye uğraşmasın.
        // Referans kopyalanabilir: çökmüş bir ekrandan not almanın tek pratik yolu.
        <div className="eb-reported">
          {t('err.reported')}
          {reportRef && (
            <> · <CopyableRef value={reportRef} copyLabel={t('err.copyRef')} copiedLabel={t('err.copied')} /></>
          )}
        </div>
      )}

      {reportFailed && (
        <div className="eb-report-failed">
          <AlertBanner tone="warning" role="status">{t('err.reportFailed')}</AlertBanner>
        </div>
      )}

      {errorText && (
        // Gerçek hata mesajı + component stack'i göster (devtools açmadan tanı).
        //
        // Kopyalama butonu ŞART: bu metin çoğu zaman birkaç ekran boyu stack trace ve kullanıcının
        // onu iletmesinin tek yolu elle seçmek. Çökmüş bir ekranda uzun bir <pre>'yi fareyle
        // seçmek pratikte işlemiyor. CopyButton bilinçli: CopyableRef değeri ekranda TEKRAR yazar
        // (kısa referans kodları için), burada metin zaten <pre> içinde. İkisi de toast KULLANMAZ —
        // bu yüzey provider'sız da çalışmak zorunda (dosya başındaki 1. kural).
        <details className="eb-details">
          <summary className="eb-details-summary">
            <span>{t('err.details')}</span>
            {/* Butona tıklamak <details>'i açıp kapatmasın diye olay burada durdurulur. */}
            <span className="eb-details-copy"
                  onClick={e => { e.preventDefault(); e.stopPropagation() }}>
              <CopyButton value={errorText} label={t('err.copyError')} copiedLabel={t('err.copied')} />
            </span>
          </summary>
          <pre className="eb-pre">{errorText}</pre>
        </details>
      )}
    </StatusBlock>
  )
}

// Dinamik import / kod-bölme chunk'ı yüklenemedi mi? (deploy sonrası bayat bundle:
// tarayıcı eski index.html'deki hash'li chunk'ı ister → 404 → ChunkLoadError). Lazy
// sekmeler (Olaylar, Admin, Raporlar…) bu yüzden "Bir şey ters gitti" verebilir.
const RELOAD_AT_KEY = 'eb-chunk-reloaded-at'
const RELOAD_LOOP_MS = 15000   // bu süre içinde 2. chunk hatası → döngü; yenileme, fallback göster
function isChunkLoadError(error) {
  const name = error?.name || ''
  const msg = error?.message || ''
  return name === 'ChunkLoadError'
    || /Loading chunk|Loading CSS chunk|dynamically imported module|Importing a module script failed|Failed to fetch dynamically imported/i.test(msg)
}

// Otomatik bildirim dedupe'u: aynı hata imzası oturumda bir kez, toplamda oturum başına en çok 3
// bildirim (render döngüsündeki bir çökme sunucuyu bombalamasın; sunucuda ayrıca IP rate-limit var).
const REPORT_SIGS_KEY = 'eb-reported-sigs'
const MAX_REPORTS_PER_SESSION = 3

export default class ErrorBoundary extends Component {
  // reportState: 'idle' | 'sending' | 'sent' | 'failed'
  // 'idle' = bildirim hiç denenmedi (chunk hatası, dedupe ya da oturum limiti). Kullanıcıya
  // "bunu zaten bildirdik" demek gürültü olurdu; yalnız gerçekten denenip BAŞARISIZ olan
  // durumda uyarı gösterilir.
  state = { hasError: false, errorText: '', reportRef: '', reportState: 'idle' }

  static getDerivedStateFromError(error) {
    const msg = error?.stack || (error?.message ? `${error.name}: ${error.message}` : String(error))
    return { hasError: true, errorText: msg }
  }

  componentDidCatch(error, info) {
    const stack = info?.componentStack || ''
    console.error('[ErrorBoundary]', error, stack)
    // Bayat bundle → chunk yüklenemedi: bu oturumda bir kez zorla tam yenile (sonsuz
    // döngü olmasın diye sessionStorage bayrağı ile korunur). Kalıcı hata ise fallback kalır.
    if (isChunkLoadError(error)) {
      let last = 0
      try { last = Number(sessionStorage.getItem(RELOAD_AT_KEY)) || 0 } catch { /* yoksay */ }
      if (Date.now() - last > RELOAD_LOOP_MS) {   // yakın zamanda yenilemediysek: tam yenile
        try { sessionStorage.setItem(RELOAD_AT_KEY, String(Date.now())) } catch { /* yoksay */ }
        window.location.reload()
        return
      }
    }
    // Component stack'i de fallback'te göster (hangi bileşende patladığı görünür).
    if (stack) {
      this.setState(s => ({ errorText: `${s.errorText}\n\nComponent stack:${stack}` }))
    }
    // Chunk hatası deploy artefaktıdır (yenileme çözer) — yöneticiye bildirilmez.
    if (!isChunkLoadError(error)) {
      this.reportError(error, stack)
    }
  }

  /** Çökmeyi sunucuya bildirir (kayıt + admin maili) — best-effort, hiçbir durumda throw etmez. */
  reportError(error, componentStack) {
    let sigs = []
    try { sigs = JSON.parse(sessionStorage.getItem(REPORT_SIGS_KEY)) || [] } catch { /* yoksay */ }
    const sig = String(error?.message || error).slice(0, 200)
    if (sigs.includes(sig) || sigs.length >= MAX_REPORTS_PER_SESSION) return
    try { sessionStorage.setItem(REPORT_SIGS_KEY, JSON.stringify([...sigs, sig])) } catch { /* yoksay */ }
    const errorText = [error?.stack || String(error), componentStack ? `\nComponent stack:${componentStack}` : '']
      .join('').slice(0, 10000)
    this.setState({ reportState: 'sending' })
    try {
      // api/client bilinçli kullanılmıyor: 401-redirect mantığı çökme anında araya girmesin diye çıplak fetch.
      fetch('/api/client-error-report', {
        method: 'POST',
        credentials: 'include',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          errorText,
          url: window.location.href,
          // Sunucu bu iki alanı zaten okuyor (ClientErrorController → ReportMeta) ama istemci
          // hiç göndermiyordu, yani CLIENT_ERROR kayıtları sürüm/ekran bilgisi olmadan düşüyordu.
          // Sözleşme değişikliği değil: uç @RequestBody Map alıyor, mevcut alanlar dolduruluyor.
          // userAgent GÖNDERİLMEZ — sunucu onu HTTP başlığından okuyor.
          appVersion: currentVersion(),
          screenSize: `${window.screen?.width || 0}x${window.screen?.height || 0}`,
        }),
      })
        .then(r => (r.ok ? r.json() : null))
        .then(d => {
          // Sunucu 4xx/5xx döndüyse d null'dır: bu da başarısızlıktır, sessizce yutulmaz.
          if (!d) { this.setState({ reportState: 'failed' }); return }
          this.setState({ reportState: 'sent', reportRef: d.reference || '' })
        })
        .catch(() => { this.setState({ reportState: 'failed' }) })
    } catch {
      this.setState({ reportState: 'failed' })
    }
  }

  handleReload = () => {
    this.setState({ hasError: false, errorText: '', reportRef: '', reportState: 'idle' })
    if (typeof this.props.onReload === 'function') {
      this.props.onReload()
    } else {
      window.location.reload()
    }
  }

  render() {
    if (this.state.hasError) {
      return (
        <ErrorFallback
          onReload={this.handleReload}
          errorText={this.state.errorText}
          reportRef={this.state.reportRef}
          reportState={this.state.reportState}
        />
      )
    }
    return this.props.children
  }
}
