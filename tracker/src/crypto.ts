// Cifrado con WebCrypto: igual en Cloudflare Workers, Node y el navegador.

const enc = new TextEncoder();

export function hex(bytes: ArrayBuffer | Uint8Array): string {
  const b = bytes instanceof Uint8Array ? bytes : new Uint8Array(bytes);
  let s = "";
  for (const x of b) s += x.toString(16).padStart(2, "0");
  return s;
}

export function randomHex(n: number): string {
  return hex(crypto.getRandomValues(new Uint8Array(n)));
}

export async function sha256(text: string): Promise<string> {
  return hex(await crypto.subtle.digest("SHA-256", enc.encode(text)));
}

/** Comparación que no se delata por el tiempo que tarda. */
export function same(a: string, b: string): boolean {
  if (a.length !== b.length) return false;
  let d = 0;
  for (let i = 0; i < a.length; i++) d |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return d === 0;
}

/**
 * La clave que manda un aparato en vez de la contraseña (la deriva él, así el
 * tracker no gasta CPU y nunca ve la contraseña). Los clientes en Java y en el
 * visor hacen exactamente esto; la usan los tests.
 */
export const KEY_ITERATIONS = 200_000;

export async function deriveKey(user: string, password: string): Promise<string> {
  const base = await crypto.subtle.importKey("raw", enc.encode(password), "PBKDF2", false, ["deriveBits"]);
  const bits = await crypto.subtle.deriveBits(
    { name: "PBKDF2", hash: "SHA-256", salt: enc.encode("synctablet:" + user), iterations: KEY_ITERATIONS },
    base,
    256,
  );
  return hex(bits);
}
