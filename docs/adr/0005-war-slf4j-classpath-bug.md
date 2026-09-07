# 0005: WAR deploy failure — slf4j-api silently missing from WEB-INF/lib

## Status

Resolved.

## Summary

Deploying the WAR to Jetty repeatedly failed with `ClassNotFoundException:
org.slf4j.Logger` / `org.slf4j.LoggerFactory`, thrown from deep inside
Logback's or Spring's own startup code (`LogbackServletContainerInitializer`,
`IntrospectionFailureLogger`, etc.), long before the application's own code
ever ran.

**Root cause:** `build.gradle.kts` declared

```kotlin
providedRuntime("org.springframework.boot:spring-boot-starter-jetty")
```

so the app would run locally via `bootRun`/tests but not bundle a servlet
container into the WAR (the external Jetty provides one). Spring Boot's
`bootWar` task packages any dependency reachable through a `providedRuntime`
path into `WEB-INF/lib-provided/` instead of `WEB-INF/lib/` — and `slf4j-api`
was transitively reachable that way (via
`spring-boot-starter-jetty → spring-boot-starter → spring-boot-starter-logging`),
so `bootWar` routed it into `lib-provided/` even though the app also needed
it at actual runtime. `WEB-INF/lib-provided/` is never added to a deployed
webapp's classpath — only `WEB-INF/lib/` is. The class was simply **absent**
from the running application, full stop.

**Fix:** exclude `slf4j-api` from the `providedRuntime` dependency, and
declare it directly as `implementation`, forcing it into `WEB-INF/lib/`:

```kotlin
implementation("org.slf4j:slf4j-api:2.0.16")

providedRuntime("org.springframework.boot:spring-boot-starter-jetty") {
    exclude(group = "org.slf4j", module = "slf4j-api")
}
```

Declaring `implementation("org.slf4j:slf4j-api:...")` *alone*, without the
exclude, was not sufficient — `providedRuntime` membership apparently takes
priority in `bootWar`'s classification regardless of what else also declares
the same dependency.

## Why this took so long to find

The actual symptom (`ClassNotFoundException: org.slf4j.Logger`) is
indistinguishable, from the stack trace alone, between two completely
different classes of problem:

1. **Classloader isolation/visibility** — the class exists somewhere on
   disk, but Jetty's webapp classloader (system/server class rules,
   `WEB-INF/jetty-web.xml`, EE10 module configuration) is blocking access
   to it.
2. **The class is genuinely missing from the deployed artifact** — a plain
   packaging/dependency-declaration bug, nothing to do with Jetty at all.

Every attempted fix chased (1): Jetty's default system/server class lists,
`ClassMatcher` configuration in `jetty-web.xml`, `ee10-annotations`
scanning, an internal-vs-external deployment-descriptor timing issue
(`WEB-INF/jetty-web.xml` is processed *after* `AnnotationConfiguration`
already invokes ServletContainerInitializers, so attribute-setting there is
too late — an external `${jetty.base}/webapps/<name>.xml` descriptor is
needed to configure the `WebAppContext` early enough), and a
`containerInitializerExclusionPattern` workaround to skip Logback's own
optional servlet-integration hook. Some of these were legitimate, correct
pieces of Jetty/EE10 knowledge — and are still in
`src/main/webapp/WEB-INF/jetty-web.xml` and
`~/apps/java-war/base/webapps/sun-moon-java-platform.xml` on the server —
but none of them were *the* bug. **The actual fix was never a classloader
problem; a single `unzip -l build/libs/*.war | grep slf4j` early on would
have shown the jar sitting in `lib-provided/` and pointed straight at the
real cause.**

## Lesson

When a "class not found inside a container" error shows up, check **whether
the class is actually present in the deployed artifact** (`unzip -l` /
`jar tf`) before reasoning about classloader visibility rules. Visibility
rules only matter once presence is confirmed; skipping that check burns a
lot of time chasing the wrong layer of the problem.
