fastlane documentation
----

# Installation

Make sure you have the latest version of the Xcode command line tools installed:

```sh
xcode-select --install
```

For _fastlane_ installation instructions, see [Installing _fastlane_](https://docs.fastlane.tools/#installing-fastlane)

# Available Actions

## Android

### android build

```sh
[bundle exec] fastlane android build
```

Build a signed release AAB (uses JBR 21, see CLAUDE.md JBR gotcha)

### android internal

```sh
[bundle exec] fastlane android internal
```

Build and push to the internal testing track

### android promote_to_production

```sh
[bundle exec] fastlane android promote_to_production
```

Promote whatever is live on internal to production

### android release

```sh
[bundle exec] fastlane android release
```

Build and push straight to production (use with care)

----

This README.md is auto-generated and will be re-generated every time [_fastlane_](https://fastlane.tools) is run.

More information about _fastlane_ can be found on [fastlane.tools](https://fastlane.tools).

The documentation of _fastlane_ can be found on [docs.fastlane.tools](https://docs.fastlane.tools).
