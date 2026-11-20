# Third-Party Notices

LoopForge4J is an independent, community-maintained Java port. It is **not
affiliated with, sponsored by, or endorsed by Tencent**. "LoopForge" is used
only to identify the upstream project this port is derived from (nominative
use); no upstream logos, artwork or branding are included.

## LoopForge (upstream)

- Project: <https://github.com/Tencent/LoopForge>
- Reviewed upstream revision: `65f2077` (2026-11, "fix(runner): install the Linux Docker helper on macOS (#1108)")
- License: MIT
- What was derived: the SOP engine semantics (graph scheduling, node
  lifecycle, gate decisions, rework/retry/approval controls, artifact
  contracts), the executor command/stream-protocol conventions, domain model
  field names and JSON shape, task-book prompt templates, and the public
  "platform check · script & approval" demo SOP. The Java code is a rewrite;
  no Go source files are redistributed.
- What was **not** taken: the upstream web UI, the vendored `superpowers`
  skills, Monaco Editor and Code-OSS (`web/monaco`, `web/vscode-web`), Harness
  UI / Grasp design assets, provider logos, executor CLI packages, and any
  deployment-specific data. Those components keep their own licenses and are
  not part of this distribution.

The upstream copyright and permission notice, reproduced verbatim as required
by its license:

```text
Tencent is pleased to support the open source community by making LoopForge available.

Copyright (C) 2026 Tencent. All rights reserved.

LoopForge is licensed under MIT, except for the third-party components listed below, which remain licensed under their respective original terms. LoopForge does not impose any additional restrictions beyond those specified in the original licenses of these third-party components. Users are required to comply with all applicable terms and conditions of the original licenses.

Terms of the MIT License:
--------------------------------------------------------------------
Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation files (the "Software"), to deal in the Software without restriction, including without limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice (including the next paragraph) shall be included in all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
```

## Runtime dependencies

The build pulls Spring Boot, Spring Framework, Jackson, Flyway, H2, the
PostgreSQL JDBC driver, springdoc-openapi and their transitive dependencies
from Maven Central. All are distributed under their own licenses (mostly
Apache-2.0; H2 is MPL-2.0/EPL-1.0; the PostgreSQL driver is BSD-2-Clause).
Binary distributions (the executable jar and the container image) keep each
dependency's original jar, including its embedded license files. List the
exact resolved set with:

```bash
./mvnw -q dependency:list -pl loopforge-server -DincludeScope=runtime
```

## xterm.js (bundled in the web console)

- Project: <https://github.com/xtermjs/xterm.js>, package `@xterm/xterm` 5.5.0
- License: MIT (the full notice ships next to the files in
  `loopforge-server/src/main/resources/static/console/vendor/xterm/LICENSE`)
- What is included: the unmodified `lib/xterm.js` and `css/xterm.css`, loaded by
  the console's workspace terminal.

```text
Copyright (c) 2017-2019, The xterm.js authors (https://github.com/xtermjs/xterm.js)
Copyright (c) 2014-2016, SourceLair Private Company (https://www.sourcelair.com)
Copyright (c) 2012-2013, Christopher Jeffrey (https://github.com/chjj/)
```

## Executor CLIs

Coding-agent CLIs (Claude Code, Codex, CodeBuddy, ...) are **not** bundled.
Operators install them separately; they remain subject to their providers'
licenses and terms of service.
