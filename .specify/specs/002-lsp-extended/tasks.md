# Tasks: LSP Extended Features (Phase 9)

**Input**: Design documents from `/specs/001-lsp-extended/`  
**Prerequisites**: plan.md ✅, spec.md ✅, research.md ✅, data-model.md ✅

**Tests**: Tests are not explicitly required, so this task list does not include test tasks. Request them separately if needed.

**Organization**: Tasks are organized by user story; each story can be implemented and tested independently.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can be performed in parallel (different files, no dependencies).
- **[Story]**: User story associated with the task (US1-US6).
- Descriptions include exact file paths.

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Project initialization and data model definitions.

- [x] T001 Add Signature Help data models to `src/main/kotlin/com/frenchef/intellijlsp/protocol/models/LspTypes.kt`.
- [x] T002 [P] Add Workspace Symbol data models to `LspTypes.kt`.
- [x] T003 [P] Add Formatting data models to `LspTypes.kt`.
- [x] T004 [P] Add Code Action data models to `LspTypes.kt`.
- [x] T005 [P] Add Inlay Hints data models to `LspTypes.kt`.
- [x] T006 [P] Add supplementary WorkspaceEdit models to `LspTypes.kt`.

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Declare new capabilities; all user stories depend on this phase.

**⚠️ CRITICAL**: Complete this phase before implementing user stories.

- [x] T007 Update `LifecycleHandler.kt` to add the `signatureHelpProvider` capability.
- [x] T008 [P] Update `LifecycleHandler.kt` to add `workspaceSymbolProvider = true`.
- [x] T009 [P] Update `LifecycleHandler.kt` to add `documentFormattingProvider = true`.
- [x] T010 [P] Update `LifecycleHandler.kt` to add `documentRangeFormattingProvider = true`.
- [x] T011 [P] Update `LifecycleHandler.kt` to add the `codeActionProvider` capability.
- [x] T012 [P] Update `LifecycleHandler.kt` to add `implementationProvider = true`.
- [x] T013 [P] Update `LifecycleHandler.kt` to add the `inlayHintProvider` capability.

**Checkpoint**: Capability declarations are complete; user story implementation can begin.

---

## Phase 3: User Story 1 - Function Parameter Hints: Signature Help (Priority: P1) 🎯 MVP

**Goal**: The editor automatically displays parameter information when the user calls a function.

**Independent Test**: Type `listOf(` in a .kt/.java file and verify that signature information is displayed.

### Implementation for User Story 1

- [x] T014 [US1] Create `SignatureHelpProvider.kt` in `src/main/kotlin/com/frenchef/intellijlsp/intellij/`.
    - Use PSI analysis to get the function signature.
    - Calculate the activeParameter position.
- [x] T015 [US1] Create `SignatureHelpHandler.kt` in `src/main/kotlin/com/frenchef/intellijlsp/handlers/`.
    - Register the `textDocument/signatureHelp` handler.
    - Call SignatureHelpProvider.
- [x] T016 [US1] Register SignatureHelpHandler in `LspServerStartupActivity.kt`.
- [x] T017 [US1] Add logging to SignatureHelpHandler (Constitution V: Observability).

**Checkpoint**: US1 complete - Signature Help can be tested independently.

---

## Phase 4: User Story 2 - Workspace Symbol Search (Priority: P2)

**Goal**: The user can search for classes, functions, and variables by name across the project.

**Independent Test**: Send a `workspace/symbol` request with query="User" and verify that a symbol list is returned.

### Implementation for User Story 2

- [x] T018 [US2] Create `WorkspaceSymbolProvider.kt` in `src/main/kotlin/com/frenchef/intellijlsp/intellij/`.
    - Use the PsiShortNamesCache API.
    - Limit the result count to ≤100.
- [x] T019 [US2] Create `WorkspaceSymbolHandler.kt` in `src/main/kotlin/com/frenchef/intellijlsp/handlers/`.
    - Register the `workspace/symbol` handler.
    - Support request cancellation.
