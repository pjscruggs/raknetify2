"""Answer the native client's RakNet SRV query on a disposable loopback UDP port."""
import contextlib
import socketserver
import struct
import threading


class Resolver(socketserver.BaseRequestHandler):
    """Provide distinct deterministic DNS answers without changing system DNS settings."""

    def handle(self):
        """Echo the question and advertise a distinct record for the one fixture service."""
        data, peer = self.request
        end = data.index(b'\x00', 12) + 5
        question = data[12:end]
        expected = b'\x0a_raknetify\x04_udp\x07fixture\x04test\x00\x00\x21\x00\x01'
        matched = question.lower() == expected
        header = data[:2] + struct.pack('!HHHHH', 0x8180 if matched else 0x8183, 1, int(matched), 0, 0)
        target = struct.pack('!HHH', 0, 0, self.server.service_port) + b'\x06target\x07fixture\x04test\x00'
        record = b'\xc0\x0c' + struct.pack('!HHIH', 33, 1, 10, len(target)) + target if matched else b''
        peer.sendto(header + question + record, self.client_address)


@contextlib.contextmanager
def running(service_port=25437):
    """Make the test resolver available only for the lifetime of the client process."""
    with socketserver.UDPServer(('127.0.0.1', 0), Resolver) as server:
        server.service_port = service_port
        thread = threading.Thread(target=server.serve_forever, kwargs={'poll_interval': 0.05})
        thread.start()
        try:
            yield '127.0.0.1:' + str(server.server_address[1])
        finally:
            server.shutdown()
            thread.join()
