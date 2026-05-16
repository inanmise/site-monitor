import React from 'react'
import ReactDOM from 'react-dom/client'
import App from './App.jsx'
import { DialogProvider } from './components/ui/Dialog.jsx'
import { LangProvider } from './i18n/index.jsx'
import { ThemeProvider } from './i18n/theme.jsx'
import './App.css'

ReactDOM.createRoot(document.getElementById('root')).render(
  <React.StrictMode>
    <ThemeProvider>
      <LangProvider>
        <DialogProvider>
          <App />
        </DialogProvider>
      </LangProvider>
    </ThemeProvider>
  </React.StrictMode>
)
