/* StreamBox API — Cloudflare Worker (native-track backend).
   Jobs:
     1. anime-map: TMDB->MAL/AniList mapping, computed SERVER-SIDE so user
        networks that cannot reach Jikan/Consumet still resolve (the site's
        #1 mapping pain). Chain: AniList GraphQL -> Kitsu (explicit
        MAL/AniList mappings) -> Jikan, with the same year-aware scoring
        validated against Doraemon 2005 = 8687 and FMA:B = 5114.
     2. tmdb proxy: api.themoviedb.org with the key coming from a Wrangler
        SECRET — the front end stops shipping key shards entirely.
   Provider keys are NEVER in this file and never returned in responses.
   State: Workers Cache API only (no KV/D1 to provision).
   Cache TTLs: mapping 24h (stable), TMDB GETs 1h. */
export default {
  async fetch(request, env, ctx) {
    try {
      return await route(request, env);
    } catch (e) {
      return json({ error: "worker_error", detail: String((e && e.message) || e) }, 502);
    }
  }
};

const ALLOWED = [
  "https://dazoarmando29.github.io",
  "http://localhost:8788",
  "http://127.0.0.1:8788",
];
// Some mapping hosts (AniList) 403 the default Workers fetch signature;
// identify as the app and speak browser-ish accept headers.
const UA = "StreamBox/1.0 (+https://github.com/dazoarmando29/streambox)";

function corsHeaders(origin) {
  const allow = origin && ALLOWED.includes(origin) ? origin : ALLOWED[0];
  return {
    "access-control-allow-origin": allow,
    "access-control-allow-methods": "GET, OPTIONS",
    "access-control-allow-headers": "content-type",
    "access-control-max-age": "86400",
    vary: "origin",
  };
}

function json(body, status, origin) {
  return new Response(JSON.stringify(body), {
    status: status || 200,
    headers: {
      "content-type": "application/json; charset=utf-8",
      "cache-control": "no-store",
      ...corsHeaders(origin),
    },
  });
}

async function route(request, env) {
  const origin = request.headers.get("origin") || "";
  if (request.method === "OPTIONS") {
    return new Response(null, { status: 204, headers: corsHeaders(origin) });
  }
  if (request.method !== "GET") return json({ error: "method_not_allowed" }, 405, origin);

  const url = new URL(request.url);
  if (url.pathname === "/healthz") return json({ ok: true, name: "streambox-api" }, 200, origin);
  if (url.pathname === "/api/anime-map") return animeMap(url, env, origin);
  if (url.pathname === "/api/anime/genres") {
    return json({ data: ANIME_GENRES.map((g) => ({ id: g, name: g })) }, 200, origin);
  }
  if (url.pathname === "/api/anime/browse") return animeBrowse(url, env, origin);
  if (url.pathname === "/api/wyz/search") return wyzSearch(url, env, origin);
  if (url.pathname === "/api/bs-subs") return bsSubs(url, env, origin);
  if (url.pathname.startsWith("/api/tmdb/")) return tmdbProxy(url, env, request, origin);
  return json({ error: "not_found" }, 404, origin);
}

/* ---------- AniList genre browse (0.40) ----------
   The Anime area browses with ANILIST's genre taxonomy (Romance, Slice of
   Life, Mecha, ...) - TMDB's TV genres have no Romance at all. Rows come
   from AniList GraphQL and are resolved to TMDB tv ids SERVER-SIDE (TMDB
   search by title + year) so the app keeps one catalog surface and every
   detail/season/playback path stays intact. Titles TMDB cannot resolve
   are DROPPED, never faked. */
const ANIME_GENRES = [
  "Action", "Adventure", "Comedy", "Drama", "Fantasy", "Horror",
  "Mahou Shoujo", "Mecha", "Music", "Mystery", "Psychological",
  "Romance", "Sci-Fi", "Slice of Life", "Sports", "Supernatural", "Thriller",
];

