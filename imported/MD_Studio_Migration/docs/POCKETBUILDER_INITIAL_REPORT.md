# PocketBuilder 0.145 — Initial APK Inspection

## Scope
This is an inspection of the supplied APK only. It is not the original source repository and does not include the PocketBuilder server implementation.

## Confirmed package facts
- Input: `pocketbuilder0.145.apk`
- APK size: 58,422,902 bytes (about 55.7 MiB)
- ZIP entries: 1,410
- Total uncompressed entry size: 73,892,949 bytes
- DEX files: `classes.dex` (10,495,560 bytes), `classes2.dex` (7,971,108 bytes)
- Native libraries include AAPT2 and AndroidX/Icing libraries for arm64-v8a, armeabi-v7a, x86 and x86_64.
- Bundled assets include `android.jar`, a `base-template.apk`, ECJ message resources, a build script, an environment setup script, and Java helper/diagnostic sources.
- APK metadata reports Android Gradle Plugin 8.11.1 and a Git revision identifier `64b94b889c423b10988ddc0ecbd38cb32ea132fb`.

## Server/API indicators found in DEX string tables
The APK contains these route strings (presence does not establish request semantics or server-side behavior):
- `/api/v1/account/usage`
- `/api/v1/payments/`
- `/api/v1/auth/refresh`
- `/api/v1/agent`
- `/api/v1/ai/tasks`
- `/api/v1/projects/`
- `/api/v1/apps/`
- `/api/v1/runtime-ai/`
- `/api/v1/runtime-search/`

The string table also contains `https://pocketbuilder.app` and related port-8788 variants, account/credit UI strings, and payment-provider library strings. This is not enough to determine the complete account-credit transaction protocol.

## Important distinction for the proposed Pro modes
The APK alone cannot authorize or enforce a no-charge entitlement on the production server. A client-side zero amount or a client-supplied "experimental" label is not proof that the server accepts a free operation. The supported implementation path is a server-recognized test entitlement / zero-cost operation, with server-side authorization and audit records. The Normal path must remain unchanged.

## Current limitations
- AndroidManifest.xml is binary AXML and has not yet been decoded in this first pass.
- DEX files have been extracted, but full Java/Kotlin decompilation has not been performed.
- No original Gradle project/source tree was found as a normal source project in the APK inventory.
- No server source/configuration or server-side credit rules are present in the inspected APK contents.
- No modified APK has been built or tested.

## Next required material for a genuine Pro implementation
A source project or authorized server-side implementation/configuration is needed to implement and verify the server-recognized Pro entitlement. The current APK can continue to be analyzed for client architecture and UI, but changing the APK alone cannot guarantee server-side no-charge behavior.
