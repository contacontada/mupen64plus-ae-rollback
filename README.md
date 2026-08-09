# Mupen64Plus-AE Rollback (Patch 12)

Este repositório é um port do **rollback netcode do RMG-K** para o [Mupen64Plus AE](https://github.com/mupen64plus-ae/mupen64plus-ae) (Android Edition). Ele permite jogar online com rollback netcode, com **compatibilidade de lobby com jogadores do RMG-K no PC**.

Mupen64Plus, Android Edition (AE) é uma interface Android para o emulador Mupen64Plus. Para suporte e discussões gerais do emulador, visite [o fórum oficial](http://www.paulscode.com/forum/index.php).

## Nightly Builds

### Baixe as compilações mais recentes pela integração contínua (GitHub Actions):

| Nome           | Status                            | Arquivo                                    |
|----------------|-----------------------------------|--------------------------------------------|
| Mupen64Plus-AE Rollback | [![Build Status][Build]][Actions] | [![Emulator][Download]][apk]  |

[Actions]: https://github.com/contacontada/mupen64plus-ae-rollback/actions/workflows/build.yml
[Build]: https://github.com/contacontada/mupen64plus-ae-rollback/actions/workflows/build.yml/badge.svg
[Download]: https://img.shields.io/badge/Download-blue
[apk]: https://github.com/contacontada/mupen64plus-ae-rollback/releases/download/Pre-release/mupen64plus-ae-main.zip

> O APK de cada push é enviado como **artifact do GitHub Actions**. Abra a aba
> [Actions](https://github.com/contacontada/mupen64plus-ae-rollback/actions) deste repositório,
> selecione a execução (run) correspondente e baixe o artifact `mupen64plus-ae-<branch>-<sha>`
> para obter o `Mupen64PlusAE-release.apk`.

## O que este repositório inclui

- **Rollback netcode (nativo)**: `mupen64plus-core` com suporte a rollback (comandos `M64CMD_ROLLBACK_*`), modo de emulação determinística, callbacks de input por frame e dynarec rollback-aware (ARM/ARM64/x86/x86_64)
- **GekkoNet**: biblioteca de rollback netcode com gerenciamento de sessão, sincronização de input entre peers e save/load de estado
- **Compatibilidade de lobby com RMG-K**: protocolo WebSocket do lobby, anchor UDP para NAT traversal, NAT punch-through, criação/entrada em salas, quick match e chat (módulo `mupen64plus-rollback`)
- **Interface Android**: `RollbackNetplayActivity` (UI de lobby, salas e status da partida) e `RollbackNetplayService` (serviço Android que gerencia a conexão e o ciclo de vida da partida)

Para detalhes completos da arquitetura e da compatibilidade com o RMG-K, veja [ROLLBACK_NETCODE_README.md](ROLLBACK_NETCODE_README.md).

## Instruções de Build

1. Instale os pré-requisitos
   - [Android Studio](https://developer.android.com/studio/index.html)
   - Durante a instalação, garanta os SDK e NDK mais recentes (o projeto usa compileSdk/NDK definidos nos `build.gradle` dos módulos)
   - No Windows, instale Git, Python, awk e o Microsoft Visual C++ Redistributable necessário
2. Clone este repositório
   - `git clone https://github.com/contacontada/mupen64plus-ae-rollback.git`
3. Abra o projeto no Android Studio
4. Compile e execute o aplicativo
   - Selecione Build --> Make Project para compilar
   - Selecione Run --> Run app para executar

O GitHub Actions deste repositório também compila o APK automaticamente a cada push
(`.github/workflows/build.yml`).

## Estrutura

| Diretório | Descrição |
|-----------|-----------|
| `app` | Aplicativo Android principal (UI do emulador) |
| `mupen64plus-rollback` | Módulo de rollback netcode e lobby RMG-K (Java + JNI) |
| `mupen64plus-core` | Core do emulador com suporte a rollback |
| `ae-bridge`, `miniupnp-bridge` | Módulos de bridge |
| `mupen64plus-*` | Módulos de plugins (áudio, vídeo, input, RSP) |
| `ndkLibs` | Bibliotecas nativas de terceiros |
