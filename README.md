# activitywatch-akka

Record what a person does on a computer, keep it on that computer, and answer questions about
it.

A port of [ActivityWatch/activitywatch](https://github.com/ActivityWatch/activitywatch)
onto **Akka**, built with **Akka Specify**.

---

## Where it came from

ActivityWatch is a set of small programs that watch which window is in front, whether the
keyboard and mouse have been touched, and what a browser has open, and send that to a server
on the same machine which keeps the history and answers questions about it. Nothing leaves
the machine.

It was rebuilt here to find out how precisely a system has to be written down before somebody
else can rebuild it from the writing alone. The port is the vehicle; the written description
is what was being made.

Those written descriptions live in a separate repository, `akka-specify-harness`, under
`activitywatch-port/`. It is private for now.

---

## ActivityWatch/activitywatch → this port

📉 8,898 Python and Rust lines → **8,019 Java lines**<br>
📁 153 files → **67 files**<br>
🖥️ 6 programs to start → **1**<br>
⚡ 23.7 → **6.1** milliseconds, recording one "still doing this" message<br>
⚡ 70.3 → **13.5** milliseconds, turning a day of recorded windows into time per program<br>
⚡ 21.4 → **12.3** milliseconds, listing the recordings a machine holds<br>
🎯 179 requests put to both → **173 answer identically**<br>
🎯 28 command-line invocations put to both → **28 answer identically**<br>
🧪 0 tests → **256**

Full method, the six differences, and the numbers that did *not* make this list:
[`bench/REPORT.md`](bench/REPORT.md).

---

## What it took to build

⏱️ **161.6 hours** from the first command to the published repository, **6.3** of them active<br>
💬 **1,678** exchanges with the model<br>
✍️ **1,977,237** tokens written by the model, **704,522,708** counting everything sent and re-sent<br>
🙋 **0** questions to a human<br>
🧪 **256** tests

```bash
python toolkit/tokens.py --port activitywatch    # turns, tokens, elapsed and active time
```

The record of every question, and where the time went, is in `port-log/` in the harness
repository.

---

## What it does

- **A message that says "still doing this" extends the recording it matches rather than
  starting a new one.** A window kept open for an hour is one entry of an hour, not three
  hundred entries of twelve seconds.
- **A recording is only extended when the two say exactly the same thing and the gap is
  small enough.** Change window, or come back after too long, and a new entry starts.
- **A stretch when nobody touched the machine is recorded too, and can be taken out of the
  answer.** Time at the desk and time away from it are different questions.
- **Everything is answered from what is on the machine.** No account, no upload, no key.
- **A recording never disappears because there is a lot of it.** Each day is kept
  separately, so a year of history costs the same to write as the first day did.
- **What the screen shows follows what the server holds, without asking again.** Open two
  windows on the same page and a change made in one appears in the other.

---

## Design decisions

**One process.** The original starts a server, a menu, three watchers and a notifier, and
each of them can be running when the others are not. Here they are one program, so there is
one thing to start and one thing that can be down.

**A day at a time.** Keeping every recording a machine has ever made in one place makes the
oldest ones cost something every time a new one arrives. Splitting them by day means writing
today never gets slower.

**Push, not ask.** The old screen asked the server for everything again every fifteen
seconds, whether or not anything had changed. Now the server tells the screen when something
changes, so a page left open costs nothing and a change shows up in a fraction of a second.

**Say what the old one says, including where it is odd.** Three requests make the original
answer with an error, and this one answers with the same error. Somebody's watcher may be
written against that answer, and a port that quietly improved it would break them.

---

## Running it — the short path

You do not need Java, Maven, or the Akka CLI installed. Akka Specify installs them for you.

**1. Install Akka Specify** in Claude Code:

```
/plugin marketplace add akka/ai-marketplace
/plugin install akka@akka-ai-marketplace
```

Restart Claude Code when it asks.

**2. Give it this prompt:**

> Clone https://github.com/TylerJewell/activitywatch-akka into a new directory and open it.
> Then run /akka:setup to install everything this project needs, and /akka:build to
> compile it, run the tests, and start it locally.

**3. Open** http://localhost:9150.

---

## Running it — the developer path

### Requirements

- Java 21 or newer
- Maven 3.9 or newer
- An Akka download token — run `akka code token` once

### Start the service

```bash
mvn compile
akka local run
```

The service starts on **port 9150**.

### The command-line tools

```bash
mvn compile
java -cp target/classes:$(cat classpath.txt) io.akka.activitywatch.cli.AwClientCli --help
java -cp target/classes:$(cat classpath.txt) io.akka.activitywatch.cli.AwCli --help
java -cp target/classes:$(cat classpath.txt) io.akka.activitywatch.cli.AwNotifyCli --help
```

### Turn the watchers on

They are off until asked, because they read what is on the screen.

```bash
curl -X POST http://localhost:9150/api/0/watchers/aw-watcher-window/start
curl -X POST http://localhost:9150/api/0/watchers/aw-watcher-afk/start
curl -X POST http://localhost:9150/api/0/watchers/aw-watcher-input/start
```

---

## Model providers

This system calls no model. There is nothing to configure and no key to set.

---

## Configuration

Everything is set in `src/main/resources/application.conf` and can be overridden by an
environment variable.

| Variable | Default | Notes |
|---|---|---|
| `AW_HOSTNAME` | the machine's own name | what recordings made here are labelled with |
| `AW_TESTING` | `false` | when true, deleting a recording needs no extra confirmation and one more page is allowed to talk to the server |
| `AW_RETAINED_EVENTS` | `0` | how many recent entries each recording keeps to hand; `0` is all of them |
| `AW_CORS_ORIGINS` | none | other pages allowed to talk to the server, separated by commas |
| `AW_HOST` | `localhost` | the name this server answers to; a request addressed to any other name is refused |
| `AW_CUSTOM_STATIC` | none | extra folders to serve pages from, written `name=path` and separated by commas |
| `AW_WATCHER_AFK` / `AW_WATCHER_WINDOW` / `AW_WATCHER_INPUT` | `false` | start that watcher when the service starts |

The notification service keeps its own file, `aw-notify/config.toml`, next to the other
settings. It is written with the defaults in it the first time the service runs.

---

## Where it differs from ActivityWatch/activitywatch

Everything not listed here behaves the same way on purpose, including the parts that look
like mistakes.

- **Which recording gets extended when two of them start at the same instant.** The original
  ships three ways of storing recordings and two of them disagree here: two pick the one that
  started last, the third picks the one that ended last. This port extends the one the
  decision actually named, by the number it was given when it was stored, because that is
  what both readings mean whenever messages arrive in order.
- **Recordings are cut to the window you asked for.** The original does this in the way it
  ships with and does not in the other two. This port cuts, matching what is shipped.
- **Three requests answer with an error.** Changing a recording's details, asking for the
  log, and giving an unreadable date all fail on the original. This port fails the same way,
  because a watcher may already be written against it.
- **How much history one recording keeps to hand.** The original keeps everything in one
  file and is limited by the disk. This port keeps the two hundred most recent entries
  immediately to hand and the rest a day at a time, so a recording years old is still
  cheap to add to. Everything is kept either way.
- **What the server remembers about a recording after it is deleted.** The original keeps
  the last entry it wrote in its own memory and does not forget it when the recording is
  deleted, so making one again under the same name and sending a matching message loses that
  message — and, on the version measured here, answers with an error. This port keeps that
  memory with the recording, so deleting takes it with it.
- **What happens when the screen loses its connection to the server.** The original's screen
  asks again every few seconds and has no answer to this. This port's screen is told when
  something changes, so it also has to say what happens when the telling stops: it picks up
  where it left off, and where it cannot it reads the window it is showing again. Nothing is
  shown twice and nothing is missed.
- **What the number on an entry counts within.** The original numbers every entry on the
  machine from one shared sequence, so the first entry of the second recording is numbered
  four. This port numbers each recording's entries from one, because each recording is kept
  separately and a shared counter would be one place everything has to queue. The number is a
  handle, and every request that takes one already says which recording it is in.
- **When something just written can be read back.** Both answer the next request with it. The
  original writes to its file inside the request; this port writes it to the recording inside
  the request and copies it to that day's store afterwards, and answers from both together.
- **What time a recording says it was made.** The original writes down the clock on the wall
  and labels it as the clock in London, so the moment it reports is wrong by however far the
  machine is from London — seven hours, on the machine this was measured on. This port writes
  down the moment. A caller that supplied its own time gets that time back on both.
- **The sentence a "no such recording" answer ends with.** Both say `There's no bucket named
  X`. The original then lists the addresses it thinks you meant, which its web framework
  writes rather than ActivityWatch. This port stops after the first sentence.
- **Where another program sends a notification.** The original listens on a second address of
  its own for this, and only when its settings name one. This port takes it at `/notify` on
  the address everything else uses, and answers "no such address" until the notification
  service is running.
- **Which of two activities with exactly the same time is listed first.** The original's
  answer depends on the order things came out of memory that run, so it can differ between
  two runs over the same day. This port puts them in alphabetical order.
- **The layout of the settings file the notification service writes.** The original's is
  written by a library and this one's is written by hand, with a line above each setting
  saying what it is for. Both read either.
- **The notification service was read, not run.** It is the one part of the original written
  in Rust, and the machine this was built on has no way to build Rust. Its thirty-three rules
  were established by reading its source rather than by running it, which is weaker evidence
  than everything else here. If one thing in this port is wrong, it is most likely there.

---

## Licence

ActivityWatch is Mozilla Public License 2.0, © the ActivityWatch contributors. This port
ships ActivityWatch's own web interface unchanged and reimplements the rest of the behaviour
without copied source; see [`ACKNOWLEDGEMENTS.md`](ACKNOWLEDGEMENTS.md), which lists every
string that appears in both.
