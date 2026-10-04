import { describe, it, expect, vi } from 'vitest'
import { render, fireEvent, waitFor } from './test-utils.jsx'
import { useT } from '../i18n/index.jsx'
import { EN } from '../i18n/en.js'

vi.mock('../api/client', () => ({ api: {}, formatDate: (s) => (s ? String(s).replace('T', ' ').slice(0, 16) : '') }))
import PushLogDetail from '../components/admin/push/PushLogDetail.jsx'

/**
 * Webhook Push Gönderim Logu ↔ fırtına push'u (2026-10-04): fırtına bildirimi satırının ayrıntısı KAPSADIĞI alarmları
 * bağlantılı listeler (kapsam dışı alarm yalnız sayı); alarm düzeyi SKIPPED_STORM karar satırının ayrıntısı alarmı KAPSAYAN
 * fırtına push satırlarına bağlanır (satıra tıklamak o satırın ayrıntısını açar); henüz push yoksa "sürüyor" notu.
 */
const fill = (key, ...args) => String(EN[key] ?? key).replace(/\{(\d+)\}/g, (_, i) => String(args[Number(i)] ?? ''))

function Harness({ detail, onPick = () => {}, onOpenAlert = () => {} }) {
  const t = useT()
  return <PushLogDetail open detail={detail} t={t} onClose={() => {}} onPick={onPick} ids={[]} canRequeue={false} onOpenAlert={onOpenAlert} />
}

const BASE = { username: 'u1', display_name: 'Bir', title: 'Site Monitor', created_at: '2026-10-04T08:00:05', at: '2026-10-04T08:00:07',
  kind: 'SENT', status: 'SENT', team_id: 1, team_name: 'Takım A', batch: [] }

describe('PushLogDetail — fırtına bağları', () => {
  it('fırtına bildirimi satırı: kapsadığı alarmlar bağlantılı; tıklamak alarmı açar; kapsam dışı yalnız sayı', async () => {
    const onOpenAlert = vi.fn()
    render(<Harness onOpenAlert={onOpenAlert} detail={{ ...BASE, id: 5, trigger: 'STORM', monitor_type: 'STORM', monitor_name: 'Alarm fırtınası',
      storm_coverage: { storm_id: 12, trigger: 'INITIAL', total: 3, hidden: 1, inferred: false, alarms: [
        { id: 501, domain: 'site-a.example.com', alert_type: 'HTTP_DOWN', alert_level: 'WARNING', resolved: false },
        { id: 502, domain: 'site-b.example.com', alert_type: 'HTTP_DOWN', alert_level: 'HIGH', resolved: true },
      ] } }} />)
    const sec = await waitFor(() => {
      const el = document.querySelector('[data-slot="pl-storm-coverage"]')
      expect(el).toBeTruthy()
      return el
    })
    expect(sec.textContent).toContain(fill('pl.sc.title', 3))
    const links = sec.querySelectorAll('[data-slot="pl-storm-alarm"]')
    expect(links).toHaveLength(2)
    expect(links[1].textContent).toContain(EN['pl.sc.resolved'])
    fireEvent.click(links[0])
    expect(onOpenAlert).toHaveBeenCalledWith(501)
    expect(sec.querySelector('[data-slot="pl-storm-hidden"]').textContent).toBe(fill('pl.sc.hidden', 1))
    expect(document.querySelector('[data-slot="pl-storm-pushes"]')).toBeNull()
  })

  it('SKIPPED_STORM karar satırı: kapsayan fırtına push satırları; satıra tıklamak o satırın ayrıntısını açar', async () => {
    const onPick = vi.fn()
    render(<Harness onPick={onPick} detail={{ ...BASE, id: 9, username: '-', display_name: '(katman kararı)', trigger: 'OPEN',
      status: 'SKIPPED_STORM', kind: 'SKIPPED', alert_event_id: 414, monitor_type: 'http', monitor_name: 'site-a.example.com',
      storm_pushes: [{ storm_id: 12, trigger: 'INITIAL', push_key: 'storm:12:INITIAL', team_id: 1, inferred: true, covered_at: '2026-10-04T08:00:05',
        rows: [
          { id: 11, username: 'u1', display_name: 'Bir', status: 'SENT', kind: 'SENT', sent_at: '2026-10-04T08:00:07' },
          { id: 12, username: 'u2', display_name: 'İki', status: 'RATE_LIMITED', kind: 'BLOCKED', created_at: '2026-10-04T08:00:05' },
        ] }],
      storm_awaiting: [] }} />)
    const sec = await waitFor(() => {
      const el = document.querySelector('[data-slot="pl-storm-pushes"]')
      expect(el).toBeTruthy()
      return el
    })
    expect(sec.textContent).toContain(fill('pl.sp.push', 12, EN['alh.sp.trigger.INITIAL']))
    expect(sec.textContent).toContain(EN['alh.sp.inferred'])
    const chain = sec.querySelectorAll('[data-chain-id]')
    expect(chain).toHaveLength(2)
    fireEvent.click(chain[1])
    expect(onPick).toHaveBeenCalledWith(12)
  })

  it('SKIPPED_STORM, henüz kapsayan push yok: "fırtına sürüyor" notu', async () => {
    render(<Harness detail={{ ...BASE, id: 9, username: '-', status: 'SKIPPED_STORM', kind: 'SKIPPED', alert_event_id: 414,
      storm_pushes: [], storm_awaiting: [13] }} />)
    await waitFor(() => expect(document.querySelector('[data-slot="pl-storm-pushes"]')?.textContent).toContain(fill('pl.sp.awaiting', 13)))
  })
})
