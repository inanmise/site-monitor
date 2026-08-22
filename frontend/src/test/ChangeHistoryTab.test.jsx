import { render, screen, fireEvent, waitFor } from './test-utils.jsx'
import { describe, it, expect, vi, beforeEach } from 'vitest'

const { apiMock } = vi.hoisted(() => {
  const deep = (obj) => new Proxy(obj, {
    get(t, prop) {
      if (prop === 'then' || typeof prop === 'symbol') return undefined
      if (prop in t) return typeof t[prop] === 'object' && t[prop] !== null ? deep(t[prop]) : t[prop]
      t[prop] = vi.fn(() => Promise.resolve({ success: true, data: {} }))
      return t[prop]
    },
  })
  return { apiMock: deep({ monitoring: {} }) }
})

vi.mock('../api/client', () => ({
  api: apiMock,
  formatDateSec: (s) => s ?? '',
  formatDateOnly: (s) => s ?? '',
  formatDate: (s) => s ?? '',
}))

import { api } from '../api/client'
import ChangeHistoryTab from '../components/history/ChangeHistoryTab.jsx'

/** Etiket sözlüğü: gerçek anahtarların karşılığı olmalı, gerisi ham anahtar döner. */
const LABELS = {
  'chg.eventCREATE': 'Oluşturuldu',
  'chg.eventUPDATE': 'Güncellendi',
  'chg.field.intervalSeconds': 'Kontrol sıklığı',
  'chg.field.name': 'Ad',
  'chg.field.password': 'Parola',
  'chg.valueOn': 'Açık',
  'chg.valueOff': 'Kapalı',
  'chg.unitSec': 'sn', 'chg.unitMin': 'dk', 'chg.unitHour': 'sa',
}
const t = (k, ...a) => LABELS[k] ?? (a.length ? `${k}:${a.join('|')}` : k)

const CREATE_ROW = {
  seq: 0, kind: 'PORT', resource_id: 4, resource_name: 'Ödeme portu', event_type: 'CREATE',
  team_id: 5, team_name: 'Kanal', actor: 'N70678', actor_name: 'Ada Lovelace',
  ip_address: '10.20.30.40', user_agent: 'Mozilla/5.0 (Windows NT 10.0) Chrome/126.0.0.0',
  changes: null, note: null, at: '2026-08-22T09:00:00',
}
const UPDATE_ROW = {
  ...CREATE_ROW, seq: 1, event_type: 'UPDATE', at: '2026-08-22T10:00:00',
  note: 'Alarm çok geç açılıyordu',
  changes: JSON.stringify({
    intervalSeconds: { from: 300, to: 60 },
    password: { from: '***', to: '***' },
  }),
}

beforeEach(() => {
  vi.clearAllMocks()
  api.monitoring.getChanges.mockResolvedValue({
    success: true, data: { changes: [UPDATE_ROW, CREATE_ROW], total: 2, page: 0, size: 25 },
  })
  api.monitoring.getChangeDetail.mockResolvedValue({
    success: true,
    data: { ...CREATE_ROW, snapshot: JSON.stringify({ name: 'Ödeme portu', intervalSeconds: 300, active: true }) },
  })
})

function draw(props = {}) {
  return render(<ChangeHistoryTab t={t} kind="port" monitorId={4} teamNames={{ 5: 'Kanal' }} {...props} />)
}

