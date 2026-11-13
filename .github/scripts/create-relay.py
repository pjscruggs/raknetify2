"""Delay a regular subset of loopback datagrams to expose dependencies between Create channels."""
import contextlib
import heapq
import select
import socket
import threading
import time


@contextlib.contextmanager
def relay(backend):
    """Run one bidirectional UDP path for the RakNet observer; the TCP control connects directly."""
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
            worker.join()


def pump(front, back, done, counts):
    """Queue packets by deadline so later packets regularly overtake earlier ones."""
    pending = []
    sequence = 0
    peer = None
    while not done.is_set():
        for source in select.select([front, back], [], [], 0.004)[0]:
            try:
                data, address = source.recvfrom(65535)
            except ConnectionResetError:
                # Windows reports ICMP from a client that already closed its UDP socket.
                continue
            if source is front:
                peer = address
            sequence += 1
            delayed = sequence % 9 == 0
            counts[0] += int(delayed)
            deadline = time.monotonic() + (0.100 if delayed else 0.008)
            heapq.heappush(pending, (deadline, sequence, source is front, peer, data))
        deliver(pending, front, back)


def deliver(pending, front, back):
    """Send only datagrams whose deadline elapsed, preserving the original bytes."""
    while pending and pending[0][0] <= time.monotonic():
        _, _, inbound, peer, data = heapq.heappop(pending)
        if inbound:
            back.send(data)
        elif peer:
            front.sendto(data, peer)
