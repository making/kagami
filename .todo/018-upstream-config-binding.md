# 018 UpstreamConfig properties are not bound

Difficulty: Low

## Context

`KagamiProperties.UpstreamConfig` declares a no-arg constructor and a four-argument
constructor in addition to its canonical record constructor. Spring Boot's value-object
binder only binds a nested record when its canonical constructor is the sole constructor;
with the extra constructors it falls back to the bean binder, which finds no setters on a
record and silently returns the values of `@DefaultValue`. A direct `Binder` test showed
`kagami.upstream.connect-timeout=7s` still producing `PT5S`.

The same pattern made the new `Retention` record ignore its limits until the extra
constructor was removed (todo 017).

## Approach

- Remove the extra constructors from `UpstreamConfig` and use its canonical constructor at
  the call sites that need a default instance.
- Add a binding test that sets `kagami.upstream.connect-timeout` and
  `kagami.upstream.tarball-hosts` and asserts the bound values.
- Check every other nested configuration record for additional constructors.

## Completion criteria

- `kagami.upstream.*` values reach the application.
- A test fails if a nested configuration record gains an extra constructor that stops the
  binding.
