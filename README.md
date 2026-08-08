# Mupen64Plus-AE Rollback

Uma versão do **Mupen64Plus-AE** para Android com suporte experimental a **rollback netplay**. Este repositório contém o código-fonte atualizado a partir do pacote corrigido enviado para esta versão do projeto.

> O rollback netplay é experimental. O comportamento pode variar conforme o dispositivo, a versão do Android, a ABI e a configuração da partida em rede.

## Download do APK

O APK é compilado automaticamente pelo GitHub Actions. Para baixar o artifact mais recente, acesse:

**[Artifacts e execuções do GitHub Actions](https://github.com/contacontada/mupen64plus-ae-rollback/actions)**

Abra a execução mais recente concluída com sucesso e baixe o artifact que contém `Mupen64PlusAE-release.apk`. O workflow usado está em [` .github/workflows/build.yml`](.github/workflows/build.yml).

## Componentes principais

| Componente | Descrição |
|---|---|
| `app` | Aplicativo Android principal e integração com a interface do emulador. |
| `mupen64plus-core` | Núcleo nativo do Mupen64Plus. |
| `mupen64plus-rollback` | Activities, serviços, ponte JNA e lógica de rollback netplay. |
| `mupen64plus-video-gliden64` | Plugin de vídeo do aplicativo. |
| `miniupnp-bridge` | Integração de descoberta e conectividade de rede. |
| `.github/workflows/build.yml` | Compilação automática e publicação do APK como artifact. |

## Integração de rollback

O módulo `mupen64plus-rollback` fornece a infraestrutura experimental para partidas em rede com execução determinística, controle de frames, callbacks nativos e recuperação de estado. A classe `RollbackCoreBridge` acessa a biblioteca nativa por JNA usando a interface específica `RollbackCoreLibrary`.

As principais classes do módulo são `RollbackNetplayActivity`, `RollbackSettingsActivity`, `RollbackNetplayService`, `NetplayOverlayService` e `RollbackCoreBridge`.

## Requisitos

Para compilar localmente, instale o [Android Studio](https://developer.android.com/studio), o Android SDK e o Android NDK `26.1.10909125`. O projeto utiliza Gradle e requer uma versão de Java compatível com o Android Gradle Plugin configurado no projeto.

As dependências Java são baixadas pelo Gradle. Entre elas estão JNA, JNA Platform, OkHttp, Material Components, RecyclerView e DrawerLayout.

## Compilação local

Clone o repositório e entre na pasta do projeto:

```bash
git clone https://github.com/contacontada/mupen64plus-ae-rollback.git
cd mupen64plus-ae-rollback
```

Sincronize o projeto no Android Studio ou execute:

```bash
./gradlew assembleRelease
```

O APK release será gerado normalmente em `app/build/outputs/apk/release/`. Para reproduzir o ambiente do GitHub Actions, mantenha o NDK `26.1.10909125` instalado.

## GitHub Actions

Cada push na branch `main` aciona o workflow [`build.yml`](.github/workflows/build.yml). Ele configura o ambiente Android, instala dependências, compila o APK release e publica o resultado como artifact.

O status das execuções pode ser acompanhado na página de [GitHub Actions](https://github.com/contacontada/mupen64plus-ae-rollback/actions). O nome e a disponibilidade do artifact dependem do commit e da política de retenção do workflow.

## Correções incluídas

A árvore atual inclui as correções de integração NDK, preservação de caminhos dos makefiles, dependências JNA, constantes `InputType` compatíveis com Android, conflitos de manifesto, bibliotecas nativas duplicadas e colisões de classes durante a minificação R8.

O módulo rollback compila o core necessário para o link JNI, mas evita empacotar uma segunda cópia de `libmupen64plus-core.so`. A interface JNA do rollback usa o nome `RollbackCoreLibrary`, evitando colisão com a `CoreLibrary` do aplicativo principal.

O histórico detalhado das falhas e soluções está em [`erros-e-solucoes.md`](erros-e-solucoes.md).

## Licença e origem

Este repositório é uma variante de desenvolvimento baseada no ecossistema Mupen64Plus-AE. Consulte os arquivos de licença e os avisos de copyright incluídos no código-fonte para conhecer as condições aplicáveis a cada componente.

O projeto original pode ser consultado em [mupen64plus-ae/mupen64plus-ae](https://github.com/mupen64plus-ae/mupen64plus-ae).

## Relato de problemas

Ao relatar um problema, informe o modelo do dispositivo, a versão do Android, a ABI, o commit ou artifact instalado e os logs relevantes. Para falhas de partidas em rede, informe também o servidor, o atraso configurado e em que etapa da conexão o problema ocorre.
