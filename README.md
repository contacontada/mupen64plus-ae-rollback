# Mupen64Plus-AE Rollback

Este repositório contém uma versão do **Mupen64Plus-AE** com suporte a rollback netplay, integração Android/JNI e as correções do **Patch 27**. O projeto é compilado automaticamente pelo GitHub Actions para gerar o APK de release.

## Estado do projeto

| Item | Link |
|---|---|
| Repositório | [contacontada/mupen64plus-ae-rollback](https://github.com/contacontada/mupen64plus-ae-rollback) |
| Workflow de compilação | [Mupen64Plus-AE — GitHub Actions](https://github.com/contacontada/mupen64plus-ae-rollback/actions/workflows/build.yml) |
| Execuções e Artifacts | [Artifacts das execuções](https://github.com/contacontada/mupen64plus-ae-rollback/actions) |
| Release prévia | [Pre-release](https://github.com/contacontada/mupen64plus-ae-rollback/releases/tag/Pre-release) |

O APK fica disponível como **Artifact** na execução correspondente ao commit mais recente. A release `Pre-release` também é atualizada automaticamente quando o workflow conclui a compilação na branch `main`.

## Principais componentes

O projeto inclui o core do emulador com comandos `M64CMD_ROLLBACK_*`, execução determinística, callbacks de input por frame e suporte a dynarec rollback-aware para ARM, ARM64, x86 e x86_64. Também inclui a biblioteca GekkoNet, o módulo Android `mupen64plus-rollback`, integração de lobby RMG-K, JNI de rollback, diagnóstico de falhas e registro de depuração.

O Patch 27 incorpora as correções presentes no arquivo `mupen64plus-ae-rollback-fixed_patch27.tar.gz`, incluindo ajustes no fluxo nativo de execução, gerenciamento de estados de rollback, callbacks não recursivos e atualizações da interface e dos recursos Android. Para detalhes técnicos, consulte [ROLLBACK_NETCODE_README.md](ROLLBACK_NETCODE_README.md) e [BUILD_GUIDE.md](BUILD_GUIDE.md).

## Compilação local

É necessário instalar o Android Studio, o Android SDK, o NDK compatível com os arquivos Gradle e o Java 21. No Linux ou macOS, execute:

```bash
chmod +x gradlew build_rollback.sh verify_build.sh
./verify_build.sh
./gradlew assemble
```

O APK de release é gerado em `app/build/outputs/apk/release/Mupen64PlusAE-release.apk`. Para abrir o projeto no Android Studio, selecione a pasta raiz deste repositório e use **Build > Make Project**.

No Windows, instale também Git, Python, awk e o Microsoft Visual C++ Redistributable exigido pelas ferramentas nativas. O workflow restaura automaticamente as permissões dos scripts no ambiente do GitHub Actions.

## Estrutura

| Diretório | Função |
|---|---|
| `app` | Aplicativo Android e interface do emulador. |
| `mupen64plus-rollback` | Rollback netcode, lobby, Java, JNI e diagnóstico. |
| `mupen64plus-core` | Core do emulador com suporte a rollback. |
| `ae-bridge` e `miniupnp-bridge` | Módulos de integração e bridge. |
| `mupen64plus-*` | Plugins de áudio, vídeo, input e RSP. |
| `ndkLibs` | Bibliotecas nativas de terceiros. |
| `.github/workflows/build.yml` | Compilação, upload do Artifact e atualização da `Pre-release`. |

## Licença

Consulte [gpl-license](gpl-license) para os termos da GPL e das demais licenças dos componentes incluídos. As licenças de terceiros devem ser respeitadas conforme os arquivos distribuídos em cada módulo.
