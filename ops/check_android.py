"""Run the demo Android UI route on an isolated emulator and test database.

Set DP30_DEMO_PASSWORD. The emulator is resized for the check. Offline mode
toggles Wi-Fi and mobile data on the selected emulator only.
"""
import argparse
import contextlib
import json
import os
from pathlib import Path
import re
import subprocess
import time
import xml.etree.ElementTree as ET


class Android:
    app = "ru.tulsu.dp30"

    def __init__(self, args):
        self.command = [args.adb, "-s", args.device]
        self.endpoint = args.endpoint
        self.password = os.environ["DP30_DEMO_PASSWORD"]
        self.height = args.height

    def adb(self, *args):
        return subprocess.run([*self.command, *map(str, args)], check=True, capture_output=True,
                              timeout=35).stdout.decode("utf-8", errors="replace").strip()

    def start(self):
        self.adb("shell", "am", "start", "-n", self.app + "/.MainActivity")

    def inputs(self, *args):
        self.adb("shell", "input", *args)

    def nodes(self):
        path = "/sdcard/dp30-ui.xml"
        for attempt in range(3):
            try:
                self.adb("shell", "uiautomator", "dump", path)
                return list(ET.fromstring(self.adb("exec-out", "cat", path)).iter("node"))
            except (subprocess.SubprocessError, ET.ParseError):
                if attempt == 2:
                    raise
                time.sleep(0.4)

    def scroll(self, forward=True):
        bottom = self.height - 155
        start, end = (bottom, 244) if forward else (244, bottom)
        self.inputs("swipe", 180, start, 180, end, 390)

    def top(self):
        for _ in range(3):
            self.scroll(False)

    def find(self, label, *, field=False, forward=True, checks=8):
        for step in range(checks):
            nodes = self.nodes()
            for node in nodes:
                if node.get("package") != self.app:
                    continue
                if field:
                    if node.get("class") == "android.widget.EditText" and any(
                        child.get("text") == label for child in node.iter("node") if child != node
                    ):
                        return node
                elif re.fullmatch(label, node.get("text", "")):
                    return node
            if step == 0:
                time.sleep(0.6)
            elif step < checks - 1:
                self.scroll(forward)
        shown = [n.get("text") for n in nodes if n.get("text") and n.get("package") == self.app]
        raise AssertionError(f"Элемент {label!r} недоступен. Видимый текст: {shown}")

    def touch(self, node):
        x1,y1,x2,y2 = map(int,re.findall(r"\d+",node.get("bounds","")))
        if x2 <= x1 or y2 <= y1:
            raise AssertionError(f"Элемент закрыт: {node.attrib}")
        self.inputs("tap", (x1+x2)//2, (y1+y2)//2)

    def tap(self, label, forward=True):
        self.touch(self.find(label, forward=forward))

    def enter(self, label, text):
        node = self.find(label, field=True)
        old = node.get("text", "")
        self.touch(node)
        if old:
            self.inputs("keyevent", "KEYCODE_MOVE_END")
            self.inputs("keyevent", *["KEYCODE_DEL"] * len(old))
        self.inputs("text", text.replace(" ", "%s"))
        self.inputs("keyevent", "KEYCODE_BACK")

    def login(self, name):
        self.top()
        self.tap("Адрес сервера")
        if self.find("HTTPS-адрес сервера", field=True).get("text") != self.endpoint:
            self.enter("HTTPS-адрес сервера", self.endpoint)
        self.tap("Скрыть адрес сервера", forward=False)
        self.top()
        self.enter("Логин", name)
        self.enter("Пароль", self.password)
        self.tap("Войти")
        heading = {"dispatcher": "Заявки", "worker": "Мои задания", "manager": "Заявки",
                   "admin": "Доступ и справочники"}[name]
        self.find(heading)

    def logout(self):
        self.top()
        self.tap("Профиль", forward=False)
        self.tap("Выйти из приложения")
        self.tap("Выйти и удалить")
        self.find("Вход")

    def card(self, number, status):
        self.top()
        self.enter("Номер заявки", str(number))
        self.tap("Найти и обновить")
        self.tap(f"№ {number}")
        self.find(f"Заявка № {number}", forward=False)
        self.find(status)

    def set_network(self, enabled):
        state = "enable" if enabled else "disable"
        self.adb("shell", "svc", "wifi", state)
        self.adb("shell", "svc", "data", state)

    def route(self, offline):
        self.login("dispatcher")
        self.tap("Создать заявку")
        self.enter("Адрес", "DemoSite")
        self.enter("Описание", "Equipment inspection")
        self.tap("Зарегистрировать")
        title = self.find(r"Заявка № \d+", forward=False).get("text")
        number = int(title.rsplit(" ",1)[1])
        self.tap("Бригада · Выберите бригаду")
        self.tap("Бригада Север")
        self.tap("Назначить бригаду")
        self.top()
        self.find("Назначена")
        self.logout()
        self.login("worker")
        self.card(number, "Назначена")
        self.tap("Принять задание")
        self.tap("Начать работы")
        self.top()
        self.find("В работе")
        if offline:
            observed = self.offline_result(number)
        else:
            self.enter("Результат работ", "Inspection complete")
            self.tap("Сохранить черновик")
            self.tap("Передать результат")
            self.top()
            self.find("Выполнена")
            observed = None
        self.tap("Показать историю")
        self.find(r"Результат передан · .*")
        self.logout()
        self.login("dispatcher")
        self.card(number, "Выполнена")
        self.tap("Закрыть заявку")
        self.top()
        self.find("Закрыта")
        self.logout()
        self.login("manager")
        self.tap("Сводка")
        self.find(r"Всего заявок: \d+")
        self.logout()
        self.login("admin")
        self.tap("Раздел · Пользователи")
        self.tap("Справочники")
        self.find("Насосное оборудование")
        self.logout()
        return number, observed

    def offline_result(self, number):
        self.set_network(False)
        self.enter("Результат работ", "Inspection complete")
        self.tap("Сохранить черновик")
        self.tap("Передать результат")
        self.find(r"Ожидает подтверждения.*")
        self.adb("shell", "am", "force-stop", self.app)
        self.start()
        self.card(number, "В работе")
        saved = self.find("Результат работ", field=True)
        assert saved.get("text") == "Inspection complete", saved.attrib
        self.find(r"Ожидает подтверждения.*", forward=False)
        start = time.monotonic()
        self.set_network(True)
        self.top()
        self.find("Выполнена", checks=12)
        return time.monotonic() - start


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--adb", required=True)
    parser.add_argument("--device", required=True)
    parser.add_argument("--apk", required=True)
    parser.add_argument("--endpoint", default="https://10.0.2.2:8448")
    parser.add_argument("--height", type=int, default=780)
    parser.add_argument("--offline", action="store_true")
    parser.add_argument("--offline-card", type=int, help="Check only a fresh seeded in-work request")
    parser.add_argument("--out", type=Path)
    args = parser.parse_args()
    if not args.device.startswith("emulator-"):
        parser.error("This automated check is restricted to a dedicated emulator.")
    a = Android(args)
    a.adb("install", "-r", args.apk)
    a.adb("shell", "wm", "size", f"360x{args.height}")
    a.adb("shell", "wm", "density", "160")
    a.adb("shell", "settings", "put", "system", "font_scale", "1.5")
    try:
        a.set_network(True)
        # Reset this app's isolated emulator data before exercising the first login.
        a.adb("shell", "pm", "clear", a.app)
        a.start()
        if args.offline_card:
            rid = args.offline_card
            a.login("worker")
            a.card(rid, "В работе")
            convergence = a.offline_result(rid)
        else:
            rid, convergence = a.route(args.offline)
    finally:
        with contextlib.suppress(subprocess.SubprocessError):
            a.set_network(True)
    result = {"device": args.device, "api": a.adb("shell", "getprop", "ro.build.version.sdk"),
              "width_dp": 360, "font_scale": 1.5, "request": rid, "offline": bool(args.offline or args.offline_card),
              "scenario": "offline-retry" if args.offline_card else "role-and-request-route",
              "seconds_until_confirmation_seen": convergence, "result": "passed"}
    report = json.dumps(result, ensure_ascii=False, indent=2)
    if args.out:
        args.out.write_text(report + "\n", encoding="utf-8")
    print(report)


if __name__ == "__main__":
    main()
