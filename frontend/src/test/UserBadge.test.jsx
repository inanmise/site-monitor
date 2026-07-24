import { describe, it, expect, vi } from 'vitest'
import { render, screen } from './test-utils'

// UserDirectory'yi mock'la: username → departman ekli display_name döndür.
vi.mock('../components/ui/UserDirectory.jsx', () => ({
  useUserDirectory: () => ({
    lookup: () => ({ id: 1, display_name: 'Erdi İnanmış (Teknoloji Servis Yönetimi Bölümü)' }),
    lookupByEmail: () => null,
  }),
}))
import UserBadge from '../components/ui/UserBadge.jsx'

describe('UserBadge nameOnly', () => {
  it('nameOnly → sondaki parantezli departman eki çıkarılır (yalnız ad-soyad)', () => {
    render(<UserBadge username="ei" inline size="sm" nameOnly />)
    expect(screen.getByText('Erdi İnanmış')).toBeInTheDocument()
    expect(screen.queryByText(/Teknoloji Servis/)).toBeNull()
  })

  it('nameOnly yok → tam display_name (departman dahil) gösterilir', () => {
    render(<UserBadge username="ei" inline size="sm" />)
    expect(screen.getByText('Erdi İnanmış (Teknoloji Servis Yönetimi Bölümü)')).toBeInTheDocument()
  })
})
