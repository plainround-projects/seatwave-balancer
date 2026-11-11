# Windows batch: the `gradlew.bat` script

## What it is

A **batch file** is the Windows counterpart of a shell script: a text file of commands, run top to bottom by `cmd.exe`, the classic Windows command interpreter. Its name ends in `.bat`. Where a POSIX script uses `$name` for a variable, a batch file uses `%name%`. Where a POSIX script has `case` and `if ... fi`, a batch file jumps between **labels** with `goto`. A label is a line that starts with a colon, such as `:execute`, and `goto execute` continues from that line.

An **exit code**, called the **error level** in batch files, is the number a program returns when it ends. 0 means success. The variable `%ERRORLEVEL%` holds the last one.

## Why this repository uses it

A learner on Windows types `gradlew.bat check`, or just `gradlew check` in `cmd.exe`, and the same wrapper starts as on macOS. The file is part of the standard wrapper that Gradle generates. It was not run on the verification machine (a Mac), so everything below comes from reading the file, not from running it.

## Where it appears

| Path | What it does |
| --- | --- |
| `gradlew.bat` | The script: 112 lines. Finds `java.exe` and runs the wrapper jar (`gradle-wrapper.md`). |
| `.gitattributes` | `*.bat text eol=crlf` makes every checkout write Windows line endings (`git.md`). |

## How it works underneath

1. `cmd.exe` reads the file by line, runs each command and moves on, unless a `goto` sends it to a label.
2. `%name%` is replaced by the variable's value **before** the line runs. `%1` to `%9` are the arguments, `%*` is all of them, `%0` is the script's own path. The form `%~dp0` takes `%0` and keeps only the drive and folder (`d` drive, `p` path); `%~n0` keeps only the file name without its extension.
3. `set NAME=value` sets a variable (no quotes needed, and quotes you type become part of the value). `if` tests a condition. `if defined NAME` tests that a variable exists.
4. `@` in front of a line stops that line from being printed. `@echo off` turns printing of commands off for the rest of the file. `@rem` or `::` starts a comment.
5. `>NUL` throws output away. `2>&1` sends error output where normal output goes. `1>&2` sends normal output to the error stream.
6. `&` puts two commands on one line.
7. Windows needs Windows line endings: each line ends in two characters, carriage return and line feed (`\r\n`, "CRLF"). A batch file with only `\n` endings can misbehave on labels.

## Walkthrough of this repository's use

### The licence comments (lines 1 to 17)

```bat
@rem
@rem Copyright 2015 the original author or authors.
@rem
@rem Licensed under the Apache License, Version 2.0 (the "License");
@rem you may not use this file except in compliance with the License.
@rem You may obtain a copy of the License at
@rem
@rem      https://www.apache.org/licenses/LICENSE-2.0
@rem
@rem Unless required by applicable law or agreed to in writing, software
@rem distributed under the License is distributed on an "AS IS" BASIS,
@rem WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
@rem See the License for the specific language governing permissions and
@rem limitations under the License.
@rem
@rem SPDX-License-Identifier: Apache-2.0
@rem
```

Every line starts with `@rem`: a comment that is not printed. It is the licence notice. Nothing runs here.

### Quiet mode (lines 19 to 24)

```bat
@if "%DEBUG%"=="" @echo off
@rem ##########################################################################
@rem
@rem  gradlew startup script for Windows
@rem
@rem ##########################################################################
```

- `@if "%DEBUG%"=="" @echo off` means: if the environment variable `DEBUG` is empty, turn command printing off. Setting `DEBUG` to anything shows every command, which helps when the script itself misbehaves. The quotes around `%DEBUG%` keep the test valid when the variable is empty.
- The remaining lines are comments with a banner.

### Local variables (lines 26 and 27)

```bat
@rem Set local scope for the variables, and ensure extensions are enabled
setlocal EnableExtensions
```

`setlocal EnableExtensions` starts a **local scope**: any variable set from here on disappears when the script ends (or at `endlocal`), so the script does not pollute your terminal. `EnableExtensions` switches on the modern behaviors of `cmd.exe` that later lines need, such as `equ` in `if` and `%~dp0`.

### A safety net for old scripts (lines 29 to 54)

```bat
@rem Catch executions from older scripts and ensure they exit cleanly.
@rem This can be removed once we can be reasonably confident that few people
@rem will be migrating directly to this new wrapper.
goto afterSafetyNet
```

```bat
::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::::
```

```bat
goto exitWithErrorLevel
:afterSafetyNet
```

- Lines 29 to 31 say what this is for: older generations of the wrapper script could be running at the moment the file is replaced by this newer one, and they must still exit cleanly.
- `goto afterSafetyNet` jumps over the next block. Lines 33 to 52 are 20 identical lines made only of colons. A line beginning with `::` is a comment, so they do nothing. They are padding: `cmd.exe` reads a batch file by position, so an old script that resumes at a remembered position in a replaced file lands somewhere harmless. I did not test this on Windows; it is the purpose the file's own comments and layout point to.
- `goto exitWithErrorLevel` (line 53) is where a stray old script ends up: it leaves with the current error level.
- `:afterSafetyNet` (line 54) is the label the first `goto` jumps to. From here the real work begins.

