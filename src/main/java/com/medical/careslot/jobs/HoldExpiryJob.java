package com.medical.careslot.jobs;

import com.medical.careslot.services.HoldExpiryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
        prefix = "careslot.holds.expiry-scheduler",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class HoldExpiryJob {

    private final Logger log = LoggerFactory.getLogger(HoldExpiryJob.class);

    private final HoldExpiryService holdExpiryService;

    public HoldExpiryJob(HoldExpiryService holdExpiryService) {
        this.holdExpiryService = holdExpiryService;
    }

    @Scheduled(
            initialDelayString = "${careslot.holds.expiry-scheduler.initial-delay-ms:5000}",
            fixedDelayString = "${careslot.holds.expiry-scheduler.fixed-delay-ms:10000}"
    )
    public void expireHolds() {
        try {
            int expiredCount = holdExpiryService.expireActiveHolds();

            if (expiredCount > 0) {
                log.info("Hold expiry sweep completed: expiredHolds={}", expiredCount);
            } else {
                log.debug("Hold expiry sweep completed: expiredHolds=0");
            }
        } catch (RuntimeException exception) {
            log.error("Hold expiry sweep failed; the next scheduled run will retry", exception);
        }
    }
}
