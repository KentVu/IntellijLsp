#!/usr/bin/env python3
"""
Test call hierarchy and type hierarchy features.
Check whether incomingCalls/outgoingCalls and supertypes/subtypes return empty results.
"""

import json
import socket
import sys


class LspTestClient:
    def __init__(self, host: str = "localhost", port: int = 2087):
        self.host = host
        self.port = port
        self.sock = None
        self.request_id = 0

    def connect(self):
        """Connect to the LSP server."""
        try:
            self.sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
            self.sock.settimeout(30)
            self.sock.connect((self.host, self.port))
            print(f"[OK] Connected to {self.host}:{self.port}")
            return True
        except Exception as e:
            print(f"[ERROR] Connection to {self.host}:{self.port} failed: {e}")
            return False

    def disconnect(self):
        if self.sock:
            self.sock.close()
            self.sock = None

    def send_request(self, method: str, params: dict = None) -> dict:
        """Send a JSON-RPC request."""
        if not self.sock:
            raise ConnectionError("Not connected")

        self.request_id += 1
        request = {
            "jsonrpc": "2.0",
            "id": self.request_id,
            "method": method,
            "params": params or {},
        }
        self._send_message(request)
        return self._receive_response()

    def _send_message(self, message: dict):
        content = json.dumps(message)
        header = f"Content-Length: {len(content)}\r\n\r\n"
        full_message = header + content
        self.sock.sendall(full_message.encode("utf-8"))
        print(f"=> Sending: {message['method']}")

    def _receive_response(self) -> dict:
        header = b""
        while b"\r\n\r\n" not in header:
            chunk = self.sock.recv(1)
            if not chunk:
                raise ConnectionError("Connection closed")
            header += chunk

        header_str = header.decode("utf-8")
        content_length = 0
        for line in header_str.split("\r\n"):
            if line.startswith("Content-Length:"):
                content_length = int(line.split(":")[1].strip())
                break

        body = b""
        while len(body) < content_length:
            chunk = self.sock.recv(content_length - len(body))
            if not chunk:
                raise ConnectionError("Connection closed")
            body += chunk

        return json.loads(body.decode("utf-8"))


def test_call_hierarchy(
        client: LspTestClient, file_uri: str, line: int, character: int
):
    """Test call hierarchy."""
    print("\n" + "=" * 60)
    print("Test call hierarchy (Call Hierarchy)")
    print("=" * 60)

    # 1. prepareCallHierarchy
    print(f"\n1. prepareCallHierarchy at {file_uri}:{line}:{character}")
    result = client.send_request(
        "textDocument/prepareCallHierarchy",
        {
            "textDocument": {"uri": file_uri},
            "position": {"line": line, "character": character},
        },
    )

    if "error" in result:
        print(f"   [ERROR] {result['error']}")
        return

    items = result.get("result", [])
    print(f"   [Result] Found {len(items)} methods")

    if not items:
        print("   [Warning] prepareCallHierarchy returned no items; cannot continue testing")
        return

    for i, item in enumerate(items):
        print(f"   [{i}] {item.get('name')} - {item.get('detail', 'N/A')}")

    # Use the first item to test incoming/outgoing calls.
    test_item = items[0]

    # 2. incomingCalls
    print(f"\n2. callHierarchy/incomingCalls for '{test_item['name']}'")
    result = client.send_request("callHierarchy/incomingCalls", {"item": test_item})

    if "error" in result:
        print(f"   [ERROR] {result['error']}")
    else:
        calls = result.get("result", [])
        print(f"   [Result] Found {len(calls)} callers")
        for i, call in enumerate(calls[:5]):  # Show only the first 5.
            from_item = call.get("from", {})
            print(
                f"   [{i}] {from_item.get('name')} ({from_item.get('detail', 'N/A')})"
            )

    # 3. outgoingCalls
    print(f"\n3. callHierarchy/outgoingCalls for '{test_item['name']}'")
    result = client.send_request("callHierarchy/outgoingCalls", {"item": test_item})

    if "error" in result:
        print(f"   [ERROR] {result['error']}")
    else:
        calls = result.get("result", [])
        print(f"   [Result] Found {len(calls)} called methods")
        for i, call in enumerate(calls[:5]):
            to_item = call.get("to", {})
            print(f"   [{i}] {to_item.get('name')} ({to_item.get('detail', 'N/A')})")


