import { useState } from 'react'
import { createRoot } from 'react-dom/client'
import { ThemeProvider } from '../../src/i18n/theme.jsx'
import { LangProvider } from '../../src/i18n/index.jsx'
import { ToastProvider } from '../../src/components/ui/Toast.jsx'
import { DialogProvider } from '../../src/components/ui/Dialog.jsx'
import ActivityDetailPage from '../../src/components/activity/detail/ActivityDetailPage.jsx'
import '../../src/styles/globals.css'   // uygulamayla aynı kaskat: shadcn jetonları + Tailwind
import '../../src/App.css'
import '../../src/styles/themes.css'

/**
 * Aktivite ayrıntı sayfası — izole harness (bkz. activity-detail.html). Uygulamanın sağlayıcıları ve CSS kaskadı aynen;
 * veri test tarafından `/api/**` mock'uyla verilir. URL parametreleri:
 *   ?id=<n>        açılacak aktivite (varsayılan 42)
 *   ?sidebar=1     içerik kabını uygulamadaki gibi 16 rem kenar çubuğu kadar daralt (1280 px senaryosu)
 * Geri / başka aktivite / zaman çizelgesi çağrıları `window.__actNav` dizisine yazılır (test okur).
 */
const q = new URLSearchParams(location.search)
window.__actNav = []

function App() {
  const [id, setId] = useState(Number(q.get('id') || 42))
  const sidebar = q.get('sidebar') === '1'
  return (
    <div style={{ display: 'flex', minHeight: '100dvh' }}>
      {sidebar && <aside data-testid="fake-sidebar" style={{ width: '16rem', flex: '0 0 16rem', background: 'var(--sidebar, #f4f4f5)' }} />}
      <main data-testid="content" className="min-w-0 flex-1 bg-background px-3 py-4 md:px-6">
        <ActivityDetailPage id={id}
          onBack={() => window.__actNav.push(['back'])}
          onOpenActivity={(n) => { window.__actNav.push(['open', n]); setId(n) }}
          onOpenTimeline={(type, mid) => window.__actNav.push(['timeline', type, mid])} />
      </main>
    </div>
  )
}

createRoot(document.getElementById('host')).render(
  <ThemeProvider>
    <LangProvider>
      <ToastProvider>
        <DialogProvider>
          <App />
        </DialogProvider>
      </ToastProvider>
    </LangProvider>
  </ThemeProvider>,
)
