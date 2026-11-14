{
  description = "Moku Mobile — native Android app (and future iOS remote client) for Tsunagu";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";
    flake-parts.url = "github:hercules-ci/flake-parts";
  };

  outputs =
    inputs@{ flake-parts, ... }:
    flake-parts.lib.mkFlake { inherit inputs; } {
      systems = [
        "x86_64-linux"
        "aarch64-linux"
        "x86_64-darwin"
        "aarch64-darwin"
      ];

      perSystem =
        { system, lib, ... }:
        let
          pkgs = import inputs.nixpkgs {
            inherit system;
            config = {
              allowUnfree = true;
              android_sdk.accept_license = true;
            };
          };
          androidComposition = pkgs.androidenv.composeAndroidPackages {
            cmdLineToolsVersion = "13.0";
            toolsVersion = "26.1.1";
            platformToolsVersion = "37.0.1";
            buildToolsVersions = [ "34.0.0" "36.0.0" ];
            platformVersions = [ "34" "36" ];
            includeEmulator = true;
            emulatorVersion = "37.2.4";
            includeSystemImages = true;
            systemImageTypes = [ "google_apis_playstore" ];
            # x86_64 for the emulator, arm64-v8a for phones.
            abiVersions = [
              "arm64-v8a"
              "x86_64"
            ];
            includeSources = false;
            includeNDK = true;
            useGoogleAPIs = true;
            useGoogleTVAddOns = false;
          };
          androidSdk = androidComposition.androidsdk;
        in
        {
          devShells.default = pkgs.mkShell {
            name = "moku-mobile-dev";

            packages = with pkgs; [
              androidSdk
              jdk17
              kotlin
              gradle
              go
            ];

            # The NDK and Go build the embedded Tsunagu core (scripts/build-tsunagu.sh).
            shellHook = ''
              export JAVA_HOME=${pkgs.jdk17}/lib/openjdk
              export PATH=$JAVA_HOME/bin:$PATH
              export ANDROID_HOME=${androidSdk}/libexec/android-sdk
              export ANDROID_SDK_ROOT=$ANDROID_HOME
              export ANDROID_NDK_HOME=$(echo $ANDROID_HOME/ndk/*)
              export GRADLE_OPTS="-Dorg.gradle.project.android.aapt2FromMavenOverride=$ANDROID_HOME/build-tools/36.0.0/aapt2"

              echo "Moku Mobile dev shell: JDK 17, Android SDK 36. Build with: cd android && gradle assembleDebug"
            '';
          };

          formatter = pkgs.nixfmt-rfc-style;
        };
    };
}
