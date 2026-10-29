"""Minimal Source RCON client (what Minecraft speaks). Standard library only."""
import socket
import struct
import time

class Rcon:
    def __init__(self, host, port, password, timeout=10):
        self.addr, self.password, self.timeout = (host, port), password, timeout
        self._connect()

    def _connect(self):
        self.sock = socket.create_connection(self.addr, timeout=self.timeout)
        self._id = 0
        if self._send(3, self.password)[0] == -1:
            raise PermissionError("RCON login refused")

    def reconnect_after_restart(self, down_timeout=600, up_timeout=600):
        """Wait for the server to go down (someone restarts it), then reconnect once it's back."""
        deadline = time.time() + down_timeout
        while time.time() < deadline:
            try:
                self.cmd("list")
                time.sleep(2)
            except (OSError, ConnectionError):
                break
        else:
            raise TimeoutError("the server was not restarted")
        deadline = time.time() + up_timeout
        while time.time() < deadline:
            time.sleep(3)
            try:
                self._connect()
                return
            except (OSError, ConnectionError, PermissionError):
                continue
        raise TimeoutError("the server did not come back")

    def _send(self, kind, body):
        self._id += 1
        self._write(self._id, kind, body)
        return self._packet()

    def _write(self, req_id, kind, body):
        data = body.encode("utf-8")
        self.sock.sendall(struct.pack("<iii", len(data) + 10, req_id, kind) + data + b"\0\0")

    def _packet(self):
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
        """Run a command. Long replies arrive in several packets (about 4 KB each), so once
        the first has arrived a marker packet is sent, and everything up to the marker's reply
        is joined. (The server reads one packet per network read and drops the connection if
        two arrive together, so the marker mustn't be sent with the command.)"""
        self._id += 1
        command_id = self._id
        self._write(command_id, 2, command)
        req_id, first = self._packet()
        parts = [first] if req_id == command_id else []
        self._id += 1
        marker = self._id
        self._write(marker, 0, "")  # an unknown request type: answered after the rest of the reply
        while True:
            req_id, payload = self._packet()
            if req_id == marker:
                return "".join(parts)
            if req_id == command_id:
                parts.append(payload)

    def close(self):
        self.sock.close()

def from_properties(path):
    props = {}
    for line in open(path):
        if "=" in line and not line.startswith("#"):
            k, v = line.rstrip("\n").split("=", 1)
            props[k] = v
    return Rcon("127.0.0.1", int(props["rcon.port"]), props["rcon.password"])
