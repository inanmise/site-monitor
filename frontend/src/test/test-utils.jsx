import { render } from '@testing-library/react'
import { LangProvider } from '../i18n/index.jsx'
import { ThemeProvider } from '../i18n/theme.jsx'
import { DialogProvider } from '../components/ui/Dialog.jsx'
import { ToastProvider } from '../components/ui/Toast.jsx'

function AllProviders({ children }) {
  return (
    <ThemeProvider>
      <LangProvider>
        <ToastProvider>
          <DialogProvider>{children}</DialogProvider>
        </ToastProvider>
      </LangProvider>
    </ThemeProvider>
  )
}

function renderWithProviders(ui, options = {}) {
  return render(ui, { wrapper: AllProviders, ...options })
}

export * from '@testing-library/react'
export { renderWithProviders as render }
