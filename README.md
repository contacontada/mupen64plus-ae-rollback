# Mupen64Plus-AE Rollback — Patch 14

Este repositório é um port do **rollback netcode do RMG-K** para o [Mupen64Plus AE](https://github.com/mupen64plus-ae/mupen64plus-ae) (Android Edition). Ele permite partidas online com rollback netcode e compatibilidade de lobby com jogadores do RMG-K no PC.

> **Versão atual:** Patch 14. Esta atualização inclui diagnóstico persistente de crashes, tratamento explícito de falhas na inicialização do core e as correções de build e publicação automatizada introduzidas nos patches anteriores.

Mupen64Plus, Android Edition (AE) é uma interface Android para o emulador Mupen64Plus. Para suporte e discussões gerais do emulador, consulte o [fórum oficial](http://www.paulscode.com/forum/index.php).

## Download do APK

O APK é compilado automaticamente pelo [GitHub Actions](https://github.com/contacontada/mupen64plus-ae-rollback/actions/workflows/build.yml) a cada push na branch `main`. A execução publica o APK como Artifact e também atualiza a Release prévia `Pre-release` com o APK e o arquivo de projeto do Patch 14.

| Projeto | Status da compilação | Download |
|---|---|---|
| Mupen64Plus-AE Rollback | [![Build Status][Build]][Actions] | [![Download][Download]][apk] |

O APK e o arquivo `mupen64plus-ae-rollback-patch14.tar.gz` estão disponíveis na [Release `Pre-release`](https://github.com/contacontada/mupen64plus-ae-rollback/releases/tag/Pre-release). Os artefatos gerados podem ser baixados na [execução do GitHub Actions](https://github.com/contacontada/mupen64plus-ae-rollback/actions) correspondente ao commit mais recente.

[Actions]: https://github.com/contacontada/mupen64plus-ae-rollback/actions/workflows/build.yml
[Build]: https://github.com/contacontada/mupen64plus-ae-rollback/actions/workflows/build.yml/badge.svg
[Download]: https://img.shields.io/badge/Download-blue
[apk]: https://github.com/contacontada/mupen64plus-ae-rollback/releases/download/Pre-release/Mupen64PlusAE-Rollback.apk

## O que este repositório inclui

O projeto inclui suporte nativo a rollback no `mupen64plus-core`, com os comandos `M64CMD_ROLLBACK_*`, modo de emulação determinística, callbacks de input por frame e dynarec rollback-aware para ARM, ARM64, x86 e x86_64. Também inclui a biblioteca GekkoNet, o módulo `mupen64plus-rollback` com integração de lobby RMG-K e a interface Android composta pela `RollbackNetplayActivity` e pelo `RollbackNetplayService`.

O Patch 14 adiciona o `RollbackCrashLogger`, que grava exceções não tratadas em `rollback_crash.txt` no diretório de arquivos externos do aplicativo. Também registra a razão quando uma sessão de rollback não possui caminho de ROM ou MD5 válidos, evitando uma inicialização silenciosa do core.

Para detalhes da arquitetura e da compatibilidade com o RMG-K, consulte [ROLLBACK_NETCODE_README.md](ROLLBACK_NETCODE_README.md).

## Instruções de build

1. Instale o [Android Studio](https://developer.android.com/studio/index.html) e os componentes de SDK e NDK compatíveis com os arquivos `build.gradle` do projeto.
2. Clone o repositório:

   ```bash
   git clone https://github.com/contacontada/mupen64plus-ae-rollback.git
   cd mupen64plus-ae-rollback
   ```

3. No Linux ou macOS, torne o wrapper executável e compile o projeto:

   ```bash
   chmod +x gradlew build_rollback.sh verify_build.sh
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
| `.github/workflows/build.yml` | Compilação, upload do Artifact e atualização da Release `Pre-release`. |

## Licença

Consulte o arquivo [gpl-license](gpl-license) para os termos de licença do projeto e de seus componentes licenciados sob GPL.
