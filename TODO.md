# TODO

## IntelliJ IDEA CLI Formatter Learnings

Context: verified on Windows with IntelliJ IDEA 2026.1.3 installed through
JetBrains Toolbox.

- IntelliJ ships a formatter CLI at:

  ```powershell
  C:\Users\okr\AppData\Local\Programs\IntelliJ IDEA\bin\format.bat
  ```

- Basic project-code-style invocation:

  ```powershell
  & 'C:\Users\okr\AppData\Local\Programs\IntelliJ IDEA\bin\format.bat' `
    -s .idea\codeStyles\Project.xml `
    path\to\File.java
  ```

- JetBrains documents that the command-line formatter starts an IntelliJ IDEA
  backend and does not work when another IDEA instance is already running:
  <https://www.jetbrains.com/help/idea/command-line-formatter.html>

- A practical workaround is to run the formatter with a temporary
  `idea.properties` file that points IDEA's config, system, and plugin paths at
  isolated temporary directories. JetBrains documents these path properties here:
  <https://www.jetbrains.com/help/idea/directories-used-by-the-ide-to-store-settings-caches-plugins-and-logs.html>

  ```powershell
  $base = Join-Path $env:TEMP 'ij-format-codex'
  $config = Join-Path $base 'config'
  $system = Join-Path $base 'system'
  $plugins = Join-Path $base 'plugins'
  New-Item -ItemType Directory -Force -Path $config, $system, $plugins | Out-Null

  $properties = Join-Path $base 'idea.properties'
  @"
  idea.config.path=$($config -replace '\\','/')
  idea.system.path=$($system -replace '\\','/')
  idea.plugins.path=$($plugins -replace '\\','/')
  "@ | Set-Content -Encoding UTF8 -Path $properties

  $env:IDEA_PROPERTIES = $properties
  & 'C:\Users\okr\AppData\Local\Programs\IntelliJ IDEA\bin\format.bat' `
    -s .idea\codeStyles\Project.xml `
    path\to\File.java
  ```

- Beware review noise: running the formatter on a large legacy file can rewrite
  thousands of unrelated lines, even when only one small block changed. For PRs,
  inspect `git diff --stat` immediately after formatting and revert formatter
  churn unless the PR intentionally reformats the file.

- `idea.cmd` from Toolbox is a launcher wrapper. Prefer the direct
  `bin\format.bat` script for formatting.
