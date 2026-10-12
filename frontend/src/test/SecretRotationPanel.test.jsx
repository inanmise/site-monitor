import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor, within } from './test-utils.jsx'

const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))

vi.mock('../api/client', () => ({
  formatDate: (s) => s ?? '',
  formatDateSec: (s) => s ?? '',
  formatDateOnly: (s) => s ?? '',
  api: withApiFallback({
    admin: {
      secretRotationStatus: vi.fn(),
      reencryptSecrets: vi.fn(),
    },
  }),
}))
import { api } from '../api/client'
import SecretRotationPanel from '../components/admin/secret/SecretRotationPanel.jsx'

/**
 * Gizli anahtar döndürme paneli (2026-10-11): aşama bandı, anahtar yuvaları, KPI kartları konum süzgeci, okunamayan
 * kayıtların TAM listesi, onaylı "Yeniden şifrele" → sonuç bandı + yeni durum, ret (409) ve yükleme hatası.
 * Kayıt adları SAHTE.
 */
const LONG_NAME = 'Ödeme Sistemleri Entegrasyon Sağlık Kontrolü — Üretim Ortamı (Bölge 2 / Yedek Hat) (#42)'

function pending() {
  return {
    key: { current_configured: true, previous_configured: true, previous_same_as_current: false },
    totals: { values: 6, current: 2, previous: 3, unreadable: 1 },
    previous_removable: false,
    can_reencrypt: true,
    locations: [
      { id: 'ldap_bind_password', label: 'LDAP bind password', column: 'ldap_settings.bind_password_enc', rows: 1, current: 0, previous: 1, unreadable: 0 },
      { id: 'smtp_password', label: 'SMTP password', column: 'smtp_settings.password_enc', rows: 1, current: 1, previous: 0, unreadable: 0 },
      { id: 'http_basic_auth', label: 'HTTP monitor — basic auth password', column: 'http_monitors.basic_auth_pass_enc', rows: 3,
        current: 1, previous: 2, unreadable: 1, unreadable_records: [LONG_NAME] },
      { id: 'push_headers', label: 'Personal push — secret request headers', column: 'app_settings.setting_value', rows: 0, current: 0, previous: 0, unreadable: 0 },
    ],
  }
}

function done() {
  const s = pending()
  return {
    ...s,
    totals: { values: 6, current: 5, previous: 0, unreadable: 1 },
    previous_removable: true,
    can_reencrypt: false,
    locations: s.locations.map((l) => ({ ...l, current: l.current + l.previous, previous: 0 })),
  }
}

beforeEach(() => {
  vi.clearAllMocks()
  api.admin.secretRotationStatus.mockResolvedValue({ success: true, data: pending() })
})

