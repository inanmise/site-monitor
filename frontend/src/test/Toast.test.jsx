import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, fireEvent, act } from '@testing-library/react'
import { ToastProvider, useToast } from '../components/ui/Toast.jsx'

function Trigger({ kind = 'success', message = 'Hello', duration }) {
  const toast = useToast()
  return (
    <button
      onClick={() =>
        kind === 'success' ? toast.success(message, duration)
        : kind === 'error' ? toast.error(message, duration)
        : toast.info(message, duration)
      }
    >
      Show
    </button>
  )
}

describe('Toast', () => {
  beforeEach(() => {
    vi.useFakeTimers()
  })
  afterEach(() => {
    vi.useRealTimers()
  })

  it('shows a success toast when triggered', () => {
    render(
      <ToastProvider>
        <Trigger message="Saved" />
      </ToastProvider>
    )
    fireEvent.click(screen.getByText('Show'))
    expect(screen.getByText('Saved')).toBeDefined()
    expect(document.querySelector('.toast-success')).toBeTruthy()
  })

  it('auto-dismisses success toast after 3.5 seconds', () => {
    render(
      <ToastProvider>
        <Trigger message="Bye" />
      </ToastProvider>
    )
    fireEvent.click(screen.getByText('Show'))
    expect(screen.queryByText('Bye')).toBeDefined()

    act(() => { vi.advanceTimersByTime(3600) })
    expect(screen.queryByText('Bye')).toBeNull()
  })

  it('auto-dismisses error toast after 5 seconds (longer for errors)', () => {
    render(
      <ToastProvider>
        <Trigger kind="error" message="Boom" />
      </ToastProvider>
    )
    fireEvent.click(screen.getByText('Show'))
    expect(screen.queryByText('Boom')).toBeDefined()

    act(() => { vi.advanceTimersByTime(3600) })
    // Still visible at 3.6s (error default 5s)
    expect(screen.queryByText('Boom')).toBeDefined()

    act(() => { vi.advanceTimersByTime(2000) })
    expect(screen.queryByText('Boom')).toBeNull()
  })

  it('dismisses immediately on toast body click', () => {
    render(
      <ToastProvider>
        <Trigger message="Click me" />
      </ToastProvider>
    )
    fireEvent.click(screen.getByText('Show'))
    const toast = document.querySelector('.toast')
    expect(toast).toBeTruthy()
    fireEvent.click(toast)
    expect(screen.queryByText('Click me')).toBeNull()
  })

  it('dismisses on close button click', () => {
    render(
      <ToastProvider>
        <Trigger message="Closable" />
      </ToastProvider>
    )
    fireEvent.click(screen.getByText('Show'))
    const closeBtn = document.querySelector('.toast-close')
    expect(closeBtn).toBeTruthy()
    fireEvent.click(closeBtn)
    expect(screen.queryByText('Closable')).toBeNull()
  })

  it('stacks multiple toasts', () => {
    render(
      <ToastProvider>
        <Trigger message="First" />
      </ToastProvider>
    )
    const btn = screen.getByText('Show')
    fireEvent.click(btn)
    fireEvent.click(btn)
    fireEvent.click(btn)
    expect(document.querySelectorAll('.toast').length).toBe(3)
  })

  it('throws if useToast is called outside ToastProvider', () => {
    function Bad() {
      useToast()
      return null
    }
    // Suppress error output noise from React
    const spy = vi.spyOn(console, 'error').mockImplementation(() => {})
    expect(() => render(<Bad />)).toThrow(/ToastProvider/)
    spy.mockRestore()
  })
})
