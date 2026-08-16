import { describe, it, expect, vi } from 'vitest'
import { render, screen, fireEvent } from '@testing-library/react'
import { LangProvider } from '../i18n/index.jsx'
import MonitorCardActions from '../components/MonitorCardActions.jsx'

/**
 * KART EYLEM DÜĞMELERİ (Kontrol Et / Düzenle / Kopyala) — beş izleme sayfasında birebir
 * kopyalanmıştı ve <b>hiçbiri test edilmiyordu</b>. Çıkarımdan önce ölçüldü: sarmalayıcıdaki
 * {@code stopPropagation} kaldırıldığında 813 testin HİÇBİRİ kırılmadı.
 *
 * O çağrı olmadan kart gövdesinin kendi {@code onClick}'i de tetiklenir: "Düzenle"ye basan
 * kullanıcı hem düzenleme formunu hem detay modalını açar, üst üste iki pencere görür. Beş
 * sayfada birden yaşayan, gözle bakmadan fark edilmeyen bir ayrıntıydı.
 */
const setup = (props = {}) => {
  const onCheck = vi.fn(); const onEdit = vi.fn(); const onDuplicate = vi.fn(); const onCardClick = vi.fn()
  render(
    <LangProvider>
      {/* Gerçek kullanımdaki gibi: eylemler TIKLANABİLİR bir kart gövdesinin içinde */}
      <div onClick={onCardClick}>
        <MonitorCardActions
          checking={null} monitorId={7}
          onCheck={onCheck} onEdit={onEdit} onDuplicate={onDuplicate}
          checkTitle="Kontrol Et" editTitle="Düzenle" {...props} />
      </div>
    </LangProvider>)
  return { onCheck, onEdit, onDuplicate, onCardClick }
}

const btn = (cls, i = 0) => document.querySelectorAll(`.${cls}`)[i]

describe('MonitorCardActions', () => {
  it('üç düğme de çizilir ve kendi geri çağrılarını tetikler', () => {
    const { onCheck, onEdit, onDuplicate } = setup()
    fireEvent.click(btn('mon-btn-check'))
    fireEvent.click(btn('mon-btn-edit', 0))
    fireEvent.click(btn('mon-btn-edit', 1))
    expect(onCheck).toHaveBeenCalledTimes(1)
    expect(onEdit).toHaveBeenCalledTimes(1)
    expect(onDuplicate).toHaveBeenCalledTimes(1)
  })

  it('DÜĞME TIKLAMASI kart gövdesine SIZMAZ (mutasyonla test edilen dal)', () => {
    // Sızarsa "Düzenle" hem formu hem detay modalını açar — üst üste iki pencere.
    const { onCardClick, onEdit } = setup()
    fireEvent.click(btn('mon-btn-edit', 0))
    expect(onEdit).toHaveBeenCalledTimes(1)
    expect(onCardClick).not.toHaveBeenCalled()
  })

  it('BU satır kontrol edilirken kontrol düğmesi devre dışı (çift tetikleme yok)', () => {
    const { onCheck } = setup({ checking: 7 })
    expect(btn('mon-btn-check').disabled).toBe(true)
    fireEvent.click(btn('mon-btn-check'))
    expect(onCheck).not.toHaveBeenCalled()
  })

  it('BAŞKA bir satır kontrol edilirken bu satırın düğmesi AÇIK kalır', () => {
    const { onCheck } = setup({ checking: 99 })
    expect(btn('mon-btn-check').disabled).toBe(false)
    fireEvent.click(btn('mon-btn-check'))
    expect(onCheck).toHaveBeenCalledTimes(1)
  })

  it('ipucu metinleri sayfadan gelir; kopyala düğmesinin erişilebilir adı vardır', () => {
    setup({ checkTitle: 'Şimdi kontrol et', editTitle: 'Kaydı düzenle' })
    expect(btn('mon-btn-check').getAttribute('title')).toBe('Şimdi kontrol et')
    expect(btn('mon-btn-edit', 0).getAttribute('title')).toBe('Kaydı düzenle')
    // Kopyala yalnız ikon içeriyor → aria-label olmazsa ekran okuyucuda isimsiz kalır
    expect(screen.getByRole('button', { name: /kopyala|duplicate/i })).toBeInTheDocument()
  })
})
