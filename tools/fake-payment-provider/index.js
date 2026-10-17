// Fake payment provider: pretends to be a bank.
// - POST /charge  -> replies 202 at once, then (after a random delay) calls the
//   webhook with SUCCEEDED (~80%) or FAILED (~20%). Sometimes it is slow, so
//   payment-service's timeout / retry / circuit breaker can be tested.
// - POST /refund  -> always succeeds.
// The webhook body is signed with HMAC-SHA256 in the X-Signature header (NFR-06).

import express from "express";
import crypto from "crypto";

const app = express();
app.use(express.json());

const PORT = process.env.PORT || 9090;
const WEBHOOK_SECRET = process.env.WEBHOOK_SECRET || "local-dev-secret";
const SUCCESS_RATE = Number(process.env.SUCCESS_RATE || 0.8);
const SLOW_RATE = Number(process.env.SLOW_RATE || 0.1);

app.get("/health", (_req, res) => res.json({ status: "UP" }));

app.post("/charge", async (req, res) => {
  const { paymentId, orderId, amount, webhookUrl } = req.body || {};
  if (!paymentId || !webhookUrl) {
    return res.status(400).json({ error: "paymentId and webhookUrl are required" });
  }

  // sometimes be slow before even answering, to trip the 3s timeout
  if (Math.random() < SLOW_RATE) {
    await sleep(5000);
  }

  const providerRef = "bank_" + crypto.randomUUID();
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
  res.json({ providerRef: providerRef || null, status: "SUCCEEDED" });
});

async function sendWebhook(url, payload) {
  const body = JSON.stringify(payload);
  const signature = crypto.createHmac("sha256", WEBHOOK_SECRET).update(body).digest("hex");
  try {
    const response = await fetch(url, {
      method: "POST",
      headers: { "Content-Type": "application/json", "X-Signature": signature },
      body,
    });
    console.log(`webhook -> ${url} payment=${payload.paymentId} status=${payload.status} http=${response.status}`);
  } catch (err) {
    console.error(`webhook failed for payment ${payload.paymentId}:`, err.message);
    // TODO: retry webhooks like a real provider would
  }
}

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

app.listen(PORT, () => console.log(`fake-payment-provider listening on :${PORT}`));