async function animeBrowse(url, env, origin) {
  const genre = (url.searchParams.get("genre") || "").trim();
  const top = url.searchParams.get("top") === "1";
  if (!ANIME_GENRES.includes(genre)) return json({ error: "bad_genre" }, 400, origin);

  const key = cacheKey("anime-browse", { genre, top: top ? "1" : "0" });
  const cached = await cacheGet(key);
  if (cached) return json({ ...cached, cached: true }, 200, origin);

  // Normalized rows {name, year, poster, backdrop, overview, vote} - then
  // ONE TMDB-resolution path. AniList primary, Kitsu fallback (its genre
  // slugs mirror the AniList taxonomy exactly, so the chips never change).
  let media = [];
  let source = "anilist";
  try {
    media = await anilistGenreBrowse(genre, top);
    if (!media.length) throw new Error("empty");
  } catch (e) {
    source = "kitsu";
    try {
      media = await kitsuGenreBrowse(genre, top);
    } catch (e2) {
      return json(
        {
          error: "genre_browse_failed",
          detail: String((e2 && e2.message) || e2),
          anilist_detail: String((e && e.message) || e),
        },
        502,
        origin
      );
    }
  }

  // Dedupe by TMDB id: season-split entries (Code Geass + R2) can resolve
  // to the same TMDB show - one card per title, never doubles.
  const seen = new Set();
  const rows = (
    await Promise.all(
      media.slice(0, 20).map(async (m) => {
        const tmdbId = await tmdbTvResolve(m.name, m.year, env);
        if (!tmdbId) return null;
        return {
          id: tmdbId,
          type: "anime",
          name: m.name,
          poster: m.poster || null,
          backdrop: m.backdrop || null,
          year: m.year || 0,
          overview: m.overview || "",
          vote: m.vote || 0,
          adult: !!m.adult,
          genreIds: [16],
          lang: "ja",
        };
      })
    )
  )
    .filter(Boolean)
    .filter((r) => (seen.has(r.id) ? false : (seen.add(r.id), true)));

  const out = { data: rows, requested: media.length, resolved: rows.length, source };
  cachePut(key, out, 3600);
  return json(out, 200, origin);
}

/* AniList GraphQL genre browse (primary carrier). */
async function anilistGenreBrowse(genre, top) {
  const query = `query ($g: String, $s: [MediaSort]) {
    Page(page: 1, perPage: 20) {
      media(type: ANIME, genre_in: [$g], sort: $s, isAdult: false) {
        id
        title { romaji english }
        coverImage { extraLarge large }
        bannerImage
        description
        averageScore
        startDate { year }
      }
    }
  }`;
  const res = await fetch("https://graphql.anilist.co", {
    method: "POST",
    headers: ANILIST_HEADERS,
    body: JSON.stringify({
      query,
      variables: {
        g: genre,
        s: top ? ["SCORE_DESC", "POPULARITY_DESC"] : ["POPULARITY_DESC"],
      },
    }),
    signal: AbortSignal.timeout(10000),
  });
  if (!res.ok) throw new Error("anilist_http_" + res.status);
  const data = await res.json();
  const media = (((data || {}).data || {}).Page || {}).media || [];
  return media.map((m) => ({
    name: ((m.title && (m.title.english || m.title.romaji)) || "").trim(),
    year: (m.startDate && m.startDate.year) || 0,
    poster: (m.coverImage && (m.coverImage.extraLarge || m.coverImage.large)) || null,
    backdrop: m.bannerImage || null,
    overview: stripTags(m.description || ""),
    vote: m.averageScore ? Math.round(m.averageScore) / 10 : 0,
  }));
}

/* Kitsu fallback: genre slugs mirror AniList's taxonomy 1:1 (sci-fi,
   slice-of-life, mahou-shoujo, ...) so the same chips keep working when
   AniList 403s the Workers fetch signature. Kitsu has no nsfw filter
   param (it 400s) - R-rated rows are marked adult and ride the app's
   adult gate instead. */
async function kitsuGenreBrowse(genre, top) {
  const slug = genre.toLowerCase().replace(/\s+/g, "-");
  const q = new URLSearchParams({
    "filter[genres]": slug,
    // popularityRank is a RANKING POSITION (lower = more popular), so the
    // popular sort is ascending; -averageRating is a plain score.
    sort: top ? "-averageRating" : "popularityRank",
    "page[limit]": "20",
  });
  const res = await fetch("https://kitsu.io/api/edge/anime?" + q.toString(), {
    headers: { accept: "application/vnd.api+json", "user-agent": UA },
    signal: AbortSignal.timeout(10000),
  });
  if (!res.ok) throw new Error("kitsu_http_" + res.status);
  const data = await res.json();
  return (data.data || [])
    .filter((x) => x && x.attributes)
    .map((x) => ({
      name: String(
        x.attributes.canonicalTitle ||
          (x.attributes.titles && (x.attributes.titles.en_jp || x.attributes.titles.en)) ||
          ""
      ).trim(),
      year: (x.attributes.startDate && parseInt(String(x.attributes.startDate).slice(0, 4), 10)) || 0,
      poster: (x.attributes.posterImage && (x.attributes.posterImage.large || x.attributes.posterImage.medium)) || null,
      backdrop: (x.attributes.coverImage && (x.attributes.coverImage.large || x.attributes.coverImage.original)) || null,
      overview: stripTags(x.attributes.synopsis || ""),
      vote: x.attributes.averageRating ? Math.round(parseFloat(x.attributes.averageRating)) / 10 : 0,
      adult: String(x.attributes.ageRating || "") === "R",
    }))
    .filter((x) => x.name);
}

