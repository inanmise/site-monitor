import { useState } from 'react'
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor, within } from './test-utils.jsx'

const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))

vi.mock('../api/client', () => ({
  formatDate: (s) => s ?? '',
  formatDateSec: (s) => s ?? '',
  formatDateOnly: (s) => s ?? '',
  api: withApiFallback({
    noc: { groupOptions: vi.fn(), coverage: vi.fn(), bulk: vi.fn() },
  }),
}))
const nav = vi.fn()
vi.mock('../utils/navigate.js', () => ({ navigateTo: (...a) => nav(...a), default: (...a) => nav(...a) }))
const toast = { success: vi.fn(), error: vi.fn(), info: vi.fn(), dismiss: vi.fn() }
vi.mock('../components/ui/Toast.jsx', () => ({ useToast: () => toast, ToastProvider: ({ children }) => children }))

import { api } from '../api/client'
import NocNotifyField from '../components/noc/forms/NocNotifyField.jsx'
import NocStatus from '../components/noc/NocStatus.jsx'
import { resetNocStateForTests } from '../components/noc/useNocState.js'
import { resetNocFormOptionsCache } from '../components/noc/forms/useNocFormOptions.js'
import {
  nocIdsFrom, nocGroupIdsBody, fallbackIds, shownSelection, toggleGroupId, listedGroups, isTypeDisabled,
  mergeBulkResults, chunk, bulkTone, bulkToast,
} from '../components/noc/forms/nocFormModel.js'
import BulkActionBar from '../components/ui/BulkActionBar.jsx'
import { mapCsv, IMPORT_COLUMNS } from '../components/inventory/inventoryModel.js'
import { MonitorStatusBadge } from '../components/monitoring/MonitorCard.jsx'
import PingMonitorCard from '../components/ping/PingMonitorCard.jsx'
import HttpMonitorCard from '../components/http/HttpMonitorCard.jsx'
import KeywordMonitorCard from '../components/keyword/KeywordMonitorCard.jsx'
import PageMonitorCard from '../components/page/PageMonitorCard.jsx'
import PageSpeedMonitorCard from '../components/pagespeed/PageSpeedMonitorCard.jsx'
import ScriptedMonitorCard from '../components/scripted/ScriptedMonitorCard.jsx'
import DnsMonitorCard from '../components/dns/DnsMonitorCard.jsx'
import PortMonitorCard from '../components/port/PortMonitorCard.jsx'
import DomainMonitorCard from '../components/domain/DomainMonitorCard.jsx'
import MonitorCardMeta from '../components/MonitorCardMeta.jsx'
import { MonitorDetailModal } from '../components/monitoring/MonitorDetail.jsx'

/**
 * 7/24 izleme ekibi — izleme formu alanı (NocNotifyField), saf model, kart/detay rozeti ve toplu işlem çubuğu
 * (2026-09-27; sözleşme `.migration/noc/CONTRACT.md`). Form başına kablolama `nocMonitorForms.test.jsx`'te.
 */
const G = (id, name, { def = false, active = true } = {}) => ({ id, name, is_default: def, active })
const TWO_ACTIVE = [G(1, 'NOC Nöbet Listesi', { def: true }), G(2, 'Hafta Sonu Vardiyası'), G(3, 'Eski Liste', { active: false })]

const SWITCH = /7\/24 izleme ekibine bildir|Notify the 24\/7 monitoring team/
const groupOption = (id) => document.querySelector(`[data-slot="noc-group-option"][data-group-id="${id}"]`)
const groupBox = (id) => within(groupOption(id)).getByRole('checkbox')

/**
 * `GET /api/noc/groups/options` — sözleşme "Backend sapmaları": `{ groups, disabled_types }` (tek istek) + kart
 * göstergesi ekleri (2026-09-28) `has_active_group`, `min_level` — sunucunun GERÇEK yanıt biçimi.
 */
