#!/usr/bin/env node
// Trivy imaj taraması kapısı (2026-11-17, inceleme bulgusu) — release.yml (yayın kapısı) ve docker-build.yml (dal kapısı)
// çağırır; CI'da `node --test scripts/__tests__/ci-gates.test.mjs` sınar.
//
// Neden: tarama `exit-code: 1` ile koşuyordu; Trivy'nin KENDİ hatası (veritabanı indirilemedi, zaman aşımı, imaj
// bulunamadı) da çıkış 1 verir — kapı ikisini aynı "HIGH/CRITICAL bulgu var" diye anlatıyordu. Şimdi tarama `exit-code: 0`
// + JSON rapor ile koşar; adım BAŞARISIZSA (continue-on-error) bu Trivy'nin kendi hatasıdır, adım başarılıysa rapordaki
// bulgular sayılır. İkisi de kapıyı DURDURUR ama ayrı ek açıklama + özetle.
//
// Sonuçlar: clean (geçer) | findings (bulgu — düzeltmesi olan HIGH/CRITICAL, .trivyignore.yaml istisnaları düşülmüş) |
// error (tarama çalışmadı ya da raporu okunamadı — bulgu YOK sanılmaz).
//
// Kullanım: node scripts/trivy-gate.mjs --report <trivy JSON> --outcome <adım sonucu: success|failure|…> [--context release|branch]
//   Çıkış 0 yalnız clean. $GITHUB_STEP_SUMMARY tanımlıysa özet ona eklenir; bulgu tablosu günlüğe de yazılır.
import { appendFileSync, readFileSync } from 'node:fs'
import { pathToFileURL } from 'node:url'

/** Özette gösterilen en çok bulgu satırı (günlükte hepsi yazılır, en çok MAX_LOG_ROWS). */
export const MAX_SUMMARY_ROWS = 50
export const MAX_LOG_ROWS = 500

/**
 * @param {{ outcome: string, reportText: string|null }} input  Trivy adımının sonucu ve JSON rapor metni
 * @returns {{ result: 'clean'|'findings'|'error', reason?: string, findings: object[] }}
 */
export function classify({ outcome, reportText }) {
  if (outcome !== 'success') {
    return { result: 'error', reason: `Trivy adımı başarısız bitti (sonuç: ${outcome || 'yok'})`, findings: [] }
  }
  if (typeof reportText !== 'string' || reportText.trim() === '') {
    return { result: 'error', reason: 'Trivy raporu yok ya da boş', findings: [] }
  }
  let report
  try {
    report = JSON.parse(reportText)
  } catch {
    return { result: 'error', reason: 'Trivy raporu okunamadı (geçersiz JSON)', findings: [] }
  }
  if (!report || typeof report !== 'object' || Array.isArray(report) || typeof report.SchemaVersion !== 'number') {
    return { result: 'error', reason: 'Trivy raporu beklenen biçimde değil (SchemaVersion yok)', findings: [] }
  }
  const results = report.Results
  if (results !== undefined && results !== null && !Array.isArray(results)) {
    return { result: 'error', reason: 'Trivy raporunda Results bir liste değil', findings: [] }
  }
  const findings = []
  for (const r of results || []) {
    if (!r || typeof r !== 'object') continue
    const target = String(r.Target ?? '')
    for (const v of Array.isArray(r.Vulnerabilities) ? r.Vulnerabilities : []) {
      findings.push({
        target, type: 'vuln', id: String(v?.VulnerabilityID ?? ''), pkg: String(v?.PkgName ?? ''),
        severity: String(v?.Severity ?? ''), installed: String(v?.InstalledVersion ?? ''), fixed: String(v?.FixedVersion ?? ''),
      })
    }
    for (const s of Array.isArray(r.Secrets) ? r.Secrets : []) {
      findings.push({
        target, type: 'secret', id: String(s?.RuleID ?? ''), pkg: String(s?.Title ?? ''),
        severity: String(s?.Severity ?? ''), installed: '', fixed: '',
      })
    }
    for (const m of Array.isArray(r.Misconfigurations) ? r.Misconfigurations : []) {
      findings.push({
        target, type: 'misconfig', id: String(m?.ID ?? m?.AVDID ?? ''), pkg: String(m?.Title ?? ''),
        severity: String(m?.Severity ?? ''), installed: '', fixed: '',
      })
    }
  }
  return findings.length > 0 ? { result: 'findings', findings } : { result: 'clean', findings }
}

