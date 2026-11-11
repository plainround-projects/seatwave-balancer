# Gradle Wrapper 9.9.1

## What it is

Every project needs one exact version of its build tool, or two people get two different results. Asking each person to install the right Gradle by hand fails sooner or later. The **Gradle Wrapper** solves this. It is a tiny program that lives inside the project, reads which Gradle version the project wants, downloads that version once, checks that the download is genuine, and then starts it. You never install Gradle yourself; you only need Java.

The wrapper is made of four files: `gradlew` (a script for macOS and Linux), `gradlew.bat` (the same for Windows), `gradle/wrapper/gradle-wrapper.jar` (the program) and `gradle/wrapper/gradle-wrapper.properties` (its settings). A **jar** is a Java archive: a zip file that holds compiled Java classes. A **properties** file is a plain text file of `key=value` lines.

## Why this repository uses it

The README lists a JDK 25 as the one tool you must install for the build (Docker is needed only for the stack): the wrapper downloads Gradle 9.9.1 on the first build. Every learner and every machine therefore builds with the same Gradle, and so do the scripts `scripts/stack.sh` and `scripts/lab.sh`, which call `./gradlew` to collect the jars, run the stack tests and run the deploy tool. The checksum in the settings means a tampered download is refused.

## Where it appears

| Path | What it does |
| --- | --- |
| `gradlew` | POSIX shell script. Finds Java and runs the wrapper jar (`posix-shell.md`). |
| `gradlew.bat` | The Windows batch version of the same job (`windows-batch.md`). |
| `gradle/wrapper/gradle-wrapper.jar` | The wrapper program: 49,154 bytes, 35 entries, main class `org.gradle.wrapper.GradleWrapperMain`. |
| `gradle/wrapper/gradle-wrapper.properties` | Which Gradle to download, from where, how to verify it, where to keep it. |
| `.gitattributes` | Forces the line endings the two scripts need (`git.md`). |
| `scripts/stack.sh`, `scripts/lab.sh` | Call `./gradlew -q stackFiles`, `./gradlew stackTest` and `./gradlew -q deploy` (`shell-scripts-walkthrough.md`). |

## How it works underneath

1. You type `./gradlew check`. Your shell starts the script `gradlew`.
2. The script finds a Java program (`JAVA_HOME` or `java` on the PATH) and runs `java -jar gradle/wrapper/gradle-wrapper.jar check`. The word `check` rides along as an argument.
3. The jar's main class reads `gradle-wrapper.properties`.
4. It works out the folder where this Gradle version belongs: `distributionBase` plus `distributionPath` plus a short name built from a hash of the download address. On this machine that is `~/.gradle/wrapper/dists/gradle-9.9.1-bin/dol8b552jwt4yhd7ihm3iahwb/`.
5. If that folder already holds a finished install (a marker file ending in `.ok`), the wrapper skips to step 8.
6. Otherwise it downloads `gradle-9.9.1-bin.zip` from the address in `distributionUrl`, waiting at most `networkTimeout` milliseconds for the network.
7. It computes the SHA-256 checksum of the zip and compares it with `distributionSha256Sum`. If they differ it stops with the message "Verification of Gradle distribution failed" and "Your Gradle distribution may have been tampered with". If they match it unpacks the zip into the folder from step 4 and writes the `.ok` marker.
8. It loads the launcher classes from the unpacked Gradle's `lib` folder and calls their main method with your arguments. From here Gradle itself is running. On this machine the finished folder holds `gradle-9.9.1`, `gradle-9.9.1-bin.zip.lck` (a lock so two builds do not unpack at once) and `gradle-9.9.1-bin.zip.ok`; the zip itself is not kept.

A **checksum** (also called a hash) is a fingerprint of a file: a fixed-length string computed from every byte. Change one byte and the fingerprint changes completely. **SHA-256** is the algorithm; its fingerprint is 64 hexadecimal characters.

## Walkthrough of this repository's use

### `gradle-wrapper.properties`, key by key

```properties
distributionBase=GRADLE_USER_HOME
distributionPath=wrapper/dists
distributionSha256Sum=d8a18a7a841dd5df825d35a685fad6f2d7f12ac0c280e57b67c27b4a520ba228
distributionUrl=https\://services.gradle.org/distributions/gradle-9.9.1-bin.zip
networkTimeout=10000
retries=0
retryBackOffMs=500
validateDistributionUrl=true
zipStoreBase=GRADLE_USER_HOME
zipStorePath=wrapper/dists
```

Properties files treat `#` and `!` as comment starts, and `:` and `=` as the divider between key and value. That is why the colon inside the URL is written `https\://`: the backslash says "this colon is part of the value".

