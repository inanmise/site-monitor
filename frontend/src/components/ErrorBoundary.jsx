import { Component } from 'react'
import { AlertOctagon } from 'lucide-react'
import { useT } from '../i18n/index.jsx'

function ErrorFallback({ onReload }) {
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
    </div>
  )
}

export default class ErrorBoundary extends Component {
  state = { hasError: false }

  static getDerivedStateFromError() {
    return { hasError: true }
  }

  componentDidCatch(error, info) {
    console.error('[ErrorBoundary]', error, info?.componentStack)
  }

  handleReload = () => {
    this.setState({ hasError: false })
    if (typeof this.props.onReload === 'function') {
      this.props.onReload()
    } else {
      window.location.reload()
    }
  }

  render() {
    if (this.state.hasError) {
      return <ErrorFallback onReload={this.handleReload} />
    }
    return this.props.children
  }
}
