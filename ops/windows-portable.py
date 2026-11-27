"""Start and stop a relocatable Windows demo from the extracted kit."""

from contextlib import ExitStack
import json
import msvcrt
import os
from pathlib import Path
import secrets
import shutil
import socket
import ssl
import subprocess
import sys
import time
from urllib.error import URLError
from urllib.request import HTTPSHandler, ProxyHandler, build_opener

import psycopg


ROOT = Path(__file__).resolve().parent
RUNTIME = ROOT / "runtime"
DATA = ROOT / "data"
LOGS = DATA / "logs"
DATABASE = DATA / "database"
PGDATA = DATABASE / "pgdata"
PORT_FILE = DATA / "https-port.txt"
POSTGRES = RUNTIME / "postgres" / "bin"
TLS = ROOT / "tls"
HIDDEN = subprocess.CREATE_NO_WINDOW


def port_bind(stack, port):
    sock = stack.enter_context(socket.socket())
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_EXCLUSIVEADDRUSE, 1)
    sock.bind(("127.0.0.1", port))
    return sock.getsockname()[1]


def select_ports():
    with ExitStack() as stack:
        for candidate in (*range(8443, 8451), 0):
            try:
                https = port_bind(stack, candidate)
                break
            except OSError:
                if candidate == 0:
                    raise
        database, api_a, api_b = [port_bind(stack, 0) for _ in range(3)]
        return https, database, api_a, api_b


def pg_tool(name, *args, check=True, timeout=35):
    with (LOGS / "postgres.log").open("ab") as output:
        return subprocess.run(
            [POSTGRES / (name + ".exe"), *args],
            stdout=output, stderr=subprocess.STDOUT,
            creationflags=HIDDEN, timeout=timeout, check=check,
        )


def prepare_database():
    if DATABASE.exists():
        return
    new_database = DATA / ("initializing-" + secrets.token_hex(8))
    new_database.mkdir()
    try:
        password = new_database / "password.txt"
        password.write_text(secrets.token_urlsafe(32) + "\n", encoding="ascii")
        pg_tool(
            "initdb", "-D", new_database / "pgdata", "-U", "dp30",
            "--auth=scram-sha-256", "--encoding=UTF8", "--locale=C",
            "--pwfile", password,
        )
        new_database.rename(DATABASE)
    finally:
        if new_database.exists() and new_database.resolve().parent == DATA.resolve():
            shutil.rmtree(new_database)


def health(port):
    context = ssl.create_default_context(cafile=str(TLS / "ca.pem"))
    direct = build_opener(ProxyHandler({}), HTTPSHandler(context=context))
    with direct.open(f"https://127.0.0.1:{port}/health", timeout=2) as response:
        return json.load(response) == {"status": "ok"}


def launch(stack, name, command, env, cwd):
    output = stack.enter_context((LOGS / (name + ".log")).open("wb"))
    process = subprocess.Popen(
        command, cwd=cwd, env=env, stdin=subprocess.DEVNULL,
        stdout=output, stderr=subprocess.STDOUT, creationflags=HIDDEN,
    )
    stack.callback(stop_worker, process)
    return process


def stop_worker(process):
    if process.poll() is None:
        process.terminate()
        process.wait(timeout=10)


def stop_database():
    if pg_tool("pg_ctl", "status", "-D", PGDATA, check=False).returncode == 0:
        pg_tool("pg_ctl", "stop", "-D", PGDATA, "-m", "fast", "-w", "-t", "15")


def start_server():
    LOGS.mkdir(parents=True, exist_ok=True)
    with (DATA / "server.lock").open("a+b") as lock:
        try:
            msvcrt.locking(lock.fileno(), msvcrt.LK_NBLCK, 1)
        except OSError as error:
            raise RuntimeError("Сервер из этой папки уже запущен.") from error
        serve()


