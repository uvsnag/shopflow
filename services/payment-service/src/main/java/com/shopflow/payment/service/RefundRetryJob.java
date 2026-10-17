package com.shopflow.payment.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Retries refunds whose provider call failed, so compensation eventually completes. */
@Component
public class RefundRetryJob {

    private static final Logger log = LoggerFactory.getLogger(RefundRetryJob.class);

    private final PaymentService paymentService;

    public RefundRetryJob(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @Scheduled(fixedDelayString = "${shopflow.refund-retry.poll-ms:60000}", initialDelay = 30000)
    public void run() {
        int retried = paymentService.retryPendingRefunds();
        if (retried > 0) {
            log.info("Retried {} pending refund(s)", retried);
        }
    }
}
