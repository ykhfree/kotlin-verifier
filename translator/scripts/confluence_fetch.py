#!/usr/bin/env python3
"""DESIGN-C.md 10.8 (C6): fetch Confluence Cloud pages in storage format, the input of `kv spec snapshot`.

Usage:
  python3 -I confluence_fetch.py --site https://SITE.atlassian.net --email EMAIL --keychain-service NAME
                                 --out DIR PAGE_ID...

For each PAGE_ID, the script sends GET SITE/wiki/api/v2/pages/PAGE_ID?body-format=storage (Confluence Cloud REST API
v2) with basic authentication: EMAIL and an Atlassian API token. It writes two files per page:
  DIR/PAGE_ID.xhtml      body.storage.value of the response, unchanged, in UTF-8.
  DIR/PAGE_ID.page.json  "id", "title", "status", "version" (the version number), "createdAt" (the time of that
                         version), "site" (--site without a trailing slash) and "fetchedAt" (the UTC time of the
                         response).
The files hold the page as Confluence returns it. No secret check runs here; `kv spec snapshot` does it. Do not
commit DIR.

The API token:
  The script reads the token at run time from the macOS Keychain:
    security find-generic-password -s NAME -a EMAIL -w
  To store the token once, run this command and type the token at the prompt:
    security add-generic-password -s NAME -a EMAIL -w
  Put -w last and give it no value. A value on the command line goes into the shell history and the process list.
  Add -U to replace an item that exists.
  The script never writes the token, the Authorization header or base64(EMAIL:token) to stdout, stderr or a file.

--site must be https://HOST or https://HOST:PORT. Plain http:// is allowed only to localhost and 127.0.0.1 (the fake
server of the tests), so the token never goes over plain HTTP to another host. The script checks --site and the page
IDs before it reads the token. It does not follow redirects, because urllib would send the Authorization header to
the new location.

Errors:
  401: authentication failed (check EMAIL and the token).
  404: the page does not exist, or EMAIL has no permission to view it (the endpoint documents no 403).
  429 and 503: the script waits Retry-After seconds (an integer, at most 60), or 1, 2 and 4 s if the header is absent
    or not an integer, and tries again. It retries at most 3 times.
  A response without body.storage.value, or without another field of the .page.json, is an error.
The script fetches the pages in the given order and stops at the first page that fails. The files of the earlier
pages stay in DIR. The failed page and the later pages get no new file; their files from an earlier run, if any, stay
unchanged.

Exit codes:
  0  every page was fetched.
  2  a usage error (a bad --site, a page ID that is not a number), or a page failed: HTTP 401 or 404, 429 or 503
     after 3 retries, another HTTP status (a redirect too), a network error, or a response without a field above.
  3  the token cannot be read from the Keychain.
"""
import argparse
import base64
import datetime
import http.client
import json
import os
import re
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

# 429 and 503 get at most this many retries.
RETRIES = 3
# The wait before each retry if the response has no integer Retry-After header.
BACKOFF = (1, 2, 4)
# The upper limit of one wait, in seconds.
MAX_WAIT = 60
# The only hosts that plain http:// may go to.
LOCAL_HOSTS = ("localhost", "127.0.0.1")
# The socket timeout of one request, in seconds.
TIMEOUT = 60


class Failure(Exception):
    """A failure with its exit code. Its message goes to stderr, so it never holds the token."""

    def __init__(self, message, code=2):
        super().__init__(message)
        self.code = code


def check_site(site):
    """Returns SITE as SCHEME://HOST[:PORT]. Raises Failure if SITE is not https or local http, or has more parts."""
    u = urllib.parse.urlsplit(site)
    try:
        u.port
    except ValueError:
        raise Failure("--site %s: the port is not a number" % site)
    if u.scheme not in ("http", "https") or (u.scheme == "http" and u.hostname not in LOCAL_HOSTS):
        raise Failure("--site %s: use https:// (plain http:// only to localhost or 127.0.0.1)" % site)
    if not u.hostname or "@" in u.netloc or u.path not in ("", "/") or u.query or u.fragment:
        raise Failure("--site %s: give only the scheme and the host, as in https://SITE.atlassian.net" % site)
    return "%s://%s" % (u.scheme, u.netloc)


def read_token(service, email):
    """Returns the API token of the Keychain item (SERVICE, EMAIL). Raises Failure (exit 3) if it cannot."""
    where = "the Keychain item with service %s and account %s" % (service, email)
    try:
        r = subprocess.run(["security", "find-generic-password", "-s", service, "-a", email, "-w"],
                           stdin=subprocess.DEVNULL, capture_output=True, encoding="utf-8", errors="replace")
    except OSError as e:
        raise Failure("cannot run security to read %s: %s" % (where, e), 3)
    # On failure, security prints no password, so its stderr is safe to show.
    if r.returncode != 0:
        raise Failure("cannot read %s: security exited %d: %s" % (where, r.returncode, r.stderr.strip()), 3)
    token = r.stdout[:-1] if r.stdout.endswith("\n") else r.stdout
    if not token:
        raise Failure("%s holds an empty token" % where, 3)
    return token


def wait_seconds(retry_after, attempt):
    """The wait before retry ATTEMPT (0 first): RETRY_AFTER if it is an integer (at most MAX_WAIT), else BACKOFF."""
    if retry_after is not None and re.fullmatch(r"[0-9]+", retry_after.strip()):
        return min(int(retry_after.strip()), MAX_WAIT)
    return BACKOFF[attempt]


