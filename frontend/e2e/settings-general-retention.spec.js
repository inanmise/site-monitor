// Ayarlar → Genel / Veri Saklama / SMTP / Haftalık e-posta / Envanter raporu — prod kapısı e2e (2026-11-15). Telefon
// (390, dokunmatik), tablet (768, dokunmatik) ve dizüstünde (1280) GERÇEK uygulama rotasında (`?tab=settings&sec=…`):
//  - Genel: yalnız DOKUNULAN anahtarlar gönderilir (`PUT {values}`); ortam adı biçim hatası alanın ALTINDA, istek gitmez;
//    sunucunun 400 VALIDATION_FAILED metni gösterilir ve değişiklik kaybolmaz; kapsamlı müdürde GLOBAL_ONLY alanlar kilitli
//    ("only a global administrator can change this"), diğerleri kaydedilir; SMTP bölümü müdüre kapalı;
//  - Veri Saklama: taban altı hızlı seçim çipi kapalı; taban altı elle yazılan değer sunucuda reddedilir (metin görünür,
//    bekleyen değişiklik kalır); geçerli değer yalnız o anahtarla kaydedilir; kuru çalışma sonucu; "Şimdi temizle" onay +
//    409 RETENTION_RUNNING metni; yasal saklama (legal hold) açılınca şerit + temizlik düğmesi kilitli;
//  - SMTP test e-postası: alıcı gövdede, başarı / hata bandı;
//  - Haftalık erişilebilirlik test gönderimi ve envanter raporu "Şimdi gönder": SENT / QUEUED / SKIPPED / NO_RECIPIENT /
//    FAILED sınıfları (`data-outcome`), başarısızda ham durum "teknik ayrıntı"da.
import { test, expect } from '@playwright/test'
import { mockApi } from './support/monitorMocks.js'
import { VIEWPORTS, json, pathIs, checkLayout, expectInViewport, audit, settle } from './support/layoutAudit.js'

const LONG_TEAM = 'Dijital Kanallar Mobil Bankacılık ve Uzun Adlı Uygulama Geliştirme Ekibi (Teknoloji / Kanal Yönetimi)'
const toast = (page, re) => page.locator('[data-sonner-toast]').filter({ hasText: re })

// ── Genel ayarlar ─────────────────────────────────────────────────────────────────────────────────────────────────────
const K = {
  base: 'site.monitor.app.base-url',
  env: 'site.monitor.environment',
  issues: 'site.monitor.login-issues.enabled',
  core: 'site.monitor.executor.core-size',
  max: 'site.monitor.executor.max-size',
  cors: 'site.monitor.cors.allowed-origins',
}
function generalItems(scoped) {
  return [
    { group: 'general', key: K.base, type: 'STRING', value: 'https://sitemonitor.example.com', default: 'http://localhost:8080', read_only: scoped },
    { group: 'general', key: K.env, type: 'STRING', value: 'prod', overridden: true, effective: 'prod', effective_source: 'setting', read_only: scoped },
    { group: 'general', key: K.issues, type: 'BOOL', value: 'true', default: 'true', read_only: false },
    { group: 'executor', key: K.core, type: 'INT', value: '20', default: '20', read_only: false },
    { group: 'executor', key: K.max, type: 'INT', value: '50', default: '50', read_only: false },
    { group: 'security', key: K.cors, type: 'TEXT', value: 'https://portal.example.com,https://raporlama-ve-analitik.ic-servisler.example.com', read_only: scoped },
  ]
}

