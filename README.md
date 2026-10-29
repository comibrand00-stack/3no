# MovieProvider Repository

A CloudStream 3 plugin repository with five plugins: **Qesset** (قصة عشق movies/series from `qesset.com`), **BingeBang** (movies/series with Arabic subtitles from `bingebang.st`), **alooyTV** (all alooytv lists: series, movies, anime from `ec.alooytv16.xyz`), **CineHD Live** (live TV channels from `cinehd.vc`) and **NovaHD** (Arabic movies/series with Arabic subtitles from `novahd.cc`).

## Overview

This repository provides provider extensions for [CloudStream](https://github.com/recloudstream/cloudstream), an Android TV streaming application. The `Qesset` plugin scrapes content from `qesset.com`, the `BingeBang` plugin uses the `bingebang.st` API, the `AlooyTV` plugin covers every alooytv list from `ec.alooytv16.xyz`, the `CineHDLive` plugin provides live TV channels from the `cinehd.vc` API, and the `NovaHD` plugin uses the `novahd.cc` API with Arabic catalogue data and every available server.

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

### NovaHD

- **Homepage sections (Arabic)**: Trending, popular/top-rated/new movies and series, plus 12 movie genres and 10 series genres (all labels in Arabic)
- **Search**: Search movies and series via `/api/search` with `lang=ar-SA`
- **Details**: Arabic titles/plots, genres in Arabic, score, recommendations, full season/episode lists
- **Streaming links**: Emits **every** server returned by `/api/sources` (Orion, Swift, Quartz, Heron, Vidlove, Rive, Falcon, …)
- **Subtitles**: **Arabic only** (site tracks + SubtitleCat fallback, since NovaHD has no Arabic track)
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

## Provider: NovaHD

| Property | Value |
|----------|-------|
| **Name** | NovaHD |
| **Provider** | NovaHD (`novahd.cc`) |
| **Supported Types** | Movie, TvSeries |
| **Language** | Arabic (`ar`) |
| **Status** | Active |
| **Chromecast** | Yes |
| **Download** | Yes |

### Methods

- `getMainPage()` - Arabic rows: trending, popular/top-rated/new movies and series, 12 movie genres and 10 series genres via `/api/movies`, `/api/shows` and `/api/trending` with `lang=ar-SA`
- `search(query)` - Search via `/api/search?search=<q>&lang=ar-SA`
- `load(url)` - Movie/series details (Arabic title, plot, genres, recommendations and every season/episode) from `/api/movies/{id}` and `/api/shows/{id}`
- `loadLinks(data)` - Calls `/api/sources` with the required `x-nova-visitor` UUID, emits **all** mirrors as separate sources, and adds **Arabic subtitles only** (site tracks + SubtitleCat fallback)

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
4. The **قصة عشق (Qesset)**, **BingeBang**, **alooyTV**, **CineHD Live** and **NovaHD** providers will appear in the provider list

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
  
