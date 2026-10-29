#!/usr/bin/env python3
"""Synthetic PCM host for ONE Android emulator. Not a production streaming server.

Run on the development machine, then Join by IP 10.0.2.2 in the emulator.
Control/clock exchange is real, and UDP is forwarded with the emulator console.
This exercises AudioTrack/foreground playback, not MediaProjection or real Wi-Fi.
"""
import argparse
import math
import socket
import struct
import subprocess
import threading
import time

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--adb', default='adb')
parser.add_argument('--serial', default='emulator-5556')
parser.add_argument('--port', type=int, default=39556)
parser.add_argument('--redirect', type=int, default=39557)
args = parser.parse_args()
adb = [args.adb, '-s', args.serial, 'emu', 'redir']
server = socket.create_server(('127.0.0.1', args.port), reuse_port=False)
print('Synthetic PCM host ready. Emulator: Join by IP 10.0.2.2. No physical audio/latency claim.', flush=True)

def read_line(stream):
    raw = stream.readline(257)
    if len(raw) > 256 or not raw.endswith(b'\n'):
        raise ValueError('Control closed or oversized')
    return raw.decode('ascii').strip()

def write(stream, value):
    stream.write((value+'\n').encode('ascii')); stream.flush()

try:
    while True:
        connection, _ = server.accept()
        stopped = threading.Event()
        mapped = False
        sender = None
        try:
            connection.settimeout(15)
            stream = connection.makefile('rwb')
            assert read_line(stream) == 'INFO 2'
            write(stream, 'SESSION 2 42 7 PCM16 48000 1')
            hello = read_line(stream).split(' ')
            assert len(hello) == 6 and hello[:3] == ['HELLO', '2', '42'] and hello[4] == 'PCM16'
            guest_port = int(hello[3]); assert 1024 <= guest_port <= 65535
            result = subprocess.check_output(adb + ['add', f'udp:{args.redirect}:{guest_port}'], text=True)
            assert 'OK' in result, result
            mapped = True
            write(stream, 'WELCOME 2 42 7 PCM16 48000 1')
            print('Synthetic listener joined, UDP forward active', flush=True)
            def audio():
                start = time.monotonic_ns(); sequence = 0
                pcm = struct.pack('<480h', *(int(2000*math.sin(2*math.pi*400*i/48000)) for i in range(480)))
                with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as udp:
                    while not stopped.is_set():
                        stamp = start + sequence*10_000_000
                        packet = struct.pack('>IBBHqiiqqHH', 0x57415332, 2, 1, 0, 42, 7,
                                             sequence, sequence*480, stamp, 960, 480) + pcm
                        udp.sendto(packet, ('127.0.0.1', args.redirect))
                        sequence += 1
                        stopped.wait(max(0, (start+sequence*10_000_000-time.monotonic_ns())/1e9))
            sender = threading.Thread(target=audio, daemon=True); sender.start()
            while True:
                line = read_line(stream); receive = time.monotonic_ns()
                if line == 'LEAVE': break
                pieces = line.split(' '); assert len(pieces) == 2 and pieces[0] == 'PING'
                int(pieces[1]); write(stream, f'PONG {pieces[1]} {receive} {time.monotonic_ns()}')
        except (OSError, ValueError, AssertionError) as error:
            print('Synthetic connection ended:', error, flush=True)
        finally:
            stopped.set()
            if sender: sender.join(timeout=1)
            connection.close()
            if mapped: subprocess.run(adb + ['del', f'udp:{args.redirect}'], capture_output=True)
except KeyboardInterrupt:
    pass
finally:
    server.close()
