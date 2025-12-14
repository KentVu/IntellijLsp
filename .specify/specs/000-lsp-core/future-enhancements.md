# Future Enhancements: IntelliJ LSP Core

**Status**: Planned
**Prerequisites**: Implement after the core features (Phases 1-8) and abcoder compatibility (Phase 10) are complete.

This document records the detailed plans for the Phase 9-10 extensions from the original IMPLEMENTATION_PLAN.md.

---

## Phase 9: Extended Features

### 9.1 Workspace Symbols (`workspace/symbol`)

**Goal**: Search for symbols across the workspace.

**Tasks**:

- [ ] Update `LspTypes.kt`: add `WorkspaceSymbolParams` and `SymbolInformation` models.
- [ ] Update `LifecycleHandler`: declare `workspaceSymbolProvider = true`.
- [ ] Create `intellij/WorkspaceSymbolProvider.kt`:
  - Search for symbols by query using IntelliJ indexing/search capabilities.
  - Limit the number of results (e.g. 100) and support cancellation to avoid hangs.
- [ ] Create `handlers/WorkspaceSymbolHandler.kt`.
- [ ] Register the handler.

**Implementation Notes**:

- Prefer IntelliJ platform indexes over traversing PSI.
- Use conservative mappings across languages (class/function/variable/module).

---

### 9.2 Go to Implementation (`textDocument/implementation`)

**Goal**: Navigate to implementations of interfaces and abstract methods.

**Tasks**:

- [ ] Update `LspTypes.kt`: add `implementationProvider`.
- [ ] Update `LifecycleHandler`: declare the capability.
- [ ] Create `handlers/ImplementationHandler.kt`:
  - Reuse IntelliJ's inheritance/implementation search capabilities.
  - Ensure Java ↔ Kotlin interoperability.

---

### 9.3 Code Formatting (`textDocument/formatting` / `rangeFormatting`)

**Goal**: Provide formatting edits without directly modifying files.

**Tasks**:

- [ ] Update `LspTypes.kt`: add `DocumentFormattingParams` and `FormattingOptions`.
- [ ] Update `LifecycleHandler`: declare formatting providers.
- [ ] Create `intellij/FormattingProvider.kt`:
  - Format a copy or temporary PSI.
  - Compute the diff and return `TextEdit[]`.
- [ ] Create `handlers/DocumentFormattingHandler.kt`.
- [ ] Create `handlers/DocumentRangeFormattingHandler.kt` (optional).

**Implementation Notes**:

- Document synchronization flows from client to server; the client applies the formatting edits returned by the server.
- Server-driven edits could be supported later through `workspace/applyEdit`.

---

### 9.4 Code Actions (`textDocument/codeAction`)

**Goal**: Expose IntelliJ quick fixes and intentions.

**Tasks**:

- [ ] Update `LspTypes.kt`: add `CodeActionParams`, `CodeAction`, and `Command`.
- [ ] Update `LifecycleHandler`: declare `codeActionProvider`.
- [ ] Create `intellij/CodeActionProvider.kt`:
  - Collect candidate actions for the current range.
  - Initially, provide only actions that can be safely converted to a `WorkspaceEdit`.
- [ ] Create `handlers/CodeActionHandler.kt`.
- [ ] Create `handlers/ExecuteCommandHandler.kt` (optional).

**Implementation Notes**:

- Challenge: IntelliJ intentions often directly modify PSI as write operations.
- Prefer implementing actions that can be converted into a `WorkspaceEdit`.

---

### 9.5 Signature Help (`textDocument/signatureHelp`)

**Goal**: Display function parameter information.

**Tasks**:

- [ ] Update `LspTypes.kt`: add `SignatureHelp`, `SignatureInformation`, and `ParameterInformation`.
- [ ] Update `LifecycleHandler`: declare `signatureHelpProvider` and triggerCharacters.
- [ ] Create `intellij/SignatureHelpProvider.kt`:
  - Reuse IntelliJ's parameter information capabilities (an Editor context may be required).
- [ ] Create `handlers/SignatureHelpHandler.kt`.

**Implementation Notes**:

- If an Editor is required, create a temporary one and ensure it is disposed.

---

### 9.6 Inlay Hints (`textDocument/inlayHint`)

**Goal**: Display type and parameter name hints.

**Tasks**:

