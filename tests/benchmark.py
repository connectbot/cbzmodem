import os, pathlib, subprocess, tempfile, threading, time

cli = str(
    pathlib.Path(__file__).resolve().parents[1]
    / "testapp/build/install/testapp/bin/testapp"
)
data = bytes(range(256)) * 2048
for window in [1024, 65536]:
    with tempfile.TemporaryDirectory() as tmp:
        root = pathlib.Path(tmp)
        source = root / "source"
        destination = root / "destination"
        destination.mkdir()
        source.write_bytes(data)
        env = dict(os.environ, ZMODEM_WINDOW_BYTES=str(window))
        with open(root / "sender.log", "w") as sender_log, open(
            root / "receiver.log", "w"
        ) as receiver_log:
            sender = subprocess.Popen(
                [cli, "send", str(source)],
                stdin=subprocess.PIPE,
                stdout=subprocess.PIPE,
                stderr=sender_log,
                env=env,
            )
            receiver = subprocess.Popen(
                ["rz", "-b", "-y"],
                cwd=destination,
                stdin=subprocess.PIPE,
                stdout=subprocess.PIPE,
                stderr=receiver_log,
            )
            responses = bytearray()

            def pump(a, b, delay=0):
                try:
                    while True:
                        chunk = os.read(a.stdout.fileno(), 4096)
                        if not chunk:
                            return
                        if delay:
                            responses.extend(chunk)
                            time.sleep(delay)
                        b.stdin.write(chunk)
                        b.stdin.flush()
                except (BrokenPipeError, ValueError):
                    pass

            threads = [
                threading.Thread(target=pump, args=(sender, receiver), daemon=True),
                threading.Thread(
                    target=pump, args=(receiver, sender, 0.01), daemon=True
                ),
            ]
            start = time.monotonic()
            for thread in threads:
                thread.start()
            try:
                sender.wait(timeout=30)
                receiver.wait(timeout=5)
                assert sender.returncode == receiver.returncode == 0
                assert (destination / "source").read_bytes() == data
                elapsed = time.monotonic() - start
                print(
                    f'window={window} bytes, ACKs={responses.count(bytes([24])+b"B03")}, elapsed={elapsed:.3f}s, throughput={len(data)/elapsed/1024:.1f} KiB/s',
                    flush=True,
                )
            except:
                sender.kill()
                receiver.kill()
                sender.wait()
                receiver.wait()
                raise
