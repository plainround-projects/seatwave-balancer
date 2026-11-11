# Git: `.gitignore` and `.gitattributes`

## What it is

**Git** records the history of a folder of files. A **repository** is that folder plus its history. A file Git records is **tracked**. Git also lets you declare which files it should leave alone and how it should treat the line endings of the rest. Two plain-text files do that, and both sit at the root of the repository: `.gitignore` (leave these alone) and `.gitattributes` (treat these files in this way).

A **line ending** is the invisible character that ends a line of text. Unix systems (macOS, Linux) use one character, **LF** (line feed, `\n`). Windows uses two, **CRLF** (carriage return then line feed, `\r\n`).

## Why this repository uses it

Gradle creates two folders while it works, `build/` and `.gradle/`, and the editor and the operating system add more files. None belongs in the history. This repository has one more reason: `scripts/stack.sh` makes a throwaway database password into `build/secrets/db.password` (`shell-scripts-walkthrough.md`), and a password must never be committed. The database container reads that file through a Compose secret (`docker-compose.md`), so every machine makes its own. And two scripts need fixed line endings to run: `gradlew` needs LF, `gradlew.bat` needs CRLF, whoever checks them out and on whatever system.

## Where it appears

| Path | What it does |
| --- | --- |
| `.gitignore` | Nine patterns for files Git must not track, and one comment. |
| `.gitattributes` | Two rules for line endings. |
| `.editorconfig` | The editor-side counterpart (`editorconfig.md`). |

## How it works underneath

1. `git status` and `git add` look at each file. If the path matches a pattern in `.gitignore`, Git skips it. The rule only affects files that are not tracked yet; a file already committed stays tracked.
2. Pattern rules: a `/` at the end means "a folder", `*` matches any run of characters, a name without a slash matches at any depth, `#` starts a comment, a blank line is ignored.
3. `.gitattributes` has the form `pattern attribute attribute`. Git reads it when it **adds** a file to the history and when it **checks out** a file into your folder.
4. `text` says "this is text; normalize its line endings". `eol=lf` or `eol=crlf` says which ending to write into the working folder on checkout. Inside the repository, Git always stores text with plain LF.

## Walkthrough of this repository's use

### `.gitignore`, line by line

```text
.gradle/
build/
out/
*.log
.DS_Store
.idea/
*.iml
.env
# The lab's database password is generated into build/secrets on every machine and never committed.
*.password
```

- `.gradle/` is Gradle's project cache folder. The slash at the end means the folder.
- `build/` is where Gradle writes compiled classes, test results, reports and the stack's jars, and where the scripts write the audit log and the database password (`gradle.md`, `jacoco.md`). They can be rebuilt, so they stay out of the history.
- `out/` is the output folder of IntelliJ IDEA and similar editors.
- `*.log` matches any file whose name ends in `.log`.
- `.DS_Store` is a folder-settings file that macOS Finder creates.
- `.idea/` is IntelliJ IDEA's project settings folder.
- `*.iml` matches IntelliJ module files.
- `.env` is the usual name for a file of secrets and local settings. Ignoring it keeps a password from being committed by accident. The repository has none.

- The line that starts with `#` is a comment. It says why the next pattern exists: the lab's database password is generated into `build/secrets` on every machine and never committed.
- `*.password` matches any file whose name ends in `.password`. The file the scripts write is `build/secrets/db.password`.

Two lines of defense stand behind each other. `build/` (line 2) already hides the whole `build/secrets` folder. The `*.password` pattern catches the same kind of file anywhere else in the tree, for example a copy that a reader makes into the project folder by mistake. `git check-ignore -v build/secrets/db.password` says `.gitignore:2:build/` and `git check-ignore -v a.password` says `.gitignore:10:*.password`. There is no pattern for key files such as `*.pem` or `*.p12`, because this repository makes none (the Module 9 project, which made test certificates, ignored them); `gitleaks.md` shows a scanner that would catch one anyway. I also asked `git ls-files` for any tracked file that ends in `.pem`, `.key`, `.crt`, `.p12`, `.jks`, `.password` or `.log`, or is named `.env`: it printed nothing.