describe('ChangeHistoryTab', () => {
  it('olayları zaman çizelgesi olarak çizer ve aktör/IP künyesini gösterir', async () => {
    draw()
    await waitFor(() => expect(api.monitoring.getChanges).toHaveBeenCalledWith('port', 4, { page: 0, size: 25 }))

    expect(await screen.findByText('Güncellendi')).toBeInTheDocument()
    expect(screen.getByText('Oluşturuldu')).toBeInTheDocument()
    expect(screen.getAllByText('10.20.30.40').length).toBeGreaterThan(0)
    // User-Agent KISALTILIR ama tamı title'da durur (veri kaybı yok).
    expect(screen.getAllByTitle(/Mozilla\/5\.0/)[0]).toBeInTheDocument()
  })

  it('değişen alanı "eski → yeni" olarak, ETİKETİYLE gösterir', async () => {
    draw()
    fireEvent.click(await screen.findByText('Güncellendi'))

    const chip = await screen.findByTitle(/Kontrol sıklığı/)
    // Saniye insancıllaştırılır: 300 → "5 dk", 60 → "1 dk" (çıplak sayı okunmuyor).
    expect(chip.textContent).toContain('5 dk')
    expect(chip.textContent).toContain('1 dk')
  })

  it('maskeli değer kilit ikonuyla gösterilir — "değişti ama göremiyorum" ile "boş" ayrılır', async () => {
    const { container } = draw()
    fireEvent.click(await screen.findByText('Güncellendi'))

    await screen.findByTitle(/Kontrol sıklığı/)
    const masked = screen.getByTitle(/Parola/)
    expect(masked.textContent).toContain('***')
    expect(container.querySelector('.chg-chip-lock')).not.toBeNull()
  })

  it('İLK KAYIT seçilince snapshot "ilk değerler" olarak açılır', async () => {
    draw()
    fireEvent.click(await screen.findByText('Oluşturuldu'))

    await waitFor(() => expect(api.monitoring.getChangeDetail).toHaveBeenCalledWith('port', 4, 0))
    expect(await screen.findByText('chg.initialValues')).toBeInTheDocument()
    expect(screen.getByText('Ödeme portu')).toBeInTheDocument()
    // boolean rozet olarak insancıllaştırılır
    expect(screen.getByText('Açık')).toBeInTheDocument()
  })

  it('kullanıcının yazdığı değişiklik nedeni notu görünür', async () => {
    draw()
    // Not hem çizelge satırında hem açılan detayda durur — ikisi de bilinçli.
    expect(await screen.findByText('Alarm çok geç açılıyordu')).toBeInTheDocument()
    fireEvent.click(screen.getByText('Güncellendi'))
    await waitFor(() => expect(screen.getAllByText('Alarm çok geç açılıyordu')).toHaveLength(2))
  })

  it('BOZUK changes JSON çökertmez — satır çizilir, çip çizilmez', async () => {
    // ResponseTimeChart kuralı: malformed kayıt DÜŞÜRÜLÜR, ekran ErrorBoundary'ye gitmez.
    api.monitoring.getChanges.mockResolvedValue({
      success: true,
      data: { changes: [{ ...UPDATE_ROW, changes: '{bozuk json' }], total: 1 },
    })
    const { container } = draw()

    expect(await screen.findByText('Güncellendi')).toBeInTheDocument()
    fireEvent.click(screen.getByText('Güncellendi'))
    expect(container.querySelector('.chg-chip')).toBeNull()
  })

  it('kayıt yoksa boş durum gösterilir', async () => {
    api.monitoring.getChanges.mockResolvedValue({ success: true, data: { changes: [], total: 0 } })
    draw()
    expect(await screen.findByText('chg.emptyTitle')).toBeInTheDocument()
  })

  it('sunucu hata verirse uyarı gösterilir, ekran çökmez', async () => {
    api.monitoring.getChanges.mockResolvedValue({ success: false, error: 'yetki yok' })
    draw()
    expect(await screen.findByText('yetki yok')).toBeInTheDocument()
  })

  // ── Geri döndürme (K6) ────────────────────────────────────────────────────

  it('geri döndürme düğmesi yalnız YÖNETEBİLENE ve durum kaydı olan olayda çıkar', async () => {
    draw()
    fireEvent.click(await screen.findByText('Oluşturuldu'))
    await screen.findByText('chg.initialValues')
    // canManage=false → düğme HİÇ çizilmez (görünüp 403 vermesi kullanıcıyı boşuna umutlandırırdı)
    expect(screen.queryByRole('button', { name: /chg.restoreAction/ })).toBeNull()
  })

  it('geri döndürme GEREKÇE ister ve notu uca iletir, sonra listeyi tazeler', async () => {
    api.monitoring.restoreChange.mockResolvedValue({
      success: true, data: { restored: true, fields: ['port', 'active'], skipped_masked: [] },
    })
    draw({ canManage: true })
    fireEvent.click(await screen.findByText('Oluşturuldu'))

    fireEvent.click(await screen.findByRole('button', { name: /chg.restoreAction/ }))
    // Zorunlu gerekçe modali: not geçerli olana kadar onay pasif (Dialog sözleşmesi).
    const box = await screen.findByRole('textbox')
    fireEvent.change(box, { target: { value: 'yanlış eşik geri alındı' } })
    fireEvent.click(screen.getAllByRole('button', { name: /chg.restoreAction/ }).at(-1))

    await waitFor(() => expect(api.monitoring.restoreChange)
      .toHaveBeenCalledWith('port', 4, 0, 'yanlış eşik geri alındı'))
    // Tazeleme: geri alma da yeni bir satır ürettiği için liste yeniden çekilmeli.
    await waitFor(() => expect(api.monitoring.getChanges).toHaveBeenCalledTimes(2))
  })

  it('geri döndürme başarısızsa ekran çökmez, liste tazelenmez', async () => {
    api.monitoring.restoreChange.mockResolvedValue({ success: false, error: 'yetkiniz yok' })
    draw({ canManage: true })
    fireEvent.click(await screen.findByText('Oluşturuldu'))

    fireEvent.click(await screen.findByRole('button', { name: /chg.restoreAction/ }))
    fireEvent.change(await screen.findByRole('textbox'), { target: { value: 'geri alma denemesi yapıldı' } })
    fireEvent.click(screen.getAllByRole('button', { name: /chg.restoreAction/ }).at(-1))

    await waitFor(() => expect(api.monitoring.restoreChange).toHaveBeenCalled())
    expect(screen.getByText('Oluşturuldu')).toBeInTheDocument()
    expect(api.monitoring.getChanges).toHaveBeenCalledTimes(1)
  })
})
