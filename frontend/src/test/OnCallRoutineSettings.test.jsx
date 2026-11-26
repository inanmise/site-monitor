import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, waitFor, fireEvent, within } from './test-utils.jsx'

const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
vi.mock('../api/client', () => ({
  formatDate: (s) => s ?? '',
  api: withApiFallback({
    admin: { oncallRoutines: { settings: vi.fn(), saveSettings: vi.fn(), overview: vi.fn() } },
  }),
}))

// Kap genişliği: varsayılan 0 (jsdom — tablo); kart düzeni testi 500 verir
const width = vi.hoisted(() => ({ value: 0 }))
vi.mock('../hooks/useElementWidth.js', () => ({
  useElementWidth: () => [() => {}, width.value],
  useElementWidthState: () => [width.value, () => {}],
}))

import { api } from '../api/client'
import { EN } from '../i18n/en.js'
import OnCallRoutineSettings from '../components/admin/OnCallRoutineSettings.jsx'
import {
  ORS_RETENTION_POLICY, ORS_SWITCHES, changedSettings, completionPct, normalizeSettings, overviewRows, pctTone, routineState, todayCode,
} from '../components/admin/onCallRoutineSettingsModel.js'

/**
 * Ayarlar → Platform → Nöbet rutinleri (2026-11-26). Kurum geneli ayarlar GLOBAL_ONLY: kapsamlı müdür salt okunur görür
 * (bant, kilitli seçici / anahtarlar, kayıt çubuğu yok). Kayıt YALNIZ değişen anahtarları gönderir; alan hatası alanın altında.
 * Takım özeti kap ≥ 760 px tablo, daha darda kart; bilinmeyen değer "—", asla %0 / yeşil. Okuma başarısızsa "okunamadı" + yeniden
 * dene; eski pod (404) "bu sürümde yok".
 */
const SETTINGS = {
  success: true,
  data: {
    values: { team_ids: [2], push_reminders: true, completion_notice: true, handover_notice: false },
    retention_days: 730, read_only: false,
    teams: [{ id: 1, name: 'Dijital Kanallar' }, { id: 2, name: 'Altyapı ve Ağ Operasyon Ekibi (Merkez — Gece Vardiyası)' }],
  },
}
const OVERVIEW = {
  success: true,
  data: [
    { team_id: 1, team_name: 'Dijital Kanallar', module_visible: false, configured: true, enabled: true, tasks_active: 14,
      today_status: 'COMPLETED', last30: { workdays: 21, completed: 19, partial: 1, incomplete: 1, missed: 0 } },
    { team_id: 2, team_name: 'Altyapı ve Ağ Operasyon Ekibi (Merkez — Gece Vardiyası)', module_visible: true, configured: true,
      enabled: false, tasks_active: 8, today_status: 'PAUSED', last30: { workdays: 0, completed: 0, partial: 0, incomplete: 0, missed: 0 } },
    { team_id: 3, team_name: 'Kurulmamış Takım', module_visible: true, configured: false, enabled: false, tasks_active: null,
      today_status: null, last30: null },
  ],
}
const sw = (key) => screen.getByRole('switch', { name: EN[`or.adm.sw.${key}`] })

let views = []
const onView = (e) => views.push(e.detail)
beforeEach(() => {
  vi.clearAllMocks()
  width.value = 0
  views = []
  window.addEventListener('sm:apply-view', onView)
  api.admin.oncallRoutines.settings.mockResolvedValue(SETTINGS)
  api.admin.oncallRoutines.overview.mockResolvedValue(OVERVIEW)
  api.admin.oncallRoutines.saveSettings.mockImplementation(async (body) => ({
    success: true, data: { ...SETTINGS.data, values: { ...SETTINGS.data.values, ...body } },
  }))
})
afterEach(() => { window.removeEventListener('sm:apply-view', onView) })

