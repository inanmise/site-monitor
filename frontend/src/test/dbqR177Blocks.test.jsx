import { describe, it, expect, vi, beforeEach, beforeAll, afterAll } from 'vitest'
import { useState } from 'react'
import { render, screen, waitFor, fireEvent, within } from './test-utils.jsx'
import { pressMenuTrigger } from './helpers/dropdownMenu.js'
import { LANG_STORAGE_KEY } from '../i18n/dateLocale.js'
import { newRule } from '../components/dbquery/ruleModel.js'

// r177 (2026-10-31) — Veritabanı Sorgu İzleme yapı taşlarının yeni görünümleri: tazelik kuralı + taban seçimi + COUNT
// tuzağı (kural oluşturucu), deneme paneli (UNKNOWN, maliyet OFF, uyarılar, kolon menüsü), geriye dönük deneme, satır
// kaydıran sorgu düzenleyici, bağlantı (TCPS, zayıf SSL, DBA betiği, hesap yetkisi), yerleşik şablonlar + yönetici
// eylemleri, tatil takvimi kapsamı, grafik eşik çizgileri.
const { withApiFallback } = await vi.hoisted(() => import('./apiMock.js'))
vi.mock('../api/client', () => ({
  formatDate: (s) => (s ? `T:${s}` : ''),
  formatDateSec: (s) => String(s),
  api: withApiFallback({
    monitoring: {
      dbQueryBacktest: vi.fn(), dbQuerySqlCheck: vi.fn(), dbConnections: vi.fn(), testDbConnection: vi.fn(), testDbConnectionDraft: vi.fn(),
      createDbConnection: vi.fn(), updateDbConnection: vi.fn(), deleteDbConnection: vi.fn(),
      dbQueryTemplates: vi.fn(), createDbQueryTemplate: vi.fn(), updateDbQueryTemplate: vi.fn(), deleteDbQueryTemplate: vi.fn(),
      dbQuerySchedulePreview: vi.fn(), getDbQuerySeries: vi.fn(),
    },
    admin: { getHolidays: vi.fn(), saveHolidays: vi.fn(), addFixedHolidays: vi.fn() },
  }),
}))
import { api } from '../api/client'
import RuleBuilder from '../components/dbquery/RuleBuilder.jsx'
import DbQueryTestPanel from '../components/dbquery/DbQueryTestPanel.jsx'
import BacktestPanel from '../components/dbquery/BacktestPanel.jsx'
import SqlEditor from '../components/dbquery/SqlEditor.jsx'
import ConnectionForm from '../components/dbquery/ConnectionForm.jsx'
import ConnectionsManager from '../components/dbquery/ConnectionsManager.jsx'
import TemplatePicker from '../components/dbquery/TemplatePicker.jsx'
import TemplatesManager from '../components/dbquery/TemplatesManager.jsx'
import ScheduleEditor from '../components/dbquery/ScheduleEditor.jsx'
import HolidayCalendarSettings from '../components/admin/HolidayCalendarSettings.jsx'
import ResponseTimeChart from '../components/ResponseTimeChart.jsx'
import { emptyForm } from '../components/dbquery/connectionModel.js'

beforeEach(() => {
  vi.clearAllMocks()
  try { localStorage.clear(); localStorage.setItem(LANG_STORAGE_KEY, 'tr') } catch { /* yok */ }
  api.monitoring.dbQuerySqlCheck.mockResolvedValue({ success: true, data: { ok: true, violations: [], tables: [] } })
  api.monitoring.dbQuerySchedulePreview.mockResolvedValue({ success: true, data: { valid: true, errors: [], next: [], effective_interval_seconds: 300, never_runs: false } })
})

function RulesHarness({ initial = [], spy, ...rest }) {
  const [rules, setRules] = useState(initial)
  return <RuleBuilder rules={rules} logic="ANY" onLogicChange={() => {}} {...rest} onChange={(r) => { setRules(r); spy?.(r) }} />
}
const card = (i = 0) => document.querySelectorAll('[data-slot="dbq-rule-card"]')[i]

