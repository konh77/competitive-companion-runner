# Competitive Companion Runner

**Receive an AtCoder problem, write Python, and run the samples—all from PyCharm.**

[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
[![PyCharm: 2026.2+](https://img.shields.io/badge/PyCharm-2026.2%2B-green.svg)](#requirements)

An unofficial PyCharm plugin that connects the
[Competitive Companion](https://github.com/jmerle/competitive-companion) browser extension
to your local Python workflow. Import problems and contests, keep your solutions intact,
and inspect test results without switching to a terminal.

[Download the plugin](https://github.com/konh77/competitive-companion-runner/releases)
· [Report an issue](https://github.com/konh77/competitive-companion-runner/issues)
· [Build from source](#development)

## Features

- **One-click import.** Receive AtCoder problems or contest batches from Competitive Companion.
- **Safe re-imports.** Existing solution files are never overwritten. Updated samples are stored
  in separate revisions; an empty re-import keeps existing samples.
- **Local test runner.** Run samples and custom cases with CPython or PyPy, with timeouts,
  cancellation, bounded output, and up to four parallel cases.
- **Useful feedback.** See verdicts, elapsed time, stdout, stderr, and expected-versus-actual diffs.
- **Custom cases.** Add, edit, delete, or copy a sample into a custom test. Omit expected output
  for manual inspection.
- **Flexible layout.** Customize solution paths, test directories, starter code, comparison
  modes, and time-limit multipliers.
- **Problem and submission pages.** Open the statement inside PyCharm when JCEF is available.
  The submit action copies your solution and attempts to pre-fill the submission form;
  you select the language, review the code, and press Submit yourself.
- **Multiple projects.** Receive into the last-focused project, pin a target, or choose per batch.

## Requirements

| Component | Requirement |
| --- | --- |
| IDE | PyCharm 2026.2 or later, with Python support; minimum platform build `262` |
| Interpreter | A local Python 3 interpreter: CPython or PyPy |
| Browser | Competitive Companion installed in a supported browser |
| Embedded pages | A JCEF-enabled IDE runtime; external-browser actions work as a fallback |

The current build is validated locally against PyCharm 2026.2.3 on macOS. Other operating systems
and newer IDE versions have not yet been verified. A separate JDK is only needed to build from source.

## Install and connect

1. Download `competitive-companion-runner-<version>.zip` from
   [Releases](https://github.com/konh77/competitive-companion-runner/releases), or build it yourself.
   Keep the ZIP compressed.
2. In PyCharm, open **Settings → Plugins → ⚙ → Install Plugin from Disk…**, select the ZIP,
   and restart if prompted.
3. Install [Competitive Companion](https://github.com/jmerle/competitive-companion#installation)
   in your browser.
4. In the extension's options, add **`10046`** under **Custom ports**. This must match the port in
   **Settings → Tools → Competitive Companion**.
5. Open a local Python project and configure its Python interpreter. Alternatively, select
   `CUSTOM` and supply an absolute interpreter path under
   **Settings → Tools → Competitive Companion → Project**.
6. Open an AtCoder problem page and click the extension's green **+** button. The plugin creates
   a solution file and lists the problem in the **Companion** tool window.

## Everyday workflow

1. **Import** a problem from your browser.
2. **Code** in the generated Python file.
3. **Run** with **Run Sample Tests**, the tool window's ▶ button, or `Ctrl+Alt+Shift+R`.
   You can change the shortcut in PyCharm's Keymap settings.
4. **Inspect** a failed case. Select it for input, expected output, actual output, and stderr;
   double-click it or choose **Show Diff** to compare outputs.
5. **Add edge cases** with **Add Custom Test** or **Copy to Custom Test**.
6. **Submit** with **Submit (Copy Solution and Open Submit Page)**. Check the source and language
   on AtCoder before submitting. If pre-filling is unavailable, paste from the clipboard.

Each run uses a snapshot of the saved solution. Later edits mark completed results as stale.
Starting another run cancels the previous one, and older results cannot replace the new run's results.

### Verdicts

| Verdict | Meaning |
| --- | --- |
| `AC` | Output matches the configured local comparator |
| `WA` | Output differs from the expected output |
| `RE` | Python exited with an error |
| `TLE` | The configured time limit was exceeded |
| `OLE` | Standard output exceeded 8 MiB |
| `IE` | Setup, execution, or cleanup could not complete correctly |
| `MANUAL` | Inspect the output yourself; no automatic comparison was performed |
| `SKIP` | The case was skipped, for example for an interactive problem |
| `CANCELLED` | The run was cancelled |

Local `AC` does not guarantee acceptance by AtCoder. This runner does not reproduce custom
checkers, enforce memory limits, or judge interactive problems. Solutions execute with your
local interpreter and permissions; the runner is not a sandbox.

## Files and customization

The default layout groups solutions and tests by contest:

```text
your-project/
├── abc400/
│   ├── abc400_a.py                    # Your solution; never overwritten on import
│   └── tests/abc400_a/
│       ├── problem.json              # Problem metadata
│       ├── samples/<revision>/
│       │   ├── sample_01.in
│       │   └── sample_01.out
│       ├── custom_01.in
│       └── custom_01.out             # Optional expected output
└── .companion/index.json             # Rebuildable cache
```

Add `.companion/` to your solution project's `.gitignore`. **Refresh Problem Index** rebuilds
the index from stored problem manifests.

Configure paths under **Settings → Tools → Competitive Companion → Project**:

| Setting | Default | Alternative |
| --- | --- | --- |
| Solution path | `${contestId}/${taskId}.py` | `${contestNumber}-${taskIndex}.py` |
| Tests directory | `${contestId}/tests/${taskId}` | `.tests/${taskId}` |

For example, the alternative layout creates `400-a.py` for `abc400_a`. Path variables include
`${contestId}`, `${contestNumber}`, `${taskId}`, `${taskIndex}`, `${taskIndexUpper}`, and `${date}`.
Changing templates affects newly imported problems; it does not move existing files.

Starter code can come from the built-in template, a project-relative file, or inline text.
Problem title variables such as `${name}` must appear on Python comment lines.

Comparison modes are **LINES** (ignore trailing spaces/tabs on each line and trailing blank lines),
**TOKENS** (ignore ASCII whitespace differences), and **FLOAT** (token comparison with absolute
and relative numeric tolerances). You can also enable automatic runs when received samples change.

## Network and sessions

The receiver listens on `127.0.0.1:10046` by default. Importing data and running local tests do not
require the plugin to fetch problem pages. **Local Summary** displays locally stored metadata and samples.

**Problem Statement** and **Submit Page** load AtCoder in the embedded browser, so those features
use the network. If a page requires authentication, log in there. Its toolbar also offers
**Import Session Cookie…** and **Clear Session** for the embedded browser's AtCoder session.

The plugin has no AI features and does not change other IDE plugins. Check the rules for your
contest before using development tools. This project is not affiliated with AtCoder, JetBrains,
or the Competitive Companion extension.

## Troubleshooting

| Problem | What to check |
| --- | --- |
| Nothing happens after clicking **+** | Confirm the extension's custom port matches the plugin, a project is open, and the listener is running. |
| Port is already in use | Stop another listener or choose an unused port in both the plugin and extension. |
| Problem arrives in the wrong project | Use **Pin Receive Target** or change the receive-target mode in settings. |
| Interpreter is missing | Configure a local Python SDK or an absolute custom interpreter path. Remote/container interpreters are unsupported. |
| Embedded statement is blank or unavailable | Use **Open Problem in External Browser**; embedded pages require JCEF and may require an AtCoder login. |
| Submit form was not filled | Paste the copied solution manually and check the selected language. |
| Stored problems are missing from the list | Run **Refresh Problem Index**. |
| Output looks right but receives `WA` | Inspect **Show Diff** and check the comparison mode and tolerances. |

Open **Companion → Diagnostics** for listener status, receive/save counters, recent events, and
a `curl` command to test the receiver. When reporting an issue, include the IDE, OS, interpreter,
and a minimal example. Omit session cookies and other credentials from diagnostics you share.

## Development

Build requirements: **JDK 25**, the included Gradle wrapper, and a compatible PyCharm installation
or access to download the configured platform. The default local path is `/Applications/PyCharm.app`;
if it does not exist, Gradle downloads `platformVersion` from `gradle.properties`.

```bash
git clone https://github.com/konh77/competitive-companion-runner.git
cd competitive-companion-runner

./gradlew test
./gradlew buildPlugin verifyPluginProjectConfiguration
./gradlew runIde -PrunProject=/absolute/path/to/a/test-project
```

The installable ZIP is written to `build/distributions/`. On Windows, use `gradlew.bat`.

On macOS, the default Gradle configuration discovers the JDK bundled with PyCharm. Otherwise,
install JDK 25 and set `JAVA_HOME`, or update `org.gradle.java.installations.paths`.
Use `-PplatformLocalPath=/path/to/PyCharm` to select a different local IDE, or remove
`platformLocalPath` from `gradle.properties` to use the configured download.

The test suite includes parsers, validation, path safety, comparison, real Python processes, and
IDE integration tests. Process tests currently discover Python at `/usr/bin/python3`,
`/usr/local/bin/python3`, or `/opt/homebrew/bin/python3`; they skip execution when none is available.
Coverage includes timeout/cancellation escalation, child cleanup, overlapping runs, and snapshot cleanup.

```text
src/main/kotlin/companion/
├── listener/   # Loopback HTTP receiver and bounded queue
├── model/      # Payload validation and AtCoder URL parsing
├── routing/    # Project selection and contest batches
├── storage/    # Manifests, sample revisions, and custom tests
├── run/        # Interpreter resolution, execution, and output comparison
├── template/   # File paths and starter-code templates
└── ui/         # Tool window, diffs, diagnostics, and optional embedded browser
```

Bug reports and pull requests are welcome. Include a reproduction for bug fixes and run the test
suite before submitting code. [SPEC.md](SPEC.md) contains the original Japanese design draft;
some planned behavior differs from the current implementation.

## License

[MIT](LICENSE), copyright © 2026 konh. Third-party components retain their own licenses.
AtCoder problem statements and sample data are not covered by this project's license.

Thanks to [Competitive Companion](https://github.com/jmerle/competitive-companion) for the
browser extension and problem-data format that make this workflow possible.
