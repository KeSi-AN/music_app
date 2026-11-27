"""How many of the user's songs have (synced) lyrics on LRCLIB?"""
import json, urllib.parse, urllib.request
UA = {"User-Agent": "Riyaz/0.1 (personal music app)"}
SONGS = [("Uska Hi Bana", "Arijit Singh"), ("Kaa Karoon Sajni", "Somlata"), ("Naina Morey", "Somlata"),
         ("Shaayar", "Seedhe Maut"), ("Buttabomma", "Armaan Malik"), ("Vellake", "Anirudh"),
         ("Ninnu Chuse Anandamlo", "Sid Sriram"), ("Seethakaalam", "Yazin Nizar"), ("Uppenantha", "KK")]
def get(url):
    with urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=20) as r:
        return json.load(r)
for title, artist in SONGS:
    q = urllib.parse.urlencode({"track_name": title, "artist_name": artist})
    res = get("https://lrclib.net/api/search?" + q)
    if not res:
        res = get("https://lrclib.net/api/search?" + urllib.parse.urlencode({"q": f"{title} {artist}"}))
    synced = [r for r in res if r.get("syncedLyrics")]
    best = (synced or res or [None])[0]
    if best:
        first = (best.get("syncedLyrics") or best.get("plainLyrics") or "").splitlines()[:2]
        print(f"{title:24s} {len(res):2d} hits, {len(synced):2d} synced | {best['trackName']} - {best['artistName']} ({best.get('duration')}s) | {first}")
    else:
        print(f"{title:24s} nothing")
