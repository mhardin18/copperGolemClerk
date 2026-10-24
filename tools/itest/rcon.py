"""Minimal Source RCON client (what Minecraft speaks). Standard library only."""
import socket
import struct

class Rcon:
    def __init__(self, host, port, password, timeout=10):
        self.sock = socket.create_connection((host, port), timeout=timeout)
        self._id = 0
        if self._send(3, password)[0] == -1:
            raise PermissionError("RCON login refused")

    def _send(self, kind, body):
        self._id += 1
        data = body.encode("utf-8")
        self.sock.sendall(struct.pack("<iii", len(data) + 10, self._id, kind) + data + b"\0\0")
        size = struct.unpack("<i", self._read(4))[0]
        req_id, _ = struct.unpack("<ii", self._read(8))
        payload = self._read(size - 8)[:-2]
        return req_id, payload.decode("utf-8", "replace")

    def _read(self, n):
        buf = b""
        while len(buf) < n:
            chunk = self.sock.recv(n - len(buf))
            if not chunk:
                raise ConnectionError("RCON connection closed")
            buf += chunk
        return buf

    def cmd(self, command):
        return self._send(2, command)[1]

    def close(self):
        self.sock.close()

def from_properties(path):
    props = {}
    for line in open(path):
        if "=" in line and not line.startswith("#"):
            k, v = line.rstrip("\n").split("=", 1)
            props[k] = v
    return Rcon("127.0.0.1", int(props["rcon.port"]), props["rcon.password"])
