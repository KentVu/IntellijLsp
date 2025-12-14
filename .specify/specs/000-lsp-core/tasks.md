# Tasks: IntelliJ LSP Core

**Input**: Design documents from `.specify/specs/000-lsp-core/`  
**Prerequisites**: plan.md (✅), spec.md (✅)

**Organization**: Tasks are grouped by user story to support independent implementation and testing.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can be performed in parallel (different files, no dependencies).
- **[Story]**: Associated user story (US1-US7).
- Includes exact file paths.

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Project initialization and basic structure.

- [x] T001 Update `build.gradle.kts`: add the Kotlin Coroutines dependency.
- [x] T002 Update `plugin.xml`: register the service, StartupActivity, and StatusBar widget.
- [x] T003 [P] Create `config/TransportMode.kt`.
- [x] T004 [P] Create `config/LspSettings.kt`.
- [x] T005 [P] Create `config/LspConfigurable.kt`.
- [x] T006 [P] Create `services/PortAllocator.kt`.

---

## Phase 2: Infrastructure (Server & Protocol)

**Purpose**: Implement the transport and protocol layers.

- [x] T007 Create `protocol/MessageReader.kt`: parse Content-Length and JSON.
- [x] T008 [P] Create `protocol/MessageWriter.kt`: format JSON-RPC messages.
- [x] T009 [P] Create `protocol/models/LspTypes.kt`: LSP data models.
- [x] T010 Create `protocol/JsonRpcHandler.kt`: request routing and error handling.
- [x] T011 Create `server/LspServer.kt`: server interface.
- [x] T012 [P] Create `server/TcpLspServer.kt`: TCP implementation.
- [x] T013 [P] Create `server/UdsLspServer.kt`: Unix socket implementation.
- [x] T014 Create `server/LspServerManager.kt`: lifecycle management.
- [x] T015 Create `services/LspProjectService.kt`: project-level service.
- [x] T016 Create `LspServerStartupActivity.kt`: initialize at startup.

**Checkpoint**: Basic server framework is complete.

---

## Phase 3: User Story 1 - Connect an External Editor (Priority: P1) 🎯 MVP

**Goal**: The client can complete the initialize handshake.

- [x] T017 [US1] Create `handlers/LifecycleHandler.kt`: initialize/shutdown/exit.
- [x] T018 [US1] Implement rootUri validation in `LifecycleHandler`.
- [x] T019 [US1] Return ServerCapabilities from `LifecycleHandler`.

**Checkpoint**: Neovim can connect and complete initialize.

---

## Phase 4: User Story 2 - Hover Documentation (Priority: P1)

**Goal**: Display type/documentation information on hover.

- [x] T020 [US2] Create `intellij/PsiMapper.kt`: convert Position ↔ offset.
- [x] T021 [US2] Create `handlers/HoverHandler.kt`: textDocument/hover.

**Checkpoint**: Hover documentation is available with K/gd.

---

## Phase 5: User Story 3 - Go to Definition (Priority: P1)

**Goal**: Go to Definition is available.

- [x] T022 [US3] Create `handlers/DefinitionHandler.kt`: textDocument/definition.

**Checkpoint**: Navigation with gd is available.

---

## Phase 6: User Story 4 - Code Completion (Priority: P1)

**Goal**: Intelligent completion is available.

- [x] T023 [US4] Create `intellij/CompletionProvider.kt`: bridge to IntelliJ completion.
- [x] T024 [US4] Create `handlers/CompletionHandler.kt`: textDocument/completion.

**Checkpoint**: Typing `.` triggers completion.

---

## Phase 7: Document Synchronization

**Purpose**: Manage document state.

- [x] T025 Create `intellij/DocumentManager.kt`: map URI → Document.
- [x] T026 Create `handlers/DocumentSyncHandler.kt`: didOpen/didChange/didClose/didSave.

---

## Phase 8: User Story 5 - Publish Diagnostics (Priority: P2)