function options({ groups = TWO_ACTIVE, disabled = [] } = {}) {
  api.noc.groupOptions.mockResolvedValue({
    success: true,
    data: { groups, disabled_types: disabled, has_active_group: groups.some((g) => g.active), min_level: 'CRITICAL' },
  })
}

/** Formdaki gibi durum tutan sarmalayıcı: alan `onChange(patch)` verir, form birleştirir. */
function Harness({ type = 'HTTP', initial = {}, onPatch, ...rest }) {
  const [v, setV] = useState({ nocNotify: false, nocGroupIds: [], ...initial })
  return (
    <NocNotifyField type={type} checked={v.nocNotify} groupIds={v.nocGroupIds} {...rest}
      onChange={(p) => { onPatch?.(p); setV((s) => ({ ...s, ...p })) }} />
  )
}

beforeEach(() => {
  vi.clearAllMocks()
  resetNocFormOptionsCache()
  options()
})

describe('nocFormModel', () => {
  it('nocIdsFrom: dizi / virgüllü metin / null → tekil pozitif tamsayılar', () => {
    expect(nocIdsFrom(null)).toEqual([])
    expect(nocIdsFrom('')).toEqual([])
    expect(nocIdsFrom([3, '7', 3, 0, -1, 'x'])).toEqual([3, 7])
    expect(nocIdsFrom('4, 5,4')).toEqual([4, 5])
  })

  it('nocGroupIdsBody: boş seçim null (varsayılan gruplar), aksi hâlde dizi', () => {
    expect(nocGroupIdsBody([])).toBeNull()
    expect(nocGroupIdsBody(undefined)).toBeNull()
    expect(nocGroupIdsBody(['2', 3])).toEqual([2, 3])
  })

  it('fallbackIds: aktif varsayılanlar; varsayılan yoksa tüm aktifler; pasifler hiç', () => {
    expect(fallbackIds(TWO_ACTIVE)).toEqual([1])
    expect(fallbackIds([G(1, 'a'), G(2, 'b'), G(3, 'c', { active: false })])).toEqual([1, 2])
    expect(fallbackIds([G(1, 'a', { def: true, active: false }), G(2, 'b')])).toEqual([2])
    expect(fallbackIds(null)).toEqual([])
  })

  it('toggleGroupId: varsayılana eşit seçim [] olur, son grup kaldırılamaz', () => {
    expect(shownSelection([], TWO_ACTIVE)).toEqual([1])
    expect(toggleGroupId([], 2, TWO_ACTIVE)).toEqual([1, 2])
    expect(toggleGroupId([1, 2], 2, TWO_ACTIVE)).toEqual([])        // yine varsayılan küme → varsayılanı izle
    expect(toggleGroupId([1, 2], 1, TWO_ACTIVE)).toEqual([2])
    expect(toggleGroupId([2], 2, TWO_ACTIVE)).toEqual([2])          // son grup kalır
    expect(toggleGroupId([], 1, TWO_ACTIVE)).toEqual([])            // varsayılan kümenin tek üyesi kaldırılamaz
  })

  it('listedGroups: aktifler (varsayılan önce) + GEÇERLİ açık seçimdeki pasif grup; silinmiş kimlik listelenmez', () => {
    expect(listedGroups([], TWO_ACTIVE).map((g) => g.id)).toEqual([1, 2])
    expect(listedGroups([2, 3, 99], TWO_ACTIVE).map((g) => g.id)).toEqual([1, 2, 3])
    // Seçimin HİÇBİRİ aktif değil → sunucu varsayılana düşer; pasif grup da listelenmez (bkz. shownSelection)
    expect(listedGroups([3, 99], TWO_ACTIVE).map((g) => g.id)).toEqual([1, 2])
  })

  it('shownSelection = sunucu resolveTargets: aktif kesişim boşsa varsayılanlar; değilse seçim (pasifler dâhil, silinmişler hariç)', () => {
    expect(shownSelection([3], TWO_ACTIVE)).toEqual([1])          // tek seçili grup pasif → varsayılan
    expect(shownSelection([3, 99], TWO_ACTIVE)).toEqual([1])      // pasif + silinmiş → varsayılan
    expect(shownSelection([99], TWO_ACTIVE)).toEqual([1])         // yalnız silinmiş → varsayılan
    expect(shownSelection([2, 3, 99], TWO_ACTIVE)).toEqual([2, 3]) // aktif kalan var → seçim (silinmiş düşer)
    // Varsayılan yoksa tüm aktifler (sunucu "ALL")
    const noDefault = [G(1, 'a'), G(2, 'b'), G(3, 'c', { active: false })]
    expect(shownSelection([3], noDefault)).toEqual([1, 2])
    // Bayat seçimde bir grubu işaretlemek varsayılan kümeden başlar
    expect(toggleGroupId([3], 2, TWO_ACTIVE)).toEqual([1, 2])
  })

  it('isTypeDisabled: yalnız bilinen listede', () => {
    expect(isTypeDisabled('PING', ['PING'])).toBe(true)
    expect(isTypeDisabled('PING', null)).toBe(false)
  })

  it('toplu sonuç birleştirme + parça + ton + bildirim metni', () => {
    const sum = mergeBulkResults([
      { updated: 2, skipped: [{ reason: 'UNCHANGED' }, { reason: 'FORBIDDEN' }] },
      { updated: 1, skipped: [{ reason: 'WEIRD' }] },
    ])
    expect(sum).toEqual({ updated: 3, skipped: 3, byReason: { UNCHANGED: 1, FORBIDDEN: 1, OTHER: 1 } })
    expect(chunk([1, 2, 3, 4, 5], 2)).toEqual([[1, 2], [3, 4], [5]])
    expect(bulkTone(sum)).toBe('success')
    expect(bulkTone({ updated: 0, skipped: 2, byReason: { UNCHANGED: 2 } })).toBe('info')
    expect(bulkTone({ updated: 0, skipped: 1, byReason: { FORBIDDEN: 1 } })).toBe('error')
    const t = (k, ...a) => `${k}(${a.join('|')})`
    expect(bulkToast(sum, true, t).text)
      .toBe('nocf.bulkOnDone(3) · nocf.bulkSkipped(3|nocf.skip.UNCHANGED_ON(1), nocf.skip.FORBIDDEN(1), nocf.skip.OTHER(1))')
    expect(bulkToast({ updated: 1, skipped: 0, byReason: {} }, false, t)).toEqual({ tone: 'success', text: 'nocf.bulkOffDone1(1)' })
    expect(bulkToast({ updated: 0, skipped: 2, byReason: { UNCHANGED: 2 } }, false, t)).toEqual({ tone: 'info', text: 'nocf.bulkNothingOff()' })
    expect(bulkToast({ updated: 0, skipped: 1, byReason: { NOT_FOUND: 1 } }, true, t).tone).toBe('error')
  })
})