/* Browser-ish headers for AniList: the Workers fetch signature gets 403d
   without them (seen live again at 0.40). */
const ANILIST_HEADERS = {
  "content-type": "application/json",
  accept: "application/json",
  "user-agent":
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36",
  origin: "https://anilist.co",
  referer: "https://anilist.co/",
  "accept-language": "en-US,en;q=0.9",
};

/* TMDB tv-id resolution for an AniList title: search by title, prefer the
   year match, then the exact normalized name, then first. */
async function tmdbTvResolve(name, year, env) {
  const key = env && env.TMDB_API_KEY;
  if (!key || !name) return null;
  const norm = (s) => String(s || "").toLowerCase().replace(/[^a-z0-9]+/g, " ").trim();
  const want = norm(name);
  const tryFind = async (useYear) => {
    const q = new URLSearchParams({ api_key: key, query: name });
    if (useYear && year) q.set("first_air_date_year", String(year));
    const res = await fetch("https://api.themoviedb.org/3/search/tv?" + q.toString(), {
      headers: { accept: "application/json" },
      signal: AbortSignal.timeout(8000),
    });
    if (!res.ok) return null;
    const data = await res.json();
    const results = (data && data.results) || [];
    if (!results.length) return null;
    const byYear =
      useYear && year
        ? results.filter((r) => String(r.first_air_date || "").startsWith(String(year)))
        : results;
    const pool = byYear.length ? byYear : results;
    const exact = pool.find((r) => norm(r.name) === want) || results.find((r) => norm(r.name) === want);
    const pick = exact || pool[0];
    return pick && pick.id ? pick.id : null;
  };
  try {
    return (await tryFind(true)) || (await tryFind(false));
  } catch (e) {
    return null;
  }
}