describe('RuleBuilder — tazelik, taban, COUNT tuzağı', () => {
  it('tazelik: birim seçimi (dk) saniyeye çevrilir; özet "15 dakikadan eskiyse"; birim değişince gösterim değişir, değer aynı', () => {
    const spy = vi.fn()
    render(<RulesHarness spy={spy} initial={[{ ...newRule('FRESHNESS', 'r-f'), column: 'son_kayit' }]} />)
    const c = card()
    expect(c.getAttribute('data-kind')).toBe('FRESHNESS')
    expect(within(c).getByLabelText('Toplama').value).toBe('MAX')
    expect([...within(c).getByLabelText('Koşul').options].map((o) => o.value)).toEqual(['GT', 'GTE'])
    const unit = c.querySelector('[data-slot="dbq-rule-age-unit"]')
    expect(unit.value).toBe('min')
    fireEvent.change(within(c).getByLabelText(/Uyarı eşiği/), { target: { value: '15' } })
    expect(spy.mock.calls.at(-1)[0][0].warning).toBe(900)
    fireEvent.change(within(c).getByLabelText(/Kritik eşik/), { target: { value: '1,5' } })
    expect(spy.mock.calls.at(-1)[0][0].critical).toBe(90)
    expect(c.querySelector('[data-slot="dbq-rule-summary"]').textContent).toContain('15 dakikadan eskiyse UYARI')
    fireEvent.change(unit, { target: { value: 'sec' } })
    expect(within(c).getByLabelText(/Uyarı eşiği/)).toHaveValue('900')
    expect(spy.mock.calls.at(-1)[0][0].warning).toBe(900)
  })

  it('değişim: taban seçimi change.baseline yazar; özet tabanı söyler', () => {
    const spy = vi.fn()
    render(<RulesHarness spy={spy} initial={[{ ...newRule('CHANGE', 'r-c'), warning: 20 }]} />)
    const sel = card().querySelector('[data-change-field="baseline"]')
    expect(sel.value).toBe('previous')
    fireEvent.change(sel, { target: { value: 'same_time_yesterday' } })
    expect(spy.mock.calls.at(-1)[0][0].change).toMatchObject({ of: 'ROW_COUNT', baseline: 'same_time_yesterday' })
    expect(card().querySelector('[data-slot="dbq-rule-summary"]').textContent).toContain('(dün aynı saate göre)')
  })

  it('COUNT tuzağı: işaretli satır sayısı kuralında uyarı + "Kolon değeri kuralına çevir" (kolon önerisiyle, eşik korunur)', () => {
    const spy = vi.fn()
    render(<RulesHarness spy={spy} initial={[{ ...newRule('ROW_COUNT', 'r-1'), warning: 20 }, newRule('ROW_COUNT', 'r-2')]}
      trapIndexes={new Set([0])} trapColumn="hata_adedi" />)
    expect(card(0).querySelector('[data-slot="dbq-rule-count-trap"]').textContent).toContain('hata_adedi')
    expect(card(1).querySelector('[data-slot="dbq-rule-count-trap"]')).toBeNull()
    fireEvent.click(within(card(0)).getByRole('button', { name: 'Kolon değeri kuralına çevir' }))
    expect(spy.mock.calls.at(-1)[0][0]).toMatchObject({ id: 'r-1', kind: 'COLUMN_VALUE', column: 'hata_adedi', aggregate: 'FIRST', warning: 20 })
    expect(card(0).querySelector('[data-slot="dbq-rule-count-trap"]')).toBeNull()
  })

  it('"Kural ekle" varsayılanı çağıranın fabrikasından (tek satır özet → kolon değeri)', () => {
    const spy = vi.fn()
    render(<RulesHarness spy={spy} newRuleFactory={() => ({ ...newRule('COLUMN_VALUE', 'r-x'), column: 'adet' })} />)
    fireEvent.click(screen.getByRole('button', { name: /Kural ekle/ }))
    expect(spy.mock.calls.at(-1)[0][0]).toMatchObject({ kind: 'COLUMN_VALUE', column: 'adet' })
  })
})

