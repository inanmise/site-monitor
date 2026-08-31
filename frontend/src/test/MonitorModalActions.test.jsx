import { describe, it, expect, vi } from 'vitest'
import { render, screen, fireEvent } from '@testing-library/react'
import { LangProvider } from '../i18n/index.jsx'
import MonitorModalActions from '../components/ui/MonitorModalActions.jsx'

/**
 * DETAY MODALI EYLEM GRUBU — dokuz izleme türünde ortak başlık.
 *
 * <p>Sözleşme: modal başlığı KARTIN eylemlerini yansıtır, fazlasını değil. Bu yüzden her düğme
 * yalnız ilgili prop verildiğinde çizilir; "Sil" kartında silme olmayan yedi türde HİÇ
 * görünmemeli. Testler sınıfa değil ROLE/erişilebilir ada bakar (MonitorCardActions deseni).
 */
const setup = (props = {}) => {
  const onClose = vi.fn()
  render(
    <LangProvider>
      <MonitorModalActions checkTitle="Kontrol Et" editTitle="Düzenle" deleteTitle="Sil"
        onClose={onClose} {...props} />
    </LangProvider>
  )
  return { onClose }
}

const btn = (name) => screen.queryByRole('button', { name })

describe('MonitorModalActions', () => {
  it('yalnız VERİLEN eylemleri çizer — kapatma her zaman vardır', () => {
    const { onClose } = setup()
    expect(btn('Kontrol Et')).toBeNull()
    expect(btn('Düzenle')).toBeNull()
    expect(btn('Sil')).toBeNull()
    const close = screen.getByRole('button')          // tek düğme: kapatma
    fireEvent.click(close)
    expect(onClose).toHaveBeenCalled()
  })

  it('Çalıştır / Düzenle / Kopyala tetikleyicileri çağrılır', () => {
    const onCheck = vi.fn(); const onEdit = vi.fn(); const onDuplicate = vi.fn()
    setup({ onCheck, onEdit, onDuplicate })
    fireEvent.click(btn('Kontrol Et')); expect(onCheck).toHaveBeenCalled()
    fireEvent.click(btn('Düzenle'));    expect(onEdit).toHaveBeenCalled()
    fireEvent.click(screen.getByRole('button', { name: /kopyala|duplicate/i }))
    expect(onDuplicate).toHaveBeenCalled()
  })

  it('SİL yalnız onDelete verilince çizilir (kartında silme olmayan türlerde çıkmamalı)', () => {
    setup()
    expect(btn('Sil')).toBeNull()
    const onDelete = vi.fn()
    setup({ onDelete })
    fireEvent.click(btn('Sil'))
    expect(onDelete).toHaveBeenCalled()
  })

  it('koşarken kontrol düğmesi kilitli ve "Kontrol ediliyor" şeridi belirir', () => {
    const onCheck = vi.fn()
    setup({ onCheck, running: true })
    // Düğme koşarken erişilebilir adını "Kontrol ediliyor…"a çevirir (CheckNowButton sözleşmesi).
    const run = screen.getByRole('button', { name: /kontrol ediliyor|checking/i })
    expect(run).toBeDisabled()
    fireEvent.click(run)
    expect(onCheck).not.toHaveBeenCalled()
    const strip = screen.getByRole('status')
    expect(strip.textContent).toMatch(/[0-9]+ ?(sn|s)/i)   // saniye sayacı
  })

  it('checkDisabled kontrolü kapatır (sentetikte k6 kurulu değilse)', () => {
    const onCheck = vi.fn()
    setup({ onCheck, checkDisabled: true })
    const run = btn('Kontrol Et')
    expect(run).toBeDisabled()
    fireEvent.click(run)
    expect(onCheck).not.toHaveBeenCalled()
  })

  it('children ayraçtan ÖNCE gelir (sayfaya özgü bağlantı-kopyala düğmesi)', () => {
    setup({ children: <button type="button">Bağlantı</button> })
    const all = screen.getAllByRole('button')
    // Sıra: … children … [ayraç] kapatma. Kapatma HER ZAMAN sonuncudur.
    expect(all.at(-2)).toHaveTextContent('Bağlantı')
  })
})
