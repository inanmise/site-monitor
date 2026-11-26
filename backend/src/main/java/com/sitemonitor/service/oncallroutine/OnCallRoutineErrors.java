package com.sitemonitor.service.oncallroutine;

import com.sitemonitor.config.GlobalExceptionHandler.CodedConflictException;
import com.sitemonitor.config.GlobalExceptionHandler.CodedForbiddenException;
import com.sitemonitor.config.GlobalExceptionHandler.CodedNotFoundException;
import com.sitemonitor.config.GlobalExceptionHandler.FieldValidationException;
import com.sitemonitor.util.Msg;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Nöbet rutinleri kodlu hataları (2026-11-26) — TEK yer; her ileti {@link Msg#t} ile istek dilinde (ne · neden · ne yapmalı).
 * Ek gövde alanları ({@code current}, {@code pending}, {@code current_version} …) kodlu çakışmanın ek alanlarıyla taşınır.
 */
public final class OnCallRoutineErrors {

    private OnCallRoutineErrors() {}

    public static CodedNotFoundException teamNotFound() {
        return new CodedNotFoundException("TEAM_NOT_FOUND", Msg.t(
                "Takım bulunamadı ya da bu takımın nöbet rutinini görme yetkiniz yok. Takım seçimini kontrol edin.",
                "The team wasn't found or you can't see this team's on-call routine. Check the team you picked."));
    }

    public static CodedForbiddenException moduleHidden() {
        return new CodedForbiddenException("ROUTINE_MODULE_HIDDEN", Msg.t(
                "Nöbet rutinleri bu takım için açık değil. Açılması için bir global yöneticiye başvurun (Ayarlar → Nöbet rutinleri).",
                "On-call routines aren't turned on for this team. Ask a global administrator to turn them on (Settings → On-call routines)."));
    }

    public static CodedConflictException notConfigured() {
        return new CodedConflictException("ROUTINE_NOT_CONFIGURED", Msg.t(
                "Bu takımın nöbet rutini henüz tanımlanmadı. Önce Şablon bölümünde en az bir etkin görev ekleyip kaydedin.",
                "This team's on-call routine isn't set up yet. First add at least one active task on the Template view and save."));
    }

    public static CodedConflictException paused() {
        return new CodedConflictException("ROUTINE_PAUSED", Msg.t(
                "Takımın nöbet rutini duraklatılmış; bu gün için işaretleme yapılamaz. Rutini Ayarlar bölümünden yeniden etkinleştirin.",
                "The team's on-call routine is paused; this day can't be ticked. Turn the routine back on from the Settings view."));
    }

    public static CodedNotFoundException taskNotFound() {
        return new CodedNotFoundException("TASK_NOT_FOUND", Msg.t(
                "Görev şablonda yok ya da etkin değil. Sayfayı yenileyip güncel listeden işaretleyin.",
                "The task isn't in the template or isn't active. Reload the page and tick it from the current list."));
    }

    public static FieldValidationException periodMismatch() {
        return new FieldValidationException("PERIOD_MISMATCH", "period_key", Msg.t(
                "Görevin dönemi bu güne uymuyor (gün / hafta / ay değişmiş olabilir). Sayfayı yenileyip yeniden deneyin.",
                "The task's period doesn't match this day (the day, week or month may have changed). Reload the page and try again."));
    }

    public static CodedConflictException itemChanged(Map<String, Object> current) {
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("current", current);
        return new CodedConflictException("ITEM_CHANGED", Msg.t(
                "Görev bu arada başka biri tarafından değiştirildi. Güncel durum gösteriliyor; gerekirse yeniden işaretleyin.",
                "Someone else changed the task meanwhile. The current state is shown; tick it again if needed."), extra);
    }

    public static CodedConflictException noteChanged(Map<String, Object> current) {
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("current", current);
        return new CodedConflictException("NOTE_CHANGED", Msg.t(
                "Not bu arada başka biri tarafından değiştirildi. Onların notunu görüp kendi notunuzu yeniden kaydedin ya da onlarınkini alın.",
                "Someone else changed the note meanwhile. Look at their note, then save yours again or keep theirs."), extra);
    }

    public static CodedConflictException dayCompleted(Map<String, Object> day) {
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("day", day);
        return new CodedConflictException("DAY_COMPLETED", Msg.t(
                "Gün tamamlandı; günlük görevler değiştirilemez. Değiştirmek için günü yeniden açın.",
                "The day is completed; its daily tasks can't be changed. Reopen the day to change them."), extra);
    }

    public static CodedConflictException dayNotReady(Object pending) {
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("pending", pending);
        return new CodedConflictException("DAY_NOT_READY", Msg.t(
                "Günün zorunlu görevlerinden bazıları bekliyor. Önce işaretleyin ya da kalanları bir nedenle atlayın.",
                "Some required tasks of the day are still open. Tick them first or skip the rest with a reason."), extra);
    }

    public static CodedConflictException dayLocked() {
        return new CodedConflictException("DAY_LOCKED", Msg.t(
                "Bu gün düzenleme penceresinin dışında (en çok " + OnCallRoutineCodes.EDIT_WINDOW_DAYS + " gün öncesi değiştirilebilir). Geçmiş yalnız okunabilir.",
                "This day is outside the edit window (at most " + OnCallRoutineCodes.EDIT_WINDOW_DAYS + " days back can be changed). History is read-only."));
    }

    public static FieldValidationException dayInFuture() {
        return new FieldValidationException("DAY_IN_FUTURE", "date", Msg.t(
                "Gelecekteki bir gün işaretlenemez. Bugünü ya da geçmiş bir günü seçin.",
                "A future day can't be ticked. Pick today or a past day."));
    }

    public static CodedConflictException notWorkday() {
        return new CodedConflictException("NOT_WORKDAY", Msg.t(
                "Bu gün takımın iş günü değil (hafta sonu ya da resmî tatil); rutin yok. Bir iş günü seçin.",
                "This day isn't a workday for the team (weekend or public holiday); there is no routine. Pick a workday."));
    }

    public static CodedConflictException concurrent(Map<String, Object> extra) {
        return new CodedConflictException("CONCURRENT_MODIFICATION", Msg.t(
                "Bu kayıt bu arada başka biri tarafından değiştirildi. Son hâlini yükleyip değişikliğinizi yeniden yapın.",
                "Someone else changed this record meanwhile. Load the latest version and make your change again."), extra);
    }

    public static CodedNotFoundException versionNotFound() {
        return new CodedNotFoundException("VERSION_NOT_FOUND", Msg.t(
                "Şablonun bu sürümü bulunamadı (en eski sürümler silinmiş olabilir). Listeden başka bir sürüm seçin.",
                "This template version wasn't found (the oldest versions may have been removed). Pick another version from the list."));
    }

    public static CodedNotFoundException problemNotFound() {
        return new CodedNotFoundException("PROBLEM_NOT_FOUND", Msg.t(
                "Sorun kaydı bulunamadı ya da silinmiş. Sayfayı yenileyin.",
                "The problem record wasn't found or was deleted. Reload the page."));
    }

    public static CodedConflictException imageLimit() {
        return new CodedConflictException("PROBLEM_IMAGE_LIMIT", Msg.t(
                "Görsel sınırına ulaşıldı: bir soruna en çok " + OnCallRoutineCodes.MAX_IMAGES_PER_PROBLEM + " görsel, takım başına toplam 200 MB eklenebilir. Bir görseli silip yeniden deneyin.",
                "Image limit reached: at most " + OnCallRoutineCodes.MAX_IMAGES_PER_PROBLEM + " images per problem and 200 MB per team. Delete an image and try again."));
    }

    public static CodedNotFoundException imageNotFound() {
        return new CodedNotFoundException("IMAGE_NOT_FOUND", Msg.t(
                "Görsel bulunamadı ya da görme yetkiniz yok. Sayfayı yenileyin.",
                "The image wasn't found or you can't see it. Reload the page."));
    }

    public static CodedNotFoundException incidentNotFound() {
        return new CodedNotFoundException("INCIDENT_NOT_FOUND", Msg.t(
                "Olay kaydı bulunamadı ya da görme yetkiniz yok. Olay numarasını kontrol edin.",
                "The incident record wasn't found or you can't see it. Check the incident number."));
    }

    public static CodedConflictException handoverNotDue() {
        return new CodedConflictException("HANDOVER_NOT_DUE", Msg.t(
                "Bu nöbet dönemi henüz bitmedi; devir dönem bittikten sonra onaylanabilir.",
                "This on-call period hasn't ended yet; the handover can be acknowledged after the period ends."));
    }

    public static CodedForbiddenException assignNotAllowed() {
        return new CodedForbiddenException("ASSIGN_NOT_ALLOWED", Msg.t(
                "Rutini başkasına atama yetkiniz yok: takımın nöbet çizelgesi varken başkasını yalnız takım yöneticisi atayabilir. Kendiniz devralabilirsiniz.",
                "You can't assign the routine to someone else: while the team has an on-call schedule only a team manager can. You can take it over yourself."));
    }

    public static FieldValidationException assigneeNotMember() {
        return new FieldValidationException("ASSIGNEE_NOT_MEMBER", "user_id", Msg.t(
                "Seçilen kişi bu takımın etkin bir üyesi değil. Listeden takım üyesi birini seçin.",
                "The chosen person isn't an active member of this team. Pick a team member from the list."));
    }

    public static FieldValidationException exportTooLarge() {
        return new FieldValidationException("EXPORT_TOO_LARGE", "from", Msg.t(
                "Seçilen aralık en çok " + OnCallRoutineCodes.EXPORT_MAX_ROWS + " görev satırı dışa aktarılabilir; daha kısa bir aralık seçin.",
                "The chosen range can export at most " + OnCallRoutineCodes.EXPORT_MAX_ROWS + " task rows; pick a shorter range."));
    }

    public static FieldValidationException field(String field, String tr, String en) {
        return new FieldValidationException(field, Msg.t(tr, en));
    }

    public static SecurityException forbidden(String tr, String en) {
        return new SecurityException(Msg.t(tr, en));
    }
}
