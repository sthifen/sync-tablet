# Tracker de Sync Tablet

Cuentas con contraseña y «mis aparatos», para que la tablet, el celular y el PC
se encuentren solos. Corre gratis en Cloudflare Workers + D1. El contrato está
en [PROTOCOLO.md](PROTOCOLO.md).

```bash
cd tracker
npm install
npm test          # API completa contra una base en memoria
npm run check     # tipos
npm run dev       # tracker local en http://<tu IP>:8787 (base D1 local)
```

Para probar sin publicar, pon `http://<IP del PC>:8787` en «Servidor de
cuentas» al iniciar sesión (en el visor del PC y en la app).

## Publicar (una vez)

1. Crea una cuenta gratis en <https://dash.cloudflare.com>.
2. `npx wrangler login` y `npx wrangler d1 create synctablet`. Copia el
   `database_id` que imprime en `wrangler.toml`.
3. En GitHub → Settings → Secrets and variables → Actions, añade
   `CLOUDFLARE_API_TOKEN` (Mi perfil → API Tokens → plantilla «Edit Cloudflare
   Workers», más el permiso *D1: Edit*) y `CLOUDFLARE_ACCOUNT_ID`.
4. `git push`: la Action prueba, aplica las migraciones y publica. La URL
   queda como `https://synctablet-tracker.<tu-subdominio>.workers.dev`. Si no
   coincide con `DEFAULT_TRACKER` (`src/account.rs` y `Account.java`), cámbialo
   ahí.
