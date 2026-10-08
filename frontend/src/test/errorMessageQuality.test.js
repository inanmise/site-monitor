import { describe, it, expect } from 'vitest'
import { TR } from '../i18n/tr.js'
import { EN } from '../i18n/en.js'
import { ERROR_TEXTS } from '../utils/errorMessages.js'

/**
 * HATA İLETİSİ KALİTE KAPISI (2026-10-08, kullanıcı isteği: "hata mesajları çok açıklayıcı olsun").
 *
 * Kullanıcının gördüğü her hata üç soruyu yanıtlamalı: NE oldu (sade dille), büyük olasılıkla NEDEN, ŞİMDİ NE yapmalı.
 * "Kaydedilemedi", "İşlem başarısız", "Error", "Failed" gibi tek başına hiçbir şey söylemeyen metin yeni bir hata
 * anahtarına GİREMEZ.
 *
 * Kural: adı /error|Error|failed|Failed|err\./ ile eşleşen her sözlük anahtarının TR ve EN değeri
 *   (1) ret listesindeki jenerik ifadelerden biri OLAMAZ, (2) en az 25 karakter olmalı.
 *
 * İstisnalar GEREKÇELİ ve CANLI (`ALLOW`): başlık (gövdeyi sunucu/istemci metni taşır), etiket (rozet, sütun, filtre,
 * sayaç), alan altı doğrulama iletisi, ayrıntıyı yer tutucudan alan şablon ve dinamik anahtarla çağrılan etiketler.
 * Liste YALNIZ KÜÇÜLÜR: bir girdi artık kurala uyuyorsa (metni açıklayıcı hâle geldiyse) ya da anahtar silindiyse test
 * kırmızıdır — girdiyi listeden çıkarın. Yeni bir anahtarı listeye eklemek, onun gerçekten ileti olmadığına dair
 * incelemeye açık bir karardır.
 *
 * İstemcinin ağ/HTTP durum metinleri (`utils/errorMessages.js` ERROR_TEXTS) ve sunucu iletileri ayrıca denetlenir
 * (bkz. errorMessages.test.js, GlobalExceptionHandlerTest).
 */

const NAME = /error|Error|failed|Failed|err\./
const MIN_LENGTH = 25

/** Jenerik ifadeler — kırpılmış, küçük harf, sondaki noktalama atılmış TAM eşleşme. */
const DENY = new Set([
  // TR
  'hata', 'bir hata oluştu', 'hata oluştu', 'bir şey ters gitti', 'beklenmeyen hata', 'bilinmeyen hata',
  'işlem başarısız', 'işlem başarısız oldu', 'başarısız', 'kaydedilemedi', 'kaydetme başarısız', 'kayıt başarısız',
  'silinemedi', 'silme başarısız', 'yüklenemedi', 'sunucu hatası', 'işlem tamamlanamadı',
  // EN
  'error', 'errors', 'an error occurred', 'something went wrong', 'unexpected error', 'unknown error', 'failed',
  'operation failed', 'action failed', 'save failed', 'could not save', 'couldn’t save', 'delete failed',
  'could not delete', 'failed to load', 'server error', 'internal server error', 'that did not work',
])

const norm = (s) => String(s).trim().toLowerCase().replace(/̇/g, '').replace(/[\s.!…:;]+$/u, '')
const problems = (value) => {
  const out = []
  if (typeof value !== 'string' || !value.trim()) return ['boş']
  if (DENY.has(norm(value))) out.push('jenerik ifade')
  if (value.length < MIN_LENGTH) out.push(`${value.length} < ${MIN_LENGTH} karakter`)
  return out
}

/** Önek bazında muafiyet — gerekçesiyle. */
const PREFIX_EXEMPT = {
  'general.lbl.': 'ayar ETİKETLERİ (Ayarlar → Genel, anahtar adından türetilir); ileti değil',
  'mcert.': 'manuel sertifika sihirbazının metinleri kendi akışında ele alınır (manualcert/manualCertErrors.js, 2026-10-08); '
    + 'oradaki hata iletileri sihirbaza özgü kodlarla eşlenir',
}