async function generalRoutes(page, { scoped = false } = {}) {
  await mockApi(page, { role: 'ADMIN', globalAdmin: !scoped })
  const puts = []
  let items = generalItems(scoped)
  await page.route(pathIs('/api/admin/general/settings'), (route) => {
    const req = route.request()
    if (req.method() === 'GET') return json({ success: true, data: items })(route)
    const body = JSON.parse(req.postData() || '{}')
    puts.push(body)
    const v = body.values || {}
    if (v[K.max] != null && Number(v[K.max]) < 20) {
      return json({ success: false, code: 'VALIDATION_FAILED', field: K.max, fields: { [K.max]: 'x' },
        error: 'Maximum threads must be at least the core thread count (20) — enter 20 or more.' }, 400)(route)
    }
    items = items.map((it) => (it.key in v ? { ...it, value: v[it.key] } : it))
    return json({ success: true, message: 'Settings saved — applied live.', data: items })(route)
  })
  return puts
}

const ROOT_GENERAL = '[data-testid="general-settings"]'

// ── Veri saklama ──────────────────────────────────────────────────────────────────────────────────────────────────────
const HOLD_KEY = 'site.monitor.retention.hold-enabled'
const ACT_KEY = 'site.monitor.activity.retention-days'
function policies(actDays) {
  return [
    { id: 'activity-log', table: 'activity_log', data_class: 'PERSONAL', configurable: true, setting_key: ACT_KEY, days: actDays,
      default_days: 365, min_days: 180, rows: 2_500_000, bytes: 900 * 1024 * 1024, purgeable: 12_000, deletes: true, mode: 'AGE',
      rationale: 'Monitoring activity feed.', oldest_at: '2025-11-15T00:00:00', newest_at: '2026-11-15T09:00:00',
      rule: 'activity_time < now - N days', time_column: 'activity_time', zero_means_never: false },
    { id: 'port-checks', table: 'port_checks', data_class: 'OPERATIONAL', configurable: true, setting_key: 'site.monitor.metrics.port.retention-days',
      days: 90, default_days: 90, min_days: 30, rows: 1_200_000, bytes: 300 * 1024 * 1024, purgeable: 0, deletes: true, mode: 'AGE',
      rationale: 'Raw port check series.', oldest_at: '2026-08-17T00:00:00', newest_at: '2026-11-15T09:00:00',
      rule: 'checked_at < now - N days', time_column: 'checked_at', zero_means_never: false },
    { id: 'monitor-check-jobs', table: 'monitor_check_jobs', data_class: 'OPERATIONAL', configurable: false, setting_key: null,
      rows: 40, bytes: 64 * 1024, purgeable: 0, deletes: false, mode: 'EXTERNAL', rationale: 'Self-pruned queue.' },
  ]
}
function overview(state) {
  const p = policies(state.actDays)
  return {
    policies: p, hold_active: state.hold, hold_key: HOLD_KEY, batch_size: 10000, cleanup_cron: '0 30 3 * * *', cleanup_zone: 'Europe/Istanbul',
    totals: { rows: 3_700_040, bytes: 1_200 * 1024 * 1024, policies: 3, tables: 3, purgeable: 12_000 },
    last_run: { total_deleted: 4200, started_at: '2026-11-15T03:30:00', failed_count: 0 }, approvals: {},
  }
}

async function retentionRoutes(page) {
  await mockApi(page, { role: 'ADMIN', globalAdmin: true })
  const state = { actDays: 365, hold: false, puts: [], dry: 0, runs: 0 }
  await page.route(pathIs('/api/admin/retention/overview'), (route) => json({ success: true, data: overview(state) })(route))
  await page.route(pathIs('/api/admin/retention/settings'), (route) => {
    const body = JSON.parse(route.request().postData() || '{}')
    state.puts.push(body)
    const v = body.values || {}
    if (v[ACT_KEY] != null && Number(v[ACT_KEY]) < 180) {
      return json({ success: false, code: 'VALIDATION_FAILED', error: `activity_log requires at least 180 days (entered: ${v[ACT_KEY]})` }, 400)(route)
    }
    if (v[ACT_KEY] != null) state.actDays = Number(v[ACT_KEY])
    if (v[HOLD_KEY] != null) state.hold = v[HOLD_KEY] === 'true'
    return json({ success: true, message: 'Retention settings saved (effective immediately)', changed: 1, data: policies(state.actDays) })(route)
  })
  await page.route(pathIs('/api/admin/retention/dry-run'), (route) => {
    state.dry++
    return json({ success: true, message: 'Dry-run complete', data: { dry_run: true, total_rows: 12_000, failed_count: 0, duration_ms: 40 } })(route)
  })
  await page.route(pathIs('/api/admin/retention/run'), (route) => {
    state.runs++
    return json({ success: false, code: 'RETENTION_RUNNING',
      error: 'Cleanup is already running (the nightly cleanup or another manual run) — this request deleted nothing. Try again in a few minutes; the last run\'s result is in the run history.' }, 409)(route)
  })
  return state
}

