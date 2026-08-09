# Mupen64Plus AE Rollback — erros e soluções

**Autor:** Manus AI  
**Data da atualização:** 9 de agosto de 2026  
**Repositório:** [contacontada/mupen64plus-ae-rollback][1]

## Objetivo e escopo

Este relatório registra os problemas encontrados ao integrar os pacotes de correção (patch8 e patch9), atualizar o repositório público e preparar a compilação do APK pelo GitHub Actions. O código foi integrado na raiz do repositório, o README foi revisado, o workflow foi corrigido e as verificações estruturais foram executadas antes do envio de cada patch.

A verificação local `./verify_build.sh` terminou com **sucesso e zero avisos** após a correção da checagem do Manifesto em ambas as integrações. O build local completo não pôde prosseguir por ausência de Android SDK no sandbox; o critério definitivo de sucesso é a execução do workflow no GitHub Actions.

| Item | Resultado final (patch8) | Resultado atual (patch9) |
|---|---|---|
| Pacote integrado | patch8 | patch9 |
| Verificação estrutural | Aprovada | Aprovada |
| Build no GitHub Actions | Sucesso | A confirmar |
| Release no GitHub Actions | Sucesso | A confirmar |
| Artifact final | [`mupen64plus-ae-main-1328e29`][5] | A confirmar |

## Erros de compilação e soluções aplicadas

### 1. Namespace ausente no Gradle
Versões recentes do Android Gradle Plugin exigem que o namespace do módulo seja declarado no `build.gradle`. O módulo de rollback recebeu o namespace `paulscode.mupen64plusae.rollback`.

### 2. Dependência nativa não encontrada pelo ndk-build
O módulo de rollback dependia do `mupen64plus-core`, mas a execução isolada do `ndk-build` não encontrava o módulo nativo. Foi configurado com `APP_ALLOW_MISSING_DEPS=true`.

### 3. RTTI desabilitado no código C++
O código GekkoNet e o bridge JNI usam `typeid` e `dynamic_cast`, que exigem RTTI. A flag `-frtti` foi adicionada no `Android.mk`.

### 4. Macros de log declaradas depois do uso
As macros `LOGI`, `LOGE` e `LOGD` foram movidas para o início de `rollback_jni.cpp`, antes de qualquer uso.

### 5. Caminho incorreto dos headers do core
O `LOCAL_C_INCLUDES` foi corrigido para `../../mupen64plus-core/upstream/src`.

### 6. Símbolo ausente no script de exportação
O símbolo `set_pif_sync_callback` foi adicionado ao bloco global de `api_export.ver`.

### 7. Bibliotecas nativas ignoradas pelo Git
Regras do `.gitignore` foram contornadas para incluir artefatos nativos necessários ao build.

### 8. Duplicidades e incompatibilidades Java/Android
Foram corrigidas dependências JNA, interfaces locais, constantes de `InputType`, duplicidade de `libmupen64plus-core.so`, conflitos de Manifesto e a classe `CoreLibrary` duplicada no R8.

## Erros encontrados nesta execução

### 9. Verificador apontava o Manifesto errado
O script `verify_build.sh` procurava a Activity no módulo `app` em vez de `mupen64plus-rollback`.
**Solução:** checagem alterada para o Manifesto correto do módulo de rollback.

### 10. Android SDK ausente no ambiente local
O comando `./gradlew assemble` parou por falta de `ANDROID_HOME`.
**Solução:** validação delegada ao GitHub Actions, que possui o ambiente configurado.

### 11. Etapa de Release condicionada à branch incorreta
O workflow usava `master` em vez de `main`.
**Solução:** condição atualizada para `refs/heads/main`.

### 12. Token do GitHub Actions sem permissão para atualizar a tag
O job `Release` falhou com HTTP 403 ao tentar atualizar a tag `Pre-release`.
**Solução:** adicionada a permissão `contents: write` ao workflow.

### 13. Regressão de arquivos na integração do patch9
O pacote `patch9` continha versões desatualizadas de arquivos críticos que sobrescreveram as correções manuais do `patch8`.
**Solução:** as correções de workflow, checagem de Manifesto e README foram restauradas manualmente.

## Resultados dos runs do GitHub Actions

| Run | Patch | Build | Release | Observação |
|---:|---|---|---|---|
| [31274026784][3] | patch8 | Sucesso | Falha 403 | Erro de permissão do token |
| [31274731391][4] | patch8 | Sucesso | Sucesso | Validação do patch8 concluída |
| A confirmar | patch9 | A confirmar | A confirmar | Aguardando push do patch9 |

## Referências

[1]: https://github.com/contacontada/mupen64plus-ae-rollback
[2]: https://github.com/contacontada/mupen64plus-ae-rollback/blob/main/.github/workflows/build.yml
[3]: https://github.com/contacontada/mupen64plus-ae-rollback/actions/runs/31274026784
[4]: https://github.com/contacontada/mupen64plus-ae-rollback/actions/runs/31274731391
[5]: https://github.com/contacontada/mupen64plus-ae-rollback/actions/runs/31274731391/artifacts/9026835826
