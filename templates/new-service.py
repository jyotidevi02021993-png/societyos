#!/usr/bin/env python3
"""Create a new independent SocietyOS service from templates/service-template.

usage: python templates/new-service.py <service-name> <java-package> <port> "<description>"
  e.g. python templates/new-service.py security-service security 8083 "Gate and security"

The database is <java-package>_db with roles <java-package>_owner / <java-package>_app
(already created by infra/docker/postgres/init for every planned service).
"""
import os
import shutil
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
TEMPLATE = os.path.join(ROOT, "templates", "service-template")


def main() -> None:
    if len(sys.argv) != 5:
        print(__doc__)
        sys.exit(1)
    name, pkg, port, description = sys.argv[1:]
    target = os.path.join(ROOT, "services", name)
    if os.path.exists(target):
        sys.exit(f"{target} already exists")
    pascal = "".join(p.capitalize() for p in pkg.split("_"))

    shutil.copytree(TEMPLATE, target, ignore=shutil.ignore_patterns("target"))
    for base in ("src/main/java/in/societyos", "src/test/java/in/societyos"):
        os.rename(os.path.join(target, base, "template"), os.path.join(target, base, pkg))
    app = os.path.join(target, "src/main/java/in/societyos", pkg, "TemplateApplication.java")
    os.rename(app, app.replace("TemplateApplication", f"{pascal}Application"))

    replacements = [
        ("in.societyos.template", f"in.societyos.{pkg}"),
        ("TemplateApplication", f"{pascal}Application"),
        ("template-service", name),
        ("template_db", f"{pkg}_db"),
        ("template_app", f"{pkg}_app"),
        ("template_owner", f"{pkg}_owner"),
        ("${PORT:8099}", "${PORT:" + port + "}"),
        ("SocietyOS service template (copy, then rename).", description),
        ("# TEMPLATE: replace \"template\" with the service name, set the port and database name.\n", ""),
    ]
    for dirpath, _, files in os.walk(target):
        if "/.mvn" in dirpath.replace("\\", "/"):
            continue
        for f in files:
            if not f.endswith((".java", ".yml", ".xml", ".sql", ".md")):
                continue
            path = os.path.join(dirpath, f)
            text = open(path, encoding="utf8").read()
            for a, b in replacements:
                text = text.replace(a, b)
            open(path, "w", encoding="utf8", newline="\n").write(text)
    print(f"Created services/{name} (package in.societyos.{pkg}, port {port}, db {pkg}_db)")


if __name__ == "__main__":
    main()