describe('DbQueryTestPanel — r177', () => {
  it('UNKNOWN nötr "Değerlendirilemedi" + ipucu; maliyet OFF nötr "denetlenmedi" (rakam yok)', () => {
    render(<DbQueryTestPanel result={{ status: 'UNKNOWN', duration_ms: 10, row_count: 1, columns: [] }}
      plan={{ supported: true, verdict: 'OFF', cost: 5, rows: 1, message: 'kapalı' }} />)
    const root = document.querySelector('[data-slot="dbq-test"]')
    expect(root.getAttribute('data-status')).toBe('UNKNOWN')
    expect(root.textContent).toContain('Değerlendirilemedi')
    expect(document.querySelector('[data-slot="dbq-test-unevaluated"]')).not.toBeNull()
    const plan = document.querySelector('[data-slot="dbq-test-plan"]')
    expect(plan.getAttribute('data-verdict')).toBe('OFF')
    expect(plan.textContent).toContain('Maliyet denetimi kapalı')
    expect(plan.textContent).not.toContain('Tahmini maliyet')
  })

  it('uyarılar: bilinen kod çevrilir, bilinmeyen sunucu iletisiyle; hassas kolon işareti; maskeli kolon "maskeli"', () => {
    render(<DbQueryTestPanel maskedColumns={['IBAN']} result={{ status: 'OK', row_count: 3, columns: [
      { name: 'tckn', type: 'varchar' }, { name: 'iban', type: 'varchar' }, { name: 'adet', type: 'int8' }],
      suggested_masks: ['TCKN', 'iban'],
      warnings: [{ code: 'SELECT_STAR' }, { code: 'SENSITIVE_UNMASKED', columns: ['tckn'] }, { code: 'FUTURE_X', message: 'Sunucu iletisi' }] }} />)
    const ws = [...document.querySelectorAll('[data-slot="dbq-test-warning"]')]
    expect(ws.map((w) => w.getAttribute('data-code'))).toEqual(['SELECT_STAR', 'SENSITIVE_UNMASKED', 'FUTURE_X'])
    expect(ws[1].textContent).toContain('tckn')
    expect(ws[2].textContent).toContain('Sunucu iletisi')
    const cols = [...document.querySelectorAll('[data-slot="dbq-col"]')]
    expect(cols[0].querySelector('[data-slot="dbq-col-sensitive"]')).not.toBeNull()
    expect(cols[1].querySelector('[data-slot="dbq-col-masked"]')).not.toBeNull()
    expect(cols[1].querySelector('[data-slot="dbq-col-sensitive"]')).toBeNull()
  })

  it('kolon menüsü: türe göre kural seçenekleri ve maskele → onColumnAction; satır verisi yok', async () => {
    const onAction = vi.fn()
    render(<DbQueryTestPanel onColumnAction={onAction} result={{ status: 'OK', row_count: 1, columns: [
      { name: 'son_kayit', type: 'timestamptz' }, { name: 'adet', type: 'int8' }] }} />)
    pressMenuTrigger(screen.getByRole('button', { name: 'son_kayit kolonu için eylemler' }))
    const items = await screen.findAllByRole('menuitem')
    expect(items.map((i) => i.getAttribute('data-action'))).toEqual(['rule:FRESHNESS', 'rule:COLUMN_VALUE', 'rule:TEXT_MATCH', 'mask'])
    fireEvent.click(items[0])
    expect(onAction).toHaveBeenCalledWith('rule:FRESHNESS', { name: 'son_kayit', type: 'timestamptz' })
    expect(document.querySelector('[data-slot="dbq-test-norows"]')).not.toBeNull()
  })

  it('eylem yoksa (kilitli) kolonlar düz rozet', () => {
    render(<DbQueryTestPanel result={{ status: 'OK', row_count: 1, columns: [{ name: 'adet', type: 'int8' }] }} />)
    expect(screen.queryByRole('button', { name: /kolonu için eylemler/ })).toBeNull()
    expect(document.querySelector('[data-slot="dbq-col"]').tagName).not.toBe('BUTTON')
  })
})

