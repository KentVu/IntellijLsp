# Tasks: LSP Advanced Features (Phase 10)

**Input**: Design documents from `/.specify/specs/003-lsp-advanced/`
**Prerequisites**: plan.md ✅, spec.md ✅, research.md ✅

**Tests**: Unit tests are included; use the Python script (`tools/lsp_test_client.py`) for E2E tests.

**Organization**: Tasks are organized by user story; each story can be implemented and tested independently.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can be performed in parallel (different files, no dependencies).
- **[Story]**: Associated user story (US1-US6).
- Includes exact file paths.

---

## Phase 1: Setup (Data Type Extensions)

**Purpose**: Define the LSP data types required for Phase 10.

- [ ] T001 [P] Add Rename data types to `src/main/kotlin/.../protocol/models/LspTypes.kt`.
    - `PrepareRenameParams`, `PrepareRenameResult`, `RenameParams`, `RenameOptions`
- [ ] T002 [P] Add Call Hierarchy data types to `src/main/kotlin/.../protocol/models/LspTypes.kt`.
    - `CallHierarchyPrepareParams`, `CallHierarchyItem`, `CallHierarchyIncomingCall`, `CallHierarchyOutgoingCall`
- [ ] T003 [P] Add Type Hierarchy data types to `src/main/kotlin/.../protocol/models/LspTypes.kt`.
    - `TypeHierarchyPrepareParams`, `TypeHierarchyItem`
- [ ] T004 [P] Add ApplyEdit data types to `src/main/kotlin/.../protocol/models/LspTypes.kt`.
    - `ApplyWorkspaceEditParams`, `ApplyWorkspaceEditResult`
- [ ] T005 [P] Add Workspace/File data types to `src/main/kotlin/.../protocol/models/LspTypes.kt`.
    - `DidChangeWorkspaceFoldersParams`, `DidChangeWatchedFilesParams`, `FileEvent`, `FileChangeType`
- [ ] T006 Update `ServerCapabilities` to add new capability fields.
    - `callHierarchyProvider`, `typeHierarchyProvider`, `workspace`

---

## Phase 2: Foundational (Bidirectional Communication Infrastructure)

**Purpose**: Core infrastructure for Server→Client requests.

**⚠️ CRITICAL**: User Story 4 (Server-Initiated Edits) depends on this phase.

- [ ] T007 Create `PendingRequestManager.kt` in `src/main/kotlin/.../protocol/`.
    - Manage the pending request map.
    - Implement a 30s timeout.
    - Generate request IDs.
- [ ] T008 Extend `JsonRpcHandler.kt` to support bidirectional communication.
    - Add a `sendRequest(method, params)` method.
    - Add `handleResponse(id, json)` to handle responses.
    - Integrate `PendingRequestManager`.
- [ ] T009 [P] Create `PendingRequestManagerTest.kt` in `src/test/kotlin/.../protocol/`.
    - Test request creation, completion, and timeouts.

**Checkpoint**: Bidirectional communication infrastructure is ready.

---

## Phase 3: User Story 1 - Rename Refactoring (Priority: P1) 🎯 MVP

**Goal**: The user renames a selected symbol and safely updates all references across files.

**Independent Test**: Select a class name in a .kt/.java file and send `textDocument/prepareRename`; verify the returned range and placeholder. Then send `textDocument/rename` and verify that the WorkspaceEdit contains all reference locations.

### Implementation for User Story 1

- [ ] T010 [P] [US1] Create `RenameProvider.kt` in `src/main/kotlin/com/frenchef/intellijlsp/intellij/`.
    - `prepareRename(file, position)` - Check whether renaming is allowed and return range/placeholder.
    - `rename(file, position, newName)` - Perform the rename and return a WorkspaceEdit.
    - Use `ReferencesSearch.search()` to find references.
    - Filter out external library symbols (return null).
    - **Detect name conflicts and return an error.**
- [ ] T011 [US1] Create `RenameHandler.kt` in `src/main/kotlin/com/frenchef/intellijlsp/handlers/`.
    - Register the `textDocument/prepareRename` handler.
    - Register the `textDocument/rename` handler.
    - Parse parameters and call RenameProvider.
- [ ] T012 [US1] Update `LifecycleHandler.kt` to enable the `renameProvider` capability.
    - `renameProvider = RenameOptions(prepareProvider = true)`
- [ ] T013 [US1] Update `LspClientConnection.kt` to register RenameHandler.
- [ ] T014 [P] [US1] Create `RenameHandlerTest.kt` in `src/test/kotlin/com/frenchef/intellijlsp/handlers/`.
    - Test that prepareRename returns the correct format.
    - Test that rename returns a WorkspaceEdit.
    - **Test detection of name conflicts.**

**Checkpoint**: Rename can be tested independently (`tools/lsp_test_client.py`).

