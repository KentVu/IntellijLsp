# Feature Specification: LSP Advanced Features (Phase 10)

**Feature Branch**: `003-lsp-advanced`  
**Created**: 2025-12-15  
**Status**: Draft  
**Input**: Phase 10 advanced features based on future-enhancements.md

## Implementation Notes

- **Bidirectional communication**: Extend JsonRpcHandler to support server → client requests.
- **K2 compatibility**: Inherit the Phase 9 declaration `<supportsKotlinPluginMode supportsK2="true">`.
- **Dependencies**: `com.intellij.java` (required), `org.jetbrains.kotlin` (optional).

## Clarifications

> [!NOTE]
> This specification is based on the Phase 10 plan in future-enhancements.md. Update this document if requirements change.

### Session 2025-12-15

- Q: What is the result limit for a single hierarchy request? → A: 100 items (matching workspace/symbol; LSP uses single-level lazy loading).
- Q: What is the fallback if the client does not support applyEdit? → A: Return WorkspaceEdit only; do not proactively send applyEdit.
- Q: Does Rename support external library symbols? → A: Project symbols only; prepareRename returns null for external library symbols.

---

## User Scenarios & Testing

### User Story 1 - Rename Refactoring (Priority: P1)

The user renames a selected symbol and safely updates all references across files.

**Why this priority**: Rename is one of the most common refactoring operations and directly affects everyday productivity.

**Independent Test**: Select a class name in a .kt file, send `textDocument/rename`, and verify that the returned WorkspaceEdit includes all reference locations.

**Acceptance Scenarios**:

1. **Given** the cursor is on the class name `Foo`, **When** a `textDocument/prepareRename` request is sent, **Then** a renameable Range and placeholder are returned.
2. **Given** the user confirms renaming `Foo` → `Bar`, **When** a `textDocument/rename` request is sent, **Then** a WorkspaceEdit containing changes for all references is returned.
3. **Given** another symbol already has the target name, **When** a rename request is sent, **Then** an error indicating a conflict is returned.
4. **Given** a Java class has Kotlin references, **When** a rename request is sent, **Then** the WorkspaceEdit includes cross-language references.

---

### User Story 2 - Call Hierarchy (Priority: P2)

The user can inspect the caller and callee hierarchy of a function.

**Why this priority**: Understanding call relationships is essential when analyzing complex systems.

**Independent Test**: Send `textDocument/prepareCallHierarchy` at a method declaration and verify that a CallHierarchyItem is returned.

**Acceptance Scenarios**:

1. **Given** the cursor is on `processData()`, **When** a `prepareCallHierarchy` request is sent, **Then** a CallHierarchyItem containing method information is returned.
2. **Given** a CallHierarchyItem is available, **When** `callHierarchy/incomingCalls` is requested, **Then** locations that call the method are returned.
3. **Given** a CallHierarchyItem is available, **When** `callHierarchy/outgoingCalls` is requested, **Then** the other methods called by the method are returned.
4. **Given** a method has no callers, **When** incomingCalls is requested, **Then** an empty array is returned.

---

### User Story 3 - Type Hierarchy (Priority: P2)

The user can inspect a class's inheritance relationships (superclasses and subclasses).

**Why this priority**: Understanding inheritance is a common need in object-oriented projects.

**Independent Test**: Send `textDocument/prepareTypeHierarchy` at a class declaration and verify that a TypeHierarchyItem is returned.

**Acceptance Scenarios**:

1. **Given** the cursor is on `UserService`, **When** a `prepareTypeHierarchy` request is sent, **Then** a TypeHierarchyItem containing class information is returned.
2. **Given** a TypeHierarchyItem is available, **When** `typeHierarchy/supertypes` is requested, **Then** the superclass/interface list is returned.
3. **Given** a TypeHierarchyItem is available, **When** `typeHierarchy/subtypes` is requested, **Then** the subclass list is returned.
4. **Given** an interface has multiple implementations, **When** subtypes is requested, **Then** all implementations are returned (limit ≤100).

---

### User Story 4 - Server-Initiated Edits (Priority: P2)

The server can send a `workspace/applyEdit` request to the client to apply a WorkspaceEdit.

**Why this priority**: This enables server-driven execution of CodeAction, Rename, and similar features.

**Independent Test**: The server sends a `workspace/applyEdit` request; the client applies the edit and returns a result.

**Acceptance Scenarios**:

1. **Given** the server needs to apply an automatic fix, **When** it sends `workspace/applyEdit`, **Then** the client returns `applied: true`.
2. **Given** the client rejects an edit, **When** it returns `applied: false`, **Then** the server handles the failure correctly.
3. **Given** a request times out, **When** the response wait exceeds 30 seconds, **Then** the server cancels the pending request and logs the event.

---

### User Story 5 - Multi-Root Workspaces (Priority: P3)

Support client notifications about workspace folder changes.

**Why this priority**: This supports monorepos and multi-module projects.