### Where the script lives (lines 56 to 63)

```bat
set DIRNAME=%~dp0
if "%DIRNAME%"=="" set DIRNAME=.
@rem This is normally unused
set APP_BASE_NAME=%~n0
set APP_HOME=%DIRNAME%

@rem Resolve any "." and ".." in APP_HOME to make it shorter.
for %%i in ("%APP_HOME%") do set APP_HOME=%%~fi
```

- `set DIRNAME=%~dp0` stores the folder the script is in, with a trailing backslash, for example `C:\work\seatwave-balancer\`.
- `if "%DIRNAME%"=="" set DIRNAME=.` uses the current folder if that is somehow empty.
- `set APP_BASE_NAME=%~n0` stores the script name without extension (`gradlew`).
- `set APP_HOME=%DIRNAME%` is the project folder.
- `for %%i in ("%APP_HOME%") do set APP_HOME=%%~fi` is a loop that runs once. In a batch file a loop variable is written `%%i`. The `~f` modifier turns the value into a fully qualified path, which removes any `.` or `..` parts and so makes the path shorter and cleaner.

### Default Java options (lines 65 and 66)

```bat
@rem Add default JVM options here. You can also use JAVA_OPTS and GRADLE_OPTS to pass JVM options to this script.
set DEFAULT_JVM_OPTS="-Xmx64m" "-Xms64m"
```

`DEFAULT_JVM_OPTS` holds two options for the wrapper's Java process: `-Xmx64m` caps its memory at 64 megabytes and `-Xms64m` starts it at 64 megabytes. The quotes stay in the value because they are needed later when the line is expanded. The POSIX script sets the same two options (`posix-shell.md`).

### Finding `java.exe` when `JAVA_HOME` is not set (lines 68 to 82)

```bat
@rem Find java.exe
if defined JAVA_HOME goto findJavaFromJavaHome

set JAVA_EXE=java.exe
%JAVA_EXE% -version >NUL 2>&1
if %ERRORLEVEL% equ 0 goto execute

1>&2 echo.
1>&2 echo ERROR: JAVA_HOME is not set and no 'java' command could be found in your PATH.
1>&2 echo.
1>&2 echo Please set the JAVA_HOME variable in your environment to match the
1>&2 echo location of your Java installation.

"%COMSPEC%" /c exit 1
goto exitWithErrorLevel
```

- `if defined JAVA_HOME goto findJavaFromJavaHome` jumps to another label if the variable `JAVA_HOME` exists.
- Otherwise `set JAVA_EXE=java.exe` means "use the `java.exe` that the PATH variable finds". `%JAVA_EXE% -version >NUL 2>&1` runs it with `-version`, hiding all output. If Java is not installed the command fails and `%ERRORLEVEL%` is not 0.
- `if %ERRORLEVEL% equ 0 goto execute` jumps to `:execute` when Java ran fine. `equ` means "equal to" as a number.
- `1>&2 echo.` prints an empty line to the error stream (`echo.` prints a blank line). The next lines print the message "JAVA_HOME is not set and no 'java' command could be found in your PATH." and ask the user to set `JAVA_HOME`. All go to the error stream, like `die` in the POSIX script.
- `"%COMSPEC%" /c exit 1` starts a fresh `cmd.exe` (`COMSPEC` is the variable that holds its path) that exits at once with code 1. This is the trick that puts 1 into `%ERRORLEVEL%` without closing your window.
- `goto exitWithErrorLevel` leaves the script.

### Finding `java.exe` through `JAVA_HOME` (lines 84 to 97)

```bat
:findJavaFromJavaHome
set JAVA_HOME=%JAVA_HOME:"=%
set JAVA_EXE=%JAVA_HOME%/bin/java.exe

if exist "%JAVA_EXE%" goto execute

1>&2 echo.
1>&2 echo ERROR: JAVA_HOME is set to an invalid directory: %JAVA_HOME%
1>&2 echo.
1>&2 echo Please set the JAVA_HOME variable in your environment to match the
1>&2 echo location of your Java installation.

"%COMSPEC%" /c exit 1
goto exitWithErrorLevel
```

- `:findJavaFromJavaHome` is the label for the previous `goto`.
- `set JAVA_HOME=%JAVA_HOME:"=%` is variable substitution: inside `%...%`, `:"=` means "replace every double quote with nothing". People sometimes put quotes around the path when they set the variable; this removes them.
- `set JAVA_EXE=%JAVA_HOME%/bin/java.exe` builds the path to the program. Windows accepts the forward slash.
- `if exist "%JAVA_EXE%" goto execute` jumps on if the file is there.
- If it is not, the script prints "ERROR: JAVA_HOME is set to an invalid directory: ..." plus the same two help lines, sets error level 1 and leaves.

### The real work (lines 99 to 107)

```bat
:execute
@rem Setup the command line



