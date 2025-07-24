# Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
# SPDX-License-Identifier: Apache-2.0
import os
import subprocess
from argparse import ArgumentParser
from pathlib import Path
from typing import TYPE_CHECKING

from . import REPO_ROOT, Change

DEFAULT_REPO = "smithy-lang/smithy"
GITHUB_URL = os.environ.get("GITHUB_SERVER_URL", "https://github.com")
GITHUB_TOKEN = os.environ.get("GITHUB_TOKEN")

try:
    from github import Github, Auth, enable_console_debug_logging
    from github.GithubObject import NotSet

    HAS_PYGITHUB = True
except ImportError:
    HAS_PYGITHUB = False  # type: ignore


if TYPE_CHECKING:
    from github import Github, Auth
    from github.GithubObject import NotSet


def main() -> None:
    parser = ArgumentParser(
        description="""\
            Amend recently-introduced changelog entries to include a pull request \
            link. This is intended to be run in GitHub as an action, but can be run \
            manually by specifying parameters GitHub would otherwise provide in \
            environment variables.

            This only checks entries that have been staged in the current branch, \
            using git to get a list of newly introduced files. If the entry already \
            has one or more associated pull requests, it is not amended.""",
    )
    parser.add_argument(
        "-n",
        "--pull-request-number",
        required=True,
        help="The numeric identifier for the pull request.",
    )
    parser.add_argument(
        "-r",
        "--repository",
        help="""\
            The name of the repository, defaulting to 'smithy-lang/smithy'. This is \
            provided by GitHub in the GITHUB_REPOSITORY environment variable.""",
    )
    parser.add_argument(
        "-b",
        "--base",
        help="""\
            The name of the base branch to diff against, defaulting to 'main'. This \
            is provided by GitHub in the GITHUB_BASE_REF environment variable.""",
    )
    parser.add_argument(
        "-c",
        "--review-comment",
        action="store_true",
        default=False,
        help="""\
            Instead of amending the local files on disk, post a review comment on the \
            PR. This will also post a normal comment if no changelog entry is found.""",
    )

    args = parser.parse_args()
    if HAS_PYGITHUB:
        enable_console_debug_logging()
    amend(
        base=args.base,
        repository=args.repository,
        pr_number=args.pull_request_number,
        review_comment=args.review_comment,
    )


def amend(
    *,
    pr_number: str,
    repository: str | None = None,
    base: str | None = None,
    review_comment: bool = False,
) -> None:
    repository = repository or os.environ.get("GITHUB_REPOSITORY", DEFAULT_REPO)
    pr_ref = f"[#{pr_number}]({GITHUB_URL}/{repository}/pull/{pr_number})"

    changes = get_new_changes(base)
    if not changes and review_comment:
        print("No changelog found, adding reminder comment.")
        description = os.environ.get("PR_TITLE", "Example description").replace(
            '"', '\\"'
        )
        comment = (
            "This pull request does not contain a staged changelog entry. To create "
            "one, use the `./.changes/new-change` command. For example:\n\n"
            f'```\n./.changes/new-change --pull-requests "#{pr_number}" '
            f'--type feature --description "{description}"\n```\n\n'
            "Make sure that the description is appropriate for a changelog entry and "
            "that the proper feature type is used. See [`./.changes/README`]("
            f"{GITHUB_URL}/{repository}/tree/main/.changes/README) or run "
            "`./.changes/new-change -h` for more information."
        )
        post_comment(
            repository=repository,
            pr_number=pr_number,
            comment=comment,
        )

    for change_file, change in changes.items():
        if not change.pull_requests:
            print(f"Amending changelog entry without associated prs: {change_file}")
            change.pull_requests = [pr_ref]

            if review_comment:
                print("Posting amended change as a review comment.")
                comment = (
                    "Staged changelog entries should have an associated pull request "
                    "set. Commit this suggestion to associate this changelog entry "
                    "with this PR.\n\n"
                    f"```suggestion\n{change.write().strip()}\n```"
                )
                post_review_comment(
                    repository=repository,
                    pr_number=pr_number,
                    comment=comment,
                    file=change_file,
                    start_line=1,
                    end_line=len(change_file.read_text().splitlines()),
                )
            else:
                print("Writing amended change to disk.")
                change.write(change_file)


def get_new_changes(base: str | None) -> dict[Path, Change]:
    base = base or os.environ.get("GITHUB_BASE_REF", "main")
    print(f"Running a diff against base branch: {base}")
    result = subprocess.run(
        f"git diff origin/{base} --name-only",
        check=True,
        shell=True,
        capture_output=True,
    )

    new_changes: dict[Path, Change] = {}
    for changed_file in result.stdout.decode("utf-8").splitlines():
        stripped = changed_file.strip()
        if stripped.startswith(".changes/next-release") and stripped.endswith(".json"):
            file = REPO_ROOT / stripped
            print(f"Discovered newly staged changelog entry: {file}")
            new_changes[file] = Change.read(file)
    return new_changes


def post_review_comment(
    repository: str,
    pr_number: str,
    comment: str,
    file: Path,
    start_line: int | None = None,
    end_line: int | None = None,
    allow_duplicate: bool = False,
) -> None:
    gh = _assert_pygithub()
    print(f"Fetching repository: {repository}")
    repo = gh.get_repo(repository)

    print(f"Fetching pr: {pr_number}")
    pr = repo.get_pull(int(pr_number))

    commit_sha = os.environ.get("TARGET_SHA")
    if not commit_sha:
        raise ValueError(
            "The TARGET_SHA environment variable must be set to post review comments."
        )

    path = str(file.relative_to(REPO_ROOT))
    if not allow_duplicate:
        print("Fetching existing review comments to check for duplicates.")
        for existing_comment in pr.get_review_comments():
            if existing_comment.body == comment and existing_comment.path == path:
                print("Review comment already posted, skipping duplicate.")
                return

    print("Creating review comment.")
    pr.create_review_comment(
        body=comment,
        commit=repo.get_commit(sha=commit_sha),
        path=path,
        start_line=start_line if start_line is not None else NotSet,
        line=end_line if end_line is not None else NotSet,
        side="RIGHT" if start_line is not None else NotSet,
        start_side="RIGHT" if end_line is not None else NotSet,
    )


def post_comment(
    repository: str,
    pr_number: str,
    comment: str,
    allow_duplicate: bool = False,
) -> None:
    gh = _assert_pygithub()
    print(f"Fetching repository: {repository}")
    repo = gh.get_repo(repository)

    print(f"Fetching pr as issue: {pr_number}")
    pr = repo.get_issue(int(pr_number))
    if not allow_duplicate:
        print("Fetching existing comments to check for duplicates.")
        for existing_comment in pr.get_comments():
            if existing_comment.body == comment:
                print("Comment already posted, skipping duplicate.")
                return

    print("Creating comment.")
    pr.create_comment(body=comment)


def _assert_pygithub() -> "Github":
    if not HAS_PYGITHUB:
        raise Exception(
            "PyGitHub is required to perform GitHub actions.Please install the "
            "smithy_changelog package or run render via `uv run render` to ensure "
            "the dependency is available."
        )

    if not GITHUB_TOKEN:
        raise Exception("GITHUB_TOKEN environment variable was not set.")

    return Github(auth=Auth.Token(GITHUB_TOKEN), base_url=GITHUB_URL)