def test_type_hierarchy(
        client: LspTestClient, file_uri: str, line: int, character: int
):
    """Test type hierarchy."""
    print("\n" + "=" * 60)
    print("Test type hierarchy (Type Hierarchy)")
    print("=" * 60)

    # 1. prepareTypeHierarchy
    print(f"\n1. prepareTypeHierarchy at {file_uri}:{line}:{character}")
    result = client.send_request(
        "textDocument/prepareTypeHierarchy",
        {
            "textDocument": {"uri": file_uri},
            "position": {"line": line, "character": character},
        },
    )

    if "error" in result:
        print(f"   [ERROR] {result['error']}")
        return

    items = result.get("result", [])
    print(f"   [Result] Found {len(items)} types")

    if not items:
        print("   [Warning] prepareTypeHierarchy returned no items; cannot continue testing")
        return

    for i, item in enumerate(items):
        print(f"   [{i}] {item.get('name')} - {item.get('detail', 'N/A')}")

    test_item = items[0]

    # 2. supertypes
    print(f"\n2. typeHierarchy/supertypes for '{test_item['name']}'")
    result = client.send_request("typeHierarchy/supertypes", {"item": test_item})

    if "error" in result:
        print(f"   [ERROR] {result['error']}")
    else:
        types = result.get("result", [])
        print(f"   [Result] Found {len(types)} supertypes")
        for i, t in enumerate(types[:5]):
            print(f"   [{i}] {t.get('name')} ({t.get('detail', 'N/A')})")

    # 3. subtypes
    print(f"\n3. typeHierarchy/subtypes for '{test_item['name']}'")
    result = client.send_request("typeHierarchy/subtypes", {"item": test_item})

    if "error" in result:
        print(f"   [ERROR] {result['error']}")
    else:
        types = result.get("result", [])
        print(f"   [Result] Found {len(types)} subtypes")
        for i, t in enumerate(types[:5]):
            print(f"   [{i}] {t.get('name')} ({t.get('detail', 'N/A')})")


def main():
    # Default test file: use the register method in CallHierarchyHandler.kt.
    test_file = "file:///f:/code/env/IntellijLsp/src/main/kotlin/com/frenchef/intellijlsp/handlers/CallHierarchyHandler.kt"

    # Cursor position: points to the register method name (line 34, character 8).
    # fun register() {
    test_line = 33  # 0-indexed: line 34 is index 33.
    test_char = 8  # "register" after "fun ".

    if len(sys.argv) > 1:
        test_file = sys.argv[1]
    if len(sys.argv) > 2:
        test_line = int(sys.argv[2])
    if len(sys.argv) > 3:
        test_char = int(sys.argv[3])

    print(f"Test file: {test_file}")
    print(f"Position: line {test_line}, column {test_char}")

    client = LspTestClient()

    if not client.connect():
        sys.exit(1)

    try:
        # Send initialize first.
        init_result = client.send_request(
            "initialize",
            {
                "processId": None,
                "rootUri": "file:///f:/code/env/IntellijLsp",
                "capabilities": {},
            },
        )
        print(f"Initialize result: {init_result.get('result', {}).get('serverInfo', 'N/A')}")

        # Test call hierarchy.
        test_call_hierarchy(client, test_file, test_line, test_char)

        # Test type hierarchy; this requires a class declaration position.
        # For TypeHierarchy, use a class.
        type_test_file = "file:///f:/code/env/IntellijLsp/src/main/kotlin/com/frenchef/intellijlsp/handlers/CallHierarchyHandler.kt"
        type_test_line = 24  # Line with the CallHierarchyHandler class declaration.
        type_test_char = 10

        test_type_hierarchy(client, type_test_file, type_test_line, type_test_char)

    finally:
        client.disconnect()

    print("\n" + "=" * 60)
    print("Test complete")
    print("=" * 60)


if __name__ == "__main__":
    main()
