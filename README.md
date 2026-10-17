# DevAgent — Ambiente de Desenvolvimento com IA

App Android (Kotlin + Jetpack Compose) que conecta o Gemini a um **workspace isolado** e a um **leitor read-only do GitHub**, com política de segurança e confirmação humana antes de qualquer escrita.

## O que funciona de verdade

| Capacidade | Status |
|---|---|
| Conectar repositório GitHub (público ou privado com token) | ✅ |
| Sincronizar arquivos no workspace local do app | ✅ |
| Classificar intenção (conversa / plano / implementação) | ✅ |
| Loop de ferramentas limitado (list/read/write/run_command) | ✅ |
| Comandos: `pwd`, `ls`, `cat` (sem shell) | ✅ |
| Confirmação humana antes de gravar arquivos | ✅ |
| Métricas reais de tools executadas | ✅ |
| Commit/push no GitHub | ❌ (ainda não) |

## Configuração

1. Defina a chave do Gemini:
   - Copie `.env.example` → `.env`
   - Preencha `GEMINI_API_KEY=sua_chave`
2. Abra o projeto no Android Studio / AI Studio e rode o app.
3. Na UI, toque no badge do repositório e informe `owner/repo` (token opcional para repos privados).

## Segurança (por design)

- Workspace fica em `filesDir/devagent-workspace` (sandbox do app).
- Paths são resolvidos com `canonicalFile` — path traversal e absolutos são rejeitados.
- `run_command` **não** usa shell: só `pwd`, `ls` e `cat`, sem pipes/metacaracteres.
- Escritas exigem:
  1. Modo **BUILD**
  2. Intenção classificada como `IMPLEMENTATION_REQUEST`
  3. **Confirmação explícita** no diálogo da UI
- Token do GitHub nunca é persistido; só vale na sessão atual.
- Arquivos sensíveis (`.env`, keys, keystores, etc.) são ignorados na leitura do GitHub.

## Fluxo típico

1. Conecte um repositório → arquivos vão para o workspace local.
2. No modo **Construir**, peça uma alteração (ex.: "adicione um README com instruções de build").
3. O agente pode chamar `list_files` / `read_file` e propor `write_file`.
4. Um diálogo mostra path + prévia do conteúdo → **Aprovar** ou **Recusar**.
5. Só após aprovar o arquivo é gravado no workspace. Nada sobe automaticamente para o GitHub.

## Limitações atuais (honestas)

- Não faz commit/push no GitHub.
- Comandos de terminal muito limitados (sem Gradle, sem testes automatizados do projeto alvo).
- Contexto do repositório limitado (~30 arquivos prioritários, ~400k chars).
- Modelos: `gemini-2.0-flash` (rápido) e `gemini-2.5-pro-preview-05-06` (raciocínio profundo) — ajuste em `AgentModels.kt` se a API mudar.

## Estrutura principal

```
app/src/main/java/com/example/
  data/
    api/          GeminiClient, GitHubRepositoryClient
    engine/       DevAgentEngine, AgentToolLoop, policies, workspace tools
    model/        ChatMessage, ToolCallInfo, PendingMutation
  ui/
    screens/      Chat, Sandbox, Arquitetura
    viewmodel/    DevAgentViewModel
```

## Licença

Uso privado / experimental. Ajuste conforme sua necessidade.
