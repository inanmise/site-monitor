import { describe, it, expect, vi, beforeEach } from 'vitest'

// macOS: düzenleyici modülü `navigator.platform`'u yüklenirken okur — içe aktarmadan ÖNCE Mac'e çevrilir
vi.hoisted(() => {
  Object.defineProperty(window.navigator, 'platform', { value: 'MacIntel', configurable: true })
})

import { render, screen } from './test-utils.jsx'

vi.mock('../hooks/use-mobile.js', () => ({ useIsMobile: () => false }))
const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
vi.mock('../api/client', () => ({
  formatDate: (s) => String(s ?? ''),
  formatDateSec: (s) => String(s ?? ''),
  api: withApiFallback({
    admin: {
      sqlListTables: vi.fn(async () => ({ success: true, data: [] })), sqlSamples: vi.fn(async () => ({ success: true, data: [] })),
      sqlHistory: vi.fn(async () => ({ success: true, data: [] })), sqlRelations: vi.fn(async () => ({ success: true, data: { tables: [], edges: [] } })),
      getDatabaseInfo: vi.fn(async () => ({ success: false })), sqlDictionary: vi.fn(async () => ({ success: false, status: 404 })),
    },
  }),
}))
import SqlPlayground from '../components/admin/SqlPlayground.jsx'

/**
 * 3. tur bulgu 15: macOS'ta ⌘+Boşluk Spotlight'ı açar, tarayıcıya hiç ulaşmaz — öneri kısayolu ipucu Ctrl+Boşluk (düzenleyicinin
 * gerçekten yakaladığı kısayol); çalıştır / biçimlendir ⌘ ile kalır.
 */
describe('SQL düzenleyici ipucu — macOS', () => {
  beforeEach(() => { localStorage.clear() })
  it('öneri Ctrl+Boşluk, çalıştır ⌘+Enter', async () => {
    render(<SqlPlayground />)
    const status = await screen.findByText(/(⌘\+Enter runs|⌘\+Enter çalıştırır)/)
    expect(status).toHaveTextContent(/Ctrl\+(Space|Boşluk)/)
    expect(status).not.toHaveTextContent(/⌘\+(Space|Boşluk)/)
    expect(status).toHaveTextContent(/⌘\+Shift\+F/)
  })
})
