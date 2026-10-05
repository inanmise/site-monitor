import React from 'react'
import ReactDOM from 'react-dom/client'
import App from './App.jsx'
import ErrorBoundary from './components/ErrorBoundary.jsx'
import { DialogProvider } from './components/ui/Dialog.jsx'
import { ToastProvider } from './components/ui/Toast.jsx'
import { LangProvider, preloadStoredLanguage } from './i18n/index.jsx'
import { LanguageBootSplash, LanguageLoadNotice } from './components/LanguageStatus.jsx'
import { ThemeProvider } from './i18n/theme.jsx'
import { BrandingProvider } from './contexts/BrandingProvider.jsx'
import { migrateStorageKeys } from './utils/migrateStorageKeys.js'
import './styles/mdEditorStyles.js'   // Markdown editörü CSS'i — editör JS'i lazy olsa da kaskadda ESKİ yerinde (öneri 22)
import './styles/globals.css'   // shadcn/ui + Tailwind (önce: App.css katmansız, üstüne biner)
import './App.css'
import './styles/themes.css'   // ek temalar (2026-10-05): YALNIZ jetonlar, şema kurallarından SONRA — sıra kaskadın parçası

// Rename storage göçü — render'dan ÖNCE senkron: Theme/Lang provider'ları localStorage'ı
// initializer'da okur; göç sonraya kalırsa kullanıcı tercihleri varsayılana dönmüş görünür.
migrateStorageKeys()
// İngilizce sözlük ayrı (lazy) chunk (2026-10-02, öneri 22): etkin dil İngilizce ise (saklı tercih 'en' ya da hiç
// tercih yok — storedLang varsayılanı 'en') isteği ilk render'ı beklemeden başlat (göç SONRASI — eski anahtardaki
// tercih de okunur). Türkçe sözlük açılış paketinde; Türkçe kullanıcı için ek istek yok.
preloadStoredLanguage()

ReactDOM.createRoot(document.getElementById('root')).render(
  <React.StrictMode>
    <ThemeProvider>
      {/* fallback: saklı dil İngilizce ve sözlüğü henüz inmemişken (genellikle bir an) uygulama yerine açılış ekranı */}
      <LangProvider fallback={<LanguageBootSplash />}>
        {/* Branding App'in ÜSTÜNDE: login sayfası da markalanır (public /api/branding, auth öncesi). */}
        <BrandingProvider>
          <ToastProvider>
            {/* Dil sözlüğü indirilemezse (arayüz Türkçe kalır) bildirim — ToastProvider'ın içinde olmalı */}
            <LanguageLoadNotice />
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
