import { Component, useState } from 'react'
import { AlertOctagon, Bug } from 'lucide-react'
import { useT } from '../i18n/index.jsx'
import IssueReportModal from './IssueReportModal.jsx'

function ErrorFallback({ onReload, errorText, reportRef }) {
  const t = useT()
  const [reportOpen, setReportOpen] = useState(false)
  return (
    <div
      className="empty-state"
      role="alert"
      style={{ color: 'var(--danger)' }}
    >
      <div style={{ display: 'flex', justifyContent: 'center', marginBottom: 12 }}>
        <AlertOctagon size={36} />
      </div>
      <div style={{ fontSize: '1.25em', fontWeight: 600, marginBottom: 8 }}>
        {t('err.title')}
      </div>
      <div style={{ marginBottom: 18, color: 'var(--text-muted, #555)' }}>
        {t('err.detail')}
      </div>
      <div style={{ display: 'flex', gap: 8, justifyContent: 'center' }}>
        <button type="button" className="btn btn-primary" onClick={onReload}>
          {t('err.reload')}
        </button>
        {/* Otomatik bildirim zaten gitti; bu buton AYNI kayda kullanıcı bağlamı ekler (linkedReference
            ile bağlanır — mükerrer çökme kaydı AÇILMAZ, ikisi yönetim ekranında yan yana görünür). */}
        <button type="button" className="btn" onClick={() => setReportOpen(true)}>
          <Bug size={15} style={{ marginRight: 6 }} />
          {t('err.reportBtn')}
        </button>
      </div>
      <IssueReportModal open={reportOpen} onClose={() => setReportOpen(false)}
                        errorText={errorText} linkedReference={reportRef} />
      {reportRef && (
        // Otomatik bildirim başarılıysa referans no göster — kullanıcı "yöneticiye ilettim mi?" diye uğraşmasın.
        <div style={{ marginTop: 14, fontSize: 13, color: 'var(--text-muted, #555)' }}>
          {t('err.reported')} · <strong>{reportRef}</strong>
        </div>
      )}
      {errorText && (
        // Gerçek hata mesajı + component stack'i göster (devtools açmadan tanı) — kopyalanabilir.
        <details style={{ marginTop: 18, textAlign: 'left', maxWidth: 720, marginInline: 'auto' }}>
          <summary style={{ cursor: 'pointer', color: 'var(--text-muted, #555)', fontWeight: 600 }}>
            {t('err.details')}
          </summary>
          <pre
            style={{
              marginTop: 8, padding: 12, borderRadius: 8,
              background: 'var(--surface-2, #f5f5f5)', color: 'var(--text, #333)',
              fontSize: 12, whiteSpace: 'pre-wrap', wordBreak: 'break-word',
              maxHeight: 320, overflow: 'auto',
            }}
          >{errorText}</pre>
        </details>
      )}
    </div>
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
  state = { hasError: false, errorText: '', reportRef: '' }

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
    try {
      // api/client bilinçli kullanılmıyor: 401-redirect mantığı çökme anında araya girmesin diye çıplak fetch.
      fetch('/api/client-error-report', {
        method: 'POST',
        credentials: 'include',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ errorText, url: window.location.href }),
      })
        .then(r => (r.ok ? r.json() : null))
        .then(d => { if (d?.reference) this.setState({ reportRef: d.reference }) })
        .catch(() => { /* bildirim başarısız — fallback zaten görünüyor, sessiz geç */ })
    } catch { /* yoksay */ }
  }

  handleReload = () => {
    this.setState({ hasError: false, errorText: '', reportRef: '' })
    if (typeof this.props.onReload === 'function') {
      this.props.onReload()
    } else {
      window.location.reload()
    }
  }

  render() {
    if (this.state.hasError) {
      return <ErrorFallback onReload={this.handleReload} errorText={this.state.errorText} reportRef={this.state.reportRef} />
    }
    return this.props.children
  }
}
