# Research: LSP Advanced Features (Phase 10)

**Feature**: 003-lsp-advanced
**Date**: 2025-12-15
**Status**: Complete

## LSP Specification Research

### 1. Rename Refactoring

**Protocol methods**:

- `textDocument/prepareRename` - Preparation request to check whether a symbol can be renamed.
- `textDocument/rename` - Perform the rename.

**Key interfaces** (LSP 3.17):

```typescript
// prepareRename response
interface PrepareRenameResult {
  range: Range;
  placeholder: string;
}

// rename request parameters
interface RenameParams extends TextDocumentPositionParams {
  newName: string;
}

// rename response
type RenameResult = WorkspaceEdit | null;
```

**Server Capability**:

- `renameProvider: boolean | RenameOptions`
- `RenameOptions.prepareProvider: boolean` indicates support for prepareRename.

**Decision**:

- Support project symbols only (prepareRename returns null for external library symbols).
- Use the IntelliJ `RefactoringElementController` API.

---

### 2. Call Hierarchy

**Protocol methods**:

- `textDocument/prepareCallHierarchy` - Prepare a call hierarchy item.
- `callHierarchy/incomingCalls` - Get callers (who calls this method).
- `callHierarchy/outgoingCalls` - Get callees (which methods this method calls).

**Key interfaces** (LSP 3.17):

```typescript
interface CallHierarchyItem {
  name: string;
  kind: SymbolKind;
  tags?: SymbolTag[];
  detail?: string;
  uri: DocumentUri;
  range: Range;
  selectionRange: Range;
  data?: LSPAny;
}

interface CallHierarchyIncomingCall {
  from: CallHierarchyItem;
  fromRanges: Range[];
}

interface CallHierarchyOutgoingCall {
  to: CallHierarchyItem;
  fromRanges: Range[];
}
```

**Design decisions**:

- LSP uses single-level lazy loading; the server returns one level per request.
- Limit each request to 100 results.
- Use the IntelliJ `CallHierarchyBrowser` API.

---

### 3. Type Hierarchy

**Protocol methods**:

- `textDocument/prepareTypeHierarchy` - Prepare a type hierarchy item.
- `typeHierarchy/supertypes` - Get supertypes.
- `typeHierarchy/subtypes` - Get subtypes.

**Key interfaces** (LSP 3.17):

```typescript
interface TypeHierarchyItem {
  name: string;
  kind: SymbolKind;
  tags?: SymbolTag[];
  detail?: string;
  uri: DocumentUri;
  range: Range;
  selectionRange: Range;
  data?: LSPAny;
}
```

**Design decisions**:

- As with Call Hierarchy, use single-level lazy loading.
- Limit each request to 100 results.
- Use the IntelliJ `TypeHierarchyBrowser` API.

---

### 4. Server-Initiated Edits (workspace/applyEdit)

**Protocol methods**:

- `workspace/applyEdit` - Server → Client request.

**Key interfaces** (LSP 3.17):

```typescript
interface ApplyWorkspaceEditParams {
  label?: string;
  edit: WorkspaceEdit;
}

interface ApplyWorkspaceEditResult {
  applied: boolean;
  failureReason?: string;
  failedChange?: uinteger;
}
```

**Design decisions**:

- Extend `JsonRpcHandler` to support bidirectional communication.
- Detect support through `clientCapabilities.workspace.applyEdit`.
- If unsupported, return WorkspaceEdit only; do not proactively send applyEdit.
- Set a 30-second timeout.
- Queue pending requests.

---

### 5. Workspace Folders

**Protocol methods**:

- `workspace/didChangeWorkspaceFolders` - Notification (Client → Server).

**Key interfaces**:

```typescript
interface DidChangeWorkspaceFoldersParams {
  event: WorkspaceFoldersChangeEvent;
}

interface WorkspaceFoldersChangeEvent {
  added: WorkspaceFolder[];
  removed: WorkspaceFolder[];
}
```

**Design decisions**:

- Maintain `workspaceFolders: List<WorkspaceFolder>` state.
- Update the diagnostics scope.

---

### 6. File Watching

**Protocol methods**:

- `workspace/didChangeWatchedFiles` - Notification (Client → Server).

**Key interfaces**:

```typescript
interface DidChangeWatchedFilesParams {
  changes: FileEvent[];
}

interface FileEvent {
  uri: DocumentUri;
  type: FileChangeType; // Created=1, Changed=2, Deleted=3
}
```

**Design decisions**:

- Refresh file state through `VirtualFileSystem.refresh()`.
- Trigger diagnostic recalculation.

---

## IntelliJ Platform API Research (Official Documentation)

**Source**: [IntelliJ SDK - Rename Refactoring](https://plugins.jetbrains.com/docs/intellij/rename-refactoring.html)

### Rename API

- `PsiNamedElement.setName()` - Rename the element itself.
- `PsiReference.handleElementRename()` - Update all references.
- `ReferencesSearch.search()` - Find all references (already used in this project).
- `RenamePsiElementProcessor` - Customize rename behavior.

### Call Hierarchy API

Follow the existing project pattern using `ReferencesSearch` and `PsiMethod` analysis:

- Callers (incomingCalls): Search method references and get the parent `PsiMethod` at each call site.
- Callees (outgoingCalls): Traverse `PsiMethodCallExpression` nodes in the method body.

### Type Hierarchy API

Use the PSI class hierarchy directly:

- Supertypes: `PsiClass.superClass` + `PsiClass.interfaces`.
- Subtypes: `ClassInheritorsSearch.search()`.

---

## Code Pattern Analysis

### Existing Handler Pattern

```kotlin
class XxxHandler(private val project: Project, private val jsonRpcHandler: JsonRpcHandler) {
    fun register() {
        jsonRpcHandler.registerRequestHandler("method", this::handleMethod)
    }

    private fun handleMethod(params: JsonElement?): JsonElement? {
        // 1. Parse parameters.
        // 2. Call the Provider.
        // 3. Return the result.
    }
}
```

### Existing Provider Pattern

```kotlin
class XxxProvider(private val project: Project) {
    fun getXxx(file: PsiFile, position: Position): Result? = runReadAction {
        // Use the IntelliJ Platform API.
    }
}
```

---

## Clarifications (from spec.md)

| Question           | Answer                         |
|------------------|------------------------------|
| Hierarchy result limit | 100 items (matching workspace/symbol) |
| Client does not support applyEdit | Return WorkspaceEdit only; do not send applyEdit proactively |
| Rename of external library symbols | Support project symbols only |
