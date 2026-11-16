# Clean TV

An IPTV player for Fire TV and Android TV that works with an Xtream Codes account. It has
films, series, live channels and a Sports section with every game that's on, in a home screen
that moves like the big streaming apps.

## What's in it
- **Home**: a billboard for the title in focus (backdrop crossfades and drifts, details swap in)
  over rows of cards. The focused card grows, the row glides so it sits on the same left edge,
  and the other rows dim. Back jumps to the top; Back again opens the menu.
- **Recommendations from what you watch**: Continue Watching, Top Picks for You (with a % match),
  and "Because you watched…" rows. Every film and episode you play is remembered on the TV;
  recent and finished titles count most. With a TMDB key, "Because you watched" uses TMDB's
  recommendations, limited to titles your provider actually has.
- **Critic reviews** on every film and series page: Rotten Tomatoes Tomatometer, Metacritic
  Metascore and IMDb scores (via OMDb), plus full reviews with star ratings from The Guardian.
- **Sports**: pick a sport (football, NFL, basketball, ice hockey, baseball, rugby, F1, UFC,
  golf, tennis) to see what's live, what's coming up and recent results, with live scores that
  refresh every minute. Press OK on a game and Clean TV finds the channel in **your** channel
  list that's showing it (your provider's event channels first, then the broadcaster and the
  channels that usually carry that league) and plays it. ▲ / ▼ in the player tries the next one.
- **Live TV**: every channel once ("Sky Sports Main Event FHD / HD / UHD / SD" becomes one
  tile), starting in HD; press OK while watching to change quality.
- **TV Shows clean-up**: duplicate shows and duplicate copies of an episode are merged; episodes
  resume where you stopped and carry on to the next.

## Critic reviews and artwork keys (optional, free)
Settings (☰ Menu) → **Critic reviews and artwork keys**:

| Key | Gives you | Get one |
|---|---|---|
| OMDb | Rotten Tomatoes, Metacritic and IMDb scores | omdbapi.com/apikey.aspx |
| TMDB | Backdrops when your provider has none, better "Because you watched" | themoviedb.org/settings/api |
| Guardian | Full critic reviews (works without a key on the Guardian's shared test key, which is rate-limited) | open-platform.theguardian.com |

Live scores come from ESPN's public scoreboard feed and need no key.

## Remote controls
| Where | Button | Does |
|---|---|---|
| Home, Movies, TV Shows | ◀ at the left edge | Open the menu |
| Rows | OK | Open the title / play the channel / watch the game |
| Rows | Hold OK | Add to or remove from My List |
| Rows | Back | Jump to the top |
| Anywhere | ☰ Menu | Settings |
| Live channel | OK or ☰ | Pick quality |
| Live channel | ▲ / ▼ | Previous / next channel (or the next channel showing that game) |
| Film or episode | ☰ Menu | Choose which copy to play |
| Episode | ⏭ / ⏮ | Next / previous episode |

## Building
Every push to GitHub builds the APK automatically (Actions tab), runs the unit tests, and
publishes it to `https://github.com/<you>/<repo>/releases/download/latest/CleanTV.apk`.

## Installing on Fire TV
1. Settings → My Fire TV → Developer options → turn on **Install unknown apps** for Downloader
   (if Developer options is hidden: My Fire TV → About → click the device name 7 times).
2. Install **Downloader** from the Amazon Appstore.
3. In Downloader, type **`is.gd/cleantvapk`**, then Install.
   Installing over an older version keeps your login, favourites and history.

This short link works on every Android version, including older boxes on Android 7.1, with or
without `http://` in front. (bit.ly's secure address uses a certificate Android 7.1 doesn't
recognise, so `bit.ly/cleantv-apk` only works there when typed as `http://bit.ly/cleantv-apk`.)
Full link if a short one is ever down:
`https://github.com/sgcfhgcvdgh/CleanTV/releases/download/latest/CleanTV.apk`
