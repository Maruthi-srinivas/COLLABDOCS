package com.collabdocs.worker;

import com.collabdocs.persistence.AccountPurgeService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class AccountPurgeJob {
    private final AccountPurgeService purgeService;

    public AccountPurgeJob(AccountPurgeService purgeService) {
        this.purgeService = purgeService;
    }

    @Scheduled(fixedDelay = 300_000)
    public void purgeDueAccounts() {
        purgeService.purgeDue();
    }
}
