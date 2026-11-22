# Changelog

All notable changes to this project are documented here. Format loosely follows
[Keep a Changelog](https://keepachangelog.com/). The short per-release notes
that F-Droid shows live in `fastlane/metadata/android/en-US/changelogs/`.

## [Unreleased]

### Changed
- Android 16 or newer is now required (minSdk 36). Material You dynamic colour is always on, and the fallback palette for older phones is gone.

## [1.12] - 2026-11-22

### Changed
- Android 6 and up, built on compileSdk 37 and targetSdk 36 with Compose BOM 2026.09.00

## [1.11] - 2026-11-16

### Changed
- New launcher icon: a lost game in nine tiles

## [1.10] - 2026-11-11

### Changed
- Gradle wrapper 9.9.1

## [1.9] - 2026-10-12

### Changed
- Build tooling and dependency updates only

## [1.8] - 2026-09-27

### Added
- Best times and win counts per preset, shown in the win and lose banner
- A flag mode toggle

### Fixed
- Hard: the first tap is always safe

## [1.7] - 2026-09-27

### Fixed
- The exploding tile's flashing border stays whole

## [1.6] - 2026-09-21

### Changed
- Build tooling and dependency updates only

## [1.5] - 2026-09-09

### Fixed
- Custom games that were unwinnable, or won on the first tap: the mine count now accounts for the safe first-tap area

## [1.4] - 2026-09-08

### Added
- The third-party licenses can be read inside the app, with no network

## [1.3] - 2026-07-26

### Fixed
- Tiles stayed tappable during the loss animation, so you could keep clearing the board and even win a game you had already lost
- The "Game Over" banner and shake now fire the instant you hit a mine

### Changed
- Updated build tooling and dependencies

## [1.2] - 2026-06-28

### Changed
- Mine counter and timer render as true seven-segment LED digits, ghost "888" and all

## [1.1] - 2026-06-21

### Added
- Custom mode: choose grid size, mine count, fog of war, and safe first tap

### Fixed
- Flagged tiles no longer show an odd tint behind the flag

## [1.0] - 2026-04-07

Initial release.
