// NFR-17: 100 users placing orders for 5 minutes — no overselling, no double charge.
// Run:  k6 run load-test/order-flow.js
//       k6 run -e BASE_URL=http://<demo-host>:8080/api/v1 load-test/order-flow.js
// After the run, compare in inventory_db:  stock.available + stock.reserved + sold
// must still equal the seeded quantity, and payments must have one row per order.

import http from "k6/http";
import { check, sleep } from "k6";
import { uuidv4 } from "https://jslib.k6.io/k6-utils/1.4.0/index.js";

const BASE = __ENV.BASE_URL || "http://localhost:8080/api/v1";

export const options = {
  vus: Number(__ENV.VUS || 100),
  duration: __ENV.DURATION || "5m",
  thresholds: {
    "http_req_duration{name:place-order}": ["p(95)<500"], // NFR-15
    checks: ["rate>0.95"],
  },
};

export function setup() {
  // one shared product to fight over — proves optimistic locking (BR-01)
  return { productId: Number(__ENV.PRODUCT_ID || 1) };
}

export default function (data) {
  const email = `k6-${__VU}@shopflow.local`;
  const password = "password123";

  // register is idempotent-ish: 201 first time, 409 after
  http.post(`${BASE}/auth/register`, JSON.stringify({ email, password, fullName: `k6 ${__VU}` }), jsonHeaders());

  const loginRes = http.post(`${BASE}/auth/login`, JSON.stringify({ email, password }), jsonHeaders());
  if (!check(loginRes, { "logged in": (r) => r.status === 200 })) return;
  const token = loginRes.json("accessToken");

  const auth = jsonHeaders(token);
  http.put(`${BASE}/cart/items/${data.productId}`, JSON.stringify({ quantity: 1 }), auth);

  const orderRes = http.post(`${BASE}/orders`, null, {
    headers: { ...auth.headers, "Idempotency-Key": uuidv4() },
    tags: { name: "place-order" },
  });
  check(orderRes, { "order placed or rejected cleanly": (r) => [201, 400, 409].includes(r.status) });
  if (orderRes.status !== 201) {
    sleep(1);
    return;
  }

  // The saga runs in the background: wait until stock is reserved (or the
  // order was cancelled because the product sold out).
  const order = orderRes.json();
  let status = order.status;
  for (let i = 0; i < 15 && status !== "STOCK_RESERVED" && status !== "CANCELLED"; i++) {
    sleep(1);
    status = http.get(`${BASE}/orders/${order.id}`, auth).json("status");
  }
  check(status, { "saga settled (reserved or sold out)": (s) => s === "STOCK_RESERVED" || s === "CANCELLED" });
  if (status !== "STOCK_RESERVED") {
    return; // sold out: a clean CANCELLED, never an oversell
  }

  // NFR-17 / BR-04: pressing "Pay" twice must charge once. The amount is not
  // sent: payment-service takes it from its order snapshot.
  const payBody = JSON.stringify({ orderId: order.id });
  const payHeaders = { headers: { ...auth.headers, "Idempotency-Key": uuidv4() }, tags: { name: "pay" } };
  const pay1 = http.post(`${BASE}/payments`, payBody, payHeaders);
  const pay2 = http.post(`${BASE}/payments`, payBody, payHeaders); // same key: replay
  check(pay2, {
    "no double charge: same key replays same payment": (r) =>
      pay1.status === 202 && r.status === 202 && r.json("paymentId") === pay1.json("paymentId"),
  });
  const pay3 = http.post(`${BASE}/payments`, payBody, {
    headers: { ...auth.headers, "Idempotency-Key": uuidv4() }, // new key, same order
    tags: { name: "pay" },
  });
  check(pay3, { "no double charge: second payment for order rejected": (r) => r.status === 409 });

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
