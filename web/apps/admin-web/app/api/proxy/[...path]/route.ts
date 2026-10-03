import { bff } from "@/lib/bff";

export const runtime = "nodejs";
export const dynamic = "force-dynamic";

type Ctx = { params: Promise<{ path: string[] }> };
const handle = (req: Request, ctx: Ctx) => bff.routes.proxy(req, ctx);

export { handle as GET, handle as POST, handle as PUT, handle as PATCH, handle as DELETE };
