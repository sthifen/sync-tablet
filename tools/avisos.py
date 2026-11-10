#!/usr/bin/env python3
"""Regenera THIRD-PARTY-NOTICES.md con las dependencias del programa del PC y de
la librería de la tablet (native/): nombre, versión, licencia y el texto de su
licencia, agrupando los paquetes que comparten el mismo texto.

Uso:  python3 tools/avisos.py
"""
import json
import pathlib
import subprocess
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
NAMES = ("LICENSE", "LICENCE", "COPYING", "NOTICE", "UNLICENSE", "COPYRIGHT")


def packages(manifest, targets):
    """Los paquetes que entran de verdad en lo compilado (sin los de desarrollo)."""
    seen = {}
    for target in targets:
        cmd = ["cargo", "metadata", "--format-version", "1", "--manifest-path", str(manifest), "--filter-platform", target]
        meta = json.loads(subprocess.run(cmd, check=True, capture_output=True, text=True).stdout)
        pkgs = {p["id"]: p for p in meta["packages"]}
        nodes = {n["id"]: n for n in meta["resolve"]["nodes"]}
        root = meta["resolve"]["root"]
        todo, used = [root], set()
        while todo:
            i = todo.pop()
            if i in used:
                continue
            used.add(i)
            for d in nodes[i]["deps"]:
                if any(k["kind"] in (None, "build") for k in d["dep_kinds"]):
                    todo.append(d["pkg"])
        for i in used:
            if i == root:
                continue
            p = pkgs[i]
            seen[(p["name"], p["version"])] = p
    return seen


def license_text(p):
    folder = pathlib.Path(p["manifest_path"]).parent
    texts = []
    for f in sorted(folder.iterdir()):
        if f.is_file() and f.name.upper().startswith(NAMES):
            try:
                texts.append(f.read_text(encoding="utf-8", errors="replace").strip())
            except OSError:
                pass
    if p.get("license_file"):
        f = folder / p["license_file"]
        if f.is_file():
            texts.append(f.read_text(encoding="utf-8", errors="replace").strip())
    # En los repositorios con varios paquetes, la licencia suele estar en la raíz.
    up = folder
    while not texts and "/.cargo/git/checkouts/" in str(up) and up.parent != up:
        up = up.parent
        for f in sorted(up.iterdir()):
            if f.is_file() and f.name.upper().startswith(NAMES):
                texts.append(f.read_text(encoding="utf-8", errors="replace").strip())
        if up.parent.parent.name == "checkouts":
            break
    return "\n\n---\n\n".join(dict.fromkeys(texts))


def main():
    all_pkgs = {}
    all_pkgs.update(packages(ROOT / "Cargo.toml", ["x86_64-unknown-linux-gnu"]))
    all_pkgs.update(packages(ROOT / "native" / "Cargo.toml", ["aarch64-linux-android"]))
    # Lo propio no es de terceros.
    for own in ("synctablet", "synctablet-native"):
        for k in [k for k in all_pkgs if k[0] == own]:
            del all_pkgs[k]
    groups, missing = {}, []
    for (name, version), p in sorted(all_pkgs.items()):
        lic = p.get("license") or "sin declarar"
        text = license_text(p)
        if not text:
            missing.append(f"- {name} {version} ({lic})")
            continue
        groups.setdefault(text, []).append(f"- {name} {version} ({lic})")
    out = ["# Avisos de terceros", "",
           "Sync Tablet incluye estos componentes en sus archivos compilados, cada uno con su propia licencia. "
           "Sync Tablet se distribuye bajo la licencia MIT (ver `LICENSE`).", ""]
    if missing:
        out += ["## Sin archivo de licencia en el paquete", "",
                "Estos paquetes declaran su licencia en su manifiesto, pero no incluyen el texto:", ""] + missing + [""]
    for n, (text, names) in enumerate(sorted(groups.items(), key=lambda g: g[1][0]), 1):
        out += [f"## Grupo {n}", ""] + names + ["", "```text", text.replace("```", "``​`"), "```", ""]
    (ROOT / "THIRD-PARTY-NOTICES.md").write_text("\n".join(out), encoding="utf-8")
    print(f"{len(all_pkgs)} paquetes, {len(groups)} grupos, {len(missing)} sin texto", file=sys.stderr)


if __name__ == "__main__":
    main()
