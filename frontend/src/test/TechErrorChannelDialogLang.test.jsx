import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor, within } from './test-utils.jsx'
import { TEAMS } from './helpers/techErrorFixtures.js'

// Yeni kanal: varsayılan şablon dili ARAYÜZ dilinden (2026-10-17, D20) — İngilizce arayüzde "Varsayılan şablon (İngilizce)",
// Türkçe arayüzde Türkçe seçili gelir; kullanıcı değiştirebilir ve seçimi gönderilir.
const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
vi.mock('../api/client', () => ({
  formatDate: (s) => s ?? '',
  api: withApiFallback({ techErrors: { createChannel: vi.fn() } }),
}))
import { api } from '../api/client'
import { LANG_STORAGE_KEY } from '../i18n/dateLocale.js'
import ChannelDialog from '../components/techerror/ChannelDialog.jsx'

const radioOf = (source) => document.querySelector(`[data-source="${source}"] [role="radio"]`)

function draw() {
  const onSaved = vi.fn()
  render(<ChannelDialog mode="create" teams={TEAMS} channels={[]} onClose={vi.fn()} onSaved={onSaved} />)
  return onSaved
}

beforeEach(() => {
  vi.clearAllMocks()
  api.techErrors.createChannel.mockResolvedValue({ success: true, data: { id: 30, name: 'ATM' } })
})

describe('yeni kanal — varsayılan şablon dili arayüz dilinden', () => {
  it('İngilizce arayüz → İngilizce varsayılan seçili; gönderimde lang: en', async () => {
    localStorage.setItem(LANG_STORAGE_KEY, 'en')
    draw()
    const dialog = await screen.findByRole('dialog', { name: 'New channel' })
    expect(within(dialog).getByRole('radio', { name: /Default template \(English\)/ })).toBeChecked()
    expect(within(dialog).getByRole('radio', { name: /Default template \(Turkish\)/ })).not.toBeChecked()
    expect(radioOf('en')).toHaveAttribute('data-state', 'checked')
    fireEvent.change(within(dialog).getByLabelText(/Channel name/), { target: { value: 'ATM' } })
    fireEvent.click(within(dialog).getByRole('button', { name: 'Open channel' }))
    await waitFor(() => expect(api.techErrors.createChannel).toHaveBeenCalledWith({ team_id: 1, name: 'ATM', description: null, lang: 'en' }))
  })

  it('Türkçe arayüz → Türkçe varsayılan seçili; kullanıcı İngilizce seçerse o gider', async () => {
    localStorage.setItem(LANG_STORAGE_KEY, 'tr')
    draw()
    const dialog = await screen.findByRole('dialog', { name: 'Yeni kanal' })
    expect(within(dialog).getByRole('radio', { name: /Varsayılan şablon \(Türkçe\)/ })).toBeChecked()
    expect(radioOf('en')).toHaveAttribute('data-state', 'unchecked')
    fireEvent.click(within(dialog).getByRole('radio', { name: /Varsayılan şablon \(İngilizce\)/ }))
    expect(radioOf('en')).toHaveAttribute('data-state', 'checked')
    fireEvent.change(within(dialog).getByLabelText(/Kanal adı/), { target: { value: 'ATM' } })
    fireEvent.click(within(dialog).getByRole('button', { name: 'Kanalı aç' }))
    await waitFor(() => expect(api.techErrors.createChannel).toHaveBeenCalledWith({ team_id: 1, name: 'ATM', description: null, lang: 'en' }))
  })
})