describe('NocNotifyField', () => {
  it('varsayılan KAPALI; kapalıyken grup seçici yok (birden çok aktif grup olsa da)', async () => {
    render(<Harness />)
    const sw = screen.getByRole('switch', { name: SWITCH })
    expect(sw).not.toBeChecked()
    expect(sw).toHaveAccessibleDescription(/kritik uyarıları|critical alerts/)
    await waitFor(() => expect(api.noc.groupOptions).toHaveBeenCalledTimes(1))
    expect(api.noc.coverage).not.toHaveBeenCalled()   // tür anahtarları seçenek yanıtında — ağır kapsam ucu çağrılmaz
    expect(document.querySelector('[data-slot="noc-group-picker"]')).toBeNull()
  })

  it('açınca {nocNotify:true} bildirir; >1 aktif grup → seçici, varsayılan önseçili, pasif grup listede yok', async () => {
    const onPatch = vi.fn()
    render(<Harness onPatch={onPatch} />)
    await waitFor(() => expect(api.noc.groupOptions).toHaveBeenCalled())
    fireEvent.click(screen.getByRole('switch', { name: SWITCH }))
    expect(onPatch).toHaveBeenLastCalledWith({ nocNotify: true })
    const picker = await waitFor(() => {
      const el = document.querySelector('[data-slot="noc-group-picker"]')
      expect(el).not.toBeNull()
      return el
    })
    expect(picker).toHaveAttribute('data-explicit', 'false')
    expect(groupBox(1)).toBeChecked()
    expect(groupBox(1)).toBeDisabled()          // varsayılan kümenin tek üyesi: son işaretli grup
    expect(groupBox(2)).not.toBeChecked()
    expect(groupOption(3)).toBeNull()
  })

  it('seçim değişince açık liste; "Varsayılan gruplara dön" [] yapar', async () => {
    const onPatch = vi.fn()
    render(<Harness initial={{ nocNotify: true }} onPatch={onPatch} />)
    await waitFor(() => expect(groupOption(2)).not.toBeNull())
    fireEvent.click(groupBox(2))
    expect(onPatch).toHaveBeenLastCalledWith({ nocGroupIds: [1, 2] })
    fireEvent.click(groupBox(1))
    expect(onPatch).toHaveBeenLastCalledWith({ nocGroupIds: [2] })
    expect(groupBox(2)).toBeDisabled()
    expect(document.querySelector('[data-slot="noc-group-picker"]')).toHaveAttribute('data-explicit', 'true')
    fireEvent.click(screen.getByRole('button', { name: /varsayılan gruplara dön|use the default groups/i }))
    expect(onPatch).toHaveBeenLastCalledWith({ nocGroupIds: [] })
    expect(groupBox(1)).toBeChecked()
  })

  it('kayıtlı seçimdeki PASİF grup "Pasif" rozetiyle listelenir ve kaldırılabilir', async () => {
    const onPatch = vi.fn()
    render(<Harness initial={{ nocNotify: true, nocGroupIds: [2, 3] }} onPatch={onPatch} />)
    await waitFor(() => expect(groupOption(3)).not.toBeNull())
    expect(groupBox(3)).toBeChecked()
    expect(within(groupOption(3)).getByText(/^(Pasif|Inactive)$/)).toBeInTheDocument()
    fireEvent.click(groupBox(3))
    expect(onPatch).toHaveBeenLastCalledWith({ nocGroupIds: [2] })
  })

  it('kayıtlı seçimin HEPSİ pasif: sunucu gibi varsayılanlar işaretli, "varsayılanı izliyor", pasif grup listelenmez', async () => {
    render(<Harness initial={{ nocNotify: true, nocGroupIds: [3] }} />)
    const picker = await waitFor(() => {
      const el = document.querySelector('[data-slot="noc-group-picker"]')
      expect(el).not.toBeNull()
      return el
    })
    expect(picker).toHaveAttribute('data-explicit', 'false')
    expect(groupBox(1)).toBeChecked()
    expect(groupBox(2)).not.toBeChecked()
    expect(groupOption(3)).toBeNull()
  })

  it('tek aktif grup → seçici YOK, "E-postalar X grubuna gider" satırı', async () => {
    options({ groups: [G(1, 'NOC Nöbet Listesi', { def: true }), G(3, 'Eski', { active: false })] })
    render(<Harness initial={{ nocNotify: true }} />)
    expect(await screen.findByText(/NOC Nöbet Listesi/)).toBeInTheDocument()
    expect(document.querySelector('[data-slot="noc-one-group"]')).not.toBeNull()
    expect(document.querySelector('[data-slot="noc-group-picker"]')).toBeNull()
  })

  it('tür yönetici tarafından kapatıldıysa uyarı bandı; anahtar KULLANILABİLİR kalır', async () => {
    options({ disabled: ['PING'] })
    const onPatch = vi.fn()
    render(<Harness type="PING" onPatch={onPatch} />)
    const off = await waitFor(() => {
      const el = document.querySelector('[data-slot="noc-type-off"] [data-slot="alert"]')
      expect(el).not.toBeNull()
      return el
    })
    expect(off).toHaveAttribute('data-tone', 'warning')
    expect(off).toHaveTextContent(/Ping/)
    const sw = screen.getByRole('switch', { name: SWITCH })
    expect(sw).toBeEnabled()
    fireEvent.click(sw)
    expect(onPatch).toHaveBeenLastCalledWith({ nocNotify: true })
  })

  it('başka tür kapalıysa bu formda uyarı YOK', async () => {
    options({ disabled: ['PING'] })
    render(<Harness type="HTTP" initial={{ nocNotify: true }} />)
    // Seçici görünür = seçenekler YÜKLENDİ (yokluk iddiası yükten önce vakum olurdu)
    await waitFor(() => expect(document.querySelector('[data-slot="noc-group-picker"]')).not.toBeNull())
    expect(document.querySelector('[data-slot="noc-type-off"]')).toBeNull()
  })

  it('aktif grup yok: yönetici → Ayarlar → 7/24 bağlantısı; diğerleri → "yöneticinize başvurun"', async () => {
    options({ groups: [G(3, 'Eski', { active: false })] })
    const { unmount } = render(<Harness canOpenSettings />)
    const line = await waitFor(() => {
      const el = document.querySelector('[data-slot="noc-no-groups"]')
      expect(el).not.toBeNull()
      return el
    })
    fireEvent.click(within(line).getByRole('button', { name: /Ayarlar → 7\/24|Settings → 24\/7/ }))
    expect(nav).toHaveBeenCalledWith('settings', { sec: 'noc' })
    unmount()
    render(<Harness canOpenSettings={false} />)
    const line2 = await waitFor(() => {
      const el = document.querySelector('[data-slot="noc-no-groups"]')
      expect(el).not.toBeNull()
      return el
    })
    expect(within(line2).queryByRole('button')).toBeNull()
    expect(line2).toHaveTextContent(/yöneticinize başvurun|ask your administrator/i)
  })

  it('seçenekler yüklenemezse "grup yok" / "tür kapalı" İDDİA EDİLMEZ (bilinmiyor ≠ yok)', async () => {
    api.noc.groupOptions.mockRejectedValue(new Error('Failed to fetch'))
    render(<Harness initial={{ nocNotify: true }} />)
    await waitFor(() => expect(api.noc.groupOptions).toHaveBeenCalled())
    await new Promise((r) => setTimeout(r, 0))
    expect(document.querySelector('[data-slot="noc-no-groups"]')).toBeNull()
    expect(document.querySelector('[data-slot="noc-type-off"]')).toBeNull()
    expect(document.querySelector('[data-slot="noc-group-picker"]')).toBeNull()
  })

  it('önbellek: ikinci form açılışı (başka tür de olsa) yeni istek atmaz; hata önbelleğe GİRMEZ', async () => {
    const a = render(<Harness type="HTTP" initial={{ nocNotify: true }} />)
    await waitFor(() => expect(document.querySelector('[data-slot="noc-group-picker"]')).not.toBeNull())
    a.unmount()
    render(<Harness type="DNS" initial={{ nocNotify: true }} />)
    await waitFor(() => expect(document.querySelector('[data-slot="noc-group-picker"]')).not.toBeNull())
    expect(api.noc.groupOptions).toHaveBeenCalledTimes(1)
  })

  it('başarısız yükleme önbelleğe girmez: sonraki açılış yeniden dener', async () => {
    api.noc.groupOptions.mockRejectedValueOnce(new Error('Failed to fetch'))
    const a = render(<Harness initial={{ nocNotify: true }} />)
    await waitFor(() => expect(api.noc.groupOptions).toHaveBeenCalledTimes(1))
    await new Promise((r) => setTimeout(r, 0))
    a.unmount()
    render(<Harness initial={{ nocNotify: true }} />)
    await waitFor(() => expect(document.querySelector('[data-slot="noc-group-picker"]')).not.toBeNull())
    expect(api.noc.groupOptions).toHaveBeenCalledTimes(2)
  })

  it('disabled → anahtar kilitli', () => {
    render(<Harness disabled />)
    expect(screen.getByRole('switch', { name: SWITCH })).toBeDisabled()
  })
})

