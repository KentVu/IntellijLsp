# Research: LSP Extended Features (Phase 9)

**Date**: 2025-12-14  
**Branch**: `001-lsp-extended`

## R1. IntelliJ Platform API Research

### Signature Help

**API entry points**:

- `com.intellij.lang.parameterInfo.ParameterInfoHandler` - Language-specific parameter information handling.
- `com.intellij.lang.parameterInfo.ParameterInfoUtils` - Utility methods.

**Implementation strategy**:

1. Get the `PsiElement` at the current position.
2. Find the enclosing function call expression.
3. Use `resolveReference()` to get the target method.
4. Extract parameter information from `PsiMethod`/`KtFunction`.
5. Calculate the current parameter index by counting commas.

**Decision**: Analyze PSI directly instead of using `ParameterInfoHandler`, which requires an Editor context.

---

### Workspace Symbols

**API entry points**:

- `com.intellij.ide.util.gotoByName.ChooseByNameContributor`
- `com.intellij.ide.util.gotoByName.GotoClassContributor`
- `com.intellij.ide.util.gotoByName.GotoSymbolContributor`
- `com.intellij.navigation.ChooseByNameContributorEx`

**Implementation strategy**:

1. Use `AllClassesGotoSearcher` or `DefaultChooseByNameItemProvider`.
2. Set `maxResults = 100` to limit the number of results.
3. Convert `NavigationItem` to `SymbolInformation`.
4. Handle search cancellation by checking for `CancellationException`.

**Decision**: Combine `ChooseByNameContributorEx.processNames()` and `processElementsWithName()`.

---

### Code Formatting

**API entry points**:

- `com.intellij.psi.codeStyle.CodeStyleManager`
- `com.intellij.openapi.editor.actions.AbstractIndentLinesHandler`

**Implementation strategy**:

1. Create a document copy (`PsiDocumentManager.getDocument()` + copy).
2. Run `CodeStyleManager.reformatText()` on the copy.
3. Compare the original and formatted documents using a diff algorithm.
4. Generate `TextEdit[]` and return it to the client.

**Decision**: Use `DocumentImpl` to create a temporary document and avoid modifying the original file.

---

### Code Actions (QuickFix)

**API entry points**:

- `com.intellij.codeInsight.intention.IntentionManager`
- `com.intellij.codeInsight.daemon.impl.ShowIntentionsPass`
- `com.intellij.codeInsight.daemon.impl.quickfix.QuickFixAction`

**Implementation strategy**:

1. Get diagnostics at the current position (using the existing `DiagnosticsProvider`).
2. Use `IntentionManager.getIntentions()` to get available actions.
3. Filter for QuickFixes with `HIGH` or `ERROR_PRIORITY`.
4. Return only actions that can be converted to `WorkspaceEdit`.

**Decision**: Initially, do not support the `command` form; return only the `edit` field.

---

### Go to Implementation

**API entry points**:

- `com.intellij.codeInsight.navigation.actions.GotoImplementationAction`
- `com.intellij.psi.search.searches.DefinitionsScopedSearch`
- `com.intellij.codeInsight.navigation.DefinitionsScopedSearchQuery`

**Implementation strategy**:

1. Get the `PsiElement` at the current position.
2. Use `DefinitionsScopedSearch.search()` to find implementations.
3. Handle interface and abstract methods.
4. Support Java ↔ Kotlin interoperability (provided by IntelliJ).

**Decision**: Reuse the existing `PsiMapper` for position conversion.

---

### Inlay Hints

**API entry points**:

- `com.intellij.codeInsight.hints.InlayHintsProvider` (2020.3+)
- `com.intellij.codeInsight.hints.InlayHintsSink`
- `com.intellij.codeInsight.hints.declarative.InlayHintsProvider` (2022.3+)

**Implementation strategy**:

1. Traverse the PSI tree to collect hint locations.
2. For type inference, find `val`/`var` declarations and get the inferred type.
3. For parameter names, find function calls and get the parameter names.
4. Convert the results to the LSP `InlayHint` format.

**Decision**: Analyze PSI directly instead of using `InlayHintsProvider` to avoid an Editor dependency.

---

## R2. Summary of Technical Decisions

| Area              | Decision                  | Alternatives Considered  | Rationale    |
|-------------------|---------------------------|--------------------------|--------------|
| Signature Help    | Direct PSI analysis           | ParameterInfoHandler      | Avoids an Editor dependency |
| Workspace Symbols | ChooseByNameContributorEx     | AllClassesGotoSearcher    | More flexible filtering |
| Formatting        | Temporary document + diff     | Direct modification       | Conforms to the LSP model |
| Code Actions      | IntentionManager + filtering  | ShowIntentionsPass        | Simpler API |
| Implementation    | DefinitionsScopedSearch       | GotoImplementationAction  | Lower-level and more controllable |
| Inlay Hints       | PSI traversal                 | InlayHintsProvider        | Avoids an Editor dependency |

---

## R3. Dependencies

### IntelliJ Platform Modules

All features use built-in IntelliJ Platform capabilities and require no additional dependencies:

- `com.intellij.lang` - Language support
- `com.intellij.psi` - PSI framework
- `com.intellij.codeInsight` - Code intelligence
- `com.intellij.ide.util` - IDE utilities

### Thread Safety

All PSI operations must run inside `ReadAction.compute {}` to comply with the Constitution.