describe('BacktestPanel (2.D)', () => {
  const RULES = [{ ...newRule('ROW_COUNT', 'r1'), label: 'Bekleyen', warning: 10, critical: 100 }]
  it('formdaki kurallar + gün seçimi gider; özet, alarm sayısı, kural satırları; kurallar değişince "eski"', async () => {
    api.monitoring.dbQueryBacktest.mockResolvedValue({ success: true, data: {
      runs: 2880, evaluated: 2700, would_breach: { warning: 12, critical: 3 }, would_open_alarms: 2,
      first_at: '2026-10-02T08:00:00', last_at: '2026-10-29T09:30:00',
      per_rule: [{ id: 'r1', evaluated: 2700, warning: 12, critical: 3 }, { id: 'zz', skipped_reason: 'something_new' }],
    } })
    const { rerender } = render(<BacktestPanel monitorId={42} rules={RULES} ruleLogic="ANY" />)
    fireEvent.click(screen.getByRole('button', { name: 'Son 90 gün' }))
    fireEvent.click(screen.getByRole('button', { name: 'Geriye dönük dene' }))
    await waitFor(() => expect(document.querySelector('[data-slot="dbq-backtest-summary"]')).not.toBeNull())
    expect(api.monitoring.dbQueryBacktest).toHaveBeenCalledWith(42, { rules: RULES, rule_logic: 'ANY', days: 90 })
    const sum = document.querySelector('[data-slot="dbq-backtest-summary"]').textContent
    expect(sum).toContain('Son 90 günde 2.880 zamanlanmış çalıştırmanın 2.700 tanesi değerlendirildi: 12 uyarı, 3 kritik ihlal.')
    expect(sum).toContain('yaklaşık 2 alarm')
    const rows = [...document.querySelectorAll('[data-slot="dbq-backtest-rule"]')]
    expect(rows[0].textContent).toContain('Bekleyen')
    expect(rows[1].textContent).toContain('uygun saklı ölçüm yok')
    expect(rows[1].textContent).not.toContain('something_new')
    rerender(<BacktestPanel monitorId={42} rules={[{ ...RULES[0], warning: 50 }]} ruleLogic="ANY" />)
    expect(document.querySelector('[data-slot="dbq-backtest-stale"]')).not.toBeNull()
  })

  it('eski sunucu (404) "desteklenmiyor"; bekleme bilgi tonu; kural hatası varken istek gitmez', async () => {
    api.monitoring.dbQueryBacktest.mockResolvedValueOnce({ success: false, status: 404, code: 'NOT_FOUND', error: 'yok' })
    render(<BacktestPanel monitorId={7} rules={RULES} ruleLogic="ALL" />)
    fireEvent.click(screen.getByRole('button', { name: 'Geriye dönük dene' }))
    await waitFor(() => expect(document.querySelector('[data-slot="dbq-backtest"]').getAttribute('data-state')).toBe('unsupported'))
    api.monitoring.dbQueryBacktest.mockResolvedValueOnce({ success: false, status: 429, code: 'RATE_LIMITED', error: '20 sn sonra yeniden deneyin.' })
    fireEvent.click(screen.getByRole('button', { name: 'Geriye dönük dene' }))
    await waitFor(() => expect(screen.getByText('20 sn sonra yeniden deneyin.')).toBeInTheDocument())
    expect(api.monitoring.dbQueryBacktest.mock.calls[1][1].rule_logic).toBe('ALL')
  })

  it('geçersiz kural ya da kuralsız: istek yok, alan söylenir', async () => {
    const { rerender } = render(<BacktestPanel monitorId={7} rules={[newRule('ROW_COUNT', 'r')]} ruleLogic="ANY" />)
    fireEvent.click(screen.getByRole('button', { name: 'Geriye dönük dene' }))
    expect(document.querySelector('[data-slot="dbq-backtest"]').getAttribute('data-state')).toBe('invalid')
    rerender(<BacktestPanel monitorId={7} rules={[]} ruleLogic="ANY" />)
    fireEvent.click(screen.getByRole('button', { name: 'Geriye dönük dene' }))
    expect(document.querySelector('[data-slot="dbq-backtest"]').getAttribute('data-state')).toBe('noRules')
    expect(api.monitoring.dbQueryBacktest).not.toHaveBeenCalled()
  })
})

describe('SqlEditor — satır kaydırma (2.C)', () => {
  it('düğme kaydırmayı açar: satır cetveli gizlenir, metin kaydırır; kapatınca geri gelir', () => {
    render(<SqlEditor value={'SELECT COUNT(*) AS hata FROM uzun_tablo_adi WHERE durum = \'ERROR\''} onChange={() => {}} dbType="POSTGRESQL" />)
    const btn = document.querySelector('[data-slot="dbq-sql-wrap"]')
    const ta = () => document.querySelector('[data-slot="dbq-sql-editor"] textarea')
    expect(btn.getAttribute('aria-pressed')).toBe('false')
    expect(ta().getAttribute('wrap')).toBe('off')
    expect(document.querySelector('[data-slot="dbq-sql-gutter"]')).not.toBeNull()
    fireEvent.click(btn)
    expect(btn.getAttribute('aria-pressed')).toBe('true')
    expect(ta().getAttribute('wrap')).toBe('soft')
    expect(document.querySelector('[data-slot="dbq-sql-gutter"]')).toBeNull()
    fireEvent.click(btn)
    expect(document.querySelector('[data-slot="dbq-sql-gutter"]')).not.toBeNull()
  })
})

