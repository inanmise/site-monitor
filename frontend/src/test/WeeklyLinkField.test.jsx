import { describe, it, expect, vi } from 'vitest'
import { useState } from 'react'
import { render, screen, fireEvent, within } from './test-utils.jsx'
import LinkField, { LinkChip, LinkChipList } from '../components/weekly/WeeklyLinkField.jsx'

/**
 * Takip bağlantısı alanı (2026-09-27, kullanıcı isteği): kullanıcı bağlantıyı girer → doğrulanır → kompakt çipe
 * kapanır; açık URL bir daha satır içinde GÖRÜNMEZ (yalnız ayrıntı penceresinde + kopyala). Saklanan değer tek URL dizesi.
 */
const URL_A = 'https://jira.example.com/browse/SY-120'

function Harness({ initial = '', editable = true, onChange }) {
  const [v, setV] = useState(initial)
  return <LinkField label="Tracking Link" value={v} editable={editable} onChange={(x) => { onChange?.(x); setV(x) }} />
}

describe('LinkField — giriş → çip', () => {
  it('boşken "bağlantı yapıştır" girdisi; Enter doğrular, çipe kapanır, etiket türetilir, ham URL görünür metin DEĞİL', () => {
    const onChange = vi.fn()
    render(<Harness onChange={onChange} />)
    const input = screen.getByRole('textbox', { name: 'Tracking Link' })
    fireEvent.change(input, { target: { value: `  ${URL_A} ` } })
    fireEvent.keyDown(input, { key: 'Enter' })
    expect(onChange).toHaveBeenCalledWith(URL_A)
    expect(screen.queryByRole('textbox')).toBeNull()
    const chip = document.querySelector('[data-slot="wr-link"]')
    expect(chip).toHaveAttribute('data-kind', 'ticket')
    expect(within(chip).getByText('SY-120')).toBeInTheDocument()
    // Ham adres satır içinde hiçbir yerde metin olarak yok
    expect(screen.queryByText(URL_A)).toBeNull()
    expect(document.body.textContent).not.toContain('jira.example.com/browse')
  })

  it('geçersiz girdi (javascript:, boşluk) reddedilir: hata görünür, onChange ÇAĞRILMAZ, girdi açık kalır', () => {
    const onChange = vi.fn()
    render(<Harness onChange={onChange} />)
    const input = screen.getByRole('textbox', { name: 'Tracking Link' })
    fireEvent.change(input, { target: { value: 'javascript:alert(1)' } })
    fireEvent.keyDown(input, { key: 'Enter' })
    expect(onChange).not.toHaveBeenCalled()
    expect(input).toHaveAttribute('aria-invalid', 'true')
    expect(screen.getByText(/http:\/\/ ve https:\/\/|Only http:\/\/ and https:\/\//)).toBeInTheDocument()
    fireEvent.change(input, { target: { value: 'https://example.com/a b' } })
    fireEvent.blur(input)
    expect(onChange).not.toHaveBeenCalled()
    expect(screen.getByText(/boşluk|spaces/)).toBeInTheDocument()
  })

  it('şemasız alan adı https ile tamamlanır (✓ düğmesiyle de eklenir)', () => {
    const onChange = vi.fn()
    render(<Harness onChange={onChange} />)
    fireEvent.change(screen.getByRole('textbox', { name: 'Tracking Link' }), { target: { value: 'docs.example.com/runbook' } })
    fireEvent.click(screen.getByRole('button', { name: /Bağlantıyı ekle — Tracking Link|Add link — Tracking Link/ }))
    expect(onChange).toHaveBeenCalledWith('https://docs.example.com/runbook')
  })

  it('çip: yeni sekmede açılan bağlantı (noopener noreferrer); düzenle girdiyi URL ile açar; kaldır boşaltır', () => {
    const onChange = vi.fn()
    render(<Harness initial={URL_A} onChange={onChange} />)
    const link = screen.getByRole('link', { name: /SY-120/ })
    expect(link).toHaveAttribute('href', URL_A)
    expect(link).toHaveAttribute('target', '_blank')
    expect(link).toHaveAttribute('rel', 'noopener noreferrer')
    fireEvent.click(screen.getByRole('button', { name: /Bağlantıyı düzenle|Edit link/ }))
    const input = screen.getByRole('textbox', { name: 'Tracking Link' })
    expect(input).toHaveValue(URL_A)
    // Esc: vazgeç — çip geri gelir, değer değişmez
    fireEvent.keyDown(input, { key: 'Escape' })
    expect(onChange).not.toHaveBeenCalled()
    fireEvent.click(screen.getByRole('button', { name: /Bağlantıyı kaldır|Remove link/ }))
    expect(onChange).toHaveBeenCalledWith('')
    expect(screen.getByRole('textbox', { name: 'Tracking Link' })).toHaveValue('')
  })

  it('ayrıntı: tam adres yalnız açılır pencerede + "Bağlantıyı kopyala"', () => {
    render(<Harness initial={URL_A} />)
    expect(screen.queryByText(URL_A)).toBeNull()
    fireEvent.click(screen.getByRole('button', { name: /Bağlantı ayrıntısı|Link details/ }))
    const pop = document.querySelector('[data-slot="wr-link-details"]')
    expect(within(pop).getByText(URL_A)).toBeInTheDocument()
    expect(within(pop).getByRole('button', { name: /Bağlantıyı kopyala|Copy link/ })).toBeInTheDocument()
  })

  it('salt okunur: çip var, düzenle/kaldır YOK; boşsa "Bağlantı yok"', () => {
    const { unmount } = render(<Harness initial={URL_A} editable={false} />)
    expect(screen.getByRole('link', { name: /SY-120/ })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /Bağlantıyı düzenle|Edit link/ })).toBeNull()
    expect(screen.queryByRole('button', { name: /Bağlantıyı kaldır|Remove link/ })).toBeNull()
    unmount()
    render(<Harness initial="" editable={false} />)
    expect(screen.getByText(/Bağlantı yok|No link/)).toBeInTheDocument()
    expect(screen.queryByRole('textbox')).toBeNull()
  })

  it('eski şemasız değer: tıklanamaz uyarı çipi (bağlantı değil)', () => {
    render(<LinkChip url="jira.example.com/pm" fieldLabel="Postmortems Link" />)
    expect(screen.queryByRole('link')).toBeNull()
    expect(document.querySelector('[data-slot="wr-link"]')).toHaveAttribute('data-valid', 'false')
    expect(screen.getByText(/geçerli bir web adresi değil|not a valid web address/)).toBeInTheDocument()
  })

  it('yoğun liste: en çok 5 çip, fazlası "+N daha"', () => {
    const items = Array.from({ length: 7 }, (_, i) => ({ key: `k${i}`, label: `L${i}`, url: `https://example.com/p/item-${i}` }))
    render(<LinkChipList items={items} />)
    expect(document.querySelectorAll('[data-slot="wr-link"]').length).toBe(5)
    expect(screen.getByRole('button', { name: /\+2 (daha|more)/ })).toBeInTheDocument()
  })
})
