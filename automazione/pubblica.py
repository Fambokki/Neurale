#!/usr/bin/env python3
"""Pubblica un'edizione di NEURALE nel repository: l'app Android la scarica da sola.

Uso: python3 automazione/pubblica.py /home/claude/neurale-lavoro/out
(la cartella deve contenere NEURALE-AAAA-MM-GG.pdf e meta.json)
"""
import json, os, shutil, subprocess, sys, time

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


def git(*args, check=True):
    r = subprocess.run(["git", "-C", REPO, *args], capture_output=True, text=True)
    if check and r.returncode != 0:
        raise SystemExit(f"git {' '.join(args)} non riuscito:\n{r.stdout}\n{r.stderr}")
    return r


def main(out):
    meta = json.load(open(os.path.join(out, "meta.json"), encoding="utf-8"))
    eid = meta["id"]
    pdf_src = os.path.join(out, f"NEURALE-{eid}.pdf")
    if not os.path.isfile(pdf_src):
        raise SystemExit(f"manca {pdf_src}")

    git("pull", "--rebase", "--depth=50", "origin", "main", check=False)

    os.makedirs(os.path.join(REPO, "pdf"), exist_ok=True)
    rel = f"pdf/NEURALE-{eid}.pdf"
    shutil.copyfile(pdf_src, os.path.join(REPO, rel))

    path = os.path.join(REPO, "editions.json")
    data = json.load(open(path, encoding="utf-8")) if os.path.exists(path) else {"editions": []}
    eds = [e for e in data.get("editions", []) if e.get("id") != eid]
    entry = {k: meta.get(k) for k in ("id", "numero", "titolo", "sommario", "richiami", "stile") if meta.get(k) is not None}
    entry["pdf"] = rel
    entry["rev"] = int(time.time())  # cambia a ogni pubblicazione: l'app riscarica le edizioni corrette
    eds.append(entry)
    eds.sort(key=lambda e: e.get("id", ""), reverse=True)
    data["editions"] = eds
    with open(path, "w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False, indent=1)

    git("add", rel, "editions.json")
    if git("diff", "--cached", "--quiet", check=False).returncode == 0:
        print("niente da pubblicare (edizione già presente)")
        return
    git("-c", "user.name=NEURALE", "-c", "user.email=noreply@users.noreply.github.com",
        "commit", "-m", f"Edizione N. {meta.get('numero', '?')} — {eid}")
    for tentativo in range(3):
        r = git("-c", "push.negotiate=true", "push", "origin", "main", check=False)
        if r.returncode == 0:
            break
        git("pull", "--rebase", "--depth=50", "origin", "main", check=False)
    else:
        raise SystemExit("push non riuscito:\n" + r.stderr)
    head = git("rev-parse", "HEAD").stdout.strip()
    remote = git("ls-remote", "origin", "main").stdout.split()[0]
    print(f"pubblicata {eid}: commit {head[:7]} (remoto {remote[:7]})")
    if head != remote:
        raise SystemExit("il commit non risulta sul remoto")


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "/home/claude/neurale-lavoro/out")
