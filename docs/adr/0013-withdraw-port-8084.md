# ADR-0013: Withdraw port 8084; deployment paused pending a port/service decision

- **Status**: Accepted — reverses the port allocation in ADR-0012; the target (the Debian server) is unchanged
- **Date**: 2026-09-09
- **Deciders**: Project owner

## Context

ADR-0012 allocated this platform 8083 (Order API), **8084 (BO)** and 9090
(Socket), chosen to fit around the Spring services already occupying
8080-8082. It was deployed and verified working on that basis.

The owner then described the scheme those ports were supposed to fit,
which had not been stated before:

```
8000        hub — everything is reached from here
8080        BO
8081-8089   API services
9090        Socket
```

Port numbers carry meaning: the range tells you what kind of thing is
listening. This retroactively explains the owner's earlier instruction —
"BO takes 8080, the Order that was on 8080 moves to 8083" — which had been
read as being about this repository's own listeners rather than the
server's whole allocation.

**8084 is wrong under that scheme**: it is inside the API range, but it
was serving BO.

There is a second, larger mismatch. The scheme assumes **one container per
service** — that is what the owner named as the main appeal of the current
setup ("여러 개의 서비스가 붙는다, docker를 통해서"). This platform ships
as *one* container binding three ports across three different categories
(BO, API, Socket), which no allocation of numbers can fix.

## Decision

**Withdraw 8084 and stop deploying for now.**

- The container has been stopped and removed from the server; 8083, 8084
  and 9090 are released. The image (`sun-moon-netty`, 368 MB), the clone
  and its `.env` remain in place, so redeploying is one command once the
  ports are settled.
- `DEPLOYMENT.md` (+`_kr`) carries a paused banner, and every command in
  it now says `<BO_PORT>` instead of 8084. The rest of the runbook —
  Dockerfile, env-file convention, database steps, verification — is
  unaffected and still correct.
- **No new port is assigned, and no split is started.** BO cannot simply
  take 8080 while the Spring Order still holds it, and moving that service
  is the owner's call on a system that has been up for 34 hours.

Nothing in the application code changes: `BO_PORT` is an environment
variable, and the code default (8080) already matches the scheme.

## Alternatives considered

- **Reassign BO to a free port outside the API range and redeploy now** —
  rejected. It would repeat the same mistake: picking a number before the
  structural question (one container or several) is answered.
- **Move the Spring Order to 8083 immediately so BO can take 8080** —
  not taken unilaterally; it means rebuilding and restarting a running
  service, and this repository does not own it.
- **Leave the container running on 8084 until the redesign lands** —
  rejected by the owner's instruction, and rightly: a service sitting on a
  number that contradicts the scheme is exactly the kind of thing that
  quietly becomes permanent.

## Consequences

- The platform is **not currently deployed anywhere**. Everything verified
  on 2026-09-09 (Dockerfile builds, BO login, Common Code and Device CRUD,
  Order API, Socket echo, metrics — all on real hardware) still stands as
  a result; it just isn't running right now.
- The open question is no longer "which port" but **"how many
  containers"** — whether BO becomes its own deployable (its own repo,
  linked from this one, matching how the Spring services are organised),
  and whether the Order API and Socket stay together. That decision
  reopens ADR-0002's single-runtime premise and deserves its own ADR.
- Also on the table for that discussion: turning the 8000 page from a
  static link list into a real reverse proxy, so that Python and C
  services can join without fighting over ports — all three projects
  currently default to 8081/8082/8090.

## References

- ADR-0012 (target and the now-withdrawn allocation), ADR-0002 (the
  single-runtime premise this reopens), ADR-0009 (the BO/API port split).
- `Alignment` ADR-0007 — "prefer process isolation over port-splitting",
  the argument that now applies directly.