function stripTags(s) {
  return String(s || "")
    .replace(/<br\s*\/?>/gi, " ")
    .replace(/<\/?[^>]+>/g, "")
    .replace(/&amp;/g, "&")
    .replace(/&lt;/g, "<")
    .replace(/&gt;/g, ">")
    .replace(/&quot;/g, '"')
    .replace(/&#0?39;/g, "'")
    .replace(/\s+/g, " ")
    .trim();
}

/* ---------- cache helpers (Workers Cache API; no bindings) ---------- */
const CACHE_VER = "v2";
function cacheKey(scope, params) {
  return new Request("https://sb-cache.internal/" + CACHE_VER + "/" + scope + "?" + new URLSearchParams(params));
}
async function cacheGet(key) {
  try {
    const hit = await caches.default.match(key);
    if (hit) return await hit.json();
  } catch (e) {}
  return null;
}
function cachePut(key, body, ttlSeconds, ctx) {
  try {
    const res = new Response(JSON.stringify(body), {
      headers: {
        "content-type": "application/json; charset=utf-8",
        "cache-control": "public, max-age=" + ttlSeconds,
      },
    });
    const p = caches.default.put(key, res);
    if (ctx && ctx.waitUntil) ctx.waitUntil(p);
  } catch (e) {}
}

/* ---------- anime mapping: Jikan -> AniList (server-side) ---------- */
async function animeMap(url, env, origin) {
  const title = (url.searchParams.get("title") || "").trim();
  const year = parseInt(url.searchParams.get("year") || "", 10) || 0;
  const tmdb = (url.searchParams.get("tmdb") || "").trim();
  if (!title) return json({ error: "title_required" }, 400, origin);

  const key = cacheKey("anime-map", { title: title.toLowerCase(), year: String(year) });
  const cached = await cacheGet(key);
  if (cached) return json({ ...cached, cached: true }, 200, origin);

  let best = null;
  let source = "";
  const errs = {};
  // Chain: AniList first (reliable carrier), then Kitsu (explicit
  // MAL/AniList mappings), then Jikan last (often unreachable). Each host
  // that fails is recorded so callers see exactly what happened.
  try {
    best = pickAnime(await anilistSearch(title), title, year);
    source = "anilist";
  } catch (e) {
    errs.anilist = String((e && e.message) || e);
  }
  if (!best) {
    try {
      best = pickAnime(await kitsuSearch(title), title, year);
      if (best) best = await kitsuMapIds(best);
      source = "kitsu";
    } catch (e) {
      errs.kitsu = String((e && e.message) || e);
    }
  }
  if (!best) {
    try {
      best = pickAnime(await jikanSearch(title), title, year);
      source = "jikan";
    } catch (e) {
      errs.jikan = String((e && e.message) || e);
    }
  }
  if (!best) return json({ error: "unmapped", title, sources: errs }, 502, origin);

  const out = {
    tmdb: tmdb || null,
    mal: best.mal || null,
    ani: best.ani || null,
    title: best.title,
    year: best.year || null,
    source,
  };
  cachePut(key, out, 86400);
  return json(out, 200, origin);
}

/* Kitsu: JSON:API catalog with explicit MAL/AniList mappings per anime. */
async function kitsuSearch(title) {
  const u = "https://kitsu.io/api/edge/anime?filter[text]=" + encodeURIComponent(title) + "&page[limit]=10";
  const res = await fetch(u, {
    headers: { accept: "application/vnd.api+json", "user-agent": UA },
    signal: AbortSignal.timeout(8000),
  });
  if (!res.ok) throw new Error("kitsu_http_" + res.status);
  const data = await res.json();
  return (data.data || [])
    .filter((x) => x && x.id)
    .map((x) => ({
      kitsuId: x.id,
      mal: null,
      ani: null,
      title: String((x.attributes && (x.attributes.canonicalTitle || (x.attributes.titles && x.attributes.titles.en_jp))) || "").trim(),
      year: (x.attributes && x.attributes.startDate && parseInt(String(x.attributes.startDate).slice(0, 4), 10)) || 0,
    }));
}

async function kitsuMapIds(row) {
  if (!row || !row.kitsuId) return row;
  try {
    const res = await fetch("https://kitsu.io/api/edge/anime/" + encodeURIComponent(row.kitsuId) + "/mappings?limit=20", {
      headers: { accept: "application/vnd.api+json", "user-agent": UA },
      signal: AbortSignal.timeout(8000),
    });
    if (!res.ok) return row;
    const data = await res.json();
    for (const m of (data.data || [])) {
      const site = String((m.attributes && m.attributes.externalSite) || "").toLowerCase();
      const ext = m.attributes && m.attributes.externalId;
      if (!ext) continue;
      if (!row.mal && site.includes("myanimelist")) row.mal = ext;
      else if (!row.ani && site.includes("anilist")) row.ani = ext;
    }
  } catch (e) {}
  return row;
}

async function jikanSearch(title) {
  const u = "https://api.jikan.moe/v4/anime?q=" + encodeURIComponent(title) + "&limit=10&sfw=true";
  const res = await fetch(u, {
    headers: { accept: "application/json", "user-agent": UA },
    signal: AbortSignal.timeout(10000),
  });
  if (!res.ok) throw new Error("jikan_http_" + res.status);
  const data = await res.json();
  return (data.data || [])
    .filter((x) => x && x.mal_id)
    .map((x) => ({
      mal: x.mal_id,
      ani: null,
      title: (x.title_english || x.title || "").trim(),
      year: (x.year || ((x.aired && x.aired.prop && x.aired.prop.from && x.aired.prop.from.year) || 0)) || 0,
    }));
}

async function anilistSearch(title) {
  const query = `query ($s: String, $n: Int) {
    Page(perPage: $n) {
      media(search: $s, type: ANIME) {
        id idMal
        title { romaji english }
        startDate { year }
      }
    }
  }`;
  const res = await fetch("https://graphql.anilist.co", {
    method: "POST",
    headers: ANILIST_HEADERS,
    body: JSON.stringify({ query, variables: { s: title, n: 25 } }),
    signal: AbortSignal.timeout(8000),
  });
  if (!res.ok) throw new Error("anilist_http_" + res.status);
  const data = await res.json();
  const media = (((data || {}).data || {}).Page || {}).media || [];
  return media
    .filter((x) => x && x.id)
    .map((x) => ({
      mal: x.idMal || null,
      ani: x.id,
      title: ((x.title && (x.title.english || x.title.romaji)) || "").trim(),
      year: (x.startDate && x.startDate.year) || 0,
    }));
}

/* Year-aware scoring: the YEAR gates first (it separates same-named
   entries like Doraemon 1979 vs 2005), then title match breaks ties --
   exact, then contained, then first. Validated: Doraemon 2005 -> 8687,
   FMA:B -> 5114, Dragon Ball -> 223, Dragon Ball Z -> 813. */
function pickAnime(list, title, year) {
  if (!list || !list.length) return null;
  const norm = (s) => String(s || "").toLowerCase().replace(/[^a-z0-9]+/g, " ").trim();
  const want = norm(title);
  let pool = list;
  if (year) {
    const same = pool.filter((x) => x.year === year);
    if (same.length) pool = same;
    else {
      const near = [...pool].sort(
        (a, b) => Math.abs((a.year || 9999) - year) - Math.abs((b.year || 9999) - year)
      );
      if (near.length && Math.abs((near[0].year || 9999) - year) <= 2) pool = near;
    }
  }
  const exact = pool.filter((x) => norm(x.title) === want);
  if (exact.length) return exact[0];
  const contains = pool.filter((x) => {
    const t = norm(x.title);
    return t && (t.includes(want) || want.includes(t));
  });
  return (contains.length ? contains[0] : pool[0]) || null;
}

/* Wyzie subtitle search proxy: the shared key lives in a Wrangler secret,
   never in the APK or the front end. Files download straight from the
   URLs in the results (public once listed). */
async function wyzSearch(url, env, origin) {
  const key = env && (env.WYZIE_API_KEY || env.WYZIE_KEY);
  if (!key) return json({ error: "wyz_key_not_configured" }, 503, origin);

  const params = new URLSearchParams(url.search);
  const id = params.get("id") || "";
  const language = (params.get("language") || "en").toLowerCase();
  if (!/^\d+$/.test(id)) return json({ error: "bad_id" }, 400, origin);
  if (!/^[a-z]{2,3}$/.test(language)) return json({ error: "bad_language" }, 400, origin);

  const q = new URLSearchParams({
    id,
    language,
    format: "srt",
    limit: "25",
    key,
  });
  const season = params.get("season");
  const episode = params.get("episode");
  if (season && /^\d+$/.test(season)) q.set("season", season);
  if (episode && /^\d+$/.test(episode)) q.set("episode", episode);

  const target = "https://sub.wyzie.io/search?" + q.toString();
  const res = await fetch(target, {
    headers: { accept: "application/json", "user-agent": UA },
    signal: AbortSignal.timeout(10000),
  });
  const body = await res.text();
  return new Response(body, {
    status: res.status,
    headers: {
      "content-type": "application/json; charset=utf-8",
      "cache-control": "no-store",
      ...corsHeaders(origin),
    },
  });
}

/* BetaSeries backup subtitles (TV only): the whole tvdb->show->episode->
    subtitles chain runs here so the API key stays a Worker secret and the
    client gets one small normalized response. Backup #2 behind TSDB. */
async function bsSubs(url, env, origin) {
  const key = env && (env.BETASERIES_API_KEY || env.BETASERIES_KEY);
  if (!key) return json({ error: "bs_key_not_configured" }, 503, origin);
  const tkey = env && env.TMDB_API_KEY;
  if (!tkey) return json({ error: "tmdb_key_not_configured" }, 503, origin);

  const params = new URLSearchParams(url.search);
  const tmdb = params.get("tmdb") || "";
  const season = params.get("season") || "";
  const episode = params.get("episode") || "";
  const lang = (params.get("lang") || "en").toLowerCase();
  if (!/^\d+$/.test(tmdb)) return json({ error: "bad_tmdb" }, 400, origin);
  if (!/^\d+$/.test(season) || !/^\d+$/.test(episode)) return json({ error: "bad_episode" }, 400, origin);
  if (!/^[a-z]{2,3}$/.test(lang)) return json({ error: "bad_language" }, 400, origin);

  // 1. TMDB -> TheTVDB id
  const extRes = await fetch(
    "https://api.themoviedb.org/3/tv/" + tmdb + "/external_ids?api_key=" + tkey,
    { signal: AbortSignal.timeout(8000) }
  );
  if (!extRes.ok) return json({ error: "tmdb_external_ids_failed" }, 502, origin);
  const ext = await extRes.json();
  const tvdb = ext && (ext.tvdb_id || ext.tvdbId);
  if (!tvdb) return json({ error: "no_tvdb" }, 404, origin);

  const bs = async (path, ps) => {
    const q = new URLSearchParams({ v: "3.0", key });
    for (const [k, v] of Object.entries(ps)) q.set(k, v);
    const res = await fetch("https://api.betaseries.com" + path + "?" + q.toString(), {
      headers: { accept: "application/json", "user-agent": UA },
      signal: AbortSignal.timeout(8000),
    });
    if (!res.ok) throw new Error("bs_http_" + res.status);
    return res.json();
  };

  try {
    const sh = await bs("/shows/display", { thetvdb_id: String(tvdb) });
    const show = sh && sh.show;
    if (!show || !show.id) return json({ error: "not_listed" }, 404, origin);
    const se = await bs("/shows/episodes", { id: String(show.id), season });
    const ep = (((se && se.episodes) || []).find((e) => Number(e.episode) === Number(episode)) || null);
    if (!ep || !ep.id) return json({ error: "episode_not_listed" }, 404, origin);
    const su = await bs("/subtitles/episode", { id: String(ep.id) });
    const list = ((su && su.subtitles) || []).filter((s) => s && s.id);

    // rank: filename language tokens + quality (same recipe as the site)
    const TOK = {
      en: ["english", ".en.", "eng"], es: ["spanish", "espanol", ".es."],
      fr: ["french", "francais", ".fr.", "vostfr"], de: ["german", "deutsch", ".de."],
      ar: ["arabic", ".ar."], hi: ["hindi", ".hi."], tl: ["tagalog", ".tl."],
      pt: ["portuguese", ".pt."], it: ["italian", ".it."], nl: ["dutch", ".nl."],
      ru: ["russian", ".ru."], tr: ["turkish", ".tr."], id: ["indonesian", ".id."],
    }[lang] || [];
    const score = (s) => {
      const f = String((s && s.file) || "").toLowerCase();
      let sc = Number(s.quality) || 0;
      if (TOK.some((t) => f.includes(t))) sc += 10;
      return sc;
    };
    const rows = list
      .sort((a, b) => score(b) - score(a))
      .map((s) => ({
        file: s.file || ("BetaSeries #" + s.id),
        language: s.language || lang.toUpperCase(),
        source: s.source || "BetaSeries",
        quality: s.quality || 0,
        url: "https://www.betaseries.com/srt/" + s.id,
      }));
    return json({ data: rows }, 200, origin);
  } catch (e) {
    return json({ error: "bs_failed", detail: String((e && e.message) || e) }, 502, origin);
  }
}

/* ---------- TMDB proxy: key stays in a Wrangler secret ---------- */
async function tmdbProxy(url, env, request, origin) {
  const rel = url.pathname.slice("/api/tmdb/".length);
  if (!/^[\w./-]+$/.test(rel)) return json({ error: "bad_path" }, 400, origin);
  const key = env && env.TMDB_API_KEY;
  if (!key) return json({ error: "tmdb_key_not_configured" }, 503, origin);

  const params = new URLSearchParams(url.search);
  params.delete("api_key");
  params.set("api_key", key);
  const target = "https://api.themoviedb.org/" + rel + "?" + params.toString();
  params.delete("api_key");

  const cacheable = !params.has("append_to_response");
  const keyCache = cacheKey("tmdb", { rel, q: params.toString() });
  if (cacheable) {
    const hit = await cacheGet(keyCache);
    if (hit) return json({ ...hit, cached: true }, 200, origin);
  }

  const res = await fetch(target, {
    headers: { accept: "application/json" },
    signal: AbortSignal.timeout(8000),
  });
  const body = await res.text();
  if (res.ok && cacheable) {
    let parsed = null;
    try { parsed = JSON.parse(body); } catch (e) {}
    if (parsed) {
      cachePut(keyCache, parsed, 3600);
      return json(parsed, 200, origin);
    }
  }
  return new Response(body, {
    status: res.status,
    headers: { "content-type": "application/json; charset=utf-8", "cache-control": "no-store", ...corsHeaders(origin) },
  });
}
