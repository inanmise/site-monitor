import React from 'react'
import ReactDOM from 'react-dom/client'
import App from './App.jsx'
import ErrorBoundary from './components/ErrorBoundary.jsx'
import { DialogProvider } from './components/ui/Dialog.jsx'
import { ToastProvider } from './components/ui/Toast.jsx'
import { LangProvider } from './i18n/index.jsx'
import { ThemeProvider } from './i18n/theme.jsx'
import { BrandingProvider } from './contexts/BrandingProvider.jsx'
import AnnouncementBanner from './components/AnnouncementBanner.jsx'
import './App.css'

ReactDOM.createRoot(document.getElementById('root')).render(
  <React.StrictMode>
    <ThemeProvider>
      <LangProvider>
        {/* Branding App'in ÜSTÜNDE: login sayfası da markalanır (public /api/branding, auth öncesi). */}
        <BrandingProvider>
          <ToastProvider>
            <DialogProvider>
              <ErrorBoundary>
                <AnnouncementBanner />
                <App />
              </ErrorBoundary>
            </DialogProvider>
          </ToastProvider>
        </BrandingProvider>
      </LangProvider>
    </ThemeProvider>
  </React.StrictMode>
)
