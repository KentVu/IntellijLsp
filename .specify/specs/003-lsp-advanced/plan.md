# Implementation Plan: LSP Advanced Features (Phase 10)

**Branch**: `003-lsp-advanced` | **Date**: 2025-12-15 | **Spec
**: [spec.md](file:///f:/code/env/IntellijLsp/.specify/specs/003-lsp-advanced/spec.md)

## Summary

Implement Phase 10 LSP advanced features: Rename Refactoring, Call Hierarchy, Type Hierarchy, Server-Initiated Edits, Workspace Folders, and File Watching. Reuse native IntelliJ Platform SDK capabilities and extend the existing Handler/Provider pattern.

## Technical Context

| Project                | Value                                  |
|----------------------|---------------------------------------|
| Language/Version     | Kotlin 2.1.0, JVM 21                  |
| Primary Dependencies | IntelliJ Platform SDK 2024.2, Gson    |
| Testing              | JUnit 5, IntelliJ Test Framework      |
| Target Platform      | IntelliJ IDEA 2024.2+                 |
| Performance Goals    | P95 response latency < 500ms under standard load |
| Constraints          | PSI access runs in a ReadAction; applyEdit timeout is 30s |

## Constitution Check

| Principle                     | Status | Notes                            |
|-------------------------------|--------|----------------------------------|
| I. Platform Integration First | ✅      | Reuse IntelliJ Rename/Hierarchy APIs |
| II. Protocol Conformance      | ✅      | Follow the LSP 3.17 specification |
| III. Project Isolation        | ✅      | Independent handler instance per project |
| IV. Graceful Degradation      | ✅      | prepareRename returns null when renaming is unavailable |
| V. Observability              | ✅      | Structured logs + status bar widget |

---

## Proposed Changes

### Component 1: LspTypes.kt - Data Type Extensions

#### [MODIFY] [LspTypes.kt](file:///f:/code/env/IntellijLsp/src/main/kotlin/com/frenchef/intellijlsp/protocol/models/LspTypes.kt)

Add the new Phase 10 types at the end of the file (about 100 lines):

- `PrepareRenameParams`, `PrepareRenameResult`, `RenameParams`, `RenameOptions`
- `CallHierarchyPrepareParams`, `CallHierarchyItem`, `CallHierarchyIncomingCall/OutgoingCall`
- `TypeHierarchyPrepareParams`, `TypeHierarchyItem`
- `ApplyWorkspaceEditParams`, `ApplyWorkspaceEditResult`
- `DidChangeWorkspaceFoldersParams`, `DidChangeWatchedFilesParams`, `FileEvent`

#### [MODIFY] [ServerCapabilities](file:///f:/code/env/IntellijLsp/src/main/kotlin/com/frenchef/intellijlsp/protocol/models/LspTypes.kt#L198-L221)

Add the new capabilities:

```diff
+val callHierarchyProvider: Boolean?
+val typeHierarchyProvider: Boolean?
+val workspace: WorkspaceCapabilities?
```

---

### Component 2: JsonRpcHandler Bidirectional Communication

#### [NEW] [PendingRequestManager.kt](file:///f:/code/env/IntellijLsp/src/main/kotlin/com/frenchef/intellijlsp/protocol/PendingRequestManager.kt)

Manage the pending state of server→client requests:

- `createRequest(id)` - Create a CompletableFuture.
- `completeRequest(id, result)` - Complete the request.
- `failRequest(id, error)` - Fail the request.
- Automatically clean up after a 30s timeout.

#### [MODIFY] [JsonRpcHandler.kt](file:///f:/code/env/IntellijLsp/src/main/kotlin/com/frenchef/intellijlsp/protocol/JsonRpcHandler.kt)

Add:

- `sendRequest(method, params)` - Send a request and return a Future.
- `handleResponse(id, json)` - Handle a response.

---

### Component 3: Handlers (5 New Files)

| File                                                                                                                                             | Methods                                                                                           | Priority |
|--------------------------------------------------------------------------------------------------------------------------------------------------|---------------------------------------------------------------------------------------------------|----------|
| [RenameHandler.kt](file:///f:/code/env/IntellijLsp/src/main/kotlin/com/frenchef/intellijlsp/handlers/RenameHandler.kt) [NEW]                     | `textDocument/prepareRename`, `textDocument/rename`                                               | P1       |
| [CallHierarchyHandler.kt](file:///f:/code/env/IntellijLsp/src/main/kotlin/com/frenchef/intellijlsp/handlers/CallHierarchyHandler.kt) [NEW]       | `textDocument/prepareCallHierarchy`, `callHierarchy/incomingCalls`, `callHierarchy/outgoingCalls` | P2       |
| [TypeHierarchyHandler.kt](file:///f:/code/env/IntellijLsp/src/main/kotlin/com/frenchef/intellijlsp/handlers/TypeHierarchyHandler.kt) [NEW]       | `textDocument/prepareTypeHierarchy`, `typeHierarchy/supertypes`, `typeHierarchy/subtypes`         | P2       |
| [WorkspaceFoldersHandler.kt](file:///f:/code/env/IntellijLsp/src/main/kotlin/com/frenchef/intellijlsp/handlers/WorkspaceFoldersHandler.kt) [NEW] | `workspace/didChangeWorkspaceFolders`                                                             | P3       |
| [FileWatchingHandler.kt](file:///f:/code/env/IntellijLsp/src/main/kotlin/com/frenchef/intellijlsp/handlers/FileWatchingHandler.kt) [NEW]         | `workspace/didChangeWatchedFiles`                                                                 | P3       |

---

### Component 4: Providers & Services (4 New Files)

| File                                                                                                                                         | Key Methods                                                          |
|----------------------------------------------------------------------------------------------------------------------------------------------|----------------------------------------------------------------------|
| [RenameProvider.kt](file:///f:/code/env/IntellijLsp/src/main/kotlin/com/frenchef/intellijlsp/intellij/RenameProvider.kt) [NEW]               | `prepareRename()`, `rename()`                                        |
| [CallHierarchyProvider.kt](file:///f:/code/env/IntellijLsp/src/main/kotlin/com/frenchef/intellijlsp/intellij/CallHierarchyProvider.kt) [NEW] | `prepareCallHierarchy()`, `getIncomingCalls()`, `getOutgoingCalls()` |
| [TypeHierarchyProvider.kt](file:///f:/code/env/IntellijLsp/src/main/kotlin/com/frenchef/intellijlsp/intellij/TypeHierarchyProvider.kt) [NEW] | `prepareTypeHierarchy()`, `getSupertypes()`, `getSubtypes()`         |
| [ApplyEditService.kt](file:///f:/code/env/IntellijLsp/src/main/kotlin/com/frenchef/intellijlsp/services/ApplyEditService.kt) [NEW]           | `applyEdit()`, capability detection, timeout handling |

---

### Component 5: LifecycleHandler Capabilities

#### [MODIFY] [LifecycleHandler.kt](file:///f:/code/env/IntellijLsp/src/main/kotlin/com/frenchef/intellijlsp/handlers/LifecycleHandler.kt#L137-L214)

Update `getServerCapabilities()`:

```kotlin
renameProvider = RenameOptions(prepareProvider = true)
callHierarchyProvider = true
typeHierarchyProvider = true
workspace = WorkspaceCapabilities(
    workspaceFolders = WorkspaceFoldersServerCapabilities(
        supported = true,
        changeNotifications = true
    )
)
```

---

## Verification Plan

### Automated Tests

**Existing test framework**: `src/test/kotlin/com/frenchef/intellijlsp/`

Existing test files:

- `protocol/MessageReaderTest.kt`
- `protocol/MessageWriterTest.kt`
- `services/PortAllocatorTest.kt`

**New tests**:

```bash
# Run all tests
./gradlew test

# Run a specific test
./gradlew test --tests "com.frenchef.intellijlsp.protocol.PendingRequestManagerTest"
./gradlew test --tests "com.frenchef.intellijlsp.handlers.RenameHandlerTest"
```

| New test file                     | Test coverage                |
|--------------------------------|---------------------------|
| `PendingRequestManagerTest.kt` | Request creation, completion, and timeouts |
| `RenameHandlerTest.kt`         | prepareRename and rename response formats |
| `CallHierarchyHandlerTest.kt`  | Call hierarchy queries |
| `TypeHierarchyHandlerTest.kt`  | Type hierarchy queries |

### Manual Verification

Use `tools/lsp_test_client.py` for manual verification:

```bash
# 1. Start IntelliJ and open the test project.
# 2. Run the test client.
python tools/lsp_test_client.py

# 3. Send test requests.
# Rename:
{"jsonrpc":"2.0","id":1,"method":"textDocument/prepareRename","params":{"textDocument":{"uri":"file:///path/to/file.kt"},"position":{"line":10,"character":5}}}

# Call Hierarchy:
{"jsonrpc":"2.0","id":2,"method":"textDocument/prepareCallHierarchy","params":{"textDocument":{"uri":"file:///path/to/file.kt"},"position":{"line":10,"character":5}}}
```

### Build Verification

```bash
./gradlew build
./gradlew buildPlugin
```

---

## Implementation Priority

| Priority | Feature                | Files   | Est. Effort |
|----------|------------------------|---------|-------------|
| P1       | Rename Refactoring     | 4 files | 2 days      |
| P2       | Call Hierarchy         | 2 files | 1.5 days    |
| P2       | Type Hierarchy         | 2 files | 1.5 days    |
| P2       | Server-Initiated Edits | 2 files | 1 day       |
| P3       | Workspace Folders      | 1 file  | 0.5 day     |
| P3       | File Watching          | 1 file  | 0.5 day     |

**Total**: ~7 days
