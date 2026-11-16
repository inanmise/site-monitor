import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, fireEvent, within, waitFor } from './test-utils.jsx'
import { toIso } from '../utils/smartDateTime.js'

// Olay formu + GERÇEK tarih alanı (2026-11-17 inceleme düzeltmeleri):
//  • Ctrl/⌘+Enter, tarih alanında yazılan ama henüz kaydedilmemiş metni ("14:30", "dün") ÖNCE çözer, sonra kaydeder.
//  • Okunamayan tarih metni kaydı durdurur (fareyle "Kaydet"te de): alan önceki değeri geri getirdi, nedeni altında yazıyor.
vi.mock('../api/client', () => ({
  api: new Proxy({}, { get: () => new Proxy({}, { get: () => vi.fn(() => Promise.resolve({ success: true, data: [] })) }) }),
  formatDate: (s) => s ?? '',
  formatDateSec: (s) => s ?? '',
  formatDateOnly: (s) => s ?? '',
}))
vi.mock('../components/ui/MarkdownEditor.jsx', () => ({
  default: ({ value, onChange }) => <textarea data-slot="md-edit" value={value || ''} onChange={(e) => onChange(e.target.value)} />,
}))

import IncidentFormModal from '../components/incidenthistory/IncidentFormModal.jsx'

const L = (y, mo, d, h = 0, mi = 0) => new Date(y, mo - 1, d, h, mi, 0, 0)
const NOW = L(2026, 11, 16, 15, 42)

beforeEach(() => {
  vi.useFakeTimers({ toFake: ['Date'], shouldAdvanceTime: true })
  vi.setSystemTime(NOW)
})
afterEach(() => { vi.useRealTimers() })

function open(onSubmit) {
  render(<IncidentFormModal mode="create" initial={{}} teams={[{ id: 1, name: 'Takım A' }]} onSubmit={onSubmit} onClose={() => {}} />)
  const dlg = screen.getByRole('dialog')
  fireEvent.change(within(dlg).getByRole('textbox', { name: /^(Başlık|Title)/ }), { target: { value: 'Kesinti' } })
  fireEvent.change(within(dlg).getByRole('combobox', { name: /^(Takım|Team)/ }), { target: { value: '1' } })
  return dlg
}

describe('olay formu — tarih alanı ile kayıt', () => {
  it('Ctrl+Enter: alanda yazılı ama kaydedilmemiş "dün 09:15" önce çözülür, kayıt onu taşır', async () => {
    const onSubmit = vi.fn(async () => ({ ok: true }))
    const dlg = open(onSubmit)
    const occurred = within(dlg).getByRole('textbox', { name: /^(Oluş Zamanı|Occurred)/ })
    fireEvent.focus(occurred)
    fireEvent.change(occurred, { target: { value: 'dün' } })                // gün sözcüğü yalnız: anında kaydedilmez
    fireEvent.keyDown(occurred, { key: 'Enter', ctrlKey: true })
    await waitFor(() => expect(onSubmit).toHaveBeenCalledTimes(1))
    expect(onSubmit.mock.calls[0][0].occurred_at).toBe(toIso(L(2026, 11, 15, 0, 0)))
  })

  it('okunamayan tarih metni kaydı durdurur — fareyle Kaydet: alan önceki değere döner, neden yazılır, odak alanda', async () => {
    const onSubmit = vi.fn(async () => ({ ok: true }))
    const dlg = open(onSubmit)
    const occurred = within(dlg).getByRole('textbox', { name: /^(Oluş Zamanı|Occurred)/ })
    fireEvent.focus(occurred)
    fireEvent.change(occurred, { target: { value: '16.11.2026 10:00' } })
    fireEvent.change(occurred, { target: { value: '31.02.2026 10:00' } })
    fireEvent.blur(occurred)                                                // fareyle "Kaydet": önce odak çıkar
    fireEvent.click(dlg.querySelector('[data-action="save"]'))
    await new Promise((r) => setTimeout(r, 30))
    expect(onSubmit).not.toHaveBeenCalled()
    expect(within(dlg).getByText(/31\.02\.2026 10:00/)).toBeInTheDocument()
    expect(document.activeElement).toBe(occurred)
    // Düzeltince kaydedilir
    fireEvent.change(occurred, { target: { value: '16.11.2026 10:00' } })
    fireEvent.blur(occurred)
    fireEvent.click(dlg.querySelector('[data-action="save"]'))
    await waitFor(() => expect(onSubmit).toHaveBeenCalledTimes(1))
    expect(onSubmit.mock.calls[0][0].occurred_at).toBe(toIso(L(2026, 11, 16, 10, 0)))
  })
})
