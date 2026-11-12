// Punto de entrada en Cloudflare Workers: sólo une la API con D1.

import { handle } from "./api.ts";
import { D1Repo } from "./repo.ts";

export interface Env {
  DB: D1Database;
}

export default {
  async fetch(req: Request, env: Env): Promise<Response> {
    return handle(req, new D1Repo(env.DB));
  },
} satisfies ExportedHandler<Env>;
