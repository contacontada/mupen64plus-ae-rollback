# Mupen64Plus-AE Rollback — Relatório de erros e soluções

**Autor:** Manus AI  
**Data:** 7 de agosto de 2026  
**Repositório:** [contacontada/mupen64plus-ae-rollback](https://github.com/contacontada/mupen64plus-ae-rollback)  
**Execução bem-sucedida:** [GitHub Actions run 31222562403](https://github.com/contacontada/mupen64plus-ae-rollback/actions/runs/31222562403)

## 1. Objetivo e resultado

O projeto fornecido no arquivo `mupen64plus-ae-rollback-fixed_patch3.tar.gz` foi extraído, integrado ao histórico existente do repositório e publicado em um repositório público do GitHub. O workflow de GitHub Actions foi executado repetidamente, com correção dos erros encontrados entre as execuções.

A execução final, identificada pelo commit `cd0c67d`, terminou com sucesso em aproximadamente 15 minutos e 32 segundos. O artifact foi publicado com o nome `mupen64plus-ae-main-cd0c67d` e contém `Mupen64PlusAE-release.apk`.

| Item | Resultado |
|---|---|
| Repositório | Público e publicado no GitHub |
| Branch | `main` |
| Commit final | `cd0c67db6a14d4fd344fbd9f0ecf5647c88291a1` |
| Workflow final | Sucesso |
| Artifact | `mupen64plus-ae-main-cd0c67d` |
| APK | `Mupen64PlusAE-release.apk` |
| Tamanho do APK baixado | 34.155.917 bytes |

## 2. Erros encontrados e soluções aplicadas

### Erro 1 — Falha de autenticação no push inicial

O primeiro envio Git foi recusado porque o Git local não estava usando uma credencial de escrita válida, apesar de o GitHub CLI possuir sessão autenticada. A solução foi configurar temporariamente um helper de autenticação para a operação de push, sem adicionar credenciais ao repositório, e remover o helper imediatamente depois do envio.

Nenhum token foi gravado no histórico Git, nos arquivos do projeto ou no workflow.

### Erro 2 — Módulo NDK indefinido e símbolos do core ausentes

A primeira compilação falhou porque o módulo rollback não incluía o módulo nativo `mupen64plus-core` no mesmo grafo do `ndk-build`. Como consequência, a biblioteca JNI não conseguia resolver o módulo nem os símbolos `CoreDoCommand` durante o link.

A correção foi incluir o makefile do core no `mupen64plus-rollback/jni/Android.mk`, preservando o diretório original do módulo rollback antes da inclusão e restaurando-o explicitamente antes de declarar a biblioteca JNI.

### Erro 3 — Caminho de projeto alterado pela inclusão do core

Depois da primeira correção, o NDK passou a restaurar o diretório interno do core em vez do diretório do rollback. Isso causava referências incorretas aos arquivos JNI do módulo.

A solução foi salvar o valor original de `LOCAL_PATH` em uma variável dedicada antes de incluir o core e usar esse valor para declarar os arquivos e a biblioteca JNI do rollback.

### Erro 4 — Dependências JNA e interface Java ausentes

A compilação Java do módulo rollback falhou porque as classes JNA não estavam disponíveis no módulo e `CoreLibrary` existia apenas no módulo principal do app. Foram adicionadas as dependências JNA `jna-platform` e `jna` ao `build.gradle` do rollback, além de uma interface local mínima para a chamada nativa `CoreDoCommand`.

Posteriormente, essa interface foi renomeada para `RollbackCoreLibrary`, evitando colisão com a interface existente no app.

### Erro 5 — Constantes inválidas de `InputType`

`RollbackSettingsActivity` usava `InputType.TYPE_TEXT_URI` e `InputType.TYPE_TEXT_PERSON_NAME`, nomes que não existem na API Android utilizada pelo projeto.

As expressões foram substituídas pelas combinações válidas `TYPE_CLASS_TEXT | TYPE_TEXT_VARIATION_URI` e `TYPE_CLASS_TEXT | TYPE_TEXT_VARIATION_PERSON_NAME`.

### Erro 6 — Biblioteca nativa `libmupen64plus-core.so` duplicada

O app recebia duas cópias de `libmupen64plus-core.so`: uma proveniente do módulo `mupen64plus-rollback`, que precisava compilar o core para fazer o link JNI, e outra proveniente do módulo `mupen64plus-core` usado pelo app. O Android Gradle Plugin interrompeu a tarefa `:app:mergeDebugNativeLibs` por causa da duplicação.

A dependência Gradle redundante do módulo core foi removida do rollback e o `build.gradle` do rollback passou a excluir `**/libmupen64plus-core.so` do empacotamento do AAR. Assim, o módulo rollback continua podendo linkar contra o core, enquanto o app fornece uma única cópia final da biblioteca.

### Erro 7 — Conflito no Android Manifest

O manifest principal e o manifest do módulo rollback declaravam as mesmas activities, mas com valores diferentes para `android:exported`, provocando falha em `:app:processDebugMainManifest`.

As declarações duplicadas de `RollbackNetplayActivity` e `RollbackSettingsActivity` foram removidas do manifest principal. As declarações passaram a ser fornecidas pelo módulo rollback, que é o proprietário dessas activities.

### Erro 8 — Classe `CoreLibrary` duplicada no R8

A compilação release alcançou a minificação, mas o R8 detectou duas definições de `paulscode.android.mupen64plusae.jni.CoreLibrary`: uma no app e outra no módulo rollback. Esse conflito foi reportado durante `:app:minifyReleaseWithR8`.

A interface local foi renomeada para `RollbackCoreLibrary`, colocada no pacote do rollback, e a ponte JNI foi atualizada para carregar essa interface. O arquivo duplicado foi removido.

### Erro 9 — Campo inválido na consulta de metadata do artifact

Ao coletar o link final, a consulta resumida do GitHub CLI foi executada com um campo JSON `artifacts` que não estava disponível nessa operação. Isso não afetou o build nem o repositório.

A solução foi consultar diretamente o endpoint de artifacts da execução e então baixar o artifact com o comando de download do GitHub CLI. O artifact final foi identificado pelo ID `9011233122`.

## 3. Execuções relevantes

| Execução | Resultado | Causa principal |
|---:|---|---|
| 31218180106 | Falha | Integração inicial do módulo nativo |
| 31218537199 | Falha | Caminho/restauração do `LOCAL_PATH` no NDK |
| 31218773878 | Falha | Dependências Java/JNA e símbolos do core |
| 31219271363 | Falha | Constantes Android inválidas |
| 31219817630 | Falha | Biblioteca nativa do core duplicada |
| 31220786804 | Falha | Duplicação persistente e conflito de manifest |
| 31221562539 | Falha | Classe `CoreLibrary` duplicada no R8 |
| 31222562403 | **Sucesso** | APK compilado e artifact publicado |

As mensagens de depreciação do GitHub Actions sobre Node.js 20, `setup-java@v4` e a distribuição AdoptOpenJDK foram registradas como avisos do ambiente, não como causas da falha. Elas não impediram a compilação final.

## 4. Resultado final e verificação

O commit final foi enviado para `main`, e a execução `31222562403` concluiu todas as etapas, incluindo build, geração das informações de branch e SHA e upload do artifact. O APK foi baixado localmente para verificação de existência e tamanho.

> Artifact final: `mupen64plus-ae-main-cd0c67d`  
> Arquivo: `Mupen64PlusAE-release.apk`  
> Status: compilação concluída com sucesso

## Referências

[1]: https://github.com/contacontada/mupen64plus-ae-rollback "Repositório público do projeto"

[2]: https://github.com/contacontada/mupen64plus-ae-rollback/actions/runs/31222562403 "Execução final bem-sucedida do GitHub Actions"

[3]: https://github.com/contacontada/mupen64plus-ae-rollback/actions/runs/31222562403/artifacts/9011233122 "Artifact do APK no GitHub Actions"

[4]: https://developer.android.com/r/tools/jniLibs-vs-imported-targets "Documentação Android sobre bibliotecas JNI e targets importados"
