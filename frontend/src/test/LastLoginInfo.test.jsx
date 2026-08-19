import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent } from './test-utils.jsx'
import { LastLoginNotice, LastLoginSummary, LastLoginPopoverLines } from '../components/LastLoginInfo.jsx'

const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))

vi.mock('../api/client', () => ({
  api: withApiFallback({}),
  // Zaman biçimlendirme kimlik fonksiyonu — test tarih formatını değil MANTIĞI doğrular.
  // Popover saniyesiz (formatDate), özet kart saniyeli (formatDateSec) kullanıyor.
  formatDate: (s) => s ?? '',
  formatDateSec: (s) => s ?? '',
}))

const INFO = {
  prev_login_at: '2026-08-10T09:00:00',
  prev_login_ip: '10.0.0.9',
  prev_login_method: 'PASSWORD',
  failed_before_login: 0,
  last_failed_at: null,
  last_failed_ip: null,
  last_failed_reason: null,
  current_login_at: '2026-08-12T11:00:00',
  current_login_method: 'PASSWORD',
  first_login: false,
}
const SUSPICIOUS = {
  ...INFO,
  failed_before_login: 3,
  last_failed_at: '2026-08-11T10:00:00',
  last_failed_ip: '10.0.0.8',
  last_failed_reason: 'BAD_PASSWORD',
}

describe('LastLoginNotice', () => {
  beforeEach(() => { sessionStorage.clear() })

  it('temiz girişte HİÇ görünmez (karar: yalnız şüpheli durumda uyar)', () => {
    const { container } = render(<LastLoginNotice info={INFO} />)
    expect(container).toBeEmptyDOMElement()
  })

  it('info yokken çökmez', () => {
    const { container } = render(<LastLoginNotice info={null} />)
    expect(container).toBeEmptyDOMElement()
  })

  it('başarısız deneme varsa uyarı gösterir: sayı + son denemenin zamanı', () => {
    render(<LastLoginNotice info={SUSPICIOUS} />)
    expect(screen.getByText(/3/)).toBeInTheDocument()
    expect(screen.getByText(/2026-08-11T10:00:00/)).toBeInTheDocument()
  })

  it('oturumda BİR kez görünür — ikinci mount (F5) tekrar göstermez', () => {
    const first = render(<LastLoginNotice info={SUSPICIOUS} />)
    expect(first.container).not.toBeEmptyDOMElement()
    first.unmount()

    const second = render(<LastLoginNotice info={SUSPICIOUS} />)
    expect(second.container).toBeEmptyDOMElement()
  })

  it('yeni giriş (yeni damga) uyarıyı tekrar gösterir', () => {
    render(<LastLoginNotice info={SUSPICIOUS} />).unmount()
    const next = render(<LastLoginNotice info={{ ...SUSPICIOUS, current_login_at: '2026-08-13T08:00:00' }} />)
    expect(next.container).not.toBeEmptyDOMElement()
  })

  it('kapatılabilir', () => {
    render(<LastLoginNotice info={SUSPICIOUS} />)
    fireEvent.click(screen.getByRole('button'))
    expect(screen.queryByText(/2026-08-11T10:00:00/)).not.toBeInTheDocument()
  })
})

describe('LastLoginSummary', () => {
  it('önceki girişi ve son başarısız denemeyi gösterir', () => {
    render(<LastLoginSummary info={SUSPICIOUS} />)
    expect(screen.getByText('2026-08-10T09:00:00')).toBeInTheDocument()   // önceki giriş (ölçü)
    expect(screen.getByText('2026-08-11T10:00:00')).toBeInTheDocument()   // son başarısız (alt şerit)
    expect(screen.getByText('3')).toBeInTheDocument()                     // deneme sayısı
    // IP'ler ikincil bilgi: ölçünün alt satırında göreli zaman/yöntemle birlikte tek metinde.
    expect(screen.getByText(/10\.0\.0\.9/)).toBeInTheDocument()
    expect(screen.getByText(/10\.0\.0\.8/)).toBeInTheDocument()
  })

  it('başarısız deneme yokken kart uyarı tonuna GEÇMEZ', () => {
    const { container } = render(<LastLoginSummary info={INFO} />)
    expect(container.querySelector('.lli-card--warn')).toBeNull()
    expect(container.querySelector('.lli-tile--warn')).toBeNull()
  })

  it('başarısız deneme varsa kart ve sayaç ölçüsü uyarı tonuna geçer', () => {
    const { container } = render(<LastLoginSummary info={SUSPICIOUS} />)
    expect(container.querySelector('.lli-card--warn')).not.toBeNull()
    expect(container.querySelector('.lli-tile--warn')).not.toBeNull()
  })

  it('ilk girişte "önceki kayıt yok" açıklaması çıkar', () => {
    // test-utils LangProvider'ı varsayılan dille (EN) kurar → metin İngilizce eşleşir.
    render(<LastLoginSummary info={{ ...INFO, prev_login_at: null, prev_login_ip: null, first_login: true }} />)
    expect(screen.getByText(/first sign-in/i)).toBeInTheDocument()
  })

  it('info yoksa hiç render edilmez (Etkinliklerim sayfası bozulmaz)', () => {
    const { container } = render(<LastLoginSummary info={null} />)
    expect(container).toBeEmptyDOMElement()
  })
})

describe('LastLoginPopoverLines', () => {
  it('iki satır gösterir; başarısız deneme varsa uyarı satırı eklenir', () => {
    render(<LastLoginPopoverLines info={SUSPICIOUS} />)
    expect(screen.getByText('2026-08-10T09:00:00')).toBeInTheDocument()
    expect(screen.getByText('2026-08-11T10:00:00')).toBeInTheDocument()
    expect(screen.getByText(/3/)).toBeInTheDocument()
  })

  it('info yoksa render edilmez', () => {
    const { container } = render(<LastLoginPopoverLines info={null} />)
    expect(container).toBeEmptyDOMElement()
  })
})