describe('Bağlantı — TCPS, zayıf SSL, DBA betiği, hesap yetkisi (2.B)', () => {
  function FormHarness({ initial }) {
    const [f, setF] = useState(initial)
    return <ConnectionForm form={f} onChange={setF} />
  }

  it('yeni PostgreSQL require → uyarı yok; prefer / disable → şifresiz uyarısı', () => {
    render(<FormHarness initial={emptyForm(1)} />)
    expect(document.querySelector('[data-slot="dbq-conn-ssl-weak"]')).toBeNull()
    fireEvent.change(screen.getByLabelText('SSL kipi'), { target: { value: 'prefer' } })
    expect(document.querySelector('[data-slot="dbq-conn-ssl-weak"]').getAttribute('data-mode')).toBe('prefer')
    fireEvent.change(screen.getByLabelText('SSL kipi'), { target: { value: 'disable' } })
    expect(document.querySelector('[data-slot="dbq-conn-ssl-weak"]').textContent).toContain('hiç şifrelenmez')
  })

  it('Oracle TCPS anahtarı: port 1521 → 2484 → 1521', () => {
    render(<FormHarness initial={{ ...emptyForm(1, 'ORACLE'), port: '1521' }} />)
    const port = screen.getByLabelText(/^Port/)
    expect(port).toHaveValue('1521')
    fireEvent.click(screen.getByRole('switch', { name: /TCPS/ }))
    expect(port).toHaveValue('2484')
    expect(document.querySelector('[data-slot="dbq-conn-tcps"]').textContent).toContain('TLS ile şifrelenir')
    fireEvent.click(screen.getByRole('switch', { name: /TCPS/ }))
    expect(port).toHaveValue('1521')
  })

  it('DBA betiği: kapalı başlar, açılınca türün betiği + kopyala düğmesi', () => {
    render(<FormHarness initial={emptyForm(1, 'MSSQL')} />)
    expect(document.querySelector('[data-slot="dbq-conn-dba-script"]')).toBeNull()
    fireEvent.click(screen.getByRole('button', { name: /Salt-okur hesap betiği/ }))
    expect(document.querySelector('[data-slot="dbq-conn-dba-script"]').textContent).toContain('DENY INSERT, UPDATE, DELETE, ALTER, EXECUTE')
    expect(screen.getByRole('button', { name: 'Betiği kopyala' })).toBeInTheDocument()
  })

  const PG = { id: 7, team_id: 3, name: 'Rapor DB', db_type: 'POSTGRESQL', host: 'h', port: 5432, database_name: 'd', username: 'u',
    password_set: true, ssl_mode: 'prefer', connect_timeout_ms: 10000, can_edit: true, used_by: [], tables_used: [],
    health: { state: 'ok', privileges: { checked: true, read_only: false, findings: [{ code: 'CAN_WRITE_TABLES', detail: 'public.orders' }] } } }

  it('ayrıntı: sağlıkta yetki özeti (yazabilen + bulgular) ve prefer uyarısı; test sonucu salt okunur → yeşil', async () => {
    api.monitoring.dbConnections.mockResolvedValue({ success: true, data: [PG] })
    api.monitoring.testDbConnection.mockResolvedValue({ success: true, data: { ok: true, duration_ms: 12, server_version: 'PostgreSQL 16',
      privileges: { checked: true, read_only: true, findings: [] } } })
    render(<ConnectionsManager open onOpenChange={() => {}} teamId={3} teams={[{ id: 3, name: 'A' }]} canEdit />)
    fireEvent.click(await screen.findByRole('button', { name: 'Rapor DB' }))
    const health = document.querySelector('[data-slot="dbq-conn-health"]')
    const priv = health.querySelector('[data-slot="dbq-conn-priv"]')
    expect(priv.getAttribute('data-state')).toBe('writable')
    expect(priv.querySelector('[data-code="CAN_WRITE_TABLES"]').textContent).toContain('public.orders')
    expect(health.querySelector('[data-slot="dbq-conn-ssl-weak"]').getAttribute('data-mode')).toBe('prefer')
    fireEvent.click(screen.getByRole('button', { name: /Bağlantıyı dene/ }))
    await waitFor(() => expect(document.querySelector('[data-slot="dbq-conn-test"][data-ok="true"]')).not.toBeNull())
    const tested = document.querySelector('[data-slot="dbq-conn-test"]').closest('[role="alert"], [data-slot="alert"], div')
    expect(tested.parentElement.querySelector('[data-slot="dbq-conn-priv"][data-state="read_only"]')).not.toBeNull()
  })

  it('test: yetki okunamadı → nötr (asla yeşil); alan yoksa (eski sunucu) hiçbir şey; BLOCK reddi başlığı', async () => {
    api.monitoring.dbConnections.mockResolvedValue({ success: true, data: [{ ...PG, health: { state: 'ok' } }] })
    api.monitoring.testDbConnection.mockResolvedValueOnce({ success: true, data: { ok: true, duration_ms: 12, privileges: { checked: false } } })
    render(<ConnectionsManager open onOpenChange={() => {}} teamId={3} teams={[{ id: 3, name: 'A' }]} canEdit />)
    fireEvent.click(await screen.findByRole('button', { name: 'Rapor DB' }))
    expect(document.querySelector('[data-slot="dbq-conn-health"] [data-slot="dbq-conn-priv"]')).toBeNull()
    fireEvent.click(screen.getByRole('button', { name: /Bağlantıyı dene/ }))
    await waitFor(() => expect(document.querySelector('[data-slot="dbq-conn-priv"]')?.getAttribute('data-state')).toBe('unknown'))
    api.monitoring.testDbConnection.mockResolvedValueOnce({ success: true, data: { ok: false, failure_reason: 'DB_ACCOUNT_WRITABLE', error: 'Hesap yazabiliyor',
      privileges: { checked: true, read_only: false, findings: [{ code: 'SUPERUSER' }] } } })
    fireEvent.click(screen.getByRole('button', { name: /Bağlantıyı dene/ }))
    await waitFor(() => expect(screen.getByText('Hesap yazabildiği için kullanılamıyor')).toBeInTheDocument())
    expect(document.querySelector('[data-code="SUPERUSER"]').textContent).toContain('süper kullanıcı')
  })
})

