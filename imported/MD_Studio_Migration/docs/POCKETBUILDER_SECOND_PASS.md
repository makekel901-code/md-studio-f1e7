# PocketBuilder 0.145 — Second-pass manifest and architecture findings

## Scope
This pass re-opened the supplied APK's binary Android manifest and inspected the bundled helper sources, build/setup scripts, DEX string table, and package metadata. It remains an APK-only inspection. It does not reconstruct the original source project or inspect the production server.

## Confirmed Android package identity
- Application ID/package: `com.pocketbuilder`
- Version name: `0.145`
- Version code: `0x91` (145 decimal)
- Minimum Android API: 29 (Android 10)
- Target Android API: 34
- `allowBackup=false`; RTL support is enabled.
- Declared app permissions include INTERNET, ACCESS_NETWORK_STATE, POST_NOTIFICATIONS, FOREGROUND_SERVICE, FOREGROUND_SERVICE_DATA_SYNC, HIDE_OVERLAY_WINDOWS, and REQUEST_INSTALL_PACKAGES.
- The app's launcher activity is `com.pocketbuilder.MainActivity`.
- A non-exported `AgentProgressService` is declared for foreground progress work.

## Declared app components worth noting
- `com.pocketbuilder.MainActivity` — exported main activity.
- `com.pocketbuilder.agent.AgentProgressService` — internal foreground service.
- `com.pocketbuilder.data.AppPayloadProvider` — internal content provider.
- `com.pocketbuilder.runtimeai.RuntimeAiProvider` and `RuntimeAiAccessActivity` — exported runtime-AI integration entry points.
- `com.pocketbuilder.runtimesearch.RuntimeSearchProvider` and `RuntimeSearchAccessActivity` — exported runtime-search integration entry points.
- `com.pocketbuilder.testing.PocketBuilderAccessibilityService` — accessibility service protected by `BIND_ACCESSIBILITY_SERVICE`.
- `com.pocketbuilder.testing.RuntimeDiagnosticsProvider` — non-exported diagnostics provider with URI grants.
- Stripe Android payment-sheet components are included in the manifest/dependencies.

The presence of exported integration entry points is not evidence of a vulnerability. Their access-control behavior would require source-level review and authorized runtime testing.

## Server/API surface indicated by DEX strings
The string table contains route names for account usage, account tasks/reviews, authentication refresh/logout, AI catalog/tasks, agent/web-agent, projects, apps/publishing, developer apps/profile, app backend, runtime AI/search, libraries, support threads, tips, multiplayer, and payments. These are route-name indicators only. They do not establish HTTP methods, payload schemas, billing rules, entitlement checks, or whether every route is active in this build.

The APK contains the PocketBuilder host string `https://pocketbuilder.app` and references to port 8788, plus Google AI service and Stripe SDK URLs. These strings alone do not prove which endpoint is selected at runtime or how requests are authenticated.

## Bundled helper sources and scripts
- `assets/runtime-diagnostics/Diagnostics.java` is a helper for opt-in diagnostic sessions. It only reports through a granted content URI, limits a session to 30 minutes, and documents that sensitive form contents and credentials should not be passed to it.
- `assets/library-guides/PocketAbout.java` is a helper to display third-party notices.
- `assets/scripts/setup-pocketbuilder-env.sh` is a Termux-oriented environment setup script that installs Java/Kotlin and Android tools.
- `assets/scripts/build-pocket-app.sh` is a short four-step script for compiling resources, compiling Kotlin, dexing, and signing. Its final signing step currently only prints a message; it does not actually invoke `apksigner`. It also suppresses some command failures with `|| echo`, so it is not a reliable verified build pipeline as packaged.
- Build metadata reports Android Gradle Plugin 8.11.1 and a Git revision identifier. That metadata is not a substitute for the missing Gradle project/source tree.

## What this means for Pocket Builder Pro
1. The installed application package is `com.pocketbuilder`; a visible label change alone would not create an independently installable Pro identity. A distinct package identity and signing/build configuration require a genuine rebuild/repackage workflow and compatibility checks.
2. The APK contains compiled DEX and resources, not a complete editable source tree. Decompilation may help understand client architecture, but it cannot restore original source comments, build configuration, or server implementation exactly.
3. The APK exposes evidence of account-usage and payment-related API surfaces, but does not reveal the server's authoritative billing/entitlement rules.
4. The agreed Normal / Pro Zero / Pro Experimental modes can only be made reliable when the server recognizes the entitlement or a documented zero-cost operation. A client-side amount or label is not authoritative. No instructions or modifications to falsify billing requests have been made.
5. The safe next implementation input is the authorized app source project plus the server-side API/entitlement contract or a test environment where the owner can add and validate the test entitlement. Without those, work can continue on static client analysis, but a functional no-charge Pro build cannot be verified from this APK alone.

## Not performed
- No production API calls or login attempts.
- No modification of payment/credit requests.
- No source-project reconstruction claimed.
- No modified APK build, signing, install, or runtime test.
