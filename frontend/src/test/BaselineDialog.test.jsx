import { describe, it, expect, vi, beforeEach } from 'vitest'
import { fireEvent, waitFor } from '@testing-library/react'
import { render } from './test-utils.jsx'
import BaselineDialog from '../components/baseline/BaselineDialog.jsx'

vi.mock('../api/client', () => ({
  api: { monitoring: {
    getHttpBaseline: vi.fn(),
    saveHttpBaseline: vi.fn(),
  } },
  formatDate: (x) => x,
  formatDateSec: (x) => x,
}))
import { api } from '../api/client'

const slot = (name, root = document) => root.querySelector(`[data-slot="${name}"]`)

const CONFIG = {
  enabled: false, direction: 'up', k_warning: 2.5, k_critical: 3.5, window: 30, min_samples: 10, season: 'none',
  sample_count: 4, defaults: true,
}

function mount(props = {}) {
  return render(<BaselineDialog monitorId={7} monitorName="https://ornek.test/" onClose={props.onClose || (() => {})}
    onSaved={props.onSaved || (() => {})} />)
}

beforeEach(() => {
  api.monitoring.getHttpBaseline.mockReset()
  api.monitoring.saveHttpBaseline.mockReset()
  api.monitoring.getHttpBaseline.mockResolvedValue({ success: true, data: CONFIG })
  api.monitoring.saveHttpBaseline.mockResolvedValue({ success: true, data: { ...CONFIG, enabled: true } })
})

describe('BaselineDialog', () => {
  it('yapılandırmayı yükler; kapalıyken parametreler gizli', async () => {
    mount()
    await waitFor(() => expect(slot('baseline-body')).toBeTruthy())
    expect(slot('baseline-enable')).toBeTruthy()
    expect(slot('baseline-window')).toBeFalsy()   // kapalı → parametre yok
  })

  it('aç → parametreler + hazırlık (taban kuruluyor) görünür', async () => {
    mount()
    await waitFor(() => expect(slot('baseline-enable')).toBeTruthy())
    fireEvent.click(document.querySelector('[role="switch"]'))
    await waitFor(() => expect(slot('baseline-window')).toBeTruthy())
    expect(slot('baseline-readiness').textContent).toMatch(/4 \/ 10/)   // kuruluyor
  })

  it('sınır dışı değer → istemci alan hatası, PUT yok', async () => {
    mount()
    await waitFor(() => expect(slot('baseline-enable')).toBeTruthy())
    fireEvent.click(document.querySelector('[role="switch"]'))
    await waitFor(() => expect(slot('baseline-k-warning')).toBeTruthy())
    fireEvent.change(slot('baseline-k-warning'), { target: { value: '0.1' } })
    fireEvent.click(slot('baseline-save'))
    await waitFor(() => expect(document.querySelector('[data-field="k_warning"][data-invalid]')).toBeTruthy())
    expect(api.monitoring.saveHttpBaseline).not.toHaveBeenCalled()
  })

  it('geçerli → PUT çağrılır, kaydedilir', async () => {
    const onSaved = vi.fn(); const onClose = vi.fn()
    mount({ onSaved, onClose })
    await waitFor(() => expect(slot('baseline-enable')).toBeTruthy())
    fireEvent.click(document.querySelector('[role="switch"]'))
    await waitFor(() => expect(slot('baseline-save')).toBeTruthy())
    fireEvent.click(slot('baseline-save'))
    await waitFor(() => expect(api.monitoring.saveHttpBaseline).toHaveBeenCalled())
    const [id, body] = api.monitoring.saveHttpBaseline.mock.calls[0]
    expect(id).toBe(7)
    expect(body).toMatchObject({ enabled: true, direction: 'up', window: 30 })
    await waitFor(() => expect(onSaved).toHaveBeenCalled())
    expect(onClose).toHaveBeenCalled()
  })

  it('sunucu alan hatası alanın altında gösterilir', async () => {
    api.monitoring.saveHttpBaseline.mockResolvedValue({ success: false, code: 'VALIDATION_FAILED', field: 'window', error: 'Pencere hatalı.' })
    mount()
    await waitFor(() => expect(slot('baseline-enable')).toBeTruthy())
    fireEvent.click(document.querySelector('[role="switch"]'))
    await waitFor(() => expect(slot('baseline-save')).toBeTruthy())
    fireEvent.click(slot('baseline-save'))
    await waitFor(() => expect(document.querySelector('[data-field="window"][data-invalid]')).toBeTruthy())
  })

  it('yükleme hatası danger bandı gösterir', async () => {
    api.monitoring.getHttpBaseline.mockResolvedValue({ success: false, error: 'okunamadı' })
    mount()
    await waitFor(() => expect(slot('baseline-load-error')).toBeTruthy())
  })
})
