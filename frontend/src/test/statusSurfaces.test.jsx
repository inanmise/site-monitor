import { describe, it, expect, vi } from 'vitest'
import { render, screen, fireEvent, waitFor } from './test-utils.jsx'
import { ShieldAlert } from 'lucide-react'
import AlertBanner from '../components/ui/AlertBanner.jsx'
import StatusBlock from '../components/ui/StatusBlock.jsx'
import Field from '../components/ui/Field.jsx'
import CopyableRef from '../components/ui/CopyableRef.jsx'

/** Durum yüzeyi ailesi — hata & bildirim yüzeylerinin ortak sunum katmanı. */

describe('AlertBanner', () => {
  it('ton sınıfını uygular ve varsayılan rolü status\'tur', () => {
    const { container } = render(<AlertBanner tone="warning">dikkat</AlertBanner>)
    const el = container.querySelector('[data-slot="alert"]')
    expect(el.getAttribute('data-tone')).toBe('warning')
    expect(el.getAttribute('role')).toBe('status')   // shadcn Alert'in varsayılan role="alert"'i EZİLDİ
  })

  it('rol dışarıdan verilebilir (ekranda tek alert kuralı için)', () => {
    render(<AlertBanner tone="danger" role="alert">olmadı</AlertBanner>)
    expect(screen.getByRole('alert').textContent).toContain('olmadı')
  })

  it('bilinmeyen ton info\'ya düşer, patlamaz', () => {
    const { container } = render(<AlertBanner tone="zzz">x</AlertBanner>)
    expect(container.querySelector('[data-slot="alert"]').getAttribute('data-tone')).toBe('info')
  })

  it('başlık ve özel ikon render edilir', () => {
    const { container } = render(
      <AlertBanner tone="danger" title="Başlık" icon={ShieldAlert}>gövde</AlertBanner>
    )
    expect(screen.getByText('Başlık')).toBeDefined()
    expect(container.querySelector('[data-slot="alert"] > svg')).toBeTruthy()
  })

  it('onDismiss verilirse kapatma butonu etiketiyle çıkar ve çağrılır', () => {
    const onDismiss = vi.fn()
    render(<AlertBanner onDismiss={onDismiss} dismissLabel="Kapat">x</AlertBanner>)
    fireEvent.click(screen.getByRole('button', { name: 'Kapat' }))
    expect(onDismiss).toHaveBeenCalledOnce()
  })

  it('onDismiss yoksa kapatma butonu hiç render edilmez', () => {
    render(<AlertBanner>x</AlertBanner>)
    expect(screen.queryByRole('button')).toBeNull()
  })
})

describe('StatusBlock', () => {
  it('ton sınıfı ile başlık/açıklama/aksiyon render eder', () => {
    const { container } = render(
      <StatusBlock tone="danger" icon={ShieldAlert} title="Bir şey ters gitti"
        description="Ayrıntılar aşağıda" actions={<button type="button">Yenile</button>} />
    )
    expect(container.querySelector('[data-slot="empty"]').getAttribute('data-tone')).toBe('danger')
    expect(screen.getByText('Bir şey ters gitti')).toBeDefined()
    expect(screen.getByText('Ayrıntılar aşağıda')).toBeDefined()
    expect(screen.getByRole('button', { name: 'Yenile' })).toBeDefined()
  })

  it('varsayılan ton neutral — hata olmayan boş durumlar yeşil/kırmızı görünmez', () => {
    const { container } = render(<StatusBlock title="Kayıt yok" />)
    expect(container.querySelector('[data-slot="empty"]').getAttribute('data-tone')).toBe('neutral')
  })

  it('rol dışarıdan verilebilir', () => {
    render(<StatusBlock role="alert" title="patladı" />)
    expect(screen.getByRole('alert')).toBeDefined()
  })

  it('yükleme durumu: simge döner (reduced-motion muafiyetiyle)', () => {
    // App.jsx ilk yüklemede `loading` prop'u veriyor (eski `status-block--loading` sınıfı kaldırıldı).
    {
      const { container, unmount } = render(<StatusBlock icon={ShieldAlert} title="yükleniyor" role="status" loading />)
      const icon = container.querySelector('[data-slot="empty"] [data-slot="empty-icon"] svg')
      expect(icon.getAttribute('class')).toContain('animate-spin')
      expect(icon.getAttribute('class')).toContain('motion-reduce:')
      unmount()
    }
    const { container } = render(<StatusBlock icon={ShieldAlert} title="boş" />)
    expect(container.querySelector('[data-slot="empty-icon"] svg').getAttribute('class')).not.toContain('animate-spin')
  })
})

