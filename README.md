# DevAgent

App **Android** (Kotlin + Jetpack Compose) com agente autônomo de engenharia de software estilo **Claude Code** e editor de código local integrado.

Provedores suportados: **Google Gemini** (`gemini-3.8-flash`) e **OpenRouter** (Claude Sonnet, DeepSeek, Qwen, etc.).

## O que é real

| Capacidade | Status |
|---|---|
| Gemini 3.8 Flash + OpenRouter | ✅ |
| Ler repositório GitHub (até 120 arquivos) | ✅ |
| Workspace local isolado + Editor de Código na UI | ✅ |
| Tools: `list_files`, `search_files`, `read_file`, `edit_file`, `write_file`, `delete_file`, `run_command` | ✅ |
| Edição cirúrgica (`edit_file` com `old_text` → `new_text`) e auto-recuperação de erros | ✅ |
| Modo Autônomo (`⚡ Auto`) ou Revisão por Diff (`🔒 Diff`) | ✅ |
| Comandos do terminal: `pwd`, `ls`, `cat`, `grep`, `find`, `head`, `tail`, `wc`, `tree` | ✅ |
| Commit atômico multi-arquivo no GitHub (Git Data API) + commit individual | ✅ (token `contents:write`) |
| Auto-Update via GitHub Releases (`HROSONE/Dev`) | ✅ |

## Setup

1. `.env.example` → `.env` com `GEMINI_API_KEY=...` (ou configure direto na engrenagem dentro do app)
2. Build no Android Studio / GitHub Actions (`Build APK`)
3. Conecte `owner/repo` (token opcional; use `contents:write` se for commitar)

## Fluxo Autônomo (Estilo Claude Code)

1. Conecte um repositório → arquivos sincronizados em `filesDir/devagent-workspace`
2. No modo **Construir (`⚡ Auto`)**, peça qualquer funcionalidade, correção ou refatoração em linguagem natural
3. O agente pesquisa (`search_files`), lê (`read_file`), edita cirurgicamente múltiplos arquivos (`edit_file` / `write_file`) em até 18 passos encadeados e exibe o diff colorido em cada card
4. Você também pode editar/criar/reverter arquivos manualmente na aba **Arquivos**
5. Na aba **Arquivos → Commit**, envie todas as mudanças em um único commit atômico para o GitHub

## Testes

```bash
gradle testDebugUnitTest
```

Inclui testes de parser, policy, intent, edições cirúrgicas, busca e workspace.