describe('Şablonlar — yerleşik + yönetici eylemleri (2.C)', () => {
  const T1 = { id: 1, team_id: 3, name: 'Takım şablonu', db_type: 'MSSQL', sql_text: 'SELECT 1', rules: [], rule_logic: 'ANY', can_edit: true }

  it('seçici: takım boşken de yerleşik grup var; bağlantının türünde SQL; "Kullan" onaysız seçer', async () => {
    api.monitoring.dbQueryTemplates.mockResolvedValue({ success: true, data: [] })
    const onPick = vi.fn()
    render(<TemplatePicker teamId={3} dbType="MSSQL" onPick={onPick} />)
    fireEvent.click(screen.getByRole('button', { name: /Şablondan başla/ }))
    expect(await screen.findByText('Bu takımda şablon yok')).toBeInTheDocument()
    const items = document.querySelectorAll('[data-group="builtin"] [data-slot="dbq-tpl-item"]')
    expect(items).toHaveLength(4)
    expect(items[0].getAttribute('data-builtin')).toBe('true')
    fireEvent.click(screen.getByRole('button', { name: 'Kullan: Hata sayısı (son 15 dakika)' }))
    expect(onPick).toHaveBeenCalledTimes(1)
    expect(onPick.mock.calls[0][0]).toMatchObject({ builtin: true, db_type: 'MSSQL' })
    expect(onPick.mock.calls[0][0].sql_text).toContain('WITH (NOLOCK)')
  })

  it('yönetici: "Yeni şablon" → POST (takım + alanlar); yerleşikten "Bu şablonla izleme oluştur" ve "Takıma kopyala"', async () => {
    api.monitoring.dbQueryTemplates.mockResolvedValue({ success: true, data: [T1] })
    api.monitoring.createDbQueryTemplate.mockResolvedValue({ success: true, data: { id: 9 } })
    const onCreate = vi.fn()
    render(<TemplatesManager open onOpenChange={() => {}} teamId={3} teams={[{ id: 3, name: 'A' }]} canEdit onCreateMonitor={onCreate} />)
    await screen.findByText('Takım şablonu')
    // Takım şablonundan izleme
    pressMenuTrigger(within(document.querySelector('[data-slot="dbq-tpl-row"]')).getByRole('button', { name: /İşlemler/ }))
    fireEvent.click(await screen.findByRole('menuitem', { name: /Bu şablonla izleme oluştur/ }))
    expect(onCreate).toHaveBeenLastCalledWith(T1)
    // Yerleşikten izleme (tür seçimi SQL'i değiştirir)
    fireEvent.click(screen.getByRole('button', { name: 'Oracle' }))
    fireEvent.click(screen.getByRole('button', { name: 'Son kaydın yaşı (tazelik) şablonuyla izleme oluştur' }))
    expect(onCreate.mock.calls.at(-1)[0]).toMatchObject({ builtin: true, db_type: 'ORACLE', key: 'freshness' })
    // Takıma kopyala → düzenleme (oluşturma) görünümü, kaydet → POST
    fireEvent.click(screen.getByRole('button', { name: 'Hata sayısı (son 15 dakika) şablonunu takım kütüphanesine kopyala' }))
    expect(await screen.findByRole('heading', { name: 'Yeni şablon' })).toBeInTheDocument()
    expect(screen.getByLabelText(/Şablon adı/)).toHaveValue('Hata sayısı (son 15 dakika)')
    fireEvent.click(document.querySelector('[data-slot="dbq-tpl-edit-save"]'))
    await waitFor(() => expect(api.monitoring.createDbQueryTemplate).toHaveBeenCalled())
    expect(api.monitoring.createDbQueryTemplate.mock.calls[0][0]).toMatchObject({ team_id: 3, name: 'Hata sayısı (son 15 dakika)', db_type: 'ORACLE' })
    expect(api.monitoring.updateDbQueryTemplate).not.toHaveBeenCalled()
  })

  it('yönetici: "Yeni şablon" boş taslakla açılır; düzenleme yetkisi yoksa yeni / kopyala yok', async () => {
    api.monitoring.dbQueryTemplates.mockResolvedValue({ success: true, data: [] })
    const { unmount } = render(<TemplatesManager open onOpenChange={() => {}} teamId={3} teams={[{ id: 3, name: 'A' }]} canEdit />)
    fireEvent.click(await screen.findByRole('button', { name: 'Yeni şablon' }))
    expect(screen.getByLabelText(/Şablon adı/)).toHaveValue('')
    unmount()
    render(<TemplatesManager open onOpenChange={() => {}} teamId={3} teams={[{ id: 3, name: 'A' }]} canEdit={false} />)
    await screen.findAllByText('Yerleşik başlangıç şablonları')
    expect(screen.queryByRole('button', { name: 'Yeni şablon' })).toBeNull()
    expect(document.querySelector('[data-slot="dbq-tpl-copy"]')).toBeNull()
    expect(document.querySelector('[data-slot="dbq-tpl-create-monitor"]')).toBeNull()
  })
})