**Independent Test**: Send a `workspace/didChangeWorkspaceFolders` notification and verify that the server updates its workspace scope.

**Acceptance Scenarios**:

1. **Given** `workspaceFolders` contains two directories during initialization, **When** initialize completes, **Then** the server records all workspace paths.
2. **Given** the client adds a workspace, **When** it sends `didChangeWorkspaceFolders`, **Then** the server updates the workspace list.
3. **Given** the client removes a workspace, **When** it sends `didChangeWorkspaceFolders`, **Then** the server removes it from the list.

---

### User Story 6 - File Watching (Priority: P3)

The server responds to filesystem changes reported by the client.

**Why this priority**: This refreshes state after external tools modify files.

**Independent Test**: Send a `workspace/didChangeWatchedFiles` notification and verify that the server refreshes the VirtualFile.

**Acceptance Scenarios**:

1. **Given** an external tool creates a file, **When** the client sends `didChangeWatchedFiles` (Created), **Then** the server refreshes the VirtualFileSystem.
2. **Given** an external tool modifies a file, **When** the client sends `didChangeWatchedFiles` (Changed), **Then** the server refreshes diagnostics.
3. **Given** an external tool deletes a file, **When** the client sends `didChangeWatchedFiles` (Deleted), **Then** the server cleans up related state.

---

### Edge Cases

- **Rename unavailable**: If a symbol cannot be renamed (e.g. a built-in type), `prepareRename` should return null.
- **Hierarchy cycles**: Prevent circular references from causing infinite recursion when processing type hierarchies.
- **Bidirectional communication timeout**: Pending requests need timeouts to prevent memory leaks.
- **Empty Workspace Folders**: The client may not support workspaceFolders; fall back to rootUri.
- **Concurrent requests**: Queue multiple applyEdit requests to avoid conflicts.

## Requirements

### Functional Requirements

- **FR-001**: The server MUST declare the `renameProvider` and `prepareProvider` capabilities.
- **FR-002**: The server MUST implement `textDocument/prepareRename` and return a renameable range.
- **FR-003**: The server MUST implement `textDocument/rename` and return a WorkspaceEdit.
- **FR-004**: The server MUST declare the `callHierarchyProvider` capability.
- **FR-005**: The server MUST implement `textDocument/prepareCallHierarchy` and return a CallHierarchyItem.
- **FR-006**: The server MUST implement `callHierarchy/incomingCalls` and `callHierarchy/outgoingCalls`.
- **FR-007**: The server MUST declare the `typeHierarchyProvider` capability.
- **FR-008**: The server MUST implement `textDocument/prepareTypeHierarchy` and return a TypeHierarchyItem.
- **FR-009**: The server MUST implement `typeHierarchy/supertypes` and `typeHierarchy/subtypes`.
- **FR-010**: The server MUST support sending `workspace/applyEdit` requests and handling responses.
- **FR-011**: The server MUST track pending requests and support timeouts/cancellation.
- **FR-012**: The server SHOULD declare the `workspaceFolders` capability.
- **FR-013**: The server SHOULD implement the `workspace/didChangeWorkspaceFolders` handler.
- **FR-014**: The server SHOULD implement the `workspace/didChangeWatchedFiles` handler.

### Key Entities

- **PrepareRenameResult**: Rename preparation result containing range and placeholder.
- **RenameParams**: Rename parameters containing textDocument, position, and newName.
- **CallHierarchyItem**: Call hierarchy item containing name, kind, uri, range, and selectionRange.
- **CallHierarchyIncomingCall**: Incoming call containing from (CallHierarchyItem) and fromRanges.
- **CallHierarchyOutgoingCall**: Outgoing call containing to (CallHierarchyItem) and fromRanges.
- **TypeHierarchyItem**: Type hierarchy item containing name, kind, uri, range, and selectionRange.
- **WorkspaceFolder**: Workspace folder containing uri and name.
- **FileEvent**: File event containing uri and type (Created/Changed/Deleted).
- **ApplyWorkspaceEditParams**: Apply-edit parameters containing label and edit.
- **ApplyWorkspaceEditResult**: Apply-edit result containing applied and optional failureReason.

## Success Criteria

### Measurable Outcomes

- **SC-001**: Applying a returned Rename WorkspaceEdit does not break compilation.
- **SC-002**: Call Hierarchy correctly identifies cross-language Java/Kotlin calls.
- **SC-003**: Type Hierarchy correctly displays multi-level inheritance.
- **SC-004**: ApplyEdit timeouts (>30s) clean up resources correctly.
- **SC-005**: The diagnostics scope updates correctly after Workspace Folders change.
- **SC-006**: VirtualFile state remains consistent after File Watching is triggered.

## Assumptions

- Phase 9 (Extended Features) is complete and stable.
- IntelliJ Platform APIs support Rename, CallHierarchy, and TypeHierarchy queries.
- Clients support `workspace/applyEdit` requests (both Neovim and VS Code).
