# activitywatch-akka

Take a steady stream of "the user is still doing this" messages and work out how long they
spent on each thing, leaving out the stretches when nobody touched the machine.

A port of [ActivityWatch/activitywatch](https://github.com/ActivityWatch/activitywatch)
onto **Akka**, built with **Akka Specify**.

---

## Where it came from

ActivityWatch records what you do on your computer. Small programs watch which window is in
front and whether the keyboard and mouse have been touched, and send that to a server which
keeps the history and answers questions about it. It was rebuilt here to find out how
precisely a system has to be written down before it can be rebuilt on a different stack.

Only the recording and the adding up were rebuilt. Reading the keyboard and mouse, drawing
the charts, and sorting activities into categories are somebody else's job here.

Those written specifications live in a separate repository, `akka-specify-harness`, under
`activitywatch-port/`. It is private for now.

---

## ActivityWatch/activitywatch → this port

📉 1,278 Python lines → **940 Java lines**<br>
📁 14 files → **14 files**<br>
⚡ 1,356 → **247** nanoseconds, deciding whether one message continues what came before<br>
⚡ 35.3 → **3.0** milliseconds, turning a day of recorded windows into time per program<br>
🎯 9,597 answers compared → **9,597 of 9,597 agree**<br>
🗑️ 3 of 3 late messages destroy an already-recorded stretch → **0 of 3**<br>
🔢 2 of 2 storage choices give a different history from the same messages → **1 of 1**<br>
🧪 0 rules broken on purpose to check a test notices → **17**

The two storage choices are the original's own, shipped in the same release, and they
disagree with each other only when a message arrives out of order. Read
[`bench/REPORT.md`](bench/REPORT.md) §1.2 before quoting that pair anywhere.

Full method, and the numbers that did not make this list: [`bench/REPORT.md`](bench/REPORT.md).

---

## What it took to build

⏱️ **1.1 hours** from the first command to the published repository, **1.1** of them active<br>
💬 **287** exchanges with the model<br>
✍️ **404,400** tokens written by the model, **61,128,879** counting everything sent and re-sent<br>
🙋 **0** questions to a human<br>
🧪 **56** tests

```bash
python toolkit/tokens.py --port activitywatch
```

The record of every question, and where the time went, lives with the specifications.

---

## What it does

- **Two messages become one stretch of time only when they say exactly the same thing and
  the second arrives soon enough after the first ends.** Change the window title and a new
  stretch begins; look away for a minute and the old stretch stays the length it was.
- **A message is compared against one earlier stretch and no others — the one written most
  recently.** A message that turns up late therefore starts its own stretch rather than
  extending one from further back.
- **The machine counts as untouched the moment the time since the last key or click reaches
  the limit, not after it.** A limit of three minutes means exactly three minutes of
  stillness is already counted as away.
- **A stretch is stamped with the moment of the last key or click, not the moment it was
  noticed.** Time spent away is never counted as time spent working, however late the
  message arrives.
- **A gap of a few seconds between two records of the same program is that program
  continuing; a gap between records of different programs is split down the middle.**
  Nothing says which side a gap of silence belongs to, so neither side gets all of it.
- **Time is only counted while the machine was in use.** Every recorded window is trimmed
  down to the stretches when somebody was actually there before anything is added up.

---

## Design decisions

**Naming the stretch a message extends.** A message that lengthens an earlier stretch says
which one, by a number handed out when that stretch began. Nothing has to guess which
record to update, so a message that arrives late can never overwrite the wrong one.

**Keeping the away-or-present switch on the server.** Whether somebody is currently away is
written down rather than held in the memory of a running program. A machine that restarts
while its owner is at lunch carries on the same absence instead of pretending they just sat
down.

**Handing out new stretches as they happen.** Anything watching a recording is given each
new stretch as it is written, instead of asking again every few seconds. What it sees is
what happened, in the order it happened, rather than a snapshot of whatever was there when
it last asked.

**Keeping only the recent past in the record itself.** A recording holds its most recent two
thousand stretches and says plainly when older ones have been dropped. The record stays
small enough to be copied between machines quickly, which is what keeps a recording
available when one of them fails.

**Recognising a message already dealt with.** A message can carry a name, and one whose
name has been seen before changes nothing. A retry after a lost connection cannot turn one
stretch of work into two.

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

**3. Open** http://localhost:9011/tracking/buckets.

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

The service starts on **port 9011**.

### Record something

```bash
# make a place to record into
curl -X POST localhost:9011/tracking/buckets/window_laptop \
  -H 'content-type: application/json' \
  -d '{"type":"currentwindow","client":"demo","hostname":"laptop"}'

# say what is in front of you, twice, five seconds apart
curl -X POST localhost:9011/tracking/buckets/window_laptop/heartbeat \
  -H 'content-type: application/json' \
  -d '{"timestamp":"2026-01-01T12:00:00Z","duration":0,"data":{"app":"editor"},"pulsetime":10}'
curl -X POST localhost:9011/tracking/buckets/window_laptop/heartbeat \
  -H 'content-type: application/json' \
  -d '{"timestamp":"2026-01-01T12:00:05Z","duration":0,"data":{"app":"editor"},"pulsetime":10}'

# one stretch, five seconds long
curl localhost:9011/tracking/buckets/window_laptop/events

# watch new ones arrive
curl -N localhost:9011/tracking/buckets/window_laptop/watch
```

---

## Configuration

There is nothing to set. The service reads no environment variables and calls no outside
service.

| Setting | Default | Notes |
|---|---|---|
| `akka.javasdk.dev-mode.http-port` in `src/main/resources/application.conf` | 9011 | the port used when running locally |
| `retained` when a recording is made | 2,000 | how many recent stretches stay readable; the most that may be asked for is also 2,000 |
| `pulsetime` on each message | none — every message carries it | how long after a stretch ends a message may still be part of it, in seconds |

---

## Where it differs from ActivityWatch/activitywatch

Everything not listed here behaves the same way on purpose, including the parts that look
like mistakes.

- **Which recorded stretch a message lengthens.** ActivityWatch does not record which
  stretch a message was matched against, so each of its two ways of storing history picks
  one for itself: one rewrites whichever stretch starts latest, the other whichever ends
  latest. Given the same messages the two leave different histories, and in all three of
  the cases measured each destroys a stretch the other keeps. This port hands every stretch
  a number when it begins and rewrites the one that was actually matched, because a
  recording that quietly loses an afternoon is worse than one that keeps an overlap.
- **What survives a restart.** ActivityWatch remembers the stretch it wrote last in the
  memory of the running server; after a restart it falls back to whichever stretch starts
  latest, and after a late message those are not the same stretch, so the same messages
  leave different histories depending on whether a restart happened in between. This port
  writes it down, so a restart changes nothing.
- **What the away-or-present switch survives.** ActivityWatch's watcher holds it in memory
  and every run begins believing somebody is present. This port writes it down, so a
  watcher that stops and starts while its owner is away carries on the same absence, which
  is the answer somebody reading the history afterwards would expect.
- **The order the two messages at a switch arrive in.** ActivityWatch's watcher queues them
  to a client that sends them over the network, and nothing states what happens when two
  are in flight at once. This port writes the pair as one record and delivers them from
  that record, so half a switch cannot arrive and the two halves cannot swap places.
- **A message that arrives twice.** ActivityWatch has no notion of this: a retry after a
  timeout is simply another message, which joins the stretch before it harmlessly if it
  lands soon enough and starts a second stretch if it does not. This port lets a message
  carry a name and ignores one it has already applied, because it delivers the watcher's
  messages from a written record and a written record is replayed when something fails.
- **Watching a recording fill up.** ActivityWatch's web page asks the server again on a
  timer. This port hands each new stretch out as it is written; a watcher that loses its
  connection sees what was written after it reconnects, and reads the history back over the
  gap to cover what it missed. This changes what a watcher can see — how much is missed
  while disconnected, and how long before a change shows up.
- **How far back a recording can be read.** ActivityWatch keeps everything in a database on
  disk. This port keeps the most recent two thousand stretches and says plainly when older
  ones have been dropped, because the recording is copied between machines on every write
  and has to stay small enough for that to be quick.
- **Folding a list of messages down without altering the list.** ActivityWatch's routine
  for this removes the first item from the list it was handed and alters the items that
  survive, so the same list cannot be folded twice. This port copies first, matching what
  the routine beside it already does.
- **The millisecond gap after somebody comes back.** ActivityWatch opens the new
  present stretch one thousandth of a second after the last key or click, and the next
  reading names the key or click itself — a thousandth of a second earlier — so it does not
  join that stretch and a second, empty one is recorded instead. This port reproduces it
  exactly. The gap follows from two rules this port copies, and changing it would mean
  answering differently from the system this is a port of.
- **What happens on the third way of storing history.** ActivityWatch ships a third
  storage choice built on `peewee`. It failed to start on the machine this was measured on,
  so nothing was run against it and nothing is claimed about it — `not checked`.
- **What the Rust server does.** ActivityWatch ships a second server, written in Rust,
  with its own implementation of these rules. Everything here was established against the
  Python one. Whether the Rust one agrees is `not checked`.
- **Splitting a gap that is an odd number of millionths of a second wide.** Both sides
  round the halfway point to the nearest millionth of a second, with an exact half going to
  the even one. The rounding was matched by reading the original's arithmetic rather than by
  running it, and no measured case reaches it — `not checked`.

---

## Licence

ActivityWatch is Mozilla Public License 2.0, © the ActivityWatch contributors. This port
reimplements the behaviour without copied source, and is published under the same licence;
see [`ACKNOWLEDGEMENTS.md`](ACKNOWLEDGEMENTS.md).
