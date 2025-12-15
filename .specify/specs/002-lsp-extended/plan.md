# Implementation Plan: LSP Extended Features (Phase 9)

**Branch**: `001-lsp-extended` | **Date**: 2025-12-14 | **Spec
**: [spec.md](file:///f:/code/env/IntellijLsp/specs/001-lsp-extended/spec.md)
**Input**: Phase 9 extended feature specification

## Summary

Implement six LSP extensions in the IntelliJ LSP Server: Signature Help, Workspace Symbols, Code Formatting, Code Actions (QuickFix only), Go to Implementation, and Inlay Hints. All features follow the Constitution principles and reuse native IntelliJ Platform SDK capabilities.

## Technical Context

**Language/Version**: Kotlin 2.1.0 + JVM 21  
**Primary Dependencies**: IntelliJ Platform SDK 2025.1.4.1, Kotlin Coroutines 1.8.1  
**Storage**: N/A (no persistence required)
**Testing**: JUnit 5 (jupiter 5.10.2)  
**Target Platform**: IntelliJ IDEA 2025.1+ (IC edition)
**Project Type**: IntelliJ Plugin (single project)
**Performance Goals**: Signature Help < 300ms, Workspace Symbol < 2s (95th percentile)  
**Constraints**: Local access only (TCP/UDS); PSI access runs inside a ReadAction.
**Scale/Scope**: 6 new handlers + 4 new providers

## Constitution Check

_GATE: All features must pass the Constitution check._

| Principle                     | Status | Compliance                       |
|-------------------------------|--------|----------------------------------|
| I. Platform Integration First | ✅ Pass | All features reuse IntelliJ Platform SDK APIs |
| II. Protocol Conformance      | ✅ Pass | Follow the LSP 3.17 specification |
| III. Project Isolation        | ✅ Pass | Handlers are bound to a Project instance |
| IV. Graceful Degradation      | ✅ Pass | Return empty results when data is unavailable |
| V. Observability              | ✅ Pass | Emit structured logs for all operations |

## Project Structure

### Documentation (this feature)

```text
specs/001-lsp-extended/
├── plan.md              # This file
├── research.md          # Phase 0 output
├── data-model.md        # Phase 1 output
└── tasks.md             # Phase 2 output (/speckit.tasks)
```

### Source Code (repository root)

```text
src/main/kotlin/com/frenchef/intellijlsp/
├── handlers/                     # LSP request/notification handlers
│   ├── LifecycleHandler.kt       # [MODIFY] Add new capabilities
│   ├── SignatureHelpHandler.kt   # [NEW]
│   ├── WorkspaceSymbolHandler.kt # [NEW]
│   ├── FormattingHandler.kt      # [NEW]
│   ├── CodeActionHandler.kt      # [NEW]
│   ├── ImplementationHandler.kt  # [NEW]
│   └── InlayHintsHandler.kt      # [NEW]
├── intellij/                     # IntelliJ Platform integration
│   ├── SignatureHelpProvider.kt  # [NEW]
│   ├── WorkspaceSymbolProvider.kt# [NEW]
│   ├── FormattingProvider.kt     # [NEW]
│   └── CodeActionProvider.kt     # [NEW]
└── protocol/models/
    └── LspTypes.kt               # [MODIFY] Add new data models

src/test/kotlin/com/frenchef/intellijlsp/
└── handlers/
    ├── SignatureHelpHandlerTest.kt   # [NEW]
    ├── WorkspaceSymbolHandlerTest.kt # [NEW]
    ├── FormattingHandlerTest.kt      # [NEW]
    ├── CodeActionHandlerTest.kt      # [NEW]
    ├── ImplementationHandlerTest.kt  # [NEW]
    └── InlayHintsHandlerTest.kt      # [NEW]
```

**Structure Decision**: Follow the existing Handler/Provider separation pattern: handlers manage the JSON-RPC protocol layer, while providers encapsulate IntelliJ Platform API calls.

---

## Phase 0: Research

### R1. IntelliJ Platform API Research

**Signature Help**:

- Use the `ParameterInfoHandler` API to retrieve function signatures.
- Create a temporary Editor context (see `EditorFactory.createDocument`).
- See `com.intellij.lang.parameterInfo.ParameterInfoUtils`.

**Workspace Symbols**:

- Use the `GotoClassContributor` / `GotoSymbolContributor` APIs.
- Implement search with `DefaultChooseByNameItemProvider`.
- Limit the number of results with the `maxResults` parameter.

**Code Formatting**:

- Run `CodeStyleManager.reformatText()` on a copy.
- Calculate the diff with `DocumentUtil` or a diff algorithm.
- Return a `TextEdit` array for the client to apply.

**Code Actions (QuickFix)**:

- Use `ShowIntentionActionsHandler` to retrieve intentions at the current position.
- Filter `IntentionAction.familyName` for QuickFix types.
- Exclude actions that directly modify PSI.

**Go to Implementation**:

- Reuse the `DefinitionsScopedSearch` API.
- Support finding implementations of interfaces and abstract classes.
- Handle Java ↔ Kotlin interoperability.

**Inlay Hints**:

- Use the `InlayHintsProvider` API (2020.3+).
- Collect `InlayPresentation` instances and convert them to LSP `InlayHint`s.
- Support type hints and parameter hints.

### R2. Technical Decisions

| Decision            | Choice           | Rationale             |
|---------------------|------------------|-----------------------|
| Signature Help trigger characters | `(` and `,` | LSP-standard triggers, matching VS Code behavior |
| Workspace Symbol limit | 100 items | Avoid performance issues in large projects |
| Formatting implementation | Run on a copy + diff | Does not modify files directly; follows the LSP model |
| CodeAction scope | QuickFix only | Based on spec clarification; avoids complex refactoring |
| Initial Inlay Hints support | Type + Parameter | Covers the most common scenarios |

---

## Phase 1: Data Model

### New LSP Types

```kotlin
// Signature Help
data class SignatureHelp(
    val signatures: List<SignatureInformation>,
    val activeSignature: Int? = null,
    val activeParameter: Int? = null
)

data class SignatureInformation(
    val label: String,
    val documentation: String? = null,
    val parameters: List<ParameterInformation>? = null
)

data class ParameterInformation(
    val label: Any, // String or [Int, Int]
    val documentation: String? = null
)

// Workspace Symbol
data class WorkspaceSymbolParams(
    val query: String
)

// Formatting
data class DocumentFormattingParams(
    val textDocument: TextDocumentIdentifier,
    val options: FormattingOptions
)

data class FormattingOptions(
    val tabSize: Int,
    val insertSpaces: Boolean
)

// Code Action
data class CodeActionParams(
    val textDocument: TextDocumentIdentifier,
    val range: Range,
    val context: CodeActionContext
)

data class CodeActionContext(
    val diagnostics: List<Diagnostic>,
    val only: List<String>? = null
)

data class CodeAction(
    val title: String,
    val kind: String? = null,
    val edit: WorkspaceEdit? = null,
    val command: Command? = null
)

// Inlay Hints
data class InlayHintParams(
    val textDocument: TextDocumentIdentifier,
    val range: Range
)

data class InlayHint(
    val position: Position,
    val label: Any, // String or InlayHintLabelPart[]
    val kind: Int? = null,
    val paddingLeft: Boolean? = null,
    val paddingRight: Boolean? = null
)
```

---

## Verification Plan

### Unit Tests

The existing test structure uses JUnit 5, with test files under `src/test/kotlin/`.

**Commands**:

```bash
./gradlew test
```

**New tests**:

| Handler                | Test file                       | Test coverage                    |
|------------------------|---------------------------------|----------------------------------|
| SignatureHelpHandler   | `SignatureHelpHandlerTest.kt`   | Verify trigger responses and activeParameter changes |
| WorkspaceSymbolHandler | `WorkspaceSymbolHandlerTest.kt` | Verify search results and the 100-result limit |
| FormattingHandler      | `FormattingHandlerTest.kt`      | Verify TextEdit correctness |
| CodeActionHandler      | `CodeActionHandlerTest.kt`      | Verify QuickFix filtering |
| ImplementationHandler  | `ImplementationHandlerTest.kt`  | Verify interface implementation search |
| InlayHintsHandler      | `InlayHintsHandlerTest.kt`      | Verify Type/Parameter hints |

### Manual Verification

**Prerequisites**:

1. Run the plugin in IntelliJ (Run Plugin configuration).
2. Connect Neovim to the LSP Server.

**Verification steps**:

1. **Signature Help**: Open a .kt file, type `listOf(`, and confirm the function signature appears.
2. **Workspace Symbol**: Run `:lua vim.lsp.buf.workspace_symbol("Foo")` in Neovim and confirm that a symbol list is returned.
3. **Formatting**: Run `:lua vim.lsp.buf.format()` and confirm that the code is formatted.
4. **Code Action**: Run `:lua vim.lsp.buf.code_action()` on a warning and confirm that a QuickFix appears.
5. **Implementation**: Run `:lua vim.lsp.buf.implementation()` on an interface method and confirm navigation.
6. **Inlay Hints**: Run `:lua vim.lsp.inlay_hint.enable(true)` and confirm that hints are displayed.

---

## Complexity Tracking

> No Constitution violations need to be reported.

---

## Implementation Order

Ordered by priority and dependencies:

1. **LspTypes.kt** - Add all new data models (no dependencies).
2. **LifecycleHandler.kt** - Declare the new capabilities.
3. **SignatureHelpHandler/Provider** (P1)
4. **WorkspaceSymbolHandler/Provider** (P2)
5. **FormattingHandler/Provider** (P2)
6. **CodeActionHandler/Provider** (P3)
7. **ImplementationHandler** (P3, reuse the existing PsiMapper).
8. **InlayHintsHandler/Provider** (P4)