/** Gerekçeli, canlı istisnalar. */
const ALLOW = {
  titles: {
    reason: 'AlertBanner/StatusBlock BAŞLIĞI ya da sonuç kutusu başlığı — gövdeyi açıklayıcı sunucu/istemci metni taşır; '
      + 'başlığın kısa kalması okunurluk içindir',
    keys: [
      'alh.loadError', 'attn.loadError', 'cdp.errorTitle', 'chg.kpiError', 'cnote.loadFailed', 'db.colError',
      'deploy.saveError', 'dns.testError', 'dom.testError', 'dreg.error', 'err.title', 'forecast.loadError',
      'guide.loadError', 'health.loadErrorTitle', 'health.sectionFailed', 'hlth.refreshError', 'hreq.chart.errors',
      'hreq.d.loadError', 'hreq.err.title', 'http.testError', 'httpdx.err.title', 'inc.loadError', 'inc.saveError',
      'incov.actionError', 'incov.loadError', 'inv.loadError', 'inv.testFailed', 'keyword.testError',
      'loginAnomaly.inc.errorTitle', 'loginAnomaly.saveFailedTitle', 'modal.errorMsg', 'mw.saveError',
      'mypush.hist.error', 'ng.histError', 'noc.cfgLoadError', 'noc.con.loadError', 'noc.con.sheet.loadError',
      'noc.groupsLoadError', 'noc.loadError', 'page.testError', 'ping.testError', 'pspd.testError', 'releases.loadError',
      'rtc.error', 'scripted.testError', 'secret.infoError', 'settings.loadError', 'sml.byErrorClass',
      'sql.diag.loadError', 'sql.ex.loadError', 'sql.td.loadError', 'sslv.loadFailed', 'sslv.m.loadFailed',
      'sysmaint.err.generic', 'sysmaint.err.load', 'thr.previewError', 'thr.saveError', 'tpl.loadError',
      'ued.saveFailed', 'waLogs.detail.failed', 'waLogs.detail.loadError',
    ],
  },
  labels: {
    reason: 'ETİKET: durum rozeti, sütun/filtre/KPI adı, sayaç, kopyala düğmesi, açıklama terimi — ileti değil',
    keys: [
      'act.foldedErrors', 'act.tok.configError', 'act.tok.loadFailed', 'alh.bulk.failed', 'alh.mailsFailed',
      'alh.sendFailed', 'alh.sp.reason.failed', 'alh.status.failed', 'alh.whyEmailFailed', 'app.error',
      'app.outageErrorRate', 'app.outageStatErrors', 'audit.noun.clientError', 'audit.tile.failedSignIns',
      'audit.verb.loginFailed', 'card.error', 'certcard.checkFailed', 'certh.copyError', 'certh.dErrorClass',
      'certh.tileFailed', 'chkhist.dnsFailedBadge', 'db.colFailed', 'db.kpiFailed', 'dba.respFailed', 'dba.seriesFailed',
      'dev.failedTitle', 'dev.noFailed', 'dexp.statusFailed', 'diag.status.failed',
      'domcard.unknownError', 'domdet.copyError', 'domdet.errorDetail', 'domdet.whyError', 'err.copied', 'err.copyError',
      'err.copyRef', 'err.reload', 'err.reportBtn', 'health.netErrors', 'health.statusFailed', 'hreq.col.errorRate',
      'hreq.col.errors', 'hreq.kpi.errorRate', 'hreq.kpi.errorsSplit', 'hreq.kpi.errorsSub', 'hreq.series.errors',
      'hreq.series.failed', 'hreq.top.errors', 'http.card.failedAfter', 'http.checkSslErrors', 'http.dashError',
      'http.errorRate', 'http.statusError', 'httpdx.err.retry', 'httpdx.error.copy', 'httpdx.error.title',
      'httpdx.tls.trustError', 'httpdx.verdict.failedStep', 'inc.fErrorBudget', 'inc.fErrorCode', 'inc.sectionErrors',
      'incov.rc.clientError', 'incov.rc.serverError', 'inv.certError', 'inv.certErrorLabel', 'inv.teamErrors',
      'inv.tileErrors', 'issue.autoError', 'issue.autoFailedReqs', 'issue.netError', 'issues.copyError',
      'keyword.card.checkFailed', 'keyword.checkSslErrors', 'keyword.dashError', 'keyword.statusError',
      'kwdx.analysis.conditionFailed', 'lastLogin.failedShort', 'lastLogin.lastFailedAt', 'lastLogin.noFailed',
      'lastLogin.popoverFailed', 'lm.stats.ch.failedWord', 'lm.stats.funnel.deliveryFailed', 'lm.stats.kpi.failed',
      'lm.stats.trend.failed', 'lm.stats.users.col.failed', 'login.helpErrorText',
      'loginIssues.errorText', 'loginIssues.mailFailed', 'loginIssues.mailTypeClientError', 'mo.att.lastFailed',
      'mo.colf.checks.failed', 'mo.csv.failed', 'mo.csv.lastError', 'mo.kpi.failed', 'mo.live.failed',
      'mon.checkAllRowFailed', 'myact.alert.showFailed', 'myact.s.failed', 'myact.tile.failed', 'ndx.steps.copyError',
      'ndx.steps.error', 'page.card.configError', 'page.card.httpError', 'page.statusConfigError', 'perm.failed',
      'perm.failedCount', 'ping.card.reasonError', 'pl.detail.failedTitle', 'pl.kpiFailedUsers', 'port.card.why.error',
      'psdx.kv.failed', 'psdx.res.failed', 'pspd.statusConfigError', 'ret.colFailed', 'ret.filterFailed',
      'rtc.kpi.failed', 'rtc.summaryFailed', 'rtc.summaryFailedOne', 'rtc.tip.failed', 'scripted.card.failedCheck',
      'scripted.card.failedCount', 'scripted.checksFailed', 'sml.colError', 'sml.colErrorClass',
      'sml.kpiFailedRecipients', 'sml.lastFailed', 'sql.err.copyRaw', 'sql.err.detail', 'sql.err.guardTitle',
      'sql.err.hint', 'sql.err.showInEditor', 'sql.err.technical', 'sql.pick.failed', 'sql.res.failed', 'ssl.errorTitle',
      'sslv.err.attempts', 'sslv.err.message', 'sslv.err.stage', 'sslv.f.trustError', 'stat.error', 'tbl.filterError',
      'tbl.statusError', 'uact.colFailedCount', 'uact.colLastFailed', 'uact.detailFailedIp', 'uact.failed',
      'uact.failedLbl', 'uact.failedRatio', 'ubd.result.failed', 'udir.failedSince', 'udir.lastFailed',
      'udir.lastFailedIp', 'ued.fixErrors', 'ued.tabErrors', 'uptime.sslError', 'userpush.error', 'waLogs.chip.failed',
      'waLogs.csv.error', 'waSched.failed', 'wr.mailStatusFailed', 'wr.sendError',
    ],
  },
  fieldErrors: {
    reason: 'ALAN ALTI doğrulama iletisi (useFormErrors) — alanın hemen altında durur; alan zaten "ne" ve "nerede"yi '
      + 'söyler, kısa ve emir kipinde olması bilinçli',
    keys: [
      'deploy.err.at', 'deploy.err.future', 'loginAnomaly.err.integer', 'loginAnomaly.err.max', 'loginAnomaly.err.min',
      'loginAnomaly.err.number', 'loginAnomaly.err.required', 'nocCall.err.outcome', 'nocCall.err.person',
      'nocCall.err.time', 'otp.err.usernameRequired', 'quiet.err.days',
    ],
  },
  detailTemplates: {
    reason: 'ŞABLON: nedeni ve sonraki adımı yer tutucudaki açıklayıcı sunucu/istemci metni taşır ({1} / {0})',
    keys: ['card.checkFailed', 'ec.testWebhookFailed', 'keyword.card.reasonError'],
  },
  dynamic: {
    reason: 'DİNAMİK anahtarla çağrılan ETİKET (t(`önek.${x}`), tablo/kv/sıralama adı) — ileti değil',
    keys: [
      'act.errorCount', 'act.errorDomainsLabel', 'act.errors', 'act.errorsLabel', 'act.filterErrorDomains',
      'act.filterErrors', 'act.latestError', 'act.moreErrors', 'act.noErrorDomains', 'alh.sp.outcome.failed',
      'attn.csv.error', 'attn.r.error', 'attn.why.error', 'audit.preset.failed', 'card.errorLbl', 'cfg.detail.error',
      'chkhist.kv.errorStage', 'chkhist.kv.whoisError', 'db.secFailed', 'dba.err.denied', 'dba.err.missing',
      'dba.err.other', 'dba.err.readonly', 'dba.err.syntax', 'dba.err.timeout', 'health.smtpFilterStatusFailed',
      'health.smtpNoErrors', 'hreq.sort.errorRate', 'http.errorsPerMin', 'http.exp.errors',
      'httpdx.finding.BODY_TIMEOUT.title.error', 'httpdx.finding.RESPONSE_TIMEOUT.title.error', 'inv.hy.error',
      'inv.osslFlagVerifyFailed', 'lastLogin.lastFailedIp', 'lastLogin.lastFailedReason',
      'loginIssues.sourceClientError', 'modal.notifError', 'modal.statusError', 'ndx.kv.error', 'ndx.kv.error_kind',
      'ndx.val.error', 'stv.kpi.error', 'tier.legendError', 'ts.error', 'uact.failed24h', 'uact.sort_failed',
    ],
  },
}

