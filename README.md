# DevAgent

App **Android** (Kotlin + Jetpack Compose) com agente autônomo de engenharia de software estilo **Claude Code**, suporte ao **Qwen 2.5 Coder** (download GGUF no aparelho + inferência local/cloud), histórico persistente de conversas e criação completa de projetos locais e repositórios no GitHub.

## O que é real

| Capacidade | Status |
|---|---|
| **Google Gemini (`gemini-3.8-flash`)**, **OpenRouter** e **Qwen 2.5 Coder** (Download GGUF 0.5B/1.5B/3B/7B no celular + Endpoint Local / HF / OpenRouter) | ✅ |
| **Histórico de Conversas Persistente** (`ChatHistoryStore` salva automaticamente em disco ao abrir/fechar o app) | ✅ |
| **Importar Arquivos Avulsos ou `.ZIP` do Celular** (Storage Access Framework) | ✅ |
| **Criar Projetos Inteiros do Zero no DevAgent** (Templates: Em Branco, Android Compose, Python, Web App) | ✅ |
| **Criar um Repositório Novo no GitHub (`POST /user/repos`) e Publicar o Projeto Local** | ✅ |
| Ler repositório GitHub existente (até 120 arquivos) + Commit atômico multi-arquivo | ✅ |
| Tools: `list_files`, `search_files`, `read_file`, `edit_file`, `write_file`, `delete_file`, `run_command` | ✅ |
| Modo Autônomo (`⚡ Auto`) ou Revisão por Diff (`🔒 Diff`) | ✅ |
| Comandos do terminal: `pwd`, `ls`, `cat`, `grep`, `find`, `head`, `tail`, `wc`, `tree` | ✅ |
| Auto-Update via GitHub Releases (`HROSONE/Dev`) | ✅ |

## Como o agente trabalha (estilo Claude Code)

- **Streaming real** em todos os provedores (Gemini `streamGenerateContent`, OpenRouter e Qwen/llama-server via SSE): o pensamento do modelo (thought summaries, `reasoning`, `<think>`) e o texto aparecem token a token no chat.
- **Linha do tempo ao vivo** em cada resposta: pensamento → narração ("Vou ler…") → cards `Read(…)`, `Edit(…)`, `Create(…)` com spinner enquanto rodam, e "Preparando write_file → index.html · 2.3k caracteres" enquanto o modelo ainda escreve a chamada.
- **Um único loop agêntico** para qualquer pedido: o modelo decide se lê, edita ou só responde. Leitura é sempre permitida; escrita só no modo Build.
- **Erros não abortam a tarefa**: arquivo inexistente, `old_text` não encontrado, recusa do usuário ou bloqueio de política voltam ao modelo como `<tool_result status="erro">` para ele se corrigir.
- **Eficiência com arquivos**: o contexto inicial traz a árvore + mapa de símbolos do workspace (e o conteúdo, se for pequeno); o prompt prioriza editar arquivos existentes; sobrescrever um arquivo sem lê-lo gera aviso ao modelo.
- **Diff real** (antes/depois) em cada escrita, com selo NOVO/EDITADO e `+N −M`, e um resumo por resposta: arquivos novos vs. editados, lidos, linhas, rodadas e barra de "reuso de arquivos".
- Conversa multi-turn de verdade, histórico compactado, retry automático em 429/5xx, botão Parar cancela a requisição HTTP na hora.

## Testes

```bash
gradle testDebugUnitTest
```

Inclui testes de parser, policy, intent, edições cirúrgicas, busca, criação de projetos locais e workspace.