@rem Execute gradlew
@rem endlocal doesn't take effect until after the line is parsed and variables are expanded
@rem which allows us to clear the local environment before executing the java command
endlocal & "%JAVA_EXE%" %DEFAULT_JVM_OPTS% %JAVA_OPTS% %GRADLE_OPTS% "-Dorg.gradle.appname=%APP_BASE_NAME%" -jar "%APP_HOME%\gradle\wrapper\gradle-wrapper.jar" %* & call :exitWithErrorLevel & goto exitWithErrorLevel
```

- `:execute` is the label both paths above jump to. The empty lines and the first comment are leftovers of the template.
- Line 107 is one long line made of several commands joined by `&`. First `endlocal` ends the local scope, discarding all variables. That is safe because `cmd.exe` has already replaced every `%NAME%` on the line with its value before running any part of it (the comments on lines 105 and 106 say this).
- `"%JAVA_EXE%" %DEFAULT_JVM_OPTS% %JAVA_OPTS% %GRADLE_OPTS% "-Dorg.gradle.appname=%APP_BASE_NAME%" -jar "%APP_HOME%\gradle\wrapper\gradle-wrapper.jar" %*` is the actual Java command: the program, the default memory options, any extra options you set in the environment, the program-name property, `-jar` and the wrapper jar, and `%*`, every argument you typed (`check`, plus any other arguments).
- `& call :exitWithErrorLevel & goto exitWithErrorLevel` runs after Java has returned: it calls the label at the end of the file, then jumps to it. The result is that the script's own error level equals Java's.

### The exit point (lines 109 to 112)

```bat
@rem This label must not be changed. We rely on old scripts being able to jump to this point.
:exitWithErrorLevel
@rem Use "%COMSPEC%" /c exit to allow operators to work properly in scripts
"%COMSPEC%" /c exit %ERRORLEVEL%
```

`:exitWithErrorLevel` is the label the whole script ends at. The comment says it must never be renamed, because older scripts jump to it by name. `"%COMSPEC%" /c exit %ERRORLEVEL%` starts a `cmd.exe` that exits with the current error level, so the exit code reaches the caller correctly even when `gradlew.bat` is used inside other scripts and with `&&` and `||`.

### Line endings

`.gitattributes` says `*.bat text eol=crlf`. Git stores the file with plain `\n` line endings in the repository, and writes `\r\n` into your working folder when you check it out. On this Mac `git ls-files --eol gradlew.bat` reports `i/lf w/crlf attr/text eol=crlf`, meaning: index LF, working tree CRLF.

## Try it yourself

None of these commands runs the script, since this machine is a Mac. They look at the file.

1. Confirm it has Windows line endings:

```
file gradlew.bat
```

   Expected: `gradlew.bat: DOS batch file text, ASCII text, with CRLF line terminators`.

2. Look at the first bytes:

```
head -c 24 gradlew.bat | od -c | head -3
```

   Expected: `@ r e m \r \n @ r e m   C o p y r ...`. The `\r \n` pair is the Windows line ending.

3. Count the padding lines:

```
grep -c '^::::' gradlew.bat
```

   Expected: `20`.

4. Count the lines without the carriage returns:

```
tr -d '\r' < gradlew.bat | wc -l
```

   Expected: `112`.

5. See how Git treats the file:

```
git ls-files --eol gradlew.bat
```

   Expected: `i/lf    w/crlf  attr/text eol=crlf    	gradlew.bat`.

## Mistakes people make

- **Saving a `.bat` file with Unix line endings.** Labels and `goto` can then fail in odd ways. That is why `.gitattributes` forces CRLF.
- **Using `%VAR%` inside a `for` loop and expecting the new value.** The whole line is expanded first. The script uses that rule on purpose in line 107.
- **Setting a variable with spaces around `=`.** `set A = 1` creates a variable named `A ` (with a space). Write `set A=1`.
- **Quoting paths twice.** Hence `set JAVA_HOME=%JAVA_HOME:"=%`.
- **Using `exit 1` inside a script.** It closes the whole window. The script runs `"%COMSPEC%" /c exit 1` to set the error level only.
- **Forgetting that `%ERRORLEVEL%` changes after every command.** Read it right after the command you care about.

## Check yourself

1. *What does `%~dp0` give?* The drive and folder of the running script, with a trailing backslash.
2. *How does `goto` work?* It continues execution at the line that starts with the named label, such as `:execute`.
3. *Why does the script run `"%COMSPEC%" /c exit 1`?* To set the error level to 1 without closing the user's window.
4. *Why is the Java command on line 107 preceded by `endlocal &`?* To drop the local variables while the command still sees their values, because `cmd.exe` already expanded the line.
5. *Why does the repository force CRLF for this file?* Windows batch files need `\r\n` endings to behave reliably, and `.gitattributes` makes every checkout produce them.

## Further reading

- `cmd.exe` and batch syntax: <https://learn.microsoft.com/en-us/windows-server/administration/windows-commands/cmd>
- Command-line reference for `set`, `if`, `for`, `goto`, `setlocal`: <https://learn.microsoft.com/en-us/windows-server/administration/windows-commands/windows-commands>
- Gradle Wrapper guide for 9.9.1: <https://docs.gradle.org/9.9.1/userguide/gradle_wrapper.html>
