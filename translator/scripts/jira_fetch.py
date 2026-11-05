#!/usr/bin/env python3
"""DESIGN-J.md section 7: fetch Jira Cloud issues, the input of `kv spec snapshot`.

Usage:
  python3 -I jira_fetch.py --site https://SITE.atlassian.net --email EMAIL --keychain-service NAME --out DIR ISSUE...

ISSUE is a key (KV-7) or a numeric id (10042). The script checks --site and each ISSUE before it reads the token.

For each ISSUE, the script sends these requests (Jira Cloud REST API v3) with basic authentication: EMAIL and an
Atlassian API token. ID is the "id" of the first response, so the later requests do not look up a moved key again.
  GET SITE/rest/api/3/issue/ISSUE?fields=*all,-comment&expand=names,schema&failFast=true
  GET SITE/rest/api/3/issue/ID/remotelink
  GET SITE/rest/api/3/issue/ID/comment?startAt=S&maxResults=100&orderBy=created      (repeated)

It writes one file per issue, DIR/ID.jira.json, in UTF-8, with these keys:
  "format"       "kv-jira-issue/1".
  "requested"    ISSUE as given.
  "site"         --site without a trailing slash.
  "fetchedAt"    the UTC time after the last response.
  "issue"        the first response, unchanged.
  "remoteLinks"  the remotelink response, unchanged, or null on HTTP 403.
  "comments"     the comments of all pages, in order.
The file holds the issue as Jira returns it: names, email addresses and secrets too. `kv spec snapshot` is the gate
for them. Do not commit DIR. The script does not add --email to the file.

Stdout never holds the summary, because that output can reach an LLM. It holds two lines per issue:
  fetched KV-7: id 10042, 14 comment(s), updated 2026-11-01T10:00:00.000+0900
  snapshot input: jira:10042=DIR/10042.jira.json
The "updated" part is only for show: a response without it is no error, and the part is left out.

The comment pages:
  S starts at 0 and grows by the number of comments received, because the server can return fewer than maxResults.
  An empty page ends the run. Jira documents "total" in two ways: the items in this page, or the items in all pages.
  Under both readings, the comments received are at least the "total" of the empty page; if not, it is an error.
  A comment id that comes again is an error, so a server that ignores startAt does not cause an endless loop.

Notices on stderr, with exit 0:
  A key in the response that differs from a requested key: the key differs in case, or the issue moved. Anchors use
  the id, so they stay.
  HTTP 403 on the remote links: the docs tie this status to disabled issue linking.

Errors:
  401, 404, 429 and 503, the token, --site and redirects work as in confluence_fetch.py, and the code is shared. The
  docstring of confluence_fetch.py says how to store the API token in the Keychain.
  Another HTTP status on the first request can mean that one field failed under failFast=true. The docs give no status
  for that failure, so the message says it can be the reason.
The script fetches the issues in the given order and stops at the first issue that fails. The files of the earlier
issues stay in DIR. A failed issue gets no new file, also if only one comment page failed: in a snapshot, a part of
the comments looks like deleted comments. A file from an earlier run stays unchanged.

Exit codes:
  0  every issue was fetched.
  2  a usage error, or an issue failed: HTTP 401 or 404, 429 or 503 after 3 retries, another HTTP status (a redirect
     too), a network error, a missing field, an "id" that is not a number, a "total" above the comments received, a
     repeated comment id, or an unpaired surrogate in a response.
  3  the token cannot be read from the Keychain.
"""
import importlib.util
import json
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
# Loading confluence_fetch.py must not add a __pycache__ file next to the scripts.
sys.dont_write_bytecode = True


def load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


confluence = load("confluence_fetch", os.path.join(HERE, "confluence_fetch.py"))
Failure = confluence.Failure
field = confluence.field
get_json = confluence.get_json

# The maxResults of a comment request.
PAGE_SIZE = 100
NOT_FOUND = "the issue was not found, or the account has no permission to view it"
NUMERIC_ID = r"[0-9]+"
ISSUE_KEY = r"[A-Za-z0-9_]+-[0-9]+"
# The statuses that have their own message, so a failFast failure is not their likely reason.
NAMED_STATUSES = (401, 404, 429, 503)


