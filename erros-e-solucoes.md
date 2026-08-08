# Mupen64Plus AE Rollback — erros e soluções

**Autor:** Manus AI
**Data da atualização:** 8 de agosto de 2026
**Repositório:** [contacontada/mupen64plus-ae-rollback][1]

## Objetivo e escopo

Este relatório registra os problemas encontrados ao integrar o pacote `mupen64plus-ae-rollback-fixed_patch8.tar.gz`, atualizar o repositório público e preparar a compilação do APK pelo GitHub Actions. O código foi integrado na raiz do repositório, o README foi revisado, o workflow foi corrigido e as verificações estruturais foram executadas antes do envio.

A verificação local `./verify_build.sh` terminou com **sucesso e zero avisos** após a correção da checagem do Manifesto. O build local completo não pôde prosseguir porque o sandbox não contém Android SDK; por isso, o critério definitivo foi o GitHub Actions, que concluiu Build e Release com sucesso no segundo run.

| Item | Resultado final |
|---|---|
| Pacote recebido | Integrado na raiz do repositório |
| Verificação estrutural | Aprovada, sem erros e sem avisos |
| Build local | Bloqueado por ausência de Android SDK no ambiente local |
| Build no GitHub Actions | **Sucesso** |
| Release no GitHub Actions | **Sucesso** após corrigir a permissão do token |
| Artifact final | [`mupen64plus-ae-main-1328e29`][5] |
| APK | `Mupen64PlusAE-release.apk` |

## Erros de compilação e soluções aplicadas

### 1. Namespace ausente no Gradle

Versões recentes do Android Gradle Plugin exigem que o namespace do módulo seja declarado no `build.gradle`. O módulo de rollback recebeu o namespace `paulscode.mupen64plusae.rollback`, evitando a falha de configuração `Namespace not specified`.

### 2. Dependência nativa não encontrada pelo ndk-build

O módulo de rollback dependia do `mupen64plus-core`, mas a execução isolada do `ndk-build` não encontrava o módulo nativo. As inclusões que causavam conflito de caminhos foram removidas, e o módulo foi configurado com `APP_ALLOW_MISSING_DEPS=true`; o vínculo entre bibliotecas permanece declarado na configuração Gradle.

### 3. RTTI desabilitado no código C++

O código GekkoNet e o bridge JNI usam `typeid` e `dynamic_cast`, que exigem RTTI. A flag `-frtti` foi adicionada aos módulos nativos `gekkonet` e `mupen64plus-rollback` no `Android.mk`.

### 4. Macros de log declaradas depois do uso

As macros `LOGI`, `LOGE` e `LOGD` eram usadas antes de sua declaração em `rollback_jni.cpp`. As definições foram movidas para o início do arquivo, antes das funções que as utilizam.

### 5. Caminho incorreto dos headers do core

O include `api/m64p_types.h` não era localizado porque o caminho relativo partia de `mupen64plus-rollback/jni/`. O `LOCAL_C_INCLUDES` foi corrigido para alcançar `../../mupen64plus-core/upstream/src`.

### 6. Símbolo ausente no script de exportação

O símbolo `set_pif_sync_callback` foi adicionado à API do core, mas não estava listado em `api_export.ver`. A entrada foi adicionada ao bloco `global`, antes de `local: *;`, corrigindo o erro de linkedição.

### 7. Bibliotecas nativas ignoradas pelo Git

Regras genéricas do `.gitignore` ignoravam arquivos `.so` e `.a` necessários ao build. Os artefatos nativos necessários foram incluídos no controle de versão quando aplicável, sem inserir credenciais ou configurações específicas de uma máquina.

### 8. Duplicidades e incompatibilidades Java/Android do módulo rollback

Durante as tentativas anteriores de integração foram corrigidas dependências JNA ausentes, a interface local usada para `CoreDoCommand`, constantes inválidas de `InputType`, a duplicidade de `libmupen64plus-core.so`, conflitos de Manifesto e a classe `CoreLibrary` duplicada no R8. A interface do rollback foi renomeada para `RollbackCoreLibrary`, as dependências foram declaradas no módulo correto, as activities passaram a ser proprietárias do Manifesto do rollback e a biblioteca nativa duplicada foi excluída do AAR do módulo.

## Erros encontrados nesta execução

### 9. Verificador apontava o Manifesto errado