const ROOT_RET = '[data-testid="retention-settings"]'
const openClass = async (page, cls) => {
  const trigger = page.locator(`[data-section="${cls}"] > button[data-slot="stats-toggle"]`)
  if ((await trigger.getAttribute('aria-expanded')) !== 'true') await trigger.click()
  await expect(trigger).toHaveAttribute('aria-expanded', 'true')
}

// BİLİNEN ÜRÜN BULGULARI (2026-11-15 prod kapısı raporunda; düzeltilene dek yalnız BU öğeler muaf — liste yalnız küçülür).
// Her muafiyet eşleşmesi `[known-bug …]` satırıyla test çıktısına yazılır:
//  - PolicyRow hızlı seçim çipleri (Button size xs, 36×24 px) ve satır aç/kapa oku (icon-sm, 32 px) dokunmatikte 40 px'e
//    büyümüyor;
//  - Haftalık e-posta arşiv tablosunun "Görüntüle" düğmeleri (Button size sm) dokunmatikte 32 px;
//  - PolicyRow ölçüm kümesi (satır / boyut / aralık / silinebilir) 1280 dizüstünde xl ızgarasına sığmıyor, satırın
//    `overflow-hidden` kabında kesiliyor (içerik sütunu ~750 px < ızgara asgarisi ~780 px);
//  - test alıcı kutuları (SMTP, haftalık, envanter raporu) telefonda `flex-col` içinde `flex-1` → 31 px yükseklik;
//  - NativeSelect dokunmatikte 36 px (shadcn tabanında `pointer-coarse:h-10` yok) — envanter raporu zamanlama seçicileri;
//  - Haftalık e-posta takım seçicisi (SearchableSelect, `wrapLabels` yok) uzun takım adını `truncate` ile kesiyor.
const RET_SMALL_OK = [/policy-row.*"(30|90|180|365|730)"$/, /policy-row>button \d+×\d+ "(activity_log|port_checks|monitor_check_jobs)"$/]
const HISTORY_VIEW_SMALL = /table-cell>button \d+×\d+ "View — /
const RET_CLIP_OK = [/\[ancestor \(div\[policy-row\]/]
const TEST_INPUT_SMALL = (name) => new RegExp('^input\\[input\\].*"' + name + '"$')
const NATIVE_SELECT_SMALL = /^select\[native-select\]/
const PICKER_CLIP = /searchable-select>popover-trigger \[own box\]/

/** checkLayout + bilinen bulguya uyan muaf öğeleri çıktıya yazar (rapor için kanıt). */
async function checkLayoutNoting(page, label, rootSel, opts = {}) {
  await settle(page)
  const m = await page.evaluate(audit, rootSel)
  for (const c of m.clipped) if ((opts.allowClip || []).some((re) => re.test(c))) console.log(`[known-bug clip] ${label}: ${c}`)
  for (const c of m.small) if ((opts.allowSmall || []).some((re) => re.test(c))) console.log(`[known-bug small] ${label}: ${c}`)
  await checkLayout(page, label, rootSel, opts)
}

// ── Haftalık erişilebilirlik / envanter raporu ────────────────────────────────────────────────────────────────────────
const OUTCOMES = [
  { cls: 'sent', status: 'SENT', success: true },
  { cls: 'queued', status: 'QUEUED_RETRY (421 — mail server busy)', success: true },
  { cls: 'skipped', status: 'SKIPPED_DISABLED', success: true },
  { cls: 'noRecipient', status: 'NO_RECIPIENT', success: true },
  { cls: 'failed', status: 'FAILED: 535 5.7.8 Authentication credentials invalid', success: false },
]
const OUTCOME_CODE = { sent: 'SENT', queued: 'QUEUED', skipped: 'SKIPPED', noRecipient: 'NO_RECIPIENT', failed: 'FAILED' }

for (const vp of VIEWPORTS) {
  test.describe(`Settings — ${vp.name} ${vp.width}`, () => {
    test.use({ viewport: { width: vp.width, height: vp.height }, hasTouch: vp.touch, isMobile: vp.touch })

    test('General: only touched keys are sent; env name error under the field; server 400 text kept; layout', async ({ page }) => {
      test.setTimeout(120_000)
      const puts = await generalRoutes(page)
      await page.goto('/?tab=settings&sec=general')
      const root = page.locator(ROOT_GENERAL)
      await expect(root.locator(`[data-setting-key="${K.core}"] input`)).toHaveValue('20', { timeout: 20_000 })
      expect(await page.evaluate(() => matchMedia('(pointer: coarse)').matches)).toBe(vp.touch)
      const bar = root.locator('[data-slot="settings-save-bar"]')
      await expect(bar).not.toHaveAttribute('data-dirty', 'true')
      await checkLayout(page, `general clean@${vp.name}`, ROOT_GENERAL)

      // 1) İki alana dokun → PUT yalnız ikisini taşır
      await root.locator(`[data-setting-key="${K.core}"] input`).fill('24')
      await root.locator(`[data-setting-key="${K.issues}"] [role="switch"]`).click()
      await expect(bar).toHaveAttribute('data-dirty', 'true')
      await bar.getByRole('button', { name: /^\s*(Save|Kaydet)$/ }).click()
      await expect.poll(() => puts.length).toBe(1)
      expect(puts[0]).toEqual({ values: { [K.core]: '24', [K.issues]: 'false' } })
      await expect(bar).not.toHaveAttribute('data-dirty', 'true')
      await expect(toast(page, /Settings saved — applied live/)).toBeVisible()

      // 2) Sunucu 400 VALIDATION_FAILED → metin görünür, değişiklik korunur (yeniden denenebilir)
      await root.locator(`[data-setting-key="${K.max}"] input`).fill('10')
      await bar.getByRole('button', { name: /^\s*(Save|Kaydet)$/ }).click()
      await expect.poll(() => puts.length).toBe(2)
      expect(puts[1]).toEqual({ values: { [K.max]: '10' } })
      await expect(toast(page, /Maximum threads must be at least the core thread count/)).toBeVisible()
      await expect(bar).toHaveAttribute('data-dirty', 'true')
      await expect(root.locator(`[data-setting-key="${K.max}"] input`)).toHaveValue('10')
      await bar.getByRole('button', { name: /Discard|Vazgeç/ }).click()
      await expect(root.locator(`[data-setting-key="${K.max}"] input`)).toHaveValue('50')
      await expect(bar).not.toHaveAttribute('data-dirty', 'true')

      // 3) Ortam adı biçim hatası: hata alanın ALTINDA, kayıt isteği gitmez
      const env = root.locator(`[data-setting-key="${K.env}"]`)
      await env.locator('input').fill('Prod Env')
      await expect(env.locator('input')).toHaveValue('prod env')
      await expect(env.locator('input')).toHaveAttribute('aria-invalid', 'true')
      await expect(env).toContainText('Use lower-case letters, digits and "-" only')
      await bar.getByRole('button', { name: /^\s*(Save|Kaydet)$/ }).click()
      await expect(toast(page, /Use lower-case letters, digits and "-" only/)).toBeVisible()
      expect(puts).toHaveLength(2)
      await checkLayout(page, `general env error@${vp.name}`, ROOT_GENERAL)
      await page.screenshot({ path: `test-results/settings-general-${vp.name}.png`, fullPage: false })
    })

    test('General as scoped admin: GLOBAL_ONLY fields disabled, others save; SMTP section locked', async ({ page }) => {
      test.setTimeout(90_000)
      const puts = await generalRoutes(page, { scoped: true })
      await page.goto('/?tab=settings&sec=general')
      const root = page.locator(ROOT_GENERAL)
      await expect(root.locator(`[data-setting-key="${K.core}"] input`)).toHaveValue('20', { timeout: 20_000 })
      for (const key of [K.base, K.cors]) {
        const box = root.locator(`[data-setting-key="${key}"]`)
        await expect(box.locator('input, textarea').first()).toBeDisabled()
        await expect(box).toContainText('only a global administrator can change this')
      }
      await expect(root.locator(`[data-setting-key="${K.env}"] input`)).toBeDisabled()
      await expect(root.locator(`[data-setting-key="${K.env}"]`).getByRole('button', { name: /Clear/ })).toHaveCount(0)
      await expect(root.locator(`[data-setting-key="${K.core}"] input`)).toBeEnabled()
      await expect(root.locator(`[data-setting-key="${K.core}"]`)).not.toContainText('only a global administrator')
      await root.locator(`[data-setting-key="${K.core}"] input`).fill('22')
      await root.locator('[data-slot="settings-save-bar"]').getByRole('button', { name: /^\s*(Save|Kaydet)$/ }).click()
      await expect.poll(() => puts.length).toBe(1)
      expect(puts[0]).toEqual({ values: { [K.core]: '22' } })
      await checkLayout(page, `general scoped@${vp.name}`, ROOT_GENERAL)

      // SMTP (sır taşıyan) bölümü kapsamlı müdüre kapalı — ekran isteği bile atmaz
      const smtpCalls = []
      page.on('request', (r) => { if (new URL(r.url()).pathname.startsWith('/api/admin/smtp')) smtpCalls.push(r.url()) })
      await page.goto('/?tab=settings&sec=smtp')
      await expect(page.locator('[data-testid="settings-global-only"]')).toContainText(/Global administrators only/, { timeout: 20_000 })
      await expect(page.locator('[data-testid="smtp-settings"]')).toHaveCount(0)
      expect(smtpCalls).toEqual([])
    })

    test('Retention: floor, below-floor rejected, valid save, dry run, run-now 409, legal hold; layout', async ({ page }) => {
      test.setTimeout(150_000)
      const state = await retentionRoutes(page)
      await page.goto('/?tab=settings&sec=retention')
      const root = page.locator(ROOT_RET)
      await expect(root.locator('[data-section="PERSONAL"]')).toBeVisible({ timeout: 20_000 })
      await openClass(page, 'PERSONAL')
      const row = root.locator('[data-slot="policy-row"][data-policy-id="activity-log"]')
      const chips = row.getByRole('group', { name: /activity_log/ })
      // Taban (180) altı hızlı seçim çipleri kapalı
      await expect(chips.getByRole('button', { name: '30', exact: true })).toBeDisabled()
      await expect(chips.getByRole('button', { name: '90', exact: true })).toBeDisabled()
      await expect(chips.getByRole('button', { name: '180', exact: true })).toBeEnabled()
      await expect(chips.getByRole('button', { name: '365', exact: true })).toHaveAttribute('aria-pressed', 'true')

      // Taban altı değer elle → gözden geçir (kısaltma = tehlike tonu) → sunucu reddeder, metin görünür, bekleyen kalır
      const days = row.getByRole('spinbutton', { name: /activity_log/ })
      await days.fill('100')
      await expect(row).toHaveAttribute('data-changed', 'true')
      const sticky = root.locator('[data-slot="retention-sticky-bar"]')
      await expect(sticky).toContainText(/1 pending changes|1 bekleyen/)
      await checkLayoutNoting(page, `retention dirty@${vp.name}`, ROOT_RET, { allowSmall: RET_SMALL_OK, allowClip: RET_CLIP_OK })
      await sticky.getByRole('button', { name: /Review and save|Gözden geçir/ }).click()
      const review = page.locator('[data-slot="retention-review"]')
      await expect(review).toHaveAttribute('data-tone', 'danger')
      await expect(review.locator('tr[data-direction="down"]')).toContainText('activity_log')
      const dialog = page.getByRole('dialog').filter({ has: review })
      await expectInViewport(dialog, vp, `${vp.name}: retention review`)
      await dialog.getByRole('button', { name: /Yes, save and shorten|Evet, kaydet ve kısalt/ }).click()
      await expect.poll(() => state.puts.length).toBe(1)
      expect(state.puts[0]).toEqual({ values: { [ACT_KEY]: '100' } })
      await expect(toast(page, /activity_log requires at least 180 days \(entered: 100\)/)).toBeVisible()
      await expect(sticky).toBeVisible()
      await expect(days).toHaveValue('100')

      // Geçerli değer (çip 180) → yalnız o anahtar; kayıt sonrası yeniden yüklenen değer
      await chips.getByRole('button', { name: '180', exact: true }).click()
      await sticky.getByRole('button', { name: /Review and save|Gözden geçir/ }).click()
      await page.getByRole('dialog').filter({ has: review }).getByRole('button', { name: /Yes, save and shorten|Evet, kaydet ve kısalt/ }).click()
      await expect.poll(() => state.puts.length).toBe(2)
      expect(state.puts[1]).toEqual({ values: { [ACT_KEY]: '180' } })
      await expect(toast(page, /Retention settings saved/)).toBeVisible()
      await expect(sticky).toHaveCount(0)
      await openClass(page, 'PERSONAL')
      await expect(days).toHaveValue('180')

      // Kuru çalışma: yalnız sayar
      await root.getByRole('button', { name: /Dry-run \(count only\)/ }).click()
      await expect.poll(() => state.dry).toBe(1)
      await expect(root).toContainText('Dry-run complete: 12K rows are eligible. Nothing was deleted.')

      // Şimdi temizle: onay → 409 RETENTION_RUNNING metni (hiçbir şey silinmedi)
      await root.getByRole('button', { name: /^(Purge now|Şimdi temizle)$/ }).click()
      const confirm = page.getByRole('dialog').filter({ hasText: /Run cleanup now\?|Temizlik şimdi/ })
      await expect(confirm).toContainText(/PERMANENTLY|KALICI/)
      await confirm.getByRole('button', { name: /Yes, purge|Evet, temizle/ }).click()
      await expect.poll(() => state.runs).toBe(1)
      await expect(toast(page, /Cleanup is already running .* this request deleted nothing/)).toBeVisible()

      // Yasal saklama: aç → yalnız hold anahtarı gönderilir, şerit görünür, temizlik düğmesi kilitli
      await root.getByRole('switch', { name: /Stop all deletion|Tüm silmeyi durdur/ }).click()
      await expect.poll(() => state.puts.length).toBe(3)
      expect(state.puts[2]).toEqual({ values: { [HOLD_KEY]: 'true' } })
      await expect(root.locator('[data-testid="ret-hold-banner"]')).toBeVisible()
      await expect(root.getByRole('button', { name: /^(Purge now|Şimdi temizle)$/ })).toBeDisabled()
      await checkLayoutNoting(page, `retention hold@${vp.name}`, ROOT_RET, { allowSmall: RET_SMALL_OK, allowClip: RET_CLIP_OK })
      await page.screenshot({ path: `test-results/settings-retention-${vp.name}.png`, fullPage: false })
    })

    test('SMTP test mail: recipient in the body, success and failure bands', async ({ page }) => {
      test.setTimeout(90_000)
      await mockApi(page, { role: 'ADMIN', globalAdmin: true })
      const sends = []
      await page.route(pathIs('/api/admin/smtp/settings'), json({ success: true, secret_key_set: true, data: {
        enabled: true, host: 'smtp-relay.kurumsal-eposta-altyapisi.example.com', port: 587, username: 'alerts', password_set: true,
        from_address: 'sitemonitor-alerts@example.com', from_name: 'SiteMonitor', auth_enabled: true, start_tls_enable: true,
        start_tls_required: false, ssl_trust: '', connection_timeout_ms: 10000, read_timeout_ms: 10000, write_timeout_ms: 10000,
        retry_delay_ms: 30000, inter_contact_delay_ms: 0, inter_domain_delay_ms: 0, updated_at: '2026-11-14 10:00', updated_by: 'demo' } }))
      await page.route(pathIs('/api/admin/smtp/test-email'), (route) => {
        const body = JSON.parse(route.request().postData() || '{}')
        sends.push(body)
        return sends.length === 1
          ? json({ success: true, message: `Test email sent to ${body.recipient}` })(route)
          : json({ success: false, error: 'The mail server refused the connection (smtp-relay.kurumsal-eposta-altyapisi.example.com:587) — check the host and the firewall.' }, 502)(route)
      })
      await page.goto('/?tab=settings&sec=smtp')
      const root = page.locator('[data-testid="smtp-settings"]')
      const input = root.getByRole('textbox', { name: /Recipient email address/ })
      await expect(input).toBeVisible({ timeout: 20_000 })
      const send = root.getByRole('button', { name: /^\s*Send test email$/ })
      await expect(send).toBeDisabled()
      await input.fill('  ops-nobet@example.com ')
      await send.click()
      await expect.poll(() => sends.length).toBe(1)
      expect(sends[0]).toEqual({ recipient: 'ops-nobet@example.com' })
      await expect(root).toContainText('Test email sent to ops-nobet@example.com')
      await send.click()
      await expect.poll(() => sends.length).toBe(2)
      await expect(root).toContainText('The mail server refused the connection')
      await checkLayoutNoting(page, `smtp@${vp.name}`, '[data-testid="smtp-settings"]', { allowSmall: [TEST_INPUT_SMALL('Recipient email address')] })
    })

    test('Weekly availability test send: SENT / QUEUED / SKIPPED / NO_RECIPIENT / FAILED classes', async ({ page }) => {
      test.setTimeout(120_000)
      await mockApi(page, { role: 'ADMIN', globalAdmin: true })
      const sends = []
      await page.route(pathIs('/api/admin/system/weekly-availability/status'), json({ success: true, data: {
        enabled: true, week_label: '09–15 Nov 2026',
        teams: [
          { id: 1, name: LONG_TEAM, domain_count: 34, to: ['dijital-kanallar-mobil-bankacilik@example.com'], cc: ['yonetici@example.com'], last_status: 'SENT', last_sent_at: '2026-11-09T10:30:00' },
          { id: 2, name: 'Ödeme Sistemleri', domain_count: 0, to: [], cc: [], last_status: null, last_sent_at: null },
        ],
        weeks: [{ offset: 0, label: '09–15 Nov 2026', current: true }, { offset: -1, label: '02–08 Nov 2026', emailed: true }],
      } }))
      await page.route(pathIs('/api/admin/system/weekly-availability/send-test'), (route) => {
        const body = JSON.parse(route.request().postData() || '{}')
        sends.push(body)
        const o = OUTCOMES[(sends.length - 1) % OUTCOMES.length]
        return json(o.success ? { success: true, data: { status: o.status } }
          : { success: false, error: 'Test email could not be sent.', data: { status: o.status } }, o.success ? 200 : 502)(route)
      })
      await page.goto('/?tab=settings&sec=weeklyavail')
      const root = page.locator('[data-testid="weeklyavail-settings"]')
      await expect(root.locator('[data-testid="wa-status"]')).toContainText('Ödeme Sistemleri', { timeout: 20_000 })
      await root.getByRole('textbox', { name: /Send test email/ }).fill('ops@example.com')
      const send = root.getByRole('button', { name: /^\s*Send$/ })
      for (const o of OUTCOMES) {
        await send.click()
        const result = root.locator('[data-slot="wa-send-result"]')
        await expect(result).toHaveAttribute('data-outcome', o.cls)
        if (o.cls === 'failed') await expect(result.locator('[data-slot="wa-send-raw"]')).toHaveText(o.status)
        else await expect(result.locator('[data-slot="wa-send-raw"]')).toHaveCount(0)
        if (o.cls === 'sent') await expect(result).toContainText('Test email sent')
        else await expect(result).not.toContainText('Test email sent')
      }
      expect(sends).toHaveLength(5)
      expect(sends[0]).toEqual({ teamId: '1', email: 'ops@example.com' })
      await checkLayoutNoting(page, `weeklyavail@${vp.name}`, '[data-testid="weeklyavail-settings"]',
        { allowClip: [PICKER_CLIP], allowSmall: [TEST_INPUT_SMALL('Send test email'), HISTORY_VIEW_SMALL] })
    })

    test('Certificate inventory report "Send now": outcome classes from data.outcome', async ({ page }) => {
      test.setTimeout(120_000)
      await mockApi(page, { role: 'ADMIN', globalAdmin: true })
      let runs = 0
      await page.route(pathIs('/api/admin/system/cert-inventory-report/status'), json({ success: true, data: {
        enabled: true, recipients: 'pki@example.com, dijital-kanallar-mobil-bankacilik@example.com', extra_recipients: 'pki@example.com', cc: '',
        cron: '0 0 10 * * FRIL', next_run: '2026-11-27 10:00', next_runs: ['2026-11-27 10:00', '2026-12-25 10:00'],
        owner_recipients: [{ team: LONG_TEAM, email: 'dijital-kanallar-mobil-bankacilik@example.com' }], teams_without_email: [],
      } }))
      await page.route(pathIs('/api/admin/system/cert-inventory-report/history'), json({ success: true, data: [
        { id: 3, year: 2026, month: 10, month_label: 'October 2026', status: 'SENT', rows: 118, findings: 6, recipients: 'pki@example.com', sent_at: '2026-10-30 10:00' },
        { id: 2, year: 2026, month: 9, month_label: 'September 2026', status: 'FAILED: 535 5.7.8', rows: 112, findings: 9, recipients: 'pki@example.com', sent_at: '2026-09-25 10:00' },
      ] }))
      await page.route(pathIs('/api/admin/system/cert-inventory-report/run'), (route) => {
        const o = OUTCOMES[runs % OUTCOMES.length]
        runs++
        return json({ success: true, data: { rows: 120, findings: 7, status: o.status, outcome: OUTCOME_CODE[o.cls] } })(route)
      })
      await page.goto('/?tab=settings&sec=certinvreport')
      const root = page.locator('[data-testid="certinv-report-settings"]')
      const run = root.getByRole('button', { name: /^\s*Send now$/ })
      await expect(run).toBeEnabled({ timeout: 20_000 })
      for (const o of OUTCOMES) {
        await run.click()
        const result = root.locator('[data-slot="cir-result"]')
        await expect(result).toHaveAttribute('data-outcome', o.cls)
        await expect(result).toContainText('120 records · 7 findings')
        if (o.cls === 'failed') await expect(result.locator('[data-slot="cir-result-raw"]')).toHaveText(o.status)
        else await expect(result.locator('[data-slot="cir-result-raw"]')).toHaveCount(0)
        await expect(run).toBeEnabled()
      }
      expect(runs).toBe(5)
      await expect(root.locator('[data-testid="cir-history"] [data-status="failed"]')).toBeVisible()
      await checkLayoutNoting(page, `certinvreport@${vp.name}`, '[data-testid="certinv-report-settings"]',
        { allowSmall: [NATIVE_SELECT_SMALL, TEST_INPUT_SMALL('Send test')] })
    })
  })
}
