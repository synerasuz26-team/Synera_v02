package com.synera.api.order;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class ReconciliationScheduler {
    private final ReconciliationService reconciliation;

    public ReconciliationScheduler(ReconciliationService reconciliation) {
        this.reconciliation = reconciliation;
    }

    @Scheduled(cron = "${synera.reconciliation.cron:0 10 1 * * *}", zone = "Asia/Ho_Chi_Minh")
    public void snapshotPreviousBusinessDay() {
        reconciliation.snapshotPreviousBusinessDay();
    }
}
