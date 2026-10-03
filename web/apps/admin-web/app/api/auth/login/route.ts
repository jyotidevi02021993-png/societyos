import { bff } from "@/lib/bff";

export const runtime = "nodejs";
export const dynamic = "force-dynamic";

export const POST = (req: Request) => bff.routes.passwordLogin(req);
