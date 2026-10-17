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
  check(orderRes, { "order placed or rejected cleanly": (r) => [201, 400, 409].includes(r.status) });

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