- [ ] Update `LspTypes.kt`: add `InlayHint` and `InlayHintLabelPart`.
- [ ] Update `LifecycleHandler`: declare `inlayHintProvider`.
- [ ] Create `intellij/InlayHintsProvider.kt`:
  - Reuse IntelliJ's computed inlay hints.
- [ ] Create `handlers/InlayHintsHandler.kt`.

**Implementation Notes**:

- Language plugin implementations vary considerably; start with common Java/Kotlin hints.

---

## Phase 10: Advanced Features

### 10.1 Bidirectional Requests: Server-Initiated Requests

**Goal**: Support server → client requests (such as `workspace/applyEdit`).

**Tasks**:

- [ ] Extend `JsonRpcHandler` to dispatch responses (messages without a method).
- [ ] Track pending requests (id → deferred) and support timeouts/cancellation.
- [ ] Provide `sendRequest()` at the TCP/UDS connection layer and await the response.
- [ ] Add a `workspace/applyEdit` helper.

---

### 10.2 Workspace Folders (`workspace/didChangeWorkspaceFolders`)

**Goal**: Support multi-root workspaces.

**Tasks**:

- [ ] Update `LspTypes.kt`: add `WorkspaceFolder` and `DidChangeWorkspaceFoldersParams`.
- [ ] Update `LifecycleHandler`: support `workspaceFolders` in `initialize` and adjust root validation.
- [ ] Create `handlers/WorkspaceFoldersHandler.kt`.

---

### 10.3 File Watching (`workspace/didChangeWatchedFiles`)

**Goal**: Respond to external file changes.

**Tasks**:

- [ ] Update `LspTypes.kt`: add `DidChangeWatchedFilesParams` and `FileEvent`.
- [ ] Create `handlers/WatchedFilesHandler.kt`: refresh the VirtualFile and trigger diagnostics.

---

### 10.4 Rename Refactoring (`textDocument/rename`)

**Goal**: Rename symbols across the project.

**Tasks**:

- [ ] Update `LspTypes.kt`: add `PrepareRenameParams` and `RenameParams`.
- [ ] Update `LifecycleHandler`: declare `renameProvider` and `prepareProvider`.
- [ ] Create `intellij/RenameProvider.kt`:
  - Generate a `WorkspaceEdit` using IntelliJ refactoring capabilities.
- [ ] Create `handlers/PrepareRenameHandler.kt`.
- [ ] Create `handlers/RenameHandler.kt`.

**Implementation Notes**:

- Correctness is more important than text replacement; IntelliJ refactoring capabilities must be reused.

---

### 10.5 Call Hierarchy (`textDocument/prepareCallHierarchy`)

**Goal**: Navigate caller/callee relationships.

**Tasks**:

- [ ] Update `LspTypes.kt`: add `CallHierarchyItem` and incoming/outgoing call parameters.
- [ ] Update `LifecycleHandler`: declare `callHierarchyProvider`.
- [ ] Create `intellij/CallHierarchyProvider.kt`.
- [ ] Create `handlers/CallHierarchyHandler.kt`.

---

### 10.6 Type Hierarchy (`textDocument/prepareTypeHierarchy`)

**Goal**: Navigate type inheritance relationships.

**Tasks**:

- [ ] Update `LspTypes.kt`: add `TypeHierarchyItem` and supertype/subtype parameters.
- [ ] Update `LifecycleHandler`: declare `typeHierarchyProvider`.
- [ ] Create `intellij/TypeHierarchyProvider.kt`:
  - Supertypes: enumerate the inheritance chain upward.
  - Subtypes: find subclasses (observe performance limits).
- [ ] Create `handlers/TypeHierarchyHandler.kt`.

---

## Implementation Priority

Prioritized based on user needs and impact:

1. **P1 - Signature Help**: Significantly improves the coding experience.
2. **P2 - Workspace Symbols**: Essential for navigation in large projects.
3. **P2 - Code Formatting**: Standardizes team collaboration.
4. **P3 - Code Actions**: Improves efficiency with quick fixes.
5. **P3 - Go to Implementation**: Commonly used in object-oriented projects.
6. **P4 - Rename**: Supports refactoring, but carries higher risk.
7. **P4 - Inlay Hints**: Visual assistance (optional).
8. **P5 - Call/Type Hierarchy**: Useful for architecture analysis.

---

## Notes

- All extension features marked `[ ]` are not yet implemented.
- Add them incrementally after the core features are stable.
- Prioritize features most requested by users.
