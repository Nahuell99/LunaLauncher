#!/usr/bin/env python3
"""Publish a signed GitHub AAB, reusing it when already uploaded to Google Play."""
import argparse
import hashlib
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
    if track not in ("production", "beta"):
        raise ValueError("Choose production or beta (open testing)")
    if track == "production" and "-" in version_name.removeprefix("v"):
        raise ValueError("Prereleases can only be published to beta")
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
        digest = hashlib.sha256(Path(bundle).read_bytes()).hexdigest()
        existing = request(session, "GET", f"{edit}/bundles").get("bundles", [])
        uploaded = next((item for item in existing if item.get("sha256", "").lower() == digest), None)
        if uploaded is None:
            with Path(bundle).open("rb") as source:
                uploaded = request(
                    session, "POST", f"{UPLOAD}/edits/{quote(edit_id, safe='')}/bundles",
                    params={"uploadType": "media"},
                    headers={"Content-Type": "application/octet-stream"}, data=source,
                )
        version_code = str(uploaded["versionCode"])
        current = next(item for item in tracks if item["track"] == track)
        releases = current.get("releases", [])
        if len(releases) == 1 and releases[0].get("versionCodes") == [version_code] and releases[0].get("status") == status:
            return version_code  # Already published; finally discards the read-only edit.
        release = {"name": version_name, "versionCodes": [version_code], "status": status}
        request(session, "PUT", f"{edit}/tracks/{quote(track, safe='')}",
                json={"track": track, "releases": [release]})
        # The maintainer's policy: replace an older pending review with the latest release.
        request(session, "POST", f"{edit}:commit",
                params={"changesInReviewBehavior": "CANCEL_IN_REVIEW_AND_SUBMIT"})
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
    parser.add_argument("--track", default="beta", choices=("production", "beta"), help="production or beta (open testing)")
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
    print(f"Published {PACKAGE} versionCode {code} to {args.track} ({args.status}).")
    print("Google Play review and managed publishing settings still apply.")


if __name__ == "__main__":
    try:
        main()
    except (RuntimeError, ValueError) as error:
        raise SystemExit(str(error))
