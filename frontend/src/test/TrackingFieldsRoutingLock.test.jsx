import { describe, it, expect, vi } from 'vitest'
import { useState } from 'react'
import { render, screen, waitFor } from './test-utils.jsx'

const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
vi.mock('../api/client', () => ({
  api: withApiFallback({
    admin: { listPlatforms: vi.fn(() => Promise.resolve({ success: true, data: [] })) },
    monitoring: {
      listGroups: vi.fn(() => Promise.resolve({ success: true, data: [] })),
      listTags: vi.fn(() => Promise.resolve({ success: true, data: [] })),
    },
  }),
}))

import TrackingFields from '../components/manualcert/wizard/TrackingFields.jsx'
import { useFormErrors } from '../hooks/useFormErrors.js'

/**
 * Yüklenen sertifika sihirbazı (inceleme 5, 2026-10-12): paylaşımla YAZILABİLEN takıma yeni takip kaydı — kademe, bildirim
 * grubu ve 7/24 varsayılana çekilir ve kilitlenir (sunucu `requireSameInventoryRouting` farkı 403 ile reddeder; envanter
 * formuyla aynı kural). Kendi takımında alanlar serbest.
 */
const TEAMS = [{ id: 1, name: 'Kendi' }, { id: 7, name: 'Paylaşan', grant: true }]
const START = { team_id: '7', group_name: '', tags: '', tier: 1, description: '', owner: '', platform: '', platform_detail: '',
  notification_group_id: '5', noc_notify: true, noc_group_ids: ['2'] }

let latest = null
function Harness({ initial }) {
  const [form, setForm] = useState(initial)
  const fe = useFormErrors('x')
  latest = form
  return <TrackingFields form={form} onField={(k, v) => setForm((f) => ({ ...f, [k]: v }))} teams={TEAMS} fe={fe} />
}

describe('TrackingFields — paylaşım takımında yönlendirme kilidi', () => {
  it('paylaşım takımı seçili: kademe / grup / 7/24 varsayılana döner, alanlar kilitli, neden notu görünür', async () => {
    render(<Harness initial={START} />)
    await waitFor(() => expect(latest).toMatchObject({ tier: null, notification_group_id: '', noc_notify: false, noc_group_ids: [] }))
    expect(document.querySelector('[data-slot="mcert-routing-locked"]')).not.toBeNull()
    expect(screen.getByRole('combobox', { name: /Kritiklik|Criticality/ })).toBeDisabled()
    expect(document.querySelector('[data-slot="noc-notify-field"] [role="switch"]')).toBeDisabled()
  })

  it('kendi takımı: değerler korunur, kilit yok', async () => {
    render(<Harness initial={{ ...START, team_id: '1' }} />)
    await waitFor(() => expect(screen.getByRole('combobox', { name: /Kritiklik|Criticality/ })).not.toBeDisabled())
    expect(latest).toMatchObject({ tier: 1, notification_group_id: '5', noc_notify: true })
    expect(document.querySelector('[data-slot="mcert-routing-locked"]')).toBeNull()
  })
})