---

## Phase 4: User Story 2 - Call Hierarchy (Priority: P2)

**Goal**: The user can inspect the caller and callee hierarchy of a function.

**Independent Test**: Send `textDocument/prepareCallHierarchy` at a method declaration and verify that a CallHierarchyItem is returned; then send `callHierarchy/incomingCalls` and verify the caller list.

### Implementation for User Story 2

- [ ] T015 [P] [US2] Create `CallHierarchyProvider.kt` in `src/main/kotlin/com/frenchef/intellijlsp/intellij/`.
    - `prepareCallHierarchy(file, position)` - Return a list of CallHierarchyItems.
    - `getIncomingCalls(item)` - Use ReferencesSearch to find callers.
    - `getOutgoingCalls(item)` - Traverse the method body to find callees.
    - Limit the result count to 100.
    - **Detect recursive calls to prevent infinite loops.**
- [ ] T016 [US2] Create `CallHierarchyHandler.kt` in `src/main/kotlin/com/frenchef/intellijlsp/handlers/`.
    - Register the `textDocument/prepareCallHierarchy` handler.
    - Register the `callHierarchy/incomingCalls` handler.
    - Register the `callHierarchy/outgoingCalls` handler.
- [ ] T017 [US2] Update `LifecycleHandler.kt` to enable the `callHierarchyProvider` capability.
- [ ] T018 [US2] Update `LspClientConnection.kt` to register CallHierarchyHandler.
- [ ] T019 [P] [US2] Create `CallHierarchyHandlerTest.kt` in `src/test/kotlin/.../handlers/`.

**Checkpoint**: Call Hierarchy can be tested independently.

---

## Phase 5: User Story 3 - Type Hierarchy (Priority: P2)

**Goal**: The user can inspect a class's inheritance relationships (superclasses and subclasses).

**Independent Test**: Send `textDocument/prepareTypeHierarchy` at a class declaration and verify that a TypeHierarchyItem is returned; then send `typeHierarchy/supertypes` and verify the superclass list.

### Implementation for User Story 3

- [ ] T020 [P] [US3] Create `TypeHierarchyProvider.kt` in `src/main/kotlin/com/frenchef/intellijlsp/intellij/`.
    - `prepareTypeHierarchy(file, position)` - Return a list of TypeHierarchyItems.
    - `getSupertypes(item)` - Use PsiClass.superClass/interfaces.
    - `getSubtypes(item)` - Use ClassInheritorsSearch.search().
    - Limit the result count to 100.
    - **Detect inheritance cycles with a visited set to prevent infinite recursion.**
- [ ] T021 [US3] Create `TypeHierarchyHandler.kt` in `src/main/kotlin/com/frenchef/intellijlsp/handlers/`.
    - Register the `textDocument/prepareTypeHierarchy` handler.
    - Register the `typeHierarchy/supertypes` handler.
    - Register the `typeHierarchy/subtypes` handler.
- [ ] T022 [US3] Update `LifecycleHandler.kt` to enable the `typeHierarchyProvider` capability.
- [ ] T023 [US3] Update `LspClientConnection.kt` to register TypeHierarchyHandler.
- [ ] T024 [P] [US3] Create `TypeHierarchyHandlerTest.kt` in `src/test/kotlin/.../handlers/`.

**Checkpoint**: Type Hierarchy can be tested independently.

---

## Phase 6: User Story 4 - Server-Initiated Edits (Priority: P2)

**Goal**: The server can send `workspace/applyEdit` requests to the client to apply WorkspaceEdits.

**Independent Test**: Call the server's internal applyEdit method and verify the request format; simulate a client response and verify result handling.

### Implementation for User Story 4

- [ ] T025 [US4] Create `ApplyEditService.kt` in `src/main/kotlin/.../services/`.
    - `applyEdit(label, edit)` - Send a workspace/applyEdit request.
    - Handle the ApplyWorkspaceEditResult response.
    - Check whether clientCapabilities.workspace.applyEdit is supported.
    - If unsupported, return null without sending a request.
- [ ] T026 [US4] Integrate ApplyEditService into RenameHandler (optional server-driven mode).
- [ ] T027 [P] [US4] Create `ApplyEditServiceTest.kt` in `src/test/kotlin/.../services/`.

**Checkpoint**: ApplyEdit service can be tested independently.

---

## Phase 7: User Story 5 - Multi-Root Workspaces (Priority: P3)

**Goal**: Support client notifications about workspace folder changes.

**Independent Test**: Send a `workspace/didChangeWorkspaceFolders` notification and verify that the server updates its workspace list.

### Implementation for User Story 5

- [ ] T028 [P] [US5] Create `WorkspaceFoldersHandler.kt` in `src/main/kotlin/.../handlers/`.
    - Maintain `workspaceFolders: List<WorkspaceFolder>` state.
    - Handle the `workspace/didChangeWorkspaceFolders` notification.
    - Update added/removed workspaces.
