import { cookies } from "next/headers";
import { redirect } from "next/navigation";
import { COOKIE_NAME, parseLoginPayload } from "@/src/utils/cookie";

export default async function Home() {
  const cookieStore = await cookies();
  const payload = parseLoginPayload(cookieStore.get(COOKIE_NAME)?.value);

  redirect(payload?.user ? "/chat" : "/login");
}
