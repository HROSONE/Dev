# DevAgent

App **Android** (Kotlin + Jetpack Compose) com agente de código local.

Modelo padrão: **`gemini-3.8-flash`**.

## O que é real

| Capacidade | Status |
|---|---|
| Gemini 3.8 Flash | ✅ |
| Ler repositório GitHub (REST) | ✅ |
| Workspace local isolado | ✅ |
| Tools: list / read / write / run_command | ✅ |
| Comandos: `pwd`, `ls`, `cat` | ✅ |
| Confirmação humana + diff antes de gravar | ✅ |
| Terminal da UI ligado ao workspace | ✅ |
| Commit no GitHub (Contents API, 1 arquivo) | ✅ (token `contents:write`) |
| Docker / gVisor / Next.js backend | ❌ não existe neste app |

## Setup

1. `.env.example` → `.env` com `GEMINI_API_KEY=...`
2. Build no Android Studio / AI Studio
3. Conecte `owner/repo` (token opcional; use `contents:write` se for commitar)

## Fluxo

1. Conectar repo → arquivos no `filesDir/devagent-workspace`
2. Modo **Construir** → pedir alteração
3. Aprovar no diálogo (diff + prévia)
4. Arquivo grava no workspace
5. Aba **Sandbox → Commit** → envia ao GitHub (se houver token)

## Segurança

- Path canonical (sem traversal)
- Sem shell / pipes
- Token só em memória de sessão
- Mutação só com BUILD + intenção de implementação + confirmação UI

## Testes

```bash
gradle testDebugUnitTest
```

Inclui testes de parser, policy, intent e workspace.
