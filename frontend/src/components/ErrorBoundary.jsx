import { Component } from 'react'
import { AlertOctagon } from 'lucide-react'
import { useT } from '../i18n/index.jsx'

function ErrorFallback({ onReload, errorText }) {
  const t = useT()
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
      <button type="button" className="btn btn-primary" onClick={onReload}>
        {t('err.reload')}
      </button>
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

export default class ErrorBoundary extends Component {
  state = { hasError: false, errorText: '' }

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
  }

  handleReload = () => {
    this.setState({ hasError: false, errorText: '' })
    if (typeof this.props.onReload === 'function') {
      this.props.onReload()
    } else {
      window.location.reload()
    }
  }

  render() {
    if (this.state.hasError) {
      return <ErrorFallback onReload={this.handleReload} errorText={this.state.errorText} />
    }
    return this.props.children
  }
}
