# Registro de Erros de Build - mupen64plus-ae-rollback

Este documento registra os erros encontrados durante a configuração inicial do repositório e a compilação do APK via GitHub Actions, juntamente com as soluções aplicadas.

## 1. Erro de Namespace no Gradle 8.14+
**Descrição:** O GitHub Actions utiliza uma versão recente do Gradle que exige que o namespace do Android seja declarado explicitamente no arquivo `build.gradle`, e não apenas no `AndroidManifest.xml`.
**Erro:**
```
A problem occurred configuring project ':mupen64plus-rollback'.
Namespace not specified. Please specify a namespace in the module's build.gradle file like so:
```
**Solução:** Adicionado o bloco `namespace 'paulscode.mupen64plusae.rollback'` dentro do bloco `android { }` no arquivo `mupen64plus-rollback/build.gradle`.

## 2. Dependências de Bibliotecas Nativas (ndk-build)
**Descrição:** O Gradle executa o `ndk-build` para cada módulo de forma isolada. O módulo `mupen64plus-rollback` dependia de `mupen64plus-core` como `LOCAL_SHARED_LIBRARIES`, mas o `ndk-build` não conseguia encontrá-lo no contexto isolado.
**Erro:**
```
Android NDK: Module mupen64plus-rollback depends on undefined modules: mupen64plus-core
```
**Tentativa 1:** Tentei incluir o `native_common.mk` e o `mupen64plus-core.mk` no `Android.mk` do rollback. Isso causou erros de paths e conflitos de variáveis (`JNI_LOCAL_PATH`).
**Solução:** Removi as inclusões problemáticas do `Android.mk` e adicionei `arguments "APP_ALLOW_MISSING_DEPS=true"` no `build.gradle` do rollback. Isso permite que o `ndk-build` termine sem falhar, delegando o linking da shared library para o próprio Gradle via `dependencies`.

## 3. Falta de RTTI (Run-Time Type Information)
**Descrição:** A biblioteca estática `GekkoNet` (compilada junto com o rollback) e o próprio código do rollback utilizam recursos de C++ como `typeid` e `dynamic_cast`, que requerem que o RTTI esteja habilitado.
**Erro:**
```
error: use of typeid requires -frtti
error: use of dynamic_cast requires -frtti
```
**Solução:** Adicionei a flag `-frtti` nos argumentos `LOCAL_CPPFLAGS` tanto para o módulo estático `gekkonet` quanto para o módulo compartilhado `mupen64plus-rollback` no arquivo `mupen64plus-rollback/jni/Android.mk`.

## 4. Macros de Log e Includes fora de Escopo
**Descrição:** As macros `LOGE` e `LOGI` foram usadas em funções que estavam definidas no início do arquivo `rollback_jni.cpp`, mas as macros só foram declaradas mais abaixo, após o include da API do `m64p_types`.
**Erro:**
```
error: use of undeclared identifier 'LOGE'
error: use of undeclared identifier 'LOGI'
```
**Solução:** Movi as definições das macros `LOGI`, `LOGE` e `LOGD` para o topo do arquivo `rollback_jni.cpp`, logo após os includes padrão, antes de qualquer uso.

## 5. Caminho Incorreto para Headers da API
**Descrição:** O caminho especificado no `LOCAL_C_INCLUDES` do `Android.mk` não apontava corretamente para o diretório onde os headers da API do `mupen64plus-core` estavam localizados.
**Erro:**
```
fatal error: 'api/m64p_types.h' file not found
```
**Solução:** O arquivo `Android.mk` do rollback está localizado em `mupen64plus-rollback/jni/`. Para chegar na raiz do projeto e acessar o core, é necessário usar `../../` e não apenas `../`. O `LOCAL_C_INCLUDES` foi corrigido para `$(LOCAL_PATH)/../../mupen64plus-core/upstream/src`.

## 6. Erro de Linker no mupen64plus-core (api_export.ver)
**Descrição:** O símbolo `set_pif_sync_callback` foi adicionado à API mas não foi incluído no script de versão de exportação do `mupen64plus-core`.
**Erro:**
```
linker command failed with exit code 1 (use -v to see invocation)
```
**Solução:** Adicionado `set_pif_sync_callback;` dentro do bloco `global:` do arquivo `mupen64plus-core/upstream/src/api/api_export.ver`, antes da seção `local: *;`.

## 7. Arquivos .so e .a ignorados pelo Git
**Descrição:** O arquivo `.gitignore` padrão continha regras para ignorar arquivos `.so` e `.a`, que são necessários para o build (como `libhidapi.so`).
**Solução:** Forçado o tracking desses arquivos com `git add -f` para garantir que fossem enviados ao repositório.

---
*Este arquivo será atualizado conforme novos erros forem encontrados e corrigidos durante o processo de build.*

## 8. Ambiente local sem Android SDK

**Descrição:** A verificação estrutural passou, mas o build local com `./gradlew assemble` não pôde iniciar a configuração dos módulos porque o sandbox não possui `ANDROID_HOME`, `ANDROID_SDK_ROOT`, `sdkmanager` ou um `local.properties` apontando para um Android SDK válido.

**Erro:**
```text
SDK location not found. Define a valid SDK location with an ANDROID_HOME environment variable or by setting the sdk.dir path in local.properties.
```

**Solução aplicada:** Não foi adicionada uma configuração local específica ao repositório, pois `local.properties` contém caminhos dependentes da máquina e não deve ser versionado. O workflow do GitHub Actions já instala/configura o SDK por meio de `android-actions/setup-android@v3` e define `ANDROID_SDK_ROOT`, portanto a validação de compilação deve ser concluída no CI. O README foi atualizado com os pré-requisitos para reproduzir o build localmente.

## 9. Condição de Release apontava para a branch incorreta

**Descrição:** O repositório usa `main` como branch padrão, mas a etapa opcional de Release do workflow ainda estava condicionada a `refs/heads/master`.

**Solução:** A condição foi atualizada para `refs/heads/main`. O upload do APK como Artifact continua ocorrendo na etapa `build` para pushes e pull requests.

## 10. Token do GitHub Actions sem permissão para atualizar a tag

**Descrição:** O job `Build` compilou e publicou o Artifact corretamente, mas o job `Release` falhou ao executar `git push -f origin Pre-release`.

**Erro:**
```text
remote: Permission to contacontada/mupen64plus-ae-rollback.git denied to github-actions[bot].
fatal: unable to access 'https://github.com/contacontada/mupen64plus-ae-rollback/': The requested URL returned error: 403
```

**Solução:** Adicionada a permissão explícita `contents: write` no workflow. Isso permite que o `GITHUB_TOKEN` do job atualize a tag `Pre-release` e que a etapa de Release crie/atualize a release correspondente. O APK já havia sido compilado com sucesso; um novo run será usado para validar a correção completa.