describe('Nöbet rutinleri ayarları — kayıt', () => {
  it('anahtarlar sunucu değerleriyle çizilir; kayıt YALNIZ değişen anahtarı gönderir; kaydedilince çubuk temizlenir', async () => {
    const { container } = render(<OnCallRoutineSettings />)
    await waitFor(() => expect(container.querySelector('[data-slot="ors-switches"]')).not.toBeNull())
    expect(sw('push_reminders')).toBeChecked()
    expect(sw('completion_notice')).toBeChecked()
    expect(sw('handover_notice')).not.toBeChecked()
    const save = screen.getByRole('button', { name: EN['settings.save'] })
    expect(save).toBeDisabled()
    fireEvent.click(sw('handover_notice'))
    expect(container.querySelector('[data-slot="settings-save-bar"]')).toHaveAttribute('data-dirty', 'true')
    fireEvent.click(screen.getByRole('button', { name: EN['settings.save'] }))
    await waitFor(() => expect(api.admin.oncallRoutines.saveSettings).toHaveBeenCalledTimes(1))
    expect(api.admin.oncallRoutines.saveSettings).toHaveBeenCalledWith({ handover_notice: true })
    await waitFor(() => expect(container.querySelector('[data-slot="settings-save-bar"]')).not.toHaveAttribute('data-dirty'))
    expect(sw('handover_notice')).toBeChecked()
    // Takım listesi değişmediği için özet yeniden okunmaz
    expect(api.admin.oncallRoutines.overview).toHaveBeenCalledTimes(1)
  })

  it('aç-kapa aynı değere dönünce değişiklik yok sayılır — istek atılmaz', async () => {
    render(<OnCallRoutineSettings />)
    await waitFor(() => expect(sw('push_reminders')).toBeChecked())
    fireEvent.click(sw('push_reminders'))
    fireEvent.click(sw('push_reminders'))
    expect(screen.getByRole('button', { name: EN['settings.save'] })).toBeDisabled()
    expect(api.admin.oncallRoutines.saveSettings).not.toHaveBeenCalled()
  })

  it('sunucu alan hatası (team_ids) seçicinin ALTINDA gösterilir; toast değil', async () => {
    api.admin.oncallRoutines.saveSettings.mockResolvedValue({
      success: false, status: 400, code: 'VALIDATION_FAILED', field: 'team_ids', error: 'Bilinmeyen takım seçildi: 99. Listeden geçerli bir takım seçin.',
    })
    const { container } = render(<OnCallRoutineSettings />)
    await waitFor(() => expect(sw('push_reminders')).toBeChecked())
    fireEvent.click(sw('push_reminders'))
    fireEvent.click(screen.getByRole('button', { name: EN['settings.save'] }))
    const field = container.querySelector('[data-slot="ors-teams-field"]')
    await waitFor(() => expect(field).toHaveTextContent('Bilinmeyen takım seçildi: 99'))
    // Taslak korunur (değişiklik kaybolmaz)
    expect(sw('push_reminders')).not.toBeChecked()
  })

  it('takım seçicisi seçili takım adlarını kırpmaz, sarar (uzun AD takım adları)', async () => {
    const { container } = render(<OnCallRoutineSettings />)
    await waitFor(() => expect(container.querySelector('[data-slot="ors-switches"]')).not.toBeNull())
    const trigger = container.querySelector('[data-slot="ors-teams-field"] [role="combobox"]')
    const label = trigger.querySelector('span')
    expect(label.className).toContain('[overflow-wrap:anywhere]')
    expect(label.className).not.toContain('truncate')
  })

  it('seçim boş → "herkes" notu (seçim varken not yok)', async () => {
    const first = render(<OnCallRoutineSettings />)
    await waitFor(() => expect(first.container.querySelector('[data-slot="ors-switches"]')).not.toBeNull())
    expect(first.container.querySelector('[data-slot="ors-everyone"]')).toBeNull()
    first.unmount()
    api.admin.oncallRoutines.settings.mockResolvedValue({ ...SETTINGS, data: { ...SETTINGS.data, values: { ...SETTINGS.data.values, team_ids: [] } } })
    const { container } = render(<OnCallRoutineSettings />)
    await waitFor(() => expect(container.querySelector('[data-slot="ors-everyone"]')).not.toBeNull())
  })

  it('saklama notu gün sayısını söyler; "Veri Saklama\'da aç" politikayı odaklayan görünümü uygular', async () => {
    const { container } = render(<OnCallRoutineSettings />)
    await waitFor(() => expect(container.querySelector('[data-slot="ors-retention"]')).not.toBeNull())
    expect(container.querySelector('[data-slot="ors-retention"]')).toHaveTextContent(EN['or.adm.retention'].replace('{0}', '730'))
    fireEvent.click(container.querySelector('[data-slot="ors-retention-link"]'))
    await waitFor(() => expect(views).toHaveLength(1))
    expect(views[0]).toEqual({ tab: 'settings', params: { sec: 'retention', ret_focus: ORS_RETENTION_POLICY } })
  })
})

