import { describe, it, expect, vi } from 'vitest'
import { render, screen, fireEvent, act } from './test-utils.jsx'
import InventoryTable from '../components/inventory/InventoryTable.jsx'
import InventoryCardList from '../components/inventory/InventoryCardList.jsx'

/**
 * 2026-09-27 regresyon taraması (release-fixes.md FRONTEND A #8): Envanter tablosu ve telefon kart listesinde
 * "Şimdi kontrol et" tek yuvalı meşgul bayrağı (`busy = domain`) tutuyordu — A sürerken B'ye basınca A'nın düğmesi
 * yeniden etkinleşiyor (iş sürerken bitmiş görünüyor, ikinci kez basılabiliyor), önce biten B diğerinin kilidini de
 * açıyordu. Model `useRunningChecks`: alan adı KÜMESİ. Denetimli promise'lerle belirlenimci.
 */
const rows = [
  { id: 1, domain: 'a.example.com', port: 443, active: true, team_id: 5, cert_status: 'valid' },
  { id: 2, domain: 'b.example.com', port: 443, active: true, team_id: 5, cert_status: 'valid' },
]
const deferred = () => { let resolve; const p = new Promise((r) => { resolve = r }); return { p, resolve } }
const checkBtn = (domain) => screen.getByRole('button', { name: new RegExp(`^${domain.replace(/\./g, '[.]')} — (Check now|Şimdi kontrol et)$`) })
const common = { canManage: true, isAdmin: true, teamsCount: 1, selected: new Set(), onToggle: () => {}, onShow: () => {}, onInline: () => {} }

async function concurrentChecks() {
  const calls = { 'a.example.com': deferred(), 'b.example.com': deferred() }
  return { calls, onCheckNow: vi.fn((r) => calls[r.domain].p) }
}
async function assertIndependentBusy(calls) {
  fireEvent.click(checkBtn('a.example.com'))
  fireEvent.click(checkBtn('b.example.com'))
  expect(checkBtn('a.example.com')).toBeDisabled()   // A hâlâ sürüyor — B başladı diye sönmez
  expect(checkBtn('b.example.com')).toBeDisabled()
  await act(async () => { calls['b.example.com'].resolve() })
  expect(checkBtn('b.example.com')).not.toBeDisabled()
  expect(checkBtn('a.example.com')).toBeDisabled()   // önce biten B, A'nın kilidini AÇMAZ
  await act(async () => { calls['a.example.com'].resolve() })
  expect(checkBtn('a.example.com')).not.toBeDisabled()
}

describe('Envanter "Şimdi kontrol et" — eşzamanlı kontroller (meşgul kümesi)', () => {
  it('InventoryTable: iki satırın kontrolü birbirinin göstergesini söndürmez', async () => {
    const { calls, onCheckNow } = await concurrentChecks()
    render(<InventoryTable rows={rows} cols={['domain']} sort="domain|asc" onSort={() => {}} density="comfortable"
      onToggleAll={() => {}} allOnPage={false} statusFilter="active" onCheckNow={onCheckNow} {...common} />)
    await assertIndependentBusy(calls)
    expect(onCheckNow).toHaveBeenCalledTimes(2)
  })

  it('InventoryCardList (telefon): iki kartın kontrolü birbirinin göstergesini söndürmez', async () => {
    const { calls, onCheckNow } = await concurrentChecks()
    render(<InventoryCardList rows={rows} onCheckNow={onCheckNow} {...common} />)
    await assertIndependentBusy(calls)
  })
})