- [x] T020 [US2] Register WorkspaceSymbolHandler in `LspServerStartupActivity.kt`.
- [x] T021 [US2] Add logging to WorkspaceSymbolHandler.

**Checkpoint**: US2 complete - Workspace Symbol can be tested independently.

---

## Phase 5: User Story 3 - Code Formatting (Priority: P2)

**Goal**: Apply IntelliJ code style when the user requests formatting for a document or selection.

**Independent Test**: Send `textDocument/formatting` and verify that TextEdit[] is returned.

### Implementation for User Story 3

- [x] T022 [US3] Create `FormattingProvider.kt` in `src/main/kotlin/com/frenchef/intellijlsp/intellij/`.
    - Use CodeStyleManager.reformatText().
    - Perform formatting on a copy and compute the diff.
- [x] T023 [US3] Create `FormattingHandler.kt` in `src/main/kotlin/com/frenchef/intellijlsp/handlers/`.
    - Register the `textDocument/formatting` handler.
    - Register the `textDocument/rangeFormatting` handler.
- [x] T024 [US3] Register FormattingHandler in `LspServerStartupActivity.kt`.
- [x] T025 [US3] Add logging to FormattingHandler.

**Checkpoint**: US3 complete - Formatting can be tested independently.

---

## Phase 6: User Story 4 - Code Actions (Priority: P3)

**Goal**: The user requests available quick fixes (QuickFix only).

**Independent Test**: Send `codeAction` on code with a warning and verify that a QuickFix is returned.

### Implementation for User Story 4

- [x] T026 [US4] Create `CodeActionProvider.kt` in `src/main/kotlin/com/frenchef/intellijlsp/intellij/`.
    - Use IntentionManager to get intentions.
    - Filter to return QuickFix actions only.
    - Convert them to WorkspaceEdit.
- [x] T027 [US4] Create `CodeActionHandler.kt` in `src/main/kotlin/com/frenchef/intellijlsp/handlers/`.
    - Register the `textDocument/codeAction` handler.
- [x] T028 [US4] Register CodeActionHandler in `LspServerStartupActivity.kt`.
- [x] T029 [US4] Add logging to CodeActionHandler.

**Checkpoint**: US4 complete - Code Actions can be tested independently.

---

## Phase 7: User Story 5 - Go to Implementation (Priority: P3)

**Goal**: The user can navigate from an interface method to a concrete implementation.

**Independent Test**: Send `textDocument/implementation` at an interface method and verify that the implementation location is returned.

### Implementation for User Story 5

- [x] T030 [US5] Create `ImplementationHandler.kt` in `src/main/kotlin/com/frenchef/intellijlsp/handlers/`.
    - Register the `textDocument/implementation` handler.
    - Use the DefinitionsScopedSearch API.
    - Reuse the existing PsiMapper for position conversion.
- [x] T031 [US5] Register ImplementationHandler in `LspServerStartupActivity.kt`.
- [x] T032 [US5] Add logging to ImplementationHandler.

**Checkpoint**: US5 complete - Go to Implementation can be tested independently.

---

## Phase 8: User Story 6 - Inlay Hints (Priority: P4)

**Goal**: The editor displays inlay hints such as inferred types and parameter names.

**Independent Test**: Send `textDocument/inlayHint` and verify that type/parameter hints are returned.

### Implementation for User Story 6

- [x] T033 [US6] Create `InlayHintsProvider.kt` in `src/main/kotlin/com/frenchef/intellijlsp/intellij/`.
    - Traverse PSI to collect type inference locations.
    - Collect parameter names from function calls.
- [x] T034 [US6] Create `InlayHintsHandler.kt` in `src/main/kotlin/com/frenchef/intellijlsp/handlers/`.
    - Register the `textDocument/inlayHint` handler.
- [x] T035 [US6] Register InlayHintsHandler in `LspServerStartupActivity.kt`.
- [x] T036 [US6] Add logging to InlayHintsHandler.

