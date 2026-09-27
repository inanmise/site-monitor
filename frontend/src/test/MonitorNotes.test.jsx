import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor, within } from './test-utils.jsx'
import MonitorNotes from '../components/MonitorNotes.jsx'
import ModalShell from '../components/ui/ModalShell.jsx'

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
    // Adı notu ayırır (a11y.rowAction): "Sertifika hatası — Düzenle"
    fireEvent.click(screen.getByRole('button', { name: /sertifika hatası — (edit|düzenle)/i }))
    // Düzenleme formu açıldı ama kart AKORDİYONU açılmadı (düğme tetiğin kardeşi → toggle yok)
    await waitFor(() => expect(document.querySelector('[data-slot="note-form"]')).not.toBeNull())
    const trigger = screen.getByRole('button', { name: /sertifika hatası/i, expanded: false })
    expect(trigger).toHaveAttribute('aria-expanded', 'false')
    // Kapalı Radix içeriği boş + gizli kalır: kartta not alanları çizilmemiş olmalı
    // (düzenleme formunun Textarea'sı aynı metni taşır — kapsam KART)
    expect(within(document.querySelector('[data-slot="note-card"]')).queryByText(/kök neden detayı/)).toBeNull()
  })

  it('not formu alanları etiketli Textarea (zorunlu yıldız ayrı öğe)', async () => {
    render(<MonitorNotes type="PORT" target="10.0.0.1:25" />)
    await screen.findByText('Sertifika hatası')
    fireEvent.click(screen.getByRole('button', { name: /^(add note|not ekle)$/i }))
    const problem = await screen.findByLabelText(/^(sorun|problem)/i)
    expect(problem.tagName).toBe('TEXTAREA')
    expect(problem.getAttribute('data-slot')).toBe('textarea')
    expect(document.querySelector('[data-slot="note-form"] [data-slot="field-required"]')).not.toBeNull()
  })

  // ── Yeniden tasarım (2026-09-27): yerleşik tür rehberi, klavye gönderimi, salt okunur ─────

  it('yerleşik tür rehberi (monitorGuides) katlanır kartta: kapalı → içerik yok, açınca belge gelir', async () => {
    render(<MonitorNotes type="HTTP" target="https://a.example.com/" />)
    await screen.findByText('Sertifika hatası')
    const howto = document.querySelector('[data-slot="note-howto"]')
    expect(howto).not.toBeNull()
    const trigger = within(howto).getByRole('button', { expanded: false })
    expect(howto.textContent).not.toMatch(/example\.com/)     // kapalı: markdown DOM'da değil
    fireEvent.click(trigger)
    await waitFor(() => expect(trigger).toHaveAttribute('aria-expanded', 'true'))
    expect(howto.textContent).toMatch(/example\.com/)         // HTTP belgesi: örnek URL
  })

  it('bilinmeyen tür için yerleşik rehber çizilmez; takım rehberi ve notlar yine gelir', async () => {
    render(<MonitorNotes type="BOGUS" target="x" />)
    await screen.findByText('Sertifika hatası')
    expect(document.querySelector('[data-slot="note-howto"]')).toBeNull()
    expect(document.querySelector('[data-slot="note-guide"]')).not.toBeNull()
  })

  it('Ctrl+Enter formu kaydeder (klavye gönderimi) — sorun alanı sunucuya gider', async () => {
    api.monitoring.addMonitorNote.mockResolvedValue({ success: true, data: {} })
    render(<MonitorNotes type="PORT" target="10.0.0.1:25" />)
    await screen.findByText('Sertifika hatası')
    fireEvent.click(screen.getByRole('button', { name: /^(add note|not ekle)$/i }))
    const problem = await screen.findByLabelText(/^(sorun|problem)/i)
    fireEvent.change(problem, { target: { value: 'Yeni sorun' } })
    fireEvent.keyDown(problem, { key: 'Enter', ctrlKey: true })
    await waitFor(() => expect(api.monitoring.addMonitorNote)
      .toHaveBeenCalledWith(expect.objectContaining({ type: 'PORT', target: '10.0.0.1:25', problem: 'Yeni sorun' })))
  })

  it('canManage=false: ekle / düzenle / sil / rehberi düzenle YOK, salt okunur notu görünür', async () => {
    render(<MonitorNotes type="PORT" target="10.0.0.1:25" canManage={false} />)
    await screen.findByText('Sertifika hatası')
    expect(screen.queryByRole('button', { name: /^(add note|not ekle)$/i })).toBeNull()
    expect(screen.queryByRole('button', { name: /sertifika hatası — (edit|düzenle|delete|sil)/i })).toBeNull()
    expect(screen.queryByRole('button', { name: /^(edit|düzenle)$/i })).toBeNull()
    expect(document.querySelector('[data-slot="note-list"]').textContent).toMatch(/manage this monitor|yönetebilenler/)
  })

  // 2026-09-27 regresyon BF3: not formu 10 izleme detay penceresinde bir ModalShell (Radix Dialog) içinde çizilir.
  // Radix'in belge düzeyindeki capture dinleyicisi formun onKeyDown'undan ÖNCE koşuyordu → Escape formu değil tüm
  // pencereyi kapatıyor, yazılan not taslağı gidiyordu.
  it('ModalShell içinde: formda Escape FORMU kapatır, pencereyi kapatmaz; form kapalıyken Escape pencereyi kapatır', async () => {
    const onClose = vi.fn()
    render(<ModalShell open onClose={onClose} title="Detay"><MonitorNotes type="PORT" target="10.0.0.1:25" /></ModalShell>)
    await screen.findByText('Sertifika hatası')
    fireEvent.click(screen.getByRole('button', { name: /^(add note|not ekle)$/i }))
    const problem = await screen.findByLabelText(/^(sorun|problem)/i)
    fireEvent.change(problem, { target: { value: 'taslak' } })

    fireEvent.keyDown(problem, { key: 'Escape' })
    await waitFor(() => expect(document.querySelector('[data-slot="note-form"]')).toBeNull())
    expect(onClose).not.toHaveBeenCalled()

    // Davranış yalnız formda değişti: form kapalıyken Escape pencereyi yine kapatır.
    fireEvent.keyDown(screen.getByRole('button', { name: /^(add note|not ekle)$/i }), { key: 'Escape' })
    expect(onClose).toHaveBeenCalled()
  })

  // 2026-09-27: takım rehberi düzenleyicisinde de aynı sorun — Escape pencereyi kapatıp uzun Markdown taslağını
  // götürüyordu. Burada Escape yalnız pencerenin kapanmasını durdurur; düzenleyici AÇIK kalır (çıkış "İptal" ile).
  it('ModalShell içinde: rehber düzenlerken Escape pencereyi kapatmaz ve taslağı silmez', async () => {
    const onClose = vi.fn()
    render(<ModalShell open onClose={onClose} title="Detay"><MonitorNotes type="PORT" target="10.0.0.1:25" /></ModalShell>)
    await screen.findByText('Sertifika hatası')
    const guideCard = document.querySelector('[data-slot="note-guide"]')
    fireEvent.click(within(guideCard).getByRole('button', { name: /^(edit|düzenle)$/i }))
    const cancel = await within(guideCard).findByRole('button', { name: /^(cancel|iptal)$/i })
    expect(guideCard.querySelector('[data-guide-form]')).not.toBeNull()

    fireEvent.keyDown(cancel, { key: 'Escape' })
    expect(onClose).not.toHaveBeenCalled()
    expect(guideCard.querySelector('[data-guide-form]')).not.toBeNull()

    // Düzenleyici kapalıyken Escape pencereyi yine kapatır.
    fireEvent.click(cancel)
    await waitFor(() => expect(guideCard.querySelector('[data-guide-form]')).toBeNull())
    fireEvent.keyDown(screen.getByRole('button', { name: /^(add note|not ekle)$/i }), { key: 'Escape' })
    expect(onClose).toHaveBeenCalled()
  })
})
