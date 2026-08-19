import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor } from './test-utils.jsx'
import MonitorNotes from '../components/MonitorNotes.jsx'

const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))

vi.mock('../api/client', () => ({
  formatDate: (s) => s ?? '',
  api: withApiFallback({
    monitoring: {
      getMonitorNotes:   vi.fn(),
      saveMonitorGuide:  vi.fn(),
      addMonitorNote:    vi.fn(),
      updateMonitorNote: vi.fn(),
      deleteMonitorNote: vi.fn(),
    },
  }),
}))
import { api } from '../api/client'

const note = {
  id: 1, problem: 'Sertifika hatası', action_taken: '', root_cause: 'kök neden detayı',
  refs: '', author_name: 'Ada', created_at: '2026-07-07T00:00:00', updated_at: null,
}

describe('MonitorNotes — not akordiyonu', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    api.monitoring.getMonitorNotes.mockResolvedValue({ success: true, data: { guide: null, notes: [note] } })
  })

  it('not varsayılan KAPALI (özet görünür, alanlar gizli); başlığa tıklayınca AÇILIR', async () => {
    render(<MonitorNotes type="PORT" target="10.0.0.1:25" />)
    // Yüklenince kart başlığındaki problem özeti görünür
    expect(await screen.findByText('Sertifika hatası')).toBeInTheDocument()
    // Kapalı: kök-neden gövdesi DOM'da yok
    expect(screen.queryByText(/kök neden detayı/)).toBeNull()
    // Başlığa tıkla → açılır, alanlar görünür
    fireEvent.click(screen.getByText('Sertifika hatası'))
    expect(await screen.findByText(/kök neden detayı/)).toBeInTheDocument()
  })

  it('Düzenle butonu kartı açmadan düzenleme formunu açar (toggle tetiklenmez)', async () => {
    render(<MonitorNotes type="PORT" target="10.0.0.1:25" />)
    await screen.findByText('Sertifika hatası')
    fireEvent.click(screen.getByTitle(/edit|düzenle/i))
    // Düzenleme formu açıldı ama kart AKORDİYONU açılmadı (stopPropagation → toggle yok)
    await waitFor(() => expect(document.querySelector('.mnote-form')).not.toBeNull())
    expect(document.querySelector('.mnote-card.open')).toBeNull()
    expect(document.querySelector('.mnote-card-body')).toBeNull()
  })
})