**Goal**: Publish errors/warnings in real time.

- [x] T027 [US5] Create `intellij/DiagnosticsProvider.kt`: extract diagnostics from HighlightInfo.
- [x] T028 [US5] Create `handlers/DiagnosticsHandler.kt`: publishDiagnostics.

**Checkpoint**: Diagnostics are published after saving a file.

---

## Phase 9: User Story 6 - Find References (Priority: P2)

**Goal**: Find References is available.

- [x] T029 [US6] Create `handlers/ReferencesHandler.kt`: textDocument/references.
- [x] T030 [P] [US6] Create `handlers/DocumentHighlightHandler.kt`: textDocument/documentHighlight.

---

## Phase 10: User Story 7 - abcoder Compatibility (Priority: P2)

**Goal**: Support typeDefinition, documentSymbol, and semanticTokens.

### Complete the Protocol Models

- [x] T031 [P] [US7] Update `LspTypes.kt`: add TypeDefinition parameters.
- [x] T032 [P] [US7] Update `LspTypes.kt`: add DocumentSymbol/SymbolKind models.
- [x] T033 [P] [US7] Update `LspTypes.kt`: add SemanticTokens/Legend models.

### Implement the Handlers

- [x] T034 [US7] Create `handlers/TypeDefinitionHandler.kt`.
- [x] T035 [US7] Create `intellij/DocumentSymbolProvider.kt`.
- [x] T036 [US7] Create `handlers/DocumentSymbolHandler.kt`.
- [x] T037 [US7] Create `intellij/SemanticTokensProvider.kt`.
- [x] T038 [US7] Create `handlers/SemanticTokensHandler.kt`.

### Declare Capabilities

- [x] T039 [US7] Update `LifecycleHandler`: declare typeDefinitionProvider.
- [x] T040 [US7] Update `LifecycleHandler`: declare documentSymbolProvider.
- [x] T041 [US7] Update `LifecycleHandler`: declare semanticTokensProvider + legend.

**Checkpoint**: ✅ abcoder can complete initialize and use the extended features.

---

## Phase 11: UI Status Display

**Purpose**: Provide real-time status bar feedback.

- [x] T042 Create `ui/LspStatusWidget.kt`: display the port/connection count/status.

---

## Phase 12: Testing and Polish

**Purpose**: Quality assurance.

- [x] T043 [P] Unit tests: MessageReader/MessageWriter.
- [x] T044 [P] Unit tests: PortAllocator.
- [ ] T045 End-to-end test: complete Neovim connection flow.
- [x] T046 Documentation update: README.md usage instructions.

---

## Dependencies & Execution Order

### Phase Dependencies

- **Phases 1-2**: Complete sequentially; later phases depend on the foundation.
- **Phases 3-6** (US1-4): Can run in parallel after Phase 2 is complete.
- **Phase 7**: Can run in parallel with Phases 3-6.
- **Phases 8-10** (US5-7): Depend on document synchronization in Phase 7.
- **Phase 11**: Can run in parallel with Phase 3 and later phases.
- **Phase 12**: Execute last.

### Parallel Opportunities

- T003-T006 can run in parallel.
- T007-T009 can run in parallel.
- T012-T013 can run in parallel.
- T031-T033 can run in parallel.
- T043-T044 can run in parallel.

---

## Implementation Strategy

### MVP First (User Stories 1-4)

1. Complete Phases 1-2: foundation.
2. Complete Phases 3-6: core features (initialize/hover/definition/completion).
3. **Verify**: Connect Neovim and use the basic features.
4. The MVP can be deployed.

### Incremental Delivery

- Phase 8: Add diagnostics publishing.
- Phase 9: Add reference search.
- Phase 10: Add abcoder compatibility.

---

## Notes

- Completed tasks are marked `[x]`.
- Incomplete tasks are marked `[ ]`.
- ✅ Phase 10 (abcoder compatibility) is complete.
- Current focus: Phase 12 (testing and polish).