// 2026-09-28: eski "7/24" rozeti (NocBadge — yalnız Zengin'de, yalnız açıkken) kaldırıldı; yerine her kartta, İKİ
// yoğunlukta ve İKİ durumda (açık/kapalı) çizilen 7/24 göstergesi (noc/NocStatus) geldi. Ayrıntılı sözleşme
// nocStatus.cards.test.jsx'te; bu blok dokuz kartın aynı yeri kullandığını ve detay penceresinin aynı bileşeni taşıdığını pinler.
describe('7/24 göstergesi — dokuz kartta (iki yoğunluk, açık/kapalı) ve detay başlığında', () => {
  beforeEach(() => { resetNocStateForTests() })
  const base = {
    id: 9, name: 'Örnek', url: 'https://www.example.com/', host: 'h.example.com', domain: 'example.com', port: 443,
    protocol: 'TCP', record_type: 'A', value: '203.0.113.10', status: 'up', active: true, team_id: 1, team_name: 'Takım A',
    checked_at: '2026-09-26T09:00:00', last_check: '2026-09-26T09:00:00', script: 'export default function(){}',
    days_remaining: 120, expiry_date: '2027-01-26T00:00:00', noc_notify: true,
  }
  const badge = <MonitorStatusBadge status="up">OK</MonitorStatusBadge>
  const meta = (m) => <MonitorCardMeta monitor={m} />
  const CARDS = [
    ['Ping', (m, d) => <PingMonitorCard monitor={m} density={d} onOpen={() => {}} />],
    ['HTTP', (m, d) => <HttpMonitorCard monitor={m} density={d} status="up" badge={badge} meta={meta(m)} onOpen={() => {}} />],
    ['Keyword', (m, d) => <KeywordMonitorCard monitor={{ ...m, keyword: 'Giriş' }} density={d} status="up" badge={badge} meta={meta(m)} onOpen={() => {}} />],
    ['Page', (m, d) => <PageMonitorCard monitor={m} density={d} status="up" badge={badge} onOpen={() => {}} />],
    ['PageSpeed', (m, d) => <PageSpeedMonitorCard monitor={m} density={d} status="up" badge={badge} meta={meta(m)} onOpen={() => {}} />],
    ['Scripted', (m, d) => <ScriptedMonitorCard monitor={m} density={d} status="up" badge={badge} onOpen={() => {}} />],
    ['DNS', (m, d) => <DnsMonitorCard monitor={m} density={d} status="up" statusBadge={badge} meta={meta(m)} onOpen={() => {}} />],
    ['Port', (m, d) => <PortMonitorCard monitor={m} density={d} status="up" badge={badge} meta={meta(m)} onOpen={() => {}} />],
    ['Domain', (m, d) => <DomainMonitorCard monitor={m} density={d} status="up" badge={badge} meta={meta(m)} onOpen={() => {}} />],
  ]
  for (const [name, card] of CARDS) {
    it(`${name}: iki yoğunlukta da gösterge durum satırının SAĞ grubunda; açık → "açık", kapalı → "kapalı" (hiç kaybolmaz)`, async () => {
      const r = render(card(base, 'rich'))
      const s = document.querySelector('[data-slot="noc-status"]')
      expect(s, 'Zengin kartta 7/24 göstergesi yok').not.toBeNull()
      expect(s.closest('[data-slot="monitor-card-rich"]'), 'gösterge yalnız-Zengin bölümde olmamalı').toBeNull()
      expect(s.closest('[data-slot="card-header"]'), 'gösterge kartın başlık bölgesinde').not.toBeNull()
      expect(s).not.toHaveAttribute('data-compact')
      // Tetik satırı ayırt eden adla (hedef — 7/24 …); tür açık + aktif grup → doğrulanmış "açık"
      const trigger = s.closest('[data-slot="hint-trigger"]')
      expect(trigger.getAttribute('aria-label')).toMatch(/ — (7\/24 açık|24\/7 on)$/)
      await waitFor(() => expect(document.querySelector('[data-slot="noc-status"]')).toHaveAttribute('data-verified', 'true'))
      fireEvent.click(trigger)
      expect(await screen.findByRole('dialog')).toHaveTextContent(/Kritik uyarılar 7\/24|Critical alerts also go to the 24\/7/)
      r.unmount()
      const c = render(card(base, 'compact'))
      expect(document.querySelector('[data-slot="noc-status"]')).toHaveAttribute('data-compact', 'true')
      c.unmount()
      render(card({ ...base, noc_notify: false }, 'rich'))
      expect(document.querySelector('[data-slot="noc-status"]')).toHaveAttribute('data-state', 'off')
    })
  }

  it('detay penceresi: `noc` → başlık satırında AYNI gösterge (pencere ADINA karışmaz); `noc` yoksa yok', () => {
    const { rerender } = render(
      <MonitorDetailModal onClose={() => {}} title="www.example.com" noc={{ type: 'HTTP', monitor: base }}>gövde</MonitorDetailModal>)
    const dlg = screen.getByRole('dialog')
    expect(dlg.querySelector('[data-slot="noc-status"]')).toHaveAttribute('data-state', 'on')
    expect(dlg).toHaveAccessibleName('www.example.com')
    rerender(<MonitorDetailModal onClose={() => {}} title="www.example.com">gövde</MonitorDetailModal>)
    expect(screen.getByRole('dialog').querySelector('[data-slot="noc-status"]')).toBeNull()
  })

  it('bağımsız gösterge: rowLabel yoksa ad yalnız durum etiketi', () => {
    render(<NocStatus type="HTTP" monitor={base} />)
    expect(screen.getByRole('button', { name: /^(7\/24 açık|24\/7 on)$/ })).toBeInTheDocument()
  })
})

