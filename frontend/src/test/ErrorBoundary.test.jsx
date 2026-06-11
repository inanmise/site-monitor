import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, fireEvent } from './test-utils.jsx'
import ErrorBoundary from '../components/ErrorBoundary.jsx'

function Bomb() {
  throw new Error('💥 BOOM')
}

function Safe() {
  return <div>safe-content</div>
}

describe('ErrorBoundary', () => {
  let errSpy
  beforeEach(() => {
    // React render error'unu console'a basar; test çıktısını temiz tutmak için sustur
    errSpy = vi.spyOn(console, 'error').mockImplementation(() => {})
  })
  afterEach(() => {
    errSpy.mockRestore()
  })

  it('hatasız child render edildiğinde içeriği geçirir', () => {
    render(
      <ErrorBoundary>
        <Safe />
      </ErrorBoundary>
    )
    expect(screen.getByText('safe-content')).toBeDefined()
  })

  it('child throw ettiğinde fallback UI gösterir', () => {
    render(
      <ErrorBoundary>
        <Bomb />
      </ErrorBoundary>
    )
    // LangProvider default 'en' (test'te localStorage boş)
    expect(screen.getByText(/Something went wrong/i)).toBeDefined()
    expect(screen.getByRole('button', { name: /Reload/i })).toBeDefined()
  })

  it('onReload prop verilirse fallback button onu çağırır', () => {
    const onReload = vi.fn()
    render(
      <ErrorBoundary onReload={onReload}>
        <Bomb />
      </ErrorBoundary>
    )
    fireEvent.click(screen.getByRole('button', { name: /Reload/i }))
    expect(onReload).toHaveBeenCalledOnce()
  })

  it('alert role taşır (a11y)', () => {
    render(
      <ErrorBoundary>
        <Bomb />
      </ErrorBoundary>
    )
    expect(screen.getByRole('alert')).toBeDefined()
  })
})
