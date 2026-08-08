# Mupen64Plus-AE Rollback

Versão do **Mupen64Plus-AE** para Android com suporte experimental a **rollback netplay**. Esta atualização incorpora o pacote `mupen64plus-ae-rollback-fixed_patch5.tar.gz` enviado para o projeto.

> O rollback netplay é experimental. O comportamento pode variar conforme o dispositivo, a versão do Android, a ABI e a configuração da partida em rede.

## Download do APK

O APK desta versão foi compilado com sucesso pelo GitHub Actions.

**[Baixar diretamente o APK do patch5 — Mupen64PlusAE-release.apk](https://github.com/contacontada/mupen64plus-ae-rollback/actions/runs/31234295771/artifacts/9015012611)**

O artifact `mupen64plus-ae-main-630dcac` foi gerado pela [execução 31234295771](https://github.com/contacontada/mupen64plus-ae-rollback/actions/runs/31234295771). Para consultar outras versões, acesse as [execuções do GitHub Actions](https://github.com/contacontada/mupen64plus-ae-rollback/actions). O arquivo gerado é `Mupen64PlusAE-release.apk`.

## Alterações do patch5

O patch5 atualiza a integração do módulo de rollback, incluindo alterações no build Gradle, no `Android.mk`, no manifesto Android, na ponte nativa, na biblioteca JNA e nos serviços de netplay. Também adiciona o `RollbackCrashLogger` e os estilos Android necessários ao módulo.

## Componentes principais

| Componente | Descrição |
|---|---|
| `app` | Aplicativo Android principal e integração com o emulador. |
| `mupen64plus-core` | Núcleo nativo do Mupen64Plus. |
| `mupen64plus-rollback` | Activities, serviços, ponte JNA e lógica de rollback netplay. |
| `mupen64plus-video-gliden64` | Plugin de vídeo. |
| `miniupnp-bridge` | Integração de conectividade e descoberta de rede. |
| `.github/workflows/build.yml` | Compilação automática e upload do APK. |

## Integração de rollback

O módulo `mupen64plus-rollback` fornece a infraestrutura experimental para partidas em rede com controle de frames, callbacks nativos, recuperação de estado e comunicação entre os jogadores. A integração Java–nativa é realizada pela `RollbackCoreBridge` usando a interface `RollbackCoreLibrary`.

Entre as classes principais estão `RollbackNetplayActivity`, `RollbackSettingsActivity`, `RollbackNetplayService`, `NetplayOverlayService` e `RollbackCrashLogger`.

## Compilação local

Instale o [Android Studio](https://developer.android.com/studio), o Android SDK e o Android NDK `26.1.10909125`. Depois clone o repositório:

```bash
git clone https://github.com/contacontada/mupen64plus-ae-rollback.git
cd mupen64plus-ae-rollback
./gradlew assembleRelease
```

O APK release será gerado normalmente em `app/build/outputs/apk/release/`. Para reproduzir o ambiente do workflow, mantenha o NDK `26.1.10909125` instalado.

## GitHub Actions

Cada push na branch `main` aciona o workflow [`build.yml`](.github/workflows/build.yml). Ele configura o ambiente Android, instala as dependências, compila a variante release, registra o commit e publica o APK como artifact.

A página de [execuções do GitHub Actions](https://github.com/contacontada/mupen64plus-ae-rollback/actions) permite acompanhar o build e baixar os artifacts disponíveis.

## Relato de problemas

Ao relatar uma falha, informe o modelo do dispositivo, a versão do Android, a ABI, o commit ou artifact instalado e os logs relevantes. Para problemas de netplay, inclua o servidor, o atraso configurado e a etapa em que a conexão falhou.

## Licença e origem

Este repositório é uma variante de desenvolvimento baseada no ecossistema Mupen64Plus-AE. Consulte os arquivos de licença e os avisos de copyright incluídos no código-fonte para conhecer as condições aplicáveis a cada componente. O projeto original pode ser consultado em [mupen64plus-ae/mupen64plus-ae](https://github.com/mupen64plus-ae/mupen64plus-ae).