describe('SecretRotationPanel', () => {
  it('aşama bandı, yuvalar, KPI; okunamayan kayıt adı tam; değersiz konum adıyla ayrı satırda', async () => {
    const { container } = render(<SecretRotationPanel />)
    const root = await waitFor(() => {
      const el = container.querySelector('[data-slot="secret-rotation"][data-phase]')
      expect(el).not.toBeNull()
      return el
    })
    expect(root.getAttribute('data-phase')).toBe('pending')
    expect(screen.getByText('Rotation in progress: 3 values on the previous key')).toBeInTheDocument()
    expect(within(root).getByText('SITE_MONITOR_SECRET_KEY_PREVIOUS')).toBeInTheDocument()
    expect(within(root).getByText('Set — rotation in progress')).toBeInTheDocument()
    expect(root.querySelector('[data-slot="stat-item"][data-key="previous"] [data-slot="stat-value"]')).toHaveTextContent('3')
    const rows = root.querySelectorAll('[data-slot="rotation-location"]')
    expect([...rows].map((r) => r.getAttribute('data-id'))).toEqual(['ldap_bind_password', 'smtp_password', 'http_basic_auth'])
    const http = root.querySelector('[data-id="http_basic_auth"]')
    expect(http.querySelector('[data-slot="rotation-unreadable"]')).toHaveTextContent(LONG_NAME)
    expect(http.querySelector('[data-count="previous"]')).toHaveTextContent('2 previous')
    expect(root.querySelector('[data-slot="rotation-empty"]')).toHaveTextContent('Personal push — secret request headers')
  })

  it('KPI kartına dokununca konumlar o yuvaya süzülür; yeniden dokununca kalkar', async () => {
    const { container } = render(<SecretRotationPanel />)
    await waitFor(() => expect(container.querySelector('[data-slot="rotation-location"]')).not.toBeNull())
    const unread = container.querySelector('[data-slot="stat-item"][data-key="unreadable"]')
    fireEvent.click(unread)
    expect(unread).toHaveAttribute('aria-pressed', 'true')
    expect([...container.querySelectorAll('[data-slot="rotation-location"]')].map((r) => r.getAttribute('data-id')))
      .toEqual(['http_basic_auth'])
    fireEvent.click(unread)
    expect(container.querySelectorAll('[data-slot="rotation-location"]')).toHaveLength(3)
  })

  it('Yeniden şifrele: onay → API → sonuç bandı + yeni durum (done), düğme devre dışı', async () => {
    api.admin.reencryptSecrets.mockResolvedValue({ success: true, data: {
      values_rewritten: 3, rows_rewritten: 2, changed_meanwhile: 0, unreadable: 1, status: done(),
    } })
    const { container } = render(<SecretRotationPanel />)
    const btn = await screen.findByRole('button', { name: 'Re-encrypt' })
    fireEvent.click(btn)
    const dialog = await screen.findByRole('dialog')
    expect(dialog).toHaveTextContent('3 values stored under the previous key')
    fireEvent.click(within(dialog).getByRole('button', { name: 'Re-encrypt' }))
    await waitFor(() => expect(api.admin.reencryptSecrets).toHaveBeenCalledTimes(1))
    const result = await waitFor(() => {
      const el = container.querySelector('[data-slot="rotation-result"]')
      expect(el).not.toBeNull()
      return el
    })
    expect(result).toHaveTextContent('3 values re-encrypted with the current key')
    expect(result).toHaveTextContent('1 values were left as they are')
    // 1 değer hiçbir anahtarla çözülemiyor → "tüm değerler güncel anahtarda" (done) denmez (2026-10-12)
    expect(container.querySelector('[data-slot="secret-rotation"]').getAttribute('data-phase')).toBe('done_unreadable')
    expect(screen.getByRole('button', { name: 'Re-encrypt' })).toBeDisabled()
  })

  it('onay iptal edilirse istek atılmaz', async () => {
    render(<SecretRotationPanel />)
    fireEvent.click(await screen.findByRole('button', { name: 'Re-encrypt' }))
    const dialog = await screen.findByRole('dialog')
    fireEvent.click(within(dialog).getByRole('button', { name: 'Cancel' }))
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
    expect(api.admin.reencryptSecrets).not.toHaveBeenCalled()
  })

  it('ret (409) sunucu metniyle bildirilir ve durum yeniden okunur', async () => {
    api.admin.reencryptSecrets.mockResolvedValue({ success: false, code: 'REENCRYPT_RUNNING', error: 'Re-encryption is already running in another session or pod.' })
    render(<SecretRotationPanel />)
    fireEvent.click(await screen.findByRole('button', { name: 'Re-encrypt' }))
    fireEvent.click(within(await screen.findByRole('dialog')).getByRole('button', { name: 'Re-encrypt' }))
    expect(await screen.findByText('Re-encryption is already running in another session or pod.')).toBeInTheDocument()
    await waitFor(() => expect(api.admin.secretRotationStatus).toHaveBeenCalledTimes(2))
  })

  it('önceki anahtarda değer yokken düğme kapalı ve neden yazılı', async () => {
    api.admin.secretRotationStatus.mockResolvedValue({ success: true, data: {
      ...done(), key: { current_configured: true, previous_configured: false }, previous_removable: false,
      totals: { values: 5, current: 5, previous: 0, unreadable: 0 },
      locations: done().locations.map((l) => ({ ...l, unreadable: 0, unreadable_records: undefined })),
    } })
    const { container } = render(<SecretRotationPanel />)
    expect(await screen.findByText('No rotation; all values are on the current key')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Re-encrypt' })).toBeDisabled()
    expect(screen.getByText('No values are waiting on the previous key.')).toBeInTheDocument()
    expect(container.querySelector('[data-slot="secret-rotation"]').getAttribute('data-phase')).toBe('steady')
  })

  it('yükleme hatası: açıklayıcı bant + Yeniden dene', async () => {
    api.admin.secretRotationStatus.mockResolvedValueOnce({ success: false, error: 'Database is not reachable right now.' })
    render(<SecretRotationPanel />)
    expect(await screen.findByText('Database is not reachable right now.')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Try again' }))
    expect(await screen.findByText('Rotation in progress: 3 values on the previous key')).toBeInTheDocument()
  })

  it('rehber adımları açılır', async () => {
    const { container } = render(<SecretRotationPanel />)
    fireEvent.click(await screen.findByRole('button', { name: /How do I rotate the key/ }))
    const steps = await waitFor(() => {
      const el = container.querySelector('[data-slot="rotation-steps"]')
      expect(el).not.toBeNull()
      return el
    })
    expect(steps.querySelectorAll('li')).toHaveLength(4)
  })
})
