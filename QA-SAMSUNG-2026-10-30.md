# QA exploratório — Samsung, 2026-10-30

Build local em `51593e5`, incluindo as alterações locais existentes. Samsung SM-A325M, Android 13, 1080 × 2400, densidade 420 dpi. `:app:assembleDebug` e `:app:testDebugUnitTest` passaram. APK instalado por atualização, com assinatura de debug correspondente e MD5 `c8270b958f47dcd28e817d0820ea510e` igual ao APK gerado; pareamento preservado.

## Observações

1. **Contraste no canvas Stack:** títulos pretos sobre cartões escuros são difíceis de ler. Reproduzir: abrir um workspace conectado → Canvas → Stack. Evidência: `/tmp/maestri-qa-20261030/canvas.png`.
2. **Barra de ferramentas do canvas encoberta:** no modo Stack, os controles inferiores estão sob a barra de navegação do Android, com alvos parcialmente cobertos. Mesma captura; verificar também os toques após corrigir o layout.
3. **Cabeçalho do canvas Board sem espaço:** ao mudar para Board, o indicador `live` quebra verticalmente na borda direita e os controles ficam comprimidos. Evidência: `/tmp/maestri-qa-20261030/board.png`.
4. **Configurações com texto comprimido:** em Settings, `Routines` quebra no meio da palavra e `Partituras` encosta na descrição. Evidência: `/tmp/maestri-qa-20261030/settings.png`.
5. **Contraste dos botões Floors:** na lista de workspaces, os rótulos aparecem muito escuros sobre o fundo escuro. Evidência: `/tmp/maestri-qa-20261030/workspaces.png`.

As capturas e árvores de acessibilidade estão em `/tmp/maestri-qa-20261030/` e podem conter conteúdo de workspaces; não foram adicionadas ao repositório. A persistência em `/tmp` não é garantida.

## Reteste após adaptação

No mesmo Samsung, com APK MD5 `15b701ce8e5a33109a2c60669c057e32` conferido na instalação, os títulos no Stack e os botões Floors ficaram legíveis, a barra de ferramentas ficou acima da navegação do sistema e as três linhas de Settings passaram a apresentar a descrição abaixo do título. No Board, os controles agora rolam horizontalmente: um gesto revelou `Fit` e `live` sem a quebra vertical. Evidências `final-canvas.png`, `adapt-workspaces.png`, `final-settings.png` e `final-board-scrolled.png` no diretório temporário acima. O build e os testes unitários passaram novamente. Orientação paisagem, escala de fonte e tablet não foram verificados neste reteste; os controles de escrita permaneceram desabilitados neste workspace e não foram acionados.

## Cobertura e limites

- Abriu no feed restaurado com indicação `live`; navegação para Canvas, alternância Stack/Board, retorno à lista de workspaces, abertura de Settings e da lista vazia de Routines foram observados.
- Nenhuma exceção fatal apareceu nas últimas 1.000 linhas de logcat consultadas; isso não garante ausência de falhas fora dessa janela.
- Não foram exercitados entrada de terminal, envio de chat, criação ou edição no host, reconexão, pareamento inicial, atualização, biometria ou fluxos de escrita. Não houve teste de regressão dos problemas históricos de fichário. Esta é uma rodada exploratória, não um aceite completo de release.