**Checkpoint**: US6 complete - Inlay Hints can be tested independently.

---

## Phase 9: Polish & Cross-Cutting Concerns

**Purpose**: Cross-story improvements and documentation updates.

- [ ] T037 [P] Review and refactor the code.
- [ ] T038 [P] Update README.md with documentation for the new features.
- [ ] T039 Verify that all features comply with the Constitution principles.
- [ ] T040 Perform full manual verification (test a Neovim connection).

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: No dependencies; can start immediately.
- **Foundational (Phase 2)**: Depends on Phase 1; blocks all user stories.
- **User Stories (Phases 3-8)**: Can run in parallel after Phase 2 is complete.
- **Polish (Phase 9)**: Depends on completing all user stories.

### User Story Dependencies

| Story    | Dependencies | Parallel |
|----------|---------|-----|
| US1 (P1) | Phase 2 | ✅   |
| US2 (P2) | Phase 2 | ✅   |
| US3 (P2) | Phase 2 | ✅   |
| US4 (P3) | Phase 2 | ✅   |
| US5 (P3) | Phase 2 | ✅   |
| US6 (P4) | Phase 2 | ✅   |

### Within Each User Story

1. Implement the Provider (wrap the IntelliJ API).
2. Implement the Handler (JSON-RPC protocol layer).
3. Register the Handler.
4. Add logging.

### Parallel Opportunities

```text
# Phase 1 - Data models can be added in parallel
T001, T002, T003, T004, T005, T006 (same file; sequential execution is recommended)

# Phase 2 - Capability declarations can be added in parallel
T007, T008, T009, T010, T011, T012, T013 (same file; sequential execution is recommended)

# User Stories - Different stories can run fully in parallel
Phase 3 (US1) || Phase 4 (US2) || Phase 5 (US3) || Phase 6 (US4) || Phase 7 (US5) || Phase 8 (US6)
```

---

## Implementation Strategy

### MVP First (User Story 1 Only)

1. ✅ Complete Phase 1: Setup (T001-T006)
2. ✅ Complete Phase 2: Foundational (T007-T013)
3. ✅ Complete Phase 3: User Story 1 (T014-T017)
4. **STOP and VALIDATE**: Test Signature Help with Neovim.
5. Deploy if the MVP is usable.

### Incremental Delivery

| Milestone | Stories  | Cumulative features              |
|-----------|----------|--------------------------------|
| MVP       | US1      | Signature Help                 |
| v1.1      | US2, US3 | + Workspace Symbol, Formatting |
| v1.2      | US4, US5 | + Code Actions, Implementation |
| v1.3      | US6      | + Inlay Hints                  |

---

## Task Summary

| Phase        | Tasks  | Files                                                 |
|--------------|--------|-------------------------------------------------------|
| Setup        | 6      | LspTypes.kt                                           |
| Foundational | 7      | LifecycleHandler.kt                                   |
| US1 (P1)     | 4      | SignatureHelpProvider.kt, SignatureHelpHandler.kt     |
| US2 (P2)     | 4      | WorkspaceSymbolProvider.kt, WorkspaceSymbolHandler.kt |
| US3 (P2)     | 4      | FormattingProvider.kt, FormattingHandler.kt           |
| US4 (P3)     | 4      | CodeActionProvider.kt, CodeActionHandler.kt           |
| US5 (P3)     | 3      | ImplementationHandler.kt                              |
| US6 (P4)     | 4      | InlayHintsProvider.kt, InlayHintsHandler.kt           |
| Polish       | 4      | -                                                     |
| **Total**    | **40** | **10 new + 2 modified**                               |

---

## Notes

- All PSI operations must run inside `ReadAction.compute {}`.
- Handlers must be bound to a Project instance (Constitution III).
- Return empty results rather than errors when data is unavailable (Constitution IV).
- Emit structured logs for all operations (Constitution V).
