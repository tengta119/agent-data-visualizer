import { cookies } from "next/headers";
import { redirect } from "next/navigation";
import LoginClient from "./login-client";
import { COOKIE_NAME, parseLoginPayload } from "@/src/utils/cookie";

export default async function LoginPage() {
  const cookieStore = await cookies();
  const payload = parseLoginPayload(cookieStore.get(COOKIE_NAME)?.value);

  if (payload?.user) {
    redirect("/chat-stream");
  }

  return <LoginClient />;
}
