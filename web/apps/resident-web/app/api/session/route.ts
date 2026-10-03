import { bff } from "@/lib/bff";

export const runtime = "nodejs";
export const dynamic = "force-dynamic";

export const GET = (req: Request) => bff.routes.session(req);
