# M64PLUS R — Patch 50

Este repositório é um port do **rollback netcode do RMG-K** para o [Mupen64Plus AE](https://github.com/mupen64plus-ae/mupen64plus-ae) (Android Edition). Ele permite partidas online com rollback netcode e compatibilidade de lobby com jogadores do RMG-K no PC.

> **Versão atual:** Patch 50. Esta atualização mantém as correções dos Patches 38, 39, 41, 42, 43, 44, 46, 47, 48 e 49 e corrige o ciclo de rede do rollback para enviar o input local antes do primeiro poll e continuar processando a rede dentro do loop de espera por eventos. A correção de concorrência entre o loop normal do `CoreService` e a execução controlada pelo rollback permanece ativa. O fluxo de inicialização continua usando o lançamento da `GameActivity` por nome de componente, compatível com a separação entre os módulos `app` e `mupen64plus-rollback`.

Mupen64Plus, Android Edition (AE) é uma interface Android para o emulador Mupen64Plus. Para suporte e discussões gerais do emulador, consulte o [fórum oficial](http://www.paulscode.com/forum/index.php).

## Download do APK

O APK é compilado automaticamente pelo [GitHub Actions](https://github.com/contacontada/mupen64plus-ae-rollback/actions/workflows/build.yml) a cada push na branch `main`. A execução publica o APK e o arquivo-fonte do projeto como Artifacts e também atualiza a Release prévia `Pre-release`.

| Projeto | Status da compilação | Download |
|---|---|---|
| M64PLUS R | [![Build Status][Build]][Actions] | [![Download][Download]][apk] |

O APK `M64PLUS.R.apk` e o arquivo `mupen64plus-ae-rollback-patch42.tar.gz` ficam disponíveis na [Release `Pre-release`](https://github.com/contacontada/mupen64plus-ae-rollback/releases/tag/Pre-release) depois que o workflow termina. Os Artifacts gerados podem ser baixados na [execução do GitHub Actions](https://github.com/contacontada/mupen64plus-ae-rollback/actions) correspondente ao commit mais recente.

[Actions]: https://github.com/contacontada/mupen64plus-ae-rollback/actions/workflows/build.yml
[Build]: https://github.com/contacontada/mupen64plus-ae-rollback/actions/workflows/build.yml/badge.svg
[Download]: https://img.shields.io/badge/Download-blue
[apk]: https://github.com/contacontada/mupen64plus-ae-rollback/releases/download/Pre-release/M64PLUS.R.apk

## O que este repositório inclui

O projeto inclui suporte nativo a rollback no `mupen64plus-core`, com os comandos `M64CMD_ROLLBACK_*`, modo de emulação determinística, callbacks de input por frame e dynarec rollback-aware para ARM, ARM64, x86 e x86_64. Também inclui a biblioteca GekkoNet, o módulo `mupen64plus-rollback` com integração de lobby RMG-K e a interface Android composta pela `RollbackNetplayActivity` e pelo `RollbackNetplayService`.

O Patch 14 adiciona o `RollbackCrashLogger`, que grava exceções não tratadas em `rollback_crash.txt` no diretório de arquivos externos do aplicativo. O Patch 15 acrescenta broadcasts internos para sincronizar a inicialização do core entre processos, notificar falhas de startup e encerrar a GameActivity quando a partida termina. O Patch 16 adiciona o `RollbackDebugLog`, que grava `rollback_debug.log` com limite de 512 KB, além do item **Debug Log** na tela de rollback para visualizar, copiar e limpar o diagnóstico. O Patch 18 adiciona `RollbackGameLaunchKeys`, um conjunto de chaves String fixas compartilhadas pelo serviço e pela GameActivity, e corrige a renderização dos itens do menu lateral. O Patch 19 coloca `RollbackNetplayService` e `RollbackNetplayActivity` no processo Android separado `:EmulationProcess`. O Patch 20 corrige os ordinais `M64CMD_ROLLBACK_*` em `RollbackJnaTypes` e registra no `RollbackDebugLog` as chamadas de `Native.load` e `CoreDoCommand`. O Patch 21 garante que os caminhos JNI de lobby também chamem `coreRollbackSetDeterministic(true)` antes de configurar os jogadores e remove a configuração duplicada no serviço Java. O Patch 22 adiciona mensagens nativas detalhadas para falhas de lobby e P2P, encaminhando a causa real ao diagnóstico e à tela de erro, e limpa erros antigos no início de cada sessão. O Patch 23 remove endereços IP e portas da interface da sala, exibindo apenas o estado de conexão ao usuário. O Patch 32 libera a âncora UDP Java antes da sessão nativa, renomeia o aplicativo para M64PLUS R e substitui os ícones do launcher pela imagem do controle fornecida. O Patch 33 corrige a passagem de `rollbackNumPlayers` da `GameActivity` até o `CoreService`, incluindo o extra de `Intent` e o construtor de `GamePrefs`, para que o processo separado preserve a quantidade de controles exigida pela partida. O Patch 34 adiciona configurações explícitas de mudança de configuração à `GameActivity`, evitando recriações desnecessárias durante rotação, mudanças de densidade, tamanho de tela, modo de UI e navegação. O Patch 35 move a entrada no modo imersivo para antes da construção do overlay de toque, garantindo que o primeiro cálculo de layout use as dimensões finais da tela. O Patch 37 antecipa a aplicação da orientação configurada para que o primeiro layout já use a orientação final do jogo e acrescenta logs de diagnóstico ao ciclo de vida do serviço de rollback. O Patch 38 promove o `NetplayOverlayService` a foreground service imediatamente ao iniciar, declara o subtipo `specialUse` e a permissão correspondente para evitar o encerramento forçado do processo; também adiciona `nativeSetCrashLogPath()` e um marcador nativo em `rollback_native_crash.txt` para registrar sinais como `SIGSEGV` e `SIGABRT` antes do tombstone, e mantém o lançamento da `GameActivity` por nome de componente, compatível com a separação entre os módulos `app` e `mupen64plus-rollback`. O Patch 39 inicia explicitamente o `RollbackNetplayService` como foreground service a partir da Activity de lobby, acompanha se uma partida realmente começou e somente o interrompe ao destruir a Activity quando nenhuma partida foi iniciada; ao encerrar o emulador em modo rollback, a `GameActivity` também interrompe explicitamente o serviço, evitando que ele permaneça ativo após o fim da partida. O Patch 41 adiciona `nativeSetDebugLogPath()` e instrumenta `rollbackInputCallback()` e `submitLocalInput()` para registrar no `rollback_debug.log` falhas de sessão, parâmetros, buffers, contadores de chamadas e amostras de input não nulas, mantendo o diagnóstico acessível pela interface existente. O Patch 42 protege com `std::mutex` a leitura e a escrita de `g_GekkoLatchedInput` e `g_GekkoHasLatchedInput`, estabelecendo sincronização entre as threads de rollback e emulação e evitando que o callback leia estado antigo ou permaneça sem visualizar o input recebido. O Patch 43 adiciona contadores e registros de diagnóstico em torno de `coreRollbackRunFrame()` para distinguir callbacks acionados durante frames ocultos e visíveis, preservando o lançamento da `GameActivity` por nome de componente no `RollbackNetplayService` para manter a compatibilidade entre os módulos e o fluxo de inicialização do lobby. O Patch 44 registra o marcador `BUILD MARKER: nativeExecute() ENTER (patch44)` no diagnóstico nativo sempre que `nativeExecute()` é chamado, facilitando a detecção de bibliotecas nativas antigas ou de builds incorretos. O Patch 46 amplia esse diagnóstico com contadores para callbacks, eventos de avanço e frames ocultos/visíveis, registra amostras de `GekkoSaveEvent` e `GekkoAdvanceEvent` e emite um resumo periódico dos principais contadores, mantendo o comportamento funcional do rollback e o lançamento compatível da `GameActivity`. O Patch 47 impede que `CoreService.emuStart()` execute o loop normal quando há jogadores rollback, aguardando a sinalização de encerramento/reinício para que `RollbackNetplayService` seja o único responsável por dirigir os frames da sessão; também mantém a quantidade de jogadores no estado do serviço para essa decisão. O Patch 48 adiciona ao resumo periódico do diagnóstico nativo os contadores de `sendto()` e `recvfrom()`, erros de envio e volume de bytes UDP, permitindo distinguir ausência de tráfego, falhas de socket e sessões que recebem ou enviam dados sem avançar frames. O Patch 49 acrescenta o contador `bad_socket_calls` para chamadas ao adaptador quando o socket UDP ainda é inválido e passa a registrar no arquivo de diagnóstico as falhas de criação/bind e o sucesso do bind, incluindo `errno` e a porta envolvida; isso torna visíveis colisões de porta e inicializações incompletas que antes podiam resultar em silêncio no transporte. O Patch 50 corrige um deadlock de rede em `gekkoTick()`: `submitLocalInput()` agora ocorre antes do primeiro `gekko_network_poll()`, e o polling continua dentro do loop que aguarda eventos, permitindo que inputs, retransmissões e dados recebidos sejam processados sem depender do retorno do próprio loop.

Para detalhes da arquitetura e da compatibilidade com o RMG-K, consulte [ROLLBACK_NETCODE_README.md](ROLLBACK_NETCODE_README.md).

## Instruções de build

1. Instale o [Android Studio](https://developer.android.com/studio/index.html) e os componentes de SDK e NDK compatíveis com os arquivos `build.gradle` do projeto.
2. Clone o repositório:

   ```bash
   git clone https://github.com/contacontada/mupen64plus-ae-rollback.git
   cd mupen64plus-ae-rollback
   ```

3. No Linux ou macOS, torne o wrapper e o script nativo executáveis e compile o projeto:

   ```bash
   chmod +x gradlew build_rollback.sh
   ./gradlew assemble
   ```

4. Para compilar pelo Android Studio, abra a pasta do projeto, selecione **Build > Make Project** e depois **Run > Run app**.

No Windows, instale também Git, Python, awk e o Microsoft Visual C++ Redistributable exigido pelas ferramentas nativas. O workflow `.github/workflows/build.yml` restaura as permissões dos scripts automaticamente no ambiente do GitHub Actions.

## Estrutura

| Diretório | Descrição |
|---|---|
| `app` | Aplicativo Android principal e interface do emulador. |
| `mupen64plus-rollback` | Módulo de rollback netcode, logger de crash e lobby RMG-K, com Java e JNI. |
| `mupen64plus-core` | Core do emulador com suporte a rollback. |
| `ae-bridge` e `miniupnp-bridge` | Módulos de integração e bridge. |
| `mupen64plus-*` | Plugins de áudio, vídeo, input e RSP. |
| `ndkLibs` | Bibliotecas nativas de terceiros. |
| `.github/workflows/build.yml` | Compilação, upload dos Artifacts e atualização da Release `Pre-release`. |

## Licença

Consulte o arquivo [gpl-license](gpl-license) para os termos de licença do projeto e de seus componentes licenciados sob GPL.
