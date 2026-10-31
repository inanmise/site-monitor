// Veritabanı Sorgu İzleme (11. tür, 2026-10-31) — form / sayfa YAPI TAŞLARI dizini.
//
// Her bileşenin gövdesi kendi dosyasında; bu dosya yalnız yeniden dışa aktarır (ad / props SABİT — sayfa
// DbQueryMonitorPage yalnız buradan içe aktarır). Props sözleşmesi:
//
// RuleBuilder        { rules, logic, onChange(rules), onLogicChange(logic), columns?: [{name,type}], fieldErrors?: {[alan]: ileti}, disabled? }
//                    — alan anahtarları sunucuyla aynı: 'rules', 'rules[N].<alt>' (N 0 tabanlı). Kaydetmeden önce istemci
//                    doğrulaması için `validateRules(rules)` (→ {alan: kod}; kod metni `dbq.rule.v.<kod>`).
// ScheduleEditor     { value: {schedule_mode, interval_seconds, cron_expression, windows, blackouts, skip_holidays, skip_dates},
//                      onChange(patch), fieldErrors?, disabled? } — sonraki çalışmaları kendisi önizler (schedule-preview)
// SqlEditor          { value, onChange(sql), dbType, fieldError?, violations?: [Violation], disabled?, onGuardChange?(result) }
//                    — canlı güvenlik denetimi (sql-check, 400 ms), satır numarası, ihlal listesi; data-field="sql_text".
//                    result = { status: 'empty'|'checking'|'ok'|'rejected'|'unknown', ok: true|false|null, violations, tables, message }
// ConnectionPicker   { teamId, value: profileId, onChange(profileId, profile), disabled?, error?, canManage?, teams? }
//                    — takımın bağlantıları + "Bağlantıları yönet" (ConnectionsManager); data-field="profile_id"
// ConnectionsManager { open, onOpenChange(open), teamId?, teams: [{id,name}], canEdit, onChanged?(profile?), initialView?: 'list'|'new' }
// TemplatePicker     { teamId, dbType?, onPick(template), disabled? }
// SaveTemplateDialog { open, onOpenChange(open), teamId, draft: {sql_text, purpose, rules, rule_logic, db_type}, onSaved?(template) }
// TemplatesManager   { open, onOpenChange(open), teamId?, teams, canEdit }
// RuleResultsList    { results: [RuleResult], compact? } — kural · beklenen · gelen · seviye (satır verisi değil)
// DbQueryAlertRows   { alertId, alertType } — alarm anındaki sorgu sonucu (paylaşımla okuyana yalnız satır sayısı)

export { default as RuleBuilder } from './RuleBuilder.jsx'
export { default as ScheduleEditor } from './ScheduleEditor.jsx'
export { default as SqlEditor } from './SqlEditor.jsx'
export { default as ConnectionPicker } from './ConnectionPicker.jsx'
export { default as ConnectionsManager } from './ConnectionsManager.jsx'
export { default as TemplatePicker } from './TemplatePicker.jsx'
export { default as SaveTemplateDialog } from './SaveTemplateDialog.jsx'
export { default as TemplatesManager } from './TemplatesManager.jsx'
export { default as RuleResultsList, ResultLevelBadge, resultState } from './RuleResultsList.jsx'
export { default as DbQueryAlertRows, isDbQueryAlertType } from './DbQueryAlertRows.jsx'
export { DbTypeBadge, HealthBadge } from './dbqueryParts.jsx'
export { validateRules, validateRule, ruleSummary, newRule, MAX_RULES } from './ruleModel.js'
export { describeCron } from './scheduleModel.js'
