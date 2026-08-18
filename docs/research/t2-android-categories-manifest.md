# T2 — Android app-category APIs, package visibility, SDK levels, and icon retrieval

- **Issue:** [#2 — Survey Android app-category APIs and manifest requirements for Nornir Launcher](https://github.com/Viking-Maker/Android-Launcher/issues/2)
- **Wayfinder map:** [#1](https://github.com/Viking-Maker/Android-Launcher/issues/1) (parent) · blocks #3, #4, #5, #7, #8, #9, #10
- **Context pointer:** `launcher-ideas.md` §1 "Development Environment & Minimum Requirements", §2 "Android Manifest Setup", §3 "Data Models & Package Manager Retrieval" (developer blueprint) and `launcher-UI.md`. Nornir Launcher = Android home launcher, Kotlin + Jetpack Compose, package `com.vm.nornir.launcher`. Plan-only ticket: no app code exists yet.
- **Status:** research complete — the decisions in §1 are ready to commit to the plan.
- **Sources:** primary only — AOSP source (`android.googlesource.com`), `developer.android.com` reference/guides, and Google Play Console Help policy pages. Every claim is tied to the source that owns it.
- **Date:** 2026-08-16

---

## 1. Decisions (commit these to the plan)

| # | Question | Answer | Single strongest reason |
| --- | --- | --- | --- |
| 1 | Is `ApplicationInfo.category` a usable category source? | **No — not as the primary source.** Treat it as an opportunistic *hint* only; design for `CATEGORY_UNDEFINED` being the common case. | The field defaults to `CATEGORY_UNDEFINED` and is only populated if the *target app's own developer* set `android:appCategory`, or the *installer* called `setApplicationCategoryHint()`. Nornir cannot set it for other apps. AOSP's own bundled apps don't set it (§2.4). |
| 2 | How does Nornir enumerate all installed apps on API 30+? | **`LauncherApps.getActivityList(null, user)`** as the primary enumeration API. Declare a `<queries>` `<intent>` for `MAIN`/`LAUNCHER` for `PackageManager` fallbacks. **Do not** rely on `QUERY_ALL_PACKAGES`. | `LauncherApps` is "mainly for use by launchers" and its `getLauncherActivities` binder call performs a **`MATCH_DIRECT_BOOT_AWARE|MATCH_DIRECT_BOOT_UNAWARE` query with `callingUid`** — and `<queries><intent>MAIN+LAUNCHER</intent></queries>` makes every launchable app visible to that uid, so a launcher never needs the restricted permission (§3.4, §3.5). |
| 3 | min/compile/target SDK | **minSdk 26, compileSdk 36, targetSdk 36.** Consider minSdk 30 if you want to drop pre-`<queries>` code paths. | Google Play: new apps and app updates must target **API 36 from 2026-08-31**; API 35 was already required from 2025-08-31 (§4.1). No category-detection reason exists to raise minSdk above 26 — the `CATEGORY_*` constants that matter all landed in **API 26** (§2.1). |
| 4 | Icon retrieval | **`LauncherActivityInfo.getIcon(density)`** per launchable activity (from the same `getActivityList` call), with `getBadgedIcon(density)` when you must show work-profile badging. Render the returned `AdaptiveIconDrawable` yourself with `getIconMask()` / `getForeground()` / `getBackground()`. | It's the same object you already hold from enumeration (no second `PackageManager` round-trip), it is per-*activity* (correct for multi-launcher-activity apps), and it is documented to obey the same size caps as `getApplicationIcon` (§5.1, §5.3). |

### 1.1 Direct corrections to the blueprint

`launcher-ideas.md` contains three claims that this research contradicts:

1. **"Minimum SDK: API 26 (Android 8.0) — *Required for category detection (`ApplicationInfo.category`)*"** — minSdk 26 is correct *as a number*, but the stated reason is a weak justification: category detection via `ApplicationInfo.category` returns `CATEGORY_UNDEFINED` for most apps in practice, so it cannot be the load-bearing reason for the floor (§2.3, §2.4). Keep 26; change the rationale to Compose/AndroidX + adaptive icons (`AdaptiveIconDrawable` is API 26, §5.3).
2. **`<uses-permission android:name="android.permission.QUERY_ALL_PACKAGES" />` with the comment "(Required for Android 11+)"** — factually wrong for a launcher, and a Play-policy liability. It is *not* required; a `<queries>` declaration plus `LauncherApps` covers the home-launcher use case, and Play restricts the permission to apps whose "core purpose [is] to search for all apps on the device" and requires a Permissions Declaration Form (§3.6, §3.7).
3. **`AppCategory` enum with `AUDIO, CHAT, CODE, EDUCATION, GAMES, GRAPHICS, SYSTEM, WEB, OTHER`** — this is a Linux `.desktop`/freedesktop-style taxonomy, not Android's. Android's real set is 9 defined values + undefined, and `CHAT`, `CODE`, `EDUCATION`, `GRAPHICS`, `WEB` do **not** exist (§2.1). Any mapping layer must be Nornir's own invention on top of the real set.

---

## 2. `ApplicationInfo.category`

### 2.1 The complete set of `CATEGORY_*` values

The `@IntDef` in AOSP is the authoritative list. There are **9 defined categories plus `CATEGORY_UNDEFINED`** — ten constants total.

| Constant | Value | `android:appCategory` string | Added in | Javadoc |
| --- | --- | --- | --- | --- |
| `CATEGORY_UNDEFINED` | `-1` | *(n/a)* | API 26 | "Value when category is undefined." |
| `CATEGORY_GAME` | `0` | `game` | API 26 | "apps which are primarily games" |
| `CATEGORY_AUDIO` | `1` | `audio` | API 26 | "apps which primarily work with audio or music, such as music players" |
| `CATEGORY_VIDEO` | `2` | `video` | API 26 | "apps which primarily work with video or movies, such as streaming video apps" |
| `CATEGORY_IMAGE` | `3` | `image` | API 26 | "apps which primarily work with images or photos, such as camera or gallery apps" |
| `CATEGORY_SOCIAL` | `4` | `social` | API 26 | "apps which are primarily social apps, such as messaging, communication, email, or social network apps" |
| `CATEGORY_NEWS` | `5` | `news` | API 26 | "apps which are primarily news apps, such as newspapers, magazines, or sports apps" |
| `CATEGORY_MAPS` | `6` | `maps` | API 26 | "apps which are primarily maps apps, such as navigation apps" |
| `CATEGORY_PRODUCTIVITY` | `7` | `productivity` | API 26 | "apps which are primarily productivity apps, such as cloud storage or workplace apps" |
| `CATEGORY_ACCESSIBILITY` | `8` | `accessibility` | **API 31** | "apps which are primarily accessibility apps, such as screen-readers" |

- Constant values, javadoc wording, and the `@IntDef` membership are from AOSP [`ApplicationInfo.java`](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/content/pm/ApplicationInfo.java).
- The "Added in API level" annotations are from the reference pages: [`CATEGORY_UNDEFINED`/`CATEGORY_GAME`/… "Added in API level 26"](https://developer.android.com/reference/android/content/pm/ApplicationInfo#CATEGORY_UNDEFINED) and [`CATEGORY_ACCESSIBILITY` "Added in API level 31"](https://developer.android.com/reference/android/content/pm/ApplicationInfo#CATEGORY_ACCESSIBILITY).
- The manifest string ↔ integer mapping is from the [`android:appCategory` attribute reference](https://developer.android.com/reference/android/R.attr#appCategory), which tabulates `accessibility 8 / audio 1 / game 0 / image 3 / maps 6 / news 5 / productivity 7 / social 4 / video 2`.

**Planning consequence:** there is no `CHAT`, `CODE`, `EDUCATION`, `GRAPHICS`, `WEB`, `SYSTEM`, `FAVORITES`, or `OTHER` category in Android. If Nornir wants those buckets they are Nornir's own domain concept and need their own derivation rule, not a platform lookup.

### 2.2 On which API levels is it populated

- The field itself exists from **API 26**: [`public int category` — "Added in API level 26"](https://developer.android.com/reference/android/content/pm/ApplicationInfo#category). Below 26 the field does not exist at all, so `minSdk 26` is the floor for even *reading* it.
- `CATEGORY_ACCESSIBILITY` (value `8`) only exists from **API 31** ([reference](https://developer.android.com/reference/android/content/pm/ApplicationInfo#CATEGORY_ACCESSIBILITY)). On API 26–30 devices a package that declares `android:appCategory="accessibility"` still parses to the raw integer `8`, but there is no compile-time constant to compare against on those levels, so Nornir must either hardcode `8` or gate on `Build.VERSION.SDK_INT >= 31`.
- Availability of the *field* is not the same as availability of a *value*. See §2.3.

### 2.3 Why it is `CATEGORY_UNDEFINED` for the majority — the mechanism

There are exactly **two** ways the field ever becomes non-`UNDEFINED`, and a third-party launcher controls neither:

1. **The target app's own manifest.** The reference for the field: *"Set from the `R.attr.appCategory` attribute in the manifest."* ([source](https://developer.android.com/reference/android/content/pm/ApplicationInfo#category)). AOSP's manifest parser confirms the default: `ParsingPackageUtils.parseBaseApplication()` does
   ```java
   .setCategory(anInt(ApplicationInfo.CATEGORY_UNDEFINED,
           R.styleable.AndroidManifestApplication_appCategory, sa))
   ```
   i.e. **`CATEGORY_UNDEFINED` is the literal parse default** when the attribute is absent ([`ParsingPackageUtils.java`](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/com/android/internal/pm/pkg/parsing/ParsingPackageUtils.java)). The field declaration itself is `public @Category int category = CATEGORY_UNDEFINED;` ([`ApplicationInfo.java`](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/content/pm/ApplicationInfo.java)).
2. **An installer hint.** *"If the manifest doesn't define a category, this value may have been provided by the installer via `PackageManager.setApplicationCategoryHint(String, int)`"* ([source](https://developer.android.com/reference/android/content/pm/ApplicationInfo#category)). That call is gated: *"This hint can only be set by the app which installed this package, as determined by `getInstallerPackageName(String)`"* ([`setApplicationCategoryHint` reference](https://developer.android.com/reference/android/content/pm/PackageManager#setApplicationCategoryHint(java.lang.String,%20int))). AOSP enforces it server-side in `PackageManagerService`:
   ```java
   if (!Objects.equals(callerPackageName,
           packageState.getInstallSource().mInstallerPackageName)) {
       throw new IllegalArgumentException("Calling package " + callerPackageName
               + " is not installer for " + packageName);
   }
   ```
   ([`PackageManagerService.java`](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/services/core/java/com/android/server/pm/PackageManagerService.java)). **A launcher is never the installer**, so this path is closed to Nornir and it also cannot be relied upon to have happened.

Also note the *intent* stated in the javadoc — the field is not designed as a launcher-facing taxonomy at all: *"Categories are used to cluster multiple apps together into meaningful groups, such as when summarizing battery, network, or disk usage. **Apps should only define this value when they fit well into one of the specific categories.**"* ([`ApplicationInfo.java`](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/content/pm/ApplicationInfo.java), emphasis added). The platform explicitly tells developers *not* to set it unless it's a good fit, which structurally guarantees sparse coverage.

Corroborating signal: `ApplicationInfo.getCategoryTitle(Context, int)` returns *"a concise, localized title for the given `ApplicationInfo.category` value, or **`null` for unknown values such as `CATEGORY_UNDEFINED`**"* ([reference](https://developer.android.com/reference/android/content/pm/ApplicationInfo#getCategoryTitle(android.content.Context,%20int))). The platform's own display helper has no label for the majority case — Nornir must supply its own fallback bucket name.

### 2.4 Empirical check: AOSP's own bundled apps don't set it

To sanity-check "does this actually get set in practice", the `AndroidManifest.xml` of every readily-available AOSP bundled app was searched for `android:appCategory`:

| AOSP app repo | declares `android:appCategory`? |
| --- | --- |
| [`packages/apps/Launcher3`](https://android.googlesource.com/platform/packages/apps/Launcher3/+/refs/heads/main/AndroidManifest.xml) | no |
| [`packages/apps/Settings`](https://android.googlesource.com/platform/packages/apps/Settings/+/refs/heads/main/AndroidManifest.xml) | no |
| [`packages/apps/Contacts`](https://android.googlesource.com/platform/packages/apps/Contacts/+/refs/heads/main/AndroidManifest.xml) | no |
| [`packages/apps/Camera2`](https://android.googlesource.com/platform/packages/apps/Camera2/+/refs/heads/main/AndroidManifest.xml) | no |
| [`packages/apps/DeskClock`](https://android.googlesource.com/platform/packages/apps/DeskClock/+/refs/heads/main/AndroidManifest.xml) | no |
| [`packages/apps/Calendar`](https://android.googlesource.com/platform/packages/apps/Calendar/+/refs/heads/main/AndroidManifest.xml) | no |
| [`packages/apps/Messaging`](https://android.googlesource.com/platform/packages/apps/Messaging/+/refs/heads/main/AndroidManifest.xml) | no |
| [`packages/apps/Gallery2`](https://android.googlesource.com/platform/packages/apps/Gallery2/+/refs/heads/main/AndroidManifest.xml) | no |
| [`packages/apps/Music`](https://android.googlesource.com/platform/packages/apps/Music/+/refs/heads/main/AndroidManifest.xml) | no |
| [`packages/apps/Dialer`](https://android.googlesource.com/platform/packages/apps/Dialer/+/refs/heads/main/AndroidManifest.xml) | no |
| [`packages/apps/Browser2`](https://android.googlesource.com/platform/packages/apps/Browser2/+/refs/heads/main/AndroidManifest.xml) | no |

**0 of 11** AOSP bundled apps set it — including apps that are textbook fits (`Music` → `audio`, `Gallery2`/`Camera2` → `image`, `Messaging` → `social`, `Calendar` → `productivity`). Google's own first-party AOSP apps therefore all report `CATEGORY_UNDEFINED`. This is direct primary-source evidence that `ApplicationInfo.category` cannot carry a category-based UI.

> **Caveat on scope:** this is evidence about AOSP-bundled apps, not a statistical measurement across a real user's Play-installed app set. The mechanism in §2.3 is the load-bearing argument; §2.4 is a corroborating spot-check. Nornir should still log an *actual* distribution once there is a running app (a one-off `getInstalledApplications()` histogram of `category`), and the plan should not depend on the outcome either way.

### 2.5 Recommended design for Nornir

- Model category as `NornirCategory` — Nornir's own sealed type — **not** a passthrough of the platform integer.
- Derivation order per app: (a) user's explicit assignment if any; (b) `ApplicationInfo.category` if `!= CATEGORY_UNDEFINED`, mapped into `NornirCategory`; (c) fallback bucket. Do not build UI that assumes (b) usually fires.
- Do not use the deprecated `FLAG_IS_GAME` bit as a game signal — it is [*"Added in API level 21, Deprecated in API level 26"*](https://developer.android.com/reference/android/content/pm/ApplicationInfo#FLAG_IS_GAME), superseded by `CATEGORY_GAME`.

---

## 3. Package visibility and `QUERY_ALL_PACKAGES` on API 30+

### 3.1 What actually changed in Android 11

> "When an app targets Android 11 (API level 30) or higher and queries for information about the other apps that are installed on a device, the system filters this information by default. This filtering behavior means that your app can't detect all the apps installed on a device…"
> — [Package visibility filtering on Android](https://developer.android.com/training/package-visibility)

Explicitly affected APIs, per the same page: *"the results returned by methods that give information about other apps, such as `queryIntentActivities()`, `getPackageInfo()`, and `getInstalledApplications()`"*.

This is a **targetSdk-gated** behaviour change, not a device-version change. AOSP wires it to a compat change id:

```java
@ChangeId
@EnabledSince(targetSdkVersion = Build.VERSION_CODES.R)
public static final long FILTER_APPLICATION_QUERY = 135549675L;
```
— [`PackageManager.java`](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/content/pm/PackageManager.java)

Confirmed from the other direction: *"if your app targets Android 10 (API level 29) or lower, **all** apps are visible to your app automatically"* ([Know which packages are visible automatically](https://developer.android.com/training/package-visibility/automatic)). Since Nornir must target 36 (§4), filtering **will** apply.

### 3.2 There is NO launcher-specific automatic allowance

The complete list of what is visible without declaration ([Know which packages are visible automatically](https://developer.android.com/training/package-visibility/automatic)) is:

- Your own app.
- Certain system packages that implement core Android functionality.
- The app that installed your app.
- Any app that launches an activity in your app via `startActivityForResult()`.
- Any app that starts or binds to a service in your app.
- Any app that accesses a content provider in your app.
- Any app whose content provider your app holds URI permissions for.
- Any app that receives input from your app — *"only when your app provides input as an input method editor"*.

**"Is a home launcher" is not on that list.** Being the HOME role holder does not grant blanket package visibility.

Confirmed against the role definition: AOSP's `roles.xml` defines `android.app.role.HOME` with only three associated permissions — `READ_HOME_APP_SEARCH_DATA` (minSdk 33), `ALLOW_SLIPPERY_TOUCHES` (minSdk 33 / optional 30), and `RECEIVE_SENSITIVE_NOTIFICATIONS` (minSdk 35):

```xml
<role name="android.app.role.HOME" behavior="HomeRoleBehavior" ... >
    <required-components>
        <activity><intent-filter>
            <action name="android.intent.action.MAIN" />
            <category name="android.intent.category.HOME" />
        </intent-filter></activity>
    </required-components>
    ...
    <permissions>
        <permission name="android.permission.READ_HOME_APP_SEARCH_DATA" minSdkVersion="33" />
        <permission name="android.permission.ALLOW_SLIPPERY_TOUCHES" minSdkVersion="33" optionalMinSdkVersion="30" />
        <permission name="android.permission.RECEIVE_SENSITIVE_NOTIFICATIONS" minSdkVersion="35" />
    </permissions>
</role>
```
— [`packages/modules/Permission/PermissionController/res/xml/roles.xml`](https://android.googlesource.com/platform/packages/modules/Permission/+/refs/heads/main/PermissionController/res/xml/roles.xml)

**`QUERY_ALL_PACKAGES` does not appear anywhere in `roles.xml`** — verified by full-text search of that file. Becoming the default home app grants Nornir nothing with respect to package visibility.

Note also from the same block that the HOME role's *required component* is exactly `action MAIN` + `category HOME` — which validates the blueprint's intent filter shape (§3.8).

### 3.3 The permission itself

```xml
<!-- Allows query of any normal app on the device, regardless of manifest declarations.
    <p>Protection level: normal -->
<permission android:name="android.permission.QUERY_ALL_PACKAGES"
            android:label="@string/permlab_queryAllPackages"
            android:description="@string/permdesc_queryAllPackages"
            android:protectionLevel="normal" />
```
— [`core/res/AndroidManifest.xml`](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/res/AndroidManifest.xml)

- Matches the reference: [`QUERY_ALL_PACKAGES` — "Added in API level 30", "Allows query of any normal app on the device, regardless of manifest declarations", "Protection level: normal"](https://developer.android.com/reference/android/Manifest.permission#QUERY_ALL_PACKAGES).
- **`normal` protection level** means it is install-time granted by the *OS* — there is no runtime prompt and it cannot be denied by the user. The gate is **Google Play policy**, not the platform (§3.6).
- AOSP honours it as a blanket bypass in the filter: `AppsFilterBase.shouldFilterApplicationInternal()` short-circuits with `requestsQueryAllPackages(callingPkgSetting.getPkg())` returning `false` (= not filtered), where `AppsFilterUtils.requestsQueryAllPackages()` is simply `pkg.getRequestedPermissions().contains(Manifest.permission.QUERY_ALL_PACKAGES)` — [`AppsFilterBase.java`](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/services/core/java/com/android/server/pm/AppsFilterBase.java), [`AppsFilterUtils.java`](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/services/core/java/com/android/server/pm/AppsFilterUtils.java).

### 3.4 The launcher-correct alternative: `LauncherApps`

`android.content.pm.LauncherApps` (API 21) is the platform's launcher API:

> "Class for retrieving a list of launchable activities for the current user and any associated managed profiles that are visible to the current user, which can be retrieved with `getProfiles()`. **This is mainly for use by launchers.**"
> — [`LauncherApps` reference](https://developer.android.com/reference/android/content/pm/LauncherApps)

`getActivityList(String packageName, UserHandle user)` (API 21) — pass `null` for `packageName` to get every app:

> "Retrieves a list of activities that specify `Intent.ACTION_MAIN` and `Intent.CATEGORY_LAUNCHER`, across all apps, for a specified user. **If an app doesn't have any activities that specify `ACTION_MAIN` or `CATEGORY_LAUNCHER`, the system adds a synthesized activity to the list.** This synthesized activity represents the app's details page within system settings."
> — [`getActivityList` reference](https://developer.android.com/reference/android/content/pm/LauncherApps#getActivityList(java.lang.String,%20android.os.UserHandle))

The same page documents the guarantee and its exceptions:

> "As of Android Q, at least one of the app's activities or synthesized activities appears in the returned list unless the app satisfies at least one of the following conditions: The app is a system app. The app doesn't request any permissions. The app doesn't have a launcher activity that is enabled by default."

Two properties matter for Nornir:
- It returns **per-launchable-activity** entries, not per-package. Apps with multiple launcher activities (e.g. a dual-purpose app) correctly yield multiple entries — which the blueprint's package-keyed `AppItem` model does not account for.
- It is **multi-profile aware** via `UserHandle` + `getProfiles()`. A launcher that enumerates only the primary user silently loses all work-profile apps.

**Permission check:** `getActivityList` does **not** require the shortcut-host permission. In `LauncherAppsService`, `getLauncherActivities()` calls `queryActivitiesForUser()`, whose only gate is `canAccessProfile(...)`:

```java
private ParceledListSlice<LauncherActivityInfoInternal> queryActivitiesForUser(
        String callingPackage, Intent intent, UserHandle user) {
    if (!canAccessProfile(user.getIdentifier(), "Cannot retrieve activities")) {
        return null;
    }
    ...
    return new ParceledListSlice<>(queryIntentLauncherActivities(intent, callingUid, user));
}
```
— [`LauncherAppsService.java`](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/services/core/java/com/android/server/pm/LauncherAppsService.java)

By contrast the **shortcut** APIs go through `ensureShortcutPermission()` → `ShortcutService.hasShortcutHostPermission()` → `hasShortcutHostPermissionInner()`, which resolves to `defaultLauncher.equals(packageName)` — i.e. *those* APIs require being the **actual default launcher** ([`ShortcutService.java`](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/services/core/java/com/android/server/pm/ShortcutService.java)). **Planning consequence:** app enumeration works before the user sets Nornir as default; shortcuts do not.

### 3.5 Why `<queries>` + `LauncherApps` is sufficient (the mechanism)

`LauncherApps` is not a visibility bypass — it filters against the **calling** uid:

```java
private List<LauncherActivityInfoInternal> queryIntentLauncherActivities(
        Intent intent, int callingUid, UserHandle user) {
    final List<ResolveInfo> apps = mPackageManagerInternal.queryIntentActivities(intent,
            intent.resolveTypeIfNeeded(mContext.getContentResolver()),
            PackageManager.MATCH_DIRECT_BOOT_AWARE
                    | PackageManager.MATCH_DIRECT_BOOT_UNAWARE,
            callingUid, user.getIdentifier());
```
— [`LauncherAppsService.java`](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/services/core/java/com/android/server/pm/LauncherAppsService.java); `PackageManagerInternal.queryIntentActivities`'s `filterCallingUid` is documented as *"The results will be filtered in the context of this UID instead of the calling UID"* ([`PackageManagerInternal.java`](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/services/core/java/android/content/pm/PackageManagerInternal.java)).

So the results are still subject to `AppsFilter`. The relevant escape hatch is the `<queries><intent>` declaration, which AOSP evaluates as an intent-filter match against the target's **exported components**:

```java
public static boolean canQueryViaComponents(AndroidPackage querying,
        AndroidPackage potentialTarget, WatchedArraySet<String> protectedBroadcasts) {
    if (!querying.getQueriesIntents().isEmpty()) {
        for (Intent intent : querying.getQueriesIntents()) {
            if (matchesPackage(intent, potentialTarget, protectedBroadcasts)) { return true; }
        }
    }
    ...
}
```
`matchesPackage` → `matchesAnyComponents` (skips `!component.isExported()`) → `matchesAnyFilter` → `matchesIntentFilter`, which is a real `IntentFilter.match(action, type, scheme, data, categories, ...)` including **categories** — [`AppsFilterUtils.java`](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/services/core/java/com/android/server/pm/AppsFilterUtils.java).

Since a launchable app is *by definition* one with an exported activity whose intent filter has `action MAIN` + `category LAUNCHER`, a `<queries>` declaration of exactly that signature matches **every launchable app on the device** — which is precisely and only the set a launcher needs. That is the whole argument for not needing `QUERY_ALL_PACKAGES`.

`<category>` is valid inside `<queries><intent>`: AOSP's `ParsedIntentInfoUtils` handles `case "category":` → `intentFilter.addCategory(value)` when parsing intent trees ([`ParsedIntentInfoUtils.java`](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/com/android/internal/pm/pkg/component/ParsedIntentInfoUtils.java)), and `parseQueries` routes `<intent>` children through that same parser ([`ParsingPackageUtils.java`](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/com/android/internal/pm/pkg/parsing/ParsingPackageUtils.java)).

**Documented `<queries><intent>` restrictions** that constrain the declaration ([Declare package visibility needs](https://developer.android.com/training/package-visibility/declaring)):
- "You must include exactly one `<action>` element."
- No `path`, `pathPrefix`, `pathPattern`, or `port` attributes in `<data>`; the system treats each as the wildcard `*`.
- No `mimeGroup` attribute.
- At most one each of `mimeType`, `scheme`, `host` across all `<data>` elements of one `<intent>`.

AOSP additionally enforces at parse time: `"intent tags must contain either an action or data."`, `"intent tag may have at most one action."`, `"intent tag may have at most one data type."` ([`ParsingPackageUtils.java`](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/com/android/internal/pm/pkg/parsing/ParsingPackageUtils.java)). A `MAIN`+`LAUNCHER` declaration has one action and no data, so it is well within these limits.

### 3.6 Google Play policy on `QUERY_ALL_PACKAGES` — why not to ship it

From [Use of the broad package (App) visibility (`QUERY_ALL_PACKAGES`) permission](https://support.google.com/googleplay/android-developer/answer/10158779) (Play Console Help):

- Scope: *"The `QUERY_ALL_PACKAGES` permission only takes effect when your app targets Android API level 30 or later on devices running Android 11 or later."*
- Bar: *"To use this permission, your app must fall within permitted uses below, and have a **core purpose to search for all apps on the device**. You must be able to adequately justify why a less intrusive method of app visibility will not sufficiently enable your app's policy-compliant user-facing core functionality."*
- Definition of core: *"Core functionality is defined as the main purpose of the app. **Without this core ability to search for all apps on the device, the app is 'broken' or becomes unusable.** The core functionality … must all be prominently documented and promoted in the app's description."*
- Permitted uses (verbatim list): *"Permitted uses include **device search**, antivirus apps, file managers, and browsers."*
- Invalid uses include: *"**When the required task can be done with a less broad app-visibility method.**"*
- Process: *"If your app meets the policy requirements … you will be required to declare this and any other high-risk permissions using the **Permissions Declaration Form** in Play Console."* And: *"Apps that fail to meet the policy requirements or do not submit the Permissions Declaration Form may be removed from Google Play."*

**Assessment for Nornir:** a keyboard-driven launcher whose core loop is "type to find and launch an app" arguably resembles the permitted "device search" case, and Nornir *is* genuinely broken without full app enumeration. But the "less broad app-visibility method" clause is decisive against declaring it: §3.5 proves `<queries>` + `LauncherApps` fully covers the launchable-app set. Declaring `QUERY_ALL_PACKAGES` would therefore fail the *"less intrusive method will not suffice"* test and hand the review process a reason to reject.

**Also note:** the permission is only *needed* if Nornir wants apps with **no** launcher activity at all — which a launcher, by definition, cannot launch anyway. There is no functional gap.

### 3.7 What Launcher3 does, and why it isn't a precedent

AOSP's own launcher **does** declare it:

```xml
<uses-permission android:name="android.permission.QUERY_ALL_PACKAGES" />
```
— [`Launcher3/AndroidManifest-common.xml`](https://android.googlesource.com/platform/packages/apps/Launcher3/+/refs/heads/main/AndroidManifest-common.xml)

This is **not** a precedent Nornir can follow: Launcher3 ships as a preinstalled system app, is not distributed through Google Play, and so is not subject to the Play Console policy in §3.6. Its `<uses-sdk>` is also system-app-shaped: `<uses-sdk android:targetSdkVersion="33" android:minSdkVersion="30"/>` ([`Launcher3/AndroidManifest.xml`](https://android.googlesource.com/platform/packages/apps/Launcher3/+/refs/heads/main/AndroidManifest.xml)) — a targetSdk that would already be rejected for a new Play submission (§4.1). Read Launcher3 for API usage patterns, not for distribution-policy guidance.

Notably, Launcher3 declares **no `<queries>` element at all** — verified by full-text search of both its manifests — because `QUERY_ALL_PACKAGES` makes it unnecessary. So Launcher3 offers no reference `<queries>` block to copy; §3.8 is derived from the platform semantics in §3.5.

### 3.8 Recommended manifest for Nornir

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <!-- Package visibility (Android 11 / API 30+): make every launchable app
         visible. This matches any app with an exported activity whose filter
         declares MAIN + LAUNCHER, which is exactly the set a launcher needs.
         Deliberately NOT using QUERY_ALL_PACKAGES: Play policy rejects it when
         a less broad method suffices. -->
    <queries>
        <intent>
            <action android:name="android.intent.action.MAIN" />
            <category android:name="android.intent.category.LAUNCHER" />
        </intent>
    </queries>

    <application
        android:label="Nornir Launcher"
        android:icon="@mipmap/ic_launcher"
        android:roundIcon="@mipmap/ic_launcher_round"
        android:supportsRtl="true"
        android:theme="@style/Theme.NornirLauncher">

        <activity
            android:name=".MainActivity"
            android:exported="true"
            android:launchMode="singleTask"
            android:clearTaskOnLaunch="true"
            android:stateNotNeeded="true"
            android:windowSoftInputMode="adjustResize">
            <!-- HOME role required-component signature, per AOSP roles.xml -->
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.HOME" />
                <category android:name="android.intent.category.DEFAULT" />
            </intent-filter>
        </activity>

    </application>
</manifest>
```

Rationale per element:
- `<queries><intent>MAIN+LAUNCHER</intent></queries>` — §3.5.
- `MAIN` + `HOME` + `DEFAULT` intent filter — `MAIN`+`HOME` is the HOME role's `<required-components>` signature in AOSP `roles.xml` (§3.2); `DEFAULT` matches what Launcher3 declares ([`Launcher3/AndroidManifest.xml`](https://android.googlesource.com/platform/packages/apps/Launcher3/+/refs/heads/main/AndroidManifest.xml)).
- `android:exported="true"` — required for any activity with an intent filter on API 31+.
- `launchMode="singleTask"`, `clearTaskOnLaunch="true"`, `stateNotNeeded="true"` — all three match Launcher3's own `Launcher` activity declaration ([source](https://android.googlesource.com/platform/packages/apps/Launcher3/+/refs/heads/main/AndroidManifest.xml)); keep them, the blueprint got this right.
- `windowSoftInputMode` — Launcher3 uses `adjustPan`; for a keyboard-driven Compose search UI `adjustResize` is the better fit. Flagged as a Nornir-specific deviation, not a platform requirement.
- **Dropped** from the blueprint: `QUERY_ALL_PACKAGES` (§3.6), and `android:allowBackup="true"` is left to the T3 persistence decision rather than asserted here.

### 3.9 Testing note

Per [Package visibility filtering on Android](https://developer.android.com/training/package-visibility) there is a dedicated testing page for visibility behaviour. To see the automatic-visibility set on a given device, [Know which packages are visible automatically](https://developer.android.com/training/package-visibility/automatic) documents:

```
adb shell dumpsys package queries
```
> "In the command output, find the `forceQueryable` section. This section includes the list of packages that the device has made visible to your app automatically."

This is the concrete way to verify Nornir's enumeration is complete on a real device rather than trusting the emulator.

---

## 4. min / compile / target SDK

### 4.1 `targetSdk` — Google Play forces the answer

From [Target API level requirements for Google Play apps](https://support.google.com/googleplay/android-developer/answer/11926878) (Play Console Help):

| Android OS version (API level) | New apps required to target from | App updates required to target from |
| --- | --- | --- |
| **Android 16 (API level 36)** | **2026-08-31** | **2026-08-31** |
| Android 15 (API level 35) | 2025-08-31 | 2025-08-31 |

Verbatim from the page header:

> "**Starting August 31, 2026:** New apps and app updates must target Android 16 (API level 36) or higher to be submitted to Google Play; except for Wear OS, and Android Automotive OS apps, which must target Android 15 (API level 35) or higher…"
> "Existing apps must target Android 15 (API level 35) or higher to remain available to new users on devices running Android OS higher than your app's target API level."

An extension is possible but only buys ~2 months: *"You will be able to request an extension to November 1, 2026."* (footnoted as *"Developers will be able to request an extension to November 1, 2026."*).

**Decision: `targetSdk = 36`.** For a project starting now and shipping in the 2025–2026 window, 35 is already the floor and 36 is required from 2026-08-31. Starting at 36 avoids a forced migration mid-project. Note the *only* documented exception is *"Permanently private apps that are restricted to users in a specific organization and intended for internal distribution only"* — which does not describe Nornir.

### 4.2 `compileSdk` — match the target

Latest stable platform per [SDK Platform release notes](https://developer.android.com/tools/releases/platforms):

> "**Android 16 (API level 36)** … Revision 1 (March 2025) — Released to the stable channel (no longer in preview) when Android 16 reached the Platform Stability milestone."

API 36 is stable and shipped (36 → Android 16, 35 → Android 15, 34 → Android 14 per the same page). **Decision: `compileSdk = 36`.**

The blueprint's "Compile SDK: API 34 or API 35" is stale — it predates API 36 stability and is below what `targetSdk 36` requires.

### 4.3 `minSdk` — keep 26, but for the right reason

- **Jetpack Compose floor is 21**, not 26: the official Compose setup guide instructs *"In the Minimum API level dropdown menu, select **API level 21** or higher"* ([Quick start / Compose setup](https://developer.android.com/develop/ui/compose/setup)). So Compose does **not** force 26.
- **`ApplicationInfo.category` is API 26** ([reference](https://developer.android.com/reference/android/content/pm/ApplicationInfo#category)) — this is the blueprint's stated reason, and it is *technically* the correct API level, but §2.3–2.4 show the field is near-useless in practice, so it is a weak load-bearing justification for a floor.
- **`AdaptiveIconDrawable` is API 26** ([reference](https://developer.android.com/reference/android/graphics/drawable/AdaptiveIconDrawable) — "Added in API level 26"). For a launcher that renders app icons, this is the *stronger* reason to sit at 26: below 26 there is no adaptive-icon class to detect or mask at all, and icon rendering is core launcher functionality.

**Decision: `minSdk = 26`** — same number as the blueprint, different and better-founded rationale (adaptive icons + AndroidX comfort, not category detection).

#### Is there a category-detection reason to raise minSdk above 26?

**No.** Two candidate arguments, both rejected:
- *"Raise to 31 for `CATEGORY_ACCESSIBILITY`"* — this single extra constant (§2.2) is not worth 5 API levels of device reach, and it can be handled with the literal `8` or a `SDK_INT >= 31` guard.
- *"Raise to 30 because visibility filtering starts there"* — filtering is gated on **targetSdk**, not device SDK (§3.1). The `<queries>` element is simply ignored by older devices (which grant full visibility anyway), so a single manifest works across 26–36 with no branching. `minSdk 30` would only be justified as a *code-simplicity* choice (dropping pre-`<queries>` reasoning), not a correctness one.

**Optional deviation:** if the project prefers a narrower support matrix, `minSdk = 30` aligns with what AOSP Launcher3 itself uses (`android:minSdkVersion="30"`, [source](https://android.googlesource.com/platform/packages/apps/Launcher3/+/refs/heads/main/AndroidManifest.xml)) and eliminates all pre-`<queries>` reasoning. This is a product decision about device reach, not a technical constraint.

### 4.4 Summary block for `build.gradle.kts`

```kotlin
android {
    namespace = "com.vm.nornir.launcher"
    compileSdk = 36          // Android 16, stable since March 2025

    defaultConfig {
        minSdk = 26          // AdaptiveIconDrawable (API 26); Compose only needs 21
        targetSdk = 36       // Play requires 36 for new apps from 2026-08-31
    }
}
```

JDK: the blueprint's "OpenJDK 17 or JDK 21" is not contradicted by anything found here and is left as-is.

**Open item deliberately not resolved here:** if Nornir ever ships native code, [Support 16 KB page sizes](https://developer.android.com/guide/practices/page-sizes) becomes relevant for API 35+ devices. A pure-Kotlin launcher has no native libraries, so this is out of scope — noted so a later ticket doesn't have to rediscover it.

---

## 5. Icon retrieval, including adaptive icons

### 5.1 The API options, ranked

| API | Signature | Added | Verdict for Nornir |
| --- | --- | --- | --- |
| `LauncherActivityInfo.getIcon(int density)` | `public Drawable getIcon(int density)` | API 21 | ✅ **Use this.** Per-activity, already in hand from `getActivityList()`. |
| `LauncherActivityInfo.getBadgedIcon(int density)` | `public Drawable getBadgedIcon(int density)` | API 21 | ✅ Use when rendering work-profile apps. |
| `PackageManager.getApplicationIcon(ApplicationInfo)` | `public abstract Drawable getApplicationIcon(ApplicationInfo info)` | API 1 | ⚠️ Per-*package*, not per-activity — wrong granularity, and no profile badging. |
| `PackageManager.getApplicationIcon(String)` | *(same, by package name)* | API 1 | ⚠️ Same limitation. |
| `PackageManager.getActivityIcon(ComponentName)` / `(Intent)` | — | API 1 | ⚠️ Correct granularity but requires a second round-trip you don't need. |

Reference wording for the two chosen calls:

> `getIcon(int density)` — "Returns the icon for this activity, without any badging for the profile. The returned drawable is subject to the same size capping limits as described in `PackageManager.getApplicationIcon(ApplicationInfo)`." Parameter: *"The preferred density of the icon, zero for default density. Use density DPI values from `DisplayMetrics`."*
> — [`LauncherActivityInfo.getIcon`](https://developer.android.com/reference/android/content/pm/LauncherActivityInfo#getIcon(int))

> `getBadgedIcon(int density)` — "Returns the activity icon with badging appropriate for the profile." Parameter: *"Optional density for the icon, or 0 to use the default density."*
> — [`LauncherActivityInfo.getBadgedIcon`](https://developer.android.com/reference/android/content/pm/LauncherActivityInfo#getBadgedIcon(int))

**Size cap** (documented on the `PackageManager` method that `getIcon` defers to):

> "Retrieve the icon associated with an application. If it has not defined an icon, the default app icon is returned. **Does not return null.** … **Note:** The returned drawable's dimensions are capped to a maximum size of **2048 x 2048 pixels**. If the resource's intrinsic dimensions exceed this limit, it will be downsampled automatically, preserving its aspect ratio."
> — [`PackageManager.getApplicationIcon(ApplicationInfo)`](https://developer.android.com/reference/android/content/pm/PackageManager#getApplicationIcon(android.content.pm.ApplicationInfo))

Two useful guarantees there: **never null**, and **falls back to the default app icon** — so Nornir's `AppItem.icon: Drawable` (non-null, as the blueprint has it) is safe for this path.

**Labels, while you're there:** use `LauncherActivityInfo.getLabel()`, which has documented fallback behaviour — *"Retrieves the label for the activity. If the activity's label is invisible for the user, use the application's label instead. If the application's label is still invisible for the user, use the package name instead."* ([reference](https://developer.android.com/reference/android/content/pm/LauncherActivityInfo#getLabel())). This removes the need for Nornir to write its own label-fallback chain.

### 5.2 Recommended retrieval shape

```kotlin
// One call gets label + icon + component for every launchable activity, all profiles.
val launcherApps = context.getSystemService(LauncherApps::class.java)
val density = context.resources.displayMetrics.densityDpi

val items = launcherApps.profiles.flatMap { user ->
    launcherApps.getActivityList(/* packageName = */ null, user).map { info ->
        AppItem(
            label       = info.label.toString(),          // documented fallback chain
            component   = info.componentName,             // per-activity identity, not package
            user        = user,
            icon        = info.getBadgedIcon(density),     // profile-correct
        )
    }
}
```

Notes:
- Key the model on `ComponentName` + `UserHandle`, **not** on `packageName` alone. `getActivityList` is per-activity (§3.4) and multi-profile, so a package-keyed map silently collapses distinct entries.
- Pass `densityDpi` explicitly rather than `0` so the icon matches the display you're rendering on.
- `getActivityList` is a binder call across every installed app and icon loading inflates resources from other APKs — do this off the main thread and cache. The framework itself treats it as expensive (`LauncherAppsService` wraps the call in `injectClearCallingIdentity()` / `ParceledListSlice` batching, [source](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/services/core/java/com/android/server/pm/LauncherAppsService.java)).
- To stay in sync, register a `LauncherApps.Callback` — the class doc notes *"Since the PackageManager will not deliver package broadcasts for other profiles, you can register for package changes here"* ([reference](https://developer.android.com/reference/android/content/pm/LauncherApps)) — plus `Intent.ACTION_MANAGED_PROFILE_ADDED` / `ACTION_MANAGED_PROFILE_REMOVED` for profile changes, as that same page instructs.

### 5.3 Adaptive icons — what you actually get back

The returned `Drawable` may be an `AdaptiveIconDrawable` (API 26+):

> "This class can also be created via XML inflation using `<adaptive-icon>` tag in addition to dynamic creation. **This drawable supports two drawable layers: foreground and background. The layers are clipped when rendering using the mask defined in the device configuration.** Both foreground and background layers should be sized at 108 x 108 dp. The inner 72 x 72 dp of the icon appears within the masked viewport. The outer 18 dp on each of the 4 sides of the layers is reserved for use by the system UI surfaces to create interesting visual effects, such as parallax or pulsing."
> — [`AdaptiveIconDrawable` reference](https://developer.android.com/reference/android/graphics/drawable/AdaptiveIconDrawable) ("Added in API level 26")

> "An alternate drawable can be specified using `<monochrome>` tag which can be drawn in place of the two (background and foreground) layers. This drawable is tinted according to the device or surface theme."
> — same reference

Layer accessors:

| Method | Added | Returns |
| --- | --- | --- |
| `getBackground()` | API 26 | Background layer `Drawable` |
| `getForeground()` | API 26 | Foreground layer `Drawable`; bounds extended by `getExtraInsetFraction()` on each axis |
| `getMonochrome()` | **API 33** | *"Returns the monochrome version of this drawable. Callers can use a tinted version of this drawable instead of the original drawable on surfaces stressing user theming."* — **"This value may be null."** |
| `getIconMask()` | API 26 | `Path` — *"When called before the bound is set, the returned path is identical to `R.string.config_icon_mask`. After the bound is set, the returned path's computed bound is same as the `#getBounds()`."* → *"the mask path object used to clip the drawable"* |
| `getExtraInsetFraction()` | API 26 | `static float` — the reserved-inset fraction |

(All from the [`AdaptiveIconDrawable` reference](https://developer.android.com/reference/android/graphics/drawable/AdaptiveIconDrawable).)

The class already handles masking when you just `setBounds()` + `draw()` it — the reference states the layers "are clipped when rendering using the mask defined in the device configuration". **So the simple path is: treat it as an opaque `Drawable`.** Only decompose into layers if Nornir wants a non-OEM shape, its own mask, or its own parallax/pulse effects — in which case `getIconMask()` gives you the OEM shape and `getForeground()`/`getBackground()` the layers.

### 5.4 Adaptive-icon facts that affect launcher UI

From the official [Adaptive icons](https://developer.android.com/develop/ui/views/launch/icon_design_adaptive) guide:

- *"An adaptive icon, or `AdaptiveIconDrawable`, can display differently depending on individual device capabilities and user theming. Adaptive icons are primarily used by the launcher on the home screen…"*
- **Shape is OEM-defined:** *"an adaptive icon can display a variety of shapes across different device models… **Each device OEM must provide a mask, which the system uses to render all adaptive icons with the same shape.**"* → Nornir should use `getIconMask()` rather than hardcoding a circle/squircle, or accept the platform's own rendering.
- **Visual effects are the launcher's job:** *"Animated visual effects are generated by supported launchers. Visual effects might vary from one launcher to another."* → parallax/pulse are optional Nornir features, not free.
- **Themed icons (API 33+):** *"starting with Android 13 (API level 33), users can theme their adaptive icons. If a user enables themed app icons in their system settings, **and the launcher supports this feature**, the system uses the coloring of the user's chosen wallpaper and theme to determine the tint color of the app icons for apps that have a `monochrome` layer…"* And: *"Starting with Android 16 QPR 2, Android automatically themes app icons for apps that don't provide their own."*
  The guide lists exactly when themed icons are *not* shown: *"If the user doesn't enable themed app icons. If your app doesn't provide a monochromatic app icon and the user's device runs on an earlier version of Android than Android 16 QPR 2. **If the launcher doesn't support themed app icons.**"*
  → **Planning consequence:** themed-icon support is opt-in launcher work (read `getMonochrome()` when non-null on API 33+, tint from the Material dynamic-color scheme). Worth a separate ticket; not required for v1.
- **Geometry for Nornir's *own* icon:** two layers (foreground + background) sized **108×108 dp**, logo at least 48×48 dp and no more than 66×66 dp, outer 18 dp per side reserved for masking/effects, vectors preferred, no baked-in masks or shadows. (Note the guide's "inner 66×66 dp … appears within the masked viewport" vs. the API reference's "inner 72 × 72 dp" — the guide is the stricter design-safe figure; use 66×66 dp when authoring `ic_launcher`.)

### 5.5 Compose interop

Nothing first-party was found that supersedes the standard approach, so this is deliberately left as an implementation choice for the UI ticket rather than asserted as a platform fact. The constraint that *is* primary-sourced: these APIs hand back `android.graphics.drawable.Drawable`, not a Compose `Painter` or `ImageBitmap`, so a conversion/interop step is unavoidable. Recommend the UI ticket decide between (a) `Drawable.toBitmap()` + `asImageBitmap()`, or (b) an image-loading library, and record the choice there.

---

## 6. Fact base — quick reference for downstream tickets

| Fact | Value | Source |
| --- | --- | --- |
| `ApplicationInfo.category` available from | API 26 | [ref](https://developer.android.com/reference/android/content/pm/ApplicationInfo#category) |
| Number of defined `CATEGORY_*` values | 9 (+ `CATEGORY_UNDEFINED`) | [AOSP `ApplicationInfo.java`](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/content/pm/ApplicationInfo.java) |
| `CATEGORY_ACCESSIBILITY` available from | API 31 | [ref](https://developer.android.com/reference/android/content/pm/ApplicationInfo#CATEGORY_ACCESSIBILITY) |
| Parse default when `appCategory` absent | `CATEGORY_UNDEFINED` | [AOSP `ParsingPackageUtils.java`](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/com/android/internal/pm/pkg/parsing/ParsingPackageUtils.java) |
| Who can set the category hint | only the installer package | [AOSP `PackageManagerService.java`](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/services/core/java/com/android/server/pm/PackageManagerService.java) |
| AOSP bundled apps setting `appCategory` | 0 of 11 checked | §2.4 |
| Visibility filtering gated on | **targetSdk** ≥ 30 (`FILTER_APPLICATION_QUERY`, `@EnabledSince(R)`) | [AOSP `PackageManager.java`](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/content/pm/PackageManager.java) |
| Launcher-specific visibility allowance | **none** | [automatic-visibility list](https://developer.android.com/training/package-visibility/automatic); [AOSP `roles.xml`](https://android.googlesource.com/platform/packages/modules/Permission/+/refs/heads/main/PermissionController/res/xml/roles.xml) |
| `QUERY_ALL_PACKAGES` protection level | `normal` (install-time, no user prompt) | [AOSP `core/res/AndroidManifest.xml`](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/res/AndroidManifest.xml) |
| `QUERY_ALL_PACKAGES` Play gate | Permissions Declaration Form; rejected if a less broad method suffices | [Play Console Help](https://support.google.com/googleplay/android-developer/answer/10158779) |
| Enumeration API for launchers | `LauncherApps.getActivityList(null, user)` (API 21) | [ref](https://developer.android.com/reference/android/content/pm/LauncherApps#getActivityList(java.lang.String,%20android.os.UserHandle)) |
| Does `getActivityList` need default-launcher status? | **No** (only `canAccessProfile`) | [AOSP `LauncherAppsService.java`](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/services/core/java/com/android/server/pm/LauncherAppsService.java) |
| Do `LauncherApps` *shortcut* APIs need it? | **Yes** — must be the default launcher | [AOSP `ShortcutService.java`](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/services/core/java/com/android/server/pm/ShortcutService.java) |
| Play `targetSdk` for new apps from 2026-08-31 | **36** | [Play Console Help](https://support.google.com/googleplay/android-developer/answer/11926878) |
| Latest stable API level | 36 (Android 16, stable March 2025) | [SDK Platform release notes](https://developer.android.com/tools/releases/platforms) |
| Compose minSdk floor | 21 | [Compose setup](https://developer.android.com/develop/ui/compose/setup) |
| `AdaptiveIconDrawable` from | API 26 | [ref](https://developer.android.com/reference/android/graphics/drawable/AdaptiveIconDrawable) |
| `getMonochrome()` from | API 33, **nullable** | [ref](https://developer.android.com/reference/android/graphics/drawable/AdaptiveIconDrawable#getMonochrome()) |
| Icon drawable size cap | 2048 × 2048 px, auto-downsampled | [`getApplicationIcon` ref](https://developer.android.com/reference/android/content/pm/PackageManager#getApplicationIcon(android.content.pm.ApplicationInfo)) |
| Adaptive icon authoring geometry | 108×108 dp layers, 66×66 dp safe zone, 18 dp reserved per side | [Adaptive icons guide](https://developer.android.com/develop/ui/views/launch/icon_design_adaptive) |

---

## 7. Open questions this research did NOT resolve

1. **Real-device `category` distribution.** §2.4 measures AOSP-bundled apps, not a typical Play-installed app set. The *mechanism* (§2.3) makes the conclusion safe, but a one-off histogram on a real device would close it empirically. Not blocking.
2. **Compose `Drawable` interop choice** (§5.5) — deferred to the UI ticket by design.
3. **Themed-icon (monochrome) support** — established as possible and opt-in (§5.4); scoping it is a separate ticket.
4. **`allowBackup` / backup semantics** — intentionally left to the T3 persistence ticket rather than asserted in the manifest here (§3.8).

## 8. Method note

All claims above trace to one of three primary-source classes: (a) `developer.android.com` API reference and official guides, (b) AOSP source read directly from `android.googlesource.com` at `refs/heads/main`, (c) Google Play Console Help policy pages. No secondary write-ups, blogs, or Q&A sites were used. Where the reference docs and AOSP source could both answer a question, both were checked and are cited together — §2.3, §3.1, §3.3, and §3.5 are the places where the AOSP source proves something the prose docs only imply.
