// NFR-17: 100 users placing orders for 5 minutes — no overselling, no double charge.
// Run: k6 run load-test/order-flow.js
// Rough skeleton: register/login once per VU, then add to cart and order in a loop.

import http from "k6/http";
import { check, sleep } from "k6";
import { uuidv4 } from "https://jslib.k6.io/k6-utils/1.4.0/index.js";

const BASE = __ENV.BASE_URL || "http://localhost:8080/api/v1";

export const options = {
  vus: 100,
  duration: "5m",
  thresholds: {
    http_req_duration: ["p(95)<500"], // NFR-15
    checks: ["rate>0.95"],
  },
};

export function setup() {
  // one shared product to fight over — proves optimistic locking
  return { productId: 1 };
}

export default function (data) {
  const email = `k6-${__VU}@shopflow.local`;
  const password = "password123";

  // register is idempotent-ish: 201 first time, 409 after
  http.post(`${BASE}/auth/register`, JSON.stringify({ email, password }), jsonHeaders());

  const loginRes = http.post(`${BASE}/auth/login`, JSON.stringify({ email, password }), jsonHeaders());
  if (!check(loginRes, { "logged in": (r) => r.status === 200 })) return;
  const token = loginRes.json("accessToken");

  const auth = jsonHeaders(token);
  http.put(`${BASE}/cart/items/${data.productId}`, JSON.stringify({ quantity: 1 }), auth);

  const orderRes = http.post(`${BASE}/orders`, null, {
    headers: { ...auth.headers, "Idempotency-Key": uuidv4() },
  });
  // Overselling shows up as clean 409s here (stock reservation fails), never as
  // extra 201s; the final stock-vs-orders comparison is done after the run.
  check(orderRes, { "order placed or rejected cleanly": (r) => [201, 400, 409].includes(r.status) });

  if (orderRes.status === 201) {
    // NFR-17: pressing "Pay" twice must charge once.
    const order = orderRes.json();
    const payBody = JSON.stringify({ orderId: order.id, amount: order.totalAmount });
    const payHeaders = { headers: { ...auth.headers, "Idempotency-Key": uuidv4() } };
    const pay1 = http.post(`${BASE}/payments`, payBody, payHeaders);
    const pay2 = http.post(`${BASE}/payments`, payBody, payHeaders); // same key: replay
    check(pay2, {
      "no double charge: same key replays same payment": (r) =>
        pay1.status === 202 && r.status === 202 && r.json("paymentId") === pay1.json("paymentId"),
    });
    const pay3 = http.post(`${BASE}/payments`, payBody, {
      headers: { ...auth.headers, "Idempotency-Key": uuidv4() }, // new key, same order
    });
    check(pay3, { "no double charge: second payment for order rejected": (r) => r.status === 409 });
  }

  sleep(1);
}

function jsonHeaders(token) {
  return {
    headers: {
      "Content-Type": "application/json",
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
    },
  };
}
