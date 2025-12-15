# Feature Specification: LSP Extended Features (Phase 9)

**Feature Branch**: `001-lsp-extended`  
**Created**: 2025-12-14  
**Status**: Implemented  
**Input**: Phase 9 extensions based on future-enhancements.md

## Implementation Notes

- **UAST** (Unified Abstract Syntax Tree) for unified cross-language Java/Kotlin processing.
- **K2 compatibility**: Declared with `<supportsKotlinPluginMode supportsK2="true">`.
- **Dependencies**: `com.intellij.java` (required), `org.jetbrains.kotlin` (optional).

## Clarifications

### Session 2025-12-14

- Q: Which CodeAction types should Phase 9 support? → A: QuickFix only (to fix diagnostic issues).

## User Scenarios & Testing

### User Story 1 - Function Parameter Hints: Signature Help (Priority: P1)

When the user calls a function, the editor automatically displays parameter information (name, type, and current parameter position).

**Why this priority**: Signature Help significantly improves the coding experience and reduces the need to consult documentation.

**Independent Test**: Type `listOf(` in a .kt/.java file and verify that the `listOf(elements: T...)` signature is displayed.

**Acceptance Scenarios**:

1. **Given** the user types `(` to call a function, **When** a `textDocument/signatureHelp` request is sent, **Then** a SignatureInformation containing the parameter list is returned.
2. **Given** the user types a comma to move to the next parameter, **When** a signatureHelp request is sent, **Then** activeParameter points to the current parameter.
3. **Given** the function has multiple overloads, **When** a signatureHelp request is sent, **Then** multiple SignatureInformation entries are returned for the user to choose from.

---

### User Story 2 - Workspace Symbol Search (Priority: P2)

The user can search for symbols such as classes, functions, and variables by name across the entire project.

**Why this priority**: This is essential for navigating large projects and is a foundation for global navigation.

**Independent Test**: Enter `@Foo` to trigger workspace symbol search and verify that matching classes/methods are returned.

**Acceptance Scenarios**:

1. **Given** the project contains multiple classes, **When** a `workspace/symbol` request with query="User" is sent, **Then** symbols whose names contain "User" are returned.
2. **Given** the query returns more than 100 results, **When** the request is sent, **Then** no more than 100 results are returned to avoid performance issues.
3. **Given** the user cancels the request, **When** `$/cancelRequest` is sent, **Then** the server stops searching.

---

### User Story 3 - Code Formatting (Priority: P2)

The user requests formatting for a document or selection, and the editor applies IntelliJ's code style.

**Why this priority**: This is essential for standardizing code style across a team.

**Independent Test**: Open a poorly formatted .kt file, send `textDocument/formatting`, and verify that the returned TextEdit[] corrects indentation and spacing.

**Acceptance Scenarios**:

1. **Given** the document contains unformatted code, **When** a `textDocument/formatting` request is sent, **Then** a TextEdit array is returned.
2. **Given** a code range is selected, **When** a `textDocument/rangeFormatting` request is sent, **Then** TextEdits are returned only for that range.
3. **Given** the document is already correctly formatted, **When** a formatting request is sent, **Then** an empty array is returned.

---

### User Story 4 - Code Actions (Priority: P3)

The user requests available quick fixes at a code location (QuickFix only; no Refactor/Source Actions).

**Why this priority**: Quick fixes improve efficiency. Phase 9 supports only QuickFix; Refactor and Source Actions are deferred to a later extension.

**Independent Test**: Send `textDocument/codeAction` on code with a warning (e.g. an unused variable) and verify that a fix is returned.

**Acceptance Scenarios**:

1. **Given** the code has a diagnostic issue, **When** a codeAction request is sent, **Then** a QuickFix associated with that diagnostic is returned.
2. **Given** the code has no issues, **When** a codeAction request is sent, **Then** a general refactoring suggestion (e.g. Extract Method) is returned.
3. **Given** the user selects a CodeAction, **When** a WorkspaceEdit is returned, **Then** the client can apply the change.

---

### User Story 5 - Go to Implementation (Priority: P3)

The user can navigate from an interface method to a method in a concrete implementing class.

**Why this priority**: This is a common navigation feature in object-oriented projects.

**Independent Test**: Send `textDocument/implementation` at an interface method declaration and verify that the implementation location is returned.

**Acceptance Scenarios**:

