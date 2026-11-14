#!/usr/bin/env python3
"""
Envoie à Supabase les e-mails du compte Vyzo (supabase/templates) et les réglages qui vont avec :
confirmation obligatoire à l'inscription, liens qui mènent au site du compte, alerte « mot de passe changé ».

    python3 scripts/mails-supabase.py            # montre l'état actuel et ce qui changera, puis demande
    python3 scripts/mails-supabase.py --voir     # montre seulement l'état actuel
    python3 scripts/mails-supabase.py --resend   # règle aussi l'envoi par Resend (SMTP)

Avec --resend, l'adresse d'expéditeur doit être sur un domaine vérifié chez Resend (https://resend.com/domains) ; la
clé d'API Resend (« re_… ») est demandée sans s'afficher, comme le jeton.

Le jeton d'accès (https://supabase.com/dashboard/account/tokens, commence par « sbp_ ») est demandé sans s'afficher et
n'est gardé nulle part. On peut aussi le passer dans SUPABASE_ACCESS_TOKEN.
"""
import getpass
import json
import os
import sys
import urllib.error
import urllib.request
from pathlib import Path

PROJECT_REF = "jucbmlwigbptgxqoiqih"
SITE_URL = "https://yannickuhrig1.github.io/vyzo/"
API = f"https://api.supabase.com/v1/projects/{PROJECT_REF}/config/auth"
TEMPLATES = Path(__file__).resolve().parent.parent / "supabase" / "templates"

# (fichier, champ du sujet, champ du contenu, sujet)
MAILS = [
    ("confirmation.html", "mailer_subjects_confirmation", "mailer_templates_confirmation_content", "Confirme ton adresse pour Vyzo"),
    ("recovery.html", "mailer_subjects_recovery", "mailer_templates_recovery_content", "Ton nouveau mot de passe Vyzo"),
    ("email_change.html", "mailer_subjects_email_change", "mailer_templates_email_change_content", "Confirme ta nouvelle adresse Vyzo"),
    ("password_changed_notification.html", "mailer_subjects_password_changed_notification",
     "mailer_templates_password_changed_notification_content", "Le mot de passe de ton compte Vyzo a changé"),
]


def call(method: str, token: str, body: dict | None = None) -> dict:
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(API, data=data, method=method, headers={
        "Authorization": f"Bearer {token}", "Content-Type": "application/json", "User-Agent": "vyzo-scripts",
    })
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            return json.loads(r.read() or b"{}")
    except urllib.error.HTTPError as e:
        detail = e.read().decode(errors="replace")[:400]
        sys.exit(f"Supabase a refusé ({e.code}) : {detail}")


def ask_token() -> str:
    token = os.environ.get("SUPABASE_ACCESS_TOKEN", "").strip()
    while not token.startswith("sbp_"):
        if token:
            print(f"  Reçu {len(token)} caractères qui ne commencent pas par « sbp_ ». Recommence (Ctrl+Maj+V pour coller).")
        token = getpass.getpass("Jeton d'accès Supabase (sbp_…, ne s'affiche pas) : ").strip()
    print(f"  Jeton reçu ({len(token)} caractères).")
    return token


def ask_resend() -> dict:
    sender = ""
    while "@" not in sender:
        sender = input("Adresse d'expéditeur, sur ton domaine vérifié chez Resend (ex. compte@mondomaine.fr) : ").strip()
    key = ""
    while not key.startswith("re_"):
        if key:
            print(f"  Reçu {len(key)} caractères qui ne commencent pas par « re_ ». Recommence (Ctrl+Maj+V pour coller).")
        key = getpass.getpass("Clé d'API Resend (re_…, ne s'affiche pas) : ").strip()
    print(f"  Clé reçue ({len(key)} caractères).")
    return {
        "smtp_host": "smtp.resend.com", "smtp_port": "465", "smtp_user": "resend", "smtp_pass": key,
        "smtp_admin_email": sender, "smtp_sender_name": "Vyzo",
    }


def allow_list(current: str) -> str:
    items = [x.strip() for x in (current or "").split(",") if x.strip()]
    for wanted in (SITE_URL, SITE_URL + "**"):
        if wanted not in items:
            items.append(wanted)
    return ",".join(items)


def main() -> None:
    only_show = "--voir" in sys.argv
    token = ask_token()
    smtp_patch = ask_resend() if "--resend" in sys.argv and not only_show else {}
    cfg = call("GET", token)

    smtp = bool(cfg.get("smtp_host"))
    print("\nÉtat actuel")
    print(f"  Adresse du site (site_url)          : {cfg.get('site_url')}")
    print(f"  Adresses de retour autorisées       : {cfg.get('uri_allow_list') or '(aucune)'}")
    print(f"  Confirmation à l'inscription        : {'non (comptes activés sans e-mail)' if cfg.get('mailer_autoconfirm') else 'oui'}")
    print(f"  Serveur d'envoi personnalisé (SMTP) : {cfg.get('smtp_host') + ' (' + str(cfg.get('smtp_sender_name') or '') + ')' if smtp else 'non'}")
    print(f"  Sujet du mail d'inscription         : {cfg.get('mailer_subjects_confirmation')}")
    print(f"  Alerte « mot de passe changé »      : {'oui' if cfg.get('mailer_notifications_password_changed_enabled') else 'non'}")
    if not cfg.get("smtp_host") and not smtp_patch:
        print("\n  ATTENTION : sans serveur d'envoi personnalisé, Supabase n'envoie les e-mails qu'aux membres de l'équipe du")
        print("  projet, et seulement quelques-uns par heure. Les autres utilisateurs ne recevront rien. Voir")
        print("  Authentication › Emails › SMTP Settings dans le tableau de bord Supabase.")
    if only_show:
        return

    patch: dict = {
        "site_url": SITE_URL,
        "uri_allow_list": allow_list(cfg.get("uri_allow_list", "")),
        "mailer_autoconfirm": False,
        "mailer_notifications_password_changed_enabled": True,
    }
    if cfg.get("smtp_host") and not cfg.get("smtp_sender_name"):
        patch["smtp_sender_name"] = "Vyzo"
    patch.update(smtp_patch)
    for file, subject_field, content_field, subject in MAILS:
        patch[subject_field] = subject
        patch[content_field] = (TEMPLATES / file).read_text(encoding="utf-8")

    print("\nÀ envoyer")
    for k, v in patch.items():
        if k.startswith("mailer_templates_"):
            print(f"  {k} : {len(v)} caractères")
        elif k == "smtp_pass":
            print(f"  {k} : (clé Resend, {len(v)} caractères)")
        elif cfg.get(k) != v:
            print(f"  {k} : {cfg.get(k)!r} → {v!r}")
    if input("\nEnvoyer ces réglages à Supabase ? [o/N] ").strip().lower() not in ("o", "oui", "y", "yes"):
        print("Rien n'a été changé.")
        return
    call("PATCH", token, patch)
    after = call("GET", token)
    # Le mot de passe SMTP n'est jamais relu par l'API ; le port peut revenir en nombre.
    ok = all(str(after.get(k)) == str(v) for k, v in patch.items() if k != "smtp_pass")
    print("\nRÉGLAGES ENVOYÉS ET VÉRIFIÉS" if ok else "\nEnvoyé, mais la relecture ne correspond pas entièrement : regarde le tableau de bord.")
    if not ok:
        sys.exit(1)


if __name__ == "__main__":
    main()