describe('Tatil takvimi kapsamı (2.A)', () => {
  it('ayar sayfası: bitiyor uyarısı + global yönetici "2027 sabit … ekle"; eski sunucuda (alan yok) uyarı yok', async () => {
    api.admin.getHolidays.mockResolvedValueOnce({ success: true, data: { days: [{ day: '2026-12-31', name: 'X' }], can_edit: true,
      coverage_until: '2026-12-31', ends_soon: true } })
    api.admin.addFixedHolidays.mockResolvedValue({ success: true, data: { days: [{ day: '2026-12-31', name: 'X' }, { day: '2027-01-01', name: 'Yılbaşı' }],
      can_edit: true, coverage_until: '2027-10-29', ends_soon: false, added: 7 } })
    const { unmount } = render(<HolidayCalendarSettings />)
    const notice = await waitFor(() => { const n = document.querySelector('[data-slot="holiday-coverage"]'); if (!n) throw new Error('yok'); return n })
    expect(notice.getAttribute('data-state')).toBe('soon')
    fireEvent.click(screen.getByRole('button', { name: '2027 sabit tarihli resmî tatillerini ekle' }))
    await waitFor(() => expect(api.admin.addFixedHolidays).toHaveBeenCalledWith(2027))
    await waitFor(() => expect(document.querySelector('[data-slot="holiday-coverage"]').getAttribute('data-state')).toBe('ok'))
    // Süzgeç eklenen yıla geçer: yeni gün görünür, eski yılınki görünmez
    expect(await screen.findByText('Yılbaşı')).toBeInTheDocument()
    expect(screen.queryByText('X')).toBeNull()
    unmount()
    api.admin.getHolidays.mockResolvedValueOnce({ success: true, data: { days: [{ day: '2026-12-31', name: 'X' }], can_edit: true } })
    render(<HolidayCalendarSettings />)
    await screen.findByText('X')
    expect(document.querySelector('[data-slot="holiday-coverage"]')).toBeNull()
  })

  it('ayar sayfası: kaydedilmemiş taslak varken düğme kapalı; yönetici olmayana düğme yok', async () => {
    api.admin.getHolidays.mockResolvedValue({ success: true, data: { days: [{ day: '2026-12-31', name: 'Yıl sonu' }], can_edit: true,
      coverage_until: '2026-12-31', ends_soon: true } })
    const { unmount } = render(<HolidayCalendarSettings />)
    await screen.findByText('Yıl sonu')
    fireEvent.click(screen.getByRole('button', { name: 'Sil: Yıl sonu' }))
    fireEvent.click(within(await screen.findByRole('dialog', { name: 'Gün silinsin mi?' })).getByRole('button', { name: 'Günü kaldır' }))
    await waitFor(() => expect(screen.getByRole('button', { name: /sabit tarihli resmî tatillerini ekle/ })).toBeDisabled())
    expect(screen.getByText('Önce kaydedilmemiş değişiklikleri kaydedin ya da vazgeçin.')).toBeInTheDocument()
    unmount()
    api.admin.getHolidays.mockResolvedValue({ success: true, data: { days: [], can_edit: false, coverage_until: null, ends_soon: true } })
    render(<HolidayCalendarSettings />)
    await waitFor(() => expect(document.querySelector('[data-slot="holiday-coverage"]')?.getAttribute('data-state')).toBe('empty'))
    expect(screen.queryByRole('button', { name: /sabit tarihli resmî tatillerini ekle/ })).toBeNull()
  })

  it('zamanlama düzenleyici: "tatillerde çalışma" açıkken bitiyor uyarısı; kapalıyken hiç okunmaz', async () => {
    api.admin.getHolidays.mockResolvedValue({ success: true, data: { days: [], can_edit: true, coverage_until: '2026-11-20', ends_soon: true } })
    const value = { schedule_mode: 'INTERVAL', interval_seconds: 300, cron_expression: '', windows: [], blackouts: [], skip_holidays: false, skip_dates: [] }
    const { rerender } = render(<ScheduleEditor value={value} onChange={() => {}} canEditHolidays />)
    expect(api.admin.getHolidays).not.toHaveBeenCalled()
    rerender(<ScheduleEditor value={{ ...value, skip_holidays: true }} onChange={() => {}} canEditHolidays />)
    const notice = await waitFor(() => { const n = document.querySelector('[data-slot="dbq-skip-holidays"] [data-slot="holiday-coverage"]'); if (!n) throw new Error('yok'); return n })
    expect(notice.getAttribute('data-state')).toBe('soon')
    expect(within(notice).getByRole('button', { name: '2027 sabit tarihli resmî tatillerini ekle' })).toBeInTheDocument()
  })
})

