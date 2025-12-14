# Feature Specification: IntelliJ LSP Core

**Feature Branch**: `000-lsp-core`  
**Created**: 2025-12-14  
**Status**: In Progress  
**Input**: Migrated from the original SPEC.md

## User Scenarios & Testing

### User Story 1 - Connect an External Editor to the LSP Server (Priority: P1)

After opening a project in IntelliJ, the user connects Neovim/Emacs/VS Code to the LSP server to access code intelligence.

**Why this priority**: This is a core feature; no LSP features can be used without a connection.

**Independent Test**: Test the connection and initialize handshake independently with `vim.lsp.rpc.connect("127.0.0.1", 2087)`.

**Acceptance Scenarios**:

1. **Given** the project is open in IntelliJ, **When** an external editor sends an `initialize` request, **Then** the server returns its capabilities.
2. **Given** the server is initialized, **When** the client sends an `initialized` notification, **Then** the server is ready to handle subsequent requests.
3. **Given** a client is connected, **When** the client disconnects, **Then** the server continues running and waits for a new connection.

---

### User Story 2 - Show Documentation on Hover (Priority: P1)

When the user hovers over a symbol in an external editor, type information and documentation are displayed.

**Why this priority**: Hover is one of the most frequently used code intelligence features.

**Independent Test**: Open any .kt/.java file and hover over a class or method name to verify the response.

**Acceptance Scenarios**:

1. **Given** a document has received didOpen, **When** a `textDocument/hover` request is sent, **Then** Markdown-formatted documentation is returned.
2. **Given** there is no symbol at the hover position, **When** a hover request is sent, **Then** null is returned (no content).

---

### User Story 3 - Go to Definition (Priority: P1)

The user navigates to a symbol's definition in an external editor.

**Why this priority**: Go to Definition is a fundamental navigation feature.

**Independent Test**: Send `textDocument/definition` at a reference and verify navigation to the declaration.

**Acceptance Scenarios**:

1. **Given** the cursor is on a reference, **When** a definition request is sent, **Then** the definition location is returned.
2. **Given** the symbol is defined in an external library, **When** a definition request is sent, **Then** the library file location (or decompiled location) is returned.

---

### User Story 4 - Code Completion (Priority: P1)

The user receives intelligent completion suggestions while writing code in an external editor.

**Why this priority**: Completion significantly improves coding efficiency.

**Independent Test**: Type a class name prefix, send `textDocument/completion`, and verify the returned candidate list.

**Acceptance Scenarios**:

1. **Given** `Stri` has been typed, **When** a completion request is sent, **Then** a CompletionItem list containing `String` is returned.
2. **Given** `.` has been typed, **When** a completion request is sent, **Then** a list of member methods/properties is returned.

---

### User Story 5 - Real-Time Diagnostics (Priority: P2)

After the user saves a file, the external editor displays IntelliJ errors and warnings.

**Why this priority**: Diagnostics help users find issues promptly, but are not essential to the initial MVP.

**Independent Test**: Introduce a syntax error in the code, save the file, and verify the `publishDiagnostics` notification.

**Acceptance Scenarios**:

1. **Given** a file contains a syntax error, **When** the file is saved, **Then** the server publishes diagnostics containing the error.
2. **Given** the file's errors have been fixed, **When** the file is saved, **Then** the diagnostics list is cleared.

---

### User Story 6 - Find All References (Priority: P2)

The user finds all references to a symbol in the project.

**Why this priority**: This is essential for understanding the impact before refactoring.

**Independent Test**: Send `textDocument/references` at a symbol and verify that all usage locations are returned.

**Acceptance Scenarios**:

1. **Given** the cursor is on a method declaration, **When** a references request is sent, **Then** all call locations are returned.

---

### User Story 7 - abcoder Compatibility (Priority: P2)

Support abcoder (UniAST), which strongly depends on typeDefinition, documentSymbol, and semanticTokens.

**Why this priority**: This expands integration scenarios, while core LSP features remain the priority.

**Independent Test**: Send `textDocument/typeDefinition`, `textDocument/documentSymbol`, and `textDocument/semanticTokens/full` and verify the responses.

**Acceptance Scenarios**:

1. **Given** a variable has type `List<String>`, **When** typeDefinition is sent, **Then** the location of the `List` interface definition is returned.
2. **Given** a file contains classes/functions, **When** documentSymbol is sent, **Then** a hierarchical symbol tree is returned.
3. **Given** a file contains code, **When** semanticTokens/full is sent, **Then** an encoded token array is returned.

---

### Edge Cases

- **Port conflicts**: If the default port is occupied, the server SHOULD automatically try the next port.
- **Multiple projects**: Each project window runs an independent server, with no port conflicts.
- **rootUri validation**: If the client's rootUri is outside the project, return an `INVALID_PARAMS` error.
- **IntelliJ indexing in progress**: Some features may return empty results during indexing; this should not be treated as an error.

## Requirements

### Functional Requirements

- **FR-001**: The server MUST start automatically when a project opens.
- **FR-002**: The server MUST support both TCP and Unix domain socket transport modes.
- **FR-003**: The server MUST implement the LSP 3.17 base protocol (initialize, initialized, shutdown, exit).
- **FR-004**: The server MUST support incremental document synchronization (didOpen, didChange, didClose, didSave).
- **FR-005**: The server MUST provide the core features hover, definition, completion, and references.
- **FR-006**: The server MUST publish real-time diagnostics (publishDiagnostics).
- **FR-007**: The server MUST display its status, port, and connection count in the status bar.
- **FR-008**: The server SHOULD support typeDefinition, documentSymbol, and semanticTokens (abcoder compatibility).

### Key Entities

- **LspServer**: Server instance that manages TCP/UDS connections.
- **JsonRpcHandler**: Routes and dispatches JSON-RPC messages.
- **DocumentManager**: Tracks and synchronizes document versions.
- **PsiMapper**: Converts between LSP Position and IntelliJ offsets.

## Success Criteria

### Measurable Outcomes

- **SC-001**: After the server starts, Neovim can complete the initialize handshake within 5 seconds.
- **SC-002**: Hover response latency is < 500ms (95th percentile).
- **SC-003**: Completion returns < 100 results with latency < 1s.
- **SC-004**: Multiple projects have no port conflicts or state contamination.
- **SC-005**: Shutting down/restarting the server causes no resource leaks.
