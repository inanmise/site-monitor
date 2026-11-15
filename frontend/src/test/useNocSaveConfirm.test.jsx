// Kayıtta 7/24 onayı — kanca kuralları (inceleme 2026-11-15): seçenekler beklenirken form kapanırsa kayıt gönderilmez,
// açık soru form kapanınca "Vazgeç" ile çözülür (asılı söz / takılı meşgul bayrağı yok), pencere seçili grupları adlandırır.
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { useState } from 'react'
import { act, screen, waitFor } from '@testing-library/react'
import { render } from './test-utils.jsx'

let resolveOptions = null
vi.mock('../api/client', () => ({
  api: {
    noc: {
      groupOptions: vi.fn(() => new Promise((r) => { resolveOptions = r })),
    },
  },
}))

import { useNocSaveConfirm } from '../components/noc/forms/useNocSaveConfirm.jsx'
import { resetNocFormOptionsCache } from '../components/noc/forms/useNocFormOptions.js'
import { confirmGroupNames } from '../components/noc/forms/nocSaveConfirmModel.js'

const OPTIONS = {
  success: true,
  data: {
    groups: [
      { id: 1, name: 'Varsayılan Nöbet', is_default: true, active: true },
      { id: 2, name: 'Hafta Sonu Ekibi', is_default: false, active: true },
      { id: 3, name: 'Pasif Grup', is_default: false, active: false },
    ],
    disabled_types: [], has_active_group: true, min_level: 'CRITICAL',
  },
}

let lastAsk = null
/** Seçenek isteği mikro görevde gider — gönderilene dek bekle. */
async function optionsRequested() {
  await waitFor(() => expect(typeof resolveOptions).toBe('function'))
}

function Harness({ groupIds = null }) {
  const nocConfirm = useNocSaveConfirm()
  const [open, setOpen] = useState(true)
  return (
    <div>
      <button type="button" onClick={() => setOpen((v) => !v)}>toggle</button>
      {open && (
        <div data-testid="form">
          <button type="button" onClick={() => {
            lastAsk = nocConfirm.ask({ type: 'PING', monitorId: 5, nocNotify: false, locked: false, groupIds })
          }}>save</button>
          {nocConfirm.dialog}
        </div>
      )}
    </div>
  )
}

describe('useNocSaveConfirm', () => {
  beforeEach(() => {
    resetNocFormOptionsCache()
    resolveOptions = null
    lastAsk = null
    try { localStorage.clear() } catch { /* yok */ }
  })

  it('seçenekler beklenirken form kapanırsa ask null döner (kayıt gönderilmez)', async () => {
    render(<Harness />)
    act(() => { screen.getByText('save').click() })
    await optionsRequested()
    act(() => { screen.getByText('toggle').click() })   // form kapandı
    await act(async () => { resolveOptions(OPTIONS) })
    await expect(lastAsk).resolves.toBeNull()
    expect(document.querySelector('[data-slot="noc-save-confirm"]')).toBeNull()
  })

  it('seçenek beklenirken form kapanıp yeniden açılırsa eski soru yeni formda çıkmaz', async () => {
    render(<Harness />)
    act(() => { screen.getByText('save').click() })
    await optionsRequested()
    act(() => { screen.getByText('toggle').click() })
    act(() => { screen.getByText('toggle').click() })   // yeni form
    await act(async () => { resolveOptions(OPTIONS) })
    await expect(lastAsk).resolves.toBeNull()
    expect(document.querySelector('[data-slot="noc-save-confirm"]')).toBeNull()
  })

  it('açık soru form kapanınca Vazgeç ile çözülür; sonraki kayıt yeniden sorulabilir (meşgul bayrağı takılı kalmaz)', async () => {
    render(<Harness />)
    act(() => { screen.getByText('save').click() })
    await optionsRequested()
    await act(async () => { resolveOptions(OPTIONS) })
    await waitFor(() => expect(document.querySelector('[data-slot="noc-save-confirm"]')).not.toBeNull())
    act(() => { screen.getByText('toggle').click() })
    await expect(lastAsk).resolves.toBeNull()
    act(() => { screen.getByText('toggle').click() })
    act(() => { screen.getByText('save').click() })   // seçenekler önbellekte
    await waitFor(() => expect(document.querySelector('[data-slot="noc-save-confirm"]')).not.toBeNull())
    act(() => { document.querySelector('[data-slot="noc-confirm-yes"]').click() })
    const r = await lastAsk
    expect(r.nocNotify).toBe(true)
    expect(r.asked).toBe(true)
  })

  it('pencere formda seçili 7/24 gruplarını adlandırır; seçim yoksa varsayılanları', async () => {
    render(<Harness groupIds={[2]} />)
    act(() => { screen.getByText('save').click() })
    await optionsRequested()
    await act(async () => { resolveOptions(OPTIONS) })
    await waitFor(() => expect(document.querySelector('[data-slot="noc-confirm-groups"]')).not.toBeNull())
    const text = document.querySelector('[data-slot="noc-confirm-groups"]').textContent
    expect(text).toContain('Hafta Sonu Ekibi')
    expect(text).not.toContain('Varsayılan Nöbet')
  })

  it('confirmGroupNames: seçili etkin gruplar; pasif / bilinmeyen atlanır; boş seçim → varsayılan', () => {
    const g = OPTIONS.data.groups
    expect(confirmGroupNames(g, [2, 3, 99])).toEqual(['Hafta Sonu Ekibi'])
    expect(confirmGroupNames(g, [])).toEqual(['Varsayılan Nöbet'])
    expect(confirmGroupNames(g, null)).toEqual(['Varsayılan Nöbet'])
    expect(confirmGroupNames(g, ['2'])).toEqual(['Hafta Sonu Ekibi'])
    expect(confirmGroupNames(null, [2])).toEqual([])
  })
})
