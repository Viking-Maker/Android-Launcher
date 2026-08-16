# T3 — Usage-persistence options for frequent-app ranking

- **Issue:** [#3 — Survey usage-persistence options for frequent-app ranking](https://github.com/Viking-Maker/Android-Launcher/issues/3)
- **Wayfinder map:** [#1](https://github.com/Viking-Maker/Android-Launcher/issues/1) (parent) · blocked by [#2](https://github.com/Viking-Maker/Android-Launcher/issues/2) · blocks #4, #5, #7, #8, #9, #10
- **Context pointer:** `launcher-ideas.md` §"Frequent App Usage Persistence" (developer blueprint) and `launcher-UI.md` §"Pin (Favorites / Frequently Used)". Nornir Launcher = Android home launcher, Kotlin + Jetpack Compose, package `com.vm.nornir.launcher`, minSdk 26. Plan-only ticket: no app code exists yet.
- **Status:** research complete — recommendation below is ready to commit to the plan.
- **Sources:** primary only — AOSP source (`android.googlesource.com`), `developer.android.com` reference/guides, and Google Play Console Help policy pages. Every claim below is tied to the source that owns it.
- **Date:** 2026-08-16

---

## 1. Recommendation (commit this to the plan)

**Self-track launches in-app; persist with Preferences DataStore; rank by recency-weighted score with a half-life. Do not request `PACKAGE_USAGE_STATS`.**

| Decision | Choice | Single strongest reason |
| --- | --- | --- |
| Source of truth | **Nornir's own launch events** (recorded in `repository.launchApp()`) | The launch *count* field in platform usage stats is **not public API** — `UsageStats.getAppLaunchCount()` is `@SystemApi`/`@hide` (§2.2). A third-party launcher literally cannot read launch counts. |
| Storage | **Preferences DataStore** (`androidx.datastore:datastore-preferences`) | Official docs: "DataStore is ideal for small datasets"; SharedPreferences reference says the Android team "strongly recommends against" it and that "the lack of transactional semantics makes operations like incrementing a counter unsafe" — and incrementing a counter is exactly this workload (§3). |
| Ranking | **Exponential decay with a 14-day half-life**, `score = Σ 2^(-Δt/T½)`, surfaced as top-N | Mirrors AOSP's own resolver ranker, which weights both launch count and recency and squares+doubles the recency feature (§4). Decay needs no cron job and no event log. |
| Permissions | **Zero additional permissions** for usage data | Usage access is an `appop` special permission with no runtime dialog, granted only by a trip to Settings, and AOSP Settings describes it to the user as "track what other apps you're using and how often" (§5). |

**Consequence for downstream tickets:** the persisted model is a single map `packageName -> (score: Float, lastLaunchMillis: Long)` plus a `schemaVersion`. No Room, no SQLite, no migrations, no permission-request UX, no Play Console permission declaration for usage access.

---

## 2. In-app tracking vs platform usage stats

### 2.1 What `queryAndAggregateUsageStats` actually returns

- It is a convenience wrapper over `queryUsageStats(INTERVAL_BEST, ...)`: "A convenience method that queries for all stats in the given range (using the best interval for that range), merges the resulting data, and keys it by package name." Its javadoc states "The caller must have `android.Manifest.permission#PACKAGE_USAGE_STATS`" and it returns "A `java.util.Map` keyed by package name". — [`UsageStatsManager.java`](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/app/usage/UsageStatsManager.java)
- The implementation returns `Collections.emptyMap()` when the underlying query list is empty, so a permissionless caller gets an **empty map, not an exception**. — same file
- `queryUsageStats` warns the returned range may not be what you asked for: "Note: The begin and end times of the time range may be expanded to the nearest whole interval period." And on locked devices: "Starting from Android R, if the user's device is not in an unlocked state ... then `null` will be returned." — same file

### 2.2 The decisive fact: launch count is not public API

- `UsageStats` exposes launch count only as `@SystemApi` + `@hide`:
  ```java
  /**
   * Returns the number of times the app was launched as an activity from outside of the app.
   * Excludes intra-app activity transitions.
   * @hide
   */
  @SystemApi
  public int getAppLaunchCount() {
      return mAppLaunchCount;
  }
  ```
  — [`UsageStats.java`](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/app/usage/UsageStats.java)
- Confirmed by the API surface files: `getAppLaunchCount` is **absent** from the public SDK surface [`core/api/current.txt`](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/api/current.txt) and **present** in [`core/api/system-current.txt`](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/api/system-current.txt), which lists exactly:
  ```
  public final class UsageStats implements android.os.Parcelable {
    method public int getAppLaunchCount();
    method public long getLastTimeAnyComponentUsed();
  }
  ```
- The full public method set of `UsageStats` in `current.txt` is: `getFirstTimeStamp`, `getLastTimeForegroundServiceUsed`, `getLastTimeStamp`, `getLastTimeUsed`, `getLastTimeVisible`, `getPackageName`, `getTotalTimeForegroundServiceUsed`, `getTotalTimeInForeground`, `getTotalTimeVisible`. **No count of any kind.** — [`current.txt`](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/api/current.txt)
- The backing fields are likewise hidden: `public int mLaunchCount;` is annotated `@UnsupportedAppUsage` and `mAppLaunchCount` is `{@hide}`. — [`UsageStats.java`](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/app/usage/UsageStats.java)

> **Therefore the premise in the ticket needs correcting.** `queryAndAggregateUsageStats` does **not** give a third-party app "real launch counts". Even with `PACKAGE_USAGE_STATS` granted, a non-system launcher gets only *recency* (`getLastTimeUsed`) and *dwell time* (`getTotalTimeInForeground`). Reconstructing counts requires `queryEvents` and tallying `ACTIVITY_RESUMED` yourself.

### 2.3 The public fallback — and why it is still worse than self-tracking

- The event API is public: `UsageEvents.Event` in `current.txt` exposes `ACTIVITY_RESUMED = 1`, `ACTIVITY_PAUSED = 2`, `getPackageName()`, `getTimeStamp()`. So counts *can* be tallied from events. — [`current.txt`](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/api/current.txt)
- But `queryEvents(long, long)` is gated the same way — the service returns `null` for unpermitted callers:
  ```java
  public UsageEvents queryEvents(long beginTime, long endTime, String callingPackage) {
      if (!hasQueryPermission(callingPackage)) {
          return null;
      }
  ```
  — [`UsageStatsService.java`](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/services/usage/java/com/android/server/usage/UsageStatsService.java)
- Platform history is bounded, so an event-tally is not durable: `static final int[] MAX_FILES_PER_INTERVAL_TYPE = new int[]{100, 50, 12, 10};` (daily/weekly/monthly/yearly file caps). — [`UsageStatsDatabase.java`](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/services/usage/java/com/android/server/usage/UsageStatsDatabase.java)
- Event scanning cost scales with all device activity, not with Nornir's own launches — a full week of every app's resume/pause events parsed to count a few hundred launches. Self-tracking is O(1) per launch.
- `queryEventsForSelf(long, long)` needs no permission but only covers the calling package, which is useless here: "Methods which only return the information for the calling package do not require this permission." — [`UsageStatsManager.java`](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/app/usage/UsageStatsManager.java)

### 2.4 Nornir has a perfect, free hook

- Nornir *is* the launcher, so it observes every launch it performs. The launch path already exists in the blueprint (`repository.launchApp()`), and app enumeration is via `LauncherApps`, "Class for retrieving a list of launchable activities ... This is mainly for use by launchers." — [`LauncherApps.java`](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/content/pm/LauncherApps.java)
- Known and accepted blind spot: launches from recents, notifications, widgets, or another launcher are invisible to self-tracking. This is the one real advantage platform stats retain (recency only). Given §2.2 it is not enough to justify the permission — and it is *not* a bug for a keyboard-driven launcher, where the ranking should reflect "what I search for and launch here".

### 2.5 Precedent: AOSP's own launcher does not take this permission

- `PACKAGE_USAGE_STATS` does **not** appear in Launcher3's manifests. [`AndroidManifest.xml`](https://android.googlesource.com/platform/packages/apps/Launcher3/+/refs/heads/main/AndroidManifest.xml) and [`AndroidManifest-common.xml`](https://android.googlesource.com/platform/packages/apps/Launcher3/+/refs/heads/main/AndroidManifest-common.xml) declare `CALL_PHONE`, `SET_WALLPAPER`, `BIND_APPWIDGET`, `QUERY_ALL_PACKAGES`, `VIBRATE`, `POST_NOTIFICATIONS`, etc. — but no usage access.
- Instead, Launcher3 *emits* its own launch events to the platform predictor: `AppEventProducer` is a "Utility class to track stats log and emit corresponding app events" with `private static final int MSG_LAUNCH = 0;`, feeding `AppTargetEvent`. — [`AppEventProducer.java`](https://android.googlesource.com/platform/packages/apps/Launcher3/+/refs/heads/main/quickstep/src/com/android/launcher3/model/AppEventProducer.java)
- That predictor path is closed to us anyway: `AppPredictionManager`, `AppPredictor`, and `AppTarget` are absent from `current.txt` and present in `system-current.txt` — `@SystemApi` only. So the AOSP-launcher pattern (emit events, let the system rank) is unavailable to a third-party launcher, leaving self-tracking as the only viable route. — [`system-current.txt`](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/api/system-current.txt)
- The `HOME` role does not hand out usage access either. The `android.app.role.HOME` block grants only `READ_HOME_APP_SEARCH_DATA`, `ALLOW_SLIPPERY_TOUCHES`, and `RECEIVE_SENSITIVE_NOTIFICATIONS` — becoming the default launcher earns **no** usage-stats access. — [`roles.xml`](https://android.googlesource.com/platform/packages/modules/Permission/+/refs/heads/main/PermissionController/res/xml/roles.xml)

**Verdict:** track internally. The permission buys recency we can approximate ourselves, and cannot buy the counts we actually want.

---
## 3. Storage: DataStore vs Room vs SharedPreferences

The workload: ~100–600 entries, one read-modify-write per app launch, one full read at startup, and a read on every keystroke while filtering. Values are a `Float` score and a `Long` timestamp per package.

### 3.1 SharedPreferences — ruled out by its own reference page

The `SharedPreferences` reference now carries a first-party warning and a drawback list:

- "Note: The Android team **strongly recommends against using `SharedPreferences` for new data storage needs.** Instead, consider using Jetpack DataStore for storing small amounts of data, or Room for relational data and larger datasets."
- "**UI Thread Blocking and ANRs:** Since `SharedPreferences` provides a synchronous API, reading and writing to disk can block the UI thread, leading to jank and StrictMode violations. Additionally, pending `Editor.apply()` calls will block the main thread during component lifecycle transitions ... This is a common source of Application Not Responding (ANR) errors."
- "**Error Handling:** The asynchronous `Editor.apply()` method has no mechanism to signal errors to the caller. The synchronous `Editor.commit()` method only returns a boolean ... and can sometimes return `false` even when a write succeeds."
- "**Durability and Consistency:** Changes are reflected in memory immediately, before being successfully persisted to disk. This can result in data loss if the app crashes or is terminated. ... Furthermore, **the lack of transactional semantics makes operations like incrementing a counter unsafe without external locking.**"
- "**Data Safety:** Data corruption can lead to silent failures or uncatchable exceptions."
- "Note: This class does not support use across multiple processes."

— [`SharedPreferences` reference](https://developer.android.com/reference/android/content/SharedPreferences)

The counter sentence is decisive: a launch-count map *is* a counter increment, and the platform documents that as unsafe here. The guide page repeats the steer: "**Caution:** DataStore is a modern data storage solution that you should use instead of `SharedPreferences`." — [Save simple data with SharedPreferences](https://developer.android.com/training/data-storage/shared-preferences)

### 3.2 Room — capable but explicitly aimed elsewhere

- Room's own guide frames it around structured/relational data: "Apps that handle non-trivial amounts of structured data can benefit greatly from persisting that data locally." Benefits listed are "Compile-time verification of SQL queries", boilerplate-reducing annotations, and "Streamlined database migration paths". — [Save data in a local database using Room](https://developer.android.com/training/data-storage/room)
- The storage-options guide draws the line by shape of data: "For structured data, use either preferences (for key-value data) or a database (**for data that contains more than 2 columns**)." Our record is exactly 2 columns keyed by package. — [Data and file storage overview](https://developer.android.com/guide/topics/data/data-storage)
- The DataStore page states the condition that would flip us to Room, and we do not meet it: "**Note:** If you need to support large or complex datasets, partial updates, or referential integrity, consider using Room instead of DataStore." — [DataStore](https://developer.android.com/topic/libraries/architecture/datastore)

Room is not *wrong*, it is simply unindicated: it adds KSP annotation processing, a `@Database`/`@Dao`/`@Entity` triad, and a migration obligation to store one map. Note the current coordinates if a later ticket revisits this: "Room 3.0 requires KSP", `androidx.room3:room3-runtime:3.0.1`. — [Room guide](https://developer.android.com/training/data-storage/room)

### 3.3 Preferences DataStore — the fit

- Positioning: "Jetpack DataStore is a data storage solution that lets you store key-value pairs or typed objects with protocol buffers. DataStore uses Kotlin coroutines and Flow to store data **asynchronously, consistently, and transactionally**." And: "If you're using `SharedPreferences` to store data, consider migrating to DataStore instead." — [DataStore](https://developer.android.com/topic/libraries/architecture/datastore)
- Explicit sweet spot: "**DataStore is ideal for small datasets** and does not support partial updates or referential integrity." — same page
- Which flavour: "If you want to store and access data using keys, use the Preferences DataStore implementation which does not require a predefined schema, and it does not provide type safety. It has a `SharedPreferences`-like API but doesn't have the drawbacks associated with shared preferences." — same page
- API is exactly two members, both suited to a Compose launcher — a `Flow` for reactive reads and a transactional suspend writer: "A flow that can be used to read data from the DataStore — `val data: Flow<T>`" and "A function to update data in the DataStore — `suspend updateData(transform: suspend (t) -> T): T`". — same page
- Writes are read-modify-write **under a lock**, which is the transactional guarantee SharedPreferences lacks:
  ```kotlin
  private suspend fun transformAndWrite(...): T =
      coordinator.lock {
          ...
          val curData = readDataOrHandleCorruption(hasWriteFileLock = true, ...)
          val newData = withContext(callerContext) { transform(curData.value) }
          curData.checkHashCode()
          if (curData.value != newData) { writeData(newData, updateCache = true) }
          newData
      }
  ```
  — [`DataStoreImpl.kt`](https://android.googlesource.com/platform/frameworks/support/+/refs/heads/androidx-main/datastore/datastore-core/src/commonMain/kotlin/androidx/datastore/core/DataStoreImpl.kt)
- Persistence is a write-to-scratch-then-atomic-move, so a crash mid-write cannot shred the map: the write scope creates `val scratchPath = parentDir / "${path.name}.tmp"`, writes there, then `fileSystem.atomicMove(scratchPath, path)`, deleting the scratch file on `IOException`. — [`OkioStorage.kt`](https://android.googlesource.com/platform/frameworks/support/+/refs/heads/androidx-main/datastore/datastore-core-okio/src/commonMain/kotlin/androidx/datastore/core/okio/OkioStorage.kt)
- Cost to be aware of: each write serializes the **whole** value (`writeData(newData, ...)` above rewrites the file, not a delta) — consistent with "does not support partial updates". For a few hundred small entries this is a trivial file; it is the reason not to grow this store into a general event log.
- Corruption is a real, documented state and has a first-party remedy: "There are rare occasions where DataStore's persistent on-disk file could get corrupted. By default, DataStore doesn't automatically recover from corruption, and attempts to read from it will cause the system to throw a `CorruptionException`." Fix: "provide a `corruptionHandler` when creating the DataStore instance in `by dataStore` or in the `DataStoreFactory` factory method" — the handler "replaces the corrupted file with a new one containing a predefined default value." — [DataStore](https://developer.android.com/topic/libraries/architecture/datastore)
- Non-negotiable usage rules to encode in the plan: "**Never create more than one instance of `DataStore` for a given file in the same process.** Doing so can break all DataStore functionality. If there are multiple DataStores active for a given file in the same process, DataStore will throw `IllegalStateException` when reading or updating data." Also "The generic type of `DataStore<T>` must be immutable" and "Do not mix usages of `SingleProcessDataStore` and `MultiProcessDataStore` for the same file." — same page
- Multi-process is available but unnecessary for a single-process launcher: "**Note:** DataStore multi-process has been available since the 1.1.0 release." — same page

### 3.4 Comparison

| | Preferences DataStore | Proto DataStore | Room | SharedPreferences | Plain file / JSON |
| --- | --- | --- | --- | --- | --- |
| Officially recommended for small key-value | **Yes** ("ideal for small datasets") | Yes (typed) | Aimed at "non-trivial amounts of structured data" | **No** ("strongly recommends against") | No guidance |
| Safe counter increment | Yes (`updateData` under lock) | Yes | Yes (SQL) | **No** (documented unsafe) | Hand-rolled |
| Async / no UI-thread risk | Yes (Flow + suspend) | Yes | Yes (Flow/suspend DAO) | **No** (synchronous API, ANR source) | Hand-rolled |
| Atomic durable write | Yes (tmp + `atomicMove`) | Yes | Yes | **No** (in-memory first) | Hand-rolled |
| Partial update of one key | No (whole-file rewrite) | No | Yes | Yes-ish | No |
| Build cost | 1 dependency | 1 dep + protobuf plugin | KSP + DAO/Entity/Database + migrations | none | none |
| Verdict | **Chosen** | Overkill; adds protobuf toolchain for 2 fields | Reconsider only if usage history becomes an event log | Ruled out | Ruled out (reinvents DataStore, badly) |

### 3.5 Concrete shape

Key encoding matters because Preferences DataStore is a flat typed key space. Two workable options, in preference order:

1. **`stringSetPreferencesKey` / single serialized blob** — one key holding a serialized `Map<String, Entry>`. One key, one rewrite, trivially versioned; no risk of orphaned half-updates across two parallel key namespaces.
2. **Two parallel keys per package** — `floatPreferencesKey("score:$pkg")` and `longPreferencesKey("last:$pkg")`. Simpler to read, but a package's score and timestamp can drift apart and cleanup on uninstall touches two keys.

Option 1 is preferred, plus an `intPreferencesKey("schemaVersion")`. If the blob-in-preferences shape starts to feel like a workaround, that is the signal to switch to **Proto DataStore** (same store semantics, real schema) — *not* to Room.

---
## 4. Ranking algorithm

### 4.1 Primary precedent: AOSP's resolver ranker

AOSP ranks app targets in the share/resolver sheet with a hand-tuned model that uses **both** count and recency. From [`ResolverRankerServiceResolverComparator.java`](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/com/android/internal/app/ResolverRankerServiceResolverComparator.java):

- Class doc: "Ranks and compares packages based on usage stats and uses the `ResolverRankerService`."
- Windows and weights:
  ```java
  // One week
  private static final long USAGE_STATS_PERIOD = 1000 * 60 * 60 * 24 * 7;
  private static final long RECENCY_TIME_PERIOD = 1000 * 60 * 60 * 12;
  private static final float RECENCY_MULTIPLIER = 2.f;
  ```
- It queries a **one-week** window: `mSinceTime = mCurrentTime - USAGE_STATS_PERIOD;` then `queryAndAggregateUsageStats(mSinceTime, mCurrentTime)`.
- Features are recency, dwell time, launch count, chooser count:
  ```java
  final float recencyScore = (float) Math.max(pkStats.getLastTimeUsed() - recentSinceTime, 0);
  final float timeSpentScore = (float) pkStats.getTotalTimeInForeground();
  final float launchScore = (float) pkStats.mLaunchCount;
  ```
- Features are max-normalised, and recency is **squared and doubled** — a deliberate, steep recency bias:
  ```java
  final float recency = target.getRecencyScore() / mostRecencyScore;
  setFeatures(target, recency * recency * RECENCY_MULTIPLIER,
          target.getLaunchScore() / mostLaunchScore,
          target.getTimeSpentScore() / mostTimeSpentScore,
          target.getChooserScore() / mostChooserScore);
  ```
- The pre-trained combination, "according to a pre-trained Logistic Regression model":
  ```java
  float sum = 2.5543f * target.getLaunchScore() + 2.8412f * target.getTimeSpentScore() +
          0.269f * target.getRecencyScore() + 4.2222f * target.getChooserScore();
  target.setSelectProbability((float) (1.0 / (1.0 + Math.exp(1.6568f - sum))));
  ```

**Lessons Nornir should take:** (a) pure launch count is not what the platform itself does — count and recency are combined; (b) recency is weighted *non-linearly*; (c) a bounded window (one week) is used rather than lifetime totals; (d) the exact coefficients are not transferable, since they were fit against features we cannot read (`mLaunchCount`, chooser counts) — copy the shape, not the numbers.

### 4.2 The three candidate options

| Option | Behaviour | Problem |
| --- | --- | --- |
| **Launch-count threshold** ("show apps with ≥N launches") | Trivial | A hard cliff. Produces an unbounded, unordered list; an app used heavily last year outranks today's app forever. AOSP does not do this. |
| **Top-N by lifetime count** | Trivial, bounded, stable | **Ossifies.** Early launches are worth the same as today's. A new app can take weeks to break in, and an abandoned app never leaves. Recency is discarded entirely — the opposite of AOSP's steep recency bias. |
| **Recency-weighted exponential decay** | Bounded, self-ageing, single-number-per-app | Needs one tuning constant (the half-life) and a decision about when decay is applied. |

### 4.3 Recommended default: score-with-half-life

Store one `Float` score and one `Long` timestamp per package. Decay lazily — **on read/write, never on a timer**:

```
// on launch of pkg at time now:
score[pkg] = score[pkg] * 2^(-(now - last[pkg]) / HALF_LIFE) + 1.0
last[pkg]  = now

// when ranking at time now (read-only, does not mutate storage):
effective(pkg) = score[pkg] * 2^(-(now - last[pkg]) / HALF_LIFE)
```

This is mathematically equivalent to summing `2^(-age/T½)` over every past launch, but costs O(1) storage and O(1) work per launch — no event log, no background job, no `WorkManager`. It subsumes both rejected options: with `T½ → ∞` it degenerates to lifetime count, and ordering by `effective` then taking the first N *is* top-N.

**`HALF_LIFE = 14 days`.** Rationale, from the arithmetic of the formula:

- Steady-state score for a once-a-day app: `1 / (1 - 2^(-1/T½))` ≈ **10.6** at 7 days, **20.7** at 14 days, **43.8** at 30 days — all comfortably inside `Float` precision, no overflow or renormalisation needed.
- An app previously launched daily, then abandoned, drops below an app launched once every 5 days after ≈ **33 days** at a 14-day half-life. A 7-day half-life makes the list twitchy (one busy afternoon reshuffles the top row); 30 days makes it sluggish (a deleted habit lingers over a month).
- 14 days also sits comfortably beyond AOSP's 7-day query window, which we are not bound by since we own our history.

**Tie-breaks and display:** rank by `effective` descending, then `lastLaunchMillis` descending, then label collation. AOSP uses a collator for its final tie-break (`private final Collator mCollator;` in the same file), so a locale-aware label comparison is the right last resort.

### 4.4 Edge cases the plan must specify

- **Cold start / no history.** Every score is 0 and the ordering is arbitrary. Fall back to alphabetical, and consider seeding nothing at all (do **not** request usage access just to bootstrap — see §5).
- **Uninstalled packages.** `LauncherApps` registers for package changes ("Since the `PackageManager` will not deliver package broadcasts for other profiles, you can register for package changes here" — [`LauncherApps.java`](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/content/pm/LauncherApps.java)). Drop the entry on removal, otherwise the map grows forever.
- **Pinned/favourite apps** (`launcher-UI.md` §"Pin") must bypass ranking entirely — pinning is a user assertion, not a statistic.
- **Pruning.** Cap the store: drop entries whose `effective` falls below a floor (e.g. `< 0.01`) during the next write. Keeps the whole-file rewrite (§3.3) small.
- **Clock changes.** `now - last` can go negative if the user moves the clock backwards. Clamp `Δt` at 0 — the same defensive shape AOSP uses with `Math.max(pkStats.getLastTimeUsed() - recentSinceTime, 0)`.

---

## 5. Privacy and permission friction

### 5.1 `PACKAGE_USAGE_STATS` is a special (appop) permission, not a runtime one

- AOSP declaration:
  ```xml
  <!-- Allows an application to collect component usage
       statistics
       <p>Declaring the permission implies intention to use the API and the user of the
       device can grant permission through the Settings application.
       <p>Protection level: signature|privileged|development|appop|retailDemo -->
  <permission android:name="android.permission.PACKAGE_USAGE_STATS"
      android:protectionLevel="signature|privileged|development|appop|retailDemo" />
  ```
  — [`core/res/AndroidManifest.xml`](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/res/AndroidManifest.xml)
- The public reference repeats it: "Allows an application to collect component usage statistics — Declaring the permission implies intention to use the API and the user of the device can grant permission through the Settings application." — [`Manifest.permission`](https://developer.android.com/reference/android/Manifest.permission#PACKAGE_USAGE_STATS)
- `appop` means special permission, which means **no dialog**: "Special permissions correspond to particular app operations. ... The `Special app access` page in system settings contains a set of user-toggleable operations. ... The system assigns the `appop` protection level to special permissions." — [Permissions on Android](https://developer.android.com/guide/topics/permissions/overview)
- Stated flatly in the special-permission guide: "Request the special permission that your app requires ... This likely involves an intent to the corresponding page in system settings where the user can grant the permission. **Unlike runtime permissions, there is no permission dialog.**" And: "Apps that declare a special permission are shown in the `Special app access` page in system settings. To grant a special permission to the app, a user must navigate to this page: **Settings > Apps > Special app access**." — [Request special permissions](https://developer.android.com/training/permissions/requesting-special)
- That guide also carries a policy caution: "**Note:** Use special permissions only in specific use cases. **There may be policy implications to adding them in your app.**" — same page
- So `requestPermissions()` is not an option. The UX is: rationale screen → `Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)` → user finds Nornir in a system list → toggles "Permit usage access" → returns → app re-checks in `onResume()`. And the intent may not even resolve: "**Activity Action:** Show settings to control access to usage information. **In some cases, a matching Activity may not exist, so ensure you safeguard against this.**" — [`Settings.ACTION_USAGE_ACCESS_SETTINGS`](https://developer.android.com/reference/android/provider/Settings#ACTION_USAGE_ACCESS_SETTINGS) (API 21)
- Checking the grant is also non-obvious — it is an appop check, not `checkSelfPermission`. The relevant op is `OPSTR_GET_USAGE_STATS` = `"android:get_usage_stats"`, "Access to `UsageStatsManager`." — [`AppOpsManager`](https://developer.android.com/reference/android/app/AppOpsManager). The service confirms this dual appop-then-permission logic:
  ```java
  private boolean hasQueryPermission(String callingPackage) {
      final int callingUid = Binder.getCallingUid();
      if (callingUid == Process.SYSTEM_UID) { return true; }
      final int mode = mAppOps.noteOp(AppOpsManager.OP_GET_USAGE_STATS, callingUid, callingPackage);
      if (mode == AppOpsManager.MODE_DEFAULT) {
          // The default behavior here is to check if PackageManager has given the app permission.
          return getContext().checkCallingPermission(Manifest.permission.PACKAGE_USAGE_STATS)
                  == PackageManager.PERMISSION_GRANTED;
      }
      return mode == AppOpsManager.MODE_ALLOWED;
  }
  ```
  — [`UsageStatsService.java`](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/services/usage/java/com/android/server/usage/UsageStatsService.java)
- Failure is **silent**: unpermitted callers get `null` from the binder (see §2.3), which surfaces as an empty list/map — no `SecurityException` to catch, so a permissionless launcher would just show a mysteriously empty "frequent" row.

### 5.2 How the platform describes usage access to the user

This is the actual friction, in the OS's own words:

- "Usage access allows an app to **track what other apps you're using and how often**, as well as your carrier, language settings, and other details."
- Screen and toggle labels: `usage_access` = "Usage access", `usage_access_title` = "Apps with usage access", `permit_usage_access` = "Permit usage access".

— [Settings `res/values/strings.xml`](https://android.googlesource.com/platform/packages/apps/Settings/+/refs/heads/main/res/values/strings.xml)

A minimalist launcher asking for surveillance-flavoured access to *sort its own list* is a hard sell, and the grant is permanently visible to the user in a system list of apps that watch them.

### 5.3 Google Play consequences

- Play policy does **not** name `PACKAGE_USAGE_STATS` or "usage access" explicitly. Searching the three governing pages — [Data safety form](https://support.google.com/googleplay/android-developer/answer/10787469), [Permissions and APIs that Access Sensitive Information](https://support.google.com/googleplay/android-developer/answer/9888170), and [User Data](https://support.google.com/googleplay/android-developer/answer/9888076) — finds no occurrence of either term. The named Restricted Permissions are SMS/Call Log, Location, All Files Access, Package Visibility, Accessibility, Install Packages, Body Sensors, Health Connect, VPN, Exact Alarm, Full-Screen Intent. So there is no declaration form specifically for usage access.
- But the Data safety form has categories that a usage-stats read lands in squarely: "**App interactions** — Information about how a user interacts with the app..."; "**Installed apps** — Information about the apps installed on a user's device." — [Data safety](https://support.google.com/googleplay/android-developer/answer/10787469)
- And the prominent-disclosure obligation attaches by *behaviour*, not by permission name: "In cases where your app's access, collection, use, or sharing of personal and sensitive user data may not be within the reasonable expectation of the user ... **you must** ... provide an in-app disclosure of your data access, collection, use, and sharing", which "Must be displayed in the normal usage of the app and not require the user to navigate into a menu or settings" and "Cannot only be placed in a privacy policy or terms of service". — [User Data](https://support.google.com/googleplay/android-developer/answer/9888076)

### 5.4 The decisive asymmetry: local-only data needs no disclosure

- "**Not in scope for data collection** — The following use cases do not need to be disclosed as collected: **On-device access/processing:** User data accessed by your app that is only processed locally on the user's device and not sent off device does **not** need to be disclosed." — [Data safety](https://support.google.com/googleplay/android-developer/answer/10787469)

Self-tracking, kept on-device in DataStore and never transmitted, is therefore outside the collection-disclosure scope entirely. Nornir gets zero permission prompts, zero Settings round-trip, zero data-safety collection declaration, and nothing in "Apps with usage access".

### 5.5 Unrelated permission Nornir *does* still need to plan for

App enumeration is a separate matter and is **not** solved by self-tracking. Launcher3 declares `QUERY_ALL_PACKAGES` ([`AndroidManifest-common.xml`](https://android.googlesource.com/platform/packages/apps/Launcher3/+/refs/heads/main/AndroidManifest-common.xml)), and `launcher-ideas.md` already plans for it. Relevant primary facts:

- "In the rare cases where the `<queries>` element doesn't provide adequate package visibility, you can use the `QUERY_ALL_PACKAGES` permission. If you publish your app on Google Play, your app's use of this permission is subject to approval." — [Package visibility filtering](https://developer.android.com/training/package-visibility)
- "The `QUERY_ALL_PACKAGES` permission only takes effect when your app targets Android API level 30 or later on devices running Android 11 or later." Permitted uses "include **device search**, antivirus apps, file managers, and browsers", and require the Permissions Declaration Form in Play Console. — [Use of the broad package (App) visibility permission](https://support.google.com/googleplay/android-developer/answer/10158779)
- "The inventory of installed apps queried from a device are regarded as personal and sensitive user data... Apps that have a core purpose to **launch, search, or interoperate with other apps** on the device, may obtain scope-appropriate visibility..." — [Permissions and APIs that Access Sensitive Information](https://support.google.com/googleplay/android-developer/answer/9888170)
- A keyboard-driven launcher is a textbook fit for that permitted use, and its core purpose is documented in `launcher-ideas.md`. **This should be its own ticket** — it is a distinct decision from usage persistence, and this ticket does not resolve it.

---

## 6. Corrections to the ticket's premises

Worth recording, because the plan should not be written against the original assumptions:

1. **"`queryAndAggregateUsageStats` gives real launch counts"** — false for third-party apps. `getAppLaunchCount()` is `@SystemApi`/`@hide`; the public `UsageStats` surface has no count field at all (§2.2). Counts would have to be tallied from `queryEvents`, which needs the same permission and only reaches back over a bounded, capped history.
2. **"is that acceptable for a launcher"** — the more useful framing is that it is *insufficient*, not merely costly. It cannot deliver the primary signal, and AOSP's own launcher does not request it (§2.5).
3. **"DataStore (Preferences) vs Room vs SharedPreferences"** — SharedPreferences is now documented as against-recommendation by the Android team on its own reference page, so it is not a live option for new code (§3.1).

---

## 7. Source index

**AOSP — `platform/frameworks/base` @ `refs/heads/main`**
- [`core/java/android/app/usage/UsageStats.java`](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/app/usage/UsageStats.java) — `getAppLaunchCount()` is `@SystemApi`/`@hide`; `mLaunchCount` is `@UnsupportedAppUsage`
- [`core/java/android/app/usage/UsageStatsManager.java`](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/app/usage/UsageStatsManager.java) — class javadoc on permission + Settings grant; `queryAndAggregateUsageStats`; `queryUsageStats`; `queryEventsForSelf`
- [`core/api/current.txt`](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/api/current.txt) — public SDK surface for `UsageStats`, `UsageStatsManager`, `UsageEvents.Event`
- [`core/api/system-current.txt`](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/api/system-current.txt) — `getAppLaunchCount()`, `AppPredictionManager`, `AppPredictor`, `AppTarget` are system-only
- [`core/res/AndroidManifest.xml`](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/res/AndroidManifest.xml) — `PACKAGE_USAGE_STATS` protection level
- [`core/java/android/content/pm/LauncherApps.java`](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/content/pm/LauncherApps.java) — "mainly for use by launchers"; package-change callbacks
- [`core/java/com/android/internal/app/ResolverRankerServiceResolverComparator.java`](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/com/android/internal/app/ResolverRankerServiceResolverComparator.java) — recency/launch/dwell features, `RECENCY_MULTIPLIER`, logistic coefficients
- [`services/usage/.../UsageStatsService.java`](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/services/usage/java/com/android/server/usage/UsageStatsService.java) — `hasQueryPermission`, silent `null` for unpermitted callers
- [`services/usage/.../UsageStatsDatabase.java`](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/services/usage/java/com/android/server/usage/UsageStatsDatabase.java) — `MAX_FILES_PER_INTERVAL_TYPE` retention caps

**AOSP — other projects**
- [`Launcher3/AndroidManifest-common.xml`](https://android.googlesource.com/platform/packages/apps/Launcher3/+/refs/heads/main/AndroidManifest-common.xml) — no `PACKAGE_USAGE_STATS`; has `QUERY_ALL_PACKAGES`
- [`Launcher3/.../AppEventProducer.java`](https://android.googlesource.com/platform/packages/apps/Launcher3/+/refs/heads/main/quickstep/src/com/android/launcher3/model/AppEventProducer.java) — Launcher3 emits its own launch events
- [`Settings/res/values/strings.xml`](https://android.googlesource.com/platform/packages/apps/Settings/+/refs/heads/main/res/values/strings.xml) — `usage_access_description`, `permit_usage_access`
- [`PermissionController/res/xml/roles.xml`](https://android.googlesource.com/platform/packages/modules/Permission/+/refs/heads/main/PermissionController/res/xml/roles.xml) — `android.app.role.HOME` permission grants
- [`datastore-core/.../DataStoreImpl.kt`](https://android.googlesource.com/platform/frameworks/support/+/refs/heads/androidx-main/datastore/datastore-core/src/commonMain/kotlin/androidx/datastore/core/DataStoreImpl.kt) — `transformAndWrite` under `coordinator.lock`
- [`datastore-core-okio/.../OkioStorage.kt`](https://android.googlesource.com/platform/frameworks/support/+/refs/heads/androidx-main/datastore/datastore-core-okio/src/commonMain/kotlin/androidx/datastore/core/okio/OkioStorage.kt) — scratch file + `atomicMove`

**developer.android.com**
- [DataStore](https://developer.android.com/topic/libraries/architecture/datastore) · [SharedPreferences reference](https://developer.android.com/reference/android/content/SharedPreferences) · [SharedPreferences guide](https://developer.android.com/training/data-storage/shared-preferences) · [Room](https://developer.android.com/training/data-storage/room) · [Data and file storage overview](https://developer.android.com/guide/topics/data/data-storage)
- [Permissions overview](https://developer.android.com/guide/topics/permissions/overview) · [Request special permissions](https://developer.android.com/training/permissions/requesting-special) · [`Manifest.permission`](https://developer.android.com/reference/android/Manifest.permission#PACKAGE_USAGE_STATS) · [`Settings.ACTION_USAGE_ACCESS_SETTINGS`](https://developer.android.com/reference/android/provider/Settings#ACTION_USAGE_ACCESS_SETTINGS) · [`AppOpsManager`](https://developer.android.com/reference/android/app/AppOpsManager) · [Package visibility](https://developer.android.com/training/package-visibility)

**Google Play Console Help (policy)**
- [Data safety form](https://support.google.com/googleplay/android-developer/answer/10787469) · [Permissions and APIs that Access Sensitive Information](https://support.google.com/googleplay/android-developer/answer/9888170) · [User Data](https://support.google.com/googleplay/android-developer/answer/9888076) · [Broad package visibility](https://support.google.com/googleplay/android-developer/answer/10158779)
