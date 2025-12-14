# Implementation Plan: IntelliJ LSP Core

**Branch**: `000-lsp-core` | **Date**: 2025-12-14 | **Spec**: [spec.md](file:///f:/code/env/IntellijLsp/.specify/specs/000-lsp-core/spec.md)
**Input**: Migrated from the original IMPLEMENTATION_PLAN.md

## Summary

Build an IntelliJ IDEA plugin that exposes IntelliJ code intelligence to external editors (Neovim/Emacs/VS Code) through the LSP protocol. Use a stack consisting of TCP/UDS transport, JSON-RPC, and PSI integration.

## Technical Context

**Language/Version**: Kotlin 1.9+ (JVM 17+)  
**Primary Dependencies**: IntelliJ Platform SDK 2025.1+, Kotlin Coroutines 1.8+  
**Storage**: N/A (no persistent storage; state is managed in memory)
**Testing**: JUnit 5 + IntelliJ Platform TestFramework  
**Target Platform**: IntelliJ IDEA (runs as a plugin)
**Project Type**: IntelliJ Plugin  
**Performance Goals**: Hover/Definition response < 500ms, Completion < 1s
**Constraints**: localhost access only; PSI access requires a ReadAction
**Scale/Scope**: Multiple projects in a single IDE instance

## Constitution Check

| Principle                     | Status | Notes                                       |
| ----------------------------- | ------ | ------------------------------------------- |
| I. Platform Integration First | ✅     | All LSP features reuse PSI/DocumentationProvider |
| II. Protocol Conformance      | ✅     | Strictly follows the LSP 3.17 specification      |
| III. Project Isolation        | ✅     | Independent server instance per project          |
| IV. Graceful Degradation      | ✅     | Handlers catch exceptions and return empty results |
| V. Observability              | ✅     | Status bar widget + structured logs              |

## Project Structure

### Documentation (this feature)

```text
.specify/specs/000-lsp-core/
├── spec.md              # Feature specification
├── plan.md              # This file
└── tasks.md             # Task list
```

### Source Code (repository root)

```text
src/main/kotlin/com/frenchef/intellijlsp/
├── server/              # Server implementation (TCP/UDS)
│   ├── LspServer.kt
│   ├── TcpLspServer.kt
│   ├── UdsLspServer.kt
│   └── LspServerManager.kt
├── protocol/            # JSON-RPC protocol layer
│   ├── JsonRpcHandler.kt
│   ├── MessageReader.kt
│   ├── MessageWriter.kt
│   └── models/          # LSP data models
├── handlers/            # LSP request handlers
│   ├── LifecycleHandler.kt
│   ├── DocumentSyncHandler.kt
│   ├── HoverHandler.kt
│   ├── DefinitionHandler.kt
│   ├── CompletionHandler.kt
│   ├── ReferencesHandler.kt
│   ├── DocumentHighlightHandler.kt
│   ├── DiagnosticsHandler.kt
│   ├── TypeDefinitionHandler.kt
│   ├── DocumentSymbolHandler.kt
│   └── SemanticTokensHandler.kt
├── intellij/            # IntelliJ integration layer
│   ├── DocumentManager.kt
│   ├── PsiMapper.kt
│   ├── DiagnosticsProvider.kt
│   ├── CompletionProvider.kt
│   ├── DocumentSymbolProvider.kt
│   └── SemanticTokensProvider.kt
├── config/              # Configuration and settings
│   ├── LspSettings.kt
│   ├── LspConfigurable.kt
│   └── TransportMode.kt
├── ui/                  # User interface
│   └── LspStatusWidget.kt
└── services/            # Project-level services
    ├── LspProjectService.kt
    └── PortAllocator.kt

src/main/resources/META-INF/
└── plugin.xml           # Plugin configuration
```

**Structure Decision**: Use the standard IntelliJ plugin structure, organized by responsibility (server/protocol/handlers/intellij/config/ui/services).

## Key Design Decisions

### Transport Layer

- **TCP**: Default mode; automatically increment the port starting from 2087.
- **UDS**: Socket file path `~/.intellij-lsp/project-{hash}.sock`.
- Message format: `Content-Length: {len}\r\n\r\n{JSON}`.

### Threading Model

- Network I/O: Kotlin coroutines (`Dispatchers.IO`).
- PSI access: `ReadAction.compute { ... }`.
- Diagnostics publishing: debounce by 500ms.

### Error Handling

- Handlers catch exceptions and log them.
- Return standard LSP error codes.
- Client disconnections do not affect the server.

## Phases Overview

| Phase | Description                 | Status    |
| ----- | --------------------------- | --------- |
| 1     | Foundation & Infrastructure | ✅ Complete |
| 2     | Server Infrastructure       | ✅ Complete |
| 3     | LSP Base Protocol           | ✅ Complete |
| 4     | Document Synchronization    | ✅ Complete |
| 5     | Core Language Features      | 🔄 In progress |
| 6     | Diagnostics                 | ✅ Complete |
| 7     | UI & Status                 | ✅ Complete |
| 8     | Testing & Polish            | ⏳ Not started |
| 9     | Extended Features           | ⏳ Planned |
| 10    | Advanced Features           | ⏳ Planned |

## Verification Plan

### Automated Tests

```bash
./gradlew test           # Run unit tests
./gradlew runIde         # Launch a test IDE instance
```

### Manual Verification

1. **Neovim connection test**: Configure `vim.lsp.rpc.connect("127.0.0.1", 2087)` and verify initialize.
2. **Hover test**: Hover over a symbol and verify that documentation is displayed.
3. **Go to Definition**: Verify navigation with `gd`.
4. **Completion**: Type `.` and verify that completion suggestions appear.
5. **Multiple projects**: Open two projects simultaneously and verify port allocation.

## Complexity Tracking

> No Constitution violations currently require tracking.
