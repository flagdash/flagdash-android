# FlagDash Android SDK

Feature flags, remote config, AI configs, translations and experiments for
Android, written in Kotlin with coroutines.

## Installation

```kotlin
dependencies {
    implementation("io.flagdash:flagdash-android:0.1.0")
}
```

## Quick start

```kotlin
import com.flagdash.sdk.FlagDashClient
import com.flagdash.sdk.EvaluationContext

val client = FlagDashClient(sdkKey = BuildConfig.FLAGDASH_SDK_KEY)

val context = EvaluationContext(userId = "alice")

if (client.flagBoolean("checkout-v2", false, context)) {
    // new checkout
}
```

Every read is a `suspend` function, so call them from a coroutine —
`lifecycleScope`, a `ViewModel`'s `viewModelScope`, or `withContext(Dispatchers.IO)`.

## API keys

Ship a **client key** (`pk_`). It carries the project and environment, which is
why no method takes an `environment` argument, and it never returns targeting
rules — an app cannot see who else you are targeting.

Never embed a server key (`sk_`) in an APK. Anything shipped to a device is
readable, and translations and experiments below need a server key: call them
from your backend when a client key is what the app holds.

## Configuration

```kotlin
val client = FlagDashClient(
    sdkKey = key,
    baseUrl = "https://flagdash.io",  // self-hosted? point it here
    timeoutMillis = 5_000,
    cacheTtlMillis = 60_000,
    region = null,                    // null auto-detects
    httpClient = null,                // supply your own OkHttpClient to share a pool
)
```

A blank `sdkKey` throws at construction — a missing key should fail loudly at
start-up rather than quietly serve defaults forever.

`FlagDashClient` implements `Closeable`. It owns its `OkHttpClient` only when
you did not pass one, so sharing your app's client is the cheaper choice.

## Evaluation context

```kotlin
val context = EvaluationContext(
    userId = "alice",
    attributes = mapOf("country" to "GB", "plan" to "premium"),
)
```

**Set `userId`** (or `unitId`) whenever you want a stable answer. Percentage
rollouts and A/B variations hash it, so a context without one re-rolls on every
call by design.

## Feature flags

Typed helpers for the common cases, and a `JsonElement` form for everything
else:

```kotlin
client.flagBoolean("checkout-v2", false, context)
client.flagString("banner-copy", "control", context)
client.flag("limits", JsonPrimitive(0), context)      // JsonElement

// Every flag for this context, in one request.
val flags: Map<String, JsonElement> = client.allFlags(context)

// Why did it resolve that way?
val detail = client.flagDetail("checkout-v2", context = context)
detail.value
detail.reason        // "rule_match", "rollout", "default", ...
```

Note that a call without a context is served from cache; with a context it asks
for a fresh evaluation.

## Remote config

```kotlin
val limit = client.config("rate_limit", JsonPrimitive(100))
val all = client.listConfigs()
```

## AI configs

Prompts, agents, skills and rules, versioned per environment and editable
without a Play Store release.

```kotlin
val agent = client.aiConfig("support-agent.md")
val files = client.listAiConfigs()
```

## Translations (server key)

```kotlin
val greeting = client.translation(
    key = "checkout.greeting",
    locale = "fr",
    defaultValue = "Hello",
    variables = mapOf("name" to "Alice"),
)
```

The key is `namespace.message`. `{placeholders}` come from `variables`, and the
default (falling back to the key) is returned whenever the catalogue, namespace
or message is missing.

## Experiments (server key)

```kotlin
val assignment = client.experiment("checkout-redesign", context)

if (assignment?.get("variant")?.toString() == "\"treatment\"") {
    // ...
}

client.trackExperimentMetric(
    experimentKey = "checkout-redesign",
    eventName = "purchase",
    userId = "alice",
    value = 42.50,
    properties = mapOf("currency" to "GBP"),
)
```

`experiment` returns `null` for a context with no identifier — an assignment
that cannot be stable is worse than none.

Metrics are buffered in memory (up to 1000) and sent by `flush()`:

```kotlin
client.flush()
```

On mobile, flush when the app backgrounds — a process that is killed with a
full buffer loses the events in it.

## Caching

Reads are cached in memory for `cacheTtlMillis` (60s by default) in a
`ConcurrentHashMap`, so a burst of `flag` calls costs one request and the client
is safe to share across coroutines.

```kotlin
client.clearCache()
client.close()
```

Evaluating on launch and on foreground is usually better than per screen.

## Failure behaviour

Evaluation reads return the default you passed rather than throwing, so a flaky
mobile network degrades to your fallback values instead of crashing a screen.

## License

MIT