- `distributionBase=GRADLE_USER_HOME` says what the next path is relative to. `GRADLE_USER_HOME` is the Gradle settings folder in your home directory, `~/.gradle` unless you changed it. The other allowed value is `PROJECT`, which would put it inside the repository.
- `distributionPath=wrapper/dists` is the folder, under that base, where unpacked Gradle versions are kept. The path on this machine is `~/.gradle/wrapper/dists`.
- `distributionSha256Sum=d8a18a7a...ba228` is the expected SHA-256 of the zip, 64 hexadecimal characters. This is the **pin**: a security measure. Without it the wrapper would trust whatever the server returned. With it, a changed, corrupted or swapped zip is refused. Gradle publishes the official checksums, so this value can be compared with theirs.
- `distributionUrl=https\://services.gradle.org/distributions/gradle-9.9.1-bin.zip` is where to download from. The version is in the file name. `-bin` means binaries only; the other kind, `-all`, also carries sources and documentation and is much larger. The address uses `https`, which encrypts the connection and checks the server's identity.
- `networkTimeout=10000` is the longest the wrapper waits for the network, in milliseconds (10 seconds), both to connect and between data packets.
- `retries=0` is how many times to try again when a download fails. 0 means one attempt only.
- `retryBackOffMs=500` is the first pause before a retry, in milliseconds. With `retries=0` it never applies, but it is stored with the other settings.
- `validateDistributionUrl=true` records that the address is checked when someone runs `./gradlew wrapper` to generate or update the wrapper files: Gradle confirms that the file really exists at the address. A `false` here would be remembered by later runs of that command.
- `zipStoreBase=GRADLE_USER_HOME` and `zipStorePath=wrapper/dists` say where the downloaded zip is kept while it is being fetched. They mirror the distribution settings, so both land in the same place.

### The jar

`gradle-wrapper.jar` is a zip file. `unzip -l` lists 35 entries: `org/gradle/wrapper/...` classes (`GradleWrapperMain`, `WrapperExecutor` which reads the properties, `Download`, `Install` which checks the checksum and unpacks), `org/gradle/cli/...` classes that parse the command line, and a `META-INF/MANIFEST.MF` whose line `Main-Class: org.gradle.wrapper.GradleWrapperMain` is what makes `java -jar` know where to start. It is a **binary** file, so it appears in the repository but cannot be read as text. Its SHA-256 on this machine is `621008afc4f5d59357fcf06ffa644dd5aade5e091bdc363742710445f73cdaf8`; Gradle publishes the official wrapper jar checksums at its release-checksums page, which is how you would confirm it has not been replaced. I did not compare the two.

### How the first run behaves

On a machine that has never run this project, the first `./gradlew` prints a `Downloading https://services.gradle.org/distributions/gradle-9.9.1-bin.zip` line with a row of dots, then the build starts. On this machine the folder `~/.gradle/wrapper/dists/gradle-9.9.1-bin/` already existed, so I did not see the download. The second and later runs start in well under a second because step 5 above finds the `.ok` marker.

## Try it yourself

1. Print the Gradle version the wrapper starts:

```
./gradlew --version
```

   Expected output starts with `Gradle 9.9.1`, then `Build time:`, `Revision:`, `Kotlin: 2.4.21`, `Groovy: 4.0.33` and the Java that launched it (`Launcher JVM: 25.0.2 (Eclipse Adoptium 25.0.2+10-LTS)` here).

2. Look at where the unpacked copy lives:

```
ls ~/.gradle/wrapper/dists/gradle-9.9.1-bin/*/
```

   Expected: `gradle-9.9.1`, `gradle-9.9.1-bin.zip.lck`, `gradle-9.9.1-bin.zip.ok`.

3. List the jar's contents and manifest:

```
unzip -l gradle/wrapper/gradle-wrapper.jar | tail -3
unzip -p gradle/wrapper/gradle-wrapper.jar META-INF/MANIFEST.MF
```

   The first prints `35 files`. The second contains `Main-Class: org.gradle.wrapper.GradleWrapperMain`.

4. Compute the jar's fingerprint:

```
shasum -a 256 gradle/wrapper/gradle-wrapper.jar
```

   Expected: `621008afc4f5d59357fcf06ffa644dd5aade5e091bdc363742710445f73cdaf8  gradle/wrapper/gradle-wrapper.jar`.

5. Read the settings with line numbers:

```
cat -n gradle/wrapper/gradle-wrapper.properties
```

   You see the ten lines quoted above.

## Mistakes people make

- **Installing Gradle and running `gradle`.** The version may differ. Use `./gradlew`.
- **Forgetting to commit the jar.** Without `gradle-wrapper.jar` the script cannot start. Git here treats it as a normal tracked file.
- **Editing the version in the URL but not the checksum.** The download would then fail the SHA-256 check. Both lines must change together; `./gradlew wrapper --gradle-version <x>` is the safe way.
- **Wrong line endings.** A `gradlew` saved with Windows line endings fails on macOS and Linux (`posix-shell.md`). `.gitattributes` prevents that (`git.md`).
- **Forgetting `chmod +x`.** The file needs the executable permission. It has it here: `-rwxr-xr-x`.
- **Running behind a proxy with a short timeout.** Raise `networkTimeout` or `retries`.

## Check yourself

1. *Why does a project carry a wrapper?* So every machine builds with the exact same Gradle, downloaded on demand.
2. *What does `distributionSha256Sum` protect against?* A corrupted or tampered download. The wrapper compares the zip's fingerprint with the pinned one and refuses a mismatch.
3. *What is inside `gradle-wrapper.jar` that makes `java -jar` work?* A manifest with `Main-Class: org.gradle.wrapper.GradleWrapperMain`.
4. *Why is the URL written `https\://`?* In a properties file `:` divides key from value, so the colon in the value is escaped with a backslash.
5. *Where does the downloaded Gradle go?* Under `~/.gradle/wrapper/dists`, into a folder named after the version and a hash of the URL; the next run reuses it.

## Further reading

- Gradle Wrapper guide for 9.9.1: <https://docs.gradle.org/9.9.1/userguide/gradle_wrapper.html>
- Wrapper checksum verification and the release checksums: <https://gradle.org/release-checksums/>
- Upgrading the wrapper: <https://docs.gradle.org/9.9.1/userguide/gradle_wrapper.html#sec:upgrading_wrapper>