const allowed = new Map()
for (const [group, { keys }] of Object.entries(ALLOW)) for (const k of keys) allowed.set(k, group)
const prefixExempt = (k) => Object.keys(PREFIX_EXEMPT).some((p) => k.startsWith(p))

/** 2026-10-08'de yeniden yazılan yüksek etkili iletiler — "ne yapmalı" adımı taşımaya devam etmeli. */
const NEXT_STEP_TR = /tekrar deneyin|tekrar gönderin|yenileyin|yenileyip|kontrol edin|kontrol edip|yeniden açın|yeniden başlatın|kopyalayın|taşıyın|deneyin|bildirin|isteyin|başvurun/
const NEXT_STEP_EN = /try again|reload|refresh|check |reopen|open it again|start it again|copy|move those|tell the|ask |contact|send it again|press ctrl/i
const REWRITTEN = [
  'settings.saveError', 'inv.saveError', 'inv.deleteError', 'inv.importError', 'inv.exportError', 'inv.transferError',
  'mon.deleteError', 'scripted.saveError', 'scripted.deleteError', 'scripted.triggerError', 'port.saveError',
  'team.deleteError', 'perm.errorGeneric', 'wr.saveFailed', 'wr.actionFailed', 'wr.uploadFailed', 'tpl.saveError',
  'login.serverError', 'alh.resolveError', 'mw.deleteError', 'plat.saveError', 'sys.error', 'audit.exportFailed',
  'sql.res.copyFailed', 'mypush.saveError', 'dev.revokeFailed', 'ldap.lookupError', 'inbox.loadError',
]

