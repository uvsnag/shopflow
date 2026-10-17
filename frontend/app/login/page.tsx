"use client";

// FR-02: log in; redirects to ?next= (same-origin path) or "/" afterwards.

import Link from "next/link";
import { useRouter, useSearchParams } from "next/navigation";
import { Suspense, useState } from "react";
import { ApiError, errorMessage, login } from "@/lib/api";
import { ErrorText, InfoText, styles } from "@/components/ui";

function LoginForm() {
  const router = useRouter();
  const params = useSearchParams();
  const next = params.get("next") || "/";
  const justRegistered = params.get("registered") === "1";
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({});
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  function validate() {
    const errors: Record<string, string> = {};
    if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email)) errors.email = "Enter a valid email address.";
    if (!password) errors.password = "Enter your password.";
    setFieldErrors(errors);
    return Object.keys(errors).length === 0;
  }

  async function onSubmit(e: React.FormEvent) {
    e.preventDefault();
    setError(null);
    if (!validate()) return;
    setBusy(true);
    try {
      await login(email.trim(), password);
      router.push(next.startsWith("/") ? next : "/");
    } catch (err) {
      if (err instanceof ApiError && (err.status === 400 || err.status === 401)) {
        setError("Login failed. Check your email and password.");
      } else {
        setError(errorMessage(err));
      }
      setBusy(false);
    }
  }

  return (
    <form onSubmit={onSubmit} noValidate style={{ display: "grid", gap: 12, maxWidth: 360 }}>
      <h1>Log in</h1>
      {justRegistered && <InfoText>Account created. Please log in.</InfoText>}
      <label style={{ display: "grid", gap: 4 }}>
        <span>Email</span>
        <input style={styles.input} type="email" value={email} onChange={(e) => setEmail(e.target.value)} autoComplete="email" required />
        {fieldErrors.email && <span style={{ color: "crimson", fontSize: 13 }}>{fieldErrors.email}</span>}
      </label>
      <label style={{ display: "grid", gap: 4 }}>
        <span>Password</span>
        <input style={styles.input} type="password" value={password} onChange={(e) => setPassword(e.target.value)} autoComplete="current-password" required />
        {fieldErrors.password && <span style={{ color: "crimson", fontSize: 13 }}>{fieldErrors.password}</span>}
      </label>
      <button type="submit" style={styles.primary} disabled={busy}>{busy ? "Logging in…" : "Log in"}</button>
      <ErrorText>{error}</ErrorText>
      <p style={styles.muted}>
        No account yet? <Link href={`/register?next=${encodeURIComponent(next)}`}>Register</Link>
      </p>
    </form>
  );
}

export default function LoginPage() {
  return (
    <Suspense>
      <LoginForm />
    </Suspense>
  );
}
