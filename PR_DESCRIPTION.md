# Chrome 152 isolated split APK support

## Summary

Chrome 152 moves the main browser classes into the `split_chrome.apk` isolated split. The base application ClassLoader cannot resolve those classes, so the existing hooks were never installed for Chrome 152.

This change obtains the split ClassLoader from `SplitChromeApplication.createContextForSplit("chrome")`, then installs the Chrome hooks after the split is available. Chrome 145 fallback paths remain in place where the signatures are still compatible.

## Changes

- Added delayed hook installation for Chrome's `chrome` split ClassLoader.
- Added Chrome 152 tab model and homepage/new-tab compatibility paths.
- Updated closed-tab cleanup to the Chrome 152 JNI signature.
- Added Chrome 152 download dialog bridge compatibility.
- Kept older Chrome 145 fallback paths.
- Documented supported versions and verification results.

## Verification

- Chrome `152.0.7977.76`
- LSPosed on the connected Android test device
- Cold start opens the configured homepage.
- Closed-tab history cleanup completes successfully.
- Chrome remains running after hook installation with no fatal exception.
- `assembleDebug` succeeds with JDK 17 and AGP 9.0.0.

## Notes

Warnings for obsolete obfuscated entry points such as `c9o`, `zkg`, `je7`, and `nze` are expected on Chrome 152. The corresponding Chrome 152 paths are installed successfully and remain active.
