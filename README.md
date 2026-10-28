# MovieProvider Repository

A CloudStream 3 plugin repository with four plugins: **Qesset** (قصة عشق movies/series from `qesset.com`), **BingeBang** (movies/series with Arabic subtitles from `bingebang.st`), **alooyTV** (all alooytv lists: series, movies, anime from `ec.alooytv16.xyz`) and **CineHD Live** (live TV channels from `cinehd.vc`).

## Overview

This repository provides provider extensions for [CloudStream](https://github.com/recloudstream/cloudstream), an Android TV streaming application. The `Qesset` plugin scrapes content from `qesset.com`, the `BingeBang` plugin uses the `bingebang.st` API, the `AlooyTV` plugin covers every alooytv list from `ec.alooytv16.xyz`, and the `CineHDLive` plugin provides live TV channels from the `cinehd.vc` API.

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

### CineHD Live

- **Homepage sections**: Arabic channels row, 19 Arabic country rows, 10 category rows, 6 world-region rows and an "all channels" row
- **Coverage**: all 8,000+ channels from the `cinehd.vc` live TV catalog across 250 countries
- **Search**: Arabic and English, including Arabic alt names with letter normalization
- **Channel page**: logo, country, categories and up to 15 stream sources per channel
- **Streaming links**: direct HLS/DASH playback (no download, live only)
- **Caching**: local 6-hour cache for the catalog to keep navigation fast

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

## Plugin: alooyTV

| Property | Value |
|----------|-------|
| **Name** | alooyTV |
| **Provider** | AlooyTV (`ec.alooytv16.xyz`) |
| **Supported Types** | Movie, TvSeries |
| **Language** | Arabic (`ar`) |
| **Status** | Active |
| **Chromecast** | Yes |
| **Download** | Yes |

### Methods

- `getMainPage()` - All 21 site lists (latest + every genre: خليجي، عربي، تركي، فارسي، انمي، رمضان …)
- `search(query)` - Search via `/search?q=<query>`
- `load(url)` - Series episodes (`Ep#N` buttons with `?key=` stream tokens) or movie details
- `loadLinks(data)` - Direct mp4 from the watch page plus Arabic subtitles (SubtitleCat)

## Plugin: CineHD Live

| Property | Value |
|----------|-------|
| **Name** | CineHD Live |
| **Provider** | CineHD (`cinehd.vc`) |
| **Supported Types** | Live |
| **Language** | Arabic (`ar`) |
| **Status** | Active |
| **Chromecast** | Yes |
| **Download** | No (live streams) |

### Methods

- `getMainPage()` - Arabic channels, 19 Arabic countries, 10 categories, 6 world regions and all channels from `GET /api/live-tv`
- `search(query)` - Searches every channel by name and alt names (Arabic/English)
- `load(url)` - Channel details: logo, country, categories and stream source count
- `loadLinks(data)` - Emits up to 15 direct HLS/DASH sources per channel

## Installation

### Adding this Repository to CloudStream

1. Open CloudStream
2. Go to **Settings** > **Extensions** > **Install from URL**
3. Enter the repository URL:
   ```
   https://raw.githubusercontent.com/comibrand00-stack/3no/master/repo.json
   ```
4. The **قصة عشق (Qesset)**, **BingeBang**, **alooyTV** and **CineHD Live** providers will appear in the provider list

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
  