describe('hata iletisi kalite kapısı', () => {
  it('kapsam gerçekten çalışıyor (bozuk desen = 0 anahtar regresyonu)', () => {
    expect(Object.keys(TR).filter((k) => NAME.test(k)).length).toBeGreaterThan(500)
  })

  it('her hata anahtarı TR ve EN\'de açıklayıcı (jenerik değil, ≥ 25 karakter) — gerekçeli istisnalar hariç', () => {
    const bad = []
    for (const k of Object.keys(TR)) {
      if (!NAME.test(k) || allowed.has(k) || prefixExempt(k)) continue
      for (const [lang, dict] of [['TR', TR], ['EN', EN]]) {
        const p = problems(dict[k])
        if (p.length) bad.push(`${lang} ${k}: "${dict[k]}" → ${p.join(', ')}`)
      }
    }
    expect(bad, 'Hata iletisi NE oldu · NEDEN · NE yapmalı söylemeli (ya da gerekçesiyle ALLOW\'a girmeli):\n'
      + bad.join('\n')).toEqual([])
  })

  it('istisna listesi CANLI: her girdi var ve hâlâ istisnaya ihtiyaç duyuyor (liste yalnız küçülür)', () => {
    const stale = []
    for (const [k, group] of allowed) {
      if (!(k in TR) || !(k in EN)) { stale.push(`${group}: ${k} sözlükte yok → listeden çıkarın`); continue }
      if (problems(TR[k]).length === 0 && problems(EN[k]).length === 0) {
        stale.push(`${group}: ${k} artık kurala uyuyor → listeden çıkarın`)
      }
    }
    expect(stale, stale.join('\n')).toEqual([])
  })

  it('istisnalar gerekçeli ve tekil (bir anahtar tek grupta)', () => {
    const seen = new Map()
    for (const [group, { reason, keys }] of Object.entries(ALLOW)) {
      expect(reason.length, group).toBeGreaterThan(30)
      for (const k of keys) {
        expect(seen.has(k), `${k}: ${seen.get(k)} ve ${group}`).toBe(false)
        seen.set(k, group)
      }
    }
  })

  it.each(REWRITTEN)('%s: iki dilde de sonraki adımı söyler', (k) => {
    expect(TR[k]).toMatch(NEXT_STEP_TR)
    expect(EN[k]).toMatch(NEXT_STEP_EN)
  })

  it('istemcinin ağ/HTTP durum metinleri de aynı çıtayı geçer ve sonraki adımı söyler', () => {
    for (const [lang, table, re] of [['tr', ERROR_TEXTS.tr, NEXT_STEP_TR], ['en', ERROR_TEXTS.en, NEXT_STEP_EN]]) {
      for (const [k, v] of Object.entries(table)) {
        expect(problems(v), `${lang}.${k}`).toEqual([])
        expect(v, `${lang}.${k}`).toMatch(re)
      }
    }
  })

  it('metinlerde yıldız (*) yok — zorunlu alan işareti ayrı JSX düğümüdür', () => {
    const starred = []
    for (const k of Object.keys(TR)) {
      if (!NAME.test(k)) continue
      if (String(TR[k]).includes('*') || String(EN[k]).includes('*')) starred.push(k)
    }
    expect(starred).toEqual([])
  })
})
