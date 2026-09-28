import { describe, it, expect, vi } from 'vitest'
import { render, screen, fireEvent } from './test-utils.jsx'
import RetentionChangeLog from '../components/admin/retention/RetentionChangeLog.jsx'
import { IssueTechDetails } from '../components/issues/IssueAdminPanel.jsx'
import { IpCopy } from '../components/admin/monitorchanges/changeParts.jsx'

vi.mock('../api/client', async (importOriginal) => ({ ...(await importOriginal()), formatDateSec: (s) => s ?? '' }))

/**
 * Kimlik izi maskesi — değişiklik geçmişi / giriş sorunu yüzeyleri (2026-09-28c ek). Sunucu (`IdentityMask`) eylemi
 * yapanın / bildirenin IP ve tarayıcısını global olmayan görüntüleyiciye HİÇ göndermez ve satırı `identity_masked: true`
 * ile işaretler. Arayüz o satırda boş / "—" yerine "Gizli" (`data-slot="id-masked"`) çizer; işaretsiz satırda IP'yi ya
 * da "kayıt yok"u aynen gösterir (yetki ile boş veri ayrı). Fixture'lar gerçek tel biçiminde (anahtar yok, null değil).
 */
const masks = (el = document) => el.querySelectorAll('[data-slot="id-masked"]')

describe('kimlik izi maskesi — geçmiş yüzeyleri', () => {
  it('Saklama değişiklik geçmişi: işaretli satır "Gizli", IP\'li satır adresi, kompakt görünümde IP hiç çizilmez', () => {
    const rows = [
      { policy_id: 'activity-log', actor: 'ADMIN', at: '2026-08-08T19:00:00', from: 90, to: 365, identity_masked: true },
      { policy_id: 'audit-log', actor: 'mudur', at: '2026-08-09T19:00:00', from: 30, to: 60, ip: '192.0.2.31' },
    ]
    const { unmount } = render(<RetentionChangeLog rows={rows} />)
    const items = document.querySelectorAll('.ret-change')
    expect(masks(items[0])).toHaveLength(1)
    expect(items[1].textContent).toContain('192.0.2.31')
    expect(masks(items[1])).toHaveLength(0)
    unmount()
    render(<RetentionChangeLog rows={rows} compact />)
    expect(masks()).toHaveLength(0)
  })

  it('Giriş sorunu teknik ayrıntı: bildirenin IP / tarayıcısı gizliyse iki alan "Gizli"; maskesizde değerler', () => {
    const { unmount } = render(<IssueTechDetails detail={{ id: 7, username: 'N12345', identity_masked: true }} />)
    fireEvent.click(screen.getByRole('button', { name: /Teknik ayrıntılar|Technical details/ }))
    expect(masks()).toHaveLength(2)
    unmount()
    render(<IssueTechDetails detail={{ id: 7, username: 'N12345', ipAddress: '198.51.100.90', userAgent: 'Mozilla/5.0 GateBrowser/4.0' }} />)
    fireEvent.click(screen.getByRole('button', { name: /Teknik ayrıntılar|Technical details/ }))
    expect(masks()).toHaveLength(0)
    expect(screen.getByText('198.51.100.90')).toBeInTheDocument()
    expect(screen.getByText('Mozilla/5.0 GateBrowser/4.0')).toBeInTheDocument()
  })

  it('İzleme Değişiklikleri IpCopy: masked + IP yok → "Gizli"; IP yok ve işaretsiz → "—"; IP varsa kopyalanabilir düğme', () => {
    const t = (k, ...a) => (a.length ? `${k}:${a.join('|')}` : k)
    const { unmount } = render(<IpCopy t={t} ip={null} masked />)
    expect(masks()).toHaveLength(1)
    unmount()
    const second = render(<IpCopy t={t} ip={null} />)
    expect(masks()).toHaveLength(0)
    expect(screen.getByText('—')).toBeInTheDocument()
    second.unmount()
    render(<IpCopy t={t} ip="192.0.2.40" masked />)   // kendi satırı: IP gelmişse gösterilir
    expect(screen.getByRole('button', { name: /192\.0\.2\.40/ })).toBeInTheDocument()
  })
})
