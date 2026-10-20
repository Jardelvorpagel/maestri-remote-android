# Terminal web (Maestri Wire)

Espelha e controla um terminal do host Maestri pelo navegador (xterm), com o socket
relay feito pelo plugin de `vite.config.ts` em `ws://<mesmo host>/terminal` (upgrade só aceito com Origin local).

- `npm install && npm run dev` → abra `http://127.0.0.1:5200/` (porta fixa).
- Token e id do terminal vêm de `web/.env.local` (`MAESTRI_WIRE_TOKEN`, `MAESTRI_TERMINAL_ID`), lidos só no servidor — nunca vão para o bundle.
- `npm run typecheck` e `npm run build` para conferir tipos e empacotar.
