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
 *
 * <p>Sınıf adları {@code mon-act} ailesine taşındı: eski {@code mon-btn-*} çifti {@code .btn-sm}
 * üstüne bindirilmiş, sabit paletli iki kutucuktu ve DNS sayfası aynı işi bambaşka bir stille
 * çiziyordu. Testler sınıfa değil ROLE/erişilebilir ada bakacak şekilde yeniden yazıldı —
 * bir sonraki görsel elden geçirme testleri kırmasın, davranış sabit kalsın.
 */
const setup = (props = {}) => {
  const onCheck = vi.fn(); const onEdit = vi.fn(); const onDuplicate = vi.fn(); const onCardClick = vi.fn()
  render(
    <LangProvider>
      {/* Gerçek kullanımdaki gibi: eylemler TIKLANABİLİR bir kart gövdesinin içinde */}
      <div onClick={onCardClick}>
        <MonitorCardActions
          running={false}
          onCheck={onCheck} onEdit={onEdit} onDuplicate={onDuplicate}
          checkTitle="Kontrol Et" editTitle="Düzenle" {...props} />
      </div>
    </LangProvider>)
  return { onCheck, onEdit, onDuplicate, onCardClick }
}

const byName = (re) => screen.getByRole('button', { name: re })

describe('MonitorCardActions', () => {
  it('üç düğme de çizilir ve kendi geri çağrılarını tetikler', () => {
    const { onCheck, onEdit, onDuplicate } = setup()
    fireEvent.click(byName(/^Kontrol Et$/))
    fireEvent.click(byName(/^Düzenle$/))
    fireEvent.click(byName(/kopyala|duplicate/i))
    expect(onCheck).toHaveBeenCalledTimes(1)
    expect(onEdit).toHaveBeenCalledTimes(1)
    expect(onDuplicate).toHaveBeenCalledTimes(1)
  })

  it('DÜĞME TIKLAMASI kart gövdesine SIZMAZ (mutasyonla test edilen dal)', () => {
    // Sızarsa "Düzenle" hem formu hem detay modalını açar — üst üste iki pencere.
    const { onCardClick, onEdit } = setup()
    fireEvent.click(byName(/^Düzenle$/))
    expect(onEdit).toHaveBeenCalledTimes(1)
    expect(onCardClick).not.toHaveBeenCalled()
  })

  /**
   * ÇALIŞIRKEN: düğme kilitli VE görünür biçimde meşgul.
   *
   * <p>Eskiden tek geri bildirim düğmenin grileşmesiydi; simge hâlâ ▶ duruyordu. Sayfa hızı
   * gibi 12–15 saniyelik bir kontrolde kullanıcı hiçbir şey olmadığını sanıp tekrar tıklıyordu.
   */
  it('kontrol SÜRERKEN düğme kilitli, meşgul işaretli ve süre sayacı görünür', () => {
    const { onCheck } = setup({ running: true })
    const b = byName(/kontrol ediliyor|checking/i)

    expect(b.disabled).toBe(true)
    expect(b.getAttribute('aria-busy')).toBe('true')
    fireEvent.click(b)
    expect(onCheck).not.toHaveBeenCalled()

    // Şerit: "Kontrol ediliyor… 0 sn" — ekran okuyucuya da duyurulur.
    expect(document.querySelector('.mon-running')).not.toBeNull()
    expect(document.querySelector('.mon-running-sec').textContent).toMatch(/^0\s/)
  })

  it('kontrol BİTİNCE düğme geri açılır ve şerit kaybolur', () => {
    const { onCheck } = setup({ running: false })
    expect(byName(/^Kontrol Et$/).disabled).toBe(false)
    // Şerit çalışmıyorken HİÇBİR ŞEY render etmemeli: kart yüksekliği değişirse liste zıplar.
    expect(document.querySelector('.mon-running')).toBeNull()
    fireEvent.click(byName(/^Kontrol Et$/))
    expect(onCheck).toHaveBeenCalledTimes(1)
  })

  it('ipucu metinleri sayfadan gelir; her düğmenin erişilebilir adı vardır', () => {
    setup({ checkTitle: 'Şimdi kontrol et', editTitle: 'Kaydı düzenle' })
    // Yalnız ikon içerirler → aria-label olmazsa ekran okuyucuda isimsiz kalırlar.
    expect(byName(/^Şimdi kontrol et$/).getAttribute('title')).toBe('Şimdi kontrol et')
    expect(byName(/^Kaydı düzenle$/).getAttribute('title')).toBe('Kaydı düzenle')
    expect(byName(/kopyala|duplicate/i)).toBeInTheDocument()
  })
})
