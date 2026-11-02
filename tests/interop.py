import os, pathlib, subprocess, tempfile, threading, hashlib

cli = str(
    pathlib.Path(__file__).resolve().parents[1]
    / "testapp/build/install/testapp/bin/testapp"
)
for direction in ["receive", "send"]:
    with tempfile.TemporaryDirectory() as tmp:
        root = pathlib.Path(tmp)
        src = root / "src"
        dst = root / "dst"
        src.mkdir()
        dst.mkdir()
        data = bytes(range(256)) * 8192 + b"last bytes"
        (src / "binary.dat").write_bytes(data)
        (src / "empty.dat").write_bytes(b"")
        cmds = (
            (
                [cli, "receive", str(dst)],
                ["sz", "-b", str(src / "binary.dat"), str(src / "empty.dat")],
            )
            if direction == "receive"
            else (
                [cli, "send", str(src / "binary.dat"), str(src / "empty.dat")],
                ["rz", "-b", "-y"],
            )
        )
        a = subprocess.Popen(
            cmds[0],
            stdin=subprocess.PIPE,
            stdout=subprocess.PIPE,
            stderr=open(root / "a.err", "w"),
        )
        b = subprocess.Popen(
            cmds[1],
            stdin=subprocess.PIPE,
            stdout=subprocess.PIPE,
            stderr=open(root / "b.err", "w"),
            cwd=dst,
        )

        def pump(x, y):
            try:
                while True:
                    chunk = os.read(x.stdout.fileno(), 4096)
                    if not chunk:
                        break
                    y.stdin.write(chunk)
                    y.stdin.flush()
            except (BrokenPipeError, ValueError):
                pass

        for x, y in [(a, b), (b, a)]:
            threading.Thread(target=pump, args=(x, y), daemon=True).start()
        try:
            a.wait(timeout=20)
            b.wait(timeout=5)
            assert a.returncode == b.returncode == 0, (a.returncode, b.returncode)
            assert (dst / "binary.dat").read_bytes() == data
            assert (dst / "empty.dat").read_bytes() == b""
            print(direction, "PASS")
        except Exception as e:
            a.kill()
            b.kill()
            print(direction, "FAIL", str(e))
            print((root / "a.err").read_text())
            print((root / "b.err").read_text())
            raise
