# MovieProvider Repository

A CloudStream 3 plugin repository containing movie/series providers for **قصة عشق (Qesset)** and **BingeBang**.

## Overview

This repository provides provider extensions for [CloudStream](https://github.com/recloudstream/cloudstream), an Android TV streaming application. The `Qesset` provider scrapes content from `qesset.com`, and the `BingeBang` provider uses the `bingebang.st` API.

## Features

### Qesset

- **Homepage sections**: Latest episodes, series, movies, new movies
- **Search**: Search movies and series by title
- **Details**: Poster, year, plot for movies and series
- **Series**: Season/episode lists collected across paginated series pages
- **Streaming links**: Decodes the watch-page server list and resolves embeds (Arab HD, estream, dailymotion, ok.ru, Red HD, Pro HD, box, now, facebook, youtube, express, …) plus direct `.m3u8` / `.mp4` extraction
- **Download support**: Download page links when available
- **Chromecast**: Supported

### BingeBang

- **Homepage sections**: Trending, popular, top rated, new releases, streaming services (Netflix, Prime Video, Disney+, …) and countries (Turkey, Iran, Egypt, India, Spain)
- **Search**: Search movies and series via `/api/search/multi`
- **Details**: Poster, year, plot, genres, trailer, recommendations, seasons/episodes
- **Streaming links**: Resolves **all** servers from `/api/player/sources` (`/api/player/resolve`)
- **Subtitles**: **Arabic only** (server subtitles filtered to Arabic + SubtitleCat fallback)
- **Download support**: Supported
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

## Provider: BingeBang

| Property | Value |
|----------|-------|
| **Name** | BingeBang |
| **Supported Types** | Movie, TvSeries |
| **Language** | English (`en`) |
| **Status** | Active |
| **Chromecast** | Yes |
| **Download** | Yes |

### Methods

- `getMainPage()` - Trending/popular/top-rated/new-release rows, per-service rows (Netflix, Prime Video, Disney+, HBO Max, Hulu, …) and per-country rows (Turkey, Iran, Egypt, India, Spain) from `/api/discover` and `/api/list`
- `search(query)` - Search via `/api/search/multi?query=<q>`
- `load(url)` - Movie/series details (including seasons/episodes) from the `data-detail-payload` block
- `loadLinks(data)` - Decrypts the player config, resolves **all** servers via `/api/player/sources` + `/api/player/resolve`, emits **Arabic subtitles only

## Installation

### Adding this Repository to CloudStream

1. Open CloudStream
2. Go to **Settings** > **Extensions** > **Install from URL**
3. Enter the repository URL:
   ```
   https://raw.githubusercontent.com/comibrand00-stack/3no/master/repo.json
   ```
4. The **قصة عشق (Qesset)** and **BingeBang** providers will appear in the provider list

### Build from Source

```bash
# Clone the repository
git clone https://github.com/comibrand00-stack/3no.git
cd MovieProviderRepo

# Build the provider
./gradlew :MovieProvider:make

# Deploy to device (requires ADB)
./gradlew :MovieProvider:deployWithAdb
```

## License

This project is licensed under the MIT License. See the [LICENSE](LICENSE) file for details.

## Disclaimer

This project is for educational purposes only. Streaming copyrighted content without authorization is illegal. Use at your own risk.

## Acknowledgments

- [CloudStream](https://github.com/recloudstream/cloudstream) - The streaming platform
- [NiceHttp](https://github.com/Blatzar/NiceHttp) - HTTP library
- [Jsoup](https://jsoup.org/) - HTML parsing library
  
