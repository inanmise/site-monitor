import { render } from '@testing-library/react'
import { LangProvider } from '../i18n/index.jsx'
import { ThemeProvider } from '../i18n/theme.jsx'

function AllProviders({ children }) {
  return (
    <ThemeProvider>
      <LangProvider>{children}</LangProvider>
    </ThemeProvider>
  )
}

function renderWithProviders(ui, options = {}) {
  return render(ui, { wrapper: AllProviders, ...options })
}

export * from '@testing-library/react'
export { renderWithProviders as render }
