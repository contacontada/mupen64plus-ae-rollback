# Mupen64Plus AE Rollback — erros e soluções

**Autor:** Manus AI  
**Data da atualização:** 8 de agosto de 2026
**Repositório:** [contacontada/mupen64plus-ae-rollback][1]

## Objetivo e escopo

Este relatório registra os problemas encontrados ao integrar o pacote `mupen64plus-ae-rollback-fixed_patch8.tar.gz`, atualizar o repositório público e preparar a compilação do APK pelo GitHub Actions. O código foi integrado na raiz do repositório, o README foi revisado e as verificações estruturais foram executadas antes do envio.

A verificação local `./verify_build.sh` terminou com **sucesso e zero avisos** após a correção da checagem do Manifesto. O build local completo não pôde prosseguir porque o sandbox não contém Android SDK; a compilação definitiva será validada no workflow configurado em [`.github/workflows/build.yml`][2].

| Item | Resultado nesta etapa |
|---|---|
| Pacote recebido | Integrado na raiz do repositório |
| Verificação estrutural | Aprovada |
| Build local | Bloqueado por ausência de Android SDK no ambiente |
| Workflow | Corrigido para usar `main` na etapa de Release |
| Artifact do APK | A confirmar após o push e a execução do CI |

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

## Erros encontrados nesta execução

### 8. Verificador apontava o Manifesto errado

O script `verify_build.sh` procurava `RollbackNetplay` no Manifesto do módulo `app`, embora a Activity esteja declarada em `mupen64plus-rollback/src/main/AndroidManifest.xml`. Isso gerava um aviso enganoso mesmo com o módulo corretamente configurado.

**Solução:** a checagem foi alterada para o Manifesto do módulo de rollback. O resultado posterior passou sem avisos.

### 9. Android SDK ausente no ambiente local

O comando `./gradlew assemble` parou antes da compilação com:

```text
SDK location not found. Define a valid SDK location with an ANDROID_HOME environment variable or by setting the sdk.dir path in local.properties.
```

**Solução:** não foi criado `local.properties`, pois esse arquivo contém um caminho específico da máquina e não deve ser versionado. O workflow usa `android-actions/setup-android@v3`, configura o SDK no runner e exporta `ANDROID_SDK_ROOT`. Para reproduzir localmente, é necessário instalar o Android SDK/NDK e definir `ANDROID_HOME` ou criar um `local.properties` não versionado.

### 10. Etapa de Release condicionada à branch incorreta

O repositório utiliza `main`, mas o workflow tinha `if: github.ref == 'refs/heads/master'`. Essa condição impediria a etapa opcional de Release de executar após pushes em `main`, embora o upload do Artifact da etapa de build continuasse disponível.

**Solução:** a condição foi atualizada para `if: github.ref == 'refs/heads/main'`.

## Validações executadas

O verificador estrutural confirmou a presença do módulo de rollback, do bridge JNI e das fontes GekkoNet, conferiu a correspondência entre métodos nativos Java e exports JNI, verificou os comandos de rollback no core, a inclusão do módulo no Gradle, a dependência da aplicação e a Activity no Manifesto correto.

O build local não foi usado como critério final porque o ambiente não fornece Android SDK. O critério final será o resultado do workflow no GitHub Actions, que também disponibiliza o APK por meio de Artifact na página do run.

## Resultado do CI

Esta seção será preenchida após o push:

| Item | Valor |
|---|---|
| Commit publicado | A confirmar |
| Run do GitHub Actions | A confirmar |
| Resultado do build | A confirmar |
| Nome do Artifact | A confirmar |
| APK | `Mupen64PlusAE-release.apk` |

## Referências

[1]: https://github.com/contacontada/mupen64plus-ae-rollback "Repositório público Mupen64Plus AE Rollback"

[2]: https://github.com/contacontada/mupen64plus-ae-rollback/blob/main/.github/workflows/build.yml "Workflow de compilação do repositório"

[3]: https://docs.github.com/en/actions/using-workflows/storing-workflow-data-as-artifacts "GitHub Actions — armazenamento de dados como artifacts"

### 11. Permissão insuficiente para a etapa Release

O job `Build` compilou o APK e finalizou o Artifact `mupen64plus-ae-main-d1c52f6`, mas o job `Release` falhou ao tentar atualizar a tag `Pre-release`:

```text
remote: Permission to contacontada/mupen64plus-ae-rollback.git denied to github-actions[bot].
fatal: unable to access 'https://github.com/contacontada/mupen64plus-ae-rollback/': The requested URL returned error: 403
```

**Solução:** foi adicionada a permissão global `contents: write` ao workflow. O APK do primeiro run está disponível e foi baixado para verificação; um novo run será usado para validar a execução completa, incluindo Release.

## Resultado do primeiro CI após o push

| Item | Valor |
|---|---|
| Commit publicado | `d1c52f6efba30c4a40c5bbe118c550a65335bd30` |
| Run do GitHub Actions | [31274026784][4] |
| Job Build | **Sucesso** |
| Job Release | Falhou com HTTP 403 por falta de `contents: write` |
| Artifact do APK | [`mupen64plus-ae-main-d1c52f6`][5] |
| Arquivo verificado | `Mupen64PlusAE-release.apk` — 34.159.805 bytes |

O workflow foi corrigido após esse run. O resultado final deverá ser atualizado após a nova execução.

[4]: https://github.com/contacontada/mupen64plus-ae-rollback/actions/runs/31274026784 "Primeiro run após a atualização do patch8"

[5]: https://github.com/contacontada/mupen64plus-ae-rollback/actions/runs/31274026784/artifacts/9026649077 "Artifact do APK do primeiro run"