def get_issue(site, issue, credentials):
    """Returns the JSON of Get issue. A message of another status says that it can be a failFast failure."""
    url = "%s/rest/api/3/issue/%s?fields=*all,-comment&expand=names,schema&failFast=true" % (site, issue)
    try:
        return get_json(site, url, credentials, NOT_FOUND)
    except Failure as e:
        if e.status is not None and e.status >= 400 and e.status not in NAMED_STATUSES:
            raise Failure("%s; with failFast=true, one field that failed to load can be the reason" % e, status=e.status)
        raise


def get_remote_links(site, issue, issue_id, credentials):
    """Returns the JSON of the remote links, or None with one notice on HTTP 403."""
    url = "%s/rest/api/3/issue/%s/remotelink" % (site, issue_id)
    try:
        return get_json(site, url, credentials, NOT_FOUND)
    except Failure as e:
        if e.status != 403:
            raise
    print("%s: HTTP 403 on the remote links; issue linking can be disabled; remoteLinks is null" % issue, file=sys.stderr)
    return None


def get_comments(site, issue_id, credentials):
    """Returns the comments of all pages, in order (see "The comment pages" in the module docstring)."""
    comments = []
    ids = set()
    while True:
        url = "%s/rest/api/3/issue/%s/comment?startAt=%d&maxResults=%d&orderBy=created" % (site, issue_id, len(comments), PAGE_SIZE)
        response = get_json(site, url, credentials, NOT_FOUND)
        page = field(response, "comments")
        if not isinstance(page, list):
            raise Failure("comments is not an array")
        if not page:
            break
        for comment in page:
            comment_id = field(comment, "id")
            if not isinstance(comment_id, str):
                raise Failure("a comment id is not a string")
            if comment_id in ids:
                raise Failure("comment %s came twice; the server can ignore startAt" % comment_id)
            ids.add(comment_id)
        comments.extend(page)
    total = field(response, "total")
    if not isinstance(total, int) or len(comments) < total:
        raise Failure("got %d comment(s), but the last response has total %s" % (len(comments), total))
    return comments


def fetch_issue(site, credentials, out, issue):
    """Fetches ISSUE, writes OUT/ID.jira.json and prints the two lines on stdout."""
    response = get_issue(site, issue, credentials)
    issue_id = field(response, "id")
    # The id goes into the next URLs and into the file name.
    if not isinstance(issue_id, str) or not re.fullmatch(NUMERIC_ID, issue_id):
        raise Failure("the id of the response is not a number")
    for name in ("names", "schema"):
        field(response, name)
    fields = field(response, "fields")
    if not isinstance(fields, dict):
        raise Failure("fields is not an object")
    key = field(response, "key")
    # A numeric ISSUE is not a key, so a key in the response cannot differ from it.
    if not re.fullmatch(NUMERIC_ID, issue) and key != issue:
        print("%s: Jira returned key %s (id %s); the key differs in case or the issue moved; anchors use the id, so they stay" % (issue, key, issue_id), file=sys.stderr)
    remote_links = get_remote_links(site, issue, issue_id, credentials)
    comments = get_comments(site, issue_id, credentials)
    document = {
        "format": "kv-jira-issue/1",
        "requested": issue,
        "site": site,
        "fetchedAt": confluence.utc_now(),
        "issue": response,
        "remoteLinks": remote_links,
        "comments": comments,
    }
    # Encode before the file opens, so an unpaired surrogate leaves no file.
    try:
        data = (json.dumps(document, indent=1, ensure_ascii=False) + "\n").encode("utf-8")
    except UnicodeEncodeError:
        raise Failure("a response holds an unpaired surrogate, so it cannot be written in UTF-8")
    path = os.path.join(out, issue_id + ".jira.json")
    with open(path, "wb") as f:
        f.write(data)
    updated = ", updated %s" % fields["updated"] if fields.get("updated") is not None else ""
    print("fetched %s: id %s, %d comment(s)%s" % (issue, issue_id, len(comments), updated))
    print("snapshot input: jira:%s=%s" % (issue_id, path))


def main(argv):
    return confluence.run_command(argv, "jira_fetch.py", "Fetch Jira Cloud issues (see the module docstring).", "issue", "ISSUE",
                                  "%s|%s" % (ISSUE_KEY, NUMERIC_ID), "not an issue key or a numeric id", fetch_issue)


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