def serve():
    PORT_FILE.unlink(missing_ok=True)
    prepare_database()
    stop_database()
    https, database, api_a, api_b = select_ports()
    print("Запуск базы и сервера ДП‑30…", flush=True)
    conn = f"host=127.0.0.1 port={database} user=dp30 connect_timeout=2"
    password = (DATABASE / "password.txt").read_text(encoding="ascii").strip()
    env = {
        **os.environ, "DP30_DATABASE_URL": conn + " dbname=dp30",
        "PGPASSWORD": password, "DP30_DEMO_PASSWORD": "Dispatch-30-demo",
    }
    with ExitStack() as cleanup:
        cleanup.callback(stop_database)
        pg_tool(
            "pg_ctl", "start", "-D", PGDATA, "-l", LOGS / "database.log",
            "-o", f"-h 127.0.0.1 -p {database}", "-w", "-t", "20",
        )
        with psycopg.connect(conn + " dbname=postgres", password=password, autocommit=True) as db:
            if not db.execute("SELECT 1 FROM pg_database WHERE datname = 'dp30'").fetchone():
                db.execute("CREATE DATABASE dp30")
        with (LOGS / "seed.log").open("wb") as output:
            subprocess.run(
                [sys.executable, "-X", "utf8", "-B", "-m", "dp30.demo"],
                cwd=ROOT / "server", env=env, stdin=subprocess.DEVNULL, stdout=output,
                stderr=subprocess.STDOUT, creationflags=HIDDEN, check=True, timeout=35,
            )
        processes = []
        for name, port in (("api1", api_a), ("api2", api_b)):
            processes.append(launch(
                cleanup, name,
                [sys.executable, "-X", "utf8", "-B", "-u", "-m", "uvicorn", "dp30.main:app",
                 "--host", "127.0.0.1", "--port", str(port), "--no-access-log"],
                env, ROOT / "server",
            ))
        processes.append(launch(
            cleanup, "caddy",
            [RUNTIME / "caddy" / "caddy.exe", "run", "--adapter", "caddyfile",
             "--config", ROOT / "ops" / "Caddyfile.local"],
            {
                **os.environ, "XDG_CONFIG_HOME": str(DATA), "XDG_DATA_HOME": str(DATA),
                "DP30_CERT_FILE": (TLS / "server.pem").as_posix(),
                "DP30_KEY_FILE": (TLS / "server.key").as_posix(),
                "DP30_PROXY_PORT": str(https), "DP30_API_PORT_A": str(api_a), "DP30_API_PORT_B": str(api_b),
            },
            ROOT,
        ))
        deadline = time.monotonic() + 25
        while time.monotonic() < deadline:
            if any(process.poll() is not None for process in processes):
                break
            try:
                if health(https):
                    PORT_FILE.write_text(str(https), encoding="ascii")
                    cleanup.callback(PORT_FILE.unlink, missing_ok=True)
                    print(f"Сервер готов. Адрес для телефона: https://127.0.0.1:{https}", flush=True)
                    print(f"Для Android Emulator: https://10.0.2.2:{https}", flush=True)
                    input("Для штатной остановки нажмите Enter.\n")
                    return
            except URLError as error:
                if isinstance(error.reason, ssl.SSLCertVerificationError):
                    raise
            time.sleep(0.5)
        raise RuntimeError("HTTPS-сервер не ответил. Проверьте журналы.")


def connect_phone():
    if not PORT_FILE.exists():
        raise RuntimeError("Сначала откройте Start-DP30-Server.cmd.")
    port = int(PORT_FILE.read_text(encoding="ascii"))
    if not health(port):
        raise RuntimeError("Сервер не ответил.")
    adb = RUNTIME / "adb" / "adb.exe"
    device = subprocess.run([adb, "-d", "get-state"], text=True, capture_output=True)
    if device.returncode != 0 or device.stdout.strip() != "device":
        raise RuntimeError("Подключите один телефон по USB и подтвердите запрос на отладку.")
    subprocess.run([adb, "-d", "install", "-r", ROOT / "dispatcher.apk"], check=True)
    subprocess.run([adb, "-d", "reverse", f"tcp:{port}", f"tcp:{port}"], check=True)
    subprocess.run([adb, "-d", "shell", "am", "start", "-n", "ru.tulsu.dp30/.MainActivity"], check=True)
    print(f"Укажите в поле «Адрес сервера» https://127.0.0.1:{port}.")


if __name__ == "__main__":
    try:
        if sys.argv[1:] == ["phone"]:
            connect_phone()
        else:
            start_server()
    except KeyboardInterrupt:
        pass
    except (OSError, subprocess.SubprocessError, RuntimeError, psycopg.Error) as error:
        print(error, file=sys.stderr)
        print(f"Журналы сервера: {LOGS}", file=sys.stderr)
        sys.exit(1)
