import { describe, it, expect, vi } from 'vitest'
import { render, screen } from './test-utils'

/**
 * Envanter detay modalı — "Sorumlu Ekipler" bölümü.
 *
 * Bu bölümün hiç testi yoktu. Üç davranışı var ve üçü de sessizce bozulabilir:
 * dolu alanların listelenmesi, değerin içindeki e-postanın `mailto:` bağlantısı olması,
 * ve dördü de boşken bölümün BOŞ IZGARA yerine açık bir not göstermesi. Sonuncusu
 * bilinçli bir karardı: boş başlıktan sonra boş ızgara "veri yüklenmedi" hissi verir.
 */

vi.mock('../api/client', () => ({
  api: { admin: { getInventoryByDomain: vi.fn(async () => ({ success: true, data: null })) } },
  formatDate: (s) => s ?? '',
}))
// Markdown render'ı jsdom'da ağır ve bu iddialar onu gerektirmiyor.
vi.mock('react-markdown', () => ({ default: ({ children }) => <div>{children}</div> }))
vi.mock('remark-gfm', () => ({ default: () => {} }))

const { InventoryDetails } = await import('../components/inventory/InventoryDetails.jsx')

const BASE = {
  id: 1, domain: 'a.example.com', port: 443, team_id: 5, team_name: 'Takım A',
  tier: 2, active: true,
}

describe('InventoryDetails — Sorumlu Ekipler', () => {
  it('dolu alanlar listelenir, BOŞ alan hiç görünmez', () => {
    render(<InventoryDetails record={{
      ...BASE,
      svc_mgmt_contact: 'Ad Soyad - ad.soyad@example.com',
      iis_admin_contact: 'iis@example.com',
      app_dev_contact: '',
      waf_admin_contact: null,
    }} />)

    expect(screen.getByText(/Service Management|Servis Yönetimi/)).toBeTruthy()
    expect(screen.getByText(/IIS Admin|IISAdmin/)).toBeTruthy()
    // Boş alanların etiketi de çizilmez — "—" dolu satırlarla karışmasın.
    expect(screen.queryByText(/WAF Admin|WAFAdmin/)).toBeNull()
  })

  it('değerdeki e-posta mailto bağlantısı olur, çevresindeki metin DÜZ kalır', () => {
    render(<InventoryDetails record={{ ...BASE, svc_mgmt_contact: 'Ad Soyad - ad.soyad@example.com' }} />)

    const link = screen.getByRole('link', { name: 'ad.soyad@example.com' })
    expect(link.getAttribute('href')).toBe('mailto:ad.soyad@example.com')
    // Ad kısmı link DEĞİL: tamamını linklemek "adı tıkla" yanılgısı yaratırdı.
    expect(link.textContent).toBe('ad.soyad@example.com')
  })

  it('e-posta İÇERMEYEN değer düz metin kalır (bağlantı üretilmez)', () => {
    render(<InventoryDetails record={{ ...BASE, app_dev_contact: 'Ad Soyad (izinde)' }} />)

    expect(screen.getByText('Ad Soyad (izinde)')).toBeTruthy()
    expect(screen.queryByRole('link', { name: /Ad Soyad/ })).toBeNull()
  })

  it('DÖRDÜ de boşken açık bir not gösterilir — boş ızgara "yüklenmedi" hissi verirdi', () => {
    render(<InventoryDetails record={BASE} />)

    expect(screen.getByText(/No responsible team has been recorded|Sorumlu ekip bilgisi girilmemiş/))
      .toBeTruthy()
  })
})
