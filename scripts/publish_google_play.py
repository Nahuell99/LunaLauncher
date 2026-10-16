#!/usr/bin/env python3
"""Upload a signed AAB to an existing Google Play testing track."""
import argparse
import json
import os
from pathlib import Path
from urllib.parse import quote

PACKAGE = "com.pierspad.betterlauncher"
API = f"https://androidpublisher.googleapis.com/androidpublisher/v3/applications/{PACKAGE}"
UPLOAD = f"https://androidpublisher.googleapis.com/upload/androidpublisher/v3/applications/{PACKAGE}"


def request(session, method, url, **kwargs):
    response = session.request(method, url, timeout=180, **kwargs)
    if not response.ok:
        try:
            message = response.json()["error"]["message"]
        except (ValueError, KeyError, TypeError):
            message = "Google Play returned an unsuccessful response"
        raise RuntimeError(f"Google Play HTTP {response.status_code}: {message}")
    return response.json() if response.content else {}


def publish(session, bundle, track, status, version_name):
    if not track or track == "production" or ":production" in track:
        raise ValueError("Choose an existing testing track; production is not supported here")
    if status not in ("draft", "completed"):
        raise ValueError("Release status must be draft or completed")
    edit_id = request(session, "POST", f"{API}/edits", json={})["id"]
    edit = f"{API}/edits/{quote(edit_id, safe='')}"
    committed = False
    try:
        tracks = request(session, "GET", f"{edit}/tracks").get("tracks", [])
        names = [item["track"] for item in tracks]
        if track not in names:
            raise ValueError(f"Track {track!r} does not exist. Available tracks: {', '.join(names)}")
        with Path(bundle).open("rb") as source:
            uploaded = request(
                session, "POST", f"{UPLOAD}/edits/{quote(edit_id, safe='')}/bundles",
                params={"uploadType": "media"},
                headers={"Content-Type": "application/octet-stream"}, data=source,
            )
        version_code = str(uploaded["versionCode"])
        release = {"name": version_name, "versionCodes": [version_code], "status": status}
        request(session, "PUT", f"{edit}/tracks/{quote(track, safe='')}",
                json={"track": track, "releases": [release]})
        # Do not cancel another review that may already be in progress.
        request(session, "POST", f"{edit}:commit",
                params={"changesInReviewBehavior": "ERROR_IF_IN_REVIEW"})
        committed = True
        return version_code
    finally:
        if not committed:
            try:
                request(session, "DELETE", edit)
            except Exception:
                pass  # Preserve the original upload/validation error.


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--bundle", required=True, type=Path)
    parser.add_argument("--track", default="beta", help="Existing API track name; beta is open testing")
    parser.add_argument("--status", choices=("draft", "completed"), default="draft")
    parser.add_argument("--version-name", required=True)
    args = parser.parse_args()
    if not args.bundle.is_file() or args.bundle.suffix != ".aab":
        parser.error("--bundle must point to a signed .aab file")
    credentials_json = os.environ.get("GOOGLE_PLAY_SERVICE_ACCOUNT_JSON")
    if not credentials_json:
        parser.error("Set the GOOGLE_PLAY_SERVICE_ACCOUNT_JSON GitHub secret first")
    from google.oauth2 import service_account
    from google.auth.transport.requests import AuthorizedSession
    credentials = service_account.Credentials.from_service_account_info(
        json.loads(credentials_json), scopes=["https://www.googleapis.com/auth/androidpublisher"]
    )
    with AuthorizedSession(credentials) as session:
        code = publish(session, args.bundle, args.track, args.status, args.version_name)
    print(f"Committed {PACKAGE} versionCode {code} to {args.track} ({args.status}).")
    print("Google Play review and managed publishing settings still apply.")


if __name__ == "__main__":
    try:
        main()
    except (RuntimeError, ValueError) as error:
        raise SystemExit(str(error))