describe('BulkActionBar — 7/24 toplu işlem', () => {
  const items = [{ id: 1, active: true }, { id: 2, active: true }, { id: 3, active: false }]
  const rowApi = () => ({ update: vi.fn().mockResolvedValue({ success: true }), remove: vi.fn() })
  const ON = /^(Seçili izlemelerde 7\/24 bildirimini aç|Switch on 24\/7 alerts for the selected monitors)$/
  const OFF = /^(Seçili izlemelerde 7\/24 bildirimini kapat|Switch off 24\/7 alerts for the selected monitors)$/

  it('nocType verilmezse 7/24 grubu HİÇ çizilmez', () => {
    render(<BulkActionBar selected={new Set([1])} items={items} api={rowApi()} />)
    expect(document.querySelector('[data-slot="bulk-noc"]')).toBeNull()
    expect(screen.queryByRole('button', { name: ON })).toBeNull()
  })

  it('Aç: seçili satırlar TEK istekle {items:[{type,id}], enabled:true}; sonuç bildirimi atlananları nedenleriyle söyler', async () => {
    api.noc.bulk.mockResolvedValue({ success: true, data: { updated: 1, skipped: [{ type: 'HTTP', id: 2, reason: 'UNCHANGED' }] } })
    const onClear = vi.fn(), onDone = vi.fn()
    const rows = rowApi()
    render(<BulkActionBar selected={new Set([1, 2])} items={items} api={rows} nocType="HTTP" onClear={onClear} onDone={onDone} />)
    const group = document.querySelector('[data-slot="bulk-noc"] [data-slot="button-group"]')
    expect(group).toHaveAccessibleName(/7\/24’e bildir|Notify 24\/7 team/)
    fireEvent.click(screen.getByRole('button', { name: ON }))
    await waitFor(() => expect(onDone).toHaveBeenCalled())
    expect(api.noc.bulk).toHaveBeenCalledTimes(1)
    expect(api.noc.bulk).toHaveBeenCalledWith([{ type: 'HTTP', id: 1 }, { type: 'HTTP', id: 2 }], true)
    expect(rows.update).not.toHaveBeenCalled()
    expect(toast.success).toHaveBeenCalledTimes(1)
    expect(toast.success.mock.calls[0][0]).toMatch(/1 izlemede açıldı · 1 atlandı: 1 zaten açıktı|switched on for 1 monitor · 1 skipped: 1 already on/)
    expect(onClear).toHaveBeenCalled()
  })

  it('Kapat: enabled:false; hepsi zaten kapalıysa bilgi tonu', async () => {
    api.noc.bulk.mockResolvedValue({ success: true, data: { updated: 0, skipped: [{ reason: 'UNCHANGED' }, { reason: 'UNCHANGED' }] } })
    render(<BulkActionBar selected={new Set([1, 3])} items={items} api={rowApi()} nocType="PORT" onClear={() => {}} onDone={() => {}} />)
    fireEvent.click(screen.getByRole('button', { name: OFF }))
    await waitFor(() => expect(toast.info).toHaveBeenCalled())
    expect(api.noc.bulk).toHaveBeenCalledWith([{ type: 'PORT', id: 1 }, { type: 'PORT', id: 3 }], false)
    expect(toast.info.mock.calls[0][0]).toMatch(/hepsinde 7\/24 bildirimi zaten kapalı|already off for all the selected/)
  })

  it('yetkisiz satırlar → hata tonu + neden; istek düşerse hata bildirimi', async () => {
    api.noc.bulk.mockResolvedValueOnce({ success: true, data: { updated: 0, skipped: [{ reason: 'FORBIDDEN' }] } })
    const { unmount } = render(<BulkActionBar selected={new Set([1])} items={items} api={rowApi()} nocType="DNS" onClear={() => {}} onDone={() => {}} />)
    fireEvent.click(screen.getByRole('button', { name: ON }))
    await waitFor(() => expect(toast.error).toHaveBeenCalledTimes(1))
    expect(toast.error.mock.calls[0][0]).toMatch(/için yetkiniz yok|you don’t have permission for/)
    unmount()
    api.noc.bulk.mockRejectedValueOnce(new Error('Failed to fetch'))
    render(<BulkActionBar selected={new Set([1])} items={items} api={rowApi()} nocType="DNS" onClear={() => {}} onDone={() => {}} />)
    fireEvent.click(screen.getByRole('button', { name: ON }))
    await waitFor(() => expect(toast.error).toHaveBeenCalledTimes(2))
    expect(toast.error.mock.calls[1][0]).toMatch(/tamamlanamadı|Couldn’t update 24\/7/)
  })
})

