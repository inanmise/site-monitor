import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, fireEvent, waitFor } from './test-utils.jsx'
import CopyLinkButton from '../components/ui/CopyLinkButton.jsx'

describe('CopyLinkButton', () => {
  beforeEach(() => {
    window.history.replaceState({}, '', '/?tab=keyword&group=X')
  })
  afterEach(() => {
    window.history.replaceState({}, '', '/')
    vi.unstubAllGlobals()
  })

  it('tık → clipboard.writeText TAM window.location.href ile çağrılır + toast görünür', async () => {
    const writeText = vi.fn().mockResolvedValue()
    vi.stubGlobal('navigator', { ...navigator, clipboard: { writeText } })

    render(<CopyLinkButton />)
    fireEvent.click(screen.getByRole('button', { name: /copy link|bağlantıyı kopyala/i }))

    await waitFor(() => expect(writeText).toHaveBeenCalledWith(window.location.href))
    expect(writeText.mock.calls[0][0]).toContain('group=X')
    expect(await screen.findByText(/link copied|bağlantı kopyalandı/i)).toBeInTheDocument()
  })

  it('clipboard reddederse execCommand fallback yolu çalışır', async () => {
    vi.stubGlobal('navigator', { ...navigator, clipboard: { writeText: vi.fn().mockRejectedValue(new Error('denied')) } })
    document.execCommand = vi.fn().mockReturnValue(true)

    render(<CopyLinkButton />)
    fireEvent.click(screen.getByRole('button', { name: /copy link|bağlantıyı kopyala/i }))

    await waitFor(() => expect(document.execCommand).toHaveBeenCalledWith('copy'))
    expect(await screen.findByText(/link copied|bağlantı kopyalandı/i)).toBeInTheDocument()
  })

  it('aria-label ve title copy-link etiketiyle doğru (EN varsayılan)', () => {
    render(<CopyLinkButton iconOnly />)
    const btn = screen.getByRole('button', { name: 'Copy link' })
    expect(btn).toHaveAttribute('title', 'Copy link')
  })

  it('2026-09-27 a11y A1: targetName verilince erişilebilir ad HEDEFİ içerir (kart ızgarasında özdeş ad yok); title kısa kalır', () => {
    render(<>
      <CopyLinkButton iconOnly url="https://x.example.com/?tab=ping&monitor=1" targetName="db-1.example.com" />
      <CopyLinkButton iconOnly url="https://x.example.com/?tab=ping&monitor=2" targetName="db-2.example.com" />
    </>)
    const a = screen.getByRole('button', { name: 'db-1.example.com — Copy link' })
    expect(a).toHaveAttribute('title', 'Copy link')
    expect(screen.getByRole('button', { name: 'db-2.example.com — Copy link' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Copy link' })).toBeNull()
  })
})