const cell = (s) => String(s).replace(/[\r\n]+/g, ' ').replace(/\|/g, '\\|').slice(0, 200)

/** Bulgu tablosu (Markdown). */
export function findingsTable(findings, max) {
  const rows = findings.slice(0, max)
  const lines = ['| Hedef | Tür | Kimlik | Paket | Önem | Kurulu | Düzeltilmiş |', '|---|---|---|---|---|---|---|']
  for (const f of rows) {
    lines.push(`| ${cell(f.target)} | ${cell(f.type)} | ${cell(f.id)} | ${cell(f.pkg)} | ${cell(f.severity)} | ${cell(f.installed)} | ${cell(f.fixed)} |`)
  }
  if (findings.length > rows.length) lines.push(`\n… ve ${findings.length - rows.length} bulgu daha (tam liste adım günlüğünde).`)
  return lines.join('\n')
}

/** Özet (Markdown) + ek açıklama. context: release (hiçbir şey itilmez) | branch (imaj zaten itildi). */
export function report(c, context) {
  const release = context === 'release'
  const stopped = release
    ? 'Yayın DURDU: hiçbir şey itilmedi (imaj etiketi / latest, Helm grafiği, sürüm commit\'i, git etiketi).'
    : 'İmaj itildi; iş kırmızı. Main\'e alınırsa Release bu imajı İTMEZ.'
  if (c.result === 'clean') {
    return {
      annotation: null,
      summary: '### ✅ Trivy: düzeltmesi olan HIGH/CRITICAL bulgu yok\n',
    }
  }
  if (c.result === 'findings') {
    return {
      annotation: `::error::Trivy: ${c.findings.length} düzeltmesi olan HIGH/CRITICAL bulgu (istisnalar .trivyignore.yaml) — ${stopped}`,
      summary: [
        `### ⛔ Trivy: ${c.findings.length} düzeltmesi olan HIGH/CRITICAL bulgu`,
        stopped,
        'Ne yapmalı: docs/RUNBOOK.md "Yayın güvenlik kapısı (Trivy)".',
        '',
        findingsTable(c.findings, MAX_SUMMARY_ROWS),
        '',
      ].join('\n'),
    }
  }
  return {
    annotation: `::error::Trivy TARAMASI ÇALIŞAMADI (bulgu değil, tarama hatası): ${c.reason} — ${stopped} `
      + 'Trivy adımının günlüğüne bakın ve yeniden koşun (geçici veritabanı / ağ hatası olabilir).',
    summary: [
      '### ⚠ Trivy taraması çalışamadı — bulgu DEĞİL, tarama hatası',
      `Neden: ${c.reason}.`,
      stopped,
      'Trivy adımının günlüğüne bakın (veritabanı indirme, zaman aşımı, imaj bulunamadı) ve iş akışını yeniden koşun.',
      '',
    ].join('\n'),
  }
}

function arg(argv, name) {
  const i = argv.indexOf(name)
  return i >= 0 && i + 1 < argv.length ? argv[i + 1] : undefined
}

export function main(argv = process.argv.slice(2), { out = process.stdout, env = process.env } = {}) {
  const path = arg(argv, '--report')
  const outcome = arg(argv, '--outcome') ?? ''
  const context = arg(argv, '--context') ?? 'branch'
  let text = null
  try { text = path ? readFileSync(path, 'utf8') : null } catch { text = null }
  const c = classify({ outcome, reportText: text })
  const r = report(c, context)
  out.write(`trivy-gate: ${c.result}${c.reason ? ` (${c.reason})` : ''}\n`)
  if (c.findings.length > 0) out.write(`${findingsTable(c.findings, MAX_LOG_ROWS)}\n`)
  if (r.annotation) out.write(`${r.annotation}\n`)
  if (env.GITHUB_STEP_SUMMARY) {
    try { appendFileSync(env.GITHUB_STEP_SUMMARY, `\n${r.summary}\n`) } catch { /* özet yazılamadı: kapı kararı değişmez */ }
  }
  if (env.GITHUB_OUTPUT) {
    try { appendFileSync(env.GITHUB_OUTPUT, `result=${c.result}\n`) } catch { /* çıktı yazılamadı: kapı kararı değişmez */ }
  }
  return c.result === 'clean' ? 0 : 1
}

if (import.meta.url === pathToFileURL(process.argv[1] ?? '').href) {
  process.exitCode = main()
}