describe('Nöbet rutinleri ayarları — salt okunur ve okuma durumları', () => {
  it('kapsamlı müdür (readOnly / read_only) salt okunur: bant var, anahtarlar kilitli, kayıt çubuğu ve saklama bağlantısı yok', async () => {
    for (const props of [{ readOnly: true }, {}]) {
      if (!props.readOnly) {
        api.admin.oncallRoutines.settings.mockResolvedValue({ ...SETTINGS, data: { ...SETTINGS.data, read_only: true } })
      }
      const { container, unmount } = render(<OnCallRoutineSettings {...props} />)
      await waitFor(() => expect(container.querySelector('[data-slot="ors-readonly"]')).not.toBeNull())
      for (const k of ORS_SWITCHES) expect(sw(k)).toBeDisabled()
      expect(container.querySelector('[data-slot="settings-save-bar"]')).toBeNull()
      expect(container.querySelector('[data-slot="ors-retention-link"]')).toBeNull()
      unmount()
    }
  })

  it('ilk okuma başarısız → kırmızı "okunamadı" + yeniden dene; yeniden dene başarılı olunca form çizilir', async () => {
    api.admin.oncallRoutines.settings.mockResolvedValueOnce({ success: false, status: 503, error: 'Sunucu geçici olarak yanıt vermiyor.' })
    const { container } = render(<OnCallRoutineSettings />)
    const retry = await waitFor(() => {
      const b = container.querySelector('[data-slot="ors-retry"]')
      expect(b).not.toBeNull()
      return b
    })
    expect(container.querySelector('[data-slot="ors-switches"]')).toBeNull()
    fireEvent.click(retry)
    await waitFor(() => expect(container.querySelector('[data-slot="ors-switches"]')).not.toBeNull())
  })

  it('eski pod (404) → "bu sürümde yok"; anahtar ya da "herkes" notu uydurulmaz', async () => {
    api.admin.oncallRoutines.settings.mockResolvedValue({ success: false, status: 404, code: 'NOT_FOUND', error: 'x' })
    api.admin.oncallRoutines.overview.mockResolvedValue({ success: false, status: 404, code: 'NOT_FOUND', error: 'x' })
    const { container } = render(<OnCallRoutineSettings />)
    await waitFor(() => expect(container.querySelector('[data-slot="ors-unsupported"]')).not.toBeNull())
    expect(container.querySelector('[data-slot="ors-switches"]')).toBeNull()
    expect(container.querySelector('[data-slot="ors-everyone"]')).toBeNull()
    await waitFor(() => expect(container.querySelector('[data-slot="ors-teams"]')).toHaveAttribute('data-status', 'unsupported'))
  })
})

describe('Nöbet rutinleri ayarları — takım özeti', () => {
  it('geniş kapta tablo: durum, aktif görev, bugün, son 30 gün; bilinmeyen "—"; modül kapalı rozeti; uzun ad sarar', async () => {
    const { container } = render(<OnCallRoutineSettings />)
    await waitFor(() => expect(container.querySelector('[data-slot="ors-teams-table"]')).not.toBeNull())
    const rows = container.querySelectorAll('[data-slot="ors-team-row"]')
    expect(rows).toHaveLength(3)
    // Takım 1: %95 (19 + 1) / 21 → aşağı yuvarlanmış 95, yeşil; modül bu takıma kapalı
    expect(rows[0].querySelector('[data-slot="ors-pct"]')).toHaveAttribute('data-tone', 'success')
    expect(rows[0].querySelector('[data-slot="ors-pct"]')).toHaveTextContent(EN['or.adm.pct'].replace('{0}', '95'))
    expect(rows[0].querySelector('[data-slot="ors-hidden"]')).not.toBeNull()
    expect(rows[0].querySelector('[data-slot="ors-today"]')).toHaveAttribute('data-status', 'COMPLETED')
    // Takım 2: duraklatılmış, iş günü yok → "%0" DEĞİL
    expect(rows[1].querySelector('[data-slot="ors-state"]')).toHaveAttribute('data-state', 'paused')
    expect(rows[1].querySelector('[data-slot="ors-pct"]')).toHaveAttribute('data-unknown', 'true')
    expect(rows[1].querySelector('[data-slot="ors-pct"]').textContent).not.toMatch(/0\s*%|%\s*0/)
    const name = rows[1].querySelector('[data-slot="ors-team-name"]')
    expect(name.className).toMatch(/overflow-wrap:anywhere/)
    expect(name.className).not.toMatch(/truncate/)
    // Takım 3: kurulmamış → bugün / son 30 gün yok, aktif görev "—"
    expect(rows[2].querySelector('[data-slot="ors-state"]')).toHaveAttribute('data-state', 'none')
    expect(rows[2].querySelector('[data-slot="ors-tasks"]')).toHaveTextContent('—')
    expect(rows[2].querySelector('[data-slot="ors-today"]')).toBeNull()
  })

  it('dar kapta kartlar', async () => {
    width.value = 500
    const { container } = render(<OnCallRoutineSettings />)
    await waitFor(() => expect(container.querySelector('[data-slot="ors-teams-cards"]')).not.toBeNull())
    expect(container.querySelector('[data-slot="ors-teams-table"]')).toBeNull()
    expect(container.querySelector('[data-slot="ors-teams"]')).toHaveAttribute('data-layout', 'cards')
    const cards = within(container.querySelector('[data-slot="ors-teams-cards"]')).getAllByRole('listitem')
    expect(cards).toHaveLength(3)
  })

  it('özet okunamazsa kendi kırmızı bloğu + yeniden dene (ayarlar etkilenmez); asla "takım yok" değil', async () => {
    api.admin.oncallRoutines.overview.mockResolvedValueOnce({ success: false, status: 500, error: 'boom' })
    const { container } = render(<OnCallRoutineSettings />)
    await waitFor(() => expect(container.querySelector('[data-slot="ors-teams-retry"]')).not.toBeNull())
    expect(container.querySelector('[data-slot="ors-teams-empty"]')).toBeNull()
    expect(container.querySelector('[data-slot="ors-switches"]')).not.toBeNull()
    fireEvent.click(container.querySelector('[data-slot="ors-teams-retry"]'))
    await waitFor(() => expect(container.querySelector('[data-slot="ors-teams-table"]')).not.toBeNull())
  })
})

