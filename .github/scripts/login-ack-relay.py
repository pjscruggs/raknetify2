"""Delay server RakNet acknowledgements independently of encrypted login responses."""
import contextlib
import heapq
import select
import socket
import threading
import time


@contextlib.contextmanager
def running(backend):
    """Expose one loopback UDP path and stop its owned worker on every exit path."""
    done = threading.Event()
    counts = [0]
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as front, socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as back:
        front.bind(('127.0.0.1', 0))
        back.connect(('127.0.0.1', backend))
        worker = threading.Thread(target=pump, args=(front, back, done, counts))
        worker.start()
        try:
            yield front.getsockname()[1], counts
        finally:
            done.set()
            worker.join(timeout=10)
            assert not worker.is_alive(), 'Login ACK relay did not stop'


def pump(front, back, done, counts):
    """Let game frames overtake server ACKs without changing packet contents or client ACK timing."""
    pending = []
    peer = None
    sequence = 0
    while not done.is_set():
        for source in select.select([front, back], [], [], 0.004)[0]:
            try:
                data, address = source.recvfrom(65535)
            except ConnectionResetError:
                continue
            if source is front:
                peer = address
            delayed = source is back and data[0] == 0xc0
            counts[0] += int(delayed)
            sequence += 1
            deadline = time.monotonic() + (0.7 if delayed else 0.005)
            heapq.heappush(pending, (deadline, sequence, source is front, peer, data))
        deliver(pending, front, back)


def deliver(pending, front, back):
    """Forward due datagrams to their original destination, preserving every byte."""
    while pending and pending[0][0] <= time.monotonic():
        _, _, inbound, peer, data = heapq.heappop(pending)
        if inbound:
            back.send(data)
        elif peer:
            front.sendto(data, peer)
