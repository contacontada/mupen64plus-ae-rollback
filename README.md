# Mupen64Plus-AE Rollback

Uma versão do **Mupen64Plus-AE** para Android com suporte experimental a **rollback netplay**. O projeto combina a interface Android do emulador com o módulo nativo e as classes Java responsáveis pela execução de partidas em rede com rollback.

> Este é um projeto experimental. O comportamento pode variar conforme o dispositivo, a versão do Android, a arquitetura ABI e a configuração da partida em rede.

## Download do APK

A versão compilada mais recente está disponível como artifact do GitHub Actions:

**[Baixar Mupen64PlusAE-release.apk](https://github.com/contacontada/mupen64plus-ae-rollback/actions/runs/31222562403/artifacts/9011233122)**

A execução que gerou o APK foi concluída com sucesso:

**[GitHub Actions — execução 31222562403](https://github.com/contacontada/mupen64plus-ae-rollback/actions/runs/31222562403)**

O artifact contém o arquivo `Mupen64PlusAE-release.apk`.

## Principais componentes

| Componente | Função |
|---|---|
| `app` | Aplicativo Android principal e integração com a interface do emulador. |
| `mupen64plus-core` | Núcleo nativo do Mupen64Plus. |
| `mupen64plus-rollback` | Serviços, activities, ponte JNA e lógica específica do rollback netplay. |
| `mupen64plus-video-gliden64` | Plugin de vídeo utilizado pelo aplicativo. |
| `miniupnp-bridge` | Integração de descoberta e conectividade de rede. |
| `.github/workflows/build.yml` | Compilação automática e publicação do APK como artifact. |

## Funcionalidades de rollback

O módulo rollback fornece a infraestrutura experimental para partidas em rede com execução determinística, controle de frames, callbacks nativos e recuperação de estado durante a partida. A ponte `RollbackCoreBridge` acessa a biblioteca nativa do core por JNA e utiliza a interface `RollbackCoreLibrary` para chamar a API nativa necessária.

As telas e serviços principais do módulo são:

- `RollbackNetplayActivity`, para iniciar a interface de partida;
- `RollbackSettingsActivity`, para configurar servidor, jogador e parâmetros de rede;
- `RollbackNetplayService`, para manter a sessão de rede;
- `NetplayOverlayService`, para exibir informações da partida;
- `RollbackCoreBridge`, para integrar o código Java ao core nativo.

## Requisitos para compilação

Para compilar localmente, instale o [Android Studio](https://developer.android.com/studio), o Android SDK e o Android NDK `26.1.10909125`. O projeto utiliza Gradle e requer Java compatível com a configuração do Android Gradle Plugin.

Também são necessárias as dependências baixadas automaticamente pelo Gradle, incluindo JNA, JNA Platform, OkHttp, Material Components, RecyclerView e DrawerLayout.

## Compilação local

Clone o repositório e abra a pasta do projeto:

```bash
git clone https://github.com/contacontada/mupen64plus-ae-rollback.git
cd mupen64plus-ae-rollback
```

No Android Studio, sincronize o projeto Gradle e selecione a variante `debug` ou `release`. Pela linha de comando, o APK release pode ser gerado com:

```bash
./gradlew assembleRelease
```

O APK normalmente será criado em:

```text
app/build/outputs/apk/release/
```

Para uma compilação equivalente à utilizada no GitHub Actions, mantenha o NDK `26.1.10909125` instalado e permita que o Gradle execute o `ndk-build` dos módulos nativos.

## GitHub Actions

Cada push na branch `main` aciona o workflow [`build.yml`](.github/workflows/build.yml). O workflow configura o Android SDK, instala as dependências, compila o APK release, registra o branch e o SHA do commit e publica o resultado como artifact.

Os artifacts podem ser acessados na página de [execuções do GitHub Actions](https://github.com/contacontada/mupen64plus-ae-rollback/actions). Eles ficam disponíveis conforme a política de retenção configurada pelo GitHub Actions.

## Correções aplicadas nesta versão

A versão publicada inclui correções de integração NDK, preservação do caminho dos makefiles, dependências JNA, constantes `InputType` compatíveis com Android, conflitos de manifesto, bibliotecas nativas duplicadas e colisões de classes durante a minificação R8.

Em particular, o módulo rollback compila o core nativo necessário para o link JNI, mas exclui a cópia duplicada de `libmupen64plus-core.so` do empacotamento final. O aplicativo principal fornece uma única cópia dessa biblioteca. A interface JNA específica do rollback é `RollbackCoreLibrary`, evitando conflito com a `CoreLibrary` já existente no aplicativo.

O histórico detalhado dos erros e das soluções está disponível no [relatório de correções](https://github.com/contacontada/mupen64plus-ae-rollback/blob/main/erros-e-solucoes.md), quando esse arquivo estiver publicado na branch principal.

## Estrutura de desenvolvimento

O código do projeto está organizado em módulos Gradle independentes. Alterações no código nativo devem ser acompanhadas por uma verificação do `Android.mk` correspondente, das ABIs suportadas e da forma como a biblioteca será empacotada pelo Android Gradle Plugin. Alterações nas interfaces JNA devem manter os nomes dos símbolos compatíveis com a biblioteca nativa carregada em tempo de execução.

## Licença e origem

Este repositório é uma variante de desenvolvimento baseada no ecossistema Mupen64Plus-AE. Consulte os arquivos de licença e os avisos de copyright incluídos no código-fonte para conhecer as condições aplicáveis a cada componente.

O projeto original do Mupen64Plus-AE pode ser consultado em [mupen64plus-ae/mupen64plus-ae](https://github.com/mupen64plus-ae/mupen64plus-ae).

## Relato de problemas

Ao relatar um problema, inclua o modelo do dispositivo, a versão do Android, a ABI utilizada, a versão do APK, o commit ou artifact instalado e os logs relevantes do aplicativo. Para falhas de partidas em rede, informe também a configuração do servidor, o atraso configurado e se o erro ocorre antes ou depois da conexão dos jogadores.
