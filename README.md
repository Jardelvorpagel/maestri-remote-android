# Maestri Remote para Android

Cliente Android nativo em Kotlin e Jetpack Compose para o protocolo Maestri Wire v1. Conecta-se a um host Maestri pareado e oferece acesso aos seus workspaces, canvas, terminais e recursos disponíveis no host.

## Funcionalidades

- Pareamento com confirmação da chave do servidor; dados de sessão persistidos localmente, com token protegido pelo Android Keystore.
- Lista de workspaces, feed ao vivo, floors e notificações.
- Canvas com navegação, seleção e operações de edição; notas, fichários e visualização de portais. As ações dependem das capacidades e permissões do host.
- Terminal ao vivo com xterm.js local, teclas de controle, ajuste de geometria e chat. O terminal é uma sessão real: entrada e teclas de controle afetam o processo no host.
- Integração de chat com Pi Relay, incluindo composição, anexos e aprovações quando disponíveis.
- Configurações, bloqueio do app, uso, rotinas e partituras. A disponibilidade varia conforme o servidor e a configuração.
- Verificação de atualizações por GitHub Releases, com conferência de SHA-256 antes da instalação.

Esta lista descreve funcionalidades presentes no código, não uma garantia de paridade completa com o iOS ou de que todos os fluxos foram verificados em aparelho. Consulte [PROGRESS.md](PROGRESS.md) para histórico e resultados de QA; entradas antigas são registros de época.

## Arquitetura

- `app/src/main/java/app/maestri/remote/`: aplicação, sessão e navegação.
- `app/src/main/java/app/maestri/wire/`: transporte Wire, modelos, APIs e sockets.
- `app/src/main/java/app/maestri/store/`: pareamento e preferências por servidor ou workspace.
- `app/src/main/java/app/maestri/ui/`: telas Compose, notas, configurações e tokens visuais.
- `app/src/main/java/app/maestri/live/`: cliente Pi Relay e componentes de conversa.
- `app/src/main/java/app/maestri/update/`: descoberta e verificação de atualizações.
- `app/src/main/assets/terminal/`: xterm.js e fit addon locais, usados pelo WebView; o token não é entregue à página.

O transporte usa HTTPS/WSS com pin SHA-256 da chave pública do servidor, e autenticação Bearer nas APIs. O feed deduplica mutações por época e sequência e solicita novo snapshot quando a época muda. IDs são preservados como recebidos; recursos opcionais dependem das capacidades anunciadas pelo host.

## Build e testes

Requisitos: JDK 21, Android SDK com plataforma 37 e build-tools 36.0.0. O projeto usa Gradle wrapper 9.5.1, AGP 9.1.1, `minSdk` 29 e `targetSdk` 36. Configure `JAVA_HOME` e `ANDROID_HOME` para suas instalações.

Os builds compartilham um lock com outros agentes e worktrees nesta máquina. Na raiz do checkout:

```bash
mkdir /tmp/maestri-build.lock || exit 1
trap 'rmdir /tmp/maestri-build.lock 2>/dev/null' EXIT
./gradlew :app:assembleDebug :app:testDebugUnitTest
```

O APK de debug fica em `app/build/outputs/apk/debug/app-debug.apk`. `./gate.sh` aponta para o checkout principal; em um worktree, execute Gradle na própria raiz com o mesmo lock. Antes de instalar sobre um app pareado, confira versão e assinatura: não desinstale para contornar incompatibilidade, pois isso apaga o pareamento.

Para QA, use preferencialmente o Samsung SM-A325M e identifique seu serial atual. Passe `-s <serial>` em comandos `adb`, confirme que o MD5 do APK instalado corresponde ao build e registre evidências. Não envie entrada a terminais de produção nem altere workspaces ou notas de produção; testes de escrita precisam de um workspace descartável, removido e verificado ao final. As regras completas estão em [AGENTS.md](AGENTS.md).

## Release

O push para `main` no remoto `github` dispara `.github/workflows/release.yml`: a CI deriva versão do histórico, assina o APK e publica o APK e `latest.json` em GitHub Releases. O remoto `origin` aponta para um caminho local e não publica no GitHub. Commits e pushes exigem solicitação explícita.
