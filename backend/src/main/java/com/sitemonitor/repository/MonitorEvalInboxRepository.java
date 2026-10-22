package com.sitemonitor.repository;

import com.sitemonitor.model.MonitorEvalInbox;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * {@code monitor_eval_inbox} — yalnız OKUMA amaçlı (sayım / tanılama). Yazma, sahiplenme ve silme koşullu SQL ile
 * {@code service.monitoring.ManualEvalInbox}'ta (iki pod aynı satırı iki kez işlemesin).
 */
public interface MonitorEvalInboxRepository extends JpaRepository<MonitorEvalInbox, Long> {
}