O script `verify_build.sh` procurava `RollbackNetplay` no Manifesto do módulo `app`, embora a Activity esteja declarada em `mupen64plus-rollback/src/main/AndroidManifest.xml`. Isso gerava um aviso enganoso mesmo com o módulo corretamente configurado.

**Solução:** a checagem foi alterada para o Manifesto do módulo de rollback. O resultado posterior passou sem avisos.

### 10. Android SDK ausente no ambiente local

O comando `./gradlew assemble` parou antes da compilação com:

```text
SDK location not found. Define a valid SDK location with an ANDROID_HOME environment variable or by setting the sdk.dir path in local.properties.
```

**Solução:** não foi criado `local.properties`, pois esse arquivo contém um caminho específico da máquina e não deve ser versionado. O workflow usa `android-actions/setup-android@v3`, configura o SDK no runner e exporta `ANDROID_SDK_ROOT`. Para reproduzir localmente, é necessário instalar o Android SDK/NDK e definir `ANDROID_HOME` ou criar um `local.properties` não versionado.

### 11. Etapa de Release condicionada à branch incorreta

O repositório utiliza `main`, mas o workflow tinha `if: github.ref == 'refs/heads/master'`. Essa condição impediria a etapa opcional de Release de executar após pushes em `main`, embora o upload do Artifact da etapa de Build continuasse disponível.

**Solução:** a condição foi atualizada para `if: github.ref == 'refs/heads/main'`.

### 12. Token do GitHub Actions sem permissão para atualizar a tag

No primeiro run após a integração, o job `Build` compilou e publicou o APK, mas o job `Release` falhou ao executar `git push -f origin Pre-release`:

```text
remote: Permission to contacontada/mupen64plus-ae-rollback.git denied to github-actions[bot].
fatal: unable to access 'https://github.com/contacontada/mupen64plus-ae-rollback/': The requested URL returned error: 403
```

**Solução:** foi adicionada a permissão explícita `contents: write` ao workflow. O segundo run confirmou a correção: tanto Build quanto Release terminaram com sucesso.

## Validações executadas

O verificador estrutural confirmou a presença do módulo de rollback, do bridge JNI e das fontes GekkoNet, conferiu a correspondência entre métodos nativos Java e exports JNI, verificou os comandos de rollback no core, a inclusão do módulo no Gradle, a dependência da aplicação e a Activity no Manifesto correto.

Também foram executados `bash -n` nos scripts de build e verificação, `git diff --check` e uma varredura dos arquivos versionáveis para garantir que nenhum token GitHub foi inserido no repositório. Os avisos de conversão de finais de linha em arquivos Visual Studio foram mantidos como avisos de normalização, não como erros de build.

O build local foi bloqueado exclusivamente pela ausência de Android SDK no sandbox. No GitHub Actions, o runner configurou Android SDK/Java, compilou o APK e publicou o Artifact final.

## Resultados dos runs do GitHub Actions

| Run | Commit | Build | Release | Resultado |
|---:|---|---|---|---|
| [31274026784][3] | `d1c52f6` | Sucesso | Falha HTTP 403 | Corrigida permissão `contents: write` |
| [31274731391][4] | `1328e29` | **Sucesso** | **Sucesso** | Execução final aprovada |

O Artifact final foi publicado com o nome `mupen64plus-ae-main-1328e29`, ID `9026835826` e tamanho de 28.861.758 bytes no arquivo compactado do Artifact. A Release `Pre-release` também foi atualizada pelo workflow e contém o pacote `mupen64plus-ae-main.zip`.

> Resultado final: o projeto foi enviado para `main`, o APK foi compilado automaticamente e a etapa de Release foi concluída após a correção da permissão de escrita do GitHub Actions.

## Referências

[1]: https://github.com/contacontada/mupen64plus-ae-rollback "Repositório público Mupen64Plus AE Rollback"

[2]: https://github.com/contacontada/mupen64plus-ae-rollback/blob/main/.github/workflows/build.yml "Workflow de compilação do repositório"

[3]: https://github.com/contacontada/mupen64plus-ae-rollback/actions/runs/31274026784 "Primeiro run após a atualização do patch8"

[4]: https://github.com/contacontada/mupen64plus-ae-rollback/actions/runs/31274731391 "Run final bem-sucedido do GitHub Actions"

[5]: https://github.com/contacontada/mupen64plus-ae-rollback/actions/runs/31274731391/artifacts/9026835826 "Artifact final do APK"

[6]: https://github.com/contacontada/mupen64plus-ae-rollback/releases/tag/Pre-release "Release Pre-release com pacote compilado"