describe('Field', () => {
  it('etiketi kontrole bağlar (getByLabelText çalışır)', () => {
    render(
      <Field label="Açıklama">
        {({ id }) => <textarea id={id} defaultValue="merhaba" />}
      </Field>
    )
    expect(screen.getByLabelText('Açıklama').value).toBe('merhaba')
  })

  it('zorunluluk yıldızı ayrı bir eleman — etiket metnine gömülmez', () => {
    const { container } = render(<Field label="E-posta" required>{({ id }) => <input id={id} />}</Field>)
    const star = container.querySelector('[data-slot="field-required"]')
    expect(star).toBeTruthy()
    expect(star.textContent).toBe('*')
    // Erişilebilir ad yıldızı içerse de etiketin KENDİ metni temiz kalmalı
    expect(container.querySelector('[data-slot="field-label"]').firstChild.textContent).toBe('E-posta')
  })

  it('ipucu ve hata aria-describedby ile bağlanır, hata aria-invalid verir', () => {
    render(
      <Field label="E-posta" hint="Kurumsal adres" error="Geçersiz">
        {({ id, describedBy, invalid }) => (
          <input id={id} aria-describedby={describedBy} aria-invalid={invalid} />
        )}
      </Field>
    )
    const input = screen.getByLabelText('E-posta')
    expect(input.getAttribute('aria-invalid')).toBe('true')
    const ids = input.getAttribute('aria-describedby').split(' ')
    expect(ids.length).toBe(2)
    const texts = ids.map(i => document.getElementById(i).textContent)
    expect(texts).toContain('Kurumsal adres')
    expect(texts).toContain('Geçersiz')
  })

  it('hata yokken aria-invalid basılmaz ve describedby yalnız ipucunu gösterir', () => {
    render(
      <Field label="Ad" hint="ipucu">
        {({ id, describedBy, invalid }) => (
          <input id={id} aria-describedby={describedBy} aria-invalid={invalid} />
        )}
      </Field>
    )
    const input = screen.getByLabelText('Ad')
    expect(input.hasAttribute('aria-invalid')).toBe(false)
    expect(document.getElementById(input.getAttribute('aria-describedby')).textContent).toBe('ipucu')
  })

  it('alan hatası role="alert" TAŞIMAZ (form duyuru gürültüsü olmasın)', () => {
    render(<Field label="Ad" error="zorunlu">{({ id }) => <input id={id} />}</Field>)
    expect(screen.queryByRole('alert')).toBeNull()
    expect(screen.getByText('zorunlu')).toBeDefined()
  })
})

describe('CopyableRef', () => {
  it('değeri tek metin düğümünde tutar ve kopyalar', async () => {
    const writeText = vi.fn().mockResolvedValue(undefined)
    Object.defineProperty(navigator, 'clipboard', { writable: true, configurable: true, value: { writeText } })

    render(<CopyableRef value="LIR-2026-000077" copyLabel="Kopyala" copiedLabel="Kopyalandı" />)
    // Metin bölünmemiş olmalı — ErrorBoundary testi getByText ile arıyor
    expect(screen.getByText('LIR-2026-000077')).toBeDefined()

    fireEvent.click(screen.getByRole('button', { name: 'Kopyala' }))
    await waitFor(() => expect(writeText).toHaveBeenCalledWith('LIR-2026-000077'))
    await waitFor(() => expect(screen.getByRole('button', { name: 'Kopyalandı' })).toBeDefined())
  })

  it('kopyalama başarısızsa onay durumuna geçmez', async () => {
    Object.defineProperty(navigator, 'clipboard', {
      writable: true, configurable: true,
      value: { writeText: vi.fn().mockRejectedValue(new Error('no')) },
    })
    Object.defineProperty(document, 'execCommand', { writable: true, configurable: true, value: () => false })

    render(<CopyableRef value="X-1" copyLabel="Kopyala" copiedLabel="Kopyalandı" />)
    fireEvent.click(screen.getByRole('button', { name: 'Kopyala' }))
    await waitFor(() => expect(screen.getByRole('button', { name: 'Kopyala' })).toBeDefined())
    expect(screen.queryByRole('button', { name: 'Kopyalandı' })).toBeNull()
  })
})
