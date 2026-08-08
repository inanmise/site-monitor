import React from 'react'
import ReactDOM from 'react-dom/client'
import App from './App.jsx'
import ErrorBoundary from './components/ErrorBoundary.jsx'
import { DialogProvider } from './components/ui/Dialog.jsx'
import { ToastProvider } from './components/ui/Toast.jsx'
import { LangProvider } from './i18n/index.jsx'
import { ThemeProvider } from './i18n/theme.jsx'
import { BrandingProvider } from './contexts/BrandingProvider.jsx'
import { migrateStorageKeys } from './utils/migrateStorageKeys.js'
import './App.css'

// Rename storage göçü — render'dan ÖNCE senkron: Theme/Lang provider'ları localStorage'ı
// initializer'da okur; göç sonraya kalırsa kullanıcı tercihleri varsayılana dönmüş görünür.
migrateStorageKeys()

ReactDOM.createRoot(document.getElementById('root')).render(
  <React.StrictMode>
    <ThemeProvider>
      <LangProvider>
        {/* Branding App'in ÜSTÜNDE: login sayfası da markalanır (public /api/branding, auth öncesi). */}
        <BrandingProvider>
          <ToastProvider>
            <DialogProvider>
              {/* Duyuru şeridi App'in İÇİNDE (içerik kolonunda) render edilir — burada olduğunda
                  tüm viewport'u kaplayıp sol menüdeki logonun üstüne çıkıyordu. */}
              <ErrorBoundary>
                <App />
              </ErrorBoundary>
            </DialogProvider>
          </ToastProvider>
        </BrandingProvider>
      </LangProvider>
    </ThemeProvider>
  </React.StrictMode>
)