1. **Given** the cursor is on an interface method, **When** an implementation request is sent, **Then** locations for all classes implementing that method are returned.
2. **Given** the cursor is on a regular method (not an interface/abstract method), **When** a request is sent, **Then** an empty result or the method's own location is returned.
3. **Given** a Java interface has a Kotlin implementation, **When** a request is sent, **Then** the cross-language implementation is returned correctly.

---

### User Story 6 - Inlay Hints (Priority: P4)

The editor displays inlay hints such as inferred types and parameter names to improve code readability.

**Why this priority**: This visual aid is not essential but improves readability.

**Independent Test**: Open Kotlin code that uses type inference, send `textDocument/inlayHint`, and verify that type annotation hints are returned.

**Acceptance Scenarios**:

1. **Given** a variable uses type inference (`val x = foo()`), **When** an inlayHint request is sent, **Then** the type hint `: ReturnType` is returned.
2. **Given** a function call has multiple parameters, **When** a request is sent, **Then** parameter name hints such as `name:` and `age:` are returned.
3. **Given** the user disables a hint category, **When** the server applies the corresponding filter, **Then** hints in that category are omitted.

---

### Edge Cases

- **Missing Editor context**: Signature Help requires an Editor context; return an empty result rather than an error if one is unavailable.
- **Indexing incomplete**: Workspace Symbols depend on indexing; return empty or partial results while indexing is in progress.
- **Formatting conflict**: Use IntelliJ's default style if the user's project has no Code Style configuration.
- **CodeAction cannot be converted**: Some IntelliJ intentions directly modify PSI and cannot be converted to WorkspaceEdit; skip those actions.
- **Cross-language implementations**: Java ↔ Kotlin interoperability requires special handling.

## Requirements

### Functional Requirements

- **FR-001**: The server MUST declare the `signatureHelpProvider` capability and specify `(` and `,` as triggerCharacters.
- **FR-002**: The server MUST implement `textDocument/signatureHelp` and return a list of SignatureInformation.
- **FR-003**: The server MUST declare the `workspaceSymbolProvider` capability.
- **FR-004**: The server MUST implement `workspace/symbol`, return a list of SymbolInformation, and limit results to ≤100.
- **FR-005**: The server MUST declare the `documentFormattingProvider` capability.
- **FR-006**: The server MUST implement `textDocument/formatting` and return a TextEdit array.
- **FR-007**: The server SHOULD declare the `documentRangeFormattingProvider` capability and implement `textDocument/rangeFormatting`.
- **FR-008**: The server MUST declare the `codeActionProvider` capability.
- **FR-009**: The server MUST implement `textDocument/codeAction` and return QuickFix CodeActions (excluding Refactor/Source Actions).
  Actions）
- **FR-010**: The server MUST declare the `implementationProvider` capability.
- **FR-011**: The server MUST implement `textDocument/implementation` and return a list of Locations.
- **FR-012**: The server SHOULD declare the `inlayHintProvider` capability.
- **FR-013**: The server SHOULD implement `textDocument/inlayHint` and return a list of InlayHints.

### Key Entities

- **SignatureInformation**: Function signature information containing label, documentation, and parameters.
- **ParameterInformation**: Parameter information containing a label (parameter name) and optional documentation.
- **SymbolInformation**: Symbol information containing name, kind, location, and containerName.
- **TextEdit**: Text edit operation containing range and newText.
- **CodeAction**: Code action containing title, kind, and edit (WorkspaceEdit).
- **InlayHint**: Inlay hint containing position, label, and kind (Type/Parameter).

## Success Criteria

### Measurable Outcomes

- **SC-001**: Signature Help response latency is < 300ms (95th percentile).
- **SC-002**: Workspace Symbol search responds in < 2s in projects with 1,000+ symbols.
- **SC-003**: After applying formatting TextEdits, the code conforms to IntelliJ Code Style.
- **SC-004**: Code Actions include at least one QuickFix associated with the current diagnostic, when a diagnostic exists.
- **SC-005**: Go to Implementation correctly identifies cross-language Java/Kotlin implementations.
- **SC-006**: Inlay Hints cover common type inference and parameter name cases.

## Assumptions

- Core LSP features (Phases 1-8) and some abcoder compatibility features (Phase 10) are complete.
- IntelliJ platform APIs can provide Signature, Symbol, Formatting, and other information.
- Some features (such as CodeAction) may require a temporary Editor context.
