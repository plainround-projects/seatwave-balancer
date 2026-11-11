# EditorConfig

## What it is

Different editors default to different rules: tabs or spaces, Windows or Unix line endings, trimming spaces at the end of a line or not. **EditorConfig** is a small file format, named `.editorconfig`, that a project places at its root so that every editor applies the same basic rules. Most editors read it by themselves or through a plug-in. It is a **hint to editors**, not a checker: nothing fails if a file breaks a rule.

## Why this repository uses it

So that every contributor's editor writes files the same way: UTF-8 text, Unix line endings, spaces, a final newline. That keeps Git diffs free of noise and prevents a Windows editor from adding carriage returns to `gradlew`.

## Where it appears

| Path | What it does |
| --- | --- |
| `.editorconfig` | The rules, 17 lines (13 of them are not blank, 4 are blank). |
| `.gitattributes` | Enforces line endings at the Git level, where EditorConfig cannot (`git.md`). |
| `build.gradle.kts` and other files | The files the rules apply to. |

## How it works underneath

1. When you open or save a file, the editor looks for `.editorconfig` in the file's folder, then in each parent folder, until it finds one that says `root = true`.
2. It reads the sections, which are headed by a **glob** (a pattern for file names) in square brackets. `*` matches any file name, `*.md` matches names that end in `.md`, `{a,b}` matches either `a` or `b`.
3. Every section whose pattern matches the file applies, and later sections override earlier ones.
4. Each line is `property = value`. The editor applies the properties it understands.

## Walkthrough of this repository's use

```text
root = true

[*]
charset = utf-8
end_of_line = lf
insert_final_newline = true
trim_trailing_whitespace = true
indent_style = space

[*.{java,toml,yaml,yml}]
indent_size = 2

[*.kts]
indent_size = 4

[*.md]
trim_trailing_whitespace = false
```

- `root = true` stops the editor from looking in parent folders for more `.editorconfig` files. It sits before any section.
- `[*]` starts the section for every file.
- `charset = utf-8` saves files as UTF-8, the encoding that can represent every character and that the compiler is told to expect (`options.encoding = "UTF-8"`, `gradle.md`).
- `end_of_line = lf` ends lines with a line feed, the Unix convention (`\n`). The Windows script `gradlew.bat` is the exception, and `.gitattributes` covers it (`git.md`).
- `insert_final_newline = true` makes the last line of a file end with a line break.
- `trim_trailing_whitespace = true` deletes spaces at the end of lines when saving.
- `indent_style = space` indents with spaces, never tab characters.
- `[*.{java,toml,yaml,yml}]` starts a section for files with those four extensions.
- `indent_size = 2` makes one indentation level two spaces. For Java this matches the google-java-format style (`spotless-google-java-format.md`).
- `[*.kts]` starts a section for the two Gradle build files, which are written in Kotlin.
- `indent_size = 4` makes one indentation level four spaces there, the usual Kotlin style and what both build files use.
- `[*.md]` starts a section for Markdown files.
- `trim_trailing_whitespace = false` overrides the `[*]` rule for Markdown. In Markdown, two spaces at the end of a line can mean "hard line break", so trimming would change the meaning.

### What I found when comparing the rules with the files

- `build.gradle.kts` and `settings.gradle.kts` are indented with four spaces, which matches the `[*.kts]` section. `grep -c "^    " build.gradle.kts` prints 86, and `grep -c "^  [^ ]" build.gradle.kts` (lines that start with exactly two spaces) prints 0. EditorConfig does not fail the build, and Spotless does not format Kotlin files here, so this rule is kept by editors and by care, not by a check.
- Trailing spaces: `build.gradle.kts` and `compose.yaml` each give 0, and `build.gradle.kts` ends with a newline.

## Try it yourself

1. Show the rules with line numbers:

```
cat -n .editorconfig
```

   Expected: the 17 lines quoted above (four are blank).

2. Find trailing spaces in two files:

```
grep -c ' $' build.gradle.kts compose.yaml
```

   Expected: `build.gradle.kts:0` and `compose.yaml:0`.

3. Check that a file ends with a newline:

```
tail -c1 build.gradle.kts | od -c | head -1
```

   Expected: `0000000   \n`.

4. Count four-space indented lines in the Kotlin files:

```
grep -c "^    " build.gradle.kts settings.gradle.kts
```

   Expected: `build.gradle.kts:86` and `settings.gradle.kts:4` (grep may print the two files in either order). Both files use four spaces, as the `[*.kts]` section says.

5. Count the lines that are indented with exactly two spaces in the Kotlin files:

```
grep -c "^  [^ ]" build.gradle.kts settings.gradle.kts
```

   Expected: `build.gradle.kts:0` and `settings.gradle.kts:0` (again in either order).

## Mistakes people make

- **Thinking EditorConfig enforces anything.** It only guides editors. Spotless and Git attributes enforce.
- **Missing the `root = true` line.** The editor may pick up rules from a parent folder.
- **Trimming whitespace in Markdown.** That is why `[*.md]` turns it off.
- **Expecting an old editor to read the file.** Some editors need a plug-in.
- **Rules that disagree with the formatter.** Here `indent_size = 2` for Java matches google-java-format on purpose, and the `.kts` files get their own four-space section so that the rule and the files agree.

## Check yourself

1. *What does `root = true` mean?* Stop searching parent folders for more `.editorconfig` files.
2. *Why does `[*.md]` turn off `trim_trailing_whitespace`?* Two trailing spaces mean a line break in Markdown.
3. *Which files get a two-space indent, and which get four?* `.java`, `.toml`, `.yaml` and `.yml` get two. The two `.kts` build files get four.
4. *Does EditorConfig fail the build?* No. It is a hint to editors.
5. *Which file sets line endings for `gradlew.bat`?* `.gitattributes`, with `*.bat text eol=crlf`.

## Further reading

- EditorConfig specification and property list: <https://spec.editorconfig.org/>
- Project site: <https://editorconfig.org/>