- [ ] T029 [US5] Update `LifecycleHandler.kt` to enable the workspace.workspaceFolders capability.
    - Add `WorkspaceCapabilities` and `WorkspaceFoldersServerCapabilities` to LspTypes.
- [ ] T030 [US5] Update `LspClientConnection.kt` to register WorkspaceFoldersHandler.

**Checkpoint**: Workspace Folders can be tested independently.

---

## Phase 8: User Story 6 - File Watching (Priority: P3)

**Goal**: The server responds to filesystem changes reported by the client.

**Independent Test**: Send a `workspace/didChangeWatchedFiles` notification and verify that the server refreshes the VirtualFile.

### Implementation for User Story 6

- [ ] T031 [P] [US6] Create `FileWatchingHandler.kt` in `src/main/kotlin/.../handlers/`.
    - Handle the `workspace/didChangeWatchedFiles` notification.
    - Perform the appropriate action based on FileChangeType.
    - Created: VirtualFileSystem.refresh()
-    - Changed: Refresh diagnostics.
-    - Deleted: Clean up related state.
- [ ] T032 [US6] Update `LspClientConnection.kt` to register FileWatchingHandler.

**Checkpoint**: File Watching can be tested independently.

---

## Phase 9: Polish & Cross-Cutting Concerns

**Purpose**: Final polish and integration verification.

- [ ] T033 [P] Update `README.md` with documentation for Phase 10 features.
- [ ] T034 [P] Update `SPEC.md` with Phase 10 capabilities.
- [ ] T035 Run `./gradlew build` to verify compilation.
- [ ] T036 Run `./gradlew test` to verify all tests pass.
- [ ] T037 Perform E2E verification with `tools/lsp_test_client.py`.

---

## Dependencies & Execution Order

### Phase Dependencies

- **Phase 1 (Setup)**: No dependencies; can start immediately.
- **Phase 2 (Foundational)**: Depends on Phase 1; blocks US4.
- **Phases 3-6 (US1-US4)**: Depend on Phase 1; US4 also depends on Phase 2.
- **Phases 7-8 (US5-US6)**: Depend on Phase 1.
- **Phase 9 (Polish)**: Depends on completing all user stories.

### User Story Dependencies

| Story                   | Depends On       | Can Parallel With       |
|-------------------------|------------------|-------------------------|
| US1 (Rename)            | Phase 1          | US2, US3, US5, US6      |
| US2 (Call Hierarchy)    | Phase 1          | US1, US3, US5, US6      |
| US3 (Type Hierarchy)    | Phase 1          | US1, US2, US5, US6      |
| US4 (ApplyEdit)         | Phase 1, Phase 2 | US5, US6                |
| US5 (Workspace Folders) | Phase 1          | US1, US2, US3, US4, US6 |
| US6 (File Watching)     | Phase 1          | US1, US2, US3, US4, US5 |

### Parallel Opportunities

```text
# Phase 1 can run fully in parallel (T001-T005).
Task: T001 [P] Rename types
Task: T002 [P] Call Hierarchy types
Task: T003 [P] Type Hierarchy types
Task: T004 [P] ApplyEdit types
Task: T005 [P] Workspace/File types

# Phase 3 tasks can run in parallel.
Task: T010 [P] RenameProvider
Task: T014 [P] RenameHandlerTest

# US1-US3 can be implemented fully in parallel.
Task: Phase 3 (US1)
Task: Phase 4 (US2)
Task: Phase 5 (US3)
```

---

## Implementation Strategy

### MVP First (User Story 1 Only)

1. Complete Phase 1: Setup (T001-T006)
2. Complete Phase 3: User Story 1 - Rename (T010-T014)
3. **STOP and VALIDATE**: Test Rename with `lsp_test_client.py`.
4. Demo MVP

### Incremental Delivery

```text
Setup (T001-T006) → US1 Rename → US2 Call Hierarchy → US3 Type Hierarchy
                  → Phase 2 Foundational → US4 ApplyEdit
                  → US5 Workspace Folders → US6 File Watching
                  → Polish
```

---

## Summary

| Metric                       | Count    |
|------------------------------|----------|
| Total Tasks                  | 37       |
| Phase 1 (Setup)              | 6        |
| Phase 2 (Foundational)       | 3        |
| US1 (Rename) - P1            | 5        |
| US2 (Call Hierarchy) - P2    | 5        |
| US3 (Type Hierarchy) - P2    | 5        |
| US4 (ApplyEdit) - P2         | 3        |
| US5 (Workspace Folders) - P3 | 3        |
| US6 (File Watching) - P3     | 2        |
| Phase 9 (Polish)             | 5        |
| Parallelizable Tasks         | 17 (46%) |