### `.gitattributes`, line by line

```text
gradlew text eol=lf
*.bat text eol=crlf
```

- `gradlew text eol=lf`: the file named `gradlew` is text and is checked out with LF endings, on every operating system. A shell script with CRLF endings fails on macOS and Linux (`posix-shell.md`).
- `*.bat text eol=crlf`: every file ending in `.bat` is text and is checked out with CRLF endings, which Windows batch files need (`windows-batch.md`).

On this Mac, `git ls-files --eol` confirms what Git does. The `i/` column is the form stored in the repository, `w/` is the form in my working folder:

```
i/lf    w/lf    attr/                 	build.gradle.kts
i/lf    w/lf    attr/text eol=lf      	gradlew
i/lf    w/crlf  attr/text eol=crlf    	gradlew.bat
```

`gradlew.bat` is stored with LF and written with CRLF; `build.gradle.kts` has no rule and stays LF. (The list is in alphabetical order, so `build.gradle.kts` comes first.)

## Try it yourself

All commands ran in a scratch copy on 11 November 2026.

1. See which pattern ignores a path:

```
git check-ignore -v build/ .gradle/ foo.log .env .idea/ out/ x.iml .DS_Store build/secrets/db.password a.password s.p12
```

   Expected: one line per path, for the paths that match, such as `.gitignore:2:build/	build/`, `.gitignore:2:build/	build/secrets/db.password` and `.gitignore:10:*.password	a.password`. `s.p12` prints nothing: no pattern matches it. (The paths need not exist.)

2. See that a normal path is not ignored:

```
git check-ignore -v src/main/Main.java; echo "exit=$?"
```

   Expected: no output, then `exit=1`.

3. See the ignored folders that exist:

```
git status --short --ignored
```

   Expected after a build: `!! .gradle/` and `!! build/` (plus anything new that is untracked).

4. See the line-ending state of three files:

```
git ls-files --eol gradlew gradlew.bat build.gradle.kts
```

   Expected: the three lines shown above.

5. Look for tracked secrets, and count the tracked files:

```
git ls-files | grep -E '\.(pem|key|crt|p12|jks|password|log)$|\.env$'
git ls-files | wc -l
```

   The first prints nothing. The second printed `103` before the `tech/` folder was added. The list includes `gradle/wrapper/gradle-wrapper.jar`, the wrapper program, which is tracked on purpose.

## Mistakes people make

- **Adding a pattern for a file that is already tracked.** Git keeps tracking it. Remove it from the index first (`git rm --cached file`).
- **Committing `build/`.** It is large and changes on every build. The pattern prevents it.
- **Committing a secret.** A password file or a private key must never enter the history. `build/` and `*.password` keep this repository's one secret out; `gitleaks.md` shows a scanner that checks for leaks of any kind.
- **Committing a `.env` file.** It would publish secrets. The pattern prevents it, and secrets should never be in the repository anyway.
- **Ignoring the wrapper jar** (a `*.jar` pattern would do it). This repository does not, because the wrapper needs the jar (`gradle-wrapper.md`).
- **Editing `gradlew` on Windows and committing CRLF.** The `eol=lf` rule normalizes it.
- **Expecting `.gitattributes` to change files already checked out.** Run `git add --renormalize .` or check the files out again.

## Check yourself

1. *Which file stops Git from tracking `build/`?* `.gitignore`, with the pattern `build/`.
2. *What does the `/` at the end of `.gradle/` mean?* It matches only a folder.
3. *Why does `gradlew` have `eol=lf`?* A shell script with CRLF endings fails on Unix.
4. *Why does `*.bat` have `eol=crlf`?* Windows batch files need Windows line endings.
5. *Which patterns keep the database password out of the history?* `build/` (the scripts write it to `build/secrets/db.password`) and `*.password` (for a copy anywhere else).

## Further reading

- gitignore patterns: <https://git-scm.com/docs/gitignore>
- gitattributes (`text`, `eol`): <https://git-scm.com/docs/gitattributes>
- `git check-ignore` and `git ls-files`: <https://git-scm.com/docs/git-check-ignore>, <https://git-scm.com/docs/git-ls-files>