class NoRedirect(urllib.request.HTTPRedirectHandler):
    """Refuses every redirect, so urllib raises HTTPError with the 3xx status.

    urllib copies the request headers into the redirected request, the Authorization header too, even to another host
    or to http://.
    """

    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


OPENER = urllib.request.build_opener(NoRedirect)


def http_failure(status, reason):
    """The Failure of an HTTP error response that gets no more retries."""
    if status == 401:
        return Failure("HTTP 401: authentication failed; check --email and the API token in the Keychain")
    if status == 404:
        return Failure("HTTP 404: the page was not found, or the account has no permission to view it")
    if status in (429, 503):
        return Failure("HTTP %d %s after %d retries" % (status, reason, RETRIES))
    if 300 <= status < 400:
        return Failure("HTTP %d %s: the script does not follow redirects; check --site" % (status, reason))
    return Failure("HTTP %d %s" % (status, reason))


def get_page(site, page_id, credentials):
    """Returns the JSON of GET /wiki/api/v2/pages/PAGE_ID?body-format=storage. Retries 429 and 503."""
    url = "%s/wiki/api/v2/pages/%s?body-format=storage" % (site, page_id)
    # Confluence sends no authentication challenge, so the header goes with the first request.
    request = urllib.request.Request(url, headers={"Authorization": "Basic " + credentials, "Accept": "application/json"})
    for attempt in range(RETRIES + 1):
        try:
            with OPENER.open(request, timeout=TIMEOUT) as response:
                data = response.read()
            break
        except urllib.error.HTTPError as e:
            retry_after = e.headers.get("Retry-After")
            e.close()
            if e.code not in (429, 503) or attempt == RETRIES:
                raise http_failure(e.code, e.reason)
            time.sleep(wait_seconds(retry_after, attempt))
        except (OSError, http.client.HTTPException) as e:
            raise Failure("cannot reach %s: %s" % (site, getattr(e, "reason", e)))
    try:
        return json.loads(data)
    except ValueError:
        raise Failure("the response is not JSON")


def field(page, *path):
    """Returns the value at PATH in PAGE. Raises Failure if a key is missing."""
    value = page
    for key in path:
        if not isinstance(value, dict) or key not in value:
            raise Failure("the response has no %s" % ".".join(path))
        value = value[key]
    return value


def write_page(out, site, page_id, page, fetched_at):
    """Writes OUT/PAGE_ID.xhtml and OUT/PAGE_ID.page.json; returns the .page.json fields."""
    # Read every field before the first write, so a response with a missing field leaves no file.
    body = field(page, "body", "storage", "value")
    if not isinstance(body, str):
        raise Failure("body.storage.value is not a string")
    meta = {
        "id": field(page, "id"),
        "title": field(page, "title"),
        "status": field(page, "status"),
        "version": field(page, "version", "number"),
        "createdAt": field(page, "version", "createdAt"),
        "site": site,
        "fetchedAt": fetched_at,
    }
    # newline="" writes the line ends of the value unchanged.
    with open(os.path.join(out, page_id + ".xhtml"), "w", encoding="utf-8", newline="") as f:
        f.write(body)
    with open(os.path.join(out, page_id + ".page.json"), "w", encoding="utf-8") as f:
        f.write(json.dumps(meta, indent=1, ensure_ascii=False) + "\n")
    return meta


def utc_now():
    """The current UTC time in the form of Confluence: YYYY-MM-DDTHH:mm:ss.sssZ."""
    return datetime.datetime.now(datetime.timezone.utc).isoformat(timespec="milliseconds").replace("+00:00", "Z")


def main(argv):
    p = argparse.ArgumentParser(prog="confluence_fetch.py", description="Fetch Confluence Cloud pages in storage format (see the module docstring).")
    p.add_argument("--site", required=True, help="https://SITE.atlassian.net")
    p.add_argument("--email", required=True, help="the email of the Atlassian account")
    p.add_argument("--keychain-service", required=True, help="the service name of the Keychain item that holds the API token")
    p.add_argument("--out", required=True, help="the output directory")
    p.add_argument("pages", nargs="+", metavar="PAGE_ID")
    args = p.parse_args(argv)
    try:
        site = check_site(args.site)
        bad = [x for x in args.pages if not re.fullmatch(r"[0-9]+", x)]
        if bad:
            raise Failure("not a page ID (a page ID is a number): %s" % " ".join(bad))
        token = read_token(args.keychain_service, args.email)
        os.makedirs(args.out, exist_ok=True)
    except Failure as e:
        print("confluence_fetch.py: %s" % e, file=sys.stderr)
        return e.code
    credentials = base64.b64encode(("%s:%s" % (args.email, token)).encode("utf-8")).decode("ascii")
    for n, page_id in enumerate(args.pages):
        try:
            page = get_page(site, page_id, credentials)
            meta = write_page(args.out, site, page_id, page, utc_now())
        except Failure as e:
            print("confluence_fetch.py: page %s: %s" % (page_id, e), file=sys.stderr)
            if len(args.pages) > 1:
                print("confluence_fetch.py: stopped; %d of %d page(s) fetched (their files stay in %s); not fetched: %s"
                      % (n, len(args.pages), args.out, " ".join(args.pages[n:])), file=sys.stderr)
            return e.code
        print("fetched %s: %s (version %s, %s)" % (page_id, meta["title"], meta["version"], meta["status"]))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
