# MovieProvider Repository

A CloudStream 3 plugin repository containing a single Arabic movie/series provider for **قصة عشق (Qesset)**.

## Overview

This repository provides a provider extension for [CloudStream](https://github.com/recloudstream/cloudstream), an Android TV streaming application. The `Qesset` provider scrapes content from `qesset.com`.

## Features

- **Homepage sections**: Latest episodes, series, movies, new movies
- **Search**: Search movies and series by title
- **Details**: Poster, year, plot for movies and series
- **Series**: Season/episode lists collected across paginated series pages
- **Streaming links**: Decodes the watch-page server list and resolves embeds (Arab HD, estream, dailymotion, ok.ru, Red HD, Pro HD, box, now, facebook, youtube, express, …) plus direct `.m3u8` / `.mp4` extraction
- **Download support**: Download page links when available
- **Chromecast**: Supported

## Provider: Qesset

| Property | Value |
|----------|-------|
| **Name** | قصة عشق (Qesset) |
| **Supported Types** | Movie, TvSeries |
| **Language** | Arabic (`ar`) |
| **Status** | Active |
| **Chromecast** | Yes |
| **Download** | Yes |

### Methods

- `getMainPage()` - Homepage sections from `qesset.com` (`/son-bolumler/`, `/discover/`, `/movies/`, `/category/yeni-filmler/`)
- `search(query)` - Search via `/?s=<query>`
- `load(url)` - Movie/series details including paginated season/episode lists
- `loadLinks(data)` - Decodes the Base64 server list from the watch page and resolves embeds

## Installation

### Adding this Repository to CloudStream

1. Open CloudStream
2. Go to **Settings** > **Extensions** > **Install from URL**
3. Enter the repository URL:
   ```
   https://raw.githubusercontent.com/comibrand00-stack/MovieProviderRepo/master/repo.json
   ```
4. The **قصة عشق (Qesset)** provider will appear in the provider list

### Build from Source

```bash
# Clone the repository
git clone https://github.com/comibrand00-stack/MovieProviderRepo.git
cd MovieProviderRepo

# Build the provider
./gradlew :MovieProvider:make

# Deploy to device (requires ADB)
./gradlew :MovieProvider:deployWithAdb
```

## Development

### Project Structure

```
MovieProviderRepo/
├── MovieProvider/
│   ├── build.gradle.kts          # Provider build configuration
│   └── src/main/
│       ├── AndroidManifest.xml   # Android manifest
│       └── kotlin/com/example/movieprovider/
│           ├── MoviePlugin.kt    # Plugin entry point
│           └── Qesset.kt         # Qesset MainAPI implementation
├── build.gradle.kts              # Root build configuration
├── settings.gradle.kts           # Project settings
├── repo.json                     # Repository manifest
└── .github/workflows/build.yml   # CI/CD pipeline
```

### Site Integration Notes

- Homepage: `/son-bolumler/` (latest episodes), `/discover/` (series), `/movies/` (movies), `/category/yeni-filmler/` (new movies)
- Search: GET `/?s=<query>`
- Cards: `article` blocks whose link points to `/movies/`, `/yeni-show/`, `/clarus/` or `/tvshow/`
- Series pages: `a[href*=/clarus/]` episode links with `episode-<n>` in the URL, `link[rel=next]` for pagination (max 10 pages)
- Watch page: the `a[href*=qesen]` link carries a Base64 `post=` value that decodes to a JSON server list of `{"name":…,"id":…}` pairs

## License

This project is licensed under the MIT License. See the [LICENSE](LICENSE) file for details.

## Disclaimer

This project is for educational purposes only. Streaming copyrighted content without authorization is illegal. Use at your own risk.

## Acknowledgments

- [CloudStream](https://github.com/recloudstream/cloudstream) - The streaming platform
- [NiceHttp](https://github.com/Blatzar/NiceHttp) - HTTP library
- [Jsoup](https://jsoup.org/) - HTML parsing library