describe('Grafik eşik çizgileri (2.D)', () => {
  let restoreRect
  beforeAll(() => {
    const orig = HTMLElement.prototype.getBoundingClientRect
    HTMLElement.prototype.getBoundingClientRect = function () {
      if (this.classList?.contains('recharts-responsive-container')) return { x: 0, y: 0, top: 0, left: 0, width: 640, height: 300, right: 640, bottom: 300, toJSON() {} }
      return orig.call(this)
    }
    restoreRect = () => { HTMLElement.prototype.getBoundingClientRect = orig }
  })
  afterAll(() => restoreRect?.())

  it('seri yanıtındaki thresholds → seviye rozetleri (uyarı / kritik); uzak eşik "grafiğin üstünde"; alan yoksa rozet yok', async () => {
    const at = (h) => new Date(Date.now() - h * 3_600_000).toISOString().slice(0, 19)
    const series = [{ ts: at(3), avg: 8, min: 8, max: 8, p95: 8, count: 1, down: 0 }, { ts: at(1), avg: 12, min: 12, max: 12, p95: 12, count: 1, down: 0 }]
    api.monitoring.getDbQuerySeries.mockResolvedValueOnce({ success: true, data: { series, bucket: 'hour', capped: false,
      thresholds: [{ rule_id: 'r1', label: 'Bekleyen', level: 'WARNING', value: 10 }, { rule_id: 'r1', label: 'Bekleyen', level: 'CRITICAL', value: 1000 }] } })
    const { unmount } = render(<ResponseTimeChart monitorId={5} kind="dbquery" metric="value" unit="" />)
    await waitFor(() => expect(document.querySelectorAll('[data-slot="chart-level"]')).toHaveLength(2))
    const [w, c] = document.querySelectorAll('[data-slot="chart-level"]')
    expect(w.getAttribute('data-level')).toBe('WARNING')
    expect(w.getAttribute('data-in-view')).toBe('true')
    expect(w.textContent).toContain('Uyarı eşiği')
    expect(w.textContent).toContain('Bekleyen')
    expect(c.getAttribute('data-in-view')).toBe('false')
    expect(c.textContent).toContain('grafiğin üstünde')
    unmount()
    api.monitoring.getDbQuerySeries.mockResolvedValueOnce({ success: true, data: { series, bucket: 'hour', capped: false } })
    render(<ResponseTimeChart monitorId={5} kind="dbquery" metric="value" unit="" />)
    await waitFor(() => expect(document.querySelector('[data-slot="chart-panel"]')).not.toBeNull())
    expect(document.querySelectorAll('[data-slot="chart-level"]')).toHaveLength(0)
  })
})