describe('envanter CSV içe aktarma — 7/24 sütunları (dışa aktar → düzelt → içe aktar)', () => {
  it('anahtar başlıklar (noc_notify / noc_groups) ve dışa aktarma etiketleri tanınır', () => {
    expect(IMPORT_COLUMNS).toEqual(expect.arrayContaining(['noc_notify', 'noc_groups']))
    const raw = mapCsv([['domain', 'noc_notify', 'noc_groups'], ['a.example.com', 'evet', 'NOC Nöbet Listesi; Hafta Sonu']])
    expect(raw.unknown).toEqual([])
    expect(raw.rows).toEqual([{ domain: 'a.example.com', noc_notify: 'evet', noc_groups: 'NOC Nöbet Listesi; Hafta Sonu' }])
    const labelled = mapCsv([['Alan Adı', '7/24 bildirimi', '7/24 grupları'], ['b.example.com', 'Hayır', '']],
      { 'Alan Adı': 'domain', '7/24 bildirimi': 'noc_notify', '7/24 grupları': 'noc_groups' })
    expect(labelled.unknown).toEqual([])
    expect(labelled.rows).toEqual([{ domain: 'b.example.com', noc_notify: 'Hayır' }])   // boş grup hücresi gönderilmez (= dokunma)
  })
})
