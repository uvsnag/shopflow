package com.shopflow.payment.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Executes queued refunds (first attempt and retries), so compensation
 * eventually completes even when the bank is flaky. Polls often: a refund is
 * normally sent within a few seconds of being requested.
 */
@Component
public class RefundRetryJob {

    private static final Logger log = LoggerFactory.getLogger(RefundRetryJob.class);

    private final PaymentService paymentService;

    public RefundRetryJob(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @Scheduled(fixedDelayString = "${shopflow.refund-retry.poll-ms:5000}", initialDelay = 10000)
    public void run() {
        int processed = paymentService.processDueRefunds();
        if (processed > 0) {
            log.info("Processed {} refund(s)", processed);
        }
    }
}
