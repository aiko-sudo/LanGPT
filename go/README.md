# Go Android binding

This directory contains the Go sources for the Android Tailscale binding. The
module dependency versions and checksums are recorded in `go.mod` and
`go.sum`; keep both files unchanged when rebuilding.

## Toolchain and tests

Use Go **1.27.3** (the minimum specified by `go.mod`), Git, and network access
to fetch dependencies. From this directory:

```sh
go version                  # verify go1.27.3
git status --short          # optional: inspect your working tree
GOTOOLCHAIN=local GOWORK=off go test -mod=readonly ./...
```

The tests above passed with Go 1.27.3 on Linux/arm64. Some tests or dependency
downloads may require network access.

## Build an Android AAR

Install a compatible Android SDK and NDK and accept their licenses. The
previous build script specified NDK **r27c**, Android API **26**, and
`android/arm64`; use a supported host for the NDK and set `ANDROID_HOME` and
`ANDROID_NDK_HOME` to your SDK and NDK directories. Ensure their command-line
tools, a C toolchain and Go are available. For example, in a POSIX shell:

```sh
export GOTOOLCHAIN=local GOWORK=off
export GOBIN="${GOBIN:-$(go env GOPATH)/bin}"
export PATH="$GOBIN:$PATH"
MOBILE_VERSION=v0.0.0-20261011034528-197770444867
go install golang.org/x/mobile/cmd/gomobile@"$MOBILE_VERSION" \
           golang.org/x/mobile/cmd/gobind@"$MOBILE_VERSION"
gomobile init
# init may install gobind@latest; restore the pinned version.
go install golang.org/x/mobile/cmd/gobind@"$MOBILE_VERSION"
go version -m "$GOBIN/gomobile"  # check the embedded module version
gomobile bind -target=android/arm64 -androidapi=26 -o tailscale.aar .
```

The pinned `golang.org/x/mobile` version is the version selected in `go.mod`;
installation of both tools at that version with Go 1.27.3 was verified. The
version and settings used to produce the existing distributed AAR have not
been established, and the AAR build above has not been verified in a clean
Android SDK/NDK environment. It is a rebuild recipe, not a claim of
byte-for-byte reproducibility or identity with that AAR. Verify toolchain,
SDK/NDK versions, dependencies, generated AAR and application integration in
your own environment before distribution. The output path above does not
overwrite the app's existing AAR.

See the repository's license files and the licenses of third-party dependencies
before redistributing.
