/**
 * İstatistikler dışa aktarımı — saf satır kurucuları (2026-11-13, #15). Excel (özet · matris · tablo · karne sayfaları) ve
 * tek sayfalık PDF özeti AYNI satırlardan beslenir. Bilinmeyen değer boş hücre / "—" (asla 0); sayılar sayı hücresi.
 */
import { LEVELS, LEVEL_TEXT, issuerOf, levelOfCert } from './statsModel.js'

const blank = (v) => (v === null || v === undefined ? '' : v)

/** Özet sayfası: [alan, değer] satırları. */
export function summaryRows({ t, kpi, exec, security, renewal, filters }) {
  const c = exec?.certs || {}, a = exec?.alerts || {}, s = exec?.sla || {}
  const rows = [[t('stx.xl.field'), t('stx.xl.value')]]
  const push = (k, v) => rows.push([k, blank(v)])
  push(t('stv.kpi.total'), kpi?.total)
  push(t('stv.kpi.valid'), kpi?.valid)
  push(t('stv.kpi.d30'), kpi?.d30)
  push(t('stv.kpi.d7'), kpi?.d7)
  push(t('stv.kpi.expired'), kpi?.expired)
  push(t('stv.kpi.error'), kpi?.error)
  push(t('exs.health'), c.error_msg ? null : c.health_pct)
  push(t('stx.xl.evaluated'), c.error_msg ? null : c.evaluated)
  if (a.visible !== false) {
    push(t('exs.alerts'), a.error_msg ? null : a.open)
    push(t('stx.xl.alertsLast30'), a.error_msg ? null : a.opened_last30)
  }
  push(t('stx.xl.slaBreaches'), s.error_msg ? null : s.breaches)
  push(t('stx.xl.slaMonitors'), s.error_msg ? null : s.monitors)
  // Saklama süresiyle kısalan pencere Excel / PDF'te de açıkça yazılır (asla sessizce "12 ay")
  for (const w of renewal?.windows || []) push(w.clamped ? t('stx.renWindowClamped', w.months, w.effective_days) : t('stx.renWindow', w.months), w.on_time_pct)
  if (security) {
    push(t('stx.xl.flagged'), security.flagged)
    push(t('stx.secUngraded'), security.ungraded)
  }
  if (filters) push(t('stx.xl.filters'), filters)
  return rows
}

/** Matris sayfası: takım × seviye (tüm katmanlar) + toplam. */
export function matrixSheetRows(mxRows, t, nameOf) {
  const head = [t('stv.mxColTeam'), ...LEVELS.map((l) => t(`ts.${l}`)), t('ts.total')]
  return [head, ...(mxRows || []).map((r) => [nameOf(r), ...LEVELS.map((l) => r.counts[l] || 0), r.total])]
}

/** Tablo sayfası: süzülmüş sertifikalar (CSV ile aynı sütunlar; kalan gün sayı hücresi). */
export function tableSheetRows(rows, t, dtm) {
  const head = [
    t('tbl.colDomain'), t('tbl.colStatus'), t('tbl.colDays'), t('tbl.colExpiry'), t('tbl.colIssuer'),
    t('sv.colSyTeam'), t('sv.colUgTeam'), t('sv.colTier'), t('stx.xl.tlsGrade'), t('tbl.colChecked'),
  ]
  const body = (rows || []).map((c) => {
    const tm = dtm?.get(c.domain)
    return [
      c.domain, t(LEVEL_TEXT[levelOfCert(c)]), blank(c.days_remaining), c.not_after || '', issuerOf(c),
      (tm?.sy?.length ? tm.sy : [c.team_name || '']).join(', '), (tm?.ug ?? []).join(', '),
      c.tier != null ? `T${c.tier}` : '', c.tls_grade || '', c.checked_at || '',
    ]
  })
  return [head, ...body]
}

/** Karne sayfası. `known` = { alerts, sla, dq } — okunmayan kaynak boş hücre. */
export function scorecardSheetRows(rows, t, nameOf, known = {}) {
  const head = [t('stx.scTeam'), t('stx.scHealth'), t('stx.xl.evaluated'), t('stx.scUnder30'), t('stx.scProblems'),
    t('stx.scAlerts'), t('stx.xl.alertsLast30'), t('stx.xl.slaBreaches'), t('stx.xl.slaMonitors'), t('stx.scDq')]
  const body = (rows || []).map((r) => [
    nameOf(r),
    blank(r.certs?.health), blank(r.certs?.evaluated), blank(r.certs?.under30),
    r.certs ? r.certs.expired + r.certs.error : '',
    known.alerts ? blank(r.alerts?.open) : '', known.alerts ? blank(r.alerts?.last30) : '',
    known.sla ? blank(r.sla?.breaches) : '', known.sla ? blank(r.sla?.monitors) : '',
    known.dq ? blank(r.dq?.score) : '',
  ])
  return [head, ...body]
}

/** Dosya adı: `<önek>-YYYYMMDD-HHmm.<uzantı>` (yerel saat). */
export function exportFileName(prefix, ext, now = new Date()) {
  const p = (n) => String(n).padStart(2, '0')
  const stamp = `${now.getFullYear()}${p(now.getMonth() + 1)}${p(now.getDate())}-${p(now.getHours())}${p(now.getMinutes())}`
  return `${prefix}-${stamp}.${ext}`
}
