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

## Testes

```bash
gradle testDebugUnitTest
```

Inclui testes de parser, policy, intent, edições cirúrgicas, busca, criação de projetos locais e workspace.