describe('Nöbet rutinleri ayarları modeli (saf)', () => {
  it('changedSettings: yalnız değişen anahtarlar; takım sırası önemsiz; bilinmeyen (null) kayıtlı anahtar gönderilmez', () => {
    const saved = { team_ids: [1, 2], push_reminders: true, completion_notice: true, handover_notice: null }
    expect(changedSettings(saved, { ...saved, team_ids: [2, 1] })).toEqual({})
    expect(changedSettings(saved, { ...saved, team_ids: [2] })).toEqual({ team_ids: [2] })
    expect(changedSettings(saved, { ...saved, push_reminders: false })).toEqual({ push_reminders: false })
    expect(changedSettings(saved, { ...saved, handover_notice: true })).toEqual({})
  })

  it('normalizeSettings: eksik anahtar null (bilinmiyor); read_only ya da çağıranın salt okunur bilgisi', () => {
    const n = normalizeSettings({ values: { team_ids: ['3', 3, 'x'], push_reminders: false }, retention_days: 0 })
    expect(n.values).toEqual({ team_ids: [3], push_reminders: false, completion_notice: null, handover_notice: null })
    expect(n.retentionDays).toBeNull()
    expect(n.readOnly).toBe(false)
    expect(normalizeSettings({ read_only: true }).readOnly).toBe(true)
    expect(normalizeSettings({}, true).readOnly).toBe(true)
  })

  it('completionPct / pctTone / routineState / todayCode / overviewRows', () => {
    expect(completionPct({ workdays: 21, completed: 19, partial: 1 })).toBe(95)
    expect(completionPct({ workdays: 3, completed: 3, partial: 0 })).toBe(100)
    expect(completionPct({ workdays: 3, completed: 2, partial: 0 })).toBe(66)
    expect(completionPct({ workdays: 0, completed: 0, partial: 0 })).toBeNull()
    expect(completionPct(null)).toBeNull()
    expect(completionPct({ workdays: 5 })).toBeNull()
    expect(pctTone(null)).toBe('muted')
    expect(pctTone(90)).toBe('success')
    expect(pctTone(70)).toBe('warning')
    expect(pctTone(69)).toBe('danger')
    expect(routineState({ configured: false })).toBe('none')
    expect(routineState({ configured: true, enabled: false })).toBe('paused')
    expect(routineState({ configured: true, enabled: true })).toBe('active')
    expect(routineState({})).toBe('unknown')
    expect(todayCode({ today_status: 'missed' })).toBe('MISSED')
    expect(todayCode({ today_status: 'BOGUS' })).toBe('UNKNOWN')
    expect(overviewRows(null)).toBeNull()
    expect(overviewRows([{ team_name: 'id yok' }, { team_id: 7 }])).toHaveLength(1)
  })
})
