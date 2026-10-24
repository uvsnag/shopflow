// Fake payment provider: pretends to be a bank.
// - POST /charge  -> replies 202 at once, then (after a random delay) calls the
//   webhook with SUCCEEDED (~80%) or FAILED (~20%). Sometimes it is slow, so
//   payment-service's timeout / retry / circuit breaker can be tested.
// - POST /refund  -> always succeeds (set REFUND_FAIL_RATE to test the refund
//   retry job in payment-service).
// Like a real provider, both endpoints honour an Idempotency-Key header: a
// retried call with the same key (e.g. after a client-side timeout) returns
// the original result and does not charge / refund / send a webhook again.
// The webhook body is signed with HMAC-SHA256 in the X-Signature header (NFR-06).
// Like a real provider, a webhook that cannot be delivered is retried with
// backoff (WEBHOOK_RETRIES times) so a short payment-service outage is survived.

import express from "express";
import crypto from "crypto";

const app = express();
app.use(express.json());

const PORT = process.env.PORT || 9090;
const WEBHOOK_SECRET = process.env.WEBHOOK_SECRET || "local-dev-secret";
const SUCCESS_RATE = Number(process.env.SUCCESS_RATE || 0.8);
const SLOW_RATE = Number(process.env.SLOW_RATE || 0.1);
const REFUND_FAIL_RATE = Number(process.env.REFUND_FAIL_RATE || 0);
const WEBHOOK_RETRIES = Number(process.env.WEBHOOK_RETRIES || 5);

// Idempotency-Key -> result. In memory is enough for a fake.
const charges = new Map();
const refunds = new Map();

app.get("/health", (_req, res) => res.json({ status: "UP" }));

app.post("/charge", async (req, res) => {
  const { paymentId, orderId, amount, webhookUrl } = req.body || {};
  if (!paymentId || !webhookUrl) {
    return res.status(400).json({ error: "paymentId and webhookUrl are required" });
  }

  const key = req.get("Idempotency-Key") || `payment-${paymentId}`;
  const known = charges.get(key);
  if (known) {
    // retry of a charge we already took: same answer, no second charge or webhook
    return res.status(202).json({ providerRef: known.providerRef, status: "PROCESSING" });
  }
  const providerRef = "bank_" + crypto.randomUUID();
  charges.set(key, { providerRef }); // recorded BEFORE the slow part, like a real bank

  // sometimes be slow before even answering, to trip the 3s timeout
  if (Math.random() < SLOW_RATE) {
    await sleep(5000);
  }

  res.status(202).json({ providerRef, status: "PROCESSING" });

  // settle asynchronously through the signed webhook
  const succeeded = Math.random() < SUCCESS_RATE;
  const delayMs = 500 + Math.random() * 2000;
  setTimeout(() => sendWebhook(webhookUrl, {
    paymentId,
    orderId,
    providerRef,
    amount,
    status: succeeded ? "SUCCEEDED" : "FAILED",
  }), delayMs);
});

app.post("/refund", (req, res) => {
  const { providerRef } = req.body || {};
  const key = req.get("Idempotency-Key");
  if (key && refunds.has(key)) {
    return res.json(refunds.get(key)); // already refunded: do not pay out twice
  }
  if (Math.random() < REFUND_FAIL_RATE) {
    return res.status(503).json({ error: "refund temporarily unavailable" });
  }
  const result = { providerRef: providerRef || null, status: "SUCCEEDED" };
  if (key) refunds.set(key, result);
  res.json(result);
});

function sign(body) {
  return crypto.createHmac("sha256", WEBHOOK_SECRET).update(body).digest("hex");
}

async function sendWebhook(url, payload) {
  const body = JSON.stringify(payload);
  const signature = sign(body);
  for (let attempt = 1; attempt <= WEBHOOK_RETRIES; attempt++) {
    try {
      const response = await fetch(url, {
        method: "POST",
        headers: { "Content-Type": "application/json", "X-Signature": signature },
        body,
      });
      console.log(`webhook -> ${url} payment=${payload.paymentId} status=${payload.status} http=${response.status} attempt=${attempt}`);
      if (response.status < 500) {
        return; // delivered (2xx) or permanently rejected (4xx): do not retry
      }
    } catch (err) {
      console.error(`webhook attempt ${attempt} failed for payment ${payload.paymentId}: ${err.message}`);
    }
    await sleep(1000 * 2 ** (attempt - 1)); // 1s, 2s, 4s, 8s, ...
  }
  console.error(`webhook for payment ${payload.paymentId} gave up after ${WEBHOOK_RETRIES} attempts`);
}

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

app.listen(PORT, () => console.log(`fake-payment-provider listening on :${PORT}`));
