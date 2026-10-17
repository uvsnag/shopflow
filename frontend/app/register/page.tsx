"use client";

// FR-01: sign up with email and password (then logs in and redirects to ?next= or "/").

import Link from "next/link";
import { useRouter, useSearchParams } from "next/navigation";
import { Suspense, useState } from "react";
import { ApiError, errorMessage, login, register } from "@/lib/api";
import { ErrorText, styles } from "@/components/ui";

function RegisterForm() {
  const router = useRouter();
  const next = useSearchParams().get("next") || "/";
  const [email, setEmail] = useState("");
  const [fullName, setFullName] = useState("");
  const [password, setPassword] = useState("");
  const [confirm, setConfirm] = useState("");
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({});
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  function validate() {
    const errors: Record<string, string> = {};
    if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email)) errors.email = "Enter a valid email address.";
    if (fullName.trim().length < 2) errors.fullName = "Enter your full name.";
    if (password.length < 8) errors.password = "Password must be at least 8 characters.";
    if (confirm !== password) errors.confirm = "Passwords do not match.";
    setFieldErrors(errors);
    return Object.keys(errors).length === 0;
  }

  async function onSubmit(e: React.FormEvent) {
    e.preventDefault();
    setError(null);
    if (!validate()) return;
    setBusy(true);
    try {
      await register(email.trim(), password, fullName.trim());
    } catch (err) {
      setError(
        err instanceof ApiError && err.status === 409
          ? "An account with this email already exists."
          : errorMessage(err),
      );
      setBusy(false);
      return;
    }
    try {
      await login(email.trim(), password);
      router.push(next.startsWith("/") ? next : "/");
    } catch {
      router.push(`/login?registered=1&next=${encodeURIComponent(next)}`);
    }
  }

  const field = (label: string, key: string, input: React.ReactNode) => (
    <label style={{ display: "grid", gap: 4 }}>
      <span>{label}</span>
      {input}
      {fieldErrors[key] && <span style={{ color: "crimson", fontSize: 13 }}>{fieldErrors[key]}</span>}
    </label>
  );

  return (
    <form onSubmit={onSubmit} noValidate style={{ display: "grid", gap: 12, maxWidth: 360 }}>
      <h1>Create account</h1>
      {field("Email", "email", <input style={styles.input} type="email" value={email} onChange={(e) => setEmail(e.target.value)} autoComplete="email" required />)}
      {field("Full name", "fullName", <input style={styles.input} value={fullName} onChange={(e) => setFullName(e.target.value)} autoComplete="name" required />)}
      {field("Password (min 8 characters)", "password", <input style={styles.input} type="password" value={password} onChange={(e) => setPassword(e.target.value)} autoComplete="new-password" required />)}
      {field("Confirm password", "confirm", <input style={styles.input} type="password" value={confirm} onChange={(e) => setConfirm(e.target.value)} autoComplete="new-password" required />)}
      <button type="submit" style={styles.primary} disabled={busy}>{busy ? "Creating…" : "Register"}</button>
      <ErrorText>{error}</ErrorText>
      <p style={styles.muted}>
        Already have an account? <Link href={`/login?next=${encodeURIComponent(next)}`}>Log in</Link>
      </p>
    </form>
  );
}

export default function RegisterPage() {
  return (
    <Suspense>
      <RegisterForm />
    </Suspense>
  );
}
