import type { LoginPayload } from "@/src/types/api";

export const COOKIE_NAME = "ai_agent_login";
export const COOKIE_DAYS = 7;

export function setCookieValue(name: string, value: string, days: number) {
  if (typeof document === "undefined") return;
  const maxAge = Math.max(0, Math.floor(days * 86400));
  document.cookie = `${name}=${encodeURIComponent(value)}; Max-Age=${maxAge}; Path=/; SameSite=Lax`;
}

export function getCookieValue(name: string) {
  if (typeof document === "undefined") return null;
  const cookies = document.cookie ? document.cookie.split("; ") : [];
  for (const item of cookies) {
    const eqIndex = item.indexOf("=");
    const key = eqIndex >= 0 ? item.slice(0, eqIndex) : item;
    const value = eqIndex >= 0 ? item.slice(eqIndex + 1) : "";
    if (key === name) return decodeURIComponent(value);
  }
  return null;
}

export function deleteCookieValue(name: string) {
  if (typeof document === "undefined") return;
  document.cookie = `${name}=; Max-Age=0; Path=/; SameSite=Lax`;
}

export function parseLoginPayload(raw?: string | null): LoginPayload | null {
  if (!raw) return null;
  try {
    const payload = JSON.parse(raw) as LoginPayload;
    if (!payload?.user) return null;
    return payload;
  } catch {
    return null;
  }
}

export function readLoginPayload() {
  return parseLoginPayload(getCookieValue(COOKIE_NAME));
}

export function formatTime(ts?: number) {
  if (!ts) return "";
  const date = new Date(ts);
  const pad = (value: number) => String(value).padStart(2, "0");

  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(
    date.getDate(),
  )} ${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(
    date.getSeconds(),
  )}`;
}
